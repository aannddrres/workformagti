# Magti Portal — Internal Call Center Portal

> NOTE: Despite earlier assumptions of "frontend only", this repo is a **full
> FastAPI backend + server-rendered templates**, not a static front end. The
> notes below reflect the actual code.

## Stack
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
- Search relies on Postgres `pg_trgm` GIN indexes (spec slide 28)

## Docker (`docker-compose.yml`)
| Container | Image | Port | Role |
|-----------|-------|------|------|
| `magti-portal-app` | built (`magtibase-app`) | 8000 | FastAPI app, 4 gunicorn workers |
| `magti-portal-db` | postgres:15-alpine | (not host-mapped) | `max_connections=500` |
| `magti-portal-redis` | redis:7-alpine | (internal) | appendonly, 256mb LRU |
| `magti-portal-migrate` | built | — | one-shot schema init before app |
| `magti-portal-backup` | built | — | runs `backup.py` every 24h |

- DB URL (in-network): `postgresql://appuser:magti_secure_db@db:5432/magti_portal`
- Postgres port 5432 is **not** mapped to the host — add `ports: ["5432:5432"]` to the `db` service if you need host/MCP access.

## Key Files
- `main.py` (~165 KB) — FastAPI app, routes (`main.py.bak` is a backup)
- `models.py` — SQLAlchemy models
- `schemas.py` — Pydantic schemas
- `security.py` — auth/JWT/hashing
- `database.py` — DB session/engine
- `config.py` — settings (`.env` via python-dotenv; see `.env.example`)
- `migrate.py` — idempotent schema migration (Phase B → Alembic)
- `seed.py` / `scripts/seed_test_users.py` — data seeding (one-off/dev seeders live in `scripts/`)
- `backup.py` — DB backup job
- `start_server.bat` — local launch
- `docs/admin-guide.md`, `README.md`, `ინფო_დეველოპერებისთვის.md` — docs

### Admin CMS Enhancements (Additive)
- `static/js/admin-cms-enhancements.js` is active and loaded after `app-core.js`/`app-renderers.js`/`app-router.js` in `base-layout.html`; it dynamically hooks into `window.focusCreateForm` and `window.editArticle` (function wrapping, originals preserved) to refresh the char counter and status badge when the article drawer opens.
- `window.statusStylesMap` is exposed from `app-renderers.js` (set right after its local declaration) so other modules can reuse the same status→color mapping for dynamic color parsing instead of duplicating it.
- Global `window` `dragover`/`drop` default-navigation blocking is scoped to this module only, to stop accidental file drops outside `#article-dropzone` from navigating the tab.

## Rules for Claude Code
- Surgical edits only — never rewrite full files; cite file name + line number
- Bilingual: Georgian + English
- Dark/light theme support required
- Mobile-first
- Migrations must stay idempotent (workers race on CREATE TABLE otherwise)
- Never commit secrets — `SECRET_KEY` and DB password are currently inline in `docker-compose.yml`; move to a `.env`/secrets store before any public push
- `magti_portal.db` (~183 MB) is local dev data — do not commit or delete
- Worktree/branch hygiene: when work in a `.claude/worktrees/*` checkout is finished (merged or abandoned), remove the worktree (`git worktree remove`) and its `claude/*` branch (`git branch -D`) in that same session — don't leave it for later. Before deleting an unmerged one, check `git diff`/`git log` against `main` for anything not yet captured. (8 stale worktrees / 16 branches / 1.2GB accumulated silently over ~3 weeks before a full cleanup on 2026-07-11 — see `docs/PRODUCTION_HANDOVER.md` for the one real deliverable that was almost lost in the pile.)
