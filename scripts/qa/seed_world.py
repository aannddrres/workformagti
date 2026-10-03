"""Fill MAGTI_QA with a call centre's worth of data, through the real API.

    python scripts/qa/seed_world.py [--operators 600] [--active 200] [--port 8090]

1. the 122 real articles, through scripts/import_legacy_content.py (once);
2. operators ``test_operator_w001@magti.ge`` ... spread 45/45/10 over
   ტექნიკური / საინფორმაციო / ოფისი, the three departments Flyway creates;
3. ten mandatory readings on imported articles, deadlines 1 to 14 days out,
   three of them with a quiz;
4. a week's activity: ``--active`` operators open, read and pass quizzes.

Idempotent: a second run reuses what exists. Writes results/world.json for
the checks that build on it. Every request comes from its own address
(X-Forwarded-For), so the per-address login limit is not what is measured.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from qa_lib import (INFO, TECH, Portal, Report, db, ensure_category, now_tbilisi,  # noqa: E402
                    require_reading, set_quiz)

REPO = Path(__file__).resolve().parents[2]
RESULTS = Path(__file__).resolve().parent / "results"
OFFICE = "ოფისი"


def ip_for(i: int) -> str:
    return f"10.77.{i // 250}.{i % 250 + 1}"


def operator_email(i: int) -> str:
    return f"test_operator_w{i:03d}@magti.ge"


def department_for(i: int) -> str:
    r = i % 20
    return TECH if r < 9 else INFO if r < 18 else OFFICE


def import_articles(content_email: str) -> None:
    with db() as c:
        n = c.cursor().execute("SELECT COUNT(*) FROM articles").fetchone()[0]
    if n >= 122:
        print(f"articles already present ({n}); import skipped")
        return
    env = dict(os.environ, ORACLE_DB_USER="MAGTI_QA",
               ORACLE_DB_PASSWORD=os.environ.get("ORACLE_DB_PASSWORD", "local_only_magti_qa_pw"),
               ORACLE_DB_URL="localhost:1521/orclpdb1", PYTHONUTF8="1")
    cmd = [sys.executable, str(REPO / "scripts/import_legacy_content.py"), "--apply",
           "--status", "published", "--author-email", content_email,
           "--source-db", str(REPO / "magti_portal.db"), "--uploads", str(REPO / "uploads")]
    print("importing the 122 legacy articles ...", flush=True)
    subprocess.run(cmd, env=env, check=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--operators", type=int, default=600)
    ap.add_argument("--active", type=int, default=200)
    ap.add_argument("--port", type=int, default=None)
    a = ap.parse_args()
    rep = Report("seed_world")
    portal = Portal(a.port, client_ip="10.77.250.1")
    admin = portal.login("admin@magti.ge")
    content = Portal(a.port, client_ip="10.77.250.2").login("content@magti.ge")

    import_articles("content@magti.ge")

    # -- operators --------------------------------------------------------------
    with db() as c:
        existing = {e for (e,) in c.cursor().execute(
            "SELECT LOWER(email) FROM users WHERE email LIKE 'test_operator_w%'")}

    # POST /api/users refuses (people come from the directory), so each
    # operator is created the way a development persona is: by signing in
    # once (test_operator_* lands in "Support"). Their department is then set
    # in the QA schema, as the directory would deliver it.
    def make(i: int):
        email = operator_email(i)
        if email in existing:
            return 0
        Portal(a.port, client_ip=ip_for(i)).login(email)
        return 1

    with ThreadPoolExecutor(8) as pool:
        created = sum(pool.map(make, range(1, a.operators + 1)))
    with db() as c:
        cur = c.cursor()
        for i in range(1, a.operators + 1):
            cur.execute("UPDATE users SET department = :d, name = :n WHERE LOWER(email) = :e",
                        d=department_for(i), n=f"ოპერატორი {i:03d}", e=operator_email(i))
        c.commit()
    rep.check(True, f"operators: {created} created, {a.operators - created} already there")

    # -- mandatory readings -----------------------------------------------------
    with db() as c:
        rows = c.cursor().execute(
            "SELECT id, title FROM articles WHERE status='published' AND is_draft=0 "
            "AND trashed_at IS NULL ORDER BY id FETCH FIRST 10 ROWS ONLY").fetchall()
        have = {r[0]: r[1] for r in c.cursor().execute(
            "SELECT item_id, id FROM required_readings WHERE item_type='article'")}
    readings = []
    base = now_tbilisi().replace(hour=18, minute=0, second=0, microsecond=0)
    for n, (article_id, title) in enumerate(rows):
        dept = TECH if n % 2 == 0 else INFO
        # The importer delivers with an empty audience list; give each article
        # its reading's department so the reading binds someone (PO-40).
        art = content.ok("get", f"/api/articles/{article_id}")
        if dept not in (art.get("target_departments") or []):
            body = {k: art.get(k) for k in ("title", "content", "category_id", "status", "published_at",
                                            "attachment_url", "audience_profile", "visible_to_tech_info",
                                            "visible_to_service_center", "quiz_enabled")}
            body.update(tags=None, target_departments=[dept], is_draft=False, quiz_enabled=n < 3)
            if body["category_id"] is None:
                body["category_id"] = ensure_category(admin)
            content.ok("put", f"/api/articles/{article_id}", body)
        if n < 3:
            set_quiz(content, article_id, question=f"{title[:60]} — გაიგეთ?", answers=("კი", "არა"), correct=0)
        if article_id in have:
            rid = have[article_id]
        else:
            rid = require_reading(content, article_id, dept, base + timedelta(days=1 + n))["id"]
        readings.append({"id": rid, "article_id": article_id, "department": dept, "quiz": n < 3})
    rep.check(len(readings) == 10, f"mandatory readings: {len(readings)}")

    # -- a week of activity -------------------------------------------------------
    quiz_keys = {}
    for r in readings:
        if r["quiz"]:
            q = content.ok("get", f"/api/articles/{r['article_id']}/quiz/admin")
            quiz_keys[r["article_id"]] = {qq["id"]: next(an["id"] for an in qq["answers"] if an["is_correct"])
                                          for qq in q["questions"]}
    rng = random.Random(7)

    def act(i: int):
        dept = department_for(i)
        mine = [r for r in readings if r["department"] == dept]
        if not mine:
            return 0
        s = Portal(a.port, client_ip=ip_for(i)).login(operator_email(i))
        done = 0
        for r in rng.sample(mine, k=min(len(mine), rng.randint(1, len(mine)))):
            s.post(f"/api/articles/{r['article_id']}/view")
            s.get(f"/api/articles/{r['article_id']}")
            if r["quiz"]:
                s.post(f"/api/articles/{r['article_id']}/quiz/attempt",
                       {"answers": {str(k): v for k, v in quiz_keys[r["article_id"]].items()}})
            if s.post(f"/api/compliance/mark-read/{r['id']}").status_code < 300:
                done += 1
        return done

    with ThreadPoolExecutor(16) as pool:
        marks = sum(pool.map(act, range(1, min(a.active, a.operators) + 1)))
    rep.check(marks > 0, f"activity: {marks} readings confirmed by {min(a.active, a.operators)} operators")

    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "world.json").write_text(json.dumps({
        "operators": a.operators, "readings": readings,
        "operator_email_pattern": "test_operator_w{:03d}@magti.ge"}, ensure_ascii=False, indent=1),
        encoding="utf-8")
    with db() as c:
        for t in ("articles", "users", "required_readings", "read_statuses", "quiz_attempts", "audit_logs"):
            try:
                rep.note(f"{t}: {c.cursor().execute(f'SELECT COUNT(*) FROM {t}').fetchone()[0]}")
            except Exception as e:  # a renamed table should not hide the rest
                rep.note(f"{t}: {e}")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
