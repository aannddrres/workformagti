"""
compliance_alerts.py — daily batch job: flags operators whose reading
compliance has fallen below the critical threshold and messages them
(+ CC their manager, if set). Run by the `compliance-alerts` docker-compose
service on a 24h sleep loop, mirroring backup.py's convention.
"""
import sys

from sqlalchemy import func

from database import SessionLocal, get_tbilisi_time
import models
from compliance_utils import get_compliance_percentage, get_total_required_readings_by_dept, get_read_counts_by_user_dept

# Mirrors main.py:3883/3889 — keep in sync if those change.
MANAGEMENT_ROLES = ("admin", "content_admin", "manager")
CRITICAL_THRESHOLD = 30

ALERT_MESSAGE_TEMPLATE = (
    "თქვენი სავალდებულო მასალების წაკითხვის მაჩვენებელი {pct}%-ია, რაც კრიტიკულ "
    "ზღვარს (< {threshold}%) ჩამორჩება. გთხოვთ, გაეცნოთ დარჩენილ მასალებს."
)
MANAGER_CC_TEMPLATE = (
    "თქვენი გუნდის წევრს, {name}-ს, სავალდებულო მასალების წაკითხვის მაჩვენებელი "
    "{pct}%-ია (კრიტიკულ ზღვარზე დაბალი)."
)


def compute_and_alert(db) -> int:
    users = db.query(models.User).filter(
        models.User.is_active == True,  # noqa: E712
        models.User.role.notin_(MANAGEMENT_ROLES),
    ).all()

    readings_by_dept = get_total_required_readings_by_dept(db)
    read_map = get_read_counts_by_user_dept(db, [u.id for u in users])

    alerted = 0
    for user in users:
        # Use the centralized utility function to calculate percentage
        pct = get_compliance_percentage(user, readings_by_dept, read_map)

        if pct >= CRITICAL_THRESHOLD:
            continue

        db.add(models.Message(
            user_id=user.id,
            sender_id=None,  # None = system-generated
            content=ALERT_MESSAGE_TEMPLATE.format(pct=pct, threshold=CRITICAL_THRESHOLD),
        ))
        if user.manager_id:
            db.add(models.Message(
                user_id=user.manager_id,
                sender_id=None,
                content=MANAGER_CC_TEMPLATE.format(name=user.name, pct=pct),
            ))
        alerted += 1

    db.commit()
    print(f"[{get_tbilisi_time()}] compliance_alerts: {alerted} operator(s) alerted "
          f"(threshold={CRITICAL_THRESHOLD}%).")
    return alerted


if __name__ == "__main__":
    session = SessionLocal()
    try:
        compute_and_alert(session)
    finally:
        session.close()
    sys.exit(0)
