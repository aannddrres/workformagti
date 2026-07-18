"""Regression: note/related's IDOR-probing guard (404, not 200-with-empty,
for an article outside the caller's department) and the stale-articles
180-day cutoff had no dedicated coverage
(docs/CODE_AUDIT_2026-07-11.md §5 Test Coverage Gap Analysis).
"""
import os
import sys
from datetime import timedelta

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


def _cleanup_article(db, article_id):
    db.query(models.UserNote).filter(models.UserNote.article_id == article_id).delete()
    db.query(models.ArticleHistory).filter(models.ArticleHistory.article_id == article_id).delete()
    db.query(models.ArticleTargetDepartment).filter(
        models.ArticleTargetDepartment.article_id == article_id
    ).delete()
    db.query(models.Article).filter(models.Article.id == article_id).delete()


def test_note_and_related_404_for_out_of_scope_article(db_session):
    admin = make_user(db_session, email="factory_misc_admin1@magti.ge", role="admin")
    outsider = make_user(
        db_session, email="factory_misc_outsider@magti.ge", role="operator", department="საინფორმაციო",
    )
    billing_only = make_article(
        db_session, author=admin, title="Billing-Only IDOR Marker", target_department="ტექნიკური",
    )

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: outsider
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get(f"/api/articles/{billing_only.id}/note")
            assert res.status_code == 404

            res = tc.put(f"/api/articles/{billing_only.id}/note", json={"content": "should not be allowed"})
            assert res.status_code == 404

            res = tc.get(f"/api/articles/{billing_only.id}/related")
            assert res.status_code == 404
    finally:
        monolith_app.dependency_overrides.clear()
        _cleanup_article(db_session, billing_only.id)
        db_session.delete(outsider)
        db_session.delete(admin)
        db_session.commit()


def test_stale_articles_180_day_cutoff(db_session):
    admin = make_user(db_session, email="factory_misc_admin2@magti.ge", role="admin")
    stale_article = make_article(db_session, author=admin, title="Stale Marker Article")
    fresh_article = make_article(db_session, author=admin, title="Fresh Marker Article")

    now = get_tbilisi_time()
    stale_article.last_verified_at = now - timedelta(days=200)
    fresh_article.last_verified_at = now - timedelta(days=5)
    db_session.commit()

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/admin/articles/stale")
            assert res.status_code == 200, res.text
            body = res.json()
            ids = {a["id"]: a for a in body}
            assert stale_article.id in ids
            assert fresh_article.id not in ids
            assert ids[stale_article.id]["days_stale"] >= 180
    finally:
        monolith_app.dependency_overrides.clear()
        _cleanup_article(db_session, stale_article.id)
        _cleanup_article(db_session, fresh_article.id)
        db_session.delete(admin)
        db_session.commit()
