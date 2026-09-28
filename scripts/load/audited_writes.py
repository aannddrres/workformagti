"""Simultaneous audited writes against an isolated test stack -- never production.

Every audited mutation takes the audit chain's single tip row
(audit_chain_state id=1, SELECT ... FOR UPDATE inside trg_audit_logs_chain,
V28) and keeps it until its own transaction commits, so audited writes from
the whole portal queue behind one another. V28 left a lock timeout for "if
real contention is observed". This observes it.

Three bursts of N simultaneous requests, one request per test operator:

  login     first sign-in of N test operators: LOGIN audit row and a session
  quiz      one quiz submission each: SUBMIT_QUIZ_ATTEMPT audit row
  favorite  one bookmark each: no audit row -- the control

The target must run with APP_ENV=development and ALLOW_DEV_LOGIN=true, which
is what creates test_operator_ accounts on first sign-in. The login burst
spends N of the 60-per-minute budget the throttle gives one address, so N is
capped at 50 and a run should not follow another sign-in heavy run (the E2E
suite, for one) within the same minute.

With --oracle-dsn -- a SYSTEM connection to the same PDB, password in
ORACLE_SYSTEM_PASSWORD -- it also samples v$session every 20 ms during each
burst and reports the most sessions seen waiting at once on a row lock in
AUDIT_CHAIN_STATE, and on any row lock at all.

    python scripts/load/audited_writes.py --base-url http://localhost:8080 \\
        --users 50 --oracle-dsn localhost:1522/XEPDB1
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import httpx

MAX_USERS = 50

AUDIT_LOCK_WAITERS = """
    SELECT COUNT(*) FROM v$session s JOIN dba_objects o ON o.object_id = s.row_wait_obj#
    WHERE s.event = 'enq: TX - row lock contention' AND o.object_name = 'AUDIT_CHAIN_STATE'
