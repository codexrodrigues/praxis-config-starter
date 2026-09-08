import assert from 'node:assert/strict';
import test from 'node:test';
import { exportGovernedState } from './export-page-builder-governed-state.mjs';

const projection = {
  schemaVersion: 'praxis.page-builder.governed-state-projection/v1',
  scenarioId: 'live-resource-workspace-command',
  observedDisposition: { testObservedState: 'review', controllerState: 'review', domState: 'review' },
  decisionDiagnostics: { status: 'blocked', reason: 'PRIVATE', decisionValid: false, requiresReview: true },
  preview: { present: true, valid: false },
  applyEligibility: { controllerCanApply: false, persistEnabled: false },
  blockingDiagnosticCodes: ['RESOURCE_FOCUS_UNCONFIRMED'],
  quickReplyIds: [], governedRepairActionIds: [], canonicalActionPresent: false,
  applyLineage: { status: 'blocked', reason: 'PRIVATE', patchAuthority: 'backend-compiled', terminalReferencePresent: true },
  execution: { turnCount: 1, attemptCount: 1, retryCount: 0 },
};
const attachment = (value = projection, turn = 1) => ({ name: `governed-state-turn-${turn}.json`, contentType: 'application/json', body: Buffer.from(JSON.stringify(value)).toString('base64') });
const report = (attachments, status = 'failed') => ({ suites: [{ suites: [{ specs: [{ title: 'PRIVATE', tests: [{ results: [{ status, retry: 0, error: 'PRIVATE', attachments }] }] }] }] }] });

test('preserves a blocked terminal with no repair reply or functional receipt', () => {
  const result = exportGovernedState(report([attachment()]));
  assert.equal(result.evidenceRole, 'diagnostic-only');
  assert.equal(result.turns[0].resultStatus, 'failed');
  assert.equal(result.turns[0].projection.applyEligibility.persistEnabled, false);
  assert.deepEqual(result.turns[0].projection.blockingDiagnosticCodes, ['RESOURCE_FOCUS_UNCONFIRMED']);
  assert.deepEqual(result.turns[0].projection.quickReplyIds, []);
});
test('preserves applicable observations without manufacturing a functional gate pass', () => {
  const result = exportGovernedState(report([attachment({ ...projection, applyEligibility: { controllerCanApply: true, persistEnabled: true }, preview: { present: true, valid: true } })], 'passed'));
  assert.equal(result.turns[0].projection.preview.valid, true);
  assert.equal(result.turns[0].resultStatus, 'passed');
  assert.equal(result.productionLike, undefined);
  assert.equal(result.firstPassFunctional, undefined);
});
test('never exports free-text reasons, raw actions, business data, titles or private attachments', () => {
  const value = {
    ...projection, prompt: 'PRIVATE', rawResponse: 'PRIVATE', tenantId: 'PRIVATE',
    canonicalActions: [{ canonicalAction: { targetId: 'PRIVATE' } }],
    observedDisposition: { controllerState: 'Bearer PRIVATE' },
    quickReplyIds: ['governed-review-revise', 'PRIVATE unsafe label'],
    blockingDiagnosticCodes: ['SAFE_CODE', 'PRIVATE\ncredential'],
    execution: { turnCount: 'PRIVATE', attemptCount: -1, retryCount: 0 },
  };
  const result = exportGovernedState(report([attachment(value), { name: 'private.json', body: 'PRIVATE' }]));
  assert.doesNotMatch(JSON.stringify(result), /PRIVATE|rawResponse|tenantId|targetId/);
  assert.deepEqual(result.turns[0].projection.quickReplyIds, ['governed-review-revise']);
  assert.equal(result.turns[0].projection.decisionDiagnostics.reason, null);
  assert.equal(result.turns[0].projection.applyLineage.reason, null);
});
test('distinguishes missing observations from false or empty known observations', () => {
  assert.deepEqual(exportGovernedState(report([])).turns, []);
  const missing = exportGovernedState(report([attachment({ schemaVersion: projection.schemaVersion, scenarioId: projection.scenarioId })])).turns[0].projection;
  assert.equal(missing.preview.present, null);
  assert.equal(missing.quickReplyIds, null);
  assert.equal(missing.intentResolutionEvidence.terminalIntentPresent, null);
  assert.equal(missing.intentResolutionEvidence.warningCodes, null);
  assert.equal(missing.intentResolutionEvidence.resolutionTelemetry.llmResolved, null);
});

