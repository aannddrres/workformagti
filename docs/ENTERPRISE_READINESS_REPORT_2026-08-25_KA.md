# Magti Portal Enterprise Readiness Report — 2026-08-25

განახლებულია: 2026-08-27

## Outcome

**NOT READY — Pilot/Production GO აკრძალულია.**

Current codebase-ს მნიშვნელოვანი ფუნქციური და უსაფრთხოების hardening უკვე აქვს, მაგრამ
GO/NO-GO blocker-ები რჩება: რეალური SSO/AD, attachment object entitlement, audit-ის
operational archive/continuity evidence, operational probe/monitoring wiring, measured performance,
signed security evidence, approved retention/legal hold, actual Oracle restore,
deployment/rollback და ოთხ-როლიანი UAT.

## Verified repository state

- worktree: `C:/Projects/Magti base`;
- branch: `codex/readiness-report-2026-08-23`;
- HEAD: `dd775556b699de063fe1dd2bad0dd589e50a6619` (`docs: add production readiness report`);
- upstream: `origin/codex/readiness-report-2026-08-23`;
- ahead/behind: `0/0`;
- initial default short status: 163 entries = 120 modified, 1 deleted, 42 collapsed untracked entries;
- expanded untracked status: 517 entries = 120 modified, 1 deleted, 396 untracked files;
- current default short status: 304 entries = 207 modified, 1 deleted, 96 collapsed untracked entries;
- current expanded status: 661 entries = 207 modified, 1 deleted, 453 untracked files;
- tracked deletion: `angular-frontend/src/app/core/auth/auth.interceptor.ts`;
- no reset/clean/checkout/commit/push/PR/branch switch/deployment was performed.

Dirty and untracked work predates this cycle and remains user-owned. In particular,
`graphify-out/` and `it-questionnaire-site/` were not modified by this batch.

## Current readiness summary

| Area | Status | Assessment |
|---|---|---|
| Re-baseline/program controls | Partial | Current Git facts/artifacts exist; post-A18 focused gate is green, but full Java/package still needs rerun |
| Audit/evidence completeness | Partial | Application mutation matrix is centralized and verified; audit archive/continuity and operational proof remain open |
| Roles/scope/file entitlement | Partial | Global authentication is deny-by-default, 22 SYSTEM_ADMIN-only surfaces reject all non-admin roles, acting export is fail-closed, manager team-stats/legacy group paths reject foreign targets, department assignment does not widen first-rollout named evidence, article/news history child IDs cannot cross parent resources, and SELF keyed/collection/profile/effective-access families are caller-bound; attachment audience and staging UAT remain open |
| Health/load/performance | Partial | probe semantics and hard script gates implemented; production-like k6/monitoring evidence missing |
| Security assurance | Partial/External | multiple code controls exist; current complete scans/pentest/rotation evidence missing |
| Retention/recovery | Partial/External | legal-hold code gate is fail-closed and audited; authority/purge/retention approval and actual Oracle restore remain external |
| Accessibility/UAT | Partial/External | automated work exists; manual evidence and business sign-off missing |
| SSO/staging/operations | External | IAM/infrastructure evidence and drills missing |

## Implemented P0-A: content mutation audit vertical slice

Added one transaction-critical audit writer for article/news/video/category CRUD
and the article/news/video lifecycle.
New evidence records contain:

- actor ID, name and email snapshot;
- item type, ID and display-name snapshot;
- action, timestamp and classified audit category;
- schema-versioned JSON with result, reason, before and after snapshots;
- version and audience/scope fields where the entity owns them;
- no full article/news body, credential, token or secret.

`saveAndFlush` is deliberate: audit constraint/trigger/chain failures surface before
the endpoint returns and mark the same transaction for rollback. Article/news/video
create/update/archive/unarchive/version-restore, category create/update/delete and
content trash/restore/purge use this boundary.

The lifecycle increment also closed a concrete transaction gap: Video archive and
unarchive previously had no controller transaction, so the content state and explicit
audit repository write could commit independently. Both endpoints are now transactional,
flush the video state, and write schema-versioned audit evidence before completion.
Article/News single and bulk archive paths plus version restore use the same contract.
Trash/restore/purge evidence records preserve title/version/lifecycle state/legal-hold
and attachment-reference count without storing content bodies or filenames.

## Implemented P0-A2: identity/access administration audit increment

The content-specific writer was generalized into one transaction-critical
`MutationAuditService`. Existing content callers preserve the same contract, while these
previously missing or legacy identity/organization paths now use it:

- authenticated self-profile update;
- system-admin profile/department/team/role update;
- user activation/deactivation;
- bulk role reassignment;
- per-user permission override update, including stable sorted before/after states;
- leadership assignment create and deactivate.

Every successful row carries actor and target snapshots plus schema version, result,
nullable reason and before/after state. User details include security-relevant role,
active, department/team/manager, position, compliance, card style, optimistic-lock
version and only `phone_present`; target email, phone value, password hash and token
version are deliberately excluded from JSON details. Business state and audit evidence
are flushed within the same transaction. Existing last-admin, self-demotion,
optimistic-lock, role/scope and permission semantics were not changed.

Directory-owned user creation, team creation and password mutation endpoints remain
fail-closed; no local/password fallback or alternative business flow was introduced.

## Implemented P0-A3: remaining application audit surface

All direct production writes to `AuditLogRepository` were reconciled into the shared
transaction-critical `MutationAuditService`. The normalized application matrix now
covers authentication success/failure/logout, owned-session revocation, broadcasts,
required-reading administration and read acknowledgement, quiz administration and
attempt submission, reminders, export request/registration and asynchronous outcome,
upload/file access, article verification, policy backfill and article/news autosave.

Audit payloads are deliberately bounded: unknown login identifiers, credentials,
tokens, content/message bodies, quiz answers, files and exception strings are not
persisted. Export completion/failure records stable job metadata and status only;
employee evidence records reading/quiz result metadata without answer content. Business
mutations and their audit evidence share the same transaction, including async export
job outcome. Raw audit-trail access meta-events retain their pre-existing best-effort
contract because they do not mutate business state.

## Implemented P0-A4: global deny-by-default authentication

Spring Security no longer leaves all routes at `permitAll`. The explicit public
allowlist contains login/SSO bootstrap, the current idempotent logout compatibility
path and aggregate health probes; every other request requires a live authenticated
identity before MVC routing. Controller/service role, permission and scope gates remain
the second boundary. Anonymous requests receive the existing stable JSON 401 shape.

The first focused run exposed a real contract conflict: access documentation targets
logout as `AUTH/SELF`, while the current controller and browser recovery flow intentionally
allow anonymous idempotent cookie cleanup after token expiry. That behavior was preserved,
recorded as `DEC-P02`, and not silently redefined. Access/source/scope focused tests,
the full Java/Oracle suite and the four-persona browser suite are green.

