# RFC: Machine-First Semantic IR and Generative UI Grounding

Status: proposed for the first implementation slice
Date: 2026-07-19
Owner: Praxis Platform

## Classification

Overall initiative: `arquitetural`, `transversal` and future `contrato-publico`.

This RFC step: `docs-apenas`.

Canonical implementation owners:

- `praxis-metadata-starter`: deterministic extraction and publication of host semantics;
- `praxis-config-starter`: ingestion, federation, governance, compilation, retrieval and AI tools;
- `praxis-api-quickstart`: operational proof with the Human Resources pilot;
- `praxis-ui-angular`: official runtime consumer;
- `@praxisui/page-builder`: generative UI proof through grounded `UiCompositionPlan` authoring.

## Decision Summary

Praxis will evolve the existing Domain Catalog and Domain Knowledge foundation into a
machine-first Semantic Intermediate Representation (Semantic IR). It will not create a
parallel domain catalog.

The Semantic IR is the governed, release-scoped representation from which Praxis compiles
regenerable projections optimized for LLM retrieval. OpenAPI documents, schemas, resources,
surfaces, actions and component manifests remain technical evidence and materialization
bindings. They are not the primary representation of business meaning.

The target protocol is:

```text
authored sources + extracted metadata + governed knowledge
                              |
                              v
                    Semantic IR compiler
                              |
                              v
            immutable governed semantic release
                              |
                              v
       multi-resolution packs + graph/vector indexes
                              |
                              v
             semantic tools selected by the LLM
                              |
                              v
         grounding and selection audit envelope
                              |
                              v
       answer | clarification | UiCompositionPlan
                              |
                              v
             compile -> validate -> preview -> apply
```

## Problem

Praxis already tells an LLM which APIs, fields, surfaces, actions and components exist. It
does not yet provide a sufficiently rich and efficient representation of why those artifacts
exist, which business capabilities they implement, how concepts relate across contexts and
which knowledge is safe and approved for reasoning.

The current generated catalog is intentionally derived from metadata already visible in the
runtime. In practice, this means that:

- an OpenAPI group can become the effective bounded context;
- an API resource can be promoted to a business `concept`;
- fields and operations dominate the searchable corpus;
- global questions compete with granular technical artifacts;
- broad authoring requests can reach API discovery before the business scope is resolved;
- the Page Builder can compile a `UiCompositionPlan`, but the plan does not by itself prove
  why each concept, resource, operation, field or component was selected.

The result is avoidable latency, token use, unrelated OpenAPI reads and weak explanations for
generative UI decisions.

## User Job

Any authorized LLM should be able to discover, understand, relate and materialize the
business and technical capabilities of a Praxis host without reading the entire source tree,
OpenAPI catalog or component registry on every turn.

The same foundation must support at least:

- business Q&A;
- platform and host capability discovery;
- explanation of the current form, table, dashboard, field or action;
- resource and operation discovery;
- governed rule and Project Knowledge authoring;
- generative forms, tables, charts, dashboards and master-detail pages;
- evidence-backed explanations of the generated result.

## Inventory Before New Contract

| Capability | Current source | Adherence |
| --- | --- | --- |
| Immutable source catalog with contexts, nodes, edges, bindings, aliases, evidence and governance | Domain Catalog v0.2 | `suportado-parcialmente` |
| Tenant/environment ingestion, release selection and canonical item reload | `DomainCatalogIngestionService` | `suportado-parcialmente` |
| Concepts, aliases, bindings, relationships, evidence and governed change sets | Domain Knowledge layer | `suportado-parcialmente` |
| Federated contexts and relationships | Domain federation | `suportado-parcialmente` |
| Vector indexes as derived projections | Domain Catalog and Project Knowledge RAG | `ja-suportado-mal-nomeado-ou-mal-materializado` |
| First-turn Domain Catalog prompt context | `DomainCatalogPromptContextService` | `ja-suportado-mal-nomeado-ou-mal-materializado` |
| Canonical resource, surface, action, capability and schema discovery | `praxis-metadata-starter` | `already-supported` |
| Intermediate generative UI plan and deterministic compiler | `UiCompositionPlan` and `compileUiCompositionPlan` | `already-supported` |
| Business meaning authored independently from API exposure | No complete canonical authoring source | `lacuna-real-de-contrato` |
| Per-claim authored/extracted/inferred provenance | Evidence exists, but source class and derivation activity are incomplete | `lacuna-real-de-contrato` |
| Multi-resolution enterprise/domain/context/capability packs | No compiled canonical projection | `lacuna-real-de-contrato` |
| Progressive semantic tools before API and component inspection | Current flow remains centered on prompt context and API resource search | `lacuna-real-de-contrato` |
| Grounding-to-UI selection audit envelope | Turn diagnostics exist, but no complete canonical chain from business concept to component selection | `lacuna-real-de-contrato` |

