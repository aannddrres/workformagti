# OPUS5 Audit 2 — Backend business logic & data integrity

**Date:** 2026-08-14
**Scope:** `java-backend/src/main/java/ge/magti/portal/web/` (all 17 controllers across the 14
domains) plus the service/repository/migration layers they depend on.
**Mode:** read-only. No source file was modified, nothing committed, nothing pushed.
**Context read first:** `CLAUDE.md`, `docs/JAVA_ORACLE_ANGULAR_MIGRATION.md` (for the intended
Python behaviour each Java controller is meant to match).

## Verification method

- `mvn -B test`: **383 tests, 0 failures, 217 errors**, every error a single environmental
  cause (`ORA-12541: no listener at localhost:1521` — this container has no Oracle). All 166
  pure-unit tests pass, including `ComplianceCalculatorTest` (9), `QuizGraderTest` (4),
  `ExportCellSanitizerTest` (4), `CsvExportBuilderTest` (4), `XlsxExportBuilderTest` (3),
  `PdfExportBuilderTest` (3), `DepartmentMatcherTest` (11). Endpoint-level claims below come
  from source tracing plus the schema, not from a green integration run.
- Framework-behaviour claims were verified against the actual dependency jar rather than
  assumed — see BL-verified §"Scheduler survives an exception".

## Findings summary

| # | Severity | Status | Finding |
|---|----------|--------|---------|
| BL-01 | **High** | CONFIRMED | Deleting any previously-edited news item fails with HTTP 500 (`news_history` FK has no `ON DELETE CASCADE`) |
| BL-02 | **High** | CONFIRMED | Deleting an article leaves its mandatory-reading rows behind as permanent ghosts that drag every operator's compliance down |
| BL-03 | **High** | CONFIRMED | Autosave rewrites published content without bumping the version, so quiz passes and read receipts stay "valid" for text nobody read |
| BL-04 | Medium | CONFIRMED | Editing a required reading can re-point it at a different article, silently transferring everyone's read status |
| BL-05 | Medium | CONFIRMED | Management roles are excluded from compliance everywhere except the notifier, which messages them anyway |
| BL-06 | Medium | CONFIRMED | Due dates have no consequence anywhere — no escalation, no daily alert job (the Python one was not ported) |
| BL-07 | Medium | CONFIRMED | Deleting the fallback category makes later category deletions move articles into an invisible category |
| BL-08 | Medium | CONFIRMED | `categories.name` is not unique and creation does not check — two identically-named categories are indistinguishable to users |
| BL-09 | Medium | CONFIRMED | Export download is one-shot and pod-local: a refresh loses the file, and on >1 replica the download usually 404s |
| BL-10 | Medium | CONFIRMED | Article/video/news deletion orphans tag mappings and favourites |
| BL-11 | Low | SUSPECTED | Concurrent update/restore of one article can collide on `ux_article_history_article_version` (no optimistic locking) |
| BL-12 | Low | CONFIRMED | Read receipts and view logs survive article deletion with `article_id = NULL` — retained but unlinkable |
| BL-13 | Low | CONFIRMED | Flyway has no `baseline-on-migrate`; pointing the app at a non-empty existing schema fails at boot |
| BL-14 | Low | CONFIRMED | The quiz gate is skipped when the required reading's article row is missing |

**Totals: Critical 0 · High 3 · Medium 7 · Low 4 (14 findings).**

---

## BL-01 — Deleting an edited news item returns 500

**Severity: High · CONFIRMED**
`src/main/resources/db/migration/V16__create_news_history.sql:10`,
`web/NewsController.java:176-191`, `web/NewsController.java:160` (`archiveCurrentState`)

`V16` declares `CONSTRAINT fk_news_history_news FOREIGN KEY (news_id) REFERENCES news (id)` —
with **no** `ON DELETE CASCADE`, i.e. Oracle's default `NO ACTION`. Every other article-child
table has the cascade (`V17`, `V18`, `V21`, `V26`, and `V24`/`V25` retro-fitted by
`V30__cascade_delete_article_child_fks.sql`); `news_history` was not part of that sweep, and
`V30` only touches `user_notes` and `knowledge_feedback`.

`updateNews` writes a history row on **every** edit (`NewsController.java:160`
`archiveCurrentState(news, user.getId())`), so any news item that has ever been edited has at
least one `news_history` row. `deleteNews` (`:176-191`) deletes only the `News` row and the
trigram index entries; it never clears history first.

This is precisely the bug class `V30`'s own header describes: *"A personal note or feedback
report left on an article made DELETE /api/articles/{id} 500 (ORA-02292)"*. The identical
condition still exists for news.

