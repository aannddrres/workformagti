# Magti Call Center Portal — Enterprise განვითარების სცენარები

**ვერსია:** 1.0  
**თარიღი:** 2026-08-24  
**დროითი ჰორიზონტი:** 36+ თვე  
**მასშტაბი:** დაახლოებით 600 მომხმარებელი; სამუშაო ვარაუდი — პიკურ პერიოდში 150-მდე ერთდროული მომხმარებელი  
**სტატუსი:** სტრატეგიული გადაწყვეტილების დოკუმენტი; არ არის production cutover-ის ნებართვა

> **მტკიცებულებათა იერარქია.** შეფასება ეყრდნობა მიმდინარე კოდს, ტესტებსა და Flyway მიგრაციებს; შემდეგ — 2026-08-24-ის UX/access გადაწყვეტილებებს; ბოლოს — ძველ არქიტექტურულ დოკუმენტებს. წინააღმდეგობისას ახალი იმპლემენტაცია სჯობს ძველ აღწერას. გარე მაგალითებში „კომპანია აცხადებს“ ნიშნავს საჯარო პირველწყაროში აღწერილ ფაქტს და არა Magti-ში იმავე შედეგის გარანტიას.

---

## 1. Executive Summary

### გადაწყვეტილება ერთ წინადადებაში

Magti-მ უნდა აირჩიოს **სცენარი B — ტრანსფორმაციული ბირთვი**, მაგრამ დაიწყოს 90-დღიანი „foundation-first“ პაკეტით: production SSO/AD, არასწორი მომსახურების guardrail-ები, ცოდნის ხარისხი/ძიების telemetry, major-incident ოპერირება, `stats.view`-ის გამიჯვნა, accessibility და retention/legal-hold control-ები; მხოლოდ ამის შემდეგ — CTI, QA, journey analytics, RPA და ქართულენოვანი, წყაროებით შემოსაზღვრული AI-assist.

### რატომ ახლა

- Portal უკვე აღარ არის მხოლოდ კონტენტის პროტოტიპი: მას აქვს Angular/Spring Boot/Oracle საფუძველი, როლები/permission override-ები, ორგანიზაციული scope, ატომური content commands, required reading/quiz, audit chain, export evidence, trash/evidence snapshots, broadcast/reminder და portal sessions.
- მიმდინარე კოდმა დახურა readiness report-ის რამდენიმე ძველი ხარვეზი: Quill 2 + server-side HTML sanitization, auth-only attachment access, HttpOnly cookie/CSRF, DB-backed session control და SSO-ს production fail-closed რეჟიმი.
- launch readiness მაინც დამოკიდებულია რეალურ SSO/AD metadata/configuration-ზე, Oracle backup/restore drill-ზე, deploy/rollback runbook-ზე, scope/export კონტროლების საბოლოო შემოწმებასა და DPO/Legal retention გადაწყვეტილებებზე.
- 36-თვიანი ღირებულების ყველაზე დიდი წყარო არის არა „მეტი კონტენტი“, არამედ **სწორი პასუხი პირველივე კონტაქტზე, ნაკლები ოპერატორული გადართვა, გაზომვადი ხარისხი, კონტროლირებადი handoff და მომსახურების მოვლენების ერთიანი მონაცემთა ფენა**.

### რეკომენდებული შედეგები და guardrail-ები

| შედეგი | 12-თვიანი მიმართულებითი მიზანი* | guardrail |
|---|---:|---|
| არასწორი მომსახურება | −20–30% pilot-ით დადასტურებულ contact reasons-ში | მხოლოდ გაზომილი baseline-ის შემდეგ; არ დაიმალოს recontact |
| ძიების დრო | P50 < 10 წმ; P90 < 25 წმ | ქართული ზუსტი/ტრანსლიტერაციის შედეგები ცალ-ცალკე იზომება |
| zero-result search | −30% top intents-ზე | ახალი სტატია მხოლოდ owner/SLA-ით |
| first-contact resolution | +5–10 პპ pilot queues-ში | transfer-ისა და 7-დღიანი recontact-ის ჩათვლით |
| after-call work | −15–25% selected workflows-ში | AI draft-ს ადამიანი ადასტურებს |
| ცოდნის freshness | კრიტიკული სტატიების ≥95% SLA-ში | owner-ის გარეშე კრიტიკული მასალა არ ქვეყნდება |
| manager visibility | ყოველდღიური scoped cockpit | acting manager-ს export არ ეძლევა |
| უსაფრთხოება/აუდიტი | 100% privileged action traceability | raw audit მხოლოდ system admin-ს |

\* მიზნები არის **სამუშაო დიაპაზონი**, არა დაპირება; მათი დამტკიცება საჭიროა 4-კვირიანი baseline-ით და კონტროლირებულ pilot-ებში.

### მთავარი საინვესტიციო არჩევანი

1. **ახლავე ავაშენოთ:** portal-ის სიღრმე — guardrail-ები, ცოდნის lifecycle, search telemetry, QA/coaching, scoped analytics, evidence/retention, integration APIs.
2. **ვიყიდოთ/დავაპარტნიორდეთ:** telephony/CTI connector, WFM optimization, speech-to-text/redaction და, საჭიროების შემთხვევაში, enterprise contact-center AI runtime.
3. **არ ავაშენოთ ჯერ:** custom PBX/ACD, native mobile app, თავისუფალი role builder, microservices decomposition, autonomous AI publishing ან customer-facing agent ადამიანთან საიმედო handoff-ის გარეშე.

### Top-10 investment themes

H1-09 Major-incident playbook (98), H1-02 wrong-service guardrail (96), H1-01 SSO/session (93), H1-06 freshness SLA (93), H1-03 operator home (91), H1-05 search telemetry (91), H2-04 QA calibration (89), H2-05 journey analytics (89), H1-04 knowledge cockpit (88), H1-12 retention/evidence baseline (87).

---

## 2. მიმდინარე capability map

| Capability | მიმდინარე მდგომარეობა | მტკიცებულება | შეფასება / შემდეგი ნაბიჯი |
|---|---|---|---|
| Identity & session | production local login იკეტება; SSO start fail-closed; HttpOnly cookie + CSRF; DB portal sessions, idle 30 წთ/max 8 სთ | `SecurityConfig`, `JwtAuthenticationFilter`, auth/session controllers, V44 | **ნაწილობრივი:** რეალური AD/SSO metadata, key/secret custody, revocation drill და end-to-end pilot აკლია |
| RBAC/permissions | 4 კანონიერი როლი; per-user override; system admin bypass; ბოლო აქტიური system admin დაცულია | security/domain/services, access matrix | **რეალიზებული ბირთვი:** `stats.view` ცალკე capability ჯერ არ ჩანს permission enum-ში |
| Organisation scope | department/group/team და leadership assignment; canonical manager `team_id`; primary vs acting scope resolver-ები | V36/V36_1, `ScopeResolver` | **ნაწილობრივი:** ძველი general `resolve()`/shadow comparison-ის დარჩენილი გამოყენება და ყველა endpoint-ის enforcement უნდა დაიხუროს |
| Content lifecycle | article/news/video, unified queue, atomic content+assignment(+quiz), archive/trash/restore | command controllers, V42/V43 | **ძლიერი ბირთვი:** legal hold-ის lifecycle ჯერ სუსტია; category integrity/performance გასამაგრებელია |
| Required reading & quiz | department/all targeting, receipt, reminders, attempts/evidence, quiz | migrations/services/tests | **რეალიზებული:** operational SLA/dashboard და overdue action loop გასაძლიერებელია |
| Search | exact/text/trigram საფუძვლები, search logs, category/tag paths | V29, search services | **ნაწილობრივი:** მოკლე query-ზე full scan, არაბაუნდირებული cache, relevance/zero-result governance არ არსებობს |
| Audit & evidence | hash-chain, snapshots, raw audit მხოლოდ system admin, deleted evidence retention | V28/V42/V43, audit/security | **რეალიზებული ბირთვი:** archive/verification runbook, legal hold და 1-წლიანი live/archive policy დასამტკიცებელია |
| Export | async export model/blob, TTL, audit; primary leadership scope | V31/V41, export services | **ნაწილობრივი:** manager 8-field allowlist/DPO sign-off და production purge job უნდა დადასტურდეს |
| Broadcast/incident | company-wide passive broadcast; audit | V38, broadcast feature | **ნაწილობრივი:** incident severity, owner, acknowledgement/escalation და post-incident learning ციკლი აკლია |
| Manager analytics | team stats და leadership scope | stats controllers/services | **ნაწილობრივი:** დღეს content capability-ზეა მიბმული; dedicated `stats.view`, metric dictionary და freshness SLA საჭიროა |
| File delivery | authentication, audit, no-store/CSP | uploaded-file controller | **ნაწილობრივი:** object-level audience entitlement ცალკე შესამოწმებელია |
| Accessibility/localization | ქართული primary, Noto Sans Georgian, dark/font scaling design decisions | UX docs, Angular UI | **ნაწილობრივი:** WCAG 2.2 AA regression suite და screen-reader/keyboard evidence არ არის სრულად ჩამოყალიბებული |
| Resilience/operations | health endpoint, Oracle pool/Flyway, tests | config, health controller, tests | **ნაწილობრივი/რისკი:** degraded DB-ზე health HTTP 200; backup/restore, RPO/RTO და rollback drill გარე dependency-ებია |
| CTI/CRM/omnichannel | portal-ში native integration არ ჩანს | routes/services/dependencies | **არ არის:** discovery + vendor/API contract საჭიროა |
| WFM/QA/coaching | portal-ში სრული WFM/QA domain არ ჩანს | schema/routes review | **არ არის:** ჯერ import/pilot, შემდეგ workflow |
| AI/voice/RPA | production capability არ ჩანს | repository review | **არ არის და სწორად:** foundation/data/governance-ის შემდეგ, მხოლოდ human-in-control pilot |

### სტატუსების გამიჯვნა

- **უკვე რეალიზებული:** core RBAC, system-admin protection, common content queue, content commands, required reading/quiz evidence, audit chain, trash evidence snapshots, broadcasts, reminders, export framework, portal sessions, server-side sanitization.
- **ნაწილობრივ რეალიზებული:** SSO/session operationalization, organisation scope enforcement, manager analytics, search, file audience authorization, retention/legal hold, exports, accessibility, operational health.
- **დაგეგმილი/შემდეგი rollout:** real AD/SSO, production Oracle/cutover, delta/reconcile jobs, backup/restore and rollback drill, Python legacy read-only window/off switch.
- **IT/DBA/DPO/Legal-ზე დამოკიდებული:** identity metadata/secrets, telephony APIs, Oracle HA/backup, data retention, voice recording/transcription, manager export fields, AI processor/data residency, legal hold and purge.
- **დახურული პროდუქტის გადაწყვეტილებები:** მხოლოდ SSO/AD production; AD unavailable ⇒ fail closed; acting manager export არა; feedback feature აღარ ბრუნდება; deleted evidence ინახება; raw audit მხოლოდ system admin; ერთი სავალდებულო system admin დაცულია; common content queue; ქართული primary; ~600 users.

---

## 3. უკვე არსებული შესაძლებლობები და მათი სტრატეგიული ღირებულება

### 3.1 ცოდნისა და compliance-ის trusted core

Portal-ს უკვე შეუძლია სტატიის/სიახლის/ვიდეოს საერთო queue-ით მართვა, draft/publish/archive/trash lifecycle, required reading, acknowledgement, quiz და attempts/evidence. ეს ქმნის „ერთი დამტკიცებული წყაროს“ საფუძველს და ამცირებს არაოფიციალური ინსტრუქციების რისკს. ატომური commands მნიშვნელოვანია: content update და compliance assignment აღარ უნდა დარჩეს ნახევრად შესრულებულ მდგომარეობაში.

### 3.2 კონტროლირებადი წვდომა

ოთხი კანონიერი როლი + per-user permission override უფრო მართვადი არჩევანია, ვიდრე custom role builder. system admin-ის სრული capability და ბოლო აქტიური system admin-ის დაცვა operational lockout-ს ამცირებს. manager-ის canonical team და acting leadership-ის primary/export scope-იდან გამიჯვნა შეესაბამება მინიმალური პრივილეგიის პრინციპს.

### 3.3 აუდიტი და evidence continuity

Audit hash-chain, snapshots და deletion-ის შემდეგ evidence-ის შენარჩუნება საკმარისი საფუძველია compliance-grade traceability-სთვის. ეს უნდა განვითარდეს verification job-ად, archive manifest-ად და legal-hold control-ად; ახალი audit subsystem-ის თავიდან აშენება არ არის საჭირო.

### 3.4 თანამედროვე უსაფრთხოების გზა

Quill 2, backend sanitization, HttpOnly cookies, CSRF, DB session records და fail-closed SSO starter readiness-ის ძველ ანგარიშთან შედარებით არსებითი გაუმჯობესებაა. მაგრამ ეს არის **იმპლემენტირებული კონტროლი**, არა production assurance: საბოლოო assurance მოითხოვს რეალურ IdP-ს, proxy/TLS გარემოს, security test-სა და revocation/cutover drill-ს.

### 3.5 არსებული ტესტური ბაზა

რეპოზიტორიის inventory-ზე დაფიქსირდა დაახლოებით 99 Java test source/645 `@Test`, 25 Angular spec/88 `it` და 19 E2E file/46 `test`. რაოდენობა არ უდრის risk coverage-ს, თუმცა საკმარისი საწყისია contract, scope, access, lifecycle და migration regression-ის გასაფართოებლად.

---

## 4. capability gap-ები და root causes

