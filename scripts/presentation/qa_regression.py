from __future__ import annotations

import json
import os
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import httpx

from common import PresentationSafetyError, assert_local_environment
from seed_oracle_demo import (
    PERSONAS,
    _assert_audit_chain,
    _environment,
    _marker_details,
    connect_oracle,
    verify_oracle_context,
)


@dataclass(frozen=True)
class AccessCase:
    name: str
    persona: str | None
    method: str
    path: str
    expected: int


@dataclass(frozen=True)
class AccessResult:
    name: str
    persona: str
    method: str
    path: str
    expected: int
    actual: int
    passed: bool
    duration_ms: int


ACCESS_CASES = (
    AccessCase("anonymous profile is rejected", None, "GET", "/api/users/me", 401),
    AccessCase("operator can list visible articles", "info@magti.ge", "GET", "/api/articles", 200),
    AccessCase("operator cannot list users", "info@magti.ge", "GET", "/api/users", 403),
    AccessCase("operator cannot read manager stats", "info@magti.ge", "GET", "/api/manager/team-stats", 403),
    AccessCase("operator cannot read audit", "info@magti.ge", "GET", "/api/audit-logs", 403),
    AccessCase("operator cannot administer organisation", "info@magti.ge", "GET", "/api/admin/org/structure", 403),
    AccessCase("manager can read team stats", "manager@magti.ge", "GET", "/api/manager/team-stats", 200),
    AccessCase("manager can read department stats", "manager@magti.ge", "GET", "/api/manager/department-stats", 200),
    AccessCase("manager can read scoped audit", "manager@magti.ge", "GET", "/api/audit-logs", 200),
    AccessCase("manager cannot list users", "manager@magti.ge", "GET", "/api/users", 403),
    AccessCase("manager cannot administer organisation", "manager@magti.ge", "GET", "/api/admin/org/structure", 403),
    AccessCase("content admin can list stale articles", "content@magti.ge", "GET", "/api/admin/articles/stale", 200),
    AccessCase("content admin can read audit", "content@magti.ge", "GET", "/api/audit-logs", 200),
    AccessCase("content admin cannot list users", "content@magti.ge", "GET", "/api/users", 403),
    AccessCase("content admin cannot administer organisation", "content@magti.ge", "GET", "/api/admin/org/structure", 403),
    AccessCase("system admin can list users", "admin@magti.ge", "GET", "/api/users", 200),
    AccessCase("system admin can administer organisation", "admin@magti.ge", "GET", "/api/admin/org/structure", 200),
    AccessCase("system admin can read audit", "admin@magti.ge", "GET", "/api/audit-logs", 200),
    AccessCase("system admin can inspect access diff", "admin@magti.ge", "GET", "/api/admin/access-diff", 200),
)


def _request(
    client: httpx.Client,
    method: str,
    path: str,
    token: str | None,
    **kwargs: Any,
) -> httpx.Response:
    headers = dict(kwargs.pop("headers", {}))
    if token:
        headers["Authorization"] = f"Bearer {token}"
    else:
        # Login responses also set an auth cookie. An anonymous matrix case
        # must not accidentally inherit the last persona's browser session.
        headers["Cookie"] = ""
    return client.request(method, path, headers=headers, **kwargs)


def _login(client: httpx.Client, email: str, password: str) -> str:
    response = client.post("/api/auth/login", json={"email": email, "password": password})
    if response.status_code != 200:
        raise PresentationSafetyError(
            f"QA login failed for {email}: HTTP {response.status_code} {response.text[:300]}"
        )
    token = response.json().get("access_token")
    if not token:
        raise PresentationSafetyError(f"QA login returned no access token for {email}")
    return str(token)


def _expect(response: httpx.Response, expected: int, context: str) -> None:
    if response.status_code != expected:
        raise PresentationSafetyError(
            f"{context}: expected HTTP {expected}, got {response.status_code}: {response.text[:500]}"
        )


def _user(client: httpx.Client, admin_token: str, email: str) -> dict[str, Any]:
    response = _request(client, "GET", "/api/users", admin_token)
    _expect(response, 200, "list users for permission test")
    matches = [row for row in response.json() if str(row.get("email", "")).lower() == email.lower()]
    if len(matches) != 1:
        raise PresentationSafetyError(f"Expected exactly one user row for {email}, found {len(matches)}")
    return dict(matches[0])


