from sqlalchemy.orm import Session
from sqlalchemy import func
import models
from typing import Optional


def get_total_required_readings_by_dept(db: Session) -> dict:
    """Fetches the total number of required readings grouped by target department."""
    return dict(
        db.query(models.RequiredReading.target_department, func.count(models.RequiredReading.id))
        .group_by(models.RequiredReading.target_department)
        .all()
    )


def get_read_counts_by_user_dept(
    db: Session, user_ids: Optional[list[int]] = None
) -> dict[tuple[int, str], int]:
    """Grouped "read"-status counts keyed by (user_id, RequiredReading.target_department)."""
    if user_ids is not None and not user_ids:
        return {}
    q = (
        db.query(
            models.ReadStatus.user_id,
            models.RequiredReading.target_department,
            func.count(models.ReadStatus.id),
        )
        .join(models.RequiredReading, models.ReadStatus.required_reading_id == models.RequiredReading.id)
        .filter(models.ReadStatus.status == "read")
    )
    if user_ids is not None:
        q = q.filter(models.ReadStatus.user_id.in_(user_ids))
    rows = q.group_by(models.ReadStatus.user_id, models.RequiredReading.target_department).all()
    return {(uid, dept): cnt for uid, dept, cnt in rows}


def get_compliance_percentage(user: models.User, readings_by_dept: dict, read_map: dict) -> int:
    """
    Calculates the compliance percentage for a single user.
    This centralized utility function is used by both the main application and background jobs
    to ensure the calculation logic is consistent.

    Args:
        db: The database session.
        user: The user object to calculate compliance for.
        readings_by_dept: A dictionary mapping department names to their total required reading counts.
        read_map: A dictionary mapping (user_id, department) tuples to their read counts.

    Returns:
        The user's compliance percentage, rounded to the nearest integer.
    """
    all_required = readings_by_dept.get("All", 0)

    if not user.department or user.department == "All":
        required = all_required
        read = read_map.get((user.id, "All"), 0)
    else:
        required = all_required + readings_by_dept.get(user.department, 0)
        read = read_map.get((user.id, "All"), 0) + read_map.get((user.id, user.department), 0)

    if required == 0:
        # Matches main.py's _reading_progress() and this module's own
        # get_compliance_data_tuple() below — both treat "nothing required"
        # as 0%, not 100%. This function used to disagree with both (returned
        # 100), which meant compliance_alerts.py's daily nag cron and the
        # live dashboard could silently classify the same user differently.
        return 0

    return round((read / required) * 100)


def get_compliance_data_tuple(user: models.User, readings_by_dept: dict, read_map: dict) -> tuple[int, int, int]:
    """
    Calculates compliance data (required, read, percentage) for a single user.
    If nothing is required, returns (0, 0, 0).
    """
    all_required = readings_by_dept.get("All", 0)

    if not user.department or user.department == "All":
        required_count = all_required
        read_count = read_map.get((user.id, "All"), 0)
    else:
        required_count = all_required + readings_by_dept.get(user.department, 0)
        read_count = read_map.get((user.id, "All"), 0) + read_map.get((user.id, user.department), 0)

    if required_count == 0:
        return 0, 0, 0

    percentage = round((read_count / required_count) * 100)
    return required_count, read_count, percentage