| Gap | Root cause | ბიზნეს-რისკი | გამოსავალი |
|---|---|---|---|
| production identity არ არის დასრულებული | AD/SSO metadata და გარემო გარეა | launch blocker; fail-closed outage | IT-owned SSO pilot, break-glass runbook არა offline login |
| არასწორი მომსახურება არ იზომება end-to-end | contact reason, outcome, transfer, recontact Portal-ს გარეთაა | FCR/quality გაურკვეველია | interaction ID + reason/outcome taxonomy + 7-day recontact |
| knowledge quality reactive-ია | owner/SLA/staleness/zero-result loop სუსტია | მოძველებული პასუხი | quality cockpit, owner, expiry/review, search telemetry |
| manager analytics capability არასწორადაა შეკრული | stats access content permission-ზეა მიბმული | ზედმეტი/არათანმიმდევრული წვდომა | dedicated `stats.view` + scoped aggregate contract |
| CTI/CRM context არ არის | integration contracts უცნობია | swivel-chair, AHT, შეცდომა | screen-pop pilot, read-only context first |
| QA/coaching workflow არ არის | ჩანაწერის/sampling/calibration მოდელი აკლია | ხარისხის მერყეობა | sampling + dual scoring + calibration + coaching actions |
| WFM data disconnected | schedule/forecast source უცნობია | overload/understaffing | CSV/API import pilot; შემდეგ skills planning |
| operational resilience არ არის დამტკიცებული | backup/restore/rollback drill გარე პროცესია | outage/data loss | DBA-led RPO/RTO evidence and restore test |
| legal hold/purge არასრულია | lifecycle-ში `legal_hold=0` default/reset ჩანს | evidence loss ან ზედმეტი retention | DPO/Legal policy + hold state machine + audited release |
| search scale/relevance risk | short query full scan; unbounded cache | latency/memory/poor results | bounded cache, min-query UX, query plan/load test |
| file entitlement შეიძლება ფართო იყოს | auth არის, object audience check დასამტკიცებელია | content exposure | resource-to-audience authorization test/contract |
| AI foundation არ არსებობს | transcript quality, Georgian eval, processor rules უცნობია | hallucination/privacy/vendor lock-in | sandbox, redaction, golden set, citations, human approval |

### Pre-mortem: თუ პროგრამა 18 თვეში ჩავარდა, ყველაზე სავარაუდო მიზეზები

1. AI იყიდა მანამდე, სანამ knowledge ownership და data quality დადგებოდა.
2. CTI ინტეგრაცია „ყველაფერი ერთბაშად“ პროექტად გადაიქცა და read-only pilot არ გაკეთდა.
3. metric dictionary არ შეთანხმდა; AHT შემცირდა, მაგრამ recontact და wrong-service გაიზარდა.
4. manager scope/export exception-ებმა security model გააფუჭა.
5. DBA/IT/DPO გადაწყვეტილებები roadmap-ის ბოლოს დარჩა და launch gate გახდა.
6. ოპერატორებს ახალი სამუშაო გაუჩნდათ, ძველი კი არ გაუქმდა; adoption დაეცა.
7. ქართული search/voice ხარისხი vendor demo-თი შეფასდა და არა Magti golden set-ით.

---

## 5. საერთაშორისო benchmark — 24 ოფიციალური მაგალითი, 13 ინდუსტრია

> ცხრილში შედეგები ზუსტად იმ დონეზეა გადმოცემული, რასაც პირველწყარო აცხადებს. Vendor capability არის შესაძლო მოდელის მაგალითი და არა დამოუკიდებლად დადასტურებული ROI.

