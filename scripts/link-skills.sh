#!/usr/bin/env sh
# POSIX counterpart of link-skills.ps1, for CI and Linux checkouts.
# See that file for why one copy is tracked and the other is a link.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$root/.claude/skills"
for dir in "$root"/.agents/skills/*/; do
  name=$(basename "$dir")
  link="$root/.claude/skills/$name"
  if [ -e "$link" ]; then echo "ok      $name"; continue; fi
  ln -s "$dir" "$link"
  echo "linked  $name"
done
