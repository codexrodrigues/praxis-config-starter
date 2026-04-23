# Governed Domain Catalog Semantic Layer Plan

Status: implementation planning  
Date: 2026-04-22  
Classification: `arquitetural` / `contrato-publico`

This plan turns the current Domain Catalog foundation into a governed semantic
layer that can be consumed by authoring tools, RAG, prompt context builders and
runtime clients without source-code inspection or keyword routing.

## Current State

The current baseline already has the foundation needed for the next step.

`praxis-metadata-starter` is the canonical publisher of generated runtime
semantics. It exposes `/schemas/domain` from metadata, OpenAPI and Praxis
annotations, and remains the owner of metadata-driven vocabulary extraction.

`praxis-config-starter` is the canonical persistence and projection boundary
for `/api/praxis/config/**`. It persists federated domain catalog releases,
materializes catalog items, exposes latest/context queries and optionally
publishes items to RAG.

`praxis-api-quickstart` is the operational reference host. It validates that
published starters work together, but it must not redefine catalog semantics.

The v0.1 contract already defines stable top-level sections:

- `contexts`
- `nodes`
- `edges`
- `bindings`
- `aliases`
- `evidence`
- `governance`

The gap is not storage. The gap is governed semantics: ownership, lifecycle,
field/entity/API-to-business mappings, cross-service relationships, authoring
operations, validation gates and compact LLM context rules.

The concrete v1 config-store design for this governed read model is captured in
[`domain-knowledge-layer-v1.md`](domain-knowledge-layer-v1.md). That document
defines the proposed `domain_knowledge_*` tables, indexing strategy, read APIs,
change-set boundary and migration phases.

## Target Outcome

The target platform behavior is:

1. Services publish deterministic semantic releases through `/schemas/domain`.
2. `praxis-config-starter` ingests, validates, persists, indexes and projects
   those releases by tenant, environment, service and release.
3. LLM and authoring tools consume compact domain context from
   `/api/praxis/config/domain-catalog/context`, not source code or keyword
   catalogs.
4. Governance metadata controls whether items can be retrieved, summarized,
   masked, used for reasoning, or used for rule/authoring proposals.
5. Cross-service concepts are connected by explicit edges, not by name guessing.
6. Future executable rules are separate artifacts. `policy_hint` remains
   semantic intent, not a policy engine.

## Canonical Ownership

| Concern | Canonical owner | Notes |
| --- | --- | --- |
| Metadata-driven extraction | `praxis-metadata-starter` | Publishes `/schemas/domain`; derives from annotations, OpenAPI, filtered schemas and resource metadata. |
| Domain catalog contract docs/schema | `praxis-config-starter/docs/domain-catalog` | Stores the platform ingestion/projection contract used by `/api/praxis/config/**`. |
| Catalog persistence and tenant/environment projection | `praxis-config-starter` | Owns `domain_catalog_release`, `domain_catalog_item`, RAG projection and context APIs. |
| Operational proof | `praxis-api-quickstart` | Hosts reference resources and validates the full integration path. |
| Angular authoring consumption | `praxis-ui-angular` | Consumes context and manifests; does not own domain semantics. |
| Public examples/docs | `praxis-ui-landing-page` and HTTP examples | Derived surfaces. They must mirror the canonical contract. |

## Contract v0.2 Direction

Do not break v0.1 storage unnecessarily. Extend the contract cleanly.

Recommended additions:

- `semanticOwner`: explicit owner for contexts, nodes and governance records.
- `steward`: quality steward or team responsible for semantic correctness.
- `lifecycle`: `draft`, `candidate`, `active`, `deprecated`, `retired`.
- `businessGlossary`: curated description, examples, negative examples and
  preferred business terms.
- `aiUsage`: retrieval, summarization, reasoning and authoring permissions.
- `dataHandling`: privacy category, masking strategy, retention and compliance
  tags.
- `resolution`: deterministic match keys for authoring tools, including
  canonical key, aliases and ambiguity policy.
- `sourceEvidence`: required evidence keys for non-generated or low-confidence
  items.
- `relationships`: explicit cross-service relationship metadata where edge
  payload needs governance or confidence details.

The schema must continue to reject unknown top-level fields. Extension points
belong inside typed payload/metadata objects only when the semantics are not
stable enough for first-class fields.

## Workstreams

### 1. Metadata Publisher

Repository: `praxis-metadata-starter`

Deliverables:

- Extend `/schemas/domain` generation with v0.2 semantic governance fields.
- Preserve v0.1 compatibility only if required by existing consumers; otherwise
  advance the emitted `schemaVersion` cleanly during beta.
- Add deterministic node keys for resources, fields, operations, states,
  surfaces and policy hints.
- Add source evidence for generated items.
- Add tests around `DomainCatalogControllerTest` for governance, aliases,
  edges, bindings and evidence.

Acceptance gate:

- Focal metadata tests pass.
- Generated catalog validates against the new JSON Schema.
- At least one quickstart resource emits field, action, state, binding,
  evidence and governance items.

