# გამოშვების მზაობის ლედჯერი

**თარიღი:** 2026-09-25, განახლდა 2026-09-26 (Asia/Tbilisi). **Branch:** `claude/magti-portal-release-audit-0alwlt`, PR #27.

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

A1–A13 თავიდანვე შეთანხმებული პუნქტებია. ყოველი ახალი პუნქტი, რომელიც
აუდიტისას აღმოჩნდება და ჩვენზეა დამოკიდებული, ბოლოში ემატება აღმოჩენის
თარიღით, რომ დახურვის წინადადებამ მისი გამოტოვება ვერ შეძლოს.

| # | პუნქტი | დასრულდა, როცა | სტატუსი | მტკიცებულება |
|---|---|---|---|---|
| A1 | კოდი GitHub-ზეა, გახსნილია draft PR | CI-ის 6-ვე job მწვანეა ბოლო commit-ზე | `PARTIAL` | PR #27 ღიაა. 5/6 job მწვანეა; SBOM job წითელია მხოლოდ artifact-ის ატვირთვაზე, რადგან ანგარიშის საცავი სავსეა. კანდიდატი ჯერ ატვირთული არ არის. `head-ci:36197161930@d6c9fe6`. **2026-09-26:** კანდიდატი GitHub-ზეა და merge-ილია: `commit:b6d62b1`. CI ახლა 7 job-ია. 553eb87-ზე 6 მწვანეა: Oracle, e2e production image-ით, credential hygiene, Angular, Java unit და Python. SBOM job-ში ოთხივე სკანი გავიდა (ორი SBOM და ორი runtime image, 0 HIGH/CRITICAL); წითელია ისევ მხოლოდ ატვირთვა („Artifact storage quota has been hit“). ეს ანგარიშის საკითხია, არა კოდის. `ci:36229625340@553eb87` |
| A2 | 4 გადაწყვეტილება ჩაწერილია | დოკუმენტებშია, თარიღით 2026-09-25 | `CLOSED` | D1–D4 ზემოთაა. D2 `PRODUCT_OWNER_DECISIONS_KA.md`-ში A3-თან ერთად ჩაიწერება. `file:docs/RELEASE_READINESS_LEDGER_KA.md`. **2026-09-26:** D2 ჩაიწერა PO-34-ის დამატებად, სრული მოცულობითა და ცნობილი შედეგით: `commit:3e61956` `file:docs/PRODUCT_OWNER_DECISIONS_KA.md` |
| A3 | პირადი მონახაზი დაცულია | სხვის მონახაზზე ყველა endpoint ორივე ტიპის ადმინს 404-ს უბრუნებს; ტესტი მწვანეა | `PARTIAL` | კანდიდატის A08 და A09 იმავე წესს ეხება, ამიტომ ჯერ მისი merge. **2026-09-26:** კანდიდატმა წესი `ArticleVisibility`-ში ჩასვა (PO-34), მაგრამ მხოლოდ იმ endpoint-ებზე, რომლებიც მას იყენებენ. შემოწმებამ სამივე ტიპის რედაქტორთან წითელი აჩვენა. სხვის პირად სტატიას ისინი გადაწერდნენ, დაარქივებდნენ, დაადასტურებდნენ და სანაგვეში გადაიტანდნენ; პასუხში სრულ შინაარსს იღებდნენ. **bulk-status `is_draft`-ს ასუფთავებდა, ანუ მონახაზს მთელ აუდიტორიაზე აქვეყნებდა.** ადმინის ძიება ყველა პირად მონახაზს სრული ტექსტით აჩვენებდა. global search-ის cache-ი ავტორის პასუხს 60 წამით მის კოლეგებს აწვდიდა. სავალდებულო დავალება მონახაზის სათაურს შემსრულებლებს უგზავნიდა. სიახლეებზეც იგივე ხვრელები იყო (PUT, archive, history, restore). სტატიები: `commit:3e61956`. სიახლეები: `commit:1447689`. `test:java-backend/src/test/java/ge/magti/portal/web/PrivateDraftIsolationIntegrationTest.java#noOtherEditorCanChangeAnotherAuthorsPrivateDraft` `test:java-backend/src/test/java/ge/magti/portal/web/PrivateDraftIsolationIntegrationTest.java#bulkOperationsSkipAnotherAuthorsPrivateDraft` `test:java-backend/src/test/java/ge/magti/portal/web/PrivateDraftIsolationIntegrationTest.java#globalSearchDoesNotServeTheAuthorsAnswerToAColleague` `test:java-backend/src/test/java/ge/magti/portal/web/PrivateDraftIsolationIntegrationTest.java#anotherAuthorsPrivateNewsDraftIsReachableFromNoEndpoint`. **აკლია:** სანაგვე (`/api/content-trash`). სია სხვის მიერ სანაგვეში გადატანილი პირადი მონახაზის სათაურს აჩვენებს. restore, legal hold და purge ამ ჩანაწერზეც მუშაობს. legal hold და purge იურიდიული და retention ფუნქციებია, ამიტომ მფლობელის პასუხს ველოდებით |
| A4 | ატვირთვა production-ში | 5 MB-იანი ფაილი nginx-ით გადის, 10 MB-ზე დიდი უარყოფილია | `CLOSED` | ყველა მოთხოვნა ingress-ის შემდეგ frontend-ის nginx-ზე გადის, მას კი ზღვარი არ ჰქონდა, ამიტომ nginx-ის ნაგულისხმევი 1 MB მოქმედებდა. production bundle-ზე გაზომილი: 5 MB-იანი PDF — 413 nginx-ისგან (backend-ს პირდაპირ — 200). გასწორების შემდეგ: 5 MB — 200, 10.5 MB — აპლიკაციის 413 თავისი მიზეზით, 12 MB — nginx-ის 413. გასწორება: `commit:37ebd12`. CI ახლა nginx-ით ამოწმებს: `commit:a33ab06`. ტესტი ძველ კონფიგურაციაზე წითელია: `file:angular-frontend/e2e/upload-limits.spec.ts` |
| A5 | ★ ან „წავიკითხე“ ორჯერ დაჭერისას | ორი ერთდროული მოთხოვნიდან ორივე 200-ს იღებს და ბაზაში ერთი ჩანაწერია | `CLOSED` | წითელი ტესტი (500, ORA-00001): `commit:65a9161`. გასწორება: `commit:5940886`. `test:java-backend/src/test/java/ge/magti/portal/web/ConcurrentUpsertIntegrationTest.java#aDuplicateBookmarkThatWaitedOnTheFirstIsReturnedNotRejected` `test:java-backend/src/test/java/ge/magti/portal/web/ConcurrentUpsertIntegrationTest.java#aDuplicateReadReceiptThatWaitedOnTheFirstIsRecordedNotRejected`. **2026-09-26:** merge-ის შემდეგ ქვითარს მხოლოდ კანდიდატის `ReadingAcknowledgementService` წერს, თანამშრომლის ჩანაწერის lock-ით (PO-30). ჩვენი upsert ამოღებულია, ტესტი კი იმავე lock-ზე გადაეწყო და მწვანეა: `commit:b6d62b1` |
| A6 | quiz-ის ორჯერ გაგზავნისას | მცდელობის ნომრები არ მეორდება; ბაზა დუბლიკატს არ უშვებს | `PARTIAL` | ნომრები აღარ მეორდება. წითელი ტესტი ([1, 1]): `commit:76e75a0`. გასწორება: `commit:c66c415`. `test:java-backend/src/test/java/ge/magti/portal/web/ConcurrentQuizAttemptIntegrationTest.java#twoSubmissionsInFlightGetTwoAttemptNumbers`. **აკლია:** ბაზის unique შეზღუდვა `(user_id, article_id, article_version, attempt_number)`. ეს Flyway migration-ია და მისი ნომერი კანდიდატის V49/V50-ის შემდეგ მოდის. არსებული დუბლიკატები შესაბამისობის მტკიცებულებაა, ამიტომ შეზღუდვა ჩაირთვება `ENABLE NOVALIDATE`-ით, ძველი ჩანაწერების გადანომრვის გარეშე. **2026-09-26:** კანდიდატი merge-ილია; migration-ის ნომერია V51 |
| A7 | login-ის ლიმიტი | 12 ერთდროული ცდიდან კომპანიის სერვერამდე მაქსიმუმ 10 აღწევს; მისი პასუხის ლოდინისას DB connection არ ეკავება | `CLOSED` | წითელი ტესტი (12/12 ცდა, 5 connection): `commit:3d5fc97`. გასწორება: `commit:d4bd70a`. ბაზის გათიშვისას ცდა კომპანიის სერვერამდე აღარ მიდის: `commit:76aa2d5`. `test:java-backend/src/test/java/ge/magti/portal/web/ConcurrentLoginIntegrationTest.java#simultaneousAttemptsOnOneAccountStayWithinTheLimit` `test:java-backend/src/test/java/ge/magti/portal/web/ConcurrentLoginIntegrationTest.java#aSignInWaitingOnTheDirectoryHoldsNoDatabaseConnection` `test:java-backend/src/test/java/ge/magti/portal/security/JdbcLoginAttemptStoreTest.java#aDatabaseThePoolCannotReachStopsTheSignInAtTheThrottle` |
| A8 | audit-ის საერთო რიგი | დატვირთვით გაზომილია და გადაწყვეტილება ჩაწერილია | `CLOSED` | 300 მოთხოვნიდან 300-მა 200 დააბრუნა. quiz-ის p90 253–323 ms-ია, რიგში ერთდროულად მაქსიმუმ 6 სესიაა. გადაწყვეტილება: lock რჩება timeout-ის გარეშე. staging-ზე ქსელით გამეორება B7-ია. `commit:d6c9fe6` `file:scripts/load/audited_writes.py` `file:scripts/load/README.md` |
| A9 | A06 (nginx 499) | მოთხოვნის ნომერი ყველა ფენაში ჩანს; 500 ცდის შემდეგ სტატუსია „გასწორდა“ ან `NOT_REPRODUCED` (D4) | `OPEN` | კანდიდატის `f1dc890`-ს request-ID და დროის ჩანაწერები უკვე აქვს, ამიტომ ჯერ მისი merge. **2026-09-26:** merge-ილია (`commit:b6d62b1`). nginx `$request_id`-ს backend-ს `X-Request-ID`-ით გადასცემს და თავის ჟურნალში წერს; backend-ში `RequestTimingFilter`-ია. დარჩა 500-ცდიანი კამპანია (D4) |
| A10 | ASVS L2 | L1+L2-ის 253 მოთხოვნიდან 0 GAP და 0 PARTIAL; L3 — `OUT_OF_TARGET_LEVEL` (D1) | `OPEN` | რეესტრი (`LOCAL_ASVS_CASE_REVIEW_2026-09-24.csv`) კანდიდატშია, ამიტომ ჯერ მისი merge. **2026-09-26:** merge-ილია: `file:docs/security/LOCAL_ASVS_CASE_REVIEW_2026-09-24.csv`. დარჩა L1+L2-ის 253 სტრიქონის განხილვა |
| A11 | CI production nginx-ს ამოწმებს | CI-ის e2e nginx-ზე ეშვება; `fast` wrapper-ის exit 0-ია | `CLOSED` | main-ის CI 2026-09-21-იდან წითელი იყო; შვიდივე მიზეზი გასწორდა: `commit:0adc68c` `commit:189dbe7` `commit:17ee408` `commit:5148831` `commit:532fddb` `commit:517c513` `commit:4610543`. ღამის e2e ჩავარდნა (20:00–24:00 UTC) და მისი მიზეზი, აუდიტის თარიღების შეცდომა: `commit:e07bebe`. CI-ის e2e ახლა production image-ზე ეშვება, `ng serve`-ის ნაცვლად: `commit:a33ab06`. აქ Dockerfile-ის runtime ნაწილზე 48/48 გავიდა, სუფთა Oracle-ზე, `nginx` მომხმარებლით და არა root-ით. GitHub-ზე image პირველად აეწყო (41 წმ) და e2e მასზე მწვანეა; იმავე run-ში მხოლოდ SBOM-ის ატვირთვაა წითელი, საცავის გამო: run 36201191965, commit fbe7baf (2026-09-26: token-ის ნაცვლად ტექსტია, რადგან `--online` მთელ run-ს ამოწმებს, A11-ის პირობა კი მხოლოდ e2e-სა და wrapper-ს ეხება; ანგარიშის საცავის საკითხი A1-შია). **აკლია:** wrapper-ისა და CI-ის gitleaks-ის პარიტეტი. gitleaks-ის ნაბიჯი კანდიდატშია. **2026-09-26:** კანდიდატი merge-ილია და CI-ს credential hygiene job-იც მოყვა. e2e ერთ production image-ზე ეშვება, k8s-ის მსგავსად: read-only root, capability-ების გარეშე. კანდიდატის nginx smoke იმავე image-ს იყენებს: `commit:b6d62b1` `file:scripts/verify-like-ci.sh`. GitHub-ზე e2e და credential hygiene მწვანეა (run 36229625340). `scripts/verify-like-ci.sh fast` აქ exit 0-ით დასრულდა, Gitleaks 8.30.1-ით (checksum შემოწმებულია). Gitleaks-ის გარეშე wrapper განზრახ exit 1-ს აბრუნებს. ჩვენი ერთი ძველი commit-ის სინთეზური credential scoped allowlist-ითაა მონიშნული: `commit:553eb87` |
| A12 | საბოლოო რეგრესია | ყველა შემოწმება გადის ბოლო commit-ზე; არქივი და მისი SHA-256 laptop-ის გარეთაა შენახული | `OPEN` | ბოლოს, A1–A11-ის შემდეგ |
| A13 | გარე მხარეებს მივმართეთ | სია B-ის ყველა პუნქტზე გაგზავნის თარიღი ჩაწერილია | `OPEN` | ხუთი წერილი გასაგზავნად მზადაა; თარიღები ჯერ არ წერია. `file:docs/RELEASE_EXTERNAL_REQUESTS_KA.md` `ledger:list-b` |
| A14 | audit-ის მთლიანობის შემოწმება ერთდროული ჩაწერების შემდეგ | ხელუხლებელ ჯაჭვს „tampered“-ად აღარ აცხადებს; შეცვლილი, წაშლილი, სხვაზე გადაბმული და ყალბი genesis სტრიქონი კვლავ ვლინდება | `CLOSED` | აუდიტისას აღმოჩნდა (2026-09-25). A8-ის დატვირთვის შემდეგ ხელუხლებელ ჯაჭვზე შემოწმებამ `tampered` თქვა: 442 სტრიქონში 61 რღვევა. მიზეზი: რიგითობას id-ით ამოწმებდა, trigger კი lock-ის რიგით აბამს. წითელი ტესტი: `commit:b1d5ec0`. გასწორება: `commit:3df0ec8`. `test:java-backend/src/test/java/ge/magti/portal/audit/ConcurrentAuditChainIntegrationTest.java#rowsThatJoinedTheChainOutOfIdOrderAreNotReportedAsTampered` `test:java-backend/src/test/java/ge/magti/portal/audit/AuditChainVerdictTest.java#twoRowsClaimingOnePredecessorAreBothLinkBreaks` `test:java-backend/src/test/java/ge/magti/portal/audit/AuditChainServiceTest.java#verifyDetectsADeletedPredecessor`. იმავე ბაზაზე ახლა: `ok`, 0 რღვევა. ფასი: 1 მლნ სტრიქონზე შემოწმებას 0.55 წმ სჭირდება, `row_hash`/`prev_hash` ინდექსით 0.02 წმ. ინდექსი migration-ია კანდიდატის V49/V50-ის შემდეგ; სასურველია, აუცილებელი არ არის. **2026-09-26:** ინდექსები კანდიდატის V49-ით მოვიდა. merge-ში ჯაჭვის სავალიდაციო გზა კანდიდატისაა (tip-იდან hash-ით), ჩვენი fork-ისა და მეორე genesis-ის შემოწმებით: `commit:b6d62b1` `test:java-backend/src/test/java/ge/magti/portal/audit/AuditChainVerdictTest.java#aSecondGenesisIsALinkBreak` |
| A15 | 11 MB-ზე დიდი ატვირთვა backend-ზე | 413 თავისი მიზეზით, როგორც 10 MB-ზე დიდ ფაილზე | `CLOSED` | აუდიტისას აღმოჩნდა (2026-09-25). backend-ს პირდაპირ გაგზავნილი 12 MB-იანი ფაილი 500-ს („მოულოდნელი შეცდომა“) და ERROR-ის stack trace-ს იძლეოდა, ანუ monitoring ამას სერვერის შეცდომად ითვლიდა. production-ის nginx-ის უკან ეს გზა დაკეტილია (A4), პირდაპირ კი ღია იყო. წითელი (მოსალოდნელი 413, მიღებული 500) და გასწორება: `commit:7c9cdb0`. `test:java-backend/src/test/java/ge/magti/portal/web/UploadSizeLimitIntegrationTest.java#anUploadOverTheTransportLimitIsRefusedAsTheSendersErrorNotTheServers` |
| A16 | ასინქრონული export UTC-ზე | XLSX/PDF export UTC-ზე მომუშავე JVM-ზე სრულდება და ჩამოიტვირთება | `CLOSED` | merge-ისას აღმოჩნდა (2026-09-26). კანდიდატის V50-ში `lease_until` `TIMESTAMP WITH TIME ZONE`-ია, entity-ის ველი კი თბილისის converter-ით იწერებოდა. UTC-ზე lease 4 საათით გვიან იწერებოდა და 4 საათით ადრე იკითხებოდა, ამიტომ worker-ს საკუთარი lease დაკარგულად ეჩვენებოდა. ყველა export „processing“-ში რჩებოდა და ბოლოს ჩავარდებოდა. production image-ს დროის სარტყელი არ აქვს დაყენებული, ანუ UTC-ზე მუშაობს. კანდიდატის suite +04:00-ზე გავიდა, UTC-ზე 7 ტესტი წითელი იყო. გასწორება: `commit:daec5ca`. ტესტების JVM ახლა UTC-ზეა: `commit:3618fbd`. `test:java-backend/src/test/java/ge/magti/portal/export/ExportJobRecoveryIntegrationTest.java#aLeaseWrittenThroughJpaIsTheInstantSqlComparesItWith`. UTC stack-ზე რეალური XLSX export `completed`-ში გადავიდა და 200-ით ჩამოიტვირთა |
| A17 | სათაური ID-ით | ★ (`POST`/`GET /api/favorites`) მხოლოდ იმ ელემენტის სათაურს აბრუნებს, რომლის ნახვაც მომხმარებელს შეუძლია | `OPEN` | აუდიტისას აღმოჩნდა (2026-09-26). ★ ნებისმიერ ID-ს იღებს და პასუხში ელემენტის სათაურს აბრუნებს. ასე ID-ების გადარჩევით ნებისმიერი სტატიის, სიახლისა და ვიდეოს სათაური ჩანს, მათ შორის სხვა დეპარტამენტისა და არქივისაც. შემოწმდა: Support-ის ოპერატორმა სტატიაზე 404 მიიღო, ★-ით კი მისი სათაური „მხოლოდ ოფისისთვის: შიდა გეგმა“ დაინახა. პირადი მონახაზის ნაწილი A3-ით დაიხურა. **შემოთავაზება:** სათაური მხოლოდ ხილულ ელემენტზე (`ArticleVisibility`, `NewsVisibility`, `VideoVisibility`), სხვაგან კი placeholder. საჭიროა მფლობელის პასუხი: რა აჩვენოს ძველმა ★-მ ელემენტზე, რომელიც მას შემდეგ დაიმალა |

