#!/usr/bin/env bash
# The caller supplies a newly built shipping image and its own isolated backend.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${NGINX_SMOKE_CONFIRM:?set to isolated-synthetic-data after verifying the backend/schema}"
[[ "$NGINX_SMOKE_CONFIRM" == isolated-synthetic-data ]] || exit 2
: "${NGINX_SMOKE_IMAGE:?build angular-frontend/Dockerfile for this checkout first}"
: "${NGINX_SMOKE_BACKEND_PORT:?isolated backend port is required}"
[[ "$NGINX_SMOKE_BACKEND_PORT" =~ ^[0-9]+$ ]] || exit 2
case "$NGINX_SMOKE_BACKEND_PORT" in 8081|8082|1523|1524) echo 'Forbidden demo/UAT port'; exit 2;; esac
PORT="${NGINX_SMOKE_PORT:-18085}"
SLOW_PORT="${NGINX_SMOKE_SLOW_PORT:-18086}"
UPSTREAM_PORT="${NGINX_SMOKE_UPSTREAM_PORT:-18087}"
BACKEND_HOST="${NGINX_SMOKE_BACKEND_HOST:-host.docker.internal}"
for port in "$PORT" "$SLOW_PORT" "$UPSTREAM_PORT"; do
  [[ "$port" =~ ^[0-9]+$ && "$port" -ge 10000 && "$port" -le 65535 ]] || exit 2
done
[[ "$PORT" != "$SLOW_PORT" && "$PORT" != "$UPSTREAM_PORT" && "$SLOW_PORT" != "$UPSTREAM_PORT" ]] || exit 2
NAME="magti-fix-nginx-$$"
SLOW_NAME="${NAME}-slow"
FIXTURE_NAME="${NAME}-fixture"
NETWORK="${NAME}-net"
NETWORK_CREATED=false
CREATED=()
cleanup() {
  local result=$?
  if [[ "$result" -ne 0 ]]; then
    for container in "${CREATED[@]}"; do
      docker logs "$container" 2>&1 | grep -E 'upstream timed out|connect\(\) failed|prematurely closed|reset by peer' || true
    done
  fi
  for container in "${CREATED[@]}"; do docker rm -f "$container" >/dev/null; done
  if [[ "$NETWORK_CREATED" == true ]]; then docker network rm "$NETWORK" >/dev/null; fi
}
trap cleanup EXIT
IMAGE_ID="$(docker image inspect --format '{{.Id}}' "$NGINX_SMOKE_IMAGE")"
FIXTURE_IMAGE="$(docker build --quiet -f "$ROOT/scripts/nginx_slow_fixture.Dockerfile" "$ROOT/scripts")"
docker network create "$NETWORK" >/dev/null
NETWORK_CREATED=true
MSYS_NO_PATHCONV=1 docker run -d --name "$FIXTURE_NAME" --network "$NETWORK" --read-only --memory=64m \
  --cap-drop=ALL --security-opt=no-new-privileges:true "$FIXTURE_IMAGE" --port "$UPSTREAM_PORT" >/dev/null
CREATED+=("$FIXTURE_NAME")
for kind in app slow; do
  if [[ "$kind" == app ]]; then name="$NAME"; port="$PORT"; upstream="$NGINX_SMOKE_BACKEND_PORT"; host="$BACKEND_HOST"
  else name="$SLOW_NAME"; port="$SLOW_PORT"; upstream="$UPSTREAM_PORT"; host="$FIXTURE_NAME"; fi
  # MSYS must not rewrite Linux container paths into Windows host paths.
  # On Docker Desktop, pass the host-gateway IPv4 via NGINX_SMOKE_BACKEND_HOST to avoid its unreachable IPv6 record.
  MSYS_NO_PATHCONV=1 docker run -d --name "$name" --network "$NETWORK" --read-only --memory=192m \
    --cap-drop=ALL --security-opt=no-new-privileges:true \
    --tmpfs /var/cache/nginx:rw,nosuid,nodev,uid=101,gid=101,size=64m \
    --tmpfs /var/run:rw,nosuid,nodev,uid=101,gid=101,size=8m \
    --tmpfs /etc/nginx/conf.d:rw,nosuid,nodev,uid=101,gid=101,size=8m \
    --add-host host.docker.internal:host-gateway \
    -p "127.0.0.1:$port:8080" -e "BACKEND_HOST=$host" -e "BACKEND_PORT=$upstream" \
    "$IMAGE_ID" >/dev/null
  CREATED+=("$name")
done
for port in "$PORT" "$SLOW_PORT"; do
  ready=false
  for _ in $(seq 1 30); do
    if curl --silent --fail "http://127.0.0.1:$port/" >/dev/null; then ready=true; break; fi
    sleep 1
  done
  [[ "$ready" == true ]] || { echo 'nginx did not start'; exit 1; }
done
# The Python fixture starts slower than nginx in front of it: one early
# probe met "connection refused" (502) on 2026-09-26. Wait for it the same
# bounded way as for nginx above.
ready=false
for _ in $(seq 1 30); do
  if curl --silent --fail "http://127.0.0.1:$SLOW_PORT/api/ready" >/dev/null; then ready=true; break; fi
  sleep 1
done
[[ "$ready" == true ]] || { echo 'slow upstream fixture did not start'; exit 1; }
export NGINX_SMOKE_ENV=isolated NGINX_SMOKE_IMAGE_ID="$IMAGE_ID"
export NGINX_SMOKE_BASE_URL="http://127.0.0.1:$PORT" NGINX_SMOKE_SLOW_URL="http://127.0.0.1:$SLOW_PORT"
cd "$ROOT/angular-frontend"
npx playwright test --config playwright.nginx.config.ts "$@"
if [[ "${NGINX_SMOKE_RUN_FULL:-false}" == true ]]; then
  E2E_BASE_URL="$NGINX_SMOKE_BASE_URL" \
    PLAYWRIGHT_REPORT_DIR="${NGINX_FULL_REPORT_DIR:-playwright-report-shipping}" \
    npx playwright test
fi
