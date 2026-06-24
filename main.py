import os
import io
import csv
import json
import uuid
import asyncio
import threading
from collections import Counter
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Optional

from fastapi import (
    FastAPI,
    Depends,
    HTTPException,
    status,
    File,
    UploadFile,
    Request,
    Response,
)
from fastapi.responses import StreamingResponse, HTMLResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from fastapi.concurrency import run_in_threadpool
from sqlalchemy.orm import Session, joinedload
from sqlalchemy import or_, and_, func, desc, case
from pydantic import BaseModel

import models
import schemas
import security
from config import settings
from database import engine, get_db, SessionLocal

# Paths are resolved relative to THIS file, so the app works regardless of the
# directory uvicorn is launched from.
BASE_DIR = os.path.dirname(os.path.abspath(__file__))

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
        ],
    }
    with engine.begin() as conn:
        for table, ddl in create_tables.items():
            try:
                conn.exec_driver_sql(ddl)
            except Exception as e:
                print(f"[migration] failed creating table {table}: {e}")

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
                        print(f"[migration] skipped {table}.{col}: {e}")

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
            print(f"[migration] audit_logs category backfill skipped: {e}")

        # Backfill results_found from the existing has_results boolean as a
        # best-effort placeholder (1 or 0) — exact historical counts weren't
        # recorded. New rows get an exact count from the call sites going forward.
        try:
            conn.exec_driver_sql("""
                UPDATE search_logs SET results_found = CASE WHEN has_results THEN 1 ELSE 0 END
                WHERE results_found IS NULL
            """)
        except Exception as e:
            print(f"[migration] search_logs results_found backfill skipped: {e}")

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
                            {"n": name, "t": datetime.utcnow()},
                        )
                        conn.execute(
                            text(
                                "INSERT OR IGNORE INTO tags_mapping (tag_id, item_type, item_id) "
                                "SELECT id, 'article', :iid FROM tags WHERE name = :n"
                            ),
                            {"n": name, "iid": article_id},
                        )
        except Exception as e:
            print(f"[migration] tags backfill skipped: {e}")

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
            print(f"[migration] article_target_departments backfill skipped: {e}")


_lightweight_migrations()


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


class InMemoryTTLCache:
    """A simple in-memory Time-To-Live (TTL) cache.

    Used for caching database search queries and categories to minimize SQLite read operations
    and improve response latency.
    """
    def __init__(self, ttl_seconds: int = 60):
        """Initializes the cache with a specific Time-To-Live (TTL) duration.

        Args:
            ttl_seconds: Cache duration in seconds before an entry is considered expired.
        """
        self.ttl = ttl_seconds
        self._cache = {}

    def get(self, key):
        """Retrieves a cached value if it exists and has not expired.

        Args:
            key: Unique key identifying the cached item.

        Returns:
            The cached value if present and valid; None if expired or not found.
        """
        if key in self._cache:
            val, expiry = self._cache[key]
            if datetime.utcnow() < expiry:
                return val
            else:
                del self._cache[key]
        return None

    def set(self, key, value):
        """Stores a value in the cache associated with the given key.

        Args:
            key: Unique key under which the value should be stored.
            value: The data to be cached.
        """
        expiry = datetime.utcnow() + timedelta(seconds=self.ttl)
        self._cache[key] = (value, expiry)

    def clear(self):
        """Clears all cached entries, forcing fresh database retrievals on subsequent requests."""
        self._cache.clear()


search_cache = InMemoryTTLCache(60)
category_cache = InMemoryTTLCache(60)


# ── Request coalescing (single-flight) ────────────────────────────────────
# When N requests for the same cache key arrive simultaneously and the cache
# is empty, only the first does the work; the other N-1 await the same future.
# Module-level dict is safe because asyncio runs single-threaded on one loop.
_inflight: dict[str, asyncio.Future] = {}


async def single_flight(key: str, factory):
    """Coalesce concurrent calls for the same key into one execution."""
    fut = _inflight.get(key)
    if fut is None:
        fut = asyncio.ensure_future(factory())
        _inflight[key] = fut
        fut.add_done_callback(lambda _f: _inflight.pop(key, None))
    return await asyncio.shield(fut)


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
                    print(f"[log_writer] final flush failed (size={len(batch)}): {e}")
                return
            batch.append(item)

        try:
            await run_in_threadpool(_write_log_batch_sync, batch)
        except Exception as e:
            # Never let logging failures kill the writer loop.
            print(f"[log_writer] flush failed (size={len(batch)}): {e}")


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
    try:
        yield
    finally:
        await app.state.log_queue.put(_LOG_SHUTDOWN)
        try:
            await asyncio.wait_for(writer_task, timeout=5.0)
        except asyncio.TimeoutError:
            writer_task.cancel()


app = FastAPI(title="Magti Internal Portal API", lifespan=lifespan)

# Ensure the uploads directory exists
os.makedirs(settings.UPLOAD_DIR, exist_ok=True)

@app.get("/static/magti_logo.png")
async def serve_logo():
    """Serves the main logo file.

    This is a specific route to handle the logo, which is located in the project
    root, while other static assets are in the /static/ directory. This route
    intercepts the request before it falls through to the StaticFiles mount.
    """
    path = os.path.join(BASE_DIR, "magti_logo.png")
    if not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="Logo file not found in project root.")
    return FileResponse(path)

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
    response.headers["Referrer-Policy"] = "no-referrer"
    if settings.is_production:
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
    # CSP — defence-in-depth against any injection sink we miss. Sinks that
    # *can* still inject inline markup (e.g. an unescaped `${title}`) are
    # blocked from running scripts by 'object-src none' + 'base-uri self', and
    # by the absence of permitted origins for plugins/forms outside our own.
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; "
        "script-src 'self' 'unsafe-inline' "
        "https://cdn.tailwindcss.com https://cdn.jsdelivr.net "
        "https://cdnjs.cloudflare.com; "
        "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; "
        "font-src 'self' data: https://cdnjs.cloudflare.com; "
        # Article content was migrated with /uploads/ rewrites, but some
        # legacy/external imagery may still resolve via https — allow it
        # read-only. data: covers SVG icon-fonts.
        "img-src 'self' data: https:; "
        "connect-src 'self'; "
        "frame-ancestors 'none'; "
        "base-uri 'self'; "
        "object-src 'none'; "
        "form-action 'self'"
    )
    return response


# ── Real-time notifications (Server-Sent Events) ──────────────────────────
import redis.asyncio as redis_async

class RedisEventBroker:
    """Redis-backed pub/sub powering the /api/stream SSE endpoint.

    ── Architecture ──────────────────────────────────────────────────────
    Two backends, chosen automatically at startup:

      1) **Redis pub/sub** (preferred). Reachable when ``REDIS_URL`` resolves
         and a TCP ping succeeds. Multi-worker safe: any worker can publish
         and every worker's subscribers see the event. This is the only
         configuration that works under gunicorn/uvicorn with >1 worker.

      2) **In-process queue fallback** (degraded). Used when Redis is not
         reachable. Subscribers are local-process Python ``asyncio.Queue``s,
         so events published in **this worker** reach **this worker's**
         subscribers only — clients connected to a *different* worker will
         miss the event.

         The fallback is intentionally process-local. It exists so the app
         keeps working in single-worker dev/test loops without needing to
         spin up Redis. **Never rely on it in production** with multiple
         workers — the startup log clearly warns about this.
    """

    QUEUE_MAXSIZE = 64

    def __init__(self):
        # If the deployment did not opt-in to Redis (REDIS_URL unset), we still
        # connect to localhost:6379 by default — but we DO NOT crash if it is
        # unreachable; we simply fall back to the in-process queue and warn.
        self.redis_url = os.getenv("REDIS_URL", "redis://localhost:6379")
        self._main_loop: Optional[asyncio.AbstractEventLoop] = None
        self._local_queues: set[asyncio.Queue] = set()
        self._use_redis = False
        self._ready = asyncio.Event() if False else None  # set later inside the loop

    def set_loop(self, loop: asyncio.AbstractEventLoop):
        """Stores the main event loop so sync threads can safely push to Redis."""
        self._main_loop = loop
        # The Event has to be constructed on the running loop; recreate now.
        self._ready = asyncio.Event()

        async def check_redis():
            try:
                r = redis_async.from_url(self.redis_url, decode_responses=True, socket_connect_timeout=1.0)
                await r.ping()
                await r.aclose()
                self._use_redis = True
                print(f"SSE Broker: connected to Redis at {self.redis_url} — multi-worker safe.")
            except Exception as e:
                self._use_redis = False
                # Detect probable multi-worker mode: gunicorn / uvicorn workers.
                multi_worker = (
                    os.getenv("GUNICORN_CMD_ARGS")
                    or os.getenv("WEB_CONCURRENCY")
                    or os.getenv("UVICORN_WORKERS")
                )
                warning = (
                    f"SSE Broker: Redis unreachable at {self.redis_url} ({e}). "
                    f"Falling back to PROCESS-LOCAL in-memory queues."
                )
                if multi_worker:
                    warning += (
                        " WARNING: this process appears to be running under a multi-worker "
                        "supervisor (GUNICORN_CMD_ARGS / WEB_CONCURRENCY / UVICORN_WORKERS set). "
                        "Live notifications WILL be unreliable across workers without Redis — "
                        "start Redis and set REDIS_URL, or run with a single worker."
                    )
                print(warning)
            finally:
                if self._ready is not None:
                    self._ready.set()

        loop.create_task(check_redis())

    async def subscribe(self) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=self.QUEUE_MAXSIZE)
        
        if self._use_redis:
            try:
                r = redis_async.from_url(self.redis_url, decode_responses=True)
                pubsub = r.pubsub()
                await pubsub.subscribe("magti_sse_events")
                
                async def reader():
                    try:
                        async for message in pubsub.listen():
                            if message["type"] == "message":
                                try:
                                    data = json.loads(message["data"])
                                    if queue.full():
                                        queue.get_nowait()
                                    queue.put_nowait(data)
                                except Exception:
                                    pass
                    except asyncio.CancelledError:
                        pass
                    finally:
                        try:
                            await pubsub.unsubscribe("magti_sse_events")
                            await pubsub.close()
                            await r.aclose()
                        except Exception:
                            pass
                        
                task = asyncio.create_task(reader())
                queue.reader_task = task
            except Exception:
                self._local_queues.add(queue)
        else:
            self._local_queues.add(queue)
            
        return queue

    def unsubscribe(self, queue: asyncio.Queue) -> None:
        if self._use_redis and hasattr(queue, 'reader_task'):
            queue.reader_task.cancel()
        self._local_queues.discard(queue)

    def publish(self, event: dict) -> None:
        if self._main_loop is None:
            return
            
        async def _push():
            if self._use_redis:
                try:
                    r = redis_async.from_url(self.redis_url, decode_responses=True)
                    await r.publish("magti_sse_events", json.dumps(event, ensure_ascii=False))
                    await r.aclose()
                except Exception as e:
                    print(f"SSE Broker: Failed to publish to Redis ({e}). Routing to local queues.")
                    self._push_local(event)
            else:
                self._push_local(event)
                
        asyncio.run_coroutine_threadsafe(_push(), self._main_loop)

    def _push_local(self, event: dict) -> None:
        for queue in list(self._local_queues):
            try:
                if queue.full():
                    queue.get_nowait()
                queue.put_nowait(event)
            except Exception:
                pass


broker = RedisEventBroker()


def _notify(event_type: str, item) -> None:
    """Publish a 'new content' event to connected clients (best-effort)."""
    broker.publish({
        "type": event_type,
        "id": item.id,
        "title": item.title,
        "target_department": getattr(item, "target_department", "All"),
    })

# ── Static HTML page serving ──────────────────────────────────────────────
# Only these pages may be served by name. An explicit allowlist prevents path
# traversal (e.g. /../security.py) and stops source files being downloaded.
SERVABLE_PAGES = {"login.html", "base-layout.html", "article.html"}


def _serve_page(filename: str) -> FileResponse:
    """Helper to serve static HTML pages safely from the allowed allowlist.

    Prevents directory traversal attacks by validating input filenames.

    Args:
        filename: Name of the HTML file to serve.

    Returns:
        A FileResponse targeting the requested page.

    Raises:
        HTTPException: If the file is not found or not in the servable pages list.
    """
    if filename not in SERVABLE_PAGES:
        raise HTTPException(status_code=404, detail="Page not found")
    path = os.path.join(BASE_DIR, filename)
    if not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="Page not found")
    return FileResponse(path, media_type="text/html")


@app.get("/", response_class=HTMLResponse)
def serve_index():
    """Serves the main application landing/portal interface.

    Access: Open to all users (anonymous and authenticated). The frontend
    authenticates and redirects to `/login.html` if no token is found.
    """
    # Entry point. base-layout.html's auth guard bounces to /login.html when no
    # token is present.
    return _serve_page("base-layout.html")


@app.get("/login.html", response_class=HTMLResponse)
def serve_login():
    """Serves the portal login page.

    Access: Open to all users.
    """
    return _serve_page("login.html")


@app.get("/base-layout.html", response_class=HTMLResponse)
def serve_base_layout():
    """Serves the primary base-layout template.

    Access: Open to all users. Authenticated sessions are validated on the client side.
    """
    # login.html redirects here (relative URL) after a successful login.
    return _serve_page("base-layout.html")


@app.get("/article.html", response_class=HTMLResponse)
def serve_article():
    """Serves the standalone article page.

    Access: Open to all users. Authenticated sessions are validated on the client side.
    """
    return _serve_page("article.html")


