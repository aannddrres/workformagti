"""Article routes: this file grows across 5 sub-phases (14a-e) of the
main.py monolith split. Phase 14a was
Core CRUD; Phase 14b added Lifecycle & history; Phase 14c added Quiz &
knowledge; Phase 14d added Read-receipts & views. Phase 14e (this pass,
final) adds Misc/admin: the two deprecated feedback stubs, note GET/PUT,
verify, stale-articles, and related — the last Articles routes left in
main.py, closing out the entire monolith-split roadmap.

Dept-helper import note: Stats (Phase 12) already landed by the time
Articles is extracted, so _dept_matches/_split_dept_group/_MANAGEMENT_ROLES
are imported directly here — no lazy `import main` needed, unlike earlier
phases that ran before Stats existed as a standalone module.
"""
import logging
from datetime import timedelta
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import and_, case, desc, func, or_
from sqlalchemy.orm import Session, defer, joinedload

import diffing
import models
import schemas
import security
from database import format_tbilisi_date, get_db, get_tbilisi_time
from compliance_utils import _dept_matches, _split_dept_group
from db_helpers import get_or_404, log_audit
from routers.stats import _MANAGEMENT_ROLES
from state import _notify, _notify_revision, category_cache, search_cache

router = APIRouter(tags=["articles"])
logger = logging.getLogger("magti")


def sync_tags(db: "Session", item_type: str, item_id: int, tags_csv: Optional[str]) -> None:
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
    if not _dept_matches(user.department, article.target_departments):
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")
    if article.status == "published":
        return
    if (
        article.status == "scheduled"
        and getattr(article, "published_at", None) is not None
        and article.published_at <= get_tbilisi_time()
    ):
        return
    raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")


