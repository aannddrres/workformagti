# Magti Portal — გაშვების აუდიტის მიმდინარე შედეგი

**თარიღი:** 2026-09-24, Asia/Tbilisi
**ვერდიქტი:** **NO-GO** — production გაშვების ყველა პირობა ჯერ არ არის დამტკიცებული.

ეს ანგარიში ეხება `bb3349c34b9b2829998a391554302a6b8bd3bfe8`-ზე დაფუძნებულ
მიმდინარე სამუშაო ხეს. საწყისში იყო 116 შეცვლილი/ახალი ფაილი. ისინი ბაიტურად
გადავიტანეთ იზოლირებულ `C:\Projects\Magti-audit-candidate-20260924` checkout-ში;
ორიგინალური ხე, demo და UAT მოცულობები არ შეცვლილა. საწყისი ფაილების ჰეშები
ინახება `C:\Projects\Magti-audit-evidence-20260924\source-manifest.json`-ში.
ყოველი შემდგომი გამოსწორება ეკუთვნის ამ იზოლირებულ კანდიდატს. ეს ლოკალური
ცდის ანგარიშია; GitHub Actions-ის რეალური Linux შედეგი ცალკე საჭიროა.
საბოლოო კანდიდატი ფიქსირდება `codex/release-audit-20260924` branch-ზე;
საწყის `HEAD`-თან შედარებით 134 ფაილი შეიცვალა ან დაემატა. მათი SHA-256
მანიფესტია `C:\Projects\Magti-audit-evidence-20260924\candidate-final-manifest.json`.

## დამტკიცებული ლოკალური შემოწმებები

| შემოწმება | შედეგი და ზღვარი |
|---|---|
| Java DB-free | 580/580, 0 failure (`-DexcludedGroups=oracle`) |
| Java Oracle | 465/465, 0 failure/skip; ახალი Oracle XE 21c სქემა, Flyway V1–V50 (`-Dgroups=oracle`) |
| Angular | 166/166 unit; lint, production build და 603 ქართული/ინგლისური გასაღების თანხვედრა |
| Python seeders | Ruff pass; scanner-ით სრულ სწრაფ ცდაში pytest 37 pass, 3 fixture-პირობითი skip |
| Frontend დამოკიდებულებები | `npm audit --audit-level=moderate`: 0 მიგნება; Angular 22.1.3 და lockfile განახლდა |
| Secrets | Gitleaks 8.30.1: სამუშაო ხე და ახალი commit-ის ისტორია, 0 მიგნება; scanner-ის დადებითი კონტროლის 7/7 ტესტი |
| Chrome/Oracle/nginx | საბოლოო production nginx image-ით, retry-ის გარეშე: სუფთა `E2E6` სქემაზე 51/51 ჩვეულებრივი რიგით; ცალკე სუფთა `E2E7` სქემაზე შეცვლილი რიგით shard 2/2 — 23/23, შემდეგ shard 1/2 — 28/28 |
| Shipping nginx საზღვრები | 2/2: 2 MiB და 10 MiB ატვირთვა, 10 MiB+ უარი 413-ით; 60 წამს გადაცილებული upstream მოთხოვნა |
| ორი backend replica | ერთ სუფთა Oracle სქემაზე ორივე მზად; 50 უნიკალური წარმატებული migration V1–V50; პირველის token მეორემ მიიღო |
| Runtime image scan | განახლებული frontend image: Trivy HIGH/CRITICAL 0; backend image-ის OS პაკეტები: 0. Backend-ის Java ბიბლიოთეკების სრული სკანი ლოკალურად ვერ დასრულდა |
| Backend runtime image | პატჩიანი image ცალკე კონტეინერში ჩაირთო; readiness, health, შესვლა და `/api/users/me` წარმატებით გაიარა |

საბოლოო კოდის ცვლილებების შემდეგ `scripts/verify-like-ci.sh fast` თავიდან
გავიდა სრულად მწვანედ. ამ ცდამ Oracle ინტეგრაცია და shipping nginx E2E არ
გაიმეორა; მათი ზემოთ მითითებული შედეგები იგივე საბოლოო კოდზე ცალკე ცდებიდანაა.

