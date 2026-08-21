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
- `V36` ქმნის და bootstrap reference data-დ ამატებს სამ ოფიციალურ დეპარტამენტს:
  ტექნიკური, საინფორმაციო, ოფისი. AD sync მოგვიანებით მათ stable external ID-ს
  უკავშირებს და შემდგომ მდგომარეობას მართავს;
- `All` ამ ცხრილში არ ინახება.

`teams` (UI-ში „ჯგუფი“)

- არსებული ცხრილი გაფართოვდება: `department_id`, AD/stable key, display name,
  active/sync metadata;
- Oracle migration არის ორ release-ად ეტაპობრივი: `V36` ამატებს nullable სვეტებს,
  შემდეგ აპლიკაციის idempotent backfill/validation სრულდება; მხოლოდ ამის შემდეგ
  ცალკე release-ში ემატება `V37` `NOT NULL`/unique constraints-ით. `V37` არ უნდა
  მოხვდეს იმავე deployment-ში, რომელშიც backfill პირველად ეშვება. ადგილობრივ
  ბაზაში 0 ჯგუფი production მონაცემების არარსებობის გარანტია არ არის;
- გლობალური `teams.name` unique constraint უქმდება; uniqueness არის
  `(department_id, name)` და ცალკე stable external ID-ზე;
- display name/stable key უფროსისგან დამოუკიდებელია; leadership ცვლილება ჯგუფის
  იდენტობას არ ცვლის;
- თითო დეპარტამენტის 5 საწყისი ჯგუფი იქმნება მხოლოდ აშკარა dev/test fixture
  seeder-ით და არა production Flyway migration-ით;
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
- `CHECK` constraint Oracle 19c-compatible `CASE` არითმეტიკით ითხოვს ზუსტად ერთ
  scope FK-ს:
  `(CASE WHEN department_id IS NULL THEN 0 ELSE 1 END) +`
  `(CASE WHEN team_id IS NULL THEN 0 ELSE 1 END) = 1`.
  `IS NULL` boolean შედეგების ერთმანეთთან `<>` შედარება არ გამოიყენება;
- `assignment_type` (`PRIMARY`/`ACTING`), `is_active NUMBER(1) DEFAULT 1 NOT NULL`,
  start/end audit metadata;
- თითო scope-ზე მხოლოდ ერთი მოქმედი `PRIMARY` assignment ორი ცალკე Oracle
  function-based unique index-ით კონტროლდება — ერთი `department_id`-ზე, მეორე
  `team_id`-ზე. უბრალო partial index Oracle-ში არ არსებობს;
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
- eligibility-ის ყოველი გადასვლა (`before → during → after`) ინახება effective
  timestamp-იან მოვლენად: assignment activation/deactivation, role ცვლილება და
  compliance override. ისტორიულ evidence report-ს უნდა შეეძლოს აჩვენოს, რატომ
  იყო ადამიანი კონკრეტულ პერიოდში eligibility-ში ან მის გარეთ.

## 5. backend-ის სამუშაოები

1. Flyway expand migration `V36`: departments + სამი canonical bootstrap row,
   ძველი გლობალური `uq_teams_name`-ის მოხსნა, teams-ის nullable extension,
   leadership assignments, permission/compliance override provenance,
   `users.lock_version NUMBER DEFAULT 0 NOT NULL` (JPA optimistic lock;
   `token_version`-ისგან დამოუკიდებელი) და sync metadata. Oracle-ში
   `IF NOT EXISTS` არ გამოიყენება. `POST /api/teams` და ყველა org mutation
   endpoint იკეტება `V36`-მდე, რათა expand/contract ფანჯარაში ახალი დუბლიკატი ვერ
   გაჩნდეს. Backfill-ის წარმატებული ცალკე rollout-ის შემდეგ `V37` ამატებს
   `NOT NULL`, composite/external-ID uniqueness-ს, one-scope `CHECK`-სა და ორ
   function-based primary-leader unique index-ს. `V37`-ის deployment-ს წინ
   უძღვის blocking preflight: `(department_id, name)` დუბლიკატები, `NULL`
   `department_id`, external-ID დუბლიკატები და scope-ზე ერთზე მეტი აქტიური
   `PRIMARY`; ნებისმიერი დარღვევა contract migration-ს აჩერებს.
