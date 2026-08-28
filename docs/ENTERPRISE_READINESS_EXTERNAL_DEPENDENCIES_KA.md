# Enterprise Readiness External Dependencies Ledger

განახლებულია: 2026-08-27

ყველა ჩანაწერი `External`-ია; ეს არ აჩერებს დამოუკიდებელ შიდა P0/P1 სამუშაოს.
Secrets, tokens, personal data და production credentials ამ ledger-ში არ იწერება.

| ID | Status | Dependency / owner | საჭირო evidence | Internal work while waiting |
|---|---|---|---|---|
| EXT-001 | External | SSO/AD non-prod integration — IAM + IT | protocol/metadata, claims/groups, certificate lifecycle, test users, revoke/deprovision, IdP outage evidence | fail-closed config guard, mapping contract and automated adapter tests |
| EXT-002 | External | Network/DNS/TLS/reverse proxy — Infrastructure | topology, trusted proxy CIDRs, TLS ownership/renewal, firewall and ingress policy | production config validation and proxy-safe tests |
| EXT-003 | External | Oracle backup/restore — DBA + Infrastructure | RMAN config/validate, encrypted backup, isolated restore, content/attachment/audit reads, measured RPO/RTO | restore runbook template and evidence checklist |
| EXT-004 | External | Retention/legal hold/privacy — DPO + Legal | data-class schedule, total audit archive duration, hold set/release authority, purge approvals, manager export sign-off | keep purge fail-closed; state-machine and authorization tests |
| EXT-005 | External | Monitoring/alerting/on-call — Infrastructure + SOC | metrics/log platform, dashboard, thresholds, notification routes, ownership/SLA, alert drill | health semantics, actionable metrics and runbook drafts |
| EXT-006 | External | Security assurance — Security | SAST/SCA/DAST/secrets results, credential rotation confirmation, pentest report, exception approvals | remediate internally reproducible findings and add regressions |
| EXT-007 | External | Production-like staging and release path — Infrastructure + Release Manager | isolated environment, versioned artifact, config injection, deploy and rollback run results | deterministic builds, readiness checks, rollback checklist |
| EXT-008 | External | Representative performance environment — Infrastructure + Performance QA | production-like topology/data volume, approved SLOs, k6 runner and trend storage | hard script thresholds and safe synthetic data scenarios |
| EXT-009 | External | Four-role UAT — Product Owner + Business Owners | named approvers, scripts, results, P0/P1 defect closure, GO/NO-GO signatures | stable role fixtures and automated smoke pack |
| EXT-010 | External | Accessibility manual review — Accessibility/QA | keyboard, focus, zoom, screen-reader and browser matrix evidence | automated axe/unit regressions and critical-flow fixes |

Primary request sources: `docs/QUESTIONS_FOR_IT.md`, `docs/IT_DISCOVERY_REQUEST_KA.md`
and `docs/IT_DISCOVERY_QUESTIONNAIRE_KA.md`. External actions require the relevant
owner's authorization; no external system was changed in this cycle.

2026-08-25 identity/access audit increment-მა ახალი external dependency არ შექმნა:
directory-owned user/team/password mutation paths fail-closed დარჩა. `DEC-P01`
file-entitlement conflict არის Product/Security decision gate და არა ნაგულისხმევად
გადასაწყვეტი გარე integration task.

2026-08-25 P0-A3 application audit reconciliation-მა ახალი external dependency არ
შექმნა: production SSO evidence კვლავ `EXT-001`-ს ეკუთვნის, ხოლო export/load/monitoring
operational proof — შესაბამის `EXT-005/007/008` ჩანაწერებს.

2026-08-25 P0-A4 deny-by-default და local isolated E2E evidence შიდა code gate-ებს
ამტკიცებს, მაგრამ production-like SSO/scope/browser UAT-ს არ ანაცვლებს: ეს კვლავ
`EXT-001`, `EXT-007` და `EXT-009` ჩანაწერებს ეკუთვნის. `DEC-P02` არის
Product/Security contract clarification და არა გარე integration blocker.

