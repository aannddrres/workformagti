> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# OPUS5 Audit 1 — Security / AuthN / AuthZ / RBAC

**Date:** 2026-08-14
**Scope:** `java-backend/` (Spring Boot on Oracle 19c) + the auth-relevant parts of
`angular-frontend/` deployment config.
**Mode:** read-only. No source file was modified, nothing committed, nothing pushed.
**Context read first:** `CLAUDE.md`, `docs/JAVA_ORACLE_ANGULAR_MIGRATION.md`,
`docs/QUESTIONS_FOR_IT.md` (AD/SSO, K8s cluster details and CORS backlog items that
are blocked on Magti IT are **not** re-flagged as findings here).

## Verification method

- Every finding below was traced through the actual code path, not inferred from
  filenames or comments. Where a claim in a javadoc contradicted the code, the code wins
  (see SEC-09).
- `mvn -B test` was run once (read-only, no server started):
  **383 tests, 0 failures, 217 errors.** All 217 errors are a single environmental cause —
  `ORA-12541: Cannot connect. No listener at host localhost port 1521` — because this audit
  container has no Oracle 19c instance. All **166 pure-unit tests passed**, including
  `ProductionSafetyGuardTest` (4), `AuthenticationServiceTest` (5),
  `JwtAuthenticationFilterTest` (8), `LoginRateLimiterTest` (2), `PermissionCheckerTest` (2),
  `DepartmentMatcherTest` (11), `DirectMessagePermissionTest` (7).
  Findings that depend on endpoint-level behaviour are therefore labelled from source
  tracing, not from a green integration run.

## Findings summary

| # | Severity | Status | Finding |
|---|----------|--------|---------|
| SEC-01 | **Critical** | CONFIRMED | Password-less admin login is active by default; nothing in the deployment artifacts sets `APP_ENV=production` |
| SEC-02 | **High** | CONFIRMED | All 4 export endpoints return org-wide personal data with no department scoping; `reports.export` is a manager default |
| SEC-03 | **High** | CONFIRMED | `/api/manager/department-stats` returns every department's named members to any manager |
| SEC-04 | **High** | CONFIRMED | Login rate limiter keys on the proxy's IP → one shared 10/min bucket for the whole company |
| SEC-05 | Medium | CONFIRMED | `audit_logs.ip_address` records the nginx/ingress IP, not the user's |
| SEC-06 | Medium | CONFIRMED | 3 of 9 permissions (`users.manage`, `compliance.assign`, `articles.view`) are never enforced anywhere |
| SEC-07 | Medium | CONFIRMED | `ProductionSafetyGuard` accepts any secret that isn't one exact literal — including the `.env.example` placeholder |
| SEC-08 | Medium | CONFIRMED | `/uploads/**` is served with no authentication at all |
| SEC-09 | Medium | CONFIRMED | Upload allowlist trusts the client-declared `Content-Type`, contrary to its own javadoc |
| SEC-10 | Medium | CONFIRMED | Audit hash chain is unkeyed SHA-256 with no UPDATE/DELETE guard — no defence against an actor with DB write access |
| SEC-11 | Medium | CONFIRMED | Broadcast targets departments by exact string while every other check is prefix-aware → sub-group staff silently receive nothing |
| SEC-12 | Medium | CONFIRMED | `PUT /api/users/{id}` can demote the last system admin (and yourself) — permanent lockout |
| SEC-13 | Low | CONFIRMED | `/api/manager/team-stats` exact-matches the manager's department → a parent-department manager sees an empty team |
| SEC-14 | Low | CONFIRMED | Logout cannot invalidate an already-issued token (no revocation list, no `jti`) |
| SEC-15 | Low | CONFIRMED | No global exception handler; `Role.fromValue` in broadcast throws → HTTP 500 instead of 400 |
| SEC-16 | Low | CONFIRMED | `application.yml` comment claims no Java production guard exists — it does, and the stale comment invites a wrong deployment decision |

**Totals: Critical 1 · High 3 · Medium 8 · Low 4 (16 findings).**

---

## SEC-01 — Password-less admin login is on unless someone remembers one env var

**Severity: Critical · CONFIRMED**
`java-backend/src/main/java/ge/magti/portal/security/AuthenticationService.java:74-92`,
`config/PortalProperties.java:16,39-41`, `src/main/resources/application.yml:56`,
`java-backend/Dockerfile` (whole file), `java-backend/.env.example:22`

Traced end to end:

1. `AuthenticationService.java:76-77` decides `isTestAccount` as
   `!properties.isProduction() && (email.startsWith("test_operator_") || DEV_TEST_EMAILS.contains(email))`.
2. `AuthenticationService.java:85-87` — when `isTestAccount` is true the method returns the
   user **before** the bcrypt check at line 88. Any password works. If the account does not
   exist, `jitProvision` (line 94-114) creates it, and `JIT_PROVISION_OVERRIDES` (line 42-48)
   gives `admin@magti.ge` the role `SYSTEM_ADMIN`.
3. `isProduction()` is `"production".equalsIgnoreCase(appEnv)` (`PortalProperties.java:39-41`),
   and `appEnv` defaults to `"development"` — both as a Java field default
   (`PortalProperties.java:16`) and as the YAML default `${APP_ENV:development}`
   (`application.yml:56`).
4. `java-backend/Dockerfile` sets **no** `ENV APP_ENV`. There are no Kubernetes manifests or
   Helm charts in the repository. `java-backend/.env.example:22` ships `APP_ENV=development`.

