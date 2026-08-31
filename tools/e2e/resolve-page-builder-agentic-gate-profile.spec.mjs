import assert from 'node:assert/strict';
import test from 'node:test';

import {
  loadGateMatrix,
  resolveGateProfile,
  validateGateMatrix,
} from './resolve-page-builder-agentic-gate-profile.mjs';

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

test('validates the canonical matrix and resolves the single-table profile', () => {
  const matrix = loadGateMatrix();
  const profile = resolveGateProfile(matrix, 'single-table');

  assert.equal(profile.expectedDiscovered, 3);
  assert.equal(profile.minimumExecuted, 3);
  assert.equal(profile.expectedSkipped, 0);
  assert.equal(profile.retries, 0);
  assert.equal(profile.humanTurnLimit, 1);
  assert.deepEqual(profile.scenarios, [
    'critical-interception-guard',
    'single-table-control',
    'table-human-refinement',
  ]);
  assert.deepEqual(profile.receiptRequirements.map((entry) => entry.scenarioId), [
    'single-table-control',
  ]);
  assert.deepEqual(profile.semanticRefinementRequirements.map((entry) => entry.scenarioId), [
    'table-human-refinement',
  ]);
});

test('rejects a non-positive human turn limit', () => {
  const matrix = clone(loadGateMatrix());
  matrix.modes['single-table'].humanTurnLimit = 0;
  assert.throws(() => validateGateMatrix(matrix), /humanTurnLimit must be a positive integer/);
});

test('rejects mode counts that drift from the scenario-to-test catalog', () => {
  const matrix = clone(loadGateMatrix());
  matrix.modes['single-table'].expectedDiscovered = 2;
  assert.throws(() => validateGateMatrix(matrix), /expectedDiscovered must equal its derived test count/);
});

test('rejects duplicated attachments and evidence titles outside their scenario', () => {
  const duplicatedAttachment = clone(loadGateMatrix());
  duplicatedAttachment.evidence.governedStateProjections[0].attachmentName =
    duplicatedAttachment.evidence.scenarioReceipts[0].attachmentName;
  assert.throws(() => validateGateMatrix(duplicatedAttachment), /attachment names must not contain duplicates/);

  const divergentTitle = clone(loadGateMatrix());
  divergentTitle.evidence.scenarioReceipts[0].testTitle = 'unrelated test';
  assert.throws(() => validateGateMatrix(divergentTitle), /testTitle diverges from scenarioTests/);
});

test('rejects required titles that no longer match their selected scenario tags', () => {
  const matrix = clone(loadGateMatrix());
  matrix.modes.full.requiredPassedTests.reverse();
  assert.throws(
    () => validateGateMatrix(matrix),
    /requiredPassedTests must exactly match the ordered scenarioTests projection/,
  );
});
