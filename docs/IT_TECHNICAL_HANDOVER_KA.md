# Magti Portal — ტექნიკური დოკუმენტაცია IT დეპარტამენტისთვის

| დოკუმენტის კონტროლი | მნიშვნელობა |
|---|---|
| ვერსია | 1.1, 2026-09-29 (1.0 — 2026-09-28) |
| აუდიტორია | Platform/DevOps, DBA, IAM, ინფორმაციული უსაფრთხოება, მხარდაჭერა და პროექტის მფლობელი |
| სტატუსი | მიმდინარე კოდისა და კონფიგურაციის აღწერა; **production-ის მიღების ოქმი არ არის** |
| საზღვარი | `main` ბრენჩის მდგომარეობა 2026-09-29-ს (PR #30-ის შემდეგ). კონკრეტული release/image ჯერ არ არის იდენტიფიცირებული — ის IT-ის პირველი build-ით განისაზღვრება |

ამ დოკუმენტის მიზანია IT-ს გადასცეს პორტალის მოქმედი ტექნიკური სურათი, გასაშვებად საჭირო გადაწყვეტილებები და მისაღები მტკიცებულება. პროდუქტის ქცევის საბოლოო წყაროა [მფლობელის გადაწყვეტილებები](PRODUCT_OWNER_DECISIONS_KA.md), endpoint-ების უფლებების — [წვდომის მატრიცა](ACCESS_CONTRACT_MATRIX_KA.md), ხოლო კონკრეტული გაშვების ნაბიჯების — [Kubernetes-ის ცნობარი](../k8s/README_KA.md) და [საოპერაციო სახელმძღვანელო](IT_OPERATIONS_RUNBOOK_KA.md). ისტორიული `api-contract/openapi.json` **არ არის** მოქმედი Java API-ის სრული OpenAPI სპეციფიკაცია.

## 1. მოკლე სტატუსი და გადაწყვეტილების საზღვარი

| საკითხი | დადასტურებული მდგომარეობა | production-მდე საჭირო მტკიცებულება |
|---|---|---|
| პროდუქტი | Java/Angular/Oracle სტეკი და ლოკალური Compose მუშაობს; [ლოკალური ცდის ჩანაწერი](IT_OPERATIONS_RUNBOOK_KA.md#გაშვების-მდგომარეობა-და-მისაღები-მტკიცებულება) დათარიღებულია 2026-09-23-ით | კონკრეტული release-ის CI შედეგი, staging UAT და ხელმოწერილი მიღება |
| კორპორაციული შესვლა | OAuth2 `ldap_auth` ადაპტერი და როლების mapping კოდშია. IT-მა ტელეფონით დაადასტურა (2026-09-29), რომ 2026-09-18-ის წერილის კონფიგურაცია სწორია; 2026-09-21-ის გაზომვისას კი სერვისმა ამ გასაღებზე `client_id=InfoPortal` უარყო. საბოლოოდ ამას production-ის პირველი შესვლა გადაწყვეტს — [როგორ წავიკითხოთ მისი შედეგი](../k8s/README_KA.md#რაც-ჯერ-კიდევ-ღიაა) | საკუთარი client credential, ოთხი შეთანხმებული როლი და Magti-ს ქსელიდან დადებითი/უარყოფითი ცდები — [IT №13](QUESTIONS_FOR_IT.md) |
| ინფრასტრუქტურა | `k8s/` მანიფესტები არსებობს, მაგრამ `IT-NN` ველები შესავსებია; staging/production-ის გაშვების მტკიცებულება არ არსებობს | ქსელი, TLS, Oracle, image-ები, Secret, proxy chain, monitoring და rollout-ის ოქმი |
| მონაცემთა აღდგენა | მფლობელის **სამიზნეა** RPO 0 და RTO 1 საათი; ეს ჯერ გაზომილი შესაძლებლობა არ არის | DBA-ს იზოლირებული restore/failover rehearsal, BLOB-ებითა და audit-ით — [PO-09](PRODUCT_OWNER_DECISIONS_KA.md#po-09--გადაწყვეტილია-rpo-0-rto-1-საათი) |
| აუდიტის მთლიანობა | Oracle-ში hash-ჯაჭვი და მისი შემოწმების API არსებობს | დამოუკიდებელი, უცვლელი გარე საკონტროლო ასლის პროცესი — [IT №14](QUESTIONS_FOR_IT.md) |

**გაშვების ვერდიქტი:** ამ დოკუმენტის შედგენისას production-ის მზადყოფნა **დაუდასტურებელია**. ცალკეული კოდის ტესტის ან ლეპტოპის წარმატება არ ხურავს IAM-ის, ქსელის, აღდგენისა და უსაფრთხოების გარე მოთხოვნებს.

## 2. დანიშნულება და ფუნქციური საზღვრები

პორტალი არის დაახლოებით 600-კაციანი ქოლ-ცენტრის შიდა ცოდნისა და შესაბამისობის სისტემა. სამიზნეა ქართული ინტერფეისი Chrome-ში, 1080p desktop-ზე, მუქი და ღია თემებით. მიმდინარე [Angular მარშრუტები](../angular-frontend/src/app/app.routes.ts) მოიცავს ცოდნის ბაზას, სიახლეებს, ვიდეოინსტრუქციებს, რჩეულებს, სავალდებულო საკითხავს, ხელმძღვანელის ხედს და ადმინისტრაციულ გვერდებს. მობილური და touch ამ ეტაპის მიღების საზღვარში არ შედის.

| მომხმარებელი/ფუნქცია | ძირითადი შესაძლებლობა | უფლებების ზუსტი წყარო |
|---|---|---|
| თანამშრომელი | მის აუდიტორიაზე გამოქვეყნებული კონტენტი, სავალდებულო წაკითხვის დადასტურება და ქვიზი | [ArticleVisibility](../java-backend/src/main/java/ge/magti/portal/article/ArticleVisibility.java), [წვდომის მატრიცა](ACCESS_CONTRACT_MATRIX_KA.md) |
| ხელმძღვანელი | საკუთარი ჯგუფის/დეპარტამენტის სახელობითი პროგრესი და შესაბამისი ექსპორტი | [ManagerScope](../java-backend/src/main/java/ge/magti/portal/security/ManagerScope.java), [PO-01](PRODUCT_OWNER_DECISIONS_KA.md#po-01--d-2--გადაწყვეტილია-სახელობითი-სია-leadership-scope-ით) |
| კონტენტის მმართველი | სტატიების, სიახლეების, ვიდეოების, კატეგორიებისა და სავალდებულო საკითხავის მართვა | `content.manage` და თითოეული endpoint-ის დამატებითი gate — [წვდომის მატრიცა](ACCESS_CONTRACT_MATRIX_KA.md) |
| სისტემური ადმინისტრატორი | მომხმარებლებისა და ორგანიზაციის ადმინისტრირება, raw audit და ადმინისტრაციული ექსპორტები | [წვდომის მატრიცა](ACCESS_CONTRACT_MATRIX_KA.md), [ექსპორტის ინვენტარი](SYSTEM_ADMIN_EXPORT_INVENTORY_KA.md) |

UI-ის დამალული მენიუ უსაფრთხოების გარანტია არ არის: backend-ის filter ითხოვს ავტორიზაციას, ხოლო თითო controller/service ამოწმებს როლს, უფლებასა და scope-ს. პირადი `is_draft` სტატია მხოლოდ ავტორს ეკუთვნის, ადმინისტრატორსაც კი პირდაპირი ID-ით არ ეხსნება. დანართი მიჰყვება მასზე მიმთითებელი კონტენტის ხილვადობას. ზუსტი უარის კოდები და გამონაკლისები endpoint-ების მატრიცაშია.

## 3. სისტემის არქიტექტურა და ნდობის საზღვრები

```text
Chrome / თანამშრომელი
    │ HTTPS, ერთი origin
    ▼
კომპანიის Ingress (TLS, DNS, trusted proxy policy)
    │ HTTP კლასტერის შიგნით
    ▼
portal-frontend: nginx 1.30 + Angular 22
    ├─ /                 → სტატიკური UI
    ├─ /api/             → portal-backend:8080
    └─ /uploads/         → portal-backend:8080
                              ├─ Oracle: ყველა მდგრადი მონაცემი, BLOB, სესია, audit
                              └─ HTTPS → კომპანიის OAuth2 token endpoint (შესვლისას)
```

Backend არის Java 21 / Spring Boot 4.1.0; სქემას მართავს Flyway (მაქსიმალური მიგრაცია `V52`). ატვირთული ფაილები და დასრულებული ექსპორტები Oracle BLOB-ებია. Backend-ს მდგრადი filesystem საცავი ან Redis არ სჭირდება. Kubernetes-ში `/tmp` და nginx-ის სამუშაო დირექტორიები დროებითი `emptyDir`-ია; ეს backup-ის ობიექტები არ არის. წყაროები: [Compose](../docker-compose.local.yml), [backend Deployment](../k8s/30-backend-deployment.yaml), [frontend Deployment](../k8s/40-frontend-deployment.yaml), [Ingress](../k8s/50-ingress.yaml).

### მონაცემთა ძირითადი ნაკადები

1. **შესვლა:** ბრაუზერი credentials-ს TLS-ით აგზავნის API-ში; ჩართული კორპორაციული რეჟიმისას backend კომპანიას მიმართავს `ldap_auth` grant-ით. მიღებული კომპანიის token-ები არ ინახება. პორტალი საკუთარ JWT cookie-ს და Oracle-ში სესიის ჩანაწერს ქმნის. [CorporateAuthClient](../java-backend/src/main/java/ge/magti/portal/security/CorporateAuthClient.java), [CorporateLoginService](../java-backend/src/main/java/ge/magti/portal/security/CorporateLoginService.java).
2. **კონტენტის კითხვა და დადასტურება:** სტატია/სიახლე/ვიდეო მომხმარებლის აუდიტორიით იფილტრება; წაკითხვისა და ქვიზის მტკიცებულება Oracle-ში რჩება. პირველი წაკითხვის დადასტურების განმეორება მის პირველ დროს არ ცვლის — [მოქმედი კონტრაქტი](api-contract/read-acknowledgement-current.md).
3. **ფაილი:** `/uploads/{filename}` იმავე ავტორიზაციას და კონტენტზე წვდომის წესს იყენებს; URL-ის ცოდნა თავისთავად წვდომას არ იძლევა. [FileAccessPolicy](../java-backend/src/main/java/ge/magti/portal/storage/FileAccessPolicy.java).
4. **ექსპორტი:** სამუშაო და დასრულებული ფაილი Oracle-შია. Worker-ის დაკარგვისას ვადაგასული lease სამუშაოს `failed`-ად აქცევს; მომხმარებელი ახალ სამუშაოს იწყებს. ადმინისტრაციული ექსპორტის owner და მიმდინარე როლი სტატუსისა და ჩამოტვირთვისას ხელახლა მოწმდება — [PO-32/33](PRODUCT_OWNER_DECISIONS_KA.md).
5. **აუდიტი:** ბიზნეს-მუტაციები და უსაფრთხოების მოვლენები Oracle audit-შია; hash-ჯაჭვის შიდა შემოწმება სრული DB ადმინისტრატორისგან დამოუკიდებელ მტკიცებულებად ვერ გამოდგება, ვიდრე [გარე ასლი](QUESTIONS_FOR_IT.md) არ ამოქმედდება.

## 4. იდენტობა, სესია და უფლებები

- ოთხი პორტალის როლი არის `operator`, `manager`, `content_admin`, `admin`. კორპორაციული პასუხის `authorities` ამ როლებზე `OAUTH_ROLE_MAP`-ით გადაიყვანება. **დაუმაპავი პასუხი შესვლას უარყოფს**; უკვე არსებული მომხმარებლისთვის ის პორტალის token version-ს ზრდის და გაცემულ სესიებს აუქმებს. კორპორაციული როლი ყოველ **შესვლაზე** განახლდება. [DirectoryRoleMapper](../java-backend/src/main/java/ge/magti/portal/security/DirectoryRoleMapper.java), [PO-31](PRODUCT_OWNER_DECISIONS_KA.md).
- **დეპარტამენტი და სახელი.** ადაპტერი მათ კორპორაციული პასუხიდან იღებს, თუ ის მათ აგზავნის (`OAUTH_DEPARTMENT_CLAIM`, ნაგულისხმევად `department`; `OAUTH_NAME_CLAIM` — `full_name`). IT-ის სიტყვით (2026-09-29) დეპარტამენტი პასუხში დაემატება. მნიშვნელობა ტექსტურად უნდა ემთხვეოდეს პორტალისას — `ტექნიკური`, `საინფორმაციო`, `ოფისი`, ჯგუფი `ტექნიკური — ჯგუფი 03` ფორმით — რადგან კონტენტის ხილვადობა ამ ტექსტზე დგას; სხვა ფორმით მოსულ მნიშვნელობას ცალკე შესაბამისობის ცხრილი დასჭირდება. სანამ დეპარტამენტი არ მოდის, ადმინისტრატორი მას ხელით ანიჭებს, მანამდე კი თანამშრომელი მხოლოდ `All`-ის კონტენტს ხედავს ([PO-23](PRODUCT_OWNER_DECISIONS_KA.md)).
- ყოველი API მოთხოვნა პორტალის მომხმარებლის მიმდინარე როლს, აქტიურობასა და token version-ს Oracle-დან ხელახლა კითხულობს. ეს **არ ნიშნავს** კომპანიის directory-ს ყოველ მოთხოვნაზე შემოწმებას: directory-ში შეცვლილი როლი პორტალში შემდეგ წარმატებულ შესვლაზე აისახება, ხოლო პორტალში ადმინისტრატორის deactivation დაუყოვნებლივ მოქმედებს. AD sync და 5–15 წუთიანი ასახვის გზა კვლავ [IT №2/11](QUESTIONS_FOR_IT.md)-ზეა დამოკიდებული. [JwtAuthenticationFilter](../java-backend/src/main/java/ge/magti/portal/security/JwtAuthenticationFilter.java).
- ბრაუზერის JWT არის `HttpOnly` cookie-ში; state-changing მოთხოვნებს XSRF cookie/header წყვილი იცავს. წარმოებაში `COOKIE_SECURE=true` და HTTPS აუცილებელია. სესიის მაქსიმუმია 8 საათი, უმოქმედობის ზღვარი — 30 წუთი ([კონფიგურაცია](../k8s/10-configmap.yaml)). `/api/auth/sso/start` დღეს 503-ს აბრუნებს; redirect SSO flow არ არის.
- შესვლის გვერდი ყველგან ერთია: ელფოსტა და პაროლი ([PO-41](PRODUCT_OWNER_DECISIONS_KA.md)). საცდელი ანგარიშების ნებისმიერი პაროლით შესვლა იმავე ფორმით მხოლოდ `APP_ENV=development` **და** `ALLOW_DEV_LOGIN=true` კომბინაციით მუშაობს. ლოკალური Compose მხოლოდ `127.0.0.1:8080`-ს აქვეყნებს. [ProductionSafetyGuard](../java-backend/src/main/java/ge/magti/portal/config/ProductionSafetyGuard.java) production-ში ამ bypass-სა და არასაიმედო cookie/secret-ს ბლოკავს.

## 5. გარემოები და გარე ინტეგრაციები

| გარემო | დანიშნულება და საზღვარი |
|---|---|
| ლოკალური Compose | `docker-compose.local.yml`: Oracle XE 21c, backend, frontend, ერთჯერადი seed; development login ჩართულია. ეს მხოლოდ ლეპტოპის ტესტია. |
| დემო/UAT | repo-დან ამოღებულია 2026-09-29-ს ([PO-42](PRODUCT_OWNER_DECISIONS_KA.md)); ინსტრუქციები და UAT-ის სცენარები [არქივშია](archive/demo-and-uat/). მიღების ტესტირება კომპანიის staging-ზე, რეალური ანგარიშებით ტარდება. |
| Staging | კომპანიის იზოლირებული Oracle, ქსელი და InfoPortal-ის რეალური ტესტი საჭიროა; რეპოზიტორიაში დასრულებული staging manifest/მიღება არ არის. |
| Production | `k8s/` არის შესავსები deployment template. Namespace, versioned image-ები, JDBC, proxy chain, ingress/TLS და Secret IT-ის კონფიგურაციაა; მათი სია [აქაა](../k8s/README_KA.md). |

საჭირო ქსელური გზა: კლიენტი → Ingress `443`; Ingress → frontend `8080`; frontend → backend `8080`; backend → Oracle (IT-ის JDBC პორტი) და `oauth.magticom.ge:443`; ასევე DNS. Backend-ის `/actuator/health/*` და `/actuator/prometheus` მხოლოდ შიდა ქსელისთვისაა. `TRUSTED_PROXIES`-ში უნდა იყოს backend-ის მიერ დანახული frontend peer და რეალური სანდო proxy hop-ები; მთელი pod ქსელის ნდობა IP-ის გაყალბების რისკს ქმნის. დეტალური proxy მიღების ცდა [Kubernetes-ის ცნობარშია](../k8s/README_KA.md#it-06--trusted_proxies).

Backend-ის გაშვებისას Flyway იმავე Oracle connection-ით ასრულებს სქემის მიგრაციებს; ამიტომ გარემოს DB ანგარიშის DDL უფლება და მისი კონტროლის პროცედურა DBA-მ წინასწარ უნდა დაამტკიცოს. უკვე გამოყენებული მიგრაცია არ შეიცვალოს — ცვლილება ახალ `V*` ფაილად დაიწეროს. [application.yml](../java-backend/src/main/resources/application.yml), [backend-ის სამუშაო წესები](../java-backend/AGENTS.md).

**საიდუმლოები:** `SECRET_KEY`, `ORACLE_DB_PASSWORD` და `OAUTH_SECRET` კომპანიის secret manager/Kubernetes Secret-ით მიეწოდება, git-ში არა. `OAUTH_SECRET` უნდა ეკუთვნოდეს სწორედ `InfoPortal` კლიენტს. არასაიდუმლო ცვლადები [ConfigMap-შია](../k8s/10-configmap.yaml). გამოცემამდე გადაამოწმეთ `CORPORATE_AUTH_ENABLED`, `OAUTH_SERVICE_URI`, `OAUTH_CLIENT_ID`, `OAUTH_USERNAME_FORMAT`, claim mapping და `OAUTH_ROLE_MAP` რეალურ IT პასუხთან. [№13-ის გაზომვა](QUESTIONS_FOR_IT.md) სხვა კლიენტით იყო და InfoPortal-ის საბოლოო ქცევას არ ამტკიცებს.

## 6. მონაცემები, აღდგენა და შენახვის პოლიტიკა

Oracle შეიცავს მომხმარებლის ვინაობასა და ორგანიზაციულ კუთვნილებას, კონტენტს, ატვირთულ ფაილებს, სესიებს, წაკითხვის/ქვიზის მტკიცებულებას, ექსპორტის სამუშაოებს და აუდიტს. ამიტომ DB-ის backup/restore უნდა ფარავდეს **მთელ შესაბამის სქემას და BLOB-ებს**, ერთ თანმიმდევრულ წერტილში. მხოლოდ Kubernetes image-ის ან მანიფესტის rollback მონაცემებს უკან არ აბრუნებს; Flyway-ს უკუ-მიგრაცია ავტომატურად არ აქვს. [მიგრაციების დირექტორია](../java-backend/src/main/resources/db/migration/), [აღდგენის საზღვრები](IT_OPERATIONS_RUNBOOK_KA.md#backup-restore-და-rollback-ის-საზღვრები).

| მონაცემი | მოქმედი/დაგეგმილი წესი | მიღების საზღვარი |
|---|---|---|
| წაშლილი სტატია, ვიდეო და მათი დანართი | მფლობელის გადაწყვეტილებით 30 დღე აღდგენადი; legal hold წაშლას კეტავს | DPO/Legal-ის უფლებამოსილი პირები და purge-ის უსაფრთხოება staging-ზე დასადასტურებელია |
| გენერირებული ექსპორტი | დასრულებული ფაილი და job ჩამოსატვირთად 1 საათი რჩება | სამუშაოს დასრულება, ვადის გასვლა და owner/role ხელახალი შემოწმება staging-ზე |
| ბიზნეს audit | სამიზნეა 1 წელი აქტიურ Oracle-ში, შემდეგ დაცული გარე არქივი | არქივის პროცესი და საერთო retention ვადა ჯერ IT/Security/DPO/Legal-ს დასამტკიცებელი აქვს |
| წაკითხვა და ქვიზი | compliance evidence დამოუკიდებელი შენახვის პოლიტიკის საგანია | ზოგადი retention purge-ში არ მოხვდეს |

**საწყისი მონაცემები.** Production ცარიელი სქემით იწყება: Flyway ცხრილებს და სამ დეპარტამენტს ქმნის, მომხმარებლები კი პირველ შესვლაზე ჩნდებიან. ძველი პორტალის 122 რეალური სტატია (11 კატეგორია, 429 სურათი) ერთხელ შემოდის `scripts/import_legacy_content.py`-ით, **გამოქვეყნებულად** ([PO-43](PRODUCT_OWNER_DECISIONS_KA.md)). რიგი: backend-მა მიგრაციები დაასრულა → კორპორაციული შესვლა მუშაობს და კონტენტ-ადმინი ერთხელ შევიდა → იმპორტი იმ მანქანიდან, საიდანაც production-ის Oracle მიიწვდომება; წყაროა `magti_portal.db` და `uploads/`, რომლებსაც პროექტის მფლობელი გადმოსცემს (git-ში არ არის). [ნაბიჯები](LEGACY_CONTENT_IMPORT_KA.md).

ეს წესები და დაუხურავი ტექნიკური/იურიდიული ნაწილები აღწერილია [PO-10-ში](PRODUCT_OWNER_DECISIONS_KA.md) და [მიღების მატრიცაში](ENTERPRISE_READINESS_ACCEPTANCE_MATRIX_KA.md).

მფლობელის სამიზნე `RPO=0` და `RTO=1 საათი` IT/DBA-ს მიერ ჯერ **არ არის დადასტურებული**. აუცილებელია backup-ის გრაფიკისა და დაცვის, restore-ის იზოლირებულ გარემოში დასრულების, failover-ის დროის და დაკარგული ტრანზაქციების გაზომვის ოქმი. აუდიტის ჯაჭვის გარე საკონტროლო ასლი Oracle-ის იმავე სქემისგან დამოუკიდებლად უნდა ინახებოდეს. Retention-ის საბოლოო ვადები Security/DPO/Legal-ის დასამტკიცებელია; წაკითხვის ქვითრები, ქვიზის ცდები და სხვა compliance evidence ზოგადი purge-ით არ იშლება. [PO-10](PRODUCT_OWNER_DECISIONS_KA.md), [IT №14](QUESTIONS_FOR_IT.md).

## 7. ოპერირება და ცვლილების მართვა

| სიგნალი | მნიშვნელობა | ოპერატორის პირველი წყარო |
|---|---|---|
| `GET /api/health` | Oracle-ზე რეალური `SELECT 1`; კავშირი იკარგება → HTTP 503 | [HealthController](../java-backend/src/main/java/ge/magti/portal/web/HealthController.java) |
| `/actuator/health/liveness` | JVM-ის სიცოცხლე, DB-ის გარეშე | [backend Deployment](../k8s/30-backend-deployment.yaml) |
| `/actuator/health/readiness` | JVM + DB; DB-ის ჩავარდნისას pod ტრეფიკიდან გამოდის | [application.yml](../java-backend/src/main/resources/application.yml) |
| `/actuator/prometheus` | HTTP/JVM/Hikari მეტრიკები, მხოლოდ შიდა scrape | [Kubernetes-ის მონიტორინგი](../k8s/README_KA.md#მონიტორინგი) |
| `GET /api/audit-logs/chain-health` | SYSTEM_ADMIN-ის შიდა ჯაჭვის ვერდიქტი; HTTP 200 ჯერ კიდევ არ ნიშნავს `status=ok`-ს | [წვდომის მატრიცა](ACCESS_CONTRACT_MATRIX_KA.md) |

Container log-ები stdout/stderr-შია. ცენტრალური შეგროვება, alert threshold-ები, მორიგე და SIEM-ის retention ჯერ კომპანიის დასადგენია [IT №10](QUESTIONS_FOR_IT.md). ინციდენტისას შეინახეთ დრო, გარემო, HTTP კოდი და `correlation_id`; არ გააზიაროთ პაროლი, cookie, token, Authorization header ან მთლიანი პერსონალური ლოგი. დეტალური უსაფრთხო ბრძანებები და სიმპტომების ცხრილი [საოპერაციო სახელმძღვანელოშია](IT_OPERATIONS_RUNBOOK_KA.md).

ყოველი release-ისთვის ჩაიწეროს commit SHA, ორი image digest, Flyway-ის მიმდინარე/სამიზნე ვერსია, კონფიგურაციის ცვლილება, CI run ID, staging-ის შედეგი, backup checkpoint, rollback-ის შესაძლო საზღვარი, დამმტკიცებელი და რეალური rollout დრო. არსებულ ბაზაზე `V49`-მდე audit hash-ის დუბლიკატები წაკითხვით უნდა შემოწმდეს; `V50`-ზე ძველი და ახალი export worker ერთდროულად არ იმუშავოს. SQL და ნაბიჯები [Kubernetes-ის გაშვების ნაწილშია](../k8s/README_KA.md#გაშვება). CI-ის სწრაფი ლოკალური ბრძანებაა `scripts/verify-like-ci.sh fast`; Oracle ტესტები და Playwright ცალკე მტკიცებულებას მოითხოვს. [CI workflow](../.github/workflows/ci.yml).

## 8. მიღების კრიტერიუმები და პასუხისმგებლობა

ქვემოთ `ღია` ნიშნავს, რომ ამ დოკუმენტს შესაბამისი **კომპანიის გარემოს მტკიცებულება არ ახლავს**; ეს არ არის კოდის ჩავარდნის მტკიცება. თითო პუნქტის დახურვისას ჩაიწეროს გარემო, release/image digest, შესრულების თარიღი, შემსრულებელი, შედეგი და მტკიცებულების ბმული. ცარიელი ხელმოწერა ან მხოლოდ „შევამოწმეთ“ საკმარისი არ არის.

| Gate | მისაღები ცდა / არტეფაქტი | პასუხისმგებელი | სტატუსი |
|---|---|---|---|
| IAM | InfoPortal credential-ით წარმატებული შესვლა; ცუდი პაროლი/დაუმაპავი როლი/გათიშული ანგარიში/სერვისის 503; მინიმუმ ერთი მოქმედი admin; role claim-ის რეალური სახელი | IAM + უსაფრთხოება | ღია — IT №13 |
| ქსელი/TLS | HTTPS ერთი origin-ით; backend/Actuator გარე ქსელიდან მიუწვდომელია; OAuth egress; ორი რეალური კლიენტის IP და spoofed header-ის უარყოფა | Platform + ქსელი | ღია — IT №4/7/9 |
| მონაცემთა ბაზა | V1…V52 სუფთა გარემოზე; არსებული სქემის წინასწარი შემოწმება; Flyway startup/rollback rehearsal და DB capacity/BLOB შეფასება | DBA + backend | ღია — IT №5/6 |
| აღდგენა | იზოლირებული restore/failover, კონტენტის/ფაილის/აუდიტის შემოწმება, რეალურად გაზომილი RPO/RTO | DBA + Platform | ღია — PO-09 |
| აუდიტი/SIEM | გარე hash anchor, მისი შეცვლის აკრძალვა DB მომხმარებლისთვის, tamper ცდა და alert-ის მიმღები; ლოგების redaction/retention | უსაფრთხოება + DBA | ღია — IT №10/14 |
| პროდუქტის მიღება | ოთხივე როლის UAT, დეპარტამენტებს შორის უარის ცდები, export/ფაილი/წაკითხვა, Chrome 1080p მუქ/ღია თემებში | QA + ბიზნეს-მფლობელი | ღია — სცენარების ნიმუში: [UAT პაკეტი (არქივი)](archive/demo-and-uat/uat/UAT_00_RUNBOOK_KA.md) |
| release | CI-ის სრული შედეგი, versioned image digest-ები, staging rollout/rollback და მორიგეობის გეგმა | Release manager + Platform | ღია — [მიღების მატრიცა](ENTERPRISE_READINESS_ACCEPTANCE_MATRIX_KA.md) |

## 9. IT-სთან შეთანხმების მიმდინარე სია

ერთი კითხვის რამდენიმე გუნდთან გაგზავნის ნაცვლად გამოიყენეთ [ოფიციალური კითხვების რეესტრი](QUESTIONS_FOR_IT.md): IAM — №13; Kubernetes/CI და ქსელი — №3/4/7/9; DBA — №5/6; SOC/Security — №10/14; ორგანიზაციის სინქრონიზაცია — №2/11; SMS-ის შესაძლო მომავალი ინტეგრაცია — №12. `k8s/`-ის 13 შესავსები და 3 გადასახედი მნიშვნელობა ჩამოთვლილია [IT-NN ცხრილში](../k8s/README_KA.md#ა-13-ველი-რომელიც-უნდა-შეავსოთ). პასუხის მიღების შემდეგ განახლდეს შესაბამისი კონფიგურაცია, staging-ის ტესტი და ეს სტატუსის ცხრილი; საიდუმლო მნიშვნელობები დოკუმენტში არ ჩაიწეროს.

**ჩაბარების პაკეტის წაკითხვის რიგი:** ეს დოკუმენტი → [IT-ის სწრაფი შესასვლელი](IT_START_HERE_KA.md) → [საოპერაციო სახელმძღვანელო](IT_OPERATIONS_RUNBOOK_KA.md) → [Kubernetes-ის ცნობარი](../k8s/README_KA.md) → [წვდომის მატრიცა](ACCESS_CONTRACT_MATRIX_KA.md) → [UAT მიღების ოქმის შაბლონი (არქივი)](archive/demo-and-uat/uat/UAT_SIGNOFF_KA.md). საკითხის გადაწყვეტა და ფაქტობრივი მიღება ცალ-ცალკე ჩაიწეროს.
