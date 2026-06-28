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
    dict(email="teamlead@magticom.ge", name="ჯგუფის უფროსი",
         department=TECH_GROUP_LABELS[0],  # remapped anchor, not "Technical Operators"
         position="Group Leader", role="manager"),
    dict(email="creator@magticom.ge", name="კონტენტ კრეატორი", department="All",
         position="Content Administrator", role="content_admin"),
]

TARGET_ARTICLE_IDS = (102, 103, 104, 105)
RECEIPT_ARTICLE_IDS = (102, 103, 104)  # 105 deliberately stays unread

ARTICLE_TARGET_BUCKETS = {
    102: ["All"],
    103: ["Informational", "Support"],
    104: ["Informational"],
    105: ["Informational"],
}

VALID_DEPARTMENT_STRINGS = set(ALL_GROUP_LABELS) | {
    "All", "Informational", "Support", "IT Security", "Content Creation",
}


def expand_bucket_to_groups(bucket: str) -> list:
    if bucket == "All":
        return ["All"]
    if bucket == "Informational":
        return list(INFO_GROUP_LABELS)
    if bucket == "Support":
        return list(TECH_GROUP_LABELS)
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

    print("Upserting named accounts + 152 structured operators...")
    for spec in NAMED_ACCOUNTS:
        upsert_user_by_email(db, **spec)
    db.flush()

    info_pos_idx = 0
    for label, count in zip(INFO_GROUP_LABELS, INFO_GROUP_COUNTS):
        g = INFO_GROUP_LABELS.index(label) + 1
        for e in range(1, count + 1):
            email = f"info.g{g:02d}.op{e:02d}@magticom.ge"
            position = INFO_POSITION_SPLIT[info_pos_idx]
            info_pos_idx += 1
            upsert_user_by_email(
                db, email=email, name=f"საინფორმაციო ოპერატორი {info_pos_idx}",
                department=label, position=position, role="operator",
            )

    for label, count in zip(TECH_GROUP_LABELS, TECH_GROUP_COUNTS):
        g = TECH_GROUP_LABELS.index(label) + 1
        for e in range(1, count + 1):
            email = f"tech.g{g:02d}.op{e:02d}@magticom.ge"
            upsert_user_by_email(
                db, email=email, name=f"ტექნიკური ოპერატორი {g:02d}-{e:02d}",
                department=label, position="Technical Operator", role="operator",
            )
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


def phase2(db):
    live_departments = [
        row[0] for row in db.query(models.User.department).distinct().all() if row[0]
    ]
    audit_department_strings({"users.live_departments": live_departments})

    for article_id in RECEIPT_ARTICLE_IDS:
        article = db.query(models.Article).filter(models.Article.id == article_id).first()
        if not article:
            print(f"  SKIP article {article_id}: not found.")
            continue

        required = db.query(models.RequiredReading).filter(
            models.RequiredReading.item_type == "article",
            models.RequiredReading.item_id == article_id,
        ).first()
        if not required:
            print(f"  SKIP article {article_id}: no RequiredReading row (run --phase 1 first).")
            continue
        due_date = required.due_date

        eligible = get_eligible_operators(db, article)
        if not eligible:
            print(f"  SKIP article {article_id}: 0 eligible operators.")
            continue

        version = article.version
        random.shuffle(eligible)
        total = len(eligible)
        unread_limit = int(total * 0.30)
        ontime_limit = unread_limit + int(total * 0.50)

        unread_count = ontime_count = late_count = 0
        for idx, user in enumerate(eligible):
            if idx < unread_limit:
                unread_count += 1
                db.query(models.ArticleReadReceipt).filter(
                    models.ArticleReadReceipt.article_id == article_id,
                    models.ArticleReadReceipt.article_version == version,
                    models.ArticleReadReceipt.operator_id == user.id,
                ).delete()
                continue

            if idx < ontime_limit:
                ontime_count += 1
                read_at = due_date - timedelta(days=4)
            else:
                late_count += 1
                read_at = due_date + timedelta(days=2)

            receipt = db.query(models.ArticleReadReceipt).filter(
                models.ArticleReadReceipt.article_id == article_id,
                models.ArticleReadReceipt.article_version == version,
                models.ArticleReadReceipt.operator_id == user.id,
            ).first()
            if not receipt:
                receipt = models.ArticleReadReceipt(
                    article_id=article_id, operator_id=user.id, article_version=version,
                )
                db.add(receipt)
            receipt.article_title_snapshot = article.title
            receipt.operator_name_snapshot = user.name
            receipt.operator_email_snapshot = user.email
            receipt.operator_department_snapshot = user.department
            receipt.read_at = read_at

        db.commit()
        print(f"  Article {article_id} ({article.title}): total={total} "
              f"unread={unread_count} on_time={ontime_count} late={late_count}")

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
