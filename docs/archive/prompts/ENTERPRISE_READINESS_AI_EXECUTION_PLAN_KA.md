> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# Magti Portal — AI Agent Enterprise Readiness Execution Plan

**სტატუსი:** სამუშაო კონტრაქტი / execution source of truth  
**ვერსია:** 1.0  
**თარიღი:** 2026-08-25  
**მიზანი:** არსებული ფუნქციების Enterprise Pilot/Production readiness-მდე მიყვანა  
**სამუშაო ჰორიზონტი:** 90 დღე, ფაზებად და მტკიცებულების gate-ებით

## 1. როგორ გამოიყენოს ეს დოკუმენტი AI აგენტმა

ეს დოკუმენტი არის შემდეგ ჩატში მომუშავე AI აგენტის ძირითადი execution
კონტრაქტი. აგენტმა ჯერ უნდა გადაამოწმოს repository-ის მიმდინარე მდგომარეობა და
მხოლოდ ამის შემდეგ დაიწყოს ცვლილებები.

დოკუმენტი არ ცვლის მიმდინარე კოდს, ტესტებსა და მომხმარებლის გადაწყვეტილებებს.
თუ ტექსტი და მიმდინარე implementation ერთმანეთს ეწინააღმდეგება:

1. მომხმარებლის ახალი პირდაპირი გადაწყვეტილება არის უმაღლესი პროდუქტის წყარო;
2. უსაფრთხოების და მოქმედების საზღვრებისთვის მოქმედებს სისტემური/repository
   ინსტრუქცია;
3. ფაქტობრივი implementation-ისთვის მიმდინარე კოდი და ტესტია წყარო;
4. დახურული UX/პროდუქტის გადაწყვეტილებებისთვის გამოიყენება
   `docs/UX_IMPLEMENTATION_DECISIONS_2026-08-24_KA.md`;
5. მოთხოვნებისთვის გამოიყენება `docs/PRODUCT_UX_REQUIREMENTS_KA.md`;
6. წვდომის კონტრაქტისთვის გამოიყენება `docs/ACCESS_CONTRACT_MATRIX_KA.md`;
7. ეს execution plan განსაზღვრავს თანმიმდევრობას, gate-ებსა და მტკიცებულებას;
8. ძველი audit/readiness დოკუმენტები ისტორიული მტკიცებულებაა და არა ავტომატურად
   მიმდინარე სტატუსი.

აგენტმა არ უნდა ჩათვალოს ძველი finding დახურულად ან ღიად, სანამ მიმდინარე კოდით,
ტესტით ან ოპერაციული მტკიცებულებით არ გადაამოწმებს.

## 2. პროგრამის მიზანი

არსებული Magti Portal გადავიდეს ფუნქციურად გამართული ლოკალური სისტემიდან
კონტროლირებულ enterprise pilot-ready და შემდეგ production-ready მდგომარეობაში.

მთავარი შედეგია არა დამატებული feature-ების რაოდენობა, არამედ:

- სწორი პასუხის სწრაფად პოვნა;
- შეცდომის და არასწორი მომსახურების რისკის შემცირება;
- როლებისა და მონაცემთა scope-ის მკაცრი დაცვა;
- სრული, აღდგენადი და აუდიტირებადი evidence;
- რეალური SSO/AD identity;
- გაზომვადი წარმადობა და საიმედოობა;
- backup/restore და rollback-ის დამტკიცებული პროცესი;
- ოთხივე როლის მიერ რეალურ გარემოში მიღებული UX.

## 3. წარმატების საბოლოო კრიტერიუმები

პროგრამა დასრულებულად მხოლოდ მაშინ ითვლება, როცა მტკიცებულებით დასტურდება:

1. production-like staging გარემო არსებობს;
2. რეალური SSO/AD login, revoke, deactivation და outage ქცევა გავლილია;
3. Role × Permission × Scope მატრიცაში კრიტიკული scope leak არის 0;
4. acting manager-ს export არ აქვს და primary manager-ის export server-side
   allowlist-სა და scope-ს იცავს;
