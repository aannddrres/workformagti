#!/usr/bin/env bash
# Runs what CI runs, against a local Oracle.
#
# Exists because CI stopped being able to answer. The account's Actions
# allowance ran out on 2026-08-22 and every run since has failed in two
# seconds with no logs, on every branch. Until that is resolved, "verified"
# means somebody ran it on their own machine -- and that is worth something
# only if it is the same commands, in the same order, with nothing left out
# because it was slow.
#
# It is NOT a replacement for CI. It runs on one machine, against one Oracle,
# started by the person who also wrote the code. What it removes is the
# ambiguity in "I ran the tests".
#
#   scripts/verify-like-ci.sh          # everything, including e2e (~15 min)
#   scripts/verify-like-ci.sh fast     # only the jobs a branch push still runs
#   scripts/verify-like-ci.sh oracle   # only the two Oracle-backed suites
#
# Oracle: expects one reachable at ORACLE_DSN below. To start one:
#   docker run -d --name magti-oracle -p 1521:1521 \
#     -e ORACLE_PASSWORD=LocalSysPw1 -e APP_USER=magti_app \
#     -e APP_USER_PASSWORD=LocalAppPw1 gvenzl/oracle-xe:21-slim-faststart
set -euo pipefail

MODE="${1:-all}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ORACLE_HOST="${ORACLE_HOST:-localhost:1521/XEPDB1}"
ORACLE_USER="${ORACLE_USER:-magti_app}"
ORACLE_PASSWORD="${ORACLE_PASSWORD:-LocalAppPw1}"

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
skip() { printf '\n\033[2m-- skipped: %s\033[0m\n' "$1"; }

cd "$ROOT"

# --- the three jobs a branch push still runs in CI -------------------------

if [ "$MODE" = "all" ] || [ "$MODE" = "fast" ]; then
  step "test (ruff + pytest)"
  ruff check .
  pytest tests/ -q

  step "java-unit (DB-free)"
  (cd java-backend && mvn -B test -DexcludedGroups=oracle)

  step "frontend (build + unit tests)"
  (cd angular-frontend && npm ci && npx ng build --configuration production && npx ng test --watch=false)
fi

# --- the jobs that now run only on a pull request or on main --------------

if [ "$MODE" = "all" ] || [ "$MODE" = "oracle" ]; then
  step "java-integration (Oracle)"
  (cd java-backend && \
    ORACLE_DB_URL="jdbc:oracle:thin:@${ORACLE_HOST}" \
    ORACLE_DB_USER="$ORACLE_USER" \
    ORACLE_DB_PASSWORD="$ORACLE_PASSWORD" \
    mvn -B -e test -Dgroups=oracle)

  step "etl-oracle (cutover rehearsal)"
  ETL_ORACLE_DSN="$ORACLE_HOST" \
  ETL_ORACLE_USER="$ORACLE_USER" \
  ETL_ORACLE_PASSWORD="$ORACLE_PASSWORD" \
    python -m pytest tests/etl -q
fi

if [ "$MODE" = "all" ]; then
  # Deliberately not automated here. The CI job builds the jar, starts the
  # backend, waits for /api/health, serves Angular and drives a browser --
  # ten minutes of orchestration that a script on a developer machine tends
  # to get subtly wrong (a stale port, a leftover process, a different
  # Oracle). Getting it wrong quietly is worse than not running it, so this
  # says what to run instead of pretending.
  skip "e2e -- run it deliberately: see the 'e2e' job in .github/workflows/ci.yml"
fi

printf '\n\033[1mDone (%s).\033[0m Note what this is: one machine, one Oracle, no independent runner.\n' "$MODE"
