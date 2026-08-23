"""
The guard that keeps the migration plan honest.

The plan is only trustworthy while it describes the schemas that actually
exist. So these tests do not read spec.py's opinion of the world -- they
parse the Flyway migrations and models.py and require every table in either
schema to be accounted for, either mapped or explicitly refused with a
reason.

The practical effect: adding V43 with a new table breaks this test until
someone decides whether the cutover carries data into it. That decision is
cheap now and expensive during a change window.
"""
from __future__ import annotations

import os
import re

import pytest

from scripts.etl import preflight, reconcile, spec, transforms

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MIGRATIONS = os.path.join(REPO_ROOT, "java-backend", "src", "main", "resources", "db", "migration")
MODELS = os.path.join(REPO_ROOT, "models.py")

CREATE_TABLE = re.compile(r"^\s*CREATE\s+TABLE\s+(\w+)", re.IGNORECASE | re.MULTILINE)
TABLENAME = re.compile(r'^\s*__tablename__\s*=\s*["\'](\w+)["\']', re.MULTILINE)

# Not created by a migration, but present in every Flyway-managed schema.
INFRASTRUCTURE_TABLES = {"flyway_schema_history"}


def oracle_tables() -> set[str]:
    found: set[str] = set()
    for name in sorted(os.listdir(MIGRATIONS)):
        if not name.endswith(".sql"):
            continue
        with open(os.path.join(MIGRATIONS, name), encoding="utf-8") as handle:
            body = "\n".join(
                line for line in handle.read().splitlines() if not line.strip().startswith("--")
            )
        found.update(match.lower() for match in CREATE_TABLE.findall(body))
    return found


def source_tables() -> set[str]:
    with open(MODELS, encoding="utf-8") as handle:
        return {match.lower() for match in TABLENAME.findall(handle.read())}


def test_every_oracle_table_is_mapped_or_refused():
    accounted = set(spec.BY_TARGET) | set(spec.NOT_MIGRATED)
    missing = oracle_tables() - accounted
    assert not missing, (
        "these Oracle tables are in the schema but in neither PLAN nor NOT_MIGRATED: "
        f"{sorted(missing)} -- decide whether the cutover carries data into them"
    )


def test_not_migrated_names_real_tables():
    unknown = set(spec.NOT_MIGRATED) - oracle_tables() - INFRASTRUCTURE_TABLES
    assert not unknown, f"NOT_MIGRATED refers to tables that do not exist: {sorted(unknown)}"


def test_every_source_table_is_mapped_or_refused():
    mapped = {s.source for s in spec.PLAN}
    # NOT_MIGRATED counts too: export_jobs exists on both sides and is refused
    # on the target side, which is the same decision recorded once.
    accounted = mapped | set(spec.SOURCE_ONLY) | set(spec.NOT_MIGRATED)
    missing = source_tables() - accounted
    assert not missing, (
        f"these legacy tables have no migration decision: {sorted(missing)}"
    )


def test_plan_sources_exist_in_models():
    unknown = {s.source for s in spec.PLAN} - source_tables()
    assert not unknown, f"PLAN reads tables models.py does not define: {sorted(unknown)}"


def test_every_refusal_carries_a_reason():
    for registry in (spec.NOT_MIGRATED, spec.SOURCE_ONLY, spec.DECISION_REQUIRED):
        for table, reason in registry.items():
            assert len(reason) > 40, f"{table}: a one-word reason is not a reason"


def test_transform_names_are_real():
    for table in spec.PLAN:
        for column in table.columns:
            assert column.transform in transforms.TRANSFORMS, (
                f"{table.target}.{column.target} uses unknown transform {column.transform!r}"
            )


def test_load_order_is_fk_safe():
    order = {s.target: s.order for s in spec.PLAN}
    for child, column, parent, _ in preflight.FOREIGN_KEYS:
        if child not in order or parent not in order or child == parent:
            continue
        assert order[parent] <= order[child], (
            f"{child}.{column} references {parent}, which is loaded later"
        )


