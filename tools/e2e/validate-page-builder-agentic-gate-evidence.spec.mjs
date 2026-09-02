import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { loadGateMatrix, resolveGateProfile } from './resolve-page-builder-agentic-gate-profile.mjs';
import {
  validateGateEvidenceSet,
  validateGateReport,
  validatePublishedGateEvidenceSet,
  validatePublishedGateResult,
  validateRuntimeExcellenceEvidenceSet,
  validateRuntimeExcellenceResult,
} from './validate-page-builder-agentic-gate-evidence.mjs';

const profile = resolveGateProfile(loadGateMatrix(), 'single-table');
const crudProfile = resolveGateProfile(loadGateMatrix(), 'crud-simple');
const masterDetailProfile = resolveGateProfile(loadGateMatrix(), 'master-detail');
const relatedProfile = resolveGateProfile(loadGateMatrix(), 'related-resource');
const businessCommandProfile = resolveGateProfile(loadGateMatrix(), 'business-command');
const businessCommandRuntimeProfile = resolveGateProfile(
  loadGateMatrix(),
  'business-command-runtime-excellence',
);
const hash = 'a'.repeat(64);
const runtimeCompiledHash =
  '8c5742a203354a30724c3614cdd748a682c8a953c6829e2f98312b2289c9af31';
const runtimePlanHash =
  '694dba6cb77d9f243754dc7c48d82c8f3ae12313c70cbfd7729c6b6943f4e9d8';

function jsonAttachment(name, value) {
  return {
    name,
    contentType: 'application/json',
    body: Buffer.from(JSON.stringify(value)).toString('base64'),
  };
}

function receipt() {
  return {
    schemaVersion: 'praxis.page-builder-agentic-scenario-receipt/v1',
    scenarioId: 'single-table-control',
    archetype: 'single-table-control',
    authoringFirstPass: true,
    interaction: {
      initialPromptCount: 1,
      totalTurnCount: 1,
      clarificationQuickReplyCount: 0,
      governedRevisionCount: 0,
      correctiveTypedPromptCount: 0,
      deterministicRepairCount: 0,
    },
    terminal: {
      outcome: 'applicable',
      transport: 'stream',
      blockingDiagnosticCodes: [],
      referencePresent: true,
      backendPatchAuthority: true,
    },
    apply: {
      terminalReferenceMatched: true,
      streamIdMatched: true,
      resultEventIdMatched: true,
      payloadSha256: hash,
      matchesPersistedPayload: true,
    },
    persistence: {
      version: 1,
      etagPresent: true,
      persistedPayloadSha256: hash,
      reloadPayloadSha256: hash,
      reloadMatchesPersisted: true,
      reloadEtagMatches: true,
    },
    functionalAssertions: [
      'composition.single-table-only',
      'grounding.resource-schema-verified',
      'resource.rows-rendered',
      'persistence.page-apply-authorized',
      'persistence.readback-equivalent',
      'persistence.reload-equivalent',
    ],
    timingMs: {
      firstUsefulStatus: 10,
      firstApplicableTerminal: 20,
      applyCompleted: 30,
      runtimeFunctional: 40,
      reloadCompleted: 50,
      total: 50,
    },
  };
}

function refinement() {
  return {
    baselineFieldCount: 2,
    turns: [{
      turnId: 'rename-status',
      variation: 'short phrase',
      state: 'review',
      clarified: false,
      operationKind: 'modify',
      artifactKind: 'table',
      changeKind: 'column.header.set',
      blockingDiagnosticCodes: [],
      operationCandidateIds: ['column.header.set'],
      selectedOperationIds: [],
      targetComponentMatchesPraxisTable: true,
      targetWidgetKeyPresent: true,
      selectedResourceMatchesEmployee: true,
      semanticDecisionIdPresent: true,
      semanticDecisionPreviousIdPresent: true,
      semanticDecisionRefinementOfPresent: true,
      activeDecisionLineagePresent: true,
      columnHeaderCapabilityPresent: true,
      targetMatchesCompiledComponentEdit: true,
      expectedOperationIds: ['column.header.set'],
      operationIds: ['column.header.set'],
      compiledPatchPresent: true,
      proposedColumnFields: ['id', 'status'],
      materializedColumnFields: ['id', 'status'],
      assistantMessagePresent: true,
    }],
    focalTurnLimit: 1,
    turnLimitSource: 'canonical-gate-profile',
    canonicalFocalRefinement: true,
  };
}

function crudReceipt() {
  return {
    ...receipt(),
    scenarioId: 'crud-simple-control',
    archetype: 'crud-simple',
    functionalAssertions: [
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
    ],
  };
}

