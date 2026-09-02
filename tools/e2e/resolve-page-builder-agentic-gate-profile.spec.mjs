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

test('resolves the focal master-detail profile from the existing operational receipt', () => {
  const profile = resolveGateProfile(loadGateMatrix(), 'master-detail');

  assert.equal(profile.expectedDiscovered, 2);
  assert.equal(profile.minimumExecuted, 2);
  assert.equal(profile.expectedSkipped, 0);
  assert.equal(profile.retries, 0);
  assert.equal(profile.domainCatalogRagRequired, true);
  assert.equal(profile.domainCatalogResourceKey, 'operations.missoes');
  assert.equal(profile.apiCatalogGroup, 'operations');
  assert.deepEqual(profile.apiCatalogPathPrefixes, ['/api/operations/missoes']);
  assert.deepEqual(profile.scenarios, [
    'critical-interception-guard',
    'live-resource-workspace-command',
  ]);
  assert.deepEqual(profile.receiptRequirements.map((entry) => entry.scenarioId), [
    'live-resource-workspace-command',
  ]);
  assert.equal(profile.receiptRequirements[0].archetype, 'master-detail-command');
  assert.deepEqual(profile.receiptRequirements[0].requiredFunctionalAssertions, [
    'composition.master-visible',
    'composition.detail-visible',
    'composition.selection-propagated',
    'discovery.actions.http-200',
    'discovery.capabilities.http-200',
    'command.execute.http-200',
    'command.duplicate.http-409',
    'resource.refresh-observed',
    'persistence.reload-rendered',
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

test('resolves the focal tabs-nested profile with its functional receipt', () => {
  const profile = resolveGateProfile(loadGateMatrix(), 'tabs-nested');

  assert.equal(profile.expectedDiscovered, 2);
  assert.equal(profile.retries, 0);
  assert.equal(profile.humanTurnLimit, 1);
  assert.equal(profile.domainCatalogRagRequired, true);
  assert.equal(profile.domainCatalogResourceKey, 'operations.missoes');
  assert.deepEqual(profile.apiCatalogPathPrefixes, ['/api/operations/missoes']);
  assert.deepEqual(profile.scenarios, [
    'critical-interception-guard',
    'tabs-nested-workspace-control',
  ]);
  assert.deepEqual(profile.receiptRequirements.map((entry) => entry.scenarioId), [
    'tabs-nested-workspace-control',
  ]);
  assert.deepEqual(profile.receiptRequirements[0].requiredFunctionalAssertions, [
    'composition.tabs-root',
    'composition.nested-table-form',
    'composition.selection-state-resource-id',
    'runtime.first-selection-details-loaded',
    'runtime.tab-switch-context-preserved',
    'runtime.second-selection-replaces-detail',
    'runtime.narrow-viewport-functional',
    'runtime.post-reload-selection-details-loaded',
    'persistence.reload-equivalent',
  ]);
});

test('resolves the independent business-command profile with governed employee scope', () => {
  const profile = resolveGateProfile(loadGateMatrix(), 'business-command');

  assert.equal(profile.expectedDiscovered, 2);
  assert.equal(profile.minimumExecuted, 2);
  assert.equal(profile.expectedSkipped, 0);
  assert.equal(profile.retries, 0);
  assert.equal(profile.humanTurnLimit, 1);
  assert.equal(profile.domainCatalogRagRequired, true);
  assert.equal(profile.domainCatalogResourceKey, 'human-resources.funcionarios');
  assert.equal(profile.apiCatalogGroup, 'human-resources');
  assert.deepEqual(profile.apiCatalogPathPrefixes, [
    '/api/human-resources/funcionarios',
  ]);
  assert.deepEqual(profile.scenarios, [
    'critical-interception-guard',
    'business-command-control',
  ]);
  assert.deepEqual(profile.receiptRequirements.map((entry) => entry.scenarioId), [
    'business-command-control',
  ]);
  assert.equal(profile.receiptRequirements[0].archetype, 'business-command');
  assert.deepEqual(profile.receiptRequirements[0].requiredFunctionalAssertions, [
    'discovery.actions-capabilities.http-200',
    'command.contract-form-confirmation-version',
    'command.confirmation-cancelled',
    'command.cancelled-not-sent',
    'command.stale-version.http-412',
    'command.governed-error-visible',
    'command.confirmation-accepted',
    'command.governed-headers-observed',
    'command.execute.http-200',
    'resource.refresh-observed',
    'resource.read-after-write-observed',
    'resource.availability-transition-observed',
    'persistence.reload-equivalent',
  ]);
});

test('resolves business-command runtime excellence without paid-provider or RAG coupling', () => {
  const profile = resolveGateProfile(
    loadGateMatrix(),
    'business-command-runtime-excellence',
  );

  assert.equal(profile.executionLane, 'runtime-excellence');
  assert.equal(profile.providerRequired, false);
  assert.equal(profile.expectedDiscovered, 1);
  assert.equal(profile.minimumExecuted, 1);
  assert.equal(profile.expectedSkipped, 0);
  assert.equal(profile.retries, 0);
  assert.equal(profile.humanTurnLimit, null);
  assert.equal(profile.domainCatalogRagRequired, false);
  assert.equal(profile.domainCatalogResourceKey, null);
  assert.equal(profile.apiCatalogGroup, null);
  assert.deepEqual(profile.apiCatalogPathPrefixes, []);
  assert.deepEqual(profile.scenarios, ['business-command-runtime-excellence']);
  assert.deepEqual(profile.receiptRequirements, []);
  assert.deepEqual(
    profile.runtimeExcellenceReceiptRequirements.map((entry) => entry.scenarioId),
    ['business-command-runtime-excellence'],
  );
  assert.equal(
    profile.runtimeExcellenceReceiptRequirements[0].planFixture,
    'tools/e2e/fixtures/business-command-runtime-excellence.ui-composition-plan.json',
  );
  assert.equal(
    profile.runtimeExcellenceReceiptRequirements[0].expectedCompiledPayloadSha256,
    '721bca02b364a2b383e6a27cf9c5926d1f7c90fd07d34b87727b732cf0dd806b',
  );
  assert.equal(
    profile.runtimeExcellenceReceiptRequirements[0].expectedPlanFixtureSha256,
    'a774a8413eb89cccfed85d80abb4f8c56c48f94e58e04aec76e6614cc05d2aac',
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
  assert.match(windowsRunnerSource, /\$executionLane -eq "runtime-excellence"/);
  assert.match(windowsRunnerSource, /if \(\$providerRequired -and -not \$ConfirmPaidProviderRun\.IsPresent\)/);
  assert.match(windowsRunnerSource, /SPRING_AI_ENABLED = '\$\(\$providerRequired\.ToString\(\)\.ToLowerInvariant\(\)\)'/);
  assert.match(windowsRunnerSource, /Skipping pgvector preflight because runtime excellence/);
  assert.match(windowsRunnerSource, /Skipping Domain Catalog and API Catalog ingestion because runtime excellence/);
  assert.match(windowsRunnerSource, /scripts\\build-libs\.js --prod --only praxis-page-builder/);
  assert.doesNotMatch(windowsRunnerSource, /ng build praxis-page-builder/);
  assert.match(windowsRunnerSource, /praxis-page-builder-runtime-excellence\.playwright\.config\.ts/);
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

test('rejects paid-provider and catalog coupling in runtime excellence', () => {
  const provider = clone(loadGateMatrix());
  provider.modes['business-command-runtime-excellence'].providerRequired = true;
  assert.throws(
    () => validateGateMatrix(provider),
    /providerRequired must match its execution lane/,
  );

  const rag = clone(loadGateMatrix());
  rag.modes['business-command-runtime-excellence'].domainCatalogRagRequired = true;
  assert.throws(
    () => validateGateMatrix(rag),
    /cannot require Domain Catalog RAG in runtime-excellence/,
  );

  const catalog = clone(loadGateMatrix());
  catalog.modes['business-command-runtime-excellence'].apiCatalogGroup = 'human-resources';
  assert.throws(
    () => validateGateMatrix(catalog),
    /cannot require API Catalog ingestion in runtime-excellence/,
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
