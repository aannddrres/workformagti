# გამოშვების მზაობის ლედჯერი

**თარიღი:** 2026-09-25 (Asia/Tbilisi). **Branch:** `claude/magti-portal-release-audit-0alwlt`, PR #27.

ეს ფაილი ერთ კითხვას პასუხობს: გავაკეთეთ თუ არა ყველაფერი, რაც ჩვენზე იყო
დამოკიდებული. სამუშაო ორ სიადაა დაყოფილი:

- **სია A — ჩვენზე დამოკიდებული.** თითო პუნქტს აქვს ზუსტი პირობა „დასრულდა,
  როცა…“ და მტკიცებულება.
- **სია B — სხვაზე დამოკიდებული.** ჩვენი ნაწილია დათარიღებული წერილობითი
  მოთხოვნა. პასუხის გაცემა მფლობელზეა.

Production GO ამ ფაილით არ ცხადდება — ის B8-ია.

## როგორ მოწმდება

```bash
python scripts/readiness_check.py            # commit-ები, ტესტები, ფაილები
python scripts/readiness_check.py --online   # დამატებით: CI run-ები GitHub-თან
```

სიტყვა `CLOSED` თავისთავად არაფერს ამტკიცებს. სკრიპტი მტკიცებულებას ამოწმებს:

| მტკიცებულება | რას ამოწმებს |
|---|---|
| `commit:<sha>` | commit HEAD-ის ისტორიაშია |
| `test:<ფაილი>#<სახელი>` | ფაილი HEAD-შია და ეს ტესტი მასში წერია |
| `file:<ფაილი>` | ფაილი HEAD-შია. ignored ლოგი მტკიცებულება არ არის |
| `ci:<run>@<sha>` | CI run ამ commit-ზე. `--online` ამოწმებს, რომ ყველა job გავიდა |
| `head-ci:<run>@<sha>` | იგივე, და ეს run HEAD-ს ფარავს: მას შემდეგ მხოლოდ ეს ფაილი შეიცვალა |
| `ledger:list-b` | პუნქტი იხურება მხოლოდ მაშინ, როცა სია B-ის ყველა სტრიქონს გაგზავნის თარიღი აქვს |

`CLOSED` სტრიქონს ერთი commit, ტესტი ან ფაილი მაინც სჭირდება. სკრიპტის exit
კოდები: **0** — სია A დახურულია და დამოწმებულია, ქვემოთ მოცემული წინადადება
იბეჭდება; **1** — ლედჯერი სწორია, მაგრამ სამუშაო ჯერ არ დასრულებულა; **2** —
ლედჯერი რეპოზიტორიას ეწინააღმდეგება.

**ცვლილების წესი:** სტატუსი იცვლება მხოლოდ მტკიცებულებასთან ერთად, იმავე
commit-ში. სტრიქონი და მტკიცებულება არ იშლება. თუ პუნქტი ხელახლა გაიხსნა,
სტატუსი უკან ბრუნდება და მიზეზი თარიღით ემატება.

## გადაწყვეტილებები

| # | გადაწყვეტილება | თარიღი | სად სრულდება |
|---|---|---|---|
| D1 | ASVS 5.0-ის სამიზნე დონეა **L2**. L1+L2-ის 253 მოთხოვნიდან თითოეული ხდება `PASS`, `N/A`, `EXTERNAL_BLOCKED` ან `RISK_ACCEPTED`. L3-ის 92 მოთხოვნა ხდება `OUT_OF_TARGET_LEVEL` | 2026-09-25 | A10 |
| D2 | სხვის პირად მონახაზს (`is_draft`) content admin-ი ვერ ხედავს: არც როლით, არც `content.manage` უფლებით, არც ერთ endpoint-ზე. **ცნობილი შედეგი:** სამსახურიდან წასული ავტორის პირად მონახაზებს ვეღარავინ ნახავს. თუ ეს პრობლემაა, ცალკე წესი დაგვჭირდება | 2026-09-25 | A3. [`PRODUCT_OWNER_DECISIONS_KA.md`](PRODUCT_OWNER_DECISIONS_KA.md)-ში კოდთან ერთად ჩაიწერება |
| D3 | კანდიდატი `codex/release-audit-20260924` GitHub-ზე აიტვირთება და CI-ს draft PR-ით გაივლის | 2026-09-25 | A1 |
| D4 | A06: თუ 500 კონტროლირებად ცდაში არ განმეორდა, სტატუსია `NOT_REPRODUCED`, და production-ისთვის იწერება alert იმავე ნიშანზე | 2026-09-25 | A9 |

## სია A — ჩვენზე დამოკიდებული

