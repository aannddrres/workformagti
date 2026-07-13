"""End-to-end proof of the revived auto-audit chain: a real HTTP article edit
(bearer token, no dependency overrides) must produce an AuditLog UPDATE row
attributed to the JWT holder, carrying the field-level old/new diff.

This exercises the full path: actor_context_middleware (decodes the token,
sets the ContextVar) -> ORM flush -> audit_trail after_update listener
(resolves the actor id on the flush connection, writes the row).
"""
import json
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
from tests.factories import make_article

models.Base.metadata.create_all(bind=engine)


def test_http_article_edit_writes_attributed_diff(db_session):
    monolith_app.dependency_overrides.clear()

    article = None
    with TestClient(monolith_app) as tc:
        # Dev bypass: seeded admin@magti.ge authenticates regardless of
        # password outside production — gives us a REAL token.
        login = tc.post(
            "/api/auth/login",
            json={"email": "admin@magti.ge", "password": "password"},
        )
        assert login.status_code == 200, login.text
        token = login.json()["access_token"]

        admin_row = db_session.query(models.User).filter(
            models.User.email == "admin@magti.ge"
        ).first()
        assert admin_row is not None

        article = make_article(db_session, author=admin_row, title="AuditTrail E2E Article")

        res = tc.put(
            f"/api/articles/{article.id}",
            json={
                "title": "AuditTrail E2E Article RENAMED",
                "content": article.content,
                "category_id": article.category_id,
                "target_departments": ["All"],
                "status": "published",
                "is_draft": False,
            },
            headers={"Authorization": f"Bearer {token}"},
        )
        assert res.status_code == 200, res.text

    db_session.expire_all()
    log = (
        db_session.query(models.AuditLog)
        .filter(
            models.AuditLog.item_type == "article",
            models.AuditLog.item_id == article.id,
            models.AuditLog.action == "UPDATE",
        )
        .order_by(models.AuditLog.id.desc())
        .first()
    )
    assert log is not None, "auto-audit row missing for HTTP article edit"
    assert log.admin_id == admin_row.id, "audit row must be attributed to the JWT holder"
    assert log.category == "CONTENT"

    details = json.loads(log.details)
    assert details["changed"]["title"]["old"] == "AuditTrail E2E Article"
    assert details["changed"]["title"]["new"] == "AuditTrail E2E Article RENAMED"
    # The version bump rides along in the same diff.
    assert details["changed"]["version"]["new"] == 2

    # Cleanup: this test's audit rows, history snapshot, and the article.
    db_session.query(models.AuditLog).filter(
        models.AuditLog.item_type == "article",
        models.AuditLog.item_id == article.id,
    ).delete()
    db_session.query(models.ArticleHistory).filter(
        models.ArticleHistory.article_id == article.id
    ).delete()
    art = db_session.query(models.Article).filter(models.Article.id == article.id).first()
    if art:
        db_session.delete(art)
    db_session.commit()
