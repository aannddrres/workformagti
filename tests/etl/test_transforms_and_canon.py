"""
Transforms and the reconciliation fingerprint.

These two have to agree with each other or reconciliation reports damage
that never happened: load.py writes `transform(source_value)` and
reconcile.py compares `canon(transform(source_value))` with `canon(what
Oracle returned)`. Every case below is one of the ways the same fact looks
different on the two sides of the crossing.
"""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal

import pytest

from scripts.etl.reconcile import canon, fingerprint
from scripts.etl.transforms import apply, bool_to_number, json_to_clob, passthrough


def test_booleans_become_oracle_numbers():
    assert bool_to_number(True) == 1
    assert bool_to_number(False) == 0
    assert bool_to_number(None) is None
    assert bool_to_number(1) == 1  # SQLite already stores 0/1


def test_a_non_boolean_integer_in_a_boolean_column_is_refused():
    with pytest.raises(ValueError):
        bool_to_number(2)


def test_permissions_json_survives_georgian_text():
    value = ["articles.edit", "სისტემური"]
    assert json_to_clob(value) == '["articles.edit", "სისტემური"]'


def test_permissions_null_and_empty_are_not_invented_into_a_list():
    assert json_to_clob(None) is None
    assert json_to_clob("   ") is None  # an empty CLOB would fail CHECK (... IS JSON)


def test_malformed_permissions_json_is_reported_not_swallowed():
    with pytest.raises(ValueError):
        json_to_clob("{not json")


def test_timestamps_are_never_shifted():
    """A time zone conversion here would move every audit row by four hours."""
    moment = datetime(2026, 8, 23, 14, 5, 6, 123456)
    assert passthrough(moment) is moment


def test_canon_reads_sqlite_text_and_oracle_datetimes_as_the_same_instant():
    assert canon(datetime(2026, 8, 23, 14, 5, 6, 123456)) == canon("2026-08-23 14:05:06.123456")
    assert canon(datetime(2026, 8, 23, 14, 5, 6)) == canon("2026-08-23T14:05:06")


def test_canon_collapses_empty_string_and_null_as_oracle_does():
    assert canon("") == canon(None)


def test_canon_normalises_number_representations():
    assert canon(5) == canon(Decimal("5")) == canon(5.0) == "5"
    assert canon(Decimal("1.50")) == canon(1.5)


def test_canon_keeps_georgian_text_intact():
    assert canon("ტექნიკური — ჯგუფი 03") == "ტექნიკური — ჯგუფი 03"


def test_fingerprint_separates_shifted_columns():
    """('a', None, 'b') and ('a', 'b', None) must not collide."""
    assert fingerprint(("a", None, "b")) != fingerprint(("a", "b", None))


def test_apply_rejects_an_unknown_transform():
    with pytest.raises(KeyError):
        apply("no_such_transform", 1)
