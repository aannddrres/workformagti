# Magti Portal — საპრეზენტაციო გარემოს Runbook

## მიზანი და უსაფრთხოების საზღვარი

ეს გარემო განკუთვნილია მხოლოდ ამ კომპიუტერზე, პროდუქტის შესაძლებლობების 25–30 წუთში საჩვენებლად. იგი მუშაობს ცალკე Docker project-ში `magti-portal-presentation`, ცალკე Oracle volume-ში `magti-portal-presentation-oracle-data` და იხსნება მხოლოდ `http://127.0.0.1:8081`-ზე.

გარემო არ იყენებს არსებულ local Oracle volume-ს, production API-ს ან production მონაცემებს. თანამშრომლების აქტივობა სინთეტიკურია და პრეზენტაციაზე ასე უნდა დასახელდეს. ეს დემო არ ცვლის `READINESS_REPORT_2026-08-23`-ის production-readiness ბლოკერების სტატუსს და არ უნდა იქნას წარმოდგენილი production უსაფრთხოების/მზადყოფნის მტკიცებულებად.

### არსებული მონაცემის შენარჩუნებით განახლება — 2026-09-28

თუ presentation Oracle volume-ში მონაცემი უკვე არსებობს, `prepare`, `reset` და
`test regression` **არ გამოიყენოთ** განახლებისთვის: ეს ბრძანებები საწყისი
მონაცემის ჩატვირთვის/ხელახლა აგების გზებია. ჯერ გააჩერეთ presentation სტეკი,
შექმენით Oracle volume-ის დაცული სარეზერვო ასლი და დამოუკიდებელ volume-ზე
Oracle-ის გახსნით გადაამოწმეთ მისი აღდგენადობა. ჩაიწერეთ ცხრილების რაოდენობა
და ყველა ბიზნესჩანაწერის, BLOB-ის, audit chain-ის hash; Flyway განახლება ჯერ
ასლზე გამოსცადეთ. გასაგრძელებლად საჭიროა უარყოფითი privacy/file/receipt
ტესტებისა და Chrome-ის სრული სცენარის გავლა.

მიღებული კანდიდატის checkout-ში backend/frontend image-ები კონკრეტული commit-იდან
ააგეთ და შეამოწმეთ მათი digest. ამის შემდეგ იმავე `.presentation.env`-ით
შესაძლებელია `docker compose -f docker-compose.presentation.yml up -d --no-build
--wait oracle backend frontend` (განახლებისას **seeder არ გაუშვათ**).
მყისიერად შეამოწმეთ `127.0.0.1:8081`, Flyway-ის ბოლო ვერსია, image digest,
ანგარიშების/სტატიების/ქვითრების/ქვიზების/ფაილების რაოდენობა, BLOB hash და
audit chain. მხოლოდ Flyway history-ის ნებადართული სხვაობა აღრიცხეთ; სხვა
აუხსნელი სხვაობისას შეწყვიტეთ დემო და დაბრუნდით დამოწმებულ backup-ზე.
2026-09-28-ის კონკრეტული ასლისა და შემოწმებების მტკიცებულება ინახება
`.presentation-artifacts/remediation/2026-09-28_0059/`-ში.

## მომზადება

წინაპირობები:

- Docker Desktop გაშვებულია;
- repository root-ში არსებობს დამტკიცებული `magti_portal.db`;
- repository root-ში არსებობს `uploads/`;
- თავისუფალია დაახლოებით 3–5 GB ადგილი Oracle image/volume-სა და build cache-ისთვის;
- Chrome მზადაა 1920×1080 ან 1080p ეკრანზე.

PowerShell-ში, repository root-იდან:

```powershell
.\presentation.ps1 prepare
```

