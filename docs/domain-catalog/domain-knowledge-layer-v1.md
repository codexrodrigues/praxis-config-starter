# Domain Knowledge Layer v1

Status: implementation design  
Date: 2026-04-22  
Scope: `praxis-config-starter` canonical runtime model

## Purpose

The Domain Knowledge Layer is the governed runtime layer that lets humans,
frontends, backends and LLM agents understand enterprise business meaning
without reading source code.

It sits above the immutable Domain Catalog release payload and below future
executable rules, policies, workflows and generated UI behavior.

This layer answers:

- what business concept a field, action, state or metric represents;
- which service/context owns that concept;
- which technical artifacts implement it;
- which aliases business users may use for it;
- which relationships connect it to other concepts and services;
- what evidence supports the mapping;
- what an LLM may retrieve, reason about or propose changing.

It does not execute rules.

## Existing Foundation

The current `praxis-config-starter` foundation already provides:

- immutable `domain_catalog_release`;
- projected `domain_catalog_item`;
- raw payload retention for full-fidelity replay;
- `/domain-catalog/context` for compact LLM prompt context;
- `resourceKey` scoping for multi-resource service catalogs;
- AI visibility filtering and governed summaries;
- authoring quick replies with `contextHints.domainCatalog.resourceKey`;
- manual and scheduled quickstart runtime smoke coverage.

The next step is to add a queryable knowledge model without breaking the
existing release/item storage.

## Design Rule

Keep three concepts separate:

| Layer | Purpose | Mutable? | Examples |
| --- | --- | --- | --- |
| Domain Catalog Release | Immutable source snapshot from a producer | No | `/schemas/domain`, release payload, projected items |
| Domain Knowledge Layer | Governed semantic graph and resolved enterprise meaning | Versioned by change set | concepts, aliases, bindings, evidence, stewardship, AI visibility |
| Rule/Policy/Workflow Artifacts | Executable decisions or generated behavior | Versioned independently | form rules, OPA/Rego, DMN, workflow transitions |

Rules and policies may reference knowledge nodes, but knowledge nodes must not
become executable rules.

## Config Database Model

Do not replace `domain_catalog_release` and `domain_catalog_item`. Add curated
tables next to them.

The initial table foundation is represented by Flyway migration:

```text
src/main/resources/db/migration/V18__create_domain_knowledge_layer.sql
```

Do not apply this manually to the shared remote config database until the remote
Flyway history has been validated. The remote database may be ahead of local
migrations.

### `domain_knowledge_concept`

Canonical semantic node known by the platform.

Recommended fields:

- `id`
- `concept_key`: stable canonical identity, unique per tenant/environment
- `context_key`
- `resource_key`: optional nearest API resource identity
- `node_type`: `concept`, `field`, `state`, `action`, `event`, `metric`,
  `actor`, `document`, `policy_placeholder`, `rule_placeholder`
- `label`
- `description`
- `locale`
- `semantic_owner`
- `steward`
- `lifecycle`: `draft`, `candidate`, `active`, `deprecated`, `retired`
- `curation_status`: `generated`, `review_required`, `approved`, `rejected`
- `ai_visibility`: `allow`, `mask`, `summarize_only`, `deny`
- `data_category`
- `classification`
- `compliance_tags`: JSON array
- `source_release_id`: nullable reference to the release that first proposed it
- `payload`: JSONB for contract-specific details
- `created_at`, `updated_at`

Indexes:

- unique `(tenant_id, environment, concept_key)`
- `(tenant_id, environment, context_key)`
- `(tenant_id, environment, resource_key)`
- `(tenant_id, environment, node_type)`
- `(tenant_id, environment, lifecycle, curation_status)`
- JSONB GIN on `payload` only if runtime queries need it.

### `domain_knowledge_alias`

Business vocabulary and natural-language lookup.

Recommended fields:

- `id`
- `concept_id`
- `alias`
- `normalized_alias`
- `locale`
- `region`
- `business_unit`
- `alias_type`: `preferred_term`, `synonym`, `abbreviation`,
  `legacy_name`, `business_slang`, `technical_name`, `misspelling`
- `weight`
- `source`: `generated`, `annotated`, `manual`, `imported`, `llm_proposed`
- `curation_status`
- `created_at`, `updated_at`

Indexes:

- `(tenant_id, environment, normalized_alias)`
- `(tenant_id, environment, concept_id)`
- unique partial index for approved preferred terms where useful.

### `domain_knowledge_binding`

Mapping from semantic meaning to implementation artifacts.

Recommended fields:

- `id`
- `concept_id`
- `binding_type`: `api_resource`, `api_operation`, `dto_class`,
  `dto_field`, `entity_class`, `entity_field`, `service_method`,
  `repository_projection`, `workflow_action`, `ui_surface`,
  `ui_schema_field`, `form_config`, `table_config`, `component_capability`,
  `event_schema`
- `binding_key`
- `resource_key`
- `api_path`
- `api_method`
- `schema_pointer`
- `source_release_id`
- `confidence`
- `curation_status`
- `payload`
- `created_at`, `updated_at`

Indexes:

- `(tenant_id, environment, concept_id)`
- `(tenant_id, environment, binding_type, binding_key)`
- `(tenant_id, environment, resource_key)`
- `(tenant_id, environment, api_path, api_method)`

### `domain_knowledge_relationship`

Explicit semantic edge between concepts.

Recommended fields:

- `id`
- `source_concept_id`
- `target_concept_id`
- `relationship_type`: `contains`, `has_field`, `has_state`,
  `has_action`, `references`, `depends_on`, `computed_from`, `triggers`,
  `maps_to`, `same_as`, `broader_than`, `narrower_than`, `governed_by`,
  `owned_by`, `stewarded_by`
