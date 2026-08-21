# ორგანიზაციული სტრუქტურისა და შეთავსებული უფლებების გეგმა

**სტატუსი:** პროდუქტის მფლობელის მიერ შეთანხმებული და კოდთან გადამოწმებული სამიზნე გეგმა
**ბოლო განახლება:** 2026-08-21
**წყარო:** პროდუქტის მფლობელის პასუხები და Java/Oracle/Angular კოდის აუდიტი

## 1. მიზანი

პორტალმა უნდა ასახოს სამი დეპარტამენტი — **ტექნიკური**, **საინფორმაციო** და
**ოფისი** — თითოეულში ცვალებადი რაოდენობის ჯგუფებით. ერთ მომხმარებელს აქვს ერთი
ძირითადი ჯგუფი, მაგრამ შეიძლება ერთდროულად ჰქონდეს რამდენიმე პასუხისმგებლობა:
ოპერატორის სამუშაო, ჯგუფის ან დეპარტამენტის ხელმძღვანელობა და კონტენტის შექმნა.

სამიზნე წვდომის ფორმულაა:

> **ერთი ძირითადი role + მოქმედების permissions + დამოუკიდებელი
> ორგანიზაციული/data scope**

ნამდვილი multi-role (`user_roles`) ამ ამოცანისთვის საჭირო არ არის. მოქმედების
უფლების დამატებამ პერსონალური/სტატისტიკური მონაცემების ხილვადობა არ უნდა
გააფართოოს.

## 2. დადასტურებული გადაწყვეტილებები

- ოფიციალური დეპარტამენტებია `ტექნიკური`, `საინფორმაციო`, `ოფისი`.
- თითო დეპარტამენტში საწყისად იქმნება 5 fixture-ჯგუფი; production რაოდენობა და
  შემადგენლობა ცვალებადია და საბოლოოდ AD-დან სინქრონდება.
- მომხმარებელი ერთდროულად მხოლოდ ერთ ჯგუფს ეკუთვნის.
- ჯგუფს შეიძლება ჰყავდეს ძირითადი უფროსი და დროებითი შემცვლელი; ერთი ადამიანი
  დროებით შეიძლება რამდენიმე ჯგუფს ხელმძღვანელობდეს.
- ჯგუფს აქვს უფროსისგან დამოუკიდებელი სტაბილური AD key/name. უფროსის შეცვლა
  ჯგუფის იდენტობას ან სახელს არ ცვლის; UI უფროსს ცალკე აჩვენებს.
- სისტემურ ადმინს შეუძლია როგორც მუდმივი, ისე დროებითი უფროსის დანიშვნა.
- არქიტექტურა ითვალისწინებს დეპარტამენტის ხელმძღვანელსაც, თუმცა პირველი rollout
  მის გარეშე მუშაობს. ხელმძღვანელი ხედავს დეპარტამენტის ყველა ჯგუფის
  სტატისტიკას, ხოლო კონტენტს მხოლოდ დამატებითი permission-ით მართავს.
- ჯგუფის უფროსი სავალდებულო გაცნობის მონაწილე არ არის.
- ოპერატორს შეიძლება დაემატოს კონტენტის შექმნის permission; default-ად ის
  compliance-ში რჩება, ხოლო სისტემურ ადმინს შეუძლია ეს სტატუსი შეცვალოს.
- კონტენტის permission გლობალური მოქმედების უფლებაა: მფლობელს შეუძლია მასალა
  გამოაქვეყნოს მთელი კომპანიისთვის, მათ შორის თუ თვითონ ჯგუფის უფროსია.
- კონტენტის ავტორს/რედაქტორს შეუძლია სხვისი სტატიის შეცვლა და კატეგორიების
  მართვა; audit ინახავს ვინ, როდის და რა შეცვალა.
- კონტენტ-ადმინი სტატისტიკას მხოლოდ ჯგუფის უფროსობის assignment-ის არსებობისას
  ხედავს და მხოლოდ საკუთარ ჯგუფზე.
- permissions-სა და ხელმძღვანელობის assignment-ებს მხოლოდ სისტემური ადმინი
  მართავს.
- დროებითი permission-ის expiry არ გვჭირდება; დროებითი უფროსის assignment და
  მისი ხელით ჩართვა/გათიშვა საჭიროა.