## Implemented P0-A5: SYSTEM_ADMIN named/raw-data negative matrix

A real-JWT/Oracle parameterized suite now proves the SYSTEM_ADMIN boundary across 22
named/raw employee-data and administrative evidence surfaces for each non-admin canonical
role: OPERATOR, MANAGER and CONTENT_ADMIN. The 66 combinations cover access-diff,
article-view evidence, audit list/export/verify/chain health, six administrative exports,
organization structure/assignments, policy diagnostics/backfill, all-user/team statistics,
group leaders and the user directory. Every request receives 403 with a stable non-empty
detail before the protected query, export or mutation can run.

The first full run also exposed an order-dependent test defect: upload audit verification
called `equals` on nullable `audit_logs.admin_id` while scanning the shared ledger. Both
upload/file-access predicates now compare from the known non-null expected actor ID. This
changes test robustness only; the nullable audit schema, authorization and business behavior
are unchanged. The focused upload rerun and final full regression are green.

## Implemented P0-A6: manager team-stats foreign-IDOR closure

`GET /api/manager/team-stats` previously applied legacy department-string visibility
before filtering on a caller-controlled `team_id`. Two different teams can share the same
department text, so a manager with one active canonical team could request a sibling team's
ID and receive its named member statistics. The endpoint now follows the same assignment-
backed selector rule already used by department stats and critical operators: when active
leadership options exist, the default is the primary team; any explicitly selected team
must belong to `resolveGroupLeadership`, otherwise the request fails with 403. Own-team
access remains 200, acting interactive scope remains permitted by the existing resolver,
unassigned legacy fallback is preserved, and SYSTEM_ADMIN remains unscoped.

## Implemented P1-Q1: bounded public list/search/cache and news CLOB projection

`GET /api/articles` and `GET /api/news` now reject negative offsets, zero/negative
limits and limits above 1000 with a stable 400 response. The 1000 ceiling is deliberate:
the current Angular admin/knowledge views already request exactly that value, so the
guard removes caller-controlled unbounded cardinality without breaking the confirmed
client flow. The news list query now constructs a summary projection and never selects
`News.content`; global search retains its full-entity mapper separately.

Search requests above 200 characters are rejected before database/cache work. Both
short-query fallback scans and trigram candidate queries have a deterministic newest-first
1000-row work-set ceiling; the KB response is capped at 1000 while global search retains
its existing 8/5/5 response limits. The 60-second global-search cache normalizes equivalent
keys, removes expired entries and evicts the oldest entry at 512 keys. Existing ranking,
visibility and two-character search behavior remains covered. Article-list CLOB removal is
covered by P1-Q2 below; WS3-05 remains `Partial` because several all-user administrative/
analytics operations still need paging/aggregation work and representative memory evidence.

## Implemented P0-R1: legal-hold fail-closed state machine

Two explicit content-trash endpoints now set and release legal hold. Authority is not
inferred from SYSTEM_ADMIN, CONTENT_ADMIN or any other application role: an exact active
SSO email allowlist must be supplied through external configuration, and its default is
empty. Consequently every role is denied until DPO/Legal approves and operations supplies
the named authority identities; no identity is stored in source or documentation.

The service repeats the authority check so internal callers cannot bypass the HTTP gate.
State changes write transaction-coupled `SET_LEGAL_HOLD`/`RELEASE_LEGAL_HOLD` evidence
with before/after lifecycle snapshots. Trash preserves an existing hold; restore cannot
silently clear it; held restore and purge return 423. Hold set/release and purge serialize
on the Oracle content row before purge touches dependent references, while the final purge
delete retains a second `legal_hold = 0` condition. The implementation activates no
automatic purge and does not decide the pending DPO/Legal policy.

## Implemented P1-Q2: article-list CLOB-free exact read-time projection

`GET /api/articles` now uses an `ArticleListItem` constructor projection that excludes
`Article.content`. Exact legacy word-based read-time is preserved through a derived Oracle
`articles.read_time` scalar: V45 backfills existing rows, then a database trigger recomputes
the value from content on every insert or update. The application mapping is read-only, so
client or ORM input cannot make the derived value authoritative. Detail and global-search
full-entity paths retain their existing response behavior.

The first Oracle 19.3 attempt exposed `ORA-25006`: a LOB cannot appear in an `UPDATE OF`
trigger column list. Because Oracle DDL auto-commits, the added column/backfill already
existed after the failed migration. Recovery was deliberately non-destructive: V45 gained
an idempotent column guard and an all-row insert/update trigger, and Flyway `repair` removed
only the failed schema-history entry. No application row, content value or column was
dropped. A direct SQL attempt to set `read_time=999` is now proven to be reset by the
database trigger to the content-derived value.

## Implemented P1-Q3: bounded admin user-directory and all-user progress

`GET /api/users` and `GET /api/statistics/user-progress` preserve their existing plain-array
response shapes while accepting optional `skip` and `limit`. Defaults remain compatible with
the confirmed approximately 600-user rollout: `skip=0`, `limit=1000`; negative offsets,
non-positive limits and limits above 1000 fail with the established 400 detail before a
directory query runs. Oracle applies offset/limit over stable ascending user-ID candidate
slices, so neither endpoint hydrates the entire directory before slicing; user-progress
retains its established percentage sort within the selected slice.

The progress query selects only active operators, matching the current compliance eligibility
rule. `ComplianceQueryService` still rechecks eligibility before applying the single shared
required/read formula, so the database optimization did not become a second policy source.
Authorization remains first: all three non-admin canonical roles continue to receive 403 on
both SYSTEM_ADMIN-only surfaces, including requests that carry bounds.

## Implemented P1-Q4: complete-result org/compliance/stats cardinality boundary

Complete-result active-user operations now share a fail-loud 1000-user contract. The query
requests a 1001st sentinel row from Oracle; up to 1000 rows are accepted, while a larger active
directory returns HTTP 413 instead of silently truncating an organization report, compliance
result or statistics response. This is separate from P1-Q3's explicitly paged plain-array
endpoints and preserves their response contract.

Org-backfill plan and apply each obtain one deterministic active-user snapshot; apply reuses
that snapshot for planning and mutation instead of querying the directory twice. A pre-mutation
ceiling failure writes transaction-coupled `ORG_BACKFILL_APPLY` failure evidence with the
classified reason `CARDINALITY_LIMIT`, while all other runtime failures retain rollback behavior.
Org-wide compliance selects only active operators through a 1001-row repository sentinel, and
active-user statistics paths use the same shared boundary before filtering by team/department.
No role, scope, compliance formula or successful-response shape changed.