**Failure scenario:** a content admin publishes a news item, fixes a typo (one edit → one
history row), then tries to delete it. Oracle raises `ORA-02292: integrity constraint
violated - child record found`, Spring maps it to `DataIntegrityViolationException`, and with no
`@ControllerAdvice` anywhere in the backend the client receives a bare HTTP 500. The news item
cannot be deleted through the UI at all; only an untouched, never-edited item deletes cleanly —
which is exactly why this can pass a quick manual test and still fail in real use.

**Suggested fix direction:** add a `V31` migration that drops and recreates
`fk_news_history_news` with `ON DELETE CASCADE`, mirroring `V30` line-for-line. Decide
deliberately whether `fk_news_history_user` should also become `ON DELETE SET NULL` — deleting
a user who ever edited news has the same shape of problem.

---

## BL-02 — Deleting an article leaves an un-removable mandatory reading behind

**Severity: High · CONFIRMED**
`web/ArticleController.java:412-431`, `src/main/resources/db/migration/V6__create_required_readings.sql:1-10`,
`V22__create_read_statuses.sql:9-10`, `web/ComplianceController.java:111-142`,
`compliance/ComplianceCalculator.java:88-108`

`required_readings.item_type/item_id` is a deliberate polymorphic soft reference with no foreign
key (`V6` header comment says so explicitly). `deleteArticle` (`ArticleController.java:424-430`)
deletes the `Article` row and the search trigrams — nothing else. The DB cascade cleans the
*declared* children (target departments, history, quiz questions/attempts, notes, feedback), but
`required_readings` is invisible to it.

The surviving row keeps counting. `ComplianceCalculator.computeProgress`
(`ComplianceCalculator.java:88-104`) builds the denominator from
`requiredReadingRepository.countGroupedByTargetDepartment()` — a pure `GROUP BY` over
`required_readings` with no existence check on the referenced item. `ComplianceController`'s
`my-readings` renders the ghost with the placeholder strings from lines 137-138:
`"Item #" + r.getItemId()` and `"Content not available."`.

**Failure scenario:** an article assigned as mandatory reading to the technical department is
deleted (replaced by a newer article, say). Every operator in that department now sees a
mandatory item titled `Item #482` with the body "Content not available." that they cannot
meaningfully read. Their compliance percentage is permanently reduced — the denominator still
counts it — so the executive dashboard shows the department sliding below target for a reason no
one can see. They *can* clear it by pressing acknowledge (`markRead` tolerates the missing
article, `ComplianceController.java:182-190`), which is arguably worse: staff are trained to
acknowledge material they never read. The same orphaning applies to deleted news items.

**Suggested fix direction:** in `deleteArticle` (and `deleteNews`), delete the matching
`required_readings` rows and their `read_statuses` inside the same transaction — note the
ordering constraint from BL-01's cousin: `read_statuses` → `required_readings` → item. Failing
that, filter unresolvable items out of both `my-readings` and the compliance denominator so a
deleted item stops being owed. A periodic integrity sweep would catch the rows already orphaned
by any deletion done before the fix.

---

## BL-03 — Autosave changes published articles without a version bump

**Severity: High · CONFIRMED**
`web/ArticleController.java:325-410` vs `:268-323`, `quiz/QuizGateChecker.java:47-48`,
`repository/ArticleReadReceiptRepository` (`upsert`, keyed on `article_version`),
`src/main/resources/db/migration/V19__create_article_read_receipts.sql:17`

`updateArticle` does the full, correct ritual: archive the current state
(`:293-295`), apply fields, **`article.setVersion(article.getVersion() + 1)`** (`:307`), save,
reindex, and write a new history row (`:313-320`).

`autosaveArticle` (`:325-410`) applies `title`, `content`, `status`, `published_at`,
`target_departments`, `is_draft` and more to the same live row — and then, at `:405-407`, sets
only `updatedAt`, saves, and reindexes. **No version bump. No history row.** There is also no
guard restricting it to drafts: nothing in the method checks `article.isDraft()` or the current
`status`, so it applies to a published article exactly as readily as to a draft.

Three consequences follow from the version staying still, all of which are the version number's
whole job:

1. **Quiz passes carry over silently.** `QuizGateChecker` looks up
   `existsByArticleIdAndArticleVersionAndUserIdAndPassedTrue(article.getId(),
   article.getVersion(), user.getId())` (`QuizGateChecker.java:47-48`). Rewrite the article body
   via autosave and every operator who passed the quiz on the old text still satisfies the gate.
2. **Read receipts stay green.** `article_read_receipts` is unique on
   `(article_id, article_version, operator_id)` (`V19:17`), so the receipt written against the
   old version still reads as "this person has read the current version".
