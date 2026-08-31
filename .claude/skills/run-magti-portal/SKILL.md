---
name: run-magti-portal
description: Build, run, and drive the Magti Portal (FastAPI internal call-center KB/compliance app). Use when asked to start Magti Portal, run its tests, smoke-test it, take a screenshot of its UI, or interact with the running app.
---

Magti Portal is a FastAPI backend (`main.py`) serving a server-rendered
HTML/vanilla-JS frontend (`base-layout.html` + `static/js/*.js`) against a
local SQLite DB by default. Drive it two ways: `smoke.sh` in this directory
for a headless, no-browser API check (the committed harness — start here),
or a real browser via this environment's `mcp__Claude_Preview__*` tools for
visual/UI checks (see "Run (agent path) — browser" below).

All paths below are relative to the repo root (`C:\Projects\Magti base`).

## Prerequisites

Windows dev box, not a container — no `apt-get` needed. Requires:
- Python 3.11 (confirmed: `venv/Scripts/python.exe` → `Python 3.11.9`)
- A `venv/` at the repo root with `requirements.txt` installed. One already
  exists in this checkout; for a fresh clone:

```bash
python -m venv venv
venv/Scripts/python.exe -m pip install -r requirements.txt
```

No `.env` is required for local dev — `config.py` defaults `DATABASE_URL`
to `sqlite:///./magti_portal.db` and `APP_ENV` to `development`.

## Build

None. Interpreted Python + a single committed, pre-built
`static/css/app.min.css` — no bundler, no build step to run the app.

Only rebuild CSS if you edited `static/css/input.css` or
`tailwind.config.js` (uses the **v3** standalone CLI binary, not v4 — v4
ignores this project's `tailwind.config.js`):

```bash
./tailwindcss-v3.exe -i static/css/input.css -o static/css/app.min.css --minify
```

## Run (agent path) — headless smoke test

The committed driver, `smoke.sh`, is the primary check — no browser
needed. It reuses an already-running server on port 8002 if one exists,
otherwise launches `uvicorn` itself, waits for readiness, logs in, hits
three representative authenticated endpoints, and cleans up after itself:

```bash
bash .claude/skills/run-magti-portal/smoke.sh
```

Verified output (this session, reusing an already-running server):
```
Server already responding on http://127.0.0.1:8002 — reusing it.
Login OK — got a token.
GET /api/users/me -> 200 OK
GET /api/categories -> 200 OK
GET /api/articles?limit=5 -> 200 OK

All smoke checks passed.
```

Also verified with nothing running (`PORT=8003 bash .claude/skills/run-magti-portal/smoke.sh`):
it launches `venv/Scripts/python.exe -m uvicorn main:app --host 127.0.0.1
--port 8003`, polls `GET /` until 200, runs the same checks, then kills
the process it started (confirmed the port was free again afterward).
Override the port with `PORT=<n>`; server log goes to
`${TMPDIR:-/tmp}/magti-smoke-uvicorn.log`.

## Run (agent path) — browser / visual checks

For anything the smoke script can't see (rendered HTML, dark mode, modal
layout, console errors), use this environment's built-in
`mcp__Claude_Preview__*` tools — no custom driver needed, they're already
the right tool for a Claude Code agent. `.claude/launch.json` defines the
named configs:

| config | port | notes |
|---|---|---|
| `magti-verify` | 8001 | plain `uvicorn`, no reload |
| `magti-verify-reload` | 8002 | `uvicorn --reload` — prefer this for iterating |
| `magti-base` | 5500 | **avoid** — plain `python -m http.server`, no FastAPI app behind it, POSTs 501 |

Verified login → dashboard flow (this session, exact sequence that worked):

