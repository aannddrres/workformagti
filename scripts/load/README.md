# k6 load test — Java backend

Load-tests the isolated Java+Oracle test instance (default `:8090`), never
production. See `docs/JAVA_ORACLE_ANGULAR_MIGRATION.md` ("Test 4") for the
full write-up and results from the run this was built for.

## Run

```bash
# 1. Pre-fetch a small pool of tokens (respects the 10/min login rate limit)
TARGET_BASE_URL=http://localhost:8090 ./fetch_tokens.sh

# 2. Run the load test (k6 must be on PATH, or invoke the binary directly)
TARGET_BASE_URL=http://localhost:8090 k6 run k6-java-backend.js
```

Ramps 0 -> 600 virtual users over ~50s, holds at 600 for 30s, ramps down.
Each iteration replays realistic read traffic (`/api/compliance/my-progress`,
`/api/compliance/my-readings`, `/api/articles`, `/api/news`) using a token
picked round-robin from the pre-fetched pool -- deliberately not logging in
per-VU, since the login endpoint's own rate limiter would otherwise dominate
the result instead of the thing actually under test (HikariCP/Tomcat
capacity under concurrent authenticated reads).

`tokens.json` (gitignored) holds real JWTs — never commit it, and only run
this against a throwaway/test instance.
