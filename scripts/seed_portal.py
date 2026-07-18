"""
ერთი სკრიპტი — ტესტ-ლოგინები / კომპანიის ორგსტრუქტურა / მსუბუქი დემო.

გამოყენება (რეპოს root-დან):

  # მხოლოდ TEST_LOGINS (~20) — სუფთა მინიმუმი
  venv\\Scripts\\python.exe scripts\\seed_portal.py users

  # კომპანიის ორგსტრუქტურა (რეკომენდებული რეალისტური ტესტისთვის)
  venv\\Scripts\\python.exe scripts\\seed_portal.py org

  # მსუბუქი დემო კონტენტი (news/video) — ორგის შემდეგ
  venv\\Scripts\\python.exe scripts\\seed_portal.py demo

ორგსტრუქტურა (org):
  • ტექნიკური:     5 ჯგუფი × (12 ოპ. + 1 უფროსი)
  • საინფორმაციო: 20 ჯგუფი × (15 ოპ. + 1 უფროსი)
  • ოფისი:        20 ჯგუფი × (8 ოპ. + 1 უფროსი)
  • ოფისი:        10 ჯგუფი × (8 ოპ., უფროსის გარეშე) → ექვემდებარებიან ოფისების საერთო უფროსს
  • + ოფისების საერთო უფროსი + portal admin/content/sysadmin
"""
from __future__ import annotations

import os
import sys
from datetime import timedelta

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from config import settings
from database import SessionLocal, engine, get_tbilisi_time
import models
from security import (
    DEFAULT_PERMISSIONS_BY_ROLE,
    get_password_hash,
)

# Optional: worktree/qa_accounts may not exist in an older Magti base checkout.
try:
    from qa_accounts import TEST_ACCOUNTS  # type: ignore
except ImportError:
    TEST_ACCOUNTS = []  # type: ignore
try:
    from qa_accounts import TEST_ACCOUNT_PASSWORD  # type: ignore
except ImportError:
    TEST_ACCOUNT_PASSWORD = "Test1234!"  # type: ignore

models.Base.metadata.create_all(bind=engine)

KEEP_EMAILS = {a["email"].lower() for a in TEST_ACCOUNTS}


def _refuse_if_production() -> None:
    """This script seeds known test credentials (TEST_ACCOUNTS, password
    Test1234! committed in TEST_LOGINS.md) — it must never touch a real
    deployment, independent of the mock-AD bypass's own is_production gate."""
    if settings.is_production:
        print(
            "REFUSING: APP_ENV=production. This script seeds known test "
            "credentials and must never run against a real database.",
            file=sys.stderr,
        )
        sys.exit(1)

# ── Georgian name pools (deterministic) ──────────────────────────────────────
_FIRST = [
    "გიორგი", "ნინო", "დავით", "მარიამ", "ლევან", "თამარ", "ირაკლი", "ანა",
    "ზურაბ", "ქეთევან", "ნიკა", "სოფიო", "ბექა", "ელენე", "გელა", "თეა",
    "ვახტანგ", "ლია", "სანდრო", "ნათია", "ოთარ", "მაია", "გოჩა", "ხატია",
    "ლუკა", "სალომე", "შოთა", "ნინუკა", "არჩილ", "თეონა",
]
_LAST = [
    "ბერიძე", "მაისურაძე", "გელაშვილი", "ლომიძე", "წერეთელი", "კვარაცხელია",
    "მამედაშვილი", "ჩხეიძე", "ფირცხალავა", "კიკნაძე", "გოგიჩაიშვილი", "თავაძე",
    "ნადირაძე", "ჯაფარიძე", "ხურციძე", "ბოლქვაძე", "სანიკიძე", "წიკლაური",
    "აბაშიძე", "გვასალია", "შენგელია", "მელაძე", "ქავთარაძე", "დოლიძე",
]


def _name(seq: int) -> str:
    return f"{_FIRST[seq % len(_FIRST)]} {_LAST[(seq // len(_FIRST) + seq) % len(_LAST)]}"


