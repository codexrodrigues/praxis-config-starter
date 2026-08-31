import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { loadGateMatrix, resolveGateProfile } from './resolve-page-builder-agentic-gate-profile.mjs';
import {
  validateGateEvidenceSet,
  validateGateReport,
} from './validate-page-builder-agentic-gate-evidence.mjs';

const profile = resolveGateProfile(loadGateMatrix(), 'single-table');
const crudProfile = resolveGateProfile(loadGateMatrix(), 'crud-simple');
const relatedProfile = resolveGateProfile(loadGateMatrix(), 'related-resource');
const hash = 'a'.repeat(64);

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
    writeFileSync(second, JSON.stringify(report()));
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
