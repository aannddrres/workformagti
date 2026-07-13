"""
One-shot schema bootstrap, called from the docker-compose `migrate` service
before `app` starts.

What it does
------------
1. Creates any missing tables via SQLAlchemy ``create_all`` (idempotent).
2. On PostgreSQL, enables ``pg_trgm`` and adds GIN indexes on the
   commonly-searched text columns so ``ILIKE '%term%'`` queries run in
   milliseconds instead of doing sequential scans.

Idempotent — every statement uses IF NOT EXISTS, so it's safe to re-run on
every container start. Replace with Alembic when Phase B lands.
"""
import logging
import sys

from sqlalchemy import text

from database import engine
from models import Base

logging.basicConfig(level=logging.INFO, format="[migrate] %(message)s")
log = logging.getLogger("migrate")


PG_TRGM_STATEMENTS = (
    "CREATE EXTENSION IF NOT EXISTS pg_trgm",
    "CREATE INDEX IF NOT EXISTS articles_title_trgm "
    "  ON articles USING gin (title gin_trgm_ops)",
    "CREATE INDEX IF NOT EXISTS articles_content_trgm "
    "  ON articles USING gin (content gin_trgm_ops)",
    "CREATE INDEX IF NOT EXISTS articles_tags_trgm "
    "  ON articles USING gin (tags gin_trgm_ops)",
    "CREATE INDEX IF NOT EXISTS news_title_trgm "
    "  ON news USING gin (title gin_trgm_ops)",
    "CREATE INDEX IF NOT EXISTS news_content_trgm "
    "  ON news USING gin (content gin_trgm_ops)",
    # Global search (_run_global_search_sync) ILIKE-matches these two columns
    # too; they were missing from this list, so video search fell back to a
    # sequential scan even on Postgres.
    "CREATE INDEX IF NOT EXISTS video_instructions_title_trgm "
    "  ON video_instructions USING gin (title gin_trgm_ops)",
    "CREATE INDEX IF NOT EXISTS video_instructions_category_trgm "
    "  ON video_instructions USING gin (category gin_trgm_ops)",
)


# Plain btree indexes on high-traffic FK lookup columns. Cross-dialect — plain
# `CREATE INDEX IF NOT EXISTS` is valid on both SQLite (dev) and Postgres (prod),
# so these run regardless of backend. Names match the models.py Index() defs so
# fresh-create and existing DBs converge on a single index per column.
BTREE_INDEX_STATEMENTS = (
    "CREATE INDEX IF NOT EXISTS ix_audit_logs_admin_id ON audit_logs (admin_id)",
    "CREATE INDEX IF NOT EXISTS ix_messages_sender_id ON messages (sender_id)",
    "CREATE INDEX IF NOT EXISTS ix_audit_logs_category_timestamp ON audit_logs (category, timestamp DESC)",
    "CREATE INDEX IF NOT EXISTS ix_audit_logs_admin_timestamp ON audit_logs (admin_id, timestamp DESC)",
    "CREATE INDEX IF NOT EXISTS ix_audit_logs_action_timestamp ON audit_logs (action, timestamp DESC)",
    "CREATE INDEX IF NOT EXISTS ix_article_read_receipts_article_version ON article_read_receipts (article_id, article_version)",
    "CREATE INDEX IF NOT EXISTS ix_article_read_receipts_article_operator ON article_read_receipts (article_id, operator_id)",
)


