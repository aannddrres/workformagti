# AI Context: Magti Internal Portal Refactoring

This document provides a comprehensive overview of the project's identity, current architectural state, strict engineering constraints, and next steps. Future AI agents must read this document before making any changes.

---

## 1. Project Identity

- **Backend Framework:** FastAPI (Python)
- **Database:** SQLite (local development) / PostgreSQL (production) with SQLAlchemy ORM
- **Frontend Architecture:** Server-rendered HTML pages utilizing Jinja2 templates
- **Styling & Scripting:** Vanilla JavaScript + Tailwind CSS (No Single Page Application framework)
- **Role/Permissions System:** Custom role-based security system mapping user groups to granular permissions

---

## 2. Current Architectural State (The Strangler Pattern)

We are actively de-monolithing the application by transitioning from a single, massive 4,600+ line `main.py` file to a modular design.

### Core Factory Pattern (Phase 2.2)
- The core FastAPI instantiation, lifecycle handlers (`lifespan`), static folder mounts, and global middlewares (CORS, custom security hardening headers) have been successfully extracted into [app/main.py](file:///c:/Projects/Magti%20base/app/main.py).
- The root [main.py](file:///c:/Projects/Magti%20base/main.py) now acts solely as the entry point and legacy route container, instantiating the app via `app = create_app()`.

### Extracted Domains (Phases 2.3 - 2.6)
We have successfully modularized the following domains into dedicated APIRouter instances under `app/routers/` and included them in the main application factory:
1. **Auth:** [auth.py](file:///c:/Projects/Magti%20base/app/routers/auth.py)
2. **Users:** [users.py](file:///c:/Projects/Magti%20base/app/routers/users.py)
3. **News:** [news.py](file:///c:/Projects/Magti%20base/app/routers/news.py)
4. **Categories:** [categories.py](file:///c:/Projects/Magti%20base/app/routers/categories.py)
5. **Articles:** [articles.py](file:///c:/Projects/Magti%20base/app/routers/articles.py)

### Remaining Legacy
- Several administrative endpoints, statistics/KPI functions, favorites, messages, compliance reading trackers, and media upload features still reside in the root [main.py](file:///c:/Projects/Magti%20base/main.py) and will be strangled in subsequent phases.

---

## 3. Strict Engineering Rules

> [!IMPORTANT]
> **Rule 1: Circular Import Prevention**
> Extracted routers often need to access cross-cutting singletons defined in the root module (such as `search_cache`, `category_cache`, `broker`, or `_notify`).
> To avoid circular dependency compilation crashes at module-load time, **you MUST use the lazy import pattern inside function bodies** that require them:
> ```python
> @router.post("")
> def my_route():
>     import main as _main
>     _main.search_cache.clear()
> ```
> NEVER import `main` at the top level of any file under the `app/` directory.

> [!WARNING]
> **Rule 2: No Base64 Images**
> Quill.js rich text editor uploads must NOT be saved inline as Base64 strings. Doing so bloats the database and causes sqlite lockouts and application crashes. Images must be sent to the backend `/api/upload` endpoint, saved as binary files in the storage system, and references returned as public URLs.

> [!NOTE]
> **Rule 3: Secrets (Phase 0)**
> Hardcoded secrets present in configuration files (e.g., `docker-compose.yml`) are intentionally kept for local testing of roles and permissions. Do not attempt to fix or flag them as high-priority security concerns at this time.

---

## 4. Immediate Next Steps (Tomorrow's Goals)

### Priority 1 (Block 7) — Safe Image Upload Handling
- Implement the `POST /api/upload` endpoint in the backend.
- It must handle file uploads from the Quill.js editor, validate their MIME types, save the binaries securely to the configured uploads directory (`static/uploads/`), and return the access URL.

### Priority 2 — Extinguishing Remaining Legacy Routes
- Incrementally extract the remaining route categories from the root `main.py` into new routers (e.g., statistics, search, videos, messages, compliance) until the legacy monolith is completely clean.
