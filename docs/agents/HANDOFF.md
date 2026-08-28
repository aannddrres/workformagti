# Objective

Last verified/updated for Claude handoff: **2026-08-27 (Asia/Tbilisi)**.

Claude must first read `docs/ENTERPRISE_READINESS_AI_EXECUTION_PLAN_KA.md` fully, then this file fully,
then re-run the read-only Git snapshot below. This handoff is a verified snapshot, not authority to trust
stale branch/worktree facts or to discard dirty work.

Magti Portal-ის არსებული Angular/Java/Oracle ფუნქციები ეტაპობრივად მივიდეს
Enterprise Pilot/Production readiness-მდე
`docs/ENTERPRISE_READINESS_AI_EXECUTION_PLAN_KA.md`-ის P0/P1, Definition of Done
და evidence gate-ების შესაბამისად. მიმდინარე verdict არის **NOT READY**.

# Verified repository state

- Repository root: `C:/Projects/Magti base`
- Branch: `codex/readiness-report-2026-08-23`
- Full HEAD: `dd775556b699de063fe1dd2bad0dd589e50a6619`
- Upstream: `origin/codex/readiness-report-2026-08-23`
- Ahead/behind: `0/0`
- HEAD summary: `dd77555 docs: add production readiness report`
- Current `git status --short`: 304 entries — 207 modified, 1 deleted, 96
  collapsed untracked entries.
- Current `git status --short --untracked-files=all`: 661 entries — 207
  modified, 1 deleted, 453 expanded untracked files.
- The count increase from the 163/517 baseline is explained by the current
  readiness/audit/health/load implementation; no existing work was discarded.
- One tracked deletion remains:
  `angular-frontend/src/app/core/auth/auth.interceptor.ts`.
- Worktree is heavily dirty and belongs to the user/current task.
- No reset, clean, checkout, commit, push, PR, branch switch, deployment or
  external-system mutation was performed.

# Completed work

- The execution plan, previous handoff and current product/UX/access/
  architecture sources were read and reconciled. Historical readiness reports
  were treated as snapshots, not current truth.
- Current Git branch, full HEAD, upstream, ahead/behind and both collapsed and
  expanded worktree status were independently re-verified.
- Created the required readiness artifacts:
  - `docs/ENTERPRISE_READINESS_ACCEPTANCE_MATRIX_KA.md`;
  - `docs/ENTERPRISE_READINESS_REPORT_2026-08-25_KA.md`;
  - `docs/ENTERPRISE_READINESS_DECISIONS_KA.md`;
  - `docs/ENTERPRISE_READINESS_TEST_EVIDENCE_KA.md`;
  - `docs/ENTERPRISE_READINESS_EXTERNAL_DEPENDENCIES_KA.md`.
- Selected initial independent P0 batches: audit completeness; file/resource
  entitlement; health/readiness and load gates.
- Implemented P0-A content mutation audit vertical slice:
  - new `MutationAuditService` writes actor/target/result/reason and
    before/after snapshots without full content bodies or secret material;
  - article/news/video create/update and category create/update/delete now write
    evidence inside the business transaction;
  - audit `saveAndFlush` ensures constraint/trigger/chain failure is observed
    before the endpoint returns and rolls the same transaction back;
  - existing XSS sanitization, atomic command, trash/lifecycle and search/tag
    changes were preserved.
- Added/updated unit and Oracle integration regressions for four content types,
  actor snapshots, version/audience snapshots, category soft delete and audit
  persistence failure semantics.
- Completed the next P0-A content lifecycle increment:
  - article/news/video archive and unarchive plus article/news version restore
    now use the same schema-versioned before/after audit contract;
  - Video archive/unarchive gained the missing controller transaction, closing
    the split-commit risk between business state and audit evidence;
  - article bulk archive/unarchive is normalized to the same contract;
  - trash/restore/purge now records title, version, lifecycle state, legal-hold
    state and non-sensitive attachment-reference count;
  - audit `saveAndFlush` remains inside every lifecycle business transaction.
- Expanded real-Oracle regressions for idempotency, actor/item snapshots,
  before/after status/version transitions, trash recovery, evidence-safe purge
  and shared audit-failure rollback semantics.
- Completed P0-A2 identity/access-administration audit increment:
  - generalized the content writer into the single `MutationAuditService`
    without changing existing content evidence;
  - self-profile, admin profile/department/team/role, user status, bulk role,
    per-user permission override and leadership create/deactivate mutations now
    use schema-versioned before/after audit evidence;
  - the shared writer `saveAndFlush` remains inside each business transaction;
  - user JSON details exclude target email, phone value, password/hash and token
    version; `phone_present` captures relevant state without contact data;
  - existing last-admin, self-demotion, optimistic-lock, role/scope and
    permission rules are unchanged;
  - directory-owned user/team/password mutation paths remain fail-closed and no
    local/password fallback was added.
- Implemented P0-C health/readiness code gate:
  - Actuator liveness excludes Oracle; readiness includes Oracle;
  - health details remain hidden on unauthenticated operational endpoints;
  - compatibility `/api/health` now returns HTTP 503 on dependency failure;
  - DOWN database test proves readiness=503 while liveness remains 200/UP.
- Hardened `scripts/load/k6-java-backend.js`: representative default 150 VUs,
  optional 600-VU stress override, global-search scenario, error <1%, checks
  >99%, and search p95 <2s hard thresholds.
- P0-B implementation was not guessed: PO-02/Access Matrix confirm `AUTH` scope,
  while the new execution plan asks for content-dependent object entitlement.
  This material scope/security conflict is recorded as `DEC-P01`.
- Completed P0-A3 remaining application audit reconciliation:
  - authentication success/failure/logout and owned-session revocation use the
    shared schema; unknown identifiers, credentials and tokens are excluded;
  - broadcast, required-reading administration/read acknowledgement, quiz
    administration/attempt, reminders, upload/file-access, article verification,
    policy backfill and article/news autosave are normalized;
  - export request/registration and asynchronous completion/failure are audited;
    job mutation and outcome evidence share one transaction;
  - message/content bodies, quiz answers, file bytes, exception strings and other
    sensitive payloads are not persisted in audit details;
  - production-source scan confirms `MutationAuditService` is the only direct
    application audit writer; raw audit-view meta events keep their pre-existing
    best-effort non-business-mutation contract.
- Completed P0-A4 global deny-by-default authentication increment:
  - Spring Security explicitly permits only login/SSO bootstrap, current
    idempotent logout compatibility and aggregate health probes;
  - every other route requires a live identity before MVC; controller/service
    role, permission and scope checks remain the second boundary;
  - anonymous requests preserve the established JSON 401 response contract;
  - anonymous logout versus Access Matrix `AUTH/SELF` conflict was not guessed
    and is recorded as `DEC-P02`;
  - local-only Java/Oracle + Angular Chromium E2E pair was started for the run,
    then all owned helper processes were stopped and ports 8080/4201/8090 freed.
