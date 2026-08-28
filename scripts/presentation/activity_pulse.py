"""Create a visible 24-operator activity wave through the real Spring APIs."""

from __future__ import annotations

import json
import os
import sys
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import httpx


API_BASE_URL = os.getenv("PRESENTATION_API_BASE_URL", "http://backend:8080").rstrip("/")
PASSWORD = os.getenv("PRESENTATION_DEMO_PASSWORD", "MagtiDemo2026!")
ARTIFACT_DIR = Path(os.getenv("PRESENTATION_ARTIFACT_DIR", "/artifacts"))
TBILISI = ZoneInfo("Asia/Tbilisi")

OPERATORS = (
    [("tech@magti.ge", "მომსახურების სტანდარტი")]
    + [(f"presentation.tech.g01.op{number:02d}@magti.ge", "მომსახურების სტანდარტი") for number in range(2, 9)]
    + [("info@magti.ge", "პერსონალურ მონაცემთა დაცვა")]
    + [(f"presentation.info.g01.op{number:02d}@magti.ge", "პერსონალურ მონაცემთა დაცვა") for number in range(2, 9)]
    + [("nino@magti.ge", "მობილური პორტირების პროცედურა")]
    + [(f"presentation.office.g01.op{number:02d}@magti.ge", "მობილური პორტირების პროცედურა") for number in range(2, 9)]
)


class PulseError(RuntimeError):
    pass


def login(client: httpx.Client, email: str) -> str:
    response = client.post("/api/auth/login", json={"email": email, "password": PASSWORD})
    if response.status_code != 200:
        raise PulseError(f"login {email}: HTTP {response.status_code} {response.text[:240]}")
    return str(response.json()["access_token"])


def auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def require(response: httpx.Response, label: str, expected: int = 200) -> httpx.Response:
    if response.status_code != expected:
        raise PulseError(f"{label}: HTTP {response.status_code} {response.text[:300]}")
    return response


def snapshot(client: httpx.Client, token: str) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for name, path in (
        ("activity", "/api/statistics/activity"),
        ("kpi", "/api/statistics/kpi"),
        ("popular_searches", "/api/statistics/popular-searches"),
    ):
        response = client.get(path, headers=auth(token))
        result[name] = response.json() if response.status_code == 200 else {"status": response.status_code}
    return result


def run_pulse() -> dict[str, Any]:
    pulse_id = str(uuid.uuid4())
    started_at = datetime.now(TBILISI).isoformat()
    counters = {
        "operators": 0,
        "searches": 0,
        "views": 0,
        "favorite_requests": 0,
        "failed_quiz_attempts": 0,
        "passed_quiz_attempts": 0,
        "acknowledgments": 0,
    }
    details: list[dict[str, Any]] = []

    with httpx.Client(base_url=API_BASE_URL, timeout=httpx.Timeout(60.0)) as client:
        health = client.get("/api/health")
        require(health, "backend health")
        admin_token = login(client, "admin@magti.ge")
        before = snapshot(client, admin_token)

        for index, (email, title) in enumerate(OPERATORS):
            token = login(client, email)
            headers = auth(token)
            results = require(
                client.get("/api/search", params={"q": title}, headers=headers),
                f"search {email}",
            ).json()
            counters["searches"] += 1
            exact = next((item for item in results if item.get("title") == title), None)
            if exact is None:
                raise PulseError(f"search {email}: exact article not found: {title}")
            article_id = int(exact["id"])

            require(client.post(f"/api/articles/{article_id}/view", headers=headers), f"view {email}")
            counters["views"] += 1
            require(
                client.post(
                    "/api/favorites",
                    headers=headers,
                    json={"item_type": "article", "item_id": article_id},
                ),
                f"favorite {email}",
            )
            counters["favorite_requests"] += 1

            quiz_response = client.get(f"/api/articles/{article_id}/quiz", headers=headers)
            if quiz_response.status_code == 200:
                questions = quiz_response.json().get("questions", [])
                correct = {str(question["id"]): question["answers"][0]["id"] for question in questions}
                if index % 3 == 0:
                    wrong = {
                        str(question["id"]): question["answers"][1]["id"]
                        for question in questions
                        if len(question.get("answers", [])) > 1
                    }
                    failed = require(
                        client.post(
                            f"/api/articles/{article_id}/quiz/attempt",
                            headers=headers,
                            json={"answers": wrong},
                        ),
                        f"failed quiz scenario {email}",
                    ).json()
                    if failed.get("passed"):
                        raise PulseError(f"failed quiz scenario unexpectedly passed for {email}")
                    counters["failed_quiz_attempts"] += 1
                passed = require(
                    client.post(
                        f"/api/articles/{article_id}/quiz/attempt",
                        headers=headers,
                        json={"answers": correct},
                    ),
                    f"passing quiz {email}",
                ).json()
                if not passed.get("passed"):
                    raise PulseError(f"correct quiz scenario did not pass for {email}")
                counters["passed_quiz_attempts"] += 1
            elif quiz_response.status_code != 404:
                raise PulseError(f"quiz lookup {email}: HTTP {quiz_response.status_code}")

            require(
                client.post(f"/api/articles/{article_id}/read-receipt", headers=headers),
                f"acknowledgment {email}",
            )
            counters["acknowledgments"] += 1
            counters["operators"] += 1
            details.append({"email": email, "article_id": article_id, "title": title})

        after = snapshot(client, admin_token)

    report = {
        "pulse_id": pulse_id,
        "started_at": started_at,
        "completed_at": datetime.now(TBILISI).isoformat(),
        "counters": counters,
        "dashboard_before": before,
        "dashboard_after": after,
        "operators": details,
    }
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    destination = ARTIFACT_DIR / f"pulse-{pulse_id}.json"
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(counters, ensure_ascii=False, indent=2))
    print(f"Pulse report: {destination}")
    return report


def main() -> int:
    try:
        run_pulse()
        return 0
    except (PulseError, httpx.HTTPError, KeyError, ValueError) as error:
        print(f"PULSE ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
