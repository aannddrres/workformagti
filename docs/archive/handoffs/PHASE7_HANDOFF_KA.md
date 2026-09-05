> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# Phase 7 — effective access + Angular

**სტატუსი:** ✅ **დასრულებულია** (2026-08-22, `2b7b0b5`) — review §7, blocker არ არის
**შედგენილია:** 2026-08-22 (Claude)
**გეგმა:** `docs/ORG_ACCESS_ARCHITECTURE_PLAN_KA.md` §7, ფაზა 7
**კონტრაქტი:** `docs/ACCESS_CONTRACT_MATRIX_KA.md`
**წინაპირობა:** Phase 6 დასრულებულია (`docs/PHASE6_HANDOFF_KA.md` §11)

---

## 1. რატომ არის ეს საჭირო — ერთი წინადადებით

Phase 6-მა backend-ს ასწავლა, რომ `content.manage`-ის მქონე **ოპერატორს**
კონტენტის მართვა შეუძლია. frontend-მა ეს არ იცის — `/admin` კვლავ **role**-ს
ითხოვს.

```typescript
// app.routes.ts:94
canActivate: [roleGuard(ADMIN_OR_CONTENT_ADMIN)]
```

ე.ი. ადმინს შეუძლია ოპერატორს `content.manage` მიანიჭოს, backend მას ყველა
25 endpoint-ზე გაატარებს — და ეს ადამიანი **ვერანაირად ვერ მოხვდება** იმ
ეკრანზე, რომელიც ამ endpoint-ებს იძახებს. permission გრანტდება, აღსრულდება,
და პროდუქტში გამოუყენებელია.

**Phase 7 სწორედ ამ ხარვეზს ხურავს.** ეს არ არის refactor — ეს არის Phase 6-ის
დაუმთავრებელი ნახევარი.

---

## 2. Backend — `GET /api/me/effective-access`

### 2.1 კონტრაქტი

```json
{
  "role": "operator",
  "permissions": ["content.manage"],
  "bypass": false
}
```

| ველი | წყარო |
|---|---|
| `role` | `user.getRole().value()` |
| `permissions` | `PermissionChecker.effectivePermissions(user)` → `Permission::value`, სორტირებული |
| `bypass` | `user.getRole() == Role.SYSTEM_ADMIN` |

Gate: `requireAuthenticated`. სხვა არაფერი — ეს საკუთარ თავზეა.

### 2.2 `bypass` აუცილებელია, არა დეკორაცია

მატრიცის `/api/users/me` მწკრივი ამას პირდაპირ ითხოვს: **„`bypass: true`
აშკარად უნდა ჩანდეს"**.

მიზეზი: `effectivePermissions` სისტემურ ადმინს **ყველა** permission-ს უბრუნებს
(`CapabilityService:69-73`), რაც სიმართლეა — `PermissionChecker.hasPermission`
მისთვის ყოველთვის `true`-ს აბრუნებს. მაგრამ სია არ ამბობს **რატომ**. ამ
დროშის გარეშე UI ვერ განასხვავებს „ყველაფერი მიენიჭა"-ს „შემოწმებას გვერდს
უვლის"-გან, ხოლო Phase 8-ის permission-ების ეკრანი ყველა გადამრთველს
ჩართულად აჩვენებდა ისე, თითქოს ვინმეს ისინი ხელით ჩაერთო.

### 2.3 `scope` **არ** დაამატო

leadership scope Phase 4-ია და დაბლოკილია (org backfill არ გაშვებულა).
ცარიელი ან placeholder `scope` ველი UI-ს მასზე დაყრდნობას შეაგულიანებდა
მანამ, სანამ ის რამეს ნიშნავს. როცა Phase 4 დასრულდება, ველი მაშინ დაემატება.

### 2.4 კონტრაქტის ვალდებულებები

**`AccessContractCoverageTest` **აუცილებლად** დაეცემა** — ახალი endpoint
მატრიცაში არ არის. `docs/ACCESS_CONTRACT_MATRIX_KA.md` **იმავე commit-ში**
უნდა განახლდეს:

```
| `GET /api/me/effective-access` | `<Controller>.getEffectiveAccess` | `requireAuthenticated` | AUTH | `SELF` | no | Phase 7. მომძახებლის საკუთარი effective permission-ები; `bypass` სისტემური ადმინის შემოვლას აშკარას ხდის. |
```

