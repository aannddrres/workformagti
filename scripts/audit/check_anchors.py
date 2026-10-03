"""Compare the hourly AUDIT_CHAIN_ANCHOR log lines with the audit table.

The hash chain proves that the rows still in audit_logs were not edited. It
cannot prove that none were cut off the end, or that someone with the schema
owner's rights did not rewrite a stretch and recompute every hash after it.
For that the backend logs the chain's end every hour
(audit/AuditChainAnchor.java), and the log leaves the pod for IT's
collection, where the database's users cannot rewrite it. This tool does the
comparison those lines exist for:

    python scripts/audit/check_anchors.py app.log [more.log ...]
    kubectl logs ... | python scripts/audit/check_anchors.py -

For every distinct anchor it checks that row ``id`` is still in audit_logs
and still carries ``row_hash``, and that the table has at least
``chained_rows`` chained rows. It only reads (SELECT on audit_logs). The
connection comes from ORACLE_DB_URL (or ORACLE_DB_DSN), ORACLE_DB_USER and
ORACLE_DB_PASSWORD, as for the legacy importer.

Exit status 0: every anchor holds. 1: at least one does not -- the ledger
was cut back or rewritten after that hour. 2: no anchors found to check.

A database RESTORED from a backup fails here on purpose: everything after
the backup moment is gone, which is exactly what truncation looks like.
Record the restore (when, which backup, why) before closing such a finding.
"""

from __future__ import annotations

import argparse
import os
import re
import sys

ANCHOR = re.compile(r"AUDIT_CHAIN_ANCHOR id=(\d+) row_hash=([0-9a-f]{64}) chained_rows=(\d+)")


def parse(lines) -> dict[int, tuple[str, int, str]]:
    """id -> (row_hash, chained_rows, first log line); later duplicates of an
    id must agree, and a disagreement is itself reported by check()."""
    found: dict[int, tuple[str, int, str]] = {}
    conflicts = []
    for line in lines:
        m = ANCHOR.search(line)
        if not m:
            continue
        rid, h, n = int(m.group(1)), m.group(2), int(m.group(3))
        if rid in found and found[rid][0] != h:
            conflicts.append(rid)
        found.setdefault(rid, (h, n, line.strip().split()[0][:32]))
    if conflicts:
        raise ValueError(f"the logs disagree with themselves about rows {sorted(set(conflicts))}")
    return found


def check(anchors: dict[int, tuple[str, int, str]], lookup, chained_total: int) -> list[str]:
    """``lookup(ids)`` -> {id: row_hash}. Returns one line per broken anchor."""
    problems = []
    current = lookup(list(anchors))
    for rid in sorted(anchors):
        want, n, when = anchors[rid]
        got = current.get(rid)
        if got is None:
            problems.append(f"row {rid} (anchored {when}) is GONE")
        elif got != want:
            problems.append(f"row {rid} (anchored {when}) has a DIFFERENT hash: {got[:16]}... not {want[:16]}...")
        if chained_total < n:
            problems.append(f"anchor {when} counted {n} chained rows; the table now has {chained_total}")
    return problems


def connect():
    import oracledb

    raw = os.getenv("ORACLE_DB_URL") or os.getenv("ORACLE_DB_DSN") or "localhost:1521/orclpdb1"
    dsn = raw.split("@", 1)[1] if raw.startswith("jdbc:") else raw
    return oracledb.connect(user=os.environ["ORACLE_DB_USER"], password=os.environ["ORACLE_DB_PASSWORD"],
                            dsn=dsn.lstrip("/"))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("logs", nargs="+", help="log files, or - for standard input")
    a = ap.parse_args()
    lines = []
    for path in a.logs:
        if path == "-":
            lines.extend(sys.stdin)
        else:
            with open(path, encoding="utf-8", errors="replace") as fh:
                lines.extend(fh)
    try:
        anchors = parse(lines)
    except ValueError as e:
        print(f"BROKEN: {e}")
        return 1
    if not anchors:
        print("no AUDIT_CHAIN_ANCHOR lines found")
        return 2
    with connect() as conn:
        cur = conn.cursor()
        chained = cur.execute("SELECT COUNT(*) FROM audit_logs WHERE row_hash IS NOT NULL").fetchone()[0]

        def lookup(ids):
            out = {}
            for i in range(0, len(ids), 500):
                chunk = ids[i:i + 500]
                binds = ",".join(f":{k}" for k in range(len(chunk)))
                for rid, h in cur.execute(f"SELECT id, row_hash FROM audit_logs WHERE id IN ({binds})", chunk):
                    out[int(rid)] = h
            return out

        problems = check(anchors, lookup, chained)
    if problems:
        print(f"BROKEN: {len(problems)} problem(s) across {len(anchors)} anchor(s)")
        for p in problems:
            print("  " + p)
        return 1
    print(f"OK: all {len(anchors)} anchors hold ({chained} chained rows now)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