პირველი გაშვება რამდენიმე წუთს მოითხოვს: ჩამოიტვირთება Oracle image, აიგება Spring Boot/Angular/tools images, შესრულდება Flyway-ის ყველა მიგრაცია (seeder ითხოვს ზუსტად `scripts/presentation/common.py`-ის `EXPECTED_FLYWAY_VERSION`-ს), baseline ჩაიტვირთება და ავტომატურად გადამოწმდება. წარმატებისას იხსნება:

```text
http://127.0.0.1:8081
```

`prepare` ავტომატურად ქმნის gitignored `.presentation.env`-ს და `.presentation-artifacts/` ანგარიშების საქაღალდეს. source SQLite და `uploads/` tools container-ში read-only რეჟიმით mount-დება.

დამტკიცებული source manifest იყენებს checkpoint-გავლილი `magti_portal.db`-ის SHA-256-ს `299248ef1859799bc372932173bee0182411391104064ffeabeb770dad61b5d8`. მისი კონტროლირებული იმპორტის სრული პროექცია შედარებულია საწყისი, pre-checkpoint ფაილიდან აგებულ Oracle baseline-თან: 122 სტატია, 132 ისტორია, 5 სიახლე, 3 ვიდეო და 429 ფაილი (60,929,367 ბაიტი) ზუსტად ემთხვევა. სხვა checksum-ზე safety guard უნდა ჩავარდეს.

## სადემო ანგარიშები

ყველა ანგარიშის local-only პაროლია `MagtiDemo2026!`, **მაგრამ ამ სტენდზე პაროლი არ მოწმდება.** `docker-compose.presentation.yml`-ში `ALLOW_DEV_LOGIN` განზრახ `"true"`-ა (`x-allow-dev-login`), რომ login-ის persona picker-მა იმუშაოს: ის პაროლს არ აგზავნის, კორპორატიული SSO კი ჯერ არ არის. ამიტომ ქვემოთ ჩამოთვლილი ექვსი ანგარიში და ყველა `presentation.*` მისამართი **ნებისმიერი პაროლით** შედის, ხოლო უცნობი `presentation.*` მისამართი ახალ ოპერატორს ქმნის (`Test User …`). სხვა ელფოსტა (allow-list-ის გარეთ) პაროლს კვლავ ითხოვს.

**პრეზენტაციაზე არ თქვათ, რომ „არასწორი პაროლი არ გაივლის".** თუ ეს დემოს პროდუქტს არასწორად წარმოაჩენს, compose-ის კომენტარის მიხედვით `x-allow-dev-login` გადართეთ `"false"`-ზე და backend თავიდან აწიეთ — მაშინ მხოლოდ ზემოთ მოცემული პაროლი იმუშავებს, persona picker კი აღარ.

| ანგარიში | როლი და გამოსაყენებელი სცენარი |
|---|---|
| `admin@magti.ge` | სისტემური ადმინი — ორგანიზაცია, უფლებები, audit, საერთო სტატისტიკა |
| `content@magti.ge` | კონტენტ-ადმინი — ისტორია/diff, draft, scheduled, trash/restore, quiz |
| `manager@magti.ge` | ტექნიკური ჯგუფისა და დეპარტამენტის ლიდერი — scope-იანი სტატისტიკა/compliance |
| `info@magti.ge` | საინფორმაციო ოპერატორი — KB, ძებნა, პერსონალურ მონაცემთა quiz |
| `tech@magti.ge` | ტექნიკური ოპერატორი — KB, მომსახურების სტანდარტის quiz |
| `nino@magti.ge` | ოფისის ოპერატორი — ოფისის scope, რჩეული, reminder, acknowledgment |

ცხრილის სწრაფად სანახავად:

```powershell
.\presentation.ps1 credentials
```

## ავტომატური მიღების შემოწმება

პრეზენტაციის წინა დღეს და დაწყებამდე ერთხელ გაუშვით:

```powershell
.\presentation.ps1 verify
```

შემოწმება ადასტურებს:

