"""Data retention: archive-then-purge for the two unbounded tracking tables.

Rows older than settings.AUDIT_RETENTION_DAYS (default 180) are exported to a
timestamped JSON file under archives/ and deleted from the DB. Runs daily in
the backup container (backup.py calls run_retention() after the backup), so
the archive lands on the same host bind mount lifecycle as backups.

Standalone on purpose — imports only models/database/config, never main.py,
so the cron container doesn't pay the full FastAPI import (and doesn't
register SSE brokers, caches, etc. just to delete old rows).
"""
import json
import logging
import os

from datetime import timedelta

import models
from config import settings
from database import SessionLocal, get_tbilisi_time

logging.basicConfig(level=logging.INFO, format="[retention] %(message)s")
log = logging.getLogger("retention")

ARCHIVE_DIR = "archives"


def _archive_and_purge(db, query, serialize, archive_prefix: str) -> int:
    """Write `query`'s rows to a JSON archive, then delete them. Returns count."""
    stale = query.all()
    if not stale:
        return 0

    os.makedirs(ARCHIVE_DIR, exist_ok=True)
    archive_path = os.path.join(
        ARCHIVE_DIR,
        f"{archive_prefix}_{get_tbilisi_time().strftime('%Y%m%d_%H%M%S')}.json",
    )

    payload = []
    for row in stale:
        payload.append(serialize(row))
        db.delete(row)

    # Archive BEFORE commit: if the file write fails, the rows stay in the DB.
    with open(archive_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    db.commit()
    return len(payload)


def rotate_audit_logs(db) -> int:
    """Exports audit_logs rows older than the retention window and purges them."""
    cutoff = get_tbilisi_time() - timedelta(days=settings.AUDIT_RETENTION_DAYS)
    return _archive_and_purge(
        db,
        db.query(models.AuditLog).filter(models.AuditLog.timestamp < cutoff),
        lambda r: {
            "id": r.id,
            "admin_id": r.admin_id,
            "action": r.action,
            "item_type": r.item_type,
            "item_id": r.item_id,
            "timestamp": r.timestamp.isoformat() if r.timestamp else None,
            "category": r.category,
            "details": r.details,
        },
        "audit_log_archive",
    )


def rotate_view_logs(db) -> int:
    """Exports article_view_logs rows older than the retention window and purges them."""
    cutoff = get_tbilisi_time() - timedelta(days=settings.AUDIT_RETENTION_DAYS)
    return _archive_and_purge(
        db,
        db.query(models.ArticleViewLog).filter(models.ArticleViewLog.viewed_at < cutoff),
        lambda r: {
            "id": r.id,
            "article_id": r.article_id,
            "article_title_snapshot": r.article_title_snapshot,
            "article_version": r.article_version,
            "operator_id": r.operator_id,
            "operator_name_snapshot": r.operator_name_snapshot,
            "operator_email_snapshot": r.operator_email_snapshot,
            "operator_department_snapshot": r.operator_department_snapshot,
            "viewed_at": r.viewed_at.isoformat() if r.viewed_at else None,
        },
        "view_log_archive",
    )


def run_retention() -> None:
    with SessionLocal() as db:
        audit_n = rotate_audit_logs(db)
        view_n = rotate_view_logs(db)
    log.info(
        "retention pass done: %d audit rows, %d view rows archived (window: %d days)",
        audit_n, view_n, settings.AUDIT_RETENTION_DAYS,
    )


if __name__ == "__main__":
    run_retention()
