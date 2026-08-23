"""
Reconciliation: proving the two databases say the same thing.

Row counts alone prove almost nothing -- they pass just as happily when a
Georgian title arrived mojibaked, a timestamp shifted by a time zone, or a
boolean inverted. So every table is compared row by row on a canonical
fingerprint of its mapped columns, and the first mismatches are reported
with their primary keys.

audit_logs gets a second, stronger check. Oracle's V28 trigger recomputes
the hash chain from the same canonical string migrate.py's Postgres trigger
used, over the same ids, in the same order. If a single audited field did
not survive the crossing, the hashes diverge from that row on. Comparing
them is the closest thing this migration has to a proof.
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Iterator

from . import transforms
from .spec import NOT_MIGRATED, TableSpec

SAMPLE_LIMIT = 10
US = "\x1f"  # unit separator, same sentinel style as the audit chain
NUL = "\x00"

_TIMESTAMP_TEXT = re.compile(r"^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d{1,6})?$")


def canon(value: Any) -> str:
    """One value, one canonical string, on either side of the crossing.

    Three normalisations earn their place:

    * ``''`` and NULL collapse together. Oracle stores an empty VARCHAR2 as
      NULL -- that is the database's semantics, not a migration defect, and
      preflight reports how many such values exist so the fact stays visible.
    * datetimes and timestamp-shaped strings both reduce to one ISO form,
      because SQLite hands back text where Postgres and Oracle hand back
      datetime objects.
    * integral floats/Decimals reduce to their integer form: Oracle NUMBER
      comes back as int, Decimal or float depending on scale.
    """
    if value is None or value == "":
        return NUL
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (bytes, bytearray)):
        return hashlib.sha256(bytes(value)).hexdigest()
    if isinstance(value, datetime):
        return value.strftime("%Y-%m-%dT%H:%M:%S.%f")
    if isinstance(value, date):
        return value.strftime("%Y-%m-%dT00:00:00.000000")
    if isinstance(value, (int, Decimal, float)):
        as_decimal = Decimal(str(value))
        if as_decimal == as_decimal.to_integral_value():
            return str(int(as_decimal))
        return str(as_decimal.normalize())
    text = str(value)
    if _TIMESTAMP_TEXT.match(text):
        stamp = text.replace(" ", "T")
        if "." not in stamp:
            stamp += ".000000"
        else:
            head, frac = stamp.split(".")
            stamp = f"{head}.{frac.ljust(6, '0')}"
        return stamp
    return text


def fingerprint(values: tuple) -> str:
    return hashlib.sha256(US.join(canon(v) for v in values).encode("utf-8")).hexdigest()


@dataclass
class TableReport:
    target: str
    source_rows: int = 0
    target_rows: int = 0
    mismatched: int = 0
    missing_in_target: list[Any] = field(default_factory=list)
    extra_in_target: list[Any] = field(default_factory=list)
    samples: list[Any] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return (
            self.source_rows == self.target_rows
            and not self.mismatched
            and not self.missing_in_target
            and not self.extra_in_target
        )


@dataclass
class Report:
    tables: list[TableReport] = field(default_factory=list)
    audit_hash: dict[str, Any] = field(default_factory=dict)
    identity: list[dict[str, Any]] = field(default_factory=list)
    untouched: list[dict[str, Any]] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return (
            all(t.ok for t in self.tables)
            and self.audit_hash.get("ok", True)
            and all(i.get("ok", True) for i in self.identity)
            and all(u.get("ok", True) for u in self.untouched)
        )


def _keyed(rows: Iterator[tuple], key_len: int) -> dict[tuple, tuple]:
    return {row[:key_len]: row[key_len:] for row in rows}


def compare_table(source, target, spec: TableSpec) -> TableReport:
    report = TableReport(target=spec.target)
    report.source_rows = source.count(spec.source)
    report.target_rows = target.count(spec.target)

    key_len = len(spec.pk)
    compare_cols = [c for c in spec.columns if c.checksum]
    target_cols = [*spec.pk, *[c.target for c in compare_cols]]

    def source_rows() -> Iterator[tuple]:
        cols = [*spec.pk, *spec.source_columns]
        for row in source.stream(spec.source, cols, spec.extract_order):
            key = row[:key_len]
            by_source = dict(zip(spec.source_columns, row[key_len:]))
            yield key + tuple(
                transforms.apply(c.transform, by_source[c.source]) for c in compare_cols
            )

    target_rows = target.stream(spec.target, target_cols, ", ".join(spec.pk))

    if spec.pk == ("id",):
        # Numeric single-column key: both sides stream in the same order, so
        # they can be walked in lockstep without holding either in memory.
        _walk_sorted(report, source_rows(), target_rows, key_len)
    else:
        # A text component in the key means the two databases may not agree on
        # collation order. These tables are small; compare them as maps.
        left = _keyed(source_rows(), key_len)
        right = _keyed(target_rows, key_len)
        report.missing_in_target = [k for k in left if k not in right][:SAMPLE_LIMIT]
        report.extra_in_target = [k for k in right if k not in left][:SAMPLE_LIMIT]
        for key, values in left.items():
            if key in right and fingerprint(values) != fingerprint(right[key]):
                report.mismatched += 1
                if len(report.samples) < SAMPLE_LIMIT:
                    report.samples.append(key)
    return report


def _walk_sorted(report: TableReport, left: Iterator[tuple], right: Iterator[tuple], key_len: int) -> None:
    lrow = next(left, None)
    rrow = next(right, None)
    while lrow is not None or rrow is not None:
        if rrow is None or (lrow is not None and canon(lrow[0]) < canon(rrow[0])):
            if len(report.missing_in_target) < SAMPLE_LIMIT:
                report.missing_in_target.append(lrow[:key_len])
            lrow = next(left, None)
        elif lrow is None or canon(rrow[0]) < canon(lrow[0]):
            if len(report.extra_in_target) < SAMPLE_LIMIT:
                report.extra_in_target.append(rrow[:key_len])
            rrow = next(right, None)
        else:
            if fingerprint(lrow[key_len:]) != fingerprint(rrow[key_len:]):
                report.mismatched += 1
                if len(report.samples) < SAMPLE_LIMIT:
                    report.samples.append(lrow[:key_len])
            lrow = next(left, None)
            rrow = next(right, None)


def compare_audit_hashes(source, target) -> dict[str, Any]:
    """Compare the Postgres chain with the one Oracle's trigger rebuilt.

    Only Oracle can answer this: the chain is rebuilt by V28's trigger, which
    no rehearsal target has. Claiming a match against a target that never
    hashed anything would be the one lie this report cannot afford.
    """
    if getattr(target, "kind", "") != "oracle":
        return {
            "ok": True,
            "skipped": True,
            "matched": 0,
            "mismatched": 0,
            "source_unchained": 0,
            "samples": [],
            "detail": "hash-chain comparison skipped: only an Oracle target rebuilds the chain",
        }
    left = source.stream("audit_logs", ["id", "row_hash"], "id")
    right = target.stream("audit_logs", ["id", "row_hash"], "id")
    matched = mismatched = source_unchained = 0
    samples: list[Any] = []
    right_by_id: dict[Any, Any] = {}
    for row_id, row_hash in right:
        right_by_id[int(row_id)] = row_hash
    for row_id, row_hash in left:
        row_id = int(row_id)
        if row_hash is None:
            source_unchained += 1
            continue
        if right_by_id.get(row_id) == row_hash:
            matched += 1
        else:
            mismatched += 1
            if len(samples) < SAMPLE_LIMIT:
                samples.append({"id": row_id, "source": row_hash, "target": right_by_id.get(row_id)})
    return {
        "ok": mismatched == 0,
        "matched": matched,
        "mismatched": mismatched,
        "source_unchained": source_unchained,
        "samples": samples,
        "detail": (
            f"{matched} audit row(s) hash identically on both sides; {mismatched} diverge; "
            f"{source_unchained} source row(s) had no hash to compare (pre-chain rows)"
        ),
    }


def check_identity_high_water(target, specs: list[TableSpec]) -> list[dict[str, Any]]:
    """The identity sequence must resume above the highest migrated id."""
    if getattr(target, "kind", "") != "oracle":
        return [{"ok": True, "table": "*", "detail": "identity check skipped (not an Oracle target)"}]
    results = []
    for spec in specs:
        if not spec.identity:
            continue
        rows = target.query(
            "SELECT s.last_number FROM user_tab_identity_cols i "
            "JOIN user_sequences s ON s.sequence_name = i.sequence_name "
            "WHERE i.table_name = :1",
            (spec.target.upper(),),
        )
        max_id = target.scalar(f"SELECT MAX(id) FROM {spec.target}")
        next_value = int(rows[0][0]) if rows else None
        ok = next_value is not None and (max_id is None or next_value > int(max_id))
        results.append(
            {
                "ok": ok,
                "table": spec.target,
                "max_id": int(max_id) if max_id is not None else None,
                "sequence_next": next_value,
                "detail": (
                    f"identity resumes at {next_value}, above max id {max_id}"
                    if ok
                    else f"identity would reissue an existing id (next={next_value}, max={max_id})"
                ),
            }
        )
    return results


def check_untouched(target) -> list[dict[str, Any]]:
    """Tables the plan says the ETL must not fill had better still be empty."""
    if getattr(target, "kind", "") != "oracle":
        return [
            {
                "ok": True,
                "table": "*",
                "detail": "untouched-table check skipped (not an Oracle target)",
            }
        ]
    expected_seeded = {"departments": 3, "audit_chain_state": 1}
    skip = {"flyway_schema_history", "stored_files", "user_permission_overrides"}
    results = []
    for table in NOT_MIGRATED:
        if table in skip:
            continue
        try:
            count = target.count(table)
        except Exception as exc:  # noqa: BLE001 - a missing table is itself the finding
            results.append({"ok": False, "table": table, "detail": f"could not count: {exc}"})
            continue
        expected = expected_seeded.get(table, 0)
        results.append(
            {
                "ok": count == expected,
                "table": table,
                "detail": f"{count} row(s), expected {expected}",
            }
        )
    return results


def run(source, target, specs: list[TableSpec]) -> Report:
    report = Report()
    for spec in specs:
        report.tables.append(compare_table(source, target, spec))
    if any(spec.target == "audit_logs" for spec in specs):
        report.audit_hash = compare_audit_hashes(source, target)
    report.identity = check_identity_high_water(target, specs)
    report.untouched = check_untouched(target)
    return report
