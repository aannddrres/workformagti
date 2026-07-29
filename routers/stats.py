"""Statistics/dashboard routes and the department/compliance aggregation
helpers they share — Phase 12 of the main.py monolith split.

This is where the widely-lazy-imported cross-domain helpers
(_MANAGEMENT_ROLES, compute_compliance, DEPARTMENT_WHITELIST,
_match_department_bucket) finally get a real home — routers/compliance.py,
platform.py, exports.py, and news.py all reached these via a temporary
`import main` lazy import, now repointed to this module as part of this
same phase.

_split_dept_group/_dept_matches/_normalize_dept moved to compliance_utils.py
(imported below) so the department-eligibility rule has exactly one home,
shared with the standalone compliance_alerts.py cron — see that module's
docstring.

OTHER_DEPARTMENT_LABEL and _DASHBOARD_EXCLUDED_ROLE were dropped here —
confirmed zero references anywhere in the repo before this phase.
"""
import asyncio
import json
import logging
from datetime import datetime, timedelta
from typing import Optional

import redis.asyncio as redis_async
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import desc, func
from sqlalchemy.orm import Session

import models
import schemas
import security
from compliance_utils import (
    MANAGEMENT_ROLES as _MANAGEMENT_ROLES,
    CRITICAL_THRESHOLD as _CRITICAL_THRESHOLD,
    _DEPT_GROUP_DELIM,
    _split_dept_group,
)
from database import get_db, get_tbilisi_time
from state import broker

router = APIRouter(tags=["stats"])
logger = logging.getLogger("magti")

# ── Best-effort Redis cache for historical (non-realtime) stats ────────────
# These endpoints run as sync `def` (threadpool); we reuse the broker's event
# loop to drive the async redis client, gated on broker._use_redis. EVERY redis
# call is wrapped so a Redis outage degrades to a live DB query — never a 500.
_STATS_CACHE_TTL = 300


def _stats_cache_get(key: str):
    """Return the cached JSON payload for `key`, or None on miss / any error."""
    if not broker._use_redis or broker._main_loop is None:
        return None

    async def _get():
        r = redis_async.from_url(
            broker.redis_url, decode_responses=True, socket_connect_timeout=0.5
        )
        try:
            return await r.get(key)
        finally:
            await r.aclose()

    try:
        raw = asyncio.run_coroutine_threadsafe(_get(), broker._main_loop).result(timeout=1.0)
        return json.loads(raw) if raw else None
    except Exception as e:
        logger.warning("stats cache GET failed (%s): %s", key, e)
        return None


def _stats_cache_set(key: str, value, ttl: int = _STATS_CACHE_TTL) -> None:
    """Store `value` as a JSON string under `key` with a TTL. Never raises."""
    if not broker._use_redis or broker._main_loop is None:
        return

    async def _set():
        r = redis_async.from_url(
            broker.redis_url, decode_responses=True, socket_connect_timeout=0.5
        )
        try:
            await r.set(key, json.dumps(value, ensure_ascii=False, default=str), ex=ttl)
        finally:
            await r.aclose()

    try:
        asyncio.run_coroutine_threadsafe(_set(), broker._main_loop).result(timeout=1.0)
    except Exception as e:
        logger.warning("stats cache SET failed (%s): %s", key, e)


