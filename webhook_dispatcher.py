import httpx
import logging
import threading
from typing import Dict, Any

logger = logging.getLogger("uvicorn.error")

def dispatch_webhook(url: str, payload: Dict[str, Any]):
    """Dispatches a webhook payload in a non-blocking background thread."""
    def send():
        try:
            with httpx.Client(timeout=5.0) as client:
                res = client.post(url, json=payload)
                if res.status_code >= 400:
                    logger.warning(f"Webhook dispatch to {url} failed with status {res.status_code}")
        except Exception as e:
            logger.error(f"Error dispatching webhook to {url}: {e}")

    thread = threading.Thread(target=send, daemon=True)
    thread.start()

def notify_webhook_listeners(action: str, log_data: Dict[str, Any]):
    """Retrieves active webhooks configured for this action and dispatches alerts."""
    from database import SessionLocal
    import models

    db = SessionLocal()
    try:
        webhooks = db.query(models.WebhookConfig).filter(models.WebhookConfig.is_active == True).all()
        for wh in webhooks:
            # Clean trigger actions list
            actions = [a.strip().upper() for a in (wh.trigger_actions or "").split(",") if a.strip()]
            # Trigger if trigger_actions list is empty (triggers on all) or contains the action
            if not actions or action.upper() in actions:
                dispatch_webhook(wh.url, log_data)
    except Exception as e:
        logger.error(f"Error checking webhooks: {e}")
    finally:
        db.close()
