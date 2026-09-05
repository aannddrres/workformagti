# Magti Portal — working notes for coding agents

Internal knowledge base and compliance portal for Magti's ~600-person call
centre: articles and news, mandatory reading with quizzes, department-scoped
visibility, a tamper-evident audit trail, and manager exports.

Product target is **Georgian-only, Chrome, 1080p desktop**. Mobile and touch
are out of scope for this phase. Dark and light themes are both required.

This file is the map. It holds what is always true; everything else is reached
from here, so that neither half goes stale by being restated.

## Stack

| | |
|---|---|
| Backend | `java-backend/` — Java 21, Spring Boot 4.1.0, Maven wrapper (`mvnw` / `mvnw.cmd`) |
| Frontend | `angular-frontend/` — Angular 22, Node 22.22.3 (pinned in `.nvmrc`) |
| Database | Oracle. Flyway owns the schema; the highest migration is `V48` |
| Tooling | Four Python seeders in `scripts/`, covered by `tests/` |

The FastAPI/PostgreSQL/server-rendered implementation was deleted on
2026-08-31 (102 files). It is in git history; nothing in the tree depends on
it. Treat any document describing `main.py`, `routers/` or `pg_trgm` as
history, not as a description of this codebase.

## Commands

Get it running — either works, the first needs only Docker:

```bash
docker compose -f docker-compose.local.yml up --build
```

```bash
scripts/run-local.sh
```

The first serves the whole stack on `:8080`; the second is the developer loop,
with the Angular dev server on `:4200`. Docker Desktop needs **6 GB or more**
of memory or Oracle dies without saying why, and a first run takes 10–15
minutes. Any password logs in as `admin@magti.ge`, `content@magti.ge`,
`manager@magti.ge`, `info@magti.ge` or `tech@magti.ge` while
`APP_ENV=development`.

Verify a change — **this is the one to reach for**:

```bash
scripts/verify-like-ci.sh fast
```

`fast` is what every branch push runs in CI; `oracle` is the Oracle-backed
Java suite; no argument runs both. It runs the same commands CI runs, in the
same order, and exits non-zero rather than report success for a job it could
not run here. The Oracle-backed CI jobs are gated to pull requests and `main`,
so between a branch push and a PR this script *is* the verification.

The individual loops, when you want one of them alone:

```bash
cd java-backend && ./mvnw -B test -DexcludedGroups=oracle
```

```bash
cd angular-frontend && npx ng test --watch=false && npm run check:i18n
```

```bash
python -m pytest tests/ -q
```

**The Java split matters.** Plain `./mvnw test` runs both halves, and 44 test
classes need a database — with `ORACLE_DB_URL` unset, Testcontainers starts
one, which is slow. `-DexcludedGroups=oracle` is the loop to iterate in;
`-Dgroups=oracle` is the other half. On Windows use `.\mvnw.cmd`.

End-to-end (Playwright) needs a stack already running; the config will not
start one. The recipe is the `e2e` job in `.github/workflows/ci.yml`, and
`scripts/verify-like-ci.sh` deliberately refuses to automate it rather than
get it quietly wrong.

Two more stacks exist, driven by their PowerShell wrappers and published on
`127.0.0.1` only. Neither is a deployment manifest, and both run Oracle XE in
a container like the local one:

| | Port | For |
|---|---|---|
| `presentation.ps1 prepare` | 8081 | The demo. Persona login on, 602-person org, real content |
| `uat.ps1 prepare` | 8082 | Acceptance testing. Eleven named accounts; writes test data, so a separate stack and volume on purpose |

## Where things are

| Path | What, and where its own notes are |
|---|---|
| `java-backend/` | Spring Boot — see `java-backend/AGENTS.md` |
| `angular-frontend/` | Angular — see `angular-frontend/AGENTS.md` |
| `scripts/` | Seeders and entry points — see `scripts/AGENTS.md` |
| `docs/` | Living reference plus `docs/archive/` — start at `docs/README.md` |
| `k8s/` | Production manifests; twelve values still `<<< IT-NN >>>` |
| `tests/` | Pytest suite for the seeders |

## One rule, one place

Each of these is the *single* implementation of a rule that used to be, or
could easily become, several. Read the file before writing a second copy.

- `security/ManagerScope.java` — which users a manager may see (prefix-aware).
  Five sites once answered this independently with exact string equality
  (SEC-13).
