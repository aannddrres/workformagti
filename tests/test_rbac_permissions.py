"""Regression: require_permission()'s dotted PERM_* catalog (security.py)
and the DB-backed Role/RolePermission/Permission catalog seeded by
scripts/seed_rbac.py use two entirely different naming schemes ("videos.archive"
vs "content:archive") that share no strings — except PERM_SYSTEM_AUDIT, which
was deliberately kept colon-named to match. Before this fix, role_has_permission
only ever checked the DB tables, so every dotted permission except
PERM_SYSTEM_AUDIT was unreachable for any role but system_admin, even though
DEFAULT_PERMISSIONS_BY_ROLE, scripts/sync_rbac.py, and the admin "edit
permissions" UI all populate/edit User.permissions assuming it was live.
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


def test_content_admin_can_archive_video_via_user_permissions_column(db_session):
    """content_admin's DEFAULT_PERMISSIONS_BY_ROLE entry includes
    PERM_VIDEOS_ARCHIVE — this must actually grant archive access, not just
    sit unread in the User.permissions column."""
    content_admin = make_user(db_session, email="factory_rbac_content1@magti.ge", role="content_admin")
    video = models.VideoInstruction(title="RBAC Fix Marker Video", video_url="https://example.com/v")
    db_session.add(video)
    db_session.commit()
    db_session.refresh(video)

    assert security.PERM_VIDEOS_ARCHIVE in content_admin.permissions

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: content_admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post(f"/api/videos/{video.id}/archive")
            assert res.status_code == 200, res.text
            assert res.json()["is_archived"] is True
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(video)
        db_session.delete(content_admin)
        db_session.commit()


def test_operator_still_denied_video_archive(db_session):
    """operator's DEFAULT_PERMISSIONS_BY_ROLE entry is empty — must stay 403,
    proving the fix checks the user's actual permissions rather than granting
    access unconditionally."""
    operator = make_user(db_session, email="factory_rbac_operator1@magti.ge", role="operator")
    video = models.VideoInstruction(title="RBAC Fix Denial Marker Video", video_url="https://example.com/v2")
    db_session.add(video)
    db_session.commit()
    db_session.refresh(video)

    assert security.PERM_VIDEOS_ARCHIVE not in (operator.permissions or [])

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post(f"/api/videos/{video.id}/archive")
            assert res.status_code == 403
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(video)
        db_session.delete(operator)
        db_session.commit()


def test_require_permission_returns_same_dependency_for_same_args():
    """require_permission is cached (lru_cache) so two identical calls return
    the same object — required for dependency_overrides to target it, and the
    correct shared-identity behavior for a stateless factory."""
    dep1 = security.require_permission(security.PERM_VIDEOS_ARCHIVE)
    dep2 = security.require_permission(security.PERM_VIDEOS_ARCHIVE)
    assert dep1 is dep2

    dep3 = security.require_permission(security.PERM_VIDEOS_ARCHIVE, exclude_roles=frozenset({security.ROLE_MANAGER}))
    assert dep3 is not dep1
