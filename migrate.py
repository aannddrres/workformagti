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
    # Backs _ensure_current_version_archived's race-safety (main.py): two
    # concurrent archivers of the same version now collide here instead of
    # creating a duplicate row. NULLs (legacy pre-version_id rows) are always
    # distinct from each other in a unique index on both SQLite and Postgres,
    # so they never trip this. Run scripts/repair_article_history_duplicates.py
    # first on any database where this index has never existed before —
    # existing duplicates would otherwise make this CREATE fail.
    "CREATE UNIQUE INDEX IF NOT EXISTS ux_article_history_article_version ON article_history (article_id, version_id)",
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
    # Immutable snapshots — audit rows stay self-contained after user/content deletion.
    ("audit_logs", "admin_name_snapshot", "VARCHAR", None),
    ("audit_logs", "admin_email_snapshot", "VARCHAR", None),
    ("audit_logs", "item_name_snapshot", "VARCHAR", None),
    # Tamper-evident chain (Postgres-only trigger, see AUDIT_CHAIN_STATEMENTS)
    # + request metadata. Nullable: pre-migration rows stay unchained forever,
    # never backfilled — see audit_logs_chain_trigger's genesis comment.
    ("audit_logs", "prev_hash", "VARCHAR(64)", None),
    ("audit_logs", "row_hash", "VARCHAR(64)", None),
    ("audit_logs", "ip_address", "VARCHAR(45)", None),
    ("audit_logs", "user_agent", "VARCHAR(500)", None),
)