## External Design Inputs

The RFC adopts principles, not external product implementations.

| Reference | Adopted principle | Praxis application | Explicit non-adoption |
| --- | --- | --- | --- |
| SAP CAP domain modeling | Capture intent before services and protocols; separate concerns through aspects | Semantic source before API bindings; separate governance and integration aspects | No requirement to adopt CDS or SAP runtimes |
| SAP Business Data Graph | Keep extracted projections distinct from a unified curated business model and customer extensions | Source extraction -> governed federated release -> tenant overlays | No immediate universal data graph or unified query engine |
| SAP Datasphere glossary | Keep business vocabulary separate from technical assets and link them through semantic enrichment | Concepts, definitions, aliases and stewardship linked to resources, fields, APIs and KPIs | No data-warehouse catalog scope expansion |
| W3C SKOS | Stable concept identity, preferred/alternative labels and hierarchical/associative relations | Vocabulary and relationship semantics | No RDF/OWL storage requirement |
| W3C PROV-O | Entity/activity/agent provenance | Minimal claim-level derivation model | No wholesale PROV-O ontology implementation |
| GraphRAG and DRIFT | Answer global questions from hierarchical summaries, then traverse local evidence dynamically | Compiled overview packs, pruning and bounded neighborhood expansion | No dependency on a specific GraphRAG product or graph database |

Primary references:

- <https://cap.cloud.sap/docs/guides/domain/>
- <https://help.sap.com/docs/integration-suite/sap-integration-suite/business-data-graph-894e28c9eda1498ab8a9f153a3ff9b48>
- <https://help.sap.com/docs/SAP_DATASPHERE/aca3ccb4b2f84eb8b6154e8fd2812c0e/193336a4eba94c978c08fd2cee625a3e.html>
- <https://www.w3.org/TR/skos-reference/>
- <https://www.w3.org/TR/prov-o/>
- <https://www.microsoft.com/en-us/research/publication/from-local-to-global-a-graph-rag-approach-to-query-focused-summarization/>
- <https://www.microsoft.com/en-us/research/blog/introducing-drift-search-combining-global-and-local-search-methods-to-improve-quality-and-efficiency/>

## Goals

1. Make business meaning addressable independently from endpoints and UI artifacts.
2. Give LLMs compact, typed and progressively retrievable semantic context.
3. Preserve an evidence chain from source claim to answer or UI materialization.
4. Make OpenAPI, schemas and component manifests just-in-time technical bindings.
5. Keep authoring, extraction and inference distinct and governed.
6. Support global, local, executable and generative UI questions with different retrieval budgets.
7. Reuse current Domain Catalog, Domain Knowledge, RAG, AI Registry and Page Builder contracts.
8. Prove the architecture with an end-to-end Human Resources pilot.

## Non-Goals

- Replacing OpenAPI, `/schemas/filtered`, surfaces, actions or capabilities.
- Creating a second domain catalog or a Page Builder-specific business model.
- Requiring a graph database in the first implementation.
- Making embeddings, chunks, summaries or prompts canonical truth.
- Publishing LLM inference without validation and approval.
- Loading source code, all APIs or all component manifests on every user turn.
- Defining an exhaustive enterprise ontology before the pilot demonstrates need.
- Routing primary user intent through keywords, regexes or aliases.

