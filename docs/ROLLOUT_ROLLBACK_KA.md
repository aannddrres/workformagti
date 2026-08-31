# Rollout flag-ების rollback — Phase 4/5 და DEC-P01

**სტატუსი:** სამივე გადამრთველი არსებობს; Phase 4/5-ის ორს **არაფერი
კითხულობს** (და მათი ქცევა ისედაც ჩართულია — ე.ი. rollback-ის ბერკეტი არ
არსებობს), DEC-P01-ის ერთი კი ნამდვილად მოქმედებს და პროდაქშენზე shadow-ითაა
გაშვებული
**განახლებულია:** 2026-08-31

## გადამრთველები

| env | `false` | `true` | კოდი კითხულობს? |
|---|---|---|---|
| `ROLLOUT_LEADERSHIP_SCOPE` | — | — | **არა** |
| `ROLLOUT_COMPLIANCE_ELIGIBILITY` | — | — | **არა** |
| `ROLLOUT_FILE_ENTITLEMENT` | shadow — გადაწყვეტილება ითვლება და აღირიცხება, ფაილი მაინც გაიცემა | `FileAccessPolicy`-ის enforcement `/uploads/{filename}`-ზე (DEC-P01) | **დიახ** |

სამივე მნიშვნელობის default არის `false`. გადამრთველები ერთმანეთისგან
დამოუკიდებელია: compliance-ის rollback-მა leadership scope არ უნდა შეცვალოს და
პირიქით. მნიშვნელობა startup-ზე იკითხება, ამიტომ env-ის ცვლილების შემდეგ
application instance-ები ჩვეულებრივი rollout/restart-ით უნდა განახლდეს.

> ### პირველ ორ გადამრთველს არაფერი კითხულობს (2026-08-31)
>
> `RolloutSwitchWiringTest` ამას ახლა ყოველ build-ზე ამოწმებს: `main`-ში
> `isLeadershipScopeEnabled()` და `isComplianceEligibilityEnabled()` არსად
> გამოიძახება. ორივე მნიშვნელობა ბაინდდება, ლოგშიც ჩანს, და **ქცევაზე არ
> მოქმედებს** — არც `true`, არც `false`.
>
> ეს იმაზე მეტია, ვიდრე „ჯერ არ არის გაყვანილი". ქცევა, რომელსაც ეს ორი
> უნდა მართავდეს, **უკვე ჩართულია და გადამრთველის გარეშე**:
> `ExportQueryService`-ი და `StatsController`-ი leadership assignment-ებს
> უპირობოდ იყენებენ, როცა ისინი არსებობს. ე.ი. `false`-ზე დაბრუნება
> **არაფერს დააბრუნებს უკან**.
>
> ორივე `docker-compose.presentation.yml`-სა და `docker-compose.uat.yml`-ში
> `"true"`-დაა დაყენებული. ესეც უეფექტოა, მაგრამ განზრახ არ შეცვლილა: მათი
> `"false"`-ზე გადაყვანა ვითომ-rollback-ს კიდევ უფრო დამაჯერებელს გახდიდა.
>
> **ე.ი. ქვემოთ აღწერილი „ქცევის rollback-ის პროცედურა" პირველ ორ flag-ზე
> არ მუშაობს.** გადაწყვეტილება საჭიროა — ან wiring გაკეთდეს (რაც
> ავტორიზაციის ცვლილებაა და ცალკე მიღებას მოითხოვს), ან ორივე flag მოიხსნას
> და დოკუმენტმა მხოლოდ DEC-P01 დატოვოს. იხ.
> `claude/r5-complete-r6-planning`, სადაც wiring-ის ერთი ვარიანტი დაწერილია
> (`ScopeResolver.decide()` + `LeadershipRolloutGuard`).
>
> **`ROLLOUT_FILE_ENTITLEMENT` გამონაკლისია** — მას `UploadedFileController`
> ნამდვილად კითხულობს და `true` ქცევას ცვლის. იხ. ქვემოთ, DEC-P01.

