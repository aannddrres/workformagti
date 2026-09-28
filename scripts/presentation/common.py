"""Source inventory, safety gates and sanitizer for the presentation seed.

This module deliberately has no Oracle dependency so its failure-prone input
validation can be unit-tested without a database.  The Oracle writer imports
only data that passed these checks.
"""

from __future__ import annotations

import hashlib
import html
import json
import os
import re
import sqlite3
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable
from urllib.parse import unquote, urlparse

from bs4 import BeautifulSoup, Comment


SOURCE_ARTICLE_MIN_ID = 17
SOURCE_ARTICLE_MAX_ID = 138
# The original pre-checkpoint file hash was
# 569afcd6d7c56f72bb22fd0c0797a95608046672f0c7a6bc8003b2b1a27c8ac7.
# SQLite normalized the physical file while the presentation stack was first
# being diagnosed.  Before approving the resulting immutable source, its whole
# controlled import projection was compared with the Oracle baseline produced
# from the original file: 122 articles, 132 history rows, 5 news rows, 3 videos,
# and all 429 assets (60,929,367 bytes) were identical.
EXPECTED_SOURCE_DB_SHA256 = "299248ef1859799bc372932173bee0182411391104064ffeabeb770dad61b5d8"
EXPECTED_ASSET_MANIFEST_SHA256 = "3cab92b5f7ee35032d8b6e169c5da336e2a5959cd623a6bdb4e668a45c34e751"
EXPECTED_ARTICLES = 122
EXPECTED_ARTICLE_HISTORY = 132
EXPECTED_ASSETS = 429
EXPECTED_ASSET_BYTES = 60_929_367
EXPECTED_CATEGORIES = 11
EXPECTED_NEWS = 5
EXPECTED_VIDEOS = 3

EXPECTED_DSN = "oracle:1521/XEPDB1"
EXPECTED_ORACLE_USER = "magti_app"
EXPECTED_CONFIRMATION = "LOCAL_ONLY_MAGTI_PRESENTATION_V1"
EXPECTED_ORACLE_CONTEXT = ("MAGTI_APP", "XEPDB1", "XEPDB1")

# The exact Flyway version this seeder was written against. It is an equality
# check, not a minimum, on purpose: the seeder writes rows directly into a
# schema it cannot see the source of, so a migration it has not been reviewed
# against must stop it rather than silently produce a half-correct database.
#
# Raising this is a deliberate act. Before changing it, read every migration
# between the old value and the new one and confirm none of them removes a
# table the seeder writes, or adds a NOT NULL column without a default to one.
#
# 42 -> 45 (2026-08-29), reviewed for exactly that:
#   V43 drops knowledge_feedback  -- the seeder never writes it
#   V44 adds portal_sessions      -- new table, nothing here touches it
#   V45 adds articles.read_time   -- NOT NULL but DEFAULT 1, so the existing
#                                    article inserts still satisfy it
# Found because a fresh presentation/UAT stack could not be seeded at all
# while the guard sat at 42 and the schema had moved to 45.
#
# 45 -> 48 (2026-09-11), reviewed the same way. All three are CREATE TABLE
# only: nothing is dropped, and no existing table gains a column, so every
# INSERT this seeder writes still matches the schema it writes into.
#   V46 adds stored_file_references -- see below, the one that needed thought
#   V47 adds legacy_content_imports -- the importer's own provenance map,
#                                      written by scripts/import_legacy_content.py
#   V48 adds login_attempts         -- throwaway throttle counters
#
# V46 is the one worth recording. It is the index behind DEC-P01 ("a file is
# readable when content referencing it is readable"), and this seeder does not
# write it -- it writes stored_files and articles and stops. V46's own backfill
# cannot cover a fresh volume either, because it runs at migration time, before
# any of those rows exist. That reads like every demo image being visible to
# content@magti.ge alone.
#
# It is not, and the reason is deliberate rather than lucky:
# FileReferenceIndex.referencesTo falls through to an authoritative scan when
# the index has no row, logs that a save path is out of sync, and writes what
# it finds back. A missing index degrades to slow-and-correct on first access
# and heals itself. So the seeder is not required to populate it, and this
# guard does not need to hold the line at 45 on its account.
# 48 -> 49 (2026-09-23): audit chain hash/previous-hash indexes only.
# Seeders write no audit hashes directly; the V28 trigger continues to do so.
# Existing duplicates make the unique index fail loudly and need investigation.
EXPECTED_FLYWAY_VERSION = "52"
SEARCH_ENTITY_TYPES = {
    "article": "ARTICLE",
    "news": "NEWS",
    "video": "VIDEO",
}