- Completed P0-A5 SYSTEM_ADMIN named/raw-data negative matrix:
  - 22 administrative PII/evidence surfaces are exercised for OPERATOR, MANAGER
    and CONTENT_ADMIN through the real JWT/filter chain (66 combinations);
  - all 66 requests return 403 with a non-empty detail before protected query,
    export or mutation handling;
  - covered families include raw audit and view evidence, six admin exports,
    org/policy diagnostics, all-user/team stats, group leaders and user directory;
  - the first full run exposed an order-dependent upload test predicate that
    dereferenced nullable `audit_logs.admin_id`; comparison is now from the known
    non-null expected actor ID, with no production behavior or schema change.
- Completed P0-A6 manager team-stats foreign-IDOR closure:
  - an assignment-backed manager now defaults to their primary team on
    `/api/manager/team-stats`;
  - an explicit same-department foreign `team_id` fails with 403 before named
    statistics are queried, while own-team access remains 200;
  - acting interactive scope, unassigned legacy fallback and SYSTEM_ADMIN
    semantics remain unchanged.
- Completed P1-Q1 public list/search/cache cardinality and news CLOB increment:
  - article/news lists reject invalid offset/limit and enforce the current Angular-
    compatible ceiling of 1000;
  - news summaries come from a projection that does not select `content` CLOB;
  - search rejects queries above 200 characters, bounds fallback/trigram work sets
    and KB responses at 1000, and retains existing 2-character/ranking/visibility behavior;
  - the 60-second global-search cache normalizes keys, removes expired entries and
    cannot retain more than 512 entries;
  - WS3-05 remains `Partial`: P1-Q2 removes the article-list content load, P1-Q3
    bounds directory/user-progress, P1-Q4 bounds complete-result org/compliance/stats
    and P1-Q5 bounds the remaining identified user fan-outs; P1-Q6 adds exact DB paging/
    aggregation for article views and fail-loud history/read-evidence ceilings. History-row
    CLOB payload and org relation-list paths still need reconciliation.
- Completed P0-R1 legal-hold fail-closed state-machine increment:
  - `POST`/`DELETE /api/content-trash/{itemType}/{itemId}/legal-hold` set and release
    hold only for an externally configured exact active SSO identity;
  - authority is not inferred from any application role and the default allowlist is
    empty, so every role fails closed until DPO/Legal supplies approved identities;
  - the service repeats the authority check and records transaction-coupled
    `SET_LEGAL_HOLD`/`RELEASE_LEGAL_HOLD` before/after lifecycle evidence;
  - moving to trash preserves hold, restore cannot clear it, and held restore/purge
    return 423;
  - set/release/purge serialize on the Oracle content row before purge touches dependent
    references; the final delete retains an independent `legal_hold = 0` condition;
  - no automatic purge, authority identity or pending retention/security policy was chosen.
- Completed P1-Q2 article-list CLOB-free exact read-time projection:
  - `GET /api/articles` now uses `ArticleListItem` and does not select the content CLOB;
  - V45 backfills a derived `articles.read_time` scalar and an Oracle trigger recomputes
    it from content for every insert/update writer; the application mapping is read-only;
  - the first Oracle 19.3 attempt exposed `ORA-25006` because a LOB cannot be named in an
    `UPDATE OF` trigger list; Oracle had auto-committed only the column/backfill;
  - recovery was non-destructive: V45 gained an idempotent column guard and all-row trigger,
    and Flyway repair removed only the failed schema-history entry;
  - focused evidence proves a direct `read_time=999` write is reset to the content-derived
    value, while detail/global-search response behavior remains unchanged.
- Completed P1-Q3 bounded admin user-directory and all-user progress increment:
  - both existing plain-array endpoints accept optional `skip/limit`, default/ceiling 1000;
  - invalid bounds return the established 400 detail before querying;
  - Oracle applies offset/limit over deterministic ascending user-ID candidate slices;
  - progress candidates are current eligible active operators, and the shared compliance
    calculator rechecks eligibility before applying the unchanged formula;
  - SYSTEM_ADMIN-first authorization and all 66 non-admin negative combinations remain green.
- Completed P1-Q4 complete-result org/compliance/stats cardinality increment:
  - active-user complete-result queries request a 1001st Oracle sentinel row, accept up to
    1000 and return stable 413 instead of partial data when the ceiling is exceeded;
  - org-backfill plan/apply each use one deterministic snapshot, and apply reuses it across
    planning and mutation instead of reading the active directory twice;
  - pre-mutation apply overflow records transaction-coupled `ORG_BACKFILL_APPLY` failure
    evidence with reason `CARDINALITY_LIMIT`; the explicit no-rollback exception is limited
    to that pre-mutation resource condition and other runtime failures still roll back;
  - org-wide compliance and active-user statistics use the same shared ceiling before
    role/team/department filters; successful response shapes, scope and formulas are unchanged.
- Completed P1-Q5 remaining complete-result user fan-out increment:
  - access-diff (including inactive users), assignment reminder recipients, eligible operators/
    read evidence, manager export hydration, exact-department compliance and group-leader selector
    now use Oracle-side 1001 sentinel/shared 1000 ceiling and stable 413 overflow behavior;
  - assignment recipients are validated before the first reminder write, so an oversized
    directory rolls the enclosing required-reading transaction back without partial reminders;
  - Spring production uses the bounded directory service; repository-only access-diff/export
    fallbacks remain solely in their older DB-free pure-test constructors;
  - raw-audit's unreachable private manager-directory scan and unused repository dependency were
    removed; the four raw-audit surfaces remain SYSTEM_ADMIN-only and their negative matrix is green.
- Completed P1-Q6 article view/history/read-evidence cardinality increment:
  - article-view rows use exact Oracle offset/limit (ceiling 200) while full total and distinct-
    operator values are calculated by DB aggregates for the same optional version filter;
  - history/version and read-receipt source queries request a 1001st sentinel, accept up to 1000
    and return the stable 413 family instead of silently truncating complete evidence;
  - final read-receipt response rows have a second combined-size guard, covering expansion from
    eligible users plus detached snapshot rows;
  - successful response shapes, ordering, permissions, scope and version semantics are unchanged;
    the existing history content CLOB remains until a compatible summary/detail API/UX design exists;
  - the first full regression exposed only the static access-lock naming convention; renaming the
    non-access helpers from `require*` to `enforce*` restored the lock without changing access policy.
- Completed P1-Q7 bounded organization reference snapshot increment:
  - a single deep `OrgDirectoryQueryService` interface requests a 1001st sentinel and accepts at
    most 1000 complete department, team or leadership-assignment rows; overflow is stable 413;
  - admin assignments, `/api/teams`, access-diff, reminders, org-backfill and department-scope
    expansion use this module in Spring production; only explicit DB-free test constructors retain
    repository fallbacks;
  - org structure replaces department-by-department team N+1 reads with one bounded query and
    restricts member-count aggregation to the selected bounded team IDs;
  - assignment hydration loads only referenced user/department/team IDs; backfill org-cardinality
    rejection is transaction-coupled/audited; role/scope/wire/backfill success semantics are unchanged.
