"""
Shared pytest fixtures for Magti Portal tests.

Uses a DEDICATED test database (test_magti_portal.db) so tests never pollute
the development/production database with synthetic audit-log rows, seed users,
or transient data. The test database is created fresh at the start of every
pytest session and cleaned up when the session finishes.

The production `database.engine` and `database.get_db` are monkey-patched at
import time so that every existing test file that does
    from database import engine
picks up the test engine automatically — zero changes needed in individual
test modules.
"""
import os
import sys

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

# Ensure project root is on sys.path before any local imports.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ── Isolated test database ────────────────────────────────────────────────
_PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_TEST_DB_PATH = os.path.join(_PROJECT_ROOT, "test_magti_portal.db")
_TEST_DB_URL = f"sqlite:///{_TEST_DB_PATH}"

_test_engine = create_engine(
    _TEST_DB_URL,
    connect_args={"check_same_thread": False, "timeout": 30},
)
_TestSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=_test_engine)

# Monkey-patch the database module BEFORE importing models — this ensures
# that `from database import engine` in test files gets the test engine, and
# models.Base.metadata.create_all uses the test DB.
import database  # noqa: E402
database.engine = _test_engine
database.SessionLocal = _TestSessionLocal

# Replace get_db so any test that uses `Depends(get_db)` or calls it
# directly gets a session on the test engine.
def _test_get_db():
    db = _TestSessionLocal()
    try:
        yield db
    finally:
        db.close()

database.get_db = _test_get_db

import models  # noqa: E402

# Create all tables on the test engine (idempotent).
models.Base.metadata.create_all(bind=_test_engine)

# Seed the system:audit RBAC permission so access-control tests aren't
# failing on missing seed data instead of a real bug. Imported here (after
# the database.engine patch above) so migrate.py's own `from database import
# engine` binds to the test engine, not the production one.
import migrate  # noqa: E402
migrate.ensure_system_audit_permission_seeded()


@pytest.fixture
def db_session():
    """Yields a session bound to the isolated test database.

    Each test gets a fresh session. The session is closed (but NOT rolled
    back) after the test so that later tests in the same session can observe
    the committed state — matching the old behaviour while being safely
    isolated from the real database.
    """
    db = _TestSessionLocal()
    try:
        yield db
    finally:
        db.close()


def pytest_sessionfinish(session, exitstatus):
    """Remove the test database file after the full test session.

    This guarantees every pytest run starts with a clean slate and prevents
    stale test data from accumulating on disk.
    """
    _test_engine.dispose()
    try:
        if os.path.exists(_TEST_DB_PATH):
            os.remove(_TEST_DB_PATH)
            print(f"\n[conftest] removed test database: {_TEST_DB_PATH}")
    except OSError as exc:
        print(f"\n[conftest] test DB cleanup skipped: {exc}")
