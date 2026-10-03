"""Check 10 -- every way an article moves between states (ISTQB state transitions).

    python scripts/qa/check_states.py [--port 8090]

States: draft, scheduled (moment in the future), published, archived,
in the trash, purged. Each transition is driven through the API an editor's
screen uses, and after each one an operator of the target department is
asked whether the article opens and whether its mandatory reading binds
them. Invalid transitions must be clear refusals (4xx), never 500s.

The expectation for archive -> unarchive is "back to where it was": an
archived draft must not come back published, and an archived article
scheduled for next week must not come back live today.
"""

from __future__ import annotations

import argparse
import sys
import time
from datetime import timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from qa_lib import TECH, Portal, Report, article_body, db, ensure_category, iso, now_tbilisi  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8090)
    a = ap.parse_args()
    rep = Report("check_states")
    run = str(int(time.time()))[-6:]
    admin = Portal(a.port, client_ip="10.120.0.1").login("admin@magti.ge")
    ed = Portal(a.port, client_ip="10.120.0.2").login("content@magti.ge")
    op = Portal(a.port, client_ip="10.120.0.3").login("test_operator_w021@magti.ge")  # 21 % 20 = 1 -> ტექნიკური
    cat = ensure_category(admin)
    now = now_tbilisi()

    def new(title, **kw):
        return ed.ok("post", "/api/articles", article_body(f"{title} {run}", cat, [TECH], **kw))["id"]

    def status(i):
        with db() as c:
            return c.cursor().execute("SELECT status FROM articles WHERE id = :i", i=i).fetchone()[0]

    def opens(i):
        return op.get(f"/api/articles/{i}").status_code == 200

    def code(r):
        return r.status_code

    # -- archive -> unarchive returns to where it was ------------------------------
    d = new("მონახაზი", status="draft")
    rep.check(not opens(d), "draft: an operator cannot open it")
    rep.check(code(ed.post(f"/api/articles/{d}/archive")) == 200, "draft -> archived")
    r = ed.post(f"/api/articles/{d}/unarchive")
    rep.check(status(d) == "draft" and not opens(d),
              "archived draft -> unarchive: back to draft, still hidden",
              f"{code(r)}; status now '{status(d)}'; operator opens: {opens(d)}")

    s = new("დაგეგმილი", status="scheduled", published_at=iso(now + timedelta(days=7)))
    rep.check(not opens(s), "scheduled for next week: hidden today")
    ed.post(f"/api/articles/{s}/archive")
    r = ed.post(f"/api/articles/{s}/unarchive")
    rep.check(status(s) == "scheduled" and not opens(s),
              "archived scheduled -> unarchive: still scheduled, still hidden until its moment",
              f"{code(r)}; status now '{status(s)}'; operator opens: {opens(s)}")

    d2 = new("მონახაზი bulk", status="draft")
    s2 = new("დაგეგმილი bulk", status="scheduled", published_at=iso(now + timedelta(days=7)))
    r1 = ed.post("/api/articles/bulk-archive", {"ids": [d2, s2], "archive": True})
    r2 = ed.post("/api/articles/bulk-archive", {"ids": [d2, s2], "archive": False})
    rep.check(not opens(d2) and not opens(s2),
              "bulk archive then bulk unarchive: neither the draft nor the scheduled one goes live",
              f"{code(r1)}/{code(r2)}; statuses '{status(d2)}', '{status(s2)}'; "
              f"operator opens draft: {opens(d2)}, scheduled: {opens(s2)}")

    # -- news: a personal draft through archive and back -------------------------
    nd = ed.ok("post", "/api/news", {
        "title": f"სიახლის მონახაზი {run}", "content": "<p>QA</p>", "target_department": TECH,
        "attachment_url": None, "visible_to_tech_info": True, "visible_to_service_center": False,
        "expires_at": None, "is_draft": True})["id"]

    def news_opens(i):
        return op.get(f"/api/news/{i}").status_code == 200
    rep.check(not news_opens(nd), "news draft: an operator cannot open it")
    ra, ru = ed.post(f"/api/news/{nd}/archive"), ed.post(f"/api/news/{nd}/unarchive")
    rep.check(not news_opens(nd), "news draft -> archive -> unarchive: still a hidden draft",
              f"{code(ra)}/{code(ru)}; operator opens: {news_opens(nd)}")

    # -- the ordinary life of a published article, with its reading ------------------
    p = new("გამოქვეყნებული")
    rr = ed.ok("post", "/api/compliance/required-readings", {
        "item_type": "article", "item_id": p, "target_department": TECH,
        "due_date": iso(now + timedelta(days=5)), "priority": "high"})

    def binds():
        return rr["id"] in {x["reading"]["id"] for x in op.ok("get", "/api/compliance/my-readings")}
    rep.check(opens(p) and binds(), "published: opens, and its reading binds")
    ed.ok("post", f"/api/articles/{p}/archive")
    rep.check(not opens(p) and not binds(), "archived: hidden, reading suspended (PO-40)")
    ed.ok("post", f"/api/articles/{p}/unarchive")
    rep.check(opens(p) and binds(), "unarchived: opens again, reading resumes")
    ed.ok("post", f"/api/articles/{p}/archive")
    rep.check(code(ed.delete(f"/api/articles/{p}")) < 300, "archived -> trash")
    rep.check(not opens(p) and not binds(), "in the trash: hidden, reading suspended")
    rep.check(400 <= code(ed.post(f"/api/articles/{p}/unarchive")) < 500, "in the trash: unarchive refused")
    put = ed.put(f"/api/articles/{p}", article_body(f"edited in trash {run}", cat, [TECH]))
    rep.check(400 <= code(put) < 500, "in the trash: editing refused", f"{code(put)} {put.text[:100]}")
    rep.check(code(ed.post(f"/api/content-trash/article/{p}/restore")) == 200, "trash -> restore")
    rep.check(status(p) == "archived" and not opens(p), "restored from the trash: archived, not live",
              f"status '{status(p)}'")
    ed.ok("post", f"/api/articles/{p}/unarchive")
    rep.check(opens(p) and binds(), "restored then unarchived: live, reading binds again")

    # -- refusals ----------------------------------------------------------------------
    live = new("ცოცხალი")
    checks = [
        ("unarchive a published article", ed.post(f"/api/articles/{live}/unarchive")),
        ("trash a published article without archiving", ed.delete(f"/api/articles/{live}")),
        ("restore an article that is not in the trash", ed.post(f"/api/content-trash/article/{live}/restore")),
        ("purge an article that is not in the trash", admin.delete(f"/api/content-trash/article/{live}")),
        ("editor purges (system admin only)", ed.delete(f"/api/content-trash/article/{live}")),
        ("archive an article that does not exist", ed.post("/api/articles/999999999/archive")),
        ("unknown item type in the trash", admin.post(f"/api/content-trash/gadget/{live}/restore")),
        ("operator archives", op.post(f"/api/articles/{live}/archive")),
    ]
    for label, r in checks:
        rep.check(400 <= r.status_code < 500, f"refused: {label}", f"{r.status_code} {r.text[:90]}")
    arch_twice = ed.post(f"/api/articles/{live}/archive"), ed.post(f"/api/articles/{live}/archive")
    rep.check(all(x.status_code == 200 for x in arch_twice) and status(live) == "archived",
              "archiving twice is harmless")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
