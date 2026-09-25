# `docs/` — რა სად წერია და რამდენად ენდობი

ეს ინდექსია. თითოეული ფაილი აქ ერთხელ ჩნდება, თავისი სახეობით და
თარიღით. სანამ რომელიმეს ენდობი, ჯერ ეს სვეტი წაიკითხე.

| სახეობა | რას ნიშნავს |
|---|---|
| **გადაწყვეტილება** | ავტორიტეტულია. კოდი ამას მიჰყვება. თუ კოდი და ეს ერთმანეთს არ ემთხვევა, ეს შეცდომაა და უნდა გასწორდეს |
| **ცნობარი** | მიმდინარე აღწერა. სწორია, სანამ საწინააღმდეგო არ დამტკიცდება |
| **გეგმა** | განზრახვა და მისი უკან დაბრუნების გზა. შესრულების სტატუსი ცალკე იკითხება |
| **მიღება** | რა დამტკიცდა რეალურად და რა მტკიცებულებით |
| **ისტორია** | დათარიღებული სნეპშოტი `archive/`-ში. **მიმდინარე კოდს აღარ აღწერს.** არ იშლება და არ სწორდება — მიგნება ინარჩუნებს თავის თავდაპირველ ტექსტს |

მუშა წესები კოდისთვის `AGENTS.md`-შია, repo-ს ძირში, არა აქ.

---

## გადაწყვეტილებები — ავტორიტეტული

| ფაილი | თარიღი | რაზეა |
|---|---|---|
| [`PRODUCT_OWNER_DECISIONS_KA.md`](PRODUCT_OWNER_DECISIONS_KA.md) | 2026-09-21 | პროდუქტის ქცევის ოფიციალური რეესტრი (PO-nn / DEC-nn) |
| [`ACCESS_CONTRACT_MATRIX_KA.md`](ACCESS_CONTRACT_MATRIX_KA.md) | 2026-09-21 | თითო ენდპოინტზე: ვინ იძახებს დღეს და ვინ უნდა იძახებდეს. **მანქანურად შემოწმებადი** — `AccessContractCoverageTest` build-ს ვარდენს შეუსაბამობაზე |
| [`ENTERPRISE_READINESS_DECISIONS_KA.md`](ENTERPRISE_READINESS_DECISIONS_KA.md) | 2026-08-28 | DEC-001… — რომ იგივე გადაწყვეტილება თავიდან არ მიიღოს ვინმემ |
| [`ROLLOUT_ROLLBACK_KA.md`](ROLLOUT_ROLLBACK_KA.md) | 2026-09-21 | თითო `ROLLOUT_*` დროშა: რას აკეთებს და როგორ ბრუნდება უკან. `RolloutSwitchWiringTest` ამოწმებს |
| [`PRODUCT_UX_REQUIREMENTS_KA.md`](PRODUCT_UX_REQUIREMENTS_KA.md) | 2026-08-22 | დადასტურებული პროდუქტული/UX მოთხოვნები და ღიად დარჩენილი კითხვები |

## ცნობარი — მიმდინარე მდგომარეობა

| ფაილი | თარიღი | რაზეა |
|---|---|---|
| [`HOW_IT_WORKS_KA.md`](HOW_IT_WORKS_KA.md) | 2026-08-31 | პრინციპები მარტივი ენით, პროდუქტის მფლობელისთვის |
| [`PROJECT_TECHNOLOGY_GUIDE_KA.md`](PROJECT_TECHNOLOGY_GUIDE_KA.md) | 2026-09-21 | 31 ტექნოლოგიური ბარათი არაპროგრამისტისთვის. თავად აღნიშნავს, რომელი ბარათია დაძველებული |
| [`RUN_LOCALLY.md`](RUN_LOCALLY.md) | 2026-08-20 | ლოკალური გაშვება, Windows-ის დათქმებით (Docker-ს ≥6 GB სჭირდება) |
| [`PRESENTATION_RUNBOOK_KA.md`](PRESENTATION_RUNBOOK_KA.md) | 2026-09-21 | დემო სტეკი პორტ 8081-ზე: მომზადება, ანგარიშები, 25-წუთიანი სცენარი |
| [`LEGACY_CONTENT_IMPORT_KA.md`](LEGACY_CONTENT_IMPORT_KA.md) | 2026-08-31 | 122 ძველი სტატიის შემოტანა Oracle-ში |
| [`SUPPLY_CHAIN_SBOM_KA.md`](SUPPLY_CHAIN_SBOM_KA.md) | 2026-08-31 | SBOM და მოწყვლადობის სკანირება CI-ში |
| [`SYSTEM_ADMIN_EXPORT_INVENTORY_KA.md`](SYSTEM_ADMIN_EXPORT_INVENTORY_KA.md) | 2026-08-22 | რა მონაცემი შედის SYSTEM_ADMIN-ის ექსპორტში და რა — არა |

