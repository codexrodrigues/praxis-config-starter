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
  }

  const evidence = matrix.evidence;
  assertCondition(evidence && typeof evidence === 'object', 'Gate matrix evidence is missing.');
  for (const category of ['scenarioReceipts', 'governedStateProjections', 'semanticRefinements']) {
    assertCondition(Array.isArray(evidence[category]), `evidence.${category} must be an array.`);
  }
  assertCondition(
    evidence.criticalInterceptionGuardTest
      === scenarioById.get('critical-interception-guard')?.testTitles?.[0],
    'criticalInterceptionGuardTest must match the canonical critical-interception-guard title.',
  );
  const definitions = [
    ...evidence.scenarioReceipts.map((entry) => ({ ...entry, kind: 'receipt' })),
    ...evidence.governedStateProjections.map((entry) => ({ ...entry, kind: 'projection' })),
    ...evidence.semanticRefinements.map((entry) => ({ ...entry, kind: 'semantic-refinement' })),
  ];
  assertUniqueStrings(definitions.map((entry) => entry.attachmentName), 'evidence attachment names');
  for (const definition of definitions) {
    assertCondition(scenarioById.has(definition.scenarioId),
      `Evidence ${definition.attachmentName} references unknown scenario: ${definition.scenarioId}`);
    assertCondition(scenarioById.get(definition.scenarioId).testTitles.includes(definition.testTitle),
      `Evidence ${definition.attachmentName} testTitle diverges from scenarioTests.`);
    if (definition.kind === 'receipt') {
      assertCondition(typeof definition.archetype === 'string'
          && /^[a-z0-9][a-z0-9-]*$/.test(definition.archetype),
      `Evidence ${definition.attachmentName} archetype is invalid.`);
      assertUniqueStrings(definition.requiredFunctionalAssertions,
        `evidence.scenarioReceipts.${definition.scenarioId}.requiredFunctionalAssertions`);
    }
    if (definition.kind === 'semantic-refinement') {
      assertCondition(typeof definition.turnLimitSource === 'string' && definition.turnLimitSource.length > 0,
        `Evidence ${definition.attachmentName} turnLimitSource is missing.`);
      assertUniqueStrings(definition.requiredOperationIds,
        `evidence.semanticRefinements.${definition.scenarioId}.requiredOperationIds`);
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
