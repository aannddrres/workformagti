"""Regression: scripts/seed_portal.py must refuse to run when APP_ENV=production.

This script seeds known test credentials (TEST_ACCOUNTS, password Test1234!
committed in TEST_LOGINS.md) — including admin@magti.ge, the same email on the
mock-AD bypass list. It must never touch a real deployment, independent of
that bypass's own is_production gate.
"""
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from config import settings
from database import engine
import models
from scripts import seed_portal

models.Base.metadata.create_all(bind=engine)


def test_cmd_users_refuses_in_production(monkeypatch, db_session):
    monkeypatch.setattr(settings, "APP_ENV", "production")
    before = db_session.query(models.User).count()
    with pytest.raises(SystemExit):
        seed_portal.cmd_users()
    assert db_session.query(models.User).count() == before


def test_cmd_org_refuses_in_production(monkeypatch, db_session):
    monkeypatch.setattr(settings, "APP_ENV", "production")
    before = db_session.query(models.User).count()
    with pytest.raises(SystemExit):
        seed_portal.cmd_org()
    assert db_session.query(models.User).count() == before


def test_cmd_demo_refuses_in_production(monkeypatch, db_session):
    monkeypatch.setattr(settings, "APP_ENV", "production")
    before = db_session.query(models.News).count()
    with pytest.raises(SystemExit):
        seed_portal.cmd_demo()
    assert db_session.query(models.News).count() == before


def test_refuse_if_production_is_noop_in_dev(monkeypatch):
    monkeypatch.setattr(settings, "APP_ENV", "development")
    seed_portal._refuse_if_production()  # must not raise