function relatedReceipt() {
  return {
    ...receipt(),
    scenarioId: 'related-resource-control',
    archetype: 'parent-child-related-resource',
    functionalAssertions: [
      'composition.parent-table-related-outlet',
      'composition.selection-parent-context',
      'discovery.surface-catalog.http-200',
      'related.read.http-200',
      'related.child-crud-materialized',
      'related.child-update.http-200',
      'related.child-delete.http-204',
      'related.child-create.http-201',
      'related.child-create-parent-derived',
      'related.parent-switch-no-data-leak',
      'persistence.reload-equivalent',
    ],
  };
}

function masterDetailReceipt() {
  return {
    ...receipt(),
    scenarioId: 'live-resource-workspace-command',
    archetype: 'master-detail-command',
    functionalAssertions: [
      'composition.master-visible',
      'composition.detail-visible',
      'composition.selection-propagated',
      'discovery.actions.http-200',
      'discovery.capabilities.http-200',
      'command.execute.http-200',
      'command.duplicate.http-409',
      'resource.refresh-observed',
      'persistence.reload-rendered',
    ],
  };
}

function businessCommandReceipt() {
  return {
    ...receipt(),
    scenarioId: 'business-command-control',
    archetype: 'business-command',
    functionalAssertions: [
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
    ],
  };
}

function businessCommandRuntimeReceipt() {
  return {
    schemaVersion: 'praxis.page-builder-runtime-excellence-receipt/v1',
    scenarioId: 'business-command-runtime-excellence',
    archetype: 'business-command',
    providerUsed: false,
    materialization: {
      sourceKind: 'praxis.ui-composition-plan',
      sourceSha256: runtimePlanHash,
      typescriptCompilerValid: true,
      compiledPayloadSha256: runtimeCompiledHash,
      persistedPayloadSha256: runtimeCompiledHash,
      reloadPayloadSha256: runtimeCompiledHash,
      persistedPageEquivalent: true,
      reloadEquivalent: true,
    },
    persistence: {
      version: 1,
      etagPresent: true,
    },
    functionalAssertions: [
      'materialization.ui-composition-plan-typescript-valid',
      'materialization.persisted-page-equivalent',
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
      'resource.deterministic-default-sort-observed',
      'persistence.reload-equivalent',
    ],
    timingMs: {
      planCompiled: 10,
      persisted: 20,
      runtimeFunctional: 40,
      reloadCompleted: 50,
      total: 50,
    },
  };
}

function result(attachments = []) {
  return {
    status: 'passed',
    duration: 1,
    errors: [],
    stdout: [],
    stderr: [],
    retry: 0,
    startTime: '2026-08-31T00:00:00.000Z',
    attachments,
  };
}

function spec(title, attachments = []) {
  return {
    title,
    ok: true,
    tests: [{
      timeout: 600000,
      annotations: [],
      expectedStatus: 'passed',
      projectId: 'chromium-production-like',
      projectName: 'chromium-production-like',
      results: [result(attachments)],
      status: 'expected',
    }],
    id: title,
    file: 'focal.spec.ts',
    line: 1,
    column: 1,
  };
}

function report() {
  return {
    config: {},
    suites: [{
      title: 'focal',
      file: 'focal.spec.ts',
      column: 1,
      line: 1,
      specs: profile.requiredPassedTests.map((title) => {
        if (title.startsWith('single-table control')) {
          return spec(title, [jsonAttachment('single-table-first-pass-receipt.json', receipt())]);
        }
        if (title.startsWith('table-human refinement')) {
          return spec(title, [jsonAttachment('table-human-refinement-sanitized-evidence.json', refinement())]);
        }
        return spec(title);
      }),
    }],
    errors: [],
    stats: {
      startTime: '2026-08-31T00:00:00.000Z',
      duration: 100,
      expected: 3,
      skipped: 0,
      unexpected: 0,
      flaky: 0,
    },
  };
}

function crudReport() {
  return {
    config: {},
    suites: [{
      title: 'crud-focal',
      file: 'crud-focal.spec.ts',
      column: 1,
      line: 1,
      specs: crudProfile.requiredPassedTests.map((title) => title.startsWith('CRUD simples')
        ? spec(title, [jsonAttachment('crud-simple-first-pass-receipt.json', crudReceipt())])
        : spec(title)),
    }],
    errors: [],
    stats: {
      startTime: '2026-08-31T00:00:00.000Z',
      duration: 100,
      expected: 2,
      skipped: 0,
      unexpected: 0,
      flaky: 0,
    },
  };
}

