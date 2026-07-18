import os
import asyncio
import logging
import logging.handlers
from contextlib import asynccontextmanager
from dataclasses import dataclass

from fastapi import (
    FastAPI,
    Request,
)
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from fastapi.concurrency import run_in_threadpool
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded

import audit_trail
import models
import security
from config import settings
from database import engine, SessionLocal, get_tbilisi_time
from state import (
    limiter,
    broker,
)

# ORM-level CREATE/UPDATE/DELETE auditing with deep diffs (idempotent).
audit_trail.register_listeners()

# Paths are resolved relative to THIS file, so the app works regardless of the
# directory uvicorn is launched from.
BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# ── Centralized logging ───────────────────────────────────────────────────
# Level comes from settings: production→INFO, development→DEBUG, LOG_LEVEL
# env var overrides both (config.resolve_log_level).
_LOG_FORMAT = '%(asctime)s [%(levelname)s] %(filename)s:%(lineno)d - %(message)s'
_log_level = getattr(logging, settings.LOG_LEVEL, logging.INFO)

_console_handler = logging.StreamHandler()
_console_handler.setLevel(_log_level)
_console_handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s [%(name)s] %(message)s"))
_handlers: list[logging.Handler] = [_console_handler]

# RotatingFileHandler is NOT multi-process safe: under gunicorn all 4 workers
# would rotate logs/magti_portal.log out from under each other and clobber
# writes. Multi-worker deployments therefore log to stdout only — Docker
# captures it and docker-compose.yml's json-file driver handles rotation —
# while the file handler remains a single-process/dev convenience.
_is_multi_worker = (
    any(k in os.environ for k in ("GUNICORN_CMD_ARGS", "WEB_CONCURRENCY", "UVICORN_WORKERS"))
    or os.getenv("WORKERS", "1") not in ("", "1")
)
if not _is_multi_worker:
    os.makedirs("logs", exist_ok=True)
    _rotating_handler = logging.handlers.RotatingFileHandler(
        "logs/magti_portal.log", maxBytes=10 * 1024 * 1024, backupCount=5
    )
    _rotating_handler.setLevel(_log_level)
    _rotating_handler.setFormatter(logging.Formatter(_LOG_FORMAT))
    _handlers.append(_rotating_handler)

# Root stays at WARNING so third-party libraries can't flood the app's logs;
# the app's own "magti" logger below gets the configured level.
logging.basicConfig(level=logging.WARNING, format=_LOG_FORMAT, handlers=_handlers)

# Module logger — replaces ad-hoc print() calls so operational messages get
# levels/timestamps and route through the app's stdio (captured by gunicorn/
# Docker) and, in single-process dev, the rotating file (propagate=False means
# root's handler list is otherwise unreachable from this logger).
logger = logging.getLogger("magti")
if not logger.handlers:
    for _h in _handlers:
        logger.addHandler(_h)
    logger.setLevel(_log_level)
    logger.propagate = False

# SQL echo — opt-in dev chaos-testing aid (LOG_SQL=true), never the default:
# at DEBUG it logs every query from every worker and rotates real errors out
# of the log file within hours under production traffic.
if settings.LOG_SQL:
    _sa_engine_logger = logging.getLogger("sqlalchemy.engine")
    for _h in _handlers:
        _sa_engine_logger.addHandler(_h)
    _sa_engine_logger.setLevel(logging.DEBUG)
    _sa_engine_logger.propagate = False

# Creates all database tables on startup — but only when RUN_INIT=1.
# Otherwise, 4 gunicorn workers all race and 3 fail with UniqueViolation on
# pg_type_typname_nsp_index when run against Postgres. The migrate service in
# docker-compose.yml runs once with RUN_INIT=1 before the app starts.
# In production, use Alembic migrations instead (esp. on PostgreSQL).
if os.getenv("RUN_INIT") == "1":
    models.Base.metadata.create_all(bind=engine)