DEPARTMENT_MAP = {
    "საინფო": "საინფორმაციო",
    "Informational": "საინფორმაციო",
    "საინფორმაციო": "საინფორმაციო",
    "ტექნიკური": "ტექნიკური",
    "ოფისი": "ოფისი",
    "All": "All",
}

_IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif"}
_LOCAL_UPLOAD_RE = re.compile(r"(?:^|/)uploads/([^?#]+)", re.IGNORECASE)
_SAFE_TAGS = {
    "a", "b", "blockquote", "br", "caption", "code", "col", "colgroup",
    "dd", "del", "details", "div", "dl", "dt", "em", "figcaption", "figure",
    "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "ins", "li",
    "mark", "ol", "p", "pre", "s", "small", "span", "strong", "sub", "summary",
    "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul",
}
_DROP_WITH_CONTENT = {
    "applet", "audio", "button", "canvas", "embed", "form", "iframe", "input",
    "link", "meta", "noscript", "object", "script", "select", "source", "style",
    "svg", "template", "textarea", "video",
}
_GLOBAL_ATTRS = {"class", "dir", "lang", "title"}
_TAG_ATTRS = {
    "a": {"href", "rel", "target"},
    "img": {"alt", "height", "src", "width"},
    "td": {"colspan", "rowspan"},
    "th": {"colspan", "rowspan", "scope"},
    "col": {"span", "width"},
}
_SAFE_SCHEMES = {"http", "https", "mailto", "tel"}


class PresentationSafetyError(RuntimeError):
    """Raised when the local-only or source-integrity contract is violated."""


class SanitizationLossError(PresentationSafetyError):
    """Raised when sanitization removes a material amount of article text."""


def normalize_search_entity_type(value: str) -> str:
    try:
        return SEARCH_ENTITY_TYPES[value.lower()]
    except (AttributeError, KeyError) as exc:
        raise PresentationSafetyError(f"Unknown search entity type: {value!r}") from exc


@dataclass(frozen=True)
class SourceInventory:
    database_sha256: str
    articles: int
    article_history: int
    categories: int
    assets: int
    asset_bytes: int
    asset_manifest_sha256: str
    news: int
    videos: int

    def as_dict(self) -> dict[str, object]:
        return asdict(self)


@dataclass
class SanitizationStats:
    source_article_id: int
    documents: int = 0
    removed_tags: int = 0
    unwrapped_tags: int = 0
    removed_attributes: int = 0
    removed_images: int = 0
    retained_images: int = 0
    source_text_chars: int = 0
    sanitized_text_chars: int = 0
    notes: list[str] = field(default_factory=list)

    @property
    def text_loss_ratio(self) -> float:
        if self.source_text_chars == 0:
            return 0.0
        return max(0.0, (self.source_text_chars - self.sanitized_text_chars) / self.source_text_chars)

    def as_dict(self) -> dict[str, object]:
        result = asdict(self)
        result["text_loss_ratio"] = round(self.text_loss_ratio, 6)
        return result


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_department(value: str | None) -> str:
    normalized = (value or "All").strip() or "All"
    if normalized not in DEPARTMENT_MAP:
        raise PresentationSafetyError(f"Unknown department label in source: {normalized!r}")
    return DEPARTMENT_MAP[normalized]


