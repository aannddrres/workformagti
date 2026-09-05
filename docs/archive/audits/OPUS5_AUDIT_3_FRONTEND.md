> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# OPUS5 Audit 3 — Angular frontend correctness & UX

**Date:** 2026-08-14
**Scope:** `angular-frontend/src/app/` — 17 feature areas, shared components, core services,
guards, i18n catalogs.
**Mode:** read-only. No source file was modified, nothing committed, nothing pushed.
**Context read first:** `CLAUDE.md`.

## Verification method

- Every `.subscribe(` call site in the app was enumerated mechanically with a brace-matching
  parser (not a line grep, which produces false positives on single-line subscribes):
  **97 subscribe calls total**, of which 11 have no error handler and 14 pass an empty
  `error: () => {}`. The full list is in FE-04.
- The i18n catalogs were parsed and flattened, and every `| translate` / `translate.instant(...)`
  key referenced in any template or component was resolved against both catalogs.
- `node_modules/` was absent at audit start; `npm ci` was run to make the workspace testable.
  Findings below come from source analysis — none of them depends on a test run.

## Findings summary

| # | Severity | Status | Finding |
|---|----------|--------|---------|
| FE-01 | **High** | CONFIRMED | News and video deletion failures are silently swallowed — the exact bug just fixed for articles, unfixed in two sibling tables |
| FE-02 | **High** | CONFIRMED | Activating/deactivating a user fails silently — the admin sees no error and no state change |
| FE-03 | **High** | CONFIRMED | Export polling never unsubscribes; a stuck job polls the backend forever, surviving navigation away from the page |
| FE-04 | Medium | CONFIRMED | 25 of 97 subscribe call sites give the user no feedback on failure (11 with no handler, 14 with an empty one) |
| FE-05 | Medium | CONFIRMED | No 401 handling anywhere — an expired session produces silent failures until the user happens to navigate |
| FE-06 | Medium | CONFIRMED | Managers cannot reach the audit page even though the backend grants them department-scoped access |
| FE-07 | Medium | CONFIRMED | The news department filter compares exact strings and one option's value is truncated — it silently returns nothing |
| FE-08 | Low | CONFIRMED | A handful of light-only colour classes remain (icons, one checkbox) |
| FE-09 | Low | CONFIRMED | Two small mobile-width rough edges below 360px |
| FE-10 | Low | CONFIRMED | 5 unit spec files for 17 feature areas |

**Totals: Critical 0 · High 3 · Medium 4 · Low 3 (10 findings).**

---

## FE-01 — Deleting a news item or video fails in silence

**Severity: High · CONFIRMED**
`features/admin-content/news-admin-table/news-admin-table.ts:66`,
`features/admin-content/videos-admin-table/videos-admin-table.ts:66`

Both are one line, and both are identical:

```ts
this.newsService.remove(item.id).subscribe({ next: () => this.load(), error: () => {} });
```

This is the same defect that was just fixed in `admin-content-page.ts`'s
`deleteArticle`/`toggleArchive`/`bulkArchive`. The article table was fixed; the news table and
the videos table beside it were not.

