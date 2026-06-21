#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8088}"
ORIGIN="${ORIGIN:-http://localhost:4003}"
PROVIDER="${PROVIDER:-openai}"
if [[ -z "${MODEL:-}" ]]; then
  if [[ "$PROVIDER" == "gemini" ]]; then
    MODEL="${PRAXIS_AI_GEMINI_MODEL:-gemini-2.5-flash}"
  else
    MODEL="${PRAXIS_AI_OPENAI_MODEL:-gpt-4.1-mini}"
  fi
fi
STREAM_TIMEOUT_SECONDS="${STREAM_TIMEOUT_SECONDS:-180}"
ARTIFACTS_DIR="${ARTIFACTS_DIR:-$STARTER_ROOT/artifacts/local-e2e/agentic-turn-pre-intent-matrix-$(date +%Y%m%d-%H%M%S)}"
REUSE_EXISTING_ARTIFACTS="${REUSE_EXISTING_ARTIFACTS:-false}"

mkdir -p "$ARTIFACTS_DIR"

cases=(
  "empregados|/api/human-resources/funcionarios|quero criar algo que mostre informacoes dos empregados"
  "colaboradores|/api/human-resources/funcionarios|quero uma tela para acompanhar colaboradores"
  "funcionarios_typo|/api/human-resources/funcionarios|mostra info dos funsionarios"
  "funcionarios_transcricao_ruim|/api/human-resources/funcionarios|eu queria uma tela pra ver os fucionaro da empresa tipo nome cargo area essas coisa"
  "pessoal_aberto|/api/human-resources/funcionarios|preciso ver como esta meu pessoal"
  "pessoas_resumo_perfil|/api/human-resources/vw-perfil-heroi|mostre uma ficha de resumo das pessoas da empresa com nome contato cargo e departamento"
  "staff_en|/api/human-resources/funcionarios|build me a staff overview"
  "perfil_individual_funcionario|/api/human-resources/vw-perfil-heroi|quero uma tela de perfil individual do funcionário"
  "visao_resumida_funcionario|/api/human-resources/vw-perfil-heroi|visão resumida de funcionário"
  "analytics_folha_explicito|/api/human-resources/vw-analytics-folha-pagamento|quero um painel analítico de folha de pagamento por departamento"
  "fornecedores|/api/procurement/contracts,/api/procurement/suppliers|quero visualizar contratos de fornecedores"
  "narrativa_colaboradores|/api/human-resources/funcionarios|Estou reorganizando uma rotina de gestao e queria uma tela para acompanhar o time da empresa. Preciso enxergar quem sao as pessoas, alguma visao geral por area e conseguir abrir detalhes depois, mas ainda nao sei se isso deve virar tabela, painel ou outra coisa."
  "narrativa_longa_colaboradores_confusa|/api/human-resources/funcionarios|Eu estava pensando em melhorar um processo interno porque hoje cada area manda uma planilha diferente, as pessoas falam funcionario, colaborador, pessoal, time, as vezes escrevem sem acento e fica tudo meio baguncado. Nao sei ainda se quero dashboard ou tabela, talvez um lugar para comecar vendo quem sao essas pessoas, quais cargos elas ocupam, em que departamento estao e depois abrir detalhes se precisar. Pode montar uma tela para isso?"
  "narrativa_longa_perfil_individual|/api/human-resources/vw-perfil-heroi|Na conversa com o RH surgiu uma necessidade um pouco especifica. Eles nao querem primeiro uma lista geral, pelo menos nao agora. A ideia e clicar ou procurar uma pessoa e ter uma tela de perfil individual, como uma ficha resumida do funcionario, mostrando os principais dados da pessoa e deixando claro quem ela e dentro da empresa."
  "narrativa_longa_analytics_folha|/api/human-resources/vw-analytics-folha-pagamento|Estou tentando entender melhor custos e distribuicao do time. O pedido veio meio aberto, mas o objetivo e olhar indicadores agregados de folha de pagamento, comparar departamentos e enxergar metricas gerais, nao editar cadastro de funcionario nem abrir uma ficha individual."
  "narrativa_fornecedores|/api/procurement/contracts,/api/procurement/suppliers|Na operacao de compras eu preciso entender melhor os acordos com parceiros externos. Minha ideia e ter uma tela para acompanhar contratos, status e informacoes principais dos fornecedores sem precisar escolher manualmente a API agora."
)

echo "Running pre-intent authoring matrix against $BASE_URL"
echo "Artifacts: $ARTIFACTS_DIR"

if [[ "$REUSE_EXISTING_ARTIFACTS" == "true" ]]; then
  echo "Reusing existing case artifacts; no backend or LLM calls will be executed."
