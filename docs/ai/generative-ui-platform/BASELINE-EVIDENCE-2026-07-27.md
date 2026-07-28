# Baseline Evidence — 2026-07-27

Status: accepted investigation baseline
Machine-readable companion: [`baseline.snapshot.json`](baseline.snapshot.json)

## How to use this baseline

Evidence `B-001` through `B-014` is accepted for subsequent phases. Do not repeat the full audit.
Revalidate an item only if:

1. its source file changed after the recorded repository SHA;
2. a focused test or generated artifact contradicts it; or
3. this document marks it as a hypothesis.

The baseline was captured from a workspace with pre-existing uncommitted changes. Repository HEADs,
dirty counts and generated-artifact hashes are recorded in `baseline.snapshot.json`. Facts about
`praxis-config-starter` describe the inspected working tree; facts about the table owner files were
not overlapped by the six pre-existing Angular changes observed at capture time.

`baseline.snapshot.json` also records SHA-256 for every working-tree source used directly by the
critical findings. This matters because `AgenticAuthoringPreviewService.java`,
`AgenticAuthoringTurnEngine.java` and `AgenticAuthoringToolRegistry.java` were already modified
relative to `HEAD` when inspected. A future session must compare those file hashes, not only the
repository commit, before deciding that `B-008` or `B-011` is unchanged.

Minimal integrity check from each recorded repository root:

```sh
shasum -a 256 <recorded-source-path>
```

If a hash differs, inspect the focused diff and revalidate only the evidence IDs that cite that
source.

## Executive conclusion

The problem is not absence of content. The table alone has about 538 KB of generated AI-facing
content. The problem is that knowledge is split across capabilities, authoring manifests, backend
execution registries, runtime behavior, observations and tests without a complete, executable
conformance relation.

The failure therefore cannot be solved reliably by a larger prompt, a different embedding model or
a more capable LLM alone. The platform must make dependency closure, success conditions and outcome
proof deterministic.

## Registry and table inventory

Run the registry revalidation commands in this section from the `praxis-ui-angular` repository
root. Paths in later sections state their owning repository explicitly.

### B-001 — Table capability surface

Confirmed: `praxis-table` exposes 500 capability paths in the generated aggregate ingestion corpus.

Source:

- workspace path: `praxis-ui-angular/dist/praxis-component-registry-ingestion.json`
- generated at: `2026-07-26T23:30:30.363Z`
- SHA-256: `7c88964c45ad530856d80d4f9ce6ac3b1daf6ee30c5ca55be81f4e36e8c40cd6`

Minimal revalidation:

```sh
jq '.components["praxis-table"].capabilities | length' \
  dist/praxis-component-registry-ingestion.json
```

### B-002 — Dependency surface

Confirmed: 340 table capabilities declare `dependsOn`; they refer to 79 distinct dependency paths.

This is not yet proof that authoring operations close those dependencies.

Minimal revalidation:

```sh
jq '[.components["praxis-table"].capabilities[] | select(.dependsOn != null and .dependsOn != "")] | length' \
  dist/praxis-component-registry-ingestion.json
jq '[.components["praxis-table"].capabilities[] | select(.dependsOn != null and .dependsOn != "") | .dependsOn] | unique | length' \
  dist/praxis-component-registry-ingestion.json
```

### B-003 — Declared authoring and corpus inventory

Confirmed for `praxis-table`:

| Artifact | Count |
| --- | ---: |
| authoring operations | 68 |
| validators declared by the manifest | 23 |
| editable targets | 32 |
| examples | 52 |
| chunks | 168 |
| `authoring_manifest` chunks | 106 |
| capability chunks | 12 |
| context-pack chunks | 14 |
| recipe chunks | 35 |
| summary chunks | 1 |

Across the aggregate registry, 105 entries exist. Ninety-five carry a manifest projection, but
many are projections of the same family manifest. They reduce to 20 unique manifest families and
289 canonical `(manifest family, operation)` definitions. Those definitions use 278 globally
distinct `operationId` strings because some semantic operation IDs are shared by more than one
family. Never deduplicate executable ownership by `operationId` alone. Ten registry entries have no
manifest projection; this is not automatically a defect because runtime helpers and probes may be
intentionally non-authorable.

A naive sum of operations across all 95 manifest projections produces 889, not 289. The extra 600
are projection duplicates: `praxis-dynamic-fields` alone is present in one owner entry and 75 child
entries, each repeating its eight family operations. Deduplicate source manifests by at least
`(componentId, manifestVersion, ownerPackage)` and confirm the owner entry whose key equals
`authoringManifest.componentId`.

The aggregate also contains 76 entries with manifest-profile data, 93 repeated profile projection
objects and only 18 distinct `profileId` values. `component-docs.json` contains 101 component-doc
entries; ingestion adds four aggregate/governance/runtime identities. Therefore, 105 is the number
of ingestion entries, not a claim that the platform has 105 independent runtime components.