| # | პუნქტი | დასრულდა, როცა | სტატუსი | მტკიცებულება |
|---|---|---|---|---|
| A1 | კოდი GitHub-ზეა, გახსნილია draft PR | CI-ის 6-ვე job მწვანეა ბოლო commit-ზე | `PARTIAL` | PR #27 ღიაა. 5/6 job მწვანეა; SBOM job წითელია მხოლოდ artifact-ის ატვირთვაზე, რადგან ანგარიშის საცავი სავსეა. კანდიდატი ჯერ ატვირთული არ არის. `head-ci:36197161930@d6c9fe6` |
| A2 | 4 გადაწყვეტილება ჩაწერილია | დოკუმენტებშია, თარიღით 2026-09-25 | `PARTIAL` | D1–D4 ზემოთაა. D2 `PRODUCT_OWNER_DECISIONS_KA.md`-ში A3-თან ერთად ჩაიწერება. `file:docs/RELEASE_READINESS_LEDGER_KA.md` |
| A3 | პირადი მონახაზი დაცულია | სხვის მონახაზზე ყველა endpoint ორივე ტიპის ადმინს 404-ს უბრუნებს; ტესტი მწვანეა | `OPEN` | კანდიდატის A08 და A09 იმავე წესს ეხება, ამიტომ ჯერ მისი merge |
| A4 | ატვირთვა production-ში | 5 MB-იანი ფაილი nginx-ით გადის, 10 MB-ზე დიდი უარყოფილია | `OPEN` | main-ის `angular-frontend/nginx.conf.template`-ს `client_max_body_size` არ აქვს, ამიტომ nginx 1 MB-ზე ჩერდება. კანდიდატის E7 smoke ამას ეხება, ამიტომ ჯერ მისი merge |
| A5 | ★ ან „წავიკითხე“ ორჯერ დაჭერისას | ორი ერთდროული მოთხოვნიდან ორივე 200-ს იღებს და ბაზაში ერთი ჩანაწერია | `CLOSED` | წითელი ტესტი (500, ORA-00001): `commit:65a9161`. გასწორება: `commit:5940886`. `test:java-backend/src/test/java/ge/magti/portal/web/ConcurrentUpsertIntegrationTest.java#aDuplicateBookmarkThatWaitedOnTheFirstIsReturnedNotRejected` `test:java-backend/src/test/java/ge/magti/portal/web/ConcurrentUpsertIntegrationTest.java#aDuplicateReadReceiptThatWaitedOnTheFirstIsRecordedNotRejected` |
| A6 | quiz-ის ორჯერ გაგზავნისას | მცდელობის ნომრები არ მეორდება; ბაზა დუბლიკატს არ უშვებს | `PARTIAL` | ნომრები აღარ მეორდება. წითელი ტესტი ([1, 1]): `commit:76e75a0`. გასწორება: `commit:c66c415`. `test:java-backend/src/test/java/ge/magti/portal/web/ConcurrentQuizAttemptIntegrationTest.java#twoSubmissionsInFlightGetTwoAttemptNumbers`. **აკლია:** ბაზის unique შეზღუდვა `(user_id, article_id, article_version, attempt_number)`. ეს Flyway migration-ია და მისი ნომერი კანდიდატის V49/V50-ის შემდეგ მოდის. არსებული დუბლიკატები შესაბამისობის მტკიცებულებაა, ამიტომ შეზღუდვა ჩაირთვება `ENABLE NOVALIDATE`-ით, ძველი ჩანაწერების გადანომრვის გარეშე |
| A7 | login-ის ლიმიტი | 12 ერთდროული ცდიდან კომპანიის სერვერამდე მაქსიმუმ 10 აღწევს; მისი პასუხის ლოდინისას DB connection არ ეკავება | `CLOSED` | წითელი ტესტი (12/12 ცდა, 5 connection): `commit:3d5fc97`. გასწორება: `commit:d4bd70a`. ბაზის გათიშვისას ცდა კომპანიის სერვერამდე აღარ მიდის: `commit:76aa2d5`. `test:java-backend/src/test/java/ge/magti/portal/web/ConcurrentLoginIntegrationTest.java#simultaneousAttemptsOnOneAccountStayWithinTheLimit` `test:java-backend/src/test/java/ge/magti/portal/web/ConcurrentLoginIntegrationTest.java#aSignInWaitingOnTheDirectoryHoldsNoDatabaseConnection` `test:java-backend/src/test/java/ge/magti/portal/security/JdbcLoginAttemptStoreTest.java#aDatabaseThePoolCannotReachStopsTheSignInAtTheThrottle` |
| A8 | audit-ის საერთო რიგი | დატვირთვით გაზომილია და გადაწყვეტილება ჩაწერილია | `CLOSED` | 300 მოთხოვნიდან 300-მა 200 დააბრუნა. quiz-ის p90 253–323 ms-ია, რიგში ერთდროულად მაქსიმუმ 6 სესიაა. გადაწყვეტილება: lock რჩება timeout-ის გარეშე. staging-ზე ქსელით გამეორება B7-ია. `commit:d6c9fe6` `file:scripts/load/audited_writes.py` `file:scripts/load/README.md` |
| A9 | A06 (nginx 499) | მოთხოვნის ნომერი ყველა ფენაში ჩანს; 500 ცდის შემდეგ სტატუსია „გასწორდა“ ან `NOT_REPRODUCED` (D4) | `OPEN` | კანდიდატის `f1dc890`-ს request-ID და დროის ჩანაწერები უკვე აქვს, ამიტომ ჯერ მისი merge |
| A10 | ASVS L2 | L1+L2-ის 253 მოთხოვნიდან 0 GAP და 0 PARTIAL; L3 — `OUT_OF_TARGET_LEVEL` (D1) | `OPEN` | რეესტრი (`LOCAL_ASVS_CASE_REVIEW_2026-09-24.csv`) კანდიდატშია, ამიტომ ჯერ მისი merge |
| A11 | CI production nginx-ს ამოწმებს | CI-ის e2e nginx-ზე ეშვება; `fast` wrapper-ის exit 0-ია | `PARTIAL` | main-ის CI 2026-09-21-იდან წითელი იყო; შვიდივე მიზეზი გასწორდა: `commit:0adc68c` `commit:189dbe7` `commit:17ee408` `commit:5148831` `commit:532fddb` `commit:517c513` `commit:4610543`. ღამის e2e ჩავარდნა (20:00–24:00 UTC) და მისი მიზეზი, აუდიტის თარიღების შეცდომა: `commit:e07bebe`. **აკლია:** e2e production nginx image-ზე (ახლა `ng serve`-ზე ეშვება) და wrapper-ისა და CI-ის gitleaks-ის პარიტეტი. gitleaks-ის ნაბიჯი კანდიდატშია |
| A12 | საბოლოო რეგრესია | ყველა შემოწმება გადის ბოლო commit-ზე; არქივი და მისი SHA-256 laptop-ის გარეთაა შენახული | `OPEN` | ბოლოს, A1–A11-ის შემდეგ |
| A13 | გარე მხარეებს მივმართეთ | სია B-ის ყველა პუნქტზე გაგზავნის თარიღი ჩაწერილია | `OPEN` | `ledger:list-b` |