ასევე:
* „ციფრებში" სექციაში **114 → 115**;
* `/api/users/me`-ის მწკრივის შენიშვნა (`„/api/me/effective-access ამას ცვლის
  Phase 7-ზე"`) განახლდეს — ის უკვე მოხდა.

`ResponseShapeContractTest`-ს დაემატოს ახალი record-ის wire shape:

```java
assertEquals(List.of("role", "permissions", "bypass"), wireFieldsOf(EffectiveAccessResponse.class));
```

### 2.5 რა **არ** უნდა შეიცვალოს

`/api/users/me` რჩება. ის პროფილს ატარებს (სახელი, დეპარტამენტი, progress),
რაც effective-access-ის საქმე არ არის. მისი `permissions` და
`can_view_audit_log` უკვე effective access-იდან ითვლება (Phase 6, `27e2289`),
ე.ი. ორი endpoint ვერ დაუპირისპირდება ერთმანეთს.

`users.permissions` **სვეტი** ჯერ რჩება. `PermissionChecker`-ის javadoc მის
მოხსნას „Phase 7 response-contract cleanup"-ს აბარებდა — **პასუხის** ნაწილი
უკვე გაკეთდა; თავად სვეტის წაშლა ცალკე migration-ია და მხოლოდ მას შემდეგ
შეიძლება, რაც `V36.1` ყველა გარემოზე გაივლის (ის ამ სვეტს კითხულობს).
**Phase 7-ის ნაწილი არ არის.**

---

## 3. Angular — რომელი gate გადადის და რომელი არა

**ეს ცხრილი ამ დავალების ყველაზე მნიშვნელოვანი ნაწილია.** ყველა role-check
capability-ს არ ნიშნავს, და არასწორი გადაყვანა Phase 4/5-ის გადაწყვეტილებას
წინასწარ ჩაკეტავს.

### 3.1 გადადის ✅

| ადგილი | დღეს | Phase 7 |
|---|---|---|
| `app.routes.ts:94` `/admin` parent | `roleGuard(ADMIN_OR_CONTENT_ADMIN)` | `content.manage` **ან** `bypass` |
| `app.routes.ts` `/admin/content` | მშობლისგან | იგივე |
| `app.routes.ts` `/admin/categories` | მშობლისგან | იგივე |
| `app-shell.ts:78` nav „კონტენტი" | `allowRoles: ['admin','content_admin']` | `content.manage` |
| `app-shell.ts:79` nav „კატეგორიები" | `allowRoles: ['admin','content_admin']` | `content.manage` |
| `role.guard.ts:60-71` `adminOverviewGuard` | role-ებზე | ქვემოთ, §3.3 |

### 3.2 **არ** გადადის ❌

| ადგილი | დღეს | რატომ რჩება |
|---|---|---|
| `/admin/overview` + nav „მიმოხილვა" | admin-only | სტატისტიკაა. **D-8 ღიაა** — `content.manage`-ით სტატისტიკის გახსნა ზუსტად ის სადავო გადაწყვეტილებაა. კოდით არ გადაწყვიტო. |
| `/admin/access` + nav „მომხმარებლები" | `roleGuard(ADMIN_ONLY)` | `org.manage` **უარყოფილია** (SEC-06): SYSTEM_ADMIN bypass-ს აკეთებს, ე.ი. permission ვერასოდეს იქნება `false`. |
| `/admin/audit` + nav „აუდიტი" | `auditLogGuard` | **უკვე permission-ზეა.** არაფერი გასაკეთებელი. |
| `/manager` + nav „გუნდის მდგომარეობა" | `roleGuard(MANAGER_ROLES)` | leadership scope = **Phase 4**. |
| `/reading` + nav „სავალდებულო გაცნობა" | `denyRoles(MANAGEMENT_ROLES)` | compliance eligibility = **Phase 5**. |

### 3.3 `adminOverviewGuard`-ის ახალი წესი

დღეს ის `content_admin`-ს `/admin/content`-ზე ამისამართებს, რომ ადმინის
არეში შესვლისას ცარიელ ეკრანზე არ აღმოჩნდეს. იგივე ლოგიკა უნდა დარჩეს,
ოღონდ role-ის ნაცვლად capability-ზე:

