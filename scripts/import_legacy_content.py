"""Move the retired portal's real knowledge base into Oracle, once, for keeps.

The 122 articles in ``magti_portal.db`` (ids 17-138) are the call centre's
actual reference material -- roaming tariffs, GPON parameters, the porting
procedure -- and they exist nowhere else. This brings them across as ordinary
articles, along with the 11 categories they sit in and the images they embed.

WHAT IT DOES NOT WRITE
----------------------

Nothing here writes a user, a team or a password. The demo seeder that read
this same content for the presentation stack also invented 605 employees and
a shared demo password; it was removed from the repository with that stack
at the production handover (2026-09-29). The source inventory and the HTML
sanitiser it shared with this script live on in ``scripts/legacy_content.py``.

WHAT IT WRITES
--------------

  categories -> the 11 that the articles use
  stored_files -> the images those articles embed
  stored_file_references -> so DEC-P01 can answer "who may open this image"
  articles + article_target_departments
  article_history -> the 132 prior versions, because an article whose history
                     starts at "imported" cannot be audited
  search_trigrams -> or the articles are invisible to search
  legacy_content_imports -> the source-to-target map (V47)

IMPORTED AS DRAFTS
------------------

Default status is ``draft``, and that is a decision rather than caution.
Publishing 122 articles at once would bury whatever the call centre is
actually meant to read first, and the stated plan is to release them
gradually, some later becoming mandatory reading. Drafts are invisible to
operators and fully visible to content administrators, which is exactly the
holding state that plan needs. ``--status published`` is there for whoever
decides otherwise, deliberately and out loud.

RUNNING IT TWICE IS SAFE
------------------------

Every row written is recorded in ``legacy_content_imports`` against its
source id. A second run updates what it already wrote and inserts only what
is genuinely new. Without that the second run would produce 122 duplicate
articles with no way to tell them apart -- titles are not unique and the old
ids are not carried over.

USAGE
-----

    python scripts/import_legacy_content.py                 # dry run, changes nothing
    python scripts/import_legacy_content.py --apply
    python scripts/import_legacy_content.py --apply --status published

Connection comes from ORACLE_DB_URL / ORACLE_DB_USER / ORACLE_DB_PASSWORD,
the same variables the backend reads.
"""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
import uuid
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "scripts"))

from legacy_content import (  # noqa: E402
    SOURCE_ARTICLE_MAX_ID,
    SOURCE_ARTICLE_MIN_ID,
    SanitizationStats,
    detect_image_type,
    normalize_category_icon,
    normalize_department,
    open_source_database,
    normalize_search_entity_type,
    referenced_asset_names,
    sanitize_html,
    searchable_text,
    source_articles,
    source_histories,
    trigrams,
)

BATCH_PREFIX = "legacy-import"
DEFAULT_SOURCE_DB = REPO_ROOT / "magti_portal.db"
DEFAULT_SOURCE_UPLOADS = REPO_ROOT / "uploads"

# The source ids that carry a quiz in the old portal. Preserved because the
# quiz gate keys off the flag, and losing it would silently turn a gated
# article into an ungated one.
QUIZ_SOURCE_IDS = (129, 131)

SOURCE_TYPE_ARTICLE = "article"
SOURCE_TYPE_CATEGORY = "category"
SOURCE_TYPE_FILE = "stored_file"


class ImportError_(RuntimeError):
    """Refusing to do something that would leave the database wrong."""


# --------------------------------------------------------------------------
# connection
# --------------------------------------------------------------------------


def _dsn_from_env() -> str:
    raw = os.getenv("ORACLE_DB_URL") or os.getenv("ORACLE_DB_DSN") or "localhost:1521/orclpdb1"
    return raw.removeprefix("jdbc:oracle:thin:@").removeprefix("//")


