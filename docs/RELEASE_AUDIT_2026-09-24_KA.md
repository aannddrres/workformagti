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
