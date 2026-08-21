# Phase 6 — content gates: role → permission

**სტატუსი:** დავალება Codex-ისთვის
**შედგენილია:** 2026-08-21
**კონტექსტი:** `docs/ORG_ACCESS_ARCHITECTURE_PLAN_KA.md` §5.8, §7.6
**კონტრაქტი:** `docs/ACCESS_CONTRACT_MATRIX_KA.md`

---

## 1. სად ვდგავართ

| ფაზა | მდგომარეობა |
|---|---|
| 0 — security hotfix | ✅ დასრულებული |
| 1 — decision/contract lock | ✅ დასრულებული |
| 2 — schema expand + backfill | ✅ **კოდი დაწერილია**, ❌ Oracle-ზე არ გაშვებულა |
| 3 — policy layer (shadow) | ✅ დასრულებული |
| **6 — content gates** | ⬅ **ეს დავალება** |

Phase 4 (leadership scope cutover) და 5 (compliance cutover) **დაბლოკილია** — იხ. §5.
Phase 6 მათზე დამოკიდებული **არ არის**: content permission-ები ჯგუფებს, scope-ს და
`team_id`-ს საერთოდ არ ეხება.

---

## 2. დავალება

25 endpoint გადადის `requireContentAdmin` **role**-gate-იდან ახალ
`content.manage` **permission**-ზე.

### 2.1 ახალი permission

`domain/Permission.java`-ს ემატება:

```java
CONTENT_MANAGE("content.manage"),
```

`DEFAULTS_BY_ROLE`-ში:

- `CONTENT_ADMIN` — ✅ იღებს
- `SYSTEM_ADMIN` — ✅ იღებს (სიის სისრულისთვის; bypass მას ისედაც ატარებს)
- `MANAGER`, `OPERATOR` — ❌ არ იღებენ

> **რატომ ერთი capability და არა ხუთი.** წესი #9 ამ უფლებებს ერთ კონად აღწერს:
> კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატიის რედაქტირება + კატეგორიების
> მართვა. `PermissionChecker`-ის javadoc უკვე აღწერს, რას უშვრება ადმინის
> წარმოდგენას ხუთი switch, რომელიც ყოველთვის ერთად ინთება.
> იხ. აგრეთვე მატრიცის **D-5**, თუ დაშლა მაინც საჭირო აღმოჩნდება.

### 2.2 გადასაყვანი endpoint-ები

ცხრა კონტროლერს **თითოეულს თავისი** `requireContentAdmin` helper აქვს. თითოეულ
მათგანში დაემატოს `requireContentManage`, `ArticleController`-ის უკვე არსებული
ნიმუშით:

```java
private ResponseEntity<Map<String, String>> requireArticlesEditPermission(User user) {
    ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
    if (authFailure != null) {
        return authFailure;
    }
    if (!permissionChecker.hasPermission(user, Permission.ARTICLES_EDIT)) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
    }
    return null;
}
```

| endpoint | handler |
|---|---|
| `POST /api/news` | `NewsController.createNews` |
| `PUT /api/news/{id}` | `NewsController.updateNews` |
| `DELETE /api/news/{id}` | `NewsController.deleteNews` |
| `PATCH /api/news/{id}/autosave` | `NewsController.autosaveNews` |
| `GET /api/news/{id}/history` | `NewsController.getNewsHistory` |
| `POST /api/news/{id}/history/{historyId}/restore` | `NewsController.restoreNewsVersion` |
| `POST /api/videos` | `VideoController.createVideo` |
| `PUT /api/videos/{id}` | `VideoController.updateVideo` |
| `DELETE /api/videos/{id}` | `VideoController.deleteVideo` |
| `POST /api/categories` | `CategoryController.createCategory` |
| `PUT /api/categories/{id}` | `CategoryController.updateCategory` |
| `DELETE /api/categories/{id}` | `CategoryController.deleteCategory` |
| `POST /api/upload` | `UploadController.uploadFile` |
| `POST /api/articles/{id}/verify` | `ArticleController.verifyArticle` |
| `GET /api/articles/{id}/history` | `ArticleController.getArticleHistory` |
| `POST /api/articles/{id}/history/{historyId}/restore` | `ArticleController.restoreArticleVersion` |
| `GET /api/admin/articles/stale` | `ArticleController.getStaleArticles` |
| `GET /api/articles/{id}/quiz/admin` | `QuizController.getArticleQuizAdmin` |
| `PUT /api/articles/{id}/quiz/admin` | `QuizController.updateArticleQuizAdmin` |
| `GET /api/statistics/activity` | `StatsController.getActivityTrend` |
| `GET /api/statistics/breakdown` | `StatsController.getStatisticsBreakdown` |
| `GET /api/statistics/compliance` | `StatsController.getComplianceStatistics` |
| `GET /api/statistics/failed-searches` | `StatsController.getFailedSearches` |
| `GET /api/statistics/kpi` | `StatsController.getKpiCounts` |
| `GET /api/statistics/popular-searches` | `StatsController.getPopularSearches` |

