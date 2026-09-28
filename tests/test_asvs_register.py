"""A10: the ASVS 5.0 register at the owner's target level holds together.

The owner set the target at L2 (ledger D1, 2026-09-25). Every L1 and L2
requirement ends as PASS, N/A, EXTERNAL_BLOCKED or RISK_ACCEPTED, and every
L3 requirement as OUT_OF_TARGET_LEVEL. Until the owner answers, a risk the
code cannot close is RISK_ACCEPTANCE_REQUESTED, and a gap still being closed
is GAP, which must name the ledger row (A<n>) that closes it.

A register is only as good as its links. This reads every evidence token
against the tree, so a renamed test or a deleted file fails the build here
instead of leaving a PASS that points at nothing. It does not decide whether
the review is finished on its own: RISK_ACCEPTANCE_REQUESTED and GAP were
legitimate states while the owner had not answered. A10 closed on 2026-09-26,
so test_the_review_is_finished now refuses both; a new gap, or a risk the
owner has not yet taken, reopens A10 in the ledger first.

A test token names a test as written in its file. Tokens are separated by
whitespace, so a Playwright or Vitest title is written with "_" for each
space: test:angular-frontend/e2e/x.spec.ts#signs_out_from_any_page.

The dated baseline (LOCAL_ASVS_CASE_REVIEW_2026-09-24.csv) stays as it was;
this register is its successor, not an edit of it.
"""

from __future__ import annotations

import csv
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTER = ROOT / "docs" / "security" / "ASVS_L2_REVIEW_2026-09-26.csv"
BASELINE = ROOT / "docs" / "security" / "LOCAL_ASVS_CASE_REVIEW_2026-09-24.csv"
LEDGER = ROOT / "docs" / "RELEASE_READINESS_LEDGER_KA.md"
IT_QUESTIONS = ROOT / "docs" / "QUESTIONS_FOR_IT.md"

COLUMNS = ["requirement", "level", "chapter", "description", "disposition", "evidence", "rationale", "owner"]
TARGET_LEVEL = {"PASS", "N/A", "EXTERNAL_BLOCKED", "RISK_ACCEPTANCE_REQUESTED", "RISK_ACCEPTED", "GAP"}
TOKEN = re.compile(r"^(test|file|ledger|it):(\S+)$")


def rows() -> list[dict[str, str]]:
    with REGISTER.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        assert reader.fieldnames == COLUMNS, reader.fieldnames
        return list(reader)


def tokens(row: dict[str, str]) -> list[tuple[str, str]]:
    found = []
    for raw in row["evidence"].split():
        match = TOKEN.match(raw)
        assert match, f"{row['requirement']}: unreadable evidence token {raw!r}"
        found.append((match.group(1), match.group(2)))
    return found


def unresolved(kind: str, value: str) -> str | None:
    """Why a token points at nothing, or None when it resolves."""
    if kind in ("test", "file"):
        path, _, name = value.partition("#")
        target = ROOT / path
        if not target.is_file():
            return f"{path} does not exist"
        text = target.read_text(encoding="utf-8")
        if kind == "test" and (not name or (name not in text and name.replace("_", " ") not in text)):
            return f"{path} does not name {name!r}"
        return None
    if kind == "ledger":
        # A<n> is our own open work, B<n> an external request, D<n> an owner decision.
        if not re.fullmatch(r"[ABD]\d+", value):
            return f"ledger:{value} is not an A, B or D row"
        if not re.search(rf"^\| {value} \|", LEDGER.read_text(encoding="utf-8"), re.MULTILINE):
            return f"the ledger has no row {value}"
        return None
    if kind == "it":
        if not re.search(rf"^### {re.escape(value)}\. ", IT_QUESTIONS.read_text(encoding="utf-8"), re.MULTILINE):
            return f"QUESTIONS_FOR_IT.md has no question {value}"
        return None
    return f"unknown kind {kind}"


def test_the_register_covers_exactly_the_baselines_requirements_at_their_levels():
    with BASELINE.open(encoding="utf-8", newline="") as handle:
        baseline = {r["requirement"]: r["level"] for r in csv.DictReader(handle)}
    register = {r["requirement"]: r["level"] for r in rows()}
    assert len(register) == len(rows()), "a requirement appears twice"
    assert register == baseline


def test_every_l3_requirement_is_out_of_target_level_and_nothing_else_is():
    for row in rows():
        is_l3 = row["level"] == "3"
        assert (row["disposition"] == "OUT_OF_TARGET_LEVEL") == is_l3, row["requirement"]


def test_every_target_level_row_has_an_allowed_disposition_and_says_why():
    for row in rows():
        if row["level"] == "3":
            continue
        assert row["disposition"] in TARGET_LEVEL, f"{row['requirement']}: {row['disposition']}"
        assert row["rationale"].strip(), f"{row['requirement']}: no rationale"


def test_a_pass_cites_at_least_one_executed_test():
    """A requirement that asks for a document, an inventory or a classification is met by that artifact."""
    for row in rows():
        if row["disposition"] != "PASS":
            continue
        kinds = {kind for kind, _ in tokens(row)}
        asks_for_an_artifact = re.search(r"document|inventor|classif", row["description"], re.IGNORECASE)
        assert "test" in kinds or (asks_for_an_artifact and "file" in kinds), \
            f"{row['requirement']}: PASS without a test"


def test_a_gap_names_the_ledger_row_that_closes_it():
    for row in rows():
        if row["disposition"] == "GAP":
            assert any(kind == "ledger" and value.startswith("A") for kind, value in tokens(row)), row["requirement"]


def test_a_not_applicable_row_cites_what_keeps_it_true():
    for row in rows():
        if row["disposition"] == "N/A":
            assert any(kind in ("test", "file") for kind, _ in tokens(row)), row["requirement"]


def test_an_external_row_names_who_it_waits_on():
    for row in rows():
        if row["disposition"] == "EXTERNAL_BLOCKED":
            assert any(kind == "it" or (kind == "ledger" and value.startswith("B"))
                       for kind, value in tokens(row)), row["requirement"]


def test_an_accepted_risk_cites_the_owners_decision():
    for row in rows():
        if row["disposition"] == "RISK_ACCEPTED":
            assert any(kind == "ledger" and value.startswith("D") for kind, value in tokens(row)), row["requirement"]


def test_every_evidence_token_resolves():
    problems = [f"{row['requirement']}: {why}"
                for row in rows()
                for kind, value in tokens(row)
                if (why := unresolved(kind, value))]
    assert not problems, "\n".join(problems)


def test_the_review_is_finished():
    waiting = [row["requirement"] for row in rows() if row["disposition"] in ("GAP", "RISK_ACCEPTANCE_REQUESTED")]
    assert waiting == [], f"A10 closed on 2026-09-26; reopen it in the ledger before these: {waiting}"