So the shipping default of the Java image is "dev bypass enabled". This is the opposite of the
Python side's posture that `CLAUDE.md` records — there, `docker-compose.yml` was deliberately
made to fall back to `APP_ENV=production` so a missing `.env` fails *safe*. The Java image
fails *open*, silently, with no startup warning: `ProductionSafetyGuard.verify()` returns
immediately at `ProductionSafetyGuard.java:28-30` when the env is not production, so the one
component that could shout says nothing.

**Failure scenario:** the app is deployed to the on-prem K8s cluster and the Deployment
manifest omits `APP_ENV` (or misspells it, or sets `APP_ENV=prod`). The app boots normally and
looks healthy. Anyone who can reach the portal URL — ~600 employees, plus anyone on the
internal network — sends
`POST /api/auth/login {"email":"admin@magti.ge","password":"anything"}` and receives a valid
60-minute `SYSTEM_ADMIN` token. That role bypasses every permission check unconditionally
(`PermissionChecker.java:21-23`): full user administration, all exports, the whole audit trail.
The same applies to `manager@magti.ge`, `content@magti.ge`, and to any address matching
`test_operator_*`, which auto-creates an operator account on first use.

**Suggested fix direction (not applied):** invert the default so the insecure mode is the one
that must be asked for — e.g. default `appEnv` to `production` and require an explicit
`APP_ENV=development` for the bypass; and/or gate the bypass on a second, separate flag
(`portal.security.allow-dev-login`) that `ProductionSafetyGuard` refuses to accept in
production. Add `ENV APP_ENV=production` to `java-backend/Dockerfile` so the image is safe
before any manifest is written. A startup `WARN` log whenever the bypass is live would make the
condition visible in any environment.

---

## SEC-02 — Every export endpoint hands out the whole company's personal data

**Severity: High · CONFIRMED**
`web/ExportController.java:91-188, 303-311`, `export/ExportQueryService.java:56-104`,
`domain/Permission.java:45`

`requireReportsExport` (`ExportController.java:303-311`) is the only gate on all four export
endpoints, and it checks exactly one thing: does the caller hold `reports.export`.
`Permission.java:45` grants `reports.export` to **every `MANAGER` by default**.

The data layer is entirely unscoped. `ExportQueryService.eligibleReadingRows()` (line 56-59)
starts from `complianceQueryService.computeCompliance()` — no department, no team, no caller
argument at all — and returns one row per read-status for every eligible user in the
organisation, carrying `user.getName()` and `user.getDepartment()`
(`ExportQueryService.java:80-83`). `departmentComplianceTotals()` (line 94-104) is likewise
org-wide. `ExportController` never filters the returned rows before writing the CSV/XLSX/PDF
(`ExportController.java:99-118`, `130-142`, `154-165`, `177-187`).

This is the same leak class as bug #312, which was fixed in `StatsController` — the fix simply
never reached this controller. Note the asymmetry it creates: the same manager is hard-pinned
to their own department by `getTeamStats` (`StatsController.java:227-232`), by
`getCriticalOperators` (`:293-297`), by `getGroupUsers` (`:325-332`) and by the audit log
(`AuditLogController.java:205-207`) — and then gets everything anyway, in a downloadable
spreadsheet, from `/api/export/readings.xlsx`.

**Failure scenario:** a group manager in "ტექნიკური — ჯგუფი 03" opens the export screen and
downloads `readings_export.csv`. It contains the name, department and per-item compliance
status of all ~600 employees, including every other department and every other manager's team.
The file then leaves the portal entirely — it is on their laptop, in email, wherever they put
it. Nothing in the audit row (`ExportController.java:270-278`) records that the export was
org-wide rather than team-scoped.

**Suggested fix direction:** give `eligibleReadingRows()`/`departmentComplianceTotals()` a
caller argument and apply the same rule already used in `StatsController.getCriticalOperators`
— unscoped for `SYSTEM_ADMIN`/`CONTENT_ADMIN`, pinned to the caller's department for `MANAGER`.
Consider recording the effective scope in the export's audit `details`, as
`AuditLogController.writeMetaAudit` already does with `scope_department`.

---

## SEC-03 — The department dashboard shows every department's named staff to any manager

**Severity: High · CONFIRMED**
`web/StatsController.java:250-260`, `stats/DepartmentDashboard.java`,
`stats/DepartmentStats.java`, `stats/DepartmentGroupStats.java`, `stats/DepartmentMember.java`

`getDepartmentStats` gates on `requireManagerOrAdmin` (line 253) and then calls
`complianceQueryService.computeCompliance()` (line 257) — the no-argument, org-wide overload —
with no scoping branch of any kind, unlike the two endpoints immediately below it in the same
file, which both branch on `user.getRole() == Role.MANAGER`.

The response is not a coarse aggregate. `DepartmentDashboard` → `List<DepartmentStats>` →
`List<DepartmentGroupStats>` → `List<DepartmentMember>`, and `DepartmentMember` carries
`user_id`, `user_name`, `position`, `read_count`, `required_count`, `percentage` and
`is_critical`. So the payload is a per-person compliance list for every group of every
department.

**Failure scenario:** a manager of one group calls `GET /api/manager/department-stats` (the
dashboard their own UI loads on mount) and receives the names, positions and individual
compliance percentages of staff in departments they have no relationship to — including which
named individuals are flagged `is_critical` (below 30%). Meanwhile the very same manager gets a
403 from `/api/admin/departments/{department}/groups/{groupName}/users` for that same group
(`StatsController.java:325-332`), so the intent that they *not* see it is already expressed in
the codebase; only this endpoint disagrees.