- source manifest-ს: 122 სტატია, 132 ისტორია, 429/429 სურათი, 5 სიახლე და 3 ვიდეო;
- Oracle/Flyway context-ს (Flyway ზუსტად `EXPECTED_FLYWAY_VERSION`-ზე) და presentation marker/checksum-ს;
- 605 მომხმარებელს — 600 ორგანიზაციაში (3 დეპარტამენტი × 5 ჯგუფი × 40: თითო ჯგუფში 1 ლიდერი და 39 ოპერატორი) და 5 ცენტრალურ ანგარიშს (`admin@`, `content@`, `content2@`–`content4@`) — 15 ჯგუფს და leadership assignments-ს. ეს ერთადერთი ადგილია, სადაც ზუსტი რიცხვი წერია; დანარჩენი დოკუმენტები „~600"-ს ამბობს. 602 იყო 2026-09-01-მდე, სანამ `ac5cc7e`-მ persona picker-ისთვის `content2–4@` დაამატა;
- FK/reference მთლიანობას, tags/mappings-სა და არაცარიელ trigram ინდექსს;
- ყველა 429 BLOB-ის ზომას/checksum-ს და backend-იდან გახსნას;
- ექვსივე სწორ login-ს; მცდარ პაროლს — იმ რეჟიმის მიხედვით, რომელშიც სტენდი მუშაობს: `ALLOW_DEV_LOGIN=true`-ზე (ნაგულისხმევი) მცდარი პაროლი ექვსივე ანგარიშზე **გადის** (HTTP 200), სამაგიეროდ მოწმდება, რომ allow-list-ის გარეთ მისამართი 401-ს იღებს; `"false"`-ზე ექვსივე მცდარი პაროლი უნდა უარიყოს (401). ორივე რეჟიმში ბაზაში მოწმდება, რომ შენახული hash მცდარ პაროლს არ ემთხვევა;
- operator/content-admin/manager/system-admin `200/403` role gates-ს;
- manager-ის მხოლოდ ტექნიკურ leadership scope-ს;
- ცნობილ ქართულ სათაურებს `/api/search`-ით;
- quiz-ის წინ acknowledgment-ის `403`-ს, სწორი პასუხის შემდეგ pass/acknowledgment-ს და receipt/status bridge-ს;
- audit hash-chain-ს და synthetic unhashed ჩანაწერების არარსებობას.

შენიშვნა: API verification რეალურად შედის ანგარიშებში და ერთ live quiz/acknowledgment სცენარს ასრულებს, ამიტომ audit/activity-ში verification მოვლენები დაემატება. თვითონ baseline seeder-ის ხელახლა გაშვება marker-ის აღმოჩენისას მხოლოდ verification-only რეჟიმში მუშაობს და baseline duplicate-ებს არ ქმნის.

## ლოკალური QA პროფილები

სწრაფი, არამუტირებადი შემოწმება გაუშვით ყოველი მცირე ცვლილების შემდეგ:

```powershell
.\presentation.ps1 test quick
```

იგი ამოწმებს presentation preflight/health-ს, seeder-ის Python safety suite-ს,
Java DB-free unit suite-ს, Angular unit suite-სა და production build-ს.

სრული ფუნქციური regression მნიშვნელოვანი ცვლილების ან release candidate-ის წინ:

```powershell
.\presentation.ps1 test regression
```

სრული პროფილი დამატებით ასრულებს database/API verifier-ს, runtime RBAC/scope
მატრიცას, Maven-ის Oracle integration suite-ს ცალკე დროებით `MAGTI_QA` სქემაში
და presentation-ისთვის შერჩეულ Playwright persona flow-ებს. Oracle-ის QA პორტი მიბმულია მხოლოდ
`127.0.0.1:1523`-ზე. `MAGTI_QA` სქემა ყოველი გაშვების ბოლოს იშლება.

