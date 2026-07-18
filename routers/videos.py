"""Video instruction routes: listing, view-count increment, and admin CRUD
(create/update/delete/archive/unarchive) — Phase 8 of the main.py monolith
split.

normalize_youtube_url moves here too: its only callers were video create/
update and main.py's startup _lightweight_migrations() (which now reaches it
via a lazy import, same pattern as the other cross-module helpers).
"""
import re

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

import models
import schemas
import security
from database import get_db
from db_helpers import get_or_404, log_audit
from routers.articles import sync_tags
from state import _notify, search_cache

router = APIRouter(tags=["videos"])


def normalize_youtube_url(url: str) -> str:
    if not url:
        return url
    url_str = url.strip()

    # Strict regex patterns to match standard, shortened, and embed YouTube formats
    # capturing the 11-character video ID.
    patterns = [
        r'youtu\.be/([a-zA-Z0-9_-]{11})',
        r'youtube(?:-nocookie)?\.com/embed/([a-zA-Z0-9_-]{11})',
        r'youtube(?:-nocookie)?\.com/watch\?(?:[^&]*&)*v=([a-zA-Z0-9_-]{11})',
        r'youtube(?:-nocookie)?\.com/v/([a-zA-Z0-9_-]{11})',
        r'youtube(?:-nocookie)?\.com/vi/([a-zA-Z0-9_-]{11})',
        r'youtube(?:-nocookie)?\.com/e/([a-zA-Z0-9_-]{11})',
        r'[?&]v=([a-zA-Z0-9_-]{11})',
    ]
    for pattern in patterns:
        match = re.search(pattern, url_str, re.IGNORECASE)
        if match:
            return f"https://www.youtube.com/embed/{match.group(1)}?rel=0"

    # Fallback if the input is strictly an 11-character YouTube video ID
    if len(url_str) == 11 and re.match(r'^[a-zA-Z0-9_-]{11}$', url_str):
        return f"https://www.youtube.com/embed/{url_str}?rel=0"

    return url_str


@router.get("/api/videos", response_model=list[schemas.VideoInstructionResponse])
def get_videos(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves video instruction modules.

    Regular operators and managers see only videos targeting their department or "All".
    Content administrators and system administrators see all videos.

    Access: Authenticated users (any active role).

    Args:
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A list of VideoInstructionResponse schemas.
    """
    query = db.query(models.VideoInstruction)
    # Admins manage content across all departments (including archived items),
    # so they see everything; operators/managers see only active, in-department content.
    if current_user.role not in security.CONTENT_ADMIN_ROLES:
        query = query.filter(
            models.VideoInstruction.target_department.in_([current_user.department, "All"]),
            models.VideoInstruction.is_archived == False,  # noqa: E712
        )
    return query.all()


@router.post("/api/videos/{video_id}/view", response_model=schemas.VideoInstructionResponse)
def view_video(
    video_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Increments the view count of a video instruction module.

    Access: Authenticated users (any active role).

    Args:
        video_id: ID of the video to view.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        The updated VideoInstruction database row.

    Raises:
        HTTPException: 404 Not Found if the video does not exist.
    """
    video = get_or_404(db, models.VideoInstruction, video_id, "ვიდეო ვერ მოიძებნა")

    video.views_count += 1
    db.commit()
    db.refresh(video)
    return video


@router.post("/api/videos", response_model=schemas.VideoInstructionResponse)
def create_video(
    video: schemas.VideoInstructionCreate,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Creates a new video instruction entry, sends real-time SSE notify, and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        video: Video instruction creation details.
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        The created VideoInstruction database row.
    """
    video_data = video.model_dump()
    video_data["video_url"] = normalize_youtube_url(video_data["video_url"])
    db_video = models.VideoInstruction(**video_data)
    db.add(db_video)
    db.flush()
    sync_tags(db, "video", db_video.id, db_video.tags)

    db.commit()
    search_cache.clear()

    # Real-time: notify the target department about the new video.
    _notify("video", db_video)
    return db_video


@router.put("/api/videos/{video_id}", response_model=schemas.VideoInstructionResponse)
def update_video(
    video_id: int,
    video: schemas.VideoInstructionCreate,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Updates an existing video instruction entry and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        video_id: ID of the video to update.
        video: Video update details.
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        The updated VideoInstruction database row.

    Raises:
        HTTPException: 404 Not Found if the video does not exist.
    """
    db_video = get_or_404(db, models.VideoInstruction, video_id, "ვიდეო ვერ მოიძებნა")

    video_data = video.model_dump()
    video_data["video_url"] = normalize_youtube_url(video_data["video_url"])
    for key, value in video_data.items():
        setattr(db_video, key, value)

    sync_tags(db, "video", db_video.id, db_video.tags)
    db.commit()
    db.refresh(db_video)

    search_cache.clear()
    return db_video


@router.delete("/api/videos/{video_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_video(
    video_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Deletes a video instruction entry and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        video_id: ID of the video to delete.
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        None.

    Raises:
        HTTPException: 404 Not Found if the video does not exist.
    """
    db_video = get_or_404(db, models.VideoInstruction, video_id, "ვიდეო ვერ მოიძებნა")

    db.delete(db_video)
    db.commit()

    search_cache.clear()
    return None


@router.post("/api/videos/{video_id}/archive", response_model=schemas.VideoInstructionResponse)
def archive_video(
    video_id: int,
    current_admin: models.User = Depends(security.require_permission(security.PERM_VIDEOS_ARCHIVE)),
    db: Session = Depends(get_db),
):
    """Soft-archives a video instruction by setting is_archived=True.

    Mirrors archive_article: archived videos disappear from the operator-facing
    list (get_videos / global search already filter on is_archived for
    non-admins). Admins continue to see them.
    """
    db_video = get_or_404(db, models.VideoInstruction, video_id, "ვიდეო ვერ მოიძებნა")
    if db_video.is_archived:
        return db_video  # idempotent

    db_video.is_archived = True
    log_audit(db, admin_id=current_admin.id, action="ARCHIVE", item_type="video", item_id=video_id)
    db.commit()
    search_cache.clear()
    db.refresh(db_video)
    return db_video


@router.post("/api/videos/{video_id}/unarchive", response_model=schemas.VideoInstructionResponse)
def unarchive_video(
    video_id: int,
    current_admin: models.User = Depends(security.require_permission(security.PERM_VIDEOS_ARCHIVE)),
    db: Session = Depends(get_db),
):
    """Restores an archived video to is_archived=False."""
    db_video = get_or_404(db, models.VideoInstruction, video_id, "ვიდეო ვერ მოიძებნა")
    if not db_video.is_archived:
        raise HTTPException(status_code=400, detail="ვიდეო არ არის არქივში")

    db_video.is_archived = False
    log_audit(db, admin_id=current_admin.id, action="UNARCHIVE", item_type="video", item_id=video_id)
    db.commit()
    search_cache.clear()
    db.refresh(db_video)
    return db_video
