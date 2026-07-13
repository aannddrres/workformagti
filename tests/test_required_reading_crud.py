"""Regression: RequiredReading CRUD lifecycle (create/lookup/update/delete)
had zero automated coverage despite being the core of the compliance
feature (docs/CODE_AUDIT_2026-07-11.md §5 Test Coverage Gap Analysis).
"""
import os
import sys
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import security

security.require_content_creator = lambda perm: security.get_current_admin_user
security.PERM_ARTICLES_CREATE = "articles.create"
security.PERM_NEWS_CREATE = "news.create"

from database import engine, get_tbilisi_time
from main import app as monolith_app
import models
from tests.factories import make_article, make_user

models.Base.metadata.create_all(bind=engine)


def test_required_reading_crud_lifecycle(db_session):
    admin = make_user(db_session, email="factory_rr_admin@magti.ge", role="admin")
    article = make_article(db_session, author=admin, title="RR Factory Article")

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            due = (get_tbilisi_time() + timedelta(days=3)).isoformat()

            # CREATE
            res = tc.post("/api/compliance/required-readings", json={
                "item_type": "article",
                "item_id": article.id,
                "target_department": "ტექნიკური",
                "due_date": due,
                "priority": "high",
            })
            assert res.status_code == 200, res.text
            body = res.json()
            reading_id = body["id"]
            assert body["target_department"] == "ტექნიკური"
            assert body["priority"] == "high"

            # LOOKUP BY ITEM
            res = tc.get(f"/api/compliance/required-readings/by-item/article/{article.id}")
            assert res.status_code == 200, res.text
            assert res.json()["id"] == reading_id

            # UPDATE
            new_due = (get_tbilisi_time() + timedelta(days=10)).isoformat()
            res = tc.put(f"/api/compliance/required-readings/{reading_id}", json={
                "item_type": "article",
                "item_id": article.id,
                "target_department": "საინფორმაციო",
                "due_date": new_due,
                "priority": "normal",
            })
            assert res.status_code == 200, res.text
            updated = res.json()
            assert updated["target_department"] == "საინფორმაციო"
            assert updated["priority"] == "normal"

            # DELETE
            res = tc.delete(f"/api/compliance/required-readings/{reading_id}")
            assert res.status_code == 204, res.text

            # VERIFY GONE
            res = tc.get(f"/api/compliance/required-readings/by-item/article/{article.id}")
            assert res.status_code == 200
            assert res.json() is None
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.query(models.RequiredReading).filter(
            models.RequiredReading.item_id == article.id
        ).delete()
        db_session.delete(article)
        db_session.delete(admin)
        db_session.commit()


def test_update_required_reading_404_for_missing_id(db_session):
    admin = make_user(db_session, email="factory_rr_404_admin@magti.ge", role="admin")
    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.put("/api/compliance/required-readings/999999999", json={
                "item_type": "article",
                "item_id": 1,
                "target_department": "All",
                "due_date": get_tbilisi_time().isoformat(),
                "priority": "normal",
            })
            assert res.status_code == 404

            res = tc.delete("/api/compliance/required-readings/999999999")
            assert res.status_code == 404
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(admin)
        db_session.commit()
