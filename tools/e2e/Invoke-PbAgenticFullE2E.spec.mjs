import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const runnerSource = readFileSync(resolve(scriptDir, '..', 'Invoke-PbAgenticFullE2E.ps1'), 'utf8');

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
