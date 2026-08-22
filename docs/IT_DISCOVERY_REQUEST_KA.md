# Magti Portal — IT-სთვის გასაგზავნი მოკლე მოთხოვნა

**დანიშნულება:** პირველი ოფიციალური გაგზავნა IT კოორდინატორთან
**ფორმატი:** 12 შეკრული თემატური მოთხოვნა; IT კოორდინატორი ანაწილებს შესაბამის
გუნდებზე
**ტექნიკური დანართი:** `docs/IT_DISCOVERY_QUESTIONNAIRE_KA.md` — 93-პუნქტიანი
validation checklist, რომელსაც მხოლოდ შესაბამისი სპეციალისტები იყენებენ

## უკვე გადაწყვეტილი პროდუქტული მოთხოვნები

IT-ს არ ვთხოვთ პორტალის მუშაობის პრინციპების არჩევას. ტექნიკური პასუხი ამ
დადასტურებულ მოთხოვნებს უნდა მოერგოს:

- პორტალი საკუთარ პაროლებს არ ინახავს; შესვლას კომპანიის ავტორიზაციის სისტემა
  ადასტურებს;
- AD-ში deactivation, ჯგუფისა და დეპარტამენტის ცვლილება პორტალში მაქსიმუმ
  5–15 წუთში უნდა აისახოს;
- ფაილები მხოლოდ ავტორიზებულ თანამშრომლებს ეხსნებათ;
- წაშლილი content/attachments 30 დღეა აღდგენადი; export ფაილი — 1 საათი;
- `RPO = 0`, `RTO = 1 საათი`;
- ბიზნეს audit პირველი 1 წელი Oracle-შია, შემდეგ კომპანიის დაცულ არქივში
  რეგულაციებით განსაზღვრული ვადით;
- პორტალში შენახული audit/search/view/read/quiz/security log-ების export მხოლოდ
  `SYSTEM_ADMIN`-ს შეუძლია, ცალკე და სრულად აუდიტირებული ფაილებით; secret-ები
  არასოდეს გადის. runtime log SIEM-ში რჩება და მის წვდომას IT/SOC წყვეტს;
- Java/Angular cutover-ის შემდეგ Python 30 დღეა read-only, შემდეგ ითიშება.

## ყველა პასუხის საერთო ფორმატი

თითოეულ თემაზე მოგვაწოდეთ:

1. არჩეული/სავალდებულო ტექნიკური გადაწყვეტა;
2. განსხვავება `dev`, `test`, `staging` და `production` გარემოებს შორის;
3. პასუხისმგებელი გუნდი, primary/secondary contact და SLA;
4. სანიტიზებული diagram/config/sample/runbook ან ticket;
5. შეზღუდვა, ფასი, blocker და სამიზნე თარიღი.

არ მოგვაწოდოთ რეალური პაროლი, token, secret, private key ან wallet password —
მხოლოდ მათი საცავის სახელი, owner და rotation წესი.

## 12 შეკრული მოთხოვნა

### 1. პასუხისმგებლები და კომპანიის სავალდებულო წესები

დაგვისახელეთ ერთი ტექნიკური კოორდინატორი და AD/IAM, Kubernetes, Oracle, Network/
TLS, CI/CD, Security, Backup/DR, Monitoring/SIEM და Service Desk-ის owner-ები.
მოგვაწოდეთ production change/approval პროცესი, სავალდებულო არქიტექტურული და
უსაფრთხოების სტანდარტები და გამონაკლისის მოთხოვნის გზა.

**ტექნიკური დანართი:** `GOV-01…GOV-05`.

### 2. მომხმარებლის ავტორიზაციის სრული კონტრაქტი

გვითხარით, კომპანიის არსებული პაროლების/SSO სისტემიდან რომელი დამტკიცებული flow
უნდა გამოიყენოს პორტალმა. მოგვაწოდეთ protocol/endpoints ან metadata, request/
response ან token/claim mapping, TLS/certificate წესები, account-state/error
mapping, MFA/conditional-access მოთხოვნები, test გარემო და break-glass პროცესი.

**ტექნიკური დანართი:** `IAM-01…IAM-10`.

### 3. AD ორგანიზაციული მონაცემების კონტრაქტი

მოგვაწოდეთ departments, groups/teams, users და leaders-ის რეალური OU/group/
attribute მოდელი, stable ID-ები, membership/precedence წესი, multi-membership-ის
ქცევა და სანიტიზებული მაგალითები. დაადასტურეთ ვინ არის source of truth და ვინ
ასწორებს მონაცემის შეცდომას.

**ტექნიკური დანართი:** `SYNC-01…SYNC-08`.

### 4. AD ცვლილებებისა და deactivation-ის კონტრაქტი

აირჩიეთ change notification/delta/feed/polling მექანიზმი, რომელიც deactivation-ს,
ჯგუფისა და დეპარტამენტის ცვლილებას 5–15 წუთში მოგვაწვდის დაახლოებით 600
მომხმარებელზე ზედმეტი დატვირთვის გარეშე. აღწერეთ cursor/failover, delete/rename,
conflict, dry-run, retry/alert და უკვე გაცემული session/token-ის გაუქმება.

