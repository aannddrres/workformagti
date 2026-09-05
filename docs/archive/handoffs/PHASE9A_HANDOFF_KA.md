> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# Phase 9 — ნაწილი A: cutover-ის შექცევადობა და მტკიცებულება

**სტატუსი:** დასაწყები
**შედგენილია:** 2026-08-22 (Claude)
**გეგმა:** `docs/ORG_ACCESS_ARCHITECTURE_PLAN_KA.md` §7, ფაზა 9
**კონტრაქტი:** `docs/ACCESS_CONTRACT_MATRIX_KA.md`
**წინაპირობა:** Phase 8 (`PHASE8_HANDOFF_KA.md`), review `9ec1108`

---

## 1. რატომ არის ეს „ნაწილი A"

გეგმის ფაზა 9 ოთხ რამეს ითხოვს:

> **9. AD readiness/production:** sync adapter dry run, reconciliation/access-diff
> report, feature flags და rollback-ready rollout.

**პირველი მათგანი დაბლოკილია და შენზე არ არის დამოკიდებული.**
`docs/QUESTIONS_FOR_IT.md` §10 ღიაა და პირდაპირ ამბობს:

> „ორივე პასუხი გვჭირდება, სანამ sync adapter დაიწერება."

უცნობია AD-ის stable ID (`objectGUID` თუ `distinguishedName`), ცვლილებების
feed-ის მექანიზმი, deactivation-ის სემანტიკა და მრავალწევრობის წესი. ამ
პასუხების გარეშე დაწერილი adapter გამოცნობა იქნება, არა იმპლემენტაცია.

**დანარჩენი სამი დაბლოკილი არ არის — და სწორედ ისინი სჭირდება Phase 4/5-ს.**

ეს დავალება მათზეა.

---

## 2. რატომ ახლა, და არა cutover-თან ერთად

Phase 4/5 გეგმის ყველაზე სარისკო ნაბიჯია. `ComplianceEligibilityService`-ის
javadoc ამას პირდაპირ წერს:

> „Compliance percentages are computed at query time, so flipping eligibility
> rewrites every historical percentage in the same instant -- and those
> records are usable as evidence about what an operator knew."

ე.ი. cutover-ის მომენტში **ისტორიული პროცენტები გადაიწერება**. თუ რაიმე
არასწორად წავიდა, საჭიროა უკან დაბრუნება **წუთებში, არა deploy-ის ციკლში**.

დღეს ასეთი გადამრთველი **არ არსებობს**. `portal.security.allow-dev-login`
ერთადერთი boolean-ია ამ სტილში, და `ProductionSafetyGuard`-ს გარდა
rollout-ის კონტროლი არსად არის.

---

## 3. სამუშაო

### 3.1 M-1 — seeder-ის guard-ის ტესტი (ჯერ ეს, პატარაა)

**`scripts/seed_phase8_org_fixtures.py:50-73`** — `_assert_local_target` ის
ერთადერთი რამაა, რაც Phase 8-ის seeder-სა და production ბაზას შორის დგას.
ის **სუფთა Python-ია** (მხოლოდ env + DSN string, ბაზა არ სჭირდება), მაგრამ
ერთადერთი ტესტი Oracle-ზეა gated და default-ად skip-დება
(`tests/test_phase8_org_fixture_seeder.py:10-13`).

ე.ი. **CI-ში ამ seeder-ის ნულოვანი ტესტი გადის.** regex-ის შესუსტება ან
`_PRODUCTION_PROFILES`-იდან ერთი სახელის მოკლება არაფერს ტეხს.

**DB-free ტესტი** დაამატე (Oracle-ის gate-ის **გარეშე**, რომ ყოველთვის
გავიდეს):

| შემთხვევა | მოსალოდნელი |
|---|---|
| `localhost:1521/orclpdb1` | დაიშვება |
| `127.0.0.1:1521/x`, `[::1]:1521/x` | დაიშვება |
| `prod-db.magti.ge:1521/PROD` | `RuntimeError` |
| `jdbc:oracle:thin:@//prod:1521/X` | `RuntimeError` |
| `PRODDB` (TNS alias, პორტის გარეშე) | `RuntimeError` — fail-closed |
| `APP_ENV=production` + ლოკალური DSN | `RuntimeError` |
| `SPRING_PROFILES_ACTIVE=oracle,staging` | `RuntimeError` |

