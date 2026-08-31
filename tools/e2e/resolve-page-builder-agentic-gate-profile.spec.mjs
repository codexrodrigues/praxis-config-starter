import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

import {
  loadGateMatrix,
  resolveGateProfile,
  validateGateMatrix,
} from './resolve-page-builder-agentic-gate-profile.mjs';

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

const windowsRunnerSource = readFileSync(
  new URL('../Invoke-PbAgenticFullE2E.ps1', import.meta.url),
  'utf8',
);

test('validates the canonical matrix and resolves the single-table profile', () => {
  const matrix = loadGateMatrix();
  const profile = resolveGateProfile(matrix, 'single-table');

  assert.equal(profile.expectedDiscovered, 3);
  assert.equal(profile.minimumExecuted, 3);
  assert.equal(profile.expectedSkipped, 0);
  assert.equal(profile.retries, 0);
  assert.equal(profile.humanTurnLimit, 1);
  assert.equal(profile.domainCatalogRagRequired, false);
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

test('resolves the focal CRUD profile with its matrix-owned receipt', () => {
  const matrix = loadGateMatrix();
  const profile = resolveGateProfile(matrix, 'crud-simple');

  assert.equal(profile.expectedDiscovered, 2);
  assert.equal(profile.minimumExecuted, 2);
  assert.equal(profile.expectedSkipped, 0);
  assert.equal(profile.retries, 0);
  assert.equal(profile.humanTurnLimit, null);
  assert.equal(profile.domainCatalogResourceKey, 'human-resources.departamentos');
  assert.deepEqual(profile.scenarios, [
    'critical-interception-guard',
    'crud-simple-control',
  ]);
  assert.deepEqual(profile.receiptRequirements.map((entry) => entry.scenarioId), [
    'crud-simple-control',
  ]);
  assert.deepEqual(profile.receiptRequirements[0].requiredFunctionalAssertions, [
    'composition.crud-single-host',
    'discovery.capabilities.http-200',
    'discovery.capabilities.crud-operations',
    'discovery.create-schema.http-200',
    'crud.create.http-201',
    'crud.read-after-create-rendered',
    'crud.update.http-200',
    'crud.read-after-update-rendered',
    'crud.delete.http-204',
    'crud.read-after-delete-absent',
    'resource.refresh-observed',
    'persistence.reload-equivalent',
  ]);
});

test('resolves the focal related-resource profile with governed mission scope', () => {
  const profile = resolveGateProfile(loadGateMatrix(), 'related-resource');

  assert.equal(profile.expectedDiscovered, 2);
  assert.equal(profile.retries, 0);
  assert.equal(profile.domainCatalogRagRequired, true);
  assert.equal(profile.domainCatalogResourceKey, 'operations.missoes');
  assert.equal(profile.apiCatalogGroup, 'operations');
  assert.deepEqual(profile.apiCatalogPathPrefixes, [
    '/api/operations/missoes',
    '/api/operations/missao-participantes',
  ]);
  assert.deepEqual(profile.scenarios, [
    'critical-interception-guard',
    'related-resource-control',
  ]);
  assert.deepEqual(profile.receiptRequirements.map((entry) => entry.scenarioId), [
    'related-resource-control',
  ]);
  assert.equal(
    profile.receiptRequirements[0].archetype,
    'parent-child-related-resource',
  );
});

test('keeps the Windows runner matrix-driven for new focal modes and domain scope', () => {
  assert.doesNotMatch(
    windowsRunnerSource,
    /\[ValidateSet\("smoke", "single-table", "full"\)\]/,
  );
  assert.match(windowsRunnerSource, /\$modeMatrix\.domainCatalogResourceKey/);
  assert.match(windowsRunnerSource, /\$modeMatrix\.domainCatalogRagRequired/);
  assert.match(windowsRunnerSource, /schemas\/domain\?resourceKey=/);
  assert.match(windowsRunnerSource, /domain-catalog\/rag\/status/);
  assert.match(windowsRunnerSource, /PUBLISHED/);
  assert.match(windowsRunnerSource, /reconciled/);
});

test('rejects a non-positive human turn limit', () => {
  const matrix = clone(loadGateMatrix());
  matrix.modes['single-table'].humanTurnLimit = 0;
  assert.throws(() => validateGateMatrix(matrix), /humanTurnLimit must be a positive integer/);
});

test('rejects a non-canonical domain catalog resource identity', () => {
  const matrix = clone(loadGateMatrix());
  matrix.modes['crud-simple'].domainCatalogResourceKey = '/api/human-resources/departamentos';
  assert.throws(
    () => validateGateMatrix(matrix),
    /domainCatalogResourceKey must be a canonical dotted resource identity/,
  );
});

test('rejects a non-boolean Domain Catalog RAG requirement', () => {
  const matrix = clone(loadGateMatrix());
  matrix.modes['related-resource'].domainCatalogRagRequired = 'true';
  assert.throws(
    () => validateGateMatrix(matrix),
    /domainCatalogRagRequired must be a boolean/,
  );
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