**Suggested fix direction:** decide explicitly whether the executive dashboard is meant to be
org-wide for managers. If yes, strip `members` (and ideally `critical_count` detail) from the
payload for `MANAGER` callers so it stays an aggregate. If no, scope
`computeCompliance()` by the caller's department exactly as `getCriticalOperators` does.

---

## SEC-04 — Login rate limiting collapses to one bucket for the entire company

**Severity: High · CONFIRMED**
`web/AuthController.java:61`, `security/LoginRateLimiter.java:28-50`,
`src/main/resources/application.yml` (no `server.forward-headers-strategy`),
`angular-frontend/nginx.conf.template:22-27`

`AuthController.java:61` keys the limiter on `httpRequest.getRemoteAddr()`. Spring only
substitutes the forwarded client IP when `server.forward-headers-strategy` is set to
`framework` or `native`; `application.yml` sets neither (the whole `server:` block is
`port: 8080`, lines 48-49). The deployment always has a proxy in front: the Angular container's
nginx proxies `/api/` to the backend (`nginx.conf.template:22-27`), and on K8s an ingress sits
in front of that.

Therefore `getRemoteAddr()` returns the **nginx/ingress pod IP for every user**, and
`MAX_ATTEMPTS = 10` per `WINDOW = 1 minute` (`LoginRateLimiter.java:30-31`) applies to all
~600 employees collectively.

Two concrete consequences, both real:

- **Availability:** any one person (or a misbehaving script, or a user retyping a password)
  burning 10 attempts in a minute causes every other employee's login to return
  `429 ძალიან ბევრი მცდელობა` until the window rolls. This is a trivial company-wide
  denial-of-login with 10 requests.
- **Throttling value:** the limiter no longer distinguishes attackers from anyone else, so it
  provides no per-attacker brute-force protection — while still being cheap to weaponise
  against everyone else.

Separately, the state is a plain in-JVM `ConcurrentHashMap` (`LoginRateLimiter.java:33`). The
class javadoc (lines 18-25) argues a single shared counter is correct "because Spring Boot runs
as a single JVM by default" — that reasoning holds for one pod and stops holding the moment the
K8s Deployment has `replicas: 2`, which is the stated deployment target
(`docs/QUESTIONS_FOR_IT.md` §"Kubernetes დეპლოიმენტი"). See also the operational report
(Audit 4) for the multi-replica angle.

**Suggested fix direction:** set `server.forward-headers-strategy=framework` **and** configure
the trusted-proxy set (otherwise `X-Forwarded-For` becomes client-spoofable, trading one bug
for another); then key the limiter on `email + client IP` rather than IP alone, so one noisy
address cannot lock out unrelated accounts. For multi-replica correctness the counter needs to
move out of the JVM (Redis is already part of the Python-side stack).

---

## SEC-05 — The audit trail records the proxy's IP address, not the user's

**Severity: Medium · CONFIRMED**
`web/AuthController.java:80-81, 98`

`failedLogin.setIpAddress(httpRequest.getRemoteAddr())` and the same call on the success path
inherit exactly the problem described in SEC-04: with no `forward-headers-strategy`, every
`LOGIN` and `LOGIN_FAILED` row stores the nginx/ingress pod address.

**Failure scenario:** an account is misused and the investigation asks "where did that login
come from". Every row in `audit_logs.ip_address` says `10.x.x.x` — the same pod — for every
user and every event. The field is hashed into the tamper-evident chain
(`V28__audit_hash_chain.sql`, `p_ip_address` in the canonical string), so it is faithfully
protected, but the value it protects is not evidence.

**Suggested fix direction:** same as SEC-04; once forwarded headers are trusted, this field
becomes meaningful with no further change.

---

## SEC-06 — Three of nine permissions are decoration only

**Severity: Medium · CONFIRMED**
`domain/Permission.java:33-51`, `web/UserController.java:477-515`,
`web/ComplianceController.java:220-292`, `web/ArticleController.java:161-215`

A repo-wide grep for `permissionChecker.hasPermission(...)` returns exactly seven call sites:
`UserController.java:92` (read-only UI flag), `VideoController.java:263`,
`AuditLogController.java:242`, `ArticleController.java:1280/1303/1316`,
`ExportController.java:308`. Cross-referencing against the nine catalog entries in
`Permission.java:33-41`:

| Permission | Enforced? | Where the decision is actually made |
|---|---|---|
| `articles.edit` / `articles.publish` / `articles.archive` | yes | `ArticleController.java:1298-1321` |
| `videos.archive` | yes | `VideoController.java:258-266` |
| `reports.export` | yes | `ExportController.java:303-311` |
| `system.audit` | yes | `AuditLogController.java:237-247` |
| **`users.manage`** | **no** | `UserController` gates everything on `requireSystemAdmin` (`:530-539`) |
| **`compliance.assign`** | **no** | `ComplianceController` gates on `requireContentAdmin` (`:334-343`) |
| **`articles.view`** | **no** | acknowledged as a deliberate remaining gap in `ArticleController.java:86-92` |

This is the same class as bug #314 (which is why the article permissions above *are* now
enforced). `PUT /api/users/{userId}/permissions` (`UserController.java:477-515`) validates the
submitted strings against the full enum and persists them, so the admin UI presents all nine as
meaningful toggles.

**Failure scenario:** an administrator revokes `users.manage` from an account to stop it
managing users — the toggle saves, the UI shows it off, and the account keeps full user
administration because only the role is consulted. The inverse also fails: granting
`compliance.assign` to a manager so they can assign required readings does nothing; they still
get 403 because `requireContentAdmin` never looks at permissions. The security control most
visible to the administrator is the one that does not work.

