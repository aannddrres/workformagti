"""
One-shot RBAC reset: purges all existing users and rebuilds the Magti Call
Center topology (1 admin, 15 group leaders/managers, 150 operators = 166 total).

Destructive: this script backs up magti_portal.db via backup.create_backup()
before touching anything, then:

  1. Deletes personal/activity rows.
  2. Deletes every old User row.
  3. Creates the new System Admin.
  4. Reassigns authored content to the new admin.
  5. Inserts 15 managers (5 tech, 10 info) and 10 operators per manager (150).

Run manually (from the repo root):
    venv\\Scripts\\python.exe scripts\\seed_perfect_users.py
"""
import sys
# Ensure the project root is importable when run from the repo root
sys.path.append('.')
from database import SessionLocal
from security import get_password_hash
import models

DEFAULT_PASSWORD = "password"  # same convention as seed.py / seed_test_users.py

TECH_GROUP_COUNT = 5
INFO_GROUP_COUNT = 10
OPERATORS_PER_MANAGER = 10
CONTENT_CREATOR_COUNT = 5  # of the 15 managers, how many get content-creator flags

# Content-creation in this app is gated by `User.role in ("admin", "content_admin")`
# everywhere in main.py (verified: e.g. main.py:826, :857, :1309 ...). The
# `permissions` JSON column only layers *granular* sub-permissions on top of
# content_admin/admin (security.py:332) — it does not by itself grant base
# content-creation access to a "manager". Flipping a manager's role to
# content_admin instead would remove them from the role=="manager"
# group-leaders dropdown we just fixed, breaking the "15 groups" requirement.
# So: keep all 15 as role="manager" (full Group Leader / dropdown integrity)
# and flag content-creator status via `permissions` as the task's own
# fallback wording allows ("...depending on how the app registers content
# creators"). This is data-true but currently inert for actual write access —
# flagged again in the final report.
CONTENT_CREATOR_PERMISSIONS = ["articles.create", "articles.publish", "news.create", "news.publish"]


def reassign_content_to(db, new_owner_id: int):
    db.query(models.Article).update({models.Article.author_id: new_owner_id}, synchronize_session=False)
    db.query(models.ArticleHistory).update({models.ArticleHistory.updated_by: new_owner_id}, synchronize_session=False)
    db.query(models.NewsHistory).update({models.NewsHistory.updated_by: new_owner_id}, synchronize_session=False)
    db.query(models.Message).update(
        {models.Message.sender_id: new_owner_id, models.Message.user_id: new_owner_id},
        synchronize_session=False,
    )


def delete_personal_activity_rows(db):
    db.query(models.AuditLog).delete(synchronize_session=False)
    db.query(models.Favorite).delete(synchronize_session=False)
    db.query(models.ReadStatus).delete(synchronize_session=False)
    db.query(models.SearchLog).delete(synchronize_session=False)
    db.query(models.UserNote).delete(synchronize_session=False)
    db.query(models.KnowledgeFeedback).delete(synchronize_session=False)


def main():
    print("Backing up magti_portal.db before any destructive change...")
    import backup as backup_module
    backup_module.create_backup()

    db = SessionLocal()
    try:
        hashed_pw = get_password_hash(DEFAULT_PASSWORD)

        print("Deleting personal/activity rows with no surviving owner "
              "(audit_logs, favorites, read_statuses, search_logs, user_notes, knowledge_feedback)...")
        delete_personal_activity_rows(db)
        db.commit()

        print("Deleting all pre-existing users (frees up emails like admin@magti.ge for reuse; "
              "SQLite enforces no FK constraints here, so articles/messages/history rows are "
              "momentarily dangling until the reassignment step right after)...")
        deleted = db.query(models.User).delete(synchronize_session=False)
        db.commit()
        print(f"  -> deleted {deleted} old user rows")

        print("Creating new System Admin...")
        admin = models.User(
            name="სისტემური ადმინი",
            email="admin@magti.ge",
            department="All",
            position="System Administrator",
            role="admin",
            is_active=True,
            hashed_password=hashed_pw,
        )
        db.add(admin)
        db.commit()
        print(f"  -> admin id={admin.id}")

        print("Reassigning authored content (articles/history/news_history/messages) to new admin...")
        reassign_content_to(db, admin.id)
        db.commit()

        print(f"Creating 15 group leaders (managers) and {15 * OPERATORS_PER_MANAGER} operators...")
        created_managers = []
        for i in range(1, TECH_GROUP_COUNT + 1):
            m = models.User(
                name=f"ტექნიკური ჯგუფის ხელმძღვანელი {i}",
                email=f"manager_tech{i}@magti.ge",
                department="ტექნიკური",
                position="Group Leader / Supervisor",
                role="manager",
                is_active=True,
                hashed_password=hashed_pw,
            )
            db.add(m)
            created_managers.append(("ტექნიკური", m))
        for i in range(1, INFO_GROUP_COUNT + 1):
            m = models.User(
                name=f"საინფორმაციო ჯგუფის ხელმძღვანელი {i}",
                email=f"manager_info{i}@magti.ge",
                department="საინფორმაციო",
                position="Group Leader / Supervisor",
                role="manager",
                is_active=True,
                hashed_password=hashed_pw,
            )
            db.add(m)
            created_managers.append(("საინფორმაციო", m))
        db.flush()

        for _, m in created_managers[:CONTENT_CREATOR_COUNT]:
            m.permissions = list(CONTENT_CREATOR_PERMISSIONS)

        for idx, (dept, manager) in enumerate(created_managers, start=1):
            label = "tech" if dept == "ტექნიკური" else "info"
            local_index = idx if dept == "ტექნიკური" else idx - TECH_GROUP_COUNT
            for op_num in range(1, OPERATORS_PER_MANAGER + 1):
                op = models.User(
                    name=f"{dept} ოპერატორი {local_index}-{op_num}",
                    email=f"operator_{label}{local_index}_{op_num}@magti.ge",
                    department=dept,
                    position="Operator",
                    role="operator",
                    is_active=True,
                    hashed_password=hashed_pw,
                    manager_id=manager.id,
                )
                db.add(op)

        db.commit()

        total = db.query(models.User).count()
        print(f"\nDone. Total users now in magti_portal.db: {total}")
        print("All accounts use the password:", DEFAULT_PASSWORD)
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


if __name__ == "__main__":
    main()
