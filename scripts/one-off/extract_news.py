import os
import glob
import json
import re
from bs4 import BeautifulSoup

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
SOURCE_DIR = os.path.join(BASE_DIR, "old web base")
DATA_DIR = os.path.join(BASE_DIR, "src", "data", "news")
OUT_FILE = os.path.join(DATA_DIR, "news_data.json")

REMOVE_TAGS = ["script", "style", "svg", "noscript", "nav", "header", "footer", "button"]
REMOVE_ROLES = {"banner", "navigation", "search", "complementary", "contentinfo",
                "button", "region", "dialog", "menu", "menubar", "toolbar", "tablist"}
TITLE_PREFIXES = ["Magti Call Center -", "Magti Call Center –", "ISP ", "MOB ", "Mob "]

def clean_title(raw: str) -> str:
    t = (raw or "").strip()
    t = re.sub(r"\s*\(\d{1,2}[_/／]\d{1,2}[_/／]\d{2,4}[^)]*\)\s*$", "", t)
    for p in TITLE_PREFIXES:
        if t.startswith(p):
            t = t[len(p):].strip()
    return re.sub(r"\s+", " ", t).strip(" -–—")

def sanitize_content(soup: BeautifulSoup) -> str:
    node = soup.find("div", attrs={"jsname": "ZBtY8b"})
    if node and node.get_text(strip=True):
        main_tag = node
    else:
        blocks = soup.find_all(["section", "article", "main", "div"])
        main_tag = max(blocks, key=lambda e: len(e.get_text(strip=True)), default=None) or soup.body

    if not main_tag:
        return ""

    for el in main_tag.find_all(attrs={"role": True}):
        if not getattr(el, "decomposed", False) and el.get("role") in REMOVE_ROLES:
            el.decompose()
    for tag in main_tag.find_all(REMOVE_TAGS):
        tag.decompose()

    return main_tag.decode_contents().strip()

def main():
    if not os.path.isdir(SOURCE_DIR):
        print(f"Error: '{SOURCE_DIR}' not found. Cannot extract.")
        return

    os.makedirs(DATA_DIR, exist_ok=True)
    html_files = glob.glob(os.path.join(SOURCE_DIR, "**", "*.html"), recursive=True)
    
    news_items = []
    for path in html_files:
        folder = os.path.basename(os.path.dirname(path))
        filename = os.path.splitext(os.path.basename(path))[0]
        target_dept = "Support" if folder.lower() == "tech" else "All"

        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                raw_html = f.read()
            soup = BeautifulSoup(raw_html, "html.parser")

            raw_title = soup.title.get_text() if soup.title else ""
            title = clean_title(raw_title)
            if not title.strip():
                title = clean_title(filename) or filename
            
            content = sanitize_content(soup)
            if not content.strip():
                content = soup.body.decode_contents().strip() if soup.body else raw_html.strip()
        except Exception as e:
            print(f"Error processing {path}: {e}. Falling back to raw file content.")
            title = clean_title(filename) or filename
            content = raw_html if 'raw_html' in locals() else "Error reading file content."

        news_items.append({"title": title, "content": content, "target_department": target_dept})

    with open(OUT_FILE, "w", encoding="utf-8") as f:
        json.dump(news_items, f, ensure_ascii=False, indent=4)

    print(f"Successfully extracted {len(news_items)} items to '{OUT_FILE}'.")
    print("You can now safely run 'python seed.py', then delete the 'old web base' directory.")

if __name__ == "__main__":
    main()