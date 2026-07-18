"""
Legacy KB article importer — one-shot migration tool, not part of the running app.

Source data reality check (see inline notes below for why this isn't a naive
BeautifulSoup-grab-and-store job):

  * Files are SingleFile-extension exports of Google Sites pages, not plain
    authored HTML. Each one is ~5-8MB on disk.
  * Every <img> is a base64 `data:image/...` URI — there are no relative
    image paths and no sibling `_files/` folders in this dataset. Storing
    that inline would put megabytes of base64 text into a single `articles.
    content` row; this script decodes and writes each image once (content-
    hashed, so duplicates across articles are stored only once) to
    UPLOAD_DIR/legacy_import/ and rewrites <img src> to /uploads/... ,
    matching the /uploads/ rewrite convention already used by main.py's CSP
    comment for previously-migrated content.
  * The actual visible text+images sit under 200-300+ levels of Google-Sites
    wrapper <div>s with auto-generated, non-portable class names (e.g.
    "fktJzd", "QZ3zWd") that differ per export and carry no semantic value.
    soup.find(role="main") only captures the page heading, not the body.
    There is no stable CSS selector to "grab innerHTML" from. Instead this
    script walks the DOM in document order and rebuilds a minimal, clean
    <p>/<img> body from the actual text and image nodes, discarding the
    wrapper noise entirely.
  * Two of the three source roots have ~40 overlapping filenames (repeated
    exports of the same page) — dedup is keyed on normalized title across
    *all* source roots in a single run, not just "does a DB row exist".

Run modes:
    python scripts/migrate_legacy_html.py                       # dry run (default, no writes)
    python scripts/migrate_legacy_html.py --commit --author-id 1  # actually writes

This script intentionally does not import or modify main.py / models.py /
schemas.py logic — it only imports the Article/Category ORM classes to
construct rows the same way the app itself would.
"""
import argparse
import base64
import hashlib
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from bs4 import BeautifulSoup, NavigableString

from config import settings
from database import SessionLocal
import models

# ── Constants discovered by inspecting the actual source files ─────────────

DEFAULT_SOURCE_DIRS = [
    r"C:/Projects/Project_Trash/scripts/legacy/desk_html",
    r"C:/Projects/Project_Trash/scripts/legacy/old web base/Desk",
    r"C:/Projects/Project_Trash/scripts/legacy/old web base/MOB",
    r"C:/Projects/Project_Trash/scripts/legacy/old web base/tech",
]

# Every observed <title> is "Magti Call Center - <topic>"; the prefix carries
# no information and would otherwise be duplicated on every single row.
TITLE_PREFIX_RE = re.compile(r"^\s*Magti Call Center\s*-\s*", re.IGNORECASE)

# Valid Article.status values, per models.py / main.py visibility filters
# (_assert_article_visible and the list endpoints only recognize these three —
# anything else, e.g. 'active', is silently invisible everywhere in the app).
VALID_STATUSES = {"published", "scheduled", "archived"}

DATA_URI_RE = re.compile(r"^data:image/([\w+.-]+);base64,(.*)$", re.DOTALL)

EXT_BY_SUBTYPE = {
    "png": "png", "jpeg": "jpg", "jpg": "jpg", "gif": "gif",
    "webp": "webp", "svg+xml": "svg", "bmp": "bmp", "x-icon": "ico",
}

NON_CONTENT_TAGS = {"script", "style", "noscript", "svg", "link", "meta", "head", "iframe"}


def normalize_title(raw_title: str) -> str:
    title = TITLE_PREFIX_RE.sub("", raw_title or "").strip()
    title = re.sub(r"\s+", " ", title)
    return title


def dedup_key(title: str) -> str:
    return title.strip().lower()


def guess_audience_profile(path: str) -> str:
    return "tech" if "tech" in path.lower() else "all"


def guess_target_department(path: str) -> str:
    lower = path.lower()
    if "mob" in lower:
        return "Mobile"
    return "All"


