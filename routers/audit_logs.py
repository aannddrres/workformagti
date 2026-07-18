"""Audit log routes: list, CSV export, tamper-evident hash-chain verify and
health check — Phase 4 of the main.py monolith split."""
import csv
import io
import json
import logging
from datetime import datetime, timedelta
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Response
from fastapi.responses import StreamingResponse
from sqlalchemy import and_, desc, func, or_
from sqlalchemy.orm import Session

import models
import schemas
import security
from database import get_db
from db_helpers import log_audit

logger = logging.getLogger("magti")

router = APIRouter(tags=["audit-logs"])


def _parse_audit_date(value: str, end_of_day: bool = False) -> Optional[datetime]:
    """Convert a user-supplied date/datetime string to naive Tbilisi-local time.

    AuditLog.timestamp is stored as naive Tbilisi-local time (UTC+4) —
    database.get_tbilisi_time(), NOT datetime.utcnow() despite what this
    docstring used to claim. Comparing a converted-to-UTC value against that
    column silently skewed every date-range filter by 4 hours. If we compared
    a timezone-aware value directly we'd hit "can't compare offset-naive and
    offset-aware datetimes" on PostgreSQL and a silent miscompare on SQLite,
    so: parse the input, normalise it to Tbilisi-local via astimezone(), then
    strip tzinfo.

    ``value`` may be a bare date (YYYY-MM-DD) or a full ISO-8601 datetime with
    optional offset/Z suffix. A bare date is interpreted as midnight Tbilisi-local.
    """
    from datetime import timezone

    if not value:
        return None
    text = value.strip()
    # Accept the JS ".toISOString()" trailing Z.
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        # Full ISO-8601 with optional offset.
        parsed = datetime.fromisoformat(text)
    except ValueError:
        # Fall back to a bare date — frontend often sends YYYY-MM-DD.
        try:
            parsed = datetime.strptime(text.split("T")[0], "%Y-%m-%d")
        except ValueError:
            return None
    if end_of_day and parsed.hour == 0 and parsed.minute == 0 and parsed.second == 0:
        # End-of-day inclusive: roll forward and apply a < filter at the call site.
        parsed = parsed + timedelta(days=1)
    if parsed.tzinfo is not None:
        # SAFE conversion: shift to Tbilisi-local (UTC+4), then drop tzinfo so
        # the comparison matches the naive-local values get_tbilisi_time() writes.
        parsed = parsed.astimezone(timezone(timedelta(hours=4))).replace(tzinfo=None)
    return parsed


_DELETED_USER_LABEL = "წაშლილი მომხმარებელი"


def _audit_item_name(log, article_title, news_title, video_title, category_name):
    """Human-readable target name per item_type (None for system/file/etc.)."""
    return {
        "article": article_title,
        "news": news_title,
        "video": video_title,
        "category": category_name,
    }.get((log.item_type or "").lower())


def _log_audit_trail_access(db: Session, actor: models.User, action: str, filters: dict) -> None:
    """Meta-audit: records that `actor` looked at (or exported) the audit
    trail, and with which filters — same shape as every other AuditLog call
    site in this file (db.add + commit), classified SECURITY via
    models._AUDIT_SECURITY_ACTIONS. item_id is self-referential (no natural
    target item), matching the LOGIN row's own convention.

    Best-effort: a failure here must never block the actual read the caller
    already computed, so errors are logged and swallowed, not raised.
    """
    try:
        details = json.dumps({k: v for k, v in filters.items() if v not in (None, "")}, ensure_ascii=False)
        log_audit(db, admin_id=actor.id, action=action, item_type="audit_log", item_id=actor.id, details=details)
        db.commit()
    except Exception:
        db.rollback()
        logger.exception("Failed to write meta-audit row for %s by admin_id=%s", action, actor.id)


def _audit_scope_department(current_user: models.User) -> Optional[str]:
    """None = unrestricted (system_admin, content_admin). A manager is pinned
    to their own department/group string — same idiom as get_team_stats'
    existing 'manager sees only their own department' branch (main.py:4380)."""
    if current_user.role == security.ROLE_MANAGER:
        return current_user.department
    return None


