> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# Magti Internal Portal — Production Readiness Audit

**თარიღი:** 2026-08-23
**აუდიტის HEAD:** `f6d0eb1ad5fd0928f47079f2bfef6dab1181511d` (`codex/phase6-content-gates`)
**აუდიტის ინსტრუქცია:** `origin/claude/comprehensive-ready-to-go-prompt-qycacc` @ `50507771bf9723d1d90d0beacaaa34cb8155ca0c`

## ვერდიქტი — NOT READY

პორტალი ამ მდგომარეობით production-ში არ უნდა გაეშვას. ხუთი ერთმანეთისგან დამოუკიდებელი ბლოკერი მოიცავს არასწორ deployment artifact-ს, დაუცველ დანართებს, ადმინისტრატორის ანგარიშის ხელში ჩაგდების გზას, არასწორ ორგანიზაციულ scope-ს და Oracle backup/restore-ის არქონას. Java-ის იზოლირებული ტესტები წარმატებით დასრულდა, მაგრამ production stack, Oracle migration, Angular build/E2E და რეალური restore არ დამოწმებულა. თერთმეტივე IT-დამოკიდებულება ჯერ ღიაა, ამიტომ უსაფრთხო deploy/rollback-ის პასუხისმგებლობა და პროცედურა განსაზღვრული არ არის.

## ვერიფიკაციის მეთოდი

- ინსტრუქცია თავიდან ბოლომდე, 643 ხაზად, წავიკითხე remote ref-იდან, რადგან აუდიტირებულ HEAD-ში `docs/READINESS_AUDIT_PROMPT.md` არ არსებობს. შემოწმება შესრულდა §8-ის რიგით: §3.A, §3.H, §3.C+§3.D, §3.B, §3.E+§3.K, §3.F+§3.G, §3.I+§3.J, §3.L.
- სტატიკური ინვენტარი მოიცავდა 302 main Java ფაილს, 96 Java test ფაილს, 146 TypeScript ფაილს, 25 Angular unit spec-ს, 18 Playwright E2E spec-ს, 29 Python test ფაილს და 42 Flyway migration-ს. ყველა 42 migration სრულად წავიკითხე; უსაფრთხოების, ავტორიზაციის, scope-ის, storage/export-ის, lifecycle-ის, search/performance-ის, frontend shell/modal/form-ისა და ოპერაციული დოკუმენტების შესაბამისი ფაილები წავიკითხე სრულად ან მიგნების გარშემო line-by-line.
- endpoint/RBAC inventory-მ მოიცვა 130 Spring mapping და 123 legacy FastAPI endpoint. მიმდინარე gate-ები შევადარე access-contract test-ს, controller/service enforcement-ს და product/IT დოკუმენტებს; მხოლოდ matrix-ის არსებობა მტკიცებულებად არ ჩამითვლია.
- dependency review: `npm audit`-მა დაადასტურა direct `quill@1.3.7` advisory; Maven dependency tree/advisory applicability ხელით შევადარე რეალურ call path-ებს. Git history-ზე ჩავატარე targeted secret search; სრული entropy scan არ ჩატარებულა.
- შესრულებული შემოწმებები: `ruff check .` — PASS; Maven `test -DexcludedGroups=oracle` — PASS, 358/358, 0 failure/error/skip. Python pytest ვერ გაეშვა დაზიანებული Python 3.11 venv-ის გამო; `npm ci` ორჯერ შეჩერდა ჩაკეტილ native binary-ზე (`EPERM`), რის შემდეგაც Angular CLI აღარ იყო ხელმისაწვდომი; Docker ამ გარემოში არ არის.
- evidence standard: დადასტურებულ მიგნებას აქვს მიმდინარე კოდის `ფაილი:ხაზი`, 1–10 ხაზის ციტატა, მოქმედი call path, კონკრეტული failure scenario და გამეორებადი verification. დოკუმენტი გამოყენებულია მოთხოვნის/ოპერაციული მდგომარეობის დასადასტურებლად, მაგრამ არა კოდის ქცევის ერთადერთ მტკიცებულებად.
- აუდიტისას source code, configuration და documentation არ შეცვლილა; ერთადერთი შექმნილი ფაილია ეს ანგარიში. commit და push არ შესრულებულა.

## 1. ბლოკერები

| ID | ბლოკერი | უშუალო გავლენა | მტკიცებულება | გასაშვებად აუცილებელი პირობა |
|---|---|---|---|---|
| RTA-001 | ახალი stack-ის production deployment/rollback არ არსებობს; root artifact ძველ Python/PostgreSQL stack-ს უშვებს | ოპერატორმა შეიძლება production-ში არასწორი აპი და ბაზა გაუშვას; rollback და data cutover გაურკვეველია | `Dockerfile:2,16,29`; `docker-compose.yml:61-64,107-110`; `docs/PRODUCTION_HANDOVER.md:3-13` | versioned target manifests, immutable images, secrets/ingress/health wiring, PostgreSQL→Oracle cutover rehearsal და tested rollback |
| RTA-002 | `/uploads/{filename}` ავტორიზაციის გარეშეა | URL-ის მცოდნე ყოფილი თანამშრომელი ან გარე პირი კითხულობს შიდა დოკუმენტს/PII-ს | `UploadedFileController.java:25-41,52-66`; `SecurityConfig.java:45` | attachment access contract, authenticated/authorized delivery და negative tests |
| RTA-003 | Stored XSS ჯაჭვი Quill 1.3.7-ით და JWT `localStorage`-ში | content admin-ის ჩანაწერი system admin-ის browser-ში კოდს გაუშვებს და signed JWT-ს მოიპარავს | `package.json:29`; `ArticleController.java:1279-1285`; `rich-text-editor.ts:136-143`; `auth.service.ts:65-70` | patched editor, server-side sanitization, CSP, token isolation და exploit regression test |
| RTA-004 | leadership-based scope მხოლოდ shadow-ად ითვლება და legacy free-text პასუხი ბრუნდება | გაუქმებული/არასწორი manager mapping-ით სხვისი პერსონალური მონაცემები კვლავ ხელმისაწვდომია | `ScopeResolver.java:42-45,163-186`; `ExportQueryService.java:139-147`; `application.yml:87-93` | clean reconciliation, V37 constraints, gated enforcement, deny-by-default tests და production cutover evidence |
| RTA-005 | Oracle backup/restore არ არსებობს და restore არასოდეს დამოწმებულა | ბაზის/ტაბლსფეისის დაზიანებისას კონტენტი, BLOB დანართები და audit evidence შეიძლება აღუდგენლად დაიკარგოს | `backup.py:31-47`; `docs/QUESTIONS_FOR_IT.md:166-206` | Oracle-consistent backup including BLOB tablespace, encrypted retention, RPO/RTO, restore runbook და witnessed restore test |

## 2. Production exit criteria — 14/14

| # | კრიტერიუმი | სტატუსი | მტკიცებულება / მიზეზი |
|---:|---|---|---|
| 1 | ბლოკერი არ არის | **FAIL** | RTA-001–RTA-005 — 5 ბლოკერი |
| 2 | Production-ში passwordless login ნებისმიერი config-ით შეუძლებელია | **PASS (code-level)** | `ProductionSafetyGuard.java:17-155`; `ProductionSafetyGuardTest.java:41-111`; default environment production-ია და bypass production-თან ერთად startup-ს აჩერებს |
| 3 | unauthorized data არ ჩანს; სრული RBAC matrix მუშაობს | **FAIL** | public attachments (RTA-002) და unenforced leadership scope (RTA-004); 130 mapping-ის current-gate coverage passing-ია, მაგრამ intended scope არა |
| 4 | HEAD-სა და history-ში secret არ არის | **FAIL** | HEAD-ში რეალური secret ვერ მოიძებნა, მაგრამ history-ში `365c6d3` და `a31a7a6` შეიცავს credential-like მნიშვნელობებს; rotation/unuse ვერ დადასტურდა (RTA-007) |
| 5 | clean DB migration გადის; existing DB behavior ცნობილი/დოკუმენტირებულია | **FAIL** | V37 განზრახ გამოტოვებულია, backfill/reconciliation/rehearsal არ ჩატარებულა; Oracle integration suite ვერ გაეშვა |
| 6 | uploads/exports restart-ს უძლებს და 2+ replica-ზე მუშაობს | **UNVERIFIED** | ახალი storage Oracle BLOB-ზეა (`StoredFile`, V31), ამიტომ ძველი pod-local დეფექტი code-level-ზე გამოსწორებულია; 2-replica runtime test და target deployment არ არსებობს |
| 7 | backup მუშაობს და restore ერთხელ მაინც გამოცდილია | **FAIL** | მხოლოდ legacy SQLite/PostgreSQL backup არსებობს; Oracle restore procedure/test არ არის (RTA-005) |
| 8 | ყველა CI green-ია და production stack-ს ამოწმებს | **FAIL** | Java DB-free suite 358/358 და ruff PASS; Python/Angular/Oracle/E2E/Docker ამ audit run-ში ვერ გაეშვა; production deployment job არ არსებობს |
| 9 | ყოველი წარუმატებელი action მომხმარებელს ეუბნება | **UNVERIFIED** | ძველი FE-01–FE-07 გზები გამოსწორებულია, მაგრამ სრული UI failure-path matrix და Angular tests მიმდინარე გარემოში ვერ დადასტურდა |
| 10 | ყველა screen bilingual, dark/light და mobile-ია | **FAIL** | modal/form/tab accessibility defects; Playwright მხოლოდ desktop Chromium-ს იყენებს; სრული bilingual/dark/mobile matrix არ არის (RTA-014) |
| 11 | search/main screens <2s realistic volume-ზე | **FAIL** | ისტორიული k6 p95 828 ms მხოლოდ შერჩეულ read paths-ზეა; search არ ტესტდება, 2-char query full scan-ს იწვევს და performance gate არ არსებობს (RTA-015/RTA-017) |
| 12 | audit log სრულია და application-ის მიერ უცვლელია | **FAIL** | content CRUD audit events აკლია; DB chain UPDATE/DELETE-ს არ ბლოკავს და keyed integrity არ აქვს (RTA-006/RTA-018) |
| 13 | სხვა ადამიანს შეუძლია deploy და rollback | **FAIL** | target handover თავად მონიშნულია “DO NOT USE”; target manifests/runbook/rollback rehearsal არ არსებობს (RTA-001) |
| 14 | launch-blocking IT კითხვები პასუხგაცემულია | **FAIL** | `docs/QUESTIONS_FOR_IT.md`-ში 11/11 სტატუსია `ღიაა`, პასუხი — `ჯერ არ არის` |

