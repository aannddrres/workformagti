"""A27: the endpoint case register points at tests that exist.

docs/security/ENDPOINT_CASE_REVIEW_2026-09-27.csv holds one row per API
action and, for its success, denial and error case, the test that asserts it:
File.java#method, or several methods joined by "+". A case with no test says
why in words, starting "N/A:".

Its predecessor, LOCAL_ENDPOINT_CASE_REVIEW_2026-09-24.csv, named file:line
ranges instead. Line numbers move with every edit above them and nothing
checked them: by 2026-09-27, 44 of its 513 ranges landed on a different test
than the one they were written for. The predecessor stays as it was; this
register is its successor, not an edit of it.

This reads every pointer against the tree, and the endpoint and gate columns
against docs/ACCESS_CONTRACT_MATRIX_KA.md, so a renamed test, a deleted file
or a moved gate fails the build here instead of leaving a case that points at
nothing.
"""

from __future__ import annotations

import csv
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTER = ROOT / "docs" / "security" / "ENDPOINT_CASE_REVIEW_2026-09-27.csv"
MATRIX = ROOT / "docs" / "ACCESS_CONTRACT_MATRIX_KA.md"
JAVA_SOURCES = ROOT / "java-backend" / "src"

COLUMNS = ["endpoint", "gate", "success_case", "denial_case", "error_case", "case_result"]
CASES = ("success_case", "denial_case", "error_case")
POINTER = re.compile(r"\b([A-Za-z0-9_]+\.java)#([A-Za-z0-9_]+(?:\+[A-Za-z0-9_]+)*)")
LINE_POINTER = re.compile(r"\.java:\d|(?<![\w.]):\d+(?:-\d+)?\b")
MATRIX_ROW = re.compile(r"^\| `((?:GET|POST|PUT|DELETE|PATCH) [^`]+)` \| [^|]*\| ([^|]*)\|")
ASSERTION = re.compile(r"\b(?:assert\w*|andExpect|verify\w*|fail)\s*\(")


def rows() -> list[dict[str, str]]:
    with REGISTER.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        assert reader.fieldnames == COLUMNS, reader.fieldnames
        return list(reader)


def matrix_gates() -> dict[str, str]:
    gates: dict[str, str] = {}
    for line in MATRIX.read_text(encoding="utf-8").splitlines():
        match = MATRIX_ROW.match(line)
        if match:
            assert match.group(1) not in gates, f"{match.group(1)} appears twice in the access matrix"
            gates[match.group(1)] = match.group(2).strip().strip("`")
    return gates


def java_files() -> dict[str, list[Path]]:
    files: dict[str, list[Path]] = {}
    for path in JAVA_SOURCES.rglob("*.java"):
        files.setdefault(path.name, []).append(path)
    return files


def closing_brace(source: str, start: int) -> int:
    """Index of the brace that closes the one at start; strings and comments are skipped."""
    depth, i = 0, start
    while i < len(source):
        if source.startswith('"""', i):
            i = source.index('"""', i + 3) + 3
            continue
        char = source[i]
        if char in "\"'":
            i += 1
            while source[i] != char:
                i += 2 if source[i] == "\\" else 1
            i += 1
            continue
        if source.startswith("//", i):
            i = source.index("\n", i)
            continue
        if source.startswith("/*", i):
            i = source.index("*/", i) + 2
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise AssertionError("unbalanced braces")


def method_body(source: str, name: str) -> str | None:
    """The body of the method declared as name, or None when nothing declares it."""
    declaration = re.compile(r"\b" + re.escape(name) + r"\s*\([^;{}]*\)\s*(?:throws\s[^{;]*)?\{")
    for match in declaration.finditer(source):
        before = source[: match.start()].rstrip()
        # A declaration's name follows its type; a call follows ".", "(", "new" and the like.
        if not before or before[-1] in ".(=,!&|?:" or before.endswith(("new", "return")):
            continue
        brace = match.end() - 1
        return source[brace + 1 : closing_brace(source, brace)]
    return None


def pointers(cell: str) -> list[tuple[str, list[str]]]:
    return [(match.group(1), match.group(2).split("+")) for match in POINTER.finditer(cell)]


def unresolved(file_name: str, method: str, files: dict[str, list[Path]]) -> str | None:
    """Why a pointer names nothing, or None when it resolves."""
    paths = files.get(file_name, [])
    if len(paths) != 1:
        return f"{file_name} matches {len(paths)} files under java-backend/src"
    source = paths[0].read_text(encoding="utf-8")
    body = method_body(source, method)
    if body is None:
        return f"{file_name} declares no method {method}"
    if "/src/test/" in paths[0].as_posix() and not ASSERTION.search(body):
        return f"{file_name}#{method} asserts nothing"
    return None


def test_the_register_has_one_row_per_endpoint_of_the_access_matrix() -> None:
    endpoints = [row["endpoint"] for row in rows()]
    assert len(endpoints) == len(set(endpoints)), "an endpoint appears twice"
    matrix = matrix_gates()
    assert sorted(set(matrix) - set(endpoints)) == [], "endpoints in the access matrix without a row here"
    assert sorted(set(endpoints) - set(matrix)) == [], "rows here for endpoints the access matrix does not have"


def test_every_gate_matches_the_access_matrix() -> None:
    matrix = matrix_gates()
    moved = [f"{row['endpoint']}: {row['gate']} here, {matrix[row['endpoint']]} in the matrix"
             for row in rows() if row["gate"] != matrix.get(row["endpoint"])]
    assert moved == []


def test_every_case_names_a_test_or_says_why_not() -> None:
    bare = [f"{row['endpoint']} {case}" for row in rows() for case in CASES
            if not pointers(row[case]) and not row[case].startswith("N/A:")]
    assert bare == []


def test_no_case_points_at_a_line_number() -> None:
    by_line = [f"{row['endpoint']} {case}: {match.group(0)}" for row in rows() for case in CASES
               for match in [LINE_POINTER.search(row[case])] if match]
    assert by_line == [], "line numbers drift; name the test method instead"


def test_every_pointer_names_a_method_that_exists_and_asserts() -> None:
    files = java_files()
    broken = []
    for row in rows():
        for case in CASES:
            for file_name, methods in pointers(row[case]):
                for method in methods:
                    reason = unresolved(file_name, method, files)
                    if reason:
                        broken.append(f"{row['endpoint']} {case}: {reason}")
    assert broken == []


def test_the_check_is_not_vacuous() -> None:
    found = [method for row in rows() for case in CASES
             for _, methods in pointers(row[case]) for method in methods]
    assert len(rows()) >= 150
    assert len(found) >= 500, f"only {len(found)} pointers were read; the pattern has stopped matching"
    assert unresolved("UserControllerIntegrationTest.java", "noSuchTestMethod", java_files())
