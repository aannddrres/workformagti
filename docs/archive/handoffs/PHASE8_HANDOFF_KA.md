> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# Phase 8 — system-admin UI + fixtures

**სტატუსი:** დასაწყები, **ნაწილობრივ დაბლოკილი** — იხ. §2
**შედგენილია:** 2026-08-22 (Claude)
**გეგმა:** `docs/ORG_ACCESS_ARCHITECTURE_PLAN_KA.md` §7, ფაზა 8
**კონტრაქტი:** `docs/ACCESS_CONTRACT_MATRIX_KA.md`
**წინაპირობა:** Phase 6 (`PHASE6_HANDOFF_KA.md` §11), Phase 7 (`PHASE7_HANDOFF_KA.md` §7)

---

## 1. რას ითხოვს გეგმა

> **8. System-admin UI + fixtures:** read-only AD structure, assignments,
> permissions, 5 ჯგუფი თითო დეპარტამენტზე და სრული persona QA.

ოთხი რამ. სამი აშენებადია **დღეს**, ერთი ელოდება.

---

## 2. ჯერ წაიკითხე: რა არის დაბლოკილი და რატომ

`V36`-მა შექმნა `departments`, `leadership_assignments` და
`user_permission_overrides`. **მაგრამ org backfill არსად არ გაშვებულა**, ე.ი.:

* `leadership_assignments` **ცარიელია** ყველგან;
* `users.team_id` 0 row-ზეა შევსებული;
* `departments`-ში მხოლოდ `V36`-ის სამი bootstrap მწკრივია.

ამიტომ:

| Phase 8-ის ნაწილი | მდგომარეობა |
|---|---|
| fixtures (3 დეპარტამენტი × 5 ჯგუფი) | ✅ **აშენებადია** — ეს seeder-ია, არა backfill |
| read-only AD structure ეკრანი | ✅ **აშენებადია** fixture მონაცემებზე |
| assignments ეკრანი | ✅ **აშენებადია** |
| backfill report/apply ეკრანი | ✅ **აშენებადია** — endpoint-ები უკვე არსებობს |
| permissions ეკრანი | ✅ **უკვე არსებობს** (Phase 6-ის drawer) — იხ. §3.5 |
| **სრული persona QA** | ❌ **დაბლოკილია** — production-ის ფორმის მონაცემი არ არსებობს |

**seeder ≠ backfill.** fixture ლოკალურ development ბაზას ავსებს, რომ ეკრანებს
ჰქონდეთ რა აჩვენონ. `OrgBackfillService` არსებულ `users.department`-ს
ნამდვილ ორგანიზაციულ სტრუქტურად თარგმნის. **ერთი მეორეს არ ცვლის და
seeder-ი backfill-ს არ უნდა იძახებდეს.**

---

## 3. სამუშაო

### 3.1 Backend — ახალი endpoint-ები

დღეს `departments`/`leadership_assignments`-ისთვის **არცერთი endpoint არ
არსებობს** — მხოლოდ `DepartmentRepository` და
`LeadershipAssignmentRepository`. UI-ს მათი დამატება სჭირდება.

მინიმალური ნაკრები:

| endpoint | gate | დანიშნულება |
|---|---|---|
| `GET /api/admin/org/structure` | `requireSystemAdmin` | დეპარტამენტები → ჯგუფები → წევრთა რაოდენობა. **read-only** |
| `GET /api/admin/org/assignments` | `requireSystemAdmin` | მოქმედი და ისტორიული leadership assignment-ები |
| `POST /api/admin/org/assignments` | `requireSystemAdmin` | ლიდერის დანიშვნა (`PRIMARY` / `ACTING`) |
| `DELETE /api/admin/org/assignments/{id}` | `requireSystemAdmin` | დეაქტივაცია (soft, `is_active = 0`) |

**gate არის `requireSystemAdmin`, არა `org.manage`.** `org.manage` მატრიცაში
`NEW`-ად წერია, მაგრამ **არ უნდა დაინერგოს**: `PermissionChecker` სისტემურ
ადმინს უპირობოდ ატარებს, ე.ი. permission, რომელსაც მხოლოდ ის role ატარებს,
ვერასოდეს იქნება `false`. ეს SEC-06-ის ზუსტი გამეორება იქნებოდა.

### 3.2 რაც კონტრაქტს მოსდევს

**`AccessContractCoverageTest` აუცილებლად დაეცემა** — ოთხი ახალი endpoint
მატრიცაში არ არის. `docs/ACCESS_CONTRACT_MATRIX_KA.md` **იმავე commit-ში**:

* ოთხი მწკრივი (`gate: requireSystemAdmin`, `capability: —`, `scope: ORG`);
* `structure` და `assignments` **`PII = yes`** — ისინი სახელებს ატარებენ;
* „ციფრებში": **115 → 119**;
* ახალი სექცია `### Org (4)` ან არსებულში ჩართვა — თანმიმდევრობა ანბანურია.