5. ყველა კრიტიკული mutation სრულად აუდიტირდება;
6. წაშლილი კონტენტის/თანამშრომლის read/quiz/compliance evidence არ იკარგება;
7. legal-hold და retention პოლიტიკა დამტკიცებულია შესაბამისი მფლობელების მიერ;
8. Critical/High უსაფრთხოების finding ღია არ არის, გარდა წერილობით მიღებული და
   არაბლოკერი გამონაკლისისა;
9. WCAG 2.2 AA კრიტიკულ flow-ებზე გავლილია automated და manual evidence-ით;
10. ჩვეულებრივ მიზნობრივ დატვირთვაზე ძებნის end-to-end p95 არ აღემატება 2 წამს,
    ხოლო ძირითადი API error rate არის 1%-ზე ნაკლები;
11. Oracle backup რეალურად აღდგენილია იზოლირებულ გარემოში და შემოწმებულია content,
    attachment და audit evidence;
12. readiness/liveness, monitoring, alerting, on-call, incident და rollback
    პროცედურები მუშაობს;
13. ოთხივე როლზე UAT გავლილია, P0/P1 დეფექტი ღია არ არის და GO/NO-GO-ზე ხელი
    მოაწერეს შესაბამისმა მფლობელებმა.

რიცხვითი ზღვრები სამუშაო შეთავაზებაა. თუ Steering Committee სხვა ზღვარს
ამტკიცებს, გადაწყვეტილება უნდა ჩაიწეროს decision log-ში და შესაბამისი tests/SLO
განახლდეს.

## 4. უცვლელი პროდუქტის გადაწყვეტილებები

აგენტმა არ უნდა დაარღვიოს შემდეგი წესები:

- production login მხოლოდ კომპანიის SSO/AD-ით ხდება;
- AD/SSO მიუწვდომლობისას წვდომა არ გაიცემა;
- production-ში local/offline/password fallback არ არსებობს;
- დროებით/acting მენეჯერს export არ აქვს;
- სტატიებისა და სიახლეების feedback ფუნქცია აღარ ბრუნდება;
- წაშლილი კონტენტის და თანამშრომლის ისტორიული evidence ინახება;
- raw audit მხოლოდ `SYSTEM_ADMIN`-ისთვისაა;
- მინიმუმ ერთი აქტიური `SYSTEM_ADMIN` სავალდებულოა და დაცულია;
- content queue აერთიანებს სტატიებს, სიახლეებსა და ვიდეოებს;
- ქართული არის ძირითადი სამუშაო ენა;
- Portal გათვლილია დაახლოებით 600 მომხმარებელზე და ჩვეულებრივ 150-ზე ნაკლებ
  ერთდროულ მომხმარებელზე;
- AI-ს არ შეუძლია ადამიანის გარეშე კონტენტის გამოქვეყნება;
- microservices decomposition, custom role builder, native mobile app და სხვა
  დიდი ახალი მიმართულება ამ readiness პროგრამის scope-ში არ შედის.

## 5. მოქმედების ფარგლები

### 5.1 AI აგენტის მიერ ნებადართული ადგილობრივი სამუშაო

ამ execution plan-ის გასაშვები პრომპტი აგენტს აძლევს უფლებას:

- წაიკითხოს repository-ის კოდი, docs, migrations, tests და config;
- შექმნას და განაახლოს readiness-ის Markdown დოკუმენტები;
- შეიტანოს ამ გეგმაში აღწერილი ადგილობრივი Angular/Java/Oracle migration/test
  ცვლილებები;
- გაუშვას არადესტრუქციული build, unit, integration, E2E, security და static
  verification;
- იმუშაოს მცირე, შემოწმებად ცვლილებებად;
- აღმოჩენილი შეუსაბამობა გაასწოროს, თუ იგი ამ პროგრამის scope-შია;
- განაახლოს acceptance matrix და evidence ledger ფაქტობრივი შედეგებით.

### 5.2 ცალკე ნებართვის გარეშე აკრძალული მოქმედებები

- commit, push, pull request ან branch-ის შეცვლა;
- არსებული დაუკომიტებელი ცვლილებების reset/checkout/discard;
- production ან სხვა გარე გარემოში deploy;
- production მონაცემის ცვლილება, purge ან migration-ის გაშვება;
- მომხმარებლის/IT-ის სახელით გარე სისტემაში ცვლილება;
- secret, credential, token ან პირადი მონაცემის დოკუმენტში ჩაწერა;
- რეალური retention, legal, privacy ან security გადაწყვეტილების გამოგონება;
- გარე dependency-ის შესრულებულად მონიშვნა შესაბამისი მტკიცებულების გარეშე.

