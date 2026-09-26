# Magti Portal — release audit handoff

**სნეპშოტი:** 2026-09-26, Asia/Tbilisi. ეს დოკუმენტი შემდეგ სესიას აძლევს
გადამოწმებულ მდგომარეობას; ახალი სესია ჯერ Git-სა და მტკიცებულებებს ხელახლა
ამოწმებს. ისტორიული აუდიტის ტექსტი არ წაიშალოს. მიმდინარე ვერდიქტია
**„ლოკალურად მზად არ არის“**; production GO არ არის.

## გადაცემის დავალება შემდეგ AI აგენტს

შენ ხარ შემდეგი QA/უსაფრთხოების/Java–Angular აგენტი. **მიზანი** არის Magti
Portal-ის იზოლირებული კანდიდატის ლოკალური გამოშვების პირობების დამტკიცება და
დარჩენილი პრობლემების გამოსწორების გზა. საბოლოო ლოკალური ვერდიქტი შეიძლება
გახდეს „ლოკალურად მზადაა“ მხოლოდ მაშინ, როცა ქვემოთ ჩამოთვლილი ყველა
ლოკალური gate მტკიცებულებით დაიხურება. Production GO ცალკე გადაწყვეტილებაა.

**ახლა შენგან გვჭირდება გადაწყვეტილებამდე მისასვლელი გეგმა.** ჯერ წაიკითხე
მთელი ეს handoff, `AGENTS.md`-ები, მიმდინარე რეესტრები და ლოგები; read-only
ბრძანებებით გადაამოწმე Git/ფაილების მდგომარეობა. შემდეგ მომხმარებელს
უპასუხე ქართულად:

1. რა არის უკვე დამტკიცებული და რა რჩება დაუმტკიცებელი — ზუსტი რაოდენობით,
   commit-ითა და მტკიცებულების ფაილით.
2. რა უნდა გავაკეთოთ **პრიორიტეტულ რიგში**: ASVS-ის თითოეული ღია ჯგუფის,
   A06-ის მიზეზის, `fast` wrapper-ის და საბოლოო რეგრესიის კონკრეტული სამუშაო
   პაკეტები. თითო პაკეტს მიუთითე პასუხისმგებელი როლი, შესაცვლელი ან
   შესამოწმებელი ადგილი, საჭირო ტესტი/ლოგი, დასრულების კრიტერიუმი და
   დამოკიდებულება სხვა პაკეტზე.
3. რომელი ნაბიჯია პირველი უსაფრთხო სამუშაო დღეს და რა შედეგი უნდა მივიღოთ
   მისგან. განასხვავე ლოკალურად შესასრულებელი სამუშაო Magti IT/DBA-ს,
   staging-ის, backup restore-ისა და ბიზნეს UAT-ის გარე პირობებისგან.
4. რომელი გაურკვევლობაა მხოლოდ მომხმარებლის ან IT-ის პასუხით მოსახსნელი.
   დაუმტკიცებელი მოთხოვნა `pass`-ად არ მონიშნო. თუ მონაცემი შეცვლილია,
   მიუთითე განსხვავება ამ სნეპშოტთან და გამოიყენე ახალი ფაქტი.

**ლოკალური მიღების კრიტერიუმი:** 150 API მოქმედების success/denial/error
მტკიცებულება შენარჩუნებულია; ASVS 5.0-ის 345 მოთხოვნა ინდივიდუალურადაა
განკარგული და ლოკალურად მოქმედებზე 0 GAP/0 PARTIAL რჩება; A06-ის ერთი
მოთხოვნა nginx → Java → Hikari → Oracle დროებით კვალშია დაკავშირებული,
დადასტურებული მიზეზი გამეორებადი ჩავარდნის ტესტითა და რეგრესიითაა
დახურული; საბოლოო commit-ზე fast, Oracle, frontend, dependency/secret,
მთლიანი image/SBOM და ორი სუფთა production-nginx Playwright ნაკრები
გადის. რომელიმე პირობის დაუმტკიცებლობისას ვერდიქტი უნდა დარჩეს
„ლოკალურად მზად არ არის“.

ეს handoff თვითონ **არ არის** კოდის ცვლილების, commit-ის, push-ის ან
deployment-ის ნებართვა. თუ მომხმარებელი შემდეგ განხორციელებას გთხოვს,
იმუშავე მხოლოდ ამ იზოლირებულ კანდიდატში და დაიცავი ქვემოთ აღწერილი
უსაფრთხოების საზღვრები.

## Objective