function relatedReport() {
  return {
    config: {},
    suites: [{
      title: 'related-resource-focal',
      file: 'related-resource-focal.spec.ts',
      column: 1,
      line: 1,
      specs: relatedProfile.requiredPassedTests.map((title) => title.startsWith('Parent-child')
        ? spec(title, [jsonAttachment('related-resource-first-pass-receipt.json', relatedReceipt())])
        : spec(title)),
    }],
    errors: [],
    stats: {
      startTime: '2026-08-31T00:00:00.000Z',
      duration: 100,
      expected: 2,
      skipped: 0,
      unexpected: 0,
      flaky: 0,
    },
  };
}

function masterDetailReport() {
  return {
    config: {},
    suites: [{
      title: 'master-detail-focal',
      file: 'master-detail-focal.spec.ts',
      column: 1,
      line: 1,
      specs: masterDetailProfile.requiredPassedTests.map((title) => title.startsWith('Fluxo 3')
        ? spec(title, [jsonAttachment('mission-workspace-first-pass-receipt.json', masterDetailReceipt())])
        : spec(title)),
    }],
    errors: [],
    stats: {
      startTime: '2026-08-31T00:00:00.000Z',
      duration: 100,
      expected: 2,
      skipped: 0,
      unexpected: 0,
      flaky: 0,
    },
  };
}

function businessCommandReport() {
  return {
    config: {},
    suites: [{
      title: 'business-command-focal',
      file: 'business-command-focal.spec.ts',
      column: 1,
      line: 1,
      specs: businessCommandProfile.requiredPassedTests.map((title) =>
        title.startsWith('Business command')
          ? spec(title, [jsonAttachment(
            'business-command-first-pass-receipt.json',
            businessCommandReceipt(),
          )])
          : spec(title)),
    }],
    errors: [],
    stats: {
      startTime: '2026-09-01T00:00:00.000Z',
      duration: 100,
      expected: 2,
      skipped: 0,
      unexpected: 0,
      flaky: 0,
    },
  };
}

function businessCommandRuntimeReport() {
  return {
    config: {},
    suites: [{
      title: 'business-command-runtime-excellence',
      file: 'business-command-runtime-excellence.spec.ts',
      column: 1,
      line: 1,
      specs: [spec(
        businessCommandRuntimeProfile.requiredPassedTests[0],
        [jsonAttachment(
          'business-command-runtime-excellence-receipt.json',
          businessCommandRuntimeReceipt(),
        )],
      )],
    }],
    errors: [],
    stats: {
      startTime: '2026-09-02T00:00:00.000Z',
      duration: 100,
      expected: 1,
      skipped: 0,
      unexpected: 0,
      flaky: 0,
    },
  };
}