**Suggested fix direction:** either enforce the three (add `requireUsersManagePermission` /
`requireComplianceAssignPermission` helpers alongside the existing role gate, mirroring
`requireArticlesEditPermission`), or remove them from the catalog so the editor stops offering
switches that do nothing. A test asserting "every `Permission.values()` entry appears in at
least one `hasPermission` call" would stop the gap reopening.

---

## SEC-07 — The production guard can be satisfied with a placeholder secret

**Severity: Medium · CONFIRMED**
`config/ProductionSafetyGuard.java:18, 26-43`, `java-backend/.env.example:15`

The guard's secret check is a single equality against one literal:
`DEV_JWT_SECRET.equals(properties.getSecurity().getJwt().getSecret())`
(`ProductionSafetyGuard.java:18, 31`). Anything else passes — including
`SECRET_KEY=change-me-to-a-long-random-value`, which is what
`java-backend/.env.example:15` ships and what a hurried deployer is most likely to copy
verbatim. There is no minimum entropy/length assertion here, and no check that the value is not
a well-known placeholder.

One accidental backstop exists and is worth knowing about: `JwtService.java:40` uses
`Keys.hmacShaKeyFor(...)`, which throws `WeakKeyException` for a secret under 32 bytes, so a
*short* secret fails closed at startup. A 33-character placeholder like the one in
`.env.example` sails past both checks.

`ProductionSafetyGuardTest` (4 tests) passes and does verify that the guard fires for the exact
dev literal and for `COOKIE_SECURE=false` — the gap is in what the guard was asked to check,
not in whether it works.

**Failure scenario:** the deployment sets `APP_ENV=production`, `COOKIE_SECURE=true`, and copies
`.env.example`'s `SECRET_KEY` placeholder. The app boots with a green startup and signs every
session token with a value that is published in this repository. Anyone with the repo can forge
a token for any email, including `admin@magti.ge` — no login required, and the
`JwtAuthenticationFilter` will happily accept it because the signature verifies.

**Suggested fix direction:** in the same guard, additionally reject secrets shorter than ~48
characters, secrets matching a small deny-list of shipped placeholders (`change-me…`,
`CHANGE_ME…`), and secrets with very low distinct-character counts. Same treatment for
`ORACLE_DB_PASSWORD` (see Audit 4, secrets hygiene).

---

## SEC-08 — Uploaded files are readable by anyone, with no login

**Severity: Medium · CONFIRMED**
`config/WebConfig.java:23-27`, `security/SecurityConfig.java:45`,
`angular-frontend/nginx.conf.template:29-32`

`WebConfig` registers `/uploads/**` as a plain static resource handler. `SecurityConfig`'s chain
ends in `auth.anyRequest().permitAll()` (line 45), and `JwtAuthenticationFilter` only *populates*
a security context — it never rejects an anonymous request (`JwtAuthenticationFilter.java:57-78`;
it returns 403 only for a validly-signed token belonging to a deactivated user). Nothing else
sits in front of the path: nginx proxies `/uploads/` straight through
(`nginx.conf.template:29-32`).

So every attachment on every article, news item and video — internal call-center procedures,
whatever PDFs/DOCX/XLSX staff attach — is retrievable by an unauthenticated GET.

The one thing standing in the way is that filenames are random: `UUID.randomUUID() + ext`
(`UploadController.java:94`). That makes guessing impractical, but it makes the control
"the URL is the password". Any URL that leaks — a forwarded email, a pasted link in a ticket, a
browser history export, a referer header to an external site — is a permanent public handle on
that document, and there is no way to revoke it short of deleting the file.

**Failure scenario:** an article attachment containing internal tariff/procedure detail is
linked in a chat message that reaches someone outside the department, or an ex-employee keeps a
bookmark. The file remains fetchable after their account is deactivated — deactivation only
blocks the API (`JwtAuthenticationFilter.java:66-72`), not `/uploads/**`.

**Suggested fix direction:** serve uploads through an authenticated controller (or add
`/uploads/**` to a `.authenticated()` matcher in `SecurityConfig` and let the JWT filter's
context decide), so an upload is reachable only by a logged-in session. If direct static serving
must stay for performance, consider short-lived signed URLs.

---

## SEC-09 — The upload allowlist checks a header the client controls

**Severity: Medium · CONFIRMED**
`web/UploadController.java:23-37 (javadoc), 75-87`

The class javadoc states the stored extension is derived from "the server-detected
`content_type`, never the client-supplied filename". The first half of that is not what the code
does: `file.getContentType()` (line 75) returns the `Content-Type` header **the client wrote
into that multipart part**. There is no magic-byte sniffing (no `Tika`, no
`URLConnection.guessContentTypeFromStream`) anywhere in the file. A client that declares
`image/png` while sending arbitrary bytes passes the allowlist at line 82-87 and gets the file
stored as `<uuid>.png`.

Two things limit the blast radius, and both are worth stating precisely because they are why
this is Medium and not High:

- The endpoint is gated to `content_admin`/`system_admin` (`UploadController.java:70, 115-124`),
  so this is an insider/compromised-admin path, not an anonymous one.
- The response for `/uploads/**` gets Spring Security's **default** headers — `SecurityConfig`
  never calls `.headers(...)` to disable them — which includes
  `X-Content-Type-Options: nosniff`. A `.png` containing HTML is therefore served as `image/png`
  and will not execute as a document in a current browser. `image/svg+xml` is correctly absent
  from the allowlist (`UploadController.java:45-57`), which closes the classic stored-XSS route.

