#!/usr/bin/env bash
#
# Bring the whole product up on one machine: Oracle in Docker, the Spring Boot
# backend, and the Angular dev server. One command, Ctrl+C to stop.
#
# This is the same recipe the E2E job in .github/workflows/ci.yml uses, which
# is the only configuration of this stack that is known to work start to
# finish. Kept here so it is runnable without reading a YAML file.
#
#   ./scripts/run-local.sh          bring it up (seeds demo content if empty)
#   ./scripts/run-local.sh --clean  destroy the database first, then bring it up
#
# Requires: Docker, JDK 21, Node (see angular-frontend/.nvmrc).
#
# ---------------------------------------------------------------------------
# SECURITY, read this once.
#
# The backend is started with APP_ENV=development and ALLOW_DEV_LOGIN=true.
# Together those turn on password-less login for the seeded accounts, INCLUDING
# an admin -- any password is accepted. That is deliberate for a laptop and
# catastrophic anywhere else. The two flags exist separately so this cannot
# happen by accident (SEC-01): nothing defaults to it, both must be asked for,
# and this script is asking for them on purpose.
#
# It also binds only to localhost. Do not put this behind a tunnel, a shared
# network, or a reverse proxy.
# ---------------------------------------------------------------------------

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER=magti-portal-oracle-dev
BACKEND_URL=http://localhost:8080
FRONTEND_URL=http://localhost:4200
LOG_DIR="$REPO_ROOT/.local-run"

# Throwaway credentials for a throwaway local database. Not secrets: this
# container is not reachable from outside the machine, and --clean deletes it.
ORACLE_SYS_PW=local_only_sys_pw
ORACLE_APP_USER=magti_app
ORACLE_APP_PW=local_only_app_pw

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m  %s\n' "$*"; }
die()  { printf '\033[1;31mxx\033[0m  %s\n' "$*" >&2; exit 1; }

# --- preflight -------------------------------------------------------------
# Checked up front, because finding out that Node is a patch version too old
# after Oracle has spent two minutes starting is a bad way to learn it.

command -v docker >/dev/null || die "docker not found. Docker Desktop / Docker Engine is required for the Oracle container."
docker info >/dev/null 2>&1 || die "the Docker daemon is not running. Start Docker and try again."
command -v java >/dev/null || die "java not found. JDK 21 is required."
command -v node >/dev/null || die "node not found. See angular-frontend/.nvmrc for the version."

# Matched on the version string itself rather than read off the first line.
# `java -version` does not promise the version is line one: with
# JAVA_TOOL_OPTIONS set -- which is how a corporate JDK is usually pointed at
# an HTTPS proxy and a truststore -- the JVM prints "Picked up
# JAVA_TOOL_OPTIONS: ..." above it, and a head -1 reads that instead. This
# script died on exactly that the first time it was run for real.
JAVA_MAJOR="$(java -version 2>&1 | grep -Eo 'version "[0-9]+' | head -1 | grep -Eo '[0-9]+')"
[ -n "$JAVA_MAJOR" ] || die "could not read the Java version. Check: java -version"
[ "$JAVA_MAJOR" -ge 21 ] || die "JDK 21 or newer is required, found $JAVA_MAJOR."

NODE_WANT="$(cat "$REPO_ROOT/angular-frontend/.nvmrc")"
NODE_HAVE="$(node -v | sed 's/^v//')"
if [ "$NODE_HAVE" != "$NODE_WANT" ]; then
  # Not fatal -- newer is fine. But the Angular CLI enforces a minimum and
  # refuses to build below it, so a lower version needs to be said plainly.
  lowest="$(printf '%s\n%s\n' "$NODE_WANT" "$NODE_HAVE" | sort -V | head -1)"
  if [ "$lowest" = "$NODE_HAVE" ]; then
    die "Node $NODE_HAVE is older than the required $NODE_WANT. The Angular CLI will refuse to build. Try: nvm install $NODE_WANT"
  fi
  warn "Node $NODE_HAVE (repo pins $NODE_WANT). Newer, so continuing."
fi

mkdir -p "$LOG_DIR"

# --- teardown --------------------------------------------------------------
# The Oracle container is left RUNNING on exit, on purpose: it takes a couple
# of minutes to initialise and keeping it means the second run of this script
# starts in seconds. --clean is how you throw it away.