def open_source_database(path: Path) -> sqlite3.Connection:
    resolved = path.resolve()
    uri = resolved.as_uri() + "?mode=ro&immutable=1"
    connection = sqlite3.connect(uri, uri=True)
    connection.row_factory = sqlite3.Row
    # The approved historical file has two orphaned pages in its freelist and
    # therefore reports "Page ... never used" from quick_check.  Its exact
    # SHA-256 plus the strict table/row/file manifest below is the import
    # identity; rejecting that known file for harmless unused pages would make
    # the approved source impossible to load.  Any structural corruption still
    # fails when the required tables are queried.
    required_tables = {
        "articles", "article_history", "article_target_departments", "categories",
        "news", "news_history", "video_instructions",
    }
    actual_tables = {
        str(row[0])
        for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")
    }
    missing = required_tables - actual_tables
    if missing:
        connection.close()
        raise PresentationSafetyError(f"SQLite source is missing required tables: {sorted(missing)!r}")
    return connection


def source_articles(connection: sqlite3.Connection) -> list[sqlite3.Row]:
    return connection.execute(
        "SELECT * FROM articles WHERE id BETWEEN ? AND ? ORDER BY id",
        (SOURCE_ARTICLE_MIN_ID, SOURCE_ARTICLE_MAX_ID),
    ).fetchall()


def source_histories(connection: sqlite3.Connection) -> list[sqlite3.Row]:
    return connection.execute(
        "SELECT * FROM article_history WHERE article_id BETWEEN ? AND ? "
        "ORDER BY article_id, version_id, id",
        (SOURCE_ARTICLE_MIN_ID, SOURCE_ARTICLE_MAX_ID),
    ).fetchall()


def referenced_asset_names(contents: Iterable[str]) -> list[str]:
    names: set[str] = set()
    for content in contents:
        soup = BeautifulSoup(content or "", "html.parser")
        for image in soup.find_all("img"):
            filename = local_upload_filename(image.get("src"))
            if filename:
                names.add(filename)
    return sorted(names)


def local_upload_filename(value: str | None) -> str | None:
    if not value:
        return None
    candidate = html.unescape(value).strip().replace("\\", "/")
    parsed = urlparse(candidate)
    if parsed.scheme or parsed.netloc:
        return None
    match = _LOCAL_UPLOAD_RE.search(unquote(parsed.path))
    if not match:
        return None
    raw_name = match.group(1).strip("/")
    if "/" in raw_name or raw_name in {".", ".."}:
        return None
    if Path(raw_name).suffix.lower() not in _IMAGE_EXTENSIONS:
        return None
    return raw_name


def detect_image_type(path: Path) -> str:
    header = path.read_bytes()[:16]
    suffix = path.suffix.lower()
    if header.startswith(b"\x89PNG\r\n\x1a\n") and suffix == ".png":
        return "image/png"
    if header.startswith(b"\xff\xd8\xff") and suffix in {".jpg", ".jpeg"}:
        return "image/jpeg"
    if header.startswith((b"GIF87a", b"GIF89a")) and suffix == ".gif":
        return "image/gif"
    raise PresentationSafetyError(f"Image extension/magic mismatch: {path.name}")