## Canonical Semantic Kernel

The first implementation must keep a small typed kernel. Profiles may extend it after
cross-consumer evidence exists.

### Context

Stable semantic scope, with an explicit kind and optional parent:

- enterprise;
- domain;
- subdomain;
- bounded context;
- external or legacy context.

OpenAPI group names may bind to a context, but must not define the context automatically.

### Semantic node

First-profile node kinds:

- `concept`;
- `business_capability`;
- `process`;
- `business_event`;
- `policy`;
- `metric`;
- `actor`.

Fields, endpoints, schemas, surfaces and component manifests remain technical assets or
bindings unless an authored semantic claim explicitly promotes their business meaning.

### Relationship

Typed and evidence-backed links such as:

- `broader` / `narrower`;
- `part_of`;
- `related_to`;
- `triggers`;
- `produces` / `consumes`;
- `applies_to`;
- `measured_by`;
- `implemented_by`;
- `maps_to` / `same_as`.

Cross-context equivalence is a governed claim. An embedding similarity must never silently
canonicalize two concepts.

### Binding

Typed link between a semantic identity and an implementation artifact:

- API resource or operation;
- DTO/entity class or field;
- service method;
- event schema;
- workflow action;
- UI surface;
- component capability or manifest;
- schema pointer.

### Claim and evidence

Every material statement must declare one source class:

- `authored`: asserted by an authorized source;
- `extracted`: produced deterministically from code or metadata;
- `inferred`: proposed by an LLM from cited evidence.

`confidence` does not mean approval. An inferred claim begins as a candidate and must pass a
governed change-set lifecycle before becoming available to production reasoning.

Minimum claim provenance:

- claim id and subject;
- source class;
- source pointer and source release/hash;
- derivation activity;
- authoring agent: human, extractor or model/toolchain;
- model and template hash when inferred;
- timestamp and validity interval;
- validator results;
- curation status;
- superseded claim reference when applicable.

Lifecycle of the business concept, claim curation, temporal validity, release status and AI
visibility are separate dimensions.

## Authoring Sources

The Semantic IR is the canonical compiled representation. It does not prescribe a single
authoring syntax in the first slice.

Allowed source categories:

- concise versioned semantic manifests;
- governed Project Knowledge and approved change sets;
- Java annotations that contain stable refs or bindings;
- deterministic extraction from entities, DTOs, services, tests and metadata;
- imported governed vocabularies.

Java annotations must remain small. Rich prose and relationship graphs do not belong in
controllers or DTO fields. A future annotation should primarily bind a symbol to a stable
semantic id.

## Compiler Pipeline

### 1. Collect

Read the explicit semantic source and deterministic host metadata. OpenAPI and source symbols
are evidence at this stage, not the semantic model itself.

### 2. Normalize

Resolve stable ids, context scope, locale, source release, aliases and type profiles. Do not
promote a resource to a business concept without an explicit or candidate claim.

### 3. Link

Create typed bindings and relationships with source pointers. Detect orphans, duplicates,
conflicts and cross-context ambiguity.

### 4. Synthesize

An LLM may propose definitions, relationships, summaries and answerable questions. Every
proposal is `inferred`, cites its inputs and opens a governed change set.

### 5. Govern

Validate, review, approve, reject or supersede claims. Enforce tenant, environment,
classification, AI visibility and evidence lifecycle.

### 6. Compile

Create deterministic, content-addressed projections from an approved immutable release.

### 7. Index

Index stable ids by abstraction level and retrieval purpose. Vector search ranks canonical
ids; every hit is reloaded and reauthorized from the canonical store.

### 8. Serve

Expose progressive tools and compact packs. Technical contracts are inspected only after
semantic scope and bindings are sufficient.

## Compiled Projections

Compiled packs are caches and indexes, never sources of truth:

