import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const schemaVersion = 'praxis-agentic-authoring-provider-telemetry.v1';
const counters = ['latencyMs', 'inputTokens', 'outputTokens', 'cacheReadInputTokens', 'cacheWriteInputTokens', 'totalTokens'];
const code = value => typeof value === 'string' && /^[a-zA-Z0-9._:-]{1,160}$/.test(value) ? value : null;
const count = value => Number.isSafeInteger(value) && value >= 0 ? value : null;

function sanitize(value) {
  if (value === null) return null; // A turn without terminal telemetry is unknown, not zero calls.
  if (!value || value.schemaVersion !== schemaVersion || !Array.isArray(value.providerInvocations)
      || value.providerInvocations.length > 12) throw new Error('Invalid provider telemetry attachment.');
  return {
    schemaVersion,
    invocationCount: count(value.invocationCount),
    truncated: typeof value.truncated === 'boolean' ? value.truncated : null,
    providerInvocations: value.providerInvocations.map(invocation => {
      if (!invocation || typeof invocation !== 'object') throw new Error('Invalid provider invocation.');
      return {
        ...Object.fromEntries(['phase', 'provider', 'model', 'transport', 'status', 'failureKind', 'responseId', 'finishReason'].map(key => [key, code(invocation[key])])),
        attempt: count(invocation.attempt),
        ...Object.fromEntries(counters.map(key => [key, count(invocation[key])])),
      };
    }),
  };
}

// Derived operational evidence only: never copy test titles, prompts, rows, errors or arbitrary attachments.
export function exportProviderTelemetry(report) {
  const turns = [];
  let testOrdinal = 0;
  function visit(suite) {
    for (const spec of suite.specs ?? []) for (const test of spec.tests ?? []) {
      testOrdinal += 1;
      for (const [resultOrdinal, result] of (test.results ?? []).entries()) {
        for (const attachment of result.attachments ?? []) {
          const match = /^provider-telemetry-turn-([1-9][0-9]*)\.json$/.exec(attachment.name ?? '');
          if (!match) continue;
          if (attachment.contentType !== 'application/json' || typeof attachment.body !== 'string' || attachment.path
              || attachment.body.length > 100_000) throw new Error('Provider telemetry must be bounded inline JSON.');
          let value;
          try { value = JSON.parse(Buffer.from(attachment.body, 'base64').toString('utf8')); }
          catch { throw new Error('Invalid provider telemetry JSON.'); }
          turns.push({ testOrdinal, resultOrdinal, retry: count(result.retry), turnNumber: count(Number(match[1])), telemetry: sanitize(value) });
        }
      }
    }
    for (const child of suite.suites ?? []) visit(child);
  }
  visit(report);
  return { scope: 'authoring-turns-only', turns };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const args = process.argv.slice(2);
    if (args.length !== 4 || args[0] !== '--report' || args[2] !== '--out') throw new Error('Expected --report and --out.');
    const evidence = exportProviderTelemetry(JSON.parse(readFileSync(args[1], 'utf8')));
    writeFileSync(args[3], JSON.stringify(evidence, null, 2) + '\n');
    console.log(`Exported provider telemetry for ${evidence.turns.length} observed turns.`);
  } catch {
    console.error('Provider telemetry export failed; no private report content was published.');
    process.exitCode = 1;
  }
}
