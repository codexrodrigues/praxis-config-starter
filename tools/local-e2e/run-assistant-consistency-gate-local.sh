#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CORPUS_PATH="${CORPUS_PATH:-$STARTER_ROOT/docs/ai/agentic-authoring/proofs/assistant-consistency-corpus.v1.json}"
PRICING_SNAPSHOT_PATH="${PRICING_SNAPSHOT_PATH:-$STARTER_ROOT/docs/ai/agentic-authoring/proofs/provider-pricing-snapshot.v1.json}"
PROFILE="${PROFILE:-must-pass}"
REPETITIONS="${REPETITIONS:-1}"
RELEASE_GATE="${RELEASE_GATE:-false}"
CASE_IDS="${CASE_IDS:-}"
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
    MODEL="${PRAXIS_AI_OPENAI_MODEL:-gpt-5.4-mini}"
  fi
fi
STREAM_TIMEOUT_SECONDS="${STREAM_TIMEOUT_SECONDS:-180}"
ARTIFACTS_DIR="${ARTIFACTS_DIR:-$STARTER_ROOT/artifacts/local-e2e/assistant-consistency-$(date +%Y%m%d-%H%M%S)}"
REUSE_EXISTING_ARTIFACTS="${REUSE_EXISTING_ARTIFACTS:-false}"
MAX_FIRST_FEEDBACK_SECONDS="${MAX_FIRST_FEEDBACK_SECONDS:-2}"
MAX_GUIDANCE_SECONDS="${MAX_GUIDANCE_SECONDS:-12}"
MAX_AUTHORING_SECONDS="${MAX_AUTHORING_SECONDS:-45}"
ENFORCE_LATENCY="${ENFORCE_LATENCY:-$RELEASE_GATE}"
MAX_TOKENS_PER_RUN="${MAX_TOKENS_PER_RUN:-12000}"
MAX_ESTIMATED_COST_USD_MICROS_PER_RUN="${MAX_ESTIMATED_COST_USD_MICROS_PER_RUN:-10000}"
ENFORCE_EFFICIENCY="${ENFORCE_EFFICIENCY:-$RELEASE_GATE}"

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required." >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required." >&2
  exit 1
fi
if [[ ! -f "$CORPUS_PATH" ]]; then
  echo "Corpus not found: $CORPUS_PATH" >&2
  exit 1
fi
if [[ ! -f "$PRICING_SNAPSHOT_PATH" ]]; then
  echo "Pricing snapshot not found: $PRICING_SNAPSHOT_PATH" >&2
  exit 1
fi
if ! [[ "$REPETITIONS" =~ ^[1-9][0-9]*$ ]]; then
  echo "REPETITIONS must be a positive integer." >&2
  exit 1
fi
if [[ "$RELEASE_GATE" == "true" && "$REPETITIONS" -lt 3 ]]; then
  echo "RELEASE_GATE=true requires at least three consecutive repetitions." >&2
  exit 1
fi

mkdir -p "$ARTIFACTS_DIR"

python3 - "$CORPUS_PATH" "$ARTIFACTS_DIR" "$PROFILE" "$REPETITIONS" "$CASE_IDS" <<'PY'
import json
import pathlib
import sys
from copy import deepcopy

corpus_path = pathlib.Path(sys.argv[1])
artifacts_dir = pathlib.Path(sys.argv[2])
profile = sys.argv[3]
repetitions = int(sys.argv[4])
requested_ids = {item.strip() for item in sys.argv[5].split(",") if item.strip()}

corpus = json.loads(corpus_path.read_text(encoding="utf-8"))
if corpus.get("version") != "1.0.0" or corpus.get("kind") != "praxis.ai-authoring.assistant-consistency-corpus":
    raise SystemExit("Unsupported assistant consistency corpus version or kind.")

statuses = {
    "must-pass": {"must-pass"},
    "extended": {"must-pass", "extended"},
    "all": {"must-pass", "extended"},
}.get(profile)
if statuses is None:
    raise SystemExit("PROFILE must be must-pass, extended, or all.")

contexts = corpus.get("contexts") or {}
seen = set()
selected = []
for case in corpus.get("cases") or []:
    case_id = case.get("id") or ""
    if not case_id or case_id in seen:
        raise SystemExit(f"Missing or duplicated case id: {case_id!r}")
    seen.add(case_id)
    context_ref = case.get("contextRef")
    if context_ref not in contexts:
        raise SystemExit(f"Unknown contextRef {context_ref!r} in {case_id}")
    if case.get("status") not in statuses:
        continue
    if requested_ids and case_id not in requested_ids:
        continue
    selected.append(("case", case))

for journey in corpus.get("journeys") or []:
    journey_id = journey.get("id") or ""
    if not journey_id or journey_id in seen:
        raise SystemExit(f"Missing or duplicated corpus unit id: {journey_id!r}")
    seen.add(journey_id)
    context_ref = journey.get("contextRef")
    if context_ref not in contexts:
        raise SystemExit(f"Unknown contextRef {context_ref!r} in {journey_id}")
    if journey.get("status") not in statuses:
        continue
    if requested_ids and journey_id not in requested_ids:
        continue
    selected.append(("journey", journey))

missing_requested = requested_ids - {unit["id"] for _, unit in selected}
if missing_requested:
    raise SystemExit(f"Requested cases not selected by profile or absent: {sorted(missing_requested)}")
if not selected:
    raise SystemExit("No corpus cases selected.")