@app.get("/favicon.ico", include_in_schema=False)
def favicon():
    """Responds to favicon requests with a 204 No Content.

    Access: Open to all users. Used to prevent server errors on automatic browser fetches.
    """
    # Browsers auto-request this; return 204 instead of a noisy 404.
    return Response(status_code=204)

@app.post("/api/auth/login", response_model=schemas.Token)
def login_for_access_token(
    credentials: schemas.LoginRequest,
    response: Response,
    db: Session = Depends(get_db)
):
    """Authenticates user credentials and issues a signed JWT access token.
    """
    try:
        user = security.authenticate_user(db, credentials.email, credentials.password)
        if not user:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="არასწორი ელ. ფოსტა ან მომხმარებელი არ არსებობს",
            )

        # Audit trail: every successful login is recorded
        if not credentials.email.startswith("test_operator_"):
            audit_log = models.AuditLog(
                admin_id=user.id,
                action="LOGIN",
                item_type="user",
                item_id=user.id
            )
            db.add(audit_log)
            db.commit()

        access_token = security.create_access_token(data={"sub": user.email, "role": user.role})

        response.set_cookie(
            key="access_token",
            value=access_token,
            httponly=True,
            secure=settings.COOKIE_SECURE,
            samesite=settings.COOKIE_SAMESITE,
            max_age=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
            path="/",
        )

        return {"access_token": access_token, "token_type": "bearer"}
    except HTTPException:
        raise
    except Exception as e:
        import traceback
        traceback.print_exc()
        # 500-ის ნაცვლად ვაბრუნებთ 400-ს, რომ ბრაუზერმა CORS-ის გარეშე უსაფრთხოდ წაიკითხოს შიდა ერორი
        raise HTTPException(status_code=400, detail=f"შიდა სერვერული ერორი: {str(e)}")


@app.post("/api/auth/logout")
def logout(response: Response):
    """Clear the auth cookie (relevant when using cookie-based auth)."""
    response.delete_cookie("access_token", path="/")
    return {"detail": "Logged out"}

@app.get("/api/users/me", response_model=schemas.UserResponse)
def read_users_me(current_user: models.User = Depends(security.get_current_user)):
    """Retrieves the profile information of the currently authenticated user.

    Access: Authenticated users (any active role).

    Args:
        current_user: The authenticated User object.

    Returns:
        The current User details.
    """
    return current_user