`ResponseShapeContractTest`-ს ახალი record-ები დაუფიქსირდეს — ორივე
თანამშრომლის მონაცემს ატარებს, ე.ი. `SEC-03`-ის წესი მათზეც ვრცელდება.

### 3.3 Fixtures — 3 × 5

`scripts/`-ში ცალკე seeder (არსებული `seed_portal.py`/`seed_test_users.py`-ის
გვერდით), რომელიც ავსებს:

* სამ დეპარტამენტს (`V36`-ის bootstrap უკვე არსებობს — **არ დაადუბლირო**);
* თითოზე ხუთ ჯგუფს `teams`-ში, `department_id`-ით;
* თითო ჯგუფზე რამდენიმე ოპერატორს `users.team_id`-ით;
* თითო ჯგუფზე ერთ `PRIMARY` leadership assignment-ს.

**იდემპოტენტური იყოს** — ორჯერ გაშვება არ უნდა ქმნიდეს დუბლიკატს.
`V36`-ის `uq_leadership_primary_team` ერთ აქტიურ `PRIMARY`-ზე მეტს ისედაც
არ დაუშვებს, მაგრამ seeder-მა კარგი შეცდომა უნდა დააბრუნოს და არა
constraint violation.

### 3.4 Angular — ორი ახალი ეკრანი

**`/admin/org` — სტრუქტურა (read-only).**
დეპარტამენტი → ჯგუფები → წევრთა რაოდენობა. რედაქტირების არცერთი კონტროლი.
წესი #16: **AD ფლობს დეპარტამენტებს, ჯგუფებს და წევრობას.** UI-ს მათი
შეცვლა არ შეუძლია — და ეს ეკრანზე **აშკარად უნდა ეწერებოდეს**, თორემ
პირველი კითხვა „სად ვამატებ ჯგუფს?" იქნება.

`POST /api/teams` უკვე fail-closed `403`-ია (Phase 0). **არ გახსნა.**

**`/admin/org/assignments` — ლიდერების დანიშვნა.**
სისტემური ადმინი ნიშნავს `PRIMARY`/`ACTING` ლიდერს ჯგუფზე ან დეპარტამენტზე.
წესი #3: ჯგუფს აქვს AD-იდან მომდინარე იდენტობა **ლიდერისგან დამოუკიდებლად**
— ე.ი. ლიდერის მოხსნა ჯგუფს არ შლის.

ორივე: `roleGuard(ADMIN_ONLY)`, Phase 7-ის ნიმუშით. **`content.manage`-ს არ
დაუკავშირო** — ეს არ არის კონტენტი.

nav-ში `allowRoles: ['admin']`, ისევე როგორც „მომხმარებლები და წვდომა".

### 3.5 Permissions ეკრანი — უკვე არსებობს

Phase 6-მა `user-edit-modal`-ში ჩააშენა INHERIT/ALLOW/DENY editor, Phase 7-მა
კი `bypass` გახადა ხილული. **ახალი ეკრანი არ დაწერო.**

რაც შეიძლება დაემატოს, თუ QA-მ აჩვენა, რომ საჭიროა: `bypass: true`-ს
ვიზუალური აღნიშვნა drawer-ში, რომ სისტემურ ადმინზე ყველა გადამრთველი
„ჩართულად" არ იკითხებოდეს ისე, თითქოს ვინმემ ხელით ჩართო. `EffectiveAccess`
უკვე ატარებს ამ დროშას — ახალი endpoint არ სჭირდება.

### 3.6 Backfill-ის ეკრანი

`PolicyDiagnosticsController`-ის javadoc ამას პირდაპირ ამ ფაზას აბარებს:

> „Phase 8 puts a screen in front of this; it does not change who may do it
> or what gets recorded."

სამივე endpoint არსებობს და `SYSTEM_ADMIN`-only-ია:

```
GET  /api/admin/policy-shadow
GET  /api/admin/org-backfill/report
POST /api/admin/org-backfill/apply
```

ეკრანმა უნდა აჩვენოს `blocks_cutover` **მკაფიოდ** — ეს არის ის ერთი ველი,
რომელზეც Phase 4-ის cutover არის დამოკიდებული. `needs_a_decision > 0`-ის
დროს `apply`-ის ღილაკი უნდა მუშაობდეს (ის იდემპოტენტურია და სიის
დაცარიელებას ერთზე მეტი გავლა სჭირდება), მაგრამ **`V37`-ის ან cutover-ის
ნებართვად არ იკითხებოდეს**.

`policy-shadow`-ზე: `unexercised` დროშა რიცხვებზე **წინ** აჩვენე. ნული/ნული
ნიშნავს „აქ არავინ მისულა", არა „სუფთაა" — და ეს ზუსტად ის შეცდომაა,
რომლითაც cutover უსაფრთხოდ ჩაითვლება.

