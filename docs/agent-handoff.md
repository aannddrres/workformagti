# Magti Portal — Agent Handoff

Snapshot written 2026-07-08. If it's much later than that, treat everything
here as a *starting hypothesis* — re-run `git log`, `git status`, and the
test suite before trusting it.

## What this project is

Internal call-center knowledge-base / compliance portal for Magti (Georgian
telecom), ~600 users. It is **not** a static frontend — it's a full FastAPI
backend with server-rendered HTML templates (Georgian + English UI). Full
architecture/stack/rules live in [`CLAUDE.md`](../CLAUDE.md) at repo root —
read that first if your tool supports project instructions; this doc covers
*state*, not rules.

One-line mental model: operators read/search KB articles and news, get
pushed live notifications over SSE, and get tracked for mandatory-reading
compliance; admins manage content, categories, users, and audit logs.

## Current repo state (uncommitted, working tree)

`git status` shows real work-in-progress on top of the last commit
(`ef4bd02`). Do not assume HEAD reflects what's actually running. As of this
snapshot:

- **`app/routers/*.py` and `app/__init__.py`, `app/main.py` are deleted** —
  a prior attempt to extract routes out of the monolith `main.py` into
  per-domain routers was rolled back. `main.py` (~165KB) is the single
  source of truth for all routes again. If you see any reference to
  `app.routers.*`, it's stale.
- **Secrets externalization (in progress, looks complete):**
  `docker-compose.yml` now reads `${SECRET_KEY}`, `${DATABASE_URL}`,
  `${POSTGRES_PASSWORD}` from env instead of hardcoding them (this was a
  standing CLAUDE.md action item — "move to `.env`/secrets store before any
  public push" — looks like someone actually did it). `.env.example` was
  updated with matching documented vars. **Verify a real `.env` exists
  with real values before assuming Docker Compose still boots** — this
  change wasn't tested end-to-end as far as this snapshot can tell.
- **Rate limiting added:** `slowapi` is imported and wired in `main.py`
  (`Limiter`, `@limiter.limit(...)` on 3 endpoints — login, forgot-password,
  SSO callback area, roughly `main.py:1056`, `6146`, `6655`). Added to
  `requirements.txt`. Looks fully wired, not half-done.
- **`tech_info` / `service_center` visibility toggles hidden in the UI**
  (`base-layout.html`, marked `[Fix F]`) — those roles don't exist yet in
  `security.py`'s `VALID_ROLES`, so the toggles were non-functional and got
  `hidden`-classed rather than deleted. Backend columns + JS wiring
  deliberately left in place for whenever those roles land.
- **Notification-flow bug fixes (this session, see below).**
- Misc small WIP: `.gitignore` now un-ignores `.claude/skills/` and
  `.claude/launch.json` specifically (rest of `.claude/` stays ignored);
  Tailwind config now scans `diffing.py` too (its diff markup uses Tailwind
  classes in Python string templates); cache-busting query strings bumped
  in `base-layout.html`.

Run `git status --short` and `git diff --stat` yourself before touching
any of the above — this is a description, not a substitute for checking.

## What was just fixed (this session)

Investigated "how does new-article notification work" and "what does the
red button on categories do." The categories button was fine (it's just
soft-delete on a category, works as designed). Found and fixed 3 real gaps
in the notification path:

1. **`main.py` `update_article` (~L2443, ~L2490-2506):** editing a draft to
   `status="published"` now fires the same `_notify("article", ...)`
   broadcast that `create_article` already fires on initial publish —
   previously this was silent unless the editor manually ticked "notify
   operators" (which only ever sent the *revision*-diff event, not the
   *new content* one).
2. **`static/js/app-core.js` (~L1464), `handleLiveEvent`'s
   `article_revision` branch:** the revision toast now shows the real
   `+added/-removed` diff counts (already present in the SSE payload, just
   previously unused) and clicking it opens the article + its existing
   version-history/diff overlay (`openArticleModalById` +
   `toggleModalHistoryOverlay`), instead of a bare page nav.
3. **`main.py` `/api/health` (~L5888):** was checking
   `broker._main_loop` (always truthy once the app is up) instead of
   `broker._use_redis` (the actual Redis-PING-verified flag), so a real
   production Redis outage under the 4-gunicorn-worker deployment — where
   SSE silently degrades to a broken per-process queue — was invisible to
   monitoring. Now reports `"degraded_fallback"` and escalates to 503 when
   multi-worker env vars (`GUNICORN_CMD_ARGS`/`WEB_CONCURRENCY`/
   `UVICORN_WORKERS`) are present.