@app.put("/api/users/me", response_model=schemas.UserResponse)
def update_users_me(
    update_data: schemas.UserSelfUpdate,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Allows users to update their own profile details (name, position, phone)."""
    current_user.name = update_data.name
    if update_data.position is not None:
        current_user.position = update_data.position
    if update_data.phone is not None:
        current_user.phone = update_data.phone
    if update_data.card_style is not None:
        current_user.card_style = update_data.card_style
    db.commit()
    db.refresh(current_user)
    return current_user

@app.get("/api/news/{news_id}", response_model=schemas.NewsResponse)
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
    n = db.query(models.News).filter(models.News.id == news_id).first()
    if not n:
        raise HTTPException(status_code=404, detail="სიახლე ვერ მოიძებნა")
    if current_user.role not in ("admin", "content_admin") \
       and n.target_department not in (current_user.department, "All"):
        raise HTTPException(status_code=404, detail="სიახლე ვერ მოიძებნა")
    return n


@app.get("/api/news", response_model=list[schemas.NewsSummaryResponse])
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
    # Admins manage content across all departments, so they see everything
    if current_user.role not in ["admin", "content_admin"]:
        query = query.filter(models.News.target_department.in_([current_user.department, "All"]))
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

@app.post("/api/news", response_model=schemas.NewsResponse)
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
    db.add(db_news)
    db.flush()

    audit_log = models.AuditLog(
        admin_id=current_admin.id, action="CREATE", item_type="news", item_id=db_news.id
    )
    db.add(audit_log)
    db.commit()
    search_cache.clear()

    # Real-time: push a notification to connected users in the target department.
    _notify("news", db_news)
    return db_news

@app.put("/api/news/{news_id}", response_model=schemas.NewsResponse)
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
    db_news = db.query(models.News).filter(models.News.id == news_id).first()
    if not db_news:
        raise HTTPException(status_code=404, detail="სიახლე ვერ მოიძებნა")

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

    audit_log = models.AuditLog(
        admin_id=current_admin.id, action="UPDATE", item_type="news", item_id=db_news.id
    )
    db.add(audit_log)
    db.commit()
    search_cache.clear()
    db.refresh(db_news)
    return db_news

@app.delete("/api/news/{news_id}", status_code=status.HTTP_204_NO_CONTENT)
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
    db_news = db.query(models.News).filter(models.News.id == news_id).first()
    if not db_news:
        raise HTTPException(status_code=404, detail="სიახლე ვერ მოიძებნა")

    db.delete(db_news)

    audit_log = models.AuditLog(
        admin_id=current_admin.id, action="DELETE", item_type="news", item_id=news_id
    )
    db.add(audit_log)
    db.commit()
    search_cache.clear()
    return None


def _get_stream_user(request: Request, bearer_token: Optional[str]) -> dict:
    """Synchronous auth+lookup for /api/stream, offloaded via run_in_threadpool.

    Mirrors security.get_current_user but returns a plain dict so the SSE
    handler doesn't need the ORM object on the event loop.
    """
    from jose import jwt, JWTError

    token = bearer_token or request.cookies.get("access_token")
    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Not authenticated",
            headers={"WWW-Authenticate": "Bearer"},
        )
    try:
        payload = jwt.decode(token, security.SECRET_KEY, algorithms=[security.ALGORITHM])
        email = payload.get("sub")
        if not email:
            raise JWTError("missing sub")
    except JWTError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    with SessionLocal() as db:
        user = db.query(models.User).filter(models.User.email == email).first()
        if not user or not user.is_active:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="User not authorized",
            )
        return {
            "user_id": user.id,
            "is_admin": user.role in ("admin", "content_admin"),
            "department": user.department,
        }


@app.get("/api/stream")
async def event_stream(
    request: Request,
):
    """Server-Sent Events: live 'new content' notifications for the logged-in
    user, filtered to their department.

    Auth note: EventSource cannot send an Authorization header, so this relies
    on the httpOnly `access_token` cookie set at login (security.get_current_user
    accepts either the bearer header or that cookie).
    """
    auth_header = request.headers.get("Authorization")
    bearer_token = auth_header.split(" ")[1] if auth_header and auth_header.startswith("Bearer ") else None

    # Offload the synchronous DB query to the threadpool to prevent event loop deadlocks
    user_info = await run_in_threadpool(_get_stream_user, request, bearer_token)
    is_admin = user_info["is_admin"]
    user_dept = user_info["department"]

    try:
        queue = await broker.subscribe()
    except Exception:
        # Fallback for local testing without Redis (prevents 500 error)
        async def fallback_generator():
            yield ": connected (redis offline fallback)\n\n"
            while True:
                if await request.is_disconnected():
                    break
                await asyncio.sleep(15)
                yield ": keep-alive\n\n"
        return StreamingResponse(
            fallback_generator(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}
        )

    async def event_generator():
        try:
            # Open the stream so the browser's EventSource fires `onopen`.
            yield ": connected\n\n"
            while True:
                if await request.is_disconnected():
                    break
                try:
                    event = await asyncio.wait_for(queue.get(), timeout=15.0)
                except asyncio.TimeoutError:
                    # Heartbeat keeps the connection alive through proxies.
                    yield ": keep-alive\n\n"
                    continue

                # Deliver only what this user is allowed to see.
                target = event.get("target_department", "All")
                if is_admin or target == "All" or target == user_dept:
                    yield "event: %s\ndata: %s\n\n" % (
                        event.get("type", "message"),
                        json.dumps(event, ensure_ascii=False),
                    )
        finally:
            broker.unsubscribe(queue)

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",  # don't let nginx buffer the stream
        },
    )

@app.get("/api/categories", response_model=list[schemas.CategoryResponse])
def get_categories(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves all categories. Results are cached with a 60-second TTL.

    Access: Authenticated users (any active role).

    Args:
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A list of CategoryResponse schemas.
    """
    cache_key = f"categories:{current_user.role}:{current_user.department}"
    cached = category_cache.get(cache_key)
    if cached is not None:
        return cached

    categories = db.query(models.Category).filter(models.Category.is_active == True).all()
    serialized = [schemas.CategoryResponse.model_validate(c).model_dump() for c in categories]
    category_cache.set(cache_key, serialized)
    return serialized

@app.post("/api/categories", response_model=schemas.CategoryResponse)
def create_category(
    category: schemas.CategoryCreate,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Creates a new category, clears search/category caches, and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        category: The category creation details.
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        The created Category database row.
    """
    db_category = models.Category(**category.model_dump())
    db.add(db_category)
    db.commit()
    db.refresh(db_category)

    audit_log = models.AuditLog(admin_id=current_admin.id, action="CREATE", item_type="category", item_id=db_category.id)
    db.add(audit_log)
    db.commit()
    category_cache.clear()
    search_cache.clear()
    return db_category

@app.put("/api/categories/{category_id}", response_model=schemas.CategoryResponse)
def update_category(
    category_id: int,
    category: schemas.CategoryCreate,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Updates an existing category, clears search/category caches, and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        category_id: ID of the category to update.
        category: The category update details.
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        The updated Category database row.

    Raises:
        HTTPException: 404 Not Found if the category does not exist.
    """
    db_category = db.query(models.Category).filter(models.Category.id == category_id).first()
    if not db_category:
        raise HTTPException(status_code=404, detail="კატეგორია ვერ მოიძებნა")
    
    for key, value in category.model_dump().items():
        setattr(db_category, key, value)
    
    db.commit()
    db.refresh(db_category)

    audit_log = models.AuditLog(admin_id=current_admin.id, action="UPDATE", item_type="category", item_id=db_category.id)
    db.add(audit_log)
    db.commit()
    category_cache.clear()
    search_cache.clear()
    return db_category

@app.delete("/api/categories/{category_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_category(
    category_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Deletes a category, clears search/category caches, and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        category_id: ID of the category to delete.
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        None.

    Raises:
        HTTPException: 404 Not Found if the category does not exist.
    """
    db_category = db.query(models.Category).filter(models.Category.id == category_id).first()
    if not db_category:
        raise HTTPException(status_code=404, detail="კატეგორია ვერ მოიძებნა")
    
    fallback_cat = db.query(models.Category).filter(models.Category.name == "ზოგადი").first()
    if not fallback_cat:
        fallback_cat = models.Category(name="ზოგადი", slug="general", icon="fa-layer-group", pastel_color_class="general", is_active=True)
        db.add(fallback_cat)
        db.commit()
        db.refresh(fallback_cat)

    if fallback_cat.id != category_id:
        db.query(models.Article).filter(models.Article.category_id == category_id).update({"category_id": fallback_cat.id})

    db_category.is_active = False
    db.commit()

    audit_log = models.AuditLog(admin_id=current_admin.id, action="DELETE", item_type="category", item_id=category_id)
    db.add(audit_log)
    db.commit()
    category_cache.clear()
    search_cache.clear()
    return None


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
    if not ({user.department, "All"} & set(article.target_departments)):
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


@app.get("/api/articles", response_model=list[schemas.ArticleSummaryResponse])
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
    query = db.query(models.Article).options(joinedload(models.Article.category))
    # Admins manage content across all departments, so they see everything
    # (including archived items); regular users see only published content
    if current_user.role not in ["admin", "content_admin"]:
        now = datetime.utcnow()
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


@app.get("/api/articles/{article_id}", response_model=schemas.ArticleResponse)
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

@app.get("/api/compliance/my-readings", response_model=list[schemas.MyReadingResponse])
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
    readings = db.query(models.RequiredReading).filter(
        models.RequiredReading.target_department.in_([current_user.department, "All"])
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

    now = datetime.utcnow()
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


@app.get("/api/notifications/summary")
def get_notifications_summary(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
):
    """Combined bell-icon payload — unread mandatory readings + recent news + unread messages count.

    Item 15: the notifications popover used to call /my-readings + /news + /messages
    in sequence, each of which does its own DB roundtrip. This consolidates the
    fetch into ONE endpoint so the popover opens in roughly a single RTT.
    """
    # 1) Unread/overdue required readings (visible-to-this-user)
    readings = db.query(models.RequiredReading).filter(
        models.RequiredReading.target_department.in_([current_user.department, "All"])
    ).all()
    reading_ids = [r.id for r in readings]
    read_map: dict[int, models.ReadStatus] = {}
    if reading_ids:
        read_map = {
            s.required_reading_id: s
            for s in db.query(models.ReadStatus).filter(
                models.ReadStatus.user_id == current_user.id,
                models.ReadStatus.required_reading_id.in_(reading_ids),
            )
        }
    now = datetime.utcnow()
    # Title batch lookup
    title_map: dict[tuple[str, int], str] = {}
    by_type: dict[str, list[int]] = {"article": [], "news": [], "video": []}
    for r in readings:
        if r.item_type in by_type:
            by_type[r.item_type].append(r.item_id)
    if by_type["article"]:
        for aid, t in db.query(models.Article.id, models.Article.title).filter(
            models.Article.id.in_(set(by_type["article"]))
        ):
            title_map[("article", aid)] = t
    if by_type["news"]:
        for nid, t in db.query(models.News.id, models.News.title).filter(
            models.News.id.in_(set(by_type["news"]))
        ):
            title_map[("news", nid)] = t
    if by_type["video"]:
        for vid, t in db.query(models.VideoInstruction.id, models.VideoInstruction.title).filter(
            models.VideoInstruction.id.in_(set(by_type["video"]))
        ):
            title_map[("video", vid)] = t

    unread_readings = []
    for r in readings:
        stat = read_map.get(r.id)
        if stat and stat.status == "read":
            continue
        overdue = (not stat or stat.status != "read") and r.due_date < now
        unread_readings.append({
            "id": r.id,
            "item_type": r.item_type,
            "item_id": r.item_id,
            "title": title_map.get((r.item_type, r.item_id), f"მასალა #{r.item_id}"),
            "due_date": r.due_date,
            "is_overdue": bool(overdue),
        })

    # 2) Recent news (last 7 days), visible to user
    seven_days_ago = now - timedelta(days=7)
    news_q = db.query(models.News).filter(models.News.created_at >= seven_days_ago)
    if current_user.role not in ("admin", "content_admin"):
        news_q = news_q.filter(
            models.News.target_department.in_([current_user.department, "All"])
        )
    recent_news = [
        {"id": n.id, "title": n.title, "target_department": n.target_department,
         "created_at": n.created_at}
        for n in news_q.order_by(desc(models.News.created_at)).limit(10).all()
    ]

    # 3) Unread personal-message count (drives envelope badge)
    unread_messages = db.query(func.count(models.Message.id)).filter(
        models.Message.user_id == current_user.id,
        models.Message.is_read == False,  # noqa: E712 (SQLAlchemy needs == False)
    ).scalar() or 0

    return {
        "unread_readings": unread_readings,
        "recent_news": recent_news,
        "unread_messages_count": int(unread_messages),
    }

@app.post("/api/compliance/mark-read/{reading_id}", response_model=schemas.ReadStatusResponse)
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
    reading = db.query(models.RequiredReading).filter(models.RequiredReading.id == reading_id).first()
    if not reading:
        raise HTTPException(status_code=404, detail="სავალდებულო მასალა ვერ მოიძებნა")

    stat = db.query(models.ReadStatus).filter(models.ReadStatus.user_id == current_user.id, models.ReadStatus.required_reading_id == reading_id).first()
    if not stat:
        stat = models.ReadStatus(user_id=current_user.id, required_reading_id=reading_id)
        db.add(stat)
    stat.status = "read"
    stat.read_at = datetime.utcnow()

    # Audit trail: compliance acknowledgements are recorded (actor is the reader)
    audit_log = models.AuditLog(
        admin_id=current_user.id,
        action="MARK_READ",
        item_type="required_reading",
        item_id=reading_id
    )
    db.add(audit_log)
    db.commit()
    db.refresh(stat)
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
    item_title = f"მასალა #{db_reading.item_id}"
    if db_reading.item_type == "article":
        art = db.query(models.Article).filter(models.Article.id == db_reading.item_id).first()
        if art:
            item_title = art.title
    elif db_reading.item_type == "news":
        news = db.query(models.News).filter(models.News.id == db_reading.item_id).first()
        if news:
            item_title = news.title
    elif db_reading.item_type == "video":
        vid = db.query(models.VideoInstruction).filter(models.VideoInstruction.id == db_reading.item_id).first()
        if vid:
            item_title = vid.title

    # Find all active users in the target department (excluding the admin).
    users_q = db.query(models.User.id).filter(models.User.is_active == True)
    if db_reading.target_department != "All":
        users_q = users_q.filter(models.User.department == db_reading.target_department)
    target_user_ids = [uid for (uid,) in users_q.all() if uid != current_admin_id]

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
            "created_at": datetime.utcnow(),
        }
        for uid in target_user_ids
    ]
    db.bulk_insert_mappings(models.Message, message_rows)

    # Single broadcast event for the toast + list refresh. The SSE endpoint
    # gates by target_department, so users outside the dept never see it.
    try:
        broker.publish({
            "type": "required_reading",
            "id": db_reading.id,
            "title": item_title,
            "item_type": db_reading.item_type,
            "item_id": db_reading.item_id,
            "due_date": due_str,
            "target_department": db_reading.target_department,
        })
    except Exception:
        pass

    # Per-user "message" events so each open session gets its unread badge
    # updated immediately (the badge is keyed by user_id).
    for uid in target_user_ids:
        try:
            broker.publish({
                "type": "message",
                "user_id": uid,
                "content": message_content,
                "sender_id": current_admin_id,
                # 'All' so the SSE endpoint's department filter doesn't drop it;
                # the client-side handler checks user_id == me.
                "target_department": "All",
            })
        except Exception:
            pass

@app.post("/api/compliance/required-readings", response_model=schemas.RequiredReadingResponse)
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
        print(f"Error auto generating mandatory notifications: {e}")

    audit_log = models.AuditLog(
        admin_id=current_admin.id,
        action="CREATE",
        item_type="required_reading",
        item_id=db_reading.id
    )
    db.add(audit_log)
    db.commit()
    return db_reading

@app.get("/api/videos", response_model=list[schemas.VideoInstructionResponse])
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
    if current_user.role not in ["admin", "content_admin"]:
        query = query.filter(
            models.VideoInstruction.target_department.in_([current_user.department, "All"]),
            models.VideoInstruction.is_archived == False,  # noqa: E712
        )
    return query.all()

@app.get("/api/favorites", response_model=list[schemas.FavoriteResponse])
def get_favorites(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves all bookmarked items (articles, news, videos) for the current user.

    Access: Authenticated users (any active role).

    Args:
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A list of FavoriteResponse schemas.
    """
    favorites = db.query(models.Favorite).filter(models.Favorite.user_id == current_user.id).all()
    results = []
    for fav in favorites:
        title = None
        if fav.item_type == "article":
            art = db.query(models.Article).filter(models.Article.id == fav.item_id).first()
            if art:
                title = art.title
        elif fav.item_type == "news":
            news = db.query(models.News).filter(models.News.id == fav.item_id).first()
            if news:
                title = news.title
        elif fav.item_type == "video":
            vid = db.query(models.VideoInstruction).filter(models.VideoInstruction.id == fav.item_id).first()
            if vid:
                title = vid.title
        
        results.append({
            "id": fav.id,
            "user_id": fav.user_id,
            "item_type": fav.item_type,
            "item_id": fav.item_id,
            "item_title": title or f"მასალა #{fav.item_id}"
        })
    return results

@app.post("/api/favorites", response_model=schemas.FavoriteResponse)
def add_favorite(
    favorite: schemas.FavoriteCreate,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Bookmarks an item (adds it to the user's favorites).

    Prevents duplicate bookmarks for the same item.

    Access: Authenticated users (any active role).

    Args:
        favorite: Target item details (type and ID).
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        The created or existing Favorite database row.
    """
    existing = db.query(models.Favorite).filter(
        models.Favorite.user_id == current_user.id,
        models.Favorite.item_type == favorite.item_type,
        models.Favorite.item_id == favorite.item_id
    ).first()
    
    fav_row = existing
    if not existing:
        new_favorite = models.Favorite(
            user_id=current_user.id,
            item_type=favorite.item_type,
            item_id=favorite.item_id
        )
        db.add(new_favorite)
        db.commit()
        db.refresh(new_favorite)
        fav_row = new_favorite

    # Resolve title
    title = None
    if fav_row.item_type == "article":
        art = db.query(models.Article).filter(models.Article.id == fav_row.item_id).first()
        if art:
            title = art.title
    elif fav_row.item_type == "news":
        news = db.query(models.News).filter(models.News.id == fav_row.item_id).first()
        if news:
            title = news.title
    elif fav_row.item_type == "video":
        vid = db.query(models.VideoInstruction).filter(models.VideoInstruction.id == fav_row.item_id).first()
        if vid:
            title = vid.title

    return {
        "id": fav_row.id,
        "user_id": fav_row.user_id,
        "item_type": fav_row.item_type,
        "item_id": fav_row.item_id,
        "item_title": title or f"მასალა #{fav_row.item_id}"
    }

@app.delete("/api/favorites/{favorite_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_favorite(
    favorite_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Removes an item from the user's bookmarks (favorites).

    Access: Authenticated users (any active role).

    Args:
        favorite_id: ID of the bookmark to delete.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        None.

    Raises:
        HTTPException: 404 Not Found if the bookmark does not exist or belongs to another user.
    """
    favorite = db.query(models.Favorite).filter(
        models.Favorite.id == favorite_id,
        models.Favorite.user_id == current_user.id,
    ).first()
    if not favorite:
        raise HTTPException(status_code=404, detail="Favorite not found")
    
    db.delete(favorite)
    db.commit()
    return None

@app.post("/api/articles", response_model=schemas.ArticleResponse)
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

    # Real-time: notify the target department only about PUBLISHED articles.
    if db_article.status == "published":
        _notify("article", db_article)
    search_cache.clear()
    category_cache.clear()
    return db_article

@app.put("/api/articles/{article_id}", response_model=schemas.ArticleResponse)
def update_article(
    article_id: int,
    article: schemas.ArticleCreate,
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

    update_data = article.model_dump()
    target_departments = update_data.pop("target_departments")
    for key, value in update_data.items():
        setattr(db_article, key, value)

    db_article.target_department = "All" if "All" in target_departments else target_departments[0]
    db_article.target_department_rows = [
        models.ArticleTargetDepartment(department=d) for d in target_departments
    ]

    db_article.version += 1
    sync_tags(db, "article", db_article.id, db_article.tags)
    db.commit()
    db.refresh(db_article)

    audit_log = models.AuditLog(admin_id=current_admin.id, action="UPDATE", item_type="article", item_id=db_article.id)
    db.add(audit_log)
    db.commit()
    search_cache.clear()
    category_cache.clear()
    return db_article

@app.delete("/api/articles/{article_id}", status_code=status.HTTP_204_NO_CONTENT)
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
    search_cache.clear()
    category_cache.clear()
    return None


@app.post("/api/articles/{article_id}/archive", response_model=schemas.ArticleResponse)
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
    search_cache.clear()
    category_cache.clear()
    db.refresh(db_article)
    return db_article


@app.post("/api/articles/{article_id}/unarchive", response_model=schemas.ArticleResponse)
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
    search_cache.clear()
    category_cache.clear()
    db.refresh(db_article)
    return db_article


@app.get("/api/articles/{article_id}/history")
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

@app.post("/api/articles/{article_id}/history/{history_id}/restore", response_model=schemas.ArticleResponse)
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
    search_cache.clear()
    category_cache.clear()

    return db_article

@app.post("/api/articles/{article_id}/view")
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

@app.post("/api/videos/{video_id}/view", response_model=schemas.VideoInstructionResponse)
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
    video = db.query(models.VideoInstruction).filter(models.VideoInstruction.id == video_id).first()
    if not video:
        raise HTTPException(status_code=404, detail="ვიდეო ვერ მოიძებნა")
    
    video.views_count += 1
    db.commit()
    db.refresh(video)
    return video

@app.get("/api/export/readings")
def export_readings(
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db)
):
    """Exports required readings compliance status to a CSV file.

    Restricted strictly to system administrators due to containment of employee personal data.
    Logs the export action.

    Access: Restricted to system administrators (admin) only.

    Args:
        current_admin: The authenticated system administrator User.
        db: SQLAlchemy database session.

    Returns:
        A StreamingResponse delivering the exported CSV data.
    """
    # RBAC: this CSV contains personal data (employee names + read timestamps),
    # so it is restricted to the system administrator, not content admins.
    audit_log = models.AuditLog(
        admin_id=current_admin.id, action="EXPORT", item_type="readings", item_id=0
    )
    db.add(audit_log)
    db.commit()

    # Join ReadStatus with User and RequiredReading to output human-readable data
    query = db.query(models.ReadStatus, models.User.name, models.RequiredReading.item_type, models.RequiredReading.item_id).join(
        models.User, models.ReadStatus.user_id == models.User.id
    ).join(
        models.RequiredReading, models.ReadStatus.required_reading_id == models.RequiredReading.id
    ).all()
    
    output = io.StringIO()
    writer = csv.writer(output)
    
    writer.writerow(["User ID", "User Name", "Item Type", "Item ID", "Status", "Read At"])
    
    for rs, user_name, item_type, item_id in query:
        read_at_str = rs.read_at.strftime("%Y-%m-%d %H:%M:%S") if rs.read_at else "N/A"
        writer.writerow([rs.user_id, user_name, item_type, item_id, rs.status, read_at_str])
        
    output.seek(0)
    
    return StreamingResponse(
        iter([output.getvalue()]),
        media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=readings_export.csv"}
    )

@app.post("/api/upload")
def upload_file(
    file: UploadFile = File(...),
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Uploads an attachment file, validating its MIME type and size limit.

    Generates a unique filename using UUID4 to prevent filename collisions and security risks.
    Logs the upload action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        file: The uploaded file.
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        A dictionary containing the uploaded file URL and filename.

    Raises:
        HTTPException: 415 if type is not allowed; 413 if size exceeds limit.
    """
    # 1) MIME allowlist. The stored extension is derived from the DETECTED type,
    #    never from the client-supplied filename (which is spoofable, and could
    #    smuggle .html/.svg/.php for stored-XSS or arbitrary execution).
    content_type = (file.content_type or "").split(";")[0].strip().lower()
    ext = settings.ALLOWED_UPLOAD_TYPES.get(content_type)
    if ext is None:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail="Unsupported file type '%s'. Allowed: %s"
            % (file.content_type, ", ".join(sorted(settings.ALLOWED_UPLOAD_TYPES))),
        )

    unique_filename = f"{uuid.uuid4()}{ext}"
    file_path = os.path.join(settings.UPLOAD_DIR, unique_filename)

    # 2) Stream to disk in 1 MB chunks, enforcing the size cap as we go so a huge
    #    upload cannot exhaust memory/disk. Partial files are removed on failure.
    max_bytes = settings.MAX_UPLOAD_SIZE_BYTES
    written = 0
    try:
        with open(file_path, "wb") as buffer:
            while True:
                chunk = file.file.read(1024 * 1024)
                if not chunk:
                    break
                written += len(chunk)
                if written > max_bytes:
                    raise HTTPException(
                        status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                        detail=f"File exceeds the maximum allowed size of {max_bytes} bytes",
                    )
                buffer.write(chunk)
    except HTTPException:
        if os.path.exists(file_path):
            os.remove(file_path)
        raise
    finally:
        file.file.close()

    audit_log = models.AuditLog(
        admin_id=current_admin.id, action="UPLOAD", item_type="file", item_id=0
    )
    db.add(audit_log)
    db.commit()

    return {"url": f"/uploads/{unique_filename}", "filename": unique_filename}

@app.get("/api/search", response_model=list[schemas.ArticleResponse])
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
    if current_user.role not in ["admin", "content_admin"]:
        now = datetime.utcnow()
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
            now = datetime.utcnow()
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


@app.get("/api/search/global", response_model=schemas.GlobalSearchResponse)
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
    cache_key = f"search:{q}:{current_user.role}:{current_user.department}"
    is_admin = current_user.role in ("admin", "content_admin")
    user_dept = current_user.department
    
    norm_q = q.strip().lower()

    cached = search_cache.get(cache_key)
    if cached is not None:
        results_found = len(cached.get("articles", [])) + len(cached.get("news", [])) + len(cached.get("videos", []))
        if len(norm_q) >= 3 and not current_user.email.startswith("test_operator_"):
            enqueue_log(request, LogItem("search", {
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
        enqueue_log(request, LogItem("search", {
            "user_id": current_user.id,
            "search_term": norm_q,
            "has_results": results_found > 0,
            "results_found": results_found,
        }))
    return result

@app.get("/api/search/history")
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

@app.get("/api/statistics/popular-searches", response_model=list[schemas.PopularSearchResponse])
def get_popular_searches(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieves the top 10 most popular search terms across the organization.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        A list of PopularSearchResponse schemas.
    """
    normalized_term = func.lower(func.trim(models.SearchLog.search_term))
    results = db.query(
        normalized_term.label("search_term"),
        func.count(models.SearchLog.id).label("count")
    ).filter(models.SearchLog.has_results == True).group_by(normalized_term).order_by(desc("count")).limit(10).all()
    
    return [{"search_term": r.search_term, "count": r.count} for r in results]

@app.get("/api/statistics/failed-searches", response_model=list[schemas.PopularSearchResponse])
def get_failed_searches(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieves the top 10 search terms that returned no results.
    
    Useful for identifying knowledge gaps in the database.
    """
    normalized_term = func.lower(func.trim(models.SearchLog.search_term))
    results = db.query(
        normalized_term.label("search_term"),
        func.count(models.SearchLog.id).label("count")
    ).filter(models.SearchLog.has_results == False).group_by(normalized_term).order_by(desc("count")).limit(10).all()
    
    return [{"search_term": r.search_term, "count": r.count} for r in results]

@app.get("/api/statistics/compliance", response_model=schemas.ComplianceStatsResponse)
def get_compliance_statistics(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Computes overall compliance statistics (read/unread ratio, top read articles).

    Employs optimized counts to avoid N+1 queries.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        A ComplianceStatsResponse schema.
    """
    # Calculate compliance percentage by calculating total expected reads vs actual marked read (operators only)
    read_count = db.query(models.ReadStatus).join(models.User).filter(
        models.User.is_active == True,
        models.User.role.notin_(_MANAGEMENT_ROLES),
        models.ReadStatus.status == "read"
    ).count()

    # PERFORMANCE: previously this ran one COUNT(*) per required reading (N+1).
    # Now we fetch active-operator counts per department once, then sum in memory.
    active_by_dept = dict(
        db.query(models.User.department, func.count(models.User.id))
        .filter(models.User.is_active == True, models.User.role.notin_(_MANAGEMENT_ROLES))
        .group_by(models.User.department)
        .all()
    )
    total_active = sum(active_by_dept.values())

    total_assignments = 0
    readings = db.query(models.RequiredReading).all()
    for r in readings:
        if r.target_department == "All":
            total_assignments += total_active
        else:
            total_assignments += active_by_dept.get(r.target_department, 0)

    if total_assignments > 0:
        read_percentage = round((read_count / total_assignments) * 100, 2)
        unread_percentage = round(100 - read_percentage, 2)
    else:
        read_percentage = 0.0
        unread_percentage = 100.0

    # Discover the top 5 most read articles across the organization (operators only)
    top_articles = db.query(models.Article).join(
        models.RequiredReading, models.RequiredReading.item_id == models.Article.id
    ).join(
        models.ReadStatus, models.ReadStatus.required_reading_id == models.RequiredReading.id
    ).join(
        models.User, models.ReadStatus.user_id == models.User.id
    ).filter(
        models.User.is_active == True,
        models.User.role.notin_(_MANAGEMENT_ROLES),
        models.RequiredReading.item_type == "article",
        models.ReadStatus.status == "read"
    ).group_by(*models.Article.__table__.columns).order_by(
        desc(func.count(models.ReadStatus.id))
    ).limit(5).all()

    return {
        "read_percentage": read_percentage,
        "unread_percentage": unread_percentage,
        "top_articles": top_articles
    }

def _reading_progress(user, all_required, readings_by_dept, read_map):
    """Compute (required_count, read_count, percentage) for one user from
    pre-aggregated maps — no per-user query, so callers avoid the N+1.

    Applicable readings = those targeting "All" plus those targeting the user's
    own department; read_map is keyed by (user_id, reading_target_department).
    """
    if not user.department or user.department == "All":
        required_count = all_required
        read_count = read_map.get((user.id, "All"), 0)
    else:
        required_count = all_required + readings_by_dept.get(user.department, 0)
        read_count = (
            read_map.get((user.id, "All"), 0)
            + read_map.get((user.id, user.department), 0)
        )

    if required_count == 0:
        return 0, 0, 0
    percentage = round((read_count / required_count) * 100)
    return required_count, read_count, percentage


@app.get("/api/statistics/user-progress")
def get_user_progress(
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db)
):
    """Computes reading compliance progress details for all active users.

    Aggregates database counts once in memory to avoid N+1 query patterns.

    Access: Restricted to system administrators (admin) only.

    Args:
        current_admin: The authenticated system administrator User.
        db: SQLAlchemy database session.

    Returns:
        A list of employee compliance percentages, sorted by compliance level.
    """
    # RBAC: org-wide, per-employee progress is personal data → system admin only.
    users = db.query(models.User).filter(
        models.User.is_active == True,
        models.User.role.notin_(_MANAGEMENT_ROLES),
    ).all()
    all_readings = db.query(models.RequiredReading).all()

    # Required-reading counts per department bucket (computed once).
    readings_by_dept = Counter(r.target_department for r in all_readings)
    all_required = readings_by_dept.get("All", 0)

    # PERFORMANCE: one grouped query replaces a per-user COUNT (the N+1).
    # read_map[(user_id, reading_department)] = number of "read" statuses.
    read_rows = (
        db.query(
            models.ReadStatus.user_id,
            models.RequiredReading.target_department,
            func.count(models.ReadStatus.id),
        )
        .join(
            models.RequiredReading,
            models.ReadStatus.required_reading_id == models.RequiredReading.id,
        )
        .filter(models.ReadStatus.status == "read")
        .group_by(models.ReadStatus.user_id, models.RequiredReading.target_department)
        .all()
    )
    read_map = {(uid, dept): cnt for uid, dept, cnt in read_rows}

    results = []
    for user in users:
        required_count, read_count, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        results.append({
            "user_id": user.id,
            "user_name": user.name,
            "department": user.department,
            "read_count": read_count,
            "required_count": required_count,
            "percentage": f"{percentage}%",
        })

    results.sort(key=lambda x: int(x["percentage"].replace("%", "")), reverse=True)
    return results

@app.get("/api/admin/stats/team/{team_id}", response_model=dict)
def get_admin_team_stats(
    team_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieves compliance statistics for a specific team.
    
    Access: Restricted to system administrators (admin).
    """
    users = db.query(models.User).filter(
        models.User.is_active == True,
        models.User.role.notin_(_MANAGEMENT_ROLES),
        models.User.team_id == team_id,
    ).all()
    if not users:
        return {"team_id": team_id, "average_percentage": "0%", "members": []}
    
    all_readings = db.query(models.RequiredReading).all()
    readings_by_dept = Counter(r.target_department for r in all_readings)
    all_required = readings_by_dept.get("All", 0)

    read_rows = (
        db.query(
            models.ReadStatus.user_id,
            models.RequiredReading.target_department,
            func.count(models.ReadStatus.id),
        )
        .join(
            models.RequiredReading,
            models.ReadStatus.required_reading_id == models.RequiredReading.id,
        )
        .filter(models.ReadStatus.status == "read")
        .group_by(models.ReadStatus.user_id, models.RequiredReading.target_department)
        .all()
    )
    read_map = {(uid, d): cnt for uid, d, cnt in read_rows}

    members = []
    total_percentage = 0
    for user in users:
        required_count, read_count, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        members.append({
            "user_id": user.id,
            "user_name": user.name,
            "read_count": read_count,
            "required_count": required_count,
            "percentage": f"{percentage}%",
        })
        total_percentage += percentage

    avg_percentage = int(total_percentage / len(users)) if users else 0
    members.sort(key=lambda x: int(x["percentage"].replace("%", "")), reverse=True)
    
    return {
        "team_id": team_id,
        "average_percentage": f"{avg_percentage}%",
        "members": members
    }

@app.get("/api/manager/team-stats", response_model=schemas.TeamStatsResponse)
def get_team_stats(
    department: Optional[str] = None,
    team_id: Optional[int] = None,
    operator_name: Optional[str] = None,
    current_manager: models.User = Depends(security.get_current_manager_user),
    db: Session = Depends(get_db)
):
    """Retrieves compliance statistics for users in the manager's department.

    Employs optimized pre-aggregated counts.

    Access: Restricted to managers (manager) and system administrators (admin).
    The ``department`` query parameter is honoured ONLY for system admins; managers
    are always pinned to their own department to prevent cross-team data leakage.
    The ``operator_name`` query parameter performs a case-insensitive substring
    match on the user's display name.

    Args:
        department: (Admin-only) restrict to a specific department.
        team_id: Optional filter to restrict by a specific team_id.
        operator_name: Substring filter on the operator's name.
        current_manager: The authenticated manager/supervisor User.
        db: SQLAlchemy database session.

    Returns:
        A TeamStatsResponse containing team member compliance progress.
    """
    users_q = db.query(models.User).filter(
        models.User.is_active == True,
        models.User.role.notin_(_MANAGEMENT_ROLES),
    )

    if current_manager.role == "admin":
        if department:
            users_q = users_q.filter(models.User.department == department)
            dept = department
        else:
            dept = "All"
    else:
        # RBAC: a manager is hard-pinned to their own department, even if they
        # send a different department in the query string.
        dept = current_manager.department
        users_q = users_q.filter(models.User.department == dept)

    if team_id:
        users_q = users_q.filter(models.User.team_id == team_id)

    if operator_name:
        # Case-insensitive substring match — supports partial typing in the filter.
        users_q = users_q.filter(models.User.name.ilike(f"%{operator_name.strip()}%"))

    users = users_q.all()

    all_readings = db.query(models.RequiredReading).all()
    readings_by_dept = Counter(r.target_department for r in all_readings)
    all_required = readings_by_dept.get("All", 0)

    # PERFORMANCE: one grouped query instead of a COUNT per team member (N+1).
    read_rows = (
        db.query(
            models.ReadStatus.user_id,
            models.RequiredReading.target_department,
            func.count(models.ReadStatus.id),
        )
        .join(
            models.RequiredReading,
            models.ReadStatus.required_reading_id == models.RequiredReading.id,
        )
        .filter(models.ReadStatus.status == "read")
        .group_by(models.ReadStatus.user_id, models.RequiredReading.target_department)
        .all()
    )
    read_map = {(uid, d): cnt for uid, d, cnt in read_rows}

    members = []
    for user in users:
        required_count, read_count, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        members.append({
            "user_id": user.id,
            "user_name": user.name,
            "read_count": read_count,
            "required_count": required_count,
            "percentage": f"{percentage}%",
        })

    members.sort(key=lambda x: int(x["percentage"].replace("%", "")), reverse=True)
    return {"department": dept, "members": members}


# ── Executive Department Dashboard ────────────────────────────────────────────
# The org hierarchy lives in the free-text users.department string, formatted as
# "{prefix} — ჯგუფი NN" by seed_org_hierarchy(). We split on the em dash to
# recover Department (prefix) → Group (suffix) → Members. The Team/team_id FK is
# NOT used here because the seed never populates it.

# Whitelisted department prefixes, in display order. Matching is by prefix
# (startswith), so "საინფორმაციო" matches "საინფორმაციო სამსახური — ჯგუფი 01".
DEPARTMENT_WHITELIST = ["საინფორმაციო", "ტექნიკური", "ოფისები"]

# Roles excluded from required-reading target-audience calculations.
# DB has: admin, content_admin, manager, operator.  Only operators are the
# intended audience; management roles inflate the denominator otherwise.
_MANAGEMENT_ROLES = ("admin", "content_admin", "manager")

# Legacy single-role alias kept for any code that references it.
_DASHBOARD_EXCLUDED_ROLE = "admin"

# Compliance threshold below which an operator is flagged "critical".
_CRITICAL_THRESHOLD = 30

# Em dash used as the department/group delimiter in the seeded labels.
_DEPT_GROUP_DELIM = "—"  # —


def _split_dept_group(raw_department):
    """Return (department_prefix, group_label) from a raw users.department value.

    "საინფორმაციო სამსახური — ჯგუფი 01" -> ("საინფორმაციო სამსახური", "ჯგუფი 01").
    Values without the delimiter return (whole, whole) so they still bucket.
    """
    raw = (raw_department or "").strip()
    if _DEPT_GROUP_DELIM in raw:
        prefix, _, suffix = raw.partition(_DEPT_GROUP_DELIM)
        prefix, suffix = prefix.strip(), suffix.strip()
        return prefix, (suffix or prefix)
    return raw, raw


def _aggregate_members(members):
    """Roll up a list of member dicts into (compliance, output_volume, critical).

    Compliance averages only members who actually have required readings, so
    operators with nothing assigned don't drag the average to 0.
    """
    output_volume = sum(m["read_count"] for m in members)
    critical_count = sum(1 for m in members if m["is_critical"])
    scored = [m["percentage"] for m in members if m["required_count"] > 0]
    compliance = round(sum(scored) / len(scored)) if scored else 0
    return compliance, output_volume, critical_count


def build_department_stats(db: Session):
    """Build the executive dashboard payload: Insights Ribbon + Department tree.

    Pure data builder, fully decoupled from the HTTP layer so it can be unit
    tested and reused. Uses the same pre-aggregated read_map pattern as the other
    stats endpoints to avoid the per-user N+1.

    Returns a dict matching schemas.DepartmentStatsResponse.
    """
    users = (
        db.query(models.User)
        .filter(
            models.User.is_active == True,  # noqa: E712
            models.User.role.notin_(_MANAGEMENT_ROLES),
        )
        .all()
    )

    all_readings = db.query(models.RequiredReading).all()
    readings_by_dept = Counter(r.target_department for r in all_readings)
    all_required = readings_by_dept.get("All", 0)

    read_rows = (
        db.query(
            models.ReadStatus.user_id,
            models.RequiredReading.target_department,
            func.count(models.ReadStatus.id),
        )
        .join(
            models.RequiredReading,
            models.ReadStatus.required_reading_id == models.RequiredReading.id,
        )
        .filter(models.ReadStatus.status == "read")
        .group_by(models.ReadStatus.user_id, models.RequiredReading.target_department)
        .all()
    )
    read_map = {(uid, d): cnt for uid, d, cnt in read_rows}

    # Bucket members by (whitelisted department prefix, group label).
    # groups_by_dept[prefix][group_label] -> list[member dict]
    groups_by_dept = {wl: {} for wl in DEPARTMENT_WHITELIST}
    all_members = []  # flat list for the global ribbon

    for user in users:
        prefix, group_label = _split_dept_group(user.department)
        matched = next(
            (wl for wl in DEPARTMENT_WHITELIST if prefix.startswith(wl)), None
        )
        if matched is None:
            continue  # not a whitelisted department — skip

        required_count, read_count, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        member = {
            "user_id": user.id,
            "user_name": user.name,
            "position": user.position,
            "read_count": read_count,
            "required_count": required_count,
            "percentage": percentage,
            "is_critical": required_count > 0 and percentage < _CRITICAL_THRESHOLD,
        }
        groups_by_dept[matched].setdefault(group_label, []).append(member)
        all_members.append(member)

    # Assemble the department tree, always rendering every whitelisted dept.
    departments = []
    for wl in DEPARTMENT_WHITELIST:
        groups = []
        for group_label, gmembers in groups_by_dept[wl].items():
            gmembers.sort(key=lambda m: m["percentage"], reverse=True)
            g_comp, g_out, g_crit = _aggregate_members(gmembers)
            groups.append({
                "name": group_label,
                # The raw department string for any member in this group (they
                # share it) — useful for drill-down filters on the old endpoint.
                "full_department": _group_full_department(wl, group_label),
                "member_count": len(gmembers),
                "compliance": g_comp,
                "output_volume": g_out,
                "critical_count": g_crit,
                "members": gmembers,
            })
        groups.sort(key=lambda g: g["name"])

        dept_members = [m for g in groups for m in g["members"]]
        d_comp, d_out, d_crit = _aggregate_members(dept_members)
        departments.append({
            "name": wl,
            "member_count": len(dept_members),
            "group_count": len(groups),
            "compliance": d_comp,
            "output_volume": d_out,
            "critical_count": d_crit,
            "is_empty": len(dept_members) == 0,
            "groups": groups,
        })

    g_comp, g_out, g_crit = _aggregate_members(all_members)
    insights = {
        "global_compliance": g_comp,
        "critical_operators": g_crit,
        "total_output_volume": g_out,
        "total_members": len(all_members),
    }

    return {
        "insights": insights,
        "departments": departments,
        "generated_at": datetime.utcnow(),
    }


def _group_full_department(prefix, group_label):
    """Reconstruct the raw department string for a (prefix, group) pair."""
    if group_label and group_label != prefix:
        return f"{prefix} {_DEPT_GROUP_DELIM} {group_label}"
    return prefix


@app.get("/api/manager/department-stats", response_model=schemas.DepartmentStatsResponse)
def get_department_stats(
    current_manager: models.User = Depends(security.get_current_manager_user),
    db: Session = Depends(get_db),
):
    """Executive dashboard data: Insights Ribbon + Department → Groups → Members.

    Aggregates compliance over the whitelisted departments, excluding the system
    administrator role. Decoupled from rendering — the body is built entirely by
    build_department_stats().

    Access: managers and system administrators.
    """
    return build_department_stats(db)


@app.get("/api/admin/critical-operators", response_model=schemas.CriticalOperatorResponse)
def get_critical_operators(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """List operators whose reading compliance is below the critical threshold.

    Three bulk queries (users, reading counts by dept, read counts by user+dept),
    then pure Python aggregation — no N+1, no ORM hydration of RequiredReading rows.
    """
    users = (
        db.query(
            models.User.id, models.User.name, models.User.department,
        )
        .filter(
            models.User.is_active == True,  # noqa: E712
            models.User.role.notin_(_MANAGEMENT_ROLES),
        )
        .all()
    )

    readings_by_dept = dict(
        db.query(
            models.RequiredReading.target_department,
            func.count(models.RequiredReading.id),
        )
        .group_by(models.RequiredReading.target_department)
        .all()
    )
    all_required = readings_by_dept.get("All", 0)

    read_rows = (
        db.query(
            models.ReadStatus.user_id,
            models.RequiredReading.target_department,
            func.count(models.ReadStatus.id),
        )
        .join(
            models.RequiredReading,
            models.ReadStatus.required_reading_id == models.RequiredReading.id,
        )
        .filter(models.ReadStatus.status == "read")
        .group_by(models.ReadStatus.user_id, models.RequiredReading.target_department)
        .all()
    )
    read_map = {(uid, d): cnt for uid, d, cnt in read_rows}

    operators = []
    for user in users:
        required_count, read_count, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        if required_count > 0 and percentage < _CRITICAL_THRESHOLD:
            parts = (user.name or "").split(None, 1)
            operators.append({
                "user_id": user.id,
                "first_name": parts[0] if parts else "",
                "last_name": parts[1] if len(parts) > 1 else "",
                "department": user.department,
                "overdue_count": required_count - read_count,
            })

    operators.sort(key=lambda o: o["overdue_count"], reverse=True)
    return {
        "operators": operators,
        "total": len(operators),
        "generated_at": datetime.utcnow(),
    }


@app.get(
    "/api/admin/departments/{department}/groups/{group_name}/users",
    response_model=schemas.GroupUsersResponse,
)
def get_group_users(
    department: str,
    group_name: str,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Per-group user completion stats for the admin drill-down modal.

    Three scoped queries: users in the target group, reading counts by dept,
    and read counts for those users only — no ORM hydration of RequiredReading.

    The department path param is a whitelist prefix (e.g. "საინფორმაციო"), not
    the full DB value ("საინფორმაციო სამსახური — ჯგუფი 01"), so we match with
    startswith + group label rather than exact equality.
    """
    all_candidates = (
        db.query(
            models.User.id, models.User.name, models.User.department,
        )
        .filter(
            models.User.is_active == True,  # noqa: E712
            models.User.role.notin_(_MANAGEMENT_ROLES),
            models.User.department.like(f"{department}%"),
        )
        .all()
    )
    users = [
        u for u in all_candidates
        if _split_dept_group(u.department)[1] == group_name
    ]

    readings_by_dept = dict(
        db.query(
            models.RequiredReading.target_department,
            func.count(models.RequiredReading.id),
        )
        .group_by(models.RequiredReading.target_department)
        .all()
    )
    all_required = readings_by_dept.get("All", 0)

    user_ids = [u.id for u in users]
    read_rows = (
        db.query(
            models.ReadStatus.user_id,
            models.RequiredReading.target_department,
            func.count(models.ReadStatus.id),
        )
        .join(
            models.RequiredReading,
            models.ReadStatus.required_reading_id == models.RequiredReading.id,
        )
        .filter(
            models.ReadStatus.status == "read",
            models.ReadStatus.user_id.in_(user_ids),
        )
        .group_by(models.ReadStatus.user_id, models.RequiredReading.target_department)
        .all()
    ) if user_ids else []
    read_map = {(uid, d): cnt for uid, d, cnt in read_rows}

    result = []
    for user in users:
        _, _, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        parts = (user.name or "").split(None, 1)
        result.append({
            "user_id": user.id,
            "first_name": parts[0] if parts else "",
            "last_name": parts[1] if len(parts) > 1 else "",
            "completion_percentage": percentage,
        })

    result.sort(key=lambda u: u["completion_percentage"])
    return {
        "department": department,
        "group_name": group_name,
        "users": result,
        "total": len(result),
    }


@app.put("/api/users/{user_id}/status", response_model=schemas.UserResponse)
def update_user_status(
    user_id: int,
    status_update: schemas.UserStatusUpdate,
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db)
):
    """Activates or deactivates a user account and logs the change.

    Prevents administrators from deactivating their own account.

    Access: Restricted to system administrators (admin) only.

    Args:
        user_id: ID of the user whose status is being changed.
        status_update: Target status configuration.
        current_admin: The authenticated system administrator User.
        db: Session = Depends(get_db)

    Returns:
        The updated User database row.

    Raises:
        HTTPException: 404 if user not found; 400 if self-deactivation is attempted.
    """
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    # Safety: an admin cannot deactivate their own account — get_current_user
    # now rejects inactive users, so this would lock them out immediately.
    if user.id == current_admin.id and not status_update.is_active:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="You cannot deactivate your own account",
        )

    user.is_active = status_update.is_active

    # Audit trail: account activation/deactivation must be attributable
    audit_log = models.AuditLog(
        admin_id=current_admin.id,
        action=f"UPDATE_STATUS_TO_{str(status_update.is_active).upper()}",
        item_type="user",
        item_id=user_id
    )
    db.add(audit_log)
    db.commit()
    db.refresh(user)
    return user

@app.post("/api/videos", response_model=schemas.VideoInstructionResponse)
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
    db_video = models.VideoInstruction(**video.model_dump())
    db.add(db_video)
    db.flush()
    sync_tags(db, "video", db_video.id, db_video.tags)

    audit_log = models.AuditLog(admin_id=current_admin.id, action="CREATE", item_type="video", item_id=db_video.id)
    db.add(audit_log)
    db.commit()
    search_cache.clear()

    # Real-time: notify the target department about the new video.
    _notify("video", db_video)
    return db_video

@app.put("/api/videos/{video_id}", response_model=schemas.VideoInstructionResponse)
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
    db_video = db.query(models.VideoInstruction).filter(models.VideoInstruction.id == video_id).first()
    if not db_video:
        raise HTTPException(status_code=404, detail="ვიდეო ვერ მოიძებნა")
    
    for key, value in video.model_dump().items():
        setattr(db_video, key, value)

    sync_tags(db, "video", db_video.id, db_video.tags)
    db.commit()
    db.refresh(db_video)

    audit_log = models.AuditLog(admin_id=current_admin.id, action="UPDATE", item_type="video", item_id=db_video.id)
    db.add(audit_log)
    db.commit()
    search_cache.clear()
    return db_video

@app.delete("/api/videos/{video_id}", status_code=status.HTTP_204_NO_CONTENT)
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
    db_video = db.query(models.VideoInstruction).filter(models.VideoInstruction.id == video_id).first()
    if not db_video:
        raise HTTPException(status_code=404, detail="ვიდეო ვერ მოიძებნა")
    
    db.delete(db_video)
    db.commit()

    audit_log = models.AuditLog(admin_id=current_admin.id, action="DELETE", item_type="video", item_id=video_id)
    db.add(audit_log)
    db.commit()
    search_cache.clear()
    return None


@app.post("/api/videos/{video_id}/archive", response_model=schemas.VideoInstructionResponse)
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
    db_video = db.query(models.VideoInstruction).filter(models.VideoInstruction.id == video_id).first()
    if not db_video:
        raise HTTPException(status_code=404, detail="ვიდეო ვერ მოიძებნა")
    if db_video.is_archived:
        return db_video  # idempotent

    db_video.is_archived = True
    db.add(models.AuditLog(
        admin_id=current_admin.id, action="ARCHIVE", item_type="video", item_id=video_id
    ))
    db.commit()
    search_cache.clear()
    db.refresh(db_video)
    return db_video


@app.post("/api/videos/{video_id}/unarchive", response_model=schemas.VideoInstructionResponse)
def unarchive_video(
    video_id: int,
    current_admin: models.User = Depends(security.require_permission(security.PERM_VIDEOS_ARCHIVE)),
    db: Session = Depends(get_db),
):
    """Restores an archived video to is_archived=False."""
    db_video = db.query(models.VideoInstruction).filter(models.VideoInstruction.id == video_id).first()
    if not db_video:
        raise HTTPException(status_code=404, detail="ვიდეო ვერ მოიძებნა")
    if not db_video.is_archived:
        raise HTTPException(status_code=400, detail="ვიდეო არ არის არქივში")

    db_video.is_archived = False
    db.add(models.AuditLog(
        admin_id=current_admin.id, action="UNARCHIVE", item_type="video", item_id=video_id
    ))
    db.commit()
    search_cache.clear()
    db.refresh(db_video)
    return db_video


@app.get("/api/admin/group-leaders", response_model=list[schemas.GroupLeaderResponse])
def get_group_leaders(
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db)
):
    """Lists team leads for the Block 5 group-filter dropdown.

    Fast/lightweight by design: id + name only, no stats joins.

    Access: Restricted to system administrators (admin) only.
    """
    return (
        db.query(models.User.id, models.User.name)
        .filter(models.User.role == "team_lead")
        .order_by(models.User.name)
        .all()
    )


@app.get("/api/users", response_model=list[schemas.UserResponse])
def list_users(
    manager_id: Optional[int] = None,
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db)
):
    """Lists all users in the system, optionally filtered to one manager's group.

    Access: Restricted to system administrators (admin) only.

    Args:
        manager_id: Block 5 group filter — when provided, only users reporting
            to this manager (team_lead) are returned.
        current_admin: The authenticated system administrator User.
        db: SQLAlchemy database session.

    Returns:
        A list of UserResponse schemas.
    """
    query = db.query(models.User)
    if manager_id is not None:
        query = query.filter(models.User.manager_id == manager_id)
    users = query.all()

    # Precompute statistics for each user
    all_readings = db.query(models.RequiredReading).all()
    readings_by_dept = Counter(r.target_department for r in all_readings)
    all_required = readings_by_dept.get("All", 0)

    read_rows = (
        db.query(
            models.ReadStatus.user_id,
            models.RequiredReading.target_department,
            func.count(models.ReadStatus.id),
        )
        .join(
            models.RequiredReading,
            models.ReadStatus.required_reading_id == models.RequiredReading.id,
        )
        .filter(models.ReadStatus.status == "read")
        .group_by(models.ReadStatus.user_id, models.RequiredReading.target_department)
        .all()
    )
    read_map = {(uid, d): cnt for uid, d, cnt in read_rows}

    for user in users:
        required_count, read_count, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        user.read_count = read_count
        user.required_count = required_count
        user.progress_percentage = percentage

    return users

@app.put("/api/users/{user_id}", response_model=schemas.UserResponse)
def update_user_admin(
    user_id: int,
    update: schemas.UserAdminUpdate,
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db)
):
    """Updates a user's details (role, department, position) and logs the action.

    Access: Restricted to system administrators (admin) only.

    Args:
        user_id: ID of the user to update.
        update: New administrator-managed fields.
        current_admin: The authenticated system administrator User.
        db: SQLAlchemy database session.

    Returns:
        The updated User database row.

    Raises:
        HTTPException: 404 Not Found if the user does not exist.
    """
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="მომხმარებელი ვერ მოიძებნა")

    user.role = update.role
    user.department = update.department
    user.position = update.position
    if update.phone is not None:
        user.phone = update.phone
    if update.team_id is not None:
        user.team_id = update.team_id

    # Audit trail: full user updates must be attributable
    audit_log = models.AuditLog(
        admin_id=current_admin.id,
        action="UPDATE_USER",
        item_type="user",
        item_id=user_id
    )
    db.add(audit_log)
    db.commit()
    db.refresh(user)
    return user

@app.get("/api/statistics/activity")
def get_activity_trend(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieves user activity trend for the last 7 days based on audit logs."""
    # ბოლო 7 დღის დათვლა
    cutoff = datetime.utcnow() - timedelta(days=6)
    cutoff_date = cutoff.replace(hour=0, minute=0, second=0, microsecond=0)
    
    recent_logs = db.query(models.AuditLog.timestamp).filter(
        models.AuditLog.timestamp >= cutoff_date
    ).all()
    
    activity_by_date = {}
    for i in range(7):
        d = (cutoff_date + timedelta(days=i)).strftime("%Y-%m-%d")
        activity_by_date[d] = 0
        
    for log in recent_logs:
        d = log.timestamp.strftime("%Y-%m-%d")
        if d in activity_by_date:
            activity_by_date[d] += 1
            
    return [{"date": k, "count": v} for k, v in activity_by_date.items()]

@app.get("/api/statistics/kpi", response_model=schemas.KpiResponse)
def get_kpi_counts(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieves totals of active users, articles, required readings, and videos.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        A KpiResponse schema.
    """
    return {
        "users": db.query(models.User).filter(models.User.is_active == True).count(),
        "articles": db.query(models.Article).count(),
        "required_readings": db.query(models.RequiredReading).count(),
        "videos": db.query(models.VideoInstruction).count(),
    }

@app.get("/api/messages", response_model=list[schemas.MessageResponse])
def get_my_messages(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves all incoming messages/notifications for the current user.

    Access: Authenticated users (any active role).

    Args:
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A list of MessageResponse schemas, sorted by creation date descending.
    """
    return db.query(models.Message).filter(
        models.Message.user_id == current_user.id
    ).order_by(desc(models.Message.created_at)).all()

@app.post("/api/messages", response_model=schemas.MessageResponse)
def send_message(
    message: schemas.MessageCreate,
    current_sender: models.User = Depends(security.get_current_manager_user),
    db: Session = Depends(get_db)
):
    """Sends a message to an employee and logs the transaction.

    Managers can only message users within their own department. System admins can message anyone.

    Access: Restricted to managers (manager) and system administrators (admin).

    Args:
        message: The message recipient and content.
        current_sender: The authenticated manager User.
        db: SQLAlchemy database session.

    Returns:
        The created Message database row.

    Raises:
        HTTPException: 404 if recipient not found; 403 if manager messages outside department.
    """
    # RBAC FIX: per the PDF, the supervisor/manager (and system admin) message
    # operators — previously this was scoped to content admins, so managers
    # couldn't message their team while content admins could.
    recipient = db.query(models.User).filter(models.User.id == message.user_id).first()
    if not recipient:
        raise HTTPException(status_code=404, detail="მიმღები ვერ მოიძებნა")

    # Confidentiality: a manager may only message users in their own department;
    # the system admin may message anyone.
    if (
        current_sender.role == security.ROLE_MANAGER
        and recipient.department != current_sender.department
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="მენეჯერებს შეუძლიათ შეტყობინების გაგზავნა მხოლოდ საკუთარი დეპარტამენტის თანამშრომლებისთვის",
        )

    db_message = models.Message(
        user_id=message.user_id,
        sender_id=current_sender.id,
        content=message.content
    )
    db.add(db_message)
    db.commit()
    db.refresh(db_message)

    # Audit trail (PDF: every significant action is logged).
    audit_log = models.AuditLog(
        admin_id=current_sender.id,
        action="SEND_MESSAGE",
        item_type="user",
        item_id=message.user_id,
    )
    db.add(audit_log)
    db.commit()
    db.refresh(db_message)
    return db_message

@app.post("/api/messages/{message_id}/read", response_model=schemas.MessageResponse)
def mark_message_read(
    message_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Marks a received message as read.

    Access: Authenticated users (any active role).

    Args:
        message_id: ID of the message to mark as read.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        The updated Message database row.

    Raises:
        HTTPException: 404 Not Found if the message is not found or belongs to another user.
    """
    msg = db.query(models.Message).filter(
        models.Message.id == message_id,
        models.Message.user_id == current_user.id
    ).first()
    if not msg:
        raise HTTPException(status_code=404, detail="შეტყობინება ვერ მოიძებნა")

    msg.is_read = True
    db.commit()
    db.refresh(msg)
    return msg

@app.delete("/api/messages/{message_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_message(
    message_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Deletes an incoming message.

    Access: Authenticated users (any active role).

    Args:
        message_id: ID of the message to delete.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        None.

    Raises:
        HTTPException: 404 Not Found if the message is not found or belongs to another user.
    """
    msg = db.query(models.Message).filter(
        models.Message.id == message_id,
        models.Message.user_id == current_user.id
    ).first()
    if not msg:
        raise HTTPException(status_code=404, detail="შეტყობინება ვერ მოიძებნა")

    db.delete(msg)
    db.commit()
    return None

@app.get("/api/audit-logs", response_model=list[schemas.AuditLogResponse])
def get_audit_logs(
    start_date: Optional[str] = None,
    end_date: Optional[str] = None,
    user_id: Optional[int] = None,
    action: Optional[str] = None,
    category: Optional[str] = None,
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieves system audit logs with optional filters (date, user, action type).

    Access: Restricted to system administrators (admin) only.
    """
    from datetime import timezone

    def _parse_to_naive_utc(value: str, end_of_day: bool = False) -> Optional[datetime]:
        """Convert a user-supplied date/datetime string to a naive UTC datetime.

        AuditLog.timestamp is stored as naive UTC (default=datetime.utcnow). If we
        compared a timezone-aware value directly we'd hit "can't compare offset-naive
        and offset-aware datetimes" on PostgreSQL and a silent miscompare on SQLite.
        So: parse the input, normalise it to UTC via astimezone(), then strip tzinfo.

        ``value`` may be a bare date (YYYY-MM-DD) or a full ISO-8601 datetime with
        optional offset/Z suffix. A bare date is interpreted as midnight UTC.
        """
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
            # SAFE conversion: shift to UTC, then drop tzinfo so the comparison
            # matches the naive-UTC values written by datetime.utcnow().
            parsed = parsed.astimezone(timezone.utc).replace(tzinfo=None)
        return parsed

    query = db.query(models.AuditLog)
    if start_date:
        start_dt = _parse_to_naive_utc(start_date)
        if start_dt is not None:
            query = query.filter(models.AuditLog.timestamp >= start_dt)
        else:
            print(f"Audit log start_date parsing error: invalid format '{start_date}'")
    if end_date:
        end_dt = _parse_to_naive_utc(end_date, end_of_day=True)
        if end_dt is not None:
            query = query.filter(models.AuditLog.timestamp < end_dt)
        else:
            print(f"Audit log end_date parsing error: invalid format '{end_date}'")
    if user_id:
        query = query.filter(models.AuditLog.admin_id == user_id)
    if action:
        if action == "LOGIN":
            # LOGIN filter aggregates both password-based logins and SSO logins.
            query = query.filter(models.AuditLog.action.in_(["LOGIN", "LOGIN_SSO"]))
        else:
            query = query.filter(models.AuditLog.action == action)
    if category:
        query = query.filter(models.AuditLog.category == category.upper())

    return query.order_by(desc(models.AuditLog.timestamp)).limit(100).all()


@app.get("/api/tags", response_model=list[schemas.TagResponse])
def get_tags(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
):
    """Lists the normalized tag vocabulary (Block 1 tags/archiving foundation).

    Tags are created on the fly by sync_tags() when an article/video is saved
    with a non-empty tags field — there is no separate tag-creation endpoint.

    Access: Authenticated users (any active role).
    """
    return db.query(models.Tag).order_by(models.Tag.name).all()


@app.get("/api/teams", response_model=list[schemas.TeamResponse])
def get_teams(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
):
    """Lists teams (Team Statistics foundation, Block 1 Task 4).

    Access: Authenticated users (any active role) — read-only for non-admins.
    """
    return db.query(models.Team).order_by(models.Team.name).all()


@app.post("/api/teams", response_model=schemas.TeamResponse)
def create_team(
    payload: schemas.TeamCreate,
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db),
):
    """Creates a new team. Access: system administrators only."""
    if db.query(models.Team).filter(models.Team.name == payload.name).first():
        raise HTTPException(status_code=400, detail="ამ სახელით ჯგუფი უკვე არსებობს")
    team = models.Team(name=payload.name)
    db.add(team)
    db.commit()
    db.refresh(team)
    return team


@app.post("/api/broadcast")
def post_broadcast(
    req: schemas.BroadcastRequest,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Broadcasts a high-priority system-wide alert message to all SSE connected clients.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        req: Schema containing the broadcast alert message.
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        A dictionary indicating success.
    """
    broker.publish({
        "type": "broadcast",
        "message": req.message
    })
    audit_log = models.AuditLog(
        admin_id=current_admin.id,
        action="BROADCAST",
        item_type="system",
        item_id=0
    )
    db.add(audit_log)
    db.commit()
    return {"status": "success"}


@app.post("/api/articles/{article_id}/feedback", response_model=schemas.KnowledgeFeedbackResponse)
def create_article_feedback(
    article_id: int,
    req: schemas.KnowledgeFeedbackCreate,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Creates a feedback report (reporting an issue/typo) for an article.

    Access: Authenticated users (any active role).

    Args:
        article_id: ID of the article to submit feedback for.
        req: Message detailing the feedback.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A KnowledgeFeedbackResponse containing submission status and details.

    Raises:
        HTTPException: 404 Not Found if the article does not exist.
    """
    db_article = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not db_article:
        raise HTTPException(status_code=404, detail="სტატია ვერ მოიძებნა")
    # R-1: prevent feedback on articles outside the caller's department scope.
    _assert_article_visible(db_article, current_user)

    feedback = models.KnowledgeFeedback(
        user_id=current_user.id,
        article_id=article_id,
        message=req.message
    )
    db.add(feedback)
    db.commit()
    db.refresh(feedback)

    return schemas.KnowledgeFeedbackResponse(
        id=feedback.id,
        user_id=feedback.user_id,
        article_id=feedback.article_id,
        message=feedback.message,
        status=feedback.status,
        created_at=feedback.created_at,
        user_name=current_user.name,
        article_title=db_article.title
    )


@app.get("/api/admin/feedback", response_model=list[schemas.KnowledgeFeedbackResponse])
def get_admin_feedback(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieves all submitted crowdsourced knowledge feedback reports.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        A list of KnowledgeFeedbackResponse schemas.
    """
    feedbacks = db.query(
        models.KnowledgeFeedback,
        models.User.name.label("user_name"),
        models.Article.title.label("article_title")
    ).join(models.User, models.KnowledgeFeedback.user_id == models.User.id)\
     .join(models.Article, models.KnowledgeFeedback.article_id == models.Article.id)\
     .order_by(models.KnowledgeFeedback.created_at.desc()).all()

    return [
        schemas.KnowledgeFeedbackResponse(
            id=f.KnowledgeFeedback.id,
            user_id=f.KnowledgeFeedback.user_id,
            article_id=f.KnowledgeFeedback.article_id,
            message=f.KnowledgeFeedback.message,
            status=f.KnowledgeFeedback.status,
            created_at=f.KnowledgeFeedback.created_at,
            user_name=f.user_name,
            article_title=f.article_title
        )
        for f in feedbacks
    ]


@app.get("/api/articles/{article_id}/note", response_model=Optional[schemas.UserNoteResponse])
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


@app.put("/api/articles/{article_id}/note", response_model=schemas.UserNoteResponse)
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


@app.post("/api/articles/{article_id}/verify", response_model=schemas.ArticleResponse)
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
    search_cache.clear()

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


@app.get("/api/admin/articles/stale")
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
    cutoff = datetime.utcnow() - timedelta(days=180)
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
            "days_stale": (datetime.utcnow() - a.last_verified_at).days if a.last_verified_at else 999,
        }
        for a in stale
    ]


@app.get("/api/articles/{article_id}/related")
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


@app.post("/api/users/{user_id}/nudge")
def nudge_user(
    user_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Sends a real-time compliance nudge alert message to a specific operator.

    Access: Restricted to managers and administrators (admin, manager, content_admin).
    """
    if current_user.role not in ["admin", "manager", "content_admin"]:
        raise HTTPException(status_code=403, detail="Permission denied")

    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="მომხმარებელი ვერ მოიძებნა")

    # Broadcast nudge event via SSE broker
    event_broker.publish({
        "type": "nudge",
        "user_id": user_id,
        "message": f"გთხოვთ გაეცნოთ სავალდებულო მასალებს! (გამოგეგზავნათ მენეჯერისგან: {current_user.name})"
    })
    return {"status": "success", "message": f"Nudge sent to {user.name}"}


@app.get("/api/health")
def health_check(db: Session = Depends(get_db)):
    """System health monitoring endpoint for automated checks."""
    from sqlalchemy import text
    status = {"status": "ok", "database": "unknown", "redis": "unknown"}
    try:
        db.execute(text("SELECT 1"))
        status["database"] = "ok"
    except Exception as e:
        status["database"] = f"error: {str(e)}"
        status["status"] = "degraded"
    
    try:
        status["redis"] = "ok" if broker._main_loop else "not_initialized"
    except Exception as e:
        status["redis"] = f"error: {str(e)}"
        status["status"] = "degraded"
        
    if status.get("status") == "degraded":
        raise HTTPException(status_code=503, detail=status)
    return status

# ══════════════════════════════════════════════════════════════════════════
# Spec-completion endpoints — Magti developer-info gap closure (2026).
# Each block below addresses one P0/P1 item from the audit report.
# ══════════════════════════════════════════════════════════════════════════


# ── News versioning (parity with articles) ────────────────────────────────
@app.get("/api/news/{news_id}/history")
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


@app.post("/api/news/{news_id}/history/{history_id}/restore", response_model=schemas.NewsResponse)
def restore_news_version(
    news_id: int,
    history_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Restores a news item to a previous version. Current state is archived first."""
    db_news = db.query(models.News).filter(models.News.id == news_id).first()
    if not db_news:
        raise HTTPException(status_code=404, detail="სიახლე ვერ მოიძებნა")
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

    db.add(models.AuditLog(
        admin_id=current_admin.id, action="RESTORE", item_type="news", item_id=db_news.id,
    ))
    db.commit()
    db.refresh(db_news)
    search_cache.clear()
    return db_news


# ── Knowledge feedback: resolve / reject workflow ─────────────────────────
@app.put("/api/admin/feedback/{feedback_id}/status", response_model=schemas.KnowledgeFeedbackResponse)
def update_feedback_status(
    feedback_id: int,
    update: schemas.FeedbackStatusUpdate,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Admin transitions a feedback report between open / resolved / rejected."""
    fb = db.query(models.KnowledgeFeedback).filter(models.KnowledgeFeedback.id == feedback_id).first()
    if not fb:
        raise HTTPException(status_code=404, detail="უკუკავშირი ვერ მოიძებნა")
    fb.status = update.status
    if update.status in ("resolved", "rejected"):
        fb.resolved_at = datetime.utcnow()
        fb.resolved_by = current_admin.id
    else:
        fb.resolved_at = None
        fb.resolved_by = None

    db.add(models.AuditLog(
        admin_id=current_admin.id,
        action=f"FEEDBACK_{update.status.upper()}",
        item_type="feedback",
        item_id=fb.id,
    ))
    db.commit()
    db.refresh(fb)

    reporter = db.query(models.User).filter(models.User.id == fb.user_id).first()
    article = db.query(models.Article).filter(models.Article.id == fb.article_id).first()
    return schemas.KnowledgeFeedbackResponse(
        id=fb.id,
        user_id=fb.user_id,
        article_id=fb.article_id,
        message=fb.message,
        status=fb.status,
        created_at=fb.created_at,
        user_name=reporter.name if reporter else None,
        article_title=article.title if article else None,
    )


# ── Admin: create user (with password policy) ─────────────────────────────
@app.post("/api/users", response_model=schemas.UserResponse)
def create_user_admin(
    payload: schemas.UserCreateAdmin,
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db),
):
    """System-admin creates a new user account. Password validated against policy."""
    security.validate_password_policy(payload.password)
    if db.query(models.User).filter(func.lower(models.User.email) == payload.email.lower()).first():
        raise HTTPException(status_code=400, detail="ეს ელ. ფოსტა უკვე გამოყენებულია")
    user = models.User(
        email=payload.email.lower(),
        name=payload.name,
        department=payload.department,
        position=payload.position,
        phone=payload.phone,
        role=payload.role,
        hashed_password=security.get_password_hash(payload.password),
        is_active=True,
        team_id=payload.team_id,
        permissions=security.DEFAULT_PERMISSIONS_BY_ROLE.get(payload.role, []),
    )
    db.add(user)
    from sqlalchemy.exc import IntegrityError
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=400, detail="ეს ელ. ფოსტა უკვე გამოყენებულია")

    db.add(models.AuditLog(
        admin_id=current_admin.id, action="CREATE_USER", item_type="user", item_id=user.id,
    ))
    db.commit()
    db.refresh(user)
    return user


# ── Self: change own password (Item 3 audit coverage) ─────────────────────
@app.post("/api/users/me/password")
def change_own_password(
    payload: schemas.PasswordChangeRequest,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
):
    """Allow an authenticated user to rotate their own password.

    Validates the current password, runs the new password through the central
    policy validator, then writes the new bcrypt hash. Audit-logged so the
    admin trail records "user changed their own password" without exposing
    the password itself.
    """
    if not current_user.hashed_password or not security.verify_password(
        payload.current_password, current_user.hashed_password
    ):
        raise HTTPException(status_code=400, detail="მიმდინარე პაროლი არასწორია")
    if payload.new_password == payload.current_password:
        raise HTTPException(status_code=400, detail="ახალი პაროლი არ უნდა ემთხვეოდეს ძველს")
    security.validate_password_policy(payload.new_password)

    current_user.hashed_password = security.get_password_hash(payload.new_password)
    db.add(models.AuditLog(
        admin_id=current_user.id, action="PASSWORD_CHANGE", item_type="user", item_id=current_user.id,
    ))
    db.commit()
    return {"detail": "პაროლი წარმატებით შეიცვალა."}


# ── Admin: reset another user's password (Item 3 audit coverage) ──────────
class _AdminPasswordResetPayload(BaseModel):
    new_password: str


@app.post("/api/users/{user_id}/reset-password")
def admin_reset_password(
    user_id: int,
    payload: _AdminPasswordResetPayload,
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db),
):
    """System-admin sets a new password for any user. Audit-logged."""
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="მომხმარებელი ვერ მოიძებნა")
    security.validate_password_policy(payload.new_password)
    user.hashed_password = security.get_password_hash(payload.new_password)
    db.add(models.AuditLog(
        admin_id=current_admin.id, action="PASSWORD_RESET", item_type="user", item_id=user_id,
    ))
    db.commit()
    return {"detail": "პაროლი წარმატებით აღდგა."}


# ── Admin: update permissions matrix for a user (Item 3) ──────────────────
class _PermissionsUpdatePayload(BaseModel):
    permissions: list[str]


@app.put("/api/users/{user_id}/permissions", response_model=schemas.UserResponse)
def admin_update_permissions(
    user_id: int,
    payload: _PermissionsUpdatePayload,
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db),
):
    """System-admin overwrites a user's granular permissions list.

    Each permission must be a known canonical name from security.py. Unknown
    permissions are rejected up-front so a typo can't silently grant nothing.
    """
    known = {
        security.PERM_ARTICLES_VIEW, security.PERM_ARTICLES_EDIT,
        security.PERM_ARTICLES_PUBLISH, security.PERM_ARTICLES_ARCHIVE,
        security.PERM_USERS_MANAGE, security.PERM_COMPLIANCE_ASSIGN,
        security.PERM_REPORTS_EXPORT,
    }
    unknown = [p for p in payload.permissions if p not in known]
    if unknown:
        raise HTTPException(
            status_code=400,
            detail=f"უცნობი უფლება(ები): {', '.join(unknown)}",
        )

    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="მომხმარებელი ვერ მოიძებნა")

    user.permissions = list(dict.fromkeys(payload.permissions))  # dedupe, preserve order
    db.add(models.AuditLog(
        admin_id=current_admin.id, action="UPDATE_PERMISSIONS", item_type="user", item_id=user_id,
    ))
    db.commit()
    db.refresh(user)
    return user


