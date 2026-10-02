#!/usr/bin/env bash
# Shows every screen that a change makes look different, in both themes.
#
#   scripts/visual-diff.sh            # working tree against HEAD
#   scripts/visual-diff.sh main       # working tree against main
#
# Builds the frontend at the given git ref in a temporary worktree, serves it
# on :4202 beside the change's own dev server on :4201, and has
# e2e/visual-compare.spec.ts photograph each screen in both and compare. Both
# builds talk to the SAME backend at the same moment, so data, dates and
# fonts are identical and a difference can only be the change. A screen that
# differs fails, and playwright-report/visual/ holds the before, after and
# difference images.
#
# Why it exists: edits from another AI tool twice broke screens they were not
# meant to touch, and nothing failed until a person happened to look.
#
# Needs, already running (the E2E recipe in .github/workflows/ci.yml):
#   - the backend on :8090 (development login on, a throwaway schema)
#   - the change's frontend: npx ng serve --port 4201 --proxy-config proxy.e2e.conf.json
# and some content to show: an article, a news item and a video the
# tech@magti.ge persona can see.
#
# Frontend only. A change to the backend shows up here only through what the
# screens draw, and both builds draw from the one backend.
set -euo pipefail

REF="${1:-HEAD}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_PORT=4202
# Beside the repository, not under %TEMP%: Vite computes fs.allow from the
# working directory, and from a temp path it refused the build's own public/
# files -- the baseline drew raw translation keys, no fonts and no logo, and
# every screen "differed" (the dev-serve.cmd trap in angular-frontend/AGENTS.md).
WORKTREE="$(dirname "$ROOT")/.magti-visual-base-$$"

git -C "$ROOT" rev-parse --verify --quiet "$REF^{commit}" >/dev/null || { echo "unknown git ref: $REF" >&2; exit 2; }
curl -sf -o /dev/null http://localhost:8090/api/health || { echo "no backend on :8090" >&2; exit 2; }
curl -sf -o /dev/null http://localhost:4201/ || { echo "no frontend on :4201 (the change)" >&2; exit 2; }
# A server already on the baseline port would be compared against instead of
# the ref asked for -- without an error.
if curl -s -o /dev/null "http://localhost:$BASE_PORT/"; then echo "something already serves :$BASE_PORT; stop it first" >&2; exit 2; fi

SERVER_PID=""
cleanup() {
  if [ -n "$SERVER_PID" ]; then
    if command -v taskkill >/dev/null 2>&1; then
      # $! is Git Bash's own process number, which taskkill does not know, so
      # the server is found by the port it holds; /T takes esbuild with it.
      # Killing $! alone left the server up, and every later run compared
      # against that stale build (2026-10-02).
      for pid in $(netstat -ano | awk -v p=":$BASE_PORT" '$2 ~ p"$" && $4 == "LISTENING" {print $5}' | sort -u); do
        taskkill //F //T //PID "$pid" >/dev/null 2>&1 || true
      done
    else
      kill "$SERVER_PID" 2>/dev/null || true
    fi
  fi
  # The link first, so removing the worktree can never reach into the real node_modules.
  local link="$WORKTREE/angular-frontend/node_modules"
  rm -f "$link" 2>/dev/null || MSYS_NO_PATHCONV=1 cmd /c rmdir "$(cygpath -w "$link")" 2>/dev/null || true
  if [ -e "$link" ]; then
    echo "left $WORKTREE in place: its node_modules link would not come off, and removing the worktree through it would empty the real one" >&2
    return
  fi
  cp "$WORKTREE/ng-serve.log" "$ROOT/angular-frontend/test-results/visual-baseline-ng-serve.log" 2>/dev/null || true
  git -C "$ROOT" worktree remove --force "$WORKTREE" 2>/dev/null || true
}
trap cleanup EXIT

git -C "$ROOT" worktree add --detach "$WORKTREE" "$REF" >/dev/null
# The installed packages are borrowed, not reinstalled: a full npm ci per run
# would cost minutes. A ref whose package-lock differs from the working tree's
# may need its own install instead.
if command -v cygpath >/dev/null 2>&1; then
  # MSYS_NO_PATHCONV: Git Bash would otherwise rewrite /J into a path.
  MSYS_NO_PATHCONV=1 cmd /c mklink /J "$(cygpath -w "$WORKTREE/angular-frontend/node_modules")" "$(cygpath -w "$ROOT/angular-frontend/node_modules")" >/dev/null
else
  ln -s "$ROOT/angular-frontend/node_modules" "$WORKTREE/angular-frontend/node_modules"
fi

( cd "$WORKTREE/angular-frontend" && exec npx ng serve --port "$BASE_PORT" --proxy-config proxy.e2e.conf.json ) \
  > "$WORKTREE/ng-serve.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 90); do
  curl -sf -o /dev/null "http://localhost:$BASE_PORT/" && break
  sleep 2
done
curl -sf -o /dev/null "http://localhost:$BASE_PORT/" || { echo "baseline build did not start:" >&2; tail -20 "$WORKTREE/ng-serve.log" >&2; exit 1; }

cd "$ROOT/angular-frontend"
VISUAL_BASELINE_URL="http://localhost:$BASE_PORT" npx playwright test -c playwright.visual.config.ts