```
preview_start({ name: "magti-verify-reload" })
preview_eval: localStorage.clear(); window.location.href = '/login.html'
preview_fill("#email", "admin@magti.ge")
preview_fill("#password", "password")
preview_click('button[type="submit"]')
preview_eval: window.location.href
  → "http://localhost:8002/base-layout.html#page-dashboard"
preview_screenshot()   # renders the KB category dashboard
preview_console_logs({ level: "error" })   # → "No console logs." (clean)
```

`admin@magti.ge` works with **any password** in local dev — see Gotchas.
`preview_screenshot`/`preview_snapshot` return image/DOM data directly
into the calling agent's context rather than a fixed file path on disk;
there is no separate screenshot-file location to document for this tool.

## Run (human path)

```bash
uvicorn main:app --reload --port 8000
```
Open `http://127.0.0.1:8000/`. Ctrl-C to stop. (`start_server.bat` wraps
this plus first-run venv setup and seeding — Windows-only, from the
existing README, not re-verified here since it duplicates the manual
steps above.)

## Test

```bash
venv/Scripts/python.exe -m pytest tests/ -v
```

Verified this session: **7 passed in 2.98s**, one deprecation warning
(`StarletteDeprecationWarning: Using httpx with starlette.testclient is
deprecated; install httpx2 instead` — harmless today, worth revisiting if
`starlette`/`fastapi` get upgraded).

## Gotchas

- **`admin@magti.ge` (and 5 other seeded emails) accept ANY password
  locally.** `security.py`'s JIT/test-account bypass skips password
  verification entirely whenever `APP_ENV` names a development environment
  (`development`, `dev`, `local`, `test` — see `config.py`'s
  `_DEVELOPMENT_ENVIRONMENTS`), which is the default. It used to be anything
  that was not exactly `production`, so `prod`, a typo, or a trailing space
  left the bypass live. This is intentional for local dev (see README §5), but don't
  mistake it for a bug, and don't rely on it as a real auth check when
  testing anything security-related — use `docker-compose.yml`
  (`APP_ENV=production` via `.env`) for that.
- **Port 8001 can get stuck with an orphaned, unkillable process** on this
  machine (Windows tools can't see/kill it despite `netstat` showing it
  listening). Use `magti-verify-reload` (8002) instead of fighting it.
- **`magti-base` (port 5500) is a dumb static file server** — it can serve
  HTML/JS/CSS but returns `501` on any API call. Confirmed via this
  session's own history. Only use it if you need zero API calls.
- **JS/CSS cache-busting:** static assets are loaded with `?v=` query
  params in `base-layout.html`. Edit `static/js/app-core.js` or
  `static/css/app.min.css` without bumping the matching `?v=` and the
  browser will keep serving the old cached copy — a change can look like
  it "didn't apply" when it actually did.
- **`uvicorn --reload` occasionally hangs** after a file edit in this
  environment (requests time out with no response). Don't trust it blindly
  — if a change doesn't seem to take effect, stop and restart the server
  rather than waiting on reload.
- **`magti_portal.db`** (local dev SQLite, ~183MB) — never delete or
  overwrite it; it's real seeded/working data, not a fixture.
- **`smoke.sh`'s login step assumes JIT dev-bypass is active** (i.e.
  `APP_ENV != production`). Against a real `APP_ENV=production` deployment
  it will correctly fail at the login step with a non-2xx — that's
  expected, not a script bug.

## Troubleshooting

- **`git status` doesn't show a newly-created file under `.claude/`**:
  `.claude/` is gitignored wholesale except for `.claude/skills/` and
  `.claude/launch.json` (explicit `.gitignore` negations). Anything else
  new under `.claude/` (worktrees, plans, other local state) stays
  ignored by design.
- **`ModuleNotFoundError` for a package that "should" be installed**:
  double-check you're using `venv/Scripts/python.exe`, not a bare `python`
  on PATH — this repo has both, and the system Python does **not** have
  this project's dependencies (confirmed while building this skill: a
  bare `python -c "import httpx"` failed, while
  `venv/Scripts/python.exe -c "import httpx"` succeeded — same package,
  different interpreter).
