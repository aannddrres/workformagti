# Magti Call Center Portal — ტექნოლოგიებისა და არქიტექტურის სახელმძღვანელო Product Owner-ისთვის

> **დანიშნულება:** ეს დოკუმენტი არის არაპროგრამისტი Product Owner-ის სასწავლო და საკომუნიკაციო სახელმძღვანელო. იგი აღწერს repository-ში 2026-08-22 მდგომარეობით ნანახ კოდს, კონფიგურაციას, migration-ებსა და ტესტებს. ეს არ არის production ინფრასტრუქტურის „როგორც აშენდა“ დოკუმენტი — repository-დან დაუდასტურებელი ინფრასტრუქტურული საკითხები მონიშნულია **IT-DEPENDENT** სტატუსით.

> ### ⚠ ნაწილობრივ დაძველებულია (შენიშვნა: 2026-08-31)
>
> ტექსტი 2026-08-22-ის მდგომარეობას აღწერს და **განზრახ არ გადაწერილა** —
> პროექტის წესია, რომ დათარიღებული დოკუმენტი არ იშლება, ცვლილება კი
> ზემოთ ემატება. სამი რამ უკვე აღარ შეესაბამება კოდს:
>
> - **„`SecurityConfig` … `anyRequest().permitAll()`" (დამატებულია 2026-09-21).**
>   2026-08-28-დან ჯაჭვი deny-by-default-ია — `.anyRequest().authenticated()`;
>   ანონიმურად ღიაა მხოლოდ login, SSO start, `/api/health` და actuator-ის
>   probe-ები (`ANONYMOUS_*`, ამოწმებს `AnonymousSurfaceTest`). როლი, უფლება და
>   scope კვლავ handler-ში მოწმდება (`require*`). შესაბამისად, `/uploads`-ის
>   ფაილი საჯარო აღარ არის — შესვლა სჭირდება, აუდიტორიის შემოწმება კი DEC-P01-ითაა
>   (`FileAccessPolicy`, `ROLLOUT_FILE_ENTITLEMENT`).
>
> - **`LEGACY` სვეტი და ბარათი 29 (Python/FastAPI).** ის კოდი
>   **2026-08-31-ს წაიშალა** — 102 ფაილი. პროექტში აღარ არსებობს, git-ის
>   ისტორიაშია. დარჩენილი Python მხოლოდ ოთხი ხელსაწყოა (`scripts/`), და
>   ისინი ძველი აპლიკაცია არ არიან.
> - **ბარათი 7 — Quill 1.3.7.** რეალურად `2.0.2`.
>
> **მარტივი აღწერისთვის იხ. [HOW_IT_WORKS_KA.md](HOW_IT_WORKS_KA.md)** —
> მოკლე დოკუმენტი პრინციპებზე, ვერსიების გარეშე. ეს ფაილი დეტალური
> ცნობარია და ისე უნდა იკითხებოდეს.

## როგორ წავიკითხოთ ეს დოკუმენტი

- **CURRENT** ნიშნავს მიმდინარე აქტიურ კოდს და ასაწყობ არტეფაქტს; თავისთავად არ ნიშნავს, რომ ის უკვე production-შია გაშვებული.
- **LEGACY** ნიშნავს ძველ Python სისტემას, რომელიც repository-ში ჯერ კიდევ არის, მაგრამ ახალი მიზნობრივი stack არ არის.
- **PLANNED/TARGET** ნიშნავს შეთანხმებულ მიმართულებას ან მოთხოვნას, რომლის სრული რეალიზაცია კოდში ჯერ არ ჩანს.
- **IT-DEPENDENT** ნიშნავს საკითხს, რომელსაც მხოლოდ კომპანიის რეალური ინფრასტრუქტურის ცოდნით ვერ გადავწყვეტთ.
- „უნდა“ ზოგჯერ აღწერს პროდუქტის მოთხოვნას და არა უკვე არსებულ ქცევას. ასეთ შემთხვევაში სტატუსი ყოველთვის ცალკე წერია.
- ფაილების მისამართები repository-ის root-თან მიმართებითაა მოცემული.

---

# 1. მთელი სისტემის მარტივი სურათი

## 1.1 რა პროდუქტია ეს

Magti Call Center Portal არის თანამშრომლების შიდა ცოდნისა და შესაბამისობის (compliance) პორტალი. ოპერატორი პოულობს სტატიებს, სიახლეებსა და ვიდეოებს, კითხულობს მისთვის სავალდებულო მასალას და ადასტურებს გაცნობას. მენეჯერი ხედავს თავისი პასუხისმგებლობის არეალის შედეგებს. კონტენტის და სისტემის ადმინისტრატორები მართავენ მასალებს, კატეგორიებს, ორგანიზაციულ წვდომასა და მომხმარებლებს.

ბიბლიოთეკის ანალოგიით: Angular არის მკითხველისთვის ხილული დარბაზი და კატალოგი; Java/Spring Boot არის ბიბლიოთეკარი, რომელიც წესებს ამოწმებს; Oracle არის დაცული საცავი და რეესტრი; Nginx არის შესასვლელი და მიმმართველი. ტექნიკურად ეს არის ბრაუზერში გაშვებული ერთგვერდიანი აპლიკაცია, რომელიც HTTP/JSON მოთხოვნებით უკავშირდება Java REST API-ს, ხოლო API მონაცემებს Oracle-ში JPA/JDBC-ით კითხულობს და წერს.

სისტემის სამი მთავარი ნაწილი:

1. **Frontend** — მომხმარებლის ეკრანი: `angular-frontend/`.
2. **Backend** — წესები, უსაფრთხოება და API: `java-backend/`.
3. **Database** — Oracle-ის ცხრილები, კავშირები და ისტორია: სტრუქტურა აღწერილია Flyway migration-ებში.

## 1.2 ერთი მოთხოვნის გზა

```text
თანამშრომელი
    ↓
ბრაუზერი — HTML/CSS/Angular
    ↓ HTTPS (production-ში დასადასტურებელია)
Nginx — SPA ფაილები + /api reverse proxy
    ↓ HTTP კომპანიის შიდა ქსელში
Java / Spring Boot REST API
    ↓ JPA → Hibernate → JDBC → HikariCP connection
Oracle Database
    ↑
JSON response ← Java ← Nginx ← Angular ეკრანი
```

მნიშვნელოვანი სიზუსტე: ბრაუზერი ჯერ Nginx-იდან იღებს Angular-ის უკვე აწყობილ HTML/CSS/JavaScript ფაილებს. შემდეგ Angular იძახებს `/api/...` მისამართებს. Nginx ამ მოთხოვნებს Java backend-ს უგზავნის. Java ამოწმებს მომხმარებელსა და უფლებას, ასრულებს ბიზნესწესს და Oracle-ს მიმართავს. პასუხი JSON ფორმატით იმავე გზით ბრუნდება.

## 1.3 სამი რეალური სცენარი

### სცენარი A — მომხმარებელი შედის სისტემაში

1. Angular-ის Reactive Form იღებს ელფოსტასა და პაროლს და აგზავნის `POST /api/auth/login` მოთხოვნას.
2. Nginx `/api` მოთხოვნას Java-ს `AuthController`-ისკენ გადაამისამართებს.
3. Java rate limit-ს ამოწმებს. მიმდინარე კოდში ავთენტიფიკაცია არის BCrypt-ით დაცულ ლოკალურ პაროლზე; development რეჟიმში არსებობს მკაცრად შემოსაზღვრული bypass. კომპანიის IdP/AD ინტეგრაცია ჯერ **PLANNED/TARGET + IT-DEPENDENT** არის.
4. წარმატებისას backend ქმნის ხელმოწერილ JWT-ს, აბრუნებს პასუხში და ასევე წერს `HttpOnly` cookie-ს.
5. Angular მიმდინარე კოდში სრულ JWT-ს `localStorage`-შიც ინახავს და შემდეგ მოთხოვნებს `Authorization: Bearer ...` სათაურს ურთავს. ეს მოსახერხებელია, მაგრამ XSS-ის შემთხვევაში token-ის მოპარვის რისკს ზრდის.
6. ყოველ დაცულ მოთხოვნაზე Java ხელმოწერას და ვადას ამოწმებს, შემდეგ Oracle-დან ხელახლა ტვირთავს მომხმარებელს, მის აქტიურობასა და `token_version`-ს. ამიტომ დეაქტივაცია ან „logout everywhere“ ძველ token-ს აუქმებს.

**რა უნდა დაიმახსოვროს Product Owner-მა:** login ეკრანის არსებობა არ ნიშნავს, რომ კომპანიის ავტორიზაცია უკვე ინტეგრირებულია. პროტოკოლი, იდენტიფიკატორი, ჯგუფები და გათიშვის სცენარი IT-მ უნდა დაადასტუროს.

### სცენარი B — თანამშრომელი ხსნის სტატიას

1. Angular Router ხსნის `/article/:id` გვერდს.
2. Angular აგზავნის `GET /api/articles/{id}` მოთხოვნას.
3. Java-ს JWT filter ადგენს ვინ არის მომხმარებელი; controller/service ამოწმებს სტატუსსა და ხილვადობას.
4. Repository JPA/Hibernate-ის მეშვეობით Oracle-დან ტვირთავს სტატიას, კატეგორიას, სამიზნე დეპარტამენტებსა და დაკავშირებულ მონაცემებს.
5. Java ქმნის Response DTO-ს და JSON-ს აბრუნებს; entity პირდაპირ browser-ში არ იგზავნება.
6. Angular ტექსტს ეკრანზე აჩვენებს. `[innerHTML]`-ს Angular-ის ჩაშენებული sanitizer იცავს; რედაქტორში ჩასმულ HTML-ს DOMPurify დამატებით წმენდს.
7. ეკრანის ჩატვირთვის შემდეგ Angular ცალკე, „fire-and-forget“ `POST /api/articles/{id}/view` მოთხოვნით ნახვას წერს. ამის შეცდომა სტატიის წაკითხვას არ ბლოკავს.

**რა უნდა დაიმახსოვროს Product Owner-მა:** „სტატია გაიხსნა“ და „ნახვის ჩანაწერი წარმატებით ჩაიწერა“ ორი მოთხოვნაა. ანალიტიკის კრიტიკული მტკიცებულებისთვის მათი გარანტიები ცალკე უნდა განისაზღვროს.

### სცენარი C — ადმინისტრატორი აქვეყნებს სავალდებულო საკითხავს

1. Angular-ის `article-edit-drawer` ამოწმებს სათაურს, კონტენტს, დეპარტამენტსა და ვადას.
2. `POST/PUT /api/articles...` მოთხოვნაზე Java ამოწმებს `articles.edit` და გამოქვეყნებისას `articles.publish` უფლებას.
3. ერთ database transaction-ში ინახება სტატია, მისი ვერსიის ისტორია, tags/targets და ახლდება search trigram index.
4. წარმატებული სტატიის ოპერაციის შემდეგ Angular **ცალკე** იძახებს required-reading API-ს.
5. backend `compliance.assign` უფლებას ამოწმებს, წერს სავალდებულო საკითხავს და ქმნის მდგრად შეტყობინების ჩანაწერებს.
6. quiz-ის სინქრონიზაციაც ცალკე მოთხოვნა შეიძლება იყოს.

აქ მიმდინარე რისკია: სტატიის გამოქვეყნება და სავალდებულო საკითხავად დანიშვნა ერთი ატომური transaction არ არის; მეორე მოთხოვნის შეცდომა UI-ში non-fatal-ად მუშავდება. შედეგად შესაძლებელია სტატია გამოქვეყნდეს, მაგრამ assignment ვერ შეიქმნას.

**რა უნდა დაიმახსოვროს Product Owner-მა:** თუ ბიზნესისთვის „გამოქვეყნება + დანიშვნა“ განუყოფელი ქმედებაა, acceptance criteria-მ atomicity ან მკაფიო recovery workflow უნდა მოითხოვოს.

---

# 2. CURRENT, LEGACY, PLANNED/TARGET და IT-DEPENDENT რუკა

| თემა | სტატუსი | რეალური მდგომარეობა |
|---|---|---|
| Angular 22 + TypeScript frontend | **CURRENT** | `angular-frontend/`; standalone SPA, 33 route declaration |
| Java 21 + Spring Boot 4.1 backend | **CURRENT** | `java-backend/`; ერთი modular monolith JAR, Spring MVC |
| Oracle + JPA/Hibernate + Flyway | **CURRENT** | 37 migration, V1–V36.1; production-ის რეალური Oracle გარემო დასადასტურებელია |
| Nginx და Docker image-ები | **CURRENT** | ახალი frontend/backend-ის Dockerfile-ები არსებობს; production გაშვება repository არ ადასტურებს |
| GitHub Actions CI | **CURRENT** | Python legacy, Java unit/integration, Angular unit/build და Playwright E2E jobs |
| Python/FastAPI frontend/API | **LEGACY** | root-ში დარჩენილი ძველი სისტემა; cutover-ის შემდეგ 30-დღიანი read-only პერიოდი მიზნობრივია |
| SQLite/PostgreSQL/Redis | **LEGACY** | ძველი სისტემის local/compose მონაცემთა ფენა და realtime/cache |
| კომპანიის IdP/AD login | **PLANNED/TARGET + IT-DEPENDENT** | პროტოკოლი და mapping პასუხს ელოდება; მიმდინარე კოდი ლოკალურ BCrypt login-ს იყენებს |
| ახალი leadership-based data scope | **PLANNED/TARGET** | ახალი ცხრილები/resolver არსებობს, მაგრამ enforcement ისევ legacy department scope-ს იყენებს; ახალი შედეგი shadow-ად ითვლება |
| `stats.view` ცალკე permission | **PLANNED/TARGET** | მიმდინარე stats endpoint-ები `content.manage` gate-ს იყენებს |
| authenticated attachment access | **PLANNED/TARGET** | upload/download controller მიმდინარე SecurityConfig-ის გამო საჯაროდ მისაწვდომი რჩება |
| personal messaging-ის ამოღება | **PLANNED/TARGET** | messaging UI/API/E2E ჯერ კოდშია |
| fuzzy/transliteration search | **PLANNED/TARGET** | ახლა trigram კანდიდატები + ზუსტი case-insensitive substring; typo/transliteration არა |
| ქართული 8-სვეტიანი manager export | **PLANNED/TARGET** | მიმდინარე CSV ინგლისურ headers-სა და ID-ებს შეიცავს; XLSX/PDF-შიც არის ID-ები |
| 1-წლიანი აქტიური audit + მრავალწლიანი archive | **PLANNED/TARGET + DPO/IT-DEPENDENT** | Java-ში ამ retention/archive ციკლის job არ არის |
| Kubernetes production deployment | **IT-DEPENDENT** | მიზანი on-prem Kubernetes-ია, მაგრამ repository-ში manifest/Helm/CD არ არის |
| TLS, DNS, ingress/load balancer | **IT-DEPENDENT** | აპი reverse-proxy headers-ს ამზადებს; რეალური სერტიფიკატი/დომენი/topology უცნობია |
| backup, HA, failover, RPO/RTO | **IT-DEPENDENT** | Product target RPO=0, RTO=1 საათია; განხორციელება და დასაბუთება DBA/IT-ის საქმეა |
| centralized logs, metrics, alerts, SIEM | **IT-DEPENDENT** | console logs/health არსებობს; collector/dashboard/alert/SIEM wiring არა |
| staging გარემო და CD | **IT-DEPENDENT** | repository-ში განსაზღვრული არაა; CI deploy-ს არ აკეთებს |

---

# 3. მნიშვნელოვანი ტექნოლოგიების ერთნაირი ბარათები

ქვემოთ არის **31 ძირითადი ტექნოლოგიური ბარათი**. ახლო ურთიერთობის მქონე ხელსაწყოები ერთ ბარათშია დაჯგუფებული; უმნიშვნელო transitive dependency-ები შეგნებულად გამოტოვებულია. ყველა ბარათს აქვს ერთი და იგივე 17 ველი.

## ბარათი 1 — Angular 22.1.0

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Angular `22.1.0`; CLI/build `22.1.3` |
| 2. რა არის | TypeScript-ზე დაფუძნებული frontend framework — მზა წესები ეკრანების ასაწყობად. |
| 3. ზოგადი პრობლემა | დიდ UI-ში კომპონენტებს, მდგომარეობას, ნავიგაციასა და API კავშირებს ორგანიზებას აძლევს. |
| 4. აქ გამოყენება | portal-ის ყველა ახალი გვერდი, layout, admin ეკრანი, guard და service. |
| 5. სად ჩანს | `angular-frontend/src/app/`, `package.json`, `angular.json` |
| 6. მაგალითი | `/article/:id` route ხსნის `ArticleDetailPage`-ს და API-დან სტატიას ტვირთავს. |
| 7. ურთიერთობა | TypeScript, RxJS, Router, Forms, Nginx, Java REST API. |
| 8. მის გარეშე | UI-ს Vanilla JavaScript-ით ხელით ორგანიზება მოგვიწევდა და ზრდასთან ერთად სირთულე მოიმატებდა. |
| 9. ძალა | მკაფიო არქიტექტურა, dependency injection, routing/forms/testing ეკოსისტემა. |
| 10. რისკი | შედარებით მძიმეა; major upgrade-ები დაგეგმვასა და გუნდის ცოდნას მოითხოვს. |
| 11. ალტერნატივა | React, Vue, server-rendered HTML. |
| 12. რატომ ალტერნატივა | React — მოქნილობა/დიდი ბაზარი; Vue — მარტივი შესვლა; server HTML — პატარა, ნაკლებად ინტერაქტიული სისტემა. |
| 13. არჩევანის შეფასება | მრავალროლიანი, ბევრი admin flow-ს მქონე portal-ისთვის გონივრულია. |
| 14. PO-მ იცოდეს | frontend validation და hidden button უსაფრთხოების საბოლოო გარანტია არ არის. |
| 15. Development | კომპონენტები, accessibility, state/API კავშირი, tests, bundle ზომა. |
| 16. IT/სხვა | Nginx hosting, browser policy, cache/TLS; PO — UX წესი. |
| 17. დაიმახსოვრე | **Angular არის ახალი portal-ის ბრაუზერში გაშვებული ეკრანის სისტემა.** |

## ბარათი 2 — TypeScript 6.0.3 და JavaScript

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | TypeScript lock-ში `6.0.3`; JavaScript browser runtime-ის ენაა. |
| 2. რა არის | TypeScript არის JavaScript დამატებითი ტიპებით; build-ისას JavaScript-ად გარდაიქმნება. |
| 3. ზოგადი პრობლემა | ტიპები ბევრ შეცდომას გაშვებამდე პოულობს და დიდ კოდს გასაგებს ხდის. |
| 4. აქ გამოყენება | Angular component/service/model/test ფაილები `.ts`; browser ასრულებს მათ compiled JavaScript-ს. |
| 5. სად ჩანს | `angular-frontend/src/**/*.ts`, `tsconfig*.json` |
| 6. მაგალითი | `Article` interface ადგენს რა fields მოელის UI backend-ის JSON-სგან. |
| 7. ურთიერთობა | Angular compiler, Node.js, npm, browser. |
| 8. მის გარეშე | შესაძლებელია JavaScript, მაგრამ API ფორმის შეცდომები გვიან გამოჩნდება. |
| 9. ძალა | IDE დახმარება, refactoring, compile-time შემოწმება. |
| 10. რისკი | ტიპი runtime გარანტია არაა; backend-ის არასწორი JSON მაინც უნდა დამუშავდეს. |
| 11. ალტერნატივა | plain JavaScript, Dart, Elm. |
| 12. რატომ ალტერნატივა | JavaScript — პატარა პროექტი; Dart/Elm — სხვა ეკოსისტემა/ძლიერი კონტროლი. |
| 13. არჩევანის შეფასება | Angular-ის ბუნებრივი და გამართლებული არჩევანია. |
| 14. PO-მ იცოდეს | TypeScript type API contract-ის დოკუმენტია, მაგრამ contract test-ს ვერ ცვლის. |
| 15. Development | მკაცრი ტიპები, model-ების backend-თან სინქრონი, nullable/error შემთხვევები. |
| 16. IT/სხვა | browser support policy; ინფრასტრუქტურული მოვლა მცირეა. |
| 17. დაიმახსოვრე | **TypeScript დეველოპერს შეცდომის ადრე დანახვაში ეხმარება; browser ბოლოს JavaScript-ს ასრულებს.** |

## ბარათი 3 — HTML, CSS და Tailwind CSS 3.4.19

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | HTML/CSS web standards; Tailwind CSS `3.4.19`. |
| 2. რა არის | HTML აწყობს შინაარსის ჩონჩხს; CSS აფორმებს; Tailwind მზა პატარა CSS კლასებს იძლევა. |
| 3. ზოგადი პრობლემა | გვერდის სტრუქტურა, ფერი, ზომა, განლაგება და responsive ქცევა. |
| 4. აქ გამოყენება | Angular templates, global/component styles, dark mode, responsive utilities. |
| 5. სად ჩანს | `src/app/**/*.html`, `src/styles.css`, `tailwind.config.js` |
| 6. მაგალითი | admin cards desktop-ზე grid-ად, ვიწრო ეკრანზე ერთ სვეტად ლაგდება. |
| 7. ურთიერთობა | Angular templates, Font Awesome, browser, accessibility. |
| 8. მის გარეშე | browser-ს შინაარსისა და ვიზუალური განლაგების სწორი აღწერა არ ექნება. |
| 9. ძალა | standards უნივერსალურია; Tailwind აჩქარებს თანმიმდევრულ styling-ს. |
| 10. რისკი | კლასებით გადატვირთული template; design-token დისციპლინის საჭიროება. |
| 11. ალტერნატივა | Sass, Bootstrap, Material, CSS Modules. |
| 12. რატომ ალტერნატივა | Bootstrap/Material — მზა კომპონენტები; Sass — რთული custom CSS; Modules — იზოლაცია. |
| 13. არჩევანის შეფასება | custom portal დიზაინისთვის გონივრულია; mobile ოფიციალურად target scope-ში არაა. |
| 14. PO-მ იცოდეს | responsive კოდი არსებობს, მაგრამ პროდუქტის მხარდაჭერილი სამიზნე desktop Chrome/1080p-ია. |
| 15. Development | semantic HTML, keyboard/ARIA, contrast, 100–200% font scale, responsive QA. |
| 16. IT/სხვა | browser policy; PO — design/accessible acceptance criteria. |
| 17. დაიმახსოვრე | **HTML არის ჩონჩხი, CSS გარეგნობა, Tailwind კი CSS-ის სწრაფი სამუშაო ლექსიკონი.** |