| # | ინდუსტრია / ორგანიზაცია | საჯარო ინიციატივა და ფაქტი | Magti-სთვის transferable lesson | წყარო |
|---:|---|---|---|---|
| 1 | Telecom — Deutsche Telekom | Frag Magenta არის web/app/voice gateway; 2025 ანგარიშში აღწერილია GenAI და ავტომატური ticket structuring; Magenta View ერთ front-end-ში აერთიანებს customer/technology data-ს | ერთი front door + 360° context; ჯერ source systems-ის სანდოობა | [DT AI](https://www.telekom.com/en/company/digital-responsibility/details/artificial-intelligence-at-deutsche-telekom-1055154), [2025 Annual Report](https://report.telekom.com/annual-report-2025/management-report/group-strategy/data-ai.html) |
| 2 | Telecom — Vodafone | TOBi მრავალ ბაზარსა და ენაზე მუშაობს; SuperAgent repetitive work-ს ამცირებს; Vodafone აცხადებს AI disclosure-სა და human focus-ს რთულ შემთხვევებზე | ქართული language eval + მკაფიო AI disclosure + human escalation | [Vodafone GenAI](https://www.vodafone.com/news/newsroom/technology/vodafone-supercharging-customer-experience-with-microsoft-s-gen-ai-tools), [AI Framework](https://www.vodafone.com/news/newsroom/public-policy/vodafone-launches-artificial-intelligence-framework) |
| 3 | Telecom — Telefónica | Aura multiple channels-ში customer data/service interaction-ს აერთიანებს; კომპანია human team-ის ჩანაცვლების ნაცვლად FCR-ის გაუმჯობესებაზე საუბრობს | AI როგორც relationship/context layer, არა უკონტროლო publisher | [Aura launch](https://www.telefonica.com/en/communication-room/press-room/telefonica-launches-aura-and-leads-the-integration-of-artifical-intelligence-in-its-networks-and-customer-care/), [customer interaction](https://www.telefonica.com/en/communication-room/blog/closer-to-customer-in-every-interaction/) |
| 4 | Banking — JPMorganChase | LLM Suite 200,000+ თანამშრომლისთვის controlled environment-ში, customer/company data protection-ით | broad assistant მხოლოდ enterprise guardrails, shared capabilities და data controls-ით | [2024 COO letter](https://www.jpmorganchase.com/content/dam/jpmc/jpmorgan-chase-and-co/investor-relations/documents/chief-operating-officer-letter.pdf) |
| 5 | Banking — BBVA | 3,000 საწყისი ChatGPT Enterprise licence-იდან 11,000-მდე გაფართოება; თანამშრომელთა custom GPT-ები და adoption governance | მცირე cohort → measured adoption → controlled scale; champion network | [BBVA initial adoption](https://www.bbva.com/en/innovation/bbva-sparks-a-wave-of-innovation-among-its-employees-with-the-deployment-of-chatgpt-enterprise/), [scale-up](https://www.bbva.com/en/innovation/bbva-expands-its-agreement-with-openai-to-11000-chatgpt-licences-for-the-banks-employees/) |
| 6 | Banking — DBS | CSO Assistant transcribes/summarizes/recommends; DBS 2024 source expected up to 20% handling-time reduction; 2025 Joy includes evaluator review and human handoff | draft + recommendation + post-call human evaluation; KPI baseline required | [DBS CSO Assistant](https://www.dbs.com/NewsPrinter.page?locale=en&newsId=lyq3tqw8), [DBS Joy](https://www.dbs.com/NewsPrinter.page?locale=en&newsId=mhp8jvh0) |
| 7 | Insurance — AXA | Secure GPT created internal secure LLM access; later M365 Copilot rollout followed employee-representative dialogue | safe enterprise gateway and workforce consultation before scale | [AXA AI workplace](https://www.axa.com/en/press/press-releases/axa-accelerates-its-ai-workplace-strategy) |
| 8 | Retail — Walmart | My Assistant launched in-house, expanded by cohort/country and local language; focus on drafting, summarization and secure company knowledge | build reusable assistant shell; local-language/domain knowledge; release by cohort | [Walmart My Assistant](https://corporate.walmart.com/news/2024/01/09/walmarts-expanding-one-of-a-kind-associate-genai-tool-to-11-countries-in-2024), [Digital Trust](https://corporate.walmart.com/purpose/esgreport/digital-trust) |
| 9 | Retail — Target | Store Companion answers process questions, coaches new staff and rolled out chainwide after testing | frontline knowledge assistant starts with narrow procedures and onboarding | [Target Store Companion](https://corporate.target.com/press/release/2024/06/target-to-roll-out-transformative-genai-technology-to-its-store-team-members-chainwide) |
| 10 | Retail — IKEA | Billie provides 24/7 chatbot/voicebot with coworker transfer; privacy materials publish transcript/retention handling | human handoff + explicit retention/transcript policy are product features | [IKEA chat terms](https://www.ikea.com/es/en/customer-service/terms-conditions/terminos-condiciones-chat-pub51ab0db0/), [IKEA contact](https://www.ikea.com/au/en/customer-service/contact-us/) |
| 11 | Logistics — UPS | ORION/UPSNav combines optimization with handheld workflow and dynamic route changes | intelligence must live inside frontline workflow, not separate dashboard | [UPSNav](https://about.ups.com/gb/en/newsroom/press-releases/innovation-driven/ups-deploys-purpose-built-navigation-for-ups-service-personnel.html) |
| 12 | Logistics — DHL | group digital platforms describe 24/7 virtual assistants, RPA/OCR and employee/customer use; Viva publishes tasks and limitations | publish bot limitations; automate repetitive back office before high-risk decisions | [DHL digital platforms](https://careers.dhl.com/apac/en/digital-platforms), [Viva](https://www.dhl.com/it-en/home/freight/help-center-for-european-road-and-rail/welcome-to-viva.html) |
| 13 | Aviation — Delta | Delta Concierge beta used phased rollout, limited capability, internal context and live-care handoff | beta by cohort + service-disruption context + full handoff history | [Delta Concierge beta](https://news.delta.com/smarter-journeys-start-here-delta-concierge-now-beta-rollout) |
| 14 | Healthcare — HCA Healthcare | GenAI converts clinical conversation into draft note that physician reviews; secure storage/privacy controls emphasized | high-risk output is draft; accountable human finalizes; privacy-by-design | [HCA + Google Cloud](https://investor.hcahealthcare.com/news/news-details/2023/HCA-Healthcare-Collaborates-With-Google-Cloud-to-Bring-Generative-AI-to-Hospitals/default.aspx) |
| 15 | Energy/Utilities — Octopus/Kraken | end-to-end utility platform links billing, customer service, communication and self-service; Magic Ink drafts service email across clients/languages | contact center gains from operating-model/data integration, not chatbot alone | [Kraken platform](https://octopusenergy.group/kraken-technologies), [FY25 report](https://octopusenergy.group/static/documents/KTL_Financial_statements_Annual_Report_FY25.pdf) |
| 16 | Manufacturing/Energy management — Schneider Electric | Knowledge Bot supports care reps from internal documents; Jo hands history to live agents; AI Hub prioritizes enterprise use cases | cited knowledge + seamless handoff + central governance/federated domain owners | [Schneider GenAI](https://www.se.com/ww/en/about-us/newsroom/news/press-releases/schneider-electric-drives-generative-ai-productivity-and-sustainability-solutions-by-integrating-microsoft-azure-openai-6551d3eacbbacd6fa60f2b03/), [AI use cases](https://www.se.com/us/en/work/solutions/artificial-intelligence/solutions/) |
| 17 | Public sector — NYC311 | NYC311 positions online as digital front door to city information/services and preserves request records | single service taxonomy/status view; public-record/evidence discipline | [About NYC311](https://portal.311.nyc.gov/about-nyc-311/) |
| 18 | BPO/CX — Teleperformance | TP GenAI combines agent assist, knowledge, interaction analytics and back-office automation; official framing retains human emotional intelligence | portfolio approach; AI handles repetition, people handle nuanced/high-value contacts | [TP Integrated Report](https://www.teleperformance.com/media/mdhnzhj0/240320_teleperformance_ri2023_gb_mel.pdf) |
| 19 | Hospitality — Accor | contact centers blend channels, translation and human touch; AccorGPT/Travel Concierge framed across guests, hotels and teams | translate/assist across journey, but protect hospitality/human accountability | [Accor contact centers](https://group.accor.com/en/news-stories/contact-centers-guest-experience), [AI strategy](https://group.accor.com/en/news-stories/accor-leading-hospitality-ai) |
| 20 | Contact-center technology — Microsoft | Dynamics 365 documents case, customer-intent, QA and service-operations agents with human oversight | assess suite breadth, but demand scoped agent roles and override/audit | [Microsoft AI/Copilot overview](https://learn.microsoft.com/en-us/dynamics365/contact-center/use/overview-ai-agents-copilot-features) |
| 21 | Contact-center technology — AWS | Amazon Q in Connect detects intent and suggests responses/actions with source links inside agent workspace | embedded assist + citation; API-first option for custom desktop | [Amazon Q in Connect](https://aws.amazon.com/about-aws/whats-new/2023/11/amazon-q-connect-generative-ai-powered-agent-assistance-real-time/) |
| 22 | Contact-center technology — Google Cloud | CCAI Agent Assist supports contact-center integration via conversation profiles/service accounts | identity/key/data-boundary diligence is prerequisite, not afterthought | [Google Agent Assist](https://docs.cloud.google.com/contact-center/ccai-platform/docs/agent-assist) |
| 23 | CRM/CX technology — Salesforce | Agentforce Service unifies channels, cases, routing, self-service and human escalation | buy-vs-build comparator for CRM/omnichannel; avoid duplicating mature case routing | [Agentforce Service docs](https://help.salesforce.com/s/articleView?id=sf.service_cloud.htm&language=en_US) |
| 24 | Automotive/customer experience — Mercedes-Benz | MBUX virtual assistant is positioned as contextual in-product experience; manuals explicitly say GenAI output is aid | context-rich assistance must still disclose limitation and preserve user control | [Mercedes-Benz 2025 report](https://group.mercedes-benz.com/documents/investors/reports/annual-report/mercedes-benz/mercedes-benz-annual-report-2025-incl-combined-management-report-mbg-ag.pdf) |

### Benchmark synthesis

საერთო pattern-ები: (1) frontline workflow-ში ჩაშენება; (2) source-linked answer; (3) human handoff/approval; (4) phased rollout; (5) central governance + domain ownership; (6) self-service და agent assist ერთიან journey-ში; (7) metrics, retention და disclosure წინასწარ; (8) AI-მდე strong knowledge/data/integration core.

---

## 6. 72-იდეიანი opportunity universe

**ნიშნები:** N = არა-AI; A = AI/ML; H = hybrid. 72 იდეიდან 51 (71%) არის N ან ისეთი foundation, რომლის ღირებულება AI-ის გარეშეც არსებობს; მხოლოდ 8 იდეაა უშუალოდ content lifecycle-ზე ორიენტირებული. ამით universe განზრახ გადის „მეტი კონტენტის“ ფარგლებს გარეთ.

| კლასტერი | იდეები (ID — ტიპი — ჰორიზონტი) |
|---|---|
| A. Operator desktop & workflow | U01 ერთიანი operator home — N — H1; U02 CTI screen-pop — N — H2; U03 wrong-service checklist/guardrail — N — H1; U04 case/task handoff — N — H2; U05 ACW structured templates — N — H1; U06 customer context timeline — N — H2; U07 one-click expert escalation — N — H2; U08 approved macros/actions — N — H1; U09 shift-start readiness checklist — N — H1; U10 supervisor assist console — N — H2; U11 interruption-minimizing focus mode — N — H1; U12 accessibility preferences/profiles — N — H1 |
| B. Knowledge, learning & expertise | U13 freshness owner/SLA — N — H1; U14 knowledge quality cockpit — N — H1; U15 search/zero-result telemetry — N — H1; U16 Georgian synonym/transliteration governance — N — H1; U17 Georgian semantic search — A — H2; U18 grounded agent assist — A — H2; U19 enterprise knowledge graph — H — H3; U20 source-linked answer cards — N — H2; U21 role/skill learning paths — N — H2; U22 3-minute scenario drills — N — H1; U23 QA-to-knowledge correction loop — N — H2; U24 expertise directory — N — H2 |
| C. Workforce, QA & wellbeing | U25 WFM schedule/skills import — N — H1; U26 skills-based planning — N — H2; U27 capacity/absence forecast — A — H3; U28 adherence exception alert — N — H2; U29 shift-fairness analysis — N — H2; U30 risk-based QA sampling — N — H2; U31 QA calibration cockpit — N — H2; U32 coaching action plans — N — H2; U33 passive workload/friction telemetry — N — H1; U34 break-adherence and overload guardrails — N — H1; U35 skill certification matrix — N — H2; U36 surge staffing playbook — N — H1 |
| D. Journey, omnichannel & self-service | U37 omnichannel intake normalization — N — H2; U38 contact-reason/outcome taxonomy — N — H1; U39 journey/recontact analytics — N — H2; U40 guided self-service diagnostics — N — H2; U41 customer digital assistant + human handoff — A — H3; U42 proactive outage/recovery messaging — H — H3; U43 scheduled callback queue — N — H2; U44 complaint rescue lane — N — H2; U45 vulnerable-customer handling — N — H2; U46 AI/digital-to-human transcript package — N — H3; U47 cross-channel interaction ID — N — H2; U48 resolution status page — N — H2 |
| E. Automation, data, integration & control | U49 contact-center event hub/API — N — H3; U50 approved back-office RPA — N — H2; U51 after-call summary draft — A — H2; U52 voice transcription/redaction analytics — A — H3; U53 process/task mining — H — H3; U54 human-controlled automation orchestration — H — H3; U55 data catalog/lineage — N — H2; U56 retention control plane — N — H1; U57 dedicated `stats.view` — N — H1; U58 evidence ledger + legal hold — N — H1; U59 production SSO/session hardening — N — H1; U60 backup/restore/DR rehearsal — N — H1 |
| F. Incident, risk, revenue & innovation | U61 major-incident command/playbook — N — H1; U62 fraud/scam signal assist — A — H3; U63 next-best-action, human-approved — A — H3; U64 guarded revenue offers — H — H3; U65 voice-of-contact theme discovery — A — H3; U66 service-failure cost model — N — H2; U67 quarterly experiment portfolio — N — H1; U68 vendor capability sandbox — N — H1; U69 partner referral handoff loop — N — H2; U70 governed service-recovery credits — N — H3; U71 customer-effort observatory — N — H2; U72 AI use-case/model registry — N — H2 |

---

## 7. თემატური კლასტერები და იდეების გენერაციის ლოგიკა

### 7.1 JTBD

- **ოპერატორი:** „როცა კლიენტი მიკავშირდება, მინდა მისი კონტექსტი და მხოლოდ მოქმედი ინსტრუქცია ერთ ეკრანზე, რათა თავიდან ავიცილო არასწორი მომსახურება და ზედმეტი გადართვა.“
- **მენეჯერი:** „როცა ცვლა/გუნდი რისკშია, მინდა scoped, დროული მიზეზები და მოქმედების queue, არა მხოლოდ dashboard.“
- **content/compliance owner:** „როცა პოლიტიკა იცვლება, მინდა ვიცოდე ვის შეეხო, ვინ წაიკითხა/ჩააბარა და რომელი ძველი პასუხი უნდა მოიხსნას.“
- **სისტემის ადმინისტრატორი/აუდიტორი:** „როცა privileged მოქმედება ან deletion ხდება, მინდა tamper-evident evidence და მინიმალური წვდომა.“
- **კლიენტი:** „მინდა ჩემი საკითხი ერთხელ ავხსნა და მივიღო სწორი გადაწყვეტა ან სრული კონტექსტით გადაცემული handoff.“

### 7.2 Service blueprint

`მოთხოვნა → იდენტიფიკაცია/კონტექსტი → intent/reason → ცოდნა/დიაგნოსტიკა → ქმედება/გადაცემა → დადასტურება → ACW/evidence → recontact/outcome → learning loop`.

Frontstage-ს (operator/customer experience) მხარს უნდა უჭერდეს backstage: identity, source systems, content ownership, scope, integration events, evidence retention, QA calibration, SRE/DBA და DPO governance.

### 7.3 Value chain და deep-module პრინციპი

ინვესტიციის ღირებულების ჯაჭვია: **სანდო identity → სანდო scope → სანდო knowledge/data → სწორი workflow → გაზომვადი outcome → კონტროლირებული automation**. Portal უნდა დარჩეს modular monolith-ად ღრმა domain interfaces-ით. CTI/WFM/AI providers იცვლებოდეს adapter-ებით; business policy არ უნდა გაიფანტოს vendor flow-ებში.

### 7.4 Cross-industry transfer

- logistics-იდან: real-time recommendation frontline მოწყობილობაში;
- healthcare-იდან: high-risk draft + accountable human finalization;
- banking/insurance-იდან: controlled AI environment, processor/data governance;
- retail-იდან: narrow process assistant + cohort rollout;
- aviation/hospitality-იდან: journey context და human handoff;
- public sector-იდან: single taxonomy, status და durable records;
- manufacturing-იდან: central AI hub + federated domain owners.

### 7.5 Three Horizons

- **H1 (0–3 თვე):** launch safety, knowledge/search operations, scoped management, incident/accessibility/data controls.
- **H2 (3–12 თვე):** CTI/case/QA/WFM/journey layer, controlled RPA და internal AI-assist pilots.
- **H3 (12–36+ თვე):** voice/AI, proactive recovery, event hub, cross-channel self-service და guarded revenue automation.

---

## 8. 36-იდეიანი დეტალური shortlist და scoring

### 8.1 შეფასების მეთოდი

ყველა კრიტერიუმი 1–5: **BV** business value (25%), **UI** operator/manager impact (15%), **RR** wrong-service risk reduction (15%), **SF** strategic fit (10%), **FE** feasibility (10%), **TV** time-to-value (10%), **DR** data/integration readiness (5%), **SP** security/privacy risk (5%), **OC** operational support complexity (5%). SP/OC-ში **5 = დაბალი რისკი/მარტივი მხარდაჭერა**, 1 = მაღალი. 0–100 ქულა ითვლება ფორმულით: `5×BV + 3×UI + 3×RR + 2×SF + 2×FE + 2×TV + DR + SP + OC`.

> ქულები არის portfolio triage, არა business case. ყოველ pilot-ს უნდა ჰქონდეს baseline, owner, stop/go gate და სარგებლის დამოუკიდებელი შემოწმება.

| ინიციატივა | BV | UI | RR | SF | FE | TV | DR | SP | OC | /100 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| H1-01 Production SSO/session | 5 | 4 | 5 | 5 | 4 | 5 | 4 | 5 | 4 | **93** |
| H1-02 Wrong-service guardrail | 5 | 5 | 5 | 5 | 4 | 5 | 4 | 5 | 4 | **96** |
| H1-03 Operator unified home | 4 | 5 | 4 | 5 | 5 | 5 | 4 | 5 | 5 | **91** |
| H1-04 Knowledge quality cockpit | 5 | 4 | 4 | 5 | 4 | 4 | 4 | 5 | 4 | **88** |
| H1-05 Search telemetry | 5 | 5 | 4 | 5 | 4 | 4 | 4 | 5 | 4 | **91** |
| H1-06 Freshness owner/SLA | 5 | 4 | 4 | 5 | 5 | 5 | 4 | 5 | 5 | **93** |
| H1-07 Manager insights v1 | 4 | 4 | 4 | 5 | 4 | 4 | 4 | 5 | 4 | **83** |
| H1-08 WFM import pilot | 4 | 4 | 4 | 4 | 3 | 3 | 2 | 4 | 3 | **73** |
| H1-09 Major-incident playbook | 5 | 5 | 5 | 5 | 5 | 5 | 4 | 5 | 4 | **98** |
| H1-10 WCAG 2.2 AA uplift | 4 | 5 | 3 | 5 | 5 | 4 | 5 | 5 | 5 | **87** |
| H1-11 Workload/friction telemetry | 4 | 4 | 4 | 4 | 4 | 4 | 4 | 5 | 4 | **81** |
| H1-12 Retention/evidence baseline | 5 | 3 | 5 | 5 | 4 | 4 | 3 | 5 | 4 | **87** |
| H2-01 CTI screen-pop | 5 | 5 | 5 | 5 | 3 | 3 | 2 | 3 | 3 | **85** |
| H2-02 Case/task handoff | 5 | 5 | 5 | 5 | 3 | 3 | 3 | 4 | 3 | **87** |
| H2-03 Omnichannel intake | 5 | 4 | 4 | 4 | 3 | 3 | 2 | 3 | 2 | **76** |
| H2-04 QA calibration | 5 | 4 | 5 | 5 | 4 | 4 | 3 | 4 | 4 | **89** |
| H2-05 Journey/recontact analytics | 5 | 4 | 5 | 5 | 4 | 4 | 3 | 4 | 4 | **89** |
| H2-06 Coaching action plans | 4 | 5 | 4 | 5 | 4 | 4 | 4 | 5 | 4 | **86** |
| H2-07 Georgian semantic search | 5 | 5 | 5 | 5 | 3 | 3 | 3 | 3 | 3 | **86** |
| H2-08 Grounded agent assist | 5 | 5 | 5 | 5 | 3 | 3 | 3 | 2 | 3 | **85** |
| H2-09 ACW summary draft | 5 | 5 | 3 | 5 | 4 | 4 | 3 | 3 | 3 | **84** |
| H2-10 Approved back-office RPA | 5 | 4 | 4 | 4 | 3 | 3 | 3 | 4 | 3 | **79** |
| H2-11 Skills workforce planning | 5 | 5 | 4 | 4 | 3 | 3 | 3 | 5 | 3 | **83** |
| H2-12 Self-service diagnostics | 5 | 4 | 5 | 5 | 3 | 3 | 2 | 3 | 3 | **82** |
| H3-01 Voice transcript/redaction | 5 | 4 | 5 | 5 | 2 | 2 | 2 | 2 | 2 | **76** |
| H3-02 Next-best-action | 5 | 5 | 4 | 5 | 2 | 2 | 2 | 2 | 2 | **76** |
| H3-03 Proactive recovery | 5 | 5 | 5 | 5 | 2 | 2 | 1 | 3 | 2 | **79** |
| H3-04 Customer digital assistant | 5 | 4 | 4 | 4 | 2 | 2 | 2 | 2 | 2 | **71** |
| H3-05 Contact-center event hub/API | 5 | 4 | 4 | 5 | 3 | 2 | 2 | 4 | 2 | **77** |
| H3-06 Process/task mining | 4 | 3 | 4 | 4 | 3 | 3 | 3 | 4 | 3 | **71** |
| H3-07 Enterprise knowledge graph | 4 | 4 | 4 | 4 | 2 | 2 | 2 | 3 | 2 | **67** |
| H3-08 Predictive capacity | 4 | 4 | 3 | 4 | 2 | 2 | 2 | 4 | 2 | **65** |
| H3-09 Fraud/scam assist | 5 | 4 | 5 | 5 | 2 | 2 | 1 | 2 | 2 | **75** |
| H3-10 Guarded revenue offers | 5 | 4 | 2 | 4 | 3 | 3 | 2 | 3 | 3 | **71** |
| H3-11 Contact theme discovery | 4 | 4 | 4 | 4 | 2 | 2 | 2 | 2 | 2 | **66** |
| H3-12 Safe automation orchestration | 5 | 4 | 5 | 5 | 2 | 2 | 2 | 2 | 1 | **75** |

### 8.2 დეტალური საინიციატივო ბარათები — H1 (0–3 თვე)

#### H1-01 — Production SSO/AD და session assurance

**მომხმარებელი/პრობლემა:** ყველა როლი; production identity ჯერ გარე config-ზეა დამოკიდებული. **სცენარი/ღირებულება:** IdP login → role/scope load → DB session → idle/max expiry → self/administrative revoke; AD unavailable-ზე fail closed. ამცირებს unauthorized access-სა და launch risk-ს. **KPI:** login success, p95 auth latency, failed-closed tests, revoke propagation, orphan sessions = 0. **მონაცემები/ინტეგრაცია:** AD/SSO metadata, stable employee identifier, groups/claims, reverse proxy/TLS, secrets. **Privacy/security:** მინიმალური claims; cookie/CSRF; no offline/local production login. **დამოკიდებულება:** IT/IAM, Security, HR identifier owner. **სირთულე/ღირებულება/TTV:** M / M / 4–8 კვირა. **მოდელი:** build connector + IT partner. **რისკი:** claim drift, clock/key rotation, proxy headers. **MVP/pilot:** 30-user cohort, revoke/expiry/outage drill. **Benchmark/adaptation:** banking controlled access pattern; Magti ინარჩუნებს არსებულ fail-closed კონტრაქტს. **რატომ გავაკეთოთ:** launch prerequisite; **რატომ არა:** მხოლოდ მაშინ შეჩერდეს, თუ corporate IdP contract თავად იცვლება.

#### H1-02 — Wrong-service guardrail და resolution checklist

**მომხმარებელი/პრობლემა:** ოპერატორი; მსგავს სერვისებსა და პოლიტიკებს შორის შეცდომა. **სცენარი/ღირებულება:** contact reason-ის არჩევისას Portal აჩვენებს 3–7 სავალდებულო შემოწმებას, active article/version-ს, disqualifier-ს და escalation-ს; დასრულებისას outcome ფიქსირდება. **KPI:** wrong-service defect rate, FCR, transfer, 7-day recontact, checklist bypass. **მონაცემები/ინტეგრაცია:** reason/outcome taxonomy, active knowledge, later CTI interaction ID. **Privacy:** customer PII პირველ MVP-ში არ ინახება; მხოლოდ pseudonymous interaction ID. **დამოკიდებულება:** Operations, QA, content owners. **სირთულე/ღირებულება/TTV:** M / S–M / 4–6 კვირა. **მოდელი:** build. **რისკი:** ზედმეტი click burden; checklist gaming. **MVP:** top-5 costly reasons ერთ გუნდში, control cohort. **Benchmark/adaptation:** Target process companion/UPS embedded guidance; AI-ის გარეშე. **რატომ:** ყველაზე მაღალი controllable risk reduction; **რატომ არა:** არ გავავრცელოთ baseline-ისა და owner-ის გარეშე.

#### H1-03 — ოპერატორის ერთიანი საწყისი და context launcher

**მომხმარებელი/პრობლემა:** ოპერატორი; dashboard-იდან ხშირ workflow-ზე გადასვლა და ძიება ფრაგმენტულია. **სცენარი/ღირებულება:** shift card, alerts, required readings, recent/favorite content, search და top actions ერთ keyboard-first page-ზე; customer PII-ის გარეშე. **KPI:** task-start time, clicks/contact, search success, required-reading lateness. **მონაცემები:** არსებული Portal data. **ინტეგრაცია:** none H1; CTI deep-link H2. **Privacy:** დაბალი. **დამოკიდებულება:** UX/accessibility, telemetry dictionary. **სირთულე/ღირებულება/TTV:** S / S / 3–5 კვირა. **მოდელი:** build. **რისკი:** dashboard clutter. **MVP:** 3 ყველაზე ხშირი task; usability test 8–12 operator-თან. **Benchmark/adaptation:** Magenta View-ის one-front-end pattern, მაგრამ Magti-ს მცირე modular monolith-ში. **რატომ:** adoption multiplier; **რატომ არა:** არ გადაიქცეს ყველა metric-ის კედლად.

#### H1-04 — Knowledge quality cockpit

**მომხმარებელი/პრობლემა:** content admin/operations manager; ხარისხი reactive-ია. **სცენარი/ღირებულება:** ownerless, expiring, frequently searched, zero-result-linked, low-use, conflicting და overdue-review content-ის action queue. **KPI:** SLA freshness, owner coverage, conflict age, zero-result closure time, article-to-resolution use. **მონაცემები:** articles/news/videos, search logs, audit, required readings. **ინტეგრაცია:** none. **Privacy:** aggregate only. **დამოკიდებულება:** owner model, metric definitions. **სირთულე/ღირებულება/TTV:** M / M / 6–8 კვირა. **მოდელი:** build. **რისკი:** vanity scores ან volume incentives. **MVP:** articles only, top categories. **Benchmark/adaptation:** Schneider/Target knowledge governance; existing common queue stays. **რატომ:** trusted knowledge is all later assist-ის prerequisite; **რატომ არა:** არ შეაფასოს ავტორი მხოლოდ page views-ით.

#### H1-05 — Search telemetry და zero-result governance

**მომხმარებელი/პრობლემა:** ოპერატორი/content owner; ძიების მარცხი უხილავია. **სცენარი/ღირებულება:** query normalization, zero-result/abandon/reformulation/click/result-rank telemetry და weekly correction queue; Georgian synonyms/transliteration dictionary. **KPI:** search success@3, zero-result rate, time-to-click, reformulation, content-gap lead time. **მონაცემები:** search logs, anonymized user role/team, clicked content; არ ჩაიწეროს customer PII. **ინტეგრაცია:** Portal only. **Privacy:** query redaction/denylist, bounded retention. **დამოკიდებულება:** DPO query-log policy, DBA indexing. **სირთულე/ღირებულება/TTV:** M / M / 6–8 კვირა. **მოდელი:** build. **რისკი:** sensitive text queries; unbounded cache/full scan. **MVP:** top 500 query forms; performance budget. **Benchmark/adaptation:** retail search learning loop; ქართულ ტრანსლიტერაციაზე custom layer. **რატომ:** measurable operator benefit; **რატომ არა:** raw query archive policy-ის გარეშე.

#### H1-06 — Knowledge freshness owner/SLA

**მომხმარებელი/პრობლემა:** content owner/compliance; outdated material. **სცენარი/ღირებულება:** risk tier, named owner/delegate, review-by date, pre-expiry reminders, overdue state, replacement link და emergency withdrawal. **KPI:** critical in-SLA %, overdue age, owner coverage, emergency withdrawal time. **მონაცემები:** content metadata/audit. **ინტეგრაცია:** corporate directory later. **Privacy:** low. **დამოკიდებულება:** Operations/Legal define tiers/SLA. **სირთულე/ღირებულება/TTV:** S / S / 3–5 კვირა. **მოდელი:** build. **რისკი:** checkbox reviews. **MVP:** critical policy articles. **Benchmark/adaptation:** ISO-style continual service quality; common queue preserved. **რატომ:** high value/low complexity; **რატომ არა:** არ მოვთხოვოთ ყველა low-risk news-ს მძიმე workflow.

#### H1-07 — Manager insights v1 და dedicated `stats.view`

**მომხმარებელი/პრობლემა:** canonical manager; scoped action view აკლია და stats content permission-ზეა მიბმული. **სცენარი/ღირებულება:** separate permission; team-only aggregate for reading, quiz, workload/search/compliance; acting leadership screen access possible, export არა. **KPI:** access denials, scope leakage = 0, action completion, data freshness. **მონაცემები:** team/leadership, receipts, quiz, activity aggregates. **ინტეგრაცია:** none. **Privacy:** minimum cell-size/suppression where needed; no raw audit. **დამოკიდებულება:** Security, DPO, access matrix update. **სირთულე/ღირებულება/TTV:** M / S–M / 4–6 კვირა. **მოდელი:** build. **რისკი:** performance surveillance misuse. **MVP:** 5 actionable metrics; no ranking. **Benchmark/adaptation:** employee enablement, not scorecard punishment. **რატომ:** closes access seam; **რატომ არა:** არ შექმნას individual league tables.

#### H1-08 — WFM schedule/skills import pilot

**მომხმარებელი/პრობლემა:** workforce planner/manager; schedule/skills data disconnected. **სცენარი/ღირებულება:** read-only CSV/API import → validation → shift/skill coverage view → exceptions; source WFM remains system of record. **KPI:** import validity, coverage gaps found, planner time, schedule mismatch. **მონაცემები:** employee ID, team, shift, skill/certification. **ინტეგრაცია:** WFM/HR export. **Privacy:** purpose limitation; no health/absence reason. **დამოკიდებულება:** HR/WFM owner, DPO, stable IDs. **სირთულე/ღირებულება/TTV:** M / M / 6–10 კვირა. **მოდელი:** partner + build adapter. **რისკი:** stale schedules, duplicate master. **MVP:** one department, weekly import. **Benchmark/adaptation:** import-first avoids replacing WFM. **რატომ:** proves data readiness; **რატომ არა:** no custom forecasting before source quality.

#### H1-09 — Major-incident command/playbook

**მომხმარებელი/პრობლემა:** operators/managers/incident commander; broadcast passive-ია. **სცენარი/ღირებულება:** severity, canonical customer wording, affected services, start/update/resolve timestamps, owner, operator action card, update SLA, post-incident link; company-wide broadcast remains untargeted/passive. **KPI:** detection-to-publish, update SLA, inconsistent-answer defects, incident recontact. **მონაცემები:** incident metadata, no customer PII. **ინტეგრაცია:** NOC incident feed later. **Privacy:** low; security-sensitive details separated. **დამოკიდებულება:** NOC/Operations comms RACI. **სირთულე/ღირებულება/TTV:** S–M / S / 3–5 კვირა. **მოდელი:** build. **რისკი:** conflicting sources/over-broadcast. **MVP:** manual incident commander flow + drill. **Benchmark/adaptation:** airline/service disruption context; existing broadcast extended without targeting. **რატომ:** highest score and immediate risk reduction; **რატომ არა:** no autonomous publish.

#### H1-10 — WCAG 2.2 AA uplift და Georgian accessibility QA

**მომხმარებელი/პრობლემა:** keyboard/screen-reader/low-vision users; evidence incomplete. **სცენარი/ღირებულება:** focus visibility/order, skip links, landmark/name/role/value, error summaries, target size, zoom 200%, reduced motion, Georgian pronunciation checks. **KPI:** critical accessibility defects, keyboard completion, axe/manual pass, user task success. **მონაცემები:** none. **ინტეგრაცია:** CI accessibility checks. **Privacy:** none. **დამოკიდებულება:** UX, QA, representative users. **სირთულე/ღირებულება/TTV:** S–M / S / 4–8 კვირა. **მოდელი:** build + specialist audit. **რისკი:** automated-only false assurance. **MVP:** login, operator home, search, required reading, admin queue. **Benchmark:** [WCAG 2.2](https://www.w3.org/TR/wcag/) recommends testable accessibility criteria. **რატომ:** usability/risk benefit for all; **რატომ არა:** no native mobile scope creep.

#### H1-11 — Passive workload/friction telemetry

**მომხმარებელი/პრობლემა:** operator/manager; overload და workflow friction გვიან ჩანს. **სცენარი/ღირებულება:** aggregate time-in-task, repeated searches, excessive switching, queue pressure, missed break windows; manager sees team signals and process causes, not keystroke surveillance. **KPI:** overload windows, repeated-step rate, task switching, break exception, rework. **მონაცემები:** minimum event metadata; no message content/keystroke capture. **ინტეგრაცია:** WFM later. **Privacy:** employee monitoring policy, aggregation, retention. **დამოკიდებულება:** HR/DPO/employee representatives. **სირთულე/ღირებულება/TTV:** M / S–M / 6–8 კვირა. **მოდელი:** build. **რისკი:** surveillance perception. **MVP:** optically aggregate process telemetry, published metric charter. **Benchmark/adaptation:** wellbeing through process redesign, not a feedback feature. **რატომ:** reveals toil; **რატომ არა:** stop if metrics become individual discipline proxy.

#### H1-12 — Retention, legal-hold და evidence control baseline

**მომხმარებელი/პრობლემა:** DPO/Legal/audit/admin; retention and hold lifecycle incomplete. **სცენარი/ღირებულება:** data-class inventory, purpose, owner, live/archive/erase rule, legal-hold set/release, purge approval, evidence manifest and periodic verification. **KPI:** unknown classes = 0, overdue purge, held record deletion = 0, audit-chain verification. **მონაცემები:** all Portal entities/exports/logs/snapshots. **ინტეგრაცია:** archive/backup systems. **Privacy/security:** core objective; segregation of duties. **დამოკიდებულება:** DPO/Legal/DBA/Security. **სირთულე/ღირებულება/TTV:** M / M / 6–10 კვირა. **მოდელი:** policy + build. **რისკი:** over-retention or evidence deletion. **MVP:** audit, export, deleted user/content evidence and search logs. **Benchmark/adaptation:** IKEA publishes retention; HCA emphasizes protected processing. **რატომ:** unlocks analytics/voice safely; **რატომ არა:** no purge automation before signed schedule.

### 8.3 დეტალური საინიციატივო ბარათები — H2 (3–12 თვე)

#### H2-01 — CTI screen-pop და interaction context

**მომხმარებელი/პრობლემა:** operator; caller/context switching. **სცენარი/ღირებულება:** inbound event → pseudonymous interaction ID → read-only customer/service context → reason/checklist/knowledge → call control remains CTI. **KPI:** handle time, hold/transfer, wrong customer/service selection, screen-pop latency, FCR. **მონაცემები:** ANI/customer/service IDs, consent/recording flags, case history minimum. **ინტეგრაცია:** PBX/ACD/CRM/identity. **Privacy:** screen masking, least data, no local token storage. **დამოკიდებულება:** IT/telephony vendor/DPO. **სირთულე/ღირებულება/TTV:** L / L / 3–6 თვე. **მოდელი:** buy/partner connector + build Portal adapter. **რისკი:** brittle vendor APIs, identity mismatch. **MVP:** read-only pop for one queue; manual fallback. **Benchmark:** Magenta View/Amazon embedded workspace. **Magti adaptation:** Portal remains knowledge/workflow layer, not PBX replacement. **რატომ:** high operator impact; **რატომ არა:** no write-back until identity/latency proven.

#### H2-02 — Case/task handoff workflow

**მომხმარებელი/პრობლემა:** operator/expert/back office; context is re-explained and ownership unclear. **სცენარი/ღირებულება:** structured handoff with reason, checks completed, cited content, customer promise, SLA, owner and status; receiving team accepts/rejects with reason. **KPI:** handoff completeness, bounce rate, SLA, recontact, customer re-explanation. **მონაცემები:** interaction/case IDs, structured summary, no unnecessary transcript. **ინტეგრაცია:** CRM/ticketing. **Privacy:** field-level minimization, scoped access, retention. **დამოკიდებულება:** process owners/API. **სირთულე/ღირებულება/TTV:** L / M–L / 3–5 თვე. **მოდელი:** build workflow or configure bought case platform. **რისკი:** duplicate source of truth. **MVP:** one frequent escalation path. **Benchmark:** Salesforce case routing/Delta handoff. **Adaptation:** Portal stores evidence/reference; case system stays master. **რატომ:** closes journey break; **რატომ არა:** no parallel universal ticket system.

#### H2-03 — Omnichannel intake normalization

**მომხმარებელი/პრობლემა:** operators/managers; phone/chat/email reasons incomparable. **სცენარი/ღირებულება:** adapters normalize channel, interaction ID, reason, timestamp, queue, outcome and handoff into one event contract. **KPI:** classified interactions %, duplicate contacts, channel transfer, data latency. **მონაცემები:** channel metadata; content stored only by approved purpose. **ინტეგრაცია:** telephony/chat/email/CRM. **Privacy:** consent/retention by channel, redaction. **დამოკიდებულება:** enterprise integration and DPO. **სირთულე/ღირებულება/TTV:** L / L / 4–8 თვე. **მოდელი:** buy/partner + canonical event model. **რისკი:** scope explosion. **MVP:** phone + one digital channel, metadata only. **Benchmark:** Microsoft/Salesforce unified channels; IKEA transfer. **Adaptation:** avoid buying full suite until gap/volume economics proven. **რატომ:** enables journey view; **რატომ არა:** not a big-bang omnichannel replacement.

#### H2-04 — QA sampling და calibration cockpit

**მომხმარებელი/პრობლემა:** QA/manager/operator; sampling/calibration inconsistent or external. **სცენარი/ღირებულება:** risk-based sample queue, scorecard versioning, dual score, disagreement, calibration session, appeal and action evidence. **KPI:** calibration variance, sample coverage, defect recurrence, appeal overturn, coaching closure. **მონაცემები:** interaction/case metadata, approved recording link, QA score/evidence. **ინტეგრაცია:** recorder/CRM. **Privacy:** role separation, sensitive-call exclusion, retention. **დამოკიდებულება:** QA/DPO/Legal. **სირთულე/ღირებულება/TTV:** M–L / M / 3–5 თვე. **მოდელი:** build if scorecard-specific; buy comparator if recorder suite bundled. **რისკი:** punitive use/bias. **MVP:** top-2 risk queues, manual sample import. **Benchmark:** Microsoft QA-agent capability is comparator, not auto-score authority. **Adaptation:** human QA remains final. **რატომ:** measurable service quality loop; **რატომ არა:** no opaque 100% AI scoring.

#### H2-05 — Journey, contact-reason და recontact analytics

**მომხმარებელი/პრობლემა:** Operations/Product; AHT/FCR alone hide repeat effort. **სცენარი/ღირებულება:** pseudonymous interaction chain by service/reason/outcome; transfers, repeat contacts, unresolved loops and failure cost. **KPI:** FCR, 24h/7d recontact, transfer chain, customer effort proxy, cost per resolved reason. **მონაცემები:** canonical interaction event, customer/service hash, reason/outcome. **ინტეგრაცია:** CTI/CRM/billing/network status. **Privacy:** pseudonymization, aggregation, access by purpose. **დამოკიდებულება:** data engineering/DPO/business glossary. **სირთულე/ღირებულება/TTV:** M–L / M–L / 3–6 თვე. **მოდელი:** build semantic layer; BI buy/use existing. **რისკი:** identity false match. **MVP:** 10 contact reasons, 7-day window. **Benchmark:** Octopus end-to-end operating layer; NYC311 taxonomy. **Adaptation:** Portal action links, warehouse calculation. **რატომ:** makes portfolio value measurable; **რატომ არა:** no individual productivity surveillance.

#### H2-06 — QA-linked coaching action plans

**მომხმარებელი/პრობლემა:** manager/operator/trainer; QA result does not reliably become skill improvement. **სცენარი/ღირებულება:** defect → root cause (knowledge/process/skill/system) → specific drill/reading/shadowing → due date → reassessment. **KPI:** recurrence, action completion, time-to-competence, post-coaching score. **მონაცემები:** QA result, skill matrix, existing readings/quizzes. **ინტეგრაცია:** HR/LMS optional. **Privacy:** employee record access/retention; no public ranking. **დამოკიდებულება:** HR/Training/QA. **სირთულე/ღირებულება/TTV:** M / M / 2–4 თვე after QA. **მოდელი:** build on required-reading core. **რისკი:** compliance checkbox, manager bias. **MVP:** 3 defect types and 3 learning actions. **Benchmark:** Target coaching + DBS evaluator loop. **Adaptation:** reuse Portal evidence, not feedback feature. **რატომ:** converts measurement to outcome; **რატომ არა:** no automated disciplinary action.

#### H2-07 — Georgian semantic search pilot

**მომხმარებელი/პრობლემა:** operator; typo, morphology and transliteration reduce keyword search recall. **სცენარი/ღირებულება:** hybrid retrieval combines Oracle/text/trigram, curated synonyms and embeddings; returns ranked active content with reason/source; exact search remains fallback. **KPI:** success@3, MRR/nDCG on golden set, latency, zero-result, unsafe/stale retrieval = 0. **მონაცემები:** published content and labeled Georgian queries; no customer data. **ინტეგრაცია:** embedding/search provider or self-hosted service. **Privacy/security:** content classification, no external training, processor contract. **დამოკიდებულება:** 300–500-query golden set, DPO/Security. **სირთულე/ღირებულება/TTV:** M–L / M / 3–4 თვე. **მოდელი:** build retrieval layer + buy model runtime. **რისკი:** Georgian quality/cost. **MVP:** 2 categories, shadow mode. **Benchmark:** Amazon Q/source links; Target narrow knowledge. **Adaptation:** no generated answer yet. **რატომ:** directly attacks findability; **რატომ არა:** no rollout below relevance/latency gates.

#### H2-08 — Grounded agent assist, human-approved

**მომხმარებელი/პრობლემა:** operator; რთული საკითხის პასუხის შედგენა. **სცენარი/ღირებულება:** active scoped sources retrieve → draft answer/checklist → visible citations/version → operator edits/accepts → decision logged; model cannot publish/change customer state. **KPI:** answer accuracy on golden set, citation coverage, edit distance, AHT/FCR, unsafe suggestion rate. **მონაცემები:** approved content + minimal interaction context. **ინტეგრაცია:** CTI/CRM read-only, LLM gateway. **Privacy:** redaction, no provider training, residency/retention, prompt-injection controls. **დამოკიდებულება:** H1 knowledge, H2 semantic search, DPO/Legal/Security. **სირთულე/ღირებულება/TTV:** L / M–L / 4–6 თვე. **მოდელი:** buy runtime/partner + build guardrails/eval. **რისკი:** hallucination/automation bias. **MVP:** 3 low-risk reasons, shadow then opt-in. **Benchmark:** DBS, Schneider, Amazon. **Adaptation:** Georgian citations mandatory. **რატომ:** high potential; **რატომ არა:** no autonomous send/publish.

#### H2-09 — After-call work summary draft

**მომხმარებელი/პრობლემა:** operator; repetitive ACW. **სცენარი/ღირებულება:** approved transcript/structured interaction → required-field draft → operator review/edit/submit; transcript is not copied into Portal by default. **KPI:** ACW time, field completeness, correction rate, downstream reject. **მონაცემები:** transcript or live text, outcome schema. **ინტეგრაცია:** recorder/STT/CRM. **Privacy:** consent, redaction, short processing retention. **დამოკიდებულება:** Legal/DPO, STT Georgian quality, CRM API. **სირთულე/ღირებულება/TTV:** M–L / M / 3–5 თვე. **მოდელი:** buy STT/LLM + build template. **რისკი:** fabricated facts, sensitive text. **MVP:** post-call only, 100% human confirm. **Benchmark:** DBS/HCA draft-finalize pattern. **Adaptation:** bounded fields and reason codes. **რატომ:** narrow measurable automation; **რატომ არა:** no auto-submit.

#### H2-10 — Approved back-office RPA

**მომხმარებელი/პრობლემა:** back office/operator; repetitive copy/check tasks. **სცენარი/ღირებულება:** rules engine/RPA validates input, calls approved API or controlled UI, returns receipt; high-risk action requires two-person/role approval. **KPI:** cycle time, manual touches, exception/failure, rollback, defect. **მონაცემები:** case fields needed for task. **ინტეგრაცია:** billing/CRM/order systems. **Privacy/security:** service account vault, least privilege, full audit, no screen scraping if API exists. **დამოკიდებულება:** system owners/Security. **სირთულე/ღირებულება/TTV:** M–L / M–L / 3–6 თვე. **მოდელი:** buy RPA platform + build automations. **რისკი:** brittle UI automation, hidden business rules. **MVP:** one high-volume/low-risk task. **Benchmark:** DHL intelligent automation. **Adaptation:** approved action catalog. **რატომ:** removes toil; **რატომ არა:** no citizen bots against production without controls.

#### H2-11 — Skills-based workforce planning

**მომხმარებელი/პრობლემა:** planner/manager; staffing ignores detailed skill/certification. **სცენარი/ღირებულება:** demand by reason/time + roster/skills → coverage gaps and reskilling options; planner approves schedule in source WFM. **KPI:** service level, occupancy, overtime, skill-gap hours, schedule stability. **მონაცემები:** volume/handle-time forecast, roster, skill matrix. **ინტეგრაცია:** WFM/HR/CTI. **Privacy:** no health data; fairness review. **დამოკიდებულება:** H1 import, HR, WFM owner. **სირთულე/ღირებულება/TTV:** L / L / 4–8 თვე. **მოდელი:** buy WFM optimization, build Portal view/links. **რისკი:** unfair schedules, bad forecast. **MVP:** one skill family; human planner. **Benchmark:** mature contact-center WFM pattern; vendor proof required. **Adaptation:** ~600-user economics favor buy/configure. **რატომ:** capacity and wellbeing; **რატომ არა:** do not build optimizer from scratch.

#### H2-12 — Guided customer self-service diagnostics

**მომხმარებელი/პრობლემა:** customer/operator; simple diagnostic contacts consume queues. **სცენარი/ღირებულება:** authenticated/verified customer follows rule-based service-specific checks, sees status and can hand off completed steps/context to operator. **KPI:** task completion, assisted-contact reduction, handoff rate, repeat contact, abandonment. **მონაცემები:** service status/account minimum, diagnostic steps/outcomes. **ინტეგრაცია:** customer portal/app, network/billing APIs, CTI. **Privacy/security:** strong auth for account action; anonymous only for generic info. **დამოკიდებულება:** digital channels/IT/product owners. **სირთულე/ღირებულება/TTV:** L / L / 4–8 თვე. **მოდელი:** partner APIs + build journey. **რისკი:** wrong diagnosis/digital exclusion. **MVP:** one reversible, high-volume diagnostic with immediate human handoff. **Benchmark:** Vodafone proactive diagnostics, Delta self-service. **Adaptation:** rule-based before AI assistant. **რატომ:** cost + effort reduction; **რატომ არა:** no dead-end deflection.

### 8.4 დეტალური საინიციატივო ბარათები — H3 (12–36+ თვე)

#### H3-01 — Voice transcription, redaction და conversation analytics

**მომხმარებელი/პრობლემა:** QA/operator/operations; voice interaction is opaque and manual to review. **სცენარი/ღირებულება:** approved recording stream → Georgian STT → PII redaction → searchable restricted transcript/features → QA/theme/ACW use; recording remains source. **KPI:** Georgian WER by scenario, redaction recall, processing latency, sampled accuracy, unauthorized access = 0. **მონაცემები:** voice, consent, speaker/queue metadata. **ინტეგრაცია:** recorder/STT/KMS/QA. **Privacy:** high; consent/lawful basis, residency, retention, sensitive-call exclusion. **დამოკიდებულება:** Legal/DPO/Security/procurement. **სირთულე/ღირებულება/TTV:** XL / XL / 9–18 თვე. **მოდელი:** buy/partner. **რისკი:** Georgian accuracy, biometric/sensitive inference, cost. **MVP:** offline 500-call consented corpus; no production search. **Benchmark:** DBS transcribe-assist; IKEA publishes transcript handling. **Adaptation:** redaction/eval before downstream AI. **რატომ:** unlocks QA and ACW; **რატომ არა:** fail if redaction/accuracy gates missed.

#### H3-02 — Next-best-action, human-approved

**მომხმარებელი/პრობლემა:** operator/customer; contextually best resolution/offer is hard to select. **სცენარი/ღირებულება:** eligibility rules + service context + policy + prior outcomes → ranked action with rationale/constraints → operator approves or rejects. **KPI:** resolution lift, acceptance, complaint/reversal, fairness, incremental margin, wrong-action rate. **მონაცემები:** customer/service eligibility, interaction reason, policy, outcomes. **ინტეგრაცია:** CRM/billing/order/consent. **Privacy/security:** profiling/transparency, purpose limitation, protected/vulnerable exclusions. **დამოკიდებულება:** DPO/Legal/Product/Data. **სირთულე/ღირებულება/TTV:** XL / L–XL / 9–18 თვე. **მოდელი:** build decision policy, buy model/decision engine. **რისკი:** bias, unsuitable offers, automation bias. **MVP:** rules-only retention action for one segment; model in shadow. **Benchmark:** Telefónica/DBS contextual assistance. **Adaptation:** resolution first, revenue second. **რატომ:** value at scale; **რატომ არა:** no autonomous execution.

#### H3-03 — Proactive outage/service recovery

**მომხმარებელი/პრობლემა:** affected customer/operator/NOC; customers learn after failure and queues spike. **სცენარი/ღირებულება:** network event → impacted-service mapping → approved message/action/status → customer digital notice and operator card → resolution confirmation/recovery option. **KPI:** avoidable inbound contacts, publish latency, affected-match precision, repeat contact, recovery completion. **მონაცემები:** network incident, service/customer mapping, contact preference. **ინტეგრაცია:** NOC, CRM, messaging/app, Portal. **Privacy:** messaging consent, minimize incident detail. **დამოკიდებულება:** network data/communications/Legal. **სირთულე/ღირებულება/TTV:** XL / XL / 9–18 თვე. **მოდელი:** partner integration + build orchestration. **რისკი:** false positive, contradictory message. **MVP:** one outage type, employee/internal shadow then opt-in customers. **Benchmark:** Vodafone diagnostics, Delta disruption support. **Adaptation:** incident commander always approves at first. **რატომ:** largest call avoidance/trust opportunity; **რატომ არა:** no event-to-customer autopublish initially.

#### H3-04 — Customer digital assistant with full human handoff

**მომხმარებელი/პრობლემა:** customer; 24/7 simple help and continuity. **სცენარი/ღირებულება:** assistant answers approved generic/account-safe intents, performs only reversible approved tasks, and hands transcript/identity/completed steps to human. **KPI:** contained-and-resolved (not deflection), handoff success, CSAT/customer effort, repeat contact, unsafe response. **მონაცემები:** approved knowledge and consented account context. **ინტეგრაცია:** customer app/web, CRM/CTI, identity, action APIs. **Privacy/security:** AI disclosure, retention notice, prompt injection, auth step-up. **დამოკიდებულება:** H2 self-service/knowledge/event IDs, DPO/Legal. **სირთულე/ღირებულება/TTV:** XL / XL / 12–24 თვე. **მოდელი:** buy/partner + build governed skills. **რისკი:** dead-end deflection, hallucination. **MVP:** 3 generic intents + live handoff. **Benchmark:** Vodafone/IKEA/Delta/DBS. **Adaptation:** Georgian-first, no humanless publishing/decision. **რატომ:** channel convenience; **რატომ არა:** only after handoff and accuracy SLO.

#### H3-05 — Contact-center event hub/API

**მომხმარებელი/პრობლემა:** all products/data teams; point-to-point integrations become brittle. **სცენარი/ღირებულება:** canonical versioned events for interaction started/ended, reason/outcome, case/handoff, knowledge used, incident and recovery; adapters isolate vendors. **KPI:** integration lead time, schema violations, event loss/duplication, consumer lag, change failure. **მონაცემები:** pseudonymous IDs and minimum facts. **ინტეგრაცია:** CTI/CRM/WFM/NOC/Portal/warehouse. **Privacy/security:** schema classification, ACL, encryption, retention. **დამოკიდებულება:** enterprise architecture/data platform. **სირთულე/ღირებულება/TTV:** L–XL / L / 9–15 თვე. **მოდელი:** build contracts on managed/bought broker. **რისკი:** platform-before-use-cases. **MVP:** three events powering journey analytics. **Benchmark:** omnichannel/contact-center suite patterns. **Adaptation:** not microservices; event seam around modular monolith. **რატომ:** avoids vendor lock-in; **რატომ არა:** no enterprise bus without 2+ consumers.

#### H3-06 — Process/task mining

**მომხმარებელი/პრობლემა:** transformation/operations; hidden rework and loops. **სცენარი/ღირებულება:** approved event logs reconstruct process variants, bottlenecks and repeats; team validates root cause and selects simplification/RPA. **KPI:** rework loops, path variants, waiting time, automation value realized. **მონაცემები:** event timestamps/IDs/outcomes; no screen recording by default. **ინტეგრაცია:** Portal/CRM/RPA data. **Privacy:** employee monitoring limits, aggregation, purpose charter. **დამოკიდებულება:** H2 event taxonomy, HR/DPO. **სირთულე/ღირებულება/TTV:** L / L / 6–12 თვე. **მოდელი:** buy process-mining tool + internal analysis. **რისკი:** correlation mistaken for cause. **MVP:** one case process, workshop validation. **Benchmark:** cross-industry operational optimization. **Adaptation:** process improvement, not individual ranking. **რატომ:** finds non-AI value; **რატომ არა:** no desktop surveillance suite by default.

#### H3-07 — Enterprise knowledge graph

**მომხმარებელი/პრობლემა:** operator/content/data/AI; relationships across service, policy, incident, eligibility and action are implicit. **სცენარი/ღირებულება:** governed entities/relations with provenance/effective dates connect approved content and systems; search/assist use graph filters. **KPI:** coverage, orphan/conflict rate, provenance, retrieval accuracy, update latency. **მონაცემები:** taxonomy, content, service/product/policy/action metadata. **ინტეგრაცია:** catalog/CRM/NOC/search. **Privacy:** keep customer instances out initially; access by classification. **დამოკიდებულება:** ontology owner/data governance. **სირთულე/ღირებულება/TTV:** XL / L–XL / 12–24 თვე. **მოდელი:** build model; buy graph/search infrastructure. **რისკი:** ontology program with no use case. **MVP:** service→issue→article→action for one domain. **Benchmark:** contextual platforms at telecom/retail. **Adaptation:** start as relational metadata if enough. **რატომ:** improves explainability; **რატომ არა:** stop if hybrid search meets needs cheaper.

#### H3-08 — Predictive capacity/absence scenarios

**მომხმარებელი/პრობლემა:** WFM planner; volume/absence uncertainty. **სცენარი/ღირებულება:** time-series + events create forecast bands and staffing scenarios; planner selects, never automatic schedule. **KPI:** forecast error, service level, overtime, schedule changes, fairness. **მონაცემები:** historical volume/AHT, calendar/incidents, anonymized absence counts. **ინტეგრაცია:** CTI/WFM/NOC. **Privacy:** no individual health inference. **დამოკიდებულება:** 12+ months quality history/WFM. **სირთულე/ღირებულება/TTV:** L / L / 6–12 თვე after data. **მოდელი:** buy WFM/forecast, validate locally. **რისკი:** structural shifts, self-fulfilling cuts. **MVP:** shadow forecast vs existing planner. **Benchmark:** mature WFM practice. **Adaptation:** confidence intervals and override reason. **რატომ:** capacity/cost; **რატომ არა:** no individual absence prediction.

#### H3-09 — Fraud/scam signal assist

**მომხმარებელი/პრობლემა:** operator/security/customer; social-engineering patterns are missed. **სცენარი/ღირებულება:** interaction/device/account signals + approved rules/model flag risk, show safe script/step-up/escalation; operator/security decides. **KPI:** confirmed scam detection, false positive, prevented loss, escalation time, customer friction. **მონაცემები:** interaction/account/security signals. **ინტეგრაცია:** fraud/SIEM/CRM/identity. **Privacy/security:** high-risk profiling; strict access, explainability, retention. **დამოკიდებულება:** Security/Fraud/Legal/DPO. **სირთულე/ღირებულება/TTV:** XL / XL / 12–24 თვე. **მოდელი:** buy/partner; build workflow. **რისკი:** false accusation/bias/adversarial evasion. **MVP:** rules and known scam scripts, shadow model. **Benchmark:** banking trust/governance patterns. **Adaptation:** assistance only, no account block from Portal. **რატომ:** protects customer/trust; **რატომ არა:** no AI-only adverse action.

#### H3-10 — Guarded revenue/retention offers

**მომხმარებელი/პრობლემა:** operator/customer/commercial; relevant offer timing inconsistent. **სცენარი/ღირებულება:** eligible catalog + service outcome + consent → 1–3 compliant offers with reason/price/terms; resolution workflow completes first; acceptance writes via approved API. **KPI:** incremental conversion/margin, complaint/cancel, suitability, handle-time impact. **მონაცემები:** eligibility, consent, product, interaction outcome. **ინტეგრაცია:** CRM/product catalog/order. **Privacy:** profiling/transparency; vulnerable/customer-exclusion rules. **დამოკიდებულება:** Commercial/Legal/DPO. **სირთულე/ღირებულება/TTV:** L / L / 6–12 თვე. **მოდელი:** build rules first, buy decisioning if scale. **რისკი:** trust erosion/mis-selling. **MVP:** rules-only opt-in retention offer in one reason. **Benchmark:** Deutsche Telekom app monetization and contextual recommendations. **Adaptation:** wrong-service/FCR guardrail outranks sales. **რატომ:** revenue option; **რატომ არა:** stop if complaints or FCR worsen.

#### H3-11 — Voice-of-contact theme discovery

**მომხმარებელი/პრობლემა:** Product/Operations; emerging issues hidden across contacts. **სცენარი/ღირებულება:** redacted transcript/case summaries cluster themes, attach exemplars, trend/anomaly; analyst names/validates theme and opens action. **KPI:** precision of validated themes, lead time to detect, action closure, recurrence. **მონაცემები:** redacted text/reason/outcome. **ინტეგრაცია:** H3 voice/H2 omnichannel/BI. **Privacy:** de-identification, minimum exemplars, restricted access. **დამოკიდებულება:** data quality/analyst capacity/DPO. **სირთულე/ღირებულება/TTV:** L / M–L / 6–12 თვე. **მოდელი:** buy ML runtime + build validation/action loop. **რისკი:** spurious clusters/sensitive inference. **MVP:** weekly batch, 10k redacted records, human labeling. **Benchmark:** Teleperformance interaction analytics; BBVA survey analysis pattern. **Adaptation:** this is operational contact analysis, not prohibited feedback feature. **რატომ:** early-warning learning; **რატომ არა:** no automated product decision.

#### H3-12 — Safe automation orchestration

**მომხმარებელი/პრობლემა:** operator/back office/admin; multiple automated actions need policy/compensation. **სცენარი/ღირებულება:** case state machine coordinates API/RPA/AI drafts, explicit approvals, idempotency, timeouts, rollback/compensation and evidence. **KPI:** straight-through safe completion, exception/rollback, duplicate action = 0, manual touches, audit completeness. **მონაცემები:** case/action state and receipts. **ინტეგრაცია:** CRM/billing/order/RPA/AI gateway. **Privacy/security:** service identity, action allowlist, dual control, least privilege. **დამოკიდებულება:** stable APIs/process ownership/SRE. **სირთულე/ღირებულება/TTV:** XL / XL / 12–24 თვე. **მოდელი:** buy workflow/orchestration runtime + build policy modules. **რისკი:** cascading errors/support complexity. **MVP:** one reversible multi-step workflow with chaos/rollback tests. **Benchmark:** Microsoft case lifecycle/DHL automation; human oversight preserved. **Adaptation:** modular-monolith saga/state machine before distributed microservices. **რატომ:** scale automation safely; **რატომ არა:** no generalized platform before 3 proven workflows.

---

## 9. H1 / H2 / H3 პორტფელი

| ჰორიზონტი | საინვესტიციო თეზისი | 12 shortlist initiative | exit criteria |
|---|---|---|---|
| **H1 — 0–3 თვე** | „უსაფრთხო launch + მართვადი ცოდნა + გაზომვადი სამუშაო“ | SSO/session; wrong-service guardrail; operator home; knowledge cockpit; search telemetry; freshness SLA; manager insights/`stats.view`; WFM import; incident playbook; WCAG; workload telemetry; retention/evidence | SSO drill passed; no critical scope leak; top-reason baseline; incident drill; critical content owner/SLA; signed retention controls |
| **H2 — 3–12 თვე** | „interaction-to-outcome loop“ | CTI; case handoff; omnichannel metadata; QA; journey analytics; coaching; semantic search; grounded assist; ACW draft; RPA; skill planning; self-service diagnostics | canonical interaction ID; CTI/CRM latency/SLO; recontact view; QA calibration; 2 pilots with verified benefit and no guardrail breach |
| **H3 — 12–36+ თვე** | „proactive, assisted, cross-channel operation“ | voice/redaction; next-best-action; proactive recovery; digital assistant; event hub; process mining; knowledge graph; predictive capacity; fraud assist; guarded offers; theme discovery; safe orchestration | Georgian STT/redaction gates; DPO/Legal approval; human handoff SLO; rollback/compensation proof; measurable incremental value |

### Portfolio dependencies

`H1 identity/scope/retention/knowledge → H2 interaction/QA/journey → H2 AI pilots → H3 voice/proactive/customer automation`.

საგანგებო exception: CTI discovery შეიძლება H1-ში დაიწყოს, მაგრამ production write-back არა. ასევე WFM import შეიძლება ცალკე წავიდეს, თუ stable employee IDs უკვე მზადაა.

### Capacity allocation recommendation

- H1: 60% platform/product engineering; 20% production integration/SRE/DBA; 10% UX/accessibility; 10% change/data governance.
- H2: 35% workflow/product; 25% integration/data; 15% QA/analytics; 15% pilots/AI/RPA; 10% change/security.
- H3: 25% core/product; 30% data/integration/event; 25% voice/AI/automation; 10% SRE/security; 10% adoption/value realization.

---

## 10. სამი საინვესტიციო სცენარი

### სცენარი A — კონსერვატიული „Trusted Portal“

**მიზანი:** უსაფრთხო launch, knowledge/compliance excellence და მცირე integration risk.  
**პორტფელი:** ყველა კრიტიკული H1; H2-დან QA/coaching, journey metadata და ერთი read-only CTI pilot; H3 მხოლოდ discovery.  
**12 თვე:** production SSO/cutover; wrong-service/search/knowledge/incident; `stats.view`; QA v1; WFM import.  
**24 თვე:** CTI read-only, case handoff ერთ პროცესში, rules-based self-service; one RPA.  
**36 თვე:** selective semantic search/ACW pilot მხოლოდ დადასტურებული data readiness-ით.

**საჭირო capability/team:** core product owner, program/delivery lead, tech lead, 3–4 full-stack engineer, QA automation, part-time UX/data/SRE/DBA/Security/DPO — დაახლოებით 6–9 core/FTE-equivalent. **ინფრასტრუქტურა:** არსებული modular monolith + Oracle; observability/BI; no event platform or GPU. **შედარებითი ღირებულება:** **1.0×**. **მოსალოდნელი ეფექტი:** launch risk, knowledge freshness, search და compliance მნიშვნელოვნად უმჯობესდება; FCR/AHT effect საშუალოა. **ძირითადი რისკი:** Portal დარჩება CTI/CRM-ის გვერდით და swivel-chair ბოლომდე არ ქრება.

**Stop/go:** GO თუ H1 KPI-ები 90 დღეში იკეტება და adoption ≥70% target tasks-ზე; STOP/reshape თუ SSO/cutover ან scope assurance ვერ სრულდება. **როდის ავირჩიოთ:** როცა budget/integration capacity შეზღუდულია ან telephony roadmap გაურკვეველია.

### სცენარი B — ტრანსფორმაციული „Connected Service Operating System“ — რეკომენდებული

**მიზანი:** Portal გადაიქცეს operator knowledge/workflow/quality layer-ად, რომელიც CTI/CRM/WFM/NOC-ს API-ებით უკავშირდება და interaction-to-outcome learning loop-ს ქმნის.  
**პორტფელი:** ყველა H1; H2-ის 12-ვე, ეტაპობრივი gates-ით; H3-დან event hub, proactive recovery discovery, voice/redaction pilot და safe orchestration ერთი use case-ით.  
**12 თვე:** H1 foundation + CTI read-only + reason/outcome taxonomy + QA/coaching + journey analytics + semantic/agent-assist shadow pilot + one RPA/self-service pilot.  
**24 თვე:** CTI/case handoff production, phone+ერთი digital metadata, skill planning, ACW draft, selected self-service, event contracts; voice corpus/redaction pilot.  
**36 თვე:** proactive recovery ერთ service domain-ში, guarded next-best-action, customer assistant limited intents, safe orchestration — მხოლოდ gate-გავლილი capabilities.

**საჭირო capability/team:** product director/PO, transformation program lead, enterprise/solution architect, 5–7 full-stack/integration engineer, QA/quality lead, UX/accessibility, data engineer + analyst, change/training lead; shared SRE/DBA/Security/DPO/Legal/procurement — დაახლოებით 11–16 core/FTE-equivalent. **ინფრასტრუქტურა:** current monolith/Oracle; API gateway/integration adapters; metrics/warehouse/BI; managed queue/broker მხოლოდ 2+ consumer-ზე; AI gateway/vector/hybrid retrieval sandbox; secrets/KMS/observability. **შედარებითი ღირებულება:** **2.0–3.0×** სცენარი A. **მოსალოდნელი ეფექტი:** მაღალი wrong-service/FCR/AHT/QA/adoption გავლენა და საფუძველი proactive care-სთვის. **ძირითადი რისკი:** cross-system identity/data quality, vendor/API lead time და change fatigue.

**Stop/go:** 6 თვეზე GO, თუ interaction ID coverage ≥90% pilot-ში, screen-pop p95 შეთანხმებულ SLO-შია, scope leaks = 0 და first two pilots verified value-ს აჩვენებს. 12 თვეზე scale მხოლოდ მაშინ, თუ FCR/recontact ერთად უმჯობესდება და operator burden არ იზრდება. **როდის ავირჩიოთ:** ახლა — ის უკეთ აბალანსებს ღირებულებას, feasibility-სა და Magti-ს ზომას.

### სცენარი C — მოწინავე „Proactive AI Service Network“

**მიზანი:** real-time voice/data/AI orchestration, proactive recovery, cross-channel assistant და human-governed next-best-actions.  
**პორტფელი:** H1/H2 სრული მასშტაბი + H3-ის უმეტესობა; knowledge graph და process mining მხოლოდ use-case gates-ით.  
**12 თვე:** B-ის foundation დაჩქარებული; voice/STT vendor bake-off; AI registry/evaluation factory; event platform.  
**24 თვე:** redacted conversation analytics, ACW/agent assist ფართო rollout, proactive incident pilot, customer assistant limited tasks.  
**36 თვე:** multi-domain proactive recovery, guarded next-best-action/revenue, fraud assist, safe automation orchestration and selective agentic workflows.

**საჭირო capability/team:** B-ის გუნდი + ML/LLMOps lead, 2–3 ML/data engineer, conversation designer/Georgian linguist, model-risk/AI assurance, platform/SRE და vendor management — დაახლოებით 18–26 core/FTE-equivalent. **ინფრასტრუქტურა:** streaming/event, feature/analytics store, STT/redaction, AI gateway/model registry/eval, KMS/DLP, high-grade observability and cost controls. **შედარებითი ღირებულება:** **4.0–6.0×** სცენარი A. **მოსალოდნელი ეფექტი:** პოტენციურად უმაღლესი cost-to-serve/customer effort/revenue ეფექტი. **ძირითადი რისკი:** privacy, Georgian model quality, vendor lock-in, operational complexity, premature platform investment.

**Stop/go:** STOP თუ voice redaction recall ან unsafe-response threshold ვერ აღწევს წინასწარ დამტკიცებულ ზღვარს; customer automation არ იწყება human handoff SLO-ის გარეშე; თითოეული AI skill scale-ზე გადადის მხოლოდ incremental value + fairness + security review-ით. **როდის ავირჩიოთ:** მხოლოდ B-ის 12-თვიანი evidence-ის შემდეგ და ცალკე investment committee approval-ით.

### სცენარების შედარება

| განზომილება | A Trusted Portal | B Connected Service OS | C Proactive AI Network |
|---|---|---|---|
| ღირებულება | 1.0× | 2–3× | 4–6× |
| 12-თვიანი risk | დაბალი–საშუალო | საშუალო | მაღალი |
| operator impact | საშუალო | მაღალი | მაღალი |
| wrong-service reduction | საშუალო–მაღალი | მაღალი | მაღალი, მაგრამ model risk-ით |
| integration breadth | დაბალი | საშუალო–მაღალი | ძალიან მაღალი |
| time to first value | 4–8 კვირა | 4–8 კვირა foundation; 4–6 თვე integration | foundation სწრაფი, frontier value 9–18 თვე |
| vendor dependency | დაბალი | საშუალო | მაღალი |
| რეკომენდაცია | fallback | **აირჩიეთ** | option value; ჯერ არა full commitment |

---

## 11. 12/24/36-თვიანი roadmap

### 0–3 თვე — launch/control tranche

1. Production SSO/AD cohort, fail-closed/revoke/expiry drills; proxy/TLS/security evidence.
2. `stats.view` contract, manager/acting/export regression and all endpoint scope audit.
3. Wrong-service reason/outcome taxonomy + top-5 checklist pilot baseline.
4. Incident command flow + tabletop/live drill.
5. Knowledge owner/SLA, quality cockpit/search telemetry MVP; bound cache/query performance.
6. Retention/legal-hold policy and data-class inventory; file object-entitlement verification.
7. WCAG 2.2 AA critical-flow audit/fixes.
8. DBA backup/restore/RPO/RTO and deploy/rollback runbook; health status semantics.

### 4–6 თვე — connect and measure

1. CTI read-only screen-pop and canonical interaction ID in one queue.
2. Journey/recontact semantic model for ten reasons.
3. QA scorecard/version/sample/calibration MVP.
4. WFM weekly import and skills coverage.
5. One structured case handoff and one RPA candidate discovery.
6. Georgian search golden set and hybrid retrieval shadow benchmark.

### 7–12 თვე — prove and scale

1. CTI pilot decision; selected production read-only/limited write-back.
2. QA-linked coaching; manager action queue.
3. One low-risk RPA and one guided self-service diagnostic.
4. Semantic search production if relevance/latency pass; grounded agent assist shadow/opt-in.
5. ACW draft discovery with approved transcript source.
6. Phone + one digital channel metadata normalization.
7. Quarterly value-realization review: FCR + recontact + quality + workload jointly.

### 13–24 თვე — operating-system layer

1. Expand CTI/case handoff/QA/WFM by queue based on benefits.
2. Event contracts/broker only where at least two consumers exist.
3. Voice/STT/redaction offline corpus and vendor bake-off.
4. ACW draft production for bounded workflows.
5. Proactive outage impact-mapping shadow pilot.
6. Process-mining one case journey; eliminate two rework loops before new automation.
7. Digital assistant discovery only after human-handoff readiness.

### 25–36+ თვე — selective frontier

1. Proactive recovery one service domain; operator/customer messaging with approvals.
2. Customer assistant 3–5 intents with full handoff and step-up auth.
3. Guarded next-best-action/retention offer, rules-first.
4. Fraud/scam assist shadow mode.
5. Safe orchestration for one reversible multi-step process.
6. Knowledge graph only if measured hybrid-search/context gaps justify it.
7. Annual architecture decision: continue modular monolith; split only proven scaling/ownership boundary.

### Release gates across all waves

- **Security gate:** threat model, least privilege, audit, secret/key rotation, rollback.
- **Data gate:** named owner, lineage, quality SLO, retention and purpose.
- **Service gate:** baseline/control cohort; FCR and recontact jointly; no hidden failure.
- **Human gate:** training, override, fallback and workload impact.
- **AI gate:** golden set, citations, unsafe tests, redaction, processor terms, human approval.
- **Operations gate:** runbook, alerts, on-call/support owner, capacity/cost budget.

---

## 12. Top 10 რეკომენდაცია

| Rank | ინიციატივა | ქულა | რატომ ახლა | 90-დღიანი deliverable |
|---:|---|---:|---|---|
| 1 | H1-09 Major-incident playbook | 98 | მაღალი wrong-service/queue-spike რისკი, არსებული broadcast core | severity/owner/update SLA + drill + post-incident action |
| 2 | H1-02 Wrong-service guardrail | 96 | პირდაპირი მიზნობრივი ღირებულება | top-5 reasons, checklist, baseline/control |
| 3 | H1-01 SSO/session assurance | 93 | production launch prerequisite | 30-user IdP pilot + revoke/outage drill |
| 4 | H1-06 Freshness owner/SLA | 93 | low complexity, AI/search prerequisite | critical content owner/review SLA |
| 5 | H1-03 Operator unified home | 91 | immediate adoption/time benefit | top tasks keyboard-first landing |
| 6 | H1-05 Search telemetry | 91 | რეალური content gaps ჩანს | success@3/zero-result loop + privacy rules |
| 7 | H2-04 QA calibration | 89 | quality loop measurement | one scorecard, dual score, calibration |
| 8 | H2-05 Journey/recontact analytics | 89 | FCR/AHT gaming-ის საწინააღმდეგო | 10 reasons, 7-day chain |
| 9 | H1-04 Knowledge cockpit | 88 | owner/SLA/action view | ownerless/expired/conflict queue |
| 10 | H1-12 Retention/evidence baseline | 87 | unlocks export/voice/AI safely | signed data classes + legal hold MVP |

**შემდეგი რიგი:** H2-02 case handoff (87), H1-10 accessibility (87), H2-06 coaching (86), H2-07 semantic search (86), H2-01/H2-08 (85).

---

## 13. საპილოტე პროგრამები

| Pilot | ხანგრძლივობა/cohort | primary metric | guardrail/stop | scale decision |
|---|---|---|---|---|
| P1 Wrong-service | 6 კვირა; 25–40 ოპერატორი; 5 reasons; matched control | verified defect + FCR | clicks/contact +15%-ზე მეტი ან recontact ზრდა ⇒ redesign | ≥15% defect reduction და no guardrail breach |
| P2 Search/freshness | 8 კვირა; 2 category; 300–500 golden queries | success@3, zero-result, time-to-answer | stale/unauthorized retrieval >0 ⇒ stop | relevance/latency SLO + owner closure loop |
| P3 CTI read-only | 8–12 კვირა; 1 queue | screen-pop latency, handle time, identity match | mismatch/security incident/manual fallback failure ⇒ stop | ≥90% matched interactions, no scope leak |
| P4 QA/coaching | 12 კვირა; 2 teams; dual scoring | calibration variance, recurrence | punitive use/appeal issue/privacy breach ⇒ stop | variance/recurrence reduction + manager adoption |
| P5 RPA | 8–12 კვირა; one low-risk task | cycle time/manual touches/defect | unrecoverable error or brittle change rate ⇒ stop | positive verified hours/quality, rollback proven |
| P6 Grounded assist | 12 კვირა; shadow 4, opt-in 8; 20–30 operators | accuracy/citation/edit distance/FCR | critical unsafe response, stale source or privacy breach ⇒ stop | threshold signed by QA/DPO/Security; measured net value |
| P7 Proactive incident | 12–16 კვირა; one incident type | avoidable contacts and match precision | false customer targeting/contradictory message ⇒ stop | incident commander approval + benefit/precision gate |

### Pilot measurement standard

ყველა pilot-ს სჭირდება: pre-registered hypothesis; 4-კვირიანი baseline; comparable control ან stepped-wedge rollout; instrumentation before feature; adoption and workload measure; privacy/security approval; incident log; exit report with raw numerator/denominator; benefit owner sign-off. Vendor-produced ROI მარტო საკმარისი არ არის.

---

## 14. Build / Buy / Partner რეკომენდაცია

| Capability | რეკომენდაცია | მიზეზი | კომერციული/ტექნიკური gate |
|---|---|---|---|
| Portal domain workflow, role/scope, knowledge/compliance, evidence | **Build** | Magti-specific policy და უკვე არსებული ძლიერი core | contract tests, migration safety, modular interfaces |
| `stats.view`, manager action views, search telemetry/freshness | **Build** | მცირე scale, local semantics, direct fit | metric dictionary, access tests, query performance |
| CTI/PBX/ACD | **Buy/Partner connector** | telephony control/availability mature vendor domain-ია | open API/events, HA, Georgian support, exit rights |
| CRM/case management | **Configure/Integrate; build thin workflow only** | თავიდან CRM-ის აშენება ძვირია | source-of-truth decision, bi-directional API, identity mapping |
| WFM forecast/optimizer | **Buy** | complex optimization and schedule rules | import/export portability, fairness, no lock-in on employee data |
| QA calibration/coaching layer | **Build on Portal; compare bundled suite** | Magti scorecard + existing learning evidence | recorder link/data export and human final authority |
| BI/analytics visualization | **Use enterprise BI/Buy; build semantic model** | report tooling commodity-ა; semantics Magti-specific | row-level security, lineage, export control |
| RPA runtime | **Buy; build governed automations** | credentials, queues, retries, orchestration mature tooling | vault, audit, API-first, rollback, licence economics |
| Georgian semantic retrieval | **Hybrid:** build eval/retrieval policy, buy runtime | domain/golden set local; model infra commodity | offline eval, no-training clause, latency/cost, portability |
| LLM agent assist | **Partner/Buy runtime; build guardrails/UI/evaluation** | avoid model hosting burden; retain product control | citations, redaction, model/provider switch, audit, residency |
| Speech-to-text/redaction | **Competitive buy/partner** | Georgian accuracy and acoustic tuning specialist work | blinded corpus bake-off, WER/redaction, pricing, data terms |
| Event broker/API gateway | **Managed/buy infrastructure; build contracts/adapters** | infrastructure commodity; canonical events strategic | 2+ consumers, schema registry, replay/idempotency |
| Knowledge graph | **Defer; build model only after use case** | relational metadata/hybrid search may be enough | proven retrieval/context gap and two consuming use cases |
| Native mobile | **Do not build now** | desktop primary; no justified field/offline job | separate business case only |

### Vendor scorecard

Weighted evaluation: functional fit 20%; integration/API/data portability 15%; security/privacy/residency 15%; Georgian quality 15%; reliability/support 10%; total cost 10%; implementation partner capability 5%; observability/audit 5%; exit/lock-in 5%. AI/voice procurement additionally requires custom golden-set bake-off; generic English demo receives no quality credit.

### Contract clauses that are non-negotiable

No training on Magti data; subprocessor disclosure; data location/retention/deletion certificate; breach notification; audit evidence; model/version change notice; export/API portability; availability/support SLO; price protection/cost caps; prompt/transcript ownership; customer data separation; termination assistance.

---

## 15. Data, უსაფრთხოება, privacy და AI governance

### 15.1 Canonical data contracts

| Domain | system of record | Portal role | მინიმალური ID/field |
|---|---|---|---|
| employee identity/status | AD/HR | authenticate + scoped cache/reference | immutable employee ID, status, role/team references |
| organisation/team/leadership | agreed HR/org source; Portal during transition | enforce scope | department/group/team/assignment/effective dates |
| customer/service/account | CRM/billing | read minimum context/deep link | pseudonymous customer ID, service ID, eligibility/status |
| interaction | CTI/CRM/event layer | workflow/knowledge/evidence reference | interaction ID, channel, queue, reason, outcome, timestamps |
| case/handoff | CRM/ticketing | create/reference/action evidence | case ID, owner, SLA, status, required structured fields |
| knowledge/compliance | Portal | source of truth | content/version/owner/effective/review/audience/evidence |
| recording/transcript | recorder/voice platform | restricted link/features; not duplicate default | recording ID, consent, redaction status, retention class |
| analytics | governed warehouse/semantic layer | scoped action views | pseudonymous facts/aggregates/quality timestamps |

### 15.2 Data-quality SLO-ები

- Identity/team/leadership: active employee mapping completeness ≥99.9%; unresolved conflict blocks privilege expansion.
- Interaction: unique ID coverage ≥95% production channel by channel; duplicate rate <0.1%.
- Reason/outcome: ≥90% valid classification in pilot, with „other“ weekly governance.
- Knowledge: critical owner/review coverage ≥95%; expired active content = 0.
- Analytics: freshness/lineage visible; stale dashboard clearly marked and not used for adverse decisions.
- AI corpus: only active/approved/versioned sources; deletion/withdrawal propagated within defined SLO.

### 15.3 Access, privacy and evidence principles

1. Production auth remains SSO/AD only; IdP unavailable ⇒ fail closed. Break-glass is an IT-controlled recovery procedure, not offline Portal login.
2. Acting manager may see explicitly approved scoped screens but cannot export. Primary manager export remains separate, allowlisted and audited.
3. Raw audit is visible only to system admin. Other roles receive purpose-built aggregates/evidence, not raw log access.
4. At least one active system admin is mandatory and protected against removal/deactivation that would leave zero.
5. Deleted content/user evidence persists according to signed policy; purge never deletes held evidence.
6. Queries/transcripts/interaction text are high-risk: minimize, redact, purpose-limit, restrict and expire.
7. Employee analytics improve process/workload; no opaque individual rank, keystroke monitoring or automated discipline.
8. Export data uses field allowlist, scope, reason, TTL, watermark where needed and download audit.

### 15.4 AI control stack

`use-case registry → risk tier → approved data/source scope → redaction → model/provider allowlist → prompt/tool policy → offline golden-set eval → adversarial/security eval → shadow mode → human approval → production monitoring → incident/rollback → periodic revalidation`.

Minimum AI telemetry: model/version, prompt-template version (not necessarily raw prompt), retrieved source/version, tool/action request, human accept/edit/reject, latency/cost, safety category, outcome. Sensitive raw input should not be copied into generic logs.

NIST’s [AI RMF and GenAI profile](https://www.nist.gov/itl/ai-risk-management-framework) provides a useful govern/map/measure/manage frame. This is a governance reference, not a claim of regulatory applicability in Georgia. Information-security management should align with enterprise ISMS and [ISO/IEC 27001](https://www.iso.org/standard/27001); contact-center KPI/service design can reference [ISO 18295-1](https://www.iso.org/standard/64739.html), noting ISO already lists a revision project.

### 15.5 Threat scenarios to test

- prompt injection in article/customer text; stale/withdrawn source retrieval; cross-team scope leak;
- forged proxy headers/cookie theft/CSRF; session replay after revoke; IdP claim drift;
- malicious upload/HTML/link; file ID enumeration/object entitlement bypass;
- export replay after TTL; hidden sensitive column; acting-manager scope confusion;
- RPA/service account privilege escalation; duplicate non-idempotent action; rollback failure;
- voice transcript PII leak; redaction miss; model/provider training or retention beyond contract;
- audit chain break, archive omission or legal-hold release without dual control.

---

## 16. IT / DBA / DPO / Legal და სხვა dependencies

| Owner | საჭირო გადაწყვეტილება/მიწოდება | ბოლო მისაღები დრო | blocker/gate |
|---|---|---|---|
| IT/IAM | SSO protocol, metadata, claims, immutable employee ID, certificates/rotation, outage/support | H1 week 2–4 | production login/cutover |
| Network/Infrastructure | TLS/proxy headers, network zones, DNS, monitoring, load balancer, egress policy | H1 | security/availability |
| DBA | Oracle topology, backup/restore, RPO/RTO, capacity/index plan, Flyway/change window, archive/purge jobs | H1 | launch and analytics scale |
| Security/SOC | threat model, SIEM/audit alerts, key/secrets, vulnerability/penetration test, privileged-access review | each release | security gate |
| DPO | data map/purpose/lawful basis, employee monitoring, search logs, exports, recording/transcript/AI processors | H1 for core; before each pilot | privacy gate |
| Legal/Compliance | retention/legal hold, call notice/consent, AI disclosure, vendor clauses, adverse-action boundaries | H1/H2 before relevant scope | legal gate |
| Telephony owner/vendor | CTI events/API, interaction ID, recorder/consent flags, latency/HA/sandbox | discovery by month 2 | CTI/voice |
| CRM/Billing/NOC owners | source-of-truth fields, read/write APIs, rate limits, event/incident contracts, rollback | H2 | journey/proactive |
| HR/WFM | employee/team/skill/schedule source, fairness/monitoring policy | H1 pilot/H2 scale | WFM/coaching |
| Operations/QA/Training | reason/outcome taxonomy, scorecards, knowledge owners/SLA, pilot cohorts/change plan | immediately | value/adoption |
| Procurement/Finance | RFP/bake-off, TCO, exit rights, benefit tracking | H2/H3 | buy/partner |

### RACI simplification

- **Accountable:** Contact Center executive sponsor for outcomes; CIO/IT for production platform; DPO/Legal for their control gates.
- **Responsible:** Product/Program lead coordinates; domain owners deliver taxonomy/process/knowledge; engineering/data implement.
- **Consulted:** QA, Security, DBA, HR, vendors and representative operators.
- **Informed:** leadership and affected workforce through release/training communications.

### External dependencies that must remain explicitly unverified until evidence exists

Real SSO/AD metadata/claims; production Oracle HA/backup/restore results; corporate telephony/CRM/WFM/NOC API details; exact call recording notices/consents; Georgian STT/LLM quality; vendor data residency/subprocessors; manager export final allowlist; live/archive/purge durations approved by DPO/Legal.

---

## 17. რა არ უნდა ავაშენოთ ახლა

1. **Offline/local production login** — ეწინააღმდეგება SSO-only/fail-closed გადაწყვეტილებას.
2. **Feedback feature-ის დაბრუნება** — დახურული გადაწყვეტილებაა; contact analytics არ უნდა გახდეს ფარული feedback module.
3. **Autonomous AI publishing/customer-state change** — human approval, citations, audit and rollback-ის გარეშე დაუშვებელია.
4. **Custom role builder** — ოთხი როლი + overrides საკმარისია; იზრდება access complexity და audit risk.
5. **Microservices „თანამედროვეობისათვის“** — ~600-user scale და არსებული modular monolith ამას არ ამართლებს; seams ჯერ interfaces/events-ით გაღრმავდეს.
6. **Native mobile app** — desktop-first სამუშაოა; mobile მხოლოდ ცალკე field/touch/offline JTBD business case-ით.
7. **Custom PBX/ACD, WFM optimizer, STT model ან RPA platform** — mature commodity/vendor domains; Magti-მ ააშენოს adapters/policy/UX/evaluation.
8. **Portal-ში CRM-ის სრული ასლი** — case/customer master duplication შექმნის reconciliation risk-ს.
9. **ყველა არხის big-bang migration** — metadata normalization და ერთი channel-at-a-time.
10. **100% opaque AI QA scoring** — sampling/triage შეიძლება, საბოლოო ხარისხის გადაწყვეტილება ადამიანთან რჩება.
11. **Employee productivity league tables/keystroke surveillance** — wellbeing/adoption-ს აზიანებს და privacy risk-ს ქმნის.
12. **ყველა მონაცემის data lake/knowledge graph წინასწარ** — დაიწყოს minimum canonical events/metadata და კონკრეტული use case.
13. **AI chatbot მხოლოდ deflection KPI-ით** — resolved-without-recontact + successful human handoff არის სწორი საზომი.
14. **Automatic purge policy-ის ხელმოწერამდე** — deleted evidence და legal hold უნდა იყოს დაცული.
15. **Acting manager exports ან raw audit delegation** — ეწინააღმდეგება ფიქსირებულ კონტროლებს.

---

## 18. ღია Product Owner / Investment Committee გადაწყვეტილებები

> მიმდინარე access/product contract-ში დაფიქსირებული გადაწყვეტილებები დახურულია; ქვემოთ არის **ახალი სტრატეგიული არჩევანი**, არა ძველი საკითხების ხელახლა გახსნა.

| # | გადაწყვეტილება | რეკომენდებული default | ბოლო თარიღი |
|---:|---|---|---|
| 1 | რომელი საინვესტიციო სცენარი? | **B — Connected Service OS** | პროგრამის დაწყებამდე |
| 2 | პირველი top-5 wrong-service reasons/queue | highest verified cost/risk | week 1–2 |
| 3 | operator home-ის top 3 task | search, incident/checklist, required work | week 2 |
| 4 | critical knowledge risk tiers/SLA | 30/90/180-day review by risk | month 1, Legal consult |
| 5 | manager insights-ის 5 action metric | compliance, workload, search gap, QA action, recontact | month 1; DPO consult |
| 6 | პირველი CTI pilot queue/vendor path | read-only, highest volume, stable identity | month 2 |
| 7 | canonical case system of record | existing CRM/ticketing; Portal thin workflow | CTI design before build |
| 8 | პირველი QA scorecard/process | high-risk, trainable reason family | month 3–4 |
| 9 | პირველი RPA/self-service use cases | reversible, high-volume, low privacy risk | month 4–6 |
| 10 | AI pilot risk ceiling | internal low-risk assist only | before AI procurement |
| 11 | scenario C option trigger | after 12-month B evidence | month 12 investment review |
| 12 | value reinvestment rule | verified savings partially fund next tranche | steering committee |

PO-სგან არ მოითხოვება security/privacy/legal risk-ის ერთპიროვნულად მიღება: შესაბამის gate-ს ხელს აწერს შესაბამისი accountable ფუნქცია.

---

## 19. წყაროები და audit trail

### 19.1 შიდა პირველწყაროები

- `docs/PRODUCT_UX_REQUIREMENTS_KA.md` — product/UX/accessibility/retention/roles/scale მოთხოვნები.
- `docs/archive/audits/UX_IMPLEMENTATION_DECISIONS_2026-08-24_KA.md` — ბოლო UX/flow გადაწყვეტილებები.
- `docs/ACCESS_CONTRACT_MATRIX_KA.md` — endpoint/role/scope/export/audit contract; 2026-08-24 მდგომარეობა.
- `docs/PROJECT_TECHNOLOGY_GUIDE_KA.md` — აქტიური Angular/Spring Boot/Oracle target და rollout guidance.
- `docs/archive/audits/READINESS_REPORT_2026-08-23.md` — ისტორიული launch findings; გამოყენებულია მხოლოდ current-code revalidation-ით.
- `docs/archive/legacy-stack/ARCHITECTURE.md` — legacy context; წინააღმდეგობისას current target docs/code სჯობს.
- `docs/PRODUCT_OWNER_DECISIONS_KA.md`, `docs/IMPLEMENTATION_PLAN_KA.md` — დახურული გადაწყვეტილებები და production integration/cutover sequence.
- Angular routes/package/config; backend controllers/services/security/domain; Flyway V1–V45; Java/Angular/E2E test inventory — capability/status-ის მთავარი მტკიცებულება.

### 19.2 გარე პირველწყაროები

კომპანიის ინიციატივების 24 პირდაპირი official link მოცემულია §5-ის თითოეულ row-ში. დამატებითი normative/governance წყაროები:

- [NIST AI Risk Management Framework](https://www.nist.gov/itl/ai-risk-management-framework) და [Generative AI Profile](https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.600-1.pdf).
- [W3C Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/wcag/).
- [ISO 18295-1:2017 — Customer contact centres](https://www.iso.org/standard/64739.html).
- [ISO/IEC 27001:2022 — Information security management systems](https://www.iso.org/standard/27001).

### 19.3 შეზღუდვები და შემოწმების საჭიროება

- რეპოზიტორია აქტიურად იცვლება; ეს დოკუმენტი აღწერს 2026-08-24-ზე ნანახ მდგომარეობას. production config და external systems რეპოზიტორიიდან სრულად ვერ დასტურდება.
- readiness-ის ძველი finding დახურულად ჩაითვალა მხოლოდ მაშინ, როცა current code/migration შესაბამის კონტროლს აჩვენებს; operational evidence-ის გარეშე „იმპლემენტირებული“ არ ნიშნავს „production-verified“.
- კომპანიის შედეგები არის მათივე საჯარო განცხადება; Magti business case-ს სჭირდება საკუთარი baseline/control.
- cost S/M/L/XL და scenario multipliers არის relative planning bands; cash budget მიიღება vendor quotes, internal rate card და detailed solution design-ის შემდეგ.
- scoring-ში მცირე ცვლილებამ შეიძლება რიგი შეცვალოს; security/privacy blocker მაღალი total score-ით არ გაუქმდება.

---

## საბოლოო რეკომენდაცია

Steering Committee-მ დაამტკიცოს **სცენარი B** ორეტაპიანი დაფინანსებით: პირველი tranche — H1 foundation + discovery; მეორე tranche — CTI/QA/journey/WFM და მხოლოდ gate-გავლილი AI/RPA pilot-ები. 90 დღეში გადაწყვეტილების მთავარი საზომი უნდა იყოს არა გამოშვებული feature-ების რაოდენობა, არამედ: production assurance, wrong-service baseline/pilot movement, knowledge/search freshness, incident response, scope/privacy evidence და operator task completion. ეს გზაა, რომელიც Portal-ს აქცევს სანდო enterprise capability-ად ზედმეტი პლატფორმიზაციისა და ნაადრევი AI რისკის გარეშე.
