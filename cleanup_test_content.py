"""
One-off content cleanup: unpublish any article or video whose title is
exactly "test" (case-insensitive) — leftover placeholders from manual
QA/demo poking, not real content.

Uses the same soft-archive mechanism as the Admin UI's archive button
(Article.status = "archived", VideoInstruction.is_archived = True) so
archived items disappear from operator-facing lists/search but stay in
the DB and remain restorable via the existing unarchive endpoints —
nothing is deleted.

Run manually:

    python cleanup_test_content.py

Safe to re-run: already-archived matches are skipped, so a second run
finds nothing left to do.

Note: the running app caches search/category results in-memory for 60s
(search_cache / category_cache in main.py). If the app is already running
for a demo, a "test" item may still show up in search for up to a minute
after this runs.
"""
import logging
import sys

from sqlalchemy import func

from database import SessionLocal
from models import Article, AuditLog, User, VideoInstruction

logging.basicConfig(level=logging.INFO, format="[cleanup_test_content] %(message)s")
log = logging.getLogger("cleanup_test_content")

TITLE = "test"


def main() -> int:
    db = SessionLocal()
    try:
        admin = db.query(User).filter(User.role == "admin").first()
        if not admin:
            log.error("No admin user found — cannot attribute audit log entries. Aborting.")
            return 1

        articles = (
            db.query(Article)
            .filter(func.lower(Article.title) == TITLE, Article.status != "archived")
            .all()
        )
        for a in articles:
            log.info("Archiving article id=%d title=%r", a.id, a.title)
            a.status = "archived"
            db.add(AuditLog(admin_id=admin.id, action="ARCHIVE", item_type="article", item_id=a.id))

        videos = (
            db.query(VideoInstruction)
            .filter(func.lower(VideoInstruction.title) == TITLE, VideoInstruction.is_archived == False)  # noqa: E712
            .all()
        )
        for v in videos:
            log.info("Archiving video id=%d title=%r", v.id, v.title)
            v.is_archived = True
            db.add(AuditLog(admin_id=admin.id, action="ARCHIVE", item_type="video", item_id=v.id))

        db.commit()
        log.info("Done: archived %d article(s), %d video(s).", len(articles), len(videos))

        remaining = (
            db.query(Article).filter(func.lower(Article.title) == TITLE, Article.status != "archived").count()
            + db.query(VideoInstruction)
            .filter(func.lower(VideoInstruction.title) == TITLE, VideoInstruction.is_archived == False)  # noqa: E712
            .count()
        )
        if remaining:
            log.error("Verification failed — %d matching item(s) still unarchived.", remaining)
            return 1

        log.info("Verification passed — no unarchived items titled '%s' remain.", TITLE)
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    sys.exit(main())