"""
ANY_ROW_LOCK_WAITERS = "SELECT COUNT(*) FROM v$session WHERE event = 'enq: TX - row lock contention'"


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    index = min(len(ordered) - 1, max(0, round(fraction * (len(ordered) - 1))))
    return ordered[index]


class LockSampler:
    """Polls v$session on its own connection while a burst runs."""

    def __init__(self, dsn: str, user: str, password: str) -> None:
        import oracledb

        self._connection = oracledb.connect(user=user, password=password, dsn=dsn)
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.max_audit_waiters = 0
        self.max_any_waiters = 0
        self.samples = 0
        self.samples_with_audit_waiters = 0

    def __enter__(self) -> "LockSampler":
        self._stop.clear()
        self.max_audit_waiters = self.max_any_waiters = 0
        self.samples = self.samples_with_audit_waiters = 0
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *exc_info: object) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join()

    def _run(self) -> None:
        with self._connection.cursor() as cursor:
            while not self._stop.is_set():
                audit_waiters = cursor.execute(AUDIT_LOCK_WAITERS).fetchone()[0]
                any_waiters = cursor.execute(ANY_ROW_LOCK_WAITERS).fetchone()[0]
                self.samples += 1
                if audit_waiters:
                    self.samples_with_audit_waiters += 1
                self.max_audit_waiters = max(self.max_audit_waiters, audit_waiters)
                self.max_any_waiters = max(self.max_any_waiters, any_waiters)
                time.sleep(0.02)

    def close(self) -> None:
        self._connection.close()


def burst(name: str, calls: list, sampler: LockSampler | None) -> dict:
    """Fires every call at the same instant and times each one from that instant."""
    barrier = threading.Barrier(len(calls))

    def fire(call):
        barrier.wait()
        started = time.perf_counter()
        try:
            status = call()
        except httpx.HTTPError as error:
            status = f"{type(error).__name__}"
        return status, time.perf_counter() - started

    def run() -> list:
        with ThreadPoolExecutor(max_workers=len(calls)) as pool:
            return list(pool.map(fire, calls))

    wall_started = time.perf_counter()
    if sampler is not None:
        with sampler:
            results = run()
    else:
        results = run()
    wall = time.perf_counter() - wall_started

    latencies = [seconds for _, seconds in results]
    statuses: dict[str, int] = {}
    for status, _ in results:
        statuses[str(status)] = statuses.get(str(status), 0) + 1
    summary = {
        "burst": name,
        "requests": len(results),
        "statuses": statuses,
        "p50_ms": round(statistics.median(latencies) * 1000),
        "p90_ms": round(percentile(latencies, 0.9) * 1000),
        "max_ms": round(max(latencies) * 1000),
        "wall_ms": round(wall * 1000),
    }
    if sampler is not None:
        summary.update({
            "max_waiting_on_audit_chain": sampler.max_audit_waiters,
            "max_waiting_on_any_row_lock": sampler.max_any_waiters,
            "samples": sampler.samples,
            "samples_with_audit_chain_waiters": sampler.samples_with_audit_waiters,
        })
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--users", type=int, default=MAX_USERS)
    parser.add_argument("--oracle-dsn", help="SYSTEM connection to the target's PDB, for lock sampling")
    parser.add_argument("--oracle-user", default="system")
    args = parser.parse_args()
    if not 1 <= args.users <= MAX_USERS:
        parser.error(f"--users must be between 1 and {MAX_USERS} (the per-address sign-in budget)")

    sampler = None
    if args.oracle_dsn:
        password = os.environ.get("ORACLE_SYSTEM_PASSWORD")
        if not password:
            parser.error("--oracle-dsn needs ORACLE_SYSTEM_PASSWORD in the environment")
        sampler = LockSampler(args.oracle_dsn, args.oracle_user, password)

    run = uuid.uuid4().hex[:8]
    with httpx.Client(base_url=args.base_url, timeout=60) as client:
        def login(email: str) -> httpx.Response:
            return client.post("/api/auth/login", json={"email": email, "password": "load"})

        admin = login("admin@magti.ge")
        admin.raise_for_status()
        admin_headers = {"Authorization": f"Bearer {admin.json()['access_token']}"}

        category = client.post("/api/categories", headers=admin_headers,
                               json={"name": f"load {run}", "parent_id": None})
        category.raise_for_status()
        article = client.post("/api/articles", headers=admin_headers, json={
            "title": f"load {run}", "content": "<p>load</p>", "category_id": category.json()["id"],
            "tags": None, "target_departments": ["All"], "status": "published", "published_at": None,
            "attachment_url": None, "audience_profile": "all", "visible_to_tech_info": True,
            "visible_to_service_center": False, "is_draft": False, "quiz_enabled": True,
            "notify_operators": False,
        })
        article.raise_for_status()
        article_id = article.json()["id"]
        client.put(f"/api/articles/{article_id}/quiz/admin", headers=admin_headers, json={"questions": [{
            "id": None, "question_text": "load", "position": 0,
            "answers": [{"id": None, "answer_text": text, "is_correct": index == 0, "position": index}
                        for index, text in enumerate(("yes", "no"))],
        }]}).raise_for_status()

        emails = [f"test_operator_load_{run}_{index}@magti.ge" for index in range(args.users)]
        tokens: dict[str, str] = {}

        def sign_in(email: str):
            def call():
                response = login(email)
                if response.status_code == 200:
                    tokens[email] = response.json()["access_token"]
                return response.status_code
            return call

        results = [burst("login", [sign_in(email) for email in emails], sampler)]
        if len(tokens) < len(emails):
            print(f"only {len(tokens)} of {len(emails)} sign-ins succeeded; later bursts use those",
                  file=sys.stderr)
        signed_in = [{"Authorization": f"Bearer {token}"} for token in tokens.values()]
        if not signed_in:
            print(json.dumps(results, indent=2))
            return 1

        quiz = client.get(f"/api/articles/{article_id}/quiz", headers=signed_in[0])
        quiz.raise_for_status()
        question = quiz.json()["questions"][0]
        answers = {str(question["id"]): question["answers"][0]["id"]}

        def submit(headers: dict):
            return lambda: client.post(f"/api/articles/{article_id}/quiz/attempt", headers=headers,
                                       json={"answers": answers}).status_code

        def bookmark(headers: dict):
            return lambda: client.post("/api/favorites", headers=headers,
                                       json={"item_type": "article", "item_id": article_id}).status_code

        results.append(burst("quiz", [submit(headers) for headers in signed_in], sampler))
        results.append(burst("favorite", [bookmark(headers) for headers in signed_in], sampler))

    if sampler is not None:
        sampler.close()
    print(json.dumps({"run": run, "users": args.users, "bursts": results}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
