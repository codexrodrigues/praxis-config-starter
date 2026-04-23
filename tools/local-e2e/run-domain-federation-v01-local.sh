#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8088}"
ORIGIN="${ORIGIN:-http://localhost:4003}"
TENANT_ID="${TENANT_ID:-domain-federation-v01-local-e2e}"
ENVIRONMENT="${ENVIRONMENT:-local}"
SERVICE_KEY="${SERVICE_KEY:-praxis-service}"
RESOURCE_KEY="${RESOURCE_KEY:-human-resources.folhas-pagamento}"
ARTIFACTS_DIR="${ARTIFACTS_DIR:-$STARTER_ROOT/artifacts/local-e2e/domain-federation-v01-$(date +%Y%m%d-%H%M%S)}"

mkdir -p "$ARTIFACTS_DIR"
command -v jq >/dev/null 2>&1 || { echo "jq is required." >&2; exit 1; }

headers=(-H "Origin: $ORIGIN" -H "X-Tenant-ID: $TENANT_ID" -H "X-Env: $ENVIRONMENT")
json_headers=(-H "Origin: $ORIGIN" -H "Content-Type: application/json" -H "X-Tenant-ID: $TENANT_ID" -H "X-Env: $ENVIRONMENT")

echo "[1/5] health"
test "$(curl -fsS --max-time 10 "$BASE_URL/actuator/health" | tee "$ARTIFACTS_DIR/health.json" | jq -r '.status')" = "UP"

endpoint_status="$(curl -sS --max-time 10 -o "$ARTIFACTS_DIR/endpoint-probe.txt" -w '%{http_code}' \
  -X POST "$BASE_URL/api/praxis/config/domain-federation/dry-run" \
  "${json_headers[@]}" \
  --data-binary '{}')"
if [[ "$endpoint_status" = "404" ]]; then
  echo "Domain federation endpoint is not available in the running backend jar." >&2
  echo "Package praxis-api-quickstart with a praxis-config-starter build that contains DomainFederationController, then rerun this script." >&2
  exit 2
fi

echo "[2/6] domain catalog bootstrap"
catalog="$(curl -fsS --max-time 60 "$BASE_URL/schemas/domain?resourceKey=$RESOURCE_KEY" -H "Origin: $ORIGIN" | tee "$ARTIFACTS_DIR/catalog.json")"
test "$(jq -r '.schemaVersion' <<<"$catalog")" = "praxis.domain-catalog/v0.2"
printf '%s' "$catalog" | curl -fsS --max-time 90 -X POST "$BASE_URL/api/praxis/config/domain-catalog/ingest" \
  "${json_headers[@]}" \
  --data-binary @- \
  | tee "$ARTIFACTS_DIR/catalog-ingest.response.json" >/dev/null

echo "[3/6] federation request"
jq -n \
  --arg tenantId "$TENANT_ID" \
  --arg environment "$ENVIRONMENT" \
  --arg serviceKey "$SERVICE_KEY" \
  '{
    schemaVersion: "praxis.domain-federation/v0.1",
    tenantId: $tenantId,
    environment: $environment,
    sources: [
      {
        sourceKey: "praxis-api-quickstart",
        sourceType: "microservice",
        serviceKey: $serviceKey,
        serviceName: "Praxis API Quickstart",
        tenantId: $tenantId,
        environment: $environment,
        semanticOwner: "Architecture",
        technicalOwner: "Platform",
        trustLevel: "authoritative",
        status: "active",
        latestReleaseKey: "domain-catalog:praxis-service:latest"
      }
    ],
    contexts: [
      {
        contextKey: "human-resources",
        sourceKey: "praxis-api-quickstart",
        contextType: "bounded_context",
        label: "Human Resources",
        description: "People, employment lifecycle and payroll concepts.",
        semanticOwner: "RH",
        technicalOwner: "Platform",
        tenantId: $tenantId,
        environment: $environment,
        status: "active",
        latestReleaseKey: "domain-catalog:human-resources:latest"
      },
      {
        contextKey: "assets",
        sourceKey: "praxis-api-quickstart",
        contextType: "bounded_context",
        label: "Assets",
        description: "Vehicles, equipment and asset allocation concepts.",
        semanticOwner: "Operations",
        technicalOwner: "Platform",
        tenantId: $tenantId,
        environment: $environment,
        status: "active",
        latestReleaseKey: "domain-catalog:assets:latest"
      }
    ],
    contracts: [
      {
        contractKey: "assets.vehicle-allocation.lookup.v1",
        contractType: "lookup_option_source",
        providerSourceKey: "praxis-api-quickstart",
        providerContextKey: "assets",
        consumerContextKey: "human-resources",
        resourceKey: "assets.veiculos",
        operationKey: "vehicleAllocationLookup",
        schemaRef: "openapi://praxis-api-quickstart/api/assets/veiculos",
        compatibility: "stable",
        visibility: "internal",
        status: "active"
      }
    ],
    contextRelationships: [
      {
        relationshipKey: "human-resources.funcionarios.references.assets.veiculos",
        sourceContextKey: "human-resources",
        targetContextKey: "assets",
        relationshipType: "references",
        contractKey: "assets.vehicle-allocation.lookup.v1",
        direction: "source_to_target",
        ownership: "target_owned",
        confidence: 0.91,
        status: "active"
      }
    ],
    resolutions: [
      {
        resolutionKey: "hr.allocated_vehicle.maps_to.assets.vehicle",
        sourceConceptKey: "human-resources.funcionario.veiculo_alocado",
        targetConceptKey: "assets.veiculo",
        sourceContextKey: "human-resources",
        targetContextKey: "assets",
        resolutionType: "maps_to",
        confidence: 0.9,
        status: "approved",
        reviewOwner: "Architecture"
      }
    ]
  }' > "$ARTIFACTS_DIR/request.json"