2026-08-25 P0-A5 SYSTEM_ADMIN negative matrix ახალი external dependency არ ქმნის.
იგი local Oracle-ზე role-boundary code evidence-ს აძლიერებს, მაგრამ cross-team UAT-ს,
signed security assurance-სა და staging evidence-ს არ ანაცვლებს; ეს კვლავ
`EXT-006`, `EXT-007` და `EXT-009` ჩანაწერებს ეკუთვნის.

2026-08-25 P0-A6 manager team-stats foreign-IDOR closure შიდა code gate-ია და ახალი
external dependency არ შეუქმნია. Production-like group-change data, staging security
verification და business UAT კვლავ `EXT-007`/`EXT-009` evidence-ს მოითხოვს.

2026-08-25 P1-Q1 list/search/cache/CLOB increment-მა ახალი external dependency არ
შექმნა. Local Oracle code evidence ვერ ანაცვლებს production-like corpus/memory/p95 load
result-ს; ეს კვლავ `EXT-008`-ს ეკუთვნის. დარჩენილი article CLOB და all-user query paths
შიდა WS3-05 სამუშაოა და `External`-ად არ არის გადატანილი.

2026-08-25 P0-R1 legal-hold state-machine-მა ახალი external dependency არ შექმნა და
`EXT-004` არ დახურა. Internal code default-empty exact named-SSO allowlist-ით ყველა
application role-ს fail-closed უარყოფს, set/release-ს audit-ით ფარავს და held
restore/purge-ს კეტავს. DPO/Legal-მა კვლავ უნდა დაამტკიცოს კონკრეტული authority
identities, purge approval და retention schedule; მანამდე configuration ცარიელი რჩება
და production activation evidence არ არსებობს.

2026-08-25 P1-Q2 article-list CLOB projection-მა ახალი external dependency არ შექმნა.
V45 local Oracle code/migration evidence article-list CLOB transfer-ს ხურავს, მაგრამ
representative corpus-ის memory/p95 შედეგს არ ანაცვლებს; ეს კვლავ `EXT-008`-ია.
იმ ეტაპზე დარჩენილი all-user paths შიდა WS3-05 სამუშაო იყო; P1-Q3-მ directory და
user-progress დახურა, ხოლო org-backfill და სხვა org-wide compliance/stats paths ღიაა.

2026-08-25 P1-Q3 user-directory/user-progress bounds-მა ახალი external dependency არ
შექმნა. Local Oracle boundary/full-regression evidence representative corpus-ის memory/p95
შედეგს არ ანაცვლებს (`EXT-008`). Org-backfill და დარჩენილი org-wide compliance/stats
paths კვლავ დამოუკიდებელი შიდა WS3-05 სამუშაოა.

2026-08-25 P1-Q4 complete-result org/compliance/stats boundary-მ ახალი external
dependency არ შექმნა. Org-backfill, org-wide compliance და active-user statistics ახლა
1000-row shared safety ceiling-ზე partial შედეგის ნაცვლად fail-loud ხდება; დარჩენილი
`AccessDiffService`, reminder recipient scan და eligible-operator/export inventory კვლავ
დამოუკიდებელი შიდა WS3-05 სამუშაოა. Representative corpus-ის memory/p95 gate უცვლელად
`EXT-008`-ს ეკუთვნის და local 748-test evidence მას არ ანაცვლებს.

2026-08-25 P1-Q5 remaining user-fan-out boundary-მ ახალი external dependency არ შექმნა.
Access-diff, assignment reminder, eligible-operator/read-evidence, manager export, scoped
 compliance და group-leader user hydration ახლა local shared ceiling-ითაა დაცული. Article
view/read-receipt history და org assignment/team list paths დამოუკიდებელ შიდა WS3-05
სამუშაოდ რჩება; representative memory/p95 შედეგი კვლავ `EXT-008`-ია და local 754-test
evidence მას არ ანაცვლებს.

