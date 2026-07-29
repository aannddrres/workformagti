# Magti პორტალის მიგრაცია: FastAPI+PostgreSQL+Vanilla JS → Spring Boot+Oracle+Angular

| | |
|---|---|
| **სტატუსი** | სტრატეგიული გეგმა — დამტკიცებულია საწყისი კვლევის ფარგლები, ცალკეული ეტაპები მოითხოვს ცალკე დამტკიცებას |
| **წყარო-მოთხოვნა** | [`docs/MIGRATION_PROMPT_JAVA_ORACLE_ANGULAR.md`](MIGRATION_PROMPT_JAVA_ORACLE_ANGULAR.md) |
| **დაწერილია** | 2026-07-29, დამოუკიდებელი კოდის კვლევის საფუძველზე (არა წინა სესიის დაშვებებზე) |
| **სისტემის მდგომარეობა** | ჯერ არ არის production-ში — ცოცხალი მომხმარებლების/მონაცემების გარეშე |
| **სამიზნე Oracle ვერსია** | **19c** — შესწორებულია 21c-დან 2026-07-29-ს, IT დეპარტამენტის დადასტურებით (რეალურად Oracle 19c + Java Spring გამოიყენება) |
| **გუნდი** | 1 არა-ტექნიკური პროდუქტის მფლობელი + Claude Code; Java/Oracle-ექსპერტიზა კომპანიაშია ხელმისაწვდომი, მაგრამ არა უშუალოდ ამ სამუშაოზე |

> **წაკითხვამდე**: იხილეთ ბოლოს, სექცია „მასშტაბის რეალობა" — ეს არის დაახლოებით
> 28 000 ხაზი კოდის სამმაგი ერთდროული გადაწერა (ენა + ბაზა + ინტერფეისი),
> უსაფრთხოებით-კრიტიკულ სისტემაში. ეს დოკუმენტი გეგმავს **როგორ** გაკეთდეს
> უსაფრთხოდ, არა იმას, რომ ეს არის მცირე სამუშაო.

> **განახლება, იმავე დღეს:** IT დეპარტამენტმა დაადასტურა, რომ რეალურად
> გამოიყენება **Oracle 19c** (არა 21c) და **Java Spring**. ეს ცვლის ერთ
> კონკრეტულ ტექნიკურ დეტალს — §2.1-ის მე-9 პუნქტი (JSON სვეტები)
> განახლებულია შესაბამისად: 19c-ს native JSON ტიპი არ აქვს (ეს მხოლოდ
> 21c-დან არსებობს), ასე რომ საჭიროა `CLOB`/`VARCHAR2` + `CHECK (... IS
> JSON)` — ზუსტად ის „12c-ის ხრიკი", რასაც თავდაპირველი მოთხოვნა-დოკუმენტი
> აფრთხილებდა. დანარჩენ დასკვნებზე (მათ შორის იდენტიფიკატორების
> 128-სიმბოლოიან ლიმიტზე) გავლენა არ არის — ეს Oracle 12.2+-ის ყველა
> ვერსიაზე მოქმედებს, 19c-ს ჩათვლით.

---

## შესავალი: რა შეიცვალა თავდაპირველი მოთხოვნის მონაცემებში

მოთხოვნა ითხოვდა წინა სესიის ფაქტების დამოუკიდებელ გადამოწმებას. გავაკეთე ეს —
წავიკითხე ყოველი ციტირებული ფაილი, გავუშვი 3 პარალელური საკვლევი აგენტი
(ბაზა/Postgres-სპეციფიკა, Python/FastAPI-მექანიზმები, frontend-ინვენტარი) და
პირადად გადავამოწმე ყველა 11 ცნობილი ხარვეზი წყაროში.

**ერთი მნიშვნელოვანი შესწორება ცნობილ ხარვეზებში** (დეტალები §5-ში):
ბაგი #1 („create/update/delete არ წერს აუდიტ-ლოგში") **არასწორია**.
`audit_trail.py`-ში დარეგისტრირებული ORM-level listener-ები (`after_insert/
after_update/after_delete`) ავტომატურად წერენ CREATE/UPDATE/DELETE ჩანაწერებს
ყველა 6 აუდიტირებად მოდელზე, ველების დონის diff-ით, და ეს დადასტურებულია
ტესტით (`tests/test_audit_trail.py:32-86`). დოკუმენტაციაში (docstring-ебши)
მოძველებული აღწერაა დარჩენილი წინა რეფაქტორინგის შემდეგ — მაგრამ **რეალური
ქცევა სწორია**. ეს არსებითად ცვლის მიგრაციის გეგმას: Java-ვერსიას სჭირდება
ეკვივალენტური ავტომატური auditing-ფენა (Hibernate Envers ან custom
`@EntityListeners`), და არა ხელით `log_audit()` ზარების დამატება ყველა
create/update/delete endpoint-ზე.

ასევე გასწორებულია: „build-პროცესი არ არსებობს" → **CSS-ს აქვს build
(Tailwind CLI), JavaScript-ს არა.**

