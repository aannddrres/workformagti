import os
import glob
import hashlib
import base64
import mimetypes
import re
import urllib.request
from datetime import datetime, timezone

try:
    from bs4 import BeautifulSoup
except ImportError:
    print("გთხოვთ დააინსტალიროთ BeautifulSoup: ტერმინალში გაუშვით 'pip install beautifulsoup4'")
    exit()

from database import SessionLocal, engine
from models import Base, User, Category, Article

def download_and_save_image(src, upload_dir="uploads"):
    """Re-host an inline base64 image locally under a content-hashed filename
    (duplicates collapse to one file) and return its /uploads/ URL.

    NETWORK-FREE BY DESIGN: remote http(s) srcs are returned unchanged rather
    than fetched. Across ~155 source files this keeps the migration fast,
    deterministic and immune to network hangs — absolute URLs already render in
    the browser. Only base64 payloads (which would otherwise bloat the DB) are
    extracted to disk. Returns "" if a base64 blob cannot be decoded so the
    caller can drop the broken <img> instead of storing a multi-MB junk string.
    """
    try:
        if not src or not src.startswith("data:image"):
            return src  # remote / relative / empty -> leave as-is (no network)
        header, encoded = src.split(",", 1)
        mime = header.split(";")[0].split(":")[1]
        data = base64.b64decode(encoded)
        ext = mimetypes.guess_extension(mime) or ".png"
        if ext == ".jpe":
            ext = ".jpg"
        os.makedirs(upload_dir, exist_ok=True)
        digest = hashlib.sha256(data).hexdigest()
        filename = f"{digest}{ext}"
        filepath = os.path.join(upload_dir, filename)
        if not os.path.exists(filepath):
            with open(filepath, "wb") as f:
                f.write(data)
        return f"/uploads/{filename}"
    except Exception as e:
        print(f"  [!] base64 სურათის შენახვის შეცდომა: {e}")
        return ""

def download_and_save_file(url, upload_dir="uploads"):
    """
    ამოწმებს ბმულს, თუ Google Drive-ის ფაილია, აგენერირებს გადმოსაწერ ბმულს, 
    ტვირთავს და ინახავს ლოკალურად. აბრუნებს ახალ /uploads/ მისამართს.
    """
    os.makedirs(upload_dir, exist_ok=True)
    try:
        drive_match = re.search(r'drive\.google\.com/file/d/([a-zA-Z0-9_-]+)', url)
        if drive_match:
            file_id = drive_match.group(1)
            download_url = f"https://drive.google.com/uc?export=download&id={file_id}"
        elif url.startswith("http://") or url.startswith("https://"):
            download_url = url
        else:
            return url

        req = urllib.request.Request(download_url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=20) as response:
            data = response.read()
            mime = response.headers.get_content_type()
            
        ext = mimetypes.guess_extension(mime) or ""
        if not ext and not drive_match:
            ext = os.path.splitext(url)[1].split('?')[0]
        if ext == ".bin" or not ext:
            ext = ".pdf" # ნაგულისხმევი ვარაუდი Drive-ის დოკუმენტებისთვის
            
        digest = hashlib.sha256(data).hexdigest()
        filename = f"doc_{digest[:15]}{ext}"
        filepath = os.path.join(upload_dir, filename)
        
        if not os.path.exists(filepath):
            with open(filepath, "wb") as f:
                f.write(data)
        return f"/uploads/{filename}"
    except Exception as e:
        print(f"  [!] დოკუმენტის ჩამოტვირთვის შეცდომა ({url[:30]}...): {e}")
        return url

# Sentinel tag stamped on every migrated article so the Admin "მიგრირებული ბაზა"
# view can list exactly the legacy-imported set (vs hand-authored/seed articles).
MIGRATED_TAG = "მიგრირებული"

# Safe inline-style declarations to KEEP (the "visual data stream") while dropping
# Google Sites' layout/positioning styles (position/transform/top/left/width/...)
# that collapse or hide text when rendered outside their proprietary grid.
_SAFE_STYLE_PROPS = {
    "color", "background-color", "background", "font-weight", "font-style",
    "font-size", "text-align", "text-decoration", "text-transform",
    "vertical-align", "border", "border-color", "border-width", "border-style",
}


def sanitize_inline_style(style_value):
    """Keep only safe visual declarations from an inline style attribute."""
    kept = []
    for decl in str(style_value).split(";"):
        if ":" not in decl:
            continue
        prop, _, val = decl.partition(":")
        prop, val = prop.strip().lower(), val.strip()
        if (prop in _SAFE_STYLE_PROPS and val
                and "url(" not in val.lower() and "expression" not in val.lower()):
            kept.append(f"{prop}: {val}")
    return "; ".join(kept)