- Completed P1-Q8 remaining content plain-array ceiling increment:
  - category, tag, video and news-history queries request a 1001st Oracle sentinel, accept at most
    1000 complete rows and return stable 413 on overflow rather than partial arrays;
  - current Angular single-fetch contracts have no new pagination parameter; successful response
    shapes, video visibility, tag/name order and news-history updated-time order remain unchanged;
  - article/news history content CLOBs remain in their established wire contracts and require a
    backward-compatible summary/detail design before removal.
- Completed P1-Q9 session/personal/compliance/quiz complete-result increment:
  - neutral `CompleteResultGuard` owns the shared 1000/1001 sentinel/fail-loud contract;
    `ListQueryBounds` remains responsible only for HTTP request bounds;
  - active sessions, favorites, active broadcasts, required-readings and quiz question/answer
    queries are Oracle-bounded and return stable 413 rather than partial complete-result arrays;
  - session revoke/expiry/idle filters execute in Oracle before the sentinel; notification recent
    news remains a 10-row query and unread reminders remain an aggregate count;
  - quiz question/answer input ceilings are validated before full-replace deletion; a DB-free
    regression proves oversized input reaches no delete/write/audit interaction;
  - successful response shapes, order, permissions, scope and business flow are unchanged.
- Completed P1-Q10 scheduler batching/article reference increment:
  - export expiry cleanup repeatedly reads 500 ID/path-only rows, deletes legacy files and uses an
    explicit clear/flush bulk-ID delete; export content BLOBs are not materialized;
  - reminder due-soon/overdue discovery processes at most 1000 reading IDs per type/run and each
    assignment-seed snapshot uses the 1000/1001 fail-loud contract before reminder writes;
  - stale and related article endpoints use CLOB-free `ArticleReferenceItem` metadata queries with
    the shared 1000/1001 stable-413 boundary;
  - stale order, related ranking/visibility/missing-source behavior, access and wire shapes are unchanged.
- Completed P1-Q11 scalar aggregate/export/leadership query increment:
  - knowledge score uses one Oracle scalar projection for passed-version and first-try counts instead
    of materializing every grouped attempt row; formula and wire shape are unchanged;
  - readings export requests an ID-ordered 20,001-row sentinel and rejects the existing 20,000-row
    overflow before user/required-reading hydration or rendering;
  - broadcast/compliance/backfill leadership questions use scalar existence queries, while scope's
    required full per-user assignment set uses the org module's 1000/1001 fail-loud snapshot;
  - remaining list callers are classified: permission/status finite/unique and time/enum aggregates
    are bounded; article-target/required-reading relation fan-out, compliance grouped-result size and
    article/news history CLOBs remain explicit internal WS3-05 gaps.
- Completed P1-Q12 item-relation/compliance aggregate increment:
  - article read-receipt's required-reading item/target lookup uses the shared 1000/1001 sentinel and
    stable 413 overflow inside the transaction; no partial receipt/compliance result commits;
  - compliance scope is derived in Java with canonical `DepartmentMatcher` All/exact/prefix semantics,
    de-duplicated to at most three target pairs per bounded user;
  - Oracle 19.3 receives the pairs as CLOB JSON, `JSON_TABLE` returns exactly one required/read aggregate
    row per pair, and `ComplianceCalculator` remains the only percentage/rounding implementation;
  - the old user-by-all-historical-target grouped result and its unbounded Java map were removed;
  - successful API shapes, eligibility, role/scope and business formula remain unchanged; multi-parent
    article-target relation fan-out and article/news history CLOBs remain internal WS3-05 gaps.
- Completed P1-Q13 multi-parent article-target relation increment:
  - `ArticleTargetQueryService` is the only production read boundary for single- and multi-parent
    article audience relations; controller, search query/response and quiz visibility callers use it;
  - one sentinel query bounds the total child relation to 1000 complete rows, and row 1001 fails
    loudly with stable 413 before a partial audience/visibility/ranking map can be produced;
  - repository reads no longer expose an unbounded overload; mutation delete/save behavior is unchanged;
  - successful wire shape, ordering, audience, scope, ranking and quiz visibility semantics are unchanged;
  - the remaining internal WS3-05 source gap is article/news history content CLOB; representative
    memory/p95 proof remains external.
- Completed P1-Q14/Q15 history CLOB compatibility increment:
  - article/news `history-summary` endpoints and article reader `/versions` use CLOB-free metadata
    projections; selected-detail endpoints load one revision CLOB scoped to its owning content ID;
  - Angular admin article history is summary-first/detail-on-expand; diff/restore and legacy full-history
    successful response contracts remain unchanged;
  - legacy full article/news arrays run in a SERIALIZABLE read transaction and Oracle checks a hard
    2,000,000-character aggregate before entity hydration; overflow is stable 413;
  - all four new endpoints are locked to existing `content.manage`/`ORG-CONTENT` in the Access Matrix;
  - a repeatable 4201→8090 E2E proxy file was added; production-default helper startup correctly failed
    closed on the placeholder secret, then the isolated helper ran only under explicit development flags;
  - source-level WS3-05 query/cache/CLOB inventory is closed; representative memory/p95 remains external.
- Completed P0-A7 legacy group-path/department-assignment named-data boundary:
  - assignment-backed legacy department/group paths resolve to exactly one active canonical team;
    missing, inactive, ambiguous and foreign targets are 403 instead of authorization-by-empty-result;
  - valid assigned empty teams keep the existing 200/empty contract, while returned members are filtered
    by the authorized canonical `team_id`, not only by transitional free-text department strings;
  - article read-evidence integration gives the same leader a direct group and department assignment and
    proves the first rollout still returns names only from the direct group; SYSTEM_ADMIN, content-manager
    aggregate, unassigned legacy fallback and export semantics are unchanged;
  - focused source-backed 96/96, broad named-data/access 147/147, DB-free 417/417 and full Oracle 793-test
    regressions are green; WS2 remains `Partial` only because production-like cross-team UAT is external.
- Completed P0-A8 content-history parent-child IDOR negative lock:
  - article selected detail, base diff, explicit compare and restore reject a real history ID owned by
    another article with 404; news selected detail/restore enforce the same parent boundary;
  - rejected restore leaves target title/content/version/history cardinality unchanged and writes no
    success audit; existing roles, permissions, visibility, successful wire and schema are unchanged;
  - focused Article/News 86/86 and broad history/access/shape 109/109 are green;
  - the first full 795-test gate exposed only a reused-schema Stats top-10 fixture assumption. The fixture
    now grows the current leading normalized bucket; corrected Stats 22/22 and final full 795-test suite
    are green with one existing skip.
- Completed P0-A9 SELF-scoped keyed-resource isolation lock:
  - a caller presenting another user's real active session ID receives the established opaque 204 no-op;
    the owner token/session remains active, the intruder's list does not disclose the ID and no intruder
    `REVOKE_SESSION` success audit is written;
  - two users on the same visible article retain separate `(user_id, article_id)` notes and an absent note
    remains the established literal JSON `null`; favorite foreign-owner delete stays 404/non-mutating and
    reminder foreign-owner mark-read stays 404;
  - the first 91-test focused attempt failed only because JSONAssert cannot parse a root JSON null; exact
    response-text assertion corrected the harness. Corrected focused 91/91 and full Oracle 797/797 are green;
  - production source/schema, roles, scope, successful wire and business flow are unchanged.
