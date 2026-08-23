"""
The two triggers must agree, or the migration's main evidence is worthless.

reconcile.py leans on one claim: Oracle, given the same audit rows in the
same order, recomputes the same hashes Postgres did. That holds only while
`audit_logs_canonical_string` means the same thing in both databases -- same
fields, same order, same NULL sentinels, same timestamp format.

So these tests read both SQL sources (migrate.py's Postgres function and
V28's Oracle one) and compare them against each other and against the Python
port in scripts/etl/audit_chain.py. Editing one trigger without the other
fails here, at the only moment when fixing it is cheap.
"""
from __future__ import annotations

import hashlib
import os
import re
from datetime import datetime

from scripts.etl import audit_chain

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MIGRATE_PY = os.path.join(REPO_ROOT, "migrate.py")
V28 = os.path.join(
    REPO_ROOT, "java-backend", "src", "main", "resources", "db", "migration",
    "V28__audit_hash_chain.sql",
)


def _postgres_canonical() -> str:
    body = open(MIGRATE_PY, encoding="utf-8").read()
    start = body.index("CREATE OR REPLACE FUNCTION audit_logs_canonical_string(r audit_logs)")
    return body[start : body.index("$$ LANGUAGE sql IMMUTABLE", start)]


def _oracle_canonical() -> str:
    body = "\n".join(
        line for line in open(V28, encoding="utf-8").read().splitlines()
        if not line.strip().startswith("--")
    )
    start = body.index("RETURN\n", body.index("FUNCTION audit_logs_canonical_string"))
    return body[start : body.index("END audit_logs_canonical_string", start)]


def test_postgres_and_oracle_hash_the_same_fields_in_the_same_order():
    postgres = re.findall(r"\br\.(\w+)", _postgres_canonical())
    oracle = [name for name in re.findall(r"\bp_(\w+)\b", _oracle_canonical())]
    # Oracle reduces details through a local variable, so the parameter name
    # appears where the column would; normalise that one indirection.
    oracle_fields = [f for f in oracle if f in audit_chain.FIELDS]
    if "details" not in oracle_fields:
        oracle_fields.insert(list(audit_chain.FIELDS).index("details"), "details")
    assert postgres == list(audit_chain.FIELDS), "the Postgres trigger drifted from FIELDS"
    assert oracle_fields == list(audit_chain.FIELDS), "the Oracle trigger drifted from FIELDS"


def test_both_triggers_use_the_same_separator_and_null_sentinel():
    postgres, oracle = _postgres_canonical(), _oracle_canonical()
    assert r"E'\x1f'" in postgres and r"E'\x00'" in postgres
    # Oracle spells the same two bytes CHR(31)/CHR(0), bound above as us/nulv.
    v28 = open(V28, encoding="utf-8").read()
    assert "CHR(31)" in v28 and "CHR(0)" in v28
    assert " us ||" in oracle and "nulv" in oracle


def test_the_same_fields_carry_a_null_sentinel_on_both_sides():
    postgres_nullable = set(re.findall(r"COALESCE\(r\.(\w+),", _postgres_canonical()))
    oracle_nullable = set(re.findall(r"NVL\(p_(\w+),", _oracle_canonical()))
    # details is NVL'd through its reduced local variable, not inline.
    oracle_nullable.add("details")
    assert postgres_nullable == oracle_nullable
    assert postgres_nullable == set(audit_chain.FIELDS) - audit_chain.NOT_NULL_FIELDS


def test_both_triggers_format_the_timestamp_identically():
    """'US' in Postgres and 'FF6' in Oracle are the same six digits."""
    assert "'YYYY-MM-DD\"T\"HH24:MI:SS.US'" in _postgres_canonical()
    assert "'YYYY-MM-DD\"T\"HH24:MI:SS.FF6'" in _oracle_canonical()


def test_the_python_port_reproduces_the_documented_canonical_string():
    row = {
        "id": 42,
        "prev_hash": None,
        "admin_id": 7,
        "action": "UPDATE",
        "item_type": "article",
        "item_id": 3,
        "timestamp": datetime(2026, 8, 23, 14, 5, 6, 123456),
        "category": None,
        "details": '{"title": "ტარიფი"}',
        "admin_name_snapshot": "გიორგი კაპანაძე",
        "admin_email_snapshot": None,
        "item_name_snapshot": "ტარიფი",
        "ip_address": "10.0.0.5",
        "user_agent": None,
    }
    expected = "\x1f".join([
        "42", "\x00", "7", "UPDATE", "article", "3", "2026-08-23T14:05:06.123456",
        "\x00", '{"title": "ტარიფი"}', "გიორგი კაპანაძე", "\x00", "ტარიფი",
        "10.0.0.5", "\x00",
    ])
    assert audit_chain.canonical_string(row) == expected
    assert audit_chain.row_hash(row) == hashlib.sha256(expected.encode("utf-8")).hexdigest()


def test_a_null_field_cannot_collide_with_a_shifted_one():
    """Why the sentinel exists instead of concat_ws skipping NULLs."""
    base = {f: None for f in audit_chain.FIELDS}
    left = {**base, "category": "a", "details": None}
    right = {**base, "category": None, "details": "a"}
    assert audit_chain.canonical_string(left) != audit_chain.canonical_string(right)


def test_details_is_reduced_exactly_where_oracle_reduces_it():
    assert f"DBMS_LOB.SUBSTR(p_details, {audit_chain.DETAILS_LIMIT}, 1)" in open(
        V28, encoding="utf-8"
    ).read()
    long_row = {f: None for f in audit_chain.FIELDS}
    long_row["details"] = "ა" * (audit_chain.DETAILS_LIMIT + 10)
    clipped = {**long_row, "details": "ა" * audit_chain.DETAILS_LIMIT}
    assert audit_chain.canonical_string(long_row) == audit_chain.canonical_string(clipped)


def test_recompute_links_each_row_to_the_previous_hash():
    rows = [
        {**{f: None for f in audit_chain.FIELDS}, "id": 1, "action": "CREATE"},
        {**{f: None for f in audit_chain.FIELDS}, "id": 2, "action": "UPDATE"},
    ]
    chain = list(audit_chain.recompute(rows))
    assert chain[0][1] is None  # genesis
    assert chain[1][1] == chain[0][2]  # each prev_hash is the previous row_hash
