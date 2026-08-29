# Magti Portal — IT-ის ტექნიკური სამუშაო კითხვარი (93 კონტროლირებული პუნქტი)

**აუდიტორია:** Magti IT, AD/IAM, Kubernetes/DevOps, DBA, Network/Security,
SOC/Monitoring და Service Desk
**მიზანი:** Phase 9B-ის, production rollout-ისა და შემდგომი ოპერირების წინ
მხოლოდ კომპანიის რეალურ ინფრასტრუქტურაზე დამოკიდებული ფაქტების შეგროვება
**სტატუსი:** სამუშაო კითხვარი — პასუხების ოფიციალური რეესტრი კვლავ
`docs/QUESTIONS_FOR_IT.md`-ია
**განახლებულია:** 2026-08-22

პორტალის მუშაობის პრინციპებზე არჩევანი ამ კითხვარში არ შედის; ის ცალკეა
აღწერილი `docs/PRODUCT_OWNER_DECISIONS_KA.md`-ში.

> **ეს დოკუმენტი IT-ს პირველ წერილში მთლიანად არ ეგზავნება.** გასაგზავნი მოკლე
> 12-თემიანი მოთხოვნაა `docs/IT_DISCOVERY_REQUEST_KA.md`. ეს 93-პუნქტიანი ვერსია
> არის ტექნიკური validation checklist და გუნდებზე ნაწილდება მხოლოდ შესაბამისი
> სექციებით.

---

## 1. როგორ გამოვიყენოთ ეს დოკუმენტი

ეს კითხვარი არ ნიშნავს, რომ IT-მ ერთ შეხვედრაზე ყველაფერი ზეპირად უნდა იცოდეს.
მიზანია თითოეულ პუნქტს ჰყავდეს **პასუხისმგებელი**, **პასუხის თარიღი** და, სადაც
შესაძლებელია, **მტკიცებულება**.

კითხვარი გუნდებზე ნაწილდება ID-ის prefix-ით:

| ID | ძირითადი პასუხისმგებელი | კითხვების რაოდენობა | შედეგი |
|---|---|---:|---|
| `GOV` | IT კოორდინატორი / Architecture / Change Management | 5 | owner-ები, approval და სავალდებულო სტანდარტები |
| `IAM` | IAM / AD / Security | 10 | ავტორიზაციის დამტკიცებული contract |
| `SYNC` | AD / IAM / directory data owner | 16 | org mapping და ცვლილებების feed contract |
| `PLAT` | Kubernetes / Platform / Network | 14 | runtime, ქსელი, DNS/TLS და availability |
| `REL` | DevOps / Release / DBA | 8 | CI/CD, Flyway და rollback runbook |
| `DB` | Oracle DBA | 13 | Oracle connectivity, privilege, HA და capacity |
| `DR` | DBA / Backup / Storage / DPO | 8 | ფაილები, backup, RPO/RTO და retention |
| `OPS` | Platform / SOC / Security / Service Desk | 12 | secrets, observability და incident response |
| `CUT` | Change Manager / Ops / Engineering | 7 | cutover, acceptance და legacy shutdown |

პრიორიტეტები:

- **P0 — ბლოკერი:** პასუხის გარეშე Phase 9B-ის შესაბამისი ნაწილი ან უსაფრთხო
  production გაშვება არ იწყება.
- **P1 — production-მდე:** კოდის წერა შეიძლება გაგრძელდეს, მაგრამ go-live-მდე
  პასუხი და runbook აუცილებელია.
- **P2 — ოპერირება:** პირველი სტაბილური production პერიოდისთვის უნდა დაიხუროს.

განაწილება: **61 P0**, **31 P1**, **1 P2**. P0 არ ნიშნავს, რომ 61 შეხვედრაა
საჭირო — ერთი გუნდის პასუხის პაკეტმა რამდენიმე დაკავშირებული ID ერთად უნდა
დახუროს, მაგრამ თითო ID-ზე მტკიცებულება და სტატუსი ცალკე ფიქსირდება.

ყოველ პასუხს დავურთოთ:

1. სტატუსი: `პასუხგაცემული`, `ბლოკირებული` ან `N/A` დასაბუთებით;
2. პასუხი ცალ-ცალკე `dev` / `test` / `staging` / `production` გარემოსთვის;
3. არჩეული ან კომპანიის მიერ სავალდებულო კონკრეტული გადაწყვეტა — მხოლოდ
   „დიახ/არა“ პასუხი საკმარისი არ არის;
4. პასუხისმგებელი გუნდი, primary owner და შემცვლელი;
5. მტკიცებულება — სანიტიზებული config/export/diagram/screenshot/ticket/runbook;
6. SLA, capacity/rate limit, შეზღუდვა, ფასი ან გამონაკლისი;
7. შესრულების ან ხელახალი გადამოწმების თარიღი.

> **უსაფრთხოება:** პასუხებში არ ჩაიწეროს პაროლი, JWT secret, bind password,
> private key, wallet password ან რეალური access token. მოგვაწოდონ secret-ის
> **სახელი, საცავი, owner და rotation წესი**, თვითონ მნიშვნელობა კი დაცული
> არხით შეიყვანონ შესაბამის გარემოში.

### პასუხის მოკლე შაბლონი

```text
ID:
სტატუსი: პასუხგაცემული / ბლოკირებული / N/A
გარემო:
არჩეული ან სავალდებულო გადაწყვეტა:
Owner / გუნდი / შემცვლელი:
მტკიცებულება ან ticket:
SLA / limit / capacity:
გამონაკლისი / რისკი:
სამიზნე თარიღი:
ბოლო გადამოწმება:
```

### უცვლელი პროდუქტული მოთხოვნები

ქვემოთ ჩამოთვლილი პირობები IT-სთვის input-ია და არა ხელახლა გადასაწყვეტი საკითხი:

- პორტალი საკუთარ პაროლს არ ინახავს; მომხმარებელს კომპანიის ავტორიზაციის სისტემის
  პასუხის საფუძველზე უშვებს;
- AD deactivation, ჯგუფისა და დეპარტამენტის ცვლილება მაქსიმუმ 5–15 წუთში უნდა
  აისახოს;
- დანართები მხოლოდ ავტორიზებულ თანამშრომლებს ეხსნებათ;
- წაშლილი content/attachments 30 დღეა აღდგენადი; დროებითი export — 1 საათი;
- `RPO = 0`, `RTO = 1 საათი`;
- ბიზნეს audit პირველი 1 წელი Oracle-შია, შემდეგ კომპანიის დაცულ არქივში
  რეგულაციებით განსაზღვრული ვადით;
- Java/Angular cutover-ის შემდეგ Python 30 დღეა სრულად read-only, შემდეგ ითიშება;
- production-ში fixture seeder და Flyway `clean` აკრძალულია; backfill მხოლოდ
  დამტკიცებული runbook/change-ის ფარგლებში სრულდება.

თუ არსებული ინფრასტრუქტურა რომელიმე მოთხოვნას ვერ ასრულებს, პასუხში უნდა ეწეროს
ზუსტი ტექნიკური მიზეზი, გაზომილი შეზღუდვა, შესაძლო ალტერნატივა, ფასი/ვადა და owner.
მოთხოვნა IT-ის პასუხით ავტომატურად არ იცვლება.

---

## 2. პირველი შეხვედრის P0 სია

თუ დრო ცოტა გვაქვს, ჯერ ეს პუნქტები დავხუროთ:

- `GOV-01`–`GOV-02` — ტექნიკური კოორდინატორი და ყველა სისტემის owner;
- `IAM-01`–`IAM-05`, `IAM-07`–`IAM-10` — SSO/ADFS/LDAP გზა, identity
  claim-ები, LDAPS, service account, test გარემო და break-glass;
- `SYNC-01`–`SYNC-12`, `SYNC-14`, `SYNC-16` — OU/group mapping, stable ID,
  ცვლილებების feed, deactivation, multi-membership და conflict/dry-run წესები;
- `PLAT-01`, `PLAT-04`–`PLAT-10` — კლასტერი, deployment, replica-ები,
  ingress, DNS/TLS, trusted proxy და ქსელის გზები;
- `DB-01`–`DB-08` — Oracle endpoint/version/topology, ანგარიშები,
  privileges, encryption და connection limits;
- `DR-01`–`DR-05` — backup, BLOB-ები, RPO/RTO და restore test;
- `REL-01`, `REL-03`–`REL-07` — CI/CD, secrets, migration owner,
  V36/V36.1 მდგომარეობა, V37-ის preflight და rollback;
- `OPS-01`, `OPS-07`, `OPS-11` — secret store, აუცილებელი alert-ები და
  incident kill-switch;