2026-08-25 P1-Q6 article view/history/read-evidence boundary-მ ახალი external dependency
არ შექმნა. View rows ახლა DB paging-ს და DB aggregate counts-ს იყენებს; history/version/
read-receipt complete-result paths 1000/1001 fail-loud ceiling-ზეა. Existing history response-ში
content CLOB-ის summary/detail contract-ით შეცვლა და org assignment/team list bounds კვლავ
დამოუკიდებელი შიდა WS3-05 სამუშაოა. Representative corpus-ის memory/p95 gate უცვლელად
`EXT-008`-ს ეკუთვნის და local 757-test evidence მას არ ანაცვლებს.

2026-08-25 P1-Q7 organization reference snapshot boundary-მ ახალი external dependency
არ შექმნა. Departments, teams და leadership assignments-ის complete-result queries ახლა
local 1000/1001 fail-loud module-ზეა; org structure N+1 team query ამოღებულია. დარჩენილი
category/tag/video list და article-history CLOB work დამოუკიდებელი შიდა WS3-05 სამუშაოა.
Representative corpus-ის memory/p95 gate კვლავ `EXT-008`-ს ეკუთვნის და local 763-test
evidence მას არ ანაცვლებს.

2026-08-25 P1-Q8 category/tag/video/news-history ceiling-მ ახალი external dependency
არ შექმნა. Legacy plain-array contracts ახლა local 1000/1001 fail-loud query-ს იყენებს;
news-history content CLOB wire contract უცვლელია. History summary/detail redesign და დარჩენილი
session/quiz/notification inventory დამოუკიდებელი შიდა WS3-05 სამუშაოა. Representative
corpus-ის memory/p95 gate კვლავ `EXT-008`-ს ეკუთვნის და local 766-test evidence მას არ
ანაცვლებს.

2026-08-25 P1-Q9 session/personal/compliance/quiz ceiling-მ ახალი external dependency
არ შექმნა. Active sessions, favorites, broadcasts, required-readings და quiz question/answer
complete-result paths ახლა local 1000/1001 fail-loud contract-ზეა; notification recent-news
უკვე 10-row query-ზე იყო და unread reminder count aggregate-ია. Article/news history CLOB
summary/detail redesign და დარჩენილი background/operations collection inventory დამოუკიდებელი
შიდა WS3-05 სამუშაოა. Representative corpus-ის memory/p95 gate კვლავ `EXT-008`-ს ეკუთვნის
და local 767-test evidence მას არ ანაცვლებს.

2026-08-25 P1-Q10 scheduler/reference boundary-მ ახალი external dependency არ შექმნა.
Export expiry cleanup ახლა BLOB-free 500-row ID/path batches-ს იყენებს; reminder pending work
1000-ID batch-ზეა, seed snapshot 1000/1001 fail-loud-ია; stale/related article references
CLOB-free 1000/1001 query-ზეა. Article/news history content CLOB redesign და დარჩენილი
keyed-child/finite-domain query classification დამოუკიდებელი შიდა WS3-05 სამუშაოა.
Representative corpus-ის memory/p95 gate კვლავ `EXT-008`-ს ეკუთვნის და local 770-test
evidence მას არ ანაცვლებს.

2026-08-25 P1-Q11 aggregate/export/leadership query boundary-მ ახალი external dependency
არ შექმნა. Knowledge score ახლა Oracle scalar summary-ია, readings export DB-side 20,001
sentinel-ს იყენებს, leadership authorization existence query-ებზეა, ხოლო scope assignment
snapshot bounded org seam-ში გადის. დარჩენილი article-target/required-reading relation fan-out,
compliance grouped-result cardinality და history CLOB summary/detail design დამოუკიდებელი შიდა
WS3-05 სამუშაოა. Representative corpus-ის memory/p95 gate კვლავ `EXT-008`-ს ეკუთვნის და
local 774-test evidence მას არ ანაცვლებს.