def _lightweight_migrations() -> None:
    """Local-dev migrations for SQLite — adds missing columns when the codebase
    moves ahead of an existing DB.

    Idempotent: ALTER TABLE ... ADD COLUMN runs at most once per column.
    Production deployments should switch to Alembic; this is a developer-UX
    safety net so `uvicorn main:app` keeps working without manual ALTER TABLE
    after a `git pull`.
    """
    if not settings.is_sqlite:
        return  # PostgreSQL → use Alembic, not this helper.
    from sqlalchemy import text

    # New standalone tables — created here (not just via create_all(), which
    # only runs when RUN_INIT=1) so they exist on every plain `uvicorn --reload`
    # dev boot too. CREATE TABLE IF NOT EXISTS is naturally idempotent.
    create_tables = {
        "teams": """
            CREATE TABLE IF NOT EXISTS teams (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name VARCHAR NOT NULL UNIQUE,
                created_at DATETIME
            )
        """,
        "tags": """
            CREATE TABLE IF NOT EXISTS tags (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name VARCHAR NOT NULL UNIQUE,
                created_at DATETIME
            )
        """,
        "tags_mapping": """
            CREATE TABLE IF NOT EXISTS tags_mapping (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tag_id INTEGER NOT NULL REFERENCES tags(id),
                item_type VARCHAR NOT NULL,
                item_id INTEGER NOT NULL,
                UNIQUE(tag_id, item_type, item_id)
            )
        """,
        # Multi-department targeting for Article; coexists with the legacy
        # articles.target_department column (not dropped yet — see backfill below).
        "article_target_departments": """
            CREATE TABLE IF NOT EXISTS article_target_departments (
                article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
                department VARCHAR NOT NULL,
                PRIMARY KEY (article_id, department)
            )
        """,
        # Optional per-article knowledge-check (quiz_enabled toggle lives on
        # articles, added via add_cols below).
        "quiz_questions": """
            CREATE TABLE IF NOT EXISTS quiz_questions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
                question_text TEXT NOT NULL,
                position INTEGER DEFAULT 0
            )
        """,
        "quiz_answers": """
            CREATE TABLE IF NOT EXISTS quiz_answers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                question_id INTEGER NOT NULL REFERENCES quiz_questions(id) ON DELETE CASCADE,
                answer_text VARCHAR NOT NULL,
                is_correct BOOLEAN DEFAULT 0,
                position INTEGER DEFAULT 0
            )
        """,
        "quiz_attempts": """
            CREATE TABLE IF NOT EXISTS quiz_attempts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
                article_version INTEGER NOT NULL,
                user_id INTEGER NOT NULL REFERENCES users(id),
                attempt_number INTEGER NOT NULL,
                score INTEGER NOT NULL,
                total_questions INTEGER NOT NULL,
                passed BOOLEAN DEFAULT 0,
                created_at DATETIME
            )
        """,
        # Background export job status — DB-backed so it survives across the
        # 4 gunicorn workers in prod (see models.ExportJob).
        "export_jobs": """
            CREATE TABLE IF NOT EXISTS export_jobs (
                id VARCHAR PRIMARY KEY,
                status VARCHAR NOT NULL DEFAULT 'processing',
                path VARCHAR,
                expires_at FLOAT NOT NULL
            )
        """,
    }

    add_cols = {
        # table  -> [(column, definition)]
        "news": [
            ("attachment_url", "VARCHAR"),
            ("version", "INTEGER DEFAULT 1"),
            # Block 5: role-based content visibility.
            ("visible_to_tech_info", "BOOLEAN DEFAULT 1"),
            ("visible_to_service_center", "BOOLEAN DEFAULT 0"),
        ],
        # knowledge_feedback is missing a default status in some seeded DBs.
        "knowledge_feedback": [
            ("status", "VARCHAR DEFAULT 'open'"),
        ],
        "users": [
            ("phone", "VARCHAR"),
            ("permissions", "JSON"),
            # Team Statistics foundation (Block 1, Task 4).
            ("team_id", "INTEGER REFERENCES teams(id)"),
            # Block 5: self-referencing manager (team_lead) FK.
            ("manager_id", "INTEGER REFERENCES users(id)"),
        ],
        "search_logs": [
            ("has_results", "BOOLEAN DEFAULT 1"),
            # Exact result count alongside the existing has_results boolean.
            ("results_found", "INTEGER"),
        ],
        "categories": [
            ("pastel_color_class", "VARCHAR"),
            ("is_active", "BOOLEAN DEFAULT 1"),
        ],
        "audit_logs": [
            ("category", "VARCHAR"),
            ("details", "TEXT"),
        ],
        "video_instructions": [
            ("is_archived", "BOOLEAN DEFAULT 0"),
            ("tags", "VARCHAR"),
        ],
        "articles": [
            # Block 5: role-based content visibility.
            ("visible_to_tech_info", "BOOLEAN DEFAULT 1"),
            ("visible_to_service_center", "BOOLEAN DEFAULT 0"),
            ("quiz_enabled", "BOOLEAN DEFAULT 0"),
        ],
    }
    with engine.begin() as conn:
        for table, ddl in create_tables.items():
            try:
                conn.exec_driver_sql(ddl)
            except Exception as e:
                logger.warning("[migration] failed creating table %s: %s", table, e)

        for table, cols in add_cols.items():
            try:
                existing = {row[1] for row in conn.exec_driver_sql(f"PRAGMA table_info({table})").fetchall()}
            except Exception:
                # table not present yet; create_all() above will handle it.
                continue
            for col, definition in cols:
                if col not in existing:
                    try:
                        conn.exec_driver_sql(f"ALTER TABLE {table} ADD COLUMN {col} {definition}")
                    except Exception as e:
                        # Don't break startup on a missing optional column; just log.
                        logger.warning("[migration] skipped %s.%s: %s", table, col, e)

        # Backfill category on pre-existing audit_logs rows (new rows get this
        # automatically from the AuditLog.before_insert hook in models.py).
        # WHERE category IS NULL makes this a no-op after the first run.
        try:
            conn.exec_driver_sql("""
                UPDATE audit_logs SET category = CASE
                    WHEN action IN ('LOGIN','LOGIN_SSO','PASSWORD_CHANGE','PASSWORD_RESET',
                                     'PASSWORD_RESET_REQUEST','CREATE_USER','UPDATE_PERMISSIONS')
                         OR action LIKE 'UPDATE_STATUS_TO_%' THEN 'SECURITY'
                    WHEN action IN ('VIEW','MARK_READ','SEND_MESSAGE') THEN 'USER'
                    WHEN item_type IN ('news','category','article','video','feedback','required_reading') THEN 'CONTENT'
                    WHEN item_type = 'user' THEN 'USER'
                    ELSE 'SYSTEM'
                END
                WHERE category IS NULL
            """)
        except Exception as e:
            logger.warning("[migration] audit_logs category backfill skipped: %s", e)

        # Backfill results_found from the existing has_results boolean as a
        # best-effort placeholder (1 or 0) — exact historical counts weren't
        # recorded. New rows get an exact count from the call sites going forward.
        try:
            conn.exec_driver_sql("""
                UPDATE search_logs SET results_found = CASE WHEN has_results THEN 1 ELSE 0 END
                WHERE results_found IS NULL
            """)
        except Exception as e:
            logger.warning("[migration] search_logs results_found backfill skipped: %s", e)

        # Backfill tags/tags_mapping from the existing flat articles.tags text
        # column. Only runs while tags_mapping is empty, so it's a one-time
        # normalize rather than a per-boot resync.
        try:
            mapping_count = conn.exec_driver_sql("SELECT COUNT(*) FROM tags_mapping").scalar()
            if mapping_count == 0:
                rows = conn.exec_driver_sql(
                    "SELECT id, tags FROM articles WHERE tags IS NOT NULL AND tags != ''"
                ).fetchall()
                for article_id, tags_csv in rows:
                    for raw_name in tags_csv.split(","):
                        name = raw_name.strip()
                        if not name:
                            continue
                        conn.execute(
                            text("INSERT OR IGNORE INTO tags (name, created_at) VALUES (:n, :t)"),
                            {"n": name, "t": get_tbilisi_time()},
                        )
                        conn.execute(
                            text(
                                "INSERT OR IGNORE INTO tags_mapping (tag_id, item_type, item_id) "
                                "SELECT id, 'article', :iid FROM tags WHERE name = :n"
                            ),
                            {"n": name, "iid": article_id},
                        )
        except Exception as e:
            logger.warning("[migration] tags backfill skipped: %s", e)

        # Backfill article_target_departments from the legacy single-value
        # articles.target_department column. NOT EXISTS guard makes this a
        # no-op after the first run, and on every subsequent boot once an
        # article already has rows here.
        try:
            conn.exec_driver_sql("""
                INSERT INTO article_target_departments (article_id, department)
                SELECT id, target_department FROM articles
                WHERE target_department IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM article_target_departments
                      WHERE article_id = articles.id
                  )
            """)
        except Exception as e:
            logger.warning("[migration] article_target_departments backfill skipped: %s", e)

        # Batch-normalize existing video URLs in SQLite database
        try:
            rows = conn.exec_driver_sql("SELECT id, video_url FROM video_instructions").fetchall()
            from routers.videos import normalize_youtube_url as _normalize_youtube_url

            for vid, original_url in rows:
                if original_url:
                    normalized_url = _normalize_youtube_url(original_url)
                    if normalized_url != original_url:
                        conn.execute(
                            text("UPDATE video_instructions SET video_url = :url WHERE id = :id"),
                            {"url": normalized_url, "id": vid}
                        )
                        logger.info("[migration] Normalized video %s URL: %s -> %s", vid, original_url, normalized_url)
        except Exception as e:
            logger.warning("[migration] Video URLs normalization skipped or failed: %s", e)