3. **History becomes inaccurate rather than merely incomplete.** The stored `article_history`
   snapshot for version *N* holds the pre-autosave text while the article at version *N* holds
   post-autosave text. `getArticleVersions` (`:851-895`) will list version *N*, and a diff of
   "current vs version *N*" shows changes *within one version number* — which is not a state the
   version list is designed to express.

**Failure scenario:** a content admin opens a published compliance article and reworks a
procedure. The editor autosaves as they type. The change is live for readers, but every operator
who already acknowledged the old text remains marked as having read and passed the current
version. The read-receipt report (`/api/articles/{id}/read-receipts`) shows full compliance for
content nobody has seen — the exact assurance the versioned receipt design exists to provide.

**Suggested fix direction:** decide the intended contract explicitly. Either restrict autosave to
articles where `is_draft`/`status != 'published'` (returning 409 otherwise), or make autosave
bump the version and write history the same way `updateArticle` does when it touches `title` or
`content` of a published article. A test asserting "a passed quiz does not satisfy the gate after
the article's content changes" would pin whichever choice is made.

---

## BL-04 — Re-pointing a required reading transfers everyone's read status

**Severity: Medium · CONFIRMED**
`web/ComplianceController.java:265-286`, `V22__create_read_statuses.sql:6-11`

`updateRequiredReading` reassigns `itemType` and `itemId` (`:279-280`) on an existing row. The
`read_statuses` rows are keyed on `(user_id, required_reading_id)`
(`uq_read_status_user_reading`, `V22:11`) — they follow the *reading*, not the item. Nothing in
the method resets them when the target changes.

**Failure scenario:** an admin edits a mandatory reading to point at the updated version of a
procedure (a different article id). Every operator who had acknowledged the old article is
instantly recorded as having read the new one; the compliance dashboard shows 100% for material
that was published seconds ago. There is no audit row for this endpoint either (unlike most
mutating endpoints in this codebase), so nothing records that the target was swapped.

**Suggested fix direction:** if `itemType`/`itemId` differ from the stored values, either reject
the change (force "delete + create", which is what the semantics actually are) or clear the
associated `read_statuses` in the same transaction. Add an audit row either way.

---

## BL-05 — The notifier messages the exact people compliance excludes

**Severity: Medium · CONFIRMED**
`compliance/RequiredReadingNotifier.java:50-58` vs `compliance/ComplianceCalculator.java:44-58`
and `web/ComplianceController.java:105-109`

The prompt's question — is management genuinely excluded everywhere the compliance calculation is
reused — is **yes** for every calculation path, and **no** for the notification path:

- `ComplianceQueryService.computeCompliance` filters `ComplianceCalculator::isEligible`
  (`ComplianceQueryService.java:72`), and every dashboard/export/team-stats view goes through it.
- `ComplianceController.getMyReadings` returns an empty list outright for management roles
  (`:107-109`).
- `EligibleOperatorsService.forArticle` filters `MANAGEMENT_ROLES`
  (`EligibleOperatorsService.java:58`).
- `StatsController.getGroupUsers` filters `ComplianceCalculator::isEligible` (`:334`).
- **`RequiredReadingNotifier.notifyAffectedUsers` does not.** Line 50 iterates
  `userRepository.findByActiveTrue()` and filters on department only (`:55-57`) — no
  eligibility check.

**Failure scenario:** a required reading is created for "ტექნიკური". Every manager, content admin
and system admin whose department matches receives an inbox message and an unread badge telling
them there is new mandatory material with a deadline. They open the readings page and it is
empty (`:107-109`), because the item was never owed by them. Recurring, unactionable
notifications are how people learn to ignore the notification channel.

**Suggested fix direction:** add `.filter(ComplianceCalculator::isEligible)` to the notifier's
candidate loop, so the one rule has one implementation across all five call sites.

---

## BL-06 — A missed deadline has no consequence anywhere

**Severity: Medium · CONFIRMED**
`web/ComplianceController.java:132-135`, `web/PlatformController.java:110-114`,
`web/ArticleController.java:1142-1152`, repo-wide `@Scheduled` inventory

Every use of `due_date` in the backend is presentational. A grep for `getDueDate` across
`src/main/java` returns exactly these consumers:

- `ComplianceController.java:132-135` — relabels an unread item's status string to `"overdue"`
  for display.
- `PlatformController.java:110-114` — an `overdue` boolean on the notification summary.
- `ArticleController.java:1142-1152` — an `isLate` flag on a read-receipt row.
- `ExportController.java:245` / `ExportQueryService.java:83` — a "ვადა" column in exports.

