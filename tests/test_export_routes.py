"""Regression: /api/export/readings had zero HTTP-level coverage — only the
async job status/download plumbing was tested (test_export_job_lifecycle.py),
never the CSV route itself. This route previously leaked ReadStatus rows for
ineligible users (management roles) into the export before a user_id-scoping
fix; guard that regression directly
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

from database import engine, get_tbilisi_time
from main import app as monolith_app
import models
from tests.factories import make_required_reading, make_user

models.Base.metadata.create_all(bind=engine)


def test_export_readings_csv_excludes_ineligible_management_roles(db_session):
    sysadmin = make_user(db_session, email="factory_exp_sysadmin1@magti.ge", role="admin")
    operator = make_user(
        db_session, email="factory_exp_operator1@magti.ge", role="operator", name="Eligible Operator",
    )
    manager = make_user(
        db_session, email="factory_exp_manager1@magti.ge", role="manager", name="Ineligible Manager",
    )
    reading = make_required_reading(db_session, item_id=1, target_department="All", due_days=5)

    op_status = models.ReadStatus(
        user_id=operator.id, required_reading_id=reading.id, status="read",
        read_at=get_tbilisi_time(),
    )
    mgr_status = models.ReadStatus(
        user_id=manager.id, required_reading_id=reading.id, status="read",
        read_at=get_tbilisi_time(),
    )
    db_session.add_all([op_status, mgr_status])
    db_session.commit()

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_system_admin_user] = lambda: sysadmin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/export/readings")
            assert res.status_code == 200, res.text
            csv_text = res.content.decode("utf-8")
            assert "Eligible Operator" in csv_text
            assert "Ineligible Manager" not in csv_text
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.query(models.ReadStatus).filter(
            models.ReadStatus.id.in_([op_status.id, mgr_status.id])
        ).delete(synchronize_session=False)
        db_session.delete(reading)
        db_session.delete(operator)
        db_session.delete(manager)
        db_session.delete(sysadmin)
        db_session.commit()