BACKEND_PID=""
FRONTEND_PID=""
cleanup() {
  say "stopping"
  [ -n "$FRONTEND_PID" ] && kill "$FRONTEND_PID" 2>/dev/null || true
  [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  echo
  # Only claim the container is there if it actually is. The first real run of
  # this script failed while CREATING it and still printed "still running, so
  # the next start is fast", which is a confusing thing to read directly under
  # the error that says it was never created.
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$CONTAINER"; then
    say "the Oracle container ($CONTAINER) is still running, so the next start is fast."
    say "to remove it and its data:  docker rm -f $CONTAINER"
  fi
}
trap cleanup EXIT INT TERM

if [ "${1:-}" = "--clean" ]; then
  say "removing the existing database container"
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
fi

# --- oracle ----------------------------------------------------------------
# Same image as CI. XE 21c is used rather than something lighter because it
# agrees with production 19c about NUMBER(1) boolean columns, which is what
# Hibernate's ddl-auto=validate checks at startup -- a different engine would
# pass here and fail there.

if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    say "starting the existing Oracle container"
    docker start "$CONTAINER" >/dev/null
  else
    say "Oracle container already running"
  fi
else
  say "creating the Oracle container (first run takes a few minutes)"
  docker run -d --name "$CONTAINER" \
    -p 1521:1521 \
    -e ORACLE_PASSWORD="$ORACLE_SYS_PW" \
    -e APP_USER="$ORACLE_APP_USER" \
    -e APP_USER_PASSWORD="$ORACLE_APP_PW" \
    gvenzl/oracle-xe:21-slim-faststart >/dev/null
fi

say "waiting for Oracle to accept connections"
for i in $(seq 1 180); do
  if docker exec "$CONTAINER" healthcheck.sh >/dev/null 2>&1; then
    say "Oracle ready after ${i}s"
    break
  fi
  [ "$i" -eq 180 ] && die "Oracle did not become healthy in 3 minutes. Check: docker logs $CONTAINER"
  sleep 1
done

# --- backend ---------------------------------------------------------------

say "building the backend"
(cd "$REPO_ROOT/java-backend" && ./mvnw -B -q -DskipTests package)

JAR="$(ls -t "$REPO_ROOT"/java-backend/target/*.jar 2>/dev/null | head -1)"
[ -n "$JAR" ] || die "no jar in java-backend/target after the build."

say "starting the backend"
# Backgrounded as a subshell that execs into java, so $! is the JVM's own pid
# and the cleanup trap can actually kill it. Writing the pid from inside a
# subshell instead would leave the JVM reparented and unkillable from here.
(
  cd "$REPO_ROOT/java-backend"
  ORACLE_DB_URL="jdbc:oracle:thin:@localhost:1521/XEPDB1" \
  ORACLE_DB_USER="$ORACLE_APP_USER" \
  ORACLE_DB_PASSWORD="$ORACLE_APP_PW" \
  APP_ENV=development \
  ALLOW_DEV_LOGIN=true \
  COOKIE_SECURE=false \
  exec java -jar "$JAR"
) > "$LOG_DIR/backend.log" 2>&1 &
BACKEND_PID=$!

for i in $(seq 1 120); do
  if curl -sf "$BACKEND_URL/api/health" >/dev/null 2>&1; then
    say "backend up after ${i}s  ($BACKEND_URL)"
    break
  fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    tail -30 "$LOG_DIR/backend.log"
    die "the backend exited during startup. Full log: $LOG_DIR/backend.log"
  fi
  [ "$i" -eq 120 ] && die "backend did not answer /api/health in 2 minutes. Log: $LOG_DIR/backend.log"
  sleep 1
done

# --- demo content ----------------------------------------------------------
# Flyway creates the schema but nothing puts content in it, and the users
# themselves are provisioned just-in-time on first login. A brand new database
# therefore renders a working but completely empty portal, which is not much
# use for looking around. This adds enough to browse: two categories and a few
# articles and news items, through the real API, exactly as the admin UI would.
#
# Skipped entirely if any article already exists, so re-running is safe and
# never duplicates.

seed_content() {
  local token cat_a cat_b
  token="$(curl -sf -X POST "$BACKEND_URL/api/auth/login" \
      -H 'Content-Type: application/json' \
      -d '{"email":"admin@magti.ge","password":"x"}' \
    | grep -o '"access_token":"[^"]*"' | head -1 | cut -d'"' -f4)"
  [ -n "$token" ] || { warn "could not log in to seed demo content; skipping"; return 0; }

  # "does any article exist" rather than "is the body exactly []", so
  # pretty-printing or a changed envelope cannot turn this into a duplicate
  # seed on every start.
  if curl -sf "$BACKEND_URL/api/articles" -H "Authorization: Bearer $token" | grep -q '"id"'; then
    say "content already present, not seeding"
    return 0
  fi

  say "seeding demo content"
  api() { curl -sf -X POST "$BACKEND_URL$1" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$2"; }
  # First "id" in the response, not the last: a greedy match would pick up an
  # id from a nested object if the shape ever grows one.
  id_of() { grep -o '"id":[0-9]*' | head -1 | cut -d: -f2; }

  cat_a="$(api /api/categories '{"name":"ინტერნეტი და ქსელი","parent_id":null}' | id_of)"
  cat_b="$(api /api/categories '{"name":"ტარიფები","parent_id":null}' | id_of)"

  article() {
    api /api/articles "$(cat <<JSON
{"title":$1,"content":$2,"category_id":$3,"tags":null,
 "target_departments":["საინფორმაციო","ტექნიკური"],"status":"published",
 "published_at":null,"attachment_url":null,"audience_profile":"all",
 "visible_to_tech_info":true,"visible_to_service_center":false,
 "is_draft":false,"quiz_enabled":false,"notify_operators":false}
JSON
)" >/dev/null
  }

  article '"ინტერნეტი არ მუშაობს — პირველი ნაბიჯები"' \
          '"<p>შეამოწმეთ ტერმინალის ინდიკატორები, გადატვირთეთ როუტერი, დააფიქსირეთ ხაზის სტატუსი.</p>"' "$cat_a"
  article '"GPON ტერმინალის დიაგნოსტიკა"' \
          '"<p>LOS ინდიკატორი, სიგნალის დონე, ოპტიკური ხაზის შემოწმების თანმიმდევრობა.</p>"' "$cat_a"
  article '"Wi-Fi სიჩქარე დაბალია"' \
          '"<p>არხის დატვირთვა, 2.4 vs 5 GHz, ტერმინალის განთავსება.</p>"' "$cat_a"
  article '"სატარიფო გეგმის შეცვლა"' \
          '"<p>რა პირობებით იცვლება გეგმა და როდის ამოქმედდება ცვლილება.</p>"' "$cat_b"
  article '"დამატებითი პაკეტების აქტივაცია"' \
          '"<p>აქტივაციის არხები და მოქმედების ვადები.</p>"' "$cat_b"

  api /api/news '{"title":"ახალი სატარიფო გეგმები 1 სექტემბრიდან","content":"<p>დეტალები ცალკე ინსტრუქციაში.</p>","target_department":"All","attachment_url":null,"visible_to_tech_info":true,"visible_to_service_center":true,"expires_at":null,"is_draft":false}' >/dev/null
  api /api/news '{"title":"გეგმიური სამუშაოები ქსელზე","content":"<p>შესაძლო შეფერხებები ღამის საათებში.</p>","target_department":"All","attachment_url":null,"visible_to_tech_info":true,"visible_to_service_center":true,"expires_at":null,"is_draft":false}' >/dev/null

  say "seeded 2 categories, 5 articles, 2 news items"
}
seed_content || warn "seeding failed; the portal will start empty"

# --- frontend --------------------------------------------------------------

say "installing frontend dependencies"
(cd "$REPO_ROOT/angular-frontend" && npm ci --no-audit --no-fund >"$LOG_DIR/npm.log" 2>&1) \
  || die "npm ci failed. Log: $LOG_DIR/npm.log"

say "starting the frontend"
(cd "$REPO_ROOT/angular-frontend" && exec npm start) > "$LOG_DIR/frontend.log" 2>&1 &
FRONTEND_PID=$!

for i in $(seq 1 180); do
  if curl -sf "$FRONTEND_URL" >/dev/null 2>&1; then break; fi
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    tail -30 "$LOG_DIR/frontend.log"
    die "the frontend exited during startup. Full log: $LOG_DIR/frontend.log"
  fi
  [ "$i" -eq 180 ] && die "frontend did not answer in 3 minutes. Log: $LOG_DIR/frontend.log"
  sleep 1
done

cat <<BANNER

  ────────────────────────────────────────────────────────────
   $FRONTEND_URL

   Any password works for these (dev login is on):

     admin@magti.ge      ადმინისტრატორი
     content@magti.ge    კონტენტის რედაქტორი
     manager@magti.ge    მენეჯერი — გუნდის სტატისტიკა
     info@magti.ge       ოპერატორი, საინფორმაციო
     tech@magti.ge       ოპერატორი, ტექნიკური

   logs:  $LOG_DIR/backend.log   $LOG_DIR/frontend.log
   stop:  Ctrl+C
  ────────────────────────────────────────────────────────────

BANNER

wait "$FRONTEND_PID"
