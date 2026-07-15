#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8088}"
ORIGIN="${ORIGIN:-http://localhost:4003}"
TENANT_ID="${TENANT_ID:-agentic-authoring-local-pre-intent}"
USER_ID="${USER_ID:-codex-local}"
ENVIRONMENT="${ENVIRONMENT:-local}"
COMPONENT_TYPE="${COMPONENT_TYPE:-praxis-dynamic-page}"
UPDATED_BY="${UPDATED_BY:-assistant-consistency-gate}"
ARTIFACTS_DIR="${ARTIFACTS_DIR:?ARTIFACTS_DIR is required}"

for command in curl jq awk; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "$command is required." >&2
    exit 1
  fi
done

events_path="$ARTIFACTS_DIR/turn.events.jsonl"
start_request_path="$ARTIFACTS_DIR/turn.start.request.json"
if [[ ! -f "$events_path" || ! -f "$start_request_path" ]]; then
  echo "Turn artifacts are required before transactional persistence proof." >&2
  exit 1
fi

component_id="${COMPONENT_ID:-assistant-consistency:$(jq -r '.clientTurnId // empty' "$start_request_path")}"
if [[ "$component_id" == "assistant-consistency:" ]]; then
  echo "clientTurnId is required to derive an isolated componentId." >&2
  exit 1
fi

urlencode() {
  jq -nr --arg value "$1" '$value | @uri'
}

etag_header() {
  awk '
    tolower(substr($0, 1, 5)) == "etag:" {
      line = $0
      sub(/^[^:]+:[[:space:]]*/, "", line)
      sub(/\r$/, "", line)
      value = line
    }
    END { print value }
  ' "$1"
}

headers=(
  -H "Origin: $ORIGIN"
  -H "Content-Type: application/json"
  -H "X-Tenant-ID: $TENANT_ID"
  -H "X-User-ID: $USER_ID"
  -H "X-Env: $ENVIRONMENT"
)

ui_uri="$BASE_URL/api/praxis/config/ui?componentType=$(urlencode "$COMPONENT_TYPE")&componentId=$(urlencode "$component_id")&scope=user"
apply_uri="$BASE_URL/api/praxis/config/ai/authoring/page-apply"
latest_etag=""
cleanup_complete=false
apply_attempted=false

cleanup_on_exit() {
  if [[ "$cleanup_complete" == "true" || "$apply_attempted" != "true" ]]; then
    return
  fi
  set +e
  if [[ -z "$latest_etag" ]]; then
    curl -sS --max-time 30 -D "$ARTIFACTS_DIR/transaction.cleanup-on-exit.headers" \
      -o /dev/null "$ui_uri" "${headers[@]}" >/dev/null
    latest_etag="$(etag_header "$ARTIFACTS_DIR/transaction.cleanup-on-exit.headers")"
  fi
  if [[ -n "$latest_etag" ]]; then
    curl -sS --max-time 30 -X DELETE "$ui_uri" "${headers[@]}" -H "If-Match: $latest_etag" >/dev/null
  fi
}
trap cleanup_on_exit EXIT

started_at="$(date +%s)"
jq -s 'map(select(.type == "result")) | first | .payload' "$events_path" \
  > "$ARTIFACTS_DIR/transaction.terminal-result.json"
terminal="$ARTIFACTS_DIR/transaction.terminal-result.json"
jq -e '.canApply == true' "$terminal" >/dev/null
jq -e '.preview.compiledFormPatch.patch.page.widgets | type == "array" and length > 0' "$terminal" >/dev/null
jq -e '.intentResolution.semanticDecision.schemaVersion == "praxis-agentic-authoring-semantic-decision.v1"' "$terminal" >/dev/null

jq \
  --arg componentType "$COMPONENT_TYPE" \
  --arg componentId "$component_id" \
  '{
    compiledFormPatch: .preview.compiledFormPatch,
    semanticDecision: .intentResolution.semanticDecision,
    componentType: $componentType,
    componentId: $componentId,
    scope: "user",
    tags: {purpose: "assistant-consistency-transactional-proof"}
  }' "$terminal" > "$ARTIFACTS_DIR/transaction.apply.request.json"

initial_status="$(curl -sS --max-time 30 -o "$ARTIFACTS_DIR/transaction.initial-get.response.json" \
  -w '%{http_code}' "$ui_uri" "${headers[@]}")"
test "$initial_status" = "404"

apply_attempted=true
first_apply_status="$(curl -sS --max-time 60 \
  -D "$ARTIFACTS_DIR/transaction.apply-1.headers" \
  -o "$ARTIFACTS_DIR/transaction.apply-1.response.json" \
  -w '%{http_code}' \
  -X POST "$apply_uri" "${headers[@]}" -H "X-Updated-By: $UPDATED_BY" \
  --data-binary @"$ARTIFACTS_DIR/transaction.apply.request.json")"
test "$first_apply_status" = "200"
jq -e '.applied == true and .scope == "user"' "$ARTIFACTS_DIR/transaction.apply-1.response.json" >/dev/null
first_version="$(jq -r '.version' "$ARTIFACTS_DIR/transaction.apply-1.response.json")"
test "$first_version" = "1"
first_etag_raw="$(jq -r '.etag // empty' "$ARTIFACTS_DIR/transaction.apply-1.response.json")"
first_etag="$(etag_header "$ARTIFACTS_DIR/transaction.apply-1.headers")"
test -n "$first_etag_raw"
test "$first_etag" = "\"$first_etag_raw\""
latest_etag="$first_etag"

first_get_status="$(curl -sS --max-time 30 \
  -D "$ARTIFACTS_DIR/transaction.readback-1.headers" \
  -o "$ARTIFACTS_DIR/transaction.readback-1.response.json" \
  -w '%{http_code}' "$ui_uri" "${headers[@]}")"