guard დღეს **სწორია** — შემთხვევები გავიარე. ტესტი იმისთვისაა, რომ ხვალაც
იყოს.

### 3.2 Rollout-ის გადამრთველები

`PortalProperties`-ს (`config/PortalProperties.java`, namespace `portal:`)
დაემატოს ახალი ბლოკი — `allow-dev-login`-ის ნიმუშით, **fail-safe default-ით**:

```yaml
portal:
  rollout:
    # Phase 4: leadership scope. false = ManagerScope-ის ძველი წესი.
    leadership-scope-enabled: ${ROLLOUT_LEADERSHIP_SCOPE:false}
    # Phase 5: compliance eligibility. false = ComplianceCalculator-ის ძველი წესი.
    compliance-eligibility-enabled: ${ROLLOUT_COMPLIANCE_ELIGIBILITY:false}
```

**წესები:**

* **default `false`** — გამოტოვებული env ნიშნავს „ძველი ქცევა", არა ახალი.
  იგივე ლოგიკა, რითაც `app-env` production-ზეა default (SEC-01).
* **ორი ცალკე დროშა, არა ერთი.** Phase 4 და 5 დამოუკიდებლად უნდა
  ჩაირთოს და დამოუკიდებლად უკან დაბრუნდეს. ერთი საერთო გადამრთველი
  ნიშნავს, რომ compliance-ის პრობლემა scope-საც აბრუნებს უკან.
* **`ProductionSafetyGuard`-ს არ შეეხო.** ეს დროშები არ არის უსაფრთხოების
  bypass — მათი ჩართვა production-ში ლეგიტიმური ნაბიჯია.

**ამ ფაზაზე დროშები არაფერს რთავენ.** `ScopeResolver` და
`ComplianceEligibilityService` კვლავ shadow-ში რჩება (`shadowCompare`
legacy პასუხს აბრუნებს). დროშა **ინფრასტრუქტურაა Phase 4/5-ისთვის**, არა
თავად cutover.

> ⚠️ **გადამრთველი დაამატე, მაგრამ არცერთი call site არ გადართო.**
> თუ `shadowCompare`-ს ნამდვილ პასუხზე გადაიყვან, ეს Phase 4-ია და
> backfill-ს ელოდება.

### 3.3 Access-diff report

`GET /api/admin/access-diff`, `SYSTEM_ADMIN`-only.

`PolicyShadowRecorder` აჩვენებს **რამდენ** გადაწყვეტილებაზე განსხვავდება
ძველი და ახალი წესი. ის არ ამბობს **ვისზე**. cutover-ის წინ ეს მეორე
კითხვაა მნიშვნელოვანი: „ვინ დაკარგავს წვდომას და ვინ მიიღებს".

პასუხი — თითო მომხმარებელზე, ორივე მიმართულებით:

```json
{
  "generated_at": "...",
  "totals": { "users": 600, "gains": 3, "losses": 12, "unchanged": 585 },
  "rows": [
    {
      "user_id": 42,
      "user_name": "...",
      "role": "manager",
      "compliance": { "legacy": true, "proposed": false },
      "scope": { "legacy_user_count": 40, "proposed_user_count": 12 }
    }
  ]
}
```

**კონკრეტულად:**

* `compliance` — `ComplianceCalculator.isEligible` vs
  `ComplianceEligibilityService.resolve`. მხოლოდ განსხვავებული მწკრივები
  მოხვდეს `rows`-ში; `totals` ყველას ითვლის.
* `scope` — `ManagerScope`-ის ხილული მომხმარებლების **რაოდენობა** vs
  `ScopeResolver`-ის. **სახელები არა, მხოლოდ რაოდენობა** — თორემ ეს
  endpoint თავად გახდება იმ მონაცემის გამტანი, რომლის დაცვასაც ემსახურება.
* **მხოლოდ კითხვაა.** `apply` არ არსებობს; ჩაწერა არ ხდება.
* `PII = yes` — `user_name` ატარებს.

### 3.4 Rollback-ის პროცედურა