def extract_content_root(soup):
    """Resilient container fallback chain for varied Google Sites exports.

    Order: top-level div.QZ3zWd  ->  role="main" (if substantial)  ->
    div.sites-layout-tile  ->  <body>. Returns (root_element, selector_label).
    """
    # 1) Primary: concatenate the top-level .QZ3zWd content sections.
    blocks = soup.select("div.QZ3zWd")
    top_blocks = [b for b in blocks if not b.find_parent("div", class_="QZ3zWd")]
    if top_blocks and sum(len(b.get_text(strip=True)) for b in top_blocks) >= 40:
        wrapper = soup.new_tag("div")
        for b in top_blocks:
            wrapper.append(b.extract())
        return wrapper, "div.QZ3zWd"
    # 2) role="main", only if it actually carries content.
    main = soup.find(attrs={"role": "main"})
    if main and len(main.get_text(strip=True)) >= 40:
        return main, 'role="main"'
    # 3) Classic Google Sites layout tiles.
    tiles = soup.select("div.sites-layout-tile")
    if tiles and sum(len(t.get_text(strip=True)) for t in tiles) >= 40:
        wrapper = soup.new_tag("div")
        for t in tiles:
            wrapper.append(t.extract())
        return wrapper, "div.sites-layout-tile"
    # 4) Last resort: the whole body.
    return (soup.body or soup), "body"


def _categorize(lower_content):
    """Heuristic (category, target_department) from the article body text."""
    cname = "საინფორმაციო (Desk)"
    if any(w in lower_content for w in ["ბილინგ", "გადახდ", "ტარიფ", "კრედიტ", "საფასურ"]):
        cname = "ბილინგი"
    elif any(w in lower_content for w in ["პაუზა", "შეჩერებ", "რეაქტივაცი"]):
        cname = "სერვისის მართვა"
    elif any(w in lower_content for w in ["ქულებ", "მაგთი ქარდ", "ლოიალობ"]):
        cname = "ლოიალობა"
    elif any(w in lower_content for w in ["ინტერნეტ", "ოპტიკ", "მეგაბაიტ"]):
        cname = "ინტერნეტი"
    elif any(w in lower_content for w in ["iptv", "ტელევიზ", "tv play", "არხებ"]):
        cname = "IPTV"
    elif "როუმინგ" in lower_content:
        cname = "როუმინგი"

    target_dept = "Informational"
    if any(w in lower_content for w in ["როუტერ", "კონფიგურაცი", "პარამეტრ", "დაზიანებ",
                                        "ინსტალაცი", "ხარვეზ", "error", "კაბელ", "მოდემ"]):
        target_dept = "Support"
    elif (any(w in lower_content for w in ["აქცი", "როუმინგ", "პორტირებ"])
          or cname in ["როუმინგი", "ლოიალობა"]):
        target_dept = "All"
    return cname, target_dept


