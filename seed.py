import os
import random
import sys
from datetime import datetime, timedelta

# Ensure parent directory is in path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

# Windows consoles often default to a non-UTF-8 codepage (cp1252), which can't
# encode the Georgian text in this script's print() calls and crashes the
# seeder mid-run with UnicodeEncodeError - after the DB commit already
# succeeded, so it looks like a failure when the data actually landed. Force
# UTF-8 stdout/stderr where supported (Python 3.7+); harmless no-op elsewhere.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

from database import SessionLocal, engine, Base
import models
from models import (
    User, VideoInstruction, News, Article, RequiredReading,
    ReadStatus, Message, Favorite,
)
from security import get_password_hash

DEFAULT_PASSWORD = "password"


DEFAULT_PERMISSIONS_BY_ROLE = {
    "admin": [
        "articles.publish", "articles.verify", "articles.nudge", "articles.history",
        "news.publish", "news.history", "videos.manage", "users.manage", "reports.export"
    ],
    "content_admin": [
        "articles.publish", "articles.verify", "articles.history", "news.publish", "news.history", "videos.manage"
    ],
    "manager": [
        "articles.nudge", "reports.export"
    ],
    "operator": []
}

# ─────────────────────────────────────────────────────────────────────────────
# Realistic compliance distribution, shared by seed_database() and
# seed_org_hierarchy(). Demo data should never sit at a flat 0% - that reads as
# "nobody uses this" rather than "this is a working compliance tool".
#
#   ~80% of operators: 90-100% read  (high performers)
#   ~15% of operators: 70-89% read   (needs minor attention)
#   ~5%  of operators: 15-69% read   (clear outliers - the Admin "nudge" demo)
# ─────────────────────────────────────────────────────────────────────────────
_COMPLIANCE_TIERS = [
    (0.80, 90, 100),
    (0.95, 70, 89),
    (1.00, 15, 69),
]


def _pick_target_percentage() -> float:
    r = random.random()
    cumulative = 0.0
    for threshold, lo, hi in _COMPLIANCE_TIERS:
        cumulative = threshold
        if r < cumulative:
            return random.uniform(lo, hi)
    return random.uniform(*_COMPLIANCE_TIERS[-1][1:])  # r >= last threshold (rounding edge)


def _assign_realistic_compliance(db, operators, now):
    """Marks a random subset of each operator's applicable required readings as
    'read' so their percentage lands in one of the bands above.

    "Applicable" mirrors main.py's _reading_progress: readings targeting "All"
    plus readings targeting the operator's own department string - which for
    the org-hierarchy seeder's unique per-group labels means just "All".

    Idempotent: skips any operator who already has a ReadStatus row, so a
    re-run of `seed.py org` doesn't reshuffle numbers a demo already relies on.
    """
    all_readings = db.query(RequiredReading).all()
    if not all_readings:
        return 0  # nothing to mark read yet (seed_database() hasn't run)

    by_dept = {}
    for r in all_readings:
        by_dept.setdefault(r.target_department, []).append(r)
    by_all = by_dept.get("All", [])

    new_rows = []
    for user in operators:
        if db.query(ReadStatus.id).filter(ReadStatus.user_id == user.id).first():
            continue  # already has compliance data - leave it alone

        applicable = {r.id: r for r in (by_all + by_dept.get(user.department, []))}
        applicable = list(applicable.values())
        if not applicable:
            continue

        n_read = round(_pick_target_percentage() / 100 * len(applicable))
        n_read = max(0, min(n_read, len(applicable)))
        for r in random.sample(applicable, n_read):
            new_rows.append(ReadStatus(
                user_id=user.id, required_reading_id=r.id, status="read",
                read_at=now - timedelta(days=random.randint(0, 6), hours=random.randint(0, 23)),
            ))

    if new_rows:
        db.add_all(new_rows)
    return len(new_rows)