- enterprise overview;
- domain overview;
- bounded-context overview;
- capability pack;
- concept neighborhood;
- execution pack;
- UI-generation pack;
- provenance digest.

Every pack must carry:

- canonical member ids;
- tenant/environment scope;
- source and compiled release ids;
- content hash;
- abstraction level and purpose;
- visibility policy;
- provenance refs;
- expiration or supersession state.

## Progressive LLM Tools

Names are provisional; semantics are required.

| Tool | Purpose | Forbidden behavior |
| --- | --- | --- |
| `discover_domain_map` | Return high-level contexts and capabilities | Reading OpenAPI or schemas |
| `describe_domain_context` | Return governed overview and provenance | Returning unbounded member lists |
| `search_business_concepts` | Rank canonical concepts inside resolved scope | Deciding primary intent by text match |
| `expand_semantic_neighborhood` | Expand bounded typed relationships | Unlimited graph traversal |
| `discover_business_capabilities` | Resolve capability candidates for an intent | Selecting an endpoint directly |
| `resolve_domain_bindings` | Resolve technical/UI bindings for accepted semantic ids | Returning unrelated resources |
| `inspect_resource_contract` | Inspect one accepted resource's surfaces/actions/capabilities | Loading the full API catalog |
| `inspect_operation` | Resolve one operation and its canonical schema JIT | Inferring a missing schema |
| `discover_components_by_capability` | Rank registry components for role/data/interactions | Loading every manifest |
| `inspect_component_manifest` | Read selected component ports and operations | Inventing component inputs |
| `validate_ui_composition_plan` | Compile and validate the intermediate plan | Applying arbitrary JSON patches |

Tool selection is authored semantically by the LLM. Textual matching may only rank candidates
after the semantic scope has been resolved.

## Retrieval Profiles And Initial Budgets

Budgets are policy defaults and must remain configurable and observable.

| Profile | Default scope | Technical read budget |
| --- | --- | --- |
| Global explanation | Up to 8 overview summaries | Zero OpenAPI, schema and component-manifest reads |
| Context exploration | Up to 12 relevant nodes/edges across 1-2 expansions | Zero schema reads |
| Resource grounding | Top 1-3 accepted resources | Surfaces/actions/capabilities only for accepted resources |
| Executable operation | One selected operation | At most one canonical schema per selected operation |
| UI materialization | Top 3-5 component candidates | Full manifests only for the top 1-2 selected components |

Every turn records tool calls, canonical reads, candidates considered/accepted/rejected,
tokens, latency, cache hits and fallback reason.

## Generative UI Integration

`WidgetPageDefinition` remains the canonical persisted page. `UiCompositionPlan` remains the
intermediate plan that must compile before preview or apply.

The authoring turn and preview gain a non-persisted grounding/selection audit envelope:

```text
semantic intent
  -> context/capability/concept refs
  -> accepted and rejected semantic candidates
  -> resource/surface/action/schema refs
  -> component/manifest refs
  -> evidence and release refs
  -> UiCompositionPlan validation outcome
  -> terminal authority
```

The envelope must explain every material selection without being embedded in the persisted
page document. Runtime frontend observations remain untrusted evidence and cannot elevate the
authority of a semantic claim.

## Fail-Closed Rules

- Missing semantic scope or binding produces clarification, not broad endpoint discovery.
- Missing governed operation/schema produces a consultative result with `canApply=false`.
- Missing component manifest or incompatible ports blocks materialization.
- Conflict between Domain Catalog, Project Knowledge and canonical schema is explicit and
  blocks apply.
- Stale, superseded, denied or cross-scope evidence is discarded.
- Invalid `UiCompositionPlan` allows bounded repair; repeated failure requires clarification.
- A vector-store outage falls back to governed structured retrieval, never unscoped or stale
  candidates.
- Runtime observations never override server-authoritative metadata or governance.

## Observability Contract Direction

The future trace must expose structured events or diagnostics for:

