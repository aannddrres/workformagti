"""
migrate_kb.py — one-off migration of the legacy Google Sites Knowledge Base
into the Magti portal's `articles` table.

Source : ./old web base/{Desk, MOB, tech}/**/*.html   (messy Google Sites exports)
Target : Article rows via database.SessionLocal
Images : base64 `data:` images in the source are decoded and written to
         ./uploads/<sha256>.<ext> (content-addressed → automatic dedup across
         pages AND across re-runs); the <img src=...> is rewritten to
         /uploads/<sha256>.<ext>.

SAFE & RE-RUNNABLE: skips any article whose cleaned title already exists in the
DB or was already inserted this run, so duplicate timestamped/non-timestamped
source files collapse into one row. Image extraction is idempotent — re-running
detects existing files by content hash and never duplicates.

    pip install beautifulsoup4
    python migrate_kb.py --dry-run         # preview only — writes nothing
    python migrate_kb.py                   # insert for real
    python migrate_kb.py --clean-orphans   # delete UUID-named orphan images first
"""
import os
import re
import sys
import glob
import base64
import hashlib
import argparse
from collections import Counter

import urllib.parse
import mimetypes
import requests
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from bs4 import BeautifulSoup
from sqlalchemy import text

from database import SessionLocal, engine
from models import Base, User, Category, Article

# ── Configuration ─────────────────────────────────────────────────────────
SOURCE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "old web base")
UPLOAD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "uploads")
os.makedirs(UPLOAD_DIR, exist_ok=True)

# Tags removed entirely, with their contents.
REMOVE_TAGS = ["script", "style", "svg", "noscript",
               "nav", "header", "footer", "button"]

# Only these attributes survive, on these tags; everything else is stripped.
KEEP_ATTRS = {
    "a": ["href"],
    "img": ["src"],
    "iframe": ["src", "width", "height", "allow", "allowfullscreen", "frameborder"],
    "video": ["src", "controls", "poster", "width", "height"],
    "table": ["class"],
    "th": ["class"],
    "td": ["class"]
}

ALLOWED_IFRAME_SRC = [
    r"^https?://www\.youtube\.com/embed/",
    r"^https?://drive\.google\.com/file/d/"
]

ALLOWED_ATTACHMENT_DOMAINS = {"drive.google.com", "docs.google.com"}

TABLE_TAILWIND_CLASSES = "table-auto w-full border-collapse"
# Elements with these ARIA roles are chrome, not content (Google Sites nav,
# header, footer, zoom-buttons, etc.). NOTE: 'presentation', 'img', 'link',
# 'heading', 'main' are intentionally EXCLUDED — they carry real content
# (Google Sites puts body paragraphs in <p role="presentation">).
REMOVE_ROLES = {"banner", "navigation", "search", "complementary", "contentinfo",
                "button", "region", "dialog", "menu", "menubar", "toolbar", "tablist"}

# data: URI → file extension. Anything not in this map gets dropped.
EXT_BY_MIME = {
    "image/png":      ".png",
    "image/jpeg":     ".jpg",
    "image/jpg":      ".jpg",
    "image/gif":      ".gif",
    "image/webp":     ".webp",
    "image/svg+xml":  ".svg",
}
DATA_URI_RE = re.compile(r"^data:([^;,]+);base64,(.+)$", re.S)
# Strict UUIDv4-ish file name (the regressed UUID image-export pattern).
UUID_FILE_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(png|jpg|jpeg|gif|webp|svg)$",
    re.I,
)

