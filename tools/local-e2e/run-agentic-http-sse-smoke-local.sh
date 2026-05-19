#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$STARTER_ROOT/.env.openai.local.sh}"
BASE_URL="${BASE_URL:-http://localhost:8088}"
ORIGIN="${ORIGIN:-http://localhost:4003}"
TENANT_ID="${TENANT_ID:-agentic-authoring-local-e2e}"
USER_ID="${USER_ID:-codex-local}"
ENVIRONMENT="${ENVIRONMENT:-local}"
PROVIDER="${PROVIDER:-openai}"
ARTIFACTS_DIR="${ARTIFACTS_DIR:-$STARTER_ROOT/artifacts/local-e2e/agentic-http-sse-$PROVIDER-$(date +%Y%m%d-%H%M%S)}"

mkdir -p "$ARTIFACTS_DIR"

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required." >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required." >&2
  exit 1
fi

set -a
eval "$(LC_ALL=C perl -pe 's/^\xEF\xBB\xBF// if $. == 1; s/\r$//;' "$ENV_FILE" | sed '/^#!/d')" >/dev/null
set +a

if [[ "$PROVIDER" == "openai" ]]; then
  MODEL="${PRAXIS_AI_OPENAI_MODEL:-gpt-5-mini}"
  [[ -n "${PRAXIS_AI_OPENAI_API_KEY:-}" ]] || { echo "PRAXIS_AI_OPENAI_API_KEY is required." >&2; exit 1; }
elif [[ "$PROVIDER" == "gemini" ]]; then
  MODEL="${PRAXIS_AI_GEMINI_MODEL:-gemini-2.5-flash}"
  [[ -n "${PRAXIS_AI_GEMINI_API_KEY:-}" ]] || { echo "PRAXIS_AI_GEMINI_API_KEY is required." >&2; exit 1; }
else
  echo "Unsupported PROVIDER=$PROVIDER" >&2
  exit 1
fi

urlencode() {
  python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"
}

headers=(-H "Origin: $ORIGIN" -H "Content-Type: application/json" -H "X-Tenant-ID: $TENANT_ID" -H "X-User-ID: $USER_ID" -H "X-Env: $ENVIRONMENT")
prompt="Crie um formulario didatico so com os campos realmente necessarios para cadastrar incidentes de missao operacionais. Use a fonte Incidentes de Missao."

echo "[1/7] health"
test "$(curl -fsS --max-time 10 "$BASE_URL/actuator/health" | tee "$ARTIFACTS_DIR/health.json" | jq -r '.status')" = "UP"

echo "[2/7] minimal-form-plan"
body="$(jq -n --arg userPrompt "$prompt" --arg provider "$PROVIDER" --arg model "$MODEL" \
  '{userPrompt:$userPrompt, provider:$provider, model:$model, apiKey:(if $provider == "openai" then env.PRAXIS_AI_OPENAI_API_KEY else env.PRAXIS_AI_GEMINI_API_KEY end)}')"
printf '%s' "$body" | jq 'del(.apiKey) + {apiKey:"***"}' > "$ARTIFACTS_DIR/request.sanitized.json"
plan="$(curl -fsS --max-time 120 -X POST "$BASE_URL/api/praxis/config/ai/authoring/minimal-form-plan" "${headers[@]}" --data-binary @<(printf '%s' "$body") | tee "$ARTIFACTS_DIR/plan.response.json")"
test "$(jq -r '.valid' <<<"$plan")" = "true"
jq -e '.minimalFormPlan.fields | map(.name) | index("titulo") != null' <<<"$plan" >/dev/null
jq -e '.minimalFormPlan.fields | map(.name) | index("prioridadeId") == null and index("statusAtualId") == null' <<<"$plan" >/dev/null

