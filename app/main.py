import os
import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request, Response
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from config import settings
from app.routers import auth
from app.routers import users as users_router
from app.routers import news as news_router
from app.routers import categories as categories_router
from app.routers import articles as articles_router

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


@asynccontextmanager
async def lifespan(app: FastAPI):
    """App-lifecycle plumbing: start the queue writer, set the broker's loop.
    Threadpool stays at the anyio default (40) — with sync SQLAlchemy + SQLite,
    every extra worker is a potential writer-lock contender. Tested 80 and 200;
    both made article-view latency worse. The default is the sweet spot here."""
    import main
    app.state.log_queue = asyncio.Queue(maxsize=main.LOG_QUEUE_MAXSIZE)
    writer_task = asyncio.create_task(main._log_writer(app.state.log_queue))
    main.broker.set_loop(asyncio.get_running_loop())
    try:
        yield
    finally:
        await app.state.log_queue.put(main._LOG_SHUTDOWN)
        try:
            await asyncio.wait_for(writer_task, timeout=5.0)
        except asyncio.TimeoutError:
            writer_task.cancel()


def create_app() -> FastAPI:
    app = FastAPI(title="Magti Internal Portal API", lifespan=lifespan)

    # Ensure the uploads directory exists
    upload_dir = settings.UPLOAD_DIR
    if not os.path.isabs(upload_dir):
        upload_dir = os.path.join(BASE_DIR, upload_dir)
    os.makedirs(upload_dir, exist_ok=True)

    # Serve static assets (JS, CSS) from the 'static' directory.
    app.mount("/static", StaticFiles(directory=os.path.join(BASE_DIR, "static")), name="static")

    # Serve uploaded files.
    app.mount("/uploads", StaticFiles(directory=upload_dir), name="uploads")

    # CORS — origins are env-driven.
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.CORS_ORIGINS,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # ── Routers (Phase 2.x: incremental strangler extraction from main.py) ──
    app.include_router(auth.router)
    app.include_router(users_router.router)    # Phase 2.3
    app.include_router(news_router.router)     # Phase 2.4
    app.include_router(categories_router.router)  # Phase 2.5
    app.include_router(articles_router.router)    # Phase 2.6

    # ── Security Headers Middleware ──
    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        """Baseline hardening headers applied to every response."""
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        if settings.is_production:
            response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; "
            "script-src 'self' 'unsafe-inline' "
            "https://cdn.jsdelivr.net "
            "https://cdnjs.cloudflare.com; "
            "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://fonts.googleapis.com https://fonts.gstatic.com; "
            "font-src 'self' data: https://cdnjs.cloudflare.com https://fonts.gstatic.com; "
            "img-src 'self' data: https:; "
            "connect-src 'self'; "
            "frame-ancestors 'none'; "
            "base-uri 'self'; "
            "object-src 'none'; "
            "form-action 'self'"
        )
        return response

    return app
