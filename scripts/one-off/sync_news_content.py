"""
sync_news_content.py — One-off content audit and synchronization for News items.

This script iterates through all `News` items in the database, finds their
corresponding original HTML file in the `old web base/` directory, and updates
the database content if the original source is more complete. This is intended
to fix truncated or incomplete news items that were created during initial seeding.

The script is idempotent and safe to re-run. It uses a title-matching
heuristic to locate source files and a simple length comparison to detect
truncated content.

Usage:
    # See what would be updated without changing the database
    python sync_news_content.py --dry-run

    # Apply the content updates to the database
    python sync_news_content.py
"""
import os
import re
import sys
import glob
import argparse
from bs4 import BeautifulSoup
from sqlalchemy.orm import Session

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from database import SessionLocal
from models import News

# --- Configuration and Helpers (adapted from migrate_kb.py) ---

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
SOURCE_DIR = os.path.join(BASE_DIR, "old web base")

# Prefixes and suffixes to strip from titles for cleaner matching
TITLE_PREFIXES = ["Magti Call Center -", "Magti Call Center –", "ISP ", "MOB ", "Mob "]
TIMESTAMP_RE = re.compile(r"\s*\(\d{1,2}[_/／]\d{1,2}[_/／]\d{2,4}[^)]*\)\s*$")

# Tags and roles to remove for content cleaning
REMOVE_TAGS = ["script", "style", "svg", "noscript", "nav", "header", "footer", "button"]
REMOVE_ROLES = {"banner", "navigation", "search", "complementary", "contentinfo",
                "button", "region", "dialog", "menu", "menubar", "toolbar", "tablist"}


def clean_title(raw: str) -> str:
    """Strips common prefixes/suffixes from a title string for matching."""
    t = (raw or "").strip()
    t = TIMESTAMP_RE.sub("", t)
    for p in TITLE_PREFIXES:
        if t.startswith(p):
            t = t[len(p):].strip()
    return re.sub(r"\s+", " ", t).strip(" -–—")


def find_content_body(soup: BeautifulSoup):
    """Locates the main content block in a Google Sites HTML export."""
    node = soup.find("div", attrs={"jsname": "ZBtY8b"})
    if node and node.get_text(strip=True):
        return node
    blocks = soup.find_all(["section", "article", "main", "div"])
    best = max(blocks, key=lambda e: len(e.get_text(strip=True)), default=None)
    return best or soup.body


def sanitize_content(main_tag: BeautifulSoup) -> str:
    """
    Extracts and cleans the HTML content from the main content tag,
    removing unwanted page chrome like scripts, styles, and navigation elements.
    """
    if not main_tag:
        return ""
    # Decompose elements by role and tag name
    for el in main_tag.find_all(attrs={"role": True}):
        if not getattr(el, "decomposed", False) and el.get("role") in REMOVE_ROLES:
            el.decompose()
    for tag in main_tag.find_all(REMOVE_TAGS):
        tag.decompose()
    # Return the inner HTML of the cleaned tag
    return main_tag.decode_contents().strip()


def find_matching_html(news_title: str, all_files: list[str]) -> str | None:
    """
    Finds the best matching HTML file path for a given news title from a list of files.
    It prioritizes perfect matches in the HTML <title> tag, then falls back to
    fuzzy matching against cleaned filenames.
    """
    news_title_lower = news_title.lower()
    for path in all_files:
        # Check against cleaned filename
        filename_title = clean_title(os.path.splitext(os.path.basename(path))[0])
        if news_title_lower in filename_title.lower() or filename_title.lower() in news_title_lower:
            # Check against HTML <title> for a more accurate match
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as f:
                    soup = BeautifulSoup(f.read(), "html.parser")
                html_title_raw = soup.title.get_text() if soup.title else ""
                if news_title_lower == clean_title(html_title_raw).lower():
                    return path  # Perfect match
            except Exception:
                continue
            # Filename match is a good fallback
            return path
    return None


def sync_news(db: Session, dry_run: bool):
    """The main synchronization logic."""
    all_news = db.query(News).all()
    all_html_files = glob.glob(os.path.join(SOURCE_DIR, "**", "*.html"), recursive=True)

    print(f"Found {len(all_news)} news items in the database to audit.")
    print(f"Found {len(all_html_files)} source HTML files in '{SOURCE_DIR}'.")

    updated_count = 0
    not_found_count = 0

    for news_item in all_news:
        print(f"\nProcessing: '{news_item.title}' (ID: {news_item.id})")

        html_path = find_matching_html(news_item.title, all_html_files)

        if not html_path:
            print("  -> No matching source HTML found. Skipping.")
            not_found_count += 1
            continue

        print(f"  -> Found source: {os.path.relpath(html_path, BASE_DIR)}")

        try:
            with open(html_path, "r", encoding="utf-8", errors="replace") as f:
                soup = BeautifulSoup(f.read(), "html.parser")

            main_content_tag = find_content_body(soup)
            full_content = sanitize_content(main_content_tag)

            if not full_content.strip():
                print("  -> Source content is empty. Skipping.")
                continue

            # Heuristic: if the source content is significantly longer, update.
            if len(full_content) > len(news_item.content or "") + 50:
                print(f"  -> Content mismatch. DB: {len(news_item.content or '')} chars, Source: {len(full_content)} chars. UPDATING.")
                if not dry_run:
                    news_item.content = full_content
                    news_item.version = (news_item.version or 1) + 1
                updated_count += 1
            else:
                print("  -> Content seems up-to-date. No changes needed.")

        except Exception as e:
            print(f"  -> ERROR processing file {html_path}: {e}")

    if not dry_run:
        db.commit()
        print("\nDatabase has been updated.")

    print("\n--- Sync Complete ---")
    print(f"Updated: {updated_count} news items.")
    print(f"Source not found: {not_found_count} news items.")
    print(f"Unchanged: {len(all_news) - updated_count - not_found_count} news items.")


def main():
    parser = argparse.ArgumentParser(description="Synchronize news content from legacy HTML files into the database.")
    parser.add_argument("--dry-run", action="store_true", help="Parse and report what would be changed without writing to the database.")
    args = parser.parse_args()

    if not os.path.isdir(SOURCE_DIR):
        print(f"Error: Source directory not found at '{SOURCE_DIR}'")
        sys.exit(1)

    db = SessionLocal()
    try:
        sync_news(db, args.dry_run)
    finally:
        db.close()


if __name__ == "__main__":
    main()