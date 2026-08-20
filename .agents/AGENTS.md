# Magti Portal Project Rules

This document outlines behavioral constraints and architectural guidelines specific to the Magti Portal codebase.

## 0. Product and UX Source of Truth

- Before changing UX, search, content lifecycle, compliance/audit flows, role
  dashboards, exports or branding, read
  `docs/PRODUCT_UX_REQUIREMENTS_KA.md` and `docs/UI_UX_REDESIGN_PLAN_KA.md`.
- That document contains the product owner's confirmed requirements and an
  explicit list of unresolved decisions. Do not replace unresolved answers
  with assumptions.

## 1. Router-Based Architecture (Corrected 2026-08-05 — see below)
- **Current state**: `main.py` holds no routes. It only does app/middleware setup and wires up 14 domain routers via `include_router()`. All routes live in `routers/*.py` (articles, auth, users, news, videos, categories, favorites, compliance, messaging, exports, audit_logs, search, stats, platform) — see `CLAUDE.md` for the authoritative file map.
- **When adding or changing a route**: put it in the matching `routers/*.py` file, not in `main.py`.
- **Historical note (superseded)**: an earlier Strangler Pattern extraction attempt was rolled back due to circular imports and session-handling issues; the router split was later redone successfully and is now the live, working architecture. Do not attempt to re-consolidate routes back into `main.py`.

## 2. Health Monitoring & Redis Event Broker Fallbacks
- **Rule**: When implementing or updating backend health endpoints (e.g., `/api/health`), verify the active Redis connectivity status using `broker._use_redis` instead of checking if the broker loop has been initialized.
- **Worker Detection**: If the application is running in a multi-worker environment (detected via environment variables like `GUNICORN_CMD_ARGS`, `WEB_CONCURRENCY`, or `UVICORN_WORKERS`), and `broker._use_redis` is false, report the service as degraded and return an HTTP 503 status code to prevent silent failure.

## 3. Real-Time Broadcasts for Draft Articles
- **Rule**: When editing an article from a `draft` status to `published`, you must trigger the core `_notify("article", ...)` event. This ensures operator dashboards receive immediate live updates for newly-published articles, matching the behavior of fresh article creations.
- **Frontend Sync**: The client-side toast notifications in `static/js/app-core.js` for article revisions should display the line additions and deletions (`+added / -removed`) in the toast message, and clicking the toast should open the revision history/diff overlay directly.