## ბარათი 4 — Angular Router, SPA და responsive navigation

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Angular Router `22.1.0`; SPA (Single Page Application). |
| 2. რა არის | route URL-ს ეკრანთან აკავშირებს; SPA სრული HTML გვერდის ყოველ click-ზე თავიდან ჩატვირთვას არ საჭიროებს. |
| 3. ზოგადი პრობლემა | სწრაფი ნავიგაცია, deep link, guard და browser history. |
| 4. აქ გამოყენება | 33 path declaration; app shell-ის ქვეშ user/admin გვერდები. |
| 5. სად ჩანს | `app.routes.ts`, `app.config.ts`, `shell/`, route guards |
| 6. მაგალითი | `/admin/audit` შესაბამის admin გვერდს guard-ის შემდეგ ხსნის. |
| 7. ურთიერთობა | Angular, Nginx `try_files ... /index.html`, auth guards. |
| 8. მის გარეშე | გვერდების ხელით გადართვა ან მრავალი server HTML დაგვჭირდებოდა. |
| 9. ძალა | სწრაფი UX, bookmark/deep link, lazy loading-ის შესაძლებლობა. |
| 10. რისკი | Nginx fallback-ის გარეშე refresh-ზე 404; guard მარტო security არაა. |
| 11. ალტერნატივა | server routing, React Router, Vue Router. |
| 12. რატომ ალტერნატივა | server rendering SEO/simple pages-ს ერგება; სხვა routers — სხვა framework-ს. |
| 13. არჩევანის შეფასება | შიდა ინტერაქტიული portal-ისთვის SPA გონივრულია. |
| 14. PO-მ იცოდეს | route-ის დამალვა UX-ია; endpoint-ის დაცვა backend-ის საქმეა. |
| 15. Development | route map, guards, 404, deep-link და responsive menu tests. |
| 16. IT/სხვა | Nginx fallback/cache; PO — მხარდაჭერილი ეკრანები. |
| 17. დაიმახსოვრე | **Router განსაზღვრავს რომელ URL-ზე რომელი Angular ეკრანი გამოჩნდეს.** |

## ბარათი 5 — Angular Forms და RxJS 7.8.2

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Angular Forms `22.1.0`; RxJS `7.8.2`. |
| 2. რა არის | Forms მართავს ველებს/ვალიდაციას; RxJS asynchronous მოვლენებს Observable ნაკადად ამუშავებს. |
| 3. ზოგადი პრობლემა | input-ის მდგომარეობა, შეცდომები, API პასუხები და გაუქმებადი ნაკადები. |
| 4. აქ გამოყენება | login/admin forms; HTTP calls, search chaining, component cleanup. |
| 5. სად ჩანს | login, admin assignment components, `core/services/*.ts` |
| 6. მაგალითი | სტატიის ID იცვლება, `switchMap` შესაბამის GET-ს უკავშირებს. |
| 7. ურთიერთობა | Angular HttpClient, signals, TypeScript. |
| 8. მის გარეშე | manual event/state/callback კოდი მოიმატებდა. |
| 9. ძალა | declarative async composition; standard validation. |
| 10. რისკი | RxJS სწავლის მრუდი; subscription leak ან race არასწორი operator-ით. |
| 11. ალტერნატივა | Promises/async-await, native forms, state libraries. |
| 12. რატომ ალტერნატივა | Promise მარტივი ერთჯერადი call-ისთვის; state library რთული global state-ისთვის. |
| 13. არჩევანის შეფასება | Angular HttpClient-ის ბუნებრივი stack-ია. |
| 14. PO-მ იცოდეს | frontend validation მომხმარებელს ეხმარება; backend validation მაინც სავალდებულოა. |
| 15. Development | validation parity, cancellation, loading/error state, unsubscribe. |
| 16. IT/სხვა | პასუხისმგებლობა უმეტესად development; PO — error UX. |
| 17. დაიმახსოვრე | **Forms აკონტროლებს შეყვანას, RxJS კი დროში მოსულ მოვლენებსა და პასუხებს.** |

## ბარათი 6 — Chart.js 4.5.1

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Chart.js `4.5.1`. |
| 2. რა არის | JavaScript ბიბლიოთეკა დიაგრამებისთვის. |
| 3. ზოგადი პრობლემა | რიცხვების ვიზუალურად აღქმა. |
| 4. აქ გამოყენება | admin statistics გვერდის charts. |
| 5. სად ჩანს | `features/admin-stats/`, `package.json` |
| 6. მაგალითი | სტატიის/წაკითხვის მაჩვენებელი chart-ად ჩანს. |
| 7. ურთიერთობა | Angular canvas/component, stats REST API. |
| 8. მის გარეშე | ცხრილი ან custom canvas/SVG კოდი დაგვჭირდებოდა. |
| 9. ძალა | გავრცელებული, მოქნილი, მრავალი chart type. |
| 10. რისკი | accessibility-სთვის ცხრილოვანი/ტექსტური ალტერნატივა საჭიროა; დიდი dataset ძვირია. |
| 11. ალტერნატივა | ECharts, D3.js, Highcharts. |
| 12. რატომ ალტერნატივა | ECharts — dashboard; D3 — სრული custom; Highcharts — enterprise support. |
| 13. არჩევანის შეფასება | portal-ის საშუალო სირთულის charts-ს ერგება. |
| 14. PO-მ იცოდეს | chart-ის ლამაზი ხედი არ ასწორებს არასწორ metric definition-ს. |
| 15. Development | correct aggregation, labels, color/keyboard accessibility. |
| 16. IT/სხვა | მცირე; PO განსაზღვრავს metric-სა და data scope-ს. |
| 17. დაიმახსოვრე | **Chart.js API-დან მიღებულ სტატისტიკას დიაგრამად აჩვენებს.** |

## ბარათი 7 — Quill 1.3.7 და DOMPurify 3.4.13

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Quill `1.3.7`; DOMPurify `3.4.13`. |
| 2. რა არის | Quill rich-text editor-ია; DOMPurify არასანდო HTML-ს საშიში ნაწილებისგან წმენდს. |
| 3. ზოგადი პრობლემა | ფორმატირებული კონტენტის შექმნა და XSS რისკის შემცირება. |
| 4. აქ გამოყენება | article editor; განსაკუთრებით paste-ით შემოსული HTML/data-URI image sanitation. |
| 5. სად ჩანს | `shared/rich-text-editor/`, `package.json` |
| 6. მაგალითი | Word/web-დან ჩასმული HTML DOMPurify-ს გაივლის და შემდეგ editor-ში შედის. |
| 7. ურთიერთობა | Angular, HTML, upload API, Angular sanitizer. |
| 8. მის გარეშე | plain text ან custom editor; sanitizer-ის გარეშე XSS რისკი იზრდება. |
| 9. ძალა | WYSIWYG editing; DOMPurify გამოცდილი sanitizer-ია. |
| 10. რისკი | Quill 1.x ძველი major-ია; sanitation policy-ს ტესტი და განახლება სჭირდება. |
| 11. ალტერნატივა | CKEditor, TinyMCE, TipTap/ProseMirror. |
| 12. რატომ ალტერნატივა | enterprise workflows, უფრო აქტიური editor core, structured content. |
| 13. არჩევანის შეფასება | მიმდინარე editor-ისთვის მუშაობს; Quill upgrade/security review დაგეგმვად ღირს. |
| 14. PO-მ იცოდეს | DOMPurify რისკს ამცირებს, მაგრამ upload/auth/CSP-ს ვერ ანაცვლებს. |
| 15. Development | allowlist, paste/upload paths, preview/render parity, dependency updates. |
| 16. IT/სხვა | Security — content policy/CSP; IT — malware scanning თუ მოითხოვება. |
| 17. დაიმახსოვრე | **Quill ქმნის მდიდარ ტექსტს; DOMPurify მასში საშიში HTML-ის მოხვედრის რისკს ამცირებს.** |

## ბარათი 8 — Font Awesome 7.3.1 და ngx-translate 18.0.0

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Font Awesome `7.3.1`; ngx-translate core/loader `18.0.0`. |
| 2. რა არის | პირველი icon library-ა; მეორე ტექსტებს locale JSON-იდან ტვირთავს. |
| 3. ზოგადი პრობლემა | თანმიმდევრული icons და მრავალენოვანი ტექსტის მართვა. |
| 4. აქ გამოყენება | UI icons; `ka.json` default/fallback და არსებული `en.json`. |
| 5. სად ჩანს | `angular.json`, `public/i18n/`, `app.config.ts` |
| 6. მაგალითი | translation key ქართულ label-ად გარდაიქმნება. |
| 7. ურთიერთობა | Angular template pipes, HTTP loader, CSS. |
| 8. მის გარეშე | ხელით SVG/text; ტექსტები component-ებში გაიფანტებოდა. |
| 9. ძალა | ცენტრალიზებული labels; ცნობადი icon სისტემა. |
| 10. რისკი | missing key; icon-ის აზრი label-ის გარეშე ბუნდოვანი; English ინფრასტრუქტურა target-ს ეწინააღმდეგება. |
| 11. ალტერნატივა | inline SVG/Material Icons; Angular built-in i18n/Transloco. |
| 12. რატომ ალტერნატივა | SVG — მცირე bundle/custom; built-in i18n — compile-time locale; Transloco — სხვა API. |
| 13. არჩევანის შეფასება | ngx-translate ჯერ აქტიურად გამოიყენება, თუმცა პროდუქტის target Georgian-only-ია; English switch-ის ბედი PO-მ უნდა დაადასტუროს. |
| 14. PO-მ იცოდეს | თარგმანის ტექნიკური შესაძლებლობა არ უდრის მრავალენოვნების პროდუქტის გადაწყვეტილებას. |
| 15. Development | keys, fallback, accessible labels, dead English flow-ის cleanup გადაწყვეტილების შემდეგ. |
| 16. IT/სხვა | PO — ენობრივი scope; ინფრასტრუქტურული პასუხისმგებლობა მცირეა. |
| 17. დაიმახსოვრე | **icons ვიზუალურ ნიშნებს იძლევა, ngx-translate კი ეკრანის ტექსტს locale ფაილიდან მართავს.** |

## ბარათი 9 — Browser storage და cookie-ები

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Web platform `localStorage`; HTTP cookie (`HttpOnly`, production-ში `Secure`, `SameSite=Lax`). |
| 2. რა არის | localStorage-ს JavaScript კითხულობს; HttpOnly cookie-ს JavaScript ვერ კითხულობს და browser ავტომატურად აგზავნის. |
| 3. ზოგადი პრობლემა | session/token და UI preference-ის შენარჩუნება. |
| 4. აქ გამოყენება | სრული signed JWT `magti_token` localStorage-ში; backend იგივე token-ს cookie-შიც წერს; theme/font/sidebar ასევე localStorage-შია. |
| 5. სად ჩანს | Angular auth/theme services; Java auth/JWT config. |
| 6. მაგალითი | reload-ის შემდეგ interceptor token-ს localStorage-დან იღებს. |
| 7. ურთიერთობა | JWT, XSS, CSRF, HTTPS, Spring Security. |
| 8. მის გარეშე | reload-ზე login დაიკარგებოდა ან server session დაგვჭირდებოდა. |
| 9. ძალა | localStorage მარტივია; HttpOnly cookie token-ს JS წაკითხვისგან იცავს. |
| 10. რისკი | XSS localStorage token-ს მოიპარავს; cookie auth CSRF threat-model-ს მოითხოვს. |
| 11. ალტერნატივა | memory-only access token + refresh cookie; server-side session. |
| 12. რატომ ალტერნატივა | token theft window მცირდება; session-ს ცენტრალური revoke მარტივდება. |
| 13. არჩევანის შეფასება | მიმდინარე dual path მუშაობს, მაგრამ security architecture review სჭირდება, განსაკუთრებით IdP-ისას. |
| 14. PO-მ იცოდეს | `docs/archive/legacy-stack/ARCHITECTURE.md`-ის „unsigned shell localStorage-ში“ მტკიცება მიმდინარე კოდს ეწინააღმდეგება. |
| 15. Development | ერთი მკაფიო auth transport, expiry/revoke/CSRF/XSS tests. |
| 16. IT/სხვა | Security — threat model; IT — HTTPS/domain/proxy; PO — session UX. |
| 17. დაიმახსოვრე | **localStorage-ს გვერდის კოდი ხედავს, HttpOnly cookie-ს — არა; ამიტომ მათი რისკები განსხვავდება.** |

## ბარათი 10 — Java 21

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Java `21` LTS target. |
| 2. რა არის | ძლიერ ტიპიზებული server-side პროგრამირების ენა და runtime. |
| 3. ზოგადი პრობლემა | გრძელვადიანი, მრავალმომხმარებლიანი ბიზნეს backend-ის აშენება. |
| 4. აქ გამოყენება | controllers, services, entities, repositories, security, jobs, tests. |
| 5. სად ჩანს | `java-backend/src/`, `pom.xml`, Dockerfile Temurin 21. |
| 6. მაგალითი | `CapabilityService` role + override-იდან ეფექტურ უფლებას ითვლის. |
| 7. ურთიერთობა | Spring Boot, Maven, Oracle JDBC, JUnit. |
| 8. მის გარეშე | სხვა backend ენა/framework დაგვჭირდებოდა. |
| 9. ძალა | LTS, type safety, mature enterprise/Oracle ecosystem. |
| 10. რისკი | მეტი boilerplate/მეხსიერება და პროფესიული ცოდნის საჭიროება. |
| 11. ალტერნატივა | C#/.NET, Node/NestJS, Python/FastAPI. |
| 12. რატომ ალტერნატივა | .NET enterprise stack; Node ერთიანი TS; Python სწრაფი prototype. |
| 13. არჩევანის შეფასება | Oracle/enterprise/on-prem მოთხოვნებისთვის ძლიერი და გონივრულია. |
| 14. PO-მ იცოდეს | Java ენაა; Spring Boot framework-ია — ერთი და იგივე არაა. |
| 15. Development | domain წესები, concurrency, secure error handling, maintainability. |
| 16. IT/სხვა | Java runtime/image patching, memory/CPU limits, vulnerability process. |
| 17. დაიმახსოვრე | **Java არის backend-ის ძირითადი ენა, რომელზეც პროდუქტის წესები სრულდება.** |

## ბარათი 11 — Spring Boot 4.1.0 და Spring MVC / Framework 7.0.8

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Spring Boot `4.1.0`; Spring Framework/MVC `7.0.8`. |
| 2. რა არის | Java framework-ები web server-ის, configuration-ისა და application lifecycle-ისთვის. |
| 3. ზოგადი პრობლემა | HTTP API, beans, transactions, validation და production features სტანდარტულად აწყობა. |
| 4. აქ გამოყენება | ერთი synchronous servlet modular monolith; microservices/WebFlux არა. |
| 5. სად ჩანს | `pom.xml`, `PortalBackendApplication`, `config/`, `web/`. |
| 6. მაგალითი | annotation-იანი controller `/api/articles` endpoint-ს ქმნის. |
| 7. ურთიერთობა | Java, Tomcat, JPA, Security, Actuator. |
| 8. მის გარეშე | HTTP server, serialization და wiring ხელით ან სხვა framework-ით დაგვჭირდებოდა. |
| 9. ძალა | conventions, mature modules, testing/operations ecosystem. |
| 10. რისკი | magic/convention-ის არასწორი გაგება; major 4.x სიახლეებთან dependency compatibility. |
| 11. ალტერნატივა | Quarkus, Micronaut, Jakarta EE, .NET. |
| 12. რატომ ალტერნატივა | fast startup/cloud-native; lower memory; application-server standard. |
| 13. არჩევანის შეფასება | გუნდის enterprise Java უნარისას გონივრული; 4.1 dependency support უნდა კონტროლდებოდეს. |
| 14. PO-მ იცოდეს | სისტემა ერთი deployable backend-ია; package-ებად გაყოფა microservices-ს არ ნიშნავს. |
| 15. Development | module boundaries, transactions, API stability, upgrades. |
| 16. IT/სხვა | JVM/container sizing, health probes, runtime patching. |
| 17. დაიმახსოვრე | **Spring Boot Java backend-ს ერთ მართვად, გასაშვებ აპლიკაციად აწყობს.** |

## ბარათი 12 — REST API, JSON და web-layer ობიექტები

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | HTTP REST-style API + JSON; 120 mapping annotation მიმდინარე source-ში. |
| 2. რა არის | API არის შეთანხმებული „ფანჯარა“; request კითხვა/ბრძანებაა, response პასუხი; JSON ტექსტური მონაცემთა ფორმატია. |
| 3. ზოგადი პრობლემა | frontend/backend დამოუკიდებელ ნაწილებს მკაფიო contract-ით აკავშირებს. |
| 4. აქ გამოყენება | Controller იღებს; Request DTO ზღუდავს input-ს; Service ასრულებს წესს; Response DTO აბრუნებს. |
| 5. სად ჩანს | `java-backend/.../web/`, Angular `core/services/`, models. |
| 6. მაგალითი | `GET /api/articles/{id}` → სტატიის Response JSON. |
| 7. ურთიერთობა | Nginx, Jackson, validation, Angular HttpClient. |
| 8. მის გარეშე | UI database-ს პირდაპირ შეეხებოდა ან tightly coupled გახდებოდა. |
| 9. ძალა | მკაფიო საზღვარი, testing, სხვა client-ის შესაძლებლობა. |
| 10. რისკი | contract-ის breaking change frontend-ს აფუჭებს; HTTP status semantics საჭიროა. |
| 11. ალტერნატივა | GraphQL, gRPC, server-rendered HTML. |
| 12. რატომ ალტერნატივა | GraphQL მოქნილი query; gRPC service-to-service; server HTML მარტივი UI. |
| 13. არჩევანის შეფასება | browser CRUD portal-ისთვის REST/JSON პრაქტიკული არჩევანია. |
| 14. PO-მ იცოდეს | endpoint მხოლოდ მისამართი არაა — input, output, errors და access ერთ contract-ს ქმნის. |
| 15. Development | backward compatibility, DTO isolation, status/errors, contract tests. |
| 16. IT/სხვა | gateway/proxy limits; Security — sensitive fields; PO — business semantics. |
| 17. დაიმახსოვრე | **REST API არის frontend-სა და backend-ს შორის ხელშეკრულება.** |

## ბარათი 13 — Dependency Injection, Validation და Global Exception Handling

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Spring DI/Bean Validation/exception advice — Spring Boot stack. |
| 2. რა არის | DI საჭირო ობიექტს აწვდის; validation input-ს ამოწმებს; global handler შეცდომას ერთნაირ პასუხად აქცევს. |
| 3. ზოგადი პრობლემა | loose coupling, სწორი input და პროგნოზირებადი errors. |
| 4. აქ გამოყენება | constructor injection; Jakarta annotations; 400/409/500 mapping და correlation id. |
| 5. სად ჩანს | services/controllers constructors, request records, `GlobalExceptionHandler`. |
| 6. მაგალითი | optimistic conflict → HTTP 409; არასწორი body → 400. |
| 7. ურთიერთობა | Spring MVC, Service, logging, Angular error UI. |
| 8. მის გარეშე | manual wiring, duplicated checks, stack trace-ის client-ში გაჟონვის რისკი. |
| 9. ძალა | consistency და testability. |
| 10. რისკი | მხოლოდ annotation business validation-ს ვერ ცვლის; ზედმეტად ზოგადი 500 debugging-ს ართულებს. |
| 11. ალტერნატივა | manual factories/checks, other DI/validation libraries. |
| 12. რატომ ალტერნატივა | პატარა აპში ნაკლები framework; სპეციალური validation engine. |
| 13. არჩევანის შეფასება | მიმდინარე Spring არქიტექტურის სწორი ნაწილია. |
| 14. PO-მ იცოდეს | „ფორმამ მიიღო“ არ ნიშნავს „ბიზნესწესი დაკმაყოფილდა“. |
| 15. Development | input/business validation, stable error codes, safe logs. |
| 16. IT/სხვა | centralized log correlation; PO — error/recovery UX. |
| 17. დაიმახსოვრე | **DI აერთებს ნაწილებს, validation ცუდ input-ს აჩერებს, handler კი შეცდომას გასაგებ პასუხად აქცევს.** |

## ბარათი 14 — Spring Security 7.1.0, JWT 0.12.6 და BCrypt

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Spring Security `7.1.0`; JJWT `0.12.6`; BCrypt. |
| 2. რა არის | security framework; JWT ხელმოწერილი identity claim-ია; BCrypt password hash-ია. |
| 3. ზოგადი პრობლემა | ვინ არის user, token ნამდვილია თუ არა, და პაროლი plaintext-ად არ ინახებოდეს. |
| 4. აქ გამოყენება | Bearer/cookie filter, 60-წუთიანი HS256 token, live Oracle user check, token_version revoke. |
| 5. სად ჩანს | `security/`, `SecurityConfig`, auth controller/service. |
| 6. მაგალითი | logout increment-ს უკეთებს `token_version`-ს და ძველ JWT-ებს უარყოფს. |
| 7. ურთიერთობა | Oracle user, roles, cookies, HTTPS, IdP target. |
| 8. მის გარეშე | ნებისმიერი client თავს სხვა მომხმარებლად გაასაღებდა. |
| 9. ძალა | signature/expiry; live deactivation; BCrypt salted one-way hashing. |
| 10. რისკი | JWT secret-ის გაჟონვა კრიტიკულია; `anyRequest().permitAll()` გამო ყველა controller-ს manual gate სჭირდება; CSRF disabled მიუხედავად cookie path-ისა. |
| 11. ალტერნატივა | company OIDC/SAML, server sessions, opaque tokens. |
| 12. რატომ ალტერნატივა | SSO/MFA/central lifecycle; central revoke; ნაკლები client claims. |
| 13. არჩევანის შეფასება | signed token/current guard სასარგებლოა, მაგრამ local password საბოლოო target არაა და IdP integration IT-ზეა. |
| 14. PO-მ იცოდეს | authentication ამბობს „ვინ ხარ“; authorization — „რისი უფლება გაქვს“. |
| 15. Development | deny-by-default, endpoint gates, key rotation design, CSRF/XSS tests. |
| 16. IT/სხვა | IdP, secret manager, TLS; Security — threat model/MFA/session policy. |
| 17. დაიმახსოვრე | **JWT identity-ს ადასტურებს, მაგრამ უფლებას backend ცალკე ამოწმებს.** |

