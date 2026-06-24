"""Articles domain router (Phase 2.6 — strangler extraction from main.py).

Scope: all /api/articles/* endpoints.
  - GET /                        list articles
  - GET /{article_id}            get single article
  - POST /                       create article
  - PUT /{article_id}            update article
  - DELETE /{article_id}         delete article
  - POST /{article_id}/archive   soft-archive
  - POST /{article_id}/unarchive restore from archive
  - GET /{article_id}/history    revision list
  - POST /{article_id}/history/{history_id}/restore  restore version
  - POST /{article_id}/view      track view
  - POST /{article_id}/feedback  user feedback report
  - GET /{article_id}/note       get private note
  - PUT /{article_id}/note       upsert private note
  - POST /{article_id}/verify    mark content verified
  - GET /{article_id}/related    related articles

Zero direct imports to categories.py — both routers are fully independent.

Cross-cutting singletons (search_cache, category_cache, _notify) are accessed
via `import main as _main` inside each function body that needs them, avoiding
a circular dependency at module-load time.

`sync_tags` and `_assert_article_visible` are pure helpers that only depend on
shared infrastructure (models, Session, security) — they are defined locally
here rather than triggering a main import.
"""
from datetime import datetime, timedelta
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import and_, case, desc, func, or_
from sqlalchemy.orm import Session

import models
import schemas
import security
from database import get_db

router = APIRouter(prefix="/api/articles", tags=["articles"])


# ── Private helpers ───────────────────────────────────────────────────────────

def _assert_article_visible(article: models.Article, user: models.User) -> None:
    """Raise 404 if ``user`` is not authorised to see ``article``.

    Centralises the visibility contract that ``GET /api/articles`` already
    enforces for the list view, so per-article child routes (notes, feedback,
    /related, …) can't be used to *probe* or *act on* articles outside the
    caller's scope. We deliberately raise **404** rather than **403** to avoid
    leaking the existence of out-of-scope articles via enumeration
    (`/api/articles/{n}/note` would otherwise return 200 vs 403 — a side
    channel that lists every article id).

    Admins (system + content) bypass — they manage everything.

    Args:
        article: The Article ORM row (must be loaded).
        user: The authenticated User.

    Raises:
        HTTPException(404): If the user's department is not covered, or the
            article is not in a publicly-readable state.
    """
    if user.role in (security.ROLE_SYSTEM_ADMIN, security.ROLE_CONTENT_ADMIN):
        return
    if article.target_department not in (user.department, "All"):
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")
    if article.status == "published":
        return
    if (
        article.status == "scheduled"
        and getattr(article, "published_at", None) is not None
        and article.published_at <= datetime.utcnow()
    ):
        return
    raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")


def sync_tags(db: Session, item_type: str, item_id: int, tags_csv: Optional[str]) -> None:
    """Keeps the normalized tags/tags_mapping tables in sync with an item's
    flat comma-separated tags string, creating new Tag rows on the fly.

    Call after the item itself is flushed (item_id must already exist).
    Does not commit — caller's existing commit() covers this too.
    """
    db.query(models.TagMapping).filter(
        models.TagMapping.item_type == item_type,
        models.TagMapping.item_id == item_id,
    ).delete()

    if not tags_csv:
        return

    names = {n.strip() for n in tags_csv.split(",") if n.strip()}
    for name in names:
        tag = db.query(models.Tag).filter(models.Tag.name == name).first()
        if tag is None:
            tag = models.Tag(name=name)
            db.add(tag)
            db.flush()
        db.add(models.TagMapping(tag_id=tag.id, item_type=item_type, item_id=item_id))


# ── Read endpoints ────────────────────────────────────────────────────────────