echo "[3/7] compiled-form-patch"
compile_body="$(jq -n --argjson minimalFormPlan "$(jq '.minimalFormPlan' <<<"$plan")" '{minimalFormPlan:$minimalFormPlan}')"
compiled="$(curl -fsS --max-time 60 -X POST "$BASE_URL/api/praxis/config/ai/authoring/compiled-form-patch" "${headers[@]}" --data-binary @<(printf '%s' "$compile_body") | tee "$ARTIFACTS_DIR/compile.response.json")"
test "$(jq -r '.valid' <<<"$compiled")" = "true"
test "$(jq -r '.compiledFormPatch.patch.page.widgets[0].definition.id' <<<"$compiled")" = "praxis-dynamic-form"
test "$(jq -r '.compiledFormPatch.patch.page.widgets[0].definition.inputs.submitUrl' <<<"$compiled")" = "/api/operations/incidentes"

echo "[4/7] page-preview"
intent="$(curl -fsS --max-time 120 -X POST "$BASE_URL/api/praxis/config/ai/authoring/intent-resolution" "${headers[@]}" --data-binary @<(printf '%s' "$body") | tee "$ARTIFACTS_DIR/intent.response.json")"
test "$(jq -r '.valid' <<<"$intent")" = "true"
jq -e '.semanticDecision.schemaVersion == "praxis-agentic-authoring-semantic-decision.v1"' <<<"$intent" >/dev/null
preview_body="$(jq --argjson intentResolution "$intent" '. + {intentResolution:$intentResolution}' <<<"$body")"
preview="$(curl -fsS --max-time 120 -X POST "$BASE_URL/api/praxis/config/ai/authoring/page-preview" "${headers[@]}" --data-binary @<(printf '%s' "$preview_body") | tee "$ARTIFACTS_DIR/preview.response.json")"
test "$(jq -r '.valid' <<<"$preview")" = "true"
test "$(jq -r '.compiledFormPatch.patch.page.widgets[0].definition.id' <<<"$preview")" = "praxis-dynamic-form"

echo "[5/7] page-apply/get/delete"
component_type="praxis-dynamic-page"
component_id="agentic-authoring:local-e2e:operations-incident-form"
ui_uri="$BASE_URL/api/praxis/config/ui?componentType=$(urlencode "$component_type")&componentId=$(urlencode "$component_id")&scope=user"
apply_body="$(jq -n --argjson compiledFormPatch "$(jq '.compiledFormPatch' <<<"$preview")" --argjson semanticDecision "$(jq '.semanticDecision' <<<"$intent")" --arg componentType "$component_type" --arg componentId "$component_id" \
  '{compiledFormPatch:$compiledFormPatch, semanticDecision:$semanticDecision, componentType:$componentType, componentId:$componentId, scope:"user", tags:{purpose:"agentic-authoring-local-e2e"}}')"
curl -fsS --max-time 60 -D "$ARTIFACTS_DIR/apply.headers" -o "$ARTIFACTS_DIR/apply.response.json" \
  -X POST "$BASE_URL/api/praxis/config/ai/authoring/page-apply" "${headers[@]}" -H "X-Updated-By: agentic-authoring-local-e2e" --data-binary @<(printf '%s' "$apply_body")
test "$(jq -r '.applied' "$ARTIFACTS_DIR/apply.response.json")" = "true"
curl -fsS --max-time 60 -o "$ARTIFACTS_DIR/get.response.json" "$ui_uri" "${headers[@]}"
test "$(jq -r '.payload.widgets[0].definition.id' "$ARTIFACTS_DIR/get.response.json")" = "praxis-dynamic-form"
etag="$(jq -r '.etag' "$ARTIFACTS_DIR/get.response.json")"
curl -fsS --max-time 60 -X DELETE "$ui_uri" "${headers[@]}" -H "If-Match: \"$etag\"" -o "$ARTIFACTS_DIR/delete.response.json"
delete_status="$(curl -sS --max-time 30 -o "$ARTIFACTS_DIR/get-after-delete.response.json" -w '%{http_code}' "$ui_uri" "${headers[@]}")"
test "$delete_status" = "404"

echo "[6/7] SSE stream/probe/replay/cancel"
turn_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
stream_body="$(jq -n --arg componentId praxis-table --arg componentType table \
  --arg userPrompt 'Defina appearance.density como compact. Use exatamente um destes valores permitidos: compact, comfortable ou spacious.' \
  --arg provider "$PROVIDER" --arg turn "$turn_id" \
  '{componentId:$componentId, componentType:$componentType, userPrompt:$userPrompt, clientTurnId:$turn, currentState:{columns:[], capabilities:[], runtimeState:{}, ai:{provider:$provider}}}')"
