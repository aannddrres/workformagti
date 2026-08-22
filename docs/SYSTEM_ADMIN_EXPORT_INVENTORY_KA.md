# SYSTEM_ADMIN export — მონაცემთა ინვენტარი და უსაფრთხოების საზღვარი

**განახლებულია:** 2026-08-22
**პროდუქტის წყარო:** `PRODUCT_OWNER_DECISIONS_KA.md` PO-14
**სტატუსი:** ტექნიკური allowlist განხორციელებულია; production retention და საბოლოო
სამართლებრივი დამტკიცება რჩება Security/DPO/Legal gate-ად.

## რას აკეთებს პორტალი

პორტალში კანონიერად შენახული ექვსი მონაცემთა ოჯახი ექვს ცალკე XLSX ფაილად
გამოდის. ყველა შექმნა მხოლოდ `SYSTEM_ADMIN`-ს შეუძლია, საკუთარი audit action-ით.
თარიღის ფილტრი optional-ია; უფილტროდ გამოდის მთელი შენახული ისტორია. 20 000-ზე
მეტი row ჩუმად არ იჭრება — მოთხოვნა `413`-ით ჩერდება და ადმინს პერიოდის
შემცირებას სთხოვს. job/file 1 საათში იშლება და classified admin ფაილს მხოლოდ
მისი შემქმნელი ჩამოტვირთავს.

| ოჯახი | წყარო | გატანილი მონაცემის ტიპი | განზრახ გამოტოვებული |
|---|---|---|---|
| აუდიტის სრული ჟურნალი | `audit_logs` | actor/item IDs და snapshots, action/category, დრო, details, IP, user-agent, prev/row hash | credential/secret details |
| ოფიციალური გაცნობა | `article_read_receipts`, `read_statuses`, `required_readings` | თანამშრომელი, მასალა, ვერსია/status, read time, due time | password/session მონაცემი |
| სტატიის გახსნა | `article_view_logs` | თანამშრომლისა და სტატიის snapshots, ვერსია, გახსნის დრო | ოფიციალური read-ის მცდარი ნიშანი |
| ძებნის ისტორია | `search_logs` + `users` | მომხმარებელი, query, შედეგის ფაქტი/რაოდენობა, დრო | auth/session მონაცემი |
| Quiz მცდელობები | `quiz_attempts` + `users/articles` | მცდელობის ნომერი, score/total, pass, article version, დრო | არჩეული პასუხები — schema-ში არ ინახება |
| ცვლილებები და უსაფრთხოება | `audit_logs` | USER/CONTENT/SECURITY/SYSTEM კატეგორიის მოვლენების სამუშაო view | credential/secret details |

## საიდუმლო მონაცემის წესი

არც SQL allowlist-ში და არც სათაურებში არ არსებობს password, password hash,
access/refresh token, session credential, cookie, authorization header, private
key, database password ან secret. Audit `details` JSON რეკურსიულად იფარება ამ
key-ებზე. თუ ძველი details ველი JSON არ არის, ის fail-closed marker-ით იცვლება და
raw ტექსტი ფაილში არ გადის. `row_hash` და `prev_hash` ნებადართული integrity
metadata-ა და credential hash არ არის.

## რა არ ეკუთვნის ამ ეკრანს

Java/Kubernetes/ingress/runtime log Oracle-ში არ კოპირდება. მისი ძიება,
retention და შესაძლო export კომპანიის SIEM-ში IT/SOC-ის პასუხისმგებლობაა.
Production retention-ის ვადები და ამ allowlist-ის სამართლებრივი საბოლოო ვერსია
Security/DPO/Legal-ის წერილობით დადასტურებამდე არ აქტიურდება.
