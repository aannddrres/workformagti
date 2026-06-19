"""
Additive test-content generator for the Magti portal.

Unlike seed.py (which WIPES the database), this script only ADDS more articles,
news, videos and required readings on top of whatever already exists — handy for
exercising the UI with a richer dataset across departments and statuses.

Safe to run while the server is up; safe to run multiple times (each run appends
a fresh batch).

    python add_test_content.py
"""
import sys
from datetime import datetime, timedelta

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from sqlalchemy import text

from database import SessionLocal, engine
from models import (
    Base, User, Category, Article, News, VideoInstruction, RequiredReading,
)


def main():
    Base.metadata.create_all(bind=engine)
    db = SessionLocal()
    try:
        # Wait (don't crash) if the running server briefly holds a write lock.
        db.execute(text("PRAGMA busy_timeout=5000"))

        # Author for the new content: prefer a content_admin, then admin, then any.
        author = (
            db.query(User).filter(User.role == "content_admin").first()
            or db.query(User).filter(User.role == "admin").first()
            or db.query(User).first()
        )
        if author is None:
            print("No users found — run `python seed.py` first.")
            return

        # Ensure categories exist (reuse existing, create only what's missing).
        wanted_cats = ["როუმინგი", "ინტერნეტი", "IPTV", "ტექნიკური", "მობილური", "კორპორატიული", "პორტირება", "MyMagti"]
        existing = {c.name: c for c in db.query(Category).all()}
        for name in wanted_cats:
            if name not in existing:
                c = Category(name=name)
                db.add(c)
                existing[name] = c
        db.flush()
        cat = {name: existing[name].id for name in existing}

        now = datetime.utcnow()

        def ago(d):
            return now - timedelta(days=d)

        # ── Articles: title, content, category, department, status, created_at ──
        new_articles = [
            ("eSIM-ის გააქტიურების ინსტრუქცია", "eSIM პროფილის ჩამოტვირთვა და გააქტიურება ნაბიჯ-ნაბიჯ.", "მობილური", "All", "published", ago(1)),
            ("ნომრის პორტირება სხვა ოპერატორიდან", "MNP პროცესი და საჭირო დოკუმენტები.", "მობილური", "All", "published", ago(2)),
            ("5G ქსელის დაფარვის ზონები 2026", "5G-ის ხელმისაწვდომი უბნები და მოწყობილობების მოთხოვნები.", "ტექნიკური", "Support", "published", ago(3)),
            ("Magti TV Play — ხშირად დასმული კითხვები", "ანგარიში, მოწყობილობები და გავრცელებული პრობლემები.", "IPTV", "Support", "published", ago(5)),
            ("ინტერნეტ პაკეტების შედარება", "სახლის ინტერნეტის ტარიფები და სიჩქარეები.", "ინტერნეტი", "All", "published", ago(8)),
            ("როუმინგი ევროპაში — ტარიფები", "ზონები, ფასები და გააქტიურების წესი.", "როუმინგი", "All", "published", ago(20)),
            ("ბილინგის ხშირი პრობლემები და გადაჭრა", "გადახდები, დავალიანება და ანგარიშის შემოწმება.", "კორპორატიული", "Support", "published", ago(12)),
            ("VoLTE-ს ჩართვა მოწყობილობაზე", "VoLTE-ს მხარდაჭერა და პარამეტრები.", "ტექნიკური", "Support", "published", ago(30)),
            ("SIM ბარათის დაბლოკვა და აღდგენა", "დაკარგული SIM-ის დაბლოკვისა და აღდგენის პროცედურა.", "მობილური", "All", "published", ago(4)),
            ("ზამთრის აქცია 2024 (დასრულებული)", "დასრულებული სააქციო შეთავაზება — არქივი.", "როუმინგი", "All", "archived", ago(400)),
        ]
        created = []
        for title, content, cname, dept, status, created_at in new_articles:
            art = Article(
                title=title, content=content, category_id=cat.get(cname),
                tags=cname, target_department=dept, status=status,
                author_id=author.id, created_at=created_at, updated_at=created_at,
            )
            db.add(art)
            created.append(art)
        db.flush()

        # ── News ──
        new_news = [
            ("ახალი 5G ანძები თბილისში", "გაფართოვდა 5G დაფარვა დედაქალაქში.", "All", ago(1)),
            ("Magti აპლიკაციის განახლება v4.2", "ახალი დიზაინი და გადახდის გაუმჯობესება.", "All", ago(2)),
            ("საახალწლო სამუშაო გრაფიკი", "მომსახურების განრიგი დღესასწაულებზე.", "All", ago(6)),
            ("Support: ახალი CRM სისტემა", "გუნდისთვის ახალი ხელსაწყო მომართვების დასამუშავებლად.", "Support", ago(3)),
            ("ინტერნეტის სიჩქარის გაზრდა რეგიონებში", "FTTH გაფართოება ახალ ქალაქებში.", "All", ago(15)),
        ]
        for title, content, dept, created_at in new_news:
            db.add(News(title=title, content=content, target_department=dept, created_at=created_at))

        # ── Videos ──
        yt = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        new_videos = [
            ("eSIM-ის გააქტიურება — ვიდეო", "ტექნიკური", "All"),
            ("როუტერის გადატვირთვა", "ტექნიკური", "All"),
            ("Magti TV Play-ის დაყენება", "IPTV", "All"),
            ("ბილინგის შემოწმება აპლიკაციაში", "კორპორატიული", "Support"),
        ]
        for title, cname, dept in new_videos:
            db.add(VideoInstruction(title=title, video_url=yt, category=cname, target_department=dept))

        # ── Required readings: varied due dates + departments ──
        a_esim = created[0]     # All
        a_billing = created[6]  # Support
        a_5g = created[2]       # Technical
        new_readings = [
            RequiredReading(item_type="article", item_id=a_esim.id, target_department="All",
                            due_date=now + timedelta(days=5), priority="high"),
            RequiredReading(item_type="article", item_id=a_billing.id, target_department="Support",
                            due_date=now - timedelta(days=2), priority="high"),   # overdue
            RequiredReading(item_type="article", item_id=a_5g.id, target_department="Support",
                            due_date=now + timedelta(days=10), priority="normal"),
        ]
        db.add_all(new_readings)

        db.commit()

        print("Added: %d articles, %d news, %d videos, %d required readings." % (
            len(new_articles), len(new_news), len(new_videos), len(new_readings)))
        print("Totals now -> articles: %d | news: %d | videos: %d | required_readings: %d" % (
            db.query(Article).count(), db.query(News).count(),
            db.query(VideoInstruction).count(), db.query(RequiredReading).count()))
        print("Tip: refresh the browser (Ctrl+Shift+R) to see the new content.")

    except Exception as e:
        db.rollback()
        print("Error:", e)
        raise
    finally:
        db.close()


if __name__ == "__main__":
    main()
