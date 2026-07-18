"""News/announcement routes: CRUD, autosave, and revision history/restore —
Phase 10 of the main.py monolith split.

Two clusters in the original main.py (CRUD around line 839, history/restore
around line 4016) are gathered into this one file, per the roadmap's
non-contiguous-domain procedure.
"""
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import case, desc, or_
from sqlalchemy.orm import Session

import models
import schemas
import security
from database import get_db, get_tbilisi_time
from db_helpers import get_or_404, log_audit
from routers.stats import _dept_matches, _split_dept_group
from state import _notify, search_cache

router = APIRouter(tags=["news"])


@router.get("/api/news/{news_id}", response_model=schemas.NewsResponse)
def get_news_item(
    news_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
):
    """Retrieve a single news item by id.

    Mirrors get_news visibility rules: regular users see only their department
    or 'All', admins see everything. Added so the reading dispatcher and the
    history viewer can resolve a news row by id without loading the list.
    """
    n = get_or_404(db, models.News, news_id, "სიახლე ვერ მოიძებნა")
    if current_user.role not in security.CONTENT_ADMIN_ROLES:
        if n.is_draft:
            raise HTTPException(status_code=404, detail="სიახლე ვერ მოიძებნა")
        if not _dept_matches(current_user.department, [n.target_department]):
            raise HTTPException(status_code=404, detail="სიახლე ვერ მოიძებნა")
    else:
        if n.is_draft and n.author_id != current_user.id:
            raise HTTPException(status_code=404, detail="სიახლე ვერ მოიძებნა")
    return n