def _connect():
    import oracledb

    return oracledb.connect(
        user=os.getenv("ORACLE_DB_USER", "magti_app"),
        # Same default as application.yml, so the script and the backend
        # reach the same database without either needing an env var set.
        password=os.getenv("ORACLE_DB_PASSWORD", "CHANGE_ME_LOCAL_DEV_ONLY"),
        dsn=_dsn_from_env(),
    )


def _one(cursor, sql: str, **params) -> tuple[Any, ...] | None:
    cursor.execute(sql, params)
    return cursor.fetchone()


def _scalar(cursor, sql: str, **params) -> Any:
    row = _one(cursor, sql, **params)
    return None if row is None else row[0]


def _insert_id(cursor, sql: str, **params) -> int:
    import oracledb

    generated = cursor.var(oracledb.NUMBER)
    cursor.execute(sql, {**params, "generated_id": generated})
    return int(generated.getvalue()[0])


def _as_timestamp(value: Any, fallback: datetime | None = None) -> datetime | None:
    if value in (None, ""):
        return fallback
    if isinstance(value, datetime):
        return value.replace(tzinfo=None)
    for pattern in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.strptime(str(value)[:26], pattern)
        except ValueError:
            continue
    return fallback


# --------------------------------------------------------------------------
# preflight
# --------------------------------------------------------------------------


def _verify_schema(cursor) -> None:
    """V47 must be applied, or there is nowhere to record what was imported."""
    applied = _scalar(
        cursor,
        'SELECT COUNT(*) FROM "flyway_schema_history" WHERE "success" = 1 AND "version" = \'47\'',
    )
    if not applied:
        raise ImportError_(
            "migration V47 is not applied on this database. Start the backend "
            "once against it (Flyway runs on startup) and try again -- without "
            "legacy_content_imports this import cannot be re-run safely."
        )


def _resolve_author(cursor, email: str | None) -> tuple[int, str]:
    """The user the imported articles are attributed to.

    Requires a real content administrator that already exists. Creating one
    here would be the one thing this script must never do: an import that can
    mint an account with content permissions is an import that can be used to
    get them.
    """
    if email:
        row = _one(
            cursor,
            "SELECT id, email FROM users WHERE LOWER(email) = LOWER(:email) AND is_active = 1",
            email=email,
        )
        if row is None:
            raise ImportError_(f"no active user with email {email!r} on this database")
        return int(row[0]), str(row[1])

    row = _one(
        cursor,
        "SELECT id, email FROM users WHERE role IN ('content_admin', 'admin') "
        "AND is_active = 1 ORDER BY CASE role WHEN 'content_admin' THEN 0 ELSE 1 END, id",
    )
    if row is None:
        raise ImportError_(
            "no active content_admin or admin exists on this database to attribute "
            "the import to. Create one first, or pass --author-email."
        )
    return int(row[0]), str(row[1])


def _existing_map(cursor, source_type: str) -> dict[int, int]:
    cursor.execute(
        "SELECT source_id, target_id FROM legacy_content_imports WHERE source_type = :source_type",
        source_type=source_type,
    )
    return {int(source_id): int(target_id) for source_id, target_id in cursor.fetchall()}


def _record(cursor, source_type: str, source_id: int, target_id: int, batch: str, now: datetime) -> None:
    cursor.execute(
        "MERGE INTO legacy_content_imports t "
        "USING (SELECT :source_type AS source_type, :source_id AS source_id FROM dual) s "
        "ON (t.source_type = s.source_type AND t.source_id = s.source_id) "
        "WHEN MATCHED THEN UPDATE SET t.target_id = :target_id, t.imported_at = :imported_at, t.batch = :batch "
        "WHEN NOT MATCHED THEN INSERT (source_type, source_id, target_id, imported_at, batch) "
        "VALUES (:source_type, :source_id, :target_id, :imported_at, :batch)",
        source_type=source_type,
        source_id=source_id,
        target_id=target_id,
        imported_at=now,
        batch=batch,
    )


# --------------------------------------------------------------------------
# adopting content that is already there
# --------------------------------------------------------------------------


