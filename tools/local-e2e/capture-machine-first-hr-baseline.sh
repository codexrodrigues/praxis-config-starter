#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORKSPACE_ROOT="$(cd "$STARTER_ROOT/.." && pwd)"

BACKEND_URL="${BACKEND_URL:-http://localhost:8088}"
QUICKSTART_ROOT="${QUICKSTART_ROOT:-$WORKSPACE_ROOT/praxis-api-quickstart}"
GROUP="${GROUP:-human-resources}"
TENANT_ID="${TENANT_ID:-desenv}"
ENVIRONMENT="${ENVIRONMENT:-local}"
USER_ID="${USER_ID:-codex-e2e}"
ORIGIN="${ORIGIN:-http://localhost:4003}"

for command in curl git jq perl; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing required command: $command" >&2
    exit 2
  fi
done

if [[ ! -f "$QUICKSTART_ROOT/pom.xml" ]]; then
  echo "Quickstart pom not found: $QUICKSTART_ROOT/pom.xml" >&2
  exit 2
fi

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/praxis-machine-first-hr.XXXXXX")"
catalog_file="$tmp_dir/domain-catalog.json"
releases_file="$tmp_dir/domain-catalog-releases.json"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

urlencode() {
  jq -nr --arg value "$1" '$value | @uri'
}

encoded_group="$(urlencode "$GROUP")"
curl -fsS "${BACKEND_URL%/}/schemas/domain?group=${encoded_group}" -o "$catalog_file"

if ! jq -e '
    .schemaVersion == "praxis.domain-catalog/v0.2"
    and (.service.serviceKey | type == "string" and length > 0)
    and (.release.releaseKey | type == "string" and length > 0)
    and (.release.sourceHash | type == "string" and length == 64)
    and (.contexts | type == "array")
    and (.nodes | type == "array")
    and (.edges | type == "array")
    and (.bindings | type == "array")
    and (.aliases | type == "array")
    and (.evidence | type == "array")
    and (.governance | type == "array")
  ' "$catalog_file" >/dev/null; then
  echo "The live domain catalog does not satisfy the baseline envelope." >&2
  jq '{schemaVersion, service, release, keys: keys}' "$catalog_file" >&2
  exit 1
fi

service_key="$(jq -r '.service.serviceKey' "$catalog_file")"
encoded_service_key="$(urlencode "$service_key")"
config_store_available=true
if ! curl -fsS \
    "${BACKEND_URL%/}/api/praxis/config/domain-catalog/releases?serviceKey=${encoded_service_key}&limit=50" \
    -H "Origin: ${ORIGIN}" \
    -H "X-Tenant-ID: ${TENANT_ID}" \
    -H "X-User-ID: ${USER_ID}" \
    -H "X-Env: ${ENVIRONMENT}" \
    -o "$releases_file"; then
  config_store_available=false
  printf '[]' > "$releases_file"
elif ! jq -e 'type == "array"' "$releases_file" >/dev/null; then
  config_store_available=false
  printf '[]' > "$releases_file"
fi

quickstart_commit="$(git -C "$QUICKSTART_ROOT" rev-parse HEAD)"
quickstart_version="$(perl -0777 -ne 'print $1 if /<artifactId>praxis-api-quickstart<\/artifactId>\s*<version>([^<]+)<\/version>/s' "$QUICKSTART_ROOT/pom.xml")"
metadata_version="$(perl -0777 -ne 'print $1 if /<praxis\.core\.version>([^<]+)<\/praxis\.core\.version>/s' "$QUICKSTART_ROOT/pom.xml")"
config_version="$(perl -0777 -ne 'print $1 if /<praxis\.config\.version>([^<]+)<\/praxis\.config\.version>/s' "$QUICKSTART_ROOT/pom.xml")"
captured_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

jq -n \
  --arg captured_at "$captured_at" \
  --arg backend_url "$BACKEND_URL" \
  --arg group "$GROUP" \
  --arg quickstart_commit "$quickstart_commit" \
  --arg quickstart_version "$quickstart_version" \
  --arg metadata_version "$metadata_version" \
  --arg config_version "$config_version" \
  --argjson config_store_available "$config_store_available" \
  --slurpfile catalog "$catalog_file" \
  --slurpfile releases "$releases_file" '
    def counts_by($values; $name):
      $values
      | group_by(.)
      | map({($name): .[0], count: length});

    ($catalog[0]) as $c
    | ($releases[0]) as $stored
    | {
        schemaVersion: "praxis.machine-first-hr-baseline/v0.1",
        capturedAt: $captured_at,
        source: {
          backendUrl: $backend_url,
          group: $group,
          endpoint: ("/schemas/domain?group=" + $group),
          httpMode: "read-only"
        },
        runtime: {
          quickstartCommit: $quickstart_commit,
          quickstartVersion: $quickstart_version,
          metadataStarterVersion: $metadata_version,
          configStarterVersion: $config_version
        },
        catalog: {
          schemaVersion: $c.schemaVersion,
          service: $c.service,
          release: $c.release,
          counts: {
            contexts: ($c.contexts | length),
            nodes: ($c.nodes | length),
            edges: ($c.edges | length),
            bindings: ($c.bindings | length),
            aliases: ($c.aliases | length),
            evidence: ($c.evidence | length),
            governance: ($c.governance | length)
          },
          vocabulary: {
            nodeTypes: counts_by([$c.nodes[].nodeType]; "nodeType"),
            edgeTypes: counts_by([$c.edges[].edgeType]; "edgeType"),
            bindingTypes: counts_by([$c.bindings[].bindingType]; "bindingType"),
            evidenceTypes: counts_by([$c.evidence[].evidenceType]; "evidenceType"),
            governanceSources: counts_by([$c.governance[].source]; "source")
          }
        },
        coverage: {
          contextsWithDescription: ([$c.contexts[] | select((.description // "") != "")] | length),
          contextsWithOwner: ([$c.contexts[] | select((.owner // "") != "")] | length),
          nodesWithDescription: ([$c.nodes[] | select((.description // "") != "")] | length),
          nodesWithoutDescription: ([$c.nodes[] | select((.description // "") == "")] | length),
          conceptsWithoutDescription: ([$c.nodes[] | select(.nodeType == "concept" and ((.description // "") == ""))] | length),
          fieldsWithoutDescription: ([$c.nodes[] | select(.nodeType == "field" and ((.description // "") == ""))] | length),
          nodesWithOwner: ([$c.nodes[] | select((.owner // "") != "")] | length),
          governanceWithOwnerOrSteward: ([$c.governance[] | select((.owner // "") != "" or (.steward // "") != "")] | length),
          heuristicGovernance: ([$c.governance[] | select(.source == "dto-field-heuristic")] | length),
          explicitGovernance: ([$c.governance[] | select(.source != "dto-field-heuristic")] | length)
        },
        configStore: {
          available: $config_store_available,
          releasesInspected: ($stored | length),
          exactReleaseAlreadyPersisted: (any($stored[]; .releaseKey == $c.release.releaseKey))
        },
        validation: {
          sourceEnvelope: "pass",
          databaseMutationPerformed: false
        }
      }
  '
