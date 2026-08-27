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


@pytest.mark.parametrize(
    "app_env",
    [
        # DEC-P04: whitespace a .env file and docker compose both preserve.
        "production ", " production", "\tPRODUCTION\n",
        # DEC-P05: a shorthand, and the typo nobody thinks of in advance.
        "prod", "produciton", "staging",
        # Set but empty -- a variable somebody meant to fill in and did not.
        "",
    ],
)
def test_bypass_stays_shut_for_every_non_development_app_env(db_session, monkeypatch, app_env):
    """The same Critical finding as the tests above, reached through the value
    of APP_ENV rather than through code that forgot to check it.

    ``is_production`` used to be ``self.APP_ENV == "production"``, so each of
    these was a development environment and ``admin@magti.ge`` accepted any
    password. There is no second ALLOW_DEV_LOGIN switch on this side, unlike
    the Java port -- one wrong character was the whole distance between a
    deployment and unauthenticated admin access.

    Note these set ``settings.APP_ENV`` directly, exactly as the tests above
    do, which bypasses the strip/lower applied when config.py first reads the
    variable. That is deliberate: it is why the normalisation lives inside
    ``is_development_environment`` rather than only at read time.
    """
    monkeypatch.setattr(settings, "APP_ENV", app_env)

    assert security.authenticate_user(db_session, "admin@magti.ge", _WRONG_PASSWORD) is None, (
        f"APP_ENV={app_env!r} accepted a wrong password for a bypass-listed admin"
    )


def test_named_development_environments_still_reach_the_bypass(db_session, monkeypatch):
    """The other direction: the insecure posture stays reachable by naming it,
    or local development cannot start at all."""
    monkeypatch.setattr(settings, "APP_ENV", "development")
    assert security.authenticate_user(db_session, "admin@magti.ge", _WRONG_PASSWORD) is not None