def _adopt_existing(
    cursor, source: sqlite3.Connection, batch: str, now: datetime, apply: bool
) -> dict[str, dict[int, int]]:
    """Link rows that are already in the target to the source rows they came from.

    For a database this importer has never touched but whose content arrived
    by another route. The retired demo/UAT seeder read the same 122 source
    articles and wrote them straight to Oracle without recording anything
    in ``legacy_content_imports`` -- so a first --apply there would insert
    a second copy of all 122, and of their categories.

    Matching is by exact title (articles) and exact name (categories), and an
    ambiguous match is REFUSED rather than resolved. Two rows sharing a title
    is precisely the case where a guess links the source row to the wrong one
    and the next re-run silently overwrites an article nobody meant to touch.
    ``categories.name`` in particular carries no unique constraint.

    Nothing is modified here beyond the map; the ordinary import then treats
    every adopted row as an update.
    """
    adopted: dict[str, dict[int, int]] = {SOURCE_TYPE_CATEGORY: {}, SOURCE_TYPE_ARTICLE: {}}
    ambiguous: list[str] = []

    def _match(source_type: str, rows: list[tuple[int, str]], sql: str, column: str) -> None:
        already = _existing_map(cursor, source_type)
        for source_id, value in rows:
            if source_id in already:
                continue
            cursor.execute(sql, **{column: value})
            found = [int(row[0]) for row in cursor.fetchall()]
            if len(found) > 1:
                ambiguous.append(f"{source_type} {source_id}: {value!r} matches {len(found)} rows")
                continue
            if not found:
                continue
            adopted[source_type][source_id] = found[0]

    category_rows = source.execute(
        "SELECT id, name FROM categories WHERE id IN ("
        "  SELECT DISTINCT category_id FROM articles "
        "  WHERE id BETWEEN ? AND ? AND category_id IS NOT NULL"
        ") ORDER BY id",
        (SOURCE_ARTICLE_MIN_ID, SOURCE_ARTICLE_MAX_ID),
    ).fetchall()
    _match(
        SOURCE_TYPE_CATEGORY,
        [(int(row["id"]), str(row["name"])) for row in category_rows],
        "SELECT id FROM categories WHERE name = :name",
        "name",
    )
    _match(
        SOURCE_TYPE_ARTICLE,
        [(int(row["id"]), str(row["title"])) for row in source_articles(source)],
        "SELECT id FROM articles WHERE title = :title AND trashed_at IS NULL",
        "title",
    )

    if ambiguous:
        raise ImportError_(
            "--adopt-existing cannot decide which row to adopt:\n  "
            + "\n  ".join(ambiguous)
            + "\nResolve the duplicates, or import without --adopt-existing."
        )

    # Written only once every match came out unambiguous. Closing the
    # connection would roll a partial map back anyway, but a half-adopted
    # database is not a state anyone should have to reason about.
    if apply:
        for source_type, matches in adopted.items():
            for source_id, target_id in matches.items():
                _record(cursor, source_type, source_id, target_id, batch, now)
    return adopted


# --------------------------------------------------------------------------
# the import itself
# --------------------------------------------------------------------------


