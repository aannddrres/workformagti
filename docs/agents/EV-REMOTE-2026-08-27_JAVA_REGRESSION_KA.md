# EV-REMOTE-2026-08-27 — Java/Oracle regression + package + IDOR სტატიკური აუდიტი

> **სტატუსი: NOT READY** (verdict უცვლელია).
> ეს ფაილი **არ** ცვლის და **არ** ანაცვლებს
> `docs/ENTERPRISE_READINESS_*_KA.md` / `docs/agents/HANDOFF.md`-ს —
> ის ცალკე, დამატებითი evidence ჩანაწერია remote სესიიდან
> (იხ. §0 „გარემოს შეუსაბამობა"). სახელი განზრახ არ ემთხვევა
> არცერთ არსებულ enterprise-readiness დოკუმენტს, რომ merge-ის დროს
> ისინი არ გადაეწეროს.

---

## 0. გარემოს შეუსაბამობა (კრიტიკული)

მითითებული workspace: `C:\Projects\Magti base` (Windows).
რეალური სამუშაო გარემო: **remote Linux container**, `/home/user/workformagti`.
ეს **არ არის** იგივე მანქანა და არ აქვს წვდომა Windows workspace-ზე.

გადამოწმებული repository snapshot (2026-08-27T05:48Z):

| ველი | მნიშვნელობა |
|---|---|
| branch | `claude/enterprise-readiness-verify-lq1eto` |
| HEAD | `4bf6e6227de2b9ec17812970566fcb6ddbf06bb9` |
| upstream (local tracking) | **არ არის კონფიგურირებული** (`fatal: no upstream configured`) |
| `origin/claude/enterprise-readiness-verify-lq1eto` | `4bf6e622…` — იდენტური HEAD-თან |
| `origin/main` | `4bf6e622…` — იდენტური HEAD-თან |
| ahead / behind | `0 / 0` (სამივე ref ერთსა და იმავე commit-ზეა) |
| `git status --porcelain` (collapsed) | **0 ჩანაწერი** |
| `git status` (expanded) | modified: 0 · deleted: 0 · untracked: 0 |
| tracked deletion | **არცერთი** |
| `git diff --check` | სუფთა (exit 0) |
| `git stash list` | ცარიელი |
| მოსმენილი პორტები | **არცერთი** (არც 4200, არც 8080, არც 1521) |

**შედეგი:** handoff-ში აღწერილი მდგომარეობა ამ გარემოში არ არსებობს:

- `docs/ENTERPRISE_READINESS_AI_EXECUTION_PLAN_KA.md` — **არ არსებობს**
- `docs/ENTERPRISE_READINESS_ACCEPTANCE_MATRIX_KA.md` — **არ არსებობს**
- `docs/ENTERPRISE_READINESS_REPORT_2026-08-25_KA.md` — **არ არსებობს**
- `docs/ENTERPRISE_READINESS_DECISIONS_KA.md` — **არ არსებობს**
- `docs/ENTERPRISE_READINESS_TEST_EVIDENCE_KA.md` — **არ არსებობს**
- `docs/ENTERPRISE_READINESS_EXTERNAL_DEPENDENCIES_KA.md` — **არ არსებობს**
- `docs/agents/HANDOFF.md` — **არ არსებობს**

გადამოწმებულია არა მხოლოდ working tree-ში, არამედ git ისტორიაშიც:
`git log --all --diff-filter=A -- 'docs/ENTERPRISE*' 'docs/agents/*'` — 0 შედეგი;
`git log --all --grep="EV-2"` და `--grep="enterprise" -i` — 0 შედეგი.

ე.ი. EV-229 snapshot, P0-A18-ის 20/20 verification, `DEC-P01`, `DEC-P02`
**არასდროს ყოფილა commit-ული ამ repository-ში** — ისინი მხოლოდ ლოკალურ
Windows workspace-ში არსებობს. ამ სესიაში მათი წაკითხვა, გადამოწმება ან
განახლება ფიზიკურად შეუძლებელია.

არცერთი ცვლილება არ დაკარგულა: working tree თავიდანვე სუფთა იყო და სუფთა
დარჩა (არც `reset`, არც `clean`, არც `checkout`, არც `restore` არ გაშვებულა).

---

## 1. Toolchain (გაზომილი, არა ნავარაუდევი)

| კომპონენტი | ვერსია |
|---|---|
| Maven (wrapper `./mvnw`) | 3.9.16 (`2bdd9fddda4b155ebf8000e807eb73fd829a51d5`) |
| JDK | OpenJDK 21.0.10 (Ubuntu, `21.0.10+7-Ubuntu-124.04`) |
| Spring Boot parent | 4.1.0 |
| Spring Security | 7.1.0 |
| Hibernate ORM | 7.4.1.Final |
| **Flyway** | `flyway-core` **12.4.0** + `flyway-database-oracle` **12.4.0** |
| **Oracle JDBC** | `com.oracle.database.jdbc:ojdbc11` **23.26.2.0.0** |
| უმაღლესი migration | **V35** (`V35__retain_article_id_on_deletion_evidence.sql`) |
| Oracle server | **მიუწვდომელი** — იხ. §4 |

---

## 2. Run 1 — `./mvnw -B test` (სრული suite) — ❌ BUILD FAILURE

```
Tests run: 514, Failures: 0, Errors: 257, Skipped: 0
BUILD FAILURE
Total time:  39.611 s
Finished at: 2026-08-27T05:49:02Z
```

**ყველა 257 error ერთი და იმავე მიზეზისაა:** `ORA-12541` (TNS: no listener) /
`Connection refused` — Oracle არ არსებობს `localhost:1521/orclpdb1`-ზე.
არცერთი assertion არ ჩავარდნილა (`Failures: 0`).

დაზარალებული კლასები (20, ყველა `@RequiresOracle` / `@SpringBootTest`):
`PortalBackendApplicationTests`, `audit.AuditChainServiceTest`,
`repository.OracleRoundTripTest`, და `web.*IntegrationTest` × 17
(Article, AuditLog, Auth, Category, Compliance, Export, Favorite, Messaging,
News, Platform, Quiz, Search, Stats, Upload, User, Video).

**ეს არ არის regression.** ეს არის გარემოს ნაკლი — იხ. §4.

---

## 3. Run 2 — `./mvnw -B test -DexcludedGroups=oracle` (DB-free) — ✅ BUILD SUCCESS

ეს არის ზუსტად ის, რასაც CI-ის სწრაფი job აწარმოებს
(`.github/workflows/ci.yml:53`).

```
Tests run: 257, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time:  6.617 s
Finished at: 2026-08-27T05:49:54Z
```

დაფარვის გაყოფა (`@Test` მეთოდების სტატიკური აღრიცხვა):
სულ 71 test source file / 70 კლასი `@Test`-ით / 515 `@Test` ანოტაცია;
20 კლასი `@RequiresOracle`, 50 კლასი DB-free.
Runtime-ის გაყოფა: **257 DB-free (მწვანე) + 257 Oracle-gated (ვერ გაეშვა) = 514**.

---

## 4. Run 3 — `./mvnw -B clean -DskipTests package` — ✅ BUILD SUCCESS

```
Copying 1 resource   from src/main/resources to target/classes
Copying 37 resources from src/main/resources to target/classes
Compiling 250 source files with javac [debug parameters release 21] to target/classes
Copying 1 resource   from src/test/resources to target/test-classes
Compiling  71 source files with javac [debug parameters release 21] to target/test-classes
Building jar: target/portal-backend-0.0.1-SNAPSHOT.jar
Replacing main artifact ... with repackaged archive, adding nested dependencies in BOOT-INF/.
BUILD SUCCESS
Total time:  7.794 s
Finished at: 2026-08-27T05:50:56Z
```

| მეტრიკა | მნიშვნელობა |
|---|---|
| main source files | **250** |
| test source files | **71** |
| main resources | **38** (1 + 37, ორ ცალკე copy ბიჯად) |
| test resources | **1** |
| artifact | `target/portal-backend-0.0.1-SNAPSHOT.jar` |
| JAR ზომა | **93 096 060 ბაიტი** (≈ 88.8 MiB / 89 MB) |
| ტიპი | Spring Boot repackaged (executable, nested deps `BOOT-INF/`) |
| clean build დრო | **7.794 s** |

**Executable package მიღებულია.** კომპილაცია (main + test) სუფთაა.

> ზემოთ მოცემული 71 test source file არის **baseline** (§6.1-ის ახალ
> ტესტამდე). მისი დამატების შემდეგ ხელახლა გაშვებული clean package:
> `Compiling 72 source files ... to target/test-classes`, `BUILD SUCCESS`,
> `Total time: 6.342 s`, `Finished at: 2026-08-27T06:01:39Z`,
> JAR უცვლელი — **93 096 060 ბაიტი** (ტესტი jar-ში არ ხვდება).

---

## 5. External blocker — Oracle მიუწვდომელია

`.github/workflows/ci.yml:118` Oracle-იან job-ებს აწვდის
`gvenzl/oracle-xe:21-slim-faststart`-ს. ამ container-ში:

- Docker daemon ხელით აეშვა და მუშაობს (29.3.1, overlayfs);
- `docker pull gvenzl/oracle-xe:21-slim-faststart` **ჩავარდა**:
  ```
  production.cloudfront.docker.com:443
  gateway answered 403 to CONNECT (policy denial or upstream failure)
  ```
  (დადასტურებული agent-proxy-ის status endpoint-იდან, `recentRelayFailures`).

პროექტს **არ აქვს** Testcontainers dependency (`pom.xml`-ში არ არის),
ამიტომ ალტერნატიული ავტომატური Oracle-ის აწევა არ არსებობს.

**შედეგი — `External`:**
1. P0-A18-ის შემდგომი **სრული Java/Oracle regression ვერ დასრულდა**;
2. P0-A18-ის focused Video/Access 20/20 **ვერ გადამოწმდა ხელახლა**;
3. WS2 P0 negative/IDOR-ის **ყველა** არსებული ტესტი Oracle-gated-ია
   (ყველა `isForbidden/isNotFound/isUnauthorized` assertion ცხოვრობს
   `@RequiresOracle` კლასებში), ამიტომ ახალი negative/IDOR ტესტის
   დაწერაც შესაძლებელია, მაგრამ **გაშვება/დადასტურება — არა**.

Evidence gate ღიაა → **verdict რჩება NOT READY**.

---

## 6. WS2 P0 — negative/IDOR სტატიკური აუდიტი (Oracle-ს არ საჭიროებს)

მეთოდი: ყველა controller-ის ყველა path-variable-იანი endpoint
(`{id}`-ის შემცველი) და მისი ავტორიზაციის დამცავი.
სულ **18 `@RestController`**, **112 endpoint**, აქედან **54**
path-variable-იანი (გაზომილი, არა შეფასებული).

`@PreAuthorize` პროექტში **საერთოდ არ გამოიყენება** (0 დამთხვევა), და
`SecurityConfig:45` არის `anyRequest().permitAll()` — ე.ი. framework-ის
დონეზე **არცერთი** endpoint არ არის დაცული. მთელი ავტორიზაცია ცხოვრობს
handler-ის შიგნით: `require*` helper-ები + `PermissionChecker` +
`ManagerScope`. ეს მუშაობს, მაგრამ **fail-open**-ია: ახალი endpoint,
რომელიც უბრალოდ დაივიწყებს guard-ის გამოძახებას, ხდება საჯარო და
framework ამას ვერ ამჩნევს. იხ. §6.1.

### 6.1 ✅ ახალი guard — `EndpointPrincipalCoverageTest` (DB-free, გაშვებული და მწვანე)

ფაილი: `java-backend/src/test/java/ge/magti/portal/web/EndpointPrincipalCoverageTest.java`

რას ამოწმებს: ყველა `@RestController`-ის ყველა `@*Mapping` handler-ს
უნდა გადაეცემოდეს `@AuthenticationPrincipal User`. handler, რომელსაც
გამომძახებელი არ ეძლევა, **სტრუქტურულად** ვერ შეამოწმებს role-ს,
permission-ს ან owner id-ს — რაც არ უნდა ეწეროს მის სხეულში.

რატომ ასე და არა „guard გამოძახებულია"-ს შემოწმება: method body
reflection-ისთვის უხილავია. ეს არის **იატაკი, არა ჭერი** — ვერ დაიჭერს
handler-ს, რომელიც principal-ს იღებს და უგულებელყოფს (ამისთვისაა
`*IntegrationTest` negative case-ები), მაგრამ იჭერს იმ შეცდომას,
რომელსაც სხვა backstop საერთოდ არ აქვს.

`PUBLIC_BY_DESIGN` allowlist — 3 ჩანაწერი, თითოეული მიზეზით:

| route | მიზეზი |
|---|---|
| `POST /api/auth/login` | ტოკენს თვითონ გასცემს; principal ჯერ არ არსებობს. ბოროტად გამოყენებას ზღუდავს `LoginRateLimiter`, არა authentication |
| `GET /api/health` | liveness/readiness probe; აბრუნებს მხოლოდ `ok`/`redis: not_configured`; ტოკენიანი probe load balancer-ისთვის უვარგისია |
| `GET /uploads/{filename}` | **DEC-P01 / QUESTIONS_FOR_IT №9 — განზრახ გადაუწყვეტელი.** ჩამაგრებულია აქ, რომ ამ კითხვის დახურვა გამოჩნდეს როგორც განზრახი edit |

allowlist მოწმდება **ორივე მიმართულებით** (მეორე `@Test`): ჩანაწერი,
რომელიც აღარ არსებულ route-ს ასახელებს, ან route, რომელიც უკვე იღებს
principal-ს — ორივე აფეილებს build-ს. ე.ი. allowlist ვერ გადაურჩება იმ
გადაწყვეტილებას, რომელსაც ის აღრიცხავს, და `DEC-P01`-ის დახურვა
ავტომატურად მოითხოვს ამ ჩანაწერის წაშლას.

**გაშვების evidence:**

```
mvn -B test -DexcludedGroups=oracle -Dtest=EndpointPrincipalCoverageTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (5.627 s)
```

**Negative control (mutation probe — დადასტურებულია, რომ ტესტი ცარიელი არაა):**
allowlist-ის `GET /uploads/{filename}` დროებით შეიცვალა არარსებული
route-ით; **ორივე** `@Test` ჩავარდა სწორად და სწორი შეტყობინებით:

```
everyEndpointReceivesTheAuthenticatedPrincipal
  expected: <[]> but was: <[GET /uploads/{filename}  (UploadedFileController#serve)]>
allowlistHasNoStaleEntries
  expected: <[]> but was: <[GET /__mutation_probe_route__]>
```

ფაილი შემდეგ byte-identical აღდგა (`diff -q` — იდენტური).

**სრული DB-free suite ახალი ტესტით:**

```
mvn -B test -DexcludedGroups=oracle
Tests run: 259, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (9.539 s)
Finished at: 2026-08-27T05:59:27Z
```

(257 → 259; +2 ახალი, არცერთი არსებული არ დაზიანებულა.)

ეს ტესტი **არ ცვლის** არცერთ role/scope/export/retention/security
გადაწყვეტილებას — ის მხოლოდ აფიქსირებს და კეტავს არსებულ მდგომარეობას.

### ✅ სწორად დაცული (ownership/scope რეალურად მოწმდება)

| ზედაპირი | მექანიზმი |
|---|---|
| `DELETE /api/favorites/{id}` | `findByIdAndUserId` → 404 სხვისზე |
| `POST /api/messages/{messageId}/read`, `DELETE /api/messages/{messageId}` | `findByIdAndUserId` → 404 სხვისზე |
| `POST /api/messages` | `DirectMessagePermission.canSend` (role + department) |
| `POST /api/compliance/mark-read/{readingId}` | `DepartmentMatcher.matches` → 403 უცხო დეპარტამენტზე; `ReadStatus` იწერება მხოლოდ `user.getId()`-ზე |
| `PUT/DELETE /api/compliance/required-readings/{readingId}` | `requireComplianceAssign` / `contentAdmin` + `Permission.COMPLIANCE_ASSIGN` |
| `POST /api/articles/{id}/quiz/attempt` | attempt იწერება მხოლოდ `user.getId()`-ზე |
| `PUT /api/users/{userId}`, `/status`, `/reset-password`, `/permissions` | `requireSystemAdmin` (+ SYSTEM_ADMIN-ის role-escalation დაცვა `UserController:582`) |
| `GET/PUT /api/users/me*` | self-scoped, `@AuthenticationPrincipal`-იდან |
| `/api/manager/team-stats`, `/api/admin/departments/{d}/groups/{g}/users` | `ManagerScope.visibleActiveUsers` (prefix-aware, SEC-13) — query string-იდან სხვა department-ის გადაცემა იგნორირდება |
| `GET /api/admin/stats/team/{teamId}` | `requireContentAdmin` — org-wide by design, არა manager-ის ზედაპირი |

**IDOR ვერ დადასტურდა** ვერცერთ ზემოთ ჩამოთვლილში (სტატიკურად).

### ⚠️ ღია ზედაპირი 1 — export job-ს მფლობელი არ ჰყავს  **[ახალი — გადაწყვეტილება საჭიროა]**

- `GET /api/export/status/{jobId}` — `ExportController:213`
- `GET /api/export/download/{jobId}` — `ExportController:245`

ორივე ამოწმებს **მხოლოდ** `requireReportsExport(user)`-ს
(`ExportController:403-413`), რაც არის `Permission.REPORTS_EXPORT`-ის
ქონა — **და მეტი არაფერი**. `jobId`-ის მფლობელობა არ მოწმდება.

მიზეზი უფრო ღრმაა ვიდრე გამორჩენილი `if`:
**`export_jobs` ცხრილს საერთოდ არ აქვს owner სვეტი.**
`domain/ExportJob.java` ველები: `id`, `status`, `path`, `content`,
`filename`, `expires_at` — არც `created_by`, არც `user_id`
(`V15__create_export_jobs.sql`, `V31__stored_files_and_export_blobs.sql`).
`ExportController`-ში `created_by|createdBy|ownerId|getUserId` — **0 დამთხვევა**.

რატომ აქვს მნიშვნელობა: export-ის შიგთავსი **scope-დამოკიდებულია** —
`ExportQueryService:114` აჭრის მონაცემებს `ManagerScope`-ით მთხოვნელის
მიხედვით. ე.ი. A დეპარტამენტის manager-ის job შეიცავს A-ს პერსონალურ
მონაცემებს; B დეპარტამენტის manager, რომელსაც ასევე აქვს
`reports.export`, ჩამოტვირთავს მას **მთლიანად**, თუ `jobId` ხელში ჩაუვარდა.

შემამსუბუქებელი: `jobId` არის `UUID.randomUUID()` (`ExportController:327`) —
არაგამოსაცნობი და არაენუმერირებადი. ე.ი. ეს არის
*authorization-by-obscurity*, არა ღია ენუმერაცია. ექსპლუატაცია მოითხოვს
`jobId`-ის მოპოვებას (ლოგი, browser history, გაზიარებული ეკრანი,
audit trail, referrer).

დარღვევა: SEC-02-ის განზრახვა (`ExportController:339-346`) — „audit trail-მა
უნდა უპასუხოს, ვისი პერსონალური მონაცემები გავიდა პორტალიდან" — ვერ სრულდება
თუ ჩამომტვირთავი შეიძლება სხვა იყოს, ვიდრე შემქმნელი.

**არ შევცვალე.** გამოსწორება მოითხოვს ახალ Flyway migration-ს
(`V36`: `export_jobs.created_by` + backfill/nullable სტრატეგია) და ეხება
დადასტურებულ **export** flow-ს → საჭიროა ცალკე გადაწყვეტილება
(შემოთავაზებული იდენტიფიკატორი: **`DEC-P03`**).

### ⚠️ ღია ზედაპირი 2 — `/uploads/{filename}` სრულიად საჯაროა  **[= DEC-P01, არ შევეხე]**

`UploadedFileController:54` — არანაირი დამცავი; `SecurityConfig`-ის
`anyRequest().permitAll()`-ის ქვეშ. ეს არის ზუსტად `DEC-P01`-ის
(file entitlement) საგანი და უკვე დაფიქსირებულია როგორც
`docs/QUESTIONS_FOR_IT.md` კითხვა №9. **გადაწყვეტილების გარეშე არ შეიცვალა.**

Trade-off (უცვლელად, კონტროლერის javadoc-იდან): token-ის მოთხოვნა
გატეხავს inline `<img src="/uploads/...">`-ს სტატიის ტექსტში, სანამ
frontend არ გახდება token-aware.

### ℹ️ დაკვირვება — `PermissionChecker`-ის SYSTEM_ADMIN bypass

`PermissionChecker:53-56` — SYSTEM_ADMIN გვერდს უვლის ყველა permission-ს.
ეს არსებული, დოკუმენტირებული და განზრახი დიზაინია (კლასის javadoc);
**აქ არ იცვლება**, მხოლოდ აღინიშნება, რომ ნებისმიერი ახალი
export-ownership შემოწმება ამ bypass-ს დაექვემდებარება.

---

## 7. რა **ვერ** დადასტურდა ამ სესიაში

- სრული Java/Oracle regression (257 Oracle-gated ტესტი) — `External`
- P0-A18 Video/Access 20/20 — `External` (Oracle-gated)
- ნებისმიერი ახალი negative/IDOR ტესტის runtime დადასტურება — `External`
- `DEC-P01` / `DEC-P02` სტატუსი — ვერ წავიკითხე (დოკუმენტი არ არსებობს)
- EV-229 snapshot-თან შედარება — ვერ მოხერხდა (ჩანაწერი არ არსებობს)
- Angular frontend / e2e — არ შეხებია (dev server არსად მუშაობს)

---

## 8. Verdict

**NOT READY** — უცვლელი.
Evidence gate „P0-A18-ის შემდგომი სრული Java/Oracle regression" **ღიაა**
და ამ გარემოში დახურვადი არ არის.
Executable package gate — **დახურულია** (§4).
DB-free unit/slice gate — **დახურულია** (§3, 257/257 მწვანე).

---

## 11. WS2 P0 გაგრძელება — DB-free ციკლი 2

### 11.1 `EndpointGuardCoverageTest` — bytecode-დონის guard შემოწმება

`EndpointPrincipalCoverageTest` (§6.1) მხოლოდ *იატაკია*: ის ამოწმებს, რომ
handler-ს **გადაეცემა** გამომძახებელი. ღია რჩებოდა ზუსტად ის შემთხვევა,
რომელიც თვითონვე დაასახელა — handler, რომელიც `@AuthenticationPrincipal
User`-ს იღებს და **არასდროს უყურებს**. `permitAll`-ის ქვეშ ასეთი endpoint
ყველასთვის ხელმისაწვდომია, პარამეტრი კი დაცულის შთაბეჭდილებას ტოვებს.

ახალი ტესტი ამ ნახვრეტს კეტავს. method body reflection-ისთვის უხილავია,
**bytecode-ისთვის — არა**, და Spring-ს უკვე მოაქვს repackaged ASM
(`org.springframework.asm`) — ახალი dependency საჭირო არ არის. ტესტი
კითხულობს თითოეული controller-ის `.class` ფაილს, აგროვებს რას იძახებს
თითოეული handler, **მიჰყვება იმავე კლასის private helper-ებში**
(repo-ს ყველა `require*` guard private-ია და რამდენიმე handler მათ
helper-ის გავლით აღწევს) და მოითხოვს, რომ call closure-ში იყოს
ამოცნობილი guard.

ამოცნობილი guard-ები (განზრახ მოკლე სია):

| ტიპი | რა ითვლება |
|---|---|
| controller-ის საკუთარი | ნებისმიერი მეთოდი `require`-ით (`requireAuthenticated`, `requireSystemAdmin`, `requireContentAdmin`, `requireManagerOrAdmin`, `requireReportsExport`, `requireComplianceAssign`, `requireSystemAuditNonManager`) — prefix-ით, რომ ახალი guard დაწერის დღესვე ხილული იყოს |
| permission | `PermissionChecker.hasPermission` |
| scope | `ManagerScope.isDepartmentScoped` / `.visibleActiveUsers` |
| department | `DepartmentMatcher.matches` |
| messaging | `DirectMessagePermission.canSend` |
| quiz gate | `QuizGateChecker.denialFor` |
| ownership | repository finder `findBy…UserId…` — favourites/messages **არასდროს** მოიძებნება მარტო id-ით, მხოლოდ (id, userId)-ით, ამიტომ სხვისი row ცარიელი ბრუნდება და handler პასუხობს 404-ს. ეს **არის** authorization და ისე ითვლება |

**რას ვერ ამტკიცებს (ჩაწერილია javadoc-ში):** ამტკიცებს, რომ guard
**გამოძახებულია**, არა რომ მისი შედეგი **გათვალისწინებულია** — handler,
რომელიც `requireSystemAdmin(user)`-ს იძახებს და denial-ს გადააგდებს,
ისევ გაივლის. ასევე ვერ ამოწმებს, სწორი guard-ია თუ არა არჩეული
(`requireAuthenticated` admin-only endpoint-ზე დააკმაყოფილებს).
ეს რჩება `*IntegrationTest` negative case-ების საქმედ. ასევე
**ვერ ხედავს lambda-ს შიგნით გამოძახებულ guard-ს** (`invokedynamic` სხვა
ინსტრუქციაა) — ეს უსაფრთხო მიმართულებით ცდება: ასეთი handler **ჩავარდება**,
არ გაივლის.

**შედეგი: 112 endpoint-იდან 108 ამოწმებს guard-ს. 4 გამონაკლისი:**

| route | მიზეზი |
|---|---|
| `POST /api/auth/login` | ჯერ არავინაა უარსაყოფი; ზღუდავს `LoginRateLimiter` |
| `POST /api/auth/logout` | **DEC-P02 — logout contract.** განზრახ null-tolerant (`AuthController:136-141`): გასვლა როცა უკვე გასული ხარ შეცდომა არაა, და 401 აქ frontend-ის საკუთარ logout გზას ჩიხში მოაქცევდა ტოკენის ვადის გასვლისას. **principal-ს იღებს**, ამიტომ §6.1-ის ტესტს გადის — ეს ერთადერთი endpoint-ია, სადაც ორი guard კანონიერად არ თანხმდება |
| `GET /api/health` | probe; მხოლოდ status სტრიქონები |
| `GET /uploads/{filename}` | **DEC-P01** — იგივე ჩანაწერი, რაც §6.1-ში |

`DEC-P02`-ის ჩამაგრება allowlist-ში იმავე ლოგიკით ხდება, რაც `DEC-P01`-ის:
გადაწყვეტილების დახურვა **ავტომატურად** მოითხოვს ჩანაწერის წაშლას,
რადგან allowlist ორივე მიმართულებით მოწმდება.

**Evidence:**

```
mvn -B test -DexcludedGroups=oracle -Dtest='Endpoint*CoverageTest'
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (5.040 s)
```

**Negative control — ორი mutation probe:**

*Probe A* (allowlist-იდან `POST /api/auth/logout` მოხსნილი) → სწორად
ჩავარდა და სწორად დაასახელა:
```
expected: <[]> but was: <[POST /api/auth/logout  (AuthController#logout)]>
```

*Probe B* (`GUARD_PREFIX` = `"require"` შეიცვალა არარსებული prefix-ით,
რომ ანალიზი დაბრმავებულიყო) → **60 endpoint** გამოცხადდა ungoverned-ად.
ეს ამტკიცებს, რომ closure ანალიზი რეალურად მიჰყვება `require*`
გამოძახებებს (private helper-ების ჩათვლით) 60 endpoint-ისთვის, დანარჩენ
48-ს კი სხვა ტიპის guard ფარავს — ე.ი. ტესტი **არ არის ცარიელი**.

ფაილი ორივე probe-ის შემდეგ byte-identical აღდგა (`diff -q`).

### 11.2 `ControllerEndpoints` — გაზიარებული scanner

ორივე coverage ტესტი ერთსა და იმავე endpoint სიას უნდა უყურებდეს.
scanner გატანილია `ControllerEndpoints`-ში (test scope), `EndpointPrincipalCoverageTest`
გადაყვანილია მასზე. მიზეზი javadoc-შია: ორი guard, რომლებიც ვერ
შეთანხმდებოდნენ „რა არის endpoint"-ზე, თითოეული სხვა ქვესიმრავლეს
შეამოწმებდა, და მათ შორის ჩავარდნილი endpoint ზუსტად ის იქნებოდა,
რომელსაც ვერავინ შეამჩნევდა. ისინი განსხვავდებიან იმით, **რას** ამტკიცებენ,
არასდროს — იმით, **რას უყურებენ**.

### 11.3 Negative/IDOR დაფარვის ინვენტარიზაცია (ანალიზი, არა gate)

112 endpoint შედარდა ყველა integration ტესტის negative assertion-ს
(`isForbidden` / `isUnauthorized` = role denial; `isNotFound` = ownership-ის
სტილის denial):

| კატეგორია | რაოდენობა |
|---|---|
| აქვს 403/401 denial ტესტი | **72** |
| მხოლოდ 404 ტესტი (ownership-ის სტილი) | **10** |
| **არანაირი negative ტესტი** | **30** |

**არანაირი negative დაფარვა (30)** — WS2 P0-ის დარჩენილი სამუშაოს რუკა:

- **ArticleController (9):** `/api/admin/articles/stale`, `{id}/read-receipt/me`, `{id}/read-receipts`, `{id}/related`, `{id}/unarchive`, `{id}/versions`, `{id}/view`, `{id}/views`, `/api/me/recently-viewed`
- **StatsController (7):** `/api/admin/stats/team/{teamId}`, `/api/manager/team-stats`, `/api/statistics/activity`, `/breakdown`, `/compliance`, `/failed-searches`, `/popular-searches`
- **ExportController (3):** `/api/export/download/{jobId}` ⚠️, `/readings.xlsx`, `/team-stats.pdf`
- **QuizController (3):** `{id}/quiz/attempt`, `/api/knowledge-leaderboard`, `/api/users/me/knowledge-score`
- **ComplianceController (2):** `/my-progress`, `/required-readings/by-item/{itemType}/{itemId}`
- **სხვა (6):** `GET /api/audit-logs/{id}/verify`, `POST /api/auth/logout` (DEC-P02), `PATCH /api/news/{id}/autosave`, `GET /uploads/{filename}` (DEC-P01), `POST /api/users/me/password`, `POST /api/videos/{id}/unarchive`

⚠️ `GET /api/export/download/{jobId}` ორმაგად ღიაა: **არც** ownership
შემოწმება აქვს (§6, `DEC-P03`) **და არც** negative ტესტი.

**მეთოდის ვალიდაცია (spot-check, არა ნდობა ევრისტიკაზე):**

- `POST /api/articles/{id}/view` — ერთადერთი ტესტი უცხო operator-ით
  (`ArticleControllerIntegrationTest:1370`) **განზრახ** `isOk()`-ს ამტკიცებს
  („view-tracking still succeeds anyway"). ე.ი. denial ტესტი მართლაც არ არსებობს.
- `POST /api/users/me/password` — 4 negative ტესტი
  (`UserControllerIntegrationTest:172-190`) მხოლოდ `isBadRequest`-ია
  (პაროლის ვალიდაცია), არა authorization denial. კლასიფიკაცია სწორია.

**რატომ ანალიზი და არა build gate:** ratchet ტესტი, რომელიც ტესტების
**წყაროს** parse-ს დააფუძნებდა, მყიფე იქნებოდა; ორი გაშვებული gate
(§6.1 reflection, §11.1 bytecode) სტრუქტურულ მონაცემებს კითხულობს და
არა ტექსტს. ეს ინვენტარიზაცია სამუშაოს რუკაა, არა კარიბჭე.

### 11.4 შემოწმებული და უცვლელი

- `ManagerScope` / `DepartmentMatcher` / `DirectMessagePermission` — უკვე
  აქვთ 6 / 11 / 9 unit ტესტი, prefix-boundary, null-fail-closed და
  `"All"` ქცევის ჩათვლით. **დამატებითი ტესტი არ დამიწერია** — დუბლირება
  იქნებოდა.
- გადამოწმდა, რომ `DirectMessagePermission.canSend(MANAGER, "All", …)`
  რეალურად wildcard-ის შტოში გადის (`DepartmentMatcher.matches` target
  `"All"`-ით) და `managerStoredAsAllKeepsItsExistingReach` სწორედ ამას
  ამტკიცებს — javadoc-ის განცხადებული `ManagerScope`-თან განსხვავება
  **რეალურია და უკვე დაფარულია** ორივე მხრიდან. შეუსაბამობა არ აღმოჩნდა.

### 11.5 სრული DB-free suite

```
mvn -B test -DexcludedGroups=oracle
Tests run: 261, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (9.245 s)
Finished at: 2026-08-27T06:12:11Z
```

257 (baseline) → 259 (§6.1) → **261** (§11.1). არსებული არცერთი ტესტი
არ დაზიანებულა.

---

## 12. WS2 P0 ციკლი 3 — `PermissionEnforcementCoverageTest`-ის გაძლიერება

არსებული ტესტი (3 ტესტი) ამოწმებდა, რომ catalog-ის ყოველი permission
**გამოძახებულია** სადმე `hasPermission(...)`-ით (SEC-06-ის დაცვა).
`PermissionChecker`-ის javadoc კი პირდაპირ ამბობს, რას **ვერ** იჭერს ის:

> „…catches a permission nobody checks; it cannot catch one that is checked
> but **can never be false**."

ეს ხვრელი დაიხურა. **3 → 8 ტესტი.**

### 12.1 რატომ შეიძლება permission იყოს „შემოწმებული, მაგრამ მკვდარი"

`PermissionChecker:53-56` SYSTEM_ADMIN-ს უპირობოდ `true`-ს უბრუნებს,
ჯერ არაფრის წაკითხვამდე. აქედან **ორი** სხვადასხვა სიკვდილის ფორმა:

| ფორმა | სად ჩანს | ტესტი |
|---|---|---|
| **A.** ყველა non-admin როლს **აქვს** default-ად → არავის უარი ეთქმის | `Permission.DEFAULTS_BY_ROLE`-ში | `everyPermissionCanActuallyRefuseSomebody` |
| **B.** მოწმდება **მხოლოდ** იქ, სადაც SYSTEM_ADMIN როლი ისედაც სავალდებულოა → შემოწმება მუშაობს მხოლოდ იმ როლისთვის, რომელიც მას გვერდს უვლის | call site-ებში, **არა** default-ებში | `noPermissionIsConsultedOnlyBehindTheSystemAdminBypass` |

**`users.manage`-ს B ფორმა ჰქონდა**, არა A: OPERATOR-ს ის არ ჰქონდა
(default-ები ცარიელია), მაგრამ ყველა user-administration endpoint
`requireSystemAdmin`-ის უკან იდგა, ამიტომ შემოწმებამდე მხოლოდ
SYSTEM_ADMIN აღწევდა — და ის bypass-ს იყენებდა.

> ⚠️ პირველი ვერსია ამ ორ ფორმას ერთმანეთში ურევდა და javadoc აცხადებდა,
> რომ A ფორმის ტესტი `users.manage`-ს დაიჭერდა. **არ დაიჭერდა.**
> javadoc გასწორდა და B ფორმისთვის ცალკე, bytecode-ზე დაფუძნებული ტესტი
> დაიწერა.

### 12.2 დამატებული ტესტები

| ტესტი | რას იცავს |
|---|---|
| `everyPermissionCanActuallyRefuseSomebody` | ფორმა A. შეფასდება `Permission.defaultsFor`-ის მიხედვით — ე.ი. იმის მიხედვით, რას იღებს **რეალური** ანგარიში შექმნისას/როლის ცვლილებისას (`UserController:213`, `:450`, `AuthenticationService:114`), და არა ხელით აწყობილი `User` ობიექტის მიხედვით |
| `noPermissionIsConsultedOnlyBehindTheSystemAdminBypass` | ფორმა B — **`users.manage`-ის ნამდვილი ფორმა**. თითოეული permission-ისთვის პოულობს endpoint-ებს, რომელთა call closure **ასახელებს** კონსტანტას *და* აღწევს `hasPermission`-ს; შემდეგ ამოწმებს, ყველა მათგანი იძახებს თუ არა `requireSystemAdmin`-ს |
| `theRefusabilityCheckWouldActuallyFailForAnAdminOnlyPermission` | negative control ფორმა A-სთვის |
| `everyRoleHasADefaultPermissionSet` | `DEFAULTS_BY_ROLE` არის `Map.of` 4 როლზე, შედეგი კი **დაუყოვნებლივ** dereference-დება სამივე call site-ზე (ორი `for`-each, ერთი `.stream()`). მე-5 როლი NPE-ს გამოიწვევდა user creation-ში, role reassignment-სა და JIT provisioning-ში — runtime-ზე, პირველივე ანგარიშზე |
| `systemAdminDefaultsCoverTheWholeCatalog` | ხდის `PermissionChecker`-ის javadoc-ში აღწერილ **ღია გადაწყვეტილებას** (bypass-ის მოხსნა) მოგვიანებით უსაფრთხოდ ასაღებს. დღეს SYSTEM_ADMIN-ის default set საერთოდ არ იკითხება — bypass ჯერ პასუხობს — სწორედ ამიტომ შეუძლია მას შეუმჩნევლად გადაიხაროს. bypass-ის მოხსნის დღე იქნება ის დღე, როცა ეს გადახრა ადმინების უფლებების უხმო დაკარგვად იქცევა |

### 12.3 არსებული ტესტის გამაგრება — per-file scanning

`allProductionSource()` ყველა `.java`-ს **ერთ blob-ად** აერთებდა, ხოლო
`enforcementOf`-ის `[^;]*?` DOTALL-ით შეიძლებოდა ფაილის საზღვარი გადაეკვეთა:
ერთი ფაილის ბოლოს დარჩენილი `hasPermission(user,` და მეორის დასაწყისში
მდგარი `Permission.SYSTEM_AUDIT` ერთად წაიკითხებოდა როგორც enforcement,
რომელიც **არცერთში არ არსებობს**.

ეს არის false positive **სწორედ სახიფათო მიმართულებით** — ტესტი იტყოდა
„permission დაცულია", როცა არ არის. `productionSourceFiles()` ახლა
თითოეულ ფაილს ცალკე ამოწმებს; შესაძლებლობა მოხსნილია და არა განხილული.

### 12.4 `ControllerBytecode` — გაზიარებული bytecode reader

B ფორმის ტესტს method body-ს კითხვა სჭირდება. ASM visitor-ის მეორედ
დაწერის ნაცვლად, `EndpointGuardCoverageTest`-ის მანქანერია გატანილია
`ControllerBytecode`-ში (test scope) და გაფართოებულია `visitFieldInsn`-ით —
enum კონსტანტა static ველია, ასე იკითხება „რომელ `Permission`-ს ასახელებს
ეს მეთოდი". `EndpointGuardCoverageTest` გადაყვანილია მასზე (4/4 მწვანე).

`ControllerEndpoints` გახდა `public` — `PermissionEnforcementCoverageTest`
ცხოვრობს `ge.magti.portal.domain`-ში (permission არის domain-ის კითხვა),
მაგრამ პასუხისთვის endpoint-ებს უნდა უყუროს.

### 12.5 Evidence

```
mvn -B test -DexcludedGroups=oracle -Dtest=PermissionEnforcementCoverageTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

**Mutation probe 1 — `Permission.java` (main source, დროებით):**
(i) `REPORTS_EXPORT` მიეცა ყველა non-admin როლს, (ii) `SYSTEM_AUDIT`
მოეხსნა SYSTEM_ADMIN-ს. სწორად ჩავარდა **ზუსტად 2 ტესტი**:

```
These permissions can never be false for anyone: [reports.export]
SYSTEM_ADMIN's default permission set is missing [system.audit]
```

**Mutation probe 2 — B ფორმის დეტექტორი:**
`SYSTEM_ADMIN_GATE` შეიცვალა `"requireAuthenticated"`-ით (gate, რომელსაც
consulting endpoint-ები **მართლაც** იძახებენ) → **6 permission** გამოცხადდა
მკვდრად:

```
[articles.edit, articles.publish, articles.archive, videos.archive,
 compliance.assign, reports.export]
```

**Mutation probe 3 — non-vacuity:** `SYSTEM_ADMIN_GATE` = არარსებული სახელი
→ ცალკე assertion-მა იმუშავა (`no endpoint anywhere was seen calling
__no_such_gate__ -- the detector cannot recognise the admin gate, so this
test would never fire whatever the code did`). ეს აუცილებელია, რადგან
ტესტი „მკვდარს" **ყველა** consulting endpoint-ის gate-ქვეშ ყოფნიდან
ასკვნის — დეტექტორი, რომელიც gate-ს ვერ ხედავს, სამუდამოდ საპირისპიროს
დაასკვნიდა, ჩუმად.

ყველა ფაილი probe-ების შემდეგ byte-identical აღდგა (`diff -q`);
`git status`-ში `src/main/java` **უცვლელია**.

### 12.6 სრული DB-free suite

```
mvn -B clean test -DexcludedGroups=oracle
Tests run: 266, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (11.637 s)
mvn -B -DskipTests package → BUILD SUCCESS, JAR 93 096 060 ბაიტი (უცვლელი)
```

257 (baseline) → 259 → 261 → **266**.

### 12.7 რაც კვლავ ღიაა

`PermissionChecker`-ის SYSTEM_ADMIN bypass **არ მოხსნილა** — ეს
დოკუმენტირებული ღია გადაწყვეტილებაა („needs a look at real data first")
და მისი შეცვლა role/permission-ის დადასტურებული გადაწყვეტილების ცვლილება
იქნებოდა. §12.2-ის ბოლო ტესტი მას მხოლოდ **უსაფრთხოდ ასაღებს ხდის**.

---

## 13. WS2 P0 ციკლი 4 — `ProductionSafetyGuardTest` მატრიცა

**12 → 17 ტესტი.** მხოლოდ ტესტი; `ProductionSafetyGuard.java` და
`PortalProperties.java` **უცვლელია**.

### 13.1 რატომ *არ* დაიწერა 16-კომბინაციიანი truth table

თავდაპირველად დავწერე 2⁴ = 16 კომბინაციის მატრიცა (devLogin × weakSecret ×
devDbPassword × insecureCookie) და **წავშალე**, რადგან გამართლება, რომელიც
მას მივუწერე, **მცდარი იყო**.

`ProductionSafetyGuard.verify()` არის ოთხი დამოუკიდებელი
`if (risk) throw` სწორხაზოვანი მიმდევრობა, სადაც თითოეული პრედიკატი
**მხოლოდ საკუთარ ღერძს** კითხულობს. ამიტომ 16-კომბინაციიანი ცხრილი
**მათემატიკურად გამომდინარეობს** უკვე არსებული ოთხი single-risk
ტესტიდან. კომენტარი, რომელიც დავწერე — „else-if ჯაჭვად გადაქცევა
single-risk ტესტებს მწვანედ დატოვებდა" — ასევე მცდარია: როცა ყველა შტო
`throw`-ს აკეთებს, `else if` სემანტიკურად იდენტურია.

ე.ი. 16 ტესტი მხოლოდ გაიმეორებდა არსებულს. წაშლილია.

**რეალური კომბინატორული ზედაპირი სხვაგან იყო** — ორი შემოწმების *შიგნით*:
`PLACEHOLDER_MARKERS`-ს **10** ჩანაწერი აქვს, `KNOWN_DEV_DB_PASSWORDS`-ს —
**5**, არსებული ტესტები კი თითოეულიდან **მხოლოდ 2**-ს ეხებოდა.

### 13.2 დამატებული ტესტები

| ტესტი | რას იცავს |
|---|---|
| `everyPlaceholderMarkerIsRejectedAsASecret` | ყველა 10 marker. სია **reflection-ით იკითხება თვით ველიდან**, არა კოპირებული — დუბლიკატი ჩუმად გადაიხრებოდა ახალი marker-ის დამატებისთანავე, და გადაიხრებოდა იმ მიმართულებით, რომ **ნაკლებს** ამოწმებდეს, ვიდრე კოდი |
| `everyKnownDevDatabasePasswordIsRejected` | ყველა 5 shipped dev პაროლი (PR-05), იმავე reflection-ით |
| `noNonProductionEnvironmentIsEverChecked` | guard-ის სკოპი: `development`, `dev`, `staging`, `test`, `local`, `""` — სრულიად გატეხილი კონფიგურაციითაც არ უნდა გაჩერდეს |
| `appEnvIsMatchedCaseInsensitivelyButIsNotTrimmed` | ⚠️ იხ. §13.3 — **დაფიქსირებული ხარვეზი**, არა დამტკიცებული ქცევა |
| `theStartupSecurityLineIsLoggedEvenWhenTheGuardRefusesToBoot` | PR-08. logback `ListAppender`-ით |

### 13.3 ⚠️ ახალი ხარვეზი — `APP_ENV` არ იჭრება (`trim`)

`PortalProperties.isProduction()` არის `"production".equalsIgnoreCase(appEnv)`
— **მხოლოდ ეს**. რეგისტრი მნიშვნელობა არ აქვს (`PRODUCTION`, `Production`
მუშაობს), მაგრამ **გარშემო ჰარისი წყვეტს, საერთოდ გაეშვება თუ არა რომელიმე
შემოწმება**.

`APP_ENV=production ` (ერთი ბოლო ჰარისით — რასაც `.env` ფაილიც და
docker compose-იც **ინარჩუნებს**) `isProduction()`-ისთვის **არ არის**
production. guard პირველივე შემოწმებამდე ბრუნდება, და deployment ჩართული
dev login-ითა და placeholder secret-ით **ჩუმად ეშვება**.

ეს არის ზუსტად ის ავარია, რომელზეც SEC-01-ია — ოღონდ დაკარგული ცვლადის
ნაცვლად შემთხვევითი სიმბოლოთი მოსული.

**არ გამისწორებია.** `trim()` ერთსტრიქონიანი ცვლილებაა, მაგრამ ცვლის
*როდის ამბობს უარს production deployment ჩატვირთვაზე* — ე.ი. დადასტურებული
security ქცევის ცვლილებაა. ტესტი მიმდინარე ქცევას **აფიქსირებს, არ
ამტკიცებს სისწორეს**, და მისი შეტყობინება პირდაპირ ამბობს: *„თუ ეს ახლა
ჩავარდა, `isProduction()` უფრო შემწყნარებელი გახდა, რაც გაუმჯობესებაა —
წაშალე ტესტის ეს ნახევარი და მასთან ერთად ეს ხარვეზიც."*

შემოთავაზებული იდენტიფიკატორი: **`DEC-P04`**.

### 13.4 Evidence — 5 mutation probe, ყველა სწორად ჩავარდა

| probe (main source, დროებით) | შედეგი |
|---|---|
| `containsPlaceholderMarker` → `equals` (ნაცვლად `contains`) | **ყველა 10 marker** დაფიქსირდა: `[change-me, change_me, changeme, super-secret-temporary-key, your-secret, replace-me, placeholder, example, todo, xxxxx]` |
| `isKnownDevDatabasePassword` → ყოველთვის `false` | ყველა 5 პაროლი დაფიქსირდა |
| `logEffectiveSecurityConfig()` გადატანილი შემოწმებების ქვემოთ (**ზუსტად PR-08-ის რეგრესია**) | `aborted the boot without logging` |
| `if (!properties.isProduction()) return;` მოხსნილი | `refused a non-production environment` |
| `isProduction()`-ს დაემატა `.trim()` | `is currently NOT production` — ე.ი. §13.3-ის ტესტი შესწორებას სწორად ამჩნევს |

`ProductionSafetyGuard.java` და `PortalProperties.java` ორივე probe-ის
შემდეგ **byte-identical** აღდგა (`diff -q`), `git status`-ში
`src/main/java` უცვლელია.

### 13.5 სრული DB-free suite

```
mvn -B clean test -DexcludedGroups=oracle
Tests run: 271, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (11.451 s)
mvn -B -DskipTests package → BUILD SUCCESS, JAR 93 096 060 ბაიტი (უცვლელი)
```

257 → 259 → 261 → 266 → **271**.

---

## 14. WS2 P0 ციკლი 5 — audit chain tamper-verdict, Oracle-ის გარეშე

**+12 ტესტი** (`AuditChainVerdictTest`). `AuditChainService.java`
**უცვლელია** — მხოლოდ ახალი ტესტი.

### 14.1 რატომ ეს აირჩა

tamper-evidence P0 უსაფრთხოების თვისებაა, და მის უკან **მხოლოდ**
`AuditChainServiceTest` იდგა, რომელიც `@RequiresOracle`-ია. ე.ი. DB-free
CI job მას **მთლიანად ტოვებდა**: verdict ლოგიკის ცვლილება ხვდებოდა ან
ნელ job-ში, ან დეველოპერის მანქანაზე, ან — არსად.

`ClientIpResolver`-ს უკვე 10 ტესტი აქვს (მარგინალური მოგება), ხოლო
`DEC-P03`/`DEC-P04` patch-ები თქვენს გადაწყვეტილებაზეა დაბლოკილი.

### 14.2 რა **არ** გაკეთდა და რატომ

`AuditChainServiceTest` **უცვლელი რჩება და მთავარია** — ის რეალური JPA
გზით წერს, V28-ის Oracle trigger-ს ახარებინებს hash-ს, შემდეგ ცვლის row-ს,
შლის predecessor-ს და აყალბებს მეორე genesis-ს. აქ არაფერი ცვლის მას
და აქაური არცერთი ტესტი ვერ დაიჭერს შეცდომას SQL-ში ან
`audit_logs_canonical_string`-ში.

**verdict ლოგიკის სუფთა ფუნქციად გამოტანა (refactor) განზრახ არ გაკეთდა.**
ეს იქნებოდა tamper-detection კოდის რედაქტირება, რომლის რეგრესიაზე
შემოწმებაც ამ გარემოში **შეუძლებელია** (Oracle მიუწვდომელია). ამის
ნაცვლად ტესტი რეალურ `AuditChainService`-ს ატარებს **stub `JdbcTemplate`-ით**
— main source ხელუხლებელია.

ფასი: ტესტი მიბმულია სამი query-ის სვეტების სახელებზე. ეს არის
შესაძლო ყველაზე პატარა მიბმა — ალტერნატივა main-ის შეცვლა იყო.

### 14.3 რა დაიფარა (ის, რასაც Oracle ტესტი ყველაზე ცუდად წვდება)

verdict-ის კიდეები კონკრეტულ chain-მდგომარეობას საჭიროებს — 11 გატეხილი
row, ერთდროულად შეცვლილი *და* გაწყვეტილი row, chain-ის შუაში დაწყებული
window. ეს არითმეტიკაა ბაზიდან მოსულ მნიშვნელობებზე:

| ტესტი | უცილობლობა |
|---|---|
| `theWindowIsClampedToAtLeastOne` / `...AtMostFiveHundred` | `n` იჭრება [1,500]-ში *query-მდე*: 0/უარყოფითი `FETCH FIRST ? ROWS ONLY`-ს **შეცდომად** აქცევდა, უსაზღვრო კი dashboard-ის mount-ს სრული ცხრილის ხელახალ ჰეშირებად |
| `anIntactChainFromGenesisIsOk` | საბაზისო |
| `aRowThatNoLongerRecomputesIsAHashMismatch` | შეცვლილი row |
| `aRowPointingAtTheWrongPredecessorIsALinkBreak` | წაშლილი/გადანაცვლებული predecessor |
| `aRowThatFailsBothWaysCountsTwiceButIsListedOnce` | ორივე მრიცხველი დამოუკიდებელია, `bad_ids` კი **სანახავი row-ების ნაკრებია** — ორჯერ გატეხილი row მაინც *ერთი* row-ია |
| `theOldestRowInTheWindowIsCheckedAgainstTheRowBeforeIt` | window-ის უძველესი row-იც რეალურ link-შემოწმებას იღებს. ამის გარეშე ზუსტად window-ის კიდეზე დაწყებული გაყალბება — **გაყალბებული genesis-ის ჩათვლით** — ერთადერთი რამ იქნებოდა, რასაც dashboard ვერ ხედავს |
| `badIdsStopAtTenWhileTheCountersDoNot` | `bad_ids` არის dashboard-ის ნიმუში, შეზღუდული 10-ით; **მრიცხველები არ იზღუდება** — ისინი პასუხობენ „რამდენად ცუდადაა", და იქ ჭერი კატასტროფულ ზიანს 10 row-ად აჩვენებდა |
| `anEmptyWindowIsOkAndNeverLooksForAPredecessor` | ცარიელ window-ზე boundary query ზედმეტი round trip იქნებოდა ყოველ mount-ზე |
| `rowsPredatingTheChainAreCountedButDoNotMakeItTampered` | V28-მდელი row-ები **ზიანი არ არის**. მათი tampering-ად ჩათვლა dashboard-ს წითლად აანთებდა ყოველ deployment-ზე, რომელსაც ისტორია მოჰყვება — მუდმივად წითელ ინდიკატორს კი აღარავინ კითხულობს |
| `verifyReportsUnchainedForARowWithNoHash` | არც ok, არც tampered — შესადარებელი არაფერია; „tampered" deployment-ს იმ ზიანში დაადანაშაულებდა, რომელიც არ ჩაუდენია |
| `verifyIsTamperedWhenEitherTheHashOrTheLinkFails` | ორივე ცალკე ჩავარდნა საკმარისია |

### 14.4 Evidence — 6 mutation probe, თითოეულმა **ზუსტად** სამიზნე ტესტი ჩააგდო

| probe (`AuditChainService.java`, დროებით) | ჩავარდა |
|---|---|
| clamp მოხსნილი | `theWindowIsClampedToAtLeastOne`, `...AtMostFiveHundred` |
| `bad_ids` ჭერი 10 → 100 | `badIdsStopAtTenWhileTheCountersDoNot` |
| boundary predecessor აღარ იძებნება | `theOldestRowInTheWindowIsCheckedAgainstTheRowBeforeIt` |
| link შემოწმება → `else if` | `aRowThatFailsBothWaysCountsTwiceButIsListedOnce` |
| pre-V28 row-ები status-ს „tampered"-ად აქცევს | `rowsPredatingTheChainAreCountedButDoNotMakeItTampered` |
| `verify()` აღარ აბრუნებს `unchained`-ს | `verifyReportsUnchainedForARowWithNoHash` |

`AuditChainService.java` ყველა probe-ის შემდეგ **byte-identical** აღდგა;
`git status`-ში `src/main/java` უცვლელია.

### 14.5 სრული DB-free suite

```
mvn -B clean test -DexcludedGroups=oracle
Tests run: 283, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (11.224 s)
mvn -B -DskipTests package → BUILD SUCCESS, JAR 93 096 060 ბაიტი (უცვლელი)
```

257 → 259 → 261 → 266 → 271 → **283**.

### 14.6 რაც კვლავ Oracle-ს საჭიროებს

hash-ის **გამოთვლა** (V28 trigger + `audit_logs_canonical_string` +
`STANDARD_HASH`) და სამივე query-ის SQL. ეს `AuditChainServiceTest`-ის
საქმეა და ამ გარემოში ვერ გაეშვება — **`External`, უცვლელი**.

---

## 9. ამ სესიაში შეცვლილი ფაილები (არ არის commit-ული)

**ციკლი 1** (commit `1656b96`, push-ული):

| ფაილი | ტიპი |
|---|---|
| `docs/agents/EV-REMOTE-2026-08-27_JAVA_REGRESSION_KA.md` | ახალი |
| `java-backend/src/test/java/ge/magti/portal/web/EndpointPrincipalCoverageTest.java` | ახალი |

**ციკლი 2** (§11):

| ფაილი | ტიპი |
|---|---|
| `java-backend/src/test/java/ge/magti/portal/web/EndpointGuardCoverageTest.java` | ახალი |
| `java-backend/src/test/java/ge/magti/portal/web/ControllerEndpoints.java` | ახალი |
| `java-backend/src/test/java/ge/magti/portal/web/EndpointPrincipalCoverageTest.java` | შეცვლილი (scanner გატანილი) |
| `docs/agents/EV-REMOTE-2026-08-27_JAVA_REGRESSION_KA.md` | შეცვლილი (§11) |

**ციკლი 3** (§12):

| ფაილი | ტიპი |
|---|---|
| `java-backend/src/test/java/ge/magti/portal/domain/PermissionEnforcementCoverageTest.java` | შეცვლილი (3 → 8 ტესტი) |
| `java-backend/src/test/java/ge/magti/portal/web/ControllerBytecode.java` | ახალი |
| `java-backend/src/test/java/ge/magti/portal/web/ControllerEndpoints.java` | შეცვლილი (`public`) |
| `java-backend/src/test/java/ge/magti/portal/web/EndpointGuardCoverageTest.java` | შეცვლილი (reader გატანილი) |
| `docs/agents/EV-REMOTE-2026-08-27_JAVA_REGRESSION_KA.md` | შეცვლილი (§12) |

**ციკლი 4** (§13):

| ფაილი | ტიპი |
|---|---|
| `java-backend/src/test/java/ge/magti/portal/config/ProductionSafetyGuardTest.java` | შეცვლილი (12 → 17 ტესტი) |
| `docs/agents/EV-REMOTE-2026-08-27_JAVA_REGRESSION_KA.md` | შეცვლილი (§13) |

**ციკლი 5** (§14):

| ფაილი | ტიპი |
|---|---|
| `java-backend/src/test/java/ge/magti/portal/audit/AuditChainVerdictTest.java` | ახალი (12 ტესტი) |
| `docs/agents/EV-REMOTE-2026-08-27_JAVA_REGRESSION_KA.md` | შეცვლილი (§14) |

`main` კოდში (`src/main/java`) **არაფერი შეცვლილა** — ორივე ციკლი მხოლოდ
ტესტს და დოკუმენტაციას ეხება. PR **არ შექმნილა**.

⚠️ ეს გარემო **ephemeral container**-ია — შენახვა მხოლოდ push-ის შემდეგაა
გარანტირებული.

---

## 10. საჭირო გადაწყვეტილებები

| ID | საკითხი | სტატუსი |
|---|---|---|
| `DEC-P01` | `/uploads/{filename}` file entitlement | **ღია** — არ შევეხე; ახლა ჩამაგრებულია `EndpointPrincipalCoverageTest.PUBLIC_BY_DESIGN`-ში |
| `DEC-P02` | logout contract (`users.token_version`, „log out everywhere") | **ღია** — არ შევეხე; ამ სესიაში ვერ წავიკითხე (დოკუმენტი არ არსებობს) |
| ~~`DEC-P03`~~ | `export_jobs`-ს არ ჰქონდა owner სვეტი | ✅ **გასწორებულია** — იხ. §18 |
| ~~`DEC-P04`~~ | `PortalProperties.isProduction()` არ იჭრებოდა | ✅ **გასწორებულია** — იხ. §16 |
| ~~`DEC-P05`~~ | `APP_ENV=prod` და typo-ები production არ იყო | ✅ **გასწორებულია** — იხ. §17 |
| — | დოკუმენტების შეუსაბამობა: 7 enterprise-readiness ფაილი არ არსებობს ამ repository-ში (§0) | **მომხმარებლის გადასაწყვეტი** — უნდა დაი-commit-დეს თუ არა Windows workspace-იდან |

---

## 15. გაჩერების წერტილი — 2026-08-27

**Verdict: NOT READY.** სესია შეჩერდა განზრახ: დარჩენილი ნამდვილი P0
სამუშაო **Oracle-ს საჭიროებს**, რაც ამ გარემოში დაბლოკილია (§5), ხოლო
დამატებითი DB-free ტესტი უკვე კლებადი უკუგებაა.

### 15.1 Repository-ის მდგომარეობა

| | |
|---|---|
| branch | `claude/enterprise-readiness-verify-lq1eto` |
| HEAD | `28c51539c216abb190f41dc5aa1a24cfa03d4216` |
| `origin/<branch>` | იდენტური — ყველაფერი push-ულია |
| working tree | სუფთა (porcelain 0) |
| commit-ები `main`-ის ზემოთ | 5 |
| `src/main/java` | **არცერთხელ არ შეცვლილა** |

```
28c5153 test(audit): check the tamper verdict without a database
92a9f4f test(config): cover every placeholder marker and dev password, not two of each
fc0ce57 test(security): catch a permission that is checked but can never be false
120054a test(security): assert every endpoint actually calls a guard, not just receives the caller
1656b96 test(security): fail the build when an endpoint cannot authorize anyone
```

### 15.2 რა შეიცვალა ჯამში

DB-free suite: **257 → 283** (+26). ხუთი ახალი build gate:

| gate | ფაილი | რას კეტავს |
|---|---|---|
| endpoint principal | `EndpointPrincipalCoverageTest` | endpoint, რომელსაც გამომძახებელი არ გადაეცემა |
| endpoint guard | `EndpointGuardCoverageTest` | endpoint, რომელიც principal-ს იღებს და **არ უყურებს** |
| permission liveness | `PermissionEnforcementCoverageTest` | permission, რომელიც შემოწმებულია, მაგრამ **ვერასდროს იქნება false** (ორივე ფორმა) |
| production safety | `ProductionSafetyGuardTest` | placeholder/dev-password სიის ჩანაწერი, რომელიც არაფერს უარყოფს |
| audit tamper verdict | `AuditChainVerdictTest` | chain-health-ის არითმეტიკა Oracle-ის გარეშე |

ყველა gate mutation probe-ით დადასტურებულია, რომ **ცარიელი არ არის**.

### 15.3 რა არის დაბლოკილი და რაზე

| დაბლოკილი სამუშაო | ბლოკერი |
|---|---|
| სრული Java/Oracle regression (257 ტესტი) | `External` — Oracle მიუწვდომელია (§5) |
| P0-A18 Video/Access 20/20 ხელახალი დადასტურება | `External` — იგივე |
| 30 endpoint-ის negative/IDOR ტესტი (§11.3) | `External` — ყველა negative ტესტი `@RequiresOracle`-ია |
| EV-229-თან შედარება, `DEC-P01`/`DEC-P02` სტატუსი | დოკუმენტები ამ repo-ში არ არსებობს (§0) |
| `DEC-P03` fix (export ownership + `V36`) | გადაწყვეტილება |
| `DEC-P04` fix (`APP_ENV` trim) | გადაწყვეტილება |

### 15.4 შემდეგი ნაბიჯები, პრიორიტეტით

1. **`DEC-P03`** — `export_jobs`-ს owner სვეტი არ აქვს, `GET /api/export/download/{jobId}` მფლობელობას არ ამოწმებს და negative ტესტიც არ აქვს (§6, §11.3). ორმაგად ღიაა.
2. **`DEC-P04`** — `APP_ENV=production ` (ბოლო ჰარისით) **ყველა production შემოწმებას გამორთავს** (§13.3).
3. **Oracle-იანი გარემო** — მის გარეშე verdict ვერ შეიცვლება. საჭიროა ან
   `gvenzl/oracle-xe`-ზე წვდომა (აქ egress policy კრძალავს), ან
   Testcontainers-ის დამატება `pom.xml`-ში, ან CI-ის Oracle job-ის გაშვება.
4. **7 enterprise-readiness დოკუმენტის commit** Windows workspace-იდან —
   მათ გარეშე ყოველი ახალი აგენტი ამ §0-ის შეუსაბამობით იწყებს.
5. დარჩენილი DB-free კანდიდატი: `ClientIpResolver` / `TRUSTED_PROXIES`
   CIDR და spoofed `X-Forwarded-For` კიდურა შემთხვევები (მოკრძალებული).

### 15.5 რა **არ** შეიცვალა (განზრახ)

`PermissionChecker`-ის SYSTEM_ADMIN bypass · `/uploads/{filename}`-ის
საჯაროობა (`DEC-P01`) · logout-ის null-tolerant კონტრაქტი (`DEC-P02`) ·
export flow · retention · `isProduction()`-ის სემანტიკა (`DEC-P04`) ·
`AuditChainService`-ის ლოგიკა · ნებისმიერი role/scope წესი.

---

## 16. `DEC-P04` — გასწორებულია

**პირველი main-source ცვლილება ამ სესიაში.** მომხმარებლის პირდაპირი
გადაწყვეტილებით.

### 16.1 ხარვეზი უფრო მძიმე აღმოჩნდა, ვიდრე §13.3-ში ჩავწერე

`isProduction()`-ს **ორი** გამომძახებელი აქვს, არა ერთი:

1. `ProductionSafetyGuard:76` — `if (!properties.isProduction()) return;`
2. **`AuthenticationService:80`** — `boolean isTestAccount = !properties.isProduction() && ...`
   — ეს არის **უპაროლო dev login-ის კარიბჭე**.

ე.ი. `APP_ENV=production ` (ერთი ბოლო ჰარისი):

- guard **ყველა** შემოწმებამდე ბრუნდებოდა → `allow-dev-login=true`
  ჩატვირთვისას აღარ იკრძალებოდა; **და**
- dev login-ის გზა ხდებოდა **მისაწვდომი**.

SEC-01-ის (Critical) **ორივე** დამცავი ერთი უხილავი სიმბოლოთი ეცემოდა.
§13.3-ში მხოლოდ პირველი მქონდა აღწერილი.

### 16.2 გასწორება

`PortalProperties.isProduction()`:

```java
// იყო
return "production".equalsIgnoreCase(appEnv);

// გახდა
return appEnv == null || appEnv.isBlank() || "production".equalsIgnoreCase(appEnv.strip());
```

`strip()` და არა `trim()` — Unicode-ს ითვალისწინებს (non-breaking space და
მისთანები), იგივე, რასაც `DepartmentMatcher` იყენებს.

**null/blank → production.** იმავე მიზეზით, რითაც ველი production-ზეა
დაყენებული: `APP_ENV=` არის ცვლადი, რომლის დაყენებაც სურდათ და არ
დააყენეს. დეველოპერი, რომელიც აქ მოხვდება, იღებს **ხმამაღალ უარს**
APP_ENV-ის დასახელებით, და არა ჩუმ production ჩატვირთვას.

### 16.3 რა ტესტები დაემატა/შეიცვალა

| ტესტი | კლასი |
|---|---|
| `appEnvIgnoresSurroundingWhitespaceAndCasing` | `ProductionSafetyGuardTest` (ჩაანაცვლა ხარვეზის დამფიქსირებელი ძველი ტესტი) |
| `anUnsetOrBlankAppEnvIsProduction` | `ProductionSafetyGuardTest` |
| `appEnvStillHasToBeTheWordProduction` | `ProductionSafetyGuardTest` — ზღუდავს გასწორების არეალს |
| `aProductionAppEnvWithStrayWhitespaceIsStillChecked` | `ProductionSafetyGuardTest` — **guard-ის მხრიდან** |
| `whitespaceAroundAProductionAppEnvDoesNotReEnableTheBypass` | `AuthenticationServiceTest` — **bypass-ის მხრიდან** |
| `aBlankAppEnvDoesNotEnableTheBypass` | `AuthenticationServiceTest` |

`noNonProductionEnvironmentIsEverChecked`-ის სიიდან `""` მოიხსნა —
ის ახლა production-ია.

⚠️ `isProduction()`-ის მარტო შემოწმება **არასაკმარისი იქნებოდა**:
ხვრელი ორ გამომძახებელშია, ამიტომ ორივე მხარე ცალკე იტესტება.

### 16.4 Evidence — გასწორების უკუქცევა (probe)

`isProduction()` დროებით დაბრუნდა გასწორებამდელ ვერსიაზე →
**5 ტესტი ჩავარდა**, მათ შორის ექსპლოიტი პირდაპირი ტექსტით:

```
AuthenticationServiceTest.whitespaceAroundAProductionAppEnvDoesNotReEnableTheBypass:123
    APP_ENV=[ production] handed out a password-less admin login
AuthenticationServiceTest.aBlankAppEnvDoesNotEnableTheBypass:137
    APP_ENV=[] handed out a password-less admin login
ProductionSafetyGuardTest.aProductionAppEnvWithStrayWhitespaceIsStillChecked:278
    APP_ENV=[ production] booted with the dev login on and a placeholder secret
ProductionSafetyGuardTest.appEnvIgnoresSurroundingWhitespaceAndCasing:260
ProductionSafetyGuardTest.anUnsetOrBlankAppEnvIsProduction:304
```

`PortalProperties.java` byte-identical აღდგა გასწორებულ ვერსიაზე.

```
mvn -B clean test -DexcludedGroups=oracle
Tests run: 288, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (11.193 s)
mvn -B -DskipTests package → BUILD SUCCESS, JAR 93 096 140 ბაიტი
```

283 → **288**.

### 16.5 ⚠️ დარჩენილი ღიობი — `DEC-P05`

გასწორება **ჰარისებს** აშორებს, **სიტყვას არ ცვლის**.
`APP_ENV=prod` რეალურ deployment-ზე კვლავ **ჩუმად გამორთავს ყველა
შემოწმებას და dev login-ს მისაწვდომს ხდის** — ზუსტად იგივე fail-open.

`PortalProperties`-ის javadoc თავად ასახელებს `APP_ENV=prod`-ს SEC-01-ის
პრობლემის ნაწილად, მაგრამ „რომელი alias-ები ჩაითვალოს" **სიის არჩევაა**,
არა ჰარისის მოცილება — სხვა ტიპის გადაწყვეტილება. განზრახ არ გაფართოვდა.
`appEnvStillHasToBeTheWordProduction` ამ საზღვარს **ტესტით ამაგრებს**:
თუ ვინმე მატჩს გააფართოებს, ეს ტესტი ჩავარდება და აიძულებს, რომ ეს
შეგნებული ცვლილება იყოს.

### 16.6 Python-თან პარიტეტი — შეგნებული განსხვავება

`config.py:41` — `os.getenv("APP_ENV", "development").lower()`,
`config.py:111` — `self.APP_ENV == "production"`.

ე.ი. Python-საც **აქვს იგივე whitespace ხარვეზი**, და მისი default
`development`-ია (fail-open). Java-ს პორტი უკვე შეგნებულად განსხვავდება
აქ (default `production`, SEC-01) — ეს გასწორება იმავე მიმართულებით
აგრძელებს. Python-ის მხარე **არ შემეხო**.

---

## 17. `DEC-P05` — გასწორებულია (ინვერსიით, არა alias-ების სიით)

### 17.1 რატომ **არ** გაკეთდა alias-ების სია

§16.5-ში საკითხი ასე ჩავწერე: „რომელი alias-ები ჩაითვალოს production-ად".
გამოსავალი კი **საპირისპიროა**.

production-ის სპელინგების სია (`production`, `prod`, `prd`, `live`, …)
**ვერასდროს დასრულდება**, და ყოველი მასში არარსებული სახელი
**fail-open**-ია. `APP_ENV=produciton` (ბეჭდვითი შეცდომა) იმავე კლასისაა,
რაც `APP_ENV=prod` — და production-allowlist-ის ქვეშ **ორივე** ჩუმად
გამორთავს ყველა შემოწმებას. ე.ი. alias-ების სია ხურავს იმას, რაც
ვინმემ მოასწრო მოეფიქრებინა, და ღიად ტოვებს ყველა typo-ს — **ეს არის
ხარვეზის ფორმა, არა მისი გამოსწორება**.

### 17.2 გასწორება — development-ის სია, ყველა დანარჩენი production

```java
private static final Set<String> DEVELOPMENT_ENVIRONMENTS =
        Set.of("development", "dev", "local", "test");

public boolean isProduction() {
    if (appEnv == null) {
        return true;
    }
    return !DEVELOPMENT_ENVIRONMENTS.contains(appEnv.strip().toLowerCase(Locale.ROOT));
}
```

ახლა **ერთადერთი გზა უსაფრთხოების გამორთვისკენ არის development გარემოს
სახელით დასახელება, სწორად დაწერილი**. ჰარისები და რეგისტრი პატიებულია,
რადგან ისინი არასდროსაა განზრახვა; თვით სიტყვა — არა, რადგან ის ყოველთვისაა.

ეს არის იმავე წესის დასრულება, რომელსაც ველის default-ი (`production`)
და `DEC-P04`-ის blank-ის დამუშავება უკვე მისდევდნენ: **დაუცველი რეჟიმი
ის არის, რომელიც სახელით უნდა მოითხოვო.**

### 17.3 ⚠️ ქცევის ცვლილება — `staging`, `qa`, `uat` ახლა production-ია

სიაში **მხოლოდ ლოკალური მანქანის სახელებია**. `staging`, `qa`, `uat`,
`sandbox`, `preprod` არის **განთავსებული, საზიარო** გარემოები, რომლებზეც
სხვა ადამიანებსაც მიუწვდებათ ხელი — ამიტომ guard მათზეც ისევე მოქმედებს,
როგორც production-ზე.

ეს არის ცვლილება ძველ ქცევასთან, სადაც `"production"`-ის გარდა **ყველა**
სტრიქონი ყველა შემოწმებას გვერდს უვლიდა.

**რეპოზე გავლენა: არანაირი.** გადამოწმებულია — მთელ repo-ში მხოლოდ ორი
მნიშვნელობა გამოიყენება:

| სად | მნიშვნელობა |
|---|---|
| `.github/workflows/ci.yml:253` | `development` ✅ |
| `src/test/resources/application.properties:26` | `development` ✅ |
| `java-backend/.env.example:27` | `development` ✅ |
| `java-backend/Dockerfile:40` | `production` ✅ |

ავარიის რეჟიმი არასწორი მნიშვნელობისას: guard **უარს ამბობს ჩატვირთვაზე
და ასახელებს ცვლადს** — ხმამაღალი და გამოსასწორებელი, და არა ჩუმი
production ჩატვირთვა ჩართული dev login-ით.

### 17.4 ტესტები

| ტესტი | კლასი |
|---|---|
| `anythingThatIsNotAKnownDevelopmentEnvironmentIsProduction` | `ProductionSafetyGuardTest` — `prod`, `prd`, `live`, `produciton`, `prodcution`, `developement`, `staging`, `qa`, `uat`, `sandbox`, `preprod`, `anything-at-all` |
| `theKnownDevelopmentEnvironmentsAreStillReachable` | `ProductionSafetyGuardTest` — დაუცველი რეჟიმი კვლავ მისაწვდომია სახელით (`DEVELOPMENT`, `" dev "`, `"\tLocal\n"`) |
| `anUnrecognisedAppEnvDoesNotEnableTheBypass` | `AuthenticationServiceTest` — **bypass-ის მხრიდან** |

`noNonProductionEnvironmentIsEverChecked`-ის სიიდან `staging` მოიხსნა
(ახლა production-ია). `appEnvStillHasToBeTheWordProduction` — `DEC-P04`-ის
საზღვრის ტესტი — ჩანაცვლდა: ის განზრახ ზღუდავდა გასწორებას ჰარისებით,
რაც `DEC-P05`-მა გააუქმა.

### 17.5 Evidence — გასწორების უკუქცევა

`isProduction()` დროებით დაბრუნდა `DEC-P04`-ის (production-allowlist)
ფორმაზე → **2 ტესტი ჩავარდა**, ერთი ექსპლოიტს პირდაპირ ამბობს:

```
AuthenticationServiceTest.anUnrecognisedAppEnvDoesNotEnableTheBypass:151
    APP_ENV=[prod] handed out a password-less admin login
ProductionSafetyGuardTest.anythingThatIsNotAKnownDevelopmentEnvironmentIsProduction:337
```

`PortalProperties.java` byte-identical აღდგა.

```
mvn -B clean test -DexcludedGroups=oracle
Tests run: 290, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (10.928 s)
mvn -B -DskipTests package → BUILD SUCCESS, JAR 93 096 357 ბაიტი
```

288 → **290**.

### 17.6 Python-თან პარიტეტი — განსხვავება გაიზარდა

`config.py:41` კვლავ `os.getenv("APP_ENV", "development").lower()` და
`:111` კვლავ `== "production"` — ე.ი. Python-ს **ორივე** ხარვეზი აქვს
(`DEC-P04` და `DEC-P05`) და default-იც fail-open. Java-ს პორტი აქ უკვე
შეგნებულად განსხვავდებოდა (SEC-01); ეს გასწორება განსხვავებას აღრმავებს.
**Python-ის მხარეს არ შევხებივარ** — ის ცალკე გადაწყვეტილებაა.

---

## 18. `DEC-P03` — გასწორებულია (`V36` + ownership-scoped query)

ამ სესიის **პირველი სქემის ცვლილება**. მომხმარებლის პირდაპირი
გადაწყვეტილებით.

### 18.1 ცვლილებები

| ფაილი | რა |
|---|---|
| `V36__export_job_owner.sql` | **ახალი** — `export_jobs.created_by NUMBER` + FK `users(id)` `ON DELETE SET NULL` |
| `ExportJob.java` | `createdBy` ველი |
| `ExportJobRepository.java` | `findByIdAndCreatedBy(String, Long)` |
| `ExportController.java` | `enqueueJob` აჭედებს მთხოვნელს; `status`/`download` scoped query-ს იყენებს |

### 18.2 სამი გადაწყვეტილება, რომელიც კოდში ჩანს

**1. ownership არის *query*-ში, არა შემდგომ შემოწმებაში.**
`findByIdAndCreatedBy` და არა `findById` + `if`. სხვისი job ბრუნდება
ცარიელი — ე.ი. **განურჩეველია არარსებული id-სგან**, და endpoint პასუხობს
იმავეს, რასაც უცნობ id-ზე. ცალკე „not yours" პასუხი დაუდასტურებდა job-ის
არსებობას იმას, ვინც შეიძლება მხოლოდ ეჭვობდეს.

**2. pre-`V36` row (created_by NULL) არავის ეკუთვნის.**
scoped query მას ვერასდროს იჭერს. TTL 1 საათია
(`ExportJobWorker.EXPORT_JOB_TTL_SECONDS`), ე.ი. მთელი legacy პოპულაცია
deploy-იდან ერთ საათში ქრება. მათი მომსახურება ყველასთვის, ვისაც
permission აქვს, ხვრელს ზუსტად ამ ფანჯრისთვის ღიად დატოვებდა.
მომხმარებელი იღებს უკვე არსებულ „ვადა გავიდა — თავიდან შექმენით".

**3. SYSTEM_ADMIN-ის გამონაკლისი — არა.**
`ExportQueryService` admin-ს არ scope-ავს, ე.ი. მისი **საკუთარი** export
ისედაც სხვისის ზედსიმრავლეა და სხვისი job არასდროს სჭირდება. დაშვება კი
გატეხავდა ზუსტად იმას, რაც SEC-02-მა დაამატა: audit row, რომელიც ამბობს
ვისი პერსონალური მონაცემები გავიდა **და ვინ წაიღო**.

### 18.3 ტესტები

**DB-free** (`ExportJobOwnershipTest`, 6 ტესტი — გაშვებული და მწვანე):
მფლობელი ტვირთავს; სხვა manager იღებს **byte-for-byte იმავე პასუხს**, რასაც
უცნობი id; status-იც scoped-ია; ownerless row არავისია; admin გამონაკლისი
არაა; და — სტრუქტურული ნახევარი — `findById` **არასდროს** გამოიძახება.

**Oracle-gated** (`ExportControllerIntegrationTest`, +2): ორი manager ორ
სხვადასხვა დეპარტამენტში, სვეტის/mapping-ის/query-ის თანხმობა, და
ownerless row. ⚠️ **ვერ გავუშვი** — იხ. §18.5.

`ExportControllerDownloadTest` (არსებული, BL-09-ის ოთხი პასუხი) განახლდა:
მისი `exporter()`-ს ახლა `id` სჭირდება, რადგან ძებნა scoped გახდა.

### 18.4 Evidence — probe-მა ჩემივე ტესტის სისუსტე გამოააშკარავა

პირველი probe (fix-ის უკუქცევა) მხოლოდ **5/6** ჩააგდო.
`anotherManagerGetsByteForByteTheAnswerAnUnknownIdGets` **გაიარა — არასწორი
მიზეზით**: mock-ი მხოლოდ `findByIdAndCreatedBy`-ს ჰქონდა დაყენებული,
ამიტომ უკუქცეული controller-ის `findById` ცარიელს აბრუნებდა და „სხვისი
job არ არსებობს" ხდებოდა ვაკუუმური.

გასწორდა: mock ახლა **row-ს არსებულად ხატავს** (`findById` აბრუნებს
მფლობელის job-ს, scoped query — ცარიელს), რაც არის რეალური ბაზის
მდგომარეობა.

განმეორებითი probe — **6/6 ჩავარდა**, ექსპლოიტი პირდაპირი ტექსტით:

```
anotherManagerGetsByteForByteTheAnswerAnUnknownIdGets:156  expected: <410 GONE> but was: <200 OK>
statusIsScopedToTheCallerToo:170                           expected: <404 NOT_FOUND> but was: <200 OK>
notEvenASystemAdminOpensSomebodyElsesJob:225               expected: <410 GONE> but was: <200 OK>
aJobFromBeforeTheOwnerColumnBelongsToNobody:187            expected: <410 GONE> but was: <200 OK>
```

`ExportController.java` byte-identical აღდგა.

```
mvn -B clean test -DexcludedGroups=oracle
Tests run: 296, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (10.951 s)
mvn -B -DskipTests package → BUILD SUCCESS, JAR 93 097 889 ბაიტი
```

290 → **296**.

### 18.5 ⚠️ რაც **ვერ** გადამოწმდა

`V36` **არ გაშვებულა** — ამ გარემოში Oracle არ არსებობს. ე.ი.
გადაუმოწმებელია: migration-ის SQL, FK-ის შექმნა, JPA mapping-ის შესაბამისობა
(`ddl-auto=validate` boot-ზე გაასწორებდა, თუ არ ემთხვევა), და ორივე ახალი
integration ტესტი.

რისკის შესამცირებლად migration **ორ განცხადებად დაიწერა**
(`ALTER TABLE ... ADD (created_by NUMBER);` + ცალკე
`ADD CONSTRAINT ... FOREIGN KEY`) — Oracle კომბინირებულ ფორმასაც იღებს,
მაგრამ იქ, სადაც გაშვება შეუძლებელია, სჯობს ფორმა, რომელშიც შეცდომის
ადგილი არ არის. `CLAUDE.md`-ის წესის თანახმად Flyway migration-ს
`IF NOT EXISTS`-ის მსგავსი დაცვა **არ** სჭირდება.

**პირველი Oracle-იანი გაშვება ამ ცვლილების ვალიდაციაა.** სანამ ის არ
მოხდება, `DEC-P03` ჩაითვალოს „გასწორებული, დაუდასტურებელი".

---

## 19. Testcontainers — Oracle-ის blocker-ის მოხსნა დეველოპერის მანქანაზე

### 19.1 პრობლემა

~257 Oracle-tagged ტესტს რეალური Oracle სჭირდება. CI-ის oracle job მას
`services:`-ით აწვდის და `ORACLE_DB_URL`-ს აყენებს; ლოკალური instance-ის
მქონე დეველოპერი იმავე ცვლადს აყენებს. **ვისაც მხოლოდ Docker აქვს —
ვერაფერს უშვებდა**, ე.ი. ბაზაზე დამოკიდებული suite ჩუმად იქცევა ისეთად,
რომელსაც push-მდე არავინ უშვებს.

### 19.2 დამატებული

| ფაილი | რა |
|---|---|
| `pom.xml` | `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-oracle-xe` — **მხოლოდ `test` scope** |
| `OracleTestcontainer.java` | **ახალი** — `@TestConfiguration` + `@ServiceConnection` |
| `RequiresOracle.java` | დაემატა `@Import(OracleTestcontainer.class)` |

**ვერსია:** Spring Boot 4.1-ის parent-ი მართავს Testcontainers **2.0.5**-ს.
2.x-ში artifact id-ები `testcontainers-` პრეფიქსით არის (`oracle-xe` →
`testcontainers-oracle-xe`) — 1.x-ის სახელები 404-ს იძლევა.

**image:** `gvenzl/oracle-xe:21-slim-faststart` — **ზუსტად ის, რასაც CI
იყენებს** (`ci.yml:118`), რომ ორივე გზა ერთსა და იმავე Oracle-ს
ამოწმებდეს, და არა ორ სხვადასხვას, რომლებიც უბრალოდ ორივე „Oracle"-ს
ეძახიან.

### 19.3 ორი დიზაინის გადაწყვეტილება

**1. კონტეინერი იშვება მხოლოდ მაშინ, როცა `ORACLE_DB_URL` დაყენებული არაა.**
ე.ი. **გზას ამატებს, არ ართმევს**. კონტეინერი უფრო ნელია, ვიდრე უკვე
არსებული ბაზა, და განზრახ დაყენებული URL-ის ჩუმად გადაფარვა უარესი
იქნებოდა, ვიდრე fallback-ის არშეთავაზება.

პირობა ამოწმებს **env ცვლადს**, არა `spring.datasource.url`-ს — ამ
უკანასკნელს ყოველთვის აქვს მნიშვნელობა (`application.yml` აყენებს
`localhost:1521/orclpdb1`-ს). ეს default არის „Oracle არ არის
კონფიგურირებული", და არა კონფიგურირებული — ე.ი. მასზე დაყრდნობა
კონტეინერს ვერასდროს გაუშვებდა ზუსტად იმისთვის, ვისთვისაც ის არის.

**2. `@Import` ზის `@RequiresOracle`-ზე, არა 20 ტესტ-კლასზე.**
ერთი რედაქტირება, და ოცივე DB-ზე დამოკიდებული კლასი იღებს კონტეინერს.
`OracleTagCoverageTest` უკვე აფეილებს build-ს `@RequiresOracle`-ის გარეშე
დარჩენილი `@SpringBootTest`-ისთვის — ე.ი. **ახალი ტესტი ვერ დაემატება ისე,
რომ ჩუმად გამორჩეს**.

### 19.4 Evidence — რა დადასტურდა და რა არა

✅ **სრულად დადასტურებული — `ORACLE_DB_URL` დაყენებულია (CI-ის გზა):**

```
ORACLE_DB_URL=... mvn -B test -Dgroups=oracle -Dtest=OracleRoundTripTest
  "Pulling docker image" ......... 0
  "DockerClientFactory" .......... 0
  docker ps -a ................... ცარიელი
  ჩავარდნის მიზეზი ............... ORA-12541 / Connection refused
```

ე.ი. კონტეინერი **არ შექმნილა**, datasource `application.yml`-იდან წამოვიდა,
ქცევა **ზუსტად ისეთია, როგორიც იყო**. **CI-ს არაფერი შეხებია** — მისი
ორივე Oracle job `ORACLE_DB_URL`-ს აყენებს (`ci.yml:146`, `:238`).

⚠️ **ნაწილობრივ დადასტურებული — `ORACLE_DB_URL` არაა (ახალი გზა):**

wiring **მუშაობს ბოლომდე**, რასაც context-ის კონფიგურაცია ადასტურებს:

```
ImportsContextCustomizer key = [ge.magti.portal.OracleTestcontainer]
...ServiceConnectionContextCustomizer
Testcontainers version: 2.0.5
Connected to docker
tc.gvenzl/oracle-xe:21-slim-faststart : Pulling docker image: gvenzl/oracle-xe:21-slim-faststart
```

ე.ი. `@Import` მიაღწია ტესტ-კლასს → პირობა დაკმაყოფილდა → `@ServiceConnection`
დარეგისტრირდა → Testcontainers Docker-ს დაუკავშირდა → **CI-ის image-ს
დაუძახა**.

ჩავარდა **მხოლოდ registry-ის pull-ზე**, იმავე egress policy-ით, რაც §5-შია:

```
Can't get Docker image: RemoteDockerImage(imageName=gvenzl/oracle-xe:21-slim-faststart, ...)
failed to copy: httpReadSeeker: failed open: ... Forbidden
```

(იგივე `testcontainers/ryuk`-ზეც; `TESTCONTAINERS_RYUK_DISABLED=true`-ით
გავიარე, რომ თვით Oracle-ის pull-მდე მიმეღწია და დავრწმუნებულიყავი, რომ
ხელისშემშლელი კონკრეტულად registry-ია და არა ryuk-ის თავისებურება.)

**შესაბამისად:** კოდი ბოლომდე მუშაობს, გარემო ბლოკავს ბაიტების ჩამოტვირთვას.
პირველი გაშვება ღია registry-ის მქონე მანქანაზე არის ამის ვალიდაცია.

### 19.5 გვერდითი ეფექტები

DB-free suite: **296/296 უცვლელი**, დრო 10.9s → 12.6s (Testcontainers
classpath-ზეა, მაგრამ Oracle-tagged კლასების გარეშე უქმია — 21 დამთხვევა
ლოგში მხოლოდ Spring-ის context-customizer plumbing-ია, არა კონტეინერი).

JAR: 93 098 296 ბაიტი — ცვლილება მხოლოდ `test` scope-შია, artifact-ში
Testcontainers **არ ხვდება**.

### 19.6 რას ხსნის ეს

დეველოპერს Docker-ით ახლა შეუძლია გაუშვას:

```
mvn -B test            # სრული suite, კონტეინერი ავტომატურად
mvn -B test -Dgroups=oracle
```

`ORACLE_DB_URL`-ის დაყენების გარეშე. **მაგრამ ეს ამ გარემოს blocker-ს არ
ხსნის** — აქ registry დაბლოკილია, ე.ი. სრული regression, P0-A18 და `V36`
კვლავ **`External`**.