## ბარათი 15 — Role, permission/capability და data scope

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | 4 canonical role; role defaults + per-user ALLOW/DENY override; legacy scope enforcement. |
| 2. რა არის | role სამუშაო ტიპია; permission ქმედებაა; scope პასუხობს „ვის მონაცემებზე?“. |
| 3. ზოგადი პრობლემა | least privilege და ორგანიზაციული საზღვრები. |
| 4. აქ გამოყენება | OPERATOR, MANAGER, CONTENT_ADMIN, SYSTEM_ADMIN; `CapabilityService`; manager department scope. |
| 5. სად ჩანს | security/services, permission override migration V36/V36.1, controllers. |
| 6. მაგალითი | მენეჯერს export უფლება შეიძლება ჰქონდეს, მაგრამ მხოლოდ თავისი scope-ის ხალხზე. |
| 7. ურთიერთობა | Spring Security, Oracle org tables, audit, Angular guards. |
| 8. მის გარეშე | ყველა authenticated user ყველაფერს ნახავდა/შეცვლიდა. |
| 9. ძალა | action და data boundary ცალ-ცალკე; explicit deny override. |
| 10. რისკი | SYSTEM_ADMIN bypass; manual endpoint gates; ახალი leadership resolver ჯერ shadow-only-ა. |
| 11. ალტერნატივა | pure RBAC, ABAC/policy engine, IdP groups. |
| 12. რატომ ალტერნატივა | RBAC მარტივია; ABAC რთულ პირობებს; groups ცენტრალურ მართვას. |
| 13. არჩევანის შეფასება | capability layer გონივრულია; data scope cutover ჯერ დასრულებული არაა. |
| 14. PO-მ იცოდეს | „შეუძლია export“ არ პასუხობს „ვისი ჩანაწერების export“. |
| 15. Development | gate coverage, scope query, override concurrency/audit, rollout. |
| 16. IT/სხვა | IT — authoritative org/AD data; Security — privileged access; PO — business visibility. |
| 17. დაიმახსოვრე | **Role აჯგუფებს, permission მოქმედებას აძლევს, scope მონაცემების საზღვარს ავლებს.** |

## ბარათი 16 — JPA და Hibernate 7.4.1.Final

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Jakarta Persistence/JPA; Hibernate `7.4.1.Final`. |
| 2. რა არის | JPA specification-ია; Hibernate მისი implementation, რომელიც Java entity-ს SQL-თან აკავშირებს. |
| 3. ზოგადი პრობლემა | repetitive SQL mapping და transaction-aware persistence. |
| 4. აქ გამოყენება | 32 entity + 32 repository; `ddl-auto=validate`, schema-ს არ ცვლის. |
| 5. სად ჩანს | `domain/`, `repository/`, `pom.xml`, application config. |
| 6. მაგალითი | `Article` entity row-ად ინახება, repository კი ID-ით პოულობს. |
| 7. ურთიერთობა | Oracle, JDBC/Hikari, Spring transactions, Flyway. |
| 8. მის გარეშე | JDBC SQL და mapping ხელით დაიწერებოდა. |
| 9. ძალა | unit of work, relations, optimistic version, repositories. |
| 10. რისკი | N+1 queries, lazy loading, generated SQL-ის გაუგებრობა, Oracle dialect mismatch. |
| 11. ალტერნატივა | plain JDBC, jOOQ, MyBatis. |
| 12. რატომ ალტერნატივა | SQL-ზე სრული კონტროლი, complex reporting, ნაკლები ORM magic. |
| 13. არჩევანის შეფასება | CRUD/domain portal-ისთვის გონივრულია; performance query-ები პროფილირებას საჭიროებს. |
| 14. PO-მ იცოდეს | entity schema არაა; Flyway არის schema source of truth. |
| 15. Development | mappings, query plans, transaction boundaries, N+1 tests. |
| 16. IT/სხვა | DBA — indexes/plans/statistics; Development — ORM behavior. |
| 17. დაიმახსოვრე | **JPA წესია, Hibernate კი Java ობიექტებს Oracle-ის rows-ად გარდაქმნის.** |

## ბარათი 17 — Oracle, JDBC 23.26.2 და HikariCP 7.0.2

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Oracle target 19c; local/CI XE 21c; ojdbc11 `23.26.2.0.0`; HikariCP `7.0.2`. |
| 2. რა არის | Oracle database; JDBC Java driver; Hikari reusable connection pool. |
| 3. ზოგადი პრობლემა | durable relational data და ეფექტური concurrent connections. |
| 4. აქ გამოყენება | ძირითადი current store; pool max 30, timeout 10s, leak detection 60s. |
| 5. სად ჩანს | application config, `pom.xml`, Flyway, `docker-compose.local.yml`. |
| 6. მაგალითი | ArticleRepository pool-იდან connection-ით Oracle query-ს ასრულებს. |
| 7. ურთიერთობა | Hibernate/JPA, Flyway, backups, DBA. |
| 8. მის გარეშე | სხვა database/driver/pool დაგვჭირდებოდა; connection-ის ყოველ request-ზე შექმნა ძვირი იქნებოდა. |
| 9. ძალა | enterprise transactions, constraints, Oracle tooling; სწრაფი pool. |
| 10. რისკი | license/operations/skills; pool limit; 23ai native BOOLEAN current mappings-ს migration-ის გარეშე არღვევს. |
| 11. ალტერნატივა | PostgreSQL, SQL Server, MySQL. |
| 12. რატომ ალტერნატივა | open source; Microsoft ecosystem; simpler commodity workloads. |
| 13. არჩევანის შეფასება | კომპანიის Oracle სტანდარტისას ლოგიკურია; version/HA/capacity IT-მ უნდა დაადასტუროს. |
| 14. PO-მ იცოდეს | 600 user არ ნიშნავს 600 DB connection-ს; concurrent workload/pool/load test მთავარია. |
| 15. Development | efficient queries, short transactions, retries, compatibility. |
| 16. IT/DBA | provisioning, accounts/grants, tablespace, backup, patch, monitoring, HA. |
| 17. დაიმახსოვრე | **Oracle ინახავს მონაცემს, JDBC აკავშირებს Java-ს, Hikari connection-ებს ხელახლა იყენებს.** |

## ბარათი 18 — Flyway 12.4.0 და schema migration

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Flyway `12.4.0`; 37 versioned scripts, უმაღლესი `V36_1`. |
| 2. რა არის | database ცვლილებების თანმიმდევრული, version-controlled გამშვები. |
| 3. ზოგადი პრობლემა | ყველა გარემოში schema ერთნაირი და აუდიტირებადი იყოს. |
| 4. აქ გამოყენება | table/constraint/index/backfill/trigger ცვლილებები V1–V36.1. |
| 5. სად ჩანს | `java-backend/src/main/resources/db/migration/`, `flyway_schema_history`. |
| 6. მაგალითი | V36 ქმნის org/leadership/permission override სტრუქტურას; V36.1 legacy permissions-ს backfill-ს უკეთებს. |
| 7. ურთიერთობა | Oracle SQL, Spring startup, CI fresh-schema test. |
| 8. მის გარეშე | DBA-ს ხელით, გარემოებს შორის აცდენილი SQL გაჩნდებოდა. |
| 9. ძალა | order, checksum, locking, repeatable deployment. |
| 10. რისკი | ცუდი irreversible migration; production data volume/lock; down scripts არ არსებობს. |
| 11. ალტერნატივა | Liquibase, DBA scripts, Hibernate ddl-auto. |
| 12. რატომ ალტერნატივა | Liquibase XML/YAML/rollback; DBA control; ddl-auto მხოლოდ throwaway dev-ში. |
| 13. არჩევანის შეფასება | Oracle production-ისთვის სწორი არჩევანია; backup/forward-fix პროცესი IT-მ დაამტკიცოს. |
| 14. PO-მ იცოდეს | code rollback database rollback არ არის; migration plan ცალკეა. |
| 15. Development | additive/compatible scripts, data backfill, integration test, runbook. |
| 16. IT/DBA | privilege/window/backup/monitoring/restore approval. |
| 17. დაიმახსოვრე | **Flyway არის Oracle schema-ს ცვლილებების დანომრილი ისტორია.** |

## ბარათი 19 — SQL/schema, CLOB/BLOB, trigram index და audit chain

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Oracle SQL/schema features; custom V29 trigram; V28 SHA-256 audit chain. |
| 2. რა არის | SQL database ენაა; schema სტრუქტურა; CLOB დიდი ტექსტი; BLOB binary; index სწრაფი საძიებო რუკა. |
| 3. ზოგადი პრობლემა | სტრუქტურირებული შენახვა, დიდი content/files, სწრაფი ძებნა და audit tamper evidence. |
| 4. აქ გამოყენება | article/news/message ტექსტი CLOB; files/exports BLOB; `search_trigrams`; chained `audit_logs`. |
| 5. სად ჩანს | Flyway V1–V36.1, repositories/services. |
| 6. მაგალითი | audit insert trigger წინა hash-სა და ახალ row-ს აერთიანებს და ახალ hash-ს წერს. |
| 7. ურთიერთობა | Oracle transaction/constraints, search service, POI/PDFBox. |
| 8. მის გარეშე | large data ვერ ჩაეტეოდა; search full scan; audit ცვლილების კვალი სუსტი იქნებოდა. |
| 9. ძალა | DB-level integrity; tamper-evident chain; candidate narrowing. |
| 10. რისკი | BLOB capacity/backup; chain insertion serialization; trigram search fuzzy/transliteration არაა; app audit coverage არასრულია. |
| 11. ალტერნატივა | S3/MinIO; Oracle Text/Elasticsearch; external immutable audit store. |
| 12. რატომ ალტერნატივა | object scale; richer search; stronger isolation/WORM retention. |
| 13. არჩევანის შეფასება | current scale-ზე ერთ DB-ში სიმარტივეა; file/search/audit retention საბოლოოდ IT/DPO-სთან უნდა დამტკიცდეს. |
| 14. PO-მ იცოდეს | hash chain ცვლილებას ამოსაცნობს ხდის, მაგრამ ცვლილებას ფიზიკურად არ კრძალავს. |
| 15. Development | complete audit events, query/index correctness, checksum verification. |
| 16. IT/DBA/Security | tablespace, archive/WORM/access, backup, capacity, legal retention. |
| 17. დაიმახსოვრე | **CLOB ტექსტია, BLOB ფაილი, index აჩქარებს, audit chain კი ცვლილების კვალს ამოწმებინებს.** |

## ბარათი 20 — Optimistic locking, background jobs, scheduler, health და realtime

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | JPA `@Version`; Spring `@Async`/`@Scheduled`; Actuator dependency + custom `/api/health`; Java SSE აქტიური არაა. |
| 2. რა არის | version conflict-ს პოულობს; job ფონში მუშაობს; scheduler პერიოდულად; health მზადყოფნას ამოწმებს; SSE realtime push-ია. |
| 3. ზოგადი პრობლემა | concurrent overwrite, ხანგრძლივი export, cleanup და operational probe. |
| 4. აქ გამოყენება | Article/User lock version; export BLOB job+1h TTL; 10-წუთიანი cleanup; Oracle `SELECT 1 FROM dual` health. |
| 5. სად ჩანს | entities/services/config, ExportJobWorker, cleanup scheduler, HealthController. |
| 6. მაგალითი | ორი admin ერთ user-ს ცვლის: მეორე ძველი version-ით HTTP 409-ს იღებს. |
| 7. ურთიერთობა | Oracle transaction, global error handler, Docker/K8s probes. |
| 8. მის გარეშე | silent lost update; export request timeout; stale jobs; unhealthy replica traffic-ს მიიღებდა. |
| 9. ძალა | simple conflict detection; user სწრაფ პასუხს იღებს; durable job row. |
| 10. რისკი | async worker იგივე process-შია; multi-replica coordination/cleanup შესამოწმებელია; active SSE endpoint არ არსებობს. |
| 11. ალტერნატივა | pessimistic lock; queue worker; Quartz; WebSocket/SSE. |
| 12. რატომ ალტერნატივა | write contention; durable scale/retry; coordinated schedules; realtime UX. |
| 13. არჩევანის შეფასება | current workload-ზე მარტივია; replica count/queue/realtime საჭიროება დასადასტურებელია. |
| 14. PO-მ იცოდეს | 409 conflict სწორი დაცვაა და UI-მ merge/reload გზა უნდა აჩვენოს. |
| 15. Development | idempotent jobs, retry/status, conflict UX, health semantics. |
| 16. IT/სხვა | probes, replica scheduling, alerting; PO — realtime აუცილებლობა. |
| 17. დაიმახსოვრე | **Version იცავს silent overwrite-ს; ფონური job დიდ საქმეს request-ის გარეთ აკეთებს.** |

## ბარათი 21 — Nginx 1.27 და reverse proxy

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Nginx `1.27` container runtime. |
| 2. რა არის | სწრაფი web server და reverse proxy — ოფისის მიმღების მსგავსი. |
| 3. ზოგადი პრობლემა | static SPA-ს მიწოდება და `/api`/`/uploads` backend-ზე გადამისამართება. |
| 4. აქ გამოყენება | Angular production image port 8080, SPA fallback, forwarded headers. |
| 5. სად ჩანს | `angular-frontend/Dockerfile`, `nginx.conf.template`. |
| 6. მაგალითი | `/api/articles` browser-იდან Nginx-ს ხვდება და Java service-ზე გადადის. |
| 7. ურთიერთობა | Angular files, Java, TLS/ingress, trusted proxies. |
| 8. მის გარეშე | Java-ს static hosting ან სხვა proxy დაგვჭირდებოდა. |
| 9. ძალა | ეფექტური static/cache/proxy, პატარა runtime. |
| 10. რისკი | არასწორი proxy header/client IP; timeout/body-size/cache; TLS კონფიგი აქ არაა. |
| 11. ალტერნატივა | Apache, Caddy, Kubernetes ingress, cloud gateway. |
| 12. რატომ ალტერნატივა | არსებული სტანდარტი, automatic TLS, cluster-native routing. |
| 13. არჩევანის შეფასება | frontend container-ისთვის გონივრულია; production hop topology IT-მ უნდა დაადასტუროს. |
| 14. PO-მ იცოდეს | Nginx API წესებს არ წყვეტს; backend-ამდე მიმყვანი კარიბჭეა. |
| 15. Development | same-origin paths, headers/timeouts, SPA fallback. |
| 16. IT/DevOps | ingress/LB/TLS/DNS, proxy CIDR, rate/client-IP correctness. |
| 17. დაიმახსოვრე | **Nginx ეკრანის ფაილებს გასცემს და API მოთხოვნას Java-ს უგზავნის.** |

## ბარათი 22 — Docker, image და container

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Dockerfile standard; Node `22.22.3`, Nginx `1.27`, Temurin Java `21`; local Oracle XE 21c. |
| 2. რა არის | image არის შეფუთული შაბლონი; container მისი გაშვებული instance. |
| 3. ზოგადი პრობლემა | ერთნაირი runtime dev/test/prod-ს შორის. |
| 4. აქ გამოყენება | multi-stage Angular/Java images, non-root users, local compose. |
| 5. სად ჩანს | ორივე Dockerfile, `docker-compose.local.yml`. |
| 6. მაგალითი | Maven build stage JAR-ს აწყობს, runtime stage მხოლოდ JRE-სა და JAR-ს ატარებს. |
| 7. ურთიერთობა | Kubernetes/registry, Nginx, Java, Oracle. |
| 8. მის გარეშე | VM-ზე dependencies ხელით უნდა დაყენდეს. |
| 9. ძალა | reproducibility, isolation, immutable artifact. |
| 10. რისკი | stale/vulnerable base image; secret image-ში; local compose production HA არაა. |
| 11. ალტერნატივა | VM/systemd, buildpacks, managed platform. |
| 12. რატომ ალტერნატივა | მარტივი existing VM; standardized build; outsourced operations. |
| 13. არჩევანის შეფასება | Kubernetes target-ისთვის სწორი შეფუთვაა; registry/scanning/deploy IT-ზეა. |
| 14. PO-მ იცოდეს | image-ის არსებობა production deployment-ს არ ნიშნავს. |
| 15. Development | minimal/non-root image, health, config externalization, SBOM/vulnerability fixes. |
| 16. IT/DevOps | registry, signing/scanning, resources, rollout, base-image policy. |
| 17. დაიმახსოვრე | **Image არის დახურული პაკეტი; container — ამ პაკეტის ცოცხალი გაშვება.** |

## ბარათი 23 — Kubernetes, TLS/DNS/LB, observability და resilience

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | **IT-DEPENDENT**; repository-ში Kubernetes/Helm manifest და პროდუქტის ინფრასტრუქტურული ვერსიები არაა. |
| 2. რა არის | Kubernetes containers-ს მართავს; DNS სახელს აძლევს; TLS შიფრავს; LB ტრაფიკს ანაწილებს; monitoring/alerting მდგომარეობას აკვირდება. |
| 3. ზოგადი პრობლემა | უსაფრთხო მისამართი, scale, health routing, logs/metrics/alerts, failover. |
| 4. აქ გამოყენება | მხოლოდ target/assumption: on-prem cluster; Docker comments service name-ს ვარაუდობს. |
| 5. სად ჩანს | მოთხოვნების/IT კითხვების docs; deployment manifest კოდში არ ჩანს. |
| 6. მაგალითი | probe `/api/health`-ით unhealthy pod-ს ტრაფიკიდან ამოიღებდა — თუ IT ასე დააკონფიგურირებს. |
| 7. ურთიერთობა | Docker registry, Nginx, Oracle, secrets, SIEM. |
| 8. მის გარეშე | VM/systemd deployment შესაძლებელია, მაგრამ orchestration ხელით ხდება. |
| 9. ძალა | declarative rollout, self-healing, scaling. |
| 10. რისკი | რთული ოპერირება; app replicas shared state/rate-limit/jobs-ს ცვლის; cluster თავისთავად DB HA არაა. |
| 11. ალტერნატივა | VM/systemd, Nomad, managed cloud platform. |
| 12. რატომ ალტერნატივა | პატარა ops burden; სხვა company standard; managed service. |
| 13. არჩევანის შეფასება | საბოლოო არაა repository-ის მტკიცებულებით; IT-მ distro/registry/ingress/secrets/replicas დაადასტუროს. |
| 14. PO-მ იცოდეს | RPO=0/RTO=1სთ მიზანია, არა კოდის მიერ შესრულებული გარანტია. |
| 15. Development | statelessness, probes, graceful shutdown, structured logs, replica-safe jobs/cache. |
| 16. IT/DevOps/DBA | cluster, TLS/DNS/LB, secrets, logs/metrics/alerts/SIEM, HA/failover/DR drills. |
| 17. დაიმახსოვრე | **Production ინფრასტრუქტურა repository-ში არ არის დასრულებულად აღწერილი და IT-ის დადასტურებას საჭიროებს.** |

## ბარათი 24 — Maven, pom.xml და JAR

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Maven Wrapper; Spring parent `4.1.0`; JAR artifact. |
| 2. რა არის | Maven Java build/dependency manager; `pom.xml` მისი რეცეპტი; JAR assembled application. |
| 3. ზოგადი პრობლემა | dependencies, compile, tests და package ერთნაირად შესრულდეს. |
| 4. აქ გამოყენება | `./mvnw test/package`; dependency versions; executable backend JAR. |
| 5. სად ჩანს | `java-backend/pom.xml`, `.mvn/`, `mvnw*`, `target/`. |
| 6. მაგალითი | CI Oracle-tagged tests-ს ცალკე Maven command-ით უშვებს. |
| 7. ურთიერთობა | Java, Spring, JUnit, Docker build. |
| 8. მის გარეშე | compiler/JAR/dependencies ხელით ან Gradle-ით. |
| 9. ძალა | reproducible conventions, dependency graph, wrapper. |
| 10. რისკი | transitive conflicts/security CVE; dynamic repository availability. |
| 11. ალტერნატივა | Gradle, Bazel. |
| 12. რატომ ალტერნატივა | faster/custom build; huge monorepo/hermetic builds. |
| 13. არჩევანის შეფასება | standard Spring/enterprise არჩევანია. |
| 14. PO-მ იცოდეს | `pom.xml`-ის dependency update შეიძლება feature-ის გარეშე მაინც რისკიანი ცვლილება იყოს. |
| 15. Development | dependency hygiene, wrapper, reproducible tests, vulnerability updates. |
| 16. IT/სხვა | artifact repository/cache/scanning, build runners. |
| 17. დაიმახსოვრე | **Maven რეცეპტიდან ამოწმებს და აწყობს Java-ს გასაშვებ JAR-ს.** |