def _build_audit_query(db, start_date, end_date, user_id, user_name, action, category,
                       *, log_prefix: str = "Audit log", scope_department: Optional[str] = None):
    """Shared join/filter builder for the audit list and its CSV export.

    The User join is OUTER on purpose: deleting an account must not erase its
    audit history from the view (rows fall back to a 'deleted user' label).
    Item-name joins cover articles, news, videos and categories.

    scope_department (manager-only): filters to rows whose actor's CURRENT
    department matches exactly. Because the User join is OUTER, a deleted
    actor's row has User.department IS NULL, and NULL == 'X' is never true in
    SQL — such rows are correctly excluded (fail closed), no extra code needed.
    """
    query = (
        db.query(
            models.AuditLog,
            models.User.name.label("admin_name"),
            models.Article.title.label("article_title"),
            models.News.title.label("news_title"),
            models.VideoInstruction.title.label("video_title"),
            models.Category.name.label("category_name"),
        )
        .outerjoin(models.User, models.AuditLog.admin_id == models.User.id)
        .outerjoin(models.Article, and_(
            func.lower(models.AuditLog.item_type) == "article",
            models.AuditLog.item_id == models.Article.id,
        ))
        .outerjoin(models.News, and_(
            func.lower(models.AuditLog.item_type) == "news",
            models.AuditLog.item_id == models.News.id,
        ))
        .outerjoin(models.VideoInstruction, and_(
            func.lower(models.AuditLog.item_type) == "video",
            models.AuditLog.item_id == models.VideoInstruction.id,
        ))
        .outerjoin(models.Category, and_(
            func.lower(models.AuditLog.item_type) == "category",
            models.AuditLog.item_id == models.Category.id,
        ))
    )
    if start_date:
        start_dt = _parse_audit_date(start_date)
        if start_dt is not None:
            query = query.filter(models.AuditLog.timestamp >= start_dt)
        else:
            logger.warning("%s start_date parsing error: invalid format '%s'", log_prefix, start_date)
    if end_date:
        end_dt = _parse_audit_date(end_date, end_of_day=True)
        if end_dt is not None:
            query = query.filter(models.AuditLog.timestamp < end_dt)
        else:
            logger.warning("%s end_date parsing error: invalid format '%s'", log_prefix, end_date)
    if user_id:
        query = query.filter(models.AuditLog.admin_id == user_id)
    if user_name:
        query = query.filter(models.User.name.ilike(f"%{user_name}%"))
    if scope_department:
        query = query.filter(models.User.department == scope_department)
    if action:
        if action == "LOGIN":
            # LOGIN filter aggregates both password-based logins and SSO logins.
            query = query.filter(models.AuditLog.action.in_(["LOGIN", "LOGIN_SSO"]))
        else:
            query = query.filter(models.AuditLog.action == action)
    if category:
        query = query.filter(models.AuditLog.category == category.upper())
    return query.order_by(desc(models.AuditLog.timestamp))


@router.get("/api/audit-logs", response_model=list[schemas.AuditLogResponse])
def get_audit_logs(
    response: Response,
    start_date: Optional[str] = None,
    end_date: Optional[str] = None,
    user_id: Optional[int] = None,
    user_name: Optional[str] = None,
    action: Optional[str] = None,
    category: Optional[str] = None,
    q: Optional[str] = None,
    limit: int = 50,
    offset: int = 0,
    current_user: models.User = Depends(security.require_permission(security.PERM_SYSTEM_AUDIT)),
    db: Session = Depends(get_db)
):
    """Retrieves system audit logs with optional filters (date, user, action type).

    Access: system administrators and content_admin see everything. A manager
    holds the same system:audit permission but is hard-pinned to their own
    department/group (see _audit_scope_department) — "own group only, nothing
    more" per the access-control requirement this scoping was built for.
    """
    limit = max(1, min(limit, 200))  # was unbounded; a runaway limit shouldn't be able to hurt the DB
    scope_department = _audit_scope_department(current_user)
    query = _build_audit_query(db, start_date, end_date, user_id, user_name, action, category,
                                scope_department=scope_department)
    if q:
        like = f"%{q}%"
        query = query.filter(or_(
            models.AuditLog.action.ilike(like),
            models.AuditLog.item_type.ilike(like),
            models.AuditLog.item_name_snapshot.ilike(like),
            models.AuditLog.details.ilike(like),
        ))
    # order_by(None): the base query already carries an ORDER BY for listing,
    # which Postgres would otherwise sort for pointlessly before counting.
    response.headers["X-Total-Count"] = str(query.order_by(None).count())
    rows = query.offset(offset).limit(limit).all()
    result = [
        {
            "id": log.id,
            "admin_id": log.admin_id,
            "admin_name": log.admin_name_snapshot or admin_name or _DELETED_USER_LABEL,
            "action": log.action,
            "item_type": log.item_type,
            "item_id": log.item_id,
            "item_name": log.item_name_snapshot or _audit_item_name(log, article_title, news_title, video_title, category_name),
            "timestamp": log.timestamp,
            "category": log.category,
            "details": log.details,
            "prev_hash": log.prev_hash,
            "row_hash": log.row_hash,
            "ip_address": log.ip_address,
            "user_agent": log.user_agent,
        }
        for log, admin_name, article_title, news_title, video_title, category_name in rows
    ]

    # Meta-audit: record who browsed the audit trail and with which filters —
    # written AFTER `result` is built so this call's own response can never
    # include its own row. Not fired by chain-health (passive, on every page
    # mount) — this is specifically "a person chose to look at this data".
    _log_audit_trail_access(db, current_user, "VIEW_AUDIT_LOG", {
        "start_date": start_date, "end_date": end_date, "user_id": user_id,
        "user_name": user_name, "action": action, "category": category,
        "q": q, "offset": offset, "limit": limit, "result_count": len(result),
        "scope_department": scope_department,
    })
    return result


