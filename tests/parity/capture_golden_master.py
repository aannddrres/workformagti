"""Golden-master capture for the FastAPI/Postgres backend (Phase 0.3 of
docs/JAVA_ORACLE_ANGULAR_MIGRATION.md).

Seeds a known dataset, calls a representative set of endpoints with REAL
HTTP requests through TestClient (real /api/auth/login, real Bearer tokens
-- not dependency_overrides, which would bypass the auth code path this is
meant to characterize), and records exact status + response body for each
call into docs/api-contract/golden_master_v1.json.

This is not a pass/fail test -- there is no Java server yet to compare
against. It is the frozen reference: once the Java/Spring Boot port exists,
the SAME calls get replayed against it and diffed against this file.

Coverage is a representative slice across every router, not all 122
operations -- chosen to exercise the business rules the migration doc flags
as risky (quiz gate, department-prefix matching, RBAC catalogs, audit
formula-injection, chain-health SQLite degradation), not just CRUD
happy-paths. Extend GOLDEN_CALLS to widen coverage over time.

Usage: python tests/parity/capture_golden_master.py
Output: docs/api-contract/golden_master_v1.json
"""
import json
import os
import sys
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

_PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, _PROJECT_ROOT)

_DB_PATH = os.path.join(_PROJECT_ROOT, "_golden_master_scratch.db")
_DB_URL = f"sqlite:///{_DB_PATH}"

from sqlalchemy import create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402

_engine = create_engine(_DB_URL, connect_args={"check_same_thread": False})
_SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=_engine)

import database  # noqa: E402
database.engine = _engine
database.SessionLocal = _SessionLocal


def _get_db():
    db = _SessionLocal()
    try:
        yield db
    finally:
        db.close()


database.get_db = _get_db
os.environ["RUN_INIT"] = "1"

import models  # noqa: E402
import security  # noqa: E402

models.Base.metadata.create_all(bind=_engine)

import migrate  # noqa: E402
migrate.ensure_system_audit_permission_seeded()

from fastapi.testclient import TestClient  # noqa: E402
import main as monolith_app_module  # noqa: E402
from qa_accounts import TEST_ACCOUNTS, TEST_ACCOUNT_PASSWORD  # noqa: E402
from tests.factories import (  # noqa: E402
    make_user, make_category, make_article, make_required_reading, make_read_receipt,
)

app = monolith_app_module.app
db = _SessionLocal()

# ── Seed a known dataset ───────────────────────────────────────────────────
for acc in TEST_ACCOUNTS:
    make_user(db, email=acc["email"], role=acc["role"], department=acc["department"],
               name=acc["name"], password=TEST_ACCOUNT_PASSWORD)

sysadmin = db.query(models.User).filter(models.User.email == "sysadmin@magti.ge").first()
content_admin = db.query(models.User).filter(models.User.email == "content@magti.ge").first()
manager_tech = db.query(models.User).filter(models.User.email == "manager.tech@magti.ge").first()
operator_tech = db.query(models.User).filter(models.User.email == "operator.tech@magti.ge").first()
operator_office = db.query(models.User).filter(models.User.email == "operator.office@magti.ge").first()

cat = make_category(db, name="Golden Master Category", category_id=1)

plain_article = make_article(
    db, author=content_admin, title="Golden — Plain Article",
    content="<p>Sample body for parity capture.</p>",
    category_id=cat.id, target_department="All",
)
quiz_article = make_article(
    db, author=content_admin, title="Golden — Quiz Article",
    content="<p>Read me, then take the quiz.</p>",
    category_id=cat.id, quiz_enabled=True, target_department="All",
)
dept_article = make_article(
    db, author=content_admin, title="Golden — Tech-Only Article",
    content="<p>Only ტექნიკური should see this.</p>",
    category_id=cat.id, target_department="ტექნიკური",
)

reading_dept = make_required_reading(
    db, item_id=dept_article.id, item_type="article",
    target_department="ტექნიკური", due_days=5,
)
# Pre-existing read receipt so /my-readings and /my-progress have real rows.
make_read_receipt(db, article=plain_article, operator=operator_tech)

quiz_question = models.QuizQuestion(article_id=quiz_article.id, question_text="2+2?", position=0)
db.add(quiz_question)
db.flush()
db.add_all([
    models.QuizAnswer(question_id=quiz_question.id, answer_text="3", is_correct=False, position=0),
    models.QuizAnswer(question_id=quiz_question.id, answer_text="4", is_correct=True, position=1),
])
db.commit()

# NOTE: the audit-CSV formula-injection gap (bug #8) needs a real audit row
# whose admin_name/details contain a formula-trigger character, which means
# driving it through an authenticated HTTP action (not raw ORM inserts --
# audit_trail.py only fires with a request-bound actor). Left for a
# dedicated regression test per the migration doc's §3.2, not duplicated
# here as a golden-master capture.


def _login(email: str) -> str:
    res = client.post("/api/auth/login", json={"email": email, "password": TEST_ACCOUNT_PASSWORD})
    assert res.status_code == 200, f"login failed for {email}: {res.text}"
    return res.json()["access_token"]


client = TestClient(app)
tokens = {
    "sysadmin": _login("sysadmin@magti.ge"),
    "content_admin": _login("content@magti.ge"),
    "manager_tech": _login("manager.tech@magti.ge"),
    "operator_tech": _login("operator.tech@magti.ge"),
    "operator_office": _login("operator.office@magti.ge"),
}