- Completed P0-A10 compliance/quiz per-user evidence isolation lock:
  - one operator's required-reading acknowledgement creates only that operator's read-status and versioned
    receipt; a second eligible operator remains `unread` and later creates separate caller-owned rows;
  - quiz attempt numbering is independent per `(article, version, user)` and one caller's pass/score does
    not alter the other caller's first attempt or personal knowledge score;
  - the first 31-test focused attempt used `pending` instead of the established `unread` response value;
    Quiz 11/11 and Compliance 19/20 already passed. Corrected focused 31/31 and full Oracle 799/799 are green;
  - production source/schema, roles, scope, retention, successful wire and business flow are unchanged.
- Completed P0-A11 SELF collection-read cross-user disclosure lock:
  - search history, recently-viewed and notification unread-reminder aggregate now have explicit second-caller
    Oracle HTTP assertions; the second caller sees none of the first caller's SELF collection rows/count;
  - existing ordering/dedupe/response shape remains green; focused Article/Search/Platform is 91/91;
  - production source/schema/policy is unchanged; EV-203 covers the focused assertion expansion and
    the later EV-224 802-test run is the current full regression baseline.
- Completed P0-A12 SELF profile/effective-access and directory-owned password lock:
  - two authenticated operators receive only their own explicit effective-access override;
  - current-profile read is caller-bound, and self-profile update changes only the caller's allowed fields
    while preserving caller email/role/department and another user's profile;
  - AD-owned self-password returns the established 403 without password-hash mutation or success audit;
  - focused User suite executed 32 tests with 0 failures/errors and 1 existing skip; production source,
    schema, policy and business flow are unchanged; EV-224 is now the current 802-test full baseline.
- Completed P0-A13 favorite collection and reminder inbox/read ownership lock:
  - two callers favorite the same item into distinct ownership rows and each list sees only its own ID;
  - deleting one caller's favorite preserves the other caller's same-item favorite;
  - assignment reminders are separate per recipient, each inbox exposes only the caller row, foreign read
    stays 404 and one caller's read transition leaves the other reminder unchanged;
  - focused Favorite/Reminder Oracle gate is 10/10; production source/schema/policy/business flow is unchanged.
- Completed P0-A14 read-receipt status and compliance-progress SELF lock:
  - one caller's versioned article receipt remains false for another caller until the latter creates its
    own receipt;
  - shared reading eligibility total remains correct, while completion/pending/percentage derives only
    from the authenticated caller's read-status rows;
  - focused Article/Compliance Oracle gate is 91/91; production source/schema/policy/business flow is unchanged.
- Completed P0-A15 portal-session heartbeat caller isolation lock:
  - valid-CSRF/no-auth heartbeat returns stable JSON 401; authenticated heartbeat touches only the caller's
    token-bound session and preserves another user's active-session timestamp;
  - first attempt was stopped by expected CSRF 403 before auth because the harness omitted CSRF; second
    behaviorally passed but exposed only Oracle timestamp precision rounding in the assertion;
  - corrected Auth Oracle gate is 11/11; production source/schema/logout policy/business flow is unchanged.
- Completed P0-A16 / confirmed D-8 aggregate statistics capability split:
  - `stats.view` is now the dedicated backend permission for the six company-wide aggregate statistics
    endpoints; `content.manage` alone receives 403, an explicit stats-only grant receives 200, and
    SYSTEM_ADMIN bypass remains intact;
  - no non-admin canonical role receives `stats.view` by default, and stats-only access does not open the
    content workspace, raw audit, named user progress or critical-operator surfaces;
  - Angular overview route/navigation/catalog/localization and conditional data loaders follow the same
    split; child content routes retain their own `content.manage` guards;
  - the first backend run exposed only stale Access Contract Matrix bindings, the first supported Angular
    run exposed template block placement, and one compiled full Angular attempt lost a worker. Corrected
    source-contract 12/12, changed Angular surface 33/33, full Angular 96/96, production builds and full
    Java/Oracle 802/802 are green; all failed attempts are retained in EV-215–EV-219.
- Completed P0-A17 async export-job owner IDOR evidence lock:
  - two independently authorized managers now exercise a real completed job over Oracle/HTTP;
  - the foreign caller receives opaque 404 status and 410 download, while the owner remains 200 and the
    job row survives both probes/download until established TTL cleanup;
  - focused export family is 29/29 green; production source, D-3 legacy/classified rules, scope, wire,
    retention and business flow are unchanged, so EV-224 remains the current full production baseline.
- Completed P0-A18 video-view audience/archived object-visibility closure:
  - `POST /api/videos/{id}/view` now applies the same established visibility contract as the video list;
  - non-admin foreign-department and archived real IDs return opaque 404 and leave counts unchanged;
  - content admin retains existing all/archived access and successful increment;
  - focused Video 16 + Access Contract 4 = 20/20 green on Oracle 19.3/Flyway v45;
  - production source changed after EV-224, so full post-A18 Java/Oracle regression and package are explicitly
    not yet verified and are Claude's first implementation verification step.

# Verification evidence

- `java-backend\\mvnw.cmd -DskipTests compile`: `BUILD SUCCESS`; 317 main
  sources compiled. Existing deprecated API warning remains in
  `AdminExportController`.
- `java-backend\\mvnw.cmd -Dtest=ContentMutationAuditServiceTest test`: 2 tests,
  0 failures, 0 errors, 0 skipped.
- First focused Oracle attempt failed during Spring context startup because the
  application exposed no autowire-candidate `ObjectMapper`; the audit writer
  was made self-contained and the full command was rerun.
- Final focused command:
  `java-backend\\mvnw.cmd "-Dtest=ContentMutationAuditServiceTest,ContentMutationAuditTransactionIntegrationTest,CategoryControllerIntegrationTest,ArticleControllerIntegrationTest,NewsControllerIntegrationTest,VideoControllerIntegrationTest" test`
  — 106 tests, 0 failures, 0 errors, 0 skipped.
- Oracle evidence in the final run: Oracle 19.3; 44 Flyway migrations validated;
  schema version 44; no migration required.
- Failure-path evidence: a synthetic 51-character audit action produced real
  `ORA-12899`; the transaction rolled back and the test category did not remain.
- Lifecycle-focused command:
  `java-backend\\mvnw.cmd "-Dtest=ContentMutationAuditServiceTest,ContentMutationAuditTransactionIntegrationTest,ContentLifecycleServiceIntegrationTest,ContentTrashControllerIntegrationTest,ArticleControllerIntegrationTest,NewsControllerIntegrationTest,VideoControllerIntegrationTest" test`
  — 96 tests, 0 failures, 0 errors, 0 skipped.
- Health focused evidence: 5 healthy/compatibility tests plus 1 DOWN-dependency
  test, all green; readiness 503 and liveness 200 were asserted together.
