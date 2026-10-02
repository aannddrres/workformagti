# scripts/ — notes for coding agents

Shell entry points that drive the whole repository, and Python tools -- the
legacy content importer above all -- that are **current tooling, not
leftovers** from the deleted FastAPI application. The demo and UAT seeders
left with their stacks at the production handover (2026-09-29).
Each script explains itself in its own header — `verify-like-ci.sh` at length.
What follows is only what those headers do not say.

## Which of these run on Windows

| | |
|---|---|
| Git Bash only | `run-local.sh`, `verify-like-ci.sh`, `seed-demo-content.sh`, `load/fetch_tokens.sh`, `visual-diff.sh` |
| Native PowerShell | `link-skills.ps1` |

`seed-demo-content.sh` is POSIX `sh`, not bash, because it also runs inside
`curlimages/curl` as the compose `seed` service. `.gitattributes` pins it to
LF for the same reason.

## What the headers do not tell you

**`python -m pytest`, never bare `pytest`.** A uv- or pipx-installed pytest
lives in its own environment, cannot import the seeders' dependencies, and
dies during collection with a `ModuleNotFoundError` that reads like a broken
repository. `verify-like-ci.sh` does this deliberately.

**The Oracle-fixture tests self-skip** unless `RUN_ORACLE_FIXTURE_TESTS=true`.
A green local `pytest` run does not mean they executed.

**Imported content must arrive with `is_draft = false`.** That flag is the
personal-autosave marker and hides a row from every account but its author,
content administrators included; editorial state belongs in `status`, which is
why everything imports as `status='draft'`. Getting this wrong on the first
import made all 122 articles visible to exactly one person.

**`requirements-dev.txt` is the one list of Python dependencies** -- for the
tools and for `tests/`. Ruff is configured in the root
`pyproject.toml` and selects only `E9` and `F` — real errors, not style.

## Never

- **Never commit or delete `magti_portal.db`.** About 183 MB, gitignored, and
  the only copy of the 122 real articles `import_legacy_content.py` brings
  into production -- they exist nowhere else. `uploads/` is its companion.
- **Never run `seed_phase8_org_fixtures.py` against anything but a local
  stack.** It refuses non-local DSNs; that guard is the point, not an
  obstacle.