## გეგმები

| ფაილი | თარიღი | რაზეა |
|---|---|---|
| [`IMPLEMENTATION_PLAN_KA.md`](IMPLEMENTATION_PLAN_KA.md) | 2026-08-23 | ეტაპები A–G. სტატუსი: A–F დასრულებულია |
| [`UI_UX_REDESIGN_PLAN_KA.md`](UI_UX_REDESIGN_PLAN_KA.md) | 2026-08-22 | სამიზნე ინფორმაციული არქიტექტურა, ეკრან-ეკრან. **სნეპშოტია**: შესრულების სტატუსი 2026-08-21-ზე გაყინულია |
| [`ORG_ACCESS_ARCHITECTURE_PLAN_KA.md`](ORG_ACCESS_ARCHITECTURE_PLAN_KA.md) | 2026-08-23 | ორგანიზაციული იერარქია და შერეული უფლებები |
| [`ENTERPRISE_DEVELOPMENT_SCENARIOS_KA.md`](ENTERPRISE_DEVELOPMENT_SCENARIOS_KA.md) | 2026-08-28 | სტრატეგიული კვლევა: 72 იდეა, 3 საინვესტიციო სცენარი, 12/24/36-თვიანი გზამკვლევი |

## IT დეპარტამენტთან

| ფაილი | თარიღი | რაზეა |
|---|---|---|
| [`QUESTIONS_FOR_IT.md`](QUESTIONS_FOR_IT.md) | 2026-09-21 | **ოფიციალური მოკლე რეესტრი.** 13 კითხვა; პასუხები აქ ჩაიწერება. ახალი კითხვა აქ ემატება, არა მფლობელს |
| [`IT_REQUEST_AUTH_FOLLOWUP_KA.md`](IT_REQUEST_AUTH_FOLLOWUP_KA.md) | 2026-09-18 | №13 წერილის სახით — ავტორიზაციის პასუხის დაზუსტება. გასაგზავნად მზადაა |
| [`IT_DISCOVERY_REQUEST_KA.md`](IT_DISCOVERY_REQUEST_KA.md) | 2026-08-22 | 12 შეკრული მოთხოვნა — ეს იგზავნება |
| [`IT_DISCOVERY_QUESTIONNAIRE_KA.md`](IT_DISCOVERY_QUESTIONNAIRE_KA.md) | 2026-08-29 | 93-პუნქტიანი ტექნიკური ჩამონათვალი. მთლიანად ერთ ადამიანს არ ეგზავნება |
| [`ENTERPRISE_READINESS_EXTERNAL_DEPENDENCIES_KA.md`](ENTERPRISE_READINESS_EXTERNAL_DEPENDENCIES_KA.md) | 2026-08-28 | EXT-nnn: გარე ბლოკერები (IAM, ქსელი, DBA) |

## მიღება და UAT