- მომხმარებლის საწყისი დავალება: მხოლოდ იზოლირებულ
  `C:\Projects\Magti-audit-candidate-20260924` კანდიდატში 150 API მოქმედების,
  ASVS 5.0-ის 345 მოთხოვნის, A06 nginx 499-ის, საბოლოო image/SBOM-ისა და
  ორი სუფთა Playwright ნაკრების შემოწმება; დადასტურებულ დეფექტზე რიგი:
  ჩავარდნის ტესტი → მცირე fix → კონტრაქტი/დოკუმენტი → მიზნობრივი და სრული
  რეგრესია.
- ბოლო სამუშაო ფოკუსი იყო დარჩენილი API მოქმედებები. ეს ეტაპი დასრულდა;
  ASVS-ის ინდივიდუალური დახურვა და A06-ის მიზეზი კვლავ ღიაა. წინარე ლოკალური
  ტესტები არ ნიშნავს staging-ის, InfoPortal-ის, backup-იდან აღდგენის ან
  ბიზნეს UAT-ის მიღებას.

## Verified repository state

- Git root: `C:\Projects\Magti-audit-candidate-20260924`; branch:
  `codex/release-audit-20260924`; HEAD:
  `b96182fbb2712eaa7edcb8b90466d51645be9f33` (2026-09-25,
  `Audit remaining API contracts and protect private draft history`). საწყისი
  შესამოწმებელი commit: `b6dbc9bf9c06feac40ef8db1726139ccfb953281`.
- ამ branch-ს upstream არ აქვს (`git branch -vv`); push და PR **Not verified**.
  `origin` არის `https://github.com/aannddrres/workformagti`. სხვა checkout,
  `C:\Projects\Magti base`, ცალკე `main`-ზეა; ამ handoff-იდან იქ არ იმუშაოთ.
- ხელის შეხებამდე `git status --short --branch` სუფთა იყო. ამ handoff-ის
  შექმნა თვითონ ტოვებს `docs/agents/HANDOFF.md`-ს untracked-ად და
  `docs/README.md`-ს modified-ად; ისინი **არ არის staged ან committed**.
  საბოლოო მდგომარეობა ქვემოთ ხელახლა უნდა შემოწმდეს.
- პროექტი: Java 21 / Spring Boot 4.1.0, Angular 22 / Node 22.22.3,
  Oracle/Flyway V50. ძირეული და ქვეპროექტების `AGENTS.md` არის სამუშაო
  წესების წყარო. `is_draft` პირად მონახაზს ნიშნავს და სტატუსის `draft`-ისგან
  დამოუკიდებელია; მას მხოლოდ ავტორი ხედავს, content admin-იც ვერა.
- მთავარი მიმდინარე წყაროები: `docs/RELEASE_AUDIT_2026-09-24_KA.md`,
  `docs/security/LOCAL_AUDIT_EXECUTION_PLAN_2026-09-24_KA.md`,
  `docs/security/LOCAL_ENDPOINT_CASE_REVIEW_2026-09-24.csv`,
  `docs/security/LOCAL_ASVS_CASE_REVIEW_2026-09-24.csv`,
  `docs/ACCESS_CONTRACT_MATRIX_KA.md`, `docs/PRODUCT_OWNER_DECISIONS_KA.md`,
  `docs/QUESTIONS_FOR_IT.md` და `docs/uat/`. დათარიღებულ ანგარიშებში
  შუალედური 17/19 API რიცხვები ისტორიულია; უახლესი რეესტრი 150/150-ია.

## Completed work

| Commit | შესრულებული სამუშაო |
|---|---|
| `b6dbc9bf9c06feac40ef8db1726139ccfb953281` | საწყისი release audit checkpoint; Tomcat 11.0.26-ზე A07-ის გამოსწორება, საწყისი მტკიცებულებები და უარყოფითი ვერდიქტი. |
| `f1dc890fd89541678c7bf601ef78e360e1df280d` | A08: სხვისი პირადი draft-ის quiz API-ზე გამჟღავნების ჩავარდნის ტესტი და `ArticleVisibility`-ზე გადაყვანა; request-ID/timing დაკვირვებადობა. |
| `b718eacea8726d2f233febc10cde11f1c7da3f74` | ASVS-ის ინდივიდუალური რეესტრი და საბოლოო აუდიტის მტკიცებულების დათარიღებული ჩანაწერი. |
| `6f3bcc8f42d749e1925054b77a79faabafd84b61` | დარჩენილი ლოკალური აუდიტის შესასრულებელი გეგმა. |
| `9f71f374f0af31b561f85508cb6f27aa455922a9` | ექსპორტისა და მენეჯერის სტატისტიკის service-failure assertion-ები: უსაფრთხო 500, correlation ID და გაჟონვის/არასწორი audit-ის უარყოფა. |
| `b96182fbb2712eaa7edcb8b90466d51645be9f33` | 150 მოქმედების success/denial/error assertion-ების დაკავშირება; მაღალი რისკის ახალი ტესტები; A09 პირადი draft-ის history/restore-ის წვდომის fix; იმავე commit-ში ოთხი სტრიქონის განახლება წვდომის მატრიცაში. |