runs = []
for repetition in range(1, repetitions + 1):
    for kind, unit in selected:
        context = deepcopy(contexts[unit["contextRef"]])
        context.setdefault("contextHints", {})["responseLocale"] = unit["locale"]
        apply_target = (context.get("contextHints") or {}).get("agenticApplyTarget")
        if apply_target is not None:
            apply_target["componentId"] = f"assistant-consistency-{repetition}-{unit['id']}"
        turns = unit.get("turns") if kind == "journey" else [unit]
        previous_relative = None
        for turn_index, turn in enumerate(turns, start=1):
            if kind == "journey":
                relative = (
                    pathlib.Path(f"run-{repetition:02d}")
                    / unit["id"]
                    / f"turn-{turn_index:02d}-{turn['id']}"
                )
                case = {
                    "id": f"{unit['id']}#{turn['id']}",
                    "family": unit["family"],
                    "status": unit["status"],
                    "locale": unit["locale"],
                    "contextRef": unit["contextRef"],
                    "userPrompt": turn["userPrompt"],
                    "expected": turn["expected"],
                    "currentPageSource": turn["currentPageSource"],
                }
                if "pageAssertions" in turn:
                    case["pageAssertions"] = turn["pageAssertions"]
                if "lineage" in turn:
                    case["lineage"] = turn["lineage"]
                if "quickReplySelection" in turn:
                    case["quickReplySelection"] = turn["quickReplySelection"]
            else:
                relative = pathlib.Path(f"run-{repetition:02d}") / unit["id"]
                case = unit

            case_dir = artifacts_dir / relative
            case_dir.mkdir(parents=True, exist_ok=True)
            (case_dir / "case.json").write_text(
                json.dumps(case, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            (case_dir / "context.json").write_text(
                json.dumps(context, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            runs.append({
                "kind": kind,
                "repetition": repetition,
                "caseId": unit["id"],
                "turnId": turn.get("id") if kind == "journey" else None,
                "turnIndex": turn_index if kind == "journey" else None,
                "family": unit["family"],
                "status": unit["status"],
                "directory": str(relative),
                "previousDirectory": str(previous_relative) if previous_relative else None,
            })
            previous_relative = relative

plan = {
    "version": "1.0.0",
    "kind": "praxis.ai-authoring.assistant-consistency-execution-plan",
    "corpusPath": str(corpus_path),
    "profile": profile,
    "repetitions": repetitions,
    "caseCount": len(selected),
    "runCount": len(runs),
    "runs": runs,
}
(artifacts_dir / "execution-plan.json").write_text(
    json.dumps(plan, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY

echo "Running Praxis assistant consistency gate"
echo "Corpus: $CORPUS_PATH"
echo "Pricing: $PRICING_SNAPSHOT_PATH"
echo "Profile: $PROFILE | repetitions: $REPETITIONS | provider: $PROVIDER | model: $MODEL"
echo "Artifacts: $ARTIFACTS_DIR"

if [[ "$REUSE_EXISTING_ARTIFACTS" != "true" ]]; then
  while IFS= read -r relative_dir; do
    case_dir="$ARTIFACTS_DIR/$relative_dir"
    case_id="$(jq -r '.id' "$case_dir/case.json")"
    repetition="$(jq -r --arg directory "$relative_dir" '.runs[] | select(.directory == $directory) | .repetition' "$ARTIFACTS_DIR/execution-plan.json")"
    run_kind="$(jq -r --arg directory "$relative_dir" '.runs[] | select(.directory == $directory) | .kind' "$ARTIFACTS_DIR/execution-plan.json")"
    previous_relative="$(jq -r --arg directory "$relative_dir" '.runs[] | select(.directory == $directory) | .previousDirectory // empty' "$ARTIFACTS_DIR/execution-plan.json")"
    current_page_json="$(jq -c '.currentPage' "$case_dir/context.json")"
    conversation_messages_json='[]'
    active_semantic_decision_json='null'
    selected_user_prompt="$(jq -r '.userPrompt' "$case_dir/case.json")"
    selected_context_hints_json="$(jq -c '.contextHints + {includeLlmDiagnostics:true}' "$case_dir/context.json")"
    session_id="assistant-consistency-$repetition-$case_id"
    if [[ "$run_kind" == "journey" && -n "$previous_relative" ]]; then
      previous_dir="$ARTIFACTS_DIR/$previous_relative"
      if [[ ! -f "$previous_dir/turn.start.response.json" || ! -f "$previous_dir/turn.events.jsonl" ]]; then
        echo "Previous journey turn did not produce the required transport artifacts." | tee "$case_dir/journey-precondition-error.txt" >&2
        printf '%s\n' '1' > "$case_dir/runner-exit-code.txt"
        continue
      fi
      session_id="$(jq -r '.threadId // empty' "$previous_dir/turn.start.response.json")"
      current_page_source="$(jq -r '.currentPageSource' "$case_dir/case.json")"
      previous_preview_json="$(jq -s -c '
        [.[] | select(.type == "result")][-1].payload.preview
        | if (.uiCompositionPlan.widgets? | type) == "array" then .uiCompositionPlan
          elif (.compiledFormPatch.patch.page.widgets? | type) == "array" then .compiledFormPatch.patch.page
          else empty
          end
      ' "$previous_dir/turn.events.jsonl")"
      if [[ -z "$session_id" ]]; then
        echo "Previous journey turn did not expose a canonical thread." | tee "$case_dir/journey-precondition-error.txt" >&2
        printf '%s\n' '1' > "$case_dir/runner-exit-code.txt"
        continue
      fi
      if [[ "$current_page_source" == "previous-preview" ]]; then
        current_page_json="$previous_preview_json"
        if [[ -z "$current_page_json" ]]; then
          echo "Journey turn requires the previous materialized preview, but none was emitted." | tee "$case_dir/journey-precondition-error.txt" >&2
          printf '%s\n' '1' > "$case_dir/runner-exit-code.txt"
          continue
        fi
      elif [[ "$current_page_source" == "context" ]]; then
        current_page_json="$(jq -c '.currentPage' "$case_dir/context.json")"
      else
        echo "Unsupported journey currentPageSource '$current_page_source'." | tee "$case_dir/journey-precondition-error.txt" >&2
        printf '%s\n' '1' > "$case_dir/runner-exit-code.txt"
        continue
      fi
      previous_conversation='[]'
      if [[ -f "$previous_dir/input-conversation.json" ]]; then
        previous_conversation="$(jq -c '.' "$previous_dir/input-conversation.json")"
      fi
      previous_prompt="$(jq -r '.userPrompt' "$previous_dir/case.json")"
      previous_assistant_message="$(jq -s -r '[.[] | select(.type == "result")][-1].payload.assistantMessage // empty' "$previous_dir/turn.events.jsonl")"
      quick_reply_id="$(jq -r '.quickReplySelection.replyId // empty' "$case_dir/case.json")"
      if [[ -n "$quick_reply_id" ]]; then
        selected_quick_reply_json="$(jq -s -c --arg replyId "$quick_reply_id" '
          [.[] | select(.type == "result")][-1].payload.quickReplies
          | [.[] | select(.id == $replyId)]
          | if length == 1 then .[0] else empty end
        ' "$previous_dir/turn.events.jsonl")"
        if [[ -z "$selected_quick_reply_json" ]]; then
          echo "Previous turn did not emit exactly one quick reply '$quick_reply_id'." | tee "$case_dir/journey-precondition-error.txt" >&2
          printf '%s\n' '1' > "$case_dir/runner-exit-code.txt"
          continue
        fi
        require_semantic_decision="$(jq -r '.quickReplySelection.requireSemanticDecision // false' "$case_dir/case.json")"
        active_semantic_decision_json="$(jq -c '.semanticDecision // null' <<<"$selected_quick_reply_json")"
        if [[ "$require_semantic_decision" == "true" ]] && ! jq -e '
          type == "object" and ((.decisionId // "") | length > 0)
        ' >/dev/null <<<"$active_semantic_decision_json"; then
          echo "Selected quick reply '$quick_reply_id' did not carry a semantic decision." | tee "$case_dir/journey-precondition-error.txt" >&2
          printf '%s\n' '1' > "$case_dir/runner-exit-code.txt"
          continue
        fi
        selected_user_prompt="$(jq -r '
          if (.value | type) == "string" and (.value | length) > 0 then .value else .prompt end
        ' <<<"$selected_quick_reply_json")"
        selected_context_hints_json="$(jq -cn \
          --argjson base "$selected_context_hints_json" \
          --argjson reply "$selected_quick_reply_json" '
          $base
          + ($reply.contextHints // {})
          + (if ($reply.semanticDecision | type) == "object"
              then {semanticDecision: $reply.semanticDecision}
              else {}
            end)
          + {includeLlmDiagnostics:true}
        ')"
        printf '%s\n' "$selected_quick_reply_json" | jq '.' > "$case_dir/input-selected-quick-reply.json"
      fi
      timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      conversation_messages_json="$(jq -cn \
        --argjson previous "$previous_conversation" \
        --arg prompt "$previous_prompt" \
        --arg assistant "$previous_assistant_message" \
        --arg timestamp "$timestamp" \
        '$previous + [
          {id: ("journey-user-" + (($previous | length) | tostring)), role: "user", text: $prompt, createdAt: $timestamp},
          {id: ("journey-assistant-" + (($previous | length) | tostring)), role: "assistant", text: $assistant, createdAt: $timestamp}
        ]')"
    fi
    printf '%s\n' "$current_page_json" | jq '.' > "$case_dir/input-current-page.json"
    printf '%s\n' "$conversation_messages_json" | jq '.' > "$case_dir/input-conversation.json"
    echo
    echo "=== repetition $repetition | $case_id ==="
    set +e
    USER_PROMPT="$selected_user_prompt" \
      TARGET_APP="$(jq -r '.targetApp' "$case_dir/context.json")" \
      TARGET_COMPONENT_ID="$(jq -r '.targetComponentId' "$case_dir/context.json")" \
      CURRENT_ROUTE="$(jq -r '.currentRoute' "$case_dir/context.json")" \
      CURRENT_PAGE_JSON="$current_page_json" \
      SELECTED_WIDGET_KEY_JSON="$(jq -c '.selectedWidgetKey' "$case_dir/context.json")" \
      CONTEXT_HINTS_JSON="$selected_context_hints_json" \
      ACTIVE_SEMANTIC_DECISION_JSON="$active_semantic_decision_json" \
      CONVERSATION_MESSAGES_JSON="$conversation_messages_json" \
      SESSION_ID="$session_id" \
      ARTIFACTS_DIR="$case_dir" \
      BASE_URL="$BASE_URL" \
      ORIGIN="$ORIGIN" \
      TENANT_ID="$TENANT_ID" \
      USER_ID="$USER_ID" \
      ENVIRONMENT="$ENVIRONMENT" \
      PROVIDER="$PROVIDER" \
      MODEL="$MODEL" \
      REQUIRE_TOOL_PLAN="$(jq -r 'if .expected.terminal.preview == "forbidden" then "false" else "true" end' "$case_dir/case.json")" \
      STREAM_TIMEOUT_SECONDS="$STREAM_TIMEOUT_SECONDS" \
      "$SCRIPT_DIR/run-agentic-turn-pre-intent-local.sh" 2>&1 | tee "$case_dir/runner.log"
    runner_exit="${PIPESTATUS[0]}"
    set -e
    printf '%s\n' "$runner_exit" > "$case_dir/runner-exit-code.txt"

    persistence_apply="$(jq -r '.expected.persistence.apply // empty' "$case_dir/case.json")"
    if [[ "$runner_exit" = "0" && "$persistence_apply" = "required" ]]; then
      echo "--- transactional create/readback/duplicate-guard/cleanup proof ---"
      set +e
      ARTIFACTS_DIR="$case_dir" \
        COMPONENT_ID="$(jq -r '.contextHints.agenticApplyTarget.componentId // empty' "$case_dir/context.json")" \
        BASE_URL="$BASE_URL" \
        ORIGIN="$ORIGIN" \
        TENANT_ID="$TENANT_ID" \
        USER_ID="$USER_ID" \
        ENVIRONMENT="$ENVIRONMENT" \
        "$SCRIPT_DIR/run-assistant-consistency-transaction-local.sh" \
        2>&1 | tee "$case_dir/transaction.log"
      transaction_exit="${PIPESTATUS[0]}"
      set -e
      printf '%s\n' "$transaction_exit" > "$case_dir/transaction-exit-code.txt"
    fi
  done < <(jq -r '.runs[].directory' "$ARTIFACTS_DIR/execution-plan.json")
else
  echo "Reusing existing artifacts; backend and provider calls were skipped."
fi

PROVIDER="$PROVIDER" \
MODEL="$MODEL" \
RELEASE_GATE="$RELEASE_GATE" \
ENFORCE_LATENCY="$ENFORCE_LATENCY" \
MAX_FIRST_FEEDBACK_SECONDS="$MAX_FIRST_FEEDBACK_SECONDS" \
MAX_GUIDANCE_SECONDS="$MAX_GUIDANCE_SECONDS" \
MAX_AUTHORING_SECONDS="$MAX_AUTHORING_SECONDS" \
MAX_TOKENS_PER_RUN="$MAX_TOKENS_PER_RUN" \
MAX_ESTIMATED_COST_USD_MICROS_PER_RUN="$MAX_ESTIMATED_COST_USD_MICROS_PER_RUN" \
ENFORCE_EFFICIENCY="$ENFORCE_EFFICIENCY" \
python3 - "$ARTIFACTS_DIR" "$PRICING_SNAPSHOT_PATH" <<'PY'
import json
import math
import os
import pathlib
import re
import statistics
import sys
import unicodedata
from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_UP

base = pathlib.Path(sys.argv[1])
pricing_path = pathlib.Path(sys.argv[2])
plan = json.loads((base / "execution-plan.json").read_text(encoding="utf-8"))
pricing = json.loads(pricing_path.read_text(encoding="utf-8"))
if pricing.get("version") != "1.0.0" or pricing.get("kind") != "praxis.ai-provider-pricing-snapshot":
    raise SystemExit("Unsupported provider pricing snapshot version or kind.")
provider = os.environ["PROVIDER"]
model = os.environ["MODEL"]
release_gate = os.environ.get("RELEASE_GATE", "false").lower() == "true"
enforce_latency = os.environ.get("ENFORCE_LATENCY", "false").lower() == "true"
enforce_efficiency = os.environ.get("ENFORCE_EFFICIENCY", "false").lower() == "true"
max_first_feedback = float(os.environ.get("MAX_FIRST_FEEDBACK_SECONDS", "2"))
max_guidance = float(os.environ.get("MAX_GUIDANCE_SECONDS", "12"))
max_authoring = float(os.environ.get("MAX_AUTHORING_SECONDS", "45"))
max_tokens_per_run = int(os.environ.get("MAX_TOKENS_PER_RUN", "12000"))
max_cost_micros_per_run = int(os.environ.get("MAX_ESTIMATED_COST_USD_MICROS_PER_RUN", "10000"))

def non_negative_int(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0

def pricing_entry(invocation_provider, invocation_model):
    for entry in pricing.get("entries") or []:
        if entry.get("provider") != invocation_provider:
            continue
        if entry.get("model") == invocation_model:
            return entry
        if any(invocation_model.startswith(prefix) for prefix in entry.get("modelPrefixes") or []):
            return entry
    return None

def efficiency_projection(terminal_payload):
    diagnostics = terminal_payload.get("decisionDiagnostics")
    diagnostics = diagnostics if isinstance(diagnostics, dict) else {}
    telemetry = diagnostics.get("providerTelemetry")
    telemetry = telemetry if isinstance(telemetry, dict) else {}
    invocations = telemetry.get("providerInvocations")
    invocations = invocations if isinstance(invocations, list) else []
    successful = [
        item for item in invocations
        if isinstance(item, dict) and item.get("status") == "success"
    ]
    total_tokens = 0
    priced_count = 0
    cost_micros = Decimal("0")
    missing_usage = []
    missing_pricing = []
    for invocation in successful:
        invocation_provider = str(invocation.get("provider") or "")
        invocation_model = str(invocation.get("model") or "")
        input_tokens = invocation.get("inputTokens")
        output_tokens = invocation.get("outputTokens")
        if not non_negative_int(input_tokens) or not non_negative_int(output_tokens):
            missing_usage.append(f"{invocation_provider}:{invocation_model}")
            continue
        invocation_total = invocation.get("totalTokens")
        total_tokens += invocation_total if non_negative_int(invocation_total) else input_tokens + output_tokens
        entry = pricing_entry(invocation_provider, invocation_model)
        if entry is None:
            missing_pricing.append(f"{invocation_provider}:{invocation_model}")
            continue
        cached_tokens = invocation.get("cacheReadInputTokens")
        cached_tokens = cached_tokens if non_negative_int(cached_tokens) else 0
        cached_tokens = min(cached_tokens, input_tokens)
        uncached_tokens = input_tokens - cached_tokens
        cost_micros += Decimal(uncached_tokens) * Decimal(str(entry["inputUsdPerMillion"]))
        cost_micros += Decimal(cached_tokens) * Decimal(str(entry["cachedInputUsdPerMillion"]))
        cost_micros += Decimal(output_tokens) * Decimal(str(entry["outputUsdPerMillion"]))
        priced_count += 1
    rounded_cost = int(cost_micros.quantize(Decimal("1"), rounding=ROUND_HALF_UP))
    complete = bool(successful) and not missing_usage and not missing_pricing and priced_count == len(successful)
    return {
        "providerInvocationCount": len(invocations),
        "successfulInvocationCount": len(successful),
        "pricedInvocationCount": priced_count,
        "totalTokens": total_tokens,
        "estimatedCostUsdMicros": rounded_cost if complete else None,
        "costEstimateComplete": complete,
        "missingUsage": sorted(set(missing_usage)),
        "missingPricing": sorted(set(missing_pricing)),
        "pricingSnapshotVersion": pricing.get("version"),
        "pricingCapturedAt": pricing.get("capturedAt"),
    }

def event_payload(event):
    payload = event.get("payload")
    return payload if isinstance(payload, dict) else {}

def event_phase(event):
    return event_payload(event).get("phase") or event.get("phase") or ""

def timestamp(event):
    value = event.get("timestamp")
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
    except ValueError:
        return None

def elapsed(first, last):
    start = timestamp(first)
    end = timestamp(last)
    if start is None or end is None:
        return None
    return round((end - start).total_seconds(), 3)

def normalized(value):
    text = unicodedata.normalize("NFKD", str(value or "")).encode("ascii", "ignore").decode("ascii")
    return re.sub(r"\s+", " ", text.lower()).strip()

def collect_paths(value, key=""):
    paths = []
    if isinstance(value, dict):
        for child_key, child in value.items():
            if child_key in {"resourcePath", "submitUrl"} and isinstance(child, str) and child.startswith("/api/"):
                paths.append(child)
            paths.extend(collect_paths(child, child_key))
    elif isinstance(value, list):
        for child in value:
            paths.extend(collect_paths(child, key))
    return sorted(set(paths))

MISSING = object()

def json_pointer(value, pointer):
    if not isinstance(pointer, str) or not pointer.startswith("/"):
        return MISSING
    current = value
    for raw_token in pointer[1:].split("/"):
        token = raw_token.replace("~1", "/").replace("~0", "~")
        if isinstance(current, dict) and token in current:
            current = current[token]
            continue
        if isinstance(current, list) and token.isdigit() and int(token) < len(current):
            current = current[int(token)]
            continue
        return MISSING
    return current

def same_json_value(actual, expected):
    if isinstance(actual, bool) or isinstance(expected, bool):
        return type(actual) is type(expected) and actual == expected
    return actual == expected


def widget_inputs(widget):
    if not isinstance(widget, dict):
        return {}
    direct = widget.get("inputs")
    if isinstance(direct, dict):
        return direct
    definition = widget.get("definition")
    if isinstance(definition, dict) and isinstance(definition.get("inputs"), dict):
        return definition["inputs"]
    return {}

rows = []
for run in plan["runs"]:
    case_dir = base / run["directory"]
    case = json.loads((case_dir / "case.json").read_text(encoding="utf-8"))
    context = json.loads((case_dir / "context.json").read_text(encoding="utf-8"))
    expected = case["expected"]
    persistence_expected = expected.get("persistence")
    failures = []
    runner_exit_path = case_dir / "runner-exit-code.txt"
    runner_exit = int(runner_exit_path.read_text().strip()) if runner_exit_path.exists() else None
    if runner_exit not in (0, None):
        failures.append(f"single-turn runner exited with {runner_exit}")

    transaction = None
    if isinstance(persistence_expected, dict):
        transaction_exit_path = case_dir / "transaction-exit-code.txt"
        transaction_exit = (
            int(transaction_exit_path.read_text().strip())
            if transaction_exit_path.exists()
            else None
        )
        transaction_summary_path = case_dir / "transaction-summary.json"
        if transaction_exit != 0:
            failures.append(f"transactional proof exited with {transaction_exit!r}")
        if transaction_summary_path.exists():
            transaction = json.loads(transaction_summary_path.read_text(encoding="utf-8"))
            transaction_checks = {
                "applied": True,
                "exactReadback": True,
                "duplicateCreateBlocked": True,
                "stateUnchangedAfterDuplicate": True,
                "widgetCountStable": True,
                "cleanupDeleted": True,
            }
            for field, expected_value in transaction_checks.items():
                if transaction.get(field) is not expected_value:
                    failures.append(
                        f"transaction {field} {transaction.get(field)!r} expected {expected_value!r}"
                    )
            initial_version = transaction.get("initialVersion")
            if not isinstance(initial_version, int) or initial_version != 1:
                failures.append(
                    f"transaction initial version {initial_version!r} does not prove isolated create"
                )
        else:
            failures.append("transaction summary absent")

    events_path = case_dir / "turn.events.jsonl"
    events = []
    if events_path.exists():
        for line in events_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                events.append(json.loads(line))
    if not events:
        failures.append("no SSE events captured")
        rows.append({
            **run,
            "persistenceExpected": isinstance(persistence_expected, dict),
            "passed": False,
            "failures": failures,
        })
        continue

    terminal = next((event for event in events if event.get("type") in {"result", "error", "cancelled"}), None)
    if terminal is None:
        failures.append("no terminal event")
        rows.append({
            **run,
            "persistenceExpected": isinstance(persistence_expected, dict),
            "passed": False,
            "failures": failures,
        })
        continue

    terminal_payload = event_payload(terminal)
    intent = terminal_payload.get("intentResolution")
    intent = intent if isinstance(intent, dict) else {}
    terminal_expected = expected["terminal"]
    intent_expected = expected["intent"]

    if terminal.get("type") not in terminal_expected["eventTypes"]:
        failures.append(f"terminal type {terminal.get('type')!r} not in {terminal_expected['eventTypes']}")
    for field, allowed in (
        ("operationKind", intent_expected["operationKinds"]),
        ("artifactKind", intent_expected["artifactKinds"]),
        ("changeKind", intent_expected["changeKinds"]),
    ):
        if intent.get(field) not in allowed:
            failures.append(f"{field} {intent.get(field)!r} not in {allowed}")

    selected = intent.get("selectedCandidate")
    selected = selected if isinstance(selected, dict) else {}
    selected_resource = selected.get("resourcePath")
    if "resourcePaths" in intent_expected and selected_resource not in intent_expected["resourcePaths"]:
        failures.append(f"resourcePath {selected_resource!r} not in {intent_expected['resourcePaths']}")
    selected_submit_url = selected.get("submitUrl")
    if "submitUrls" in intent_expected and selected_submit_url not in intent_expected["submitUrls"]:
        failures.append(f"submitUrl {selected_submit_url!r} not in {intent_expected['submitUrls']}")

    can_apply = terminal_payload.get("canApply")
    if can_apply is not terminal_expected["canApply"]:
        failures.append(f"canApply {can_apply!r} expected {terminal_expected['canApply']!r}")

    preview = terminal_payload.get("preview")
    has_preview = isinstance(preview, dict) and bool(preview)
    preview_expectation = terminal_expected["preview"]
    if preview_expectation == "required" and not has_preview:
        failures.append("preview required but absent")
    if preview_expectation == "forbidden" and has_preview:
        failures.append("preview forbidden but present")

    decision_diagnostics = terminal_payload.get("decisionDiagnostics")
    decision_diagnostics = decision_diagnostics if isinstance(decision_diagnostics, dict) else {}
    minimum_semantic_axis_count = terminal_expected.get("minimumSemanticAxisCount")
    semantic_axis_count = decision_diagnostics.get("semanticAxisCount")
    semantic_axis_verified_count = decision_diagnostics.get("semanticAxisVerifiedCount")
    if isinstance(minimum_semantic_axis_count, int):
        if not isinstance(semantic_axis_count, int) or semantic_axis_count < minimum_semantic_axis_count:
            failures.append(
                f"semanticAxisCount {semantic_axis_count!r} below minimum {minimum_semantic_axis_count}"
            )
    if terminal_expected.get("requireAllSemanticAxesVerified") is True:
        if not isinstance(semantic_axis_count, int) or semantic_axis_count <= 0:
            failures.append("semantic axes must exist before verification can pass")
        elif semantic_axis_verified_count != semantic_axis_count:
            failures.append(
                f"semanticAxisVerifiedCount {semantic_axis_verified_count!r} expected {semantic_axis_count!r}"
            )

    column_fields = []
    selected_config = {}
    if has_preview:
        composition = preview.get("uiCompositionPlan")
        composition = composition if isinstance(composition, dict) else {}
        if not isinstance(composition.get("widgets"), list):
            compiled_patch = preview.get("compiledFormPatch")
            compiled_patch = compiled_patch if isinstance(compiled_patch, dict) else {}
            patch = compiled_patch.get("patch") if isinstance(compiled_patch.get("patch"), dict) else {}
            page = patch.get("page") if isinstance(patch.get("page"), dict) else {}
            composition = page if isinstance(page.get("widgets"), list) else {}
        selected_widget_key = context.get("selectedWidgetKey")
        for widget in composition.get("widgets") or []:
            if not isinstance(widget, dict):
                continue
            inputs = widget_inputs(widget)
            config = inputs.get("config") if isinstance(inputs.get("config"), dict) else {}
            if widget.get("key") == selected_widget_key:
                selected_config = config
            for column in config.get("columns") or []:
                if isinstance(column, dict) and isinstance(column.get("field"), str):
                    column_fields.append(column["field"])
        if not selected_config:
            selected_config = next((
                inputs.get("config", {})
                for widget in composition.get("widgets") or []
                for inputs in (widget_inputs(widget),)
                if isinstance(widget, dict)
                and isinstance(inputs.get("config"), dict)
                and isinstance(inputs.get("config", {}).get("columns"), list)
            ), {})
    page_assertions = case.get("pageAssertions")
    asserted_config_values = {}
    if isinstance(page_assertions, dict):
        missing_columns = [
            field for field in page_assertions.get("requiredColumnFields", [])
            if field not in column_fields
        ]
        if missing_columns:
            failures.append(f"preview missing required columns: {missing_columns}")
        if page_assertions.get("uniqueColumnFields") is True and len(column_fields) != len(set(column_fields)):
            failures.append(f"preview contains duplicated column fields: {column_fields}")
        columns_by_field = {
            column.get("field"): column
            for column in selected_config.get("columns", [])
            if isinstance(column, dict) and isinstance(column.get("field"), str)
        }
        for field, expected_properties in page_assertions.get("requiredColumnProperties", {}).items():
            column = columns_by_field.get(field)
            if column is None:
                failures.append(f"preview cannot assert properties for missing column {field!r}")
                continue
            for property_name, expected_value in expected_properties.items():
                actual_value = column.get(property_name, MISSING)
                if actual_value is MISSING:
                    failures.append(
                        f"preview column {field!r} missing required property {property_name!r}"
                    )
                elif not same_json_value(actual_value, expected_value):
                    failures.append(
                        f"preview column {field!r} property {property_name!r} "
                        f"{actual_value!r} expected {expected_value!r}"
                    )
        for pointer, expected_value in page_assertions.get("requiredConfigValues", {}).items():
            actual_value = json_pointer(selected_config, pointer)
            asserted_config_values[pointer] = None if actual_value is MISSING else actual_value
            if actual_value is MISSING:
                failures.append(f"preview config missing required value at {pointer!r}")
            elif not same_json_value(actual_value, expected_value):
                failures.append(
                    f"preview config value at {pointer!r} {actual_value!r} expected {expected_value!r}"
                )

    start_response_path = case_dir / "turn.start.response.json"
    start_response = (
        json.loads(start_response_path.read_text(encoding="utf-8"))
        if start_response_path.exists()
        else {}
    )
    thread_id = start_response.get("threadId")
    turn_id = start_response.get("turnId")
    active_decision_id = event_payload(events[0]).get("activeSemanticDecisionId")
    lineage = case.get("lineage")
    if isinstance(lineage, dict):
        previous_directory = run.get("previousDirectory")
        previous_dir = base / previous_directory if previous_directory else None
        previous_start_path = previous_dir / "turn.start.response.json" if previous_dir else None
        previous_events_path = previous_dir / "turn.events.jsonl" if previous_dir else None
        if not previous_start_path or not previous_start_path.exists() or not previous_events_path.exists():
            failures.append("previous journey evidence absent")
        else:
            previous_start = json.loads(previous_start_path.read_text(encoding="utf-8"))
            previous_events = [
                json.loads(line)
                for line in previous_events_path.read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            previous_terminal = next(
                (event for event in previous_events if event.get("type") in {"result", "error", "cancelled"}),
                {},
            )
            quick_reply_selection = case.get("quickReplySelection")
            if isinstance(quick_reply_selection, dict):
                selected_reply_id = quick_reply_selection.get("replyId")
                selected_replies = [
                    reply
                    for reply in event_payload(previous_terminal).get("quickReplies") or []
                    if isinstance(reply, dict) and reply.get("id") == selected_reply_id
                ]
                previous_decision_id = (
                    selected_replies[0].get("semanticDecision", {}).get("decisionId")
                    if len(selected_replies) == 1
                    and isinstance(selected_replies[0].get("semanticDecision"), dict)
                    else None
                )
            else:
                previous_decision_id = event_payload(previous_terminal).get("decisionDiagnostics", {}).get(
                    "semanticDecisionId"
                )
            if lineage.get("sameThread") is True and thread_id != previous_start.get("threadId"):
                failures.append(
                    f"thread lineage changed {previous_start.get('threadId')!r}->{thread_id!r}"
                )
            if lineage.get("distinctTurn") is True and turn_id == previous_start.get("turnId"):
                failures.append(f"turn lineage reused turnId {turn_id!r}")
            if (
                lineage.get("activeDecisionFromPreviousTurn") is True
                and active_decision_id != previous_decision_id
            ):
                failures.append(
                    f"active decision {active_decision_id!r} does not continue {previous_decision_id!r}"
                )

    quick_replies = terminal_payload.get("quickReplies")
    quick_replies = quick_replies if isinstance(quick_replies, list) else []
    if len(quick_replies) < terminal_expected["minimumQuickReplies"]:
        failures.append(
            f"quickReplies {len(quick_replies)} below minimum {terminal_expected['minimumQuickReplies']}"
        )

    phases = {event_phase(event) for event in events}
    forbidden_phases = sorted(set(terminal_expected["forbiddenPhases"]) & phases)
    if forbidden_phases:
        failures.append(f"forbidden phases emitted: {forbidden_phases}")

    message = terminal_payload.get("assistantMessage") or ""
    message_expected = expected["assistantMessage"]
    if len(message.strip()) < message_expected["minimumCharacters"]:
        failures.append(
            f"assistantMessage length {len(message.strip())} below minimum {message_expected['minimumCharacters']}"
        )
    normalized_message = normalized(message)
    for group in message_expected["requiredConceptGroups"]:
        if not any(normalized(term) in normalized_message for term in group):
            failures.append(f"assistantMessage missing concept group {group}")
    leaked_terms = [term for term in message_expected["forbiddenTerms"] if normalized(term) in normalized_message]
    if leaked_terms:
        failures.append(f"assistantMessage exposes forbidden terms {leaked_terms}")

    safety = expected["safety"]
    grounded_paths = collect_paths({
        "selectedCandidate": selected,
        "preview": preview,
        "assistantContent": terminal_payload.get("assistantContent"),
    })
    if safety["forbidUnlistedResourcePaths"]:
        unexpected_paths = [
            path for path in grounded_paths
            if not any(path.startswith(prefix) for prefix in safety["allowedResourcePathPrefixes"])
        ]
        if unexpected_paths:
            failures.append(f"unlisted resource paths emitted: {unexpected_paths}")
    if can_apply is True and safety["mutationRequiresPreview"] and not has_preview:
        failures.append("mutation became applicable without preview")

    efficiency = efficiency_projection(terminal_payload)
    if enforce_efficiency:
        if efficiency["successfulInvocationCount"] <= 0:
            failures.append("efficiency telemetry has no successful provider invocation")
        if efficiency["missingUsage"]:
            failures.append(f"provider usage unavailable for {efficiency['missingUsage']}")
        if efficiency["missingPricing"]:
            failures.append(f"pricing snapshot does not cover {efficiency['missingPricing']}")
        if efficiency["totalTokens"] > max_tokens_per_run:
            failures.append(
                f"total tokens {efficiency['totalTokens']} exceed {max_tokens_per_run} per run"
            )
        estimated_cost = efficiency["estimatedCostUsdMicros"]
        if estimated_cost is None:
            failures.append("estimated provider cost is incomplete")
        elif estimated_cost > max_cost_micros_per_run:
            failures.append(
                f"estimated cost {estimated_cost} USD micros exceeds {max_cost_micros_per_run} per run"
            )

    first_feedback = next((
        event for event in events
        if event.get("type") in {"status", "thought.step", "heartbeat"}
        and isinstance(event_payload(event).get("message"), str)
        and event_payload(event)["message"].strip()
    ), None)
    first_feedback_seconds = elapsed(events[0], first_feedback) if first_feedback else None
    duration_seconds = elapsed(events[0], terminal)
    duration_limit = (
        max_guidance
        if terminal_expected["canApply"] is False and preview_expectation == "forbidden"
        else max_authoring
    )
    if enforce_latency:
        if first_feedback_seconds is None or first_feedback_seconds > max_first_feedback:
            failures.append(
                f"first feedback {first_feedback_seconds!r}s exceeds {max_first_feedback:g}s"
            )
        if duration_seconds is None or duration_seconds > duration_limit:
            failures.append(f"duration {duration_seconds!r}s exceeds {duration_limit:g}s")

    rows.append({
        **run,
        "persistenceExpected": isinstance(persistence_expected, dict),
        "locale": case["locale"],
        "userPrompt": case["userPrompt"],
        "passed": not failures,
        "failures": failures,
        "actual": {
            "terminalType": terminal.get("type"),
            "operationKind": intent.get("operationKind"),
            "artifactKind": intent.get("artifactKind"),
            "changeKind": intent.get("changeKind"),
            "resourcePath": selected_resource,
            "submitUrl": selected_submit_url,
            "canApply": can_apply,
            "hasPreview": has_preview,
            "quickReplyCount": len(quick_replies),
            "assistantMessage": message,
            "groundedResourcePaths": grounded_paths,
            "threadId": thread_id,
            "turnId": turn_id,
            "activeSemanticDecisionId": active_decision_id,
            "columnFields": column_fields,
            "columnProperties": {
                field: properties
                for field, properties in (
                    (column.get("field"), column)
                    for column in selected_config.get("columns", [])
                    if isinstance(column, dict)
                )
                if isinstance(field, str)
            },
            "assertedConfigValues": asserted_config_values,
            "persistence": transaction,
            "efficiency": efficiency,
        },
        "timingSeconds": {
            "firstFeedback": first_feedback_seconds,
            "terminal": duration_seconds,
            "limitEnforced": enforce_latency,
        },
    })

total = len(rows)
passed = sum(1 for row in rows if row["passed"])
must_pass_rows = [row for row in rows if row["status"] == "must-pass"]
extended_rows = [row for row in rows if row["status"] == "extended"]
must_pass_accuracy = (
    sum(1 for row in must_pass_rows if row["passed"]) / len(must_pass_rows)
    if must_pass_rows else 1.0
)
extended_accuracy = (
    sum(1 for row in extended_rows if row["passed"]) / len(extended_rows)
    if extended_rows else None
)
durations = [row.get("timingSeconds", {}).get("terminal") for row in rows]
durations = [value for value in durations if isinstance(value, (int, float))]
sorted_durations = sorted(durations)
p95_terminal = (
    sorted_durations[max(0, math.ceil(len(sorted_durations) * 0.95) - 1)]
    if sorted_durations else None
)
efficiency_rows = [
    row.get("actual", {}).get("efficiency", {})
    for row in rows
]
token_counts = [
    item.get("totalTokens") for item in efficiency_rows
    if item.get("successfulInvocationCount", 0) > 0 and non_negative_int(item.get("totalTokens"))
]
cost_estimates = [
    item.get("estimatedCostUsdMicros") for item in efficiency_rows
    if non_negative_int(item.get("estimatedCostUsdMicros"))
]
transaction_rows = [
    row for row in rows
    if row.get("persistenceExpected") is True
]
transaction_passed = sum(
    1 for row in transaction_rows
    if isinstance(row.get("actual", {}).get("persistence"), dict)
    and not any(failure.startswith("transaction") for failure in row["failures"])
)

gate_failures = []
if must_pass_accuracy < 1.0:
    gate_failures.append(f"must-pass accuracy {must_pass_accuracy:.3f} is below 1.000")
if extended_accuracy is not None and extended_accuracy < 0.95:
    gate_failures.append(f"extended accuracy {extended_accuracy:.3f} is below 0.950")
if release_gate and plan["repetitions"] < 3:
    gate_failures.append("release gate requires three consecutive repetitions")

report = {
    "version": "1.0.0",
    "kind": "praxis.ai-authoring.assistant-consistency-gate-result",
    "provider": provider,
    "model": model,
    "profile": plan["profile"],
    "repetitions": plan["repetitions"],
    "releaseGate": release_gate,
    "latencyEnforced": enforce_latency,
    "efficiencyEnforced": enforce_efficiency,
    "pricingSnapshot": {
        "version": pricing.get("version"),
        "capturedAt": pricing.get("capturedAt"),
        "sourceUrl": pricing.get("sourceUrl"),
        "path": str(pricing_path),
    },
    "thresholds": {
        "maxFirstFeedbackSeconds": max_first_feedback,
        "maxGuidanceSeconds": max_guidance,
        "maxAuthoringSeconds": max_authoring,
        "maxTokensPerRun": max_tokens_per_run,
        "maxEstimatedCostUsdMicrosPerRun": max_cost_micros_per_run,
    },
    "summary": {
        "runCount": total,
        "passed": passed,
        "failed": total - passed,
        "accuracy": passed / total if total else 0.0,
        "mustPassAccuracy": must_pass_accuracy,
        "extendedAccuracy": extended_accuracy,
        "medianTerminalSeconds": statistics.median(durations) if durations else None,
        "p95TerminalSeconds": p95_terminal,
        "totalTokens": sum(token_counts),
        "averageTokensPerRun": (sum(token_counts) / len(token_counts)) if token_counts else None,
        "maxTokensPerRun": max(token_counts) if token_counts else None,
        "totalEstimatedCostUsdMicros": sum(cost_estimates),
        "averageEstimatedCostUsdMicrosPerRun": (
            sum(cost_estimates) / len(cost_estimates) if cost_estimates else None
        ),
        "maxEstimatedCostUsdMicrosPerRun": max(cost_estimates) if cost_estimates else None,
        "completeCostEstimateRunCount": len(cost_estimates),
        "transactionRunCount": len(transaction_rows),
        "transactionPassed": transaction_passed,
        "gatePassed": not gate_failures,
        "gateFailures": gate_failures,
    },
    "runs": rows,
}
(base / "gate-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

lines = [
    "# Praxis assistant consistency gate",
    "",
    f"- Provider/model: `{provider}` / `{model}`",
    f"- Profile: `{plan['profile']}`",
    f"- Repetitions: `{plan['repetitions']}`",
    f"- Passed: `{passed}/{total}`",
    f"- Must-pass accuracy: `{must_pass_accuracy:.1%}`",
    f"- Terminal median / p95: `{statistics.median(durations) if durations else None}` / `{p95_terminal}` seconds",
    f"- Tokens total / max per run: `{sum(token_counts)}` / `{max(token_counts) if token_counts else None}`",
    f"- Estimated cost total / max per run: `{sum(cost_estimates)}` / `{max(cost_estimates) if cost_estimates else None}` USD micros",
    f"- Pricing snapshot: `{pricing.get('version')}` captured `{pricing.get('capturedAt')}`",
    f"- Gate: `{'PASS' if not gate_failures else 'FAIL'}`",
    "",
    "| Run | Case | Turn | Family | Result | Terminal | Transaction | Seconds | Tokens | Cost USD micros |",
    "| --- | --- | --- | --- | --- | --- | --- | ---: | ---: | ---: |",
]
for row in rows:
    timing = row.get("timingSeconds", {}).get("terminal")
    timing_text = "" if timing is None else f"{timing:.3f}"
    actual = row.get("actual", {})
    if row.get("persistenceExpected") is True:
        transaction_text = (
            "PASS"
            if isinstance(actual.get("persistence"), dict)
            and not any(failure.startswith("transaction") for failure in row["failures"])
            else "FAIL"
        )
    else:
        transaction_text = "—"
    efficiency = actual.get("efficiency", {})
    tokens_text = efficiency.get("totalTokens", "")
    cost_text = efficiency.get("estimatedCostUsdMicros")
    cost_text = "" if cost_text is None else cost_text
    lines.append(
        f"| {row['repetition']} | {row['caseId']} | {row.get('turnId') or '—'} | {row['family']} | "
        f"{'PASS' if row['passed'] else 'FAIL'} | {actual.get('terminalType', '')} | "
        f"{transaction_text} | {timing_text} | {tokens_text} | {cost_text} |"
    )
if gate_failures:
    lines.extend(["", "## Gate failures", ""] + [f"- {failure}" for failure in gate_failures])
case_failures = [row for row in rows if row["failures"]]
if case_failures:
    lines.extend(["", "## Case failures", ""])
    for row in case_failures:
        lines.append(f"- `{row['caseId']}` run {row['repetition']}: {'; '.join(row['failures'])}")
(base / "gate-result.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
if gate_failures:
    raise SystemExit(1)
PY

echo "Gate report: $ARTIFACTS_DIR/gate-result.md"