# Postgres-only: tamper-evident hash chain over audit_logs. A BEFORE INSERT
# trigger (not a SQLAlchemy ORM event) because audit_logs has two structurally
# different write paths — audit_trail.py's Core-level `.insert().values(...)`
# (bypasses ORM events on purpose) and ~27 ORM-level `models.AuditLog(...)`
# call sites in main.py — and a trigger is the only thing that fires for both
# without touching every call site.
AUDIT_CHAIN_STATEMENTS = (
    "CREATE EXTENSION IF NOT EXISTS pgcrypto",

    # Single source of truth for the canonical string: called by both the
    # trigger and the /verify endpoint so they can never drift apart. Joined
    # with E'\x1f' (unit separator) + E'\x00' NULL-sentinels rather than
    # concat_ws('|', ...) -- concat_ws silently *skips* NULL arguments, so
    # ('a', NULL, 'b') and ('a', 'b', NULL) would both collapse to "a|b", a
    # real (if narrow) collision for a tamper-evidence primitive.
    """
    CREATE OR REPLACE FUNCTION audit_logs_canonical_string(r audit_logs) RETURNS text AS $$
      SELECT
        r.id::text || E'\x1f' ||
        COALESCE(r.prev_hash, E'\x00') || E'\x1f' ||
        r.admin_id::text || E'\x1f' ||
        r.action || E'\x1f' ||
        r.item_type || E'\x1f' ||
        r.item_id::text || E'\x1f' ||
        to_char(r.timestamp, 'YYYY-MM-DD"T"HH24:MI:SS.US') || E'\x1f' ||
        COALESCE(r.category, E'\x00') || E'\x1f' ||
        COALESCE(r.details, E'\x00') || E'\x1f' ||
        COALESCE(r.admin_name_snapshot, E'\x00') || E'\x1f' ||
        COALESCE(r.admin_email_snapshot, E'\x00') || E'\x1f' ||
        COALESCE(r.item_name_snapshot, E'\x00') || E'\x1f' ||
        COALESCE(r.ip_address, E'\x00') || E'\x1f' ||
        COALESCE(r.user_agent, E'\x00')
    $$ LANGUAGE sql IMMUTABLE
    """,

    """
    CREATE OR REPLACE FUNCTION audit_logs_chain_trigger() RETURNS trigger AS $$
    DECLARE
      tip_hash VARCHAR(64);
      prev_lock_timeout text;
    BEGIN
      -- set_config(..., true) is transaction-scoped, not statement-scoped:
      -- without the restore below, the REST of the business transaction
      -- (whatever the route does before COMMIT) would silently run under a
      -- 3s lock ceiling it never asked for. Save/restore keeps the timeout
      -- scoped to the advisory-lock acquisition only.
      prev_lock_timeout := current_setting('lock_timeout');
      PERFORM set_config('lock_timeout', '3000ms', true);

      -- Serializes ALL audit_logs inserts through one point (transaction-
      -- scoped, auto-released at COMMIT/ROLLBACK). This is what closes the
      -- genesis race: a BEFORE INSERT trigger can't catch-and-retry its own
      -- row's unique_violation (Postgres only detects that violation after
      -- the trigger has already returned NEW), so the retry-based pattern
      -- used elsewhere doesn't work here. An advisory lock needs no side
      -- table and no retry anywhere. At 4 Gunicorn workers / ~600 users, one
      -- global serialization point per audit write is not a throughput
      -- concern -- it IS the "one global chain" this was scoped down to.
      PERFORM pg_advisory_xact_lock(72710060142);
      PERFORM set_config('lock_timeout', prev_lock_timeout, true);
      -- The EXCEPTION arm needs no restore: a PL/pgSQL block with an
      -- EXCEPTION clause runs in a subtransaction, and set_config(..., true)
      -- changes roll back with it.

      NEW.ip_address := NULLIF(left(current_setting('app.client_ip', true), 45), '');
      NEW.user_agent := NULLIF(left(current_setting('app.user_agent', true), 500), '');

      SELECT row_hash INTO tip_hash
      FROM audit_logs
      WHERE row_hash IS NOT NULL
      ORDER BY id DESC
      LIMIT 1;
      -- No FOR UPDATE needed: the advisory lock above already excludes every
      -- other concurrent writer, so this read is race-free by construction.

      NEW.prev_hash := tip_hash;  -- NULL exactly once: the true genesis row
      NEW.row_hash := encode(digest(audit_logs_canonical_string(NEW), 'sha256'), 'hex');

      RETURN NEW;
    EXCEPTION WHEN lock_not_available THEN
      RAISE EXCEPTION 'audit_logs_chain_trigger: could not acquire chain lock within timeout';
    END;
    $$ LANGUAGE plpgsql
    """,

    "DROP TRIGGER IF EXISTS trg_audit_logs_chain ON audit_logs",
    """
    CREATE TRIGGER trg_audit_logs_chain
      BEFORE INSERT ON audit_logs
      FOR EACH ROW
      EXECUTE FUNCTION audit_logs_chain_trigger()
    """,

    # Defense-in-depth invariant, not the primary race-fix (the advisory lock
    # is). If trigger logic ever has a bug, the DB refuses a second genesis
    # row loudly instead of silently accepting a corrupted chain.
    """
    CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_logs_chain_genesis
      ON audit_logs ((true))
      WHERE prev_hash IS NULL AND row_hash IS NOT NULL
    """,

    # Backs audit_chain_health's `unchained_total` count (main.py), which
    # otherwise sequentially scans the full table on every passive dashboard
    # mount. Partial index over just the (small, fixed) set of pre-migration
    # NULL-hash rows keeps that count cheap regardless of table size.
    """
    CREATE INDEX IF NOT EXISTS ix_audit_logs_unchained
      ON audit_logs (id)
      WHERE row_hash IS NULL
    """,
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


def backfill_audit_log_snapshots() -> None:
    """One-time backfill: populate admin_name/email/item_name snapshots on
    existing audit_logs rows from the live users/articles/news/video tables.

    Idempotent — only touches rows where admin_name_snapshot IS NULL.
    Rows whose referenced user/content has already been deleted will simply
    keep NULL snapshots and fall back to the legacy 'წაშლილი მომხმარებელი'
    label in the API layer.
    """
    with engine.begin() as conn:
        # Admin name + email
        result = conn.execute(text("""
            UPDATE audit_logs
            SET admin_name_snapshot = (SELECT name FROM users WHERE users.id = audit_logs.admin_id),
                admin_email_snapshot = (SELECT email FROM users WHERE users.id = audit_logs.admin_id)
            WHERE admin_name_snapshot IS NULL
        """))
        if result.rowcount:
            log.info("Backfilled admin snapshots on %s audit_logs rows", result.rowcount)

        # Item name snapshot — per item_type
        for item_type, table, col in [
            ("article", "articles", "title"),
            ("news", "news", "title"),
            ("video", "video_instructions", "title"),
            ("category", "categories", "name"),
            ("user", "users", "name"),
        ]:
            result = conn.execute(text(f"""
                UPDATE audit_logs
                SET item_name_snapshot = (SELECT {col} FROM {table} WHERE {table}.id = audit_logs.item_id)
                WHERE lower(item_type) = :it AND item_name_snapshot IS NULL
            """), {"it": item_type})
            if result.rowcount:
                log.info("Backfilled item_name_snapshot for item_type=%s on %s rows", item_type, result.rowcount)


def backfill_missing_version_1() -> None:
    """Checks all articles in the database. If any article is missing a Version 1
    entry in the article_history table, it ensures one exists.

    If the article has other history entries (e.g. the oldest existing row is
    version_id=2, a leftover of the old, incorrect versioning semantics), that
    oldest row is RENUMBERED to version_id=1 rather than cloned — it already
    IS the article's first revision chronologically, just mislabeled. Cloning
    it would leave two rows with identical content (a permanent no-op
    "revision" in the diff view). Renumbering is safe: nothing else keys off
    an exact version_id besides ordering (get_article_diff's predecessor
    lookup, the version list) — id-based references (compare_history_id)
    are untouched.

    If the article has no history entries at all, Version 1 is backfilled
    using the article's current title and content.
    """
    with engine.begin() as conn:
        articles = conn.execute(text(
            "SELECT id, title, content, author_id, created_at FROM articles"
        )).fetchall()

        backfilled_count = 0
        for art in articles:
            art_id, title, content, author_id, created_at = art

            # Check if version 1 exists
            exists = conn.execute(text(
                "SELECT id FROM article_history WHERE article_id = :aid AND version_id = 1"
            ), {"aid": art_id}).first()

            if exists:
                continue

            # Find oldest existing history entry (id-tiebreak keeps this
            # deterministic if two rows ever tied on version_id).
            oldest = conn.execute(text("""
                SELECT id FROM article_history
                WHERE article_id = :aid ORDER BY version_id ASC, id ASC LIMIT 1
            """), {"aid": art_id}).first()

            if oldest:
                conn.execute(text(
                    "UPDATE article_history SET version_id = 1 WHERE id = :hid"
                ), {"hid": oldest[0]})
            else:
                conn.execute(text("""
                    INSERT INTO article_history (article_id, title, content, updated_by, version_id, updated_at)
                    VALUES (:aid, :title, :content, :updated_by, 1, :updated_at)
                """), {
                    "aid": art_id,
                    "title": title,
                    "content": content,
                    "updated_by": author_id or 1,  # fallback to admin ID 1 if no author
                    "updated_at": created_at,
                })
            backfilled_count += 1

        if backfilled_count:
            log.info("Backfilled missing Version 1 on %s articles", backfilled_count)


def ensure_system_audit_permission_seeded() -> None:
    """Idempotent, additive-only: guarantees the 'system:audit' Permission row
    and its content_admin + manager RolePermission bindings exist, without
    touching any other permission/role data. NOT a replacement for
    scripts/seed_rbac.py's full 13-permission catalog -- deliberately narrow
    so this migration can't clobber RBAC state set up some other way. Runs on
    both dialects (Role/Permission/RolePermission are plain SQLAlchemy
    tables, nothing Postgres-specific). system_admin needs no row here:
    security.require_permission() bypasses the DB check for that role
    entirely.

    content_admin sees every audit row; a manager holds the same permission
    but main.py's _audit_scope_department() hard-pins them to their own
    department/group at query time -- the permission only gates entry, the
    route body decides the actual visible scope.
    """
    from sqlalchemy.orm import sessionmaker
    import models
    import security

    Session = sessionmaker(bind=engine)
    db = Session()
    try:
        perm = db.query(models.Permission).filter(
            models.Permission.name == security.PERM_SYSTEM_AUDIT
        ).first()
        if not perm:
            perm = models.Permission(
                name=security.PERM_SYSTEM_AUDIT,
                description="Access DDL and administrative action logs",
            )
            db.add(perm)
            db.flush()
            log.info("Seeded permission: %s", security.PERM_SYSTEM_AUDIT)

        for role_name in (security.ROLE_CONTENT_ADMIN, security.ROLE_MANAGER):
            role = db.query(models.Role).filter(models.Role.name == role_name).first()
            if not role:
                role = models.Role(name=role_name, description=f"{role_name} role")
                db.add(role)
                db.flush()

            bound = db.query(models.RolePermission).filter_by(
                role_id=role.id, permission_id=perm.id
            ).first()
            if not bound:
                db.add(models.RolePermission(role_id=role.id, permission_id=perm.id))
                log.info("Bound system:audit to %s", role_name)

        db.commit()
    finally:
        db.close()


def main() -> int:
    log.info("Creating any missing tables...")
    Base.metadata.create_all(bind=engine)
    ensure_columns()
    backfill_read_status_department_snapshot()
    normalize_audit_item_type_casing()
    backfill_audit_log_snapshots()
    backfill_missing_version_1()
    ensure_system_audit_permission_seeded()
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

    with engine.begin() as conn:
        for stmt in AUDIT_CHAIN_STATEMENTS:
            conn.execute(text(stmt))
    log.info("audit_logs hash-chain trigger ready.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
