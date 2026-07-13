import os
import sys
import pytest
from fastapi.testclient import TestClient

# Ensure the project root is in the python path for absolute imports
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import security
security.require_content_creator = lambda perm: security.get_current_admin_user
security.PERM_ARTICLES_CREATE = "articles.create"
security.PERM_NEWS_CREATE = "news.create"

from main import app as monolith_app
import models
from datetime import timedelta
from database import get_db, engine, get_tbilisi_time

models.Base.metadata.create_all(bind=engine)

@pytest.fixture
def db_session():
    db = next(get_db())
    try:
        yield db
    finally:
        db.close()

@pytest.fixture
def admin_user(db_session):
    admin = db_session.query(models.User).filter(models.User.role == "admin").first()
    if not admin:
        admin = models.User(
            email="admin@magti.ge",
            name="სისტემური ადმინი",
            role="admin",
            department="Administration",
            is_active=True
        )
        db_session.add(admin)
        db_session.commit()
        db_session.refresh(admin)
    return admin

@pytest.fixture
def client(admin_user):
    active_app = monolith_app

    # Set mock user overrides
    active_app.dependency_overrides[security.get_current_admin_user] = lambda: admin_user
    active_app.dependency_overrides[security.get_current_user] = lambda: admin_user

    with TestClient(active_app) as tc:
        yield tc

    active_app.dependency_overrides.clear()

