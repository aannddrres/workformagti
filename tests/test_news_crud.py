"""Regression: /api/news (CRUD, autosave, history, restore) had zero
automated coverage beyond an incidental XSS-payload test
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
from tests.factories import make_user

models.Base.metadata.create_all(bind=engine)


def test_news_crud_lifecycle(db_session):
    admin = make_user(db_session, email="factory_news_admin1@magti.ge", role="admin")
    news_id = None

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post("/api/news", json={
                "title": "News CRUD Marker",
                "content": "Initial content.",
                "target_department": "All",
            })
            assert res.status_code == 200, res.text
            body = res.json()
            news_id = body["id"]
            assert body["title"] == "News CRUD Marker"

            res = tc.get("/api/news")
            assert res.status_code == 200, res.text
            assert news_id in [n["id"] for n in res.json()]

            res = tc.put(f"/api/news/{news_id}", json={
                "title": "News CRUD Marker Updated",
                "content": "Updated content.",
                "target_department": "All",
            })
            assert res.status_code == 200, res.text
            updated = res.json()
            assert updated["title"] == "News CRUD Marker Updated"
            assert updated["version"] == 2

            res = tc.delete(f"/api/news/{news_id}")
            assert res.status_code == 204, res.text
            news_id = None

            res = tc.get("/api/news")
            assert res.status_code == 200
            assert all(n["id"] != body["id"] for n in res.json())
    finally:
        monolith_app.dependency_overrides.clear()
        if news_id is not None:
            db_session.query(models.NewsHistory).filter(models.NewsHistory.news_id == news_id).delete()
            db_session.query(models.News).filter(models.News.id == news_id).delete()
        db_session.delete(admin)
        db_session.commit()


def test_news_operations_404_for_missing_id(db_session):
    admin = make_user(db_session, email="factory_news_404_admin@magti.ge", role="admin")
    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.put("/api/news/999999999", json={
                "title": "Nope", "content": "Nope", "target_department": "All",
            })
            assert res.status_code == 404

            res = tc.delete("/api/news/999999999")
            assert res.status_code == 404

            res = tc.patch("/api/news/999999999/autosave", json={"title": "Nope"})
            assert res.status_code == 404
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(admin)
        db_session.commit()


def test_news_history_restore_reverts_content_and_archives_current(db_session):
    admin = make_user(db_session, email="factory_news_restore_admin@magti.ge", role="admin")
    news_id = None

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post("/api/news", json={
                "title": "Restore Marker v1",
                "content": "Version one content.",
                "target_department": "All",
            })
            assert res.status_code == 200, res.text
            news_id = res.json()["id"]

            # Update once — this writes a NewsHistory row capturing v1's state.
            res = tc.put(f"/api/news/{news_id}", json={
                "title": "Restore Marker v2",
                "content": "Version two content.",
                "target_department": "All",
            })
            assert res.status_code == 200, res.text
            assert res.json()["version"] == 2

            res = tc.get(f"/api/news/{news_id}/history")
            assert res.status_code == 200, res.text
            history = res.json()
            assert len(history) == 1
            v1_history_id = history[0]["id"]
            assert history[0]["title"] == "Restore Marker v1"

            # Restore to the v1 snapshot.
            res = tc.post(f"/api/news/{news_id}/history/{v1_history_id}/restore")
            assert res.status_code == 200, res.text
            restored = res.json()
            assert restored["title"] == "Restore Marker v1"
            assert restored["content"] == "Version one content."
            assert restored["version"] == 3

            # The restore itself must have archived the pre-restore (v2) state.
            res = tc.get(f"/api/news/{news_id}/history")
            assert res.status_code == 200, res.text
            history_after = res.json()
            assert len(history_after) == 2
            titles = {h["title"] for h in history_after}
            assert titles == {"Restore Marker v1", "Restore Marker v2"}
    finally:
        monolith_app.dependency_overrides.clear()
        if news_id is not None:
            db_session.query(models.NewsHistory).filter(models.NewsHistory.news_id == news_id).delete()
            db_session.query(models.News).filter(models.News.id == news_id).delete()
        db_session.delete(admin)
        db_session.commit()
