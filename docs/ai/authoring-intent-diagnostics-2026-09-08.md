# Terminal intent evidence — 2026-09-08

## Scope and existing evidence

Classification: `transversal`, test instrumentation and sanitized diagnostic export only.
Adherence: `ja-suportado-mal-nomeado-ou-mal-materializado`. Config already publishes
`AgenticAuthoringIntentResolutionResult.warnings` and
`llmDiagnostics.resolutionTelemetry`; Angular's authoring turn client already preserves
them under terminal `diagnostics.intentResolution`. No new runtime or public API contract.

The paid run `34171631443` recorded unknown operation/artifact failure codes but did
not export these warnings. Provider transport success did not establish semantic success.
The cause of that historical run cannot be reconstructed from this change.

Impact:

- Angular E2E: project an exact allowlist from the terminal carrier into the existing
  `praxis.page-builder.governed-state-projection/v1` diagnostic attachment before assertions.
- Config exporter: re-sanitize that projection before publishing `governed-state-turns.json`.
- Backend resolver/engine, UI runtime, endpoints, generated contracts, quickstart,
  landing, manifests, HTTP corpus and release workflows: unchanged.
- Public breaking risk: none. Older attachments remain readable, with unknown/null
  new observations; pin both updated repositories for a future live capture.

## Interpretation, not root-cause inference

`intentResolutionEvidence` contains only terminal intent presence, its existing `valid`
boolean, exact recognized warning codes, and four nullable telemetry booleans.

| Observation | What it establishes | What it does not establish |
| --- | --- | --- |
| `llm-intent-resolution-unresolved-clarification-required` | The resolver ended unresolved and requires clarification. | That the raw provider response itself was unresolved. |
| `llm-resource-selection-unconfirmed-by-ai-authored-focus` | Resource-focus confirmation rejected the selection. | A new primary intent or permission to retry/apply. |
| Both warnings | Both canonical signals survived in the terminal result. | Two provider calls, two independent failures, or an ordered event trace. |
| Provider failure warning | The resolver recorded provider failure. | The private error message, credentials or exact provider cause. |
| `resolutionTelemetry.llmResolutionAttempted` | The resolver's recorded attempt flag. | A successful semantic result or provider invocation count. |
| `resolutionTelemetry.llmResolved` | Resolution status after reconciliation. | The raw pre-policy LLM result or apply authority. |
| `valid` | The intent result's own validity flag. | Preview validity, functional success or `canApply`. |

`keywordFallbackApplied` and `semanticPolicyApplied` preserve existing booleans, not
new routing decisions. Warning absence is not proof that a phase never ran. `[]`
means a warning array was observed but contained no allowlisted code; `null` means
missing or malformed data. `terminalIntentPresent=false` means the capture ran but
found no terminal carrier. On an older attachment the exporter produces `null`, not
false. No classifier synthesizes a root cause from `unknown`, DOM state or messages.

## Privacy and validation

Both boundaries use exact known warning codes, not generic token-shaped strings.
Duplicates are removed and the warning output is bounded by the allowlist. No recursive
lookup through context hints, historical turns, candidates or provider payloads.
Raw prompts, assistant messages, request diagnostics, invocation payloads, selected
resource paths and candidate evidence are excluded. Existing unrelated projection fields
retain their previous sanitizer; this is not a claim to have audited all artifact fields.

Local proof:

- Config: 75 Node tests passed across export, provider telemetry, runner order,
  profile resolution and functional-evidence validation.
- Angular: 27 Node tests passed: 17 source-audit tests, two pre-assertion placement
  guards and eight intent-evidence tests. Source audit also passed against the checkout.
- Three cross-repository cases passed using the actual Angular projection helper
  and Config exporter together (unresolved, focus rejection, provider failure).
- Playwright `--list` loaded the changed spec and found the three canonical smoke
  tests. This is discovery only, not browser execution.
- `git diff --check` passed. No public lib build or Maven run was needed because
  executable product code and public/generated types are unchanged.

Node 22.22.2 used the existing macOS-owned dependencies read-only. Its TypeScript
module-detection warning does not fail the tests; package/workspace configuration was
not changed to suppress it. PowerShell execution was not validated (`pwsh` unavailable).
No paid provider call, new workflow dispatch, release or deploy was performed.

Angular MCP tools were inspected (`get_best_practices`, `search_documentation`,
`list_projects`, `find_examples`, `ai_tutor`, `onpush_zoneless_migration`). Applied
guidance: strict unknown/narrowing, inferred types, focused responsibility and pure
transformations. Search returned v20 references for
[isolated testing](https://angular.dev/guide/testing/services#testing-a-service) and
[HTTP testing](https://angular.dev/guide/testing/services#testing-http-services).
The tests here are pure diagnostic tests, not HTTP service proof.

## Operational handoff

The last paid gate remains failed; this instrumentation does not approve the article
laboratory or prove the intent fix against a live provider. A future deliberately
authorized smoke must pin Config with both the intent correction (PR #475) and this
exporter, plus the matching Angular capture. Inspect the sanitized warnings before
proposing further runtime changes. Do not repeat a paid run merely to debug an exporter.

Reusable skill lesson: preserve post-policy warnings separately from provider transport
telemetry, never label them as the raw LLM response, and test capture/export together.
The selected installed specialist skills have no exact canonical counterpart in this
checkout's `codex-skills/`; no speculative skill copy or local-only sync was made.
