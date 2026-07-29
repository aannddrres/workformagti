import os
import sys
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import security
# Mock before importing main/app
security.require_content_creator = lambda perm: security.get_current_admin_user
security.PERM_ARTICLES_CREATE = "articles.create"
security.PERM_NEWS_CREATE = "news.create"

from database import get_db, engine
from sqlalchemy import text, event
from main import app as monolith_app
from routers.stats import _reading_progress
import compliance_utils
import models

models.Base.metadata.create_all(bind=engine)

@event.listens_for(engine, "checkin")
def reset_sqlite_pragma(dbapi_connection, connection_record):
    try:
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA foreign_keys=OFF")
        cursor.close()
    except Exception:
        pass

@pytest.fixture
def db_session():
    db = next(get_db())
    try:
        yield db
    finally:
        db.close()

@pytest.fixture
def test_data(db_session):
    # Ensure category exists
    cat = db_session.query(models.Category).filter(models.Category.id == 999).first()
    if not cat:
        cat = models.Category(id=999, name="Compliance Category", is_active=True)
        db_session.add(cat)
        db_session.commit()
        db_session.refresh(cat)

    # Create admin
    admin = db_session.query(models.User).filter(models.User.email == "com_admin@magti.ge").first()
    if not admin:
        admin = models.User(
            email="com_admin@magti.ge",
            name="Compliance Admin",
            role="admin",
            department="HQ",
            is_active=True
        )
        db_session.add(admin)

    # Create operator
    operator = db_session.query(models.User).filter(models.User.email == "com_op@magti.ge").first()
    if not operator:
        operator = models.User(
            email="com_op@magti.ge",
            name="Compliance Operator",
            role="operator",
            department="Support",
            is_active=True
        )
        db_session.add(operator)
    else:
        operator.department = "Support"
        db_session.add(operator)

    db_session.commit()
    db_session.refresh(admin)
    db_session.refresh(operator)

    # Create article
    article = models.Article(
        title="Compliance Rules 2026",
        content="Read carefully.",
        category_id=cat.id,
        status="published",
        is_draft=False,
        version=1,
        author_id=admin.id
    )
    db_session.add(article)
    db_session.commit()
    db_session.refresh(article)

    # Add target department
    dept = models.ArticleTargetDepartment(department="Support")
    article.target_department_rows.append(dept)
    db_session.commit()
    db_session.refresh(article)

    yield {"admin": admin, "operator": operator, "article": article, "category": cat}

    # Clean up
    # Delete read receipts
    db_session.query(models.ArticleReadReceipt).filter(
        models.ArticleReadReceipt.operator_id == operator.id
    ).delete()
    db_session.query(models.ArticleTargetDepartment).filter(
        models.ArticleTargetDepartment.article_id == article.id
    ).delete()
    db_session.query(models.Article).filter(models.Article.id == article.id).delete()
    # Sentinel users must never persist into the shared dev DB.
    db_session.query(models.User).filter(
        models.User.email.in_(["com_admin@magti.ge", "com_op@magti.ge"])
    ).delete(synchronize_session=False)
    db_session.commit()

