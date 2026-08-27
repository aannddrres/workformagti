"""Authentication and Role-Based Access Control (RBAC) security dependencies.

This module handles password hashing, JWT creation/validation, and OAuth2 security
scopes. It defines role dependencies that match Magti Call Center Portal RBAC permissions.
"""

from datetime import datetime, timedelta
from functools import lru_cache
from typing import Optional

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import OAuth2PasswordBearer
from jose import JWTError, jwt
from passlib.context import CryptContext
from sqlalchemy.orm import Session
from sqlalchemy import func

import models
from config import settings
from database import get_db

# JWT configuration now comes from env-driven settings (see config.py / .env).
SECRET_KEY = settings.SECRET_KEY
ALGORITHM = settings.ALGORITHM
ACCESS_TOKEN_EXPIRE_MINUTES = settings.ACCESS_TOKEN_EXPIRE_MINUTES

# ── Canonical role set ────────────────────────────────────────────────────
# Mirrors the PDF "Roles / Permissions": operator < manager < content_admin,
# with admin (system administrator) as the superset.
ROLE_OPERATOR = "operator"
ROLE_MANAGER = "manager"
ROLE_CONTENT_ADMIN = "content_admin"
ROLE_SYSTEM_ADMIN = "admin"
VALID_ROLES = {ROLE_OPERATOR, ROLE_MANAGER, ROLE_CONTENT_ADMIN, ROLE_SYSTEM_ADMIN}

# The "content admin or system admin" check — same two roles
# get_current_admin_user gates on — was duplicated ad hoc across routers as
# `role not in ["admin", "content_admin"]`/`("admin", "content_admin")`.
CONTENT_ADMIN_ROLES = (ROLE_CONTENT_ADMIN, ROLE_SYSTEM_ADMIN)

# ┌──────────────────────────────────────────────────────────┐
# │ MOCK AD ACCOUNTS FOR LOCAL TESTING                       │
# │ Any password works for these emails.                     │
# │ Active only when APP_ENV NAMES a development environment │
# │ (config.py's _DEVELOPMENT_ENVIRONMENTS). It used to be   │
# │ "anything that is not exactly production", so a trailing │
# │ space, "prod" or a typo left this six-account credential │
# │ bypass live -- DEC-P04/DEC-P05.                          │
# └──────────────────────────────────────────────────────────┘
_DEV_TEST_EMAILS = {
    "admin@magti.ge",
    "content@magti.ge",
    "manager@magti.ge",
    "nino@magti.ge",
    "tech@magti.ge",
    "info@magti.ge"
}

if settings.is_production:
    TEST_EMAILS: set[str] = set()
else:
    TEST_EMAILS = _DEV_TEST_EMAILS

# auto_error=False so we can ALSO accept the token from an httpOnly cookie
# (defence-in-depth alternative to a localStorage bearer token).
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/auth/login", auto_error=False)

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def verify_password(plain_password: str, hashed_password: str) -> bool:
    """Verifies a plain text password against a hashed password using BCrypt.

    Args:
        plain_password: The raw password input.
        hashed_password: The stored hashed password.

    Returns:
        True if the password matches, False otherwise.
    """
    return pwd_context.verify(plain_password, hashed_password)


def get_password_hash(password: str) -> str:
    """Generates a BCrypt hash for a plain text password.

    Args:
        password: The raw password string.

    Returns:
        The BCrypt hashed password.
    """
    return pwd_context.hash(password)


# ── Password policy ───────────────────────────────────────────────────────
# Spec requirement: "პაროლის პოლიტიკა ან SSO". Enforced server-side on
# create-user (admin) and change-password (self). NOTE: the test-mode email
# allowlist (TEST_EMAILS above) bypasses *login* without checking a password,
# so it never reaches this validator — test mode UX stays intact.
PASSWORD_MIN_LENGTH = 8
PASSWORD_REQUIRE_DIGIT = True
PASSWORD_REQUIRE_UPPER = True
PASSWORD_REQUIRE_LOWER = True


def validate_password_policy(password: str) -> None:
    """Validate a candidate password against the central policy.

    Raises:
        HTTPException(400): With a Georgian-language detail listing what failed.
    """
    errors = []
    if len(password) < PASSWORD_MIN_LENGTH:
        errors.append(f"მინიმუმ {PASSWORD_MIN_LENGTH} სიმბოლო")
    if PASSWORD_REQUIRE_DIGIT and not any(c.isdigit() for c in password):
        errors.append("მინიმუმ ერთი ციფრი")
    if PASSWORD_REQUIRE_UPPER and not any(c.isupper() for c in password):
        errors.append("მინიმუმ ერთი დიდი ასო")
    if PASSWORD_REQUIRE_LOWER and not any(c.islower() for c in password):
        errors.append("მინიმუმ ერთი პატარა ასო")
    if errors:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="პაროლი ვერ აკმაყოფილებს მოთხოვნებს: " + ", ".join(errors),
        )


