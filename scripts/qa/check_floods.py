"""Check 7 -- one person asks for far too much. Does everyone else notice?

    python scripts/qa/check_floods.py [--port 8090]

Only sign-in and manual reminders have a rate limit. For each other way one
account can generate work, this sends a burst from that one account while
ten bystanders keep reading, and measures the bystanders:

  E1 30 spreadsheet + PDF exports at once (manager)
  E2 searching nonstop from 20 tabs for 45 s
  E3 40 uploads of 10 MB
  E4 the same article "viewed" 1,000 times
  E5 a quiz answered 200 times in a row (every answer combination)
  E6 600 wrong passwords against a colleague's account from other addresses

Pass: bystanders' p95 under 2 s and no 5xx for them; the flooder gets either
service or a clear refusal, never a 500.
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
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent))
from qa_lib import Portal, Report, db  # noqa: E402

HERE = Path(__file__).resolve().parent


class Bystanders(threading.Thread):
    def __init__(self, port, n=10):
        super().__init__(daemon=True)
        self.sessions = [Portal(port, client_ip=f"10.102.0.{i}").login(f"test_operator_w{i:03d}@magti.ge")
                         for i in range(500, 500 + n)]
        self.lat: list[float] = []
        self.codes: dict[int, int] = {}
        self.stop = threading.Event()

    def run(self):
        paths = ["/api/compliance/my-readings", "/api/articles?limit=20", "/api/notifications/summary",
                 "/api/news"]
        pool = ThreadPoolExecutor(len(self.sessions))

        def one(s, k):
            t0 = time.time()
            try:
                c = s.get(paths[k % len(paths)], timeout=60).status_code
            except httpx.HTTPError:
                c = -1
            self.lat.append(time.time() - t0)
            self.codes[c] = self.codes.get(c, 0) + 1

        k = 0
        while not self.stop.is_set():
            list(pool.map(lambda s: one(s, k), self.sessions))
            k += 1
            time.sleep(0.5)

    def summary(self):
        lat = sorted(self.lat)
        p95 = lat[int(len(lat) * 0.95) - 1] if lat else 0
        return {"n": len(lat), "p95_s": round(p95, 2), "median_s": round(statistics.median(lat), 3) if lat else 0,
                "codes": self.codes}


def measure(rep, port, label, flood):
    b = Bystanders(port)
    b.start()
    time.sleep(3)
    t0 = time.time()
    flood_result = flood()
    took = time.time() - t0
    b.stop.set()
    b.join()
    s = b.summary()
    bad = sum(v for k, v in s["codes"].items() if k >= 500 or k < 0)
    rep.check(s["p95_s"] < 2 and bad == 0, f"{label}: bystanders unaffected",
              f"bystanders {s}; flooder {flood_result}; {took:.0f} s")
    return s, flood_result


def tally(codes):
    out = {}
    for c in codes:
        out[str(c)] = out.get(str(c), 0) + 1
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8090)
    ap.add_argument("--only", default="")
    a = ap.parse_args()
    rep = Report("check_floods")
    results = {}
    want = set(a.only.split(",")) if a.only else None

    # E1 exports ---------------------------------------------------------------
    if not want or "E1" in want:
        # Exports are scoped by team (users.team_id). Put every ტექნიკური
        # operator and manager@ in one team, as the org catalogue would: ~270
        # people, the largest scope one manager could have here.
        with db() as c:
            cur = c.cursor()
            dept = cur.execute("SELECT id FROM departments WHERE stable_key = 'TECHNICAL'").fetchone()[0]
            row = cur.execute("SELECT id FROM teams WHERE stable_key = 'QA-FLOOD'").fetchone()
            if row:
                team = row[0]
            else:
                tv = cur.var(int)
                cur.execute("INSERT INTO teams (name, created_at, department_id, stable_key, is_active) "
                            "VALUES ('QA ტექნიკური', SYSTIMESTAMP, :d, 'QA-FLOOD', 1) RETURNING id INTO :t",
                            d=dept, t=tv)
                team = tv.getvalue()[0]
            cur.execute("UPDATE users SET team_id = :t WHERE department LIKE 'ტექნიკური%' "
                        "OR LOWER(email) = 'manager@magti.ge'", t=team)
            c.commit()
        mgr = Portal(a.port, client_ip="10.102.1.1", timeout=600).login("manager@magti.ge")
        period = {"from": "2026-09-01", "through": "2026-12-31"}

        def e1():
            def one(k):
                path = "/api/export/readings.xlsx" if k % 2 else "/api/export/readings.pdf"
                t0 = time.time()
                try:
                    r = mgr.get(path, params=period, timeout=600)
                    return r.status_code, round(time.time() - t0, 1)
                except httpx.HTTPError as e:
                    return type(e).__name__, round(time.time() - t0, 1)
            jobs = []

            def one_job(k):
                code, took = one(k)
                return code, took

            def submit(k):
                path = "/api/export/readings.xlsx" if k % 2 else "/api/export/readings.pdf"
                t0 = time.time()
                r = mgr.get(path, params=period, timeout=600)
                if r.status_code == 200 and "job_id" in r.text:
                    jobs.append(r.json()["job_id"])
                return r.status_code, round(time.time() - t0, 1)
            t_all = time.time()
            with ThreadPoolExecutor(30) as p:
                got = list(p.map(submit, range(30)))
            # The work happens after the answer: wait for every job to finish.
            final = {}
            while len(final) < len(jobs) and time.time() - t_all < 900:
                for j in jobs:
                    if j not in final:
                        st = mgr.get(f"/api/export/status/{j}").json().get("status")
                        if st in ("completed", "failed", "expired"):
                            final[j] = st
                time.sleep(1)
            return {"codes": tally(g[0] for g in got), "slowest_request_s": max(g[1] for g in got),
                    "jobs": tally(final.values()), "all_done_s": round(time.time() - t_all, 1)}
        results["E1"] = measure(rep, a.port, "E1 30 exports at once", e1)

    # E2 search ----------------------------------------------------------------
    if not want or "E2" in want:
        sr = Portal(a.port, client_ip="10.102.1.2").login("test_operator_w590@magti.ge")
        words = ["ტარიფი", "როუმინგი", "ინტერნეტი", "ბალანსი", "პაკეტი", "კოდი", "MyMagti", "აქცია"]

        def e2():
            end = time.time() + 45
            codes = []

            def tab(k):
                i = k
                while time.time() < end:
                    try:
                        codes.append(sr.get("/api/search/global", params={"q": words[i % len(words)] + str(i % 7)},
                                            timeout=60).status_code)
                    except httpx.HTTPError:
                        codes.append(-1)
                    i += 1
            with ThreadPoolExecutor(20) as p:
                list(p.map(tab, range(20)))
            return {"searches": len(codes), "codes": tally(codes)}
        results["E2"] = measure(rep, a.port, "E2 nonstop search from 20 tabs", e2)
        with db() as c:
            n = c.cursor().execute("SELECT COUNT(*) FROM search_logs WHERE user_id = :u", u=sr.user["id"]).fetchone()[0]
        rep.note(f"E2 search history rows stored for that one person: {n}")

    # E3 uploads ---------------------------------------------------------------
    if not want or "E3" in want:
        ed = Portal(a.port, client_ip="10.102.1.3", timeout=300).login("content@magti.ge")
        blob = os.urandom(10 * 1024 * 1024 - 4096)
        png = b"\x89PNG\r\n\x1a\n" + blob

        def e3():
            def one(k):
                try:
                    return ed.post("/api/upload", files={"file": (f"qa-flood-{k}.png", png, "image/png")},
                                   timeout=300).status_code
                except httpx.HTTPError as e:
                    return type(e).__name__
            with db() as c:
                before = c.cursor().execute("SELECT NVL(SUM(byte_size),0) FROM stored_files").fetchone()[0]
            with ThreadPoolExecutor(40) as p:
                got = list(p.map(one, range(40)))
            with db() as c:
                after = c.cursor().execute("SELECT NVL(SUM(byte_size),0) FROM stored_files").fetchone()[0]
            return {"codes": tally(got), "db_growth_mb": round((after - before) / 1048576)}
        results["E3"] = measure(rep, a.port, "E3 40 uploads of 10 MB", e3)

    # E4 views -----------------------------------------------------------------
    if not want or "E4" in want:
        v = Portal(a.port, client_ip="10.102.1.4").login("test_operator_w591@magti.ge")
        art = 30

        def e4():
            with ThreadPoolExecutor(10) as p:
                got = list(p.map(lambda k: v.post(f"/api/articles/{art}/view").status_code, range(1000)))
            return {"codes": tally(got)}
        results["E4"] = measure(rep, a.port, "E4 one article viewed 1,000 times by one person", e4)
        with db() as c:
            rows = c.cursor().execute("SELECT COUNT(*) FROM article_view_logs WHERE operator_id = :u AND article_id = :a",
                                      u=v.user["id"], a=art).fetchone()[0]
        rep.note(f"E4 view rows stored: {rows} (the activity chart counts rows; per-article viewers count people)")

    # E5 quiz brute force ------------------------------------------------------
    if not want or "E5" in want:
        world = json.loads((HERE / "results/world.json").read_text(encoding="utf-8"))
        qz = next(r for r in world["readings"] if r["quiz"])
        # A new operator each run: earlier attempts would rightly count.
        fresh = f"test_operator_e5_{int(time.time())}@magti.ge"
        Portal(a.port, client_ip="10.102.1.5").login(fresh)
        with db() as c:
            c.cursor().execute("UPDATE users SET department = :d WHERE LOWER(email) = :e", d=qz["department"], e=fresh)
            c.commit()
        q = Portal(a.port, client_ip="10.102.1.5").login(fresh)

        def e5():
            # Wrong on purpose, every time: what an answer-guessing script
            # does after its lucky first try fails. PO-55: three failures,
            # then ten minutes.
            quiz = q.get(f"/api/articles/{qz['article_id']}/quiz")
            if quiz.status_code != 200:
                return {"quiz": quiz.status_code, "text": quiz.text[:120]}
            editor = Portal(a.port, client_ip="10.102.1.6").login("content@magti.ge")
            key = editor.ok("get", f"/api/articles/{qz['article_id']}/quiz/admin")["questions"]
            wrong = {str(qq["id"]): next(an["id"] for an in qq["answers"] if not an["is_correct"]) for qq in key}
            codes = []
            for _ in range(200):
                r = q.post(f"/api/articles/{qz['article_id']}/quiz/attempt", {"answers": wrong})
                codes.append(r.status_code)
            first_refusal = codes.index(429) + 1 if 429 in codes else None
            return {"codes": tally(codes), "first_refused_attempt": first_refusal}
        results["E5"] = measure(rep, a.port, "E5 200 quiz attempts in a row", e5)
        rep.check(results["E5"][1].get("first_refused_attempt") == 4,
                  "E5 the fourth attempt after three failures waits (PO-55)", str(results["E5"][1]))

    # E6 wrong passwords against a colleague --------------------------------------
    if not want or "E6" in want:
        # The development login accepts any password, so a wrong password
        # needs the corporate path: a second backend that signs in through
        # fake_idp.py, which refuses the password "wrong" (invalid_grant).
        import subprocess
        idp = subprocess.Popen([sys.executable, str(HERE / "fake_idp.py"), "--port", "18900"])
        env = dict(os.environ, CORPORATE_AUTH_ENABLED="true", OAUTH_SERVICE_URI="http://127.0.0.1:18900/",
                   OAUTH_CLIENT_ID="qa", OAUTH_SECRET="cWE6cWE=")
        cport = 8094
        subprocess.run(["bash", str(HERE / "qa-backend.sh"), "start", str(cport)], check=True, env=env)
        try:
            victim = "qa.victim@magticom.ge"
            first = Portal(cport, client_ip="10.104.0.1").http.post(
                "/api/auth/login", json={"email": victim, "password": "right"})
            rep.check(first.status_code == 200, "E6 the colleague can sign in through the company login",
                      f"{first.status_code} {first.text[:120]}")

            def e6():
                def one(k):
                    p = Portal(cport, client_ip=f"10.103.{k // 250}.{k % 250 + 1}")
                    return p.http.post("/api/auth/login", json={"email": victim, "password": "wrong"}).status_code
                with ThreadPoolExecutor(30) as p:
                    got = list(p.map(one, range(600)))
                return {"codes": tally(got)}
            results["E6"] = measure(rep, a.port, "E6 600 wrong passwords for a colleague from 600 addresses", e6)
            r = Portal(cport, client_ip="10.104.0.1").http.post("/api/auth/login",
                                                               json={"email": victim, "password": "right"})
            rep.check(r.status_code == 200, "E6 ...and the colleague still signs in from their own desk",
                      f"{r.status_code} {r.text[:150]}")
            same_ip = [Portal(cport, client_ip="10.104.0.9").http.post(
                "/api/auth/login", json={"email": victim, "password": "wrong"}).status_code for _ in range(12)]
            rep.check(429 in same_ip, "E6 one address guessing is stopped after 10 a minute", str(tally(same_ip)))
        finally:
            subprocess.run(["bash", str(HERE / "qa-backend.sh"), "stop", str(cport)])
            idp.terminate()

    (HERE / "results").mkdir(exist_ok=True)
    (HERE / "results/floods.json").write_text(json.dumps(results, indent=1, default=str, ensure_ascii=False),
                                              encoding="utf-8")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
