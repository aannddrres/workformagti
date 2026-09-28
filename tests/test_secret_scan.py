"""The scanner must never read excluded files or print a discovered secret."""

import json
import os
import subprocess
from pathlib import Path

import pytest

from scripts.scan_secrets import collect_sources, report_findings, scan


def git(root, *args):
    return subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True).stdout


@pytest.fixture
def repository(tmp_path):
    git(tmp_path, "init", "-q")
    git(tmp_path, "config", "user.email", "fixture@example.invalid")
    git(tmp_path, "config", "user.name", "Scanner fixture")
    return tmp_path


def commit(root):
    git(root, "add", "--all")
    git(root, "commit", "-qm", "fixture")
    return git(root, "rev-parse", "HEAD").decode().strip()


def test_excluded_files_are_filtered_before_read_and_new_allowed_files_are_scanned(repository, monkeypatch):
    for name in (".env", ".presentation.env", ".uat.env", "safe.txt"):
        (repository / name).write_text("fixture", encoding="utf-8")
    commit(repository)
    (repository / "untracked.txt").write_text("scan this too", encoding="utf-8")
    (repository / "nested").mkdir()
    (repository / "nested" / ".env").write_text("never open", encoding="utf-8")
    read = Path.read_bytes

    def guarded_read(path):
        assert path.name not in {".env", ".presentation.env", ".uat.env"}, "excluded file was opened"
        return read(path)

    monkeypatch.setattr(Path, "read_bytes", guarded_read)
    sources = dict(collect_sources(repository))
    assert sources == {"working-tree/safe.txt": b"fixture",
                       "working-tree/untracked.txt": b"scan this too"}


def test_scans_intermediate_commits_even_when_final_tree_removed_the_value(repository):
    (repository / "safe.txt").write_text("safe", encoding="utf-8")
    base = commit(repository)
    (repository / "safe.txt").write_text("synthetic intermediate value", encoding="utf-8")
    middle = commit(repository)
    (repository / "safe.txt").write_text("safe again", encoding="utf-8")
    commit(repository)
    sources = dict(collect_sources(repository, base))
    assert sources[f"commits/{middle}/safe.txt"] == b"synthetic intermediate value"
    assert sources["working-tree/safe.txt"] == b"safe again"


def test_tracked_symlink_is_never_followed(repository):
    # Write a symlink into the index without requiring Windows symlink privilege.
    oid = git(repository, "hash-object", "-w", "--stdin")  # empty synthetic target
    git(repository, "update-index", "--add", "--cacheinfo", "120000", oid.decode().strip(), "link")
    (repository / "link").write_text("must not be read", encoding="utf-8")
    assert dict(collect_sources(repository)) == {}


def test_report_contains_only_location_and_rule(capsys):
    sensitive = "synthetic-" + "private-value"
    findings = [{"File": "working-tree/example.txt", "StartLine": 7, "RuleID": "jwt",
                 "Secret": sensitive, "Match": sensitive}]
    report_findings(json.dumps(findings))
    output = capsys.readouterr().out
    assert output.strip() == "working-tree/example.txt:7: jwt"
    assert sensitive not in output


def test_scanner_output_is_redacted_even_when_the_process_logs_a_value(repository, monkeypatch, capsys):
    sensitive = "synthetic-" + "private-value"
    real_run = subprocess.run

    def scanner_run(command, **kwargs):
        if command[0] == "fixture-gitleaks":
            assert "--redact=100" in command
            report = Path(command[command.index("--report-path") + 1])
            report.write_text(json.dumps([{"File": "example.txt", "StartLine": 2, "RuleID": "fixture",
                                           "Secret": sensitive, "Match": sensitive}]), encoding="utf-8")
            return subprocess.CompletedProcess(command, 1, sensitive.encode(), sensitive.encode())
        return real_run(command, **kwargs)

    monkeypatch.setattr(subprocess, "run", scanner_run)
    assert scan(repository, "fixture-gitleaks") == 1
    output = capsys.readouterr()
    assert sensitive not in output.out + output.err
    assert output.out.strip() == "example.txt:2: fixture"


def test_clean_scan_can_succeed_without_an_empty_report_file(repository, monkeypatch, capsys):
    (repository / "safe.txt").write_text("safe", encoding="utf-8")
    commit(repository)
    real_run = subprocess.run

    def scanner_run(command, **kwargs):
        if command[0] == "fixture-gitleaks":
            return subprocess.CompletedProcess(command, 0, b"no leaks found", b"")
        return real_run(command, **kwargs)

    monkeypatch.setattr(subprocess, "run", scanner_run)
    assert scan(repository, "fixture-gitleaks") == 0
    assert "passed" in capsys.readouterr().out


@pytest.mark.skipif(not os.environ.get("GITLEAKS_BIN"), reason="Gitleaks binary not supplied; required in secret-scan CI job")
def test_real_scanner_detects_generated_jwt_and_accepts_marker(repository, capsys):
    import base64
    import shutil

    root = Path(__file__).resolve().parent.parent
    shutil.copyfile(root / ".gitleaks.toml", repository / ".gitleaks.toml")
    encoded = lambda value: base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip("=")
    token = encoded({"alg": "HS256"}) + "." + encoded({"sub": "scanner-fixture"}) + "." + "A" * 43
    (repository / "sample.txt").write_text(token, encoding="utf-8")
    commit(repository)
    assert scan(repository, os.environ["GITLEAKS_BIN"]) == 1
    output = capsys.readouterr()
    assert token not in output.out + output.err
    assert "portal-jwt-example" in output.out
    (repository / "sample.txt").write_text("NOT_A_CREDENTIAL_API_EXAMPLE", encoding="utf-8")
    assert scan(repository, os.environ["GITLEAKS_BIN"]) == 0