შერჩეული browser gate იყენებს ექვსივე რეალურ სადემო ანგარიშს და ფარავს login-ს,
role redirect-ებს, content/system admin-ის გამიჯვნას, manager scope-ს, დეპარტამენტულ
ხილვადობას, ძებნას/რჩეულებს, quiz/read gate-ს, version history-ს, კატეგორიებსა და
ვიდეოს navigation-ს. ძველი fixture-heavy სრული suite ცალკე დიაგნოსტიკური ბრძანებით
რჩება (`cd angular-frontend; npx playwright test`) და presentation gate-ს არ ერევა.

წარმატებული regression presentation volume-ს ერთხელ თავიდან აგებს, რათა
პრეზენტაცია კვლავ სუფთა baseline-ზე დარჩეს. ჩავარდნისას გარემო არ reset-დება —
შეცვლილი მდგომარეობა ინახება გამოძიებისთვის.

ყოველი გაშვების ანგარიში იქმნება:

```text
.presentation-artifacts/tests/<run-id>/
  summary.md
  results.json
  access-matrix.json
  logs/
  playwright-report/
```

ანგარიშები და token cache gitignored-ია; პაროლი ლოგებში არ იწერება.

ანგარიშები ინახება:

- `.presentation-artifacts/seed-report.json`;
- `.presentation-artifacts/sanitization-report.json`;
- `.presentation-artifacts/verification-report.json`;
- ყოველი pulse-ის `.presentation-artifacts/pulse-<uuid>.json`.

## 25–30-წუთიანი სცენარი

### 0–2 წუთი — კონტექსტი

1. გახსენით portal Chrome-ში 1080p-ზე.
2. თქვით, რომ 122 ცოდნის სტატია და 429 image ძველი ბაზიდან კონტროლირებადად არის გადმოტანილი; მომხმარებლები და ქმედებები სინთეტიკურია.
3. აჩვენეთ light/dark რეჟიმის გადართვა და Georgian UI.

მოსალოდნელი ეკრანი: login და შემდეგ მთავარი dashboard — შევსებული KPI-ებით, აქტიური broadcast-ით, reminders-ითა და ბოლო აქტივობით.

### 2–7 წუთი — სისტემური ადმინისტრატორი

შედით `admin@magti.ge`-ით.

1. გახსენით ორგანიზაციის სტრუქტურა: 3 დეპარტამენტი, 15 ჯგუფი, თითო ჯგუფში PRIMARY ლიდერი და ოპერატორები.
2. გახსენით მომხმარებლები: აჩვენეთ აქტიური და გათიშული ანგარიში, როლები და permission override.
   **წასულების გავლა (PO-24):** ფილტრში „ბოლო შესვლა“ აირჩიეთ „60 დღეზე მეტია არ შემოსულა“.
   მონიშვნის უჯრა ექნება 9 **ჯერ კიდევ აქტიურ** ანგარიშს (თითო დეპარტამენტიდან სამი), ვინც
   75 ან 130 დღეა არ შემოსულა; „90 დღეზე მეტი“ მათგან სამს დატოვებს. ეს სწორედ ის შემთხვევაა,
   რისთვისაც ფუნქცია არსებობს: ადამიანი წავიდა, ანგარიში არავინ გათიშა, და სავალდებულო მასალაში
   ვადაგადაცილებულად ითვლება. მონიშნეთ, დააჭირეთ „მონიშნულების გათიშვა“ და აჩვენეთ აუდიტში
   „ანგარიშების ჯგუფური გათიშვა“ უსაფრთხოების კატეგორიაში.

   ორი რამ გაითვალისწინეთ. **თარიღები reset-ის მომენტიდან ითვლება** — დემო ბერდება, და reset-იდან
   ორ კვირაში 75-დღიანებიც „90 დღეზე მეტში“ გადავლენ, ხოლო ადრე გათიშული 15 ანგარიში (უჯრის
   გარეშე) 60-დღიან სიაშიც გამოჩნდება; მნიშვნელოვანი პრეზენტაციის წინ გაუშვით `reset`.
   **დემოს შემდეგ გათიშულები ხელახლა გააქტიურეთ** (სტრიქონის ღილაკით), თორემ მომდევნო
   პრეზენტაციაზე საჩვენებელი აღარაფერი დარჩება.