What remains is that the allowlist is not actually enforcing file *type*: it enforces a claim.
Anything can be stored under a trusted-looking extension and later handed to a program that
trusts the extension rather than the bytes (a user's Excel, a downstream import job).

**Failure scenario:** a compromised content-admin account uploads an executable or a
macro-bearing payload declared as `application/vnd.ms-excel`; it is stored as `<uuid>.xls`,
appears in the UI as a normal attachment, and a colleague opens it from the portal, trusting it
because it came from the internal knowledge base.

**Suggested fix direction:** verify the magic bytes of the received stream against the declared
type and reject on mismatch; keep the declared type only as a fast pre-filter. Fix the javadoc
either way — a comment asserting a control that does not exist is how the next reader stops
looking.

---

## SEC-10 — The audit chain detects application-level tampering, not database-level tampering

**Severity: Medium · CONFIRMED**
`src/main/resources/db/migration/V28__audit_hash_chain.sql` (whole file),
`audit/AuditChainService.java:48-144`

Traced rather than assumed. The design is sound for what it covers:

- `admin_id` can not be spoofed through the API. All 18 `setAdminId(...)` call sites in
  `src/main/java` pass an authenticated principal's id (`AuthController.java:75,93`,
  `UserController.java:145,216,254,422,455,506`, `ArticleController.java:516,1195`,
  `MessagingController.java:126,207`, `ExportController.java:272`, `UploadController.java:105`,
  `QuizController.java:156`, `AuditLogController.java:225`, `VideoController.java:226`).
  No endpoint accepts an actor id from the request body.
- The `BEFORE INSERT` trigger (`V28:...trg_audit_logs_chain`) overwrites `:NEW.prev_hash` and
  `:NEW.row_hash` unconditionally, so an API caller cannot pre-set them.
- `verify()` re-derives the predecessor live rather than trusting the stored `prev_hash`
  (`AuditChainService.java:53-56, 73-76`), so a **deleted** predecessor is caught, not just an
  edited row. `chainHealth()` additionally anchors the window's first row to the chained row
  before it (`AuditChainService.java:108-116`), which catches a forged "second genesis" inside
  the window; a unique functional index (`V28`, `ux_audit_logs_chain_genesis`) blocks a second
  genesis row outright.

The limitation is the threat model, and it is not stated anywhere in the code: the hash is a
plain `STANDARD_HASH(..., 'SHA256')` over a canonical string, with **no secret key** and no
external anchoring, and there is no `BEFORE UPDATE`/`BEFORE DELETE` trigger on `audit_logs`
(only `V10`, `V28`, `V29` touch the table; none adds one). Anyone who can `UPDATE audit_logs`
directly — a DBA, an application account with DDL/DML rights, anyone with the Oracle password
from SEC-07's neighbourhood — can alter a row, recompute its hash with the same public function,
and re-stitch every subsequent row plus `audit_chain_state.tip_hash`. `verify()` and
`chainHealth()` would then report `ok`.

**Failure scenario:** an insider deletes the `EXPORT` rows proving they downloaded the org-wide
compliance file (SEC-02), then recomputes the chain forward. The chain-health dashboard stays
green, and the tamper-evidence feature actively provides false assurance during the
investigation.

**Suggested fix direction:** treat this as a documented boundary rather than silently implying
more. Concretely: use a keyed HMAC whose key lives outside the database (so DB write access
alone is insufficient), and/or periodically export the current `tip_hash` to an append-only
store outside Oracle. Add `BEFORE UPDATE OR DELETE` triggers that raise, so ordinary tampering
fails loudly rather than merely being detectable later. Note also that the 180-day retention
purge (`retention.py` on the Python side) deletes audit rows — whatever replaces it on the Java
side will create permanent, legitimate chain breaks unless purges are anchored.

---

## SEC-11 — Broadcast reaches nobody in a sub-group

**Severity: Medium · CONFIRMED**
`web/MessagingController.java:183-185` vs `messaging/DirectMessagePermission.java:35-41`
and `util/DepartmentMatcher.java:98-111`

The prompt's question — do the two department-matching rules get used in the right places — has
one clear wrong answer here. `postBroadcast` selects recipients with
`userRepository.findByActiveTrueAndDepartment(request.targetDepartmentOrDefault())`: a plain SQL
equality on the free-text department string. Every other department decision in the messaging
and content path is prefix-aware via `DepartmentMatcher.matches`
(`DirectMessagePermission.java:39-40`, `ArticleController.java:1213`,
`SearchQueryService.java:133,186,206`, `ComplianceCalculator.java:96-101`).

Real department strings carry a group suffix — `"ტექნიკური — ჯგუფი 03"`
(`DepartmentMatcher.java:36-39`, `StatsController.java:280`). So a broadcast targeted at
`"ტექნიკური"` matches only users whose department is *exactly* that string, and silently misses
every operator in `ტექნიკური — ჯგუფი 01/02/03…`. The endpoint returns
`BroadcastResponse("success", recipients.size())` (`MessagingController.java:214`) — a success
with a low count, which reads as "that department is small", not as "delivery failed".