@router.get("", response_model=list[schemas.ArticleSummaryResponse])
def get_articles(
    skip: int = 0,
    limit: int = 20,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves Knowledge Base articles.

    Regular operators and managers see only published or scheduled articles whose target publication
    time has passed, and which target their specific department or "All". Content administrators
    and system administrators can retrieve all articles including drafts, scheduled ones, and archives.

    Access: Authenticated users (any active role).

    Args:
        skip: Number of records to skip (for pagination).
        limit: Maximum number of records to return.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A list of ArticleSummaryResponse schemas.
    """
    query = db.query(models.Article)
    # Admins manage content across all departments, so they see everything
    # (including archived items); regular users see only published content
    if current_user.role not in ["admin", "content_admin"]:
        now = datetime.utcnow()
        query = query.filter(
            models.Article.target_department.in_([current_user.department, "All"]),
            or_(
                models.Article.status == "published",
                and_(
                    models.Article.status == "scheduled",
                    models.Article.published_at <= now
                )
            )
        )
    # Block 5: role-based visibility split, independent of department targeting.
    if current_user.role == "tech_info":
        query = query.filter(models.Article.visible_to_tech_info == True)  # noqa: E712
    elif current_user.role == "service_center":
        query = query.filter(models.Article.visible_to_service_center == True)  # noqa: E712

    # Department-first sorting
    dept_score = case(
        (models.Article.target_department == current_user.department, 1),
        else_=0
    )
    return query.order_by(desc(dept_score), desc(models.Article.created_at)).offset(skip).limit(limit).all()


@router.get("/{article_id}", response_model=schemas.ArticleResponse)
def get_article(
    article_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves full details of a specific article.

    Args:
        article_id: ID of the article to retrieve.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        The full ArticleResponse schema.
    """
    article = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not article:
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")
    _assert_article_visible(article, current_user)
    return article


# ── Write endpoints ───────────────────────────────────────────────────────────

@router.post("", response_model=schemas.ArticleResponse)
def create_article(
    article: schemas.ArticleCreate,
    current_admin: models.User = Depends(security.require_content_creator(security.PERM_ARTICLES_CREATE)),
    db: Session = Depends(get_db)
):
    """Creates a new Knowledge Base article and logs the action.

    Sets the author as the currently authenticated administrator. Publishes an SSE notification
    if the article is immediately published. Clears search and category caches.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        article: Article creation details.
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        The created Article database row.
    """
    # Data integrity: the author is the authenticated editor, never a
    # client-supplied author_id (which could be spoofed).
    payload = article.model_dump()
    payload["author_id"] = current_admin.id
    db_article = models.Article(**payload)
    db.add(db_article)
    db.flush()
    sync_tags(db, "article", db_article.id, db_article.tags)

    audit_log = models.AuditLog(
        admin_id=current_admin.id,
        action="CREATE",
        item_type="article",
        item_id=db_article.id
    )
    db.add(audit_log)
    db.commit()

    import main as _main
    # Real-time: notify the target department only about PUBLISHED articles.
    if db_article.status == "published":
        _main._notify("article", db_article)
    _main.search_cache.clear()
    _main.category_cache.clear()
    return db_article


@router.put("/{article_id}", response_model=schemas.ArticleResponse)
def update_article(
    article_id: int,
    article: schemas.ArticleCreate,
    current_admin: models.User = Depends(security.require_content_creator(security.PERM_ARTICLES_CREATE)),
    db: Session = Depends(get_db)
):
    """Updates an existing article, archiving the old state in ArticleHistory.

    Increments the version number of the article. Clears search and category caches.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        article_id: ID of the article to update.
        article: The new article details.
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        The updated Article database row.

    Raises:
        HTTPException: 404 Not Found if the article does not exist.
    """
    db_article = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not db_article:
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")

    # Save the current state to history before applying changes
    article_history = models.ArticleHistory(
        article_id=db_article.id,
        title=db_article.title,
        content=db_article.content,
        updated_by=current_admin.id
    )
    db.add(article_history)

    for key, value in article.model_dump().items():
        setattr(db_article, key, value)

    db_article.version += 1
    sync_tags(db, "article", db_article.id, db_article.tags)
    db.commit()
    db.refresh(db_article)

    audit_log = models.AuditLog(admin_id=current_admin.id, action="UPDATE", item_type="article", item_id=db_article.id)
    db.add(audit_log)
    db.commit()

    import main as _main
    _main.search_cache.clear()
    _main.category_cache.clear()
    return db_article


@router.delete("/{article_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_article(
    article_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Deletes an article, clears caches, and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        article_id: ID of the article to delete.
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        None.

    Raises:
        HTTPException: 404 Not Found if the article does not exist.
    """
    db_article = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not db_article:
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")

    db.delete(db_article)
    db.commit()

    audit_log = models.AuditLog(admin_id=current_admin.id, action="DELETE", item_type="article", item_id=article_id)
    db.add(audit_log)
    db.commit()

    import main as _main
    _main.search_cache.clear()
    _main.category_cache.clear()
    return None


# ── Lifecycle: archive / unarchive ────────────────────────────────────────────

@router.post("/{article_id}/archive", response_model=schemas.ArticleResponse)
def archive_article(
    article_id: int,
    current_admin: models.User = Depends(security.require_permission(security.PERM_ARTICLES_ARCHIVE)),
    db: Session = Depends(get_db),
):
    """Soft-archives an article by setting status='archived'.

    Archived articles disappear from the operator-facing list (the existing
    visibility contract in _assert_article_visible already excludes any status
    that isn't 'published' or a live 'scheduled'). Admins continue to see them.

    Spec slide 24: archive completed promotional offers / superseded materials.
    """
    db_article = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not db_article:
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")
    if db_article.status == "archived":
        return db_article  # idempotent

    db_article.status = "archived"
    db.add(models.AuditLog(
        admin_id=current_admin.id, action="ARCHIVE", item_type="article", item_id=article_id
    ))
    db.commit()

    import main as _main
    _main.search_cache.clear()
    _main.category_cache.clear()
    db.refresh(db_article)
    return db_article


@router.post("/{article_id}/unarchive", response_model=schemas.ArticleResponse)
def unarchive_article(
    article_id: int,
    current_admin: models.User = Depends(security.require_permission(security.PERM_ARTICLES_ARCHIVE)),
    db: Session = Depends(get_db),
):
    """Restores an archived article to status='published'."""
    db_article = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not db_article:
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")
    if db_article.status != "archived":
        raise HTTPException(status_code=400, detail="სტატია არ არის არქივში")

    db_article.status = "published"
    db.add(models.AuditLog(
        admin_id=current_admin.id, action="UNARCHIVE", item_type="article", item_id=article_id
    ))
    db.commit()

    import main as _main
    _main.search_cache.clear()
    _main.category_cache.clear()
    db.refresh(db_article)
    return db_article


# ── Versioning ────────────────────────────────────────────────────────────────

@router.get("/{article_id}/history")
def get_article_history(
    article_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieve the revision history of an article (Admins only)."""
    # Join with User to get the editor's name
    history = (
        db.query(models.ArticleHistory, models.User.name.label("author_name"))
        .join(models.User, models.ArticleHistory.updated_by == models.User.id)
        .filter(models.ArticleHistory.article_id == article_id)
        .order_by(desc(models.ArticleHistory.updated_at))
        .all()
    )

    return [
        {
            "id": h.ArticleHistory.id,
            "title": h.ArticleHistory.title,
            "content": h.ArticleHistory.content,
            "updated_at": h.ArticleHistory.updated_at,
            "author_name": h.author_name,
        }
        for h in history
    ]


@router.post("/{article_id}/history/{history_id}/restore", response_model=schemas.ArticleResponse)
def restore_article_version(
    article_id: int,
    history_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Restores a previous version of an article from its history.

    Archives the current version in ArticleHistory before restoring. Increments the version counter.
    Clears search and category caches. Logs the restoration in the audit logs.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        article_id: ID of the article to restore.
        history_id: ID of the history entry to restore.
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        The restored Article database row.

    Raises:
        HTTPException: 404 Not Found if the article or history version is not found.
    """
    db_article = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not db_article:
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")

    history = db.query(models.ArticleHistory).filter(
        models.ArticleHistory.id == history_id,
        models.ArticleHistory.article_id == article_id,
    ).first()
    if not history:
        raise HTTPException(status_code=404, detail="ისტორიის ვერსია ვერ მოიძებნა")

    # Save the current state to history first (enabling undo)
    backup_history = models.ArticleHistory(
        article_id=db_article.id,
        title=db_article.title,
        content=db_article.content,
        updated_by=current_admin.id
    )
    db.add(backup_history)

    # Overwrite current row with history values
    db_article.title = history.title
    db_article.content = history.content
    db_article.version += 1
    db.commit()
    db.refresh(db_article)

    # Log restore to audit trail
    audit_log = models.AuditLog(
        admin_id=current_admin.id,
        action="RESTORE",
        item_type="article",
        item_id=db_article.id
    )
    db.add(audit_log)
    db.commit()

    import main as _main
    _main.search_cache.clear()
    _main.category_cache.clear()
    return db_article


# ── Engagement: view tracking ─────────────────────────────────────────────────

@router.post("/{article_id}/view")
def track_article_view(
    article_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Logs an article view action in the audit logs.

    Used to track which content items operators are reading.

    Access: Authenticated users (any active role).

    Args:
        article_id: ID of the article viewed.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A dictionary indicating success.

    Raises:
        HTTPException: 404 Not Found if the article does not exist.
    """
    db_article = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not db_article:
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")

    # Log the view action in the audit logs
    audit_log = models.AuditLog(
        admin_id=current_user.id,
        action="VIEW",
        item_type="article",
        item_id=article_id
    )
    db.add(audit_log)
    db.commit()
    return {"status": "success"}


# ── Feedback ──────────────────────────────────────────────────────────────────

@router.post("/{article_id}/feedback", response_model=schemas.KnowledgeFeedbackResponse)
def create_article_feedback(
    article_id: int,
    req: schemas.KnowledgeFeedbackCreate,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    raise HTTPException(status_code=410, detail="ხარვეზის რეპორტირება დეპრეკირებულია")


# ── Private notes ─────────────────────────────────────────────────────────────

@router.get("/{article_id}/note", response_model=Optional[schemas.UserNoteResponse])
def get_user_note(
    article_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves the private scratchpad note on a specific article for the current user.

    Access: Authenticated users (any active role).

    Args:
        article_id: ID of the article.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        The UserNote details, or None if no note exists.
    """
    # R-1: only let the user touch notes on articles they can actually see;
    # otherwise an operator could enumerate every article id by probing for
    # a 200 vs 404 here.
    article = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not article:
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")
    _assert_article_visible(article, current_user)

    note = db.query(models.UserNote).filter(
        models.UserNote.user_id == current_user.id,
        models.UserNote.article_id == article_id
    ).first()
    return note


@router.put("/{article_id}/note", response_model=schemas.UserNoteResponse)
def put_user_note(
    article_id: int,
    req: schemas.UserNoteCreate,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Creates or updates a user's private scratchpad note on an article.

    Access: Authenticated users (any active role).

    Args:
        article_id: ID of the article.
        req: Scratchpad note content.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        The created or updated UserNote database row.
    """
    # R-1: scope guard — never allow a user to plant data on an article they
    # can't read. The article load is required anyway to assert visibility.
    article = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not article:
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")
    _assert_article_visible(article, current_user)

    note = db.query(models.UserNote).filter(
        models.UserNote.user_id == current_user.id,
        models.UserNote.article_id == article_id
    ).first()

    if note:
        note.content = req.content
    else:
        note = models.UserNote(
            user_id=current_user.id,
            article_id=article_id,
            content=req.content
        )
        db.add(note)

    db.commit()
    db.refresh(note)
    return note


# ── Content quality ───────────────────────────────────────────────────────────

@router.post("/{article_id}/verify", response_model=schemas.ArticleResponse)
def verify_article(
    article_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Confirms that an article's content has been reviewed and verified as up-to-date.

    Updates the last_verified_at field to the current timestamp and logs the event.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        article_id: ID of the article to verify.
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        The verified Article database row.

    Raises:
        HTTPException: 404 Not Found if the article does not exist.
    """
    article = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not article:
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")

    article.last_verified_at = datetime.utcnow()

    import main as _main
    _main.search_cache.clear()

    # Log in audit trail
    audit_log = models.AuditLog(
        admin_id=current_admin.id,
        action="VERIFY",
        item_type="article",
        item_id=article_id
    )
    db.add(audit_log)
    db.commit()
    db.refresh(article)
    return article


# ── Discovery: related articles ───────────────────────────────────────────────

@router.get("/{article_id}/related")
def get_related_articles(
    article_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Returns up to 4 articles related by shared category or overlapping tags.

    Used in the article reader modal to provide operators quick access to
    adjacent procedures without returning to search.

    Access: Authenticated users (any active role).

    Args:
        article_id: ID of the source article.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A list of related article summaries (max 4).
    """
    source = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not source:
        return []
    # R-1: if the user can't see the source article, behave as if it doesn't
    # exist (404). Empty-list-on-miss would otherwise be a side-channel signal.
    _assert_article_visible(source, current_user)

    # Build a query for same-category or tag-overlapping articles
    candidates = db.query(models.Article).filter(
        models.Article.id != article_id,
        models.Article.status == "published"
    )

    # Restrict to user's department visibility
    if current_user.role not in ["admin", "content_admin"]:
        candidates = candidates.filter(
            models.Article.target_department.in_([current_user.department, "All"])
        )

    # Prefer same category
    same_cat = candidates.filter(
        models.Article.category_id == source.category_id
    ).limit(4).all()

    # If we have fewer than 4, fill with tag matches
    results = list(same_cat)
    if len(results) < 4 and source.tags:
        existing_ids = {a.id for a in results}
        tag_list = [t.strip().lower() for t in source.tags.split(",") if t.strip()]
        for tag in tag_list:
            if len(results) >= 4:
                break
            tag_matches = candidates.filter(
                models.Article.tags.ilike(f"%{tag}%"),
                ~models.Article.id.in_(existing_ids | {article_id})
            ).limit(4 - len(results)).all()
            for a in tag_matches:
                if a.id not in existing_ids:
                    results.append(a)
                    existing_ids.add(a.id)

    # If we still have fewer than 4, fill with any published articles visible to the user
    if len(results) < 4:
        existing_ids = {a.id for a in results}
        fill_matches = candidates.filter(
            ~models.Article.id.in_(existing_ids | {article_id})
        ).order_by(models.Article.created_at.desc()).limit(4 - len(results)).all()
        for a in fill_matches:
            if a.id not in existing_ids:
                results.append(a)
                existing_ids.add(a.id)

    return [
        {
            "id": a.id,
            "title": a.title,
            "category_id": a.category_id,
            "tags": a.tags,
        }
        for a in results[:4]
    ]
