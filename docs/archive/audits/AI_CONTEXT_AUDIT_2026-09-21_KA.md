> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# AI-ის კონტექსტის დამოუკიდებელი აუდიტი — 2026-09-21

**რა შემოწმდა:** ყველაფერი, რასაც AI ამ რეპოზიტორიაში კითხულობს — `AGENTS.md` (4), `CLAUDE.md` და
შიმები, პროექტის 4 სქილი, `.claude/settings.json` / `launch.json`, ცოცხალი დოკუმენტები `docs/`-ში
(`archive/`-ის გარდა), AI-ის მეხსიერება (23 ჩანაწერი) — და ერთი E2E გაშვება CI-ის წესით.
**ტოტი / HEAD:** `main` @ `ba0726c` (origin/main-თან თანაბარი).
**რეჟიმი:** მხოლოდ ანგარიში. კოდი, დოკუმენტები და მეხსიერება **არ შეცვლილა** — ყოველი მიგნება
შემოთავაზებული გასწორებით არის ჩაწერილი; რა გასწორდება, მფლობელი წყვეტს.
**ვინ:** Claude (Opus 5), ერთი სესია, ქვე-აგენტების გარეშე.

---

## 1. მოკლე შეჯამება

კოდი თავად კარგ მდგომარეობაშია: „ერთი წესი, ერთი ადგილი"-ს ათივე ფაილი არსებობს და იმას აკეთებს,
რაც წერია; ვერსიები, მიგრაციის ნომერი (V48) და endpoint-ების ჯამი (150) ზუსტად ემთხვევა. პრობლემა
ტექსტებშია — **AI-სთვის განკუთვნილი რამდენიმე ინსტრუქცია კოდს ეწინააღმდეგება**, და ოთხი მათგანი
სასწრაფოა. (1) სქილი და backend-ის შენიშვნები ამბობენ, რომ სისტემის კარი „ყველასთვის ღიაა"
(`permitAll`) — სინამდვილეში პირიქითაა, ჩაკეტილია და მხოლოდ შესვლის გვერდია ღია; AI უსაფრთხოებაზე
მცდარი სურათით იმუშავებს. (2) დემოს runbook წერს, რომ არასწორი პაროლი არ გაივლის — დემოზე
**ნებისმიერი პაროლი გადის** (ეს განზრახაა, მაგრამ runbook-ი საპირისპიროს ამბობს და პრეზენტატორმა
შეიძლება ხმამაღლა თქვას მცდარი რამ). (3) AGENTS.md წერს, რომ სატესტო ანგარიშები მუშაობს
`APP_ENV=development`-ით — საჭიროა მეორე ჩამრთველიც (`ALLOW_DEV_LOGIN=true`); ზუსტად ამით ჩავარდა
CI-ის პირველი E2E. (4) ახალი მიგრაციის სქილი არ ამბობს, რომ დემოსა და UAT-ის სიდერი მიგრაციის
ნომერს „ზუსტად 48"-ად ამოწმებს — შემდეგი მიგრაცია (V49) ორივე სტენდის მომზადებას ჩუმად გატეხავს.
დანარჩენი ძირითადად მოძველებული რიცხვები და თარიღებია (602→605, 44→42, V42/V47→V48, 40→44 PII).
მეხსიერების 23 ჩანაწერიდან 9 უკვე წაშლილ Python-სისტემას ან დასრულებულ მდგომარეობას აღწერს და
მომავალ სესიას შეცდომაში შეიყვანს. **ბრაუზერის ტესტები (E2E) 46-დან 46 გავიდა** PO-24-ის შემდეგ, CI-ის
წესით — ადმინის ცხრილის ახალმა სვეტმა არაფერი გატეხა; თუმცა თავად PO-24-ის ახალ ღილაკს ჯერ საკუთარი ტესტი არ აქვს (§5).

---

## 2. მიგნებები

სიმძიმე: **კრიტიკული** — AI ამის გამო არასწორად იმოქმედებს ან უსაფრთხოებაზე მცდარი მტკიცებაა;
**საშუალო** — მოძველებული ფაქტი; **დაბალი** — კოსმეტიკა. ★ = წინა სესიის მიერ უკვე ნაპოვნი, აქ დადასტურებული.

