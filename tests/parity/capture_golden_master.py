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
from dataclasses import dataclass
from typing import Any, Optional

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
# Imported for its side effects, not for a name: it must load AFTER the
# database.get_db patch above, and its module-level CryptContext(schemes=
# ["bcrypt"]) surfaces a passlib/bcrypt version mismatch here rather than
# midway through a capture run (see CLAUDE.md on the bcrypt==4.0.1 pin).
import security  # noqa: E402,F401

models.Base.metadata.create_all(bind=_engine)

import migrate  # noqa: E402
migrate.ensure_system_audit_permission_seeded()

from fastapi.testclient import TestClient  # noqa: E402
import main as monolith_app_module  # noqa: E402
from qa_accounts import TEST_ACCOUNTS, TEST_ACCOUNT_PASSWORD  # noqa: E402
from tests.factories import (  # noqa: E402
    make_user, make_category, make_article, make_required_reading, make_read_receipt,
)
from database import get_tbilisi_time  # noqa: E402
from datetime import timedelta  # noqa: E402
from urllib.parse import quote  # noqa: E402

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
wrong_answer = models.QuizAnswer(question_id=quiz_question.id, answer_text="3", is_correct=False, position=0)
right_answer = models.QuizAnswer(question_id=quiz_question.id, answer_text="4", is_correct=True, position=1)
db.add_all([wrong_answer, right_answer])
db.commit()
db.refresh(right_answer)

# Non-Article fixtures seeded directly via ORM (no factory helper exists for
# these yet) so GET/PUT/DELETE calls below have a known, stable id -- the
# separate create_* calls in GOLDEN_CALLS exercise POST against a distinct
# new row instead of chaining off these ids.
news_item = models.News(
    title="Golden — News Item", content="<p>News body.</p>",
    target_department="All", is_draft=False, author_id=content_admin.id,
)
db.add(news_item)

video_item = models.VideoInstruction(
    title="Golden — Video", video_url="https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    category="ტექნიკური", target_department="ტექნიკური",
)
db.add(video_item)

team = models.Team(name="Golden Team")
db.add(team)

reading_all = make_required_reading(
    db, item_id=plain_article.id, item_type="article",
    target_department="All", due_days=5,
)