# Category priority: the FIRST category with a keyword hit wins. Keywords are
# lowercase (matched against lowercased title or text). Tune freely.
CATEGORY_KEYWORDS = [
    ("ბილინგი",         ["გადახდ", "თანხ", "გადარიცხვ", "კრედიტ", "დავალიანებ",
                          "ანაზღაურ", "ბილინგ", "გადასახად", "ვადაგადაცილებ",
                          "გადაფორმებ", "განვადებ", "ანგარიშის შევსებ"]),
    ("ლოიალობა",        ["ქულებ", "ბონუს", "ლოიალ", "ქეშბექ", "cashback",
                          "მაგთი ქარდი", "magti card", "დაბადების დღ"]),
    ("როუმინგი",        ["როუმინგ", "როუმერ", "ტურისტ", "გლობალ ერთი"]),
    ("IPTV",            ["iptv", "tv play", "არხ", "ტელევიზ", "პულტ",
                          "სეტ-ტოპ", "set-top"]),
    ("ინტერნეტი",       ["ოპტიკ", "ინტერნეტ", "ftth", "fttb", "isp", "სიჩქარ",
                          "ჰოსტინგ", "დომენ", "wi-fi", "wifi", "ბუსტ", "boost", "voip"]),
    ("მობილური",        ["მობილურ", "sim", "esim", "ნომრ", "sms", " ზარ", "წუთ",
                          "voice mail", "callback", "გადმორეკ", "112"]),
    ("სერვისის მართვა", ["სერვის", "გააქტიურ", "გათიშვ", "ჩართვ", "შეჩერებ",
                          "რეაქტივ", "ტერმინაც", "სტატუს", "მართვ", "პაუზ", "სეზონ"]),
    # "საინფორმაციო (Desk)" is the default / catch-all (no keywords needed).
]
DEFAULT_CATEGORY = "საინფორმაციო (Desk)"
ALL_CATEGORY_NAMES = [name for name, _ in CATEGORY_KEYWORDS] + [DEFAULT_CATEGORY]

# Department routing (checked in this order; first hit wins).
SUPPORT_KEYWORDS = ["როუტერ", "კონფიგურაცი", "ინსტალაცი", "ხარვეზ", "დაზიანებ",
                    "მოდემ", "ემულატორ", "აპარატურ", "პულტ", "ინსტრუქცი",
                    "ftth", "fttb"]
ALL_KEYWORDS = ["აქცი", "როუმინგ", "პორტირებ", "შეთავაზებ"]

TITLE_PREFIXES = ["Magti Call Center -", "Magti Call Center –", "ISP ", "MOB ", "Mob "]
# Snapshot suffix in filenames, e.g. " (6_12_2026 4：29：44 PM)" (full-width colons).
TIMESTAMP_RE = re.compile(r"\s*\(\d{1,2}[_/／]\d{1,2}[_/／]\d{2,4}[^)]*\)\s*$")

# Run-wide image extraction stats (reset in main()).
IMG_STATS = Counter()
# Set by main() before sanitization runs; gates side-effect writes to uploads/.
DRY_RUN = False


def clean_title(raw: str) -> str:
    t = (raw or "").strip()
    t = TIMESTAMP_RE.sub("", t)                 # drop snapshot timestamp (filename case)
    for p in TITLE_PREFIXES:
        if t.startswith(p):
            t = t[len(p):].strip()
    return re.sub(r"\s+", " ", t).strip(" -–—")


def extract_title(soup, filename: str) -> str:
    if soup.title and soup.title.get_text(strip=True):
        return clean_title(soup.title.get_text())
    for tag in ("h1", "h2"):
        el = soup.find(tag)
        if el and el.get_text(strip=True):
            return clean_title(el.get_text())
    return clean_title(os.path.splitext(os.path.basename(filename))[0])


def find_content(soup):
    """Locate the real article body.

    These are New Google Sites exports where role="main" holds only the page
    TITLE; the body content lives in div[jsname="ZBtY8b"]. Fall back to the
    single largest text block, then to <body>.
    """
    node = soup.find("div", attrs={"jsname": "ZBtY8b"})
    if node and node.get_text(strip=True):
        return node
    blocks = soup.find_all(["section", "article", "main", "div"])
    best = max(blocks, key=lambda e: len(e.get_text(strip=True)), default=None)
    return best or soup.body


def _persist_data_uri(src: str):
    """Decode a base64 data: URI, write to uploads/<sha256>.<ext>, return URL.

    Returns the new public path ("/uploads/<hash>.<ext>") or None if the URI is
    malformed / unsupported. Content-addressed naming dedups identical images
    across pages and across re-runs (an existing file is left untouched).
    """
    m = DATA_URI_RE.match(src.strip())
    if not m:
        return None
    mime = m.group(1).strip().lower()
    ext = EXT_BY_MIME.get(mime)
    if ext is None:
        IMG_STATS["unsupported_mime"] += 1
        return None
    try:
        # validate=False tolerates whitespace inside the base64 payload.
        blob = base64.b64decode(m.group(2), validate=False)
    except Exception:
        IMG_STATS["decode_failed"] += 1
        return None
    if not blob:
        IMG_STATS["empty"] += 1
        return None
    digest = hashlib.sha256(blob).hexdigest()
    fname = f"{digest}{ext}"
    fpath = os.path.join(UPLOAD_DIR, fname)
    if os.path.exists(fpath):
        IMG_STATS["reused"] += 1   # already present from a sibling page or a prior run
    elif not DRY_RUN:
        with open(fpath, "wb") as f:
            f.write(blob)
        IMG_STATS["written"] += 1
    else:
        # Dry-run: count what WOULD be written, but don't touch the FS.
        IMG_STATS["would_write"] += 1
    return f"/uploads/{fname}"