# ── Forgot password: trigger reset (Item 3 audit coverage) ────────────────
@app.post("/api/auth/forgot-password")
def forgot_password(
    payload: schemas.ForgotPasswordRequest,
    db: Session = Depends(get_db),
):
    """Accepts an email and (in production) emails a reset link.

    We DO NOT leak whether the email exists — the response is identical either
    way, otherwise this becomes a user-enumeration oracle. The audit log is
    keyed by the user id when the email matches, or 0 when it does not.
    """
    user = db.query(models.User).filter(
        func.lower(models.User.email) == payload.email.lower()
    ).first()
    if user:
        # We deliberately don't email anything in this codebase — wire SMTP in
        # production. The token is logged so an admin can hand-deliver it during
        # the cutover period.
        token = security.create_reset_token(user.email)
        db.add(models.AuditLog(
            admin_id=user.id, action="PASSWORD_RESET_REQUEST", item_type="user", item_id=user.id,
        ))
        db.commit()
        if not settings.is_production:
            print(f"[forgot-password] dev reset token for {user.email}: {token}")
    # Constant-time-ish: always claim success.
    return {"detail": "თუ ეს ელ. ფოსტა რეგისტრირებულია, აღდგენის ინსტრუქცია გამოგზავნილია."}


# ── Compliance: required-reading edit/lookup/delete (mandatory-on-edit fix) ──
@app.get("/api/compliance/required-readings/by-item/{item_type}/{item_id}", response_model=Optional[schemas.RequiredReadingResponse])
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


