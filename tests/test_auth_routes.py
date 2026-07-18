"""Regression: /api/auth/logout, /api/auth/forgot-password, and the SSO
mock-login callback had zero dedicated coverage — only /api/auth/login's
production-gating edge cases were tested
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


def test_logout_clears_access_token_cookie():
    with TestClient(monolith_app) as tc:
        tc.cookies.set("access_token", "some-token-value")
        res = tc.post("/api/auth/logout")
        assert res.status_code == 200, res.text
        assert res.json() == {"detail": "Logged out"}
        # The Set-Cookie response header must expire/clear the cookie.
        set_cookie = res.headers.get("set-cookie", "")
        assert "access_token" in set_cookie


def test_forgot_password_identical_response_for_known_and_unknown_email(db_session):
    known = make_user(db_session, email="factory_forgot_pw_known@magti.ge", role="operator")
    try:
        with TestClient(monolith_app) as tc:
            res_known = tc.post("/api/auth/forgot-password", json={"email": known.email})
            res_unknown = tc.post("/api/auth/forgot-password", json={"email": "factory_forgot_pw_nobody@magti.ge"})

            assert res_known.status_code == 200, res_known.text
            assert res_unknown.status_code == 200, res_unknown.text
            assert res_known.json() == res_unknown.json()

        # Only the known-email request can write a log — an AuditLog row
        # requires a real admin_id (NOT NULL FK), and the unknown email
        # matches no user at all, so the `if user:` guard in the route
        # skips the write entirely for that request.
        db_session.expire_all()
        known_logs = db_session.query(models.AuditLog).filter(
            models.AuditLog.admin_id == known.id,
            models.AuditLog.action == "PASSWORD_RESET_REQUEST",
        ).all()
        assert len(known_logs) == 1
    finally:
        db_session.query(models.AuditLog).filter(models.AuditLog.admin_id == known.id).delete()
        db_session.delete(known)
        db_session.commit()


def test_sso_callback_issues_token_and_audit_log(db_session):
    # sso_callback authenticates with the literal dummy password
    # "sso_dummy_password" — this test user isn't in security.TEST_EMAILS,
    # so it goes through the real verify_password() check, not the dev
    # bypass; give it that exact password so the check legitimately passes.
    user = make_user(
        db_session, email="factory_sso_callback@magti.ge", role="operator",
        password="sso_dummy_password",
    )
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post("/api/auth/sso/callback", params={"email": user.email})
            assert res.status_code == 200, res.text
            body = res.json()
            assert body["token_type"] == "bearer"
            assert body["access_token"]

        db_session.expire_all()
        log = db_session.query(models.AuditLog).filter(
            models.AuditLog.admin_id == user.id,
            models.AuditLog.action == "LOGIN_SSO",
        ).first()
        assert log is not None
    finally:
        db_session.query(models.AuditLog).filter(models.AuditLog.admin_id == user.id).delete()
        db_session.delete(user)
        db_session.commit()
