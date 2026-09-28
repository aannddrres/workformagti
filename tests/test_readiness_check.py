"""Covers scripts/readiness_check.py: what it accepts as evidence, and when it
lets the closure statement be printed.

The statement is the one sentence the release owner signs, so every way the
ledger could claim more than the repository shows is pinned here as a
refusal. The last test parses the real ledger, which keeps its format
readable by the checker on every CI run -- the checker itself is not a gate.
"""

from __future__ import annotations

import datetime as dt
from pathlib import Path

from scripts.readiness_check import LEDGER, RunResult, check, parse

ROOT = Path(__file__).resolve().parents[1]
TODAY = dt.date(2026, 9, 25)
HEAD = "c0ffee1" + "0" * 33
FIX = "5940886" + "1" * 33
ELSEWHERE = "dead000" + "2" * 33
TEST_PATH = "java-backend/src/test/java/ExampleTest.java"


class FakeGit:
    def __init__(self, changed_since=None, ledger_committed=True):
        self.history = {HEAD, FIX}
        self.commits = self.history | {ELSEWHERE}
        self.files = {TEST_PATH: "class ExampleTest { void aDuplicateIsNotRejected() {} }"}
        self._changed_since = changed_since or {}
        self._ledger_committed = ledger_committed

    def head(self):
        return HEAD

    def resolve(self, ref):
        return next((sha for sha in self.commits if sha.startswith(ref)), None)

    def in_history(self, sha):
        return sha in self.history

    def in_head(self, path):
        return path in self.files

    def read(self, path):
        return self.files[path]

    def changed_since(self, sha):
        return self._changed_since.get(sha, [])

    def ledger_committed(self):
        return self._ledger_committed


def ledger(ours, theirs=(("IT", "2026-09-24"),)):
    lines = ["| # | პუნქტი | დასრულდა, როცა | სტატუსი | მტკიცებულება |", "|---|---|---|---|---|"]
    lines += [f"| A{n} | item {n} | when | `{status}` | {evidence} |" for n, (status, evidence) in enumerate(ours, 1)]
    lines += ["", "| # | ვისზეა | რა | მოთხოვნა გაიგზავნა | პასუხი |", "|---|---|---|---|---|"]
    lines += [f"| B{n} | {who} | ask | {sent} | — |" for n, (who, sent) in enumerate(theirs, 1)]
    return parse("\n".join(lines))


def passing(run_id):
    return RunResult(HEAD, True, "6/6 job success")


def test_a_closed_row_that_names_nothing_checkable_is_refused():
    report = check(ledger([("CLOSED", "გასწორდა")]), FakeGit(), TODAY)

    assert report.exit_code == 2
    assert report.statement is None


def test_a_commit_outside_heads_history_is_refused():
    report = check(ledger([("CLOSED", f"`commit:{ELSEWHERE[:7]}`")]), FakeGit(), TODAY)

    assert report.exit_code == 2
    assert any("dead000" in problem for problem in report.problems)


def test_a_test_the_cited_file_does_not_contain_is_refused():
    report = check(ledger([("CLOSED", f"`test:{TEST_PATH}#aTestNobodyWrote`")]), FakeGit(), TODAY)

    assert report.exit_code == 2


def test_a_file_that_is_not_in_head_is_refused():
    report = check(ledger([("CLOSED", "`file:logs/release-audit/evidence.log`")]), FakeGit(), TODAY)

    assert report.exit_code == 2


def test_open_rows_mean_not_finished_rather_than_wrong():
    report = check(ledger([("CLOSED", f"`commit:{FIX[:7]}`"), ("OPEN", "—")]), FakeGit(), TODAY)

    assert report.exit_code == 1
    assert report.problems == []
    assert report.statement is None