- `CUT-01`–`CUT-06` — change window, dry-run-ის გარემო, Python read-only,
  backfill operator, flags და პლატფორმის rollback შესაძლებლობები.

AD sync adapter-ის პირდაპირი ბლოკერია `SYNC-02`–`SYNC-10`. ეს შეესაბამება
`docs/QUESTIONS_FOR_IT.md` §10-სა და access contract-ის G-4/G-5 gate-ებს.

---

## 3. პასუხისმგებლობა და გადაწყვეტილების გზა

### GOV-01 — ერთი ტექნიკური კოორდინატორი — P0

**კითხვა IT-სთვის:** ვინ იქნება ამ პროექტის ერთი ტექნიკური კოორდინატორი და ვინ
ჩაანაცვლებს შვებულების/არყოფნის დროს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** სახელი, გუნდი, საკონტაქტო არხი, შემცვლელი და პასუხის
მოსალოდნელი SLA.

### GOV-02 — სისტემების owner-ები — P0

**კითხვა IT-სთვის:** ვინ ფლობს AD/IAM-ს, Kubernetes-ს, Oracle-ს, ქსელს/TLS-ს,
CI/CD-ს, secrets-ს, backup/DR-ს, monitoring/SIEM-სა და Service Desk-ს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** RACI ან მინიმუმ გუნდი + primary/secondary contact.

### GOV-03 — ცვლილების დამტკიცება — P1

**კითხვა IT-სთვის:** production ცვლილებას ვინ ამტკიცებს, რა ticket/change
ფორმაა საჭირო, რამდენი დღით ადრე და არის თუ არა change-freeze პერიოდები?

**მოსაწოდებელი პასუხი/მტკიცებულება:** change-management პროცესი, approval chain და კალენდარი.

### GOV-04 — რისკის მიღება — P1

**კითხვა IT-სთვის:** თუ რომელიმე კონტროლი დროებით ვერ სრულდება, ვინ იღებს
რისკს, რა ვადით და სად იწერება გამონაკლისი?

**მოსაწოდებელი პასუხი/მტკიცებულება:** exception/risk-acceptance შაბლონი და approver.

### GOV-05 — კომპანიის სავალდებულო პლატფორმული შეზღუდვები — P1

**კითხვა IT-სთვის:** Java + Angular + external Oracle + on-prem Kubernetes
სამიზნეზე რომელი სავალდებულო კომპანიის სტანდარტები ან ტექნიკური შეზღუდვები
მოქმედებს (approved base image, network zone, runtime, registry, ingress)?

**მოსაწოდებელი პასუხი/მტკიცებულება:** მოქმედი platform standards, შეზღუდვები, owner და
გამონაკლისის მოთხოვნის პროცესი.

---

## 4. SSO, მომხმარებლის შესვლა და AD/IAM

### IAM-01 — კომპანიის ავტორიზაციის ტექნიკური გზა — P0

**დადგენილი პროდუქტული პრინციპი:** პორტალი საკუთარ პაროლების სისტემას არ ქმნის,
პაროლს არ ინახავს და თავად არ წყვეტს სწორია თუ არა იგი. მომხმარებელს მხოლოდ
კომპანიის არსებული ავტორიზაციის სისტემის წარმატებული პასუხის საფუძველზე უშვებს.

**კითხვა IT-სთვის:** კომპანიის ინფრასტრუქტურაში ამ შემოწმების დამტკიცებული გზა
რომელია: პორტალი იღებს შეყვანილ მონაცემებს და backend-იდან უგზავნის სპეციალურ
authentication service-ს/LDAP-ს, მომხმარებელი გადადის ADFS/SAML/OIDC login
გვერდზე, გამოიყენება Kerberos/Windows Integrated Authentication, თუ სხვა
მექანიზმი? რომელი გზა არის ამ პროექტისთვის სავალდებულო?

**მოსაწოდებელი პასუხი/მტკიცებულება:** ერთი დამტკიცებული flow, პროტოკოლი, პროდუქტის/სერვისის
სახელი, system owner, network path და სანიტიზებული sequence diagram ან working
integration example.

### IAM-02 — authentication request/response contract და გარემოები — P0

**კითხვა IT-სთვის:** არჩეული flow-ისთვის მოგვაწოდეთ dev/test/staging/prod
კონტრაქტი. თუ პორტალის backend აგზავნის username/password-ს, რომელ FQDN/endpoint/
პორტსა და პროტოკოლს იყენებს, რომელი ველები იგზავნება და რას ნიშნავს success,
wrong password, locked/disabled account, expired password, MFA-required,
timeout და unavailable პასუხები? თუ redirect/token flow გამოიყენება, რა არის
issuer/entity ID, metadata/login/logout endpoint, signing certificate, allowed
redirect/ACS URL-ები და საჭირო claim-ები?

ასევე დაადასტურეთ, ხვდება თუ არა raw password საერთოდ portal frontend/backend-ში.
თუ ხვდება, მოგვაწოდეთ კომპანიის სავალდებულო წესები: მხოლოდ TLS, არასოდეს
database/log/cache/analytics-ში, request-body masking, timeout/retry/rate-limit
პოლიტიკა და security review მოთხოვნა.

**მოსაწოდებელი პასუხი/მტკიცებულება:** სანიტიზებული API/protocol contract და sample request/
response-ები რეალური პაროლის/token/private key-ის გარეშე; გარემოების მისამართები,
TLS/CA chain, error mapping, timeout/retry/rate-limit წესები, certificate owner და
rotation თარიღი.

### IAM-03 — მომხმარებლის უცვლელი login identity — P0

**კითხვა IT-სთვის:** რომელი claim/attribute არის ადამიანის ანგარიშის კანონიკური
იდენტიფიკატორი — `objectGUID`, SID, UPN, `sAMAccountName`, employee ID თუ email?
იცვლება თუ არა ის გვარის, email-ის ან domain-ის ცვლილებისას?

**მოსაწოდებელი პასუხი/მტკიცებულება:** არჩეული key, ფორმატი, uniqueness scope და rename-ის
რეალური მაგალითი.

### IAM-04 — login claim-ების სრული mapping — P0

**კითხვა IT-სთვის:** რომელი attribute-ებიდან მოდის სახელი, გვარი, display name,
email, employee number, language, department და account status? რომელი ველია
სავალდებულო და რომელი შეიძლება იყოს ცარიელი?

**მოსაწოდებელი პასუხი/მტკიცებულება:** attribute dictionary, 2–3 სანიტიზებული sample assertion
ან LDAP record და null/duplicate შემთხვევები.

### IAM-05 — domain-ები, forest-ები და trust — P0

**კითხვა IT-სთვის:** ყველა ~600 მომხმარებელი ერთ domain/forest-შია? არის trusted
domain, contractor/partner account, duplicate UPN ან რამდენიმე email domain?

**მოსაწოდებელი პასუხი/მტკიცებულება:** domain/forest სია, trust მიმართულება და uniqueness წესი.

### IAM-06 — MFA და დამატებითი login წესები — P1

**კითხვა IT-სთვის:** მოქმედებს MFA, smart card, device compliance, VPN/IP
შეზღუდვა, სამუშაო საათები ან Conditional Access? რომელი წესები შეეხება ამ აპს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** policy-ის სახელი, scope, გამონაკლისები და test გზა.

### IAM-07 — LDAP/LDAPS service account — P0

**კითხვა IT-სთვის:** თუ LDAP/LDAPS გვჭირდება, მოგვცემთ dedicated read-only bind
account-ს? რა OU/attribute/group-ის წაკითხვა შეუძლია, როგორ ინახება/როტირდება
მისი secret და იკეტება თუ არა interactive login?

**მოსაწოდებელი პასუხი/მტკიცებულება:** account owner, DN-ის სანიტიზებული ფორმა, privilege list,
secret-store reference და rotation SLA; პაროლი არა.

### IAM-08 — LDAPS endpoint და certificate chain — P0

**კითხვა IT-სთვის:** რომელ FQDN/პორტებზეა LDAPS, არის load-balanced VIP თუ
კონკრეტული DC-ები, რომელი CA chain უნდა ენდოს Java truststore და plain LDAP
აკრძალულია თუ არა?

**მოსაწოდებელი პასუხი/მტკიცებულება:** FQDN/პორტები, CA certificate chain, failover წესი და
`openssl`/`ldapsearch`-ის სანიტიზებული წარმატებული შემოწმება.

### IAM-09 — non-production AD მონაცემები — P0

**კითხვა IT-სთვის:** არსებობს test AD/OU/IdP tenant ან უსაფრთხო test ჯგუფები?
თუ მხოლოდ production AD არსებობს, რომელი test persona-ებით და რა read-only
შეზღუდვით ვამოწმებთ ინტეგრაციას?