- required reading მიზნობრივია დეპარტამენტის ან `All` დონეზე, არა ჯგუფზე.
- ჯგუფის ცვლილება იმავე დეპარტამენტში მიმდინარე დავალებებს არ ცვლის;
  დეპარტამენტის ცვლილებისას ძველი დეპარტამენტის მიმდინარე დავალებები აღარ
  მიჰყვება, ისტორიული მტკიცებულება კი რჩება.
- უფროსის გარეშე დარჩენილ ჯგუფზე სისტემური ადმინი იღებს შეტყობინებას.
- დეპარტამენტების, ჯგუფებისა და წევრობის production source of truth არის AD;
  სისტემური ადმინი ამ ორგანიზაციულ მონაცემებს ხელით არ ცვლის.
- UI ტერმინია **„ჯგუფი“**. `All` არის მხოლოდ აუდიტორიის wildcard და არა
  მომხმარებლის ორგანიზაციული ერთეული.
- სისტემურ ადმინს რჩება უპირობო სრული ფუნქციური წვდომა, გარდა AD-ით მართული
  ორგანიზაციული ფაქტების ხელით გადაწერისა.
- ჯგუფის უფროსის export მოიცავს საკუთარი ჯგუფის წევრების სახელებს და ყველა
  დაშვებულ სტატისტიკურ ფორმას/მაჩვენებელს. raw audit/view/search/session/security
  log-ების export აკრძალულია. სახელების export საჭიროებს production-მდე
  იურიდიულ/DPO დადასტურებას.

## 3. მიმდინარე სისტემის დადასტურებული მდგომარეობა

2026-08-21-ის ადგილობრივ Oracle მონაცემებში:

- `teams` ცხრილში 0 ჩანაწერია;
- `users.team_id` 0 მომხმარებელზეა შევსებული;
- `users.manager_id` 0 მომხმარებელზეა შევსებული;
- 10 მომხმარებლის `department` ტექსტში უკვე წერია `ჯგუფი`, მაგრამ ეს არ არის
  ნორმალიზებული კავშირი;
- Angular-ის user form მხოლოდ ოთხ მნიშვნელობას სთავაზობს:
  `All`, `ტექნიკური`, `საინფორმაციო`, `ოფისი`;
- backend-ში ჯგუფური prefix matching არსებობს, მაგრამ ჯგუფის შექმნის,
  დეპარტამენტთან მიბმის, უფროსის assignment-ისა და წევრების მართვის სრული UI/API
  workflow არ არსებობს;
- კონტენტის, compliance-ის, stats-ისა და navigation-ის არაერთი gate პირდაპირ
  ადარებს `Role`-ს. ამიტომ per-user permission დღეს ვერ გამოსახავს ყველა
  შეთავსებულ პასუხისმგებლობას.

### 3.1 კოდთან დადასტურებული P0 ბლოკერები

ეს პუნქტები redesign-ის დაწყებამდე უნდა დაიხუროს, რადგან არსებული API-ებით
UI-ის დამალვა მონაცემთა უსაფრთხოებას ვერ უზრუნველყოფს.

1. `ExportQueryService.scopedCompliance()` ყველა non-`MANAGER` მომხმარებელს
   ორგანიზაციის სრულ compliance მონაცემს უბრუნებს, ხოლო export gate მხოლოდ
   `REPORTS_EXPORT` permission-ს ამოწმებს. რადგან ეს permission სისტემურ ადმინს
   ნებისმიერი მომხმარებლისთვის შეუძლია ჩართოს, scope-ის არქონა დღეს fail-open
   ქცევას ქმნის.
2. `/api/manager/department-stats` ჯგუფის უფროსს დეპარტამენტის ყველა ჯგუფის
   aggregate მონაცემებს უბრუნებს; მხოლოდ member list-ის მოცილება არასაკმარისია.
   პასუხის ყველა group/department ჩანაწერი მოქმედ leadership scope-ზე უნდა
   გაიფილტროს.
3. `/api/admin/stats/team/{teamId}`, `/api/admin/critical-operators` და
   `/api/admin/departments/{department}/groups/{group}/users` content-admin
   role-ს scope-ის გარეშე აძლევს თანამშრომელთა სახელებსა და სტატისტიკას.
   leadership assignment-ის cutover-მდე non-system-admin წვდომა უნდა დაიკეტოს;
   შემდეგ კი მხოლოდ `ScopeResolver`-ის შედეგით გაიხსნას.