## ბარათი 25 — Node.js, npm, package files და Angular CLI

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Node `22.22.3` pinned; npm `11.17.0`; package-lock v3; Angular CLI `22.1.3`. |
| 2. რა არის | Node build runtime; npm dependency manager; package.json მოთხოვნა; lock ზუსტი dependency tree; CLI Angular build tool. |
| 3. ზოგადი პრობლემა | frontend-ის dependencies, tests, development server და production bundle. |
| 4. აქ გამოყენება | `npm ci`, `ng build`, `ng test`, Playwright. |
| 5. სად ჩანს | `.nvmrc`, `package.json`, `package-lock.json`, `angular.json`. |
| 6. მაგალითი | CI `npm ci`-ით lock-ის ზუსტ tree-ს აყენებს და production build-ს აწყობს. |
| 7. ურთიერთობა | Angular/TypeScript/Tailwind, Docker, GitHub Actions. |
| 8. მის გარეშე | browser build toolchain სხვა runtime/package manager-ს მოითხოვდა. |
| 9. ძალა | reproducible lock, დიდი ecosystem, standard CLI. |
| 10. რისკი | supply-chain/CVE, lock drift, Node version mismatch. |
| 11. ალტერნატივა | pnpm, Yarn, Bun; Vite/manual build. |
| 12. რატომ ალტერნატივა | disk speed/workspaces; სხვა team standard; faster runtime. |
| 13. არჩევანის შეფასება | Angular-ის სტანდარტული და pinned setup-ია. |
| 14. PO-მ იცოდეს | `^`/`~` version range განახლებას უშვებს, lock კი ამ build-ის ზუსტ ვერსიას აჩერებს. |
| 15. Development | lock commit, `npm ci`, dependency review, bundle budgets. |
| 16. IT/სხვა | registry/proxy/cache, vulnerability policy, CI Node image. |
| 17. დაიმახსოვრე | **package.json ამბობს რა გვინდა; package-lock — ზუსტად რა დაგვიყენდა.** |

## ბარათი 26 — Git, GitHub და GitHub Actions

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Git repository; GitHub; Actions workflow `ci.yml`. |
| 2. რა არის | Git ცვლილებების ისტორიაა; GitHub თანამშრომლობის პლატფორმა; Actions ავტომატური runner. |
| 3. ზოგადი პრობლემა | branch/commit/review, conflicts და repeatable CI. |
| 4. აქ გამოყენება | push/PR-ზე legacy lint/test, Java unit/Oracle integration, Angular build/Vitest, Playwright E2E. |
| 5. სად ჩანს | `.git/`, `.github/workflows/ci.yml`. |
| 6. მაგალითი | pull request-ისას Oracle XE service-ზე Flyway fresh-schema tests ეშვება. |
| 7. ურთიერთობა | Maven, npm, Playwright, Oracle. |
| 8. მის გარეშე | ცვლილებების კვალისა და ავტომატური quality gate-ის დაკარგვა. |
| 9. ძალა | traceability, parallel review, automated regression checks. |
| 10. რისკი | secret/large file commit; unsafe history rewrite; green CI არასრული test set-ისას ცრუ თავდაჯერება. |
| 11. ალტერნატივა | GitLab/CI, Azure DevOps, Jenkins. |
| 12. რატომ ალტერნატივა | company hosting/integration/compliance და existing runners. |
| 13. არჩევანის შეფასება | CI არსებობს; CD/image publish/deploy workflow არა — საბოლოო toolchain IT-მ უნდა დაადასტუროს. |
| 14. PO-მ იცოდეს | commit ადგილობრივი checkpoint-ია; push remote-ზე აგზავნის; merge branch-ებს აერთიანებს; rewrite საერთო ისტორიაზე სახიფათოა. |
| 15. Development | small PR, review, conflict resolution, green required checks. |
| 16. IT/DevOps/Security | branch protection, runners/secrets, artifact/deploy permissions, audit. |
| 17. დაიმახსოვრე | **Git ინახავს ცვლილების ისტორიას; Actions ამ ცვლილებას ავტომატურად ამოწმებს.** |

## ბარათი 27 — ტესტირების toolchain

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | JUnit `6.0.3`, Spring Test/MockMvc, Mockito `5.23`, Vitest `4.1.10`, Playwright `1.62.1`, k6 script; legacy pytest. |
| 2. რა არის | unit პატარა წესს; integration ნამდვილ კავშირებს; E2E browser flow-ს; load წარმადობას ამოწმებს. |
| 3. ზოგადი პრობლემა | regression-ის ადრე პოვნა და მოთხოვნის მტკიცებულება. |
| 4. აქ გამოყენება | 86 Java test files (~631 `@Test` source annotation), 20 Angular specs (~77 `it`), 19 E2E specs (~43 tests), 23 Oracle-required Java classes, k6 600-user ramp. |
| 5. სად ჩანს | Java `src/test`, Angular `*.spec.ts`, `e2e/`, `scripts/load/`, CI. |
| 6. მაგალითი | access contract test endpoint gate coverage-ს ამოწმებს; Oracle test migration/schema-ს. |
| 7. ურთიერთობა | Maven/npm/CI, Oracle XE, browser. |
| 8. მის გარეშე | ცვლილების დაზიანება production-მდე ხშირად შეუმჩნეველი დარჩებოდა. |
| 9. ძალა | მრავალშრიანი დაცვა: logic, DB, security contract, real UI. |
| 10. რისკი | source count ≠ passing count; flaky E2E; coverage threshold/report CI-ში არ ჩანს; k6 threshold `rate<1.0` ძალიან სუსტია და CI-ში არაა. |
| 11. ალტერნატივა | TestNG; Jest; Cypress/Selenium; Gatling/JMeter/Locust. |
| 12. რატომ ალტერნატივა | team standard; ecosystem; alternate browser model; JVM/GUI load tooling. |
| 13. არჩევანის შეფასება | ფართო და კარგი stack-ია; coverage/performance acceptance thresholds გასამკაცრებელია. |
| 14. PO-მ იცოდეს | green build ნიშნავს „განსაზღვრული checks გაიარა“, არა „შეცდომა შეუძლებელია“. |
| 15. Development | risk-based tests, fixtures/mocks, contract/security/regression, flaky-test ownership. |
| 16. IT/სხვა | production-like test env/data policy/load window; PO — acceptance scenarios. |
| 17. დაიმახსოვრე | **სხვადასხვა ტესტი სხვადასხვა კლასის შეცდომას იჭერს; ერთი სახეობა ყველას ვერ ცვლის.** |

## ბარათი 28 — Apache POI 5.3.0 და PDFBox 3.0.3

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Apache POI `5.3.0`; Apache PDFBox `3.0.3`. |
| 2. რა არის | Java ბიბლიოთეკები Excel-compatible XLSX და PDF დოკუმენტებისთვის. |
| 3. ზოგადი პრობლემა | ანგარიშების ფაილად export. |
| 4. აქ გამოყენება | compliance/reading export jobs; CSV ასევე ხელით გენერირდება. |
| 5. სად ჩანს | `pom.xml`, export services/workers. |
| 6. მაგალითი | async job Oracle BLOB-ში XLSX/PDF bytes-ს ინახავს და 1 საათში ასუფთავებს. |
| 7. ურთიერთობა | Java, Oracle BLOB, file download/auth. |
| 8. მის გარეშე | მხოლოდ JSON/CSV ან სხვა document service დაგვჭირდებოდა. |
| 9. ძალა | mature open-source, server-side generation. |
| 10. რისკი | memory დიდი exports-ისას; PII field allowlist; Georgian fonts/PDF rendering. |
| 11. ალტერნატივა | OpenPDF/iText, JasperReports, external report service. |
| 12. რატომ ალტერნატივა | richer PDF/layout/report templates ან centralized reporting. |
| 13. არჩევანის შეფასება | საკმარისია, მაგრამ მიმდინარე fields PO-13 target-ს ჯერ არ ემთხვევა. |
| 14. PO-მ იცოდეს | format-ზე უფრო მნიშვნელოვანია ვის რა columns და scope გაეცემა. |
| 15. Development | allowlist, authorization, memory/large-data tests, deletion/TTL. |
| 16. IT/DPO/Security | download/storage controls, data classification, retention, audit. |
| 17. დაიმახსოვრე | **POI/PDFBox მონაცემს ფაილად აქცევს; მონაცემის კანონიერ scope-ს თვითონ არ განსაზღვრავს.** |

## ბარათი 29 — LEGACY Python 3.11, FastAPI 0.137.2 და Pydantic 2.13.4

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Python 3.11 image; FastAPI `0.137.2`; Pydantic `2.13.4`. **LEGACY**. |
| 2. რა არის | Python ენაა; FastAPI API framework; Pydantic request/response validation/modeling. |
| 3. ზოგადი პრობლემა | სწრაფი web API development. |
| 4. აქ გამოყენება | root `main.py`, `routers/`, `schemas.py`, ძველი portal API. |
| 5. სად ჩანს | root Python files, `requirements*.txt`, root Dockerfile. |
| 6. მაგალითი | FastAPI router request-ს Pydantic schema-ით ამოწმებს. |
| 7. ურთიერთობა | SQLAlchemy, SQLite/PostgreSQL, Redis, Vanilla JS. |
| 8. მის გარეშე | ძველი სისტემა არ იმუშავებდა; ახალი Java stack-ზე გავლენა არ აქვს. |
| 9. ძალა | concise, სწრაფი prototype, readable schemas. |
| 10. რისკი | ორი stack-ის ერთდროული მოვლა/ქცევის აცდენა; ძველ docs-ში current-ადაა აღწერილი. |
| 11. ალტერნატივა | Java/Spring — უკვე არჩეული; Django, Node/NestJS. |
| 12. რატომ ალტერნატივა | enterprise target; batteries-included app; TypeScript stack. |
| 13. არჩევანის შეფასება | migration source/reference-ად რჩება; target — cutover-ის შემდეგ 30 დღე read-only, მერე გათიშვა. |
| 14. PO-მ იცოდეს | root Python endpoint-ის ნახვა არ ნიშნავს, რომ ახალი Java endpoint-იც იგივე ქცევას ასრულებს. |
| 15. Development | parity/gap inventory, safe cutover, eventual removal plan. |
| 16. IT/სხვა | routing/cutover/data archive/decommission; PO — read-only window. |
| 17. დაიმახსოვრე | **Python/FastAPI აქ ძველი სისტემაა, არა ახალი backend-ის ტექნოლოგია.** |

## ბარათი 30 — LEGACY SQLAlchemy, SQLite და PostgreSQL 15

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | SQLAlchemy `2.0.51`; SQLite files; PostgreSQL `15` compose. **LEGACY**. |
| 2. რა არის | Python ORM და ორი relational database engine. |
| 3. ზოგადი პრობლემა | ძველი მოდელების persistence; SQLite local/simple, PostgreSQL server production-like. |
| 4. აქ გამოყენება | root `database.py`, `models.py`, `.db` files, legacy compose. |
| 5. სად ჩანს | root Python/config/db files, `docker-compose.yml`. |
| 6. მაგალითი | SQLAlchemy model ძველი article table-ს აღწერს. |
| 7. ურთიერთობა | FastAPI/Pydantic, Alembic-like legacy migration scripts. |
| 8. მის გარეშე | legacy მონაცემთა ფენა არ იმუშავებდა; current Oracle დამოუკიდებელია. |
| 9. ძალა | SQLite zero-admin; PostgreSQL ძლიერი open-source; SQLAlchemy flexible. |
| 10. რისკი | SQLite concurrency; dual schema/data source; legacy backup script Oracle-ს არ იცავს. |
| 11. ალტერნატივა | Oracle/JPA — current; SQL Server/MySQL. |
| 12. რატომ ალტერნატივა | company standard/enterprise; Microsoft; commodity web. |
| 13. არჩევანის შეფასება | current source of truth უნდა გახდეს Oracle; reconciliation/cutover evidence საჭიროა. |
| 14. PO-მ იცოდეს | backup.py-ის არსებობა ახალი Oracle-ის backup გარანტია არ არის. |
| 15. Development | data mapping/reconciliation/read-only enforcement. |
| 16. IT/DBA | final data migration, archive, PostgreSQL/SQLite decommission, Oracle backup. |
| 17. დაიმახსოვრე | **SQLite/PostgreSQL ძველი საცავია; ახალი მიზნობრივი საცავი Oracle-ია.** |

## ბარათი 31 — LEGACY Redis 7, Uvicorn/Gunicorn, SSE და Vanilla JavaScript

| ველი | პასუხი |
|---|---|
| 1. სახელი/ვერსია | Redis 7 compose; Uvicorn `0.49`, Gunicorn `26`, Vanilla JS/HTML; legacy SSE. **LEGACY**. |
| 2. რა არის | Redis shared memory/pub-sub; Uvicorn/Gunicorn Python server/workers; Vanilla JS framework-ის გარეშე UI; SSE server-to-browser stream. |
| 3. ზოგადი პრობლემა | multi-worker serving, realtime notifications და მარტივი browser UI. |
| 4. აქ გამოყენება | old compose 4 workers, `/api/stream`, Redis pub/sub fallback, root `static/` HTML/JS. |
| 5. სად ჩანს | root compose/Dockerfile, Python routers/state, `static/`. |
| 6. მაგალითი | legacy event Redis channel-ით სხვა worker-ს აღწევს და SSE browser-ს ატყობინებს. |
| 7. ურთიერთობა | FastAPI, PostgreSQL, root Nginx/HTML. |
| 8. მის გარეშე | legacy multi-worker realtime ან in-memory-only გახდებოდა; current Java დღეს polling/durable messages-ს იყენებს. |
| 9. ძალა | Redis სწრაფი shared pub/sub; SSE მარტივი one-way push; Vanilla JS მცირე dependency. |
| 10. რისკი | pub/sub durability არა; multi-stack operations; დიდი Vanilla JS რთულად სამართავი. |
| 11. ალტერნატივა | Kafka/RabbitMQ, WebSocket, Angular; current Java polling. |
| 12. რატომ ალტერნატივა | durable queue; two-way realtime; structured UI; simpler current behavior. |
| 13. არჩევანის შეფასება | Java source-ში SSE endpoint არ არის; მხოლოდ visibility helper/test არსებობს. realtime target თუ საჭიროა, ცალკე გადაწყვეტილებაა. |
| 14. PO-მ იცოდეს | ძველი `/api/stream`-ის არსებობა არ ამტკიცებს, რომ ახალი portal realtime push-ს იყენებს. |
| 15. Development | legacy shutdown/parity; თუ realtime მოითხოვება — authorization/reconnect/multi-replica design. |
| 16. IT/სხვა | Redis/queue availability მხოლოდ არჩევისას; decommission old runtime. |
| 17. დაიმახსოვრე | **Redis/SSE/Gunicorn/Vanilla JS ძველი პლატფორმის ნაწილებია; ახალ Java/Angular stack-ში არ უნდა ავურიოთ.** |

# 4. ტექნიკური ცნებების პრაქტიკული გაშლა

## 4.1 Frontend-ის შიდა ენა

Angular-ის **component** არის ეკრანის პასუხისმგებლობის მქონე ნაწილი: TypeScript მართავს ქცევას, HTML სტრუქტურას, CSS/Tailwind გარეგნობას. **Service** frontend-ში API კავშირის ან გაზიარებული ლოგიკის ობიექტია. **Signal** Angular-ის reactive მდგომარეობაა: მნიშვნელობა რომ შეიცვლება, დამოკიდებული ეკრანი ახლდება. **Computed signal** სხვა signals-იდან გამოთვლილი მნიშვნელობაა. RxJS-ის **Observable** კი დროში მოსული მნიშვნელობების ნაკადია, განსაკუთრებით HTTP-სა და მოვლენებისთვის.

SPA-ში browser ერთხელ იღებს application shell-ს. Router URL-ის მიხედვით component-ს ცვლის, ხოლო მონაცემი API-დან მოდის. ეს server-rendered template არ არის. Legacy root HTML-იც რეალურად ძირითადად static HTML + Vanilla JavaScript-ია; repository-ში Jinja template rendering არ დასტურდება, მიუხედავად ძველი დოკუმენტაციის ზოგადი აღწერისა.

**Responsive design** ნიშნავს, რომ განლაგება სივრცეს ერგება. კოდში breakpoint-ები, mobile menu და Playwright mobile სცენარიც არის. მიუხედავად ამისა, მიმდინარე პროდუქტის მოთხოვნის მხარდაჭერილი სამიზნე არის desktop Chrome დაახლოებით 1080p-ზე; mobile/touch ოფიციალურ scope-ში არაა. კოდის უნარი და პროდუქტის მხარდაჭერის ვალდებულება ერთი რამ არ არის.

## 4.2 Backend-ის ფენები

ოფისის ანალოგიით: Controller არის მისაღები; Service — პასუხისმგებელი სპეციალისტი; Repository — არქივთან უფლებამოსილი კურიერი; Entity — არქივის ჩანაწერის Java ფორმა; DTO — გარეთ გასატანი, მხოლოდ საჭირო ველების მქონე ასლი.

ტექნიკურად:

| ფენა | რას აკეთებს | აქ სად ვნახოთ | რა არ უნდა გააკეთოს |
|---|---|---|---|
| Controller | HTTP path/method, user context, input/output | `java-backend/.../web/` | რთული ბიზნესლოგიკის დაგროვება |
| Request DTO | client-ის input-ის ფორმა და basic validation | `web` request records | database entity-ს ბრმად მიბაძვა |
| Service | permission/scope, business rule, transaction, orchestration | `service/` ქვესაქაღალდეები | UI-ს ვიზუალური წესების გადაწყვეტა |
| Repository | entity query/save | `repository/` | პროდუქტის გადაწყვეტილების თავისით მიღება |
| Domain/Entity | Oracle row/relations/version mapping | `domain/` | პირდაპირ browser-ში ყველა შიდა field-ის გატანა |
| Response DTO | API-ს კონტროლირებადი პასუხი | `web` response records | secret/internal field-ის შემთხვევით გაჟონვა |

**Dependency Injection (DI)** Spring-ს აძლევს უფლებას საჭირო Service/Repository შექმნას და constructor-ში მიაწოდოს. ამიტომ კლასები ტესტში mock-ით იცვლება და ერთმანეთზე ნაკლებად მაგრადაა მიწებებული.

**Validation** ორ საფეხურიანია: ფორმატის შემოწმება — მაგალითად, ცარიელი title; ბიზნესწესი — მაგალითად, აქვს თუ არა publisher-ს `articles.publish` permission და შესაბამისი scope. ორივე backend-მაც უნდა გააკეთოს.

**Global Exception Handling** შეცდომებს ერთნაირ HTTP პასუხებად აქცევს. ამ პროექტში body validation ხშირად 400-ია, optimistic conflict 409, მოულოდნელი შეცდომა 500 და მოკლე correlation id. სრული stack trace log-ში რჩება, client-ს არ გადაეცემა.

## 4.3 Database-ის ძირითადი გრამატიკა

ბიბლიოთეკის რეესტრის ანალოგიით:

- **ცხრილი (Table)** ერთი ტიპის ჩანაწერების წიგნია, მაგალითად `articles`.
- **მწკრივი (Row)** ერთი სტატია ან ერთი user.
- **სვეტი (Column)** ყველა row-სთვის განსაზღვრული ატრიბუტი, მაგალითად `title`.
- **Primary key** უნიკალური ID-ია — ჩანაწერის პირადი ნომერი.
- **Foreign key** სხვა ცხრილის ID-ზე კონტროლირებადი კავშირია — მაგალითად article-ის category.
- **Constraint** database-ის წესი: მნიშვნელობა აუცილებელია, უნიკალურია ან კავშირი არსებობს.
- **Index** დამატებითი საძიებო სტრუქტურაა. წაკითხვას აჩქარებს, მაგრამ ადგილს იკავებს და write-ს მცირე ხარჯს უმატებს.
- **Schema** ცხრილების, კავშირების, indexes და სხვა database ობიექტების ერთობლიობაა.
- **SQL** არის ენა, რომლითაც მონაცემს ვკითხულობთ და ვცვლით.

**Transaction** რამდენიმე database ნაბიჯს ერთ სამუშაო ერთეულად აერთიანებს. **Commit** ყველაფერს საბოლოოდ ამტკიცებს; **rollback** transaction-ის ჯერ დაუმტკიცებელ ცვლილებებს აბრუნებს. თუ transaction-ში სტატიის row ჩაიწერა, მაგრამ history insert ჩავარდა, rollback-ით ორივე ძველ მდგომარეობაში რჩება. თუმცა Angular-ის მიერ ცალკე გაკეთებული article და required-reading requests ერთ transaction-ში ვერ მოხვდება — ეს სწორედ სცენარი C-ის atomicity gap-ია.

**Atomicity** ნიშნავს „ყველაფერი ან არაფერი“. **Consistency** — constraints/წესების დაცვას. **Isolation** — concurrent transaction-ების ერთმანეთისგან დაცვას. **Durability** — commit-ის შემდეგ მონაცემის დაკარგვისგან დაცვას. ეს ოთხი თვისება ხშირად ACID აბრევიატურით არის ცნობილი.

## 4.4 Environment, build და configuration

| გარემო | მიზანი | repository-ში მდგომარეობა |
|---|---|---|
| Development | სწრაფი ადგილობრივი მუშაობა | `docker-compose.local.yml`, Oracle XE 21c, explicit dev login; მხოლოდ local-use |
| Test/CI | ავტომატური, თავიდან შექმნილი შემოწმება | GitHub Actions, Oracle XE service, unit/integration/E2E |
| Staging | production-ის მსგავსი მიღების გარემო | **IT-DEPENDENT** — config/deploy workflow repository-ში არ ჩანს |
| Production | რეალური თანამშრომლები და დაცული მონაცემი | Docker-safe defaults არსებობს; deployment/topology/secrets/HA **IT-DEPENDENT** |

**Configuration** არის გარემოსთვის ცვალებადი ჩვეულებრივი პარამეტრი: API URL, pool size, log level. **Secret** არის კონფიდენციალური მნიშვნელობა: Oracle password, JWT signing key, IdP client secret. Secret არ უნდა მოხვდეს Git-ში, image-ში, log-ში ან screenshot-ში; production-ში secret manager/Kubernetes Secret-ის რეალური არჩევანი IT-მ უნდა დაადასტუროს.