Nothing else. The compliance percentage does not weight overdue items differently
(`ComplianceCalculator.computeProgress:88-108` counts read/required only). No access is
restricted. And the only `@Scheduled` bean in the entire backend is
`ExportJobCleanupScheduler` (verified by grep across `src/main/java`) — there is **no Java
equivalent of the Python side's `compliance_alerts.py`**, which `CLAUDE.md` documents as a
separate container running every 24 hours.

**Failure scenario:** a mandatory reading's deadline passes. The item turns red in the operator's
own list — which only matters if they open that page. No reminder is sent, no manager is
notified, nothing escalates. The organisation discovers the miss when someone runs a report,
which is exactly the manual process the compliance feature was meant to replace. Note this is a
migration *gap*, not a regression introduced here: the behaviour existed in Python and did not
come across.

**Suggested fix direction:** port `compliance_alerts.py` as a `@Scheduled` bean (the
`ComplianceCalculator` javadoc at lines 20-26 already argues that Spring makes this easy — one
`@Service` shared by the job and the controllers, no duplicated formula). Decide separately
whether overdue items should be weighted in the percentage or merely reported.

---

## BL-07 — Deleting the fallback category quietly breaks later deletions

**Severity: Medium · CONFIRMED**
`web/CategoryController.java:67, 124-155, 77-89`

`deleteCategory` is a soft delete plus a reassignment: find (or create) the category named
`"ზოგადი"` (`FALLBACK_NAME`, line 67), move the deleted category's articles onto it
(`:148-150`), then set the deleted category inactive (`:152-153`).

Two gaps compound:

1. The fallback lookup `findFirstByNameOrderByIdAsc(FALLBACK_NAME)` (`:138`) does **not** filter
   on `is_active`. If "ზოგადი" itself has been deleted (it is an ordinary category — nothing
   stops it), the lookup still returns that now-inactive row, so the guard at `:148` passes and
   articles are reassigned onto an inactive category.
2. `getCategories` (`:84-87`) filters `Category::isActive`, so an inactive category is not in the
   list the UI renders.

**Failure scenario:** someone tidies up and deletes "ზოგადი". Later, any other category is
deleted; its articles are reassigned to the invisible "ზოგადი" row. Those articles now have a
`category_id` that resolves to nothing the frontend knows about — they vanish from category
navigation and filtering while still existing and still being searchable. No error is raised at
any point.

**Suggested fix direction:** filter the fallback lookup on active rows (falling through to the
create branch when none is active), and/or refuse to delete the fallback category outright — it
is load-bearing infrastructure, not content.

---

## BL-08 — Two categories can share one name, and the fallback picks by id

**Severity: Medium · CONFIRMED**
`src/main/resources/db/migration/V2__create_categories.sql:1-14`,
`web/CategoryController.java:91-103, 138`

`V2`'s own header states the choice: *"name/slug are indexed but NOT unique in the source model
— mirrored exactly, not tightened."* So the constraint's absence is a deliberate parity
decision, not an oversight — the question the prompt asks is whether it is *acceptable in
production*, and there is a concrete reason it is not:

- `createCategory` (`:91-103`) performs no duplicate-name check (compare `createTeam`,
  `UserController.java:369-371`, which does check its own uniqueness, and `createUserAdmin`
  `:399-401` for email).
- `deleteCategory` resolves its fallback by **name**, taking the lowest id
  (`findFirstByNameOrderByIdAsc`, `:138`). With two "ზოგადი" rows this silently picks one, and
  articles from different deletions can land in different categories that display identically.

**Failure scenario:** two content admins each create a category called "სერვისები". The UI
renders two visually identical entries; an operator filtering by category sees only half the
articles they expect, with no way to tell why. Nothing in the system reports the collision, and
merging them later requires manual SQL.

**Suggested fix direction:** this is a decision to make explicitly rather than a mechanical fix.
Either add a duplicate-name check to create/update (cheap, no migration, keeps the schema in
parity), or add a unique constraint plus a data-cleanup migration for existing duplicates. Record
which was chosen — this exact question has now been raised twice.

---

## BL-09 — Export downloads are one-shot and tied to one pod

**Severity: Medium · CONFIRMED**
`web/ExportController.java:204-232, 249-268`, `export/ExportJobWorker.java:44-66`,
`export/ExportJobCleanupScheduler.java:38-52`

Two independent problems in the same path:

1. **One-shot download.** `downloadExport` calls `cleanupExport(jobId, path)` (`:222`)
   immediately after reading the bytes, which deletes the file *and* the `export_jobs` row
   (`:261-268`). A browser retry, a refresh, an interrupted transfer, or a second click returns
   `404 ექსპორტი ჯერ არ არის მზად` — a message that says "not ready yet", implying waiting will
   help, when in fact the export is gone and must be regenerated.
