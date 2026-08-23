"""
The migration plan: which Postgres table becomes which Oracle table, column
by column, in which order.

This module is the single source of truth for the ETL. Nothing else decides
what gets migrated -- extract, load, preflight and reconcile all read the
same TableSpec objects, so a column can never be loaded but not reconciled,
or width-checked against a limit it is not actually stored in.

Three registries, and every table in either schema must appear in exactly
one of them (enforced by tests/etl/test_spec_coverage.py, which parses the
Flyway migrations and models.py rather than trusting this file):

  PLAN          -- migrated, with a column map
  NOT_MIGRATED  -- an Oracle table the ETL deliberately does not fill, + why
  SOURCE_ONLY   -- a Postgres table with no Oracle counterpart, + why

DECISION_REQUIRED marks the two tables whose fate is a product/DPO call
rather than an engineering one. They are in PLAN but skipped unless named
explicitly on the command line, and the run report always states that they
were skipped -- silence in either direction would be a decision made by
default.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ColumnSpec:
    """One target column and where its value comes from.

    max_chars mirrors the Oracle VARCHAR2(n CHAR) width so preflight can
    reject over-long source rows *before* the load starts. Character
    semantics matter: the schema is VARCHAR2(n CHAR) throughout precisely
    so Georgian text is not capped at n/3 (see V1's comment), so the check
    counts characters, not bytes.
    """

    target: str
    source: str | None = None  # None => not read from the source row
    transform: str = "passthrough"
    max_chars: int | None = None
    lob: str | None = None  # "clob" | "blob" -- driver needs the type up front
    self_fk: bool = False  # nulled on insert, patched after the table is full
    checksum: bool = True  # part of the row fingerprint in reconcile.py

    def __post_init__(self) -> None:
        if self.source is None and not self.self_fk:
            raise ValueError(f"{self.target}: a mapped column needs a source")


@dataclass(frozen=True)
class TableSpec:
    target: str
    source: str
    order: int
    pk: tuple[str, ...]
    columns: tuple[ColumnSpec, ...]
    identity: bool = True  # GENERATED ALWAYS AS IDENTITY -> flipped for the load
    order_by: str | None = None  # deterministic extract order; defaults to pk
    postload_sql: tuple[str, ...] = ()
    decision_required: str | None = None
    notes: str = ""

    @property
    def extract_order(self) -> str:
        return self.order_by or ", ".join(self.pk)

    @property
    def source_columns(self) -> tuple[str, ...]:
        return tuple(c.source for c in self.columns if c.source is not None)

    @property
    def insert_columns(self) -> tuple[ColumnSpec, ...]:
        """Columns written by the INSERT -- self-FKs are patched afterwards."""
        return tuple(c for c in self.columns if not c.self_fk)

    @property
    def self_fk_columns(self) -> tuple[ColumnSpec, ...]:
        return tuple(c for c in self.columns if c.self_fk)


def _c(target: str, source: str | None = None, **kw) -> ColumnSpec:
    return ColumnSpec(target=target, source=source if source is not None else target, **kw)


# --------------------------------------------------------------------------
# Load order
#
# FK-safe, and audit_logs sits deliberately late: every row it points at must
# exist first, and its BEFORE INSERT trigger (V28) rebuilds the hash chain in
# insertion order, so it is also the one table that must be loaded in a single
# ascending-id pass with no parallelism. See docs/DATA_MIGRATION_PG_TO_ORACLE_KA.md.
# --------------------------------------------------------------------------

PLAN: tuple[TableSpec, ...] = (
    TableSpec(
        target="teams",
        source="teams",
        order=10,
        pk=("id",),
        columns=(
            _c("id"),
            _c("name", max_chars=200),
            _c("created_at"),
        ),
        notes=(
            "department_id/stable_key/ad_external_id/synced_at (V36) stay NULL: they are "
            "AD-owned org data, filled by the separate leadership/org backfill runbook, "
            "not inferred from a legacy team name."
        ),
    ),
    TableSpec(
        target="categories",
        source="categories",
        order=20,
        pk=("id",),
        columns=(
            _c("id"),
            _c("name", max_chars=200),
            _c("parent_id", self_fk=True),
            _c("slug", max_chars=150),
            _c("icon", max_chars=100),
            _c("pastel_color_class", max_chars=100),
            _c("is_active", transform="bool_to_number"),
        ),
        notes="parent_id is a self-FK: nulled on insert, patched once every row exists.",
    ),
    TableSpec(
        target="users",
        source="users",
        order=30,
        pk=("id",),
        columns=(
            _c("id"),
            _c("email", max_chars=255),
            _c("name", max_chars=200),
            _c("department", max_chars=200),
            _c("position", max_chars=200),
            _c("phone", max_chars=30),
            _c("role", max_chars=30),
            _c("is_active", transform="bool_to_number"),
            _c("last_active"),
            _c("hashed_password", max_chars=255),
            _c("permissions", transform="json_to_clob", lob="clob"),
            _c("team_id"),
            _c("manager_id", self_fk=True),
            _c("last_news_viewed_at"),
            _c("card_style", max_chars=50),
        ),
        notes=(
            "last_categories_viewed_at is intentionally not migrated -- the column does not "
            "exist in Oracle at all (V3, confirmed dead 2026-07-30). token_version and "
            "lock_version take their DDL default of 0; compliance_override stays NULL."
        ),
    ),
    TableSpec(
        target="articles",
        source="articles",
        order=40,
        pk=("id",),
        columns=(
            _c("id"),
            _c("title", max_chars=500),
            _c("content", lob="clob"),
            _c("category_id"),
            _c("tags", max_chars=500),
            _c("target_department", max_chars=200),
            _c("audience_profile", max_chars=20),
            _c("created_at"),
            _c("updated_at"),
            _c("version"),
            _c("author_id"),
            _c("status", max_chars=30),
            _c("youtube_id", max_chars=50),
            _c("published_at"),
            _c("attachment_url", max_chars=1000),
            _c("last_verified_at"),
            _c("visible_to_tech_info", transform="bool_to_number"),
            _c("visible_to_service_center", transform="bool_to_number"),
            _c("is_draft", transform="bool_to_number"),
            _c("quiz_enabled", transform="bool_to_number"),
        ),
        notes=(
            "V42's trash columns (trashed_at/purge_after/trashed_by/legal_hold) stay at their "
            "defaults: nothing in the legacy portal was ever trashed, so every migrated row "
            "arrives live and un-held. lock_version defaults to 0."
        ),
    ),
    TableSpec(
        target="news",
        source="news",
        order=41,
        pk=("id",),
        columns=(
            _c("id"),
            _c("title", max_chars=500),
            _c("content", lob="clob"),
            _c("target_department", max_chars=200),
            _c("created_at"),
            _c("attachment_url", max_chars=1000),
            _c("version"),
            _c("visible_to_tech_info", transform="bool_to_number"),
            _c("visible_to_service_center", transform="bool_to_number"),
            _c("expires_at"),
            _c("is_draft", transform="bool_to_number"),
            _c("author_id"),
        ),
    ),
    TableSpec(
        target="video_instructions",
        source="video_instructions",
        order=42,
        pk=("id",),
        columns=(
            _c("id"),
            _c("title", max_chars=500),
            _c("video_url", max_chars=1000),
            _c("category", max_chars=200),
            _c("target_department", max_chars=200),
            _c("created_at"),
            _c("views_count"),
            _c("tags", max_chars=500),
            _c("is_archived", transform="bool_to_number"),
        ),
    ),
    TableSpec(
        target="article_target_departments",
        source="article_target_departments",
        order=50,
        pk=("article_id", "department"),
        identity=False,
        columns=(
            _c("article_id"),
            _c("department", max_chars=200),
        ),
    ),
    TableSpec(
        target="article_history",
        source="article_history",
        order=51,
        pk=("id",),
        columns=(
            _c("id"),
            _c("article_id"),
            _c("title", max_chars=500),
            _c("content", lob="clob"),
            _c("updated_at"),
            _c("updated_by"),
            _c("version_id"),
        ),
    ),
    TableSpec(
        target="news_history",
        source="news_history",
        order=52,
        pk=("id",),
        columns=(
            _c("id"),
            _c("news_id"),
            _c("title", max_chars=500),
            _c("content", lob="clob"),
            _c("attachment_url", max_chars=1000),
            _c("updated_at"),
            _c("updated_by"),
        ),
    ),
    TableSpec(
        target="required_readings",
        source="required_readings",
        order=60,
        pk=("id",),
        columns=(
            _c("id"),
            _c("item_type", max_chars=20),
            _c("item_id"),
            _c("target_department", max_chars=200),
            _c("due_date"),
            _c("priority", max_chars=20),
        ),
        postload_sql=(
            # Byte-for-byte the same expression V42 runs, re-run here because
            # V42 executed against an empty table long before this data arrived.
            """
            UPDATE required_readings r
            SET item_title_snapshot = CASE
                WHEN r.item_type = 'article' THEN (SELECT a.title FROM articles a WHERE a.id = r.item_id)
                WHEN r.item_type = 'news' THEN (SELECT n.title FROM news n WHERE n.id = r.item_id)
                WHEN r.item_type = 'video' THEN (SELECT v.title FROM video_instructions v WHERE v.id = r.item_id)
                ELSE 'მასალა #' || TO_CHAR(r.item_id)
            END
            WHERE item_title_snapshot IS NULL
            """,
        ),
        notes="item_title_snapshot (V42) is derived post-load, not carried from the source.",
    ),
    TableSpec(
        target="read_statuses",
        source="read_statuses",
        order=61,
        pk=("id",),
        columns=(
            _c("id"),
            _c("user_id"),
            _c("required_reading_id"),
            _c("status", max_chars=20),
            _c("read_at"),
            _c("operator_department_snapshot", max_chars=200),
        ),
    ),
    TableSpec(
        target="tags",
        source="tags",
        order=70,
        pk=("id",),
        columns=(
            _c("id"),
            _c("name", max_chars=100),
            _c("created_at"),
        ),
    ),
    TableSpec(
        target="tags_mapping",
        source="tags_mapping",
        order=71,
        pk=("id",),
        columns=(
            _c("id"),
            _c("tag_id"),
            _c("item_type", max_chars=20),
            _c("item_id"),
        ),
    ),
    TableSpec(
        target="favorites",
        source="favorites",
        order=72,
        pk=("id",),
        columns=(
            _c("id"),
            _c("user_id"),
            _c("item_type", max_chars=20),
            _c("item_id"),
        ),
    ),
    TableSpec(
        target="article_read_receipts",
        source="article_read_receipts",
        order=80,
        pk=("id",),
        columns=(
            _c("id"),
            _c("article_id"),
            _c("article_title_snapshot", max_chars=500),
            _c("article_version"),
            _c("operator_id"),
            _c("operator_name_snapshot", max_chars=200),
            _c("operator_email_snapshot", max_chars=255),
            _c("operator_department_snapshot", max_chars=200),
            _c("read_at"),
        ),
        postload_sql=(
            "UPDATE article_read_receipts SET article_id_snapshot = article_id "
            "WHERE article_id IS NOT NULL AND article_id_snapshot IS NULL",
        ),
        notes="article_id_snapshot (V35) is derived post-load: the source has no such column.",
    ),
    TableSpec(
        target="article_view_logs",
        source="article_view_logs",
        order=81,
        pk=("id",),
        columns=(
            _c("id"),
            _c("article_id"),
            _c("article_title_snapshot", max_chars=500),
            _c("article_version"),
            _c("operator_id"),
            _c("operator_name_snapshot", max_chars=200),
            _c("operator_email_snapshot", max_chars=255),
            _c("operator_department_snapshot", max_chars=200),
            _c("viewed_at"),
        ),
        postload_sql=(
            "UPDATE article_view_logs SET article_id_snapshot = article_id "
            "WHERE article_id IS NOT NULL AND article_id_snapshot IS NULL",
        ),
    ),
    TableSpec(
        target="quiz_questions",
        source="quiz_questions",
        order=90,
        pk=("id",),
        columns=(
            _c("id"),
            _c("article_id"),
            _c("question_text", lob="clob"),
            _c("position"),
        ),
    ),
    TableSpec(
        target="quiz_answers",
        source="quiz_answers",
        order=91,
        pk=("id",),
        columns=(
            _c("id"),
            _c("question_id"),
            _c("answer_text", max_chars=1000),
            _c("is_correct", transform="bool_to_number"),
            _c("position"),
        ),
    ),
    TableSpec(
        target="quiz_attempts",
        source="quiz_attempts",
        order=92,
        pk=("id",),
        columns=(
            _c("id"),
            _c("article_id"),
            _c("article_version"),
            _c("user_id"),
            _c("attempt_number"),
            _c("score"),
            _c("total_questions"),
            _c("passed", transform="bool_to_number"),
            _c("created_at"),
        ),
        postload_sql=(
            """
            UPDATE quiz_attempts q
            SET article_id_snapshot = q.article_id,
                article_title_snapshot = (SELECT a.title FROM articles a WHERE a.id = q.article_id)
            WHERE q.article_id_snapshot IS NULL
            """,
        ),
    ),
    TableSpec(
        target="search_logs",
        source="search_logs",
        order=100,
        pk=("id",),
        columns=(
            _c("id"),
            _c("user_id"),
            _c("search_term", max_chars=500),
            _c("timestamp"),
            _c("has_results", transform="bool_to_number"),
            _c("results_found"),
        ),
    ),
    TableSpec(
        target="user_notes",
        source="user_notes",
        order=101,
        pk=("id",),
        columns=(
            _c("id"),
            _c("user_id"),
            _c("article_id"),
            _c("content", lob="clob"),
            _c("created_at"),
            _c("updated_at"),
        ),
    ),
    TableSpec(
        target="audit_action_translations",
        source="audit_action_translations",
        order=110,
        pk=("id",),
        columns=(
            _c("id"),
            _c("action", max_chars=50),
            _c("label_ka", max_chars=200),
        ),
    ),
    TableSpec(
        target="webhook_configs",
        source="webhook_configs",
        order=111,
        pk=("id",),
        columns=(
            _c("id"),
            _c("url", max_chars=1000),
            _c("is_active", transform="bool_to_number"),
            _c("trigger_actions", max_chars=500),
        ),
    ),
    TableSpec(
        target="audit_logs",
        source="audit_logs",
        order=120,
        pk=("id",),
        order_by="id",
        columns=(
            _c("id"),
            _c("admin_id"),
            _c("action", max_chars=50),
            _c("item_type", max_chars=30),
            _c("item_id"),
            _c("timestamp"),
            _c("category", max_chars=20),
            _c("details", lob="clob"),
            _c("admin_name_snapshot", max_chars=255),
            _c("admin_email_snapshot", max_chars=255),
            _c("item_name_snapshot", max_chars=500),
            _c("ip_address", max_chars=45),
            _c("user_agent", max_chars=500),
        ),
        notes=(
            "prev_hash/row_hash are NOT inserted. V28's BEFORE INSERT trigger recomputes the "
            "chain from the same canonical string Postgres used, so loading in ascending id "
            "order reproduces the source hashes exactly -- and any field that did not survive "
            "the crossing shows up as a hash mismatch in reconcile.py. That comparison is the "
            "strongest evidence this migration produces; it only holds for a single-threaded, "
            "ascending-id load into a target whose chain is still at genesis."
        ),
    ),
    TableSpec(
        target="messages",
        source="messages",
        order=140,
        pk=("id",),
        decision_required=(
            "R2/R3 removed private messaging from the product; the Oracle table still exists "
            "but nothing in the Java app writes to it. Migrating the legacy inbox moves "
            "employee-to-employee PII into the new system for a surface that no longer has a "
            "UI. Owner + DPO decide: migrate, archive in the frozen Postgres snapshot, or drop."
        ),
        columns=(
            _c("id"),
            _c("user_id"),
            _c("sender_id"),
            _c("content", lob="clob"),
            _c("is_read", transform="bool_to_number"),
            _c("created_at"),
        ),
    ),
    TableSpec(
        target="knowledge_feedback",
        source="knowledge_feedback",
        order=141,
        pk=("id",),
        decision_required=(
            "R1/stage A removed the feedback feature; IMPLEMENTATION_PLAN_KA.md keeps the table "
            "pending a data inventory. Same call as messages: migrate, archive, or drop."
        ),
        columns=(
            _c("id"),
            _c("user_id"),
            _c("article_id"),
            _c("message", lob="clob"),
            _c("status", max_chars=20),
            _c("created_at"),
            _c("resolved_at"),
            _c("resolved_by"),
        ),
    ),
)


# Oracle tables the ETL deliberately leaves alone.
NOT_MIGRATED: dict[str, str] = {
    "departments": (
        "Seeded by V36 with the three fixed departments. The legacy source stores department "
        "as free text on users.department; mapping that text onto department rows is the org "
        "backfill's job (and depends on the AD answers), not the ETL's."
    ),
    "leadership_assignments": (
        "Filled by the approved leadership backfill runbook. Inventing assignments from "
        "users.manager_id here would put unreviewed access decisions into production."
    ),
    "user_permission_overrides": (
        "Derived, not copied: V36_1's MERGE reads users.permissions. It runs at Flyway time "
        "against an empty users table, so the ETL re-runs the identical statement after the "
        "user load (see load.py's post_load_global)."
    ),
    "search_trigrams": (
        "A derived search index. TrigramIndexer rebuilds it from article/news text; copying "
        "stale trigram rows would only risk disagreeing with the content actually loaded."
    ),
    "broadcasts": "R2 feature, no legacy counterpart. Starts empty by design.",
    "reminders": (
        "R3 feature. IMPLEMENTATION_PLAN_KA.md explicitly forbids backfilling reminders from "
        "historical material or old messages rows."
    ),
    "export_jobs": (
        "Transient by construction: a 1-hour TTL and a temporary XLSX/BLOB payload. Every row "
        "in the source is already expired by the time a cutover finishes."
    ),
    "audit_chain_state": (
        "The hash chain's tip row, owned by V28's trigger. The ETL must never write it -- the "
        "trigger advances it per insert, which is what makes the chain reproducible."
    ),
    "stored_files": (
        "Not a table-to-table copy: the bytes live on the legacy pod's filesystem, not in "
        "Postgres. uploads.py loads them from the uploads directory (see PR-03)."
    ),
    "flyway_schema_history": (
        "Flyway's own bookkeeping. The target's migration history describes the target; "
        "copying the source's would make Flyway disagree with the schema in front of it."
    ),
}

# Postgres tables with no Oracle counterpart.
SOURCE_ONLY: dict[str, str] = {
    "roles": (
        "The Java app has a fixed role catalog in code (Role enum), not a roles table. The "
        "per-user effect of legacy rows is preserved through users.role + V36_1's overrides."
    ),
    "permissions": "Same: a fixed Permission enum in code, not a table.",
    "role_permissions": (
        "Same: the role->permission matrix is code (RolePermissions), and any per-user "
        "deviation is reconstructed by the V36_1 override MERGE."
    ),
}

# Tables whose loading is a product/DPO decision, not an engineering one.
DECISION_REQUIRED: dict[str, str] = {
    spec.target: spec.decision_required for spec in PLAN if spec.decision_required
}

BY_TARGET: dict[str, TableSpec] = {spec.target: spec for spec in PLAN}


def load_order(include_decision_gated: frozenset[str] = frozenset()) -> list[TableSpec]:
    """The tables to load, FK-safe, with the gated ones opted in by name."""
    chosen = [
        spec
        for spec in PLAN
        if spec.decision_required is None or spec.target in include_decision_gated
    ]
    return sorted(chosen, key=lambda s: s.order)


def unknown_gated(names: frozenset[str]) -> set[str]:
    return {name for name in names if name not in DECISION_REQUIRED}