Two tests added to `tests/test_resilience.py` (`test_draft_publish_via_edit_notifies`,
`test_health_check_redis_fallback_status`). All 9 tests in `tests/` pass.
All three fixes were also verified live via a browser preview (toast fires,
diff counts render, diff overlay opens with correct fresh data, `/api/health`
flips modes correctly).

**Known, accepted limitation — not fixed, intentionally out of scope:**
`openArticleModalById` (app-core.js) resolves an article from the
`Store.articles` cache without refetching if content is already cached. If
an operator already has an article open when a revision notification
arrives, my fix guarantees the version-*history list* is fresh, but the
*article body* shown could still be stale until they refetch/reopen. Would
need a broader cache-invalidation pass to close; wasn't worth it for this
narrow bug-fix scope.

## Architecture cheat sheet

- **Backend:** FastAPI + Uvicorn (dev) / Gunicorn 4 workers (Docker prod).
  Everything routes through `main.py` (monolith, ~165KB).
- **Data:** SQLAlchemy (`models.py`), Pydantic v2 (`schemas.py`). SQLite
  locally (`magti_portal.db`, ~183MB, **never commit/delete** — it's real
  local dev data), Postgres 15 in Docker.
- **Auth:** JWT (python-jose) + passlib + `bcrypt==4.0.1` (pinned —
  passlib 1.7.4 breaks on bcrypt ≥4.1). `security.py`.
- **Real-time:** `RedisEventBroker` class in `main.py` (~L752) powers
  `/api/stream` (SSE). Redis pub/sub when reachable (multi-worker safe);
  falls back to a process-local `asyncio.Queue` otherwise (dev-only safe,
  **broken across gunicorn workers in prod** — see health-check fix above).
  No dedicated `Notification` DB model — a generic `Message` table
  (`models.py:309`) plus SSE events (`type: "article"`, `"article_revision"`,
  `"news"`, `"video"`, `"broadcast"`, `"nudge"`) cover it.
- **Diffing:** `diffing.py` — structure-aware, XSS-safe HTML diff (BeautifulSoup
  + difflib) for article version comparisons. Feeds both the SSE revision
  summary and the version-history overlay in the UI.
- **Frontend:** server-rendered `base-layout.html` + `static/js/app-core.js`
  (huge single file, most UI logic), `app-renderers.js`, `app-router.js`,
  `admin-cms-enhancements.js` (additive, wraps `window.focusCreateForm`/
  `window.editArticle`), `frontend_api.js`. Tailwind (v3 CLI build, not v4)
  → `static/css/app.min.css`; `custom-styles.css` is hand-written on top.
- **Docker Compose services:** `magti-portal-app` (FastAPI, 4 workers),
  `magti-portal-db` (Postgres, port not host-mapped by default),
  `magti-portal-redis`, `magti-portal-migrate` (one-shot idempotent schema
  init — **migrations must stay idempotent**, workers race on `CREATE TABLE`
  otherwise), `magti-portal-backup` (24h `backup.py` cron-in-a-loop).

## Rules that actually matter here (see CLAUDE.md for the full list)

- Surgical edits only — this is a mature, live-feeling codebase; don't
  rewrite files wholesale, cite file:line, reuse existing helpers/patterns
  before adding new ones.
- Bilingual (Georgian + English) UI, dark/light theme, mobile-first.
- Never commit secrets. `magti_portal.db` is local dev data, not a fixture
  — don't touch it.

## How to run / verify

- Python venv already set up at `venv/` (`./venv/Scripts/python`).
  `./venv/Scripts/python -m pytest tests/ -q` runs both test files
  (`test_compliance.py`, `test_resilience.py`).
- `.claude/launch.json` has preview server configs: `magti-verify`
  (uvicorn, port 8001, no reload), `magti-verify-reload` (port 8002, with
  `--reload`), `magti-verify-alt` (port 8003 — added this session because
  8001/8002 were both occupied by other concurrent sessions; use whichever
  port is actually free). `magti-base` (port 5500) is a dumb static server
  with no API — don't use it to test backend behavior.
- Local admin login for manual testing: `admin@magti.ge` / `password`
  (dev/local only, not a real secret).
