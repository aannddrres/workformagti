"""Idempotent in-place patch: give the KB demo articles their full rich HTML.

WHY THIS EXISTS
---------------
`seed.py` historically seeded these production-critical articles with one-line
placeholder strings, so the reader modal showed a single summary line instead of
the real IPTV remote-code / APN-settings tables. seed.py is now fixed, but
re-running it DELETES the whole database (including the ~183 MB local dev data).

This script instead UPDATES only the matching article rows in the existing
database — no deletes, no reseed, safe to run against the live dev DB and safe to
run repeatedly. It pulls the exact same rich HTML bodies from seed.py so there is
a single source of truth.

Usage:
    python scripts/one-off/fix_article_content.py
"""
import os
import sys
from datetime import datetime

# Make the project root importable when run as scripts/one-off/fix_article_content.py
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))

from database import SessionLocal  # noqa: E402
from models import Article  # noqa: E402
import seed  # noqa: E402  (module-level HTML constants only; does NOT run seeding)

# title -> rich HTML body. Single source of truth lives in seed.py.
TITLE_TO_HTML = {
    "როუმინგული ტარიფები": seed.ROAMING_TARIFFS_HTML,
    "როუმინგის აქტივაცია": seed.ROAMING_ACTIVATION_HTML,
    "ბოჭკოვანი ინტერნეტის ინსტალაცია": seed.FTTH_INSTALL_HTML,
    "GPON პარამეტრები (Huawei)": seed.GPON_HUAWEI_HTML,
    "IPTV პულტის კოდები": seed.IPTV_REMOTE_CODES_HTML,
    "მობილური პორტირების პროცედურა": seed.PORTING_HTML,
    "MyMagti რეგისტრაცია": seed.MYMAGTI_REG_HTML,
    "მობილური ინტერნეტის პარამეტრები": seed.MOBILE_APN_HTML,
}


def patch_articles() -> None:
    db = SessionLocal()
    updated, skipped, missing = 0, 0, []
    try:
        for title, html in TITLE_TO_HTML.items():
            html = html.strip()
            art = db.query(Article).filter(Article.title == title).first()
            if not art:
                missing.append(title)
                continue
            if (art.content or "").strip() == html:
                skipped += 1  # already up to date — idempotent no-op
                continue
            art.content = html
            art.updated_at = datetime.utcnow()
            updated += 1
        db.commit()
    except Exception as exc:  # pragma: no cover
        db.rollback()
        print(f"[!] შეცდომა: {exc}")
        raise
    finally:
        db.close()

    print(f"განახლდა: {updated} | უცვლელი (უკვე სწორი): {skipped}")
    if missing:
        print("ვერ მოიძებნა (სათაური არ ემთხვევა ბაზას):")
        for t in missing:
            print(f"  - {t}")


if __name__ == "__main__":
    patch_articles()
