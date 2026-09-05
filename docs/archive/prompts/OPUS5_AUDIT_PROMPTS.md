> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# Opus 5 კოდის აუდიტის პრომპტები

ეს ფაილი შეიცავს 4 ცალკეულ, თვითკმარ პრომპტს Opus 5-ისთვის — თითოეული
გაშვებულია ცალკე Claude Code სესიაში (Opus 5 არჩეული, ამ repo-ს root-ში:
`C:\Projects\Magti base`), ამ საუბრის კონტექსტის გარეშეც მუშაობს.

**თანმიმდევრობა:** 1 (უსაფრთხოება/RBAC) → 2 (ბექენდის ბიზნეს-ლოგიკა) →
3 (Angular) → 4 (production-readiness). თითო პრომპტი ცალკე ჩაუშვი,
ბოლომდე დაელოდე, მერე შემდეგი.

**მნიშვნელოვანი:** ეს მხოლოდ აუდიტია — არაფერს არ ასწორებს, მხოლოდ წერს
ანგარიშს `docs/OPUS5_AUDIT_*.md` ფაილებში. დასრულების შემდეგ ეს ფაილები
მოუტანე Claude-ს (ჩვეულებრივ სესიაში) — ერთად გადავხედავთ, დავადგენთ
რომელი მართლა რეალურია, და გასწორდება უსაფრთხოდ.

## ერთი-პრომპტიანი გაშვება (რეკომენდებული)

არ არის საჭირო ქვემოთმოცემული 4 პრომპტის ცალ-ცალკე კოპირება. საკმარისია
ერთხელ ჩაუგდო Opus 5-ს (ახალი Claude Code სესია, Opus 5 არჩეული, ამ
repo-ს root-ში) ეს ერთი ტექსტი, და თავად წაიკითხავს და თანმიმდევრობით
გაუშვებს ოთხივეს:

```
გახსენი docs/OPUS5_AUDIT_PROMPTS.md ამ repo-ში (C:\Projects\Magti base).
ფაილი შეიცავს 4 დამოუკიდებელ, თვითკმარ აუდიტის დავალებას, სათაურით
"Prompt 1" — "Prompt 4". თითოეული სრულად შეასრულე, თანმიმდევრობით
(1→2→3→4), როგორც ცალკე, დამოუკიდებელი დავალება -- არცერთი არ გამოტოვო
და ერთმანეთში არ შეაერთო ისე, რომ რომელიმეს დეტალები დაკარგო. თითოეული
პრომპტი თავად განსაზღვრავს საკუთარ output ფაილს (docs/OPUS5_AUDIT_1_
SECURITY.md და ა.შ.) -- ზუსტად ისე დაწერე, როგორც შიგნითაა მითითებული.
ნუ გაჩერდები დადასტურების მოლოდინში დავალებებს შორის -- ოთხივე
ავტონომიურად, ზედიზედ გაუშვი.

ყველა 4 დავალებაზე მოქმედი წესები: მხოლოდ წაკითხვა/აუდიტია -- არცერთ
საწყის კოდს არ შეცვლი, არაფერს არ დააკომიტებ და push არ გააკეთებ. ყოველი
მიგნება უნდა იყოს რეალურად წაკითხული/გადამოწმებული კოდიდან (საჭიროებისას
mvn test-ის ან ng test-ის გაშვებით), არა ვარაუდი -- აღნიშნე CONFIRMED თუ
SUSPECTED. არცერთი გრძელვადიანი/interactive სერვერი არ ჩართო.

ოთხივეს დასრულების შემდეგ დაწერე დამატებითი ფაილი
docs/OPUS5_AUDIT_SUMMARY.md, სადაც ერთ მოკლე დოკუმენტში შეაერთებ ოთხივე
ანგარიშის ქართულ, non-technical executive summary-ს: სულ რამდენი
Critical/High/Medium/Low მიგნება იყო თითოეულში, ტოპ 5 ყველაზე
მნიშვნელოვანი მიგნება საერთო რიგში (severity-ის მიხედვით), და ბმული
თითოეულ სრულ ანგარიშზე. ბოლოს, ჩატის პასუხში, მომწერე მოკლედ ქართულად
რომ დაასრულე და სად ვნახო შედეგები.
```