_lightweight_migrations()



# _inflight / single_flight (request coalescing) now live in
# routers/search.py — the only consumer.

# ── Batched async log writer ──────────────────────────────────────────────
# A single background task drains an asyncio.Queue and flushes log rows in
# bulk inserts. One writer, one transaction per batch — SQLite's single-
# writer lock is no longer fought over by hundreds of concurrent requests.

@dataclass(slots=True)
class LogItem:
    kind: str          # "search" | "audit"
    payload: dict      # column-name -> value mapping for bulk_insert_mappings


LOG_QUEUE_MAXSIZE = 10_000
LOG_BATCH_SIZE = 200
LOG_FLUSH_INTERVAL = 1.0  # seconds
_LOG_SHUTDOWN = object()  # sentinel for graceful drain on app shutdown


def _write_log_batch_sync(items: list[LogItem]) -> None:
    """Runs in the threadpool. One transaction, one bulk insert per kind."""
    search_rows = [i.payload for i in items if i.kind == "search"]
    audit_rows = [i.payload for i in items if i.kind == "audit"]
    with SessionLocal() as session:
        if search_rows:
            session.bulk_insert_mappings(models.SearchLog, search_rows)
        if audit_rows:
            session.bulk_insert_mappings(models.AuditLog, audit_rows)
        session.commit()