2. **Pod-local file, cluster-wide URL.** The worker writes to
   `Path.of(portalProperties.getUploadsDir(), "exports")` (`ExportJobWorker.java:51-54`) — the
   container's own filesystem — while the job row lives in shared Oracle. With more than one
   replica behind the K8s Service, `GET /api/export/download/{jobId}` is load-balanced
   independently of which pod built the file, so it lands on the wrong pod roughly
   (n−1)/n of the time and `Files.readAllBytes` throws `IOException` → the same misleading 404
   (`:217-221`). The same reasoning applies to `/uploads/**` attachments (see Audit 4).

**Failure scenario:** a manager generates the compliance XLSX, the download stalls on a slow
link, they click again — and the export is gone. They regenerate, and on a two-replica
deployment it fails again for a different reason. Nothing in the response distinguishes "still
building", "expired", "wrong pod" or "already downloaded".

**Suggested fix direction:** keep the row until TTL expiry and let `ExportJobCleanupScheduler`
(which already exists for exactly this, `:38-52`) do the deleting; distinguish
processing/expired/failed in the response. For multi-replica, put the artifact where every
replica can reach it — a shared `PersistentVolume` (ReadWriteMany), object storage, or the
`export_jobs` row itself as a BLOB.

Worth flagging as adjacent fragility: `enqueueJob` (`:249-258`) relies on
`saveAndFlush` having *committed* before `@Async` starts, which is true today only because
`exportReadingsXlsx`/`exportReadingsPdf` are **not** `@Transactional` (verified: only
`@RestController` on the class, no class-level `@Transactional`). Adding `@Transactional` to
those methods later would make the worker's `findById(jobId)` (`ExportJobWorker.java:56`) see
nothing on its own connection and silently leave every job stuck in "processing" — with the file
written but never linked. Worth a comment at the call site.

---

## BL-10 — Deletions orphan tag mappings and favourites

**Severity: Medium · CONFIRMED**
`web/ArticleController.java:424-430`, `web/VideoController.java:155-171`,
`web/NewsController.java:176-191`, `V23__create_tags_mapping.sql`, `V9__create_favorites.sql:7`

`tags_mapping` and `favorites` both address content polymorphically via `(item_type, item_id)`
with no FK to the target table (`V9:7` declares only `fk_favorites_user`). `TagSyncService.sync`
is called on create and update (`ArticleController.java:253,310`;
`VideoController.java:126,149`) but **never on delete** — a grep for `tagSyncService` shows no
call in any delete method. No delete path touches `favorites` either.

**Failure scenario:** an article is deleted. Its tags keep appearing in `/api/tags`
(`PlatformController.java:71-79`), so operators filter by a tag and get fewer results than the
count suggested — or none. Users who bookmarked the article keep a favourite that renders with a
null title (`FavoriteController.java:69` resolves the title to `null` for a missing item). Both
grow monotonically; nothing ever cleans them.

**Suggested fix direction:** call `tagSyncService.sync(type, id, "")` and delete matching
`favorites` rows in each delete path (three places), or add a small `ContentDeletionService` that
owns all polymorphic cleanup — this same list is where `required_readings` (BL-02) belongs too,
which argues for one shared place rather than three.

---

## BL-11 — Concurrent edit and restore of the same article can collide

**Severity: Low · SUSPECTED**
`web/ArticleController.java:293-320, 826-844`, `V18__create_article_history.sql:15`,
`domain/Article` (no `@Version`)

`article_history` is unique on `(article_id, version_id)` (`V18:15`). Both `updateArticle`
(`:307`) and `restoreArticleVersion` (`:832`) compute the next version as
`article.getVersion() + 1` from a row read earlier in the same request, and `Article` carries no
JPA `@Version` optimistic-lock column. Two content admins saving the same article at the same
instant can therefore both compute *N+1*; the second insert violates the unique constraint.

Labelled SUSPECTED rather than CONFIRMED deliberately: the window is narrow, and I could not
execute the concurrent case here (no Oracle in this container), so this is a read of the code
plus the constraint rather than an observed failure. `archiveIfMissing` is explicitly race-safe
(its usage at `:826` and `:875` is described as such), which suggests the race was considered for
the archive step but not for the increment.

**Failure scenario:** two editors save within the same second; one gets an opaque HTTP 500 (no
global exception handler) and their edit is lost with no explanation.

**Suggested fix direction:** add `@Version` to `Article` so the second writer gets a clean
optimistic-lock failure that can be mapped to 409 "someone else edited this", which is also the
better user experience.

---

## BL-12 — Read receipts and view logs survive deletion, unlinkable

**Severity: Low · CONFIRMED**
`V19__create_article_read_receipts.sql:15-16`, `V20__create_article_view_logs.sql:13-14`

