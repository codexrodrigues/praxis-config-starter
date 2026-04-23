#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORKSPACE_ROOT="$(cd "$STARTER_ROOT/.." && pwd)"

QUICKSTART_ROOT="${QUICKSTART_ROOT:-$WORKSPACE_ROOT/praxis-api-quickstart}"
BASE_URL="${BASE_URL:-http://localhost:8088}"
BACKEND_URL="${BACKEND_URL:-$BASE_URL}"
ORIGIN="${ORIGIN:-https://praxisui-dev.web.app}"
TENANT_ID="${TENANT_ID:-tenant-federation-smoke}"
ENVIRONMENT="${ENVIRONMENT:-local-e2e}"
SMOKE_RUN_ID="${SMOKE_RUN_ID:-$(date -u +%Y%m%d%H%M%S)}"
ARTIFACTS_DIR="${ARTIFACTS_DIR:-$STARTER_ROOT/artifacts/local-e2e/domain-federation-v01-$(date +%Y%m%d-%H%M%S)}"
QUICKSTART_SMOKE="$QUICKSTART_ROOT/scripts/verify-domain-federation-runtime.sh"

usage() {
  cat >&2 <<'USAGE'
Usage:
  tools/local-e2e/run-domain-federation-v01-local.sh

Examples:
  BASE_URL=http://localhost:8088 tools/local-e2e/run-domain-federation-v01-local.sh
  QUICKSTART_ROOT=../praxis-api-quickstart tools/local-e2e/run-domain-federation-v01-local.sh

This wrapper delegates the live federation verification to the quickstart host
script, because praxis-api-quickstart is the operational reference runtime for
published config-starter contracts.

Requirements:
  - a running quickstart jar that includes DomainFederationController;
  - V21__create_domain_federation_read_model.sql applied in the config DB;
  - praxis.domain-federation.persistence.enabled=true in the runtime;
  - no Flyway action is executed by this wrapper.
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ ! -d "$QUICKSTART_ROOT" ]]; then
  echo "Quickstart root not found: $QUICKSTART_ROOT" >&2
  exit 1
fi
if [[ ! -x "$QUICKSTART_SMOKE" ]]; then
  echo "Quickstart federation smoke not found or not executable: $QUICKSTART_SMOKE" >&2
  echo "Update praxis-api-quickstart to a revision that contains scripts/verify-domain-federation-runtime.sh." >&2
  exit 1
fi
command -v jq >/dev/null 2>&1 || { echo "jq is required." >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl is required." >&2; exit 1; }

mkdir -p "$ARTIFACTS_DIR"

echo "[1/2] health"
test "$(curl -fsS --max-time 10 "$BACKEND_URL/actuator/health" | tee "$ARTIFACTS_DIR/health.json" | jq -r '.status')" = "UP"

echo "[2/2] quickstart federation runtime smoke"
(
  cd "$QUICKSTART_ROOT"
  BACKEND_URL="$BACKEND_URL" \
  ORIGIN="$ORIGIN" \
  TENANT_ID="$TENANT_ID" \
  ENVIRONMENT="$ENVIRONMENT" \
  SMOKE_RUN_ID="$SMOKE_RUN_ID" \
  "$QUICKSTART_SMOKE"
) | tee "$ARTIFACTS_DIR/federation-runtime-smoke.log"

if ! rg -q '"status": "federation-runtime-ready"' "$ARTIFACTS_DIR/federation-runtime-smoke.log"; then
  echo "Federation runtime smoke did not report federation-runtime-ready." >&2
  exit 1
fi

jq -n \
  --arg artifactsDir "$ARTIFACTS_DIR" \
  --arg backendUrl "$BACKEND_URL" \
  --arg tenantId "$TENANT_ID" \
  --arg environment "$ENVIRONMENT" \
  --arg smokeRunId "$SMOKE_RUN_ID" \
  '{
    artifactsDir: $artifactsDir,
    backendUrl: $backendUrl,
    tenantId: $tenantId,
    environment: $environment,
    smokeRunId: $smokeRunId,
    schemaVersion: "praxis.domain-federation/v0.1",
    status: "federation-runtime-ready"
  }' | tee "$ARTIFACTS_DIR/summary.json"
