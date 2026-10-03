"""Checks 5 and 6 -- a month in a day, and the edges of the calendar.

    python scripts/qa/month_in_a_day.py [--port 8090]

There is no clock to wind forward, so time moves the other way: dates in the
MAGTI_QA data are moved into the past (a deadline 3 hours ago is what a
deadline set today looks like 3 hours from now). Run it against a backend
started with QA_TZ=UTC to see what the production container will do; the
alpine image has no time zone set.

Everything is created inside one group, ``ტექნიკური — ჯგუფი 77``, so the
reminders and readings reach four QA operators, not the 600 of seed_world.

Phases: build the situation and move the dates; restart the backend so the
reminder sweep runs (it starts 60 s after boot, then every 15 minutes); then
look at what each person sees.
"""

from __future__ import annotations

import argparse
import csv
import io
import os
import subprocess
import sys
import time
from datetime import timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from qa_lib import (Portal, Report, article_body, db, ensure_category, iso,  # noqa: E402
                    now_tbilisi)

HERE = Path(__file__).resolve().parent
GROUP = "ტექნიკური — ჯგუფი 77"
OPS = [f"test_operator_m{i:02d}@magti.ge" for i in range(1, 5)]


def shift(table: str, column: str, row_id: int, delta: timedelta) -> None:
    """Move one timestamp into the past by ``delta``."""
    secs = int(delta.total_seconds())
    with db() as c:
        c.cursor().execute(
            f"UPDATE {table} SET {column} = {column} - NUMTODSINTERVAL(:s, 'SECOND') WHERE id = :i",
            s=secs, i=row_id)
        c.commit()


