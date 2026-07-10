#!/usr/bin/env bash
# Smoke-tests the Magti Portal FastAPI backend: launches it (if not already
# running), logs in, and hits a few representative authenticated endpoints.
# Exit code 0 = healthy. Run from the repo root:
#   bash .claude/skills/run-magti-portal/smoke.sh
set -uo pipefail

PORT="${PORT:-8002}"
BASE_URL="http://127.0.0.1:${PORT}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PYTHON="${REPO_ROOT}/venv/Scripts/python.exe"
LOG_FILE="${TMPDIR:-/tmp}/magti-smoke-uvicorn.log"

WE_STARTED_IT=0
SERVER_PID=""

cleanup() {
  if [ "$WE_STARTED_IT" = "1" ] && [ -n "$SERVER_PID" ]; then
    echo "Stopping server we started (PID $SERVER_PID)..."
    kill "$SERVER_PID" 2>/dev/null
  fi
}
trap cleanup EXIT

fail() {
  echo "FAIL: $1"
  exit 1
}

# ── 1. Ensure the server is up (reuse if already running, else launch) ─────
if curl -sf -o /dev/null "${BASE_URL}/"; then
  echo "Server already responding on ${BASE_URL} — reusing it."
else
  echo "No server on ${BASE_URL} — launching uvicorn (log: ${LOG_FILE})..."
  cd "$REPO_ROOT"
  "$PYTHON" -m uvicorn main:app --host 127.0.0.1 --port "$PORT" > "$LOG_FILE" 2>&1 &
  SERVER_PID=$!
  WE_STARTED_IT=1

  echo "Waiting for readiness (PID $SERVER_PID)..."
  ready=0
  for i in $(seq 1 30); do
    if curl -sf -o /dev/null "${BASE_URL}/"; then
      ready=1
      break
    fi
    sleep 1
  done
  if [ "$ready" != "1" ]; then
    echo "--- last 40 lines of ${LOG_FILE} ---"
    tail -40 "$LOG_FILE"
    fail "server never became ready on ${BASE_URL} within 30s"
  fi
fi

# ── 2. Log in (JIT dev-bypass: any password works for admin@magti.ge when
#      APP_ENV != production — see the skill's Gotchas section) ────────────
LOGIN_RESPONSE=$(curl -sf -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@magti.ge","password":"password"}') \
  || fail "POST /api/auth/login did not return 2xx"

TOKEN=$("$PYTHON" -c "import sys, json; print(json.load(sys.stdin)['access_token'])" <<< "$LOGIN_RESPONSE") \
  || fail "could not extract access_token from login response: $LOGIN_RESPONSE"

echo "Login OK — got a token."

# ── 3. Hit a few representative authenticated endpoints ─────────────────────
check_endpoint() {
  local path="$1"
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}${path}" \
    -H "Authorization: Bearer ${TOKEN}")
  if [ "$code" != "200" ]; then
    fail "GET ${path} -> ${code} (expected 200)"
  fi
  echo "GET ${path} -> 200 OK"
}

check_endpoint "/api/users/me"
check_endpoint "/api/categories"
check_endpoint "/api/articles?limit=5"

echo ""
echo "All smoke checks passed."