2. გარდამავალი mapper: არსებული `department` ტექსტების უსაფრთხო mapping ახალ
   department/team ID-ებზე; ისტორიული audit არ იცვლება. mapper საერთოდ არ ეხება
   `read_statuses.operator_department_snapshot` და
   `article_read_receipts.operator_department_snapshot` სვეტებს.
3. Leadership backfill: თითო აქტიური `MANAGER`-ისთვის
   `DepartmentMatcher.splitGroup()`-ით იქმნება assignment candidate. მხოლოდ
   ერთმნიშვნელოვანი group match შეიძლება ავტომატურად გადაიქცეს `PRIMARY team_id`
   assignment-ად. მხოლოდ დეპარტამენტის match ავტომატურად department-head წვდომას
   არ ქმნის, რადგან ასეთი ხელმძღვანელი პირველ rollout-ში დადასტურებული არ არის;
   ის, unmapped/ambiguous მნიშვნელობა და ერთ scope-ზე რამდენიმე primary კანდიდატი
   reconciliation report-ში ხვდება და სისტემური ადმინი cutover-მდე ხელით წყვეტს.
4. `CapabilityService`: ცენტრალური permission check.
5. `ScopeResolver`: ჯგუფის/დეპარტამენტის სტატისტიკისა და პერსონალური მონაცემების
   fail-closed scope. მხოლოდ `SYSTEM_ADMIN` არის unscoped; სხვა მომხმარებლისთვის
   მოქმედი assignment-ის არქონა collection endpoint-ზე ცარიელ შედეგს, კონკრეტულ
   უცხო resource-ზე კი `403`-ს იძლევა.
6. `ComplianceEligibilityService`: default + system-admin override და
   effective-dated eligibility transition audit.
7. `PUT /api/users/{id}/permissions` flat full-replace კონტრაქტი იცვლება
   override-aware delta კონტრაქტით (`INHERIT`/`ALLOW`/`DENY`, optimistic
   concurrency). UI/API role defaults-ს explicit override-ებად არ აგზავნის.
8. Article/category/news/video/upload endpoints-ებიდან `isContentAdmin()`
   role-gate-ის ჩანაცვლება რეალური permission-ებით.
9. Manager stats/audit/export paths-იდან `Role.MANAGER`-ის ჩანაცვლება მოქმედი
   leadership assignment-ით.
10. `/api/me/effective-access`: role, inherited defaults, explicit overrides,
   effective permissions, leadership scopes, compliance status და UI capabilities
   ერთ პასუხში. `SYSTEM_ADMIN`-ზე პასუხი ცალკე `bypass: true` დროშას აბრუნებს და
   ხელოვნურად შევსებულ „ყველა permission“-ის ნაკრებს არ ქმნის; „რატომ აქვს ეს
   წვდომა“ ამ bypass-ს ცალკე მიზეზად აჩვენებს.
11. AD sync contract: stable external IDs, upsert, deactivation, reconciliation,
   dry-run/report და orphan-group alert. რეალური AD connector ცალკე ფაზაა.
12. audit events: assignment/permission/compliance override ცვლილებები და სხვისი
    სტატიის edit-ის ზუსტი actor/version კვალი.
13. მიმდინარე P0 endpoint-ების server-side შეზღუდვა:
    `department-stats`, `stats/team/{teamId}`, `critical-operators`, group users და
    ყველა report export. response-ის nested aggregates-იც scope-ზე იფილტრება.
14. `getGroupLeaders` role-იდან leadership assignment-ზე გადადის. AD-managed
    რეჟიმში `POST /api/teams` და სხვა org mutation endpoints `403`-ს აბრუნებს;
    fixture/dev რეჟიმი ცალკე, აშკარად მონიშნული გამონაკლისია.
