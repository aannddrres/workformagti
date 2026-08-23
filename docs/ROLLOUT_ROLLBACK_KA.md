# Phase 4/5 rollout-ის rollback

**სტატუსი:** `ROLLOUT_LEADERSHIP_SCOPE` **მიერთებულია enforcement-თან**
(default `false`); `ROLLOUT_COMPLIANCE_ELIGIBILITY` კვლავ shadow mode-შია
**განახლებულია:** 2026-08-23

## გადამრთველები

| env | `false` — rollback მდგომარეობა | `true` — cutover მდგომარეობა |
|---|---|---|
| `ROLLOUT_LEADERSHIP_SCOPE` | Phase 4-ის ძველი `ManagerScope` ქცევა | leadership assignment-ზე დაფუძნებული `ScopeResolver` |
| `ROLLOUT_COMPLIANCE_ELIGIBILITY` | Phase 5-ის ძველი `ComplianceCalculator` ქცევა | `ComplianceEligibilityService` |

ორივე მნიშვნელობის default არის `false`. გადამრთველები ერთმანეთისგან
დამოუკიდებელია: compliance-ის rollback-მა leadership scope არ უნდა შეცვალოს და
პირიქით. მნიშვნელობა startup-ზე იკითხება, ამიტომ env-ის ცვლილების შემდეგ
application instance-ები ჩვეულებრივი rollout/restart-ით უნდა განახლდეს.

### რა შეიცვალა 2026-08-23-ს

`ROLLOUT_LEADERSHIP_SCOPE` აღარ არის მხოლოდ კონფიგურაცია. `ScopeResolver.decide`
კითხულობს მას ოთხივე scope-გადაწყვეტილების წერტილში:

| decision | endpoint |
|---|---|
| `scope.team-stats` | `GET /api/manager/team-stats` |
| `scope.department-stats` | `GET /api/manager/department-stats` |
| `scope.critical-operators` | `GET /api/admin/critical-operators` |
| `scope.export` | compliance/reading export-ები |

- `false` (default) — ძველი `ManagerScope` (დეპარტამენტის ტექსტი);
- `true` — `ScopeResolver` (leadership assignment-ები).

**ორივე შემთხვევაში შედარება ჩაიწერება.** ეს განზრახაა: cutover-ის შემდეგაც
ვხედავთ, რას აჩვენებდა ძველი წესი — სწორედ ეს არის rollback-ის გადაწყვეტილების
მტკიცებულება, და არა შეგრძნება.

`ROLLOUT_COMPLIANCE_ELIGIBILITY` კვლავ **მხოლოდ იზომება** — Phase 5 მას ცალკე,
საკუთარი მტკიცებულებით ჩართავს.

### ⚠️ ჩართვის წინაპირობა — backfill

scope მოდის `leadership_assignments`-იდან. თუ backfill არ გაშვებულა,
**ყოველი არა-ადმინი ვერავის ხედავს**: მენეჯერის ყველა ეკრანი და ყველა export
ერთდროულად ცარიელდება — შეცდომის გარეშე, წარმატებული პასუხით. სიმპტომი
მონაცემების დაკარგვას ჰგავს და ისე იქნებოდა გამოძიებული.

ამიტომ `LeadershipRolloutGuard` **არ უშვებს აპლიკაციას**, თუ flag ჩართულია და
`leadership_assignments`-ში ერთი აქტიური ჩანაწერიც არ არის.

მაგრამ guard მხოლოდ უხეშ შემთხვევას იჭერს — 3 assignment 30 ჯგუფზე მას
გაივლის და მაინც 27 ჯგუფს დაუკეტავს წვდომას. **ნამდვილი gate არის**
`GET /api/admin/access-diff` — per-user ანგარიში, რომელიც ჩამოთვლის ვინ რას
დაკარგავს და ვინ რას მოიპოვებს. ის **ადამიანმა უნდა წაიკითხოს** ჩართვამდე.

## ქცევის rollback-ის პროცედურა

1. დაადგინე რომელი policy ქმნის პრობლემას: leadership scope თუ compliance.
2. მხოლოდ შესაბამისი env დააბრუნე `false`-ზე; მეორე flag უცვლელი დატოვე.
3. განაახლე application instance-ები და გადაამოწმე მათი effective config.
4. შეამოწმე `GET /api/admin/policy-shadow` და
   `GET /api/admin/access-diff`; დაადასტურე, რომ მომხმარებლებს legacy ქცევა
   ემსახურება და diff კვლავ მხოლოდ მტკიცებულებად ითვლება.
5. audit/application log-ში დააფიქსირე rollback-ის დრო, მიზეზი და პასუხისმგებელი.

## რას არ აბრუნებს flag

Flag **ქცევას აბრუნებს, მონაცემს და schema-ს არა**:

- `V36`/`V36.1`-ით შექმნილი ან შეცვლილი schema რჩება;
- backfill-ის მიერ ჩაწერილი `leadership_assignments` რჩება;
- manual/AD_SYNC assignment-ები და audit history არ იშლება;
- rollback-ისას არც backfill-ის უკუგდება და არც assignment-ების მასობრივი
  წაშლა არ უნდა შესრულდეს.

## V37-ის საზღვარი

`V37`-მდე Phase 4/5-ის ქცევითი rollback flags-ით სწრაფად კეთდება. `V37`-ის
შემდეგ rollback **აღარ არის მხოლოდ flag-ის შეცვლა**: `NOT NULL`/uniqueness
constraint-ები schema-ს ამკაცრებს და მათი უკან დაბრუნება ცალკე, წინასწარ
დაგეგმილ database migration-სა და DBA preflight-ს მოითხოვს.

ამიტომ `V37` არ უნდა დაიწეროს ან გაეშვას, სანამ reconciliation/preflight სუფთა,
`blocks_cutover: false` და rollback owner/procedure შეთანხმებული არ არის.