@router.get("/api/audit-logs/export")
def export_audit_logs(
    start_date: Optional[str] = None,
    end_date: Optional[str] = None,
    user_id: Optional[int] = None,
    user_name: Optional[str] = None,
    action: Optional[str] = None,
    category: Optional[str] = None,
    q: Optional[str] = None,
    current_user: models.User = Depends(security.require_permission(
        security.PERM_SYSTEM_AUDIT, exclude_roles=frozenset({security.ROLE_MANAGER}))),
    db: Session = Depends(get_db),
):
    """Memory-safe CSV export of audit logs.

    Streams the response via a generator + yield_per(1000) instead of building
    the full CSV in memory first, so a large unfiltered export can't bloat
    server memory or block the worker for the whole query duration.

    Access: system administrators and content_admin. A manager holds the same
    system:audit permission (for the scoped list view) but is explicitly
    blocked here — view-only for managers, no bulk egress of their group's data.
    """
    query = _build_audit_query(
        db, start_date, end_date, user_id, user_name, action, category,
        log_prefix="Audit log export",
    )
    if q:
        like = f"%{q}%"
        query = query.filter(or_(
            models.AuditLog.action.ilike(like),
            models.AuditLog.item_type.ilike(like),
            models.AuditLog.item_name_snapshot.ilike(like),
            models.AuditLog.details.ilike(like),
        ))

    # Written before streaming starts, not inside generate_csv(): the CSV
    # export is a single higher-risk egress action (data leaves the system),
    # so it's logged once per request regardless of how many rows stream out
    # — a row count would need a second full COUNT query just to report it.
    _log_audit_trail_access(db, current_user, "EXPORT_AUDIT_LOG", {
        "start_date": start_date, "end_date": end_date, "user_id": user_id,
        "user_name": user_name, "action": action, "category": category, "q": q,
    })

    def generate_csv():
        output = io.StringIO()
        writer = csv.writer(output)
        writer.writerow(["ID", "დრო", "ვინ", "ქმედება", "ობიექტი", "დეტალები"])
        yield output.getvalue()
        output.seek(0)
        output.truncate(0)

        for log, admin_name, article_title, news_title, video_title, category_name in query.yield_per(1000):
            resolved_admin = log.admin_name_snapshot or admin_name or _DELETED_USER_LABEL
            item_name = log.item_name_snapshot or _audit_item_name(log, article_title, news_title, video_title, category_name)
            writer.writerow([
                log.id,
                log.timestamp.strftime("%Y-%m-%d %H:%M:%S") if log.timestamp else "",
                resolved_admin,
                log.action,
                item_name or log.item_type,
                log.details or "",
            ])
            yield output.getvalue()
            output.seek(0)
            output.truncate(0)

    return StreamingResponse(
        generate_csv(),
        media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=audit_logs.csv"},
    )


