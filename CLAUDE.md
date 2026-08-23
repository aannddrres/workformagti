# Magti Portal — Internal Call Center Portal

> ⚠️ **Stack transition notice (2026-08-22):** the active target and primary
> implementation are `angular-frontend/` + `java-backend/` — Angular, Spring
> Boot, Flyway and Oracle. The FastAPI/PostgreSQL/template material below is a
> legacy snapshot kept for the 30-day read-only cutover window and historical
> comparison. New production behavior must follow
> `docs/PRODUCT_OWNER_DECISIONS_KA.md`, `docs/ACCESS_CONTRACT_MATRIX_KA.md` and
> `docs/IMPLEMENTATION_PLAN_KA.md`.

## Legacy Python stack (historical; not the target architecture)
- **Backend:** FastAPI + Uvicorn (local dev) / Gunicorn 4 workers (Docker prod)
- **ORM:** SQLAlchemy (`models.py`); **validation:** Pydantic v2 (`schemas.py`, `pydantic[email]`)
- **Auth:** JWT via python-jose + passlib + `bcrypt==4.0.1` (`security.py`)
  - bcrypt pinned to 4.0.1 — passlib 1.7.4 breaks with bcrypt ≥ 4.1
- **Database:** PostgreSQL 15 (Docker, prod) with `pg_trgm` + GIN indexes for sub-2s ILIKE search; local dev uses SQLite (`magti_portal.db`)
- **Cache:** Redis 7
- **Exports:** openpyxl (XLSX), reportlab (PDF — DejaVu Sans for Georgian glyphs); endpoints degrade to 503 if libs missing
- **Templates:** server-rendered HTML (`base-layout.html`, `login.html`, `article.html`), `static/`, `uploads/`

## Architecture
- ~600 users, call center department
- Auth/backend owned in this repo (not by Magti IT — that was an outdated assumption)
- The active Java/Oracle search uses the materialized `search_trigrams` table
  and its Oracle indexes introduced by `V29__search_trigram_index.sql`.
  PostgreSQL `pg_trgm` belongs only to the legacy Python/Postgres stack.
- **Product/UX source of truth:** read
  `docs/PRODUCT_UX_REQUIREMENTS_KA.md` before changing UX, search, content
  lifecycle, compliance/audit flows, role dashboards, exports or branding.
  It records the product owner's confirmed decisions and unresolved questions;
  do not infer answers for items explicitly left open there.
- **Access/authorization source of truth:** `docs/ACCESS_CONTRACT_MATRIX_KA.md`
  states, for all 112 backend endpoints, what gates each one today and what
  capability + data scope it must have in the target model.
  `AccessContractCoverageTest` fails the build when an endpoint has no row,
  a row has no endpoint, or a gate changes without the contract changing with
  it — so adding or re-gating an endpoint means editing that file in the same
  commit. Its `⚠` rows are open decisions: do not resolve one by writing code. For the approved
  proposed target information architecture, component behavior, rollout order and
  acceptance criteria, also read `docs/UI_UX_REDESIGN_PLAN_KA.md`.

## Docker (`docker-compose.yml`)
| Container | Image | Port | Role |
|-----------|-------|------|------|
| `magti-portal-app` | built (`magtibase-app`) | 8000 | FastAPI app, 4 gunicorn workers |
| `magti-portal-db` | postgres:15-alpine | (not host-mapped) | `max_connections=500` |
| `magti-portal-redis` | redis:7-alpine | (internal) | appendonly, 256mb LRU |
| `magti-portal-migrate` | built | — | one-shot schema init before app |
| `magti-portal-backup` | built | — | runs `backup.py` every 24h |
| `magti-portal-compliance-alerts` | built | — | runs `compliance_alerts.py` every 24h (separate process, not imported by `main.py`) |

- DB URL (in-network): built from `.env`'s `DATABASE_URL` (e.g. `postgresql+psycopg2://appuser:<POSTGRES_PASSWORD>@db:5432/magti_portal`) — not hardcoded in `docker-compose.yml`.
- Postgres port 5432 is **not** mapped to the host — add `ports: ["5432:5432"]` to the `db` service if you need host/MCP access.