def _override_state(user: dict[str, Any], permission: str) -> str:
    for override in user.get("permission_overrides", []):
        if override.get("permission") == permission:
            return str(override.get("state"))
    return "INHERIT"


def _set_override(
    client: httpx.Client,
    admin_token: str,
    user: dict[str, Any],
    permission: str,
    state: str,
) -> dict[str, Any]:
    response = _request(
        client,
        "PUT",
        f"/api/users/{int(user['id'])}/permissions",
        admin_token,
        json={
            "lock_version": int(user["lock_version"]),
            "overrides": [{"permission": permission, "state": state}],
        },
    )
    _expect(response, 200, f"set {permission}={state} for {user['email']}")
    return dict(response.json())


def _effective_access(client: httpx.Client, token: str) -> dict[str, Any]:
    response = _request(client, "GET", "/api/me/effective-access", token)
    _expect(response, 200, "read effective access")
    return dict(response.json())


def _run_access_cases(client: httpx.Client, tokens: dict[str, str]) -> list[AccessResult]:
    results: list[AccessResult] = []
    for case in ACCESS_CASES:
        started = time.perf_counter()
        response = _request(client, case.method, case.path, tokens.get(case.persona or ""))
        duration_ms = round((time.perf_counter() - started) * 1000)
        results.append(AccessResult(
            name=case.name,
            persona=case.persona or "anonymous",
            method=case.method,
            path=case.path,
            expected=case.expected,
            actual=response.status_code,
            passed=response.status_code == case.expected,
            duration_ms=duration_ms,
        ))
    failures = [result for result in results if not result.passed]
    if failures:
        rendered = "; ".join(
            f"{result.name}: expected {result.expected}, got {result.actual}" for result in failures
        )
        raise PresentationSafetyError(f"Static access matrix failed: {rendered}")
    return results


def _assert_manager_scope(client: httpx.Client, manager_token: str) -> dict[str, Any]:
    response = _request(client, "GET", "/api/manager/department-stats", manager_token)
    _expect(response, 200, "manager department scope")
    rendered = json.dumps(response.json(), ensure_ascii=False)
    leaked = [department for department in ("საინფორმაციო", "ოფისი") if department in rendered]
    if leaked or "ტექნიკური" not in rendered:
        raise PresentationSafetyError(
            f"manager scope is not technical-only; leaked={leaked}, has_technical={'ტექნიკური' in rendered}"
        )
    return {"expected": "technical-only", "leaked_departments": leaked, "passed": True}


def _assert_article_isolation(
    client: httpx.Client,
    cursor: Any,
    tokens: dict[str, str],
) -> dict[str, Any]:
    cursor.execute(
        "SELECT a.id FROM articles a WHERE a.status='published' AND a.is_draft=0 "
        "AND EXISTS (SELECT 1 FROM article_target_departments t WHERE t.article_id=a.id AND t.department='ტექნიკური') "
        "AND NOT EXISTS (SELECT 1 FROM article_target_departments t WHERE t.article_id=a.id "
        "AND t.department IN ('All','საინფორმაციო')) ORDER BY a.id FETCH FIRST 1 ROW ONLY"
    )
    row = cursor.fetchone()
    if row is None:
        raise PresentationSafetyError("No technical-only article exists for the QA isolation test")
    article_id = int(row[0])
    allowed = _request(client, "GET", f"/api/articles/{article_id}", tokens["tech@magti.ge"])
    hidden = _request(client, "GET", f"/api/articles/{article_id}", tokens["info@magti.ge"])
    _expect(allowed, 200, "technical operator reads technical-only article")
    _expect(hidden, 404, "information operator cannot read technical-only article")
    return {"article_id": article_id, "allowed_status": 200, "cross_department_status": 404, "passed": True}