@router.get("/api/articles", response_model=list[schemas.ArticleSummaryResponse])
def get_articles(
    skip: int = 0,
    limit: int = 20,
    q: Optional[str] = None,
    category_id: Optional[int] = None,
    status: Optional[str] = None,
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
        q: Optional search query to filter by title.
        category_id: Optional category ID filter.
        status: Optional status filter.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A list of ArticleSummaryResponse schemas.
    """
    query = db.query(models.Article).outerjoin(models.Category).options(
        joinedload(models.Article.category), defer(models.Article.content)
    )

    if q:
        query = query.filter(models.Article.title.contains(q))
    if category_id is not None:
        query = query.filter(models.Article.category_id == category_id)
    if status:
        query = query.filter(models.Article.status == status)

    query = query.filter(
        or_(
            models.Article.is_draft == False,
            models.Article.author_id == current_user.id
        )
    )

    # Admins manage content across all departments, so they see everything
    # (including archived items); regular users see only published content
    if current_user.role not in security.CONTENT_ADMIN_ROLES:
        now = get_tbilisi_time()
        _dept_prefix = _split_dept_group(current_user.department)[0]
        query = query.filter(
            models.Article.is_draft == False,
            models.Article.target_department_rows.any(
                models.ArticleTargetDepartment.department.in_([current_user.department, _dept_prefix, "All"])
            ),
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
        (models.Article.target_department_rows.any(
            models.ArticleTargetDepartment.department == current_user.department
        ), 1),
        else_=0
    )
    return query.order_by(desc(dept_score), desc(models.Article.created_at)).offset(skip).limit(limit).all()


@router.get("/api/articles/{article_id}", response_model=schemas.ArticleResponse)
def get_article(
    article_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves full details of a specific article.

    View tracking does NOT happen here — the client cache means this endpoint
    fires on a different schedule than actual opens. POST /api/articles/{id}/view
    (ArticleViewLog) is the single source of truth for who-viewed-what.

    Args:
        article_id: ID of the article to retrieve.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        The full ArticleResponse schema.
    """
    article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")
    _assert_article_visible(article, current_user)
    return article


@router.post("/api/articles", response_model=schemas.ArticleResponse)
def create_article(
    article: schemas.ArticleCreate,
    current_admin: models.User = Depends(security.get_current_admin_user),
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
    # Transient broadcast flag — not a column on Article; drop it on create.
    payload.pop("notify_operators", None)
    target_departments = payload.pop("target_departments")
    payload["author_id"] = current_admin.id
    # Legacy single-value column kept in sync for not-yet-migrated readers
    # (e.g. _notify's SSE payload) during the transition window.
    payload["target_department"] = "All" if "All" in target_departments else target_departments[0]
    db_article = models.Article(**payload)
    db_article.target_department_rows = [
        models.ArticleTargetDepartment(department=d) for d in target_departments
    ]
    db.add(db_article)
    if db_article.status == "published" and db_article.published_at is None:
        db_article.published_at = get_tbilisi_time()
    db.flush()
    sync_tags(db, "article", db_article.id, db_article.tags)

    # Save the initial state to history for Unified Revision Log (Version 1)
    article_history = models.ArticleHistory(
        article_id=db_article.id,
        title=db_article.title,
        content=db_article.content,
        updated_by=current_admin.id,
        version_id=1,
    )
    db.add(article_history)
    db.commit()
    db.refresh(db_article)

    # Real-time: notify the target department only about PUBLISHED articles.
    if db_article.status == "published":
        _notify("article", db_article)
    search_cache.clear()
    category_cache.clear()
    return db_article


def _ensure_current_version_archived(db: Session, article: models.Article, updated_by_id: int) -> None:
    """Checks if the current active version of an article is archived in history.
    If not, archives it under the current version number before any changes or bumps occur.

    Race-safe: article_history has a unique index on (article_id, version_id)
    (migrate.py's ux_article_history_article_version). Two concurrent callers
    archiving the same version both pass the `exists` check, but only one
    insert succeeds — the other degrades to a no-op instead of a duplicate
    row. Callers must not have other uncommitted work pending on `db` before
    calling this: the retry path rolls the session back (same constraint as
    _upsert_read_receipt).
    """
    from sqlalchemy.exc import IntegrityError

    exists = db.query(models.ArticleHistory).filter(
        models.ArticleHistory.article_id == article.id,
        models.ArticleHistory.version_id == article.version,
    ).first()
    if exists:
        return
    archive_row = models.ArticleHistory(
        article_id=article.id,
        title=article.title,
        content=article.content,
        updated_by=updated_by_id,
        version_id=article.version,
        updated_at=article.updated_at or get_tbilisi_time(),
    )
    db.add(archive_row)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()  # another request already archived this version


@router.put("/api/articles/{article_id}", response_model=schemas.ArticleResponse)
def update_article(
    article_id: int,
    article: schemas.ArticleUpdate,
    current_admin: models.User = Depends(security.get_current_admin_user),
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
    db_article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")

    # Ensure the pre-edit content is archived in history under the current version
    _ensure_current_version_archived(db, db_article, current_admin.id)

    # Capture the pre-edit content and status BEFORE updates overwrite it
    old_content = db_article.content
    old_status = db_article.status

    update_data = article.model_dump()
    # Transient broadcast flag — never a column on Article; pop before setattr.
    notify_operators = update_data.pop("notify_operators", False)
    target_departments = update_data.pop("target_departments")
    # The edit form never sends these — they're not user-editable — so the
    # schema defaults them to None. Pop them before the blanket setattr loop
    # below or every edit silently wipes the article's original author and
    # its last-verified timestamp.
    update_data.pop("author_id", None)
    update_data.pop("last_verified_at", None)
    for key, value in update_data.items():
        setattr(db_article, key, value)

    db_article.target_department = "All" if "All" in target_departments else target_departments[0]
    db_article.target_department_rows = [
        models.ArticleTargetDepartment(department=d) for d in target_departments
    ]

    if db_article.status == "published" and db_article.published_at is None:
        db_article.published_at = get_tbilisi_time()

    db_article.version += 1
    sync_tags(db, "article", db_article.id, db_article.tags)

    # Save the new (updated) state to history for Unified Revision Log
    article_history = models.ArticleHistory(
        article_id=db_article.id,
        title=db_article.title,
        content=db_article.content,
        updated_by=current_admin.id,
        version_id=db_article.version,
    )
    db.add(article_history)
    db.commit()
    db.refresh(db_article)

    search_cache.clear()
    category_cache.clear()

    # Real-time broadcast is OPT-IN. The history row is always written above; we
    # only ping the SSE channel when the editor ticked "notify operators".
    if old_status == "draft" and db_article.status == "published":
        _notify("article", db_article)
    elif notify_operators and db_article.status == "published":
        summary = diffing.diff_html(old_content, db_article.content)
        _notify_revision(db_article, current_admin.name, summary)
    else:
        logger.info(
            "Article %s revised by %s (v%s) — history saved, no broadcast "
            "(notify_operators=%s, status=%s).",
            db_article.id, current_admin.id, db_article.version,
            notify_operators, db_article.status,
        )

    return db_article


@router.patch("/api/articles/{article_id}/autosave", response_model=schemas.ArticleAutosaveResponse)
def autosave_article(
    article_id: int,
    article: schemas.ArticleAutosave,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Autosaves partial article details without strict validation."""
    db_article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")

    update_data = article.model_dump(exclude_unset=True)

    if "target_departments" in update_data:
        target_departments = update_data.pop("target_departments")
        if target_departments:
            db_article.target_department = "All" if "All" in target_departments else target_departments[0]
            db_article.target_department_rows = [
                models.ArticleTargetDepartment(department=d) for d in target_departments
            ]

    for key, value in update_data.items():
        setattr(db_article, key, value)

    db.commit()
    db.refresh(db_article)
    search_cache.clear()
    category_cache.clear()
    return db_article


@router.delete("/api/articles/{article_id}", status_code=status.HTTP_204_NO_CONTENT)
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
    db_article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")

    db.delete(db_article)
    db.commit()

    search_cache.clear()
    category_cache.clear()
    return None


@router.post("/api/articles/{article_id}/archive", response_model=schemas.ArticleResponse)
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
    db_article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")
    if db_article.status == "archived":
        return db_article  # idempotent

    db_article.status = "archived"
    log_audit(db, admin_id=current_admin.id, action="ARCHIVE", item_type="article", item_id=article_id)
    db.commit()
    search_cache.clear()
    category_cache.clear()
    db.refresh(db_article)
    return db_article


@router.post("/api/articles/{article_id}/unarchive", response_model=schemas.ArticleResponse)
def unarchive_article(
    article_id: int,
    current_admin: models.User = Depends(security.require_permission(security.PERM_ARTICLES_ARCHIVE)),
    db: Session = Depends(get_db),
):
    """Restores an archived article to status='published'."""
    db_article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")
    if db_article.status != "archived":
        raise HTTPException(status_code=400, detail="სტატია არ არის არქივში")

    db_article.status = "published"
    log_audit(db, admin_id=current_admin.id, action="UNARCHIVE", item_type="article", item_id=article_id)
    db.commit()
    search_cache.clear()
    category_cache.clear()
    db.refresh(db_article)
    return db_article


@router.post("/api/articles/bulk-archive", response_model=schemas.ArticleBulkArchiveResponse)
def bulk_archive_articles(
    payload: schemas.ArticleBulkArchiveRequest,
    current_admin: models.User = Depends(security.require_permission(security.PERM_ARTICLES_ARCHIVE)),
    db: Session = Depends(get_db),
):
    """Bulk soft-archive / unarchive in one round-trip.

    Mirrors archive_article / unarchive_article per id (same status transition
    and AuditLog rows), but commits once. Ids that are missing or already in the
    target state are reported in skipped_ids rather than erroring.
    """
    target = "archived" if payload.archive else "published"
    rows = db.query(models.Article).filter(models.Article.id.in_(payload.ids)).all()
    found = {a.id for a in rows}
    skipped = [i for i in payload.ids if i not in found]
    updated = 0
    for a in rows:
        if a.status == target:
            skipped.append(a.id)
            continue
        a.status = target
        log_audit(
            db,
            admin_id=current_admin.id,
            action="ARCHIVE" if payload.archive else "UNARCHIVE",
            item_type="article", item_id=a.id,
            # Passed explicitly (already in hand) so the mapper event
            # (models.py's _auto_classify_audit_log) doesn't re-query the
            # same admin/article per row in this loop.
            admin_name_snapshot=current_admin.name, admin_email_snapshot=current_admin.email,
            item_name_snapshot=a.title,
        )
        updated += 1
    db.commit()
    search_cache.clear()
    category_cache.clear()
    return schemas.ArticleBulkArchiveResponse(updated=updated, status=target, skipped_ids=skipped)


@router.get("/api/articles/{article_id}/history")
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
            "version_id": h.ArticleHistory.version_id,
        }
        for h in history
    ]


def _diff_ordered_by_version(content_a: str, version_a: int, content_b: str, version_b: int) -> dict:
    """Diffs two (content, version) pairs, always presenting the chronologically
    older side as base_version/the newer as compare_version — regardless of
    which order the caller happened to pass them in. Shared by all three
    get_article_diff branches (predecessor / explicit compare / current),
    which previously each re-implemented this same ordering check.
    """
    if version_a < version_b:
        result = diffing.diff_html(content_a, content_b)
        result["base_version"] = version_a
        result["compare_version"] = version_b
    else:
        result = diffing.diff_html(content_b, content_a)
        result["base_version"] = version_b
        result["compare_version"] = version_a
    return result


@router.get("/api/articles/{article_id}/history/{history_id}/diff")
def get_article_diff(
    article_id: int,
    history_id: int,
    compare_history_id: Optional[int] = None,
    compare_to_predecessor: bool = False,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
):
    """Diff a historical snapshot against its predecessor, the article's CURRENT content, or another historical snapshot.

    Returns {'html', 'added', 'removed', 'version_id'} — available to any user
    who can already read the article.
    """
    art = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")
    _assert_article_visible(art, current_user)
    snap = db.query(models.ArticleHistory).filter(
        models.ArticleHistory.id == history_id,
        models.ArticleHistory.article_id == article_id,
    ).first()
    if not snap:
        raise HTTPException(status_code=404, detail="ისტორიის ვერსია ვერ მოიძებნა")

    if compare_to_predecessor:
        # Find predecessor in history (previous version_id for this article).
        # No predecessor (Version 1) -> self-compare, shows cleanly with no diffs.
        pred = db.query(models.ArticleHistory).filter(
            models.ArticleHistory.article_id == article_id,
            models.ArticleHistory.version_id < snap.version_id,
        ).order_by(desc(models.ArticleHistory.version_id)).first()
        other_content, other_version = (pred.content, pred.version_id) if pred else (snap.content, snap.version_id)
        result = _diff_ordered_by_version(snap.content, snap.version_id, other_content, other_version)
    elif compare_history_id:
        compare_snap = db.query(models.ArticleHistory).filter(
            models.ArticleHistory.id == compare_history_id,
            models.ArticleHistory.article_id == article_id,
        ).first()
        if not compare_snap:
            raise HTTPException(status_code=404, detail="შესადარებელი ისტორიის ვერსია ვერ მოიძებნა")
        result = _diff_ordered_by_version(snap.content, snap.version_id, compare_snap.content, compare_snap.version_id)
    else:
        # Compare snap against current active content
        result = _diff_ordered_by_version(snap.content, snap.version_id, art.content, art.version)

    result["version_id"] = snap.version_id
    return result


@router.post("/api/articles/{article_id}/history/{history_id}/restore", response_model=schemas.ArticleResponse)
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
    db_article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")

    history = db.query(models.ArticleHistory).filter(
        models.ArticleHistory.id == history_id,
        models.ArticleHistory.article_id == article_id,
    ).first()
    if not history:
        raise HTTPException(status_code=404, detail="ისტორიის ვერსია ვერ მოიძებნა")

    # Ensure the active state right before restoration is archived in history
    _ensure_current_version_archived(db, db_article, current_admin.id)

    # Overwrite current row with history values
    db_article.title = history.title
    db_article.content = history.content
    db_article.version += 1

    # Save the restored state to history as a new revision (Version N)
    restored_history = models.ArticleHistory(
        article_id=db_article.id,
        title=db_article.title,
        content=db_article.content,
        updated_by=current_admin.id,
        version_id=db_article.version,
    )
    db.add(restored_history)
    db.commit()
    db.refresh(db_article)

    # Log restore to audit trail
    log_audit(db, admin_id=current_admin.id, action="RESTORE", item_type="article", item_id=db_article.id)
    db.commit()
    search_cache.clear()
    category_cache.clear()

    return db_article


@router.get("/api/articles/{article_id}/quiz/admin", response_model=schemas.QuizAdminUpdate)
def get_article_quiz_admin(
    article_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Full question set INCLUDING is_correct, for re-populating the admin
    question-builder when reopening an article for edit."""
    get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")

    questions = (
        db.query(models.QuizQuestion)
        .filter(models.QuizQuestion.article_id == article_id)
        .order_by(models.QuizQuestion.position)
        .all()
    )
    return {
        "questions": [
            {
                "id": q.id,
                "question_text": q.question_text,
                "position": q.position,
                "answers": [
                    {"id": a.id, "answer_text": a.answer_text, "is_correct": a.is_correct, "position": a.position}
                    for a in sorted(q.answers, key=lambda a: a.position)
                ],
            }
            for q in questions
        ]
    }


@router.put("/api/articles/{article_id}/quiz/admin", response_model=schemas.QuizAdminUpdate)
def update_article_quiz_admin(
    article_id: int,
    payload: schemas.QuizAdminUpdate,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Full delete-and-recreate of an article's quiz questions from the payload."""
    get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")

    if not payload.questions:
        raise HTTPException(status_code=422, detail="ქვიზს უნდა ჰქონდეს მინიმუმ ერთი კითხვა")
    for q in payload.questions:
        if len(q.answers) < 2:
            raise HTTPException(status_code=422, detail="ყოველ კითხვას უნდა ჰქონდეს მინიმუმ 2 პასუხი")
        correct_count = sum(1 for a in q.answers if a.is_correct)
        if correct_count != 1:
            raise HTTPException(status_code=422, detail="ყოველ კითხვას უნდა ჰქონდეს ზუსტად ერთი სწორი პასუხი")

    # Full replace: delete existing questions (cascades to answers), insert the new set.
    db.query(models.QuizQuestion).filter(models.QuizQuestion.article_id == article_id).delete()
    db.flush()

    for qi, q in enumerate(payload.questions):
        db_question = models.QuizQuestion(article_id=article_id, question_text=q.question_text, position=qi)
        db.add(db_question)
        db.flush()
        for ai, a in enumerate(q.answers):
            db.add(models.QuizAnswer(
                question_id=db_question.id, answer_text=a.answer_text, is_correct=a.is_correct, position=ai,
            ))

    log_audit(db, admin_id=current_admin.id, action="UPDATE_QUIZ", item_type="article", item_id=article_id)
    db.commit()

    return get_article_quiz_admin(article_id, current_admin, db)


@router.get("/api/articles/{article_id}/quiz", response_model=schemas.QuizPublicResponse)
def get_article_quiz(
    article_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Operator-facing quiz questions — no is_correct anywhere in the payload."""
    article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")
    _assert_article_visible(article, current_user)
    if not article.quiz_enabled:
        raise HTTPException(status_code=404, detail="ამ სტატიას კვიზი არ აქვს")

    questions = (
        db.query(models.QuizQuestion)
        .filter(models.QuizQuestion.article_id == article_id)
        .order_by(models.QuizQuestion.position)
        .all()
    )
    return {
        "article_id": article_id,
        "article_version": article.version,
        "questions": [
            {
                "id": q.id,
                "question_text": q.question_text,
                "answers": [
                    {"id": a.id, "answer_text": a.answer_text}
                    for a in sorted(q.answers, key=lambda a: a.position)
                ],
            }
            for q in questions
        ],
    }


@router.post("/api/articles/{article_id}/quiz/attempt", response_model=schemas.QuizAttemptResult)
def submit_article_quiz_attempt(
    article_id: int,
    payload: schemas.QuizAttemptSubmit,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Grades a quiz submission server-side, records the attempt (pass or fail),
    and returns which questions were wrong so the operator can review before retrying."""
    article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")
    _assert_article_visible(article, current_user)
    if not article.quiz_enabled:
        raise HTTPException(status_code=404, detail="ამ სტატიას კვიზი არ აქვს")

    questions = (
        db.query(models.QuizQuestion)
        .filter(models.QuizQuestion.article_id == article_id)
        .all()
    )
    if not questions:
        raise HTTPException(status_code=404, detail="ამ სტატიას კვიზის კითხვები არ აქვს")

    wrong_question_ids = []
    score = 0
    for q in questions:
        correct_answer = next((a for a in q.answers if a.is_correct), None)
        chosen_answer_id = payload.answers.get(q.id)
        if correct_answer is not None and chosen_answer_id == correct_answer.id:
            score += 1
        else:
            wrong_question_ids.append(q.id)

    total = len(questions)
    passed = score == total

    prior_attempts = db.query(func.count(models.QuizAttempt.id)).filter(
        models.QuizAttempt.article_id == article_id,
        models.QuizAttempt.article_version == article.version,
        models.QuizAttempt.user_id == current_user.id,
    ).scalar() or 0
    attempt_number = prior_attempts + 1

    db.add(models.QuizAttempt(
        article_id=article_id,
        article_version=article.version,
        user_id=current_user.id,
        attempt_number=attempt_number,
        score=score,
        total_questions=total,
        passed=passed,
    ))
    db.commit()

    return {
        "passed": passed,
        "score": score,
        "total_questions": total,
        "wrong_question_ids": wrong_question_ids,
        "attempt_number": attempt_number,
    }


@router.get("/api/users/me/knowledge-score", response_model=schemas.KnowledgeScoreResponse)
def get_my_knowledge_score(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """'ცოდნის ქულა' — +10 per distinct article quiz passed, +5 bonus if passed
    on the very first attempt for that article version. Tunable constants, not
    architecturally load-bearing."""
    score_data = _compute_knowledge_score(db, current_user.id)
    return {"user_id": current_user.id, **score_data}


def _compute_knowledge_score(db: Session, user_id: int) -> dict:
    """Shared by the personal score endpoint and the leaderboard."""
    rows = (
        db.query(
            models.QuizAttempt.article_id,
            models.QuizAttempt.article_version,
            func.min(models.QuizAttempt.attempt_number),
        )
        .filter(models.QuizAttempt.user_id == user_id, models.QuizAttempt.passed == True)  # noqa: E712
        .group_by(models.QuizAttempt.article_id, models.QuizAttempt.article_version)
        .all()
    )
    articles_passed = len(rows)
    first_try_passes = sum(1 for _, _, min_attempt in rows if min_attempt == 1)
    score = 10 * articles_passed + 5 * first_try_passes
    return {"score": score, "articles_passed": articles_passed, "first_try_passes": first_try_passes}


@router.get("/api/knowledge-leaderboard", response_model=schemas.LeaderboardResponse)
def get_knowledge_leaderboard(
    scope: str = "department",
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Ranks users by 'ცოდნის ქულა' within the current user's own department
    (scope='department', default) or team (scope='team')."""
    query = db.query(models.User).filter(
        models.User.is_active == True,  # noqa: E712
        models.User.role.notin_(_MANAGEMENT_ROLES),
    )
    if scope == "team" and current_user.team_id:
        query = query.filter(models.User.team_id == current_user.team_id)
    else:
        query = query.filter(models.User.department == current_user.department)
    users = query.all()

    entries = []
    for u in users:
        data = _compute_knowledge_score(db, u.id)
        if data["score"] == 0:
            continue
        entries.append({
            "user_id": u.id, "user_name": u.name, "department": u.department, "score": data["score"], "rank": 0,
        })
    entries.sort(key=lambda e: e["score"], reverse=True)
    for i, e in enumerate(entries):
        e["rank"] = i + 1

    return {"entries": entries, "generated_at": get_tbilisi_time()}


@router.get("/api/articles/{article_id}/versions", response_model=list[schemas.ArticleVersionItem])
def get_article_versions(
    article_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieve all available versions (revisions) of an article.

    Available to any user who can already read the article (same department
    gate as GET /api/articles/{id}) — not admin-only. Lets operators see what
    changed and when, without exposing who-read-what (see read-receipts).
    """
    article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")
    _assert_article_visible(article, current_user)

    # Self-healing migration for Unified Revision Log: ensure the article's
    # CURRENT version has a history row (legacy articles predating this
    # feature may be missing it). Shares the race-safe archive logic with
    # update_article/restore_article_version instead of reimplementing it.
    _ensure_current_version_archived(db, article, article.author_id or current_user.id)
    db.commit()

    history = (
        db.query(models.ArticleHistory, models.User.name.label("author_name"))
        .outerjoin(models.User, models.ArticleHistory.updated_by == models.User.id)
        .filter(models.ArticleHistory.article_id == article_id)
        .order_by(desc(models.ArticleHistory.version_id))
        .all()
    )

    versions = []
    for h in history:
        versions.append({
            "version": h.ArticleHistory.version_id or 0,
            "title": h.ArticleHistory.title,
            "updated_at": h.ArticleHistory.updated_at,
            "author_name": h.author_name,
            "history_id": h.ArticleHistory.id,
        })
    versions.sort(key=lambda x: x["version"], reverse=True)
    return versions


def _get_eligible_operators(db: Session, article: models.Article) -> list[models.User]:
    if article.is_draft:
        return []
    now = get_tbilisi_time()
    is_visible = (
        article.status == "published"
        or (
            article.status == "scheduled"
            and article.published_at is not None
            and article.published_at <= now
        )
    )
    if not is_visible:
        return []

    target_depts = article.target_departments
    # Query active users who aren't management roles (admin/content_admin/manager)
    query = db.query(models.User).filter(
        models.User.is_active == True,
        models.User.role.notin_(_MANAGEMENT_ROLES)
    )
    if "All" not in target_depts:
        query = query.filter(models.User.department.in_(target_depts))

    users = query.all()
    eligible = []
    for u in users:
        if u.role == "tech_info" and not article.visible_to_tech_info:
            continue
        if u.role == "service_center" and not article.visible_to_service_center:
            continue
        eligible.append(u)
    return eligible


def _check_quiz_gate(db: Session, article: models.Article, user: models.User) -> None:
    """Raise 403 if article.quiz_enabled and the user has no passing QuizAttempt
    for the article's current version.

    Quiz applies to ANY article (admin toggles it per-article, independent of
    mandatory-reading status), so this is called from BOTH "mark as read" code
    paths: the general article read-receipt (create_article_read_receipt below)
    and the mandatory-reading compliance mark-read (routers/compliance.py).

    Admins/content_admins bypass — mirrors the existing role short-circuit in
    updateAckButtonState on the frontend (they never see the ack button either).
    """
    if user.role in (security.ROLE_SYSTEM_ADMIN, security.ROLE_CONTENT_ADMIN):
        return
    if not article.quiz_enabled:
        return
    passed = db.query(models.QuizAttempt).filter(
        models.QuizAttempt.article_id == article.id,
        models.QuizAttempt.article_version == article.version,
        models.QuizAttempt.user_id == user.id,
        models.QuizAttempt.passed == True,  # noqa: E712
    ).first()
    if not passed:
        raise HTTPException(status_code=403, detail="საჭიროა ქვიზის წარმატებით ჩაბარება წაკითხვის დასადასტურებლად")


@router.get("/api/articles/{article_id}/read-receipts", response_model=schemas.ArticleReadReceiptResponse)
def get_article_read_receipts(
    article_id: int,
    version: Optional[int] = None,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieve compliance read receipts for an article (Admins only)."""
    article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")

    required = db.query(models.RequiredReading).filter(
        models.RequiredReading.item_type == "article",
        models.RequiredReading.item_id == article_id
    ).first()
    due_date = required.due_date if required else None

    target_version = version if version is not None else article.version
    eligible_users = _get_eligible_operators(db, article)
    receipts = db.query(models.ArticleReadReceipt).filter(
        models.ArticleReadReceipt.article_id == article_id,
        models.ArticleReadReceipt.article_version == target_version
    ).all()

    # Map operator_id to the receipt for this specific version.
    receipt_map = {r.operator_id: r for r in receipts if r.operator_id is not None}

    processed_operator_ids = set()
    rows = []

    # 1. Process eligible active users
    for u in eligible_users:
        processed_operator_ids.add(u.id)
        receipt = receipt_map.get(u.id)
        if receipt:
            is_late = False
            status = "read"
            if due_date and receipt.read_at:
                r_at = receipt.read_at.replace(tzinfo=None) if receipt.read_at.tzinfo else receipt.read_at
                d_date = due_date.replace(tzinfo=None) if due_date.tzinfo else due_date
                is_late = r_at > d_date
                if is_late:
                    status = "late_read"
            rows.append({
                "operator_id": u.id,
                "operator_name": receipt.operator_name_snapshot,
                "operator_email": receipt.operator_email_snapshot,
                "department": receipt.operator_department_snapshot,
                "read_at": format_tbilisi_date(receipt.read_at),
                "article_version": receipt.article_version,
                "has_read": True,
                "is_late": is_late,
                "deadline": format_tbilisi_date(due_date),
                "status": status
            })
        else:
            rows.append({
                "operator_id": u.id,
                "operator_name": u.name,
                "operator_email": u.email,
                "department": u.department,
                "read_at": None,
                "article_version": None,
                "has_read": False,
                "is_late": False,
                "deadline": format_tbilisi_date(due_date),
                "status": "unread"
            })

    # 2. Add detached/orphaned snapshot rows
    for receipt in receipts:
        if receipt.operator_id is None or receipt.operator_id not in processed_operator_ids:
            is_late = False
            status = "read"
            if due_date and receipt.read_at:
                r_at = receipt.read_at.replace(tzinfo=None) if receipt.read_at.tzinfo else receipt.read_at
                d_date = due_date.replace(tzinfo=None) if due_date.tzinfo else due_date
                is_late = r_at > d_date
                if is_late:
                    status = "late_read"
            rows.append({
                "operator_id": receipt.operator_id,
                "operator_name": receipt.operator_name_snapshot,
                "operator_email": receipt.operator_email_snapshot,
                "department": receipt.operator_department_snapshot,
                "read_at": format_tbilisi_date(receipt.read_at),
                "article_version": receipt.article_version,
                "has_read": True,
                "is_late": is_late,
                "deadline": format_tbilisi_date(due_date),
                "status": status
            })

    return {
        "article_id": article.id,
        "article_title": article.title,
        "current_version": target_version,
        "receipts": rows
    }


def _upsert_read_receipt(db: Session, db_article: models.Article, user: models.User) -> models.ArticleReadReceipt:
    """Upsert the (article, current version, operator) read receipt — self-contained
    commit cycle with an IntegrityError retry, so a concurrent duplicate insert
    degrades to an update instead of a 500. Callers must commit their own work
    BEFORE calling: the retry path rolls the session back.
    """
    from sqlalchemy.exc import IntegrityError

    try:
        receipt = db.query(models.ArticleReadReceipt).filter(
            models.ArticleReadReceipt.article_id == db_article.id,
            models.ArticleReadReceipt.article_version == db_article.version,
            models.ArticleReadReceipt.operator_id == user.id
        ).first()

        if receipt:
            receipt.read_at = get_tbilisi_time()
            receipt.article_title_snapshot = db_article.title
            receipt.operator_name_snapshot = user.name
            receipt.operator_email_snapshot = user.email
            receipt.operator_department_snapshot = user.department
        else:
            receipt = models.ArticleReadReceipt(
                article_id=db_article.id,
                article_title_snapshot=db_article.title,
                article_version=db_article.version,
                operator_id=user.id,
                operator_name_snapshot=user.name,
                operator_email_snapshot=user.email,
                operator_department_snapshot=user.department,
                read_at=get_tbilisi_time()
            )
            db.add(receipt)
        db.commit()
        db.refresh(receipt)
    except IntegrityError:
        db.rollback()
        # Retry with select-and-update to avoid race conditions
        receipt = db.query(models.ArticleReadReceipt).filter(
            models.ArticleReadReceipt.article_id == db_article.id,
            models.ArticleReadReceipt.article_version == db_article.version,
            models.ArticleReadReceipt.operator_id == user.id
        ).first()
        if receipt:
            receipt.read_at = get_tbilisi_time()
            receipt.article_title_snapshot = db_article.title
            receipt.operator_name_snapshot = user.name
            receipt.operator_email_snapshot = user.email
            receipt.operator_department_snapshot = user.department
            db.commit()
            db.refresh(receipt)
        else:
            raise
    return receipt


@router.post("/api/articles/{article_id}/read-receipt")
def create_article_read_receipt(
    article_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Mark an article as read by the current user (upsert with integrity retry).

    Bridge to compliance: when this article is also a RequiredReading covering
    the user's department, the acknowledgment counts there too — the operator
    should never have to confirm the same article twice in two systems.
    """
    db_article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")

    _assert_article_visible(db_article, current_user)
    _check_quiz_gate(db, db_article, current_user)

    receipt = _upsert_read_receipt(db, db_article, current_user)

    # Compliance bridge: only fills gaps — an already-"read" ReadStatus keeps
    # its original read_at (first acknowledgment is what compliance measures).
    _dept_prefix = _split_dept_group(current_user.department)[0]
    covering = db.query(models.RequiredReading).filter(
        models.RequiredReading.item_type == "article",
        models.RequiredReading.item_id == article_id,
        models.RequiredReading.target_department.in_(
            [current_user.department, _dept_prefix, "All"]
        ),
    ).all()
    bridged = False
    for rr in covering:
        stat = db.query(models.ReadStatus).filter(
            models.ReadStatus.user_id == current_user.id,
            models.ReadStatus.required_reading_id == rr.id,
        ).first()
        if stat and stat.status == "read":
            continue
        if not stat:
            stat = models.ReadStatus(user_id=current_user.id, required_reading_id=rr.id)
            db.add(stat)
        stat.status = "read"
        stat.read_at = get_tbilisi_time()
        stat.operator_department_snapshot = current_user.department
        bridged = True
    if bridged:
        db.commit()

    return {
        "status": "success",
        "read_at": receipt.read_at,
        "article_version": receipt.article_version
    }


@router.get("/api/articles/{article_id}/read-receipt/me")
def get_my_article_read_receipt_status(
    article_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Fetch read receipt status for the current user and the current article version."""
    article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")
    _assert_article_visible(article, current_user)

    receipt = db.query(models.ArticleReadReceipt).filter(
        models.ArticleReadReceipt.article_id == article_id,
        models.ArticleReadReceipt.article_version == article.version,
        models.ArticleReadReceipt.operator_id == current_user.id
    ).first()

    if receipt:
        return {
            "has_read": True,
            "read_at": receipt.read_at,
            "article_version": receipt.article_version,
            "current_version": article.version
        }
    else:
        return {
            "has_read": False,
            "read_at": None,
            "article_version": None,
            "current_version": article.version
        }


@router.post("/api/articles/{article_id}/view")
def track_article_view(
    article_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Records a passive view: who opened the article, when, and which version.

    One ArticleViewLog row per open — no dedup, repeat views are signal.
    Distinct from the read-receipt flow, which records the operator's explicit
    "გავეცანი" acknowledgment. Snapshots (name/email/department/title/version)
    are taken at view time so the record survives later renames and moves.

    Access: Authenticated users (any active role).

    Raises:
        HTTPException: 404 Not Found if the article does not exist.
    """
    db_article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")

    db.add(models.ArticleViewLog(
        article_id=db_article.id,
        article_title_snapshot=db_article.title,
        article_version=db_article.version,
        operator_id=current_user.id,
        operator_name_snapshot=current_user.name,
        operator_email_snapshot=current_user.email,
        operator_department_snapshot=current_user.department,
        viewed_at=get_tbilisi_time(),
    ))
    db.commit()
    return {"status": "success"}


@router.get("/api/articles/{article_id}/views")
def get_article_views(
    article_id: int,
    version: Optional[int] = None,
    limit: int = 50,
    offset: int = 0,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Who viewed this article, when, and which version was on screen (admins).

    Passive views — the companion of GET .../read-receipts (explicit acks).
    Optional `version` narrows to one revision; omitted returns every version.
    """
    article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")

    filters = [models.ArticleViewLog.article_id == article_id]
    if version is not None:
        filters.append(models.ArticleViewLog.article_version == version)

    total_views = db.query(func.count(models.ArticleViewLog.id)).filter(*filters).scalar() or 0
    unique_viewers = (
        db.query(func.count(func.distinct(models.ArticleViewLog.operator_id)))
        .filter(*filters, models.ArticleViewLog.operator_id.isnot(None))
        .scalar() or 0
    )
    rows = (
        db.query(models.ArticleViewLog)
        .filter(*filters)
        .order_by(desc(models.ArticleViewLog.viewed_at))
        .offset(max(offset, 0))
        .limit(max(1, min(limit, 200)))
        .all()
    )
    return {
        "article_id": article_id,
        "current_version": article.version,
        "filter_version": version,
        "total_views": total_views,
        "unique_viewers": unique_viewers,
        "views": [
            {
                "operator_id": v.operator_id,
                "operator_name": v.operator_name_snapshot,
                "operator_email": v.operator_email_snapshot,
                "department": v.operator_department_snapshot,
                "article_version": v.article_version,
                "viewed_at": format_tbilisi_date(v.viewed_at),
            }
            for v in rows
        ],
    }


@router.get("/api/me/recently-viewed", response_model=list[schemas.RecentlyViewedItem])
def get_my_recently_viewed(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Returns the current user's most recently viewed articles (server-backed,
    cross-device — distinct from the client-only localStorage strip on the KB page).

    Reads ArticleViewLog. The inner join to Article both fetches the LIVE title
    (snapshots go stale on rename) and drops views of since-deleted articles
    (their article_id is SET NULL, so they simply don't join).
    """
    rows = (
        db.query(models.ArticleViewLog.article_id, models.Article.title, models.ArticleViewLog.viewed_at)
        .join(models.Article, models.ArticleViewLog.article_id == models.Article.id)
        .filter(models.ArticleViewLog.operator_id == current_user.id)
        .order_by(desc(models.ArticleViewLog.viewed_at))
        .limit(30)
        .all()
    )

    seen_ids: set[int] = set()
    items: list[dict] = []
    for article_id, title, viewed_at in rows:
        if article_id in seen_ids:
            continue
        seen_ids.add(article_id)
        items.append({"article_id": article_id, "title": title, "viewed_at": viewed_at})
        if len(items) >= 10:
            break
    return items


@router.post("/api/articles/{article_id}/feedback", response_model=schemas.KnowledgeFeedbackResponse)
def create_article_feedback(
    article_id: int,
    req: schemas.KnowledgeFeedbackCreate,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    raise HTTPException(status_code=410, detail="ხარვეზის რეპორტირება დეპრეკირებულია")


@router.get("/api/admin/feedback", response_model=list[schemas.KnowledgeFeedbackResponse])
def get_admin_feedback(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    raise HTTPException(status_code=410, detail="უკუკავშირის ნახვა დეპრეკირებულია")


@router.get("/api/articles/{article_id}/note", response_model=Optional[schemas.UserNoteResponse])
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
    article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")
    _assert_article_visible(article, current_user)

    note = db.query(models.UserNote).filter(
        models.UserNote.user_id == current_user.id,
        models.UserNote.article_id == article_id
    ).first()
    return note


@router.put("/api/articles/{article_id}/note", response_model=schemas.UserNoteResponse)
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
    article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")
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


@router.post("/api/articles/{article_id}/verify", response_model=schemas.ArticleResponse)
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
    article = get_or_404(db, models.Article, article_id, "სტატია ვერ მოიძებნა")

    article.last_verified_at = get_tbilisi_time()
    search_cache.clear()

    # Log in audit trail
    log_audit(db, admin_id=current_admin.id, action="VERIFY", item_type="article", item_id=article_id)
    db.commit()
    db.refresh(article)
    return article


@router.get("/api/admin/articles/stale")
def get_stale_articles(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Lists articles whose content has not been verified in over 180 days.

    Used by the admin dashboard to surface a proactive content health alert,
    ensuring operators never rely on outdated procedures.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        A list of stale article summaries with days-since-verification counts.
    """
    cutoff = get_tbilisi_time() - timedelta(days=180)
    stale = db.query(models.Article).filter(
        models.Article.last_verified_at < cutoff,
        models.Article.status == "published"
    ).order_by(models.Article.last_verified_at.asc()).all()

    return [
        {
            "id": a.id,
            "title": a.title,
            "target_departments": a.target_departments,
            "last_verified_at": a.last_verified_at,
            "days_stale": (get_tbilisi_time() - a.last_verified_at).days if a.last_verified_at else 999,
        }
        for a in stale
    ]


@router.get("/api/articles/{article_id}/related")
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
    if current_user.role not in security.CONTENT_ADMIN_ROLES:
        candidates = candidates.filter(
            models.Article.target_department_rows.any(
                models.ArticleTargetDepartment.department.in_([current_user.department, "All"])
            )
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
