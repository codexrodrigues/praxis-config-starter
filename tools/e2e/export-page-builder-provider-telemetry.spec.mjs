import assert from 'node:assert/strict';
import test from 'node:test';
import { exportProviderTelemetry } from './export-page-builder-provider-telemetry.mjs';
const invocation = { phase: 'intent', attempt: 1, model: 'gpt-5-mini', status: 'failure', failureKind: 'timeout', inputTokens: 12 };
const telemetry = { schemaVersion: 'praxis-agentic-authoring-provider-telemetry.v1', invocationCount: 1, truncated: false, providerInvocations: [invocation] };
const attachment = (value, turn = 1) => ({ name: `provider-telemetry-turn-${turn}.json`, contentType: 'application/json', body: Buffer.from(JSON.stringify(value)).toString('base64') });
const report = attachments => ({ suites: [{ suites: [{ specs: [{ title: 'PRIVATE', tests: [{ results: [{ status: 'failed', retry: 0, error: 'SECRET', attachments }] }] }] }] }] });

test('exports a failed journey without a final scenario receipt', () => {
  const result = exportProviderTelemetry(report([attachment(telemetry)]));
  assert.equal(result.turns[0].telemetry.providerInvocations[0].failureKind, 'timeout');
  assert.equal(result.turns[0].retry, 0);
});
test('preserves identical phase and attempt in separate turns', () => {
  const result = exportProviderTelemetry(report([attachment(telemetry), attachment(telemetry, 2)]));
  assert.deepEqual(result.turns.map(turn => turn.turnNumber), [1, 2]);
  assert.equal(result.turns[1].telemetry.providerInvocations.length, 1);
});
test('strips arbitrary fields and unsafe strings from private attachments', () => {
  const result = exportProviderTelemetry(report([attachment({ ...telemetry, rawPrompt: 'SECRET', providerInvocations: [{ ...invocation, responseId: 'Bearer SECRET', rawResponse: 'SECRET', model: 'model\nSECRET' }] }), { name: 'private.json', body: 'SECRET' }]));
  assert.doesNotMatch(JSON.stringify(result), /SECRET|PRIVATE|rawResponse|rawPrompt/);
  assert.equal(result.turns[0].telemetry.providerInvocations[0].model, null);
});
test('retains unknown usage and truncation without fabricating totals', () => {
  const result = exportProviderTelemetry(report([attachment({ ...telemetry, truncated: true })]));
  assert.equal(result.turns[0].telemetry.truncated, true);
  assert.equal(result.turns[0].telemetry.providerInvocations[0].outputTokens, null);
  assert.equal(result.turns[0].telemetry.providerInvocations[0].inputTokens, 12);
  assert.equal(result.turns[0].telemetry.providerInvocations[0].totalTokens, null);
});
test('rejects attachment paths without reading them', () => {
  assert.throws(() => exportProviderTelemetry(report([{ ...attachment(telemetry), path: '/private/credentials' }])), /inline JSON/);
});
test('retains missing terminal telemetry as unknown and missing attachments as unobserved', () => {
  assert.equal(exportProviderTelemetry(report([attachment(null)])).turns[0].telemetry, null);
  assert.deepEqual(exportProviderTelemetry(report([])).turns, []);
});
test('rejects malformed known telemetry', () => {
  assert.throws(() => exportProviderTelemetry(report([attachment({ schemaVersion: 'other' })])), /Invalid provider/);
});
