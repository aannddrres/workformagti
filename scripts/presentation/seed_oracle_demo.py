"""Guarded Oracle baseline loader and verifier for the local presentation stack.

The loader accepts exactly one approved SQLite source manifest and exactly one
Docker-network Oracle target.  It performs no DDL and commits the complete
baseline once, after all content, organisation and activity checks pass.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import re
import sqlite3
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import quote, urlparse
from zoneinfo import ZoneInfo

import httpx
import oracledb
from passlib.context import CryptContext

from common import (
    EXPECTED_ARTICLES,
    EXPECTED_ARTICLE_HISTORY,
    EXPECTED_ASSETS,
    EXPECTED_ASSET_BYTES,
    EXPECTED_NEWS,
    EXPECTED_FLYWAY_VERSION,
    EXPECTED_ORACLE_CONTEXT,
    EXPECTED_VIDEOS,
    SanitizationStats,
    SourceInventory,
    PresentationSafetyError,
    assert_expected_inventory,
    assert_local_environment,
    assert_read_only_sources,
    build_source_inventory,
    detect_image_type,
    local_upload_filename,
    normalize_department,
    normalize_search_entity_type,
    open_source_database,
    referenced_asset_names,
    sanitize_html,
    searchable_text,
    source_articles,
    source_histories,
    trigrams,
)


MARKER_ACTION = "PRESENTATION_BASELINE_COMPLETE"
MARKER_VERSION = 1
DEMO_PASSWORD_DEFAULT = "MagtiDemo2026!"
SOURCE_DB_DEFAULT = Path("/source/magti_portal.db")
SOURCE_UPLOADS_DEFAULT = Path("/source/uploads")
ARTIFACT_DIR_DEFAULT = Path("/artifacts")
API_BASE_URL_DEFAULT = "http://backend:8080"

DEPARTMENTS = (
    ("TECHNICAL", "ტექნიკური", "tech"),
    ("INFORMATION", "საინფორმაციო", "info"),
    ("OFFICE", "ოფისი", "office"),
)
ROLE_PERMISSIONS = {
    "admin": [
        "articles.edit", "articles.publish", "articles.archive", "videos.archive",
        "content.manage", "compliance.assign", "reports.export", "system.audit",
    ],
    "content_admin": [
        "articles.edit", "articles.publish", "articles.archive", "videos.archive",
        "content.manage", "compliance.assign", "system.audit",
    ],
    "manager": ["reports.export", "system.audit"],
    "operator": [],
}
PERSONAS = {
    "admin@magti.ge": "admin",
    "content@magti.ge": "content_admin",
    "manager@magti.ge": "manager",
    "info@magti.ge": "operator",
    "tech@magti.ge": "operator",
    "nino@magti.ge": "operator",
}
# Addresses outside every branch of AuthenticationService's dev-login
# allow-list: not in DEV_TEST_EMAILS, and not carrying the "test_operator_" or
# "presentation." prefixes. They must still be refused while dev login is on.
OUTSIDE_DEV_LOGIN_ALLOWLIST = (
    "verify.not.a.real.account@example.com",
    "nobody@nowhere.test",
)
PULSE_EMAILS = tuple(
    ["tech@magti.ge"] + [f"presentation.tech.g01.op{number:02d}@magti.ge" for number in range(2, 9)]
    + ["info@magti.ge"] + [f"presentation.info.g01.op{number:02d}@magti.ge" for number in range(2, 9)]
    + ["nino@magti.ge"] + [f"presentation.office.g01.op{number:02d}@magti.ge" for number in range(2, 9)]
)
# PO-24's leaver sweep needs something to find. Every other active operator
# is seen minutes before the baseline is taken, so the "not seen in a long
# time" filter had nothing to show on stage. These nine stay ACTIVE accounts
# -- the situation PO-24 exists for: somebody left and nobody switched them
# off. Six are past 60 days but not 90 and three are past 90, so the 30, 60
# and 90 day filters each show a different, non-empty list.
#
# They are kept out of every activity generator (_present_operators): a
# person last seen four months ago cannot have read an article yesterday, and
# a demo where the leaver list contradicts the reading log is worse than no
# demo of it. Because they have no read status, compliance counts them as
# unread wherever they are in the audience -- which is PO-24's own argument
# for why leavers must be switched off.
#
# Deliberately outside group 01, where the pulse accounts live, and outside
# slot 39, which is the deactivated set. The headcount does not change: these
# are existing operators with an older last sign-in, not new people.
LONG_ABSENT_OPERATORS: dict[tuple[str, int, int], int] = {
    ("TECHNICAL", 2, 38): 75,
    ("INFORMATION", 2, 38): 75,
    ("OFFICE", 2, 38): 75,
    ("TECHNICAL", 4, 38): 75,
    ("INFORMATION", 4, 38): 75,
    ("OFFICE", 4, 38): 75,
    ("TECHNICAL", 3, 37): 130,
    ("INFORMATION", 3, 37): 130,
    ("OFFICE", 3, 37): 130,
}
DEMO_ARTICLE_TITLES = (
    "სადემო მონახაზი — eSIM FAQ",
    "სადემო დაგეგმილი სტატია — ახალი პროცედურა",
    "სადემო წაშლილი მასალა — აღდგენის სცენარი",
)
QUIZ_SOURCE_IDS = (129, 131)
REQUIRED_READING_SOURCE_IDS = (17, 18, 24, 129, 131, 22)

_PASSWORDS = CryptContext(schemes=["bcrypt"], deprecated="auto")
_TBILISI = ZoneInfo("Asia/Tbilisi")


@dataclass(frozen=True)
class SeedUser:
    id: int
    email: str
    name: str
    department: str
    department_key: str | None
    team_key: str | None
    role: str
    active: bool
    # False for LONG_ABSENT_OPERATORS: the account is on, the person is gone.
    present: bool = True


@dataclass
class SeedState:
    inventory: SourceInventory
    now: datetime
    admin_id: int
    content_id: int
    users: dict[str, SeedUser]
    article_ids: dict[int, int]
    article_rows: dict[int, sqlite3.Row]
    required_reading_ids: dict[int, int]
    sanitizer_reports: dict[int, SanitizationStats]


def _now() -> datetime:
    return datetime.now(_TBILISI).replace(tzinfo=None, microsecond=0)


def _as_timestamp(value: Any, fallback: datetime | None = None) -> datetime | None:
    if value is None or value == "":
        return fallback
    if isinstance(value, datetime):
        return value.replace(tzinfo=None)
    text = str(value).strip().replace("Z", "+00:00")
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(_TBILISI).replace(tzinfo=None)
    return parsed


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def _one(cursor: oracledb.Cursor, sql: str, **params: Any) -> tuple[Any, ...] | None:
    cursor.execute(sql, params)
    return cursor.fetchone()


def _scalar(cursor: oracledb.Cursor, sql: str, **params: Any) -> Any:
    row = _one(cursor, sql, **params)
    if row is None:
        raise PresentationSafetyError(f"Expected one row from Oracle query: {sql[:80]}")
    return row[0]


def _insert_id(cursor: oracledb.Cursor, sql: str, **params: Any) -> int:
    identifier = cursor.var(int)
    params["generated_id"] = identifier
    cursor.execute(sql, params)
    value = identifier.getvalue()
    if isinstance(value, list):
        value = value[0]
    return int(value)


def _executemany(cursor: oracledb.Cursor, sql: str, rows: Iterable[dict[str, Any]], batch: int = 1000) -> int:
    pending: list[dict[str, Any]] = []
    count = 0
    for row in rows:
        pending.append(row)
        if len(pending) >= batch:
            cursor.executemany(sql, pending)
            count += len(pending)
            pending.clear()
    if pending:
        cursor.executemany(sql, pending)
        count += len(pending)
    return count


def _environment() -> dict[str, str]:
    return {
        "app_env": os.getenv("APP_ENV", ""),
        "dsn": os.getenv("ORACLE_DB_DSN", ""),
        "user": os.getenv("ORACLE_DB_USER", ""),
        "password": os.getenv("ORACLE_DB_PASSWORD", ""),
        "confirmation": os.getenv("PRESENTATION_SEED_CONFIRM", ""),
        "demo_password": os.getenv("PRESENTATION_DEMO_PASSWORD", DEMO_PASSWORD_DEFAULT),
    }


def connect_oracle() -> oracledb.Connection:
    env = _environment()
    assert_local_environment(
        app_env=env["app_env"], dsn=env["dsn"], user=env["user"], confirmation=env["confirmation"]
    )
    if not env["password"]:
        raise PresentationSafetyError("ORACLE_DB_PASSWORD is required")
    return oracledb.connect(user=env["user"], password=env["password"], dsn=env["dsn"])


def source_paths() -> tuple[Path, Path, Path]:
    return (
        Path(os.getenv("PRESENTATION_SOURCE_DB", str(SOURCE_DB_DEFAULT))),
        Path(os.getenv("PRESENTATION_SOURCE_UPLOADS", str(SOURCE_UPLOADS_DEFAULT))),
        Path(os.getenv("PRESENTATION_ARTIFACT_DIR", str(ARTIFACT_DIR_DEFAULT))),
    )


def load_and_validate_inventory() -> SourceInventory:
    database_path, uploads_path, _ = source_paths()
    assert_read_only_sources(database_path, uploads_path)
    inventory = build_source_inventory(database_path, uploads_path)
    assert_expected_inventory(inventory)
    return inventory


def verify_oracle_context(cursor: oracledb.Cursor) -> None:
    row = _one(
        cursor,
        "SELECT SYS_CONTEXT('USERENV','SESSION_USER'), SYS_CONTEXT('USERENV','DB_NAME'), "
        "SYS_CONTEXT('USERENV','CON_NAME') FROM dual",
    )
    actual = tuple(str(value).upper() for value in row or ())
    if actual != EXPECTED_ORACLE_CONTEXT:
        expected = "/".join(EXPECTED_ORACLE_CONTEXT)
        raise PresentationSafetyError(
            f"Refusing unexpected Oracle context {actual!r}; expected {expected}"
        )

    cursor.execute(
        'SELECT "version", "success" FROM "flyway_schema_history" ORDER BY "installed_rank"'
    )
    migrations = [(str(version), int(success)) for version, success in cursor.fetchall()]
    failed = [version for version, success in migrations if success != 1]
    latest = migrations[-1][0] if migrations else None
    if failed or latest != EXPECTED_FLYWAY_VERSION:
        raise PresentationSafetyError(
            f"Presentation requires successful Flyway through V{EXPECTED_FLYWAY_VERSION} exactly; "
            f"latest={latest!r}, failed={failed!r}"
        )

    invalid = int(_scalar(
        cursor,
        "SELECT COUNT(*) FROM user_objects WHERE status = 'INVALID' "
        "AND object_type IN ('FUNCTION','TRIGGER','VIEW')",
    ))
    if invalid:
        raise PresentationSafetyError(f"Oracle schema has {invalid} invalid function/trigger/view objects")


def _marker_details(cursor: oracledb.Cursor) -> dict[str, Any] | None:
    cursor.execute(
        "SELECT details FROM audit_logs WHERE action = :action ORDER BY id",
        action=MARKER_ACTION,
    )
    rows = cursor.fetchall()
    if not rows:
        return None
    if len(rows) != 1:
        raise PresentationSafetyError(f"Expected exactly one presentation marker; found {len(rows)}")
    raw = rows[0][0]
    text = raw.read() if hasattr(raw, "read") else str(raw)
    try:
        return json.loads(text)
    except json.JSONDecodeError as error:
        raise PresentationSafetyError("Presentation marker contains invalid JSON") from error


def assert_fresh_target(cursor: oracledb.Cursor) -> None:
    expected_departments = {"TECHNICAL", "INFORMATION", "OFFICE"}
    cursor.execute("SELECT stable_key FROM departments")
    actual_departments = {str(row[0]) for row in cursor.fetchall()}
    if actual_departments != expected_departments:
        raise PresentationSafetyError(
            f"Expected only V36 bootstrap departments; found {sorted(actual_departments)!r}"
        )

    guarded_tables = (
        "teams", "users", "categories", "articles", "article_history", "news",
        "video_instructions", "stored_files", "search_logs", "article_view_logs",
        "favorites", "required_readings", "read_statuses", "audit_logs", "broadcasts",
        "reminders", "leadership_assignments", "user_permission_overrides",
    )
    nonempty: dict[str, int] = {}
    for table in guarded_tables:
        count = int(_scalar(cursor, f"SELECT COUNT(*) FROM {table}"))
        if count:
            nonempty[table] = count
    if nonempty:
        raise PresentationSafetyError(
            "Oracle target is not the empty presentation database; refusing to merge into unknown data: "
            + _json(nonempty)
        )


def _write_report(name: str, payload: dict[str, Any]) -> None:
    _, _, artifact_dir = source_paths()
    artifact_dir.mkdir(parents=True, exist_ok=True)
    destination = artifact_dir / name
    destination.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    print(f"Wrote {destination}")


def _source_bundle() -> tuple[
    sqlite3.Connection,
    list[sqlite3.Row],
    list[sqlite3.Row],
    set[str],
    dict[int, SanitizationStats],
]:
    database_path, _uploads_path, _ = source_paths()
    connection = open_source_database(database_path)
    articles = source_articles(connection)
    histories = source_histories(connection)
    valid_assets = set(referenced_asset_names(
        [str(row["content"] or "") for row in articles]
        + [str(row["content"] or "") for row in histories]
    ))
    reports = {int(row["id"]): SanitizationStats(source_article_id=int(row["id"])) for row in articles}
    return connection, articles, histories, valid_assets, reports


def _safe_attachment(value: str | None, valid_assets: set[str]) -> str | None:
    if not value:
        return None
    filename = local_upload_filename(value)
    if filename:
        return f"/uploads/{filename}" if filename in valid_assets else None
    parsed = urlparse(value.strip())
    return value.strip() if parsed.scheme in {"http", "https"} else None


def _create_central_users(cursor: oracledb.Cursor, now: datetime, password_hash: str) -> tuple[int, int, dict[str, SeedUser]]:
    # content2..4 exist so the login picker can offer four content admins.
    # Every other demo account signs in through the dev-login gate -- the six
    # named personas by allowlist, everyone else by the "presentation." prefix
    # -- but these three are neither, so they must actually hold the persona
    # password for the picker's local-persona sign-in to match. admin@ and
    # content@ keep the demo password (their allowlist entry ignores it anyway).
    persona_hash = _PASSWORDS.hash("local-persona")
    rows = (
        ("admin@magti.ge", "სისტემური ადმინი", "ადმინისტრაცია", "სისტემური ადმინისტრატორი", "admin", password_hash),
        ("content@magti.ge", "კონტენტის ადმინისტრატორი", "კონტენტი", "კონტენტ-ადმინი", "content_admin", password_hash),
        ("content2@magti.ge", "სადემო კონტენტ-ადმინი 02", "კონტენტი", "კონტენტ-ადმინი", "content_admin", persona_hash),
        ("content3@magti.ge", "სადემო კონტენტ-ადმინი 03", "კონტენტი", "კონტენტ-ადმინი", "content_admin", persona_hash),
        ("content4@magti.ge", "სადემო კონტენტ-ადმინი 04", "კონტენტი", "კონტენტ-ადმინი", "content_admin", persona_hash),
    )
    users: dict[str, SeedUser] = {}
    for email, name, department, position, role, phash in rows:
        user_id = _insert_id(
            cursor,
            "INSERT INTO users (email,name,department,position,role,is_active,last_active,hashed_password,permissions) "
            "VALUES (:email,:name,:department,:position,:role,1,:last_active,:password_hash,:permissions) "
            "RETURNING id INTO :generated_id",
            email=email,
            name=name,
            department=department,
            position=position,
            role=role,
            last_active=now - timedelta(minutes=5),
            password_hash=phash,
            permissions=_json(ROLE_PERMISSIONS[role]),
        )
        users[email] = SeedUser(user_id, email, name, department, None, None, role, True)
    return users["admin@magti.ge"].id, users["content@magti.ge"].id, users


def _create_organisation(
    cursor: oracledb.Cursor,
    now: datetime,
    admin_id: int,
    password_hash: str,
    users: dict[str, SeedUser],
) -> tuple[dict[str, int], dict[str, int]]:
    cursor.execute("SELECT id, stable_key FROM departments")
    department_ids = {str(key): int(identifier) for identifier, key in cursor.fetchall()}
    team_ids: dict[str, int] = {}
    leader_ids: dict[str, int] = {}

    for department_key, department_name, short in DEPARTMENTS:
        for team_number in range(1, 6):
            team_key = f"PRES_{department_key}_G{team_number:02d}"
            team_ids[team_key] = _insert_id(
                cursor,
                "INSERT INTO teams (name,created_at,department_id,stable_key,is_active,synced_at) "
                "VALUES (:name,:created_at,:department_id,:stable_key,1,:synced_at) "
                "RETURNING id INTO :generated_id",
                name=f"ჯგუფი {team_number:02d}",
                created_at=now - timedelta(days=365),
                department_id=department_ids[department_key],
                stable_key=team_key,
                synced_at=now - timedelta(hours=3),
            )

    for department_key, department_name, short in DEPARTMENTS:
        for team_number in range(1, 6):
            team_key = f"PRES_{department_key}_G{team_number:02d}"
            email = (
                "manager@magti.ge"
                if department_key == "TECHNICAL" and team_number == 1
                else f"presentation.{short}.g{team_number:02d}.lead@magti.ge"
            )
            name = (
                "სადემო ტექნიკური დეპარტამენტის ლიდერი"
                if email == "manager@magti.ge"
                else f"სადემო {department_name} ჯგუფი {team_number:02d} ლიდერი"
            )
            label = f"{department_name} — ჯგუფი {team_number:02d}"
            user_id = _insert_id(
                cursor,
                "INSERT INTO users (email,name,department,position,role,is_active,last_active,hashed_password,permissions,team_id) "
                "VALUES (:email,:name,:department,'ჯგუფის ლიდერი','manager',1,:last_active,:password_hash,:permissions,:team_id) "
                "RETURNING id INTO :generated_id",
                email=email,
                name=name,
                department=label,
                last_active=now - timedelta(minutes=(team_number * 7)),
                password_hash=password_hash,
                permissions=_json(ROLE_PERMISSIONS["manager"]),
                team_id=team_ids[team_key],
            )
            leader_ids[team_key] = user_id
            users[email] = SeedUser(user_id, email, name, label, department_key, team_key, "manager", True)

    for department_key, department_name, short in DEPARTMENTS:
        for team_number in range(1, 6):
            team_key = f"PRES_{department_key}_G{team_number:02d}"
            label = f"{department_name} — ჯგუფი {team_number:02d}"
            for operator_number in range(1, 40):
                persona_email = {
                    ("TECHNICAL", 1, 1): "tech@magti.ge",
                    ("INFORMATION", 1, 1): "info@magti.ge",
                    ("OFFICE", 1, 1): "nino@magti.ge",
                }.get((department_key, team_number, operator_number))
                email = persona_email or (
                    f"presentation.{short}.g{team_number:02d}.op{operator_number:02d}@magti.ge"
                )
                name = {
                    "tech@magti.ge": "ტექნიკური ოპერატორი",
                    "info@magti.ge": "საინფორმაციო ოპერატორი",
                    "nino@magti.ge": "ნინო — ოფისის ოპერატორი",
                }.get(email, f"სადემო {department_name} ჯგუფი {team_number:02d} ოპერატორი {operator_number:02d}")
                active = operator_number != 39
                absent_days = LONG_ABSENT_OPERATORS.get((department_key, team_number, operator_number))
                if not active:
                    last_active = now - timedelta(days=45)
                elif absent_days is not None:
                    last_active = now - timedelta(days=absent_days)
                else:
                    last_active = now - timedelta(minutes=(operator_number * 11 + team_number))
                user_id = _insert_id(
                    cursor,
                    "INSERT INTO users (email,name,department,position,role,is_active,last_active,hashed_password,permissions,team_id,manager_id) "
                    "VALUES (:email,:name,:department,'ოპერატორი','operator',:is_active,:last_active,:password_hash,:permissions,:team_id,:manager_id) "
                    "RETURNING id INTO :generated_id",
                    email=email,
                    name=name,
                    department=label,
                    is_active=1 if active else 0,
                    last_active=last_active,
                    password_hash=password_hash,
                    permissions=_json(ROLE_PERMISSIONS["operator"]),
                    team_id=team_ids[team_key],
                    manager_id=leader_ids[team_key],
                )
                users[email] = SeedUser(
                    user_id, email, name, label, department_key, team_key, "operator", active,
                    present=absent_days is None,
                )

    for team_key, leader_id in leader_ids.items():
        cursor.execute(
            "INSERT INTO leadership_assignments "
            "(user_id,team_id,assignment_type,is_active,started_at,created_by,source) "
            "VALUES (:user_id,:team_id,'PRIMARY',1,:started_at,:created_by,'MANUAL')",
            user_id=leader_id,
            team_id=team_ids[team_key],
            started_at=now - timedelta(days=180),
            created_by=admin_id,
        )

    for department_key, _department_name, _short in DEPARTMENTS:
        leader_id = leader_ids[f"PRES_{department_key}_G01"]
        cursor.execute(
            "INSERT INTO leadership_assignments "
            "(user_id,department_id,assignment_type,is_active,started_at,created_by,source) "
            "VALUES (:user_id,:department_id,'PRIMARY',1,:started_at,:created_by,'MANUAL')",
            user_id=leader_id,
            department_id=department_ids[department_key],
            started_at=now - timedelta(days=120),
            created_by=admin_id,
        )

    for department_key, _department_name, _short in DEPARTMENTS:
        acting_user = leader_ids[f"PRES_{department_key}_G02"]
        cursor.execute(
            "INSERT INTO leadership_assignments "
            "(user_id,team_id,assignment_type,is_active,started_at,ended_at,created_by,source) "
            "VALUES (:user_id,:team_id,'ACTING',1,:started_at,:ended_at,:created_by,'MANUAL')",
            user_id=acting_user,
            team_id=team_ids[f"PRES_{department_key}_G03"],
            started_at=now - timedelta(days=3),
            ended_at=now + timedelta(days=7),
            created_by=admin_id,
        )

    override_user = users["presentation.tech.g02.op02@magti.ge"].id
    cursor.execute(
        "INSERT INTO user_permission_overrides (user_id,permission,state,updated_at,updated_by) "
        "VALUES (:user_id,'articles.edit','ALLOW',:updated_at,:updated_by)",
        user_id=override_user,
        updated_at=now - timedelta(days=4),
        updated_by=admin_id,
    )
    deny_user = users["presentation.info.g02.lead@magti.ge"].id
    cursor.execute(
        "INSERT INTO user_permission_overrides (user_id,permission,state,updated_at,updated_by) "
        "VALUES (:user_id,'reports.export','DENY',:updated_at,:updated_by)",
        user_id=deny_user,
        updated_at=now - timedelta(days=2),
        updated_by=admin_id,
    )

    if len(users) != 605:
        raise PresentationSafetyError(f"Organisation generation produced {len(users)} users, expected 605")
    return department_ids, team_ids


def _import_categories(cursor: oracledb.Cursor, source: sqlite3.Connection) -> dict[int, int]:
    rows = source.execute(
        "SELECT * FROM categories WHERE id IN ("
        "SELECT DISTINCT category_id FROM articles WHERE id BETWEEN 17 AND 138 AND category_id IS NOT NULL"
        ") ORDER BY id"
    ).fetchall()
    if len(rows) != 11:
        raise PresentationSafetyError(f"Expected 11 used categories, found {len(rows)}")
    category_ids: dict[int, int] = {}
    for row in rows:
        category_ids[int(row["id"])] = _insert_id(
            cursor,
            "INSERT INTO categories (name,parent_id,slug,icon,pastel_color_class,is_active) "
            "VALUES (:name,NULL,:slug,:icon,:pastel_color_class,:is_active) "
            "RETURNING id INTO :generated_id",
            name=str(row["name"]),
            slug=row["slug"],
            icon=row["icon"],
            pastel_color_class=row["pastel_color_class"],
            is_active=1 if row["is_active"] else 0,
        )
    for row in rows:
        parent_source_id = row["parent_id"]
        if parent_source_id is None or int(parent_source_id) not in category_ids:
            continue
        cursor.execute(
            "UPDATE categories SET parent_id = :parent_id WHERE id = :category_id",
            parent_id=category_ids[int(parent_source_id)],
            category_id=category_ids[int(row["id"])],
        )
        if cursor.rowcount != 1:
            raise PresentationSafetyError("Category parent update did not affect exactly one row")
    return category_ids


def _import_stored_files(
    cursor: oracledb.Cursor,
    asset_names: set[str],
    content_id: int,
    now: datetime,
) -> None:
    _database_path, uploads_path, _artifact_dir = source_paths()
    for name in sorted(asset_names):
        path = uploads_path / name
        payload = path.read_bytes()
        cursor.execute(
            "INSERT INTO stored_files (filename,content_type,byte_size,uploaded_by,created_at,content) "
            "VALUES (:filename,:content_type,:byte_size,:uploaded_by,:created_at,:content)",
            filename=name,
            content_type=detect_image_type(path),
            byte_size=len(payload),
            uploaded_by=content_id,
            created_at=now - timedelta(days=70),
            content=payload,
        )


def _import_articles(
    cursor: oracledb.Cursor,
    source: sqlite3.Connection,
    articles: list[sqlite3.Row],
    histories: list[sqlite3.Row],
    category_ids: dict[int, int],
    valid_assets: set[str],
    content_id: int,
    sanitizer_reports: dict[int, SanitizationStats],
) -> tuple[dict[int, int], dict[int, sqlite3.Row], list[tuple[str, int, str, str, str | None]]]:
    article_ids: dict[int, int] = {}
    article_rows: dict[int, sqlite3.Row] = {}
    search_records: list[tuple[str, int, str, str, str | None]] = []
    source_targets: dict[int, list[str]] = defaultdict(list)
    for row in source.execute(
        "SELECT article_id, department FROM article_target_departments "
        "WHERE article_id BETWEEN 17 AND 138 ORDER BY article_id, department"
    ):
        source_targets[int(row["article_id"])].append(normalize_department(row["department"]))

    for row in articles:
        source_id = int(row["id"])
        sanitized, _ = sanitize_html(
            str(row["content"] or ""),
            source_article_id=source_id,
            valid_assets=valid_assets,
            stats=sanitizer_reports[source_id],
        )
        legacy_target = normalize_department(row["target_department"])
        targets = sorted(set(source_targets.get(source_id) or [legacy_target]))
        if "All" in targets:
            targets = ["All"]
        version = int(row["version"] or 1)
        article_id = _insert_id(
            cursor,
            "INSERT INTO articles (title,content,category_id,tags,target_department,audience_profile,created_at,updated_at,"
            "version,author_id,status,youtube_id,published_at,attachment_url,last_verified_at,visible_to_tech_info,"
            "visible_to_service_center,is_draft,quiz_enabled) VALUES "
            "(:title,:content,:category_id,:tags,:target_department,:audience_profile,:created_at,:updated_at,"
            ":version,:author_id,'published',:youtube_id,:published_at,:attachment_url,:last_verified_at,"
            ":visible_to_tech_info,:visible_to_service_center,0,:quiz_enabled) RETURNING id INTO :generated_id",
            title=str(row["title"]),
            content=sanitized,
            category_id=category_ids.get(int(row["category_id"])) if row["category_id"] is not None else None,
            tags=row["tags"],
            target_department=legacy_target,
            audience_profile=str(row["audience_profile"] or "all"),
            created_at=_as_timestamp(row["created_at"]),
            updated_at=_as_timestamp(row["updated_at"]),
            version=version,
            author_id=content_id,
            youtube_id=row["youtube_id"],
            published_at=_as_timestamp(row["published_at"]),
            attachment_url=_safe_attachment(row["attachment_url"], valid_assets),
            last_verified_at=_as_timestamp(row["last_verified_at"]),
            visible_to_tech_info=1 if row["visible_to_tech_info"] else 0,
            visible_to_service_center=1 if row["visible_to_service_center"] else 0,
            quiz_enabled=1 if source_id in QUIZ_SOURCE_IDS else 0,
        )
        article_ids[source_id] = article_id
        article_rows[source_id] = row
        search_records.append(("article", article_id, str(row["title"]), sanitized, row["tags"]))
        _executemany(
            cursor,
            "INSERT INTO article_target_departments (article_id,department) VALUES (:article_id,:department)",
            ({"article_id": article_id, "department": department} for department in targets),
        )

    for row in histories:
        source_id = int(row["article_id"])
        sanitized, _ = sanitize_html(
            str(row["content"] or ""),
            source_article_id=source_id,
            valid_assets=valid_assets,
            stats=sanitizer_reports[source_id],
        )
        cursor.execute(
            "INSERT INTO article_history (article_id,title,content,updated_at,updated_by,version_id) "
            "VALUES (:article_id,:title,:content,:updated_at,:updated_by,:version_id)",
            article_id=article_ids[source_id],
            title=str(row["title"]),
            content=sanitized,
            updated_at=_as_timestamp(row["updated_at"]),
            updated_by=content_id,
            version_id=int(row["version_id"]) if row["version_id"] is not None else None,
        )

    if len(article_ids) != EXPECTED_ARTICLES:
        raise PresentationSafetyError(f"Imported {len(article_ids)} articles, expected {EXPECTED_ARTICLES}")
    return article_ids, article_rows, search_records


def _create_demo_cms_articles(
    cursor: oracledb.Cursor,
    now: datetime,
    content_id: int,
    category_id: int,
) -> list[tuple[str, int, str, str, str | None]]:
    definitions = (
        (
            DEMO_ARTICLE_TITLES[0],
            "<h2>სადემო სამუშაო ვერსია</h2><p>eSIM-ის შესახებ FAQ კონტენტ-ადმინის რედაქტირების სცენარისთვის.</p>",
            "draft", None, None, None,
        ),
        (
            DEMO_ARTICLE_TITLES[1],
            "<h2>დაგეგმილი გამოქვეყნება</h2><p>ეს მასალა გამოჩნდება მითითებული დროის დადგომის შემდეგ.</p>",
            "scheduled", now + timedelta(days=1), None, None,
        ),
        (
            DEMO_ARTICLE_TITLES[2],
            "<h2>Trash/restore სცენარი</h2><p>მასალა ინახება 30 დღე და შეიძლება აღდგეს.</p>",
            "published", now - timedelta(days=5), now - timedelta(days=1), now + timedelta(days=29),
        ),
    )
    records: list[tuple[str, int, str, str, str | None]] = []
    for index, (title, content, status, published_at, trashed_at, purge_after) in enumerate(definitions):
        article_id = _insert_id(
            cursor,
            "INSERT INTO articles (title,content,category_id,tags,target_department,audience_profile,created_at,updated_at,"
            "version,author_id,status,published_at,last_verified_at,visible_to_tech_info,visible_to_service_center,"
            "is_draft,quiz_enabled,trashed_at,purge_after,trashed_by) VALUES "
            "(:title,:content,:category_id,'სადემო, cms','All','all',:created_at,:updated_at,1,:author_id,:status,"
            ":published_at,:last_verified_at,1,1,:is_draft,0,:trashed_at,:purge_after,:trashed_by) "
            "RETURNING id INTO :generated_id",
            title=title,
            content=content,
            category_id=category_id,
            created_at=now - timedelta(days=6 - index),
            updated_at=now - timedelta(days=2 - index),
            author_id=content_id,
            status=status,
            published_at=published_at,
            last_verified_at=now - timedelta(days=2),
            is_draft=1 if status == "draft" else 0,
            trashed_at=trashed_at,
            purge_after=purge_after,
            trashed_by=content_id if trashed_at else None,
        )
        cursor.execute(
            "INSERT INTO article_target_departments (article_id,department) VALUES (:article_id,'All')",
            article_id=article_id,
        )
        cursor.execute(
            "INSERT INTO article_history (article_id,title,content,updated_at,updated_by,version_id) "
            "VALUES (:article_id,:title,:content,:updated_at,:updated_by,1)",
            article_id=article_id,
            title=title,
            content=content,
            updated_at=now - timedelta(days=3),
            updated_by=content_id,
        )
        records.append(("article", article_id, title, content, "სადემო, cms"))
    return records


def _import_news_and_videos(
    cursor: oracledb.Cursor,
    source: sqlite3.Connection,
    valid_assets: set[str],
    content_id: int,
) -> tuple[list[tuple[str, int, str, str, str | None]], list[tuple[str, int, str | None]]]:
    search_records: list[tuple[str, int, str, str, str | None]] = []
    tag_records: list[tuple[str, int, str | None]] = []
    news_ids: dict[int, int] = {}
    news_rows = source.execute("SELECT * FROM news ORDER BY id").fetchall()
    if len(news_rows) != EXPECTED_NEWS:
        raise PresentationSafetyError(f"Expected {EXPECTED_NEWS} news rows, found {len(news_rows)}")
    for row in news_rows:
        sanitized, _stats = sanitize_html(
            str(row["content"] or ""),
            source_article_id=-int(row["id"]),
            valid_assets=valid_assets,
        )
        news_id = _insert_id(
            cursor,
            "INSERT INTO news (title,content,target_department,created_at,attachment_url,version,visible_to_tech_info,"
            "visible_to_service_center,expires_at,is_draft,author_id) VALUES "
            "(:title,:content,:target_department,:created_at,:attachment_url,:version,:visible_to_tech_info,"
            ":visible_to_service_center,:expires_at,0,:author_id) RETURNING id INTO :generated_id",
            title=str(row["title"]),
            content=sanitized,
            target_department=normalize_department(row["target_department"]),
            created_at=_as_timestamp(row["created_at"]),
            attachment_url=_safe_attachment(row["attachment_url"], valid_assets),
            version=int(row["version"] or 1),
            visible_to_tech_info=1 if row["visible_to_tech_info"] else 0,
            visible_to_service_center=1 if row["visible_to_service_center"] else 0,
            expires_at=_as_timestamp(row["expires_at"]),
            author_id=content_id,
        )
        news_ids[int(row["id"])] = news_id
        search_records.append(("news", news_id, str(row["title"]), sanitized, None))

    for row in source.execute("SELECT * FROM news_history ORDER BY news_id, id"):
        source_news_id = int(row["news_id"])
        if source_news_id not in news_ids:
            continue
        sanitized, _stats = sanitize_html(
            str(row["content"] or ""),
            source_article_id=-source_news_id,
            valid_assets=valid_assets,
        )
        cursor.execute(
            "INSERT INTO news_history (news_id,title,content,attachment_url,updated_at,updated_by) "
            "VALUES (:news_id,:title,:content,:attachment_url,:updated_at,:updated_by)",
            news_id=news_ids[source_news_id],
            title=str(row["title"]),
            content=sanitized,
            attachment_url=_safe_attachment(row["attachment_url"], valid_assets),
            updated_at=_as_timestamp(row["updated_at"]),
            updated_by=content_id,
        )

    video_rows = source.execute("SELECT * FROM video_instructions ORDER BY id").fetchall()
    if len(video_rows) != EXPECTED_VIDEOS:
        raise PresentationSafetyError(f"Expected {EXPECTED_VIDEOS} video rows, found {len(video_rows)}")
    for row in video_rows:
        video_id = _insert_id(
            cursor,
            "INSERT INTO video_instructions (title,video_url,category,target_department,created_at,views_count,tags,is_archived) "
            "VALUES (:title,:video_url,:category,:target_department,:created_at,:views_count,:tags,:is_archived) "
            "RETURNING id INTO :generated_id",
            title=str(row["title"]),
            video_url=str(row["video_url"]),
            category=row["category"],
            target_department=normalize_department(row["target_department"]),
            created_at=_as_timestamp(row["created_at"]),
            views_count=int(row["views_count"] or 0),
            tags=row["tags"],
            is_archived=1 if row["is_archived"] else 0,
        )
        search_records.append(("video", video_id, str(row["title"]), str(row["category"] or ""), row["tags"]))
        tag_records.append(("video", video_id, row["tags"]))
    return search_records, tag_records


def _index_tags(
    cursor: oracledb.Cursor,
    article_ids: dict[int, int],
    article_rows: dict[int, sqlite3.Row],
    extra_records: list[tuple[str, int, str | None]],
    now: datetime,
) -> None:
    records = [
        ("article", article_ids[source_id], article_rows[source_id]["tags"])
        for source_id in sorted(article_ids)
    ] + extra_records
    normalized: dict[str, set[tuple[str, int]]] = defaultdict(set)
    for item_type, item_id, flat_tags in records:
        for raw in str(flat_tags or "").split(","):
            tag = re.sub(r"\s+", " ", raw).strip().lower()
            if tag:
                normalized[tag].add((item_type, item_id))
    for name in sorted(normalized):
        tag_id = _insert_id(
            cursor,
            "INSERT INTO tags (name,created_at) VALUES (:name,:created_at) RETURNING id INTO :generated_id",
            name=name[:100],
            created_at=now - timedelta(days=60),
        )
        _executemany(
            cursor,
            "INSERT INTO tags_mapping (tag_id,item_type,item_id) VALUES (:tag_id,:item_type,:item_id)",
            (
                {"tag_id": tag_id, "item_type": item_type, "item_id": item_id}
                for item_type, item_id in sorted(normalized[name])
            ),
        )


def _index_search(
    cursor: oracledb.Cursor,
    records: list[tuple[str, int, str, str, str | None]],
) -> int:
    rows = (
        {
            "entity_type": normalize_search_entity_type(item_type),
            "entity_id": item_id,
            "trigram": trigram,
        }
        for item_type, item_id, title, content, tags in records
        for trigram in trigrams(searchable_text(title, content, tags))
    )
    return _executemany(
        cursor,
        "INSERT INTO search_trigrams (entity_type,entity_id,trigram) "
        "VALUES (:entity_type,:entity_id,:trigram)",
        rows,
        batch=2000,
    )


def _create_quizzes(cursor: oracledb.Cursor, article_ids: dict[int, int]) -> None:
    definitions = {
        129: (
            ("რა არის მომსახურების სტანდარტის მთავარი მიზანი?", "მომხმარებლის თანმიმდევრული და ხარისხიანი მომსახურება", "ზარის სწრაფად დასრულება", "ინფორმაციის დამალვა"),
            ("როდის უნდა გადამოწმდეს მომხმარებლის მოთხოვნა?", "გადაწყვეტის დაფიქსირებამდე", "მხოლოდ საჩივრის შემდეგ", "არასდროს"),
        ),
        131: (
            ("ვის შეიძლება გადაეცეს პერსონალური მონაცემი?", "მხოლოდ უფლებამოსილ პირს დადგენილი წესით", "ნებისმიერ კოლეგას", "საჯარო ჩატში"),
            ("რა უნდა გააკეთოს ოპერატორმა საეჭვო მოთხოვნისას?", "შეაჩეროს გაცემა და გამოიყენოს ესკალაციის წესი", "მაინც გასცეს ინფორმაცია", "მონაცემი პირად მოწყობილობაზე შეინახოს"),
        ),
    }
    for source_id, questions in definitions.items():
        for question_position, answers in enumerate(questions):
            question_id = _insert_id(
                cursor,
                "INSERT INTO quiz_questions (article_id,question_text,position) "
                "VALUES (:article_id,:question_text,:position) RETURNING id INTO :generated_id",
                article_id=article_ids[source_id],
                question_text=answers[0],
                position=question_position,
            )
            _executemany(
                cursor,
                "INSERT INTO quiz_answers (question_id,answer_text,is_correct,position) "
                "VALUES (:question_id,:answer_text,:is_correct,:position)",
                (
                    {
                        "question_id": question_id,
                        "answer_text": answer,
                        "is_correct": 1 if answer_position == 0 else 0,
                        "position": answer_position,
                    }
                    for answer_position, answer in enumerate(answers[1:])
                ),
            )


def _present_operators(users: dict[str, SeedUser]) -> list[SeedUser]:
    """Operators who generate activity: switched on AND still around.

    Was "active operators". An account that is on but whose owner left months
    ago (LONG_ABSENT_OPERATORS) must not read, search, favourite or take a
    quiz after the date the admin table says they were last seen.
    """
    return sorted(
        (user for user in users.values() if user.role == "operator" and user.active and user.present),
        key=lambda user: user.email,
    )


def _source_targets(source: sqlite3.Connection) -> dict[int, set[str]]:
    targets: dict[int, set[str]] = defaultdict(set)
    for row in source.execute(
        "SELECT article_id,department FROM article_target_departments WHERE article_id BETWEEN 17 AND 138"
    ):
        targets[int(row["article_id"])].add(normalize_department(row["department"]))
    for row in source.execute("SELECT id,target_department FROM articles WHERE id BETWEEN 17 AND 138"):
        targets[int(row["id"])].add(normalize_department(row["target_department"]))
    for source_id, values in targets.items():
        if "All" in values:
            targets[source_id] = {"All"}
    return targets


def _visible_source_ids(user: SeedUser, targets: dict[int, set[str]]) -> list[int]:
    department_name = next(
        (name for key, name, _short in DEPARTMENTS if key == user.department_key),
        None,
    )
    return sorted(
        source_id
        for source_id, article_targets in targets.items()
        if "All" in article_targets or department_name in article_targets
    )


def _seed_knowledge_activity(
    cursor: oracledb.Cursor,
    source: sqlite3.Connection,
    now: datetime,
    users: dict[str, SeedUser],
    article_ids: dict[int, int],
    article_rows: dict[int, sqlite3.Row],
) -> None:
    operators = _present_operators(users)
    targets = _source_targets(source)
    randomizer = random.Random(20260823)

    view_rows: list[dict[str, Any]] = []
    for index in range(4500):
        user = operators[index % len(operators)]
        visible = _visible_source_ids(user, targets)
        source_id = visible[(index * 17 + index // len(operators)) % len(visible)]
        article = article_rows[source_id]
        view_rows.append({
            "article_id": article_ids[source_id],
            "article_id_snapshot": article_ids[source_id],
            "title": str(article["title"]),
            "version": int(article["version"] or 1),
            "operator_id": user.id,
            "operator_name": user.name,
            "operator_email": user.email,
            "department": user.department,
            "viewed_at": now - timedelta(
                days=(index * 13) % 30,
                minutes=(index * 37) % (24 * 60),
            ),
        })
    randomizer.shuffle(view_rows)
    _executemany(
        cursor,
        "INSERT INTO article_view_logs (article_id,article_id_snapshot,article_title_snapshot,article_version,"
        "operator_id,operator_name_snapshot,operator_email_snapshot,operator_department_snapshot,viewed_at) "
        "VALUES (:article_id,:article_id_snapshot,:title,:version,:operator_id,:operator_name,:operator_email,"
        ":department,:viewed_at)",
        view_rows,
    )

    successful_terms = [
        "როუმინგი", "ინტერნეტი", "ტარიფები", "მომსახურების სტანდარტი", "პერსონალურ მონაცემთა დაცვა",
        "IPTV", "MyMagti", "მობილური", "პორტირება", "ბილინგი",
    ]
    failed_terms = [
        "სადემო უშედეგო ძიება", "უცნობი პროცედურა 2026", "არარსებული პაკეტი", "demo-no-result",
    ]
    search_rows: list[dict[str, Any]] = []
    for index in range(1200):
        user = operators[(index * 7) % len(operators)]
        has_results = index >= 180
        term = (
            successful_terms[index % len(successful_terms)]
            if has_results
            else failed_terms[index % len(failed_terms)]
        )
        search_rows.append({
            "user_id": user.id,
            "term": term.lower(),
            "timestamp": now - timedelta(days=(index * 11) % 30, minutes=(index * 19) % 1440),
            "has_results": 1 if has_results else 0,
            "results_found": (index % 9) + 1 if has_results else 0,
        })
    _executemany(
        cursor,
        "INSERT INTO search_logs (user_id,search_term,timestamp,has_results,results_found) "
        "VALUES (:user_id,:term,:timestamp,:has_results,:results_found)",
        search_rows,
    )

    favorite_rows: list[dict[str, Any]] = []
    for user in operators:
        for source_id in _visible_source_ids(user, targets):
            favorite_rows.append({
                "user_id": user.id,
                "item_type": "article",
                "item_id": article_ids[source_id],
            })
            if len(favorite_rows) == 800:
                break
        if len(favorite_rows) == 800:
            break
    if len(favorite_rows) != 800:
        raise PresentationSafetyError("Unable to construct 800 unique visible favorites")
    _executemany(
        cursor,
        "INSERT INTO favorites (user_id,item_type,item_id) VALUES (:user_id,:item_type,:item_id)",
        favorite_rows,
    )

    note_rows: list[dict[str, Any]] = []
    for index, user in enumerate(operators[:60]):
        source_id = _visible_source_ids(user, targets)[index % len(_visible_source_ids(user, targets))]
        note_rows.append({
            "user_id": user.id,
            "article_id": article_ids[source_id],
            "content": f"სადემო პირადი შენიშვნა #{index + 1}: გადასამოწმებელი ნაბიჯი.",
            "created_at": now - timedelta(days=index % 20),
            "updated_at": now - timedelta(days=index % 10),
        })
    _executemany(
        cursor,
        "INSERT INTO user_notes (user_id,article_id,content,created_at,updated_at) "
        "VALUES (:user_id,:article_id,:content,:created_at,:updated_at)",
        note_rows,
    )


def _reading_due_dates(now: datetime) -> dict[int, datetime]:
    return {
        17: now - timedelta(days=5),
        18: now + timedelta(days=2),
        24: now + timedelta(days=14),
        129: now - timedelta(days=2),
        131: now + timedelta(days=1),
        22: now + timedelta(days=7),
    }


def _reading_targets() -> dict[int, str]:
    return {
        17: "All",
        18: "All",
        24: "All",
        129: "ტექნიკური",
        131: "საინფორმაციო",
        22: "ოფისი",
    }


def _eligible_for_reading(user: SeedUser, target: str) -> bool:
    if target == "All":
        return True
    department_key = next((key for key, name, _short in DEPARTMENTS if name == target), None)
    return user.department_key == department_key


def _seed_compliance(
    cursor: oracledb.Cursor,
    now: datetime,
    users: dict[str, SeedUser],
    article_ids: dict[int, int],
    article_rows: dict[int, sqlite3.Row],
    admin_id: int,
) -> dict[int, int]:
    due_dates = _reading_due_dates(now)
    targets = _reading_targets()
    reading_ids: dict[int, int] = {}
    for source_id in REQUIRED_READING_SOURCE_IDS:
        reading_ids[source_id] = _insert_id(
            cursor,
            "INSERT INTO required_readings (item_type,item_id,target_department,due_date,priority,item_title_snapshot) "
            "VALUES ('article',:item_id,:target_department,:due_date,:priority,:title) "
            "RETURNING id INTO :generated_id",
            item_id=article_ids[source_id],
            target_department=targets[source_id],
            due_date=due_dates[source_id],
            priority="high" if due_dates[source_id] < now else "normal",
            title=str(article_rows[source_id]["title"]),
        )

    operators = _present_operators(users)
    pulse_emails = set(PULSE_EMAILS)
    reminder_rows: list[dict[str, Any]] = []
    manual_candidates: list[SeedUser] = []
    for source_id in REQUIRED_READING_SOURCE_IDS:
        reading_id = reading_ids[source_id]
        article_id = article_ids[source_id]
        article = article_rows[source_id]
        due_date = due_dates[source_id]
        eligible = [user for user in operators if _eligible_for_reading(user, targets[source_id])]
        for index, user in enumerate(eligible):
            weak_group = bool(user.team_key and user.team_key.endswith("G05"))
            bucket = (index * 7 + source_id) % 10
            if weak_group:
                outcome = "unread" if bucket < 3 else "late" if bucket < 5 else "on_time"
            else:
                outcome = "unread" if bucket == 0 else "late" if bucket == 1 else "on_time"

            is_pulse_assignment = user.email in pulse_emails and source_id in {129, 131, 22}
            if is_pulse_assignment:
                outcome = "unread"

            passed_quiz = False
            if source_id in QUIZ_SOURCE_IDS and not is_pulse_assignment:
                quiz_bucket = (index + source_id) % 5
                if quiz_bucket == 0:
                    cursor.execute(
                        "INSERT INTO quiz_attempts (article_id,article_id_snapshot,article_title_snapshot,article_version,"
                        "user_id,attempt_number,score,total_questions,passed,created_at) "
                        "VALUES (:article_id,:article_id_snapshot,:title,:version,:user_id,1,1,2,0,:created_at)",
                        article_id=article_id,
                        article_id_snapshot=article_id,
                        title=str(article["title"]),
                        version=int(article["version"] or 1),
                        user_id=user.id,
                        created_at=now - timedelta(days=6, minutes=index % 120),
                    )
                    outcome = "unread"
                elif quiz_bucket == 1:
                    _executemany(
                        cursor,
                        "INSERT INTO quiz_attempts (article_id,article_id_snapshot,article_title_snapshot,article_version,"
                        "user_id,attempt_number,score,total_questions,passed,created_at) "
                        "VALUES (:article_id,:article_id_snapshot,:title,:version,:user_id,:attempt_number,:score,2,"
                        ":passed,:created_at)",
                        (
                            {
                                "article_id": article_id,
                                "article_id_snapshot": article_id,
                                "title": str(article["title"]),
                                "version": int(article["version"] or 1),
                                "user_id": user.id,
                                "attempt_number": attempt,
                                "score": score,
                                "passed": passed,
                                "created_at": now - timedelta(days=5 - attempt, minutes=index % 120),
                            }
                            for attempt, score, passed in ((1, 1, 0), (2, 2, 1))
                        ),
                    )
                    passed_quiz = True
                else:
                    cursor.execute(
                        "INSERT INTO quiz_attempts (article_id,article_id_snapshot,article_title_snapshot,article_version,"
                        "user_id,attempt_number,score,total_questions,passed,created_at) "
                        "VALUES (:article_id,:article_id_snapshot,:title,:version,:user_id,1,2,2,1,:created_at)",
                        article_id=article_id,
                        article_id_snapshot=article_id,
                        title=str(article["title"]),
                        version=int(article["version"] or 1),
                        user_id=user.id,
                        created_at=now - timedelta(days=4, minutes=index % 120),
                    )
                    passed_quiz = True
            elif source_id not in QUIZ_SOURCE_IDS:
                passed_quiz = True

            read_at: datetime | None = None
            if outcome != "unread" and passed_quiz:
                if outcome == "late" and due_date < now - timedelta(hours=1):
                    read_at = min(now - timedelta(minutes=index % 60), due_date + timedelta(hours=8))
                else:
                    read_at = min(now - timedelta(minutes=index % 180), due_date - timedelta(hours=8))

            cursor.execute(
                "INSERT INTO read_statuses (user_id,required_reading_id,status,read_at,operator_department_snapshot) "
                "VALUES (:user_id,:reading_id,:status,:read_at,:department)",
                user_id=user.id,
                reading_id=reading_id,
                status="read" if read_at else "unread",
                read_at=read_at,
                department=user.department,
            )
            if read_at:
                cursor.execute(
                    "INSERT INTO article_read_receipts (article_id,article_id_snapshot,article_title_snapshot,"
                    "article_version,operator_id,operator_name_snapshot,operator_email_snapshot,"
                    "operator_department_snapshot,read_at) VALUES "
                    "(:article_id,:article_id_snapshot,:title,:version,:operator_id,:operator_name,:operator_email,"
                    ":department,:read_at)",
                    article_id=article_id,
                    article_id_snapshot=article_id,
                    title=str(article["title"]),
                    version=int(article["version"] or 1),
                    operator_id=user.id,
                    operator_name=user.name,
                    operator_email=user.email,
                    department=user.department,
                    read_at=read_at,
                )

            assignment_created = min(now - timedelta(days=9), due_date - timedelta(days=7))
            reminder_rows.append({
                "recipient_id": user.id,
                "recipient_name": user.name,
                "reading_id": reading_id,
                "reminder_type": "ASSIGNMENT",
                "content": f"დავალება: {article['title']}",
                "item_id": article_id,
                "item_title": str(article["title"]),
                "due_at": due_date,
                "trigger_id": admin_id,
                "trigger_name": "სისტემური ადმინი",
                "created_at": assignment_created,
                "read_at": assignment_created + timedelta(hours=4) if index % 3 else None,
            })
            if read_at is None and due_date <= now + timedelta(days=3):
                reminder_type = "OVERDUE" if due_date < now else "DUE_SOON"
                created_at = now - timedelta(hours=8 if reminder_type == "OVERDUE" else 2)
                reminder_rows.append({
                    "recipient_id": user.id,
                    "recipient_name": user.name,
                    "reading_id": reading_id,
                    "reminder_type": reminder_type,
                    "content": f"შეხსენება ({reminder_type}): {article['title']}",
                    "item_id": article_id,
                    "item_title": str(article["title"]),
                    "due_at": due_date,
                    "trigger_id": None,
                    "trigger_name": "სისტემა",
                    "created_at": created_at,
                    "read_at": created_at + timedelta(hours=1) if index % 4 else None,
                })
            if source_id == 17 and len(manual_candidates) < 30:
                manual_candidates.append(user)

    for index, user in enumerate(manual_candidates):
        reminder_rows.append({
            "recipient_id": user.id,
            "recipient_name": user.name,
            "reading_id": None,
            "reminder_type": "MANUAL",
            "content": "სადემო პირადი შეხსენება: გადაამოწმეთ განახლებული პროცედურა.",
            "item_id": None,
            "item_title": None,
            "due_at": None,
            "trigger_id": admin_id,
            "trigger_name": "სისტემური ადმინი",
            "created_at": now - timedelta(days=index % 8, minutes=index),
            "read_at": now - timedelta(days=index % 4) if index % 2 else None,
        })

    _executemany(
        cursor,
        "INSERT INTO reminders (recipient_user_id,recipient_name_snapshot,required_reading_id,reminder_type,"
        "content_snapshot,item_type_snapshot,item_id_snapshot,item_title_snapshot,due_at_snapshot,"
        "triggered_by_user_id,triggered_by_name_snapshot,created_at,read_at) VALUES "
        "(:recipient_id,:recipient_name,:reading_id,:reminder_type,:content,'article',:item_id,:item_title,:due_at,"
        ":trigger_id,:trigger_name,:created_at,:read_at)",
        reminder_rows,
    )
    return reading_ids


def _seed_broadcasts(cursor: oracledb.Cursor, now: datetime, admin_id: int, content_id: int) -> None:
    rows = (
        {
            "message": "სადემო აქტიური შეტყობინება: განახლებული ცოდნის ბაზა ხელმისაწვდომია.",
            "priority": "CRITICAL", "published": now - timedelta(hours=2), "ends": now + timedelta(days=2),
            "ended": None, "publisher": admin_id, "publisher_name": "სისტემური ადმინი",
            "ended_by": None, "ended_name": None,
        },
        {
            "message": "დასრულებული მნიშვნელოვანი broadcast — მომსახურების სტანდარტის განახლება.",
            "priority": "IMPORTANT", "published": now - timedelta(days=12), "ends": now - timedelta(days=9),
            "ended": now - timedelta(days=10), "publisher": content_id, "publisher_name": "კონტენტის ადმინისტრატორი",
            "ended_by": admin_id, "ended_name": "სისტემური ადმინი",
        },
        {
            "message": "დასრულებული ჩვეულებრივი broadcast — დაგეგმილი ტექნიკური სამუშაოები.",
            "priority": "NORMAL", "published": now - timedelta(days=20), "ends": now - timedelta(days=18),
            "ended": now - timedelta(days=18), "publisher": admin_id, "publisher_name": "სისტემური ადმინი",
            "ended_by": admin_id, "ended_name": "სისტემური ადმინი",
        },
        {
            "message": "დასრულებული კრიტიკული broadcast — უსაფრთხოების სადემო სწავლება.",
            "priority": "CRITICAL", "published": now - timedelta(days=28), "ends": now - timedelta(days=25),
            "ended": now - timedelta(days=26), "publisher": content_id, "publisher_name": "კონტენტის ადმინისტრატორი",
            "ended_by": content_id, "ended_name": "კონტენტის ადმინისტრატორი",
        },
    )
    _executemany(
        cursor,
        "INSERT INTO broadcasts (message,priority,published_at,ends_at,ended_at,published_by_user_id,"
        "publisher_name_snapshot,ended_by_user_id,ended_by_name_snapshot) VALUES "
        "(:message,:priority,:published,:ends,:ended,:publisher,:publisher_name,:ended_by,:ended_name)",
        rows,
    )


def _seed_audit_events(
    cursor: oracledb.Cursor,
    now: datetime,
    users: dict[str, SeedUser],
    article_ids: dict[int, int],
    article_rows: dict[int, sqlite3.Row],
    admin_id: int,
    content_id: int,
) -> None:
    actors = [
        users["admin@magti.ge"],
        users["content@magti.ge"],
        users["manager@magti.ge"],
    ]
    event_types = (
        ("LOGIN", "authentication", "user"),
        ("LOGIN_FAILED", "authentication", "user"),
        ("UPDATE_ARTICLE", "content", "article"),
        ("ASSIGN_READING", "compliance", "article"),
        ("SEND_REMINDER", "reminder", "user"),
        ("PUBLISH_BROADCAST", "broadcast", "broadcast"),
        ("VIEW_ACCESS", "access", "user"),
        ("EXPORT_READINGS", "export", "export"),
    )
    for index in range(240):
        actor = actors[index % len(actors)]
        action, category, item_type = event_types[index % len(event_types)]
        source_id = sorted(article_ids)[index % len(article_ids)]
        if item_type == "article":
            item_id = article_ids[source_id]
            item_name = str(article_rows[source_id]["title"])
        elif item_type == "user":
            item_id = actor.id
            item_name = actor.name
        else:
            item_id = (index % 4) + 1
            item_name = f"სადემო {item_type} #{item_id}"
        cursor.execute(
            "INSERT INTO audit_logs (admin_id,action,item_type,item_id,timestamp,category,details,"
            "admin_name_snapshot,admin_email_snapshot,item_name_snapshot,ip_address,user_agent) VALUES "
            "(:admin_id,:action,:item_type,:item_id,:timestamp,:category,:details,:admin_name,:admin_email,"
            ":item_name,'127.0.0.1','Magti Presentation Synthetic Baseline/1.0')",
            admin_id=actor.id,
            action=action,
            item_type=item_type,
            item_id=item_id,
            timestamp=now - timedelta(days=(index * 7) % 30, minutes=(index * 29) % 1440),
            category=category,
            details=_json({"synthetic": True, "scenario": "presentation-baseline", "sequence": index + 1}),
            admin_name=actor.name,
            admin_email=actor.email,
            item_name=item_name,
        )

    demo_ids = {
        str(title): int(identifier)
        for identifier, title in cursor.execute(
            "SELECT id,title FROM articles WHERE title IN (:one,:two,:three)",
            one=DEMO_ARTICLE_TITLES[0], two=DEMO_ARTICLE_TITLES[1], three=DEMO_ARTICLE_TITLES[2],
        )
    }
    for action, title in (
        ("CREATE_DRAFT", DEMO_ARTICLE_TITLES[0]),
        ("SCHEDULE_ARTICLE", DEMO_ARTICLE_TITLES[1]),
        ("TRASH_ARTICLE", DEMO_ARTICLE_TITLES[2]),
        ("RESTORE_ARTICLE_PREVIEW", DEMO_ARTICLE_TITLES[2]),
    ):
        cursor.execute(
            "INSERT INTO audit_logs (admin_id,action,item_type,item_id,timestamp,category,details,"
            "admin_name_snapshot,admin_email_snapshot,item_name_snapshot,ip_address,user_agent) VALUES "
            "(:admin_id,:action,'article',:item_id,:timestamp,'content',:details,"
            "'კონტენტის ადმინისტრატორი','content@magti.ge',:item_name,'127.0.0.1',"
            "'Magti Presentation Synthetic Baseline/1.0')",
            admin_id=content_id,
            action=action,
            item_id=demo_ids[title],
            timestamp=now - timedelta(days=2),
            details=_json({"synthetic": True, "scenario": "cms-lifecycle"}),
            item_name=title,
        )


def _collect_counts(cursor: oracledb.Cursor) -> dict[str, int]:
    tables = (
        "users", "teams", "categories", "articles", "article_history", "stored_files",
        "news", "video_instructions", "tags", "tags_mapping", "search_trigrams",
        "article_view_logs", "search_logs", "favorites", "user_notes", "required_readings",
        "read_statuses", "article_read_receipts", "quiz_questions", "quiz_answers", "quiz_attempts",
        "reminders", "broadcasts", "leadership_assignments", "user_permission_overrides", "audit_logs",
    )
    return {table: int(_scalar(cursor, f"SELECT COUNT(*) FROM {table}")) for table in tables}


def _assert_baseline_counts(cursor: oracledb.Cursor) -> dict[str, int]:
    counts = _collect_counts(cursor)
    exact = {
        "users": 605,
        "teams": 15,
        "categories": 11,
        "articles": 125,
        "article_history": 135,
        "stored_files": EXPECTED_ASSETS,
        "news": EXPECTED_NEWS,
        "video_instructions": EXPECTED_VIDEOS,
        "article_view_logs": 4500,
        "search_logs": 1200,
        "favorites": 800,
        "user_notes": 60,
        "required_readings": 6,
        "quiz_questions": 4,
        "quiz_answers": 12,
        "broadcasts": 4,
        "leadership_assignments": 21,
        "user_permission_overrides": 2,
    }
    wrong = {table: {"expected": expected, "actual": counts[table]} for table, expected in exact.items() if counts[table] != expected}
    if wrong:
        raise PresentationSafetyError(f"Generated baseline counts are wrong: {_json(wrong)}")
    if counts["read_statuses"] < 2200 or counts["article_read_receipts"] < 1500:
        raise PresentationSafetyError(f"Compliance baseline is unexpectedly sparse: {_json(counts)}")
    if counts["search_trigrams"] == 0 or counts["tags"] == 0 or counts["reminders"] == 0:
        raise PresentationSafetyError(f"Search/tag/reminder baseline is empty: {_json(counts)}")
    return counts


def _insert_marker(
    cursor: oracledb.Cursor,
    now: datetime,
    admin_id: int,
    inventory: SourceInventory,
    counts: dict[str, int],
) -> dict[str, Any]:
    details = {
        "synthetic": True,
        "scenario": "presentation-baseline-marker",
        "marker_version": MARKER_VERSION,
        "source_database_sha256": inventory.database_sha256,
        "source_asset_manifest_sha256": inventory.asset_manifest_sha256,
        "source_counts": inventory.as_dict(),
        "baseline_counts_before_marker": counts,
        "created_at": now.isoformat(),
    }
    cursor.execute(
        "INSERT INTO audit_logs (admin_id,action,item_type,item_id,timestamp,category,details,"
        "admin_name_snapshot,admin_email_snapshot,item_name_snapshot,ip_address,user_agent) VALUES "
        "(:admin_id,:action,'presentation',1,:timestamp,'system',:details,'სისტემური ადმინი',"
        "'admin@magti.ge','Local presentation baseline','127.0.0.1','Magti Presentation Seeder/1.0')",
        admin_id=admin_id,
        action=MARKER_ACTION,
        timestamp=now,
        details=_json(details),
    )
    return details


def seed_baseline() -> dict[str, Any]:
    inventory = load_and_validate_inventory()
    env = _environment()
    if env["demo_password"] != DEMO_PASSWORD_DEFAULT:
        raise PresentationSafetyError(
            "PRESENTATION_DEMO_PASSWORD must equal the approved local-only password MagtiDemo2026!"
        )
    connection = connect_oracle()
    cursor = connection.cursor()
    source: sqlite3.Connection | None = None
    try:
        verify_oracle_context(cursor)
        existing_marker = _marker_details(cursor)
        if existing_marker is not None:
            if existing_marker.get("marker_version") != MARKER_VERSION:
                raise PresentationSafetyError("Presentation marker version does not match this seeder")
            if existing_marker.get("source_database_sha256") != inventory.database_sha256:
                raise PresentationSafetyError("Presentation marker source checksum differs from mounted source")
            report = verify_database(cursor, inventory=inventory, include_passwords=True)
            report["mode"] = "verification-only"
            connection.rollback()
            _write_report("verification-report.json", report)
            print("Presentation marker already exists; verification-only mode completed without writes.")
            return report

        assert_fresh_target(cursor)
        now = _now()
        source, articles, histories, valid_assets, sanitizer_reports = _source_bundle()
        password_hash = _PASSWORDS.hash(env["demo_password"])
        admin_id, content_id, users = _create_central_users(cursor, now, password_hash)
        _create_organisation(cursor, now, admin_id, password_hash, users)
        category_ids = _import_categories(cursor, source)
        _import_stored_files(cursor, valid_assets, content_id, now)
        article_ids, article_rows, search_records = _import_articles(
            cursor,
            source,
            articles,
            histories,
            category_ids,
            valid_assets,
            content_id,
            sanitizer_reports,
        )
        search_records.extend(_create_demo_cms_articles(
            cursor, now, content_id, next(iter(category_ids.values()))
        ))
        news_video_records, extra_tag_records = _import_news_and_videos(
            cursor, source, valid_assets, content_id
        )
        search_records.extend(news_video_records)
        _index_tags(cursor, article_ids, article_rows, extra_tag_records, now)
        indexed_trigrams = _index_search(cursor, search_records)
        if indexed_trigrams < 1000:
            raise PresentationSafetyError(f"Only {indexed_trigrams} search trigrams were generated")
        _create_quizzes(cursor, article_ids)
        _seed_knowledge_activity(cursor, source, now, users, article_ids, article_rows)
        reading_ids = _seed_compliance(
            cursor, now, users, article_ids, article_rows, admin_id
        )
        _seed_broadcasts(cursor, now, admin_id, content_id)
        _seed_audit_events(cursor, now, users, article_ids, article_rows, admin_id, content_id)
        counts = _assert_baseline_counts(cursor)
        marker = _insert_marker(cursor, now, admin_id, inventory, counts)

        # All constraints and the audit trigger have run at this point.  This is
        # the only commit in the loader: any exception above rolls back every row.
        connection.commit()
        report = {
            "mode": "seeded",
            "source": inventory.as_dict(),
            "counts": {**counts, "audit_logs": counts["audit_logs"] + 1},
            "marker": marker,
            "required_reading_target_ids": reading_ids,
            "sanitization": [sanitizer_reports[key].as_dict() for key in sorted(sanitizer_reports)],
        }
        _write_report("sanitization-report.json", {
            "source_database_sha256": inventory.database_sha256,
            "articles": report["sanitization"],
        })
        _write_report("seed-report.json", report)
        print("Presentation baseline committed successfully in one transaction.")
        return report
    except Exception:
        connection.rollback()
        raise
    finally:
        if source is not None:
            source.close()
        cursor.close()
        connection.close()


def _assert_audit_chain(cursor: oracledb.Cursor) -> dict[str, int]:
    broken = int(_scalar(
        cursor,
        "WITH calculated AS ("
        " SELECT a.id,a.prev_hash,a.row_hash,ROW_NUMBER() OVER (ORDER BY a.id) rn,"
        " LAG(a.row_hash) OVER (ORDER BY a.id) expected_prev,"
        " LOWER(RAWTOHEX(STANDARD_HASH(audit_logs_canonical_string("
        " a.id,a.prev_hash,a.admin_id,a.action,a.item_type,a.item_id,a.timestamp,a.category,a.details,"
        " a.admin_name_snapshot,a.admin_email_snapshot,a.item_name_snapshot,a.ip_address,a.user_agent),"
        " 'SHA256'))) calculated_hash FROM audit_logs a"
        ") SELECT COUNT(*) FROM calculated WHERE row_hash IS NULL OR row_hash <> calculated_hash "
        "OR (rn = 1 AND prev_hash IS NOT NULL) OR (rn > 1 AND NVL(prev_hash,'!') <> NVL(expected_prev,'!'))",
    ))
    if broken:
        raise PresentationSafetyError(f"Audit hash chain contains {broken} broken rows")
    tip_matches = int(_scalar(
        cursor,
        "SELECT COUNT(*) FROM audit_chain_state s WHERE s.id=1 AND s.tip_hash = "
        "(SELECT row_hash FROM audit_logs WHERE id=(SELECT MAX(id) FROM audit_logs))",
    ))
    if tip_matches != 1:
        raise PresentationSafetyError("audit_chain_state tip does not match the last audit row")
    synthetic_unhashed = int(_scalar(
        cursor,
        "SELECT COUNT(*) FROM audit_logs WHERE DBMS_LOB.INSTR(details, '\"synthetic\":true') > 0 "
        "AND row_hash IS NULL",
    ))
    if synthetic_unhashed:
        raise PresentationSafetyError(f"Found {synthetic_unhashed} unhashed synthetic audit rows")
    return {
        "rows": int(_scalar(cursor, "SELECT COUNT(*) FROM audit_logs")),
        "broken": broken,
        "synthetic_unhashed": synthetic_unhashed,
    }


def _verify_file_blobs(cursor: oracledb.Cursor) -> dict[str, Any]:
    digest = hashlib.sha256()
    count = 0
    total = 0
    cursor.execute("SELECT filename,byte_size,content FROM stored_files ORDER BY filename")
    for filename, byte_size, lob in cursor:
        payload = lob.read() if hasattr(lob, "read") else bytes(lob or b"")
        if len(payload) != int(byte_size):
            raise PresentationSafetyError(f"stored_files byte_size mismatch for {filename}")
        digest.update(str(filename).encode("utf-8"))
        digest.update(b"\0")
        digest.update(hashlib.sha256(payload).digest())
        count += 1
        total += len(payload)
    actual = {"count": count, "bytes": total, "manifest_sha256": digest.hexdigest()}
    expected = {
        "count": EXPECTED_ASSETS,
        "bytes": EXPECTED_ASSET_BYTES,
        "manifest_sha256": os.getenv(
            "PRESENTATION_ASSET_MANIFEST_SHA256",
            "3cab92b5f7ee35032d8b6e169c5da336e2a5959cd623a6bdb4e668a45c34e751",
        ),
    }
    if actual != expected:
        raise PresentationSafetyError(f"Oracle stored-file manifest mismatch: expected={expected}, actual={actual}")
    return actual


def _verify_references(cursor: oracledb.Cursor) -> dict[str, int]:
    checks = {
        "article_category": "SELECT COUNT(*) FROM articles a WHERE a.category_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.id=a.category_id)",
        "article_author": "SELECT COUNT(*) FROM articles a WHERE a.author_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id=a.author_id)",
        "article_target": "SELECT COUNT(*) FROM article_target_departments t WHERE NOT EXISTS (SELECT 1 FROM articles a WHERE a.id=t.article_id)",
        "article_history": "SELECT COUNT(*) FROM article_history h WHERE NOT EXISTS (SELECT 1 FROM articles a WHERE a.id=h.article_id) OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id=h.updated_by)",
        "favorite_user": "SELECT COUNT(*) FROM favorites f WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id=f.user_id)",
        "read_status": "SELECT COUNT(*) FROM read_statuses s WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id=s.user_id) OR NOT EXISTS (SELECT 1 FROM required_readings r WHERE r.id=s.required_reading_id)",
        "quiz_answer": "SELECT COUNT(*) FROM quiz_answers a WHERE NOT EXISTS (SELECT 1 FROM quiz_questions q WHERE q.id=a.question_id)",
        "quiz_attempt": "SELECT COUNT(*) FROM quiz_attempts q WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id=q.user_id) OR (q.article_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM articles a WHERE a.id=q.article_id))",
        "leadership": "SELECT COUNT(*) FROM leadership_assignments l WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id=l.user_id) OR (l.team_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM teams t WHERE t.id=l.team_id)) OR (l.department_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM departments d WHERE d.id=l.department_id))",
        "tag_mapping": "SELECT COUNT(*) FROM tags_mapping m WHERE NOT EXISTS (SELECT 1 FROM tags t WHERE t.id=m.tag_id) OR (m.item_type='article' AND NOT EXISTS (SELECT 1 FROM articles a WHERE a.id=m.item_id)) OR (m.item_type='video' AND NOT EXISTS (SELECT 1 FROM video_instructions v WHERE v.id=m.item_id))",
        "trigrams": "SELECT COUNT(*) FROM search_trigrams s WHERE (s.entity_type='ARTICLE' AND NOT EXISTS (SELECT 1 FROM articles a WHERE a.id=s.entity_id)) OR (s.entity_type='NEWS' AND NOT EXISTS (SELECT 1 FROM news n WHERE n.id=s.entity_id)) OR (s.entity_type='VIDEO' AND NOT EXISTS (SELECT 1 FROM video_instructions v WHERE v.id=s.entity_id))",
    }
    results = {name: int(_scalar(cursor, query)) for name, query in checks.items()}
    broken = {name: count for name, count in results.items() if count}
    if broken:
        raise PresentationSafetyError(f"Broken references found: {_json(broken)}")
    return results


def _verify_credentials_in_database(cursor: oracledb.Cursor) -> dict[str, bool]:
    password = _environment()["demo_password"]
    results: dict[str, bool] = {}
    for email in PERSONAS:
        row = _one(cursor, "SELECT hashed_password,is_active FROM users WHERE email=:email", email=email)
        valid = bool(row and int(row[1]) == 1 and _PASSWORDS.verify(password, str(row[0])))
        wrong_rejected = bool(row and not _PASSWORDS.verify("Wrong-Magti-Demo-Password!", str(row[0])))
        results[email] = valid and wrong_rejected
    if not all(results.values()):
        raise PresentationSafetyError(f"Database credential verification failed: {_json(results)}")
    return results


def verify_database(
    cursor: oracledb.Cursor,
    *,
    inventory: SourceInventory,
    include_passwords: bool,
) -> dict[str, Any]:
    verify_oracle_context(cursor)
    marker = _marker_details(cursor)
    if marker is None:
        raise PresentationSafetyError("Presentation baseline marker is missing")
    if marker.get("marker_version") != MARKER_VERSION:
        raise PresentationSafetyError("Unexpected presentation marker version")
    if marker.get("source_database_sha256") != inventory.database_sha256:
        raise PresentationSafetyError("Presentation marker/source checksum mismatch")

    counts = _collect_counts(cursor)
    minima = {
        "users": 605, "teams": 15, "categories": 11, "articles": 125,
        "article_history": 135, "stored_files": 429, "news": 5,
        "video_instructions": 3, "article_view_logs": 4500, "search_logs": 1200,
        "favorites": 800, "required_readings": 6, "broadcasts": 4,
    }
    too_small = {table: {"minimum": minimum, "actual": counts[table]} for table, minimum in minima.items() if counts[table] < minimum}
    if too_small:
        raise PresentationSafetyError(f"Presentation dataset is incomplete: {_json(too_small)}")
    demo_count = int(_scalar(
        cursor,
        "SELECT COUNT(*) FROM articles WHERE title IN (:one,:two,:three)",
        one=DEMO_ARTICLE_TITLES[0], two=DEMO_ARTICLE_TITLES[1], three=DEMO_ARTICLE_TITLES[2],
    ))
    if demo_count != 3 or counts["articles"] != EXPECTED_ARTICLES + 3:
        raise PresentationSafetyError("Imported/demo article boundary is not 122 + 3")
    imported_history = counts["article_history"] - 3
    if imported_history != EXPECTED_ARTICLE_HISTORY:
        raise PresentationSafetyError(f"Imported history boundary is {imported_history}, expected 132")

    trigramless = int(_scalar(
        cursor,
        "SELECT COUNT(*) FROM articles a WHERE a.trashed_at IS NULL AND NOT EXISTS ("
        "SELECT 1 FROM search_trigrams s WHERE s.entity_type='ARTICLE' AND s.entity_id=a.id)",
    ))
    if trigramless:
        raise PresentationSafetyError(f"{trigramless} non-trashed articles have no search trigrams")
    failed_searches = int(_scalar(cursor, "SELECT COUNT(*) FROM search_logs WHERE has_results=0"))
    baseline_ratio = failed_searches / counts["search_logs"]
    if not 0.05 <= baseline_ratio <= 0.20:
        raise PresentationSafetyError(f"Failed-search ratio {baseline_ratio:.3f} is outside the presentation profile")

    read_total = counts["read_statuses"]
    read_count = int(_scalar(cursor, "SELECT COUNT(*) FROM read_statuses WHERE status='read'"))
    late_count = int(_scalar(
        cursor,
        "SELECT COUNT(*) FROM read_statuses s JOIN required_readings r ON r.id=s.required_reading_id "
        "WHERE s.status='read' AND s.read_at > r.due_date",
    ))
    if read_total == 0 or not 0.60 <= read_count / read_total <= 0.90:
        raise PresentationSafetyError("Compliance distribution does not contain the expected read/unread mix")

    return {
        "source": inventory.as_dict(),
        "counts": counts,
        "references": _verify_references(cursor),
        "files": _verify_file_blobs(cursor),
        "audit_chain": _assert_audit_chain(cursor),
        "credentials": _verify_credentials_in_database(cursor) if include_passwords else {},
        "compliance": {
            "total": read_total,
            "read": read_count,
            "unread": read_total - read_count,
            "late": late_count,
            "read_ratio": round(read_count / read_total, 4),
        },
        "failed_search_ratio": round(baseline_ratio, 4),
        "marker": marker,
    }


def _api_login(client: httpx.Client, email: str, password: str) -> str:
    response = client.post("/api/auth/login", json={"email": email, "password": password})
    if response.status_code != 200:
        raise PresentationSafetyError(
            f"API login failed for {email}: HTTP {response.status_code} {response.text[:300]}"
        )
    token = response.json().get("access_token")
    if not token:
        raise PresentationSafetyError(f"API login returned no access_token for {email}")
    return str(token)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _expect_status(
    client: httpx.Client,
    method: str,
    path: str,
    token: str,
    expected: int,
    **kwargs: Any,
) -> httpx.Response:
    response = client.request(method, path, headers=_auth(token), **kwargs)
    if response.status_code != expected:
        raise PresentationSafetyError(
            f"Expected {method} {path} -> {expected}, got {response.status_code}: {response.text[:400]}"
        )
    return response


def _verify_api_files(
    client: httpx.Client,
    token: str,
    asset_names: list[str],
) -> dict[str, Any]:
    digest = hashlib.sha256()
    total = 0
    for filename in asset_names:
        response = client.get(f"/uploads/{quote(filename, safe='')}", headers=_auth(token))
        if response.status_code != 200:
            raise PresentationSafetyError(
                f"Stored image {filename} is not served by the backend: HTTP {response.status_code}"
            )
        payload = response.content
        if not response.headers.get("content-type", "").lower().startswith("image/"):
            raise PresentationSafetyError(f"Stored image {filename} has non-image Content-Type")
        digest.update(filename.encode("utf-8"))
        digest.update(b"\0")
        digest.update(hashlib.sha256(payload).digest())
        total += len(payload)
    result = {"count": len(asset_names), "bytes": total, "manifest_sha256": digest.hexdigest()}
    if result["count"] != EXPECTED_ASSETS or result["bytes"] != EXPECTED_ASSET_BYTES:
        raise PresentationSafetyError(f"API image inventory is incomplete: {_json(result)}")
    if result["manifest_sha256"] != "3cab92b5f7ee35032d8b6e169c5da336e2a5959cd623a6bdb4e668a45c34e751":
        raise PresentationSafetyError("API image payload checksum differs from the approved source manifest")
    return result


def verify_api(
    cursor: oracledb.Cursor,
    source: sqlite3.Connection,
    valid_assets: set[str],
) -> dict[str, Any]:
    base_url = os.getenv("PRESENTATION_API_BASE_URL", API_BASE_URL_DEFAULT).rstrip("/")
    password = _environment()["demo_password"]
    tokens: dict[str, str] = {}
    with httpx.Client(base_url=base_url, timeout=httpx.Timeout(90.0), follow_redirects=False) as client:
        health = client.get("/api/health")
        if health.status_code != 200:
            raise PresentationSafetyError(f"Backend health failed: HTTP {health.status_code} {health.text[:300]}")

        # What "a wrong password is refused" means depends on how the stack
        # was configured, so this asserts the mode it is actually running in
        # rather than one fixed answer.
        #
        # ALLOW_DEV_LOGIN is "true" for this demo on purpose: the login
        # screen's persona picker sends no password, and corporate SSO is not
        # wired up. AuthenticationService.authenticate then bypasses the
        # password for its allow-list, gated on BOTH !isProduction() and
        # allow-dev-login, so it stays inert in production.
        #
        # This check used to demand 401 unconditionally. Against a stack with
        # dev login on, that is a property the stack was deliberately
        # configured not to have -- and it failed the whole verification on
        # the intended behaviour. It survived only because nothing had run a
        # full reset/verify since dev login was turned on.
        dev_login = os.getenv("ALLOW_DEV_LOGIN", "false").strip().lower() == "true"
        expected_wrong_password_status = 200 if dev_login else 401

        wrong_password_results: dict[str, int] = {}
        for email in PERSONAS:
            wrong = client.post(
                "/api/auth/login",
                json={"email": email, "password": "Wrong-Magti-Demo-Password!"},
            )
            wrong_password_results[email] = wrong.status_code
            if wrong.status_code != expected_wrong_password_status:
                detail = (
                    "was rejected despite ALLOW_DEV_LOGIN being on"
                    if dev_login
                    else "was not rejected"
                )
                raise PresentationSafetyError(
                    f"Wrong password {detail} for {email}: HTTP {wrong.status_code} "
                    f"(expected {expected_wrong_password_status})"
                )
            tokens[email] = _api_login(client, email, password)

        # With the password bypassed for the allow-list, the property still
        # worth proving is that the bypass is SCOPED. An address outside the
        # allow-list must still need a real password, or "dev login" would
        # mean "no authentication at all" and the loopback binding would be
        # the only thing left.
        #
        # These addresses are chosen to be outside every branch of that
        # allow-list on purpose. Do NOT probe an unknown "presentation."
        # address here: that prefix is JIT-provisioned, so the probe would
        # CREATE the account ("Test User " + the local part) and push the user
        # count past the baseline this same verification then checks.
        outside_allowlist_results: dict[str, int] = {}
        if dev_login:
            for email in OUTSIDE_DEV_LOGIN_ALLOWLIST:
                refused = client.post(
                    "/api/auth/login",
                    json={"email": email, "password": "Wrong-Magti-Demo-Password!"},
                )
                outside_allowlist_results[email] = refused.status_code
                if refused.status_code != 401:
                    raise PresentationSafetyError(
                        "Dev login is not scoped to its allow-list: "
                        f"{email} answered HTTP {refused.status_code}, expected 401"
                    )

        quiz_title = str(source.execute("SELECT title FROM articles WHERE id=129").fetchone()[0])
        quiz_article_id = int(_scalar(cursor, "SELECT id FROM articles WHERE title=:title", title=quiz_title))
        role_gates = {
            "operator_users": _expect_status(client, "GET", "/api/users", tokens["info@magti.ge"], 403).status_code,
            "operator_quiz_admin": _expect_status(
                client, "GET", f"/api/articles/{quiz_article_id}/quiz/admin", tokens["tech@magti.ge"], 403
            ).status_code,
            "content_quiz_admin": _expect_status(
                client, "GET", f"/api/articles/{quiz_article_id}/quiz/admin", tokens["content@magti.ge"], 200
            ).status_code,
            "manager_team_stats": _expect_status(
                client, "GET", "/api/manager/team-stats", tokens["manager@magti.ge"], 200
            ).status_code,
            "manager_org_admin": _expect_status(
                client, "GET", "/api/admin/org/structure", tokens["manager@magti.ge"], 403
            ).status_code,
            "system_admin_org": _expect_status(
                client, "GET", "/api/admin/org/structure", tokens["admin@magti.ge"], 200
            ).status_code,
        }

        manager_scope = _expect_status(
            client, "GET", "/api/manager/department-stats", tokens["manager@magti.ge"], 200
        ).json()
        scope_text = json.dumps(manager_scope, ensure_ascii=False)
        if "საინფორმაციო" in scope_text or "ოფისი" in scope_text:
            raise PresentationSafetyError("manager@magti.ge API response leaked a sibling department")
        if "ტექნიკური" not in scope_text:
            raise PresentationSafetyError("manager@magti.ge API response did not contain its technical scope")

        known_searches = {
            "tech@magti.ge": str(source.execute("SELECT title FROM articles WHERE id=129").fetchone()[0]),
            "info@magti.ge": str(source.execute("SELECT title FROM articles WHERE id=131").fetchone()[0]),
            "nino@magti.ge": str(source.execute("SELECT title FROM articles WHERE id=22").fetchone()[0]),
        }
        search_results: dict[str, bool] = {}
        for email, title in known_searches.items():
            response = _expect_status(
                client,
                "GET",
                "/api/search",
                tokens[email],
                200,
                params={"q": title},
            )
            found = any(str(item.get("title")) == title for item in response.json())
            search_results[email] = found
            if not found:
                raise PresentationSafetyError(f"Known Georgian title is not searchable for {email}: {title}")

        gate_candidate = _one(
            cursor,
            "SELECT u.id,u.email FROM users u WHERE u.role='operator' AND u.is_active=1 "
            "AND u.department LIKE 'ტექნიკური%' AND NOT EXISTS ("
            "SELECT 1 FROM quiz_attempts q WHERE q.user_id=u.id AND q.article_id=:article_id "
            "AND q.article_version=(SELECT version FROM articles WHERE id=:article_id) AND q.passed=1) "
            "ORDER BY u.email FETCH FIRST 1 ROW ONLY",
            article_id=quiz_article_id,
        )
        gate_result: dict[str, Any]
        if gate_candidate is None:
            gate_result = {"mode": "existing-evidence", "detail": "all eligible operators already passed"}
        else:
            candidate_id, candidate_email = int(gate_candidate[0]), str(gate_candidate[1])
            candidate_token = _api_login(client, candidate_email, password)
            denied = _expect_status(
                client,
                "POST",
                f"/api/articles/{quiz_article_id}/read-receipt",
                candidate_token,
                403,
            )
            quiz = _expect_status(
                client, "GET", f"/api/articles/{quiz_article_id}/quiz", candidate_token, 200
            ).json()
            answers = {
                str(question["id"]): question["answers"][0]["id"]
                for question in quiz.get("questions", [])
            }
            attempt = _expect_status(
                client,
                "POST",
                f"/api/articles/{quiz_article_id}/quiz/attempt",
                candidate_token,
                200,
                json={"answers": answers},
            ).json()
            if not attempt.get("passed"):
                raise PresentationSafetyError("Correct presentation quiz answers did not produce a pass")
            acknowledged = _expect_status(
                client,
                "POST",
                f"/api/articles/{quiz_article_id}/read-receipt",
                candidate_token,
                200,
            )
            bridge = int(_scalar(
                cursor,
                "SELECT COUNT(*) FROM article_read_receipts rr JOIN read_statuses rs ON rs.user_id=rr.operator_id "
                "JOIN required_readings r ON r.id=rs.required_reading_id "
                "WHERE rr.operator_id=:user_id AND rr.article_id_snapshot=:article_id "
                "AND rr.article_version=(SELECT version FROM articles WHERE id=:article_id) "
                "AND r.item_type='article' AND r.item_id=:article_id AND rs.status='read' "
                "AND rs.read_at IS NOT NULL",
                user_id=candidate_id,
                article_id=quiz_article_id,
            ))
            if bridge != 1:
                raise PresentationSafetyError("Read receipt/read status bridge is inconsistent after quiz pass")
            gate_result = {
                "mode": "live",
                "email": candidate_email,
                "pre_pass_status": denied.status_code,
                "quiz_passed": True,
                "acknowledgment_status": acknowledged.status_code,
                "receipt_status_bridge": bridge,
            }

        files = _verify_api_files(client, tokens["admin@magti.ge"], sorted(valid_assets))
        chain_after_api = _assert_audit_chain(cursor)
        return {
            "health": health.json(),
            "correct_logins": {email: True for email in tokens},
            "dev_login": dev_login,
            "wrong_password_statuses": wrong_password_results,
            "outside_allowlist_statuses": outside_allowlist_results,
            "role_gates": role_gates,
            "manager_scope": "technical-only",
            "known_searches": search_results,
            "quiz_gate": gate_result,
            "files": files,
            "audit_chain_after_api": chain_after_api,
        }


def run_verification() -> dict[str, Any]:
    inventory = load_and_validate_inventory()
    database_path, _uploads_path, _artifact_dir = source_paths()
    source = open_source_database(database_path)
    valid_assets = set(referenced_asset_names(
        [str(row["content"] or "") for row in source_articles(source)]
        + [str(row["content"] or "") for row in source_histories(source)]
    ))
    connection = connect_oracle()
    cursor = connection.cursor()
    try:
        database_report = verify_database(cursor, inventory=inventory, include_passwords=True)
        api_report = verify_api(cursor, source, valid_assets)
        final_chain = _assert_audit_chain(cursor)
        report = {
            "verified_at": _now().isoformat(),
            "database": database_report,
            "api": api_report,
            "final_audit_chain": final_chain,
        }
        _write_report("verification-report.json", report)
        print("Presentation database, APIs, credentials, role gates, files and audit chain verified.")
        return report
    finally:
        connection.rollback()
        cursor.close()
        connection.close()
        source.close()


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs="?", choices=("seed", "verify", "inventory"), default="seed")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.command == "inventory":
            inventory = load_and_validate_inventory()
            print(json.dumps(inventory.as_dict(), ensure_ascii=False, indent=2, sort_keys=True))
        elif args.command == "verify":
            run_verification()
        else:
            seed_baseline()
        return 0
    except (PresentationSafetyError, oracledb.Error, httpx.HTTPError, sqlite3.Error) as error:
        print(f"PRESENTATION ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
