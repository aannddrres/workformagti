import os
import sys
import pytest
from fastapi.testclient import TestClient

# Ensure the project root is in the python path for absolute imports
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import security
# Mock require_content_creator before importing app.main to prevent AttributeError
security.require_content_creator = lambda perm: security.get_current_admin_user
security.PERM_ARTICLES_CREATE = "articles.create"
security.PERM_NEWS_CREATE = "news.create"

from main import app as monolith_app
from app.main import create_app
import models
from database import get_db

modular_app = create_app()

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
            name="სისტემის ადმინისტრატორი",
            role="admin",
            department="Administration",
            is_active=True
        )
        db_session.add(admin)
        db_session.commit()
        db_session.refresh(admin)
    return admin

@pytest.fixture(params=["monolith", "modular"])
def client(request, admin_user):
    if request.param == "monolith":
        active_app = monolith_app
    else:
        active_app = modular_app
    
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

@pytest.mark.parametrize("app_instance", [monolith_app, modular_app])
def test_auth_missing(app_instance):
    """Auth Missing Test: Verifies that sending requests to secure admin

    endpoints without auth headers results in 401/403 errors.
    """
    app_instance.dependency_overrides.clear()
    
    with TestClient(app_instance) as tc:
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

    monolith_app.dependency_overrides.clear()


