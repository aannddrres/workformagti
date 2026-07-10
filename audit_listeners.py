from contextvars import ContextVar
from typing import Optional
import json
from sqlalchemy import event
import models

# Global ContextVar to track the user performing database changes
current_actor_id: ContextVar[Optional[int]] = ContextVar("current_actor_id", default=None)

def _get_current_actor_id() -> int:
    return current_actor_id.get() or 1

def _log_change(connection, target, action: str):
    from sqlalchemy import select
    actor_id = current_actor_id.get()
    if actor_id is None:
        # Fallback: find any valid user ID in the database to satisfy the foreign key constraint
        actor_id = connection.scalar(select(models.User.id).limit(1)) or 1

    item_type = target.__class__.__name__.lower()
    item_id = getattr(target, "id", 0)
    
    # Avoid recursion/logging AuditLog changes
    if item_type == "auditlog":
        return

    details = None
    if action == "UPDATE":
        from sqlalchemy.orm.attributes import get_history
        changed = {}
        state = target._sa_instance_state
        for attr in state.mapper.column_attrs:
            history = get_history(target, attr.key)
            if history.has_changes():
                if attr.key in ("hashed_password", "password_hash"):
                    changed[attr.key] = {"changed": True}
                else:
                    def serialize_val(val):
                        if isinstance(val, (dict, list)):
                            return val
                        return str(val) if val is not None else None

                    changed[attr.key] = {
                        "old": serialize_val(history.deleted[0]) if history.deleted else None,
                        "new": serialize_val(history.added[0]) if history.added else None
                    }
        if changed:
            details = json.dumps({"changed": changed}, ensure_ascii=False)

    from models import classify_audit_category, get_tbilisi_time
    category = classify_audit_category(item_type, action)

    # Insert log row using the provided Connection
    connection.execute(
        models.AuditLog.__table__.insert().values(
            admin_id=actor_id,
            action=action,
            item_type=item_type,
            item_id=item_id,
            timestamp=get_tbilisi_time(),
            category=category,
            details=details
        )
    )

    try:
        from webhook_dispatcher import notify_webhook_listeners
        log_payload = {
            "action": action,
            "item_type": item_type,
            "item_id": item_id,
            "actor_id": actor_id,
            "category": category,
            "details": details
        }
        notify_webhook_listeners(action, log_payload)
    except Exception:
        pass

def register_listeners():
    classes = [models.Article, models.Category, models.News, models.User]
    for cls in classes:
        @event.listens_for(cls, "after_insert")
        def after_insert_listener(mapper, connection, target):
            _log_change(connection, target, "CREATE")

        @event.listens_for(cls, "after_update")
        def after_update_listener(mapper, connection, target):
            _log_change(connection, target, "UPDATE")

        @event.listens_for(cls, "after_delete")
        def after_delete_listener(mapper, connection, target):
            _log_change(connection, target, "DELETE")


def seed_default_translations(db_session):
    defaults = {
        "LOGIN": "სისტემაში შესვლა",
        "LOGIN_SSO": "SSO ავტორიზაცია",
        "LOGOUT": "სისტემიდან გამოსვლა",
        "VIEW": "ნახვა (გახსნა)",
        "CREATE": "შექმნა",
        "UPDATE": "რედაქტირება",
        "DELETE": "წაშლა",
        "READ_ARTICLE": "სტატიის წაკითხვა",
        "MARK_READ": "წაკითხულად მონიშვნა (Compliance)",
        "LOGIN_FAILED": "ავტორიზაციის ჩავარდნა",
        "UPDATE_PERMISSIONS": "უფლებების შეცვლა"
    }
    existing = db_session.query(models.AuditActionTranslation).count()
    if existing == 0:
        for action, label in defaults.items():
            trans = models.AuditActionTranslation(action=action, label_ka=label)
            db_session.add(trans)
        db_session.commit()
