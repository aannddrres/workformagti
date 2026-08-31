#!/usr/bin/env bash
# Runs what CI runs, against a local Oracle.
#
# Exists because CI no longer answers on every push. The Oracle-backed jobs
# (java-integration and e2e) are gated to pull requests and main -- they were
# most of what a full run cost, and paying it on every branch push is what
# exhausted the account's Actions allowance on 2026-08-22. Between pushing to
# a branch and opening a pull request, "verified" therefore means somebody ran
# it on their own machine -- and that is worth something only if it is the
# same commands, in the same order, with nothing left out because it was slow.
#
# It is NOT a replacement for CI. It runs on one machine, against one Oracle,
# started by the person who also wrote the code. What it removes is the
# ambiguity in "I ran the tests".
#
#   scripts/verify-like-ci.sh          # everything it can run here
#   scripts/verify-like-ci.sh fast     # only the jobs a branch push still runs
#   scripts/verify-like-ci.sh oracle   # only the Oracle-backed Java suite
#
# Oracle: expects one reachable at ORACLE_HOST below. The repo's own local
# instance is localhost:1521/orclpdb1; override the three variables for any
# other. With none reachable, the Java suite starts a Testcontainer itself
# (see OracleTestcontainer), which is slower but works.
#
# Brought across from claude/r5-complete-r6-planning, adapted to this tree:
# the ETL rehearsal it also ran does not exist here, the Maven wrapper is
# used instead of a system mvn, and the i18n guard is the npm script.
set -euo pipefail

MODE="${1:-all}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# `python -m pytest`, not the `pytest` on PATH. In CI they are the same thing,
# because the job pip-installs into the interpreter it then runs. On a
# developer machine they often are not: a uv- or pipx-installed pytest lives
# in its own environment and cannot import the seeders' dependencies, so the
# suite dies during collection with a ModuleNotFoundError that looks like a
# broken repo.
PYTHON="${PYTHON:-python}"

ORACLE_HOST="${ORACLE_HOST:-localhost:1521/orclpdb1}"
ORACLE_USER="${ORACLE_USER:-magti_app}"
ORACLE_PASSWORD="${ORACLE_PASSWORD:-CHANGE_ME_LOCAL_DEV_ONLY}"

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

# Anything this machine could not run. The script exits non-zero while this is
# non-empty: a job that did not run must never read as a job that passed --
# that is the whole failure mode of replacing CI with "I ran it locally".
UNVERIFIED=()
unverified() { UNVERIFIED+=("$1"); printf '\n\033[33m!! not verified here: %s\033[0m\n' "$1"; }

cd "$ROOT"

# --- the three jobs a branch push still runs in CI -------------------------

if [ "$MODE" = "all" ] || [ "$MODE" = "fast" ]; then
  step "seeder-tools (ruff + pytest)"
  "$PYTHON" -m ruff check scripts/ tests/
  "$PYTHON" -m pytest tests/ -q

  step "java-unit (DB-free)"
  (cd java-backend && ./mvnw -B test -DexcludedGroups=oracle)

  step "frontend (i18n guard, production build, unit tests)"
  (cd angular-frontend && npm ci >/dev/null)
  # Plain Node, no Angular CLI -- so this one runs even where the build cannot.
  (cd angular-frontend && npm run check:i18n)

  # Nothing pins the Node version, and the Angular CLI refuses a release one
  # patch old. Rather than let the CLI's own message look like a broken repo,
  # say what it is and record that the job did not run.
  if (cd angular-frontend && npx ng version >/dev/null 2>&1); then
    (cd angular-frontend && npx ng build --configuration production && npx ng test --watch=false)
  else
    unverified "Angular build and unit tests -- the CLI rejects this machine's Node ($(node --version))"
  fi
fi

# --- the jobs that now run only on a pull request or on main --------------

if [ "$MODE" = "all" ] || [ "$MODE" = "oracle" ]; then
  step "java-integration (Oracle)"
  (cd java-backend && \
    ORACLE_DB_URL="jdbc:oracle:thin:@${ORACLE_HOST}" \
    ORACLE_DB_USER="$ORACLE_USER" \
    ORACLE_DB_PASSWORD="$ORACLE_PASSWORD" \
    ./mvnw -B -e test -Dgroups=oracle)
fi

if [ "$MODE" = "all" ]; then
  # Deliberately not automated here. The CI job builds the jar, starts the
  # backend, waits for /api/health, serves Angular and drives a browser --
  # ten minutes of orchestration that a script on a developer machine tends
  # to get subtly wrong (a stale port, a leftover process, a different
  # Oracle). Getting it wrong quietly is worse than not running it, so this
  # says what to run instead of pretending.
  unverified "e2e -- ten minutes of orchestration a developer-machine script tends to get quietly wrong; run the 'e2e' job's steps from .github/workflows/ci.yml deliberately"
fi

printf '\n\033[1mDone (%s).\033[0m One machine, one Oracle, no independent runner.\n' "$MODE"

if [ ${#UNVERIFIED[@]} -gt 0 ]; then
  printf '\n\033[33m%d thing(s) this run did NOT verify:\033[0m\n' "${#UNVERIFIED[@]}"
  for item in "${UNVERIFIED[@]}"; do printf '  - %s\n' "$item"; done
  exit 1
fi
