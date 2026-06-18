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

mkdir -p "$ARTIFACTS_DIR"

cases=(
  "empregados|/api/human-resources/funcionarios|quero criar algo que mostre informacoes dos empregados"
  "colaboradores|/api/human-resources/funcionarios|quero uma tela para acompanhar colaboradores"
  "funcionarios_typo|/api/human-resources/funcionarios|mostra info dos funsionarios"
  "pessoal_aberto|/api/human-resources/funcionarios|preciso ver como esta meu pessoal"
  "staff_en|/api/human-resources/funcionarios|build me a staff overview"
  "fornecedores|/api/procurement/contracts,/api/procurement/suppliers|quero visualizar contratos de fornecedores"
  "narrativa_colaboradores|/api/human-resources/funcionarios|Estou reorganizando uma rotina de gestao e queria uma tela para acompanhar o time da empresa. Preciso enxergar quem sao as pessoas, alguma visao geral por area e conseguir abrir detalhes depois, mas ainda nao sei se isso deve virar tabela, painel ou outra coisa."
  "narrativa_fornecedores|/api/procurement/contracts,/api/procurement/suppliers|Na operacao de compras eu preciso entender melhor os acordos com parceiros externos. Minha ideia e ter uma tela para acompanhar contratos, status e informacoes principais dos fornecedores sem precisar escolher manualmente a API agora."
)

echo "Running pre-intent authoring matrix against $BASE_URL"
echo "Artifacts: $ARTIFACTS_DIR"

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

python3 - "$ARTIFACTS_DIR" <<'PY'
import json
import pathlib
import sys

base = pathlib.Path(sys.argv[1])
rows = []
def event_payload(event):
    value = event.get("payload")
    return value if isinstance(value, dict) else {}

def resource_path(candidate):
    value = candidate.get("resourcePath") if isinstance(candidate, dict) else None
    return value or ""

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

for summary_path in sorted(base.glob("*/summary.json")):
    case_dir = summary_path.parent
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    events_path = case_dir / "turn.events.jsonl"
    tool_result = {}
    terminal_result = {}
    phases = []
    if events_path.exists():
        for line in events_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            event = json.loads(line)
            payload = event_payload(event)
            if payload.get("phase"):
                phases.append(payload.get("phase"))
            if payload.get("phase") == "tool.result":
                tool_result = payload.get("diagnostics") if isinstance(payload.get("diagnostics"), dict) else {}
            if event.get("type") == "result":
                terminal_result = payload
    expected = expected_paths(case_dir)
    intent = terminal_result.get("intentResolution") if isinstance(terminal_result.get("intentResolution"), dict) else {}
    candidates = intent.get("candidates") if isinstance(intent.get("candidates"), list) else []
    selected = intent.get("selectedCandidate") if isinstance(intent.get("selectedCandidate"), dict) else {}
    decision = terminal_result.get("decisionDiagnostics") if isinstance(terminal_result.get("decisionDiagnostics"), dict) else {}
    expected_rank = candidate_rank(candidates, expected)
    selected_path = decision.get("selectedResourcePath") or resource_path(selected)
    quick_replies = terminal_result.get("quickReplies") if isinstance(terminal_result.get("quickReplies"), list) else []
    rows.append(
        {
            "case": case_dir.name,
            "expectedResourcePaths": expected,
            "planOrSkippedPhase": summary.get("planOrSkippedPhase"),
            "retrievalSource": tool_result.get("retrievalSource"),
            "retrievalQuery": tool_result.get("retrievalQuery"),
            "artifactKind": tool_result.get("artifactKind"),
            "toolResultCandidateCount": summary.get("toolResultCandidateCount"),
            "expectedResourceRank": expected_rank,
            "expectedResourceRecovered": expected_rank is not None,
            "topCandidates": [
                {
                    "rank": index,
                    "resourcePath": resource_path(candidate),
                    "score": candidate.get("score"),
                    "evidence": candidate.get("evidence"),
                    "reason": candidate.get("reason"),
                }
                for index, candidate in enumerate(candidates[:8], start=1)
            ],
            "selectedResourcePath": selected_path,
            "selectedIsExpected": selected_path in set(expected),
            "selectedEvidence": selected.get("evidence"),
            "providerFailedAfterCandidateDiscovery": summary.get("providerFailedAfterCandidateDiscovery"),
            "resourceDiscoveryGroundedClarification": summary.get("resourceDiscoveryGroundedClarification"),
            "groundedClarificationPhase": "consultative.grounded-clarification" in phases,
            "plannerProviderErrorBeforeCandidateDiscovery": summary.get("plannerProviderErrorBeforeCandidateDiscovery"),
            "domainDiscoveryGroundedClarification": summary.get("domainDiscoveryGroundedClarification"),
            "resultCanApply": summary.get("resultCanApply"),
            "requiresReview": decision.get("requiresReview"),
            "reviewReason": decision.get("reviewReason"),
            "quickReplyCount": len(quick_replies),
            "assistantMessage": (terminal_result.get("assistantMessage") or "")[:320],
        }
    )

matrix_summary = {
    "caseCount": len(rows),
    "expectedRecoveredCount": sum(1 for row in rows if row["expectedResourceRecovered"]),
    "unexpectedApplyCount": sum(
        1
        for row in rows
        if row["resultCanApply"] is True and row["expectedResourcePaths"] and not row["selectedIsExpected"]
    ),
    "cases": rows,
}
(base / "matrix-summary.json").write_text(
    json.dumps(matrix_summary, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print(json.dumps(matrix_summary, ensure_ascii=False, indent=2))
PY