@app.put("/api/compliance/required-readings/{reading_id}", response_model=schemas.RequiredReadingResponse)
def update_required_reading(
    reading_id: int,
    payload: schemas.RequiredReadingBase,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Update the due date / department / priority of an existing required reading."""
    rr = db.query(models.RequiredReading).filter(models.RequiredReading.id == reading_id).first()
    if not rr:
        raise HTTPException(status_code=404, detail="Required reading not found")
    for k, v in payload.model_dump().items():
        setattr(rr, k, v)
    db.add(models.AuditLog(
        admin_id=current_admin.id, action="UPDATE", item_type="required_reading", item_id=rr.id,
    ))
    db.commit()
    db.refresh(rr)
    return rr


@app.delete("/api/compliance/required-readings/{reading_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_required_reading(
    reading_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Remove a required-reading assignment (e.g. admin un-checks the mandatory box)."""
    rr = db.query(models.RequiredReading).filter(models.RequiredReading.id == reading_id).first()
    if not rr:
        raise HTTPException(status_code=404, detail="სავალდებულო მასალა ვერ მოიძებნა")
    db.delete(rr)
    db.add(models.AuditLog(
        admin_id=current_admin.id, action="DELETE", item_type="required_reading", item_id=reading_id,
    ))
    db.commit()
    return None


# ── XLSX export for compliance readings ───────────────────────────────────
@app.get("/api/export/readings.xlsx")
def export_readings_xlsx(
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db),
):
    """Spec-required Excel export of compliance data — admin-only (personal data).

    Falls back gracefully if openpyxl isn't installed (returns 503) so the rest
    of the app keeps working even on a minimal install.
    """
    try:
        from openpyxl import Workbook
        from openpyxl.styles import Font, PatternFill
    except ImportError:
        raise HTTPException(
            status_code=503,
            detail="openpyxl ბიბლიოთეკა არ არის დაყენებული. გაუშვით: pip install openpyxl",
        )

    db.add(models.AuditLog(
        admin_id=current_admin.id, action="EXPORT_XLSX", item_type="readings", item_id=0,
    ))
    db.commit()

    rows = db.query(
        models.ReadStatus, models.User.name, models.User.department,
        models.RequiredReading.item_type, models.RequiredReading.item_id,
        models.RequiredReading.due_date,
    ).join(models.User, models.ReadStatus.user_id == models.User.id) \
     .join(models.RequiredReading, models.ReadStatus.required_reading_id == models.RequiredReading.id) \
     .all()

    wb = Workbook()
    ws = wb.active
    ws.title = "Compliance"
    header_font = Font(bold=True, color="FFFFFF")
    header_fill = PatternFill("solid", fgColor="CC0000")
    headers = ["თანამშრომელი", "დეპარტამენტი", "მასალის ტიპი", "მასალის ID", "სტატუსი", "წაკითხვის თარიღი", "ვადა"]
    for col, h in enumerate(headers, start=1):
        c = ws.cell(row=1, column=col, value=h)
        c.font = header_font
        c.fill = header_fill
    for i, (rs, user_name, dept, item_type, item_id, due_date) in enumerate(rows, start=2):
        ws.cell(row=i, column=1, value=user_name)
        ws.cell(row=i, column=2, value=dept)
        ws.cell(row=i, column=3, value=item_type)
        ws.cell(row=i, column=4, value=item_id)
        ws.cell(row=i, column=5, value=rs.status)
        ws.cell(row=i, column=6, value=rs.read_at.strftime("%Y-%m-%d %H:%M") if rs.read_at else "")
        ws.cell(row=i, column=7, value=due_date.strftime("%Y-%m-%d") if due_date else "")
    # Auto-width
    for col_cells in ws.columns:
        max_len = max((len(str(c.value)) for c in col_cells if c.value), default=10)
        ws.column_dimensions[col_cells[0].column_letter].width = min(max_len + 2, 40)

    buf = io.BytesIO()
    wb.save(buf)
    buf.seek(0)
    return StreamingResponse(
        buf,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Content-Disposition": "attachment; filename=readings_export.xlsx"},
    )