def download_external(url: str) -> str | None:
    """Download a file from an allowed external URL and store it in uploads/.
    Returns the public /uploads/... path or None on failure.
    """
    try:
        resp = requests.get(url, timeout=10, stream=True)
        resp.raise_for_status()
    except Exception:
        return None
    # Enforce 10MB size limit
    MAX_SIZE = 10 * 1024 * 1024
    # Check Content-Length header if present
    cl = resp.headers.get('Content-Length')
    if cl is not None:
        try:
            if int(cl) > MAX_SIZE:
                return None
        except ValueError:
            pass
    # Read content and verify size
    content = resp.content
    if len(content) > MAX_SIZE:
        return None
    # Determine extension
    mime = resp.headers.get('Content-Type', '').split(';')[0]
    ext = mimetypes.guess_extension(mime) or os.path.splitext(url)[1]
    if not ext:
        ext = '.bin'
    digest = hashlib.sha256(content).hexdigest()
    fname = f"{digest}{ext}"
    fpath = os.path.join(UPLOAD_DIR, fname)
    if not os.path.exists(fpath):
        with open(fpath, 'wb') as f:
            f.write(content)
    return f"/uploads/{fname}"


def sanitize(main) -> str:
    # 0) chrome by ARIA role (nav / banner / footer / zoom-buttons that
    #    are <div role="..."> rather than real <nav>/<button> tags)
    for el in main.find_all(attrs={"role": True}):
        if not getattr(el, "decomposed", False) and el.get("role") in REMOVE_ROLES:
            el.decompose()
    # Preserve allowed iframes
    for iframe in main.find_all('iframe'):
        src = iframe.get('src', '').strip()
        if any(re.search(pat, src) for pat in ALLOWED_IFRAME_SRC):
            allowed = ['src', 'width', 'height', 'allow', 'allowfullscreen', 'frameborder']
            for attr in list(iframe.attrs):
                if attr not in allowed:
                    del iframe[attr]
            continue
        iframe.decompose()
    # 1) blocklisted tags
    for tag in main.find_all(REMOVE_TAGS):
        tag.decompose()
    # 2) base64 images → /uploads/<sha256>.<ext>; rewrite src in place
    for img in main.find_all("img"):
        src = (img.get("src") or "").strip()
        if not src.lower().startswith("data:"):
            continue
        new_url = _persist_data_uri(src)
        if new_url is None:
            img.decompose()
        else:
            img["src"] = new_url
    # 3) link-dominated blocks — the in-content Google Sites sidebar menu that
    #    lists sibling pages pollutes both content and categorization.
    for el in main.find_all(["ul", "nav", "section", "div"]):
        if el.parent is None:
            continue
        # Skip elements that contain actual content images or headings
        if el.find(["img", "h1", "h2", "h3", "h4", "h5", "h6"]):
            continue
        txt = el.get_text(strip=True)
        links = el.find_all("a")
        if txt and len(links) >= 3:
            link_txt = "".join(a.get_text(strip=True) for a in links)
            if len(link_txt) >= 0.7 * len(txt):
                el.decompose()
    # 4) strip every attribute except the whitelist
    for tag in main.find_all(True):
        allowed = KEEP_ATTRS.get(tag.name, [])
        for attr in list(tag.attrs):
            if attr not in allowed:
                del tag[attr]
    # Handle links: rewrite internal Google Sites URLs and download allowed attachments
    for a in main.find_all('a'):
        href = a.get('href', '').strip()
        if href.startswith('https://sites.google.com/'):
            slug = href.rstrip('/').split('/')[-1].replace('.html', '')
            a['href'] = f"/articles/{slug}"
            continue
        parsed = urllib.parse.urlparse(href)
        if parsed.netloc in ALLOWED_ATTACHMENT_DOMAINS:
            local_url = download_external(href)
            if local_url:
                a['href'] = local_url
            else:
                a['class'] = (a.get('class', []) + ['attachment-missing'])
            continue
    # 5) flatten layout cruft: Google Sites nests ~10 generic div/span wrappers
    #    around every line. The spec excludes div/span from the allowed-tag set,
    #    so unwrap() them, keeping inner content + semantic tags (p/h1/ul/li/a).
    for tag in main.find_all(["span", "div", "section", "article", "main", "center", "font"]):
        if tag.parent is not None and not getattr(tag, "decomposed", False):
            tag.unwrap()
    # 6) drop now-empty semantic tags left behind
    for tag in main.find_all(["p", "li", "h1", "h2", "h3", "h4", "h5", "h6"]):
        if getattr(tag, "decomposed", False):
            continue
        if not tag.get_text(strip=True) and not tag.find(["img", "a", "br"]):
            tag.decompose()
    # Style tables with Tailwind utilities
    for table in main.find_all('table'):
        existing = table.get('class', [])
        table['class'] = list(set(existing + TABLE_TAILWIND_CLASSES.split()))
        for cell in table.find_all(['th', 'td']):
            cell_classes = cell.get('class', [])
            cell['class'] = list(set(cell_classes + ['border', 'px-4', 'py-2']))
    return main.decode_contents().strip()


