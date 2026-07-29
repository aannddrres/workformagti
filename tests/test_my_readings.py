"""Regression: GET /api/compliance/my-readings and GET /api/compliance/
my-progress had zero automated coverage despite being the operator-facing
dashboard widgets for the compliance feature
(docs/CODE_AUDIT_2026-07-11.md §5 Test Coverage Gap Analysis).
"""
import os
import sys

from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import security

security.require_content_creator = lambda perm: security.get_current_admin_user
security.PERM_ARTICLES_CREATE = "articles.create"
security.PERM_NEWS_CREATE = "news.create"

from database import engine
from main import app as monolith_app
import models
from tests.factories import make_article, make_required_reading, make_user

models.Base.metadata.create_all(bind=engine)


def _cleanup(db, article, reading, users):
    db.query(models.ReadStatus).filter(
        models.ReadStatus.required_reading_id == reading.id
    ).delete()
    db.delete(reading)
    db.delete(article)
    for u in users:
        db.delete(u)
    db.commit()


def test_my_readings_lists_assigned_reading_as_unread(db_session):
    admin = make_user(db_session, email="factory_myread_admin1@magti.ge", role="admin")
    operator = make_user(
        db_session, email="factory_myread_op1@magti.ge", role="operator",
        department="ტექნიკური",
    )
    article = make_article(db_session, author=admin, title="My-Readings Marker Article")
    reading = make_required_reading(
        db_session, item_id=article.id, target_department="ტექნიკური", due_days=5
    )

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/compliance/my-readings")
            assert res.status_code == 200, res.text
            items = res.json()
            item = next(i for i in items if i["reading"]["id"] == reading.id)
            assert item["status"] == "unread"
            assert item["is_overdue"] is False
            assert item["item_title"] == "My-Readings Marker Article"
    finally:
        monolith_app.dependency_overrides.clear()
        _cleanup(db_session, article, reading, [operator, admin])


def test_my_readings_empty_for_management_roles(db_session):
    admin = make_user(db_session, email="factory_myread_admin2@magti.ge", role="admin")
    article = make_article(db_session, author=admin, title="Management-Excluded Article")
    reading = make_required_reading(db_session, item_id=article.id, target_department="All", due_days=5)

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/compliance/my-readings")
            assert res.status_code == 200, res.text
            assert res.json() == []
    finally:
        monolith_app.dependency_overrides.clear()
        _cleanup(db_session, article, reading, [admin])


def test_my_progress_reflects_mark_read(db_session):
    admin = make_user(db_session, email="factory_myread_admin3@magti.ge", role="admin")
    operator = make_user(
        db_session, email="factory_myread_op3@magti.ge", role="operator",
        department="ტექნიკური",
    )
    article = make_article(db_session, author=admin, title="My-Progress Marker Article")
    reading = make_required_reading(
        db_session, item_id=article.id, target_department="ტექნიკური", due_days=5
    )

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            before = tc.get("/api/compliance/my-progress")
            assert before.status_code == 200, before.text
            assert before.json()["pending"] >= 1

            res = tc.post(f"/api/compliance/mark-read/{reading.id}")
            assert res.status_code == 200, res.text

            after = tc.get("/api/compliance/my-progress")
            assert after.status_code == 200, after.text
            after_body = after.json()
            assert after_body["read_completed"] >= before.json()["read_completed"] + 1
            assert after_body["pending"] == before.json()["pending"] - 1
    finally:
        monolith_app.dependency_overrides.clear()
        _cleanup(db_session, article, reading, [operator, admin])


def test_mark_read_rejects_department_mismatch(db_session):
    """A user outside the reading's target department must not be able to
    mark it read via a direct API call — previously mark_read had zero
    department check at all."""
    admin = make_user(db_session, email="factory_myread_admin4@magti.ge", role="admin")
    operator = make_user(
        db_session, email="factory_myread_op4@magti.ge", role="operator",
        department="ოფისი",
    )
    article = make_article(db_session, author=admin, title="Dept-Mismatch Marker Article")
    reading = make_required_reading(
        db_session, item_id=article.id, target_department="ტექნიკური", due_days=5
    )

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post(f"/api/compliance/mark-read/{reading.id}")
            assert res.status_code == 403, res.text
    finally:
        monolith_app.dependency_overrides.clear()
        _cleanup(db_session, article, reading, [operator, admin])


def test_mark_read_allows_group_suffixed_department(db_session):
    """A user in a sub-group ("ტექნიკური — ჯგუფი 03") must still be able to
    mark read a reading targeted at the parent department ("ტექნიკური") —
    the eligibility check must accept the same prefix match that
    get_my_readings already uses to show them the reading in the first place."""
    admin = make_user(db_session, email="factory_myread_admin5@magti.ge", role="admin")
    operator = make_user(
        db_session, email="factory_myread_op5@magti.ge", role="operator",
        department="ტექნიკური — ჯგუფი 03",
    )
    article = make_article(db_session, author=admin, title="Group-Suffix Marker Article")
    reading = make_required_reading(
        db_session, item_id=article.id, target_department="ტექნიკური", due_days=5
    )

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post(f"/api/compliance/mark-read/{reading.id}")
            assert res.status_code == 200, res.text
    finally:
        monolith_app.dependency_overrides.clear()
        _cleanup(db_session, article, reading, [operator, admin])