ლოკალური image ID-ებია: frontend
`sha256:d92e7598ae8f60ad29e2180ba3e9c81fe3f2f215f54296a0cba41f729bd936d0`,
backend
`sha256:57cb337fc5b4d533c779d55bff3f023cbdb68545351fe06de0fabac4cafac060`.
ესენი ამ მანქანაზე აგებული image-ების ID-ებია; staging/registry digest ცალკე
უნდა ჩაიწეროს. Playwright/Trivy ლოკალური არტეფაქტები იმავე evidence
დირექტორიაშია; GitHub-ის artifact-ად ჯერ არ ატვირთულა.

## მიგნებები და განკარგულება

| ID | გამეორება/გავლენა | მფლობელი და გამოსწორება | დამადასტურებელი ტესტი | სტატუსი |
|---|---|---|---|---|
| A01 | საწყის `npm audit`-ში 7 moderate რჩევა; frontend dependency gate გამორთული იყო | Frontend: Angular 22.1.3, lockfile, shipped/dev audit CI-ში | `npm ci`, audit 0, Angular 166/166, build/lint | შიდა დახურული |
| A02 | `team-stats.spec.ts` სუფთა სქემაზე პირველი გაშვებისას ჯგუფის წევრს წინა ტესტის შექმნაზე ელოდებოდა | QA: საჭირო `tech@` თვითონ ტესტმა შექმნა | შეცვლილი რიგით 51/51; ჩვეულებრივი რიგით 51/51 | შიდა დახურული |
| A03 | Windows Docker Desktop-ზე host-ზე გაშვებულ timeout fixture-ს nginx კონტეინერი ვერ უკავშირდებოდა | QA/DevOps: fixture ცალკე Docker ქსელში გადავიდა | nginx smoke 2/2, შემდეგ სრული 51/51 | შიდა დახურული |
| A04 | ძველ nginx runtime image-ში 40 გამოსწორებადი HIGH/CRITICAL მიგნება, backend OS image-ში ერთი HIGH (`libexpat`) | DevOps: ორივე runtime base განახლდა/დაიპინა, Alpine პატჩი დაემატა, CI image gate შეიქმნა | ორივე განახლებული image-ის Trivy OS scan 0; frontend სრული scan 0 | შიდა დახურული; Linux CI ელოდება |
| A05 | ტექნიკური ინსტრუქცია ამტკიცებდა, რომ Flyway მთელ სერიას ერთ JVM-ში ატარებს; რეალურად ორი JVM 25/25 მიგრაციას მონაცვლეობით ასრულებდა | Backend/DBA: `AGENTS.md`-ები რეალურ ქცევას შეესაბამა | 50 უნიკალური წარმატებული row, ორივე replica მზად | დოკუმენტი დახურული; staging Oracle ცდა ღია |
| A06 | ერთ სრულ nginx E2E-ში რჩეულის POST კლიენტის 5-წამიანი შეწყვეტით დასრულდა (499); proxy-ის დაყოვნების ზუსტი მიზეზი არ დადასტურდა | Backend/DevOps: staging-ზე request-id, nginx upstream latency, JVM/Hikari და Oracle დროების კორელაცია; დადასტურებული მიზეზის გამოსწორება | HTTP 200-ის მოლოდინით შემდეგი ორი სუფთა E2E 51/51, მაგრამ Linux/staging latency ცდა ჯერ საჭიროა | ღია დაკვირვება |