`docs/`-ში მოკლე დოკუმენტი (ან არსებულის სექცია), რომელიც წერს:

1. რომელი env ცვლადი რას აბრუნებს უკან;
2. რა **არ** ბრუნდება უკან დროშით — `V36`/`V36.1` schema და backfill-ის
   მიერ ჩაწერილი `leadership_assignments` მწკრივები **რჩება**. დროშა
   ქცევას აბრუნებს, მონაცემს არა;
3. `V37`-ის შემდეგ rollback **აღარ არის** მხოლოდ დროშა — constraint-ები
   გამკაცრდება. ეს ცალკე უნდა ეწეროს, რომ არავინ ჩათვალოს, რომ ყველა
   ნაბიჯი შექცევადია.

---

## 4. სავალდებულო ტესტები

| ტესტი | უნდა ამტკიცებდეს |
|---|---|
| seeder guard (§3.1) | ცხრილის შვიდივე შემთხვევა, **Oracle-ის გარეშე** |
| დროშის default | env-ის გარეშე ორივე `false` |
| დროშა ქცევას არ ცვლის | `ScopeResolver`/`ComplianceEligibilityService` დროშის ორივე მდგომარეობაზე **იმავე** legacy პასუხს აბრუნებს |
| `access-diff` არა-ადმინზე | `403`, `content.manage`-ის მქონე operator-ის ჩათვლით |
| `access-diff` read-only | არცერთი repository `save`/`delete` არ იძახება (mock verify) |
| `access-diff` shape | `ResponseShapeContractTest` — **სახელების სია არ ჩანს**, მხოლოდ რაოდენობები |
| `AccessContractCoverageTest` | გადის — მატრიცა განახლდა |

**`AccessContractCoverageTest` აუცილებლად დაეცემა** — ერთი ახალი endpoint.
`docs/ACCESS_CONTRACT_MATRIX_KA.md` იმავე commit-ში: მწკრივი
(`requireSystemAdmin` / `—` / `ORG` / `yes`) + **119 → 120**.

---

## 5. საზღვრები

* **AD sync adapter არ დაწერო** — `QUESTIONS_FOR_IT` §10 ღიაა (§1).
* **`shadowCompare`-ის არცერთი call site არ გადართო** — ეს Phase 4/5-ია.
* **`V37` არ დაწერო**; **org backfill არ გაუშვა** production-მსგავსზე.
* **`org.manage` არ დაამატო**; **D-8 კოდით არ გადაწყვიტო**.
* **`POST /api/teams` არ გახსნა**; **`users.permissions` სვეტი არ წაშალო**.
* **`ProductionSafetyGuard`-ს არ შეეხო.**

---

## 6. რაც ამის შემდეგ რჩება

| | ვისზეა |
|---|---|
| `V36`/`V36.1` staging/production Oracle-ზე | ops |
| org backfill + `blocks_cutover: false` | ops |
| `V37` → Phase 4 → Phase 5 | დროშებით, ამ დავალების შემდეგ |
| AD sync adapter (Phase 9 ნაწილი B) | **Magti IT** — `QUESTIONS_FOR_IT` §10 |
| D-8 | პროდუქტის მფლობელი |
| G-1/G-2 | იურიდიული / DPO |

---

## 7. Branch

base: `codex/phase6-content-gates` (`9ec1108`)
`claude/dept-groups-architecture-biqtma` იმავე commit-ზეა + ეს დოკუმენტი.

დაწყებამდე: `git fetch origin && git merge origin/claude/dept-groups-architecture-biqtma`

---

## 8. განხორციელების ჩანაწერი

Rollback-ის ოპერაციული პროცედურა აღწერილია
`docs/ROLLOUT_ROLLBACK_KA.md`-ში. Phase 9A მხოლოდ ორი fail-safe flag-ის
configuration-სა და read-only access-diff evidence-ს ამატებს:
`shadowCompare` call site-ები legacy პასუხს კვლავ უცვლელად აბრუნებს.

AD sync adapter კვლავ დაბლოკილია `QUESTIONS_FOR_IT.md` §10-ზე; `V37`, org
backfill და Phase 4/5 enforcement ამ დავალებაში არ შესრულებულა.
