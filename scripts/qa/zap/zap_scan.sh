#!/usr/bin/env bash
# Check 6 -- OWASP ZAP against a QA instance: the API from its OpenAPI
# description (docs/api-contract/openapi.json, 102 paths), signed in as a
# system administrator so every gated endpoint is reached, plus the
# production build's pages through serve_prod.cjs.
#
#   scripts/qa/zap/zap_scan.sh [ACTIVE_MINUTES]      # default 30
#
# Needs: a QA backend on :8090 (qa-backend.sh), serve_prod.cjs on :4300, and
# ZAP 2.17.0 (owner-approved download, 2026-10-03) unpacked at
# scripts/qa/.run/zap/ZAP_2.17.0, or ZAP_HOME pointing at one. Output:
# scripts/qa/results/zap/zap-report.{html,json}. ZAP's own state goes to a
# throwaway directory, never the user's profile.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
zap="${ZAP_HOME:-$here/../.run/zap/ZAP_2.17.0}"
out="$here/../results/zap"
mkdir -p "$out"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

token="$(curl -sf -X POST http://127.0.0.1:8090/api/auth/login -H 'Content-Type: application/json' \
  -H 'X-Forwarded-For: 10.200.0.1' -d '{"email":"admin@magti.ge","password":"x"}' \
  | python -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')"

sed -e "s#@@API@@#http://127.0.0.1:8090#g" -e "s#@@WEB@@#http://127.0.0.1:4300#g" \
    -e "s#@@OPENAPI@@#$(win "$repo/docs/api-contract/openapi.json")#" \
    -e "s#@@OUT@@#$(win "$out")#g" -e "s#@@ACTIVE_MINUTES@@#${1:-30}#" \
    "$here/plan.template.yaml" > "$work/plan.yaml"

# ZAP adds this header to every request it sends (its documented env hook).
export ZAP_AUTH_HEADER=Authorization ZAP_AUTH_HEADER_VALUE="Bearer $token" ZAP_AUTH_HEADER_SITE=127.0.0.1
# -port: ZAP's own proxy defaults to 8080, which belongs to another stack here.
java -Xmx2g -jar "$(win "$zap/zap-2.17.0.jar")" -cmd -port "${ZAP_PORT:-18080}" -dir "$(win "$work/home")" \
  -autorun "$(win "$work/plan.yaml")"
echo "report: $out/zap-report.html"