**წარუმატებელი ცდები შენარჩუნებულია:** ერთ ჩვეულებრივ E2E გაშვებაში 47/51
გავიდა. ორ პირდაპირი ფაილის URL ტესტს `ROLLOUT_FILE_ENTITLEMENT=true` აკლდა
ლოკალურ backend-ზე; ეს პარამეტრი CI-ში უკვე სავალდებულოა. თემის ახალ ტესტში
გვერდის განმეორებითი დაუყოვნებელი ჩატვირთვა მოვაშორეთ. რჩეულის ერთ
მოთხოვნაზე nginx-მა კლიენტის 5-წამიანი შეწყვეტა (499) ჩაიწერა; ტესტი ახლა
HTTP პასუხს ელოდება და მის სტატუსსაც ამოწმებს. განმეორებით სრულ გაშვებებში
ეს არ გამეორებულა, მაგრამ ლოკალური proxy-ის მიზეზი არ არის ბოლომდე
დადგენილი (A06); Linux CI და staging latency მტკიცებულება საჭიროა. წარუმატებელი
გაშვება წარმატებულის რაოდენობაში არ შედის.

## სრული აუდიტის ჯერ ღია ნაწილი

- `security/ENDPOINT_COVERAGE_CANDIDATES_2026-09-24.csv` შეიცავს 150 API
  მოქმედებას, სავარაუდო ტესტების ოჯახებს და **150/150 `UNVERIFIED`**
  case-level შედეგს. 57-ს browser ტესტის კანდიდატი არ აქვს. QA/Backend-მა
  წარმატება, უარი და შეცდომა თითო route-ზე ზუსტ assertion-ს უნდა მიაბან.
- `security/ASVS_5_0_0_MATRIX.csv`: 345 ოფიციალური მოთხოვნიდან 11-ს აქვს
  ნაწილობრივი მტკიცებულება, 18 არის დასამტკიცებელი „არ ვრცელდება“, 316 კი
  ინდივიდუალურად განსახილველია. Security-ის სრული review არ ჩატარებულა.
- Java დამოკიდებულებების Trivy სკანმა ლოკალურ Docker-ში Java DB-ის დიდი
  განახლებისას `cannot allocate memory` დააბრუნა. CI-ის SBOM scan სავალდებულო
  gate-ია, მაგრამ ამ კანდიდატზე Linux runner-ის შედეგი ჯერ არ გვაქვს.
- `scripts/load/k6-staging-600.js` მზადაა 600 **განსხვავებული სინთეზური**
  ანგარიშით, ერთი ჩაწერით თითოეულზე და hard thresholds-ით; staging-ზე არ
  გაშვებულა. DB-ის 600 განსხვავებული ავტორის reconciliation აუცილებელია.
- ნამდვილი InfoPortal client/როლები, TLS/proxy/IP გზა, კომპანიის Oracle-ის
  ვერსია, ორი replica, Flyway, monitoring/alert, rollback, გარე audit checkpoint,
  BLOB-ებიანი backup-ის **სხვა გარემოში აღდგენა** და ოთხი როლის ბიზნეს UAT
  ჯერ დაუდასტურებელია. პასუხისმგებლები და კითხვები უკვე არის
  `QUESTIONS_FOR_IT.md`-ში და `IT_OPERATIONS_RUNBOOK_KA.md`-ში.

## GO/NO-GO წესი

**GO** მხოლოდ მაშინ, როცა დადასტურებული ხარვეზები დახურულია, 150 მოქმედებისა
და შესაბამისი ASVS კონტროლის დაფარვა დამტკიცებულია, სრული Linux CI და ორი
სუფთა E2E მწვანეა, 600-ანგარიშიანი დატვირთვა ზღვარს გადის მონაცემის
დაკარგვის გარეშე, staging/backup/rollback/გარე audit მტკიცებულებები არსებობს
და ოთხივე როლის UAT პროდუქტის მფლობელმა მიიღო. HTTP შეცდომა უნდა იყოს `<1%`,
ბიზნეს checks `>99%`, ძებნის p95 `<2s`, ხოლო identity mismatch და write
failure ზუსტად ნული. ნებისმიერი შეუმოწმებელი პირობა **NO-GO**-ა.

