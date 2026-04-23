# Agentic Authoring Live LLM Tests

These tests call real LLM providers and are disabled by default. They are intended for controlled validation of agentic authoring behavior that cannot be proven with mocks alone.

## Compliance Policy Shadow

`AgenticAuthoringLlmCompliancePolicyIntegrationTest` validates that a federated compliance context is interpreted with the `compliance_review` policy profile:

- denied content is not used;
- low-confidence signals are excluded;
- allowed guidance keeps governed LGPD/GDPR vocabulary such as CPF masking and review requirements.

Local PowerShell run:

```powershell
.\tools\Invoke-AgenticAuthoringLlmCompliancePolicyRun.ps1 `
  -Provider openai `
  -EnvFile .\.env.openai.local.ps1
```

Local shell run:

```bash
set -a
source ./.env.openai.local.sh
set +a
export PRAXIS_AGENTIC_AUTHORING_LLM_COMPLIANCE_POLICY=true
export PRAXIS_AGENTIC_AUTHORING_SHADOW_PROVIDER=openai
export PRAXIS_AI_PROVIDER=openai
mvn -Dtest=AgenticAuthoringLlmCompliancePolicyIntegrationTest test
```

GitHub Actions:

1. Open `Agentic Authoring HTTP Smoke`.
2. Run the workflow manually.
3. Select `provider`.
4. Enable `run_llm_compliance_policy_shadow`.

The sanitized result is uploaded from `target/agentic-authoring/`. Secrets must remain in local env files or GitHub Actions secrets and must not be committed.
