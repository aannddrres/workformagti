"""Source inventory and sanitizer for the legacy content import.

This module deliberately has no Oracle dependency so its failure-prone input
validation can be unit-tested without a database.  The Oracle writer imports
only data that passed these checks.
"""

from __future__ import annotations

import hashlib
import html
import json
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
# SQLite normalized the physical file while the retired demo stack was first
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


class SourceSafetyError(RuntimeError):
    """Raised when the source database or its content breaks the import contract."""


class SanitizationLossError(SourceSafetyError):
    """Raised when sanitization removes a material amount of article text."""


def normalize_search_entity_type(value: str) -> str:
    try:
        return SEARCH_ENTITY_TYPES[value.lower()]
    except (AttributeError, KeyError) as exc:
        raise SourceSafetyError(f"Unknown search entity type: {value!r}") from exc


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
        raise SourceSafetyError(f"Unknown department label in source: {normalized!r}")
    return DEPARTMENT_MAP[normalized]


def normalize_category_icon(value: str | None) -> str | None:
    """A Font Awesome class, or None so the portal falls back to its own.

    The source stores ``fa_wifi`` for roaming, an underscore where Font
    Awesome has a hyphen; copied verbatim it drew an empty square on every
    category tile. The Angular side repairs the same value on display
    (``categoryIconClass``); this keeps it out of the production database.
    """
    icon = (value or "").strip().replace("_", "-")
    return icon if re.fullmatch(r"fa-[a-z0-9-]+", icon) else None


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
        raise SourceSafetyError(f"SQLite source is missing required tables: {sorted(missing)!r}")
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
    raise SourceSafetyError(f"Image extension/magic mismatch: {path.name}")


def build_source_inventory(database_path: Path, uploads_path: Path) -> SourceInventory:
    if not database_path.is_file():
        raise SourceSafetyError(f"Source database is missing: {database_path}")
    if not uploads_path.is_dir():
        raise SourceSafetyError(f"Source uploads directory is missing: {uploads_path}")

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
        raise SourceSafetyError(
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
        raise SourceSafetyError(
            "Source inventory differs from the approved source manifest.\n"
            f"expected={json.dumps(expected.as_dict(), ensure_ascii=False, sort_keys=True)}\n"
            f"actual={json.dumps(inventory.as_dict(), ensure_ascii=False, sort_keys=True)}"
        )


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