def _group_label(prefix: str, g: int) -> str:
    """Canonical department string: 'ტექნიკური — ჯგუფი 01'."""
    return f"{prefix} — ჯგუფი {g:02d}"


# ── Magti call-center topology ───────────────────────────────────────────────
ORG_TECH = {"prefix": "ტექნიკური", "code": "tech", "groups": 5, "ops": 12}
ORG_INFO = {"prefix": "საინფორმაციო", "code": "info", "groups": 20, "ops": 15}
ORG_OFFICE = {"prefix": "ოფისი", "code": "office", "groups_with_lead": 20, "groups_no_lead": 10, "ops": 8}

# Portal accounts always present with org (easy login for admin tasks)
PORTAL_ACCOUNTS = [
    {
        "email": "sysadmin@magti.ge",
        "name": "სისტემური ადმინისტრატორი",
        "role": "admin",
        "department": "All",
        "position": "System Administrator",
        "permissions": DEFAULT_PERMISSIONS_BY_ROLE.get("admin", []),
    },
    {
        "email": "admin@magti.ge",
        "name": "პორტალის ადმინი",
        "role": "admin",
        "department": "All",
        "position": "Portal Admin",
        "permissions": DEFAULT_PERMISSIONS_BY_ROLE.get("admin", []),
    },
    {
        "email": "content@magti.ge",
        "name": "კონტენტის ადმინისტრატორი",
        "role": "content_admin",
        "department": "All",
        "position": "Content Administrator",
        "permissions": DEFAULT_PERMISSIONS_BY_ROLE.get("content_admin", []),
    },
]

# pytest pollution — only these get wiped after tests (org users stay)
_TEST_POLLUTION_PREFIXES = (
    "cq_",
    "e2e_",
    "gp_",
    "factory_",
    "test_operator_",
)


def _upsert_one(db, *, email, name, role, department, position, hashed, manager_id=None) -> models.User:
    email = email.lower()
    u = db.query(models.User).filter(models.User.email == email).first()
    if not u:
        u = models.User(email=email, hashed_password=hashed)
        db.add(u)
    u.name = name
    u.role = role
    u.department = department
    u.position = position
    u.is_active = True
    u.hashed_password = hashed
    u.permissions = list(DEFAULT_PERMISSIONS_BY_ROLE.get(role, []))
    u.manager_id = manager_id
    return u


def _upsert_test_users(db) -> tuple[int, int]:
    hashed = get_password_hash(TEST_ACCOUNT_PASSWORD)
    created = updated = 0
    for acc in TEST_ACCOUNTS:
        email = acc["email"].lower()
        perms = acc.get("permissions") or DEFAULT_PERMISSIONS_BY_ROLE.get(acc["role"], [])
        user = db.query(models.User).filter(models.User.email == email).first()
        if not user:
            user = models.User(
                email=email,
                name=acc["name"],
                role=acc["role"],
                department=acc["department"],
                position=acc.get("position"),
                is_active=True,
                hashed_password=hashed,
                permissions=list(perms),
            )
            db.add(user)
            created += 1
            print(f"  + {email}")
        else:
            user.name = acc["name"]
            user.role = acc["role"]
            user.department = acc["department"]
            user.position = acc.get("position")
            user.is_active = True
            user.hashed_password = hashed
            user.permissions = list(perms)
            updated += 1
            print(f"  ~ {email}")
    db.commit()
    return created, updated


def _pick_keeper(db) -> models.User:
    for email in ("sysadmin@magti.ge", "admin@magti.ge", "admin.tech@magti.ge"):
        u = db.query(models.User).filter(models.User.email == email).first()
        if u:
            return u
    u = db.query(models.User).filter(models.User.role == "admin").first()
    if not u:
        raise RuntimeError("No admin user found")
    return u