15. Leadership cutover-ისას ძველი `ManagerScope` იშლება და stats-ის hardcoded
    `DepartmentBuckets` იცვლება departments/team repository-ით; გარდამავალ
    მდგომარეობაში ორი scope resolver ან რამდენიმე department source of truth არ
    რჩება.

## 6. Angular/UI სამუშაოები

### სისტემური ადმინი

- ორგანიზაციული ხე: დეპარტამენტი → ჯგუფი → წევრები;
- AD-owned ველები read-only და წყაროს/ბოლო sync-ის მითითებით;
- ძირითადი/დროებითი უფროსის assignment;
- permissions-ის ჩართვა/გათიშვა;
- permission editor ცალ-ცალკე აჩვენებს role-იდან inherited default-ს და explicit
  `ALLOW`/`DENY` override-ს; ჩვეულებრივი save inherited default-ს override-ად არ
  აქცევს;
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
- Phase 0-ში content-admin-only მომხმარებლისთვის `/admin/main`-ის stats ნაწილი
  აღარ იხსნება (mixed dashboard-ის შემთხვევაში მომხმარებელი content workspace-ზე
  გადადის). route/navigation capability-ზე იკეტება, ხოლო უკვე გახსნილ stats
  view-ზე `403` კონტროლირებულ „სტატისტიკის წვდომა არ გაქვთ“ empty state-ად ჩანს
  და არა რამდენიმე error toast-ად.

## 7. დანერგვის ფაზები

0. **Security hotfix:** P0 endpoint-ებზე არსებული role-based შესაძლებლობებით
   fail-open წვდომის ჩაკეტვა; leadership schema-მდე non-system-admin-ს scope-ის
   გარეშე პერსონალური/ორგანიზაციული მონაცემი არ მიეწოდება. `POST /api/teams` და
   სხვა org mutation endpoint-ებიც აქვე იკეტება, `V36`-ის deploy-მდე.
1. **Decision/contract lock:** permission/scope matrix, response-shape contracts,
   export allowlist და external production gates. **დასრულებულია** —
   `docs/ACCESS_CONTRACT_MATRIX_KA.md` ფარავს სამივე backend surface-ს
   (112 endpoint) და მას `AccessContractCoverageTest` აკავშირებს source-თან;
   7 ღია გადაწყვეტილება (D-1…D-7) და 6 გარე gate (G-1…G-6) იქვეა ჩამოთვლილი.
   export-ის allowlist-სა და employee-data პასუხების ფორმას აღასრულებს
   `ExportColumnAllowlistTest` და `ResponseShapeContractTest`.
2. **Schema expand + backfill:** Oracle-compatible `V36`, ნორმალიზებული org
   tables, format-preserving department mapper, manager→leadership candidate
   backfill და reconciliation report. `V37` მხოლოდ წარმატებული validation-ის
   შემდეგ, ცალკე release-ში ამკაცრებს constraints-ს.
   **კოდი დაწერილია** — `V36__org_structure_expand.sql`, `domain/Department`,
   `domain/LeadershipAssignment`, `domain/UserPermissionOverride`,
   `org/OrgBackfillPlanner` და `org/OrgBackfillService`.
   `V37` განზრახ **ჯერ არ არსებობს** (`V36MigrationShapeTest.v37IsNotShippedYet`).
   `V36`-ის რეალურ Oracle-ზე გაშვება ჯერ დარჩენილია.
3. **Policy layer, shadow mode:** `CapabilityService`, fail-closed
   `ScopeResolver`, compliance policy; ძველი და ახალი გადაწყვეტილებების diff
   ითვლება, სანამ enforcement ჩაირთვება.
4. **Leadership scope cutover:** stats/export/audit assignment-ზე გადაყვანა და
   nested response filtering; `ManagerScope` და `DepartmentBuckets` იშლება.
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
- backfill არ ცვლის `operator_department_snapshot` ისტორიულ სვეტებს;
- scope/compliance cutover არ იწყება, თუ reconciliation report-ში არის
  დაუდასტურებელი manager, ambiguous primary collision ან authorization diff.