- Identity/access focused evidence: generic writer 3/3, Oracle rollback 1/1,
  user suite 30 green + 1 existing skip, leadership 3/3. The first combined
  run's only failure was a new assertion that assumed repository row order;
  selecting by action fixed it and the rerun passed.
- P0-A3 focused evidence: auth/session 37/37; reminder/export registration
  45/45; async registration assertion 15/15; broad application audit 137/137;
  article/news autosave 76/76; async export outcome 12/12; required-reading and
  quiz submission 32/32.
- WS2 focused access/source/scope baseline: 71/71 green. Deny-default targeted
  final rerun: 23/23; stable JSON 401 focused rerun: 13/13.
- The first full deny-default run had 11 failures out of 661, all from correct
  401 status with an empty body; the first JSON fix used a different message.
  The entry point was aligned to the existing exact contract and rerun green.
- SYSTEM_ADMIN named/raw-data focused matrix: 66 tests, 0 failures/errors/skips.
- First P0-A5 full run: 727 tests, one test error from a nullable-actor upload
  assertion; endpoint authorization/business behavior was not implicated.
- Nullable-actor upload focused rerun: 7 tests, 0 failures/errors/skips.
- Final P0-A5 `java-backend\\mvnw.cmd test`: 727 tests, 0 failures, 0 errors, 1 skipped;
  `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v44.
- P0-A6 focused stats suite: 20/20 green; broad WS2 scope suite: 132/132 green.
- Final P0-A6 `java-backend\\mvnw.cmd test`: 728 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v44.
- P1-Q1 article/news list projection/bounds suite: 78/78 green. Search/cache +
  list suite: 93/93 green; Oracle 19.3/Flyway v44.
- Final P1-Q1 `java-backend\\mvnw.cmd test`: 733 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; total time 02:39.
- P0-R1 final focused state-machine/access-contract suite: 9/9 green; broad audit,
  deletion and SYSTEM_ADMIN suite: 77/77 green.
- Final P0-R1 `java-backend\\mvnw.cmd test`: 736 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3/Flyway v44; total time 02:38.
- First P1-Q2 run retained as negative evidence: `ORA-25006` rejected the LOB-specific
  trigger declaration; subsequent Flyway repair changed failed-history metadata only.
- Corrected P1-Q2 migration/projection suite: 81/81 green, including v44→v45 recovery,
  exact 450-word read-time, response compatibility and direct-writer drift correction.
- Final P1-Q2 `java-backend\\mvnw.cmd test`: 739 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3/Flyway v45; total time 02:43.
- P1-Q3 first focused gate: 98 tests, 0 failures/errors, 1 existing skip; final
  directory/progress/access focused gate: 124 tests, 0 failures/errors, 1 existing skip.
- Final P1-Q3 `java-backend\\mvnw.cmd test`: 741 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3/Flyway v45; total time 02:34.
- P1-Q4 final directory/org/compliance/stats/error focused gate: 143 tests,
  0 failures/errors/skips; boundary, transaction and existing scope contracts passed.
- Final P1-Q4 `java-backend\\mvnw.cmd test`: 748 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3/Flyway v45; total time 02:44.
- `java-backend\\mvnw.cmd -DskipTests package`: 324 main and 112 test sources,
  48 main-resource files; executable Spring Boot JAR produced; `BUILD SUCCESS`; 8.066 s.
- First P1-Q5 DB-free gate: 24 tests, 2 fixture-only failures because the new
  published Article fixture retained default `isDraft=true`; corrected rerun 24/24 green.
- First P1-Q5 Oracle/Spring gate: 197 tests, 0 failures/errors/skips; final expanded
  user-fan-out/audit/access gate: 269 tests, 0 failures/errors, 1 existing skip.
- Final P1-Q5 `java-backend\\mvnw.cmd test`: 754 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3/Flyway v45; total time 02:36.
- P1-Q5 `java-backend\\mvnw.cmd -DskipTests package`: 324 main and 114 test sources,
  48 main-resource files; executable Spring Boot JAR produced; `BUILD SUCCESS`; 11.271 s.
- P1-Q6 corrected view gate: 147/147 green; broad evidence/access/shape gate: 155/155
  green; final article-specific gate: 67/67 green.
- First P1-Q6 full regression: 757 tests, one static `AccessContractCoverageTest` failure
  because non-access `require*` helpers matched the access-gate naming convention; functional
  suites had no failure. Corrected access-lock/cardinality rerun: 6/6 green.
- Final P1-Q6 `java-backend\\mvnw.cmd test`: 757 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3/Flyway v45; total time 02:59.
- P1-Q6 `java-backend\\mvnw.cmd -DskipTests package`: 326 main and 115 test sources,
  48 main-resource files; executable Spring Boot JAR produced; `BUILD SUCCESS`; 14.491 s.
- P1-Q7 production compile: 327 main sources, `BUILD SUCCESS`, 15.358 s; DB-free module/
  access/backfill/reminder gate 53/53 green; Oracle/Spring org/access gate 132 tests,
  0 failures/errors and 1 existing skip.
- Final P1-Q7 `java-backend\\mvnw.cmd test`: 763 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3/Flyway v45; total time 03:14.
- P1-Q7 `java-backend\\mvnw.cmd -DskipTests package`: 327 main and 116 test sources,
  48 main-resource files; executable Spring Boot JAR produced; `BUILD SUCCESS`; 14.868 s.
- P1-Q8 category/tag/video/news-history focused Oracle/access/shape gate: 75/75 green.
- Final P1-Q8 `java-backend\\mvnw.cmd test`: 766 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3/Flyway v45; total time 03:10.
- P1-Q9 initial test compile retained as negative evidence: 8 test-only calls used the removed
  unbounded reminder overload; explicit bounded `PageRequest` calls corrected them. Corrected
  compile gate: 328 main/117 test sources, `BUILD SUCCESS`.
- P1-Q9 DB-free contract/access/destructive-order gate: 32/32 green; Oracle-backed session/
  favorite/broadcast/compliance/notification/quiz/reminder HTTP gate: 63/63 green.
- Final P1-Q9 `java-backend\\mvnw.cmd test`: 767 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3/Flyway v45; total time 03:19.
- P1-Q10 background scheduler/cardinality gate: 19/19 green; article reference Oracle/access/
  response gate: 87/87 green.
- First BLOB-free export cleanup gate: 12 tests, 1 same-transaction persistence-context failure
  after inherited bulk-ID deletion. Explicit clear/flush bulk-ID DML corrected it without loading
  BLOBs; corrected export row/file cleanup gate: 12/12 green.
- Final P1-Q10 `java-backend\\mvnw.cmd test`: 770 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3/Flyway v45; total time 03:10.
- P1-Q11 focused gates: quiz/knowledge Oracle 10/10; export DB-free 11/11 and Oracle
  11/11; org/policy/backfill DB-free 34/34 and broadcast Oracle 9/9.
- P1-Q11 full DB-free suite: 406 tests, 0 failures/errors/skips; `BUILD SUCCESS`.
- Final P1-Q11 `java-backend\\mvnw.cmd test`: 774 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3/Flyway v45; total time 03:19.
- P1-Q12 focused evidence: compliance scope/formula unit 4/4; corrected Oracle CLOB/JSON_TABLE
  aggregate 2/2; item-relation sentinel HTTP/Oracle 1/1; Stats/User/aggregate 55 tests with
  0 failures/errors and 1 existing skip. The first aggregate Oracle attempt failed at context
  wiring because no `ObjectMapper` bean existed; the repository was made self-contained and rerun green.
- P1-Q12 raw report-directory totals were DB-free 414/full 786. P1-Q13's source/timestamp
  audit found three removed-class stale XML reports contributing five tests; corrected Q12
  source-backed totals are DB-free 410/full 781. The green verdict is unchanged (EV-161).
- P1-Q13 query/cardinality unit gate: 4/4; multi-parent Oracle HTTP overflow: 1/1;
  broad Article/Search/Quiz Oracle regression: 90/90, all green.
- P1-Q13 timestamp-bound DB-free suite: 413 tests, 0 failures/errors/skips; exit 0.
- Final timestamp-bound P1-Q13 `java-backend\\mvnw.cmd test`: 785 tests, 0 failures,
  0 errors, 1 skipped; Oracle 19.3/Flyway v45. Raw 790 was not used because it included
  the five stale tests described above.
- P1-Q13 `java-backend\\mvnw.cmd -DskipTests package`: 334 main and 123 test sources,
  48 main-resource files; executable Spring Boot JAR produced; `BUILD SUCCESS`; 93,354,634 bytes.
- P1-Q14 focused Angular summary/detail 3/3 and Java/Oracle summary/detail 3/3; first broad
  access gate found four missing matrix rows (2/103 failures), corrected broad gate 103/103 and
  explicit content-manage/access lock 5/5 green.
- P1-Q15 payload guard/oversized Article+News Oracle gate 4/4; final broad Java history/access
  regression 107/107 green.
- P0-A7 focused source-backed gate: 96/96; broad named-data/access gate: 147/147.
- P0-A8 focused parent-child history-IDOR gate: 86/86; broad access/history gate: 109/109.
- First P0-A8 full gate retained as negative evidence: 795 tests, one Stats fixture failure caused by
  equal-frequency persistent Oracle rows outside bounded top-10; all new history tests passed.
- Corrected Stats gate: 22/22 green.
- P0-A9 focused SELF keyed-resource gate: first 91-test attempt had one assertion-adapter error on a
  correct literal JSON `null`; corrected rerun 91/91 green (Article 71, Auth 10, Favorite 6, Reminder 4).
- P0-A10 focused compliance/quiz gate: first 31-test attempt had one expected-label assertion mismatch;
  corrected rerun 31/31 green (Compliance 20, Quiz 11).
- P0-A11 focused SELF collection-read gate: Article 71 + Search 13 + Platform 7 = 91/91 green;
  no production source changed in that batch; EV-224 is now the current 802-test full baseline.
- P0-A12 focused User SELF profile/effective-access/password gate: 32 tests, 0 failures/errors,
  1 existing skip; `BUILD SUCCESS`; no production source changed.
- P0-A13 focused Favorite/Reminder SELF ownership gate: 10/10 green; `BUILD SUCCESS`;
  no production source changed.
- P0-A14 focused Article/Compliance SELF receipt-status/progress gate: 91/91 green;
  `BUILD SUCCESS`; no production source changed.
- P0-A15 Auth/session heartbeat gate: first two harness-only failures retained; corrected 11/11 green;
  `BUILD SUCCESS`; no production source changed.
- D-8 first Java gate: 68 tests executed; all functional permission/gate suites passed and the sole
  failure was stale Access Contract Matrix text. Corrected source/matrix gate: 12/12 green.
- D-8 Angular: changed-surface tests 33/33 across five files; final full suite 27 files / 96 tests green.
  Unsupported `--run`, template-balance failure and one pre-result Vitest worker exit are retained as
  tooling/correction evidence; none remains open.
- D-8 Angular production build: success; initial bundle 370.17 kB (estimated transfer 73.70 kB).
- P0-A17 export owner/status/download Oracle gate: 29/29 green; `BUILD SUCCESS`; test-only increment.
- P0-A18 focused video/access gate: 20/20 green; `BUILD SUCCESS`; full post-A18 regression/package pending.
- Current DB-free suite: timestamp-bound 417 tests, 0 failures/errors/skips; exit 0.
- Latest full Java/Oracle suite (pre-A18): 802 tests, 0 failures, 0 errors, 1 existing skip;
  Oracle 19.3/Flyway v45; `BUILD SUCCESS`; total time 05:37 min. Post-A18 focused gate is 20/20.
- Latest package (pre-A18): 339 main and 124 test sources, 48 resources; executable JAR 93,364,931 bytes;
  `BUILD SUCCESS`; 56.079 s. A post-A18 package has not been built.
- Current Angular 22.1: 27 files / 96 tests green; production build green; prior npm audit 0
  with dependencies unchanged.
- Current Chromium: first full run 44/45; both history flows passed. The only failure was a
  department fixture sorted below the first 40 visible cards in an accumulated local DB; unique-run-id
  search isolated the same allow/deny scenario and corrected rerun passed 1/1.
- Earlier Angular 25-file/88-test and Chromium 45/45 totals remain historical snapshots;
  the current P1-Q14 figures are recorded immediately above.
- `node --check scripts/load/k6-java-backend.js`: pass. k6 is not installed,
  so no load result exists.
- Current `git diff --check` exit code: 0; no whitespace errors.
- Repository facts are current through EV-229; current pre-A18 backend package evidence is EV-226.
  Chromium evidence remains historical/current for its unchanged flows; D-8 route/capability behavior is
  covered by current Angular unit tests and has not been rerun in a browser. Older handoff totals remain
  historical snapshots only.

# Uncommitted or untracked work

- All existing broad Angular/Java/Flyway/E2E/docs/research changes remain intact.
- This cycle newly modified tracked controllers/tests:
  - `ArticleController`, `CategoryController`, `NewsController`, `VideoController`;
  - their four integration test classes.
- The lifecycle increment additionally modified tracked
  `ContentLifecycleService` and `ContentLifecycleServiceIntegrationTest`.
- The identity/access increment additionally modified tracked
  `LeadershipAssignmentService`, `UserController`,
  `OrgAdminControllerIntegrationTest` and `UserControllerIntegrationTest`.
- P0-A3 additionally modifies authentication/session, broadcast, compliance,
  quiz, reminder, export, upload/file access, policy diagnostics and autosave
  controllers/services plus their focused tests. `ExportJobWorker` now records
  sanitized asynchronous outcome evidence transactionally.
- P0-A4 additionally modifies tracked `SecurityConfig` and adds untracked
  `java-backend/src/test/java/ge/magti/portal/security/SecurityConfigIntegrationTest.java`.
- P0-A5 adds untracked
  `java-backend/src/test/java/ge/magti/portal/security/SystemAdminPiiSurfaceIntegrationTest.java`
  and makes the tracked upload integration test's audit selectors nullable-actor-safe.
- P0-A6 additionally modifies tracked `StatsController` and
  `StatsControllerIntegrationTest` to enforce and prove the foreign-team selector boundary.
- P1-Q1 additionally modifies article/news/search controllers, query services and
  repositories plus their integration tests. It adds untracked `NewsListItem`,
  `ListQueryBounds` and `GlobalSearchCacheTest`.
- P0-R1 additionally modifies `ContentLifecycleService`, `ContentTrashController`,
  `ContentTrashControllerIntegrationTest`, `application.yml` and the access contract;
  it adds untracked `LegalHoldAuthority` and `LegalHoldAuthorityTest`.
- P1-Q2 additionally modifies `Article`, `ArticleController`, `ArticleQueryService`,
  `ArticleSummaryResponse`, `ArticleControllerIntegrationTest` and
  `ArticleSummaryResponseTest`; it adds untracked `ArticleListItem`,
  `V45__article_list_read_time_projection.sql` and `V45MigrationShapeTest`.
- P1-Q3 additionally modifies `ComplianceQueryService`, `UserController`,
  `StatsController` and their two Oracle integration tests; it adds untracked
  `java-backend/src/main/java/ge/magti/portal/user/UserDirectoryQueryService.java`.
- P1-Q4 additionally modifies `OrgBackfillService`, `PolicyDiagnosticsController`,
  `UserRepository`, `GlobalExceptionHandler`, `ComplianceQueryService`, `StatsController`
  and focused tests; it adds untracked `OrgBackfillServiceTest` and
  `UserDirectoryQueryServiceTest`.
- P1-Q5 additionally modifies `UserDirectoryQueryService`, `AccessDiffService`,
  `ReminderService`, `EligibleOperatorsService`, `ExportQueryService`,
  `ComplianceQueryService`, `UserController`, `AuditLogController`, `UserRepository` and
  focused tests; it adds untracked `EligibleOperatorsServiceTest` and
  `ReminderServiceCardinalityTest`.
- P1-Q6 additionally modifies `ArticleController`, `ArticleHistoryRepository`,
  `ArticleReadReceiptRepository`, `ArticleControllerIntegrationTest`, `GlobalExceptionHandler`
  and `GlobalExceptionHandlerTest`; it adds untracked `ArticleViewQueryService`,
  `ArticleEvidenceCardinalityGuard` and `ArticleEvidenceCardinalityGuardTest`.
- P1-Q7 additionally modifies department/team/leadership/user repositories,
  `OrgAdminController`, `UserController`, `AccessDiffService`, `ScopeResolver`,
  `ReminderService`, `OrgBackfillService`, `PolicyDiagnosticsController`,
  `GlobalExceptionHandler` and focused tests; it adds untracked `OrgDirectoryQueryService`
  and `OrgDirectoryQueryServiceTest`.
- P1-Q8 additionally modifies category/tag/video/news-history repositories, their controllers,
  `GlobalExceptionHandler`, `NewsControllerIntegrationTest` and focused tests. Its generic
  complete-result helper/test were moved in P1-Q9 to the neutral query module.
- P1-Q9 additionally modifies portal-session/favorite/broadcast/required-reading/quiz/reminder
  repositories and their services/controllers/tests; it adds untracked `CompleteResultGuard`,
  `CompleteResultGuardTest` and `QuizControllerCardinalityTest`. `ListQueryBounds` now contains
  request-bound validation only.
- P1-Q10 additionally modifies export/reminder/article repositories, schedulers/services,
  `ArticleController` and focused tests; it adds untracked `ExpiredExportJobReference`,
  `ArticleReferenceItem`, `ExportJobCleanupSchedulerTest` and `ReminderSweepServiceTest`.
- P1-Q11 additionally modifies quiz/read-status/leadership repositories, knowledge-score/export/
  scope/compliance/broadcast/backfill services and focused tests; it adds untracked
  `java-backend/src/main/java/ge/magti/portal/repository/PassedQuizSummary.java`.
- P1-Q12 additionally modifies required-reading/read-status repositories, `ArticleController`,
  `ComplianceQueryService`, `UserController` and the article integration test; it adds untracked
  `ComplianceAggregateRepository`, `ComplianceProgressQueryService` and their unit/Oracle tests.
- P1-Q13 additionally modifies the article-target repository, `ArticleController`, search query/
  response controllers, `QuizController` and focused tests; it adds untracked
  `ArticleTargetQueryService` and `ArticleTargetQueryServiceTest`.
- P1-Q14/Q15 additionally modifies article/news history repositories/controllers/integration tests,
  `GlobalExceptionHandler`, Access Contract Matrix, Angular article-history model/service/modal and
  department-visibility E2E; it adds untracked history summary projection/response records,
  `HistoryPayloadGuard` + test, Angular modal spec and `proxy.e2e.conf.json`.
- P0-A8 additionally modifies the existing Article/News integration tests with real foreign-parent
  history IDs and hardens the existing Stats integration fixture against reused-schema top-10 ties;
  production code/schema is unchanged.
- P0-A9 additionally modifies the existing Auth/Article integration tests with foreign-session and
  same-article cross-user note isolation evidence. Favorite/Reminder ownership tests were rerun unchanged;
  production code/schema is unchanged.
- P0-A10 additionally modifies the existing Compliance/Quiz integration tests with two-caller
  read-status/receipt and attempt/knowledge-score isolation evidence; production code/schema is unchanged.
- P0-A11 additionally strengthens the existing Article/Search/Platform integration tests with
  second-caller collection-read isolation assertions; production code/schema is unchanged.
- P0-A12 additionally strengthens the existing User integration test with two-caller effective-access,
  profile-read/update isolation and directory-owned password non-mutation assertions; production
  code/schema is unchanged.
- P0-A13 additionally strengthens the existing Favorite/Reminder integration tests with two-caller
  collection, same-item mutation and per-recipient read-state isolation assertions; production
  code/schema is unchanged.
- P0-A14 additionally strengthens the existing Article/Compliance integration tests with two-caller
  receipt-status and caller-derived progress aggregate assertions; production code/schema is unchanged.
- P0-A15 additionally adds an Auth integration test for valid-CSRF/no-auth and two-session heartbeat
  isolation; production code/schema/logout policy is unchanged.
- P0-A16/D-8 additionally modifies `Permission`, `StatsController`, the permission/content/stats backend
  tests, Angular admin routes/guards/navigation/permission catalog/localization/admin-stats page and their
  specs, plus the Access Contract Matrix. It adds untracked
  `angular-frontend/src/app/features/admin-stats/admin-stats-page.spec.ts`.
- P0-A17 additionally strengthens the existing `ExportControllerIntegrationTest` with two-authorized-manager
  status/download owner isolation; production source/schema is unchanged.
- P0-A18 additionally modifies `VideoController`, `VideoControllerIntegrationTest` and the Access Contract
  Matrix to make the keyed view mutation obey existing list visibility; no schema/role/product-policy change.
- This cycle added untracked Java files:
  - `java-backend/src/main/java/ge/magti/portal/audit/MutationAuditService.java`;
  - `java-backend/src/main/java/ge/magti/portal/compliance/RequiredReadingMutationService.java`;
  - `java-backend/src/main/java/ge/magti/portal/export/LegacyExportJobService.java`;
  - `java-backend/src/test/java/ge/magti/portal/audit/MutationAuditServiceTest.java`;
  - `java-backend/src/test/java/ge/magti/portal/audit/MutationAuditTransactionIntegrationTest.java`;
  - `java-backend/src/test/java/ge/magti/portal/export/ExportJobWorkerTest.java`;
  - `java-backend/src/test/java/ge/magti/portal/web/HealthControllerTest.java`;
  - `java-backend/src/test/java/ge/magti/portal/web/HealthEndpointIntegrationTest.java`;
  - `java-backend/src/test/java/ge/magti/portal/web/HealthEndpointDownIntegrationTest.java`.
- The five readiness artifacts and this handoff are untracked.
- `graphify-out/` and `it-questionnaire-site/` remain unrelated/user-owned and
  were not edited.
- Before any overlapping edit, inspect the current file and its diff; Article,
  News and their tests already contained user changes before P0-A.

# Open issues and risks

- Application mutation audit reconciliation is complete and the source writer is
  centralized. WS1 remains `Partial` because archive/continuity operations,
  deleted-directory identity resolution and operational evidence remain open;
  raw audit-view meta events are deliberately best-effort.
- Attachment access remains `AUTH` per confirmed PO-02. Enforcing owning-content
  audience would change scope and is paused at `DEC-P01`; no migration was guessed.
- Anonymous idempotent logout remains the current recovery behavior, but conflicts
  with the matrix target `AUTH/SELF`; changing either security/business flow is
  paused at `DEC-P02`.
- All three non-admin roles are now explicitly denied on 22 SYSTEM_ADMIN-only
  named/raw-data surfaces; manager team-stats rejects foreign group IDs; assignment-backed
  legacy paths reject missing/ambiguous/foreign targets and filter exact canonical team members;
  department assignment cannot widen first-rollout named read evidence. WS2 stays `Partial`
  until production-like cross-team UAT is signed.
- The confirmed D-8 split is implemented: `stats.view` independently gates six company-wide aggregates,
  is not defaulted to non-admin roles and does not widen content/audit/named-user UI or backend access.
  Production-like four-role UAT is still external, so WS2 remains `Partial`.
- Async export job status/download now has real-Oracle cross-manager owner evidence; foreign IDs remain
  opaque and the owner row/flow survives. Production-like export UAT and signed security evidence remain open.
- Video view keyed mutation no longer bypasses department/archived visibility. Focused evidence is green,
  but full post-A18 Java/Oracle regression and executable package have not yet been rerun.
- Article/news history selected detail/diff/compare/restore now have explicit cross-parent IDOR
  regressions; foreign-parent IDs are 404 and rejected restore is non-mutating/non-auditing.
- Health code gate passes, but deployment probe wiring, monitoring and alerting
  remain external operational evidence.
- k6 hard thresholds exist but k6 is not installed and no approved staging run
  exists; performance remains `Partial`.
- Public article/news/search cardinality and global-search cache have hard ceilings;
  neither public list fetches its content CLOB, exact article read-time is database-owned,
  and admin user-directory/all-user progress page at Oracle. Complete-result org-backfill,
  org-wide compliance and active-user statistics fail loudly above 1000 instead of returning
  partial results. Remaining complete-result user fan-outs now use the same bounded family;
  article views now use DB paging/aggregation and history/version/read-receipt evidence fails
  loudly above 1000. Organization department/team/assignment snapshots now share a bounded module
  and org-structure N+1 hydration is removed. Category/tag/video/news-history, active-session,
  favorite, active-broadcast, required-reading and quiz arrays now fail loudly above 1000;
  notification recent-news is bounded to ten and unread reminders use an aggregate count.
  Stale/related article paths now use bounded CLOB-free metadata; export cleanup uses BLOB-free
  500-row batches; scheduled reminder discovery and seed snapshots are bounded. Knowledge score is
  scalar, readings export rejects at a DB sentinel before enrichment, and leadership reads are
  scalar or bounded by caller need. Item-level required-reading relations now use the shared
  1000/1001 sentinel and compliance aggregate output is limited to three canonical target pairs per
  bounded user. Multi-parent article-target reads now use one total-child 1000/1001 seam across
  article/search/quiz callers. History lists are CLOB-free for first-party clients, selected detail
  is one-row and compatibility full arrays are character-budgeted. Representative memory/p95 evidence
  remains external, so WS3-05 is `Partial`.
- Legal-hold code gate is implemented and fail-closed, but its exact named SSO
  authorities, purge approval and total retention remain `EXT-004`. The source default
  is intentionally empty; do not populate it without DPO/Legal and operations approval.
- Full current Java/Oracle, Angular unit/build/audit and Chromium Playwright are
  green. Non-Chromium, staging and business UAT evidence remain open.
- Real SSO/AD, SAST/SCA/DAST/pentest, monitoring/on-call, retention approval,
  actual Oracle restore, staging rollback, manual WCAG and UAT remain external
  evidence gates.
- Production readiness must not be claimed while any listed P0/External gate is
  open.

# Next actions

1. First run `Set-Location java-backend; .\mvnw.cmd test` against the current post-P0-A18 source; if green,
   run `.\mvnw.cmd -DskipTests package`, record exact totals/times/JAR size in EV ledger/report/handoff.
2. Then continue WS2 P0 endpoint negative/IDOR mapping with the next independent SELF write/resource family
   after P0-A18, excluding attachment policy and logout until `DEC-P01`/`DEC-P02` are explicitly resolved.
3. Resolve `DEC-P01`: retain any-authenticated file access or enforce owning-content
   audience with an explicit temporary uploader rule.
4. After the decision, implement P0-B binding/enforcement and authorized,
   unauthenticated, cross-department/IDOR and access-audit tests.
5. Resolve `DEC-P02`: keep anonymous idempotent logout or require authentication
   with an approved expired-token frontend cleanup flow.
6. Obtain `EXT-004` DPO/Legal approval before configuring any legal-hold authority;
   keep the default-empty fail-closed behavior until then.
7. Execute hard k6/memory gate only on approved production-like non-production staging and
   attach the result; a local run is not production evidence.
8. Continue independent internal work while external ledgers remain pending.

# Do not do without authorization

- Do not reset, checkout, discard, clean, move or overwrite the current dirty
  worktree.
- Do not commit, push, open a PR or change branches.
- Do not deploy or mutate staging/production/external systems.
- Do not run destructive database operations or purge production/user data.
- Do not add production local/offline/password login fallback.
- Do not change confirmed role/scope/export/evidence/audit visibility/retention
  or business-flow semantics without the required product/IT/DPO decision.
- Do not record secrets, tokens, credentials or personal data in docs/logs.
- Do not claim production readiness from code/tests alone; operational and
  external gates require real evidence.
