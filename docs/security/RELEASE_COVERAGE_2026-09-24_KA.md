# ფუნქციებისა და მდგომარეობების დაფარვა — 2026-09-24

ეს რუკა აერთიანებს `ACCESS_CONTRACT_MATRIX_KA.md`-ის API კონტრაქტს,
`docs/uat/UAT_01`–`UAT_05`-ის ოთხ როლს და მიმდინარე Java/Playwright ნაკრებს.
ქვემოთ მითითებული ტესტი **მხოლოდ დასახელებულ შემთხვევას** ამტკიცებს. ყველა
150 endpoint-ის წარმატება/უარი/შეცდომის assertion-ით მიბმა ჯერ არ დასრულებულა;
დეტალური სამუშაო სია არის `ENDPOINT_COVERAGE_CANDIDATES_2026-09-24.csv`.

| მოქმედება და გადასვლა | როლი და დეპარტამენტი | წარმატების მტკიცებულება | უარის/შეცდომის მტკიცებულება | დარჩენილი შემოწმება |
|---|---|---|---|---|
| InfoPortal შესვლა → portal სესია | ოთხივე როლი; AD-ის დეპარტამენტი | `CorporateLoginIntegrationTest`, `CorporateLoginServiceTest` mock-ით | უროლო/გათიშული მომხმარებელი და მიუწვდომელი IdP იმავე ტესტებში | ნამდვილი InfoPortal client, როლები და outage staging-ზე — IT |
| სტატია: პირადი draft → გამოქვეყნება → არქივი/სანაგვე → აღდგენა | ავტორი/content admin; ოპერატორი მხოლოდ სამიზნე დეპარტამენტში | `ArticleControllerIntegrationTest`, `ContentTrashControllerIntegrationTest`, `admin-content-list.spec.ts`, `admin-article-history.spec.ts` | სხვის draft-ზე 404, დეპარტამენტის უარი და არასწორი მოთხოვნა Java ტესტებში | მთელი ჯაჭვი ოთხი როლით UAT-ზე; ყველა endpoint-ის error შემთხვევის მიბმა |
| სიახლე/ვიდეო → დანართი → პირდაპირი URL | ავტორი, სხვა admin, შესაბამისი/სხვა დეპარტამენტის ოპერატორი | `NewsControllerIntegrationTest`, `VideoControllerIntegrationTest`, `admin-news-videos.spec.ts` | `FileEntitlementEnforcedIntegrationTest`, `department-visibility.spec.ts`; ზომის 413 `nginx-upload.spec.ts` | იგივე გზა staging ingress/TLS-ით და BLOB-ის აღდგენით |
| სავალდებულო მასალა → ქვიზი → ერთი დადასტურება → ახალი ვერსიის ცალკე ქვითარი | სამიზნე დეპარტამენტის ოპერატორი, content admin | `QuizControllerIntegrationTest`, `ReadingAcknowledgementConcurrencyIntegrationTest`, `quiz-gate.spec.ts`, `reading-and-history.spec.ts` | ქვიზის ბარიერი, ორმაგი მოთხოვნა და აუდიტის ჩავარდნის rollback ტესტები | ოთხი როლის UAT; ნამდვილი მონაცემის backup/restore |
| მენეჯერის დეპარტამენტი/ჯგუფი → ანგარიში → ექსპორტი | manager მხოლოდ საკუთარი scope; acting manager-ის უარი | `ManagerScopeTest`, `StatsControllerDepartmentScopeTest`, `team-stats.spec.ts` | `ExportQueryServiceScopingTest`, `team-stats.spec.ts`-ში primary team-ის გარეშე 403 | კომპანიის ჯგუფების რეალური იერარქიის UAT |
| ექსპორტის შექმნა → worker lease → retry/recovery → ჩამოტვირთვა | უფლებამოსილი manager/system admin | `AdminExportJobServiceIntegrationTest`, `ExportJobRecoveryIntegrationTest`, `ExportControllerDownloadTest` | worker-ის დაკარგვა და აუდიტის ჩავარდნა `ExportJobRecoveryAuditRollbackIntegrationTest`-ში | replica crash/restart, download და audit staging-ზე |
| როლის შეცვლა/ანგარიშის გათიშვა → ძველი token-ის უარყოფა | system admin/AD; სხვა როლს აკრძალვა | `CorporateLoginIntegrationTest`, `JwtAuthenticationFilterTest`, `admin-bulk-deactivate.spec.ts` | `DenyByDefaultIntegrationTest`; UI დამალვა `presentation-personas.spec.ts` | ნამდვილი AD ცვლილების გავრცელების დრო |
| ძებნა/პირადი რჩეულები → შედეგის გახსნა | ავტორიზებული ოპერატორი საკუთარი scope-ით | `SearchControllerIntegrationTest`, `operator-browsing.spec.ts`, `shared-components.spec.ts` | UI-ის შეცდომიდან აღდგენა `shared-components.spec.ts` | სხვა დეპარტამენტის ID-ების ყველა სახეობის უარის რუკა |
| mutation → აუდიტის hash-ჯაჭვი → გადამოწმება | system admin კითხულობს; დანარჩენი უარყოფილია | `AuditChainServiceTest`, `AuditLogControllerIntegrationTest`, `admin-audit.spec.ts` | ბოლო ჩანაწერის დაკარგვა/ჩაწერის ჩავარდნა შესაბამის Java ტესტებში | გარე checkpoint, დამოუკიდებელი ასლი და აღდგენა staging-ზე |
| Flyway V1–V50 → ორი replica → საერთო სესია | DevOps/DBA | 2026-09-24: სუფთა Oracle XE 21c-ზე ორივე replica მზად, 50 უნიკალური history row, ერთი replica-ს token მეორემ მიიღო | წარუმატებელი migration-ის/DB outage-ის production-like ცდა არ ჩატარებულა | კომპანიის Oracle-ის ვერსია, staging-ის ორი replica, rollback |
| 1920×1080 ქართული UI → ღია/მუქი თემა → კლავიატურა | ოთხი როლის Chrome სამუშაო გარემო | `desktop-theme.spec.ts`, `shell-and-stats.spec.ts`; shipping nginx-ზე 51/51 Playwright | ცალკეული გვერდების ყველა error მდგომარეობა არაა დაფარული | ბიზნეს UAT-ის ვიზუალური ხელმოწერა |

მდგომარეობების დამატებითი საზღვრები, რომლებიც route-ს კი არა **გადასვლას**
ეხება: `is_draft` ავტორის პირად მონახაზს ნიშნავს, `status='draft'` კი სარედაქციო
მდგომარეობას; გამოქვეყნებული სტატიის სამიზნის შეცვლა ძველ მკითხველს წვდომას
უკარგავს; სანაგვე ინარჩუნებს უკვე ჩაწერილ read/quiz/audit მტკიცებულებას;
დადასტურება ახალი article version-ისთვის ცალკე ქვითარია; export-ის
`processing` მდგომარეობა lease-ის დაკარგვის შემდეგ აღდგენადია. ეს
გადასვლები Java ტესტებში არსებობს, მაგრამ სრული ოთხროლიანი staging/UAT
ჯაჭვის მტკიცებულება ჯერ არ გვაქვს.
