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
TARGET_APP="${TARGET_APP:-praxis-ui-angular}"
TARGET_COMPONENT_ID="${TARGET_COMPONENT_ID:-praxis-dynamic-page-builder}"
CURRENT_ROUTE="${CURRENT_ROUTE:-/decision-playground}"
CURRENT_PAGE_JSON="${CURRENT_PAGE_JSON:-}"
SELECTED_WIDGET_KEY_JSON="${SELECTED_WIDGET_KEY_JSON:-null}"
CONVERSATION_MESSAGES_JSON="${CONVERSATION_MESSAGES_JSON:-[]}"
PENDING_CLARIFICATION_JSON="${PENDING_CLARIFICATION_JSON:-null}"
ATTACHMENT_SUMMARIES_JSON="${ATTACHMENT_SUMMARIES_JSON:-[]}"
CONTEXT_HINTS_JSON="${CONTEXT_HINTS_JSON:-}"
SESSION_ID="${SESSION_ID:-local-pre-intent-session}"
STREAM_TIMEOUT_SECONDS="${STREAM_TIMEOUT_SECONDS:-180}"
ARTIFACTS_DIR="${ARTIFACTS_DIR:-$STARTER_ROOT/artifacts/local-e2e/agentic-turn-pre-intent-$(date +%Y%m%d-%H%M%S)}"

if [[ -z "$CURRENT_PAGE_JSON" ]]; then
  CURRENT_PAGE_JSON='{"widgets":[]}'
fi
if [[ -z "$CONTEXT_HINTS_JSON" ]]; then
  CONTEXT_HINTS_JSON='{"domainDiscovery":[{"resourceKey":"operations.missoes","title":"Missões","fields":["Nome","Status"]},{"resourceKey":"human-resources.funcionarios","title":"Funcionários","fields":["Nome","E-mail","Cargo","Departamento"],"surfaces":["Cadastrar funcionário","Obter funcionário","Perfil 360"]}]}'
fi

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
  --arg targetApp "$TARGET_APP" \
  --arg targetComponentId "$TARGET_COMPONENT_ID" \
  --arg currentRoute "$CURRENT_ROUTE" \
  --arg sessionId "$SESSION_ID" \
  --argjson currentPage "$CURRENT_PAGE_JSON" \
  --argjson selectedWidgetKey "$SELECTED_WIDGET_KEY_JSON" \
  --argjson conversationMessages "$CONVERSATION_MESSAGES_JSON" \
  --argjson pendingClarification "$PENDING_CLARIFICATION_JSON" \
  --argjson attachmentSummaries "$ATTACHMENT_SUMMARIES_JSON" \
  --argjson contextHints "$CONTEXT_HINTS_JSON" \
  '{
    userPrompt: $userPrompt,
    targetApp: $targetApp,
    targetComponentId: $targetComponentId,
    currentRoute: $currentRoute,
    currentPage: $currentPage,
    selectedWidgetKey: $selectedWidgetKey,
    provider: $provider,
    model: $model,
    apiKey: null,
    sessionId: $sessionId,
    clientTurnId: $clientTurnId,
    conversationMessages: $conversationMessages,
    pendingClarification: $pendingClarification,
    attachmentSummaries: $attachmentSummaries,
    contextHints: $contextHints,
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
STREAM_URL="$BASE_URL/api/praxis/config/ai/authoring/turn/stream/$stream_id$query" \
RAW_SSE_PATH="$ARTIFACTS_DIR/turn.raw.sse" \
ORIGIN="$ORIGIN" \
TENANT_ID="$TENANT_ID" \
USER_ID="$USER_ID" \
ENVIRONMENT="$ENVIRONMENT" \
STREAM_TIMEOUT_SECONDS="$STREAM_TIMEOUT_SECONDS" \
python3 <<'PY'
import json
import os
import socket
import sys
import urllib.request