def test_every_row_closed_with_evidence_prints_the_statement():
    rows = [
        ("CLOSED", f"`commit:{FIX[:7]}` `test:{TEST_PATH}#aDuplicateIsNotRejected`"),
        ("CLOSED", f"`head-ci:36197161930@{HEAD[:7]}`  `file:{TEST_PATH}`"),
        ("CLOSED", "`ledger:list-b`"),
    ]
    report = check(ledger(rows), FakeGit(), TODAY, ci=passing)

    assert report.exit_code == 0
    assert report.statement.startswith("ჩვენზე დამოკიდებული 3-ვე პირობა დახურულია")
    assert "commit c0ffee1" in report.statement
    assert "CI run 36197161930" in report.statement
    assert "დარჩენილი 1 პირობა გარე მხარეებზეა: IT." in report.statement


def test_ci_is_not_taken_on_trust_offline():
    rows = [("CLOSED", f"`commit:{FIX[:7]}` `head-ci:36197161930@{HEAD[:7]}`")]
    report = check(ledger(rows), FakeGit(), TODAY)

    assert report.exit_code == 1
    assert report.statement is None
    assert report.withheld


def test_a_failed_ci_run_cannot_close_a_row():
    def failed(run_id):
        return RunResult(HEAD, False, "5/6 job success")

    rows = [("CLOSED", f"`commit:{FIX[:7]}` `head-ci:36197161930@{HEAD[:7]}`")]
    report = check(ledger(rows), FakeGit(), TODAY, ci=failed)

    assert report.exit_code == 2


def test_a_ci_run_of_another_commit_is_refused():
    def other(run_id):
        return RunResult(FIX, True, "6/6 job success")

    rows = [("CLOSED", f"`commit:{FIX[:7]}` `head-ci:36197161930@{HEAD[:7]}`")]
    report = check(ledger(rows), FakeGit(), TODAY, ci=other)

    assert report.exit_code == 2


def test_head_ci_must_cover_head_where_only_the_ledger_may_have_changed_since():
    rows = [("CLOSED", f"`commit:{FIX[:7]}` `head-ci:36197161930@{FIX[:7]}`")]

    ledger_only = FakeGit(changed_since={FIX: [LEDGER]})
    assert check(ledger(rows), ledger_only, TODAY, ci=lambda run_id: RunResult(FIX, True, "6/6")).exit_code == 0

    code_changed = FakeGit(changed_since={FIX: [LEDGER, "java-backend/pom.xml"]})
    assert check(ledger(rows), code_changed, TODAY, ci=lambda run_id: RunResult(FIX, True, "6/6")).exit_code == 2


def test_the_list_b_row_closes_exactly_when_every_request_is_dated():
    unsent = (("IT", "2026-09-24"), ("DBA", "—"))

    assert check(ledger([("CLOSED", "`ledger:list-b`")], unsent), FakeGit(), TODAY).exit_code == 2
    assert check(ledger([("OPEN", "`ledger:list-b`")], unsent), FakeGit(), TODAY).exit_code == 1
    assert check(ledger([("OPEN", "`ledger:list-b`")]), FakeGit(), TODAY).exit_code == 2


def test_a_request_dated_in_the_future_is_refused():
    report = check(ledger([("OPEN", "—")], (("IT", "2026-09-26"),)), FakeGit(), TODAY)

    assert report.exit_code == 2


def test_an_uncommitted_ledger_withholds_the_statement():
    rows = [("CLOSED", f"`commit:{FIX[:7]}`")]
    report = check(ledger(rows), FakeGit(ledger_committed=False), TODAY)

    assert report.exit_code == 1
    assert report.statement is None


def test_the_real_ledger_parses_into_numbered_lists():
    parsed = parse((ROOT / LEDGER).read_text(encoding="utf-8"))

    assert parsed.problems == []
    assert [row.ident for row in parsed.ours] == [f"A{n}" for n in range(1, len(parsed.ours) + 1)]
    assert [row.ident for row in parsed.theirs] == [f"B{n}" for n in range(1, len(parsed.theirs) + 1)]
