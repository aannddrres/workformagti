"""Search routes: KB article search, portal-wide global search, and per-user
search history — Phase 5 of the main.py monolith split.
"""
import asyncio
from typing import Optional

from fastapi import APIRouter, Depends, Request
from fastapi.concurrency import run_in_threadpool
from sqlalchemy import and_, case, desc, or_
from sqlalchemy.orm import Session

import models
import schemas
import security
from database import SessionLocal, get_db, get_tbilisi_time
from state import search_cache

router = APIRouter(tags=["search"])

# ── Request coalescing (single-flight) ────────────────────────────────────
# When N requests for the same cache key arrive simultaneously and the cache
# is empty, only the first does the work; the other N-1 await the same future.
# Module-level dict is safe because asyncio runs single-threaded on one loop.
# Only consumed by global_search_all below.
_inflight: dict[str, asyncio.Future] = {}


async def single_flight(key: str, factory):
    """Coalesce concurrent calls for the same key into one execution."""
    fut = _inflight.get(key)
    if fut is None:
        fut = asyncio.ensure_future(factory())
        _inflight[key] = fut
        fut.add_done_callback(lambda _f: _inflight.pop(key, None))
    return await asyncio.shield(fut)


@router.get("/api/search", response_model=list[schemas.ArticleResponse])
def global_search(
    q: str,
    category_id: Optional[int] = None,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Searches KB articles matching a query term in title, content, or tags.

    Regular operators and managers search only published/scheduled articles for their department or "All".
    Administrators and content admins search all articles. Logs the search term for analytics.

    Access: Authenticated users (any active role).

    Args:
        q: The search query string.
        category_id: Optional category ID to narrow the search.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A list of matching Article database rows.
    """
    words = [w for w in q.split() if w.strip()]
    if not words:
        if category_id is not None:
            query = db.query(models.Article)
        else:
            return []
    else:
        conditions = []
        for w in words:
            pattern = f"%{w}%"
            conditions.append(or_(
                models.Article.title.ilike(pattern),
                models.Article.content.ilike(pattern),
                models.Article.tags.ilike(pattern)
            ))

        # Relevance scoring logic for Articles: TITLE(10) > TAGS(5) > CONTENT(1)
        article_score = sum(
            case(
                (models.Article.title.ilike(f"%{w}%"), 10),
                (models.Article.tags.ilike(f"%{w}%"), 5),
                (models.Article.content.ilike(f"%{w}%"), 1),
                else_=0
            ) for w in words
        )
        query = db.query(models.Article).filter(and_(*conditions)).order_by(desc(article_score))

    # Admins manage content across all departments, so they search everything;
    # regular users only search published content for their department
    if current_user.role not in security.CONTENT_ADMIN_ROLES:
        now = get_tbilisi_time()
        query = query.filter(
            models.Article.target_department_rows.any(
                models.ArticleTargetDepartment.department.in_([current_user.department, "All"])
            ),
            or_(
                models.Article.status == "published",
                and_(
                    models.Article.status == "scheduled",
                    models.Article.published_at <= now
                )
            )
        )

    if category_id is not None:
        query = query.filter(models.Article.category_id == category_id)

    articles = query.all()

    # Log the search term for analytics if results were found and query length >= 3
    # Normalize to lowercase to ensure clean grouping in popular searches
    norm_q = q.strip().lower()
    if len(articles) > 0 and len(norm_q) >= 3 and not current_user.email.startswith("test_operator_"):
        search_log = models.SearchLog(
            user_id=current_user.id,
            search_term=norm_q,
            results_found=len(articles),
        )
        db.add(search_log)
        db.commit()

    return articles

def _run_global_search_sync(q: str, is_admin: bool, user_dept: str) -> dict:
    """Synchronous SQLAlchemy work, run via run_in_threadpool from the endpoint."""
    words = [w for w in q.split() if w.strip()]
    if not words:
        return {"articles": [], "news": [], "videos": []}

    dept_filter = [user_dept, "All"]
    article_conds, news_conds, video_conds = [], [], []
    for w in words:
        pattern = f"%{w}%"
        article_conds.append(or_(
            models.Article.title.ilike(pattern),
            models.Article.content.ilike(pattern),
            models.Article.tags.ilike(pattern),
        ))
        news_conds.append(or_(
            models.News.title.ilike(pattern),
            models.News.content.ilike(pattern),
        ))
        video_conds.append(or_(
            models.VideoInstruction.title.ilike(pattern),
            models.VideoInstruction.category.ilike(pattern),
        ))

    with SessionLocal() as db:
        # Relevance scoring logic for Articles: TITLE(10) > TAGS(5) > CONTENT(1)
        article_score = sum(
            case(
                (models.Article.title.ilike(f"%{w}%"), 10),
                (models.Article.tags.ilike(f"%{w}%"), 5),
                (models.Article.content.ilike(f"%{w}%"), 1),
                else_=0
            ) for w in words
        )
        articles = db.query(models.Article).filter(and_(*article_conds)).order_by(desc(article_score))
        if not is_admin:
            now = get_tbilisi_time()
            articles = articles.filter(
                models.Article.target_department_rows.any(
                    models.ArticleTargetDepartment.department.in_(dept_filter)
                ),
                or_(
                    models.Article.status == "published",
                    and_(
                        models.Article.status == "scheduled",
                        models.Article.published_at <= now,
                    ),
                ),
            )

        # Relevance scoring logic for News: TITLE(3) > CONTENT(1)
        news_score = sum(
            case(
                (models.News.title.ilike(f"%{w}%"), 3),
                (models.News.content.ilike(f"%{w}%"), 1),
                else_=0
            ) for w in words
        )
        news = db.query(models.News).filter(and_(*news_conds)).order_by(desc(news_score))
        if not is_admin:
            news = news.filter(models.News.target_department.in_(dept_filter))

        videos = db.query(models.VideoInstruction).filter(and_(*video_conds))
        if not is_admin:
            videos = videos.filter(
                models.VideoInstruction.target_department.in_(dept_filter),
                models.VideoInstruction.is_archived == False,  # noqa: E712
            )

        articles_list = articles.limit(8).all()
        news_list = news.limit(5).all()
        videos_list = videos.limit(5).all()

        return {
            "articles": [schemas.ArticleSummaryResponse.model_validate(a).model_dump() for a in articles_list],
            "news": [schemas.NewsSummaryResponse.model_validate(n).model_dump() for n in news_list],
            "videos": [schemas.VideoInstructionResponse.model_validate(v).model_dump() for v in videos_list],
        }


@router.get("/api/search/global", response_model=schemas.GlobalSearchResponse)
async def global_search_all(
    request: Request,
    q: str,
    current_user: models.User = Depends(security.get_current_user),
):
    """Portal-wide search across articles, news, and videos in one response.

    60-second TTL cache + single-flight: identical concurrent queries are coalesced
    into a single DB hit. SearchLog writes are queued for batch insert so the
    request path never blocks on SQLite's writer lock.

    Access: Authenticated users (any active role).
    """
    # Lazy import: enqueue_log/LogItem are part of main.py's lifespan-bound
    # log-queue writer (app.state.log_queue is set up in main's lifespan()),
    # so they stay there rather than moving into a router. Deferred to call
    # time to avoid any top-level circular import with main's include_router.
    import main

    cache_key = f"search:{q}:{current_user.role}:{current_user.department}"
    is_admin = current_user.role in security.CONTENT_ADMIN_ROLES
    user_dept = current_user.department

    norm_q = q.strip().lower()

    cached = search_cache.get(cache_key)
    if cached is not None:
        results_found = len(cached.get("articles", [])) + len(cached.get("news", [])) + len(cached.get("videos", []))
        if len(norm_q) >= 3 and not current_user.email.startswith("test_operator_"):
            main.enqueue_log(request, main.LogItem("search", {
                "user_id": current_user.id,
                "search_term": norm_q,
                "has_results": results_found > 0,
                "results_found": results_found,
            }))
        return cached

    async def factory():
        result = await run_in_threadpool(_run_global_search_sync, q, is_admin, user_dept)
        search_cache.set(cache_key, result)
        return result

    result = await single_flight(cache_key, factory)
    results_found = len(result.get("articles", [])) + len(result.get("news", [])) + len(result.get("videos", []))
    if len(norm_q) >= 3 and not current_user.email.startswith("test_operator_"):
        main.enqueue_log(request, main.LogItem("search", {
            "user_id": current_user.id,
            "search_term": norm_q,
            "has_results": results_found > 0,
            "results_found": results_found,
        }))
    return result

@router.get("/api/search/history")
def get_search_history(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves the recent search query history for the logged-in user.

    Access: Authenticated users (any active role).

    Args:
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A list of search history objects.
    """
    logs = db.query(models.SearchLog).filter(
        models.SearchLog.user_id == current_user.id
    ).order_by(desc(models.SearchLog.timestamp)).limit(50).all()

    return [
        {
            "id": log.id,
            "search_term": log.search_term,
            "timestamp": log.timestamp
        }
        for log in logs
    ]