def _import_categories(
    cursor,
    source: sqlite3.Connection,
    batch: str,
    now: datetime,
    apply: bool,
    adopted: dict[int, int] | None = None,
) -> dict[int, int]:
    rows = source.execute(
        "SELECT * FROM categories WHERE id IN ("
        "  SELECT DISTINCT category_id FROM articles "
        "  WHERE id BETWEEN ? AND ? AND category_id IS NOT NULL"
        ") ORDER BY id",
        (SOURCE_ARTICLE_MIN_ID, SOURCE_ARTICLE_MAX_ID),
    ).fetchall()

    # Read on a dry run too: it is a SELECT, and a dry run that reports 122
    # articles as "created" when they are all already mapped is the one
    # sentence of the report anybody actually reads.
    existing = {**_existing_map(cursor, SOURCE_TYPE_CATEGORY), **(adopted or {})}
    mapping: dict[int, int] = {}
    created = 0

    for row in rows:
        source_id = int(row["id"])
        if source_id in existing:
            mapping[source_id] = existing[source_id]
            continue
        if not apply:
            mapping[source_id] = -source_id
            created += 1
            continue
        target_id = _insert_id(
            cursor,
            "INSERT INTO categories (name,parent_id,slug,icon,pastel_color_class,is_active) "
            "VALUES (:name,NULL,:slug,:icon,:pastel_color_class,1) RETURNING id INTO :generated_id",
            name=str(row["name"]),
            slug=row["slug"],
            icon=normalize_category_icon(row["icon"]),
            pastel_color_class=row["pastel_color_class"],
        )
        mapping[source_id] = target_id
        _record(cursor, SOURCE_TYPE_CATEGORY, source_id, target_id, batch, now)
        created += 1

    # Parents second, so a child can point at a parent created in this run.
    if apply:
        for row in rows:
            parent = row["parent_id"]
            if parent is None or int(parent) not in mapping:
                continue
            cursor.execute(
                "UPDATE categories SET parent_id = :parent_id WHERE id = :category_id",
                parent_id=mapping[int(parent)],
                category_id=mapping[int(row["id"])],
            )

    return {"mapping": mapping, "found": len(rows), "created": created}


def _import_files(
    cursor,
    asset_names: set[str],
    uploads: Path,
    author_id: int,
    batch: str,
    now: datetime,
    apply: bool,
) -> dict[str, Any]:
    """Images the articles embed, into stored_files.

    Keyed by filename rather than a numeric source id -- stored_files' primary
    key IS the filename, so a file that is already there is already there.
    """
    created = 0
    missing: list[str] = []
    for name in sorted(asset_names):
        path = uploads / name
        if not path.is_file():
            missing.append(name)
            continue
        if apply:
            already = _scalar(
                cursor, "SELECT COUNT(*) FROM stored_files WHERE filename = :filename", filename=name
            )
            if already:
                continue
            payload = path.read_bytes()
            cursor.execute(
                "INSERT INTO stored_files (filename,content_type,byte_size,uploaded_by,created_at,content) "
                "VALUES (:filename,:content_type,:byte_size,:uploaded_by,:created_at,:content)",
                filename=name,
                content_type=detect_image_type(path),
                byte_size=len(payload),
                uploaded_by=author_id,
                created_at=now - timedelta(days=70),
                content=payload,
            )
        created += 1
    return {"created": created, "missing": missing}


def _sync_file_references(cursor, article_id: int, content: str, attachment: str | None) -> None:
    """Populate stored_file_references for this article (V46, DEC-P01).

    The Java side maintains this on every save; an importer writing straight
    to Oracle has to do it too. Skipping it would not break anything visibly
    -- FileReferenceIndex falls back to a full scan when it finds no row --
    but every image in every imported article would take that slow path and
    log a warning saying a save path is broken, which would be untrue and
    would bury a real one.
    """
    cursor.execute(
        "DELETE FROM stored_file_references WHERE item_type = 'article' AND item_id = :article_id",
        article_id=article_id,
    )
    names = referenced_asset_names([content, attachment or ""])
    for name in names:
        cursor.execute(
            "INSERT INTO stored_file_references (filename,item_type,item_id) "
            "VALUES (:filename,'article',:article_id)",
            filename=name,
            article_id=article_id,
        )