## Implemented P1-Q5: remaining complete-result user fan-out boundary

The shared 1000/1001 complete-result contract now covers the remaining production user
fan-outs found by source inventory: access-diff (including inactive directory rows), required-
reading assignment reminders, article eligible-operator/read-evidence candidates, manager export
scope hydration, exact-department compliance and the group-leader selector. Oracle performs the
bounded candidate query; overflow maps to the same stable HTTP 413 family instead of returning
partial evidence or exporting/creating a partial recipient set.

Assignment reminder delivery obtains and validates the whole bounded active-user snapshot before
the first reminder write, so the enclosing required-reading transaction rolls back on overflow.
Access-diff and export use the bounded service in Spring production while retaining repository-only
fallbacks solely for existing DB-free pure-test constructors. The raw-audit controller's old
private manager-scope directory scan was unreachable because all raw-audit endpoints are
SYSTEM_ADMIN-only; that dead unbounded path and its unused repository dependency were removed.
No authorization, audience, export scope, eligibility formula or successful wire shape changed.

## Implemented P1-Q6: article view/history/read-evidence cardinality boundary

`GET /api/articles/{id}/views` no longer materializes every view-log row before slicing.
`ArticleViewQueryService` asks Oracle separately for the exact requested row page and for the
full matching total/distinct-operator aggregates. Offset, limit≤200, descending view order and
optional version filter retain the established response semantics; a one-row page still reports
the complete aggregate counts for the same article/version selection.

Article history, version history and read-receipt source rows now request a 1001st sentinel and
fail loudly with the shared stable HTTP 413 response above the 1000-row complete-result ceiling.
Read-receipt assembly also checks the final combined eligible/detached row count, preventing a
bounded source query from expanding into an unbounded response. Successful response shapes,
permission/scope gates, ordering and version behavior did not change. History rows still include
the established content field; removing that CLOB or introducing a summary/detail flow requires
an explicit compatible API/UX design and was not guessed in this batch.

## Implemented P1-Q7: bounded organization reference snapshots

`OrgDirectoryQueryService` is the single deep module interface for complete-result department,
team and leadership-assignment snapshots. Each query requests a 1001st sentinel from Oracle,
accepts at most 1000 rows and fails through the stable HTTP 413 family instead of returning a
partial organization/access answer. Admin assignments, `/api/teams`, access-diff, reminder
eligibility, org-backfill and department-scope expansion use this seam in Spring production;
older unbounded repository fallbacks remain only in explicitly DB-free pure-test constructors.

The org-structure endpoint now loads all teams for the already bounded active-department set in
one query rather than one query per department. Its member-count aggregate is restricted to those
bounded team IDs. Assignment response hydration fetches only referenced user/department/team IDs,
not entire dictionaries. Successful ordering, response shapes, access gates, leadership scope and
backfill behavior are unchanged; backfill org-cardinality rejection is audited before returning 413.

## Implemented P1-Q8: remaining content plain-array ceilings

Category, tag, video and news-history list endpoints now use the same complete-result rule as
other legacy arrays: Oracle receives a 1001-row sentinel query, up to 1000 rows are returned in
full, and overflow maps to a stable HTTP 413 instead of silently truncating. No new pagination
parameter was introduced, so the current Angular single-fetch contracts and successful response
shapes remain unchanged. Video archived/department visibility, tag/name order and news-history
updated-time order are preserved.

News-history rows still contain the established content CLOB, just as article history does.
This batch bounds peak row cardinality but does not claim the history payload is lightweight;
removing content requires the same backward-compatible summary/detail API/UX design and was not
performed without that contract decision.

## Implemented P1-Q9: session/personal/compliance/quiz complete-result ceilings

The shared complete-result boundary now lives in the neutral `CompleteResultGuard` module rather
than the HTTP request-bound helper. Active-session, favorite, active-broadcast, required-reading
and quiz question/answer queries request at most 1001 rows from Oracle, accept a complete result of
at most 1000 and fail through the stable HTTP 413 family on overflow. Successful response shapes,
ordering, permissions, audience matching and business workflows remain unchanged.

Session revocation, maximum expiry and idle-expiry filters now execute in the Oracle query before
the sentinel is applied. The notification summary's recent-news branch was already bounded to ten
rows and its unread-reminder value is a database count; its required-reading branch now uses the
same 1000/1001 complete-result boundary as `/api/compliance/my-readings`. Quiz admin replacement
validates both question and total-answer ceilings before the destructive full-replace step. A
DB-free regression proves a 1001-answer payload reaches no question delete, answer write or audit
interaction.

## Implemented P1-Q10: scheduler batching and article reference projections

Export expiry cleanup no longer loads every expired `ExportJob` entity or its content BLOB. A
500-row ID/path-only projection preserves legacy file deletion, then an explicit clear/flush bulk-ID
delete removes the selected rows; the scheduler repeats bounded batches until the fixed-cutoff set is
empty. Reminder due-soon and overdue discovery now processes at most 1000 reading IDs per type/run,
so later scheduled runs make bounded progress. Each reading's assignment-seed snapshot requests a
1001st sentinel and rejects overflow before the first scheduled reminder write.

The stale-report and related-article endpoints now query `ArticleReferenceItem`, containing only the
metadata they return or rank. Oracle no longer materializes article content CLOBs for these paths.
Both remain complete-result APIs: at most 1000 metadata candidates are accepted and a 1001st row
uses the established stable 413 response. Existing stale order, related category/tag/recency ranking,
exact department filter, missing-source behavior, permissions and wire shapes are unchanged.

## Implemented P1-Q11: scalar aggregates, export sentinel and leadership query boundary

Knowledge score no longer materializes every passed `(article, version)` group for a user. Oracle
computes the distinct-version count and first-try count in one scalar projection; Java applies the
same `10 * passed + 5 * first_try` formula and returns the unchanged response. The Oracle regression
covers empty history, duplicate later passes, distinct article versions and first-/later-try results.

Readings export previously applied its 20,000-row guard only after every matching `ReadStatus` entity
had already been hydrated. The repository now requests a deterministic ID-ordered 20,001-row sentinel;
the existing oversize exception fires before user/required-reading reference hydration or export
rendering. The limit, scope rules, columns and CSV/XLSX/PDF behavior are unchanged.

Leadership queries are now separated by the information their callers need. Broadcast publishing,
compliance eligibility and org-backfill duplicate detection use scalar existence queries. Scope
resolution still needs the complete assignment set, so Spring production routes that read through
`OrgDirectoryQueryService`'s 1000/1001 fail-loud per-user snapshot. The repository-only path remains
solely as a DB-free test-constructor fallback; role, primary/acting and export-scope rules are unchanged.