# Dedicated, disposable rows for the DELETE endpoints -- kept separate from
# the fixtures above so deleting them can't break a later call in the list.
article_to_delete = make_article(
    db, author=content_admin, title="Golden — Disposable Article (delete target)",
    content="<p>To be deleted.</p>", category_id=cat.id, target_department="All",
)
news_to_delete = models.News(
    title="Golden — Disposable News", content="<p>To be deleted.</p>",
    target_department="All", is_draft=False, author_id=content_admin.id,
)
db.add(news_to_delete)
video_to_delete = models.VideoInstruction(
    title="Golden — Disposable Video", video_url="https://www.youtube.com/watch?v=000000000",
    category="ტექნიკური", target_department="All",
)
db.add(video_to_delete)
category_to_delete = models.Category(name="Golden — Disposable Category", is_active=True)
db.add(category_to_delete)
db.flush()
reading_to_delete = make_required_reading(
    db, item_id=article_to_delete.id, item_type="article",
    target_department="All", due_days=5,
)
favorite_to_delete = models.Favorite(user_id=operator_tech.id, item_type="article", item_id=quiz_article.id)
db.add(favorite_to_delete)
message_to_delete = models.Message(
    user_id=operator_tech.id, sender_id=manager_tech.id, content="Golden — disposable message",
)
db.add(message_to_delete)
db.commit()
db.refresh(news_item)
db.refresh(video_item)
db.refresh(team)
db.refresh(news_to_delete)
db.refresh(video_to_delete)
db.refresh(category_to_delete)
db.refresh(favorite_to_delete)
db.refresh(message_to_delete)

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

    # ── Quiz: full pass flow, then the gate that was previously blocking ──
    Call("quiz_attempt_wrong_answer", "POST", f"/api/articles/{quiz_article.id}/quiz/attempt", "operator_tech",
         {"answers": {str(quiz_question.id): wrong_answer.id}},
         note="passed=false expected"),
    Call("quiz_attempt_correct_answer", "POST", f"/api/articles/{quiz_article.id}/quiz/attempt", "operator_tech",
         {"answers": {str(quiz_question.id): right_answer.id}},
         note="passed=true expected; +10/+5 knowledge-score bonus on first try"),
    Call("read_receipt_after_passing_quiz", "POST", f"/api/articles/{quiz_article.id}/read-receipt", "operator_tech",
         note="must now succeed -- gate lifted after the attempt above"),
    Call("knowledge_score_after_pass", "GET", "/api/users/me/knowledge-score", "operator_tech"),
    Call("knowledge_leaderboard", "GET", "/api/knowledge-leaderboard", "operator_tech"),

    # ── Articles: history/diff/view/note/feedback/related/bulk-archive ───
    Call("article_history", "GET", f"/api/articles/{plain_article.id}/history", "content_admin"),
    Call("article_view_log", "POST", f"/api/articles/{plain_article.id}/view", "operator_tech"),
    Call("article_note_upsert", "PUT", f"/api/articles/{plain_article.id}/note", "operator_tech",
         {"content": "პირადი შენიშვნა golden capture-ისთვის"}),
    Call("article_feedback", "POST", f"/api/articles/{plain_article.id}/feedback", "operator_tech",
         {"message": "ტიპოგრაფიული შეცდომა სათაურში."},
         note="deliberately deprecated in code -- always 410, not a capture bug"),
    Call("article_related", "GET", f"/api/articles/{plain_article.id}/related", "operator_tech"),
    Call("articles_bulk_archive", "POST", "/api/articles/bulk-archive", "content_admin",
         {"ids": [dept_article.id], "archive": True},
         note="requires PERM_ARTICLES_ARCHIVE"),

    # ── News: create/list/get/update/history ──────────────────────────────
    Call("create_news", "POST", "/api/news", "content_admin",
         {"title": "Golden — Created News", "content": "<p>Body.</p>", "target_department": "All", "is_draft": False}),
    Call("list_news", "GET", "/api/news", "operator_tech"),
    Call("get_news", "GET", f"/api/news/{news_item.id}", "operator_tech"),
    Call("update_news", "PUT", f"/api/news/{news_item.id}", "content_admin",
         {"title": "Golden — News Item (edited)", "content": "<p>Edited body.</p>", "target_department": "All", "is_draft": False}),
    Call("news_history", "GET", f"/api/news/{news_item.id}/history", "content_admin"),

    # ── Videos: create/list (bug #10 exact-match probe)/view/archive ─────
    Call("create_video", "POST", "/api/videos", "content_admin",
         {"title": "Golden — Created Video", "video_url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
          "category": "ტექნიკური", "target_department": "ტექნიკური"}),
    Call("list_videos_as_tech_operator", "GET", "/api/videos", "operator_tech",
         note="bug #10 CONFIRMED empirically: operator_tech's department is the sub-group "
              "'ტექნიკური — ჯგუფი 01', video targets parent 'ტექნიკური' -- articles/news would show "
              "this via prefix-match, but videos.py's exact == returns an EMPTY list here"),
    Call("list_videos_as_office_operator", "GET", "/api/videos", "operator_office",
         note="contrast baseline: a genuinely unrelated department, also empty -- same visible symptom "
              "as the sub-group case above, for a different (correct) reason"),
    Call("video_view", "POST", f"/api/videos/{video_item.id}/view", "operator_tech"),
    Call("video_archive", "POST", f"/api/videos/{video_item.id}/archive", "content_admin",
         note="requires PERM_VIDEOS_ARCHIVE"),

    # ── Categories ─────────────────────────────────────────────────────────
    Call("create_category", "POST", "/api/categories", "content_admin", {"name": "Golden — Second Category"}),
    Call("list_categories", "GET", "/api/categories", "operator_tech"),
    Call("update_category", "PUT", f"/api/categories/{cat.id}", "content_admin",
         {"name": "Golden Master Category (renamed)"}),

    # ── Favorites ──────────────────────────────────────────────────────────
    Call("add_favorite", "POST", "/api/favorites", "operator_tech", {"item_type": "article", "item_id": plain_article.id}),
    Call("list_favorites", "GET", "/api/favorites", "operator_tech"),

    # ── Teams ──────────────────────────────────────────────────────────────
    Call("create_team", "POST", "/api/teams", "sysadmin", {"name": "Golden — Created Team"}),
    Call("list_teams", "GET", "/api/teams", "sysadmin"),

    # ── Required readings: CRUD + by-item lookup ──────────────────────────
    Call("create_required_reading", "POST", "/api/compliance/required-readings", "sysadmin",
         {"item_type": "article", "item_id": plain_article.id, "target_department": "All",
          "due_date": (get_tbilisi_time() + timedelta(days=10)).isoformat(), "priority": "normal"}),
    Call("required_reading_by_item", "GET",
         f"/api/compliance/required-readings/by-item/article/{dept_article.id}", "content_admin"),
    Call("update_required_reading", "PUT", f"/api/compliance/required-readings/{reading_all.id}", "sysadmin",
         {"item_type": "article", "item_id": plain_article.id, "target_department": "All",
          "due_date": (get_tbilisi_time() + timedelta(days=20)).isoformat(), "priority": "high"}),

    # ── Users / RBAC admin console ──────────────────────────────────────────
    Call("list_users_as_sysadmin", "GET", "/api/users", "sysadmin"),
    Call("create_user_as_sysadmin", "POST", "/api/users", "sysadmin",
         {"email": "golden.newuser@magti.ge", "name": "Golden New User", "role": "operator",
          "department": "ტექნიკური", "password": TEST_ACCOUNT_PASSWORD}),
    Call("update_user_admin_no_permission_reset", "PUT", f"/api/users/{operator_tech.id}", "sysadmin",
         {"role": "content_admin", "department": operator_tech.department},
         note="known gap #3: unlike bulk-reassign, this must NOT reset granular permissions -- capture as-is"),
    Call("update_user_status", "PUT", f"/api/users/{operator_office.id}/status", "sysadmin", {"is_active": True}),
    Call("nudge_user", "POST", f"/api/users/{operator_tech.id}/nudge", "content_admin"),
    Call("admin_reset_password", "POST", f"/api/users/{operator_office.id}/reset-password", "sysadmin",
         {"new_password": "NewGoldenPass1!"}),
    Call("group_leaders", "GET", "/api/admin/group-leaders", "sysadmin"),

    # ── Messaging: department confidentiality boundary (exact-match today) ─
    Call("send_message_same_department", "POST", "/api/messages", "manager_tech",
         {"user_id": operator_tech.id, "content": "Golden capture: same-department message."}),
    Call("send_message_cross_department_manager_blocked", "POST", "/api/messages", "manager_tech",
         {"user_id": operator_office.id, "content": "Golden capture: should be blocked."},
         note="exact-match today: manager confined to own department for direct messages"),
    Call("list_messages_operator", "GET", "/api/messages", "operator_tech"),
    Call("broadcast_as_sysadmin", "POST", "/api/broadcast", "sysadmin",
         {"message": "Golden capture broadcast.", "target_department": "All", "target_role": "All"}),

    # ── Audit log: single-row verify (SQLite degradation) ──────────────────
    Call("audit_verify_single_row_sqlite", "GET", "/api/audit-logs/1/verify", "sysadmin",
         note="must 501 on SQLite -- chain only exists on Postgres"),

    # ── Search history, platform tags/notifications ────────────────────────
    Call("search_history", "GET", "/api/search/history", "operator_tech"),
    Call("tags_list", "GET", "/api/tags", "operator_tech"),
    Call("notifications_summary", "GET", "/api/notifications/summary", "operator_tech"),

    # ── Search ────────────────────────────────────────────────────────────
    Call("search_basic", "GET", "/api/search", "operator_tech", params={"q": "Golden"}),
    Call("search_global", "GET", "/api/search/global", "operator_tech", params={"q": "Golden"}),

    # ── Health / platform ─────────────────────────────────────────────────
    Call("health", "GET", "/api/health", None),

    # ── Articles: remaining coverage (create, single archive/unarchive, ────
    # ── verify, versions, note-get, read-receipts, views, admin quiz, etc.) ─
    Call("create_article", "POST", "/api/articles", "content_admin",
         {"title": "Golden — Created Article", "content": "<p>Body.</p>", "category_id": cat.id,
          "target_departments": ["All"], "status": "published"}),
    Call("article_archive_single", "POST", f"/api/articles/{article_to_delete.id}/archive", "content_admin"),
    Call("article_unarchive_single", "POST", f"/api/articles/{article_to_delete.id}/unarchive", "content_admin"),
    Call("article_verify", "POST", f"/api/articles/{plain_article.id}/verify", "content_admin",
         note="marks last_verified_at -- 'still accurate' admin action"),
    Call("article_versions", "GET", f"/api/articles/{plain_article.id}/versions", "content_admin"),
    Call("article_note_get", "GET", f"/api/articles/{plain_article.id}/note", "operator_tech"),
    Call("article_read_receipts_admin", "GET", f"/api/articles/{plain_article.id}/read-receipts", "content_admin"),
    Call("article_read_receipt_me", "GET", f"/api/articles/{plain_article.id}/read-receipt/me", "operator_tech"),
    Call("article_views", "GET", f"/api/articles/{plain_article.id}/views", "content_admin"),
    Call("recently_viewed", "GET", "/api/me/recently-viewed", "operator_tech"),
    Call("admin_feedback_list", "GET", "/api/admin/feedback", "content_admin",
         note="deliberately deprecated in code -- always 410"),
    Call("admin_articles_stale", "GET", "/api/admin/articles/stale", "content_admin"),
    Call("quiz_admin_get", "GET", f"/api/articles/{quiz_article.id}/quiz/admin", "content_admin"),
    Call("quiz_admin_update", "PUT", f"/api/articles/{quiz_article.id}/quiz/admin", "content_admin",
         {"questions": [{"question_text": "3+3?", "position": 0, "answers": [
             {"answer_text": "5", "is_correct": False, "position": 0},
             {"answer_text": "6", "is_correct": True, "position": 1},
         ]}]},
         note="known gap #2: this full-replace does NOT bump article.version, though the quiz-gate reads version"),
    Call("article_delete", "DELETE", f"/api/articles/{article_to_delete.id}", "content_admin"),

    # ── Auth: remaining ────────────────────────────────────────────────────
    Call("forgot_password", "POST", "/api/auth/forgot-password", None, {"email": "operator.tech@magti.ge"}),
    Call("sso_mock_login_page", "GET", "/api/auth/sso/mock-login", None,
         note="known gap #11: no production-level route gate on this picker page"),
    Call("sso_callback", "POST", "/api/auth/sso/callback", None, params={"email": "content@magti.ge"},
         note="security.py's dev-bypass allowlist (6 hardcoded emails) is a DIFFERENT set than "
              "qa_accounts.TEST_ACCOUNTS (19 seeded accounts) -- only content@magti.ge/admin@magti.ge/"
              "manager@magti.ge overlap, so most seeded accounts here can't actually use mock-SSO"),
    Call("logout", "POST", "/api/auth/logout", "operator_tech"),

    # ── Categories / Favorites / Videos: remaining CRUD ────────────────────
    Call("category_delete", "DELETE", f"/api/categories/{category_to_delete.id}", "content_admin"),
    Call("favorite_delete", "DELETE", f"/api/favorites/{favorite_to_delete.id}", "operator_tech"),
    Call("video_update", "PUT", f"/api/videos/{video_item.id}", "content_admin",
         {"title": "Golden — Video (edited)", "video_url": video_item.video_url,
          "category": "ტექნიკური", "target_department": "ტექნიკური"}),
    Call("video_unarchive", "POST", f"/api/videos/{video_item.id}/unarchive", "content_admin"),
    Call("video_delete", "DELETE", f"/api/videos/{video_to_delete.id}", "content_admin"),

    # ── Articles / News: autosave ──────────────────────────────────────────
    Call("article_autosave", "PATCH", f"/api/articles/{plain_article.id}/autosave", "content_admin",
         {"title": plain_article.title, "content": "<p>Autosaved draft body.</p>"}),
    Call("news_autosave", "PATCH", f"/api/news/{news_item.id}/autosave", "content_admin",
         {"title": news_item.title, "content": "<p>Autosaved draft body.</p>"}),
    Call("news_delete", "DELETE", f"/api/news/{news_to_delete.id}", "content_admin"),

    # ── Compliance: remaining ──────────────────────────────────────────────
    Call("required_reading_delete", "DELETE", f"/api/compliance/required-readings/{reading_to_delete.id}", "sysadmin"),

    # ── Exports: the two synchronous formats not yet captured ─────────────
    Call("export_readings_xlsx_enqueue", "GET", "/api/export/readings.xlsx", "sysadmin",
         note="async job, same shape as team-stats.pdf"),
    Call("export_readings_pdf_enqueue", "GET", "/api/export/readings.pdf", "manager_tech",
         note="PERM_REPORTS_EXPORT -- manager holds this by default, content_admin does not"),

    # ── Messaging: remaining ───────────────────────────────────────────────
    Call("messages_sent", "GET", "/api/messages/sent", "manager_tech"),
    Call("message_mark_read", "POST", f"/api/messages/{message_to_delete.id}/read", "operator_tech"),
    Call("message_delete", "DELETE", f"/api/messages/{message_to_delete.id}", "operator_tech"),

    # ── Users: remaining self-service + admin ──────────────────────────────
    Call("update_users_me", "PUT", "/api/users/me", "operator_tech",
         {"name": "ოპერატორი (ტექნიკური) 1 (renamed)", "position": "უფროსი ოპერატორი"}),
    Call("change_own_password", "POST", "/api/users/me/password", "operator_tech",
         {"current_password": TEST_ACCOUNT_PASSWORD, "new_password": "NewGoldenPass2!"},
         note="uses operator_tech, not operator_office -- operator_office's password was already "
              "rotated by admin_reset_password earlier in this list, which would make TEST_ACCOUNT_PASSWORD "
              "the wrong 'current_password' by the time this call runs"),
    Call("bulk_reassign_roles", "POST", "/api/admin/roles/bulk-reassign", "sysadmin",
         {"user_ids": [operator_office.id], "new_role": "manager"},
         note="unlike the single-user PUT above (bug #3), this DOES reset permissions to the new role's defaults"),

    # ── Stats: remaining ────────────────────────────────────────────────────
    Call("popular_searches", "GET", "/api/statistics/popular-searches", "content_admin"),
    Call("failed_searches", "GET", "/api/statistics/failed-searches", "content_admin"),
    Call("user_progress_stats_forbidden", "GET", "/api/statistics/user-progress", "content_admin",
         note="correctly 403 -- this endpoint is system-admin only, unlike most other /statistics/* routes"),
    Call("user_progress_stats_as_sysadmin", "GET", "/api/statistics/user-progress", "sysadmin"),
    Call("admin_team_stats", "GET", f"/api/admin/stats/team/{team.id}", "sysadmin"),
    Call("manager_team_stats", "GET", "/api/manager/team-stats", "manager_tech"),
    Call("critical_operators", "GET", "/api/admin/critical-operators", "content_admin"),
    Call("department_group_users", "GET",
         f"/api/admin/departments/{quote('ტექნიკური')}/groups/{quote('ჯგუფი 01')}/users", "content_admin"),
    Call("statistics_breakdown_by_role", "GET", "/api/statistics/breakdown", "sysadmin", params={"dimension": "role"}),
    Call("statistics_activity", "GET", "/api/statistics/activity", "sysadmin"),

    # ── Platform: static pages + upload ─────────────────────────────────────
    Call("serve_root", "GET", "/", None),
    Call("serve_login_html", "GET", "/login.html", None),
    Call("serve_base_layout_html", "GET", "/base-layout.html", None),
    Call("serve_article_html", "GET", "/article.html", None),
    Call("serve_logo", "GET", "/static/magti_logo.png", None,
         note="NEWLY DISCOVERED (not in the original 11+4 known issues): 404s despite the file existing at "
              "the project root. main.py mounts StaticFiles at /static (line 477) BEFORE include_router(platform) "
              "(line 499) -- the mount's prefix match wins, so platform.py's dedicated "
              "@router.get('/static/magti_logo.png') (whose own docstring claims it 'intercepts the request "
              "before it falls through to the StaticFiles mount') never actually runs. Dead code, logo broken."),
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

