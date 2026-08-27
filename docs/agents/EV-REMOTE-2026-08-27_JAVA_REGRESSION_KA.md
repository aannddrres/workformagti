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

## 9. ამ სესიაში შეცვლილი ფაილები (არ არის commit-ული)

| ფაილი | ტიპი | სტატუსი |
|---|---|---|
| `docs/agents/EV-REMOTE-2026-08-27_JAVA_REGRESSION_KA.md` | ახალი | untracked |
| `java-backend/src/test/java/ge/magti/portal/web/EndpointPrincipalCoverageTest.java` | ახალი | untracked |

`commit` / `push` / PR **არ შესრულებულა** — ცალკე ნებართვის მოლოდინში.
არსებულ არცერთ ფაილს არ შეხებია (`git status`-ში modified/deleted: 0).

⚠️ ეს გარემო **ephemeral container**-ია. თუ ცვლილებები არ დაი-commit-დება
და არ დაი-push-დება, ისინი დაიკარგება container-ის გადამუშავებისას.

---

## 10. საჭირო გადაწყვეტილებები

| ID | საკითხი | სტატუსი |
|---|---|---|
| `DEC-P01` | `/uploads/{filename}` file entitlement | **ღია** — არ შევეხე; ახლა ჩამაგრებულია `EndpointPrincipalCoverageTest.PUBLIC_BY_DESIGN`-ში |
| `DEC-P02` | logout contract (`users.token_version`, „log out everywhere") | **ღია** — არ შევეხე; ამ სესიაში ვერ წავიკითხე (დოკუმენტი არ არსებობს) |
| `DEC-P03` *(ახალი, შემოთავაზებული)* | `export_jobs`-ს არ აქვს owner სვეტი → `/api/export/download/{jobId}` და `/status/{jobId}` არ ამოწმებს მფლობელობას (§6, ღია ზედაპირი 1) | **გადაწყვეტილება საჭიროა** — გამოსწორება მოითხოვს `V36` migration-ს და ეხება დადასტურებულ export flow-ს |
| — | დოკუმენტების შეუსაბამობა: 7 enterprise-readiness ფაილი არ არსებობს ამ repository-ში (§0) | **მომხმარებლის გადასაწყვეტი** — უნდა დაი-commit-დეს თუ არა Windows workspace-იდან |
