---
name: magti-portal-helper
description: Guides development, asset compilation, database seeding, and testing for the Magti Call Center Portal FastAPI application.
---

# Magti Portal Helper Skill

Use this skill when developing, testing, or building assets for the Magti Call Center Portal.

## 1. Asset Compilation (Tailwind CSS)
The project uses Tailwind CSS v3 CLI to generate `static/css/app.min.css` from `static/css/input.css`.
- **Command**:
  ```powershell
  .\tailwindcss-v3.exe -i static/css/input.css -o static/css/app.min.css --minify
  ```
- **Rule**: Always run this compilation command after making any modifications to HTML files (`base-layout.html`, `login.html`, `article.html`) or files with Tailwind classes.

## 2. Test Execution
The project has a custom test suite checking compliance, security filters, and notifications.
- **Run pytest**:
  ```powershell
  .\venv\Scripts\pytest.exe
  ```
- **Note**: Ensure the SQLite database (`magti_portal.db`) is present and initialized before running tests.

## 3. Database Operations
- **Dev Seeding**:
  To reset and seed test data, run:
  ```powershell
  .\venv\Scripts\python.exe seed.py
  ```
- **Warning**: Do not commit or delete the local SQLite database file `magti_portal.db` unless explicitly instructed.

## 4. Verification checklist
- When adding or changing routes, place them in the matching `routers/*.py` file — `main.py` holds no routes (see `.agents/AGENTS.md` §1 and `CLAUDE.md`).
- Ensure all new/updated routes have correct authorization guards.
- Verify health status under multi-worker and fallback modes using `/api/health`.