**მოსაწოდებელი პასუხი/მტკიცებულება:** გარემო, test account/group სია, data masking და cleanup
წესი.

### IAM-10 — break-glass ადმინისტრატორი — P0

**კითხვა IT-სთვის:** თუ AD/IdP მიუწვდომელია, არსებობს თუ არა emergency
SYSTEM_ADMIN login? ვინ ინახავს მას, როგორ კონტროლდება გამოყენება, როგორ
როტირდება და როგორ ვიგებთ, რომ გამოიყენეს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** break-glass runbook, dual control, audit alert და test
სიხშირე; credential არა.

---

## 5. AD ორგანიზაციული სტრუქტურა და sync contract

### SYNC-01 — source of truth და data owner — P0

**კითხვა IT-სთვის:** AD ნამდვილად არის departments, groups და user membership-ის
production source of truth? რომელი გუნდი ასწორებს შეცდომას AD-ში და რა SLA აქვს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** owner, change პროცესი, SLA და escalation.

### SYNC-02 — რეალური OU/group ხე — P0 / Phase 9B blocker

**კითხვა IT-სთვის:** მოგვაწოდეთ ტექნიკური, საინფორმაციო და ოფისის რეალური
OU/group ხის სანიტიზებული export. ჯგუფი OU-ა, security group-ია, distribution
group-ია თუ custom attribute-ით განისაზღვრება?

**მოსაწოდებელი პასუხი/მტკიცებულება:** subtree export, object class-ები, base DN-ები და 2–3
sample record.

### SYNC-03 — department stable ID — P0 / Phase 9B blocker

**კითხვა IT-სთვის:** დეპარტამენტის რომელი მნიშვნელობა არ იცვლება rename/move-ის
დროს და ჩაიწერება `departments.ad_external_id`-ში?

**მოსაწოდებელი პასუხი/მტკიცებულება:** attribute, encoding/string ფორმა, uniqueness და rename/move
ტესტის მაგალითი.

### SYNC-04 — team/group stable ID — P0 / Phase 9B blocker

**კითხვა IT-სთვის:** ჯგუფის რომელი key არის უფროსისა და display name-ისგან
დამოუკიდებელი და rename/move-ისას უცვლელი?

**მოსაწოდებელი პასუხი/მტკიცებულება:** attribute, uniqueness scope და ორი ისტორიული ცვლილების
მაგალითი თუ არსებობს.

### SYNC-05 — user stable ID — P0 / Phase 9B blocker

**კითხვა IT-სთვის:** მომხმარებლის stable key არის `objectGUID`, SID, employee ID
თუ სხვა? რა ხდება rehire-ის, account recreation-ისა და domain migration-ისას?

**მოსაწოდებელი პასუხი/მტკიცებულება:** lifecycle table: rename, transfer, leave, rehire,
recreate, domain move → key იცვლება/არ იცვლება.

### SYNC-06 — membership-ის წყარო და precedence — P0

**კითხვა IT-სთვის:** მომხმარებლის ძირითადი ჯგუფი OU-ით, direct group
membership-ით, nested group-ით, `primaryGroupID`-ით თუ custom attribute-ით
დგინდება? რომელი წყარო იგებს წინააღმდეგობისას?

**მოსაწოდებელი პასუხი/მტკიცებულება:** deterministic precedence წესები და conflict sample.

### SYNC-07 — მრავალჯგუფიანობა — P0 / Phase 9B blocker

**კითხვა IT-სთვის:** შეიძლება თანამშრომელი ერთდროულად რამდენიმე შესაბამის AD
ჯგუფში იყოს? თუ კი, რომელი არსებული attribute ან კომპანიის მიერ დამტკიცებული
deterministic precedence განსაზღვრავს პორტალის ერთ ძირითად ჯგუფს? თუ ასეთი წყარო
არ არსებობს, დაადასტურეთ ეს და მოგვაწოდეთ conflict owner/quarantine პროცესი;
პორტალი ჯგუფს თვითნებურად არ გამოიცნობს.

**მოსაწოდებელი პასუხი/მტკიცებულება:** precedence, conflict owner, quarantine/fail-closed ქცევა.

### SYNC-08 — manager/leader-ის წარმოდგენა — P0

**კითხვა IT-სთვის:** „ჯგუფის უფროსი“ სად ჩანს — AD group membership-ში,
`manager` attribute-ში, OU-ში, custom group-ში თუ სხვა სისტემაში? ერთი ჯგუფის
PRIMARY და ACTING ხელმძღვანელი როგორ განვასხვაოთ?

**მოსაწოდებელი პასუხი/მტკიცებულება:** mapping rule, sample objects და acting leader-ის lifecycle.

### SYNC-09 — ცვლილებების feed — P0 / Phase 9B blocker

**დადგენილი პროდუქტული სამიზნე:** deactivation, ჯგუფის ცვლილება და დეპარტამენტის
ცვლილება ყველა მაქსიმუმ 5–15 წუთში უნდა აისახოს.

**კითხვა IT-სთვის:** უნდა ვაკეთოთ full scan, `uSNChanged`, DirSync cookie,
change notification თუ სხვა feed? რა სიხშირით შეიძლება კითხვა და რა rate/size
limit არსებობს? რომელი გზა მოგვაწვდის ყველა ამ ცვლილებას 5–15 წუთში ისე, რომ
AD/auth service და პორტალი მნიშვნელოვნად არ დაიტვირთოს? უპირატესია event/delta
გზა; თუ მხოლოდ polling შეიძლება, რა არის ყველაზე სწრაფი უსაფრთხო interval
დაახლოებით 600 მომხმარებელზე?

**მოსაწოდებელი პასუხი/მტკიცებულება:** არჩეული მექანიზმი, polling interval, page size, timeout,
rate limit, საჭირო privilege და 5–15 წუთიანი საერთო სამიზნის დატვირთვის შეფასება.
თუ სამიზნე შეუძლებელია, გაზომილი მიზეზი და ყველაზე სწრაფი უსაფრთხო interval.

### SYNC-10 — რამდენიმე DC და delta cursor — P0

**კითხვა IT-სთვის:** feed ერთ კონკრეტულ DC-ზე უნდა მიებას თუ VIP-ზე? თუ DC
შეიცვალა, ძველი `uSNChanged`/DirSync cursor ვარგა? როგორ ავიცილოთ დაკარგული ან
ორმაგი ცვლილება replication lag-ისას?

**მოსაწოდებელი პასუხი/მტკიცებულება:** DC affinity/failover კონტრაქტი, replication SLA და full
reconciliation fallback.

### SYNC-11 — deactivation semantics — P0 / Phase 9B blocker

**დადგენილი პროდუქტული სამიზნე:** გათიშული თანამშრომლის portal access მაქსიმუმ
5–15 წუთში უნდა დაიხუროს, თუ ეს ინფრასტრუქტურას მნიშვნელოვნად არ ამძიმებს.

**კითხვა IT-სთვის:** თანამშრომლის წასვლა რას ნიშნავს: disabled bit, expiry,
OU-ში გადატანა, group removal თუ deletion? რომელი მოვლენა გვაძლევს საიმედო
deactivation signal-ს, როგორ მივიღებთ მას 5–15 წუთში და როგორ გაუქმდება უკვე
გაცემული portal session/token? თუ ამ SLA-ს უსაფრთხოდ ვერ ასრულებთ, რა არის
ყველაზე სწრაფი შესაძლებელი ვადა და რა კონკრეტული დატვირთვა/შეზღუდვა გვიშლის ხელს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** event → portal action/session revocation → მაქსიმალური
დაყოვნების ცხრილი, დატვირთვის შეფასება და პასუხისმგებელი owner.

### SYNC-12 — deletion და tombstone — P0

**კითხვა IT-სთვის:** წაშლილი AD object რამდენ ხანს რჩება tombstone/recycle bin-ში,
შეგვიძლია stable ID-ის წაკითხვა და როგორ განვასხვაოთ deletion დროებითი read
failure-ისგან?

**მოსაწოდებელი პასუხი/მტკიცებულება:** tombstone lifetime, recycle-bin policy და failure handling.

### SYNC-13 — rename/move/merge/split — P1

**კითხვა IT-სთვის:** როგორ ხდება დეპარტამენტის/ჯგუფის rename, OU move, merge და
split? რომელი change ticket ან attribute გვანიშნებს, რომ ძველი object ახალი
object-ით შეიცვალა?

**მოსაწოდებელი პასუხი/მტკიცებულება:** ოთხივე scenario-ს business/technical პროცესი და sample.