echo "[4/6] dry-run validation"
curl -fsS --max-time 90 -X POST "$BASE_URL/api/praxis/config/domain-federation/dry-run" \
  "${json_headers[@]}" \
  --data-binary @"$ARTIFACTS_DIR/request.json" \
  | tee "$ARTIFACTS_DIR/dry-run.response.json" >/dev/null
test "$(jq -r '.valid' "$ARTIFACTS_DIR/dry-run.response.json")" = "true"
test "$(jq -r '.errorCount' "$ARTIFACTS_DIR/dry-run.response.json")" = "0"

echo "[5/6] ingest dry-run preview"
curl -fsS --max-time 90 -X POST "$BASE_URL/api/praxis/config/domain-federation/ingest?dryRun=true" \
  "${json_headers[@]}" \
  --data-binary @"$ARTIFACTS_DIR/request.json" \
  | tee "$ARTIFACTS_DIR/ingest-dry-run.response.json" >/dev/null
test "$(jq -r '.dryRun' "$ARTIFACTS_DIR/ingest-dry-run.response.json")" = "true"
test "$(jq -r '.valid' "$ARTIFACTS_DIR/ingest-dry-run.response.json")" = "true"
test "$(jq -r '.previewCount' "$ARTIFACTS_DIR/ingest-dry-run.response.json")" -ge 1

echo "[6/6] federated context query"
curl -fsS --max-time 90 \
  "$BASE_URL/api/praxis/config/domain-federation/context?itemType=node&q=salario&limit=10&policyProfile=authoring&minConfidence=0.80" \
  "${headers[@]}" \
  | tee "$ARTIFACTS_DIR/context.response.json" >/dev/null
test "$(jq -r '.schemaVersion' "$ARTIFACTS_DIR/context.response.json")" = "praxis.domain-federation-context/v0.1"
test "$(jq -r '.federated' "$ARTIFACTS_DIR/context.response.json")" = "true"
jq -e '.retrievalGuidance | index("Contracts, resolutions and final redaction decisions are validated separately and are not yet materialized in query results.") != null' \
  "$ARTIFACTS_DIR/context.response.json" >/dev/null

jq -n \
  --arg artifactsDir "$ARTIFACTS_DIR" \
  --arg serviceKey "$SERVICE_KEY" \
  --arg resourceKey "$RESOURCE_KEY" \
  --arg tenantId "$TENANT_ID" \
  --arg environment "$ENVIRONMENT" \
  --argjson dryRunIssueCount "$(jq '.issues | length' "$ARTIFACTS_DIR/dry-run.response.json")" \
  --argjson previewCount "$(jq '.previewCount' "$ARTIFACTS_DIR/ingest-dry-run.response.json")" \
  --argjson contextItemCount "$(jq '.context.items | length // 0' "$ARTIFACTS_DIR/context.response.json")" \
  --argjson relationshipCount "$(jq '.relationships | length // 0' "$ARTIFACTS_DIR/context.response.json")" \
  '{
    artifactsDir: $artifactsDir,
    serviceKey: $serviceKey,
    resourceKey: $resourceKey,
    tenantId: $tenantId,
    environment: $environment,
    schemaVersion: "praxis.domain-federation/v0.1",
    dryRunValid: true,
    dryRunIssueCount: $dryRunIssueCount,
    ingestDryRunValid: true,
    previewCount: $previewCount,
    contextFederated: true,
    contextItemCount: $contextItemCount,
    relationshipCount: $relationshipCount
  }' | tee "$ARTIFACTS_DIR/summary.json"