2026-08-25 P1-Q12 relation/compliance aggregate boundary-მ ახალი external dependency არ
შექმნა. Article read-receipt-ის required-reading relation 1000/1001 fail-loud კონტრაქტზეა;
compliance query Oracle-ში მხოლოდ Java-derived All/exact/prefix user-target pairs-ს აჯგუფებს
და result მაქსიმუმ 3 row/user-ია. Multi-parent article-target child cross-product და history
CLOB summary/detail design დამოუკიდებელი შიდა WS3-05 სამუშაოა. Representative corpus-ის
memory/p95 gate კვლავ `EXT-008`-ს ეკუთვნის და local source-backed 781-test evidence მას არ ანაცვლებს.

2026-08-25 P1-Q13 multi-parent article-target boundary-მ ახალი external dependency არ შექმნა.
Article list/search/related/quiz visibility callers ერთ 1000/1001 total-child relation seam-ზეა;
bounded parent set-ის 1001 child cross-product stable 413-ით fail-loud-ია. History CLOB-ის
backward-compatible summary/detail boundary დამოუკიდებელი შიდა WS3-05 სამუშაოა. Representative
corpus-ის memory/p95 gate კვლავ `EXT-008`-ს ეკუთვნის და local 785-test evidence მას არ ანაცვლებს.

2026-08-25 P1-Q14/Q15 history CLOB boundary-მ ახალი external dependency არ შექმნა. Article/news
summary lists CLOB-free-ია, selected detail მხოლოდ ერთ payload-ს იღებს, reader version list projection-ზეა,
ხოლო backward-compatible full endpoints Oracle aggregate-ით 2,000,000-character budget-ს hydration-მდე
ამოწმებს. Source-level history CLOB gap დახურულია. Representative corpus-ის memory/p95 და hard k6 gate
კვლავ `EXT-008`-ს ეკუთვნის; local 790-test/91-Angular/browser evidence production-like performance proof-ს
არ ანაცვლებს.

2026-08-25 P0-A7 legacy group-path/department-assignment boundary-მ ახალი external dependency
არ შექმნა. Assignment-backed group path canonical active department/team identity-ზე fail-closed
resolve-დება და first-rollout named read evidence department assignment-ით არ ფართოვდება. დარჩენილი
production-like cross-team UAT კვლავ `EXT-006`/`EXT-009`-ს ეკუთვნის; local broad 147-test და full
793-test Oracle evidence business/security staging sign-off-ს არ ანაცვლებს.

2026-08-25 P0-A8 content-history parent-child IDOR negative lock-მა ახალი external
dependency არ შექმნა. Article/news foreign-parent history IDs local Oracle HTTP gate-ზე
404-ია და rejected restore non-mutating-ია. Remaining production-like cross-team UAT კვლავ
`EXT-006`/`EXT-009`-ს ეკუთვნის; local 109-test broad და 795-test full evidence staging
security/business sign-off-ს არ ანაცვლებს.

2026-08-26 P0-A9 SELF-scoped keyed-resource isolation lock-მა ახალი external dependency
არ შექმნა. Session revoke, article note, favorite delete და reminder mark-read caller-owned
boundary local Oracle HTTP evidence-ითაა გამაგრებული; production role/scope/wire contract არ
შეცვლილა. Production-like cross-user/cross-team security UAT კვლავ `EXT-006`/`EXT-009`-ს
ეკუთვნის; local 91-test focused და 797-test full evidence signed staging sign-off-ს არ ანაცვლებს.

2026-08-26 P0-A10 compliance/quiz per-user evidence isolation lock-მა ახალი external
dependency არ შექმნა. Required-reading status/receipt და quiz attempt/knowledge score caller-owned
boundary local Oracle HTTP/row evidence-ითაა გამაგრებული; production code/policy/schema არ
შეცვლილა. Production-like four-role evidence UAT კვლავ `EXT-006`/`EXT-009`-ს ეკუთვნის; local
31-test focused და 799-test full evidence business/security sign-off-ს არ ანაცვლებს.

