"""Check 2 -- the database does not die, it hangs (Release It!: timeouts).

    python scripts/qa/check_db_hang.py [--port 8091]

Needs MAGTI_QA seeded (seed_world.py). Starts its own fault proxy
(fault_proxy.py, :15210 -> :1521) and its own backend on --port reaching
Oracle through it, then:

  A. a row lock held by another session while 40 editors save that article;
  B. the network to Oracle freezes for 45 s (no answer, no hang-up);
  C. every connection is reset and new ones refused for 20 s;
  D. every packet delayed 200 ms for 30 s.

During each, an operator keeps opening other pages. What is measured: how
long a request can wait, whether unrelated pages keep working, whether the
pool (hikaricp_*) drains, whether anyone is signed out (401), and how long
recovery takes once the fault ends.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent))
from qa_lib import Portal, Report, db  # noqa: E402

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
CONTROL = "http://127.0.0.1:15299"


def proxy(cmd: str) -> dict:
    return httpx.get(f"{CONTROL}{cmd}", timeout=5).json()


def pool_metrics(port: int) -> dict:
    try:
        text = httpx.get(f"http://127.0.0.1:{port}/actuator/prometheus", timeout=3).text
    except httpx.HTTPError:
        return {"error": "metrics unreachable"}
    out = {}
    for name in ("hikaricp_connections_active", "hikaricp_connections_pending",
                 "hikaricp_connections_timeout_total", "tomcat_threads_busy_threads",
                 "executor_active_threads"):
        m = re.search(rf"^{name}\{{[^}}]*\}} ([0-9.eE+-]+)", text, re.M)
        if m:
            out[name] = float(m.group(1))
    return out


class Prober(threading.Thread):
    """An operator opening ordinary pages every second, timing each."""

    def __init__(self, session, paths, timeout=120):
        super().__init__(daemon=True)
        self.s, self.paths, self.timeout = session, paths, timeout
        self.samples: list[tuple[float, int, float]] = []  # (t, status, seconds)
        self.stop = threading.Event()

    def run(self):
        i = 0
        while not self.stop.is_set():
            path = self.paths[i % len(self.paths)]
            i += 1
            t0 = time.time()
            try:
                status = self.s.get(path, timeout=self.timeout).status_code
            except httpx.HTTPError as e:
                status = -1 if isinstance(e, httpx.TimeoutException) else -2
            self.samples.append((t0, status, time.time() - t0))
            time.sleep(1)

    def summary(self, since=0.0):
        xs = [x for x in self.samples if x[0] >= since]
        if not xs:
            return {"n": 0}
        lat = [x[2] for x in xs]
        return {"n": len(xs), "ok": sum(1 for x in xs if 200 <= x[1] < 300),
                "401": sum(1 for x in xs if x[1] == 401),
                "other_4xx": sum(1 for x in xs if 400 <= x[1] < 500 and x[1] != 401), "5xx": sum(1 for x in xs if x[1] >= 500),
                "timeouts": sum(1 for x in xs if x[1] == -1), "max_s": round(max(lat), 1),
                "median_s": round(statistics.median(lat), 2)}


def recovery_seconds(session, path, limit=180) -> float:
    t0 = time.time()
    while time.time() - t0 < limit:
        try:
            if session.get(path, timeout=10).status_code == 200:
                return round(time.time() - t0, 1)
        except httpx.HTTPError:
            pass
        time.sleep(0.5)
    return float("inf")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8091)
    ap.add_argument("--freeze", type=int, default=45)
    a = ap.parse_args()
    rep = Report("check_db_hang")
    world = json.loads((HERE / "results/world.json").read_text(encoding="utf-8"))
    # Both in ტექნიკური, the department of test_operator_w001: an article for
    # another department is a correct 404 and would read as a failure here.
    tech = [r["article_id"] for r in world["readings"] if r["department"] == "ტექნიკური"]
    locked_article, other_article = tech[0], tech[1]

    px = subprocess.Popen([sys.executable, str(HERE / "fault_proxy.py")])
    time.sleep(1)
    env_cmd = ["bash", str(HERE / "qa-backend.sh"), "start", str(a.port)]
    subprocess.run(env_cmd, check=True, env={**__import__("os").environ,
                                               "QA_DB_URL": "jdbc:oracle:thin:@127.0.0.1:15210/orclpdb1"})
    results = {}
    try:
        portal = Portal(a.port, client_ip="10.88.0.1")
        op = Portal(a.port, client_ip="10.88.0.2").login("test_operator_w001@magti.ge")
        editor = portal.login("content@magti.ge")
        pages = ["/api/compliance/my-readings", f"/api/articles/{other_article}", "/api/news",
                 "/api/notifications/summary"]
        base = Prober(op, pages)
        base.start()
        time.sleep(8)
        base.stop.set()
        base.join()
        rep.note(f"baseline: {base.summary()}")

        # -- A. a row lock ------------------------------------------------------
        art = editor.ok("get", f"/api/articles/{locked_article}")
        body = {k: art.get(k) for k in ("title", "content", "category_id", "status", "published_at",
                                        "attachment_url", "audience_profile", "visible_to_tech_info",
                                        "visible_to_service_center", "quiz_enabled", "target_departments")}
        body.update(tags=None, is_draft=False)
        with db() as c:
            cur = c.cursor()
            cur.execute("SELECT id FROM articles WHERE id = :i FOR UPDATE", i=locked_article)
            t_lock = time.time()
            pr = Prober(op, pages)
            pr.start()
            outcomes = []

            def save(n):
                t0 = time.time()
                try:
                    r = editor.put(f"/api/articles/{locked_article}",
                                   {**body, "title": f"{art['title']}"}, timeout=200)
                    outcomes.append((r.status_code, time.time() - t0))
                except httpx.HTTPError as e:
                    outcomes.append((type(e).__name__, time.time() - t0))

            pool = ThreadPoolExecutor(40)
            for n in range(40):
                pool.submit(save, n)
            time.sleep(20)
            m_during = pool_metrics(a.port)
            time.sleep(40)
            during = pr.summary(t_lock + 5)
            c.rollback()  # release the lock after 60 s
        pool.shutdown(wait=True)
        pr.stop.set()
        pr.join()
        waits = sorted(o[1] for o in outcomes)
        codes = {}
        for o in outcomes:
            codes[str(o[0])] = codes.get(str(o[0]), 0) + 1
        results["A"] = {"pool_during": m_during, "operator_during": during, "saves": codes,
                        "longest_save_wait_s": round(waits[-1], 1)}
        rep.note(f"A row lock: {results['A']}")
        # 40 editors on one row is more than the pool (30): pages suffer until
        # the 30 s limit (PO-53) frees the connections -- recorded, not judged.
        rep.note(f"A 40 saves on one locked row: operator {during}, pool {m_during}")
        rep.check(waits[-1] < 40, "A: a save waiting on a lock gives up at the 30 s limit (PO-53)",
                  f"longest wait {waits[-1]:.0f} s, outcomes {codes}")

        # -- A2. one slow audited transaction --------------------------------------
        # V28's trigger takes audit_chain_state row 1 FOR UPDATE on every audit
        # insert and keeps it until commit: every audited action in the portal
        # queues behind whichever transaction is slowest to commit. Hold that
        # lock for 30 s, as one slow transaction would, while 60 operators sign
        # in and confirm readings, and watch an unrelated reader.
        with db() as c:
            cur = c.cursor()
            cur.execute("SELECT tip_hash FROM audit_chain_state WHERE id = 1 FOR UPDATE")
            t_lock = time.time()
            pr = Prober(op, pages)
            pr.start()
            acts = []

            def operator_signs_in(i):
                t0 = time.time()
                try:
                    p = Portal(a.port, timeout=120, client_ip=f"10.89.0.{i}")
                    r = p.http.post("/api/auth/login",
                                    json={"email": f"test_operator_w{i:03d}@magti.ge", "password": "x"})
                    acts.append((r.status_code, time.time() - t0))
                except httpx.HTTPError as e:
                    acts.append((type(e).__name__, time.time() - t0))

            pool = ThreadPoolExecutor(60)
            for i in range(300, 360):
                pool.submit(operator_signs_in, i)
            time.sleep(15)
            m_chain = pool_metrics(a.port)
            time.sleep(15)
            during = pr.summary(t_lock + 3)
            c.rollback()
        pool.shutdown(wait=True)
        pr.stop.set()
        pr.join()
        codes = {}
        for x in acts:
            codes[str(x[0])] = codes.get(str(x[0]), 0) + 1
        results["A2"] = {"pool_during": m_chain, "reader_during": during, "sign_ins": codes,
                         "longest_sign_in_s": round(max(x[1] for x in acts), 1)}
        rep.note(f"A2 audit-chain lock 30s: {results['A2']}")
        longest = max(x[1] for x in acts)
        # Up to 10 s waiting for a free connection (Hikari) plus 5 s for the lock.
        rep.check(longest < 16, "A2: an audited action behind a held audit lock gives up within seconds (PO-53, V54)",
                  f"longest sign-in {longest:.1f} s, outcomes {codes}")
        rep.check(during.get("5xx", 1) <= 1, "A2: reading pages keep working while the audit chain is held",
                  f"reader {during}, pool {m_chain}")

        # -- A3. a real long audited transaction: bulk archive of 100 articles ----
        # Each article is audited inside the one transaction, so the chain's
        # lock is held from the first article to the commit. Meanwhile 40
        # operators sign in (an audited write each).
        ids = list(range(20, 120))
        waits = []

        def sign_in_during(i):
            t0 = time.time()
            try:
                p = Portal(a.port, timeout=120, client_ip=f"10.89.1.{i}")
                r = p.http.post("/api/auth/login",
                                json={"email": f"test_operator_w{i:03d}@magti.ge", "password": "x"})
                waits.append((r.status_code, time.time() - t0))
            except httpx.HTTPError as e:
                waits.append((type(e).__name__, time.time() - t0))

        # 40 sign-ins at one instant are slow on their own (password hashing
        # is CPU): measure that first, so only the bulk's own effect is judged.
        waits.clear()
        with ThreadPoolExecutor(40) as warm:
            list(warm.map(sign_in_during, range(340, 380)))
        alone = sorted(w[1] for w in waits)
        waits.clear()
        t_bulk = time.time()
        pool = ThreadPoolExecutor(41)
        bulk = pool.submit(lambda: editor.post("/api/articles/bulk-archive", {"ids": ids, "archive": True},
                                               timeout=300))
        time.sleep(0.3)
        for i in range(380, 420):
            pool.submit(sign_in_during, i)
        bulk_r = bulk.result()
        bulk_s = time.time() - t_bulk
        pool.shutdown(wait=True)
        editor.post("/api/articles/bulk-archive", {"ids": ids, "archive": False}, timeout=300)
        lat = sorted(w[1] for w in waits)
        results["A3"] = {"bulk_status": bulk_r.status_code, "bulk_s": round(bulk_s, 1),
                         "sign_in_p50_s": round(lat[len(lat) // 2], 2), "sign_in_max_s": round(lat[-1], 2),
                         "sign_in_codes": {str(c): sum(1 for w in waits if w[0] == c) for c in {w[0] for w in waits}}}
        rep.note(f"A3 bulk archive of 100 while 40 sign in: {results['A3']}")
        results["A3"]["alone_p50_s"] = round(alone[len(alone) // 2], 2)
        rep.check(lat[len(lat) // 2] <= alone[len(alone) // 2] + 1.0,
                  "A3 a colleague's bulk archive adds no wait to sign-ins (PO-53)",
                  f"sign-in p50 {lat[len(lat) // 2]:.1f} s with the bulk, {alone[len(alone) // 2]:.1f} s without; "
                  f"the bulk took {bulk_s:.1f} s")

        # -- B. the network freezes ----------------------------------------------
        pr = Prober(op, pages)
        pr.start()
        time.sleep(3)
        proxy("/mode?set=freeze")
        t_freeze = time.time()
        time.sleep(a.freeze / 2)
        m_freeze = pool_metrics(a.port)
        health = None
        try:
            health = httpx.get(f"http://127.0.0.1:{a.port}/actuator/health/liveness", timeout=5).status_code
        except httpx.HTTPError as e:
            health = type(e).__name__
        try:
            ready = httpx.get(f"http://127.0.0.1:{a.port}/actuator/health/readiness", timeout=5).status_code
        except httpx.HTTPError as e:
            ready = type(e).__name__
        time.sleep(a.freeze / 2)
        proxy("/mode?set=pass")
        rec = recovery_seconds(op, pages[0])
        time.sleep(5)
        pr.stop.set()
        pr.join(timeout=150)
        during = pr.summary(t_freeze)
        results["B"] = {"pool_during": m_freeze, "liveness": health, "readiness": ready,
                        "operator": during, "recovery_s": rec}
        rep.note(f"B freeze {a.freeze}s: {results['B']}")
        rep.check(health == 200, "B: liveness stays UP (pods are not restarted for a DB fault)", str(health))
        rep.check(ready != 200, "B: readiness reports the frozen DB (taken out of rotation)", str(ready))
        rep.check(during.get("401", 0) == 0, "B: nobody is signed out", str(during))
        rep.check(during.get("max_s", 999) <= a.freeze + 15,
                  "B: no request waits much longer than the freeze itself", str(during))
        rep.check(rec < 15, "B: pages answer again within 15 s of the network coming back", f"{rec} s")

        # -- C. connections reset, new ones refused ------------------------------
        pr = Prober(op, pages)
        pr.start()
        time.sleep(3)
        proxy("/mode?set=refuse")
        proxy("/cut")
        t_cut = time.time()
        time.sleep(20)
        proxy("/mode?set=pass")
        rec = recovery_seconds(op, pages[0])
        time.sleep(5)
        pr.stop.set()
        pr.join(timeout=60)
        during = pr.summary(t_cut)
        results["C"] = {"operator": during, "recovery_s": rec}
        rep.note(f"C outage 20s: {results['C']}")
        rep.check(during.get("401", 0) == 0, "C: nobody is signed out by a DB outage (PO-44)", str(during))
        rep.check(during.get("max_s", 999) < 15, "C: during an outage a request fails within 15 s", str(during))
        rep.check(rec < 15, "C: recovery within 15 s of the DB coming back", f"{rec} s")

        # -- D. a slow network ---------------------------------------------------
        pr = Prober(op, pages)
        pr.start()
        proxy("/mode?set=delay&ms=200")
        t_slow = time.time()
        time.sleep(30)
        proxy("/mode?set=pass")
        pr.stop.set()
        pr.join(timeout=60)
        during = pr.summary(t_slow)
        results["D"] = {"operator": during}
        rep.note(f"D 200ms per packet: {results['D']}")
        rep.check(during.get("ok", 0) == during.get("n", -1), "D: everything still works, only slower",
                  str(during))
    finally:
        subprocess.run(["bash", str(HERE / "qa-backend.sh"), "stop", str(a.port)])
        px.terminate()
        (HERE / "results").mkdir(exist_ok=True)
        (HERE / "results/db_hang.json").write_text(json.dumps(results, indent=1, default=str))
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