def pick_category(title_lower: str, text_lower: str) -> str:
    # The TITLE is the strongest signal (e.g. "IPTV პაკეტები" -> IPTV), so match
    # it first; pricing terms appear on nearly every page, so body-only matching
    # over-assigns ბილინგი. Fall back to the full text only if the title is mute.
    for name, keywords in CATEGORY_KEYWORDS:
        if any(k in title_lower for k in keywords):
            return name
    for name, keywords in CATEGORY_KEYWORDS:
        if any(k in text_lower for k in keywords):
            return name
    return DEFAULT_CATEGORY


def pick_department(title_lower: str, text_lower: str, folder: str) -> str:
    # Generic company-wide policies / guides → All
    # e.g., "მომსახურების სტანდარტი", "პერსონალურ მონაცემთა დაცვა", "ქოლცენტრი"
    if any(k in title_lower for k in ["მომსახურების სტანდარტი", "პერსონალურ მონაცემთა დაცვა", "ქოლცენტრი"]):
        return "All"
    
    # tech/ is the technical-support section.
    if folder.lower() == "tech":
        return "Support"
        
    # All other commercial, mobile, roaming, and billing articles (Desk and MOB folders) → Informational
    return "Informational"


def process_file(path: str):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        soup = BeautifulSoup(f.read(), "html.parser")

    title = extract_title(soup, path)
    main = find_content(soup)
    if main is None:
        return None

    # Prepend header images to main content block (retaining their relative order)
    header = soup.find("header")
    if header:
        import copy
        for img in reversed(header.find_all("img")):
            main.insert(0, copy.copy(img))

    content = sanitize(main)
    plain = main.get_text(" ", strip=True)
    if not content or not plain:
        return None

    folder = os.path.basename(os.path.dirname(path))
    title_lower = title.lower()
    text_lower = (title + " " + plain).lower()
    return {
        "title": title,
        "content": content,
        "category_name": pick_category(title_lower, text_lower),
        "target_department": pick_department(title_lower, text_lower, folder),
    }


def clean_orphan_uploads() -> int:
    """Delete UUID-named image files in uploads/ (the previous, non-dedup'd
    extraction pattern). Returns count deleted. Non-image UUID files (e.g. .txt
    test uploads) are left alone."""
    if not os.path.isdir(UPLOAD_DIR):
        return 0
    deleted = 0
    for name in os.listdir(UPLOAD_DIR):
        if UUID_FILE_RE.match(name):
            try:
                os.remove(os.path.join(UPLOAD_DIR, name))
                deleted += 1
            except OSError:
                pass
    return deleted


def gc_unreferenced_uploads(db) -> tuple[int, int]:
    """Remove SHA-256 image files in uploads/ that no article references.

    Returns (deleted_count, bytes_freed). Safe to run after every migration:
    the link-dominated-block strip can drop an <img> after its file has been
    extracted, and dup-title skips leave whole pages' images behind. This GC
    matches DB references against on-disk content-addressed files.
    """
    if not os.path.isdir(UPLOAD_DIR):
        return 0, 0
    sha_re = re.compile(r"^[0-9a-f]{64}\.[a-z]+$")
    refs = set()
    for (content,) in db.execute(text(
        "select content from articles where content like '%/uploads/%'"
    )):
        refs.update(re.findall(r"/uploads/([0-9a-f]+\.[a-z]+)", content))
    deleted = bytes_freed = 0
    for name in os.listdir(UPLOAD_DIR):
        if sha_re.match(name) and name not in refs:
            path = os.path.join(UPLOAD_DIR, name)
            try:
                bytes_freed += os.path.getsize(path)
                os.remove(path)
                deleted += 1
            except OSError:
                pass
    return deleted, bytes_freed