The remaining caller inventory is now classified rather than left as an undifferentiated query list:

| Query family | Status | Evidence / remaining work |
|---|---|---|
| knowledge-score grouped attempts | Pass | Oracle scalar summary; no grouped Java rows; EV-138 |
| readings-export statuses | Pass | 20,001 DB sentinel before enrichment; EV-139–EV-140 |
| leadership authorization/scope | Pass | scalar existence where possible; bounded full snapshot where required; EV-141–EV-142 |
| per-user permission overrides and per-user/reading statuses | Pass | DB uniqueness plus finite permission catalog or bounded input makes the result finite |
| day/hour, role and status aggregates | Pass | caller time windows or fixed enums bound the group domain; no entity-history hydration |
| item-level required-reading relation | Pass | 1000/1001 sentinel before receipt-compliance mutation; EV-150 |
| multi-parent article-target relation fan-out | Pass | all production callers use one 1000/1001 total-child seam; two-parent 501+500 Oracle HTTP overflow is stable 413; EV-156–EV-159 |
| compliance per-user-target aggregate | Pass | Java derives max. three exact target pairs/user; Oracle returns one row/pair; EV-147–EV-149, EV-151 |
| article/news history payload | Pass | CLOB-free summary/version projections + one-row detail; legacy full arrays retain wire shape under a 2,000,000-character Oracle pre-hydration budget; EV-164–EV-179 |

## Implemented P1-Q12: item relation sentinel and bounded compliance aggregate

The article read-receipt compliance bridge previously hydrated every matching required-reading
relation for one item and the caller's All/exact/prefix targets. V6 intentionally has no uniqueness
constraint on that relation. The repository now accepts the established 1,001-row sentinel page;
the shared complete-result guard rejects overflow with stable 413 inside the receipt transaction,
before any partial compliance result can commit. Successful response, audience and read-status rules
are unchanged.

Compliance calculation no longer asks Oracle for every historical target-department group for every
selected user. `ComplianceProgressQueryService` derives each user's de-duplicated All/exact/prefix
targets with the canonical `DepartmentMatcher`, bounded by the existing 1,000-user contract. The
query receives those pairs as a CLOB JSON value, uses Oracle 19.3 `JSON_TABLE`, and returns exactly
one required/read aggregate row per requested pair: at most three rows per user. Oracle performs only
exact-string joins; department parsing and the percentage/rounding formula remain in the existing Java
modules, avoiding a second SQL interpretation of scope semantics.

The first focused Oracle run exposed a missing application `ObjectMapper` bean during repository
construction. The repository serializer was made self-contained and the corrected Oracle run passed,
including a >32 KB scope payload. This failed attempt and correction are retained as EV-148–EV-149.

## Implemented P1-Q13: multi-parent article-target relation boundary

Article list, related candidates, global search response/ranking and quiz visibility already bounded
their parent article sets, but each caller could still hydrate an unbounded junction-table child
cross-product. `ArticleTargetQueryService` is now the single production read boundary. It issues one
1,001-row sentinel query for the whole parent set, returns at most 1,000 complete relation rows, and
fails with the established stable 413 before any partial audience/visibility/ranking map is built.
Single-article lookups delegate to the same multi-parent seam; repository reads no longer expose an
unbounded overload. Successful response shape, target audience, access checks, order and search ranking
are unchanged.

The real Oracle HTTP regression creates two otherwise valid bounded article parents with 501 and 500
target rows and proves that list expansion fails loudly. Focused service/cardinality tests are 4/4 and
the Article/Search/Quiz integration regression is 90/90.

This cycle also audited Surefire artifact provenance. Three XML reports belonged to removed test classes
and contributed five stale tests to raw directory totals. Current evidence is therefore timestamp-bound:
Q13 is 785 tests (not raw 790), and the earlier Q12 source-backed totals are 781 full / 410 DB-free
(not raw 786 / 414). No artifact was deleted; pass/fail outcomes are unchanged.

## Implemented P1-Q14/Q15: history CLOB summary/detail and compatibility budget

Article/news history now has a backward-compatible list-first/detail-on-demand seam. New summary
endpoints select only revision metadata; article reader `/versions` uses the same CLOB-free projection.
Selected-detail endpoints scope the revision ID to its owning content object and load one CLOB. Angular's
admin article-history modal now uses the summary endpoint and fetches content only when a row is expanded;
diff and restore flows retain their established behavior. The four new routes are explicitly locked in
the Access Contract Matrix to the same `content.manage`/`ORG-CONTENT` policy as legacy history.

Existing full-history arrays remain available so no wire contract was removed. Their memory exposure is
now bounded: inside a SERIALIZABLE read transaction Oracle calculates aggregate CLOB characters without
hydrating entities. At exactly 2,000,000 characters the request proceeds; the first character above the
budget returns stable 413 before full-row hydration. Real Oracle tests prove oversized article and news
history remains available through the CLOB-free summary while the legacy full route rejects it.

The first broad access run correctly failed because source had four routes absent from the matrix; the
artifact was updated without changing a role/scope decision and the corrected access/broad gates passed.
The first full Chromium run was 44/45: the history scenarios passed, while department visibility depended
on a new fixture sorting into the first 40 cards of a shared DB full of older E2E rows. Searching by the
test's unique run ID isolates the same allow/deny assertion; its corrected rerun passed 1/1.

## Implemented P0-A7: legacy group-path and department-assignment boundary

Assignment-backed legacy group drill-down no longer infers authorization from whether a response happens
to contain users. The caller-controlled department/group path must resolve to exactly one active canonical
department/team pair. Missing, inactive, duplicate/ambiguous or unassigned targets fail closed with 403;
a real assigned team with no members still returns the established 200/empty response.

After target authorization, free-text department matching is not trusted as identity: only users whose
canonical `team_id` equals the authorized target are included. This closes the stale-string cross-team
case. Separately, the article read-evidence Oracle test now gives a leader both a direct group assignment
and a department assignment and proves that first-rollout named rows still contain only the direct group.
Unassigned legacy `ManagerScope`, SYSTEM_ADMIN, content-manager aggregate and export rules are unchanged.

The focused source-backed set is 96/96 after correcting one wire-shape-only assertion; the broader scope,
reminder, policy and export regression is 147/147. Timestamp-bound DB-free is 417/417 and the complete
Oracle suite is 793 tests with no failures/errors and one existing skip.

## Implemented P0-A8: content-history parent-child IDOR negative lock

Article selected-history detail, base diff, explicit compare and restore now have direct Oracle HTTP
regressions using a valid history ID owned by a different article. News selected detail and restore have
the same cross-parent proof. Every foreign-parent request returns the established 404 contract; rejected
restore leaves the target title, content, version and history cardinality unchanged and writes no success
audit. Production repositories already used parent-qualified lookup methods, so this batch locked evidence
without changing role, scope, schema, successful wire shape or business flow.