**Build** source code-ს შესამოწმებელ/გასაშვებ არტეფაქტად გარდაქმნის. Angular production build TypeScript/templates/Tailwind-ს optimized hashed static files-ად აწყობს და bundle budget-ს ამოწმებს. Maven Java-ს compile/test/package-ით executable JAR-ს ქმნის. Docker შემდეგ ამ artifacts-ს images-ში ფუთავს.

**Semantic Versioning (SemVer)** ჩვეულებრივ `MAJOR.MINOR.PATCH` ფორმაა: MAJOR შესაძლოა compatibility-ს არღვევდეს; MINOR backward-compatible ფუნქციას ამატებდეს; PATCH bug/security fix იყოს. `^22.1.0` დამოკიდებულებას თავსებადი minor/patch მიმართულებით უშვებს, `~6.0.2` უფრო ვიწროდ patch ხაზს, ხოლო lock file ზუსტ resolved ვერსიას ინახავს. ყველა პროექტი SemVer-ს იდეალურად არ იცავს, ამიტომ upgrade ყოველთვის test-ს მოითხოვს.

## 4.5 Logging, monitoring, alerting და SIEM

- **Logging** — მოვლენის ტექსტური ჩანაწერი. Java SLF4J-ით console-ზე წერს; error-ს correlation id უკავშირდება.
- **Monitoring** — metrics/health-ის უწყვეტი დაკვირვება: latency, error rate, JVM memory, pool usage, DB health.
- **Alerting** — ზღურბლის დარღვევისას პასუხისმგებელი ადამიანის შეტყობინება.
- **SIEM** (Security Information and Event Management) — security logs/events-ის ცენტრალური ანალიზი და კორელაცია.

Repository-ში custom health და Actuator dependency ჩანს, მაგრამ log collector, Prometheus/Grafana, alert routes და SIEM forwarding არ ჩანს. ეს ფუნქციები არ შეიძლება „არსებულად“ ჩაითვალოს — **IT-DEPENDENT** არის.

## 4.6 Backup, archive, retention და disaster recovery

| ცნება | მარტივი მნიშვნელობა | ამ პროექტის მაგალითი/სტატუსი |
|---|---|---|
| Backup | აღსადგენი ტექნიკური ასლი ავარიისთვის | Oracle backup მეთოდი repository-ში არაა; legacy `backup.py` ახალ სისტემას არ ფარავს |
| Archive | იშვიათად საჭირო, გრძელვადიანი დაცული მონაცემის გადატანა | audit-ის მრავალწლიანი protected archive **TARGET + IT/DPO-DEPENDENT** |
| Retention | რამდენ ხანს ვინახავთ და როდის ვშლით | target: active audit 1 წელი; exports 1სთ უკვე არის; სხვა ვადები დასანერგია |
| Legal hold | წესით წასაშლელი მონაცემის დროებითი, სამართლებრივი შენარჩუნება | policy/implementation დასადასტურებელია DPO/Legal-თან |
| Disaster Recovery | დიდი ავარიის შემდეგ სერვისისა და მონაცემის აღდგენის პროცესი | runbook/secondary site/drill repository-ში არ ჩანს |
| RPO | დასაშვები მონაცემის დაკარგვის დროითი მოცულობა | PO target `0`; IT/DBA-მ ტექნიკურად უნდა დაასაბუთოს |
| RTO | რამდენ ხანში უნდა აღდგეს სერვისი | PO target `1 საათი`; IT/DBA-მ უნდა დაადასტუროს |
| HA | High Availability — ერთ failure-ზე სერვისის გაგრძელება | application/Oracle topology უცნობია |
| Failover | primary-ის ჩავარდნისას secondary-ზე გადასვლა | პროცესი და ტესტი IT-DEPENDENT |

**რა უნდა დაიმახსოვროს Product Owner-მა:** backup retention არ არის, archive backup არ არის, ხოლო „გვაქვს backup“ არ ამტკიცებს, რომ restore დროულად და სრულად მუშაობს. მტკიცებულება არის წარმატებული აღდგენის ტესტი.

---

# 5. ტექნოლოგიური ალტერნატივების რუკა

არჩევანი არასოდეს არის აბსოლუტურად „საუკეთესო“. ის დამოკიდებულია მოთხოვნაზე, გუნდის უნარზე, კომპანიის სტანდარტზე, ლიცენზიაზე, უსაფრთხოებაზე და ოპერირების ფასზე.

## 5.1 Frontend framework

| არჩევანი | რას აკეთებს | მთავარი პლუსი | მთავარი მინუსი | კარგი შემთხვევა | ჩვენი სტატუსი |
|---|---|---|---|---|---|
| Angular | სრული UI framework | ერთიანი მკაფიო წესები | სწავლის მრუდი/upgrade | დიდი admin/business SPA | **არჩეული CURRENT** |
| React | UI library/ecosystem | მოქნილი, დიდი ბაზარი | ბევრი არქიტექტურული არჩევანი | მრავალფეროვანი web products | არაა არჩეული |
| Vue | progressive framework | მარტივი შესვლა | enterprise market შედარებით მცირე | მცირე/საშუალო SPA | არაა არჩეული |
| Server-rendered HTML | server ყოველ გვერდს აწყობს | მარტივი პატარა app/SEO | ნაკლები rich interaction | content/public site | current target არა; legacy-ც რეალურად static JS-heavy იყო |

## 5.2 Backend platform

| არჩევანი | რას აკეთებს | პლუსი | მინუსი | კარგი შემთხვევა | ჩვენი სტატუსი |
|---|---|---|---|---|---|
| Java/Spring Boot | enterprise API/backend | mature Oracle/security/testing | მეტი რესურსი/ცოდნა | გრძელვადიანი on-prem business system | **არჩეული CURRENT** |
| C#/.NET | Microsoft enterprise backend | ძლიერი tooling/AD ecosystem | სხვა runtime/team skill | Microsoft-centric კომპანია | შესაძლო ალტერნატივა |
| Node.js/NestJS | TypeScript backend | ერთიანი frontend/backend ენა | CPU-heavy work/conventions | TS გუნდი, I/O APIs | შესაძლო ალტერნატივა |
| Python/FastAPI | სწრაფი typed API | სწრაფი development | დიდი enterprise governance ცალკე სჭირდება | prototype/data services | **LEGACY აქ** |

## 5.3 Database

| არჩევანი | რას აკეთებს | პლუსი | მინუსი | კარგი შემთხვევა | ჩვენი სტატუსი |
|---|---|---|---|---|---|
| Oracle | enterprise relational DB | company tooling, transactions, HA options | ლიცენზია/DBA სირთულე | regulated enterprise | **CURRENT target; version/topology IT-DEPENDENT** |
| PostgreSQL | open-source relational DB | შესაძლებლობები/ფასი | company support შეიძლება არ არსებობდეს | ფართო workload | **LEGACY compose** |
| SQL Server | Microsoft DB | AD/.NET integration | ლიცენზია/ecosystem | Microsoft shop | არაა არჩეული |
| MySQL | პოპულარული relational DB | მარტივი/ფართო hosting | ნაკლები Oracle-specific enterprise fit | commodity web | არაა არჩეული |

## 5.4 Authentication და federation

აქ ტერმინები ერთმანეთის პირდაპირი კონკურენტები ყოველთვის არ არის. **IdP** identity provider სისტემაა; LDAP directory protocol; SAML/OIDC federation protocols; OAuth 2.0 authorization framework.

| არჩევანი | რას აკეთებს | პლუსი | მინუსი | კარგი შემთხვევა | ჩვენი სტატუსი |
|---|---|---|---|---|---|
| კომპანიის IdP/AD | ცენტრალური identity/lifecycle | SSO, deactivation, MFA/policy | IT integration dependency | თანამშრომლების შიდა portal | **TARGET, კონკრეტიკა IT-DEPENDENT** |
| LDAP/LDAPS | directory query/bind | პირდაპირი AD კავშირი | app credential/network coupling; federation არა | legacy enterprise auth | IT პასუხი საჭიროა |
| SAML 2.0 | browser SSO assertions | ფართოდ დანერგილი enterprise | XML/flow complexity | არსებული corporate SSO | IT პასუხი საჭიროა |
| OpenID Connect | identity layer OAuth2-ზე | თანამედროვე token/metadata | IdP/client config | web/mobile SSO | IT პასუხი საჭიროა |
| OAuth 2.0 | delegated authorization | standard scopes/tokens | მარტო user authentication არაა | API authorization | OIDC-თან შესაძლოა |
| Local passwords | app თვითონ ამოწმებს | ინფრასტრუქტურულად მარტივი | lifecycle/MFA/support/security ტვირთი | isolated/small app | **CURRENT დროებითი; target არა** |

## 5.5 File storage

| არჩევანი | რას აკეთებს | პლუსი | მინუსი | კარგი შემთხვევა | ჩვენი სტატუსი |
|---|---|---|---|---|---|
| Oracle BLOB | ფაილი transaction-თან ერთად DB-ში | ერთი backup/access model | tablespace/backup ზრდა | ზომიერი მოცულობა, ძლიერი consistency | **CURRENT** |
| S3/MinIO | object storage | scale, lifecycle, object semantics | ცალკე service/consistency/security | ბევრი/დიდი ფაილი | **IT განხილვა** |
| Shared filesystem | მრავალი replica ერთი mount-ით | ნაცნობი file semantics | permissions/HA/locking | existing NAS/RWX | **IT განხილვა** |
| Kubernetes PV | pod-ის persistent volume | cluster integration | backend/storage class/HA complexity | stateful cluster workload | **IT განხილვა** |

გადაწყვეტილებამდე საჭიროა BLOB მოცულობა, ზრდა, backup window, malware scan, authenticated delivery, classification და multi-replica მოთხოვნა.

## 5.6 Deployment

| არჩევანი | პლუსი | მინუსი | კარგი შემთხვევა | ჩვენი სტატუსი |
|---|---|---|---|---|
| Docker + Kubernetes | rollout/self-healing/scale | ops complexity | არსებული cluster/platform team | Docker CURRENT; K8s **IT-DEPENDENT** |
| VM + systemd | მარტივი/ნაცნობი | manual HA/rollout | მცირე სტაბილური გარემო | ალტერნატივა |
| Managed cloud platform | ნაკლები ops | vendor/data residency/cost | cloud-ნებადართული პროდუქტი | ამჟამად target არა |

## 5.7 Testing tools

| სფერო | არჩეული | ალტერნატივები | როდის ალტერნატივა | გადაწყვეტილება |
|---|---|---|---|---|
| Angular unit | Vitest | Jest, Karma/Jasmine | team ecosystem/legacy Angular | **CURRENT** |
| Browser E2E | Playwright | Cypress, Selenium | interactive runner ან ფართო legacy browser grid | **CURRENT** |
| Java tests | JUnit + Spring Test/MockMvc + Mockito | TestNG, Spock | სხვა team standard/Groovy specification style | **CURRENT** |
| Load | k6 script | Gatling, JMeter, Locust | JVM DSL, GUI/enterprise harness, Python legacy | script არსებობს; acceptance/CI ჯერ არასრული |

---

# 6. არქიტექტურული ცნებები, რომლებიც Product Owner-ს უნდა ესმოდეს

| ცნება | მარტივი განმარტება | ამ პროექტის ზუსტი მაგალითი |
|---|---|---|
| Frontend / Backend | frontend ეკრანია; backend ავტორიტეტული წესები/მონაცემთა წვდომა | Angular button შეიძლება დამალოს; Java controller მაინც ამოწმებს permission-ს |
| Client–server | client ითხოვს, server პასუხობს | browser Angular → Java API |
| Request / Response | მოთხოვნა და მისი შედეგი | `GET /api/articles/42` → article JSON ან 403/404 |
| Synchronous | caller პასუხს ელოდება | ჩვეულებრივი Spring MVC request და Oracle query |
| Asynchronous | სამუშაო/შედეგი სხვა დროს სრულდება | export job ფონში ქმნის BLOB-ს; UI status-ს კითხულობს |
| Monolith | ერთი deployable application | მთელი Java backend ერთი JAR/process-ია, მიუხედავად package modules-ისა |
| Microservices | დამოუკიდებლად deployable პატარა services | repository-ში მიმდინარე architecture **არ არის** |
| Stateless | request-ს identity/context თან მოაქვს; server session memory აუცილებელი არაა | JWT request-ზე მოდის, მაგრამ DB-სა და job/cache-ს state მაინც აქვს |
| Stateful | მდგომარეობა request-ებს შორის ინახება | Oracle, browser storage, export job rows |
| Cache | პასუხის დროებითი ასლი | search-ის 60-წუთიანი არა, **60-წამიანი** in-memory cache/single-flight თითო JVM-ზე |
| Source of truth | საბოლოო ავტორიტეტული წყარო | schema-სთვის Flyway; user active/token_version-სთვის Oracle; product rule-სთვის დამტკიცებული requirements |
| Legacy | ძველი მოქმედი/დარჩენილი სისტემა | root FastAPI/SQLite/PostgreSQL/Redis/static JS |
| Migration | სტრუქტურის ან სისტემის კონტროლირებული გადასვლა | Flyway V36; Python→Java/Oracle/Angular cutover |
| Backfill | უკვე არსებულ rows-ში ახალი წესის მონაცემის შევსება | V36.1 legacy permissions-იდან overrides-ის შექმნა |
| Seed / Fixture | საწყისი ან test მონაცემის წინასწარ შექმნა | local seed service და tests fixtures |
| Backfill vs seed | backfill რეალურ ძველ მონაცემს გარდაქმნის; seed ცარიელ/test გარემოს ავსებს | V36.1 ≠ local seed container |
| API contract | path/method/input/output/error/access შეთანხმება | Article Response DTO და Angular model/service |
| Backward compatibility | ახალი version ძველ client/data-ს არ არღვევს | additive response field ჩვეულებრივ რბილია; field rename breaking შეიძლება იყოს |
| Transaction | რამდენიმე DB ოპერაცია ერთ ერთეულად | article + history save ერთ service transaction-ში |
| Atomicity | ყველაფერი ან არაფერი | article transaction შიგნით; მაგრამ article→required-reading ორი request და არა atomic |
| Concurrency | რამდენიმე მოქმედება ერთდროულად | ორი admin ერთ article/user-ს ცვლის |
| Optimistic locking | version-ით ძველი update-ის უარყოფა | Article/User `@Version` → HTTP 409 |
| CAS | Compare-And-Set — შეცვალე მხოლოდ თუ version ძველ მნიშვნელობას ემთხვევა | permission/user update concurrency test |
| Race condition | შედეგი მოქმედებების დროით თანმიმდევრობაზე ხდება დამოკიდებული | ცალკე publish და assignment requests; parallel updates version-ის გარეშე |
| Idempotency | იგივე ბრძანების გამეორება შედეგს აღარ აორმაგებს | migration ერთხელ history-ით; background retry-სთვის explicit design საჭიროა |
| Audit | ვინ/რა/როდის გააკეთა | `audit_logs`, history tables; coverage ყველა ბიზნესქმედებაზე ჯერ ერთნაირი არაა |
| Tamper evidence | ცვლილება შესამჩნევი გახდეს | V28 SHA-256 chain; prevention/WORM არა |
| Retention | შენახვის/წაშლის დროითი წესი | export 1 საათი; audit target/legal policy დარჩენილია |
| Archive | გრძელვადიანი, იშვიათად გამოყენებული შენახვა | target multi-year protected audit archive |
| Backup | აღდგენის ასლი | Oracle backup IT/DBA-სგან დასადასტურებელია |
| Disaster recovery | კატასტროფის შემდეგ აღდგენის გეგმა და ვარჯიში | RPO/RTO target არსებობს, runbook/drill არაა repo-ში |
| Security boundary | ადგილი, სადაც ნდობის დონე იცვლება | browser→backend; backend→Oracle; upload→stored file |
| Least privilege | მხოლოდ აუცილებელი მინიმალური უფლება | per-user DENY/ALLOW; SYSTEM_ADMIN განსაკუთრებული რისკი |
| Fail-safe default | გაურკვევლობისას უარყოფა | desired deny-by-default; მაგრამ SecurityConfig `anyRequest().permitAll()` manual gates-ზეა დამოკიდებული |
| Technical debt | დროებითი არჩევანის მომავალი ფასი | local login, dual token storage, legacy scope, mixed docs |
| Refactoring | ქცევის უცვლელად შიდა სტრუქტურის გაუმჯობესება | controller logic-ის service-ში გატანა; regression tests საჭიროა |
| Regression | ადრე მომუშავე ქცევის გაფუჭება | role change-ისას სხვა endpoint-ის უნებლიე გახსნა |
| Feature flag / rollout switch | ქცევის ეტაპობრივი ჩართვის გადამრთველი | scope rollout properties არსებობს, მაგრამ call sites ჯერ მათ არ იყენებს; ამიტომ „flag გვაქვს“ ≠ cutover მზადაა |
| Shadow comparison | ახალი წესის შედეგის ჩუმად შედარება, enforcement-ის გარეშე | `ScopeResolver`/eligibility ახალი პასუხი ითვლება, მაგრამ legacy answer ბრუნდება |

### განსაკუთრებით მნიშვნელოვანი: auth, permission და scope

```text
Authentication: ნიკა ნამდვილად ნიკაა?
Authorization / permission: ნიკას შეუძლია export?
Scope: თუ შეუძლია, რომელი თანამშრომლების ჩანაწერებზე?
```

სამივე backend-ის query/result-მდე უნდა მოქმედებდეს. Angular guard მხოლოდ მომხმარებლისთვის სწორი მენიუს ჩვენებას უზრუნველყოფს.

---

# 7. პასუხისმგებლობების საზღვარი

**R = ასრულებს; A = საბოლოო ბიზნეს/პროფესიული გადაწყვეტილების პატრონი; C = სავალდებულო კონსულტაცია; I = ინფორმირებული.** ერთ თემას შეიძლება რამდენიმე პროფესიული დამტკიცება სჭირდებოდეს, მაგრამ მათი საგანი განსხვავდება.

| თემა | Product Owner | Development / AI agent | IT / DevOps / DBA | Security / DPO / Legal |
|---|---|---|---|---|
| პროდუქტის ქცევა/acceptance | **A** | R/C — ტექნიკური რეალიზაცია | I/C თუ ინფრაზე მოქმედებს | C თუ რისკი/რეგულაციაა |
| ვინ ხედავს სახელობით წაკითხვას | **A** ბიზნესსაჭიროებაზე | R — permission/scope | C — org data | **A/C** კანონიერ საფუძველზე |
| Angular component-ის შიდა სტრუქტურა | I | **A/R** | I | C accessibility/security საჭიროებისას |
| REST contract/transaction | C — semantics | **A/R** | C limits/gateway | C sensitive fields |
| კომპანიის IdP/AD კავშირი | A — UX/business identity rule | R — adapter/gates | **A/R** protocol/endpoints/groups | C — auth/session policy |
| Oracle schema/Flyway | C — data meaning | R — migration | **A/R** production execution/DB standards | C — classification/retention |
| Oracle backup მეთოდი | I — business recovery need | C restore compatibility | **A/R** | C evidence/retention |
| RPO/RTO | **A** მიზანი/impact | C app recovery | **A/R** feasibility/design/evidence | C regulatory minimum |
| Audit event-ის business scope | **A** | R | C storage/operations | **A/C** legal/security adequacy |
| Audit retention/legal hold | C business use | R technical support | R archive/storage | **A** სავალდებულო ვადა/policy |
| File storage ტექნოლოგია | C business needs | C/R app adapter | **A/R** capacity/backup/platform | C classification/scanning |
| Secrets/TLS/DNS/K8s | I/C availability target | R app compatibility | **A/R** | C policy/review |
| Test scenarios | **A** acceptance | **A/R** automation/quality | C environments/load | C security tests |
| Production deploy/rollback | A business approval წესზე | R artifact/runbook | **A/R** execution/platform | C high-risk approval |
| Incident business communication | **A/R** impact/priorities | R diagnose/fix | R contain/restore | R/C breach/legal handling |

პრინციპი: PO არ ირჩევს თვითნებურად Oracle backup engine-ს, მაგრამ წყვეტს რა ბიზნესზარალია მისაღები და ითხოვს RPO/RTO მტკიცებულებას. IT არ წყვეტს ვის უნდა ჰქონდეს სახელობითი მონაცემის ნახვა, თუმცა ადასტურებს შეძლებს თუ არა არჩეული კონტროლის უსაფრთხოდ გაშვებას. Security/DPO/Legal არ აგებს Angular component-ს, მაგრამ ადგენს აუცილებელ შეზღუდვებს.

---

# 8. უსაფრთხოების მარტივი სახელმძღვანელო

## 8.1 რატომ არ არის დამალული ღილაკი უსაფრთხოება

მომხმარებელს browser developer tools-ით შეუძლია პირდაპირ API request გაგზავნოს. ამიტომ Angular guard/menu მხოლოდ UX-ია. Java backend-ის თითოეულ ბიზნეს endpoint-ზე permission და scope უნდა შემოწმდეს. მიმდინარე კოდში `SecurityConfig` ბოლოს `permitAll()`-ს იყენებს და controller-ები ხელით აკეთებენ `requireAuthenticated`/permission checks-ს; contract coverage test ამ fragile საზღვარს ამოწმებს. კომენტარი „business endpoints ჯერ არ არის“ მოძველებულია — source-ში 120 mapping annotation ჩანს.

## 8.2 Password, token და secret

- პაროლი plaintext-ად არასოდეს ინახება. მიმდინარე local login BCrypt hash-ს ადარებს; hash-იდან ორიგინალი პაროლის „გაშიფვრა“ არ ხდება.
- signing key და Oracle password secret-ებია. `ProductionSafetyGuard` production-ში ძლიერ JWT secret-ს, ნამდვილ DB password-ს, secure cookie-ს და dev login-ის გათიშვას ითხოვს.
- JWT-ის signature მის შეცვლას ამოსაცნობს ხდის; token-ის მოპარვას არ უშლის. HTTPS გზაში იცავს, HttpOnly cookie JavaScript წაკითხვისგან, ხოლო სწრაფი expiry/revoke ზიანის ფანჯარას ამცირებს.

