"""Users/RBAC/Admin routes: the authenticated user's own profile, admin user
CRUD, role/permission management, and team management — Phase 13 of the
main.py monolith split.

CRITICAL ordering invariant: PUT /api/users/me and PUT /api/users/{user_id}
are both 2-segment PUT routes — one literal, one path param. Starlette
matches same-method routes in registration order, so ALL /api/users/me...
routes (GET me, PUT me, POST me/password) must stay registered before any
/api/users/{user_id}... route in this file, exactly as they appear below —
do not alphabetize or reorder them.
"""
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func
from sqlalchemy.orm import Session

import models
import schemas
import security
from database import get_db
from db_helpers import get_or_404, log_audit
from routers.stats import _get_read_counts_by_user_dept, _reading_progress
from state import _safe_publish

router = APIRouter(tags=["users"])


@router.get("/api/users/me", response_model=schemas.CurrentUserResponse)
def read_users_me(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
):
    """Retrieves the profile information of the currently authenticated user.

    Access: Authenticated users (any active role).

    Args:
        current_user: The authenticated User object.

    Returns:
        The current User details, plus permission-derived UI flags
        (can_view_audit_log) the frontend can't compute on its own since
        RBAC lives in the DB, not in anything shipped to the client.
    """
    resp = schemas.CurrentUserResponse.model_validate(current_user)
    resp.can_view_audit_log = security.role_has_permission(
        db, current_user, security.PERM_SYSTEM_AUDIT
    )
    return resp


@router.put("/api/users/me", response_model=schemas.UserResponse)
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


# ── Self: change own password (Item 3 audit coverage) ─────────────────────
@router.post("/api/users/me/password")
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
    log_audit(db, admin_id=current_user.id, action="PASSWORD_CHANGE", item_type="user", item_id=current_user.id)
    db.commit()
    return {"detail": "პაროლი წარმატებით შეიცვალა."}


# ── Role management: bulk reassignment (admin role console) ────────────────
@router.post("/api/admin/roles/bulk-reassign")
def bulk_reassign_roles(
    payload: schemas.BulkRoleReassignRequest,
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db),
):
    """Bulk-reassign a set of users to a single target role (admin role console).

    Guardrails:
      • target role must be one of the canonical VALID_ROLES;
      • the acting admin can never bulk-change their OWN role (prevents an
        accidental self-lockout) — their id is dropped from the set;
      • the operation may not demote the last active system administrator;
      • each moved user has their granular permissions reset to the new role's
        DEFAULT_PERMISSIONS_BY_ROLE template, mirroring create/edit behaviour.

    Every individual role change is written to the audit log. Returns how many
    rows actually changed vs. were skipped (already on the target role).
    """
    if payload.new_role not in security.VALID_ROLES:
        raise HTTPException(status_code=400, detail="უცნობი როლი")

    # Self-exclusion: an admin cannot bulk-change their own role.
    target_ids = {uid for uid in payload.user_ids if uid != current_admin.id}
    if not target_ids:
        raise HTTPException(
            status_code=400,
            detail="არცერთი მომხმარებელი არ არის შესარჩევი (საკუთარი როლის შეცვლა ჯგუფურად შეუძლებელია).",
        )

    users = db.query(models.User).filter(models.User.id.in_(target_ids)).all()
    if not users:
        raise HTTPException(status_code=404, detail="მომხმარებლები ვერ მოიძებნა")

    # Last-admin protection: refuse if this move would demote every remaining
    # active system administrator.
    if payload.new_role != security.ROLE_SYSTEM_ADMIN:
        demoted_admin_ids = [u.id for u in users if u.role == security.ROLE_SYSTEM_ADMIN]
        if demoted_admin_ids:
            remaining = (
                db.query(models.User)
                .filter(
                    models.User.role == security.ROLE_SYSTEM_ADMIN,
                    models.User.is_active == True,  # noqa: E712
                    models.User.id.notin_(demoted_admin_ids),
                )
                .count()
            )
            if remaining == 0:
                raise HTTPException(
                    status_code=400,
                    detail="ბოლო სისტემური ადმინისტრატორის როლის შეცვლა შეუძლებელია.",
                )

    default_perms = list(security.DEFAULT_PERMISSIONS_BY_ROLE.get(payload.new_role, []))
    changed = 0
    for u in users:
        if u.role == payload.new_role:
            continue
        old_role = u.role
        u.role = payload.new_role
        # Reset granular permissions to the target role's default template.
        u.permissions = list(default_perms)
        log_audit(
            db,
            admin_id=current_admin.id,
            action=f"BULK_ROLE_{old_role}_TO_{payload.new_role}",
            item_type="user",
            item_id=u.id,
            # Passed explicitly (already in hand) so the mapper event
            # (models.py's _auto_classify_audit_log) doesn't re-query the
            # same admin/user per row in this loop.
            admin_name_snapshot=current_admin.name, admin_email_snapshot=current_admin.email,
            item_name_snapshot=u.name,
        )
        changed += 1

    db.commit()
    return {
        "new_role": payload.new_role,
        "changed": changed,
        "skipped": len(users) - changed,
        "requested": len(payload.user_ids),
    }