**ტექნიკური დანართი:** `SYNC-09…SYNC-16`.

### 5. Kubernetes, ქსელი, DNS და TLS

მოგვაწოდეთ cluster/namespace/runtime topology, რესურსები და quota, replica/
autoscaling/availability მოდელი, ingress/load-balancer, URL/DNS/TLS, proxy/header/
source-IP trust, firewall/egress გზები, NTP/timezone და დიდი upload/download-ის
ლიმიტები.

**ტექნიკური დანართი:** `PLAT-01…PLAT-14`.

### 6. CI/CD, artifact და უსაფრთხო release

აღწერეთ source-to-production pipeline, runner/registry, immutable artifact-ის
promotion, secret injection, image/dependency/security scans, environment config,
approval gates და rollback. ცალკე მოგვაწოდეთ Flyway migration owner, production
preflight, backup და forward-fix/restore runbook; production-ში `clean` აკრძალულია.

**ტექნიკური დანართი:** `REL-01…REL-08`.

### 7. Oracle-ის სრული production კონტრაქტი

მოგვაწოდეთ Oracle 19c-ის endpoint/service/PDB/topology, TLS/wallet, application და
migration accounts/privileges, schema/quota/tablespace, JDBC/pool/session limits,
HA/Data Guard, encryption/auditing, monitoring/performance tooling და patch/
upgrade ownership.

**ტექნიკური დანართი:** `DB-01…DB-13`.

### 8. ფაილების საცავი და scanning

ჩვენი პროდუქტული მოთხოვნებისთვის შეარჩიეთ კომპანიის მხარდაჭერილი საცავი — Oracle
BLOB, S3/MinIO/Ceph ან RWX — და მოგვაწოდეთ capacity/cost, auth/TLS, availability,
versioning, 30-დღიანი recovery, backup/restore, monitoring და migration პასუხი.
დაამატეთ PDF/image malware scanning/quarantine-ის სავალდებულო გზა.

**ტექნიკური დანართი:** `DR-01`, `DR-07`, `DR-08`.

### 9. Backup, disaster recovery და retention

დაადასტურეთ როგორ სრულდება `RPO = 0` და `RTO = 1 საათი` Oracle-ის, ფაილებისა და
სრული portal stack-ისთვის. მოგვაწოდეთ failure-scenario ცხრილი, ბოლო რეალური
restore/failover test, immutable/off-site backup, legal hold/purge წესები და audit-
ის ერთწლიანი Oracle-დან მრავალწლიან დაცულ არქივში გადატანის გადაწყვეტა.

**ტექნიკური დანართი:** `DR-02…DR-06`.

### 10. Secrets, ლოგები, მონიტორინგი და Security/SOC

მოგვაწოდეთ central secret store და rotation, certificate-expiry მართვა, runtime
log aggregation/SIEM, redaction, metrics/dashboard, health/synthetic monitoring,
alert/on-call წესები, security-event correlation და go-live security/penetration
test მოთხოვნები. ბიზნეს audit და runtime log ცალკე ნაკადებად აღწერეთ. ცალკე
დაადასტურეთ, კომპანიის პოლიტიკა აძლევს თუ არა პორტალის `SYSTEM_ADMIN`-ს SIEM-ის
runtime log-ზე export/search უფლებას და რა SSO/role/approval/audit გზით.

**ტექნიკური დანართი:** `OPS-01…OPS-10`.

### 11. Incident response და ყოველდღიური მხარდაჭერა

მოგვაწოდეთ account/session/system kill-switch პროცესი, severity/escalation/on-call
matrix, Service Desk ticket კატეგორიები და SLA, runbook-ები, პასუხისმგებლობის
საზღვარი Engineering-სა და IT-ს შორის და incident-ის შემდეგ evidence/report გზა.

**ტექნიკური დანართი:** `OPS-11…OPS-12`.

### 12. Production cutover, მიღება და ძველი Python

მოგვაწოდეთ change window, fixture/backfill/AD-sync operator matrix, AD dry-run-ის
გარემო, rollout flags/rollback, acceptance/sign-off და hypercare გეგმა. ცალკე
აღწერეთ Python-ის ნამდვილი backend/API/database read-only enforcement 30 დღით,
შემდეგ მისი DNS/deployment გათიშვა, backup/archive და restore verification.

**ტექნიკური დანართი:** `CUT-01…CUT-06`, `CUT-08`.

## გამოყენების წესი

1. IT კოორდინატორს პირველად ეგზავნება მხოლოდ ეს 12-პუნქტიანი დოკუმენტი.
2. კოორდინატორი თითო თემას შესაბამის გუნდს ანიჭებს.
3. კონკრეტულ სპეციალისტს ეგზავნება მხოლოდ მისი თემის ტექნიკური დანართი, არა ყველა
   93 პუნქტი.
4. მოკლე საბოლოო პასუხები და სტატუსები იწერება `docs/QUESTIONS_FOR_IT.md`-ში.
5. 93-პუნქტიანი checklist გამოიყენება იმის შესამოწმებლად, პასუხში მნიშვნელოვანი
   დეტალი ხომ არ გამოგვრჩა; ის დამოუკიდებელი 93 წერილი ან 93 შეხვედრის კითხვა არ არის.