### SYNC-14 — orphan და conflict-ების გადაწყვეტა — P0

**კითხვა IT-სთვის:** ვინ და რა ვადაში აგვარებს user-without-team,
unknown-department, duplicate stable ID, multiple-primary, deleted-team-with-users
და სხვა reconciliation შეცდომებს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** severity, owner, SLA, notification channel და fail-closed
ქცევა თითო conflict-ზე.

### SYNC-15 — sync schedule, blackout და retry — P1

**კითხვა IT-სთვის:** რა სიხშირით ეშვება sync, არის AD maintenance blackout,
რამდენჯერ ვცადოთ retry, როდის გადავიდეს alert-ში და რამდენი ხნის stale data
არის დასაშვები?

**მოსაწოდებელი პასუხი/მტკიცებულება:** schedule, timeout/retry/backoff, stale-data threshold და SLA.

### SYNC-16 — dry-run, audit და მონაცემთა მინიმიზაცია — P0

**კითხვა IT-სთვის:** არჩეული AD მექანიზმით რომელი attributes-ის წაკითხვაა
ტექნიკურად შესაძლებელი/დაშვებული, და რა სავალდებულო კომპანიის logging/retention/
redaction პოლიტიკა ეხება sync service-ს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** ტექნიკურად ხელმისაწვდომი attribute-ები და კომპანიის
სავალდებულო log redaction/retention/access policy. პროექტის საბოლოო allowlist-ს
პროდუქტის მფლობელი და DPO/Security ამ ფაქტების საფუძველზე განსაზღვრავენ.

---

## 6. Kubernetes, ქსელი, DNS და TLS

### PLAT-01 — კლასტერის ზუსტი პლატფორმა — P0

**კითხვა IT-სთვის:** რა Kubernetes დისტრიბუცია/ვერსიაა, ვინ მართავს მას, რა
upgrade cadence აქვს და რომელი API/PodSecurity შეზღუდვებია ჩართული?

**მოსაწოდებელი პასუხი/მტკიცებულება:** version, distribution, support window და policy summary.

### PLAT-02 — გარემოებისა და namespace-ების გამიჯვნა — P1

**კითხვა IT-სთვის:** dev/test/staging/prod ცალკე cluster/namespace/network-შია?
ვინ ხედავს თითოეულს და შეიძლება თუ არა non-prod workload-მა prod secret/DB-ს
მიაღწიოს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** topology, namespace names, RBAC და isolation evidence.

### PLAT-03 — registry და image supply chain — P1

**კითხვა IT-სთვის:** რომელი container registry გამოიყენება, ვინ push/pull-ს
აკეთებს, არის vulnerability scan, image signing/verification, retention და
immutable tag policy?

**მოსაწოდებელი პასუხი/მტკიცებულება:** registry URL/name, auth გზა, scan/sign policy და tag rule.

### PLAT-04 — deployment ფორმატი და owner — P0

**კითხვა IT-სთვის:** Helm, Kustomize, raw manifests, Argo CD/Flux თუ სხვა GitOps
გამოიყენება? ვინ შექმნის/შეინახავს production manifests-ს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** repository/path, tool/version, owner და promotion წესი.

### PLAT-05 — replica, HA და autoscaling — P0

**კითხვა IT-სთვის:** `RTO = 1 საათის` მოთხოვნის შესასრულებლად frontend/backend
რამდენ replica-ზე უნდა გაეშვას, საჭიროა თუ არა HPA და რა min/max/trigger იქნება?
აღწერეთ node/pod failure-ის დროს traffic failover და, თუ ერთი replica დროებითი
degraded mode-ია, მისი მაქსიმალური ხანგრძლივობა და alert/escalation.

**მოსაწოდებელი პასუხი/მტკიცებულება:** replica/HPA values, availability target და scaling trigger.

### PLAT-06 — Redis საერთო rate limiter-ისთვის — P0 თუ backend 2+ replicaა

**კითხვა IT-სთვის:** Java pod-ებიდან ხელმისაწვდომია production-grade Redis?
არის TLS/auth/HA, რომელი endpoint/DB index და ვინ მართავს outage/rotation-ს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** service endpoint, security/HA policy, eviction policy და
failure behavior. Secret მნიშვნელობა არა.

### PLAT-07 — Ingress, Service და routing — P0

**კითხვა IT-სთვის:** რომელი ingress controller/class გამოიყენება, რა ერქმევა
frontend/backend Service-ს, რომელ პორტებზე, და როგორ route-დება `/api` და
`/uploads`?

**მოსაწოდებელი პასუხი/მტკიცებულება:** სანიტიზებული ingress/service მაგალითი და საბოლოო names.

### PLAT-08 — production DNS და TLS — P0

**კითხვა IT-სთვის:** რა იქნება portal-ის FQDN, ვინ ქმნის DNS ჩანაწერს, სად
სრულდება TLS, რომელი CA გასცემს certificate-ს, ვინ/როგორ განაახლებს და არის თუ
არა HTTP→HTTPS redirect/HSTS?

**მოსაწოდებელი პასუხი/მტკიცებულება:** FQDN, DNS/TLS owner, certificate lifecycle და renewal alert.

### PLAT-09 — trusted proxy-ის ზუსტი წყარო — P0

**კითხვა IT-სთვის:** backend pod რა source IP/CIDR-ს ხედავს ingress/proxy-დან?
რომელი proxy chain წერს `X-Forwarded-For`, და backend-ზე პირდაპირი წვდომა
NetworkPolicy/firewall-ით აკრძალულია?

**მოსაწოდებელი პასუხი/მტკიცებულება:** ყველაზე ვიწრო `TRUSTED_PROXIES` მნიშვნელობა, hop chain და
direct-access denial test.

### PLAT-10 — pod-იდან აუცილებელი ქსელის გზები — P0

**კითხვა IT-სთვის:** frontend/backend pod-ებიდან რომელი DNS/FQDN/IP/პორტებია
დაშვებული Oracle, AD/IdP/LDAPS, Redis, registry, object storage, NTP და monitoring
მიმართულებით? არის proxy ან custom CA საჭირო?

**მოსაწოდებელი პასუხი/მტკიცებულება:** ingress/egress matrix და firewall/NetworkPolicy ticket-ები.

### PLAT-11 — resource limits და JVM — P1

**კითხვა IT-სთვის:** CPU/memory request/limit, namespace quota, ephemeral storage
და OOM/restart policy რა იქნება? export-ის პიკური მეხსიერება გათვალისწინებულია?

**მოსაწოდებელი პასუხი/მტკიცებულება:** values, sizing საფუძველი და load-test-ის სამიზნე.

### PLAT-12 — probes, graceful shutdown და rollout — P1

**კითხვა IT-სთვის:** readiness/liveness/startup probe-ები რას ამოწმებს,
termination grace რამდენია, ingress როდის წყვეტს ტრაფიკს და rolling deployment
დროს ძველი/ახალი ვერსია რამდენ ხანს თანაარსებობს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** probe paths/timings, rollout strategy და drain evidence.

### PLAT-13 — singleton scheduler-ები — P1

**კითხვა IT-სთვის:** cleanup/sync მსგავსი პერიოდული სამუშაო ერთი pod-იდან უნდა
იმუშაოს, CronJob-ად, თუ distributed lock-ით? ვინ აკონტროლებს missed/duplicate run-ს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** არჩეული scheduler pattern, owner და alert.

### PLAT-14 — დრო და NTP — P1

**კითხვა IT-სთვის:** node-ები, Oracle, AD და SIEM ერთ NTP წყაროზეა? application
UTC-ში იმუშავებს თუ Asia/Tbilisi-ში და ვინ აკონტროლებს clock drift-ს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** timezone/NTP standard და drift alert threshold.

---

## 7. CI/CD, release და schema migration

### REL-01 — CI/CD პლატფორმა და runner — P0

**კითხვა IT-სთვის:** Jenkins/GitLab/Azure DevOps/GitHub Actions თუ სხვა სისტემა
deploy-ს გააკეთებს? runner-ს აქვს registry/Kubernetes/Oracle staging ქსელური
წვდომა და ვინ მართავს მას?

**მოსაწოდებელი პასუხი/მტკიცებულება:** platform, runner topology, owner და onboarding პროცესი.

### REL-02 — branch, review და artifact promotion — P1

**კითხვა IT-სთვის:** რა branch protection, required checks/approvals და release
tag წესია? ერთი და იგივე immutable image გადადის dev→staging→prod თუ prod-ზე
თავიდან build-დება?

**მოსაწოდებელი პასუხი/მტკიცებულება:** pipeline diagram, required gates და tag/digest policy.

### REL-03 — secrets CI/CD-ში — P0