def seed_database():
    db = SessionLocal()
    try:
        print("Cleaning old data...")
        for model in (
            ReadStatus, models.ArticleHistory, models.AuditLog, models.SearchLog, Message, Favorite,
            RequiredReading, News, VideoInstruction, User,
        ):  # NOTE: Article, Category, ArticleTargetDepartment intentionally excluded — article content is permanent
            try:
                db.query(model).delete()
            except Exception:
                pass
        db.commit()

        hashed_pw = get_password_hash(DEFAULT_PASSWORD)

        # ── Users (all four roles; @magti.ge to match the live environment) ──
        print("Seeding users...")
        admin = User(name="სისტემური ადმინი", email="admin@magti.ge",
                     role="admin", department="Administration",
                     position="Chief Admin Officer", is_active=True, hashed_password=hashed_pw,
                     permissions=DEFAULT_PERMISSIONS_BY_ROLE["admin"])
        content_admin = User(name="კონტენტის ადმინისტრატორი", email="content@magti.ge",
                             role="content_admin", department="All",
                             position="Content Manager", is_active=True, hashed_password=hashed_pw,
                             permissions=DEFAULT_PERMISSIONS_BY_ROLE["content_admin"])

        # Demo team members — use real department prefixes so filtering works
        manager = User(name="ჯგუფის მენეჯერი (ტექნიკური)", email="manager@magti.ge",
                       role="manager", department="ტექნიკური",
                       position="ჯგუფის უფროსი", is_active=True, hashed_password=hashed_pw,
                       permissions=DEFAULT_PERMISSIONS_BY_ROLE["manager"])
        nino = User(name="ნინო ჩიტიშვილი", email="nino@magti.ge",
                    role="operator", department="ტექნიკური",
                    position="ოპერატორი", phone="555 00 00 00", is_active=True, hashed_password=hashed_pw,
                    permissions=DEFAULT_PERMISSIONS_BY_ROLE["operator"])
        tech = User(name="ტექნიკური ოპერატორი", email="tech@magti.ge",
                    role="operator", department="ტექნიკური",
                    position="ოპერატორი", is_active=True, hashed_password=hashed_pw,
                    permissions=DEFAULT_PERMISSIONS_BY_ROLE["operator"])
        info = User(name="საინფო ოპერატორი", email="info@magti.ge",
                    role="operator", department="საინფო",
                    position="ოპერატორი", is_active=True, hashed_password=hashed_pw,
                    permissions=DEFAULT_PERMISSIONS_BY_ROLE["operator"])

        # NOTE: Billing and Sales departments were retired (see scripts/migrate_departments.py).
        # Do not re-add dedicated Billing/Sales User accounts here — that would
        # silently re-introduce the departments this seed script is meant to keep gone.

        db.add_all([
            admin, content_admin, manager, nino, tech, info,
        ])
        db.flush()  # assign IDs

        # ── Video instructions (centralised) ──────────────────────────────────
        print("Seeding video instructions...")
        videos = [
            VideoInstruction(title="PIN კოდის მართვა", video_url="https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0", category="ტექნიკური", target_department="All", views_count=42),
            VideoInstruction(title="სინქრონიზაცია", video_url="https://www.youtube.com/embed/9bZkp7q19f0?rel=0", category="ტექნიკური", target_department="All", views_count=17),
            VideoInstruction(title="დილერების აქტივაცია", video_url="https://www.youtube.com/embed/kJQP7kiw5Fk?rel=0", category="ტექნიკური", target_department="ტექნიკური", views_count=8),
        ]
        db.add_all(videos)
        db.flush()

        # ── News ────────────────────────────────────────────────────────────
        print("Seeding news...")
        news = [
            News(title="IPTV ახალი არხების დამატება", content="დაემატა ახალი არხები IPTV პაკეტში.", target_department="All", is_draft=False),
            News(title="„როუმერის“ ახალი პარტნიორი ოპერატორები", content="გაფართოვდა როუმინგის პარტნიორების სია.", target_department="All", is_draft=False),
            News(title="ტექნიკური: განახლებული სკრიპტები", content="ტექნიკური გუნდისთვის ახალი სასაუბრო სკრიპტები.", target_department="ტექნიკური", is_draft=False),
            # Two extra "All"-targeted items purely so there are 10 "All" required
            # readings total - see the comment by the required-readings block below.
            News(title="სისტემის გეგმური პროფილაქტიკა", content="ამ შაბათ-კვირას მოსალოდნელია მოკლევადიანი მომსახურების შეფერხება.", target_department="All", is_draft=False),
            News(title="ახალი ჩატის სკრიპტების ბაზა", content="დაემატა განახლებული საუბრის შაბლონები ყველა დეპარტამენტისთვის.", target_department="All", is_draft=False),
        ]
        db.add_all(news)
        db.flush()

        # ── Required readings (compliance) ──────────────────────────────────
        # Pick existing articles from DB (permanent content) for compliance demos.
        # Mix departments + due-dates so "overdue / to-read / done" states all appear.
        print("Seeding required readings...")
        now = datetime.utcnow()

        _arts_by_dept: dict = {}
        for a in db.query(Article).filter(Article.is_draft == False).all():
            _arts_by_dept.setdefault(a.target_department, []).append(a)

        def _pick(dept: str, n: int = 2) -> list:
            return (_arts_by_dept.get(dept, []) + _arts_by_dept.get("All", []))[:n]

        _all_arts  = _pick("All", 4)
        _tech_arts = _pick("ტექნიკური", 2)
        _info_arts = _pick("საინფო", 2)

        readings = []
        if len(_all_arts) >= 1:
            readings.append(RequiredReading(item_type="article", item_id=_all_arts[0].id,
                target_department="All", due_date=now - timedelta(days=3), priority="high"))
        if len(_all_arts) >= 2:
            readings.append(RequiredReading(item_type="article", item_id=_all_arts[1].id,
                target_department="All", due_date=now + timedelta(days=10), priority="normal"))
        if len(_all_arts) >= 3:
            readings.append(RequiredReading(item_type="article", item_id=_all_arts[2].id,
                target_department="All", due_date=now + timedelta(days=12), priority="normal"))
        if len(_all_arts) >= 4:
            readings.append(RequiredReading(item_type="article", item_id=_all_arts[3].id,
                target_department="All", due_date=now + timedelta(days=9), priority="normal"))
        if len(_tech_arts) >= 1:
            readings.append(RequiredReading(item_type="article", item_id=_tech_arts[0].id,
                target_department="ტექნიკური", due_date=now + timedelta(days=5), priority="high"))
        if len(_tech_arts) >= 2:
            readings.append(RequiredReading(item_type="article", item_id=_tech_arts[1].id,
                target_department="ტექნიკური", due_date=now + timedelta(days=14), priority="normal"))
        if len(_info_arts) >= 1:
            readings.append(RequiredReading(item_type="article", item_id=_info_arts[0].id,
                target_department="საინფო", due_date=now + timedelta(days=5), priority="high"))
        if len(_info_arts) >= 2:
            readings.append(RequiredReading(item_type="article", item_id=_info_arts[1].id,
                target_department="საინფო", due_date=now + timedelta(days=7), priority="normal"))
        readings += [
            RequiredReading(item_type="video", item_id=videos[0].id, target_department="All",
                            due_date=now + timedelta(days=15), priority="normal"),
            RequiredReading(item_type="video", item_id=videos[1].id, target_department="All",
                            due_date=now + timedelta(days=15), priority="normal"),
        ]
        if news:
            for n in (news[0], news[1], news[3], news[4]):
                readings.append(RequiredReading(item_type="news", item_id=n.id, target_department="All",
                                                due_date=now + timedelta(days=7), priority="normal"))

        db.add_all(readings)
        db.flush()

        # ── Read statuses ───────────────────────────────────────────────────
        # Realistic compliance distribution instead of a flat 0% - see
        # _assign_realistic_compliance above. Applies to nino/tech (Support)
        # and info (Informational) alike.
        print("Seeding read statuses...")
        _assign_realistic_compliance(db, [nino, tech, info], now)

        # ── A welcome message from the manager to a team member ─────────────
        db.add(Message(user_id=nino.id, sender_id=manager.id,
                       content="გამარჯობა, ნინო. გთხოვ, გაეცანი სავალდებულო მასალებს.",
                       is_read=False))

        db.commit()
        print("Seed completed successfully!\n")

        print("==========================================================")
        print("ACCESS URLS:")
        print("Application URL: http://127.0.0.1:8000")
        print("----------------------------------------------------------")
        print("LOGIN ACCOUNTS (password: password)")
        print("  admin@magti.ge     -> admin          (system administrator)")
        print("  content@magti.ge   -> content_admin  (content management)")
        print("  manager@magti.ge   -> manager        (ტექნიკური)")
        print("  nino@magti.ge      -> operator       (ტექნიკური)")
        print("  tech@magti.ge      -> operator       (ტექნიკური)")
        print("  info@magti.ge      -> operator       (საინფო)")
        print("==========================================================")

    except Exception as e:
        db.rollback()
        print(f"An error occurred while seeding: {e}")
        raise
    finally:
        db.close()