## 6. სამუშაო პრინციპები AI აგენტისთვის

1. დაიწყე repository-ის ფაქტობრივი მდგომარეობის re-baseline-ით.
2. შეინარჩუნე მომხმარებლის ყველა არსებული დაუკომიტებელი ცვლილება.
3. ყოველი finding დააკავშირე კოდთან, endpoint-თან, requirement-თან და test-თან.
4. P0 უსაფრთხოება/data-loss/scope საკითხი ასწრებს ვიზუალურ polish-ს.
5. ერთი batch უნდა იყოს მცირე, დასრულებადი და შემოწმებადი.
6. ცვლილებამდე აღწერე observable acceptance criteria.
7. ცვლილების შემდეგ გაუშვი focused test; milestone-ზე — სრული შესაბამისი suite.
8. არ გამოიყენო test deletion ან assertion-ის დასუსტება პრობლემის დასამალად.
9. flaky ან shared-state test-ისას იპოვე isolation/root cause.
10. external dependency არ აჩერებს ყველა შიდა სამუშაოს: გააგრძელე დამოუკიდებელი
    task-ები და ცალკე შეინახე blocker/evidence request.
11. თუ გადაწყვეტილება materially ცვლის როლს, scope-ს, retention-ს, export-ს,
    უსაფრთხოებას ან business flow-ს, მიმართე მომხმარებელს არჩევანისთვის.
12. routine implementation დეტალებზე გააკეთე დასაბუთებული, დოკუმენტირებული
    ვარაუდი და განაგრძე.
13. არ მონიშნო ფაზა დასრულებულად, თუ acceptance evidence არ არსებობს.

## 7. სავალდებულო სამუშაო artifacts

აგენტმა თანმიმდევრულად უნდა შექმნას ან განაახლოს:

| Artifact | დანიშნულება |
|---|---|
| `docs/ENTERPRISE_READINESS_ACCEPTANCE_MATRIX_KA.md` | მოთხოვნა → owner → code → test → evidence → status |
| `docs/ENTERPRISE_READINESS_REPORT_2026-08-25_KA.md` | მიმდინარე Pass/Partial/Fail/External მდგომარეობა |
| `docs/ENTERPRISE_READINESS_DECISIONS_KA.md` | მიღებული და მომლოდინე გადაწყვეტილებები |
| `docs/ENTERPRISE_READINESS_TEST_EVIDENCE_KA.md` | შესრულებული commands, counts, შედეგები და გარემო |
| `docs/ENTERPRISE_READINESS_EXTERNAL_DEPENDENCIES_KA.md` | IT/IAM/DBA/Security/DPO/Legal მოთხოვნები |
| `docs/agents/HANDOFF.md` | სხვა სესიაში გასაგრძელებელი მიმდინარე snapshot |

არსებული დოკუმენტი არ უნდა დაკოპირდეს ახალ ფაილად მხოლოდ ფორმალურად. ყოველი
artifact უნდა იყოს ცოცხალი, მოკლე, ერთმანეთთან დაკავშირებული და ფაქტობრივი.

## 8. პრიორიტეტები

### P0 — Pilot/production blockers

- რეალური SSO/AD და session assurance;
- Role × Permission × Scope სრული enforcement;
- attachment object-level entitlement;
- audit completeness;
- retention/legal-hold უსაფრთხო კონტრაქტი;
- Oracle backup/restore;
- readiness/monitoring/alerting;
- Critical/High უსაფრთხოების findings;
- deployment/rollback procedure.

### P1 — აუცილებელი ხარისხი

- WCAG 2.2 AA;
- რეალური performance thresholds;
- მხარდაჭერილი browser/viewport matrix;
- search performance და bounded resources;
- operator/manager/content/system-admin UAT;
- runbooks და support ownership.

### P2 — Pilot-ის შემდეგი polish

- დაბალი რისკის ვიზუალური polish;
- არაკრიტიკული empty/error states;
- secondary reports;
- documentation refinement;
- შეთანხმებული მცირე usability improvements.