- API რეესტრი: 150 `ASSERTIONS_LINKED`, 0 `PARTIAL`, 0 `GAP`. 457 მითითებული
  ტესტის ხაზი შემოწმდა: 0 არარსებული დიაპაზონი და 0 სტატიკურად საეჭვო
  assertion. ეს არის კონკრეტული case-level მტკიცებულების რუკა, არა ASVS-ის
  ავტომატური pass.
- A09-ის red ტესტმა სხვა `content.manage` ადმინისტრატორისთვის
  `GET /api/articles/{id}/history`-ზე მოსალოდნელი 404-ის ნაცვლად 200
  დააფიქსირა. `ArticleController` ახლა ისტორიის სიაზე, მოკლე სიაზე, ერთ
  snapshot-ზე და restore-ზე საერთო `ArticleVisibility` წესს იყენებს.
  უარყოფილი restore არ ცვლის სტატიას, ისტორიას ან audit-ს. არარსებული სტატიის
  `/history` ძველი ცარიელი სიის კონტრაქტი შენარჩუნდა.
- A07 დახურული რჩება: Tomcat 11.0.26; Java DB-ის ლოკალური Trivy შეზღუდვა
  გადაიჭრა იზოლირებული cache-ით. A08 და A09 ლოკალურად დახურულია.
  A01–A05-ის წინარე განკარგულებები და დარჩენილი გარე პირობები იხილეთ
  `docs/RELEASE_AUDIT_2026-09-24_KA.md`-ში; ახალი გადაწყვეტილებით არ
  გადააწეროთ ისტორია.
- აუდიტის ცდები შესრულდა მხოლოდ არსებული Oracle კონტეინერის შიგნით შექმნილ
  სახელით გამორჩეულ სინთეზურ სქემებზე. ახალი Oracle კონტეინერი არ გაშვებულა;
  demo/UAT მოცულობები არ შეცვლილა. AP31/AP32, FINAL_03/FINAL_04 და E7/E8
  საკუთარი სქემების drop და ტესტის კონტეინერების cleanup ლოგირებულია;
  შემდგომმა read-only შემოწმებამ მათზე `no rows selected` მიიღო.

## Verification evidence

ყველა ქვემოთ მითითებული ლოგი და `.exit` არის კანდიდატის იგნორირებულ
`logs/release-audit-20260925/` დირექტორიაში; ისინი Git commit-ში **არ შედის**.
წინარე `C:\Projects\Magti-audit-evidence-20260924-recheck` არის საწყისი
მტკიცებულება და არა საბოლოო commit-ის ტესტის შემცვლელი.

