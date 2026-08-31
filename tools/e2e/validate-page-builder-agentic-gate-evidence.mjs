#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  defaultMatrixPath,
  loadGateMatrix,
  resolveGateProfile,
} from './resolve-page-builder-agentic-gate-profile.mjs';

const scriptPath = fileURLToPath(import.meta.url);

function assertCondition(condition, message) {
  if (!condition) throw new Error(message);
}

function assertObject(value, context) {
  assertCondition(value && typeof value === 'object' && !Array.isArray(value), `${context} must be an object.`);
}

function assertExactKeys(value, expected, context) {
  assertObject(value, context);
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  assertCondition(actual.length === wanted.length && actual.every((key, index) => key === wanted[index]),
    `${context} properties diverge. expected=${wanted.join(',')} actual=${actual.join(',')}`);
}

function assertUniqueStrings(values, context, pattern = null) {
  assertCondition(Array.isArray(values), `${context} must be an array.`);
  assertCondition(values.every((value) => typeof value === 'string'
      && value.length > 0
      && value.trim() === value
      && (!pattern || pattern.test(value))),
  `${context} must contain canonical non-empty strings.`);
  assertCondition(new Set(values).size === values.length, `${context} must not contain duplicates.`);
}

function sameOrderedStrings(left, right) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function collectSpecs(suites, output = []) {
  for (const suite of suites ?? []) {
    output.push(...(suite.specs ?? []));
    collectSpecs(suite.suites, output);
  }
  return output;
}

function readJsonAttachment(attachment, reportPath, context) {
  assertObject(attachment, context);
  assertCondition(attachment.contentType === 'application/json', `${context} must use application/json.`);
  let bytes;
  if (typeof attachment.body === 'string' && attachment.body.length > 0) {
    bytes = Buffer.from(attachment.body, 'base64');
  } else if (typeof attachment.path === 'string' && attachment.path.length > 0) {
    const attachmentPath = resolve(dirname(reportPath), attachment.path);
    bytes = readFileSync(attachmentPath);
  } else {
    throw new Error(`${context} has neither an inline body nor a path.`);
  }
  try {
    return JSON.parse(bytes.toString('utf8'));
  } catch (error) {
    throw new Error(`${context} is not valid JSON: ${error.message}`);
  }
}

function findAttachment(result, definition, reportPath) {
  const matches = (result.attachments ?? [])
    .filter((attachment) => attachment.name === definition.attachmentName);
  assertCondition(matches.length === 1,
    `${definition.testTitle} must contain exactly one ${definition.attachmentName} attachment.`);
  return readJsonAttachment(matches[0], reportPath, definition.attachmentName);
}

