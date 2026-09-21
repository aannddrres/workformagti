---
name: run-and-verify
description: Use when starting the Magti Portal locally, running its tests, or proving a change actually works - "run the app", "start the stack", "run the tests", "does this work", "verify this", "check it in the browser", or before reporting a change as done. Covers docker-compose vs run-local.sh, the verify-like-ci.sh entry point, the Java fast/slow test split, the e2e recipe, and the browser-verification traps this project has hit.
---

# Running it, and proving a change works

## Bringing it up

```bash
docker compose -f docker-compose.local.yml up --build
```

Whole stack on `:8080`. Needs only Docker — no JDK, no Node, no Git Bash.
Stop with `down`; `down -v` also deletes the database.

```bash
scripts/run-local.sh
```

The developer loop: Oracle in Docker, backend built and started, Angular dev
server on `:4200`. Needs Docker, JDK 21 and Node 22.22.3. `--clean` destroys
the database container first.

Two things that look like bugs and are not. **Docker Desktop needs 6 GB or
more** or Oracle dies without a useful message, and a first run takes 10–15
minutes. And `seed exited with code 0` in the compose output is success, not
failure — it is a one-shot container finishing.

Any password logs in as `admin@magti.ge`, `content@magti.ge`,
`manager@magti.ge`, `info@magti.ge`, `tech@magti.ge` or `nino@magti.ge`, and
as any `test_operator_*` or `presentation.*` address (created on first login)
— but only while **both** `APP_ENV=development` and `ALLOW_DEV_LOGIN=true` are
set. Both commands above set both; a backend started by hand needs both, or
every one of these logins fails — CI's first E2E run did exactly that.

For department-scoped checks, know what each carries on a local database:
`tech@` and `manager@` sit in `ტექნიკური` and `info@` in `საინფორმაციო` —
real values, and an older English value is corrected on the next login
(`AuthenticationService.synchronizeDevDepartment`, since `9fe45c4`).
`nino@` is created in `Support`, which matches no content, and
`admin@`/`content@` have English placeholders but bypass audience rules
anyway. For a check at *group* level (`ტექნიკური — ჯგუფი 03`), create a
`test_operator_*` user with that exact department string.

The demo and acceptance stacks are separate and PowerShell-driven:
`./presentation.ps1 prepare` (`:8081`, persona login, ~600-person org) and
`./uat.ps1 prepare` (`:8082`, eleven named accounts, writes test data).

## Verifying

**Reach for this first.** It runs what CI runs, in CI's order, and exits
non-zero rather than report success for a job it could not run here:

```bash
scripts/verify-like-ci.sh fast     # the three jobs every branch push runs
scripts/verify-like-ci.sh oracle   # the Oracle-backed Java suite
scripts/verify-like-ci.sh          # both
```

This matters more than it looks. The Oracle-backed CI jobs are gated to pull
requests and `main` — they exhausted the Actions allowance on 2026-08-22 — so
between a branch push and a PR, "verified" means somebody ran this.

The pieces, when you want one alone:

```bash
cd java-backend && ./mvnw -B test -DexcludedGroups=oracle   # fast, DB-free
cd java-backend && ./mvnw -B test -Dgroups=oracle           # needs Oracle
cd angular-frontend && npx ng test --watch=false
cd angular-frontend && npm run lint && npm run check:i18n
python -m pytest tests/ -q
```

**The Java split is the one to know.** Plain `./mvnw test` runs both halves,
and every `@SpringBootTest` — about forty classes, each tagged
`@RequiresOracle` — needs a database. With `ORACLE_DB_URL` unset it first
tries the default `localhost:1521/orclpdb1` and uses it if it answers (on
this project's development machine a local 19c does); only if nothing answers
does it start a Testcontainer, which is slow. Point `ORACLE_DB_URL` at an
instance to choose explicitly. On Windows, `.\mvnw.cmd`.

`python -m pytest`, not the `pytest` on `PATH`: a uv- or pipx-installed pytest
lives in its own environment, cannot import the seeders' dependencies, and
dies during collection with a `ModuleNotFoundError` that looks like a broken
repository.

End-to-end needs a stack already running — the Playwright config will not
start one. The recipe is the `e2e` job in `.github/workflows/ci.yml`, and
`verify-like-ci.sh` refuses to automate it rather than get it quietly wrong.

## Checking it in a browser

Do this for anything visible. Do not ask the user to look — look, and show
them what you saw.

Three failure modes this project has actually hit, all of which waste an hour
if you do not know them:

**A fix that "did not take" is usually a stale bundle.** The dev server may
not have pushed a fresh build to the tab. Confirm with
`fetch('/main.js', {cache: 'no-store'})` before doubting the code.

**Console readings persist across navigations in the same tab.** An
errors-only view does not mean "an error just now" — filter by recency.

**Clicks can silently no-op** in the sandboxed browser pane on this app —
row buttons, menu buttons, the login submit. The workaround is
`javascript_tool` with `.click()`. And if every `/api/*` call returns 403 for
a whole session, suspect the tunnel rather than the app: switch to the real
Chrome integration to confirm before debugging application code.

## Reporting

Say what you ran and what it said. A job that did not run is not a job that
passed — `verify-like-ci.sh` is built around exactly that distinction, and a
report should keep it. If the Oracle half did not run here, say so.
