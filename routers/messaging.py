"""Real-time + messaging routes: the SSE event stream, direct manager→operator
messages, and the admin broadcast alert — Phase 7 of the main.py monolith
split.

Non-contiguous in the original main.py: /api/stream sat alone near the top
of the file, while messages/broadcast were gathered near the bottom —
combined here into one "real-time" domain per the roadmap's non-contiguous-
domain procedure.
"""
import asyncio
import json
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import StreamingResponse
from sqlalchemy import desc
from sqlalchemy.orm import Session

import models
import schemas
import security
from database import SessionLocal, get_db
from db_helpers import get_or_404, log_audit
from state import _safe_publish, broker

router = APIRouter(tags=["messaging"])


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
            "is_admin": user.role in security.CONTENT_ADMIN_ROLES,
            "department": user.department,
            "role": user.role,
        }


@router.get("/api/stream")
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
    user_id = user_info["user_id"]
    user_dept = user_info["department"]
    user_role = user_info["role"]

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
                target_dept = event.get("target_department", "All")
                target_role = event.get("target_role", "All")
                # Optional per-user targeting (e.g. nudge/message events) — absent
                # means no extra restriction, same permissive default as "All".
                target_user_id = event.get("target_user_id")
                dept_match = target_dept == "All" or target_dept == user_dept
                role_match = target_role == "All" or target_role == user_role
                user_match = target_user_id is None or target_user_id == user_id
                if is_admin or (dept_match and role_match and user_match):
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


@router.get("/api/messages", response_model=list[schemas.MessageResponse])
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


@router.get("/api/messages/sent", response_model=list[schemas.MessageResponse])
def get_sent_messages(
    current_user: models.User = Depends(security.get_current_manager_user),
    db: Session = Depends(get_db)
):
    """Retrieves all sent messages by the current manager/administrator.

    Access: Restricted to managers (manager) and system administrators (admin).
    """
    return db.query(models.Message).filter(
        models.Message.sender_id == current_user.id
    ).order_by(desc(models.Message.created_at)).all()


@router.post("/api/messages", response_model=schemas.MessageResponse)
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
    recipient = get_or_404(db, models.User, message.user_id, "მიმღები ვერ მოიძებნა")

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
    log_audit(db, admin_id=current_sender.id, action="SEND_MESSAGE", item_type="user", item_id=message.user_id)
    db.commit()
    db.refresh(db_message)
    return db_message


@router.post("/api/messages/{message_id}/read", response_model=schemas.MessageResponse)
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


@router.delete("/api/messages/{message_id}", status_code=status.HTTP_204_NO_CONTENT)
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


@router.post("/api/broadcast")
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
    _safe_publish({
        "type": "broadcast",
        "message": req.message,
        "target_department": req.target_department,
        "target_role": req.target_role,
    })
    log_audit(db, admin_id=current_admin.id, action="BROADCAST", item_type="system", item_id=0)
    db.commit()
    return {"status": "success"}