@router.put("/api/users/{user_id}/status", response_model=schemas.UserResponse)
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
    user = get_or_404(db, models.User, user_id, "მომხმარებელი ვერ მოიძებნა")

    # Safety: an admin cannot deactivate their own account — get_current_user
    # now rejects inactive users, so this would lock them out immediately.
    if user.id == current_admin.id and not status_update.is_active:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="საკუთარი ანგარიშის დეაქტივაცია არ შეიძლება",
        )

    user.is_active = status_update.is_active

    # Audit trail: account activation/deactivation must be attributable
    log_audit(
        db, admin_id=current_admin.id,
        action=f"UPDATE_STATUS_TO_{str(status_update.is_active).upper()}",
        item_type="user", item_id=user_id,
    )
    db.commit()
    db.refresh(user)
    return user


@router.get("/api/admin/group-leaders", response_model=list[schemas.GroupLeaderResponse])
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
        .filter(models.User.role == "manager")
        .order_by(models.User.name)
        .all()
    )


@router.get("/api/users", response_model=list[schemas.UserResponse])
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
    readings_by_dept = dict(
        db.query(models.RequiredReading.target_department, func.count(models.RequiredReading.id))
        .group_by(models.RequiredReading.target_department)
        .all()
    )
    all_required = readings_by_dept.get("All", 0)

    read_map = _get_read_counts_by_user_dept(db)

    for user in users:
        required_count, read_count, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        user.read_count = read_count
        user.required_count = required_count
        user.progress_percentage = percentage

    return users


@router.put("/api/users/{user_id}", response_model=schemas.UserResponse)
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
    user = get_or_404(db, models.User, user_id, "მომხმარებელი ვერ მოიძებნა")

    user.role = update.role
    user.department = update.department
    user.position = update.position
    if update.phone is not None:
        user.phone = update.phone
    if update.team_id is not None:
        user.team_id = update.team_id

    db.commit()
    db.refresh(user)
    return user


@router.get("/api/teams", response_model=list[schemas.TeamResponse])
def get_teams(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
):
    """Lists teams (Team Statistics foundation, Block 1 Task 4).

    Access: Authenticated users (any active role) — read-only for non-admins.
    """
    return db.query(models.Team).order_by(models.Team.name).all()


@router.post("/api/teams", response_model=schemas.TeamResponse)
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


@router.post("/api/users/{user_id}/nudge")
def nudge_user(
    user_id: int,
    current_user: models.User = Depends(
        security.require_roles(security.ROLE_SYSTEM_ADMIN, security.ROLE_MANAGER, security.ROLE_CONTENT_ADMIN)
    ),
    db: Session = Depends(get_db)
):
    """Sends a real-time compliance nudge alert message to a specific operator.

    Access: Restricted to managers and administrators (admin, manager, content_admin).
    """
    user = get_or_404(db, models.User, user_id, "მომხმარებელი ვერ მოიძებნა")

    # Broadcast nudge event via SSE broker — target_user_id restricts server-side
    # delivery to the nudged operator (+ admins, who see everything); previously
    # this had no dept/role/user targeting at all, so the raw SSE payload
    # (who's being nudged, and the manager's message) reached every connected
    # client, relying only on client-side JS to hide the toast from the rest.
    _safe_publish({
        "type": "nudge",
        "user_id": user_id,
        "target_user_id": user_id,
        "message": f"გთხოვთ გაეცნოთ სავალდებულო მასალებს! (გამოგეგზავნათ მენეჯერისგან: {current_user.name})"
    })
    return {"status": "success", "message": f"Nudge sent to {user.name}"}


# ── Admin: create user (with password policy) ─────────────────────────────
@router.post("/api/users", response_model=schemas.UserResponse)
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

    log_audit(db, admin_id=current_admin.id, action="CREATE_USER", item_type="user", item_id=user.id)
    db.commit()
    db.refresh(user)
    return user


# ── Admin: reset another user's password (Item 3 audit coverage) ──────────
@router.post("/api/users/{user_id}/reset-password")
def admin_reset_password(
    user_id: int,
    payload: schemas.AdminPasswordResetRequest,
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db),
):
    """System-admin sets a new password for any user. Audit-logged."""
    user = get_or_404(db, models.User, user_id, "მომხმარებელი ვერ მოიძებნა")
    security.validate_password_policy(payload.new_password)
    user.hashed_password = security.get_password_hash(payload.new_password)
    log_audit(db, admin_id=current_admin.id, action="PASSWORD_RESET", item_type="user", item_id=user_id)
    db.commit()
    return {"detail": "პაროლი წარმატებით აღდგა."}


# ── Admin: update permissions matrix for a user (Item 3) ──────────────────
@router.put("/api/users/{user_id}/permissions", response_model=schemas.UserResponse)
def admin_update_permissions(
    user_id: int,
    payload: schemas.PermissionsUpdateRequest,
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

    user = get_or_404(db, models.User, user_id, "მომხმარებელი ვერ მოიძებნა")

    user.permissions = list(dict.fromkeys(payload.permissions))  # dedupe, preserve order
    log_audit(db, admin_id=current_admin.id, action="UPDATE_PERMISSIONS", item_type="user", item_id=user_id)
    db.commit()
    db.refresh(user)
    return user
