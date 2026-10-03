"""Check 8 -- a call-centre shift: 600 people, the real 122 articles.

    python scripts/qa/load_shift.py [--port 8090] [--minutes 30] [--peak-minutes 5] [--users 600]

Needs seed_world.py's world. Phases:

  burst   all operators sign in within about a minute (shift start)
  steady  --minutes of ordinary work: every operator, every 15-45 s, does
          one thing -- opens the knowledge base, searches, opens and reads
          an article, checks their readings; now and then confirms one.
          Three editors save articles; five managers look at statistics.
  peak    --peak-minutes with the pauses cut to 3-8 s, to find headroom.

Measured: latency per kind of request (p50/p95/p99), errors, the server's
heap, threads and connection pool every 30 s (/actuator/prometheus), and
how much each table grows -- projected to a year of shifts for IT.
Pass: steady p95 under 1 s, no 5xx, heap back near its start after a GC.
"""

from __future__ import annotations

import argparse
import json
import random
import re
import sys
import threading
import time
from collections import defaultdict
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent))
from qa_lib import Portal, Report, db  # noqa: E402

HERE = Path(__file__).resolve().parent
WORDS = ["ტარიფი", "როუმინგი", "ინტერნეტი", "ბალანსი", "პაკეტი", "კოდი", "MyMagti", "აქცია", "IPTV",
         "ნომრის", "გადახდა", "SIM", "ბონუსი", "ზარი", "4G"]