# ── Articles: update -> history -> diff -> restore chain ───────────────────
# The diff/restore endpoints need a real history_id, which only exists after
# an edit -- so this is a short dynamic sequence, not a static Call entry.
_put_res = client.put(
    f"/api/articles/{dept_article.id}", headers=_auth("content_admin"),
    json={"title": "Golden — Tech-Only Article (edited)", "content": "<p>Edited.</p>",
          "category_id": cat.id, "target_departments": ["ტექნიკური"], "status": "published"},
)
results.append({
    "name": "article_update", "request": {"method": "PUT", "path": f"/api/articles/{dept_article.id}", "as_user": "content_admin"},
    "response": {"status_code": _put_res.status_code, "body": _put_res.json() if _put_res.status_code == 200 else _put_res.text[:500]},
    "note": "creates a history row, consumed by the diff/restore calls below",
})
_hist_res = client.get(f"/api/articles/{dept_article.id}/history", headers=_auth("content_admin"))
_hist_rows = _hist_res.json() if _hist_res.status_code == 200 else []
_hist_id = _hist_rows[0]["id"] if _hist_rows else None
if _hist_id:
    _diff_res = client.get(f"/api/articles/{dept_article.id}/history/{_hist_id}/diff", headers=_auth("content_admin"))
    results.append({
        "name": "article_history_diff",
        "request": {"method": "GET", "path": f"/api/articles/{dept_article.id}/history/{_hist_id}/diff", "as_user": "content_admin"},
        "response": {"status_code": _diff_res.status_code, "body": _diff_res.json() if _diff_res.status_code == 200 else _diff_res.text[:500]},
        "note": "diffs the pre-edit snapshot against the article's current content",
    })
    _restore_res = client.post(f"/api/articles/{dept_article.id}/history/{_hist_id}/restore", headers=_auth("content_admin"))
    results.append({
        "name": "article_history_restore",
        "request": {"method": "POST", "path": f"/api/articles/{dept_article.id}/history/{_hist_id}/restore", "as_user": "content_admin"},
        "response": {"status_code": _restore_res.status_code, "body": _restore_res.json() if _restore_res.status_code == 200 else _restore_res.text[:500]},
        "note": "",
    })

