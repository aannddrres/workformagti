# Magti Portal — Internal Call Center Portal

Angular (`angular-frontend/`) + Spring Boot, Flyway and Oracle
(`java-backend/`). ~600 users, call centre department.

> **The legacy FastAPI/PostgreSQL/server-rendered-HTML implementation was
> deleted on 2026-08-31** — 102 files: the application, its templates, its
> CSS/JS, its tests and its Docker artefacts. It is in git history if it is
> ever needed (`git log --diff-filter=D`), and nothing in the working tree
> depends on it.
>
> It was kept until then for a "30-day read-only cutover window". That window
> turned out to be moot: the Python app was never deployed to production —
> no `.env` has ever existed in this repository and there is no go-live in
> the history — so there was nothing to run read-only and no data to migrate.
> Real users are provisioned by AD on first login.

## Sources of truth

- **Product/UX:** `docs/PRODUCT_UX_REQUIREMENTS_KA.md` before changing UX,
  search, content lifecycle, compliance/audit flows, role dashboards, exports
  or branding. It records the product owner's confirmed decisions *and* the
  questions left open; do not resolve an open one by writing code. For the
  approved target information architecture and rollout order, also read
  `docs/UI_UX_REDESIGN_PLAN_KA.md`.
- **Access/authorization:** `docs/ACCESS_CONTRACT_MATRIX_KA.md` states, for
  every backend endpoint, what gates it today and what capability + data scope
  it must have in the target model. `AccessContractCoverageTest` fails the
  build when an endpoint has no row, a row has no endpoint, or a gate changes
  without the contract changing with it — so adding or re-gating an endpoint
  means editing that file in the same commit.
- **Rollout switches:** `docs/ROLLOUT_ROLLBACK_KA.md` — what each
  `ROLLOUT_*` flag does, the criterion for turning one on, and how to roll it
  back.
- **Deployment:** `k8s/README_KA.md` — the manifests and the values IT must
  supply. `docs/QUESTIONS_FOR_IT.md` is the register of what is still
  unanswered.

## Architecture notes

- Search uses the materialized `search_trigrams` table and its Oracle indexes
  (`V29__search_trigram_index.sql`). PostgreSQL `pg_trgm` belonged to the
  deleted stack and is not coming back.
- Auth/backend are owned in this repo, not by Magti IT.
- Corporate SSO is **not wired**: `POST /api/auth/sso/start` answers 503 on
  purpose until IT confirms the protocol (`docs/QUESTIONS_FOR_IT.md` §1).

## Environments

| File | Ports | What it is |
|---|---|---|
| `docker-compose.local.yml` | 8080 | Development on one machine; also what `scripts/run-local.sh` drives |
| `docker-compose.presentation.yml` | 8081 | The demo everyone looks at. Persona login on, loopback only |
| `docker-compose.uat.yml` | 8082 | Acceptance testing. Writes test data, so deliberately a separate stack and volume |
| `k8s/` | — | Production manifests. Twelve values still marked `<<< IT-NN >>>` |

All three compose stacks run Oracle XE in a container and are laptop-only;
none is a deployment manifest.

## Key files

- `java-backend/.../storage/` — `FileStorageService` (uploads live in Oracle
  as BLOBs, not on disk — audit PR-03), `FileTypeVerifier` (magic-byte check,
  SEC-09), `FileAccessPolicy` + `FileReferenceIndex` (DEC-P01: a file is
  readable when content that references it is readable). **Nothing in the
  backend writes to a filesystem at all** — which is why the Kubernetes
  manifests need no PersistentVolume.
- `java-backend/.../security/LoginAttemptStore.java` — the login throttle's
  counts live in Oracle (`login_attempts`, V48), not in each JVM. They were
  per-process, which multiplied every documented limit by the replica count.
  `InMemoryLoginAttemptStore` exists for the fast policy tests and is
  deliberately not a bean.
- `java-backend/.../security/ClientIpResolver.java` — resolves the real
  caller behind a proxy for rate limiting and the audit log. **Needs
  `TRUSTED_PROXIES` set in production or `X-Forwarded-For` is ignored**
  (deliberately safe default — `QUESTIONS_FOR_IT.md` §7).
- `java-backend/.../security/ManagerScope.java` — the single rule for "which
  users may a manager see" (prefix-aware). Read it before adding any
  manager-scoped query; five sites once answered this independently with
  exact string equality (SEC-13).
- `java-backend/.../article/ArticleVisibility.java` — the single rule for
  "may this person read this article", shared by the article endpoint and by
  `/uploads/{filename}` so the two cannot disagree.
