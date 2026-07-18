"""
One-time repair for duplicate article_history rows.

Two now-fixed bugs could create more than one article_history row for the
same (article_id, version_id):
  1. get_article_versions' self-healing insert-on-GET had no unique
     constraint backing it, so two concurrent requests viewing the same
     under-migrated article's history could both insert a "current version"
     row.
  2. migrate.py's backfill_missing_version_1 cloned the oldest existing
     history row into a new "Version 1" entry instead of renumbering it,
     leaving two rows with identical content.

Run in dry-run mode (default) first to see what would be deleted, then
with --apply to actually delete. Must be run — and confirmed clean — before
migrate.py's ux_article_history_article_version unique index is deployed,
or that index's creation will fail on the leftover duplicates.

Usage:
    python scripts/repair_article_history_duplicates.py            # dry run
    python scripts/repair_article_history_duplicates.py --apply    # delete
"""
import argparse
import sys

sys.path.append('.')

from sqlalchemy import func
from sqlalchemy.orm import sessionmaker

from database import engine
import models


def find_duplicate_groups(db):
    """Returns [(article_id, version_id, [history_id, ...] sorted ascending), ...]
    for every (article_id, version_id) pair with more than one row."""
    dupe_keys = (
        db.query(models.ArticleHistory.article_id, models.ArticleHistory.version_id)
        .filter(models.ArticleHistory.version_id.isnot(None))
        .group_by(models.ArticleHistory.article_id, models.ArticleHistory.version_id)
        .having(func.count(models.ArticleHistory.id) > 1)
        .all()
    )
    groups = []
    for article_id, version_id in dupe_keys:
        ids = [
            row.id for row in db.query(models.ArticleHistory.id)
            .filter(
                models.ArticleHistory.article_id == article_id,
                models.ArticleHistory.version_id == version_id,
            )
            .order_by(models.ArticleHistory.id.asc())
            .all()
        ]
        groups.append((article_id, version_id, ids))
    return groups


def repair(apply: bool) -> int:
    Session = sessionmaker(bind=engine)
    db = Session()
    try:
        groups = find_duplicate_groups(db)
        if not groups:
            print("No duplicate (article_id, version_id) groups found. Nothing to do.")
            return 0

        total_extra_rows = 0
        for article_id, version_id, ids in groups:
            keep, drop = ids[0], ids[1:]
            total_extra_rows += len(drop)
            print(f"article_id={article_id} version_id={version_id}: "
                  f"keeping history_id={keep}, {'deleting' if apply else 'would delete'} {drop}")
            if apply:
                db.query(models.ArticleHistory).filter(
                    models.ArticleHistory.id.in_(drop)
                ).delete(synchronize_session=False)

        if apply:
            db.commit()
            print(f"\nDeleted {total_extra_rows} duplicate row(s) across {len(groups)} group(s).")
        else:
            print(f"\nDry run: {total_extra_rows} duplicate row(s) across {len(groups)} group(s) "
                  f"would be deleted. Re-run with --apply to actually delete.")
        return total_extra_rows
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true",
                         help="Actually delete duplicates (default: dry run / report only).")
    args = parser.parse_args()
    repair(apply=args.apply)
