"""
fix_kb_migration.py — One-shot KB data integrity fix
=====================================================
Addresses findings from the Comet AI deep content crawl:

  1. Article #18 (როუმინგის აქტივაცია) is in category 17 ("ციფრული სერვისები /
     MyMagti") instead of category 1 ("როუმინგი"). This causes the related-
     articles algorithm to surface unrelated hosting / call-center articles.
     Fix: re-categorise to cat 1 and add roaming-specific tags.

  2. Article #17 (როუმინგული ტარიფები) is in category 14 ("ფიქსირებული /
     ოპტიკური ინტერნეტი") — also a miscategorised roaming article.
     Fix: move to cat 1.

  3. Article #21 (IPTV პულტის კოდები) is in category 15 (correct) but has null
     tags, so the tag-matching phase of /related contributes nothing and the
     generic backfill surfaces hosting articles.
     Fix: add IPTV/technical tags so it cross-links with other IPTV content.

  4. Article #19 (ბოჭკოვანი ინტერნეტის ინსტალაცია) is scoped to Billing only,
     has null tags and audience_profile "info". Operators across departments
     need this procedure.
     Fix: broaden target_department to "All", add tags, set audience to "all".

Run once:  python fix_kb_migration.py
Safe:      reads current values, logs before/after, rolls back on error.
"""

import sys
import io
from datetime import datetime, timezone

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

from database import SessionLocal
from models import Article

FIXES = [
    {
        "id": 17,
        "label": "როუმინგული ტარიფები — recategorise to როუმინგი",
        "updates": {
            "category_id": 1,
            "tags": "როუმინგი,ტარიფი,საერთაშორისო",
        },
    },
    {
        "id": 18,
        "label": "როუმინგის აქტივაცია — recategorise to როუმინგი + add tags",
        "updates": {
            "category_id": 1,
            "tags": "როუმინგი,აქტივაცია,MyMagti,SMS",
        },
    },
    {
        "id": 19,
        "label": "ბოჭკოვანი ინტერნეტის ინსტალაცია — broaden visibility + add tags",
        "updates": {
            "target_department": "All",
            "audience_profile": "all",
            "tags": "ინსტალაცია,FTTH,ოპტიკური,მონტაჟი,ინტერნეტი",
        },
    },
    {
        "id": 21,
        "label": "IPTV პულტის კოდები — add IPTV/technical tags",
        "updates": {
            "tags": "iptv,პულტი,კოდები,STB,ტექნიკური",
        },
    },
]


def run():
    db = SessionLocal()
    try:
        for fix in FIXES:
            article = db.query(Article).filter(Article.id == fix["id"]).first()
            if not article:
                print(f"  [SKIP] Article #{fix['id']} not found — {fix['label']}")
                continue

            print(f"\n  [{fix['id']}] {fix['label']}")
            for col, new_val in fix["updates"].items():
                old_val = getattr(article, col, None)
                print(f"         {col}: {old_val!r}  →  {new_val!r}")
                setattr(article, col, new_val)

            article.updated_at = datetime.now(timezone.utc)

        db.commit()
        print("\n  [OK] All fixes committed.\n")

    except Exception as exc:
        db.rollback()
        print(f"\n  [ERROR] Rolled back — {exc}\n", file=sys.stderr)
        raise
    finally:
        db.close()


if __name__ == "__main__":
    print("\n=== fix_kb_migration.py ===\n")
    run()