def _delete_users_by_ids(db, extra_ids: list[int], keeper: models.User) -> None:
    if not extra_ids:
        return
    db.query(models.User).filter(models.User.manager_id.in_(extra_ids)).update(
        {models.User.manager_id: None}, synchronize_session=False
    )
    db.query(models.Article).filter(models.Article.author_id.in_(extra_ids)).update(
        {models.Article.author_id: keeper.id}, synchronize_session=False
    )
    try:
        db.query(models.News).filter(models.News.author_id.in_(extra_ids)).update(
            {models.News.author_id: keeper.id}, synchronize_session=False
        )
    except Exception:
        pass
    for model, col in (
        (getattr(models, "ArticleHistory", None), "updated_by"),
        (getattr(models, "NewsHistory", None), "updated_by"),
        (getattr(models, "AuditLog", None), "admin_id"),
        (models.ReadStatus, "user_id"),
        (models.Favorite, "user_id"),
        (models.Message, "user_id"),
        (models.Message, "sender_id"),
        (models.ArticleReadReceipt, "operator_id"),
        (getattr(models, "QuizAttempt", None), "user_id"),
        (getattr(models, "UserNote", None), "user_id"),
        (getattr(models, "SearchLog", None), "user_id"),
    ):
        if model is None:
            continue
        try:
            if col in ("updated_by", "admin_id"):
                db.query(model).filter(getattr(model, col).in_(extra_ids)).update(
                    {getattr(model, col): keeper.id}, synchronize_session=False
                )
            else:
                db.query(model).filter(getattr(model, col).in_(extra_ids)).delete(
                    synchronize_session=False
                )
        except Exception:
            pass
    db.query(models.User).filter(models.User.id.in_(extra_ids)).delete(synchronize_session=False)
    db.commit()


def _remove_extra_users(db, keep_emails: set[str]) -> int:
    keep = {e.lower() for e in keep_emails}
    extra = db.query(models.User).filter(~models.User.email.in_(list(keep))).all()
    if not extra:
        print("  (არ არის ზედმეტი იუზერი)")
        return 0
    keeper = _pick_keeper(db)
    ids = [u.id for u in extra]
    print(f"  იშლება {len(ids)} იუზერი...")
    _delete_users_by_ids(db, ids, keeper)
    return len(ids)


def _remove_test_pollution(db) -> int:
    """Delete only pytest junk users; leave org + TEST_LOGINS alone."""
    q = db.query(models.User)
    extras = []
    for u in q.all():
        local = u.email.split("@")[0].lower()
        if any(local.startswith(p) for p in _TEST_POLLUTION_PREFIXES):
            extras.append(u)
    if not extras:
        return 0
    keeper = _pick_keeper(db)
    ids = [u.id for u in extras]
    _delete_users_by_ids(db, ids, keeper)
    return len(ids)


def purge_extra_users() -> int:
    """After pytest: remove pollution only (keeps org if present)."""
    db = SessionLocal()
    try:
        return _remove_test_pollution(db)
    finally:
        db.close()


def purge_to_test_logins_only() -> int:
    """Hard clean: only TEST_ACCOUNTS remain."""
    db = SessionLocal()
    try:
        _upsert_test_users(db)
        return _remove_extra_users(db, KEEP_EMAILS)
    finally:
        db.close()


def cmd_users() -> None:
    _refuse_if_production()
    print("=" * 50)
    print("seed_portal: მხოლოდ TEST_LOGINS (~20)")
    print("=" * 50)
    db = SessionLocal()
    try:
        print("1) ტესტ-ანგარიშები...")
        c, u = _upsert_test_users(db)
        print(f"   created={c} updated={u}")
        print("2) ზედმეტი იუზერების წაშლა...")
        n = _remove_extra_users(db, KEEP_EMAILS)
        print(f"   წაშლილია: {n}")
        left = db.query(models.User).count()
        print(f"\nმზადაა. მომხმარებლები: {left}")
        print("ორგსტრუქტურისთვის:  python scripts/seed_portal.py org")
    finally:
        db.close()