| ფაილი | თარიღი | რაზეა |
|---|---|---|
| [`RELEASE_READINESS_LEDGER_KA.md`](RELEASE_READINESS_LEDGER_KA.md) | 2026-09-25 | **გამოშვების ლედჯერი.** სია A — ჩვენზე დამოკიდებული, სია B — სხვებზე, თითოეული მტკიცებულებით. `scripts/readiness_check.py` მტკიცებულებას ამოწმებს, სიტყვა „დახურულს“ არ ენდობა |
| [`ENTERPRISE_READINESS_ACCEPTANCE_MATRIX_KA.md`](ENTERPRISE_READINESS_ACCEPTANCE_MATRIX_KA.md) | 2026-08-31 | WS-nn მიღების მატრიცა. ვერდიქტი: NOT READY |
| [`uat/UAT_00_RUNBOOK_KA.md`](uat/UAT_00_RUNBOOK_KA.md) | 2026-08-31 | UAT-ის გაშვების ინსტრუქცია — **აქ იწყება** |
| [`uat/UAT_01_OPERATOR_KA.md`](uat/UAT_01_OPERATOR_KA.md) | 2026-08-29 | ოპერატორის სცენარები + შესრულების შედეგი |
| [`uat/UAT_02_MANAGER_KA.md`](uat/UAT_02_MANAGER_KA.md) | 2026-08-29 | მენეჯერის სცენარები |
| [`uat/UAT_03_CONTENT_KA.md`](uat/UAT_03_CONTENT_KA.md) | 2026-08-31 | კონტენტ-ადმინის სცენარები |
| [`uat/UAT_04_ADMIN_KA.md`](uat/UAT_04_ADMIN_KA.md) | 2026-08-29 (შენიშვნა 2026-09-21) | სისტემური ადმინის სცენარები |
| [`uat/UAT_05_CROSS_ROLE_KA.md`](uat/UAT_05_CROSS_ROLE_KA.md) | 2026-08-29 | როლებს შორის იზოლაცია |
| [`uat/UAT_06_ADVERSARIAL_KA.md`](uat/UAT_06_ADVERSARIAL_KA.md) | 2026-08-31 | მტრული ტესტირება, F-1…F-4 მიგნებებით და დათარიღებული გადამოწმებით |
| [`uat/UAT_07_CONTENT_CORRECTNESS_KA.md`](uat/UAT_07_CONTENT_CORRECTNESS_KA.md) | 2026-08-31 | 122 შემოტანილი სტატიის სისწორე |
| [`uat/UAT_SIGNOFF_KA.md`](uat/UAT_SIGNOFF_KA.md) | 2026-08-29 (შენიშვნა 2026-09-21) | მიღების ოქმი. **ხელმოწერის ველები ჯერ ცარიელია** |

## მანქანური არტეფაქტები

| ფაილი | რაზეა |
|---|---|
| [`api-contract/`](api-contract/) | `openapi.json` და `golden_master_v1.json` — API-ის ჩაწერილი ფორმა. `article-visibility-cases.json` — შემთხვევები, რომლებზეც Java-ს და Angular-ის ხილვადობის წესები უნდა ემთხვეოდნენ |
| [`i18n-catalog/README.md`](i18n-catalog/README.md) | ქართული სტრიქონების კატალოგის წარმომავლობა (2026-08-06). ამოღებულია უკვე წაშლილი `static/js` და `routers/`-იდან |
| [`wireframes/`](wireframes/) | `system-admin-wireframes.html` |

---

## `archive/` — ისტორია

**აქ არაფერი სწორდება.** მიგნება ინარჩუნებს თავდაპირველ ტექსტს; რაც მას შემდეგ
შეიცვალა, ქვემოთ დაერთვის დათარიღებული განკარგულებით. თითოეულ ფაილს ზემოდან
აწერია, რომ არქივია.

### `archive/audits/` — დათარიღებული აუდიტები