# Columns added after the initial schema. (table, column, DDL type, default).
# Applied idempotently via ADD COLUMN only when missing — never drops anything.
_ADDED_COLUMNS = (
    ("categories", "slug", "VARCHAR", None),
    ("categories", "icon", "VARCHAR", None),
    ("categories", "pastel_color_class", "VARCHAR", None),
    ("categories", "is_active", "BOOLEAN", "1"),
    ("articles", "audience_profile", "VARCHAR", "'all'"),
    ("users", "last_news_viewed_at", "DATETIME", "NULL"),
    ("users", "last_categories_viewed_at", "TEXT", "NULL"),
    ("users", "card_style", "VARCHAR DEFAULT 'corporate'", None),
    ("news", "expires_at", "DATETIME", "NULL"),
    ("articles", "is_draft", "BOOLEAN", "0"),
    ("news", "is_draft", "BOOLEAN", "0"),
    ("news", "author_id", "INTEGER", "NULL"),
    ("article_history", "version_id", "INTEGER", "NULL"),
    ("read_statuses", "operator_department_snapshot", "TEXT", None),
)


def _existing_columns(conn, table):
    """Return the set of column names on `table` for the active dialect."""
    if engine.dialect.name == "sqlite":
        rows = conn.execute(text(f"PRAGMA table_info({table})")).fetchall()
        return {r[1] for r in rows}
    rows = conn.execute(
        text("SELECT column_name FROM information_schema.columns WHERE table_name = :t"),
        {"t": table},
    ).fetchall()
    return {r[0] for r in rows}


def ensure_columns() -> None:
    """Idempotently ADD COLUMN for taxonomy fields on existing tables."""
    with engine.begin() as conn:
        for table, column, coltype, default in _ADDED_COLUMNS:
            if column in _existing_columns(conn, table):
                continue
            ddl = f"ALTER TABLE {table} ADD COLUMN {column} {coltype}"
            if default is not None:
                ddl += f" DEFAULT {default}"
            log.info("Adding column %s.%s", table, column)
            conn.execute(text(ddl))


def backfill_read_status_department_snapshot() -> None:
    """Backfill NULL operator_department_snapshot rows from live users.department.

    Idempotent — only touches rows where the snapshot is still NULL, so old
    rows get a one-time best-effort department value and re-running this is a
    no-op once every row has been filled in.
    """
    with engine.begin() as conn:
        result = conn.execute(text("""
            UPDATE read_statuses
            SET operator_department_snapshot = (
                SELECT users.department FROM users WHERE users.id = read_statuses.user_id
            )
            WHERE operator_department_snapshot IS NULL
        """))
        if result.rowcount:
            log.info("Backfilled operator_department_snapshot on %s read_statuses rows", result.rowcount)


def normalize_audit_item_type_casing() -> None:
    """One-time cleanup: the retired log_article_read task wrote
    item_type='ARTICLE' (uppercase) while every other writer uses 'article',
    which made those rows miss the case-sensitive category classifier map.
    Idempotent — matches nothing once normalized.
    """
    with engine.begin() as conn:
        result = conn.execute(text(
            "UPDATE audit_logs SET item_type = 'article' WHERE item_type = 'ARTICLE'"
        ))
        if result.rowcount:
            log.info("Normalized item_type casing on %s audit_logs rows", result.rowcount)


def main() -> int:
    log.info("Creating any missing tables...")
    Base.metadata.create_all(bind=engine)
    ensure_columns()
    backfill_read_status_department_snapshot()
    normalize_audit_item_type_casing()
    log.info("Schema ensured.")

    # Cross-dialect FK lookup indexes (run on SQLite and Postgres alike).
    with engine.begin() as conn:
        for stmt in BTREE_INDEX_STATEMENTS:
            log.info("Executing: %s", stmt.split(" IF NOT EXISTS")[0])
            conn.execute(text(stmt))
    log.info("FK lookup indexes ensured.")

    if not engine.dialect.name.startswith("postgres"):
        log.info("Skipping pg_trgm setup — backend is %s, not postgres.", engine.dialect.name)
        return 0

    with engine.begin() as conn:
        for stmt in PG_TRGM_STATEMENTS:
            log.info("Executing: %s", stmt.split(" IF NOT EXISTS")[0])
            conn.execute(text(stmt))
    log.info("pg_trgm extension + GIN indexes ready.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