დანარჩენი 10 ცნობილი ხარვეზი დადასტურდა ზუსტად, ხოლო ერთი (#3) აღმოჩნდა
ნაწილობრივ არასწორი — დეტალები §5-ში.

---

# სექცია 1 — ფაზური მიგრაციის არქიტექტურული გეგმა

## რატომ ეს თანმიმდევრობა (Big-Bang-ის თავიდან აცილება)

REST API კონტრაქტი (123 endpoint, იხ. §4) არის ერთადერთი სტაბილური ნაკერი ამ
სისტემაში. ვანილა-JS frontend backend-ის შიგნეულობასთან (ORM, auth-მექანიზმი,
ბაზის დიალექტი) პირდაპირ კავშირში საერთოდ არ არის — მხოლოდ ამ 123 endpoint-ის
მეშვეობით ურთიერთქმედებს. ეს ნიშნავს, რომ **backend + ბაზა შეიძლება მთლიანად
შეიცვალოს frontend-ის ხელუხლებლად**, თუ ახალი Java-სერვისი ზუსტად იმეორებს
იმავე მოთხოვნა/პასუხის ფორმას.

საპირისპირო თანმიმდევრობა (ჯერ Angular, შემდეგ backend) ორი მიზეზით ცუდია:
1. **უსაფრთხოებით-კრიტიკული ნაწილი ბოლოში დარჩება** — აუდიტის ჰეშ-ჯაჭვი და
   RBAC ორივე backend/ბაზის ფენაშია. თუ ეს ბოლოსკენ გადავიდა, ყველაზე
   სახიფათო სამუშაო ხდება მაშინ, როცა გუნდი (ანუ მარტო ეს სესია) უკვე
   დაღლილია ორი დიდი ფაზის შემდეგ — ზუსტად საწინააღმდეგო იმისა, რასაც
   წესი #4 მოითხოვს.
2. **Angular-ს frontend-ის საკუთარი validation სჭირდება ცოცხალი API-ს
   მიმართ** — ორივე backend ერთდროულად რომ იცვლებოდეს, ნებისმიერი
   Angular-ის მხრიდან შეცდომა ვერ გაირჩევა "ჩვენი კომპონენტის ბაგია თუ
   Java-ს API-ს სხვაობააო".

დამატებით, სამივე ფენის ერთდროული ცვლილება (რასაც big-bang გულისხმობს)
სამმაგად ზრდის დებაგინგის სირთულეს ნებისმიერი შეუსაბამობისას — ვერ
გაირკვევა, ბაგი ბაზის თარგმანშია, Java-ლოგიკაშია თუ Angular-ის მხარეს.
ეტაპობრივი მიდგომა თითოეულ ცვლილებას ცალკე ამოწმებს.

## პროცესის ჩარჩო, რომელიც ვრცელდება ყველა ფაზაზე

ეს არ არის ერთხელ დაწერილი წესი — თითოეული ქვემოთხსენებული ფაზა მთავრდება
რეალურ გადამოწმებით (ტესტები + ცოცხალი დემო) და ჩემი მოკლე ანგარიშით, სანამ
შემდეგზე გადავალ. ესეც არის: სად სჭირდება Opus 4.8-ის დონის მსჯელობა
(სტრატეგიული გადაწყვეტილებები, უსაფრთხოება) და სად კარგად განსაზღვრული,
განმეორებადი კოდის წერაა (Sonnet 5-ისთვის ვარგისი) — თითოეული ფაზის თავში
მითითებულია.

---

## ფაზა 0 — საბაზისო წერტილის გაყინვა

**მოდელი: Opus-კლასის მსჯელობა** (გადაწყვეტილებები, არა კოდის მოცულობა).

| ნაბიჯი | რატომ |
|---|---|
| 0.1 — მიმდინარე WIP-ის დასრულება და commit | ✅ **დასრულდა** (commit `78db0fc`, 2026-07-29) — დეპარტამენტის მისადაგების კონსოლიდაცია 3 საიტზე (ბაგი #6-ის ნაწილი), ყველა 102 ტესტი მწვანე. დარჩენილი 3 საიტი (SSE/მესიჯინგი/აუდიტ-scope) განზრახ არ შეხებია — ცალკე გადაწყვეტილება სჭირდება (§5) |
| 0.2 — 123 endpoint-ის API-კონტრაქტის დაფიქსირება | ✅ **დასრულდა** (commit `74b676d`) — `scripts/export_api_contract.py` პირდაპირ კოდიდან (არა ხელით) აგენერირებს `docs/api-contract/openapi.json`-ს: 104 path, 122 ოპერაცია |
| 0.3 — Parity-ჰარნესის აწყობა | ✅ **პრაქტიკულად დასრულებულია** — `tests/parity/capture_golden_master.py`: **130 სცენარი, 121/122 ოპერაცია** (ერთადერთი განზრახ გამოტოვებული — `GET /api/stream`, SSE გრძელვადიანი კავშირია და არა request/response წყვილი; ცალკე, timeout-დაცული ტესტი სჭირდება, არა ეს capture-სკრიპტი). ამ პროცესმა თავად აღმოაჩინა **3 ცოცხალი მტკიცებულება**: ბაგი #3, ბაგი #10, და ახალი, აქამდე დაუდოკუმენტებელი ბაგი (იხ. §5, პუნქტი 16 — ლოგოს serving). ჯერ არ არსებობს Java-სერვისი შესადარებლად — ეს ეტალონია, რომელსაც მომავალში შევადარებთ |
| 0.4 — არსებული 4 661 ხაზი ტესტის კატალოგიზაცია | ✅ **დასრულდა** — [`docs/api-contract/test_acceptance_catalog.md`](api-contract/test_acceptance_catalog.md): ყველა 103 ტესტ-ფუნქცია (27 ფაილი), თითოეული — კონკრეტული ბიზნეს-წესით, არა მხოლოდ სახელით. ჯვარედინად გადამოწმებული `pytest --collect-only`-ის წინააღმდეგ — 103/103 დაფარული, არცერთი გამოტოვებული | 28 ტესტ-ფაილი გადაიქცევა line-by-line მიგრაციის მისაღებ სპეციფიკაციად — არცერთი ტესტ-scenario არ უნდა დაიკარგოს თარგმანში |

**გასასვლელი კრიტერიუმი**: `git status` სუფთაა, API-კონტრაქტი დაწერილია
ფაილად, parity-ჰარნესი მუშაობს არსებული (FastAPI-ვერსიის) წინააღმდეგ და
თანხმდება 100%-ით საკუთარ თავთან (sanity check).

---

## ფაზა 1 — Backend + ბაზა (Java + Oracle) არსებული API-ს მიღმა

ვანილა-JS frontend ამ მთელი ფაზის განმავლობაში **უცვლელი რჩება** და აგრძელებს
მუშაობას თავდაპირველი FastAPI-სერვერის წინააღმდეგ, სანამ Java-სერვისი არ
გავა production-სიმზადეს — მხოლოდ მაშინ გადაერთვება routing ორივეს შორის
(ან DNS/reverse-proxy დონეზე, ან feature-flag-ით).

### 1a — აუდიტის ჰეშ-ჯაჭვი (უსაფრთხოებით-კრიტიკული, პირველ რიგში)

**მოდელი: Opus-კლასი აუცილებელია.** ეს ერთადერთი ნაწილია, რომელიც
*მექანიკურ თარგმანს არ ექვემდებარება* — 6 დამოუკიდებელი Postgres-მექანიზმი
საჭიროებს ხელახალ არქიტექტურულ გადაწყვეტას Oracle-ზე (სრული ცხრილი — §2.1).

ნაბიჯები:
1. კანონიკური სტრიქონის ფუნქციის ხელახალი დაპროექტება (Oracle-ს არ აქვს
   row-ტიპის არგუმენტი ისე, როგორც Postgres-ს)
2. `DBMS_CRYPTO.HASH` + UTF-8 encoding-ის დადასტურება ქართული ტექსტისთვის
   (რისკი: თუ encoding არასწორია, ჰეში განსხვავებული გამოვა იდენტურ
   მონაცემზეც კი — ეს ჩუმად დაამტვრევს მთელ ჯაჭვს)
3. `pg_advisory_xact_lock` → `DBMS_LOCK.REQUEST` (საჭიროებს ცალკე grant-ს)
4. ორივე ნაწილობრივი ინდექსის ხელახალი დაპროექტება ფუნქციური ინდექსებით
5. **უსაფრთხოების ცალკე გადამოწმება ჩემთან ერთად, ტესტების გავლის მიღმა**
   (წესი #4) — მინიმუმ 3 scenario: (ა) ერთი ჩანაწერის ცვლილება post-hoc,
   (ბ) წინამორბედის წაშლა, (გ) ცრუ "genesis" ჩანაწერის ჩასმა შუაში.
   ეს ზუსტად ის 3 scenario-ა, რასაც უკვე ამოწმებს
   `tests/test_audit_trail.py:331-421` Postgres-ზე — იგივე უნდა გამეორდეს
   Oracle-ზეც, არა უბრალოდ "ტესტი გავიდაო".

**გასასვლელი კრიტერიუმი**: სამივე ტამპერ-სცენარი ვლინდება Oracle-ვერსიაზეც,
და თქვენი პირადი დადასტურება ცალკე, დემოთი.

### 1b — სქემის თარგმანი (30 ცხრილი, 202 სვეტი)

**მოდელი: Opus საწყისი გადაწყვეტილებებისთვის, Sonnet — მექანიკური გადატანისთვის** (თითოეული ცხრილი ერთი და იმავე ნიმუშს იმეორებს, მას შემდეგ რაც პირველი 2-3 დამტკიცდება).

კონკრეტული, გადასაწყვეტი საკითხები (თითოეული ცალკე დასამტკიცებელია — §2.1):
- 68 უსიგრძო `String` სვეტს სჭირდება ცხადი სიგრძის მინიჭება (Oracle
  `VARCHAR2`-ს სიგრძე სავალდებულოა) — თითოეულისთვის რეალური მონაცემიდან
  გამომდინარე მაქსიმუმის შერჩევა
- 15 Boolean სვეტი → `NUMBER(1)` (Oracle 21c-ს SQL-დონის BOOLEAN არ აქვს
  ცხრილის სვეტებისთვის)
- 2 JSON სვეტი (`users.permissions`, `users.last_categories_viewed_at`) →
  **⚠️ შესწორებულია (19c):** native JSON ტიპი მხოლოდ Oracle 21c-დან
  არსებობს — 19c-ზე საჭიროა `CLOB`/`VARCHAR2` + `CHECK (col IS JSON)`
  constraint, `JSON_VALUE()`-ით წაკითხვით
- 25 `DateTime` სვეტი, ყველა naive (Tbilisi UTC+4, tzinfo მოხსნილი
  `database.py`-ში) → Oracle `TIMESTAMP(6)` (არა უბრალო `DATE`, რომელიც
  წამებამდე იჭრება — ეს დაარღვევდა ჰეშ-ჯაჭვის მიკროწამურ ფორმატს)
- 4 ცხრილს სჭირდება "id არასდროს არ მეორდება" გარანტია
  (`sqlite_autoincrement` პარამეტრის Oracle-ეკვივალენტი — `GENERATED
  ALWAYS AS IDENTITY`), რადგან `audit_logs.item_id` არის უტიპო soft
  reference — id-ის ხელახალი გამოყენება ჩუმად არასწორ ობიექტს მიაბამდა
  ძველ აუდიტ-ჩანაწერს
- 12 ინდექსის სახელი აჭარბებს ან უახლოვდება 30-სიმბოლოს ლიმიტს — Oracle
  12.2+-ზე (19c-ს ჩათვლით, 128-სიმბოლოიანი ლიმიტი) ეს აღარ არის პრობლემა,
  მაგრამ ღირს კონსისტენტური სახელების დატოვება მომავალი პორტაბელურობისთვის

### 1c — ძებნა: `pg_trgm` → Oracle Text

**მოდელი: Opus** (ტექნოლოგიური არჩევანი, არა უბრალო თარგმანი).

ეს არ არის კონფიგურაციის ცვლილება — Oracle Text სრულიად სხვა ტექნოლოგიაა
(`CONTEXT` ინდექსი + `CONTAINS()`), საკუთარი tuning-ით. კონკრეტულად
დასაზუსტებელი: `routers/search.py`-ის `ILIKE`-დაფუძნებული რელევანტობის
qqქულირება (სათაური×10, ტეგები×5, კონტენტი×1) არ გადადის უცვლელად
`CONTAINS()`-ზე — საჭიროა cross-engine რელევანტობის შედარება (§3).

### 1d — Auth + RBAC

**მოდელი: Opus** (უსაფრთხოებით-კრიტიკული — წესი #7).

**◐ დაწყებულია (commit `3ea69ab`, 2026-07-29):** Spring Boot 4.1.0-ის
ჩონჩხი აშენდა (`java-backend/`, ძველი Python-კოდის გვერდით, არ ერევა).
`JwtService`-მა პორტირება გაუწია `security.py`-ის ტოკენის მექანიზმს ზუსტად
(HS256, იგივე claim-ფორმა). გადამოწმებულია: კომპილირდება, ეშვება, `GET
/api/health` პასუხობს. **ბაზა ჯერ საერთოდ არ არის დაკავშირებული** — ეს
თქვენი გადაწყვეტილებაა (Docker+WSL2 საჭიროებდა გადატვირთვას, გადავდეთ).
რეალური per-request ავტორიზაცია და ყველა endpoint ამ გადაწყვეტილებას ელოდება.

⚠️ **პორტირებისას აღმოჩენილი ახალი, ცოცხალი რისკი**: JJWT (Java-ის
ბიბლიოთეკა) მკაცრად ითხოვს HS256-ისთვის მინიმუმ 256-ბიტიან (32-ბაიტიან)
გასაღებს და ამის ქვემოთ `WeakKeyException`-ს აგდებს; python-jose ამას
საერთოდ არ ამოწმებს. **რეალური production `SECRET_KEY`-ის სიგრძე უნდა
გადამოწმდეს**, სანამ გადართვა მოხდება — თუ 32 ბაიტზე მოკლეა, Java-ვერსია
საერთოდ ვერ ჩაირთვება.

კარგი ამბავი ჯერ: bcrypt ჰეშები **პორტირებადია უცვლელად** — Spring
Security-ის `BCryptPasswordEncoder` კითხულობს არსებულ ჰეშებს პირდაპირ,
პაროლის იძულებითი აღდგენა არავის სჭირდება (სისტემა ჯერ production-ში
არ არის, ასე რომ ეს პრაქტიკულად მოიხსნა როგორც რისკი, მაგრამ მექანიზმი
მაინც უნდა იმუშაოს სწორად მომავლისთვის).

გადასაწყვეტი, ჯერ არ გადაწყვეტილი:
- **ორი პარალელური RBAC-კატალოგი** (ცნობილი ხარვეზი #5) — 8 წერტილიანი
  (`articles.view` ტიპის) permission `User.permissions`-ში vs 13
  ორწერტილიანი (`content:archive` ტიპის) კატალოგი DB-ცხრილებში, რომლებიც
  **არასდროს ემთხვევა** ერთმანეთს გარდა ერთი გამონაკლისისა
  (`system:audit`, რომელიც განზრახ დაემთხვა). Java-ზე გადასვლისას ეს
  **უნდა** გაერთიანდეს ერთ კატალოგში — ორი პარალელური სისტემის ატანა
  ახალ ენაზეც აზრი არ აქვს. რეკომენდაცია: შეინარჩუნეთ ერთი, დოტ-notation
  კატალოგი (8+ permission, `system:audit`-ის ჩათვლით გადარქმეული
  `system.audit`-ად), რადგან ის აქტიურად გამოიყენება წერტილოვან
  endpoint-ებზე; ორწერტილიანი კატალოგის დანარჩენი 12 permission
  გადაისინჯოს საჭიროებისამებრ.
- ორი დამოუკიდებელი roles-სია (`security.VALID_ROLES` vs
  `compliance_utils.MANAGEMENT_ROLES`) სხვადასხვა დანიშნულებით
  (წვდომის კონტროლი vs სტატისტიკური გამორიცხვა) — ეს ორივე უნდა
  გადავიდეს, უბრალოდ ცხადად დოკუმენტირებული განსხვავებული დანიშნულებით

### 1e — დანარჩენი დომენები, დომენების მიხედვით (Sonnet-კლასი უკვე გამართული ნიმუშისთვის)

დომენების რიგი რისკის კლებადობით: **Content (Articles/News/Videos/
Categories) → Compliance/Stats → Audit/Messaging/Exports**. თითოეული
დომენი ცალკე ეტაპია, საკუთარი parity-ტესტით და ცოცხალი დემოთი, ზუსტად
ისე, როგორც 14-როუტერიანი გაყოფა ადრე გაკეთდა ამ პროექტში.

განსაკუთრებული ყურადღება:
- **SSE real-time** (`state.py`-ის Redis broker) — `asyncio.
  run_coroutine_threadsafe`-ის მექანიზმს Java-ში საერთოდ არ სჭირდება
  ეკვივალენტი (Spring servlet thread-ებზეა აგებული) — მაგრამ bounded
  drop-oldest queue (64 ზომის) და "admin ხედავს ყველაფერს" ფილტრი
  ცხადად უნდა გამეორდეს, თორემ ჩუმად დაიკარგება
- **Export-ჯობების TTL** (ცნობილი ხარვეზი #9) — ველი იწერება, არასდროს
  იკითხება; Java-ვერსიას რეალურად სჭირდება scheduled reaper (`@Scheduled`),
  რაც ახალი ქცევაა, არა თარგმანი — ცალკე უნდა აღინიშნოს, რომ ეს
  დამატებაა და არა ხარვეზის „ერთგული" გადატანა
- **Rate limiting** — დღეს in-memory, 4 gunicorn worker-ზე გაყოფილი
  (ეფექტური ლიმიტი 4x ნომინალურზე). Java+Bucket4j+Redis რეალურად
  **გამკაცრებდა** ამ ლიმიტს — ეს განზრახ ქცევის ცვლილებაა, არა ბაგი

---

## ფაზა 2 — Parity ვალიდაციის კარიბჭე

Angular არ იწყება, სანამ Parity-ჰარნესი (§3) არ აჩვენებს 100%-იან თანხმობას
ყველა 123 endpoint-ზე, ცნობილი/დაშვებული განსხვავებების outside-ით
(allowlist, §3-ში). ეს ფაზა თავისთავად არ ატარებს ახალ კოდს — ეს არის
show-stopper შემოწმება.

---

## ფაზა 3 — Angular

**მოდელი: Opus არქიტექტურისთვის (routing-ის დაშლა, state-მართვის დიზაინი),
Sonnet — კომპონენტი-კომპონენტზე თარგმანისთვის.**

### რატომ არ არსებობს ინკრემენტული გზა (დადასტურებულია რიცხვებით)

- **299 inline event-handler** (`onclick=`, `onchange=` და ა.შ.), საიდანაც
  173 პირდაპირ HTML-ში და 126 JS-გენერირებულ სტრიქონებში
- **`app-router.js` პოულობს ნავიგაციის ბმულებს `onclick`-ატრიბუტის
  ტექსტის წაკითხვით** (`a[onclick*="pageId"]`) — ანუ inline-ჰენდლერების
  ეტაპობრივი მოცილება **პირველივე ნაბიჯზე ანგრევს როუტინგს**
- **249 გლობალური ფუნქცია + 290 განსხვავებული `window.*` — ერთ საერთო
  scope-ში** (0 ES module მთელ frontend-ში) — არ არსებობს ბუნებრივი
  ზღვარი, სადაც კომპონენტი გამოეყოფა დანარჩენს
- **285 `innerHTML`/`insertAdjacentHTML` საიტი**, უდიდესი ერთი ფუნქცია
  (`ensureArticleModal`, `app-core.js:2780-2975`) აგენერირებს 191
  ხაზიან HTML string-ს ერთბაშად

ამიტომ ფაზა 3 თავად იყოფა ორ ქვე-ფაზად:

### 3a — საძირკველი (build tooling + i18n ინფრასტრუქტურა)

დღეს **არ არსებობს** JS-build (0 `package.json`), მაშინ როცა CSS-ს აქვს
(Tailwind CLI). Angular-ს სჭირდება სრული build-ჯაჭვი ნულიდან. ასევე,
**1 230 ხაზი ქართული ტექსტი 11 ფაილში, ყოველგვარი i18n-ინფრასტრუქტურის
გარეშე** — `@angular/localize`-ზე ან ngx-translate-ზე გადასვლამდე ეს
ტექსტი უნდა ამოიღოს კოდიდან, მათ შორის Python-მხარეს (`routers/auth.py`-ის
inline SSO-გვერდი, HTTPException detail-ები).

### 3b — Shell + Routing ხელახალი აწყობა

`app-router.js`-ის ჩანაცვლება Angular Router-ით ცალკე ეტაპია, სანამ
ცალკეული გვერდები გადადის — 11 ძირითადი გვერდი + 7 admin ქვე-პანელი
+ 5 პროფილის ტაბი = ~23 მისამართი.

### 3c — დომენი-დომენზე კომპონენტების პორტი

იგივე დომენური თანმიმდევრობა, რაც Backend-ში (§1e).

### 3d — მესამე-მხარის ბიბლიოთეკების ჩანაცვლება

| ბიბლიოთეკა | დღევანდელი ვერსია | სტატუსი |
|---|---|---|
| Quill (rich-text) | 1.3.7 | **EOL, მაღალი რისკი** — 345 ხაზი ხელნაკეთი გაფართოება (`admin-cms-enhancements.js`) სრულად გადასაწერია ngx-quill/Quill 2-ზე |
| Chart.js | დაუფიქსირებელი ვერსია | დაბალი რისკი — ng2-charts თითქმის პირდაპირი ჩანაცვლებაა, მხოლოდ 3 გამოძახება |
| Flatpickr | დაუფიქსირებელი ვერსია | საშუალო რისკი — Angular-ეკვივალენტი მოსაძებნია |
| ხელნაკეთი SVG progress-ring, dept-dashboard.js (479 ხაზი), audit-dashboard.js (545 ხაზი) | — | **სრული ხელახალი წერა** — Angular-კომპონენტებად, არა თარგმანი |

---

# სექცია 2 — ტექნიკური რისკებისა და ტრანსლაციის მატრიცა

## 2.1 — PostgreSQL → Oracle

| # | მიმდინარე მექანიზმი (ფაილი:ხაზი) | სამიზნე Oracle-მექანიზმი | კონკრეტული რისკი | შერბილების სტრატეგია |
|---|---|---|---|---|
| 1 | `audit_logs_canonical_string(r audit_logs)` — row-ტიპი არგუმენტად, `migrate.py:118-136` | ეკვივალენტი არ არსებობს | ფუნქციის სრული ხელახალი დაპროექტება საჭირო | ცალკე დაპროექტება, არა თარგმანი — §1a |
| 2 | `E'\x1f'`/`E'\x00'` სენტინელები კანონიკურ სტრიქონში | `CHR(31)`/`CHR(0)` | `CHR(0)` `VARCHAR2`-ში დამაზიანებელია ზოგ driver-ში | დატესტვა რეალურ Oracle instance-ზე უსაფრთხოების გადამოწმებამდე |
| 3 | `pg_advisory_xact_lock(72710060142)` — ტრანზაქცია-scoped, ავტო-გათავისუფლებადი | `DBMS_LOCK.REQUEST` | არ ავტო-თავისუფლდება commit-ზე ისე ბუნებრივად; საჭირო ცალკე grant | ხელით `RELEASE` ან ტრიგერის ბოლოში საკუთარი cleanup |
| 4 | `digest(str, 'sha256')` (pgcrypto) | `DBMS_CRYPTO.HASH(...,  DBMS_CRYPTO.HASH_SH256)` | ქართული ტექსტის encoding — თუ byte-representation სხვაობს, ჰეში დაირღვევა უხმაუროდ | UTF-8 byte-level შედარება ტესტში, არა მხოლოდ ლათინური ტესტ-მონაცემი |
| 5 | 2 ნაწილობრივი ინდექსი (`WHERE row_hash IS NULL`, `WHERE prev_hash IS NULL AND row_hash IS NOT NULL`) | ფუნქციური ინდექსი `CASE WHEN...END`-ზე | Oracle-ს არ აქვს `WHERE`-იანი ინდექსი | ინდექსის გადაპროექტება, ქცევის ტესტით დადასტურება |
| 6 | ცარიელი სტრიქონი ≠ NULL (`COALESCE(col, E'\x00')`) | ცარიელი სტრიქონი == NULL Oracle-ში | ჯაჭვის კანონიკური სტრიქონის სემანტიკა შეიცვლება ჩუმად | ცხადი placeholder ყველგან (არა დაყრდნობა ცარიელ სტრიქონზე) |
| 7 | 68 უსიგრძო `String` სვეტი | `VARCHAR2(N)` — სიგრძე სავალდებულო | SQLAlchemy Oracle-დიალექტი პირდაპირ ჩავარდება `create_all()`-ზე | თითოეულის რეალურ მონაცემზე დაფუძნებული სიგრძის შერჩევა |
| 8 | 15 Boolean სვეტი, `DEFAULT 1`/`DEFAULT 0`, ~30 `== True/False` query-ფილტრი | `NUMBER(1)` | დღევანდელი DDL სტრიქონები (`BOOLEAN DEFAULT 1`) Oracle-ინვალიდურია | ORM level-ზე Boolean→NUMBER(1) mapping, query-ების გადამოწმება |
| 9 | 2 JSON სვეტი (`users.permissions`, `users.last_categories_viewed_at`) | **Oracle 19c**: `CLOB`/`VARCHAR2` + `CHECK (...IS JSON)` (native JSON ტიპი მხოლოდ 21c-დან არსებობს) | `migrate.py` ერთ ადგილას ამატებს ერთ-ერთს როგორც TEXT-ს — შეუსაბამობა უკვე დღესაც | ორივეს ერთგვაროვნად `CHECK IS JSON` constraint-ით განსაზღვრა; `JSON_VALUE()`-ით წაკითხვა |
| 10 | 25 naive `DateTime` სვეტი, Tbilisi UTC+4 hardcoded (`database.py:69-71`) | `TIMESTAMP(6)` | `DATE`-ზე გადაყვანა წაშლიდა მიკროწამებს — დაარღვევდა ჰეშ-ჯაჭვის ფორმატს | `TIMESTAMP(6)`, არა `DATE` |
| 11 | `pg_trgm` GIN ინდექსები (7) + `ILIKE` (27 საიტი) | Oracle Text `CONTEXT` ინდექსი + `CONTAINS()` | სრულიად სხვა ტექნოლოგია, ცალკე tuning; `CASE`-ში ჩაშენებული `ILIKE`-რელევანტობის ქულირება არ გადადის პირდაპირ | ცალკე ტექნიკური spike + რელევანტობის cross-engine შედარება (§3) |
| 12 | 4 ცხრილი `sqlite_autoincrement`-ის „id არასდროს არ მეორდება" გარანტიით | `GENERATED ALWAYS AS IDENTITY` | `audit_logs.item_id` უტიპო reference-ია — id-ის ხელახალი გამოყენება ჩუმად არასწორ ისტორიულ ჩანაწერს მიაბამდა | იდენტიფიკატორის non-reuse გარანტიის ცხადი ტესტი |
| 13 | 12 ინდექსის სახელი 30+ სიმბოლო | Oracle 12.2+ (19c-ს ჩათვლით): 128-სიმბოლოიანი ლიმიტი | — | მოგვარებულია — 19c უკვე აკმაყოფილებს ამ ლიმიტს |
| 14 | `information_schema.columns` (migrate.py-ის schema introspection) | `USER_TAB_COLUMNS`/`ALL_TAB_COLUMNS` | სქემა-introspection კოდი დიალექტ-სპეციფიკურია | Java-migration ჩარჩო (Flyway/Liquibase) აბსტრაჰირებს ამას სრულად |
| 15 | `func.date_trunc()` vs `func.strftime()` dialect branch (`routers/stats.py:839-877`) | `TRUNC(col, 'HH')` | მესამე დიალექტ-შტოს დამატება საჭირო ნებისმიერ დარჩენილ Postgres/SQLite ლოგიკაში | ყოველი dialect-branch წერტილის აღმოჩენა და მესამე შტოს გატესტვა |
| 16 | ცარიელი სტრიქონი vs NULL ზოგადად (`main.py:295`-ის `tags != ''`) | Oracle-ში `''` == `NULL` | ეს კონკრეტული predicate always-false გახდება | ყოველი `!= ''`/`== ''` შედარების აუდიტი მთელ კოდზე |

## 2.2 — Python/FastAPI → Java/Spring Boot

| # | მიმდინარე მექანიზმი | Java/Spring ეკვივალენტი | კონკრეტული რისკი | შერბილების სტრატეგია |
|---|---|---|---|---|
| 1 | ORM auto-audit listener — `after_insert/update/delete` მოვლენები 6 მოდელზე, attribute-history introspection-ით (`audit_trail.py`) | Hibernate Envers ან custom `@EntityListeners` + reflection | **სტრუქტურულად განსხვავებული მოდელი** — Envers ცალკე `_AUD` ცხრილებს ქმნის, custom listener-ს ხელით სჭირდება `_SECRET_COLUMNS`/`_SKIPPED_COLUMNS`/200-სიმბოლოიანი truncation-ის ხელახალი დაწერა | შეგნებული არქიტექტურული არჩევანი (არა default) — რეკომენდაცია: custom listener, არა Envers, რადგან საჭიროა ზუსტად იგივე `details` JSON-ფორმატი, რასაც აუდიტის დღევანდელი UI კითხულობს |
| 2 | Actor identity ContextVar-ით, async middleware-დან (`main.py`-ის `actor_context_middleware`) | `ThreadLocal` / Spring `RequestContextHolder` | **სინამდვილეში გამარტივება** — Spring MVC thread-per-request-ია, ასე რომ FastAPI-ს threadpool-ის კონტექსტ-კოპირების პრობლემა საერთოდ არ არსებობს Java-ში | დარწმუნდით, რომ "actor არ არსებობს ⇒ აუდიტ-ჩანაწერი არ იწერება" სემანტიკა შენარჩუნებულია განზრახ, თორემ background-ჯობები FK-ზე ჩავარდება |
| 3 | bcrypt ჰეშირება, passlib + `bcrypt==4.0.1` | Spring Security `BCryptPasswordEncoder` | დაბალი — bcrypt ფორმატი portable | პირდაპირი გადამოწმება ტესტ-ჰეშებზე |
| 4 | JWT (python-jose, HS256, 60წთ) | `io.jsonwebtoken` (jjwt) ან Spring Security OAuth2 | დაბალი — სტანდარტული HS256 | secret-ის და algorithm-ის იდენტურობის დადასტურება |
| 5 | ორი disjoint RBAC-კატალოგი (8 წერტილიანი + 13 ორწერტილიანი, `security.py:372-381`-ში თვითონ დოკუმენტირებული) | ერთიანი Spring `@PreAuthorize`-კატალოგი | **ეს ბაგი არ უნდა გადავიდეს Java-ში** — გაერთიანება სავალდებულოა, მიგრაცია ამის კარგი შესაძლებლობაა | გაერთიანების გადაწყვეტილება საჭიროებს თქვენს დადასტურებას (§5) |
| 6 | `require_permission()` — `@lru_cache`-ით memoized dependency-factory, FastAPI-ის object-identity dependency-რეზოლუციისთვის | `@PreAuthorize("hasAuthority(...)")` + custom SpEL `exclude_roles`-ის კარვ-აუთისთვის | caching-ის საჭიროება საერთოდ ქრება Java-ში (Spring არ დამოკიდებულია object identity-ზე) | `exclude_roles` პარამეტრი (მენეჯერი მფლობელობს permission-ს, მაგრამ კონკრეტულ endpoint-ზე მაინც უარყოფილია) — საჭირო custom voter ან SpEL |
| 7 | Redis-ზე დაფუძნებული SSE broker, `asyncio.run_coroutine_threadsafe` sync→async ხიდით (`state.py`) | Spring `SseEmitter`/WebFlux `Flux<ServerSentEvent>` + Spring Data Redis `RedisMessageListenerContainer` | ხიდის მექანიზმს Java-ში ეკვივალენტი არ სჭირდება (publish სინქრონულია servlet thread-იდან) — მაგრამ 64-ზომის drop-oldest queue და "admin ხედავს ყველაფერს" ფილტრი ხელით უნდა გამეორდეს | ცალკე unit-ტესტი queue overflow-ის და admin-override-ის ქცევაზე |
| 8 | ქართული გლიფების PDF (reportlab + DejaVu Sans, `routers/exports.py:172-243`) | iText ან PDFBox + იგივე ფონტის ხელახალი ჩაშენება | ფონტის registration path (`static/fonts/DejaVuSans.ttf` fallback ჯაჭვი) უნდა გამეორდეს ზუსტად | ვიზუალური შედარება ორივე PDF-ძრავაზე იდენტურ input-ზე (§3) |
| 9 | Rate limiting — slowapi, in-memory, per-worker (`state.py:76-78`) | Bucket4j ან Resilience4j `RateLimiter`, სავარაუდოდ Redis-backed | **ქცევის განზრახ ცვლილება** — Redis-backed ლიმიტერი 4x მკაცრი გახდება დღევანდელთან შედარებით (დღეს 4 worker × ნომინალური ლიმიტი) | ეს უნდა აღინიშნოს როგორც შეგნებული გაუმჯობესება, არა გვერდითი ეფექტი — თქვენი დადასტურება საჭირო |
| 10 | Pydantic v2 `field_validator` (schema-ფენა კითხულობს security-ფენას, `schemas.py:11`) | Bean Validation `@Constraint` + custom `ConstraintValidator` | დამოკიდებულების მიმართულება ინვერტირებულია (schema→security) — Java-ში ეს ბუნებრივად გასწორდება Service-ფენის validation-ით | — |
| 11 | `Article.read_time` — `@property`, საკუთარ load-state-ს ამოწმებს (`sa_inspect(self).unloaded`) რომ არ გამოიწვიოს lazy-load bulk query-ებში | `Hibernate.isPropertyInitialized()` + `@Basic(fetch=LAZY)` + bytecode enhancement | უფრო მყიფეა Java-ში — bytecode enhancement-ის კონფიგურაცია არატრივიალურია | ტესტი: bulk-list query არ უნდა გამოიწვიოს სრული content-ის fetch-ი |
| 12 | Export-worker: primitive rows only, საკუთარი DB session (`routers/exports.py:347-380`) | `@Async` + ცალკე transaction | request-ის session-ის დახურვის შემდეგ მუშაობის ნიმუში იგივეა Java-შიც | — |

## 2.3 — Vanilla JS → Angular

| # | მიმდინარე მდგომარეობა (რიცხვები) | Angular ეკვივალენტი | კონკრეტული რისკი | შერბილების სტრატეგია |
|---|---|---|---|---|
| 1 | 0 ES module, 249 გლობალური ფუნქცია, 290 `window.*` სახელი ერთ scope-ში | Component + Service არქიტექტურა | ბუნებრივი ზღვარი არ არსებობს კომპონენტების გამოსაყოფად — 7,041-ხაზიანი `app-core.js` ერთი მონოლითია | დომენების მიხედვით ხელით დაყოფა, იმავე თანმიმდევრობით, რაც backend-ს (§1e) |
| 2 | 299 inline event-handler (173 HTML-ში, 126 JS-სტრიქონებში) | `(click)="handler()"` binding | routing კითხულობს `onclick`-ატრიბუტს ტექსტობრივად — ჯაჭვური დამოკიდებულება | routing-ის ჯერ გადაწერა (3b), მერე handler-ების მოცილება |
| 3 | 285 `innerHTML`/`insertAdjacentHTML` საიტი, უდიდესი 343-ხაზიანი (`openArticleModal`) | Angular template + `*ngIf`/`*ngFor` | XSS-სანიტაცია (DOMPurify, 10 საიტი) საჭიროებს გადამოწმებას Angular-ის built-in sanitization-თან შედარებით | ვერიფიკაცია: ყოველი ადრინდელი DOMPurify-წერტილი გადამოწმდეს, რომ Angular-ის ტემპლეიტ-სანიტაცია ფარავს |
| 4 | 118 `fetch()` საიტი, მხოლოდ 15 (12.7%) გადის wrapper-ში; `window.fetch` monkeypatch cache-ინვალიდაციისთვის (`frontend_api.js:35-45`) | `HttpClient` + `HttpInterceptor` | monkeypatch-ის ქცევა (POST/PUT/DELETE/PATCH-ზე ავტომატური cache-გასუფთავება) უხმაუროდ დაიკარგება, თუ ცხადად არ გადაწერილა interceptor-ად | ცხადი `HttpInterceptor` cache-invalidation-ისთვის, unit-ტესტით |
| 5 | Auth token: localStorage-ში მხოლოდ header+payload (ხელმოწერის გარეშე), რეალური credential — httpOnly cookie | Angular `HttpInterceptor` + `withCredentials: true` | კონცეპტი იგივეა, დეტალები (რომელი headers, cookie flags) ზუსტად უნდა გადავიდეს | ტესტი: expired-token redirect ქცევა იდენტურია |
| 6 | Quill 1.3.7 + 345 ხაზი ხელნაკეთი გაფართოება (drag-drop upload, character counter, markdown shortcuts, slash-menu) | ngx-quill + Quill 2 | **EOL ბიბლიოთეკა, API შეცვლილია (Delta/clipboard/register)** | სრული გადაწერა, არა თარგმანი — ცალკე ვადის შეფასება |
| 7 | Chart.js, დაუფიქსირებელი ვერსია, 3 გამოძახება | ng2-charts | დაბალი რისკი | პირდაპირი ჩანაცვლება |
| 8 | ხელნაკეთი SVG progress-ring, dept-dashboard.js (479 ხაზი), audit-dashboard.js (545 ხაზი) | Angular კომპონენტები | ეს "თარგმანი" არაა — სრული ხელახალი წერა ბიზნეს-ლოგიკის შენარჩუნებით | თითოეული ცალკე ეტაპად, ორიგინალის ქცევის ჩამონათვალით მისაღებ კრიტერიუმად |
| 9 | 0 build-tool (package.json არ არსებობს), მანუალური `?v=` cache-busting (8 სხვადასხვა ვერსია-სტრიქონი ხელით) | Angular CLI + webpack | სრული საწყისი აწყობა ნულიდან | ფაზა 3a |
| 10 | 1 230 ხაზი ქართული ტექსტი ჩაშენებული 11 ფაილში (frontend + backend), i18n-ინფრასტრუქტურის გარეშე | `@angular/localize` ან ngx-translate | ტექსტის amoკრეფა თავად დიდი სამუშაოა | ცალკე ეტაპი (3a), არა თანდაყოლილი Angular-ტემპლეიტების წერასთან ერთად |
| 11 | `article.html`-ს აქვს `Auth` ობიექტისა და `api()` wrapper-ის **დუბლიკატი** (თავად `app-core.js`-საგან დამოუკიდებელი ასლი) | ერთი გაზიარებული `AuthService` | ორი განსხვავებული auth-კოდის ასლი დღესაც არსებობს — რომელი დავუშვათ "სწორად"? | გადაწყვეტილება საჭირო, თუ რომელი ასლი გახდეს წყარო |
| 12 | `input.css` — 1 382 ხაზი ხელნაწერი Tailwind-გაფართოება (5 card-theme ვარიანტი × light/dark, 13 custom CSS property) | Angular-ში SCSS/CSS ფენა უცვლელად შენარჩუნებადია | დაბალი რისკი — ეს ფენა თითქმის ბიბლიოთეკა-დამოუკიდებელია | პირდაპირი გადატანა, მინიმალური ცვლილებით |

---

# სექცია 3 — გადამოწმებისა და ტესტირების ჩარჩო (Verification Protocol)

## 3.1 — API Parity Testing

**მექანიზმი**: pytest + httpx-ზე დაფუძნებული სკრიპტი (არსებული ტესტ-ინფრასტრუქტურის
გაგრძელება — `tests/conftest.py`-ის factory-ლოგიკის ხელახლა გამოყენებით),
რომელიც:
1. თესლავს **ორივე** backend-ს (FastAPI + Spring Boot) იდენტური fixture
   მონაცემებით
2. უგზავნის იმავე HTTP მოთხოვნას პარალელურად ორივეს
3. ადარებს: HTTP status კოდს, პასუხის სტრუქტურას (key-by-key), მნიშვნელობებს,
   და მნიშვნელოვან header-ებს (`Content-Type`, `Content-Disposition` export-ებზე)

**Allowlist ცნობილი, განზრახ განსხვავებებისთვის** (რომ diff-ტესტმა ცრუ-negative
არ დააფიქსიროს):
- Rate-limit ლიმიტების სიმკაცრე (§2.2, პუნქტი 9 — Java-ვერსია მკაცრია)
- Export-jobs TTL-ის რეალური აღსრულება (§2.2-ის ცნობილი ხარვეზი #9 — თუ
  Java-ვერსიაში დაემატა reaper)
- ნებისმიერი RBAC-კატალოგის გაერთიანების შედეგი (§2.2, პუნქტი 5)
- SQL Error-პასუხების ზუსტი ტექსტი (DB driver-სპეციფიკურია ორივე მხარეს)

## 3.2 — მონაცემთა და უსაფრთხოების მთლიანობა

**(ა) bcrypt-ჰეშების რეალური გადამოწმება**: ყოველი seed/test ანგარიშისთვის,
დაადასტურეთ რომ `BCryptPasswordEncoder.matches()` წარმატებით ამოწმებს
passlib-ით შექმნილ ჰეშს, პაროლის ხელახლა დაყენების გარეშე.

**(ბ) აუდიტის ჰეშ-ჯაჭვის მთლიანობა Oracle-ზე** — გაიმეორეთ ზუსტად ის
scenario-ები, რასაც `tests/test_audit_trail.py:331-421` უკვე ამოწმებს
Postgres-ზე:
1. ჩვეულებრივი ჯაჭვის აგება (5+ თანმიმდევრული ჩანაწერი) → `chain-health`
   აბრუნებს `status: ok`
2. ერთი შუალედური ჩანაწერის ველის ცვლილება (`UPDATE audit_logs SET
   action='TAMPERED' WHERE id=...`) → ვლინდება `hash_mismatches`-ით
3. ჩანაწერის წაშლა window-ის საზღვარზე → ვლინდება `link_breaks`-ით
4. ორმაგი migration-გაშვება (idempotence-ის დამტკიცება — `CREATE OR
   REPLACE` ორჯერ არ ამტვრევს არსებულ ჯაჭვს)

**(გ) ქართული ტექსტის მთლიანობა**:
- ძებნის რელევანტურობა: აღებული 20+ საძიებო ტერმინი → შეადარეთ `pg_trgm`-ის
  ტოპ-10 შედეგი Oracle Text-ის ტოპ-10-თან (იდენტური არ იქნება — საჭიროა
  „მისაღები გადაფარვის" ზღვარზე შეთანხმება, არა ზუსტი დამთხვევა)
- PDF ვიზუალური შედარება: იგივე compliance-მონაცემი → reportlab PDF vs
  iText/PDFBox PDF, გვერდი-გვერდზე screenshot-შედარება ქართული გლიფების
  სისწორეზე ფოკუსით

## 3.3 — ცნობილი შეუსაბამობების რეგრესია

დეპარტამენტის მისადაგების სამივე წესი (§5, ბაგი #6) — რაც არ უნდა
გადაწყვიტოთ (გაერთიანება თუ განზრახ დატოვება ცალკე), **Java-ვერსიაში
შედეგი უნდა იყოს იდენტური იმ გადაწყვეტილებისა**, არა შემთხვევითი
კონსოლიდაცია თარგმანის დროს. ტესტ-მატრიცა: 3 დეპარტამენტის-სტრიქონის
ფორმატი (em-dash, ჰიფენი, "ჯგუფი"-საკვანძო) × 7 გამოყენების წერტილი
(compliance-დათვლა, mark-read, SSE-ფილტრი, პირდაპირი მესიჯინგი,
აუდიტ-ლოგის scope, ვიდეოების ფილტრი, სტატისტიკის დაჯგუფება).

---

# სექცია 4 — ფუნქციური გადამოწმების დეტალური მატრიცა

სულ **123 endpoint** 14 როუტერში. ცხრილი დაჯგუფებულია დომენების მიხედვით;
ტრივიალური read-only endpoint-ები (მარტივი list/get, ბიზნეს-ლოგიკის გარეშე)
თავმოყრილია შემაჯამებელ მწკრივებში.

## Auth / Identity

| მოდული | ფუნქცია/ბიზნეს-ლოგიკა | Endpoint | ტესტირების მეთოდი | მიღების კრიტერიუმი |
|---|---|---|---|---|
| Auth | login: ორმხრივი credential-შემოწმება (production password vs dev bypass ცნობილი ტესტ-ანგარიშებისთვის), JWT-გაცემა + httpOnly cookie | `POST /api/auth/login` | Parity + rate-limit ტესტი (10/წთ) | იდენტური token-claims, cookie flags, dev-bypass გამორთულია production-ში |
| Auth | password-less dev-bypass 6 ფიქსირებული ელფოსტისთვის + `test_operator_*` პრეფიქსი | (login-ის შიდა ლოგიკა) | უნიტ-ტესტი `is_production=True/False` ორივეზე | production-ში ცარიელი `TEST_EMAILS` სეტი — არავითარი bypass |
| Auth | JIT-provisioning: ახალი test-ანგარიშის ავტომატური შექმნა login-ზე | (login-ის შიდა ლოგიკა, dev-only) | უნიტ-ტესტი | ახალი user commit-დება იდენტური default-permissions-ით |
| Auth | Mock SSO — 3 ჰარდქოდილი ანგარიშის picker, production-გეიტის გარეშე (ცნობილი ხარვეზი #11) | `GET /api/auth/sso/init`, `GET /api/auth/sso/mock-login`, `POST /api/auth/sso/callback` | Parity + production-გეიტის დამატების გადაწყვეტილება | გვერდის დონეზე production-redirect (თუ გადაწყდა გასწორება) |
| Auth | Forgot-password: ტოკენის გენერაცია, SMTP დაუკავშირებელი (dev-log-ონლი) | `POST /api/auth/forgot-password` | Parity — constant-time success-response | იდენტური "ყოველთვის success" ქცევა |
| Auth | Logout | `POST /api/auth/logout` | Parity | cookie იშლება |
| Users | `/me`: მიმდინარე user + derived UI flags (`can_view_audit_log`) | `GET/PUT /api/users/me`, `POST /api/users/me/password` | Parity | flags გამომუშავებულია `role_has_permission`-იდან იდენტურად |
| Users | ერთი user-ის admin-რედაქტირება — **არ ასუფთავებს permissions-ს როლის ცვლილებაზე** (ცნობილი ხარვეზი #3) | `PUT /api/users/{id}` | Parity + გადაწყვეტილება (§5) | გადაწყვეტილებაზეა დამოკიდებული |
| Users | Bulk role-reassign — ასუფთავებს permissions-ს, იცავს last-admin-ს, ბლოკავს self-change-ს | `POST /api/admin/roles/bulk-reassign` | Parity + უნიტ-ტესტი (self-exclusion, last-admin protection) | ორივე დაცვა ინარჩუნებს ქცევას |
| Users | Permissions-მატრიცის განახლება — whitelist-ს აკლია `videos.archive` (ცნობილი ხარვეზი #4) | `PUT /api/users/{id}/permissions` | Parity + გადაწყვეტილება (§5) | გადაწყვეტილებაზეა დამოკიდებული |
| Users | User status (active/inactive) | `PUT /api/users/{id}/status` | Parity | — |
| Users | User create/reset-password/nudge | `POST /api/users`, `POST /api/users/{id}/reset-password`, `POST /api/users/{id}/nudge` | Parity | default-permissions role-ის მიხედვით |
| Users, Teams | ტრივიალური CRUD/list (group-leaders, teams get/create) | `GET /api/admin/group-leaders`, `GET/POST /api/teams`, `GET /api/users` | Parity smoke-test | სტანდარტული list/create ქცევა |

## Content — Articles / News / Videos / Categories

| მოდული | ფუნქცია/ბიზნეს-ლოგიკა | Endpoint | ტესტირების მეთოდი | მიღების კრიტერიუმი |
|---|---|---|---|---|
| Articles | CRUD + ავტომატური ORM-auditing (§0-ის შესწორება) — `version` იზრდება ყოველ update-ზე | `POST/PUT/DELETE /api/articles`, `/api/articles/{id}` | Parity + `test_audit_trail.py`-ის ეკვივალენტი | UPDATE-აუდიტ-ჩანაწერი იწერება ველების diff-ით, `version` +1 |
| Articles | Archive/unarchive + bulk-archive | `POST /api/articles/{id}/archive`, `/unarchive`, `/bulk-archive` | Parity | აუდიტ-ჩანაწერი ARCHIVE/UNARCHIVE action-ით |
| Articles | Autosave (დრაფტი, ცალკე `version`-ის გარეშე) | `PATCH /api/articles/{id}/autosave` | Parity | draft state არ ცვლის published `version`-ს |
| Articles | ისტორია + diff-ის გამოთვლა (predecessor/explicit/current შედარება) | `GET /api/articles/{id}/history`, `/history/{hid}/diff`, `POST /history/{hid}/restore` | Parity + diffing.py-ის გამომავალი HTML-ის სტრუქტურული შედარება | identical `<ins>`/`<del>` სემანტიკა (არა აუცილებლად identical markup) |
| Articles | **Quiz admin-რედაქტირება არ ზრდის `version`-ს, თუმცა quiz-gate `version`-ზეა მიბმული** (ცნობილი ხარვეზი #2) | `PUT /api/articles/{id}/quiz/admin` | Parity + გადაწყვეტილება (§5) | გადაწყვეტილებაზეა დამოკიდებული — მაღალი პრიორიტეტი, რადგან ცოცხალ მონაცემებზე მოქმედებს |
| Articles | Quiz-ჩაბარება: server-side grading, ერთჯერადი "ცოდნის ქულის" +10/+5 ბონუსი | `GET /api/articles/{id}/quiz`, `POST /quiz/attempt` | Parity + score-ფორმულის უნიტ-ტესტი | `is_correct` არასდროს გამოჩნდება public schema-ში |
| Articles | Read-receipt + quiz-gate enforcement | `POST /api/articles/{id}/read-receipt`, `GET /read-receipt/me` | Parity | gate 403-ობს ჩაუბარებელ quiz-ზე |
| Articles | View-logging (passive) | `POST /api/articles/{id}/view`, `GET /views` | Parity | `ArticleViewLog` ერთადერთი passive-view წყარო რჩება |
| Articles | დეპარტამენტის prefix-aware ხილვადობა | `GET /api/articles` (list-ფილტრი) | Parity + §3.3-ის რეგრესია | იდენტური ხილვადობა 3 დეპარტამენტ-ფორმატზე |
| Articles | Knowledge-score, leaderboard, recently-viewed | `GET /api/users/me/knowledge-score`, `/api/knowledge-leaderboard`, `/api/me/recently-viewed` | Parity | ფორმულა იდენტური (+10/+5 bonus) |
| Articles | User notes + feedback | `GET/PUT /api/articles/{id}/note`, `POST /feedback`, `GET /api/admin/feedback` | Parity smoke-test | — |
| Articles | Verify (მენეჯერული დადასტურება) + related-articles (tag-based) | `POST /api/articles/{id}/verify`, `GET /related` | Parity | — |
| Articles | Stale-content რეპორტი | `GET /api/admin/articles/stale` | Parity smoke-test | — |
| News | CRUD + ისტორია/restore, **`is_archived` არის `@property` (expires_at-ზე გამოთვლადი, არა queryable სვეტი)** | `GET/POST/PUT/DELETE /api/news`, `/api/news/{id}`, history/restore, autosave | Parity | ARCHIVE-ლოგიკა ცალკე Java-ში — ან computed field, ან real column (გადაწყვეტილება) |
| News | დეპარტამენტის prefix-aware ხილვადობა (identical to Articles) | `GET /api/news` | Parity + §3.3 | — |
| Videos | CRUD + archive/unarchive, **დეპარტამენტის ფილტრი ზუსტი-დამთხვევაა, არა prefix** (ცნობილი ხარვეზი #10) | `GET/POST/PUT/DELETE /api/videos`, `/archive`, `/unarchive`, `/view` | Parity + გადაწყვეტილება (§5) | გადაწყვეტილებაზეა დამოკიდებული |
| Categories | ტრივიალური CRUD | `GET/POST/PUT/DELETE /api/categories` | Parity smoke-test | — |
| Platform | ატვირთვა, ტეგები, servable-pages whitelist, health-check | `POST /api/upload`, `GET /api/tags`, `GET /api/health`, static/HTML routes | Parity smoke-test | — |

## Compliance

| მოდული | ფუნქცია/ბიზნეს-ლოგიკა | Endpoint | ტესტირების მეთოდი | მიღების კრიტერიუმი |
|---|---|---|---|---|
| Compliance | `compute_compliance()` — ერთადერთი გაზიარებული ფორმულა (numerator/denominator) ყველა compliance-ხედვისთვის | `GET /api/compliance/my-readings`, `/my-progress` | Parity + უნიტ-ტესტი ფორმულაზე | ერთი წყარო ბოლომდე — dashboard, export, cron ერთსა და იმავეს აბრუნებენ |
| Compliance | mark-read + quiz-gate ინტეგრაცია | `POST /api/compliance/mark-read/{id}` | Parity | quiz-ჩაუბარებელზე 403 |
| Compliance | Required-readings CRUD, დეპარტამენტის prefix-mapping | `GET/POST/PUT/DELETE /api/compliance/required-readings`, `/by-item/{type}/{id}` | Parity + §3.3 | — |
| Compliance-alerts | 24სთ-იანი cron: critical-threshold (30%) შემოწმება, მენეჯერის CC | (cron, არა HTTP endpoint — `compliance_alerts.py`) | სცენარული ტესტი low-compliance-user-ზე | Message-ჩანაწერი user-ს + manager-ს ეგზავნება |

## Stats / Reporting

| მოდული | ფუნქცია/ბიზნეს-ლოგიკა | Endpoint | ტესტირების მეთოდი | მიღების კრიტერიუმი |
|---|---|---|---|---|
| Stats | Team-stats/department-stats — დეპარტამენტის prefix-aware დაჯგუფება, whitelisted bucket-მისადაგება | `GET /api/manager/team-stats`, `/department-stats`, `/api/admin/departments/{d}/groups/{g}/users` | Parity + §3.3 | იდენტური bucket-მისადაგება |
| Stats | Critical-operators (30%-ის ქვემოთ) | `GET /api/admin/critical-operators` | Parity | იგივე threshold |
| Stats | KPI/activity/breakdown — Postgres `date_trunc` vs SQLite `strftime` dialect-branch | `GET /api/statistics/kpi`, `/activity`, `/breakdown` | Parity + Oracle `TRUNC()` მესამე შტოს ტესტი | დროის-band-ის იდენტური დაჯგუფება Oracle-ზეც |
| Stats | Popular/failed searches | `GET /api/statistics/popular-searches`, `/failed-searches` | Parity smoke-test | — |
| Stats | Compliance-სტატისტიკის ერთიანი schema | `GET /api/statistics/compliance`, `/user-progress` | Parity | იყენებს იმავე `compute_compliance()`-ს |
| Search | Global-search + per-source რელევანტობის ქულირება (title×10/tags×5/content×1 articles-ზე; title×3/content×1 news-ზე) | `GET /api/search`, `/api/search/global`, `/api/search/history` | Parity + §3.2(გ) რელევანტობის შედარება | ტოპ-N გადაფარვა შეთანხმებულ ზღვარს ზემოთ |

## Audit / Messaging / Exports

| მოდული | ფუნქცია/ბიზნეს-ლოგიკა | Endpoint | ტესტირების მეთოდი | მიღების კრიტერიუმი |
|---|---|---|---|---|
| Audit | List — მენეჯერი შეზღუდულია საკუთარ დეპარტამენტზე (ზუსტი-დამთხვევა, არა prefix) | `GET /api/audit-logs` | Parity + §3.3 | მენეჯერი ვერ ხედავს სხვა დეპარტამენტის ჩანაწერებს |
| Audit | CSV export — **ფორმულა-ინექციისგან დაუცველი**, განსხვავებით სხვა ექსპორტებისგან (ცნობილი ხარვეზი #8) | `GET /api/audit-logs/export` | Parity + უსაფრთხოების უნიტ-ტესტი (`=`, `+`, `@`-დაწყებული სახელი) | **სავალდებულო გასწორება** — §5-ში აღინიშნება, არა ნებაყოფლობითი |
| Audit | Verify — ერთი ჩანაწერის ჰეშ-ხელახლა-გამოთვლა + წინამორბედის ბმის შემოწმება | `GET /api/audit-logs/{id}/verify` | §3.2(ბ) — 3 tamper-scenario | Oracle-ზე იდენტური აღმოჩენა |
| Audit | Chain-health — batch-ვალიდაცია ბოლო N ჩანაწერზე, SQLite-ზე degradation | `GET /api/audit-logs/chain-health` | §3.2(ბ) | 200 "unavailable" non-Oracle-ზე, არა შეცდომა |
| Messaging | SSE stream — გლობალური არხი, server-side ფილტრი (dept/role/user), admin-override | `GET /api/stream` | Parity + queue-overflow/admin-override უნიტ-ტესტი | ბაუნდარი queue behavior იდენტური |
| Messaging | პირდაპირი მესიჯინგი — მენეჯერი შეზღუდულია საკუთარ დეპარტამენტზე (ზუსტი-დამთხვევა) | `GET/POST /api/messages`, `/sent`, `POST /{id}/read`, `DELETE /{id}` | Parity + §3.3 | — |
| Messaging | Broadcast (ადმინისტრაციული გავრცელება) | `POST /api/broadcast` | Parity | SSE ივენთი სწორი target-ით |
| Messaging | Notifications-summary | `GET /api/notifications/summary` | Parity smoke-test | — |
| Exports | Readings CSV — ფორმულა-სანიტაცია მოქმედია, `compute_compliance()`-ის eligibility-ფილტრი | `GET /api/export/readings` | Parity + უსაფრთხოების ტესტი | `_sanitize_cell`-ის ეკვივალენტი მოქმედია |
| Exports | Readings/team-stats XLSX + PDF — **readings.pdf არ იზიარებს CSV-ის ვინაობის ფილტრს** (ცნობილი ხარვეზი #7) | `GET /api/export/readings.xlsx`, `/readings.pdf`, `/team-stats.pdf` | Parity + §3.2(გ) ვიზუალური PDF-შედარება + გადაწყვეტილება (§5) | ფონტი + სანიტაცია + ფილტრი-კონსისტენტურობა |
| Exports | Async job-status/download, **TTL იწერება არასდროს იკითხება** (ცნობილი ხარვეზი #9) | `GET /api/export/status/{id}`, `/download/{id}` | Parity + reaper-ის დამატების გადაწყვეტილება | — |

---

# სექცია 5 — ცნობილი ხარვეზები: გადაწყვეტილებების სია

თითოეული დადასტურებულია რეალურ კოდში (ფაილი:ხაზი მითითებული). **გადაწყვეტილება
თითოეულზე თქვენია** — გავასწორო თუ დავტოვო ცალკე task-ად. ეს ცხრილი
დამტკიცების შემდეგ ცალკე, არჩევანის ფორმით გაგივლით (წესი #1).

| # | ხარვეზი | დადასტურება | ჩემი რეკომენდაცია |
|---|---|---|---|
| 1 | ~~აუდიტ-ლოგში არ იწერება~~ **უარყოფილია** — ORM-listener წერს | `audit_trail.py:86-233`, `tests/test_audit_trail.py:32-86` | არაფრის კეთება საჭირო — docstring-ების განახლება, რომ აღარ ატყუებდეს |
| 2 | Quiz-რედაქტირება არ ზრდის `version`-ს, gate `version`-ზეა | `routers/articles.py:730-765` (არ ეხება `db_article.version`), `:1024` (gate) | **✔ გადაწყვეტილია — გავასწოროთ** (თქვენი დადასტურება, 2026-07-29) |
| 3 | ერთი user-ის როლის ცვლილება არ ასუფთავებს permissions-ს (bulk აკეთებს) | `routers/users.py:322-334` (ერთი) vs `:162-163` (bulk) — **ცოცხლად დადასტურდა**: ოპერატორი გადავიყვანეთ `content_admin`-ზე ამ endpoint-ით, `permissions` პასუხში დარჩა `[]` — არც ერთი ახალი როლის default უფლება არ მიენიჭა | **გასწორება რეკომენდირებულია** — თანმიმდევრულობისთვის bulk-ის ლოგიკასთან |
| 4 | `videos.archive` აკლია permissions whitelist-ს | `routers/users.py:460-465` | **გასწორება რეკომენდირებულია** — მარტივი, დაბალი-რისკის დამატება |
| 5 | ორი disjoint RBAC-კატალოგი | `security.py:372-381` (თვითონ დოკუმენტირებული) | **✔ გადაწყვეტილია — გავაერთიანოთ მიგრაციისას** (თქვენი დადასტურება, 2026-07-29) |
| 6 | დეპარტამენტის მისადაგების 3 წესი (prefix vs ზუსტი) | 7 prefix-საიტი vs 7 ზუსტი-საიტი (იხ. §3.3) | **◐ ნაწილობრივ დასრულდა (commit `78db0fc`, 2026-07-29):** compliance-დათვლა, mark-read და სავალდებულო-წაკითხვის შეტყობინებები გაერთიანდა prefix-წესზე — ესენი ადრე სამივე ზუსტი-დამთხვევა იყო, მიუხედავად თავდაპირველი მოთხოვნის დაშვებისა, რომ უკვე prefix-მგრძნობიარენი იყვნენ. **დარჩენილი 3 საიტი** (SSE-ნაკადის ფილტრი, პირდაპირი მესიჯინგის შეზღუდვა, აუდიტ-ლოგის მენეჯერ-scope) **განზრახ არ შეხებია** — ესენი გავლენას ახდენენ ვინ-რას-ხედავს კონფიდენციალურობასა და აუდიტის ხილვადობაზე, ამიტომ ცალკე, კონკრეტული განხილვა სჭირდება თითოეულზე, სანამ შეიცვლება |
| 7 | XLSX/PDF-ს არ აქვს CSV-ის ვინაობის ფილტრი | `exports.py:93` (CSV) vs `:144` (XLSX) vs `:264` (readings.pdf) | **გასწორება რეკომენდირებულია** — მონაცემთა შეუსაბამობა ფორმატებს შორის |
| 8 | აუდიტ-ლოგის CSV-ს არ აქვს ფორმულა-ინექციის დაცვა | `routers/audit_logs.py:305-312` (არ იძახებს `_sanitize_cell`) | **✔ გადაწყვეტილია — გავასწოროთ** (თქვენი დადასტურება, 2026-07-29) |
| 9 | Export-job TTL იწერება, არასდროს იკითხება | `exports.py:368,376,395` (წერა) — read არსად | **გასწორება რეკომენდირებულია** Java-ვერსიაზე — scheduled reaper `@Scheduled`-ით |
| 10 | ვიდეოების დეპარტამენტ-ფილტრი prefix-ის გარეშე | `routers/videos.py:77` — **ცოცხლად დადასტურდა** golden-master-ით: „ტექნიკური — ჯგუფი 01"-ის ოპერატორს "ტექნიკური"-ზე მიმართული ვიდეო **საერთოდ არ უჩანს** (ცარიელი სია), მაშინ როცა იგივე სტატია/სიახლე გამოჩნდებოდა | **გასწორება რეკომენდირებულია** — თანმიმდევრულობა Articles/News-თან |
| 11 | Mock-SSO picker production-გეიტის გარეშე route-დონეზე | `routers/auth.py:127` | **გასწორება რეკომენდირებულია** — დღეს მინიმალური რისკია (ჯერ production არაა), მაგრამ production-ზე გადასვლამდე აუცილებელია |

## დამატებით აღმოჩენილი (თავდაპირველ სიაში არ იყო)

| # | აღმოჩენა | დადასტურება |
|---|---|---|
| 12 | სამუშაო ხეში დაუმთავრებელი commit — ბაგი #6-ის კონსოლიდაცია მიმდინარეობს | `git diff --stat`: `compliance_utils.py`, `routers/stats.py` (−111 ხაზი), `routers/compliance.py` და 2 ტესტ-ფაილი |
| 13 | `scripts/sync_rbac.py` ეყრდნობა ფაილს, რომელიც რეპოში არ არსებობს | `sync_rbac.py:11-14` — `sys.exit(1)` თუ გაეშვება დღეს |
| 14 | CI მუშაობს მხოლოდ SQLite-ზე — აუდიტის ჰეშ-ჯაჭვი და `pg_trgm` ინდექსები **არასდროს** ტესტირდება CI-ში | `.github/workflows/ci.yml` (მხოლოდ SQLite dependency) |
| 15 | Rate-limiting 4x სუსტია დღეს, ვიდრე ნომინალურად ჩანს (in-memory, 4 worker) | `state.py:76-78` vs `Dockerfile`-ის gunicorn worker-count |
| 16 | **ლოგოს ფაილი (`magti_logo.png`) არასდროს არ მოიწოდება — 404, მიუხედავად ფაილის არსებობისა.** `main.py:477`-ზე `app.mount("/static", ...)` რეგისტრირდება **მანამდე**, სანამ `main.py:499`-ზე `platform`-როუტერი ჩაირთვება — ანუ mount იჭერს `/static/magti_logo.png`-ს პირველი, და `platform.py`-ის სპეციალური route (რომლის docstring თვითონ აცხადებს "intercepts the request before it falls through to the StaticFiles mount") **არასდროს არ სრულდება.** მკვდარი კოდია, აღმოჩენილია golden-master capture-ით (`serve_logo` სცენარი) | `main.py:477,499`, `routers/platform.py:61-72` |

---

## შემდეგი ნაბიჯი

**4 გადაწყვეტილება უკვე მიღებულია (2026-07-29):** ბაგი #2 (quiz-version),
ბაგი #5 (RBAC-კატალოგების გაერთიანება), ბაგი #6 (დეპარტამენტის მისადაგების
კონსოლიდაცია — WIP-ის დასრულება) და ბაგი #8 (აუდიტ-ლოგის CSV-სანიტაცია) —
ყველა **„გავასწოროთ/გავაერთიანოთ"**.

**დარჩენილი ღია პუნქტები** (#3, #4, #7, #9, #10, #11 + 4 დამატებით
აღმოჩენილი) დაისმება არჩევანის ფორმით, შესაბამის ეტაპთან მიახლოებისას —
არა ერთბაშად, რომ არ გადატვირთოთ.

**ფაზა 0 დასრულებულია** (0.1–0.4 ოთხივე ✅) — WIP დაკომიტებულია, API-კონტრაქტი
დაფიქსირებულია (122 ოპერაცია), parity-ჰარნესი მუშაობს (130 სცენარი, 121/122
დაფარული), და ტესტების კატალოგი მზადაა (103/103).

**ჯავისა და Maven-ის დაყენება — დასრულებულია (2026-07-29):** Eclipse Temurin
JDK 21 (LTS) და Apache Maven 3.9.16 ორივე დაყენებულია ამ კომპიუტერზე
(officialAdoptium/Apache-ის წყაროებიდან, checksum-ით გადამოწმებული). **ფაზა
1-ის (Java/Spring Boot კოდის წერის) დაწყების წინაშე ტექნიკური დაბრკოლება
აღარ არსებობს** — მხოლოდ თქვენი გადაწყვეტილებაა, როდის დავიწყოთ.
