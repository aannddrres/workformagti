# AI Context: Magti Internal Portal

> **This file was previously stale and self-contradictory** — it described a
> `main.py` → `app/routers/*.py` extraction as successfully completed. That
> extraction was rolled back (see git history); `main.py` is the single
> source of truth for all routes again. It also told AI agents not to flag
> hardcoded secrets in `docker-compose.yml`, which contradicted `CLAUDE.md`
> (the authoritative project instructions) and was fixed anyway. Corrected
> 2026-07-11 after a full code audit (`docs/CODE_AUDIT_2026-07-11.md`) — see
> that document for the current, detailed state of the codebase.

**Read `CLAUDE.md` first** — it's the authoritative, checked-in source for
stack, architecture, and rules. `.agents/AGENTS.md` documents a few specific
behavioral constraints (monolith-only, health-check semantics, real-time
broadcast rules) that remain accurate. This file only adds a couple of
still-useful engineering notes that don't live elsewhere.

---

## Project identity

- Backend: FastAPI (Python), single `main.py` monolith — do not re-attempt
  splitting it into `app/routers/*.py`; a prior attempt was rolled back due
  to circular-import and DB-session complications.
- Frontend: server-rendered HTML (no SPA framework) + vanilla JS.
- DB: SQLite (local dev) / PostgreSQL (production) via SQLAlchemy.

## Still-accurate engineering notes

**Lazy imports for cross-module singletons.** Modules outside `main.py`
that need something defined in `main.py` (or in each other) should import it
inside the function body that uses it, not at module top level, to avoid
circular-import failures at load time. This pattern is already in active use
— see `scripts/seed_portal.py`'s import of `main.build_department_stats`
inside `cmd_org()`.

**No inline Base64 images.** Quill.js editor uploads must go through
`POST /api/upload` (validates MIME type, saves as a binary file, returns a
URL) — never saved inline as Base64 in article content. Inline Base64 bloats
the database and has previously caused SQLite lockouts.

**Validate before claiming a backend change is done.** Run
`./venv/Scripts/python -c "import main"` after any backend edit to catch
syntax/import errors immediately, before running the full test suite.