- `article/ArticleVisibility.java` — may this person read this article. Shared
  by the article endpoint and by `/uploads/{filename}` so the two cannot
  disagree. Its reader-facing subset is mirrored in
  `angular-frontend/src/app/shared/article-visibility.ts`, and the cases both
  must agree on are pinned in `docs/api-contract/article-visibility-cases.json`.
- `security/ClientIpResolver.java` — the real caller behind a proxy, for rate
  limiting and the audit log. Needs `TRUSTED_PROXIES` set in production or
  `X-Forwarded-For` is ignored (a deliberately safe default).
- `security/LoginAttemptStore.java` — login-throttle counts live in Oracle,
  not in each JVM; they were per-process, which multiplied every documented
  limit by the replica count.
- `security/JwtAuthenticationFilter.java` — authorization is re-read from the
  database on *every* request, so a role change or deactivation takes effect
  immediately. The `tv` claim against `users.token_version` is log-out-
  everywhere, not per-session, and that is deliberate.
- `storage/FileAccessPolicy.java` — a file is readable when content that
  references it is readable (DEC-P01). Uploads are Oracle BLOBs; **nothing in
  the backend writes to a filesystem at all**, which is why the Kubernetes
  manifests need no PersistentVolume.
- `config/PortalProperties.java` — `isProduction()` is the complement of a
  short development allowlist, so a typo or an unknown environment name fails
  *safe*.
- `web/GlobalExceptionHandler.java` — the only unhandled-exception handler;
  pairs a client-visible correlation id with the stack trace in the log.

## Rules

- **Surgical edits only.** Never rewrite a whole file; cite file and line.
- **Re-gating an endpoint means editing `docs/ACCESS_CONTRACT_MATRIX_KA.md` in
  the same commit.** A row of coverage tests reads the source and the document
  against each other and fails the build on any mismatch; they are listed in
  `java-backend/AGENTS.md`.
- **Flyway migrations are not individually idempotent and must not carry
  `IF NOT EXISTS`-style guards.** Flyway takes an exclusive lock on
  `flyway_schema_history` before applying anything, so simultaneous instances
  serialise — one applies, the other skips. Plain `CREATE TABLE` is correct.
- **`is_draft` is not `status='draft'`.** `is_draft` is the personal-autosave
  flag, and it hides a row from everyone but its author — content
  administrators included. Editorial state goes in `status`. Getting this
  wrong once made all 122 imported articles visible to exactly one account.
- **Never commit secrets.** Every environment reads `${VAR}` with `:?`, so a
  missing value fails the start rather than defaulting. `ProductionSafetyGuard`
  refuses to boot on a blank, short, placeholder or shipped-development secret.
- **Do not resolve an audit finding by deleting its evidence.** Findings keep
  their original text; what changed since is appended as a dated disposition.
- **Keep `ka.json` and `en.json` in step** — `npm run check:i18n` gates it, and
  ngx-translate renders a missing key as the key itself, so it fails silently.
- **No broad retention purge** without an approved retention policy. Quiz
  attempts, read receipts and compliance evidence are excluded from any
  generic purge.
- **Questions only Magti's IT department can answer** (AD/SSO, Kubernetes,
  network topology — anything needing knowledge of real internal
  infrastructure) go in `docs/QUESTIONS_FOR_IT.md`, with the context for why
  the answer matters. Check there first; some are already settled. Keep
  working on whatever does not depend on the answer.
- **Finished worktree, removed worktree** — `git worktree remove` and its
  `claude/*` branch, in the same session. Eight stale worktrees and 1.2 GB
  once accumulated silently over three weeks.

## Where to look next

- `docs/README.md` — the index. Says, for every document, whether it is a
  decision, a reference or dated history, and how far to trust it.
- `docs/ACCESS_CONTRACT_MATRIX_KA.md` — the per-endpoint authorization contract.
- `docs/PRODUCT_OWNER_DECISIONS_KA.md` — settled product behaviour.
- `docs/QUESTIONS_FOR_IT.md` — what is blocked on Magti IT, and what is not.
- `docs/ROLLOUT_ROLLBACK_KA.md` — what each `ROLLOUT_*` flag does, and how to
  reverse it.
- `k8s/README_KA.md` — deployment, written for Magti's platform team.

Corporate SSO is **not wired**: `POST /api/auth/sso/start` answers 503 on
purpose until IT confirms the protocol. Nobody can sign in with a real
identity yet, so the product cannot go live regardless of code state.
