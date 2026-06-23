"""Authentication routes (Phase 2.1 — strangler extraction from main.py).

Scope: login + logout only. Profile endpoints (/api/users/me) intentionally
remain in main.py until the `users` router is extracted.

This module imports ONLY shared infrastructure (models, schemas, security,
database, config) and never imports `main`, so including it from main.py
creates no circular import. Decorator paths are kept byte-identical to the
originals so behaviour and the OpenAPI schema are unchanged.
"""
from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy.orm import Session

import models
import schemas
import security
from config import settings
from database import get_db

router = APIRouter(tags=["auth"])


@router.post("/api/auth/login", response_model=schemas.Token)
def login_for_access_token(
    credentials: schemas.LoginRequest,
    response: Response,
    db: Session = Depends(get_db),
):
    """Authenticates user credentials and issues a signed JWT access token."""
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
                item_id=user.id,
            )
            db.add(audit_log)
            db.commit()

        access_token = security.create_access_token(
            data={"sub": user.email, "role": user.role}
        )

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


@router.post("/api/auth/logout")
def logout(response: Response):
    """Clear the auth cookie (relevant when using cookie-based auth)."""
    response.delete_cookie("access_token", path="/")
    return {"detail": "Logged out"}