- semantic intent and abstraction level;
- retrieval profile and tier;
- source/compiled releases;
- candidate funnel by tier;
- fallback and pruning reasons;
- APIs, schemas and manifests actually read;
- evidence used by material decisions;
- rejected alternatives;
- per-phase latency, tokens, cache and budget;
- validation and repair count;
- terminal authority: explain, clarify, preview or apply.

User-facing stream messages are curated projections over these events, not raw internal traces.

## Security And Governance

- Every canonical and derived read remains scoped by tenant and environment.
- AI visibility is rechecked after vector ranking and before projection.
- Denied evidence never enters a prompt, compiled pack or audit response.
- Mask and summarize-only policies are applied before indexing.
- Inference stores model/toolchain and source hashes without storing secrets.
- Cross-context and cross-tenant equivalence never arises from similarity alone.
- Prompt, raw source payload and sensitive runtime observations are not persisted into
  `WidgetPageDefinition`.

## Beta Migration Strategy

Praxis is in beta. The migration is canonical and clean:

1. Keep Domain Catalog v0.2 and Domain Knowledge as the only existing sources of truth.
2. Add the minimum Semantic IR semantics to the next contract revision after the RFC is
   accepted; do not create v1/v2 runtime paths or a parallel catalog.
3. Mark automatic resource-to-concept promotion as extracted candidate semantics.
4. Introduce compiled packs as derived projections and migrate prompt context to them.
5. Introduce progressive tools before restricting broad API discovery.
6. Update quickstart, Angular clients, HTTP corpus and public docs in the same release cycle.
7. Remove superseded broad fallbacks rather than preserving permanent compatibility flags.

The exact contract version is intentionally not selected by this RFC-only slice.

## Implementation Slices

### Slice 0: Baseline and contract review

- [x] Author this RFC, the Human Resources inventory and the acceptance corpus.
- [x] Capture the read-only local `/schemas/domain?group=human-resources` source baseline.
- [x] Synchronize the documentary/runtime Domain Catalog v0.2 schema and add an anti-drift gate.
- [x] Select and implement the minimal pilot IR and claim-provenance semantics.
- [ ] Capture governed retrieval/provider traces for the must-pass corpus.

The source baseline is reproducible through
`tools/local-e2e/capture-machine-first-hr-baseline.sh`. It proved a valid v0.2 catalog with 545
nodes, 525 edges, 515 bindings and 1,001 evidence items. It also measured the key quality gap:
the aggregate HR context and all 545 nodes lack explicit owners, while 143 of 163 governance
entries are heuristic. This is evidence for Slice 1 authoring and provenance work, not a reason
to create a parallel catalog.

### Slice 1: Authored source and claim provenance

- [x] Prototype one small Human Resources semantic source.
- [x] Bind it to existing quickstart resources without changing API identity.
- [x] Persist authored/extracted/inferred provenance and governance through reviewed change sets.
- [x] Validate the reference proposal through the public HTTP create contract with the real
  controller, validator and service in an isolated MockMvc proof.
- [x] Validate the proposed pilot through the real HTTP lifecycle without mutating a shared database.

The pilot reuses `/domain-knowledge/change-sets` as its concise source syntax. It does not add a
manifest database, a second catalog or a Page Builder-specific model. Claim provenance remains in
the governed semantic payload in this cut, while lifecycle, curation, tenant/environment and
review identity continue in first-class columns. The reference proposal is
`ai/agentic-authoring/proofs/human-resources-semantic-pilot-change-set.v0.1.json`.
`DomainKnowledgeHumanResourcesSemanticPilotHttpTest` proves acceptance by the public endpoint
without external state. `DomainKnowledgeHumanResourcesSemanticPilotPostgresHttpTest` starts an
isolated PostgreSQL, baselines the non-vector predecessor at V17, copies the canonical Domain
Knowledge migrations V18, V19, V26 and V38 into an ephemeral Flyway location, and proves the full
create -> validate -> approve -> apply -> get/timeline lifecycle. It also queries the persisted
concepts, alias, resource binding, `measured_by` relationship and claim evidence before shutting
the database down. This avoids both the shared database and a false H2 approximation; the wider
V1 -> V38 migration chain remains covered separately because its vector migrations require a
PostgreSQL distribution with `pgvector`.

