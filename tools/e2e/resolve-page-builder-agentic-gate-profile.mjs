#!/usr/bin/env node

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
export const defaultMatrixPath = resolve(scriptDir, 'page-builder-agentic-gate-matrix.json');

function assertCondition(condition, message) {
  if (!condition) throw new Error(message);
}

function assertUniqueStrings(values, context) {
  assertCondition(Array.isArray(values) && values.length > 0, `${context} must be a non-empty array.`);
  for (const value of values) {
    assertCondition(typeof value === 'string' && value.trim() === value && value.length > 0,
      `${context} must contain non-empty trimmed strings.`);
  }
  assertCondition(new Set(values).size === values.length, `${context} must not contain duplicates.`);
}

function assertNonNegativeInteger(value, context) {
  assertCondition(Number.isInteger(value) && value >= 0, `${context} must be a non-negative integer.`);
}

function assertPositiveInteger(value, context) {
  assertCondition(Number.isInteger(value) && value > 0, `${context} must be a positive integer.`);
}

function sameOrderedStrings(left, right) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

export function validateGateMatrix(matrix) {
  assertCondition(matrix && typeof matrix === 'object' && !Array.isArray(matrix), 'Gate matrix must be an object.');
  assertCondition(matrix.schemaVersion === 'praxis.page-builder-agentic-gate-matrix/v1',
    'Unexpected gate matrix schemaVersion.');
  assertCondition(matrix.defaults && typeof matrix.defaults === 'object', 'Gate matrix defaults are missing.');
  assertNonNegativeInteger(matrix.defaults.retries, 'defaults.retries');
  assertNonNegativeInteger(matrix.defaults.streamProcessingTimeoutSeconds,
    'defaults.streamProcessingTimeoutSeconds');
  assertNonNegativeInteger(matrix.defaults.playwrightTestTimeoutMs, 'defaults.playwrightTestTimeoutMs');

  assertCondition(Array.isArray(matrix.scenarioTests) && matrix.scenarioTests.length > 0,
    'scenarioTests must be a non-empty array.');
  const scenarioIds = matrix.scenarioTests.map((entry) => entry?.scenarioId);
  assertUniqueStrings(scenarioIds, 'scenarioTests.scenarioId');
  for (const scenarioId of scenarioIds) {
    assertCondition(/^[a-z0-9-]+$/.test(scenarioId), `Invalid scenario id: ${scenarioId}`);
  }
  const scenarioById = new Map();
  const allTitles = [];
  for (const entry of matrix.scenarioTests) {
    assertUniqueStrings(entry.testTitles, `scenarioTests.${entry.scenarioId}.testTitles`);
    scenarioById.set(entry.scenarioId, entry);
    allTitles.push(...entry.testTitles);
  }
  assertCondition(new Set(allTitles).size === allTitles.length,
    'Scenario test titles must be globally unique.');

  assertCondition(matrix.modes && typeof matrix.modes === 'object' && !Array.isArray(matrix.modes),
    'Gate matrix modes are missing.');
  const modeNames = Object.keys(matrix.modes);
  assertUniqueStrings(modeNames, 'modes');
  for (const modeName of modeNames) {
    const mode = matrix.modes[modeName];
    const executionLane = mode.executionLane ?? 'live';
    assertCondition(['live', 'runtime-excellence'].includes(executionLane),
      `modes.${modeName}.executionLane must be live or runtime-excellence.`);
    if (mode.providerRequired !== undefined) {
      assertCondition(typeof mode.providerRequired === 'boolean',
        `modes.${modeName}.providerRequired must be a boolean.`);
    }
    const providerRequired = mode.providerRequired ?? executionLane === 'live';
    assertCondition(providerRequired === (executionLane === 'live'),
      `modes.${modeName}.providerRequired must match its execution lane.`);
    assertUniqueStrings(mode.scenarios, `modes.${modeName}.scenarios`);
    for (const scenarioId of mode.scenarios) {
      assertCondition(scenarioById.has(scenarioId),
        `modes.${modeName} references unknown scenario: ${scenarioId}`);
    }
    const derivedTitles = mode.scenarios.flatMap((scenarioId) => scenarioById.get(scenarioId).testTitles);
    assertUniqueStrings(mode.requiredPassedTests, `modes.${modeName}.requiredPassedTests`);
    assertCondition(sameOrderedStrings(mode.requiredPassedTests, derivedTitles),
      `modes.${modeName}.requiredPassedTests must exactly match the ordered scenarioTests projection.`);
    assertNonNegativeInteger(mode.expectedDiscovered, `modes.${modeName}.expectedDiscovered`);
    assertNonNegativeInteger(mode.minimumExecuted, `modes.${modeName}.minimumExecuted`);
    assertNonNegativeInteger(mode.expectedSkipped, `modes.${modeName}.expectedSkipped`);
    assertCondition(mode.expectedDiscovered === derivedTitles.length,
      `modes.${modeName}.expectedDiscovered must equal its derived test count.`);
    assertCondition(mode.minimumExecuted === mode.expectedDiscovered,
      `modes.${modeName}.minimumExecuted must equal expectedDiscovered.`);
    const retries = mode.retries ?? matrix.defaults.retries;
    assertNonNegativeInteger(retries, `modes.${modeName}.retries`);
    if (mode.humanTurnLimit !== undefined) {
      assertPositiveInteger(mode.humanTurnLimit, `modes.${modeName}.humanTurnLimit`);
    }
    if (mode.domainCatalogRagRequired !== undefined) {
      assertCondition(
        typeof mode.domainCatalogRagRequired === 'boolean',
        `modes.${modeName}.domainCatalogRagRequired must be a boolean.`,
      );
    }
    if (mode.domainCatalogResourceKey !== undefined) {
      assertCondition(
        typeof mode.domainCatalogResourceKey === 'string'
          && /^[a-z0-9][a-z0-9-]*(?:\.[a-z0-9][a-z0-9-]*)+$/.test(mode.domainCatalogResourceKey),
        `modes.${modeName}.domainCatalogResourceKey must be a canonical dotted resource identity.`,
      );
    }
    if (mode.apiCatalogGroup !== undefined) {
      assertCondition(
        typeof mode.apiCatalogGroup === 'string'
          && /^[a-z0-9][a-z0-9-]*$/.test(mode.apiCatalogGroup),
        `modes.${modeName}.apiCatalogGroup must be a canonical group id.`,
      );
    }
    if (mode.apiCatalogPathPrefixes !== undefined) {
      assertUniqueStrings(mode.apiCatalogPathPrefixes, `modes.${modeName}.apiCatalogPathPrefixes`);
      for (const prefix of mode.apiCatalogPathPrefixes) {
        assertCondition(/^\/api\/[a-z0-9][a-z0-9-/]*$/.test(prefix),
          `modes.${modeName}.apiCatalogPathPrefixes must contain canonical /api paths.`);
      }
    }
    if (executionLane === 'runtime-excellence') {
      assertCondition(mode.humanTurnLimit === undefined,
        `modes.${modeName} cannot declare humanTurnLimit in runtime-excellence.`);
      assertCondition(mode.domainCatalogRagRequired !== true,
        `modes.${modeName} cannot require Domain Catalog RAG in runtime-excellence.`);
      assertCondition(mode.domainCatalogResourceKey === undefined,
        `modes.${modeName} cannot declare a Domain Catalog resource in runtime-excellence.`);
      assertCondition(mode.apiCatalogGroup === undefined && mode.apiCatalogPathPrefixes === undefined,
        `modes.${modeName} cannot require API Catalog ingestion in runtime-excellence.`);
    }
  }

  const evidence = matrix.evidence;
  assertCondition(evidence && typeof evidence === 'object', 'Gate matrix evidence is missing.');
  for (const category of [
    'scenarioReceipts',
    'runtimeExcellenceReceipts',
    'governedStateProjections',
    'semanticRefinements',
  ]) {
    assertCondition(Array.isArray(evidence[category]), `evidence.${category} must be an array.`);
  }
  assertCondition(
    evidence.criticalInterceptionGuardTest
      === scenarioById.get('critical-interception-guard')?.testTitles?.[0],
    'criticalInterceptionGuardTest must match the canonical critical-interception-guard title.',
  );
  const definitions = [
    ...evidence.scenarioReceipts.map((entry) => ({ ...entry, kind: 'receipt' })),
    ...evidence.runtimeExcellenceReceipts.map((entry) => ({
      ...entry,
      kind: 'runtime-excellence-receipt',
    })),
    ...evidence.governedStateProjections.map((entry) => ({ ...entry, kind: 'projection' })),
    ...evidence.semanticRefinements.map((entry) => ({ ...entry, kind: 'semantic-refinement' })),
  ];
  assertUniqueStrings(definitions.map((entry) => entry.attachmentName), 'evidence attachment names');
  for (const definition of definitions) {
    assertCondition(scenarioById.has(definition.scenarioId),
      `Evidence ${definition.attachmentName} references unknown scenario: ${definition.scenarioId}`);
    assertCondition(scenarioById.get(definition.scenarioId).testTitles.includes(definition.testTitle),
      `Evidence ${definition.attachmentName} testTitle diverges from scenarioTests.`);
    if (definition.kind === 'receipt' || definition.kind === 'runtime-excellence-receipt') {
      assertCondition(typeof definition.archetype === 'string'
          && /^[a-z0-9][a-z0-9-]*$/.test(definition.archetype),
      `Evidence ${definition.attachmentName} archetype is invalid.`);
      assertUniqueStrings(definition.requiredFunctionalAssertions,
        `evidence.${definition.kind}.${definition.scenarioId}.requiredFunctionalAssertions`);
    }
    if (definition.kind === 'runtime-excellence-receipt') {
      assertCondition(typeof definition.planFixture === 'string'
          && /^tools\/e2e\/fixtures\/[a-z0-9][a-z0-9.-]+\.ui-composition-plan\.json$/.test(definition.planFixture),
      `Evidence ${definition.attachmentName} planFixture is invalid.`);
      assertCondition(typeof definition.expectedCompiledPayloadSha256 === 'string'
          && /^[0-9a-f]{64}$/.test(definition.expectedCompiledPayloadSha256),
      `Evidence ${definition.attachmentName} expectedCompiledPayloadSha256 is invalid.`);
      assertCondition(typeof definition.expectedPlanFixtureSha256 === 'string'
          && /^[0-9a-f]{64}$/.test(definition.expectedPlanFixtureSha256),
      `Evidence ${definition.attachmentName} expectedPlanFixtureSha256 is invalid.`);
    }
    if (definition.kind === 'semantic-refinement') {
      assertCondition(typeof definition.turnLimitSource === 'string' && definition.turnLimitSource.length > 0,
        `Evidence ${definition.attachmentName} turnLimitSource is missing.`);
      assertUniqueStrings(definition.requiredOperationIds,
        `evidence.semanticRefinements.${definition.scenarioId}.requiredOperationIds`);
    }
  }
  for (const [modeName, mode] of Object.entries(matrix.modes)) {
    const executionLane = mode.executionLane ?? 'live';
    const selectedRuntimeReceipts = evidence.runtimeExcellenceReceipts
      .filter((entry) => mode.scenarios.includes(entry.scenarioId));
    const selectedAgenticReceipts = evidence.scenarioReceipts
      .filter((entry) => mode.scenarios.includes(entry.scenarioId));
    if (executionLane === 'runtime-excellence') {
      assertCondition(selectedRuntimeReceipts.length === mode.scenarios.length,
        `modes.${modeName} must attach one runtime-excellence receipt to every scenario.`);
      assertCondition(selectedAgenticReceipts.length === 0,
        `modes.${modeName} cannot require an agentic first-pass receipt in runtime-excellence.`);
    } else {
      assertCondition(selectedRuntimeReceipts.length === 0,
        `modes.${modeName} cannot include runtime-excellence receipts in the live lane.`);
    }
  }
  return matrix;
}