def extract_images_and_clean_body(soup: BeautifulSoup, image_out_dir: Path,
                                   dry_run: bool, stats: dict) -> str:
    """Walks the document in order, pulling out text + <img> nodes only,
    decoding/saving base64 images as real files, and returns a small, clean
    HTML fragment — discarding the Google-Sites wrapper-div soup entirely."""
    body = soup.body or soup
    for tag in body.find_all(NON_CONTENT_TAGS):
        tag.decompose()
    # Google Sites duplicates some text for screen readers in visually-hidden
    # spans; drop those so paragraphs aren't doubled.
    for hidden in body.find_all(attrs={"aria-hidden": "true"}):
        hidden.decompose()

    parts = []
    buffer = []

    def flush():
        text = " ".join(buffer).strip()
        buffer.clear()
        if text:
            parts.append(f"<p>{text}</p>")

    seen_text_blocks = set()
    for node in body.descendants:
        if isinstance(node, NavigableString):
            text = str(node).strip()
            if not text:
                continue
            parent = node.parent.name if node.parent else ""
            if parent in NON_CONTENT_TAGS:
                continue
            buffer.append(text)
        elif getattr(node, "name", None) == "img":
            flush()
            src = node.get("src") or ""
            alt = (node.get("alt") or "").strip()
            new_src = resolve_image_src(src, image_out_dir, dry_run, stats)
            if new_src:
                parts.append(f'<img src="{new_src}" alt="{alt}">')
            else:
                stats["images_dropped"] += 1
        elif getattr(node, "name", None) == "br":
            buffer.append("\n")
    flush()

    deduped = []
    for p in parts:
        key = p.strip()
        if key in seen_text_blocks and p.startswith("<p>"):
            continue
        seen_text_blocks.add(key)
        deduped.append(p)
    return "\n".join(deduped)


def resolve_image_src(src: str, image_out_dir: Path, dry_run: bool, stats: dict):
    if not src:
        return None
    m = DATA_URI_RE.match(src)
    if m:
        subtype, b64data = m.group(1).lower(), m.group(2)
        ext = EXT_BY_SUBTYPE.get(subtype, "bin")
        try:
            raw = base64.b64decode(b64data, validate=False)
        except Exception:
            stats["images_dropped"] += 1
            return None
        digest = hashlib.sha256(raw).hexdigest()[:24]
        filename = f"{digest}.{ext}"
        dest = image_out_dir / filename
        stats["images_seen"] += 1
        if not dest.exists():
            stats["images_written"] += 1
            if not dry_run:
                image_out_dir.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(raw)
        else:
            stats["images_deduped"] += 1
        return f"/uploads/legacy_import/{filename}"
    if src.startswith("http://") or src.startswith("https://"):
        # External/absolute — CSP already allows https: for img-src, leave as-is.
        return src
    # Relative path referencing a sibling file that doesn't exist in this
    # dataset (no _files/ folders were found) — rather than emit a broken
    # <img>, drop it and let the caller count it.
    return None


def get_or_create_category(db, name: str, dry_run: bool):
    cat = db.query(models.Category).filter(models.Category.name == name).first()
    if cat:
        return cat
    cat = models.Category(name=name, is_active=True)
    if not dry_run:
        db.add(cat)
        db.flush()
    return cat


