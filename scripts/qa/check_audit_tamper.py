"""Check 11 -- tamper with the audit ledger. Does anything notice?

    python scripts/qa/check_audit_tamper.py [--port 8090]

Needs a seeded MAGTI_QA, a backend on --port that has run at least five
minutes (so it has logged an AUDIT_CHAIN_ANCHOR line), and the dump from
check_backup_restore.py: the last step puts audit_logs and
audit_chain_state back from it, because some of this cannot be undone.

What someone with the schema owner's rights -- today, the portal's own
database account (docs/QUESTIONS_FOR_IT.md No.14) -- might do, and who sees:

  T1 edit one old row ............ the whole-ledger check (PO-51)
  T2 delete one old row .......... the whole-ledger check
  T3 cut the newest rows off and point the tip back
                                   only the logged anchors (check_anchors.py)
  T4 rewrite an old row and recompute every hash after it
                                   only the logged anchors
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from qa_lib import Portal, Report, db  # noqa: E402

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
PW = os.environ.get("ORACLE_DB_PASSWORD", "local_only_magti_qa_pw")

REHASH = """
DECLARE
    r audit_logs%ROWTYPE;
    v_canon VARCHAR2(32100 CHAR);
    v_hash RAW(32);
BEGIN
    SELECT * INTO r FROM audit_logs WHERE id = :i;
    v_canon := audit_logs_canonical_string(r.id, :prev, r.admin_id, r.action, r.item_type, r.item_id,
        r.timestamp, r.category, r.details, r.admin_name_snapshot, r.admin_email_snapshot,
        r.item_name_snapshot, r.ip_address, r.user_agent);
    SELECT STANDARD_HASH(v_canon, 'SHA256') INTO v_hash FROM dual;
    UPDATE audit_logs SET prev_hash = :prev, row_hash = LOWER(RAWTOHEX(v_hash)) WHERE id = :i;
    :out := LOWER(RAWTOHEX(v_hash));