@router.get("/api/news", response_model=list[schemas.NewsSummaryResponse])
def get_news(
    skip: int = 0,
    limit: int = 20,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves general announcements and news items.

    Regular operators and managers see only announcements targeting their own department
    or designated as "All". Administrators and content admins can see all announcements.

    Access: Authenticated users (any active role).

    Args:
        skip: Pagination offset count.
        limit: Maximum number of announcements to return.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A list of NewsResponse schemas.
    """
    query = db.query(models.News)

    # Filter out other users' drafts for all query paths (admins and operators)
    query = query.filter(
        or_(
            models.News.is_draft == False,
            models.News.author_id == current_user.id
        )
    )

    # Admins manage content across all departments, so they see everything (including expired)
    if current_user.role not in security.CONTENT_ADMIN_ROLES:
        query = query.filter(models.News.is_draft == False)
        _dept_prefix = _split_dept_group(current_user.department)[0]
        query = query.filter(models.News.target_department.in_([current_user.department, _dept_prefix, "All"]))
        query = query.filter(
            or_(
                models.News.expires_at.is_(None),
                models.News.expires_at >= get_tbilisi_time()
            )
        )
    # Block 5: role-based visibility split, independent of department targeting.
    if current_user.role == "tech_info":
        query = query.filter(models.News.visible_to_tech_info == True)  # noqa: E712
    elif current_user.role == "service_center":
        query = query.filter(models.News.visible_to_service_center == True)  # noqa: E712

    # Department-first sorting: User's exact department bubbles to the top, then ordered by date
    dept_score = case(
        (models.News.target_department == current_user.department, 1),
        else_=0
    )
    return query.order_by(desc(dept_score), models.News.created_at.desc()).offset(skip).limit(limit).all()


@router.post("/api/news", response_model=schemas.NewsResponse)
def create_news(
    news: schemas.NewsCreate,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Creates a new news announcement and publishes a real-time SSE notification.

    Creates an audit log entry tracking the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        news: The news announcement creation details.
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        The created News database row.
    """
    # SECURITY FIX: publishing news is a content-admin action. Previously this
    # endpoint only required get_current_user, letting any operator post news.
    db_news = models.News(**news.model_dump())
    db_news.author_id = current_admin.id
    db.add(db_news)
    db.flush()

    db.commit()
    search_cache.clear()

    # Real-time: push a notification to connected users in the target department if not a draft.
    if not db_news.is_draft:
        _notify("news", db_news)
    return db_news


@router.put("/api/news/{news_id}", response_model=schemas.NewsResponse)
def update_news(
    news_id: int,
    news: schemas.NewsCreate,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Updates an existing news announcement and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        news_id: ID of the news announcement to update.
        news: The news announcement update details.
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        The updated News database row.

    Raises:
        HTTPException: 404 Not Found if the announcement does not exist.
    """
    db_news = get_or_404(db, models.News, news_id, "სიახლე ვერ მოიძებნა")

    # Save the current state to history before applying changes (parity with articles).
    db.add(models.NewsHistory(
        news_id=db_news.id,
        title=db_news.title,
        content=db_news.content,
        attachment_url=db_news.attachment_url,
        updated_by=current_admin.id,
    ))

    for key, value in news.model_dump().items():
        setattr(db_news, key, value)

    db_news.version = (db_news.version or 1) + 1

    db.commit()
    search_cache.clear()
    db.refresh(db_news)
    return db_news


@router.delete("/api/news/{news_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_news(
    news_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Deletes a news announcement and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        news_id: ID of the news announcement to delete.
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        None.

    Raises:
        HTTPException: 404 Not Found if the announcement does not exist.
    """
    db_news = get_or_404(db, models.News, news_id, "სიახლე ვერ მოიძებნა")

    db.delete(db_news)

    db.commit()
    search_cache.clear()
    return None


@router.patch("/api/news/{news_id}/autosave", response_model=schemas.NewsAutosaveResponse)
def autosave_news(
    news_id: int,
    news: schemas.NewsAutosave,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Autosaves partial news details without strict validation."""
    db_news = get_or_404(db, models.News, news_id, "სიახლე ვერ მოიძებნა")

    update_data = news.model_dump(exclude_unset=True)

    # Set author_id if it's not set
    if getattr(db_news, "author_id", None) is None:
        db_news.author_id = current_admin.id

    for key, value in update_data.items():
        setattr(db_news, key, value)

    db.commit()
    db.refresh(db_news)
    search_cache.clear()
    return db_news


@router.get("/api/news/{news_id}/history", response_model=list[schemas.NewsHistoryResponse])
def get_news_history(
    news_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Returns the revision list for one news item — admin-only."""
    history = (
        db.query(models.NewsHistory, models.User.name.label("author_name"))
        .join(models.User, models.NewsHistory.updated_by == models.User.id)
        .filter(models.NewsHistory.news_id == news_id)
        .order_by(desc(models.NewsHistory.updated_at))
        .all()
    )
    return [
        {
            "id": h.NewsHistory.id,
            "title": h.NewsHistory.title,
            "content": h.NewsHistory.content,
            "attachment_url": h.NewsHistory.attachment_url,
            "updated_at": h.NewsHistory.updated_at,
            "author_name": h.author_name,
        }
        for h in history
    ]


@router.post("/api/news/{news_id}/history/{history_id}/restore", response_model=schemas.NewsResponse)
def restore_news_version(
    news_id: int,
    history_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Restores a news item to a previous version. Current state is archived first."""
    db_news = get_or_404(db, models.News, news_id, "სიახლე ვერ მოიძებნა")
    h = db.query(models.NewsHistory).filter(
        models.NewsHistory.id == history_id,
        models.NewsHistory.news_id == news_id,
    ).first()
    if not h:
        raise HTTPException(status_code=404, detail="ისტორიის ვერსია ვერ მოიძებნა")

    # Archive the current state so the restore itself is undoable.
    db.add(models.NewsHistory(
        news_id=db_news.id,
        title=db_news.title,
        content=db_news.content,
        attachment_url=db_news.attachment_url,
        updated_by=current_admin.id,
    ))

    db_news.title = h.title
    db_news.content = h.content
    db_news.attachment_url = h.attachment_url
    db_news.version = (db_news.version or 1) + 1

    log_audit(db, admin_id=current_admin.id, action="RESTORE", item_type="news", item_id=db_news.id)
    db.commit()
    db.refresh(db_news)
    search_cache.clear()
    return db_news