Both tables use `ON DELETE SET NULL` for `article_id` (and for `operator_id`), unlike their
siblings which cascade. So deleting an article nulls the link on its receipts and view logs
rather than removing them. The rows are retained — which is defensible for compliance evidence —
but nothing can query them any more: every read path filters by `article_id`
(`ArticleController.java:915-960, 1075-1110`).

Recording it because "retained but unreachable" is a decision worth being deliberate about: it is
neither the clean deletion `CASCADE` gives nor usable evidence. If the intent is evidence
retention, the receipts need their own denormalised article title (they already snapshot the
operator's name/email/department — `V19` — so the pattern is established).

---

## BL-13 — Flyway will not start against a pre-existing schema

**Severity: Low · CONFIRMED**
`src/main/resources/application.yml:37-38`

The Flyway configuration is two lines: `locations: classpath:db/migration`. There is no
`baseline-on-migrate`, no `baseline-version`. Flyway therefore refuses to run against a schema
that already contains objects but has no `flyway_schema_history` table, failing the application
at boot.

This is the correct strict default for a greenfield schema and should probably stay — it is
recorded here only because the Oracle instance in the real deployment may not be empty (a
pre-created schema with a table or two from an earlier trial is enough to trigger it), and the
failure message at boot is not self-explanatory to someone who has not seen it before.

**On the prompt's actual idempotency question:** the migrations do **not** need to be individually
idempotent, and the `CREATE TABLE IF NOT EXISTS`-style guards `CLAUDE.md` requires for the Python
`migrate.py` are not needed here. Flyway takes an exclusive lock on `flyway_schema_history`
before applying anything, so two app instances starting simultaneously serialise: one applies,
the other sees the recorded version and skips. Spot-checking the complex ones confirms nothing
breaks that model — `V28` uses `CREATE OR REPLACE` for its function/trigger (safely re-runnable
by hand) and its single `INSERT INTO audit_chain_state` is a once-only seed; `V30` is a
drop-and-recreate of two constraints; `V29`, `V21`, `V27` are plain `CREATE TABLE` + indexes.
The one data-touching statement in the whole set is `V28`'s seed row (verified by grepping all 30
files for `INSERT`/`UPDATE`/`MERGE` — only `V28` matches).

---

## BL-14 — The quiz gate is skipped for a missing article

**Severity: Low · CONFIRMED**
`web/ComplianceController.java:181-190`

`markRead` looks up the article and only consults the gate `if (readingArticle != null)`
(`:184-189`). When the article row is gone — exactly the BL-02 situation — the acknowledgement
succeeds unconditionally, quiz or no quiz.

In isolation this is a sensible fail-open (you cannot take a quiz for an article that no longer
exists), which is why it is Low. It is recorded because it is a second place where the
orphan-required-reading condition is silently tolerated rather than surfaced: fixing BL-02
removes this branch's reachability, and leaving BL-02 unfixed makes this the mechanism by which
ghost items get marked complete.

---

## Checked and found sound

Recorded with the evidence, so the next audit does not redo them:

- **Quiz gate genuinely blocks mark-as-read, on both paths.** Not taken from a comment: there is
  one implementation (`QuizGateChecker.denialFor`) and both callers consult it before writing
  anything — `ArticleController.createArticleReadReceipt:979` (before the receipt upsert at
  `:1000+`) and `ComplianceController.markRead:185-189` (before the `ReadStatus` save at
  `:192-202`). Content admins bypass by design (`QuizGateChecker.java:41-43`). The attempt path
  itself grades server-side (`QuizController.java:220`, `QuizGrader`), stores `passed` from the
  grade rather than from the client (`:231`), and re-checks article visibility before accepting
  an attempt (`:206-209`). Caveat: see BL-03 — the gate is only as strong as the version number
  it keys on.
- **All four "delete-then-recreate" sites were checked individually for the `TagSyncService`
  flush-order trap; none is currently vulnerable.**
  - `search_trigrams` — has the dangerous unique constraint (`uq_search_trigrams`, `V29:20`) and
    is protected: `SearchTrigramRepository.deleteByEntityTypeAndEntityId` is a `@Modifying`
    bulk JPQL delete, not a derived delete, with a javadoc recording that a real `ORA-00001` was
    hit and fixed here.
  - `tags_mapping` — has `uq_tag_mapping_item` (`V23:8`) and is protected by the explicit
    `flush()` at `TagSyncService.java:41`.
  - `quiz_questions` (`QuizController.java:134`) — derived delete followed by inserts, but `V21`
    declares **no** unique constraint, so the insert-before-delete ordering cannot violate
    anything; `quiz_answers` is cleaned by `ON DELETE CASCADE` (`V27:7`).
  - `article_target_departments` (`ArticleController.java:1183-1191`) — same shape, and `V17`
    likewise declares no unique constraint.
