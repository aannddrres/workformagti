"""Two-phase presentation/demo seeder.

--phase 1: reset to a clean 0%-compliance baseline (users, org hierarchy,
           article targeting, RequiredReading rows). No receipts written.
--phase 2: inject read-receipt activity live during the demo so compliance
           dashboards visibly update.

Department targeting bug fix: main.py's eligibility/visibility checks
(_get_eligible_operators, _assert_article_visible, etc.) do an EXACT string
match between User.department and ArticleTargetDepartment.department. The
org hierarchy uses Georgian group labels ("{prefix} — ჯგუფი NN") while
articles historically targeted English bucket sentinels ("Informational",
"Support", "All"), so the two never matched. This script fixes that for
articles 102-105 by expanding the legacy buckets into the literal group
labels (expand_bucket_to_groups), entirely within this seeder — no main.py
changes.
"""
import argparse
import random
import sys
from datetime import timedelta

from database import SessionLocal, get_tbilisi_time
from security import get_password_hash, DEFAULT_PERMISSIONS_BY_ROLE
import models
import backup

PASSWORD = "Magti2026!"

FIRST_NAMES = ["გიორგი", "დავით", "ალექსანდრე", "ლუკა", "ნიკოლოზ", "ირაკლი", "ლევან", "ზურაბ", "ბექა", "საბა", "შოთა", "ვაჟა", "თეიმურაზ", "არჩილ", "მალხაზ", "ვახტანგ", "ილარიონი", "რატი", "ანდრია", "კოტე", "ნინო", "მარიამ", "თამარ", "ანა", "ელენე", "ქეთევან", "სალომე", "მაია", "ნათია", "რუსუდან", "ეკატერინე", "ლიკა", "თეონა", "ნათელა", "ხატია", "ნინუკა", "სოფო", "მანანა", "ციცინო", "ლალი"]
SURNAMES = ["კაპანაძე", "გელაშვილი", "მაისურაძე", "ბერიძე", "კვარაცხელია", "მებონია", "შენგელია", "დიასამიძე", "ლომიძე", "აბაშიძე", "ნოზაძე", "ხუციშვილი", "ტაბატაძე", "მჭედლიშვილი", "ჯაფარიძე", "ღლონტი", "მესხი", "ქავთარაძე", "კალანდაძე", "ჩხეიძე", "ელიზბარაშვილი", "გორგაძე", "წიკლაური", "თოდუა", "ჭანტურია", "კოპალიანი", "გვენეტაძე", "ბაქრაძე", "ახვლედიანი", "მიქელაძე", "ასათიანი", "ამირეჯიბი", "გურგენიძე", "ონიანი", "ფაჩულია", "გაჩეჩილაძე", "ცინცაძე", "მელიქიძე", "სანიკიძე", "კალაძე"]
NAME_POOL_SIZE = len(FIRST_NAMES) * len(SURNAMES)  # 1600 unique combinations


def full_name(index: int) -> str:
    """Deterministic, collision-free name for a given person index.
    (index*7) % NAME_POOL_SIZE is a full permutation of 0..1599 (gcd(7,1600)=1),
    so any index range up to 1600 yields zero duplicate names.
    """
    pair = (index * 7) % NAME_POOL_SIZE
    return f"{FIRST_NAMES[pair % len(FIRST_NAMES)]} {SURNAMES[pair // len(FIRST_NAMES)]}"


DEPT_GROUPS = [("საინფორმაციო სამსახური", "info", 10), ("ტექნიკური სამსახური", "tech", 5)]


def group_label(prefix: str, g: int) -> str:
    return f"{prefix} — ჯგუფი {g:02d}"


INFO_GROUP_LABELS = [group_label(DEPT_GROUPS[0][0], g) for g in range(1, 11)]
TECH_GROUP_LABELS = [group_label(DEPT_GROUPS[1][0], g) for g in range(1, 6)]
ALL_GROUP_LABELS = INFO_GROUP_LABELS + TECH_GROUP_LABELS  # 15 labels

# 152 operators: 10 info groups -> 102 (two groups of 11, eight of 10),
# 5 tech groups -> 50 (10 each).
INFO_GROUP_COUNTS = [11, 11, 10, 10, 10, 10, 10, 10, 10, 10]
TECH_GROUP_COUNTS = [10, 10, 10, 10, 10]
assert sum(INFO_GROUP_COUNTS) == 102
assert sum(TECH_GROUP_COUNTS) == 50