def build_source_inventory(database_path: Path, uploads_path: Path) -> SourceInventory:
    if not database_path.is_file():
        raise PresentationSafetyError(f"Source database is missing: {database_path}")
    if not uploads_path.is_dir():
        raise PresentationSafetyError(f"Source uploads directory is missing: {uploads_path}")

    database_sha256 = sha256_file(database_path)
    connection = open_source_database(database_path)
    try:
        articles = source_articles(connection)
        histories = source_histories(connection)
        category_count = int(connection.execute(
            "SELECT COUNT(DISTINCT category_id) FROM articles "
            "WHERE id BETWEEN ? AND ? AND category_id IS NOT NULL",
            (SOURCE_ARTICLE_MIN_ID, SOURCE_ARTICLE_MAX_ID),
        ).fetchone()[0])
        news_count = int(connection.execute("SELECT COUNT(*) FROM news").fetchone()[0])
        video_count = int(connection.execute("SELECT COUNT(*) FROM video_instructions").fetchone()[0])
        names = referenced_asset_names(
            [str(row["content"] or "") for row in articles]
            + [str(row["content"] or "") for row in histories]
        )
    finally:
        connection.close()

    asset_digest = hashlib.sha256()
    asset_bytes = 0
    missing: list[str] = []
    for name in names:
        path = uploads_path / name
        if not path.is_file():
            missing.append(name)
            continue
        detect_image_type(path)
        payload_digest = hashlib.sha256(path.read_bytes()).digest()
        asset_bytes += path.stat().st_size
        asset_digest.update(name.encode("utf-8"))
        asset_digest.update(b"\0")
        asset_digest.update(payload_digest)
    if missing:
        raise PresentationSafetyError(
            f"Referenced upload files are missing ({len(missing)}): {missing[:5]!r}"
        )

    return SourceInventory(
        database_sha256=database_sha256,
        articles=len(articles),
        article_history=len(histories),
        categories=category_count,
        assets=len(names),
        asset_bytes=asset_bytes,
        asset_manifest_sha256=asset_digest.hexdigest(),
        news=news_count,
        videos=video_count,
    )


def assert_expected_inventory(inventory: SourceInventory) -> None:
    expected = SourceInventory(
        database_sha256=EXPECTED_SOURCE_DB_SHA256,
        articles=EXPECTED_ARTICLES,
        article_history=EXPECTED_ARTICLE_HISTORY,
        categories=EXPECTED_CATEGORIES,
        assets=EXPECTED_ASSETS,
        asset_bytes=EXPECTED_ASSET_BYTES,
        asset_manifest_sha256=EXPECTED_ASSET_MANIFEST_SHA256,
        news=EXPECTED_NEWS,
        videos=EXPECTED_VIDEOS,
    )
    if inventory != expected:
        raise PresentationSafetyError(
            "Source inventory differs from the approved presentation manifest.\n"
            f"expected={json.dumps(expected.as_dict(), ensure_ascii=False, sort_keys=True)}\n"
            f"actual={json.dumps(inventory.as_dict(), ensure_ascii=False, sort_keys=True)}"
        )


def assert_local_environment(*, app_env: str, dsn: str, user: str, confirmation: str) -> None:
    if app_env.strip().lower() != "development":
        raise PresentationSafetyError("Presentation seeding requires APP_ENV=development exactly")
    normalized_dsn = dsn.removeprefix("jdbc:oracle:thin:@").removeprefix("//")
    if normalized_dsn != EXPECTED_DSN:
        raise PresentationSafetyError(
            f"Refusing Oracle DSN {normalized_dsn!r}; expected {EXPECTED_DSN!r}"
        )
    if user.strip().lower() != EXPECTED_ORACLE_USER:
        raise PresentationSafetyError(
            f"Refusing Oracle user {user!r}; expected {EXPECTED_ORACLE_USER!r}"
        )
    if confirmation != EXPECTED_CONFIRMATION:
        raise PresentationSafetyError("Missing exact local presentation confirmation marker")


def _decode_mount_path(value: str) -> str:
    return value.replace("\\040", " ").replace("\\011", "\t").replace("\\134", "\\")


def path_is_on_read_only_mount(path: Path, mountinfo: str | None = None) -> bool:
    """Return true when Linux mountinfo says the longest covering mount is ro."""
    if os.name == "nt" and mountinfo is None:
        return False
    text = mountinfo if mountinfo is not None else Path("/proc/self/mountinfo").read_text("utf-8")
    target = path.as_posix() if mountinfo is not None else str(path.resolve())
    matches: list[tuple[int, set[str]]] = []
    for line in text.splitlines():
        before, separator, _after = line.partition(" - ")
        if not separator:
            continue
        fields = before.split()
        if len(fields) < 6:
            continue
        mount_point = _decode_mount_path(fields[4])
        options = set(fields[5].split(","))
        if target == mount_point or target.startswith(mount_point.rstrip("/") + "/"):
            matches.append((len(mount_point), options))
    return bool(matches) and "ro" in max(matches, key=lambda item: item[0])[1]