def _import_articles(
    cursor,
    source: sqlite3.Connection,
    category_ids: dict[int, int],
    valid_assets: set[str],
    author_id: int,
    status: str,
    batch: str,
    now: datetime,
    apply: bool,
    adopted: dict[int, int] | None = None,
) -> dict[str, Any]:
    articles = source_articles(source)
    histories = source_histories(source)

    targets: dict[int, list[str]] = defaultdict(list)
    for row in source.execute(
        "SELECT article_id, department FROM article_target_departments "
        "WHERE article_id BETWEEN ? AND ? ORDER BY article_id, department",
        (SOURCE_ARTICLE_MIN_ID, SOURCE_ARTICLE_MAX_ID),
    ):
        targets[int(row["article_id"])].append(normalize_department(row["department"]))

    existing = {**_existing_map(cursor, SOURCE_TYPE_ARTICLE), **(adopted or {})}
    stats: dict[int, SanitizationStats] = {}
    mapping: dict[int, int] = {}
    texts: dict[int, tuple[str, str, str | None]] = {}
    created = 0
    updated = 0

    for row in articles:
        source_id = int(row["id"])
        stats.setdefault(source_id, SanitizationStats(source_article_id=source_id))
        sanitized, _ = sanitize_html(
            str(row["content"] or ""),
            source_article_id=source_id,
            valid_assets=valid_assets,
            stats=stats[source_id],
        )
        legacy_target = normalize_department(row["target_department"])
        departments = sorted(set(targets.get(source_id) or [legacy_target]))
        if "All" in departments:
            departments = ["All"]
        attachment = row["attachment_url"]
        if attachment and Path(str(attachment)).name not in valid_assets:
            attachment = None
        texts[source_id] = (str(row["title"]), sanitized, row["tags"])

        if not apply:
            if source_id in existing:
                updated += 1
            else:
                created += 1
            mapping[source_id] = existing.get(source_id, -source_id)
            continue

        fields = dict(
            title=str(row["title"]),
            content=sanitized,
            category_id=category_ids.get(int(row["category_id"])) if row["category_id"] is not None else None,
            tags=row["tags"],
            target_department=legacy_target,
            audience_profile=str(row["audience_profile"] or "all"),
            created_at=_as_timestamp(row["created_at"], now),
            updated_at=_as_timestamp(row["updated_at"], now),
            version=int(row["version"] or 1),
            author_id=author_id,
            status=status,
            youtube_id=row["youtube_id"],
            published_at=_as_timestamp(row["published_at"]) if status == "published" else None,
            attachment_url=attachment,
            last_verified_at=_as_timestamp(row["last_verified_at"]),
            visible_to_tech_info=1 if row["visible_to_tech_info"] else 0,
            visible_to_service_center=1 if row["visible_to_service_center"] else 0,
            # NOT 1 for a draft, and the difference matters. is_draft is the
            # personal-autosave flag, and GET /api/articles filters on it with
            # "(a.isDraft = false OR a.authorId = :userId)" -- for EVERYONE,
            # content administrators included. Importing with is_draft=1 made
            # all 122 articles visible only to the account the import was
            # attributed to, which is the opposite of a batch several
            # administrators are meant to release gradually.
            #
            # status='draft' with is_draft=0 is the state that actually holds:
            # operators are filtered out (the visibility clause demands
            # published or scheduled), every content admin sees them, and the
            # UI labels them "მონახაზი".
            is_draft=0,
            quiz_enabled=1 if source_id in QUIZ_SOURCE_IDS else 0,
        )

        if source_id in existing:
            article_id = existing[source_id]
            cursor.execute(
                "UPDATE articles SET title=:title, content=:content, category_id=:category_id, tags=:tags, "
                "target_department=:target_department, audience_profile=:audience_profile, updated_at=:updated_at, "
                "author_id=:author_id, youtube_id=:youtube_id, attachment_url=:attachment_url, "
                "last_verified_at=:last_verified_at, visible_to_tech_info=:visible_to_tech_info, "
                "visible_to_service_center=:visible_to_service_center, quiz_enabled=:quiz_enabled "
                "WHERE id = :article_id",
                article_id=article_id,
                **{
                    key: value
                    for key, value in fields.items()
                    # Status, is_draft, published_at, version and created_at are
                    # NOT overwritten on a re-run. An administrator who has
                    # published or retargeted an imported article since the last
                    # run must not have that undone by re-importing the text.
                    if key
                    not in ("status", "is_draft", "published_at", "version", "created_at")
                },
            )
            updated += 1
        else:
            article_id = _insert_id(
                cursor,
                "INSERT INTO articles (title,content,category_id,tags,target_department,audience_profile,"
                "created_at,updated_at,version,author_id,status,youtube_id,published_at,attachment_url,"
                "last_verified_at,visible_to_tech_info,visible_to_service_center,is_draft,quiz_enabled) VALUES "
                "(:title,:content,:category_id,:tags,:target_department,:audience_profile,:created_at,:updated_at,"
                ":version,:author_id,:status,:youtube_id,:published_at,:attachment_url,:last_verified_at,"
                ":visible_to_tech_info,:visible_to_service_center,:is_draft,:quiz_enabled) "
                "RETURNING id INTO :generated_id",
                **fields,
            )
            created += 1

        mapping[source_id] = article_id
        _record(cursor, SOURCE_TYPE_ARTICLE, source_id, article_id, batch, now)

        cursor.execute(
            "DELETE FROM article_target_departments WHERE article_id = :article_id", article_id=article_id
        )
        for department in departments:
            cursor.execute(
                "INSERT INTO article_target_departments (article_id,department) "
                "VALUES (:article_id,:department)",
                article_id=article_id,
                department=department,
            )
        _sync_file_references(cursor, article_id, sanitized, attachment)

    history_rows = 0
    if apply:
        for row in histories:
            source_id = int(row["article_id"])
            article_id = mapping.get(source_id)
            if article_id is None:
                continue
            version_id = int(row["version_id"]) if row["version_id"] is not None else None
            already = _scalar(
                cursor,
                "SELECT COUNT(*) FROM article_history WHERE article_id = :article_id "
                "AND (version_id = :version_id OR (:version_id IS NULL AND version_id IS NULL))",
                article_id=article_id,
                version_id=version_id,
            )
            if already:
                continue
            stats.setdefault(source_id, SanitizationStats(source_article_id=source_id))
            sanitized, _ = sanitize_html(
                str(row["content"] or ""),
                source_article_id=source_id,
                valid_assets=valid_assets,
                stats=stats[source_id],
            )
            cursor.execute(
                "INSERT INTO article_history (article_id,title,content,updated_at,updated_by,version_id) "
                "VALUES (:article_id,:title,:content,:updated_at,:updated_by,:version_id)",
                article_id=article_id,
                title=str(row["title"]),
                content=sanitized,
                updated_at=_as_timestamp(row["updated_at"], now),
                updated_by=author_id,
                version_id=version_id,
            )
            history_rows += 1
    else:
        history_rows = len(histories)

    return {
        "mapping": mapping,
        "texts": texts,
        "found": len(articles),
        "created": created,
        "updated": updated,
        "history_rows": history_rows,
        "max_text_loss": max((s.text_loss_ratio for s in stats.values()), default=0.0),
    }