3. გახსენით საერთო compliance/statistics dashboard: სხვადასხვა ჯგუფის განსხვავებული completion და მიზანმიმართულად დაბალი ჯგუფები.
4. გახსენით audit log: login/login-failed, content, compliance, reminder, broadcast, access და export კატეგორიები.
5. თუ UI-ში ხელმისაწვდომია, აჩვენეთ chain-health/verify შედეგი.

მოსალოდნელი შედეგი: admin ხედავს მთელ ორგანიზაციას; synthetic audit entries შეიცავს `synthetic=true` დეტალს და hash-chain healthy-ა.

### 7–14 წუთი — კონტენტ-ადმინი და CMS lifecycle

გამოდით და შედით `content@magti.ge`-ით.

1. ცოდნის ბაზაში მოძებნეთ `პერსონალურ მონაცემთა დაცვა` ან `მომსახურების სტანდარტი`.
2. გახსენით imported სტატიის history და diff; აღნიშნეთ, რომ შენარჩუნებულია source version/date და 132 ისტორიული snapshot.
3. გახსენით `სადემო მონახაზი — eSIM FAQ` და აჩვენეთ draft რედაქტირება.
4. აჩვენეთ `სადემო დაგეგმილი სტატია — ახალი პროცედურა` scheduled მდგომარეობაში.
5. Trash-ში გახსენით `სადემო წაშლილი მასალა — აღდგენის სცენარი`; აჩვენეთ restore flow, მაგრამ პრეზენტაციის სტაბილურობისთვის საბოლოო mutation სურვილისამებრ შეასრულეთ.
6. quiz admin-ში აჩვენეთ ორი სტატიის კითხვები/პასუხები.
7. აჩვენეთ 5 სიახლე და 3 ვიდეოინსტრუქცია.

მოსალოდნელი შედეგი: ძველი HTML უსაფრთხოდ ჩანს, local images იტვირთება, history/diff მუშაობს, draft/scheduled/trash ერთმანეთისგან მკაფიოდ განსხვავდება.

### 14–19 წუთი — ლიდერის scope

გამოდით და შედით `manager@magti.ge`-ით.

1. გახსენით team statistics და department statistics.
2. აჩვენეთ დროულად/გვიან/წაუკითხავი განაწილება და critical/low-compliance წევრები.
3. ხაზგასმით აღნიშნეთ, რომ ლიდერი ხედავს მხოლოდ საკუთარ ტექნიკურ leadership scope-ს; საინფორმაციო და ოფისის თანამშრომლების სახელები/ჯგუფები არ ჩანს.
4. გახსენით reminders ან audit-ის role-scoped ხედი, თუ მიმდინარე მენიუშია.

მოსალოდნელი შედეგი: manager-ის ეკრანზე არ უნდა გამოჩნდეს sibling departments; admin-ის ეკრანზე ისინი ჩანს.

### 19–25 წუთი — ოპერატორის სრული გზა

გამოდით და შედით `tech@magti.ge`-ით.

1. მოძებნეთ `მომსახურების სტანდარტი`.
2. გახსენით სტატია, აჩვენეთ embedded image/content, დაამატეთ რჩეულებში და პირადი note.
3. acknowledgment-ის წინ გაიარეთ quiz: სურვილისამებრ ჯერ არასწორი პასუხი, შემდეგ სწორი.
4. სწორი pass-ის შემდეგ დაადასტურეთ წაკითხვა; inbox/reminder-ში ნახეთ ცვლილება.
5. სწრაფად გადაერთეთ `info@magti.ge`-ზე და მოძებნეთ `პერსონალურ მონაცემთა დაცვა`, ან `nino@magti.ge`-ზე და მოძებნეთ `მობილური პორტირების პროცედურა`, რათა department visibility გამოჩნდეს.

