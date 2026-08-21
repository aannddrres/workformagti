# ორგანიზაციული სტრუქტურისა და შეთავსებული უფლებების გეგმა

**სტატუსი:** შეთანხმებული draft, დარჩენილია რამდენიმე დაზუსტება  
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
- არქიტექტურა ითვალისწინებს დეპარტამენტის ხელმძღვანელსაც, თუმცა პირველი rollout
  მის გარეშე მუშაობს.
- ჯგუფის უფროსი სავალდებულო გაცნობის მონაწილე არ არის.
- ოპერატორს შეიძლება დაემატოს კონტენტის შექმნის permission; compliance-ში მისი
  მონაწილეობა სისტემური ადმინის მიერ მართვადი უნდა იყოს.
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

## 4. სამიზნე მონაცემთა მოდელი

### 4.1 ორგანიზაციული ერთეულები

`departments`

- `id`, უცვლელი AD/stable key, ოფიციალური სახელი, აქტიურობა, sort order;
- საწყისი seed: ტექნიკური, საინფორმაციო, ოფისი;
- `All` ამ ცხრილში არ ინახება.

`teams` (UI-ში „ჯგუფი“)

- არსებული ცხრილი გაფართოვდება: `department_id`, AD/stable key, display name,
  active/sync metadata;
- საწყისი fixture: თითო დეპარტამენტზე 5 ჯგუფი;
- production-ში შექმნა/გაუქმება და წევრობა მოდის AD sync-იდან.

`users`

- `team_id` ხდება მომხმარებლის ერთი აქტიური ჯგუფის კანონიკური კავშირი;
- `department` ტექსტი რჩება მხოლოდ გარდამავალ compatibility ველად, შემდეგ
  გამოითვლება `team.department_id`-დან;
- `manager_id` აღარ იქნება ჯგუფური scope-ის წყარო.

`leadership_assignments`

- `user_id`, `scope_type` (`GROUP`/`DEPARTMENT`), `scope_id`;
- `assignment_type` (`PRIMARY`/`ACTING`), active flag, start/end audit metadata;
- ერთი მომხმარებელი შეიძლება დროებით რამდენიმე scope-ს ხელმძღვანელობდეს;
- მოქმედი assignment-ის ყველა ცვლილება audit-ში ხვდება.

### 4.2 მოქმედება და ხილვადობა

- `Role` რჩება ოთხ ფიქსირებულ მნიშვნელობად: operator, manager,
  content_admin, admin.
- `Permission` პასუხობს მხოლოდ კითხვას „რისი გაკეთება შეუძლია?“.
- `ScopeResolver` პასუხობს „ვის/რას ხედავს?“ და იყენებს leadership assignment-ს,
  არა role-ს ან free-text department-ს.
- `SYSTEM_ADMIN` ინარჩუნებს permission bypass-ს, მაგრამ AD-owned org data-ის
  mutation endpoint საერთოდ არ ექნება.
- compliance eligibility ცალკე policy ხდება; მხოლოდ management role-ზე მიბმა
  აღარ იქნება საკმარისი.

## 5. backend-ის სამუშაოები

1. Flyway migration: departments, teams extension, leadership assignments,
   sync metadata და საჭირო constraints/indexes.
2. გარდამავალი mapper: არსებული `department` ტექსტების უსაფრთხო mapping ახალ
   department/team ID-ებზე; ისტორიული audit არ იცვლება.
3. `CapabilityService`: ცენტრალური permission check.
4. `ScopeResolver`: ჯგუფის/დეპარტამენტის სტატისტიკისა და პერსონალური მონაცემების
   fail-closed scope.
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
- კონტენტის permission-ის მქონე მომხმარებელი ხედავს კონტენტის workspace-ს;
- კონტენტის workspace-ის გამოჩენა არ აჩენს stats/audit გვერდებს;
- role switcher არ გამოიყენება.

## 7. დანერგვის ფაზები

1. **Decision lock:** დარჩენილი კითხვების დახურვა და permission/scope matrix.
2. **Schema, behavior unchanged:** ნორმალიზებული org tables და compatibility
   mapping.
3. **Policy layer:** CapabilityService, ScopeResolver, compliance policy და
   unit/contract tests.
4. **Content gates:** role-იდან permission-ზე გადასვლა.
5. **Leadership scope:** stats/export/audit assignment-ზე გადაყვანა.
6. **System-admin UI:** read-only AD structure + local assignments/permissions.
7. **Fixture rollout:** 5 ჯგუფი თითო დეპარტამენტზე და სრული persona QA.
8. **AD readiness:** sync adapter contract, dry run და reconciliation report.
9. **Production rollout:** feature flags, staged backfill, access-diff report და
   rollback-ready migration.

## 8. უსაფრთხოების წესები

- action permissions ერთიანდება; data scope არ ერთიანდება და ყოველთვის ყველაზე
  ვიწრო მოქმედი assignment-ით განისაზღვრება;
- ჯგუფის უფროსი ვერ ხედავს sibling ჯგუფს პირდაპირი API მოთხოვნითაც;
- content permission არასოდეს იძლევა employee stats/audit/export წვდომას;
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
  მართვადია;
- content admin ხელმძღვანელობის assignment-ის გარეშე stats-ს ვერ ხსნის;
- ჯგუფის ცვლილება დეპარტამენტის შიგნით readings-ს ინარჩუნებს; დეპარტამენტის
  ცვლილება ძველ მიმდინარე readings-ს წყვეტს, ისტორიას კი ინარჩუნებს;
- სხვისი სტატიის edit-ში actor და version audit ზუსტად ინახება;
- უფროსის გარეშე დარჩენილი ჯგუფი სისტემურ ადმინს alert-ად უჩნდება;
- AD-owned ჯგუფი/წევრობა UI-დან არ იცვლება;
- authorization matrix და პირდაპირი API negative tests ყველა კომბინაციაზე
  მწვანეა.

## 10. დარჩენილი გადაწყვეტილებები

1. ჯგუფის სახელი უნდა იყოს სტაბილური AD სახელი/კოდი თუ უფროსის შეცვლისას უნდა
   შეიცვალოს უფროსის სახელთან ერთად.
2. AD განსაზღვრავს თუ არა მუდმივ უფროსსაც, თუ ყველა primary/acting leadership
   assignment მხოლოდ პორტალში იმართება.
3. დეპარტამენტის ხელმძღვანელის ზუსტი stats/export scope და შემცვლელის წესი.
4. operator + content permission-ის compliance default და override semantics.
5. ჯგუფის უფროსის უსაფრთხო სტატისტიკური export-ის column whitelist.
