import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const schemaVersion = 'praxis.page-builder.governed-state-projection/v1';
const code = value => typeof value === 'string' && /^[a-zA-Z0-9._:-]{1,160}$/.test(value) ? value : null;
const count = value => Number.isSafeInteger(value) && value >= 0 ? value : null;
const boolean = value => typeof value === 'boolean' ? value : null;
const codes = (value, limit) => Array.isArray(value)
  ? [...new Set(value.map(code).filter(value => value !== null))].slice(0, limit) : null;
const fields = (value, names, sanitize) => Object.fromEntries(names.map(name => [name, sanitize(value?.[name])]));

function sanitize(value) {
  if (!value || value.schemaVersion !== schemaVersion
      || value.scenarioId !== 'live-resource-workspace-command') {
    throw new Error('Invalid governed state projection.');
  }
  // Reuse the existing diagnostic projection. This is evidence, never apply authority.
  // Reasons can contain free text: do not export them, even when they resemble a token.
  return {
    sourceSchemaVersion: schemaVersion,
    scenarioId: value.scenarioId,
    observedDisposition: fields(value.observedDisposition, ['testObservedState', 'controllerState', 'domState'], code),
    decisionDiagnostics: {
      status: code(value.decisionDiagnostics?.status),
      reason: null,
      ...fields(value.decisionDiagnostics, ['decisionValid', 'requiresReview'], boolean),
    },
    preview: fields(value.preview, ['present', 'valid'], boolean),
    applyEligibility: fields(value.applyEligibility, ['controllerCanApply', 'persistEnabled'], boolean),
    blockingDiagnosticCodes: codes(value.blockingDiagnosticCodes, 24),
    quickReplyIds: codes(value.quickReplyIds, 12),
    governedRepairActionIds: codes(value.governedRepairActionIds, 12),
    canonicalActionPresent: boolean(value.canonicalActionPresent),
    applyLineage: {
      status: code(value.applyLineage?.status),
      reason: null,
      patchAuthority: code(value.applyLineage?.patchAuthority),
      terminalReferencePresent: boolean(value.applyLineage?.terminalReferencePresent),
    },
    execution: fields(value.execution, ['turnCount', 'attemptCount', 'retryCount'], count),
  };
}

export function exportGovernedState(report) {
  const turns = [];
  let testOrdinal = 0;
  function visit(suite) {
    for (const spec of suite.specs ?? []) for (const test of spec.tests ?? []) {
      testOrdinal += 1;
      for (const [resultOrdinal, result] of (test.results ?? []).entries()) {
        const seen = new Set();
        for (const attachment of result.attachments ?? []) {
          const match = /^governed-state-turn-([1-9][0-9]*)\.json$/.exec(attachment.name ?? '');
          if (!match) continue;
          const turnNumber = count(Number(match[1]));
          if (turnNumber === null || seen.has(turnNumber)) throw new Error('Invalid or duplicate turn number.');
          seen.add(turnNumber);
          if (attachment.contentType !== 'application/json' || typeof attachment.body !== 'string'
              || attachment.path || attachment.body.length > 100_000) {
            throw new Error('Governed state must be bounded inline JSON.');
          }
          let value;
          try { value = JSON.parse(Buffer.from(attachment.body, 'base64').toString('utf8')); }
          catch { throw new Error('Invalid governed state JSON.'); }
          turns.push({
            testOrdinal, resultOrdinal, retry: count(result.retry), turnNumber,
            resultStatus: ['passed', 'failed', 'timedOut', 'skipped', 'interrupted'].includes(result.status) ? result.status : null,
            projection: sanitize(value),
          });
        }
      }
    }
    for (const child of suite.suites ?? []) visit(child);
  }
  visit(report);
  return { scope: 'observed-mission-authoring-turns-only', evidenceRole: 'diagnostic-only', turns };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const args = process.argv.slice(2);
    if (args.length !== 4 || args[0] !== '--report' || args[2] !== '--out') throw new Error('Expected --report and --out.');
    const evidence = exportGovernedState(JSON.parse(readFileSync(args[1], 'utf8')));
    writeFileSync(args[3], JSON.stringify(evidence, null, 2) + '\n');
    console.log(`Exported governed state for ${evidence.turns.length} observed turns.`);
  } catch {
    console.error('Governed state export failed; no private report content was published.');
    process.exitCode = 1;
  }
}