else
  for entry in "${cases[@]}"; do
    slug="${entry%%|*}"
    rest="${entry#*|}"
    expected="${rest%%|*}"
    prompt="${rest#*|}"
    echo
    echo "=== $slug ==="
    echo "expected: $expected"
    echo "$prompt"
    mkdir -p "$ARTIFACTS_DIR/$slug"
    printf '%s\n' "$expected" > "$ARTIFACTS_DIR/$slug/expected-resource-paths.txt"
    USER_PROMPT="$prompt" \
      ARTIFACTS_DIR="$ARTIFACTS_DIR/$slug" \
      BASE_URL="$BASE_URL" \
      ORIGIN="$ORIGIN" \
      PROVIDER="$PROVIDER" \
      MODEL="$MODEL" \
      STREAM_TIMEOUT_SECONDS="$STREAM_TIMEOUT_SECONDS" \
      "$SCRIPT_DIR/run-agentic-turn-pre-intent-local.sh"
  done
fi

python3 - "$ARTIFACTS_DIR" <<'PY'
import json
import os
import pathlib
import re
import sys
from collections import Counter
from datetime import datetime, timezone

base = pathlib.Path(sys.argv[1])
rows = []

def event_payload(event):
    value = event.get("payload")
    return value if isinstance(value, dict) else {}

technical_message_pattern = re.compile(
    r"\b("
    r"Agentic|Governed|Runtime|Retrieved|Granular|"
    r"preview planning|tool loop|backend API resource search|"
    r"Post-intent|Resource candidates|Compiled preview|"
    r"watchdog|schema|JSON"
    r")\b"
)