# ── News: history -> restore chain (update_news above already produced ────
# a history row) ─────────────────────────────────────────────────────────
_news_hist_res = client.get(f"/api/news/{news_item.id}/history", headers=_auth("content_admin"))
_news_hist_rows = _news_hist_res.json() if _news_hist_res.status_code == 200 else []
_news_hist_id = _news_hist_rows[0]["id"] if _news_hist_rows else None
if _news_hist_id:
    _news_restore_res = client.post(f"/api/news/{news_item.id}/history/{_news_hist_id}/restore", headers=_auth("content_admin"))
    results.append({
        "name": "news_history_restore",
        "request": {"method": "POST", "path": f"/api/news/{news_item.id}/history/{_news_hist_id}/restore", "as_user": "content_admin"},
        "response": {"status_code": _news_restore_res.status_code, "body": _news_restore_res.json() if _news_restore_res.status_code == 200 else _news_restore_res.text[:500]},
        "note": "",
    })

# ── Upload: multipart file (needs a `files=` kwarg, not `json=`) ──────────
_upload_res = client.post(
    "/api/upload", headers=_auth("content_admin"),
    files={"file": ("golden.png", b"\x89PNG\r\n\x1a\n" + b"\x00" * 32, "image/png")},
)
results.append({
    "name": "upload_file", "request": {"method": "POST", "path": "/api/upload", "as_user": "content_admin"},
    "response": {"status_code": _upload_res.status_code, "body": _upload_res.json() if _upload_res.status_code == 200 else _upload_res.text[:500]},
    "note": "",
})