| № | სიმძიმე | ფაილი:ხაზი | რას ამბობს | რა არის სინამდვილეში (მტკიცებულება) | შემოთავაზებული გასწორება |
|---|---|---|---|---|---|
| 1 ★ | კრიტიკული | `.claude/skills/access-change/SKILL.md:8-9`; `java-backend/AGENTS.md:21`; კომენტარები `web/Guards.java:17,77`, `ControllerGuardConsolidationTest.java:28` | „`SecurityConfig` is `anyRequest().permitAll()`" | `SecurityConfig.java:152-156` — `.anyRequest().authenticated()`. ფილტრ-ჯაჭვი deny-by-default-ია; ანონიმურად ღიაა მხოლოდ `POST /api/auth/login`, `POST /api/auth/sso/start`, `GET /api/health` (`:58`, `:94`) და actuator-ის probe-ები (`:85-86`). როლის/უფლების შემოწმება კი მართლაც handler-შია (`require*`) | ორივე დოკუმენტში: „ჯაჭვი ითხოვს ავთენტიფიკაციას ყველგან, გარდა `ANONYMOUS_*` მასივებისა; როლი/უფლება/scope მოწმდება handler-ში `require*`-ით". კომენტარები `Guards.java`-სა და ტესტში — იგივე |
| 2 | კრიტიკული | `docs/PRESENTATION_RUNBOOK_KA.md:37`, `:69` | „`ALLOW_DEV_LOGIN=false`, ამიტომ სხვა პაროლი არ იმუშავებს"; verify ამოწმებს „ექვსივე მცდარი პაროლის უარყოფას" | `docker-compose.presentation.yml:18` — `x-allow-dev-login: &allow-dev-login "true"`. `AuthenticationService.java:86-90,98-101` — `DEV_TEST_EMAILS`-ისა და `presentation.*`-ისთვის პაროლი არ მოწმდება. verifier `seed_oracle_demo.py:1888`: `expected_wrong_password_status = 200 if dev_login else 401` — ანუ დღეს მცდარი პაროლი **200**-ს იღებს | runbook-ში: „დემოზე password-less შესვლა ჩართულია (`x-allow-dev-login`), ნებისმიერი პაროლი გადის; stakeholder-დემოსთვის გადართეთ `"false"`-ზე (compose-ის კომენტარი :5-11)". `:69` — „მცდარი პაროლის ქცევა შეესაბამება dev-login-ის რეჟიმს" |
| 3 | კრიტიკული | `AGENTS.md:42-44`; `.claude/skills/run-and-verify/SKILL.md:30-32` | „Any password logs in as admin@ … while `APP_ENV=development`" | საჭიროა **ორივე**: `APP_ENV` dev-სიიდან **და** `ALLOW_DEV_LOGIN=true` (`AuthenticationService.java:86-87`; `application.yml:152` default `false`). `ci.yml:357-367` ჩაწერს, რომ E2E-ის პირველი გაშვება ზუსტად ამით ჩავარდა („dev-login=disabled"). სიას აკლია `nino@magti.ge` (`AuthenticationService.java:34-36`) და `test_operator_*` / `presentation.*` პრეფიქსები | „…while `APP_ENV=development` **and** `ALLOW_DEV_LOGIN=true` (both compose-ის local ფაილი და `run-local.sh` ორივეს აყენებს)". სიაში დაემატოს `nino@` და პრეფიქსები |
| 4 | კრიტიკული | `.claude/skills/db-migration/SKILL.md:18-20` | ახალი მიგრაციისას განაახლე `AGENTS.md` და `java-backend/AGENTS.md` | `scripts/presentation/common.py:86` — `EXPECTED_FLYWAY_VERSION = "48"`; `seed_oracle_demo.py:285-288` ითხოვს Flyway-ს **ზუსტად** ამ ვერსიამდე, სხვაგვარად უარს ამბობს. იგივე სიდერს იყენებს UAT (`docker-compose.uat.yml:13-23`). არცერთი ტესტი ამას არ ამოწმებს — V49 დემოსა და UAT-ის seed/verify-ს ჩუმად გატეხავს | სქილის „Numbering" სექციაში: „…და `scripts/presentation/common.py`-ის `EXPECTED_FLYWAY_VERSION` იმავე commit-ში". სურვილისამებრ `DocumentedFactsTest`-ს დაემატოს ამ მნიშვნელობის შემოწმება |
| 5 ★ | საშუალო | `AGENTS.md:193-195`; `k8s/README_KA.md:195-197` | SSO 503-ს პასუხობს „until IT confirms the protocol" / „სანამ არ ვიცით… (SAML, OIDC თუ LDAP)" | IT-მ 2026-09-18-ს დაადასტურა: OAuth2 token endpoint, `grant_type=ldap_auth` (`QUESTIONS_FOR_IT.md:61-72`, სტატუსი 🟡). ახლა ბლოკავს №13 — რა მოდის პასუხში (`QUESTIONS_FOR_IT.md:497-536`) | „…answers 503 on purpose: the protocol is known (OAuth2 `ldap_auth`, QUESTIONS_FOR_IT №1), the adapter waits on №13 — what the response carries". k8s README-ში იგივე |
| 6 ★ | საშუალო | `AGENTS.md:88`; `README.md:37`; `run-and-verify/SKILL.md:38`; `PRESENTATION_RUNBOOK_KA.md:66`; `uat/UAT_SIGNOFF_KA.md:17`; `uat/UAT_04_ADMIN_KA.md:21` | „602-person org" / „602 მომხმარებელს" / „613 (602 + 11)" | სიდერი ზუსტად **605**-ს ამოწმებს (`seed_oracle_demo.py:573-574`, `:1473`, `:1739`): **600** ორგანიზაციაში (3 დეპარტამენტი × 5 ჯგუფი × (1 ლიდერი + 39 ოპერატორი), `:30-79` ფუნქციაში) + **5** ცენტრალური (`admin@`, `content@`, `content2-4@`, `:388-394`). 602 სწორი იყო 2026-09-01-მდე; `ac5cc7e`-ის მესიჯი: „org-count assertions move 602 -> 605" (content2-4 პიკერისთვის დაემატა). **ორივე ერთდროულად სწორი არ არის** | AGENTS/README/სქილში — „~600-person org" (მყარი, ყოველ ცვლილებაზე არ ძველდება). ზუსტი რიცხვი და განმარტება **ერთ ადგილას** — runbook-ში: „605 ანგარიში = 600 (3×5×40) + 5 ცენტრალური". UAT-ის დოკუმენტები მიღების მტკიცებულებაა (08-29-ზე 602 სწორი იყო) — ტექსტი არ შეიცვალოს, დაერთოს დათარიღებული შენიშვნა |
| 7 | საშუალო | `docs/PRESENTATION_RUNBOOK_KA.md:25`, `:65` | „შესრულდება Flyway V42"; verify ამოწმებს „Oracle/Flyway V42 context-ს" | `common.py:86` — ზუსტად `"48"`; backend-იც V48-მდე მიდის (E2E-ში: „Successfully applied 48 migrations … now at version v48") | „V42" → „V48" (ან „მიმდინარე უმაღლესი, იხ. `common.py`") |
| 8 | საშუალო | `AGENTS.md:72-73`; `run-and-verify/SKILL.md:67` | „44 test classes need a database" | `@RequiresOracle` კლასის დონეზე აქვს **42** კლასს (`grep -rln "^@RequiresOracle" src/test/java` → 42; კიდევ 2 ფაილი მხოლოდ ახსენებს). ანოტაციას `@Inherited` არ აქვს და abstract საბაზო კლასი არ არსებობს | „42" — ან რიცხვის ნაცვლად „every `@SpringBootTest` (≈40)", რომ ყოველ ტესტზე არ ძველდებოდეს (`DocumentedFactsTest`-ის ფილოსოფიაც ესაა) |
| 9 | საშუალო | `AGENTS.md:72-74`; `run-and-verify/SKILL.md:67-69`; `db-migration/SKILL.md:76-80` | „with `ORACLE_DB_URL` unset, Testcontainers starts one"; „…the only thing that proves the migration applies from empty" | `OracleTestcontainer.java:88-110,127-131` — ჯერ ამოწმებს default-ს `localhost:1521/orclpdb1`; თუ პასუხობს, კონტეინერი **არ** ეშვება. ამ კომპიუტერზე 19c პასუხობს → მიგრაცია უკვე გამოყენებულ `magti_app` სქემაზე ეშვება, არა ცარიელზე. `java-backend/AGENTS.md:15-17` და `README.md:68-70` ამას სწორად წერენ | სამივეში: „unset → uses the local Oracle if one answers at the default URL, otherwise starts a container". db-migration-ში: „from empty-ის დასამტკიცებლად `ORACLE_DB_URL` მიუთითეთ ცარიელ სქემაზე (მაგ. ახალი `MAGTI_QA`)" |
| 10 | საშუალო | `run-and-verify/SKILL.md:32-35` (+ მეხსიერება, §4) | JIT ანგარიშების დეპარტამენტები ინგლისური placeholder-ებია და ქართულ დეპარტამენტს არ ემთხვევა | `9fe45c4` (2026-08-21)-დან: `manager@`, `tech@` → „ტექნიკური", `info@` → „საინფორმაციო" (`AuthenticationService.java:43-48`); ძველ ინგლისურ მნიშვნელობას `synchronizeDevDepartment` (`:138-157`) შესვლისას ასწორებს. ინგლისური რჩება მხოლოდ `admin@` / `content@`-ს — ისინი ადმინები არიან და ხილვადობას ისედაც გვერდს უვლიან | „`tech@`/`info@`/`manager@` have real Georgian departments; for a *group*-level check create a `test_operator_*` user with a `… — ჯგუფი NN` value" |
| 11 | საშუალო | `docs/QUESTIONS_FOR_IT.md:221`, `:229-231` | „ამ რეპოზიტორიაში საერთოდ არ არის K8s-manifest-ები"; `FileStorageService` „ერთადერთი, რომელიც ფაილურ სისტემაში *წერს*" | იმავე ფაილის `:11-16`: „Kubernetes-ის ფაილები მზადაა (2026-08-31)". `FileStorageService.java:73-77`: „nothing in this backend writes to a filesystem at all … `store` writes a row" | ტექსტი არ წაიშალოს (ცოცხალი რეესტრია, თავდაპირველი ფორმულირება რჩება) — §6-ს ქვეშ დაერთოს დათარიღებული განკარგულება: „2026-08-31-დან manifest-ები არსებობს; ფაილები მხოლოდ BLOB-ად იწერება" |
| 12 | საშუალო | `docs/PRODUCT_OWNER_DECISIONS_KA.md:6`, `:21-24` | „განახლებულია: 2026-08-31"; „ღიაა ერთი: PO-21 … PO-01…PO-20 დახურულია" | ფაილს 2026-09-18/19-ს PO-22…PO-29 დაემატა; **PO-28** (`:677`) და **PO-29** (`:694`) 🔲 ღიაა. AI, რომელიც თავს კითხულობს, ჩათვლის, რომ ღიაა მხოლოდ PO-21 | თარიღი → 2026-09-19; `:21-24` → „ღიაა სამი: PO-21, PO-28, PO-29" |
| 13 | საშუალო | `docs/README.md:22`, `:23`, `:35`; `ACCESS_CONTRACT_MATRIX_KA.md:4` | თარიღები 2026-08-31 / 2026-08-31 / 2026-08-28; მატრიცის „ბოლო განახლება: 2026-08-26" | ბოლო შინაარსობრივი ცვლილება (`git log`): PO decisions — 2026-09-19 (`487b1ad`), მატრიცა — 2026-09-19 (`1753cd0`), presentation runbook — 2026-09-21 (`ba0726c`) | სამი სტრიქონის თარიღი და მატრიცის თავსართი განახლდეს. სურვილისამებრ `DocsIndexCoverageTest`-ს — გაფრთხილება, თუ ფაილის ბოლო commit ინდექსის თარიღზე ახალია |
| 14 | საშუალო | `docs/ACCESS_CONTRACT_MATRIX_KA.md:76`, `:77`, `:90`, `:224` | „40 ატარებს PII-ს"; „12 უკვე leadership-scoped"; „Article (26)"; „News (12)" | ცხრილის სტრიქონებით: `PII = **yes**` — **44** (+1 `content-dependent`); Article სექციაში **30** სტრიქონია, News-ში **14**; leadership: `GROUP…` scope-ით **11** სტრიქონი, „+ leadership" capability-ით **15** — 12 არცერთს არ ემთხვევა. ჯამი 150 = 150 `@*Mapping` ✓ (ამას `DocumentedFactsTest` ამოწმებს, დანარჩენს — არაფერი) | 40→44, 26→30, 12→14; „12 leadership-scoped"-ს დაემატოს დათვლის წესი ან გასწორდეს. ან რიცხვები ამოვიდეს და დარჩეს მხოლოდ ტესტით დაცული ჯამი |
| 15 | საშუალო | `AGENTS.md:163-164`; `QUESTIONS_FOR_IT.md:74-76`; `IT_REQUEST_AUTH_FOLLOWUP_KA.md:16-18` | „Every environment reads `${VAR}` with `:?`"; client secret-ის ადგილი gitignore-ული env-ფაილია და ყველა გარემო `${VAR}`-ით კითხულობს | `:?` მხოლოდ presentation/UAT compose-შია. `docker-compose.local.yml:47-74` ლოკალურ მნიშვნელობებს პირდაპირ წერს; `application.yml:15,171` dev default-ებს შეიცავს (production-ში მათ `ProductionSafetyGuard` უარყოფს — ეს ნაწილი სწორია). OAuth-ის client secret-ისთვის **არცერთი ცვლადი** არ არსებობს `application.yml`-ში, `k8s/`-ში ან compose-ში (`.env` არ გამიხსნია — აკრძალულია; იხ. §7) | AGENTS: „presentation/UAT compose use `:?`; `application.yml` has dev defaults that `ProductionSafetyGuard` refuses in production". IT-დოკუმენტებში: „ცვლადი ადაპტერთან ერთად დაემატება" — სანამ არ არსებობს |
| 16 | საშუალო | `k8s/README_KA.md:121` | Flyway „V1-დან V47-მდე" | უმაღლესი `V48` (`V48__login_attempts.sql`) | „V48-მდე" ან „ყველა მიგრაცია" |
| 17 | დაბალი | `AGENTS.md:22-23`; `PRODUCT_OWNER_DECISIONS_KA.md:371` | „deleted … (102 files)" | `a06b66a`: `git diff-tree` → 112 D (+6 M); commit-ის მესიჯიც „112 files" | 102 → 112 |
| 18 | დაბალი | `java-backend/AGENTS.md:43-44`; `db-migration/SKILL.md:58`, `:67` | „Five `V*MigrationShapeTest` classes" | ექვსია: V36, V39, V40, V41, V43, V45 — ყველა დოკუმენტის დაწერამდე არსებობდა (უახლესი 2026-08-28) | five → six |
| 19 | დაბალი | `angular-feature/SKILL.md:71` | „53 of 53 components" | 54 `@Component` (spec-ების გარეშე), ყველა `app-` პრეფიქსით (`portalDialog` — `@Directive`) | 54, ან „every component" |
| 20 | დაბალი | `angular-frontend/AGENTS.md:82`; `angular-feature/SKILL.md:81`; `ci.yml:154` | Prettier: „188 of 211 files" | `npx prettier --list-different "src/**/*.{ts,html,css}"` → 184 ფაილი 220-დან | რიცხვი ან „most files" |
| 21 | დაბალი | `angular-frontend/AGENTS.md:62` | ცვლილებები აღმოჩნდა „ten and twenty-one days late" | `ac5cc7e` — 2026-09-01, `c541c58` — 2026-08-28, აღმოჩენა 2026-09-08 → 7 და 11 დღე. რა ითვლებოდა 10-ად/21-ად, ვერ დავადგინე | „seven and eleven days" ან დაზუსტება |
| 22 | დაბალი | `access-change/SKILL.md:3` | „the eight coverage tests" | სქილის ცხრილში 7 ტესტია; `java-backend/AGENTS.md`-ში — 14 | „the coverage tests below" |
| 23 | დაბალი | `README.md:37` | „Persona buttons on the login page" | ღილაკები `ac5cc7e`-მ ჩაანაცვლა კასკადური პიკერით | „a persona picker on the login page" |
| 24 | დაბალი | `docs/ROLLOUT_ROLLBACK_KA.md:43` | ტოტი `claude/r5-complete-r6-planning` | არსებობს მხოლოდ `origin/claude/r5-complete-r6-planning-5exzf2` | ზუსტი სახელი |
| 25 | დაბალი | `angular-frontend/AGENTS.md:3-4`; `angular-feature/SKILL.md:8-9` | Node 22.22.3 — „so use it" | `@angular/cli` engines: `^22.22.3 \|\| ^24.15.0 \|\| >=26.0.0`; ამ კომპიუტერზე Node 24.19.0 და ყველაფერი მუშაობს; `run-local.sh:68-75` ახალს იღებს | „22.22.3 is the CI pin; 24.15+ also works" |
| 26 | დაბალი | root `CLAUDE.md:12`; `DocumentedFactsTest.java:88,161` | შიმები „one-line"; ტესტის შეტყობინება ასახელებს სექციას „Adding a migration" | შიმები 8-9 სტრიქონიანია (ახსნა + `@AGENTS.md`); სექციის სათაურია „## Migrations" | „a short `CLAUDE.md` whose only instruction is `@AGENTS.md`"; ტესტის ტექსტში სექციის სახელი |
| 27 | დაბალი | `EndpointPrincipalCoverageTest.java:25`; `OracleTestcontainer.java:49-50`; `RequiresOracle.java:29` | „every one of the 112 endpoints"; „the twenty test classes that need a database" | 150 endpoint; 42 კლასი | რიცხვები ამოვიდეს კომენტარებიდან |
| 28 | დაბალი | `PRODUCT_OWNER_DECISIONS_KA.md:603-605` (PO-22) | „`AuthenticationService`-ის JIT-შექმნა დღეს ნებისმიერ ავტორიზებულ ელფოსტას ქმნის" | დღეს JIT მხოლოდ `test_operator_*` / `presentation.*` / `DEV_TEST_EMAILS`-ს ქმნის და მხოლოდ dev-ჩამრთველებით (`AuthenticationService.java:86-93`); production-ში login 403-ს აბრუნებს (`AuthController.java:72-74`). გადაწყვეტილება თავად სწორია — არასწორია მხოლოდ დღევანდელი კოდის აღწერა | „SSO-ადაპტერის JIT-ს დაემატება დეპარტამენტის ფილტრი" |

