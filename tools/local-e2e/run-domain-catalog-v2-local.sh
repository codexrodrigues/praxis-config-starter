#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8088}"
ORIGIN="${ORIGIN:-http://localhost:4003}"
TENANT_ID="${TENANT_ID:-domain-catalog-v2-local-e2e}"
ENVIRONMENT="${ENVIRONMENT:-local}"
RESOURCE_KEY="${RESOURCE_KEY:-human-resources.folhas-pagamento}"
ARTIFACTS_DIR="${ARTIFACTS_DIR:-$STARTER_ROOT/artifacts/local-e2e/domain-catalog-v2-$(date +%Y%m%d-%H%M%S)}"

mkdir -p "$ARTIFACTS_DIR"
command -v jq >/dev/null 2>&1 || { echo "jq is required." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is required." >&2; exit 1; }

urlencode() {
  python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"
}

headers=(-H "Origin: $ORIGIN" -H "X-Tenant-ID: $TENANT_ID" -H "X-Env: $ENVIRONMENT")
json_headers=(-H "Origin: $ORIGIN" -H "Content-Type: application/json" -H "X-Tenant-ID: $TENANT_ID" -H "X-Env: $ENVIRONMENT")

echo "[1/6] health"
test "$(curl -fsS --max-time 10 "$BASE_URL/actuator/health" | tee "$ARTIFACTS_DIR/health.json" | jq -r '.status')" = "UP"

echo "[2/6] projected schema"
catalog="$(curl -fsS --max-time 60 "$BASE_URL/schemas/domain?resourceKey=$(urlencode "$RESOURCE_KEY")" -H "Origin: $ORIGIN" | tee "$ARTIFACTS_DIR/catalog.json")"
test "$(jq -r '.schemaVersion' <<<"$catalog")" = "praxis.domain-catalog/v0.2"
test "$(jq '.aliases | length' <<<"$catalog")" -ge 1
service_key="$(jq -r '.service.serviceKey' <<<"$catalog")"
test -n "$service_key"

echo "[3/6] ingest"
printf '%s' "$catalog" | curl -fsS --max-time 90 -X POST "$BASE_URL/api/praxis/config/domain-catalog/ingest" "${json_headers[@]}" --data-binary @- | tee "$ARTIFACTS_DIR/ingest.json" >/dev/null

echo "[4/6] contexts"
encoded_service="$(urlencode "$service_key")"
curl -fsS --max-time 60 "$BASE_URL/api/praxis/config/domain-catalog/context?serviceKey=$encoded_service&type=node&nodeType=field&q=salario&limit=10" "${headers[@]}" | tee "$ARTIFACTS_DIR/node-context.json" >/dev/null
curl -fsS --max-time 60 "$BASE_URL/api/praxis/config/domain-catalog/context?serviceKey=$encoded_service&type=alias&q=salario&limit=10" "${headers[@]}" | tee "$ARTIFACTS_DIR/alias-context.json" >/dev/null
curl -fsS --max-time 60 "$BASE_URL/api/praxis/config/domain-catalog/context?serviceKey=$encoded_service&type=governance&limit=5" "${headers[@]}" | tee "$ARTIFACTS_DIR/governance-context.json" >/dev/null

echo "[5/6] relationships"
curl -fsS --max-time 60 "$BASE_URL/api/praxis/config/domain-catalog/relationships/latest?serviceKey=$encoded_service&limit=10" "${headers[@]}" | tee "$ARTIFACTS_DIR/relationships.json" >/dev/null

node_count="$(jq '.items | length' "$ARTIFACTS_DIR/node-context.json")"
alias_count="$(jq '.items | length' "$ARTIFACTS_DIR/alias-context.json")"
governance_count="$(jq '.items | length' "$ARTIFACTS_DIR/governance-context.json")"
relationship_count="$(jq 'length' "$ARTIFACTS_DIR/relationships.json")"
semantic_count="$(jq '[.items[] | select(.payload.semanticOwner or .payload.lifecycle or .payload.businessGlossary or .payload.resolution or .payload.sourceEvidenceKeys)] | length' "$ARTIFACTS_DIR/node-context.json")"

test "$node_count" -ge 1
test "$alias_count" -ge 1
test "$governance_count" -ge 1
test "$relationship_count" -ge 1
test "$semantic_count" -ge 1

echo "[6/6] summary"
jq -n --arg artifactsDir "$ARTIFACTS_DIR" --arg serviceKey "$service_key" --arg resourceKey "$RESOURCE_KEY" \
  --argjson nodeCount "$node_count" --argjson aliasCount "$alias_count" --argjson governanceCount "$governance_count" \
  --argjson relationshipCount "$relationship_count" --argjson semanticCount "$semantic_count" \
  '{artifactsDir:$artifactsDir, serviceKey:$serviceKey, resourceKey:$resourceKey, catalogSchemaVersion:"praxis.domain-catalog/v0.2", nodeCount:$nodeCount, aliasCount:$aliasCount, governanceCount:$governanceCount, relationshipCount:$relationshipCount, semanticNodeCount:$semanticCount}' \
  | tee "$ARTIFACTS_DIR/summary.json"