# Position-title flavor within the info cohort (department stays the valid
# Georgian group label regardless — "Billing"/"VIP Concierge" never get
# written to User.department, only to the descriptive `position` field).
INFO_POSITION_SPLIT = (
    ["Support Specialist"] * 45
    + ["Billing Specialist"] * 35
    + ["VIP Concierge Specialist"] * 22
)
assert len(INFO_POSITION_SPLIT) == 102

NAMED_ACCOUNTS = [
    dict(email="sysadmin@magticom.ge", name="სისტემური ადმინისტრატორი", department="All",
         position="System Administrator", role="admin"),
]

CONTENT_CREATORS = [
    dict(email="creator1@magticom.ge", name="კონტენტ კრეატორი — საინფორმაციო", department="All",
         position="Content Administrator", role="content_admin"),
    dict(email="creator2@magticom.ge", name="კონტენტ კრეატორი — ტექნიკური", department="All",
         position="Content Administrator", role="content_admin"),
    dict(email="creator3@magticom.ge", name="კორპორატიული რედაქტორი", department="All",
         position="Content Administrator", role="content_admin"),
]


def _group_code_and_index(label: str):
    if label in INFO_GROUP_LABELS:
        return "info", INFO_GROUP_LABELS.index(label) + 1
    return "tech", TECH_GROUP_LABELS.index(label) + 1


# 1 Group Leader per group (15 total) — indices 0..14 of the name pool.
GROUP_LEADERS = []
for _i, _label in enumerate(ALL_GROUP_LABELS):
    _code, _g = _group_code_and_index(_label)
    GROUP_LEADERS.append(dict(
        email=f"teamlead.{_code}.g{_g:02d}@magticom.ge",
        name=full_name(_i),
        department=_label,
        position="ჯგუფის უფროსი",
        role="manager",
    ))
assert len(GROUP_LEADERS) == 15
PERSON_IDX_OFFSET = len(GROUP_LEADERS)  # operators continue the name pool from here

TARGET_ARTICLE_IDS = (102, 103, 104, 105)

ARTICLE_TARGET_BUCKETS = {
    102: ["All"],
    103: ["Informational", "Support"],
    104: ["Informational"],
    105: ["Informational"],
}

# Only the Georgian structural hierarchy + the "All" sentinel are valid going
# forward. "Sales", "IT Security", "Content Creation" (and the old "Informational"/
# "Support" buckets) are legacy English strings being scrubbed from the DB.
VALID_DEPARTMENT_STRINGS = set(ALL_GROUP_LABELS) | {"All"}

# Legacy English department bucket -> Georgian structural group set it must be
# rewritten to. Anything not listed here (e.g. a stray "Content Creation" row)
# is left untouched and will surface as a ⚠ WARNING via audit_department_strings.
LEGACY_DEPARTMENT_REMAP = {
    "Informational": INFO_GROUP_LABELS,
    "Sales": INFO_GROUP_LABELS,
    "Support": TECH_GROUP_LABELS,
    "IT Security": TECH_GROUP_LABELS,
}


def expand_bucket_to_groups(bucket: str) -> list:
    if bucket == "All":
        return ["All"]
    if bucket in LEGACY_DEPARTMENT_REMAP:
        return list(LEGACY_DEPARTMENT_REMAP[bucket])
    raise ValueError(f"Unknown legacy bucket: {bucket!r}")


def audit_department_strings(planned_writes: dict) -> None:
    print("=" * 70)
    print("DEPARTMENT STRING AUDIT (pre-write, non-blocking)")
    for source, values in planned_writes.items():
        for v in sorted(set(values)):
            if v in VALID_DEPARTMENT_STRINGS:
                print(f"  [{source}] {v!r} -> OK")
            else:
                print(f"  ⚠ WARNING: {v!r} (source={source}) is not in the known-valid department set.")
    print("=" * 70)


def upsert_user_by_email(db, email, name, department, position, role):
    u = db.query(models.User).filter(models.User.email == email).first()
    if not u:
        u = models.User(email=email, hashed_password=get_password_hash(PASSWORD))
        db.add(u)
    u.name = name
    u.department = department
    u.position = position
    u.role = role
    u.permissions = DEFAULT_PERMISSIONS_BY_ROLE.get(role, [])
    u.is_active = True
    return u


