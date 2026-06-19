"""Smart content analyzer + taxonomy seeder (idempotent, NON-destructive).

Scans every article's HTML, groups it into exactly 10 telecom categories
(9 themed + 1 "ზოგადი / სხვადასხვა" catch-all) by keyword frequency, and assigns
an `audience_profile` ('info' | 'tech' | 'all') from commercial-vs-infrastructure
jargon. Only UPDATEs article rows and UPSERTs the 10 categories — it never drops
tables, users, or logs. Safe to re-run.

Usage:
    python scripts/one-off/classify_taxonomy.py
"""
import os
import re
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))

from database import SessionLocal  # noqa: E402
from models import Article, Category  # noqa: E402
from migrate import ensure_columns  # noqa: E402  (idempotent ADD COLUMN)

# ── The 10 categories: slug -> (Georgian name, Font Awesome icon, keywords) ──
# Order matters only for display; classification is by keyword score. The last
# entry (general) is the catch-all and intentionally has no keywords.
CATEGORIES = [
    ("mobile",    "მობილური სერვისები",            "fa-mobile-screen-button",
     ["მობილ", "esim", "ე-სიმ", " sim", "ნომრ", "პორტირ", "112-ის", "სააბონენტო"]),
    ("fiber",     "ფიქსირებული / ოპტიკური ინტერნეტი", "fa-network-wired",
     ["ოპტიკ", "ბოჭკ", "ftth", "fttb", "gpon", "ინტერნეტ", "მოდემ", "როუტერ", "სიჩქარ", "მბ_წმ", "მბ/წმ"]),
    ("iptv",      "IPTV ტელევიზია",                 "fa-tv",
     ["iptv", "ტელევიზ", "არხ", "stb", "პულტ", "tv play", "cavea", "თემატურ"]),
    ("hosting",   "ჰოსტინგი და დომენი",             "fa-server",
     ["ჰოსტინგ", "დომენ", "cpanel", "hosting"]),
    ("digital",   "ციფრული სერვისები / MyMagti",     "fa-wand-magic-sparkles",
     ["mymagti", "აპლიკაცი", "hi app", "ციფრ"]),
    ("billing",   "ბილინგი და გადახდები",           "fa-money-bill-wave",
     ["გადახდ", "ბილინგ", "კრედიტ", "დავალიანებ", "საფასურ", "თანხ", "განვადებ", "გადარიცხ", "ანგარიშსწორ"]),
    ("service",   "სერვისის მართვა",                "fa-gears",
     ["პაუზა", "შეჩერებ", "რეაქტივაცი", "გადაფორმ", "ტერმინაცი", "სტატუს"]),
    ("loyalty",   "ლოიალობა და აქციები",            "fa-gift",
     ["აქცი", "ქულ", "მაგთი ქარდ", "ლოიალ", "შეთავაზებ", "black friday", "უფასო"]),
    ("technical", "ტექნიკური ინსტრუქციები",          "fa-screwdriver-wrench",
     ["კონფიგურაცი", "სვიჩ", "vlan", "backend", "დაზიანებ", "ხარვეზ", "კაბელ", "error", "პორტი"]),
    ("general",   "ზოგადი / სხვადასხვა",            "fa-layer-group", []),
]

TECH_JARGON = ["კონფიგურაცი", "სვიჩ", "vlan", "backend", "gpon", "fttb", "ftth",
               "როუტერ", "მოდემ", "კაბელ", "error", "ip მისამართ", "ხარვეზ",
               "დაზიანებ", "პორტი"]
INFO_JARGON = ["ტარიფ", "აქცი", "გადახდ", "შეთავაზებ", "აბონ", "პაკეტ",
               "რეგისტრაცი", "სააბონენტო", "ფასდაკლებ", "ღირებულ"]

_TAG_RE = re.compile(r"<[^>]+>")


def _plain(html):
    return _TAG_RE.sub(" ", html or "").lower()


def _score(text, title, keywords):
    s = 0
    for kw in keywords:
        s += text.count(kw) + 2 * title.count(kw)
    return s


def classify():
    ensure_columns()
    db = SessionLocal()
    try:
        # 1) Upsert the 10 categories by slug (idempotent; refresh name/icon).
        slug_to_id = {}
        for slug, name, icon, _kw in CATEGORIES:
            cat = db.query(Category).filter(Category.slug == slug).first()
            if not cat:
                # reuse a same-named legacy category if present, else create
                cat = db.query(Category).filter(Category.name == name).first() or Category()
            cat.name, cat.slug, cat.icon = name, slug, icon
            if cat.id is None:
                db.add(cat)
            db.flush()
            slug_to_id[slug] = cat.id
        db.commit()

        # 2) Classify every published article (migrated + demo).
        articles = db.query(Article).all()
        per_cat = {slug: 0 for slug, *_ in CATEGORIES}
        per_profile = {"info": 0, "tech": 0, "all": 0}
        migrated = 0
        for a in articles:
            text = _plain(a.content)
            title = (a.title or "").lower()
            is_migrated = "მიგრირებული" in (a.tags or "")
            if is_migrated:
                migrated += 1

            # category: highest keyword score, else catch-all
            best_slug, best_score = "general", 0
            for slug, name, icon, kw in CATEGORIES[:-1]:
                sc = _score(text, title, kw)
                if sc > best_score:
                    best_slug, best_score = slug, sc
            a.category_id = slug_to_id[best_slug]
            per_cat[best_slug] += 1

            # audience profile from jargon balance
            tech_score = sum(text.count(k) for k in TECH_JARGON)
            info_score = sum(text.count(k) for k in INFO_JARGON)
            if best_slug == "technical" or (tech_score > 0 and tech_score >= info_score):
                profile = "tech"
            elif info_score > 0:
                profile = "info"
            else:
                profile = "all"
            a.audience_profile = profile
            per_profile[profile] += 1
        db.commit()

        print("=" * 58)
        print("TAXONOMY CLASSIFICATION — idempotent, non-destructive")
        print(f"  კატეგორიები: {len(CATEGORIES)} (9 თემატური + 1 catch-all)")
        print(f"  დაკლასიფიცირებული სტატია: {len(articles)} (მათ შორის მიგრირებული: {migrated})")
        print(f"  განაწილება კატეგორიებზე: {per_cat}")
        print(f"  audience_profile: {per_profile}")
        print("=" * 58)
        return {"categories": len(CATEGORIES), "articles": len(articles),
                "migrated": migrated, "per_cat": per_cat, "per_profile": per_profile}
    except Exception as exc:
        db.rollback()
        print(f"classify error: {exc}")
        raise
    finally:
        db.close()


if __name__ == "__main__":
    classify()
