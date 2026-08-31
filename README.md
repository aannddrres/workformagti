# Magti Portal — Internal Call Centre Portal

A knowledge base and compliance-tracking system for Magti's ~600-person call
centre: articles and news, mandatory reading with quizzes, department-scoped
visibility, an audit trail, and exports for managers.

**Stack:** Angular 22 (`angular-frontend/`) · Spring Boot 3 + Flyway
(`java-backend/`) · Oracle 19c.

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
| `docker-compose.presentation.yml` | 8081 | The demo. Persona buttons on the login page, 602-person org, real content |
| `docker-compose.uat.yml` | 8082 | Acceptance testing. Eleven named accounts; writes test data, so it is deliberately a separate stack |

Both are driven by their PowerShell wrappers (`presentation.ps1`, `uat.ps1`)
and are published on `127.0.0.1` only. Neither is a deployment manifest.

---

## Tests

```bash
cd java-backend && ./mvnw test          # 848 tests; needs Oracle, or Docker
cd angular-frontend && npx ng test      # 100 tests, no browser needed
cd angular-frontend && npm run check:i18n
python -m pytest tests/ -q              # the seeder tooling
```

The Java suite needs a database. Set `ORACLE_DB_URL` to point at one, or
leave it unset and it will use a local Oracle if one answers — and start a
throwaway container if none does.

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

`docs/` has accumulated a great deal. Before trusting a file, check what kind
it is:

| Kind | Files | Trust |
|---|---|---|
| **Decisions** | `PRODUCT_OWNER_DECISIONS_KA.md`, `ACCESS_CONTRACT_MATRIX_KA.md`, `ENTERPRISE_READINESS_DECISIONS_KA.md` | Authoritative. Code follows these |
| **Plans** | `IMPLEMENTATION_PLAN_KA.md`, `UI_UX_REDESIGN_PLAN_KA.md`, `ROLLOUT_ROLLBACK_KA.md` | Intent, and how to undo it |
| **Audits** | `READINESS_REPORT_2026-08-23.md`, `OPUS5_AUDIT_*.md` | Dated snapshots. Findings keep their original text; what changed since is appended as a disposition |
| **Acceptance** | `ENTERPRISE_READINESS_ACCEPTANCE_MATRIX_KA.md`, `uat/` | What has actually been proven, and by what evidence |
| **For IT** | `QUESTIONS_FOR_IT.md`, `IT_DISCOVERY_*` | Open questions only they can answer |

`CLAUDE.md` holds the working rules for this repository.

An audit's finding is never resolved by deleting it. If something in a dated
report is no longer true, the report says so underneath it and keeps the
original.
