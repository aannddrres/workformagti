# k6 load test — Java backend

Load-tests the isolated Java+Oracle test instance (default `:8090`), never
production. See `docs/JAVA_ORACLE_ANGULAR_MIGRATION.md` ("Test 4") for the
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