function validateReceipt(receipt, definition, retry) {
  assertExactKeys(receipt, [
    'schemaVersion', 'scenarioId', 'archetype', 'authoringFirstPass', 'interaction',
    'terminal', 'apply', 'persistence', 'functionalAssertions', 'timingMs',
  ], `Receipt ${definition.scenarioId}`);
  assertCondition(receipt.schemaVersion === 'praxis.page-builder-agentic-scenario-receipt/v1',
    `Receipt ${definition.scenarioId} has an unexpected schemaVersion.`);
  assertCondition(receipt.scenarioId === definition.scenarioId && receipt.archetype === definition.archetype,
    `Receipt ${definition.scenarioId} identity diverges from the gate matrix.`);

  assertExactKeys(receipt.interaction, [
    'initialPromptCount', 'totalTurnCount', 'clarificationQuickReplyCount',
    'governedRevisionCount', 'correctiveTypedPromptCount', 'deterministicRepairCount',
  ], `Receipt ${definition.scenarioId} interaction`);
  const interaction = receipt.interaction;
  const authoringFirstPass = interaction.initialPromptCount === 1
    && interaction.totalTurnCount === 1
    && interaction.clarificationQuickReplyCount === 0
    && interaction.governedRevisionCount === 0
    && interaction.correctiveTypedPromptCount === 0
    && interaction.deterministicRepairCount === 0;
  assertCondition(receipt.authoringFirstPass === authoringFirstPass && authoringFirstPass && retry === 0,
    `Receipt ${definition.scenarioId} is not a zero-retry first-pass result.`);

  assertExactKeys(receipt.terminal, [
    'outcome', 'transport', 'blockingDiagnosticCodes', 'referencePresent', 'backendPatchAuthority',
  ], `Receipt ${definition.scenarioId} terminal`);
  assertUniqueStrings(
    receipt.terminal.blockingDiagnosticCodes,
    `Receipt ${definition.scenarioId} blockingDiagnosticCodes`,
    /^[a-z0-9][a-z0-9._:-]{0,119}$/,
  );
  assertCondition(receipt.terminal.outcome === 'applicable'
      && receipt.terminal.transport === 'stream'
      && receipt.terminal.blockingDiagnosticCodes.length === 0
      && receipt.terminal.referencePresent === true
      && receipt.terminal.backendPatchAuthority === true,
  `Receipt ${definition.scenarioId} terminal authority is incomplete.`);

  assertExactKeys(receipt.apply, [
    'terminalReferenceMatched', 'streamIdMatched', 'resultEventIdMatched',
    'payloadSha256', 'matchesPersistedPayload',
  ], `Receipt ${definition.scenarioId} apply`);
  assertCondition(receipt.apply.terminalReferenceMatched === true
      && receipt.apply.streamIdMatched === true
      && receipt.apply.resultEventIdMatched === true
      && /^[0-9a-f]{64}$/.test(receipt.apply.payloadSha256)
      && receipt.apply.matchesPersistedPayload === true,
  `Receipt ${definition.scenarioId} apply lineage is incomplete.`);

  assertExactKeys(receipt.persistence, [
    'version', 'etagPresent', 'persistedPayloadSha256', 'reloadPayloadSha256',
    'reloadMatchesPersisted', 'reloadEtagMatches',
  ], `Receipt ${definition.scenarioId} persistence`);
  const persistence = receipt.persistence;
  assertCondition(Number.isInteger(persistence.version) && persistence.version >= 1
      && persistence.etagPresent === true
      && receipt.apply.payloadSha256 === persistence.persistedPayloadSha256
      && persistence.persistedPayloadSha256 === persistence.reloadPayloadSha256
      && /^[0-9a-f]{64}$/.test(persistence.persistedPayloadSha256)
      && persistence.reloadMatchesPersisted === true
      && persistence.reloadEtagMatches === true,
  `Receipt ${definition.scenarioId} persistence/reload lineage is incomplete.`);

  assertUniqueStrings(
    receipt.functionalAssertions,
    `Receipt ${definition.scenarioId} functionalAssertions`,
    /^[a-z0-9][a-z0-9.-]{0,119}$/,
  );
  assertCondition(sameOrderedStrings(
    [...receipt.functionalAssertions].sort(),
    [...definition.requiredFunctionalAssertions].sort(),
  ), `Receipt ${definition.scenarioId} functional assertions diverge from the gate matrix.`);

  const milestones = [
    'firstUsefulStatus', 'firstApplicableTerminal', 'applyCompleted',
    'runtimeFunctional', 'reloadCompleted', 'total',
  ];
  assertExactKeys(receipt.timingMs, milestones, `Receipt ${definition.scenarioId} timingMs`);
  const timingValues = milestones.map((name) => receipt.timingMs[name]);
  assertCondition(timingValues.every((value) => Number.isInteger(value) && value >= 0),
    `Receipt ${definition.scenarioId} timings must be non-negative integers.`);
  assertCondition(timingValues.slice(1).every((value, index) => value >= timingValues[index])
      && receipt.timingMs.reloadCompleted === receipt.timingMs.total,
  `Receipt ${definition.scenarioId} timing milestones are not monotonic.`);

  return {
    scenarioId: definition.scenarioId,
    firstPassFunctional: true,
    totalMs: receipt.timingMs.total,
    persistedPayloadSha256: persistence.persistedPayloadSha256,
  };
}