def get_eligible_operators(db, article) -> list:
    """Read-only mirror of main.py:2531-2563 _get_eligible_operators.
    Reimplemented (not imported) so this script stays standalone and doesn't
    trigger FastAPI app construction. Keep in sync if that function changes.
    """
    if article.is_draft:
        return []
    now = get_tbilisi_time()
    is_visible = (
        article.status == "published"
        or (
            article.status == "scheduled"
            and article.published_at is not None
            and article.published_at <= now
        )
    )
    if not is_visible:
        return []

    target_depts = article.target_departments
    query = db.query(models.User).filter(
        models.User.is_active == True,
        models.User.role.notin_(["admin", "content_admin"]),
    )
    if "All" not in target_depts:
        query = query.filter(models.User.department.in_(target_depts))

    users = query.all()
    eligible = []
    for u in users:
        if u.role == "tech_info" and not article.visible_to_tech_info:
            continue
        if u.role == "service_center" and not article.visible_to_service_center:
            continue
        eligible.append(u)
    return eligible


def phase1(db):
    audit_department_strings({
        "users.named_accounts": [a["department"] for a in NAMED_ACCOUNTS],
        "users.content_creators": [a["department"] for a in CONTENT_CREATORS],
        "users.group_leaders": [a["department"] for a in GROUP_LEADERS],
        "users.operator_groups": ALL_GROUP_LABELS,
        "article_target_departments.102": expand_bucket_to_groups("All"),
        "article_target_departments.103": (
            expand_bucket_to_groups("Informational") + expand_bucket_to_groups("Support")
        ),
        "article_target_departments.104": expand_bucket_to_groups("Informational"),
        "article_target_departments.105": expand_bucket_to_groups("Informational"),
        "required_readings": ["All"],
    })

    articles = {
        a.id: a
        for a in db.query(models.Article).filter(models.Article.id.in_(TARGET_ARTICLE_IDS)).all()
    }
    missing = set(TARGET_ARTICLE_IDS) - set(articles)
    if missing:
        print(f"FATAL: Target presentation articles not found in database: {sorted(missing)}")
        sys.exit(1)

    print("Backing up magti_portal.db + uploads before truncation...")
    backup.create_backup()

    print("Truncating AuditLog, ArticleReadReceipt, ReadStatus, User...")
    for model in (models.AuditLog, models.ArticleReadReceipt, models.ReadStatus, models.User):
        try:
            db.query(model).delete(synchronize_session=False)
        except Exception as e:
            print(f"  (non-fatal) could not clear {model.__name__}: {e}")
    db.commit()

    print("Upserting 1 admin + 15 group leaders + 3 content creators + 152 operators (171 total)...")
    sysadmin = None
    for spec in NAMED_ACCOUNTS:
        sysadmin = upsert_user_by_email(db, **spec)
    for spec in GROUP_LEADERS:
        upsert_user_by_email(db, **spec)
    for spec in CONTENT_CREATORS:
        upsert_user_by_email(db, **spec)
    db.flush()

    person_idx = PERSON_IDX_OFFSET
    info_pos_idx = 0
    for label, count in zip(INFO_GROUP_LABELS, INFO_GROUP_COUNTS):
        g = INFO_GROUP_LABELS.index(label) + 1
        for e in range(1, count + 1):
            email = f"info.g{g:02d}.op{e:02d}@magticom.ge"
            position = INFO_POSITION_SPLIT[info_pos_idx]
            info_pos_idx += 1
            upsert_user_by_email(
                db, email=email, name=full_name(person_idx),
                department=label, position=position, role="operator",
            )
            person_idx += 1

    for label, count in zip(TECH_GROUP_LABELS, TECH_GROUP_COUNTS):
        g = TECH_GROUP_LABELS.index(label) + 1
        for e in range(1, count + 1):
            email = f"tech.g{g:02d}.op{e:02d}@magticom.ge"
            upsert_user_by_email(
                db, email=email, name=full_name(person_idx),
                department=label, position="Technical Operator", role="operator",
            )
            person_idx += 1
    db.commit()
    assert person_idx == PERSON_IDX_OFFSET + 152

    print("Injecting 10 admin audit-log rows (last 10 days)...")
    admin_log_entries = [
        ("category_create", "category", 1, "შეიქმნა კატეგორია: როუმინგი"),
        ("category_create", "category", 4, "შეიქმნა კატეგორია: ტექნიკური"),
        ("news_publish", "news", 1, "გამოქვეყნდა სიახლე #1"),
        ("news_publish", "news", 2, "გამოქვეყნდა სიახლე #2"),
        ("news_publish", "news", 3, "გამოქვეყნდა სიახლე #3"),
        ("draft_clear", "article", 102, "გასუფთავდა დრაფტის სტატუსი სტატიაზე #102"),
        ("draft_clear", "article", 104, "გასუფთავდა დრაფტის სტატუსი სტატიაზე #104"),
        ("category_create", "category", 8, "შეიქმნა კატეგორია: საინფორმაციო (Desk)"),
        ("news_publish", "news", 4, "გამოქვეყნდა სიახლე #4"),
        ("draft_clear", "article", 105, "გასუფთავდა დრაფტის სტატუსი სტატიაზე #105"),
    ]
    now_for_logs = get_tbilisi_time()
    for day_offset, (action, item_type, item_id, details) in zip(range(10, 0, -1), admin_log_entries):
        db.add(models.AuditLog(
            admin_id=sysadmin.id, action=action, item_type=item_type, item_id=item_id,
            timestamp=now_for_logs - timedelta(days=day_offset), category="admin", details=details,
        ))
    db.commit()

    print("Clearing legacy is_draft blockages on articles 102/104...")
    for aid in (102, 104):
        articles[aid].is_draft = False
    db.commit()

    print("Rewriting ArticleTargetDepartment rows for 102-105...")
    for aid in TARGET_ARTICLE_IDS:
        db.query(models.ArticleTargetDepartment).filter(
            models.ArticleTargetDepartment.article_id == aid
        ).delete()
        expanded = []
        for bucket in ARTICLE_TARGET_BUCKETS[aid]:
            expanded.extend(expand_bucket_to_groups(bucket))
        for dept in dict.fromkeys(expanded):
            db.add(models.ArticleTargetDepartment(article_id=aid, department=dept))
    db.commit()

    print("Remapping legacy English department buckets to Georgian hierarchy "
          "for every other article...")
    other_rows = db.query(models.ArticleTargetDepartment).filter(
        models.ArticleTargetDepartment.article_id.notin_(TARGET_ARTICLE_IDS)
    ).all()
    by_article = {}
    for row in other_rows:
        by_article.setdefault(row.article_id, set()).add(row.department)

    remap_audit = {}
    remapped_count = 0
    for article_id, depts in by_article.items():
        legacy_present = depts & set(LEGACY_DEPARTMENT_REMAP)
        if not legacy_present:
            continue
        keep = depts - legacy_present
        expanded = set()
        for legacy in legacy_present:
            expanded.update(LEGACY_DEPARTMENT_REMAP[legacy])
        new_depts = expanded - keep
        db.query(models.ArticleTargetDepartment).filter(
            models.ArticleTargetDepartment.article_id == article_id,
            models.ArticleTargetDepartment.department.in_(legacy_present),
        ).delete(synchronize_session=False)
        for dept in new_depts:
            db.add(models.ArticleTargetDepartment(article_id=article_id, department=dept))
        remap_audit[f"article_target_departments.{article_id}"] = sorted(keep | new_depts)
        remapped_count += 1
    db.commit()
    if remap_audit:
        audit_department_strings(remap_audit)
    print(f"  Remapped legacy department targeting on {remapped_count} articles.")

    print("Ensuring RequiredReading rows exist for 102-105...")
    existing_rr = {
        r.item_id: r
        for r in db.query(models.RequiredReading)
        .filter(
            models.RequiredReading.item_type == "article",
            models.RequiredReading.item_id.in_(TARGET_ARTICLE_IDS),
        )
        .all()
    }
    now = get_tbilisi_time()
    for aid in TARGET_ARTICLE_IDS:
        if aid in existing_rr:
            continue  # article 102's row (id=22, due 2026-06-26) stays untouched
        db.add(models.RequiredReading(
            item_type="article", item_id=aid, target_department="All",
            due_date=now + timedelta(days=3),
            priority="high" if aid == 103 else "normal",
        ))
    db.commit()

    print("Phase 1 complete: clean 0%-compliance baseline ready. No receipts written.")


