"""Check 4 -- back up the portal's database, lose it, restore it.

    python scripts/qa/check_backup_restore.py [--port 8090]

Needs a seeded MAGTI_QA and the one owner-approved grant
(sql/grant_datapump.sql: READ, WRITE on DATA_PUMP_DIR to MAGTI_QA).

Everything the portal keeps is in Oracle -- articles, uploaded files (BLOBs
in stored_files), the audit chain, sessions, export files -- so one schema
export is the whole backup. The rehearsal:

  1. a hot backup with expdp while operators keep working, consistent to one
     moment (FLASHBACK_TIME), timed;
  2. a fingerprint: row count of every table, a SHA-256 of every stored
     file, the audit tip;
  3. the disaster: the backend stops and the schema is dropped;
  4. the restore: an empty schema, the grant again, impdp, timed;
  5. the portal starts on it: Flyway accepts it, people sign in, files open
     byte for byte, the whole audit chain verifies, and the counts match the
     backup moment.

Real production backups are the DBA's (RMAN, docs/QUESTIONS_FOR_IT.md No.6
and No.14); this proves the portal survives a restore, which RMAN cannot
tell you.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from qa_lib import Portal, Report, db  # noqa: E402

HERE = Path(__file__).resolve().parent
PW = os.environ.get("ORACLE_DB_PASSWORD", "local_only_magti_qa_pw")
CONNECT = f"MAGTI_QA/{PW}@localhost:1521/orclpdb1"


def sh(*args, **kw):
    return subprocess.run(["bash", str(HERE / args[0]), *map(str, args[1:])], check=True, **kw)


def fingerprint(as_of_scn: int | None = None) -> dict:
    """Counts and file hashes, optionally AS OF the backup's SCN, so a backup
    taken while people work is compared with the moment it captured."""
    flash = f" AS OF SCN {as_of_scn}" if as_of_scn else ""
    with db() as c:
        cur = c.cursor()
        tables = [t for (t,) in cur.execute("SELECT table_name FROM user_tables WHERE table_name NOT LIKE 'SYS_%' "
                                            "AND table_name NOT LIKE 'BIN$%' ORDER BY table_name")]
        counts = {}
        for t in tables:
            counts[t] = cur.execute(f'SELECT COUNT(*) FROM "{t}"{flash}').fetchone()[0]
        files = {}
        for fid, blob in cur.execute(f"SELECT filename, content FROM stored_files{flash} ORDER BY filename"):
            files[fid] = hashlib.sha256(blob.read() if blob else b"").hexdigest()
        tip = cur.execute(f"SELECT tip_hash FROM audit_chain_state{flash} WHERE id = 1").fetchone()[0]
    return {"counts": counts, "files": files, "tip": tip}


def current_scn() -> int:
    with db() as c:
        return c.cursor().execute("SELECT TIMESTAMP_TO_SCN(SYSTIMESTAMP) FROM dual").fetchone()[0]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8090)
    a = ap.parse_args()
    rep = Report("check_backup_restore")
    stamp = time.strftime("%Y%m%d%H%M%S")
    dumpfile, logfile = f"magti_qa_{stamp}.dmp", f"magti_qa_{stamp}_exp.log"

    sh("qa-backend.sh", "start", a.port)
    # Work continues during the backup, as it would at 03:00 on a real night shift.
    stop = threading.Event()
    traffic = {"ok": 0, "bad": 0}

    def operators():
        sessions = [Portal(a.port, client_ip=f"10.100.0.{i}").login(f"test_operator_w{i:03d}@magti.ge")
                    for i in range(400, 420)]
        while not stop.is_set():
            for s in sessions:
                for item in s.ok("get", "/api/compliance/my-readings"):
                    if item["status"] != "read":
                        r = s.post(f"/api/compliance/mark-read/{item['reading']['id']}")
                        traffic["ok" if r.status_code in (200, 403) else "bad"] += 1
                        break
                s.post("/api/articles/30/view")
                if stop.is_set():
                    break

    t = threading.Thread(target=operators)
    t.start()
    time.sleep(5)

    scn = current_scn()
    t0 = time.time()
    exp = subprocess.run(["expdp", CONNECT, "SCHEMAS=MAGTI_QA", "DIRECTORY=DATA_PUMP_DIR",
                          f"DUMPFILE={dumpfile}", f"LOGFILE={logfile}", f"FLASHBACK_SCN={scn}"],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    exp_s = time.time() - t0
    time.sleep(3)
    stop.set()
    t.join()
    rep.check(exp.returncode == 0 and "successfully completed" in exp.stdout + exp.stderr,
              "1 hot backup while operators work", f"{exp_s:.0f} s, traffic {traffic}; "
              + (exp.stdout + exp.stderr)[-300:].replace("\n", " "))
    before = fingerprint(as_of_scn=scn)
    live = fingerprint()
    rep.note(f"backup moment: {sum(before['counts'].values())} rows in {len(before['counts'])} tables, "
             f"{len(before['files'])} files; work done after it: "
             f"{sum(live['counts'].values()) - sum(before['counts'].values())} rows")

    # -- the disaster -----------------------------------------------------------
    sh("qa-backend.sh", "stop", a.port)
    sh("qa-schema.sh", "create", capture_output=True)  # drop MAGTI_QA CASCADE, recreate empty
    sh("qa-schema.sh", "sql", str(HERE / "sql/grant_datapump.sql"), capture_output=True)
    with db() as c:
        empty = c.cursor().execute("SELECT COUNT(*) FROM user_tables").fetchone()[0]
    rep.check(empty == 0, "3 the schema is really gone", f"{empty} tables left")

    t0 = time.time()
    imp = subprocess.run(["impdp", CONNECT, "SCHEMAS=MAGTI_QA", "DIRECTORY=DATA_PUMP_DIR",
                          f"DUMPFILE={dumpfile}", f"LOGFILE=magti_qa_{stamp}_imp.log",
                          "TABLE_EXISTS_ACTION=REPLACE"],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    imp_s = time.time() - t0
    out = imp.stdout + imp.stderr
    # "completed with N error(s)" for the CREATE USER that already exists is expected.
    benign = all(("ORA-31684" in line) for line in out.splitlines() if line.startswith("ORA-"))
    rep.check(imp.returncode in (0, 5) and benign, "4 restore", f"{imp_s:.0f} s; " + out[-300:].replace("\n", " "))

    after = fingerprint()
    diff = {t: (before["counts"][t], after["counts"].get(t)) for t in before["counts"]
            if before["counts"][t] != after["counts"].get(t)}
    rep.check(not diff, "5 every table has exactly the rows of the backup moment", str(diff)[:400])
    bad_files = [f for f, h in before["files"].items() if after["files"].get(f) != h]
    rep.check(not bad_files, f"5 all {len(before['files'])} stored files are byte-identical", str(bad_files[:10]))
    rep.check(before["tip"] == after["tip"], "5 the audit chain ends where it ended at the backup moment")

    t0 = time.time()
    sh("qa-backend.sh", "start", a.port)
    rep.note(f"portal up on the restored schema in {time.time() - t0:.0f} s")
    admin = Portal(a.port, client_ip="10.100.1.1").login("admin@magti.ge")
    full = admin.get("/api/audit-logs/chain-health/full", timeout=600)
    rep.check(full.status_code == 200 and full.json().get("status") == "ok",
              "5 the whole audit chain verifies on the restored data", full.text[:200])
    op = Portal(a.port, client_ip="10.100.1.2").login("test_operator_w401@magti.ge")
    rep.check(op.get("/api/compliance/my-readings").status_code == 200, "5 an operator signs in and reads")
    with db() as c:
        sample = c.cursor().execute("SELECT filename FROM stored_files ORDER BY filename FETCH FIRST 3 ROWS ONLY").fetchall()
    for (name,) in sample:
        r = admin.get(f"/uploads/{name}")
        rep.check(r.status_code == 200 and len(r.content) > 0, f"5 restored file {name} downloads", str(r.status_code))
    writes = op.post("/api/articles/31/view")
    rep.check(writes.status_code < 300, "5 the restored portal accepts new writes (identity columns continue)",
              f"{writes.status_code} {writes.text[:120]}")
    full2 = admin.get("/api/audit-logs/chain-health/full", timeout=600)
    rep.check(full2.json().get("status") == "ok", "5 new audit rows chain onto the restored tip", full2.text[:160])
    (HERE / "results").mkdir(exist_ok=True)
    (HERE / "results/backup_restore.json").write_text(json.dumps({
        "export_seconds": round(exp_s), "import_seconds": round(imp_s), "dumpfile": dumpfile,
        "rows": sum(before["counts"].values()), "files": len(before["files"])}, indent=1))
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