async def _log_writer(queue: asyncio.Queue) -> None:
    """Drain the queue, batching by size or time, flush via threadpool."""
    while True:
        try:
            first = await queue.get()
        except asyncio.CancelledError:
            return
        if first is _LOG_SHUTDOWN:
            return

        batch: list[LogItem] = [first]
        loop = asyncio.get_running_loop()
        deadline = loop.time() + LOG_FLUSH_INTERVAL

        while len(batch) < LOG_BATCH_SIZE:
            timeout = deadline - loop.time()
            if timeout <= 0:
                break
            try:
                item = await asyncio.wait_for(queue.get(), timeout=timeout)
            except asyncio.TimeoutError:
                break
            if item is _LOG_SHUTDOWN:
                try:
                    await run_in_threadpool(_write_log_batch_sync, batch)
                except Exception as e:
                    logger.error("[log_writer] final flush failed (size=%d): %s", len(batch), e)
                return
            batch.append(item)

        try:
            await run_in_threadpool(_write_log_batch_sync, batch)
        except Exception as e:
            # Never let logging failures kill the writer loop.
            logger.error("[log_writer] flush failed (size=%d): %s", len(batch), e)


def enqueue_log(request: Request, item: LogItem) -> None:
    """Non-blocking: drop on full rather than back-pressure the request path."""
    queue = getattr(request.app.state, "log_queue", None)
    if queue is None:
        return
    try:
        queue.put_nowait(item)
    except asyncio.QueueFull:
        # In production wire this to a metric/counter.
        pass