def create_reset_token(email: str) -> str:
    """Generates a temporary token for password reset."""
    expire = datetime.utcnow() + timedelta(minutes=15)
    to_encode = {"sub": email, "exp": expire, "purpose": "reset"}
    return jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)


def create_access_token(data: dict, expires_delta: Optional[timedelta] = None) -> str:
    """Generates a signed JWT access token for the given user data payload.

    Args:
        data: Payload dictionary (must contain the 'sub' key holding the user email).
        expires_delta: Optional custom token expiration duration.

    Returns:
        The encoded JWT token string.
    """
    to_encode = data.copy()
    if expires_delta:
        expire = datetime.utcnow() + expires_delta
    else:
        expire = datetime.utcnow() + timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    to_encode.update({"exp": expire})
    encoded_jwt = jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)
    return encoded_jwt


# JIT provisioning: (role, department, name) overrides per known dev/test
# email — any field left None falls back to the generic operator default.
# Keeping this table separate from authenticate_user() lets that function
# stay focused on authentication rather than dev-fixture data.
_JIT_PROVISION_OVERRIDES: dict[str, tuple[Optional[str], Optional[str], str]] = {
    "admin@magti.ge": ("admin", "Administration", "სისტემური ადმინი"),
    "content@magti.ge": ("content_admin", "Content Creation", "კონტენტის ადმინისტრატორი"),
    "manager@magti.ge": ("manager", "Support", "ჯგუფის მენეჯერი"),
    "nino@magti.ge": (None, None, "ნინო ჩიტიშვილი"),
    "tech@magti.ge": (None, None, "ტექნიკური ოპერატორი"),
    "info@magti.ge": (None, "Informational", "საინფორმაციო ოპერატორი"),
}


def _jit_provision_test_user(lower_email: str) -> tuple[str, str, str]:
    """Determines (role, department, name) for a JIT-created dev/test user."""
    role, department, name = "operator", "Support", f"Test User {lower_email.split('@')[0]}"
    override_role, override_department, override_name = _JIT_PROVISION_OVERRIDES.get(
        lower_email, (None, None, None)
    )
    return override_role or role, override_department or department, override_name or name


def authenticate_user(db: Session, email: str, password: str) -> Optional[models.User]:
    """Authenticates a user against database credentials or mock AD list.
    
    Checks if user is active, checks test accounts, and falls back to verifying
    the hashed password. For development, it will also create test users on-the-fly
    if they don't exist in the database (Just-in-Time provisioning).
    
    Args:
        db: Database session.
        email: User email.
        password: Password string.
    
    Returns:
        The authenticated models.User database row, or None.
    """
    lower_email = email.lower()
    user = db.query(models.User).filter(func.lower(models.User.email) == lower_email).first()

    # --- JIT Provisioning for Development ---
    # If the user doesn't exist, but it's a known test email in a dev environment,
    # create them on the fly. This makes testing robust without requiring a manual seed.
    if not user and not settings.is_production:
        is_test_account = lower_email.startswith("test_operator_") or lower_email in TEST_EMAILS
        if is_test_account:
            role, department, name = _jit_provision_test_user(lower_email)

            user = models.User(
                email=lower_email, name=name, role=role, department=department, is_active=True,
                hashed_password=get_password_hash("dummy_password_for_jit_user"),
                permissions=DEFAULT_PERMISSIONS_BY_ROLE.get(role, [])
            )
            db.add(user)
            db.commit()
            db.refresh(user)

    if not user:
        return None
        
    if not user.is_active:
        return None
        
    # Development Bypass for pre-seeded or JIT-provisioned test accounts.
    # The TEST_EMAILS set is empty in production.
    if not settings.is_production and (lower_email.startswith("test_operator_") or lower_email in TEST_EMAILS):
        return user
        
    # Production password check for all other users.
    if not user.hashed_password or not verify_password(password, user.hashed_password):
        return None
        
    return user