| შემოწმება საბოლოო `b96182f` commit-ზე | შედეგი და არტეფაქტი |
|---|---|
| Oracle სრული, `run-api-oracle.ps1 -Schema AUDIT_API_FINAL_04 -TestClasses '*' -Label final04` | `api-final04-oracle.log/.exit`: 659/659, 0 failure/error/skip, exit 0; სქემა წაიშალა. |
| `scripts/verify-like-ci.sh fast` | `api-postcommit-fast.log/.exit`: Python Ruff pass, pytest 36 pass/4 skip; Java DB-free 627/627; npm audit 0; i18n 603 გასაღები; lint და production build pass; Angular 166/166. **მთლიანი wrapper exit 1** მხოლოდ იმიტომ, რომ ლოკალური `gitleaks` ბრძანება არ არის. |
| Gitleaks 8.30.1 კონტეინერით | `api-postcommit-secret.log/.exit`: 1,341 ნებადართული tracked ფაილის ვერსია, 0 მიგნება, exit 0. ეს ცალკე ეკვივალენტური scan-ია; wrapper-ის exit 1 ისტორიად რჩება. |
| API მტკიცებულების შემოწმება | `check-api-citations.py`, `check-api-assertion-lines.py`: 150 row, 457 reference, 0 არარსებული/საეჭვო; სკრიპტები იმავე იგნორირებულ evidence საქაღალდეშია. |
| Backend image build და ერთიანი Trivy | `api-postcommit-backend-build.exit` 0; `backend-b96182f-trivy.log/.json/.exit`: მთლიანი image-ის Alpine OS და Java JAR ორივე გაანალიზდა, გამოსასწორებელი HIGH/CRITICAL 0, exit 0. Backend image ID `sha256:212e11223832e125e8bcc471c423bcd22424c2f30cdec27793eaa0f67e92da5b`. |
| Frontend image და SBOM | Frontend image ID `sha256:42d64475f3606b5bed30705bc0724befe3a1e66cf7d50abea55771b4714c1e34`; image Trivy 0 HIGH/CRITICAL, exit 0. CycloneDX backend/frontend 126/497 კომპონენტი; ორივე SBOM Trivy 0 HIGH/CRITICAL, exit 0. იხილეთ `backend-b96182f-*`, `backend-frontendb96182f-trivy.*`, `frontend-b96182f-*`. |
| Production nginx Playwright, E7 | `playwright-E7.log/.exit`: 51/51, exit 0, retry=0, ერთი worker; `nginx-smoke-E7.log/.exit`: upload/timeout 2/2, exit 0. |
| Production nginx Playwright, E8 | სხვა სუფთა სქემაზე საპირისპირო რიგით ჯერ `playwright-E8-shard2`: 23/23, შემდეგ `playwright-E8-shard1`: 28/28; ორივე exit 0, retry=0. |
| Browser მიზანი | `angular-frontend/playwright.config.ts` და `e2e/desktop-theme.spec.ts`: Desktop Chrome 1920×1080, ქართული UI, ორივე თემა და კლავიატურის ძირითადი გზა ორივე სრულ ნაკრებში შესრულდა. |
| SHA-256 | `candidate-b96182f-sha256.json`: 1,294 tracked ფაილის მანიფესტი commit `b96182f`-ისთვის. თვით მანიფესტის SHA-256: `3AACD80605C1D02A12DAC20DA796EBC2B892C53F4DF552323AE41A03F139BC3E`. მიმდინარე uncommitted handoff მასში არ შედის. |

დეფექტის ჩავარდნილი ცდა შენარჩუნებულია: `api-ap31-oracle.log/.exit`
(1 failure, exit 1, 404-ის ნაცვლად 200). მცირე fix-ის შემდეგ
`api-ap32-oracle.log/.exit` 1/1, exit 0. AP13/AP27-ის fixture შეცდომებიც
ლოგებში დარჩა; AP14/AP28-ზე გასწორდა, შემდეგ სრული FINAL_04 659/659
გავიდა. Retry ჩავარდნის დასაფარად არ გამოყენებულა.

## Uncommitted or untracked work

- ამ handoff-ის მოთხოვნამდე სამუშაო ხე სუფთა იყო. ამ მოთხოვნით შექმნილი
  `docs/agents/HANDOFF.md` untracked და `docs/README.md` modified არის;
  არც ერთი არ არის staged/committed. აპლიკაციის კოდში ახალი ცვლილება არ არის.
- `logs/release-audit-20260925/` განზრახ `.gitignore`-ით იგნორირებულია;
  იქ არის ლოგები, report JSON-ები, exit code-ები და დამხმარე ლოკალური
  სკრიპტები. ისინი commit-ის ნაწილად ან remote artifact-ად არ ჩათვალოთ.
- push, PR, Linux CI და staging-ის ამ HEAD-ზე შედეგები **Not verified**.

## Open issues and risks

1. **ASVS 5.0:** 345 მოთხოვნიდან 18 `N/A_SOURCE_REVIEWED`, 11
   `PARTIAL_NOT_ASVS_PASS`, 316 `GAP`. 327 დაუმტკიცებელი მოთხოვნა pass არ
   არის. 11 PARTIAL-ის ID-ებია V4.1.3, V5.2.1, V5.2.2, V6.3.1,
   V6.3.8, V7.4.2, V8.1.1, V8.2.2, V8.3.2, V9.1.1, V16.5.1. ზუსტი
   აკლებული წინაპირობა თითო სტრიქონის `exact_evidence_or_gap` ველშია.
2. **A06 nginx 499:** ისტორიულ 5-წამიან `POST /api/favorites`-ს საერთო
   request ID არ ჰქონდა; მიზეზის უკუქცევით დადასტურება შეუძლებელია.
   E5/E6-ში 21 მოკლე GET 499-ის request ID Java-ს 200 პასუხს ემთხვეოდა
   (`a06-499-correlated.csv`); ეს ძველი POST-ისგან ცალკე შემთხვევაა.
   უახლეს E7/E8 nginx ლოგებში 499 არ გამოჩნდა, მაგრამ ეს მიზეზს არ ხსნის.
   Hikari/Oracle-ის ერთი მოთხოვნის დროები არ არის მიბმული; A06 ღიაა.