- `cross_context`: boolean
- `source_context_key`
- `target_context_key`
- `contract_key`: optional REST/event/schema contract that justifies the edge
- `confidence`
- `curation_status`
- `payload`
- `created_at`, `updated_at`

Indexes:

- `(tenant_id, environment, source_concept_id)`
- `(tenant_id, environment, target_concept_id)`
- `(tenant_id, environment, relationship_type)`
- `(tenant_id, environment, cross_context)`

### `domain_knowledge_evidence`

Auditable reason why the platform believes a concept, alias, binding or
relationship exists.

Recommended fields:

- `id`
- `evidence_key`
- `subject_type`: `concept`, `alias`, `binding`, `relationship`
- `subject_id`
- `evidence_type`: `annotation`, `openapi`, `json_schema`, `java_symbol`,
  `catalog_release`, `manual_review`, `llm_proposal`, `import`
- `source_release_id`
- `source_uri`
- `source_pointer`
- `confidence`
- `payload`
- `created_at`

Indexes:

- `(tenant_id, environment, subject_type, subject_id)`
- `(tenant_id, environment, evidence_key)`
- `(tenant_id, environment, source_release_id)`

### `domain_knowledge_change_set`

Governed patch proposal and approval record. This is the bridge for analyst and
LLM authoring.

Recommended fields:

- `id`
- `change_set_key`
- `status`: `draft`, `proposed`, `approved`, `rejected`, `applied`,
  `superseded`
- `author_type`: `human`, `llm`, `system`
- `author_id`
- `reviewer_id`
- `intent`
- `reason`
- `patch`: JSONB structured operations
- `validation_result`: JSONB
- `created_at`, `reviewed_at`, `applied_at`

This table is the write boundary for future LLM-managed knowledge changes.
LLMs propose change sets; deterministic services validate and apply them.

## Ingestion Flow

1. A service publishes `/schemas/domain`.
2. `praxis-config-starter` validates and stores the immutable release.
3. Projection writes `domain_catalog_item` as today.
4. A new knowledge projection stage upserts generated concepts, aliases,
   bindings, relationships and evidence as `curation_status=generated` or
   `review_required`.
5. Human/LLM curation creates `domain_knowledge_change_set`.
6. Approved change sets update curated knowledge rows.
7. Runtime context reads the curated knowledge layer first, then falls back to
   release items when curated rows are missing.

The Java projection service is intentionally disabled by default until the
target config database has Flyway V18 applied and verified. Enable it with:

```properties
praxis.domain-knowledge.projection.enabled=true
```

## Runtime APIs

Recommended first endpoints:

- `GET /api/praxis/config/domain-knowledge/concepts`
- `GET /api/praxis/config/domain-knowledge/concepts/{conceptKey}`
- `GET /api/praxis/config/domain-knowledge/resolve?q=...`
- `GET /api/praxis/config/domain-knowledge/context`
- `GET /api/praxis/config/domain-knowledge/relationships`
- `POST /api/praxis/config/domain-knowledge/change-sets`
- `POST /api/praxis/config/domain-knowledge/change-sets/{id}/validate`
- `POST /api/praxis/config/domain-knowledge/change-sets/{id}/apply`

`/domain-catalog/context` may continue to exist as the stable public retrieval
API. Internally it can delegate to the knowledge layer once v1 is ready.

## LLM Runtime Contract

LLMs should request knowledge with a compact envelope:

```json
{
  "serviceKey": "praxis-service",
  "resourceKey": "human-resources.folhas-pagamento",
  "intent": "author_dashboard",
  "query": "salario e eventos de folha",
  "includeRelationships": true,
  "relationshipScope": "federated",
  "visibilityMode": "llm_prompt"
}
```

Response items must include:

- `conceptKey`
- `label`
- `description`
- `aliases`
- `bindings`
- `relationships`
- `governance`
- `evidence`
- `aiUsage`
- `retrievalGuidance`

Raw sensitive values must not be returned through this path.

## Migration Phases

### Phase 1: Read Model

- Add tables for concepts, aliases, bindings, relationships and evidence.
- Populate them from existing `domain_catalog_item`.
- Add read-only APIs and tests.
- Keep `/domain-catalog/context` behavior unchanged.

### Phase 2: Curated Overlay

- Add `domain_knowledge_change_set`.
- Add validation for structured patch operations.
- Let approved curated rows override generated rows.
- Add audit and status transitions.

### Phase 3: Authoring Integration

- Let agentic authoring call domain knowledge context before LLM planning.
- Require plans to cite concept keys/evidence when domain terms influenced a
  proposal.
- Preserve component manifests as the only UI mutation contract.

### Phase 4: Rule References

- Introduce rule/policy/workflow artifacts that reference `concept_key`.
- Do not embed executable policy in concept rows.
- Add deterministic validation that a rule only references active/approved
  concepts.

## Open Decisions

- Whether `domain_knowledge_*` tables should live in V18 or wait for a broader
  V18/V19 split between read-model and change-set tables.
- Whether curated overlay should be tenant-specific from day one or allow a
  global baseline plus tenant overrides.
- Whether alias search should use PostgreSQL full-text, trigram extension or a
  vector/RAG side index.
- Whether source services should publish richer evidence for entities/services
  through annotations or through external declarative domain files.

## Acceptance Criteria For v1

- Existing v0.2 release ingestion still passes unchanged.
- Read-model projection is idempotent.
- Every generated concept has at least one evidence row.
- Every generated field/action concept has at least one binding.
- LLM context respects `ai_visibility`.
- Cross-context relationships are explicit and evidence-backed.
- Change sets are validated before apply.
- No executable rule is stored as a domain concept.
