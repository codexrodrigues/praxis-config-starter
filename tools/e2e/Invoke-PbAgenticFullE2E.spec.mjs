import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const runnerSource = readFileSync(resolve(scriptDir, '..', 'Invoke-PbAgenticFullE2E.ps1'), 'utf8');
const httpRunnerSource = readFileSync(
  resolve(scriptDir, '..', 'Invoke-QuickstartAgenticAuthoringHttpSmokeSuite.ps1'),
  'utf8',
);
const dispatchRunnerSource = readFileSync(
  resolve(scriptDir, '..', 'Invoke-GitHubAgenticAuthoringSmokeWorkflow.ps1'),
  'utf8',
);
const matrix = JSON.parse(
  readFileSync(resolve(scriptDir, 'page-builder-agentic-gate-matrix.json'), 'utf8'),
);
const workflowSource = readFileSync(
  resolve(scriptDir, '..', '..', '.github', 'workflows', 'agentic-authoring-smoke.yml'),
  'utf8',
);

test('projects the canonical human turn limit into the Playwright process', () => {
  assert.match(runnerSource, /\$humanTurnLimit\s*=\s*if \(\$null -ne \$modeMatrix\.humanTurnLimit\)/);
  assert.match(runnerSource, /\$env:PRAXIS_E2E_HUMAN_TURN_LIMIT\s*=\s*"\$humanTurnLimit"/);
  assert.match(
    runnerSource,
    /\$env:PRAXIS_E2E_HUMAN_TURN_LIMIT_SOURCE\s*=\s*"canonical-gate-profile"/,
  );
  assert.match(runnerSource, /Remove-Item Env:\\PRAXIS_E2E_HUMAN_TURN_LIMIT\b/);
  assert.match(runnerSource, /Remove-Item Env:\\PRAXIS_E2E_HUMAN_TURN_LIMIT_SOURCE\b/);
});