> `/api/statistics/*` ექვსივე მხოლოდ **აგრეგატებს** აბრუნებს, იდენტობის გარეშე
> (`breakdown` = department/role/status `COUNT`). ისინი `ORG-AGG` scope-ია და
> `content.manage`-ს ექვემდებარება — **არა** leadership scope-ს.

### 2.3 `PUT /api/users/{userId}/permissions` — delta კონტრაქტი

დღეს flat full-replace-ია (`UserController.java`, `user.setPermissions(...)`).
გადადის `INHERIT` / `ALLOW` / `DENY` delta-ზე `user_permission_overrides`
ცხრილის ბაზაზე (V36-ით უკვე შექმნილია, `domain/UserPermissionOverride`).

- `INHERIT` = **row-ის წაშლა**. ცალკე მდგომარეობად არ ინახება — რომ
  `Permission.defaultsFor`-ისგან ვერასოდეს დაშორდეს;
- `ALLOW`/`DENY` role-ს ორივე მიმართულებით სჯობს;
- optimistic concurrency — `users.lock_version` (V36-ში უკვე არის);
- **`@Version`-ის მიბმა `User`-ზე სწორედ ამ ფაზის საქმეა.** Phase 2-მა სვეტი
  განზრახ არ მიაბა: მიბმა ერთბაშად ჩართავს optimistic locking-ს ყველა `User`
  save-ზე, მათ შორის იმ გზებზე, სადაც detached principal ინახება. ეს ცალკე
  უნდა შემოწმდეს.

`CapabilityService` უკვე დაწერილია და ამ წესს ითვლის — Phase 6-ის საქმეა, რომ
იგი **გადაწყვეტდეს** და არა მხოლოდ ზომავდეს (`PermissionChecker`-ის shadow
call-ის ჩანაცვლება).

### 2.4 Angular

`user-edit-modal.ts` — permission editor ცალ-ცალკე უნდა აჩვენებდეს role-იდან
**inherited default**-სა და **explicit `ALLOW`/`DENY` override**-ს.

**ორივე დღევანდელი ხარვეზი უნდა დაიხუროს:**

1. `onEditRoleChange` აკეთებს `editPermissions.set(new Set())` — role-ის
   ცვლილებაზე override-ები ინულება;
2. `submitEdit` აგზავნის `defaults ∪ overrides` — ე.ი. **role-ის ცვლილების
   გარეშეც**, ჩვეულებრივი save role default-ებს explicit override-ებად წერს
   ბაზაში.

მეორე უფრო მზაკვრულია და §4-ის ტესტმა ორივე უნდა დაიჭიროს.

---

## 3. რას **არ** ეხება ეს ფაზა

| capability | endpoint-ები | რატომ არა |
|---|---|---|
| `org.manage` | 11 | **გადასაწყვეტია — იხ. §6.1** |
| `content.evidence` | 3 | **D-2** ღიაა |
| `messaging.broadcast` | 1 | **D-7**: scope-იდან ამოღებულ მოდულს ახალი წესი არ ეძლევა |

ეს 15 endpoint `requireContentAdmin` / `requireSystemAdmin` role-gate-ზე რჩება.

---

## 4. სავალდებულო ტესტები

