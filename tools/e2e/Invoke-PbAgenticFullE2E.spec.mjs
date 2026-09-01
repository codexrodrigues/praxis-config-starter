import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const runnerSource = readFileSync(resolve(scriptDir, '..', 'Invoke-PbAgenticFullE2E.ps1'), 'utf8');
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

test('publishes HTTP/SSE evidence only for the live provider HTTP lane', () => {
  assert.match(
    workflowSource,
    /\$includeLiveHttpSse\s*=\s*'\$\{\{ inputs\.run_quickstart_http_smoke \}\}' -eq 'true' -and[\s\S]*?'\$\{\{ inputs\.domain_rule_lifecycle_only \}\}' -ne 'true'/,
  );
  assert.match(
    workflowSource,
    /PublicationProfile = if \(\$includeLiveHttpSse\) \{ 'page-builder-http-sse' \} else \{ 'page-builder' \}/,
  );
  assert.match(
    workflowSource,
    /if \(\$includeLiveHttpSse\) \{[\s\S]*?\$publicationArgs\.HttpArtifactRoot/,
  );
  assert.match(
    workflowSource,
    /\$publicationArgs\.HttpArtifactRoot\s*=\s*"\$env:GITHUB_WORKSPACE\\praxis-config-starter\\artifacts\\ai-sse-smoke"/,
  );
  assert.match(
    workflowSource,
    /^\s+praxis-config-starter\/artifacts\/ai-sse-smoke\/\*\*\/summary\.json$/m,
  );
  assert.doesNotMatch(
    workflowSource,
    /^\s+artifacts\/ai-sse-smoke\/\*\*\/summary\.json$/m,
  );
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
