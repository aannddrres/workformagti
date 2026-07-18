"""Regression: the entire Users/RBAC/Admin route set had zero automated
coverage (list, create, update, status, permissions, reset-password, nudge,
bulk-reassign, group-leaders, teams). This file covers the highest-value
guardrails, not exhaustive CRUD shape
(docs/CODE_AUDIT_2026-07-11.md §5 Test Coverage Gap Analysis).
"""
import os
import sys

from fastapi import Depends
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import security

security.require_content_creator = lambda perm: security.get_current_admin_user
security.PERM_ARTICLES_CREATE = "articles.create"
security.PERM_NEWS_CREATE = "news.create"

from database import engine, get_db
from main import app as monolith_app
import models
from tests.factories import make_user

models.Base.metadata.create_all(bind=engine)


def test_put_users_me_not_shadowed_by_user_id_route(db_session):
    """PUT /api/users/me must update the caller's own profile, not be
    captured by PUT /api/users/{user_id} with user_id="me" — the ordering
    invariant routers/users.py's module docstring calls out explicitly."""
    user = make_user(db_session, email="factory_rbac_me1@magti.ge", role="operator", name="Original Name")

    def _override_current_user(db=Depends(get_db)):
        # Re-query within the SAME per-request session get_db() hands the
        # route (FastAPI dependency-caches Depends(get_db) per request) —
        # returning the db_session-bound `user` object directly would make
        # the route's own db.refresh(current_user) fail with "not persistent
        # within this Session", since production code relies on
        # get_current_user and the route sharing one session.
        return db.query(models.User).filter(models.User.id == user.id).first()

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = _override_current_user
    try:
        with TestClient(monolith_app) as tc:
            res = tc.put("/api/users/me", json={"name": "Updated Own Name"})
            assert res.status_code == 200, res.text
            body = res.json()
            assert body["id"] == user.id
            assert body["name"] == "Updated Own Name"
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(user)
        db_session.commit()


def test_bulk_reassign_guardrails(db_session):
    """Two real, independently-triggerable guardrails on bulk-reassign:
    an unknown target role is rejected, and reassigning ONLY the caller's own
    id is refused (self-exclusion drops it from the target set, leaving
    nothing to reassign) rather than silently no-op'ing."""
    admin = make_user(db_session, email="factory_rbac_bulkadmin@magti.ge", role="admin")

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_system_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post("/api/admin/roles/bulk-reassign", json={
                "user_ids": [admin.id], "new_role": "not_a_real_role",
            })
            assert res.status_code == 400

            res = tc.post("/api/admin/roles/bulk-reassign", json={
                "user_ids": [admin.id], "new_role": "operator",
            })
            assert res.status_code == 400
            db_session.expire_all()
            assert db_session.query(models.User).filter(models.User.id == admin.id).first().role == "admin"
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(admin)
        db_session.commit()


def test_update_user_status_cannot_deactivate_self(db_session):
    admin = make_user(db_session, email="factory_rbac_selfdeactivate@magti.ge", role="admin")

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_system_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.put(f"/api/users/{admin.id}/status", json={"is_active": False})
            assert res.status_code == 400
            db_session.expire_all()
            assert db_session.query(models.User).filter(models.User.id == admin.id).first().is_active is True
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(admin)
        db_session.commit()


def test_permissions_update_rejects_unknown_permission(db_session):
    admin = make_user(db_session, email="factory_rbac_permsadmin@magti.ge", role="admin")
    target = make_user(db_session, email="factory_rbac_permstarget@magti.ge", role="operator")

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_system_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.put(f"/api/users/{target.id}/permissions", json={
                "permissions": ["not.a.real.permission"],
            })
            assert res.status_code == 400

            res = tc.put(f"/api/users/{target.id}/permissions", json={
                "permissions": [security.PERM_ARTICLES_VIEW],
            })
            assert res.status_code == 200, res.text
            assert res.json()["permissions"] == [security.PERM_ARTICLES_VIEW]
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(admin)
        db_session.delete(target)
        db_session.commit()