- **`AccessContractCoverageTest` აუცილებლად დაეცემა** — gate-ის სახელი იცვლება
  25 endpoint-ზე. ეს **მოსალოდნელია და სწორია**: `docs/ACCESS_CONTRACT_MATRIX_KA.md`-ის
  `gate (დღეს)` სვეტი **იმავე commit-ში** უნდა განახლდეს. gate-ის გადაადგილება
  review-ს დაქვემდებარებული მოვლენაა, არა ჩუმად გასასწორებელი შეუსაბამობა.
- **`PermissionEnforcementCoverageTest`** — `content.manage` კატალოგში
  დამატებისთანავე მოითხოვს, რომ სადმე `hasPermission(...)`-ით შემოწმდეს.
- **ახალი:** `CONTENT_ADMIN` გადის, `OPERATOR`/`MANAGER` იღებს `403`-ს ყველა 25
  endpoint-ზე.
- **ახალი:** `OPERATOR` + `content.manage` `ALLOW` override-ით **გადის** —
  სწორედ ესაა წესი #9-ის მიზანი.
- **ახალი:** `CONTENT_ADMIN` + `DENY` override-ით **იღებს `403`-ს** — `DENY`
  ერთადერთი გზაა, default ჩამოერთვას role-ის შეცვლის გარეშე.
- **`ResponseShapeContractTest`, `ExportColumnAllowlistTest`** — არ უნდა
  შეიცვალოს. თუ შეიცვალა, რაღაც scope-ს ეხება და ეს ფაზა არ არის.
- **permission provenance ტესტი** (მატრიცის §9.1): inherited default explicit
  override-ად **არ** ინახება — არც role-change-ისას, არც უცვლელი role-ის save-ისას;
  delta endpoint-ის `INHERIT` override-ს **ნამდვილად შლის**.
- **role-change ტესტი** ცალ-ცალკე bulk backend გზაზე და Angular გზაზე.

---

## 5. წინა ფაზებიდან დარჩენილი — **ეს Codex-ის საქმე არ არის**

ქვემოთ ჩამოთვლილი მხოლოდ **რეალურ Oracle-ზე** კეთდება. აქამდე არცერთ
გარემოში არ შესრულებულა.

### 5.1 `V36` არსად არ არის გაშვებული

`V36__org_structure_expand.sql` დაწერილია და სტრუქტურულად შემოწმებული
(`V36MigrationShapeTest`), მაგრამ **არასოდეს გაშვებულა** — Flyway-ს Oracle
სჭირდება. სანამ ის არ გაეშვება, `departments`, `leadership_assignments`,
`user_permission_overrides` და `users.lock_version` **არ არსებობს**.

> ⚠️ **Phase 6-ის §2.3 (delta კონტრაქტი) სწორედ ამ ცხრილებს ეყრდნობა.**
> თუ `V36` ჯერ არ გაშვებულა, Phase 6-ის §2.1/§2.2 (თავად content gates)
> მაინც შესრულებადია — §2.3 კი მას ელოდება.

### 5.2 backfill არ გაშვებულა

`V36`-ის შემდეგ, ამ თანმიმდევრობით:

1. `GET /api/admin/org-backfill/report` — dry run, არაფერს წერს;
2. გადაწყდეს `DEPARTMENT_ONLY_MANAGER`, `UNRESOLVED_MANAGER`,
   `DUPLICATE_PRIMARY` შემთხვევები;
3. `POST /api/admin/org-backfill/apply` — იდემპოტენტურია, რამდენჯერაც საჭიროა;
4. `GET /api/admin/policy-shadow` — `scope.*`-ის `disagreed` **მკვეთრად უნდა
   დაეცეს**. თუ არ დაეცა, backfill არ გავრცელდა;
5. როცა `blocks_cutover: false` — მხოლოდ მაშინ `V37` და Phase 4.

სამივე endpoint `SYSTEM_ADMIN`-only.

### 5.3 `V37` განზრახ არ არსებობს

`V36MigrationShapeTest.v37IsNotShippedYet` ამას ტესტით იცავს. `V37` (NOT NULL,
`(department_id, name)` uniqueness, external-id uniqueness) მხოლოდ
**წარმატებული backfill-ის შემდეგ, ცალკე release-ში**.

`V37`-ს წინ უნდა უძღოდეს preflight: `(department_id, name)` დუბლიკატები,
`NULL` `department_id`, external-id დუბლიკატები, ერთ scope-ზე ერთზე მეტი
აქტიური `PRIMARY`.