@router.get("/api/audit-logs/{log_id}/verify", response_model=schemas.AuditLogVerifyResponse)
def verify_audit_log(
    log_id: int,
    current_user: models.User = Depends(security.require_permission(
        security.PERM_SYSTEM_AUDIT, exclude_roles=frozenset({security.ROLE_MANAGER}))),
    db: Session = Depends(get_db),
):
    """Re-derives a row's hash and its link to its predecessor and compares
    against what's stored, to prove (or disprove) the row hasn't been
    altered since the migrate.py trigger wrote it.

    Postgres-only: the hash chain only exists there (see migrate.py's
    AUDIT_CHAIN_STATEMENTS). GET, no side effects — matches the existing
    /api/articles/{id}/history/{id}/diff convention.

    Access: system administrators and content_admin only — a manager holds
    system:audit (for the scoped list view) but not this tool. The chain is
    one single global sequence with no per-department sharding, so there's
    no meaningful "my department's slice" of it to verify.
    """
    from sqlalchemy import text

    if db.bind.dialect.name != "postgresql":
        raise HTTPException(
            status_code=501,
            detail="მთლიანობის შემოწმება ხელმისაწვდომია მხოლოდ PostgreSQL-ზე",
        )
    row = db.execute(text("""
        SELECT al.row_hash, al.prev_hash,
               encode(digest(audit_logs_canonical_string(al), 'sha256'), 'hex') AS recomputed_hash,
               prev.row_hash AS actual_prev_row_hash
        FROM audit_logs al
        LEFT JOIN audit_logs prev ON prev.id = (
            SELECT id FROM audit_logs WHERE id < al.id AND row_hash IS NOT NULL ORDER BY id DESC LIMIT 1
        )
        WHERE al.id = :id
    """), {"id": log_id}).mappings().first()
    if not row:
        raise HTTPException(status_code=404, detail="ჩანაწერი ვერ მოიძებნა")
    if row["row_hash"] is None:
        return {"status": "unchained"}
    hash_match = row["row_hash"] == row["recomputed_hash"]
    # Re-derives the expected predecessor live rather than trusting the
    # stored prev_hash blindly, so an altered/deleted predecessor is caught
    # too, not just a directly-altered row.
    chain_match = row["prev_hash"] == row["actual_prev_row_hash"]
    return {
        "status": "ok" if (hash_match and chain_match) else "tampered",
        "hash_match": hash_match,
        "chain_match": chain_match,
        "row_hash": row["row_hash"],
        "recomputed_hash": row["recomputed_hash"],
    }


@router.get("/api/audit-logs/chain-health", response_model=schemas.AuditChainHealthResponse)
def audit_chain_health(
    n: int = 100,
    current_user: models.User = Depends(security.require_permission(
        security.PERM_SYSTEM_AUDIT, exclude_roles=frozenset({security.ROLE_MANAGER}))),
    db: Session = Depends(get_db),
):
    """Batch-validates the last N chained rows in one pass, so the dashboard
    can show chain health on mount instead of forcing per-row verify clicks.

    Read-only — never touches the trigger's advisory lock, so it can't
    contend with writers. On SQLite this returns 200 "unavailable" rather
    than the per-row endpoint's 501: that one is an explicit user click,
    this one fires passively on every dashboard mount, and a guaranteed
    console error on every dev load would be noise.

    Access: system administrators and content_admin only — same rationale as
    verify_audit_log (one global chain, no per-department slice to check;
    a filtered window would also compare non-adjacent rows and manufacture
    false "tampered" results).
    """
    from sqlalchemy import text

    n = max(1, min(n, 500))
    if db.bind.dialect.name != "postgresql":
        return {"status": "unavailable", "window": n}

    # JOIN back to the base table rather than selecting from the CTE:
    # audit_logs_canonical_string(r audit_logs) takes the table's composite
    # type, and a CTE row is an anonymous record that won't match it.
    window_rows = db.execute(text("""
        WITH recent AS (
          SELECT id FROM audit_logs WHERE row_hash IS NOT NULL ORDER BY id DESC LIMIT :n
        )
        SELECT a.id, a.prev_hash, a.row_hash,
               encode(digest(audit_logs_canonical_string(a), 'sha256'), 'hex') AS recomputed
        FROM audit_logs a JOIN recent r ON r.id = a.id
        ORDER BY a.id
    """), {"n": n}).mappings().all()
    unchained_total = db.execute(
        text("SELECT count(*) FROM audit_logs WHERE row_hash IS NULL")
    ).scalar() or 0

    # Boundary predecessor: the chained row just before the window, so the
    # window's oldest row gets a real link check instead of a skipped one
    # (this also catches a fake "second genesis" inside the window).
    expected_prev = None
    if window_rows:
        expected_prev = db.execute(text("""
            SELECT row_hash FROM audit_logs
            WHERE row_hash IS NOT NULL AND id < :min_id
            ORDER BY id DESC LIMIT 1
        """), {"min_id": window_rows[0]["id"]}).scalar()

    hash_mismatches = link_breaks = 0
    bad_ids: list[int] = []
    for r in window_rows:
        bad = False
        if r["row_hash"] != r["recomputed"]:
            hash_mismatches += 1
            bad = True
        if r["prev_hash"] != expected_prev:
            link_breaks += 1
            bad = True
        if bad and len(bad_ids) < 10:
            bad_ids.append(r["id"])
        expected_prev = r["row_hash"]

    return {
        "status": "ok" if not (hash_mismatches or link_breaks) else "tampered",
        "checked": len(window_rows),
        "window": n,
        "hash_mismatches": hash_mismatches,
        "link_breaks": link_breaks,
        "bad_ids": bad_ids,
        "unchained_total": unchained_total,
    }