მოსალოდნელი შედეგი: quiz pass-მდე acknowledgment იბლოკება; pass-ის შემდეგ receipt და compliance read status ერთსა და იმავე user/article version-ზე იცვლება.

### 25–28 წუთი — live pulse და განახლებული dashboard

ცალკე PowerShell ფანჯარაში გაუშვით:

```powershell
.\presentation.ps1 pulse
```

ეს 24 ოპერატორით რეალური Spring API-ების გავლით ასრულებს login/search/view/favorite/quiz/acknowledgment wave-ს. შემდეგ admin/manager dashboard refresh გააკეთეთ.

მოსალოდნელი შედეგი: views/search/quiz/read მაჩვენებლები იზრდება; pulse report-ში ჩანს შესრულებული მოთხოვნების რაოდენობა და dashboard-ის before/after snapshot.

### 28–30 წუთი — export და შეჯამება

1. admin/manager ანგარიშით გახსენით export-ready compliance/search/view/quiz მონაცემები.
2. საჭირო XLSX/PDF შექმენით UI-დან ხელით — baseline რეალურ ფაილს წინასწარ არ აყალბებს.
3. შეჯამეთ: imported KB, CMS lifecycle, RBAC/scope, compliance/quiz, reminders/broadcast, audit/export და live activity.

## სწრაფი fallback

| პრობლემა | მოქმედება |
|---|---|
| Docker CLI/daemon ვერ მოიძებნა | გაუშვით Docker Desktop, დაელოდეთ engine-ის მზადყოფნას და გაიმეორეთ `prepare` |
| source checksum/count განსხვავდება | არ გააგრძელოთ; დააბრუნეთ დამტკიცებული `magti_portal.db` და `uploads/` — guard-ის გამორთვა დაუშვებელია |
| Oracle-ის პირველი გაშვება ნელია | დაელოდეთ healthcheck-ს; `prepare` უსაფრთხოდ შეიძლება განმეორდეს |
| seeder marker უკვე არსებობს | ეს ნორმალურია: ის გადავა verification-only რეჟიმში და duplicate-ს არ შექმნის |
| verify ვერ გადის API/image/role gate-ზე | პრეზენტაცია არ დაიწყოთ; ნახეთ terminal error და `.presentation-artifacts/verification-report.json` |
| pulse-ის ნაწილი ვერ შესრულდა | baseline არ ზიანდება; გაუშვით `verify`, შემდეგ pulse ხელახლა |
| UI-ში კონკრეტული mutation უკვე შესრულებულია | გამოიყენეთ სხვა სადემო სტატია/ოპერატორი ან შეასრულეთ სრული `reset` |

თუ პრეზენტაციის დროს live pulse-ისთვის დრო არ არის, აჩვენეთ უკვე არსებული 30-დღიანი baseline dashboards — ძირითადი ფუნქციები წინასწარ სრულად შევსებულია.

## reset

სრული, სუფთა baseline-ის დასაბრუნებლად:

```powershell
.\presentation.ps1 reset
```

სკრიპტი წინასწარ აჩვენებს ზუსტ target volume-ს და ითხოვს სიტყვა `RESET`-ის აკრეფას. შემდეგ იგი შლის მხოლოდ `magti-portal-presentation-oracle-data` volume-ს, თავიდან აგებს presentation stack-ს, ტვირთავს baseline-ს და უშვებს verification-ს. სხვა Docker project/volume და არსებული local/production მონაცემები არ იშლება.

უბრალოდ გასაჩერებლად, მონაცემის შენარჩუნებით:

```powershell
docker compose --env-file .presentation.env -f docker-compose.presentation.yml stop
```