# ─────────────────────────────────────────────────────────────────────────────
# ORG-HIERARCHY SEEDER  —  `python seed.py org`
#
# IDEMPOTENT & NON-DESTRUCTIVE. Unlike seed_database() above (which wipes the DB),
# this ONLY upserts a fixed set of 165 employees by email. It never deletes rows,
# so existing users, audit logs and required-reading states are left intact.
#
# Structure (exactly):
#   • საინფორმაციო სამსახური (Information = Desk + Mob) — 10 groups
#   • ტექნიკური სამსახური (Technical)                  — 5 groups
#   Every group = 1 ჯგუფის უფროსი (manager) + 10 თანამშრომელი = 11 members.
#   15 groups × 11 = 165 users.
#
# Each group maps to its own `department` value, because the manager Team-Stats
# view groups by department — so every group becomes a real team led by its
# manager. Employee roles are distributed: content_admin (1/group) and a couple
# of cross-team system admins, the rest operators.
# ─────────────────────────────────────────────────────────────────────────────

_ORG_FIRST_NAMES = [
    "გიორგი", "ნინო", "დავით", "მარიამ", "ლევან", "თამარ", "ირაკლი", "ანა",
    "ზურაბ", "ქეთევან", "ნიკა", "სოფიო", "ბექა", "ელენე", "გელა", "თეა",
    "ვახტანგ", "ლია", "სანდრო", "ნათია", "ოთარ", "მაია", "გოჩა", "ხატია",
]
_ORG_LAST_NAMES = [
    "ბერიძე", "მაისურაძე", "გელაშვილი", "ლომიძე", "წერეთელი", "კვარაცხელია",
    "მამედაშვილი", "ჩხეიძე", "ფირცხალავა", "კიკნაძე", "გოგიჩაიშვილი", "თავაძე",
    "ნადირაძე", "ჯაფარიძე", "ხურციძე", "ბოლქვაძე", "სანიკიძე", "წიკლაური",
    "აბაშიძე", "გვასალია", "შენგელია", "მელაძე", "ქავთარაძე", "დოლიძე",
]