@asynccontextmanager
async def lifespan(app: "FastAPI"):
    """App-lifecycle plumbing: start the queue writer, set the broker's loop.
    Threadpool stays at the anyio default (40) — with sync SQLAlchemy + SQLite,
    every extra worker is a potential writer-lock contender. Tested 80 and 200;
    both made article-view latency worse. The default is the sweet spot here."""
    app.state.log_queue = asyncio.Queue(maxsize=LOG_QUEUE_MAXSIZE)
    writer_task = asyncio.create_task(_log_writer(app.state.log_queue))
    # broker is created at module load (below); by the time lifespan runs it exists.
    broker.set_loop(asyncio.get_running_loop())
    logging.info("FastAPI Magti Portal application bootstrap success")
    try:
        yield
    finally:
        await app.state.log_queue.put(_LOG_SHUTDOWN)
        try:
            await asyncio.wait_for(writer_task, timeout=5.0)
        except asyncio.TimeoutError:
            writer_task.cancel()


app = FastAPI(
    title="Magti Internal Portal API",
    lifespan=lifespan,
    docs_url=None if settings.is_production else "/docs",
    redoc_url=None if settings.is_production else "/redoc",
    openapi_url=None if settings.is_production else "/openapi.json",
)

# Rate limiting — brute-force / credential-stuffing mitigation for the
# auth endpoints. Per-IP, in-memory (no Redis backend needed at this scale).
# `limiter` itself lives in state.py (imported above) — it's used in
# `@limiter.limit(...)` decorators, which evaluate at module-import time.
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# Ensure the uploads directory exists
os.makedirs(settings.UPLOAD_DIR, exist_ok=True)

# Serve static assets (JS, CSS) from the 'static' directory.
# Using an absolute path is more robust against Current Working Directory issues.
app.mount("/static", StaticFiles(directory=os.path.join(BASE_DIR, "static")), name="static")

# Serve uploaded files. X-Content-Type-Options: nosniff (added by the middleware
# below) stops the browser from re-interpreting an upload as active content.
app.mount("/uploads", StaticFiles(directory=settings.UPLOAD_DIR), name="uploads")

# CORS — origins are env-driven (see config.CORS_ORIGINS / .env).
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Domain routers — each module owns one area's routes (auth, articles,
# users, stats, etc.); main.py just wires them in here.
from routers import categories as categories_router
app.include_router(categories_router.router)
from routers import favorites as favorites_router
app.include_router(favorites_router.router)
from routers import platform as platform_router
app.include_router(platform_router.router)
from routers import audit_logs as audit_logs_router
app.include_router(audit_logs_router.router)
from routers import search as search_router
app.include_router(search_router.router)
from routers import compliance as compliance_router
app.include_router(compliance_router.router)
from routers import messaging as messaging_router
app.include_router(messaging_router.router)
from routers import videos as videos_router
app.include_router(videos_router.router)
from routers import exports as exports_router
app.include_router(exports_router.router)
from routers import news as news_router
app.include_router(news_router.router)
from routers import auth as auth_router
app.include_router(auth_router.router)
from routers import stats as stats_router
app.include_router(stats_router.router)
from routers import users as users_router
app.include_router(users_router.router)
from routers import articles as articles_router
app.include_router(articles_router.router)


