"""
How long does the cutover take?

That number decides the change window, and until it is measured it is a
guess. This harness generates a legacy database shaped like the real one --
same tables, same column mix, Georgian text, multi-kilobyte article bodies,
a real Postgres-style audit chain -- at a chosen scale, then runs the actual
ETL against a real Oracle and times every phase separately.

    python -m scripts.etl.bench --scale 0.1 --oracle-dsn localhost:1521/XEPDB1 \\
        --oracle-user magti_app --oracle-password '***'

What it measures, and why each is its own number:

* **load** -- bulk inserts. Scales with row count and CLOB size.
* **audit load** -- the same, but every row goes through V28's BEFORE INSERT
  trigger, which takes a row lock on the chain tip and computes a SHA-256.
  It cannot be batched away and it cannot run in parallel, so it is measured
  apart from everything else.
* **reconcile** -- reads *both* databases back and fingerprints every row. It
  is not a formality: at real volumes it can cost more than the load.
* **hash comparison** and **chain verification** -- the audit evidence.

The row counts in PROFILE are a model of a 600-user portal under the
180-day retention `retention.py` applies, not measurements of the real
database. Replace them with `SELECT COUNT(*)` from production and the
throughput numbers this prints turn into a real window estimate.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sqlite3
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from . import audit_chain, preflight, reconcile, spec, uploads
from . import load as load_mod
from .db import OracleTarget, SourceDb

# A 600-user call centre, one year in, with the 180-day purge in retention.py
# already trimming the two log tables. Structural tables do not grow with
# usage, so --scale leaves them alone.
PROFILE: dict[str, int] = {
    "teams": 25,
    "categories": 60,
    "users": 600,
    "tags": 250,
    "articles": 900,
    "news": 500,
    "video_instructions": 120,
    "article_target_departments": 1_800,
    "article_history": 2_700,
    "news_history": 900,
    "required_readings": 350,
    "audit_action_translations": 60,
    "webhook_configs": 3,
    "quiz_questions": 2_500,
    "quiz_answers": 9_000,
    # everything below grows with use
    "read_statuses": 180_000,
    "article_read_receipts": 200_000,
    "article_view_logs": 1_200_000,
    "quiz_attempts": 35_000,
    "search_logs": 600_000,
    "user_notes": 5_000,
    "favorites": 6_000,
    "tags_mapping": 3_000,
    "audit_logs": 400_000,
}

GROWS_WITH_USE = frozenset({
    "read_statuses", "article_read_receipts", "article_view_logs", "quiz_attempts",
    "search_logs", "user_notes", "favorites", "tags_mapping", "audit_logs",
})

KA = (
    "ტარიფის ცვლილება მოქმედებს ყველა აბონენტისთვის. ოპერატორმა უნდა გადაამოწმოს "
    "ხელშეკრულების პირობები და მომხმარებელს განუმარტოს ახალი პაკეტის დეტალები. "
)
START = datetime(2026, 2, 1, 8, 0, 0)


def georgian(target_chars: int) -> str:
    return (KA * (target_chars // len(KA) + 1))[:target_chars]


def scaled(scale: float) -> dict[str, int]:
    counts = {}
    for table, base in PROFILE.items():
        counts[table] = max(1, int(base * scale)) if table in GROWS_WITH_USE else base
    return counts


@dataclass
class Phase:
    name: str
    seconds: float
    rows: int = 0

    @property
    def rows_per_second(self) -> float:
        return round(self.rows / self.seconds, 1) if self.seconds > 0 and self.rows else 0.0


@dataclass
class BenchResult:
    scale: float
    counts: dict[str, int] = field(default_factory=dict)
    phases: list[Phase] = field(default_factory=list)
    ok: bool = True
    notes: list[str] = field(default_factory=list)

    @property
    def total_rows(self) -> int:
        return sum(self.counts.values())

    @property
    def cutover_seconds(self) -> float:
        """Everything a change window actually has to contain."""
        return round(sum(p.seconds for p in self.phases if p.name != "generate"), 1)


# ------------------------------------------------------------------ generate


def _timestamps(count: int, spread_days: int = 180):
    step = timedelta(seconds=max(1, spread_days * 86400 // max(count, 1)))
    moment = START
    for _ in range(count):
        moment += step
        yield moment.strftime("%Y-%m-%d %H:%M:%S.%f")


def _insert(conn, table: str, columns: list[str], rows, batch: int = 5000) -> int:
    sql = f"INSERT INTO {table} ({', '.join(columns)}) VALUES ({', '.join('?' * len(columns))})"
    total, chunk = 0, []
    for row in rows:
        chunk.append(row)
        if len(chunk) >= batch:
            conn.executemany(sql, chunk)
            total += len(chunk)
            chunk = []
    if chunk:
        conn.executemany(sql, chunk)
        total += len(chunk)
    return total


def generate(path: str, counts: dict[str, int], *, seed: int = 7) -> None:
    random.seed(seed)
    if os.path.exists(path):
        os.remove(path)
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA journal_mode = OFF")
    conn.execute("PRAGMA synchronous = OFF")
    for table in spec.PLAN:
        columns = list(dict.fromkeys(table.source_columns))
        if table.target == "audit_logs":
            columns += ["prev_hash", "row_hash"]
        conn.execute(f"CREATE TABLE {table.source} ({', '.join(columns)})")

    n = counts
    ts = _timestamps(n["users"])
    _insert(conn, "teams", ["id", "name", "created_at"],
            ((i, f"ჯგუფი {i:02d}", START.strftime("%Y-%m-%d %H:%M:%S.%f")) for i in range(1, n["teams"] + 1)))
    _insert(conn, "categories", ["id", "name", "parent_id", "slug", "is_active"],
            ((i, f"კატეგორია {i}", (i // 10) or None, f"cat-{i}", 1) for i in range(1, n["categories"] + 1)))
    _insert(conn, "users",
            ["id", "email", "name", "department", "position", "phone", "role", "is_active",
             "last_active", "hashed_password", "permissions", "team_id", "manager_id",
             "last_news_viewed_at", "card_style"],
            ((i, f"user{i}@magti.ge", f"თანამშრომელი {i}", "ტექნიკური", "ოპერატორი",
              f"+9955{i:08d}", "operator" if i > 30 else "manager", 1, next(ts),
              "$2b$12$" + "x" * 22, '["reports.export"]' if i % 50 == 0 else None,
              (i % n["teams"]) + 1, None if i == 1 else ((i % 30) + 1), None, "corporate")
             for i in range(1, n["users"] + 1)))
    _insert(conn, "tags", ["id", "name", "created_at"],
            ((i, f"თეგი-{i}", START.strftime("%Y-%m-%d %H:%M:%S.%f")) for i in range(1, n["tags"] + 1)))

    body = georgian(4000)
    ats = _timestamps(n["articles"])
    _insert(conn, "articles",
            ["id", "title", "content", "category_id", "tags", "target_department",
             "audience_profile", "created_at", "updated_at", "version", "author_id", "status",
             "youtube_id", "published_at", "attachment_url", "last_verified_at",
             "visible_to_tech_info", "visible_to_service_center", "is_draft", "quiz_enabled"],
            ((i, f"სტატია {i} — {georgian(60)}", body, (i % n["categories"]) + 1, "ტარიფი",
              "All", "all", (t := next(ats)), t, 3, (i % 30) + 1, "published", None, t,
              "/uploads/file-%d.pdf" % (i % 200), t, 1, 0, 0, 1 if i % 10 == 0 else 0)
             for i in range(1, n["articles"] + 1)))
    nts = _timestamps(n["news"])
    _insert(conn, "news",
            ["id", "title", "content", "target_department", "created_at", "attachment_url",
             "version", "visible_to_tech_info", "visible_to_service_center", "expires_at",
             "is_draft", "author_id"],
            ((i, f"სიახლე {i}", georgian(2000), "All", next(nts), None, 1, 1, 0, None, 0,
              (i % 30) + 1) for i in range(1, n["news"] + 1)))
    _insert(conn, "video_instructions",
            ["id", "title", "video_url", "category", "target_department", "created_at",
             "views_count", "tags", "is_archived"],
            ((i, f"ვიდეო {i}", f"https://video.example/{i}", "ზოგადი", "All",
              START.strftime("%Y-%m-%d %H:%M:%S.%f"), i * 3, None, 0)
             for i in range(1, n["video_instructions"] + 1)))
    _insert(conn, "article_target_departments", ["article_id", "department"],
            _unique_departments(n))
    hts = _timestamps(n["article_history"])
    _insert(conn, "article_history",
            ["id", "article_id", "title", "content", "updated_at", "updated_by", "version_id"],
            ((i, (i % n["articles"]) + 1, f"ძველი სათაური {i}", georgian(1500), next(hts),
              (i % 30) + 1, i) for i in range(1, n["article_history"] + 1)))
    nhts = _timestamps(n["news_history"])
    _insert(conn, "news_history",
            ["id", "news_id", "title", "content", "attachment_url", "updated_at", "updated_by"],
            ((i, (i % n["news"]) + 1, f"ძველი სიახლე {i}", georgian(800), None, next(nhts),
              (i % 30) + 1) for i in range(1, n["news_history"] + 1)))
    rts = _timestamps(n["required_readings"])
    _insert(conn, "required_readings",
            ["id", "item_type", "item_id", "target_department", "due_date", "priority"],
            ((i, "article", (i % n["articles"]) + 1, "All", next(rts), "normal")
             for i in range(1, n["required_readings"] + 1)))
    _insert(conn, "audit_action_translations", ["id", "action", "label_ka"],
            ((i, f"ACTION_{i}", f"მოქმედება {i}") for i in range(1, n["audit_action_translations"] + 1)))
    _insert(conn, "webhook_configs", ["id", "url", "is_active", "trigger_actions"],
            ((i, f"https://hooks.example/{i}", 1, "LOGIN_FAILED")
             for i in range(1, n["webhook_configs"] + 1)))
    _insert(conn, "quiz_questions", ["id", "article_id", "question_text", "position"],
            ((i, (i % n["articles"]) + 1, georgian(120), i % 5) for i in range(1, n["quiz_questions"] + 1)))
    _insert(conn, "quiz_answers", ["id", "question_id", "answer_text", "is_correct", "position"],
            ((i, (i % n["quiz_questions"]) + 1, georgian(60), 1 if i % 4 == 0 else 0, i % 4)
             for i in range(1, n["quiz_answers"] + 1)))

    # -- the tables that grow with use ------------------------------------
    _insert(conn, "read_statuses",
            ["id", "user_id", "required_reading_id", "status", "read_at",
             "operator_department_snapshot"],
            _read_statuses(n))
    _insert(conn, "article_read_receipts",
            ["id", "article_id", "article_title_snapshot", "article_version", "operator_id",
             "operator_name_snapshot", "operator_email_snapshot",
             "operator_department_snapshot", "read_at"],
            _receipts(n))
    _insert(conn, "article_view_logs",
            ["id", "article_id", "article_title_snapshot", "article_version", "operator_id",
             "operator_name_snapshot", "operator_email_snapshot",
             "operator_department_snapshot", "viewed_at"],
            _view_logs(n))
    _insert(conn, "quiz_attempts",
            ["id", "article_id", "article_version", "user_id", "attempt_number", "score",
             "total_questions", "passed", "created_at"],
            _attempts(n))
    _insert(conn, "search_logs",
            ["id", "user_id", "search_term", "timestamp", "has_results", "results_found"],
            _searches(n))
    _insert(conn, "user_notes", ["id", "user_id", "article_id", "content", "created_at", "updated_at"],
            _notes(n))
    _insert(conn, "favorites", ["id", "user_id", "item_type", "item_id"], _favorites(n))
    _insert(conn, "tags_mapping", ["id", "tag_id", "item_type", "item_id"], _tag_mappings(n))
    _insert(conn, "audit_logs",
            ["id", "admin_id", "action", "item_type", "item_id", "timestamp", "category",
             "details", "admin_name_snapshot", "admin_email_snapshot", "item_name_snapshot",
             "ip_address", "user_agent", "prev_hash", "row_hash"],
            _audit(n))
    conn.commit()
    conn.close()


def _unique_departments(n):
    seen, out, i = set(), [], 0
    while len(out) < n["article_target_departments"]:
        article = (i % n["articles"]) + 1
        dept = f"დეპარტამენტი {(i // n['articles']) % 5}"
        if (article, dept) not in seen:
            seen.add((article, dept))
            out.append((article, dept))
        i += 1
    return out


def _read_statuses(n):
    ts = _timestamps(n["read_statuses"])
    for i in range(n["read_statuses"]):
        yield (i + 1, (i % n["users"]) + 1, (i // n["users"]) % n["required_readings"] + 1,
               "read", next(ts), "ტექნიკური")


def _receipts(n):
    ts = _timestamps(n["article_read_receipts"])
    for i in range(n["article_read_receipts"]):
        yield (i + 1, (i % n["articles"]) + 1, f"სტატია {(i % n['articles']) + 1}", 3,
               (i // n["articles"]) % n["users"] + 1, f"თანამშრომელი {(i % n['users']) + 1}",
               f"user{(i % n['users']) + 1}@magti.ge", "ტექნიკური", next(ts))


def _view_logs(n):
    ts = _timestamps(n["article_view_logs"])
    for i in range(n["article_view_logs"]):
        yield (i + 1, (i % n["articles"]) + 1, f"სტატია {(i % n['articles']) + 1}", 3,
               (i % n["users"]) + 1, f"თანამშრომელი {(i % n['users']) + 1}",
               f"user{(i % n['users']) + 1}@magti.ge", "ტექნიკური", next(ts))


def _attempts(n):
    ts = _timestamps(n["quiz_attempts"])
    for i in range(n["quiz_attempts"]):
        yield (i + 1, (i % n["articles"]) + 1, 3, (i % n["users"]) + 1, (i % 3) + 1, 4, 5,
               1 if i % 3 else 0, next(ts))


def _searches(n):
    ts = _timestamps(n["search_logs"])
    for i in range(n["search_logs"]):
        yield (i + 1, (i % n["users"]) + 1, f"ძებნა {i % 500}", next(ts), 1, i % 20)


def _notes(n):
    ts = _timestamps(n["user_notes"])
    for i in range(n["user_notes"]):
        t = next(ts)
        yield (i + 1, (i % n["users"]) + 1, (i % n["articles"]) + 1, georgian(150), t, t)


def _favorites(n):
    for i in range(n["favorites"]):
        yield (i + 1, (i % n["users"]) + 1, "article", (i // n["users"]) % n["articles"] + 1)


def _tag_mappings(n):
    for i in range(n["tags_mapping"]):
        yield (i + 1, (i % n["tags"]) + 1, "article", (i // n["tags"]) % n["articles"] + 1)


def _audit(n):
    """Audit rows carrying the chain the Postgres trigger would have written."""
    ts = _timestamps(n["audit_logs"])
    tip = None
    for i in range(1, n["audit_logs"] + 1):
        row = {
            "id": i, "admin_id": (i % 30) + 1, "action": "UPDATE", "item_type": "article",
            "item_id": (i % n["articles"]) + 1, "timestamp": next(ts), "category": "CONTENT",
            "details": json.dumps({"title": {"old": f"სტატია {i}", "new": f"სტატია {i} v2"}},
                                  ensure_ascii=False),
            "admin_name_snapshot": f"თანამშრომელი {(i % 30) + 1}",
            "admin_email_snapshot": f"user{(i % 30) + 1}@magti.ge",
            "item_name_snapshot": f"სტატია {(i % n['articles']) + 1}",
            "ip_address": f"10.0.{i % 256}.{(i // 256) % 256}", "user_agent": "Mozilla/5.0",
            "prev_hash": tip,
        }
        digest = audit_chain.row_hash(row)
        tip = digest
        yield (row["id"], row["admin_id"], row["action"], row["item_type"], row["item_id"],
               row["timestamp"], row["category"], row["details"], row["admin_name_snapshot"],
               row["admin_email_snapshot"], row["item_name_snapshot"], row["ip_address"],
               row["user_agent"], row["prev_hash"], digest)


# --------------------------------------------------------------------- run


def reset(target, specs) -> None:
    for table in reversed(specs):
        target.execute(f"DELETE FROM {table.target}")
    target.execute("DELETE FROM stored_files")
    target.execute("DELETE FROM user_permission_overrides")
    target.execute("UPDATE audit_chain_state SET tip_hash = NULL WHERE id = 1")
    target.commit()
    for table in specs:
        if table.identity:
            target.identity_restore(table.target)


def _timed(name: str, rows: int, fn):
    started = time.monotonic()
    value = fn()
    return Phase(name, round(time.monotonic() - started, 2), rows), value


def run(source_path: str, target, counts: dict[str, int], scale: float,
        uploads_dir: str | None) -> BenchResult:
    result = BenchResult(scale=scale, counts=counts)
    specs = spec.load_order()
    source = SourceDb(f"sqlite:///{source_path}").connect()
    try:
        reset(target, specs)

        phase, checks = _timed("preflight", result.total_rows,
                               lambda: preflight.run(source, target, specs))
        result.phases.append(phase)
        blocking = preflight.blocking(checks)
        if blocking:
            result.ok = False
            result.notes.append(f"preflight blocked: {[c.name for c in blocking]}")
            return result

        audit_spec = [s for s in specs if s.target == "audit_logs"]
        rest = [s for s in specs if s.target != "audit_logs"]

        phase, _ = _timed("load (bulk)", sum(counts[s.target] for s in rest),
                          lambda: load_mod.run(source, target, rest))
        result.phases.append(phase)

        # Separate on purpose: every row here goes through V28's trigger.
        phase, _ = _timed("load audit_logs (hash-chain trigger)", counts["audit_logs"],
                          lambda: load_mod.run(source, target, audit_spec))
        result.phases.append(phase)

        if uploads_dir:
            phase, up = _timed("uploads (BLOB)", 0,
                               lambda: uploads.load(source, target, uploads_dir))
            phase.rows = up.files_found
            result.phases.append(phase)
            result.notes.append(f"attachments: {up.files_found} files, {up.bytes_total} bytes")

        phase, tables = _timed("reconcile (row fingerprints)", result.total_rows * 2,
                               lambda: [reconcile.compare_table(source, target, s) for s in specs])
        result.phases.append(phase)
        bad = [t.target for t in tables if not t.ok]
        if bad:
            result.ok = False
            result.notes.append(f"reconcile mismatches: {bad}")

        phase, audit_hash = _timed("reconcile audit hashes", counts["audit_logs"] * 2,
                                   lambda: reconcile.compare_audit_hashes(source, target))
        result.phases.append(phase)
        if not audit_hash["ok"]:
            result.ok = False
            result.notes.append(f"audit hash mismatch: {audit_hash['detail']}")

        phase, integrity = _timed("verify chain (re-hash in Python)", counts["audit_logs"],
                                  lambda: reconcile.verify_target_chain(target))
        result.phases.append(phase)
        if not integrity["ok"]:
            result.ok = False
            result.notes.append(f"chain integrity: {integrity['detail']}")

        result.notes.append(audit_hash["detail"])
        result.notes.append(integrity["detail"])
    finally:
        source.close()
    return result


def render(result: BenchResult) -> str:
    lines = [
        f"scale={result.scale}  rows={result.total_rows:,}  "
        f"verdict={'OK' if result.ok else 'PROBLEM'}",
        "",
        f"{'phase':44} {'seconds':>9} {'rows':>12} {'rows/s':>10}",
        "-" * 78,
    ]
    for phase in result.phases:
        lines.append(
            f"{phase.name:44} {phase.seconds:>9.2f} {phase.rows:>12,} "
            f"{phase.rows_per_second:>10,.0f}"
        )
    lines += [
        "-" * 78,
        f"{'CUTOVER TOTAL (excludes generation)':44} {result.cutover_seconds:>9.2f}",
        "",
    ]
    lines += [f"note: {n}" for n in result.notes]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="scripts.etl.bench", description=__doc__)
    parser.add_argument("--scale", type=float, default=0.05,
                        help="multiplier on the usage-driven tables (1.0 = the modelled portal)")
    parser.add_argument("--oracle-dsn", default=os.environ.get("ETL_ORACLE_DSN", ""))
    parser.add_argument("--oracle-user", default=os.environ.get("ETL_ORACLE_USER", ""))
    parser.add_argument("--oracle-password", default=os.environ.get("ETL_ORACLE_PASSWORD", ""))
    parser.add_argument("--work-dir", default="/tmp/etl-bench")
    parser.add_argument("--uploads", type=int, default=0, help="number of attachment files to generate")
    parser.add_argument("--upload-kb", type=int, default=200)
    parser.add_argument("--json", default="", help="also write the result as JSON here")
    args = parser.parse_args(argv)

    if not args.oracle_dsn:
        raise SystemExit("--oracle-dsn (or ETL_ORACLE_DSN) is required")

    os.makedirs(args.work_dir, exist_ok=True)
    source_path = os.path.join(args.work_dir, f"legacy-{args.scale}.db")
    counts = scaled(args.scale)

    started = time.monotonic()
    generate(source_path, counts)
    generation = round(time.monotonic() - started, 2)

    uploads_dir = None
    if args.uploads:
        uploads_dir = os.path.join(args.work_dir, "uploads")
        os.makedirs(uploads_dir, exist_ok=True)
        payload = os.urandom(args.upload_kb * 1024)
        for i in range(args.uploads):
            with open(os.path.join(uploads_dir, f"file-{i}.pdf"), "wb") as handle:
                handle.write(b"%PDF-1.4" + payload)

    target = OracleTarget(args.oracle_dsn, args.oracle_user, args.oracle_password).connect()
    try:
        result = run(source_path, target, counts, args.scale, uploads_dir)
    finally:
        target.close()
    result.phases.insert(0, Phase("generate (fixture, not part of a cutover)", generation,
                                  result.total_rows))
    print(render(result))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump({
                "scale": result.scale, "ok": result.ok, "counts": result.counts,
                "phases": [{"name": p.name, "seconds": p.seconds, "rows": p.rows,
                            "rows_per_second": p.rows_per_second} for p in result.phases],
                "cutover_seconds": result.cutover_seconds, "notes": result.notes,
            }, handle, ensure_ascii=False, indent=2)
    return 0 if result.ok else 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
