"""Says whether everything the release depends on *us* for is done, checking
the evidence rather than the word "done".

Reads docs/RELEASE_READINESS_LEDGER_KA.md. List A is ours; List B is other
people's, and our part of each B row is a dated written request.

    python scripts/readiness_check.py
    python scripts/readiness_check.py --online    # also asks GitHub whether each cited CI run passed

Exit status
    0  every List A row is CLOSED and all of its evidence checks out. Prints
       the closure statement.
    1  the ledger holds together, but List A is not finished.
    2  the ledger does not hold together: a cited commit is not in HEAD's
       history, a cited test or file is not in HEAD, a CLOSED row cites
       nothing checkable. Fix the ledger before believing anything it says.

Evidence, written in backticks in a row's evidence cell:

    commit:<sha>               a commit in HEAD's history
    test:<path>#<name>         a file in HEAD that names the test
    file:<path>                a file in HEAD; untracked and ignored files are not evidence
    ci:<run id>@<sha>          a CI run of that commit
    head-ci:<run id>@<sha>     the same, and it must cover HEAD: nothing but the
                               ledger may have changed since that commit
    ledger:list-b              the row closes exactly when every List B row has a request date

A CLOSED row needs at least one commit, test or file. CI runs are checked
only with --online (GITHUB_TOKEN or GH_TOKEN for a private repository);
until then a CLOSED row that cites one withholds the statement.

Not a CI gate, deliberately: it would keep every build red until the release
is ready, and CI's shallow checkout cannot see the commits the ledger cites.
tests/test_readiness_check.py keeps the ledger's format parseable instead.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[1]
LEDGER = "docs/RELEASE_READINESS_LEDGER_KA.md"

ROW_ID = re.compile(r"^([AB])(\d+)$")
STATUS = re.compile(r"`(CLOSED|PARTIAL|OPEN)`")
EVIDENCE = re.compile(r"`(commit|test|file|ci|head-ci|ledger):([^`\s]+)`")
RUN = re.compile(r"^(\d+)@([0-9a-f]{7,40})$")
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
REPO_SLUG = re.compile(r"([^/:]+)/([^/]+?)(?:\.git)?/?$")


@dataclass
class Row:
    ident: str
    cells: list[str]
    line: int


@dataclass
class Ledger:
    ours: list[Row]
    theirs: list[Row]
    problems: list[str]


def parse(text: str) -> Ledger:
    """List A and List B rows are the table rows whose first cell is A<n> or B<n>."""
    ours: list[Row] = []
    theirs: list[Row] = []
    problems: list[str] = []
    for number, line in enumerate(text.splitlines(), start=1):
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        match = ROW_ID.match(cells[0])
        if not match:
            continue
        if len(cells) != 5:
            problems.append(f"სტრიქონი {number}: {cells[0]}-ს {len(cells)} უჯრა აქვს, უნდა ჰქონდეს 5")
            continue
        (ours if match.group(1) == "A" else theirs).append(Row(cells[0], cells, number))
    for rows, letter in ((ours, "A"), (theirs, "B")):
        found = [row.ident for row in rows]
        expected = [f"{letter}{n}" for n in range(1, len(rows) + 1)]
        if not rows:
            problems.append(f"სია {letter}-ს სტრიქონები ვერ მოიძებნა")
        elif found != expected:
            problems.append(f"სია {letter}: {found}, მოსალოდნელი იყო {expected}")
    return Ledger(ours, theirs, problems)


class Git:
    """The repository as HEAD has it. Evidence that is only in the working tree does not count."""

    def __init__(self, root: Path) -> None:
        self.root = root

    def _git(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["git", *args], cwd=self.root, capture_output=True, text=True)

    def head(self) -> str:
        return self._git("rev-parse", "HEAD").stdout.strip()

    def resolve(self, ref: str) -> str | None:
        result = self._git("rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}")
        return result.stdout.strip() if result.returncode == 0 else None

    def in_history(self, sha: str) -> bool:
        return self._git("merge-base", "--is-ancestor", sha, "HEAD").returncode == 0

    def in_head(self, path: str) -> bool:
        return self._git("cat-file", "-e", f"HEAD:{path}").returncode == 0

    def read(self, path: str) -> str:
        return self._git("show", f"HEAD:{path}").stdout

    def changed_since(self, sha: str) -> list[str]:
        return [path for path in self._git("diff", "--name-only", sha, "HEAD").stdout.splitlines() if path]

    def ledger_committed(self) -> bool:
        return self._git("diff", "--quiet", "HEAD", "--", LEDGER).returncode == 0

    def github_slug(self) -> str | None:
        match = REPO_SLUG.search(self._git("remote", "get-url", "origin").stdout.strip())
        return f"{match.group(1)}/{match.group(2)}" if match else None


@dataclass
class RunResult:
    head_sha: str
    passed: bool
    summary: str


def github_run(slug: str, run_id: str) -> RunResult:
    """One CI run as GitHub reports it: passed means completed, success, and every job a success."""
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    def get(url: str) -> dict:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
            return json.load(response)

    run = get(f"https://api.github.com/repos/{slug}/actions/runs/{run_id}")
    jobs = get(f"https://api.github.com/repos/{slug}/actions/runs/{run_id}/jobs?per_page=100")["jobs"]
    green = [job for job in jobs if job["conclusion"] == "success"]
    passed = run["status"] == "completed" and run["conclusion"] == "success" and jobs and len(green) == len(jobs)
    return RunResult(run["head_sha"], bool(passed), f"{len(green)}/{len(jobs)} job success")


@dataclass
class Report:
    lines: list[str] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)
    withheld: list[str] = field(default_factory=list)
    statement: str | None = None

    @property
    def exit_code(self) -> int:
        if self.problems:
            return 2
        return 0 if self.statement else 1


def check(
    ledger: Ledger,
    git,
    today: dt.date,
    ci: Callable[[str], RunResult] | None = None,
) -> Report:
    """Everything the ledger claims, against the repository. ``ci`` is None when offline."""
    report = Report(problems=list(ledger.problems))
    head = git.head()

    requested = {}
    for row in ledger.theirs:
        dates = DATE.findall(row.cells[3])
        if not dates:
            continue
        try:
            sent = dt.date.fromisoformat(dates[0])
        except ValueError:
            report.problems.append(f"{row.ident}: „{dates[0]}“ თარიღი არ არის")
            continue
        if sent > today:
            report.problems.append(f"{row.ident}: მოთხოვნის თარიღი {sent} მომავალშია")
            continue
        requested[row.ident] = sent

    head_runs: list[str] = []
    closed = 0
    for row in ledger.ours:
        status_match = STATUS.search(row.cells[3])
        if not status_match:
            report.problems.append(f"{row.ident}: სტატუსი უნდა იყოს `CLOSED`, `PARTIAL` ან `OPEN`")
            continue
        status = status_match.group(1)
        notes: list[str] = []
        checkable = False
        for kind, value in EVIDENCE.findall(row.cells[4]):
            if kind == "commit":
                sha = git.resolve(value)
                if not sha or not git.in_history(sha):
                    report.problems.append(f"{row.ident}: commit {value} HEAD-ის ისტორიაში არ არის")
                checkable = True
            elif kind in ("test", "file"):
                path, _, name = value.partition("#")
                if not git.in_head(path):
                    report.problems.append(f"{row.ident}: {path} HEAD-ში არ არის")
                elif kind == "test" and (not name or name not in git.read(path)):
                    report.problems.append(f"{row.ident}: {path} არ შეიცავს ტესტს „{name}“")
                checkable = True
            elif kind in ("ci", "head-ci"):
                match = RUN.match(value)
                sha = git.resolve(match.group(2)) if match else None
                if not match or not sha or not git.in_history(sha):
                    report.problems.append(f"{row.ident}: CI მითითება {value} უნდა იყოს <run id>@<HEAD-ის ისტორიის commit>")
                    continue
                run_id = match.group(1)
                if kind == "head-ci":
                    since = [path for path in git.changed_since(sha) if path != LEDGER]
                    if since:
                        notes.append(f"CI run {run_id} ძველ commit-ზეა; მას შემდეგ შეიცვალა: {', '.join(since[:5])}")
                        if status == "CLOSED":
                            report.problems.append(f"{row.ident}: CLOSED-ია, მაგრამ CI run {run_id} HEAD-ს არ ფარავს")
                    else:
                        head_runs.append(run_id)
                if ci is None:
                    if status == "CLOSED":
                        report.withheld.append(f"{row.ident}: CI run {run_id} შემოწმებულია მხოლოდ --online-ით")
                    continue
                result = ci(run_id)
                if not result.head_sha.startswith(match.group(2)):
                    report.problems.append(f"{row.ident}: CI run {run_id} სხვა commit-ისაა ({result.head_sha[:7]})")
                elif not result.passed:
                    notes.append(f"CI run {run_id}: {result.summary}")
                    if status == "CLOSED":
                        report.problems.append(f"{row.ident}: CLOSED-ია, მაგრამ CI run {run_id} არ გავიდა ({result.summary})")
            elif kind == "ledger" and value == "list-b":
                checkable = True
                all_sent = len(requested) == len(ledger.theirs)
                if (status == "CLOSED") != all_sent:
                    report.problems.append(
                        f"{row.ident}: სია B-დან წერილობით მოთხოვნილია {len(requested)}/{len(ledger.theirs)}, "
                        f"სტატუსი კი {status}-ია")
            else:
                report.problems.append(f"{row.ident}: უცნობი მტკიცებულება `{kind}:{value}`")
        if status == "CLOSED" and not checkable:
            report.problems.append(f"{row.ident}: CLOSED-ია, მაგრამ შესამოწმებელ commit-ს, ტესტს ან ფაილს არ ასახელებს")
        closed += status == "CLOSED"
        mark = "✓" if status == "CLOSED" else "·"
        report.lines.append(f"  {mark} {row.ident:<4}{status:<8}{row.cells[1]}")
        report.lines.extend(f"        {note}" for note in notes)

    report.lines.insert(0, f"სია A (ჩვენზე): {closed}/{len(ledger.ours)} დახურულია")
    report.lines.append(f"სია B (სხვებზე): წერილობით მოთხოვნილია {len(requested)}/{len(ledger.theirs)}")
    for row in ledger.theirs:
        sent = requested.get(row.ident)
        report.lines.append(f"  {'✓' if sent else '·'} {row.ident:<4}{row.cells[1]}: {sent or 'ჯერ არ გაგზავნილა'}")

    if not git.ledger_committed():
        report.withheld.append("ლედჯერს commit-ში შეუტანელი ცვლილებები აქვს")
    finished = closed == len(ledger.ours) and not report.problems and not report.withheld
    if finished:
        owners = ", ".join(dict.fromkeys(row.cells[1] for row in ledger.theirs))
        runs = ", ".join(dict.fromkeys(head_runs)) or "—"
        report.statement = (
            f"ჩვენზე დამოკიდებული {len(ledger.ours)}-ვე პირობა დახურულია და დამოუკიდებლად გადამოწმებულია "
            f"(commit {head[:7]}, CI run {runs}). დარჩენილი {len(ledger.theirs)} პირობა გარე მხარეებზეა: "
            f"{owners}. თითოეულს წერილობით მივმართეთ; თარიღები ლედჯერშია."
        )
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=f"Checks {LEDGER} against the repository.")
    parser.add_argument("--online", action="store_true", help="ask GitHub whether each cited CI run passed")
    parser.add_argument("--repo", help="owner/name on GitHub; defaults to the origin remote")
    args = parser.parse_args()

    git = Git(ROOT)
    ledger = parse((ROOT / LEDGER).read_text(encoding="utf-8"))
    slug = args.repo or git.github_slug()
    if args.online and not slug:
        parser.error("could not tell the GitHub repository from origin; pass --repo owner/name")

    def ci(run_id: str) -> RunResult:
        return github_run(slug, run_id)

    report = check(ledger, git, dt.date.today(), ci if args.online else None)
    print(f"მზაობა — HEAD {git.head()[:7]}\n")
    print("\n".join(report.lines))
    for heading, items in (("\nპრობლემები:", report.problems), ("\nჯერ არ დასტურდება:", report.withheld)):
        if items:
            print(heading)
            print("\n".join(f"  ✗ {item}" for item in items))
    if report.statement:
        print(f"\n{report.statement}")
    elif not report.problems:
        print("\nსია A ჯერ არ დასრულებულა.")
    return report.exit_code


if __name__ == "__main__":
    sys.exit(main())