3. **ფორმალური fast exit:** wrapper exit 1 ლოკალური Gitleaks CLI-ის
   არყოფნის გამო; ცალკე კონტეინერული Gitleaks scan exit 0. ამ სხვაობის
   აღწერის გარეშე fast-ს მთლიანად მწვანედ ნუ მონიშნავთ.
4. **გარე მიღება:** InfoPortal-ის სწორი client credential და როლების
   სახელები IT-თან დასადასტურებელია (`docs/QUESTIONS_FOR_IT.md` №13;
   `k8s/` IT-15/IT-16); staging, backup-იდან აღდგენა, ბიზნეს UAT და Linux CI
   ამ ლოკალურმა ცდამ არ დაამტკიცა. ლოკალური 1-worker/სინთეზური დატვირთვით
   600-მომხმარებლიანი staging წარმადობა არ გამოითვლება.

## Next actions

1. ახალი სესიის დასაწყისში წაიკითხეთ ეს handoff და გადაამოწმეთ branch, სრული
   HEAD, `git status`, რეესტრები და შესაბამისი `.exit`/ლოგები. ეს სნეპშოტი
   ავტორიტეტად არ გამოიყენოთ, თუ Git/მტკიცებულება შეიცვალა.
2. ASVS-ის 11 PARTIAL მოთხოვნას თითო აკლებული წინაპირობით მიჰყევით;
   შემდეგ 316 GAP დამუშავდეს 20–30-იანი თავობრივი ნაწილებით. ყოველი
   მოთხოვნისთვის დააკავშირეთ სრული ფორმულირება, კოდი/კონფიგურაცია,
   შესრულებული assertion, შედეგის ლოგი და დარჩენილი პირობა. დაუსაბუთებელი
   pass ან მექანიკური N/A არ გამოიყენოთ. IT/staging-ზე დამოკიდებული ნაწილი
   ღიად დატოვეთ და მფლობელი მიუთითეთ.
3. A06-ზე საკუთარ სინთეზურ სქემაში ცალკე გააკეთეთ კონტროლირებადი favorites
   POST და კლიენტის შეწყვეტის სცენარი. ერთ request ID-ს მიაბით nginx-ის
   request/upstream, Java-ს, Hikari connection wait/hold-ს და Oracle-ის
   კონკრეტულ session/SQL დროს, საიდუმლოების ან SQL literal-ის ლოგირების
   გარეშე. მხოლოდ დადასტურებულ მიზეზზე: ჩავარდნის ტესტი → მცირე fix →
   დათარიღებული განკარგულება → სრული რეგრესია; სხვაგვარად A06 ღია დარჩეს.
4. თუ კოდი ან კონტრაქტი შეიცვლება, იმავე commit-ში განაახლეთ
   `docs/ACCESS_CONTRACT_MATRIX_KA.md` საჭიროებისას; შემდეგ საბოლოო commit-ზე
   ხელახლა გაუშვით fast, აშკარა საკუთარი Oracle სქემა, frontend, secret,
   მთლიანი image/SBOM და ორ სუფთა სქემაზე Playwright განსხვავებული რიგით.
   გააკეთეთ ახალი manifest; არ მიაწეროთ `b96182f`-ის შედეგები ახალ კოდს.
5. IT/DBA-სთან ცალკე მიიღეთ InfoPortal, audit chain-ის გარე ასლის,
   staging-ისა და backup restore-ის პასუხები. Production GO მხოლოდ ყველა
   ლოკალური და გარე gate-ის დადასტურების შემდეგ შეიძლება შეფასდეს.

## Do not do without authorization

- მხოლოდ ამ handoff-ის მოთხოვნა არ ნიშნავს კოდის შეცვლის, staging-ში
  ჩაწერის, commit-ის, push-ის, PR-ის, merge-ის ან deploy-ის ნებართვას.
- არ შეცვალოთ `C:\Projects\Magti base`, demo/UAT მონაცემები ან Docker volume-ები;
  არ გაუშვათ ახალი Oracle კონტეინერი. გამოიყენეთ მხოლოდ საკუთარი სახელით
  გამორჩეული სქემები და სინთეზური მონაცემები; წაშალეთ მხოლოდ საკუთარი
  რესურსები.
- არ ჩაწეროთ პაროლები, token-ები ან რეალური თანამშრომლის მონაცემები.
  არ დამალოთ ჩავარდნა retry-ით; ისტორიული მიგნებები და მტკიცებულება არ
  წაშალოთ. არ გამოაცხადოთ „ლოკალურად მზადაა“ ან Production GO, სანამ
  აუცილებელი პირობები დამტკიცებული არ არის.