def test_xss_injection(client, db_session):
    """XSS Injection Test: Verifies that creating news with HTML/Script tags

    does not crash the backend and is handled/saved safely.
    """
    payload = {
        "title": "<script>alert('XSS')</script> Title",
        "content": "<p>Dangerous content <script>XSS</script></p>",
        "target_department": "All",
        "is_draft": True
    }
    
    response = client.post("/api/news", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["title"] == "<script>alert('XSS')</script> Title"
    assert data["content"] == "<p>Dangerous content <script>XSS</script></p>"
    
    # Cleanup database
    created_id = data["id"]
    db_news = db_session.query(models.News).filter(models.News.id == created_id).first()
    if db_news:
        db_session.delete(db_news)
        db_session.commit()

def test_malformed_idor(admin_user):
    """Malformed IDOR Test: Verifies that strings and negative integers

    sent to message endpoints are handled gracefully instead of throwing 500s.
    """
    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin_user
    
    with TestClient(monolith_app) as tc:
        # String ID (validated by FastAPI type constraints -> 422)
        response_str = tc.post("/api/messages/abc/read")
        assert response_str.status_code == 422
        
        # Negative integer ID (doesn't exist -> 404)
        response_neg = tc.post("/api/messages/-9999/read")
        assert response_neg.status_code == 404
        
    monolith_app.dependency_overrides.clear()

def test_auth_missing():
    """Auth Missing Test: Verifies that sending requests to secure admin

    endpoints without auth headers results in 401/403 errors.
    """
    monolith_app.dependency_overrides.clear()

    with TestClient(monolith_app) as tc:
        # Attempt to create news without credentials
        payload = {"title": "Test", "content": "Test"}
        response = tc.post("/api/news", json=payload)
        assert response.status_code in (401, 403)


def test_youtube_normalization():
    """Verifies that backend normalizes YouTube URLs correctly."""
    from main import normalize_youtube_url
    
    test_cases = {
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ": "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0",
        "https://youtube.com/watch?v=dQw4w9WgXcQ&feature=share": "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0",
        "http://youtube.com/watch?v=dQw4w9WgXcQ": "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0",
        "youtube.com/watch?v=dQw4w9WgXcQ": "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0",
        "https://youtu.be/dQw4w9WgXcQ": "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0",
        "youtu.be/dQw4w9WgXcQ?t=10": "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0",
        "https://www.youtube.com/embed/dQw4w9WgXcQ": "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0",
        "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0": "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0",
        "dQw4w9WgXcQ": "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0",
        "https://example.com/not-youtube": "https://example.com/not-youtube",
        "": "",
        None: None,
    }
    
    for input_url, expected in test_cases.items():
        assert normalize_youtube_url(input_url) == expected


def test_sent_messages(admin_user, db_session):
    """Verifies that manager/admin can retrieve messages they sent."""
    recipient = db_session.query(models.User).filter(models.User.email == "test_operator_sent@magti.ge").first()
    if not recipient:
        recipient = models.User(
            email="test_operator_sent@magti.ge",
            name="Test Recipient",
            role="operator",
            department="Support",
            is_active=True
        )
        db_session.add(recipient)
        db_session.commit()
        db_session.refresh(recipient)

    monolith_app.dependency_overrides[security.get_current_user] = lambda: admin_user
    monolith_app.dependency_overrides[security.get_current_manager_user] = lambda: admin_user

    try:
        with TestClient(monolith_app) as tc:
            payload = {"user_id": recipient.id, "content": "Test sent messages content"}
            response = tc.post("/api/messages", json=payload)
            assert response.status_code == 200
            msg_id = response.json()["id"]

            sent_response = tc.get("/api/messages/sent")
            assert sent_response.status_code == 200
            sent_msgs = sent_response.json()
            assert len(sent_msgs) >= 1

            target_msg = [m for m in sent_msgs if m["id"] == msg_id][0]
            assert target_msg["content"] == "Test sent messages content"
            assert target_msg["sender_id"] == admin_user.id
            assert target_msg["user_id"] == recipient.id
            assert target_msg["sender_name"] == admin_user.name
            assert target_msg["recipient_name"] == recipient.name

            # Clean up database
            db_msg = db_session.query(models.Message).filter(models.Message.id == msg_id).first()
            if db_msg:
                db_session.delete(db_msg)
                db_session.commit()
    finally:
        monolith_app.dependency_overrides.clear()
        # Sentinel recipient must never persist into the shared dev DB.
        db_session.query(models.User).filter(
            models.User.email == "test_operator_sent@magti.ge"
        ).delete(synchronize_session=False)
        db_session.commit()


def test_article_status_and_youtube_id(client, db_session):
    """Verifies backend maps youtube_id and status correctly on create and update."""
    # Ensure category 1 exists for the foreign key reference
    category = db_session.query(models.Category).filter(models.Category.id == 1).first()
    if not category:
        category = models.Category(id=1, name="Test Category", is_active=True)
        db_session.add(category)
        db_session.commit()

    payload = {
        "title": "Test CMS Feature",
        "content": "CMS Content testing status and youtube_id.",
        "category_id": 1,
        "target_departments": ["Support"],
        "status": "draft",
        "youtube_id": "dQw4w9WgXcQ"
    }

    # POST create
    response = client.post("/api/articles", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["youtube_id"] == "dQw4w9WgXcQ"
    assert data["status"] == "draft"
    article_id = data["id"]

    # Check database raw values
    db_article = db_session.query(models.Article).filter(models.Article.id == article_id).first()
    assert db_article is not None
    assert db_article.youtube_id == "dQw4w9WgXcQ"
    assert db_article.status == "draft"

    # PUT update
    update_payload = payload.copy()
    update_payload["youtube_id"] = "xyz12345678"
    update_payload["status"] = "published"
    update_response = client.put(f"/api/articles/{article_id}", json=update_payload)
    assert update_response.status_code == 200
    update_data = update_response.json()
    assert update_data["youtube_id"] == "xyz12345678"
    assert update_data["status"] == "published"

    # Test default values when null in DB
    db_article.youtube_id = None
    db_article.status = None
    db_session.commit()
    db_session.refresh(db_article)

    # Get single article to check schema defaults
    get_response = client.get(f"/api/articles/{article_id}")
    assert get_response.status_code == 200
    get_data = get_response.json()
    assert get_data["youtube_id"] == ""
    assert get_data["status"] == "draft"

    # Clean up database
    db_session.delete(db_article)
    db_session.commit()


def test_draft_publish_via_edit_notifies(client, db_session, monkeypatch):
    """Editing a draft article to status="published" must broadcast an
    'article' SSE event (like create_article does), even without
    notify_operators ticked — and must NOT also fire the revision broadcast.
    """
    import main

    category = db_session.query(models.Category).filter(models.Category.id == 1).first()
    if not category:
        category = models.Category(id=1, name="Test Category", is_active=True)
        db_session.add(category)
        db_session.commit()

    notify_calls = []
    revision_calls = []
    monkeypatch.setattr(main, "_notify", lambda *a, **kw: notify_calls.append((a, kw)))
    monkeypatch.setattr(main, "_notify_revision", lambda *a, **kw: revision_calls.append((a, kw)))

    payload = {
        "title": "Draft to publish",
        "content": "Some content.",
        "category_id": 1,
        "target_departments": ["Support"],
        "status": "draft",
    }
    response = client.post("/api/articles", json=payload)
    assert response.status_code == 200
    article_id = response.json()["id"]
    assert notify_calls == []  # create_article itself skips _notify for drafts

    update_payload = payload.copy()
    update_payload["status"] = "published"
    update_response = client.put(f"/api/articles/{article_id}", json=update_payload)
    assert update_response.status_code == 200

    assert len(notify_calls) == 1
    assert notify_calls[0][0][0] == "article"
    assert revision_calls == []

    db_article = db_session.query(models.Article).filter(models.Article.id == article_id).first()
    db_session.delete(db_article)
    db_session.commit()


def test_health_check_redis_fallback_status(client, monkeypatch):
    """/api/health must reflect the broker's real Redis connectivity
    (_use_redis), not just whether the event loop was initialized — otherwise
    a Redis outage under multiple gunicorn workers is invisible to monitoring.
    """
    import main

    monkeypatch.setattr(main.broker, "_main_loop", object())
    monkeypatch.setattr(main.broker, "_use_redis", False)
    monkeypatch.delenv("GUNICORN_CMD_ARGS", raising=False)
    monkeypatch.delenv("WEB_CONCURRENCY", raising=False)
    monkeypatch.delenv("UVICORN_WORKERS", raising=False)

    response = client.get("/api/health")
    assert response.status_code == 200
    assert response.json()["redis"] == "degraded_fallback"

    monkeypatch.setenv("WEB_CONCURRENCY", "4")
    response = client.get("/api/health")
    assert response.status_code == 503
    body = response.json()["detail"]
    assert body["redis"] == "degraded_fallback"
    assert body["status"] == "degraded"


def test_article_published_at_set(client, db_session):
    """Verifies that published_at is set to the current Tbilisi time when an
    article is created or updated with status='published' and published_at is null.
    """
    category = db_session.query(models.Category).filter(models.Category.id == 1).first()
    if not category:
        category = models.Category(id=1, name="Test Category", is_active=True)
        db_session.add(category)
        db_session.commit()

    payload = {
        "title": "Immediate Publish Test",
        "content": "Content...",
        "category_id": 1,
        "target_departments": ["Support"],
        "status": "published",
    }

    # 1. Test POST creation immediately published
    response = client.post("/api/articles", json=payload)
    assert response.status_code == 200
    data = response.json()
    article_id = data["id"]

    db_article = db_session.query(models.Article).filter(models.Article.id == article_id).first()
    assert db_article.published_at is not None

    # 2. Test PUT update draft -> published sets published_at
    db_article.status = "draft"
    db_article.published_at = None
    db_session.commit()

    update_payload = payload.copy()
    update_payload["title"] = "Updated title"
    update_payload["status"] = "published"

    update_response = client.put(f"/api/articles/{article_id}", json=update_payload)
    assert update_response.status_code == 200

    db_session.refresh(db_article)
    assert db_article.published_at is not None

    # Cleanup
    db_session.delete(db_article)
    db_session.commit()


def test_orm_auto_audit_and_diff(db_session):
    """Verifies audit_trail's listeners: ORM insert/update/delete on an audited
    model (Category) auto-creates ATTRIBUTED AuditLog rows, and the update
    captures the field-level old/new diff.

    Fossil-proof rewrite: the previous version filtered on generic values and
    kept passing for months on leftover rows from a long-dead listener chain
    (SQLite reuses Category ids, so old audit rows matched new categories).
    Unique names, actor filtering, and a no-actor negative case make that
    impossible now.
    """
    import json
    import uuid

    import audit_trail
    from tests.factories import make_user

    actor = make_user(db_session, email="factory_audit_actor@magti.ge", role="admin")
    unique = f"Audit Trail Cat {uuid.uuid4().hex[:8]}"

    ctx = audit_trail.current_actor_email.set(actor.email)
    try:
        # 1. CREATE
        category = models.Category(name=unique, is_active=True)
        db_session.add(category)
        db_session.commit()
        db_session.refresh(category)

        create_log = db_session.query(models.AuditLog).filter(
            models.AuditLog.item_type == "category",
            models.AuditLog.item_id == category.id,
            models.AuditLog.action == "CREATE",
            models.AuditLog.admin_id == actor.id,
        ).first()
        assert create_log is not None

        # 2. UPDATE with deep diff
        category.name = unique + " Updated"
        db_session.commit()

        update_log = db_session.query(models.AuditLog).filter(
            models.AuditLog.item_type == "category",
            models.AuditLog.item_id == category.id,
            models.AuditLog.action == "UPDATE",
            models.AuditLog.admin_id == actor.id,
        ).first()
        assert update_log is not None
        assert update_log.details is not None
        details = json.loads(update_log.details)
        assert details["changed"]["name"]["old"] == unique
        assert details["changed"]["name"]["new"] == unique + " Updated"

        # 3. DELETE
        category_id = category.id
        db_session.delete(category)
        db_session.commit()

        delete_log = db_session.query(models.AuditLog).filter(
            models.AuditLog.item_type == "category",
            models.AuditLog.item_id == category_id,
            models.AuditLog.action == "DELETE",
            models.AuditLog.admin_id == actor.id,
        ).first()
        assert delete_log is not None
    finally:
        audit_trail.current_actor_email.reset(ctx)

    db_session.query(models.AuditLog).filter(models.AuditLog.admin_id == actor.id).delete()
    db_session.delete(actor)
    db_session.commit()


def test_auto_audit_skips_unattributable_writes(db_session):
    """No actor in context (background jobs, seeds, migrations) => no audit
    row — AuditLog.admin_id is NOT NULL by design and an unattributable row
    is noise."""
    import uuid

    unique = f"Audit Orphan Cat {uuid.uuid4().hex[:8]}"
    category = models.Category(name=unique, is_active=True)
    db_session.add(category)
    db_session.commit()
    db_session.refresh(category)

    category.name = unique + " Updated"
    db_session.commit()

    logs = db_session.query(models.AuditLog).filter(
        models.AuditLog.item_type == "category",
        models.AuditLog.item_id == category.id,
        models.AuditLog.timestamp >= get_tbilisi_time() - timedelta(minutes=1),
    ).all()
    assert logs == [], [f"{l.action}:{l.admin_id}" for l in logs]

    db_session.delete(category)
    db_session.commit()


def test_log_rotation_and_archiving(db_session):
    """Verifies that rotate_audit_logs correctly exports logs older than 180 days
    to archives/ and purges them from the database.
    """
    from datetime import timedelta
    from main import rotate_audit_logs
    from models import get_tbilisi_time
    import os

    # Insert a stale log (181 days old) and a fresh log
    stale_time = get_tbilisi_time() - timedelta(days=181)
    stale_log = models.AuditLog(
        admin_id=1,
        action="TEST_STALE",
        item_type="system",
        item_id=0,
        timestamp=stale_time
    )
    fresh_log = models.AuditLog(
        admin_id=1,
        action="TEST_FRESH",
        item_type="system",
        item_id=0,
        timestamp=get_tbilisi_time()
    )
    db_session.add_all([stale_log, fresh_log])
    db_session.commit()

    # Run log rotation
    rotate_audit_logs(db_session)

    # Verify stale log was purged
    purged = db_session.query(models.AuditLog).filter(models.AuditLog.action == "TEST_STALE").first()
    assert purged is None

    # Verify fresh log is still in database
    active = db_session.query(models.AuditLog).filter(models.AuditLog.action == "TEST_FRESH").first()
    assert active is not None

    # Verify archive file was created in archives/
    assert os.path.exists("archives")
    archives = os.listdir("archives")
    assert len(archives) > 0

    # Cleanup fresh log and archive files
    db_session.delete(active)
    db_session.commit()
    for f in os.listdir("archives"):
        os.remove(os.path.join("archives", f))
    os.rmdir("archives")


def test_article_history_comparison_diff(client, db_session):
    """Verifies that get_article_diff correctly handles comparing two historical snapshots."""
    # 1. Create a mock article
    article = models.Article(
        title="Initial Title",
        content="This is the first version content.",
        target_department="All",
        is_draft=False
    )
    db_session.add(article)
    db_session.commit()
    db_session.refresh(article)
    
    # 2. Add two history snapshots for this article
    snap1 = models.ArticleHistory(
        article_id=article.id,
        title="Initial Title",
        content="This is the first version content.",
        updated_by=1,
        version_id=1
    )
    snap2 = models.ArticleHistory(
        article_id=article.id,
        title="Second Title",
        content="This is the second version content.",
        updated_by=1,
        version_id=2
    )
    db_session.add_all([snap1, snap2])
    db_session.commit()
    db_session.refresh(snap1)
    db_session.refresh(snap2)
    
    # 3. Call the diff endpoint comparing snap1 against current article content
    resp1 = client.get(f"/api/articles/{article.id}/history/{snap1.id}/diff")
    assert resp1.status_code == 200
    data1 = resp1.json()
    assert "html" in data1
    assert data1["version_id"] == 1
    
    # 4. Call the diff endpoint comparing snap1 against snap2 using compare_history_id
    resp2 = client.get(f"/api/articles/{article.id}/history/{snap1.id}/diff?compare_history_id={snap2.id}")
    assert resp2.status_code == 200
    data2 = resp2.json()
    assert "html" in data2
    assert data2["version_id"] == 1
    
    # Clean up
    db_session.delete(snap1)
    db_session.delete(snap2)
    db_session.delete(article)
    db_session.commit()




