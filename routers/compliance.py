"""Compliance / required-reading routes: the mandatory-reading assignment
CRUD, the per-user reading list + progress widget, and the mark-read
acknowledgement — Phase 6 of the main.py monolith split.

Two clusters in the original main.py (my-readings/my-progress/mark-read/
create around line 1350, by-item/update/delete around line 5121) are
gathered into this one file, per the roadmap's non-contiguous-domain
procedure.
"""
import logging
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

import models
import schemas
import security
from compliance_utils import _dept_matches, _split_dept_group
from database import get_db, get_tbilisi_time
from db_helpers import get_or_404, log_audit, resolve_item_title
from routers.articles import _check_quiz_gate, _upsert_read_receipt
from routers.stats import _MANAGEMENT_ROLES, compute_compliance
from state import _safe_publish

router = APIRouter(tags=["compliance"])
logger = logging.getLogger("magti")


@router.get("/api/compliance/my-readings", response_model=list[schemas.MyReadingResponse])
def get_my_readings(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves required compliance readings assigned to the current user.

    Computes reading statuses ('read', 'unread', or 'overdue') and fetches
    details of associated items (articles, news, videos) in batched queries —
    three IN(...) lookups in total, not one query per reading. This is the
    hot path behind the notifications popover, so any N+1 here directly
    shows up as a slow load on the bell icon.
    """
    # Management roles manage the system rather than consume operator-level
    # training content - they shouldn't see required-reading items at all here.
    if current_user.role in _MANAGEMENT_ROLES:
        return []

    _dept_prefix = _split_dept_group(current_user.department)[0]
    readings = db.query(models.RequiredReading).filter(
        models.RequiredReading.target_department.in_([current_user.department, _dept_prefix, "All"])
    ).all()

    if not readings:
        return []

    # Item 15 (perf): batch by item_type and run one IN(...) query per type.
    article_ids: list[int] = []
    news_ids: list[int] = []
    video_ids: list[int] = []
    for r in readings:
        if r.item_type == "article":
            article_ids.append(r.item_id)
        elif r.item_type == "news":
            news_ids.append(r.item_id)
        elif r.item_type == "video":
            video_ids.append(r.item_id)

    item_details: dict[tuple[str, int], dict] = {}
    if article_ids:
        for aid, title in db.query(models.Article.id, models.Article.title).filter(
            models.Article.id.in_(set(article_ids))
        ):
            item_details[("article", aid)] = {"title": title, "content": ""}
    if news_ids:
        for nid, title in db.query(models.News.id, models.News.title).filter(
            models.News.id.in_(set(news_ids))
        ):
            item_details[("news", nid)] = {"title": title, "content": ""}
    if video_ids:
        for vid, title, url in db.query(
            models.VideoInstruction.id,
            models.VideoInstruction.title,
            models.VideoInstruction.video_url,
        ).filter(models.VideoInstruction.id.in_(set(video_ids))):
            item_details[("video", vid)] = {"title": title, "content": url}

    reading_ids = [r.id for r in readings]
    statuses = db.query(models.ReadStatus).filter(
        models.ReadStatus.user_id == current_user.id,
        models.ReadStatus.required_reading_id.in_(reading_ids),
    ).all()
    status_map = {s.required_reading_id: s for s in statuses}

    now = get_tbilisi_time()
    results = []
    for r in readings:
        stat = status_map.get(r.id)
        current_status = stat.status if stat else "unread"
        read_at = stat.read_at if stat else None
        is_overdue = current_status == "unread" and r.due_date < now
        if is_overdue:
            current_status = "overdue"

        details = item_details.get((r.item_type, r.item_id))
        results.append({
            "reading": r,
            "status": current_status,
            "read_at": read_at,
            "is_overdue": is_overdue,
            "item_title": details["title"] if details else f"Item #{r.item_id}",
            "item_content": details["content"] if details else "Content not available.",
        })
    return results


@router.get("/api/compliance/my-progress")
def get_my_progress(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Lightweight gamified reading-progress summary for the current user.

    Delegates to compute_compliance() — the single source of truth for the
    required/read pairing (see that function's docstring) — scoped to just
    this user via scope_user_ids, so it stays two GROUP BY aggregate queries
    total, never hydrating RequiredReading/ReadStatus rows individually.

    Access: any authenticated user. Unlike /api/statistics/*, this is
    intentionally not admin-gated — it's the dashboard widget for operators.
    """
    records = compute_compliance(db, scope_user_ids=[current_user.id])
    if not records:
        return {"total_mandatory": 0, "read_completed": 0, "pending": 0, "percentage": 0}

    record = records[0]
    total = record["required_count"]
    completed = record["read_count"]
    return {
        "total_mandatory": total,
        "read_completed": completed,
        "pending": total - completed,
        "percentage": record["percentage"],
    }


@router.post("/api/compliance/mark-read/{reading_id}", response_model=schemas.ReadStatusResponse)
def mark_read(
    reading_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Acknowledge a required reading assignment, marking it as read and logging the event.

    Access: Authenticated users (any active role).

    Args:
        reading_id: ID of the RequiredReading to mark as read.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        The updated or created ReadStatus database row.

    Raises:
        HTTPException: 404 Not Found if the required reading assignment does not exist.
    """
    reading = get_or_404(db, models.RequiredReading, reading_id, "სავალდებულო მასალა ვერ მოიძებნა")
    if not _dept_matches(current_user.department, [reading.target_department]):
        raise HTTPException(status_code=403, detail="ეს მასალა თქვენს დეპარტამენტს არ ეხება")

    reading_article = None
    if reading.item_type == "article":
        reading_article = db.query(models.Article).filter(models.Article.id == reading.item_id).first()
        if reading_article:
            _check_quiz_gate(db, reading_article, current_user)

    stat = db.query(models.ReadStatus).filter(models.ReadStatus.user_id == current_user.id, models.ReadStatus.required_reading_id == reading_id).first()
    if not stat:
        stat = models.ReadStatus(user_id=current_user.id, required_reading_id=reading_id)
        db.add(stat)
    stat.status = "read"
    stat.read_at = get_tbilisi_time()
    stat.operator_department_snapshot = current_user.department

    # Audit trail: compliance acknowledgements are recorded (actor is the reader)
    log_audit(db, admin_id=current_user.id, action="MARK_READ", item_type="required_reading", item_id=reading_id)
    db.commit()
    db.refresh(stat)

    # Receipt bridge: a mandatory-reading acknowledgment of an article is also
    # a versioned read receipt — keeps "who read which version" complete without
    # asking the operator to confirm twice. Runs after the commit above so the
    # helper's rollback-on-retry can't discard the ReadStatus row.
    if reading.item_type == "article" and reading_article:
        _upsert_read_receipt(db, reading_article, current_user)

    return stat


def auto_generate_notifications_for_mandatory(db_reading, current_admin_id, db):
    """JIT-create per-user Message rows for everyone affected by a new required reading.

    Item 25: when an article (or news/video) is published as mandatory for a
    department, every active user in that department gets:
      • one Message row in their inbox (drives the unread envelope badge)
      • one broadcast SSE event of type ``required_reading`` (drives the toast +
        list refresh on the active session)

    Implementation notes:
      • Uses ``bulk_insert_mappings`` so creating 200 messages is one INSERT,
        not 200. The previous loop called ``db.flush()`` per row which forced
        a round-trip per user — quadratic on department size.
      • Publishes a SINGLE broadcast event with the department code; the SSE
        endpoint filters per-connection so users only see what they're allowed
        to see. The old per-user message events still fire so unread counts
        keep updating.
    """
    item_title = resolve_item_title(db, db_reading.item_type, db_reading.item_id) or f"მასალა #{db_reading.item_id}"

    # Find all active users in the target department (excluding the admin).
    # Prefix-aware via _dept_matches (org is ~600 users — one query, then an
    # in-memory filter, no N+1) so a "ტექნიკური"-targeted reading also
    # notifies users in "ტექნიკური — ჯგუფი 03", matching what their own
    # "my readings" list already shows them.
    if db_reading.target_department == "All":
        active_users = db.query(models.User.id).filter(models.User.is_active == True).all()
        target_user_ids = [uid for (uid,) in active_users if uid != current_admin_id]
    else:
        active_users = db.query(models.User.id, models.User.department).filter(
            models.User.is_active == True
        ).all()
        target_user_ids = [
            uid for uid, dept in active_users
            if uid != current_admin_id and _dept_matches(dept, [db_reading.target_department])
        ]

    if not target_user_ids:
        return

    due_str = db_reading.due_date.strftime("%Y-%m-%d")
    message_content = f"ახალი სავალდებულოდ გასაცნობი მასალა: '{item_title}' (ვადა: {due_str})"

    # PERFORMANCE (Item 15/25): one bulk INSERT instead of N flushes.
    message_rows = [
        {
            "user_id": uid,
            "sender_id": current_admin_id,
            "content": message_content,
            "is_read": False,
            "created_at": get_tbilisi_time(),
        }
        for uid in target_user_ids
    ]
    db.bulk_insert_mappings(models.Message, message_rows)

    # Single broadcast event for the toast + list refresh. The SSE endpoint
    # gates by target_department, so users outside the dept never see it.
    _safe_publish({
        "type": "required_reading",
        "id": db_reading.id,
        "title": item_title,
        "item_type": db_reading.item_type,
        "item_id": db_reading.item_id,
        "due_date": due_str,
        "target_department": db_reading.target_department,
    })

    # Per-user "message" events so each open session gets its unread badge
    # updated immediately (the badge is keyed by user_id). target_user_id
    # restricts server-side delivery to the recipient (+ admins) instead of
    # relying solely on the client-side user_id check to hide it from others.
    for uid in target_user_ids:
        _safe_publish({
            "type": "message",
            "user_id": uid,
            "target_user_id": uid,
            "content": message_content,
            "sender_id": current_admin_id,
            # 'All' so the SSE endpoint's department filter doesn't drop it;
            # target_user_id above is now the real restriction.
            "target_department": "All",
        })


@router.post("/api/compliance/required-readings", response_model=schemas.RequiredReadingResponse)
def create_required_reading(
    reading: schemas.RequiredReadingBase,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Assigns a required compliance reading for a content item and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        reading: Schema containing the assignment properties (item type, item ID, department, due date).
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        The created RequiredReading database row.
    """
    db_reading = models.RequiredReading(**reading.model_dump())
    db.add(db_reading)
    db.commit()
    db.refresh(db_reading)

    # Auto-generate notifications for mandatory content
    try:
        auto_generate_notifications_for_mandatory(db_reading, current_admin.id, db)
        db.commit()
    except Exception as e:
        logger.exception("Error auto generating mandatory notifications: %s", e)

    db.commit()
    return db_reading


@router.get("/api/compliance/required-readings/by-item/{item_type}/{item_id}", response_model=Optional[schemas.RequiredReadingResponse])
def get_required_reading_for_item(
    item_type: str,
    item_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Returns the RequiredReading row that targets this content item, if any.

    Used by the admin edit form to discover whether an article/news is currently
    mandatory and prefill the checkbox + due date.
    """
    rr = db.query(models.RequiredReading).filter(
        models.RequiredReading.item_type == item_type,
        models.RequiredReading.item_id == item_id,
    ).first()
    return rr


@router.put("/api/compliance/required-readings/{reading_id}", response_model=schemas.RequiredReadingResponse)
def update_required_reading(
    reading_id: int,
    payload: schemas.RequiredReadingBase,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Update the due date / department / priority of an existing required reading."""
    rr = get_or_404(db, models.RequiredReading, reading_id, "სავალდებულო მასალა ვერ მოიძებნა")
    for k, v in payload.model_dump().items():
        setattr(rr, k, v)
    db.commit()
    db.refresh(rr)
    return rr


@router.delete("/api/compliance/required-readings/{reading_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_required_reading(
    reading_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Remove a required-reading assignment (e.g. admin un-checks the mandatory box)."""
    rr = get_or_404(db, models.RequiredReading, reading_id, "სავალდებულო მასალა ვერ მოიძებნა")
    db.delete(rr)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="სავალდებულო მასალის წაშლა ვერ ხერხდება -- მომხმარებლებმა უკვე გაიცნეს იგი",
        )
    return None
