"""Regression: team-stats dashboard always exposes 3 Magti departments."""
import os
import sys

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import security

security.require_content_creator = lambda perm: security.get_current_admin_user
security.PERM_ARTICLES_CREATE = "articles.create"
security.PERM_NEWS_CREATE = "news.create"

from database import get_db, engine
from main import app as monolith_app, build_department_stats, DEPARTMENT_WHITELIST, _match_department_bucket
import models
from tests.factories import make_user

models.Base.metadata.create_all(bind=engine)

EXPECTED_DEPTS = {"ტექნიკური", "საინფორმაციო", "ოფისი"}


def test_whitelist_has_three_departments():
    assert list(DEPARTMENT_WHITELIST) == ["ტექნიკური", "საინფორმაციო", "ოფისი"]
    assert set(DEPARTMENT_WHITELIST) == EXPECTED_DEPTS


def test_match_department_bucket():
    assert _match_department_bucket("ტექნიკური") == "ტექნიკური"
    assert _match_department_bucket("ტექნიკური სამსახური") == "ტექნიკური"
    assert _match_department_bucket("საინფორმაციო") == "საინფორმაციო"
    assert _match_department_bucket("საინფო") == "საინფორმაციო"
    assert _match_department_bucket("ოფისი") == "ოფისი"
    assert _match_department_bucket("ოფისი — ჯგუფი 01".split("—")[0].strip()) == "ოფისი"
    assert _match_department_bucket("All") is None
    assert _match_department_bucket("") is None


def test_build_department_stats_always_three_buckets(db_session):
    """API shape always has exactly 3 department cards (may be empty)."""
    stats = build_department_stats(db_session)
    names = [d["name"] for d in stats["departments"]]
    assert names == list(DEPARTMENT_WHITELIST), names
    assert len(stats["departments"]) == 3


def test_build_department_stats_includes_office_operators(db_session):
    """Operators under ოფისი — ჯგუფი NN land in the ოფისი bucket."""
    op = make_user(
        db_session,
        email="factory_ds_office_op@magti.ge",
        role="operator",
        department="ოფისი — ჯგუფი 99",
        name="ოფისი ტესტ ოპ",
    )
    stats = build_department_stats(db_session)
    office = next(d for d in stats["departments"] if d["name"] == "ოფისი")
    member_ids = {
        m["user_id"]
        for g in office["groups"]
        for m in g.get("members", [])
    }
    assert op.id in member_ids, (
        f"office operator not in stats; groups={[(g['name'], g['member_count']) for g in office['groups']]}"
    )
    # cleanup
    db_session.delete(op)
    db_session.commit()


def test_department_stats_endpoint_returns_three(db_session):
    admin = make_user(db_session, email="factory_ds_api_admin@magti.ge", role="admin")
    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_manager_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/manager/department-stats")
            assert res.status_code == 200, res.text
            body = res.json()
            depts = body.get("departments") or []
            assert len(depts) == 3, [d.get("name") for d in depts]
            assert {d["name"] for d in depts} == EXPECTED_DEPTS
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(admin)
        db_session.commit()
