# Magti Portal — დადასტურებული გადაწყვეტილებების განხორციელების გეგმა

**მფლობელი:** Product Owner + Engineering
**განახლებულია:** 2026-08-22
**სტატუსი:** მიმდინარეობს — ეტაპები A და B დასრულებულია; შემდეგია ეტაპი C

## 1. მიზანი და source of truth

ეს გეგმა აქცევს `PRODUCT_OWNER_DECISIONS_KA.md`-ში დადასტურებულ ქცევას
პატარა, შემოწმებად ცვლილებებად. წვდომის ყოველი ცვლილება იმავე commit-ში უნდა
აისახოს `ACCESS_CONTRACT_MATRIX_KA.md`-სა და regression tests-ში.

პრიორიტეტი ასეთია: პერსონალური მონაცემის საზღვარი → პროდუქტის ზედაპირი →
ადმინისტრაციული export → მონაცემთა lifecycle → production integration.

## 2. უცვლელი შეზღუდვები

- ახალი `V37` არ იწერება და არ ეშვება ამ გეგმის მიმდინარე ნაწილში.
- org backfill არცერთ production-მსგავს გარემოში არ ეშვება.
- `knowledge_feedback` ცხრილი ახლა არ იშლება; ჯერ endpoint/UI ქრება, შემდეგ
  ცალკე data inventory და დამტკიცებული forward migration დაიგეგმება.
- applied Flyway migration არ რედაქტირდება და checksum `repair`-ით არ იმალება.
- AD-owned დეპარტამენტი, ჯგუფი და წევრობა UI-დან არ იცვლება.
- ხელმძღვანელის რვასვეტიანი export და SYSTEM_ADMIN-ის სრული log-export სხვადასხვა
  კონტრაქტებია და საერთო DTO/template არ აქვთ.
- password/hash/token/session credential/private key/secret export-ში არასოდეს
  ხვდება.

## 3. ეტაპები

### შესრულების სტრატეგია

სამუშაო იყოფა დამოუკიდებელ release-ებად. თითო release ერთ სრულ vertical slice-ს
ასრულებს: Oracle schema → Java domain/service/API → authorization/audit → Angular
UI → regression tests → სრული quality gate → commit/push. ნახევრად აშენებული
endpoint ან UI მთავარ branch-ზე არ იგზავნება.

| release | შედეგი | გარე დამოკიდებულება |
|---|---|---|
| R1 | A–B: feedback removal და read/view evidence boundary | არა |
| R2 | C: Broadcast-ის სრული lifecycle და UI | არა |
| R3 | D: ფიქსირებული reminder engine | notification transport-ის production არჩევანი — IT; in-app ნაწილი დამოუკიდებლად კეთდება |
| R4 | E: SYSTEM_ADMIN export center | საბოლოო fields/retention — Security/DPO validation |
| R5 | F: archive/trash/recovery/purge | object storage purge contract — IT/Platform |
| R6 | G: SSO/AD/cutover | კომპანიის IdP/AD/infra პასუხები აუცილებელია |

R2–R5-ში IT-ზე დამოკიდებული adapter/config seam წინასწარ მზადდება, მაგრამ უცნობი
კომპანიის ინფრასტრუქტურა არ გამოიგონება. R6-ის local implementation იმდენად
სრულდება, რამდენადაც mock/contract adapter-ით შეიძლება; production activation
მხოლოდ IT-ის რეალური პასუხებით ხდება.

### ეტაპი A — feedback ფუნქციის საბოლოო ამოღება — დასრულებულია

**მიზანი:** პროდუქტში საერთოდ აღარ არსებობდეს თანამშრომლის feedback.

- წაიშალოს `POST /api/articles/{id}/feedback` და `GET /api/admin/feedback`;
- წაიშალოს მხოლოდ ამ deprecated endpoint-ების ტესტები და matrix rows;
- Java entity/repository ამოიღოს runtime code-იდან, თუ სხვა გამოყენება არ აქვს;
- ძველი Oracle ცხრილი და ისტორიული migration უცვლელად დარჩეს;
- Angular-ის misleading კომენტარები და ძველი audit label გასწორდეს ისე, რომ quiz-ის
  შიდა „feedback“ state არ აგვერიოს პროდუქტულ feedback ფუნქციაში.

**მიღება:** endpoint-ები `404`; access-contract coverage მწვანე; სრული backend
და Angular test/build მწვანე.

### ეტაპი B — ოფიციალური წაკითხვა და გახსნის log-ის საზღვარი

**სტატუსი:** ✅ დასრულებულია 2026-08-22

**მიზანი:** ლიდერი ხედავდეს მხოლოდ ოფიციალურ read evidence-ს; უბრალო გახსნა
იყოს მხოლოდ SYSTEM_ADMIN-ის log.

- read-receipts: ჯგუფის უფროსი — საკუთარი ჯგუფი; `SYSTEM_ADMIN` — ორგანიზაცია;
- დეპარტამენტის ხელმძღვანელის გზა კოდში შეიძლება მზად იყოს, მაგრამ პირველი
  rollout-ის UI/gate არ აქტიურდება;
- `content.manage`-ს მხოლოდ per-material aggregate რჩება;
- სახელობით response-იდან email ამოიღოს; სახელი საკმარისია;
- article-view rows გახდეს SYSTEM_ADMIN-only და leadership endpoint/UI-ში არ
  გამოჩნდეს;
- response-shape, direct API negative და scope regression tests დაემატოს.

