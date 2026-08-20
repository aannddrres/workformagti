# პროექტის ლოკალურად გაშვება

ორი გზაა. თუ უბრალოდ გინდა პროდუქტი ნახო და დატესტო — **პირველი აირჩიე.**

---

## 1. მხოლოდ Docker (რეკომენდებული, მუშაობს Windows-ზეც)

**საჭიროა მხოლოდ Docker Desktop.** არც Java, არც Node, არც Git Bash.

```bash
docker compose -f docker-compose.local.yml up --build
```

მერე გახსენი **http://localhost:8080**

გასაჩერებლად `Ctrl+C`. ბაზა რჩება, ანუ მეორე გაშვება სწრაფია.

```bash
docker compose -f docker-compose.local.yml down      # გაჩერება, ბაზა რჩება
docker compose -f docker-compose.local.yml down -v   # გაჩერება და ბაზის წაშლა
```

**პირველი გაშვება ნელია** — Oracle-ის image იწერება (~2 GB), მერე ბაზა
ინიციალიზდება. სულ 10-15 წუთი. მომდევნო გაშვებები — 1-2 წუთი.

> **Docker Desktop-ს მეხსიერება მიეცი.** Oracle XE-ს დაახლოებით 2 GB
> სჭირდება, JVM-ს კიდევ. Settings → Resources → Memory: **მინიმუმ 6 GB**.
> ამაზე ნაკლებით Oracle ჩუმად კვდება და ბექენდი ვერასდროს დაიწყებს.

### რატომ ესაა სწორი გზა

ეს იმავე **ორ image-ს აშენებს, რომელსაც Kubernetes გაუშვებს**
(`java-backend/Dockerfile`, `angular-frontend/Dockerfile`) — არა ცალკე
სადეველოპერო კონსტრუქციას. ანუ ის, რასაც ლოკალურად ხედავ, არის ის, რაც
გაიგზავნება. ფრონტის image-ს `/api`-ის პროქსირება უკვე შეუძლია
`${BACKEND_HOST}:${BACKEND_PORT}`-ზე — კლასტერში ეს Service-ის სახელია,
აქ კი compose-ის სერვისი.

---

## 2. run-local.sh (დეველოპერისთვის)

უფრო სწრაფი გამეორებისთვის — კოდი ჰოსტზე შენდება, ფრონტი `ng serve`-ით
მუშაობს ცხელი გადატვირთვით.

**საჭიროა:** Docker, JDK 21, Node (იხ. `angular-frontend/.nvmrc`).
**Windows-ზე:** Git Bash ან WSL.

```bash
./scripts/run-local.sh          # გაშვება
./scripts/run-local.sh --clean  # ბაზის წაშლა და თავიდან აწყობა
```

გახსნი **http://localhost:4200**-ს.

---

## შესვლა

**ორივე გზაზე ნებისმიერი პაროლი მუშაობს** — dev-login ჩართულია.

| ანგარიში | როლი |
|---|---|
| `admin@magti.ge` | ადმინისტრატორი — აუდიტი, სტატისტიკა, მომხმარებლები |
| `content@magti.ge` | კონტენტის რედაქტორი |
| `manager@magti.ge` | მენეჯერი — გუნდის სტატისტიკა |
| `info@magti.ge` | ოპერატორი, საინფორმაციო |
| `tech@magti.ge` | ოპერატორი, ტექნიკური |

ცარიელი პორტალი არ დაგხვდება: ორივე გზა თესავს 2 კატეგორიას, 5 სტატიას
და 2 სიახლეს (`scripts/seed-demo-content.sh`, საერთო ორივესთვის).
თესვა ხდება მხოლოდ მაშინ, თუ ბაზაში სტატია არ არის — ხელახლა გაშვება
არაფერს ადუბლირებს.

---

## ⚠️ უსაფრთხოება

ორივე გზა ბექენდს `APP_ENV=development` და `ALLOW_DEV_LOGIN=true`-ით უშვებს.
ეს რთავს **უპაროლო შესვლას, ადმინის ჩათვლით**.

image თავად `APP_ENV=production`-ით იგზავნება და dev-login-ით საერთოდ
უარს იტყვის გაშვებაზე (SEC-01). ეს ფაილები **განზრახ** გადაფარავენ ამას —
სწორედ ამიტომ ჰქვია `docker-compose.local.yml` და არა უბრალოდ
`docker-compose.yml`.

პორტი მიბმულია **მხოლოდ `127.0.0.1`-ზე**. არ გაუშვა ტუნელში, საერთო
ქსელში ან reverse proxy-ის უკან.

---

## თუ გატყდა

| სიმპტომი | სავარაუდო მიზეზი |
|---|---|
| `oracle` unhealthy რჩება | Docker-ს მეხსიერება არ ჰყოფნის — 6 GB მიეცი |
| ბექენდი ეშვება და კვდება | `docker compose -f docker-compose.local.yml logs backend` |
| გვერდი იხსნება, მაგრამ შესვლა არ გამოდის | `logs backend` — უნდა ეწეროს `dev-login=ENABLED` |
| `seed exited with code 0` | ეს **წარმატებაა**, არა შეცდომა — ერთჯერადი კონტეინერია |

დეტალური ლოგი:
```bash
docker compose -f docker-compose.local.yml logs -f backend
```