4. report export-ის კონტრაქტი ჯერ არ კრძალავს ტექნიკურად audit/view/search/
   session/security log-ების სამომავლო დამატებას. საჭიროა server-side allowlist
   და ცალკე სისტემურ-ადმინისტრატორული audit-export capability; `SYSTEM_AUDIT`
   permission report export-ის გაფართოების უფლება არ არის.
5. `teams.name`-ზე არსებული გლობალური unique constraint ეწინააღმდეგება ერთნაირი
   სახელის (მაგ. „ჯგუფი 1“) სხვადასხვა დეპარტამენტში გამოყენებას. შემდეგ Oracle
   migration-ში ის უნდა ჩანაცვლდეს `(department_id, name)` უნიკალურობით და
   stable external ID-ის ცალკე unique constraint-ით.

## 4. სამიზნე მონაცემთა მოდელი

### 4.1 ორგანიზაციული ერთეულები

`departments`

- `id`, უცვლელი AD/stable key, ოფიციალური სახელი, აქტიურობა, sort order;
- საწყისი seed: ტექნიკური, საინფორმაციო, ოფისი;
- `All` ამ ცხრილში არ ინახება.

`teams` (UI-ში „ჯგუფი“)

- არსებული ცხრილი გაფართოვდება: `department_id`, AD/stable key, display name,
  active/sync metadata;
- Oracle migration არის ეტაპობრივი: ჯერ nullable სვეტები, შემდეგ backfill და
  validation, ბოლოს `NOT NULL`/unique constraints. ადგილობრივ ბაზაში 0 ჯგუფი
  production მონაცემების არარსებობის გარანტია არ არის;
- გლობალური `teams.name` unique constraint უქმდება; uniqueness არის
  `(department_id, name)` და ცალკე stable external ID-ზე;
- display name/stable key უფროსისგან დამოუკიდებელია; leadership ცვლილება ჯგუფის
  იდენტობას არ ცვლის;
- საწყისი fixture: თითო დეპარტამენტზე 5 ჯგუფი;
- production-ში შექმნა/გაუქმება და წევრობა მოდის AD sync-იდან.

`users`

- `team_id` ხდება მომხმარებლის ერთი აქტიური ჯგუფის კანონიკური კავშირი;
- `department` ტექსტი რჩება მატერიალიზებულ compatibility ველად და AD sync ერთ
  ტრანზაქციაში აახლებს მას `team_id`-სთან ერთად. Oracle virtual/generated column
  სხვა ცხრილთან join-ით ვერ გამოითვლება;
- გარდამავალ პერიოდში mapper ინარჩუნებს არსებული ტექსტის ფორმატს და backfill-ის
  წინ/შემდეგ authorization-result parity ცალკე მოწმდება;
- `manager_id` აღარ იქნება ჯგუფური scope-ის წყარო.

`leadership_assignments`

- `user_id`, nullable `department_id`, nullable `team_id` — ორივე რეალური FK;
- `CHECK` constraint ითხოვს ზუსტად ერთ scope FK-ს: ან დეპარტამენტს, ან ჯგუფს.
  polymorphic `scope_type + scope_id` არ გამოიყენება, რადგან Oracle FK-ით მის
  სისწორეს ვერ დაიცავს;
- `assignment_type` (`PRIMARY`/`ACTING`), active flag, start/end audit metadata;
- თითო scope-ზე მხოლოდ ერთი მოქმედი `PRIMARY` assignment Oracle function-based
  unique index-ით კონტროლდება; უბრალო partial index Oracle-ში არ არსებობს;
- ერთი მომხმარებელი შეიძლება დროებით რამდენიმე scope-ს ხელმძღვანელობდეს;
- primary და acting assignment-ს მხოლოდ სისტემური ადმინი ქმნის/თიშავს;
- მოქმედი assignment-ის ყველა ცვლილება audit-ში ხვდება.

### 4.2 მოქმედება და ხილვადობა

- `Role` რჩება ოთხ ფიქსირებულ მნიშვნელობად: operator, manager,
  content_admin, admin.
