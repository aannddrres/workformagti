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
from sqlalchemy.orm import Session

import models
from database import get_tbilisi_time

logger = logging.getLogger("magti")

# E-mail (JWT ``sub``) of the authenticated request actor; None outside a
# request or when the request carried no valid token. E-mail rather than id:
# the middleware decodes the JWT without a DB session, so the listener
# resolves the id on the flush connection instead.
current_actor_email: ContextVar = ContextVar("current_actor_email", default=None)

# Same request-scoped-ContextVar pattern as current_actor_email, but for the
# audit_logs hash-chain trigger's ip_address/user_agent columns (see
# migrate.py's AUDIT_CHAIN_STATEMENTS). Set by main.py's
# actor_context_middleware.
current_client_ip: ContextVar = ContextVar("current_client_ip", default=None)
current_user_agent: ContextVar = ContextVar("current_user_agent", default=None)


@event.listens_for(Session, "after_begin")
def _stamp_request_context(session, transaction, connection):
    """Re-applies the request's IP/User-Agent as Postgres GUCs at the start of
    every transaction on every session (registered on the Session *class*,
    not a specific sessionmaker instance — tests/conftest.py monkey-patches
    database.SessionLocal to a different sessionmaker object after this
    module has already imported and registered against the original, so an
    instance-scoped listener would silently never fire in tests).

    Why GUCs at all, instead of columns set here directly: audit_logs has two
    write paths (this module's Core-level insert, and ~27 ORM-level
    `models.AuditLog(...)` sites in main.py), and only a DB-side BEFORE
    INSERT trigger (migrate.py's AUDIT_CHAIN_STATEMENTS) covers both without
    touching every call site. GUCs are how a value set here reaches that
    trigger. set_config() (a function call with bind params) is used rather
    than string-formatting `SET LOCAL app.user_agent = '...'` -- a
    User-Agent is arbitrary client-controlled text, and building DDL text out
    of it directly would be a real injection surface.

    Re-runs on every new transaction (not just once per request) so a
    request that commits more than once (e.g. the two-phase login flow)
    stays stamped on its second transaction too. No-op, zero DB round-trip,
    for every non-request context: background jobs, migrations, tests, and
    requests with no authenticated actor -- exactly when both ContextVars
    are unset.
    """
    if connection.dialect.name != "postgresql":
        return
    ip = current_client_ip.get()
    ua = current_user_agent.get()
    if ip is None and ua is None:
        return
    connection.execute(
        text("SELECT set_config('app.client_ip', :ip, true), "
             "set_config('app.user_agent', :ua, true)"),
        {"ip": ip or "", "ua": ua or ""},
    )


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


def _resolve_actor_info(connection):
    """Returns (user_id, name, email) for the current request actor, or
    (None, None, None) if there is no authenticated actor."""
    email = current_actor_email.get()
    if not email:
        return None, None, None
    row = connection.execute(
        text("SELECT id, name, email FROM users WHERE lower(email) = :email"),
        {"email": email.lower()},
    ).first()
    return (row[0], row[1], row[2]) if row else (None, None, None)


def _write_audit_row(connection, *, actor_id, actor_name=None, actor_email=None,
                     action, item_type, item_id, item_name=None, details=None):
    # Core insert inside the same flush transaction: if the business change
    # rolls back, its audit row rolls back with it. Bypasses the ORM
    # before_insert classifier, so category and snapshots are set explicitly.
    connection.execute(
        models.AuditLog.__table__.insert().values(
            admin_id=actor_id,
            action=action,
            item_type=item_type,
            item_id=item_id,
            timestamp=get_tbilisi_time(),
            category=models.classify_audit_category(item_type, action),
            details=details,
            admin_name_snapshot=actor_name,
            admin_email_snapshot=actor_email,
            item_name_snapshot=item_name,
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


def _target_label(target):
    """Best human-readable label for the audited object."""
    return (getattr(target, "title", None) or getattr(target, "name", None)
            or getattr(target, "email", None))


def _make_listeners(item_type):
    def after_insert(mapper, connection, target):
        actor_id, actor_name, actor_email = _resolve_actor_info(connection)
        if actor_id is None:
            return
        _write_audit_row(connection, actor_id=actor_id,
                         actor_name=actor_name, actor_email=actor_email,
                         action="CREATE",
                         item_type=item_type, item_id=target.id,
                         item_name=_target_label(target))

    def after_update(mapper, connection, target):
        actor_id, actor_name, actor_email = _resolve_actor_info(connection)
        if actor_id is None:
            return
        changed = _diff_for(target)
        if not changed:
            # Relationship-only touches (e.g. re-assigned junction rows) mark
            # the row dirty without a column diff — nothing worth a row.
            return
        _write_audit_row(
            connection, actor_id=actor_id,
            actor_name=actor_name, actor_email=actor_email,
            action="UPDATE",
            item_type=item_type, item_id=target.id,
            item_name=_target_label(target),
            details=json.dumps({"changed": changed}, ensure_ascii=False, default=str),
        )

    def after_delete(mapper, connection, target):
        actor_id, actor_name, actor_email = _resolve_actor_info(connection)
        if actor_id is None:
            return
        label = _target_label(target)
        details = json.dumps({"deleted": _clip(label)}, ensure_ascii=False) if label else None
        _write_audit_row(connection, actor_id=actor_id,
                         actor_name=actor_name, actor_email=actor_email,
                         action="DELETE",
                         item_type=item_type, item_id=target.id,
                         item_name=label, details=details)

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