## 9. სამუშაო ნაკადები და backlog

### WS-0 — Re-baseline და პროგრამის მართვა

**მიზანი:** ძველი audit-ის ნაცვლად მიმდინარე ფაქტობრივი readiness baseline.

Tasks:

- branch/HEAD/upstream/worktree ინვენტარიზაცია;
- ბოლო ცვლილებების ფუნქციური inventory;
- ძველი readiness findings-ის current code-თან შედარება;
- Pass/Partial/Fail/External კლასიფიკაცია;
- acceptance matrix;
- risk/decision/external-dependency ledger;
- P0/P1 backlog და milestone plan.

Gate:

- ყველა P0-ს ჰყავს owner, evidence მოთხოვნა და შემდეგი მოქმედება;
- წინააღმდეგობრივი docs მონიშნულია;
- დაუკომიტებელი ცვლილებები ინვენტარიზებულია და დაცულია.

### WS-1 — Audit და evidence completeness

**მიზანი:** კრიტიკული მოქმედებები სრულად აღდგენადი audit trail-ით.

Tasks:

- article/news/video/category mutations matrix;
- create/update/delete/archive/unarchive/trash/restore/purge audit;
- user/role/permission/team/leadership ცვლილებების audit;
- login/logout/session/revoke/failure audit;
- required reading/quiz/reminder/export/file-access audit;
- actor/target/result/reason/version/snapshot consistency;
- audit-write failure semantics;
- hash-chain verify/health და archive continuity;
- positive და negative regression tests.

Gate:

- ყველა კრიტიკული mutation-ზე „ვინ/რა/როდის/ობიექტი/შედეგი“ reconstructable-ია;
- evidence transaction failure-ზე ნახევრად შესრულებული business state არ რჩება;
- deleted entity snapshot-ით კვლავ იძებნება.

### WS-2 — Access, scope და entitlement

**მიზანი:** არცერთი როლი არ ხედავს ან არ გამოაქვს ზედმეტ მონაცემს.

Tasks:

- access contract matrix-ის მიმდინარე endpoint-ებთან reconciliation;
- primary vs acting manager scope;
- დარჩენილი legacy/shadow scope call sites;
- manager stats და export separation;
- file/resource audience entitlement;
- raw audit `SYSTEM_ADMIN` only;
- last active `SYSTEM_ADMIN` protection;
- deny-by-default/IDOR/privilege-escalation tests;
- საჭიროა თუ არა dedicated `stats.view` — ცალკე PO decision gate.

Gate:

- cross-team named-data leak = 0;
- acting manager export = denied;
- UI navigation და backend authority თანხვედრილია;
- ყველა P0 endpoint-ს აქვს negative authorization test.

### WS-3 — Health, performance და bounded resources

**მიზანი:** orchestrator და IT სწორად ხედავს ავარიას; რეალური დატვირთვა მკაცრ
threshold-ებს აკმაყოფილებს.

Tasks:

- readiness vs liveness semantics;
- DB outage-ზე non-ready/non-2xx შესაბამისი probe;
- k6 observational threshold-ის რეალურ gate-ად შეცვლა;
- 150 concurrent load, 600 stress და soak profile;
- search p95 და zero-result latency;
- short-query/full-scan risk;
- cache size/TTL/eviction;
- Hikari/Tomcat/Oracle pool metrics და tuning evidence;
- failure/recovery test.

Gate:

- approved SLO threshold-ები CI/staging gate-ად მუშაობს;
- DB failure-ზე traffic unhealthy instance-ზე არ მიდის;
- normal target load-ზე error rate <1% და search p95 ≤2s, თუ decision log სხვა
  დამტკიცებულ ზღვარს არ ადგენს.

### WS-4 — Security assurance

**მიზანი:** security control implementation-ს ახლავს traceable verification.

Tasks:

- OWASP ASVS 5.0 mapping;
- threat model და abuse cases;
- auth/session/CSRF/XSS/injection/IDOR/upload tests;
- secret/dependency/container/static scan-ების CI integration;
- security headers/TLS/proxy assumptions;
- login rate-limit multi-replica strategy — IT decision/evidence;
- penetration-test findings remediation;
- SIEM event list და alert ownership.

