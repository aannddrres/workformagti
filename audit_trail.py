"""Automatic audit trail: ORM-level CREATE/UPDATE/DELETE rows with deep field
diffs for the audited content models.

Replaces the never-registered listener chain removed in the 2026-07-13
stabilization pass (audit_listeners.py had zero register_listeners() callers,
so its rows exist only as fossils in old dev data). This module is minimal —
no webhooks — and main.py actually calls register_listeners() at import time.

Actor attribution flows through a ContextVar set by main.py's
actor_context_middleware from the request's bearer token — NOT from
security.get_current_user: sync dependencies run in a threadpool with a
*copied* context, so a ContextVar set there never propagates back out.

No actor (background jobs, seeds, migrations, tests without a real token)
=> no audit row: an unattributable row is noise, and AuditLog.admin_id is
NOT NULL by design.
"""
from __future__ import annotations

import json
import logging
from contextvars import ContextVar

from sqlalchemy import event, text
from sqlalchemy import inspect as sa_inspect

import models
from database import get_tbilisi_time

logger = logging.getLogger("magti")

# E-mail (JWT ``sub``) of the authenticated request actor; None outside a
# request or when the request carried no valid token. E-mail rather than id:
# the middleware decodes the JWT without a DB session, so the listener
# resolves the id on the flush connection instead.
current_actor_email: ContextVar = ContextVar("current_actor_email", default=None)

AUDITED_MODELS = {
    models.Article: "article",
    models.News: "news",
    models.Category: "category",
    models.VideoInstruction: "video",
    models.User: "user",
    models.RequiredReading: "required_reading",
}

# Never expose these values in diffs — presence of a change is enough.
_SECRET_COLUMNS = {"hashed_password"}
# Bookkeeping noise: changes on every edit, duplicates the row's timestamp.
_SKIPPED_COLUMNS = {"updated_at"}
_MAX_VALUE_LEN = 200

_registered = False


def _clip(value):
    """JSON-safe, size-bounded representation of a diffed value."""
    if isinstance(value, str):
        return value[:_MAX_VALUE_LEN] + "…" if len(value) > _MAX_VALUE_LEN else value
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return str(value)


def _resolve_actor_id(connection):
    email = current_actor_email.get()
    if not email:
        return None
    row = connection.execute(
        text("SELECT id FROM users WHERE lower(email) = :email"),
        {"email": email.lower()},
    ).first()
    return row[0] if row else None


def _write_audit_row(connection, *, actor_id, action, item_type, item_id, details=None):
    # Core insert inside the same flush transaction: if the business change
    # rolls back, its audit row rolls back with it. Bypasses the ORM
    # before_insert classifier, so category is set explicitly here.
    connection.execute(
        models.AuditLog.__table__.insert().values(
            admin_id=actor_id,
            action=action,
            item_type=item_type,
            item_id=item_id,
            timestamp=get_tbilisi_time(),
            category=models.classify_audit_category(item_type, action),
            details=details,
        )
    )


def _diff_for(target) -> dict:
    """{column: {"old": ..., "new": ...}} for every changed, loaded column."""
    state = sa_inspect(target)
    changed = {}
    for attr in state.mapper.column_attrs:
        key = attr.key
        if key in _SKIPPED_COLUMNS:
            continue
        hist = state.attrs[key].history
        if not hist.has_changes():
            continue
        old = hist.deleted[0] if hist.deleted else None
        new = hist.added[0] if hist.added else None
        if old == new:
            continue
        if key in _SECRET_COLUMNS:
            changed[key] = {"old": "***", "new": "***"}
        else:
            changed[key] = {"old": _clip(old), "new": _clip(new)}
    return changed


def _make_listeners(item_type):
    def after_insert(mapper, connection, target):
        actor_id = _resolve_actor_id(connection)
        if actor_id is None:
            return
        _write_audit_row(connection, actor_id=actor_id, action="CREATE",
                         item_type=item_type, item_id=target.id)

    def after_update(mapper, connection, target):
        actor_id = _resolve_actor_id(connection)
        if actor_id is None:
            return
        changed = _diff_for(target)
        if not changed:
            # Relationship-only touches (e.g. re-assigned junction rows) mark
            # the row dirty without a column diff — nothing worth a row.
            return
        _write_audit_row(
            connection, actor_id=actor_id, action="UPDATE",
            item_type=item_type, item_id=target.id,
            details=json.dumps({"changed": changed}, ensure_ascii=False, default=str),
        )

    def after_delete(mapper, connection, target):
        actor_id = _resolve_actor_id(connection)
        if actor_id is None:
            return
        label = (getattr(target, "title", None) or getattr(target, "name", None)
                 or getattr(target, "email", None))
        details = json.dumps({"deleted": _clip(label)}, ensure_ascii=False) if label else None
        _write_audit_row(connection, actor_id=actor_id, action="DELETE",
                         item_type=item_type, item_id=target.id, details=details)

    return after_insert, after_update, after_delete


def register_listeners() -> None:
    """Attach the listeners once — idempotent across workers/import paths."""
    global _registered
    if _registered:
        return
    _registered = True
    for model, item_type in AUDITED_MODELS.items():
        ins, upd, dele = _make_listeners(item_type)
        if model is not models.User:
            # User creation keeps its explicit, SECURITY-categorised
            # CREATE_USER row (richer context than a generic CREATE).
            event.listen(model, "after_insert", ins)
        event.listen(model, "after_update", upd)
        event.listen(model, "after_delete", dele)
    logger.info("audit_trail: auto-audit listeners registered for %d models", len(AUDITED_MODELS))
