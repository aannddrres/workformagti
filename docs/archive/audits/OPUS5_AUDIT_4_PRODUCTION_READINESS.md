> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# OPUS5 Audit 4 — Production readiness & operations

**Date:** 2026-08-14
**Scope:** build/deploy artifacts (`java-backend/Dockerfile`, `angular-frontend/Dockerfile`,
`nginx.conf.template`, `.github/workflows/ci.yml`), runtime configuration, scheduled work,
logging, and failure modes of the two container images.
**Mode:** read-only. No source file was modified, nothing committed, nothing pushed.
**Context read first:** `CLAUDE.md`, `docs/QUESTIONS_FOR_IT.md` (K8s cluster specifics that are
blocked on Magti IT are not re-raised as findings here).

## Verification method

- Secrets were searched across **tracked files only** (`git ls-files | xargs grep`), not the
  working tree, so build output and untracked local files could not produce false positives.
  `java-backend/target/` is confirmed untracked (0 files under it in `git ls-files`).
- `mvn -B test`: 383 tests, 0 failures, 217 errors — all `ORA-12541` (no Oracle in this
  container). 166 unit tests pass.
- `npm ci` in `angular-frontend/` succeeded (exit 0). `npx ng test --watch=false` **could not
  run**: *"The Angular CLI requires a minimum Node.js version of v22.22.3"* against this
  container's v22.22.2 — see PR-10, which is a finding in its own right.
- Framework behaviour was verified against the actual jars in the local Maven repository rather
  than assumed (see "Scheduled work" below).

## Findings summary

| # | Severity | Status | Finding |
|---|----------|--------|---------|
| PR-01 | **High** | CONFIRMED | CI builds and tests only the retired Python app — the Java backend and Angular frontend are never compiled or tested |
| PR-02 | **High** | CONFIRMED | PDF export cannot work in the Java image: no Georgian-capable font is installed |
| PR-03 | **High** | CONFIRMED | Uploads and export files are written to the pod's own disk — lost on restart, invisible to other replicas |
| PR-04 | **High** | CONFIRMED | The login rate limiter is per-pod, in-memory, and keyed on the proxy's IP |
| PR-05 | Medium | CONFIRMED | A real-looking Oracle password is committed as a default in `application.yml` |
| PR-06 | Medium | CONFIRMED | No `.dockerignore` anywhere — the 183 MB dev database and `.git` go into every build context |
| PR-07 | Medium | CONFIRMED | The frontend container runs nginx as root, unlike the backend image |
| PR-08 | Medium | CONFIRMED | Six log statements in the entire backend — nothing to diagnose an incident with |
| PR-09 | Medium | CONFIRMED | `/api/health` returns raw database error text to unauthenticated callers |
| PR-10 | Medium | CONFIRMED | No Node version pinning; the frontend build fails on a Node that is one patch old |
| PR-11 | Low | CONFIRMED | No JVM heap sizing in the image |
| PR-12 | Low | CONFIRMED | The `@Async` export executor is the shared default with an unbounded queue |
| PR-13 | Low | CONFIRMED | The cleanup scheduler runs on every replica |
| PR-14 | Low | CONFIRMED | No Hikari connection-timeout / leak-detection settings |

**Totals: Critical 0 · High 4 · Medium 6 · Low 4 (14 findings).**

---

## PR-01 — CI tests the app that is being replaced, and nothing else

**Severity: High · CONFIRMED**
`.github/workflows/ci.yml` (whole file, 26 lines)

The only workflow in the repository does four things: check out, set up Python 3.11,
`pip install -r requirements-dev.txt`, `ruff check .`, `pytest tests/ -q`.

There is no `mvn test`, no `mvn package`, no `ng build`, no `ng test`, no Docker build step, and
no other workflow file (`.github/workflows/` contains only `ci.yml`).

So the two artifacts that are actually going to production — a 5,400-line Java backend with 383
tests, and an Angular app with 17 feature areas — are never compiled, never tested and never
built into an image by CI. Every pull request goes green on the strength of the legacy Python
suite alone.

**Failure scenario:** a change breaks the Java build, or a Flyway migration collides, or an
Angular template stops compiling. CI reports success. The break is discovered by hand, at deploy
time or later — which is exactly what happened during this audit for `ExportControllerIntegrationTest`
(fixed in commit `e845c1a`) and would have happened again silently.

