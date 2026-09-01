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
4. Select `paid_gate_lane=llm-compliance`.

The paid lane selector is exclusive, so the compliance-policy shadow cannot be combined with the
paid HTTP/SSE or Page Builder gates. The deterministic HTTP and Domain Catalog validations may remain
enabled because they do not call an external provider.

By default, provider quota or temporary provider unavailability writes a sanitized
`providerStatus=unavailable` report and skips the compliance-policy assertion. Enable
`fail_llm_compliance_on_provider_unavailable` in GitHub Actions, or set
`PRAXIS_AGENTIC_AUTHORING_LLM_COMPLIANCE_FAIL_ON_PROVIDER_UNAVAILABLE=true` locally,
when the validation must fail hard on provider outages.

The sanitized result is uploaded from `target/agentic-authoring/`. Secrets must remain in local env files or GitHub Actions secrets and must not be committed.