def restart(port: int) -> None:
    sh = ["bash", str(HERE / "qa-backend.sh")]
    subprocess.run(sh + ["stop", str(port)], check=True)
    subprocess.run(sh + ["start", str(port)], check=True, env=dict(os.environ))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8090)
    ap.add_argument("--no-restart", action="store_true", help="skip the reminder-sweep phase")
    a = ap.parse_args()
    rep = Report("month_in_a_day")
    portal = Portal(a.port, client_ip="10.91.0.1")
    admin = portal.login("admin@magti.ge")
    editor = Portal(a.port, client_ip="10.91.0.2").login("content@magti.ge")
    run = str(int(time.time()))[-6:]
    cat = ensure_category(admin)

    # The four operators, in the group, as the directory would deliver them.
    for n, e in enumerate(OPS):
        Portal(a.port, client_ip=f"10.91.1.{n + 1}").login(e)
    with db() as c:
        for e in OPS:
            c.cursor().execute("UPDATE users SET department = :d WHERE LOWER(email) = :e", d=GROUP, e=e)
        c.commit()

    sessions: dict[int, object] = {}

    def op(n: int, fresh: bool = False):
        # Reuse sign-ins: ten a minute per account is the portal's limit.
        if fresh or n not in sessions:
            sessions[n] = Portal(a.port, client_ip=f"10.91.2.{n + 1}").login(OPS[n])
        return sessions[n]

    now = now_tbilisi()

    def article(title, **kw):
        return editor.ok("post", "/api/articles", article_body(f"{title} {run}", cat, [GROUP], **kw))["id"]

    def reading(article_id, due):
        r = editor.post("/api/compliance/required-readings", {
            "item_type": "article", "item_id": article_id, "target_department": GROUP,
            "due_date": iso(due), "priority": "high"})
        return r

    # ---------------------------------------------------------------- edges --
    a_edge = article("თარიღების კიდეები")
    cases = [
        ("deadline one minute ago is refused (PO-46)", now - timedelta(minutes=1), False),
        ("deadline in two minutes is accepted", now + timedelta(minutes=2), True),
        ("23:59 Tbilisi tonight", now.replace(hour=23, minute=59, second=0, microsecond=0)
         + timedelta(days=1), True),
        ("month end 31 Oct 23:59", now.replace(year=2026, month=10, day=31, hour=23, minute=59,
                                               second=0, microsecond=0), True),
        ("year end 31 Dec 23:59:59", now.replace(year=2026, month=12, day=31, hour=23, minute=59,
                                                 second=59, microsecond=0), True),
        ("leap day 29 Feb 2028", now.replace(year=2028, month=2, day=29, hour=12, minute=0,
                                             second=0, microsecond=0), True),
    ]
    edge_ids = []
    for label, due, should in cases:
        r = reading(a_edge, due)
        ok = (r.status_code < 300) == should
        detail = f"{r.status_code}"
        if r.status_code < 300:
            got = r.json()["due_date"]
            edge_ids.append(r.json()["id"])
            ok = ok and got.startswith(iso(due)[:16]) and got.endswith("+04:00")
            detail += f" stored as {got}"
            # Only one reading per item may exist at a time: clear it for the next case.
            editor.delete(f"/api/compliance/required-readings/{r.json()['id']}")
        else:
            detail += " " + r.text[:120]
        rep.check(ok, f"edge: {label}", detail)

    for label, raw, want in [
        ("UTC input 19:59Z is 23:59 Tbilisi", "2026-11-30T19:59:00Z", "2026-11-30T23:59:00+04:00"),
    ]:
        r = editor.post("/api/compliance/required-readings", {
            "item_type": "article", "item_id": a_edge, "target_department": GROUP,
            "due_date": raw, "priority": "high"})
        got = r.json().get("due_date") if r.status_code < 300 else r.text[:120]
        rep.check(r.status_code < 300 and got == want, f"edge: {label}", f"{r.status_code} {got}")
        if r.status_code < 300:
            editor.delete(f"/api/compliance/required-readings/{r.json()['id']}")
    for label, raw in [("30 February", "2027-02-30T12:00:00+04:00"), ("year 9999", "9999-12-31T12:00:00+04:00"),
                       ("no offset (the screen always sends one)", "2026-11-30T23:59:00"),
                       ("empty", ""), ("not a date", "ხვალ")]:
        r = editor.post("/api/compliance/required-readings", {
            "item_type": "article", "item_id": a_edge, "target_department": GROUP,
            "due_date": raw, "priority": "high"})
        rep.check(400 <= r.status_code < 500, f"edge: {label} is a clear refusal", f"{r.status_code} {r.text[:100]}")
        if r.status_code < 300:
            editor.delete(f"/api/compliance/required-readings/{r.json()['id']}")

    # ------------------------------------------------- build the month's state --
    # S1 overdue: due in 2 h, then moved 3 h back.
    a1 = article("ვადაგადაცილებული")
    r1 = reading(a1, now + timedelta(hours=2)).json()
    shift("required_readings", "due_date", r1["id"], timedelta(hours=3))
    # S2 due soon: due in 30 h, moved 10 h back -> due in 20 h.
    a2 = article("ვადა ახლოვდება")
    r2 = reading(a2, now + timedelta(hours=30)).json()
    shift("required_readings", "due_date", r2["id"], timedelta(hours=10))
    # S3 read late: due in 2 h, moved back, then m02 confirms.
    a3 = article("გვიან წაკითხული")
    r3 = reading(a3, now + timedelta(hours=2)).json()
    shift("required_readings", "due_date", r3["id"], timedelta(hours=3))
    late = op(1).post(f"/api/compliance/mark-read/{r3['id']}")
    rep.check(late.status_code < 300, "S3: a reading can still be confirmed after its deadline",
              f"{late.status_code} {late.text[:100]}")
    # S4 scheduled publication: publishes in 2 h, mandatory from then.
    a4 = article("დაგეგმილი", status="scheduled", published_at=iso(now + timedelta(hours=2)))
    before = op(0).get(f"/api/articles/{a4}").status_code
    r4 = reading(a4, now + timedelta(days=3)).json()
    mine_before = {x["reading"]["id"] for x in op(0).ok("get", "/api/compliance/my-readings")}
    rep.check(before == 404, "S4: a scheduled article is hidden before its moment", str(before))
    rep.check(r4["id"] not in mine_before, "S4: its reading binds nobody before publication (PO-40)")
    with db() as c:
        c.cursor().execute("UPDATE articles SET published_at = published_at - NUMTODSINTERVAL(3, 'HOUR') "
                           "WHERE id = :i", i=a4)
        c.commit()
    after = op(0).get(f"/api/articles/{a4}").status_code
    mine_after = {x["reading"]["id"] for x in op(0).ok("get", "/api/compliance/my-readings")}
    rep.check(after == 200, "S4: it opens once its moment has passed", str(after))
    rep.check(r4["id"] in mine_after, "S4: its reading binds from that moment")
    # S5 news expiry.
    n5 = editor.ok("post", "/api/news", {
        "title": f"ვადიანი სიახლე {run}", "content": "<p>QA</p>", "target_department": GROUP,
        "attachment_url": None, "visible_to_tech_info": True, "visible_to_service_center": False,
        "expires_at": iso(now + timedelta(hours=1)), "is_draft": False})["id"]
    seen = {n["id"] for n in op(0).ok("get", "/api/news")}
    rep.check(n5 in seen, "S5: news is shown before it expires")
    shift("news", "expires_at", n5, timedelta(hours=2))
    seen = {n["id"] for n in op(0).ok("get", "/api/news")}
    rep.check(n5 not in seen, "S5: news is gone once it has expired")
    rep.check(op(0).get(f"/api/news/{n5}").status_code in (404, 410), "S5: expired news cannot be opened by id")
    # S6 trash: 30 days.
    a6 = article("ნაგავში")
    editor.ok("post", f"/api/articles/{a6}/archive")  # delete needs an archived article
    editor.ok("delete", f"/api/articles/{a6}")
    early = admin.delete(f"/api/content-trash/article/{a6}")
    rep.check(early.status_code >= 400, "S6: purge refused on day 0", f"{early.status_code} {early.text[:100]}")
    with db() as c:
        c.cursor().execute("UPDATE articles SET trashed_at = trashed_at - NUMTODSINTERVAL(29, 'DAY'), "
                           "purge_after = purge_after - NUMTODSINTERVAL(29, 'DAY') WHERE id = :i", i=a6)
        c.commit()
    d29 = admin.delete(f"/api/content-trash/article/{a6}")
    rep.check(d29.status_code >= 400, "S6: purge refused on day 29", f"{d29.status_code}")
    with db() as c:
        c.cursor().execute("UPDATE articles SET trashed_at = trashed_at - NUMTODSINTERVAL(2, 'DAY'), "
                           "purge_after = purge_after - NUMTODSINTERVAL(2, 'DAY') WHERE id = :i", i=a6)
        c.commit()
    d31 = admin.delete(f"/api/content-trash/article/{a6}")
    rep.check(d31.status_code < 300, "S6: purge allowed on day 31", f"{d31.status_code} {d31.text[:100]}")
    restored = editor.post(f"/api/content-trash/article/{a6}/restore")
    rep.check(restored.status_code >= 400, "S6: a purged article cannot be restored", str(restored.status_code))
    # S7 sessions: idle 30 min, maximum 8 h.
    s_idle = op(2, fresh=True)
    with db() as c:
        c.cursor().execute("UPDATE portal_sessions SET last_seen_at = last_seen_at - NUMTODSINTERVAL(31, 'MINUTE') "
                           "WHERE user_id = :u AND revoked_at IS NULL", u=s_idle.user["id"])
        c.commit()
    r = s_idle.get("/api/compliance/my-readings")
    rep.check(r.status_code == 401, "S7: 31 idle minutes end the session", f"{r.status_code} {r.text[:120]}")
    s_long = op(3, fresh=True)
    with db() as c:
        c.cursor().execute("UPDATE portal_sessions SET created_at = created_at - NUMTODSINTERVAL(481, 'MINUTE'), "
                           "expires_at = expires_at - NUMTODSINTERVAL(481, 'MINUTE') "
                           "WHERE user_id = :u AND revoked_at IS NULL", u=s_long.user["id"])
        c.commit()
    r = s_long.get("/api/compliance/my-readings")
    rep.check(r.status_code == 401, "S7: 8 h 1 min after sign-in the session ends, however active",
              f"{r.status_code} {r.text[:120]}")
    s_ok = op(1, fresh=True)
    with db() as c:
        c.cursor().execute("UPDATE portal_sessions SET last_seen_at = last_seen_at - NUMTODSINTERVAL(29, 'MINUTE') "
                           "WHERE user_id = :u AND revoked_at IS NULL", u=s_ok.user["id"])
        c.commit()
    rep.check(s_ok.get("/api/compliance/my-readings").status_code == 200, "S7: 29 idle minutes do not")

    # ------------------------------------------------------- what people see --
    mine = {x["reading"]["id"]: x for x in op(0).ok("get", "/api/compliance/my-readings")}
    rep.check(mine.get(r1["id"], {}).get("is_overdue") is True, "S1: the passed deadline shows as overdue",
              str(mine.get(r1["id"], {}).get("is_overdue")))
    rep.check(mine.get(r2["id"], {}).get("is_overdue") is False, "S2: 20 h before the deadline is not overdue")
    manager = Portal(a.port, client_ip="10.91.0.3").login("manager@magti.ge")
    # An export needs reports.export AND a PRIMARY leadership: make manager@
    # the leader of ტექნიკური (the group is inside it, prefix-aware).
    with db() as c:
        dept_id = c.cursor().execute("SELECT id FROM departments WHERE stable_key = 'TECHNICAL'").fetchone()[0]
    r = admin.post("/api/admin/org/assignments", {"user_id": manager.user["id"], "department_id": dept_id,
                                                  "team_id": None, "assignment_type": "PRIMARY"})
    rep.check(r.status_code in (200, 409), "manager@ leads ტექნიკური", f"{r.status_code} {r.text[:100]}")
    # Export scope is by team (users.team_id), so the group needs one.
    # POST /api/teams refuses (teams come from the org catalogue), so the
    # team is written as the catalogue sync would write it.
    with db() as c:
        cur = c.cursor()
        tid = cur.var(int)
        cur.execute("INSERT INTO teams (name, created_at, department_id, stable_key, is_active) "
                    "VALUES (:n, SYSTIMESTAMP, :d, :k, 1) RETURNING id INTO :t",
                    n=f"QA ჯგუფი 77 {run}", d=dept_id, k=f"QA-77-{run}", t=tid)
        team_id = tid.getvalue()[0]
        for e in OPS + ["manager@magti.ge"]:
            cur.execute("UPDATE users SET team_id = :t WHERE LOWER(email) = :e", t=team_id, e=e)
        c.commit()
    period = {"from": (now - timedelta(days=2)).date().isoformat(),
              "through": (now + timedelta(days=5)).date().isoformat()}
    exp = manager.get("/api/export/readings", params=period)
    if exp.status_code == 200:
        rows = list(csv.reader(io.StringIO(exp.content.decode("utf-8-sig"))))
        head, body = rows[0], rows[1:]
        ours = [r for r in body if f"გვიან წაკითხული {run}" in " ".join(r)]
        rep.note(f"S3 export rows for the late article: {ours[:2]}")
        rep.check(any("დაგვიან" in " ".join(r) for r in ours),
                  "S3: the manager's export marks the late read as late (PO-49)",
                  f"{len(body)} rows; header {head}")
    else:
        rep.check(False, "S3: manager export", f"{exp.status_code} {exp.text[:150]}")

    if not a.no_restart:
        restart(a.port)  # the sweep runs 60 s after boot
        time.sleep(80)
        rem = op(0).ok("get", "/api/reminders")["items"]
        kinds = {(x["required_reading_id"], x["type"]) for x in rem}
        rep.check((r1["id"], "OVERDUE") in kinds, "S1: an overdue reminder is delivered")
        rep.check((r2["id"], "DUE_SOON") in kinds, "S2: a due-soon reminder is delivered")
        rep.check((r4["id"], "ASSIGNMENT") in kinds, "S4: the scheduled article's assignment is delivered")
        rep.check((r3["id"], "OVERDUE") not in {(x["required_reading_id"], x["type"])
                                                for x in op(1).ok("get", "/api/reminders")["items"]},
                  "S3: no overdue reminder to the person who already read it")
        texts = [x["content"] for x in rem if x["required_reading_id"] == r2["id"]]
        rep.note("S2 reminder texts: " + " | ".join(texts)[:300])
        restart_again = op(0).ok("get", "/api/reminders")["items"]
        rep.check(len(restart_again) == len(rem), "no duplicate reminders after a restart")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