## 8.3 მთავარი web რისკები

| რისკი | მარტივი ახსნა | აქ არსებული დაცვა | დარჩენილი საკითხი |
|---|---|---|---|
| XSS | მავნე script სხვის browser-ში ეშვება | Angular sanitizer; editor paste-ზე DOMPurify; `nosniff` files | localStorage-ში სრული JWT XSS-სთვის ღირებული სამიზნეა; CSP/topology review საჭიროა |
| SQL injection | input SQL ბრძანების ნაწილად იქცევა | JPA/repository parameter binding და validation | custom/native SQL ყოველთვის parameterized უნდა დარჩეს |
| CSRF | სხვა site user-ის browser-ს cookie-auth request-ს გააკეთებინებს | SameSite=Lax/same-origin ამცირებს რისკს | CSRF disabled და cookie token accepted; IdP/topology ცვლილებისას formal review აუცილებელია |
| File upload | malware, disguised MIME, XSS file, storage exhaustion | 10MB limit, MIME allowlist/magic bytes, `nosniff`, BLOB | D-4 authenticated access ჯერ არა; virus scanning/classification/ingress IT/Security-ზეა |
| Broken access control | user სხვის data-ს ხედავს | controller capability/scope checks და tests | leadership scope cutover, named evidence, stats/file gates pending |
| Secret leak | key/password Git/log-ში ხვდება | env configuration + production guard | central secret store/rotation/audit IT-DEPENDENT |

**Authenticated file access** ნიშნავს, რომ file URL-ს მიღება საკმარისი არ არის: backend ყოველი download-ისას ამოწმებს ვინ ითხოვს და აქვს თუ არა ამ კონკრეტული ფაილის/კონტენტის უფლება. მიმდინარე `UploadedFileController` საერთო `permitAll` მოდელში საჯაროდ რჩება — target-ის შესრულებად არ ჩაითვალოს.

## 8.4 Audit, deactivation და privileged role

Audit log გამოძიებასა და ანგარიშვალდებულებას ემსახურება. V28 chain tampering-ის აღმოჩენას აძლევს საშუალებას, მაგრამ უფლებამოსილ DBA-ს ჩანაწერის შეცვლას ფიზიკურად არ უკრძალავს. აპლიკაციური audit coverage-ც სრული არაა: ზოგი article ცვლილება history-შია, ზოგი action audit log-ში, მაგრამ ყველა საჭირო business event ერთნაირად არა.

დეაქტივირებულ თანამშრომელს active JWT-ით მუშაობა აღარ უნდა შეეძლოს. Java filter user-ს Oracle-დან ყოველ request-ზე ხელახლა ამოწმებს; `token_version` logout/revoke-ს უზრუნველყოფს. კომპანიის AD change feed-ის 5–15 წუთიანი მიზანი ჯერ IT integration-ს მოითხოვს.

`SYSTEM_ADMIN` capability checks-ს გვერდს უვლის. ეს განზრახ „სუპერ გასაღებია“, ამიტომ საჭიროა უმცირესი რაოდენობა, ძლიერი identity/MFA, ცალკე audit, პერიოდული access review და ჩვეულებრივი ყოველდღიური ანგარიშისგან განცალკევება.

**რა უნდა დაიმახსოვროს Product Owner-მა:** security მხოლოდ login გვერდი არაა. ის მოიცავს backend gate-ს, data scope-ს, secure transport-ს, secrets-ს, audit-ს, retention-ს, monitoring-სა და incident response-ს.

---

# 9. სისტემის სრული სიცოცხლის ციკლი

## A. Product Owner ითხოვს ახალ ფუნქციას

1. აღწერს მომხმარებლის პრობლემას, როლს, scope-ს, happy/error flow-სა და acceptance criteria-ს.
2. ასახელებს რა მონაცემი ინახება, ვინ ხედავს, audit/retention/export საჭიროა თუ არა.
3. Development აკეთებს impact analysis-ს: Angular, API, Oracle migration, security, tests, IT/DPO dependency.
4. გაურკვეველი business წესი PO-ს უბრუნდება; ინფრასტრუქტურული ფაქტი — IT-ს; სავალდებულო ვადა — DPO/Legal-ს.

## B. იცვლება Angular

Developer ცვლის component/template/service/model/route-ს, ამატებს unit test-ს და საჭიროებისას Playwright სცენარს. `npm ci`, `ng test`, production build და E2E ამოწმებს. API contract-ის შეცვლა backend-თან კოორდინირდება. UI permission backend permission-ს ვერ ანაცვლებს.

## C. იცვლება Java backend

Developer ცვლის controller/request/response/service/repository/entity-ს შესაბამის ფენაში, ამოწმებს authentication/permission/scope/transaction/audit/error-ს. JUnit/Mockito unit, Spring MockMvc/security contract და საჭიროებისას Oracle integration test ემატება.

## D. იცვლება Oracle schema

1. ძველი migration არ რედაქტირდება, თუ უკვე გაზიარებულ გარემოში გაეშვა.
2. იქმნება შემდეგი Flyway versioned SQL.
3. განისაზღვრება backward compatibility, lock/data-volume და backfill.
4. CI fresh Oracle-ზე migration-სა და Java integration-ს ამოწმებს.
5. production-მდე DBA ადგენს privilege/window/backup/monitoring/forward-fix ან restore გეგმას.

## E. ტესტები ეშვება

ადგილობრივად/CI-ში სწრაფი unit tests პირველად; შემდეგ Angular production build/Vitest; Java Oracle integration; ბოლოს სრული Playwright browser flow. Performance k6 script ცალკეა და CI quality gate არ არის. Test fixture გამოგონილი კონტროლირებადი მონაცემია; mock ნამდვილი dependency-ის იმიტაცია; coverage მიუთითებს რომელი კოდი გაეშვა tests-ში, მაგრამ correctness-ს თავისით არ ამტკიცებს. CI-ში explicit coverage report/threshold კონფიგურაცია არ ჩანს.

## F. commit და push

Developer ქმნის მიზნობრივ branch-ს, ამოწმებს diff-სა და secrets-ს, აკეთებს მცირე შინაარსობრივ commit-ს, შემდეგ push-ს. Fetch remote ცნობებს ჩამოტვირთავს; pull ჩვეულებრივ fetch+integrate-ია. Merge histories-ს აერთიანებს; conflict ადამიანმა მნიშვნელობით უნდა გადაწყვიტოს. გაზიარებული history-ის rewrite (`rebase --force`, reset და force-push) წინასწარი წესის გარეშე არ შეიძლება.

## G. GitHub Actions

ყოველ push/PR-ზე ოთხი მიმართულება მოწმდება: legacy Python; Java unit; Angular build/unit; Oracle integration; დამატებით სრული Playwright E2E stack. Green build ნიშნავს, რომ workflow-ში განსაზღვრული command-ები წარმატებით დასრულდა. იგი არ მოიცავს CD-ს, production migration-ს, security approval-ს ან load SLO-ს.

## H. Docker image იქმნება

Dockerfile-ები multi-stage build-ით Angular/Nginx და Java/JRE images-ს ქმნიან. მაგრამ მიმდინარე GitHub Actions ამ images-ს არც აშენებს, არც registry-ში push-ს აკეთებს. image build/sign/scan/publish პროცესი **IT-DEPENDENT** დარჩენილია.

## I. production-ში გადასვლა

სასურველი პროცესი: approved PR → immutable versioned image → registry → staging smoke/UAT → change approval → DB backup/readiness → Flyway → rolling/canary app rollout → health/smoke/metrics/audit verification. repository-ში ამ CD/Kubernetes ნაბიჯების რეალური workflow/manifests არ არის. IT-მ უნდა განსაზღვროს ვინ, სად და როგორ აკეთებს.

## J. პრობლემა და rollback/აღდგენა

აპის image rollback შესაძლებელია წინა immutable image-ზე, თუ API/schema backward-compatibleა. უკვე შესრულებული database migration უბრალო image rollback-ით არ ქრება; საჭიროა forward-fix ან წინასწარ დამტკიცებული restore. მონაცემის დაზიანებისას Oracle backup/failover/point-in-time recovery DBA-ს პროცესია. Incident-ზე უნდა ვიცოდეთ impact, last good version, migration version, logs/correlation id, restore შედეგი და მომხმარებელთან კომუნიკაცია.

**რა უნდა დაიმახსოვროს Product Owner-მა:** deploy-ის ბოლო ნაწილი repository-ის feature კოდი აღარ არის — ეს Development და IT-ის შეთანხმებული, მტკიცებულებებზე დაფუძნებული ოპერაციული პროცედურაა.

---

# 10. როგორ წავიკითხო ეს repository

## 10.1 რუკა

```text
Magti base/
├─ angular-frontend/              ახალი CURRENT browser SPA
│  ├─ src/app/core/               auth, services, models, theme, HTTP
│  ├─ src/app/features/           გვერდები და პროდუქტის flows
│  ├─ src/app/shared/             reusable UI/helpers
│  ├─ src/app/shell/              საერთო layout/navigation
│  ├─ public/i18n/                ka/en ტექსტები
│  └─ e2e/                        Playwright tests
├─ java-backend/                  ახალი CURRENT Java API
│  ├─ src/main/java/.../web/      controllers + request/response
│  ├─ src/main/java/.../service/  ბიზნესლოგიკა/security/search/jobs
│  ├─ src/main/java/.../domain/   JPA entities
│  ├─ src/main/java/.../repository/ repositories
│  ├─ src/main/resources/db/migration/ Oracle/Flyway SQL
│  └─ src/test/                   Java unit/integration/contract tests
├─ .github/workflows/ci.yml       CI checks; CD არა
├─ docker-compose.local.yml       ახალი stack-ის local environment
├─ docs/                          requirements, decisions, IT questions
├─ main.py, routers/, models.py   LEGACY FastAPI backend
├─ static/                        LEGACY HTML/CSS/Vanilla JS UI
├─ Dockerfile, docker-compose.yml LEGACY runtime/compose
└─ scripts/load/                  k6 load test
```

## 10.2 მნიშვნელოვანი ადგილები

| ფაილი/საქაღალდე | რას წარმოადგენს | როდის ვნახო | დაუფიქრებლად არ შეცვალო |
|---|---|---|---|
| `angular-frontend/src/app/app.routes.ts` | ახალი UI route map | რომელი გვერდი სად იხსნება | guards/deep links |
| `angular-frontend/src/app/features/` | dashboard, KB, article, news, video, account, messaging, admin pages | კონკრეტული UX flow | მხოლოდ template-ით backend rule არ „შეცვალო“ |
| `angular-frontend/src/app/core/services/` | API calls/auth/shared data | რომელ endpoint-ს იძახებს UI | URL/model/error contract |
| `angular-frontend/src/app/core/` | auth/interceptor/models/theme | token და global behavior | token storage/security |
| `angular-frontend/src/app/shared/` | reusable components/editor/helpers | განმეორებადი UI | sanitizer/accessibility |
| `angular-frontend/package.json` | direct dependency ranges/scripts | frontend tooling/version | lock-თან ერთად განიხილე |
| `angular-frontend/package-lock.json` | ზუსტი dependency tree | reproducibility/security audit | ხელით არ დაარედაქტირო |
| `angular-frontend/angular.json` | build/test/styles/budgets | production build ან global asset | budget/config ეფექტი |
| `angular-frontend/nginx.conf.template` | SPA/API reverse proxy | deploy/path/header საკითხი | proxy headers/timeouts/routes |
| `java-backend/.../web/` | REST API boundary | endpoint/input/output/access | manual gate არ გამოტოვო |
| `java-backend/.../service/` | ბიზნესლოგიკა | რეალურად როგორ სრულდება წესი | transaction/audit/scope |
| `java-backend/.../repository/` | Oracle queries | მონაცემის მიღების scope/performance | unscoped query/N+1 |
| `java-backend/.../domain/` | entity mappings | schema/type/version | Flyway-ის გარეშე schema assumption |
| `java-backend/.../security/` და config | JWT/auth/capabilities | წვდომა/session | secret/permitAll/CSRF |
| `java-backend/src/main/resources/application.yml` და profiles | runtime parameters | env, pool, cookie, Flyway | production defaults/secrets |
| `java-backend/src/main/resources/db/migration/` | Oracle schema history | data/schema ცვლილება | უკვე გაშვებული version არ შეცვალო |
| `java-backend/src/test/` | unit/Oracle/security/contract evidence | change proof | test-ის წაშლით feature ნუ „გაასწორებ“ |
| ორივე ახალი Dockerfile | deployable images | runtime/build/security | base version/non-root/health |
| `docker-compose.local.yml` | local CURRENT stack | developer setup | production topology-დ ნუ ჩათვლი |
| `.github/workflows/ci.yml` | ავტომატური checks | რას ნიშნავს green | CD/deploy-ს ნუ მიაწერ |
| `docs/PRODUCT_UX_REQUIREMENTS_KA.md` | უახლესი product target | UX/business acceptance | code implementation-ად ნუ ჩათვლი |
| `docs/ACCESS_CONTRACT_MATRIX_KA.md` | endpoint/access გადაწყვეტილებები და gaps | role/scope/security change | „decision closed“ ≠ code done |
| `docs/QUESTIONS_FOR_IT.md` | ღია ინფრასტრუქტურული კითხვები | deploy/auth/storage planning | ვარაუდით ნუ შეავსებ |
| root Python/`static/` | legacy implementation | parity/cutover/history | current stack-ში ნუ აურევ |
| root `Dockerfile`, `docker-compose.yml` | legacy PostgreSQL/Redis/FastAPI | old runtime | new production config-ად ნუ გამოიყენებ |
| `docs/archive/legacy-stack/ARCHITECTURE.md`, `README.md`, `CLAUDE.md` | შერეული/მოძველებული აღწერები | ისტორია/კონტექსტი | კოდზე მაღალ source of truth-ად ნუ ჩათვლი |

## 10.3 სწრაფი კვლევის გზა

თუ კითხვა არის „რას ხედავს მომხმარებელი?“ — დაიწყე `features` გვერდიდან, გადადი Angular service-ზე, შემდეგ Java controller/service/repository-ზე და ბოლოს Flyway schema-ზე. თუ კითხვაა „ვის აქვს უფლება?“ — controller gate, capability service, scope query და შესაბამისი contract/security test ერთად ნახე. თუ კითხვაა „როგორ გაიშვება?“ — ორივე Dockerfile, Nginx, application config, CI და `QUESTIONS_FOR_IT` შეაჯერე.

# 11. რა კითხვები უნდა დავსვა ტექნიკური ცვლილების მიღებამდე?

| კითხვა | რატომ არის მნიშვნელოვანი |
|---|---|
| რას ცვლის ეს მომხმარებლისთვის და რომელი role-სთვის? | ტექნიკური აქტივობა უნდა უკავშირდებოდეს გაზომვად მომხმარებლის შედეგს. |
| რა არის happy path და რა ხდება შეცდომისას? | acceptance მხოლოდ წარმატებული click არ არის; retry/recovery/message საჭიროა. |
| ვის ემატება ან აკლდება მოქმედების უფლება? | permission-ის ცვლილება privilege escalation შეიძლება იყოს. |
| რომელი ადამიანების/დეპარტამენტების მონაცემზე ვრცელდება? | permission scope-ის გარეშე ზედმეტად ფართო შეიძლება იყოს. |
| backend-იც ამოწმებს უფლებას თუ მხოლოდ UI? | დამალული ღილაკი პირდაპირ API call-ს ვერ აჩერებს. |
| რა ახალი პერსონალური ან სენსიტიური მონაცემი ინახება/გამოდის? | classification, minimization, consent/legal basis და breach impact იცვლება. |
| response/export-ში ყველა field საჭიროა? | ზედმეტი ID/email PII exposure-ს ზრდის; manager export target უკვე აცდენილია. |
| იცვლება Oracle schema? საჭიროა Flyway? | გარემოების თანმიმდევრულობასა და rollback-ს განსაზღვრავს. |
| ძველ rows-ს რა ემართება — backfill, default თუ nullable? | ახალი feature ძველ მონაცემზე შეიძლება გატყდეს. |
| migration რამდენ row-ს ეხება და რამდენ ხანს lockავს? | production downtime/latency და deploy window შეიძლება შეიცვალოს. |
| operation ერთ transaction-შია? | ნაწილობრივი წარმატება inconsistent business state-ს ქმნის. |
| concurrent ცვლილებაზე რა ხდება? | lost update/race condition ადმინისტრაციულ მონაცემს აზიანებს. |
| retry უსაფრთხოა და operation idempotent-ია? | timeout-ის შემდეგ გამეორებამ duplicate assignment/message არ უნდა შექმნას. |
| რა audit event იქმნება და რას არ ინახავს? | გამოძიებას სჭირდება actor/action/target/time/outcome, მაგრამ data minimization-იც. |
| retention, archive ან legal hold იცვლება? | კოდის default-ით სავალდებულო ვადას ვერ გადავწყვეტთ. |
| file upload/download იცვლება? | MIME, malware, auth, size, storage/backup და content-disposition რისკებია. |
| რა unit, integration, security და E2E test ამტკიცებს ქცევას? | სხვადასხვა მტკიცება სხვადასხვა failure-ს ფარავს. |
| უარყოფით access სცენარსაც ამოწმებს? | „სწორ user-ს მუშაობს“ არ ამტკიცებს „არასწორს ეკრძალება“. |
| რა regression შეიძლება შეეხოს სხვა role/flow-ს? | საერთო service/permission ცვლილება შორს მყოფ feature-ს არღვევს. |
| test coverage ანტესტირებულ critical branch-ს აჩვენებს? | test რაოდენობა ხარისხის სრული საზომი არაა. |
| რა ხდება 150 ტიპური ან 600 ერთდროული მომხმარებლისას? | response time, DB pool, memory, rate limiter და export load უნდა შეფასდეს; 600 product-scale upper discussion-ია. |
| რა ხდება Oracle-ის გათიშვისას? | health, error UX, retry, queue/job consistency და RTO უნდა იყოს ცნობილი. |
| რა ხდება კომპანიის IdP/AD-ის გათიშვისას? | ახალი login, არსებული session და deactivation freshness ცალ-ცალკე საკითხებია. |
| multi-replica-ზე cache/rate-limit/job იგივეა? | current in-memory cache/limiter თითო JVM-ზეა; შედეგი replica count-ზე იცვლება. |
| რა logs/metrics/alerts დაამტკიცებს production health-ს? | deploy-ის შემდეგ მხოლოდ „გვერდი გაიხსნა“ საკმარისი არაა. |
| საჭიროა IT/DBA-ს ცვლილება? | DNS/TLS/secret/DB grant/storage/proxy საკუთარი release lead time-ს ქმნის. |
| საჭიროა Security/DPO/Legal approval? | named evidence, audit retention, attachment და export policy პროდუქტის სურვილით მარტო ვერ წყდება. |
| შესაძლებელია application rollback? | წინა image ხელმისაწვდომი და config-compatible უნდა იყოს. |
| database rollback ან forward-fix როგორია? | migration image rollback-ს არ მიჰყვება. |
| rollout ეტაპობრივია? რა არის stop criterion? | canary/feature flag/shadow comparison blast radius-ს ამცირებს. |
| documentation/runbook/owner ვინ განაახლა? | ცოდნა კონკრეტულ დეველოპერზე არ უნდა დარჩეს. |

## 11.1 repository აუდიტისას აღმოჩენილი წინააღმდეგობები და implementation gaps

| წყარო/მოლოდინი | რეალური კოდი | დასკვნა |
|---|---|---|
| `README.md` და `docs/archive/legacy-stack/ARCHITECTURE.md` საკუთარ თავს მიმდინარე აღწერად წარმოაჩენს და FastAPI/PostgreSQL/Redis/static frontend-ს აღწერს | active migration მიმართულება `angular-frontend` + `java-backend` + Oracle-ია | docs არსებითად **LEGACY/მოძველებულია** |
| `docs/archive/legacy-stack/ARCHITECTURE.md`: localStorage-ში მხოლოდ unsigned shell | Angular სრულ signed JWT-ს `magti_token`-ად ინახავს | security-sensitive წინააღმდეგობა; კოდს აქვს უპირატესობა |
| `docs/archive/legacy-stack/ARCHITECTURE.md`: bilingual/Compose-centric architecture | პროდუქტის target Georgian-only და on-prem K8s; K8s manifest არ არის | ენობრივი და deployment აღწერა მოძველებული/target-ისგან განსხვავებულია |
| `CLAUDE.md`: უმაღლესი migration V35 | რეალურად 37 script და უმაღლესი V36.1 | version inventory მოძველებულია |
| `SecurityConfig` კომენტარი: business endpoint ჯერ არაა | source-ში 120 mapping annotation | კომენტარი მოძველებულია; manual gate coverage კრიტიკულია |
| `PortalBackendApplication`/ზოგი კომენტარი ძველ მდგომარეობას აღწერს | მიმდინარე modules/features გაცილებით ფართოა | კომენტარი source of truth არაა |
| `docs/archive/migration/TEST_PLAN_AND_RESULTS.md`: E2E/load „TBD/not started“ | CI-ს სრული Playwright job აქვს; migration log k6 შესრულებასაც აღნიშნავს | test status დოკუმენტი მოძველებულია; k6 მაინც CI gate არაა |
| `docs/PRODUCT_UX_REQUIREMENTS_KA.md`: Georgian-only, mobile out | `en.json`, language switch E2E და mobile menu E2E ჯერ არსებობს | transitional code; PO გადაწყვეტილების cleanup ჯერ დარჩენილია |
| target leadership-based manager scope და rollout switches | call sites legacy free-text department scope-ს enforce-ავს; ახალი resolver shadow result-ს არ აბრუნებს | **PLANNED**, არა implemented cutover |
| target authenticated attachments | upload controller public permitAll მოდელშია | D-4 ჯერ არ შესრულებულა |
| target remove personal messaging | messaging API/UI/E2E არსებობს | D-7 ჯერ არ შესრულებულა |
| target `stats.view` | stats endpoints `content.manage` gate-ს იყენებს | D-8 implementation pending |
| target manager export 8 ქართული field | CSV ინგლისური header + IDs; XLSX/PDF-შიც IDs | D-6/PO-13 pending; PII exposure review |
| target fuzzy typo + Latin transliteration search | trigram candidate narrowing + exact case-insensitive substring | trigram არსებობს, სასურველი semantics არა |
| SSE visibility helper/test შეიძლება realtime-ს ჰგავდეს | Java-ში არც `SseEmitter`/event-stream endpoint ჩანს; messaging polling-ია | Java realtime active არა; legacy FastAPI SSE-ს ნუ ავურევთ |
| audit requirement ფართოა | chain არსებობს, მაგრამ ყველა ბიზნესქმედებაზე app-level audit ერთნაირი არაა | integrity mechanism ≠ complete event coverage |
| 1-წლიანი audit/მრავალწლიანი archive და 30-დღიანი recoverable content target | Java retention/archive/delete lifecycle job არ ჩანს; export 1სთ cleanup არსებობს | policy/implementation gap |
| legacy აღწერა „server-rendered templates“ | root platform routes static HTML files-ს აძლევს და Vanilla JS API-ს იძახებს; Jinja usage არ ჩანს | legacy UI-საც ზუსტად static/Vanilla JS უნდა ვუწოდოთ |