@app.middleware("http")
async def security_headers(request: Request, call_next):
    """Baseline hardening headers applied to every response.

    Includes a Content-Security-Policy that is permissive enough for the
    current single-file layout (inline `<script>`/`<style>` blocks in
    base-layout.html + login.html, plus the Tailwind/Chart.js/Font Awesome
    CDNs). 'unsafe-inline' for scripts is the cost of not having a build step
    today — once base-layout.html is split into ES modules (architectural
    plan, Phase 2.1), tighten to a nonce-based policy.
    """
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    if settings.is_production:
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
    # CSP — defence-in-depth against any injection sink we miss. Sinks that
    # *can* still inject inline markup (e.g. an unescaped `${title}`) are
    # blocked from running scripts by 'object-src none' + 'base-uri self', and
    # by the absence of permitted origins for plugins/forms outside our own.
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; "
        "script-src 'self' 'unsafe-inline' "
        "https://cdn.jsdelivr.net "
        "https://cdnjs.cloudflare.com; "
        "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; "
        "font-src 'self' data: https://cdnjs.cloudflare.com; "
        # Article content was migrated with /uploads/ rewrites, but some
        # legacy/external imagery may still resolve via https — allow it
        # read-only. data: covers SVG icon-fonts.
        "img-src 'self' data: https:; "
        "connect-src 'self'; "
        "frame-src 'self' https://www.youtube.com https://youtube.com https://www.youtube-nocookie.com https://youtube-nocookie.com; "
        "frame-ancestors 'none'; "
        "base-uri 'self'; "
        "object-src 'none'; "
        "form-action 'self'"
    )
    return response


@app.middleware("http")
async def actor_context_middleware(request: Request, call_next):
    """Binds the request's authenticated identity (JWT ``sub``) to a ContextVar
    so audit_trail's ORM listeners can attribute writes to an actor.

    This has to live in async middleware: sync dependencies (including
    security.get_current_user) run in a threadpool with a *copied* context, so
    a ContextVar set there is lost when the call returns. A value set here, in
    the request's own context, is visible in every downstream copy. Signature
    validation stays get_current_user's job — a forged token simply 401s there
    before any audited write can happen.
    """
    email = None
    # Same precedence as security._candidate_tokens: try the Authorization
    # header first (so a caller deliberately acting as a different identity
    # than a stale cookie still wins), then the httpOnly cookie.
    auth = request.headers.get("authorization", "")
    bearer_token = auth[7:] if auth.lower().startswith("bearer ") else None
    for token in (bearer_token, request.cookies.get("access_token")):
        if not token:
            continue
        try:
            payload = security.jwt.decode(
                token, security.SECRET_KEY, algorithms=[security.ALGORITHM]
            )
            email = payload.get("sub")
            if email:
                break
        except Exception:
            continue
    ctx_token = audit_trail.current_actor_email.set(email)
    # Same request.client.host convention LOGIN_FAILED already uses below,
    # generalized here for every audited write via audit_trail's after_begin
    # event. Same pre-existing reverse-proxy limitation that capture already
    # has (captures the proxy's IP if Magti sits behind one).
    ip = request.client.host if request.client else None
    ua = request.headers.get("user-agent") or None
    if ua:
        ua = ua[:500]
    ip_token = audit_trail.current_client_ip.set(ip)
    ua_token = audit_trail.current_user_agent.set(ua)
    try:
        return await call_next(request)
    finally:
        audit_trail.current_actor_email.reset(ctx_token)
        audit_trail.current_client_ip.reset(ip_token)
        audit_trail.current_user_agent.reset(ua_token)


@app.middleware("http")
async def catch_unhandled_exceptions(request: Request, call_next):
    """Last-resort safety net — log and return generic 500."""
    try:
        return await call_next(request)
    except Exception:
        try:
            logger.exception("Internal Server Error intercepted")
        except Exception:
            pass
        return JSONResponse(status_code=500, content={"detail": "Internal Server Error"})