## Key Files
- `main.py` (~620 lines) — app/middleware setup only; **no routes live here
  anymore**. Wires up the 14 domain routers via `include_router` and defines
  `actor_context_middleware` (see `audit_trail.py` below)
- `routers/` — the 14 domain routers (articles, auth, users, news, videos,
  categories, favorites, compliance, messaging, exports, audit_logs, search,
  stats, platform); each is a self-contained `APIRouter`
- `db_helpers.py` — shared `get_or_404`, `log_audit`, `resolve_item_title(s_bulk)`
  used across routers to avoid duplicated fetch-or-404 / audit-log boilerplate
- `state.py` — shared app-level singletons (moved out of `main.py`)
- `java-backend/src/main/java/ge/magti/portal/storage/` — `FileStorageService`
  (uploads live in Oracle as BLOBs, not on disk — audit PR-03) and
  `FileTypeVerifier` (magic-byte check, SEC-09). Sole owner of where an
  upload lives, so a later move to S3/RWX changes one class. Since PR-03
  **nothing in the backend writes to a filesystem at all** (verified: no
  `Files.write`/`createDirectories`/`transferTo`/`FileOutputStream` under
  `src/main/java`). Three classes still *read or delete* paths —
  `ExportController` and `ExportJobCleanupScheduler` for pre-V31 export
  files, `GeorgianPdfFont` for a system font it no longer needs — all of
  which go dead once no pre-V31 rows remain.
- `java-backend/.../security/ClientIpResolver.java` — resolves the real caller
  behind a proxy for rate limiting and the audit log. **Needs
  `TRUSTED_PROXIES` set in production or `X-Forwarded-For` is ignored**
  (deliberately safe default — see QUESTIONS_FOR_IT.md §7)
- `java-backend/.../web/GlobalExceptionHandler.java` — the only
  unhandled-exception handler; pairs a client-visible correlation id with the
  stack trace in the log
- `java-backend/.../security/ManagerScope.java` — the single rule for "which
  users may a manager see" (prefix-aware, so a parent-department manager sees
  their subtree and a sub-group manager only their own group). Read it before
  adding any new manager-scoped query; five sites used to answer this
  question independently with exact string equality (SEC-13)
- `java-backend/.../security/JwtAuthenticationFilter.java` — authorization is
  re-read from the DB on **every** request, so a role change or deactivation
  takes effect immediately. Also compares the token's `tv` claim against
  `users.token_version`: logout, a password change and an admin password
  reset each increment it, which revokes tokens already issued (SEC-14).
  This is *log out everywhere*, not per-session — deliberate, see
  `V34__user_token_version.sql`
- `qa_accounts.py` — QA/dev seed test-account list (`TEST_ACCOUNTS`,
  `TEST_ACCOUNT_PASSWORD`), imported by `security.py`'s JIT provisioning and
  `scripts/seed_portal.py`
- `models.py` — SQLAlchemy models
- `schemas.py` — Pydantic schemas
- `security.py` — auth/JWT/hashing
- `audit_trail.py` — ORM auto-audit listeners (deep old/new diffs on
  Article/News/Category/Video/User/RequiredReading); actor identity comes from
  main.py's `actor_context_middleware` ContextVar, NOT `get_current_user` —
  sync dependencies run in a copied threadpool context, so a set there is lost
- `retention.py` — 180-day archive-then-purge for `audit_logs` +
  `article_view_logs` (`AUDIT_RETENTION_DAYS`); run daily by `backup.py`.
  **This policy is not ported to the Java/Oracle backend.** Do not recreate a
  broad purge there without an approved retention policy; quiz attempts, read
  receipts/acknowledgments and compliance evidence are explicitly excluded
  from any generic purge.
- `database.py` — DB session/engine
- `config.py` — settings (`.env` via python-dotenv; see `.env.example`); also
  holds a startup guard that refuses to boot when `APP_ENV=production` with a
  dev-default `SECRET_KEY` or `COOKIE_SECURE=false`
