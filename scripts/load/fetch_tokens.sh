#!/usr/bin/env bash
# Pre-fetches a small pool of JWTs for k6-java-backend.js, writing
# tokens.json next to this script. Run against an isolated test instance
# only -- never production. Respects AuthController's LoginRateLimiter
# (10/minute/IP) since it makes only 6 sequential login calls.
set -e
BASE="${TARGET_BASE_URL:-http://localhost:8090}"
cd "$(dirname "$0")"

EMAILS=("admin@magti.ge" "content@magti.ge" "manager@magti.ge" "nino@magti.ge" "tech@magti.ge" "info@magti.ge")
echo "[" > tokens.json
first=true
for email in "${EMAILS[@]}"; do
  TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" \
    -d "{\"email\":\"$email\",\"password\":\"x\"}" | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)
  if [ -z "$TOKEN" ]; then
    echo "WARN: no token for $email" >&2
    continue
  fi
  if [ "$first" = true ]; then first=false; else echo "," >> tokens.json; fi
  echo "  \"$TOKEN\"" >> tokens.json
done
echo "]" >> tokens.json
echo "Wrote tokens.json ($(grep -c '"' tokens.json | head -1) tokens)"
