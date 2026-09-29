#!/bin/sh
#
# Put enough content in a fresh database to have something to look at.
#
# Flyway creates the schema and nothing puts content in it; users are
# provisioned just-in-time on first login. A brand new database therefore
# renders a working but completely empty portal, which is not much use for
# looking around.
#
#   ./scripts/seed-demo-content.sh [BASE_URL]      default http://localhost:8080
#
# Skipped entirely if any article already exists, so running it twice is safe.
#
# POSIX sh, curl, and busybox grep/cut only -- no bash. It runs both from
# run-local.sh on a developer's machine and inside a curlimages/curl container
# as the `seed` service of docker-compose.local.yml, and that image has no
# bash. Keep it that way.

set -eu

BASE="${1:-http://localhost:8080}"

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

# The dev login accepts any password for the seeded accounts, which is the
# whole reason this can seed over HTTP without a credential (SEC-01). It also
# means this script is only ever correct against a local, throwaway instance.
TOKEN="$(curl -sf -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"email":"admin@magti.ge","password":"x"}' \
  | grep -o '"access_token":"[^"]*"' | head -1 | cut -d'"' -f4)"

if [ -z "$TOKEN" ]; then
  echo "could not log in at $BASE -- is the backend up, and is ALLOW_DEV_LOGIN=true?" >&2
  exit 1
fi

# "does any article exist" rather than "is the body exactly []", so
# pretty-printing or a changed envelope cannot turn this into a duplicate seed
# on every start.
if curl -sf "$BASE/api/articles" -H "Authorization: Bearer $TOKEN" | grep -q '"id"'; then
  say "content already present, not seeding"
  exit 0
fi

say "seeding demo content"

api() {
  curl -sf -X POST "$BASE$1" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "$2"
}

# First "id" in the response, not the last: a greedy match would pick up an id
# from a nested object if the shape ever grows one.
id_of() { grep -o '"id":[0-9]*' | head -1 | cut -d: -f2; }

CAT_NET="$(api /api/categories '{"name":"ინტერნეტი და ქსელი","parent_id":null}' | id_of)"
CAT_TARIFF="$(api /api/categories '{"name":"ტარიფები","parent_id":null}' | id_of)"

article() {
  api /api/articles "{\"title\":$1,\"content\":$2,\"category_id\":$3,\"tags\":null,
 \"target_departments\":[\"საინფორმაციო\",\"ტექნიკური\"],\"status\":\"published\",
 \"published_at\":null,\"attachment_url\":null,\"audience_profile\":\"all\",
 \"visible_to_tech_info\":true,\"visible_to_service_center\":false,
 \"is_draft\":false,\"quiz_enabled\":false}" > /dev/null
}

article '"ინტერნეტი არ მუშაობს — პირველი ნაბიჯები"' \
        '"<p>შეამოწმეთ ტერმინალის ინდიკატორები, გადატვირთეთ როუტერი, დააფიქსირეთ ხაზის სტატუსი.</p>"' "$CAT_NET"
article '"GPON ტერმინალის დიაგნოსტიკა"' \
        '"<p>LOS ინდიკატორი, სიგნალის დონე, ოპტიკური ხაზის შემოწმების თანმიმდევრობა.</p>"' "$CAT_NET"
article '"Wi-Fi სიჩქარე დაბალია"' \
        '"<p>არხის დატვირთვა, 2.4 vs 5 GHz, ტერმინალის განთავსება.</p>"' "$CAT_NET"
article '"სატარიფო გეგმის შეცვლა"' \
        '"<p>რა პირობებით იცვლება გეგმა და როდის ამოქმედდება ცვლილება.</p>"' "$CAT_TARIFF"
article '"დამატებითი პაკეტების აქტივაცია"' \
        '"<p>აქტივაციის არხები და მოქმედების ვადები.</p>"' "$CAT_TARIFF"

# is_draft explicitly false: the create endpoint defaults it to false while
# Python's schema defaulted it to true (NewsRequest.java:29-34), and a seed
# that leaned on the default would be seeding that quirk rather than a news item.
api /api/news '{"title":"ახალი სატარიფო გეგმები 1 სექტემბრიდან","content":"<p>დეტალები ცალკე ინსტრუქციაში.</p>","target_department":"All","attachment_url":null,"visible_to_tech_info":true,"visible_to_service_center":true,"expires_at":null,"is_draft":false}' > /dev/null
api /api/news '{"title":"გეგმიური სამუშაოები ქსელზე","content":"<p>შესაძლო შეფერხებები ღამის საათებში.</p>","target_department":"All","attachment_url":null,"visible_to_tech_info":true,"visible_to_service_center":true,"expires_at":null,"is_draft":false}' > /dev/null

say "seeded 2 categories, 5 articles, 2 news items"