def test_self_referencing_columns_are_marked():
    """A self-FK that is not marked would be inserted before its parent exists."""
    for child, column, parent, _ in preflight.FOREIGN_KEYS:
        if child != parent:
            continue
        table = spec.BY_TARGET.get(child)
        if table is None:
            continue
        marked = {c.target for c in table.self_fk_columns}
        assert column in marked, f"{child}.{column} points at its own table but is not self_fk"


def test_decision_gated_tables_are_excluded_by_default():
    default = {s.target for s in spec.load_order()}
    assert not (default & set(spec.DECISION_REQUIRED)), (
        "a table whose migration is a product decision must not load by default"
    )
    opted_in = {s.target for s in spec.load_order(frozenset({"messages"}))}
    assert "messages" in opted_in
    assert "knowledge_feedback" not in opted_in


def test_unknown_gated_names_are_rejected():
    assert spec.unknown_gated(frozenset({"articles"})) == {"articles"}
    assert spec.unknown_gated(frozenset({"messages"})) == set()


def test_primary_keys_are_part_of_the_column_map():
    for table in spec.PLAN:
        mapped = {c.target for c in table.columns}
        assert set(table.pk) <= mapped, f"{table.target}: primary key columns must be mapped"


def test_audit_log_hashes_are_never_inserted():
    """V28's trigger owns the chain; carrying the source hashes would defeat it."""
    audit = spec.BY_TARGET["audit_logs"]
    written = {c.target for c in audit.insert_columns}
    assert "row_hash" not in written and "prev_hash" not in written


@pytest.mark.parametrize("table", [s.target for s in spec.PLAN])
def test_extract_order_is_deterministic(table):
    assert spec.BY_TARGET[table].extract_order


def oracle_timestamp_columns() -> set[tuple[str, str]]:
    """(table, column) for every TIMESTAMP(6) column the migrations declare."""
    found: set[tuple[str, str]] = set()
    create = re.compile(r"CREATE\s+TABLE\s+(\w+)\s*\((.*?)\n\);", re.IGNORECASE | re.DOTALL)
    alter = re.compile(r"ALTER\s+TABLE\s+(\w+)\s+ADD\s*\((.*?)\);", re.IGNORECASE | re.DOTALL)
    column = re.compile(r"^\s*(\w+)\s+TIMESTAMP\b", re.IGNORECASE | re.MULTILINE)
    for name in sorted(os.listdir(MIGRATIONS)):
        if not name.endswith(".sql"):
            continue
        with open(os.path.join(MIGRATIONS, name), encoding="utf-8") as handle:
            body = "\n".join(
                line for line in handle.read().splitlines() if not line.strip().startswith("--")
            )
        for pattern in (create, alter):
            for table, block in pattern.findall(body):
                for col in column.findall(block):
                    found.add((table.lower(), col.lower()))
    return found


def test_every_timestamp_column_uses_the_timestamp_transform():
    """A TIMESTAMP bound as text is rejected by Oracle unless it happens to
    match NLS_TIMESTAMP_FORMAT -- which is a binding failure mid-load, not a
    preflight one. The transform normalises the SQLite dev source; this test
    is what keeps the two lists in step."""
    declared = oracle_timestamp_columns()
    for table in spec.PLAN:
        for col in table.columns:
            if (table.target, col.target) in declared:
                assert col.transform == "timestamp", (
                    f"{table.target}.{col.target} is TIMESTAMP in Oracle but is mapped with "
                    f"transform={col.transform!r}"
                )


def test_the_timestamp_transform_is_not_used_on_other_columns():
    declared = oracle_timestamp_columns()
    for table in spec.PLAN:
        for col in table.columns:
            if col.transform == "timestamp":
                assert (table.target, col.target) in declared, (
                    f"{table.target}.{col.target} is not a TIMESTAMP column in Oracle"
                )


def test_reconciliation_never_selects_the_same_column_twice():
    """Oracle rejects a duplicated column once ORDER BY names it (ORA-00960);
    SQLite happily returns it twice, so only a real Oracle run found this."""
    for table in spec.PLAN:
        columns = reconcile.target_columns(table)
        assert len(columns) == len(set(columns)), f"{table.target}: {columns}"
