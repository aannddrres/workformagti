"""A06: controlled bookmark cycles through the shipping nginx -- never production.

The historical A06 is one ``POST /api/favorites`` that took five seconds and
was abandoned by the client (nginx 499), in one E2E run, with no request ID
to follow it by. The owner's D4 (2026-09-25): repeat the action 500 times
under control. If the 499 does not come back, the status is NOT_REPRODUCED,
and production gets an alert on the same signal.

Each cycle is one of three shapes, in rotation, under the browser's 5 s:

  single   POST, then DELETE of the bookmark it made
  double   two POSTs of the same bookmark at once (a double click), then DELETE
  toggle   POST, DELETE, POST, DELETE back to back

Every response's X-Request-ID is kept: nginx generates it and the backend
logs it (RequestTimingFilter), so a slow request can be found in both logs.
A request that times out has no response and so no ID; its time and path
find it in nginx's log, as a 499.

With --oracle-dsn -- a SYSTEM connection to the same PDB, password in
ORACLE_SYSTEM_PASSWORD -- it also samples v$session every 20 ms and reports
the most sessions seen waiting on a row lock at once.

    python scripts/load/favorites_campaign.py --base-url http://localhost:4200 \\
        --cycles 500 --confirm isolated-synthetic-data

The target needs APP_ENV=development and ALLOW_DEV_LOGIN=true (the persona
signs in with any password). Exit 0 only when no request timed out and none
answered 5xx.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

try:
    import httpx
except ImportError:  # the pure helpers are tested without it (requirements-dev.txt)
    httpx = None

SHAPES = ("single", "double", "toggle")
CONFIRMATION = "isolated-synthetic-data"
# The demo (8081) and UAT (8082) stacks hold data people rely on; their
# owners run anything against them by hand.
FORBIDDEN_PORTS = {8081, 8082}
SLOW_MS = 1000.0

ROW_LOCK_WAITERS = "SELECT COUNT(*) FROM v$session WHERE event = 'enq: TX - row lock contention'"


def refuse_target(base_url: str, confirm: str | None) -> str | None:
    """Why this target must not be used, or None when it may."""
    if confirm != CONFIRMATION:
        return f"pass --confirm {CONFIRMATION} after checking the target holds synthetic data only"
    parsed = urlparse(base_url)
    if parsed.hostname not in {"localhost", "127.0.0.1"}:
        return "only a loopback stack is accepted"
    if parsed.port in FORBIDDEN_PORTS:
        return f"port {parsed.port} is the demo or UAT stack"
    return None


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    index = min(len(ordered) - 1, max(0, round(fraction * (len(ordered) - 1))))
    return ordered[index]


def summarize(calls: list[dict]) -> dict:
    """Counts and latencies per method; every timeout, 5xx and slow call listed."""
    statuses: dict[str, int] = {}
    by_method: dict[str, list[float]] = {}
    for call in calls:
        statuses[str(call["status"])] = statuses.get(str(call["status"]), 0) + 1
        if call["status"] != "timeout":
            by_method.setdefault(call["method"], []).append(call["ms"])
    latency = {
        method: {
            "n": len(values),
            "p50": round(statistics.median(values), 1),
            "p90": round(percentile(values, 0.9), 1),
            "p99": round(percentile(values, 0.99), 1),
            "max": round(max(values), 1),
        }
        for method, values in sorted(by_method.items())
    }
    flagged = [c for c in calls
               if c["status"] == "timeout" or (isinstance(c["status"], int) and c["status"] >= 500)
               or c["ms"] >= SLOW_MS]
    return {"requests": len(calls), "statuses": statuses, "latency_ms": latency, "flagged": flagged}


class LockSampler:
    """Polls v$session on its own connection while the campaign runs."""

    def __init__(self, dsn: str, password: str) -> None:
        import oracledb

        self._connection = oracledb.connect(user="system", password=password, dsn=dsn)
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self.max_waiters = 0
        self.samples = 0

    def _run(self) -> None:
        cursor = self._connection.cursor()
        while not self._stop.is_set():
            cursor.execute(ROW_LOCK_WAITERS)
            self.max_waiters = max(self.max_waiters, cursor.fetchone()[0])
            self.samples += 1
            time.sleep(0.02)

    def __enter__(self) -> "LockSampler":
        self._thread.start()
        return self

    def __exit__(self, *_exc) -> None:
        self._stop.set()
        self._thread.join()
        self._connection.close()


class Campaign:
    def __init__(self, base_url: str, token: str, article_id: int, timeout: float) -> None:
        headers = {"Authorization": f"Bearer {token}"}
        # One client per concurrent caller: a double click is two connections.
        self._clients = [httpx.Client(base_url=base_url, headers=headers, timeout=timeout) for _ in range(2)]
        self._article_id = article_id
        self.calls: list[dict] = []
        self._lock = threading.Lock()

    def close(self) -> None:
        for client in self._clients:
            client.close()

    def _call(self, cycle: int, shape: str, method: str, path: str, client: int = 0, **kwargs) -> httpx.Response | None:
        started = time.perf_counter()
        wall = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime())
        try:
            response = self._clients[client].request(method, path, **kwargs)
            status: int | str = response.status_code
            request_id = response.headers.get("X-Request-ID")
        except httpx.TimeoutException:
            response, status, request_id = None, "timeout", None
        elapsed = (time.perf_counter() - started) * 1000
        with self._lock:
            self.calls.append({"cycle": cycle, "shape": shape, "method": method, "path": path,
                               "status": status, "ms": round(elapsed, 1), "request_id": request_id,
                               "utc": wall})
        return response

    def _add(self, cycle: int, shape: str, client: int = 0) -> int | None:
        response = self._call(cycle, shape, "POST", "/api/favorites", client,
                              json={"item_type": "article", "item_id": self._article_id})
        if response is not None and response.status_code == 200:
            return response.json()["id"]
        return None

    def _remove(self, cycle: int, shape: str, favorite_id: int | None) -> None:
        if favorite_id is not None:
            self._call(cycle, shape, "DELETE", f"/api/favorites/{favorite_id}")

    def run_cycle(self, cycle: int) -> None:
        shape = SHAPES[cycle % len(SHAPES)]
        if shape == "single":
            self._remove(cycle, shape, self._add(cycle, shape))
        elif shape == "double":
            start = threading.Barrier(2)

            def click(client: int) -> int | None:
                start.wait()
                return self._add(cycle, shape, client)

            with ThreadPoolExecutor(max_workers=2) as pool:
                ids = list(pool.map(click, (0, 1)))
            # Both answers name the same bookmark; one DELETE removes it.
            self._remove(cycle, shape, next((i for i in ids if i is not None), None))
        else:
            for _ in range(2):
                self._remove(cycle, shape, self._add(cycle, shape))


def sign_in(base_url: str, email: str) -> str:
    response = httpx.post(f"{base_url}/api/auth/login", json={"email": email, "password": "synthetic"}, timeout=10)
    response.raise_for_status()
    return response.json()["access_token"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", required=True, help="the shipping nginx in front of the backend")
    parser.add_argument("--cycles", type=int, default=500)
    parser.add_argument("--timeout", type=float, default=5.0, help="the client's patience, in seconds")
    parser.add_argument("--email", default="test_operator_a06@magti.ge")
    parser.add_argument("--article-id", type=int, help="default: the first article the persona can list")
    parser.add_argument("--oracle-dsn", help="SYSTEM connection for row-lock sampling, e.g. localhost:1522/XEPDB1")
    parser.add_argument("--report", help="write the full JSON report here")
    parser.add_argument("--confirm")
    args = parser.parse_args()

    refusal = refuse_target(args.base_url, args.confirm)
    if refusal:
        print(f"refused: {refusal}", file=sys.stderr)
        return 2

    base_url = args.base_url.rstrip("/")
    token = sign_in(base_url, args.email)
    article_id = args.article_id
    if article_id is None:
        listed = httpx.get(f"{base_url}/api/articles", params={"limit": 1},
                           headers={"Authorization": f"Bearer {token}"}, timeout=10)
        listed.raise_for_status()
        article_id = listed.json()[0]["id"]

    campaign = Campaign(base_url, token, article_id, args.timeout)
    sampler = None
    if args.oracle_dsn:
        sampler = LockSampler(args.oracle_dsn, os.environ["ORACLE_SYSTEM_PASSWORD"])
    started = time.perf_counter()
    try:
        if sampler:
            sampler.__enter__()
        for cycle in range(args.cycles):
            campaign.run_cycle(cycle)
    finally:
        if sampler:
            sampler.__exit__(None, None, None)
        campaign.close()

    report = summarize(campaign.calls)
    report.update({"cycles": args.cycles, "article_id": article_id, "client_timeout_s": args.timeout,
                   "seconds": round(time.perf_counter() - started, 1)})
    if sampler:
        report["max_row_lock_waiters"] = sampler.max_waiters
        report["lock_samples"] = sampler.samples
    if args.report:
        with open(args.report, "w", encoding="utf-8") as handle:
            json.dump({**report, "calls": campaign.calls}, handle, ensure_ascii=False, indent=1)
    printable = {key: value for key, value in report.items() if key != "flagged"}
    print(json.dumps(printable, ensure_ascii=False, indent=1))
    for call in report["flagged"]:
        print(f"FLAGGED cycle={call['cycle']} {call['shape']} {call['method']} status={call['status']} "
              f"ms={call['ms']} request_id={call['request_id']} utc={call['utc']}")
    failed = any(c["status"] == "timeout" or (isinstance(c["status"], int) and c["status"] >= 500)
                 for c in campaign.calls)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