def _index_for_search(
    cursor,
    mapping: dict[int, int],
    texts: dict[int, tuple[str, str, str | None]],
    apply: bool,
) -> int:
    """Without this the imported articles cannot be found by searching.

    search_trigrams stores one row per three-character window, not a copy of
    the text, so the same helpers the seeder uses build the rows -- a second
    implementation of "what is searchable about an article" would put the
    imported content one query away from everything else.
    """
    if not apply or not mapping:
        return len(mapping)
    entity_type = normalize_search_entity_type("article")
    indexed = 0
    for source_id, article_id in mapping.items():
        title, body, tags = texts[source_id]
        cursor.execute(
            "DELETE FROM search_trigrams WHERE entity_type = :entity_type AND entity_id = :entity_id",
            entity_type=entity_type,
            entity_id=article_id,
        )
        rows = [
            {"entity_type": entity_type, "entity_id": article_id, "trigram": trigram}
            for trigram in trigrams(searchable_text(title, body, tags))
        ]
        if rows:
            cursor.executemany(
                "INSERT INTO search_trigrams (entity_type,entity_id,trigram) "
                "VALUES (:entity_type,:entity_id,:trigram)",
                rows,
            )
        indexed += 1
    return indexed


# --------------------------------------------------------------------------
# entry point
# --------------------------------------------------------------------------


