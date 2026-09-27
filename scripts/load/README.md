# k6 load test — Java backend

Load-tests the isolated Java+Oracle test instance (default `:8090`), never
production. See `docs/archive/migration/JAVA_ORACLE_ANGULAR_MIGRATION.md` ("Test 4") for the
full write-up and results from the run this was built for.

## Run

```bash
# 1. Pre-fetch a small pool of tokens (respects the 10/min login rate limit)
TARGET_BASE_URL=http://localhost:8090 ./fetch_tokens.sh

# 2. Run the representative release gate (150 VUs by default)
TARGET_BASE_URL=http://localhost:8090 k6 run k6-java-backend.js

# Optional capacity/stress run; this does not replace the 150-VU release gate
TARGET_VUS=600 TARGET_BASE_URL=http://localhost:8090 k6 run k6-java-backend.js
```

By default, ramps 0 -> 150 virtual users over ~50s, holds at 150 for 60s, then
ramps down. `TARGET_VUS` changes the capacity target explicitly.
Each iteration replays realistic read traffic (`/api/compliance/my-progress`,
`/api/compliance/my-readings`, `/api/articles`, `/api/news`, and
`/api/search/global`) using a token
picked round-robin from the pre-fetched pool -- deliberately not logging in
per-VU, since the login endpoint's own rate limiter would otherwise dominate
the result instead of the thing actually under test (HikariCP/Tomcat
capacity under concurrent authenticated reads).

The script is now a hard gate, not an observational run:

- HTTP failure rate must remain below 1%;
- business checks must remain above 99%;
- global-search p95 must remain below 2 seconds.

k6 exits non-zero if any threshold fails. A passing local/dev run is useful for
regression only; Enterprise readiness still requires the same command and a
saved result from approved production-like staging with representative data.

`tokens.json` (gitignored) holds real JWTs — never commit it, and only run
this against a throwaway/test instance.

## 600 distinct users and writes on staging

`k6-staging-600.js` is a separate capacity gate for a production-like,
isolated staging environment. IT must first provision 600 **synthetic**
InfoPortal identities named `loadtest.*`, each with a short-lived token from
the staging client and an article ID visible to that identity. Put them in
gitignored `staging-users.json` next to the script:

```json
[{"email":"loadtest.0001@example.test","token":"<staging JWT>","articleId":123}]
```

The real file needs 600 distinct entries. The script checks `/api/users/me`
against each entry before sending that identity's one article-view write. Run
it only after confirming that staging has disposable data and no real users:

```bash
LOAD_CONFIRM=isolated-synthetic-staging \
TARGET_BASE_URL=https://staging.example.test \
k6 run --summary-export=staging-600-summary.json k6-staging-600.js
```

The hard thresholds are HTTP errors below 1%, business checks above 99%,
global-search p95 below 2 seconds, exactly 600 verified identities and write
attempts, zero identity mismatches, and zero failed writes. A green k6 result
still needs database reconciliation: record the
start timestamp before the run, then have the DBA verify that
`article_view_logs` has 600 distinct `operator_email_snapshot` values starting
with `loadtest.` after that timestamp, with one row per expected account.
Record JVM, Hikari, Oracle, request latency and any stuck export jobs during
the run. Save the summary and the reconciliation query/result with the
release evidence. Do not commit `staging-users.json` or any token.
