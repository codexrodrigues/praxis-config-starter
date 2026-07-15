#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CORPUS_PATH="${CORPUS_PATH:-$STARTER_ROOT/docs/ai/agentic-authoring/proofs/assistant-consistency-corpus.v1.json}"
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
    MODEL="${PRAXIS_AI_OPENAI_MODEL:-gpt-4.1-mini}"
  fi
fi
STREAM_TIMEOUT_SECONDS="${STREAM_TIMEOUT_SECONDS:-180}"
ARTIFACTS_DIR="${ARTIFACTS_DIR:-$STARTER_ROOT/artifacts/local-e2e/assistant-consistency-$(date +%Y%m%d-%H%M%S)}"
REUSE_EXISTING_ARTIFACTS="${REUSE_EXISTING_ARTIFACTS:-false}"
MAX_FIRST_FEEDBACK_SECONDS="${MAX_FIRST_FEEDBACK_SECONDS:-2}"
MAX_GUIDANCE_SECONDS="${MAX_GUIDANCE_SECONDS:-12}"
MAX_AUTHORING_SECONDS="${MAX_AUTHORING_SECONDS:-45}"
ENFORCE_LATENCY="${ENFORCE_LATENCY:-$RELEASE_GATE}"

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
    selected.append(case)

missing_requested = requested_ids - {case["id"] for case in selected}
if missing_requested:
    raise SystemExit(f"Requested cases not selected by profile or absent: {sorted(missing_requested)}")
if not selected:
    raise SystemExit("No corpus cases selected.")