def _org_name(seq):
    """Deterministic Georgian full name from a sequence index (stable re-runs)."""
    first = _ORG_FIRST_NAMES[seq % len(_ORG_FIRST_NAMES)]
    last = _ORG_LAST_NAMES[(seq // len(_ORG_FIRST_NAMES) + seq) % len(_ORG_LAST_NAMES)]
    return f"{first} {last}"


def _org_upsert(db, email, name, department, position, role, counters):
    """Insert-or-update one user by email. Password is only set on first insert."""
    u = db.query(User).filter(User.email == email).first()
    if not u:
        u = User(email=email, hashed_password=get_password_hash(DEFAULT_PASSWORD))
        db.add(u)
        counters["created"] += 1
    else:
        counters["updated"] += 1
    u.name = name
    u.department = department
    u.position = position
    u.role = role
    u.permissions = DEFAULT_PERMISSIONS_BY_ROLE.get(role, [])
    u.is_active = True
    counters["roles"][role] = counters["roles"].get(role, 0) + 1
    return u


def seed_org_hierarchy():
    """Idempotently upsert the org hierarchy: 3 departments, each with sub-groups."""
    db = SessionLocal()
    # (department prefix, email code, number of groups, operators per group)
    departments = [
        ("ტექნიკური",    "tech",   5, 8),
        ("საინფო",   "info",   5, 8),
        ("ოფისი",         "office", 3, 6),
    ]
    counters = {"created": 0, "updated": 0, "groups": 0, "roles": {}, "renamed": 0}
    seq = 0
    now = datetime.utcnow()
    org_operators = []
    try:
        for prefix, code, n_groups, n_ops in departments:
            for g in range(1, n_groups + 1):
                dept_label = f"{prefix} — ჯგუფი {g:02d}"
                counters["groups"] += 1

                # 1 group leader (manager) per group
                _org_upsert(
                    db, f"{code}.g{g:02d}.lead@magti.ge", _org_name(seq),
                    dept_label, "ჯგუფის უფროსი", "manager", counters,
                )
                seq += 1

                # N operators per group — all plain operators, no admin/content_admin
                for e in range(1, n_ops + 1):
                    u = _org_upsert(
                        db, f"{code}.g{g:02d}.op{e:02d}@magti.ge", _org_name(seq),
                        dept_label, "ოპერატორი", "operator", counters,
                    )
                    org_operators.append(u)
                    seq += 1

        db.flush()  # assign ids to newly-created users before compliance assignment
        n_compliance = _assign_realistic_compliance(db, org_operators, now)
        db.commit()
        total = counters["created"] + counters["updated"]
        n_depts = len(departments)
        n_groups_total = counters["groups"]
        print("=" * 60)
        print("ORG HIERARCHY SEED — idempotent, non-destructive")
        print(f"  დეპარტამენტები: {n_depts} (ტექნიკური, საინფო, ოფისი)")
        print(f"  ჯგუფები სულ: {n_groups_total}")
        print(f"  სულ მომხმარებელი: {total}  (ახალი: {counters['created']}, განახლდა: {counters['updated']})")
        print(f"  compliance read-statuses: {n_compliance} ({len(org_operators)} operators)")
        print(f"  როლები: {counters['roles']}")
        print("  პაროლი ყველასთვის: password")
        print("=" * 60)
        return {"groups": n_groups_total, "total": total, **counters}
    except Exception as e:
        db.rollback()
        print(f"ORG seed error: {e}")
        raise
    finally:
        db.close()


def _refuse_destructive_reseed_in_production() -> None:
    """Stops `python seed.py` from wiping a production database.

    Audit 3, RTA-013. This script's no-argument branch deletes users, articles,
    audit rows, messages and compliance records from whatever database
    ``DATABASE_URL`` points at, and it ships inside the image: the root
    Dockerfile does ``COPY . .`` and .dockerignore did not exclude it. So the
    whole disaster was an operator with a shell in the wrong container typing
    a command that reads like an idempotent bootstrap.

    The guard is deliberately two-part. ``APP_ENV`` alone is not enough --
    it defaults to production, so a developer's local machine would be
    refused too, and a guard people routinely override is not a guard. The
    override is an explicit, unmistakable phrase rather than a bare flag,
    because the point is that nobody types it by accident or copies it out of
    a runbook without reading it.
    """
    app_env = os.getenv("APP_ENV", "production").strip().lower()
    if app_env != "production":
        return
    if os.getenv("SEED_CONFIRM_WIPE") == "yes-destroy-all-data":
        print("WARNING: destructive reseed running with APP_ENV=production, by explicit confirmation.")
        return
    sys.exit(
        "REFUSED: `python seed.py` deletes every user, article, audit record, message and\n"
        "compliance result in the database at DATABASE_URL, and APP_ENV is 'production'.\n"
        "\n"
        "  - Local development?  Set APP_ENV=development.\n"
        "  - Org hierarchy only? Run `python seed.py org` -- idempotent, deletes nothing.\n"
        "  - You really mean it? SEED_CONFIRM_WIPE=yes-destroy-all-data python seed.py\n"
    )


if __name__ == "__main__":
    # `python seed.py org` -> idempotent, non-destructive org-hierarchy seeder.
    # `python seed.py`     -> DESTRUCTIVE full demo reseed (wipes the DB first).
    wants_org_only = len(sys.argv) > 1 and sys.argv[1].lower() == "org"
    if not wants_org_only:
        # Before create_all, so the refusal happens without touching the
        # database at all -- a guard that has already issued DDL is a guard
        # that ran too late.
        _refuse_destructive_reseed_in_production()

    # Make sure the schema exists before we try to clear / insert anything.
    # Running this script on a fresh checkout (no magti_portal.db yet) used to
    # blow up on the very first DELETE because the tables didn't exist; now we
    # create them on demand so `python seed.py` is a one-shot bootstrap.
    Base.metadata.create_all(bind=engine)
    if wants_org_only:
        seed_org_hierarchy()
    else:
        seed_database()