def collect_source_files(source_dirs):
    files = []
    for d in source_dirs:
        p = Path(d)
        if not p.exists():
            print(f"  [skip] source dir not found: {d}")
            continue
        files.extend(sorted(p.rglob("*.html")))
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dirs", nargs="*", default=DEFAULT_SOURCE_DIRS)
    parser.add_argument("--commit", action="store_true",
                         help="Actually write to the database/disk. Without this flag, "
                              "the script only parses and reports what it would do.")
    parser.add_argument("--author-id", type=int, default=None,
                         help="users.id to attribute imported articles to. Required with --commit.")
    parser.add_argument("--status", choices=sorted(VALID_STATUSES), default="archived",
                         help="Default: 'archived' so a human reviews/restores before these "
                              "go live to ~600 users. 'active' is NOT a valid status in this "
                              "schema and would make rows invisible everywhere.")
    parser.add_argument("--category-name", default="Legacy Import")
    parser.add_argument("--limit", type=int, default=None, help="Process at most N files (debugging).")
    parser.add_argument("--skip-backup", action="store_true",
                         help="Skip the automatic magti_portal.db backup before writing.")
    args = parser.parse_args()

    dry_run = not args.commit
    if not dry_run and args.author_id is None:
        parser.error("--author-id is required when using --commit")

    if not dry_run and not args.skip_backup and settings.is_sqlite:
        print("Creating a pre-migration backup (backup.create_backup)...")
        import backup as backup_module
        backup_module.create_backup()

    image_out_dir = Path(settings.UPLOAD_DIR) / "legacy_import"

    files = collect_source_files(args.source_dirs)
    if args.limit:
        files = files[: args.limit]
    print(f"Found {len(files)} source HTML files across {len(args.source_dirs)} root(s).")

    db = SessionLocal()
    existing_titles = {
        dedup_key(t) for (t,) in db.query(models.Article.title).all()
    }
    print(f"{len(existing_titles)} articles already exist in the DB (used for idempotency).")

    category = get_or_create_category(db, args.category_name, dry_run)

    stats = {
        "images_seen": 0, "images_written": 0, "images_deduped": 0, "images_dropped": 0,
        "parsed": 0, "skipped_duplicate": 0, "imported": 0, "errors": 0,
    }
    seen_this_run = set()

    for path in files:
        try:
            raw_html = path.read_text(encoding="utf-8", errors="replace")
            soup = BeautifulSoup(raw_html, "html.parser")
            raw_title = soup.title.get_text() if soup.title else path.stem
            title = normalize_title(raw_title)
            key = dedup_key(title)
            stats["parsed"] += 1

            if not title:
                print(f"  [error] empty title after normalization: {path.name}")
                stats["errors"] += 1
                continue

            if key in existing_titles or key in seen_this_run:
                stats["skipped_duplicate"] += 1
                continue

            content_html = extract_images_and_clean_body(soup, image_out_dir, dry_run, stats)
            if not content_html.strip():
                print(f"  [error] no extractable content: {path.name}")
                stats["errors"] += 1
                continue

            seen_this_run.add(key)

            if dry_run:
                print(f"  [would import] \"{title}\" "
                      f"({len(content_html)} chars, dept={guess_target_department(str(path))}, "
                      f"audience={guess_audience_profile(str(path))})")
            else:
                article = models.Article(
                    title=title,
                    content=content_html,
                    category_id=category.id,
                    target_department=guess_target_department(str(path)),
                    audience_profile=guess_audience_profile(str(path)),
                    author_id=args.author_id,
                    status=args.status,
                    visible_to_tech_info=True,
                    visible_to_service_center=False,
                )
                db.add(article)
                db.commit()
            stats["imported"] += 1
        except Exception as exc:
            stats["errors"] += 1
            print(f"  [error] {path.name}: {exc}")
            if not dry_run:
                db.rollback()

    db.close()

    print("\n--- Summary ---")
    print(f"Mode:               {'DRY RUN (no writes)' if dry_run else 'COMMITTED'}")
    print(f"Files parsed:       {stats['parsed']}")
    print(f"Skipped (dupes):    {stats['skipped_duplicate']}")
    print(f"Imported:           {stats['imported']}")
    print(f"Errors:             {stats['errors']}")
    print(f"Images seen:        {stats['images_seen']}")
    print(f"Images written:     {stats['images_written']}")
    print(f"Images deduped:     {stats['images_deduped']} (identical content already on disk)")
    print(f"Images dropped:     {stats['images_dropped']} (broken/unsupported src)")
    if dry_run:
        print("\nThis was a dry run — nothing was written. Re-run with --commit --author-id <id> to apply.")


if __name__ == "__main__":
    main()