2026-08-26 P0-A11 SELF collection-read cross-user disclosure lock-მა ახალი external
dependency არ შექმნა. Search history, recently-viewed და notification reminder aggregate local
Oracle HTTP evidence-ით caller-ზეა ჩაკეტილი; production source/schema არ შეცვლილა. Production-like
cross-user UAT კვლავ `EXT-006`/`EXT-009`-ს ეკუთვნის; local 91-test focused gate signed staging
security/business sign-off-ს არ ანაცვლებს.

2026-08-26 P0-A12 SELF profile/effective-access/password lock-მა ახალი external dependency
არ შექმნა. Profile და effective-access caller-bound local Oracle HTTP evidence-ითაა გამაგრებული;
directory-owned password route fail-closed 403-ს ინარჩუნებს და local hash/audit-ს არ ცვლის.
რეალური directory password lifecycle და SSO/group mapping კვლავ `EXT-001`-ია, ხოლო production-like
cross-user UAT `EXT-006`/`EXT-009`-ს ეკუთვნის; local 32-test focused gate მათ sign-off-ს არ ანაცვლებს.

2026-08-26 P0-A13 favorite/reminder SELF ownership lock-მა ახალი external dependency არ შექმნა.
Favorite collection/mutation და reminder inbox/read state local Oracle HTTP/row evidence-ით caller-ზეა
ჩაკეტილი; production source/schema/policy არ შეცვლილა. Production-like cross-user UAT კვლავ
`EXT-006`/`EXT-009`-ს ეკუთვნის; local 10-test focused gate signed security/business sign-off-ს არ
ანაცვლებს.

2026-08-26 P0-A14 read-receipt status/compliance-progress SELF lock-მა ახალი external dependency
არ შექმნა. Versioned receipt status და progress aggregate local Oracle HTTP/row evidence-ით caller-ზეა
ჩაკეტილი; production source/schema/policy არ შეცვლილა. Production-like cross-user UAT კვლავ
`EXT-006`/`EXT-009`-ს ეკუთვნის; local 91-test focused gate signed security/business sign-off-ს არ
ანაცვლებს.

2026-08-26 P0-A15 session-heartbeat SELF lock-მა ახალი external dependency არ შექმნა. Authenticated
heartbeat local Oracle HTTP/row evidence-ით მხოლოდ caller session-ზეა ჩაკეტილი; production
source/schema/policy არ შეცვლილა. Real SSO/session lifecycle კვლავ `EXT-001`-ია, production-like
cross-user UAT კი `EXT-006`/`EXT-009`-ს ეკუთვნის; local 11-test gate მათ sign-off-ს არ ანაცვლებს.

2026-08-26 P0-A16/D-8 aggregate statistics capability split-მა ახალი external dependency არ შექმნა.
დადასტურებული `stats.view` permission ექვს company-wide aggregate endpoint-ს content management-ისგან
აშორებს; non-admin role-ს default grant არ მიეცა და named user/audit/content surfaces არ გაფართოვდა.
Local 802-test Java/Oracle, 96-test Angular და production-build evidence production-like four-role UAT-ს,
signed security assurance-ს ან release-path proof-ს არ ანაცვლებს; ეს კვლავ `EXT-006`/`EXT-007`/`EXT-009`-ს
ეკუთვნის.

2026-08-26 P0-A17 export-job owner IDOR evidence lock-მა ახალი external dependency არ შექმნა. Local
Oracle/HTTP gate-ზე ორი fully authorized manager-ის job ownership opaque 404/410-ით იზოლირებულია და
owner 200 flow უცვლელია. Production-like export/UAT და signed security assurance კვლავ
`EXT-006`/`EXT-007`/`EXT-009`-ს ეკუთვნის; local 29-test evidence მათ არ ანაცვლებს.

2026-08-27 P0-A18 video-view object-visibility closure-მა ახალი external dependency არ შექმნა. Existing
video audience/archived contract now applies to the keyed mutation; local 20-test Oracle evidence confirms
opaque rejection/non-mutation. Full local regression remains internal work, while production-like cross-role
UAT and signed security assurance remain `EXT-006`/`EXT-007`/`EXT-009`.
