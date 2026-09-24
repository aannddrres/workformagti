# ლოკალური გამოშვების აუდიტის შესრულებადი გეგმა — 2026-09-24

**საწყისი კანდიდატი:** `3af21d621ad49a6b05c2e6613898706352dd0a9a`;
`codex/release-audit-20260924`, სუფთა სამუშაო ხე. ყოველი შედეგი უნდა
დაუკავშირდეს საბოლოო commit-ს, ბრძანებას, exit code-ს და ლოგს. მხოლოდ
სინთეზური მონაცემები და ცალკე Oracle სქემები; არსებული demo/UAT volume-ები
ხელშეუხებელია. Testcontainers-ის Oracle fallback აკრძალულია: DB-ს ყველა
გამშვებს გადაეცემა აშკარა `ORACLE_DB_URL`.

| ფუნქცია / გადასვლა | როლი და დეპარტამენტი | წარმატება | უარი | შეცდომა / ერთდროულობა | კონკრეტული ტესტი |
|---|---|---|---|---|---|
| სტატია: პირადი draft → published → archived; პირდაპირი ID | ავტორი, სხვა content admin, სამიზნე და უცხო დეპარტამენტის ოპერატორი | ავტორი ხედავს draft-ს; გამოქვეყნებულს ხედავს მხოლოდ სამიზნე | სხვის draft-ს admin-იც ვერ ხსნის; უცხო ID 404 | არათავსებადი `is_draft`/status და პარალელური ცვლილება უარყოფილია | `ArticleVisibilityDraftTest`, `ArticleControllerIntegrationTest`, `ArticleRequestDraftConsistencyTest`, `ArticleBulkOperationsIntegrationTest` |
| გამოქვეყნება → დანართის პირდაპირი URL | იმავე ოთხი მომხმარებელი | სამიზნე მომხმარებელი იღებს BLOB-ს | სხვის draft-სა და უცხო დეპარტამენტს ფაილზეც უარი | ზედმეტი ზომა 413; აუდიტის ჩავარდნისას mutation rollback | `FileEntitlementEnforcedIntegrationTest`, `UploadControllerIntegrationTest`, `nginx-upload.spec.ts` |
| სავალდებულო წაკითხვა → ქვიზი → დადასტურება → ახალი ვერსია | სამიზნე და უცხო დეპარტამენტის ოპერატორი | ჩაბარების შემდეგ მხოლოდ ერთი ქვითარი; ახალი ვერსიისთვის ახალი ქვითარი | ჩავარდნილი ქვიზი, უცხო დეპარტამენტი და ძველი ვერსიის ცდა უარყოფილია | ერთდროული ორმაგი მოთხოვნა ერთ ქვითარს ტოვებს; audit failure აბრუნებს ტრანზაქციას | `QuizControllerIntegrationTest`, `ReadingAcknowledgementConcurrencyIntegrationTest`, `ReadingAcknowledgementRollbackIntegrationTest`, `quiz-gate.spec.ts` |
| დადასტურება → მენეჯერის ანგარიში / ექსპორტი | ჯგუფის და დეპარტამენტის მენეჯერი | მხოლოდ საკუთარ scope-ში სწორ რაოდენობასა და სახელებს ხედავს | სხვა ჯგუფი და სხვა მენეჯერის export ID უარყოფილია | ცარიელი/არასწორი filter კონტროლირებადი შეცდომაა | `ManagerScopeTest`, `StatsControllerDepartmentScopeTest`, `ExportControllerScopeGateTest`, `ExportControllerDownloadTest` |
| export queued → processing → worker recovery → ready → download → audit | უფლებამოსილი მენეჯერი და system admin | lease-ის დაკარგვის შემდეგ job აღდგება, ფაილი ჩამოიტვირთება და audit ჩანს | უცხო მფლობელის ID არ ჩანს | worker crash, აუდიტის ჩავარდნა და ორი worker-ის კონკურენცია არ ქმნის ორმაგ შედეგს | `ExportJobRecoveryIntegrationTest`, `ExportJobRecoveryAuditRollbackIntegrationTest`, `AdminExportJobServiceIntegrationTest` |
| როლის გაუქმება / დეაქტივაცია → ძველი სესიის API/UI | system admin, ყოფილი მენეჯერი | ცვლილება ინახება | ძველი token-ით დაცულ API-ზე 401/403; UI აღარ აჩვენებს დაცულ მოქმედებას | მეორე replica-შიც იგივე უარი | `CorporateLoginIntegrationTest`, `JwtAuthenticationFilterTest`, `admin-bulk-deactivate.spec.ts`, `presentation-personas.spec.ts`; ცალკე ორრეპლიკიანი ცდა |
| mutation → audit hash-ჯაჭვი → verify | system admin და ოპერატორი | ჯაჭვი სრულად მოწმდება | ოპერატორს audit API ეკრძალება | ჩანაწერის ჩავარდნა mutation-ს აბრუნებს; გაწყვეტილი ჯაჭვი unhealthy-ა | `AuditChainServiceTest`, `AuditLogControllerIntegrationTest`, `MutationAuditTransactionIntegrationTest` |
| 150 API მოქმედება / ASVS 5.0-ის 345 მოთხოვნა | თითო route-ის უფლებამოსილი და აკრძალული როლი | ზუსტი assertion და შესრულების ლოგი | ზუსტი უარის assertion | შეცდომის assertion ან დასაბუთებული N/A | `ENDPOINT_COVERAGE_CANDIDATES_2026-09-24.csv`, `ASVS_5_0_0_MATRIX.csv`; სახელი მტკიცებულებად არ ჩაითვლება |
| nginx 499: client → proxy → Java → Hikari → Oracle | იზოლირებული სინთეზური მომხმარებელი | request ID-ით დროები თავსდება ერთ კვალში | timeout კონტროლირებადია | ჩავარდნა შენახულია; დადასტურებულ მიზეზს ჯერ failing test, მერე fix | `nginx-upload.spec.ts`, `verify-nginx-smoke.sh`, ოთხივე ფენის დროითი ლოგი |
| Georgian Chrome 1920×1080, ორი თემა, კლავიატურა | ოთხივე როლი | ძირითადი გზა ხელმისაწვდომია | დაუშვებელი UI მოქმედება დამალულია | ქსელის ჩავარდნა აჩვენებს გასაგებ შეტყობინებას | `desktop-theme.spec.ts`, `shell-and-stats.spec.ts`, სრული Playwright ორჯერ ორ სუფთა სქემაზე, retries=0 |

**გაშვების რიგი:** არსებული კოდის/ტესტის assertion-ების აუდიტი → მაღალი რისკის
ცარიელი შემთხვევების დამატება → პატარა მიზნობრივი ტესტები → `fast` → Oracle
ინტეგრაცია → frontend/build/lint/i18n → supply-chain/image → საბოლოო image-ზე
Playwright ორჯერ → რესურსით შეზღუდული smoke დატვირთვა → საბოლოო hash/commit.
ყველა ჩავარდნილი ცდა ინახება და არ გადაიქცევა pass-ად retry-ით.

**ლოკალური მიღება:** ყველა აუცილებელი შემოწმება ნამდვილად შესრულდა საბოლოო
კანდიდატზე, 150 route-ის და შესაბამისი ASVS შემთხვევის აღრიცხვა დასრულდა,
მაღალი რისკის ღია ხარვეზი არ დარჩა და ორჯერადი სუფთა E2E გავიდა. სხვა
შემთხვევაში ვერდიქტია „ლოკალურად მზად არ არის“. Production მიღება ცალკეა.