---

## Prompt 1 — Security / AuthN / AuthZ / RBAC audit

```
You are doing a read-only security audit of a real production-bound internal
call-center portal codebase at C:\Projects\Magti base (java-backend/ = Spring
Boot on Oracle 19c, angular-frontend/ = Angular). ~600 real employees will use
this. First read CLAUDE.md (repo root) and docs/JAVA_ORACLE_ANGULAR_MIGRATION.md
for context, and docs/QUESTIONS_FOR_IT.md so you don't re-flag items already
known and blocked on IT (AD/SSO, K8s specifics, CORS backlog).

DO NOT MODIFY ANY CODE. Do not commit or push anything. This is audit-only.
You may run the existing test suites (mvn test in java-backend/, ng test in
angular-frontend/) to confirm or refute a suspected issue -- do not start
long-running interactive servers.

Audit scope -- read the actual code end-to-end for each item, don't infer
from filenames or docs:

1. JWT handling (JwtService, JwtAuthenticationFilter): signature/expiry
   validation, algorithm confusion, token replay, what happens on a
   malformed/missing token.
2. LoginRateLimiter: is it actually per-IP as intended? Can it be bypassed
   (X-Forwarded-For trust, per-pod-instance state that won't survive multiple
   K8s replicas)?
3. Every @RestController endpoint across all 14 domains (articles, auth,
   users, news, videos, categories, favorites, compliance, messaging,
   exports, audit_logs, search, stats, platform): does it enforce the
   correct permission/role check? Look specifically for endpoints reachable
   by a role that shouldn't have access, and for permission-vs-role mismatches
   (a known bug class already fixed once in ArticleController -- check every
   OTHER controller for the same class of gap).
4. Department-scoped data visibility: this app has TWO different department-
   matching patterns in use (DepartmentMatcher's prefix-aware rule vs
   EligibleOperatorsService's exact-match). Verify every place that filters
   data by department (stats, compliance, exports, messaging broadcast,
   search, article targeting) uses the correct one for its context, and
   check for the same manager cross-department leak pattern already fixed
   once in StatsController -- does it exist anywhere else data is aggregated
   or filtered by department?
5. JIT test-account bypass (qa_accounts-equivalent / TEST_ACCOUNTS
   allow-list): confirm this cannot be reachable in a real production
   config, not just "should be" -- trace the actual condition that gates it.
6. ProductionSafetyGuard: confirm it actually fires under a realistic
   prod-like config (APP_ENV=production, default SECRET_KEY, COOKIE_SECURE
   false) and can't be trivially satisfied with a weak-but-technically-valid
   config.
7. Password policy (PasswordPolicy.java): confirm it's enforced at every
   single place a password is created or changed, not just user-creation.
8. File upload endpoint(s): path traversal, extension/content-type
   validation, size limits, whether uploaded files served from /uploads/**
   could allow stored XSS (e.g. an uploaded SVG/HTML file served with a
   browser-executable content-type).
9. Audit-trail hash-chain (AuditChainService and friends): can actor
   identity be spoofed? Does the tamper-detection actually catch a tampered
   row if you trace the verification logic, not just assume it does?
10. CORS configuration: what is the actual current state (not the backlog
    note) -- is it wide open, and is that a real risk before go-live?

Output: write docs/OPUS5_AUDIT_1_SECURITY.md. Structure: for each finding,
file:line, a CONFIRMED (you traced/tested it) or SUSPECTED (pattern-matched,
not fully traced) label, severity (Critical/High/Medium/Low), the concrete
failure scenario, and a suggested fix direction (not applied). End the
document with a short plain-language executive summary IN GEORGIAN, written
for a non-technical project owner who cannot read code or diffs -- explain
in plain terms what was found and why it matters, no jargon.
```