### 2. Config Ingestion And Projection

Repository: `praxis-config-starter`

Deliverables:

- Add v0.2 JSON Schema validation before persistence.
- Persist any new indexed fields needed for filtering, especially
  classification, data category, owner, steward, AI visibility and lifecycle.
- Keep raw payload storage for full fidelity.
- Make `/domain-catalog/context` enforce AI visibility and masking guidance.
- Add deterministic context retrieval rules for LLM authoring.
- Add tests for invalid schema, unsupported lifecycle, governance filtering,
  tenant/environment latest release selection and RAG metadata.

Acceptance gate:

- Focal config tests pass.
- Invalid v0.2 payloads fail before persistence.
- Context responses contain retrieval guidance, release evidence and governance
  restrictions.
- RAG projection does not index denied or masked content as unrestricted text.

### 3. Authoring Integration

Repository: `praxis-config-starter`

Deliverables:

- Inject Domain Catalog context into authoring orchestration before the LLM
  proposes component edits.
- Keep component authoring manifests responsible for UI config operations.
- Use Domain Catalog only for business vocabulary, field/resource meaning,
  governance and disambiguation.
- Add evals showing the LLM resolves business terms through catalog context
  instead of keywords.

Acceptance gate:

- Authoring smoke proves catalog context is retrieved and included.
- Plans cite catalog item keys or evidence where business vocabulary influenced
  the proposal.
- The manifest validator remains the gate for component config mutations.

### 4. Quickstart Operational Proof

Repository: `praxis-api-quickstart`

Deliverables:

- Add representative resources with governance annotations or metadata that
  produce v0.2 catalog items.
- Add smoke fixtures for ingesting generated catalogs into config starter.
- Validate `/context` queries for business vocabulary, protected fields and
  cross-service references.

Acceptance gate:

- Quickstart packages against the local starter versions.
- HTTP smoke covers generate, ingest, query latest context and authoring
  context injection.
- Remote workflow records the evidence once the local gate is stable.

### 5. Derived Docs And Examples

Repositories: `praxis-ui-landing-page`, `praxisui-http-examples`,
`praxis-ui-angular`

Deliverables:

- Update public docs only after canonical contract and smoke evidence exist.
- Add HTTP examples for `/schemas/domain`, `/domain-catalog/ingest`,
  `/domain-catalog/items/latest` and `/domain-catalog/context`.
- Add Angular or authoring examples only as consumers of the runtime context.

Acceptance gate:

- Examples validate against the canonical schema.
- Public docs do not promise unsupported authoring or governance behavior.

## Agent Task Split

Use these as independent agent briefs. Each agent must read this plan, the v0.1
contract and the runtime flow document before editing.

1. Metadata agent: implement v0.2 publisher and tests in
   `praxis-metadata-starter`.
2. Config schema agent: add v0.2 schema validation and persistence/index
   updates in `praxis-config-starter`.
3. Config context agent: enforce AI visibility/masking and improve
   `/domain-catalog/context` retrieval guidance.
4. Authoring agent: inject domain catalog context into the LLM authoring flow
   without moving component semantics out of manifests.
5. Quickstart agent: add end-to-end fixtures and smoke coverage.
6. Docs/examples agent: update derived docs only after the canonical gates pass.

## Non-Goals For This Cycle

- Page-builder-specific semantic behavior.
- Executable rule engine, OPA or DMN integration.
- UI governance editor.
- Automatic legal classification without explicit evidence.
- Treating aliases or keyword lists as primary routing.
- Moving component config semantics from manifests into Domain Catalog.

## Minimum Validation Matrix

| Scope | Validation |
| --- | --- |
| Metadata publisher | `DomainCatalogControllerTest` and generated catalog schema validation. |
| Config ingestion | Domain catalog ingestion/service tests, including invalid payloads and tenant/environment latest lookup. |
| Authoring | Focal authoring smoke proving context retrieval and manifest validation remain separate. |
| Quickstart | Package against local starters, generate catalog, ingest catalog, query context and run HTTP/SSE authoring smoke. |
| Derived docs/examples | Schema/example validation only after canonical behavior is merged. |

## Promotion Gate

Do not publish another release baseline until these are true:

- v0.2 schema is versioned and committed.
- Metadata publisher emits a valid v0.2 catalog.
- Config starter validates and persists v0.2.
- `/domain-catalog/context` applies governance restrictions.
- Quickstart proves generate -> ingest -> context -> authoring smoke.
- Derived docs match the canonical behavior.

## Open Decisions

- Whether v0.2 should be additive under `metadata` for some governance fields
  or promote them to first-class fields immediately.
- Whether denied AI visibility should exclude items entirely from context
  responses or return masked evidence with explicit denial metadata.
- Which annotations in `praxis-metadata-starter` should become the canonical
  authoring surface for business glossary and stewardship.
- Whether cross-service equivalence should be authored manually first or
  generated from explicit annotations only.
