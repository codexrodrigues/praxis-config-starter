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
