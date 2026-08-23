"""
The guard on preflight's enum domains.

`preflight.ENUM_VALUES` is a hand-written copy of what two Java enums allow,
and a hand-written copy is worth exactly as much as whatever keeps it in
step. So these tests do not trust it: they parse
`java-backend/src/main/java/ge/magti/portal/domain/` and fail if either side
has moved. Adding a fifth AuditCategory, or changing the string Role
persists, breaks this test until preflight is told about it -- which is the
point, because the alternative is a preflight that passes while rejecting
values the application now accepts, or accepts values it now rejects.

`ENUM_DOMAINS` gets the same treatment for the column side: a table named
there must still exist in the plan's source schema.
"""
from __future__ import annotations

import os
import re

import pytest

from scripts.etl import preflight, spec

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOMAIN = os.path.join(
    REPO_ROOT, "java-backend", "src", "main", "java", "ge", "magti", "portal", "domain"
)

BODY = re.compile(r"\benum\s+(\w+)\s*\{(.*?)\}", re.DOTALL)
CONSTANT = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\s*(?:\(|,|;|$)", re.MULTILINE)
VALUED_CONSTANT = re.compile(r'^\s*[A-Z][A-Z0-9_]*\s*\(\s*"([^"]*)"', re.MULTILINE)


def enum_body(name: str) -> str:
    path = os.path.join(DOMAIN, f"{name}.java")
    if not os.path.exists(path):
        pytest.fail(f"preflight names {name} but {path} does not exist")
    with open(path, encoding="utf-8") as handle:
        source = handle.read()
    for found, body in BODY.findall(source):
        if found == name:
            return body
    pytest.fail(f"{path} holds no `enum {name}` declaration")
    raise AssertionError  # unreachable; keeps type checkers quiet


def test_audit_category_constants_match_preflight():
    """AuditCategory is persisted by @Enumerated(STRING), so the stored string
    is the constant name."""
    declared = tuple(CONSTANT.findall(enum_body("AuditCategory")))

    assert declared == preflight.ENUM_VALUES["AuditCategory"], (
        "AuditCategory changed in Java; preflight.ENUM_VALUES must change with it "
        f"(Java: {declared}, preflight: {preflight.ENUM_VALUES['AuditCategory']})"
    )


def test_role_persisted_strings_match_preflight():
    """Role is persisted by RoleConverter as Role.value(), NOT Role.name() --
    'admin', not 'SYSTEM_ADMIN'. Comparing against the constant names here
    would make preflight reject every real row in the users table."""
    declared = tuple(VALUED_CONSTANT.findall(enum_body("Role")))

    assert declared, "Role's constants no longer carry a persisted string literal"
    assert declared == preflight.ENUM_VALUES["Role"], (
        "Role's persisted values changed in Java; preflight.ENUM_VALUES must change with it "
        f"(Java: {declared}, preflight: {preflight.ENUM_VALUES['Role']})"
    )


def test_every_declared_domain_names_a_java_type_preflight_knows():
    for _table, _column, java_type, _nullable in preflight.ENUM_DOMAINS:
        assert java_type in preflight.ENUM_VALUES, (
            f"ENUM_DOMAINS names {java_type} but ENUM_VALUES has no allowed set for it"
        )


def test_every_declared_domain_names_a_column_the_plan_carries():
    """A domain check on a column the plan does not migrate is dead weight that
    reads as coverage."""
    planned = {table.source: table for table in spec.PLAN}
    for table, column, _java_type, _nullable in preflight.ENUM_DOMAINS:
        assert table in planned, f"ENUM_DOMAINS names {table}, which is not in spec.PLAN"
        sources = {col.source for col in planned[table].columns}
        assert column in sources, f"ENUM_DOMAINS names {table}.{column}, which the plan does not map"


def _source(tmp_path):
    from scripts.etl.db import SourceDb
    from tests.etl import legacy_source

    path = str(tmp_path / "legacy.db")
    legacy_source.seed(path)
    return SourceDb(f"sqlite:///{path}").connect()


def _named(checks, name):
    for check in checks:
        if check.name == name:
            return check
    pytest.fail(f"no check named {name} among {[c.name for c in checks]}")
    raise AssertionError  # unreachable


def test_the_clean_fixture_holds_no_out_of_domain_value(tmp_path):
    source = _source(tmp_path)
    try:
        checks = preflight.check_enum_domains(source, list(spec.PLAN))
    finally:
        source.close()

    assert all(check.ok for check in checks), [c.detail for c in checks if not c.ok]


def test_a_category_the_java_enum_rejects_blocks_the_cutover(tmp_path):
    """The exact shape of the defect this check exists for: a value written by
    a Python call site that supplied its own category instead of letting
    models.classify_audit_category() choose one. It migrates cleanly; the
    first Java read of the row throws."""
    source = _source(tmp_path)
    try:
        source._conn.execute("UPDATE audit_logs SET category = 'admin' WHERE id = 1")
        source._conn.commit()
        checks = preflight.check_enum_domains(source, list(spec.PLAN))
    finally:
        source.close()

    failed = _named(checks, "enum-domain:audit_logs.category")
    assert not failed.ok
    assert failed.affected == 1
    assert ("admin", 1) in failed.samples
    assert preflight.blocking(checks) == [failed], "an unreadable audit row must stop the load"


def test_a_null_role_blocks_the_cutover_but_a_null_category_does_not(tmp_path):
    """The two columns are nullable in the DDL and only one of them is
    nullable in practice -- JwtService dereferences getRole() unguarded, while
    a missing audit category is just a gap in the taxonomy."""
    source = _source(tmp_path)
    try:
        source._conn.execute("UPDATE audit_logs SET category = NULL WHERE id = 1")
        source._conn.execute("UPDATE users SET role = NULL WHERE id = 1")
        source._conn.commit()
        checks = preflight.check_enum_domains(source, list(spec.PLAN))
    finally:
        source.close()

    assert _named(checks, "enum-domain:audit_logs.category").ok
    role = _named(checks, "enum-domain:users.role")
    assert not role.ok and role.affected == 1
    assert (None, 1) in role.samples
