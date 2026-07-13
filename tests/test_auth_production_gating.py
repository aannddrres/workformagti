"""Regression for docs/CODE_AUDIT_2026-07-11.md §3.1 (Critical): the mock-AD
password-bypass and JIT test-account provisioning must be completely
unreachable once APP_ENV=production — a single misconfigured env var must
not silently reopen unauthenticated admin access.

These tests never set/overwrite a bypass-listed email's password — they only
ever pass an intentionally-wrong password, so they can't accidentally clobber
the documented manual-login credentials for admin@magti.ge etc.
"""
import os
import sys

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from config import settings
from database import engine
from main import app as monolith_app
import models
import security

models.Base.metadata.create_all(bind=engine)

_WRONG_PASSWORD = "totally-wrong-password-xyz123"


def test_dev_bypass_then_production_gating(db_session, monkeypatch):
    monkeypatch.setattr(settings, "APP_ENV", "development")
    # JIT-provisions admin@magti.ge if missing, or uses the existing seeded
    # row otherwise — either way the bypass must grant access despite the
    # wrong password, proving the negative case below actually tests something.
    user = security.authenticate_user(db_session, "admin@magti.ge", _WRONG_PASSWORD)
    assert user is not None, "dev bypass should grant access for a bypass-listed email"
    assert user.email == "admin@magti.ge"

    monkeypatch.setattr(settings, "APP_ENV", "production")
    user = security.authenticate_user(db_session, "admin@magti.ge", _WRONG_PASSWORD)
    assert user is None, "dev bypass must be unreachable when APP_ENV=production"


def test_jit_provisioning_disabled_in_production(db_session, monkeypatch):
    """A never-before-seen bypass-eligible email (test_operator_*) must not
    be auto-created once APP_ENV=production."""
    monkeypatch.setattr(settings, "APP_ENV", "production")
    ghost_email = "test_operator_prodgate_check@magti.ge"
    assert db_session.query(models.User).filter(models.User.email == ghost_email).first() is None

    user = security.authenticate_user(db_session, ghost_email, "any-password")
    assert user is None
    assert db_session.query(models.User).filter(models.User.email == ghost_email).first() is None


def test_login_endpoint_rejects_bypass_email_in_production(db_session, monkeypatch):
    """Full-stack confirmation via the real HTTP endpoint (no dependency
    overrides) — mirrors how the original audit demonstrated the
    vulnerability live with curl."""
    monkeypatch.setattr(settings, "APP_ENV", "development")
    security.authenticate_user(db_session, "admin@magti.ge", _WRONG_PASSWORD)  # ensure it exists

    monkeypatch.setattr(settings, "APP_ENV", "production")
    with TestClient(monolith_app) as tc:
        res = tc.post(
            "/api/auth/login",
            json={"email": "admin@magti.ge", "password": _WRONG_PASSWORD},
        )
    assert res.status_code == 401, res.text