- `Permission` პასუხობს მხოლოდ კითხვას „რისი გაკეთება შეუძლია?“.
- `ScopeResolver` პასუხობს „ვის/რას ხედავს?“ და იყენებს leadership assignment-ს,
  არა role-ს ან free-text department-ს.
- დეპარტამენტის ხელმძღვანელის assignment აძლევს საკუთარი დეპარტამენტის ყველა
  ჯგუფის stats/export scope-ს, მაგრამ არა კონტენტის permission-ს.
- `SYSTEM_ADMIN` ინარჩუნებს permission bypass-ს, მაგრამ AD-owned org data-ის
  mutation endpoint საერთოდ არ ექნება.
- role defaults და კონკრეტული მომხმარებლის `ALLOW`/`DENY` permission override
  ცალ-ცალკე ინახება (ან ეკვივალენტური provenance-ით). ძირითადი role-ის ცვლილება
  explicit override-ებს არ შლის; ცვლილების actor და permission delta აუდიტირდება.
- compliance eligibility ცალკე policy ხდება: default-ად operator მონაწილეობს,
  manager/content-admin/system-admin არ მონაწილეობენ და მოქმედი leadership
  assignment-იც გამორიცხავს მონაწილეობას. operator + content permission კვლავ
  მონაწილეობს, თუ სისტემურ ადმინს explicit compliance override არ დაუყენებია.
  მხოლოდ role ან მხოლოდ leadership ამ გადაწყვეტილებისთვის საკმარისი არ არის.

## 5. backend-ის სამუშაოები

1. Flyway `V36` migration: departments, teams extension, leadership assignments,
   permission/compliance override provenance, sync metadata და Oracle-compatible
   constraints/function-based indexes. Oracle-ში `IF NOT EXISTS` არ გამოიყენება.
2. გარდამავალი mapper: არსებული `department` ტექსტების უსაფრთხო mapping ახალ
   department/team ID-ებზე; ისტორიული audit არ იცვლება.
3. `CapabilityService`: ცენტრალური permission check.
4. `ScopeResolver`: ჯგუფის/დეპარტამენტის სტატისტიკისა და პერსონალური მონაცემების
   fail-closed scope. მხოლოდ `SYSTEM_ADMIN` არის unscoped; სხვა მომხმარებლისთვის
   მოქმედი assignment-ის არქონა collection endpoint-ზე ცარიელ შედეგს, კონკრეტულ
   უცხო resource-ზე კი `403`-ს იძლევა.
5. `ComplianceEligibilityService`: default + system-admin override.
6. Article/category/news/video/upload endpoints-ებიდან `isContentAdmin()`
   role-gate-ის ჩანაცვლება რეალური permission-ებით.
7. Manager stats/audit/export paths-იდან `Role.MANAGER`-ის ჩანაცვლება მოქმედი
   leadership assignment-ით.
8. `/api/me/effective-access`: role, permissions, leadership scopes,
   compliance status და UI capabilities ერთ პასუხში.
9. AD sync contract: stable external IDs, upsert, deactivation, reconciliation,
   dry-run/report და orphan-group alert. რეალური AD connector ცალკე ფაზაა.
10. audit events: assignment/permission/compliance override ცვლილებები და სხვისი
    სტატიის edit-ის ზუსტი actor/version კვალი.
11. მიმდინარე P0 endpoint-ების server-side შეზღუდვა:
    `department-stats`, `stats/team/{teamId}`, `critical-operators`, group users და
    ყველა report export. response-ის nested aggregates-იც scope-ზე იფილტრება.
12. `getGroupLeaders` role-იდან leadership assignment-ზე გადადის. AD-managed
    რეჟიმში `POST /api/teams` და სხვა org mutation endpoints `403`-ს აბრუნებს;
    fixture/dev რეჟიმი ცალკე, აშკარად მონიშნული გამონაკლისია.

## 6. Angular/UI სამუშაოები

### სისტემური ადმინი

- ორგანიზაციული ხე: დეპარტამენტი → ჯგუფი → წევრები;
- AD-owned ველები read-only და წყაროს/ბოლო sync-ის მითითებით;
- ძირითადი/დროებითი უფროსის assignment;
- permissions-ის ჩართვა/გათიშვა;
- compliance override;
- effective-access summary და „რატომ აქვს ეს წვდომა“ განმარტება;
- უფროსის გარეშე დარჩენილი ჯგუფების alert/task list.