def _auth(who: Optional[str]) -> dict:
    if who is None:
        return {}
    return {"Authorization": f"Bearer {tokens[who]}"}


@dataclass
class Call:
    name: str
    method: str
    path: str
    as_user: Optional[str] = None
    json_body: Optional[dict] = None
    params: Optional[dict] = None
    note: str = ""


GOLDEN_CALLS: list[Call] = [
    # ── Auth ────────────────────────────────────────────────────────────
    Call("login_operator", "POST", "/api/auth/login", None,
         {"email": "operator.tech@magti.ge", "password": TEST_ACCOUNT_PASSWORD}),
    Call("login_wrong_password", "POST", "/api/auth/login", None,
         {"email": "operator.tech@magti.ge", "password": "wrong"}),
    Call("sso_init", "GET", "/api/auth/sso/init", None),
    Call("me_operator", "GET", "/api/users/me", "operator_tech"),

    # ── Articles: visibility, quiz gate, department targeting ───────────
    Call("list_articles_as_office_operator", "GET", "/api/articles", "operator_office",
         note="office operator must NOT see the ტექნიკური-only article"),
    Call("list_articles_as_tech_operator", "GET", "/api/articles", "operator_tech",
         note="tech operator must see the ტექნიკური-only article"),
    Call("get_dept_article_as_tech", "GET", f"/api/articles/{dept_article.id}", "operator_tech"),
    Call("get_dept_article_as_office", "GET", f"/api/articles/{dept_article.id}", "operator_office",
         note="known-inconsistency probe: visibility vs 404/403 for out-of-department direct access"),
    Call("quiz_gate_blocks_before_attempt", "POST", f"/api/articles/{quiz_article.id}/read-receipt", "operator_tech",
         note="must 403 -- quiz not yet passed"),
    Call("get_quiz_public", "GET", f"/api/articles/{quiz_article.id}/quiz", "operator_tech",
         note="must never include is_correct"),

    # ── Compliance: mark-read department guard (this session's fix) ─────
    Call("mark_read_wrong_department", "POST", f"/api/compliance/mark-read/{reading_dept.id}", "operator_office",
         note="must 403 -- office operator, tech-targeted reading"),
    Call("my_readings_tech_operator", "GET", "/api/compliance/my-readings", "operator_tech"),
    Call("my_progress_tech_operator", "GET", "/api/compliance/my-progress", "operator_tech"),

    # ── Stats / RBAC scoping ──────────────────────────────────────────────
    Call("compliance_stats_as_manager", "GET", "/api/statistics/compliance", "manager_tech"),
    Call("department_stats_as_manager", "GET", "/api/manager/department-stats", "manager_tech"),
    Call("kpi_as_sysadmin", "GET", "/api/statistics/kpi", "sysadmin"),

    # ── Audit log: manager department-scoping + CSV sanitization gap ─────
    Call("audit_list_as_manager", "GET", "/api/audit-logs", "manager_tech",
         note="manager must only see own-department rows"),
    Call("audit_csv_export_as_manager_forbidden", "GET", "/api/audit-logs/export", "manager_tech",
         note="must 403 -- export/verify/chain-health exclude managers"),
    Call("audit_chain_health_sqlite", "GET", "/api/audit-logs/chain-health", "sysadmin",
         note="must degrade to 200 {status: unavailable} on SQLite, not 501"),

    # ── RBAC permissions endpoint (known gap: videos.archive missing) ────
    Call("permissions_update_unknown_perm_rejected", "PUT",
         f"/api/users/{operator_tech.id}/permissions", "sysadmin",
         {"permissions": ["videos.archive"]},
         note="known gap #4: videos.archive should be grantable but the whitelist rejects it"),

    # ── Search ────────────────────────────────────────────────────────────
    Call("search_basic", "GET", "/api/search", "operator_tech", params={"q": "Golden"}),
    Call("search_global", "GET", "/api/search/global", "operator_tech", params={"q": "Golden"}),

    # ── Health / platform ─────────────────────────────────────────────────
    Call("health", "GET", "/api/health", None),
]


def _run(call: Call) -> dict:
    headers = _auth(call.as_user)
    kwargs: dict[str, Any] = {"headers": headers}
    if call.json_body is not None:
        kwargs["json"] = call.json_body
    if call.params is not None:
        kwargs["params"] = call.params
    res = client.request(call.method, call.path, **kwargs)
    try:
        body: Any = res.json()
    except ValueError:
        body = res.text[:500]
    return {
        "name": call.name,
        "request": {
            "method": call.method, "path": call.path, "as_user": call.as_user,
            "json_body": call.json_body, "params": call.params,
        },
        "response": {
            "status_code": res.status_code,
            "body": body,
        },
        "note": call.note,
    }


results = [_run(c) for c in GOLDEN_CALLS]

out_dir = os.path.join(_PROJECT_ROOT, "docs", "api-contract")
os.makedirs(out_dir, exist_ok=True)
out_path = os.path.join(out_dir, "golden_master_v1.json")
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2, sort_keys=False, default=str)
    f.write("\n")

print(f"Wrote {out_path}")
print(f"{len(results)} calls captured")
for r in results:
    print(f"  {r['response']['status_code']:>3}  {r['name']}")

db.close()
_engine.dispose()
for suffix in ("", "-shm", "-wal"):
    p = _DB_PATH + suffix
    if os.path.exists(p):
        os.remove(p)
