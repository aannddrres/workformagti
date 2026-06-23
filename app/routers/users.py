"""Users domain router (Phase 2.3 — strangler extraction from main.py).

Scope: all /api/users/* endpoints.
  - Self-service: GET/PUT /me, POST /me/password
  - Admin CRUD: GET /, POST /, PUT /{user_id}, PUT /{user_id}/status
  - Admin tools: POST /{user_id}/reset-password, PUT /{user_id}/permissions
  - Manager tools: POST /{user_id}/nudge

This module imports ONLY shared infrastructure (models, schemas, security,
database) and never imports `main` at module-load time to prevent circular
imports. The `broker` singleton is fetched lazily at request time via
`import main` inside the nudge handler body — the same pattern used in
`app/main.py`'s lifespan.

Decorator paths are stripped of the /api/users prefix because the router is
mounted with prefix="/api/users", keeping them byte-identical to the originals
in the OpenAPI schema.
"""
from collections import Counter

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

import models
import schemas
import security
from database import get_db

router = APIRouter(prefix="/api/users", tags=["users"])


# ── Private helpers ───────────────────────────────────────────────────────────

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


# ── Local payload models (previously inlined in main.py) ─────────────────────

class _AdminPasswordResetPayload(BaseModel):
    new_password: str


class _PermissionsUpdatePayload(BaseModel):
    permissions: list[str]


# ── Self-service endpoints ────────────────────────────────────────────────────

@router.get("/me", response_model=schemas.UserResponse)
def read_users_me(current_user: models.User = Depends(security.get_current_user)):
    """Retrieves the profile information of the currently authenticated user.

    Access: Authenticated users (any active role).

    Args:
        current_user: The authenticated User object.

    Returns:
        The current User details.
    """
    return current_user


@router.put("/me", response_model=schemas.UserResponse)
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
    db.commit()
    db.refresh(current_user)
    return current_user


# ── Self: change own password (Item 3 audit coverage) ─────────────────────

@router.post("/me/password")
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


# ── Admin: list all users ─────────────────────────────────────────────────────

@router.get("", response_model=list[schemas.UserResponse])
def list_users(
    manager_id: int | None = None,
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


# ── Admin: create user (with password policy) ─────────────────────────────────

@router.post("", response_model=schemas.UserResponse)
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


# ── Admin: update user details ────────────────────────────────────────────────

@router.put("/{user_id}", response_model=schemas.UserResponse)
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


# ── Admin: activate / deactivate user ────────────────────────────────────────

@router.put("/{user_id}/status", response_model=schemas.UserResponse)
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


# ── Admin: reset another user's password ─────────────────────────────────────

@router.post("/{user_id}/reset-password")
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


# ── Admin: update permissions matrix ─────────────────────────────────────────

@router.put("/{user_id}/permissions", response_model=schemas.UserResponse)
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


# ── Manager: send compliance nudge to a user ─────────────────────────────────

@router.post("/{user_id}/nudge")
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

    # Lazy import to avoid circular dependency: main imports app, app.routers.users
    # must not import main at module level.
    import main as _main
    _main.broker.publish({
        "type": "nudge",
        "user_id": user_id,
        "message": f"გთხოვთ გაეცნოთ სავალდებულო მასალებს! (გამოგეგზავნათ მენეჯერისგან: {current_user.name})"
    })
    return {"status": "success", "message": f"Nudge sent to {user.name}"}