**უკვე ნაპოვნი №3-ზე პასუხი** (რომელია სწორი და სად ეწეროს): სწორია სიდერი — **605** ანგარიში. 602
2026-09-01-მდე იყო სწორი. განმარტება ერთ ადგილას უნდა ეწეროს — `PRESENTATION_RUNBOOK_KA.md`-ში
(„605 = 600 ორგანიზაციაში + 5 ცენტრალური"); AGENTS/README/სქილმა უნდა თქვას „~600", რომ მომდევნო
ცვლილებამ სამი ფაილი ისევ არ დააძველოს (იხ. №6).

---

## 3. გადასახედი — ჩაწერილი ან განზრახ გადაწყვეტილებები, რომლებიც შეიძლება ცდებოდეს

ეს **ხარვეზები არ არის** — ან ჩაწერილი გადაწყვეტილებაა, ან დოკუმენტი „განზრახ"-ად აღწერს.

1. **`.claude/settings.json`-ის allowlist საშიშ ბრძანებებს ნებართვის გარეშე უშვებს.** `Bash(docker compose *)`,
   `PowerShell(.\presentation.ps1 *)`, `PowerShell(.\uat.ps1 *)` ფარავს `docker compose … down -v`-ს (ბაზას შლის —
   `run-and-verify/SKILL.md:15`), `.\uat.ps1 reset -Force`-ს (`-Force` დადასტურებას გამოტოვებს — `uat.ps1:6-8,142`)
   და `.\presentation.ps1 test regression`-ს (წარმატებისას დემოს volume-ს თავიდან აგებს — runbook `:106-108`).
   `CLAUDE.md` allowlist-ს განზრახად აღწერს („so a new session does not re-approve"), მაგრამ ეს ზუსტად ის
   ოპერაციებია, რომლებიც მფლობელმა აკრძალა. **შეთავაზება:** `deny`-ში დაემატოს `Bash(docker compose * down -v*)`,
   `Bash(docker compose * down --volumes*)`, `PowerShell(.\uat.ps1 reset*)`, `PowerShell(.\presentation.ps1 reset*)`,
   `PowerShell(.\presentation.ps1 test regression*)`.
2. **დემოზე password-less შესვლა ჩართულია** (`docker-compose.presentation.yml:5-18`, განზრახ). compose-ის
   კომენტარი თავად ამბობს: stakeholder-დემოზე, სადაც ეს პროდუქტს არასწორად წარმოაჩენდა, გადართეთ `"false"`-ზე.
   runbook ამ არჩევანს არ ახსენებს (იხ. №2).
3. **ადმინს შეუძლია კოლეგის პირადი დრაფტის გახსნა id-ით** (`ArticleVisibility.java:37-67`, „open question,
   deliberately not settled"; `ArticleVisibilityDraftTest`). სიაში დრაფტი არ ჩანს, id-ით კი იხსნება — ორი გზა
   განსხვავებულ პასუხს იძლევა. პროდუქტის გადაწყვეტილებაა.
4. **`requireSystemAdmin` ცხრაჯერ, ოთხი სხვადასხვა უარის ტექსტით** (`Guards.java:34-56`,
   `ControllerGuardConsolidationTest`). ცნობილი და ჩაწერილია; გადაწყვეტილებას ელოდება — რა ეწეროს უარს.
5. **`ROLLOUT_LEADERSHIP_SCOPE` / `ROLLOUT_COMPLIANCE_ELIGIBILITY`-ს არაფერი კითხულობს** (`ROLLOUT_ROLLBACK_KA.md:22-47`,
   `RolloutSwitchWiringTest`). k8s-ში და compose-ში მაინც დგას; დოკუმენტი თავად ითხოვს გადაწყვეტას — wiring ან
   მოხსნა.
6. **NetworkPolicy „სხვა კავშირი არცერთს არ სჭირდება"** (`k8s/README_KA.md:207-214`) — დღეს სწორია, მაგრამ
   არჩეული SSO (`oauth.magticom.ge`) backend-ს გამავალ 443-ს მოსთხოვს. IT-ის წერილის №5 ამას უკვე ეკითხება.
7. **შერწყმული ლოკალური ტოტები არ წაშლილა** — `claude/agent-ready-repo`, `claude/save-wip-and-fix-container-build`,
   `claude/visual-credibility` და სამი `codex/*` სრულად `main`-შია (`git merge-base --is-ancestor` → yes).
   `AGENTS.md:178-180` worktree-ს ტოტებზე ამბობს „remove in the same session"; worktree აღარ არსებობს, ტოტები კი დარჩა.

---

## 4. მეხსიერება

`C:\Users\nikaa\.claude\projects\C--Projects-Magti-base\memory\` — 23 ჩანაწერი. **`MEMORY.md`-ის 23-ივე
სტრიქონი არსებულ ფაილზე მიუთითებს და ყველა ფაილი ინდექსშია.** თუმცა ინდექსის ორი სტრიქონი საკუთარ ფაილს
ეწინააღმდეგება (იხ. `parallel-branches…` და `agent-readiness…`).

| ჩანაწერი | სტატუსი | მიზეზი |
|---|---|---|
| `user-technical-experience-level` | სწორია | მომხმარებლის შესახებ; ამ დავალების ტექსტი ადასტურებს (არატექნიკური მფლობელი) |
| `verify-design-request-paths` | სწორია | ქცევის წესი (ციტირებული ფაილების გადამოწმება); ძველი მაგალითები Python-ისაა, წესი მოქმედებს |
| `user-prefers-thorough-cleanup` | სწორია | ქცევის წესი; მაგალითები Python-ის დროინდელია |
| `feedback-fix-bugs-found-during-java-port` | სწორია (ისტორიული) | პორტირება დასრულდა; ბილიკი `docs/MIGRATION_PROMPT…` ახლა `docs/archive/prompts/`-შია |
| `feedback-explain-in-concrete-repo-terms` | სწორია | ბილიკი `docs/agents/HANDOFF.md` ახლა `docs/archive/handoffs/HANDOFF.md`-ია |
| `verify-branch-before-merging` | სწორია | ქცევის წესი |
| `docker-desktop-stale-socket-bug` | სწორია | 2026-09-21-ით განახლებული; რეპოზიტორიას არ ეწინააღმდეგება (გარე მდგომარეობა — ვერ გადავამოწმე) |
| `xsrf-token-rotates-every-request` | სწორია | `b05238a` `main`-შია; `SessionLifetimeCsrfTokenRepository` + `NullAuthenticatedSessionStrategy` — `SecurityConfig.java:100,141`; ტესტი `SecurityConfigIntegrationTest` არსებობს |
| `presentation-stack-demo-traps` | სწორია | `PORTAL_SECURITY_SESSION_IDLE_MINUTES: "480"` — `docker-compose.presentation.yml:92`; დანარჩენი (≈63/100 ოპერატორი) ბაზის მდგომარეობაა — ვერ გადავამოწმე |
| `auth-bypass-intentional-pending-ad` | მოძველებულია (ნაწილობრივ) | IT-ის 09-18 პასუხი სწორადაა ჩაწერილი, მაგრამ ბაიპასს აღწერს როგორც `security.py`-ის `not is_production` (წაშლილია); Java-ში საჭიროა `ALLOW_DEV_LOGIN=true`-ც; ახსენებს PO-22…28, PO-29 აკლია; `docs/CODE_AUDIT_2026-07-11.md` ახლა `docs/archive/audits/`-შია |
| `agent-readiness-pass-2026-09-05` | მოძველებულია | წერს „not merged to main and not pushed" — `claude/agent-ready-repo` `main`-შია; Prettier 188/211 → 184/220 |
| `java-oracle-angular-migration-initiative` (261 KB) | მოძველებულია | შემოწმდა ინდექსის სტრიქონი და თავი: „JIT ანგარიშებს ინგლისური დეპარტამენტი აქვს" — `9fe45c4`-მა გაასწორა (№10); „duplicated not symlinked" — 09-05-დან junction-ებია; „no more unblocked engineering work" (08-12) — შემდეგ PO-24 და სხვ. აშენდა; `docs/JAVA_ORACLE_ANGULAR_MIGRATION.md` ახლა `docs/archive/migration/`-შია. სწორია: Oracle 19c `localhost:1521/orclpdb1`, NG0950, dark-mode toggle, `dev-serve.cmd`, `/uploads` proxy, users-ის hard delete არ არსებობს, `/api/tags` და `/api/notifications/summary` არსებობს, `quiz-taker-modal` არსებობს |
| `banner-asset-gitignored` | მოძველებულია | `static/img/banner-bg.png` ფიზიკურად არსებობს, მაგრამ Angular მას არსად იყენებს; `static/css/custom-styles.css` აღარ არსებობს |
| `project-phase-launch-prep` | ეწინააღმდეგება | `docker-compose.yml`, „No CI at all", `PRODUCTION_HANDOVER.md` — წაშლილი/არქივი; CI არსებობს; „feature work done 07-15" — სექტემბერში ახალი ფუნქციები აშენდა (PO-24) |
| `live-app-runs-from-main-checkout` | ეწინააღმდეგება | `uvicorn main:app` აღარ არსებობს; ცოცხალი სტენდები Docker-შია (8081/8082) |
| `tailwind-build-setup` | ეწინააღმდეგება | `static/css/input.css`, `base-layout.html`, `article.html` — წაშლილია; Angular-ს საკუთარი Tailwind build აქვს |
| `mandatory-reading-nag-surfaces` | ეწინააღმდეგება | ყველა ფაილი (`base-layout.html`, `app-core.js`, `main.py`) წაშლილია; `magti_token` Angular-ში არ გვხვდება (httpOnly cookie) |
| `stabilization-2026-07-13-remediation` | ეწინააღმდეგება | `compliance_utils.py`, `main.py`, `sync_rbac.py` — არ არსებობს; ზოგადი გაკვეთილი („წაშლამდე მთელ repo-ში მოძებნე") კვლავ ძალაშია |
| `view-tracking-logging-overhaul-2026-07-13` | ეწინააღმდეგება | აღწერს `retention.py`-ის 180-დღიან purge-ს როგორც მიმდინარე არქიტექტურას — `AGENTS.md:170-172` და `db-migration/SKILL.md:84-87`: purge არ გადმოტანილა, განზრახ; Postgres trigger → Oracle `V28__audit_hash_chain.sql` |
| `dept-group-hierarchy-in-free-text` | ეწინააღმდეგება (ნაწილობრივ) | ძირითადი ფაქტი (იერარქია `users.department`-ის „{prefix} — ჯგუფი NN" ტექსტშია) სწორია და `ManagerScope`/`DepartmentMatcher` ამას ეყრდნობა; მაგრამ „team_id-ს არავინ ავსებს, არ დაეყრდნო" — V36-მა დაამატა `departments`/`leadership_assignments`, სიდერი `team_id`-ს ავსებს; `seed.py`, `models.py`, `main.py`, `static/js` წაშლილია |
| `kubernetes-deployment-target` | ეწინააღმდეგება (ნაწილობრივ) | სამიზნე (on-prem K8s) სწორია; მაგრამ „manifest-ები არ დაწერო" — `k8s/` 2026-08-31-დან არსებობს; „ამ კომპიუტერზე Docker არ არის" — Docker დაყენებულია და ორი სტენდი მუშაობს |
| `parallel-branches-pending-merge` | ეწინააღმდეგება | წერს „main is 33 commits ahead of origin/main, still unpushed" — `git rev-list origin/main..main` → 0; reflog: `origin/main@{2026-09-21}: update by push` (`ba0726c`). ინდექსის სტრიქონი („visual-credibility (21 commits) … unpushed") საკუთარ ფაილსაც ეწინააღმდეგება (ფაილში — merged 09-19) |

**შეთავაზება:** ცხრა „ეწინააღმდეგება" ჩანაწერიდან Python-ის შვიდი ან წაიშალოს, ან თავში დაეწეროს „ისტორიული —
Python-ის სტეკი 2026-08-31-ს წაიშალა"; `parallel-branches…` და `agent-readiness…` მოკლედ განახლდეს; ინდექსის
სტრიქონები ფაილებს გაუსწორდეს. (მეხსიერება ამ აუდიტში არ შეცვლილა.)

---

## 5. E2E შედეგი

**შედეგი: 46 / 46 გავიდა, 0 ჩავარდა, 0 გამოტოვებული, 0 flaky** (3.9 წუთი). გაშვება ერთხელ, 2026-09-21 10:50–10:54.

**როგორ გაეშვა** (`ci.yml`-ის `e2e` job-ის ნაბიჯები, ლოკალურ Oracle-ზე ადაპტირებული — იგივე სქემა, რაც წინა 46/46-ში):

| ნაბიჯი | რა გაკეთდა | შედეგი |
|---|---|---|
| backend build | `java-backend: ./mvnw.cmd -B -DskipTests package` (CI: `mvn -B -DskipTests package`) | jar 10:47 |
| ბაზა | ახალი `MAGTI_QA` სქემა ლოკალურ 19c-ზე (`localhost:1521/orclpdb1`) — `scripts/presentation/qa_schema_create.sql`, `sysdba`-თი | ცარიელი სქემა |
| backend | `java -jar … --server.port=8090`, `APP_ENV=development`, `ALLOW_DEV_LOGIN=true`, `COOKIE_SECURE=false` | ~20 წმ; `Successfully applied 48 migrations … now at version v48`; `Startup security config: app-env=development, dev-login=ENABLED …` |
| JIT შემოწმება | `POST :8090/api/auth/login` `admin@magti.ge` / ნებისმიერი პაროლი | HTTP 200 |
| frontend | `npx ng serve --port 4201 --proxy-config proxy.e2e.conf.json` | ~38 წმ; `/api/health` პროქსით → 200 |
| E2E | `npx playwright test` (baseURL — `playwright.config.ts`-ის default `:4201`), Chromium 1234 / Playwright 1.62.1, 1 worker | **46 passed** |
| დასრულება | ორივე პროცესი გაჩერდა; `MAGTI_QA` წაიშალა (`qa_schema_drop.sql`); 8090/4201 თავისუფალია | `:8081` და `:8082` `/api/health` → `ok` (არ შეხებიან) |

**CI-სგან განსხვავება, გულწრფელად:** CI ახალ XE 21c კონტეინერს იყენებს, აქ — ლოკალური 19c-ის ახალი სქემა;
CI `npm ci`-ს უშვებს, აქ არსებული `node_modules` გამოვიყენე (`npm ls` — lockfile-თან სინქრონშია); Node 24.19.0
(CI — 22.22.3, `.nvmrc`); პორტები 8090/4201 (CI — 8080/4200).

**PO-24-ის ცხრილი:** `admin-users.spec.ts`-ის ორივე ტესტი გავიდა — მონიშვნის სვეტმა არსებული სცენარები არ გატეხა.
მაგრამ **ახალ ფუნქციას (ფილტრი „ბოლო შესვლა", მონიშვნა, „მონიშნულების გათიშვა") E2E ტესტი არ აქვს** — `e2e/`-ში
`bulk-deactivate`-ს არაფერი ეხება. ეს ტესტის ხარვეზია, არა ჩავარდნა.

ამონარიდი (ბოლო სტრიქონები):

```text
  ok 14 [chromium] › e2e\admin-users.spec.ts:24:7 › admin users › directory-owned creation and password reset are fail-closed (2.9s)
  ok 15 [chromium] › e2e\admin-users.spec.ts:54:7 › admin users › permission override saves while AD identity fields remain read-only (3.8s)
  …
  ok 45 [chromium] › e2e\shell-and-stats.spec.ts:241:7 › admin dashboard and videos › a category page comes back to where it was opened from (10.2s)
  ok 46 [chromium] › e2e\team-stats.spec.ts:4:5 › manager dashboard is scoped, interactive and export-fail-closed without a primary team (7.7s)

  46 passed (3.9m)
```

**ერთი დაკვირვება backend-ის ლოგიდან:** გაშვებისას ერთი `ERROR` ჩაიწერა — `Unhandled exception [dbd45c4d] on GET /api/articles`.
მიზეზი `ClientAbortException`-ია („An established connection was aborted by the software in your host machine"):
ბრაუზერმა გვერდი დატოვა, სანამ პასუხი იწერებოდა. პროდუქტის ბაგი არ არის და ტესტი არ ჩავარდნილა, მაგრამ
`GlobalExceptionHandler` ასეთ შემთხვევას ERROR-ად და სრული stack trace-ით წერს — production-ის ლოგში ეს ხმაური
იქნება. შეთავაზება (გადასაწყვეტი): client-abort DEBUG/WARN-ად, stack trace-ის გარეშე.

---

## 6. რა შევამოწმე და რა ემთხვევა

**AI-ის ინსტრუქციები ↔ კოდი**
- სტეკი: Spring Boot 4.1.0, Java 21 (`pom.xml`), Angular 22 (`@angular/core ^22.1.0`), Node 22.22.3 (`.nvmrc`) ✓.
- მიგრაციები: უმაღლესი `V48`, შემდეგი `V49`, `V37` არ არსებობს, `V36_1` არსებობს ✓; Flyway-მ E2E-ში 48 მიგრაცია გამოიყენა.
- „One rule, one place": ათივე ფაილი არსებობს და იმას აკეთებს, რაც წერია — `ManagerScope` (prefix-aware, `DepartmentMatcher`-ით), `ArticleVisibility` (draft clause admin bypass-ის ქვემოთ, ღია კითხვა კომენტარშიც), მისი Angular-სარკე და `article-visibility-cases.json`, `DepartmentMatcher.visibilityTargets` (null → `["All"]`), `Guards` (2 guard; `requireSystemAdmin` — ზუსტად 9 ასლი), `ClientIpResolver` + `TRUSTED_PROXIES` ცარიელი default, `JdbcLoginAttemptStore` (V48; in-memory — მხოლოდ ტესტისთვის, bean არ არის), `JwtAuthenticationFilter` (`tv` ↔ `token_version`, deactivated → 403), `FileAccessPolicy` → `ArticleVisibility`, ფაილურ სისტემაში ჩაწერა არსად, `PortalProperties.isProduction()` allowlist-ით, ერთადერთი `@RestControllerAdvice`.
- `java-backend/AGENTS.md`-ის 14 ტესტიდან 14 არსებობს; `AnonymousSurfaceTest`, `DenyByDefaultIntegrationTest` არსებობს; ArchUnit არ არის ✓.
- `ProductionSafetyGuard`: 48-სიმბოლოიანი მინიმუმი, placeholder-ები, `COOKIE_SECURE=false` production-ში → უარი ✓.
- პორტები: 8080 (compose local), 4200 (`run-local.sh`), 8081 / 8082 (ორივე `127.0.0.1`-ზე მუშაობს) ✓; UAT — 11 ანგარიში ✓; k8s — 12 განსხვავებული `IT-NN` ✓.
- `scripts/AGENTS.md`: ოთხი სიდერი, `magti_portal.db` ≈183 MB, `.gitattributes` LF, Ruff `E9`+`F`, `RUN_ORACLE_FIXTURE_TESTS` ✓.
- `angular-frontend/AGENTS.md`: `theme.service.ts` (manual toggle), `check-i18n.mjs`-ის ორი ფორმა და სამი შეცდომის ტიპი, `dev-serve.cmd`, `proxy.conf.json` (`/api` + `/uploads`), `signInAsPersona`, `inlineCritical: false` ✓.
- root `CLAUDE.md`: `settings.json` commit-შია და სამ env-ფაილს კრძალავს ✓; `settings.local.json` gitignore-შია ✓; `launch.json` — 2 ჩანაწერი ✓; ვენდორის 5 სქილი ადგილზეა (NTFS junction → `.agents/skills/`, `name:` ველი სწორი) ✓; სამი `CLAUDE.md` შიმი `@AGENTS.md`-ს შემოიტანს ✓.

**დოკუმენტები ერთმანეთთან**
- `QUESTIONS_FOR_IT.md` №1 (🟡, 09-18) ↔ `IT_REQUEST_AUTH_FOLLOWUP_KA.md` ↔ PO-22…PO-29 ↔ მეხსიერება — თანმიმდევრულია; №13-ის მოკლე ვერსია (1, 4-5, 6) წერილს ემთხვევა; README-ის „13 კითხვა" ✓.
- PO-24 (`bulk-deactivate`, `last_active`), PO-25 (`AuthController.java:72-74`), PO-29 (`user_permission_overrides` V36, `CapabilityService.java`) — კოდში არსებობს ✓.
- `ACCESS_CONTRACT_MATRIX_KA.md`: 150 სტრიქონი = 150 `@*Mapping` ✓; სექციების დანარჩენი თავსართები ემთხვევა.
- `ROLLOUT_ROLLBACK_KA.md`: ორ flag-ს არაფერი კითხულობს, `ROLLOUT_FILE_ENTITLEMENT`-ს — `UploadedFileController.java:76`; k8s-ში `"true"`; compose-ის მნიშვნელობები ✓.
- `k8s/README_KA.md`: replicas 2, რესურსები, `MetricsEndpointIntegrationTest`, `MIN_SECRET_LENGTH = 48` ✓.
- `docs/README.md`-ის აღწერები (31 ბარათი, 93 პუნქტი, 12 მოთხოვნა, 72 იდეა, NOT READY, A–F) ფაილების საკუთარ მტკიცებებს ემთხვევა; ყოველი ბმული არსებობს.
- runbook: 15 ადრე გათიშული ანგარიში და 9 „წასული" (3 × 75 დღე ×2 ჯგუფი + 3 × 130 დღე) — `seed_oracle_demo.py:122-132,492-498` ✓.

---

## 7. რა ვერ შევამოწმე და რატომ

- **`.env`, `.presentation.env`, `.uat.env`** — გახსნა აკრძალულია (დავალება + `.claude/settings.json`). ამიტომ ვერ დავადასტურე, რომ client secret მართლა env-ფაილშია (№15).
- **`docs/archive/`** — დავალებით გამორიცხული (გაყინული მტკიცებულება).
- **ვენდორის 5 სქილის შიგთავსი** — დავალებით მხოლოდ ადგილზე ყოფნა.
- **„12 leadership-scoped"-ის (№14) და „ten and twenty-one days"-ის (№21) საფუძველი** — დათვლის წესი არსად წერია; ვაჩვენე მხოლოდ, რომ ჩემი დათვლები არ ემთხვევა.
- **`java-oracle-angular-migration-initiative.md` (261 KB)** — სრულად არ წამიკითხავს; შემოწმდა ინდექსის სტრიქონი, თავი და ყველა მტკიცება, რომელიც ინდექსშია.
- **ბაზის მდგომარეობაზე დამოკიდებული მტკიცებები** (დემოს ≈63/100 ოპერატორი, დემო-სცენარის ეკრანები) — დემოს ბაზას არ შევხებივარ.
- **Docker Desktop-ის ბაგის ჩანაწერი** — გარე მდგომარეობაა, რეპოზიტორიიდან არ მოწმდება.
- **`verify-like-ci.sh`, Oracle-ის Java suite, GitHub-ის CI** — დავალების ნაწილი არ იყო და არ გამიშვია; ტესტებიდან გავუშვი მხოლოდ E2E და ინდექსის ორი ტესტი (§5, და ამ ფაილის დამატების შემდეგ `DocsIndexCoverageTest` + `DocumentedFactsTest` — 12 / 12 გავიდა).
- **ცოცხალი დოკუმენტები, რომლებიც დავალების სიაში არ იყო** (`HOW_IT_WORKS_KA.md`, `PROJECT_TECHNOLOGY_GUIDE_KA.md` და სხვ.) — შემოწმდა მხოლოდ ინდექსის დონეზე და SSO-ს ფორმულირებაზე ძებნით.

---

## განკარგულება — 2026-09-21 (იმავე დღეს): ოთხივე კრიტიკული მიგნება გასწორდა

მფლობელის მოთხოვნით. ზემოთ ტექსტი უცვლელია; იქ მითითებული ხაზები `ba0726c`-ს ეხება და გასწორებულ ფაილებში
ახლა ოდნავ წანაცვლებულია.

| № | რა შეიცვალა |
|---|---|
| 1 | „`anyRequest().permitAll()`" შეიცვალა სწორი აღწერით — ჯაჭვი deny-by-default-ია და მხოლოდ ავთენტიფიკაციას ამოწმებს; როლი/უფლება/scope handler-შია; დავიწყებული gate **ყველა შესულ თანამშრომელს** უღებს კარს (და არა „ყველას"). ფაილები: `.claude/skills/access-change/SKILL.md`, `java-backend/AGENTS.md`, `Guards.java` (ორი javadoc), `ControllerGuardConsolidationTest.java`, `EndpointPrincipalCoverageTest.java`, `EndpointGuardCoverageTest.java` (javadoc + ჩავარდნის შეტყობინება). ძებნამ იგივე მტკიცება იპოვა `docs/PROJECT_TECHNOLOGY_GUIDE_KA.md`-ის შვიდ ადგილას, რომელიც ამ აუდიტის ცხრილში არ იყო — იქ, ამ დოკუმენტის საკუთარი წესით, ტექსტი არ გადაწერილა; ზედა გაფრთხილებას დაემატა მესამე პუნქტი |
| 2 | `docs/PRESENTATION_RUNBOOK_KA.md` — დემოზე პაროლი არ მოწმდება (`x-allow-dev-login: "true"`), რატომ, რისი თქმა არ შეიძლება პრეზენტაციაზე და როგორ ირთვება `"false"`; verify-ის აღწერა ახლა ორივე რეჟიმს ასახავს |
| 3 | `AGENTS.md`, `.claude/skills/run-and-verify/SKILL.md` — სატესტო შესვლას **ორივე** სჭირდება, `APP_ENV=development` და `ALLOW_DEV_LOGIN=true`; სიას დაემატა `nino@magti.ge` და `test_operator_*` / `presentation.*` |
| 4 | `.claude/skills/db-migration/SKILL.md` და `java-backend/AGENTS.md` ასახელებს `scripts/presentation/common.py`-ის `EXPECTED_FLYWAY_VERSION`-ს. დაემატა ტესტი `DocumentedFactsTest.thePresentationSeederExpectsTheHighestMigration` — build ჩავარდება, თუ ეს რიცხვი უმაღლეს მიგრაციას ჩამორჩება. გადამოწმდა, რომ ტესტი მართლა ჭერს: `"47"`-ზე ჩავარდა, `"48"`-ზე გადის |

**გადამოწმება:** Java-ს DB-free suite (`./mvnw.cmd -B test -DexcludedGroups=oracle`) — 525 / 525 გავიდა.
პროდუქტის ქცევა არ შეცვლილა: Java-ში შეიცვალა მხოლოდ კომენტარები, ერთი ტესტის შეტყობინება და ერთი ახალი ტესტი.

**ღიად რჩება:** 12 საშუალო და 12 დაბალი მიგნება, §3 „გადასახედი" და მეხსიერება (§4) — ამ განკარგულებით არ
შეხებია. ყურადღება: №10 (`run-and-verify`-ის წინადადება „ინგლისური დეპარტამენტების" შესახებ) უშუალოდ
გასწორებული №3-ის შემდეგ დგას და კვლავ მოძველებულია; №13 — `docs/README.md`-ში runbook-ის თარიღი (2026-08-28)
ახლა კიდევ უფრო ჩამორჩება.

---

## განკარგულება №2 — 2026-09-21: საშუალო მიგნებები (№5–16) გასწორდა

მფლობელის მოთხოვნით. ზემოთ ტექსტი უცვლელია.

| № | რა შეიცვალა |
|---|---|
| 5 | `AGENTS.md`, `k8s/README_KA.md` — SSO: პროტოკოლი ცნობილია (OAuth2 `ldap_auth`, 2026-09-18), ადაპტერი ელოდება №13-ს |
| 6 | `AGENTS.md`, `README.md`, `run-and-verify` — „~600-person org"; ზუსტი რიცხვი და შემადგენლობა მხოლოდ `PRESENTATION_RUNBOOK_KA.md`-შია (605 = 600 + 5). UAT-ის ორ დოკუმენტს (`UAT_04`, `UAT_SIGNOFF`) რიცხვი არ შეეცვალა — დაერთო დათარიღებული შენიშვნა (UAT_04-ში: ხელახლა მომზადებულ სტენდზე 616 = 605 + 11). „602" იგივე მიზეზით შეიცვალა ოთხ კომენტარში/შეტყობინებაში, რომლებიც ცხრილში არ იყო: `uat.ps1:125`, `docker-compose.uat.yml:127`, `scripts/uat/Dockerfile:5`, `angular-frontend/e2e/helpers.ts:302` |
| 7 | runbook — „Flyway V42" → ზუსტად `EXPECTED_FLYWAY_VERSION` (`scripts/presentation/common.py`), რიცხვის გარეშე, რომ აღარ დაძველდეს |
| 8, 9 | `AGENTS.md`, `run-and-verify` — „44 test classes" → „every `@SpringBootTest`, about forty"; ახსნილია, რომ `ORACLE_DB_URL`-ის გარეშე ჯერ ლოკალური Oracle იცდება და Testcontainer მხოლოდ მაშინ ეშვება, თუ არაფერი პასუხობს. `db-migration` — რას ამტკიცებს Oracle-ის გაშვება რომელ ბაზაზე, და როგორ დავამტკიცოთ „from empty" (ახალი `MAGTI_QA` სქემა ან CI) |
| 10 | `run-and-verify` — რა დეპარტამენტი აქვს თითოეულ სატესტო ანგარიშს (`tech@`/`manager@` — ტექნიკური, `info@` — საინფორმაციო, `nino@` — `Support`, ადმინები — ინგლისური, მაგრამ ხილვადობას გვერდს უვლიან); ჯგუფის დონისთვის — `test_operator_*` |
| 11 | `QUESTIONS_FOR_IT.md` §6 — დათარიღებული დისპოზიცია; თავდაპირველი ტექსტი უცვლელია |
| 12 | `PRODUCT_OWNER_DECISIONS_KA.md` — თარიღი და „ღიაა სამი: PO-21, PO-28, PO-29" |
| 13 | `docs/README.md` — დღეს შეცვლილი ფაილების თარიღები; UAT-ის ორ ფაილს — „2026-08-29 (შენიშვნა 2026-09-21)", რომ არ ჩანდეს, თითქოს UAT თავიდან ჩატარდა. მატრიცის თავსართის თარიღი |
| 14 | `ACCESS_CONTRACT_MATRIX_KA.md` — PII 40 → 44 (+1 `content-dependent`), leadership-scoped 12 → 11 **დათვლის წესით** (`scope` იწყება `GROUP`-ით), Article (26) → (30), News (12) → (14). ისტორიის შემოწმებამ აჩვენა, რომ „12" და „40" ჩაწერისას უკვე არ ემთხვეოდა ცხრილს |
| 15 | `AGENTS.md` — `:?` მხოლოდ presentation/UAT compose-შია; local compose და `application.yml` dev მნიშვნელობებს შეიცავს, production-ს `ProductionSafetyGuard` იცავს. `QUESTIONS_FOR_IT.md` — client secret-ს კოდი ჯერ არსად კითხულობს. `IT_REQUEST_AUTH_FOLLOWUP_KA.md:16-18` ხელახლა წაკითხვისას მხოლოდ ადგილს ასახელებს და `${VAR}`-ზე არაფერს ამბობს — **არ შეცვლილა** (ადგილი `.env`-ში ვერ გადამოწმდება, ფაილის გახსნა აკრძალულია) |
| 16 | `k8s/README_KA.md` — „V1-დან V47-მდე" → „ყველა მიგრაცია, V1-დან" |

**გადამოწმება:** Java-ს DB-free suite — 525 / 525; `ACCESS_CONTRACT_MATRIX_KA.md`-ის სექციები ხელახლა დაითვალა — შეუსაბამობა აღარ არის, ჯამი 150 = 150. ცვლილებები მხოლოდ ტექსტია (დოკუმენტები, სქილები, კომენტარები, ერთი PowerShell-ის შეტყობინება); პროდუქტის ქცევა არ შეცვლილა. `docker-compose.uat.yml`-ში შეიცვალა მხოლოდ კომენტარი — `docker compose config` არ გამიშვია, რადგან `.uat.env`-ს წაიკითხავდა.

**ღიად რჩება:** 12 დაბალი მიგნება (№17–28), §3 „გადასახედი" და მეხსიერება (§4).
