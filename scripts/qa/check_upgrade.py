"""Check 3 -- upgrade from the previous release, both side by side, roll back.

    python scripts/qa/check_upgrade.py --old OLD.jar --new NEW.jar [--skip-seed]

Kubernetes replaces replicas one at a time (k8s/30-backend-deployment.yaml,
RollingUpdate), so for a while the old and the new version serve the same
Oracle schema together. This rehearses exactly that, on a fresh MAGTI_QA:

  1. the OLD version alone, filled by seed_world.py (real articles, 600
     operators, a week of reading);
  2. OLD and NEW together for two minutes of mixed traffic -- people
     signing in, reading, confirming, passing quizzes, editors saving -- each
     request sent to either version at random;
  3. NEW alone (the update finished);
  4. OLD alone again (the rollback), then NEW again (roll forward).

At every step: no 5xx, the audit chain intact end to end, no reading or
confirmation lost, no reminder delivered twice. Build OLD from the previous
release, e.g. a worktree at main: ``./mvnw.cmd -B -DskipTests package``.
"""

from __future__ import annotations

import argparse
import json
import random
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from qa_lib import Portal, Report, db  # noqa: E402

HERE = Path(__file__).resolve().parent
OLD_PORT, NEW_PORT = 8092, 8093


def sh(*args):
    subprocess.run(["bash", str(HERE / "qa-backend.sh"), *map(str, args)], check=True)


def fingerprint() -> dict:
    with db() as c:
        cur = c.cursor()
        out = {}
        for t in ("articles", "news", "users", "required_readings", "read_statuses", "quiz_attempts",
                  "reminders", "audit_logs", "article_history"):
            out[t] = cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
        out["reminder_duplicates"] = cur.execute(
            "SELECT COUNT(*) FROM (SELECT required_reading_id, recipient_user_id, reminder_type "
            "FROM reminders WHERE required_reading_id IS NOT NULL AND reminder_type <> 'MANUAL' "
            "GROUP BY required_reading_id, recipient_user_id, reminder_type HAVING COUNT(*) > 1)").fetchone()[0]
        out["read_status_duplicates"] = cur.execute(
            "SELECT COUNT(*) FROM (SELECT required_reading_id, user_id FROM read_statuses "
            "GROUP BY required_reading_id, user_id HAVING COUNT(*) > 1)").fetchone()[0]
        out["flyway"] = cur.execute(
            """SELECT MAX(TO_NUMBER("version")) || '/' || COUNT(*) || '/' ||
                      SUM(CASE WHEN "success" = 1 THEN 1 ELSE 0 END)
               FROM "flyway_schema_history" WHERE "version" IS NOT NULL""").fetchone()[0]
    return out


def chain_ok(port: int) -> tuple[bool, str]:
    admin = Portal(port, client_ip=f"10.95.0.{port % 250}").login("admin@magti.ge")
    r = admin.get("/api/audit-logs/chain-health/full", timeout=600)
    if r.status_code == 404:  # the previous release has only the last-100 check
        r = admin.get("/api/audit-logs/chain-health", timeout=120)
    body = r.json() if r.status_code == 200 else {}
    ok = r.status_code == 200 and (body.get("healthy") is True or body.get("status") in ("ok", "healthy", "OK")
                                   or body.get("intact") is True)
    return ok, f"{r.status_code} {json.dumps(body, ensure_ascii=False)[:220]}"


class Traffic:
    """Operators and editors, each request to a port picked at random."""

    def __init__(self, ports, world, seconds):
        self.ports, self.world, self.seconds = ports, world, seconds
        self.status: dict[str, int] = {}
        self.errors: list[str] = []
        self.lock = threading.Lock()

    def count(self, port, method, path, code, text=""):
        key = f"{code}"
        with self.lock:
            self.status[key] = self.status.get(key, 0) + 1
            if code >= 500 and len(self.errors) < 15:
                self.errors.append(f":{port} {method} {path} -> {code} {text[:160]}")

    def operator(self, i):
        rng = random.Random(i)
        email = f"test_operator_w{i:03d}@magti.ge"
        sessions = {}
        end = time.time() + self.seconds
        while time.time() < end:
            port = rng.choice(self.ports)
            if port not in sessions:
                try:
                    sessions[port] = Portal(port, client_ip=f"10.96.{i // 250}.{i % 250 + 1}").login(email)
                except Exception as e:  # noqa: BLE001 -- a failed sign-in is a finding, not a crash
                    self.count(port, "POST", "/api/auth/login", 599, str(e))
                    time.sleep(2)
                    continue
            s = sessions[port]
            mine = s.get("/api/compliance/my-readings")
            self.count(port, "GET", "my-readings", mine.status_code, mine.text)
            if mine.status_code == 200:
                for item in mine.json():
                    if item["status"] != "read" and rng.random() < 0.3:
                        art = item["reading"]["item_id"]
                        r = s.get(f"/api/articles/{art}")
                        self.count(port, "GET", f"/api/articles/{art}", r.status_code, r.text)
                        r = s.post(f"/api/compliance/mark-read/{item['reading']['id']}")
                        self.count(port, "POST", "mark-read", r.status_code, r.text)
            r = s.get("/api/notifications/summary")
            self.count(port, "GET", "notifications", r.status_code, r.text)
            time.sleep(rng.uniform(0.5, 2))

    def editor(self):
        rng = random.Random(99)
        sessions = {p: Portal(p, client_ip=f"10.97.0.{p % 250}").login("content@magti.ge") for p in self.ports}
        ids = [r["article_id"] for r in self.world["readings"]] + list(range(20, 60))
        end = time.time() + self.seconds
        while time.time() < end:
            p_read, p_write = rng.choice(self.ports), rng.choice(self.ports)
            art = rng.choice(ids)
            got = sessions[p_read].get(f"/api/articles/{art}")
            self.count(p_read, "GET", f"/api/articles/{art}", got.status_code, got.text)
            if got.status_code != 200:
                continue
            a = got.json()
            body = {k: a.get(k) for k in ("title", "content", "category_id", "status", "published_at",
                                          "attachment_url", "audience_profile", "visible_to_tech_info",
                                          "visible_to_service_center", "quiz_enabled", "target_departments")}
            body.update(tags=None, is_draft=False, content=(a.get("content") or "") + "<p>.</p>")
            if "version" in a:
                body["version"] = a["version"]
            r = sessions[p_write].put(f"/api/articles/{art}", body)
            self.count(p_write, "PUT", f"/api/articles/{art}", r.status_code, r.text)
            time.sleep(rng.uniform(1, 3))

    def run(self, operators=40):
        threads = [threading.Thread(target=self.operator, args=(i,)) for i in range(201, 201 + operators)]
        threads.append(threading.Thread(target=self.editor))
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        return self.status