def main():
    parser = argparse.ArgumentParser(description="Migrate legacy KB HTML into articles.")
    parser.add_argument("--dry-run", action="store_true",
                        help="parse & report without writing to the DB (or to uploads/)")
    parser.add_argument("--clean-orphans", action="store_true",
                        help="before migrating, delete UUID-named image files in uploads/")
    args = parser.parse_args()

    if not os.path.isdir(SOURCE_DIR):
        print(f"Source directory not found: {SOURCE_DIR}")
        return

    if args.clean_orphans and not args.dry_run:
        n = clean_orphan_uploads()
        print(f"Removed {n} UUID-named orphan image(s) from uploads/\n")

    # Reset per-run image stats; thread the dry-run flag down to the persister.
    IMG_STATS.clear()
    global DRY_RUN
    DRY_RUN = args.dry_run

    Base.metadata.create_all(bind=engine)
    db = SessionLocal()
    try:
        # Don't crash if the running server briefly holds a write lock.
        db.execute(text("PRAGMA busy_timeout=5000"))

        author = (db.query(User).filter(User.role == "content_admin").first()
                  or db.query(User).filter(User.role == "admin").first()
                  or db.query(User).first())
        if author is None:
            print("No users found — run `python seed.py` first.")
            return

        # Ensure all target categories exist; build name -> id.
        existing = {c.name: c for c in db.query(Category).all()}
        for name in ALL_CATEGORY_NAMES:
            if name not in existing:
                existing[name] = Category(name=name)
                db.add(existing[name])
        db.flush()
        cat_id = {name: existing[name].id for name in ALL_CATEGORY_NAMES}

        # Dedup against existing titles + within this run.
        seen = {row[0] for row in db.query(Article.title).all()}

        files = sorted(glob.glob(os.path.join(SOURCE_DIR, "**", "*.html"), recursive=True))
        print(f"Found {len(files)} HTML files under {SOURCE_DIR}\n")

        inserted = dup = empty = failed = 0
        cat_dist, dept_dist = Counter(), Counter()

        for path in files:
            try:
                data = process_file(path)
            except Exception as e:
                failed += 1
                print(f"  [FAIL] {os.path.basename(path)} -> {e}")
                continue
            if data is None:
                empty += 1
                continue
            if data["title"] in seen:
                dup += 1
                continue
            seen.add(data["title"])
            cat_dist[data["category_name"]] += 1
            dept_dist[data["target_department"]] += 1

            if args.dry_run:
                print(f"  [WOULD ADD] {data['target_department']:<13} | "
                      f"{data['category_name']:<18} | {len(data['content']):>6}b | {data['title']}")
            else:
                db.add(Article(
                    title=data["title"],
                    content=data["content"],
                    category_id=cat_id[data["category_name"]],
                    target_department=data["target_department"],
                    status="published",
                    author_id=author.id,
                ))
            inserted += 1

        if not args.dry_run:
            db.commit()
            # Sweep image files orphaned by link-dominated-block strips and
            # dup-title skips. Safe to always run (DB → disk reconciliation).
            n_gc, bytes_freed = gc_unreferenced_uploads(db)
            if n_gc:
                print(f"GC: removed {n_gc} unreferenced image(s) "
                      f"({bytes_freed/1024/1024:.1f} MB freed)")

        verb = "Would insert" if args.dry_run else "Inserted"
        print(f"\n{verb}: {inserted} | duplicate-title skipped: {dup} | "
              f"empty skipped: {empty} | failed: {failed}")
        print("By category:  " + ", ".join(f"{k}={v}" for k, v in cat_dist.most_common()))
        print("By department:" + ", ".join(f" {k}={v}" for k, v in dept_dist.most_common()))
        print("Images       :"
              f" written={IMG_STATS['written']},"
              f" reused(dedup)={IMG_STATS['reused']},"
              f" decode_failed={IMG_STATS['decode_failed']},"
              f" unsupported_mime={IMG_STATS['unsupported_mime']},"
              f" empty={IMG_STATS['empty']}")
        if not args.dry_run:
            print(f"Total articles in DB now: {db.query(Article).count()}")

    except Exception as e:
        db.rollback()
        print("Migration error:", e)
        raise
    finally:
        db.close()


if __name__ == "__main__":
    main()
