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

## Audited writes — `audited_writes.py`

The k6 run above is reads only. Every audited write takes the audit chain's
single tip row (`audit_chain_state`, locked by `trg_audit_logs_chain`, V28)
until its transaction commits, so audited writes from the whole portal queue
behind one another. This script measures that queue: 50 simultaneous first
sign-ins, then 50 quiz submissions, then 50 bookmarks as the unaudited
control, while sampling `v$session` for sessions waiting on the lock.

```bash
ORACLE_SYSTEM_PASSWORD=... python scripts/load/audited_writes.py \
  --base-url http://localhost:8080 --users 50 --oracle-dsn localhost:1522/XEPDB1
```

Needs `APP_ENV=development` and `ALLOW_DEV_LOGIN=true` on the target. One
address may sign in 60 times a minute, so do not run it within a minute of
another sign-in heavy run.

Result on 2026-09-25, Oracle XE 21c and the backend on one host, two runs:

| Burst | p50 | p90 | max | Most sessions waiting on the audit lock |
|---|---|---|---|---|
| sign-in | 1239–1301 ms | 1372–1431 ms | 1463 ms | 2 |
| quiz submission | 190–254 ms | 253–323 ms | 340 ms | 3–6 |
| bookmark (not audited) | 103–174 ms | 139–221 ms | 232 ms | 0 |

All 300 requests returned 200. The queue is real but short: at 50 at once it
never held more than six sessions, and no submission took over 340 ms. The
sign-in time is the first-login password hashing of 50 new accounts, not the
lock. So the lock keeps waiting without a timeout, as V28 has it. One host
cannot show network time to the database, which lengthens every hold, so the
same run belongs on staging before go-live.
