# scripts/ — notes for coding agents

Shell entry points that drive the whole repository, and four Python tools that
are **current tooling, not leftovers** from the deleted FastAPI application.
Each script explains itself in its own header — `verify-like-ci.sh` at length.
What follows is only what those headers do not say.

## Which of these run on Windows

| | |
|---|---|
| Git Bash only | `run-local.sh`, `verify-like-ci.sh`, `seed-demo-content.sh`, `load/fetch_tokens.sh` |
| Native PowerShell | `link-skills.ps1`, and `presentation.ps1` / `uat.ps1` at the repository root |

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

**`requirements-dev.txt` pins the versions the seeder container images use.**
Change one and change the other. Ruff is configured in the root
`pyproject.toml` and selects only `E9` and `F` — real errors, not style.

## Never

- **Never commit or delete `magti_portal.db`.** About 183 MB, gitignored, and
  the content source for the demo and UAT stacks — the articles shown in a
  presentation are read out of it. `uploads/` is its companion.
- **Never run the demo seeder against anything but a local stack.** It refuses
  without `PRESENTATION_SEED_CONFIRM` set to its exact sentinel, and
  `seed_phase8_org_fixtures.py` refuses non-local DSNs; those guards are the
  point, not an obstacle.