Worth noting the interaction with the integration suite: 217 of the 383 Java tests need a live
Oracle 19c. A CI job that just runs `mvn test` on a runner without a database will fail exactly
as it did here. Adding CI therefore also means deciding how those tests get a database
(Testcontainers with `gvenzl/oracle-free`, or a service container, or splitting unit from
integration with a Maven profile so at least the 166 unit tests gate every PR).

**Suggested fix direction:** add jobs for `mvn -B test` (with a database strategy) and
`npm ci && npx ng build && npx ng test --watch=false`, and consider `docker build` on both images
so a broken Dockerfile is caught before deployment rather than during it.

---

## PR-02 — PDF export is guaranteed to fail in the container

**Severity: High · CONFIRMED**
`export/GeorgianPdfFont.java:26-31`, `export/PdfExportBuilder.java:52`,
`java-backend/Dockerfile` (whole file), vs root `Dockerfile:27`

`GeorgianPdfFont` resolves a Georgian-capable TTF from a fixed candidate list:

```java
"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
"/usr/share/fonts/dejavu/DejaVuSans.ttf",
"C:/Windows/Fonts/dejavusans.ttf",
"C:/Windows/Fonts/sylfaen.ttf",
```

`PdfExportBuilder.java:52` calls `GeorgianPdfFont.load(document).orElseThrow(PdfFontUnavailableException::new)`
— no font, no PDF.

`java-backend/Dockerfile`'s runtime stage is `eclipse-temurin:21-jre-alpine` with **no package
installation at all** (no `apk add`), so none of those four paths exists in the image. The Python
side does install the font — root `Dockerfile:27` includes `fonts-dejavu-core` in its `apt-get`
line — and `GeorgianPdfFont`'s own javadoc anticipated this: *"The Docker/production candidate
expects `fonts-dejavu-core` installed the same way the Python Dockerfile already does … no
Java-side Dockerfile exists yet to wire that into, so this is a note for whenever one is
written."* The Dockerfile was written since, and the note was not carried across.

Also note the Alpine wrinkle: even once a font is installed, Alpine's package is
`ttf-dejavu` (path `/usr/share/fonts/ttf-dejavu/DejaVuSans.ttf`), which matches **none** of the
four candidates. Installing the Debian-named package on Alpine will not fix this by itself —
either the candidate list or the base image needs to change.

**Failure scenario:** a manager clicks "export PDF". The job is enqueued, the worker throws
`PdfFontUnavailableException`, `ExportJobWorker.java:62-65` catches it and marks the job
`failed`, and the UI shows "export failed" with no reason. Every time, for every user, forever —
and because the degradation is graceful, nothing in the logs looks like an outage
(`ExportJobWorker.java:63` logs a single `warn` with the message).

**Suggested fix direction:** add the font to the runtime stage (`RUN apk add --no-cache
ttf-dejavu` on Alpine, or switch to a Debian-based `eclipse-temurin:21-jre` and use
`fonts-dejavu-core`) **and** add the matching path to `GeorgianPdfFont.CANDIDATES`. Better:
bundle the TTF as a classpath resource so the export cannot depend on the base image at all. A
test asserting `GeorgianPdfFont.resolvePath().isPresent()` would fail loudly in CI (once PR-01
exists) instead of silently at runtime.

---

## PR-03 — Uploads and exports live on one pod's disk

**Severity: High · CONFIRMED**
`config/PortalProperties.java:18`, `application.yml:60`, `web/UploadController.java:95-98`,
`export/ExportJobWorker.java:51-54`, `java-backend/Dockerfile` (no `VOLUME`, no mount),
`config/WebConfig.java:23-27`

`portal.uploads-dir` defaults to the relative path `uploads` (`PortalProperties.java:18`,
`application.yml:60`), resolved against the process working directory — `/app` in the image. The
Dockerfile creates `/app/uploads` and `chown`s it, but declares no `VOLUME`, and there are no
Kubernetes manifests in the repository to attach a `PersistentVolumeClaim`.

Two distinct consequences:

1. **Restart = data loss.** A container filesystem is ephemeral. Every pod restart, rollout,
   node drain or scale-down permanently deletes every uploaded attachment. The database still
   holds the `attachment_url` (`/uploads/<uuid>.pdf`), so articles keep linking to files that
   return 404. Nothing warns anyone; the loss surfaces one broken link at a time.