## 11.2 საკითხები, რომლებიც IT-ის პასუხის გარეშე არ უნდა გადავწყვიტოთ

1. კომპანიის auth protocol და contract: OIDC, SAML, LDAP/LDAPS, Kerberos ან სხვა; endpoints, certificates, claims, MFA და outage behavior.
2. AD/IdP-ის authoritative employee ID, OU/group structure, multi-membership, rename/deactivation feed და მაქსიმალური 5–15 წუთის სინქრონიზაცია.
3. არსებული CI/CD პლატფორმა, runners, approvals, artifact retention და branch protection.
4. on-prem Kubernetes distro/version, namespaces, registry, ingress, service DNS, secrets, network policies, resource quotas და Oracle-ის განთავსება.
5. Oracle-ის რეალური version/topology; 19c→23ai გეგმა და native BOOLEAN compatibility.
6. BLOB tablespace/capacity/growth/backup, ან S3/MinIO/RWX/PV არჩევანი.
7. trusted reverse proxy/LB IP/CIDR, რათა client IP/rate limit/audit სწორად იმუშაოს.
8. application replica count; ერთზე მეტისას shared rate limiter/cache/job coordination-ის საჭიროება.
9. attachment domain/ingress/cache/auth, antivirus/content scanning და file classification.
10. DNS სახელი, TLS certificates/termination/renewal და frontend→backend hop encryption.
11. production/staging environment-ები, release/rollback/change window და deploy ownership.
12. centralized logging, metrics, dashboard, alert routing, SIEM integration და on-call ownership.
13. Oracle backup/restore/PITR, HA/Data Guard/failover topology, DR site და RPO=0/RTO=1სთ-ის feasibility/drill evidence.
14. secrets manager, rotation, emergency access და audit.
15. protected audit archive/WORM-ის ტექნოლოგია DPO/Legal-ის ვადების მიღების შემდეგ.

---

# 12. ქართულ–ინგლისური ლექსიკონი

ლექსიკონი დალაგებულია ქართული მნიშვნელობის მიხედვით. აქ არის **124 პრაქტიკული ტერმინი**.

| ინგლისური სახელი | ქართული მნიშვნელობა | მარტივი წინადადება | ამ პროექტის მაგალითი |
|---|---|---|---|
| Authentication | ავთენტიფიკაცია — ვინაობის შემოწმება | სისტემა ადგენს ნამდვილად ვინ ხარ. | JWT + Oracle active user check; target IdP |
| Authorization | ავტორიზაცია — უფლების შემოწმება | ვინაობის შემდეგ მოწმდება რისი გაკეთება შეგიძლია. | `articles.publish`, `compliance.assign` gates |
| Asynchronous | ასინქრონული მუშაობა | საქმე შეიძლება მოგვიანებით დასრულდეს და request არ დაელოდოს. | export background job |
| Archive | არქივი | იშვიათად საჭირო მონაცემი გრძელვადიან დაცულ საცავში გადადის. | multi-year audit archive target |
| Alerting | ალერტინგი — საგანგაშო შეტყობინება | ცუდ metric-ზე პასუხისმგებელი ადამიანი გაფრთხილებას იღებს. | repository-ში production alert wiring არაა |
| API | აპლიკაციის პროგრამული ინტერფეისი | ორი პროგრამული ნაწილი შეთანხმებული გზით საუბრობს. | Angular `/api/articles`-ს იძახებს |
| API Contract | API ხელშეკრულება | path, input, output, error და access წინასწარ განსაზღვრულია. | Java DTO ↔ Angular model |
| Audit Log | აუდიტის ჟურნალი | იწერება ვინ რა ქმედება როდის შეასრულა. | `audit_logs` |
| Audit Chain | აუდიტის ჯაჭვი | თითო ჩანაწერის hash წინას უკავშირდება. | V28 SHA-256 trigger |
| Backfill | ბექფილი — ძველი მონაცემის შევსება | ახალი field/წესი არსებულ rows-საც ერგება. | V36.1 permission override backfill |
| Backend | ბექენდი — სერვერის ნაწილი | აქ სრულდება ბიზნესწესი და რეალური access check. | `java-backend/` |
| BLOB | ბინარული დიდი ობიექტი | database-ში დიდი binary bytes ინახება. | uploaded files და export bytes |
| Browser | ბრაუზერი | მომხმარებლის მოწყობილობაზე web აპს უშვებს. | მხარდაჭერილი target Chrome desktop |
| Build | ბილდი — აწყობა | source გასაშვებ artifacts-ად გარდაიქმნება. | `ng build`, `mvn package` |
| Cache | ქეში — დროებითი სწრაფი ასლი | განმეორებითი წაკითხვისთვის შედეგს ცოტა ხანს ვინახავთ. | search 60-წამიანი in-memory cache |
| Capability | ქმედების შესაძლებლობა | role/override-ებიდან მიღებული ეფექტური უფლებაა. | `CapabilityService` |
| CD | უწყვეტი მიწოდება/განთავსება | შემოწმებული ცვლილება ავტომატურად გარემოში გადადის. | მიმდინარე workflow-ში არ არის |
| CI | უწყვეტი ინტეგრაცია | ყოველი ცვლილება ავტომატურად იწყობა და იტესტება. | GitHub Actions `ci.yml` |
| Client | კლიენტი პროგრამა | server-ს მოთხოვნას უგზავნის. | Angular browser app |
| CLOB | სიმბოლოების დიდი ობიექტი | database-ში დიდი ტექსტი ინახება. | article/news content |
| Commit | კომიტი — ისტორიის ჩანაწერი | Git-ში ცვლილების სახელდებული snapshot იქმნება. | feature commit |
| Component | კომპონენტი | UI-ს საკუთარი პასუხისმგებლობის ნაწილი. | `ArticleDetailPage` |
| Configuration | კონფიგურაცია | გარემოს ჩვეულებრივი პარამეტრია, secret არა. | pool size, log level |
| Connection Pool | კავშირების აუზი | database კავშირები ხელახლა გამოიყენება. | Hikari max 30 |
| Container | კონტეინერი | image-ის გაშვებული instance-ია. | Java backend container |
| Controller | კონტროლერი | HTTP request-ს იღებს და პასუხს აბრუნებს. | `ArticleController` |
| Cookie | ქუქი | browser server-ისთვის მცირე მნიშვნელობას ინახავს/აგზავნის. | HttpOnly access token cookie |
| Correlation ID | კორელაციის იდენტიფიკატორი | ერთი შეცდომის client პასუხსა და log-ს აკავშირებს. | global 500 response short id |
| CSRF | სხვა საიტიდან მოთხოვნის გაყალბება | უცხო გვერდი browser-ის cookie-ს ბოროტად იყენებს. | cookie auth + disabled CSRF review |
| Database | მონაცემთა ბაზა | სტრუქტურირებულ მონაცემს საიმედოდ ინახავს. | Oracle CURRENT |
| Deployment | დანერგვა/გაშვება | artifact კონკრეტულ გარემოში იწყებს მუშაობას. | production პროცესი IT-DEPENDENT |
| Dependency | დამოკიდებულება | გარე ბიბლიოთეკა, რომელსაც პროექტი იყენებს. | Spring, RxJS, DOMPurify |
| Dependency Injection | დამოკიდებულების მიწოდება | framework კლასს საჭირო service-ს აწვდის. | constructor-injected repository |
| DNS | დომენის სახელთა სისტემა | ადამიანურ სახელს IP/service-ს უკავშირებს. | production portal domain უცნობია |
| Docker Image | Docker-ის გამოსახულება | აპისა და runtime-ის უცვლელი შეფუთული შაბლონია. | Temurin 21 + JAR image |
| Domain Entity | დომენის entity | ბიზნეს ჩანაწერის Java/database mapping-ია. | `Article`, `User` |
| DTO | მონაცემთა გადასატანი ობიექტი | API-ში მხოლოდ შეთანხმებულ fields-ს ატარებს. | ArticleResponse |
| Endpoint | საბოლოო API მისამართი | კონკრეტულ path/method-ზე ოპერაცია სრულდება. | `POST /api/auth/login` |
| Environment | გარემო | app-ის გაშვების იზოლირებული კონტექსტია. | dev, test, staging, production |
| Fail-safe Default | უსაფრთხო ნაგულისხმევი უარი | გაურკვევლობისას წვდომა უნდა აიკრძალოს. | სასურველი deny-by-default |
| Failover | სარეზერვო სისტემაზე გადასვლა | primary-ის ჩავარდნისას secondary აგრძელებს. | Oracle/app მეთოდი IT-DEPENDENT |
| Feature Flag | ფუნქციის გადამრთველი | ახალ ქცევას ეტაპობრივად რთავს. | scope rollout properties, ჯერ unwired |
| Fixture | სატესტო მონაცემის ფიქსირებული ნაკრები | ტესტს ცნობილ საწყის მდგომარეობას აძლევს. | test users/articles |
| Flyway | მონაცემთა ბაზის migration tool | დანომრილ SQL ცვლილებებს სწორი რიგით უშვებს. | V1–V36.1 |
| Foreign Key | გარე გასაღები | row-ს სხვა table-ის არსებულ row-ს აკავშირებს. | article → category |
| Frontend | ფრონტენდი — მომხმარებლის ეკრანი | browser-ში ხილული და მოქმედი ნაწილია. | `angular-frontend/` |
| Git | ცვლილებების მართვის სისტემა | ფაილების ისტორიასა და branch-ებს ინახავს. | repository history |
| GitHub Actions | GitHub-ის ავტომატიზაცია | push/PR-ზე command-ებს runner-ზე უშვებს. | Java/Angular/Oracle/E2E jobs |
| Green Build | მწვანე ბილდი | განსაზღვრული CI checks წარმატებით დასრულდა. | ყველა `ci.yml` job pass |
| High Availability | მაღალი ხელმისაწვდომობა | ერთი failure სერვისს მთლიანად არ აჩერებს. | topology ჯერ IT-DEPENDENT |
| Health Endpoint | ჯანმრთელობის endpoint | სისტემის ძირითად მზადყოფნას მანქანა ამოწმებს. | `/api/health` Oracle probe |
| HTML | ჰიპერტექსტის მარკირების ენა | web გვერდის მნიშვნელობით სტრუქტურას აღწერს. | Angular templates |
| HTTP/HTTPS | web მოთხოვნის პროტოკოლი/მისი დაშიფრული ფორმა | HTTPS გზაში მონაცემს TLS-ით იცავს. | `/api` traffic; production TLS უცნობია |
| Idempotency | იდემპოტენტურობა | იგივე ბრძანების გამეორება შედეგს აღარ აორმაგებს. | retry-safe assignment ჯერ design საკითხია |
| Index | ინდექსი | database-ის საძიებო რუკაა. | compound/trigram indexes |
| Integration Test | ინტეგრაციული ტესტი | რამდენიმე ნამდვილ ნაწილს ერთად ამოწმებს. | Java + Oracle XE |
| Java | ჯავა | backend-ის strongly typed პროგრამირების ენაა. | Java 21 |
| JavaScript | ჯავასკრიპტი | browser-ის უშუალოდ შესრულებადი ენაა. | compiled Angular runtime; legacy Vanilla JS |
| JDBC | ჯავის database კავშირი | Java-ს Oracle driver-ის სტანდარტულ გზას აძლევს. | ojdbc11 |
| JSON | ტექსტური მონაცემთა ფორმატი | API-ში სახელდებულ fields-ს აგზავნის. | article response body |
| JWT | ხელმოწერილი web token | identity claims-ს, expiry-სა და signature-ს ატარებს. | 60-წუთიანი HS256 access token |
| Kubernetes | კონტეინერების ორკესტრატორი | მრავალ container instance-ს deploy/health/scale-ს მართავს. | target on-prem; manifest არაა |
| Legacy | მოძველებული/ძველი სისტემა | migration-ის გამო repository-ში დროებით რჩება. | root FastAPI stack |
| Load Balancer | დატვირთვის გამანაწილებელი | მოთხოვნებს რამდენიმე instance-ზე ანაწილებს. | production LB IT-DEPENDENT |
| Load Test | დატვირთვის ტესტი | ბევრ ერთდროულ request-ზე ქცევას ზომავს. | k6 600-user ramp script |
| Logging | ჟურნალში ჩაწერა | ტექნიკურ მოვლენებს გამოკვლევისთვის წერს. | Java console SLF4J logs |
| Maven | Java build/dependency manager | dependencies-ს ტვირთავს, ტესტავს და JAR-ს აწყობს. | `pom.xml`, Maven wrapper |
| Merge | branch-ების გაერთიანება | ორი ისტორიის ცვლილებები ერთდება. | pull request merge |
| Microservice | მიკროსერვისი | დამოუკიდებლად deployable მცირე backend service-ია. | მიმდინარე Java სისტემა ასეთი არაა |
| Migration | მიგრაცია | schema/data/system კონტროლირებულად ახალ მდგომარეობაზე გადადის. | Flyway; legacy→current cutover |
| Mock | იმიტირებული დამოკიდებულება | unit test-ში ნამდვილი გარე ნაწილი იცვლება. | Mockito repository mock |
| Modular Monolith | მოდულური მონოლითი | ერთი deployable app შიდა პასუხისმგებლობებადაა დაყოფილი. | ერთი Spring Boot JAR |
| Monitoring | მონიტორინგი | health და metrics-ს უწყვეტად აკვირდება. | collector/dashboard repo-ში არაა |
| Nginx | ვებ-სერვერი/reverse proxy | SPA-ს გასცემს და API-ს backend-ზე აგზავნის. | frontend container port 8080 |
| Node.js | JavaScript build runtime | frontend tooling-ს browser-ის გარეთ უშვებს. | pinned 22.22.3 |
| npm | Node package manager | frontend dependencies/scripts-ს მართავს. | npm 11.17.0 |
| Observable | დაკვირვებადი ნაკადი | დროში მოსულ მნიშვნელობებს/operators-ით აერთიანებს. | Angular HTTP RxJS |
| Optimistic Locking | ოპტიმისტური ბლოკირება | version-ის აცდენით ძველ update-ს აჩერებს. | Article/User `@Version` |
| Oracle Database | Oracle მონაცემთა ბაზა | current relational source of truth-ის მიზნობრივი engine-ია. | local XE21c, target 19c |
| Package Lock | dependency-ების ზუსტი საკეტი | ყველა build-ში იგივე tree-ს იმეორებს. | `package-lock.json` v3 |
| Permission | მოქმედების უფლება | განსაზღვრავს რას აკეთებს user. | `content.manage` |
| Primary Key | პირველადი გასაღები | row-ს უნიკალურ ID-ს აძლევს. | article `id` |
| Pull Request | ცვლილების განხილვის მოთხოვნა | branch merge-მდე diff/test/review ჩანს. | GitHub PR |
| Production | რეალური სამუშაო გარემო | აქ რეალური users/dataა და მკაცრი კონტროლი სჭირდება. | deployment details IT-DEPENDENT |
| Proxy / Reverse Proxy | შუამავალი მიმმართველი | client request-ს რეალურ backend-ს გადასცემს. | Nginx `/api` proxy |
| Race Condition | დროით გამოწვეული შეჯიბრი | შედეგი ორი პარალელური მოქმედების რიგზეა დამოკიდებული. | concurrent admin updates |
| Regression | უკუსვლა | ახალი ცვლილება ძველ მუშა ქცევას აფუჭებს. | role change სხვა endpoint-ს ხსნის |
| Repository Pattern | საცავთან წვდომის ფენა | database query/save-ს service-ისგან გამოყოფს. | Spring Data repositories |
| Request | მოთხოვნა | client server-ს ოპერაციას სთხოვს. | `GET /api/articles/1` |
| Response | პასუხი | server status/data/error-ს აბრუნებს. | 200 JSON, 403, 409 |
| REST | რესურსებზე ორიენტირებული API სტილი | HTTP method/path-ებით მოქმედებას გამოხატავს. | GET/POST/PUT article endpoints |
| Retention | შენახვის ვადა | ადგენს როდის იშლება ან არქივდება მონაცემი. | export TTL 1 საათი |
| Role | როლი | სამუშაო ტიპს permissions-ის default ნაკრებს აძლევს. | MANAGER |
| Rollback | წინა მდგომარეობაზე დაბრუნება | ცუდ release-ზე app/image ან transaction ბრუნდება. | DB migration ცალკე გეგმას ითხოვს |
| Route | მარშრუტი | URL-ს ეკრანთან ან endpoint-თან აკავშირებს. | Angular `/article/:id` |
| Row | მწკრივი | table-ში ერთი ჩანაწერია. | ერთი user |
| RPO | აღდგენის წერტილის მიზანი | რამდენი დროის მონაცემის დაკარგვაა დასაშვები. | target 0 |
| RTO | აღდგენის დროის მიზანი | ავარიიდან რამდენ ხანში უნდა აღდგეს სერვისი. | target 1 საათი |
| RxJS | რეაქტიული JavaScript ბიბლიოთეკა | Observable ნაკადებსა და operators-ს იძლევა. | HTTP chaining/search |
| Schema | მონაცემთა ბაზის სტრუქტურა | tables, columns, keys და indexes-ის ერთობლიობაა. | Flyway-owned Oracle schema |
| Secret | საიდუმლო პარამეტრი | მისი გაჟონვა წვდომას ან ნდობას არღვევს. | JWT key, Oracle password |
| Security Boundary | ნდობის საზღვარი | გადასვლისას input/identity/access ხელახლა მოწმდება. | browser → Java API |
| Seed | საწყისი მონაცემის დათესვა | ცარიელ გარემოს ცნობილ მონაცემს უმატებს. | local seed container |
| Semantic Versioning | სემანტიკური ვერსიონირება | major/minor/patch ცვლილების მასშტაბს მიუთითებს. | Angular `22.1.0` |
| Service | სერვისი/ბიზნესლოგიკის ფენა | წესსა და ოპერაციის ნაბიჯებს აერთიანებს. | ArticleService/CapabilityService |
| SIEM | უსაფრთხოების მოვლენების ცენტრალური მართვა | მრავალ log-ს საეჭვო ქცევის საპოვნელად აერთიანებს. | integration IT-DEPENDENT |
| Scope | მონაცემთა მოქმედების არეალი | permission-ის გამოყენება კონკრეტულ ხალხზე/დეპარტამენტზე იზღუდება. | manager department scope |
| Source of Truth | სიმართლის ავტორიტეტული წყარო | წინააღმდეგობისას საბოლოო პასუხს აქ ვეძებთ. | მიმდინარე code/Flyway/Oracle |
| SPA | ერთგვერდიანი აპლიკაცია | გვერდები browser-ში სრული reload-ის გარეშე იცვლება. | Angular portal |
| Spring Boot | Java application framework | backend-ის web/config/data/security ნაწილებს აწყობს. | 4.1.0 |
| Spring MVC | სინქრონული Java web framework | controller request-ს servlet thread-ზე ამუშავებს. | current REST controllers |
| Spring Security | Java security framework | auth filter/context/control primitives-ს იძლევა. | JWT filter; manual controller gates |
| SQL | მონაცემთა ბაზის მოთხოვნის ენა | rows-ს კითხულობს/ცვლის და schema-ს ქმნის. | Flyway Oracle scripts |
| Staging | წინასაპროდუქციო გარემო | production-ის მსგავსად release-ს საბოლოოდ ამოწმებს. | repository-ში არ არის განსაზღვრული |
| Stateful | მდგომარეობის მქონე | request-ებს შორის მონაცემი რჩება. | Oracle rows/export jobs |
| Stateless | server session-memory-ზე დაუყრდნობელი request | request identity-ს token-ით მოაქვს. | JWT filter, თუმცა app მთლიანად state-free არაა |
| Test Coverage | ტესტით დაფარული კოდი | აჩვენებს რა გაეშვა test-ში, არა რომ ყველაფერი სწორია. | CI threshold/report არ ჩანს |
| Transaction | ტრანზაქცია | რამდენიმე DB ნაბიჯს commit/rollback ერთეულად აერთიანებს. | article + history save |
| TypeScript | ტიპებიანი JavaScript | build-მდე model/type შეცდომებს ადრე პოულობს. | Angular `.ts` files |
| Unit Test | ერთეულის ტესტი | პატარა წესს იზოლირებულად და სწრაფად ამოწმებს. | CapabilityService JUnit test |
| Validation | ვალიდაცია | input-ის ფორმატსა და ბიზნესწესს ამოწმებს. | required title + permission |
| Vitest | frontend unit test runner | TypeScript/Angular tests-ს სწრაფად უშვებს. | `*.spec.ts` |
| Playwright | browser E2E tool | რეალურ Chromium-ში user flow-ს ამოწმებს. | login/admin E2E |
| XSS | script-ის ჩასმის შეტევა | მავნე HTML/JS სხვა user-ის browser-ში ეშვება. | DOMPurify + Angular sanitizer |