printf '%s' "$stream_body" > "$ARTIFACTS_DIR/stream.start.request.json"
stream_start="$(curl -fsS --max-time 90 -X POST "$BASE_URL/api/praxis/config/ai/patch/stream/start" "${headers[@]}" --data-binary @<(printf '%s' "$stream_body") | tee "$ARTIFACTS_DIR/stream.start.response.json")"
stream_id="$(jq -r '.streamId' <<<"$stream_start")"
token="$(jq -r '.streamAccessToken // empty' <<<"$stream_start")"
query=""
[[ -n "$token" ]] && query="?accessToken=$(urlencode "$token")"
probe_code="$(curl -sS --max-time 30 -o /dev/null -w '%{http_code}' "$BASE_URL/api/praxis/config/ai/patch/stream/$stream_id/probe$query" "${headers[@]}")"
test "$probe_code" = "204"
curl -sS --max-time 180 "$BASE_URL/api/praxis/config/ai/patch/stream/$stream_id$query" "${headers[@]}" -o "$ARTIFACTS_DIR/stream.raw.sse" || true
awk '/^data:/ {sub(/^data:[[:space:]]*/, ""); print}' "$ARTIFACTS_DIR/stream.raw.sse" > "$ARTIFACTS_DIR/stream.events.jsonl"
event_count="$(wc -l < "$ARTIFACTS_DIR/stream.events.jsonl" | tr -d ' ')"
test "$event_count" -gt 0
terminal_count="$(jq -r 'select((.type|ascii_downcase) == "result" or (.type|ascii_downcase) == "error" or (.type|ascii_downcase) == "cancelled") | .type' "$ARTIFACTS_DIR/stream.events.jsonl" | wc -l | tr -d ' ')"
test "$terminal_count" -gt 0
error_count="$(jq -r 'select((.type|ascii_downcase) == "error") | .type' "$ARTIFACTS_DIR/stream.events.jsonl" | wc -l | tr -d ' ')"
test "$error_count" = "0"
anchor="$(jq -r 'select((.eventId // "") != "" and (.seq // -1) >= 0) | [.eventId, .seq] | @tsv' "$ARTIFACTS_DIR/stream.events.jsonl" | head -1 || true)"
replay_checked=false
if [[ -n "$anchor" ]]; then
  anchor_id="$(printf '%s' "$anchor" | cut -f1)"
  joiner="?"
  [[ -n "$query" ]] && joiner="&"
  curl -sS --max-time 180 "$BASE_URL/api/praxis/config/ai/patch/stream/$stream_id${query}${joiner}lastEventId=$(urlencode "$anchor_id")" "${headers[@]}" -o "$ARTIFACTS_DIR/replay.raw.sse" || true
  awk '/^data:/ {sub(/^data:[[:space:]]*/, ""); print}' "$ARTIFACTS_DIR/replay.raw.sse" > "$ARTIFACTS_DIR/replay.events.jsonl"
  [[ "$(wc -l < "$ARTIFACTS_DIR/replay.events.jsonl" | tr -d ' ')" -gt 0 ]] && replay_checked=true
fi
curl -fsS --max-time 30 -X POST "$BASE_URL/api/praxis/config/ai/patch/stream/$stream_id/cancel$query" "${headers[@]}" -o "$ARTIFACTS_DIR/cancel.response.json"

echo "[7/7] summary"
jq -n --arg artifactsDir "$ARTIFACTS_DIR" --arg provider "$PROVIDER" --arg model "$MODEL" --arg eventCount "$event_count" --arg replayChecked "$replay_checked" \
  '{provider:$provider, model:$model, artifactsDir:$artifactsDir, planValid:true, compileValid:true, previewValid:true, applyPersisted:true, applyCleanupDeleted:true, streamTerminalSeen:true, streamReplayChecked:($replayChecked == "true"), streamEventCount:($eventCount|tonumber)}' \
  | tee "$ARTIFACTS_DIR/summary.json"
