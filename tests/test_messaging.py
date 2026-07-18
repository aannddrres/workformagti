"""Regression: GET /api/messages, DELETE /api/messages/{id}, and
POST /api/broadcast had zero automated coverage (test_resilience.py covers
sent-messages and mark-read, but not the inbox list, delete, or broadcast
paths) (docs/CODE_AUDIT_2026-07-11.md §5 Test Coverage Gap Analysis).
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


def test_get_my_messages_returns_own_inbox(db_session):
    recipient = make_user(db_session, email="factory_msg_recipient1@magti.ge", role="operator")
    other = make_user(db_session, email="factory_msg_other1@magti.ge", role="operator")
    msg = models.Message(user_id=recipient.id, sender_id=other.id, content="Inbox marker message")
    other_msg = models.Message(user_id=other.id, sender_id=recipient.id, content="Not mine")
    db_session.add_all([msg, other_msg])
    db_session.commit()
    db_session.refresh(msg)

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: recipient
    try:
        with TestClient(monolith_app) as tc:
            res = tc.get("/api/messages")
            assert res.status_code == 200, res.text
            ids = [m["id"] for m in res.json()]
            assert msg.id in ids
            assert other_msg.id not in ids
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.query(models.Message).filter(
            models.Message.id.in_([msg.id, other_msg.id])
        ).delete(synchronize_session=False)
        db_session.delete(recipient)
        db_session.delete(other)
        db_session.commit()


def test_delete_message_removes_own_message_not_others(db_session):
    owner = make_user(db_session, email="factory_msg_owner1@magti.ge", role="operator")
    other = make_user(db_session, email="factory_msg_owner2@magti.ge", role="operator")
    sender = make_user(db_session, email="factory_msg_sender1@magti.ge", role="admin")
    mine = models.Message(user_id=owner.id, sender_id=sender.id, content="Delete me")
    not_mine = models.Message(user_id=other.id, sender_id=sender.id, content="Not owned by requester")
    db_session.add_all([mine, not_mine])
    db_session.commit()
    db_session.refresh(mine)
    db_session.refresh(not_mine)

    monolith_app.dependency_overrides.clear()
    monolith_app.dependency_overrides[security.get_current_user] = lambda: owner
    try:
        with TestClient(monolith_app) as tc:
            res = tc.delete(f"/api/messages/{not_mine.id}")
            assert res.status_code == 404

            res = tc.delete(f"/api/messages/{mine.id}")
            assert res.status_code == 204
    finally:
        monolith_app.dependency_overrides.clear()
        db_session.query(models.Message).filter(models.Message.id == not_mine.id).delete()
        db_session.delete(owner)
        db_session.delete(other)
        db_session.delete(sender)
        db_session.commit()


def test_broadcast_requires_admin_and_writes_audit_log(db_session):
    admin = make_user(db_session, email="factory_broadcast_admin1@magti.ge", role="admin")
    operator = make_user(db_session, email="factory_broadcast_op1@magti.ge", role="operator")

    monolith_app.dependency_overrides.clear()
    try:
        with TestClient(monolith_app) as tc:
            # Non-admin is rejected (get_current_admin_user left un-overridden,
            # so it runs for real against the overridden get_current_user).
            monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
            res = tc.post("/api/broadcast", json={"message": "should be rejected"})
            assert res.status_code == 403

            # Admin succeeds and an audit row is written
            monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
            res = tc.post("/api/broadcast", json={"message": "Broadcast marker message"})
            assert res.status_code == 200, res.text
            assert res.json() == {"status": "success"}
    finally:
        monolith_app.dependency_overrides.clear()

    audit_log = db_session.query(models.AuditLog).filter(
        models.AuditLog.admin_id == admin.id,
        models.AuditLog.action == "BROADCAST",
    ).first()
    assert audit_log is not None

    db_session.delete(audit_log)
    db_session.delete(admin)
    db_session.delete(operator)
    db_session.commit()
