"""Regression: none of the /api/statistics/*, /api/manager/*-stats, or
/api/admin/critical-operators routes had any test coverage
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
from tests.factories import make_required_reading, make_user

models.Base.metadata.create_all(bind=engine)


def test_critical_operators_flags_below_threshold_only(db_session):
    admin = make_user(db_session, email="factory_stats_admin1@magti.ge", role="admin")
    critical_op = make_user(
        db_session, email="factory_stats_critical_op@magti.ge", role="operator", name="Critical Marker Operator",
    )
    compliant_op = make_user(
        db_session, email="factory_stats_compliant_op@magti.ge", role="operator", name="Compliant Marker Operator",
    )
    reading = make_required_reading(db_session, item_id=1, target_department="All", due_days=5)

    # compliant_op has read it (100%); critical_op has not (0%, below the
    # 30% _CRITICAL_THRESHOLD).
    read_status = models.ReadStatus(
        user_id=compliant_op.id, required_reading_id=reading.id, status="read",
    )
    db_session.add(read_status)
    db_session.commit()

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/admin/critical-operators")
            assert res.status_code == 200, res.text
            body = res.json()
            ids = [o["user_id"] for o in body["operators"]]
            assert critical_op.id in ids
            assert compliant_op.id not in ids
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(read_status)
        db_session.delete(reading)
        db_session.delete(critical_op)
        db_session.delete(compliant_op)
        db_session.delete(admin)
        db_session.commit()


def test_statistics_breakdown_rejects_unknown_dimension(db_session):
    admin = make_user(db_session, email="factory_stats_admin2@magti.ge", role="admin")
    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/statistics/breakdown", params={"dimension": "not_a_real_column"})
            assert res.status_code == 400

            res = tc.get("/api/statistics/breakdown", params={"dimension": "role"})
            assert res.status_code == 200, res.text
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(admin)
        db_session.commit()


def test_team_stats_manager_pinned_to_own_department(db_session):
    manager = make_user(
        db_session, email="factory_stats_manager1@magti.ge", role="manager", department="ტექნიკური",
    )
    own_dept_op = make_user(
        db_session, email="factory_stats_own_dept_op@magti.ge", role="operator", department="ტექნიკური",
        name="Own Dept Marker Operator",
    )
    other_dept_op = make_user(
        db_session, email="factory_stats_other_dept_op@magti.ge", role="operator", department="საინფორმაციო",
        name="Other Dept Marker Operator",
    )

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: manager
    monolith_app.dependency_overrides[security.get_current_manager_user] = lambda: manager
    try:
        with TestClient(monolith_app) as tc:
            # Manager tries to view another department by passing it explicitly —
            # the server must ignore this and pin to the manager's own department.
            res = tc.get("/api/manager/team-stats", params={"department": "საინფორმაციო"})
            assert res.status_code == 200, res.text
            body = res.json()
            assert body["department"] == "ტექნიკური"
            names = [m["user_name"] for m in body["members"]]
            assert "Own Dept Marker Operator" in names
            assert "Other Dept Marker Operator" not in names
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(manager)
        db_session.delete(own_dept_op)
        db_session.delete(other_dept_op)
        db_session.commit()
