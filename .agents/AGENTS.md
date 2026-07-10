# Magti Portal Project Rules

This document outlines behavioral constraints and architectural guidelines specific to the Magti Portal codebase.

## 1. Monolith Maintenance (No Modularization)
- **Constraint**: DO NOT attempt to split the `main.py` codebase into separate subfolders or domain router files (e.g., `app/routers/*.py`).
- **Reasoning**: A prior attempt to extract routes using the Strangler Pattern was rolled back due to complex circular imports and database session handling issues. `main.py` is the single source of truth for all backend routes and logic. Keep it as a unified monolith.

## 2. Health Monitoring & Redis Event Broker Fallbacks
- **Rule**: When implementing or updating backend health endpoints (e.g., `/api/health`), verify the active Redis connectivity status using `broker._use_redis` instead of checking if the broker loop has been initialized.
- **Worker Detection**: If the application is running in a multi-worker environment (detected via environment variables like `GUNICORN_CMD_ARGS`, `WEB_CONCURRENCY`, or `UVICORN_WORKERS`), and `broker._use_redis` is false, report the service as degraded and return an HTTP 503 status code to prevent silent failure.

## 3. Real-Time Broadcasts for Draft Articles
- **Rule**: When editing an article from a `draft` status to `published`, you must trigger the core `_notify("article", ...)` event. This ensures operator dashboards receive immediate live updates for newly-published articles, matching the behavior of fresh article creations.
- **Frontend Sync**: The client-side toast notifications in `static/js/app-core.js` for article revisions should display the line additions and deletions (`+added / -removed`) in the toast message, and clicking the toast should open the revision history/diff overlay directly.
