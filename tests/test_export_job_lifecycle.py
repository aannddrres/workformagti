"""Regression: the export job lifecycle (enqueue -> background build -> poll
status -> download -> cleanup) through the DB-backed ExportJob model had zero
automated coverage, despite being a recent fix for a real cross-worker 404 bug
(docs/CODE_AUDIT_2026-07-11.md §3.4).
"""
import os
import sys

import pytest
from fastapi import BackgroundTasks
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import security

security.require_content_creator = lambda perm: security.get_current_admin_user
security.PERM_ARTICLES_CREATE = "articles.create"
security.PERM_NEWS_CREATE = "news.create"

from database import engine
import main
from main import app as monolith_app
import models
from tests.factories import make_user

models.Base.metadata.create_all(bind=engine)


def test_export_job_enqueue_poll_download_cleanup(db_session):
    admin = make_user(db_session, email="factory_export_admin@magti.ge", role="admin")

    result = main._enqueue_export(
        BackgroundTasks(),
        [["row1col1", "row1col2"], ["row2col1", "row2col2"]],
        ["Header A", "Header B"],
        "Factory Export",
        "xlsx",
    )
    job_id = result["job_id"]

    job = db_session.query(models.ExportJob).filter(models.ExportJob.id == job_id).first()
    assert job is not None
    assert job.status == "processing"

    # Run the queued worker synchronously (BackgroundTasks itself was never
    # invoked above) to simulate the real post-response execution.
    main._async_file_worker(
        job_id,
        [["row1col1", "row1col2"], ["row2col1", "row2col2"]],
        ["Header A", "Header B"],
        "Factory Export",
        "xlsx",
    )
    db_session.expire_all()
    job = db_session.query(models.ExportJob).filter(models.ExportJob.id == job_id).first()
    assert job.status == "completed", job.status
    assert job.path and os.path.exists(job.path)

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get(f"/api/export/status/{job_id}")
            assert res.status_code == 200, res.text
            assert res.json()["status"] == "completed"

            res = tc.get(f"/api/export/download/{job_id}")
            assert res.status_code == 200, res.text
            assert len(res.content) > 0
    finally:
        monolith_app.dependency_overrides.clear()

    # Cleanup runs as a Starlette BackgroundTask attached to the response;
    # TestClient executes it synchronously after the response completes.
    db_session.expire_all()
    remaining = db_session.query(models.ExportJob).filter(models.ExportJob.id == job_id).first()
    assert remaining is None, "export job row should be deleted after successful download"

    db_session.delete(admin)
    db_session.commit()


def test_export_status_404_for_unknown_job(db_session):
    admin = make_user(db_session, email="factory_export_404_admin@magti.ge", role="admin")
    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/export/status/does-not-exist")
            assert res.status_code == 404

            res = tc.get("/api/export/download/does-not-exist")
            assert res.status_code == 404
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.delete(admin)
        db_session.commit()


def test_export_worker_marks_job_failed_on_error(db_session, monkeypatch):
    """If the file build itself raises, the job must land in 'failed', not
    stay stuck in 'processing' forever."""
    admin = make_user(db_session, email="factory_export_fail_admin@magti.ge", role="operator")
    result = main._enqueue_export(BackgroundTasks(), [], ["H"], "Broken Export", "xlsx")
    job_id = result["job_id"]

    def _boom(*args, **kwargs):
        raise RuntimeError("simulated build failure")

    monkeypatch.setattr(main, "_build_table_xlsx", _boom)
    main._async_file_worker(job_id, [], ["H"], "Broken Export", "xlsx")

    db_session.expire_all()
    job = db_session.query(models.ExportJob).filter(models.ExportJob.id == job_id).first()
    assert job.status == "failed", job.status
    assert job.path is None

    db_session.delete(job)
    db_session.delete(admin)
    db_session.commit()