function validateSemanticRefinement(evidence, definition, profile) {
  assertObject(evidence, `Semantic refinement ${definition.scenarioId}`);
  assertCondition(evidence.canonicalFocalRefinement === true,
    `Semantic refinement ${definition.scenarioId} is not canonically attested.`);
  assertCondition(evidence.turnLimitSource === definition.turnLimitSource,
    `Semantic refinement ${definition.scenarioId} turn-limit source diverges from the gate matrix.`);
  assertCondition(Number.isInteger(profile.humanTurnLimit) && profile.humanTurnLimit > 0,
    `Mode ${profile.mode} must declare a humanTurnLimit for semantic refinement evidence.`);
  assertCondition(evidence.focalTurnLimit === profile.humanTurnLimit,
    `Semantic refinement ${definition.scenarioId} focal limit diverges from the gate profile.`);
  assertCondition(Array.isArray(evidence.turns) && evidence.turns.length === profile.humanTurnLimit,
    `Semantic refinement ${definition.scenarioId} must prove exactly ${profile.humanTurnLimit} turn(s).`);

  for (const [index, turn] of evidence.turns.entries()) {
    assertObject(turn, `Semantic refinement ${definition.scenarioId} turn ${index + 1}`);
    assertUniqueStrings(
      turn.blockingDiagnosticCodes,
      `Semantic refinement ${definition.scenarioId} turn ${index + 1} blockingDiagnosticCodes`,
      /^[a-z0-9][a-z0-9._:-]{0,119}$/,
    );
    assertUniqueStrings(
      turn.operationIds,
      `Semantic refinement ${definition.scenarioId} turn ${index + 1} operationIds`,
      /^[a-z0-9][a-z0-9._:-]{0,119}$/,
    );
    assertUniqueStrings(
      turn.expectedOperationIds,
      `Semantic refinement ${definition.scenarioId} turn ${index + 1} expectedOperationIds`,
      /^[a-z0-9][a-z0-9._:-]{0,119}$/,
    );
    assertCondition(turn.state === 'review' && turn.clarified === false
        && turn.operationKind === 'modify' && turn.artifactKind === 'table'
        && turn.blockingDiagnosticCodes.length === 0,
    `Semantic refinement ${definition.scenarioId} turn ${index + 1} is not an applicable table modification.`);
    for (const operationId of definition.requiredOperationIds) {
      assertCondition(turn.expectedOperationIds.includes(operationId) && turn.operationIds.includes(operationId),
        `Semantic refinement ${definition.scenarioId} is missing governed operation ${operationId}.`);
    }
    for (const field of [
      'targetComponentMatchesPraxisTable', 'targetWidgetKeyPresent',
      'selectedResourceMatchesEmployee', 'semanticDecisionIdPresent',
      'semanticDecisionPreviousIdPresent', 'semanticDecisionRefinementOfPresent',
      'activeDecisionLineagePresent', 'columnHeaderCapabilityPresent',
      'targetMatchesCompiledComponentEdit', 'compiledPatchPresent', 'assistantMessagePresent',
    ]) {
      assertCondition(turn[field] === true,
        `Semantic refinement ${definition.scenarioId} turn ${index + 1} lacks ${field}.`);
    }
    assertUniqueStrings(turn.proposedColumnFields,
      `Semantic refinement ${definition.scenarioId} turn ${index + 1} proposedColumnFields`);
    assertUniqueStrings(turn.materializedColumnFields,
      `Semantic refinement ${definition.scenarioId} turn ${index + 1} materializedColumnFields`);
    assertCondition(turn.proposedColumnFields.length > 0
        && sameOrderedStrings(turn.proposedColumnFields, turn.materializedColumnFields),
    `Semantic refinement ${definition.scenarioId} turn ${index + 1} did not preserve its columns.`);
  }

  return {
    scenarioId: definition.scenarioId,
    canonical: true,
    turns: evidence.turns.length,
    requiredOperationIds: [...definition.requiredOperationIds],
  };
}

