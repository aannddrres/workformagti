# Magti Internal Portal — Production Handover Document

| | |
|---|---|
| **Document Owner** | Engineering Team |
| **Audience** | Enterprise IT / DevOps |
| **Status** | Pre-Production Handover |
| **Last Updated** | 2026-07-19 |

> ⚠️ **Read [Section 7 — Outstanding Risks Before Go-Live](#7-outstanding-risks-before-go-live) before deploying.** Several items in the current `docker-compose.yml` are development-grade defaults that **must** be remediated prior to any production cutover.

---

## 1. System Architecture Overview

The Magti Internal Portal is a **monolithic FastAPI application** serving a server-rendered HTML front end (Jinja-style templates + vanilla JS, no SPA framework/build step) to ~600 internal call-center users.

| Layer | Technology | Notes |
|---|---|---|
| Web/App Server | **FastAPI** (ASGI) on **Uvicorn** (dev) / **Gunicorn 4 workers w/ `UvicornWorker`** (prod, via Docker) | `main.py` is now app-instance construction, lifespan, and middleware/router-wiring only — **no route handlers remain in `main.py`** (626 lines: imports/logging + SQLite migration helper, lines 1-348; async log-writer + lifespan, 363-455; `FastAPI()` construction incl. conditional `docs_url`/`redoc_url`/`openapi_url`, 457-463; CORS middleware, 484-490; 14 `include_router()` calls, 494-521; 3 middleware functions — `security_headers`, `actor_context_middleware`, `catch_unhandled_exceptions` — 524-626). See the Routers row below for where routes actually live. |
| Routers | 14 domain routers under `routers/`: `articles`, `audit_logs`, `auth`, `categories`, `compliance`, `exports`, `favorites`, `messaging`, `news`, `platform`, `search`, `stats`, `users`, `videos` | All HTTP route handlers live here, one module per domain — wired into the app via `include_router()` calls in `main.py` (lines 494-521) |
| ORM | **SQLAlchemy** (`models.py`) | `Base.metadata.create_all` bootstrap, idempotent column patches in `migrate.py` |
| Validation | **Pydantic v2** (`schemas.py`), `pydantic[email]` | Request/response schema enforcement |
| Auth | **JWT** (python-jose, HS256) + **passlib/bcrypt** password hashing (`security.py`) | Stateless bearer token **or** httpOnly cookie; RBAC via FastAPI dependencies |
| Database | **PostgreSQL 15** (Docker/prod), `pg_trgm` extension + GIN indexes for fast `ILIKE` search | **SQLite** (`magti_portal.db`) is the local-dev-only fallback — not for production |
| Cache | **Redis 7** (appendonly, 256MB, allkeys-lru) | Backs `RedisEventBroker`, the SSE pub/sub broker for live update push (`state.py:82-228`, not `main.py`) — confirm current usage scope before relying on it as a hard dependency |
| Exports | **openpyxl** (XLSX), **reportlab** (PDF, DejaVu Sans bundled for Georgian glyph support) | Both degrade gracefully (HTTP 503) if the library is missing — not a hard crash |
| Templates / Static | Server-rendered HTML (`base-layout.html`, `login.html`, `article.html`), `static/`, `uploads/` | No frontend build pipeline; assets served directly by FastAPI `StaticFiles` mounts |
| Background Jobs | One-shot `migrate` container (schema bootstrap) + two long-running 24h-cycle sidecars: `backup` (`backup.py`) and `compliance-alerts` (`compliance_alerts.py`) | All three run as separate Docker Compose services from the same app image |

**Bilingual UI:** Georgian + English strings are present throughout templates and validation error messages — do not strip or "clean up" Georgian text during any refactor.

---

## 2. Environment Variables (`.env`)

The application is fully environment-driven (`config.py`, loaded via `python-dotenv`). Copy `.env.example` → `.env` and set the values below before any non-local deployment. **No production secret currently has a safe default — every value marked "MUST override" ships with an insecure development default in code.**

| Variable | Purpose | Default (dev) | Production Guidance |
|---|---|---|---|
| `APP_ENV` | Master environment switch. `production` disables the mock-AD bypass and dev conveniences (see §5). | `development` | **MUST be `production`.** See §7 — current compose file does not set this correctly. |
| `SECRET_KEY` | HMAC signing key for all JWTs (access + password-reset tokens). | `super-secret-temporary-key-for-local-development` | **MUST override.** Generate with: `python -c "import secrets; print(secrets.token_urlsafe(64))"`. Treat as a credential — store in a secrets manager (Vault/AWS Secrets Manager/Azure Key Vault), not in compose/CI files. |
| `JWT_ALGORITHM` | JWT signing algorithm. | `HS256` | Leave as `HS256` unless migrating to asymmetric (RS256) keys. |
| `ACCESS_TOKEN_EXPIRE_MINUTES` | Access token lifetime. | `60` | Tune to internal session-length policy. |
| `DATABASE_URL` | SQLAlchemy connection string. | `sqlite:///./magti_portal.db` | **MUST override** to PostgreSQL: `postgresql+psycopg2://<user>:<pass>@<host>:5432/<db>`. SQLite is dev-only and is excluded from version control (`magti_portal.db`, ~183MB of local seed data — do not commit/delete). |
| `COOKIE_SECURE` | Marks the auth cookie `Secure` (HTTPS-only). | `false` | **MUST be `true`** once served behind TLS. |
| `COOKIE_SAMESITE` | SameSite policy for the auth cookie. | `lax` | Keep `lax` unless cross-site embedding is required. |
| `CORS_ORIGINS` | Comma-separated list of allowed origins. | `http://127.0.0.1:5500,http://localhost:5500` | **MUST override** to the real production origin(s) (e.g. `https://portal.magti.ge`). Never use `*` with `allow_credentials=True`. |
| `UPLOAD_DIR` | Filesystem path for user-uploaded attachments. | `uploads` | Mount as a persistent volume (already configured as `app_uploads` in compose). |
| `MAX_UPLOAD_SIZE_BYTES` | Per-file upload size cap. | `10485760` (10 MB) | Adjust per storage/ops policy. |

> **Production-startup guard (`config.py:117-130`):** when `APP_ENV=production`, the app now fails fast at import time rather than booting insecurely — it raises `RuntimeError` if `SECRET_KEY` is still the development default, and again if `COOKIE_SECURE` is not `true`. A misconfigured production deploy refuses to start instead of silently serving traffic with a guessable JWT key or a cookie sent over plain HTTP. This guard only fires once `APP_ENV` actually resolves to `production` — see §7, item 1.

Additional variables consumed directly by `docker-compose.yml` (not read by `config.py`, but required for the Postgres/Redis containers):

| Variable | Purpose |
|---|---|
| `POSTGRES_PASSWORD` | PostgreSQL container password — **must match** the credentials embedded in `DATABASE_URL`. Substituted via `${POSTGRES_PASSWORD}` in `docker-compose.yml` (lines 78-80, 113-115). |
| `POSTGRES_USER` / `POSTGRES_DB` | **Not actually configurable.** These are hardcoded literals in `docker-compose.yml` (`appuser` / `magti_portal`, lines 78-80, 113-115) — `.env.example` does not define them either. Setting `POSTGRES_USER=`/`POSTGRES_DB=` in `.env` has **no effect**; if a different user/db name is needed, edit `docker-compose.yml` directly. |
| `REDIS_URL` | Redis connection string for the app container (`redis://redis:6379/0` in-network). |
| `WORKERS` | Declared in compose and **consumed for logging behavior only** — `main.py:53-55` uses `os.getenv("WORKERS", "1") not in ("", "1")` to decide whether to skip the (non-multi-process-safe) `RotatingFileHandler` and log to stdout only instead. It does **not** control actual Gunicorn worker count, which remains hardcoded to `--workers 4` in `Dockerfile:41`. |
| `DEBUG` | Declared in compose; verify consumption in application code before assuming it gates anything. |

---

## 3. Deployment Instructions (Docker / Docker Compose)

### 3.1 Topology

```
magti-portal-migrate  (one-shot, runs migrate.py, must complete successfully)
        │
        ▼
magti-portal-app  ◄──┬── magti-portal-db (postgres:15-alpine, max_connections=500)
   (gunicorn,         └── magti-portal-redis (redis:7-alpine, appendonly)
    4 workers,
    port 8000)

magti-portal-backup             (sidecar, sleeps 24h, then runs backup.py)
magti-portal-compliance-alerts  (sidecar, sleeps 24h, then runs compliance_alerts.py)
```

All services share the `magti-network` bridge network. Named volumes (`postgres_data`, `redis_data`, `app_uploads`, `app_static`, `app_logs`) persist state across container recreation.

### 3.2 Pre-deployment checklist

1. Provision a `.env` (or equivalent secrets injection) per §2. `docker-compose.yml` now reads `SECRET_KEY`/`POSTGRES_PASSWORD`/`APP_ENV` via `${VAR}` substitution rather than hardcoding them — but that means the app will boot with blank/missing values if `.env` isn't actually populated, which is arguably worse than a wrong-but-present default. Confirm `.env` exists and is populated before first deploy.
2. Confirm DNS/TLS termination in front of port `8000` (the app does not terminate TLS itself — front it with a reverse proxy / load balancer, e.g. Nginx, Traefik, or a cloud LB).
3. If host or external/MCP access to PostgreSQL is required, add `ports: ["5432:5432"]` to the `db` service (currently **not** host-mapped — internal network only by design).
4. Decide log shipping: Gunicorn is configured with `--access-logfile -` / `--error-logfile -` (stdout/stderr), plus `--worker-tmp-dir /dev/shm --capture-output --enable-stdio-inheritance` — wire your log aggregator (e.g. Fluentd, CloudWatch, Loki) to the container's stdout, or redirect to the `app_logs` volume.

### 3.3 Build & launch

```bash
# From the project root, with .env populated
docker compose build

# Bring up dependencies + run the one-shot migration, then start the app
docker compose up -d

# Verify the migration container exited 0 before trusting the app container
docker compose ps magti-portal-migrate
docker compose logs magti-portal-migrate
```

The `app` service has `depends_on: migrate: condition: service_completed_successfully`, so Compose will not start `app` until migration finishes cleanly. The app's own Docker `HEALTHCHECK` (`curl -f http://localhost:8000/`) gates orchestrator-level readiness (e.g. behind an LB health probe).

### 3.4 Bare-metal / systemd alternative (no Docker)

If DevOps standardizes on systemd-managed Gunicorn instead of containers:

```ini
# /etc/systemd/system/magti-portal.service
[Unit]
Description=Magti Internal Portal (Gunicorn/Uvicorn)
After=network.target postgresql.service redis.service

[Service]
Type=notify
User=appuser
WorkingDirectory=/opt/magti-portal
EnvironmentFile=/opt/magti-portal/.env
ExecStart=/opt/magti-portal/venv/bin/gunicorn main:app \
    --bind 0.0.0.0:8000 \
    --workers 4 \
    --worker-class uvicorn.workers.UvicornWorker \
    --timeout 120 \
    --access-logfile - --error-logfile -
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Run `python migrate.py` once (with `DATABASE_URL` exported) before the first `systemctl start magti-portal`, and on every subsequent deploy that introduces schema changes — it is safe to re-run (idempotent).

### 3.5 Scaling notes

- Gunicorn worker count is currently hardcoded to `4` in the `Dockerfile` `CMD`; for horizontal scale, prefer running multiple container replicas behind a load balancer over increasing per-container worker count, given the `max_connections=500` ceiling on PostgreSQL.
- Static assets (`/static`, `/uploads`) are served directly by FastAPI's `StaticFiles` — for high traffic, consider offloading these to a CDN or reverse-proxy static cache instead of round-tripping through the app workers.

---

## 4. Database Migrations

### 4.1 Current mechanism: `migrate.py` (idempotent bootstrap script)

There is **no Alembic integration yet** — schema management is a single idempotent Python script, explicitly called out in-code as a bridge ("Replace with Alembic when Phase B lands"). DevOps should treat this as the **authoritative migration path until Alembic is introduced**.

`migrate.py` performs, on every run:

1. `Base.metadata.create_all(bind=engine)` — creates any tables that don't yet exist (SQLAlchemy models in `models.py` are the source of truth for new tables).
2. On PostgreSQL only: `CREATE EXTENSION IF NOT EXISTS pg_trgm` + `CREATE INDEX IF NOT EXISTS ...` GIN trigram indexes on `articles`/`news` title/content/tags columns (enables sub-2-second `ILIKE` search at scale).
3. A hardcoded list of idempotent `ALTER TABLE ... ADD COLUMN` statements (`_ADDED_COLUMNS`) for columns introduced after the initial schema — guarded by an `IF NOT EXISTS`-equivalent existence check, never drops or renames anything.

**Why idempotency matters operationally:** in the Docker Compose topology, this script is deliberately isolated into its own one-shot `migrate` service that must complete *before* the 4-worker `app` container starts — running schema DDL from multiple racing Gunicorn workers previously caused `UniqueViolation` errors on `pg_type_typname_nsp_index`. **Do not** move migration execution into application startup/lifespan code without re-introducing that race.

### 4.2 Running migrations

```bash
# Docker Compose (automatic — runs as the `migrate` service before `app`)
docker compose up -d

# Manual / out-of-band (e.g. CI step before a rolling deploy)
DATABASE_URL=postgresql+psycopg2://<user>:<pass>@<host>:5432/<db> python migrate.py
```

### 4.3 Schema change process (until Alembic lands)

1. Add/modify the SQLAlchemy model in `models.py`.
2. For **new tables**: no further action needed — `create_all` picks them up automatically on next migration run.
3. For **new columns on existing tables**: add an entry to `_ADDED_COLUMNS` in `migrate.py` (table, column, SQL type, default) — `create_all` does **not** alter existing tables.
4. For **column drops/renames/type changes**: not supported by this script by design (it never drops data). These require a hand-written, reviewed migration — flag to engineering before attempting in production.

### 4.4 Seeding initial data

`seed.py` provisions the initial role-based user set and starter knowledge-base content — but its behavior is **mode-dependent, not uniformly idempotent** (see §6 for the full breakdown): running it with **no arguments is destructive** (it deletes and re-seeds core tables), while `seed.py org` and `scripts/seed_portal.py` are idempotent upserts. `scripts/seed_test_users.py` provisions a smaller, separate set of test/QA accounts. See §6 for which script to run and what each produces.

```bash
# Idempotent org-hierarchy seed (safe to re-run)
docker compose exec app python seed.py org

# Currently recommended seeder — see §6 and docs/SEED_GUIDE.md
docker compose exec app python scripts/seed_portal.py
```

### 4.5 Backups

The `backup` Compose service runs `backup.py` every 24 hours, producing a ZIP archive (DB dump + uploads) under `./backups`. For PostgreSQL it shells out to `pg_dump`; for the SQLite dev fallback it copies the `.db` file directly. Confirm `./backups` is mounted to durable, off-host storage (not just the Docker host's local disk) before relying on it as the production backup strategy — there is currently no off-site/offsite replication configured in the compose file.

---

## 5. Security Posture

| Control | Implementation | Location |
|---|---|---|
| **Stateless session auth** | JWT (HS256), accepted via either the `Authorization: Bearer` header or an httpOnly cookie set at login — the header is tried first, falling back to the cookie only if no header is present (previously: bearer always won if present). The token-in-URL-query-parameter fallback was deliberately removed (tokens in URLs leak via access logs, browser history, and Referer headers). | `security.py` (`_candidate_tokens`, `create_access_token`) |
| **Password storage** | bcrypt via passlib (`bcrypt==4.0.1` pinned — passlib 1.7.4 is incompatible with bcrypt ≥ 4.1). | `security.py`, `requirements.txt` |
| **Password policy** | Server-enforced on user creation and self-service password change: min 8 characters, requires upper, lower, and digit. | `security.py` (`validate_password_policy`) |
| **RBAC** | Four canonical roles (`operator` < `manager`/`content_admin` < `admin`), enforced via FastAPI dependency injection (`require_roles`, `get_current_admin_user`, `get_current_system_admin_user`, `get_current_manager_user`). | `security.py` |
| **Granular permissions** | A secondary, per-user JSON permission list (`User.permissions`) layered on top of roles (e.g. `articles.publish`, `users.manage`, `reports.export`) via `require_permission(...)`. `admin` always bypasses this check. | `security.py` |
| **Authorization freshness** | User role/active-status is re-read from the database on **every request** (not trusted from JWT claims), so deactivating an account or changing a role takes effect immediately, even against an already-issued, still-valid token. | `security.py` (`get_current_user`) |
| **Account deactivation enforcement** | A deactivated user holding a valid token is rejected with `403` immediately, not just at next login. | `security.py` |
| **Upload hardening** | Allowed MIME types are an explicit allow-list mapped to a server-controlled extension (never derived from the client-supplied filename); active/executable content types (`.html`, `.svg`, `.js`, `.php`) are intentionally excluded to prevent stored-XSS / arbitrary code execution via upload. Size-capped via `MAX_UPLOAD_SIZE_BYTES`. | `config.py` (`ALLOWED_UPLOAD_TYPES`) |
| **Static/uploaded content** | Served with `X-Content-Type-Options: nosniff` via a global response middleware, preventing the browser from re-interpreting an uploaded file as active content. | `main.py` (`security_headers` middleware) |
| **CORS** | Explicit, environment-driven origin allow-list (`CORS_ORIGINS`) — not wildcarded. `allow_credentials=True` requires this to remain a concrete origin list, never `*`. | `main.py`, `config.py` |
| **Secrets externalized via `.env`** | `SECRET_KEY`, `POSTGRES_PASSWORD`, and `APP_ENV` are read from `${VAR}` substitution in `docker-compose.yml`, not hardcoded. No `.env` exists at the repo root yet — see §7, item 1 — so this only works once one is actually provisioned. | `docker-compose.yml`, `.env.example` |
| **Swagger/OpenAPI exposure** | `main.py` now conditionally disables `/docs`, `/redoc`, and `/openapi.json` when `settings.is_production` is true (`docs_url`/`redoc_url`/`openapi_url=None`), keeping them available for local development only. This is only effective if `APP_ENV=production` is actually set at runtime (see §7, item 1). | `main.py` (`FastAPI(...)` constructor) |
| **Rate limiting** | Per-IP, in-memory limiter (`slowapi`) at 10/minute on `/api/auth/login`, `/api/auth/forgot-password`, and `/api/auth/sso/callback` — brute-force/credential-stuffing mitigation. No Redis backend needed at this scale. | `main.py` (`@limiter.limit(...)`) |
| **CSV/XLSX export hardening** | Formula-injection (CWE-1236) neutralized by quoting/escaping cells that start with `=`, `+`, `-`, `@`, tab, or CR before they reach a spreadsheet writer, so opening an export in Excel/Sheets can't trigger an embedded formula. | `routers/exports.py` (`_sanitize_cell`) |
| **Client-side token storage hardening** | Only an unsigned header+payload JWT "shell" is kept in browser `localStorage` — the real signature is stripped before storage (split on `.`, keep header+payload, append a trailing `.`). The actual credential used for authentication is the httpOnly cookie, never the `localStorage` copy. | `login.html`, `routers/auth.py` (mock-SSO callback page) |

### 5.1 Mock-AD / developer bypass — important operational control

`security.py` defines a hardcoded set of "mock AD" test emails (`admin@magti.ge`, `content@magti.ge`, `manager@magti.ge`, `nino@magti.ge`, `tech@magti.ge`, `info@magti.ge`) for which **any password is accepted**, plus just-in-time auto-provisioning for any `test_operator_*@...` email. This bypass is gated entirely behind `settings.is_production` (i.e. `APP_ENV == "production"`) — the allow-list is hardcoded empty when `APP_ENV=production`. **This makes correctly setting `APP_ENV=production` a hard security requirement, not just a cosmetic flag** — see §7, item 1: no `.env` is provisioned in this repo yet. `docker-compose.yml` now defaults each service's `APP_ENV` to `production` (`${APP_ENV:-production}`) if `.env` doesn't set it, so a missing `.env` fails safe rather than silently reopening this bypass — but a `.env` that sets `APP_ENV=development` (or anything other than `production`) will still open it, same as before.

---

## 6. Default / Seeded Credentials

There are **three separate, non-overlapping seeding paths** in this repo. They are not interchangeable — know which one you're running before you run it.

| Path | Command | Behavior | Accounts produced | Password |
|---|---|---|---|---|
| **1. Legacy full reseed** | `python seed.py` (no args) | **Destructive** — deletes all `ReadStatus`, `ArticleHistory`, `AuditLog`, `SearchLog`, `Message`, `Favorite`, `RequiredReading`, `News`, `VideoInstruction`, and `User` rows before re-seeding (`seed.py:117-126`). **Do not run against a database with real data.** | 6 core role accounts (table below) | `password` |
| **2. Org-hierarchy seed** | `python seed.py org` | Idempotent — upserts a 165-employee org hierarchy by email, never deletes existing rows. | ~165 org accounts + the 6 core accounts | `password` |
| **3. Recommended seeder** | `python scripts/seed_portal.py` (`org` / `users` / `demo` modes) | Idempotent upserts. **Currently the recommended path** per `docs/SEED_GUIDE.md`. Refuses to run at all if `APP_ENV=production` (`scripts/seed_portal.py:53-63`) — an explicit safety gate the other two paths lack. | ~650 users + 3 portal admins (`org`/`demo` modes); 20 QA accounts from `qa_accounts.TEST_ACCOUNTS` (`users` mode) | `password` (org/demo) or `Test1234!` (QA accounts via `users` mode) |

**Path 1's 6 core accounts** (also the six accounts gated by the mock-AD bypass in §5.1):

| Email | Role | Department |
|---|---|---|
| `admin@magti.ge` | `admin` (System Administrator) | Administration |
| `content@magti.ge` | `content_admin` | All |
| `manager@magti.ge` | `manager` | ტექნიკური |
| `nino@magti.ge` | `operator` | ტექნიკური |
| `tech@magti.ge` | `operator` | ტექნიკური |
| `info@magti.ge` | `operator` | საინფო |

`seed.py` explicitly notes (`seed.py:159-161`) that Billing and Sales departments were retired (see `scripts/migrate_departments.py`) — do not re-add dedicated Billing/Sales accounts.

**Source of truth for exact current account lists:** the table above covers only Path 1's core accounts — the ones tied to the security-relevant mock-AD bypass. For the full, current account inventory across all three paths (org hierarchy, QA accounts, demo data), which changes independently of this document, see **`docs/SEED_GUIDE.md`** and **`TEST_LOGINS.md`** at the repo root. Treat those files, not this section, as authoritative for "what accounts exist and what do they log in as."

> ⚠️ **Action required:** Whichever path you use, rotate every seeded password (`password` and `Test1234!` alike) immediately after the first production data load, and disable or re-credential any accounts not actively needed — especially the six accounts that double as the mock-AD bypass list in §5.1. Treat seeding as a one-time bootstrap step, not a steady-state production credential set. Prefer Path 3 (`scripts/seed_portal.py`) for new environments: it is the currently recommended seeder and the only one of the three that refuses to run when `APP_ENV=production`.

---

## 7. Outstanding Risks Before Go-Live

These are concrete gaps observed in the repository state that should be resolved as part of (not after) the production cutover:

1. **No `.env` file provisioned yet — still a live risk, now partially mitigated by a fail-loud startup guard.** `docker-compose.yml` no longer hardcodes secrets — `SECRET_KEY`, `POSTGRES_PASSWORD`, and `APP_ENV` are all `${VAR}` substitutions now — but no `.env` exists at the repo root. Two distinct scenarios follow: **(a) Still-live risk** — with no `.env`, `APP_ENV` defaults to `"development"`, so the guard below does not fire (it only checks when `APP_ENV=production`) and the app boots fine but insecurely: the mock-AD bypass is open (§5.1) and `COOKIE_SECURE` defaults to `false`. **(b) New mitigation** (`config.py:117-130`) — if `APP_ENV=production` is explicitly set but `SECRET_KEY`/`COOKIE_SECURE` are left at their development defaults, the app now **fails loud at import time** (`RuntimeError`) instead of silently booting insecurely — see §2. **Before first deploy:** create `.env` from `.env.example`, generate a real `SECRET_KEY` (`python -c "import secrets; print(secrets.token_urlsafe(64))"`), set a real `POSTGRES_PASSWORD`, set `COOKIE_SECURE=true`, and set `APP_ENV=production`. Per §5.1, `APP_ENV=production` is also what disables the six-account password-bypass list — this is a hard security requirement, not just a config nicety.
2. ~~Swagger UI / OpenAPI schema publicly reachable~~ — **resolved in code**: `/docs`, `/redoc`, `/openapi.json` are now disabled when `settings.is_production` is true (see §5) — but this is only effective once item 1 above is done and `APP_ENV` actually resolves to `"production"` at runtime.
3. **No Alembic migration framework yet** — `migrate.py` is a hand-maintained, append-only bootstrap script (§4). Acceptable for current scale; flag to engineering as Phase B work before the schema grows more complex or multi-environment promotion (dev→staging→prod) becomes a recurring need.
4. **PostgreSQL port not host-mapped by design.** If your monitoring/backup tooling needs direct DB access from outside the Docker network, you must explicitly add a `ports` mapping — don't add it reflexively, as it widens the attack surface for an internal-only datastore.
5. **`WORKERS` is consumed for logging behavior only, not for scaling; `DEBUG` is confirmed unused.** `WORKERS` (`main.py:53-55`) decides whether the app logs to a `RotatingFileHandler` file or to stdout only — it does **not** control Gunicorn worker count, which remains hardcoded to `--workers 4` in `Dockerfile:41`. `DEBUG` is declared in `docker-compose.yml` but not read anywhere in application code — don't assume it gates anything.
6. **Local dev DB file (`magti_portal.db`, ~183MB)** must never be deployed or treated as a production data source — it is dev-only seed/test data, explicitly excluded from this handover's production data plan.

---

## 8. Quick Reference — Key Files

| File / Path | Purpose |
|---|---|
| `main.py` | FastAPI app instance, lifespan, middleware, and router wiring only — **no route handlers live here** (see §1); wires in the 14 routers below via `include_router()` |
| `routers/*.py` (14 files) | All HTTP route handlers, one module per domain: `articles`, `audit_logs`, `auth`, `categories`, `compliance`, `exports`, `favorites`, `messaging`, `news`, `platform`, `search`, `stats`, `users`, `videos` |
| `state.py` | Shared app state incl. `RedisEventBroker` (SSE pub/sub for live updates, lines 82-228) |
| `db_helpers.py` | Shared DB query/helper utilities used across routers |
| `qa_accounts.py` | QA/dev seed-data account list (`TEST_ACCOUNTS`, 20 accounts, password `Test1234!`) consumed by `scripts/seed_portal.py`'s `users` mode — a seed-data table, not a login bypass (see §6) |
| `models.py` | SQLAlchemy ORM models — source of truth for schema |
| `schemas.py` | Pydantic request/response validation |
| `security.py` | Auth, JWT, RBAC, password policy, mock-AD dev bypass |
| `database.py` | DB engine/session factory |
| `config.py` | Centralized environment-driven settings, incl. the production-startup guard (§2, §7) |
| `migrate.py` | Idempotent schema bootstrap (run before app start) |
| `seed.py` | Seeding — **mode-dependent, not uniformly safe**: bare `python seed.py` is destructive, `seed.py org` is idempotent (see §6). `scripts/seed_portal.py` is the currently recommended seeder. |
| `backup.py` | Scheduled backup job (DB + uploads → ZIP), 24h-cycle `backup` service |
| `compliance_alerts.py` | Scheduled compliance-alert job, 24h-cycle `compliance-alerts` service (§1, §3) |
| `retention.py` | Data-retention logic |
| `audit_trail.py` | Audit-log/audit-hash-chain support |
| `scripts/` | Operational scripts directory — `seed_portal.py` (recommended seeder), `seed_rbac.py`, `sync_rbac.py`, `seed_test_users.py`, `seed_perfect_users.py`, `migrate_departments.py`, plus one-off cleanup/repair scripts |
| `docker-compose.yml` / `Dockerfile` | Container topology and image build (6 services incl. `compliance-alerts` — see §1, §3) |
| `.github/workflows/ci.yml`, `pyproject.toml`, `requirements-dev.txt` | CI pipeline and dev/test tooling config |
| `docs/` | Documentation directory — now 8 files: this handover, `admin-guide.md`, `SEED_GUIDE.md`, `CODE_AUDIT_2026-07-11.md`, `agent-handoff.md`, `analytics_audit.md`, `article_versioning_blueprint.md`, `csp_nonce_refactor_plan.md` (see also root `README.md`) |
| `TEST_LOGINS.md` | Repo-root reference for current test/seed account credentials — see §6 |
| `start_server.bat`, `start_8000.bat` | Local Windows dev launchers (two, not one) |

---

*Recovered from an abandoned worktree (`claude/elastic-raman-7dc200`, originally written 2026-06-23) and merged into `docs/` on 2026-07-11. §3.2, §5, and §7 updated at merge time to reflect drift since the original was written: secrets moved from hardcoded values to `${VAR}` substitution in `docker-compose.yml` (commit `b0d332f`, same day), rate limiting added (same commit), and the `/docs`/`/redoc`/`/openapi.json` production-disable gap closed (commit `8c5adac`). Treat any other claim in this document as a 2026-06-23 snapshot, not necessarily current — verify against the code before relying on it for a real deployment.*

*Refreshed 2026-07-19 following a `main.py` → 14-router split (`routers/`) and related security/ops changes: §1, §2, §3, §4.4, §5, §6, §7, and §8 updated to reflect the current architecture, seed scripts, and security controls. Treat this document as current as of 2026-07-19; verify against the code for anything not covered above.*