**კითხვა IT-სთვის:** pipeline secrets სად ინახება, ვინ კითხულობს, masking არის,
როგორ როტირდება და შეიძლება თუ არა deploy short-lived identity-ით static
credential-ის გარეშე?

**მოსაწოდებელი პასუხი/მტკიცებულება:** secret store/integration, RBAC, rotation და audit.

### REL-04 — Flyway migration-ის owner და execution — P0

**კითხვა IT-სთვის:** Flyway-ს ვინ და საიდან უშვებს, dedicated migration account
აქვს თუ runtime user, როგორ გარანტირდება ერთჯერადი გაშვება და სად ინახება log/
SQL artifact/approval?

**მოსაწოდებელი პასუხი/მტკიცებულება:** migration runbook, account/role names, least-privilege
grant list და audit location; credentials არა.

### REL-05 — V36/V36.1-ის გარემოთა მდგომარეობა — P0

**კითხვა IT-სთვის:** dev/test/staging/prod-ში რომელი Flyway version/checksum არის
რეალურად გამოყენებული? სად არის ძველი V36.1 და სად — გასწორებული? არსებობს
schema drift ან ხელით შესრულებული DDL/DML?

**მოსაწოდებელი პასუხი/მტკიცებულება:** `flyway info/validate` შედეგი და schema history export;
`repair` ან `clean` shared გარემოზე არ გაუშვან.

### REL-06 — backfill და V37 preflight — P0

**კითხვა IT-სთვის:** ვინ გაუშვებს org backfill-ს, სად შეინახება reconciliation
report და ვინ მოაწერს ხელს, რომ duplicate/null/external-ID/primary-collision
preflight სუფთაა V37-მდე?

**მოსაწოდებელი პასუხი/მტკიცებულება:** owner, environment order, report location, approvers და
stop conditions.

### REL-07 — deploy/rollback სტრატეგია — P0

**კითხვა IT-სთვის:** rolling, blue/green თუ maintenance deploy გამოიყენება?
ვინ რთავს rollout flags-ს, ვინ აბრუნებს უკან და რამდენ წუთში უნდა დასრულდეს
rollback? როგორ ვიქცევით, თუ schema უკვე contract ეტაპზეა?

**მოსაწოდებელი პასუხი/მტკიცებულება:** tested rollback runbook, authority, RTO და V37-ის შემდეგ
ცალკე DB recovery plan. იხ. `docs/ROLLOUT_ROLLBACK_KA.md`.

### REL-08 — dependency/security checks — P1

**კითხვა IT-სთვის:** pipeline-ში საჭიროა SAST, dependency/container scan, SBOM,
license scan, secret scan და DAST? რა severity ბლოკავს release-ს და ვინ მართავს
exception-ს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** tools, threshold, SLA და report retention.

---

## 8. Oracle 19c, ანგარიშები და კავშირები

### DB-01 — ზუსტი Oracle inventory — P0

**კითხვა DBA-სთვის:** თითო გარემოს exact version/edition/RU, CDB/PDB name,
service name, charset/NCHAR charset, timezone file version და patch/support
თარიღები რა არის?

**მოსაწოდებელი პასუხი/მტკიცებულება:** სანიტიზებული inventory query output თითო გარემოზე.

### DB-02 — topology და availability — P0

**კითხვა DBA-სთვის:** single instance, RAC, Data Guard/standby თუ სხვა topologyა?
failover ავტომატურია თუ ხელით, რა service/VIP იცვლება და აპმა რა უნდა გააკეთოს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** topology diagram, failover owner/SLA და JDBC connect
descriptor-ის სანიტიზებული ფორმა.

### DB-03 — endpoint, DNS და encrypted transport — P0

**კითხვა DBA/Network-სთვის:** JDBC-მ გამოიყენოს TCP/native encryption თუ TCPS,
რომელი FQDN/პორტი/service name, რა CA/wallet სჭირდება და encryption/checksum
`REQUIRED` არის თუ მხოლოდ `REQUESTED`?

**მოსაწოდებელი პასუხი/მტკიცებულება:** JDBC URL pattern, certificate chain/wallet delivery,
sqlnet policy და encrypted-session verification; secrets არა.

### DB-04 — runtime, owner და migration ანგარიშების გამიჯვნა — P0

**კითხვა DBA-სთვის:** schema owner, application runtime user და migration user
ცალ-ცალკეა? schema owner locked არის? runtime account-ს DDL/`ANY`/DBA privilege
ხომ არ აქვს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** account/role names და `DBA_SYS_PRIVS`, `DBA_ROLE_PRIVS`,
`DBA_TAB_PRIVS`-ის სანიტიზებული export.

### DB-05 — least-privilege და PUBLIC grants — P0

**კითხვა DBA-სთვის:** runtime/migration accounts-ის ზუსტი grants რა არის? აქვთ
`DBA`, `SELECT ANY TABLE`, `EXECUTE ANY PROCEDURE`, `WITH ADMIN/GRANT OPTION` ან
საშიში `PUBLIC` package access?

**მოსაწოდებელი პასუხი/მტკიცებულება:** privilege review, გამონაკლისების business justification
და remediation date.

### DB-06 — credential lifecycle — P0

**კითხვა DBA/Secrets გუნდს:** Oracle credentials სად ინახება, როგორ მიეწოდება
pod-ს, rotation დროს საჭიროა restart, რა grace/dual-password გზა გვაქვს და ვინ
აკონტროლებს expiry/lock-ს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** store/injection/rotation runbook და alert SLA.

### DB-07 — sessions/processes და connection pool budget — P0

**კითხვა DBA-სთვის:** production `processes`, `sessions`, profile limits და სხვა
აპების დატვირთვა რა არის? backend replica × `DB_POOL_MAX_SIZE=30` რამდენად
დაშვებულია და რა headroom უნდა დავტოვოთ?

**მოსაწოდებელი პასუხი/მტკიცებულება:** current/peak usage, agreed per-replica pool, total budget
და alert threshold.

### DB-08 — idle timeout და Hikari lifetime — P0

**კითხვა DBA/Network-სთვის:** firewall/Oracle რა დროის შემდეგ კლავს idle ან
მაქსიმალური ასაკის connection-ს? რა `maxLifetime`, keepalive და validation
პარამეტრები შევუთანხმოთ Hikari-ს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** ყველა შუამავალი timeout და შეთანხმებული pool settings.

### DB-09 — tablespace, BLOB ზრდა და quota — P1

**კითხვა DBA-სთვის:** `stored_files` BLOB-ების tablespace, თავისუფალი ადგილი,
autoextend/maxsize, quota და ზრდის alert რა არის? 10–20 GB/წელი შეფასება მისაღებია?

**მოსაწოდებელი პასუხი/მტკიცებულება:** capacity/forecast, thresholds, owner და expansion SLA.

### DB-10 — TDE და key/wallet მართვა — P1

**კითხვა DBA/Security-სთვის:** datafiles/tablespace/backups TDE-ით არის
დაშიფრული? ლიცენზია ფარავს? wallet/HSM სად არის, ვინ ხსნის/აროტირებს და wallet
backup DB backup-ისგან ცალკე ინახება?

**მოსაწოდებელი პასუხი/მტკიცებულება:** encryption scope, license confirmation, key owner,
rotation/restore test; key/password არა.

### DB-11 — Unified Auditing და DDL/grant audit — P1

**კითხვა DBA/SOC-სთვის:** pure/mixed Unified Auditing რომელ რეჟიმშია? იწერება
logon failure, SYSDBA, DDL, GRANT/REVOKE და migration account-ის მოქმედებები?
ვინ ნახულობს და ვის შეუძლია purge?

**მოსაწოდებელი პასუხი/მტკიცებულება:** enabled policies, retention/SIEM forwarding, purge owner
და test event.

### DB-12 — performance/space monitoring — P1

**კითხვა DBA-სთვის:** ვინ აკვირდება tablespace-ს, sessions/processes-ს, blocking
locks-ს, failed logins-ს, alert log-ს, slow SQL-სა და backup failures-ს? AWR/ASH
ლიცენზია/წვდომა გვაქვს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** monitoring tools, thresholds, dashboards, on-call და SLA.

### DB-13 — NLS/Georgian/UTC თავსებადობა — P1

**კითხვა DBA-სთვის:** database charset `AL32UTF8`-ია? NLS sort/compare settings,
session timezone და timestamp policy ქართულ ტექსტსა და audit timestamps-ს სწორად
ინახავს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** NLS/charset/timezone query output და ქართული round-trip test.

---

## 9. ფაილები, backup და disaster recovery

### DR-01 — backup-ის სრული scope — P0

