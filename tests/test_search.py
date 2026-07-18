"""Regression: search endpoints had zero automated coverage
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
from tests.factories import make_article, make_user

models.Base.metadata.create_all(bind=engine)


def test_search_finds_matching_article_by_title(db_session):
    admin = make_user(db_session, email="factory_search_admin@magti.ge", role="admin")
    article = make_article(
        db_session, author=admin, title="Zzyzx Unique Search Marker Article",
        target_department="All",
    )

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/search", params={"q": "Zzyzx Unique Search Marker"})
            assert res.status_code == 200, res.text
            titles = [a["title"] for a in res.json()]
            assert "Zzyzx Unique Search Marker Article" in titles
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(article)
        db_session.delete(admin)
        db_session.commit()


def test_search_excludes_article_outside_operators_department(db_session):
    admin = make_user(db_session, email="factory_search_admin2@magti.ge", role="admin")
    operator = make_user(
        db_session, email="factory_search_operator@magti.ge", role="operator",
        department="Support",
    )
    article = make_article(
        db_session, author=admin, title="Billing-Only Search Marker Article",
        target_department="Billing",
    )

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/search", params={"q": "Billing-Only Search Marker"})
            assert res.status_code == 200, res.text
            titles = [a["title"] for a in res.json()]
            assert "Billing-Only Search Marker Article" not in titles
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(article)
        db_session.delete(operator)
        db_session.delete(admin)
        db_session.commit()


def test_search_global_returns_combined_shape(db_session):
    admin = make_user(db_session, email="factory_search_admin3@magti.ge", role="admin")
    article = make_article(
        db_session, author=admin, title="Quixotic Global Search Marker Article",
        target_department="All",
    )

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/search/global", params={"q": "Quixotic Global Search Marker"})
            assert res.status_code == 200, res.text
            body = res.json()
            assert "articles" in body and "news" in body and "videos" in body
            titles = [a["title"] for a in body["articles"]]
            assert "Quixotic Global Search Marker Article" in titles
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(article)
        db_session.delete(admin)
        db_session.commit()


def test_search_history_records_recent_search(db_session):
    admin = make_user(db_session, email="factory_search_admin4@magti.ge", role="admin")
    article = make_article(
        db_session, author=admin, title="Fribbulous History Marker Article",
        target_department="All",
    )

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            # /api/search (synchronous path) records a SearchLog row directly,
            # unlike /api/search/global's queued analytics write.
            res = tc.get("/api/search", params={"q": "Fribbulous History Marker"})
            assert res.status_code == 200, res.text

            res = tc.get("/api/search/history")
            assert res.status_code == 200, res.text
            terms = [h["search_term"] for h in res.json()]
            assert "fribbulous history marker" in terms
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.query(models.SearchLog).filter(
            models.SearchLog.user_id == admin.id
        ).delete()
        db_session.delete(article)
        db_session.delete(admin)
        db_session.commit()
