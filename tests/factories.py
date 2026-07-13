"""
Minimal DB factories for high-level API tests (L2).

Prefer these over seed_portal demo mode. Safe to call repeatedly on the shared local DB
with unique emails/titles; tests should clean up rows they create when practical.
"""
from __future__ import annotations

from datetime import timedelta
from typing import Optional

import models
import security
from database import get_tbilisi_time


def make_user(
    db,
    *,
    email: str,
    role: str = "operator",
    department: str = "ტექნიკური",
    name: Optional[str] = None,
    password: str = security.TEST_ACCOUNT_PASSWORD,
    permissions: Optional[list] = None,
) -> models.User:
    email_l = email.lower()
    u = db.query(models.User).filter(models.User.email == email_l).first()
    perms = permissions if permissions is not None else security.DEFAULT_PERMISSIONS_BY_ROLE.get(role, [])
    if not u:
        u = models.User(
            email=email_l,
            name=name or email_l.split("@")[0],
            role=role,
            department=department,
            is_active=True,
            permissions=list(perms),
            hashed_password=security.get_password_hash(password),
        )
        db.add(u)
    else:
        u.name = name or u.name
        u.role = role
        u.department = department
        u.is_active = True
        u.permissions = list(perms)
        u.hashed_password = security.get_password_hash(password)
    db.commit()
    db.refresh(u)
    return u


def make_category(db, *, name: str = "Factory Cat", category_id: Optional[int] = None) -> models.Category:
    if category_id is not None:
        cat = db.query(models.Category).filter(models.Category.id == category_id).first()
        if cat:
            return cat
        cat = models.Category(id=category_id, name=name, is_active=True)
        db.add(cat)
        db.commit()
        db.refresh(cat)
        return cat
    cat = models.Category(name=name, is_active=True)
    db.add(cat)
    db.commit()
    db.refresh(cat)
    return cat


def make_article(
    db,
    *,
    author: models.User,
    title: str = "Factory Article",
    content: str = "<p>factory body</p>",
    status: str = "published",
    is_draft: bool = False,
    category_id: Optional[int] = None,
    quiz_enabled: bool = False,
    target_department: str = "All",
) -> models.Article:
    if category_id is None:
        cat = make_category(db, name="Factory Cat", category_id=1)
        category_id = cat.id
    art = models.Article(
        title=title,
        content=content,
        category_id=category_id,
        status=status,
        is_draft=is_draft,
        quiz_enabled=quiz_enabled,
        author_id=author.id,
        target_department=target_department,
        # Mirror the create-article endpoint: visibility checks read the
        # junction rows (article.target_departments), not the legacy column.
        target_department_rows=[
            models.ArticleTargetDepartment(department=target_department)
        ],
    )
    db.add(art)
    db.commit()
    db.refresh(art)
    return art


def make_required_reading(
    db,
    *,
    item_id: int,
    item_type: str = "article",
    target_department: str = "All",
    due_days: int = -1,
    priority: str = "normal",
) -> models.RequiredReading:
    """due_days: negative = overdue, positive = due in the future."""
    due = get_tbilisi_time() + timedelta(days=due_days)
    rr = models.RequiredReading(
        item_type=item_type,
        item_id=item_id,
        target_department=target_department,
        due_date=due,
        priority=priority,
    )
    db.add(rr)
    db.commit()
    db.refresh(rr)
    return rr


def make_read_receipt(
    db,
    *,
    article: models.Article,
    operator: models.User,
    article_version: Optional[int] = None,
) -> models.ArticleReadReceipt:
    ver = article_version if article_version is not None else article.version
    rec = models.ArticleReadReceipt(
        article_id=article.id,
        operator_id=operator.id,
        article_version=ver,
        article_title_snapshot=article.title,
        operator_name_snapshot=operator.name,
        operator_email_snapshot=operator.email,
        operator_department_snapshot=operator.department,
        read_at=get_tbilisi_time(),
    )
    db.add(rec)
    db.commit()
    db.refresh(rec)
    return rec