The exact-match rule is deliberate and correct in `EligibleOperatorsService`
(`EligibleOperatorsService.java:17-24`, matching Python's `IN`-list); this is a case where the
same choice was made where the prefix-aware rule was needed.

**Failure scenario:** a content admin broadcasts a mandatory policy announcement to the
technical department before a deadline. Nobody in any technical group receives it. The sender
sees "success". This is a compliance failure that surfaces only when people miss the deadline.

**Suggested fix direction:** select broadcast recipients through `DepartmentMatcher.matches`
(fetch active users, filter in memory as `SearchQueryService` already does), or expand the
target to the department plus its known sub-groups before the query. A non-zero/zero recipient
count is also worth surfacing in the UI response.

---

## SEC-12 — The last system administrator can be demoted, including by themselves

**Severity: Medium · CONFIRMED**
`web/UserController.java:314-348` vs `:156-228` and `:246-249`

Two neighbouring endpoints in the same controller protect against lockout, and one does not:

- `POST /api/admin/roles/bulk-reassign` excludes the caller's own id (`:172-176`) and refuses if
  the demotion would leave zero active system admins (`:187-199`).
- `PUT /api/users/{userId}/status` refuses self-deactivation (`:246-249`).
- `PUT /api/users/{userId}` — `updateUserAdmin` (`:314-348`) — sets `user.setRole(role)` at
  line 336 with **neither** guard. It can demote the only remaining `SYSTEM_ADMIN`, and it can
  be pointed at the caller's own id.

Note it also unconditionally overwrites `department` and `position` (lines 337-338) without a
null check, unlike the `phone`/`teamId` fields below it — a separate, smaller data-loss
footgun for a partial payload.

**Failure scenario:** the sole system administrator edits their own profile through the admin
user form and changes the role dropdown (or an admin demotes the one other admin while
"tidying up"). The role is applied immediately; the next request from that account is evaluated
as an operator. No account can now reach `/api/users`, `/api/users/{id}/permissions`, or role
management at all. Recovery requires a manual `UPDATE users SET role='admin'` against Oracle —
i.e. a DBA and a change ticket.

**Suggested fix direction:** extract the last-admin check from `bulkReassignRoles` into a shared
helper and call it from `updateUserAdmin` too; add the same self-target rejection.

---

## SEC-13 — A parent-department manager sees an empty team

**Severity: Low · CONFIRMED**
`web/StatsController.java:227-232`

For a non-admin the manager branch does
`dept = user.getDepartment(); candidates = userRepository.findByActiveTrueAndDepartment(dept)` —
exact string equality. A manager whose own department is stored as the bare parent
(`"ტექნიკური"`) while their operators are stored as `"ტექნიკური — ჯგუფი 03"` matches nobody.

This is under-inclusive, not a leak, which is why it is Low — but it is the mirror image of
SEC-11 and it produces a confusing, silent "your team has no members" screen rather than an
error. `DirectMessagePermission`'s javadoc (lines 13-28) documents that this exact parent/child
situation is real and was already fixed once for messaging.

**Suggested fix direction:** use `DepartmentMatcher.matches(candidate.getDepartment(),
List.of(user.getDepartment()))` for the manager branch, matching the messaging rule.

---

## SEC-14 — Logout cannot invalidate a token that is already out there

**Severity: Low · CONFIRMED**
`web/AuthController.java:111-120`, `security/JwtService.java:63-74`,
`security/JwtAuthenticationFilter.java:57-78`

`logout` only clears the cookie. The token itself stays valid for its full 60 minutes
(`application.yml:68`); there is no `jti`, no server-side deny-list, no token-version column.
Anyone holding a copy of the bearer token — a shared machine's browser storage, a proxy log, a
copied `Authorization` header — keeps access after the user logs out.

Correctly handled, and worth recording as verified rather than assumed: authorisation is
re-read from the database on **every** request (`JwtAuthenticationFilter.java:62-74`), so a role
change or a deactivation takes effect immediately regardless of the token's remaining life.
Signature/expiry validation is delegated to JJWT's `verifyWith(signingKey).parseSignedClaims`
(`JwtService.java:65-69`), which rejects unsigned (`alg: none`) tokens and algorithm-confusion
attempts by construction, and returns `Optional.empty()` on any failure so a malformed or
missing token simply yields an unauthenticated request. `JwtAuthenticationFilterTest` (8 tests)
passes. There is no `iss`/`aud` claim validation, which matters only if a second system ever
signs with the same secret.

**Suggested fix direction:** acceptable to accept as a documented risk given the 60-minute
lifetime; if not, add a `jti` plus a short-lived deny-list (Redis) checked in the filter, or a
`token_version` on `users` bumped on logout/password change and compared against a claim.

---

## SEC-15 — Unhandled enum parsing returns 500

**Severity: Low · CONFIRMED**
`web/MessagingController.java:188`; repo-wide: no `@ControllerAdvice`/`@ExceptionHandler`

`Role.fromValue(targetRole)` (`Role.java:32-37`) throws `IllegalArgumentException` for an
unknown value. `UserController` wraps every one of its calls in try/catch and returns a clean
400 (`:164-169`, `:324-329`, `:392-397`); `MessagingController.postBroadcast:188` does not. A
grep for `ControllerAdvice|ExceptionHandler` across the backend returns nothing, so the
exception surfaces as Spring's default 500 with a stack trace in the logs.

**Failure scenario:** a client (or a stale frontend build) posts `target_role: "user"` to
`/api/broadcast` and gets an opaque 500 instead of "unknown role"; the log fills with stack
traces that look like a server fault. Low impact, but it is also the reason a global handler is
worth having: with `permitAll` + hand-rolled guards, any unexpected exception in any of the 17
controllers currently reaches the client as a default error page.

**Suggested fix direction:** wrap this call like the three in `UserController`, and add a small
`@ControllerAdvice` mapping `IllegalArgumentException` → 400 and everything else → a generic 500
body without internals.

---

## SEC-16 — `application.yml` tells the deployer a safety net does not exist

**Severity: Low · CONFIRMED**
`src/main/resources/application.yml:63-65` vs `config/ProductionSafetyGuard.java`

The comment above `jwt.secret` reads: *"config.py's startup guard (config.py:121-124) refuses to
boot if this exact string is still set with APP_ENV=production; **no Java equivalent exists yet
(todo)**"*. `ProductionSafetyGuard` was added since and does exactly that
(`ProductionSafetyGuard.java:26-43`, javadoc line 10-13 says it closed this todo).

The file a deployer opens first now understates the protection they have, while SEC-01 and
SEC-07 are cases where they would over-estimate it. Both directions of stale comment are worth
fixing together.

**Suggested fix direction:** update the comment to point at `ProductionSafetyGuard` and state
plainly what it does and does not check (see SEC-07).

---

## Checked and found sound

Recorded so the next audit does not redo them:

- **Password policy (prompt item 7) — enforced at every entry point.** `PasswordPolicy.validate`
  is called in all three places a password is set: `UserController.changeOwnPassword:136`,
  `createUserAdmin:388`, `adminResetPassword:446`. There is no fourth path —
  `updateUserAdmin` (`:314-348`) does not touch the password, and `AuthenticationService`'s
  JIT dummy password (`:50, 108`) is never a user-chosen credential and is unreachable in a
  correctly configured production (SEC-01 notwithstanding).
- **CORS (prompt item 10) — not wide open; there is no CORS configuration at all.** A repo-wide
  grep for `Cors|CrossOrigin|allowedOrigin` across `src/main/java` and `src/main/resources`
  returns zero hits, so Spring emits no `Access-Control-Allow-Origin` and browsers refuse
  cross-origin calls by default. This is safe because the deployment is same-origin: nginx
  serves the Angular bundle and proxies `/api/` and `/uploads/` to the backend
  (`angular-frontend/nginx.conf.template:16-32`); local development uses
  `angular-frontend/proxy.conf.json` for the same reason. No pre-go-live CORS risk. (The CORS
  backlog item in `docs/QUESTIONS_FOR_IT.md` is untouched by this.)
- **Department visibility in content and search uses the prefix-aware rule correctly.**
  `assertArticleVisible` (`ArticleController.java:1208-1224`), `ArticleQueryService:76`,
  `SearchQueryService:133,186,206` and `ComplianceCalculator.computeProgress:96-101` all use
  `DepartmentMatcher`. The two deliberate exact-match exceptions — `EligibleOperatorsService`
  (javadoc :17-24) and `relatedArticleDeptMatches` (`ArticleController.java:1235-1249`) — are
  documented as intentional parity with the Python source and are internally consistent.
  The global-search cache key includes role and department
  (`SearchController.java:93`), so cached results cannot leak across visibility scopes.
- **Manager scoping on the audit log is correct.** `AuditLogController.scopeDepartment:205-207`
  pins a manager to their own department for `/api/audit-logs`, and export/verify/chain-health
  are additionally closed to managers entirely (`:249-258`).
- **Article history/diff/versions do enforce visibility.** The endpoints that are only
  `requireAuthenticated` (`ArticleController.java:759, 854`) both call `assertArticleVisible`
  immediately afterwards (`:769, :864`), so an operator cannot read history of an article they
  cannot see.

---

## შემაჯამებელი მიმოხილვა (არატექნიკური)

ქვემოთ მოკლედ, ჟარგონის გარეშე — რა ვნახე უსაფრთხოების შემოწმებისას და რატომ აქვს
მნიშვნელობა. სულ 16 შენიშვნაა: **1 კრიტიკული, 3 მაღალი, 8 საშუალო, 4 დაბალი.**

**ყველაზე მთავარი (კრიტიკული).** სისტემას აქვს სატესტო რეჟიმი, რომელშიც რამდენიმე
ცნობილ მისამართზე (მაგალითად `admin@magti.ge`) შესვლა შესაძლებელია **ნებისმიერი
პაროლით** — ანუ საერთოდ პაროლის ცოდნის გარეშე. ეს რეჟიმი ჩაქრობილი უნდა იყოს რეალურ
გაშვებაზე, მაგრამ დღეს პროგრამა ისეა აწყობილი, რომ **ჩართულია ავტომატურად**, და
გამორთვისთვის საჭიროა, რომ დამნერგავმა ხელით მიუთითოს ერთი კონკრეტული პარამეტრი
(`APP_ENV=production`). თუ ამას დაავიწყდება — და დღეს ვერცერთი ფაილი ვერ დააყენებს ამას
ავტომატურად — პროგრამა ჩვეულებრივად ჩაირთვება, არაფერს გვაფრთხილებს, და ნებისმიერს,
ვისაც პორტალის მისამართი აქვს, შეეძლება შევიდეს **სისტემური ადმინისტრატორის უფლებებით**:
დაათვალიეროს და შეცვალოს მომხმარებლები, ჩამოტვირთოს ყველა ანგარიში, წაიკითხოს მთელი
აუდიტის ჟურნალი. ეს არის ერთი დავიწყებული პარამეტრი, რომელიც მთელ სისტემას აღებს.

**სამი მაღალი რისკი.**

1. **ანგარიშების ექსპორტი ზედმეტად ბევრს აძლევს.** ჯგუფის უფროსს (მენეჯერს)
   ავტომატურად აქვს ექსპორტის უფლება, ხოლო ექსპორტის ფაილი შეიცავს **მთელი კომპანიის**
   ~600 თანამშრომლის სახელს, დეპარტამენტს და ვის რა აქვს წაკითხული — და არა მხოლოდ მის
   საკუთარ ჯგუფს. ეს განსაკუთრებით შესამჩნევია იმიტომ, რომ იმავე მენეჯერს პორტალის
   შიგნით სხვა დეპარტამენტის ნახვა უკვე აკრძალული აქვს — ანუ განზრახვა გასაგებია,
   უბრალოდ ექსპორტამდე ეს შეზღუდვა არ მისულა. ფაილი კი პორტალიდან გარეთ გადის: კომპიუტერზე,
   ფოსტაზე, სადაც მოხვდება.
2. **დეპარტამენტების დაფა ყველას ყველაფერს აჩვენებს.** ერთი კონკრეტული გვერდი
   („დეპარტამენტების სტატისტიკა") ნებისმიერ მენეჯერს უჩვენებს **ყველა** დეპარტამენტის
   თანამშრომლების სიას, სახელებით, თანამდებობებით და ინდივიდუალური პროცენტებით — მათ
   შორის იმას, ვინ ჩამორჩება. იმავე ინფორმაციას იგივე მენეჯერი სხვა გვერდიდან ვერ იღებს
   (იქ სწორად ეკრძალება).
3. **შესვლის შეზღუდვა მთელ კომპანიაზე ერთად მუშაობს.** დაცვა, რომელიც „წუთში 10 მცდელობაზე
   მეტს" კეტავს, ვერ არჩევს კონკრეტულ ადამიანს — სერვერისთვის ყველა მომხმარებელი ერთი და
   იმავე მისამართიდან ჩანს (რადგან შუალედური სერვერია გზაზე). შედეგი: ერთ ადამიანს
   შეუძლია 10 მცდელობით **მთელი კომპანიის შესვლა** დროებით გათიშოს; და პირიქით — რეალურ
   თავდამსხმელს ეს დაცვა ვერაფერს უშლის. იმავე მიზეზით, აუდიტის ჟურნალში ჩაწერილი
   IP-მისამართი არავის რეალურ მისამართს არ ასახავს — ანუ გამოძიებისთვის უსარგებლოა.

**საშუალო შენიშვნებიდან რაც ღირს ცოდნა.** ცხრა უფლებიდან სამი (მომხმარებლების მართვა,
სავალდებულო მასალის დანიშვნა, სტატიების ნახვა) ინტერფეისში გამოჩნდება ჩამრთველად, მაგრამ
სინამდვილეში არაფერზე მოქმედებს — ანუ ადმინისტრატორს ჰგონია, რომ უფლება ჩამოართვა,
სინამდვილეში კი არა. ატვირთული ფაილები (დანართები) ხელმისაწვდომია **ავტორიზაციის გარეშე**,
თუ ვინმეს ბმული აქვს. აუდიტის „ხელშეუხებლობის" მექანიზმი კარგად იჭერს ჩვეულებრივ
ჩარევას, მაგრამ ვერ იცავს იმისგან, ვისაც ბაზაზე პირდაპირი წვდომა აქვს — ეს ღირს ცოდნა,
რომ ცრუ დაცულობის განცდა არ შეიქმნას. საერთო შეტყობინების („broadcast") გაგზავნა
დეპარტამენტზე **ვერავის აღწევს**, ვინც ქვე-ჯგუფშია (მაგ. „ტექნიკური — ჯგუფი 03"), თუმცა
გამგზავნს „წარმატებას" უჩვენებს. და ბოლოს: ერთადერთი სისტემური ადმინისტრატორის როლის
შეცვლა შესაძლებელია ისე, რომ სისტემა სამუდამოდ დარჩეს ადმინისტრატორის გარეშე — აღდგენა
მხოლოდ ბაზაში ხელით ჩარევით მოხერხდება.

**რაც შემოწმდა და წესრიგშია.** პაროლის სირთულის მოთხოვნა მართლაც მოქმედებს ყველგან, სადაც
პაროლი იქმნება ან იცვლება (სამივე ადგილას). ე.წ. CORS-ის საკითხი, რომელზეც ადრე იყო
შეშფოთება, დღეს რისკი **არ არის** — კონფიგურაცია ისეა აწყობილი, რომ საიტი და სერვერი ერთი
მისამართიდან მუშაობს. სტატიების და ძებნის ხილვადობა დეპარტამენტების მიხედვით სწორად
ფილტრავს. აუდიტის ჟურნალში მენეჯერი მართლაც მხოლოდ თავის დეპარტამენტს ხედავს. და
ავტორიზაციის ტოკენი ყოველ მოთხოვნაზე ბაზას ამოწმებს — ანუ თანამშრომლის გათიშვა ან როლის
შეცვლა მაშინვე მოქმედებს, არ ელოდება ტოკენის ვადის გასვლას.

**რეკომენდაცია რიგითობით:** პირველ რიგში SEC-01 (ერთი პარამეტრი, რომელიც სისტემას აღებს) —
ეს გასაშვებამდე უნდა დაიხუროს. შემდეგ SEC-02 და SEC-03 (პერსონალური მონაცემების გაჟონვა
დეპარტამენტებს შორის), შემდეგ SEC-04. დანარჩენი შეიძლება დაიგეგმოს გაშვების შემდეგ,
მაგრამ SEC-06 (მატყუარა უფლებების ჩამრთველები) ღირს ადრე, სანამ ადმინისტრატორები მათ
ენდობიან.