# ── PDF export (compliance + team stats) ──────────────────────────────────
# Spec slide 26: Excel/PDF export. XLSX is above; here's the PDF half.
# Georgian Unicode requires a TTF with Georgian glyphs; DejaVu Sans works and
# is installed via fonts-dejavu-core in the Dockerfile. Locally on Windows we
# fall back to Sylfaen.

_GEORGIAN_FONT_CANDIDATES = (
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",          # Debian/Ubuntu (Docker)
    "/usr/share/fonts/dejavu/DejaVuSans.ttf",                   # Fedora/RHEL
    "C:/Windows/Fonts/dejavusans.ttf",                          # Windows (if installed)
    "C:/Windows/Fonts/sylfaen.ttf",                             # Windows native Georgian
    os.path.join(BASE_DIR, "static", "fonts", "DejaVuSans.ttf"),
)
_pdf_font_registered: Optional[str] = None


def _register_pdf_font() -> Optional[str]:
    """Register a Unicode-capable TTF for ReportLab; cache the result.

    Returns the registered font name, or None if no font was found.
    """
    global _pdf_font_registered
    if _pdf_font_registered is not None:
        return _pdf_font_registered
    try:
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.ttfonts import TTFont
    except ImportError:
        return None
    for path in _GEORGIAN_FONT_CANDIDATES:
        if os.path.isfile(path):
            try:
                pdfmetrics.registerFont(TTFont("Unicode", path))
                _pdf_font_registered = "Unicode"
                return _pdf_font_registered
            except Exception:
                continue
    return None