### Slice 2: Compiler and multi-resolution packs

- [x] Compile a bounded macro context pack before intent resolution and a focal
  context/capability/concept pack after semantic scope resolution.
- [x] Reconcile, hash and purge derived vector packs by release.
- [x] Prove structured fallback without vector infrastructure.

This cut does not introduce a pack table or a parallel compiler contract. The existing
`AgenticAuthoringProjectKnowledgeService` is the safe compiler projection: legacy knowledge keeps
using `payload.kind`, while authored semantic concepts fall back to canonical `nodeType`. A turn
without context/resource retrieves at most four governed `context` nodes. After semantic intent
resolves a context or resource, the Turn Engine recompiles the same `projectKnowledge.v1` envelope
with a focal budget of eight across context, capability, process, event, policy, metric, actor,
concept and the existing project-knowledge kinds. Empty macro retrieval is marked backend-side so
it is not repeated. The PostgreSQL pilot proves the macro Human Resources context and the focal
`context + business_capability + metric` projection directly from active evidence.

The opt-in vector projection now separates stable document identity from indexed-content
integrity. Its address remains deterministic for the canonical `conceptKey + evidenceKey`, while
`contentHash` is SHA-256 over the redacted text actually sent to the vector store. A safe semantic
revision therefore replaces the same document and changes its integrity hash instead of leaving
an older vector orphaned. Internal release reconciliation reloads only approved, active,
AI-visible concepts and active evidence from the canonical tables, purges the selected
tenant/environment/release `project_knowledge` corpus and republishes that expected set. No vector
row becomes an authority for lifecycle or AI influence.

### Slice 3: Progressive tools

- [x] Add domain/concept/capability tools.
- [x] Add binding/resource/operation inspection gates.
- [x] Prevent broad API/schema discovery for macro profiles.

The internal tool registry now exposes `discoverDomainContexts`,
`discoverDomainCapabilities` and `discoverDomainConcepts`. All three are read-only,
`retrieveEvidence`-only tools over the existing governed Project Knowledge projection; tenant and
environment always come from the authenticated backend principal, never from model-authored
arguments. The semantic pre-intent planner now returns a structured `groundingProfile` and can
choose the smallest domain tool before `api_resource`. Results are projected back into the same
`projectKnowledge.v1` context consumed by intent resolution, so the tool execution is effective
grounding rather than diagnostic-only tracing. API discovery remains available for the later
operational stage behind the binding/resource/operation gates described below.

The first operational gate now projects approved `domain_knowledge_binding` rows only when their
owning concept remains governed and has active evidence. `inspectDomainBindings` exposes that safe
projection as a read-only tool, and the planner may select `groundingProfile=domain_binding` after
resolving a canonical `resourceKey`. Its result is carried in a bounded `domainBindings.v1`
grounding envelope. A pre-intent `searchApiResources` call fails closed when the canonical resource
or an eligible binding is absent.

The operational verification gate is now complete for this slice. `verifyDomainOperation` reloads
the governed binding, resolves the exact `path + HTTP operation + request|response` variant through
`/schemas/filtered`, and checks the corresponding canonical capability operation plus current
principal availability. The safe `verifiedDomainOperations.v1` projection preserves only canonical
operation identity, schema/capabilities URLs, release and evidence refs; it never injects the full
schema into macro planning. Missing schema, unsupported operation, unverified availability or an
explicit capability denial fail closed. Pre-intent API discovery uses the same verification when
the verifier is available, so macro profiles without a resolved resource/binding/operation cannot
fall through to broad endpoint or schema loading.

### Slice 4: Generative UI selection envelope

- [x] Add component discovery by capability.
- [x] Preserve grounding evidence through preview.
- [x] Keep `UiCompositionPlan` and `WidgetPageDefinition` boundaries intact.