**კითხვა DBA/Backup გუნდს:** backup მოიცავს application schema-ს, Flyway history-ს,
`stored_files` BLOB-ებს, indexes/constraints-სა და საჭირო TDE wallet/key material-ს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** backup policy/job scope და ბოლო წარმატებული run evidence.

### DR-02 — RPO — P0

**დადგენილი პროდუქტული სამიზნე:** `RPO = 0` — პორტალის მიერ წარმატებით
შენახულად დადასტურებული transaction ავარიისას არ უნდა დაიკარგოს.

**კითხვა IT/DBA/Business continuity-სთვის:** უზრუნველყოფს თუ არა არსებული Oracle
ინფრასტრუქტურა ამ სამიზნეს ყველა შეთანხმებული failure scenario-სთვის? აღწერეთ
Data Guard/standby protection mode და transport (`SYNC`/`ASYNC`, `AFFIRM` და
სხვა), primary/standby failure domain-ები, automatic/manual failover და ის მომენტი,
როდესაც აპს commit წარმატებულად უბრუნდება. მხოლოდ პერიოდული backup `RPO = 0`-ს
არ ამტკიცებს.

თუ `RPO = 0` არსებული ინფრასტრუქტურით შეუძლებელია, რა ტექნოლოგია/რესურსი/ფასი
დასჭირდება და რა RPO ვარიანტებია რეალურად მიღწევადი?

**მოსაწოდებელი პასუხი/მტკიცებულება:** არქიტექტურის დიაგრამა, protection/transport config-ის
სანიტიზებული მტკიცებულება, failure scenario → შესაძლო data loss ცხრილი, ბოლო
failover/data-loss test და `RPO = 0`-ის ღირებულება/შეზღუდვები. თუ სამიზნე ვერ
სრულდება, უფრო სუსტი RPO ავტომატურად არ დამტკიცდება — არჩევანს პროექტის
მფლობელი აკეთებს.

### DR-03 — RTO — P0

**დადგენილი პროდუქტული სამიზნე:** `RTO = 1 საათი` — დაუგეგმავი გათიშვის შემდეგ
თანამშრომლებს პორტალის გამოყენება მაქსიმუმ ერთ საათში უნდა შეეძლოთ.

**კითხვა IT/Business continuity-სთვის:** არსებული Oracle/Kubernetes/ingress/DNS/
secret/certificate restore ან failover პროცესებით მიიღწევა თუ არა ეს RTO ყველა
შეთანხმებული failure scenario-სთვის? რომელი ნაბიჯებია ავტომატური, რომელი ხელით,
ვინ იღებს გადაწყვეტილებას და სად იწყება/მთავრდება ერთსაათიანი დროის ათვლა?

**მოსაწოდებელი პასუხი/მტკიცებულება:** ბოლო end-to-end restore/failover ტესტის დროების breakdown,
failure scenario → recovery time ცხრილი, owner-ები, escalation/runbook და
`RTO = 1 საათი`-ის შესრულების მტკიცებულება. თუ სამიზნე ვერ სრულდება, მიზეზი,
საჭირო რესურსი/ფასი და რეალურად მიღწევადი ვარიანტები; უფრო ნელი RTO ავტომატურად
არ დამტკიცდება.

### DR-04 — restore test — P0

**კითხვა Backup/DBA-სთვის:** ბოლოს როდის აღადგინეთ backup isolated გარემოში და
შეამოწმეთ BLOB download, login, audit chain და Flyway state? რამდენ ხანს გაგრძელდა
სრული პროცესი და ჩაეტია თუ არა `RTO = 1 საათში`?

**მოსაწოდებელი პასუხი/მტკიცებულება:** ბოლო restore drill report, შედეგი, დრო და შემდეგი test date.

### DR-05 — off-host/off-site/immutable ასლები — P0

**კითხვა Backup/Security-სთვის:** backup production DB-სგან და DBA-ს ყოველდღიური
ანგარიშისგან განცალკევებით ინახება? არის off-site/immutable copy და ransomware-ის
შემთხვევაში delete protection?

**მოსაწოდებელი პასუხი/მტკიცებულება:** copy topology, encryption, immutability/retention და restore
access control.

### DR-06 — retention და purge — P1

**დადგენილი პროდუქტული სამიზნე:** წაშლილი სტატია, ვიდეო და მათზე მიბმული
დანართები 30 დღე უნდა იყოს აღდგენადი, შემდეგ კი აქტიური სისტემიდან სამუდამოდ
წაიშალოს. audit/compliance evidence ამ purge-ში არ შედის და ცალკე retention-ს
ელოდება. გენერირებული export ფაილი და მისი დროებითი job ჩანაწერი შექმნიდან
1 საათში უნდა წაიშალოს; ეს ვადა მიმდინარე Java ქცევას ემთხვევა. ბიზნეს audit
rows პირველი 1 წელი პორტალის Oracle-ში რჩება; უფრო ძველი ჩანაწერები უნდა გადავიდეს
IT-ის ცალკე დაცულ მრავალწლიან არქივში და მხოლოდ წარმატებული verification-ის
შემდეგ გაიწმინდოს აქტიური Oracle-დან, legal hold-ის გათვალისწინებით. თანამშრომლის
პროფილში „ბოლოს ნანახი მასალები“ ბოლო 90 დღეს აჩვენებს; ეს UX window არ უდრის
ქვედა დონის view/search log retention-ს.

**კითხვა IT/DPO-სთვის:** რა სავალდებულო კომპანიის/იურიდიული retention minimum/
maximum და legal-hold წესები არსებობს content, attachments, exports, audit/search/
view logs, compliance evidence, sync reports და backups-ისთვის? ეწინააღმდეგება თუ
არა 30-დღიანი content/attachment recovery window რომელიმე სავალდებულო წესს?
ტექნიკურად როგორ სრულდება purge primary/replica/cache/search index/object storage/
backup ასლებში და როგორ ვინარჩუნებთ evidence-ისთვის საჭირო მინიმალურ metadata-ს?

ბიზნეს audit-ისთვის რომელი IT-managed გარე პლატფორმა გვაქვს — SIEM, immutable/
WORM object storage, archive database თუ სხვა? როგორ გავიტანოთ ერთ წელზე ძველი
rows ისე, რომ hash-chain/sequence, timestamps, actor/action details და მთლიანობის
მტკიცებულება არ დაირღვეს? რა encryption, წვდომა, legal hold, წლიური ღირებულება,
retrieval SLA და საერთო retention ვარიანტებია?

**მოსაწოდებელი პასუხი/მტკიცებულება:** სავალდებულო საზღვრები, legal-hold/backup გამონაკლისები,
30-დღიანი აღდგენისა და შემდგომი purge-ის ტექნიკური შესაძლებლობა; audit archive
platform, immutable/encryption/access შესაძლებლობები, export/verification/
retrieve/delete runbook, owner, SLA, capacity და ფასი. ამ საზღვრებში პორტალის
content/export-ის პროდუქტულ ვადებს პროექტის მფლობელი ირჩევს, ხოლო audit archive-ის
საერთო ვადა კომპანიის IT/Security/DPO/იურიდიული compliance policy-დან უნდა
მოვიდეს — პროექტი მას თვითონ არ ადგენს.

### DR-07 — S3/RWX ალტერნატივა — P1

**დადგენილი პროდუქტული მოთხოვნები:** ფაილები მხოლოდ ავტორიზებულ თანამშრომლებს
ეხსნებათ; წაშლილი ფაილი 30 დღეა აღდგენადი; ინფრასტრუქტურამ `RPO = 0` და
`RTO = 1 საათი` უნდა დააკმაყოფილოს.

**კითხვა Platform/Storage/DBA-სთვის:** ამ მოთხოვნებისთვის ტექნიკურად რომელი
საცავია კომპანიის მიერ მხარდაჭერილი და რეკომენდებული — მიმდინარე Oracle BLOB,
S3-compatible MinIO/Ceph თუ RWX StorageClass? შეადარეთ availability, failure
domain, TLS/auth, quota/capacity, versioning, 30-დღიანი recovery/lifecycle,
backup/restore, malware scanning, monitoring, support owner და ფასი. არსებობს
თუ არა S3/RWX რეალურად ყველა საჭირო გარემოში?

**მოსაწოდებელი პასუხი/მტკიცებულება:** ერთი ტექნიკური რეკომენდაცია და decision record; service
endpoint/class, SLA, quota, auth model, backup/restore test, migration საჭიროება,
capacity estimate, ფასი და owner. თუ ვერცერთი ვარიანტი ვერ ასრულებს პროდუქტულ
მოთხოვნებს, ზუსტად მიუთითეთ რომელი მოთხოვნაა შესაცვლელი — მას მხოლოდ პროექტის
მფლობელი გადაწყვეტს.