def _random_hourly_timestamp(start, end):
    total_hours = max(int((end - start).total_seconds() // 3600), 0)
    offset_hours = random.randint(0, total_hours) if total_hours > 0 else 0
    return start + timedelta(hours=offset_hours)


def _write_receipt(db, article, version, user, read_at):
    receipt = db.query(models.ArticleReadReceipt).filter(
        models.ArticleReadReceipt.article_id == article.id,
        models.ArticleReadReceipt.article_version == version,
        models.ArticleReadReceipt.operator_id == user.id,
    ).first()
    if not receipt:
        receipt = models.ArticleReadReceipt(
            article_id=article.id, operator_id=user.id, article_version=version,
        )
        db.add(receipt)
    receipt.article_title_snapshot = article.title
    receipt.operator_name_snapshot = user.name
    receipt.operator_email_snapshot = user.email
    receipt.operator_department_snapshot = user.department
    receipt.read_at = read_at
    db.add(models.AuditLog(
        admin_id=user.id, action="article_read", item_type="article", item_id=article.id,
        timestamp=read_at, category="compliance",
        details=f"მომხმარებელი გაეცნო სტატიას: {article.title}",
    ))


def phase2(db):
    random.seed(42)  # stable, reproducible "organic" variance across reruns

    live_departments = [
        row[0] for row in db.query(models.User.department).distinct().all() if row[0]
    ]
    audit_department_strings({"users.live_departments": live_departments})

    all_articles = db.query(models.Article).all()
    now = get_tbilisi_time()

    for article in all_articles:
        article_id = article.id

        required = db.query(models.RequiredReading).filter(
            models.RequiredReading.item_type == "article",
            models.RequiredReading.item_id == article_id,
        ).first()
        if required:
            due_date = required.due_date
            ontime_window = (due_date - timedelta(days=5), due_date)
            late_window = (due_date + timedelta(days=1), due_date + timedelta(days=3))
        else:
            # Legacy article with no compliance deadline: both bands fall back
            # to "sometime in the last 7 days" — there's no due date to be late
            # against, so on-time/late only controls receipt probability here.
            due_date = now
            ontime_window = (now - timedelta(days=7), now)
            late_window = (now - timedelta(days=7), now)

        eligible = get_eligible_operators(db, article)
        if not eligible:
            print(f"  SKIP article {article_id}: 0 eligible operators.")
            continue

        # Clean slate per article (both versions) so reruns never leave stale rows.
        db.query(models.ArticleReadReceipt).filter(
            models.ArticleReadReceipt.article_id == article_id
        ).delete(synchronize_session=False)
        db.query(models.AuditLog).filter(
            models.AuditLog.item_type == "article", models.AuditLog.item_id == article_id,
            models.AuditLog.action == "article_read",
        ).delete(synchronize_session=False)

        eligible = sorted(eligible, key=lambda u: u.id)
        version = article.version

        # Task 4: multi-version reading drift. Only meaningful for an article
        # that genuinely has a prior version on record (102 is at version 1 in
        # this dataset, so the drift cohort is applied to whichever target
        # article actually carries version > 1 instead of a hardcoded id).
        drift_users = []
        if version > 1:
            drift_users = random.sample(eligible, min(15, len(eligible)))
        drift_ids = {u.id for u in drift_users}
        sim_pool = [u for u in eligible if u.id not in drift_ids]

        unread_count = ontime_count = late_count = 0
        for user in sim_pool:
            roll = random.random()
            if roll < 0.65:
                ontime_count += 1
                read_at = _random_hourly_timestamp(*ontime_window)
            elif roll < 0.85:
                late_count += 1
                read_at = _random_hourly_timestamp(*late_window)
            else:
                unread_count += 1
                continue
            _write_receipt(db, article, version, user, read_at)

        for user in drift_users:
            stale_read_at = _random_hourly_timestamp(due_date - timedelta(days=10), due_date - timedelta(days=6))
            _write_receipt(db, article, 1, user, stale_read_at)

        db.commit()
        total = len(eligible)
        print(f"  Article {article_id} ({article.title}): total={total} "
              f"unread={unread_count} on_time={ontime_count} late={late_count} "
              f"version_drift={len(drift_users)}")

    print("Phase 2 complete.")
    print("\U0001F4A1 REMINDER: Trigger manual refresh sync button on the client dashboard interface to view the live updates!")


def main():
    parser = argparse.ArgumentParser(description="Seed demo data for the sales/compliance presentation.")
    parser.add_argument("--phase", type=int, required=True, choices=[1, 2])
    args = parser.parse_args()

    db = SessionLocal()
    try:
        if args.phase == 1:
            phase1(db)
        else:
            phase2(db)
    except Exception as e:
        db.rollback()
        print(f"Database transaction error: {e}")
        sys.exit(1)
    finally:
        db.close()


if __name__ == "__main__":
    main()
