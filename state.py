"""Shared, dependency-free app singletons: TTL caches, the SSE event broker,
the rate limiter, and the best-effort notification helpers built on top of
the broker.

Deliberately has no import of `main` or any `routers.*` module — every
future router file, and `main.py` itself, imports from here as an ordinary
top-level import with no circular-import risk.
"""
import os
import json
import asyncio
import logging
from datetime import timedelta
from typing import Optional

import redis.asyncio as redis_async
from slowapi import Limiter
from slowapi.util import get_remote_address

from database import get_tbilisi_time

logger = logging.getLogger("magti")


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
            if get_tbilisi_time() < expiry:
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
        expiry = get_tbilisi_time() + timedelta(seconds=self.ttl)
        self._cache[key] = (value, expiry)

    def clear(self):
        """Clears all cached entries, forcing fresh database retrievals on subsequent requests."""
        self._cache.clear()


search_cache = InMemoryTTLCache(60)
category_cache = InMemoryTTLCache(60)


# Rate limiting — brute-force / credential-stuffing mitigation for the
# auth endpoints. Per-IP, in-memory (no Redis backend needed at this scale).
limiter = Limiter(key_func=get_remote_address)


# ── Real-time notifications (Server-Sent Events) ──────────────────────────
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
        self._ready = None  # set later inside the loop, once one is running

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
                logger.info("SSE Broker: connected to Redis at %s — multi-worker safe.", self.redis_url)
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
                logger.warning("%s", warning)
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
                    logger.warning("SSE Broker: Failed to publish to Redis (%s). Routing to local queues.", e)
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


def _safe_publish(payload: dict) -> None:
    """Publish an SSE event without ever raising or failing silently.

    All broadcast call sites are documented as best-effort — a live-push
    failure should never turn an already-successful database write into a
    client-visible 500 (unhandled), and it should never vanish with zero
    trace either (bare except/pass). Log it and move on either way.
    """
    try:
        broker.publish(payload)
    except Exception as e:
        logger.warning("SSE broadcast failed (type=%s): %s", payload.get("type"), e)


def _notify(event_type: str, item) -> None:
    """Publish a 'new content' event to connected clients (best-effort)."""
    _safe_publish({
        "type": event_type,
        "id": item.id,
        "title": item.title,
        "target_department": getattr(item, "target_department", "All"),
    })


def _notify_revision(article, editor_name: str, summary: dict) -> None:
    """Broadcast that an existing article was edited (best-effort).

    Richer than _notify: carries the new version number, the editor's name, and
    the block-level add/remove counts from diffing.diff_html so the client can
    show a meaningful "x changed" toast. Gated by the caller on notify_operators.
    """
    _safe_publish({
        "type": "article_revision",
        "id": article.id,
        "title": article.title,
        "target_department": getattr(article, "target_department", "All"),
        "version": article.version,
        "editor": editor_name,
        "added": summary.get("added", 0),
        "removed": summary.get("removed", 0),
    })