## ქცევის rollback-ის პროცედურა

> **ეს პროცედურა დღეს არ მუშაობს** — იხ. ზემოთ. Phase 4/5-ის ორ flag-ს
> არაფერი კითხულობს, ამიტომ ქვემოთ აღწერილი ნაბიჯები აღწერს იმას, რაც
> wiring-ის შემდეგ მოხდებოდა, და არა იმას, რაც დღეს მოხდება.
> `ROLLOUT_FILE_ENTITLEMENT`-ის rollback ცალკეა და **მუშაობს** — იხ.
> „DEC-P01" ქვემოთ.

1. დაადგინე რომელი policy ქმნის პრობლემას: leadership scope თუ compliance.
2. მხოლოდ შესაბამისი env დააბრუნე `false`-ზე; მეორე flag უცვლელი დატოვე.
3. განაახლე application instance-ები და გადაამოწმე მათი effective config.
4. შეამოწმე `GET /api/admin/policy-shadow` და
   `GET /api/admin/access-diff`; დაადასტურე, რომ მომხმარებლებს legacy ქცევა
   ემსახურება და diff კვლავ მხოლოდ მტკიცებულებად ითვლება.
5. audit/application log-ში დააფიქსირე rollback-ის დრო, მიზეზი და პასუხისმგებელი.

## DEC-P01 — `/uploads/{filename}`-ის entitlement

### რატომ ჯერ shadow

წესი მარტივია: **ფაილი იკითხება მაშინ, როცა იკითხება ის, რაც მასზე
მიუთითებს.** „ვინ მიუთითებს" კი `stored_file_references`-იდან იკითხება
(migration `V46`), ხოლო ეს ცხრილი კონტენტის ყოველი შენახვისას ახლდება.

აქედან გამომდინარეობს რისკი, რომლის გამოც ეს flag არსებობს: შენახვის გზა,
რომელიც index-ს არ ანახლებს, **ხმაურით არ ჩავარდება** — უბრალოდ სურათები
გაქრება სტატიებიდან, რომლებსაც არაფერი სჭირთ. shadow ამას ავარიიდან
ჩანაწერად აქცევს.

(კოდში ამის საწინააღმდეგო დამცავიც არის: თუ index-ს ჩანაწერი არ აქვს,
`FileReferenceIndex.referencesTo` ავტორიტეტულ სკანირებაზე გადადის, აღმოჩენილს
უკან ჩაწერს და `WARN`-ს დაწერს. ე.ი. გამოტოვებული save-გზა ნელი-და-სწორია,
და არა სწრაფი-და-არასწორი. shadow ამის მიუხედავად საჭიროა — დამცავის
დაყრდნობით enforcement-ის ჩართვა ნიშნავს, რომ პირველი შეცდომა
მომხმარებელთან აღმოჩნდება.)

### რას აკეთებს shadow

`false`-ზე გადაწყვეტილება მაინც ითვლება, ფაილი მაინც გაიცემა, და უარყოფის
განზრახვა ორ ადგილას ფიქსირდება:

| სად | რა |
|---|---|
| application log | `INFO` — `File entitlement (shadow) would deny [<decision>] <file> for user <id> (<dept>)` |
| `audit_logs` | `action = FILE_ACCESS_SHADOW_DENY`, `details.result = SHADOW`, `details.reason` = გადაწყვეტილება, `details.after.department` |

ორივე განზრახ. log-ი დებაგისთვისაა; **აუდიტის ჩანაწერი — გადაწყვეტილებისთვის.**
პროდაქშენზე აპლიკაცია რამდენიმე replica-დ მუშაობს და „ვის აეკრძალებოდა"
grep-ით დათვლა არ გამოდის.

`FILE_ACCESS_SHADOW_DENY` (მოხდებოდა) და `FILE_ACCESS_DENIED` (მოხდა) განზრახ
სხვადასხვა action-ია — ერთი query-ის მანძილზეა და არა ერთი ფილტრის.