- `V37` არ ეშვება, სანამ schema preflight-ის duplicate/null/primary-collision
  ანგარიში სრულად სუფთა არ არის.

## 9. acceptance criteria

- სამივე დეპარტამენტში 5 fixture-ჯგუფი მუშაობს და თითო user მხოლოდ ერთ ჯგუფშია;
- ძირითადი და დროებითი უფროსის დანიშვნა/გაუქმება UI-დან აუდიტირდება;
- ერთი ადამიანი დროებით რამდენიმე ჯგუფს მართავს და ხედავს მხოლოდ მათ;
- ჯგუფის უფროსი + content permission აქვეყნებს კომპანიისთვის სტატიას, მაგრამ
  სხვა ჯგუფის თანამშრომელთა სტატისტიკას ვერ ხედავს;
- operator + content permission-ის compliance სტატუსი system admin-ის მიერ
  მართვადია და default-ად ჩართულია;
- content admin ხელმძღვანელობის assignment-ის გარეშე stats-ს ვერ ხსნის;
- cutover-ის შემდეგ ყველა დღევანდელ manager-ს აქვს დადასტურებული leadership
  scope; unmapped/collision გამონაკლისების სახელობითი სია cutover-მდე ხელითაა
  გადაწყვეტილი და არავინ კარგავს წვდომას ჩუმად;
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
  full-time content admin-ს, manager-ს და explicit override-ს; acting leader-ზე
  ტესტი ამოწმებს `before → during → after` eligibility-სა და სამივე transition
  audit timestamp-ს;
- department mapping tests ადარებს authorization შედეგს backfill-მდე და შემდეგ
  და ინარჩუნებს legacy ტექსტის ფორმატს;
- permission coverage test ყველა backend endpoint-ს აკავშირებს capability/scope
  წესთან და არ ტოვებს შემთხვევით role-only gate-ს;
- Oracle integration test ცალ-ცალკე ამოწმებს `V36` expand-სა და `V37` contract-ს,
  Oracle 19c-compatible one-scope `CHECK`-ს, `is_active NOT NULL`-ს და department/
  team primary assignment-ის ორ function-based unique index-ს რეალურ Oracle-ზე;
- E2E persona: ერთი მომხმარებელი არის group leader + content permission — მას
  შეუძლია გლობალური კონტენტის მართვა, მაგრამ სტატისტიკაში მხოლოდ საკუთარ ჯგუფს
  ხედავს;
- role-change tests ცალ-ცალკე ფარავს bulk backend update-სა და Angular user-edit
  გზას: explicit permission overrides არცერთ გზაზე არ უნდა განულდეს;
- permission provenance test ამტკიცებს, რომ inherited role default explicit
  override-ად არ ინახება არც role-change-ისას და არც უცვლელი role-ის ჩვეულებრივი
  save-ისას; delta endpoint-ის `INHERIT` მდგომარეობა override-ს ნამდვილად შლის;
- leadership backfill test მოიცავს ზუსტ group match-ს, department-only match-ს,
  unmapped/ambiguous მნიშვნელობასა და duplicate-primary collision-ს; cutover gate
  ყველა დაუდასტურებელ გამონაკლისზე იკეტება.

## 10. production-მდე გარე დადასტურება

- Magti-ის იურიდიულმა/DPO პასუხისმგებელმა უნდა დაადასტუროს ჯგუფის უფროსის
  export-ში თანამშრომელთა სახელების შეტანა და საბოლოო statistical column
  whitelist. ეს არ ცვლის შეთანხმებულ პროდუქტულ scope-ს, მაგრამ production
  ჩართვის სავალდებულო gate-ია.
- AD ინტეგრაციისას IT-მ უნდა მოგვაწოდოს departments/groups/users stable ID-ები,
  ჯგუფის ცვლილებების feed და deactivation semantics.
