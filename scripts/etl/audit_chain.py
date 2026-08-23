"""
The audit hash chain, in Python.

Both databases build the same tamper-evidence chain with their own trigger:
`migrate.py`'s `audit_logs_canonical_string` in Postgres, V28's function of
the same name in Oracle. This module is a third implementation of that one
canonical string -- not to write hashes (neither database would accept them)
but to check the other two without needing either engine running.

It buys two things the ETL cannot get any other way:

* **Independent verification of the target.** After the load, every Oracle
  row is re-hashed here from the values Oracle actually returns. If the two
  agree, the field values in Oracle are exactly the ones the chain was
  computed over -- proof that survives even when the source chain cannot be
  compared directly (see below).
* **A guard against the triggers drifting apart.** The comparison this
  migration leans on only means something while both canonical strings are
  identical, so `tests/etl/test_audit_chain_parity.py` parses both SQL
  sources and fails if a field is added, reordered or given a different
  NULL sentinel on one side only.

### Where the two chains legitimately differ

Postgres picks the chain tip with
`SELECT row_hash ... WHERE row_hash IS NOT NULL ORDER BY id DESC LIMIT 1` --
it *skips* rows written before the trigger existed. Oracle keeps the tip in
`audit_chain_state` and hashes every row it is given, pre-chain rows
included. So if the source holds any unchained row, Oracle's chain diverges
from the source's from that row on, permanently and correctly. That is not
damage, and reconcile.py must not report it as such: it says the direct
comparison is not applicable and falls back to the independent verification
above.

Two smaller divergences, both recorded rather than papered over:

* Oracle reduces `details` to its first 32000 characters before hashing
  (STANDARD_HASH takes no CLOB); Postgres hashes the whole value. Identical
  for every real row -- audit_trail.py clips each diffed value at 200
  characters -- but preflight counts anything longer, because for such a row
  the two hashes would differ for a reason that has nothing to do with the
  migration.
* A NULL `admin_id` would make Postgres's whole canonical string NULL
  (`||` propagates NULL) where Oracle treats it as empty. It cannot occur in
  migrated data -- models.py has the column NOT NULL, and only V39 made it
  nullable on the Oracle side -- so this port follows Oracle's reading.
"""
from __future__ import annotations

import hashlib
import re
from datetime import datetime
from decimal import Decimal
from typing import Any, Iterable, Iterator

US = "\x1f"  # unit separator
NUL = "\x00"  # NULL sentinel -- deliberately not "skip the field"
DETAILS_LIMIT = 32000  # V28: DBMS_LOB.SUBSTR(p_details, 32000, 1)

# The canonical string's field order. Shared by both triggers; the parity
# test checks this list against both SQL sources rather than the reverse.
FIELDS: tuple[str, ...] = (
    "id",
    "prev_hash",
    "admin_id",
    "action",
    "item_type",
    "item_id",
    "timestamp",
    "category",
    "details",
    "admin_name_snapshot",
    "admin_email_snapshot",
    "item_name_snapshot",
    "ip_address",
    "user_agent",
)

# Fields with no NULL sentinel: NOT NULL on both sides, so the triggers
# concatenate them raw.
NOT_NULL_FIELDS = frozenset({"id", "admin_id", "action", "item_type", "item_id", "timestamp"})

_TIMESTAMP_TEXT = re.compile(r"^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d{1,6})?$")


def _number(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, Decimal):
        return str(int(value)) if value == value.to_integral_value() else str(value)
    return str(int(value))


def _timestamp(value: Any) -> str:
    """'YYYY-MM-DD"T"HH24:MI:SS.FF6' in Oracle, '...US' in Postgres."""
    if value is None:
        return ""
    if isinstance(value, datetime):
        return value.strftime("%Y-%m-%dT%H:%M:%S.%f")
    text = str(value)
    if not _TIMESTAMP_TEXT.match(text):
        return text
    stamp = text.replace(" ", "T")
    head, _, frac = stamp.partition(".")
    return f"{head}.{(frac or '').ljust(6, '0')}"


def _text(value: Any, *, nullable: bool, limit: int | None = None) -> str:
    if value is None:
        return NUL if nullable else ""
    text = value.read() if hasattr(value, "read") else str(value)
    if limit is not None:
        text = text[:limit]
    return text


def canonical_string(row: dict[str, Any]) -> str:
    parts: list[str] = []
    for field in FIELDS:
        value = row.get(field)
        if field in ("id", "admin_id", "item_id"):
            parts.append(_number(value))
        elif field == "timestamp":
            parts.append(_timestamp(value))
        elif field == "details":
            parts.append(_text(value, nullable=True, limit=DETAILS_LIMIT))
        else:
            parts.append(_text(value, nullable=field not in NOT_NULL_FIELDS))
    return US.join(parts)


def row_hash(row: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_string(row).encode("utf-8")).hexdigest()


def recompute(rows: Iterable[dict[str, Any]], tip: str | None = None) -> Iterator[tuple[Any, str, str]]:
    """Replay the chain: each row hashed over the previous row's hash."""
    for row in rows:
        linked = dict(row)
        linked["prev_hash"] = tip
        digest = row_hash(linked)
        yield row.get("id"), tip, digest
        tip = digest
