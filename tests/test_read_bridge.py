"""Regression: the two read-tracking systems must stay in sync (F5,
stabilization pass 2026-07-13). Before the bridge, mark-read (compliance
ReadStatus) and the read-receipt ack (versioned ArticleReadReceipt) were
written by different buttons and could disagree — an operator could be
"compliant" with no versioned receipt, or vice versa.
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
from tests.factories import make_article, make_required_reading, make_user

models.Base.metadata.create_all(bind=engine)


def _cleanup(db, article, reading, users):
    db.query(models.ReadStatus).filter(
        models.ReadStatus.required_reading_id == reading.id
    ).delete()
    db.query(models.ArticleReadReceipt).filter(
        models.ArticleReadReceipt.article_id == article.id
    ).delete()
    db.query(models.ArticleViewLog).filter(
        models.ArticleViewLog.article_id == article.id
    ).delete()
    db.delete(reading)
    db.delete(article)
    for u in users:
        db.delete(u)
    db.commit()


def test_mark_read_also_writes_version_receipt(db_session):
    operator = make_user(
        db_session, email="factory_bridge_op1@magti.ge", role="operator",
        department="ტექნიკური — ჯგუფი 01",
    )
    admin = make_user(db_session, email="factory_bridge_admin1@magti.ge", role="admin")
    article = make_article(db_session, author=admin, title="Bridge Mandatory Article")
    reading = make_required_reading(db_session, item_id=article.id, due_days=5)

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post(f"/api/compliance/mark-read/{reading.id}")
            assert res.status_code == 200, res.text
    finally:
        monolith_app.dependency_overrides.clear()

    db_session.expire_all()
    receipt = db_session.query(models.ArticleReadReceipt).filter(
        models.ArticleReadReceipt.article_id == article.id,
        models.ArticleReadReceipt.operator_id == operator.id,
    ).first()
    assert receipt is not None, "mark-read must also produce a versioned receipt"
    assert receipt.article_version == article.version
    assert receipt.operator_department_snapshot == operator.department

    _cleanup(db_session, article, reading, [operator, admin])


def test_receipt_ack_also_marks_required_reading_read(db_session):
    operator = make_user(
        db_session, email="factory_bridge_op2@magti.ge", role="operator",
        department="ტექნიკური — ჯგუფი 02",
    )
    admin = make_user(db_session, email="factory_bridge_admin2@magti.ge", role="admin")
    article = make_article(db_session, author=admin, title="Bridge Receipt Article")
    # Department-prefix targeting: RR aimed at "ტექნიკური" must cover an
    # operator whose department is "ტექნიკური — ჯგუფი 02".
    reading = make_required_reading(
        db_session, item_id=article.id, target_department="ტექნიკური", due_days=5
    )

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post(f"/api/articles/{article.id}/read-receipt")
            assert res.status_code == 200, res.text
    finally:
        monolith_app.dependency_overrides.clear()

    db_session.expire_all()
    stat = db_session.query(models.ReadStatus).filter(
        models.ReadStatus.user_id == operator.id,
        models.ReadStatus.required_reading_id == reading.id,
    ).first()
    assert stat is not None, "receipt ack must also satisfy the covering required reading"
    assert stat.status == "read"
    assert stat.operator_department_snapshot == operator.department

    _cleanup(db_session, article, reading, [operator, admin])


def test_receipt_ack_keeps_first_compliance_read_at(db_session):
    """First acknowledgment is what compliance measures — a repeat receipt ack
    must not rewrite an existing 'read' ReadStatus timestamp."""
    from datetime import timedelta
    from database import get_tbilisi_time

    operator = make_user(db_session, email="factory_bridge_op3@magti.ge", role="operator")
    admin = make_user(db_session, email="factory_bridge_admin3@magti.ge", role="admin")
    article = make_article(db_session, author=admin, title="Bridge Idempotent Article")
    reading = make_required_reading(db_session, item_id=article.id, due_days=5)

    original_read_at = get_tbilisi_time() - timedelta(days=2)
    stat = models.ReadStatus(
        user_id=operator.id,
        required_reading_id=reading.id,
        status="read",
        read_at=original_read_at,
        operator_department_snapshot=operator.department,
    )
    db_session.add(stat)
    db_session.commit()

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
    try:
        with TestClient(monolith_app) as tc:
            res = tc.post(f"/api/articles/{article.id}/read-receipt")
            assert res.status_code == 200, res.text
    finally:
        monolith_app.dependency_overrides.clear()

    db_session.expire_all()
    stat = db_session.query(models.ReadStatus).filter(
        models.ReadStatus.user_id == operator.id,
        models.ReadStatus.required_reading_id == reading.id,
    ).first()
    assert stat.status == "read"
    assert stat.read_at.replace(microsecond=0) == original_read_at.replace(microsecond=0)

    _cleanup(db_session, article, reading, [operator, admin])
