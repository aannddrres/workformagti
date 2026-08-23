"""
The benchmark's data has to be real data, or the timings measure nothing.

A generator that quietly produced orphaned foreign keys, duplicate unique
keys or over-wide strings would still print a throughput number -- one that
described loading rows Oracle would have rejected. So the fixture is put
through the same preflight the cutover uses, and its audit chain is checked
against the shared canonical implementation.

Runs without Oracle: the scale is tiny and the target is the rehearsal one.
"""
from __future__ import annotations

import sqlite3

import pytest

from scripts.etl import audit_chain, bench, preflight, spec
from scripts.etl import load as load_mod
from scripts.etl import reconcile
from scripts.etl.db import SourceDb, SqliteTarget

SCALE = 0.002  # a few hundred rows: enough to exercise every table


@pytest.fixture(scope="module")
def generated(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("bench-fixture")
    source_path = str(tmp / "legacy.db")
    counts = bench.scaled(SCALE)
    bench.generate(source_path, counts)

    target_path = str(tmp / "rehearsal.db")
    specs = spec.load_order()
    conn = sqlite3.connect(target_path)
    for table in specs:
        columns = [c.target for c in table.columns]
        if table.target == "audit_logs":
            columns += ["prev_hash", "row_hash"]
        conn.execute(f"CREATE TABLE {table.target} ({', '.join(columns)})")
    conn.commit()
    conn.close()

    source = SourceDb(f"sqlite:///{source_path}").connect()
    target = SqliteTarget(target_path).connect()
    yield source, target, specs, counts
    source.close()
    target.close()


def test_scale_touches_only_the_tables_that_grow_with_use():
    half = bench.scaled(0.5)
    assert half["users"] == bench.PROFILE["users"]  # 600 employees is 600 employees
    assert half["articles"] == bench.PROFILE["articles"]
    assert half["article_view_logs"] == bench.PROFILE["article_view_logs"] // 2
    assert half["audit_logs"] == bench.PROFILE["audit_logs"] // 2


def test_the_profile_covers_every_table_the_plan_loads():
    assert set(bench.PROFILE) == {s.target for s in spec.load_order()}


def test_generated_rows_match_the_requested_counts(generated):
    source, _, _, counts = generated
    for table, expected in counts.items():
        assert source.count(table) == expected, table


def test_the_fixture_would_survive_a_real_cutover(generated):
    """Same preflight the cutover runs: FK orphans, widths, unique keys."""
    source, target, specs, _ = generated
    blocking = preflight.blocking(preflight.run(source, target, specs))
    assert not blocking, [f"{c.name}: {c.detail}" for c in blocking]


def test_the_fixture_audit_chain_is_the_one_postgres_would_have_written(generated):
    source, _, _, counts = generated
    columns = ["id", "prev_hash", "admin_id", "action", "item_type", "item_id", "timestamp",
               "category", "details", "admin_name_snapshot", "admin_email_snapshot",
               "item_name_snapshot", "ip_address", "user_agent", "row_hash"]
    previous = None
    checked = 0
    for row in source.stream("audit_logs", columns, "id"):
        values = dict(zip(columns, row))
        assert values["prev_hash"] == previous
        assert values["row_hash"] == audit_chain.row_hash(values)
        previous = values["row_hash"]
        checked += 1
    assert checked == counts["audit_logs"]


def test_the_generated_data_loads_and_reconciles(generated):
    source, target, specs, counts = generated
    result = load_mod.run(source, target, specs)
    assert result.rows_written == sum(counts.values())
    report = reconcile.run(source, target, specs)
    assert report.ok, [(t.target, t.mismatched) for t in report.tables if not t.ok]


def test_article_bodies_are_multi_kilobyte_georgian(generated):
    """CLOB size is most of the load cost -- a fixture of empty strings would
    have made the throughput number meaningless."""
    source, _, _, _ = generated
    body = source.scalar("SELECT content FROM articles WHERE id = 1")
    assert len(body) >= 4000
    assert "ტარიფის" in body
