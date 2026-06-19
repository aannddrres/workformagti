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
)


# Columns added after the initial schema. (table, column, DDL type, default).
# Applied idempotently via ADD COLUMN only when missing — never drops anything.
_ADDED_COLUMNS = (
    ("categories", "slug", "VARCHAR", None),
    ("categories", "icon", "VARCHAR", None),
    ("categories", "pastel_color_class", "VARCHAR", None),
    ("categories", "is_active", "BOOLEAN", "1"),
    ("articles", "audience_profile", "VARCHAR", "'all'"),
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


def main() -> int:
    log.info("Creating any missing tables...")
    Base.metadata.create_all(bind=engine)
    ensure_columns()
    log.info("Schema ensured.")

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