test "$first_get_status" = "200"
test "$(etag_header "$ARTIFACTS_DIR/transaction.readback-1.headers")" = "$first_etag"
test "$(jq -r '.version' "$ARTIFACTS_DIR/transaction.readback-1.response.json")" = "$first_version"
expected_page="$(jq -S -c '.preview.compiledFormPatch.patch.page' "$terminal")"
first_page="$(jq -S -c '.payload' "$ARTIFACTS_DIR/transaction.readback-1.response.json")"
test "$first_page" = "$expected_page"
expected_widget_count="$(jq -r '.preview.compiledFormPatch.patch.page.widgets | length' "$terminal")"
first_widget_count="$(jq -r '.payload.widgets | length' "$ARTIFACTS_DIR/transaction.readback-1.response.json")"
test "$first_widget_count" = "$expected_widget_count"

second_apply_status="$(curl -sS --max-time 60 \
  -D "$ARTIFACTS_DIR/transaction.apply-2.headers" \
  -o "$ARTIFACTS_DIR/transaction.apply-2.response.json" \
  -w '%{http_code}' \
  -X POST "$apply_uri" "${headers[@]}" -H "X-Updated-By: $UPDATED_BY" -H "If-Match: $first_etag" \
  --data-binary @"$ARTIFACTS_DIR/transaction.apply.request.json")"
test "$second_apply_status" = "200"
jq -e '.applied == true' "$ARTIFACTS_DIR/transaction.apply-2.response.json" >/dev/null
second_version="$(jq -r '.version' "$ARTIFACTS_DIR/transaction.apply-2.response.json")"
test "$second_version" = "$((first_version + 1))"
second_etag_raw="$(jq -r '.etag // empty' "$ARTIFACTS_DIR/transaction.apply-2.response.json")"
second_etag="$(etag_header "$ARTIFACTS_DIR/transaction.apply-2.headers")"
test -n "$second_etag_raw"
test "$second_etag" = "\"$second_etag_raw\""
test "$second_etag" != "$first_etag"
latest_etag="$second_etag"

second_get_status="$(curl -sS --max-time 30 \
  -D "$ARTIFACTS_DIR/transaction.readback-2.headers" \
  -o "$ARTIFACTS_DIR/transaction.readback-2.response.json" \
  -w '%{http_code}' "$ui_uri" "${headers[@]}")"
test "$second_get_status" = "200"
test "$(etag_header "$ARTIFACTS_DIR/transaction.readback-2.headers")" = "$second_etag"
test "$(jq -r '.version' "$ARTIFACTS_DIR/transaction.readback-2.response.json")" = "$second_version"
second_page="$(jq -S -c '.payload' "$ARTIFACTS_DIR/transaction.readback-2.response.json")"
test "$second_page" = "$expected_page"
second_widget_count="$(jq -r '.payload.widgets | length' "$ARTIFACTS_DIR/transaction.readback-2.response.json")"
test "$second_widget_count" = "$expected_widget_count"

stale_status="$(curl -sS --max-time 60 \
  -o "$ARTIFACTS_DIR/transaction.stale-retry.response.txt" \
  -w '%{http_code}' \
  -X POST "$apply_uri" "${headers[@]}" -H "X-Updated-By: $UPDATED_BY" -H "If-Match: $first_etag" \
  --data-binary @"$ARTIFACTS_DIR/transaction.apply.request.json")"
test "$stale_status" = "412"

after_stale_status="$(curl -sS --max-time 30 \
  -D "$ARTIFACTS_DIR/transaction.after-stale.headers" \
  -o "$ARTIFACTS_DIR/transaction.after-stale.response.json" \
  -w '%{http_code}' "$ui_uri" "${headers[@]}")"
test "$after_stale_status" = "200"
test "$(etag_header "$ARTIFACTS_DIR/transaction.after-stale.headers")" = "$second_etag"
test "$(jq -r '.version' "$ARTIFACTS_DIR/transaction.after-stale.response.json")" = "$second_version"
test "$(jq -S -c '.payload' "$ARTIFACTS_DIR/transaction.after-stale.response.json")" = "$expected_page"

delete_status="$(curl -sS --max-time 30 \
  -o "$ARTIFACTS_DIR/transaction.delete.response.txt" \
  -w '%{http_code}' \
  -X DELETE "$ui_uri" "${headers[@]}" -H "If-Match: $second_etag")"
test "$delete_status" = "204"
cleanup_complete=true
latest_etag=""

after_delete_status="$(curl -sS --max-time 30 \
  -o "$ARTIFACTS_DIR/transaction.after-delete.response.json" \
  -w '%{http_code}' "$ui_uri" "${headers[@]}")"
test "$after_delete_status" = "404"
duration_seconds="$(( $(date +%s) - started_at ))"

jq -n \
  --arg componentType "$COMPONENT_TYPE" \
  --arg componentId "$component_id" \
  --argjson initialVersion "$first_version" \
  --argjson replayVersion "$second_version" \
  --argjson widgetCount "$expected_widget_count" \
  --argjson durationSeconds "$duration_seconds" \
  '{
    applied: true,
    componentType: $componentType,
    componentId: $componentId,
    exactReadback: true,
    conditionalReplayApplied: true,
    replayStateExact: true,
    widgetCountStable: true,
    staleRetryBlocked: true,
    cleanupDeleted: true,
    initialVersion: $initialVersion,
    replayVersion: $replayVersion,
    widgetCount: $widgetCount,
    durationSeconds: $durationSeconds
  }' | tee "$ARTIFACTS_DIR/transaction-summary.json"