The first selection cut reuses the governed component capability catalogs already loaded from the
AI Registry. After the LLM has authored the canonical semantic and visualization decision, backend
policy ranks only those catalog entries against the resolved artifact, visual intent, layout,
primary component and explicit exclusions. It does not inspect the user prompt or decide primary
intent by textual matching. The result is bounded to five accepted candidates and records rejected
or budget-pruned alternatives, matched capability ids, manifest versions, catalog degradation and
AI Registry evidence refs.

The resulting `praxis-agentic-authoring-component-selection.v1` projection is injected into the
existing turn context and copied into `uiCompositionPlan.diagnostics.componentSelection`. It is
therefore available to preview, repair and terminal audit without creating another page format.
The compiler continues to materialize only the canonical page into
`compiledFormPatch.patch.page`; component-selection evidence is not written into the resulting
`WidgetPageDefinition`.

### Slice 5: Hardening and publication

- [x] Run the focal Human Resources authoring and guidance-to-materialization journey with a real
  LLM and deterministic validators.
- [x] Prove tenant/environment isolation through the published HTTP boundary.
- [x] Prove stale release rejection through the published authoring HTTP boundary.
- [x] Capture focal cost and latency evidence.
- [ ] Update derived public docs, examples, registry and HTTP corpus after the remaining isolation
  gates pass.

Operational evidence captured on 2026-07-19 against the quickstart packaged with the local
`praxis-config-starter:0.1.0-rc.85`, OpenAI `gpt-5.6-terra` and real OpenAI embeddings:

- `employee-beautiful-screen-pt` passed `1/1`, produced an applicable page with
  `praxis-rich-content + praxis-dynamic-form`, consumed 17,106 tokens, cost an estimated
  USD 0.033562 and reached the terminal event in 61.351 s;
- the component selection was reduced from five noisy candidates to the single governed primary
  `praxis-page-builder`; compared with the pre-fix run, tokens fell from 45,629 to 17,106 and
  estimated cost from USD 0.131473 to USD 0.033562;
- `platform-guidance-to-employee-dashboard-pt` passed `2/2`: the first turn answered
  consultatively with no preview and the follow-up in the same thread produced an applicable
  employee dashboard. The journey consumed 35,779 tokens, cost an estimated USD 0.080009 and had
  median terminal latency of 29.444 s;
- the dashboard materialized rich content, filter, chart, list and table widgets. Its semantic
  display field `departamentoNome` was structurally verified by `/schemas/filtered` and aligned to
  the already governed stats execution field `departamento` from resource capabilities. The chart
  finished with `statsVerified=true` and no failure codes;
- `componentSelection` remained audit-only and was absent from the compiled page in both focal
  materializations.

This gate exposed two `ja-suportado-mal-nomeado-ou-mal-materializado` defects rather than new
contract gaps. First, containment matching against broad semantic refs expanded a resolved
decision into unrelated component capabilities; ranking now requires exact canonical matches.
Second, stats verification compared DTO/display fields more strongly than the LLM-authored
business concept; it now aligns an exact semantic concept to the governed capability label before
materializing the canonical execution field. Neither correction introduces keyword-based primary
intent routing or a parallel UI contract.

The core negative isolation gate is also in place: operational binding projection now rechecks
that both binding and owning concept belong to the authenticated tenant/environment even after the
scoped repository query, and rejects a binding whose source release no longer matches the release
owning its concept. Focused unit proof passes for cross-tenant rejection, stale-release rejection
and successful operational verification. The isolated PostgreSQL HTTP pilot additionally proves
that reading a valid change set through another tenant or environment returns the same canonical
`404` already declared by the OpenAPI contract; it exposed and corrected an implementation drift
that previously returned `422`.