def public_message(event):
    payload = event_payload(event)
    for key in ("message", "summary", "label"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""

def stream_feedback_audit(events):
    status_events = [event for event in events if event.get("type") == "status"]
    heartbeat_events = [event for event in events if event.get("type") == "heartbeat"]
    feedback_events = status_events + heartbeat_events
    missing = []
    redacted = []
    technical = []
    for event in feedback_events:
        message = public_message(event)
        phase = event_payload(event).get("phase") or event.get("phase") or ""
        item = {
            "type": event.get("type"),
            "phase": phase,
        }
        if not message:
            missing.append(item)
            continue
        if "[REDACTED]" in message:
            redacted.append({**item, "message": message})
        if technical_message_pattern.search(message):
            technical.append({**item, "message": message})
    return {
        "statusEventCount": len(status_events),
        "heartbeatEventCount": len(heartbeat_events),
        "feedbackEventCount": len(feedback_events),
        "streamFeedbackMissingMessageCount": len(missing),
        "streamFeedbackRedactedMessageCount": len(redacted),
        "streamFeedbackTechnicalMessageCount": len(technical),
        "streamFeedbackMissingMessages": missing,
        "streamFeedbackRedactedMessages": redacted,
        "streamFeedbackTechnicalMessages": technical,
    }

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

def resource_path(candidate):
    value = candidate.get("resourcePath") if isinstance(candidate, dict) else None
    return value or ""

def evidence_list(candidate):
    value = candidate.get("evidence") if isinstance(candidate, dict) else None
    return value if isinstance(value, list) else []

def semantic_roles(candidate):
    return [item for item in evidence_list(candidate) if isinstance(item, str) and item.startswith("semantic-role:")]

def compact_evidence_bundle(candidate):
    bundle = candidate.get("evidenceBundle") if isinstance(candidate, dict) else None
    if not isinstance(bundle, dict):
        return None
    evidence = bundle.get("evidence")
    evidence = evidence if isinstance(evidence, list) else []
    return {
        "schemaVersion": bundle.get("schemaVersion"),
        "retrievalSource": bundle.get("retrievalSource"),
        "evidenceCount": bundle.get("evidenceCount", len(evidence)),
        "evidenceRefs": [
            {
                "source": item.get("source"),
                "kind": item.get("kind"),
                "ref": item.get("ref"),
                "confidence": item.get("confidence"),
                "matchedTermCount": len(item.get("matchedTerms") if isinstance(item.get("matchedTerms"), list) else []),
            }
            for item in evidence
            if isinstance(item, dict)
        ],
    }

def candidate_audit(index, candidate):
    return {
        "rank": index,
        "resourcePath": resource_path(candidate),
        "score": candidate.get("score"),
        "evidence": evidence_list(candidate),
        "semanticRoles": semantic_roles(candidate),
        "reason": candidate.get("reason"),
        "evidenceBundle": compact_evidence_bundle(candidate),
    }

def expected_paths(case_dir):
    path = case_dir / "expected-resource-paths.txt"
    if not path.exists():
        return []
    return [item.strip() for item in path.read_text(encoding="utf-8").split(",") if item.strip()]

def candidate_rank(candidates, expected):
    normalized_expected = set(expected)
    for index, candidate in enumerate(candidates or [], start=1):
        if resource_path(candidate) in normalized_expected:
            return index
    return None

def reciprocal_rank(rank):
    return 0.0 if rank is None or rank <= 0 else 1.0 / rank

def top_candidate_margin(candidates):
    delta = top_candidate_raw_score_delta(candidates)
    if delta is None or delta < 0:
        return None
    return delta

def top_candidate_raw_score_delta(candidates):
    if len(candidates) < 2:
        return None
    first = candidates[0].get("score")
    second = candidates[1].get("score")
    if not isinstance(first, (int, float)) or not isinstance(second, (int, float)):
        return None
    return first - second

def top_candidate_ranking_note(candidates):
    delta = top_candidate_raw_score_delta(candidates)
    if delta is None or delta >= 0:
        return ""
    return "final-rank-overrides-raw-retrieval-score"

def selected_candidate_rank(candidates, selected_path):
    if not selected_path:
        return None
    for index, candidate in enumerate(candidates or [], start=1):
        if resource_path(candidate) == selected_path:
            return index
    return None

def duration_seconds(events):
    timestamps = [event_timestamp(event) for event in events]
    timestamps = [value for value in timestamps if value is not None]
    if len(timestamps) < 2:
        return None
    return round((max(timestamps) - min(timestamps)).total_seconds(), 3)

def phase_duration_seconds(events, phase_name):
    timestamps = [
        event_timestamp(event)
        for event in events
        if (event_payload(event).get("phase") or event.get("phase") or "") == phase_name
    ]
    timestamps = [value for value in timestamps if value is not None]
    if len(timestamps) < 2:
        return None
    return round((max(timestamps) - min(timestamps)).total_seconds(), 3)

def first_phase_index(events, phase_name):
    for index, event in enumerate(events):
        if (event_payload(event).get("phase") or event.get("phase") or "") == phase_name:
            return index
    return None

def first_event_type_index(events, event_type):
    for index, event in enumerate(events):
        if event.get("type") == event_type:
            return index
    return None

def diagnostics_for_phase(events, phase_name, event_type=None):
    for event in events:
        if (event_payload(event).get("phase") or event.get("phase") or "") != phase_name:
            continue
        if event_type is not None and event.get("type") != event_type:
            continue
        diagnostics = event_payload(event).get("diagnostics")
        return diagnostics if isinstance(diagnostics, dict) else {}
    return {}

def milliseconds_to_seconds(value):
    if not isinstance(value, (int, float)):
        return None
    return round(value / 1000, 3)

def elapsed_between_event_indices(events, start_index, end_index):
    if not isinstance(start_index, int) or not isinstance(end_index, int):
        return None
    if start_index < 0 or end_index < 0 or start_index >= len(events) or end_index >= len(events):
        return None
    start = event_timestamp(events[start_index])
    end = event_timestamp(events[end_index])
    if start is None or end is None:
        return None
    return round((end - start).total_seconds(), 3)

for summary_path in sorted(base.glob("*/summary.json")):
    case_dir = summary_path.parent
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    presentation_audit = summary.get("presentationAudit") if isinstance(summary.get("presentationAudit"), dict) else {}
    events_path = case_dir / "turn.events.jsonl"
    tool_result = {}
    terminal_result = {}
    intent_resolved = {}
    events = []
    phases = []
    if events_path.exists():
        for line in events_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            event = json.loads(line)
            events.append(event)
            payload = event_payload(event)
            if payload.get("phase"):
                phases.append(payload.get("phase"))
            if payload.get("phase") == "tool.result":
                tool_result = payload.get("diagnostics") if isinstance(payload.get("diagnostics"), dict) else {}
            if event.get("type") == "intent.resolved":
                intent_resolved = payload
            if event.get("type") == "result":
                terminal_result = payload
    feedback_audit = stream_feedback_audit(events)
    resource_discovery_diagnostics = summary.get("resourceDiscoveryDiagnostics")
    if not isinstance(resource_discovery_diagnostics, dict):
        resource_discovery_diagnostics = tool_result.get("resourceDiscoveryDiagnostics")
    if not isinstance(resource_discovery_diagnostics, dict):
        resource_discovery_diagnostics = {}
    component_capabilities_diagnostics = summary.get("componentCapabilitiesDiagnostics")
    if not isinstance(component_capabilities_diagnostics, dict):
        component_capabilities_diagnostics = diagnostics_for_phase(events, "component.capabilities", "thought.step")
    expected = expected_paths(case_dir)
    intent = terminal_result.get("intentResolution") if isinstance(terminal_result.get("intentResolution"), dict) else {}
    candidates = intent.get("candidates") if isinstance(intent.get("candidates"), list) else []
    selected = intent.get("selectedCandidate") if isinstance(intent.get("selectedCandidate"), dict) else {}
    decision = terminal_result.get("decisionDiagnostics") if isinstance(terminal_result.get("decisionDiagnostics"), dict) else {}
    expected_rank = candidate_rank(candidates, expected)
    selected_path = decision.get("selectedResourcePath") or resource_path(selected)
    selected_rank = selected_candidate_rank(candidates, selected_path)
    top_candidates = [candidate_audit(index, candidate) for index, candidate in enumerate(candidates[:10], start=1)]
    quick_replies = terminal_result.get("quickReplies") if isinstance(terminal_result.get("quickReplies"), list) else []
    warnings = intent_resolved.get("warnings") if isinstance(intent_resolved.get("warnings"), list) else []
    llm_second_pass_used = "llm-intent-resolution-second-pass-used" in warnings
    context_bundle_start = first_phase_index(events, "context.bundle")
    intent_resolve_start = first_phase_index(events, "intent.resolve")
    tool_plan_start = first_phase_index(events, "tool.plan")
    tool_start = first_phase_index(events, "tool.start")
    tool_result_index = summary.get("toolResultIndex")
    component_capabilities_start = next(
        (
            index
            for index, event in enumerate(events)
            if event.get("type") == "status"
            and (event_payload(event).get("phase") or event.get("phase") or "") == "component.capabilities"
        ),
        None,
    )
    component_capabilities_done = next(
        (
            index
            for index, event in enumerate(events)
            if event.get("type") == "thought.step"
            and (event_payload(event).get("phase") or event.get("phase") or "") == "component.capabilities"
        ),
        None,
    )
    intent_resolve_evidence_start = first_phase_index(events, "intent.resolve.evidence")
    intent_resolve_llm_start = first_phase_index(events, "intent.resolve.llm")
    intent_resolution_start = (
        intent_resolve_evidence_start if intent_resolve_evidence_start is not None else intent_resolve_llm_start
    )
    intent_resolved_index = first_event_type_index(events, "intent.resolved")
    preview_plan_start = first_phase_index(events, "preview.plan")
    preview_compile_start = first_phase_index(events, "preview.compile")
    tool_loop_start = first_phase_index(events, "tool.loop")
    result_index = summary.get("terminalIndex")
    total_duration = duration_seconds(events)
    pre_intent_planning_seconds = elapsed_between_event_indices(
        events,
        intent_resolve_start,
        tool_plan_start,
    )
    tool_execution_seconds = elapsed_between_event_indices(
        events,
        tool_start,
        tool_result_index,
    )
    component_capabilities_seconds = elapsed_between_event_indices(
        events,
        component_capabilities_start,
        component_capabilities_done,
    )
    intent_resolution_seconds = elapsed_between_event_indices(
        events,
        intent_resolution_start,
        intent_resolved_index,
    )
    intent_resolved_to_preview_seconds = elapsed_between_event_indices(
        events,
        intent_resolved_index,
        preview_plan_start,
    )
    preview_compile_to_tool_loop_seconds = elapsed_between_event_indices(
        events,
        preview_compile_start,
        tool_loop_start,
    )
    tool_loop_to_result_seconds = elapsed_between_event_indices(
        events,
        tool_loop_start,
        result_index,
    )
    rows.append(
        {
            "case": case_dir.name,
            "expectedResourcePaths": expected,
            "planOrSkippedPhase": summary.get("planOrSkippedPhase"),
            "retrievalSource": tool_result.get("retrievalSource"),
            "retrievalQuery": tool_result.get("retrievalQuery"),
            "artifactKind": tool_result.get("artifactKind"),
            "toolResultCandidateCount": summary.get("toolResultCandidateCount"),
            "totalDurationSeconds": total_duration,
            "contextBundleSeconds": elapsed_between_event_indices(
                events,
                context_bundle_start,
                intent_resolve_start,
            ),
            "preIntentPlanningSeconds": pre_intent_planning_seconds,
            "toolPlanToResultSeconds": elapsed_between_event_indices(
                events,
                summary.get("planOrSkippedIndex"),
                tool_result_index,
            ),
            "toolExecutionSeconds": tool_execution_seconds,
            "toolCatalogDiscoverySeconds": milliseconds_to_seconds(
                resource_discovery_diagnostics.get("catalogDiscoveryElapsedMs")
            ),
            "toolGroundingSeconds": milliseconds_to_seconds(
                resource_discovery_diagnostics.get("groundingElapsedMs")
            ),
            "toolConsultativeProjectionSeconds": milliseconds_to_seconds(
                resource_discovery_diagnostics.get("consultativeProjectionElapsedMs")
            ),
            "toolQuickReplySeconds": milliseconds_to_seconds(
                resource_discovery_diagnostics.get("quickReplyElapsedMs")
            ),
            "toolMeasuredTotalSeconds": milliseconds_to_seconds(
                resource_discovery_diagnostics.get("totalElapsedMs")
            ),
            "toolDomainCatalogGroundedCandidateCount": (
                resource_discovery_diagnostics.get("domainCatalogGroundedCandidateCount")
            ),
            "componentCapabilitiesSeconds": component_capabilities_seconds,
            "componentCapabilitiesAwaitSeconds": milliseconds_to_seconds(
                component_capabilities_diagnostics.get("awaitElapsedMs")
            ),
            "componentCapabilitiesPreloadAgeSeconds": milliseconds_to_seconds(
                component_capabilities_diagnostics.get("preloadAgeMs")
            ),
            "componentCapabilitiesPreloaded": component_capabilities_diagnostics.get("preloaded"),
            "componentCapabilitiesPreloadCompletedBeforeAwait": (
                component_capabilities_diagnostics.get("preloadCompletedBeforeAwait")
            ),
            "componentCapabilitiesFallbackSynchronousLoad": (
                component_capabilities_diagnostics.get("fallbackSynchronousLoad")
            ),
            "componentCapabilitiesCatalogCount": component_capabilities_diagnostics.get("catalogCount"),
            "componentCapabilitiesToIntentResolvedSeconds": elapsed_between_event_indices(
                events,
                component_capabilities_start,
                intent_resolved_index,
            ),
            "intentResolveEvidencePhaseSeconds": phase_duration_seconds(events, "intent.resolve.evidence"),
            "intentResolveLlmPhaseSeconds": phase_duration_seconds(events, "intent.resolve.llm"),
            "intentResolutionSeconds": intent_resolution_seconds,
            "intentResolvedToPreviewSeconds": intent_resolved_to_preview_seconds,
            "previewToResultSeconds": elapsed_between_event_indices(
                events,
                preview_plan_start,
                result_index,
            ),
            "previewCompileToToolLoopSeconds": preview_compile_to_tool_loop_seconds,
            "toolLoopToResultSeconds": tool_loop_to_result_seconds,
            "llmSecondPassUsed": llm_second_pass_used,
            "intentWarnings": warnings,
            "expectedResourceRank": expected_rank,
            "expectedResourceRecovered": expected_rank is not None,
            "expectedReciprocalRank": reciprocal_rank(expected_rank),
            "topCandidateMargin": top_candidate_margin(candidates),
            "topCandidateRawScoreDelta": top_candidate_raw_score_delta(candidates),
            "topCandidateScoreKind": "retrieval_raw_score",
            "topCandidateRankingNote": top_candidate_ranking_note(candidates),
            "topCandidateSemanticRoles": semantic_roles(candidates[0]) if candidates else [],
            "topCandidates": top_candidates,
            "selectedResourcePath": selected_path,
            "selectedResourceRank": selected_rank,
            "selectedIsExpected": selected_path in set(expected),
            "selectedEvidence": selected.get("evidence"),
            "selectedSemanticRoles": semantic_roles(selected),
            "providerFailedAfterCandidateDiscovery": summary.get("providerFailedAfterCandidateDiscovery"),
            "resourceDiscoveryGroundedClarification": summary.get("resourceDiscoveryGroundedClarification"),
            "groundedClarificationPhase": "consultative.grounded-clarification" in phases,
            "plannerProviderErrorBeforeCandidateDiscovery": summary.get("plannerProviderErrorBeforeCandidateDiscovery"),
            "domainDiscoveryGroundedClarification": summary.get("domainDiscoveryGroundedClarification"),
            "resultCanApply": summary.get("resultCanApply"),
            "requiresReview": decision.get("requiresReview"),
            "reviewReason": decision.get("reviewReason"),
            "quickReplyCount": len(quick_replies),
            "thoughtStepMissingMessageCount": presentation_audit.get("thoughtStepMissingMessageCount"),
            "thoughtStepRedactedMessageCount": presentation_audit.get("thoughtStepRedactedMessageCount"),
            "thoughtStepTechnicalMessageCount": presentation_audit.get("thoughtStepTechnicalMessageCount"),
            "statusEventCount": feedback_audit["statusEventCount"],
            "heartbeatEventCount": feedback_audit["heartbeatEventCount"],
            "streamFeedbackMissingMessageCount": feedback_audit["streamFeedbackMissingMessageCount"],
            "streamFeedbackRedactedMessageCount": feedback_audit["streamFeedbackRedactedMessageCount"],
            "streamFeedbackTechnicalMessageCount": feedback_audit["streamFeedbackTechnicalMessageCount"],
            "assistantMessage": (terminal_result.get("assistantMessage") or "")[:320],
        }
    )

case_count = len(rows)
expected_recovered_count = sum(1 for row in rows if row["expectedResourceRecovered"])
unexpected_apply_count = sum(
    1
    for row in rows
    if row["resultCanApply"] is True and row["expectedResourcePaths"] and not row["selectedIsExpected"]
)
expected_ranks = [row["expectedResourceRank"] for row in rows if row["expectedResourceRank"] is not None]
role_counter = Counter(
    role
    for row in rows
    for role in row.get("topCandidateSemanticRoles", [])
)
review_counter = Counter(row.get("reviewReason") or "none" for row in rows)
safe_confirmation_rows = [
    row for row in rows
    if row["resultCanApply"] is not True
    and row["expectedResourceRecovered"]
    and row["selectedIsExpected"]
]
unsafe_confirmation_rows = [
    row for row in rows
    if row["resultCanApply"] is not True
    and row["expectedResourceRecovered"]
    and not row["selectedIsExpected"]
]
durations = [row["totalDurationSeconds"] for row in rows if isinstance(row["totalDurationSeconds"], (int, float))]
phase_timing_fields = [
    "contextBundleSeconds",
    "preIntentPlanningSeconds",
    "toolExecutionSeconds",
    "toolCatalogDiscoverySeconds",
    "toolGroundingSeconds",
    "toolMeasuredTotalSeconds",
    "componentCapabilitiesSeconds",
    "componentCapabilitiesAwaitSeconds",
    "componentCapabilitiesPreloadAgeSeconds",
    "intentResolveEvidencePhaseSeconds",
    "intentResolveLlmPhaseSeconds",
    "intentResolutionSeconds",
    "intentResolvedToPreviewSeconds",
    "previewToResultSeconds",
    "previewCompileToToolLoopSeconds",
    "toolLoopToResultSeconds",
]

def timing_values(field):
    return [
        row.get(field)
        for row in rows
        if isinstance(row.get(field), (int, float))
    ]

def timing_average(field):
    values = timing_values(field)
    return round(sum(values) / len(values), 3) if values else None

def timing_max(field):
    values = timing_values(field)
    return max(values) if values else None

phase_timing_averages = {
    field: timing_average(field)
    for field in phase_timing_fields
}
phase_timing_maxima = {
    field: timing_max(field)
    for field in phase_timing_fields
}

matrix_summary = {
    "schemaVersion": "praxis-agentic-authoring-pre-intent-matrix-audit.v1",
    "caseCount": case_count,
    "expectedRecoveredCount": expected_recovered_count,
    "unexpectedApplyCount": unexpected_apply_count,
    "recallAt1": sum(1 for rank in expected_ranks if rank <= 1) / case_count if case_count else 0,
    "recallAt3": sum(1 for rank in expected_ranks if rank <= 3) / case_count if case_count else 0,
    "recallAt5": sum(1 for rank in expected_ranks if rank <= 5) / case_count if case_count else 0,
    "meanReciprocalRank": (
        sum(row["expectedReciprocalRank"] for row in rows) / case_count if case_count else 0
    ),
    "top1Accuracy": sum(1 for row in rows if row["expectedResourceRank"] == 1) / case_count if case_count else 0,
    "safeConfirmationCount": len(safe_confirmation_rows),
    "unsafeConfirmationCount": len(unsafe_confirmation_rows),
    "thoughtStepMissingMessageCount": sum(row.get("thoughtStepMissingMessageCount") or 0 for row in rows),
    "thoughtStepRedactedMessageCount": sum(row.get("thoughtStepRedactedMessageCount") or 0 for row in rows),
    "thoughtStepTechnicalMessageCount": sum(row.get("thoughtStepTechnicalMessageCount") or 0 for row in rows),
    "statusEventCount": sum(row.get("statusEventCount") or 0 for row in rows),
    "heartbeatEventCount": sum(row.get("heartbeatEventCount") or 0 for row in rows),
    "streamFeedbackMissingMessageCount": sum(row.get("streamFeedbackMissingMessageCount") or 0 for row in rows),
    "streamFeedbackRedactedMessageCount": sum(row.get("streamFeedbackRedactedMessageCount") or 0 for row in rows),
    "streamFeedbackTechnicalMessageCount": sum(row.get("streamFeedbackTechnicalMessageCount") or 0 for row in rows),
    "llmSecondPassUsedCount": sum(1 for row in rows if row["llmSecondPassUsed"]),
    "averageDurationSeconds": round(sum(durations) / len(durations), 3) if durations else None,
    "maxDurationSeconds": max(durations) if durations else None,
    "phaseTimingAveragesSeconds": phase_timing_averages,
    "phaseTimingMaximaSeconds": phase_timing_maxima,
    "slowestCases": sorted(
        [
            {
                "case": row["case"],
                "totalDurationSeconds": row.get("totalDurationSeconds"),
                "preIntentPlanningSeconds": row.get("preIntentPlanningSeconds"),
                "toolExecutionSeconds": row.get("toolExecutionSeconds"),
                "intentResolutionSeconds": row.get("intentResolutionSeconds"),
                "previewToResultSeconds": row.get("previewToResultSeconds"),
            }
            for row in rows
            if isinstance(row.get("totalDurationSeconds"), (int, float))
        ],
        key=lambda item: item["totalDurationSeconds"],
        reverse=True,
    )[:5],
    "topCandidateRoleDistribution": dict(sorted(role_counter.items())),
    "reviewReasonDistribution": dict(sorted(review_counter.items())),
    "cases": rows,
}
matrix_failures = []
minimum_recall_at_1 = float(os.environ.get("MIN_RECALL_AT_1") or "1.0")
maximum_unexpected_apply = int(os.environ.get("MAX_UNEXPECTED_APPLY_COUNT") or "0")
maximum_stream_technical_messages = int(os.environ.get("MAX_STREAM_FEEDBACK_TECHNICAL_MESSAGE_COUNT") or "0")
maximum_thought_technical_messages = int(os.environ.get("MAX_THOUGHT_STEP_TECHNICAL_MESSAGE_COUNT") or "0")
if matrix_summary["expectedRecoveredCount"] != matrix_summary["caseCount"]:
    matrix_failures.append(
        f"expectedRecoveredCount={matrix_summary['expectedRecoveredCount']} caseCount={matrix_summary['caseCount']}"
    )
if matrix_summary["recallAt1"] < minimum_recall_at_1:
    matrix_failures.append(
        f"recallAt1={matrix_summary['recallAt1']:.3f} minimum={minimum_recall_at_1:.3f}"
    )
if matrix_summary["unexpectedApplyCount"] > maximum_unexpected_apply:
    matrix_failures.append(
        f"unexpectedApplyCount={matrix_summary['unexpectedApplyCount']} maximum={maximum_unexpected_apply}"
    )
if matrix_summary["streamFeedbackTechnicalMessageCount"] > maximum_stream_technical_messages:
    matrix_failures.append(
        "streamFeedbackTechnicalMessageCount="
        f"{matrix_summary['streamFeedbackTechnicalMessageCount']} maximum={maximum_stream_technical_messages}"
    )
if matrix_summary["thoughtStepTechnicalMessageCount"] > maximum_thought_technical_messages:
    matrix_failures.append(
        "thoughtStepTechnicalMessageCount="
        f"{matrix_summary['thoughtStepTechnicalMessageCount']} maximum={maximum_thought_technical_messages}"
    )
if matrix_failures:
    matrix_summary["gatePassed"] = False
    matrix_summary["gateFailures"] = matrix_failures
else:
    matrix_summary["gatePassed"] = True
    matrix_summary["gateFailures"] = []
(base / "matrix-summary.json").write_text(
    json.dumps(matrix_summary, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)

def markdown_bool(value):
    return "yes" if value is True else "no" if value is False else ""

lines = [
    "# Agentic Authoring Pre-Intent Matrix Audit",
    "",
    f"- Cases: {matrix_summary['caseCount']}",
    f"- Expected recovered: {matrix_summary['expectedRecoveredCount']}",
    f"- Unexpected apply: {matrix_summary['unexpectedApplyCount']}",
    f"- Recall@1: {matrix_summary['recallAt1']:.3f}",
    f"- Recall@3: {matrix_summary['recallAt3']:.3f}",
    f"- Recall@5: {matrix_summary['recallAt5']:.3f}",
    f"- MRR: {matrix_summary['meanReciprocalRank']:.3f}",
    f"- LLM second pass used: {matrix_summary['llmSecondPassUsedCount']}",
    f"- Thought.step missing messages: {matrix_summary['thoughtStepMissingMessageCount']}",
    f"- Thought.step redacted messages: {matrix_summary['thoughtStepRedactedMessageCount']}",
    f"- Thought.step technical messages: {matrix_summary['thoughtStepTechnicalMessageCount']}",
    f"- Stream status events: {matrix_summary['statusEventCount']}",
    f"- Stream heartbeat events: {matrix_summary['heartbeatEventCount']}",
    f"- Stream feedback missing messages: {matrix_summary['streamFeedbackMissingMessageCount']}",
    f"- Stream feedback redacted messages: {matrix_summary['streamFeedbackRedactedMessageCount']}",
    f"- Stream feedback technical messages: {matrix_summary['streamFeedbackTechnicalMessageCount']}",
    f"- Average duration seconds: {matrix_summary['averageDurationSeconds']}",
    f"- Gate passed: {markdown_bool(matrix_summary['gatePassed'])}",
    "",
    "## Phase Timing",
    "",
    "| Phase | Avg seconds | Max seconds |",
    "| --- | ---: | ---: |",
]
phase_labels = {
    "contextBundleSeconds": "context.bundle -> intent.resolve",
    "preIntentPlanningSeconds": "intent.resolve -> tool.plan",
    "toolExecutionSeconds": "tool.start -> tool.result",
    "toolCatalogDiscoverySeconds": "searchApiResources catalog discovery (engine)",
    "toolGroundingSeconds": "searchApiResources grounding (engine)",
    "toolMeasuredTotalSeconds": "searchApiResources total (engine)",
    "componentCapabilitiesSeconds": "component.capabilities",
    "componentCapabilitiesAwaitSeconds": "component.capabilities await (engine)",
    "componentCapabilitiesPreloadAgeSeconds": "component.capabilities preload age (engine)",
    "intentResolveEvidencePhaseSeconds": "intent.resolve.evidence phase",
    "intentResolveLlmPhaseSeconds": "intent.resolve.llm phase",
    "intentResolutionSeconds": "intent.resolve.evidence/llm -> intent.resolved",
    "intentResolvedToPreviewSeconds": "intent.resolved -> preview.plan",
    "previewToResultSeconds": "preview.plan -> result",
    "previewCompileToToolLoopSeconds": "preview.compile -> tool.loop",
    "toolLoopToResultSeconds": "tool.loop -> result",
}
for field in phase_timing_fields:
    avg = matrix_summary["phaseTimingAveragesSeconds"].get(field)
    maximum = matrix_summary["phaseTimingMaximaSeconds"].get(field)
    lines.append(
        f"| {phase_labels[field]} | "
        f"{'' if avg is None else avg} | "
        f"{'' if maximum is None else maximum} |"
    )
lines.extend([
    "",
    "## Slowest Cases",
    "",
    "| Case | Total | Pre-intent planning | Tool execution | Intent resolution | Preview to result |",
    "| --- | ---: | ---: | ---: | ---: | ---: |",
])
for item in matrix_summary["slowestCases"]:
    lines.append(
        "| "
        + " | ".join([
            item["case"],
            "" if item.get("totalDurationSeconds") is None else str(item["totalDurationSeconds"]),
            "" if item.get("preIntentPlanningSeconds") is None else str(item["preIntentPlanningSeconds"]),
            "" if item.get("toolExecutionSeconds") is None else str(item["toolExecutionSeconds"]),
            "" if item.get("intentResolutionSeconds") is None else str(item["intentResolutionSeconds"]),
            "" if item.get("previewToResultSeconds") is None else str(item["previewToResultSeconds"]),
        ])
        + " |"
    )
lines.extend([
    "",
    "## Cases",
    "",
    "| Case | Expected rank | Selected | Apply | Review | Duration | Timing | Feedback | Top candidates |",
    "| --- | ---: | --- | --- | --- | ---: | --- | --- | --- |",
])
for row in rows:
    top = "<br>".join(
        f"{candidate['rank']}. `{candidate['resourcePath']}` (raw score: {candidate['score']}) "
        f"{','.join(candidate.get('semanticRoles') or [])}"
        for candidate in row["topCandidates"][:5]
    )
    if row.get("topCandidateRankingNote"):
        top = f"{top}<br>note: {row['topCandidateRankingNote']}"
    lines.append(
        "| "
        + " | ".join([
            row["case"],
            "" if row["expectedResourceRank"] is None else str(row["expectedResourceRank"]),
            f"`{row['selectedResourcePath']}`" if row["selectedResourcePath"] else "",
            markdown_bool(row["resultCanApply"]),
            row.get("reviewReason") or "",
            "" if row["totalDurationSeconds"] is None else str(row["totalDurationSeconds"]),
            f"plan:{row.get('preIntentPlanningSeconds') or ''} "
            f"tool:{row.get('toolExecutionSeconds') or ''} "
            f"toolMeasured:{row.get('toolMeasuredTotalSeconds') if row.get('toolMeasuredTotalSeconds') is not None else ''} "
            f"capAwait:{row.get('componentCapabilitiesAwaitSeconds') if row.get('componentCapabilitiesAwaitSeconds') is not None else ''} "
            f"intent:{row.get('intentResolutionSeconds') or ''} "
            f"preview:{row.get('previewToResultSeconds') or ''}",
            f"S:{row.get('statusEventCount') or 0} H:{row.get('heartbeatEventCount') or 0} "
            f"missing:{row.get('streamFeedbackMissingMessageCount') or 0} "
            f"tech:{row.get('streamFeedbackTechnicalMessageCount') or 0}",
            top,
        ])
        + " |"
    )

(base / "matrix-audit.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
print(json.dumps({
    "schemaVersion": matrix_summary["schemaVersion"],
    "caseCount": matrix_summary["caseCount"],
    "expectedRecoveredCount": matrix_summary["expectedRecoveredCount"],
    "unexpectedApplyCount": matrix_summary["unexpectedApplyCount"],
    "recallAt1": matrix_summary["recallAt1"],
    "recallAt3": matrix_summary["recallAt3"],
    "meanReciprocalRank": matrix_summary["meanReciprocalRank"],
    "top1Accuracy": matrix_summary["top1Accuracy"],
    "averageDurationSeconds": matrix_summary["averageDurationSeconds"],
    "maxDurationSeconds": matrix_summary["maxDurationSeconds"],
    "phaseTimingAveragesSeconds": matrix_summary["phaseTimingAveragesSeconds"],
    "statusEventCount": matrix_summary["statusEventCount"],
    "heartbeatEventCount": matrix_summary["heartbeatEventCount"],
    "streamFeedbackTechnicalMessageCount": matrix_summary["streamFeedbackTechnicalMessageCount"],
    "gatePassed": matrix_summary["gatePassed"],
    "gateFailures": matrix_summary["gateFailures"],
}, ensure_ascii=False, indent=2))
if matrix_failures:
    raise SystemExit("Pre-intent matrix gate failed: " + "; ".join(matrix_failures))
PY