---

## 4. სავალდებულო ტესტები

### Backend

| ტესტი | უნდა ამტკიცებდეს |
|---|---|
| ოთხივე ახალი endpoint არა-ადმინზე | `403` (operator, manager, content_admin) |
| `content.manage`-ის მქონე operator | **მაინც `403`** — org ≠ content |
| `POST assignments` — მეორე `PRIMARY` იმავე ჯგუფზე | უარყოფილია (`uq_leadership_primary_team`), გასაგები შეცდომით |
| `DELETE assignments/{id}` | `is_active = 0`, **მწკრივი არ იშლება** — ისტორია რჩება |
| assignment-ის შექმნა/მოხსნა | **აუდიტდება** (`AuditLog`, actor-ით) |
| `ResponseShapeContractTest` | ახალი record-ების ზუსტი გასაღებები |
| `AccessContractCoverageTest` | გადის — მატრიცა განახლდა |

### Angular

| ტესტი | უნდა ამტკიცებდეს |
|---|---|
| `/admin/org*` `content.manage`-ის მქონე operator-ისთვის | **იკეტება** |
| `/admin/org*` სისტემური ადმინისთვის | იხსნება |
| სტრუქტურის ეკრანი | mutation-ის კონტროლი **არ არსებობს** DOM-ში |
| nav ხილვადობა | `allowRoles: ['admin']`-ს მიჰყვება |

### Seeder

იდემპოტენტურობის ტესტი: ორჯერ გაშვება იგივე რაოდენობის მწკრივს ტოვებს.

---

## 5. საზღვრები

* **`V37` არ დაწერო.** ის `V36`-ის constraint-ებს ამკაცრებს და მხოლოდ
  `blocks_cutover: false`-ის შემდეგ შეიძლება.
* **org backfill არ გაუშვა** production-ის მსგავს გარემოზე. ლოკალურ dev
  ბაზაზე `apply`-ის გამოცდა ეკრანის შესამოწმებლად დასაშვებია.
* **`org.manage` არ დაამატო** (§3.1).
* **D-8 კოდით არ გადაწყვიტო.**
* **`POST /api/teams` არ გახსნა** — fail-closed რჩება.
* **Phase 4/5-ის gate-ებს არ შეეხო** — `/manager` და `/reading` role-ზე
  რჩება, სანამ backfill არ გაივლის.
* **`users.permissions` სვეტი არ წაშალო** — `V36.1` მას კითხულობს.

---

## 6. სრული persona QA — რატომ არის ცალკე

გეგმის §8 „სრულ persona QA"-ს ითხოვს. fixture მონაცემებზე ის **ნაწილობრივია**:
ის ამოწმებს, ეკრანები მუშაობს თუ არა, არა — სწორად თარგმნის თუ არა backfill
ნამდვილ ორგანიზაციას.

ნამდვილი persona QA მოითხოვს:

1. `V36` + `V36.1` staging Oracle-ზე;
2. org backfill გაშვებული;
3. `blocks_cutover: false`;
4. Phase 4/5-ის cutover.

ე.ი. **Phase 8 fixture-ებზე ითიშება „აშენებულია"-ზე, არა „დადასტურებულია"-ზე.**
დოკუმენტში ეს განსხვავება აშკარად უნდა დარჩეს — არა „QA გავიდა".

---

## 7. Branch

base: `codex/phase6-content-gates` (`2b7b0b5`)
`claude/dept-groups-architecture-biqtma` ატარებს Phase 7-ის review-ს და ამ დოკუმენტს.

დაწყებამდე: `git fetch origin && git merge origin/claude/dept-groups-architecture-biqtma`

---

## 8. შესრულების სტატუსი — 2026-08-22

Phase 8-ის fixture seeder და system-admin ეკრანები აშენებულია. Seeder-ის
იდემპოტენტურობა local Oracle-ზე ერთ ტრანზაქციაში ორჯერ გაშვებით შემოწმდა
(`3` დეპარტამენტი, `15` ჯგუფი, `45` fixture მომხმარებელი და `15` აქტიური
PRIMARY დანიშვნა) და ტესტის ბოლოს rollback შესრულდა.

**Seeder ≠ backfill.** Seeder backfill endpoint-ს არ იძახებს; org backfill არც
local-ის გარეთ და არც production-ის მსგავს გარემოზე არ გაშვებულა. შესაბამისად,
სრული persona QA კვლავ **დაბლოკილია**: `leadership_assignments`-ის ნამდვილი
ორგანიზაციული backfill, `users.team_id`-ის production-shape შევსება,
`blocks_cutover: false` და Phase 4/5 cutover ჯერ არ არსებობს. ამ ფაზის შედეგია
„აშენებულია და fixture-ებზე შემოწმებულია“, არა „სრული persona QA გავიდა“.