| ფაილი | თარიღი | რაზეა |
|---|---|---|
| [`READINESS_REPORT_2026-08-23.md`](archive/audits/READINESS_REPORT_2026-08-23.md) | 2026-08-23 | production-მზაობის აუდიტი. ვერდიქტი NOT READY, 14/14 exit criteria |
| [`ENTERPRISE_READINESS_REPORT_2026-08-25_KA.md`](archive/audits/ENTERPRISE_READINESS_REPORT_2026-08-25_KA.md) | 2026-08-25 | P0/P1 პარტიების შესრულების ანგარიში |
| [`ENTERPRISE_READINESS_TEST_EVIDENCE_KA.md`](archive/audits/ENTERPRISE_READINESS_TEST_EVIDENCE_KA.md) | 2026-08-27 | EV-nnn: ბრძანების დონის მტკიცებულებები |
| [`OPUS5_AUDIT_SUMMARY.md`](archive/audits/OPUS5_AUDIT_SUMMARY.md) | 2026-08-14 | ოთხი OPUS5 აუდიტის შემაჯამებელი — **აქედან დაიწყე** |
| [`OPUS5_AUDIT_1_SECURITY.md`](archive/audits/OPUS5_AUDIT_1_SECURITY.md) | 2026-08-14 | SEC-01…SEC-16 |
| [`OPUS5_AUDIT_2_BACKEND_LOGIC.md`](archive/audits/OPUS5_AUDIT_2_BACKEND_LOGIC.md) | 2026-08-14 | BL-01…BL-14 |
| [`OPUS5_AUDIT_3_FRONTEND.md`](archive/audits/OPUS5_AUDIT_3_FRONTEND.md) | 2026-08-14 | FE-01…FE-10 |
| [`OPUS5_AUDIT_4_PRODUCTION_READINESS.md`](archive/audits/OPUS5_AUDIT_4_PRODUCTION_READINESS.md) | 2026-08-14 | PR-01…PR-14 |
| [`CODE_AUDIT_2026-07-11.md`](archive/audits/CODE_AUDIT_2026-07-11.md) | 2026-07-11 | სრული აუდიტი — **Python-ის სტეკზე**, რომელიც აღარ არსებობს |
| [`UI_VISUAL_AUDIT_2026-08-21_KA.md`](archive/audits/UI_VISUAL_AUDIT_2026-08-21_KA.md) | 2026-08-21 | ვიზუალური/UX სნეპშოტი |
| [`UX_IMPLEMENTATION_DECISIONS_2026-08-24_KA.md`](archive/audits/UX_IMPLEMENTATION_DECISIONS_2026-08-24_KA.md) | 2026-08-24 | ერთი დღის იმპლემენტაციური გადაწყვეტილებები |
| [`AI_CONTEXT_AUDIT_2026-09-21_KA.md`](archive/audits/AI_CONTEXT_AUDIT_2026-09-21_KA.md) | 2026-09-21 | AGENTS.md / სქილები / docs / AI-ის მეხსიერება კოდთან — 28 მიგნება; E2E 46/46. ცხრილის 28-ვე მიგნება, settings.json-ის დაცვა და მეხსიერება გასწორდა — ოთხი დათარიღებული განკარგულება ბოლოშია; ღიაა მხოლოდ „გადასახედის" 2–7 |

### `archive/handoffs/` — სესიების ჩაბარებები

