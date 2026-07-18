"""Small, shared route helpers factored out of boilerplate that had been
hand-duplicated across most routers: fetching a row by primary key or
raising a 404, and resolving a generic (item_type, item_id) pair to its
display title (articles/news/videos are all bookmarkable, mandatory-
readable, or notifiable by this same generic pair).
"""
from typing import Optional, Type, TypeVar

from fastapi import HTTPException
from sqlalchemy.orm import Session

import models

ModelT = TypeVar("ModelT")

_TITLE_MODELS = {
    "article": models.Article,
    "news": models.News,
    "video": models.VideoInstruction,
}


def get_or_404(db: Session, model: Type[ModelT], item_id: int, detail: str) -> ModelT:
    """Fetches ``model`` by primary key or raises HTTP 404 with ``detail``."""
    obj = db.query(model).filter(model.id == item_id).first()
    if not obj:
        raise HTTPException(status_code=404, detail=detail)
    return obj


def log_audit(db: Session, *, admin_id: int, action: str, item_type: str, item_id, **kwargs) -> "models.AuditLog":
    """Stages an AuditLog row (caller still controls commit timing).

    Same fields every call site already passed by hand — this just removes
    the ``models.AuditLog(...)`` + ``db.add(...)`` boilerplate, it doesn't
    change what gets written or when it's committed.
    """
    entry = models.AuditLog(admin_id=admin_id, action=action, item_type=item_type, item_id=item_id, **kwargs)
    db.add(entry)
    return entry


def resolve_item_title(db: Session, item_type: str, item_id: int) -> Optional[str]:
    """Looks up the display title for one (item_type, item_id) pair.

    Returns None if item_type is unrecognized or the row no longer exists —
    callers apply their own fallback text for a deleted/missing item.
    """
    model = _TITLE_MODELS.get(item_type)
    if model is None:
        return None
    row = db.query(model.title).filter(model.id == item_id).first()
    return row[0] if row else None


def resolve_item_titles_bulk(db: Session, pairs: list[tuple[str, int]]) -> dict[tuple[str, int], str]:
    """Batched variant of resolve_item_title: one IN(...) query per item_type
    instead of one query per pair, for call sites resolving many items at once.
    """
    ids_by_type: dict[str, set[int]] = {}
    for item_type, item_id in pairs:
        ids_by_type.setdefault(item_type, set()).add(item_id)

    titles: dict[tuple[str, int], str] = {}
    for item_type, model in _TITLE_MODELS.items():
        ids = ids_by_type.get(item_type)
        if not ids:
            continue
        for row_id, row_title in db.query(model.id, model.title).filter(model.id.in_(ids)):
            titles[(item_type, row_id)] = row_title
    return titles