1. `bypass` → `/admin/overview` გაატარე;
2. `content.manage` → `/admin/content`-ზე გადაამისამართე;
3. სხვა → `/`.

პუნქტი 2 ახლა **ოპერატორსაც** მოიცავს, თუ მას `content.manage` აქვს — სწორედ
ეს არის §1-ის ხარვეზის გამოსწორება.

### 3.4 ერთი წყარო, არა ორი

`UserProfileService` უკვე ქეშავს `/api/users/me`-ს single-flight-ით და
logout-ზე ასუფთავებს (`user-profile.service.ts:38-47`). **ნუ შექმნი
პარალელურ სერვისს** effective-access-ისთვის — ორი ქეში, რომლებიც
სხვადასხვა მომენტში იტვირთება, აუცილებლად დაუპირისპირდება ერთმანეთს და
navigation ერთს დაუჯერებს, guard კი მეორეს.

გააფართოვე `UserProfileService`, ან effective-access აქციე იმ ერთადერთ
fetch-ად, რომელსაც ორივე იყენებს.

### 3.5 ნიმუში, რომელიც უკვე გაქვს

`core/auth/permission.guard.ts` — `auditLogGuard`. მისი javadoc უკვე ამბობს
იმას, რასაც Phase 7 განაზოგადებს:

> „Gating on the server's own `can_view_audit_log` also means the UI stops
> re-deriving an authorization decision the backend already made — the two
> cannot disagree."

**fail-closed იგივენაირად:** ჩავარდნილი fetch → `null` → შინ. არა
„ალბათ `true` იქნებოდა".

---

## 4. სავალდებულო ტესტები

### Backend

| ტესტი | უნდა ამტკიცებდეს |
|---|---|
| operator + `content.manage` ALLOW | `permissions` შეიცავს `content.manage`-ს, `bypass: false` |
| content_admin + `content.manage` DENY | **არ** შეიცავს; დანარჩენი role default-ები რჩება |
| system_admin | `bypass: true` და სრული სია |
| ავტორიზაციის გარეშე | `401` |
| `ResponseShapeContractTest` | ზუსტი JSON გასაღებები |
| `AccessContractCoverageTest` | გადის — ე.ი. მატრიცა განახლდა |

### Angular

| ტესტი | უნდა ამტკიცებდეს |
|---|---|
| operator + `content.manage` | `/admin/content` **იხსნება** ← §1-ის ხარვეზი |
| operator ამის გარეშე | `/`-ზე ბრუნდება |
| content_admin + DENY | `/admin/content` **იკეტება** |
| nav ხილვადობა | იმავე წყაროს მიჰყვება, რასაც guard |
| fetch ჩავარდა | fail-closed, შინ |

**რეგრესიის ტესტი:** `/manager` და `/reading` **უცვლელი** უნდა დარჩეს —
`content.manage`-ის მქონე ოპერატორმა `/manager`-ზე წვდომა **არ** უნდა
მიიღოს. ეს არის §3.2-ის საზღვრის დაცვა კოდში, არა მხოლოდ დოკუმენტში.

---

## 5. საზღვრები

* `V37` **არ** დაწერო;
* org backfill **არ** გაუშვა;
* **D-8** კოდით არ გადაწყვიტო;
* `users.permissions` სვეტი არ წაშალო (§2.5);
* `scope` ველი არ დაამატო (§2.3);
* Phase 4/5-ის gate-ებს არ შეეხო (§3.2).

---

## 6. Branch

base: `codex/phase6-content-gates` (`e6dff78`) ან მისი merge integration-ში.
`claude/dept-groups-architecture-biqtma` = `f1685fb` (Phase 6-ის დოკუმენტაცია).

დაწყებამდე: `git fetch origin && git merge origin/claude/dept-groups-architecture-biqtma`

---

## 7. საბოლოო review (Claude, 2026-08-22) — `2b7b0b5`

**მწვანეა. blocker არ არის, არაფერი გამისწორებია.**

ერთი commit, 18 ფაილი. `514cf92` მისი წინაპარია.

### 7.1 გადამოწმებული პუნქტები

