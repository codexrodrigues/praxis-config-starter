#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORKSPACE_ROOT="$(cd "$STARTER_ROOT/.." && pwd)"

QUICKSTART_ROOT="${QUICKSTART_ROOT:-$WORKSPACE_ROOT/praxis-api-quickstart}"
BASE_URL="${BASE_URL:-${BACKEND_URL:-http://localhost:8088}}"
TENANT_ID="${TENANT_ID:-desenv}"
ENVIRONMENT="${ENVIRONMENT:-local}"

usage() {
  cat >&2 <<'USAGE'
Usage:
  tools/local-e2e/run-domain-knowledge-change-set-local.sh

Examples:
  BASE_URL=http://localhost:8088 tools/local-e2e/run-domain-knowledge-change-set-local.sh
  TENANT_ID=desenv ENVIRONMENT=local tools/local-e2e/run-domain-knowledge-change-set-local.sh

This wrapper delegates to the quickstart runtime smoke because
praxis-api-quickstart is the operational reference host for config-starter
contracts. Start the canonical local quickstart first, packaged against the
local starter, then run this wrapper.
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ ! -d "$QUICKSTART_ROOT" ]]; then
  echo "Quickstart root not found: $QUICKSTART_ROOT" >&2
  exit 2
fi

SMOKE_SCRIPT="$QUICKSTART_ROOT/scripts/verify-domain-knowledge-change-set-runtime.sh"
if [[ ! -x "$SMOKE_SCRIPT" ]]; then
  echo "Quickstart Domain Knowledge change-set smoke not found or not executable: $SMOKE_SCRIPT" >&2
  echo "Update praxis-api-quickstart to a revision that contains scripts/verify-domain-knowledge-change-set-runtime.sh." >&2
  exit 2
fi

BACKEND_URL="$BASE_URL" \
TENANT_ID="$TENANT_ID" \
ENVIRONMENT="$ENVIRONMENT" \
"$SMOKE_SCRIPT"