The backend makes this worse than a generic "the request might fail" concern, because for news
the failure is **the normal case**: `DELETE /api/news/{id}` returns HTTP 500 for any news item
that was ever edited (Audit 2, BL-01 — `news_history`'s foreign key has no `ON DELETE CASCADE`).
So the two defects compose into the worst possible user experience.

**Failure scenario:** a content admin confirms the "delete news item?" dialog. The request 500s.
`error: () => {}` discards it. `this.load()` never runs, so the row stays on screen — which reads
as "the click didn't register". They click again. And again. Nothing tells them the deletion is
failing, and nothing in the UI hints why.

**Suggested fix direction:** apply the same fix already made in `admin-content-page.ts` — set an
error signal and render it — to both tables. Given three occurrences of one bug in one folder, a
shared helper (or an error-handling operator applied in the service layer) would stop the fourth.

---

## FE-02 — Deactivating a user fails in silence

**Severity: High · CONFIRMED**
`features/admin-users/admin-users-page.ts:108-113`

```ts
toggleStatus(user: AdminUser): void {
  const next = !user.is_active;
  this.usersService.updateStatus(user.id, next).subscribe({
    next: () => this.loadUsers(),
    error: () => {}
  });
}
```

Rated above the other empty handlers in FE-04 because of what this particular control does:
deactivating an account is the app's **only** way to cut off access, and the backend rejects some
requests by design — `UserController.updateUserStatus:246-249` returns 400 for self-deactivation,
and the endpoint is `system_admin`-only. Every one of those rejections is discarded here.

**Failure scenario:** an employee leaves, or an account is suspected compromised. The admin
opens the user list and clicks deactivate. The request fails (validation, an expired token, a
network blip — the reason does not matter because none is shown). `loadUsers()` never runs, so
the row still displays "active"; the admin reasonably reads that as the toggle not having taken
effect yet, or as a UI quirk, and moves on. The account stays active and nobody knows.

**Suggested fix direction:** surface the backend's `detail` message — `updateUserStatus`'s 400
already carries a human-readable Georgian explanation that the UI is currently throwing away.
Re-running `loadUsers()` in the error branch would at least keep the displayed state truthful.

---

## FE-03 — Export polling runs forever and outlives the page

**Severity: High · CONFIRMED**
`core/services/export.service.ts:35-40`, `features/team-stats/team-stats-page.ts:210-240`;
repo-wide: `takeUntilDestroyed` appears **zero** times

```ts
pollUntilDone(jobId: string, intervalMs = 1500): Observable<ExportStatus> {
  return interval(intervalMs).pipe(
    switchMap(() => this.status(jobId)),
    takeWhile((s) => s.status === 'processing', true)
  );
}
```

The stream terminates only when the status stops being `processing`. Its single consumer
(`team-stats-page.ts:215`) subscribes with no teardown, and a grep for `takeUntilDestroyed`,
`DestroyRef` or `unsubscribe` across the whole app returns nothing — the only `ngOnDestroy` in
the codebase is in `rich-text-editor.ts:126`, and it does something unrelated.

Two ways this goes wrong, and the second is the one that bites:

1. **Navigation does not stop it.** Start an XLSX export, then click away to another page while
   it builds. The component is destroyed; the interval keeps firing every 1.5 s, and each tick
   still calls `this.asyncExport.set(...)` on the dead component's signals.
2. **A job that never completes polls forever.** `takeWhile` has no timeout and no attempt cap.
   Audit 2 (BL-09) documents exactly how a job stays `processing` indefinitely: on a
   multi-replica deployment the worker writes the file on one pod while the status request is
   load-balanced to another, so the row is never seen as completed. The result is one HTTP
   request every 1.5 s — 2,400 per hour, per user who ever clicked export — for the remaining
   life of the browser tab.

**Failure scenario:** three managers click "export XLSX" on Monday morning, leave the tab open,
and go about their day. By Monday evening the backend has served roughly 20,000 status requests
that no human is waiting for, and each one runs `requireReportsExport` plus a database lookup.
Nobody sees an error; the spinner just never stops.

**Suggested fix direction:** pipe the polling subscription through `takeUntilDestroyed()`
(inject `DestroyRef` in the component), and give `pollUntilDone` a hard stop —
`takeWhile(...)` plus `take(n)` or `timeout(...)` — so a stuck job surfaces as a failure the user
can act on rather than as an invisible loop. Worth applying `takeUntilDestroyed` as a general
convention while doing it: nothing in the app currently unsubscribes from anything.

---

## FE-04 — A quarter of all requests fail without telling the user

**Severity: Medium · CONFIRMED**
Enumerated across all 97 `.subscribe(` call sites

**Empty error handler (`error: () => {}`) — 14 sites:**

| File | Line(s) | What the user loses |
|---|---|---|
| `admin-content/news-admin-table/news-admin-table.ts` | 66 | delete failure (FE-01) |
| `admin-content/videos-admin-table/videos-admin-table.ts` | 66 | delete failure (FE-01) |
| `admin-users/admin-users-page.ts` | 111 | activate/deactivate failure (FE-02) |
| `admin-users/admin-users-page.ts` | 83 | group-leader filter list silently empty |
| `admin-content/admin-content-page.ts` | 113 | category dropdown silently empty |
| `admin-content/article-edit-drawer/article-edit-drawer.ts` | 90, 136, 161, 170 | categories, article load, mandatory-reading state, quiz questions |
| `admin-content/news-edit-drawer/news-edit-drawer.ts` | 83, 93 | news load, mandatory-reading state |
| `admin-content/video-edit-drawer/video-edit-drawer.ts` | 54, 84, 92 | categories, video list, mandatory-reading state |

The edit-drawer cases deserve particular attention: `article-edit-drawer.ts:161` loads whether
the article is a *mandatory reading* and its due date. If that request fails, `isMandatory` stays
`false` and the checkbox renders unchecked — i.e. the form shows the article as **not** mandatory
when it may well be. Saving from that state can clear a real compliance obligation. The same
pattern repeats in the news and video drawers.

**No error handler at all — 11 sites:**

`core/services/favorites.service.ts:40` · `category-view/category-view-page.ts:56` ·
`dashboard/dashboard-page.ts:57,60` · `knowledge-base/knowledge-base-page.ts:51,54,61` ·
`news/news-page.ts:78` · `reading/my-readings-page.ts:116` · `shell/app-shell.ts:73,108`

Two of these are benign and are noted so they are not "fixed" pointlessly:
`my-readings-page.ts:116` is safe because `ComplianceService.markRead` maps failures into a
result object (`result.ok` / `result.quizRequired`) rather than an error channel, and
`app-shell.ts:73` subscribes to `router.events`, which does not error. The rest genuinely drop
failures — an unhandled RxJS error also aborts the stream, so for example a failed category load
on the dashboard leaves the page permanently empty with no retry path.

**Suggested fix direction:** treat "no feedback" as the bug rather than fixing sites one at a
time — a shared error-handling operator in the service layer, or a global `HttpInterceptor` that
raises a toast for any 4xx/5xx not explicitly handled, would close all 25 at once and prevent
the 26th. Note the pattern's history: this class of bug has now been found and fixed three times
in this app.

---

## FE-05 — An expired session produces silence, not a login prompt

**Severity: Medium · CONFIRMED**
`core/auth/auth.interceptor.ts:8-14`, `core/auth/auth.guard.ts:7-15`,
`core/auth/auth.service.ts:87`

`authInterceptor` only attaches the bearer token; it inspects no response. There is no other
interceptor (`core/http/` contains only `api-base-url.interceptor.ts`) and no global error
handler, so an HTTP 401 is delivered to whichever `subscribe` made the call — which, per FE-04,
is frequently a handler that discards it.

Partially mitigated, and worth stating precisely: `AuthService` does check the JWT's `exp`
claim (`auth.service.ts:87`) and `authGuard` consults `isAuthenticated()`, so the moment the user
*navigates*, they are correctly redirected to `/login`. The gap is the time before that.

**Failure scenario:** the token's 60-minute lifetime expires while a manager is reading the team
dashboard. Every background call now 401s. Widgets show "load error" or stay empty; the export
button appears to do nothing. The page gives no indication that the session has ended, and
nothing prompts a re-login until they click a router link.

**Suggested fix direction:** add an interceptor that catches 401 and routes to `/login` with the
current URL as `returnUrl` (the guard already supports that parameter), clearing the stored token
on the way.

---

## FE-06 — Managers are locked out of the audit log they are entitled to

**Severity: Medium · CONFIRMED**
`app.routes.ts:84-105` (the `admin` route's `roleGuard(ADMIN_OR_CONTENT_ADMIN)`),
`core/auth/role.guard.ts:15-19`, vs backend `AuditLogController.java:74-114, 205-207, 237-247`
and `Permission.java:45`

The backend deliberately supports managers reading the audit log: `requireSystemAudit` gates on
the `system.audit` permission, `Permission.defaultsFor(MANAGER)` grants it, and
`scopeDepartment` (`AuditLogController.java:205-207`) exists specifically to pin a manager to
their own department. `Permission.java`'s javadoc (lines 74-92) records that this grant was
added on purpose after a live parity check, precisely so that scoping code is reachable.
`UserController.java:92` even ships a `can_view_audit_log` flag to the frontend for this.

The frontend does not use any of it. `/admin/**` is gated on `roleGuard(['admin',
'content_admin'])`, so a manager is redirected to `/` before the audit child route is evaluated,
and the `can_view_audit_log` flag is never consulted by a guard. `role.guard.ts:15-19` documents
this as unfinished work ("*the audit sub-panel's extra `can_view_audit_log` permission-based
exception … isn't modeled here yet*"), which is honest but means the feature is currently dead
on the manager side.

**Failure scenario:** a group manager is told they can review their own team's activity log. The
menu entry is absent, and typing `/admin/audit` bounces them to the dashboard. The
department-scoping logic on the backend, written for exactly this user, never executes in
production.

**Suggested fix direction:** replace the coarse role gate on the audit route with a
permission-aware guard driven by `can_view_audit_log` from `/api/users/me` (already fetched), and
show the nav entry on the same condition. Note the parallel to Audit 1's SEC-06: the app has a
permission model that the UI does not consult.

---

## FE-07 — The news department filter matches nothing

**Severity: Medium · CONFIRMED**
`features/news/news-page.html:22-27`, `features/news/news-page.ts:55-59`,
vs `java-backend/.../stats/DepartmentBuckets.java:22,33-41`

The filter is a strict string comparison in the client:

```ts
if (dept && item.target_department !== dept) return false;   // news-page.ts:58
```

against these option values (`news-page.html:24-26`):
`"ტექნიკური"`, `"საინფო"`, `"ოფისი"`.

Two independent problems:

1. **`"საინფო"` is not a department.** The canonical whitelist on the backend is
   `List.of("ტექნიკური", "საინფორმაციო", "ოფისი")` (`DepartmentBuckets.java:22`). The backend
   tolerates the truncated form only as a legacy *prefix* alias
   (`raw.startsWith("საინფო")`, line 33) — a tolerance the frontend's `!==` does not share. So
   selecting "საინფორმაციო" in the dropdown filters for a string that most rows will not equal.
2. **Exact equality against grouped departments.** Real department strings carry group suffixes
   (`"ტექნიკური — ჯგუფი 03"`, see `DepartmentMatcher`); every department comparison on the
   backend is prefix-aware for that reason. A news item targeted at a sub-group will not match
   the parent-department filter here.

**Failure scenario:** an operator filters the news list by their own department and gets an empty
list, or a partial one, with no explanation. Since the empty state is indistinguishable from "no
news for this department", nobody reports it as a bug.

**Suggested fix direction:** use the canonical `"საინფორმაციო"` as the option value, and replace
`!==` with a prefix-aware comparison mirroring `DepartmentMatcher.matches` (a small shared helper
next to the existing `shared/department-badge.ts` would serve both this and any future filter).
Better still, drive the options from a backend-supplied list so the two catalogs cannot drift.

---

## FE-08 — Remaining light-only colour classes

**Severity: Low · CONFIRMED**

Every `.html` under the feature folders was scanned for light-mode colour utilities
(`bg-white`, `bg-gray-*`, `text-gray-*`, `border-gray-*`) on lines carrying no `dark:` variant.
The result is small and concentrated:

| File | Lines | What |
|---|---|---|
| `admin-content/admin-content-page.html` | 107, 125, 131, 134, 138, 140 | `text-gray-400` icons in the row action menu |
| `admin-content/article-edit-drawer/article-edit-drawer.html` | 32, 79, 147, 151, 171 | char-counter label, paperclip icon, chevron, section label |
| `admin-content/news-admin-table/news-admin-table.html` | 25, 30, 33 | row icons / action buttons |
| `admin-content/videos-admin-table/videos-admin-table.html` | 25, 30, 33 | row icons / action buttons |
| `admin-content/news-edit-drawer/news-edit-drawer.html` | 63 | paperclip icon |
| `admin-users/user-edit-modal.html` | 63 | `border-gray-300` on a checkbox |
| `shell/app-shell.html` | 21 | sidebar section label |

`text-gray-400` is a mid-tone that stays legible on both backgrounds, so none of these is
broken — they are lower-contrast than their surroundings in dark mode rather than invisible. The
`border-gray-300` checkbox in `user-edit-modal.html:63` is the most noticeable (a light border on
a dark panel).

Worth recording that the prompt's specific worry did not materialise: **messaging, the
readings/quiz-taker UI and team-stats have zero light-only lines** — the newest features are the
best-covered ones.

---

## FE-09 — Two small mobile rough edges

**Severity: Low · CONFIRMED**

The newest features were checked specifically at narrow widths, as asked. Overall they hold up:
`messaging-page.html:1` and `my-readings-page.html:1` use `mx-auto w-full max-w-[900px] px-4 …
md:px-6` (a max-width, not a fixed one), header rows use `flex flex-wrap`, the export button row
is `flex flex-wrap gap-3` (`team-stats-page.html:129`), and the two tables in `team-stats` are 3
and 2 columns with `truncate` on the long cell — all fine at 360 px.

Two things do stand out:

- `messaging/broadcast-modal/broadcast-modal.html:17` — `grid grid-cols-2 gap-3` with no
  responsive prefix. Two `<select>` controls side by side inside a modal at 320 px leaves roughly
  140 px each, which truncates the department/role labels.
- Page headings use `text-4xl uppercase tracking-wider` (e.g. `messaging-page.html:3`). At 36 px
  with letter-spacing, a Georgian heading wraps to two or three lines on a small phone, pushing
  content down. It wraps rather than overflowing, so this is a judgement call rather than a
  defect.

**Suggested fix direction:** `grid-cols-1 sm:grid-cols-2` for the broadcast modal;
`text-2xl md:text-4xl` for page headings if the wrapping is considered undesirable.

---

## FE-10 — Almost no unit tests

**Severity: Low · CONFIRMED**

The whole app has **5** `.spec.ts` files: `app.spec.ts`, the three admin-content edit drawers,
and `quiz-taker-modal.spec.ts` — against 17 feature areas, 12 core services, and 10 shared
components. There are 5 Playwright e2e specs (`e2e/auth`, `quiz-gate`, `delete-cascade`,
`department-visibility`, `article-due-date`), which is a sensible set of journeys but no
substitute for unit coverage.

Concretely relevant to this audit: every finding above is the kind a test would have caught and
pinned — an error-path test on the delete buttons (FE-01/FE-02), a subscription-teardown test
(FE-03), a filter test with a grouped department string (FE-07). The three edit drawers that
*do* have specs are, not coincidentally, where the NG0951 crash was found and fixed.

**Suggested fix direction:** no need for blanket coverage; adding an error-path case alongside
each happy-path case in the components that mutate data (the two admin tables, the users page,
the export flow) would be a handful of tests covering most of what this audit found.

---

## Checked and found sound

Recorded with the evidence, so the next audit does not redo them:

- **No remaining `viewChild.required()` / `input.required()` traps.** All 14 usages were checked
  individually against both failure modes.
  - *Conditionally-rendered required view child (NG0951):* the two `viewChild.required` calls are
    `rich-text-editor.ts:64` → `#editorHost`, which is `rich-text-editor.html:2`, the component's
    unconditional root child (the first `@if` in that file is at line 4, after it); and
    `article-edit-drawer.ts:53` → `RichTextEditor`, rendered unconditionally at
    `article-edit-drawer.html:34` (the drawer's own `@if` blocks all start at line 77, and the
    drawer as a whole is instantiated by the parent, `admin-content-page.html:189`, so the child
    exists whenever the component does). The previously-fixed NG0951 has not regressed.
  - *Required signal read in a constructor body (NG0950):* every one of the 12
    `input.required()` declarations is read only inside `computed()` or `effect()` —
    `progress-ring.ts:20-22`, `favorite-star.ts:28,33`, `quiz-taker-modal.ts:37-42`,
    `article-version-history-overlay.ts:45,57,61-66`, `user-edit-modal.ts:54,69-71`. Two of them
    carry an explicit comment naming NG0950 as the reason for the `effect()`.
- **i18n is complete and the duplicate-key collision is gone.** Both catalogs parse to exactly
  **512 flattened keys** with **zero** difference in either direction. Every one of the **441**
  distinct keys referenced by a `| translate` pipe or `translate.instant(...)` call resolves in
  both files — no missing keys, no orphans in use. Parsing both files with a duplicate-detecting
  hook found **no duplicate top-level keys in either**, so the known `users` collision has been
  resolved; there is no runtime impact left to assess. The only Georgian string literals in any
  template are three `<option value="…">` attributes in `news-page.html:24-26`, which are data
  values rather than display text (their labels *are* translated) — see FE-07 for the separate
  problem with one of those values.
- **Route guards are real, not cosmetic.** Every admin/manager route has a functional
  `canActivate`, so typing a URL directly is blocked, not merely hidden:
  `/manager` → `roleGuard(['admin','manager'])`; `/reading` → `roleGuard(undefined,
  ['admin','content_admin','manager'])` (deny-list, operator-only); `/admin/**` →
  `roleGuard(['admin','content_admin'])` with `/admin/users` and `/admin/roles` further narrowed
  to `roleGuard(['admin'])`; the whole authenticated tree sits under `authGuard`
  (`app.routes.ts:36-38`). The guards are also correct in the "not logged in" case
  (`role.guard.ts:27-29` redirects to `/login` rather than falling through). The one substantive
  gap is FE-06 — a route that is *more* restrictive than the backend, not less.
- **Dark mode on the newest features is complete** — see FE-08; messaging, reading/quiz-taker and
  team-stats scanned clean.

---

## შემაჯამებელი მიმოხილვა (არატექნიკური)

ეს ნაწილი ეხება საიტის იმ მხარეს, რომელსაც მომხმარებელი უშუალოდ ხედავს — ღილაკები, შეცდომების
შეტყობინებები, მობილურზე გამოჩენა, ქართული/ინგლისური თარგმანები. სულ 10 შენიშვნა:
**0 კრიტიკული, 3 მაღალი, 4 საშუალო, 3 დაბალი.**

**სამი მთავარი პრობლემა — და სამივე ერთსა და იმავე ჩვევას ეხება: შეცდომა ხდება, მაგრამ
მომხმარებელს არაფერი ეუბნება.**

1. **სიახლის ან ვიდეოს წაშლა ჩუმად ჩავარდება.** ღილაკზე დაჭერისას თუ სერვერი შეცდომას
   აბრუნებს, ეკრანზე არაფერი იცვლება — ჩანაწერი ადგილზე რჩება, შეცდომა არსად ჩანს.
   ადმინისტრატორი ბუნებრივად ფიქრობს, რომ დაწკაპუნება არ „ჩაითვალა" და ისევ და ისევ აჭერს.
   ეს განსაკუთრებით საშიშია იმიტომ, რომ სიახლის წაშლა სერვერზე **მართლაც** არ მუშაობს ყველა
   იმ სიახლისთვის, რომელიც ოდესმე დარედაქტირდა (იხ. მე-2 ანგარიში) — ანუ ეს ორი ხარვეზი
   ერთმანეთს ამძაფრებს.

2. **მომხმარებლის გათიშვა/ჩართვა ჩუმად ჩავარდება.** ეს ის ერთადერთი ღილაკია, რომლითაც
   თანამშრომელს პორტალზე წვდომა ეზღუდება. თუ მოთხოვნა ვერ შესრულდა, ადმინისტრატორი ვერაფერს
   იგებს, სიაში კი ანგარიში კვლავ „აქტიურად" ჩანს. შედეგად შესაძლებელია, რომ წასული
   თანამშრომლის ანგარიში დარჩეს ჩართული და ამის შესახებ არავინ იცოდეს.

3. **ექსპორტის მოლოდინი უსასრულოდ გრძელდება.** როცა მომხმარებელი Excel/PDF ექსპორტს უშვებს,
   პროგრამა ყოველ 1.5 წამში ეკითხება სერვერს „მზად არის?" — და ეს კითხვა **არასდროს ჩერდება**,
   თუ ფაილი რაიმე მიზეზით არ დასრულდა. სხვა გვერდზე გადასვლაც კი არ აჩერებს. ერთი ღია
   ბრაუზერის ჩანართი საათში ~2400 ზედმეტ მოთხოვნას აგზავნის სერვერზე, სპინერი კი უსასრულოდ
   ტრიალებს.

**საშუალო შენიშვნები.** საერთო ჯამში პროგრამაში **97 სერვერული მოთხოვნიდან 25-ს საერთოდ არ
აქვს შეცდომის დამუშავება** — ანუ ყოველი მეოთხე. მათგან განსაკუთრებით ღირს ყურადღება
სარედაქტირო ფანჯრები: თუ „სავალდებულო მასალის" სტატუსის ჩატვირთვა ჩავარდა, ჩამრთველი
გამორთულად გამოჩნდება — თითქოს მასალა სავალდებულო არ არის — და შენახვისას ეს რეალური
ვალდებულება შეიძლება წაიშალოს. სესიის ვადის გასვლისას (1 საათი) გვერდი უბრალოდ ცარიელდება,
შესვლის ფანჯარა არ ჩნდება, სანამ მომხმარებელი სხვა გვერდზე არ გადავა. მენეჯერებს **არ
მიუწვდებათ ხელი აუდიტის ჟურნალზე**, თუმცა სერვერზე ეს უფლება სპეციალურად მათთვის გაკეთდა
(თავისი დეპარტამენტის ფარგლებში) — ანუ მზა ფუნქციონალი უბრალოდ მიუწვდომელია. და სიახლეების
დეპარტამენტის ფილტრი პრაქტიკულად არ მუშაობს: ერთი ვარიანტის მნიშვნელობა შემოკლებულია
(„საინფო" ნაცვლად „საინფორმაციო"), ხოლო შედარება ზუსტი დამთხვევით ხდება — შედეგად სია ცარიელი
რჩება და მიზეზი არსად ჩანს.

**რაც შემოწმდა და წესრიგშია — და საკმაოდ კარგადაა.** თარგმანები **იდეალურ მდგომარეობაშია**:
ქართულ და ინგლისურ ფაილში ზუსტად 512-512 ჩანაწერია, სხვაობა ნულია, და ყველა 441 გამოყენებული
ტექსტი ორივე ენაზე არსებობს. ადრე ცნობილი „users" გასაღების დუბლირება **აღარ არსებობს** —
შევამოწმე ორივე ფაილი, დუბლიკატი არ არის. მუქი (dark) რეჟიმი ახალ გვერდებზე — შეტყობინებები,
სავალდებულო მასალა, ქვიზი, გუნდის სტატისტიკა — **სრულად დაფარულია**; მცირე ხარვეზები მხოლოდ
რამდენიმე ხატულაზეა. მობილურზე (320-360px) ეს გვერდები ნორმალურად ეწყობა. და ყველა
ადმინისტრატორული გვერდი **რეალურად დაცულია** — მისამართის ხელით აკრეფით შესვლა შეუძლებელია,
და არა მხოლოდ მენიუშია დამალული.

**რეკომენდაცია რიგითობით:** FE-01 და FE-02 (ჩუმი ჩავარდნები კრიტიკულ ღილაკებზე) ჯერ — ეს
რამდენიმე ხაზის შესწორებაა. შემდეგ FE-03 (უსასრულო მოთხოვნები). FE-04 კი ღირს ერთი საერთო
გადაწყვეტით დაიხუროს (ერთი საერთო „შეცდომის მაჩვენებელი" ყველა მოთხოვნისთვის), რადგან ეს
ერთი და იგივე ხარვეზი უკვე მესამედ სწორდება ცალ-ცალკე.
