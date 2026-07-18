"""Regression: Category CRUD had zero automated coverage
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
from tests.factories import make_article, make_category, make_user

models.Base.metadata.create_all(bind=engine)


def test_category_crud_lifecycle(db_session):
    admin = make_user(db_session, email="factory_cat_admin@magti.ge", role="admin")

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    category_id = None
    try:
        with TestClient(monolith_app) as tc:
            # CREATE
            res = tc.post("/api/categories", json={
                "name": "Factory CRUD Category",
                "slug": "factory-crud-category",
                "icon": "fa-flask",
                "pastel_color_class": "general",
            })
            assert res.status_code == 200, res.text
            body = res.json()
            category_id = body["id"]
            assert body["name"] == "Factory CRUD Category"

            # APPEARS IN LIST
            res = tc.get("/api/categories")
            assert res.status_code == 200, res.text
            assert any(c["id"] == category_id for c in res.json())

            # UPDATE
            res = tc.put(f"/api/categories/{category_id}", json={
                "name": "Renamed Factory Category",
                "slug": "renamed-factory-category",
                "icon": "fa-beaker",
                "pastel_color_class": "general",
            })
            assert res.status_code == 200, res.text
            updated = res.json()
            assert updated["name"] == "Renamed Factory Category"
            assert updated["icon"] == "fa-beaker"

            # DELETE
            res = tc.delete(f"/api/categories/{category_id}")
            assert res.status_code == 204, res.text

            # VERIFY GONE FROM LIST (soft-deleted: is_active=False)
            res = tc.get("/api/categories")
            assert res.status_code == 200
            assert all(c["id"] != category_id for c in res.json())
    finally:
        monolith_app.dependency_overrides.clear()
        if category_id is not None:
            db_session.query(models.Category).filter(models.Category.id == category_id).delete()
        db_session.delete(admin)
        db_session.commit()


def test_update_delete_category_404_for_missing_id(db_session):
    admin = make_user(db_session, email="factory_cat_404_admin@magti.ge", role="admin")
    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.put("/api/categories/999999999", json={
                "name": "Nonexistent",
                "slug": "nonexistent",
                "icon": "fa-question",
                "pastel_color_class": "general",
            })
            assert res.status_code == 404

            res = tc.delete("/api/categories/999999999")
            assert res.status_code == 404
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(admin)
        db_session.commit()


def test_delete_category_reassigns_articles_to_fallback(db_session):
    admin = make_user(db_session, email="factory_cat_fallback_admin@magti.ge", role="admin")
    source_cat = make_category(db_session, name="Factory Source Category")
    article = make_article(db_session, author=admin, title="Fallback Reassign Article", category_id=source_cat.id)

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.delete(f"/api/categories/{source_cat.id}")
            assert res.status_code == 204, res.text
    finally:
        monolith_app.dependency_overrides.clear()

    db_session.refresh(article)
    fallback_cat = db_session.query(models.Category).filter(models.Category.name == "ზოგადი").first()
    assert fallback_cat is not None
    assert article.category_id == fallback_cat.id

    db_session.delete(article)
    db_session.query(models.Category).filter(models.Category.id == source_cat.id).delete()
    db_session.delete(admin)
    db_session.commit()
