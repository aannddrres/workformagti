> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# OPUS5 — გამოსწორების პრომპტები (fix prompts)

ეს ფაილი აუდიტის **გაგრძელებაა**: `docs/OPUS5_AUDIT_1..4` აღწერს *რა* არის გასასწორებელი,
ეს ფაილი კი შეიცავს მზა, თვითკმარ დავალებებს სხვა AI აგენტისთვის, რომელიც ამ გამოსწორებებს
შეასრულებს.

**როგორ გამოიყენო:** აიღე ერთი პრომპტი (`### Prompt N` ბლოკი მთლიანად, სათაურის ჩათვლით),
ჩააგდე ახალ სესიაში და გაუშვი. თითოეული პრომპტი დამოუკიდებელია — ერთდროულად ორის გაშვება
შესაძლებელია მხოლოდ იმ შემთხვევაში, თუ ისინი სხვადასხვა ფაილს ეხება (ქვემოთ თითოეულს
მითითებული აქვს, რა ფაილებს ეხება და რაზეა დამოკიდებული).

---

## საერთო წესები — დაამატე ყოველ პრომპტს

> ყოველი პრომპტი უკვე შეიცავს ამ ბლოკს; აქ ცალკე იმისთვისაა, რომ ერთ ადგილას იყოს.

```
CONTEXT (read first, in this order):
- CLAUDE.md (repo root) — the project's own rules. Follow them exactly:
  surgical edits only (never rewrite a whole file), cite file:line in your
  summary, bilingual Georgian+English UI, dark/light theme, mobile-first,
  migrations must stay idempotent, never commit secrets.
- docs/JAVA_ORACLE_ANGULAR_MIGRATION.md — what each Java controller is meant
  to match in the original Python app. Do not "fix" deliberate parity
  decisions without saying so explicitly in your summary.
- docs/QUESTIONS_FOR_IT.md — anything blocked on Magti IT (AD/SSO, K8s
  specifics, network topology). Do not block on these; if your task depends
  on one, implement what you can and add the open question to that file.
- The audit finding referenced below, in the report named in the task.

RULES:
- Verify before you change: read the actual code path, do not trust a comment
  that claims a control exists (the audit found several stale comments).
- Every claim in your summary must be traced or tested, not inferred.
- Run `cd java-backend && mvn -B test` (or `cd angular-frontend && npx ng test
  --watch=false`) after the change. NOTE: 217 of the 383 Java tests need a
  live Oracle 19c at localhost:1521 and will error with ORA-12541 without
  one — that is environmental, not your regression. Compare the failure set
  before and after your change; the 166 unit tests must stay green.
- Do NOT start any long-running/interactive server.
- Add or extend a test that fails before your fix and passes after it.
  If you cannot, say so explicitly and explain why.
- Report honestly: if part of the task is blocked or you are unsure, say
  which part and why. Do not silently narrow the scope.
```

---

## რიგითობა