stream_url = os.environ["STREAM_URL"]
raw_sse_path = os.environ["RAW_SSE_PATH"]
timeout = float(os.environ.get("STREAM_TIMEOUT_SECONDS") or "180")
request = urllib.request.Request(
    stream_url,
    headers={
        "Origin": os.environ["ORIGIN"],
        "X-Tenant-ID": os.environ["TENANT_ID"],
        "X-User-ID": os.environ["USER_ID"],
        "X-Env": os.environ["ENVIRONMENT"],
        "Accept": "text/event-stream",
    },
)
terminal_types = {"result", "error", "cancelled"}
try:
    with urllib.request.urlopen(request, timeout=timeout) as response, open(raw_sse_path, "wb") as output:
        while True:
            line = response.readline()
            if not line:
                break
            output.write(line)
            output.flush()
            decoded = line.decode("utf-8", errors="replace").strip()
            if not decoded.startswith("data:"):
                continue
            data = decoded[5:].strip()
            if not data:
                continue
            try:
                event = json.loads(data)
            except json.JSONDecodeError:
                continue
            if (event.get("type") or "").lower() in terminal_types:
                break
except (TimeoutError, socket.timeout):
    print(f"SSE read timed out after {timeout:g}s; continuing with captured events.", file=sys.stderr)
except Exception as exc:
    print(f"SSE read failed: {exc}", file=sys.stderr)
PY
awk '/^data:/ {sub(/^data:[[:space:]]*/, ""); print}' "$ARTIFACTS_DIR/turn.raw.sse" > "$ARTIFACTS_DIR/turn.events.jsonl"
event_count="$(wc -l < "$ARTIFACTS_DIR/turn.events.jsonl" | tr -d ' ')"
if [[ "$event_count" -le 0 ]]; then
  echo "No SSE events were captured. See $ARTIFACTS_DIR/turn.raw.sse" >&2
  exit 1
fi

echo "[5/5] assert pre-intent planning observability"
python3 - "$ARTIFACTS_DIR/turn.events.jsonl" "$ARTIFACTS_DIR/summary.json" <<'PY'
import json
import re
import sys
from datetime import datetime, timezone

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

def event_timestamp(event):
    value = event.get("timestamp")
    if not isinstance(value, str) or not value.strip():
        return None
    normalized = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(normalized)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed
    except ValueError:
        return None

def elapsed_between_event_indices(start_index, end_index):
    if not isinstance(start_index, int) or not isinstance(end_index, int):
        return None
    if start_index < 0 or end_index < 0 or start_index >= len(events) or end_index >= len(events):
        return None
    start = event_timestamp(events[start_index])
    end = event_timestamp(events[end_index])
    if start is None or end is None:
        return None
    return round((end - start).total_seconds(), 3)

def first_phase_index(phase_name, event_type_name=None):
    return first_index(
        lambda event: phase(event) == phase_name
        and (event_type_name is None or event_type(event) == event_type_name)
    )

def diagnostics_for_phase(phase_name, event_type_name=None):
    index = first_phase_index(phase_name, event_type_name)
    if index < 0:
        return {}
    diagnostics = payload(events[index]).get("diagnostics")
    return diagnostics if isinstance(diagnostics, dict) else {}

plan_index = first_index(lambda event: phase(event) in {"tool.plan", "tool.plan.skipped"})
resolved_index = first_index(lambda event: event_type(event) == "intent.resolved")
terminal_index = first_index(lambda event: event_type(event) in {"result", "error", "cancelled"})
tool_result_index = first_index(lambda event: phase(event) == "tool.result")
grounded_clarification_index = first_index(lambda event: phase(event) == "consultative.grounded-clarification")
grounded_domain_clarification_index = first_index(
    lambda event: phase(event) == "consultative.grounded-domain-clarification"
)
context_bundle_start = first_phase_index("context.bundle")
intent_resolve_start = first_phase_index("intent.resolve")
tool_start = first_phase_index("tool.start")
component_capabilities_start = first_phase_index("component.capabilities", "status")
component_capabilities_done = first_phase_index("component.capabilities", "thought.step")
intent_resolve_evidence_start = first_phase_index("intent.resolve.evidence")
intent_resolve_llm_start = first_phase_index("intent.resolve.llm")
intent_resolution_start = intent_resolve_evidence_start if intent_resolve_evidence_start >= 0 else intent_resolve_llm_start
preview_plan_start = first_phase_index("preview.plan")
preview_compile_start = first_phase_index("preview.compile")
tool_loop_start = first_phase_index("tool.loop")
component_capabilities_diagnostics = diagnostics_for_phase("component.capabilities", "thought.step")

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

