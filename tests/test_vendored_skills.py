"""The third-party skills under .agents/skills change only on purpose.

A skill is instructions an agent follows without asking, so a changed skill
is changed behaviour. skills-lock.json records where each came from;
skills-reviewed.json pins what was last read. These tests fail when the two
files and the directory stop agreeing, or when a skill gains a file that is
not Markdown -- a script would run, not just be read.
"""

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SKILLS = ROOT / ".agents" / "skills"
LOCK = json.loads((ROOT / "skills-lock.json").read_text(encoding="utf-8"))["skills"]
REVIEWED = json.loads((ROOT / "skills-reviewed.json").read_text(encoding="utf-8"))["skills"]


def files_of(name):
    # Sorted by the relative path string, not by Path: WindowsPath compares
    # case-insensitively, so "references/" sorted before "SKILL.md" on Windows
    # and after it on Linux, and the same files hashed differently in CI.
    base = SKILLS / name
    return [p for _, p in sorted((p.relative_to(base).as_posix(), p) for p in base.rglob("*") if p.is_file())]


def digest(name):
    # CRLF folded to LF so a Windows checkout hashes like CI's Linux one.
    h = hashlib.sha256()
    for p in files_of(name):
        rel = p.relative_to(SKILLS / name).as_posix().encode()
        h.update(rel + b"\0" + p.read_bytes().replace(b"\r\n", b"\n") + b"\0")
    return h.hexdigest()


def test_lock_reviewed_and_directory_name_the_same_skills():
    on_disk = sorted(p.name for p in SKILLS.iterdir() if p.is_dir())
    assert on_disk, "no skills found under .agents/skills -- the checks below would pass vacuously"
    assert sorted(LOCK) == on_disk, "skills-lock.json and .agents/skills disagree on which skills are installed"
    assert sorted(REVIEWED) == on_disk, (
        "a skill was added or removed without an entry in skills-reviewed.json -- read it, then add or drop it there")


def test_vendored_skills_are_markdown_only():
    found = [str(p.relative_to(SKILLS)) for name in REVIEWED for p in files_of(name) if p.suffix != ".md"]
    assert found == [], (
        "a vendored skill now carries a non-Markdown file, which an agent may execute rather than read: "
        f"{found}. Review it before accepting")


def test_vendored_skills_match_what_was_reviewed():
    changed = [
        f"{name}: {len(files_of(name))} files, sha256 {digest(name)}"
        for name, entry in REVIEWED.items()
        if digest(name) != entry["sha256"] or len(files_of(name)) != entry["files"]
    ]
    assert changed == [], (
        "these skills changed since skills-reviewed.json was written. Read the change "
        "(git diff -- .agents/skills/<name>), then record the new values and date there: " + "; ".join(changed))