const unresolved = 'llm-intent-resolution-unresolved-clarification-required';
const focus = 'llm-resource-selection-unconfirmed-by-ai-authored-focus';
for (const warningCodes of [[unresolved], [unresolved, focus], ['llm-intent-resolution-provider-failed-clarification-required'], []]) {
  test(`exports exact intent evidence without inventing a cause: ${warningCodes.join(',') || 'none'}`, () => {
    const evidence = {
      terminalIntentPresent: true, valid: false, warningCodes,
      resolutionTelemetry: { llmResolutionAttempted: true, llmResolved: false, keywordFallbackApplied: false, semanticPolicyApplied: false },
    };
    const result = exportGovernedState(report([attachment({ ...projection, intentResolutionEvidence: evidence })])).turns[0].projection;
    assert.deepEqual(result.intentResolutionEvidence, evidence);
    assert.equal(result.applyEligibility.controllerCanApply, false);
    assert.equal(result.rootCause, undefined);
    assert.equal(result.productionLike, undefined);
  });
}

test('re-sanitizes intent evidence by exact allowlist even for token-shaped secrets', () => {
  const evidence = {
    terminalIntentPresent: true, valid: 'true', warningCodes: [focus, 'PRIVATE', focus, 'llm-provider-error-PRIVATE', { code: unresolved }],
    effectivePrompt: 'PRIVATE', rawResponse: 'PRIVATE',
    resolutionTelemetry: {
      llmResolved: 'false', llmResolutionAttempted: 1, keywordFallbackApplied: false,
      selectedResourcePath: 'PRIVATE', selectedCandidateEvidence: ['PRIVATE'],
      providerInvocations: ['PRIVATE'], request: { apiKey: 'PRIVATE' },
    },
  };
  const result = exportGovernedState(report([attachment({ ...projection, intentResolutionEvidence: evidence })])).turns[0].projection;
  assert.deepEqual(result.intentResolutionEvidence.warningCodes, [focus]);
  assert.equal(result.intentResolutionEvidence.valid, null);
  assert.equal(result.intentResolutionEvidence.resolutionTelemetry.llmResolved, null);
  assert.equal(result.intentResolutionEvidence.resolutionTelemetry.llmResolutionAttempted, null);
  assert.doesNotMatch(JSON.stringify(result), /PRIVATE|rawResponse|selectedResourcePath|providerInvocations|apiKey/);
});

test('keeps malformed warning arrays unknown and an absent terminal explicitly false', () => {
  const evidence = { terminalIntentPresent: false, warningCodes: unresolved };
  const result = exportGovernedState(report([attachment({ ...projection, intentResolutionEvidence: evidence })])).turns[0].projection;
  assert.equal(result.intentResolutionEvidence.terminalIntentPresent, false);
  assert.equal(result.intentResolutionEvidence.warningCodes, null);
});
test('retains separate turn and retry ordinals, with no deduplication across attempts', () => {
  const input = report([attachment(), attachment(projection, 2)]);
  const results = input.suites[0].suites[0].specs[0].tests[0].results;
  results.push({ status: 'failed', retry: 1, attachments: [attachment()] });
  const turns = exportGovernedState(input).turns;
  assert.deepEqual(turns.map(x => [x.turnNumber, x.resultOrdinal, x.retry]), [[1, 0, 0], [2, 0, 0], [1, 1, 1]]);
});
test('bounds and deduplicates diagnostic arrays', () => {
  const value = { ...projection, blockingDiagnosticCodes: Array.from({ length: 40 }, (_, i) => `CODE_${i}`), quickReplyIds: ['review-apply', 'review-apply'] };
  const result = exportGovernedState(report([attachment(value)])).turns[0].projection;
  assert.equal(result.blockingDiagnosticCodes.length, 24);
  assert.deepEqual(result.quickReplyIds, ['review-apply']);
});
test('rejects paths, oversized or malformed known attachments and duplicate turn ids', () => {
  for (const invalid of [
    { ...attachment(), path: '/private/credentials' },
    { ...attachment(), body: 'A'.repeat(100001) },
    { ...attachment(), body: 'invalid' },
    attachment({ ...projection, schemaVersion: 'other' }),
    attachment({ ...projection, scenarioId: 'unexpected' }),
  ]) assert.throws(() => exportGovernedState(report([invalid])));
  assert.throws(() => exportGovernedState(report([attachment(), attachment()])), /duplicate/);
});