- `migrate.py` — idempotent schema migration (Phase B → Alembic)
- `seed.py` / `scripts/seed_test_users.py` — data seeding (one-off/dev seeders live in `scripts/`)
- `scripts/etl/` — the Postgres→Oracle cutover ETL. `spec.py` is the single
  source of truth for what migrates: every table in either schema must be in
  `PLAN`, `NOT_MIGRATED` or `SOURCE_ONLY`, and `tests/etl/test_spec_coverage.py`
  parses the Flyway migrations + `models.py` to enforce that — so a new `V43`
  table breaks the suite until someone decides whether data crosses into it.
  Ids are preserved (identity columns are flipped for the load), and
  `reconcile.py` compares the Postgres audit `row_hash` with the one V28's
  trigger recomputes in Oracle — the migration's strongest evidence.
  Runbook + open items: `docs/DATA_MIGRATION_PG_TO_ORACLE_KA.md`
- `backup.py` — DB backup job
- `start_server.bat` — local launch
- `docs/admin-guide.md`, `README.md` — docs

### Admin CMS Enhancements (Additive)
- `static/js/admin-cms-enhancements.js` is active and loaded after `app-core.js`/`app-renderers.js`/`app-router.js` in `base-layout.html`; it dynamically hooks into `window.focusCreateForm` and `window.editArticle` (function wrapping, originals preserved) to refresh the char counter and status badge when the article drawer opens.
- `window.statusStylesMap` is exposed from `app-renderers.js` (set right after its local declaration) so other modules can reuse the same status→color mapping for dynamic color parsing instead of duplicating it.
- Global `window` `dragover`/`drop` default-navigation blocking is scoped to this module only, to stop accidental file drops outside `#article-dropzone` from navigating the tab.

## Rules for Claude Code
- Surgical edits only — never rewrite full files; cite file name + line number
- Product target is Georgian-only, Chrome/1080p desktop-first; mobile/touch is
  out of the current scope (confirmed in `docs/PRODUCT_UX_REQUIREMENTS_KA.md`).
  Keep `ka.json` and `en.json` keys synchronized and keep the i18n CI guard
  until a dedicated, explicitly approved cleanup removes the legacy English
  locale infrastructure.
- Dark/light theme support required
- Desktop-first responsive layout across supported desktop monitor sizes;
  narrow/mobile UX must not drive redesign priorities in the current phase.
- Migrations: the idempotency rule applies to the **Python** `migrate.py` only
  (its workers race on `CREATE TABLE`). Flyway migrations in
  `java-backend/src/main/resources/db/migration/` do **not** need to be
  individually idempotent and should not carry `IF NOT EXISTS`-style guards:
  Flyway takes an exclusive lock on `flyway_schema_history` before applying
  anything, so simultaneous instances serialise — one applies, the other sees
  the recorded version and skips. Plain `CREATE TABLE` / `ALTER TABLE` is
  correct there. (Verified in audit 2, BL-13; the highest migration is
  currently `V35`.)
- Never commit secrets — `SECRET_KEY`/`POSTGRES_PASSWORD`/`APP_ENV` are already externalized to `${VAR}` substitution in `docker-compose.yml` (not hardcoded); they come from a local, gitignored `.env` (see `.env.example`). No `.env` currently exists in this repo — one must be created (with a real `SECRET_KEY` and `APP_ENV=production`) before any real deployment. `docker-compose.yml` now falls back to `APP_ENV=production` if `.env` is missing/incomplete, so an absent `.env` fails safe rather than silently reopening the dev-bypass.
- `magti_portal.db` (~183 MB) is local dev data — do not commit or delete
- Worktree/branch hygiene: when work in a `.claude/worktrees/*` checkout is finished (merged or abandoned), remove the worktree (`git worktree remove`) and its `claude/*` branch (`git branch -D`) in that same session — don't leave it for later. Before deleting an unmerged one, check `git diff`/`git log` against `main` for anything not yet captured. (8 stale worktrees / 16 branches / 1.2GB accumulated silently over ~3 weeks before a full cleanup on 2026-07-11 — see `docs/PRODUCTION_HANDOVER.md` for the one real deliverable that was almost lost in the pile.)
- Questions only Magti's IT department can answer (AD/SSO specifics, Kubernetes cluster/CI-CD specifics, network/infra topology — anything requiring knowledge of real internal infrastructure, not the codebase) go in `docs/QUESTIONS_FOR_IT.md`, not asked repeatedly of the user. Add new ones there with context on why the answer matters; keep working on whatever doesn't depend on the answer instead of blocking. The user reviews that file with IT periodically — check it for already-answered items before re-asking.