## სია B — სხვაზე დამოკიდებული

„მოთხოვნა გაიგზავნა“ სვეტში იწერება თარიღი (`YYYY-MM-DD`), როცა წერილი
რეალურად გაიგზავნა, და არა მაშინ, როცა დაიწერა. წერილები:
[`RELEASE_EXTERNAL_REQUESTS_KA.md`](RELEASE_EXTERNAL_REQUESTS_KA.md).

| # | ვისზეა | რა | მოთხოვნა გაიგზავნა | პასუხი |
|---|---|---|---|---|
| B1 | IT | InfoPortal-ის client credential და როლები: [`QUESTIONS_FOR_IT.md`](QUESTIONS_FOR_IT.md) №13, `k8s/` IT-15 და IT-16 | — | — |
| B2 | IT | AD ანგარიშის დაბლოკვის ზღვარი: რამდენი არასწორი პაროლის შემდეგ და რა დროში (№13.6, დაემატა 2026-09-25) | — | — |
| B3 | IT / უსაფრთხოება | MFA და `ldap_auth` password grant: ან გადაწყვეტილება, ან რისკის ოფიციალური მიღება (ASVS V6.3.3, V10.4.4; №13.3) | — | — |
| B4 | IT | ingress და კლიენტის IP header-ები (№4, №7) | — | — |
| B5 | IT | გათიშული ანგარიშების feed (№11) | — | — |
| B6 | DBA | backup-იდან აღდგენა და audit chain-ის შემოწმება აღდგენილ ბაზაზე | — | — |
| B7 | Platform | staging და 600 მომხმარებლის დატვირთვა, მათ შორის A8-ის გამეორება ქსელით | — | — |
| B8 | ბიზნესი | UAT-ის ხელმოწერა და Production GO | — | — |
| B9 | DBA / უსაფრთხოება | აუდიტის ჯაჭვის გარე საკონტროლო ასლი: ვინ, რა სიხშირით, სად ინახება ისე, რომ პორტალის DB ანგარიშმა ვერ შეცვალოს, და ვინ ადარებს ([`QUESTIONS_FOR_IT.md`](QUESTIONS_FOR_IT.md) №14; დაემატა 2026-09-26, წერილი 3) | — | — |

## დახურვის წინადადება

როცა `python scripts/readiness_check.py --online` 0-ით დასრულდება, ის ამ
ფორმის წინადადებას დაბეჭდავს. commit-ს, CI run-სა და რაოდენობებს თვითონ
ჩასვამს:

> „ჩვენზე დამოკიდებული 17-ვე პირობა დახურულია და დამოუკიდებლად
> გადამოწმებულია (commit …, CI run …). დარჩენილი 9 პირობა გარე მხარეებზეა:
> IT, IT / უსაფრთხოება, DBA, Platform, ბიზნესი, DBA / უსაფრთხოება. თითოეულს
> წერილობით მივმართეთ; თარიღები ლედჯერშია.“