def _candidate_tokens(request: Request, bearer_token: Optional[str]) -> list:
    """Lists JWT candidates from the Authorization header and the httpOnly cookie.

    SECURITY (C-1): the previous `?token=` query-parameter fallback was removed —
    tokens in URLs leak into webserver access logs, browser history, Referer
    headers, and proxy logs. SSE (`EventSource`) cannot set headers, but the
    same-origin httpOnly cookie set on login covers that path; file-download
    flows must use the bearer header (or be redesigned with short-lived signed
    URLs).

    SECURITY (C-2): the header is tried first (so an explicit, validly-signed
    Bearer token — a non-browser API client, or a caller deliberately acting
    as a different identity than a stale cookie — still wins), then the
    cookie. The browser frontend now keeps only an unsigned header+payload
    shell in localStorage (see login.html), so its Authorization header
    fails to decode and the caller below falls through to the cookie, which
    is the only real credential a browser session ever carries.

    Args:
        request: FastAPI HTTP request.
        bearer_token: The parsed Bearer token (if supplied via OAuth2PasswordBearer).

    Returns:
        Non-empty token candidates, in the order they should be tried.
    """
    return [t for t in (bearer_token, request.cookies.get("access_token")) if t]


def get_current_user(
    request: Request,
    bearer_token: Optional[str] = Depends(oauth2_scheme),
    db: Session = Depends(get_db),
) -> models.User:
    """Verifies token claims and retrieves the active user details from the DB.

    Authorisation is derived from the database row on every request rather than
    trusting token claims directly, so account deactivations or role changes take
    effect immediately.

    Args:
        request: FastAPI HTTP request.
        bearer_token: Extracted token.
        db: Database session.

    Returns:
        The models.User database row.

    Raises:
        HTTPException (401): If token is missing, invalid, or expired.
        HTTPException (403): If the user account is deactivated.
    """
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )

    email: Optional[str] = None
    for token in _candidate_tokens(request, bearer_token):
        try:
            payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        except JWTError:
            continue
        email = payload.get("sub")
        if email:
            break
    if email is None:
        raise credentials_exception

    user = db.query(models.User).filter(models.User.email == email).first()
    if user is None:
        raise credentials_exception

    if not user.is_active:
        # A deactivated user with a still-valid token is locked out at once.
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="User account is disabled",
        )

    return user


def require_roles(*allowed_roles: str):
    """Dependency factory: restricts FastAPI endpoint access to specific roles.

    Evaluates the active user role, raising 403 Forbidden on violations.

    Args:
        allowed_roles: List of approved roles.

    Returns:
        A callable dependency function checking RBAC role clearance.
    """

    def _dependency(
        current_user: models.User = Depends(get_current_user),
    ) -> models.User:
        if current_user.role not in allowed_roles:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Not enough permissions to perform this action",
            )
        return current_user

    return _dependency


# ── Role-scoped dependencies, aligned with the PDF role matrix ────────────
#
#   operator       → consume content only (uses get_current_user)
#   manager        → team statistics & reporting, messaging operators
#   content_admin  → content authoring (articles, news, videos, categories)
#   admin (system) → everything: users, roles, security, audit logs, exports
#

# Restricts endpoint access to content admins and system admins.
get_current_admin_user = require_roles(ROLE_CONTENT_ADMIN, ROLE_SYSTEM_ADMIN)

# Restricts endpoint access strictly to system administrators (User, DDL audits, exports).
get_current_system_admin_user = require_roles(ROLE_SYSTEM_ADMIN)

# Restricts endpoint access to supervisor managers and system administrators (Manager statistics).
get_current_manager_user = require_roles(ROLE_MANAGER, ROLE_SYSTEM_ADMIN)


# ── Granular sub-permissions (spec slide 26) ──────────────────────────────
# Layered on top of roles: a content_admin without "articles.publish" can
# create drafts but not publish; an admin without "users.manage" can edit
# settings but not create new accounts. system_admin bypasses everything.
#
# Permission canonical names — keep this list in one place so future endpoints
# can reference symbols rather than stringly-typed magic.
PERM_ARTICLES_VIEW = "articles.view"
PERM_ARTICLES_EDIT = "articles.edit"
PERM_ARTICLES_PUBLISH = "articles.publish"
PERM_ARTICLES_ARCHIVE = "articles.archive"
PERM_VIDEOS_ARCHIVE = "videos.archive"
PERM_USERS_MANAGE = "users.manage"
PERM_COMPLIANCE_ASSIGN = "compliance.assign"
PERM_REPORTS_EXPORT = "reports.export"

# Colon-named, deliberately NOT matching the dotted style above. The dotted
# PERM_* constants above are checked via the same Permission.name == perm
# query as this one, but scripts/seed_rbac.py -- the only script that ever
# seeds Role/RolePermission/Permission rows -- uses an entirely different,
# colon-named 13-permission catalog ("content:archive", "system:audit", ...)
# that shares no strings with the dotted constants. That means those 8
# dotted PERM_* checks can never match a seeded row (separate, pre-existing,
# unrelated issue — not fixed here). system:audit is colon-named specifically
# to land in the catalog seed_rbac.py already seeds, not repeat that bug.
PERM_SYSTEM_AUDIT = "system:audit"