Gate:

- Critical/High finding არ არის ღია ან დაუმტკიცებელი;
- ASVS critical requirement-ს აქვს automated/manual evidence ან signed exception;
- secret-bearing output არც log-ში და არც artifact-ში არ ხვდება.

### WS-5 — Retention, legal hold და recovery

**მიზანი:** მონაცემი არც ნაადრევად იშლება და არც უსასრულოდ ინახება პოლიტიკის
გარეშე.

Tasks:

- data classes და owners;
- active/archive/purge periods;
- content payload vs read/quiz/compliance/audit evidence separation;
- legal-hold set/release authorization და audit;
- scheduler/purge activation gate;
- archive manifest/hash/continuity;
- Oracle RMAN backup/validate/actual isolated restore runbook;
- RPO/RTO drill და result evidence.

Gate:

- DPO/Legal/DBA გადაწყვეტილების გარეშე automatic purge არ აქტიურდება;
- held evidence purge-ს ვერ გადის;
- actual restore-ში content, attachments და audit/evidence იკითხება;
- RPO/RTO შედეგი გაზომილია და ხელმოწერილია.

### WS-6 — Accessibility, browser და UAT

**მიზანი:** ოთხივე როლი რეალურ ქართულ სამუშაოს სწრაფად და დახმარების გარეშე
ასრულებს.

Tasks:

- WCAG 2.2 AA matrix;
- automated axe/static checks სადაც პრაქტიკულია;
- keyboard-only flow;
- focus/dialog/table/status semantics;
- screen-reader manual verification;
- 200% zoom და portal font scale;
- desktop/narrow laptop/tablet და შეთანხმებული browser policy;
- loading/empty/no-match/error/forbidden/session-expired states;
- ოთხივე როლის task-based UAT scripts;
- Pilot findings და regression tests.

Gate:

- critical accessibility defect = 0;
- core flows keyboard-ით სრულდება;
- UAT scenarios ≥95% pass;
- ძირითადი დავალების მონაწილეთა ≥90% დახმარების გარეშე ასრულებს, თუ Steering
  Committee სხვა დასაბუთებულ ზღვარს არ ამტკიცებს.

### WS-7 — SSO/AD, staging და operations

**მიზანი:** რეალური enterprise გარემოს ინტეგრაცია და მხარდაჭერადი rollout.

Tasks:

- IdP protocol/metadata/client/redirect/claims contract;
- immutable employee ID და org mapping;
- key/certificate rotation;
- disabled/terminated user and group-change sync;
- 30-user SSO pilot;
- outage/recovery/revoke drill;
- production-like staging;
- immutable versioned artifacts;
- secrets/TLS/DNS/proxy/network policy;
- logs/metrics/traces/dashboard/alerts/on-call;
- deploy/rollback/incident runbooks;
- hypercare plan.

Gate:

- production local login შეუძლებელია;
- IdP outage fail-closed-ია;
- staging-ზე SSO, scope, session და revoke E2E გავლილია;
- alert, deployment და rollback drill მტკიცებულებით დასრულებულია.

## 10. ფაზები და milestone-ები

### ფაზა 0 — დღე 1–5: re-baseline

- repository და მიმდინარე ცვლილებების ინვენტარიზაცია;
- ახალი acceptance/readiness/evidence artifacts;
- P0/P1 backlog;
- IT/IAM/DBA/Security/DPO evidence request;
- პირველი implementation batch-ის არჩევა.

**M0 gate:** ყველა P0 ცნობილია და არცერთი მნიშვნელოვანი მიმდინარე ცვლილება არ
არის გადაფარული.

### ფაზა 1 — კვირა 2–4: შიდა P0 hardening

რეკომენდებული რიგი:

1. audit completeness;
2. scope/file entitlement;
3. health/readiness;
4. k6 hard thresholds;
5. legal-hold უსაფრთხო state transitions;
6. critical accessibility/security regressions.

**M1 gate:** შიდა კოდური P0 დახურულია და relevant full regression green-ია.

### ფაზა 2 — კვირა 5–8: staging და external integration

- real SSO/AD;
- production-like config;
- monitoring/alerts/SIEM;
- backup/restore;
- deploy/rollback;
- 30-user identity pilot.