# ── SSE stream: deliberately NOT captured here ────────────────────────────
# GET /api/stream is a long-lived connection, not a request/response pair --
# under TestClient it depends on the broker's Redis probe (state.py's
# check_redis(), which needs a real event loop from the app's lifespan,
# never started by this script) and can hang indefinitely rather than
# fail fast. A first attempt confirmed this: the capture run hung for
# minutes with no local Redis reachable and had to be killed. The endpoint's
# filtering behaviour (department/role/user_id match, admin-sees-all) is
# already fully documented in docs/JAVA_ORACLE_ANGULAR_MIGRATION.md section
# 2.2 -- covering it here would need a real running server + a short client
# timeout, not this in-process TestClient approach. Left for a dedicated,
# timeout-guarded test rather than this static capture.
results.append({
    "name": "sse_stream_connect", "request": {"method": "GET", "path": "/api/stream", "as_user": "operator_tech"},
    "response": {"status_code": None, "body": None},
    "note": "NOT CAPTURED -- long-lived stream, hangs under TestClient without a real event loop/Redis; "
            "behavior documented in the migration doc instead, see comment above this entry in the script",
})

# ── Exports: async job flow (create -> status -> download) ────────────────
# Needs the job_id from the create response, so it's a short dynamic
# sequence rather than a static Call entry. PERM_REPORTS_EXPORT is a
# manager-default permission (DEFAULT_PERMISSIONS_BY_ROLE), not
# content_admin's -- these calls use manager_tech accordingly.
_csv_res = client.get("/api/export/readings", headers=_auth("sysadmin"))
results.append({
    "name": "export_readings_csv", "request": {"method": "GET", "path": "/api/export/readings", "as_user": "sysadmin"},
    "response": {"status_code": _csv_res.status_code, "body": _csv_res.text[:1000]},
    "note": "system-admin only; CSV formula-sanitized (contrast with audit-log export's missing sanitization, bug #8)",
})