> **გახსოვდეთ:** `V36` **ხსნის** გლობალურ `uq_teams_name`-ს, ხოლო შემცვლელი
> uniqueness მხოლოდ `V37`-შია. ამ ორ release-ს შორის ჯგუფის სახელზე
> uniqueness **საერთოდ არ არსებობს**. ფანჯარას ორი რამ ხურავს:
> `POST /api/teams` უკვე `403`-ია, და `V37`-ის preflight.

### 5.4 ტესტები, რომლებიც ამ გარემოში ვერ გავუშვი

- **Oracle integration ტესტები** — 262 error, ყველა `ORA-12541` (ბაზა არ არის);
- **Angular vitest** — CLI ითხოვს Node ≥ 22.22.3, კონტეინერს აქვს 22.22.2.
  TypeScript typecheck (`tsconfig.json` + `tsconfig.spec.json`) სუფთაა.

DB-free ტესტები: **597, 0 failure**.

---

## 6. ღია გადაწყვეტილებები

### 6.1 `org.manage` — ხაფანგში ვარდება ⚠

მატრიცამ შემოგვთავაზა `org.manage` 11 endpoint-ზე. მაგრამ მას მხოლოდ
`SYSTEM_ADMIN` ატარებდა, ხოლო `PermissionChecker.hasPermission` სისტემურ ადმინს
**უპირობოდ** ატარებს — ე.ი. `org.manage` **ვერასოდეს იქნება `false`**.

ეს ზუსტად ის ხაფანგია, რომელიც `Permission.java`-ში უკვე დოკუმენტირებულია და
რომლის გამოც `users.manage` SEC-06-ზე ამოიღეს:

> *„a permission whose only holder is SYSTEM_ADMIN can never do anything. The
> switch renders, saves, persists — and is never consulted."*

**ვარიანტები:**

- **(ა)** `org.manage` არ დაემატოს; ეს endpoint-ები `SYSTEM_ADMIN` role-gate-ზე
  დარჩეს. **გულწრფელია** — ისინი დიზაინით ადმინისაა, და არარსებული switch სჯობს
  არამომუშავეს. **← რეკომენდაცია**
- **(ბ)** დაემატოს და ვიგულისხმოთ: user-ების ადმინისტრირება არა-ადმინზე
  დელეგირებადი გახდეს. ეს **ახალი შესაძლებლობაა**, არა gate, და პროდუქტულ
  გადაწყვეტილებას საჭიროებს.

### 6.2 დანარჩენი ღია პუნქტები

| # | საკითხი | ვისზეა |
|---|---|---|
| **D-2** | `content.evidence` — read-receipts/views/feedback org-wide, `operator_email`-ით | product + DPO |
| **D-4** | `/uploads/{filename}` ავტორიზაციის გარეშე | product + IT (`QUESTIONS_FOR_IT` §9) |
| **D-5** | `content.manage`-ის მარცვლოვნება | product |
| **D-6** | csv export-ის ინგლისური სათაურები | product/UX |
| **D-7** | messaging — scope-იდან ამოღებული, კოდში დარჩენილი | product |
| **G-1/G-2** | უფროსის export-ში სახელები + statistical whitelist | იურიდიული / DPO |
| **G-4/G-5** | AD stable ID-ები, change feed, deactivation | Magti IT (`QUESTIONS_FOR_IT` §10) |

**D-1** (leaderboard) და **D-3** (`export_jobs.owner_user_id`) **დახურულია**.

---

## 7. მიმდინარე branch

```
codex/ui-ux-plan-oracle-category-fixes  →  0e0cd0b
```

ბოლო commit-ები:

```
0e0cd0b  feat(policy): make the shadow run and the backfill report readable
04af923  feat(policy): add the authorization policy layer, measuring only
534c0db  feat(access): close D-1 and D-3 from the access contract
b53a28c  feat(org): expand the schema for departments, groups and leadership
1e44207  test(access): enforce the export allowlist and the response shapes
39077db  docs(access): lock the access contract for all 112 endpoints
8d4714b  fix(security): stop a permission grant from creating an export scope
d1b48a1  fix(security): fail closed phase zero scopes
```
