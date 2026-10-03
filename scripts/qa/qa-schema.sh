#!/usr/bin/env bash
# Create or drop the throwaway MAGTI_QA schema on THIS machine's local Oracle.
#
#   scripts/qa/qa-schema.sh create   # drop if present, then create empty
#   scripts/qa/qa-schema.sh drop
#   scripts/qa/qa-schema.sh sql FILE # run any SQL file as SYSDBA in the PDB
#
# Runs scripts/qa_schema_create.sql / qa_schema_drop.sql as OS-authenticated
# SYSDBA inside the ORCLPDB1 container. Never point this at a shared or
# production database: it only ever talks to the local listener.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
case "${1:-}" in
  create) sql="$repo/scripts/qa_schema_create.sql" ;;
  drop)   sql="$repo/scripts/qa_schema_drop.sql" ;;
  sql)    sql="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")" ;;
  *) echo "usage: $0 create|drop|sql FILE" >&2; exit 2 ;;
esac

# sqlplus is a native Windows program: give it Windows paths (cygpath -m),
# and EXIT at the end so a failure cannot leave it waiting at a prompt.
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
{
  echo "WHENEVER SQLERROR EXIT SQL.SQLCODE"
  echo "ALTER SESSION SET CONTAINER=${QA_PDB:-ORCLPDB1};"
  echo "@\"$(win "$sql")\""
  echo "EXIT"
} > "$tmp/run.sql"
# Git Bash would rewrite the leading "/" of "/ as sysdba" into a path.
MSYS_NO_PATHCONV=1 sqlplus -s -L "/ as sysdba" @"$(win "$tmp/run.sql")" < /dev/null
