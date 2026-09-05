# scripts/ — notes for coding agents

Two kinds of thing live here: shell entry points that drive the whole
repository, and four Python tools that are current, load-bearing tooling —
**not** leftovers from the deleted FastAPI application. See the repository
root `AGENTS.md` for the product and the cross-cutting rules.

## Entry points

| Script | What it does | Runs on Windows? |
|---|---|---|
| `run-local.sh` | Oracle in Docker, build and start the backend, `npm start`. `--clean` destroys the database container first. | Git Bash |
| `verify-like-ci.sh` | Runs what CI runs, in CI's order. `fast` \| `oracle` \| no argument for both. Exits non-zero if anything went unverified. | Git Bash |
| `seed-demo-content.sh` | Two categories, five articles, two news items through the real API. Skips if any article exists. POSIX `sh`, because it also runs inside `curlimages/curl`. | Git Bash, or the compose `seed` service |
| `link-skills.ps1` / `.sh` | Recreates `.claude/skills/*` as links to `.agents/skills/*`. Run once after cloning. | PowerShell / Git Bash |
| `load/fetch_tokens.sh` | Pre-fetches JWTs for the k6 load gate. Its output is gitignored — real tokens. | Git Bash + k6 |

`presentation.ps1` and `uat.ps1` sit at the repository root and are native
PowerShell.

`verify-like-ci.sh` uses `python -m pytest`, not the `pytest` on `PATH`,
deliberately: a uv- or pipx-installed `pytest` lives in its own environment,
cannot import the seeders' dependencies, and dies during collection with a
`ModuleNotFoundError` that looks like a broken repository.

## The four Python tools

| Tool | What it is |
|---|---|
| `import_legacy_content.py` | Brings the 122 real articles from the retired portal into Oracle out of `magti_portal.db`. Content only — no users, no invented org. Idempotent through `legacy_content_imports` (V47), which is what makes a second run update rather than duplicate. Dry-run by default. |
| `presentation/seed_oracle_demo.py` | The demo and UAT seeder: 605 employees, personas, real content, through the real API. Refuses to run without `PRESENTATION_SEED_CONFIRM` set to its exact sentinel. |
| `uat/seed_uat_accounts.py` | The eleven named UAT accounts. |
| `seed_phase8_org_fixtures.py` | Deterministic Oracle org fixtures in one transaction. Refuses non-local DSNs and production-like profiles. |

Everything imported arrives as `status='draft'` and is released gradually from
the admin content queue. **It must arrive with `is_draft = false`** — that flag
is the personal-autosave marker and hides a row from every account but its
author. This was got wrong on the first import and made all 122 articles
visible to exactly one person.

Their tests are in `tests/` and run in the `seeder-tools` CI job together with
`ruff check scripts/ tests/`. Ruff is configured in the root `pyproject.toml`
and deliberately selects only `E9` and `F` — real errors, not style.
`requirements-dev.txt` pins the same versions the seeder container images use,
on purpose; change one and change the other.

The Oracle-fixture tests self-skip unless `RUN_ORACLE_FIXTURE_TESTS=true`, so
a green local run does not mean they executed.

## `magti_portal.db`

About 183 MB, gitignored, and **the content source for the demo and UAT
stacks** — the articles shown in a presentation are read out of it. Do not
commit it and do not delete it. `uploads/` is its companion.