## 3. ყველა მიგნება severity-ის მიხედვით

### RTA-001 — Production-ში გასაშვები target stack განსაზღვრული არ არის

- **Severity:** `BLOCKER`
- **Status:** `CONFIRMED`
- **Layer:** Deployment / Operations / Data migration
- **Where:** `Dockerfile:2,16,29`; `docker-compose.yml:61-64,107-110`; `docs/PRODUCTION_HANDOVER.md:3-13`
- **Code:**

```dockerfile
FROM python:3.11-slim-bookworm AS builder
FROM python:3.11-slim-bookworm AS runtime
COPY --chown=appuser:appgroup . .
```

```yaml
app:
  build:
    dockerfile: Dockerfile
db:
  image: postgres:15-alpine
```

- **Current behavior:** The repository-root deployment builds and runs the legacy Python/PostgreSQL product. The only Angular/Spring/Oracle compose file is explicitly local-development configuration (`APP_ENV=development`, dev login enabled, loopback bind). No Kubernetes/Helm/production compose manifests, target handover, data cutover tool, or rollback procedure exist.
- **Expected behavior and why:** A single versioned, reproducible production topology must deploy the audited Angular/Spring/Flyway/Oracle artifacts with explicit secret, ingress, readiness, migration, observability, backup and rollback contracts. Otherwise the reviewed application is not the application operators can safely launch.
- **Failure scenario:** An operator follows the root `docker-compose.yml` or legacy handover and launches the Python service against PostgreSQL while stakeholders believe the new Oracle-backed portal shipped; data then diverges and there is no rehearsed rollback/cutover path.
- **Verification:** `git ls-files` found no Deployment/Service/Ingress/Helm manifest; root Dockerfile/compose resolve to Python/PostgreSQL; `docs/PRODUCTION_HANDOVER.md:3-13` explicitly says it is not deployable for the target.
- **Required fix:** Produce immutable target images and production manifests; answer IT topology questions; define PostgreSQL→Oracle migration/reconciliation; run staging deploy, smoke, rollback and cutover rehearsal by a second operator; publish the tested handover.

> **განკარგვა (2026-08-31) — ნაწილობრივ დაიხურა.**
>
> `Required fix`-ის სიიდან შესრულდა:
>
> - **immutable target images** — `java-backend/Dockerfile` და
>   `angular-frontend/Dockerfile` აწყობილია, ორივე non-root-ით
>   (uid 1000 / uid 101, **ცოცხალ კონტეინერზე გადამოწმებული**),
>   health check-ებით და K8s-ისთვის მორგებული JVM პარამეტრებით. image
>   ნაგულისხმევად `APP_ENV=production`-ია და `ProductionSafetyGuard`
>   უარს ამბობს ჩართვაზე სუსტი secret-ით ან dev-login-ით.
> - **production manifests** — `k8s/`: Deployment, Service, Ingress,
>   ConfigMap, PodDisruptionBudget, kustomization. `kubectl kustomize`
>   სუფთად აეწყობა, 8 რესურსი. PersistentVolume არ სჭირდება — PR-03-ის
>   შემდეგ backend ფაილურ სისტემაზე არაფერს წერს.
>
> **ღიად რჩება:**
>
> - **IT topology questions** — მანიფესტებში 12 ადგილია მონიშნული
>   `<<< IT-NN >>>`-ით. ეს განზრახაა: გამოცნობით შევსება უარესია, ვიდრე
>   ცარიელი, რადგან ერთი არასწორი მნიშვნელობა (მაგ. `TRUSTED_PROXIES`)
>   ჩუმად ტოვებს ხვრელს. იხ. `k8s/README_KA.md`.
> - **staging deploy / smoke / rollback rehearsal** — კლასტერზე წვდომის
>   გარეშე შეუძლებელია.
> - **PostgreSQL→Oracle migration** — *აღარ ვრცელდება.* Python-სტეკი
>   პროდაქშენში არასოდეს განთავსებულა (არსად არსებობს `.env`, არც
>   go-live git-ისტორიაში), ე.ი. გადასატანი რეალური მონაცემი არ არსებობს.
>   რეალური მომხმარებლები AD-ის JIT-provisioning-ით შეიქმნებიან პირველი
>   შესვლისას.
> - **root `docker-compose.yml`** ისევ Python-სტეკს უშვებს. CLAUDE.md-ის
>   მიხედვით legacy 30-დღიანი read-only ფანჯრისთვისაა შენახული, მაგრამ
>   ამ მიგნების „operator follows the root compose" სცენარი ამით არ
>   იხსნება — ცალკე გადასაწყვეტია.

### RTA-002 — დანართები ავთენტიკაციის გარეშე იკითხება

- **Severity:** `BLOCKER`
- **Status:** `CONFIRMED`
- **Layer:** Authorization / Data privacy
- **Where:** `java-backend/src/main/java/ge/magti/portal/web/UploadedFileController.java:25-41,52-66`; `java-backend/src/main/java/ge/magti/portal/config/SecurityConfig.java:45`
- **Code:**

```java
@GetMapping("/uploads/{filename}")
public ResponseEntity<?> serve(@PathVariable("filename") String filename) {
    Optional<FileStorageService.StoredContent> found = fileStorageService.load(filename);
    return ResponseEntity.ok().body(content.content());
}
```

- **Current behavior:** Any caller that knows or retains an upload URL receives the BLOB without an authenticated principal or audience/scope check. `anyRequest().permitAll()` leaves enforcement to controllers, and this controller intentionally performs none.
- **Expected behavior and why:** Attachments must inherit the containing content's authentication, role and audience rules, or use a short-lived authorized delivery mechanism. UUID opacity is not authorization.
- **Failure scenario:** An ex-employee keeps a bookmarked URL or an article link leaks via mail/browser history; the URL still downloads an internal procedure, identity document or other PII without login.
- **Verification:** Anonymous GET is a direct controller path with no principal parameter and no guard; the controller comment at lines 25–41 explicitly confirms public access. IT question 9 remains unanswered.
- **Required fix:** Decide the protected-attachment contract; enforce authentication and content audience authorization; prevent cache leakage; migrate inline-image rendering to token-aware loading; add anonymous, wrong-role, wrong-department and revoked-user negative tests.

> **განკარგვა (2026-08-29) — მიგნება ძალაშია, ტექსტი მოძველდა.**
>
> ზემოთ მოყვანილი კოდი დღეს აღარ არსებობს. ორ ნაბიჯად გასწორდა:
>
> 1. **ავთენტიფიკაცია** — `UploadedFileController.serve` იღებს
>    `@AuthenticationPrincipal`-ს და principal-ის გარეშე 401-ს აბრუნებს;
>    პასუხი `no-store`-ია და წვდომა აუდიტში იწერება.
> 2. **აუდიტორია** — UAT-ის ადვერსარიულმა რაუნდმა აჩვენა, რომ ეს საკმარისი
>    არაა: ავთენტიფიცირებულმა ოფისის ოპერატორმა ჩამოტვირთა **ტექნიკურის**
>    სტატიის სურათი (`docs/uat/UAT_06_ADVERSARIAL_KA.md`, F-1). აქედან
>    DEC-P01: *ფაილი იკითხება მაშინ, როცა იკითხება ის, რაც მასზე მიუთითებს.*
>    `FileAccessPolicy` + `FileReferenceIndex` (`V46`), ხილვადობა
>    `ArticleVisibility`-ს ებარება — იმავე პრედიკატს, რომელსაც სტატიის
>    endpoint იყენებს.
>
> `Required fix`-ის სიიდან **ღიად რჩება ერთი პუნქტი:** enforcement
> პროდაქშენზე ჯერ არ არის ჩართული. იქ იგივე წესი shadow-ით გადის
> (`ROLLOUT_FILE_ENTITLEMENT=false`) — გადაწყვეტილება ითვლება და აუდიტში
> `FILE_ACCESS_SHADOW_DENY`-ად იწერება, ფაილი კი მაინც გაიცემა. ე.ი.
> **RTA-002 პროდაქშენზე დღემდე ღიაა**, ოღონდ ახლა გაზომვადია.
> ჩართვის კრიტერიუმი: `docs/ROLLOUT_ROLLBACK_KA.md` → „DEC-P01".
>
> `Required fix`-ის დანარჩენი პუნქტები: cache leakage — `no-store` +
> `Content-Security-Policy: default-src 'none'; sandbox`; inline-image
> rendering — credential httpOnly cookie-ია (`auth.service.ts:15-20`),
> ამიტომ `<img src="/uploads/...">` თავისით ავთენტიფიცირდება და ცალკე
> token-aware loader არ სჭირდება.
>
> **negative tests — ნაწილობრივ:** anonymous და wrong-department ორივე
> რეჟიმზე დაფარულია (`FileEntitlement{Shadow,Enforced}IntegrationTest`,
> `UploadControllerIntegrationTest`). wrong-role და revoked-user **ამ
> endpoint-ზე ცალკე ტესტი არ აქვს** — მექანიზმი საერთოა
> (`JwtAuthenticationFilter` ავტორიზაციას ყოველ მოთხოვნაზე ბაზიდან
> კითხულობს, `token_version`-ის ჩათვლით) და UAT-ში ხელით დადასტურდა
> (`UAT_06`, რაუნდი 4), მაგრამ ავტომატური regression აქ ჯერ არ არის.