END;"""


def full(admin) -> dict:
    return admin.get("/api/audit-logs/chain-health/full", timeout=600).json()


def window(admin) -> dict:
    return admin.get("/api/audit-logs/chain-health", timeout=120).json()


def anchors_hold(log: Path) -> tuple[int, str]:
    env = dict(os.environ, ORACLE_DB_URL="localhost:1521/orclpdb1", ORACLE_DB_USER="MAGTI_QA",
               ORACLE_DB_PASSWORD=PW, PYTHONUTF8="1")
    p = subprocess.run([sys.executable, str(REPO / "scripts/audit/check_anchors.py"), str(log)],
                       capture_output=True, text=True, env=env)
    return p.returncode, (p.stdout + p.stderr).strip().replace("\n", " | ")[:300]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8090)
    a = ap.parse_args()
    rep = Report("check_audit_tamper")
    log = HERE / f".run/backend-{a.port}.log"
    deadline = time.time() + 420
    while "AUDIT_CHAIN_ANCHOR id=" not in log.read_text(encoding="utf-8", errors="replace"):
        if time.time() > deadline:
            rep.check(False, "an anchor line was logged within 7 minutes of start")
            return rep.finish()
        time.sleep(10)
    admin = Portal(a.port, client_ip="10.101.0.1").login("admin@magti.ge")
    # A little activity after the anchor, so there is a tail to cut.
    op = Portal(a.port, client_ip="10.101.0.2").login("test_operator_w450@magti.ge")
    for art in (30, 31, 32, 33, 34):
        op.post(f"/api/articles/{art}/view")
    code, out = anchors_hold(log)
    rep.check(code == 0 and full(admin).get("status") == "ok", "start: chain intact and anchors hold", out)

    with db() as c:
        cur = c.cursor()
        anchor_id = max(int(x) for x in __import__("re").findall(
            r"AUDIT_CHAIN_ANCHOR id=(\d+)", log.read_text(encoding="utf-8", errors="replace")))
        victim, details = cur.execute(
            "SELECT id, DBMS_LOB.SUBSTR(details, 3000, 1) FROM audit_logs WHERE id < :a - 200 AND row_hash IS NOT NULL "
            "AND (details IS NULL OR DBMS_LOB.GETLENGTH(details) < 3000) ORDER BY id FETCH FIRST 1 ROWS ONLY",
            a=anchor_id).fetchone()

        # T1: edit an old row, then put it back.
        cur.execute("UPDATE audit_logs SET details = :d WHERE id = :i", d='{"forged":true}', i=victim)
        c.commit()
        f, w = full(admin), window(admin)
        rep.check(f.get("status") != "ok" and victim in f.get("bad_ids", []),
                  "T1 an edited old row: the whole-ledger check names it", json.dumps(f)[:200])
        rep.note(f"T1 the screen's newest-100 check says: {w.get('status')} (it does not look that far back)")
        rv = admin.get(f"/api/audit-logs/{victim}/verify").json()
        rep.note(f"T1 per-row verify of {victim}: {json.dumps(rv)[:160]}")
        cur.execute("UPDATE audit_logs SET details = :d WHERE id = :i", d=details, i=victim)
        c.commit()
        rep.check(full(admin).get("status") == "ok", "T1 undone: intact again")

        # T4 (before the destructive ones): rewrite and recompute every hash after.
        rows = cur.execute("SELECT id, prev_hash, row_hash FROM audit_logs WHERE row_hash IS NOT NULL").fetchall()
        succ = {prev: rid for rid, prev, _ in rows}
        by_id = {rid: (prev, h) for rid, prev, h in rows}
        cur.execute("UPDATE audit_logs SET details = :d WHERE id = :i", d='{"rewritten":true}', i=victim)
        prev = by_id[victim][0]
        rid, n = victim, 0
        out = cur.var(str)
        while rid is not None:
            old_hash = by_id[rid][1]
            cur.execute(REHASH, i=rid, prev=prev, out=out)
            prev = out.getvalue()
            rid = succ.get(old_hash)
            n += 1
        cur.execute("UPDATE audit_chain_state SET tip_hash = :h WHERE id = 1", h=prev)
        c.commit()
        f = full(admin)
        rep.check(f.get("status") == "ok", f"T4 a row rewritten with {n} hashes recomputed: the whole-ledger "
                  "check is fooled, as designed", json.dumps(f)[:160])
        code, out_text = anchors_hold(log)
        rep.check(code == 1 and "DIFFERENT" in out_text, "T4 ...but the logged anchors catch it", out_text)

        # T2: delete an old row (the chain is now T4's, consistent).
        cur.execute("DELETE FROM audit_logs WHERE id = :i", i=victim + 5)
        c.commit()
        f = full(admin)
        rep.check(f.get("status") != "ok" and f.get("link_breaks", 0) >= 1,
                  "T2 a deleted old row: the whole-ledger check sees the break", json.dumps(f)[:200])

        # T3: cut back past the anchor and move the tip to the new end.
        cur.execute("DELETE FROM audit_logs WHERE id >= :a", a=anchor_id - 2)
        new_end = cur.execute("SELECT row_hash FROM audit_logs WHERE id = (SELECT MAX(id) FROM audit_logs "
                              "WHERE row_hash IS NOT NULL)").fetchone()[0]
        cur.execute("UPDATE audit_chain_state SET tip_hash = :h WHERE id = 1", h=new_end)
        c.commit()
        f = full(admin)
        rep.note(f"T3 whole-ledger check after the cut (T2's hole is still there): {json.dumps(f)[:160]}")
        code, out_text = anchors_hold(log)
        rep.check(code == 1 and "GONE" in out_text, "T3 a cut-back tail: the logged anchors catch it", out_text)

    # Put the ledger back from the backup dump.
    dump = json.loads((HERE / "results/backup_restore.json").read_text())["dumpfile"]
    subprocess.run(["bash", str(HERE / "qa-backend.sh"), "stop", str(a.port)], check=True)
    imp = subprocess.run(["impdp", f"MAGTI_QA/{PW}@localhost:1521/orclpdb1", "DIRECTORY=DATA_PUMP_DIR",
                          f"DUMPFILE={dump}", "TABLES=AUDIT_LOGS,AUDIT_CHAIN_STATE",
                          "TABLE_EXISTS_ACTION=REPLACE", f"LOGFILE=tamper_restore_{int(time.time())}.log"],
                         capture_output=True, text=True, errors="replace")
    subprocess.run(["bash", str(HERE / "qa-backend.sh"), "start", str(a.port)], check=True)
    admin = Portal(a.port, client_ip="10.101.0.3").login("admin@magti.ge")
    rep.check(imp.returncode == 0 and full(admin).get("status") == "ok",
              "after: the ledger restored from the backup is intact", (imp.stdout + imp.stderr)[-200:])
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
