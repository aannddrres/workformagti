import re

from sqlalchemy.orm import Session
from sqlalchemy import func
import models
from typing import Optional

# Shared by routers/stats.py and compliance_alerts.py — one definition,
# not two copies that can silently drift out of sync.
#
# MANAGEMENT_ROLES: excluded from required-reading target-audience
# calculations. DB has admin, content_admin, manager, operator — only
# operators are the intended audience; management roles inflate the
# denominator otherwise.
#
# CRITICAL_THRESHOLD: compliance percentage below which an operator is
# flagged "critical".
MANAGEMENT_ROLES = ("admin", "content_admin", "manager")
CRITICAL_THRESHOLD = 30

# Regex that matches any dash variant (hyphen-minus, en-dash, em-dash) with
# optional surrounding whitespace. Handles messy data-entry from production.
_DASH_RE = re.compile(r'\s*[-–—]\s*')  # - – —

# Em dash used as the canonical delimiter when *reconstructing* department strings.
_DEPT_GROUP_DELIM = "—"

# Regex for collapsing runs of whitespace / tabs into a single space.
_WS_RE = re.compile(r'[ \t]+')

# Trailing whitespace/dash run — used to clean up a dash that sits right
# before the "ჯგუფი" keyword (e.g. "ტექნიკური - ჯგუფი 01") once the keyword
# anchor has already located the split point, so it isn't left stuck to the
# end of the prefix.
_TRAILING_DASH_RE = re.compile(r'[\s\-–—]+$')


def _normalize_dept(raw):
    """Collapse whitespace and strip, but leave dashes untouched."""
    return _WS_RE.sub(' ', (raw or '').strip())


def _split_dept_group(raw_department):
    """Return (department_prefix, group_label) from a raw users.department value.

    Handles em-dash, en-dash, ASCII hyphen, missing spaces, extra whitespace,
    and the "ჯგუფი" keyword as a fallback delimiter when no dash is present.

    "საინფორმაციო სამსახური — ჯგუფი 01" -> ("საინფორმაციო სამსახური", "ჯგუფი 01")
    "ტექნიკური - ჯგუფი 01"              -> ("ტექნიკური",             "ჯგუფი 01")
    "ტექნიკური დეპარტამენტი ჯგუფი 01"   -> ("ტექნიკური დეპარტამენტი", "ჯგუფი 01")
    """
    raw = _normalize_dept(raw_department)
    if not raw:
        return '', ''

    # 1. Canonical em-dash first — the expected format, and immune to a
    #    hyphen embedded earlier in the department name itself (e.g.
    #    "IT-Support — ჯგუფი 01" must not split on the "IT-Support" hyphen).
    if _DEPT_GROUP_DELIM in raw:
        prefix, _, suffix = raw.partition(_DEPT_GROUP_DELIM)
        prefix, suffix = prefix.strip(), suffix.strip()
        return prefix, (suffix or prefix)

    # 2. "ჯგუფი" keyword anchor — also immune to embedded hyphens, so tried
    #    before the generic dash regex.
    kw_idx = raw.find('ჯგუფი')
    if kw_idx > 0:
        prefix = _TRAILING_DASH_RE.sub('', raw[:kw_idx])
        suffix = raw[kw_idx:].strip()
        return prefix, suffix

    # 3. Last resort: any dash variant. Only reached when there's neither an
    #    em-dash nor a "ჯგუფი" keyword to anchor on.
    parts = _DASH_RE.split(raw, maxsplit=1)
    if len(parts) == 2:
        prefix, suffix = parts[0].strip(), parts[1].strip()
        return prefix, (suffix or prefix)

    return raw, raw


def _dept_matches(user_dept: str, targets) -> bool:
    """True if user_dept matches any target, with prefix support for sub-groups.
    e.g. target 'ტექნიკური' matches user dept 'ტექნიკური — ჯგუფი 03'."""
    for t in targets:
        if t == "All":
            return True
        if user_dept == t or (t and _split_dept_group(user_dept)[0] == t):
            return True
    return False


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


def get_compliance_data_tuple(user: models.User, readings_by_dept: dict, read_map: dict) -> tuple[int, int, int]:
    """
    Calculates compliance data (required, read, percentage) for a single user.
    If nothing is required, returns (0, 0, 0).

    Eligibility uses the same prefix-aware rule as _dept_matches (a reading
    targeted at 'ტექნიკური' applies to a user in 'ტექნიკური — ჯგუფი 03'), so
    this agrees with what the user's own "my readings" list shows them —
    previously this looked up readings_by_dept/read_map by the user's exact
    department string only, silently dropping group-targeted readings for
    any group-suffixed user.
    """
    all_required = readings_by_dept.get("All", 0)

    if not user.department or user.department == "All":
        required_count = all_required
        read_count = read_map.get((user.id, "All"), 0)
    else:
        dept_keys = {user.department, _split_dept_group(user.department)[0]}
        required_count = all_required + sum(readings_by_dept.get(k, 0) for k in dept_keys)
        read_count = read_map.get((user.id, "All"), 0) + sum(
            read_map.get((user.id, k), 0) for k in dept_keys
        )

    if required_count == 0:
        return 0, 0, 0

    percentage = round((read_count / required_count) * 100)
    return required_count, read_count, percentage


def get_compliance_percentage(user: models.User, readings_by_dept: dict, read_map: dict) -> int:
    """
    Calculates the compliance percentage for a single user.
    This centralized utility function is used by both the main application and background jobs
    to ensure the calculation logic is consistent.

    Returns 0 when nothing is required — matches get_compliance_data_tuple()
    above and routers/stats.py's _reading_progress(), so compliance_alerts.py's
    daily nag cron and the live dashboard can no longer classify the same
    user differently.
    """
    return get_compliance_data_tuple(user, readings_by_dept, read_map)[2]