# Default permission set baked in per role on seed/create. system_admin doesn't
# need an explicit set — the dependency below grants everything to that role.
DEFAULT_PERMISSIONS_BY_ROLE: dict[str, list[str]] = {
    ROLE_OPERATOR: [],
    ROLE_MANAGER: [PERM_REPORTS_EXPORT],
    ROLE_CONTENT_ADMIN: [
        PERM_ARTICLES_VIEW,
        PERM_ARTICLES_EDIT,
        PERM_ARTICLES_PUBLISH,
        PERM_ARTICLES_ARCHIVE,
        PERM_VIDEOS_ARCHIVE,
        PERM_COMPLIANCE_ASSIGN,
    ],
    ROLE_SYSTEM_ADMIN: [
        PERM_ARTICLES_VIEW,
        PERM_ARTICLES_EDIT,
        PERM_ARTICLES_PUBLISH,
        PERM_ARTICLES_ARCHIVE,
        PERM_VIDEOS_ARCHIVE,
        PERM_USERS_MANAGE,
        PERM_COMPLIANCE_ASSIGN,
        PERM_REPORTS_EXPORT,
    ],
}

def role_has_permission(db: Session, current_user: models.User, perm: str) -> bool:
    """Shared by require_permission() and /api/users/me's can_view_audit_log
    so the two can never disagree about what a role can do.

    Checks two independent grant sources and allows if EITHER matches — they
    cover disjoint parts of the permission catalog, not the same data twice:

    1. The per-user ``User.permissions`` JSON column. Populated from
       DEFAULT_PERMISSIONS_BY_ROLE at user creation, and the only thing
       scripts/sync_rbac.py and the admin "edit permissions" endpoint
       (PUT /api/users/{user_id}/permissions) actually write to. Every
       dotted PERM_* constant except PERM_SYSTEM_AUDIT lives here.
    2. The DB-backed Role/RolePermission/Permission tables seeded by
       scripts/seed_rbac.py, which uses an unrelated colon-named catalog
       ("content:archive", "system:audit", ...). PERM_SYSTEM_AUDIT is
       deliberately spelled "system:audit" to land in that catalog (see
       migrate.py's ensure_system_audit_permission_seeded), so this path
       is still load-bearing for audit-log access.

    Before this, `perm` was only ever checked against source 2 — so the 8
    dotted permissions (which no seed_rbac.py row ever matches) were
    unreachable for any role but system_admin, even though
    DEFAULT_PERMISSIONS_BY_ROLE/sync_rbac.py/the admin UI all assumed
    source 1 was live.
    """
    if current_user.role == ROLE_SYSTEM_ADMIN:
        return True
    if perm in (current_user.permissions or []):
        return True
    role = db.query(models.Role).filter(models.Role.name == current_user.role).first()
    if not role:
        return False
    return db.query(models.RolePermission).join(models.Permission).filter(
        models.RolePermission.role_id == role.id,
        models.Permission.name == perm
    ).first() is not None


@lru_cache(maxsize=None)
def require_permission(perm: str, exclude_roles: frozenset = frozenset()):
    """Dependency factory: gate an endpoint behind a granular DB-backed permission.
    Checks if the user's role possesses the required permission.

    exclude_roles: roles that hold `perm` but should still be denied THIS
    particular endpoint (e.g. a manager holds system:audit for the scoped
    list view, but not for export/verify/chain-health). Centralizing this
    here means a new manager-restricted endpoint can't forget the carve-out
    by copy-pasting an inline role check.

    Cached: without this, every call built a fresh `_dependency` closure, so
    two routes gating the same perm (or a test's dependency_overrides
    targeting this factory's return value) could never refer to the same
    object. FastAPI resolves dependencies by identity, so caching also means
    identical require_permission(...) calls across the app share one
    dependency — not just a testability fix, but the correct shared-identity
    behavior for a stateless factory like this.

    maxsize=None is safe here specifically because every call site passes a
    literal PERM_* constant + a literal frozenset — a small, closed set of
    arguments fixed at import time. If a future call site ever passes a
    dynamically-built perm/exclude_roles value, switch to a bounded maxsize
    so the cache can't grow unboundedly.
    """
    def _dependency(
        current_user: models.User = Depends(get_current_user),
        db: Session = Depends(get_db)
    ) -> models.User:
        if not role_has_permission(db, current_user, perm):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="წვდომა უარყოფილია: არასაკმარისი უფლებები",
            )
        if current_user.role in exclude_roles:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="ეს ფუნქცია ხელმისაწვდომია მხოლოდ ადმინისტრატორებისთვის",
            )
        return current_user
    return _dependency
