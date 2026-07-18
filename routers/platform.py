"""Platform/misc routes: static pages, health check, uploads, tags, and the
notifications-summary bell icon payload — Phase 3 of the main.py monolith
split.

Bundles several small, otherwise-scattered routes into one module rather than
one file per route, per the roadmap's "Platform/misc" phase.
"""
import os
import uuid
from datetime import timedelta

from fastapi import APIRouter, Depends, File, HTTPException, Response, UploadFile, status
from fastapi.responses import FileResponse, HTMLResponse
from sqlalchemy import desc, func
from sqlalchemy.orm import Session

import models
import schemas
import security
from config import settings
from database import get_db, get_tbilisi_time
from db_helpers import log_audit, resolve_item_titles_bulk
from routers.stats import _MANAGEMENT_ROLES, _split_dept_group
from state import broker

router = APIRouter()

# Project root — one level up from this routers/ package, matching main.py's
# own BASE_DIR (os.path.dirname(os.path.abspath(__file__)) from main.py's
# location, which sits at the project root).
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

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


@router.get("/static/magti_logo.png")
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


@router.get("/", response_class=HTMLResponse)
def serve_index():
    """Serves the main application landing/portal interface.

    Access: Open to all users (anonymous and authenticated). The frontend
    authenticates and redirects to `/login.html` if no token is found.
    """
    # Entry point. base-layout.html's auth guard bounces to /login.html when no
    # token is present.
    return _serve_page("base-layout.html")


@router.get("/login.html", response_class=HTMLResponse)
def serve_login():
    """Serves the portal login page.

    Access: Open to all users.
    """
    return _serve_page("login.html")


@router.get("/base-layout.html", response_class=HTMLResponse)
def serve_base_layout():
    """Serves the primary base-layout template.

    Access: Open to all users. Authenticated sessions are validated on the client side.
    """
    # login.html redirects here (relative URL) after a successful login.
    return _serve_page("base-layout.html")


@router.get("/article.html", response_class=HTMLResponse)
def serve_article():
    """Serves the standalone article page.

    Access: Open to all users. Authenticated sessions are validated on the client side.
    """
    return _serve_page("article.html")


@router.get("/favicon.ico", include_in_schema=False)
def favicon():
    """Responds to favicon requests with a 204 No Content.

    Access: Open to all users. Used to prevent server errors on automatic browser fetches.
    """
    # Browsers auto-request this; return 204 instead of a noisy 404.
    return Response(status_code=204)


@router.get("/api/notifications/summary")
def get_notifications_summary(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
):
    """Combined bell-icon payload — unread mandatory readings + recent news + unread messages count.

    Item 15: the notifications popover used to call /my-readings + /news + /messages
    in sequence, each of which does its own DB roundtrip. This consolidates the
    fetch into ONE endpoint so the popover opens in roughly a single RTT.
    """
    now = get_tbilisi_time()

    # 1) Unread/overdue required readings (visible-to-this-user). Management
    # roles manage the system rather than consume operator-level training
    # content, so they get no required-reading items here - mirrors get_my_readings above.
    unread_readings = []
    if current_user.role not in _MANAGEMENT_ROLES:
        _dept_prefix = _split_dept_group(current_user.department)[0]
        readings = db.query(models.RequiredReading).filter(
            models.RequiredReading.target_department.in_([current_user.department, _dept_prefix, "All"])
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
        # Title batch lookup
        title_map = resolve_item_titles_bulk(db, [(r.item_type, r.item_id) for r in readings])

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
    if current_user.role not in security.CONTENT_ADMIN_ROLES:
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


@router.post("/api/upload")
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
            detail="დაუშვებელი ფაილის ტიპი '%s'. დაშვებულია: %s"
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

    log_audit(db, admin_id=current_admin.id, action="UPLOAD", item_type="file", item_id=0)
    db.commit()

    return {"url": f"/uploads/{unique_filename}", "filename": unique_filename}


@router.get("/api/tags", response_model=list[schemas.TagResponse])
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


@router.get("/api/health")
def health_check(db: Session = Depends(get_db)):
    """System health monitoring endpoint for automated checks."""
    from sqlalchemy import text
    health_status = {"status": "ok", "database": "unknown", "redis": "unknown"}
    try:
        db.execute(text("SELECT 1"))
        health_status["database"] = "ok"
    except Exception as e:
        health_status["database"] = f"error: {str(e)}"
        health_status["status"] = "degraded"

    # Worker Detection: check if we are in a multi-worker environment
    is_multi_worker = (
        "GUNICORN_CMD_ARGS" in os.environ or
        "WEB_CONCURRENCY" in os.environ or
        "UVICORN_WORKERS" in os.environ
    )

    try:
        # Check active Redis connectivity using broker._use_redis
        if getattr(broker, "_use_redis", False):
            health_status["redis"] = "ok"
        else:
            health_status["redis"] = "degraded_fallback"
            if is_multi_worker:
                health_status["status"] = "degraded"
    except Exception as e:
        health_status["redis"] = f"error: {str(e)}"
        health_status["status"] = "degraded"

    if health_status.get("status") == "degraded":
        raise HTTPException(status_code=503, detail=health_status)
    return health_status