# 13. ცოდნის თვითშემოწმება

პასუხის გაცემისას კოდის დაწერა საჭირო არ არის. ჯერ უპასუხეთ ქვემოთ მოცემულ კითხვებს, შემდეგ შეადარეთ 13.4 განყოფილებას.

## 13.1 მარტივი კითხვები — 30

1. რა არის ამ სისტემის სამი მთავარი ნაწილი?
2. რა არის frontend და რომელი ტექნოლოგიაა აქ მისი საფუძველი?
3. რა არის backend და რა stack-ია მიმდინარე?
4. რომელი მონაცემთა ბაზაა მიმდინარე target?
5. რას აკეთებს Nginx?
6. რას ნიშნავს SPA?
7. რა განსხვავებაა HTML-სა და CSS-ს შორის?
8. რატომ იყენებს Angular TypeScript-ს?
9. რას აკეთებს Angular Router?
10. რა არის API?
11. რა ფორმატით ცვლის Angular და Java მონაცემს?
12. რას აკეთებს Controller?
13. რას აკეთებს Service?
14. რას აკეთებს Repository?
15. რა არის database table და row?
16. რა არის primary key?
17. რა არის foreign key?
18. რას აკეთებს index?
19. რა არის transaction?
20. რას ნიშნავს commit და rollback?
21. რას აკეთებს Flyway?
22. რა განსხვავებაა authentication-სა და authorization-ს შორის?
23. რა განსხვავებაა role-ს, permission-სა და scope-ს შორის?
24. რატომ არ არის დამალული ღილაკი საკმარისი დაცვა?
25. რა არის JWT?
26. რას აკეთებს DOMPurify?
27. რა განსხვავებაა Docker image-სა და container-ს შორის?
28. რა არის CI და სად არის მისი workflow?
29. რომელი stack არის legacy?
30. რას ნიშნავს IT-DEPENDENT?

## 13.2 საშუალო სირთულის კითხვები — 20

31. აღწერეთ სტატიის მოთხოვნის გზა browser-იდან Oracle-მდე და უკან.
32. რატომ არის DTO-ს გამოყენება entity-ის პირდაპირ დაბრუნებაზე უსაფრთხო/მართვადი?
33. რატომ არ ცვლის frontend validation backend validation-ს?
34. როგორ აუქმებს მიმდინარე Java სისტემა ძველ JWT-ს logout/deactivation-ისას?
35. localStorage token-სა და HttpOnly cookie-ს რა განსხვავებული რისკი აქვს?
36. რატომ მოითხოვს cookie authentication CSRF threat-model-ს, თუნდაც backend „stateless“ ერქვას?
37. რას იცავს optimistic locking და რა HTTP პასუხი აქვს conflict-ს?
38. რატომ არის leadership scope ჯერ target და არა current enforcement?
39. რას აკეთებს trigram მიდგომა და რას ვერ აკეთებს მიმდინარე search?
40. რატომ არ არის audit hash chain ყველა audit მოთხოვნის სრული გადაწყვეტა?
41. backup, archive და retention როგორ განსხვავდება?
42. რატომ ვერ აბრუნებს ძველი Docker image თავისით უკვე შესრულებულ Flyway migration-ს?
43. რას ნიშნავს green build და რას არ ნიშნავს?
44. unit, Oracle integration და E2E test რა განსხვავებულ რამეს ამოწმებს?
45. fixture-სა და mock-ს შორის რა განსხვავებაა?
46. რატომ არის current Java სისტემა modular monolith და არა microservices?
47. რატომ იცვლება in-memory cache/rate limiter-ის ქცევა რამდენიმე replica-ზე?
48. რატომ არ ნიშნავს Kubernetes-ის ხსენება, რომ production deployment უკვე მზადაა?
49. რა წინააღმდეგობაა Georgian-only target-სა და მიმდინარე frontend-ს შორის?
50. რატომ არის legacy `backup.py` არასაკმარისი ახალი სისტემისთვის?

## 13.3 სიტუაციური კითხვები Product Owner-ისთვის — 10

51. Developer ამბობს: „მენეჯერის export ღილაკი ოპერატორს დავუმალეთ, ამიტომ დაცულია.“ რას ჰკითხავთ?
52. სტატია გამოქვეყნდა, მაგრამ სავალდებულო assignment არ შეიქმნა. რომელი არქიტექტურული მიზეზია მოსალოდნელი და რა business გადაწყვეტილებაა საჭირო?
53. IT გთავაზობთ ორ application replica-ს. რომელი current მექანიზმები უნდა გადაამოწმოთ multi-replica ქცევაზე?
54. ახალი feature პერსონალურ read evidence-ს PDF-ად გააქვს. ვის რა გადაწყვეტილება ეკუთვნის?
55. Flyway migration მილიონ row-ს backfill-ს უკეთებს. რა მტკიცებულებებს მოითხოვთ production-მდე?
56. Oracle 23ai-ზე upgrade იგეგმება. რა current compatibility საკითხი უნდა გაიხსენოთ?
57. Security review ამბობს, რომ attachment URL საჯაროდ იხსნება. რა current/target gap-ია და რას ითხოვთ?
58. CI მწვანეა, მაგრამ release-ის შემდეგ latency გაიზარდა. რატომ შეიძლებოდა CI-ს ეს ვერ დაეჭირა და რა აკლია?
59. კომპანიის IdP 30 წუთით გაითიშა. რა ცალკე კითხვები უნდა დასვათ ახალ login-ზე, არსებულ session-ზე და user deactivation-ზე?
60. Production migration-ის შემდეგ bug აღმოჩნდა. Developer ამბობს „უბრალოდ წინა image დავაბრუნოთ“. რას ამოწმებთ გადაწყვეტილებამდე?

## 13.4 პასუხები

### მარტივი პასუხები

1. Angular frontend, Java/Spring Boot backend და Oracle database.
2. მომხმარებლის browser ეკრანია; საფუძველი Angular 22-ია.
3. სერვერის API/ბიზნესწესებია; Java 21 + Spring Boot 4.1/Spring MVC.
4. Oracle; local/CI XE 21c, production target/version/topology IT-სთან დასადასტურებელია.
5. Angular static files-ს გასცემს და `/api`/`/uploads` მოთხოვნებს Java-ზე proxy-ს უკეთებს.
6. Single Page Application — browser-ში გვერდები სრული document reload-ის გარეშე იცვლება.
7. HTML სტრუქტურაა, CSS ვიზუალური ფორმა/განლაგება.
8. ტიპები შეცდომებს ადრე პოულობს და დიდ UI-ს გასაგებს ხდის; ბოლოს JavaScript იქმნება.
9. URL-ს Angular component-თან აკავშირებს.
10. პროგრამულ ნაწილებს შორის შეთანხმებული ინტერფეისია.
11. JSON-ით HTTP/REST API-ზე.
12. HTTP request-ს იღებს, access/input-ს ამუშავებს და response-ს აბრუნებს.
13. ბიზნესწესსა და ოპერაციის orchestration-ს ასრულებს.
14. database entity-ს კითხულობს/ინახავს.
15. table ერთი ტიპის ჩანაწერების სტრუქტურაა; row — ერთი ჩანაწერი.
16. row-ს უნიკალური იდენტიფიკატორია.
17. ერთი table-ის row-ს მეორე table-ის არსებულ row-ს უკავშირებს.
18. query-ს აჩქარებს დამატებითი საძიებო სტრუქტურით.
19. რამდენიმე DB ნაბიჯის commit/rollback ერთეულია.
20. commit ამტკიცებს; rollback დაუმტკიცებელ transaction ცვლილებას აბრუნებს.
21. დანომრილ SQL migration-ებს სწორი რიგით უშვებს და ისტორიას ინახავს.
22. authentication ადგენს ვინ ხარ; authorization — რისი უფლება გაქვს.
23. role აჯგუფებს სამუშაო ტიპს; permission მოქმედებაა; scope — რომელი მონაცემის მიმართ.
24. client-ს პირდაპირ API request შეუძლია; backend gate აუცილებელია.
25. ხელმოწერილი token identity claims/expiry-ით; permission-ის შემცვლელი არაა.
26. არასანდო HTML-ს საშიში ნაწილებისგან წმენდს და XSS რისკს ამცირებს.
27. image შეფუთული შაბლონია; container მისი გაშვებული instance.
28. ცვლილების ავტომატური build/test; `.github/workflows/ci.yml`.
29. root Python/FastAPI/SQLAlchemy/SQLite/PostgreSQL/Redis/Uvicorn/Gunicorn/static Vanilla JS.
30. repository მარტო პასუხს ვერ იძლევა და კომპანიის ინფრასტრუქტურის მფლობელის დადასტურებაა საჭირო.

### საშუალო პასუხები

31. Browser იღებს Angular-ს Nginx-იდან; Angular `/api` request-ს Nginx-ს აძლევს; Java JWT/access/business rule-ს ამოწმებს; JPA→Hibernate→JDBC/Hikari Oracle-ს მიმართავს; JSON იმავე გზით ბრუნდება.
32. DTO მხოლოდ contract-ის საჭირო fields-ს ავრცელებს, entity-ის შიდა/სენსიტიურ fields-სა და schema coupling-ს ზღუდავს.
33. browser-ის კონტროლი bypass-დება; backend-ს input და business/access rule ავტორიტეტულად უნდა გადაამოწმოს.
34. JWT filter ყოველ request-ზე Oracle user active/token_version-ს ამოწმებს; logout token_version-ს ზრდის.
35. JavaScript localStorage token-ს ხედავს და XSS მოიპარავს; HttpOnly cookie-ს JS ვერ კითხულობს, მაგრამ browser ავტომატურად აგზავნის და CSRF უნდა შეფასდეს.
36. stateless server session-ის არქონას ნიშნავს; browser cookie-ს cross-site request-ზე ავტომატურად მიყოლა მაინც შეიძლება.
37. ძველი version-ით silent overwrite-ს; current handler HTTP 409 conflict-ს აბრუნებს.
38. ახალი resolver/ცხრილები/shadow comparison არსებობს, მაგრამ call sites legacy department პასუხს enforce-ავს და flags არ არის wired.
39. trigrams candidate rows-ს ავიწროებს; საბოლოო ძებნა case-insensitive exact substring-ია და typo/transliteration target ჯერ არაა.
40. chain tampering-ს ამოსაცნობს ხდის, მაგრამ prevention/immutable archive/retention/full event coverage-ს არ უზრუნველყოფს.
41. backup აღდგენის ასლია; archive გრძელვადიანი დაცული საცავი; retention შენახვის/წაშლის დროითი წესი.
42. schema/data state database-ში რჩება და ძველ code-ს შეიძლება აღარ შეესაბამებოდეს; forward-fix/restore გეგმაა საჭირო.
43. workflow-ში განსაზღვრული checks გაიარა; production deploy, სრული correctness, load/security/DR გარანტია არაა.
44. unit იზოლირებულ წესს; Oracle integration ნამდვილ schema/driver/query-ს; E2E browser→API→DB flow-ს.
45. fixture საწყისი test dataა; mock ნამდვილი dependency-ის კონტროლირებადი იმიტაცია.
46. ერთი JAR/process/deployment unit-ია, მხოლოდ შიდა packages/modules-ადაა დაყოფილი.
47. თითო JVM-ს საკუთარი memory აქვს; cache/rate counters/jobs replicas შორის ავტომატურად არ იზიარება.
48. repository-ში manifest/Helm/CD/cluster facts არაა; Dockerfile მხოლოდ შეფუთვას ამზადებს.
49. `en.json`, language switch და მისი E2E ჯერ არსებობს, მაშინ როცა latest product target Georgian-only-ია.
50. ის legacy SQLite/PostgreSQL პროცესისთვისაა; Oracle backup/restore/HA-ს არ მართავს.

### სიტუაციური პასუხები

51. ჰკითხეთ რომელ backend endpoint-ზე რომელი permission და scope მოწმდება და აქვს თუ არა negative authorization/contract test.
52. Angular article save-სა და required-reading sync-ს ორ ცალკე request/transaction-ად აკეთებს, მეორე შეცდომა non-fatal-ია. PO-მ გადაწყვიტოს atomic requirement, retry/recovery და user-visible status.
53. 60-წამიანი in-memory search cache/single-flight, rate limiting, async jobs/scheduler cleanup, trusted client IP და sticky/shared state საჭიროება.
54. PO წყვეტს business audience/8-field need-ს; Development აკეთებს scope/gates/export; IT storage/download/operations-ს; DPO/Security/Legal lawful basis, fields, audit, retention-ს ამტკიცებს.
55. row count/time/locks/undo-space, production-like rehearsal, compatibility, backup/restore/forward-fix, monitoring/stop criteria და Oracle integration evidence.
56. current NUMBER(1)-ზე boolean mappings/schema validation; 23ai native BOOLEAN-ზე migration/dialect compatibility წინასწარ უნდა შემოწმდეს.
57. D-4 authenticated file access target ჯერ არ არის; მოითხოვეთ ყოველი download-ის backend auth+object permission, tests, cache/ingress/scanning policy.
58. k6 CI gate არაა, threshold სუსტია და production observability/CD config repo-ში არაა; საჭიროა representative load/SLO, metrics, dashboards და alerts.
59. შეუძლია თუ არა ახალი login; valid token აგრძელებს თუ არა; user/role/deactivation ცვლილება რა freshness-ით მოდის; fallback fail-open ხომ არაა; რა message/RTOა.
60. შესრულდა თუ არა schema/data migration, backward-compatibleა თუ არა, შეიძლება forward-fix, არსებობს/გამოცდილია backup restore, რა დაკარგვის ფანჯარა/stop criterion აქვს.

---

# 14. ცალკე ამოსაბეჭდი მოკლე ფურცლები

## 14.1 მთელი პროექტი ერთ გვერდზე

**პროდუქტი:** შიდა ცოდნის, სავალდებულო წაკითხვის, ადმინისტრირების და შესაბამისობის portal call center-ის თანამშრომლებისთვის.

**მიმდინარე stack:** Angular 22 SPA → Nginx → Java 21/Spring Boot 4.1 modular monolith REST API → JPA/Hibernate/JDBC/Hikari → Oracle. Schema-ს Flyway V1–V36.1 მართავს. ახალი stack-ის Docker images/local compose არსებობს. GitHub Actions unit/integration/build/E2E checks-ს უშვებს.

**მოთხოვნის გზა:** browser იღებს Angular files-ს; Angular `/api` JSON request-ს აგზავნის; Nginx Java-ზე proxy-ს უკეთებს; Java user/token/permission/scope/business rule-ს ამოწმებს; Oracle transaction-ში კითხულობს/წერს; response უკან ბრუნდება.

**უსაფრთხოება:** signed JWT + ყოველ request-ზე live Oracle user/token_version. Backend manual controller gates-ს იყენებს, რადგან global config `permitAll()`-ით მთავრდება. Role ≠ permission ≠ scope. UI guard უსაფრთხოება არაა. Full token localStorage-შიცაა — XSS review მნიშვნელოვანია. Cookie path-ის გამო CSRF formal review საჭიროა. Files authenticated access target ჯერ არ არის.

**მონაცემი:** Oracle CURRENT; CLOB content, BLOB uploads/exports. Export BLOB 1 საათში იშლება. Flyway schema source of truth-ია. Optimistic locking Article/User-ზე 409 conflict-ს იძლევა. Audit SHA-256 chain tamper-evident-ია, მაგრამ event coverage/retention/immutable archive დასრულებული არაა.

**ტესტები:** JUnit/Spring/Mockito; Oracle integration; access contract/security; Vitest; Playwright; k6 script. Green CI მხოლოდ განსაზღვრული checks-ის წარმატებაა. Explicit coverage threshold და load CI gate არ ჩანს.

**Legacy:** root Python/FastAPI/SQLAlchemy/SQLite/PostgreSQL/Redis/Uvicorn/Gunicorn/static Vanilla JS. Cutover target-ის შემდეგ 30 დღე read-only, მერე off; მიმდინარე Java behavior-ს ძველი code/docs-ით ნუ დავასკვნით.

**Target gaps:** company IdP/AD; leadership scope cutover; authenticated files; `stats.view`; personal messaging removal; fuzzy/transliteration search; 8-field Georgian export; სრული retention/archive; DPO gates.

**IT-DEPENDENT:** K8s distro/manifests/CD, registry, replicas, TLS/DNS/LB, trusted proxies, Oracle version/HA/backup/DR, secrets, file storage/scanning, centralized logs/metrics/alerts/SIEM, staging. PO target RPO=0/RTO=1სთ IT/DBA-მ უნდა დაასაბუთოს.

**ერთი მთავარი წესი:** პროდუქტის ქცევას PO წყვეტს; რეალიზაციას Development; რეალურ ინფრასტრუქტურას IT/DBA; რეგულაციასა და სავალდებულო retention-ს Security/DPO/Legal.

## 14.2 20 ტერმინი, რომელიც აუცილებლად უნდა ვიცოდე

| # | ტერმინი | ერთი ხაზით |
|---:|---|---|
| 1 | Frontend | Angular ეკრანი browser-ში. |
| 2 | Backend | Java-ს ავტორიტეტული წესები/API. |
| 3 | Database | Oracle-ის მდგრადი მონაცემები. |
| 4 | API contract | path+input+output+error+access შეთანხმება. |
| 5 | Request/Response | მოთხოვნა და server-ის პასუხი. |
| 6 | Authentication | ვინ ხარ. |
| 7 | Authorization | რისი გაკეთება შეგიძლია. |
| 8 | Role/Permission/Scope | სამუშაო ჯგუფი / მოქმედება / ვისი მონაცემი. |
| 9 | Transaction | database-ის ყველაფერი-ან-არაფერი ერთეული. |
| 10 | Flyway migration | schema-ს დანომრილი ცვლილება. |
| 11 | Backfill | ახალი მონაცემის ძველ rows-ზე შევსება. |
| 12 | Optimistic locking | ძველი version-ით overwrite-ის 409-ით შეჩერება. |
| 13 | Audit | ვინ/რა/როდის; chain ცვლილებას ამოსაცნობს ხდის. |
| 14 | Retention/Archive/Backup | შენახვის წესი / გრძელვადიანი საცავი / აღდგენის ასლი. |
| 15 | Docker image/container | შეფუთული შაბლონი / გაშვებული instance. |
| 16 | CI/CD | ავტომატური შემოწმება / ავტომატური მიწოდება; CD ჯერ არაა. |
| 17 | RPO/RTO | დასაშვები data loss / აღდგენის დრო. |
| 18 | Legacy | ძველი Python stack, current Java/Angular არა. |
| 19 | Source of truth | წინააღმდეგობისას ავტორიტეტული წყარო. |
| 20 | IT-DEPENDENT | company infrastructure-ის დადასტურების გარეშე ვერ გადავწყვეტთ. |

## 14.3 10 წითელი ალამი Product Owner-ისთვის

1. **„ღილაკი დავმალეთ, ამიტომ დაცულია.“** მოითხოვეთ backend permission+scope და negative test.
2. **„Decision closed წერია, ამიტომ უკვე გაკეთებულია.“** შეამოწმეთ current code/test; რამდენიმე D-გადაწყვეტილება ჯერ pending-ია.
3. **„CI მწვანეა, ამიტომ production მზადაა.“** CD/load/observability/security/DR შეიძლება coverage-ში არ შედიოდეს.
4. **„უბრალოდ წინა image-ს დავაბრუნებთ.“** ჯერ database migration/backward compatibility შეამოწმეთ.
5. **„გვაქვს backup.“** მოითხოვეთ restore drill, RPO/RTO evidence და პასუხისმგებელი owner.
6. **„JWT გვაქვს, ამიტომ უსაფრთხო ვართ.“** token storage, XSS, CSRF, revoke, secret rotation, backend gates და TLS ცალკეა.
7. **„ყველა manager ხედავს თავის ხალხს.“** current legacy scope-სა და target leadership scope cutover-ს ნუ აურევთ.
8. **„Export უბრალოდ სხვა ფორმატია.“** fields, named PII, scope, audit, expiry და DPO approval მთავარია.
9. **„Kubernetes გვაქვს ნახსენები.“** manifest/CD/registry/ingress/secrets/replicas/HA-ის რეალური მტკიცებულება მოითხოვეთ.
10. **„ძველი docs ასე ამბობს.“** README/ARCHITECTURE/CLAUDE/test status შერეულია; მიმდინარე code, migrations, tests და დამტკიცებული latest requirements შეაჯერეთ.

---

# დანართი A — ამ სახელმძღვანელოს მტკიცებულების საზღვარი

სახელმძღვანელოს დასაწერად გადამოწმდა root სტრუქტურა; `java-backend` package-ები, source/tests, `pom.xml`, application configuration და Flyway SQL; `angular-frontend` feature/core/shared/shell, routes, services, tests, `package.json`, lock და `angular.json`; ახალი/legacy Docker/Compose და Nginx; GitHub Actions; load script; მოთხოვნების, access, IT კითხვების, architecture, migration და legacy docs; root Python/FastAPI/SQLAlchemy/static სისტემა.

რაოდენობები source inventory-ია და არა ამ დოკუმენტის შექმნისას tests-ის ხელახლა გაშვების ანგარიში: 285 Java main file, 86 Java test file, 23 controller, 32 repository, 32 entity, 120 mapping annotation; 37 Flyway script; 183 Angular app file, 20 unit spec და 19 Playwright spec. test annotation/case რაოდენობები დაახლოებითია, რადგან parameterized/nested/generated execution runtime count-ს ცვლის.

ამ ცვლილებას პროგრამის source/config/test/migration ფაილი არ შეუცვლია. შეიქმნა მხოლოდ ეს ახალი Markdown დოკუმენტი; commit, push და Git history rewrite არ შესრულებულა.