Minimal revalidation:

```sh
jq '.components["praxis-table"] | {
  capabilities: (.capabilities | length),
  operations: (.authoringManifest.operations | length),
  validators: (.authoringManifest.validators | length),
  targets: (.authoringManifest.editableTargets | length),
  examples: (.authoringManifest.examples | length),
  chunks: (.chunks | length)
}' dist/praxis-component-registry-ingestion.json
jq '{
  entries: (.components | length),
  withManifest: ([.components[] | select(.authoringManifest != null)] | length),
  projectedOperations: ([.components[] | .authoringManifest.operations[]?] | length),
  ownerFamilies: ([.components | to_entries[] |
    select(.value.authoringManifest != null and .key == .value.authoringManifest.componentId)] |
    length),
  ownerOperations: ([.components | to_entries[] |
    select(.value.authoringManifest != null and .key == .value.authoringManifest.componentId) |
    .value.authoringManifest.operations[]] | length),
  distinctOperationIds: ([.components | to_entries[] |
    select(.value.authoringManifest != null and .key == .value.authoringManifest.componentId) |
    .value.authoringManifest.operations[].operationId] | unique | length),
  distinctProfileIds: ([.components[] | .authoringManifestProfiles[]?.profileId] | unique | length)
}' dist/praxis-component-registry-ingestion.json
jq '.components | length' tools/ai-registry/component-docs.json
```

## The `rowAction.add` failure chain

### B-004 — Capability dependency is already known

Confirmed: the capability entry `actions.row.actions[]` declares
`dependsOn: actions.row.enabled`.

Source: `praxis-ui-angular/dist/praxis-component-registry-ingestion.json`.

Adherence class: `ja-suportado-mal-nomeado-ou-mal-materializado`.

### B-005 — Executable operation does not materialize the dependency

Confirmed: `rowAction.add` has only an `append-unique` effect on
`actions.row.actions[]`. Its affected paths and validator do not enable or assert
`actions.row.enabled`.

Source:

```text
praxis-ui-angular/projects/praxis-table/src/lib/ai/
  praxis-table-authoring-manifest.ts:1829
```

Adherence class for the immediate bug: `ja-suportado-mal-nomeado-ou-mal-materializado`.

### B-006 — Runtime requires the missing state

Confirmed: the `_actions` column is appended to displayed columns only when
`config.actions?.row?.enabled` is true.

Source:

```text
praxis-ui-angular/projects/praxis-table/src/lib/praxis-table.ts:12370
```

Therefore, adding a row action while leaving `enabled=false` produces a valid-looking config with
no visible button.

### B-007 — Focused test masks the state transition

Confirmed: the focal adapter fixture already initializes row actions with `enabled: true` before
calling `rowAction.add`.

Source:

```text
praxis-ui-angular/projects/praxis-table/src/lib/ai/table-ai.adapter.spec.ts:2310
```

The test proves preservation of an already-enabled configuration, not the minimal disabled state
that failed in the browser.

### B-008 — Preview explanation is not outcome proof

Confirmed: the generic preview message is synthesized from compiled operation IDs. It can say that
a preview was prepared without verifying visible materialization.

Source:

```text
praxis-config-starter/src/main/java/org/praxisplatform/config/ai/authoring/
  AgenticAuthoringPreviewService.java:668
```

The current browser metric named `explanationProposedConfigFidelity` checks only that an assistant
message and non-empty proposed config exist:

```text
praxis-ui-angular/projects/praxis-page-builder/test-dev/e2e/
  page-builder-table-human-authoring.playwright.spec.ts:245
```

Adherence class: `ja-suportado-mal-nomeado-ou-mal-materializado` for the metric; a reusable mapping
from operation success to observed outcome remains a candidate `lacuna-real-de-contrato` pending
Phase 1.

## Coverage limits

### B-009 — Current 100% report is structural

Confirmed: `praxis-table-authoring-coverage.json` reports 68 operations, 68 cards, static recall
`1`, no failures and 180 pairwise cases. Its pairwise dimensions are layout, renderer, size, shape
and alignment. They are not the table's actual dependency graph.

Source:

```text
praxis-ui-angular/dist/ai-registry/praxis-table-authoring-coverage.json
SHA-256 175d7fb9f449808e772c87a625ea47a7bfa5643ed66f98c795486503eb43058d
```

The production-like human table E2E contains eight turns covering header, conditional chip and
photo/code composition variations. It does not cover row actions, bulk actions, expansion, export,
toolbar, selection or the full manifest.

Conclusion: current coverage proves declaration/card/schema support and a small human journey. It
does not prove functional completeness of the table.

## Contract and retrieval limits

### B-010 — Manifest operation contract lacks explicit outcome semantics