- `java-backend/.../security/JwtAuthenticationFilter.java` — authorization is
  re-read from the DB on **every** request, so a role change or deactivation
  takes effect immediately. Also compares the token's `tv` claim against
  `users.token_version`: logout, a password change and an admin password reset
  each increment it (SEC-14). That is *log out everywhere*, not per-session —
  deliberate, see `V34__user_token_version.sql`.
- `java-backend/.../web/GlobalExceptionHandler.java` — the only unhandled
  exception handler; pairs a client-visible correlation id with the stack
  trace in the log.
- `java-backend/.../config/PortalProperties.java` — binds `portal.*`.
  `isProduction()` is the complement of a short development allowlist, so a
  typo or an unknown environment name fails **safe**.

## The legacy knowledge base

The 122 real articles from the retired portal (roaming tariffs, GPON
parameters, the porting procedure) live in `magti_portal.db` and are brought
into Oracle by `scripts/import_legacy_content.py` — content only, no users,
no invented org, idempotent, dry-run by default. They arrive as
`status='draft'` and are released gradually from the admin content queue.

**`is_draft` is not `status='draft'`.** `is_draft` is the personal-autosave
flag, and `GET /api/articles` hides a row carrying it from everyone but its
author — content administrators included. Content meant for several editors to
manage must have `is_draft = false`; the editorial state goes in `status`.
This was got wrong once, on the first import, and made all 122 articles
visible to exactly one account.

`legacy_content_imports` (V47) maps source id to target id, which is what
makes a second run update rather than duplicate.

## The Python that is left

Four tools, and nothing else. They are not the old application; they are
current tooling that happens to be written in Python, and they are
load-bearing.

- `scripts/presentation/` — fills the demo and UAT databases through the real
  API. Reads content from `magti_portal.db`.
- `scripts/uat/seed_uat_accounts.py` — the eleven named UAT accounts.
- `scripts/seed_phase8_org_fixtures.py` — Oracle org fixtures.
- `scripts/import_legacy_content.py` — the legacy knowledge base, above.

`tests/` holds the three test files that cover them; CI runs them in the
`seeder-tools` job. `requirements-dev.txt` pins the same versions the seeder
container images do, on purpose.

**`magti_portal.db` (~183 MB, gitignored) is the demo's content source** —
the articles shown in the presentation and UAT stacks are read out of it.
Do not commit or delete it. `uploads/` (gitignored) is its companion.

## Rules

- **Surgical edits only** — never rewrite a full file; cite file name + line
  number.
- Product target is Georgian-only, Chrome/1080p desktop-first; mobile/touch is
  out of scope for this phase. Keep `ka.json` and `en.json` keys synchronized —
  `npm run check:i18n` enforces it in CI.
- Dark/light theme support required.
- **Flyway migrations do not need to be individually idempotent** and should
  not carry `IF NOT EXISTS`-style guards: Flyway takes an exclusive lock on
  `flyway_schema_history` before applying anything, so simultaneous instances
  serialise — one applies, the other sees the recorded version and skips.
  Plain `CREATE TABLE` / `ALTER TABLE` is correct. The highest migration is
  currently `V48`.
- **Never commit secrets.** Every environment takes them from `${VAR}`
  substitution, and each compose file uses `:?` so a missing value fails the
  start rather than defaulting. In Kubernetes they come from a Secret created
  out of band (`k8s/README_KA.md`). `ProductionSafetyGuard` refuses to boot on
  a blank, short, placeholder or shipped-development secret, so a copy-paste
  into production fails the rollout instead of reaching users.
- **Do not resolve an audit finding by deleting its evidence.** Findings in
  `docs/READINESS_REPORT_2026-08-23.md` and the UAT documents keep their
  original text; what changed since is appended as a dated disposition.
- Worktree/branch hygiene: when work in a `.claude/worktrees/*` checkout is
  finished, remove the worktree (`git worktree remove`) and its `claude/*`
  branch in the same session. (8 stale worktrees / 16 branches / 1.2GB
  accumulated silently over ~3 weeks before the 2026-07-11 cleanup.)
- Questions only Magti's IT department can answer (AD/SSO, Kubernetes,
  network topology — anything needing knowledge of real internal
  infrastructure rather than this codebase) go in `docs/QUESTIONS_FOR_IT.md`,
  not repeatedly to the user. Add new ones there with the context for why the
  answer matters, and keep working on whatever does not depend on it. Check
  that file for already-answered items before re-asking.
- **No broad retention purge in the Java backend** without an approved
  retention policy. The deleted Python stack had a 180-day archive-then-purge;
  it was never ported, deliberately. Quiz attempts, read receipts and
  compliance evidence are explicitly excluded from any generic purge.
