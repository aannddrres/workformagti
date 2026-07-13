"""Regression: ArticleViewLog is the single source of truth for passive views
(who opened which article, when, and which version was on screen) — replacing
the retired double-write of AuditLog READ_ARTICLE/VIEW rows, which never
captured the version (stabilization pass 2026-07-13, findings F1/F2).
"""
import os
import sys

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import security

security.require_content_creator = lambda perm: security.get_current_admin_user
security.PERM_ARTICLES_CREATE = "articles.create"
security.PERM_NEWS_CREATE = "news.create"

from database import engine
from main import app as monolith_app
import models
from tests.factories import make_article, make_user

models.Base.metadata.create_all(bind=engine)


def _cleanup(db, article, users):
    db.query(models.ArticleViewLog).filter(
        models.ArticleViewLog.article_id == article.id
    ).delete()
    db.delete(article)
    for u in users:
        db.delete(u)
    db.commit()


def test_track_view_records_version_and_snapshots(db_session):
    operator = make_user(
        db_session, email="factory_viewlog_op@magti.ge", role="operator",
        department="ტექნიკური — ჯგუფი 01",
    )
    admin = make_user(db_session, email="factory_viewlog_admin@magti.ge", role="admin")
    article = make_article(db_session, author=admin, title="ViewLog Article")

    audit_before = db_session.query(models.AuditLog).filter(
        models.AuditLog.item_id == article.id,
        models.AuditLog.action.in_(["VIEW", "READ_ARTICLE"]),
    ).count()

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post(f"/api/articles/{article.id}/view")
            assert res.status_code == 200, res.text

            res = tc.post("/api/articles/999999999/view")
            assert res.status_code == 404
    finally:
        monolith_app.dependency_overrides.clear()

    db_session.expire_all()
    rows = db_session.query(models.ArticleViewLog).filter(
        models.ArticleViewLog.article_id == article.id
    ).all()
    assert len(rows) == 1
    v = rows[0]
    assert v.article_version == article.version
    assert v.operator_id == operator.id
    assert v.operator_name_snapshot == operator.name
    assert v.operator_email_snapshot == operator.email
    assert v.operator_department_snapshot == operator.department
    assert v.article_title_snapshot == article.title
    assert v.viewed_at is not None

    # The retired audit-log double-write (VIEW + READ_ARTICLE) must stay retired.
    audit_after = db_session.query(models.AuditLog).filter(
        models.AuditLog.item_id == article.id,
        models.AuditLog.action.in_(["VIEW", "READ_ARTICLE"]),
    ).count()
    assert audit_after == audit_before

    _cleanup(db_session, article, [operator, admin])


def test_view_after_version_bump_records_new_version(db_session):
    operator = make_user(db_session, email="factory_viewlog_op2@magti.ge", role="operator")
    admin = make_user(db_session, email="factory_viewlog_admin2@magti.ge", role="admin")
    article = make_article(db_session, author=admin, title="ViewLog Versioned Article")

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            assert tc.post(f"/api/articles/{article.id}/view").status_code == 200

            # Same bump update_article performs; a second view must land on v+1.
            article.version += 1
            db_session.commit()

            assert tc.post(f"/api/articles/{article.id}/view").status_code == 200
    finally:
        monolith_app.dependency_overrides.clear()

    db_session.expire_all()
    versions = sorted(
        v.article_version
        for v in db_session.query(models.ArticleViewLog).filter(
            models.ArticleViewLog.article_id == article.id
        )
    )
    assert versions == [1, 2], versions

    _cleanup(db_session, article, [operator, admin])


def test_recently_viewed_lists_viewed_article(db_session):
    operator = make_user(db_session, email="factory_viewlog_op3@magti.ge", role="operator")
    admin = make_user(db_session, email="factory_viewlog_admin3@magti.ge", role="admin")
    article = make_article(db_session, author=admin, title="ViewLog Recent Article")

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            assert tc.post(f"/api/articles/{article.id}/view").status_code == 200

            res = tc.get("/api/me/recently-viewed")
            assert res.status_code == 200, res.text
            items = res.json()
            match = [i for i in items if i["article_id"] == article.id]
            assert match, items
            # Title must be the LIVE article title (joined), not a stale snapshot.
            assert match[0]["title"] == "ViewLog Recent Article"
    finally:
        monolith_app.dependency_overrides.clear()

    _cleanup(db_session, article, [operator, admin])


def test_admin_views_endpoint_totals_and_version_filter(db_session):
    operator = make_user(db_session, email="factory_viewlog_op4@magti.ge", role="operator")
    admin = make_user(db_session, email="factory_viewlog_admin4@magti.ge", role="admin")
    article = make_article(db_session, author=admin, title="ViewLog Admin Article")

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            assert tc.post(f"/api/articles/{article.id}/view").status_code == 200
            assert tc.post(f"/api/articles/{article.id}/view").status_code == 200
            article.version += 1
            db_session.commit()
            assert tc.post(f"/api/articles/{article.id}/view").status_code == 200
    finally:
        monolith_app.dependency_overrides.clear()

    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get(f"/api/articles/{article.id}/views")
            assert res.status_code == 200, res.text
            body = res.json()
            assert body["total_views"] == 3
            assert body["unique_viewers"] == 1
            assert body["current_version"] == 2
            assert len(body["views"]) == 3

            res = tc.get(f"/api/articles/{article.id}/views?version=1")
            assert res.status_code == 200
            body = res.json()
            assert body["total_views"] == 2
            assert all(v["article_version"] == 1 for v in body["views"])
            assert all(v["operator_name"] == operator.name for v in body["views"])

            res = tc.get("/api/articles/999999999/views")
            assert res.status_code == 404
    finally:
        monolith_app.dependency_overrides.clear()

    _cleanup(db_session, article, [operator, admin])