export function loadGateMatrix(matrixPath = defaultMatrixPath) {
  return validateGateMatrix(JSON.parse(readFileSync(resolve(matrixPath), 'utf8')));
}

export function resolveGateProfile(matrix, modeName) {
  validateGateMatrix(matrix);
  const mode = matrix.modes[modeName];
  assertCondition(mode, `Unknown gate mode: ${modeName}`);
  return {
    schemaVersion: 'praxis.page-builder-agentic-gate-profile/v1',
    matrixSchemaVersion: matrix.schemaVersion,
    mode: modeName,
    description: mode.description,
    executionLane: mode.executionLane ?? 'live',
    providerRequired: mode.providerRequired ?? (mode.executionLane ?? 'live') === 'live',
    scenarios: [...mode.scenarios],
    expectedDiscovered: mode.expectedDiscovered,
    minimumExecuted: mode.minimumExecuted,
    expectedSkipped: mode.expectedSkipped,
    retries: mode.retries ?? matrix.defaults.retries,
    playwrightTestTimeoutMs: matrix.defaults.playwrightTestTimeoutMs,
    streamProcessingTimeoutSeconds: matrix.defaults.streamProcessingTimeoutSeconds,
    humanTurnLimit: mode.humanTurnLimit ?? null,
    domainCatalogRagRequired: mode.domainCatalogRagRequired ?? false,
    domainCatalogResourceKey: mode.domainCatalogResourceKey ?? null,
    apiCatalogGroup: mode.apiCatalogGroup ?? null,
    apiCatalogPathPrefixes: [...(mode.apiCatalogPathPrefixes ?? [])],
    requiredPassedTests: [...mode.requiredPassedTests],
    receiptRequirements: matrix.evidence.scenarioReceipts
      .filter((entry) => mode.scenarios.includes(entry.scenarioId)),
    runtimeExcellenceReceiptRequirements: matrix.evidence.runtimeExcellenceReceipts
      .filter((entry) => mode.scenarios.includes(entry.scenarioId)),
    diagnosticProjectionRequirements: matrix.evidence.governedStateProjections
      .filter((entry) => mode.scenarios.includes(entry.scenarioId)),
    semanticRefinementRequirements: matrix.evidence.semanticRefinements
      .filter((entry) => mode.scenarios.includes(entry.scenarioId)),
  };
}

function parseCliArgs(args) {
  const options = { matrixPath: defaultMatrixPath, mode: '' };
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] === '--matrix') options.matrixPath = resolve(args[++index] || '');
    else if (args[index] === '--mode') options.mode = String(args[++index] || '');
    else throw new Error(`Unknown argument: ${args[index]}`);
  }
  return options;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const options = parseCliArgs(process.argv.slice(2));
    const matrix = loadGateMatrix(options.matrixPath);
    const output = options.mode
      ? resolveGateProfile(matrix, options.mode)
      : {
          schemaVersion: 'praxis.page-builder-agentic-gate-matrix-validation/v1',
          valid: true,
          modes: Object.keys(matrix.modes),
          scenarioCount: matrix.scenarioTests.length,
        };
    process.stdout.write(`${JSON.stringify(output, null, 2)}\n`);
  } catch (error) {
    process.stderr.write(`Gate matrix validation failed: ${error.message}\n`);
    process.exitCode = 1;
  }
}