technical_message_pattern = re.compile(
    r"\b("
    r"Governed|Runtime|Retrieved|Granular|"
    r"preview planning|tool loop|backend API resource search|"
    r"Post-intent|Resource candidates|Compiled preview"
    r")\b"
)
missing_thought_step_messages = []
redacted_thought_step_messages = []
technical_thought_step_messages = []
for index, event in enumerate(events):
    event_payload = payload(event)
    if event_type(event) != "thought.step":
        continue
    message = event_payload.get("message")
    if not isinstance(message, str) or not message.strip():
        missing_thought_step_messages.append({
            "index": index,
            "phase": phase(event),
        })
        continue
    if "[REDACTED]" in message:
        redacted_thought_step_messages.append({
            "index": index,
            "phase": phase(event),
            "message": message,
        })
    if technical_message_pattern.search(message):
        technical_thought_step_messages.append({
            "index": index,
            "phase": phase(event),
            "message": message,
        })

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
    "presentationAudit": {
        "thoughtStepMissingMessageCount": len(missing_thought_step_messages),
        "thoughtStepRedactedMessageCount": len(redacted_thought_step_messages),
        "thoughtStepTechnicalMessageCount": len(technical_thought_step_messages),
        "thoughtStepMissingMessages": missing_thought_step_messages,
        "thoughtStepRedactedMessages": redacted_thought_step_messages,
        "thoughtStepTechnicalMessages": technical_thought_step_messages,
    },
    "phaseTimingSeconds": {
        "contextBundleToIntentResolve": elapsed_between_event_indices(context_bundle_start, intent_resolve_start),
        "preIntentPlanning": elapsed_between_event_indices(intent_resolve_start, plan_index),
        "toolExecution": elapsed_between_event_indices(tool_start, tool_result_index),
        "componentCapabilitiesTransport": elapsed_between_event_indices(
            component_capabilities_start,
            component_capabilities_done,
        ),
        "intentResolveEvidence": elapsed_between_event_indices(intent_resolve_evidence_start, resolved_index),
        "intentResolveLlm": elapsed_between_event_indices(intent_resolve_llm_start, resolved_index),
        "intentResolution": elapsed_between_event_indices(intent_resolution_start, resolved_index),
        "intentResolvedToPreview": elapsed_between_event_indices(resolved_index, preview_plan_start),
        "previewToResult": elapsed_between_event_indices(preview_plan_start, terminal_index),
        "previewCompileToToolLoop": elapsed_between_event_indices(preview_compile_start, tool_loop_start),
        "toolLoopToResult": elapsed_between_event_indices(tool_loop_start, terminal_index),
    },
    "componentCapabilitiesDiagnostics": component_capabilities_diagnostics,
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
if missing_thought_step_messages:
    print("Expected every thought.step to expose a user-facing payload.message.", file=sys.stderr)
    print(json.dumps(missing_thought_step_messages, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
if redacted_thought_step_messages:
    print("Expected thought.step payload.message not to contain redacted fallback text.", file=sys.stderr)
    print(json.dumps(redacted_thought_step_messages, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
if technical_thought_step_messages:
    print("Expected thought.step payload.message not to expose technical/audit phrasing.", file=sys.stderr)
    print(json.dumps(technical_thought_step_messages, ensure_ascii=False, indent=2), file=sys.stderr)
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
summary["resourceDiscoveryDiagnostics"] = (
    tool_result_diagnostics.get("resourceDiscoveryDiagnostics")
    if isinstance(tool_result_diagnostics.get("resourceDiscoveryDiagnostics"), dict)
    else {}
)
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