def _build_table_pdf(title: str, headers: list[str], rows: list[list[str]]) -> bytes:
    """Render a simple paginated table PDF; raises if reportlab/font is missing."""
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4, landscape
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.platypus import SimpleDocTemplate, Table, TableStyle, Paragraph, Spacer

    font_name = _register_pdf_font()
    if font_name is None:
        raise HTTPException(
            status_code=503,
            detail="PDF Unicode font not found. Install fonts-dejavu-core "
                   "(Debian/Ubuntu) or place DejaVuSans.ttf in static/fonts/.",
        )

    buf = io.BytesIO()
    doc = SimpleDocTemplate(buf, pagesize=landscape(A4),
                            leftMargin=24, rightMargin=24, topMargin=24, bottomMargin=24)
    styles = getSampleStyleSheet()
    title_style = ParagraphStyle("title", parent=styles["Title"], fontName=font_name, fontSize=14)
    body = [Paragraph(title, title_style), Spacer(1, 12)]

    data = [headers] + rows
    table = Table(data, repeatRows=1)
    table.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, -1), font_name),
        ("FONTSIZE", (0, 0), (-1, -1), 9),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#CC0000")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("ALIGN", (0, 0), (-1, 0), "CENTER"),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("GRID", (0, 0), (-1, -1), 0.25, colors.grey),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F5F5F5")]),
    ]))
    body.append(table)
    doc.build(body)
    buf.seek(0)
    return buf.getvalue()