---

## Prompt 2 — Backend business-logic & data-integrity audit

```
Same repo/context/rules as before (read-only, no commits, may run mvn test
to confirm findings). Read CLAUDE.md and docs/JAVA_ORACLE_ANGULAR_MIGRATION.md
first for context on what each domain is supposed to do (it documents the
original Python behavior each Java controller is meant to match).

Go through java-backend/src/main/java/ge/magti/portal/web/ controller by
controller (all 14 domains) and verify the actual implemented business rule
against the intended one, specifically:

1. Quiz-gate: a required-reading with a quiz must genuinely block
   mark-as-read until the quiz is passed -- trace the real enforcement path,
   don't trust a comment saying it's enforced.
2. Mandatory-reading due-date enforcement, and what happens on an overdue
   item (does anything actually change, or is due_date purely cosmetic?).
3. Article history/diff/restore: does restore actually round-trip content
   correctly, and does the version list stay consistent after multiple
   restores?
4. Cascade-delete correctness: Article delete and any other hard-delete path
   -- confirm every dependent table (read receipts, view logs, notes,
   feedback, quiz data, required readings) is handled correctly, no orphans,
   no FK violations, no silent data loss beyond what's intended.
5. Category delete with fallback reassignment: confirm the reassignment
   target is picked correctly and articles never end up pointing at a
   deleted category id.
6. Compliance calculation: confirm management roles are actually excluded
   from the eligible set everywhere this calculation is reused (dashboard,
   exports, team-stats), not just in one call site.
7. Concurrency / flush-order bugs: TagSyncService had a real bug where
   delete-then-recreate under Hibernate's default flush order executed
   INSERT before DELETE, violating a UNIQUE constraint when at least one
   item was retained across an update. That's fixed there now -- but grep
   for every OTHER place in the codebase that does the same
   "delete-all-then-recreate" pattern (article target-departments, quiz
   questions/answers, tag mappings, any other mapping table) and check each
   one individually for the identical trap.
8. @Async + @Transactional boundary (ExportJobWorker): in the real
   (non-test) async executor config, can a worker read stale data or lose a
   write because it runs on a different DB connection/transaction than the
   request that enqueued it?
9. Formula-injection sanitization (the =cmd|... CSV export attack class):
   confirmed fixed in the CSV path -- verify XLSX and PDF export paths
   sanitize the same way, and check for any newer export/report code path
   that might have skipped it.
10. Flyway migrations (all ~30): confirm every one is genuinely idempotent
    (safe if two app instances race to run it on startup) -- spot-check the
    more complex ones (constraint additions, data backfills), don't just
    trust that CREATE TABLE IF NOT EXISTS-style guards are used everywhere.
11. Data-integrity gaps that mirror the cross-test-leakage bugs already
    found in tests: e.g. categories.name has no unique constraint (confirmed
    during a prior fix) -- is that an accepted, intentional decision, or a
    real production data-integrity gap that could cause user-facing
    confusion (two categories with the same displayed name)?

Output: docs/OPUS5_AUDIT_2_BACKEND_LOGIC.md, same format as above (file:line,
CONFIRMED/SUSPECTED, severity, failure scenario, suggested fix), ending with
a Georgian plain-language executive summary for a non-technical reader.
```

---

## Prompt 3 — Angular frontend correctness & UX audit

