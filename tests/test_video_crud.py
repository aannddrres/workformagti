"""Regression: /api/videos had zero automated coverage of any kind — no
create/update/delete/archive/unarchive/view/list test existed anywhere in
the suite (docs/CODE_AUDIT_2026-07-11.md §5 Test Coverage Gap Analysis).
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


def test_video_crud_lifecycle(db_session):
    admin = make_user(db_session, email="factory_video_admin1@magti.ge", role="admin")

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    # archive/unarchive use security.require_permission(...), a factory that
    # builds a fresh closure per call — it can't be targeted by
    # dependency_overrides the way a plain function can. Left un-overridden:
    # role_name == ROLE_SYSTEM_ADMIN ("admin") short-circuits
    # role_has_permission() to True for real, so the admin fixture passes
    # the actual permission check without needing an override.
    video_id = None
    try:
        with TestClient(monolith_app) as tc:
            # CREATE — video_url gets youtube-normalized on the way in.
            res = tc.post("/api/videos", json={
                "title": "Video CRUD Marker",
                "video_url": "https://youtu.be/dQw4w9WgXcQ",
                "target_department": "All",
                "tags": "onboarding",
            })
            assert res.status_code == 200, res.text
            body = res.json()
            video_id = body["id"]
            assert body["video_url"] == "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0"
            assert body["views_count"] == 0
            assert body["is_archived"] is False

            # LIST includes it
            res = tc.get("/api/videos")
            assert res.status_code == 200, res.text
            assert video_id in [v["id"] for v in res.json()]

            # VIEW increments the counter
            res = tc.post(f"/api/videos/{video_id}/view")
            assert res.status_code == 200, res.text
            assert res.json()["views_count"] == 1

            # UPDATE re-normalizes a new URL and changes the title
            res = tc.put(f"/api/videos/{video_id}", json={
                "title": "Video CRUD Marker Updated",
                "video_url": "dQw4w9WgXcQ",
                "target_department": "All",
                "tags": "onboarding,updated",
            })
            assert res.status_code == 200, res.text
            updated = res.json()
            assert updated["title"] == "Video CRUD Marker Updated"
            assert updated["video_url"] == "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0"

            # ARCHIVE then UNARCHIVE
            res = tc.post(f"/api/videos/{video_id}/archive")
            assert res.status_code == 200, res.text
            assert res.json()["is_archived"] is True

            # Archiving again is idempotent (no error, no state change)
            res = tc.post(f"/api/videos/{video_id}/archive")
            assert res.status_code == 200, res.text
            assert res.json()["is_archived"] is True

            res = tc.post(f"/api/videos/{video_id}/unarchive")
            assert res.status_code == 200, res.text
            assert res.json()["is_archived"] is False

            # Unarchiving a non-archived video is a 400
            res = tc.post(f"/api/videos/{video_id}/unarchive")
            assert res.status_code == 400

            # DELETE
            res = tc.delete(f"/api/videos/{video_id}")
            assert res.status_code == 204, res.text
            video_id = None

            res = tc.get("/api/videos")
            assert res.status_code == 200
            assert all(v["id"] != body["id"] for v in res.json())
    finally:
        monolith_app.dependency_overrides.clear()
        if video_id is not None:
            db_session.query(models.VideoInstruction).filter(models.VideoInstruction.id == video_id).delete()
        db_session.delete(admin)
        db_session.commit()


def test_video_operations_404_for_missing_id(db_session):
    admin = make_user(db_session, email="factory_video_admin2@magti.ge", role="admin")
    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    # archive/unarchive use security.require_permission(...), a factory that
    # builds a fresh closure per call — it can't be targeted by
    # dependency_overrides the way a plain function can. Left un-overridden:
    # role_name == ROLE_SYSTEM_ADMIN ("admin") short-circuits
    # role_has_permission() to True for real, so the admin fixture passes
    # the actual permission check without needing an override.
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post("/api/videos/999999999/view")
            assert res.status_code == 404

            res = tc.put("/api/videos/999999999", json={
                "title": "Nope", "video_url": "https://example.com", "target_department": "All",
            })
            assert res.status_code == 404

            res = tc.delete("/api/videos/999999999")
            assert res.status_code == 404

            res = tc.post("/api/videos/999999999/archive")
            assert res.status_code == 404

            res = tc.post("/api/videos/999999999/unarchive")
            assert res.status_code == 404
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(admin)
        db_session.commit()


def test_operator_only_sees_own_department_and_active_videos(db_session):
    admin = make_user(db_session, email="factory_video_admin3@magti.ge", role="admin")
    operator = make_user(
        db_session, email="factory_video_op3@magti.ge", role="operator",
        department="ტექნიკური",
    )
    own_dept = models.VideoInstruction(
        title="Own Dept Video", video_url="https://example.com/a", target_department="ტექნიკური",
    )
    other_dept = models.VideoInstruction(
        title="Other Dept Video", video_url="https://example.com/b", target_department="საინფორმაციო",
    )
    archived = models.VideoInstruction(
        title="Archived Video", video_url="https://example.com/c", target_department="ტექნიკური",
        is_archived=True,
    )
    db_session.add_all([own_dept, other_dept, archived])
    db_session.commit()
    for v in (own_dept, other_dept, archived):
        db_session.refresh(v)

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/videos")
            assert res.status_code == 200, res.text
            ids = [v["id"] for v in res.json()]
            assert own_dept.id in ids
            assert other_dept.id not in ids
            assert archived.id not in ids
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.query(models.VideoInstruction).filter(
            models.VideoInstruction.id.in_([own_dept.id, other_dept.id, archived.id])
        ).delete(synchronize_session=False)
        db_session.delete(operator)
        db_session.delete(admin)
        db_session.commit()