def smoke(rep, port, label, world):
    op = Portal(port, client_ip=f"10.98.0.{port % 250}").login("test_operator_w002@magti.ge")
    r = op.get("/api/compliance/my-readings")
    rep.check(r.status_code == 200, f"{label}: an operator's readings open", str(r.status_code))
    ok, detail = chain_ok(port)
    rep.check(ok, f"{label}: audit chain intact", detail)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", required=True)
    ap.add_argument("--new", required=True)
    ap.add_argument("--skip-seed", action="store_true")
    ap.add_argument("--seconds", type=int, default=120)
    a = ap.parse_args()
    rep = Report("check_upgrade")

    if not a.skip_seed:
        # A backend left over from an interrupted run keeps MAGTI_QA open,
        # and Oracle refuses to drop a connected user (ORA-01940).
        for port in (OLD_PORT, NEW_PORT):
            sh("stop", port)
        subprocess.run(["bash", str(HERE / "qa-schema.sh"), "create"], check=True, capture_output=True)
        sh("start", OLD_PORT, a.old)
        subprocess.run([sys.executable, str(HERE / "seed_world.py"), "--port", str(OLD_PORT),
                        "--active", "150"], check=True)
    else:
        sh("start", OLD_PORT, a.old)
    world = json.loads((HERE / "results/world.json").read_text(encoding="utf-8"))
    f0 = fingerprint()
    rep.note(f"OLD alone: {f0}")
    smoke(rep, OLD_PORT, "1 OLD alone", world)

    # -- 2. both together ---------------------------------------------------------
    sh("start", NEW_PORT, a.new)
    log = (HERE / f".run/backend-{NEW_PORT}.log").read_text(encoding="utf-8", errors="replace")
    import re as _re
    applied = _re.findall(r"Migrating schema \"MAGTI_QA\" to version \"([0-9.]+)", log)
    ok = ("is up to date" in log or "Successfully applied" in log) and "failed" not in " ".join(
        line for line in log.splitlines() if "Migration" in line or "flyway" in line.lower())
    rep.check(ok, "2 NEW starts on OLD's live schema and applies its migrations cleanly",
              f"applied {applied or 'none'}" if ok else log[-300:])
    traffic = Traffic([OLD_PORT, NEW_PORT], world, a.seconds)
    status = traffic.run()
    rep.note(f"mixed traffic statuses: {status}")
    five = sum(v for k, v in status.items() if k.startswith("5"))
    rep.check(five == 0, "2 no server errors while both versions serve", "; ".join(traffic.errors[:5]))
    f1 = fingerprint()
    rep.note(f"after mixed: {f1}")
    rep.check(f1["reminder_duplicates"] == 0, "2 no reminder delivered twice by the two versions")
    rep.check(f1["read_status_duplicates"] == 0, "2 no confirmation recorded twice")
    rep.check(f1["read_statuses"] >= f0["read_statuses"], "2 no confirmation lost")
    smoke(rep, NEW_PORT, "2 NEW during the mix", world)

    # -- 3. NEW alone ---------------------------------------------------------------
    sh("stop", OLD_PORT)
    smoke(rep, NEW_PORT, "3 NEW alone", world)

    # -- 4. rollback, then forward ------------------------------------------------
    sh("stop", NEW_PORT)
    sh("start", OLD_PORT, a.old)
    t = Traffic([OLD_PORT], world, 30)
    st = t.run(operators=10)
    rep.check(not any(k.startswith("5") for k in st), "4 rolled back: OLD serves NEW's data without errors",
              f"{st} {'; '.join(t.errors[:3])}")
    smoke(rep, OLD_PORT, "4 rolled back", world)
    sh("stop", OLD_PORT)
    sh("start", NEW_PORT, a.new)
    smoke(rep, NEW_PORT, "4 forward again", world)
    f2 = fingerprint()
    rep.note(f"end: {f2}")
    _, total, good = f2["flyway"].split("/")
    rep.check(total == good, "every migration in the history succeeded; the rollback undid none",
              f"{f0['flyway']} -> {f2['flyway']} (highest/total/successful)")
    sh("stop", NEW_PORT)
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
