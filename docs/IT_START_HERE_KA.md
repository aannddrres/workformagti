# Magti Portal — IT თანამშრომელმა აქედან დაიწყოს

**მოქმედი ჩაბარება, განახლებულია 2026-09-29.** ეს გვერდი აჩვენებს პირველ ხუთ წუთში საჭირო სურათს; ქვემოთ მითითებული ლოკალური ცდის მტკიცებულება 2026-09-23-ისაა. IT-სთვის ერთიანი არქიტექტურა, უსაფრთხოების საზღვრები და მიღების კრიტერიუმები მოცემულია [ტექნიკურ ჩაბარებაში](IT_TECHNICAL_HANDOVER_KA.md). შესრულების დეტალები და ინციდენტის ნაბიჯები არის [საოპერაციო სახელმძღვანელოში](IT_OPERATIONS_RUNBOOK_KA.md). მფლობელის Windows ინსტრუქცია ცალკეა: [ლოკალური გაშვება](RUN_LOCALLY.md).

## 1. რა მუშაობს სად

| გარემო | დანიშნულება | მდგომარეობა |
|---|---|---|
| ლეპტოპი | Docker Compose, მხოლოდ `127.0.0.1:8080`, საცდელი ანგარიშები და Oracle XE-ის სახელდებული volume | ადგილობრივი ტესტი; მიმდინარე ცდის მტკიცებულება [ქვემოთ](IT_OPERATIONS_RUNBOOK_KA.md#გაშვების-მდგომარეობა-და-მისაღები-მტკიცებულება) |
| Staging | კომპანიის ქსელში ინტეგრაციისა და აღდგენის ცდები | ცალკე მოქმედი manifest/გარემო ამ რეპოზიტორიაში არ არის; IT/DevOps-მა უნდა გამოყოს და მიიღოს |
| Production | Kubernetes-ის `k8s/` მანიფესტები, კომპანიის Oracle და OAuth2 | **არ არის მიღებული ან გაშვებული**; შესავსები ველები, backup, ქსელი, TLS და ავტორიზაცია [Kubernetes-ის ცნობარში](../k8s/README_KA.md) |

## 2. კომპონენტები და მოთხოვნის გზა

`Chrome → nginx/Angular (:8080) → /api და /uploads → Java 21 / Spring Boot API (:8080) → Oracle`.
Java-ის გაშვებისას Flyway მართავს სქემის მიგრაციებს (მოქმედი მაქსიმუმი `V52`). ატვირთული ფაილები, დასრულებული ექსპორტები და აუდიტის ჩანაწერები Oracle-შია. Production შესვლისას Java API კომპანიის OAuth2 token endpoint-ს უკავშირდება; portal-ის როლი directory-ს პასუხიდან ყოველ შესვლაზე დგინდება. დეტალები: [არქიტექტურა და კონფიგურაცია](IT_OPERATIONS_RUNBOOK_KA.md#არქიტექტურა-და-კონფიგურაცია).

## 3. ჯანმრთელობა და ლოგები

ლეპტოპზე პროექტის ძირიდან PowerShell-ში:

```powershell
docker compose -f docker-compose.local.yml ps --all
curl.exe -sS http://localhost:8080/api/health
docker compose -f docker-compose.local.yml logs --tail 100 backend
```

`oracle`/`backend` უნდა იყოს `healthy`, `frontend` — `Up`, `seed` — `Exited (0)`; API-ის პასუხში უნდა იყოს `status=ok`, `database=ok` (HTTP კოდი: `curl.exe -sS -o NUL -w 'HTTP %{http_code}' http://localhost:8080/api/health`, მოსალოდნელია 200). განსხვავებული შედეგის განმარტება და თითოეული სერვისის ლოგი: [დიაგნოსტიკური ბრძანებები](IT_OPERATIONS_RUNBOOK_KA.md#უსაფრთხო-დიაგნოსტიკური-ბრძანებები-windows-powershell). ლოკალური ლოგები Docker Compose-შია; production-ში backend/frontend კონტეინერების stdout/stderr Kubernetes-იდან იკითხება, ხოლო ცენტრალური საცავი/SIEM-ის წვდომა [IT-ის №10 კითხვაა](QUESTIONS_FOR_IT.md). Kubernetes-ზე იხილეთ [მონიტორინგი და შიდა health](../k8s/README_KA.md#შემოწმება-რომ-მართლა-მუშაობს). ლოგის გაზიარებამდე დაიცავით პერსონალური მონაცემები და credentials.

## 4. მთავარი საზღვრები და პასუხის მომწოდებლები

- ლოკალური `APP_ENV=development` + `ALLOW_DEV_LOGIN=true` საცდელ, **ადმინისტრატორის ჩათვლით**, ანგარიშებზე ნებისმიერ პაროლს იღებს. ეს Compose მხოლოდ localhost-ზეა; არ გამოაქვეყნოთ ქსელში. Production-ში ეს კომბინაცია იკრძალება კოდით.
- [IT-ის ღია კითხვები](QUESTIONS_FOR_IT.md): InfoPortal-ის **საკუთარი** client credential და ოთხი ზუსტი როლი (№13); Oracle BLOB-ების მოცულობა და backup (№6); proxy-ის რეალური hop-ები (№7); აუდიტის ჯაჭვის გარე საკონტროლო ასლი (№14).
- DevOps/Platform: namespace, registry image-ები, ingress/TLS, network path, trusted proxies და staging-ის მიღება; [შესავსები `IT-NN` ველები](../k8s/README_KA.md).
- DBA: Oracle-ის სქემა/წვდომა, V49-ის წინაპირობის შემოწმება არსებულ ბაზაზე, backup/restore-ის **რეალური rehearsal**. ლეპტოპის წარმატება მათ ვერ ანაცვლებს.
- უსაფრთხოების გუნდი: აუდიტის გარე ასლი, ლოგების/SIEM-ის წვდომა და ინციდენტის არხი.

თუ სერვისი გაჩერდა ან მონაცემები საეჭვოა, დაიწყეთ [ინციდენტის პირველი ნაბიჯებით](IT_OPERATIONS_RUNBOOK_KA.md#ინციდენტის-პირველი-ნაბიჯები) და შემდეგ გამოიყენეთ [სიმპტომების ცხრილი](IT_OPERATIONS_RUNBOOK_KA.md#პრობლემების-მოძიება). Rollback-ის საზღვარი აღწერილია [აქ](IT_OPERATIONS_RUNBOOK_KA.md#backup-restore-და-rollback-ის-საზღვრები); პროდუქტის flag-ების ფაქტობრივი მოქმედება — [ცალკე ცნობარში](ROLLOUT_ROLLBACK_KA.md).