### სხვა მომხმარებლები

- navigation და dashboard იგება `/api/me/effective-access`-ით, არა მხოლოდ role-ით;
- ჯგუფის უფროსი ხედავს საკუთარი assignment-ების გაერთიანებულ, მაგრამ სხვა
  ჯგუფებზე არაგაფართოებულ სტატისტიკას;
- დეპარტამენტის ხელმძღვანელი ხედავს დეპარტამენტის ყველა ჯგუფის სტატისტიკას;
- stats export-ში ჩანს საკუთარი scope-ის წევრების სახელები და სტატისტიკური
  მონაცემები, მაგრამ log/event მონაცემები არ ჩანს და არ იტვირთება;
- კონტენტის permission-ის მქონე მომხმარებელი ხედავს კონტენტის workspace-ს;
- კონტენტის workspace-ის გამოჩენა არ აჩენს stats/audit გვერდებს;
- role switcher არ გამოიყენება.

## 7. დანერგვის ფაზები

0. **Security hotfix:** P0 endpoint-ებზე არსებული role-based შესაძლებლობებით
   fail-open წვდომის ჩაკეტვა; leadership schema-მდე non-system-admin-ს scope-ის
   გარეშე პერსონალური/ორგანიზაციული მონაცემი არ მიეწოდება.
1. **Decision/contract lock:** permission/scope matrix, response-shape contracts,
   export allowlist და external production gates.
2. **Schema, behavior unchanged:** Oracle-compatible `V36`, ნორმალიზებული org
   tables, staged backfill და format-preserving compatibility mapping.
3. **Policy layer, shadow mode:** `CapabilityService`, fail-closed
   `ScopeResolver`, compliance policy; ძველი და ახალი გადაწყვეტილებების diff
   ითვლება, სანამ enforcement ჩაირთვება.
4. **Leadership scope cutover:** stats/export/audit assignment-ზე გადაყვანა და
   nested response filtering.
5. **Compliance cutover:** eligibility/override parity, შემდეგ ახალი policy-ის
   enforcement.
6. **Content gates:** role-იდან permission-ზე გადასვლა.
7. **Effective access + Angular:** `/api/me/effective-access`, navigation და
   route guards capability-ებზე გადადის.
8. **System-admin UI + fixtures:** read-only AD structure, assignments,
   permissions, 5 ჯგუფი თითო დეპარტამენტზე და სრული persona QA.
9. **AD readiness/production:** sync adapter dry run, reconciliation/access-diff
   report, feature flags და rollback-ready rollout.

## 8. უსაფრთხოების წესები

- action permissions ერთიანდება; data scope კი მხოლოდ მოქმედი leadership
  assignment-ების აშკარა გაერთიანებაა. რამდენიმე ჯგუფის assignment მხოლოდ იმ
  ჯგუფებს აერთიანებს, ხოლო department assignment განზრახ მოიცავს მის ჯგუფებს;
  role ან სხვა permission scope-ს ავტომატურად არასოდეს აფართოებს;
- `REPORTS_EXPORT`, `SYSTEM_AUDIT` ან content permission scope-ს არ ქმნის. მხოლოდ
  `SYSTEM_ADMIN` bypass ან მოქმედი leadership assignment იძლევა პერსონალური/
  სტატისტიკური მონაცემების scope-ს;
- ჯგუფის უფროსი ვერ ხედავს sibling ჯგუფს პირდაპირი API მოთხოვნითაც;
- content permission არასოდეს იძლევა employee stats/audit/export წვდომას;
- stats export server-side whitelist-ით უშვებს identity + statistical ველებს და
  კრძალავს raw audit/view/search/session/security log-ებს;
- audit/log export, თუ მომავალში დაემატა, იქნება ცალკე system-admin-only endpoint,
  ცალკე capability-ით და report export-ის მოდელებისგან განცალკევებული DTO-ებით;
- AD-owned org mutation fail-closed არის;
- ლიდერის/permission-ის გაუქმება მოქმედებს მომდევნო მოთხოვნიდან, რადგან JWT
  filter მომხმარებლის authorization-ს DB-დან ყოველ request-ზე თავიდან კითხულობს;