Focused Article/News is 86/86 and broad history/access/shape is 109/109. The first full gate retained as
negative evidence found an existing reused-schema Stats fixture assumption: a new count=2 search term can
fall outside a bounded top-10 when older equal-frequency rows exist. The fixture now extends the current
leading normalized bucket and asserts its prior-plus-delta count. Corrected Stats is 22/22 and the final
Java/Oracle suite is 795 tests with no failures/errors and one existing skip.

## Implemented P0-A9: SELF-scoped keyed-resource isolation lock

The session, private-note, favorite and reminder keyed-resource families now have one focused cross-user
Oracle gate. A caller presenting another user's real active session ID receives the established opaque 204
no-op: the owner token remains usable, the owner session remains listed only to its owner, and no intruder
`REVOKE_SESSION` success audit is written. On the same visible article, two users retain independent
`(user_id, article_id)` note rows; a user with no note receives the established literal JSON `null`.

Existing favorite-delete evidence still returns 404 and preserves a foreign owner's row, while reminder
mark-read keeps foreign and unknown IDs equally opaque at 404. The first focused attempt retained as
negative evidence was an assertion-adapter error only: JSONAssert cannot parse a root JSON null through
`content().json("null")`, although the endpoint response was correct. The exact-body assertion corrected
that harness issue. Focused SELF isolation is 91/91 and the final timestamp-bound Oracle regression is
797/797 with one existing skip. No production source, schema, role/scope, response code or business flow
changed in this batch.

## Implemented P0-A10: compliance/quiz per-user evidence isolation lock

The same required-reading assignment now has direct two-caller evidence: after the first operator marks
it read, only that operator has a `ReadStatus` and versioned article receipt; the second operator's
`my-readings` response remains the established `unread`. When the second operator acknowledges it, Oracle
contains distinct caller-owned rows instead of shared state.

The same quiz/version also has two-caller evidence. One operator's first pass and second attempt do not
advance the other operator's first attempt beyond 1, and the first operator's score does not appear in the
second operator's personal knowledge score. The first focused gate retained as negative evidence used the
wrong expected label (`pending`) for the established `unread` wire value; all quiz cases and 19/20 compliance
cases already passed. The corrected focused gate is 31/31 and the final Java/Oracle suite is 799 tests with
no failures/errors and one existing skip. This batch changes tests/contracts only, not production code,
schema, roles, scope, retention, successful wire or business flow.

## Implemented P0-A11: SELF collection-read cross-user disclosure lock

Three existing Oracle HTTP tests now use a second authenticated operator. Search history returns none of
the first caller's search terms; recently-viewed contains only the second caller's own view row while the
first caller's deduped order remains unchanged; and an unread reminder owned by the first caller does not
increase the second caller's notification count. This locks caller-owned collection/aggregate behavior
without changing endpoints, response shapes, ordering, visibility, schema or product policy.

The focused Article/Search/Platform gate is 91/91 green. This batch's post-baseline assertion expansion is
covered by EV-203; the later EV-224 802-test Java/Oracle run is now the current full regression baseline.

## Implemented P0-A12: SELF profile/effective-access and directory-owned password lock

The User Oracle HTTP integration suite now proves that two operators receive only their own explicit
effective-access overrides. Current-profile reads are caller-bound, while a self-profile update changes
only the caller's allowed name/position/phone/card-style fields: the caller's email/role/department and a
second user's profile remain unchanged. The directory-owned self-password route keeps the established 403
response and leaves the stored password hash and actor success-audit count unchanged.

The focused User gate executed 32 tests with 0 failures/errors and 1 existing skip (`BUILD SUCCESS`). This
batch changed tests/contracts only; production source, schema, roles/scope and business flow are unchanged.
EV-205 covers this assertion expansion; the later EV-224 802-test Java/Oracle run is the current full baseline.

## Implemented P0-A13: favorite collection and reminder inbox/read ownership lock

Two callers can favorite the same visible item without sharing an ownership row or collection. Each list
returns only the caller's favorite ID, and deleting one caller's row leaves the other caller's same-item
favorite intact. Required-reading assignment creates a distinct reminder per recipient; each inbox returns
only its caller row, a foreign read remains the established opaque 404, and reading one reminder leaves the
other recipient's read state unchanged. Existing pagination and idempotent-read behavior remain green.

The focused Favorite/Reminder Oracle gate is 10/10 with `BUILD SUCCESS`. This batch changed tests/contracts
only; production source, schema, roles/scope and business flow are unchanged. EV-207 covers the focused
assertions and the later EV-224 802-test Java/Oracle run is the current full baseline.

## Implemented P0-A14: read-receipt status and compliance-progress SELF lock

On one article/version, the first caller's receipt does not make `/read-receipt/me` true for a second
caller; the second caller creates a separate receipt and then sees its own status. For the same eligible
reading set, `/my-progress` reports the same correct total to both callers but keeps completion-derived
fields caller-owned: the first caller is 1/2 (50%) while the second remains 0/2 (0%).

The focused Article/Compliance Oracle gate is 91/91 with `BUILD SUCCESS`. This batch changed tests/contracts
only; production source, schema, roles/scope, retention and business flow are unchanged. EV-209 covers the
focused assertions and the later EV-224 802-test Java/Oracle run is the current full baseline.

## Implemented P0-A15: portal-session heartbeat caller isolation lock

The heartbeat endpoint now has direct real-Oracle evidence that a valid-CSRF request without authentication
receives the stable JSON 401 contract, while an authenticated request advances only the session bound to the
token's caller. A second user's active session timestamp remains unchanged. Existing login, logout, session
list/revoke and rate-limit behavior remains green; logout policy itself was not changed.

The first attempt stopped at the expected CSRF 403 because the no-auth POST lacked a test CSRF token. The
second proved the endpoint behavior but compared pre-persistence nanoseconds with Oracle-rounded precision.
After moving the untouched-session baseline after the DB round-trip, the Auth gate passed 11/11. These are
harness corrections retained as EV-211/EV-212; production source, schema and policy are unchanged.

## Implemented P0-A16 / D-8: independent aggregate statistics capability

The confirmed D-8 contract is now executable end to end. `stats.view` is a dedicated permission for the six
company-wide aggregate endpoints (`activity`, `breakdown`, `compliance`, `failed-searches`, `kpi` and
`popular-searches`). `content.manage` alone no longer opens them; no non-admin role receives the new
permission by default, while an explicit grant and SYSTEM_ADMIN bypass open the aggregate view. A stats-only
caller still cannot open content administration, raw audit, named user progress or critical-operator data.