```
Same repo/context/rules (read-only, no commits). Read CLAUDE.md first.
Working directory: angular-frontend/src/app/.

1. Silent error-swallowing: one instance (admin-content-page.ts's
   deleteArticle/toggleArchive/bulkArchive using `error: () => {}`) was just
   fixed. Grep the ENTIRE Angular app for the same pattern -- any
   `.subscribe({ ... error: () => {} })` or `.subscribe({ next: ... })` with
   no error handler at all -- and list every remaining instance where a
   failed backend call currently gives the user zero feedback.
2. viewChild.required()/input.required() signal misuse: one NG0951 crash
   (viewChild.required() on a conditionally-@if-rendered child) was already
   found and fixed in article-edit-drawer.ts. Audit every OTHER
   viewChild.required()/input.required() call in the app for the same two
   traps: (a) required() on a conditionally-rendered child, (b) a required
   signal read directly in a constructor instead of inside effect()
   (NG0950).
3. i18n completeness: any hardcoded Georgian or English UI string that
   bypasses the translate pipe; any translate key referenced in a template
   that doesn't exist in both public/i18n/ka.json and en.json. Also confirm
   the current status of the known duplicate top-level "users" key collision
   between the two files and its actual runtime impact (not just its
   existence).
4. Dark/light theme coverage: spot-check the newest features (messaging,
   the readings-export UI, quiz-taker-modal) for elements missing `dark:`
   classes that other, older pages already handle correctly.
5. Mobile responsiveness (<768px): the sidebar-nav fix landed after several
   newer features were built (messaging, export UI, quiz-taker) -- these
   were not retested at mobile width since. Check them specifically.
6. RxJS subscription leaks: any component subscribing without
   takeUntilDestroyed or explicit unsubscribe, especially polling
   (ExportService.pollUntilDone uses interval+takeWhile) -- confirm it can't
   keep polling after the owning component is destroyed.
7. Route guards vs nav hiding: for every admin/manager-only route, confirm
   there's an actual functional route guard, not just a hidden nav link --
   a route reachable by typing the URL directly, with no guard, is a real
   access-control bug, not a cosmetic one.

Output: docs/OPUS5_AUDIT_3_FRONTEND.md, same format (file:line,
CONFIRMED/SUSPECTED, severity, failure scenario, suggested fix), ending with
a Georgian plain-language executive summary for a non-technical reader.
```

---

## Prompt 4 — Production-readiness / operational audit

```
Same repo/context/rules (read-only, no commits). Read CLAUDE.md first.

1. Secrets hygiene: actually grep the repo (tracked files, not .gitignored
   ones) for hardcoded credentials, API keys, or a real SECRET_KEY/DB
   password -- don't just trust that CLAUDE.md says they're externalized,
   verify it directly.
2. Docker artifacts (java-backend/Dockerfile, angular-frontend/Dockerfile):
   non-root user, multi-stage build with no dev dependencies leaking into
   the final image, reasonable image size, health-check readiness.
3. HikariCP pool config (maximum-pool-size currently 30): confirm it reads
   from an env var correctly in the production profile (not hardcoded for
   local dev only) and stays safely under Oracle's actual processes/sessions
   limits as configured.
4. Scheduled jobs (ExportJobCleanupScheduler, audit retention/purge, any
   other @Scheduled bean): confirm each is actually wired to run in the
   deployed app (not an unused/dead bean), and that an uncaught exception
   inside one can't silently kill the scheduler thread permanently (Spring's
   default @Scheduled behavior on an uncaught exception -- verify, don't
   assume).
5. LoginRateLimiter statefulness: since K8s will likely run multiple pod
   replicas, confirm whether this rate limiter's state is in-memory
   (per-pod, meaning it's trivially bypassable by load-balancing across
   pods) or externalized -- and if in-memory, flag this clearly as a real
   gap for the multi-replica production deployment, not just a note.
6. Logging: grep for any place a password, JWT token, or other sensitive
   value could end up in an INFO/DEBUG log line.
7. Graceful degradation: confirm that if the export libraries (POI/PDFBox
   equivalents) fail to load or a required native dependency is missing,
   the app still boots and the export endpoints degrade to a clean error
   response instead of crashing the whole application.

Output: docs/OPUS5_AUDIT_4_PRODUCTION_READINESS.md, same format (file:line,
CONFIRMED/SUSPECTED, severity, failure scenario, suggested fix), ending with
a Georgian plain-language executive summary for a non-technical reader.
```