def _assert_permission_transitions(
    client: httpx.Client,
    tokens: dict[str, str],
) -> dict[str, Any]:
    admin_token = tokens["admin@magti.ge"]
    scenarios = (
        ("manager@magti.ge", "system.audit", "DENY", 200, 403),
        ("info@magti.ge", "system.audit", "ALLOW", 403, 200),
    )
    results: list[dict[str, Any]] = []
    for email, permission, changed_state, before_status, changed_status in scenarios:
        user = _user(client, admin_token, email)
        original_state = _override_state(user, permission)
        token = tokens[email]
        before = _request(client, "GET", "/api/audit-logs", token)
        _expect(before, before_status, f"{email} audit access before override")
        changed = False
        try:
            user = _set_override(client, admin_token, user, permission, changed_state)
            changed = True
            access = _effective_access(client, token)
            holds_permission = permission in access.get("permissions", [])
            expected_holds = changed_state == "ALLOW"
            if holds_permission != expected_holds:
                raise PresentationSafetyError(
                    f"{email} effective permissions disagree with {permission}={changed_state}"
                )
            after = _request(client, "GET", "/api/audit-logs", token)
            _expect(after, changed_status, f"{email} audit access after override")
        finally:
            if changed:
                current = _user(client, admin_token, email)
                _set_override(client, admin_token, current, permission, original_state)

        restored = _request(client, "GET", "/api/audit-logs", token)
        _expect(restored, before_status, f"{email} audit access after restore")
        results.append({
            "email": email,
            "permission": permission,
            "original_state": original_state,
            "tested_state": changed_state,
            "before_status": before.status_code,
            "changed_status": changed_status,
            "restored_status": restored.status_code,
            "same_token_observed_change": True,
            "passed": True,
        })
    return {"scenarios": results, "passed": True}


def run() -> dict[str, Any]:
    env = _environment()
    assert_local_environment(
        app_env=env["app_env"],
        dsn=env["dsn"],
        user=env["user"],
        confirmation=env["confirmation"],
    )
    run_id = os.getenv("PRESENTATION_QA_RUN_ID", "manual")
    artifact_root = Path(os.getenv("PRESENTATION_ARTIFACT_DIR", "/artifacts"))
    output_dir = artifact_root / "tests" / run_id
    output_dir.mkdir(parents=True, exist_ok=True)

    connection = connect_oracle()
    try:
        cursor = connection.cursor()
        verify_oracle_context(cursor)
        if _marker_details(cursor) is None:
            raise PresentationSafetyError("QA access tests refuse to run without the presentation marker")

        base_url = os.getenv("PRESENTATION_API_BASE_URL", "http://backend:8080").rstrip("/")
        with httpx.Client(base_url=base_url, timeout=httpx.Timeout(90.0), follow_redirects=False) as client:
            health = client.get("/api/health")
            _expect(health, 200, "QA backend health")
            tokens = {email: _login(client, email, env["demo_password"]) for email in PERSONAS}
            # Persona checks below use explicit Bearer tokens. Clearing the
            # login cookie keeps every matrix row isolated from the previous
            # login and makes the anonymous row genuinely unauthenticated.
            client.cookies.clear()
            role_claims = {email: _effective_access(client, token) for email, token in tokens.items()}
            access_results = _run_access_cases(client, tokens)
            manager_scope = _assert_manager_scope(client, tokens["manager@magti.ge"])
            article_isolation = _assert_article_isolation(client, cursor, tokens)
            permission_transitions = _assert_permission_transitions(client, tokens)

        connection.commit()
        audit_chain = _assert_audit_chain(cursor)
        report = {
            "run_id": run_id,
            "generated_at_epoch": int(time.time()),
            "summary": {
                "static_access_cases": len(access_results),
                "static_access_passed": sum(result.passed for result in access_results),
                "permission_transition_cases": len(permission_transitions["scenarios"]),
                "passed": True,
            },
            "role_claims": role_claims,
            "access_cases": [asdict(result) for result in access_results],
            "manager_scope": manager_scope,
            "article_isolation": article_isolation,
            "permission_transitions": permission_transitions,
            "audit_chain": audit_chain,
        }
        (output_dir / "access-matrix.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(
            f"QA access matrix passed: {len(access_results)} static cases, "
            f"{len(permission_transitions['scenarios'])} live permission transitions."
        )
        return report
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def main() -> int:
    try:
        run()
        return 0
    except Exception as error:
        print(f"QA access regression failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