function publishedRelatedResult(run) {
  const publishedReceipt = {
    ...relatedReceipt(),
    outcome: 'first-pass',
    firstPassFunctional: true,
    playwrightRetryAttempts: 0,
  };
  const reportSha256 = String(run).repeat(64);
  return {
    schemaVersion: 'praxis.page-builder-agentic-production-like-result/v1',
    productionLike: true,
    criticalEndpointMocks: 0,
    criticalInterceptionGuard: {
      testTitle: relatedProfile.requiredPassedTests[0],
      passed: true,
    },
    executionLane: 'live',
    validationMode: 'related-resource',
    e2ePassed: true,
    provider: 'gemini',
    model: 'gemini-test',
    embeddingProvider: 'gemini',
    dependencyAttestation: {
      configStarter: {
        artifactId: 'praxis-config-starter',
        version: '1.0.0',
        source: 'maven-central',
        referenceJarSha256: hash,
        quickstartNestedJarSha256: hash,
        quickstartEntry: 'BOOT-INF/lib/praxis-config-starter-1.0.0.jar',
        byteIdentical: true,
      },
    },
    aiRegistry: { snapshotHash: hash },
    versions: {
      configStarter: '1.0.0',
      quickstartConfigDependency: '1.0.0',
      metadataStarterDependency: '1.0.0',
      quickstart: '1.0.0',
      angularWorkspace: '1.0.0',
      java: 21,
      node: 'v20',
      playwright: '1.55',
      chromium: '140',
    },
    contractHash: hash,
    git: ['config', 'metadata', 'quickstart', 'angular'].map((name) => ({
      name,
      sha: 'b'.repeat(40),
      treeSha: 'c'.repeat(40),
      materialization: 'working-tree',
      dirty: false,
    })),
    matrix: {
      schemaVersion: relatedProfile.matrixSchemaVersion,
      scenarios: [...relatedProfile.scenarios],
      expectedDiscovered: relatedProfile.expectedDiscovered,
      minimumExecuted: relatedProfile.minimumExecuted,
      expectedSkipped: relatedProfile.expectedSkipped,
      requiredPassedTests: [...relatedProfile.requiredPassedTests],
      retries: relatedProfile.retries,
      domainCatalogRagRequired: relatedProfile.domainCatalogRagRequired,
      domainCatalogResourceKey: relatedProfile.domainCatalogResourceKey,
      apiCatalogGroup: relatedProfile.apiCatalogGroup,
      apiCatalogPathPrefixes: [...relatedProfile.apiCatalogPathPrefixes],
      receiptRequirements: relatedProfile.receiptRequirements,
      semanticRefinementRequirements: relatedProfile.semanticRefinementRequirements,
    },
    playwright: {
      discovered: 2,
      executed: 2,
      passed: 2,
      skipped: 0,
      failed: 0,
      flaky: 0,
      attempts: 2,
      retryAttempts: 0,
      durationMs: 100 + run,
      tests: relatedProfile.requiredPassedTests.map((title) => ({
        title,
        status: 'expected',
        attempts: 1,
        retryAttempts: 0,
      })),
    },
    evidenceValidation: {
      passed: true,
      artifact: 'evidence-validation-summary.json',
      attestation: {
        schemaVersion: 'praxis.page-builder-agentic-gate-run-attestation/v1',
        reportSha256,
        durationMs: 100 + run,
        discovered: 2,
        passed: 2,
        retries: 0,
        receipts: [{
          scenarioId: 'related-resource-control',
          firstPassFunctional: true,
          totalMs: publishedReceipt.timingMs.total,
          persistedPayloadSha256: publishedReceipt.persistence.persistedPayloadSha256,
        }],
        semanticRefinements: [],
      },
    },
    scenarioEvidence: [publishedReceipt],
    diagnosticEvidence: [],
    failureType: null,
  };
}

