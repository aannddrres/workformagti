#!/usr/bin/env bash
# Start or stop a QA backend against the throwaway MAGTI_QA schema.
#
#   scripts/qa/qa-backend.sh start [PORT] [JAR]   # default 8090, target/*.jar
#   scripts/qa/qa-backend.sh stop  [PORT]
#   scripts/qa/qa-backend.sh kill  [PORT]          # no shutdown hooks: a crash
#   scripts/qa/qa-backend.sh status [PORT]
#
# Extra settings come from the environment and are passed through, e.g.
#   QA_TZ=UTC                      run the JVM in UTC, as the container does
#   QA_DB_URL=jdbc:...:15210/...   reach Oracle through a fault proxy
#   SESSION_IDLE_MINUTES=1 ...     anything application.yml reads
#
# Logs and PID files go to scripts/qa/.run/ (gitignored). Ports 8080-8082
# belong to the owner's own stacks and are refused.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
run="$here/.run"
mkdir -p "$run"
cmd="${1:-}"
port="${2:-8090}"
case "$port" in 8080|8081|8082) echo "refusing port $port: another stack's" >&2; exit 2 ;; esac
pidfile="$run/backend-$port.pid"
log="$run/backend-$port.log"

# The JVM's Windows PID, found by the port it listens on: /proc/$!/winpid can
# name a short-lived launcher instead, and then "stop" misses the JVM.
listener() { netstat -ano 2>/dev/null | awk -v p=":$port" '$2 ~ p"$" && $4 == "LISTENING" {print $5; exit}'; }
winpid() { local p; p="$(listener)"; [ -n "$p" ] && echo "$p" || cat "$pidfile" 2>/dev/null || true; }
alive() { local p; p="$(winpid)"; [ -n "$p" ] && tasklist //FI "PID eq $p" 2>/dev/null | grep -q "$p"; }

case "$cmd" in
start)
  if alive; then echo "already running on :$port (pid $(winpid))"; exit 0; fi
  jar="${3:-}"
  if [ -z "$jar" ]; then
    jar="$(ls "$repo"/java-backend/target/portal-backend-*.jar 2>/dev/null | grep -v plain | head -1 || true)"
  fi
  [ -f "$jar" ] || { echo "no jar; build: cd java-backend && ./mvnw.cmd -B -DskipTests package" >&2; exit 1; }
  export ORACLE_DB_URL="${QA_DB_URL:-jdbc:oracle:thin:@localhost:1521/orclpdb1}"
  export ORACLE_DB_USER=MAGTI_QA
  export ORACLE_DB_PASSWORD="${ORACLE_DB_PASSWORD:-local_only_magti_qa_pw}"
  export APP_ENV=development ALLOW_DEV_LOGIN=true COOKIE_SECURE=false
  export ROLLOUT_FILE_ENTITLEMENT="${ROLLOUT_FILE_ENTITLEMENT:-true}"
  # Lets a check give each simulated person their own address through
  # X-Forwarded-For, so 600 people are not one 60-a-minute login bucket.
  export TRUSTED_PROXIES="${TRUSTED_PROXIES:-127.0.0.1}"
  tzopt=()
  [ -n "${QA_TZ:-}" ] && tzopt=("-Duser.timezone=$QA_TZ")
  # shellcheck disable=SC2086
  java ${QA_JAVA_OPTS:-} "${tzopt[@]}" -jar "$(cygpath -m "$jar")" \
    --server.address=127.0.0.1 --server.port="$port" > "$log" 2>&1 &
  cat "/proc/$!/winpid" > "$pidfile"
  for _ in $(seq 1 180); do
    if curl -sf "http://127.0.0.1:$port/api/health" > /dev/null 2>&1; then
      listener > "$pidfile"
      echo "up on :$port (pid $(winpid), jar $(basename "$jar"), tz ${QA_TZ:-machine})"; exit 0
    fi
    alive || { echo "backend exited; last log lines:" >&2; tail -30 "$log" >&2; exit 1; }
    sleep 1
  done
  echo "not healthy after 180 s; see $log" >&2; exit 1
  ;;
stop|kill)
  p="$(winpid)"
  if [ -z "$p" ] || ! alive; then echo "not running on :$port"; rm -f "$pidfile"; exit 0; fi
  # Both are forced on Windows (a console JVM ignores a polite close); "stop"
  # is the routine one, "kill" is named for the checks that mean a crash.
  taskkill //PID "$p" //F > /dev/null
  rm -f "$pidfile"
  echo "stopped :$port (pid $p)"
  ;;
status)
  if alive; then echo "running on :$port (pid $(winpid))"; else echo "not running on :$port"; fi
  ;;
*) echo "usage: $0 start|stop|kill|status [PORT] [JAR]" >&2; exit 2 ;;
esac