**განხორციელებული კონტრაქტი:** `content.manage` აბრუნებს მხოლოდ მასალის საერთო
რაოდენობებს; მოქმედი პირდაპირი ჯგუფის assignment ხსნის სახელობით rows-ს მხოლოდ
იმ ჯგუფზე; `SYSTEM_ADMIN` ხედავს ორგანიზაციას. დეპარტამენტის assignment პირველ
rollout-ში fail-closed რჩება. ოფიციალური rows email-ს აღარ შეიცავს, ხოლო
`article_view_logs`-ის დეტალური rows მხოლოდ `SYSTEM_ADMIN`-ს გაეცემა.

### ეტაპი C — Broadcast-ის დამოუკიდებელი მოდული

**მიზანი:** საერთო ინფორმაცია არ იყოს პირადი შეტყობინება.

- აუდიტორია ფიქსირებულად ყველა ავტორიზებული თანამშრომელია;
- ჩანს მთავარ გვერდსა და პროფილში passive compact banner/card-ად;
- მომხმარებელი ვერ ხურავს, მაგრამ UI-ს გამოყენება არ იბლოკება;
- publish დაუყოვნებლივ; end time სავალდებულო; publisher-ს შეუძლია ადრე მოხსნა;
- publish შეუძლიათ `content.manage`-ს, მოქმედ ძირითად/დროებით ჯგუფის უფროსს და
  `SYSTEM_ADMIN`-ს;
- პირადი messaging-ის დარჩენილი endpoint/model/UI ცალკე removal commit-ში ქრება;
- lifecycle და authorization tests ფარავს ყველა publisher/persona-ს.

### ეტაპი D — reminder-ის ზუსტი ქცევა

- ავტომატური: მინიჭებისას, ვადამდე 24 საათით ადრე, overdue-ის შემდეგ ერთხელ;
- ჯგუფის მოქმედ უფროსს შეუძლია საკუთარ წევრს ფიქსირებული reminder გაუგზავნოს;
- თავისუფალი ტექსტი/reply/chat არ არსებობს;
- duplicate/cooldown, scope და audit tests სავალდებულოა.

### ეტაპი E — SYSTEM_ADMIN-ის სრული export ცენტრი

ჯერ კეთდება data inventory და Security/DPO review, შემდეგ — endpoint-ები.

ცალკე export ოჯახები:

1. ბიზნეს audit და integrity/hash-chain metadata;
2. ოფიციალური read receipts/read status;
3. article view history;
4. search history;
5. quiz attempts და ხელმისაწვდომი version/result metadata;
6. content/user/admin changes და security events.

ყველა endpoint `requireSystemAdmin`-ზეა, აქვს ქართული სათაურები, pagination/date
filters, 1-საათიანი job TTL, owner check და საკუთარი `EXPORT_*` audit event.
Secret denylist როგორც DTO-ზე, ისე header/snapshot tests-ში იკეტება.

**ცნობილი schema gap:** quiz-ის არჩეული პასუხები დღეს სრულად არ ინახება. მისი
დამატება მოითხოვს ახალ, ცალკე დამტკიცებულ Flyway migration-ს; მიმდინარე ეტაპზე
მონაცემს არ ვიგონებთ და export-ში მხოლოდ რეალურად შენახული fields გადის.

**runtime logs:** Java/Kubernetes/ingress log SIEM-ში რჩება. პორტალში მათი ასლი
არ იქმნება; IT/SOC უზრუნველყოფს approved search/export flow-ს.

### ეტაპი F — კონტენტის lifecycle და retention

- archive → explicit trash → 30-დღიანი recovery → verified purge;
- article/news/video/attachment payload-ის purge არ შლის read/view/audit evidence-ს;
- category delete იკეტება, სანამ მასალა სხვა კატეგორიაში არ გადავა;
- Broadcast-ს აქვს საკუთარი completed history;
- Oracle-ში 1-წლიანი ბიზნეს audit და შემდგომი protected archive მხოლოდ
  IT/Security/DPO retention/verification პასუხების შემდეგ ირთვება.

### ეტაპი G — production integration და cutover

- კომპანიის auth/SSO კონტრაქტი; local password path production-ში გამორთული;
- AD delta/deactivation 5–15 წუთი;
- read-only org structure, leadership assignments და reconciliation;
- `RPO = 0`, `RTO = 1 საათი` evidence;
- Python 30 დღე backend-enforced read-only, შემდეგ გათიშვა;
- persona QA მხოლოდ რეალური backfill/leadership data-ს შემდეგ სრულდება.

## 4. თითო ეტაპის ხარისხის gate

1. ცვლილებასთან ერთად განახლებული product/access docs;
2. unit + Oracle integration + authorization negative tests;
3. Angular Vitest და საჭირო UI regression;
4. სრული backend suite;
5. Angular production build;
6. მხოლოდ მწვანე შედეგის შემდეგ commit/push — history rewrite-ის გარეშე.

## 5. მიმდინარე შესრულების ჩანაწერი

- [x] პროდუქტის პასუხების canonical register გასწორდა;
- [x] Broadcast, export, feedback, view-log და storage ownership წინააღმდეგობები
  ძირითად დოკუმენტებში გასწორდა;
- [x] ძველი FastAPI architecture/handover ფაილები legacy-დ მოინიშნა;
- [x] ეტაპი A — feedback endpoint/runtime code removal;
- [x] ეტაპი A — access-contract, missing-route `404` regression და სრული
  backend/Angular შემოწმება;
- [x] ეტაპი B — official read evidence scope/aggregate და SYSTEM_ADMIN-only view log;
- [ ] ეტაპი C — Broadcast;
- [ ] ეტაპი D — reminder;
- [ ] ეტაპი E — SYSTEM_ADMIN export center;
- [ ] ეტაპი F — lifecycle/retention;
- [ ] ეტაპი G — production integration/cutover.