შემდეგი პასუხისმგებლები: Backend + QA ავსებენ route/state და ASVS ტესტების
კავშირებს; Security ამოწმებს applicability-სა და image/SBOM შედეგს; IT/IAM,
DevOps და DBA ასრულებენ staging-ისა და აღდგენის ოქმებს; ბიზნეს მფლობელი
აწერს ხელს UAT-ს. ამ გარე ქმედებებისთვის რეალური credentials რეპოზიტორიაში
არ უნდა მოხვდეს.

## 2026-09-24 — იზოლირებული კანდიდატის ხელახალი აუდიტი და განკარგულება

ეს დამატება ზემოთ მოცემულ ისტორიულ ანგარიშს **არ ცვლის**. ახალი საწყისი
კანდიდატი `3af21d621ad49a6b05c2e6613898706352dd0a9a` სუფთა იყო
`codex/release-audit-20260924` branch-ზე. აქ აღწერილი საბოლოო კოდი შეიცავს
Tomcat-ის განახლებას და პირდაპირი ID/ფაილის state-transition ტესტს. ზუსტი
საბოლოო commit, შეცვლილი ფაილების SHA-256 და image ID-ები ინახება
`C:\Projects\Magti-audit-evidence-20260924-recheck\final-manifest.json`-ში.
ყველა მონაცემი სინთეზურია; არსებული demo/UAT მონაცემები და volume-ები არ
შეცვლილა; ახალი Oracle კონტეინერი არ შექმნილა.

**ახალი ვერდიქტი: ლოკალურად მზად არ არის.** ლოკალური მიღების ოთხი აუცილებელი
პირობაა: (1) საბოლოო კოდზე ყველა სავალდებულო gate შესრულდა; (2) 150 API
მოქმედების წარმატება/უარი/შეცდომა assertion-ით ან დასაბუთებული N/A-ით
დამტკიცდა; (3) შესაბამისი ASVS 5.0 მოთხოვნები ინდივიდუალურად დამტკიცდა;
(4) ორი სუფთა, retry=0 shipping-image E2E გავიდა და მაღალი რისკის ღია
მიგნება არ დარჩა. პირველი და E2E ნაწილი შესრულდა. მეორე და მესამე პირობა
არ შესრულდა; A06-ის 499 მიზეზიც დაუდასტურებელია. ეს production GO არ არის.

### შესრულებული ბრძანებები და არტეფაქტები