- **Article restore round-trips correctly, including across repeated restores.** Traced:
  `restoreArticleVersion` archives the current state first (`archiveIfMissing`, `:826-828`),
  copies title/content from the snapshot, increments the version (`:832`), reindexes (`:835`),
  and writes a fresh history row at the *new* version (`:837-844`) — so
  `(article_id, version_id)` never collides and the version list stays monotonic. Restoring the
  same old version twice produces two distinct new versions rather than a conflict.
  `getArticleVersions` self-heals a missing snapshot for the current version (`:869-877`) using
  the same race-safe helper.
- **Formula-injection sanitisation is complete on the formats that can execute formulas.**
  `CsvExportBuilder.buildDataRow:42` and `XlsxExportBuilder:62` both route every data cell
  through `ExportCellSanitizer` (`=`, `+`, `-`, `@`, tab, CR → prefixed with `'`). Importantly,
  the audit-log CSV export — the Python side's known bug #8 — streams through
  `CsvExportBuilder.buildDataRow` (`AuditLogController.java:174`), so that gap is **closed** in
  the Java port. `PdfExportBuilder` does not sanitise, and does not need to: a PDF has no formula
  engine, so a leading `=` is inert text. Header rows are deliberately unsanitised in both
  builders, matching Python, and headers are server-controlled constants
  (`ExportController.java:106,137,161,178`), never user input.
- **The `@Async` export worker cannot read stale business data.** By construction: all row data
  is passed in as already-materialised primitives (`ExportJobWorker.buildAndStore`'s
  `List<List<Object>> rows`), and the worker issues no business query — only `findById`/`save` on
  its own job row. The job row is committed before the async call because the enqueueing
  controller methods are not transactional (see the fragility note in BL-09).
- **Scheduler survives an exception.** Verified against the actual dependency rather than
  assumed: disassembling `ThreadPoolTaskScheduler` from `spring-context-7.0.8.jar` in the local
  Maven repository shows the `scheduleWithFixedDelay`/`scheduleAtFixedRate` paths calling
  `errorHandlingTask(task, true)` (bytecode `iconst_1`, i.e. `isRepeatingTask = true`), which
  selects Spring's log-and-suppress error handler. An uncaught exception inside
  `ExportJobCleanupScheduler.sweepExpiredJobs` is therefore logged and the next run still
  happens — the scheduler thread is not permanently killed. (Note for Audit 4: the scheduler
  runs on *every* replica, so sweeps duplicate.)
- **Compliance eligibility is single-sourced.** `ComplianceCalculator.isEligible` and
  `computeProgress` are the only implementations, and `ComplianceQueryService` is the only query
  path feeding the dashboard, team stats, `my-progress`, `list_users` and the exports —
  `ComplianceCalculator`'s javadoc explains why the Python two-copy split was deliberately not
  carried over. The one place management exclusion is missed is BL-05.

---

## შემაჯამებელი მიმოხილვა (არატექნიკური)

ეს ნაწილი შეეხება არა უსაფრთხოებას, არამედ იმას, **სწორად მუშაობს თუ არა ბიზნეს-წესები** და
რამდენად სანდოა ბაზაში დაგროვილი მონაცემი. სულ 14 შენიშვნა: **0 კრიტიკული, 3 მაღალი,
7 საშუალო, 4 დაბალი.**

**სამი მთავარი პრობლემა.**

1. **სიახლის (news) წაშლა არ მუშაობს, თუ ის ოდესმე დარედაქტირდა.** ბაზაში ერთი კავშირი
   არასწორადაა აღწერილი: სიახლის „ისტორიის" ჩანაწერები ხელს უშლის თავად სიახლის წაშლას.
   შედეგად კონტენტის ადმინისტრატორი აჭერს „წაშლას" და იღებს გაუგებარ შეცდომას (500).
   წაშლა შესაძლებელია მხოლოდ ისეთი სიახლის, რომელიც არასდროს შეცვლილა — ამიტომ სწრაფი
   შემოწმებისას პრობლემა შეიძლება საერთოდ არ გამოჩნდეს, რეალურ მუშაობაში კი ყოველთვის ჩანს.
   იგივე ტიპის ხარვეზი უკვე ერთხელ გამოსწორდა სტატიებისთვის — სიახლეებამდე უბრალოდ არ მისულა.

