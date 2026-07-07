"""
compliance_alerts.py — daily batch job: flags operators whose reading
compliance has fallen below the critical threshold and messages them
(+ CC their manager, if set). Run by the `compliance-alerts` docker-compose
service on a 24h sleep loop, mirroring backup.py's convention.

Does NOT import main.py — that would boot the FastAPI app (Redis broker
connection, logging handlers, route registration) as an import-time side
effect, for the same reason backup.py avoids it. The compliance formula is
re-implemented here from the same query shape as main.py's compute_compliance()
/ _reading_progress() / _get_read_counts_by_user_dept() (main.py:3571-3678),
kept in sync by hand with the constants below.
"""
import sys

from sqlalchemy import func

from database import SessionLocal, get_tbilisi_time
import models

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


def _read_counts_by_user_dept(db, user_ids):
    if not user_ids:
        return {}
    rows = (
        db.query(
            models.ReadStatus.user_id,
            models.RequiredReading.target_department,
            func.count(models.ReadStatus.id),
        )
        .join(
            models.RequiredReading,
            models.ReadStatus.required_reading_id == models.RequiredReading.id,
        )
        .filter(models.ReadStatus.status == "read")
        .filter(models.ReadStatus.user_id.in_(user_ids))
        .group_by(models.ReadStatus.user_id, models.RequiredReading.target_department)
        .all()
    )
    return {(uid, dept): cnt for uid, dept, cnt in rows}


def compute_and_alert(db) -> int:
    users = db.query(models.User).filter(
        models.User.is_active == True,  # noqa: E712
        models.User.role.notin_(MANAGEMENT_ROLES),
    ).all()

    readings_by_dept = dict(
        db.query(models.RequiredReading.target_department, func.count(models.RequiredReading.id))
        .group_by(models.RequiredReading.target_department)
        .all()
    )
    all_required = readings_by_dept.get("All", 0)
    read_map = _read_counts_by_user_dept(db, [u.id for u in users])

    alerted = 0
    for user in users:
        if not user.department or user.department == "All":
            required = all_required
            read = read_map.get((user.id, "All"), 0)
        else:
            required = all_required + readings_by_dept.get(user.department, 0)
            read = read_map.get((user.id, "All"), 0) + read_map.get((user.id, user.department), 0)

        if required == 0:
            continue
        pct = round((read / required) * 100)
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
