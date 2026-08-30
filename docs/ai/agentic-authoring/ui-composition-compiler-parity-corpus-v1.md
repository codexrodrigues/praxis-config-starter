# UiCompositionPlan compiler parity corpus v1

Status: implemented as the focal cross-repository gate for
[`praxis-config-starter#357`](https://github.com/codexrodrigues/praxis-config-starter/issues/357).

## Decision and ownership

`praxis-config-starter` owns one neutral, versioned corpus for deterministic
`UiCompositionPlan` compilation. Java and TypeScript consume the same cases; neither runner owns a
second copy of expected output.

- schema:
  `docs/ai/agentic-authoring/contracts/ui-composition-compiler-parity-corpus.v1.schema.json`;
- Java peer report schema:
  `docs/ai/agentic-authoring/contracts/ui-composition-golden-report.v1.schema.json`;
- cases and frozen target profiles:
  `docs/ai/agentic-authoring/proofs/ui-composition-compiler-parity-corpus.v1.json`;
- Java runner:
  `UiCompositionGoldenCorpusRunner` under test scope;
- TypeScript runner:
  `praxis-ui-angular/tools/ai-registry/ui-composition-golden-corpus-runner.ts`.

The corpus is not a registry, runtime source of truth, template store or business-rule catalog. Its
target profile pins claims, release-train identity and hashed owner-repository evidence. Components,
ports and actions are discovered from the official Angular providers at execution time; the corpus
does not maintain a second component catalog.

## Adherence inventory

The implementation audited the existing semantic grounding corpus, template revision contract,
Java and TypeScript compilers, Page Builder preflight, Corte A.5 certification matrix and registry
snapshots before adding a file.

| Need | Existing evidence | Classification | Decision |
| --- | --- | --- | --- |
| semantic retrieval examples | semantic grounding corpus v0.1 | `ja-suportado-so-ux` | Keep it unchanged; it tests retrieval/grounding rather than deterministic page projection. |
| exact template identity | `registryKey`, `version`, `ETag` and canonical `configSha256` | `ja-suportado-mal-nomeado-ou-mal-materializado` | Reuse the identities verbatim in template and target-drift cases. |
| Java/TypeScript output comparison | two pure compilers and owner tests | `suportado-parcialmente` | Project both outputs through one documented canonicalizer and compare the same SHA-256. |
| concrete target readiness | Page Builder preflight plus Corte A.5 evidence | `suportado-parcialmente` | Run it as a separate Angular-owned phase after compiler parity. |
| shared neutral cases and expected diagnostic identities | no versioned cross-language artifact existed | `lacuna-real-de-contrato` | Add the schema and corpus above; do not add a DTO or endpoint. |

This is an adjacent deterministic corpus, not an extension of the semantic grounding corpus. Mixing
retrieval ranking with byte-stable compiler output would make both gates less precise.

## Two independent phases

### Compiler parity

Both engines validate or resolve the same plan, compile it, and compare:

- phase and pass/warning/block outcome;
- canonical projection SHA-256;
- stable diagnostic identity (`code`, `path`, `severity`, `provenance`).

Corpus v1.6 carries 20 cases. It adds positive and fail-closed parity for a `global-action`
continuation source, including the rule that only `actionId` identifies the source and runtime
dispatch supplies the payload. Its adversarial master-detail coverage mirrors the TypeScript owner:
unknown preset/slot, role-slot conflict, missing or ambiguous master, missing detail, preset
conflict, slot cardinality, widget-role fallback and detail-slot fallback. The Java builder identity
is pinned as `config-ui-composition-plan-compiler@1.3.0`.

Known Java/TypeScript spelling or path differences are explicit per expected diagnostic. The
runners may not normalize an unexpected divergence into success. Reports publish byte-exact
`corpusSha256`, `schemaSha256`, `reportSchemaSha256` and compiler identity (including implementation
hash). The corpus-owned Java receipt fixes an ordered eleven-source execution closure: runner/path
helper, compiler and nested classes, compiled-page validator, template resolver, canonical hash
service, template models/builders and the mocked template-service type boundary. Every source Git
blob and emitted class artifact is pinned. The runner also derives a sorted Praxis-only dependency
graph from JVM constant-pool class references of the executed closure; the mocked service is an
explicit leaf because its implementation is not executed. Every referenced Praxis class must be
covered by a declared artifact, and the closure hash binds both artifacts and graph.

Both runners compare that external receipt with current source/artifacts, and the peer handshake
reconstructs Java diagnostics directly from `engineCodes.java`, `pathByEngine.java`, severity and
provenance instead of trusting the peer's `canonicalDiagnostics` field. Hashing source
bytes preserves semantic `null` values instead of silently erasing them in a parser projection. The
TypeScript peer first validates the report with the strict report schema, compares its exact byte
hash with `reportSchemaSha256`, and retains manual identity checks as defense-in-depth. It then
validates the full Java closure against reported hashes before comparing every
case's phase, outcome, projection hash, canonical diagnostics, pass bit and failure list.

Projection canonicalization sorts object keys recursively, preserves array order and semantic
`null`, serializes UTF-8 JSON and hashes with SHA-256. Template configuration continues to use the
existing `CanonicalJsonHashService` convention, which omits null object properties. These two hash
domains are deliberately distinct and named in the corpus.

### Target attestation

Only after compiler parity passes does the Angular runner attest the materialization against the
selected frozen target profile. Each case publishes `profileId`, declared and compiled-derived
requirements, and the identities used for the decision. It checks:

- exact equality between declared and compiled-derived components, structured nested ports,
  capabilities and global actions, so an
  omitted or extra requirement blocks before target lookup;
- target component and semantic port availability from the injected registry;
- capability coordinates as real module exports or injectable tokens, including provider
  resolvability; a name in the corpus is never proof by itself;
- global actions only when `GlobalActionService.getReadiness(...)` proves both the handler and its
  Core-owned operational provider/runtime evidence without executing the side effect; `has()` or a
  present injector token alone is not readiness. The positive `trackEvent` case separately executes
  the real official analytics adapter and records its observation from `TelemetryService.events$`;
- exact template revision (`registryKey`, `version`, `ETag`, `configSha256`) copied from the
  materialization resolved by the template resolver, never from the target profile;
- Corte A.5 baseline `a4ccfef5720c7dc616a90c2e1b10d5b79055b1be` and the SHA-256 of every
  exact `commit:path` Git blob, rather than the current worktree file;
- the real `preflightUiCompositionPlan(...)` against a `ComponentMetadataRegistry` bootstrapped by
  `providePraxisTableMetadata()`, `providePraxisListMetadata()`, `providePraxisTabsMetadata()` and
  `providePraxisRelatedResourceOutletMetadata()` in an `EnvironmentInjector`;
- action metadata from `providePraxisGlobalActionCatalog()` and executable handlers from the
  injector. Catalog metadata alone is intentionally insufficient.

`registryFingerprint` is the canonical SHA-256 of the runtime provider projection: sorted component
ids, governed port contracts and sorted action-readiness projections returned by that injector. It is not
a digest of the target-profile JSON. The report distinguishes the ancestral certification baseline
from the executed `runtimeRevision`. A target-owned `runtimeExecutionReceipt` independently pins
the complete 15-package transitive `@praxisui/*` closure reached from Core, Table, List, Tabs and
Page Builder. It records every package source Git tree, copied directory hash, package manifest,
loadable ESM export, declared/emitted Praxis edge and the Git blobs plus byte hashes of the root
build inputs. The gate compares those content identities before importing any package and loads all
16 exports in a fresh process; merely recalculating a hash in the report is not acceptance.
`producerRevision` is resolvable provenance, not the decision identity, so equivalent trees and
build inputs remain valid after squash/rebase while any source, toolchain or transitive byte drift
blocks.

Nested targets are resolved from the materialized owner definition and `NestedPortCatalogService`;
the terminal `componentType` is checked as part of the structured path but is never accepted as the
source of the child component identity.

Only a real compiler `block` may produce target `skipped`, and then target diagnostics plus all four
requirement collections must be empty with no template revision. Compiler warnings are fully
attested. Target failure does not rewrite compiler parity. Cases intentionally prove that an identical,
valid projection can still be blocked because the target train lacks a component, port,
capability/action or pinned template revision.

The action case may carry `input.targetProbe.dispatchPayload`. It is target-only evidence and never
changes compiler projection. The corpus schema rejects extra probe fields and probes without a
`dispatchPayload`.

The owner regressions additionally prove former bypasses stay closed: removing a declared action,
emptying essential capabilities, freely marking a valid case `skipped`, accepting `surface.open`
without an operational `GLOBAL_SURFACE_SERVICE`, trusting a false nested `componentType`, declaring a fictitious capability,
declaring template version `999`, altering a loaded bundle after receipt publication, or drifting
peer diagnostics/results/compiler closure/report shape. Invalid or malformed corpus/schema input produces a
persisted, readable fail-closed report instead of leaking a `TypeError` in both languages.

## Deliberate divergence proof

The TypeScript owner test mutates one computed projection after compilation. The Java owner test
changes the expected digest only in its in-memory corpus. In both paths the runner must report
`projection-sha-mismatch` and fail before an apply or publication step. This proves the gate is not
accepting whichever output the current compiler happens to produce.

The first execution of the shared corpus exposed a real drift: the Java neutral auto-layout used a
table/list name heuristic and did not materialize the TypeScript master-detail blueprint. The
canonical Java compiler was aligned to the TypeScript Page Builder owner behavior; no runner-side
normalization was added.

## Local gates

From `praxis-config-starter`:

```bash
mvn -q \
  -Dtest=UiCompositionGoldenCorpusRunnerTest,AgenticAuthoringUiCompositionPlanCompilerTest,AgenticAuthoringUiCompositionTemplateResolverTest,AiRegistrySnapshotContractTest \
  test
```

This writes the reviewable Java report to
`target/ui-composition-golden/java-report.json`.

After the Angular implementation is committed and its worktree is clean, generate the official
closure from the sibling `praxis-ui-angular` checkout. This single command deletes stale `dist`
and Angular persistent-cache outputs, executes the topological production build, derives/copies
the transitive closure and emits its
receipt:

```bash
node tools/ai-registry/build-ui-composition-package-closure.mjs
npx ts-node --project tools/tsconfig.tools.json \
  tools/ai-registry/ui-composition-package-closure.spec.ts
```

Copy the emitted `dist/ui-composition-package-closure/closure-report.json` object byte-for-identity
into the target profile, rerun the Maven gate above to regenerate the peer report, then run the
TypeScript handshake against those exact Config files:

```bash
npm run build:tools
npx -y ts-node --project tools/tsconfig.tools.json \
  tools/ai-registry/ui-composition-golden-corpus-runner.spec.ts
```

The TypeScript spec reads the Java report, executes target attestation and proves deliberate drift.
Use `PRAXIS_CONFIG_STARTER_ROOT` when the repositories are not siblings.

## Corpus governance

When behavior changes intentionally:

1. change the canonical owner compiler or preflight first;
2. add or update the smallest semantic case in the neutral corpus;
3. run Java and TypeScript against that same uncommitted corpus;
4. inspect both projection bodies, diagnostics and hashes;
5. update an expected hash only after human review explains the semantic change;
6. update `registryFingerprint` only after reviewing an intentional change in the official provider
   projection; publish a new external `runtimeExecutionReceipt` only from reviewed, clean source
   trees and their exact built bytes; update the certification baseline independently.

There is no automatic “accept golden” command. Generated reports live under ignored build output
and are evidence, not committed sources of truth.
