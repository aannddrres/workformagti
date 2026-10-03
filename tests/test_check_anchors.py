"""scripts/audit/check_anchors.py: the comparison, without a database."""

import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location(
    "check_anchors", Path(__file__).resolve().parents[1] / "scripts" / "audit" / "check_anchors.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

H1, H2, H3 = "a" * 64, "b" * 64, "c" * 64


def line(rid, h, n, ts="2026-10-03T01:00:00+04:00"):
    return f"{ts}  INFO  --- [scheduling-1] g.m.p.audit.AuditChainAnchor : AUDIT_CHAIN_ANCHOR id={rid} row_hash={h} chained_rows={n}\n"


def test_parse_dedupes_replicas_and_ignores_other_lines():
    got = mod.parse([line(10, H1, 10), "noise\n", line(10, H1, 10), line(20, H2, 20)])
    assert sorted(got) == [10, 20]


def test_parse_refuses_logs_that_disagree():
    with pytest.raises(ValueError):
        mod.parse([line(10, H1, 10), line(10, H2, 10)])


def test_intact():
    anchors = mod.parse([line(10, H1, 10), line(20, H2, 20)])
    assert mod.check(anchors, lambda ids: {10: H1, 20: H2}, 25) == []


def test_tail_cut_back():
    anchors = mod.parse([line(10, H1, 10), line(20, H2, 20)])
    problems = mod.check(anchors, lambda ids: {10: H1}, 15)
    assert any("20" in p and "GONE" in p for p in problems)
    assert any("counted 20" in p for p in problems)


def test_rewritten_and_rehashed():
    anchors = mod.parse([line(20, H2, 20)])
    problems = mod.check(anchors, lambda ids: {20: H3}, 20)
    assert problems and "DIFFERENT" in problems[0]
