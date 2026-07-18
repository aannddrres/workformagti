"""Regression for the logging hardening (stabilization pass 2026-07-13, F6/F8):
production must not run at DEBUG with full SQL echo, and failed logins must
leave a trace.
"""
import os
import sys

from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import security

security.require_content_creator = lambda perm: security.get_current_admin_user
security.PERM_ARTICLES_CREATE = "articles.create"
security.PERM_NEWS_CREATE = "news.create"

from config import resolve_log_level, settings
from database import engine
from main import app as monolith_app
import models
from tests.factories import make_user

models.Base.metadata.create_all(bind=engine)


def test_resolve_log_level_contract():
    assert resolve_log_level("production", None) == "INFO"
    assert resolve_log_level("development", None) == "DEBUG"
    assert resolve_log_level("Production", "") == "INFO"
    # Explicit override wins in either environment.
    assert resolve_log_level("production", "debug") == "DEBUG"
    assert resolve_log_level("development", "warning") == "WARNING"


def test_sql_echo_disabled_by_default():
    assert settings.LOG_SQL is False, (
        "LOG_SQL must default to false — DEBUG SQL echo floods and rotates "
        "real errors out of the log under production traffic"
    )


def test_failed_login_writes_security_audit_row(db_session):
    user = make_user(
        db_session, email="factory_loginfail_op@magti.ge", role="operator",
        password="Correct-Password-123",
    )

    monolith_app.dependency_overrides.clear()
    with TestClient(monolith_app) as tc:
        res = tc.post(
            "/api/auth/login",
            json={"email": user.email, "password": "wrong-password-xyz"},
        )
        assert res.status_code == 401, res.text

    db_session.expire_all()
    row = (
        db_session.query(models.AuditLog)
        .filter(
            models.AuditLog.admin_id == user.id,
            models.AuditLog.action == "LOGIN_FAILED",
        )
        .order_by(models.AuditLog.id.desc())
        .first()
    )
    assert row is not None, "failed login for an existing account must be audited"
    assert row.category == "SECURITY"
    assert row.details and "IP:" in row.details

    db_session.query(models.AuditLog).filter(models.AuditLog.admin_id == user.id).delete()
    db_session.delete(user)
    db_session.commit()


def test_failed_login_unknown_email_writes_no_audit_row(db_session):
    """Unknown emails must not be hoarded — no row can exist (admin_id is a
    NOT NULL FK), so just assert the endpoint still 401s cleanly."""
    before = db_session.query(models.AuditLog).filter(
        models.AuditLog.action == "LOGIN_FAILED"
    ).count()

    monolith_app.dependency_overrides.clear()
    with TestClient(monolith_app) as tc:
        res = tc.post(
            "/api/auth/login",
            json={"email": "ghost_nonexistent@magti.ge", "password": "whatever"},
        )
        assert res.status_code == 401

    db_session.expire_all()
    after = db_session.query(models.AuditLog).filter(
        models.AuditLog.action == "LOGIN_FAILED"
    ).count()
    assert after == before
