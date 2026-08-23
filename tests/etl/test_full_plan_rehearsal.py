"""
The whole plan, every table, on the rehearsal target.

Same fixture the Oracle job uses (tests/etl/legacy_source.py), so a typo in
it -- a column that does not exist, a row that violates a foreign key --
fails here in half a second instead of ten minutes into a CI job with a
database container. What it cannot check is anything Oracle-specific; that
is test_oracle_rehearsal.py's job, and it is skipped without an instance.
"""
from __future__ import annotations

import sqlite3

import pytest

from scripts.etl import audit_chain
from scripts.etl import load as load_mod
from scripts.etl import preflight, reconcile, spec
from scripts.etl.db import SourceDb, SqliteTarget

import legacy_source


def _create_target(path: str, specs) -> None:
    conn = sqlite3.connect(path)
    for table in specs:
        columns = [c.target for c in table.columns]
        if table.target == "audit_logs":
            columns += ["prev_hash", "row_hash"]
        conn.execute(f"CREATE TABLE {table.target} ({', '.join(columns)})")
    conn.commit()
    conn.close()


@pytest.fixture(scope="module")
def full(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("full-plan")
    source_path, target_path = str(tmp / "legacy.db"), str(tmp / "rehearsal.db")
    facts = legacy_source.seed(source_path)
    specs = spec.load_order()
    _create_target(target_path, specs)
    source = SourceDb(f"sqlite:///{source_path}").connect()
    target = SqliteTarget(target_path).connect()
    yield source, target, specs, facts
    source.close()
    target.close()


def test_the_fixture_covers_every_default_table(full):
    _, _, specs, facts = full
    assert set(facts["expected_rows"]) == {s.target for s in specs}


def test_the_fixture_audit_chain_is_what_postgres_would_have_written(full):
    """The Oracle job compares against these hashes, so they must be right."""
    _, _, _, facts = full
    rows = facts["audit"]
    assert rows[0]["prev_hash"] is None
    for previous, current in zip(rows, rows[1:]):
        assert current["prev_hash"] == previous["row_hash"]
    for row in rows:
        assert row["row_hash"] == audit_chain.row_hash(row)


def test_preflight_passes_on_the_full_fixture(full):
    source, target, specs, _ = full
    blocking = preflight.blocking(preflight.run(source, target, specs))
    assert not blocking, [f"{c.name}: {c.detail}" for c in blocking]


def test_the_500_character_title_is_at_the_limit_not_over_it(full):
    """One character more and preflight would (correctly) refuse the run."""
    _, _, _, facts = full
    assert len(facts["title_500"]) == 500


def test_loading_the_full_plan_reconciles(full):
    source, target, specs, facts = full
    result = load_mod.run(source, target, specs)
    assert result.rows_written == sum(facts["expected_rows"].values())
    report = reconcile.run(source, target, specs)
    assert report.ok, [
        (t.target, t.source_rows, t.target_rows, t.mismatched) for t in report.tables if not t.ok
    ]


def test_timestamps_cross_as_datetimes_not_text(full):
    """The bind Oracle would have rejected: text into a TIMESTAMP column."""
    source, _, specs, _ = full
    articles = spec.BY_TARGET["articles"]
    row = next(iter(source.stream("articles", articles.source_columns, "id")))
    transformed = load_mod.transform_row(articles, row)
    created_at = transformed[[c.target for c in articles.insert_columns].index("created_at")]
    assert hasattr(created_at, "year"), "created_at should be a datetime by the time it is bound"
