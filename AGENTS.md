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
| Database | Oracle. Flyway owns the schema; the highest migration is `V50` |
| Tooling | Four Python seeders in `scripts/`, covered by `tests/` |

The FastAPI/PostgreSQL/server-rendered implementation was deleted on
2026-08-31 (112 files). It is in git history; nothing in the tree depends on
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
`manager@magti.ge`, `info@magti.ge`, `tech@magti.ge` or `nino@magti.ge`, and
as any `test_operator_*` or `presentation.*` address (created on first login)
— but only while **both** `APP_ENV=development` and `ALLOW_DEV_LOGIN=true` are
set (`AuthenticationService.authenticate`). Both commands above set both; a
backend started by hand with `APP_ENV` alone refuses every one of these
logins, which is exactly how CI's first E2E run failed.

Verify a change — **this is the one to reach for**:

```bash
scripts/verify-like-ci.sh fast
```

`fast` runs CI's language/test jobs; `oracle` is the Oracle-backed Java suite;
no argument runs both. It runs those commands in order and exits non-zero
rather than report success for one it could not run here. The separate
`supply-chain` CI job (SBOM and runtime image scanning) and browser E2E still
need their own results. The Oracle-backed CI jobs are gated to pull requests
and `main`, so between a branch push and a PR this script provides the local
Java/Angular/Python regression result.

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

**The Java split matters.** Plain `./mvnw test` runs both halves, and every
`@SpringBootTest` — about forty classes, each tagged `@RequiresOracle` — needs
a database. With `ORACLE_DB_URL` unset it uses a local Oracle if one answers at
the default `localhost:1521/orclpdb1`, and starts a Testcontainer only if none
does, which is slow. `-DexcludedGroups=oracle` is the loop to iterate in;
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
| `presentation.ps1 prepare` | 8081 | The demo. Persona login on, ~600-person org (exact count: `docs/PRESENTATION_RUNBOOK_KA.md`), real content |
| `uat.ps1 prepare` | 8082 | Acceptance testing. Eleven named accounts; writes test data, so a separate stack and volume on purpose |

## Where things are

| Path | What, and where its own notes are |
|---|---|
| `java-backend/` | Spring Boot — see `java-backend/AGENTS.md` |
| `angular-frontend/` | Angular — see `angular-frontend/AGENTS.md` |
| `scripts/` | Seeders and entry points — see `scripts/AGENTS.md` |
| `docs/` | Living reference plus `docs/archive/` — start at `docs/README.md` |
| `k8s/` | Production manifests; thirteen values still `<<< IT-NN >>>` |
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
  It did not evaluate `is_draft` at all until 2026-09-06, so another
  author's private draft — if its status said `published` — came back in
  full from `GET /api/articles/{id}` to any operator in its target
  departments. Since 2026-09-24, the draft check precedes the content-admin
  bypass: only its author may open a private draft by id or its attachment.
  `ArticleVisibilityDraftTest` records both sides.
- `util/DepartmentMatcher.visibilityTargets` — the department values a
  caller's content is delivered by. Six places built this list inline, and
  `List.of` throws on the null department the schema allows.
- `web/Guards.java` — `requireAuthenticated` and `requireContentManage`, once
  written 23 times between them. `requireSystemAdmin` is deliberately still
  nine copies; `ControllerGuardConsolidationTest` says why.
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
  `IF NOT EXISTS`-style guards.** A two-JVM start against fresh Oracle XE 21c
  on 2026-09-24 observed the instances interleave: each applied 25 disjoint
  migrations, and the final history had 50 unique successful versions through
  V50. Both replicas became ready. Do not assume one JVM applies the entire
  sequence while the other waits. Plain `CREATE TABLE` remains correct; a
  guard could hide a partly applied migration. Repeat the two-replica startup
  on the actual staging Oracle version before production.
- **`is_draft` is not `status='draft'`.** `is_draft` is the personal-autosave
  flag, and it hides a row from everyone but its author — content
  administrators included. Editorial state goes in `status`. Getting this
  wrong once made all 122 imported articles visible to exactly one account.
- **Never commit secrets.** The presentation and UAT compose files read every
  secret as `${VAR:?…}`, so a missing value fails the start rather than
  defaulting; in Kubernetes they come from a Secret created outside git.
  `docker-compose.local.yml` deliberately hardcodes throwaway local-only values,
  and `application.yml` carries development defaults — what keeps those out of
  production is `ProductionSafetyGuard`, which refuses to boot on a blank,
  short, placeholder or shipped-development secret.
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

Corporate login is **wired but not yet switched on**. With
`CORPORATE_AUTH_ENABLED=true`, `POST /api/auth/login` checks the address and
password against the company's OAuth2 token endpoint (`ldap_auth` grant) —
`security/CorporateAuthClient.java` for the exchange, `CorporateLoginService`
for the account — and never keeps the tokens it gets back. Roles come from the
directory on **every** sign-in through `OAUTH_ROLE_MAP` (owner decisions,
2026-09-21/23). No mapped InfoPortal role means no portal entry and revokes
previous portal tokens. The admin screens show roles read-only and the API
refuses a role change; deactivation stays the portal's and outranks a correct password.
`POST /api/auth/sso/start` still answers 503 — no redirect flow exists or is
planned. What blocks going live is on IT's side: the InfoPortal client's own
credential (the one in their email belongs to another application) and the
InfoPortal roles in their system — `docs/QUESTIONS_FOR_IT.md` №13, `k8s/`
IT-15 and IT-16. The development personas keep their bypass outside
production.