2. **More than one replica = broken by design.** `UploadController` writes to the pod that
   happened to serve the upload; `/uploads/**` is served by `WebConfig`'s local resource handler
   on whichever pod the read is load-balanced to. With *n* replicas, an attachment is visible
   about 1/*n* of the time. The identical problem hits export files
   (`ExportJobWorker.java:51-54` writes locally while the job row is in shared Oracle) — see
   Audit 2, BL-09, and Audit 3, FE-03, for how that surfaces as an infinite spinner.

`docs/QUESTIONS_FOR_IT.md` §4 already asks IT about storage-adjacent cluster details, so the
information needed to fix this may be pending — but the requirement is a code/deployment fact
that does not depend on their answer: this application cannot run as more than one replica, and
cannot survive a restart, until uploads move off the pod.

**Suggested fix direction:** a `ReadWriteMany` PersistentVolume mounted at `/app/uploads` for
both replicas, or object storage (S3-compatible) behind the upload/serve paths, or — smallest
change — store attachment bytes in Oracle as BLOBs. Whichever is chosen, add it to the
K8s manifests when they are written, and note in `QUESTIONS_FOR_IT.md` that shared storage is a
hard prerequisite rather than an optimisation.

---

## PR-04 — The rate limiter is per-pod and keyed on the wrong address

**Severity: High · CONFIRMED**
`security/LoginRateLimiter.java:18-33`, `web/AuthController.java:61`,
`application.yml:48-49` (no `forward-headers-strategy`),
`angular-frontend/nginx.conf.template:22-27`

The prompt asks specifically whether this limiter's state is in-memory per pod or externalised.
It is **in-memory per pod**: a plain `ConcurrentHashMap<String, Deque<Instant>>` field
(`LoginRateLimiter.java:33`) with no Redis or database backing anywhere in the codebase.

The class javadoc argues this is fine: *"Spring Boot runs as a single JVM by default (no
multi-process worker model), so a single shared in-memory counter here is already correct."*
That reasoning is sound for one pod and stops being true the moment the K8s Deployment has
`replicas: 2` — which is the stated deployment target. With *n* replicas the effective limit
becomes *n* × 10 attempts/minute, and an attacker gets *n* buckets for free simply by being
load-balanced.

Compounding it, the key is `httpRequest.getRemoteAddr()` (`AuthController.java:61`) while
`server.forward-headers-strategy` is unset, so behind nginx (`nginx.conf.template:22-27` sets
`X-Real-IP`/`X-Forwarded-For`, which Spring is not configured to read) every user resolves to the
proxy's address. See Audit 1, SEC-04, for the security consequences — the operational one is
that **10 login attempts per minute is the ceiling for the entire company**, so a single retrying
user can lock everyone else out.

**Suggested fix direction:** move the counter to Redis (already in the Python-side stack) keyed
on `email + real client IP`, and set `server.forward-headers-strategy=framework` together with an
explicit trusted-proxy configuration. Until then, the deployment is effectively pinned to one
replica for reasons beyond PR-03.

---

## PR-05 — A real-looking database password is committed

**Severity: Medium · CONFIRMED**
`java-backend/src/main/resources/application.yml:15`

```yaml
password: ${ORACLE_DB_PASSWORD:MagtiAppDev2026Pw}
```

The comment above it (lines 7-14) explains the reasoning honestly — it points at a dev-only
localhost Oracle with no real data, and avoids making the non-technical project owner set an env
var by hand. That is a defensible local-development trade-off, and it is the *only* such value:
a search across all tracked files for assigned password/secret/key literals returns exactly two
non-placeholder hits, this one and `ProductionSafetyGuard.java:18`'s dev-secret constant (which
exists precisely so the guard can *reject* it).

The problem is what the string looks like rather than what it currently protects.
`MagtiAppDev2026Pw` is shaped like a real corporate password — organisation name, environment,
year, suffix. Once it is in git history it is in every clone and every fork forever, and it is
the obvious first guess for the pattern `MagtiApp<Env><Year>Pw` on any other Magti system. No
guard covers it either: `ProductionSafetyGuard` checks the JWT secret and the cookie flag
(`ProductionSafetyGuard.java:31-42`), not the database password, so a production deployment that
forgets `ORACLE_DB_PASSWORD` starts up and silently tries this one.

**Failure scenario:** the deployment sets every env var except `ORACLE_DB_PASSWORD`. The app
fails to connect and crash-loops — a good outcome. The bad outcome is the reverse: somebody sets
the *real* Oracle account's password to this committed string because it is what the repo
implies, and the production credential is now public.

**Suggested fix direction:** replace the default with an obvious placeholder
(`CHANGE_ME_LOCAL_ONLY`) as `java-backend/.env.example:26` already does, and extend
`ProductionSafetyGuard` to reject known placeholder/dev database passwords when
`APP_ENV=production` — the same treatment the JWT secret gets (and see Audit 1, SEC-07, for why
that check needs strengthening too).

---

## PR-06 — No `.dockerignore`, so a 183 MB dev database is in every build context

**Severity: Medium · CONFIRMED**
No `.dockerignore` at the repository root, in `java-backend/`, or in `angular-frontend/`
(verified by direct lookup); `angular-frontend/Dockerfile:11` (`COPY . .`)

Every `docker build` uploads the entire directory tree to the daemon. In this repository that
includes `magti_portal.db` (~183 MB of local dev data, which `CLAUDE.md` explicitly says to keep
and not delete), the whole `.git` history, `java-backend/target/`, and — if present locally —
`angular-frontend/node_modules/` and any developer's `.env`.

For the Angular image this is not only slow: `COPY . .` at `Dockerfile:11` runs **after**
`npm ci`, so a host `node_modules` overwrites the freshly installed one (a classic
platform-mismatch build failure), and a local `.env` would be copied into an image layer where
it survives even if a later stage does not use it.

**Failure scenario:** a developer or CI runner builds the image on a machine with a local `.env`
and `node_modules`. The build is minutes slower, the image carries a credentials file in an
intermediate layer, and the frontend build may fail for a reason that has nothing to do with the
code.

**Suggested fix direction:** add `.dockerignore` files covering at least `.git`, `*.db`,
`node_modules`, `target`, `.env*`, `dist`, and `uploads`.

---

## PR-07 — The frontend container runs as root

**Severity: Medium · CONFIRMED**
`angular-frontend/Dockerfile` (runtime stage, no `USER`) vs `java-backend/Dockerfile:18-27`

The backend image does this correctly: it creates `appgroup`/`appuser` (uid/gid 1000), `chown`s
`/app`, and switches with `USER appuser` before the entrypoint. The frontend image ends at
`COPY nginx.conf.template …` with no `USER` directive, so the stock `nginx:1.27-alpine`
behaviour applies — the master process runs as root (workers drop to `nginx`).

Nothing forces this: `nginx.conf.template:2` already listens on **8080**, a non-privileged port,
which is the usual blocker for running nginx unprivileged.

**Failure scenario:** a container escape or an nginx vulnerability starts from uid 0 instead of
an unprivileged user. Many clusters also enforce a `runAsNonRoot` PodSecurity policy, in which
case this image simply refuses to start on the real cluster — a deployment-day surprise rather
than a security incident.

**Suggested fix direction:** use `nginxinc/nginx-unprivileged:1.27-alpine`, or add a `USER nginx`
plus the writable `/var/cache/nginx`, `/var/run` directories the stock image needs. Since the
cluster's PodSecurity settings are an open question for IT
(`docs/QUESTIONS_FOR_IT.md` §4), aligning with the backend image now avoids re-testing later.

---

## PR-08 — There is almost nothing in the logs

**Severity: Medium · CONFIRMED**
Repo-wide log-statement inventory across `java-backend/src/main/java`

The prompt asks whether a password, JWT or other sensitive value can reach a log line. The
answer is **no**, and the reason is that the backend barely logs at all. Every logging statement
in the entire application:

| Location | Level | Content |
|---|---|---|
| `web/AuditLogController.java:233` | error | meta-audit write failure — action + `adminId` |
| `web/ComplianceController.java:241` | warn | notification generation failure — reading id |
| `export/ExportJobWorker.java:63` | warn | export build failure — job id + message |
| `export/ExportJobCleanupScheduler.java:49` | info | count of expired jobs removed |
| `export/ExportJobCleanupScheduler.java:59` | warn | file deletion failure — path |
| `audit/AuditLogQueryService.java:253` | warn | invalid date filter format |

Six statements, none carrying a credential, a token, a request body or an email address. There
is no `System.out` anywhere. So the sensitive-logging question is clean.

The operational problem is the other side of the same fact. There is no request logging, no
authentication-outcome logging, no startup summary of effective configuration, and — critically
— **no logging on any of the security-relevant decisions this audit found**: the dev-login
bypass being active (Audit 1, SEC-01) produces no log line at all; a 403 from any `requireX`
helper produces none; an unhandled exception reaches the client as a 500 with only Spring's
default stack trace, since there is no `@ControllerAdvice` (Audit 1, SEC-15).

**Failure scenario:** users report "the portal is broken". The pod logs contain Spring's startup
banner and nothing else. There is no way to tell which endpoint failed, for whom, how often, or
whether the app is running with the dev-login bypass enabled — the audit table records business
actions but not operational events.

**Suggested fix direction:** log the effective security-relevant configuration once at startup
(`APP_ENV`, whether the dev bypass is live, cookie `secure`, pool size) — that single line would
have made SEC-01 visible. Add a `@ControllerAdvice` that logs unhandled exceptions with a
correlation id, and consider Spring Boot's request logging at `INFO` for non-2xx responses.

---

## PR-09 — `/api/health` hands raw database errors to anonymous callers

**Severity: Medium · CONFIRMED**
`web/HealthController.java` (the `catch` branch), `security/SecurityConfig.java:45`

The health endpoint runs a real `SELECT 1 FROM dual` and, on failure, returns
`body.put("database", "error: " + e.getMessage())`. With `anyRequest().permitAll()` and no
authentication on the path, that message is readable by anyone who can reach the service.

Oracle JDBC exception messages routinely carry the host, port, service name and `ORA-` code —
for example `ORA-12541: Cannot connect. No listener at host localhost port 1521`, which is
exactly the string this audit's own test run produced. That is internal topology detail handed
out unauthenticated, and it is the kind of endpoint an ingress is most likely to expose.

The endpoint is otherwise well built — a real liveness probe rather than a hardcoded `"ok"`, and
honest about Redis being `not_configured` rather than faking it — so this is a small change, not
a redesign. Related and lower-risk: `spring-boot-starter-actuator` is a dependency with no
`management.endpoints` configuration, so Boot's default applies (only `/actuator/health` exposed
over HTTP, details hidden); that default is safe, but it is a default rather than a decision.

**Suggested fix direction:** return a fixed `"error"` string in the body and log the detail
server-side.

---

## PR-10 — Nothing pins the Node version, and one patch release breaks the build

**Severity: Medium · CONFIRMED**
`angular-frontend/package.json` (no `engines` field), no `.nvmrc`, observed directly

Running the frontend test suite in this container failed before executing a single test:

```
Node.js version v22.22.2 detected.
The Angular CLI requires a minimum Node.js version of v22.22.3 or v24.15.0 or v26.0.0.
```

`npm ci` had already succeeded, so the dependency tree is fine — the CLI itself refuses to run.
The repository declares no `engines` constraint and carries no `.nvmrc`, so nothing communicates
the required version to a developer, a CI runner, or a build container. The Docker build happens
to work because `angular-frontend/Dockerfile:2` pins `node:22-alpine`, which today resolves to a
new enough patch — an implicit dependency on whatever the tag points at that day.

**Failure scenario:** a CI runner or a developer machine sits one patch release behind and the
frontend cannot be built or tested, with an error that reads like an environment problem rather
than a missing project constraint. (This is also why `ng test` results are absent from this
audit.)

**Suggested fix direction:** add `"engines": { "node": ">=22.22.3" }` to `package.json` and a
`.nvmrc`, and pin the Dockerfile to a specific patch tag rather than the floating `node:22-alpine`.

---

## PR-11 — No heap sizing in the image

**Severity: Low · CONFIRMED**
`java-backend/Dockerfile` (`ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport"`)

`UseContainerSupport` has been on by default since JDK 10, so this line is a no-op on the Temurin
21 base. What is missing is the setting that actually matters in a container:
`-XX:MaxRAMPercentage`. The JVM default gives the heap 25% of the container limit, so a pod with
a 2 GiB limit runs with a ~512 MiB heap and leaves most of its memory allocation unused — while a
pod without a limit sizes against the whole node.

**Suggested fix direction:** set `-XX:MaxRAMPercentage=75.0` and choose the container memory
limit deliberately when the K8s manifests are written.

---

## PR-12 — The export worker uses the shared default executor

**Severity: Low · CONFIRMED**
`PortalBackendApplication.java:18` (`@EnableAsync`), `export/ExportJobWorker.java:44`;
no `TaskExecutor`/`Executor` bean anywhere in `src/main/java` (verified by grep)

With `@EnableAsync` and no executor bean, `@Async` resolves to Boot's auto-configured
`applicationTaskExecutor` — 8 core threads and an effectively unbounded queue. Export builds are
CPU- and memory-heavy (POI holds a whole `XSSFWorkbook` in memory, `XlsxExportBuilder`'s javadoc
notes it is the non-streaming variant), and they share this executor with anything else Spring
schedules onto it.

**Failure scenario:** several managers trigger large exports at once. Requests queue without
limit rather than being rejected, memory climbs, and there is no visibility into the backlog —
`ExportSizeGuard` caps rows per export but not concurrent exports.

**Suggested fix direction:** define a dedicated bounded `ThreadPoolTaskExecutor` for exports with
a `CallerRunsPolicy` or an explicit rejection that surfaces as a "try again shortly" response.

---

## PR-13 — The cleanup scheduler runs on every replica

**Severity: Low · CONFIRMED**
`export/ExportJobCleanupScheduler.java:38-52`

`@Scheduled(fixedDelay = 10 min)` runs in every JVM, so with *n* replicas the sweep runs *n*
times. The work is close to idempotent (`findByExpiresAtLessThan` then delete), so the realistic
outcome is wasted queries and occasional harmless "file already gone" warnings at line 59 —
except that each pod can only delete *its own* files (PR-03), so expired files on other pods are
never removed by anyone.

**Suggested fix direction:** once PR-03's shared storage exists, this mostly resolves itself;
if not, use a scheduler lock (ShedLock) or a single-replica CronJob.

---

## PR-14 — No connection-timeout or leak detection on the pool

**Severity: Low · CONFIRMED**
`application.yml:25-27`

The Hikari block sets `maximum-pool-size` and `minimum-idle` and nothing else, so
`connection-timeout` (30 s), `max-lifetime` (30 min) and `leak-detection-threshold` (off) all take
their defaults. Given that Audit 2 found unbounded polling (FE-03) and long-running async work,
leak detection would be cheap insurance during the first weeks in production.

**Suggested fix direction:** set `leak-detection-threshold: 60000` at least temporarily, and
confirm `max-lifetime` sits below any Oracle-side or firewall idle timeout on the real instance.

---

## Checked and found sound

Recorded with the evidence, so the next audit does not redo them:

- **Secrets hygiene is otherwise clean — verified directly, not taken from `CLAUDE.md`.**
  Searching every tracked file for assigned password/secret/key/token literals returns exactly
  two non-placeholder hits: `application.yml:15` (PR-05) and `ProductionSafetyGuard.java:18`, the
  dev-secret constant that exists so the guard can reject it. No API keys, no bearer tokens, no
  private keys, no `.env` committed (none exists in the repository), and `java-backend/target/`
  is untracked. `docker-compose.yml` uses `${VAR}` substitution as `CLAUDE.md` claims, and
  `java-backend/.env.example` documents every variable with placeholder values.
- **Both Dockerfiles are properly multi-staged with health checks.** `java-backend/Dockerfile`
  caches Maven dependencies in a separate layer keyed on `pom.xml`, builds with a JDK image and
  runs on a JRE image, creates and switches to a non-root `appuser` (uid 1000), and health-checks
  `GET /api/health` every 30 s with a 30 s start period. `angular-frontend/Dockerfile` builds
  with `node:22-alpine` and ships only `dist/angular-frontend/browser` on `nginx:1.27-alpine`, so
  no build tooling reaches the runtime image, with its own health check. The gaps are PR-02
  (fonts), PR-06 (`.dockerignore`) and PR-07 (root).
- **The Hikari pool reads its size from the environment and is not dev-only.** `application.yml`
  is profile-independent (there are no `application-*.yml` profile files at all), so
  `maximum-pool-size: ${DB_POOL_MAX_SIZE:30}` and `minimum-idle: ${DB_POOL_MIN_IDLE:10}` apply in
  production and are overridable there. Headroom is comfortable: the comment at lines 17-24
  records Oracle's `processes` limit as 640 on the dev instance, so even four replicas
  (4 × 30 = 120) stay well under it — though `v$parameter` should be checked on the real
  production instance as that comment itself advises.
- **The scheduled job is genuinely wired, and an exception cannot kill it permanently.**
  `ExportJobCleanupScheduler` is a `@Component` with a `@Scheduled` method, and
  `PortalBackendApplication:19` carries `@EnableScheduling` — so it is live, not dead code
  (it is also the *only* `@Scheduled` bean in the application; see Audit 2, BL-06, for the daily
  compliance-alert job that was never ported). On the resilience question, the framework
  behaviour was verified rather than assumed: disassembling `ThreadPoolTaskScheduler` from
  `spring-context-7.0.8.jar` shows `scheduleWithFixedDelay`/`scheduleAtFixedRate` calling
  `errorHandlingTask(task, true)` — the repeating-task branch, which selects Spring's
  log-and-suppress error handler. An uncaught exception is logged and the next run still fires.
- **Export libraries cannot "fail to load", and a missing font degrades cleanly.** POI
  (`poi-ooxml`) and PDFBox are ordinary compile-scope dependencies in `pom.xml` (lines 155, 160),
  packaged into the fat jar — unlike the Python side's optional imports, they cannot be missing
  at runtime, so no 503-style degradation path is needed and none exists. The one real runtime
  dependency is the TTF font, and that path is handled properly: `PdfFontUnavailableException`
  propagates to `ExportJobWorker.java:62-65`, which catches `RuntimeException | IOException`,
  marks the job `failed`, and lets the application keep serving. The app does **not** crash — it
  just cannot produce PDFs (PR-02).
- **No sensitive value can reach a log line** — see PR-08 for the full six-statement inventory.

---

## შემაჯამებელი მიმოხილვა (არატექნიკური)

ეს ნაწილი ეხება არა კოდის ლოგიკას, არამედ იმას, **მზად არის თუ არა სისტემა რეალურ სერვერზე
გასაშვებად** — როგორ აიწყობა, სად ინახება ფაილები, რა ჩანს ლოგებში, გამოვა თუ არა პრობლემის
დიაგნოსტიკა. სულ 14 შენიშვნა: **0 კრიტიკული, 4 მაღალი, 6 საშუალო, 4 დაბალი.**

**ოთხი მთავარი პრობლემა.**

1. **ავტომატური შემოწმება (CI) ამოწმებს ძველ, ჩასანაცვლებელ პროგრამას.** დღეს ყოველ ცვლილებაზე
   ავტომატურად მხოლოდ ძველი Python-ის ტესტები გადის. ახალი Java-სერვერი (383 ტესტი) და Angular-ის
   საიტი **საერთოდ არ შენდება და არ იტესტება** — ანუ თუ ვინმე რაღაცას გატეხს, სისტემა მაინც
   „მწვანეს" აჩვენებს და პრობლემა მხოლოდ ხელით ან უკვე სერვერზე გამოჩნდება.

2. **PDF ექსპორტი კონტეინერში ვერასდროს იმუშავებს.** ქართული ტექსტის PDF-ში ჩასაწერად საჭიროა
   შრიფტის ფაილი. პროგრამა მას ოთხ კონკრეტულ ადგილას ეძებს — და Java-ს კონტეინერში ვერცერთი
   მათგანი არ არსებობს, რადგან შრიფტი იქ არ დაყენებულა (ძველ Python-ის კონტეინერში დაყენებულია).
   შედეგად ყოველი PDF-ექსპორტი ჩავარდება „ექსპორტი ვერ შესრულდა"-თი, ყოველთვის, ყველასთვის.

3. **ატვირთული ფაილები კონტეინერის შიგნით ინახება — ანუ დროებით.** დანართები და ექსპორტის
   ფაილები იწერება თავად სერვერის „ერთჯერად" დისკზე. ეს ორ პრობლემას ნიშნავს: (ა) კონტეინერის
   ყოველი გადატვირთვისას **ყველა ატვირთული ფაილი სამუდამოდ იკარგება** (სტატიებში ბმულები რჩება,
   ფაილები — არა); (ბ) თუ სისტემა ორ ან მეტ ასლად გაეშვება (რაც სწორედ Kubernetes-ის აზრია),
   ერთ ასლზე ატვირთული ფაილი მეორეზე უბრალოდ არ ჩანს. ანუ დღეს სისტემა ტექნიკურად მხოლოდ ერთ
   ასლში შეიძლება იმუშაოს.

4. **შესვლის შეზღუდვა ორ ასლზე ორმაგდება.** მცდელობების მთვლელი თითოეული ასლის მეხსიერებაშია —
   ორი ასლი ნიშნავს ორჯერ მეტ დაშვებულ მცდელობას. ამასთან, მთვლელი ვერ არჩევს ცალკეულ
   მომხმარებელს (დეტალები 1-ლ ანგარიშში).

**საშუალო შენიშვნებიდან რაც ღირს ცოდნა.** კონფიგურაციაში ჩაწერილია **რეალურად გამოსაყენებელივით
გამოიყურება პაროლი** ბაზისთვის (`MagtiAppDev2026Pw`) — თავად ის დღეს არაფერს იცავს, მაგრამ
ერთხელ git-ში მოხვედრილი ასეთი პაროლი სამუდამოდ საჯაროა და მისი ნიმუშით სხვა პაროლის გამოცნობა
ადვილდება. კონტეინერის აწყობისას ყოველ ჯერზე იგზავნება 183 მბ-იანი სატესტო ბაზაც და მთელი
git-ისტორია (არ არსებობს გამორიცხვის ფაილი). საიტის კონტეინერი root-ის უფლებებით ეშვება
(სერვერისა — სწორად, ჩვეულებრივი მომხმარებლით). და ბოლოს, **ლოგები პრაქტიკულად არ არსებობს** —
მთელ სერვერზე სულ 6 ჩანაწერია. ერთი მხრივ ეს კარგია (პაროლი ან ტოკენი ლოგში ვერ მოხვდება,
ეს სპეციალურად შევამოწმე), მაგრამ მეორე მხრივ ინციდენტის დროს **სანახავი არაფერია**: ვერ
გაიგებ, რომელი მოთხოვნა ჩავარდა, ვისთან და რამდენჯერ.

**რაც შემოწმდა და წესრიგშია.** საიდუმლო მონაცემების მხრივ რეპოზიტორია სუფთაა — მთელ პროექტში
მხოლოდ ორი „პაროლისმაგვარი" ჩანაწერია და ორივე ცნობილი და ახსნილი. ორივე კონტეინერი სწორადაა
აწყობილი (ორსაფეხურიანი აწყობა, სამუშაო ხელსაწყოები საბოლოო ხატში არ ხვდება, ავტომატური
„ჯანმრთელობის" შემოწმება). ბაზასთან კავშირების რაოდენობა კონფიგურაციიდან იმართება და Oracle-ის
ლიმიტს დიდი მარაგით ჯდება. დაგეგმილი ფონური სამუშაო მართლაც გაშვებულია და შეცდომის შემთხვევაში
**არ კვდება** — ეს სპეციალურად შევამოწმე ბიბლიოთეკის რეალურ კოდში. ექსპორტის ბიბლიოთეკები
პროგრამაშივეა ჩაშენებული, ანუ „დაკარგვა" შეუძლებელია, ხოლო შრიფტის არარსებობისას პროგრამა
**არ ვარდება** — უბრალოდ ექსპორტს ვერ ამზადებს.

**რეკომენდაცია რიგითობით:** PR-03 (ფაილების შენახვა) და PR-01 (CI) — ეს ორი დანარჩენზე ადრე
უნდა გადაწყდეს, რადგან პირველი განსაზღვრავს, საერთოდ როგორ შეიძლება სისტემის გაშვება, მეორე კი
იმას, დავინახავთ თუ არა შემდეგ ხარვეზს ავტომატურად. შემდეგ PR-02 (PDF შრიფტი — რამდენიმე ხაზი
Dockerfile-ში) და PR-04. PR-05 (პაროლი) ღირს გასაშვებამდე გასწორდეს.