**M2 gate:** staging production-ის identity, scope, security და failure ქცევას
სანდოდ იმეორებს.

### ფაზა 3 — კვირა 9–11: qualification

- full Java/Angular/E2E;
- access matrix;
- load/stress/soak;
- WCAG/manual accessibility;
- ASVS/penetration test;
- SSO outage/revoke;
- restore და incident drills.

**M3 gate:** ყველა ტექნიკური release gate green-ია ან მხოლოდ signed non-blocker
exception რჩება.

### ფაზა 4 — კვირა 12–13: business pilot და GO/NO-GO

- 30–50 თანამშრომელი;
- 3 დეპარტამენტი და სხვადასხვა ცვლა;
- operator, primary/acting manager, content admin, system admin;
- daily health/adoption/defect review;
- P0 incident immediate stop;
- pilot closeout report;
- Product/Operations/IT/Security/DPO sign-off.

**M4 gate:** GO, NO-GO ან მკაფიო remediation plan.

## 11. Definition of Done თითოეული task-ისთვის

Task დასრულებულია მხოლოდ მაშინ, როცა:

- requirement და observable acceptance criteria წერია;
- implementation დასრულებულია;
- happy, denial/error და boundary paths ტესტირებულია;
- focused tests green-ია;
- შესაბამის milestone-ზე full suite green-ია;
- access/security/privacy გავლენა შეფასებულია;
- docs/API/access matrix საჭიროებისას განახლებულია;
- verification command/result evidence ledger-ში წერია;
- დაუტესტავი ნაწილი პირდაპირ არის მონიშნული `Not verified`;
- unrelated user changes არ დაზიანებულა;
- task status არის `Done`, არა უბრალოდ `Code complete`.

## 12. საწყისი verification ბრძანებები

აგენტმა ჯერ უნდა გადაამოწმოს არსებული tool/runtime გარემო. ტიპური commands:

```powershell
git rev-parse --show-toplevel
git branch --show-current
git rev-parse HEAD
git status --short
git diff --check
```

```powershell
Set-Location angular-frontend
npm test -- --watch=false
npm run build
npm audit
npx playwright test
```

```powershell
Set-Location java-backend
mvn test
mvn -DskipTests package
```

E2E/Oracle/full Maven command მხოლოდ შესაბამისი isolated/test environment-ის
დადასტურების შემდეგ უნდა გაეშვას. production data არასოდეს გამოიყენო tests-ში.
თუ dependency/environment აკლია, ჩაიწეროს `Blocked/Not verified` და გაგრძელდეს
სხვა დამოუკიდებელი task.

## 13. გადაწყვეტილებები, რომლებზეც AI აგენტმა უნდა მიმართოს მომხმარებელს

აგენტმა დასვას მოკლე, კონკრეტული კითხვა, თუ საჭიროა:

- role/permission/scope semantics-ის შეცვლა;
- `stats.view`-ის ახალი capability-ის დამატება;
- manager export field/format-ის ცვლილება;
- retention/legal-hold პერიოდი ან authority;
- SSO protocol/claims-ის საბოლოო არჩევანი, თუ IT-ის პასუხები კონკურენტულია;
- production browser support policy;
- RPO/RTO/SLO-ის საბოლოო რიცხვები;
- penetration finding-ის risk acceptance;
- pilot cohort/stop-go threshold-ის მნიშვნელოვანი ცვლილება.

კითხვა არ არის საჭირო routine refactor, test isolation, accessible name, error
handling ან ამ დოკუმენტში უკვე მკაფიოდ გადაწყვეტილი implementation-ისთვის.

## 14. GO/NO-GO წესი

Pilot/production-ზე გადასვლა აკრძალულია, თუ არსებობს რომელიმე:

- რეალური SSO/AD არ მუშაობს;
- scope leak ან unauthorized personal data access;
- acting manager-ს export შეუძლია;
- ბოლო system admin-ის დაცვა ირღვევა;
- core audit event ან evidence continuity აკლია;
- Oracle backup რეალურად არ აღდგენილა;
- Critical/High security finding დაუხურავი ან მიუღებელია;
- readiness/monitoring/rollback არ მუშაობს;
- retention/legal-hold გადაწყვეტილება აუცილებელია, მაგრამ არ არსებობს;
- core UAT flow ჩავარდა;
- P0/P1 defect ღიაა.

