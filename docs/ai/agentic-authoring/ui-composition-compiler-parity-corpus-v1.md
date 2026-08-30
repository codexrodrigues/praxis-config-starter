# UiCompositionPlan compiler parity corpus v1

Status: implemented as the focal cross-repository gate for
[`praxis-config-starter#357`](https://github.com/codexrodrigues/praxis-config-starter/issues/357).

## Decision and ownership

`praxis-config-starter` owns one neutral, versioned corpus for deterministic
`UiCompositionPlan` compilation. Java and TypeScript consume the same cases; neither runner owns a
second copy of expected output.

- schema:
  `docs/ai/agentic-authoring/contracts/ui-composition-compiler-parity-corpus.v1.schema.json`;
- cases and frozen target profiles:
  `docs/ai/agentic-authoring/proofs/ui-composition-compiler-parity-corpus.v1.json`;
- Java runner:
  `UiCompositionGoldenCorpusRunner` under test scope;
- TypeScript runner:
  `praxis-ui-angular/tools/ai-registry/ui-composition-golden-corpus-runner.ts`.

The corpus is not a registry, runtime source of truth, template store or business-rule catalog. Its
target profile is a small attestation fixture derived from a named UI certification train. The
profile carries a canonical SHA-256 fingerprint and references the owner-repository evidence used
to construct it.

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

Known Java/TypeScript spelling or path differences are explicit per expected diagnostic. The
runners may not normalize an unexpected divergence into success. The TypeScript runner optionally
loads the Java report and compares every case's phase, outcome and projection hash.

Projection canonicalization sorts object keys recursively, preserves array order and semantic
`null`, serializes UTF-8 JSON and hashes with SHA-256. Template configuration continues to use the
existing `CanonicalJsonHashService` convention, which omits null object properties. These two hash
domains are deliberately distinct and named in the corpus.

### Target attestation

Only after compiler parity passes does the Angular runner attest the materialization against the
selected frozen target profile. It checks:

- target component and semantic port availability;
- declared capabilities and global actions;
- exact template revision (`registryKey`, `version`, `ETag`, `configSha256`);
- the real `preflightUiCompositionPlan(...)` against a `ComponentMetadataRegistry` built from the
  frozen profile.

Target failure does not rewrite compiler parity. Cases intentionally prove that an identical,
valid projection can still be blocked because the target train lacks a component, port,
capability/action or pinned template revision.

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

After building `@praxisui/core` and `@praxisui/page-builder`, from the sibling
`praxis-ui-angular` checkout:

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
6. update the target profile fingerprint only when its source certification train changes.

There is no automatic “accept golden” command. Generated reports live under ignored build output
and are evidence, not committed sources of truth.