The authoring HTTP/SSE gate now starts a real turn with `201`, carries the authenticated
tenant/environment into the canonical `inspect_domain_bindings` tool and proves that a binding
whose source release differs from the release owning its concept produces zero operational
bindings. The stale resource therefore never reaches the streamed grounding context. This proof
also exposed an auto-configuration ordering defect: the starter relied on an early
`@ConditionalOnBean` evaluation and could construct the tool registry without the domain-binding
service even when the repositories existed in the host. Bean resolution is now deferred through
the existing providers, preserving optional installation while ensuring that an installed Domain
Knowledge repository is materialized in the canonical registry. The focused binding,
verification, registry and HTTP/SSE suite passes 51 tests with no failures.

## Impact Map

| Subproject | Future impact | Minimum proof |
| --- | --- | --- |
| `praxis-metadata-starter` | Semantic source refs, deterministic extraction and next catalog revision | Focused domain catalog tests and schema validation |
| `praxis-config-starter` | Claims, provenance, compiler, packs, tools, retrieval policy and diagnostics | Focused domain/RAG/authoring tests |
| `praxis-api-quickstart` | Human Resources semantic source and bindings | Generate -> ingest -> query -> authoring HTTP smoke |
| `praxis-ui-angular/@praxisui/core` | Typed clients and context projections | Focused service/model tests and build |
| `praxis-ui-angular/page-builder` | Selection envelope and component discovery | Plan compiler tests and agentic E2E |
| Landing page and HTTP examples | Derived public contract and recipes | Update only after canonical runtime behavior exists |

Breaking-change risk: high if the existing Domain Catalog shape is changed in place without a
beta migration. The RFC therefore requires a coordinated contract revision and same-cycle
consumer updates.

## Acceptance Criteria

The Human Resources pilot must prove:

1. Global domain questions execute zero OpenAPI/schema reads.
2. Current-surface explanation connects UI context to governed semantic concepts.
3. Generative dashboard requests resolve domain, capability and metrics before resources.
4. Resource materialization inspects no more than the accepted top 1-3 resources.
5. Operation schemas are loaded only after operation selection.
6. Components, inputs, fields and actions are never invented.
7. Every material answer/UI selection has a canonical id, release and evidence chain.
8. Inferred claims cannot be used as approved truth before governance.
9. Structured retrieval remains correct when the vector store is unavailable.
10. Tenant/environment, visibility and stale-release violations fail closed.

Success metrics:

- unrelated OpenAPI reads = 0 for macro and contextual must-pass cases;
- hallucinated component/input/action = 0;
- provenance coverage = 100% for material selections;
- `UiCompositionPlan` compilation rate and useful-preview rate;
- grounded answer correctness and clarification precision;
- p50/p95 time to first useful answer and terminal result;
- tokens and provider cost by retrieval profile.

## Open Decisions

1. Whether compiled packs are persisted as catalog artifacts, Project Knowledge projections or
   a dedicated derived artifact type inside the same config boundary.
2. Tool granularity under the current one-tool-per-turn safety boundary.
3. The exact next Domain Catalog schema version after migration impact is measured.

Resolved for the pilot:

- source syntax: the existing governed Domain Knowledge change-set request;
- claim provenance: governed payload fields (`claimId`, `sourceClass`, `sourceRefs`, derivation and
  agent), with existing lifecycle, curation and review columns remaining authoritative.

## Related Documents

- [`2026-04-federated-domain-catalog-rfc.md`](2026-04-federated-domain-catalog-rfc.md)
- [`domain-catalog/domain-catalog-contract-v0.2.md`](domain-catalog/domain-catalog-contract-v0.2.md)
- [`domain-catalog/domain-knowledge-layer-v1.md`](domain-catalog/domain-knowledge-layer-v1.md)
- [`domain-catalog/governed-semantic-layer-plan.md`](domain-catalog/governed-semantic-layer-plan.md)
- [`ai/2026-07-machine-first-hr-baseline-inventory.md`](ai/2026-07-machine-first-hr-baseline-inventory.md)
- [`ai/2026-07-machine-first-generative-ui-acceptance-corpus.md`](ai/2026-07-machine-first-generative-ui-acceptance-corpus.md)