def run(args: argparse.Namespace) -> dict[str, Any]:
    source_db = Path(args.source_db)
    uploads = Path(args.uploads)
    if not source_db.is_file():
        raise ImportError_(f"source database not found: {source_db}")
    if not uploads.is_dir():
        raise ImportError_(f"uploads directory not found: {uploads}")

    batch = f"{BATCH_PREFIX}-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}-{uuid.uuid4().hex[:8]}"
    now = datetime.now()
    source = open_source_database(source_db)

    articles = source_articles(source)
    valid_assets = {
        name for name in referenced_asset_names(str(row["content"] or "") for row in articles)
        if (uploads / name).is_file()
    }

    connection = _connect()
    try:
        cursor = connection.cursor()
        _verify_schema(cursor)
        author_id, author_email = _resolve_author(cursor, args.author_email)

        adopted: dict[str, dict[int, int]] = {SOURCE_TYPE_CATEGORY: {}, SOURCE_TYPE_ARTICLE: {}}
        if args.adopt_existing:
            adopted = _adopt_existing(cursor, source, batch, now, args.apply)

        categories = _import_categories(
            cursor, source, batch, now, args.apply, adopted[SOURCE_TYPE_CATEGORY]
        )
        files = _import_files(cursor, valid_assets, uploads, author_id, batch, now, args.apply)
        result = _import_articles(
            cursor, source, categories["mapping"], valid_assets,
            author_id, args.status, batch, now, args.apply, adopted[SOURCE_TYPE_ARTICLE],
        )
        indexed = _index_for_search(cursor, result["mapping"], result["texts"], args.apply)

        if args.apply:
            connection.commit()
        else:
            connection.rollback()
    finally:
        connection.close()
        source.close()

    return {
        "applied": args.apply,
        "batch": batch if args.apply else None,
        "target": _dsn_from_env(),
        "author": author_email,
        "status": args.status,
        "adopted": {
            "categories": len(adopted[SOURCE_TYPE_CATEGORY]),
            "articles": len(adopted[SOURCE_TYPE_ARTICLE]),
        },
        "categories": {"found": categories["found"], "created": categories["created"]},
        "files": {"created": files["created"], "missing": len(files["missing"])},
        "articles": {
            "found": result["found"],
            "created": result["created"],
            "updated": result["updated"],
        },
        "article_history_rows": result["history_rows"],
        "search_rows": indexed,
        "max_text_loss_ratio": round(result["max_text_loss"], 4),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--apply",
        action="store_true",
        help="actually write. Without it nothing is committed and the report says what would happen.",
    )
    parser.add_argument(
        "--status",
        choices=("draft", "published"),
        default="draft",
        help="status for NEWLY imported articles (default: draft). Never changes one already imported.",
    )
    parser.add_argument(
        "--adopt-existing",
        action="store_true",
        help=(
            "adopt articles/categories already in the target that match a source row by "
            "title/name, instead of inserting a second copy. For a database seeded by "
            "another route (the retired demo and UAT stacks). Refuses on an ambiguous match."
        ),
    )
    parser.add_argument("--author-email", default=None, help="attribute the import to this user")
    parser.add_argument("--source-db", default=str(DEFAULT_SOURCE_DB))
    parser.add_argument("--uploads", default=str(DEFAULT_SOURCE_UPLOADS))
    args = parser.parse_args(argv)

    try:
        report = run(args)
    except ImportError_ as error:
        print(f"refused: {error}", file=sys.stderr)
        return 2

    print(json.dumps(report, indent=2, ensure_ascii=False))
    if not report["applied"]:
        print("\nDRY RUN -- nothing was written. Re-run with --apply.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