Confirmed: `ManifestOperation` currently represents target, input schema, effects, validators,
affected paths, submission impact and `preconditions`. It does not define reusable
`postconditions`/`ensures`, global invariants, dependency closure or operation-specific outcome
observation.

Source:

```text
praxis-ui-angular/projects/praxis-core/src/lib/ai/authoring-manifest.types.ts:92
```

This does not authorize adding fields. Phase 1 must inventory whether existing effects, validators,
capability `dependsOn`, runtime affordances and observation contracts can express each requirement
before classifying a real contract gap.

### B-011 — Retrieval is bounded and separates evidence kinds

Confirmed:

- the default retrieval limit is 5;
- pre-intent component authoring retrieval requests at most 12 chunks;
- that request fixes `chunkKind=authoring_manifest`;
- capability dependencies can therefore live outside the selected evidence kind;
- exact `getManifestSlice` retrieval still returns the incomplete `rowAction.add` operation.

Sources:

```text
praxis-config-starter/src/main/java/org/praxisplatform/config/service/
  ContextRetrievalService.java:49
praxis-config-starter/src/main/java/org/praxisplatform/config/ai/authoring/
  AgenticAuthoringTurnEngine.java:4813
  AgenticAuthoringToolRegistry.java:1020
```

Conclusion: retrieval needs a coherent operation evidence bundle, but perfect retrieval alone
would not correct an incomplete executable contract.

## OpenAI and knowledge infrastructure

### B-012 — Hosted Skills were not attached to the observed local process

Confirmed at capture time:

- `SpringAiOpenAiService` adds hosted Skills only when configured references are non-empty;
- seven authoring Skill slots are declared through environment-backed properties;
- none of the corresponding `PRAXIS_AI_OPENAI_SKILL_*_ID` keys was present in the running local
  quickstart process;
- no alternative project configuration assigning those IDs was found in the inspected repos.

Sources:

```text
praxis-config-starter/src/main/java/org/praxisplatform/config/service/
  SpringAiOpenAiService.java:476
praxis-config-starter/src/main/resources/praxis-config-defaults.properties:30
```

This finding is local-process evidence, not a claim about every deployed environment.

Skills remain appropriate for reusable procedure and validation guidance. They are not the source
of live component truth or deterministic invariants. OpenAI documents that the model decides
whether to invoke an attached Skill from its metadata and then reads `SKILL.md`:
[OpenAI Skills](https://developers.openai.com/api/docs/guides/tools-skills).

### B-013 — Current embedding choice is already a strong baseline

Confirmed: the default model is `text-embedding-3-large`; the vector schema uses 3072 dimensions.

Sources:

```text
praxis-config-starter/src/main/resources/praxis-config-defaults.properties:26
praxis-config-starter/src/main/resources/db/migration/
  V10__upgrade_embedding_dimensions.sql:1
```

Whether it is the best Praxis embedding must be answered by a domain-specific retrieval eval. A
model switch cannot repair missing deterministic effects or success conditions.

File Search can hold release-scoped documentation and examples, while client-executed Tool Search
can discover project/tenant-specific tools from the canonical Praxis registry:

- [OpenAI File Search](https://developers.openai.com/api/docs/guides/tools-file-search)
- [OpenAI Tool Search](https://developers.openai.com/api/docs/guides/tools-tool-search)

No provider projection may replace `ai_registry`, metadata, manifests or runtime owners.

### B-014 — Domain-to-component foundations already exist

Confirmed by the [Machine-First Semantic IR RFC](../../2026-07-machine-first-semantic-ir-rfc.md):

- governed Semantic IR and provenance;
- progressive tools and retrieval profiles;
- domain concept, capability and binding inspection;
- component discovery by capability;
- bounded component-selection evidence;
- preservation of that evidence in `UiCompositionPlan` diagnostics;
- fail-closed materialization rules.

Runtime component observation also already exists as an untrusted evidence boundary in the public
AI contract and registry. It observes current component context; it is not yet a generic proof that
a requested authoring operation achieved its post-materialization outcome.

Conclusion: do not recreate Semantic IR, component selection, RAG or runtime observation. Extend
their continuity into executable operation certification.

## What is confirmed versus still open

Confirmed:

- the table knowledge volume is substantial;
- the observed LLM selected the correct semantic operation;
- the operation's declared effect was insufficient for the runtime;
- current coverage overstates functional assurance;
- knowledge retrieval and Skills alone cannot enforce a missing invariant;
- the platform already has most domain-to-component foundations.

Not yet confirmed:

- which of the 500 table paths should be directly authorable;
- whether all 68 operations have complete dependency closure;
- which missing outcome semantics can be derived from existing sources;
- which gaps require a minimal extension to an existing canonical contract;
- the real intent-selection and end-to-end success rates for all component families;
- the best retrieval/embedding configuration on a representative Praxis dataset;
- readiness of all 20 manifest families and all 105 registry entries.

These open questions are Phase 1 inputs, not permission to redesign the platform from scratch.