### DR-08 — malware-scanning ინფრასტრუქტურა — P1

**კითხვა Security/Platform-სთვის:** კომპანიაში არსებობს ატვირთული PDF/სურათის
antivirus/CDR scanning service ან სავალდებულო scanning standard? რომელი engine/
API, მაქსიმალური ზომა, network endpoint, quarantine და false-positive პროცესი აქვს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** scanning capability, სავალდებულო policy, network/auth
facts და SLA. `/uploads` ავტორიზაცია პროდუქტის მფლობელის გადაწყვეტილებაა.

---

## 10. Secrets, logging, monitoring და incident response

### OPS-01 — ცენტრალური secret store — P0

**კითხვა Security/Platform-სთვის:** Vault, External Secrets, Sealed Secrets თუ
სხვა სისტემა გვაქვს? როგორ შედის Oracle/JWT/LDAP/Redis/object-storage secret
pod-ში და rotation როგორ აისახება running pod-ზე?

**მოსაწოდებელი პასუხი/მტკიცებულება:** product/integration, RBAC, audit, rotation/reload runbook.

### OPS-02 — JWT secret lifecycle — P1

**კითხვა Security-სთვის:** JWT signing-ზე რა სავალდებულო კომპანიის crypto/key
management standard არსებობს, HS256/RS256-დან რომელს უჭერს არსებული secret/KMS
ინფრასტრუქტურა მხარს და rotation/revocation ტექნიკურად როგორ სრულდება?

**მოსაწოდებელი პასუხი/მტკიცებულება:** სავალდებულო security standard, Security/
Architecture-ის მიერ რეკომენდებული კონკრეტული მექანიზმი, rotation/revocation
შესაძლებლობა, migration გავლენა და owner.

### OPS-03 — certificate expiry მართვა — P1

**კითხვა IT-სთვის:** portal TLS, IdP signing, LDAPS CA, Oracle TCPS wallet და
registry certificates სად აღირიცხება, რამდენი დღით ადრე მოდის alert და ვინ
ანახლებს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** certificate inventory, expiry dates, 30/60/90-day alerts
და owner.

### OPS-04 — log aggregation — P1

**განსხვავება:** ეს პუნქტი ეხება Java/Kubernetes-ის ტექნიკურ runtime log-ებს.
ისინი ბიზნეს `audit_logs` არ არის და Oracle-ში ერთი წლის შენახვის წესს არ
ექვემდებარება; pod-ის დროებით დისკზე დარჩენის ნაცვლად თავიდანვე ცენტრალურად უნდა
იგზავნებოდეს.

**კითხვა Platform/SOC-სთვის:** stdout/stderr სად იგზავნება — Loki/ELK/Splunk/
SIEM? რა ფორმატი, retention, search access და clock normalization იქნება?
კომპანიის პოლიტიკა აძლევს თუ არა პორტალის `SYSTEM_ADMIN`-ს ამ log-ების search/
export უფლებას; თუ კი, რომელი SSO role, approval flow და audited export ფორმატი
გამოიყენება? თუ არა, რომელი IT/SOC მოთხოვნის პროცესი უზრუნველყოფს საჭირო
ამონაწერს? runtime log Oracle-ში არ უნდა დაკოპირდეს მხოლოდ ამ წვდომის შესაქმნელად.

**მოსაწოდებელი პასუხი/მტკიცებულება:** collector/config, index/labels, retention, access და sample
search.

### OPS-05 — log-ში PII/secrets-ის დაცვა — P1

**კითხვა Security/DPO-სთვის:** რომელი ველები არ უნდა მოხვდეს log-ში, ვინ ამოწმებს
redaction-ს და რა ხდება accidental secret/PII leak-ისას?

**მოსაწოდებელი პასუხი/მტკიცებულება:** logging classification/redaction policy და incident procedure.

### OPS-06 — metrics და Actuator — P1

**კითხვა Monitoring-სთვის:** Prometheus/Micrometer გამოიყენება? როგორ ხდება
ახალი Spring Boot აპის onboarding, რომელი metrics/labels აკრძალულია ან
სავალდებულო, და Actuator endpoint-ს ვინ/რა ქსელიდან ხედავს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** scrape/auth/network design, platform requirements და
dashboard/alert შექმნის პროცესი. პროექტისთვის სასურველ metrics-ს Engineering/
მფლობელი განსაზღვრავს.

### OPS-07 — alert-ები და on-call SLA — P0

**კითხვა Monitoring/Service Desk-სთვის:** ვინ იღებს alert-ს და რა threshold/SLA
აქვს: app down, 5xx spike, login failures, DB pool exhaustion, Oracle unavailable,
tablespace full, backup failed, AD sync stale/conflict, certificate expiry და
audit-chain failure?

**მოსაწოდებელი პასუხი/მტკიცებულება:** alert matrix: signal → threshold → recipient → severity → SLA.

### OPS-08 — synthetic monitoring — P2

**კითხვა Monitoring-სთვის:** შიდა ქსელიდან შეგვიძლია პერიოდულად შევამოწმოთ DNS,
TLS, frontend, `/api/health`, login redirect და ერთი read-only API ისე, რომ test
მონაცემი არ შევქმნათ?

**მოსაწოდებელი პასუხი/მტკიცებულება:** probe location, test identity, frequency და alert.

### OPS-09 — security event monitoring — P1

**კითხვა SOC-სთვის:** რომელი სავალდებულო/ხელმისაწვდომი monitoring use-case ფარავს
შემდეგ მოვლენებს: brute force, disabled-user access, SYSTEM_ADMIN login/change,
permission/leadership override, break-glass, unexpected DDL/grant, AD mass-
deactivation და export spike? თითოეულზე მოგვაწოდეთ detection source, threshold,
severity, responder და escalation SLA.

**მოსაწოდებელი პასუხი/მტკიცებულება:** use-case list, severity, correlation rule და responder.

### OPS-10 — vulnerability/penetration test — P1

**კითხვა Security-სთვის:** go-live-მდე საჭიროა penetration test, DAST, code
review ან infrastructure review? ვინ აკეთებს, რა scope-ით და რა severity ბლოკავს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** scope, vendor/team, schedule, remediation SLA და retest.

### OPS-11 — incident response და session kill — P0

**კითხვა Security/Service Desk-სთვის:** ანგარიშის/secret-ის კომპრომეტაციისას ვინ
თიშავს მომხმარებელს, ყველა JWT session-ს, LDAP account-ს ან მთლიან portal-ს?
ვის აქვს feature flag/ingress deny/replica scale-to-zero უფლებამოსილება?

**მოსაწოდებელი პასუხი/მტკიცებულება:** severity/contact tree, kill-switch runbook და audit trail.

### OPS-12 — support model — P1

**კითხვა IT-სთვის:** L1/L2/L3 ვინ არის, რა საათებში მუშაობს support, სად ხსნის
მომხმარებელი ticket-ს, რა ინფორმაცია დაურთოს და როგორ ესკალირდება software bug
engineering-მდე?

**მოსაწოდებელი პასუხი/მტკიცებულება:** support catalog, SLA, escalation path და runbook links.

---

## 11. Production cutover და მიღება

### CUT-01 — production change window-ის ინფრასტრუქტურული პირობები — P0

**კითხვა IT/Ops-სთვის:** production deploy-ისთვის რომელი maintenance window-ებია
ხელმისაწვდომი, რამდენი lead time/approval სჭირდება და რომელ პერიოდებშია
ინფრასტრუქტურული change freeze?

**მოსაწოდებელი პასუხი/მტკიცებულება:** ხელმისაწვდომი window-ები, lead time, mandatory approvals
და freeze calendar.

### CUT-02 — fixture, backfill და AD sync-ის განსხვავება — P0

**კითხვა IT/Ops-სთვის:** წერილობით ვადასტურებთ, რომ fixture seeder მხოლოდ
dev/test მონაცემია, org backfill არსებულ მონაცემს აკავშირებს, AD sync კი
production source of truth-ს პერიოდულად კითხულობს? ვინ უშვებს თითოეულს და სად?

**მოსაწოდებელი პასუხი/მტკიცებულება:** command/job → allowed environments → owner → approval
matrix. Production-ში fixture seeder აკრძალული უნდა იყოს.

### CUT-03 — AD sync dry-run-ის ინფრასტრუქტურული მხარდაჭერა — P0

**კითხვა IT-სთვის:** dry-run-ისთვის რომელ AD snapshot/test OU-სა და ქსელურ
წვდომას მოგვცემთ, რა query/rate შეზღუდვაა და სად შეიძლება report-ის უსაფრთხოდ
შენახვა?

