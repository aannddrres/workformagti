"""Seed local Phase 8 organisation fixtures without running the org backfill.

The fixture identity is deterministic, and every write is performed in one
transaction.  The command refuses non-local Oracle targets and production-like
profiles because these rows exist only to exercise the Phase 8 UI locally.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from datetime import datetime, timezone

from passlib.context import CryptContext


FIXTURE_EMAIL_PREFIX = "phase8.fixture."
FIXTURE_STABLE_KEY_PREFIX = "P8_"
DEFAULT_DSN = "localhost:1521/orclpdb1"
DEFAULT_USER = "magti_app"
DEFAULT_PASSWORD = "CHANGE_ME_LOCAL_DEV_ONLY"
DEFAULT_FIXTURE_PASSWORD = "Phase8LocalOnly!"

DEPARTMENTS = (
    ("TECHNICAL", "ტექნიკური", "tech"),
    ("INFORMATION", "საინფორმაციო", "info"),
    ("OFFICE", "ოფისი", "office"),
)

_PASSWORDS = CryptContext(schemes=["bcrypt"], deprecated="auto")
_LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}
_PRODUCTION_PROFILES = {"prod", "production", "stage", "staging"}


@dataclass(frozen=True)
class FixtureCounts:
    departments: int
    teams: int
    users: int
    primary_assignments: int


def _dsn_from_env() -> str:
    raw = os.getenv("ORACLE_DB_DSN") or os.getenv("ORACLE_DB_URL") or DEFAULT_DSN
    dsn = raw.removeprefix("jdbc:oracle:thin:@")
    return dsn.removeprefix("//")


def _assert_local_target(dsn: str) -> None:
    profiles = {
        os.getenv("APP_ENV", ""),
        os.getenv("ENVIRONMENT", ""),
        os.getenv("SPRING_PROFILES_ACTIVE", ""),
    }
    active_profiles = {
        part.strip().lower()
        for value in profiles
        for part in value.split(",")
        if part.strip()
    }
    blocked = active_profiles & _PRODUCTION_PROFILES
    if blocked:
        raise RuntimeError(
            "Phase 8 fixtures are local-only; refusing profile(s): "
            + ", ".join(sorted(blocked))
        )

    host_match = re.match(r"^\[?([^\]/:]+|::1)\]?:\d+[/:]", dsn)
    host = host_match.group(1).lower() if host_match else ""
    if host not in _LOCAL_HOSTS:
        raise RuntimeError(
            f"Phase 8 fixtures are local-only; refusing Oracle DSN {dsn!r}"
        )


def connect_from_env():
    """Connect to a validated local Oracle instance using application defaults."""
    import oracledb

    dsn = _dsn_from_env()
    _assert_local_target(dsn)
    return oracledb.connect(
        user=os.getenv("ORACLE_DB_USER", DEFAULT_USER),
        password=os.getenv("ORACLE_DB_PASSWORD", DEFAULT_PASSWORD),
        dsn=dsn,
    )


def _one(cursor, sql: str, **params):
    cursor.execute(sql, params)
    return cursor.fetchone()


def _verify_schema(cursor) -> None:
    cursor.execute(
        'SELECT "version" FROM "flyway_schema_history" '
        "WHERE \"success\" = 1 AND \"version\" IN ('36', '36.1')"
    )
    versions = {str(row[0]) for row in cursor.fetchall()}
    if versions != {"36", "36.1"}:
        raise RuntimeError(
            "Phase 8 fixtures require successful Flyway V36 and V36.1; "
            f"found {sorted(versions)}"
        )

    cursor.execute("SELECT stable_key, name FROM departments ORDER BY stable_key")
    actual = {(str(key), str(name)) for key, name in cursor.fetchall()}
    expected = {(key, name) for key, name, _ in DEPARTMENTS}
    if actual != expected:
        raise RuntimeError(
            "Expected exactly the three V36 bootstrap departments; "
            f"found {sorted(actual)!r}"
        )


def _department_ids(cursor) -> dict[str, int]:
    cursor.execute("SELECT id, stable_key FROM departments")
    return {str(stable_key): int(identifier) for identifier, stable_key in cursor}


def _ensure_team(cursor, department_id: int, department_key: str, number: int) -> int:
    name = f"ჯგუფი {number:02d}"
    stable_key = f"{FIXTURE_STABLE_KEY_PREFIX}{department_key}_G{number:02d}"

    cursor.execute(
        "SELECT id, stable_key FROM teams WHERE department_id = :department_id AND name = :name",
        department_id=department_id,
        name=name,
    )
    matches = cursor.fetchall()
    if len(matches) > 1:
        raise RuntimeError(
            f"Duplicate team rows for department {department_key} and name {name!r}"
        )

    by_key = _one(cursor, "SELECT id, department_id, name FROM teams WHERE stable_key = :key", key=stable_key)
    if matches:
        team_id, current_key = int(matches[0][0]), matches[0][1]
        if current_key not in (None, stable_key):
            raise RuntimeError(
                f"Team {name!r} already has conflicting stable_key {current_key!r}"
            )
        if by_key and int(by_key[0]) != team_id:
            raise RuntimeError(f"Fixture stable_key {stable_key!r} belongs to another team")
        if current_key is None:
            cursor.execute(
                "UPDATE teams SET stable_key = :key WHERE id = :team_id",
                key=stable_key,
                team_id=team_id,
            )
        return team_id

    if by_key:
        raise RuntimeError(
            f"Fixture stable_key {stable_key!r} has unexpected department/name: "
            f"{by_key[1:]!r}"
        )

    identifier = cursor.var(int)
    cursor.execute(
        "INSERT INTO teams (name, created_at, department_id, stable_key, is_active) "
        "VALUES (:name, :created_at, :department_id, :stable_key, 1) "
        "RETURNING id INTO :identifier",
        name=name,
        created_at=datetime.now(timezone.utc),
        department_id=department_id,
        stable_key=stable_key,
        identifier=identifier,
    )
    return int(identifier.getvalue()[0])


def _ensure_user(
    cursor,
    *,
    email: str,
    name: str,
    department: str,
    position: str,
    role: str,
    permissions: list[str],
    team_id: int,
    password_hash: str | None,
) -> int:
    row = _one(cursor, "SELECT id FROM users WHERE email = :email", email=email)
    permissions_json = json.dumps(permissions, separators=(",", ":"))
    if row:
        user_id = int(row[0])
        cursor.execute(
            "UPDATE users SET name = :name, department = :department, position = :position, "
            "role = :role, permissions = :permissions, team_id = :team_id, is_active = 1 "
            "WHERE id = :user_id",
            name=name,
            department=department,
            position=position,
            role=role,
            permissions=permissions_json,
            team_id=team_id,
            user_id=user_id,
        )
        return user_id

    if password_hash is None:
        raise RuntimeError("A password hash is required when creating fixture users")
    identifier = cursor.var(int)
    cursor.execute(
        "INSERT INTO users (email, name, department, position, role, is_active, "
        "hashed_password, permissions, team_id) VALUES "
        "(:email, :name, :department, :position, :role, 1, :password_hash, "
        ":permissions, :team_id) RETURNING id INTO :identifier",
        email=email,
        name=name,
        department=department,
        position=position,
        role=role,
        password_hash=password_hash,
        permissions=permissions_json,
        team_id=team_id,
        identifier=identifier,
    )
    return int(identifier.getvalue()[0])


def _ensure_primary_assignment(cursor, team_id: int, leader_id: int) -> None:
    cursor.execute(
        "SELECT id, user_id FROM leadership_assignments "
        "WHERE team_id = :team_id AND assignment_type = 'PRIMARY' AND is_active = 1",
        team_id=team_id,
    )
    assignments = cursor.fetchall()
    if len(assignments) > 1:
        raise RuntimeError(f"Team {team_id} has multiple active PRIMARY assignments")
    if assignments:
        if int(assignments[0][1]) != leader_id:
            raise RuntimeError(
                f"Team {team_id} already has a different active PRIMARY leader "
                f"(user {assignments[0][1]})"
            )
        return

    cursor.execute(
        "INSERT INTO leadership_assignments "
        "(user_id, team_id, assignment_type, is_active, started_at, source) "
        "VALUES (:user_id, :team_id, 'PRIMARY', 1, :started_at, 'MANUAL')",
        user_id=leader_id,
        team_id=team_id,
        started_at=datetime.now(timezone.utc),
    )


def _counts(cursor) -> FixtureCounts:
    departments = int(
        _one(
            cursor,
            "SELECT COUNT(*) FROM departments WHERE stable_key IN "
            "('TECHNICAL', 'INFORMATION', 'OFFICE')",
        )[0]
    )
    teams = int(
        _one(
            cursor,
            "SELECT COUNT(*) FROM teams WHERE stable_key LIKE :prefix",
            prefix=f"{FIXTURE_STABLE_KEY_PREFIX}%",
        )[0]
    )
    users = int(
        _one(
            cursor,
            "SELECT COUNT(*) FROM users WHERE email LIKE :prefix",
            prefix=f"{FIXTURE_EMAIL_PREFIX}%",
        )[0]
    )
    primary_assignments = int(
        _one(
            cursor,
            "SELECT COUNT(*) FROM leadership_assignments la "
            "JOIN teams t ON t.id = la.team_id "
            "WHERE t.stable_key LIKE :prefix "
            "AND la.assignment_type = 'PRIMARY' AND la.is_active = 1",
            prefix=f"{FIXTURE_STABLE_KEY_PREFIX}%",
        )[0]
    )
    return FixtureCounts(departments, teams, users, primary_assignments)


def seed(connection, *, commit: bool = True) -> FixtureCounts:
    """Create or reconcile all fixtures; this function never invokes backfill."""
    cursor = connection.cursor()
    try:
        _verify_schema(cursor)
        department_ids = _department_ids(cursor)
        fixture_password = os.getenv("PHASE8_FIXTURE_PASSWORD", DEFAULT_FIXTURE_PASSWORD)
        password_hash: str | None = None

        for department_key, department_name, email_segment in DEPARTMENTS:
            for team_number in range(1, 6):
                team_id = _ensure_team(
                    cursor, department_ids[department_key], department_key, team_number
                )
                group_name = f"ჯგუფი {team_number:02d}"
                department_label = f"{department_name} — {group_name}"
                leader_email = (
                    f"{FIXTURE_EMAIL_PREFIX}{email_segment}.g{team_number:02d}.lead@magti.ge"
                )
                if _one(cursor, "SELECT id FROM users WHERE email = :email", email=leader_email) is None:
                    password_hash = password_hash or _PASSWORDS.hash(fixture_password)
                leader_id = _ensure_user(
                    cursor,
                    email=leader_email,
                    name=f"Phase 8 {department_name} {group_name} ლიდერი",
                    department=department_label,
                    position="ჯგუფის ლიდერი",
                    role="manager",
                    permissions=["reports.export", "system.audit"],
                    team_id=team_id,
                    password_hash=password_hash,
                )

                for operator_number in range(1, 3):
                    operator_email = (
                        f"{FIXTURE_EMAIL_PREFIX}{email_segment}.g{team_number:02d}."
                        f"op{operator_number:02d}@magti.ge"
                    )
                    if _one(cursor, "SELECT id FROM users WHERE email = :email", email=operator_email) is None:
                        password_hash = password_hash or _PASSWORDS.hash(fixture_password)
                    _ensure_user(
                        cursor,
                        email=operator_email,
                        name=(
                            f"Phase 8 {department_name} {group_name} "
                            f"ოპერატორი {operator_number:02d}"
                        ),
                        department=department_label,
                        position="ოპერატორი",
                        role="operator",
                        permissions=[],
                        team_id=team_id,
                        password_hash=password_hash,
                    )

                _ensure_primary_assignment(cursor, team_id, leader_id)

        counts = _counts(cursor)
        expected = FixtureCounts(3, 15, 45, 15)
        if counts != expected:
            raise RuntimeError(f"Unexpected Phase 8 fixture counts: {counts}; expected {expected}")
        if commit:
            connection.commit()
        return counts
    except Exception:
        connection.rollback()
        raise
    finally:
        cursor.close()


def main() -> None:
    connection = connect_from_env()
    try:
        counts = seed(connection)
    finally:
        connection.close()
    print(f"Phase 8 local fixtures ready: {counts}")
    print("Seeder only: the organisation backfill was not invoked.")


if __name__ == "__main__":
    main()