def import_all(directory="scripts/legacy"):
    """Recursively migrate every legacy .html/.htm under `directory` into the
    Article table via a SAFE idempotent upsert (match by title).

    NON-DESTRUCTIVE: never calls drop_all; create_all only creates missing
    tables. Users, audit logs and required-reading assignment states are left
    untouched (only Article rows are inserted/updated, in place, by title)."""
    Base.metadata.create_all(bind=engine)
    db = SessionLocal()

    if not os.path.isdir(directory):
        print(f"ფოლდერი '{directory}' ვერ მოიძებნა!")
        return

    author = (db.query(User).filter(User.role == "content_admin").first()
              or db.query(User).first())
    if not author:
        print("ბაზაში მომხმარებელი ვერ მოიძებნა — ჯერ გაუშვით seed.py ან შექმენით ადმინი.")
        return

    cat_names = ["საინფორმაციო (Desk)", "ბილინგი", "სერვისის მართვა",
                 "ლოიალობა", "ინტერნეტი", "IPTV", "როუმინგი", "მობილური"]
    cat_map = {}
    for name in cat_names:
        c = db.query(Category).filter(Category.name == name).first()
        if not c:
            c = Category(name=name)
            db.add(c)
            db.commit()
            db.refresh(c)
        cat_map[name] = c.id

    # Recursive discovery: .html AND .htm, every subfolder.
    html_files = sorted(set(
        glob.glob(os.path.join(directory, "**", "*.html"), recursive=True)
        + glob.glob(os.path.join(directory, "**", "*.htm"), recursive=True)
    ))
    print(f"აღმოჩენილია {len(html_files)} HTML/HTM ფაილი '{directory}'-ში (რეკურსიულად).")
    if not html_files:
        return

    added = updated = skipped = failed = 0
    selector_stats = {}
    keywords = ["ტარიფი", "პორტირება", "როუმინგი", "ბილინგი", "დავალიანება",
                "ინტერნეტი", "ოპტიკა", "iptv", "esim", "sim", "პაუზა",
                "შეჩერება", "მაგთი ქარდი", "ქულები", "როუტერი", "აქცია"]

    for file_path in html_files:
        try:
            with open(file_path, "r", encoding="utf-8", errors="replace") as f:
                soup = BeautifulSoup(f.read(), "html.parser")

            root, selector = extract_content_root(soup)
            selector_stats[selector] = selector_stats.get(selector, 0) + 1

            for tag in root.find_all(["script", "style", "header", "footer", "nav", "svg", "noscript"]):
                tag.decompose()

            # Keep structural attrs + a sanitized inline style (visual data stream).
            allowed = ['href', 'src', 'alt', 'target', 'title', 'frameborder', 'allow', 'allowfullscreen']
            for tag in root.find_all(True):
                new_attrs = {k: v for k, v in tag.attrs.items() if k in allowed}
                if 'style' in tag.attrs:
                    safe = sanitize_inline_style(tag.attrs['style'])
                    if safe:
                        new_attrs['style'] = safe
                tag.attrs = new_attrs

            # Re-host base64 images locally; drop ones that fail to decode.
            for img in root.find_all("img"):
                src = img.get("src")
                if not src:
                    continue
                new_src = download_and_save_image(src)
                if new_src:
                    img["src"] = new_src
                else:
                    img.decompose()

            content = root.decode_contents(formatter="html").strip()
            if len(soup.get_text(strip=True)) < 15 or not content:
                skipped += 1
                continue

            # Title: <title> -> first heading -> filename.
            title = None
            if soup.title and soup.title.string:
                title = soup.title.string.strip()
            if not title:
                h = soup.find(["h1", "h2", "h3"])
                if h:
                    title = h.get_text(strip=True)
            if not title:
                title = os.path.splitext(os.path.basename(file_path))[0]
            title = title.replace("Magti Call Center -", "").replace("Magti Call Center", "").strip()
            title_db = title[:255]

            lower_content = content.lower()
            cname, target_dept = _categorize(lower_content)

            tags_set = {cname, MIGRATED_TAG}
            if target_dept not in ("All", "Informational"):
                tags_set.add(target_dept)
            for kw in keywords:
                if kw in lower_content or kw in title_db.lower():
                    tags_set.add(kw)
            tags_str = ",".join(sorted(tags_set))

            now = datetime.now(timezone.utc)
            existing = db.query(Article).filter(Article.title == title_db).first()
            if existing:
                existing.content = content
                existing.category_id = cat_map[cname]
                existing.target_department = target_dept
                existing.tags = tags_str
                existing.updated_at = now
                updated += 1
            else:
                db.add(Article(
                    title=title_db, content=content, category_id=cat_map[cname],
                    target_department=target_dept, tags=tags_str, status="published",
                    author_id=author.id, created_at=now, updated_at=now,
                ))
                added += 1
            # Commit per file so a late failure never discards prior progress.
            db.commit()
        except Exception as e:
            db.rollback()
            failed += 1
            print(f"  [!] ვერ დამუშავდა {os.path.basename(file_path)}: {e}")

    migrated_total = db.query(Article).filter(Article.tags.like(f"%{MIGRATED_TAG}%")).count()
    articles_total = db.query(Article).count()
    db.close()
    print("──────────────────────────────────────────────")
    print(f"კონტეინერები (fallback chain): {selector_stats}")
    print(f"დაემატა: {added} | განახლდა: {updated} | გამოტოვებული: {skipped} | შეცდომა: {failed}")
    print(f"მიგრირებული სტატიები (tag='{MIGRATED_TAG}'): {migrated_total}")
    print(f"სტატიების სრული რაოდენობა ბაზაში: {articles_total}")
    return {"added": added, "updated": updated, "skipped": skipped, "failed": failed,
            "migrated_total": migrated_total, "articles_total": articles_total}


if __name__ == "__main__":
    import_all()