@router.get("/api/statistics/popular-searches", response_model=list[schemas.PopularSearchResponse])
def get_popular_searches(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieves the top 10 most popular search terms across the organization.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        A list of PopularSearchResponse schemas.
    """
    normalized_term = func.lower(func.trim(models.SearchLog.search_term))
    results = db.query(
        normalized_term.label("search_term"),
        func.count(models.SearchLog.id).label("count")
    ).filter(models.SearchLog.has_results == True).group_by(normalized_term).order_by(desc("count")).limit(10).all()

    return [{"search_term": r.search_term, "count": r.count} for r in results]


@router.get("/api/statistics/failed-searches", response_model=list[schemas.PopularSearchResponse])
def get_failed_searches(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieves the top 10 search terms that returned no results.

    Useful for identifying knowledge gaps in the database.
    """
    normalized_term = func.lower(func.trim(models.SearchLog.search_term))
    results = db.query(
        normalized_term.label("search_term"),
        func.count(models.SearchLog.id).label("count")
    ).filter(models.SearchLog.has_results == False).group_by(normalized_term).order_by(desc("count")).limit(10).all()

    return [{"search_term": r.search_term, "count": r.count} for r in results]


@router.get("/api/statistics/compliance", response_model=schemas.ComplianceStatsResponse)
def get_compliance_statistics(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Computes overall compliance statistics (read/unread ratio, top read articles).

    Employs optimized counts to avoid N+1 queries.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        A ComplianceStatsResponse schema.
    """
    cached = _stats_cache_get("stats:compliance")
    if cached is not None:
        return cached
    # Single shared formula (compute_compliance) for both numerator and
    # denominator — replaces the bespoke active_by_dept/total_assignments math
    # so this can no longer diverge from the dashboard/exports.
    _records = compute_compliance(db)
    read_count = sum(r["read_count"] for r in _records)
    total_assignments = sum(r["required_count"] for r in _records)

    if total_assignments > 0:
        read_percentage = round((read_count / total_assignments) * 100, 2)
        unread_percentage = round(100 - read_percentage, 2)
    else:
        read_percentage = 0.0
        unread_percentage = 100.0

    # Discover the top 5 most read articles across the organization (operators only).
    # Aggregate on Article.id only (not *all columns*) so Postgres never groups /
    # hashes the large `content` TEXT; then fetch the full rows for those ids.
    top_id_rows = db.query(
        models.Article.id,
        func.count(models.ReadStatus.id).label("read_count"),
    ).join(
        models.RequiredReading, models.RequiredReading.item_id == models.Article.id
    ).join(
        models.ReadStatus, models.ReadStatus.required_reading_id == models.RequiredReading.id
    ).join(
        models.User, models.ReadStatus.user_id == models.User.id
    ).filter(
        models.User.is_active == True,
        models.User.role.notin_(_MANAGEMENT_ROLES),
        models.RequiredReading.item_type == "article",
        models.ReadStatus.status == "read",
        # Articles with no row in article_target_departments fail ArticleResponse
        # validation (target_departments must be non-empty) and 500 the whole
        # endpoint — exclude them rather than crash on bad/legacy data.
        models.Article.target_department_rows.any()
    ).group_by(models.Article.id).order_by(
        desc(func.count(models.ReadStatus.id))
    ).limit(5).all()

    top_ids = [row.id for row in top_id_rows]
    articles_by_id = {
        a.id: a
        for a in db.query(models.Article).filter(models.Article.id.in_(top_ids)).all()
    }
    # Re-sort to match the descending read_count order from the grouped query.
    top_articles = [articles_by_id[i] for i in top_ids if i in articles_by_id]

    result = {
        "read_percentage": read_percentage,
        "unread_percentage": unread_percentage,
        "top_articles": top_articles
    }
    # Serialize ORM articles via the response model so the cached payload is
    # JSON-safe; guarded so a serialize/redis hiccup never breaks the response.
    try:
        payload = {
            "read_percentage": read_percentage,
            "unread_percentage": unread_percentage,
            "top_articles": [
                schemas.ArticleResponse.model_validate(a, from_attributes=True).model_dump(mode="json")
                for a in top_articles
            ],
        }
        _stats_cache_set("stats:compliance", payload)
    except Exception as e:
        logger.warning("stats cache serialize failed (compliance): %s", e)
    return result


def _reading_progress(user, all_required, readings_by_dept, read_map):
    """Compute (required_count, read_count, percentage) for one user from
    pre-aggregated maps — no per-user query, so callers avoid the N+1.

    Applicable readings = those targeting "All" plus those targeting the
    user's own department, matched prefix-aware via _split_dept_group (a
    reading targeted at "ტექნიკური" applies to a user in "ტექნიკური —
    ჯგუფი 03") — the same rule _dept_matches uses for visibility, so this
    can no longer disagree with what the user's own "my readings" list
    shows them. read_map is keyed by (user_id, reading_target_department).
    """
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


def _get_read_counts_by_user_dept(
    db: Session, user_ids: Optional[list[int]] = None
) -> dict[tuple[int, str], int]:
    """Grouped "read"-status counts keyed by (user_id, RequiredReading.target_department).

    One grouped query (vs a per-user COUNT) shared by the compliance / team /
    department stats views and the PDF export. Pass `user_ids` to scope to a subset;
    an empty list short-circuits to {} without a query. The department key is the
    target_department string ("All" or a dept name), matching _reading_progress.
    """
    if user_ids is not None and not user_ids:
        return {}
    q = (
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
    )
    if user_ids is not None:
        q = q.filter(models.ReadStatus.user_id.in_(user_ids))
    rows = q.group_by(
        models.ReadStatus.user_id, models.RequiredReading.target_department
    ).all()
    return {(uid, dept): cnt for uid, dept, cnt in rows}


def compute_compliance(
    db: Session,
    scope_user_ids: Optional[list[int]] = None,
    scope_department: Optional[str] = None,
):
    """Single source of truth for the compliance denominator/numerator.

    Enforces the one eligibility rule (active operators, management roles
    excluded) and the one required/read pairing rule (_reading_progress) that
    every compliance consumer must use, so the dashboard, the org-wide
    summary, and the exports can no longer diverge on "who counts" or
    "what's owed".

    Returns a list of per-user dicts: user, user_id, department,
    required_count, read_count, percentage. Callers aggregate by
    department/group/org as needed.
    """
    query = db.query(models.User).filter(
        models.User.is_active == True,  # noqa: E712
        models.User.role.notin_(_MANAGEMENT_ROLES),
    )
    if scope_user_ids is not None:
        query = query.filter(models.User.id.in_(scope_user_ids))
    if scope_department is not None:
        query = query.filter(models.User.department == scope_department)
    users = query.all()

    # SQL-side GROUP BY instead of hydrating every RequiredReading row.
    readings_by_dept = dict(
        db.query(
            models.RequiredReading.target_department,
            func.count(models.RequiredReading.id),
        )
        .group_by(models.RequiredReading.target_department)
        .all()
    )
    all_required = readings_by_dept.get("All", 0)
    read_map = _get_read_counts_by_user_dept(db, [u.id for u in users])

    records = []
    for user in users:
        required_count, read_count, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        records.append({
            "user": user,
            "user_id": user.id,
            "department": user.department,
            "required_count": required_count,
            "read_count": read_count,
            "percentage": percentage,
        })
    return records


@router.get("/api/statistics/user-progress")
def get_user_progress(
    current_admin: models.User = Depends(security.get_current_system_admin_user),
    db: Session = Depends(get_db)
):
    """Computes reading compliance progress details for all active users.

    Aggregates database counts once in memory to avoid N+1 query patterns.

    Access: Restricted to system administrators (admin) only.

    Args:
        current_admin: The authenticated system administrator User.
        db: SQLAlchemy database session.

    Returns:
        A list of employee compliance percentages, sorted by compliance level.
    """
    # RBAC: org-wide, per-employee progress is personal data → system admin only.
    users = db.query(models.User).filter(
        models.User.is_active == True,
        models.User.role.notin_(_MANAGEMENT_ROLES),
    ).all()
    # SQL-side GROUP BY instead of hydrating every RequiredReading row.
    readings_by_dept = dict(
        db.query(models.RequiredReading.target_department, func.count(models.RequiredReading.id))
        .group_by(models.RequiredReading.target_department)
        .all()
    )
    all_required = readings_by_dept.get("All", 0)

    # PERFORMANCE: one grouped query replaces a per-user COUNT (the N+1).
    # read_map[(user_id, reading_department)] = number of "read" statuses.
    read_map = _get_read_counts_by_user_dept(db)

    results = []
    for user in users:
        required_count, read_count, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        results.append({
            "user_id": user.id,
            "user_name": user.name,
            "department": user.department,
            "read_count": read_count,
            "required_count": required_count,
            "percentage": f"{percentage}%",
        })

    results.sort(key=lambda x: int(x["percentage"].replace("%", "")), reverse=True)
    return results


@router.get("/api/admin/stats/team/{team_id}", response_model=dict)
def get_admin_team_stats(
    team_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieves compliance statistics for a specific team.

    Access: Restricted to system administrators (admin).
    """
    users = db.query(models.User).filter(
        models.User.is_active == True,
        models.User.role.notin_(_MANAGEMENT_ROLES),
        models.User.team_id == team_id,
    ).all()
    if not users:
        return {"team_id": team_id, "average_percentage": "0%", "members": []}

    readings_by_dept = dict(
        db.query(models.RequiredReading.target_department, func.count(models.RequiredReading.id))
        .group_by(models.RequiredReading.target_department)
        .all()
    )
    all_required = readings_by_dept.get("All", 0)

    read_map = _get_read_counts_by_user_dept(db)

    members = []
    total_percentage = 0
    for user in users:
        required_count, read_count, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        members.append({
            "user_id": user.id,
            "user_name": user.name,
            "read_count": read_count,
            "required_count": required_count,
            "percentage": f"{percentage}%",
        })
        total_percentage += percentage

    avg_percentage = int(total_percentage / len(users)) if users else 0
    members.sort(key=lambda x: int(x["percentage"].replace("%", "")), reverse=True)

    return {
        "team_id": team_id,
        "average_percentage": f"{avg_percentage}%",
        "members": members
    }


@router.get("/api/manager/team-stats", response_model=schemas.TeamStatsResponse)
def get_team_stats(
    department: Optional[str] = None,
    team_id: Optional[int] = None,
    operator_name: Optional[str] = None,
    current_manager: models.User = Depends(security.get_current_manager_user),
    db: Session = Depends(get_db)
):
    """Retrieves compliance statistics for users in the manager's department.

    Employs optimized pre-aggregated counts.

    Access: Restricted to managers (manager) and system administrators (admin).
    The ``department`` query parameter is honoured ONLY for system admins; managers
    are always pinned to their own department to prevent cross-team data leakage.
    The ``operator_name`` query parameter performs a case-insensitive substring
    match on the user's display name.

    Args:
        department: (Admin-only) restrict to a specific department.
        team_id: Optional filter to restrict by a specific team_id.
        operator_name: Substring filter on the operator's name.
        current_manager: The authenticated manager/supervisor User.
        db: SQLAlchemy database session.

    Returns:
        A TeamStatsResponse containing team member compliance progress.
    """
    users_q = db.query(models.User).filter(
        models.User.is_active == True,
        models.User.role.notin_(_MANAGEMENT_ROLES),
    )

    if current_manager.role == "admin":
        if department:
            users_q = users_q.filter(models.User.department == department)
            dept = department
        else:
            dept = "All"
    else:
        # RBAC: a manager is hard-pinned to their own department, even if they
        # send a different department in the query string.
        dept = current_manager.department
        users_q = users_q.filter(models.User.department == dept)

    if team_id:
        users_q = users_q.filter(models.User.team_id == team_id)

    if operator_name:
        # Case-insensitive substring match — supports partial typing in the filter.
        users_q = users_q.filter(models.User.name.ilike(f"%{operator_name.strip()}%"))

    users = users_q.all()

    readings_by_dept = dict(
        db.query(models.RequiredReading.target_department, func.count(models.RequiredReading.id))
        .group_by(models.RequiredReading.target_department)
        .all()
    )
    all_required = readings_by_dept.get("All", 0)

    # PERFORMANCE: one grouped query instead of a COUNT per team member (N+1).
    read_map = _get_read_counts_by_user_dept(db)

    members = []
    for user in users:
        required_count, read_count, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        members.append({
            "user_id": user.id,
            "user_name": user.name,
            "read_count": read_count,
            "required_count": required_count,
            "percentage": f"{percentage}%",
        })

    members.sort(key=lambda x: int(x["percentage"].replace("%", "")), reverse=True)
    return {"department": dept, "members": members}


# ── Executive Department Dashboard ────────────────────────────────────────────
# The org hierarchy lives in the free-text users.department string, formatted as
# "{prefix} — ჯგუფი NN" by seed_org_hierarchy(). We split on the em dash to
# recover Department (prefix) → Group (suffix) → Members. The Team/team_id FK is
# NOT used here because the seed never populates it.

# Whitelisted department prefixes, in display order (team-stats dashboard).
# Magti call-center org: ტექნიკური | საინფორმაციო | ოფისი — always three cards.
# Matching is by prefix (startswith), so "საინფორმაციო" also matches
# "საინფორმაციო სამსახური — ჯგუფი 01" and short form "საინფო — …".
DEPARTMENT_WHITELIST = ["ტექნიკური", "საინფორმაციო", "ოფისი"]


def _match_department_bucket(prefix: str):
    """Map a department prefix (from _split_dept_group) to a whitelist bucket.

    Returns one of DEPARTMENT_WHITELIST or None if unrecognized.
    """
    raw = (prefix or "").strip()
    if not raw:
        return None
    if raw.startswith("საინფორმაციო") or raw.startswith("საინფო"):
        return "საინფორმაციო"
    if raw.startswith("ტექნიკური") or raw.startswith("ტექნიკურ"):
        return "ტექნიკური"
    if raw.startswith("ოფისი"):
        return "ოფისი"
    for wl in DEPARTMENT_WHITELIST:
        if raw.startswith(wl):
            return wl
    return None


def _aggregate_members(members):
    """Roll up a list of member dicts into (compliance, output_volume, critical).

    Compliance averages only members who actually have required readings, so
    operators with nothing assigned don't drag the average to 0.
    """
    output_volume = sum(m["read_count"] for m in members)
    critical_count = sum(1 for m in members if m["is_critical"])
    scored = [m["percentage"] for m in members if m["required_count"] > 0]
    compliance = round(sum(scored) / len(scored)) if scored else 0
    return compliance, output_volume, critical_count


def build_department_stats(db: Session):
    """Build the executive dashboard payload: Insights Ribbon + Department tree.

    Pure data builder, fully decoupled from the HTTP layer so it can be unit
    tested and reused. Every required/read number comes from compute_compliance()
    — the single shared formula — so this view can no longer diverge from the
    org-wide summary or the exports.

    Returns a dict matching schemas.DepartmentStatsResponse.
    """
    records = compute_compliance(db)

    # Bucket members by (whitelisted department prefix, group label).
    buckets = DEPARTMENT_WHITELIST
    groups_by_dept = {wl: {} for wl in buckets}
    all_members = []  # flat list for the global ribbon

    for rec in records:
        user = rec["user"]
        prefix, group_label = _split_dept_group(user.department)
        matched = _match_department_bucket(prefix)

        if matched is None:
            # Skip users outside the three Magti service lines
            continue

        # No further skip here: a bare "ოფისი" (no ჯგუფი suffix) user still
        # counts toward department + global totals, landing in a group named
        # after the department itself via _split_dept_group's fallback. A
        # previous version dropped these silently from company-wide totals
        # too, not just their own group breakdown — dangerous for any future
        # operator with this department shape, even though today's only such
        # user is a manager already excluded upstream by compute_compliance.

        member = {
            "user_id": user.id,
            "user_name": user.name,
            "position": user.position,
            "read_count": rec["read_count"],
            "required_count": rec["required_count"],
            "percentage": rec["percentage"],
            "is_critical": rec["required_count"] > 0 and rec["percentage"] < _CRITICAL_THRESHOLD,
        }
        groups_by_dept[matched].setdefault(group_label, []).append(member)
        all_members.append(member)

    # Assemble the department tree, always rendering every whitelisted dept
    # plus the fallback bucket (it renders is_empty when nothing landed there).
    departments = []
    for wl in buckets:
        groups = []
        for group_label, gmembers in groups_by_dept[wl].items():
            gmembers.sort(key=lambda m: m["percentage"], reverse=True)
            g_comp, g_out, g_crit = _aggregate_members(gmembers)
            groups.append({
                "name": group_label,
                # The raw department string for any member in this group (they
                # share it) — useful for drill-down filters on the old endpoint.
                "full_department": _group_full_department(wl, group_label),
                "member_count": len(gmembers),
                "compliance": g_comp,
                "output_volume": g_out,
                "critical_count": g_crit,
                "members": gmembers,
            })
        groups.sort(key=lambda g: g["name"])

        dept_members = [m for g in groups for m in g["members"]]
        d_comp, d_out, d_crit = _aggregate_members(dept_members)
        departments.append({
            "name": wl,
            "member_count": len(dept_members),
            "group_count": len(groups),
            "compliance": d_comp,
            "output_volume": d_out,
            "critical_count": d_crit,
            "is_empty": len(dept_members) == 0,
            "groups": groups,
        })

    g_comp, g_out, g_crit = _aggregate_members(all_members)
    insights = {
        "global_compliance": g_comp,
        "critical_operators": g_crit,
        "total_output_volume": g_out,
        "total_members": len(all_members),
    }

    return {
        "insights": insights,
        "departments": departments,
        "generated_at": get_tbilisi_time(),
    }


def _group_full_department(prefix, group_label):
    """Reconstruct the raw department string for a (prefix, group) pair."""
    if group_label and group_label != prefix:
        return f"{prefix} {_DEPT_GROUP_DELIM} {group_label}"
    return prefix


@router.get("/api/manager/department-stats", response_model=schemas.DepartmentStatsResponse)
def get_department_stats(
    current_manager: models.User = Depends(security.get_current_manager_user),
    db: Session = Depends(get_db),
):
    """Executive dashboard data: Insights Ribbon + Department → Groups → Members.

    Aggregates compliance over the whitelisted departments, excluding the system
    administrator role. Decoupled from rendering — the body is built entirely by
    build_department_stats().

    Access: managers and system administrators.
    """
    return build_department_stats(db)


@router.get("/api/admin/critical-operators", response_model=schemas.CriticalOperatorResponse)
def get_critical_operators(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """List operators whose reading compliance is below the critical threshold.

    Three bulk queries (users, reading counts by dept, read counts by user+dept),
    then pure Python aggregation — no N+1, no ORM hydration of RequiredReading rows.
    """
    users = (
        db.query(
            models.User.id, models.User.name, models.User.department,
        )
        .filter(
            models.User.is_active == True,  # noqa: E712
            models.User.role.notin_(_MANAGEMENT_ROLES),
        )
        .all()
    )

    readings_by_dept = dict(
        db.query(
            models.RequiredReading.target_department,
            func.count(models.RequiredReading.id),
        )
        .group_by(models.RequiredReading.target_department)
        .all()
    )
    all_required = readings_by_dept.get("All", 0)

    read_map = _get_read_counts_by_user_dept(db)

    operators = []
    for user in users:
        required_count, read_count, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        if required_count > 0 and percentage < _CRITICAL_THRESHOLD:
            parts = (user.name or "").split(None, 1)
            operators.append({
                "user_id": user.id,
                "first_name": parts[0] if parts else "",
                "last_name": parts[1] if len(parts) > 1 else "",
                "department": user.department,
                "overdue_count": required_count - read_count,
            })

    operators.sort(key=lambda o: o["overdue_count"], reverse=True)
    return {
        "operators": operators,
        "total": len(operators),
        "generated_at": get_tbilisi_time(),
    }


@router.get(
    "/api/admin/departments/{department}/groups/{group_name}/users",
    response_model=schemas.GroupUsersResponse,
)
def get_group_users(
    department: str,
    group_name: str,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Per-group user completion stats for the admin drill-down modal.

    Three scoped queries: users in the target group, reading counts by dept,
    and read counts for those users only — no ORM hydration of RequiredReading.

    The department path param is a whitelist bucket (e.g. "საინფორმაციო"), not
    the full DB value ("საინფორმაციო სამსახური — ჯგუფი 01"), so we match via
    _match_department_bucket() — the same single source of truth used by
    build_department_stats() — rather than a second, hand-maintained filter.
    """
    all_candidates = (
        db.query(
            models.User.id, models.User.name, models.User.department,
        )
        .filter(
            models.User.is_active == True,  # noqa: E712
            models.User.role.notin_(_MANAGEMENT_ROLES),
        )
        .all()
    )
    users = []
    for u in all_candidates:
        prefix, group_label = _split_dept_group(u.department)
        if _match_department_bucket(prefix) == department and group_label == group_name:
            users.append(u)

    readings_by_dept = dict(
        db.query(
            models.RequiredReading.target_department,
            func.count(models.RequiredReading.id),
        )
        .group_by(models.RequiredReading.target_department)
        .all()
    )
    all_required = readings_by_dept.get("All", 0)

    user_ids = [u.id for u in users]
    read_map = _get_read_counts_by_user_dept(db, user_ids=user_ids)

    result = []
    for user in users:
        _, _, percentage = _reading_progress(
            user, all_required, readings_by_dept, read_map
        )
        parts = (user.name or "").split(None, 1)
        result.append({
            "user_id": user.id,
            "first_name": parts[0] if parts else "",
            "last_name": parts[1] if len(parts) > 1 else "",
            "completion_percentage": percentage,
        })

    result.sort(key=lambda u: u["completion_percentage"])
    return {
        "department": department,
        "group_name": group_name,
        "users": result,
        "total": len(result),
    }


@router.get("/api/statistics/activity")
def get_activity_trend(
    days: int = 7,
    bucket: str = "day",
    category: str = None,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Activity trend over the last `days` days, bucketed by `bucket` (day|hour),
    optionally filtered by audit `category`. DB-native aggregation."""
    if bucket not in ("day", "hour"):
        raise HTTPException(status_code=400, detail="bucket must be 'day' or 'hour'")
    # Cap the window so a huge range can't trigger a massive scan / timeout.
    days = max(1, min(days, 90))

    cache_key = "stats:activity:%dd:%s:%s" % (days, bucket, (category or "all"))
    cached = _stats_cache_get(cache_key)
    if cached is not None:
        return cached

    now = get_tbilisi_time()
    is_pg = db.bind.dialect.name == "postgresql"
    if bucket == "hour":
        cutoff = now.replace(minute=0, second=0, microsecond=0) - timedelta(hours=days * 24 - 1)
        bucket_col = (
            func.date_trunc("hour", models.AuditLog.timestamp) if is_pg
            else func.strftime("%Y-%m-%d %H:00", models.AuditLog.timestamp)
        )
        step, key_fmt, key_len, n = timedelta(hours=1), "%Y-%m-%d %H:00", 16, days * 24
    else:
        cutoff = now.replace(hour=0, minute=0, second=0, microsecond=0) - timedelta(days=days - 1)
        bucket_col = (
            func.date_trunc("day", models.AuditLog.timestamp) if is_pg
            else func.date(models.AuditLog.timestamp)
        )
        step, key_fmt, key_len, n = timedelta(days=1), "%Y-%m-%d", 10, days

    q = db.query(bucket_col.label("bucket"), func.count(models.AuditLog.id)).filter(
        models.AuditLog.timestamp >= cutoff
    )
    if category:
        q = q.filter(models.AuditLog.category == category.upper())
    grouped = q.group_by(bucket_col).all()
    # PG date_trunc -> datetime, SQLite strftime/date -> str; slice normalizes both.
    # Hotfix: Postgres date_trunc returns a datetime; format it with key_fmt so
    # PG keys align exactly with SQLite's string buckets ("%Y-%m-%d %H:00" etc.).
    counts = {}
    for d, c in grouped:
        key = d.strftime(key_fmt) if isinstance(d, datetime) else str(d)[:key_len]
        counts[key] = c

    # Article views live in article_view_logs now (not audit_logs), but they
    # were the bulk of this chart's USER-category signal — fold them back in
    # for the "all" and USER views so the trend keeps meaning what it meant.
    if category is None or category.upper() == "USER":
        view_bucket_col = (
            func.date_trunc("hour" if bucket == "hour" else "day", models.ArticleViewLog.viewed_at) if is_pg
            else (func.strftime("%Y-%m-%d %H:00", models.ArticleViewLog.viewed_at) if bucket == "hour"
                  else func.date(models.ArticleViewLog.viewed_at))
        )
        view_grouped = (
            db.query(view_bucket_col.label("bucket"), func.count(models.ArticleViewLog.id))
            .filter(models.ArticleViewLog.viewed_at >= cutoff)
            .group_by(view_bucket_col)
            .all()
        )
        for d, c in view_grouped:
            key = d.strftime(key_fmt) if isinstance(d, datetime) else str(d)[:key_len]
            counts[key] = counts.get(key, 0) + c

    series = []
    for i in range(n):
        key = (cutoff + step * i).strftime(key_fmt)
        series.append({"date": key, "count": counts.get(key, 0)})

    _stats_cache_set(cache_key, series)
    return series


# Allow-listed breakdown dimensions → (grouping column, counted column). Mapping
# by explicit column objects (never user strings) keeps this injection-proof.
_BREAKDOWN_DIMENSIONS = {
    "department": (models.User.department, models.User.id),
    "role": (models.User.role, models.User.id),
    "status": (models.ReadStatus.status, models.ReadStatus.id),
}


@router.get("/api/statistics/breakdown")
def get_statistics_breakdown(
    dimension: str,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Aggregated counts grouped by a whitelisted `dimension`
    (department | role | status). Returns [{"label": X, "count": Y}]."""
    mapping = _BREAKDOWN_DIMENSIONS.get(dimension)
    if mapping is None:
        raise HTTPException(
            status_code=400,
            detail="dimension must be one of: department, role, status",
        )
    group_col, count_col = mapping
    rows = (
        db.query(group_col, func.count(count_col))
        .group_by(group_col)
        .order_by(desc(func.count(count_col)))
        .all()
    )
    return [{"label": label, "count": count} for label, count in rows]


@router.get("/api/statistics/kpi", response_model=schemas.KpiResponse)
def get_kpi_counts(
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Retrieves totals of active users, articles, required readings, and videos.

    Access: Restricted to content administrators (content_admin) and system administrators (admin).

    Args:
        current_admin: The authenticated administrator User.
        db: SQLAlchemy database session.

    Returns:
        A KpiResponse schema.
    """
    cached = _stats_cache_get("stats:kpi")
    if cached is not None:
        return cached

    # A6: one round-trip — four scalar COUNT subqueries in a single SELECT.
    users, articles, required_readings, videos = db.query(
        db.query(func.count(models.User.id))
        .filter(models.User.is_active == True).scalar_subquery(),
        db.query(func.count(models.Article.id)).scalar_subquery(),
        db.query(func.count(models.RequiredReading.id)).scalar_subquery(),
        db.query(func.count(models.VideoInstruction.id)).scalar_subquery(),
    ).one()
    result = {
        "users": users,
        "articles": articles,
        "required_readings": required_readings,
        "videos": videos,
    }
    _stats_cache_set("stats:kpi", result)
    return result