def cmd_org() -> None:
    """Full Magti call-center org chart for realistic testing."""
    _refuse_if_production()
    print("=" * 60)
    print("seed_portal: კომპანიის ორგსტრუქტურა")
    print("=" * 60)
    print("  ტექნიკური:     5 × (12 ოპ + 1 უფროსი)")
    print("  საინფორმაციო: 20 × (15 ოპ + 1 უფროსი)")
    print("  ოფისი:        20 × (8 ოპ + 1 უფროსი)")
    print("  ოფისი:        10 × (8 ოპ, უფროსის გარეშე) → office.head")
    print("  + ოფისების საერთო უფროსი + portal admins")
    print("=" * 60)

    hashed = get_password_hash(TEST_ACCOUNT_PASSWORD)
    db = SessionLocal()
    keep: set[str] = set()
    seq = 0
    stats = {"managers": 0, "operators": 0, "portal": 0}

    try:
        # ── Portal logins ────────────────────────────────────────────────
        print("1) Portal admin/content...")
        for acc in PORTAL_ACCOUNTS:
            _upsert_one(
                db,
                email=acc["email"],
                name=acc["name"],
                role=acc["role"],
                department=acc["department"],
                position=acc["position"],
                hashed=hashed,
            )
            keep.add(acc["email"].lower())
            stats["portal"] += 1
        db.flush()

        # ── Office overall head (before office groups) ───────────────────
        print("2) ოფისების საერთო უფროსი...")
        office_head = _upsert_one(
            db,
            email="office.head@magti.ge",
            name="ოფისების საერთო უფროსი",
            role="manager",
            department="ოფისი",
            position="ოფისების დეპარტამენტის უფროსი",
            hashed=hashed,
            manager_id=None,
        )
        keep.add("office.head@magti.ge")
        stats["managers"] += 1
        db.flush()
        office_head_id = office_head.id

        # ── Tech groups ──────────────────────────────────────────────────
        print("3) ტექნიკური ჯგუფები...")
        for g in range(1, ORG_TECH["groups"] + 1):
            dept = _group_label(ORG_TECH["prefix"], g)
            lead_email = f"tech.g{g:02d}.lead@magti.ge"
            lead = _upsert_one(
                db,
                email=lead_email,
                name=_name(seq),
                role="manager",
                department=dept,
                position="ჯგუფის უფროსი",
                hashed=hashed,
            )
            seq += 1
            keep.add(lead_email)
            stats["managers"] += 1
            db.flush()
            for e in range(1, ORG_TECH["ops"] + 1):
                em = f"tech.g{g:02d}.op{e:02d}@magti.ge"
                _upsert_one(
                    db,
                    email=em,
                    name=_name(seq),
                    role="operator",
                    department=dept,
                    position="ოპერატორი",
                    hashed=hashed,
                    manager_id=lead.id,
                )
                seq += 1
                keep.add(em)
                stats["operators"] += 1
            if g % 5 == 0 or g == ORG_TECH["groups"]:
                print(f"   tech group {g}/{ORG_TECH['groups']}")

        # ── Info groups ──────────────────────────────────────────────────
        print("4) საინფორმაციო ჯგუფები...")
        for g in range(1, ORG_INFO["groups"] + 1):
            dept = _group_label(ORG_INFO["prefix"], g)
            lead_email = f"info.g{g:02d}.lead@magti.ge"
            lead = _upsert_one(
                db,
                email=lead_email,
                name=_name(seq),
                role="manager",
                department=dept,
                position="ჯგუფის უფროსი",
                hashed=hashed,
            )
            seq += 1
            keep.add(lead_email)
            stats["managers"] += 1
            db.flush()
            for e in range(1, ORG_INFO["ops"] + 1):
                em = f"info.g{g:02d}.op{e:02d}@magti.ge"
                _upsert_one(
                    db,
                    email=em,
                    name=_name(seq),
                    role="operator",
                    department=dept,
                    position="ოპერატორი",
                    hashed=hashed,
                    manager_id=lead.id,
                )
                seq += 1
                keep.add(em)
                stats["operators"] += 1
            if g % 5 == 0 or g == ORG_INFO["groups"]:
                print(f"   info group {g}/{ORG_INFO['groups']}")

        # ── Office groups WITH lead (01–20) ──────────────────────────────
        print("5) ოფისის ჯგუფები (უფროსით)...")
        n_with = ORG_OFFICE["groups_with_lead"]
        for g in range(1, n_with + 1):
            dept = _group_label(ORG_OFFICE["prefix"], g)
            lead_email = f"office.g{g:02d}.lead@magti.ge"
            lead = _upsert_one(
                db,
                email=lead_email,
                name=_name(seq),
                role="manager",
                department=dept,
                position="ჯგუფის უფროსი",
                hashed=hashed,
                manager_id=office_head_id,  # ექვემდებარება საერთო უფროსს
            )
            seq += 1
            keep.add(lead_email)
            stats["managers"] += 1
            db.flush()
            for e in range(1, ORG_OFFICE["ops"] + 1):
                em = f"office.g{g:02d}.op{e:02d}@magti.ge"
                _upsert_one(
                    db,
                    email=em,
                    name=_name(seq),
                    role="operator",
                    department=dept,
                    position="ოპერატორი",
                    hashed=hashed,
                    manager_id=lead.id,
                )
                seq += 1
                keep.add(em)
                stats["operators"] += 1
            if g % 5 == 0 or g == n_with:
                print(f"   office (lead) group {g}/{n_with}")

        # ── Office groups WITHOUT lead (21–30) → office.head ─────────────
        print("6) ოფისის ჯგუფები (უფროსის გარეშე → office.head)...")
        n_no = ORG_OFFICE["groups_no_lead"]
        start = n_with + 1
        for i, g in enumerate(range(start, start + n_no), start=1):
            dept = _group_label(ORG_OFFICE["prefix"], g)
            for e in range(1, ORG_OFFICE["ops"] + 1):
                em = f"office.g{g:02d}.op{e:02d}@magti.ge"
                _upsert_one(
                    db,
                    email=em,
                    name=_name(seq),
                    role="operator",
                    department=dept,
                    position="ოპერატორი",
                    hashed=hashed,
                    manager_id=office_head_id,  # პირდაპირ საერთო უფროსს
                )
                seq += 1
                keep.add(em)
                stats["operators"] += 1
            if i % 5 == 0 or i == n_no:
                print(f"   office (no-lead) group {g} ({i}/{n_no})")

        db.commit()

        print("7) სხვა იუზერების გასუფთავება (რჩება მხოლოდ org + portal)...")
        removed = _remove_extra_users(db, keep)

        total = db.query(models.User).count()
        # Verify team-stats will show all 3 departments
        print("8) გუნდის სტატისტიკის შემოწმება (3 დეპარტამენტი)...")
        try:
            from routers.stats import build_department_stats
            dash = build_department_stats(db)
            by_name = {d["name"]: d for d in dash["departments"]}
            for expected, eg in (
                ("ტექნიკური", 5),
                ("საინფორმაციო", 20),
                ("ოფისი", 30),
            ):
                d = by_name.get(expected)
                if not d:
                    print(f"   ✗ {expected}: არ არის API-ში!")
                else:
                    print(
                        f"   ✓ {expected}: {d['group_count']} ჯგუფი, "
                        f"{d['member_count']} ოპერატორი"
                    )
                    if d["group_count"] != eg:
                        print(f"     (მოსალოდნელი ჯგუფები: {eg})")
            if len(dash["departments"]) != 3:
                print(f"   ✗ departments count={len(dash['departments'])} (უნდა იყოს 3)")
        except Exception as exc:
            print(f"   (dashboard check skipped: {exc})")

        print()
        print("=" * 60)
        print("მზადაა!")
        print(f"  portal admins:     {stats['portal']}")
        print(f"  managers (უფროსები): {stats['managers']}")
        print(f"  operators:         {stats['operators']}")
        print(f"  სულ users:         {total}")
        print(f"  წაშლილი ძველი:     {removed}")
        print()
        print("გუნდის სტატისტიკაში უნდა ჩანდეს 3 დეპარტამენტი:")
        print("  ტექნიკური (5) · საინფორმაციო (20) · ოფისი (30)")
        print("  თუ მხოლოდ 2 ჩანს → გადატვირთე სერვერი + Ctrl+F5")
        print()
        print("შესვლა (პაროლი ტესტ-რეჟიმში: ნებისმიერი, ან Test1234!):")
        print("  sysadmin@magti.ge          — სრული ადმინი")
        print("  content@magti.ge           — კონტენტი")
        print("  office.head@magti.ge       — ოფისების საერთო უფროსი")
        print("  tech.g01.lead@magti.ge     — ტექ. ჯგუფი 01 უფროსი")
        print("  tech.g01.op01@magti.ge     — ტექ. ჯგუფი 01 ოპერატორი")
        print("  info.g01.lead@magti.ge     — საინფო ჯგუფი 01 უფროსი")
        print("  office.g01.lead@magti.ge   — ოფისი ჯგუფი 01 უფროსი")
        print("  office.g21.op01@magti.ge   — ოფისი უპირატესი (უფროსის გარეშე)")
        print()
        print("დეტალები: TEST_LOGINS.md")
        print("=" * 60)
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def cmd_demo() -> None:
    _refuse_if_production()
    print("=" * 50)
    print("seed_portal: მსუბუქი დემო კონტენტი (org/users უკვე უნდა იყოს)")
    print("=" * 50)
    db = SessionLocal()
    try:
        # Ensure at least portal accounts exist
        hashed = get_password_hash(TEST_ACCOUNT_PASSWORD)
        for acc in PORTAL_ACCOUNTS:
            _upsert_one(
                db,
                email=acc["email"],
                name=acc["name"],
                role=acc["role"],
                department=acc["department"],
                position=acc["position"],
                hashed=hashed,
            )
        db.commit()
        keeper = _pick_keeper(db)
        now = get_tbilisi_time()

        if db.query(models.VideoInstruction).count() == 0:
            db.add_all([
                models.VideoInstruction(
                    title="PIN კოდის მართვა (დემო)",
                    video_url="https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0",
                    category="ტექნიკური",
                    target_department="All",
                    views_count=0,
                ),
                models.VideoInstruction(
                    title="სინქრონიზაცია (დემო)",
                    video_url="https://www.youtube.com/embed/9bZkp7q19f0?rel=0",
                    category="ტექნიკური",
                    target_department="All",
                    views_count=0,
                ),
            ])
            print("  + videos")
        if db.query(models.News).count() < 2:
            db.add_all([
                models.News(
                    title="დემო: სისტემის განახლება",
                    content="<p>დემო სიახლე.</p>",
                    target_department="All",
                    is_draft=False,
                    author_id=keeper.id,
                ),
                models.News(
                    title="დემო: ტექნიკური შეტყობინება",
                    content="<p>დემო სიახლე ტექნიკური.</p>",
                    target_department="ტექნიკური",
                    is_draft=False,
                    author_id=keeper.id,
                ),
            ])
            print("  + news")
        if db.query(models.RequiredReading).count() < 2:
            arts = (
                db.query(models.Article)
                .filter(models.Article.status == "published", models.Article.is_draft == False)  # noqa: E712
                .limit(3)
                .all()
            )
            for i, art in enumerate(arts):
                db.add(
                    models.RequiredReading(
                        item_type="article",
                        item_id=art.id,
                        target_department="All",
                        due_date=now + timedelta(days=(3 if i else -2)),
                        priority="high" if i == 0 else "normal",
                    )
                )
            if arts:
                print(f"  + {len(arts)} required readings")
        db.commit()
        print("დემო კონტენტი მზადაა.")
    finally:
        db.close()


def main() -> None:
    mode = (sys.argv[1] if len(sys.argv) > 1 else "org").strip().lower()
    if mode in ("users", "user", "test", "minimal"):
        cmd_users()
    elif mode in ("org", "company", "structure", "default", ""):
        cmd_org()
    elif mode in ("demo", "content"):
        cmd_demo()
    else:
        print("გამოყენება:")
        print("  python scripts/seed_portal.py org      # კომპანიის ორგსტრუქტურა (default)")
        print("  python scripts/seed_portal.py users    # მხოლოდ ~20 TEST_LOGINS")
        print("  python scripts/seed_portal.py demo     # მსუბუქი news/video")
        sys.exit(1)


if __name__ == "__main__":
    main()
