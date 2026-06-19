import os, sys
from bs4 import BeautifulSoup
from database import SessionLocal
from models import Article

# Import sanitization helpers from the migration script
# Adjust the import path if necessary; migration script is in the same directory
sys.path.append(os.path.dirname(__file__))
import migrate_kb as mkb


def retrofit_article(article):
    """Re‑process a single article's HTML content using the updated sanitizers.
    Returns True if the content was changed.
    """
    soup = BeautifulSoup(article.content, "html.parser")
    # Re‑use the same logic that migration uses to locate the main article body
    main = mkb.find_content(soup) or soup.body
    if main is None:
        return False
    new_content = mkb.sanitize(main)
    if new_content != article.content:
        article.content = new_content
        return True
    return False


def main(dry_run: bool = True):
    session = SessionLocal()
    articles = session.query(Article).order_by(Article.id).all()
    changed = 0
    for art in articles:
        if retrofit_article(art):
            changed += 1
            if not dry_run:
                session.add(art)
    if not dry_run:
        session.commit()
    print(f"Retrofit completed. Total articles processed: {len(articles)}. Changed: {changed}.")
    if dry_run:
        print("Run with '--apply' to persist changes to the database.")


if __name__ == "__main__":
    apply = "--apply" in sys.argv
    main(dry_run=not apply)
