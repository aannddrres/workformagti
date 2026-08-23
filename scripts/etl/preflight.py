"""
Preflight: everything that must be true *before* a byte is written.

The rule this module exists to enforce: a cutover fails in preflight or it
does not fail at all. Every check below describes a way the load would
otherwise die halfway through -- with some tables full, some empty, an audit
chain half-built and a change window running out.

Nothing here writes. Nothing here truncates a value to make it fit: a source
string too long for its VARCHAR2(n CHAR) target is reported with its row ids
and the run stops, because silently shortening a knowledge-base article is
worse than a failed rehearsal.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from . import audit_chain, transforms
from .spec import TableSpec

SAMPLE_LIMIT = 10


@dataclass
class Check:
    name: str
    ok: bool
    detail: str
    affected: int = 0
    samples: list[Any] = field(default_factory=list)
    fatal: bool = True

    @property
    def status(self) -> str:
        if self.ok:
            return "PASS"
        return "FAIL" if self.fatal else "WARN"


# Mirrors the FK constraints in the Flyway DDL (V1-V42). A source row whose
# parent is missing is rejected by Oracle mid-load; found here it is a
# five-minute data question instead of a rolled-back change window.
FOREIGN_KEYS: tuple[tuple[str, str, str, str], ...] = (
    ("categories", "parent_id", "categories", "id"),
    ("users", "team_id", "teams", "id"),
    ("users", "manager_id", "users", "id"),
    ("articles", "category_id", "categories", "id"),
    ("articles", "author_id", "users", "id"),
    ("news", "author_id", "users", "id"),
    ("news_history", "news_id", "news", "id"),
    ("news_history", "updated_by", "users", "id"),
    ("article_target_departments", "article_id", "articles", "id"),
    ("article_history", "article_id", "articles", "id"),
    ("article_history", "updated_by", "users", "id"),
    ("article_read_receipts", "article_id", "articles", "id"),
    ("article_read_receipts", "operator_id", "users", "id"),
    ("article_view_logs", "article_id", "articles", "id"),
    ("article_view_logs", "operator_id", "users", "id"),
    ("read_statuses", "user_id", "users", "id"),
    ("read_statuses", "required_reading_id", "required_readings", "id"),
    ("favorites", "user_id", "users", "id"),
    ("tags_mapping", "tag_id", "tags", "id"),
    ("quiz_questions", "article_id", "articles", "id"),
    ("quiz_answers", "question_id", "quiz_questions", "id"),
    ("quiz_attempts", "article_id", "articles", "id"),
    ("quiz_attempts", "user_id", "users", "id"),
    ("search_logs", "user_id", "users", "id"),
    ("user_notes", "user_id", "users", "id"),
    ("user_notes", "article_id", "articles", "id"),
    ("audit_logs", "admin_id", "users", "id"),
    ("messages", "user_id", "users", "id"),
    ("messages", "sender_id", "users", "id"),
    ("knowledge_feedback", "user_id", "users", "id"),
    ("knowledge_feedback", "article_id", "articles", "id"),
    ("knowledge_feedback", "resolved_by", "users", "id"),
)

# UNIQUE constraints Oracle enforces. Checked against the source because the
# legacy schema did not always carry the same constraint -- a duplicate that
# Postgres tolerated stops the load dead.
UNIQUE_KEYS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("users", ("email",)),
    ("tags", ("name",)),
    ("favorites", ("user_id", "item_type", "item_id")),
    ("tags_mapping", ("tag_id", "item_type", "item_id")),
    ("read_statuses", ("user_id", "required_reading_id")),
    ("article_read_receipts", ("article_id", "article_version", "operator_id")),
    ("article_history", ("article_id", "version_id")),
    ("audit_action_translations", ("action",)),
)


def _length_expr(dialect: str, column: str) -> str:
    # Both dialects count characters, not bytes, which is what VARCHAR2(n CHAR)
    # limits. char_length() would be equivalent in Postgres; length() already is.
    return f"length({column})"


def check_target_empty(target, specs: list[TableSpec]) -> list[Check]:
    checks = []
    for spec in specs:
        count = target.count(spec.target)
        checks.append(
            Check(
                name=f"target-empty:{spec.target}",
                ok=count == 0,
                detail=(
                    "target table is empty"
                    if count == 0
                    else f"target table already holds {count} rows -- a fresh cutover expects 0"
                ),
                affected=count,
            )
        )
    return checks


def check_schema_version(target, minimum: int = 42) -> Check:
    """The target must already be migrated past the last schema the plan knows."""
    try:
        # Flyway creates its history table with quoted lower-case column
        # names on Oracle, so an unquoted `version`/`success` is folded to
        # upper case and does not resolve (ORA-00904). Found on the first
        # real Oracle run; no SQLite rehearsal could have shown it.
        rows = target.query(
            'SELECT MAX(TO_NUMBER(REGEXP_SUBSTR("version", \'^\\d+\'))) '
            'FROM "flyway_schema_history" WHERE "success" = 1'
        )
        applied = int(rows[0][0]) if rows and rows[0][0] is not None else 0
    except Exception as exc:  # noqa: BLE001 - any driver error means "cannot prove it"
        return Check(
            name="target-schema-version",
            ok=False,
            detail=f"could not read flyway_schema_history: {exc}",
        )
    return Check(
        name="target-schema-version",
        ok=applied >= minimum,
        detail=f"highest applied Flyway version is V{applied}; the plan targets V{minimum}",
        affected=applied,
    )


def check_char_widths(source, specs: list[TableSpec]) -> list[Check]:
    checks = []
    for spec in specs:
        for col in spec.columns:
            if col.max_chars is None or col.source is None:
                continue
            expr = _length_expr(source.dialect, col.source)
            pk = ", ".join(spec.pk)
            rows = source.query(
                f"SELECT {pk}, {expr} FROM {spec.source} "
                f"WHERE {col.source} IS NOT NULL AND {expr} > {col.max_chars} "
                f"ORDER BY {expr} DESC"
            )
            checks.append(
                Check(
                    name=f"width:{spec.target}.{col.target}",
                    ok=not rows,
                    detail=(
                        f"fits VARCHAR2({col.max_chars} CHAR)"
                        if not rows
                        else f"{len(rows)} row(s) exceed VARCHAR2({col.max_chars} CHAR); "
                        f"longest is {rows[0][-1]} characters"
                    ),
                    affected=len(rows),
                    samples=[r[:-1] for r in rows[:SAMPLE_LIMIT]],
                )
            )
    return [c for c in checks if not c.ok] or [
        Check(name="width:all", ok=True, detail="every mapped column fits its Oracle width")
    ]


def check_foreign_keys(source, specs: list[TableSpec]) -> list[Check]:
    tables = {spec.source for spec in specs}
    checks = []
    for table, column, ref_table, ref_column in FOREIGN_KEYS:
        if table not in tables:
            continue  # a table this run does not load (e.g. a gated one)
        rows = source.query(
            f"SELECT t.{column} FROM {table} t "
            f"LEFT JOIN {ref_table} r ON r.{ref_column} = t.{column} "
            f"WHERE t.{column} IS NOT NULL AND r.{ref_column} IS NULL"
        )
        if rows:
            checks.append(
                Check(
                    name=f"fk:{table}.{column}",
                    ok=False,
                    detail=(
                        f"{len(rows)} row(s) reference a missing {ref_table}.{ref_column} -- "
                        "Oracle will reject them"
                    ),
                    affected=len(rows),
                    samples=[r[0] for r in rows[:SAMPLE_LIMIT]],
                )
            )
    return checks or [Check(name="fk:all", ok=True, detail="no orphaned foreign keys in the source")]


def check_unique_keys(source, specs: list[TableSpec]) -> list[Check]:
    tables = {spec.source for spec in specs}
    checks = []
    for table, columns in UNIQUE_KEYS:
        if table not in tables:
            continue
        cols = ", ".join(columns)
        rows = source.query(
            f"SELECT {cols}, COUNT(*) FROM {table} GROUP BY {cols} HAVING COUNT(*) > 1"
        )
        if rows:
            checks.append(
                Check(
                    name=f"unique:{table}({cols})",
                    ok=False,
                    detail=f"{len(rows)} duplicate group(s) violate the Oracle UNIQUE constraint",
                    affected=len(rows),
                    samples=[r[:-1] for r in rows[:SAMPLE_LIMIT]],
                )
            )
    return checks or [Check(name="unique:all", ok=True, detail="no duplicates for any target UNIQUE key")]


def check_transformable(source, specs: list[TableSpec]) -> list[Check]:
    """Run every transform over every row before any of them is written.

    Cheap insurance: a single malformed legacy permissions blob or a NULL in a
    NOT NULL target column would otherwise surface mid-load.
    """
    checks = []
    for spec in specs:
        typed = [c for c in spec.columns if c.transform != "passthrough" and c.source]
        if not typed:
            continue
        failures: list[Any] = []
        cols = [c.source for c in typed]
        for row in source.stream(spec.source, [*spec.pk, *cols], spec.extract_order):
            key = row[: len(spec.pk)]
            for col, value in zip(typed, row[len(spec.pk) :]):
                try:
                    transforms.apply(col.transform, value)
                except (ValueError, TypeError) as exc:
                    failures.append((key, col.target, str(exc)))
        checks.append(
            Check(
                name=f"transform:{spec.target}",
                ok=not failures,
                detail=(
                    "every value converts cleanly"
                    if not failures
                    else f"{len(failures)} value(s) cannot be converted for Oracle"
                ),
                affected=len(failures),
                samples=failures[:SAMPLE_LIMIT],
            )
        )
    return [c for c in checks if not c.ok] or [
        Check(name="transform:all", ok=True, detail="every transformed value converts cleanly")
    ]


def check_empty_strings(source, specs: list[TableSpec]) -> Check:
    """Count the values Oracle will turn into NULL.

    Oracle stores a zero-length VARCHAR2 as NULL -- there is no way to keep
    the distinction, and reconcile.py's `canon` therefore treats '' and NULL
    as equal. That is a defensible reading, but it must not be an invisible
    one: this check states up front how many values the crossing changes
    from "empty" to "absent", so a `WHERE col = ''` somewhere in the Java
    code is a decision, not a surprise.
    """
    total = 0
    per_column: list[Any] = []
    for spec in specs:
        for col in spec.columns:
            if col.max_chars is None or col.source is None:
                continue
            count = int(
                source.scalar(f"SELECT COUNT(*) FROM {spec.source} WHERE {col.source} = ''") or 0
            )
            if count:
                total += count
                per_column.append((f"{spec.target}.{col.target}", count))
    return Check(
        name="empty-string-to-null",
        ok=total == 0,
        detail=(
            "no empty strings to reinterpret"
            if total == 0
            else f"{total} empty string(s) will be stored as NULL by Oracle"
        ),
        affected=total,
        samples=per_column[:SAMPLE_LIMIT],
        fatal=False,
    )


def check_audit_details_length(source) -> Check:
    """Rows whose `details` is longer than Oracle can hash in one piece.

    V28 reduces `details` to its first 32000 characters before hashing
    (STANDARD_HASH takes no CLOB); the Postgres trigger hashed the whole
    value. For a longer row the two hashes differ for a reason that has
    nothing to do with the migration, so the direct chain comparison would
    report a false mismatch. audit_trail.py clips each diffed value at 200
    characters, so this should always be zero -- which is exactly why it is
    worth stating rather than assuming.
    """
    count = int(
        source.scalar(
            f"SELECT COUNT(*) FROM audit_logs WHERE details IS NOT NULL "
            f"AND length(details) > {audit_chain.DETAILS_LIMIT}"
        )
        or 0
    )
    return Check(
        name="audit-details-length",
        ok=count == 0,
        detail=(
            f"every audit `details` fits Oracle's {audit_chain.DETAILS_LIMIT}-character hash input"
            if count == 0
            else f"{count} audit row(s) exceed {audit_chain.DETAILS_LIMIT} characters; their "
            "hashes cannot be compared directly across the two databases"
        ),
        affected=count,
        fatal=False,
    )


def check_audit_chain_source(source) -> Check:
    """How much of the source audit log is already chained.

    Rows written before migrate.py's trigger existed carry NULL row_hash.
    Oracle's V28 trigger hashes *every* row on insert, so those rows will
    gain a hash they never had in Postgres. That is correct and expected --
    but reconcile.py must not then report them as mismatches, so the count
    is recorded here and carried into the report.
    """
    total = int(source.scalar("SELECT COUNT(*) FROM audit_logs") or 0)
    unchained = int(source.scalar("SELECT COUNT(*) FROM audit_logs WHERE row_hash IS NULL") or 0)
    return Check(
        name="audit-chain:source",
        ok=True,
        detail=(
            f"{total - unchained} of {total} source audit rows carry a hash; "
            f"{unchained} pre-chain row(s) will be hashed for the first time by V28's trigger"
        ),
        affected=unchained,
        fatal=False,
    )


def run(source, target, specs: list[TableSpec], *, check_target: bool = True) -> list[Check]:
    checks: list[Check] = []
    if check_target:
        if getattr(target, "kind", "") == "oracle":
            checks.append(check_schema_version(target))
        checks.extend(check_target_empty(target, specs))
    checks.extend(check_char_widths(source, specs))
    checks.extend(check_foreign_keys(source, specs))
    checks.extend(check_unique_keys(source, specs))
    checks.extend(check_transformable(source, specs))
    checks.append(check_empty_strings(source, specs))
    checks.append(check_audit_details_length(source))
    checks.append(check_audit_chain_source(source))
    return checks


def blocking(checks: list[Check]) -> list[Check]:
    return [c for c in checks if not c.ok and c.fatal]
