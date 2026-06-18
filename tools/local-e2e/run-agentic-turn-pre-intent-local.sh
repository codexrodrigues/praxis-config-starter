#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8088}"
ORIGIN="${ORIGIN:-http://localhost:4003}"
TENANT_ID="${TENANT_ID:-agentic-authoring-local-pre-intent}"
USER_ID="${USER_ID:-codex-local}"
ENVIRONMENT="${ENVIRONMENT:-local}"
PROVIDER="${PROVIDER:-openai}"
if [[ -z "${MODEL:-}" ]]; then
  if [[ "$PROVIDER" == "gemini" ]]; then
    MODEL="${PRAXIS_AI_GEMINI_MODEL:-gemini-2.5-flash}"
  else
    MODEL="${PRAXIS_AI_OPENAI_MODEL:-gpt-4.1-mini}"
  fi
fi
USER_PROMPT="${USER_PROMPT:-quero criar algo que mostre informacoes dos empregados}"
STREAM_TIMEOUT_SECONDS="${STREAM_TIMEOUT_SECONDS:-180}"
ARTIFACTS_DIR="${ARTIFACTS_DIR:-$STARTER_ROOT/artifacts/local-e2e/agentic-turn-pre-intent-$(date +%Y%m%d-%H%M%S)}"

mkdir -p "$ARTIFACTS_DIR"

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required." >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required." >&2
  exit 1
fi

urlencode() {
  python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"
}

headers=(
  -H "Origin: $ORIGIN"
  -H "Content-Type: application/json"
  -H "X-Tenant-ID: $TENANT_ID"
  -H "X-User-ID: $USER_ID"
  -H "X-Env: $ENVIRONMENT"
)

echo "[1/5] health $BASE_URL"
test "$(curl -fsS --max-time 10 "$BASE_URL/actuator/health" | tee "$ARTIFACTS_DIR/health.json" | jq -r '.status')" = "UP"

echo "[2/5] start agentic authoring turn stream"
client_turn_id="pre-intent-$(date +%s)-$RANDOM"
request_body="$(jq -n \
  --arg provider "$PROVIDER" \
  --arg model "$MODEL" \
  --arg clientTurnId "$client_turn_id" \
  --arg userPrompt "$USER_PROMPT" \
  '{
    userPrompt: $userPrompt,
    targetApp: "praxis-ui-angular",
    targetComponentId: "praxis-dynamic-page-builder",
    currentRoute: "/decision-playground",
    currentPage: { widgets: [] },
    selectedWidgetKey: null,
    provider: $provider,
    model: $model,
    apiKey: null,
    sessionId: "local-pre-intent-session",
    clientTurnId: $clientTurnId,
    conversationMessages: [],
    pendingClarification: null,
    attachmentSummaries: [],
    contextHints: {
      domainDiscovery: [
        {
          resourceKey: "operations.missoes",
          title: "Missões",
          fields: ["Nome", "Status"]
        },
        {
          resourceKey: "human-resources.funcionarios",
          title: "Funcionários",
          fields: ["Nome", "E-mail", "Cargo", "Departamento"],
          surfaces: ["Cadastrar funcionário", "Obter funcionário", "Perfil 360"]
        }
      ]
    },
    componentCapabilities: null,
    runtimeComponentObservations: null,
    runtimeComponentObservationTrustBoundary: null
  }')"
printf '%s\n' "$request_body" > "$ARTIFACTS_DIR/turn.start.request.json"

start_response="$(curl -fsS --max-time 60 -X POST \
  "$BASE_URL/api/praxis/config/ai/authoring/turn/stream/start" \
  "${headers[@]}" \
  --data-binary @"$ARTIFACTS_DIR/turn.start.request.json" \
  | tee "$ARTIFACTS_DIR/turn.start.response.json")"
stream_id="$(jq -r '.streamId' <<<"$start_response")"
token="$(jq -r '.streamAccessToken // empty' <<<"$start_response")"
if [[ -z "$stream_id" || "$stream_id" == "null" ]]; then
  echo "turn stream start did not return streamId." >&2
  exit 1
fi

query=""
if [[ -n "$token" ]]; then
  query="?accessToken=$(urlencode "$token")"
fi

echo "[3/5] probe stream $stream_id"
probe_code="$(curl -sS --max-time 30 -o /dev/null -w '%{http_code}' \
  "$BASE_URL/api/praxis/config/ai/authoring/turn/stream/$stream_id/probe$query" \
  "${headers[@]}")"
test "$probe_code" = "204"

echo "[4/5] read raw SSE"
curl -sS --max-time "$STREAM_TIMEOUT_SECONDS" \
  "$BASE_URL/api/praxis/config/ai/authoring/turn/stream/$stream_id$query" \
  "${headers[@]}" \
  -o "$ARTIFACTS_DIR/turn.raw.sse" || true
