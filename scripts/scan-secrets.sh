#!/usr/bin/env bash
# Gitleaks must already be available; this entry point never downloads software.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${PYTHON:-python}" "$ROOT/scripts/scan_secrets.py" "$@"
