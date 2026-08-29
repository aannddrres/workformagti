"""Seed the eleven named UAT accounts for the WS6-02 acceptance run.

Why this exists as its own script rather than reusing an API or the demo
seeder:

  * ``POST /api/users`` deliberately answers 403 -- accounts come from
    Active Directory synchronisation, so there is no create-user API to
    call, by design (UserController.java:474-484).
  * Signing in with an unknown e-mail *does* create an account, but
    ``AuthenticationService.jitProvision`` gives every unlisted address
    role ``operator`` and department ``"Support"``. That English
    placeholder matches none of the Georgian departments content is
    targeted at, so such a user opens a portal that is almost entirely
    empty. This project has already lost time to exactly that trap once.
  * The demo seeder builds a whole 602-person organisation from the
    approved SQLite baseline and refuses to run against a database that
    already has one. UAT accounts have to layer on top of that, not
    replace it.

The accounts are chosen to make scoping falsifiable rather than to look
plausible -- see ACCOUNTS below for what each one is for.

Idempotent: re-running updates the existing rows instead of duplicating
them, so it is safe to run again after a partial failure.
"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import oracledb
from passlib.context import CryptContext

EXPECTED_DSN = "oracle:1521/XEPDB1"
EXPECTED_ORACLE_USER = "magti_app"
EXPECTED_CONFIRMATION = "LOCAL_ONLY_MAGTI_UAT_V1"

# Same permission sets the demo seeder writes (seed_oracle_demo.py:72-83),
# duplicated rather than imported because that module lives in a different
# image and pulling it in would drag the whole presentation toolchain along.
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


@dataclass(frozen=True)
class UatAccount:
    email: str
    name: str
    role: str
    department: str
    position: str
    active: bool
    purpose: str


# The department strings must match the organisation the demo seeder built,
# em dash and spacing included ("ტექნიკური — ჯგუფი 02"). A near-miss here
# produces an account that silently sees nothing, which is the failure this
# script exists to prevent.
ACCOUNTS: tuple[UatAccount, ...] = (
    UatAccount(
        "uat.operator1@magti.ge", "ნინო ბერიძე", "operator",
        "ოფისი — ჯგუფი 01", "ოპერატორი", True,
        "baseline operator; pairs with uat.newbie in the same group",
    ),
    UatAccount(
        "uat.operator2@magti.ge", "გიორგი კაპანაძე", "operator",
        "ტექნიკური — ჯგუფი 02", "ოპერატორი", True,
        "different department from operator1; the leak test",
    ),
    UatAccount(
        "uat.operator3@magti.ge", "თამარ ლომიძე", "operator",
        "საინფორმაციო — ჯგუფი 03", "ოპერატორი", True,
        "third department; three-way isolation",
    ),
    UatAccount(
        "uat.operator4@magti.ge", "ლევან ჩხეიძე", "operator",
        "ტექნიკური — ჯგუფი 02", "ოპერატორი", True,
        "same group as operator2; both must see an identical library",
    ),
    UatAccount(
        "uat.newbie@magti.ge", "ანა მაისურაძე", "operator",
        "ოფისი — ჯგუფი 01", "ოპერატორი", True,
        "no reading history, so mandatory reading can be walked from zero",
    ),
    UatAccount(
        "uat.manager.dept@magti.ge", "დავით წერეთელი", "manager",
        "ტექნიკური", "დეპარტამენტის ხელმძღვანელი", True,
        "parent-level manager; must see the whole technical subtree",
    ),
    UatAccount(
        "uat.manager.group@magti.ge", "მარიამ ჯანელიძე", "manager",
        "ოფისი — ჯგუფი 01", "ჯგუფის ლიდერი", True,
        "sub-group manager; must see only their own group",
    ),
    UatAccount(
        "uat.content@magti.ge", "სოფო გელაშვილი", "content_admin",
        "კონტენტი", "კონტენტ-ადმინი", True,
        "content lifecycle: create, edit, version, archive, trash",
    ),
    UatAccount(
        "uat.admin@magti.ge", "ზურაბ ნოზაძე", "admin",
        "ადმინისტრაცია", "სისტემური ადმინისტრატორი", True,
        "primary system admin for the run",
    ),
    UatAccount(
        "uat.admin2@magti.ge", "ეკა ხურციძე", "admin",
        "ადმინისტრაცია", "სისტემური ადმინისტრატორი", True,
        "second admin, so last-active-admin protection is testable without "
        "locking the tester out",
    ),
    UatAccount(
        "uat.inactive@magti.ge", "ირაკლი ბოლქვაძე", "operator",
        "ტექნიკური — ჯგუფი 02", "ოპერატორი", False,
        "deactivated on purpose; signing in as this account must fail",
    ),
)

# The password these accounts are hashed with.
#
# "Any password is accepted while dev login is on" is only true for the six
# addresses hardcoded in AuthenticationService.DEV_TEST_EMAILS -- every other
# account, including these, goes through a real bcrypt comparison. Adding
# eleven UAT addresses to that production allow-list would widen a
# password-less sign-in path in shipped code, so instead these accounts are
# hashed with the exact string the login screen already sends:
# AuthService.loginPersona posts 'local-persona' as the password
# (angular-frontend/src/app/core/auth/auth.service.ts:41).
#
# The result for the tester is what was asked for -- type an e-mail, press
# enter, no password to remember -- while the backend still performs a real
# credential check and production source is untouched. If that constant ever
# changes on the Angular side, these accounts stop authenticating and this
# comment is where to look.
PERSONA_PASSWORD = "local-persona"


class UatSafetyError(RuntimeError):
    """Raised when the target does not look like a local throwaway database."""


def assert_local_target(app_env: str, dsn: str, user: str, confirmation: str) -> None:
    if app_env.strip().lower() != "development":
        raise UatSafetyError("UAT seeding requires APP_ENV=development exactly")
    normalized = dsn.removeprefix("jdbc:oracle:thin:@").removeprefix("//")
    if normalized != EXPECTED_DSN:
        raise UatSafetyError(f"Refusing Oracle DSN {normalized!r}; expected {EXPECTED_DSN!r}")
    if user.strip().lower() != EXPECTED_ORACLE_USER:
        raise UatSafetyError(f"Refusing Oracle user {user!r}; expected {EXPECTED_ORACLE_USER!r}")
    if confirmation != EXPECTED_CONFIRMATION:
        raise UatSafetyError("Missing exact local UAT confirmation marker")


def environment() -> dict[str, str]:
    missing = [
        key for key in ("ORACLE_DB_DSN", "ORACLE_DB_USER", "ORACLE_DB_PASSWORD")
        if not os.getenv(key)
    ]
    if missing:
        raise UatSafetyError(f"Missing required environment: {', '.join(missing)}")
    return {
        "app_env": os.getenv("APP_ENV", ""),
        "dsn": os.environ["ORACLE_DB_DSN"],
        "user": os.environ["ORACLE_DB_USER"],
        "password": os.environ["ORACLE_DB_PASSWORD"],
        "confirmation": os.getenv("UAT_SEED_CONFIRM", ""),
    }


def assert_population_present(cursor: oracledb.Cursor) -> None:
    """The demo organisation must already be seeded.

    Without it the departments these accounts name do not exist, every one of
    them would see an empty portal, and the run would measure nothing.
    """
    cursor.execute("SELECT COUNT(*) FROM users")
    total = int(cursor.fetchone()[0])
    if total < 100:
        raise UatSafetyError(
            f"Only {total} users present. Run the demo population seeder first "
            "(uat.ps1 prepare), then seed these accounts."
        )


def upsert(cursor: oracledb.Cursor, account: UatAccount, password_hash: str, now: datetime) -> str:
    permissions = json.dumps(ROLE_PERMISSIONS[account.role], ensure_ascii=False)
    cursor.execute("SELECT id FROM users WHERE LOWER(email) = :email", email=account.email.lower())
    row = cursor.fetchone()
    if row:
        cursor.execute(
            "UPDATE users SET name=:name, department=:department, position=:position, "
            "role=:role, is_active=:is_active, permissions=:permissions, team_id=NULL, "
            "hashed_password=:hashed_password "
            "WHERE id=:id",
            hashed_password=password_hash,
            name=account.name,
            department=account.department,
            position=account.position,
            role=account.role,
            is_active=1 if account.active else 0,
            permissions=permissions,
            id=int(row[0]),
        )
        return "updated"
    cursor.execute(
        "INSERT INTO users (email,name,department,position,role,is_active,last_active,"
        "hashed_password,permissions) "
        "VALUES (:email,:name,:department,:position,:role,:is_active,:last_active,"
        ":hashed_password,:permissions)",
        email=account.email,
        name=account.name,
        department=account.department,
        position=account.position,
        role=account.role,
        is_active=1 if account.active else 0,
        last_active=now - timedelta(minutes=5),
        hashed_password=password_hash,
        permissions=permissions,
    )
    return "created"


def run() -> int:
    env = environment()
    assert_local_target(env["app_env"], env["dsn"], env["user"], env["confirmation"])

    password_hash = CryptContext(schemes=["bcrypt"], deprecated="auto").hash(PERSONA_PASSWORD)
    now = datetime.now(timezone.utc)

    connection = oracledb.connect(user=env["user"], password=env["password"], dsn=env["dsn"])
    try:
        with connection.cursor() as cursor:
            assert_population_present(cursor)
            results = [(account, upsert(cursor, account, password_hash, now)) for account in ACCOUNTS]
        connection.commit()
    finally:
        connection.close()

    width = max(len(a.email) for a in ACCOUNTS)
    print("UAT accounts")
    print("-" * (width + 46))
    for account, action in results:
        state = "" if account.active else "  [INACTIVE]"
        print(f"{account.email:<{width}}  {account.role:<14} {action:<8}{state}")
    print("-" * (width + 46))
    print(f"{len(results)} accounts ready. Sign in from /login by e-mail; no password to type.")
    return 0


def main() -> int:
    try:
        return run()
    except (UatSafetyError, oracledb.Error) as error:
        print(f"UAT account seeding failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