Angular route guards, navigation, permission catalog/localization and the overview loader follow the same
split. Aggregate cards load for a stats-only caller; category/article management needs `content.manage`, and
audit/named-person panels remain system-admin-only. The first backend gate correctly exposed a stale Access
Contract Matrix binding, the first supported Angular run exposed template block placement, and one compiled
full-suite attempt lost a Vitest worker. All corrections are retained as EV-215–EV-219. Final evidence is
Java/Oracle 802 tests (0 failure/error, 1 existing skip), Angular 96/96 and a successful production build.

## Implemented P0-A17: async export-job owner IDOR evidence lock

The real-Oracle HTTP export suite now creates a completed XLSX job for one manager and probes the same real
job ID as a second manager who independently has `reports.export` and a canonical primary team scope. The
foreign caller receives the same opaque responses as an unknown/expired job (404 for status, 410 for
download), while the owner still receives 200 for both operations and the job row remains available until
the established TTL cleanup. This proves object ownership independently of coarse export permission/scope.

The focused export family is 29/29 green. This is a test/evidence increment only; D-3 SYSTEM_ADMIN legacy
bypass, classified `ADMIN_*` strict-owner behavior, export scope, response contract, retention and business
flow are unchanged. EV-224 remains the current full 802-test production-source regression baseline.

## Implemented P0-A18: video-view audience/archived object-visibility closure

`POST /api/videos/{id}/view` previously authenticated the caller and loaded the ID, but did not apply the
same audience/archived visibility used by the video list. A caller with a known ID could therefore increment
the count of a foreign-department or archived video. The endpoint now returns the same opaque 404 as a missing
video unless the object is visible under the established list contract; rejected requests leave both counts
unchanged. Content administrators retain their existing all/archived visibility and successful view flow.

The focused Video + Access Contract gate is 20/20 green on Oracle 19.3/Flyway v45. This batch changes
production source, so EV-224 is explicitly the pre-A18 full 802-test baseline; a complete post-A18 Java/Oracle
regression/package remains the next internal verification step for the continuing agent.

## Evidence

- Java compile: `mvnw.cmd -DskipTests compile` — `BUILD SUCCESS`.
- Generic DB-free audit contract: 3 tests, 0 failures/errors/skips, including sensitive-value minimization.
- Initial CRUD-focused Java + real Oracle 19.3: 106 tests, 0 failures/errors/skips.
- Lifecycle-focused Java + real Oracle 19.3: 96 tests, 0 failures/errors/skips.
- Flyway during focused suite: 44 migrations validated; schema version 44; no migration required.
- Failure-path proof: 51-character audit action caused real `ORA-12899`; the same
  transaction rolled back and the test category did not exist afterward.
- Identity/leadership focused evidence: user suite 30 green + 1 existing skip,
  leadership 3/3, generic writer 3/3 and Oracle rollback 1/1. The first combined run
  exposed only a new assertion that assumed repository row order; it was corrected to
  select by action and rerun green.
- Full Java/Oracle regression after identity/access audit hardening: 655 tests,
  0 failures, 0 errors, 1 skipped; `BUILD SUCCESS`; total time 05:48.
- P0-A3 focused runs: auth/session 37/37; reminder/export registration 45/45;
  async registration assertion 15/15; broad application surface 137/137;
  autosave 76/76; export worker outcome 12/12; read/quiz evidence 32/32.
- SYSTEM_ADMIN negative matrix: 22 protected surfaces × 3 non-admin canonical roles =
  66 tests, all 403 and all green.
- The first P0-A5 full run executed 727 tests and exposed one nullable-actor assertion
  error in the upload test; the production authorization path was not implicated.
- Nullable-actor focused upload rerun: 7/7 green.
- Final full Java/Oracle regression after P0-A5: 727 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v44.
- P0-A6 team-stats focused Oracle regression: 20/20 green; broader read-evidence,
  reminder, export, policy and stats scope regression: 132/132 green.
- Final full Java/Oracle regression after P0-A6: 728 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v44.
- P1-Q1 article/news projection/bounds regression: 78/78 green. Search/cache plus
  list regression: 93/93 green, including 512-entry cache ceiling and real-Oracle
  preservation of short-query/ranking/visibility behavior.
- Final full Java/Oracle regression after P1-Q1: 733 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v44; total time 02:39.
- P0-R1 legal-hold focused final-code regression: 9/9 green; broad audit/deletion/
  SYSTEM_ADMIN regression: 77/77 green. The initial compile and first executable
  mismatches are retained in EV-069/EV-070 and were corrected without weakening access.
- Final full Java/Oracle regression after P0-R1: 736 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v44; total time 02:38.
- Initial P1-Q2 migration run retained as negative evidence: Oracle 19.3 rejected the
  LOB-specific trigger declaration with `ORA-25006`; only the already auto-committed
  column/backfill remained. Flyway repair changed schema-history metadata only.
- Corrected P1-Q2 migration/projection suite: 81/81 green, including safe v44→v45
  migration, detail/search compatibility, exact 450-word read-time and a direct-writer
  drift attempt reset by the Oracle trigger.
- Final full Java/Oracle regression after P1-Q2: 739 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v45; total time 02:43.
- P1-Q3 first user-directory/access focused gate: 98 tests, 0 failures/errors,
  1 existing skip. Final directory/progress/access focused gate: 124 tests, 0 failures/errors,
  1 existing skip; invalid bounds and successive Oracle slices are covered.
- Final full Java/Oracle regression after P1-Q3: 741 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v45; total time 02:34.
- P1-Q4 final directory/org/compliance/stats/error focused gate: 143 tests, 0 failures,
  0 errors, 0 skipped; the 1000/1001 boundary, single-snapshot apply, transaction annotation,
  stable 413 mapping and existing scope contracts are covered.
- Final full Java/Oracle regression after P1-Q4: 748 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v45; total time 02:44.
- Backend package: 324 main and 112 test sources, 48 main-resource files; executable Spring Boot
  JAR produced with `BUILD SUCCESS` after the final full test run; total time 8.066 s.
- P1-Q5 first DB-free gate exposed two fixture-only failures because the new published Article
  fixture retained its default draft flag; setting the fixture's established `isDraft=false`
  precondition produced a 24/24 green rerun without production changes.
- P1-Q5 first Oracle/Spring boundary gate: 197 tests, 0 failures/errors/skips. Final expanded
  user-fan-out/audit/access gate: 269 tests, 0 failures/errors, 1 existing skip.
- Final full Java/Oracle regression after P1-Q5: 754 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v45; total time 02:36.
- P1-Q6 corrected view paging/aggregation gate: 147/147 green; final article evidence/access/
  response-shape gate: 155/155 green; final article-specific gate: 67/67 green.
