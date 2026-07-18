"""Regression: article delete had zero coverage, and neither the
list-visibility roundtrip nor autosave's partial/target_departments handling
was tested end-to-end (create/update basics are covered piecemeal in
test_resilience.py, but not these specific paths)
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
from tests.factories import make_category, make_user

models.Base.metadata.create_all(bind=engine)


def test_article_crud_lifecycle(db_session):
    admin = make_user(db_session, email="factory_article_admin1@magti.ge", role="admin")
    category = make_category(db_session, name="Article CRUD Cat")
    article_id = None

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post("/api/articles", json={
                "title": "Article CRUD Marker",
                "content": "Initial content.",
                "category_id": category.id,
                "target_departments": ["All"],
                "status": "draft",
            })
            assert res.status_code == 200, res.text
            body = res.json()
            article_id = body["id"]
            assert body["version"] == 1

            res = tc.get("/api/articles")
            assert res.status_code == 200, res.text
            assert article_id in [a["id"] for a in res.json()]

            res = tc.put(f"/api/articles/{article_id}", json={
                "title": "Article CRUD Marker Updated",
                "content": "Updated content.",
                "category_id": category.id,
                "target_departments": ["All"],
                "status": "draft",
            })
            assert res.status_code == 200, res.text
            updated = res.json()
            assert updated["title"] == "Article CRUD Marker Updated"
            assert updated["version"] == 2

            res = tc.get(f"/api/articles/{article_id}/history")
            assert res.status_code == 200, res.text
            # create_article writes the v1 snapshot directly; update_article
            # writes the new v2 snapshot — both accumulate in history.
            assert len(res.json()) == 2

            res = tc.delete(f"/api/articles/{article_id}")
            assert res.status_code == 204, res.text
            article_id = None

            res = tc.get("/api/articles")
            assert res.status_code == 200
            assert all(a["id"] != body["id"] for a in res.json())
    finally:
        monolith_app.dependency_overrides.clear()
        if article_id is not None:
            db_session.query(models.ArticleHistory).filter(models.ArticleHistory.article_id == article_id).delete()
            db_session.query(models.ArticleTargetDepartment).filter(
                models.ArticleTargetDepartment.article_id == article_id
            ).delete()
            db_session.query(models.Article).filter(models.Article.id == article_id).delete()
        db_session.delete(admin)
        db_session.commit()


def test_article_operations_404_for_missing_id(db_session):
    admin = make_user(db_session, email="factory_article_404_admin@magti.ge", role="admin")
    category = make_category(db_session, name="Article 404 Cat")
    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/articles/999999999")
            assert res.status_code == 404

            res = tc.put("/api/articles/999999999", json={
                "title": "Nope", "content": "Nope", "category_id": category.id,
                "target_departments": ["All"], "status": "draft",
            })
            assert res.status_code == 404

            res = tc.patch("/api/articles/999999999/autosave", json={"title": "Nope"})
            assert res.status_code == 404

            res = tc.delete("/api/articles/999999999")
            assert res.status_code == 404
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(admin)
        db_session.commit()


def test_autosave_partial_update_preserves_omitted_fields(db_session):
    """Autosave uses exclude_unset — omitted fields (including
    target_departments) must be left untouched, not reset to defaults."""
    admin = make_user(db_session, email="factory_article_autosave_admin@magti.ge", role="admin")
    category = make_category(db_session, name="Article Autosave Cat")
    article_id = None

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post("/api/articles", json={
                "title": "Autosave Marker",
                "content": "Original content.",
                "category_id": category.id,
                "target_departments": ["ტექნიკური"],
                "status": "draft",
            })
            assert res.status_code == 200, res.text
            article_id = res.json()["id"]

            # Autosave only sends `content` — title and target_departments
            # must survive unchanged.
            res = tc.patch(f"/api/articles/{article_id}/autosave", json={
                "content": "Autosaved content.",
            })
            assert res.status_code == 200, res.text
            body = res.json()
            assert body["content"] == "Autosaved content."
            assert body["title"] == "Autosave Marker"
            assert body["target_departments"] == ["ტექნიკური"]
    finally:
        monolith_app.dependency_overrides.clear()
        if article_id is not None:
            db_session.query(models.ArticleHistory).filter(models.ArticleHistory.article_id == article_id).delete()
            db_session.query(models.ArticleTargetDepartment).filter(
                models.ArticleTargetDepartment.article_id == article_id
            ).delete()
            db_session.query(models.Article).filter(models.Article.id == article_id).delete()
        db_session.delete(admin)
        db_session.commit()
