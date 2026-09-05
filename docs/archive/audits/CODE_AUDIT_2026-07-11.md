> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# Magti Portal — Code Audit

**Date:** 2026-07-11
**Scope:** Full backend (`main.py` + supporting modules), frontend (`static/js/*`, templates), tests, deployment config, and documentation, as they stand in the current working tree (detached HEAD at `ef4bd02` + uncommitted WIP).
**Requested by:** Project owner, ahead of handoff to a team of experienced developers. Four questions drove this audit: does everything work correctly, can the approach be improved, is there dead code, and does this meet the bar expected of a comparable production system.
**Nature of this document:** Findings only. Nothing was changed in the codebase during this audit — every item below is diagnostic, not yet remediated.

---

## 1. Executive Summary

- **The mock-SSO/login dev-bypass is a full unauthenticated admin-takeover vector, gated by a single environment variable with no second line of defense.** If `APP_ENV` is ever unset or misconfigured in a real deployment, anyone can `POST /api/auth/sso/callback?email=admin@magti.ge` (an email already published in this repo's own docs) and receive a valid admin JWT with zero credentials. See §3.1.
- **Editing any Knowledge Base article silently deletes its authorship record and resets its "last verified" timestamp.** Reproduced on the first attempt, 100% of the time, for every article. See §3.2.
- **Rate limiting is not implemented anywhere**, despite a dependency (`slowapi`) being installed for exactly this purpose and a project handoff doc claiming it was already wired up. Confirmed by static grep and by live-firing 8 rapid failed logins with zero throttling. See §3.3.
- **The export-job registry is a plain in-process dictionary under a 4-worker production deployment** — a status/download request can spuriously 404 if it lands on a different worker than the one that generated the file, with no warning to the user. See §3.4.
- **A recurring pattern across the codebase: cleaner rewritten modules get written, then never actually wired in**, while the original inline logic keeps running unchanged. Confirmed in four separate places, including one case where the "clean" version and the "live" version already compute different answers for the same input. See §5.
- **Real-time broadcast failures (SSE) are handled inconsistently and mostly invisibly** — some fail silently, some fail loudly on an already-successful write, and Redis degradation is never surfaced to an admin anywhere in the UI. See §3.5.
- On the positive side: RBAC tiering, upload validation, XSS defenses, SSE department/role gating, migration idempotency, and accessibility fundamentals are all solid. This is not a codebase in crisis — it's a fast-moving one with a handful of sharp edges. See §7.

---

## 2. Scope & Methodology

**Static analysis:** Three parallel research passes mapped the backend (`main.py`, 6606 lines; `models.py`, `schemas.py`, `security.py`), the frontend (`static/js/*`, ~12,000 lines; templates; CSS), and tests/deployment/docs. A further three passes swept the remaining ~40 previously-unread `main.py` endpoints, split by domain (admin/stats, media/search/export, messages/SSE/misc), each armed with a checklist of anti-patterns already confirmed elsewhere in the codebase. Every finding reported below was independently confirmed by direct `grep`/`Read` against the actual working tree — not taken on an agent's word. Two internal contradictions surfaced and were resolved this way during scoping (whether rate-limiting was really wired, whether the automatic audit-log listener was really registered); the same bar was held for every subsequent finding.

**Runtime verification:** The app was booted locally (`magti-verify-reload`, port 8002, SQLite dev DB) and driven through a browser automation layer as an admin user (`admin@magti.ge`). Exercised live: article creation with a quiz end-to-end, article editing, version history, audit logging, categories/news/search listing, XLSX/PDF export (full async job → poll → download cycle), dark/light theme, mobile viewport, and the auth dev-bypass — each checked against actual network responses and console output, not assumed from reading the code. The existing test suite was run once as a baseline (13/13 passed, no regressions from this audit's own activity). The dev environment turned out to already be running with Redis unreachable, which served as a natural (not manufactured) opportunity to observe the documented Redis-down fallback path in real conditions.

**What was not covered** (deliberate, see §9 for why): a full line-by-line read of all 6606 lines of `main.py`; exhaustive CRUD field/validation edge-case fuzzing; a formal penetration test (fuzzing, dependency CVE scanning); reproducing the multi-worker gunicorn export-job race under actual Docker/4-worker conditions (characterized precisely via code reading instead — see §3.4); adjudicating the Georgian developer-spec content question (a product decision, not a code question — see §8).

---

## 3. Findings by Severity

### Critical

#### 3.1 — Mock-SSO / dev login bypass is a single-env-var admin takeover, with no defense in depth
**Where:** `main.py:6499-6607` (`sso_init`, `sso_mock_login`, `sso_callback`); `security.py:42-49` (`_DEV_TEST_EMAILS`), `security.py:148-208` (`authenticate_user`).

**What's there:** `_DEV_TEST_EMAILS` (`security.py:42-49`) hardcodes six known emails, including `admin@magti.ge` — the same address documented as the demo admin login in this project's own `README.md`. `authenticate_user()` (`security.py:148-208`) contains a JIT test-account path: whenever `not settings.is_production` and the requested email is in that set, the function **returns the user with zero password verification** (`security.py:201-202`, short-circuiting before the real `verify_password()` check ever runs). This same function backs both the normal `/api/auth/login` endpoint and the "mock SSO" callback (`main.py:6576-6607`), which itself takes only an `email` query param and a hardcoded dummy password.

**Confirmed live:** `curl -X POST /api/auth/login -d '{"email":"admin@magti.ge","password":"totally-wrong"}'` returned `200 OK` with a valid admin JWT in this session.

**Why it's Critical, not just a dev convenience:** `settings.is_production` is the *only* thing standing between this and a total, unauthenticated admin compromise — and it's driven entirely by whether `APP_ENV=production` is correctly present in the deployment's `.env` (`.env.example:15-19`; `docker-compose.yml`'s `app` service reads `APP_ENV: ${APP_ENV}`, with no fallback if `.env` is incomplete). There is **no second control**: the mock-SSO routes are unconditionally registered regardless of environment — nothing 404s them, disables them, or even logs a warning in production mode. A single missing or blank `APP_ENV` line in a real `.env` file silently reopens password-less login as `admin@magti.ge` for anyone who can reach the server. This is exactly the kind of human deployment error (forgetting one line in a secrets file) that's more likely to happen by accident than a targeted brute-force attack.

**Verified NOT currently exploitable in a correctly-configured production deployment**, by tracing the code path: when `is_production` is `True`, `TEST_EMAILS` becomes an empty set (`security.py:51-52`), the JIT-provisioning and password-less-return branches are both skipped, and the function falls through to a real `verify_password()` check against the literal dummy password string — which will fail for every real user. So this is a **conditional** Critical: severe blast radius, trivial trigger, single point of failure, contingent on one config mistake that this codebase gives you no help avoiding.

**Recommended fix (S):** Don't rely on `is_production` alone. Either gate route *registration* itself (`if not settings.is_production: app.include_router(mock_sso_router)`, so the endpoints don't exist at all in prod) or add a second explicit flag (e.g. `ENABLE_MOCK_AUTH`) that must be *independently* true, so a single blank env var can't silently reopen this.