| # | პრომპტი | სიმძიმე | ბლოკავს გაშვებას? |
|---|---|---|---|
| 1 | [Prompt A — production config](#prompt-a--production-safety-config) | 🔴 Critical + Medium | **დიახ** |
| 2 | [Prompt B — department scoping](#prompt-b--department-scoping-of-exports-and-the-dashboard) | 🟠 High ×2 | **დიახ** |
| 3 | [Prompt C — storage](#prompt-c--shared-storage-for-uploads-and-exports) | 🟠 High | **დიახ** |
| 4 | [Prompt D — CI](#prompt-d--ci-that-actually-builds-the-new-stack) | 🟠 High | არა, მაგრამ ადრე |
| 5 | [Prompt E — deletion integrity](#prompt-e--deletion-integrity-news-articles-orphans) | 🟠 High + Medium | არა |
| 6 | [Prompt F — autosave versioning](#prompt-f--autosave-must-not-silently-invalidate-read-receipts) | 🟠 High | არა |
| 7 | [Prompt G — frontend silent failures](#prompt-g--the-frontend-must-tell-the-user-when-something-fails) | 🟠 High ×2 + Medium | არა |
| 8 | [Prompt H — polling teardown](#prompt-h--stop-the-infinite-export-polling) | 🟠 High | არა |
| 9 | [Prompt I — rate limiter](#prompt-i--login-rate-limiting-behind-a-proxy) | 🟠 High | არა |
| 10 | [Prompt J — PDF font](#prompt-j--pdf-export-in-the-container) | 🟠 High | არა |
| 11 | [Prompt K — permissions that lie](#prompt-k--permissions-that-do-nothing) | 🟡 Medium ×2 | არა |
| 12 | [Prompt L — remaining medium batch](#prompt-l--remaining-medium-findings-batch) | 🟡 Medium | არა |
| 13 | [Prompt M — low-severity cleanup](#prompt-m--low-severity-cleanup) | 🟢 Low | არა |

---

### Prompt A — production-safety config

**აფიქსირებს:** SEC-01 (Critical), SEC-07, PR-05, SEC-16 · **ანგარიში 1 და 4**
**ეხება:** `java-backend/src/main/resources/application.yml`, `config/PortalProperties.java`,
`config/ProductionSafetyGuard.java`, `java-backend/Dockerfile`, `java-backend/.env.example`

```
Read docs/OPUS5_AUDIT_1_SECURITY.md findings SEC-01, SEC-07, SEC-16 and
docs/OPUS5_AUDIT_4_PRODUCTION_READINESS.md finding PR-05 in full before
starting. [+ CONTEXT/RULES block above]

TASK — close the "one forgotten env var opens the whole system" hole.

1. The password-less dev-login bypass (AuthenticationService.java:74-92) is
   gated on `!properties.isProduction()`, and `portal.app-env` defaults to
   "development" in BOTH PortalProperties.java:16 and application.yml:56,
   while java-backend/Dockerfile sets no APP_ENV at all. Invert this so the
   INSECURE mode is the one that must be explicitly asked for:
   - default the app env to production in both places;
   - require an explicit opt-in for the bypass (e.g. a separate
     `portal.security.allow-dev-login` flag) that ProductionSafetyGuard
     REFUSES when the env is production;
   - add `ENV APP_ENV=production` to java-backend/Dockerfile;
   - log a clear WARN line at startup whenever the bypass is live, so the
     condition is visible in any environment (today it is completely silent).
   Keep the bypass working for local development — it is deliberate
   (see the auth-bypass-intentional-pending-ad decision), only its default
   must flip.

2. ProductionSafetyGuard.java:31 compares the JWT secret against ONE exact
   literal, so java-backend/.env.example:15's placeholder
   ("change-me-to-a-long-random-value") passes. Strengthen it: reject secrets
   below ~48 chars, reject a small deny-list of shipped placeholders
   (change-me…, CHANGE_ME…), reject very low distinct-character counts.

3. application.yml:15 ships `password: ${ORACLE_DB_PASSWORD:MagtiAppDev2026Pw}`
   — a real-looking corporate password committed to git. Replace the default
   with an obvious placeholder, and extend ProductionSafetyGuard to reject
   placeholder/dev DB passwords when the env is production.

4. application.yml:63-65's comment claims "no Java equivalent exists yet
   (todo)" for the production guard. It exists. Update the comment to point
   at ProductionSafetyGuard and state precisely what it does and does not
   check.

ACCEPTANCE:
- ProductionSafetyGuardTest gains cases for: placeholder secret rejected,
  short secret rejected, placeholder DB password rejected, dev-login flag
  rejected in production.
- A test proves the bypass is OFF with no env vars set at all.
- `mvn -B test` unit tests green.
```

---

### Prompt B — department scoping of exports and the dashboard

**აფიქსირებს:** SEC-02, SEC-03 (ორივე High) · **ანგარიში 1**
**ეხება:** `web/ExportController.java`, `export/ExportQueryService.java`,
`web/StatsController.java`, `stats/DepartmentStatsBuilder.java`

```
Read docs/OPUS5_AUDIT_1_SECURITY.md findings SEC-02 and SEC-03 in full before
starting. [+ CONTEXT/RULES block above]

TASK — stop personal data leaking across departments. Bug #312 fixed this
class of leak in two StatsController endpoints; the fix never reached these.

1. ExportController's four export endpoints (:91-188) gate only on the
   `reports.export` permission (:303-311), which Permission.java:45 grants to
   EVERY manager by default — and ExportQueryService.eligibleReadingRows()
   (:56-86) / departmentComplianceTotals() (:94-104) are org-wide with no
   caller argument. Result: any group manager can download the name,
   department and compliance status of all ~600 employees.
   Give both query methods a caller argument and apply the SAME rule
   StatsController.getCriticalOperators (:293-297) already uses: unscoped for
   SYSTEM_ADMIN/CONTENT_ADMIN, pinned to the caller's own department for
   MANAGER. Also record the effective scope in the export's audit row
   (ExportController.java:270-278) the way
   AuditLogController.writeMetaAudit already records `scope_department`.

2. StatsController.getDepartmentStats (:250-260) calls the no-argument,
   org-wide computeCompliance() with no scoping branch, and the response
   (DepartmentDashboard → DepartmentStats → DepartmentGroupStats →
   DepartmentMember) carries user_id, user_name, position, percentage and
   is_critical for EVERY department. The same manager gets a 403 from
   getGroupUsers (:325-332) for that same group — so the intent is already
   expressed elsewhere in the file.
   Decide explicitly and say which you chose in your summary:
   (a) keep the dashboard org-wide for managers but strip `members` from the
       payload for MANAGER callers (aggregate only), or
   (b) scope computeCompliance() by the caller's department, as (1) does.

ACCEPTANCE:
- An integration test asserting a MANAGER's export contains ONLY their own
  department's rows, and a second asserting the dashboard payload for a
  MANAGER contains no other department's member names.
- content_admin/system_admin behaviour is unchanged (assert this too).
```

---

### Prompt C — shared storage for uploads and exports

**აფიქსირებს:** PR-03 (High), BL-09 · **ანგარიში 4 და 2**
**ეხება:** `web/UploadController.java`, `config/WebConfig.java`,
`export/ExportJobWorker.java`, `web/ExportController.java`, deployment manifests
**⚠ საჭიროებს გადაწყვეტილებას** — შესაძლოა IT-სთან შეთანხმებას მოითხოვდეს.

```
Read docs/OPUS5_AUDIT_4_PRODUCTION_READINESS.md finding PR-03 and
docs/OPUS5_AUDIT_2_BACKEND_LOGIC.md finding BL-09 in full before starting.
[+ CONTEXT/RULES block above]

TASK — make uploaded files and generated exports survive a restart and be
visible from every replica. Today they are written to the pod's own disk
(portal.uploads-dir defaults to the relative path "uploads",
PortalProperties.java:18 / application.yml:60; UploadController.java:95-98;
ExportJobWorker.java:51-54), with no VOLUME in java-backend/Dockerfile and no
K8s manifests in the repo. Consequences: every restart permanently deletes
all attachments (the DB keeps the /uploads/<uuid> links), and with >1 replica
a file is visible roughly 1/n of the time.

START by writing a short options comparison in your summary (do not just pick
one silently): (a) ReadWriteMany PersistentVolume mounted at /app/uploads,
(b) S3-compatible object storage behind the upload/serve paths, (c) store
bytes in Oracle as BLOBs. Consider that this repo has NO K8s manifests yet
and that docs/QUESTIONS_FOR_IT.md §4 has open storage-adjacent questions.

THEN implement the option you recommend, and:
- Fix the export one-shot/pod-local problem at the same time
  (ExportController.java:204-232): stop deleting the job row on first
  download (cleanupExport at :261-268) — ExportJobCleanupScheduler already
  exists for TTL cleanup — and distinguish "still building" / "expired" /
  "failed" in the response instead of returning the same misleading 404.
- If the chosen option needs infrastructure this repo cannot provide, add
  the precise requirement to docs/QUESTIONS_FOR_IT.md (as a hard
  prerequisite, not an optimisation) and implement everything that does not
  depend on the answer.

ACCEPTANCE: a test proving a second download of the same job id still works,
and a documented statement of what the deployment must provide.
```

---

### Prompt D — CI that actually builds the new stack

**აფიქსირებს:** PR-01 (High), PR-10 · **ანგარიში 4**
**ეხება:** `.github/workflows/ci.yml`, `angular-frontend/package.json`, `.nvmrc`

```
Read docs/OPUS5_AUDIT_4_PRODUCTION_READINESS.md findings PR-01 and PR-10 in
full before starting. [+ CONTEXT/RULES block above]

TASK — .github/workflows/ci.yml (the only workflow, 26 lines) runs ruff +
pytest on the RETIRED Python app and nothing else. The Java backend (383
tests) and the Angular app are never compiled, never tested, never built.
Every PR is green regardless of what breaks in the new stack.

1. Add a Java job running `mvn -B test`. 217 of the 383 tests need a live
   Oracle 19c — decide and implement one of: Testcontainers with
   gvenzl/oracle-free, a GitHub Actions service container, or a Maven
   profile split so at least the 166 unit tests gate every PR while
   integration tests run on a schedule. State your choice and why.
2. Add a frontend job: `npm ci && npx ng build && npx ng test --watch=false`.
3. Pin Node: add `"engines": { "node": ">=22.22.3" }` to
   angular-frontend/package.json and an .nvmrc, and pin
   angular-frontend/Dockerfile:2 to a specific patch tag instead of the
   floating `node:22-alpine`. (The audit could not run ng test at all because
   the container had v22.22.2 — one patch behind what the Angular CLI
   requires — and nothing in the repo declared the requirement.)
4. Consider adding `docker build` for both images so a broken Dockerfile is
   caught before deployment.

ACCEPTANCE: the workflow passes on this branch, and deliberately breaking a
Java test or an Angular template makes it fail.
```

---

### Prompt E — deletion integrity (news, articles, orphans)

**აფიქსირებს:** BL-01 (High), BL-02 (High), BL-10, BL-14 · **ანგარიში 2**
**ეხება:** `db/migration/V31__…sql` (ახალი), `web/NewsController.java`,
`web/ArticleController.java`, `web/VideoController.java`

```
Read docs/OPUS5_AUDIT_2_BACKEND_LOGIC.md findings BL-01, BL-02, BL-10, BL-14
in full before starting. [+ CONTEXT/RULES block above]

TASK — deletion is currently either impossible or leaves debris.

1. BL-01: V16__create_news_history.sql:10 declares fk_news_history_news with
   NO ON DELETE CASCADE, while every article-child table has it (V17, V18,
   V21, V26, and V24/V25 retro-fitted by V30). updateNews writes a history
   row on EVERY edit (NewsController.java:160), so DELETE /api/news/{id}
   returns ORA-02292 → HTTP 500 for any news item that was ever edited.
   Add a V31 migration mirroring V30 line-for-line (drop + recreate with
   ON DELETE CASCADE). Decide deliberately whether fk_news_history_user
   should become ON DELETE SET NULL for the same reason.

2. BL-02: required_readings.item_type/item_id is a polymorphic soft
   reference with no FK (V6), so deleteArticle (ArticleController.java:
   424-430) leaves the mandatory-reading row behind. It keeps counting in the
   compliance denominator (ComplianceCalculator.java:88-104) and renders as
   "Item #482 / Content not available." (ComplianceController.java:137-138)
   forever, permanently depressing a whole department's percentage.
   Delete the matching required_readings AND read_statuses rows inside the
   same transaction (mind the order: read_statuses → required_readings →
   item). Do the same for deleteNews.

3. BL-10: no delete path clears tags_mapping or favorites (both polymorphic,
   no FK). Call tagSyncService.sync(type, id, "") and delete matching
   favorites in all three delete paths.

STRONGLY CONSIDER extracting one ContentDeletionService that owns all
polymorphic cleanup, rather than repeating the same list in three
controllers — the audit found this list is already inconsistent between them.

4. BL-14 resolves itself once BL-02 is fixed; confirm and say so.

ACCEPTANCE: tests for (a) deleting an edited news item succeeds, (b) deleting
an article removes its required reading and read statuses, (c) no orphan tag
mappings or favourites remain. Also add a one-off cleanup note for rows
already orphaned by deletions performed before this fix.
```

---

### Prompt F — autosave must not silently invalidate read receipts

**აფიქსირებს:** BL-03 (High) · **ანგარიში 2**
**ეხება:** `web/ArticleController.java:325-410`

```
Read docs/OPUS5_AUDIT_2_BACKEND_LOGIC.md finding BL-03 in full before
starting. [+ CONTEXT/RULES block above]

TASK — autosaveArticle (ArticleController.java:325-410) writes title,
content, status, published_at, target_departments and is_draft to the live
row and then sets ONLY updatedAt (:405-407). It does not bump the version and
writes no history row — unlike updateArticle (:293-320), which does both. It
also has no guard restricting it to drafts.

Because the version does not move:
- a passed quiz still satisfies QuizGateChecker (:47-48 keys on
  articleId + articleVersion + userId);
- an existing read receipt still counts as "read the current version"
  (uq_article_read_receipt_version_operator, V19:17);
- the article_history snapshot for version N no longer matches the article at
  version N, so a diff of "current vs version N" shows changes within one
  version number.

Net effect: an admin can materially rewrite a published procedure and the
read-receipt report still shows full compliance for text nobody has read.

Decide the intended contract EXPLICITLY and state it in your summary:
(a) restrict autosave to drafts / non-published articles (409 otherwise), or
(b) make autosave bump the version and write history the same way
    updateArticle does whenever it touches title or content of a PUBLISHED
    article.
Then implement it.

ACCEPTANCE: a test asserting that a passed quiz does NOT satisfy the gate
after the article's content changes, whichever option you chose.
```

---

### Prompt G — the frontend must tell the user when something fails

**აფიქსირებს:** FE-01 (High), FE-02 (High), FE-04, FE-05 · **ანგარიში 3**
**ეხება:** `angular-frontend/src/app/**` (25 call sites + interceptor)

```
Read docs/OPUS5_AUDIT_3_FRONTEND.md findings FE-01, FE-02, FE-04, FE-05 in
full before starting — FE-04 contains the complete inventory table.
[+ CONTEXT/RULES block above]

TASK — 25 of the app's 97 subscribe call sites give the user no feedback on
failure: 14 pass `error: () => {}` and 11 have no error handler at all.

Treat "no feedback" as ONE bug, not 25. This class has now been found and
fixed three separate times in this app (admin-content-page's article
delete/archive was the last), so fix the pattern, not just the instances:
add a shared error-handling operator in the service layer, or a global
HttpInterceptor that surfaces a toast for any unhandled 4xx/5xx.

Then make sure these specific ones are covered — they are the worst:
- news-admin-table.ts:66 and videos-admin-table.ts:66 — delete failures
  swallowed. Note the backend currently 500s on deleting any edited news item
  (Prompt E / BL-01), so this failure is the NORMAL case today, not an edge.
- admin-users-page.ts:111 (toggleStatus) — activating/deactivating a user is
  the app's only way to cut off access, and the backend returns a
  human-readable Georgian `detail` for its 400s which the UI throws away.
  Surface it, and re-run loadUsers() in the error branch so the displayed
  state stays truthful.
- article-edit-drawer.ts:161 (and the news/video equivalents) — if the
  "is this a mandatory reading" request fails, the checkbox renders unchecked,
  so saving can silently clear a real compliance obligation.

FE-05: there is no 401 handling anywhere (auth.interceptor.ts:8-14 only
attaches the token; core/http/ has only api-base-url.interceptor.ts). Add an
interceptor that catches 401, clears the stored token and routes to /login
with the current URL as returnUrl (auth.guard.ts already supports that param).

DO NOT "fix" these two — they are correct as-is, and the audit verified why:
my-readings-page.ts:116 (ComplianceService.markRead maps failures into a
result object, not the error channel) and app-shell.ts:73 (router.events
does not error).

ACCEPTANCE: an error-path test for each of the three worst call sites above.
```

---

### Prompt H — stop the infinite export polling

**აფიქსირებს:** FE-03 (High) · **ანგარიში 3**
**ეხება:** `core/services/export.service.ts`, `features/team-stats/team-stats-page.ts`

```
Read docs/OPUS5_AUDIT_3_FRONTEND.md finding FE-03 in full before starting.
[+ CONTEXT/RULES block above]

TASK — export.service.ts:35-40's pollUntilDone is
`interval(1500) → switchMap(status) → takeWhile(s => s.status === 'processing', true)`,
subscribed at team-stats-page.ts:215 with no teardown. `takeUntilDestroyed`
appears ZERO times in the whole app (the only ngOnDestroy is in
rich-text-editor.ts:126, unrelated), so:
- navigating away does not stop the interval, and each tick still writes to
  the destroyed component's signals;
- a job stuck at "processing" polls forever — one request every 1.5 s, ~2400
  per hour per open tab. Audit 2 (BL-09) documents exactly how a job stays
  "processing" indefinitely on a multi-replica deployment.

1. Pipe the polling subscription through takeUntilDestroyed() (inject
   DestroyRef in the component).
2. Give pollUntilDone a hard stop — take(n) or timeout(...) — so a stuck job
   surfaces as a failure the user can act on instead of an invisible loop.
3. Apply takeUntilDestroyed as a general convention while you are here:
   nothing in this app currently unsubscribes from anything. Do not churn
   files needlessly — cover the streams that do not complete on their own.

ACCEPTANCE: a test proving polling stops when the component is destroyed, and
one proving it gives up after the cap rather than looping forever.
```

---

### Prompt I — login rate limiting behind a proxy

**აფიქსირებს:** SEC-04 (High), SEC-05, PR-04 · **ანგარიში 1 და 4**
**ეხება:** `security/LoginRateLimiter.java`, `web/AuthController.java`, `application.yml`

```
Read docs/OPUS5_AUDIT_1_SECURITY.md findings SEC-04 and SEC-05, and
docs/OPUS5_AUDIT_4_PRODUCTION_READINESS.md finding PR-04, in full before
starting. [+ CONTEXT/RULES block above]

TASK — AuthController.java:61 keys the limiter on
httpRequest.getRemoteAddr() while application.yml sets no
server.forward-headers-strategy (the whole server: block is `port: 8080`,
:48-49). nginx always sits in front (nginx.conf.template:22-27 sets
X-Real-IP / X-Forwarded-For, which Spring is not configured to read), so
every user resolves to the proxy's IP and MAX_ATTEMPTS=10/minute
(LoginRateLimiter.java:30-31) applies to all ~600 employees COLLECTIVELY.
Anyone can lock out the whole company's logins with 10 requests. The same
cause makes audit_logs.ip_address (AuthController.java:80-81, 98) record the
proxy's address, so the audit trail's IP evidence is worthless.

1. Set server.forward-headers-strategy=framework AND configure the trusted
   proxy set — without the second half, X-Forwarded-For becomes
   client-spoofable and you have traded one bug for another. Do not skip it.
2. Key the limiter on email + real client IP, so one noisy address cannot
   lock out unrelated accounts.
3. LoginRateLimiter's state is a plain in-JVM ConcurrentHashMap (:33). Its
   javadoc argues a single shared counter is correct "because Spring Boot
   runs as a single JVM" — true for one pod, false the moment the K8s
   Deployment has replicas: 2, which is the stated target. Move the counter
   out of the JVM (Redis is already in the Python-side stack) or state
   plainly in the javadoc and in docs/QUESTIONS_FOR_IT.md that the
   deployment is pinned to one replica until this is done.

ACCEPTANCE: LoginRateLimiterTest gains a case proving two different users
behind the same proxy IP do not share a bucket, and one proving a spoofed
X-Forwarded-For from an untrusted source is ignored.
```

---

### Prompt J — PDF export in the container

**აფიქსირებს:** PR-02 (High) · **ანგარიში 4**
**ეხება:** `java-backend/Dockerfile`, `export/GeorgianPdfFont.java`

```
Read docs/OPUS5_AUDIT_4_PRODUCTION_READINESS.md finding PR-02 in full before
starting. [+ CONTEXT/RULES block above]

TASK — every PDF export fails in the container. GeorgianPdfFont.java:26-31
resolves a Georgian-capable TTF from four fixed paths (two Debian DejaVu
paths, two Windows paths); java-backend/Dockerfile's runtime stage is
eclipse-temurin:21-jre-alpine with NO package installation at all, so none of
them exists. PdfExportBuilder.java:52 then throws PdfFontUnavailableException,
ExportJobWorker.java:62-65 marks the job "failed", and the user sees "export
failed" with no reason — every time, for every user.

Note the Alpine wrinkle: even after installing a font, Alpine's package is
`ttf-dejavu` at /usr/share/fonts/ttf-dejavu/DejaVuSans.ttf, which matches
NONE of the four candidates. Installing the Debian-named package will not fix
this by itself.

Preferred fix: bundle the TTF as a classpath resource so PDF export cannot
depend on the base image at all. Acceptable alternative: install the font in
the runtime stage AND add the matching path to GeorgianPdfFont.CANDIDATES.
Check the font's licence before bundling and say what you found.

ACCEPTANCE: a test asserting GeorgianPdfFont resolves successfully, so this
fails loudly in CI (see Prompt D) instead of silently at runtime. Verify the
built image actually produces a PDF with Georgian glyphs.
```

---

### Prompt K — permissions that do nothing

**აფიქსირებს:** SEC-06, FE-06 · **ანგარიში 1 და 3**
**ეხება:** `web/UserController.java`, `web/ComplianceController.java`,
`domain/Permission.java`, `angular-frontend/src/app/app.routes.ts`,
`core/auth/role.guard.ts`

```
Read docs/OPUS5_AUDIT_1_SECURITY.md finding SEC-06 and
docs/OPUS5_AUDIT_3_FRONTEND.md finding FE-06 in full before starting.
[+ CONTEXT/RULES block above]

TASK — the security control most visible to the administrator is the one
that does not work.

1. Only 7 permissionChecker.hasPermission call sites exist in the whole
   backend. Three of the nine catalog entries are never consulted anywhere:
   `users.manage` (UserController gates on requireSystemAdmin, :530-539),
   `compliance.assign` (ComplianceController gates on requireContentAdmin,
   :334-343) and `articles.view` (acknowledged as a deliberate gap in
   ArticleController.java:86-92). PUT /api/users/{userId}/permissions
   (:477-515) still validates and persists all nine, so the admin UI offers
   three switches that do nothing — revoking users.manage changes nothing,
   granting compliance.assign changes nothing.
   Either enforce them (add requireX helpers mirroring
   requireArticlesEditPermission) or remove them from the catalog. Say which
   you chose and why. This is the same class as bug #314.
   ADD A TEST asserting every Permission.values() entry appears in at least
   one hasPermission call — that is what stops the gap reopening.

2. FE-06: the backend deliberately supports managers reading a
   department-scoped audit log (AuditLogController.java:205-207, 237-247;
   Permission.java:45 grants system.audit to MANAGER; UserController.java:92
   ships a can_view_audit_log flag to the frontend). The frontend gates
   /admin/** on roleGuard(['admin','content_admin']) (app.routes.ts:84-105),
   so a manager is bounced before the audit route is evaluated and that
   scoping code never runs in production. role.guard.ts:15-19 documents this
   as unfinished.
   Replace the coarse role gate on the audit route with a permission-aware
   guard driven by can_view_audit_log, and show the nav entry on the same
   condition.

ACCEPTANCE: backend test per newly-enforced permission; frontend test that a
manager can reach /admin/audit and an operator cannot.
```

---

### Prompt L — remaining medium findings (batch)

**აფიქსირებს:** SEC-08, SEC-09, SEC-12, BL-04, BL-05, BL-07, BL-08, FE-07,
PR-06, PR-07, PR-08, PR-09 · **ყველა ანგარიში**

```
[+ CONTEXT/RULES block above]

TASK — a batch of independent medium-severity findings. Do them as SEPARATE
commits, in this order, and skip nothing silently: if you disagree with one,
say so and move on to the next.

1. SEC-08 (audit 1): /uploads/** is served with no authentication at all
   (WebConfig.java:23-27 + SecurityConfig.java:45 permitAll). Random UUID
   filenames are the only protection, so any leaked URL is a permanent public
   handle — and it still works after the user's account is deactivated. Serve
   uploads through an authenticated path.
2. SEC-09 (audit 1): UploadController.java:75-87 trusts the CLIENT-declared
   Content-Type despite its javadoc (:23-37) claiming server detection. Add
   magic-byte verification and FIX THE JAVADOC either way.
3. SEC-12 (audit 1): UserController.updateUserAdmin (:314-348) can demote the
   last SYSTEM_ADMIN and can target the caller — neither guard from
   bulkReassignRoles (:172-176, :187-199) nor updateUserStatus (:246-249) is
   applied. Extract the last-admin check into a shared helper and call it
   here too. Also note lines 337-338 overwrite department/position with no
   null check, unlike the fields below them.
4. BL-04 (audit 2): ComplianceController.updateRequiredReading (:265-286) can
   re-point a reading at a different item, silently transferring everyone's
   read_statuses (keyed on required_reading_id, V22:11) — new material shows
   100% compliance instantly. Reject the change or clear the statuses, and
   add an audit row (this endpoint writes none).
5. BL-05 (audit 2): RequiredReadingNotifier.notifyAffectedUsers (:50-58) is
   the ONE place that does not filter ComplianceCalculator::isEligible, so
   managers/admins get inbox notifications for readings that never appear in
   their list (ComplianceController.java:107-109 returns [] for them).
6. BL-07 + BL-08 (audit 2): CategoryController's fallback lookup (:138) does
   not filter on is_active, so once "ზოგადი" is itself deleted, later
   deletions move articles into a category getCategories (:84-87) hides.
   Also categories.name has no unique constraint (V2, a deliberate parity
   decision) and createCategory (:91-103) does not check duplicates — two
   identical category names are indistinguishable to users. Decide
   explicitly: duplicate-name check in create/update, or a unique constraint
   plus a cleanup migration. RECORD the decision; this question has now been
   raised twice.
7. FE-07 (audit 3): news-page.ts:58 filters with `!==` against option values
   in news-page.html:24-26, one of which is the truncated "საინფო" instead of
   the canonical "საინფორმაციო" (DepartmentBuckets.java:22). Exact equality
   also misses grouped departments ("ტექნიკური — ჯგუფი 03"). Use the
   canonical value and a prefix-aware comparison mirroring
   DepartmentMatcher.matches; better, drive the options from the backend.
8. PR-06 (audit 4): no .dockerignore anywhere — every build ships the 183 MB
   magti_portal.db, .git and target/. Worse, angular-frontend/Dockerfile:11's
   `COPY . .` runs AFTER npm ci, so a host node_modules overwrites the
   installed one and a local .env would land in an image layer.
9. PR-07 (audit 4): the frontend image has no USER directive, so nginx runs
   as root (the backend image correctly uses appuser). nginx.conf.template:2
   already listens on 8080, so nothing blocks running unprivileged. Many
   clusters enforce runAsNonRoot — this would fail to start there.
10. PR-08 (audit 4): six log statements in the entire backend, and none of
    the security-relevant decisions logs anything. Log the effective
    security config once at startup (APP_ENV, dev-bypass state, cookie
    secure, pool size) — that single line would have made SEC-01 visible —
    and add a @ControllerAdvice that logs unhandled exceptions with a
    correlation id (there is currently none anywhere, so every unexpected
    exception reaches the client as a raw 500).
11. PR-09 (audit 4): HealthController's catch branch returns
    "error: " + e.getMessage() to unauthenticated callers, leaking Oracle
    host/port/service-name detail. Return a fixed string, log the detail.
```

---

### Prompt M — low-severity cleanup

**აფიქსირებს:** SEC-13, SEC-14, SEC-15, BL-11, BL-12, BL-13, FE-08, FE-09,
FE-10, PR-11, PR-12, PR-13, PR-14

```
[+ CONTEXT/RULES block above]

TASK — low-severity cleanup. Read the "Findings summary" table in each of the
four OPUS5 audit reports and work through the Low rows. None of these blocks
go-live; do them opportunistically and skip any you judge not worth the churn
(saying which, and why).

Two are worth more than their severity suggests:
- BL-11 (SUSPECTED, the only non-CONFIRMED finding in the whole audit):
  Article has no @Version, and both updateArticle (:307) and
  restoreArticleVersion (:832) compute version+1 from a stale read, so two
  simultaneous saves can collide on ux_article_history_article_version
  (V18:15) → opaque 500 and a lost edit. Adding @Version turns it into a
  clean 409 "someone else edited this", which is also better UX. The audit
  could not reproduce it (no Oracle available) — reproduce it first if you
  can, and say whether you confirmed or refuted it.
- FE-10: the Angular app has 5 spec files for 17 feature areas. Do not chase
  blanket coverage; add an error-path case beside each happy-path case in the
  components that MUTATE data (the two admin tables, the users page, the
  export flow). Nearly every finding in audit 3 is one a test like that would
  have caught.
```

---

## შენიშვნა თანმიმდევრობაზე

- **Prompt A, B, C** — გაშვებამდე. A ერთადერთი კრიტიკულია.
- **Prompt D** (CI) ღირს მალევე ამის შემდეგ, რადგან ის ყველა დანარჩენ გამოსწორებას იცავს
  რეგრესიისგან.
- **Prompt E და F** ერთსა და იმავე ფაილს (`ArticleController.java`) ეხება — **ერთდროულად არ
  გაუშვა**.
- **Prompt G და H** ორივე frontend-ს ეხება, მაგრამ სხვადასხვა ფაილს — პარალელურად შეიძლება.
- **Prompt C** შეიძლება IT-ის პასუხზე იყოს დამოკიდებული; პრომპტი ითვალისწინებს ამას და
  ავალებს აგენტს, არ გაჩერდეს, არამედ ღია კითხვა `docs/QUESTIONS_FOR_IT.md`-ში ჩაწეროს.