### როგორ განიხილება — პორტალიდან, DBA-ს გარეშე

სისტემურ ადმინისტრატორს ცალკე წვდომა არ სჭირდება. **ადმინი → აუდიტი**,
ძებნის ველში:

```
action:FILE_ACCESS_SHADOW_DENY
```

(ძებნის ველი `action:` / `actor:` / `category:` ტოკენებს იგებს —
`audit-format.ts:102-127`.) თარიღის ორი ველი ფანჯარას ზღუდავს, ჩანაწერზე
დაწკაპუნება კი დეტალებს ხსნის: `reason`, `after.stored_filename`,
`after.department`. იქვე არსებული ექსპორტიც მუშაობს, თუ სია ხელით
გადასათვალიერებლად დიდია.

<details>
<summary>იგივე SQL-ით, თუ ჯამური სურათი სჭირდება</summary>

```sql
SELECT a.admin_email_snapshot,
       COUNT(*) AS hits,
       MIN(a.timestamp) AS first_seen,
       MAX(a.timestamp) AS last_seen
  FROM audit_logs a
 WHERE a.action = 'FILE_ACCESS_SHADOW_DENY'
   AND a.timestamp > SYSTIMESTAMP - INTERVAL '7' DAY
 GROUP BY a.admin_email_snapshot
 ORDER BY hits DESC;
```

</details>

`reason` ორი მნიშვნელობით გვხვდება, და მათ შორის განსხვავება
გადამწყვეტია:

- **`DENIED_NOT_VISIBLE`** — ფაილზე მიმთითებელი კონტენტი არსებობს, მაგრამ
  მომხმარებელს არ ეხება. ეს ის შემთხვევაა, რომლის გამოც მთელი ცვლილება
  გაკეთდა (UAT F-1). აქ რიცხვი > 0 **მოსალოდნელია და კარგია.**
- **`DENIED_ORPHAN`** — ფაილზე არაფერი მიუთითებს და მომხმარებელი მისი
  ატვირთველი არ არის. აი, აქ სიფრთხილე: თუ ეს რიცხვი მაღალია და
  რედაქტორებზე მოდის, ესე იგი index არ ივსება — enforcement სურათებს
  გააქრობდა.

### ჩართვის კრიტერიუმი

`true`-ზე გადავდივართ, როცა **ერთდროულად** სრულდება:

1. მინიმუმ **14 დღე** shadow-ში, სამუშაო კვირის სრული ციკლის ჩათვლით;
2. `DENIED_ORPHAN` ჩანაწერები ან არ არის, ან თითოეული ინდივიდუალურად
   ახსნილია (მაგ. რედაქტორმა სხვისი დაუსრულებელი დრაფტის URL გახსნა);
3. application log-ში `stored_file_references had no row for ... healing from
   scan` **არცერთი** — ეს პირდაპირ ნიშნავს, რომ რომელიღაც save-გზა index-ს არ
   ანახლებს, და სანამ ის არ გასწორდება, enforcement ნაადრევია;
4. `DENIED_NOT_VISIBLE`-ის ნიმუში ხელით გადამოწმებულია: ორი-სამი ჩანაწერი
   აღებულია და დადასტურებულია, რომ იმ ადამიანს ის სტატია მართლაც არ ეხება.

### rollback

`ROLLOUT_FILE_ENTITLEMENT=false` და instance-ების restart. სხვა ნაბიჯი არ
არის — მონაცემი არ იცვლება.

მნიშვნელოვანი: `FileReferenceIndex.sync(...)` **flag-ისგან დამოუკიდებლად
მუშაობს**, ე.ი. shadow-ის ან rollback-ის პერიოდში index არ ძველდება და
ხელახლა ჩართვას backfill არ სჭირდება.

## რას არ აბრუნებს Phase 4/5-ის flag-ები

(DEC-P01-ის ანალოგიური პუნქტი მის საკუთარ „rollback" ქვეთავშია.)

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