ყველა ქვემოთ მოცემული შემოწმების ლოგი და exit ფაილი არის
`C:\Projects\Magti-audit-evidence-20260924-recheck\`-ში. Oracle ბრძანებებს
აშკარად გადაეცა მხოლოდ საკუთარი სქემის URL/user/password; მგრძნობიარე
მნიშვნელობა აქ განზრახ არ იწერება.

| ბრძანება / ცდა | შედეგი | მტკიცებულება |
|---|---|---|
| `scripts/verify-like-ci.sh fast` | exit 0; pytest 37 pass/3 პირობითი skip; Java 580/580; Angular 166/166; Ruff, lint, build, i18n 603 key, npm audit 0, Gitleaks 0 | `fast-final.log`, `fast-final.exit` |
| `mvnw.cmd -B test -Dgroups=oracle` | exit 0; 466/466, 0 failure/skip, საკუთარი `MAGTI_AUD_R1_0924` | `oracle-full-final.log`, `oracle-full-final.exit` |
| `docker build ... java-backend` და frontend image build | ორივე exit 0; საბოლოო backend `sha256:da92f54497c9bbcd882046ff3b58cdbbee635c715d3f4c0abf7904f8314c85c7`; frontend `sha256:cba7e9e8ce1bc3dc62fbd8709853d04d22e2461d4ef7538f9c274e51e878e27e` | `backend-image-build-final.log`, `frontend-image-build.log` |
| `cyclonedx:makeAggregateBom` და Trivy backend SBOM | საწყის Tomcat 11.0.22-ზე exit 1, სამი CRITICAL ჩანაწერი; 11.0.26-ზე exit 0, HIGH/CRITICAL 0 | `backend-sbom-trivy.json`, `backend-sbom-trivy-final.json`, შესაბამისი `.log`/`.exit` |
| frontend CycloneDX SBOM + Trivy | exit 0, HIGH/CRITICAL 0 | `frontend-bom.json`, `frontend-sbom-trivy.json`, `.log`/`.exit` |
| Trivy final image OS scan, backend და frontend | ორივე exit 0, HIGH/CRITICAL 0 | `backend-image-trivy-final-os.json`, `frontend-image-trivy-final-os.json`, `.log`/`.exit` |
| სრული backend image Java scan | ტექნიკურად exit 1: Trivy-ის ლოკალურ cache-ში Java DB არ იყო; იმავე საბოლოო Java დამოკიდებულებები ცალკე SBOM სკანით exit 0 შემოწმდა | `backend-image-trivy-final.log`, `backend-sbom-trivy-final.json` |
| Playwright E3, shipping nginx/backend, 1920×1080 Chrome | exit 0, 51/51, retry 0, ახალი `MAGTI_AUD_E3_0924` | `playwright-final-E3.log`, `playwright-final-E3/results.json` |
| Playwright E4, შეცვლილი რიგი shard 2/2 → 1/2 | exit 0, 23/23 + 28/28, retry 0, ახალი `MAGTI_AUD_E4_0924` | `playwright-final-E4-shard2.log`, `playwright-final-E4-shard1.log`, მათი `results.json` |
| `verify-nginx-smoke.sh` საბოლოო images-ზე | exit 0, 2/2; 2/10 MiB 200, >10 MiB 413, >60s upstream გასული | `nginx-smoke-final.log`, `.exit` |
| ორი საბოლოო backend replica, Flyway და role/API/UI probe | exit 0; 50/50 უნიკალური V1–V50, 20/30 გადანაწილება; ძველი token 401, downgrade-ზე manager API 403 და UI `/forbidden` | `replica-probe-final.json`, `role-ui-probe-final.json`, replica ლოგები |
| ოთხკლიენტიანი ლოკალური `/api/health` smoke | E3 40/40, p95 44.64ms; E4 40/40, p95 45.75ms | `load-smoke-final-E3.json`, `load-smoke-final-E4.json` |

ორი წარუმატებელი **ცალკე** ინფრასტრუქტურული ცდა შენახულია: საბოლოო replica
UI-ის პირველი fixture სუფთა სქემაზე ჯერ არარსებულ manager ID=2-ს
ააქტიურებდა და 404 მიიღო (`role-ui-probe-final-attempt1.log`); fixture
მომხმარებელს თვითონ ქმნის, შემდეგ ახალ RP3 სქემაზე შესრულდა. nginx smoke-ის
პირველ ცდაში fixture-ის კონტეინერი მხოლოდ loopback-ზე გამოქვეყნებულ backend
პორტს ვერ მისწვდა (`nginx-smoke-final-attempt1.log`); ცალკე მეორე ცდამ
სინთეზური backend fixture-ის ქსელისთვის გახსნა. არცერთი ჩავარდნილი ცდა
წარმატებულ ტესტად არ დაითვალა და retry არ ჩართულა.

### კონკრეტული assertion-ები და დარჩენილი სიცარიელე

`FileEntitlementEnforcedIntegrationTest.java:37–86` ამოწმებს საკუთარ draft-ს,
სხვა admin-ისა და უცხო დეპარტამენტის 404-ს **ორივე** პირდაპირ ID/ფაილის URL-ზე,
გამოქვეყნების შემდეგ სამიზნის 200-ს და არქივის შემდეგ 404-ს; საბოლოო Oracle
ნაკრებში კლასის 7/7 ტესტი გავიდა. `ReadingAcknowledgementConcurrencyIntegrationTest.java:112–122`
ამოწმებს ორი ერთდროული 200 პასუხის შემდეგ ერთ receipt-სა და ერთ status-ს;
`ReadingAcknowledgementRollbackIntegrationTest.java:89–92` audit ჩავარდნაზე
500-ს და ნულ დარჩენილ ქვითარს. `ExportJobRecoveryIntegrationTest.java:75–97`
ამოწმებს დაკარგული lease-ის failed სტატუსს, ერთ audit ჩანაწერს და მოქმედი
worker-ის completed/ჩამოტვირთვად bytes-ს;
`ExportJobRecoveryAuditRollbackIntegrationTest.java:40` აუდიტის შეცდომის
შემდეგ processing-ის შენარჩუნებას. `AuditChainServiceTest.java:185–186`
შემთხვევით შეცვლილ ჩანაწერზე `tampered`-სა და ერთ hash mismatch-ს ამოწმებს.
ეს assertion-ები საბოლოო Oracle ნაკრებშია; მხოლოდ კლასის სახელი არ არის
დაფარვის საბუთი.

`security/LOCAL_ENDPOINT_CASE_REVIEW_2026-09-24.csv` ზუსტად 150 მოქმედებას
შეიცავს: 7-ზე სამი case სვეტი assertion/N/A-ს უკავშირდება, 8 ნაწილობრივია,
135-ზე ზუსტი case-level მტკიცებულება ამ აუდიტში არ მოიძებნა. ზოგიერთ route-ს
შეიძლება დამატებითი დაუკავშირებელი ტესტი ჰქონდეს; სიცარიელე მტკიცებულების
და/ან ტესტისაა და ცრუ pass-ად არ გადაკეთებულა. `security/LOCAL_ASVS_CASE_REVIEW_2026-09-24.csv`
ინდივიდუალურად აღრიცხავს 345 მოთხოვნას: 18 N/A (GraphQL/WebSocket/WebRTC
ზედაპირი წყაროში არ არის), 11 ნაწილობრივი, 316 gap. მაღალი რისკის პირდაპირი
ID/ფაილის state-transition gap დაიფარა ახალი ტესტით; სხვა gap-ები ღიაა.

### A06 და A07-ის განკარგულება

**A06 ღიაა.** სინთეზური ერთი `POST /api/favorites` nginx-ით 200 იყო:
კლიენტი 38.122ms, Java route metric 20.283ms, Hikari acquire aggregate
1.022ms/4 acquisition, Oracle `V$SQL` aggregate 0.826ms/2 execution.
`latency-probe.json` და nginx-ის ერთი access line ამას აფიქსირებს, მაგრამ
nginx არ წერს `$request_time`/`$upstream_response_time`-ს და ფენებს საერთო
request ID არ აქვთ. ამიტომ ეს **ერთი და იგივე მოთხოვნის** ოთხფენიანი სრული
კორელაცია და ძველი 499-ის მიზეზის დადასტურება არ არის. მიზეზის გარეშე კოდის
გამოცნობა და ცრუ „გამოსწორება“ არ ჩატარდა. ეს დროითი დაკვირვება Tomcat-ის
განახლებამდე იყო; საბოლოო კოდის 2×51 E2E-ში 499 არ გამეორებულა, რაც მიზეზს
მაინც ვერ ამტკიცებს.

**A07 შიდა დახურულია (2026-09-24).** საწყისი გამეორებადი backend CycloneDX
SBOM Trivy scan exit 1-ით აღნიშნავდა Tomcat core 11.0.22-ის
`CVE-2026-65182`, `CVE-2026-65905`, `CVE-2026-68525` ჩანაწერებს
CRITICAL-ად. `java-backend/pom.xml`-ში მხოლოდ `tomcat.version=11.0.26`
დაემატა; core/el/websocket სამივე 11.0.26 გახდა. ხელახალი იგივე SBOM scan
exit 0-ია, საბოლოო Oracle/fast/E2E გაიარა. [Apache-ის 11.0.26 advisory](https://tomcat.apache.org/security-11)
დამატებით 11.0.25-მდე მოქმედ შეცდომებსაც ფარავს. ორიგინალი წითელი JSON
შენახულია; სკანერის severity პროდუქტის ექსპლუატირებადობას თავისთავად არ
ამტკიცებს.

### მხოლოდ კომპანიის გარემოში დასამტკიცებელი

InfoPortal client credential და როლები, ნამდვილი SSO/IAM; staging-ის
TLS/proxy/IP და observability კორელაცია; კომპანიის Oracle/DBA execution plan;
Linux CI-ის დამოუკიდებელი image/SBOM gate; რეალური ორი replica და worker
failover; 600 **განსხვავებული** ანგარიშის staging დატვირთვა მონაცემის
reconciliation-ით; backup-იდან სხვა გარემოში BLOB-ებით აღდგენა; rollback;
გარე audit checkpoint და ოთხი როლის ბიზნეს UAT. ლოკალური 4-კლიენტიანი smoke
ამ პირობებს არ ანაცვლებს. ხარისხის ცალკე პრიორიტეტული რჩევებია
`security/LOCAL_QUALITY_RECOMMENDATIONS_2026-09-24_KA.md`-ში.

## 2026-09-25 — პირადი ქვიზის უფლება და მიმდინარე შემთხვევების განკარგულება

ეს დათარიღებული დამატება წინა შედეგებს არ შლის. საწყისი სუფთა commit იყო
`b6dbc9bf9c06feac40ef8db1726139ccfb953281`, მხოლოდ იზოლირებულ
`codex/release-audit-20260924` ხეში. საბოლოო commit-ისა და ბრძანებების
exit code-ების მტკიცებულება ინახება კანდიდატის იგნორირებულ
`logs/release-audit-20260925/` დირექტორიაში და საბოლოო ანგარიშში.

**A08 — დადასტურებული და გამოსწორებული ლოკალური დეფექტი.**
`QuizController.assertArticleVisible` ცალკე იმეორებდა სტატიის ძველ წესს და
`is_draft`-ს არ ამოწმებდა. ტესტმა სხვა კონტენტ-ადმინის პირადი მონახაზის
ქვიზზე 404-ის ნაცვლად 200 მიიღო (`quiz-draft-red-assertion.log`, exit 1).
ოთხივე ქვიზის მოქმედება ახლა იყენებს `ArticleVisibility`-ს; იმავე ტესტის
ხელახალი შესრულება exit 0 იყო. ავტორის 200, სხვა ადმინის/ოპერატორის 404,
არარსებული ID-ის 404, არასწორი payload-ის 400 და ნულოვანი შენახული
მცდელობა ცალ-ცალკე მოწმდება. წვდომის კონტრაქტის Quiz სტრიქონები იმავე
ცვლილებაში განახლდა. Oracle-ის სრული რეგრესიისა და საბოლოო image E2E-ის
შედეგი ამ დეფექტის საბოლოო განკარგულებაში ცალკე უნდა მიეთითოს.

**UAT ინსტრუქციის შესწორება:** `UAT_04_ADMIN_KA.md`-ის 2026-08-29
როლის ხელით შეცვლის 200/200 გაზომვა ისტორიად დარჩა; მიმდინარე ნაბიჯი ახლა
PO-28-ის წაკითხვად როლსა და API-ის 409 უარს ითხოვს.

**A06 ღია რჩება.** nginx ახლა წერს request ID-ს, request/upstream დროსა და
სტატუსს; Java იგივე ID-ს პასუხსა და ხანგრძლივობის ლოგში აბრუნებს. ტესტი
ამოწმებს სწორ ID-სა და მავნე header-ის უგულებელყოფას. Hikari/Oracle-ის
მაჩვენებლები ჯერ მხოლოდ საერთო მრიცხველებია; ძველი 499 მოთხოვნას საერთო
ID საერთოდ არ ჰქონდა. ახალი ერთი მოთხოვნის ოთხფენიანი კორელაცია და
ძველი 499-ის მიზეზი **არ არის დამტკიცებული**. მიზეზის გარეშე A06-ის
გამოსწორება არ გამოცხადებულა.

API რეესტრი ამ ეტაპზე 150 მოქმედებას შეიცავს: 17-ზე სამივე შემთხვევას
კონკრეტული assertion ან დასაბუთებული N/A უკავშირდება, 2 ნაწილობრივია და
131 ღია GAP. `LOCAL_ENDPOINT_CASE_REVIEW_2026-09-24.csv` არის მოქმედებათა
ინდივიდუალური სია; ტესტის სახელის ნაცვლად შესაბამის სტრიქონებში ზუსტი
assertion-ის ხაზებია. დაუკავშირებელი 133 მოქმედება pass-ად არ ითვლება.

ASVS 5.0-ის 345 სტრიქონის მიმდინარე სტატუსი კვლავ არის 18 N/A,
11 ნაწილობრივი და 316 GAP. ქვემოთ 18 N/A ხელახლა შემოწმდა მათი საკუთარი
წინაპირობით. შემოწმება: `rg -n -i
'graphql|websocket|webrtc|RTCPeerConnection|getUserMedia|turn:|srtp|dtls|MediaStream|stomp'
java-backend/src/main angular-frontend/src angular-frontend/package.json
java-backend/pom.xml` — no match. REST API-ში მომხმარებლის მიერ მიცემული
GraphQL/data-layer expression არ არის; ვიდეო YouTube-ის გარე iframe-ია და
პორტალი media/signaling/TURN სერვერს არ ამუშავებს. ეს N/A მხოლოდ მიმდინარე
კოდის ლოკალურ ზედაპირს ეხება; კომპანიის ingress-ის შესაძლებლობებს არ ამტკიცებს.

| ASVS | ინდივიდუალური N/A მიზეზი |
|---|---|
| V4.3.1 | GraphQL ან მომხმარებლის data-layer expression მოთხოვნა არ არსებობს; REST ფილტრები ფიქსირებული პარამეტრებია. |
| V4.3.2 | GraphQL endpoint და introspection არ არსებობს. |
| V4.4.1 | WebSocket კავშირი არ არსებობს, ამიტომ WSS transport არ ვრცელდება. |
| V4.4.2 | WebSocket handshake არ არსებობს, ამიტომ მისი Origin შემოწმება არ ვრცელდება. |
| V4.4.3 | WebSocket session token არ არსებობს. |
| V4.4.4 | HTTPS-იდან WebSocket-ზე გადასვლა არ არსებობს. |
| V17.1.1 | TURN relay სერვერს პორტალი არ ამუშავებს. |
| V17.1.2 | TURN-ის პორტების გახსნის ზედაპირი არ არსებობს. |
| V17.2.1 | DTLS media server-ის სერტიფიკატი არ არსებობს. |
| V17.2.2 | DTLS-SRTP media server არ არსებობს. |
| V17.2.3 | SRTP პაკეტების მიმღები media server არ არსებობს. |
| V17.2.4 | malformed SRTP პაკეტების მიმღები არ არსებობს. |
| V17.2.5 | SRTP flood-ის მიმღები არ არსებობს. |
| V17.2.6 | DTLS ClientHello media endpoint არ არსებობს. |
| V17.2.7 | SRTP-სთან დაკავშირებული ჩაწერის სერვერი არ არსებობს. |
| V17.2.8 | SDP fingerprint/DTLS მედია მოლაპარაკება არ არსებობს. |
| V17.3.1 | real-time signaling სერვერი არ არსებობს. |
| V17.3.2 | malformed signaling შეტყობინების მიმღები არ არსებობს. |

დანარჩენი 327 მოთხოვნა (11 PARTIAL, 316 GAP) მოთხოვნის დონეზე pass არ არის:
`LOCAL_ASVS_CASE_REVIEW_2026-09-24.csv` თითოეულის აღწერასა და ღია
მტკიცებულებას ინახავს. ლოკალური მზაობა ვერ გამოცხადდება, სანამ მათთვის
შესრულებული assertion ან ზუსტად დასაბუთებული გამონაკლისი არ იქნება.
