"""Shared helpers for the production-readiness checks in scripts/qa/.

Every check talks to a backend started by ``scripts/qa/qa-backend.sh`` on
127.0.0.1 and to the throwaway ``MAGTI_QA`` schema, and to nothing else.
Two guards make that hard to get wrong:

* ``base_url()`` refuses any host but 127.0.0.1 and the ports of the owner's
  own stacks (8080, 8081, 8082).
* ``db()`` refuses any Oracle user but ``MAGTI_QA``.

127.0.0.1, not localhost: on this machine "localhost" resolves to ::1 first,
which TRUSTED_PROXIES does not trust, so every request would share one
login-throttle bucket and the checks would measure the throttle instead.
"""

from __future__ import annotations

import json
import os
import sys
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

import httpx

TBILISI = timezone(timedelta(hours=4))
FORBIDDEN_PORTS = {8080, 8081, 8082}
QA_USER = "MAGTI_QA"
PASSWORD = os.environ.get("QA_PASSWORD", "x")

# Departments as the local personas carry them (run-and-verify skill).
TECH = "ტექნიკური"
INFO = "საინფორმაციო"


def base_url(port: int | None = None) -> str:
    url = os.environ.get("QA_BASE", "http://127.0.0.1:8090")
    if port is not None:
        url = f"http://127.0.0.1:{port}"
    parsed = urlparse(url)
    if parsed.hostname != "127.0.0.1":
        sys.exit(f"refusing {url}: QA checks only run against 127.0.0.1")
    if parsed.port in FORBIDDEN_PORTS:
        sys.exit(f"refusing {url}: port {parsed.port} belongs to another stack")
    return url.rstrip("/")


@contextmanager
def db():
    """A connection to MAGTI_QA on the local Oracle, and nowhere else."""
    import oracledb

    user = os.environ.get("ORACLE_DB_USER", QA_USER)
    if user.upper() != QA_USER:
        sys.exit(f"refusing Oracle user {user}: QA checks only touch {QA_USER}")
    conn = oracledb.connect(
        user=QA_USER,
        password=os.environ.get("ORACLE_DB_PASSWORD", "local_only_magti_qa_pw"),
        dsn=os.environ.get("QA_DSN", "localhost:1521/orclpdb1"),
    )
    try:
        yield conn
    finally:
        conn.close()


def now_tbilisi() -> datetime:
    return datetime.now(TBILISI)


def iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")


@dataclass
class Session:
    """One signed-in account. Calls carry the bearer token, as the e2e
    helpers do; the browser's cookie path is exercised by Playwright."""

    portal: "Portal"
    email: str
    token: str
    user: dict = field(default_factory=dict)

    def _h(self, extra=None):
        h = {"Authorization": f"Bearer {self.token}"}
        if extra:
            h.update(extra)
        return h

    def get(self, path, **kw):
        return self.portal.http.get(path, headers=self._h(kw.pop("headers", None)), **kw)

    def post(self, path, json_body=None, **kw):
        return self.portal.http.post(path, json=json_body, headers=self._h(kw.pop("headers", None)), **kw)

    def put(self, path, json_body=None, **kw):
        return self.portal.http.put(path, json=json_body, headers=self._h(kw.pop("headers", None)), **kw)

    def patch(self, path, json_body=None, **kw):
        return self.portal.http.patch(path, json=json_body, headers=self._h(kw.pop("headers", None)), **kw)

    def delete(self, path, **kw):
        return self.portal.http.delete(path, headers=self._h(kw.pop("headers", None)), **kw)

    def ok(self, method, path, json_body=None, **kw):
        """Call and insist on a 2xx; the body (JSON when there is one)."""
        fn = getattr(self, method)
        r = fn(path, json_body, **kw) if method in ("post", "put", "patch") else fn(path, **kw)
        if r.status_code >= 300:
            raise AssertionError(f"{self.email} {method.upper()} {path} -> {r.status_code} {r.text[:300]}")
        if not r.content:
            return None
        try:
            return r.json()
        except json.JSONDecodeError:
            return r.content