2. **სტატიის წაშლის შემდეგ „სავალდებულოდ გასაცნობი" ჩანაწერი სამუდამოდ რჩება.** თუ სტატია
   წაიშალა, მისი სავალდებულო დავალება ბაზაში რჩება და ოპერატორებს ეჩვენებათ როგორც
   „Item #482 / Content not available." — ანუ დავალება, რომლის წაკითხვაც ფიზიკურად შეუძლებელია.
   უარესი: ეს ჩანაწერი აგრძელებს დათვლას პროცენტში, ანუ **მთელი დეპარტამენტის შესრულების
   მაჩვენებელი სამუდამოდ ეცემა** მიზეზით, რომელიც ეკრანზე არ ჩანს. თანამშრომელს შეუძლია
   „წავიკითხე"-ს დაჭერით მოიშოროს — რაც კიდევ უფრო ცუდია, რადგან ხალხს ვაჩვევთ დაუდასტუროს
   მასალა, რომელიც არ წაუკითხავს.

3. **ავტომატური შენახვა (autosave) ცვლის გამოქვეყნებულ სტატიას „ვერსიის" გაზრდის გარეშე.**
   სისტემა იმახსოვრებს, ვინ რომელი ვერსია წაიკითხა და ვინ ჩააბარა ქვიზი — და ეს მთელი
   მექანიზმი ვერსიის ნომერს ეყრდნობა. ავტოშენახვისას ტექსტი იცვლება, ნომერი კი რჩება.
   შედეგად: ადმინისტრატორმა შეიძლება არსებითად გადაწეროს ინსტრუქცია, ხოლო ანგარიშში კვლავ
   ეწეროს, რომ ყველამ წაიკითხა და ქვიზიც ჩააბარა — მაშინ როცა ახალი ტექსტი არავის უნახავს.

**საშუალო შენიშვნებიდან რაც ღირს ცოდნა.** სავალდებულო დავალების რედაქტირებისას შესაძლებელია
სხვა სტატიაზე გადამისამართება — და ყველას „წაკითხულის" სტატუსი ავტომატურად გადადის ახალ
სტატიაზე (ანუ ახლადგამოქვეყნებული მასალა მაშინვე 100%-ით შესრულებულად ჩანს). ვადის გასვლას
**არანაირი შედეგი არ მოჰყვება** — არც შეხსენება, არც ესკალაცია: პითონის დროინდელი ყოველდღიური
შემხსენებელი პროცესი Java-ში საერთოდ არ გადმოტანილა. მენეჯერები/ადმინები იღებენ შეტყობინებას
ახალი სავალდებულო მასალის შესახებ, რომელიც შემდეგ მათ სიაში საერთოდ არ ჩანს. კატეგორიებში
ორი ერთნაირი სახელი შეიძლება არსებობდეს (მომხმარებელი ვერ არჩევს, რომელია რომელი), ხოლო
სარეზერვო კატეგორიის („ზოგადი") წაშლის შემდეგ სტატიები უხილავ კატეგორიაში ხვდება. ექსპორტის
ფაილი მხოლოდ ერთხელ ჩამოიტვირთება — გვერდის განახლება ან განმეორებითი დაჭერა უკვე „არ არის
მზად"-ს აბრუნებს, თუმცა სინამდვილეში ფაილი წაშლილია.

**რაც შემოწმდა და წესრიგშია.** ქვიზის „კარიბჭე" მართლაც მუშაობს — ორივე გზაზე, სადაც
„წავიკითხე" ინიშნება, და შეფასება სერვერზე ხდება, არა ბრაუზერში. ვერსიების აღდგენა სწორად
მუშაობს, მათ შორის რამდენჯერმე ზედიზედ აღდგენისას. Excel/CSV ექსპორტში ჩაშენებულია დაცვა
ცნობილი „ფორმულის შეტევისგან" — და ის ადგილიც დაცულია, სადაც პითონის მხარეს ხარვეზი იყო.
ბაზის მიგრაციები უსაფრთხოა ორი სერვერის ერთდროული გაშვებისასაც (Flyway ჩაკეტვას იყენებს).
დაგეგმილი ფონური სამუშაო შეცდომის შემთხვევაში **არ კვდება** — ეს სპეციალურად შევამოწმე
ბიბლიოთეკის რეალურ კოდში, არა ვარაუდით.

**რეკომენდაცია რიგითობით:** BL-01 (სიახლის წაშლა) და BL-02 (მოჩვენებითი სავალდებულო
დავალებები) გასაშვებამდე — ორივე პირდაპირ ეხება ყოველდღიურ მუშაობას. BL-03 (ავტოშენახვა და
ვერსია) იმავე რიგში, რადგან სწორედ ის განსაზღვრავს, რამდენად ვენდობით შესრულების ანგარიშებს.
დანარჩენი შეიძლება გაშვების შემდეგ დაიგეგმოს.
