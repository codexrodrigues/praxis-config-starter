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
  assert.match(runnerSource, /\$evidenceValidationPassed\s*=\s*\$true/);
  assert.match(runnerSource, /passed = \$evidenceValidationPassed/);
  assert.match(runnerSource, /humanTurnLimit = if \(\$humanTurnLimit -gt 0\)/);
  assert.match(runnerSource, /semanticRefinementRequirements = @\(/);
  assert.match(runnerSource, /requiredOperationIds = @\(\$_\.requiredOperationIds\)/);
  assert.match(runnerSource, /\$publishedDiagnosticEvidence\s*=\s*@\(\)/);
  assert.match(runnerSource, /diagnosticEvidence = @\(\$publishedDiagnosticEvidence\)/);
  assert.doesNotMatch(runnerSource, /diagnosticEvidence = if \(/);
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

test('materializes focused catalog scope from the canonical gate profile', () => {
  assert.match(runnerSource, /\$modeApiCatalogGroup\s*=\s*if \(/);
  assert.match(runnerSource, /@\(\$modeMatrix\.apiCatalogPathPrefixes/);
  assert.match(runnerSource, /\$domainCatalogGroups[\s\S]*?@\(\$modeApiCatalogGroup\)/);
  assert.match(
    runnerSource,
    /\$env:API_CATALOG_PATH_PREFIXES\s*=\s*\(\$focusedApiCatalogPathPrefixes -join ","\)/,
  );
});
