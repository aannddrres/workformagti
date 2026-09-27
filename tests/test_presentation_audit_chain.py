import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts" / "presentation"))
from audit_chain import assert_audit_chain_rows  # noqa: E402
from common import PresentationSafetyError  # noqa: E402


def test_valid_chain_follows_hashes_instead_of_identity_order():
    # ID 2 obtains the lock before ID 1; ID 1 is the committed tip.
    rows = [(1, "second", "tip", "tip", 1), (2, "root", "second", "second", 1),
            (3, None, "root", "root", 1)]
    assert assert_audit_chain_rows(rows, 1, "tip") == {"rows": 3, "broken": 0, "synthetic_unhashed": 0}


@pytest.mark.parametrize("rows,tip", [
    ([(1, None, "root", "changed", 1)], "root"),
    ([(1, "missing", "tip", "tip", 1)], "tip"),
    ([(1, None, "root", "root", 1)], "missing"),
    ([(1, "b", "a", "a", 1), (2, "a", "b", "b", 1)], "b"),
    ([(1, None, "a", "a", 1), (2, None, "b", "b", 1)], "b"),
    ([(1, None, "a", "a", 1), (2, "a", "a", "a", 1)], "a"),
    ([(1, None, None, "hash", 1)], None),
    ([(1, None, "root", "root", 1)], None),
])
def test_corruption_or_unreachable_rows_fail(rows, tip):
    with pytest.raises(PresentationSafetyError):
        assert_audit_chain_rows(rows, 1, tip)


def test_empty_chain_has_one_state_with_a_null_tip():
    assert assert_audit_chain_rows([], 1, None)["rows"] == 0
    for state_count in (0, 2):
        with pytest.raises(PresentationSafetyError):
            assert_audit_chain_rows([], state_count, None)