def assert_read_only_sources(database_path: Path, uploads_path: Path) -> None:
    if os.getenv("PRESENTATION_REQUIRE_RO_MOUNTS", "true").lower() != "true":
        raise PresentationSafetyError("PRESENTATION_REQUIRE_RO_MOUNTS must remain true")
    for path in (database_path, uploads_path):
        if not path_is_on_read_only_mount(path):
            raise PresentationSafetyError(f"Source path is not on a read-only mount: {path}")


def _visible_text(value: str) -> str:
    soup = BeautifulSoup(value or "", "html.parser")
    return re.sub(r"\s+", " ", soup.get_text(" ", strip=True)).strip()


def _safe_link(value: str) -> str | None:
    cleaned = html.unescape(value).strip()
    if not cleaned:
        return None
    parsed = urlparse(cleaned)
    if parsed.scheme:
        return cleaned if parsed.scheme.lower() in _SAFE_SCHEMES else None
    if parsed.netloc or cleaned.lower().startswith(("javascript:", "data:", "vbscript:")):
        return None
    return cleaned


def sanitize_html(
    value: str,
    *,
    source_article_id: int,
    valid_assets: set[str],
    stats: SanitizationStats | None = None,
) -> tuple[str, SanitizationStats]:
    report = stats or SanitizationStats(source_article_id=source_article_id)
    report.documents += 1
    source_text = _visible_text(value)
    report.source_text_chars += len(source_text)

    soup = BeautifulSoup(value or "", "html.parser")
    for comment in soup.find_all(string=lambda text: isinstance(text, Comment)):
        comment.extract()

    for tag in list(soup.find_all(True)):
        # A parent from the original snapshot may already have been decomposed,
        # which detaches all of its children before their turn in this list.
        if tag.parent is None:
            continue
        name = tag.name.lower()
        if name in _DROP_WITH_CONTENT:
            report.removed_tags += 1
            tag.decompose()
            continue
        if name not in _SAFE_TAGS:
            report.unwrapped_tags += 1
            tag.unwrap()
            continue

        allowed_attrs = _GLOBAL_ATTRS | _TAG_ATTRS.get(name, set())
        for attr in list(tag.attrs):
            if attr.lower().startswith("on") or attr.lower() not in allowed_attrs:
                del tag.attrs[attr]
                report.removed_attributes += 1

        if name == "img":
            filename = local_upload_filename(tag.get("src"))
            if filename is None or filename not in valid_assets:
                report.removed_images += 1
                tag.decompose()
                continue
            tag["src"] = f"/uploads/{filename}"
            report.retained_images += 1
        elif name == "a":
            safe_href = _safe_link(tag.get("href", ""))
            if safe_href is None:
                if tag.has_attr("href"):
                    del tag.attrs["href"]
                    report.removed_attributes += 1
            else:
                tag["href"] = safe_href
                if urlparse(safe_href).scheme in {"http", "https"}:
                    tag["rel"] = "noopener noreferrer"

    sanitized = str(soup)
    sanitized_text = _visible_text(sanitized)
    report.sanitized_text_chars += len(sanitized_text)
    lost = len(source_text) - len(sanitized_text)
    if len(source_text) >= 100 and lost > max(80, int(len(source_text) * 0.02)):
        raise SanitizationLossError(
            f"Article {source_article_id} lost {lost}/{len(source_text)} visible text characters"
        )
    return sanitized, report


def searchable_text(title: str, content: str, tags: str | None = None) -> str:
    body = BeautifulSoup(content or "", "html.parser").get_text(" ", strip=True)
    return re.sub(r"\s+", " ", " ".join((title or "", body, tags or ""))).strip().lower()


def trigrams(value: str) -> list[str]:
    return sorted({value[index:index + 3] for index in range(max(0, len(value) - 2))})