_pdf_res = client.get("/api/export/team-stats.pdf", headers=_auth("manager_tech"))
_job_id = _pdf_res.json().get("job_id") if _pdf_res.status_code == 200 else None
_status_res = client.get(f"/api/export/status/{_job_id}", headers=_auth("manager_tech")) if _job_id else None
_download_res = client.get(f"/api/export/download/{_job_id}", headers=_auth("manager_tech")) if _job_id else None
results.append({
    "name": "export_team_stats_pdf_enqueue",
    "request": {"method": "GET", "path": "/api/export/team-stats.pdf", "as_user": "manager_tech"},
    "response": {"status_code": _pdf_res.status_code, "body": _pdf_res.json() if _pdf_res.status_code == 200 else _pdf_res.text[:500]},
    "note": "async job -- BackgroundTasks resolve synchronously under TestClient, so status should already be completed",
})
if _status_res is not None:
    results.append({
        "name": "export_job_status", "request": {"method": "GET", "path": f"/api/export/status/{_job_id}", "as_user": "manager_tech"},
        "response": {"status_code": _status_res.status_code, "body": _status_res.json()},
        "note": "bug #9: expires_at is written but nothing ever reads/reaps it -- captured as-is",
    })
    results.append({
        "name": "export_job_download",
        "request": {"method": "GET", "path": f"/api/export/download/{_job_id}", "as_user": "manager_tech"},
        "response": {"status_code": _download_res.status_code, "body": f"<{len(_download_res.content)} bytes, {_download_res.headers.get('content-type')}>"},
        "note": "file deleted server-side after this download completes (BackgroundTask cleanup)",
    })

out_dir = os.path.join(_PROJECT_ROOT, "docs", "api-contract")
os.makedirs(out_dir, exist_ok=True)
out_path = os.path.join(out_dir, "golden_master_v1.json")
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2, sort_keys=False, default=str)
    f.write("\n")

print(f"Wrote {out_path}")
print(f"{len(results)} calls captured")
for r in results:
    sc = r['response']['status_code']
    print(f"  {sc if sc is not None else '--':>3}  {r['name']}")

db.close()
_engine.dispose()
for suffix in ("", "-shm", "-wal"):
    p = _DB_PATH + suffix
    if os.path.exists(p):
        os.remove(p)