class Portal:
    def __init__(self, port: int | None = None, timeout: float = 30.0, client_ip: str | None = None):
        """``client_ip`` becomes X-Forwarded-For, which qa-backend.sh trusts
        from 127.0.0.1: one simulated person, one throttle bucket."""
        self.base = base_url(port)
        headers = {"X-Forwarded-For": client_ip} if client_ip else None
        self.http = httpx.Client(base_url=self.base, timeout=timeout, headers=headers)

    def health(self) -> int:
        try:
            return self.http.get("/api/health").status_code
        except httpx.HTTPError:
            return 0

    def wait_healthy(self, seconds: float = 180) -> None:
        end = time.time() + seconds
        while time.time() < end:
            if self.health() == 200:
                return
            time.sleep(1)
        raise TimeoutError(f"{self.base} not healthy after {seconds}s")

    def login(self, email: str, password: str = PASSWORD, retries: int = 8) -> Session:
        for attempt in range(retries):
            r = self.http.post("/api/auth/login", json={"email": email, "password": password})
            if r.status_code == 429 and attempt < retries - 1:
                # The window is one minute; waiting it out is the honest retry.
                time.sleep(int(r.headers.get("Retry-After", "15")))
                continue
            if r.status_code != 200:
                raise AssertionError(f"login {email} -> {r.status_code} {r.text[:200]}")
            token = r.json()["access_token"]
            s = Session(self, email, token)
            s.user = s.ok("get", "/api/users/me")
            return s
        raise AssertionError(f"login {email}: still throttled")


# --- content fixtures (payload shapes copied from e2e/helpers.ts) -----------

def ensure_category(admin: Session, name: str = "QA კატეგორია") -> int:
    for c in admin.ok("get", "/api/categories"):
        if c["name"] == name:
            return c["id"]
    return admin.ok("post", "/api/categories", {"name": name, "parent_id": None})["id"]


def article_body(title, category_id, departments, *, status="published", content=None,
                 published_at=None, quiz=False):
    return {
        "title": title,
        "content": content or f"<p>{title} — QA შინაარსი.</p>",
        "category_id": category_id,
        "tags": None,
        "target_departments": departments,
        "status": status,
        "published_at": published_at,
        "attachment_url": None,
        "audience_profile": "all",
        "visible_to_tech_info": True,
        "visible_to_service_center": False,
        "is_draft": False,
        "quiz_enabled": quiz,
    }


def create_article(editor: Session, title, category_id, departments, **kw) -> int:
    return editor.ok("post", "/api/articles", article_body(title, category_id, departments, **kw))["id"]


def set_quiz(editor: Session, article_id: int, question="სწორია?", answers=("კი", "არა"), correct=0):
    editor.ok("put", f"/api/articles/{article_id}/quiz/admin", {"questions": [{
        "id": None, "question_text": question, "position": 0,
        "answers": [{"id": None, "answer_text": a, "is_correct": i == correct, "position": i}
                    for i, a in enumerate(answers)],
    }]})


def require_reading(editor: Session, article_id: int, department: str, due: datetime,
                    item_type="article", priority="high") -> dict:
    return editor.ok("post", "/api/compliance/required-readings", {
        "item_type": item_type, "item_id": article_id, "target_department": department,
        "due_date": iso(due), "priority": priority,
    })


def create_operator(admin: Session, email: str, department: str, name: str | None = None) -> int:
    r = admin.post("/api/users", {
        "email": email, "name": name or email.split("@")[0], "department": department,
        "position": None, "phone": None, "role": "operator", "password": PASSWORD, "team_id": None,
    })
    if r.status_code in (200, 201):
        return r.json()["id"]
    if r.status_code in (400, 409) and "exist" in r.text.lower():
        for u in admin.ok("get", "/api/users", params={"search": email}):
            if u["email"] == email:
                return u["id"]
    raise AssertionError(f"create operator {email} -> {r.status_code} {r.text[:200]}")


# --- reporting ----------------------------------------------------------------

class Report:
    """Collects pass/fail lines and exits non-zero on any failure, so a check
    can never report success for something it did not prove."""

    def __init__(self, name: str):
        self.name = name
        self.rows: list[tuple[bool, str, str]] = []

    def check(self, ok: bool, label: str, detail: str = "") -> bool:
        self.rows.append((bool(ok), label, detail))
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] {label}" + (f" — {detail}" if detail else ""), flush=True)
        return bool(ok)

    def note(self, text: str) -> None:
        print(f"  [INFO] {text}", flush=True)

    def finish(self) -> int:
        failed = [r for r in self.rows if not r[0]]
        print(f"\n{self.name}: {len(self.rows) - len(failed)}/{len(self.rows)} passed", flush=True)
        return 1 if failed else 0
