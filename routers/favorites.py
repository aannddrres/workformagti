"""Favorites (bookmark) CRUD routes — Phase 2 of the main.py monolith split."""
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

import models
import schemas
import security
from database import get_db
from db_helpers import resolve_item_title

router = APIRouter(tags=["favorites"])


@router.get("/api/favorites", response_model=list[schemas.FavoriteResponse])
def get_favorites(
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Retrieves all bookmarked items (articles, news, videos) for the current user.

    Access: Authenticated users (any active role).

    Args:
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        A list of FavoriteResponse schemas.
    """
    favorites = db.query(models.Favorite).filter(models.Favorite.user_id == current_user.id).all()
    results = []
    for fav in favorites:
        title = resolve_item_title(db, fav.item_type, fav.item_id)

        results.append({
            "id": fav.id,
            "user_id": fav.user_id,
            "item_type": fav.item_type,
            "item_id": fav.item_id,
            "item_title": title or f"მასალა #{fav.item_id}"
        })
    return results

@router.post("/api/favorites", response_model=schemas.FavoriteResponse)
def add_favorite(
    favorite: schemas.FavoriteCreate,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Bookmarks an item (adds it to the user's favorites).

    Prevents duplicate bookmarks for the same item.

    Access: Authenticated users (any active role).

    Args:
        favorite: Target item details (type and ID).
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        The created or existing Favorite database row.
    """
    existing = db.query(models.Favorite).filter(
        models.Favorite.user_id == current_user.id,
        models.Favorite.item_type == favorite.item_type,
        models.Favorite.item_id == favorite.item_id
    ).first()

    fav_row = existing
    if not existing:
        new_favorite = models.Favorite(
            user_id=current_user.id,
            item_type=favorite.item_type,
            item_id=favorite.item_id
        )
        db.add(new_favorite)
        db.commit()
        db.refresh(new_favorite)
        fav_row = new_favorite

    title = resolve_item_title(db, fav_row.item_type, fav_row.item_id)

    return {
        "id": fav_row.id,
        "user_id": fav_row.user_id,
        "item_type": fav_row.item_type,
        "item_id": fav_row.item_id,
        "item_title": title or f"მასალა #{fav_row.item_id}"
    }

@router.delete("/api/favorites/{favorite_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_favorite(
    favorite_id: int,
    current_user: models.User = Depends(security.get_current_user),
    db: Session = Depends(get_db)
):
    """Removes an item from the user's bookmarks (favorites).

    Access: Authenticated users (any active role).

    Args:
        favorite_id: ID of the bookmark to delete.
        current_user: The authenticated User object.
        db: SQLAlchemy database session.

    Returns:
        None.

    Raises:
        HTTPException: 404 Not Found if the bookmark does not exist or belongs to another user.
    """
    favorite = db.query(models.Favorite).filter(
        models.Favorite.id == favorite_id,
        models.Favorite.user_id == current_user.id,
    ).first()
    if not favorite:
        raise HTTPException(status_code=404, detail="რჩეული ვერ მოიძებნა")

    db.delete(favorite)
    db.commit()
    return None