awk '/^data:/ {sub(/^data:[[:space:]]*/, ""); print}' "$ARTIFACTS_DIR/turn.raw.sse" > "$ARTIFACTS_DIR/turn.events.jsonl"
event_count="$(wc -l < "$ARTIFACTS_DIR/turn.events.jsonl" | tr -d ' ')"
if [[ "$event_count" -le 0 ]]; then
  echo "No SSE events were captured. See $ARTIFACTS_DIR/turn.raw.sse" >&2
  exit 1
fi

echo "[5/5] assert pre-intent planning observability"
python3 - "$ARTIFACTS_DIR/turn.events.jsonl" "$ARTIFACTS_DIR/summary.json" <<'PY'
import json
import sys

events_path = sys.argv[1]
summary_path = sys.argv[2]

events = []
with open(events_path, "r", encoding="utf-8") as handle:
    for line in handle:
        line = line.strip()
        if not line:
            continue
        events.append(json.loads(line))

def payload(event):
    value = event.get("payload")
    return value if isinstance(value, dict) else {}

def phase(event):
    return payload(event).get("phase") or event.get("phase") or ""

def event_type(event):
    return event.get("type") or ""

def first_index(predicate):
    for index, event in enumerate(events):
        if predicate(event):
            return index
    return -1

plan_index = first_index(lambda event: phase(event) in {"tool.plan", "tool.plan.skipped"})
resolved_index = first_index(lambda event: event_type(event) == "intent.resolved")
terminal_index = first_index(lambda event: event_type(event) in {"result", "error", "cancelled"})
tool_result_index = first_index(lambda event: phase(event) == "tool.result")
grounded_clarification_index = first_index(lambda event: phase(event) == "consultative.grounded-clarification")
grounded_domain_clarification_index = first_index(
    lambda event: phase(event) == "consultative.grounded-domain-clarification"
)

sequence = [
    {
        "index": index,
        "type": event_type(event),
        "phase": phase(event),
        "skipReason": payload(event).get("diagnostics", {}).get("skipReason"),
        "errorCode": payload(event).get("diagnostics", {}).get("errorCode"),
    }
    for index, event in enumerate(events)
]

summary = {
    "eventCount": len(events),
    "planOrSkippedIndex": plan_index,
    "intentResolvedIndex": resolved_index,
    "terminalIndex": terminal_index,
    "toolResultIndex": tool_result_index,
    "groundedClarificationIndex": grounded_clarification_index,
    "groundedDomainClarificationIndex": grounded_domain_clarification_index,
    "planOrSkippedPhase": phase(events[plan_index]) if plan_index >= 0 else None,
    "skipReason": payload(events[plan_index]).get("diagnostics", {}).get("skipReason") if plan_index >= 0 else None,
    "errorCode": payload(events[plan_index]).get("diagnostics", {}).get("errorCode") if plan_index >= 0 else None,
    "sequence": sequence,
}

