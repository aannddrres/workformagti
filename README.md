# Magti Portal — Internal Call Centre Portal

A knowledge base and compliance-tracking system for Magti's ~600-person call
centre: articles and news, mandatory reading with quizzes, department-scoped
visibility, an audit trail, and exports for managers.

**Stack:** Angular 22 (`angular-frontend/`) · Java 21 + Spring Boot 4.1 +
Flyway (`java-backend/`) · Oracle.

> The original FastAPI/PostgreSQL implementation, with its server-rendered
> HTML and vanilla JS, was removed on 2026-08-31. It is in git history if it
> is ever wanted. Nothing here depends on it.

---

## Run it on one machine

```bash
scripts/run-local.sh
```

Oracle in Docker, the Spring Boot backend, and the Angular dev server — one
command, Ctrl+C to stop. The same recipe the E2E job in CI uses.

A brand-new database has a schema and no content, because Flyway creates
tables and users are provisioned on first login. To have something to look
at:

```bash
scripts/seed-demo-content.sh
```

### The other two environments

| | Port | For |
|---|---|---|
| `docker-compose.presentation.yml` | 8081 | The demo. A persona picker on the login page, ~600-person org, real content |
| `docker-compose.uat.yml` | 8082 | Acceptance testing. Eleven named accounts; writes test data, so it is deliberately a separate stack |

Both are driven by their PowerShell wrappers (`presentation.ps1`, `uat.ps1`)
and are published on `127.0.0.1` only. Neither is a deployment manifest.

---

## Tests

One command runs what CI runs, in CI's order, and refuses to report success
for a job it could not run here:

```bash
scripts/verify-like-ci.sh fast
```

`fast` is the three jobs every branch push runs; `oracle` is the Oracle-backed
Java suite; no argument runs both. The Oracle jobs are gated to pull requests
and `main`, so between a branch push and a PR this script is the verification.

The pieces, if you want one alone:

```bash
cd java-backend && ./mvnw test -DexcludedGroups=oracle   # fast, no database
cd java-backend && ./mvnw test -Dgroups=oracle           # the Oracle half
cd angular-frontend && npx ng test --watch=false
cd angular-frontend && npm run lint && npm run check:i18n
python -m pytest tests/ -q                               # the seeder tooling
```

Plain `./mvnw test` runs both halves. Set `ORACLE_DB_URL` to point at a
database, or leave it unset and it uses a local Oracle if one answers — and
starts a throwaway container if none does, which is slow.

CI runs all of these plus a Playwright E2E job that drives the real browser
against the real backend against a real Oracle.

---

## Deploying it

`k8s/` holds the Kubernetes manifests. Twelve values are marked
`<<< IT-NN >>>` and must be supplied by Magti's platform team;
`k8s/README_KA.md` is written for them and explains each one.

**It cannot go live yet.** Corporate SSO is not connected —
`POST /api/auth/sso/start` answers 503 on purpose — so nobody can sign in
with a real identity. That, the Oracle backup policy, and the CI/CD path into
Magti's registry are tracked in `docs/QUESTIONS_FOR_IT.md`.

---

## Where the documentation is

**[`docs/README.md`](docs/README.md)** is the index. It lists every document
once, with its date and what kind it is — decision, reference, plan,
acceptance, or dated history — so you can tell how far to trust a file before
you read it. A build test keeps that index complete: a document with no row,
or a row pointing at nothing, fails CI.

Dated history lives in `docs/archive/` and is never corrected in place. An
audit's finding is never resolved by deleting it; if something in a dated
report is no longer true, a dated disposition is appended underneath and the
original text stays.

**[`AGENTS.md`](AGENTS.md)** holds the working rules for this repository — the
commands, the invariants, and what not to change casually. It is read by
Claude Code, Codex, Cursor and Copilot alike; `CLAUDE.md` imports it.