**მოსაწოდებელი პასუხი/მტკიცებულება:** data source/snapshot, network window, rate limits, report
storage/access და IT contact. მისაღებ diff threshold-ს პროექტის მფლობელი ადგენს.

### CUT-04 — ძველი Python პორტალის 30-დღიანი read-only ფანჯარა — P0

**დადგენილი პროდუქტული გადაწყვეტილება:** წარმატებული Java/Angular cutover-ის
შემდეგ Python პორტალი 30 დღე რჩება მხოლოდ სანახავად; ყველა write იკეტება და
ერთადერთი მოქმედი source of truth Java/Oracle ხდება. 30 დღის შემდეგ Python
deployment ითიშება და მხოლოდ დაცული backup/archive რჩება.

**კითხვა IT/Ops/Engineering-სთვის:** როგორ უზრუნველვყოფთ Python-ის ნამდვილ
read-only რეჟიმს backend/API/database დონეზე და არა მხოლოდ UI ღილაკების დამალვით?
რომელი URL/DNS/auth წვდომა დარჩება, ვინ აკვირდება 30 დღის განმავლობაში, როგორ
ავიცილებთ Java და Python database/source-of-truth-ის არევას და რა ზუსტი
decommission/archive პროცესი შესრულდება 30-ე დღეს?

**მოსაწოდებელი პასუხი/მტკიცებულება:** read-only enforcement-ის მტკიცებულება ყველა write route/
credential-ზე, traffic/DNS/auth გეგმა, owner/on-call, monitoring, 30-ე დღის
decommission checklist, backup/archive location, restore test და completion
sign-off.

### CUT-05 — rollout flags-ის ოპერირება — P0

**კითხვა Platform/Ops-სთვის:** `ROLLOUT_LEADERSHIP_SCOPE`,
`ROLLOUT_COMPLIANCE_ELIGIBILITY` და `ROLLOUT_FILE_ENTITLEMENT` ვინ ცვლის,
ცალ-ცალკე როგორ deployდება, configuration history სად ჩანს და rollback
რამდენ ხანშია შესაძლებელი?

**მოსაწოდებელი პასუხი/მტკიცებულება:** env/config owner, change audit, restart/rollout procedure
და rollback drill.

**დამატება (2026-08-29):** პირველი ორისგან განსხვავებით,
`ROLLOUT_FILE_ENTITLEMENT` **ნამდვილად ცვლის ქცევას** და პროდაქშენზე shadow-ით
ეშვება. მისი ჩართვის გადაწყვეტილება 14-დღიან სამუშაო ფანჯარას და `audit_logs`-ის
`FILE_ACCESS_SHADOW_DENY` ჩანაწერების განხილვას ეყრდნობა
(კრიტერიუმი: `docs/ROLLOUT_ROLLBACK_KA.md` → „DEC-P01").

განხილვის უმეტესობა IT-ს არ ეხება: `FILE_ACCESS_SHADOW_DENY` ჩანაწერები
პორტალის აუდიტის გვერდიდან იკითხება (`action:` ტოკენი ძებნის ველში), ე.ი.
სისტემური ადმინისტრატორი დამოუკიდებლად ართმევს თავს და DB-წვდომა არ სჭირდება.

**რჩება ერთი დამოკიდებულება — application log:** კრიტერიუმის ერთ-ერთი პუნქტი
`WARN`-ის არარსებობას ამოწმებს
(`stored_file_references had no row for ... healing from scan`). თუ log
aggregation არ არის ან replica-ების ლოგი არ ინახება, ეს პუნქტი შეუმოწმებელი
რჩება და enforcement-ის ჩართვა ბრმა იქნება.

**კითხვა:** სად და რამდენ ხანს ინახება backend-ის application log, და როგორ
მოვძებნოთ მასში კონკრეტული ტექსტი ყველა replica-ს გაშვებით?

### CUT-06 — პლატფორმის rollback შესაძლებლობები — P0

**კითხვა Platform/Ops-სთვის:** რა ავტომატური/manual rollback, previous-image,
config rollback, traffic switch და database recovery შესაძლებლობები აქვს
პლატფორმას და თითოეულს რამდენი დრო სჭირდება?

**მოსაწოდებელი პასუხი/მტკიცებულება:** მხარდაჭერილი rollback მექანიზმები, დრო, საჭირო უფლებები,
შეზღუდვები და operator.

### CUT-08 — post-go-live hypercare — P1

**კითხვა IT/Engineering-სთვის:** პირველი 24 საათი/7 დღე ვინ აკვირდება სისტემას,
რა dashboard/ticket cadence იქნება, როდის სრულდება hypercare და ვინ იღებს
ნორმალურ ოპერირებაში?

**მოსაწოდებელი პასუხი/მტკიცებულება:** rota, war-room channel, check frequency, exit criteria და
handover owner.

---

## 12. საკითხები, რომლებიც IT-მ მარტო არ უნდა გადაწყვიტოს

შემდეგი პუნქტებისთვის IT გვაძლევს **ტექნიკურ ფაქტებს**, მაგრამ საბოლოო პასუხი
პროდუქტის მფლობელს ან DPO/იურიდიულს ეკუთვნის:

| საკითხი | IT-ის წვლილი | საბოლოო owner |
|---|---|---|
| დანართის `/uploads/...` ავტორიზაცია (G-6/D-4) | ingress/cache/token-loading შესაძლებლობა, file classification და scanning | product target გადაწყვეტილია; DPO/Security validation |
| ხელმძღვანელის export-ში სახელები (G-1) | ტექნიკური audit/access controls; product target: სახელი leadership scope-ში | DPO/იურიდიული validation |
| ხელმძღვანელის export whitelist (G-2) | მიმდინარე ველების inventory და logging/storage facts; PO-13-ის რვა target სვეტი უკვე არჩეულია | DPO/იურიდიული validation |
| SYSTEM_ADMIN-ის სრული business/log export | წყაროების inventory, SIEM access, redaction და export audit მექანიზმი | product target გადაწყვეტილია; Security/DPO/იურიდიული validation და IT/SOC implementation |
| content/export-ის პროდუქტული retention | storage/backup/purge შესაძლებლობა და სავალდებულო საზღვრები | product target გადაწყვეტილია; DPO/იურიდიული validation |
| audit archive-ის საერთო retention | archive/SIEM შესაძლებლობა, მოქმედი კომპანიის პოლიტიკა და რეგულაციული მოთხოვნა | IT/Security/DPO/იურიდიული compliance owner |
| raw search/view logs, compliance evidence და AD sync reports retention | storage/purge შესაძლებლობა, კომპანიის პოლიტიკა და რეგულაციული მოთხოვნა | IT/Security/DPO/იურიდიული compliance owner |
| `RPO = 0`, `RTO = 1 საათი` | მიმდინარე ტექნიკური შესაძლებლობა, მტკიცებულება და ფასი | product target გადაწყვეტილია; IT/DBA feasibility და implementation |

თუ სავალდებულო რეგულაცია ან ინფრასტრუქტურული შეზღუდვა უკვე დადგენილ product
target-ს ეწინააღმდეგება, IT/DPO/Security აღწერს კონფლიქტსა და ვარიანტებს; target-ს
კოდი ან ინფრასტრუქტურული გუნდი ჩუმად არ ცვლის.

---

## 13. შეხვედრის დასრულებისას მისაღები არტეფაქტები

სასურველია შეხვედრის შემდეგ გვქონდეს:

1. owner/RACI სია;
2. AD/IdP არჩეული პროტოკოლი და სანიტიზებული metadata;
3. OU/group subtree და sample attributes;
4. stable-ID + lifecycle + membership precedence ცხრილი;
5. LDAPS/DC/VIP/firewall დეტალები და read-only service-account პროცესი;
6. Kubernetes/namespace/ingress/service/DNS/TLS topology;
7. `TRUSTED_PROXIES`-ის ყველაზე ვიწრო დასაშვები IP/CIDR;
8. Oracle inventory, topology, JDBC/TLS pattern და privilege export;
9. CI/CD, Flyway/backfill/V37 და rollback runbook-ების owner-ები;
10. backup scope, RPO/RTO და ბოლო restore drill-ის მტკიცებულება;
11. monitoring/alert/on-call matrix;
12. ყველა P0 პუნქტზე პასუხი ან owner + დათქმული თარიღი.

პასუხები შეჯამდეს `docs/QUESTIONS_FOR_IT.md`-ში; დიდი ან მგრძნობიარე
არტეფაქტები დარჩეს IT-ის დაცულ სისტემაში და დოკუმენტში ჩაიწეროს მხოლოდ ბმული,
owner და ბოლო გადამოწმების თარიღი.