---

#### 3.2 — Every article edit silently deletes authorship and staleness-tracking data
**Where:** `schemas.py:182-206` (`ArticleBase`, specifically lines 198-199); `main.py:2330-2416` (`update_article`, specifically lines 2374-2379).

**What's there:** `ArticleBase` — the parent schema for both create and update payloads — declares `author_id: Optional[int] = None` and `last_verified_at: Optional[datetime] = None` (`schemas.py:198-199`). The frontend's edit payload (`app-core.js:2434`) never includes these two fields (by design — they're not user-editable in the edit form). `update_article` does:
```python
update_data = article.model_dump()      # main.py:2374 — fills EVERY schema field,
                                         # including author_id/last_verified_at as None
for key, value in update_data.items():
    setattr(db_article, key, value)     # main.py:2378-2379 — blindly overwrites
```
Because Pydantic's `model_dump()` includes unset fields at their schema default, and the handler applies every key without exclusion, the two columns get overwritten to `NULL` on every single edit — regardless of what value they held before.

**Confirmed live, reproduced on the first attempt:** created a test article via the real admin UI (`author_id: 2712` immediately after creation, matching the creating admin) → edited it once through the same UI → `GET /api/articles/513` afterward showed `"author_id": null, "last_verified_at": null`.

**Impact:**
- `main.py:3032-3033` reads `article.author_id` to resolve and display the article's author. After one edit, that attribution silently disappears from the UI for that article — with no error anywhere.
- `main.py:5645-5647` (`GET /api/admin/articles/stale`, the "content needs review" admin report) filters `Article.last_verified_at < cutoff`. A `NULL` value in that comparison does not satisfy `<` in SQL — so an edited article likely **drops out of the stale-content report entirely**, the opposite of what "needs re-verification" should mean, and worse than never having verified it at all.
- Note this is scoped precisely: the separate `ArticleHistory.updated_by` field (written correctly on every version, confirmed live) is unaffected — the *version history* still knows who edited what. It's specifically the live `Article` row's original-authorship and verification-tracking fields that get wiped.

**Recommended fix (S):** In `update_article`, pop `author_id` and `last_verified_at` out of `update_data` before the `setattr` loop (the same pattern already used for `notify_operators`/`target_departments` two lines above), so editing an article never touches either field. If there's a legitimate reason edits should reset `last_verified_at`, do that explicitly and intentionally rather than as a side effect of an unrelated field being absent from a payload.

---

### High

#### 3.3 — No rate limiting anywhere, despite the dependency already being installed
**Where:** `requirements.txt:14` (`slowapi`); `main.py` (zero matches for `slowapi`, `Limiter`, or `SlowAPI`, case-insensitive, across the entire file).

`docs/agent-handoff.md` (this project's own prior session notes) states rate limiting is "fully wired" on login, forgot-password, and SSO callback, with specific line numbers. That claim does not match the code — the dependency is installed and unused. **Confirmed live:** 8 consecutive failed login attempts against a nonexistent account all returned `401` with no `429`, no delay, no lockout.

Combined with §3.1, this means the password-less admin bypass (in a misconfigured deployment) also has no throttle slowing down anyone probing for it, and `POST /api/auth/forgot-password` (`main.py:6069`, confirmed enumeration-safe in response wording but otherwise unthrottled) can be hit at unlimited volume.

**Recommended fix (M):** Wire up the already-installed `slowapi` on `/api/auth/login`, `/api/auth/forgot-password`, and `/api/auth/sso/callback`, matching what the handoff doc already believed was done.

---

#### 3.4 — Export job registry is a single-process dict; breaks under the documented 4-worker production deployment
**Where:** `main.py:86-89` (`_export_jobs` dict + lock), `main.py:6439` (`_enqueue_export`), `main.py:6467` (`/api/export/status/{job_id}`), `main.py:6480-6495` (`/api/export/download/{job_id}`); contrast with `main.py:678-824` (`RedisEventBroker`, which solved the identical class of problem for SSE).

`docker-compose.yml` runs the app under 4 gunicorn workers. `_export_jobs` is an in-process Python dict — a job created by worker A is invisible to a status/download request that lands on worker B, which returns a spurious `404 "export job not found"` / `"export not ready"`. The codebase already has the right pattern for this (Redis-backed, with an explicit startup warning when Redis isn't available — `main.py:743`) but never applied it here.

**Confirmed live (partially):** the async job → poll → download flow works correctly end-to-end in this single-process dev server (job created, polled to `"completed"`, downloaded as a valid 36KB XLSX with the correct content-type). The specific 404 error shape this bug would produce under a real cross-worker miss was also directly observed (calling download twice for the same already-consumed job correctly 404s) — confirming what a worker-miss looks like to the client, even though the actual multi-worker race itself needs Docker to reproduce and wasn't independently reproduced here.

**Recommended fix (M):** Back `_export_jobs` with Redis (or the database) instead of an in-process dict, or add the same "falling back to unreliable local state" warning the SSE broker already logs.

---

### Medium

#### 3.5 — Real-time broadcast (`broker.publish`) error handling is inconsistent across all ~10 call sites, and Redis degradation is invisible to admins
**Where:** full inventory below, all in `main.py`.

| Call site | Guard | Behavior when `publish()` throws |
|---|---|---|
| `_notify()` helper (876-883), called from news:1187, article:2325/2403, video:4669 | none | Unhandled — 500s the client *after* `db.commit()` already succeeded |
| `_notify_revision()` helper (886-902), called from article update:2407 | none | Same — loud failure on an already-durable write |
| Required-reading broadcast (2040-2051) | `except Exception: pass` | Silent — DB rows exist, live push vanishes, nothing logged |
| Per-user message event, in loop (2057-2067) | `except Exception: pass` | Same, silent |
| `/api/broadcast` (5466-5471) | none | Unhandled — also skips writing the action's audit log if it throws |
| `/api/users/{id}/nudge` (5765-5769) | none | Unhandled |

Neither pattern is clearly better: the silent ones hide a real delivery failure from the admin who just took an action; the unhandled ones turn an already-successful database write into a client-visible error. Separately, `RedisEventBroker.publish()` (`main.py:796-812`) itself never raises when Redis is down — it falls back to a per-process `asyncio.Queue` (the documented multi-worker-unsafe fallback) and swallows queue-full drops silently too. **No frontend code polls or surfaces `/api/health`'s `redis` field** (confirmed by grep across `static/js/*` — no banner, no polling), so an admin's only way to learn the system is running degraded is to read server logs. Confirmed live: this dev instance has been running with Redis unreachable (`/api/health` → `"redis": "degraded_fallback"`) for this entire audit session, and a real write (test article creation, which fires `_notify()`) succeeded cleanly with no visible symptom.

**Recommended fix (M):** Pick one policy — log-and-continue is the right default for a live-notification-is-best-effort feature — and apply it uniformly to all ~10 call sites via a shared helper instead of ad hoc try/except at each one. Surface degraded-Redis state somewhere in the admin UI (even a small header badge) rather than only in logs.

---

#### 3.6 — Recurring pattern: rewritten/extracted modules never actually wired in, with one confirmed logic divergence
**Where:** `department_stats.py` (whole file, esp. line 96) vs. `main.py:4244`/`4346`; `audit_listeners.py:80-93` (`register_listeners`) vs. `main.py`'s 40+ inline `models.AuditLog(...)` sites; `compliance_utils.py` vs. `main.py:3893-4000` (`_reading_progress`/`compute_compliance`); the original `app/routers/*.py` Strangler-pattern extraction (fully rolled back per git status).

Four instances of the same shape: someone writes a cleaner, extracted version of existing logic, and the switchover never happens — `main.py` keeps running its own inline copy, and the new module sits in the tree, imported by nothing (or only by other equally-unwired modules).

**The one place this has already caused a real, confirmed bug, not just a hypothetical one:** `compliance_utils.get_compliance_percentage()` returns `100` when `required == 0` ("nothing required = compliant" — `compliance_utils.py:62`), while `main.py`'s own `_reading_progress()` returns `0` for the identical input (`main.py:3910-3911`). Dormant today only because `compliance_utils.py` is unreachable from `main.py` — but `department_stats.py` already imports it, and if that module is ever wired in as-is, it will report *different* compliance numbers than the live dashboard for anyone with zero required readings, with no error to indicate the disagreement.

Also newly confirmed: `compliance_alerts.py` (tracked, modified, runs as its own cron service in `docker-compose.yml`) is likewise never imported by `main.py` — it's a fully separate process, not dead, just architecturally disconnected from the main app.

**Recommended fix (L for cleanup, M for a decision):** This needs a team decision, not just a fix — see §9. Whichever implementation is kept, delete the other; don't let two versions of "what does compliant mean" coexist.

---

#### 3.7 — `submitUserEditForm` never releases the double-submit guard on failure
**Where:** `static/js/app-core.js:5113-5154` (`submitUserEditForm`); contrast with `app-core.js:2374-2380`, `2833-2838`, `3209-3214` (`submitArticleForm`/`submitNewsForm`/`submitVideoForm`, which all correctly call `releaseSubmitGuard()`); the global guard itself at `app-core.js:6471-6494`.

The global double-submit guard (a `document`-level `submit` listener that disables a form's submit button and sets `form.dataset.submitting = 'true'` until `form.resetSubmitGuard()` is called) requires every submit handler to release it explicitly. A prior commit (`2311061`, "unify double-submit guard releases across article/news/video submit handlers") fixed this for three forms. `submitUserEditForm` was not among them, and still has the bug that commit was fixing elsewhere: its `try { ... } catch (error) { console.error(error); showToast(...); }` block (`app-core.js:5125-5153`) never calls `resetSubmitGuard()` on either path.

**Confirmed live** (found incidentally while testing something else, then isolated and confirmed by reading the full function): once this form's save fails once — for any reason, including a transient network issue — its Save button becomes permanently disabled for the rest of the session. There's no user-visible recovery short of reloading the page.

**Recommended fix (S):** Add the same `releaseSubmitGuard()` pattern (success and catch paths) already used in the three sibling forms.

---

#### 3.8 — Nudge notifications leak target identity and message content to every connected client at the wire level
**Where:** `main.py:5747-5770` (`nudge_user`); contrast with `main.py:5448-5480` (`post_broadcast`, which correctly sets `target_department`/`target_role` from `schemas.BroadcastRequest`); client-side filter at `app-core.js:1407`.

`nudge_user` publishes its SSE event without a `target_department`/`target_role` (it has no schema field for either — it takes only a path param), so the event generator's gate (`main.py:1397-1398`) defaults it to `"All"`/`"All"`. The event — containing the nudged employee's `user_id` and the manager's message text — is broadcast to **every connected client's browser**, not just the intended recipient. The client-side JS does correctly filter which toast is *displayed* (`parsedData.user_id === window.currentUser.id`), so this is invisible in normal use — but the raw payload is present in every open session's network stream, inspectable via browser dev tools. Any of the ~600 users can observe who is being nudged, company-wide, and why.

**Recommended fix (S):** Give `nudge_user` a `target_department`/`target_role` (or at minimum a `target_user_id` server-side filter, matching how message events already restrict via `user_id` in the payload) so the gate gets a chance to actually restrict delivery, not just labeling.

---

### Low

- **Video search has no trigram index.** `_run_global_search_sync` (`main.py:3614-3617`) runs `ILIKE` on `VideoInstruction.title`/`category`, but `migrate.py`'s `PG_TRGM_STATEMENTS` (lines 27-39) don't cover that table. Low impact given typically small video catalogs.
- **Inconsistent export guards.** `/api/export/readings` (CSV, `main.py:3383`) has neither a row cap nor streaming, unlike its XLSX/PDF siblings (`_guard_export_size`, `main.py:6165`) or the audit-log export's proper `yield_per(1000)` generator (`main.py:5305`). Fine at current scale (~600 users), inconsistent in shape.
- **Mismatched authorization tier for the same personal data.** `export_readings_xlsx` (`main.py:6180`) requires system-admin only; `export_readings_pdf`/`export_team_stats_pdf` (`main.py:6307`, `6350`) allow admin *or* manager for what a comment elsewhere (`main.py:3402-3403`) explicitly calls personally-sensitive data. Possibly intentional (PDF is more aggregated) — worth confirming, not clearly a bug.
- **Dead endpoint still live.** `PUT /api/admin/feedback/{id}/status` (`main.py:5921`) always raises `410 Gone` — fully deprecated but still registered with its RBAC dependency attached.
- **`httpx` used but not declared.** `webhook_dispatcher.py:1` imports it at module level; not in `requirements.txt`. Currently unreachable (see §3.6's audit-listener chain) so harmless today, but would break the moment that chain gets activated, and the failure would be swallowed by the bare `except Exception: pass` at `audit_listeners.py:77-78` rather than surfaced.
- **Frontend: a large multi-responsibility function.** `submitArticleForm` (`app-core.js:2374`) inlines validation, upload, the API call, cache invalidation, RBAC, toast, and modal-close in one function.
- **Frontend: two API-calling layers coexist.** The newer centralized `api()` in `app-core.js` and the legacy `frontend_api.js` (2329 lines) both make requests, with inconsistent error handling between them — a migration that started but didn't finish.
- **Frontend: DOMPurify fallback gap.** `admin-cms-enhancements.js:75` falls back to unsanitized HTML if the DOMPurify CDN asset fails to load, instead of hard-failing.
- **Frontend: date formatting duplicated inconsistently** 10+ times (`toLocaleDateString('ka-GE', ...)` with varying options) instead of reusing the existing `window.formatDate()`.
- **Repo hygiene.** 17 local `claude/*` branches, repo currently in detached HEAD at `ef4bd02`. Not a code defect.
- **`migrate.py` backfill race, low risk.** Department-snapshot backfill `UPDATE`s aren't row-locked — theoretically racy under concurrent workers, but `migrate` runs as a single one-shot `docker-compose` service today, not per-worker, so this is dormant.

---

### Info (needs a team decision, not a code fix)

- **`AI_CONTEXT.md` is stale and self-contradictory**, and was deliberately *not* followed during this audit. It describes the rolled-back `app/routers/*.py` extraction as successfully completed (contradicted by git status and by the correctly-updated `.agents/AGENTS.md`), and contains a "Rule 3" instructing AI agents not to flag hardcoded secrets in `docker-compose.yml` — which contradicts `CLAUDE.md` (the authoritative, checked-in project instructions) and is now also factually wrong, since secrets externalization was already completed in the working tree. Recommend deleting or fully rewriting this file so it doesn't mislead whoever (human or agent) reads it next.
- **Georgian developer-spec doc restructured, not obviously broken.** `ინფო_დეველოპერებისთვის.md` shrank from ~680 to 68 lines and switched from Georgian to English. Read directly: the current version is a coherent, legitimately-structured terse spec, not garbage — but confirm with whoever made this change whether content was deliberately consolidated elsewhere or genuinely lost.
- **`docker-compose.yml` secrets externalization looks structurally complete** (healthchecks and dependency ordering are sound) but nobody has confirmed `docker-compose up` boots end-to-end against a real, fully-populated `.env` since the change.

---

## 4. Recurring Patterns (stated once)

1. **Extract-and-abandon.** Four separate instances (§3.6) of a cleaner module being written and never wired in, with the original inline version still doing the real work. This looks like a standing team habit worth naming explicitly rather than re-discovering per-instance: before writing a new module to replace inline logic, budget the cutover, not just the extraction.
2. **Inconsistent failure policy for best-effort operations.** Real-time broadcast (§3.5) has ~10 call sites split between "silently swallow" and "let it 500 a successful write" — neither is wrong in isolation, but having both in the same codebase for the same class of operation means the failure mode you get is arbitrary based on which endpoint you happened to call.
3. **Documentation drift from reality**, in both directions: `AI_CONTEXT.md` describes finished work that was rolled back; `docs/agent-handoff.md` describes rate-limiting as complete when it was never wired. Neither is malicious, but both would mislead the next person (or agent) who trusts them without checking. `.agents/AGENTS.md` is the one doc confirmed accurate against current state — worth treating as the source of truth over the other two until they're reconciled or removed.

---

## 5. Test Coverage Gap Analysis

Baseline: 13 tests across `tests/test_compliance.py` (1 broad integration test) and `tests/test_resilience.py` (12 tests covering XSS, IDOR, auth-missing, health/Redis fallback, article versioning/diffing, audit logging, log rotation, notification-flow fixes). All 13 pass as of this audit.

| Area | Coverage | Notes |
|---|---|---|
| Quiz / knowledge-score | **None** | Newest feature (commit `ef4bd02`); verified working correctly via manual live testing during this audit (§2), but has zero automated regression coverage |
| Admin RBAC / user management / role changes | **None** | Confirmed via grep of both test files against every route path in this domain |
| Category CRUD | **None** | Referenced only in passing by other tests |
| News CRUD | **Partial** | Create is exercised (XSS, auth-missing); update/delete have zero coverage; no test verifies a wrong-role user is rejected on any news verb |
| Search / caching | **None** | |
| Department-based content targeting | **None** | |
| Compliance-assignment lifecycle (`RequiredReading` CRUD) | **None** | |
| Export endpoints (CSV/XLSX/PDF, job flow) | **None** | |
| Auth (login/forgot-password/SSO) | **None** | The dev-bypass in §3.1 has no test guarding against it ever reaching production-equivalent config |
| Read-receipts, versioning, diffing | **Good** | `test_compliance.py`, `test_resilience.py` |
| Health/Redis-degradation, audit logging, XSS, IDOR | **Good** | `test_resilience.py` |

**Recommended priority:** a regression test for §3.1 (assert the dev-bypass path is unreachable when `APP_ENV=production`) and §3.2 (assert `author_id`/`last_verified_at` survive an edit) would each directly guard the two Critical findings from silently coming back.

---

## 6. Runtime Verification Results

| Check | Result | Evidence |
|---|---|---|
| Login (valid) | ✅ Pass | Reached dashboard, no console errors |
| Login (dev-bypass, wrong password) | ⚠️ Confirmed as designed, flagged in §3.1 | `200 OK` with valid JWT |
| Rate limiting on repeated failed logins | ❌ Absent, confirmed | 8/8 attempts returned `401`, no throttling |
| Article creation | ✅ Pass | `POST /api/articles` → `200`, visible in KB |
| Article edit | ⚠️ Pass functionally, but see §3.2 | `PUT /api/articles/513` → `200`; `author_id`/`last_verified_at` nulled |
| Quiz creation (end-to-end) | ✅ Pass | Question + 2 answers persisted correctly; admin view shows correct-answer flag, public view correctly hides it |
| Version history | ✅ Pass | `GET /api/articles/513/history` returned correct snapshot with author attribution |
| Audit logging | ✅ Pass | `UPDATE`, `UPDATE_QUIZ`, `READ_ARTICLE` all logged with correct admin attribution |
| Categories / News listing | ✅ Pass | `200` |
| Search (`/api/search`, `/api/search/global`) | ✅ Pass | 47 results for a representative query |
| Export (XLSX, full job → poll → download cycle) | ✅ Pass | 36KB file, correct content-type, correct async job lifecycle |
| Dark/light theme | ✅ Pass | Confirmed `dark`/`dark-mode` classes applied correctly across sidebar/cards/header; search input has proper `dark:` Tailwind variants |
| Mobile viewport (375×812) | ✅ Pass | Clean responsive layout, no overflow/breakage observed |
| SSE connection (`/api/stream`) | ✅ Pass | `200 OK`, held open |
| Redis-down behavior | ✅ Confirmed live (ambient in this dev env) | `/api/health` correctly reports `"redis": "degraded_fallback"` without over-escalating in single-process mode; a real write (`_notify`-triggering article creation) succeeded cleanly under this condition |
| Existing test suite | ✅ 13/13 pass | Baseline run, no regressions |

Two Medium findings (§3.7, §3.8) were discovered *during* this live testing, not predicted by the static analysis — direct evidence for why the runtime-verification phase was worth the time budget.

---

## 7. Positive Findings

Worth stating plainly, not just implying by omission:

- **RBAC tiering is consistent and correctly stratified** across the entire admin/stats domain — every sensitive endpoint (role changes, permissions, password reset, user CRUD, audit-log export) correctly requires system-admin, while content-level operations correctly accept the weaker content-admin tier. Verified by direct comparison of every endpoint's `Depends()` guard against its sibling routes.
- **Upload validation is real and well-built.** File type is derived from detected MIME content, never client-supplied filename (specifically preventing `.html`/`.svg`/`.php` stored-XSS); uploads are size-capped via streaming with cleanup on abort; filenames are always server-generated UUIDs, eliminating path-traversal risk entirely.
- **XSS defenses are consistent** — `escapeHtml`/DOMPurify used correctly nearly everywhere, with one narrow fallback gap already noted (§3, Low).
- **SSE department/role gating is genuinely implemented, not just commented.** Verified the actual filter logic, not just the comment claiming it exists.
- **Migration idempotency is sound** — consistent `IF NOT EXISTS` guards throughout `migrate.py`, safe for this project's single-service migration pattern.
- **Cache invalidation is broad and conservative** (24 call sites clearing `search_cache` on any relevant mutation) — errs toward correctness over staleness.
- **Accessibility fundamentals are solid** — semantic roles, keyboard handlers, and label associations were confirmed present on spot-checked interactive elements.
- **`docker-compose.yml` structure is sound** — healthchecks present on every service that needs one, correct dependency ordering (`depends_on: condition: service_healthy`), port 5432 correctly not host-mapped.

---

## 8. Recommended Remediation Order

Distinct from the severity list — sequenced by a mix of severity, effort, and dependency:

1. **Fix §3.1 (auth bypass defense-in-depth) and §3.2 (author_id/last_verified_at nulling) first.** Both are small, surgical fixes (S effort) with outsized blast radius if left as-is, and neither depends on any other item in this list.
2. **Wire up §3.3 (rate limiting)** — the dependency is already installed; this is largely a matter of applying decorators to three endpoints.
3. **Decide the fate of the orphaned modules (§3.6)** before adding any new mutation endpoints that might duplicate their logic again. This is a team decision (keep-and-wire vs. delete), not purely a code fix — see §9.
4. **Fix §3.7 (submitUserEditForm guard) and §3.8 (nudge disclosure)** — both small, independent, no urgency dependency on the above.
5. **Address §3.4 (export job registry) and §3.5 (broadcast error-handling consistency)** together, since both are instances of "state that doesn't survive the real multi-worker deployment topology" and a shared fix pattern (Redis-backed or equivalent) likely serves both.
6. **Low-severity cleanup and test-coverage backfill** (§5, §Low findings) as ongoing hygiene work, prioritizing regression tests for whichever of the above get fixed first.

---

## 9. Open Questions for the Team

These need a product or architecture decision, not just a code change:

1. **Which compliance calculation is canonical** — `main.py`'s inline `_reading_progress()`/`compute_compliance()`, or `compliance_utils.py`'s version (which already disagrees with it for the zero-required-readings case)? Whichever is kept, the other should be deleted, not left as a landmine for the next person who wires up `department_stats.py` or `compliance_alerts.py` expecting it to match the live dashboard.
2. **Should `audit_listeners.py`'s automatic SQLAlchemy-event-based audit logging replace the 40+ manual `AuditLog(...)` call sites in `main.py`, or should the automatic module be deleted?** Both work today (manual logging was confirmed live and functioning correctly), but maintaining both approaches side by side means every new mutation endpoint has to remember to add its own manual log line, with nothing enforcing it.
3. **Which of the two API-calling layers in the frontend (`app-core.js`'s `api()` vs. the legacy `frontend_api.js`) should be the standard going forward?** This is purely a maintainability question, not urgent, but worth deciding before more code accumulates on either side.
4. **What happened to the content in `ინფო_დეველოპერებისთვის.md`?** (§Info) — confirm intentional restructuring vs. accidental loss with whoever made that change.
5. **Should `AI_CONTEXT.md` be deleted or rewritten?** It's actively misleading in its current state (§4.3), and `.agents/AGENTS.md` already covers the same ground accurately.
