# Magti Portal — საოპერაციო სახელმძღვანელო

**მოქმედი აღწერა: 2026-09-23.** პირველი წასაკითხია [IT-ის შესასვლელი](IT_START_HERE_KA.md). ეს დოკუმენტი ეყრდნობა მოქმედ Compose-ს, CI-ს, Java/Angular კოდს და `k8s/` მანიფესტებს. [დათარიღებული აუდიტები](README.md#archive--ისტორია) მტკიცებულებაა თავის დროზე და არა მიმდინარე მდგომარეობის წყარო. ბრძანებები ქვემოთ Windows PowerShell-ისთვისაა, პროექტის ძირიდან `C:\Projects\Magti base`.

## არქიტექტურა და კონფიგურაცია

```text
Chrome (ქართული ინტერფეისი, 1080p desktop)
  → nginx: Angular-ის სტატიკური ფაილები და /api, /uploads proxy
  → Java 21 / Spring Boot 4.1 API
  → Oracle: მომხმარებლები, კონტენტი, BLOB ფაილები/ექსპორტები, სესიები, აუდიტი
                 ↘ Flyway V1…V52 იწყება backend-ის სტარტზე
  Java → კომპანიის OAuth2 token endpoint (ldap_auth), მხოლოდ corporate login-ზე
```

nginx უსმენს `8080`-ს და მხოლოდ `/api/`-სა და `/uploads/`-ს გადასცემს backend-ს. Backend ასევე უსმენს შიდა `8080`-ს; Oracle ლოკალურ Compose ქსელშია `1521`-ზე და ჰოსტზე არ ქვეყნდება. მხოლოდ frontend ქვეყნდება `127.0.0.1:8080`-ზე. ატვირთული ფაილები და დასრულებული ექსპორტები Oracle BLOB-ებია; backend დისკზე მათ არ ინახავს. აუდიტის ჩანაწერები და ჯაჭვის ბოლო hash იმავე Oracle სქემაშია, ამიტომ დამოუკიდებელი საკონტროლო ასლი ჯერ [IT/უსაფრთხოების გადასაწყვეტია](QUESTIONS_FOR_IT.md#14-აუდიტის-ჯაჭვის-გარე-საკონტროლო-ასლი--სტატუსი--პასუხს-ველოდებით-2026-09-23). `/api/health` ამოწმებს DB კავშირს და HTTP 503-ს აბრუნებს, თუ Oracle მიუწვდომელია; JVM liveness-ისგან ეს განსხვავდება. `/api/audit-logs/chain-health` მხოლოდ SYSTEM_ADMIN-ისთვისაა და `status=ok/tampered`-ს აბრუნებს; 200 სტატუსი თავისთავად ჯაჭვის სისწორეს არ ნიშნავს.

| გარემო | პარამეტრების წყარო | დამოკიდებულებები და გამოყენება |
|---|---|---|
| ლოკალური Compose | [`docker-compose.local.yml`](../docker-compose.local.yml), development defaults [`application.yml`](../java-backend/src/main/resources/application.yml) | Docker Desktop/WSL 2, Docker-ისთვის ≥6 GB RAM, თავისუფალი დისკი; Oracle XE 21c image, backend და frontend image-ები ადგილობრივად შენდება; seed ერთჯერადია. `APP_ENV=development` **და** `ALLOW_DEV_LOGIN=true` რთავს საცდელი ანგარიშების უპაროლო გზას. მხოლოდ localhost-ზე. სახელდებული `oracle-data` volume ინახავს DB-ს `stop`/`start` და ჩვეულებრივი `down`-ის შემდეგ. |
| Staging | ამ რეპოზიტორიაში ცალკე staging manifest არ არის | IT/DevOps-მა უნდა გამოყოს კომპანიის ქსელში იზოლირებული გარემო, Oracle-ის აღდგენადი ასლი და ტესტური InfoPortal კონფიგურაცია; production rollout-მდე აქ უნდა შემოწმდეს TLS, proxy, ავტორიზაცია, მიგრაცია, backup/restore და rollback. UAT/demo ლოკალური სტეკები staging არ არის. |
| Production | [`k8s/10-configmap.yaml`](../k8s/10-configmap.yaml), გარე Kubernetes Secret, image-ები და ingress; [`k8s/README_KA.md`](../k8s/README_KA.md) | კომპანიის Oracle, HTTPS, DNS, outbound OAuth2, registry, მონიტორინგი. `APP_ENV=production`, `ALLOW_DEV_LOGIN=false`, `COOKIE_SECURE=true`; კოდი development login-ს production-ში ბლოკავს. `<<< IT-NN >>>` ველები და ორგანიზაციის მიღება ჯერ ღიაა. |

Backend image იყენებს Java 21-ს; frontend build — Node 22.22.3-ს, Angular 22-ს და runtime nginx 1.30.5-ს. ლოკალური და CI Oracle image არის XE 21c; [IT-ის №5 კითხვაში](QUESTIONS_FOR_IT.md) კომპანიის არსებული Oracle 19c არის აღწერილი, ხოლო მისი შემდგომი ვერსიის გეგმა IT-ს დასადასტურებელია. ვერსიების წყარო არის შესაბამისი [`Dockerfile`](../java-backend/Dockerfile)-ები, [`package.json`](../angular-frontend/package.json) და Compose/CI. ლოკალური სრული სტეკი Docker-ით შენდება; ჰოსტზე JDK/Node მხოლოდ დეველოპერის ალტერნატიულ ციკლს სჭირდება. პირველი `up --build` შეიძლება 10–15 წუთი გაგრძელდეს; Oracle-ის health grace 90 წამია, backend-ის — 180 წამი. თუ ვადა გავიდა, ლოგი შეამოწმეთ, მონაცემები არ წაშალოთ.

**IT-ის მისაღები კონფიგურაცია:** Oracle JDBC მისამართი, schema user და პაროლი; namespace და ორი versioned image მისამართი; ingress class, DNS და TLS Secret; frontend peer-ისა და სანდო proxy hop-ების რეალური IP/CIDR; InfoPortal-ის საკუთარი client credential, ოთხი ზუსტი role name, მინიმუმ ერთი admin და egress მისამართი/ნებართვა; გრძელი შემთხვევითი session signing key. `OAUTH_SERVICE_URI`, `OAUTH_CLIENT_ID`, `OAUTH_ROLE_MAP`, `OAUTH_USERNAME_FORMAT` და claims-ის mapping შეადარეთ [№13 პასუხებს](QUESTIONS_FOR_IT.md). ყველა `IT-NN` ადგილი აღწერილია [Kubernetes-ის ცნობარში](../k8s/README_KA.md). საიდუმლო მნიშვნელობები შეინახეთ კომპანიის secret manager-ში; არც ამ დოკუმენტში, არც ticket/log-ში ჩასვათ. კონფიგურაციის სრული `docker compose config` შეიძლება საიდუმლოებს აჩვენებდეს — სინტაქსისთვის გამოიყენეთ მხოლოდ `config --quiet`.

## უსაფრთხო დიაგნოსტიკური ბრძანებები Windows PowerShell

ყველა ბრძანება წაკითხვითია. ლოგში შეიძლება იყოს მომხმარებლის მონაცემი ან შეცდომის ტექნიკური დეტალი; გასაზიარებლად ამოიღეთ მხოლოდ საჭირო დროის მონაკვეთი და დაფარეთ ასეთი ველები.

| ბრძანება | მოსალოდნელი შედეგი | განსხვავებული შედეგის მნიშვნელობა |
|---|---|---|
| `docker version --format '{{.Server.Version}}'` | ვერსიის ნომერი | Engine არ მუშაობს/WSL 2 შეჩერებულია ან Docker Desktop არ არის დაყენებული. |
| `docker compose -f docker-compose.local.yml config --quiet` | არაფერს ბეჭდავს, exit code 0 | Compose ფაილის სინტაქსი/მნიშვნელობა არასწორია; სრული config არ დაბეჭდოთ, რათა მნიშვნელობები არ გავრცელდეს. |
| `docker compose -f docker-compose.local.yml ps --all` | `oracle` და `backend` — `healthy`, `frontend` — `Up`, `seed` — `Exited (0)` | `unhealthy`/`Exited` სერვისის ჩავარდნაა; `seed`-ის 0 ნორმალურია; ცარიელი სია ნიშნავს, რომ ეს stack გაშვებული არ არის. |
| `docker compose -f docker-compose.local.yml logs --tail 100 oracle` | ბოლო Oracle startup/ready შეტყობინებები | განმეორებადი restart, storage ან memory error — DBA/ლოკალური IT; არ გაუშვათ destructive init. |
| `docker compose -f docker-compose.local.yml logs --tail 100 backend` | startup, Flyway და `Startup security config` ჩანს, სერვისი მზადაა | Flyway/DB/auth/config შეცდომა აქ იძებნება. Development-ში `dev-login=enabled` მოსალოდნელია; production-ში ეს ავარიული გაჩერების მიზეზია. |
| `docker compose -f docker-compose.local.yml logs --tail 100 frontend` | nginx იწყებს და მოთხოვნებს ემსახურება | nginx კონფიგურაცია, upstream ან binding შეიძლება გაფუჭებული იყოს. |
| `docker compose -f docker-compose.local.yml logs --tail 100 seed` | წარმატებული ერთჯერადი თესვა; `Exited (0)` | არანულოვანი კოდი კონტენტის თესვის შეცდომაა; API/ბაზა ცალკე შეამოწმეთ. |
| `curl.exe -sS http://localhost:8080/api/health` და `curl.exe -sS -o NUL -w 'HTTP %{http_code}' http://localhost:8080/api/health` | `"status":"ok"`, `"database":"ok"` და HTTP 200 | HTTP 503 ნიშნავს DB readiness-ის ჩავარდნას; connection refused — frontend/პორტი; 502/504 — nginx→backend. `redis=not_configured` მოსალოდნელია. `-i` არ გამოიყენოთ საერთო ჩანაწერში: header-მა შეიძლება cookie დაბეჭდოს. |
| `Get-PSDrive -Name C \| Select-Object Name,Free,Used` | `Free` საკმარისია image-ებისა და DB-ს ზრდისთვის | მცირე/კლებადი ადგილი Oracle-ს და build-ს უშლის; Docker-ის storage შესაძლოა სხვა დისკზეც იყოს — შეამოწმეთ Docker Desktop-ის პარამეტრები. |
| `Get-CimInstance Win32_OperatingSystem \| Select-Object TotalVisibleMemorySize,FreePhysicalMemory` | Windows-ს აქვს თავისუფალი RAM; მნიშვნელობები KB-ებშია | მცირე RAM ჰოსტის წნევას აჩვენებს; Docker-ის ცალკე ლიმიტიც შეამოწმეთ. |
| `[math]::Round(([int64](docker info --format '{{.MemTotal}}') / 1GB),1)` | Docker-ისთვის მინიმუმ 6 GB | ნაკლები გამოყოფა Oracle-ის ნელ/წარუმატებელ გაშვებას ხსნის. |
| `Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue \| Select-Object LocalAddress,OwningProcess` | გაშვებულ stack-ზე `127.0.0.1:8080` ჩანს | ცარიელი — frontend არ უსმენს; სხვა მისამართი/პროცესი — პორტის კონფლიქტი ან არასწორი გამოქვეყნება. პროცესის დასადგენად `Get-Process -Id <OwningProcess>` გამოიყენეთ. |

Production health/ლოგის გზები მოცემულია [Kubernetes-ის ცნობარში](../k8s/README_KA.md#შემოწმება-რომ-მართლა-მუშაობს). `/actuator/prometheus` შიდა მონიტორინგისთვისაა და ინტერნეტზე არ უნდა გამოქვეყნდეს. CI-ის სწრაფი დადასტურება არის `scripts/verify-like-ci.sh fast`; Oracle suite მხოლოდ PR/main-ზეა CI-ში, ხოლო Playwright-ისთვის გაშვებული stack საჭიროა. ეს ტესტები რეალურ Magti ქსელს, backup-სა და TLS-ს არ ამოწმებს.

## პრობლემების მოძიება

ცხრილის ყველა მოქმედება მონაცემებს ტოვებს ადგილზე. „გადაცემა“ ნიშნავს: დრო, გარემო, სიმპტომი, მხოლოდ გაფილტრული ლოგი, HTTP სტატუსი და correlation ID (თუ არსებობს) მიაწოდეთ პასუხისმგებელს; credential და პერსონალური მონაცემი არ გააზიაროთ.

**„დეველოპერი“ ამ დოკუმენტში** ნიშნავს Magti IT-ის მიერ დანიშნულ ტექნიკურ მფლობელს. ის ამტკიცებს კოდის ცვლილებას და ინციდენტისას იღებს გადაწყვეტილებას. **2026-10-03-ის მდგომარეობით ასეთი ადამიანი დანიშნული არ არის** ([IT კითხვა №16](QUESTIONS_FOR_IT.md)). კოდი თითქმის მთლიანად AI-მ დაწერა. AI ხელსაწყო ამ როლს ვერ შეასრულებს: მას შეუძლია ცვლილების მომზადება, მაგრამ პასუხს ვერ აგებს. production-ში გაშვებამდე ამ ადგილას კონკრეტული სახელი და შემცვლელი ჩაიწეროს.

| სიმპტომი | შემოწმება | სავარაუდო მიზეზი | უსაფრთხო მოქმედება | წარმატების დადასტურება | თუ ვერ მოგვარდა |
|---|---|---|---|---|---|
| Docker Desktop/WSL არ იწყება | `docker version`, Docker Desktop-ის status | WSL 2/virtualization ან engine გაჩერებულია | გახსენით Docker Desktop, დაელოდეთ engine-ს; საჭიროებისას IT-თან გადაამოწმეთ WSL/virtualization | ვერსია ჩანს, `ps --all` პასუხობს | ლეპტოპის IT |
| Oracle დიდხანს `starting`/`unhealthy` | `ps --all`, `logs oracle`, Docker RAM/დისკი | პირველი init, RAM <6 GB, ადგილი ან დაზიანებული DB | 10–15 წუთი დაელოდეთ; რესურსები შეამოწმეთ; არსებული volume არ შეცვალოთ | Oracle `healthy`, health-ში DB `ok` | DBA და ლეპტოპის IT |
| Backend Flyway-ზე ჩერდება | `logs backend`, მიგრაციის ნომერი | Oracle მიუწვდომელია, V49-ზე დუბლიკატი hash ან სხვა schema conflict | არ გადაატაროთ ხელით SQL და არ შეცვალოთ `flyway_schema_history`; შეინახეთ log, DBA-მ წაკითხვით შეამოწმოს [V49 წინაპირობა](../k8s/README_KA.md#გაშვება) | backend `healthy`, schema ვერსია მოსალოდნელია | DBA + დეველოპერი |
| Backend `Exited`/`unhealthy` | `logs backend`, health-ის ზემოთ მოცემული ბრძანებები | კონფიგურაცია, Oracle, უსაფრთხოების guard, JVM | მიზეზის მიხედვით გაასწორეთ მხოლოდ დადასტურებული კონფიგურაცია; უცნობი secret არ გამოცვალოთ თვითნებურად | health HTTP 200, backend `healthy` | დეველოპერი, DBA/DevOps |
| გვერდი არ იხსნება ან API 502/504 | `ps --all`, `logs frontend`, health | nginx ვერ იწყება ან backend upstream არ არის მზად | დაელოდეთ backend-ს; შეამოწმეთ სერვისის სახელი/პორტი კონფიგურაციაში | Chrome გვერდი იხსნება, health 200 | დეველოპერი/DevOps |
| 8080 დაკავებულია | `Get-NetTCPConnection ...8080`; `Get-Process -Id <id>` | სხვა პროცესი უსმენს | დაადგინეთ მფლობელი; IT-თან შეთანხმებით გაათავისუფლეთ პორტი ან მხოლოდ ლოკალური mapping შეცვალეთ | frontend `Up`, `127.0.0.1:8080`, health 200 | ლეპტოპის IT |
| ლოკალური შესვლა ვერ ხდება | `logs backend`-ში startup mode; `ps --all` | ორივე dev flag ერთად არ არის, ანგარიში სხვაა, rate limit | შეამოწმეთ საცდელი email და `APP_ENV=development` + `ALLOW_DEV_LOGIN=true`; რეალური პაროლი არ გამოიყენოთ | საცდელი ანგარიში შედის | დეველოპერი |
| Production შესვლა/როლი არასწორია | backend-ის უსაფრთხო log, IT-13, `OAUTH_ROLE_MAP` | InfoPortal-ის credential/როლი/claim არ ემთხვევა, account deactivated ან OAuth unavailable | IT-მ გადაამოწმოს საკუთარი client და role names; პორტალში თვითნებური role override არ გააკეთოთ | staging-ზე დადებითი და უარყოფითი როლების ცდა გადის | IAM/IT + უსაფრთხოების გუნდი |
| კომპანიის ქსელში TLS/proxy პრობლემა | ingress/backend status, TLS სერტიფიკატი, trusted hop-ების სია | DNS, egress 443, TLS Secret ან `TRUSTED_PROXIES` | DevOps-მა ქსელის გზა და header-ები გადაამოწმოს; არ ენდოთ მთელ pod ქსელს | HTTPS შესვლა და ორი კლიენტის განსხვავებული აუდიტის IP | DevOps/ქსელი/უსაფრთხოება |
| ატვირთვა 413/403/ჩავარდნა | nginx/backend ლოგი და HTTP კოდი; ფაილის ზომა | nginx/Spring 11 MB ზღვარი, ტიპი/უფლება, Oracle BLOB სივრცე | შეამოწმეთ ნებადართული ზომა/როლი და DB სივრცე; არ გააუქმოთ წვდომის კონტროლი | ნებადართული საცდელი ფაილი იტვირთება და უფლებამოსილს ეხსნება | დეველოპერი/DBA |
| ექსპორტი გაჭედილია/failed | backend log, job status, Oracle მზადყოფნა | worker-ის შეცდომა/lease, DB კავშირი; pod დაიკარგა | მტკიცებულება შეინახეთ; `failed` სამუშაოს შემდეგ მომხმარებელმა ახალი ექსპორტი დაიწყოს; V50 rollout-ისას ძველი/ახალი worker არ აურიოთ | ახალი ექსპორტი სრულდება და ჩამოტვირთვა მუშაობს | დეველოპერი/DBA/DevOps |
| აუდიტში ხარვეზი/`tampered` | admin-ის chain-health, backend log, ბოლო ცნობილ გარე ასლთან შედარება | ჩანაწერის/ბმის დაზიანება ან სრულყოფილი გარე ასლის არქონა | შეაჩერეთ რისკიანი ცვლილებები, შეინახეთ ლოგი და DB snapshot; hash-ები არ გადაითვალოთ და ისტორია არ წაშალოთ | DBA/უსაფრთხოების გამოძიებით ჯაჭვი/გარე ასლი შეედარა | უსაფრთხოების გუნდი + DBA |
| ჩაწერა 5 წამზე მეტხანს გრძელდება ([alert](../k8s/README_KA.md#alert-ნელი-ჩაწერა); A06) | nginx-ის სტრიქონიდან აიღეთ `request_id`. იმავე ID-ით იპოვეთ backend-ის `RequestTimingFilter`-ის სტრიქონი (`duration_ms`). Prometheus-ში შეხედეთ Hikari-ის `pending`-ს | backend-ის `duration_ms` ≥ 5000: row lock-ის ლოდინი ბაზაში, ან კავშირების პული ამოწურულია. backend სწრაფია, nginx კი ნელი: ქსელი ან proxy | გადაეცით ID, დრო, გზა და სტატუსი. DBA-მ წაკითხვით შეამოწმოს `v$session`-ში `enq: TX - row lock contention`. მტკიცებულების შეგროვებამდე pod-ი არ გადატვირთოთ | იგივე მოქმედება 1 წამზე სწრაფად სრულდება; alert ჩაქრა | დეველოპერი + DBA |

## ინციდენტის პირველი ნაბიჯები

1. დააფიქსირეთ დაწყების დრო (თბილისის დრო და UTC), გარემო, მომხმარებელზე გავლენა და ბოლო ცვლილება. Production-ში დაუკავშირდით მორიგე DevOps/ინციდენტის არხს; ლეპტოპზე — პროექტის დეველოპერს/ადგილობრივ IT-ს.
2. წაკითხვით შეამოწმეთ `ps --all`, `/api/health` და შესაბამისი სერვისის ბოლო ლოგი. HTTP 500-ის UI/API პასუხში `correlation_id` რვა სიმბოლოა; `GlobalExceptionHandler` იმავე ID-ს backend log-ში stack trace-თან ბეჭდავს. შეაგროვეთ ID, დრო, გზა და სტატუსი. არ გადმოიტანოთ request body, cookie, Authorization header, password ან მთელი პერსონალური ლოგი.
3. მონაცემების დაკარგვის, აუდიტის დარღვევის ან არასასურველი მიგრაციის ეჭვზე **არ გაუშვათ** `down -v`, volume prune, SQL delete/repair, Flyway repair, ხელახალი seed ან დაუგეგმავი restore. შეინარჩუნეთ მიმდინარე მდგომარეობა და DBA/უსაფრთხოების გუნდს გადაეცით; snapshot-ის გადაღება კომპანიის წესით გადაწყდეს.
4. დაადასტურეთ გამოსწორება იმავე health გზით და დაზიანებული ბიზნეს-სცენარის გამეორებით; ჩაწერეთ დრო, მოქმედება და შედეგი. თუ პრობლემა განმეორდა, ticket-ში დაურთეთ მხოლოდ გაფილტრული მტკიცებულება.

## Backup, restore და rollback-ის საზღვრები

**შემოწმებული ლოკალური ქცევა:** ამ ლეპტოპზე `stop`/`start` შესრულდა და ხუთი სტატია დარჩა (დეტალი ქვემოთ, „გაშვების მდგომარეობაში“). Compose-ის ჩვეულებრივი `down` კონფიგურაციის მიხედვით `oracle-data` volume-ს ტოვებს, მაგრამ ამ სესიაში `down` არ გაგვიშვია. არც ერთი ეს მოქმედება **backup არ არის**. ატვირთული BLOB-ები, დასრულებული ექსპორტები, სესიები და აუდიტი Oracle-შია; მათი აღდგენა მხოლოდ DB-ის თანმიმდევრული ასლით შეიძლება.

**ჯერ IT/DBA-ს გამოსაცდელი:** backup-ის გრაფიკი, BLOB-ების ჩართვა, დაცული შენახვა, restore იზოლირებულ staging-ში, RPO/RTO გაზომვა და audit-chain-ის გარე ასლთან შედარება. [IT კითხვა №6](QUESTIONS_FOR_IT.md) და [№14](QUESTIONS_FOR_IT.md) პასუხს ელოდება. დადასტურებული restore rehearsal-ის ოქმის გარეშე production-ზე „აღდგენადია“ არ ჩაიწეროს.

**Rollback:** აპლიკაციის image-ის უკან დაბრუნება სქემას უკან არ აბრუნებს. Flyway მიგრაციები ავტომატურად უკუღმა არ სრულდება; განსაკუთრებით V49-ის უნიკალური ინდექსი და V50-ის export worker lease საჭიროებს DBA/დეველოპერის წინასწარ გეგმას და staging ცდას. V50-ის დროს ძველი და ახალი export worker ერთად არ ამუშაოთ — [Kubernetes-ის rollout შენიშვნა](../k8s/README_KA.md#გაშვება). [`ROLLOUT_LEADERSHIP_SCOPE` და `ROLLOUT_COMPLIANCE_ELIGIBILITY`](ROLLOUT_ROLLBACK_KA.md) კოდში მოქმედ rollback-ს **არ** იძლევა; `ROLLOUT_FILE_ENTITLEMENT=false` shadow რეჟიმში ფაილებს გასცემს და უსაფრთხოების გადაწყვეტილების გარეშე არ გამოიყენოთ. Production rollback-ის პასუხისმგებელია DevOps + DBA + დეველოპერი, დამტკიცებული ცვლილების/აღდგენის გეგმით.

## გაშვების მდგომარეობა და მისაღები მტკიცებულება

| საკითხი | ამჟამინდელი სტატუსი | პასუხისმგებელი და დახურვის მტკიცებულება |
|---|---|---|
| ლეპტოპის Compose სინტაქსი და Docker რესურსი | 2026-09-23: `config --quiet` exit 0; Docker Server 29.7.2, გამოყოფილი RAM ~7.6 GB, C:-ზე ~29.8 GB თავისუფალი; 8080 გაშვებამდე თავისუფალი იყო | ადგილობრივი IT/დეველოპერი; ბრძანებების გამოტანა |
| ამ ლეპტოპზე სრული გაშვება და საცდელი სცენარი | **დადასტურდა 2026-09-23:** `docker compose ... up --build -d` exit 0; Oracle/backend/frontend `healthy`, seed `Exited (0)`; `/api/health` HTTP 200 და DB `ok`; `info@magti.ge` API შესვლა HTTP 200, 5 სტატია და ერთის დეტალი HTTP 200; ამ ლეპტოპის Chrome-ით (headless) ლოკალური ეკრანიდან შესვლა, მთავარი გვერდი, ორი კატეგორია და ორი სიახლე, „ცოდნის ბაზიდან“ სტატიის გახსნა; `stop`/`start`-ის შემდეგ health კვლავ `ok` და 5 სტატია დარჩა. ეს არ ამტკიცებს სრულ E2E-ს ან production-ს. | დეველოპერი/მფლობელი; იმავე ნაბიჯების განმეორება ახალ ლეპტოპზე და კონკრეტული შედეგების ჩანაწერი |
| კოდის ავტომატური შემოწმება | ამ სესიაში შერჩეული დოკუმენტური, proxy/secret, უსაფრთხოების guard და corporate-auth mapping-ის **70 ტესტი გავიდა** (Maven exit 0). რეალური კომპანიის OAuth/TLS ამით არ შემოწმებულა. CI-ში fast ტესტები branch push-ზეა; Oracle integration და E2E PR/main-ზეა. | დეველოპერი; შესაბამისი CI run ID/exit code, staging-ზე ინტეგრაციის ოქმი |
| Staging მიღება | არ არსებობს დასრულებული გარემოს მტკიცებულება | DevOps + IAM + DBA; HTTPS/ქსელი, რეალური InfoPortal დადებითი/უარყოფითი შესვლა, V49/V50, backup/restore და rollback rehearsal-ის ოქმი |
| Production წინაპირობები | ღიაა; ლეპტოპის ტესტი ამ კარს არ ხურავს | DevOps: `IT-NN` ველები, TLS/proxy/monitoring; IAM: InfoPortal credential/role map; DBA: Oracle backup/restore; უსაფრთხოება: audit external anchor. თითოეულზე წერილობითი პასუხი და staging მტკიცებულება |

ისტორიული readiness ანგარიში იხილეთ მხოლოდ [არქივში](archive/audits/DEPLOYMENT_READINESS_2026-09-22_KA.md); ცოცხალი ღია საკითხების წყარო არის [IT-ის კითხვები](QUESTIONS_FOR_IT.md) და [Kubernetes-ის ცნობარი](../k8s/README_KA.md).