def test_read_receipt_flow(test_data, db_session):
    article = test_data["article"]
    admin = test_data["admin"]
    operator = test_data["operator"]

    # Monolith app configuration
    monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
    monolith_app.dependency_overrides[security.get_current_user] = lambda: operator

    try:
        with TestClient(monolith_app) as client:
            # 1. Fetch versions as Admin
            monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
            res_versions = client.get(f"/api/articles/{article.id}/versions")
            assert res_versions.status_code == 200
            versions = res_versions.json()
            assert len(versions) >= 1
            assert versions[0]["version"] == 1
            assert versions[0]["title"] == "Compliance Rules 2026"

            # 2. Get my read status as operator (should be false)
            monolith_app.dependency_overrides[security.get_current_user] = lambda: operator
            res_me = client.get(f"/api/articles/{article.id}/read-receipt/me")
            assert res_me.status_code == 200
            assert res_me.json()["has_read"] is False

            # 3. Create read receipt as operator
            res_post = client.post(f"/api/articles/{article.id}/read-receipt")
            assert res_post.status_code == 200
            assert res_post.json()["status"] == "success"
            assert res_post.json()["article_version"] == 1

            # 4. Get my status again (should be true)
            res_me2 = client.get(f"/api/articles/{article.id}/read-receipt/me")
            assert res_me2.status_code == 200
            assert res_me2.json()["has_read"] is True

            # 5. Admin fetches read-receipt grid
            monolith_app.dependency_overrides[security.get_current_admin_user] = lambda: admin
            res_grid = client.get(f"/api/articles/{article.id}/read-receipts")
            assert res_grid.status_code == 200
            grid_data = res_grid.json()
            assert grid_data["article_title"] == "Compliance Rules 2026"
            
            # Check operator row
            rows = grid_data["receipts"]
            op_row = [r for r in rows if r["operator_id"] == operator.id][0]
            assert op_row["has_read"] is True
            assert op_row["operator_name"] == "Compliance Operator"
            assert op_row["operator_email"] == "com_op@magti.ge"
            assert op_row["department"] == "Support"

            # 6. Test Position/Department Tampering: Operator transferred to "Billing"
            operator.department = "Billing"
            db_session.add(operator)
            db_session.commit()

            # Grid should STILL show department as "Support" since it was snapshot
            res_grid2 = client.get(f"/api/articles/{article.id}/read-receipts")
            grid_data2 = res_grid2.json()
            op_row2 = [r for r in grid_data2["receipts"] if r["operator_id"] == operator.id][0]
            assert op_row2["department"] == "Support"

            # 7. Test Version Filtering
            operator.department = "Support"
            db_session.add(operator)
            db_session.commit()

            res_grid_v2 = client.get(f"/api/articles/{article.id}/read-receipts?version=2")
            assert res_grid_v2.status_code == 200
            grid_data_v2 = res_grid_v2.json()
            op_row_v2 = [r for r in grid_data_v2["receipts"] if r["operator_id"] == operator.id][0]
            assert op_row_v2["has_read"] is False

            # 8. Test Hard-Delete of Operator (User)
            connection = db_session.connection()
            connection.execute(text("PRAGMA foreign_keys=ON"))
            db_session.query(models.AuditLog).filter(models.AuditLog.admin_id == operator.id).delete()
            db_session.delete(operator)
            db_session.flush()
            connection.execute(text("PRAGMA foreign_keys=OFF"))
            db_session.commit()

            # Receipt should still exist with operator_id as None
            receipt = db_session.query(models.ArticleReadReceipt).filter(
                models.ArticleReadReceipt.article_id == article.id
            ).first()
            assert receipt is not None
            db_session.refresh(receipt)
            assert receipt.operator_id is None
            assert receipt.operator_name_snapshot == "Compliance Operator"
            assert receipt.operator_email_snapshot == "com_op@magti.ge"

            # Grid fetch should still work and include the orphaned/detached row
            res_grid_orphaned = client.get(f"/api/articles/{article.id}/read-receipts")
            assert res_grid_orphaned.status_code == 200
            grid_data_orphaned = res_grid_orphaned.json()
            orphaned_row = [r for r in grid_data_orphaned["receipts"] if r["operator_id"] is None][0]
            assert orphaned_row["operator_name"] == "Compliance Operator"
            assert orphaned_row["has_read"] is True

            # 9. Test Hard-Delete of Article
            connection = db_session.connection()
            connection.execute(text("PRAGMA foreign_keys=ON"))
            db_session.delete(article)
            db_session.flush()
            connection.execute(text("PRAGMA foreign_keys=OFF"))
            db_session.commit()

            # Receipt should still exist with both article_id and operator_id as None
            db_session.refresh(receipt)
            assert receipt.article_id is None
            assert receipt.operator_id is None
            assert receipt.article_title_snapshot == "Compliance Rules 2026"
    finally:
        monolith_app.dependency_overrides.clear()
        try:
            db_session.rollback()
        except Exception:
            pass
        try:
            db_session.execute(text("PRAGMA foreign_keys=OFF"))
            db_session.commit()
        except Exception:
            pass


def test_zero_required_readings_agree_across_implementations():
    """compliance_utils.py (used by the standalone compliance_alerts.py cron)
    and main.py's own _reading_progress() (used by the live dashboard) must
    keep agreeing on the zero-required-readings edge case — they used to
    disagree (100% vs 0%), which meant the daily nag cron and the dashboard
    could classify the same user's compliance differently. Not a live DB
    test on purpose: this only needs an in-memory user and empty maps."""
    user = models.User(id=999999, department="NoSuchDepartment")

    main_percentage = _reading_progress(user, 0, {}, {})[2]
    utils_percentage = compliance_utils.get_compliance_percentage(user, {}, {})

    assert main_percentage == 0
    assert utils_percentage == 0
    assert main_percentage == utils_percentage


def test_group_suffixed_user_counts_department_reading_in_both_implementations():
    """A user in a sub-group ("ტექნიკური — ჯგუფი 03") must be counted for a
    reading targeted at the parent department ("ტექნიკური") — the same rule
    _dept_matches already applies for visibility/my-readings. Both
    _reading_progress (live dashboard) and compliance_utils.get_compliance_percentage
    (compliance_alerts.py's daily cron) must agree, or an operator can see
    "100% complete" on their own page while the dashboard/cron report 0%."""
    user = models.User(id=999999, department="ტექნიკური — ჯგუფი 03")
    readings_by_dept = {"ტექნიკური": 2}
    read_map = {(999999, "ტექნიკური"): 1}

    required, read, main_percentage = _reading_progress(user, 0, readings_by_dept, read_map)
    utils_required, utils_read, utils_percentage = compliance_utils.get_compliance_data_tuple(
        user, readings_by_dept, read_map
    )

    assert required == 2
    assert read == 1
    assert main_percentage == 50
    assert (utils_required, utils_read, utils_percentage) == (required, read, main_percentage)