test('runs the portable evidence validator and publishes semantic requirements', () => {
  assert.match(runnerSource, /validate-page-builder-agentic-gate-evidence\.mjs/);
  assert.match(runnerSource, /--expected-runs 1/);
  assert.match(runnerSource, /--report \$playwrightReportPath/);
  assert.match(runnerSource, /praxis\.page-builder-agentic-gate-run-attestation\/v1/);
  assert.match(runnerSource, /reportSha256 = \[string\] \$validatedRun\.reportSha256/);
  assert.match(runnerSource, /semanticRefinements = @\(\$validatedRun\.semanticRefinements\)/);
  assert.match(runnerSource, /\$evidenceValidationPassed\s*=\s*\$true/);
  assert.match(runnerSource, /passed = \$evidenceValidationPassed/);
  assert.match(runnerSource, /attestation = \$evidenceValidationAttestation/);
  assert.match(runnerSource, /humanTurnLimit = if \(\$humanTurnLimit -gt 0\)/);
  assert.match(runnerSource, /semanticRefinementRequirements = @\(/);
  assert.match(runnerSource, /domainCatalogRagRequired = \$modeDomainCatalogRagRequired/);
  assert.match(runnerSource, /domainCatalogResourceKey = if \(/);
  assert.match(runnerSource, /apiCatalogGroup = \$modeApiCatalogGroup/);
  assert.match(runnerSource, /apiCatalogPathPrefixes = @\(\$modeApiCatalogPathPrefixes\)/);
  assert.match(runnerSource, /requiredOperationIds = @\(\$_\.requiredOperationIds\)/);
  assert.match(runnerSource, /\$publishedDiagnosticEvidence\s*=\s*@\(\)/);
  assert.match(runnerSource, /diagnosticEvidence = @\(\$publishedDiagnosticEvidence\)/);
  assert.doesNotMatch(runnerSource, /diagnosticEvidence = if \(/);
});

test('compares the packaged Quickstart with the workflow-declared Config version', () => {
  assert.match(runnerSource, /\[string\]\s+\$ExpectedConfigVersion\s*=\s*""/);
  assert.match(
    runnerSource,
    /\$expectedStarterVersion\s*=\s*if \(\[string\]::IsNullOrWhiteSpace\(\$ExpectedConfigVersion\)\)/,
  );
  assert.match(
    workflowSource,
    /-ExpectedConfigVersion "\$env:STARTER_VERSION"/,
  );
});

test('exposes every canonical matrix mode through workflow dispatch', () => {
  const inputBlock = workflowSource.match(
    /page_builder_e2e_mode:[\s\S]*?page_builder_e2e_timeout_minutes:/,
  );
  assert.ok(inputBlock, 'page_builder_e2e_mode workflow input must exist');
  const options = [...inputBlock[0].matchAll(/^\s{10}- ([a-z0-9-]+)$/gm)].map(
    (match) => match[1],
  );
  assert.deepEqual(options, Object.keys(matrix.modes));
});

test('exposes one exclusive paid lane and removes combinable paid toggles', () => {
  const inputBlock = workflowSource.match(/paid_gate_lane:[\s\S]*?page_builder_e2e_mode:/);
  assert.ok(inputBlock, 'paid_gate_lane workflow input must exist');
  const options = [...inputBlock[0].matchAll(/^\s{10}- ([a-z0-9-]+)$/gm)].map(
    (match) => match[1],
  );
  assert.deepEqual(options, ['none', 'http-sse', 'page-builder', 'llm-compliance']);
  assert.doesNotMatch(workflowSource, /run_page_builder_full_e2e/);
  assert.doesNotMatch(workflowSource, /domain_rule_lifecycle_only/);
  assert.doesNotMatch(workflowSource, /run_llm_compliance_policy_shadow/);
  assert.match(
    workflowSource,
    /if: inputs\.paid_gate_lane == 'page-builder'[\s\S]*?Invoke-PbAgenticFullE2E\.ps1[\s\S]*?-ConfirmPaidProviderRun/,
  );
  assert.match(
    workflowSource,
    /if: inputs\.paid_gate_lane == 'llm-compliance'[\s\S]*?AgenticAuthoringLlmCompliancePolicyIntegrationTest/,
  );
  assert.match(
    workflowSource,
    /if \('\$\{\{ inputs\.paid_gate_lane \}\}' -eq 'http-sse'\) \{[\s\S]*?ConfirmPaidProviderRun = \$true/,
  );
  assert.match(
    workflowSource,
    /if \(\$paidGateLane -eq 'http-sse' -and -not \$runQuickstartHttpSmoke\) \{[\s\S]*?throw/,
  );
  assert.match(workflowSource, /PublicationProfile = 'page-builder'/);
  assert.doesNotMatch(workflowSource, /page-builder-http-sse/);
});

test('requires explicit paid-run confirmation and disables automatic retries', () => {
  assert.equal(matrix.defaults.retries, 0);
  assert.deepEqual(matrix.modes.smoke.scenarios, [
    'critical-interception-guard',
    'governed-capabilities-provenance',
    'live-resource-workspace-command',
  ]);
  assert.equal(matrix.modes.smoke.expectedDiscovered, 3);
  assert.match(runnerSource, /\[switch\]\s+\$ConfirmPaidProviderRun/);
  assert.match(runnerSource, /if \(-not \$ConfirmPaidProviderRun\.IsPresent\) \{/);
  assert.match(httpRunnerSource, /\[switch\]\s+\$ConfirmPaidProviderRun/);
  assert.match(
    httpRunnerSource,
    /if \(-not \$DomainRuleLifecycleOnly\.IsPresent -and -not \$ConfirmPaidProviderRun\.IsPresent\) \{/,
  );
});

test('dispatch helper mirrors the canonical paid lanes and matrix modes', () => {
  assert.match(
    dispatchRunnerSource,
    /\[ValidateSet\("none", "http-sse", "page-builder", "llm-compliance"\)\]/,
  );
  assert.match(
    dispatchRunnerSource,
    /\[ValidateSet\("smoke", "single-table", "crud-simple", "related-resource", "tabs-nested", "full"\)\]/,
  );
  assert.match(dispatchRunnerSource, /paid_gate_lane = \$PaidGateLane/);
  assert.doesNotMatch(dispatchRunnerSource, /RunPageBuilderFullE2E/);
});

test('materializes focused catalog scope from the canonical gate profile', () => {
  assert.match(runnerSource, /\$modeApiCatalogGroup\s*=\s*if \(/);
  assert.match(runnerSource, /@\(\$modeMatrix\.apiCatalogPathPrefixes/);
  assert.match(runnerSource, /\$domainCatalogGroups[\s\S]*?@\(\$modeApiCatalogGroup\)/);
  assert.match(
    runnerSource,
    /\$env:API_CATALOG_PATH_PREFIXES\s*=\s*\(\$focusedApiCatalogPathPrefixes -join ","\)/,
  );
});