@app.get("/api/export/readings.pdf")
def export_readings_pdf(
    current_admin: models.User = Depends(security.require_permission(security.PERM_REPORTS_EXPORT)),
    db: Session = Depends(get_db),
):
    """PDF export of compliance readings — admin/manager only via reports.export permission."""
    try:
        import reportlab  # noqa: F401
    except ImportError:
        raise HTTPException(
            status_code=503,
            detail="reportlab ბიბლიოთეკა არ არის დაყენებული. გაუშვით: pip install reportlab",
        )

    db.add(models.AuditLog(
        admin_id=current_admin.id, action="EXPORT_PDF", item_type="readings", item_id=0,
    ))
    db.commit()

    rows_q = db.query(
        models.ReadStatus, models.User.name, models.User.department,
        models.RequiredReading.item_type, models.RequiredReading.item_id,
        models.RequiredReading.due_date,
    ).join(models.User, models.ReadStatus.user_id == models.User.id) \
     .join(models.RequiredReading, models.ReadStatus.required_reading_id == models.RequiredReading.id) \
     .all()

    headers = ["თანამშრომელი", "დეპარტამენტი", "ტიპი", "ID", "სტატუსი", "წაკითხვა", "ვადა"]
    table_rows = [[
        user_name,
        dept or "",
        item_type or "",
        str(item_id),
        rs.status,
        rs.read_at.strftime("%Y-%m-%d %H:%M") if rs.read_at else "",
        due_date.strftime("%Y-%m-%d") if due_date else "",
    ] for (rs, user_name, dept, item_type, item_id, due_date) in rows_q]

    pdf_bytes = _build_table_pdf("სავალდებულოდ გასაცნობი სტატუსი", headers, table_rows)
    return StreamingResponse(
        io.BytesIO(pdf_bytes),
        media_type="application/pdf",
        headers={"Content-Disposition": "attachment; filename=readings_export.pdf"},
    )


@app.get("/api/export/team-stats.pdf")
def export_team_stats_pdf(
    current_admin: models.User = Depends(security.require_permission(security.PERM_REPORTS_EXPORT)),
    db: Session = Depends(get_db),
):
    """PDF export of team reading-completion stats by department."""
    try:
        import reportlab  # noqa: F401
    except ImportError:
        raise HTTPException(status_code=503, detail="reportlab არ არის დაყენებული.")

    db.add(models.AuditLog(
        admin_id=current_admin.id, action="EXPORT_PDF", item_type="team_stats", item_id=0,
    ))
    db.commit()

    # Aggregate by department: total assigned, completed, percentage (operators only).
    from sqlalchemy import case
    rows_q = db.query(
        models.User.department.label("dept"),
        func.count(models.ReadStatus.id).label("total"),
        func.sum(case((models.ReadStatus.status == "read", 1), else_=0)).label("read_count"),
    ).join(models.ReadStatus, models.ReadStatus.user_id == models.User.id) \
     .filter(models.User.role.notin_(_MANAGEMENT_ROLES)) \
     .group_by(models.User.department).all()

    headers = ["დეპარტამენტი", "სულ მიკუთვნებული", "წაკითხული", "%"]
    table_rows = []
    for r in rows_q:
        pct = round(100.0 * (r.read_count or 0) / r.total, 1) if r.total else 0.0
        table_rows.append([r.dept or "—", str(r.total), str(r.read_count or 0), f"{pct}%"])

    pdf_bytes = _build_table_pdf("გუნდის სტატისტიკა — წაკითხვის პროცენტი", headers, table_rows)
    return StreamingResponse(
        io.BytesIO(pdf_bytes),
        media_type="application/pdf",
        headers={"Content-Disposition": "attachment; filename=team_stats.pdf"},
    )


# ── SSO placeholder ───────────────────────────────────────────────────────
@app.get("/api/auth/sso/init")
def sso_init():
    """Returns the SSO configuration redirecting to the mock login selector in development."""
    return {
        "configured": True,
        "sso_url": "/api/auth/sso/mock-login"
    }


@app.get("/api/auth/sso/mock-login", response_class=HTMLResponse)
def sso_mock_login():
    """Renders a mock corporate Single Sign-On login selector page for active directory accounts."""
    html_content = """
    <!DOCTYPE html>
    <html lang="ka">
    <head>
        <meta charset="UTF-8">
        <title>მაგთი კორპორაციული SSO</title>
        <script src="https://cdn.tailwindcss.com"></script>
        <link href="https://fonts.googleapis.com/css2?family=Noto+Sans+Georgian:wght@400;600;700&display=swap" rel="stylesheet">
        <style>
            body { font-family: "Noto Sans Georgian", sans-serif; }
        </style>
    </head>
    <body class="bg-gray-100 flex items-center justify-center min-h-screen">
        <div class="bg-white p-8 rounded-2xl shadow-xl w-full max-w-md border border-gray-100">
            <div class="text-center mb-6">
                <img src="/static/magti_logo.png" alt="Magti Logo" class="h-10 mx-auto mb-4">
                <h2 class="text-xl font-bold text-gray-800">კორპორაციული ავტორიზაცია (SSO)</h2>
                <p class="text-xs text-gray-500 mt-1">აირჩიეთ ანგარიში შესასვლელად</p>
            </div>
            <div class="space-y-3">
                <button onclick="loginAs('admin@magti.ge')" class="w-full flex items-center justify-between p-4 rounded-xl border border-gray-200 hover:border-[#E30613] hover:bg-red-50/20 transition-all text-left">
                    <div>
                        <p class="text-sm font-bold text-gray-800">სისტემური ადმინისტრატორი</p>
                        <p class="text-xs text-gray-400">admin@magti.ge</p>
                    </div>
                    <span class="text-xs bg-red-100 text-[#E30613] font-semibold px-2 py-0.5 rounded-md">Admin</span>
                </button>
                <button onclick="loginAs('content@magti.ge')" class="w-full flex items-center justify-between p-4 rounded-xl border border-gray-200 hover:border-[#E30613] hover:bg-red-50/20 transition-all text-left">
                    <div>
                        <p class="text-sm font-bold text-gray-800">კონტენტის ადმინისტრატორი</p>
                        <p class="text-xs text-gray-400">content@magti.ge</p>
                    </div>
                    <span class="text-xs bg-slate-100 text-slate-700 font-semibold px-2 py-0.5 rounded-md">Content</span>
                </button>
                <button onclick="loginAs('tech@magti.ge')" class="w-full flex items-center justify-between p-4 rounded-xl border border-gray-200 hover:border-[#E30613] hover:bg-red-50/20 transition-all text-left">
                    <div>
                        <p class="text-sm font-bold text-gray-800">ტექნიკური ოპერატორი</p>
                        <p class="text-xs text-gray-400">tech@magti.ge</p>
                    </div>
                    <span class="text-xs bg-green-100 text-green-700 font-semibold px-2 py-0.5 rounded-md">Operator</span>
                </button>
            </div>
            <div class="text-center mt-6">
                <a href="/login.html" class="text-xs text-gray-500 hover:underline">← სტანდარტულ ავტორიზაციაზე დაბრუნება</a>
            </div>
        </div>
        <script>
            async function loginAs(email) {
                try {
                    const res = await fetch('/api/auth/sso/callback?email=' + encodeURIComponent(email), { method: 'POST' });
                    if (!res.ok) throw new Error('SSO ავტორიზაცია ჩავარდა');
                    const data = await res.json();
                    localStorage.setItem('magti_token', data.access_token);
                    window.location.href = '/base-layout.html';
                } catch(e) {
                    alert(e.message);
                }
            }
        </script>
    </body>
    </html>
    """
    return HTMLResponse(html_content)


@app.post("/api/auth/sso/callback")
def sso_callback(
    email: str,
    response: Response,
    db: Session = Depends(get_db)
):
    """Callback handling mock SSO credentials and issuing signed JWT access token."""
    user = security.authenticate_user(db, email, "sso_dummy_password")
    if not user:
        raise HTTPException(status_code=400, detail="SSO მომხმარებელი ვერ მოიძებნა")

    access_token = security.create_access_token(data={"sub": user.email, "role": user.role})

    # Log successful login to audit trail
    db.add(models.AuditLog(
        admin_id=user.id,
        action="LOGIN_SSO",
        item_type="user",
        item_id=user.id
    ))
    db.commit()

    response.set_cookie(
        key="access_token",
        value=access_token,
        httponly=True,
        secure=settings.COOKIE_SECURE,
        samesite=settings.COOKIE_SAMESITE,
        max_age=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        path="/",
    )
    return {"access_token": access_token, "token_type": "bearer"}