### RTA-003 — Stored XSS-ით ადმინისტრატორის signed JWT იპარება

- **Severity:** `BLOCKER`
- **Status:** `CONFIRMED`
- **Layer:** Frontend / Content security / Session security
- **Where:** `angular-frontend/package.json:29`; `java-backend/src/main/java/ge/magti/portal/web/ArticleController.java:1279-1285`; `angular-frontend/src/app/shared/rich-text-editor/rich-text-editor.ts:136-143`; `angular-frontend/src/app/core/auth/auth.service.ts:65-70`
- **Code:**

```java
article.setContent(request.content());
```

```typescript
quill.clipboard.dangerouslyPasteHTML(0, html, 'silent');
return localStorage.getItem(TOKEN_KEY);
localStorage.setItem(TOKEN_KEY, token);
```

- **Current behavior:** The backend stores editor HTML verbatim. Quill 1.3.7 reloads stored HTML through `dangerouslyPasteHTML`, and the signed bearer token is JavaScript-readable in `localStorage`. `npm audit` identifies the direct Quill dependency as affected by CVE-2021-3163 / GHSA-4943-9vgg-gr5r.
- **Expected behavior and why:** Untrusted rich text must be sanitized server-side against a strict allowlist and rendered/edited with a patched component. A content compromise must not expose a reusable system-admin token.
- **Failure scenario:** A content admin submits crafted image/event-handler HTML. A system admin opens the article editor; script executes in the portal origin, reads the bearer token, and the attacker replays it for system-admin actions.
- **Verification:** Direct data flow is request content → `Article.content` → Quill `dangerouslyPasteHTML`; token read/write is `localStorage`. Advisory: [GitHub Security Advisory GHSA-4943-9vgg-gr5r](https://github.com/advisories/GHSA-4943-9vgg-gr5r).
- **Required fix:** Upgrade to a non-vulnerable Quill release, sanitize on the server and again at the appropriate output boundary, deploy a restrictive CSP, move session authority out of JavaScript-readable storage where feasible, and add a stored-XSS regression test covering edit and view paths.

### RTA-004 — Leadership scope არ სრულდება და legacy free-text scope პასუხობს

- **Severity:** `BLOCKER`
- **Status:** `CONFIRMED`
- **Layer:** RBAC / Organizational scope / Privacy
- **Where:** `java-backend/src/main/java/ge/magti/portal/security/ScopeResolver.java:42-45,163-186`; `java-backend/src/main/java/ge/magti/portal/export/ExportQueryService.java:139-147`; `java-backend/src/main/resources/application.yml:87-93`
- **Code:**

```java
List<User> visible = ManagerScope.visibleActiveUsers(active, caller);
visible = scopeResolver.shadowCompare("scope.export", caller, active, visible);
...
return legacyVisible;
```

- **Current behavior:** Leadership assignments are measured only in shadow mode. Export and related manager paths still use `users.department`, an administrator-entered free-text value, and `shadowCompare` returns that legacy result regardless of rollout flags.
- **Expected behavior and why:** Non-system-admin visibility must be derived from active leadership assignments and fail closed when no assignment exists. Revoking an assignment must revoke access even if a stale department string remains.
- **Failure scenario:** A manager's leadership assignment is revoked or mapped to a different team but their legacy department text is unchanged; they continue exporting named employees and compliance results outside their current authority.
- **Verification:** The resolver's own executable path returns `legacyVisible`; `application.yml:88-93` states the flags do not enforce the proposed scope. The DB expansion says V37 must tighten the backfilled structure, but V37 is absent.
- **Required fix:** Complete and sign off reconciliation; ship V37 constraints; bind rollout flags to every scoped read/export/audit path; default to zero scope; run full role × assignment × status × endpoint negative tests; rehearse rollback without reopening unauthorized access.

### RTA-005 — Oracle backup/restore და აღდგენის მტკიცებულება არ არსებობს

- **Severity:** `BLOCKER`
- **Status:** `CONFIRMED`
- **Layer:** Disaster recovery / Database / Operations
- **Where:** `backup.py:31-47`; `docs/QUESTIONS_FOR_IT.md:166-206`
- **Code:**

```python
if settings.is_sqlite:
    shutil.copy2(db_file, temp_dir)
else:
    db_url = settings.DATABASE_URL.replace("+psycopg2", "")
    subprocess.run(["pg_dump", db_url, "-F", "c", "-f", dump_path], check=True)
```

- **Current behavior:** The repository backup script handles SQLite or PostgreSQL and a legacy filesystem uploads directory. The target stores application data and uploaded BLOBs in Oracle; there is no Data Pump/RMAN/tablespace contract, restore command, RPO/RTO, retention policy or restore result.
- **Expected behavior and why:** A production launch requires an Oracle-consistent backup covering schema, BLOB data and audit evidence, plus a tested restore with measured RPO/RTO. A backup that has never restored is not recovery evidence.
- **Failure scenario:** Oracle storage corruption or an operator error removes article BLOBs/audit records. The documented script cannot export or restore the target database, so the team discovers during the incident that no usable recovery path exists.
- **Verification:** `backup.py` contains no Oracle branch and no restore implementation. `docs/QUESTIONS_FOR_IT.md` question 6 remains open and the audit environment had no Oracle/Docker instance on which to perform a restore.
- **Required fix:** IT/DBA must define and automate the Oracle backup strategy, encryption/access, retention and monitoring; perform a clean isolated restore; validate counts/hashes/files/audit chain; record owners, RPO/RTO, commands and rollback/escalation steps.

### RTA-006 — კონტენტის ცვლილებების audit trail არასრულია

- **Severity:** `HIGH`
- **Status:** `CONFIRMED`
- **Layer:** Auditability / Backend
- **Where:** `java-backend/src/main/java/ge/magti/portal/web/ArticleController.java:98-103`; `java-backend/src/main/java/ge/magti/portal/web/NewsController.java:56-61`; `java-backend/src/main/java/ge/magti/portal/web/VideoController.java:43-50`; `java-backend/src/main/java/ge/magti/portal/web/CategoryController.java:40-43`
- **Code:**

```java
// CategoryController create path
Category category = new Category();
applyRequest(category, request);
Category saved = categoryRepository.save(category);
return ResponseEntity.ok(CategoryResponse.from(saved));
```

- **Current behavior:** Java create/update/delete/autosave paths for articles and create/update/delete paths for news, videos and categories do not consistently create audit rows. Some archive/unarchive paths do, so the log is selectively complete rather than transactionally complete.
- **Expected behavior and why:** Every privileged content mutation must write a durable audit event with actor, target, action, timestamp, request context and before/after or revision reference in the same transaction. Operations and investigations cannot infer missing events from current state.
- **Failure scenario:** An administrator changes a mandatory article or deletes a category; a later dispute cannot establish who changed what because the business write committed without an audit event.
- **Verification:** Controller mutation paths were read and searched for `AuditLogRepository.save`; the missing paths are also explicitly documented in source comments at the cited lines. Existing archive/unarchive writes demonstrate the intended mechanism but do not cover CRUD.
- **Required fix:** Centralize transactional audit emission for all privileged mutations, include stable snapshots/revision identifiers, fail the business transaction when required audit persistence fails, and add integration tests per action.

### RTA-007 — Git history-ში credential-like secrets რჩება

- **Severity:** `HIGH`
- **Status:** `CONFIRMED`
- **Layer:** Secret management / Source control
- **Where:** commit `365c6d35982847a5111da8439681670e89575561`, `docker-compose.yml:16,31,52,54,58,89`; commit `a31a7a69fa85c1b064595d006b49bd6d96a16ecc`, `java-backend/src/main/resources/application.yml:15`
- **Code:**

```text
DATABASE_URL: [REDACTED]
POSTGRES_PASSWORD: [REDACTED]
SECRET_KEY: [REDACTED]
password: [REDACTED]
```

- **Current behavior:** Current HEAD uses environment-driven values and its startup guard rejects unsafe production defaults, but targeted history inspection found committed database passwords/URLs and an application signing secret. This report intentionally does not reproduce their values.
- **Expected behavior and why:** Production-like credentials must never remain usable after appearing in source history; rotation and revocation evidence is required even when HEAD is clean.
- **Failure scenario:** A developer, CI artifact consumer or leaked clone reads an old commit and uses a still-valid Oracle/PostgreSQL credential or signing key to access data or forge sessions.
- **Verification:** `git show` at the cited immutable commits and lines produced the masked keys above. Whether the values were ever production-connected, and whether each was rotated, could not be verified from the repository.
- **Required fix:** Identify owners/systems, rotate and revoke every exposed value, review access logs, record incident closure, purge history only if organizational policy requires it, and enable repository secret scanning/pre-receive prevention.

### RTA-008 — Trash/restore ოპერაცია legal hold-ს აუქმებს

- **Severity:** `HIGH`
- **Status:** `CONFIRMED`
- **Layer:** Records lifecycle / Compliance / Database
- **Where:** `java-backend/src/main/java/ge/magti/portal/content/ContentLifecycleService.java:134-140,161-164,179-190`
- **Code:**

```java
+ " SET trashed_at = ?, purge_after = ?, trashed_by = ?, legal_hold = 0"
...
+ " SET trashed_at = NULL, purge_after = NULL, trashed_by = NULL, legal_hold = 0"
...
if (payload.legalHold()) {
    return Status.LEGAL_HOLD;
}
```

- **Current behavior:** Purge correctly refuses rows whose `legal_hold` is true, but both trash and restore unconditionally reset the flag to false. No application service was found that safely establishes or preserves a hold.
- **Expected behavior and why:** A legal hold is controlled by authorized compliance personnel and must survive normal content lifecycle operations until explicitly released with its own audit evidence.
- **Failure scenario:** A DBA/DPO places a hold, a content administrator trashes or restores the item, the flag is silently cleared, and a later system-admin purge permanently deletes evidence subject to the hold.
- **Verification:** Direct SQL assignments on both state transitions clear the same column that the purge guard later trusts.
- **Required fix:** Never modify `legal_hold` in trash/restore; provide separately authorized hold/release operations with mandatory audit; reject destructive transitions as policy requires; add hold → trash/restore → purge regression tests.

### RTA-009 — Database outage-ზე healthcheck მაინც healthy რჩება

- **Severity:** `HIGH`
- **Status:** `CONFIRMED`
- **Layer:** Availability / Orchestration
- **Where:** `java-backend/src/main/java/ge/magti/portal/web/HealthController.java:43-60`; `java-backend/Dockerfile:56-57`
- **Code:**

```java
public Map<String, String> health() {
    ...
    body.put("status", "degraded");
    return body;
}
```

```dockerfile
CMD wget -q -O- http://localhost:8080/api/health || exit 1
```

- **Current behavior:** A failed Oracle probe changes only JSON text and the controller still returns HTTP 200. The image healthcheck checks wget exit status, so an instance unable to serve data remains healthy and eligible for traffic.
- **Expected behavior and why:** Liveness and readiness must be separate; dependency failure must produce non-2xx readiness while liveness remains suitable for restart policy. Load balancers need a machine-readable failure signal.
- **Failure scenario:** Oracle becomes unavailable; every pod continues receiving user requests and returning failures while the orchestrator reports all replicas healthy.
- **Verification:** The controller return type cannot set an error status, and the catch branch returns normally. The Docker healthcheck treats any 2xx body, including `degraded`, as success.
- **Required fix:** Add distinct liveness/readiness endpoints, return 503 on failed readiness, configure orchestrator probes against them, and test DB loss/recovery and startup migration states.

### RTA-010 — Global search cache-ს შეუზღუდავი heap ზრდა აქვს

- **Severity:** `HIGH`
- **Status:** `CONFIRMED`
- **Layer:** Performance / Availability
- **Where:** `java-backend/src/main/java/ge/magti/portal/search/GlobalSearchCache.java:25-55`
- **Code:**

```java
private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
Entry cached = cache.get(key);
if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
    return cached.value();
}
...
cache.put(key, new Entry(result, Instant.now().plus(TTL)));
```

- **Current behavior:** TTL is checked on lookup, but expired keys are never removed and the cache has no size/weight bound. Keys include user-controllable query/scope dimensions and values retain result object graphs.
- **Expected behavior and why:** An in-process cache exposed to high-cardinality input must evict expired entries and enforce a bounded size/weight; otherwise authenticated usage can exhaust the JVM.
- **Failure scenario:** A user or script sends many unique searches. Each creates a permanent map key; heap grows across days until GC pressure or OOM removes the portal instance.
- **Verification:** The class has only `get`, `put` and `inflight.remove`; no `cache.remove`, scheduled cleanup, maximum size or bounded cache implementation exists.
- **Required fix:** Use a bounded cache with expiry-after-write/access and metrics, limit query cardinality/rate, avoid retaining full entities/CLOBs, and load-test unique-query churn under the configured heap.

### RTA-011 — Article list-ის `limit` შეუზღუდავია და სრულ CLOB-ებს ტვირთავს

- **Severity:** `HIGH`
- **Status:** `CONFIRMED`
- **Layer:** API robustness / Database / Performance
- **Where:** `java-backend/src/main/java/ge/magti/portal/web/ArticleController.java:171-185`; `java-backend/src/main/java/ge/magti/portal/article/ArticleQueryService.java:36-39,89-95`; `java-backend/src/main/java/ge/magti/portal/web/ArticleSummaryResponse.java:34-40`
- **Code:**

```java
@RequestParam(defaultValue = "20") int limit,
...
// No clamping here
query.setMaxResults(limit);
return query.getResultList();
```

- **Current behavior:** Any authenticated user controls an unbounded page size. The query fetches full `Article` entities including content CLOBs, then summaries compute read time from each full content value.
- **Expected behavior and why:** List endpoints need validated pagination with a hard maximum and a projection that excludes large content. Authenticated access is not protection from accidental or intentional resource exhaustion.
- **Failure scenario:** Repeated `GET /api/articles?limit=1000000` calls force Oracle/JPA to materialize all article CLOBs, causing latency, heap pressure and possible service unavailability.
- **Verification:** Controller input has no validation; service comments explicitly state no clamp and full-CLOB fetch; k6 does not exercise this boundary.
- **Required fix:** Reject negative/oversized values, cap limit (for example 100), use a summary projection with a stored/computed read-time field, add query-count/memory regression tests and rate/timeout controls.

### RTA-012 — Login throttling proxy/config/replica topology-ზე სუსტდება

- **Severity:** `HIGH`
- **Status:** `CONFIRMED`
- **Layer:** Authentication / Distributed systems
- **Where:** `java-backend/src/main/java/ge/magti/portal/security/LoginRateLimiter.java:45-58,67-71`; `java-backend/src/main/java/ge/magti/portal/security/ClientIpResolver.java:76-81`; `java-backend/src/main/resources/application.yml:106-108`
- **Code:**

```java
// counters are in-memory per JVM
static final int MAX_ATTEMPTS_PER_ACCOUNT = 10;
static final int MAX_ATTEMPTS_PER_ADDRESS = 60;
private final Map<String, Deque<Instant>> attemptsByKey = new ConcurrentHashMap<>();
```

- **Current behavior:** The improved limiter uses account+IP and IP buckets, but they are JVM-local, so N replicas allow roughly N times the attempts. With no `TRUSTED_PROXIES` value, forwarded addresses are intentionally ignored and all users behind the ingress share its address; no shipped production manifest supplies that value.
- **Expected behavior and why:** The effective production limit and client-address attribution must remain correct behind the actual trusted proxy chain and across the planned replica count.
- **Failure scenario:** With three replicas an attacker triples brute-force capacity; with a missing proxy CIDR, unrelated staff share one IP bucket and can experience a company-wide login throttle.
- **Verification:** Source comments and fields explicitly state per-JVM behavior; default `trusted-proxies` is empty; IT questions 7 and 8 are unanswered.
- **Required fix:** Obtain exact proxy/CIDR and replica design, configure trusted hops, move counters to an atomic shared backend or enforce equivalent ingress limits, and test spoofed forwarding, NAT load and multi-replica distribution.

### RTA-013 — Production image-ში destructive legacy utilities ხვდება

- **Severity:** `HIGH`
- **Status:** `CONFIRMED`
- **Layer:** Operational safety / Legacy deployment
- **Where:** `Dockerfile:29`; `database.py:21`; `seed.py:118-126,402-412`; `simulate_audit_data.py:86-90`
- **Code:**

```dockerfile
COPY --chown=appuser:appgroup . .
```

```python
db.query(model).delete()
...
# `python seed.py` -> DESTRUCTIVE full demo reseed (wipes the DB first).
else:
    seed_database()
```

- **Current behavior:** The root production-style image copies the entire repository, including no-argument destructive seed/simulation utilities. They use the environment-selected database and contain no production-environment refusal guard.
- **Expected behavior and why:** Destructive demo tooling must not ship in runtime images or must fail closed against production with explicit scoped confirmation. A typo or copied runbook command must not wipe operational data.
- **Failure scenario:** An operator execs into the legacy app container and runs `python seed.py`, expecting idempotent bootstrap; the script deletes users, audit/search data, messages and compliance records from the configured database.
- **Verification:** The Dockerfile copies all repository files; `.dockerignore` does not exclude the utilities; `database.py` selects `settings.DATABASE_URL`; the default script branch calls the destructive reseed.
- **Required fix:** Remove legacy utilities from runtime artifacts, archive the legacy deployment, add hard production guards and explicit destructive confirmation for retained maintenance commands, and restrict database privileges.

### RTA-014 — Audit hash chain privileged DB rewrite-ს ვერ აჩერებს

- **Severity:** `HIGH`
- **Status:** `CONFIRMED`
- **Layer:** Audit integrity / Oracle security
- **Where:** `java-backend/src/main/resources/db/migration/V28__audit_hash_chain.sql:114-139`
- **Code:**

```sql
CREATE OR REPLACE TRIGGER trg_audit_logs_chain
    BEFORE INSERT ON audit_logs
...
SELECT STANDARD_HASH(v_canon, 'SHA256') INTO v_hash FROM dual;
:NEW.row_hash := LOWER(RAWTOHEX(v_hash));
```

- **Current behavior:** The chain trigger runs only on INSERT and uses an unkeyed reproducible SHA-256 hash. No UPDATE/DELETE denial trigger, append-only privilege boundary, HMAC/external anchor, or WORM export prevents a privileged DB actor from rewriting the table and recomputing the chain/state.
- **Expected behavior and why:** The exit criterion requires application-level immutability. Audit evidence must be append-only under the application account and tampering by a privileged actor must be externally detectable.
- **Failure scenario:** A compromised schema owner changes/deletes an audit row, recomputes downstream hashes and updates `audit_chain_state`; the application verification sees a valid-looking chain.
- **Verification:** Migration defines only `BEFORE INSERT`; repository-wide trigger search found no UPDATE/DELETE guard for `audit_logs`. `STANDARD_HASH` has no secret or external trust anchor.
- **Required fix:** Revoke update/delete from the application role, enforce append-only DB policy, sign/anchor chain tips outside the schema (or forward to protected SIEM/WORM storage), alert on verification gaps, and test attempted mutation with the production role model.

### RTA-015 — Modal, form და tab semantics keyboard/screen-reader გზებს არღვევს

- **Severity:** `MEDIUM`
- **Status:** `CONFIRMED`
- **Layer:** Frontend / Accessibility
- **Where:** `angular-frontend/src/app/features/article-detail/article-version-history-overlay/article-version-history-overlay.html:1-3,30`; `angular-frontend/src/app/features/reading/quiz-taker-modal/quiz-taker-modal.html:1-3`; `angular-frontend/src/app/features/admin-content/article-edit-drawer/article-edit-drawer.html:1-10`; `angular-frontend/src/app/features/admin-categories/admin-categories-page.html:31-49`; `angular-frontend/src/app/features/account/account-page.html:21-25`
- **Code:**

```html
<div class="fixed inset-0 z-50 flex items-center justify-center p-4">
  <div class="absolute inset-0 ..." (click)="close()"></div>
  <div class="relative z-10 ...">
...
<div (click)="selectVersion(item)" [class]="rowClass(item)">
```

- **Current behavior:** Visual modals/drawers lack dialog role, modal/name relationships, focus trapping/restoration and consistent Escape handling; a version row is a click-only `div`. Several labels have no `for`/input `id`, and tabs omit panel relationships/roving keyboard behavior.
- **Expected behavior and why:** Every interactive flow must expose semantic controls, focus lifecycle, keyboard operation and screen-reader relationships; production acceptance explicitly covers every screen.
- **Failure scenario:** A keyboard-only user cannot select a historical version or keep focus inside a modal; a screen-reader user cannot identify the active dialog, associated fields or tab panel.
- **Verification:** Direct template inspection found the missing attributes/handlers; repository search found no axe suite. Playwright config tests desktop Chromium only.
- **Required fix:** Use Angular CDK dialog/focus primitives or equivalent, replace click-only elements with buttons, associate all labels/inputs, implement WAI-ARIA tabs and add axe plus keyboard tests on desktop/mobile layouts.

### RTA-016 — ორსიმბოლოიანი search სამივე ცხრილის full scan-ს იწვევს

- **Severity:** `MEDIUM`
- **Status:** `CONFIRMED`
- **Layer:** Search / Database performance
- **Where:** `java-backend/src/main/java/ge/magti/portal/search/SearchQueryService.java:95-105,165-167,193-196,211-224`; `angular-frontend/src/app/core/services/search.service.ts:26`
- **Code:**

```java
List<String> indexableWords = words.stream()
        .filter(w -> w.length() >= TrigramIndexer.TRIGRAM_LENGTH)
        .toList();
if (indexableWords.isEmpty()) {
    return null;
}
```

- **Current behavior:** The API/UI accepts two-character queries, but trigram narrowing starts at three characters. A two-character term therefore makes article, news and video paths call `findAll()` and filter content in the JVM; article content includes CLOB data.
- **Expected behavior and why:** Supported queries should be index-narrowed, capped and measured at realistic production volume, or short queries should be rejected with a clear UX.
- **Failure scenario:** Common two-character searches issued concurrently scan every searchable row and CLOB, saturating Oracle and application heap while ordinary pages slow down.
- **Verification:** `candidateIds` returns `null` when no 3+ character word exists, and all three callers translate `null` to repository `findAll()`. The k6 scenario does not call search.
- **Required fix:** Align minimum query length with the index, add an appropriate Oracle-backed short-token strategy if product requires two characters, page/project results, and gate p95/p99 on production-like data including worst-case misses.

### RTA-017 — Category uniqueness race მხოლოდ application check-ით იკეტება

- **Severity:** `MEDIUM`
- **Status:** `CONFIRMED`
- **Layer:** Data integrity / Concurrency
- **Where:** `java-backend/src/main/resources/db/migration/V2__create_categories.sql:1-14`; `java-backend/src/main/java/ge/magti/portal/web/CategoryController.java:171-180`
- **Code:**

```sql
-- name/slug are indexed but NOT unique
CREATE INDEX ix_categories_name ON categories (name);
```

```java
Optional<Category> existing = categoryRepository
        .findFirstByNameIgnoreCaseAndActiveTrue(name.strip());
```

- **Current behavior:** Sequential duplicate creation is rejected, but the database has no uniqueness constraint. Two concurrent requests can both observe no row and both commit indistinguishable active categories; slug has the same database-level weakness.
- **Expected behavior and why:** Business uniqueness that affects routing/fallback selection must be enforced atomically in Oracle, with an application conflict response for constraint violations.
- **Failure scenario:** Two administrators create the same category simultaneously; both succeed and later fallback/query code chooses one by ID, splitting content unexpectedly.
- **Verification:** V2 explicitly creates non-unique indexes; the controller performs a read-before-write check outside a database uniqueness guarantee. Old BL-08 is therefore only partially fixed.
- **Required fix:** Define normalized/case-insensitive active-row uniqueness, clean existing duplicates, add Oracle unique index/constraint, translate violations to 409 and test concurrent creation.

### RTA-018 — Load test თითქმის ნებისმიერ შედეგს green-ად ატარებს

- **Severity:** `MEDIUM`
- **Status:** `CONFIRMED`
- **Layer:** Performance testing / Quality gate
- **Where:** `scripts/load/k6-java-backend.js:25-36`; `docs/PRODUCTION_HANDOVER.md:3-13`
- **Code:**

```javascript
stages: [
  { duration: '30s', target: 600 },
  { duration: '30s', target: 600 }
],
thresholds: {
  http_req_failed: ['rate<1.0']
}
```

- **Current behavior:** The script drives useful concurrency but allows every failure rate below 100%, has no latency threshold, and does not make checks a threshold. Historical p95 828 ms was recorded on a laptop/read subset, not target infrastructure or search.
- **Expected behavior and why:** Production readiness needs failing thresholds for error rate, p95/p99 latency and business checks on representative data/hardware, including search and heavy list/export boundaries.
- **Failure scenario:** A run with 99% failed requests and multi-second latency exits successfully, so CI/release reviewers interpret a broken build as a passed performance gate.
- **Verification:** The only threshold is the literal `rate<1.0`; no `http_req_duration` or check-rate threshold exists.
- **Required fix:** Set agreed SLO thresholds, add search/list/auth/export scenarios and realistic data, run on staging topology, publish trends and fail the release when thresholds regress.

### RTA-019 — ოპერაციული დოკუმენტები target stack-ს არ აღწერს და მცდარ ბრძანებებს იძლევა

- **Severity:** `MEDIUM`
- **Status:** `CONFIRMED`
- **Layer:** Documentation / Operations
- **Where:** `README.md:3-11,31`; `docs/PRODUCTION_HANDOVER.md:3-13`; `docs/admin-guide.md:121-161`; `docs/IMPLEMENTATION_PLAN_KA.md:180-212`; `CLAUDE.md:142-143`
- **Code:**

```markdown
> **DO NOT USE FOR THE NEW PRODUCTION DEPLOYMENT.**
> ... A new production handover must be produced ...
| **Status** | Legacy snapshot — not deployable as the target portal |
```

- **Current behavior:** The documentation map still routes operators to a handover that forbids target use. README/architecture/admin backup and cache instructions largely describe Python/PostgreSQL, the admin guide advertises unsupported backup `--list/--restore` behavior, implementation status is internally contradictory, and CLAUDE says V35 while HEAD contains V42.
- **Expected behavior and why:** A second operator must have one current source of truth for architecture, deploy, migration, secrets, backup/restore, monitoring, smoke tests, rollback and support ownership.
- **Failure scenario:** On-call staff follow the admin guide during an incident, run a legacy backup command that cannot restore Oracle, or deploy the wrong stack from the root instructions.
- **Verification:** Full document reads were cross-checked against Dockerfiles, compose files, migrations and `backup.py`; the quoted handover status is explicit, while the executable artifacts contradict remaining operational claims.
- **Required fix:** Retire or clearly archive legacy runbooks, produce target architecture/handover after IT decisions, make all commands executable in CI/staging, add document owner/review date and verify the runbook with a second person.

### RTA-020 — Statistics permission boundary სავარაუდოდ არასწორ capability-ს იყენებს

- **Severity:** `MEDIUM`
- **Status:** `SUSPECTED`
- **Layer:** Authorization model / Product policy
- **Where:** `java-backend/src/main/java/ge/magti/portal/web/StatsController.java:129-157,431-521,545-554`; `docs/QUESTIONS_FOR_IT.md:32-54`
- **Code:**

```java
private ResponseEntity<Map<String, String>> requireContentManage(User user) {
    if (!permissionChecker.hasPermission(user, Permission.CONTENT_MANAGE)) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(...);
    }
    return null;
}
```

- **Current behavior:** Six statistics endpoints use the content mutation capability as their read gate. The intended `stats.view`/analytics audience is not finalized, and the corporate authorization contract is still open.
- **Expected behavior and why:** Read-only analytics should use an explicit least-privilege capability if product/IT policy distinguishes content editing from sensitive reporting. This cannot be declared a confirmed defect until that policy is signed.
- **Failure scenario:** A user who should only read aggregate statistics must be granted `content.manage`, unintentionally enabling content mutation; or a legitimate analyst is denied because they should not edit content.
- **Verification:** Controller calls the cited guard; current permission catalog has no independent statistics capability. The expected target is unresolved, so status remains `SUSPECTED`, not confirmed.
- **Required fix:** Product/IT must decide the capability and data sensitivity; then update the RBAC contract, migration, UI catalog and full positive/negative endpoint tests atomically.

## 4. წინა აუდიტის 54 მიგნების სტატუსი

სტატუსები ზუსტად მიჰყვება მოთხოვნილ ოთხ მნიშვნელობას: `დაფიქსირებულია`, `ნაწილობრივ`, `არ არის დაფიქსირებული`, `აღარ ვრცელდება`. „დაფიქსირებულია“ ნიშნავს, რომ ძველი კონკრეტული failure mode მიმდინარე კოდსა და შესაბამის regression evidence-ში აღარ მეორდება; ეს არ ნიშნავს, რომ იმავე კომპონენტში ახალი რისკი არ არსებობს.

### Security — SEC-01…SEC-16

| ID | ძველი მიგნება | სტატუსი | მიმდინარე მტკიცებულება |
|---|---|---|---|
| SEC-01 | Password-less admin login is on unless someone remembers one env var | `დაფიქსირებულია` | production default + dual gate: `PortalProperties.java:28-34`, `AuthenticationService.java:76`; tests `ProductionSafetyGuardTest.java:41-111` |
| SEC-02 | Every export endpoint hands out the whole company's personal data | `დაფიქსირებულია` | legacy manager export now scopes rows: `ExportQueryService.java:132-147`; negative tests `ExportControllerIntegrationTest.java:349-416` (leadership cutover is separate RTA-004) |
| SEC-03 | The department dashboard shows every department's named staff to any manager | `დაფიქსირებულია` | query-side redaction/scoping: `StatsController.java:298-367`; regression `StatsControllerIntegrationTest.java:339-352` |
| SEC-04 | Login rate limiting collapses to one bucket for the entire company | `ნაწილობრივ` | account+address buckets exist: `LoginRateLimiter.java:70-104`; actual proxy CIDR and multi-replica backend absent: RTA-012 |
| SEC-05 | The audit trail records the proxy's IP address, not the user's | `ნაწილობრივ` | trusted-hop resolver exists: `ClientIpResolver.java:71-99`; `TRUSTED_PROXIES` defaults empty and IT answer absent: `application.yml:106-108` |
| SEC-06 | Three of nine permissions are decoration only | `დაფიქსირებულია` | unenforced catalog entries removed and coverage gates enforcement: `Permission.java:33-59`; `PermissionEnforcementCoverageTest.java:16-92` |
| SEC-07 | The production guard can be satisfied with a placeholder secret | `დაფიქსირებულია` | weak/placeholder secrets rejected: `ProductionSafetyGuard.java:17-155`; `ProductionSafetyGuardTest.java:15-111` |
| SEC-08 | Uploaded files are readable by anyone, with no login | `ნაწილობრივ` | ავთენტიფიკაცია დაფიქსირებულია: `UploadedFileController.java:61-64` (401 principal-ის გარეშე), პასუხი `no-store`, წვდომა აუდიტირდება. აუდიტორია: `FileAccessPolicy` + `ArticleVisibility`, ტესტები `FileEntitlementEnforcedIntegrationTest`/`FileEntitlementShadowIntegrationTest`. `ნაწილობრივ` — არა კოდის, არამედ **rollout-ის** გამო: აუდიტორიის enforcement პროდაქშენზე `ROLLOUT_FILE_ENTITLEMENT`-ს უკან shadow-შია. იხ. RTA-002-ის ქვემოთ დამატებული განკარგვა |
| SEC-09 | The upload allowlist checks a header the client controls | `დაფიქსირებულია` | magic-byte verification: `UploadController.java:40-51,120`; `FileTypeVerifierTest.java:10-92` |
| SEC-10 | The audit chain detects application-level tampering, not database-level tampering | `არ არის დაფიქსირებული` | insert-only, unkeyed DB chain: `V28__audit_hash_chain.sql:114-139`; RTA-014 |
| SEC-11 | Broadcast reaches nobody in a sub-group | `აღარ ვრცელდება` | targeted-message model was replaced by durable company-wide broadcast: `V38__create_broadcasts.sql:1-4`; no subgroup recipient expansion path remains |
| SEC-12 | The last system administrator can be demoted, including by themselves | `დაფიქსირებულია` | self/last-admin guards: `UserController.java:369,719-755`; tests `UserControllerIntegrationTest.java:780-837` |
| SEC-13 | A parent-department manager sees an empty team | `დაფიქსირებულია` | parent/subgroup legacy matcher centralized: `ManagerScope.java:10-143`; `ManagerScopeTest.java:14-104` (future leadership cutover separately blocked) |
| SEC-14 | Logout cannot invalidate a token that is already out there | `დაფიქსირებულია` | token version claim and DB reload: `JwtAuthenticationFilter.java:34-79`; E2E backend test `AuthControllerIntegrationTest.java:79-126` |
| SEC-15 | Unhandled enum parsing returns 500 | `აღარ ვრცელდება` | implicated enum-conversion path was removed/reworked; global handler documents why converter handler is not the current path: `GlobalExceptionHandler.java:45-64` |
| SEC-16 | `application.yml` tells the deployer a safety net does not exist | `დაფიქსირებულია` | explicit production boot guard and startup security state: `ProductionSafetyGuard.java:141-160`; `java-backend/Dockerfile:35-40` |

### Backend logic — BL-01…BL-14

| ID | ძველი მიგნება | სტატუსი | მიმდინარე მტკიცებულება |
|---|---|---|---|
| BL-01 | Deleting an edited news item returns 500 | `დაფიქსირებულია` | edit/history/delete integration path: `NewsControllerIntegrationTest.java:131-166` |
| BL-02 | Deleting an article leaves an un-removable mandatory reading behind | `დაფიქსირებულია` | dependent reading/status deletion order: `RequiredReadingRepository.java:17-34`; `ArticleControllerIntegrationTest.java:683-719` |
| BL-03 | Autosave changes published articles without a version bump | `დაფიქსირებულია` | published autosave refusal/version contract: `ArticleController.java:330-365,479`; tests `ArticleControllerIntegrationTest.java:517-626` |
| BL-04 | Re-pointing a required reading transfers everyone's read status | `დაფიქსირებულია` | receipt key remains reading-specific and reassignment logic resets correctly: `ComplianceController.java:288-321`; tests `ComplianceControllerIntegrationTest.java:496-552` |
| BL-05 | The notifier messages the exact people compliance excludes | `დაფიქსირებულია` | eligibility shared by notifier/query: `ComplianceEligibilityService.java`; test `ComplianceControllerIntegrationTest.java:375-457` |
| BL-06 | A missed deadline has no consequence anywhere | `დაფიქსირებულია` | overdue state feeds compliance/reminder behavior: `ComplianceControllerIntegrationTest.java:540-552`; `ReminderService.java` |
| BL-07 | Deleting the fallback category quietly breaks later deletions | `დაფიქსირებულია` | active fallback validation: `CategoryRepository.java:25-34`; test `CategoryControllerIntegrationTest.java:304-348` |
| BL-08 | Two categories can share one name, and the fallback picks by id | `ნაწილობრივ` | sequential duplicate guard exists: `CategoryController.java:171-180`; DB uniqueness/race remains: `V2__create_categories.sql:1-14`, RTA-017 |
| BL-09 | Export downloads are one-shot and tied to one pod | `დაფიქსირებულია` | BLOB-backed repeatable download: `ExportControllerIntegrationTest.java:230-252`; `ExportJobWorker.java:22-54` |
| BL-10 | Deletions orphan tag mappings and favourites | `დაფიქსირებულია` | cleanup regression coverage: `ArticleControllerIntegrationTest.java:721-769`; `VideoControllerIntegrationTest.java:256-299` |
| BL-11 | Concurrent edit and restore of the same article can collide | `დაფიქსირებულია` | separate optimistic lock: `Article.java:104-119`; `V33__article_optimistic_lock.sql:7-21`; test `ArticleControllerIntegrationTest.java:1628-1688` |
| BL-12 | Read receipts and view logs survive deletion, unlinkable | `დაფიქსირებულია` | immutable snapshot IDs survive FK nulling: `ArticleReadReceipt.java:47-61`; test `ArticleControllerIntegrationTest.java:1466-1532` |
| BL-13 | Flyway will not start against a pre-existing schema | `არ არის დაფიქსირებული` | strict refusal is deliberately retained and requires an external baseline procedure: `application.yml:49-62`; no rehearsed existing-Oracle path |
| BL-14 | The quiz gate is skipped for a missing article | `დაფიქსირებულია` | shared fail-closed checker: `ArticleController.java:1274-1276`; `QuizGateChecker.java` |

### Frontend — FE-01…FE-10

| ID | ძველი მიგნება | სტატუსი | მიმდინარე მტკიცებულება |
|---|---|---|---|
| FE-01 | Deleting a news item or video fails in silence | `დაფიქსირებულია` | visible toast errors: `news-admin-table.ts:68-75`; `videos-admin-table.ts:67-74` |
| FE-02 | Deactivating a user fails in silence | `დაფიქსირებულია` | server detail/default toast + reload: `admin-users-page.ts:184-198` |
| FE-03 | Export polling runs forever and outlives the page | `დაფიქსირებულია` | bounded poll and lifecycle cancellation: `export.service.ts:56-82`; `team-stats-page.ts:245-293`; unit spec `export.service.spec.ts:10-93` |
| FE-04 | A quarter of all requests fail without telling the user | `დაფიქსირებულია` | explicit load/action failure states across admin views, e.g. `admin-content-page.ts:115-145,234-277`; `admin-users-page.spec.ts:84-112` |
| FE-05 | An expired session produces silence, not a login prompt | `დაფიქსირებულია` | 401 clears session and redirects with return URL: `unauthorized.interceptor.ts:8-38` |
| FE-06 | Managers are locked out of the audit log they are entitled to | `დაფიქსირებულია` | route precedes admin branch and uses audit permission guard: `app.routes.ts:80-89` |
| FE-07 | The news department filter matches nothing | `დაფიქსირებულია` | shared hierarchical matcher: `news-page.ts:59`; `department-matcher.ts:1-40`; spec `department-matcher.spec.ts:4-48` |
| FE-08 | Remaining light-only colour classes | `დაფიქსირებულია` | reviewed feature templates consistently pair surfaces/text/borders with `dark:` variants; representative `account-page.html:9-90` |
| FE-09 | Two small mobile rough edges | `დაფიქსირებულია` | responsive wrapping/grid/overflow fixes present in affected layouts; representative `account-page.html:21-26,52,66`; mobile E2E project has a dedicated viewport test |
| FE-10 | Almost no unit tests | `ნაწილობრივ` | 25 unit specs and 18 E2E specs now exist, including prior regressions; Angular suite could not run in this environment and broad coverage/axe/cross-browser gates remain unproven |

### Production readiness — PR-01…PR-14

| ID | ძველი მიგნება | სტატუსი | მიმდინარე მტკიცებულება |
|---|---|---|---|
| PR-01 | CI tests the app that is being replaced, and nothing else | `დაფიქსირებულია` | Java and Angular CI jobs now exist: `.github/workflows/ci.yml:27-65`; local Java DB-free suite passed 358/358 |
| PR-02 | PDF export is guaranteed to fail in the container | `დაფიქსირებულია` | bundled Georgian font contract/tests: `GeorgianPdfFont.java:25-76`; `GeorgianPdfFontTest.java:19-56` |
| PR-03 | Uploads and exports live on one pod's disk | `დაფიქსირებულია` | Oracle-backed `StoredFile`/export BLOB path: `FileStorageService.java:18-73`; `FileStorageServiceTest.java:26-77`; 2-replica runtime remains unverified criterion #6 |
| PR-04 | The rate limiter is per-pod and keyed on the wrong address | `ნაწილობრივ` | address resolution and account+IP key fixed, but JVM-local replica multiplication/proxy configuration remains: `LoginRateLimiter.java:45-58`, RTA-012 |
| PR-05 | A real-looking database password is committed | `დაფიქსირებულია` | unsafe HEAD default removed and startup regression exists: `ProductionSafetyGuardTest.java:84-102`; separate history exposure is RTA-007 |
| PR-06 | No `.dockerignore`, so a 183 MB dev database is in every build context | `დაფიქსირებულია` | root `.dockerignore:1-37` excludes DB/runtime/build artifacts |
| PR-07 | The frontend container runs as root | `დაფიქსირებულია` | ownership preparation + `USER nginx`: `angular-frontend/Dockerfile:26-47` |
| PR-08 | There is almost nothing in the logs | `დაფიქსირებულია` | centralized exception logging and startup state: `GlobalExceptionHandler.java:19-82`; `ProductionSafetyGuard.java:141-160` |
| PR-09 | `/api/health` hands raw database errors to anonymous callers | `დაფიქსირებულია` | wire returns generic `database:error`; exception stays in server log: `HealthController.java:49-57`; readiness-status bug is new RTA-009 |
| PR-10 | Nothing pins the Node version, and one patch release breaks the build | `დაფიქსირებულია` | `.nvmrc:1` pins `22.22.3`; engine floor `package.json:4-5`; CI reads it `.github/workflows/ci.yml:62-63` |
| PR-11 | No heap sizing in the image | `დაფიქსირებულია` | container-aware 75% heap and OOM exit: `java-backend/Dockerfile:41-53` |
| PR-12 | The export worker uses the shared default executor | `დაფიქსირებულია` | dedicated bounded executor with caller-runs rejection: `ExportExecutorConfig.java:47-59`; named async worker `ExportJobWorker.java:48-54` |
| PR-13 | The cleanup scheduler runs on every replica | `არ არის დაფიქსირებული` | scheduler remains local and unlocked: `ExportJobCleanupScheduler.java:49-60`; no ShedLock/leader election/distributed claim |
| PR-14 | No connection-timeout or leak detection on the pool | `დაფიქსირებულია` | pool max/min, connection timeout and leak detection configured: `application.yml:26-39` |

## 5. რაც კოდით ან ტესტით კარგად არის დამოწმებული

- **Java DB-free quality gate:** Maven suite completed with 358 tests, 0 failures, 0 errors and 0 skips. It includes production safety, JWT invalidation, access-contract coverage, manager scoping, upload magic-byte/storage, bounded export behavior, optimistic locking and controller failure-path tests.
- **Fail-safe local-login configuration:** `APP_ENV` defaults to production, dev login requires both non-production environment and an explicit flag, and startup rejects weak/placeholder secrets, insecure cookies and production bypass combinations.
- **Session revocation:** JWTs carry a `token_version`; the authentication filter reloads the user and compares it. Logout, password change and admin reset advance the version, so a correctly signed old token is rejected.
- **Password handling:** BCrypt is used with cost 10 and password-policy validation is centralized. No plaintext password persistence path was found in the target Java stack.
- **Upload content validation:** Size/type allowlists are supplemented by server-side magic-byte detection; filenames are UUID-based and new bytes are stored in Oracle-backed `stored_files`, not on a pod filesystem.
- **Export durability and ownership:** New export results are persisted as BLOBs, repeated downloads work, job ownership is checked, and generation uses a dedicated bounded executor rather than the common async pool.
- **Original manager overexposure regressions:** Current legacy manager paths consistently reuse `ManagerScope`, and focused tests prove that other departments' named users do not appear in export/dashboard/audit results. RTA-004 remains because the approved leadership-assignment model is not yet the enforced source of truth.
- **Endpoint gate inventory:** `AccessContractCoverageTest` covers all 130 current Spring mappings and their declared authentication/role/capability gates; no untracked mapping slipped past that current contract. This is a useful regression net, though it cannot prove that the contract itself is correct.
- **Concurrency/data-integrity fixes:** Article optimistic locking, required-reading reassignment behavior, repeatable export download, dependent cleanup and evidence snapshot IDs have focused regression tests.
- **Frontend failure feedback:** Prior silent delete/deactivate/load/export/401 flows now surface errors or redirect appropriately; polling has a hard stop and component-lifecycle cancellation.
- **Theme/responsiveness/localization foundation:** Reviewed primary screens use responsive layouts and paired light/dark classes; Georgian/English translation infrastructure is present. The stricter every-screen/browser/accessibility criterion remains unverified.
- **Static lint:** `ruff check .` passed on the Python tree.

## 6. ვერ შემოწმდა

| საკითხი | რატომ ვერ შემოწმდა | რა მტკიცებულებაა საჭირო |
|---|---|---|
| Python pytest suite | repository venv points to a missing Windows Store Python 3.11; available bundled Python 3.12 cannot load the venv's cp311 native modules | clean pinned Python 3.11 environment; `pytest tests/ -q` result |
| Angular clean install/build/unit tests | two `npm ci` attempts failed on locked native files (`lmdb` binary, then `esbuild.exe`, `EPERM`) and left Angular CLI unavailable; this is an audit-host condition, not a product defect finding | clean Node 22.22.3 workspace; `npm ci`, `npm run build`, `npm test -- --watch=false` |
| Playwright E2E | Angular build/runtime unavailable; configured project is desktop Chromium only | complete E2E on built target plus mobile Chromium and agreed Firefox/WebKit matrix |
| Clean Oracle Flyway migration | Docker/Oracle is unavailable in the audit environment | disposable supported Oracle, Flyway V1→V42 log, schema validation and smoke tests |
| Existing Oracle migration/baseline | no production-like existing schema or row-volume snapshot was supplied | masked schema/data profile, deliberate baseline/cutover rehearsal and reconciliation report |
| V36 backfill and V37 tightening | V37 does not exist and no signed reconciliation artifact is present | production-like backfill plan/result, zero unresolved rows, V37 migration and rollback evidence |
| PostgreSQL→Oracle data cutover | no ETL/mapping/count/hash/replay tool or dry-run result exists | rehearsed extract/transform/load, reconciliation, freeze/delta plan and rollback decision point |
| Docker image builds | Docker CLI/daemon is unavailable | reproducible backend/frontend image build, non-root run, image scan and startup smoke |
| Two-plus-replica behavior | no target manifests/cluster or shared rate-limit/scheduler coordination | multi-replica test covering login limits, scheduler ownership, uploads, exports and readiness |
| Backup/restore | target Oracle backup/restore implementation does not exist | witnessed isolated restore with measured RPO/RTO and content/BLOB/audit verification |
| Realistic search/page performance | supplied historical k6 run excludes search and is not a release gate or target environment | production-like volume and topology; p50/p95/p99/error/DB/heap metrics for search/main screens |
| Manual accessibility and full device/browser QA | no assistive-technology run, axe suite or cross-browser E2E result | keyboard + screen-reader checklist, axe results, mobile/tablet and browser matrix |
| Full secret-history assurance | targeted key/pattern search found known exposures, but no full entropy/provider scan or credential-system access was available | enterprise secret scanner over all refs plus rotation/revocation evidence |
| Ingress/WAF/TLS/security headers | no production ingress manifest or external platform configuration supplied | TLS/HSTS/CSP/frame/referrer policy, proxy chain, WAF/body limits and penetration/smoke evidence |
| Monitoring/alerting/SIEM | no target dashboards, alert rules, log route, on-call owner or access method supplied | metrics/log/traces contract, alert tests, SIEM access/export, retention/redaction and runbook |
| SSO/AD lifecycle | corporate authentication/OU/stable-ID/delta/deactivation answers are open | signed contract and test tenant covering login, role/group sync, rename/move and deactivation |

## 7. IT დამოკიდებულებები

`docs/QUESTIONS_FOR_IT.md` currently contains 11 questions; all 11 are marked `🔲 ღიაა` and each answer is `ჯერ არ არის`. The table below distinguishes the launch effect without pretending that repository text is an external IT answer.

| # | ღია კითხვა | launch effect | დახურვის მისაღები მტკიცებულება |
|---:|---|---|---|
| 1 | კომპანიის ავტორიზაციის კონტრაქტი | **BLOCKER** — production identity/login/claims/logout contract unknown | named system owner, protocol/endpoints/claims/session/error contract and integration test account |
| 2 | AD OU-სტრუქტურა დეპარტამენტებისთვის | **BLOCKER** — org scope/backfill cannot be reconciled safely | authoritative OU/group mapping, exception rules, sample export and owner sign-off |
| 3 | არსებული CI/CD მილსადენი | **BLOCKER** — no controlled deploy/approval/rollback path | pipeline/registry/environments/approvals/secrets/scans/deploy+rollback evidence |
| 4 | კლასტერის დეტალები | **BLOCKER** — manifests, ingress, storage, secrets and Oracle reachability cannot be finalized | cluster namespace, registry, ingress/TLS, secret store, resources, DNS/network and support owner |
| 5 | Oracle-ის განახლების გეგმა | **CONDITION / BLOCKER if required target differs** — supported version/driver/features uncertain | DBA-confirmed current/target Oracle version, maintenance window, compatibility and rollback |
| 6 | Oracle-ის ტაბლსფეისი ატვირთული ფაილებისთვის | **BLOCKER** — BLOB capacity, backup and restore ownership unknown | tablespace sizing/growth/quota, backup inclusion, encryption, restore and alert plan |
| 7 | Reverse proxy-ის მისამართი | **BLOCKER** — real client IP, audit attribution and throttling cannot be configured | exact trusted proxy IP/CIDR chain plus spoofing/NAT verification |
| 8 | რამდენი replica იგეგმება? | **BLOCKER until topology fixed** — rate limiting and cleanup ownership change with N | replica/autoscaling plan and shared limiter/single-owner scheduler decision |
| 9 | დაცული დანართების ინფრასტრუქტურული მხარდაჭერა | **BLOCKER** — public-upload flaw cannot be closed compatibly | authorized download/stream or signed-URL decision, cache/CDN behavior and frontend contract |
| 10 | SIEM-იდან დაცული წვდომა/ექსპორტი | **BLOCKER for operations/audit** — external immutable evidence and incident access absent | log route, RBAC, retention/redaction, export procedure, alerts and on-call validation |
| 11 | Stable ID-ები, ცვლილებების feed და deactivation | **BLOCKER** — identity rename/move/offboarding may retain or misassign access | immutable identifier, delta/full feed, deactivation semantics, group membership source and reconciliation test |

## 8. არატექნიკური შეჯამება

პროექტში მნიშვნელოვანი გამაგრებაა გაკეთებული: Java-ის 358 ტესტი გადის, production-ის დაუცველი login fail-safe-ად იკეტება, ძველი manager data leaks-ის კონკრეტული გზები დაიხურა, ატვირთვები და export-ები ბაზაში ინახება და მომხმარებლისთვის ბევრი ჩუმი შეცდომა უკვე ხილულია. ეს კარგი საფუძველია, მაგრამ launch-ის თანხმობა არ არის.

ამჟამად production-ის ოპერატორს არ აქვს ახალი სისტემის გასაშვები და დასაბრუნებელი პაკეტი. root ინსტრუქციები ძველ Python/PostgreSQL პროდუქტს უშვებს, ხოლო ახალი handover თავად ამბობს, რომ გამოყენება არ შეიძლება. ამავე დროს Oracle-ის backup/restore და მონაცემთა cutover გამოცდილი არ არის.

უსაფრთხოების ორი უშუალო შეტევის გზა launch-ს ცალ-ცალკე ბლოკავს: დანართის URL login-ის გარეშე მუშაობს და stored rich-text შეტევას შეუძლია ადმინისტრატორის browser-იდან bearer token-ის მოპარვა. ორგანიზაციული წვდომის ახალი leadership მოდელი ჯერ მხოლოდ აკვირდება განსხვავებას და რეალურ პასუხად ისევ ძველ free-text department-ს იყენებს.

შემდეგი gate უნდა იყოს არა ფართო „დავასრულოთ დარჩენილი სამუშაო“, არამედ ხუთივე ბლოკერის დახურვა გამეორებადი მტკიცებულებით: target deploy+rollback rehearsal, protected attachments, XSS exploit regression, leadership-scope cutover და Oracle restore test. ამის შემდეგ უნდა გაეშვას სრული Python/Angular/Oracle/E2E suite და production-like performance/accessibility/multi-replica შემოწმება; მხოლოდ ყველა 14 exit criterion-ის PASS სტატუსი იძლევა `READY` ვერდიქტს.

## საბოლოო რაოდენობები

| საზომი | რაოდენობა |
|---|---:|
| ახალი მიგნება | **20** |
| `BLOCKER` | **5** |
| `HIGH` | **9** |
| `MEDIUM` | **6** (მათგან 1 `SUSPECTED`) |
| `LOW` | **0** |
| `CONFIRMED` | **19** |
| `SUSPECTED` | **1** |
| ძველი 54-დან `დაფიქსირებულია` | **43** |
| ძველი 54-დან `ნაწილობრივ` | **5** |
| ძველი 54-დან `არ არის დაფიქსირებული` | **4** |
| ძველი 54-დან `აღარ ვრცელდება` | **2** |
| წარმატებით შესრულებული automated tests | **358 Java tests** |
| წარმატებული static quality commands | **1 (`ruff check .`)** |
| უშუალოდ/line-by-line შინაარსობრივად შემოწმებული repository files | **დაახლოებით 210** |
| repository-wide inventory/search-ით დაფარული code/test/migration files | **600-ზე მეტი** |

**Final verdict: `NOT READY`.** ერთი ბლოკერიც საკმარისი იქნებოდა; დადასტურებულია ხუთი.