## სია B — სხვაზე დამოკიდებული

„მოთხოვნა გაიგზავნა“ სვეტში იწერება თარიღი (`YYYY-MM-DD`), როცა წერილი
რეალურად გაიგზავნა, და არა მაშინ, როცა დაიწერა.

| # | ვისზეა | რა | მოთხოვნა გაიგზავნა | პასუხი |
|---|---|---|---|---|
| B1 | IT | InfoPortal-ის client credential და როლები: [`QUESTIONS_FOR_IT.md`](QUESTIONS_FOR_IT.md) №13, `k8s/` IT-15 და IT-16 | — | — |
| B2 | IT | AD ანგარიშის დაბლოკვის ზღვარი: რამდენი არასწორი პაროლის შემდეგ და რა დროში. ახალი კითხვაა, №13-ს დაემატება | — | — |
| B3 | IT / უსაფრთხოება | MFA და `ldap_auth` password grant: ან გადაწყვეტილება, ან რისკის ოფიციალური მიღება (ASVS V6.3.3, V10.4.4; №13.3) | — | — |
| B4 | IT | ingress და კლიენტის IP header-ები (№4, №7) | — | — |
| B5 | IT | გათიშული ანგარიშების feed (№11) | — | — |
| B6 | DBA | backup-იდან აღდგენა და audit chain-ის შემოწმება აღდგენილ ბაზაზე | — | — |
| B7 | Platform | staging და 600 მომხმარებლის დატვირთვა, მათ შორის A8-ის გამეორება ქსელით | — | — |
| B8 | ბიზნესი | UAT-ის ხელმოწერა და Production GO | — | — |

## დახურვის წინადადება

როცა `python scripts/readiness_check.py --online` 0-ით დასრულდება, ის ამ
ფორმის წინადადებას დაბეჭდავს. commit-ს, CI run-სა და რაოდენობებს თვითონ
ჩასვამს:

> „ჩვენზე დამოკიდებული 13-ვე პირობა დახურულია და დამოუკიდებლად
> გადამოწმებულია (commit …, CI run …). დარჩენილი 8 პირობა გარე მხარეებზეა:
> IT, IT / უსაფრთხოება, DBA, Platform, ბიზნესი. თითოეულს წერილობით მივმართეთ;
> თარიღები ლედჯერშია.“