- The first P1-Q6 full run executed 757 tests and found one static classification mismatch:
  non-access cardinality helpers named `require*` were interpreted as authorization gates.
  Renaming them to `enforce*` preserved access behavior; focused contract/cardinality rerun 6/6 green.
- Final full Java/Oracle regression after P1-Q6: 757 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v45; total time 02:59.
- P1-Q6 backend package: 326 main and 115 test sources, 48 main-resource files; executable
  Spring Boot JAR produced with `BUILD SUCCESS`; total time 14.491 s.
- P1-Q7 production compile: 327 main sources, `BUILD SUCCESS`, 15.358 s. DB-free org module/
  access/backfill/reminder gate: 53/53 green; Oracle/Spring org/access gate: 132 tests,
  0 failures/errors and 1 existing skip.
- Final full Java/Oracle regression after P1-Q7: 763 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v45; total time 03:14.
- P1-Q7 backend package: 327 main and 116 test sources, 48 main-resource files; executable
  Spring Boot JAR produced with `BUILD SUCCESS`; total time 14.868 s.
- P1-Q8 category/tag/video/news-history Oracle/access/shape gate: 75/75 green, including
  exact-ceiling/sentinel and stable 413 module tests.
- Final full Java/Oracle regression after P1-Q8: 766 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v45; total time 03:10.
- P1-Q9 initial test-compile gate retained as negative evidence: 8 test-only calls still used
  the removed unbounded reminder overload. Explicit bounded `PageRequest` calls corrected the
  fixtures; the corrected compile gate passed with 328 main and 117 test sources.
- P1-Q9 DB-free complete-result/access/destructive-order gate: 32/32 green. Oracle-backed
  session/favorite/broadcast/compliance/notification/quiz/reminder HTTP gate: 63/63 green.
- Final full Java/Oracle regression after P1-Q9: 767 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v45; total time 03:19.
- P1-Q10 background scheduler/cardinality gate: 19/19 green; article reference Oracle/access/
  response gate: 87/87 green.
- The first BLOB-free export cleanup gate ran 12 tests and found one same-transaction persistence-
  context mismatch after inherited bulk-ID deletion. An explicit clear/flush bulk-ID query fixed
  it without restoring BLOB loading; corrected export gate 12/12 green with row/file cleanup.
- Final full Java/Oracle regression after P1-Q10: 770 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v45; total time 03:10.
- P1-Q11 focused gates: knowledge-score Oracle 10/10; readings-export DB-free 11/11 and
  Oracle 11/11; leadership/org/policy DB-free 34/34 and broadcast Oracle 9/9.
- P1-Q11 full DB-free regression: 406 tests, 0 failures/errors/skips; `BUILD SUCCESS`.
- Final full Java/Oracle regression after P1-Q11: 774 tests, 0 failures, 0 errors,
  1 skipped; `BUILD SUCCESS`; Oracle 19.3 and Flyway schema v45; total time 03:19.
- P1-Q12 focused gates: compliance scope/formula DB-free 4/4; corrected compliance aggregate
  Oracle 2/2; item-relation sentinel Oracle HTTP 1/1; broad Stats/User/aggregate 55 tests with
  0 failures/errors and 1 existing skip. The first Oracle attempt failed at context wiring and is
  retained as EV-148; the corrected rerun is EV-149.
- P1-Q12 raw DB-free/full report-directory totals were 414/786; the later source/timestamp audit
  identified five stale tests. Corrected source-backed totals are DB-free 410 and full 781, with
  0 failures/errors and one full-suite skip; EV-161 records the correction.
- P1-Q13 focused query/cardinality gate 4/4; multi-parent overflow Oracle HTTP 1/1;
  Article/Search/Quiz Oracle integration regression 90/90, all with 0 failures/errors/skips.
- P1-Q13 timestamp-bound DB-free regression: 413 tests, 0 failures/errors/skips; exit 0.
- Final timestamp-bound Java/Oracle regression after P1-Q13: 785 tests, 0 failures, 0 errors,
  1 existing skip; Oracle 19.3 and Flyway schema v45. Three removed-class stale XML reports
  (five tests) were excluded from the raw 790 directory total without deleting artifacts.
- P1-Q13 backend package: 334 main and 123 test sources, 48 main-resource files; executable
  Spring Boot JAR produced with `BUILD SUCCESS` (93,354,634 bytes).
- P1-Q14 summary/detail Oracle contract 3/3; corrected access/history gate 103/103 and
  explicit content-manage/source-matrix lock 5/5. The first broad run's two matrix omissions are
  retained as EV-168 and corrected without role/scope change.
- P1-Q15 character guard + oversized Article/News Oracle HTTP gate 4/4; final broad access/history
  regression 107/107.
- P0-A7 focused source-backed gate: 96/96; broad named-data/access regression: 147/147.
- P0-A8 focused parent-child IDOR gate: 86/86; broad history/access regression: 109/109.
- First P0-A8 full gate: 795 tests with one reused-schema Stats top-10 fixture failure; all new history
  cases passed. Corrected isolated Stats gate: 22/22.
- P0-A9 first focused attempt: 91 tests with one test-harness JSON root-null assertion error; session,
  favorite and reminder suites and 70/71 article cases passed. Corrected focused SELF gate: 91/91.
- P0-A10 first focused attempt: 31 tests with one established-wire assertion mismatch (`pending` versus
  actual `unread`); Quiz 11/11 and Compliance 19/20 passed. Corrected compliance/quiz gate: 31/31.
- P0-A11 Article/Search/Platform cross-user collection gate: 91/91 green; production source unchanged.
- P0-A12 User SELF profile/effective-access/password gate: 32 tests, 0 failures/errors,
  1 existing skip; production source unchanged.
- P0-A13 Favorite/Reminder cross-user ownership gate: 10/10 green; production source unchanged.
- P0-A14 Article/Compliance receipt-status/progress gate: 91/91 green; production source unchanged.
- P0-A15 Auth/session heartbeat gate: corrected 11/11 green; production source unchanged.
- D-8 backend capability/source-contract gate: corrected 12/12; functional first gate's other 67 tests passed.
- D-8 Angular changed-surface gates: 33/33 across five files; final full suite 27 files / 96 tests green.
- D-8 Angular production build: initial 370.17 kB, configured bundle error budget respected.
- P0-A17 export job owner/status/download gate: 29/29 green; production source unchanged.
- P0-A18 video view object-visibility + contract gate: 20/20 green; full post-A18 regression not yet run.
- Current timestamp-bound DB-free regression: 417 tests, 0 failures/errors/skips; exit 0.
- Latest full Java/Oracle regression (pre-A18): 802 tests, 0 failures, 0 errors, 1 existing skip;
  Oracle 19.3/Flyway v45; `BUILD SUCCESS`; total time 05:37 min. Post-A18 focused gate is 20/20.