- ისტორიული acknowledgment, quiz attempt და audit ჩანაწერები org ცვლილებით არ
  გადაიწერება ან არ იშლება.

## 9. acceptance criteria

- სამივე დეპარტამენტში 5 fixture-ჯგუფი მუშაობს და თითო user მხოლოდ ერთ ჯგუფშია;
- ძირითადი და დროებითი უფროსის დანიშვნა/გაუქმება UI-დან აუდიტირდება;
- ერთი ადამიანი დროებით რამდენიმე ჯგუფს მართავს და ხედავს მხოლოდ მათ;
- ჯგუფის უფროსი + content permission აქვეყნებს კომპანიისთვის სტატიას, მაგრამ
  სხვა ჯგუფის თანამშრომელთა სტატისტიკას ვერ ხედავს;
- operator + content permission-ის compliance სტატუსი system admin-ის მიერ
  მართვადია და default-ად ჩართულია;
- content admin ხელმძღვანელობის assignment-ის გარეშე stats-ს ვერ ხსნის;
- ჯგუფის ცვლილება დეპარტამენტის შიგნით readings-ს ინარჩუნებს; დეპარტამენტის
  ცვლილება ძველ მიმდინარე readings-ს წყვეტს, ისტორიას კი ინარჩუნებს;
- სხვისი სტატიის edit-ში actor და version audit ზუსტად ინახება;
- უფროსის გარეშე დარჩენილი ჯგუფი სისტემურ ადმინს alert-ად უჩნდება;
- AD-owned ჯგუფი/წევრობა UI-დან არ იცვლება;
- ჯგუფის უფროსს შეუძლია საკუთარი scope-ის სახელებისა და ყველა დაშვებული
  სტატისტიკური მაჩვენებლის export, მაგრამ არცერთი raw log-ის export;
- authorization matrix და პირდაპირი API negative tests ყველა კომბინაციაზე
  მწვანეა.

### 9.1 სავალდებულო ავტომატური მტკიცებულებები

- `AuthorizationMatrix` contract test ამოწმებს არა მხოლოდ HTTP status-ს, არამედ
  response shape-სა და nested group/department ჩანაწერებს;
- negative API tests ფარავს sibling ჯგუფს, assignment-ის არქონას,
  content-permission-only და revoked assignment სცენარებს;
- CSV/XLSX/PDF export whitelist tests ამტკიცებს, რომ დაშვებული identity/statistic
  სვეტების გარდა audit/view/search/session/security ველი ვერ მოხვდება;
- compliance parity tests ფარავს operator-ს, operator+content permission-ს,
  full-time content admin-ს, manager-ს, acting leader-ს და explicit override-ს;
- department mapping tests ადარებს authorization შედეგს backfill-მდე და შემდეგ
  და ინარჩუნებს legacy ტექსტის ფორმატს;
- permission coverage test ყველა backend endpoint-ს აკავშირებს capability/scope
  წესთან და არ ტოვებს შემთხვევით role-only gate-ს;
- Oracle integration test ამოწმებს `V36` migration-ს, FK/check constraints-სა და
  function-based unique indexes-ს რეალურ Oracle-ზე;
- E2E persona: ერთი მომხმარებელი არის group leader + content permission — მას
  შეუძლია გლობალური კონტენტის მართვა, მაგრამ სტატისტიკაში მხოლოდ საკუთარ ჯგუფს
  ხედავს;
- role-change tests ცალ-ცალკე ფარავს bulk backend update-სა და Angular user-edit
  გზას: explicit permission overrides არცერთ გზაზე არ უნდა განულდეს.

## 10. production-მდე გარე დადასტურება

- Magti-ის იურიდიულმა/DPO პასუხისმგებელმა უნდა დაადასტუროს ჯგუფის უფროსის
  export-ში თანამშრომელთა სახელების შეტანა და საბოლოო statistical column
  whitelist. ეს არ ცვლის შეთანხმებულ პროდუქტულ scope-ს, მაგრამ production
  ჩართვის სავალდებულო gate-ია.
- AD ინტეგრაციისას IT-მ უნდა მოგვაწოდოს departments/groups/users stable ID-ები,
  ჯგუფის ცვლილებების feed და deactivation semantics.
