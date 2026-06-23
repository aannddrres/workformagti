"""Categories domain router (Phase 2.5 — strangler extraction from main.py).

Scope: all /api/categories/* endpoints.
  - GET /               list all active categories (TTL-cached)
  - POST /              create category
  - PUT /{category_id}  update category
  - DELETE /{category_id} soft-delete and reassign articles to fallback

Cross-cutting singletons (search_cache, category_cache) live in the root main
module and are accessed via a lazy `import main as _main` inside each write
handler body to prevent a circular import at module load time.
"""
from fastapi import APIRouter, Depends, HTTPException, status

from sqlalchemy.orm import Session

import models
import schemas
import security
from database import get_db

router = APIRouter(prefix="/api/categories", tags=["categories"])


# ── Read ──────────────────────────────────────────────────────────────────────

@router.get("", response_model=list[schemas.CategoryResponse])
def get_categories(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves all categories. Results are cached with a 60-second TTL.

    Access: Authenticated users (any active role).

    Args:
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A list of CategoryResponse schemas.
    """
    import main as _main
    cache_key = f"categories:{current_user.role}:{current_user.department}"
    cached = _main.category_cache.get(cache_key)
    if cached is not None:
        return cached

    categories = db.query(models.Category).filter(models.Category.is_active == True).all()
    serialized = [schemas.CategoryResponse.model_validate(c).model_dump() for c in categories]
    _main.category_cache.set(cache_key, serialized)
    return serialized


# ── Write ─────────────────────────────────────────────────────────────────────

@router.post("", response_model=schemas.CategoryResponse)
def create_category(
    category: schemas.CategoryCreate,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Creates a new category, clears search/category caches, and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        category: The category creation details.
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        The created Category database row.
    """
    db_category = models.Category(**category.model_dump())
    db.add(db_category)
    db.commit()
    db.refresh(db_category)

    audit_log = models.AuditLog(admin_id=current_admin.id, action="CREATE", item_type="category", item_id=db_category.id)
    db.add(audit_log)
    db.commit()

    import main as _main
    _main.category_cache.clear()
    _main.search_cache.clear()
    return db_category


@router.put("/{category_id}", response_model=schemas.CategoryResponse)
def update_category(
    category_id: int,
    category: schemas.CategoryCreate,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Updates an existing category, clears search/category caches, and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        category_id: ID of the category to update.
        category: The category update details.
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        The updated Category database row.

    Raises:
        HTTPException: 404 Not Found if the category does not exist.
    """
    db_category = db.query(models.Category).filter(models.Category.id == category_id).first()
    if not db_category:
        raise HTTPException(status_code=404, detail="კატეგორია ვერ მოიძებნა")

    for key, value in category.model_dump().items():
        setattr(db_category, key, value)

    db.commit()
    db.refresh(db_category)

    audit_log = models.AuditLog(admin_id=current_admin.id, action="UPDATE", item_type="category", item_id=db_category.id)
    db.add(audit_log)
    db.commit()

    import main as _main
    _main.category_cache.clear()
    _main.search_cache.clear()
    return db_category


@router.delete("/{category_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_category(
    category_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Deletes a category, clears search/category caches, and logs the action.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        category_id: ID of the category to delete.
        current_admin: The authenticated administrator User object.
        db: SQLAlchemy database session.

    Returns:
        None.

    Raises:
        HTTPException: 404 Not Found if the category does not exist.
    """
    db_category = db.query(models.Category).filter(models.Category.id == category_id).first()
    if not db_category:
        raise HTTPException(status_code=404, detail="კატეგორია ვერ მოიძებნა")

    fallback_cat = db.query(models.Category).filter(models.Category.name == "ზოგადი").first()
    if not fallback_cat:
        fallback_cat = models.Category(name="ზოგადი", slug="general", icon="fa-layer-group", pastel_color_class="general", is_active=True)
        db.add(fallback_cat)
        db.commit()
        db.refresh(fallback_cat)

    if fallback_cat.id != category_id:
        db.query(models.Article).filter(models.Article.category_id == category_id).update({"category_id": fallback_cat.id})

    db_category.is_active = False
    db.commit()

    audit_log = models.AuditLog(admin_id=current_admin.id, action="DELETE", item_type="category", item_id=category_id)
    db.add(audit_log)
    db.commit()

    import main as _main
    _main.category_cache.clear()
    _main.search_cache.clear()
    return None