function publishedRuntimeExcellenceResult(run) {
  const runtimeReceipt = businessCommandRuntimeReceipt();
  const compactReceipt = {
    scenarioId: runtimeReceipt.scenarioId,
    providerUsed: false,
    totalMs: runtimeReceipt.timingMs.total,
    sourceSha256: runtimeReceipt.materialization.sourceSha256,
    persistedPayloadSha256: runtimeReceipt.materialization.persistedPayloadSha256,
  };
  const reportSha256 = String(run).repeat(64);
  const runtimeRequirements = businessCommandRuntimeProfile.runtimeExcellenceReceiptRequirements
    .map((definition) => ({
      scenarioId: definition.scenarioId,
      archetype: definition.archetype,
      planFixture: definition.planFixture,
      expectedPlanFixtureSha256: definition.expectedPlanFixtureSha256,
      expectedCompiledPayloadSha256: definition.expectedCompiledPayloadSha256,
      requiredFunctionalAssertions: definition.requiredFunctionalAssertions,
    }));
  return {
    schemaVersion: 'praxis.page-builder-runtime-excellence-result/v1',
    productionLike: true,
    criticalEndpointMocks: 0,
    criticalInterceptionGuard: {
      testTitle: 'critical interception guard',
      applicable: false,
      passed: null,
    },
    executionLane: 'runtime-excellence',
    validationMode: businessCommandRuntimeProfile.mode,
    e2ePassed: true,
    provider: null,
    providerRequired: false,
    model: null,
    embeddingProvider: 'not-used',
    datasourceKinds: { application: 'postgresql', config: 'postgresql' },
    dependencyAttestation: {
      configStarter: {
        artifactId: 'praxis-config-starter',
        version: '1.0.0',
        source: 'source-checkout',
        referenceJarSha256: hash,
        quickstartNestedJarSha256: hash,
        quickstartEntry: 'BOOT-INF/lib/praxis-config-starter-1.0.0.jar',
        byteIdentical: true,
      },
    },
    pgvector: null,
    compilerProofs: {
      java: {
        test: 'AgenticAuthoringUiCompositionPlanCompilerTest#compilesCertifiedBusinessCommandRuntimeFixtureWithoutAiProvider',
        passed: true,
        providerUsed: false,
        planFixtureSha256: runtimePlanHash,
      },
      typescript: {
        passed: true,
        providerUsed: false,
        sourceSha256: runtimePlanHash,
        persistedPayloadSha256: runtimeCompiledHash,
      },
    },
    backendBaseUrl: 'http://127.0.0.1:18081',
    uiBaseUrl: 'http://localhost:4200',
    loopbackOnly: true,
    cleanupVerified: true,
    artifactRoot: '/tmp/runtime-excellence',
    sourceAudit: { passed: true, artifact: 'source-audit.json' },
    evidenceValidation: {
      passed: true,
      artifact: 'evidence-validation-summary.json',
      attestation: {
        schemaVersion: 'praxis.page-builder-agentic-gate-run-attestation/v1',
        reportSha256,
        durationMs: 100 + run,
        discovered: 1,
        passed: 1,
        retries: 0,
        receipts: [],
        runtimeExcellenceReceipts: [compactReceipt],
        semanticRefinements: [],
      },
    },
    git: ['config', 'metadata', 'quickstart', 'angular'].map((name) => ({
      name,
      sha: 'b'.repeat(40),
      treeSha: 'c'.repeat(40),
      materialization: 'working-tree',
      dirty: false,
    })),
    versions: {
      configStarter: '1.0.0',
      quickstartConfigDependency: '1.0.0',
      metadataStarterDependency: '1.0.0',
      quickstart: '1.0.0',
      angularWorkspace: '1.0.0',
      java: 21,
      node: 'v20',
      playwright: '1.55',
      chromium: '140',
    },
    contractHash: hash,
    capabilities: {
      source: 'registry',
      degraded: false,
      catalogCount: 10,
      chartResourceBinding: true,
    },
    aiRegistry: {
      ready: true,
      snapshotHash: hash,
      bootstrapOutcome: 'snapshot-current',
    },
    catalogs: { domain: null, api: null },
    matrix: {
      schemaVersion: businessCommandRuntimeProfile.matrixSchemaVersion,
      executionLane: businessCommandRuntimeProfile.executionLane,
      providerRequired: businessCommandRuntimeProfile.providerRequired,
      scenarios: [...businessCommandRuntimeProfile.scenarios],
      expectedDiscovered: businessCommandRuntimeProfile.expectedDiscovered,
      minimumExecuted: businessCommandRuntimeProfile.minimumExecuted,
      expectedSkipped: businessCommandRuntimeProfile.expectedSkipped,
      requiredPassedTests: [...businessCommandRuntimeProfile.requiredPassedTests],
      streamProcessingTimeoutSeconds:
        businessCommandRuntimeProfile.streamProcessingTimeoutSeconds,
      playwrightTestTimeoutMs: businessCommandRuntimeProfile.playwrightTestTimeoutMs,
      retries: businessCommandRuntimeProfile.retries,
      humanTurnLimit: businessCommandRuntimeProfile.humanTurnLimit,
      domainCatalogRagRequired: businessCommandRuntimeProfile.domainCatalogRagRequired,
      domainCatalogResourceKey: businessCommandRuntimeProfile.domainCatalogResourceKey,
      apiCatalogGroup: businessCommandRuntimeProfile.apiCatalogGroup,
      apiCatalogPathPrefixes: [...businessCommandRuntimeProfile.apiCatalogPathPrefixes],
      diagnosticProjectionRequirements:
        businessCommandRuntimeProfile.diagnosticProjectionRequirements,
      receiptRequirements: businessCommandRuntimeProfile.receiptRequirements,
      runtimeExcellenceReceiptRequirements: runtimeRequirements,
      semanticRefinementRequirements:
        businessCommandRuntimeProfile.semanticRefinementRequirements,
    },
    playwright: {
      discovered: 1,
      executed: 1,
      passed: 1,
      skipped: 0,
      failed: 0,
      flaky: 0,
      attempts: 1,
      retryAttempts: 0,
      durationMs: 100 + run,
      tests: [{
        title: businessCommandRuntimeProfile.requiredPassedTests[0],
        status: 'expected',
        attempts: 1,
        retryAttempts: 0,
      }],
    },
    scenarioEvidence: [],
    runtimeExcellenceEvidence: [compactReceipt],
    diagnosticEvidence: [],
    failureType: null,
  };
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function findResult(value, titlePrefix) {
  return value.suites[0].specs.find((entry) => entry.title.startsWith(titlePrefix)).tests[0].results[0];
}

function mutateAttachment(value, titlePrefix, attachmentName, mutation) {
  const attachment = findResult(value, titlePrefix).attachments.find((entry) => entry.name === attachmentName);
  const body = JSON.parse(Buffer.from(attachment.body, 'base64').toString('utf8'));
  mutation(body);
  attachment.body = Buffer.from(JSON.stringify(body)).toString('base64');
}

test('accepts a complete zero-retry single-table report', () => {
  const summary = validateGateReport(report(), '/tmp/report.json', profile);
  assert.equal(summary.discovered, 3);
  assert.equal(summary.retries, 0);
  assert.equal(summary.receipts[0].firstPassFunctional, true);
  assert.equal(summary.semanticRefinements[0].canonical, true);
});

test('accepts a complete zero-retry CRUD report', () => {
  const summary = validateGateReport(crudReport(), '/tmp/crud-report.json', crudProfile);
  assert.equal(summary.discovered, 2);
  assert.equal(summary.retries, 0);
  assert.equal(summary.receipts[0].scenarioId, 'crud-simple-control');
  assert.equal(summary.receipts[0].firstPassFunctional, true);
});

test('accepts the existing master-detail operational receipt in its focal profile', () => {
  const summary = validateGateReport(
    masterDetailReport(),
    '/tmp/master-detail-report.json',
    masterDetailProfile,
  );
  assert.equal(summary.discovered, 2);
  assert.equal(summary.retries, 0);
  assert.equal(summary.receipts[0].scenarioId, 'live-resource-workspace-command');
  assert.equal(summary.receipts[0].firstPassFunctional, true);
});

test('accepts a complete zero-retry related-resource report', () => {
  const summary = validateGateReport(
    relatedReport(),
    '/tmp/related-resource-report.json',
    relatedProfile,
  );
  assert.equal(summary.discovered, 2);
  assert.equal(summary.retries, 0);
  assert.equal(summary.receipts[0].scenarioId, 'related-resource-control');
  assert.equal(summary.receipts[0].firstPassFunctional, true);
});

test('accepts an independent zero-retry business-command report', () => {
  const summary = validateGateReport(
    businessCommandReport(),
    '/tmp/business-command-report.json',
    businessCommandProfile,
  );
  assert.equal(summary.discovered, 2);
  assert.equal(summary.retries, 0);
  assert.equal(summary.receipts[0].scenarioId, 'business-command-control');
  assert.equal(summary.receipts[0].firstPassFunctional, true);
});

test('accepts business-command runtime excellence without agentic authoring evidence', () => {
  const summary = validateGateReport(
    businessCommandRuntimeReport(),
    '/tmp/business-command-runtime-excellence-report.json',
    businessCommandRuntimeProfile,
  );
  assert.equal(summary.discovered, 1);
  assert.deepEqual(summary.receipts, []);
  assert.equal(summary.runtimeExcellenceReceipts.length, 1);
  assert.equal(
    summary.runtimeExcellenceReceipts[0].scenarioId,
    'business-command-runtime-excellence',
  );
  assert.equal(summary.runtimeExcellenceReceipts[0].providerUsed, false);
});

test('rejects runtime excellence when compiled and persisted materializations diverge', () => {
  const value = businessCommandRuntimeReport();
  mutateAttachment(
    value,
    'Business command executa lifecycle',
    'business-command-runtime-excellence-receipt.json',
    (body) => {
      body.materialization.persistedPayloadSha256 = 'b'.repeat(64);
    },
  );
  assert.throws(
    () => validateGateReport(
      value,
      '/tmp/business-command-runtime-excellence-report.json',
      businessCommandRuntimeProfile,
    ),
    /materialization lineage is incomplete/,
  );
});

test('rejects CRUD evidence that omits one functional operation', () => {
  const value = crudReport();
  mutateAttachment(value, 'CRUD simples', 'crud-simple-first-pass-receipt.json', (body) => {
    body.functionalAssertions = body.functionalAssertions.filter(
      (assertion) => assertion !== 'crud.delete.http-204',
    );
  });
  assert.throws(
    () => validateGateReport(value, '/tmp/crud-report.json', crudProfile),
    /functional assertions diverge/,
  );
});

test('rejects master-detail evidence without selection propagation', () => {
  const value = masterDetailReport();
  mutateAttachment(value, 'Fluxo 3', 'mission-workspace-first-pass-receipt.json', (body) => {
    body.functionalAssertions = body.functionalAssertions.filter(
      (assertion) => assertion !== 'composition.selection-propagated',
    );
  });
  assert.throws(
    () => validateGateReport(value, '/tmp/master-detail-report.json', masterDetailProfile),
    /functional assertions diverge/,
  );
});

test('rejects related-resource evidence that only exposes child actions without executing them', () => {
  const value = relatedReport();
  mutateAttachment(value, 'Parent-child materializa', 'related-resource-first-pass-receipt.json', (body) => {
    body.functionalAssertions = body.functionalAssertions.filter(
      (assertion) => assertion !== 'related.child-create.http-201',
    );
  });
  assert.throws(
    () => validateGateReport(value, '/tmp/related-resource-report.json', relatedProfile),
    /functional assertions diverge/,
  );
});

test('rejects business-command evidence that does not prove cancellation without HTTP execution', () => {
  const value = businessCommandReport();
  mutateAttachment(
    value,
    'Business command',
    'business-command-first-pass-receipt.json',
    (body) => {
      body.functionalAssertions = body.functionalAssertions.filter(
        (assertion) => assertion !== 'command.cancelled-not-sent',
      );
    },
  );
  assert.throws(
    () => validateGateReport(
      value,
      '/tmp/business-command-report.json',
      businessCommandProfile,
    ),
    /functional assertions diverge/,
  );
});

test('rejects Playwright retry or flaky evidence', () => {
  const value = report();
  findResult(value, 'single-table control').retry = 1;
  assert.throws(() => validateGateReport(value, '/tmp/report.json', profile), /pass without retry/);
});

test('rejects a receipt without persisted reload equivalence', () => {
  const value = report();
  mutateAttachment(value, 'single-table control', 'single-table-first-pass-receipt.json', (body) => {
    body.persistence.reloadEtagMatches = false;
  });
  assert.throws(() => validateGateReport(value, '/tmp/report.json', profile), /persistence\/reload lineage/);
});

test('rejects refinement without active semantic-decision lineage', () => {
  const value = report();
  mutateAttachment(value, 'table-human refinement', 'table-human-refinement-sanitized-evidence.json', (body) => {
    body.turns[0].activeDecisionLineagePresent = false;
  });
  assert.throws(() => validateGateReport(value, '/tmp/report.json', profile), /activeDecisionLineagePresent/);
});

test('aggregates only the declared number of unique run reports', () => {
  const directory = mkdtempSync(join(tmpdir(), 'praxis-gate-evidence-'));
  try {
    const first = join(directory, 'run-1.json');
    const second = join(directory, 'run-2.json');
    writeFileSync(first, JSON.stringify(report()));
    const secondReport = report();
    secondReport.stats.startTime = '2026-08-31T00:01:00.000Z';
    writeFileSync(second, JSON.stringify(secondReport));
    const summary = validateGateEvidenceSet({ reportPaths: [first, second], expectedRuns: 2, profile });
    assert.equal(summary.passedRuns, 2);
    assert.equal(summary.totals.passed, 6);
    assert.equal(summary.totals.retries, 0);
    assert.throws(
      () => validateGateEvidenceSet({ reportPaths: [first, first], expectedRuns: 2, profile }),
      /Report paths must be unique/,
    );
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('accepts a sanitized published related-resource result', () => {
  const summary = validatePublishedGateResult(
    publishedRelatedResult(1),
    '/tmp/production-like-result.json',
    relatedProfile,
  );
  assert.equal(summary.passed, 2);
  assert.equal(summary.retries, 0);
  assert.equal(summary.receipts[0].scenarioId, 'related-resource-control');
});

test('accepts a sanitized provider-independent runtime-excellence result', () => {
  const result = publishedRuntimeExcellenceResult(1);
  result.matrix.apiCatalogGroup = '';
  const summary = validateRuntimeExcellenceResult(
    result,
    '/tmp/runtime-excellence-result.json',
    businessCommandRuntimeProfile,
  );
  assert.equal(summary.passed, 1);
  assert.equal(summary.retries, 0);
  assert.equal(summary.runtimeExcellenceReceipts[0].providerUsed, false);
});

test('aggregates five unique runtime-excellence results on identical immutable coordinates', () => {
  const directory = mkdtempSync(join(tmpdir(), 'praxis-runtime-excellence-evidence-'));
  try {
    const paths = Array.from({ length: 5 }, (_, index) => {
      const path = join(directory, `run-${index + 1}.json`);
      writeFileSync(path, JSON.stringify(publishedRuntimeExcellenceResult(index + 1)));
      return path;
    });
    const summary = validateRuntimeExcellenceEvidenceSet({
      resultPaths: paths,
      expectedRuns: 5,
      profile: businessCommandRuntimeProfile,
    });
    assert.equal(summary.passedRuns, 5);
    assert.equal(summary.totals.passed, 5);
    assert.equal(summary.totals.retries, 0);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects runtime-excellence publication that attests provider use', () => {
  const result = publishedRuntimeExcellenceResult(1);
  result.runtimeExcellenceEvidence[0].providerUsed = true;
  assert.throws(
    () => validateRuntimeExcellenceResult(
      result,
      '/tmp/runtime-excellence-result.json',
      businessCommandRuntimeProfile,
    ),
    /providerUsed=false/,
  );
});

test('rejects runtime-excellence publication whose persisted hash diverges', () => {
  const result = publishedRuntimeExcellenceResult(1);
  result.evidenceValidation.attestation.runtimeExcellenceReceipts[0]
    .persistedPayloadSha256 = 'b'.repeat(64);
  assert.throws(
    () => validateRuntimeExcellenceResult(
      result,
      '/tmp/runtime-excellence-result.json',
      businessCommandRuntimeProfile,
    ),
    /persisted payload hash diverges/,
  );
});

test('rejects immutable coordinate drift across runtime-excellence runs', () => {
  const directory = mkdtempSync(join(tmpdir(), 'praxis-runtime-excellence-drift-'));
  try {
    const first = join(directory, 'run-1.json');
    const second = join(directory, 'run-2.json');
    writeFileSync(first, JSON.stringify(publishedRuntimeExcellenceResult(1)));
    const drifted = publishedRuntimeExcellenceResult(2);
    drifted.git[3].sha = 'd'.repeat(40);
    writeFileSync(second, JSON.stringify(drifted));
    assert.throws(
      () => validateRuntimeExcellenceEvidenceSet({
        resultPaths: [first, second],
        expectedRuns: 2,
        profile: businessCommandRuntimeProfile,
      }),
      /identical immutable coordinates/,
    );
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('aggregates five unique published results on identical immutable coordinates', () => {
  const directory = mkdtempSync(join(tmpdir(), 'praxis-published-gate-evidence-'));
  try {
    const paths = Array.from({ length: 5 }, (_, index) => {
      const path = join(directory, `run-${index + 1}.json`);
      const result = publishedRelatedResult(index + 1);
      const rebuiltJarSha256 = (index + 10).toString(16).repeat(64);
      result.dependencyAttestation.configStarter.referenceJarSha256 = rebuiltJarSha256;
      result.dependencyAttestation.configStarter.quickstartNestedJarSha256 = rebuiltJarSha256;
      writeFileSync(path, JSON.stringify(result));
      return path;
    });
    const summary = validatePublishedGateEvidenceSet({
      resultPaths: paths,
      expectedRuns: 5,
      profile: relatedProfile,
    });
    assert.equal(summary.passedRuns, 5);
    assert.equal(summary.totals.passed, 10);
    assert.equal(summary.totals.retries, 0);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects a published result whose nested Config Starter JAR differs from the reference artifact', () => {
  const result = publishedRelatedResult(1);
  result.dependencyAttestation.configStarter.quickstartNestedJarSha256 = 'b'.repeat(64);
  assert.throws(
    () => validatePublishedGateResult(result, '/tmp/production-like-result.json', relatedProfile),
    /JAR attestation is not byte-identical/,
  );
});

test('rejects a published result without a canonical Config artifact source', () => {
  const result = publishedRelatedResult(1);
  result.dependencyAttestation.configStarter.source = 'local-cache';
  assert.throws(
    () => validatePublishedGateResult(result, '/tmp/production-like-result.json', relatedProfile),
    /artifact source is invalid/,
  );
});

test('rejects duplicate report attestations across published runs', () => {
  const directory = mkdtempSync(join(tmpdir(), 'praxis-published-gate-duplicate-'));
  try {
    const first = join(directory, 'run-1.json');
    const second = join(directory, 'run-2.json');
    writeFileSync(first, JSON.stringify(publishedRelatedResult(1)));
    const duplicate = publishedRelatedResult(2);
    duplicate.evidenceValidation.attestation.reportSha256 = '1'.repeat(64);
    writeFileSync(second, JSON.stringify(duplicate));
    assert.throws(
      () => validatePublishedGateEvidenceSet({
        resultPaths: [first, second],
        expectedRuns: 2,
        profile: relatedProfile,
      }),
      /unique raw report hashes/,
    );
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects immutable coordinate drift across published runs', () => {
  const directory = mkdtempSync(join(tmpdir(), 'praxis-published-gate-drift-'));
  try {
    const first = join(directory, 'run-1.json');
    const second = join(directory, 'run-2.json');
    writeFileSync(first, JSON.stringify(publishedRelatedResult(1)));
    const drifted = publishedRelatedResult(2);
    drifted.model = 'gemini-drifted';
    writeFileSync(second, JSON.stringify(drifted));
    assert.throws(
      () => validatePublishedGateEvidenceSet({
        resultPaths: [first, second],
        expectedRuns: 2,
        profile: relatedProfile,
      }),
      /identical immutable coordinates/,
    );
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