- Latest backend package (pre-A18): 339 main and 124 test sources, 48 main-resource files; executable
  Spring Boot JAR produced with `BUILD SUCCESS` (93,364,931 bytes; EV-226); repackaging is pending.
- Current Angular: 27 files / 96 tests green; production build green; prior npm audit 0 with dependencies unchanged.
- Current Chromium: initial full run 44/45 plus corrected isolated department-visibility 1/1;
  both admin and reader article-history scenarios were green in the initial full run.
- Pre-P1-Q14 Angular snapshot: 25 files / 88 tests green; production build green; npm audit
  reports 0 vulnerabilities.
- Pre-P1-Q14 isolated local Playwright snapshot: Chromium 45/45 green in 3.4 minutes; temporary
  Java/Angular helpers stopped and ports freed after the run.

The first Oracle attempt failed before tests because Spring Boot 4.1 did not expose an
`ObjectMapper` bean in this application context. The audit writer was made self-contained,
then the complete focused command passed. This is retained in the Test Evidence Ledger.

## Implemented P0-C: health/readiness and load release gates

- Actuator health is the explicit orchestration contract: liveness contains only
  JVM availability, readiness contains availability plus Oracle.
- Operational endpoints expose aggregate status only; health details remain hidden.
- A synthetic DOWN database contributor produces readiness HTTP 503 while liveness
  remains HTTP 200/UP; real healthy Oracle produces readiness HTTP 200/UP.
- Compatibility `/api/health` now returns HTTP 503 on Oracle failure and still does
  not expose JDBC/internal topology details.
- k6 default target now matches the normal ceiling (150 VUs), keeps 600 as an
  explicit stress override, adds global search, and hard-fails at error rate ≥1%,
  check rate ≤99%, or search p95 ≥2 seconds.
- Node syntax validation passed. k6 is not installed on this workstation and no
  approved staging target/result exists, so performance remains `Partial`.

## Risks / not verified

- Full current Java/Oracle, Angular unit/build and Chromium Playwright regressions pass;
  this does not replace non-Chromium, staging, UAT or signed security evidence.
- Application mutation audit families are reconciled; WS1 remains `Partial` because
  audit archive/continuity, deleted-directory identity resolution and operational proof
  are not yet complete. Raw audit access meta-events remain best-effort by contract.
- SYSTEM_ADMIN-only named/raw-data surfaces now have explicit non-admin negative coverage;
  the independent `stats.view` capability opens only company-wide aggregate statistics and does not
  inherit from `content.manage` or widen content/audit/named-user panels;
  manager team-stats foreign IDs, assignment-backed legacy path missing/ambiguous/foreign targets,
  stale cross-team bindings and department-assignment named-evidence widening are covered locally.
  Article/news selected history detail/diff/compare/restore also reject real foreign-parent IDs and
  rejected restore is proven non-mutating/non-auditing.
  WS2 remains `Partial` until production-like staging UAT proves zero named-data leakage end to end.
- File access authenticates a user but does not prove the requested file belongs to a
  content object visible in that user's audience/scope. Exact enforcement is blocked by
  the PO-02 (`AUTH`) versus execution-plan object-entitlement conflict.
- Hard k6 gates exist but have not been executed on production-like staging.
- Public article/news/search cardinality and global-search cache are bounded, both content
  lists are CLOB-free, and admin user-directory/all-user progress now page at Oracle.
  Complete-result org-backfill, org-wide compliance and active-user statistics fail loudly
  above the 1000-user safety boundary rather than returning partial results. Access-diff,
  assignment reminders, eligible-operator/read-evidence, manager export, exact-department
  compliance and group-leader user fan-outs now use the same bounded family. Article-view rows
  use exact DB paging with DB aggregate counts; article history/version/read-receipt source and
  combined response sizes fail loudly above 1000. History first-party lists are CLOB-free and legacy
  full arrays are character-budgeted. Organization department/team/assignment snapshots now share a 1000/1001 fail-loud module
  and org-structure N+1 hydration is removed. Category/tag/video/news-history, active-session,
  favorite, active-broadcast, required-reading and quiz arrays now fail loudly above 1000;
  notification recent-news is bounded to ten and unread reminders use an aggregate count.
  Stale/related article paths now use CLOB-free bounded metadata, export cleanup uses BLOB-free
  500-row batches, and scheduled reminder discovery/seed snapshots are bounded. Knowledge score is
  a scalar Oracle aggregate, readings export uses a pre-hydration 20,001 sentinel, and leadership
  reads are scalar or bounded according to caller need. The item-level required-reading bridge now
  fails loudly above 1000 relations, and compliance aggregate output is limited to three exact
  target pairs per bounded user. The multi-parent article-target child cross-product now has the
  same 1000/1001 fail-loud boundary across list/search/related/quiz callers. History list/version
  consumers are CLOB-free, selected detail is one-row, and legacy full arrays have a pre-hydration
  aggregate character budget. No representative-corpus memory/p95 result exists yet.
- Legal-hold set/release, held restore/purge rejection, audit snapshots and purge
  serialization are implemented. The allowlist remains intentionally empty: exact named
  SSO authorities, purge approval and retention schedule still require DPO/Legal evidence.
- Real SSO, backup restore, DPO/Legal approval, security scans/pentest, staging rollback,
  monitoring/on-call and UAT remain external evidence gaps.

## Decisions needed

Two material scope/security clarifications are recorded; neither blocks independent work:

- keep PO-02 exactly as written — any active authenticated employee may open any
  known `/uploads/{filename}`; or
- enforce content-dependent entitlement — a user may open a bound file only if the
  owning article/news is visible in that user's effective content audience, with a
  narrowly defined uploader rule while a new file is not yet bound.

The second option is stricter but changes the confirmed `AUTH` scope, requires a
resource-binding model/migration and needs an explicit Product/Security decision.

Additionally, confirm whether anonymous idempotent `/api/auth/logout` remains a public
expired-token recovery exception, or becomes authenticated-only with a separately approved
frontend local-cleanup flow. Current behavior remains unchanged until that decision.

## Next batch

1. Continue WS2 P0 endpoint negative/IDOR mapping with the next independent role/scope or keyed-resource
   family after D-8, excluding attachment policy and logout until DEC-P01/DEC-P02 are explicitly resolved.
2. Resolve the P0-B file-entitlement decision above; then implement the chosen policy
   with authorized/unauthenticated/cross-scope regressions.
3. Run the hard k6/memory gate only against an approved production-like non-production target
   and attach the result; do not use a local pass as production evidence.