if plan_index < 0:
    print("Expected tool.plan or tool.plan.skipped before intent.resolved, but neither was emitted.", file=sys.stderr)
    print(json.dumps(sequence, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
if resolved_index < 0:
    print("Expected intent.resolved event, but it was not emitted.", file=sys.stderr)
    print(json.dumps(sequence, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
if plan_index >= resolved_index:
    print("Expected tool.plan/tool.plan.skipped before intent.resolved.", file=sys.stderr)
    print(json.dumps(sequence, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
if terminal_index < 0:
    print("Expected terminal result/error/cancelled event, but it was not emitted.", file=sys.stderr)
    print(json.dumps(sequence, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)

resolved_payload = payload(events[resolved_index])
terminal_event = events[terminal_index]
terminal_payload = payload(terminal_event)
tool_result_payload = payload(events[tool_result_index]) if tool_result_index >= 0 else {}
tool_result_diagnostics = tool_result_payload.get("diagnostics", {})
provider_failed_after_candidate_discovery = (
    summary["planOrSkippedPhase"] == "tool.plan"
    and tool_result_index >= 0
    and tool_result_diagnostics.get("candidateCount", 0) > 0
    and resolved_payload.get("routeClass") == "needs_clarification"
    and "llm-provider-error" in (resolved_payload.get("warnings") or [])
)
summary["providerFailedAfterCandidateDiscovery"] = provider_failed_after_candidate_discovery
summary["toolResultCandidateCount"] = tool_result_diagnostics.get("candidateCount") if tool_result_index >= 0 else None
summary["resultCanApply"] = terminal_payload.get("canApply") if event_type(terminal_event) == "result" else None
summary["resourceDiscoveryGroundedClarification"] = (
    terminal_payload.get("decisionDiagnostics", {}).get("resourceDiscoveryGroundedClarification")
    if event_type(terminal_event) == "result"
    else None
)
planner_provider_error = (
    summary["planOrSkippedPhase"] == "tool.plan.skipped"
    and summary["skipReason"] == "provider-error"
    and resolved_payload.get("routeClass") == "needs_clarification"
    and "llm-provider-error" in (resolved_payload.get("warnings") or [])
)
summary["plannerProviderErrorBeforeCandidateDiscovery"] = planner_provider_error
summary["domainDiscoveryGroundedClarification"] = (
    terminal_payload.get("decisionDiagnostics", {}).get("domainDiscoveryGroundedClarification")
    if event_type(terminal_event) == "result"
    else None
)
grounded_provider_clarification_candidates = [
    index for index in (grounded_clarification_index, grounded_domain_clarification_index)
    if index >= 0
]
grounded_provider_clarification_index = (
    min(grounded_provider_clarification_candidates)
    if grounded_provider_clarification_candidates
    else -1
)
summary["providerFailureGroundedClarificationPhase"] = (
    phase(events[grounded_provider_clarification_index])
    if grounded_provider_clarification_index >= 0
    else None
)

if provider_failed_after_candidate_discovery:
    if event_type(terminal_event) != "result":
        print("Expected grounded clarification result after provider failure with discovered candidates.", file=sys.stderr)
        print(json.dumps(sequence, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)
    if grounded_clarification_index < 0 or grounded_clarification_index <= resolved_index:
        print("Expected consultative.grounded-clarification after intent.resolved.", file=sys.stderr)
        print(json.dumps(sequence, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)
    if grounded_clarification_index >= terminal_index:
        print("Expected consultative.grounded-clarification before terminal result.", file=sys.stderr)
        print(json.dumps(sequence, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)
    if terminal_payload.get("canApply") is not False:
        print("Expected grounded clarification to remain fail-closed with canApply=false.", file=sys.stderr)
        print(json.dumps(terminal_payload, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)
    decision_diagnostics = terminal_payload.get("decisionDiagnostics", {})
    if decision_diagnostics.get("resourceDiscoveryGroundedClarification") is not True:
        print("Expected decisionDiagnostics.resourceDiscoveryGroundedClarification=true.", file=sys.stderr)
        print(json.dumps(decision_diagnostics, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)
    assistant_message = terminal_payload.get("assistantMessage") or ""
    if (
        "Encontrei candidatos governados" not in assistant_message
        and "busca governada retornou candidatos preliminares" not in assistant_message
    ):
        print("Expected assistantMessage to be anchored in governed candidate evidence.", file=sys.stderr)
        print(assistant_message, file=sys.stderr)
        sys.exit(1)

if planner_provider_error:
    if event_type(terminal_event) != "result":
        print("Expected grounded domain clarification result after planner provider failure.", file=sys.stderr)
        print(json.dumps(sequence, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)
    if grounded_provider_clarification_index < 0 or grounded_provider_clarification_index <= resolved_index:
        print("Expected governed clarification after intent.resolved.", file=sys.stderr)
        print(json.dumps(sequence, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)
    if grounded_provider_clarification_index >= terminal_index:
        print("Expected governed clarification before terminal result.", file=sys.stderr)
        print(json.dumps(sequence, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)
    if terminal_payload.get("canApply") is not False:
        print("Expected grounded domain clarification to remain fail-closed with canApply=false.", file=sys.stderr)
        print(json.dumps(terminal_payload, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)
    decision_diagnostics = terminal_payload.get("decisionDiagnostics", {})
    if (
        decision_diagnostics.get("domainDiscoveryGroundedClarification") is not True
        and decision_diagnostics.get("resourceDiscoveryGroundedClarification") is not True
    ):
        print("Expected a grounded clarification diagnostic after planner provider failure.", file=sys.stderr)
        print(json.dumps(decision_diagnostics, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)
    assistant_message = terminal_payload.get("assistantMessage") or ""
    if (
        "contexto governado disponível" not in assistant_message
        and "Encontrei candidatos governados" not in assistant_message
        and "busca governada retornou candidatos preliminares" not in assistant_message
    ):
        print("Expected assistantMessage to be anchored in governed evidence.", file=sys.stderr)
        print(assistant_message, file=sys.stderr)
        sys.exit(1)

with open(summary_path, "w", encoding="utf-8") as handle:
    json.dump(summary, handle, ensure_ascii=False, indent=2)
    handle.write("\n")

print(json.dumps({k: v for k, v in summary.items() if k != "sequence"}, ensure_ascii=False, indent=2))
PY

echo "Artifacts: $ARTIFACTS_DIR"