class Stats:
    def __init__(self):
        self.lat = defaultdict(list)
        self.codes = defaultdict(lambda: defaultdict(int))
        self.errors = []
        self.lock = threading.Lock()

    def add(self, phase, kind, code, secs, text=""):
        with self.lock:
            self.lat[(phase, kind)].append(secs)
            self.codes[phase][code] += 1
            if (code >= 500 or code < 0) and len(self.errors) < 20:
                self.errors.append(f"{phase} {kind} {code} {text[:120]}")

    def table(self, phase):
        out = {}
        for (ph, kind), xs in sorted(self.lat.items()):
            if ph != phase or not xs:
                continue
            xs = sorted(xs)
            out[kind] = {"n": len(xs), "p50": round(xs[len(xs) // 2], 3),
                         "p95": round(xs[int(len(xs) * .95) - 1], 3), "p99": round(xs[int(len(xs) * .99) - 1], 3)}
        return out


def metrics(port):
    try:
        t = httpx.get(f"http://127.0.0.1:{port}/actuator/prometheus", timeout=5).text
    except httpx.HTTPError:
        return {}

    def total(name, label=None):
        vals = [float(m.group(2)) for m in re.finditer(rf"^{name}\{{([^}}]*)\}} ([0-9.eE+-]+)", t, re.M)
                if label is None or label in m.group(1)]
        return sum(vals) if vals else None
    return {"heap_mb": round((total("jvm_memory_used_bytes", 'area="heap"') or 0) / 1048576),
            "threads": total("jvm_threads_live_threads"),
            "pool_active": total("hikaricp_connections_active"),
            "pool_pending": total("hikaricp_connections_pending"),
            "gc_pause_s": round(total("jvm_gc_pause_seconds_sum") or 0, 2)}


def table_sizes():
    with db() as c:
        cur = c.cursor()
        rows = {t: cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
                for t in ("audit_logs", "article_view_logs", "search_logs", "read_statuses", "quiz_attempts",
                          "portal_sessions", "login_attempts", "reminders", "article_history")
                if cur.execute("SELECT COUNT(*) FROM user_tables WHERE table_name = UPPER(:t)", t=t).fetchone()[0]}
        mb = dict(cur.execute("SELECT segment_name, ROUND(SUM(bytes)/1048576,1) FROM user_segments "
                              "GROUP BY segment_name").fetchall())
    return rows, mb


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8090)
    ap.add_argument("--users", type=int, default=600)
    ap.add_argument("--minutes", type=float, default=30)
    ap.add_argument("--peak-minutes", type=float, default=5)
    a = ap.parse_args()
    rep = Report("load_shift")
    st = Stats()
    rows0, mb0 = table_sizes()
    samples = [("start", time.time(), metrics(a.port))]
    phase = {"name": "burst", "think": (15, 45)}
    stop = threading.Event()

    # What each department actually sees, asked of one of its operators (the
    # same list the knowledge-base screen shows), so "open an article" never
    # picks one that is correctly a 404 for that person.
    def dept_of(i):
        r = i % 20
        return "T" if r < 9 else "I" if r < 18 else "O"
    seen_by = {}
    for d, i in (("T", 1), ("I", 10), ("O", 19)):
        s0 = Portal(a.port, client_ip=f"10.113.0.{i}").login(f"test_operator_w{i:03d}@magti.ge")
        items = s0.ok("get", "/api/articles", params={"limit": 200})
        items = items.get("items", items) if isinstance(items, dict) else items
        seen_by[d] = [x["id"] for x in items] or [1]
    rep.note(f"articles visible per department: { {d: len(v) for d, v in seen_by.items()} }")
    visible = seen_by["T"]

    def call(s, kind, method, path, **kw):
        t0 = time.time()
        try:
            r = getattr(s, method)(path, **kw) if method == "get" else getattr(s, method)(path, kw.get("json_body"))
            st.add(phase["name"], kind, r.status_code, time.time() - t0, r.text if r.status_code >= 500 else "")
            return r
        except httpx.HTTPError as e:
            st.add(phase["name"], kind, -1, time.time() - t0, type(e).__name__)
            return None

    def operator(i):
        rng = random.Random(i)
        email = f"test_operator_w{i:03d}@magti.ge"
        p = Portal(a.port, timeout=60, client_ip=f"10.110.{i // 250}.{i % 250 + 1}")
        time.sleep(rng.uniform(0, 60))  # everyone arrives within the first minute
        t0 = time.time()
        try:
            s = p.login(email)
            st.add("burst", "login", 200, time.time() - t0)
        except Exception as e:  # noqa: BLE001
            st.add("burst", "login", -2, time.time() - t0, str(e))
            return
        call(s, "my-readings", "get", "/api/compliance/my-readings")
        call(s, "notifications", "get", "/api/notifications/summary")
        while not stop.is_set():
            time.sleep(rng.uniform(*phase["think"]))
            roll = rng.random()
            if roll < 0.30:
                call(s, "kb-list", "get", "/api/articles", params={"limit": 20, "skip": rng.randint(0, 100)})
            elif roll < 0.55:
                call(s, "search", "get", "/api/search/global", params={"q": rng.choice(WORDS)})
            elif roll < 0.85:
                art = rng.choice(seen_by[dept_of(i)])
                r = call(s, "article", "get", f"/api/articles/{art}")
                if r is not None and r.status_code == 200:
                    call(s, "view", "post", f"/api/articles/{art}/view")
            elif roll < 0.95:
                r = call(s, "my-readings", "get", "/api/compliance/my-readings")
                if r is not None and r.status_code == 200:
                    todo = [x for x in r.json() if x["status"] != "read"]
                    if todo and rng.random() < 0.5:
                        call(s, "mark-read", "post", f"/api/compliance/mark-read/{todo[0]['reading']['id']}")
            else:
                call(s, "heartbeat", "post", "/api/auth/session/heartbeat")

    def editor(k):
        rng = random.Random(1000 + k)
        s = Portal(a.port, timeout=60, client_ip=f"10.111.0.{k + 1}").login("content@magti.ge")
        while not stop.is_set():
            time.sleep(rng.uniform(60, 150) if phase["name"] != "peak" else rng.uniform(10, 30))
            art = rng.choice(visible[:60])
            r = call(s, "editor-open", "get", f"/api/articles/{art}")
            if r is None or r.status_code != 200:
                continue
            x = r.json()
            body = {k2: x.get(k2) for k2 in ("title", "content", "category_id", "status", "published_at",
                                             "attachment_url", "audience_profile", "visible_to_tech_info",
                                             "visible_to_service_center", "quiz_enabled", "target_departments")}
            body.update(tags=None, is_draft=False, version=x.get("version"))
            call(s, "editor-save", "put", f"/api/articles/{art}", json_body=body)

    def manager(k):
        rng = random.Random(2000 + k)
        s = Portal(a.port, timeout=120, client_ip=f"10.112.0.{k + 1}").login(
            "manager@magti.ge" if k == 0 else "admin@magti.ge")
        while not stop.is_set():
            time.sleep(rng.uniform(120, 300) if phase["name"] != "peak" else rng.uniform(20, 40))
            for path in ("/api/statistics/kpi", "/api/statistics/compliance", "/api/manager/team-stats",
                         "/api/statistics/activity"):
                call(s, "manager-stats", "get", path)

    threads = [threading.Thread(target=operator, args=(i,), daemon=True) for i in range(1, a.users + 1)]
    threads += [threading.Thread(target=editor, args=(k,), daemon=True) for k in range(3)]
    threads += [threading.Thread(target=manager, args=(k,), daemon=True) for k in range(5)]
    t_start = time.time()
    for t in threads:
        t.start()

    def sample_until(t_end, label):
        while time.time() < t_end:
            time.sleep(30)
            samples.append((label, time.time(), metrics(a.port)))
            m = samples[-1][2]
            print(f"  [{label} +{(time.time() - t_start) / 60:.0f} min] {m}", flush=True)

    sample_until(t_start + 90, "burst")
    phase["name"] = "steady"
    sample_until(time.time() + a.minutes * 60, "steady")
    phase.update(name="peak", think=(3, 8))
    sample_until(time.time() + a.peak_minutes * 60, "peak")
    stop.set()
    elapsed_h = (time.time() - t_start) / 3600
    rows1, mb1 = table_sizes()

    for ph in ("burst", "steady", "peak"):
        tbl = st.table(ph)
        reqs = sum(v["n"] for v in tbl.values())
        rep.note(f"{ph}: {reqs} requests, codes {dict(st.codes[ph])}")
        for kind, v in tbl.items():
            rep.note(f"    {kind:15s} {v}")
    steady = st.table("steady")
    worst = max((v["p95"] for v in steady.values()), default=0)
    rep.check(worst < 1.0, "steady: every kind of request p95 under 1 s", f"worst p95 {worst} s")
    rep.check(not any(c >= 500 or c < 0 for ph in st.codes for c in st.codes[ph]), "no server errors at any phase",
              "; ".join(st.errors[:5]))
    logins = sorted(st.lat[("burst", "login")])
    if logins:
        rep.note(f"shift start: {len(logins)} sign-ins, p95 {logins[int(len(logins) * .95) - 1]:.2f} s, "
                 f"max {logins[-1]:.2f} s")
    heaps = [m.get("heap_mb", 0) for _, _, m in samples if m]
    rep.note(f"heap MB over the run: start {heaps[0]}, max {max(heaps)}, end {heaps[-1]}")
    rep.note(f"threads: max {max((m.get('threads') or 0) for _, _, m in samples if m)}; "
             f"pool active max {max((m.get('pool_active') or 0) for _, _, m in samples if m)}, "
             f"pending max {max((m.get('pool_pending') or 0) for _, _, m in samples if m)}")
    growth = {t: rows1[t] - rows0.get(t, 0) for t in rows1}
    rep.note(f"rows added in {elapsed_h * 60:.0f} min: {growth}")
    # A year: 600 people x ~250 working days x 8 h, at this run's per-person-hour rate.
    # Sessions and sign-in counters grow per shift (one sign-in each); the
    # rest grows with time worked.
    person_hours = a.users * elapsed_h
    per_shift = {"portal_sessions", "login_attempts"}
    year = {t: int(n / a.users * 600 * 250) if t in per_shift else int(n / person_hours * 600 * 8 * 250)
            for t, n in growth.items() if n}
    rep.note(f"projected rows per year (600 people, 8 h, 250 days): {year}")
    seg_growth = {k: round(mb1.get(k, 0) - mb0.get(k, 0), 1) for k in mb1 if mb1.get(k, 0) - mb0.get(k, 0) > 0.5}
    rep.note(f"segments that grew (MB): {seg_growth}")
    (HERE / "results").mkdir(exist_ok=True)
    (HERE / "results/load_shift.json").write_text(json.dumps({
        "phases": {ph: st.table(ph) for ph in ("burst", "steady", "peak")},
        "codes": {ph: dict(v) for ph, v in st.codes.items()}, "samples": samples, "growth": growth,
        "year": year, "segments_mb": mb1}, indent=1, default=str, ensure_ascii=False), encoding="utf-8")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
