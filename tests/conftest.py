"""
Shared pytest fixtures for Magti Portal tests.

Uses the project engine / magti_portal.db (same as existing suite).
After the full test session, removes users that are not in TEST_ACCOUNTS
so the admin UI stays clean for manual testing.
"""
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from database import get_db, engine
import models

models.Base.metadata.create_all(bind=engine)


@pytest.fixture
def db_session():
    db = next(get_db())
    try:
        yield db
    finally:
        db.close()


def pytest_sessionfinish(session, exitstatus):
    """Drop pytest-created users (cq_*, e2e_*, gp_*, factory_*, …). Keep TEST_LOGINS only."""
    try:
        from scripts.seed_portal import purge_extra_users

        n = purge_extra_users()
        if n:
            print(f"\n[conftest] cleaned {n} non-test user(s) after pytest")
    except Exception as exc:
        print(f"\n[conftest] user cleanup skipped: {exc}")
