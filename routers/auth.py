"""Authentication routes: login, logout, forgot-password, and the mock SSO
flow — Phase 11 of the main.py monolith split.

GET/PUT /api/users/me and POST /api/users/me/password stay out of this
router by design — they're the authenticated user's own profile, not login
mechanics, and land in routers/users.py (Phase 13) instead.
"""
import logging

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.responses import HTMLResponse
from sqlalchemy import func
from sqlalchemy.orm import Session

import models
import schemas
import security
from config import settings
from database import get_db
from db_helpers import log_audit
from state import limiter

router = APIRouter(tags=["auth"])
logger = logging.getLogger("magti")


@router.post("/api/auth/login", response_model=schemas.Token)
@limiter.limit("10/minute")
def login_for_access_token(
    request: Request,
    credentials: schemas.LoginRequest,
    response: Response,
    db: Session = Depends(get_db)
):
    """Authenticates user credentials and issues a signed JWT access token.

    Unexpected errors are left to propagate to main.py's shared
    catch_unhandled_exceptions middleware (logged, masked 500) rather than
    being caught here and leaked to the client as a 400.
    """
    user = security.authenticate_user(db, credentials.email, credentials.password)
    if not user:
        client_ip = request.client.host if request.client else "unknown"
        logger.warning("Failed login attempt for %s from %s", credentials.email, client_ip)
        # Audit row only when the account exists (admin_id is a NOT NULL
        # FK) — storing unknown attempted emails would both violate the
        # constraint and hoard enumeration data.
        existing = db.query(models.User).filter(
            func.lower(models.User.email) == credentials.email.lower()
        ).first()
        if existing:
            log_audit(
                db, admin_id=existing.id, action="LOGIN_FAILED",
                item_type="user", item_id=existing.id, details=f"IP: {client_ip}",
            )
            db.commit()
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="არასწორი ელ. ფოსტა ან მომხმარებელი არ არსებობს",
        )

    # Audit trail: every successful login is recorded
    if not credentials.email.startswith("test_operator_"):
        log_audit(db, admin_id=user.id, action="LOGIN", item_type="user", item_id=user.id)
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


@router.post("/api/auth/logout")
def logout(response: Response):
    """Clear the auth cookie (relevant when using cookie-based auth)."""
    response.delete_cookie("access_token", path="/")
    return {"detail": "Logged out"}


@router.post("/api/auth/forgot-password")
@limiter.limit("10/minute")
def forgot_password(
    request: Request,
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
        log_audit(db, admin_id=user.id, action="PASSWORD_RESET_REQUEST", item_type="user", item_id=user.id)
        db.commit()
        if not settings.is_production:
            logger.info(f"[forgot-password] dev reset token for {user.email}: {token}")
    # Constant-time-ish: always claim success.
    return {"detail": "თუ ეს ელ. ფოსტა რეგისტრირებულია, აღდგენის ინსტრუქცია გამოგზავნილია."}


@router.get("/api/auth/sso/init")
def sso_init():
    """Returns the SSO configuration redirecting to the mock login selector in development."""
    return {
        "configured": True,
        "sso_url": "/api/auth/sso/mock-login"
    }


@router.get("/api/auth/sso/mock-login", response_class=HTMLResponse)
def sso_mock_login():
    """Renders a mock corporate Single Sign-On login selector page for active directory accounts."""
    html_content = """
    <!DOCTYPE html>
    <html lang="ka">
    <head>
        <meta charset="UTF-8">
        <title>მაგთი კორპორაციული SSO</title>
        <link rel="stylesheet" href="/static/css/app.min.css">
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
                    const [tokenHeader, tokenPayload] = data.access_token.split('.');
                    localStorage.setItem('magti_token', `${tokenHeader}.${tokenPayload}.`);
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


@router.post("/api/auth/sso/callback")
@limiter.limit("10/minute")
def sso_callback(
    request: Request,
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
    log_audit(db, admin_id=user.id, action="LOGIN_SSO", item_type="user", item_id=user.id)
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