| # | შედეგი |
|---|---|
| endpoint-ის კონტრაქტი | ✅ `{role, permissions, bypass}`; `permissions` სორტირებული; gate `requireAuthenticated`; **`scope` არ დამატებულა** |
| ALLOW / DENY / bypass | ✅ ოთხივე backend ტესტი: operator+ALLOW, content_admin+DENY (`content.manage` ქრება, `articles.edit`/`system.audit` რჩება), admin `bypass: true` + სრული კატალოგი, 401 |
| ერთი წყარო | ✅ `UserProfileService` — ერთი `_access` signal, single-flight `accessRequest`, `clear()` ორივეს ასუფთავებს. guard და navigation **ერთ** ქეშს კითხულობენ |
| fail-closed | ✅ `catchError → of(null)`; `permissionGuard`, `adminOverviewGuard` და route-spec ცალკე ამოწმებენ |
| `/admin/overview`, `/admin/access` | ✅ admin-only; ტესტი `keeps overview and access outside content.manage` |
| `/manager`, `/reading` | ✅ role-gated; ტესტი `keeps Phase 4 and Phase 5 role gates unchanged` |
| მატრიცა 115 | ✅ სათაური, „ციფრებში" (112+3), User სექცია 13→14, ახალი მწკრივი სწორი gate/capability/scope/PII-ით |
| `V37` / backfill / `scope` / `users.permissions` | ✅ არცერთი არ შეხებია |

### 7.2 რაც განსაკუთრებით კარგად გაკეთდა

**`app.routes.spec.ts` ნამდვილ guard-ებს იღებს**, არა ასლს:

```typescript
canActivate: child('admin').canActivate,
canActivate: adminChild('overview').canActivate,
```

ე.ი. `app.routes.ts`-ის შეცვლა ტესტს **მიჰყვება**. კოპირებული guard-ის სია
პირველივე refactor-ზე ჩუმად მოძველდებოდა.

**Phase 5-ის საზღვარი ორივე მიმართულებით შემოწმდა.** `/reading` არა მხოლოდ
„არ გაიხსნა", არამედ **გაიხსნა** `content.manage`-ის მქონე ოპერატორისთვის —
ე.ი. capability-მ ის შემთხვევით „მენეჯმენტად" არ აქცია. ეს უფრო ბასრი ტესტია,
ვიდრე მარტო უარყოფითი.

**`auditLogGuard` კონსოლიდირდა** `can_view_audit_log`-იდან
`permissionGuard('system.audit')`-ზე. ქცევა იდენტურია (`hasPermission` და
`effectivePermissions` ორივე `PermissionChecker`-იდან მოდის), მაგრამ ახლა
ორივე გადაწყვეტილება **ერთ** წყაროზეა.

### 7.3 ორი შენიშვნა მომავლისთვის (defect არა)

* **`/admin/access` ახლა `contentManageGuard`-ის ქვეშაა.** ეს დღეს სწორად
  მუშაობს **მხოლოდ იმიტომ**, რომ SYSTEM_ADMIN-ის bypass მას `content.manage`-საც
  აძლევს. თუ ოდესმე SEC-06-ის ვარიანტი (ა) აირჩა — bypass-ის მოხსნა —
  user administration ადმინისთვისაც დაიკეტება. `roleGuard(ADMIN_ONLY)` შვილზე
  ადგილზეა, ე.ი. გაფართოება არ ხდება; ეს მხოლოდ დამოკიდებულებაა, რომელიც
  უნდა ახსოვდეს.
* **`CurrentUserProfile.can_view_audit_log` აღარავინ კითხულობს** frontend-ზე.
  wire-ზე რჩება. მისი მოხსნა response-contract ცვლილებაა — ცალკე
  გადაწყვეტილება, არა Phase 7-ის ნაწილი.

### 7.4 ვერიფიკაცია

| | Codex | Claude (ამ container-ში) |
|---|---|---|
| backend | **617**, 0 failure | **617**, 0 failure; 271 error = `ORA-12541` |
| Angular Vitest | 72/72 | ვერ გავუშვი (Node 22.22.2 < 22.22.3) |
| `tsc` main + spec | — | ✅ ორივე სუფთა |
| prod build | ✅ (bundle-budget warning non-failing) | ვერ გავუშვი |

### 7.5 სტატუსი

**Phase 7 დასრულებულია.** შემდეგი — `docs/PHASE8_HANDOFF_KA.md`.
