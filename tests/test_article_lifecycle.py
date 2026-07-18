"""Regression: bulk-archive's partial-success bookkeeping (skipped_ids for
missing/already-target-state ids) and restore's archive-current-state-first
behavior had no dedicated coverage
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


def _cleanup_article(db, article_id):
    db.query(models.ArticleHistory).filter(models.ArticleHistory.article_id == article_id).delete()
    db.query(models.ArticleTargetDepartment).filter(
        models.ArticleTargetDepartment.article_id == article_id
    ).delete()
    db.query(models.Article).filter(models.Article.id == article_id).delete()


def test_bulk_archive_reports_skipped_for_missing_and_already_target_state(db_session):
    admin = make_user(db_session, email="factory_article_bulkarchive_admin@magti.ge", role="admin")
    already_archived = make_article(db_session, author=admin, title="Already Archived Marker")
    already_archived.status = "archived"
    to_archive = make_article(db_session, author=admin, title="To Be Archived Marker")
    db_session.commit()

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post("/api/articles/bulk-archive", json={
                "ids": [already_archived.id, to_archive.id, 999999999],
                "archive": True,
            })
            assert res.status_code == 200, res.text
            body = res.json()
            assert body["updated"] == 1
            assert body["status"] == "archived"
            assert set(body["skipped_ids"]) == {already_archived.id, 999999999}
    finally:
        monolith_app.dependency_overrides.clear()
        _cleanup_article(db_session, already_archived.id)
        _cleanup_article(db_session, to_archive.id)
        db_session.delete(admin)
        db_session.commit()


def test_restore_archives_current_state_first_and_bumps_version(db_session):
    admin = make_user(db_session, email="factory_article_restore_admin@magti.ge", role="admin")
    category = make_category(db_session, name="Article Restore Cat")
    article_id = None

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post("/api/articles", json={
                "title": "Restore Marker v1",
                "content": "Version one content.",
                "category_id": category.id,
                "target_departments": ["All"],
                "status": "draft",
            })
            assert res.status_code == 200, res.text
            article_id = res.json()["id"]

            res = tc.put(f"/api/articles/{article_id}", json={
                "title": "Restore Marker v2",
                "content": "Version two content.",
                "category_id": category.id,
                "target_departments": ["All"],
                "status": "draft",
            })
            assert res.status_code == 200, res.text
            assert res.json()["version"] == 2

            res = tc.get(f"/api/articles/{article_id}/history")
            assert res.status_code == 200, res.text
            history = res.json()
            v1_entry = next(h for h in history if h["version_id"] == 1)

            res = tc.post(f"/api/articles/{article_id}/history/{v1_entry['id']}/restore")
            assert res.status_code == 200, res.text
            restored = res.json()
            assert restored["title"] == "Restore Marker v1"
            assert restored["content"] == "Version one content."
            # v1 snapshot (create) + v2 snapshot (update) already existed;
            # restore archives the pre-restore (v2) state before overwriting,
            # so version increments to 3 and history gains that v2 entry.
            assert restored["version"] == 3

            res = tc.get(f"/api/articles/{article_id}/history")
            assert res.status_code == 200, res.text
            history_after = res.json()
            version_ids = sorted(h["version_id"] for h in history_after)
            assert version_ids == [1, 2, 3]
    finally:
        monolith_app.dependency_overrides.clear()
        if article_id is not None:
            _cleanup_article(db_session, article_id)
        db_session.delete(admin)
        db_session.commit()