`GO with exception` დასაშვებია მხოლოდ non-security/non-privacy/non-data-loss
Low/Medium საკითხზე, რომელსაც აქვს owner, due date, უსაფრთხო workaround და
წერილობითი approval.

## 15. საწყისი სტატუსი

| ფაზა | სტატუსი | კომენტარი |
|---|---|---|
| Execution plan | Done | ეს დოკუმენტი შექმნილია 2026-08-25 |
| Durable handoff | Done | `docs/agents/HANDOFF.md` |
| WS-0 current re-baseline | Not started | ახალმა აგენტმა უნდა გადაამოწმოს current worktree |
| WS-1 audit hardening | Not started | მიმდინარე source-ში known gaps ხელახლა დასადასტურებელია |
| WS-2 scope/entitlement | Not started | access matrix/current endpoints reconciliation საჭიროა |
| WS-3 health/performance | Not started | current thresholds/probes ხელახლა უნდა დამტკიცდეს |
| WS-4 security assurance | Not started | ASVS mapping და pentest evidence არ არსებობს |
| WS-5 retention/recovery | External + internal pending | policy/DBA evidence საჭიროა |
| WS-6 accessibility/UAT | Not started | critical current flows უნდა გადაიხედოს |
| WS-7 SSO/staging/operations | External + internal pending | IT/IAM/Infrastructure dependency |

## 16. პირველი დავალება ახალ ჩატში

აგენტმა პირველ სამუშაო ციკლში:

1. სრულად წაიკითხოს ეს დოკუმენტი და `docs/agents/HANDOFF.md`;
2. გადაამოწმოს branch/HEAD/upstream/worktree;
3. წაიკითხოს უახლესი product/UX/access დოკუმენტები;
4. შეადაროს მიმდინარე source/tests ძველ readiness findings-ს;
5. შექმნას `ENTERPRISE_READINESS_ACCEPTANCE_MATRIX_KA.md`;
6. შექმნას ახალი current readiness report;
7. გამოარჩიოს პირველი 1–3 შიდა P0 batch;
8. გააგრძელოს პირველი batch-ის implementation და verification;
9. არ დაელოდოს external პასუხებს იმ task-ებისთვის, რომლებიც დამოუკიდებლად
   სრულდება;
10. მომხმარებელს მიმართოს მხოლოდ materially საჭირო გადაწყვეტილებისთვის.

## 17. აუცილებელი წყაროები

- `docs/agents/HANDOFF.md`
- `docs/UX_IMPLEMENTATION_DECISIONS_2026-08-24_KA.md`
- `docs/PRODUCT_UX_REQUIREMENTS_KA.md`
- `docs/ACCESS_CONTRACT_MATRIX_KA.md`
- `docs/ENTERPRISE_DEVELOPMENT_SCENARIOS_KA.md`
- `docs/PROJECT_TECHNOLOGY_GUIDE_KA.md`
- `docs/READINESS_REPORT_2026-08-23.md` — მხოლოდ ისტორიული baseline
- `docs/QUESTIONS_FOR_IT.md`
- `docs/IT_DISCOVERY_REQUEST_KA.md`
- მიმდინარე Angular routes/components/tests
- მიმდინარე Java controllers/services/security/tests
- ყველა მიმდინარე Flyway migration

## 18. AI აგენტის reporting ფორმატი

ყოველი მნიშვნელოვანი ციკლის ბოლოს აგენტმა წარმოადგინოს:

1. **Outcome** — კონკრეტულად რა გახდა true;
2. **Changed** — რომელი files/behaviors შეიცვალა;
3. **Evidence** — commands, counts და observed results;
4. **Risks/Not verified** — რა არ დადასტურდა;
5. **Decisions needed** — მხოლოდ რეალური არჩევანი;
6. **Next batch** — შემდეგი ყველაზე მაღალი ღირებულების P0/P1 სამუშაო.

არ გამოიყენოს ბუნდოვანი ფრაზები „ყველაფერი მზადაა“, „სრულად უსაფრთხოა“ ან
„production-ready“, თუ შესაბამისი gate-ის ყველა evidence არ არსებობს.