runs = []
for repetition in range(1, repetitions + 1):
    for case in selected:
        relative = pathlib.Path(f"run-{repetition:02d}") / case["id"]
        case_dir = artifacts_dir / relative
        case_dir.mkdir(parents=True, exist_ok=True)
        context = contexts[case["contextRef"]]
        (case_dir / "case.json").write_text(
            json.dumps(case, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        (case_dir / "context.json").write_text(
            json.dumps(context, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        runs.append({
            "repetition": repetition,
            "caseId": case["id"],
            "family": case["family"],
            "status": case["status"],
            "directory": str(relative),
        })

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
echo "Profile: $PROFILE | repetitions: $REPETITIONS | provider: $PROVIDER | model: $MODEL"
echo "Artifacts: $ARTIFACTS_DIR"

if [[ "$REUSE_EXISTING_ARTIFACTS" != "true" ]]; then
  while IFS= read -r relative_dir; do
    case_dir="$ARTIFACTS_DIR/$relative_dir"
    case_id="$(jq -r '.id' "$case_dir/case.json")"
    repetition="$(basename "$(dirname "$relative_dir")" | sed 's/run-//')"
    echo
    echo "=== repetition $repetition | $case_id ==="
    set +e
    USER_PROMPT="$(jq -r '.userPrompt' "$case_dir/case.json")" \
      TARGET_APP="$(jq -r '.targetApp' "$case_dir/context.json")" \
      TARGET_COMPONENT_ID="$(jq -r '.targetComponentId' "$case_dir/context.json")" \
      CURRENT_ROUTE="$(jq -r '.currentRoute' "$case_dir/context.json")" \
      CURRENT_PAGE_JSON="$(jq -c '.currentPage' "$case_dir/context.json")" \
      SELECTED_WIDGET_KEY_JSON="$(jq -c '.selectedWidgetKey' "$case_dir/context.json")" \
      CONTEXT_HINTS_JSON="$(jq -c '.contextHints' "$case_dir/context.json")" \
      SESSION_ID="assistant-consistency-$repetition-$case_id" \
      ARTIFACTS_DIR="$case_dir" \
      BASE_URL="$BASE_URL" \
      ORIGIN="$ORIGIN" \
      TENANT_ID="$TENANT_ID" \
      USER_ID="$USER_ID" \
      ENVIRONMENT="$ENVIRONMENT" \
      PROVIDER="$PROVIDER" \
      MODEL="$MODEL" \
      STREAM_TIMEOUT_SECONDS="$STREAM_TIMEOUT_SECONDS" \
      "$SCRIPT_DIR/run-agentic-turn-pre-intent-local.sh" 2>&1 | tee "$case_dir/runner.log"
    runner_exit="${PIPESTATUS[0]}"
    set -e
    printf '%s\n' "$runner_exit" > "$case_dir/runner-exit-code.txt"

    persistence_apply="$(jq -r '.expected.persistence.apply // empty' "$case_dir/case.json")"
    if [[ "$runner_exit" = "0" && "$persistence_apply" = "required" ]]; then
      echo "--- transactional apply/readback/replay/cleanup proof ---"
      set +e
      ARTIFACTS_DIR="$case_dir" \
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
python3 - "$ARTIFACTS_DIR" <<'PY'
import json
import os
import pathlib
import re
import statistics
import sys
import unicodedata
from datetime import datetime, timezone

base = pathlib.Path(sys.argv[1])
plan = json.loads((base / "execution-plan.json").read_text(encoding="utf-8"))
provider = os.environ["PROVIDER"]
model = os.environ["MODEL"]
release_gate = os.environ.get("RELEASE_GATE", "false").lower() == "true"
enforce_latency = os.environ.get("ENFORCE_LATENCY", "false").lower() == "true"
max_first_feedback = float(os.environ.get("MAX_FIRST_FEEDBACK_SECONDS", "2"))
max_guidance = float(os.environ.get("MAX_GUIDANCE_SECONDS", "12"))
max_authoring = float(os.environ.get("MAX_AUTHORING_SECONDS", "45"))

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

rows = []
for run in plan["runs"]:
    case_dir = base / run["directory"]
    case = json.loads((case_dir / "case.json").read_text(encoding="utf-8"))
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
                "conditionalReplayApplied": True,
                "replayStateExact": True,
                "widgetCountStable": True,
                "staleRetryBlocked": True,
                "cleanupDeleted": True,
            }
            for field, expected_value in transaction_checks.items():
                if transaction.get(field) is not expected_value:
                    failures.append(
                        f"transaction {field} {transaction.get(field)!r} expected {expected_value!r}"
                    )
            initial_version = transaction.get("initialVersion")
            replay_version = transaction.get("replayVersion")
            if not isinstance(initial_version, int) or replay_version != initial_version + 1:
                failures.append(
                    f"transaction versions {initial_version!r}->{replay_version!r} do not prove conditional replay"
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

    first_feedback = next((
        event for event in events
        if event.get("type") in {"status", "thought.step", "heartbeat"}
        and isinstance(event_payload(event).get("message"), str)
        and event_payload(event)["message"].strip()
    ), None)
    first_feedback_seconds = elapsed(events[0], first_feedback) if first_feedback else None
    duration_seconds = elapsed(events[0], terminal)
    duration_limit = max_guidance if case["family"] == "platform-discovery" else max_authoring
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
            "persistence": transaction,
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
    "summary": {
        "runCount": total,
        "passed": passed,
        "failed": total - passed,
        "accuracy": passed / total if total else 0.0,
        "mustPassAccuracy": must_pass_accuracy,
        "extendedAccuracy": extended_accuracy,
        "medianTerminalSeconds": statistics.median(durations) if durations else None,
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
    f"- Gate: `{'PASS' if not gate_failures else 'FAIL'}`",
    "",
    "| Run | Case | Family | Result | Terminal | Transaction | Seconds |",
    "| --- | --- | --- | --- | --- | --- | ---: |",
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
    lines.append(
        f"| {row['repetition']} | {row['caseId']} | {row['family']} | "
        f"{'PASS' if row['passed'] else 'FAIL'} | {actual.get('terminalType', '')} | "
        f"{transaction_text} | {timing_text} |"
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