export function validateGateReport(report, reportPath, profile) {
  assertObject(report, `Report ${reportPath}`);
  assertObject(report.stats, `Report ${reportPath} stats`);
  assertCondition(Number.isFinite(report.stats.duration) && report.stats.duration >= 0,
    `Report ${reportPath} duration is invalid.`);
  assertCondition(report.stats.expected === profile.expectedDiscovered,
    `Report ${reportPath} expected count diverges from ${profile.expectedDiscovered}.`);
  assertCondition(report.stats.unexpected === 0 && report.stats.skipped === profile.expectedSkipped
      && report.stats.flaky === 0,
  `Report ${reportPath} contains unexpected, skipped, or flaky tests.`);

  const specs = collectSpecs(report.suites);
  const titles = specs.map((spec) => spec.title);
  assertUniqueStrings(titles, `Report ${reportPath} test titles`);
  assertCondition(titles.length === profile.expectedDiscovered
      && profile.requiredPassedTests.every((title) => titles.includes(title)),
  `Report ${reportPath} does not exactly cover the canonical mode tests.`);

  const resultByTitle = new Map();
  for (const spec of specs) {
    assertCondition(spec.ok === true && Array.isArray(spec.tests) && spec.tests.length === 1,
      `Test ${spec.title} must have exactly one successful project result.`);
    const test = spec.tests[0];
    assertCondition(test.status === 'expected' && Array.isArray(test.results) && test.results.length === 1,
      `Test ${spec.title} must have exactly one expected execution.`);
    const result = test.results[0];
    assertCondition(result.status === 'passed' && result.retry === 0,
      `Test ${spec.title} must pass without retry.`);
    resultByTitle.set(spec.title, result);
  }

  const receipts = profile.receiptRequirements.map((definition) => {
    const result = resultByTitle.get(definition.testTitle);
    return validateReceipt(findAttachment(result, definition, reportPath), definition, result.retry);
  });
  const semanticRefinements = profile.semanticRefinementRequirements.map((definition) => {
    const result = resultByTitle.get(definition.testTitle);
    return validateSemanticRefinement(findAttachment(result, definition, reportPath), definition, profile);
  });

  return {
    reportPath,
    durationMs: report.stats.duration,
    discovered: titles.length,
    passed: titles.length,
    retries: 0,
    receipts,
    semanticRefinements,
  };
}

export function validateGateEvidenceSet({ reportPaths, expectedRuns, profile }) {
  assertCondition(Number.isInteger(expectedRuns) && expectedRuns > 0,
    'expectedRuns must be a positive integer.');
  assertCondition(Array.isArray(reportPaths) && reportPaths.length === expectedRuns,
    `Expected exactly ${expectedRuns} report(s), received ${reportPaths?.length ?? 0}.`);
  const resolvedPaths = reportPaths.map((path) => resolve(path));
  assertCondition(new Set(resolvedPaths).size === resolvedPaths.length, 'Report paths must be unique.');

  const runs = resolvedPaths.map((reportPath, index) => {
    const bytes = readFileSync(reportPath);
    const report = JSON.parse(bytes.toString('utf8'));
    return {
      run: index + 1,
      reportSha256: createHash('sha256').update(bytes).digest('hex'),
      ...validateGateReport(report, reportPath, profile),
    };
  });
  return {
    schemaVersion: 'praxis.page-builder-agentic-gate-evidence-summary/v1',
    mode: profile.mode,
    expectedRuns,
    passedRuns: runs.length,
    stable: true,
    totals: {
      discovered: runs.reduce((sum, run) => sum + run.discovered, 0),
      passed: runs.reduce((sum, run) => sum + run.passed, 0),
      retries: runs.reduce((sum, run) => sum + run.retries, 0),
      durationMs: runs.reduce((sum, run) => sum + run.durationMs, 0),
    },
    runs,
  };
}

function parseCliArgs(args) {
  const options = { matrixPath: defaultMatrixPath, mode: '', expectedRuns: 1, reportPaths: [] };
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === '--matrix') options.matrixPath = resolve(args[++index] || '');
    else if (argument === '--mode') options.mode = String(args[++index] || '');
    else if (argument === '--expected-runs') options.expectedRuns = Number(args[++index]);
    else if (argument === '--report') options.reportPaths.push(resolve(args[++index] || ''));
    else throw new Error(`Unknown argument: ${argument}`);
  }
  assertCondition(options.mode.length > 0, '--mode is required.');
  return options;
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  try {
    const options = parseCliArgs(process.argv.slice(2));
    const profile = resolveGateProfile(loadGateMatrix(options.matrixPath), options.mode);
    const summary = validateGateEvidenceSet({
      reportPaths: options.reportPaths,
      expectedRuns: options.expectedRuns,
      profile,
    });
    process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
  } catch (error) {
    process.stderr.write(`Gate evidence validation failed: ${error.message}\n`);
    process.exitCode = 1;
  }
}
