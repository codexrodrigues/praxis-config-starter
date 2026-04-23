#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$STARTER_ROOT/.env.openai.local.sh}"
PROVIDER="${PROVIDER:-openai}"
FAIL_ON_PROVIDER_UNAVAILABLE="${FAIL_ON_PROVIDER_UNAVAILABLE:-false}"

set -a
eval "$(LC_ALL=C perl -pe 's/^\xEF\xBB\xBF// if $. == 1; s/\r$//;' "$ENV_FILE" | sed '/^#!/d')" >/dev/null
set +a

export PRAXIS_AGENTIC_AUTHORING_LLM_COMPLIANCE_POLICY=true
export PRAXIS_AGENTIC_AUTHORING_SHADOW_PROVIDER="$PROVIDER"
export PRAXIS_AI_PROVIDER="$PROVIDER"
export PRAXIS_AGENTIC_AUTHORING_LLM_COMPLIANCE_FAIL_ON_PROVIDER_UNAVAILABLE="$FAIL_ON_PROVIDER_UNAVAILABLE"

cd "$STARTER_ROOT"
mvn -Dtest=AgenticAuthoringLlmCompliancePolicyIntegrationTest test

report="target/agentic-authoring/llm-compliance-policy-result.$PROVIDER.json"
if [[ -f "$report" ]]; then
  jq '{provider, model, providerStatus, result}' "$report"
fi