მოძველების ყველაზე მკაფიო მაგალითი: `HANDOFF.md` ბრძანებით იწყება
(„Claude-მა ჯერ ეს უნდა წაიკითხოს") და ბრენჩს ასახელებს, რომელიც
2026-08-27-ის შემდეგ აღარაა მიმდინარე.

| ფაილი | თარიღი | რაზეა |
|---|---|---|
| [`HANDOFF.md`](archive/handoffs/HANDOFF.md) | 2026-08-27 | აგენტის სესიის ჩაბარება; ბრენჩი და commit აღარაა HEAD |
| [`EV-REMOTE-2026-08-27_JAVA_REGRESSION_KA.md`](archive/handoffs/EV-REMOTE-2026-08-27_JAVA_REGRESSION_KA.md) | 2026-08-27 | დისტანციური სესიის რეგრესიის ჟურნალი |
| [`PHASE6_HANDOFF_KA.md`](archive/handoffs/PHASE6_HANDOFF_KA.md) | 2026-08-22 | ფაზა 6-ის ბრიფი და მისი მიმოხილვა |
| [`PHASE7_HANDOFF_KA.md`](archive/handoffs/PHASE7_HANDOFF_KA.md) | 2026-08-22 | ფაზა 7 |
| [`PHASE8_HANDOFF_KA.md`](archive/handoffs/PHASE8_HANDOFF_KA.md) | 2026-08-22 | ფაზა 8 |
| [`PHASE9A_HANDOFF_KA.md`](archive/handoffs/PHASE9A_HANDOFF_KA.md) | 2026-08-22 | ფაზა 9A |
| [`agent-handoff.md`](archive/handoffs/agent-handoff.md) | 2026-07-08 | უძველესი ჩაბარება; წაშლილ FastAPI სტეკს აღწერს როგორც მიმდინარეს |

### `archive/prompts/` — ერთჯერადი დავალებები AI-სთვის

ეს ფაილები ინსტრუქციებია, რომლითაც ზემოთ ჩამოთვლილი აუდიტები და ანგარიშები
დაიწერა. ისტორიაა, არა მოქმედი წესი.

| ფაილი | თარიღი | რა შექმნა |
|---|---|---|
| [`READINESS_AUDIT_PROMPT.md`](archive/prompts/READINESS_AUDIT_PROMPT.md) | 2026-08-23 | `READINESS_REPORT_2026-08-23.md` |
| [`OPUS5_AUDIT_PROMPTS.md`](archive/prompts/OPUS5_AUDIT_PROMPTS.md) | 2026-08-28 | ოთხი OPUS5 აუდიტი |
| [`OPUS5_FIX_PROMPTS.md`](archive/prompts/OPUS5_FIX_PROMPTS.md) | 2026-08-14 | OPUS5-ის მიგნებების გასწორება |
| [`MIGRATION_PROMPT_JAVA_ORACLE_ANGULAR.md`](archive/prompts/MIGRATION_PROMPT_JAVA_ORACLE_ANGULAR.md) | 2026-08-04 | `JAVA_ORACLE_ANGULAR_MIGRATION.md` |
| [`ENTERPRISE_READINESS_AI_EXECUTION_PLAN_KA.md`](archive/prompts/ENTERPRISE_READINESS_AI_EXECUTION_PLAN_KA.md) | 2026-08-28 | enterprise-readiness პროგრამის შესრულების ბრიფი |
| [`AI_BROWSER_TEST_PROMPT_KA.md`](archive/prompts/AI_BROWSER_TEST_PROMPT_KA.md) | 2026-08-28 | ბრაუზერით ხელით ტესტირების სცენარი #1 |
| [`AI_BROWSER_TEST_PROMPT_2_KA.md`](archive/prompts/AI_BROWSER_TEST_PROMPT_2_KA.md) | 2026-08-28 | სცენარი #2 — მიზეზ-შედეგობრივი ჯაჭვები |
| [`AI_BROWSER_TEST_PROMPT_3_KA.md`](archive/prompts/AI_BROWSER_TEST_PROMPT_3_KA.md) | 2026-08-29 | სცენარი #3 — იერარქია, ექსპორტი, შესაბამისობა |

### `archive/legacy-stack/` — წაშლილი Python აპლიკაცია

**არცერთი მათგანი აღარ აღწერს ამ კოდს.** სტეკი 2026-08-31-ს წაიშალა.

| ფაილი | თარიღი | რაზეა |
|---|---|---|
| [`ARCHITECTURE.md`](archive/legacy-stack/ARCHITECTURE.md) | 2026-07-20 | arc42 არქიტექტურა — FastAPI/PostgreSQL |
| [`PRODUCTION_HANDOVER.md`](archive/legacy-stack/PRODUCTION_HANDOVER.md) | 2026-07-19 | ძველი production ჩაბარება. თავად წერს: არ გამოიყენო ახლისთვის |
| [`admin-guide.md`](archive/legacy-stack/admin-guide.md) | 2026-06-13 | ადმინის სახელმძღვანელო ძველი ინტერფეისისთვის |
| [`SEED_GUIDE.md`](archive/legacy-stack/SEED_GUIDE.md) | 2026-07-13 | ოთხი ბრძანება `scripts/seed_portal.py`-სთვის, რომელიც აღარ არსებობს |
| [`analytics_audit.md`](archive/legacy-stack/analytics_audit.md) | 2026-06-26 | SQLAlchemy სტატისტიკის აუდიტი |
| [`article_versioning_blueprint.md`](archive/legacy-stack/article_versioning_blueprint.md) | 2026-06-27 | სტატიის ვერსირების გეგმა `main.py`-სთვის |
| [`csp_nonce_refactor_plan.md`](archive/legacy-stack/csp_nonce_refactor_plan.md) | 2026-06-26 | CSP nonce-ის გეგმა `base-layout.html`-სთვის |

### `archive/migration/` — Python → Java/Oracle/Angular

| ფაილი | თარიღი | რაზეა |
|---|---|---|
| [`JAVA_ORACLE_ANGULAR_MIGRATION.md`](archive/migration/JAVA_ORACLE_ANGULAR_MIGRATION.md) | 2026-08-14 | მიგრაციის მთავარი ჟურნალი — repo-ს ყველაზე დიდი ფაილი |
| [`TEST_PLAN_AND_RESULTS.md`](archive/migration/TEST_PLAN_AND_RESULTS.md) | 2026-08-13 | მიგრაციის ტესტ-გეგმა და შედეგები |
| [`test_acceptance_catalog.md`](archive/migration/test_acceptance_catalog.md) | 2026-07-29 | 103 ტესტის კატალოგი — **წაშლილი Python-ის სეიტის**, არა ახლის |
| [`SPECIFICATION.md`](archive/migration/SPECIFICATION.md) | 2026-07-18 | 2026-ის თავდაპირველი ერთგვერდიანი ბრიფი, საიდანაც ყველაფერი დაიწყო |
