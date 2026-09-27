"""Scan an explicitly filtered copy, never the repository directory itself.

The optional base commit also scans each changed regular-file blob in base..HEAD,
so adding a credential and removing it in a later commit does not bypass CI.
Excluded paths are filtered before any working-tree read or git blob retrieval.
"""

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tempfile


FORBIDDEN_NAMES = {".env", ".presentation.env", ".uat.env"}
REGULAR_MODES = {"100644", "100755"}


def git(root, *args):
    return subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True).stdout


def allowed(name):
    path = PurePosixPath(name)
    return (not path.is_absolute() and ".." not in path.parts
            and not any(part.lower() in FORBIDDEN_NAMES for part in path.parts))


def entries(raw):
    for entry in raw.split(b"\0"):
        if entry:
            metadata, name = entry.split(b"\t", 1)
            mode = metadata.split(b" ", 1)[0].decode("ascii")
            name = name.decode("utf-8")
            if mode in REGULAR_MODES and allowed(name):
                yield name


def collect_sources(root, base=None):
    root = Path(root).resolve()
    for name in entries(git(root, "ls-files", "--stage", "-z")):
        source = root / name
        # Also reject a regular index entry replaced locally by a symlink/junction.
        if source.exists() and source.resolve() == source and source.is_file():
            yield "working-tree/" + name, source.read_bytes()

    # Local review happens before git add/commit. Include new, non-ignored
    # files too; filter their names before touching the filesystem.
    for raw_name in git(root, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0"):
        if not raw_name:
            continue
        name = raw_name.decode("utf-8")
        if not allowed(name):
            continue
        source = root / name
        if source.exists() and source.resolve() == source and source.is_file():
            yield "working-tree/" + name, source.read_bytes()

    if base:
        if not re.fullmatch(r"[0-9a-fA-F]{40}", base):
            raise ValueError("base must be a full commit SHA")
        commits = git(root, "rev-list", "--reverse", f"{base}..HEAD").decode().splitlines()
        for revision in commits:
            changed = git(root, "diff-tree", "--root", "--no-commit-id", "--name-only", "-r",
                          "-m", "--no-renames", "--diff-filter=ACM", "-z", revision)
            names = {name.decode("utf-8") for name in changed.split(b"\0") if name}
            # ls-tree returns metadata only. Do not obtain ANY excluded blob.
            for name in entries(git(root, "ls-tree", "-r", "-z", revision)):
                if name in names:
                    yield f"commits/{revision}/{name}", git(root, "show", f"{revision}:{name}")


def report_findings(raw):
    for finding in json.loads(raw) or []:
        # JSON quoting prevents filenames/rule names from injecting log lines.
        file = json.dumps(finding["File"], ensure_ascii=True)[1:-1]
        rule = json.dumps(finding["RuleID"], ensure_ascii=True)[1:-1]
        print(f"{file}:{int(finding['StartLine'])}: {rule}")


def scan(root, binary, base=None):
    with tempfile.TemporaryDirectory(prefix="magti-secret-scan-") as temporary:
        directory = Path(temporary)
        inputs = directory / "inputs"
        inputs.mkdir()
        count = 0
        for name, content in collect_sources(root, base):
            target = inputs / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
            count += 1
        report = directory / "report.json"
        result = subprocess.run(
            [binary, "dir", str(inputs), "--config", str(root / ".gitleaks.toml"),
             "--redact=100", "--no-banner", "--log-level=error", "--ignore-gitleaks-allow",
             "--report-format=json", "--report-path", str(report)], capture_output=True)
        # Never echo scanner stdout/stderr: even error paths stay confidential.
        if result.returncode not in (0, 1):
            raise RuntimeError("secret scanner failed; raw output withheld")
        # Recent Gitleaks releases may omit the report entirely on a clean
        # scan. The separate positive fixture test guards against a binary
        # that returns zero without actually detecting a known JWT.
        if not report.exists():
            if result.returncode:
                raise RuntimeError("secret scanner failed; raw output withheld")
            print(f"Secret scan passed: {count} permitted file versions.")
            return 0
        raw = report.read_text(encoding="utf-8")
        findings = json.loads(raw) or []
        if findings:
            report_findings(raw.replace(str(inputs).replace("\\", "\\\\") + "/", ""))
            return 1
        if result.returncode:
            raise RuntimeError("secret scanner failed without findings; raw output withheld")
        print(f"Secret scan passed: {count} permitted file versions.")
        return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="full base commit SHA; also scan subsequent changed blobs")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    try:
        return scan(root, os.environ.get("GITLEAKS_BIN", "gitleaks"), args.base)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError):
        print("Secret scan could not complete; raw output withheld. Check git, config and Gitleaks.")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
