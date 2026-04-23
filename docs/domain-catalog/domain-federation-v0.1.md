# Domain Federation Contract v0.1

Status: planning contract  
Date: 2026-04-23  
Depends on: `domain-catalog-contract-v0.2`, `domain-knowledge-layer-v1`

This document defines the first concrete contract for a federated Praxis domain
map. It narrows the broader RFC in `docs/2026-04-federated-domain-catalog-rfc.md`
into the next implementable layer for multi-service domains.

The goal is not rule execution, policy evaluation, or LLM authoring. The v0.1
goal is to let Praxis and an LLM understand which services own which bounded
contexts, how contexts relate, which contracts connect them, and where semantic
terms resolve or conflict.

## Design Boundary

Federation v0.1 must be read-only and validation-first:

1. Model the federated vocabulary.
2. Persist source/context/relationship/contract/resolution records.
3. Validate references, ownership and visibility.
4. Query a consolidated LLM-safe context.
5. Only later allow LLMs to propose changes.

No v0.1 feature may execute business rules, mutate `FormConfig`, call remote
services, evaluate OPA/Rego, or apply policy decisions.

## Existing Foundation

The current foundation already provides:

- `domain_catalog_release`: immutable source releases by service, tenant and environment.
- `domain_catalog_item`: projected release items such as `context`, `node`, `edge`, `binding`, `alias`, `evidence` and `governance`.
- `domain_knowledge_concept`: curated concept projection.
- `domain_knowledge_alias`: aliases and preferred terms.
- `domain_knowledge_binding`: technical bindings to APIs, DTOs, entities, UI schemas and events.
- `domain_knowledge_relationship`: concept-to-concept relationships, including cross-context relationships.
- `domain_knowledge_evidence`: proof for semantic claims.

Federation v0.1 does not replace these tables. It adds five explicit federation
surfaces around them.

## Core Artifacts

### 1. `domain_source`

Represents a producer of domain knowledge.

Required fields:

| Field | Purpose |
| --- | --- |
| `source_key` | Stable key for the publishing system, for example `praxis-api-quickstart`. |
| `source_type` | `microservice`, `monolith`, `external_system`, `manual_catalog`, `generated` or `federated`. |
| `service_key` | Runtime service key used by catalog releases. |
| `service_name` | Human-friendly service name. |
| `tenant_id` | Tenant scope. Nullable only for global catalogs. |
| `environment` | Environment scope such as `dev`, `staging`, `prod`. |
| `semantic_owner` | Business owner of the published meaning. |
| `technical_owner` | Team or service owner accountable for publication. |
| `trust_level` | `authoritative`, `curated`, `generated`, `experimental` or `untrusted`. |
| `status` | `active`, `deprecated`, `retired` or `blocked`. |
| `latest_release_id` | Optional pointer to the latest accepted `domain_catalog_release`. |
| `evidence` | JSON proof, repository links, schema refs or review notes. |

Example:

```json
{
  "sourceKey": "praxis-api-quickstart",
  "sourceType": "microservice",
  "serviceKey": "praxis-api-quickstart",
  "serviceName": "Praxis API Quickstart",
  "tenantId": "default",
  "environment": "prod",
  "semanticOwner": "RH",
  "technicalOwner": "Platform",
  "trustLevel": "authoritative",
  "status": "active",
  "latestReleaseKey": "domain-catalog:human-resources.folhas-pagamento:v2026-04-21"
}
```

### 2. `domain_context`

Represents a bounded context as a governable domain surface published by a
source.

Required fields:

| Field | Purpose |
| --- | --- |
| `context_key` | Stable bounded context key, for example `human-resources`. |
| `source_key` | Publishing `domain_source.source_key`. |
| `context_type` | `bounded_context`, `subdomain`, `capability`, `external_context` or `federated_context`. |
| `label` | Human-friendly context label. |
| `description` | Short business description of the context boundary. |
| `semantic_owner` | Business owner accountable for meaning. |
| `technical_owner` | Team or service owner accountable for implementation. |
| `tenant_id` | Tenant scope. Nullable only for global catalogs. |
| `environment` | Environment scope such as `dev`, `staging`, `prod`. |
| `status` | `candidate`, `active`, `deprecated`, `blocked` or `retired`. |
| `latest_release_key` | Optional pointer to the latest accepted domain catalog release for this context. |
| `evidence` | JSON proof, repository links, schema refs or review notes. |

Example:

```json
{
  "contextKey": "human-resources",
  "sourceKey": "praxis-api-quickstart",
  "contextType": "bounded_context",
  "label": "Human Resources",
  "description": "People, employment lifecycle and payroll concepts.",
  "semanticOwner": "RH",
  "technicalOwner": "Platform",
  "tenantId": "default",
  "environment": "prod",
  "status": "active",
  "latestReleaseKey": "domain-catalog:human-resources.folhas-pagamento:v2026-04-21"
}
```

### 3. `domain_context_relationship`

Represents a relationship between bounded contexts, not just between semantic
nodes.

Required fields:

| Field | Purpose |
| --- | --- |
| `relationship_key` | Stable relationship identifier. |
| `source_context_key` | Context that depends on or references another context. |
| `target_context_key` | Context being referenced, depended on or mapped to. |
| `relationship_type` | `references`, `depends_on`, `uses`, `publishes_to`, `subscribes_to`, `shared_kernel`, `anti_corruption_layer`, `customer_supplier`, `conformist`, `open_host_service`, `separate_ways`. |
| `contract_key` | Optional `domain_contract.contract_key` proving how the relationship happens. |
| `direction` | `source_to_target`, `target_to_source` or `bidirectional`. |
| `ownership` | `source_owned`, `target_owned`, `shared`, `external` or `unknown`. |
| `confidence` | Numeric confidence from `0.0` to `1.0`. |
| `status` | `candidate`, `active`, `deprecated`, `blocked`, `conflict` or `rejected`. |
| `evidence` | JSON evidence and source references. |

Examples:

```json
{
  "relationshipKey": "human-resources.funcionarios.references.security.usuarios",
  "sourceContextKey": "human-resources",
  "targetContextKey": "security",
  "relationshipType": "references",
  "contractKey": "security.users.lookup.v1",
  "direction": "source_to_target",
  "ownership": "target_owned",
  "confidence": 0.95,
  "status": "active"
}
```

```json
{
  "relationshipKey": "operations.missoes.uses.assets.veiculos",
  "sourceContextKey": "operations",
  "targetContextKey": "assets",
  "relationshipType": "uses",
  "contractKey": "assets.vehicle-allocation-changed.v1",
  "direction": "bidirectional",
  "ownership": "shared",
  "confidence": 0.9,
  "status": "active"
}
```

### 4. `domain_contract`

Represents the concrete integration contract between contexts/services.

Required fields:

| Field | Purpose |
| --- | --- |
| `contract_key` | Stable contract key. |
| `contract_type` | `rest_endpoint`, `openapi_operation`, `event_schema`, `asyncapi_message`, `shared_identifier`, `lookup_option_source`, `workflow_action`, `external_system`, `policy_dependency`. |
| `provider_source_key` | Source that owns the contract. |
| `provider_context_key` | Context that publishes the contract. |
| `consumer_context_key` | Optional known consumer context. |
| `resource_key` | Optional domain resource the contract exposes. |
| `operation_key` | Optional operation, event, lookup or action name. |
| `schema_ref` | JSON schema, OpenAPI pointer, AsyncAPI pointer or DTO reference. |
| `compatibility` | `stable`, `backward_compatible`, `breaking`, `experimental`. |
| `visibility` | `public`, `internal`, `restricted`, `deny_for_llm`. |
| `status` | `candidate`, `active`, `deprecated`, `blocked` or `retired`. |
| `evidence` | JSON evidence proving the contract exists. |

Example:

```json
{
  "contractKey": "assets.vehicle-allocation-changed.v1",
  "contractType": "event_schema",
  "providerSourceKey": "assets-service",
  "providerContextKey": "assets",
  "consumerContextKey": "operations",
  "resourceKey": "assets.veiculos",
  "operationKey": "VehicleAllocationChanged",
  "schemaRef": "asyncapi://assets-service/events/VehicleAllocationChanged/v1",
  "compatibility": "stable",
  "visibility": "internal",
  "status": "active"
}
```

### 5. `domain_resolution`

Represents semantic resolution between terms, concepts and context-local names.

Required fields:

| Field | Purpose |
| --- | --- |
| `resolution_key` | Stable resolution identifier. |
| `source_concept_key` | Local concept or node key. |
| `target_concept_key` | Canonical, federated or target concept key. |
| `source_context_key` | Context of the source concept. |
| `target_context_key` | Context of the target concept. |
| `resolution_type` | `same_as`, `equivalent_to`, `broader_than`, `narrower_than`, `maps_to`, `local_projection_of`, `conflicts_with`. |
| `confidence` | Numeric confidence from `0.0` to `1.0`. |
| `status` | `candidate`, `review_required`, `approved`, `rejected`, `conflict`. |
| `review_owner` | Human/team responsible for semantic approval. |
| `evidence` | JSON evidence explaining the resolution. |

Examples:

```json
{
  "resolutionKey": "hr.funcionario.same_as.security.user.employee",
  "sourceConceptKey": "human-resources.funcionario",
  "targetConceptKey": "security.user.employee",
  "sourceContextKey": "human-resources",
  "targetContextKey": "security",
  "resolutionType": "same_as",
  "confidence": 0.82,
  "status": "review_required",
  "reviewOwner": "RH"
}
```

```json
{
  "resolutionKey": "hr.colaborador.conflicts_with.procurement.vendor-collaborator",
  "sourceConceptKey": "human-resources.colaborador",
  "targetConceptKey": "procurement.vendor-collaborator",
  "sourceContextKey": "human-resources",
  "targetContextKey": "procurement",
  "resolutionType": "conflicts_with",
  "confidence": 0.91,
  "status": "conflict",
  "reviewOwner": "Enterprise Architecture"
}
```

## Federated Release

A federated release is a read model composed from accepted source releases and
validated federation records.

Recommended release key format:

```text
domain-federation:<tenant>:<environment>:v<timestamp-or-semver>
```

The federated release should include:

- source release references;
- accepted `domain_source` records;
- accepted `domain_context` records;
- accepted context relationships;
- accepted contracts;
- accepted or reviewable resolutions;
- validation report;
- visibility/redaction decisions for LLM context retrieval.

The federated release must be immutable. Corrections create a new federated
release.

## Dry-Run Validation v0.1

Current endpoint shape:

```http
POST /api/praxis/config/domain-federation/dry-run
```

Behavior:

- accepts a `DomainFederationValidationRequest` envelope;
- returns `200 OK` with `DomainFederationValidationReport`;
- uses `X-Tenant-ID` and `X-Env` as fallback scope when the envelope omits them;
- does not persist anything.

## Ingestion v0.1

Current endpoint shape:

```http
POST /api/praxis/config/domain-federation/ingest
```

Current behavior:

- accepts the same `DomainFederationValidationRequest` envelope used by `/dry-run`;
- requires `dryRun=true`;
- returns validation plus per-context retrieval previews in one response;
- does not persist anything yet.

Preview behavior:

- each `domain_context` attempts a preview using its publishing source `serviceKey`;
- preview query defaults to the context label, falling back to `contextKey`;
- preview failures are returned per context instead of aborting the whole dry-run.

Future input should also accept:

- source release keys;
- optional source filters by `serviceKey`, `contextKey`, tenant and environment;
- curated relationship/contract/resolution payloads.

Ingestion steps:

1. Load latest or specified `domain_catalog_release` records.
2. Validate `domain_source` records against releases.
3. Validate `domain_context` records against known sources.
4. Validate relationship endpoints against known contexts.
5. Validate contracts against source/context ownership.
6. Validate resolutions against known concepts/aliases.
7. Check governance and AI visibility constraints.
8. Produce a validation report.
9. Persist only if the report has no blocking errors.
10. Generate a federated release snapshot.

## Query v0.1

Current endpoint shape:

```http
GET /api/praxis/config/domain-federation/context
```

Current filters:

| Filter | Purpose |
| --- | --- |
| `serviceKey` | Restrict to one publishing service. Omit for federated latest-release projection. |
| `resourceKey` | Restrict to one resource or concept neighborhood. |
| `contextKey` | Restrict to one source context. |
| `itemType` | Catalog item type filter. Defaults to `node`. |
| `nodeType` | Optional node type filter for the context projection. |
| `relationshipType` | Restrict relationship rows by edge type. |
| `q` | Semantic or lexical query. |
| `limit` | Maximum context and relationship items returned. |
| `policyProfile` | Named runtime policy profile: `explanation`, `authoring`, `compliance_review` or `diagnostics`. Defaults to `explanation`. |
| `minConfidence` | Optional runtime retrieval threshold override. |
| `includeDenied` | Optional override to include `aiUsage.visibility=deny` items for privileged diagnostics. |
| `includeLowConfidence` | Optional override to include items below `minConfidence`. |

Current behavior:

- projects context from `domain_catalog_release` and `domain_catalog_item`;
- returns context items plus relationship rows in one envelope;
- marks whether the result is federated or service-scoped;
- reuses catalog retrieval guidance and adds federation-specific caveats;
- applies the federation retrieval policy before returning relationships and context;
- does not yet materialize `domain_contract`, `domain_resolution` or final redaction decisions.

## Retrieval Policy v0.1

Current policy behavior:

- excludes any returned item with `aiUsage.visibility=deny`;
- preserves catalog-level masking and `summarize_only` summaries;
- reports governed-summary counts;
- reports low-confidence items below `0.7`;
- includes policy decisions in the federated context response.

Runtime options:

- `policyProfile=explanation`: `minConfidence=0.7`, `includeDenied=false`, `includeLowConfidence=true`;
- `policyProfile=authoring`: `minConfidence=0.8`, `includeDenied=false`, `includeLowConfidence=false`;
- `policyProfile=compliance_review`: `minConfidence=0.9`, `includeDenied=false`, `includeLowConfidence=false`;
- `policyProfile=diagnostics`: `minConfidence=0.0`, `includeDenied=true`, `includeLowConfidence=true`;
- `minConfidence`: optional override, clamped to `0.0..1.0`;
- `includeDenied`: optional override;
- `includeLowConfidence`: optional override.

This is intentionally conservative. It is not yet a full authorization engine.
It is the first LLM-facing retrieval guard that prevents obviously denied
semantic content from leaking through federated projections.

## Validation Rules

Blocking validation errors:

- relationship points to unknown source or target context;
- contract points to unknown provider source/context;
- relationship requires a contract but `contract_key` is missing or unknown;
- resolution points to unknown concepts;
- source has no semantic or technical owner;
- source trust is `untrusted` and status is `active`;
- restricted/deny data would be exposed to an LLM context;
- context dependency violates configured governance policy;
- relationship crosses tenants or environments without explicit allowance.

Review-required validation warnings:

- low-confidence resolution;
- conflicting aliases across contexts;
- ambiguous preferred term;
- generated source without human review;
- contract compatibility is `experimental`;
- same target context reachable through multiple inconsistent relationship types.

## Mapping To Existing Tables

v0.1 can be implemented without immediately replacing the current schema:

| Federation artifact | Existing foundation | Future first-class table |
| --- | --- | --- |
| `domain_source` | `domain_catalog_release.service_key`, `raw_payload.service`, evidence payloads | `domain_source` |
| `domain_context` | `domain_catalog_item.item_type=context`, `domain_knowledge_concept.node_type=bounded_context` | `domain_context` |
| `domain_context_relationship` | `domain_catalog_item.item_type=edge`, `domain_knowledge_relationship.cross_context=true` | `domain_context_relationship` |
| `domain_contract` | `domain_catalog_item.item_type=binding`, `domain_knowledge_binding` | `domain_contract` |
| `domain_resolution` | `domain_knowledge_relationship` with `same_as`, `maps_to`, `broader_than`, `narrower_than` | `domain_resolution` |

The first implementation may project these artifacts from existing
`domain_catalog_item` and `domain_knowledge_*` rows, then persist first-class
tables in a later migration once the contract stabilizes.

## Persistent Read Model Plan

The next architectural increment is to persist federation records as governed
read-only data. This should not introduce rule execution or LLM write access.
It should make the validated federation map durable, queryable and auditable.

### Migration Boundary

Use a new Flyway migration after the current domain catalog/shared-rule layer.
The migration should be additive only:

- do not alter `domain_catalog_release`;
- do not alter `domain_catalog_item`;
- do not alter `domain_knowledge_*`;
- do not materialize rules into `FormConfig`;
- do not execute remote contracts.

Recommended table set:

1. `domain_federation_release`;
2. `domain_source`;
3. `domain_context`;
4. `domain_context_relationship`;
5. `domain_contract`;
6. `domain_resolution`.

### `domain_federation_release`

Purpose: immutable publication envelope for one accepted federation snapshot.

Recommended columns:

| Column | Notes |
| --- | --- |
| `id` | UUID primary key. |
| `release_key` | Unique stable key, for example `domain-federation:tenant:env:v2026-04-23T16:00Z`. |
| `tenant_id` | Tenant scope. |
| `environment` | Runtime environment. |
| `status` | `candidate`, `active`, `superseded`, `blocked`, `retired`. |
| `source_release_ids` | JSONB array of accepted source `domain_catalog_release.id` values. |
| `validation_report` | JSONB validation report used to approve the release. |
| `payload_hash` | Hash of the normalized federation payload. |
| `created_by` | Human, system or automation actor. |
| `created_at` | Creation timestamp. |
| `activated_at` | Nullable activation timestamp. |

Indexes:

- unique `(tenant_id, environment, release_key)`;
- `(tenant_id, environment, status, created_at desc)`;
- unique active release per `(tenant_id, environment)` where `status='active'`.

### Shared Persistence Columns

Each first-class federation artifact should include these common fields:

| Column | Notes |
| --- | --- |
| `id` | UUID primary key. |
| `federation_release_id` | FK to `domain_federation_release(id)` with cascade delete for candidate cleanup. |
| `tenant_id` | Denormalized for filters and safety checks. |
| `environment` | Denormalized for filters and safety checks. |
| `status` | Lifecycle state for the record. |
| `confidence` | Nullable where confidence is not meaningful. |
| `evidence` | JSONB proof, source refs and review notes. |
| `created_at` | Creation timestamp. |

All lookup keys should be unique within one federation release, tenant and
environment. Do not make business labels unique.

### Table-Specific Identity

Recommended natural keys:

| Table | Natural key |
| --- | --- |
| `domain_source` | `(federation_release_id, source_key)` |
| `domain_context` | `(federation_release_id, context_key)` |
| `domain_context_relationship` | `(federation_release_id, relationship_key)` |
| `domain_contract` | `(federation_release_id, contract_key)` |
| `domain_resolution` | `(federation_release_id, resolution_key)` |

Recommended FK-like constraints:

- `domain_context.source_key` must reference a `domain_source.source_key` in the same release.
- `domain_context_relationship.source_context_key` and `target_context_key` must reference contexts in the same release.
- `domain_context_relationship.contract_key`, when present, must reference a contract in the same release.
- `domain_contract.provider_source_key` must reference a source in the same release.
- `domain_contract.provider_context_key` and `consumer_context_key`, when present, must reference contexts in the same release.
- `domain_resolution.source_context_key` and `target_context_key` must reference contexts in the same release.

PostgreSQL cannot enforce these composite key references cleanly if the source
columns are not physically unique, so the migration should either define
composite unique constraints for those keys or keep database FKs minimal and
enforce release-local referential integrity in the validator. The conservative
starting point is validator-enforced integrity plus unique indexes.

### Persistence Flow

The first persisted flow should remain validation-first:

1. Receive `DomainFederationValidationRequest`.
2. Normalize tenant/environment from headers and payload.
3. Validate all references, owners, trust, visibility and confidence rules.
4. Compute a deterministic `payload_hash`.
5. If `dryRun=true`, return report and previews without writes.
6. If persistence is enabled and validation has no blocking errors, create a `candidate` `domain_federation_release`.
7. Insert source, context, relationship, contract and resolution rows for that release.
8. Activate the release only through an explicit activation operation.
9. Supersede the previous active release for the same tenant/environment.

The first implementation should keep activation explicit. That prevents an LLM
or automation from accidentally making a candidate federation map authoritative.

### Query Flow

The query endpoint should prefer active persisted federation releases when they
exist, then fall back to the current projected behavior from
`domain_catalog_release` and `domain_catalog_item`.

Recommended read order:

1. Locate active `domain_federation_release` for tenant/environment.
2. Apply service/context/resource filters.
3. Join federation relationships/contracts/resolutions by release-local keys.
4. Apply retrieval policy before serializing any LLM-facing response.
5. Include release metadata and policy report in the response.

Fallback behavior should be explicit in the response:

```json
{
  "sourceMode": "persisted_federation"
}
```

or:

```json
{
  "sourceMode": "catalog_projection_fallback"
}
```

### Minimal Endpoint Additions

Do not add authoring yet. Add only operational read/validation endpoints:

| Endpoint | Purpose |
| --- | --- |
| `POST /api/praxis/config/domain-federation/ingest?dryRun=false` | Persist a candidate release after validation. |
| `POST /api/praxis/config/domain-federation/releases/{releaseKey}/activate` | Explicitly activate a candidate release. |
| `GET /api/praxis/config/domain-federation/releases` | List releases by tenant/environment/status. |
| `GET /api/praxis/config/domain-federation/releases/{releaseKey}/validation` | Return validation report and evidence. |

`dryRun=false` must be guarded by configuration at first, for example:

```properties
praxis.domain-federation.persistence.enabled=false
```

### LLM Safety Rules

Persisted federation data must remain LLM-safe by construction:

- Never persist or return raw secrets in `evidence`.
- Treat `visibility=deny_for_llm` contracts as non-returnable outside diagnostics.
- Do not return low-confidence resolutions in `authoring` or `compliance_review`.
- Do not cross tenant/environment boundaries unless an explicit federation release includes both scopes.
- Include source mode, release key and policy report in every LLM-facing context response.

### Implementation Slice

Recommended first implementation slice:

1. Add the Flyway migration and JPA entities/repositories.
2. Add persistence service behind `praxis.domain-federation.persistence.enabled`.
3. Extend `/ingest` so `dryRun=false` persists only candidate releases.
4. Add release list and validation read endpoints.
5. Keep `/context` behavior unchanged until candidate persistence is proven.

The second slice can switch `/context` to prefer active persisted releases.

Current implementation status:

- Flyway migration added in `V21__create_domain_federation_read_model.sql`.
- The migration creates `domain_federation_release`, `domain_source`,
  `domain_context`, `domain_context_relationship`, `domain_contract` and
  `domain_resolution`.
- JPA entities and repositories have been added for the same six persistence
  surfaces.
- `POST /api/praxis/config/domain-federation/ingest?dryRun=false` can now
  persist a validated candidate release when
  `praxis.domain-federation.persistence.enabled=true`.
- `GET /api/praxis/config/domain-federation/releases` and
  `GET /api/praxis/config/domain-federation/releases/{releaseKey}/validation`
  expose read-only release audit data.
- `POST /api/praxis/config/domain-federation/releases/{releaseKey}/activate`
  explicitly activates a candidate release and supersedes the previous active
  release for the same tenant/environment.
- Database foreign keys are intentionally minimal in this first slice:
  release ownership is enforced by the database, while release-local semantic
  references remain validator-enforced.
- No remote database execution has been performed by this change.

## Example Scenario

Human Resources publishes:

```text
source: praxis-api-quickstart
context: human-resources
resource: human-resources.folhas-pagamento
owner: RH
release: domain-catalog:human-resources.folhas-pagamento:v2026-04-21
```

Operations publishes:

```text
source: operations-service
context: operations
resource: operations.missoes
owner: Operacoes
release: domain-catalog:operations.missoes:v2026-04-22
```

Assets publishes:

```text
source: assets-service
context: assets
resource: assets.veiculos
owner: Frota
release: domain-catalog:assets.veiculos:v2026-04-22
```

Federation records:

```text
operations.missoes uses assets.veiculos through assets.vehicle-allocation-changed.v1
human-resources.funcionarios references security.usuarios through security.users.lookup.v1
human-resources.funcionario same_as security.user.employee, review_required
```

LLM context retrieval for `operations.missoes` should return the mission
context, the vehicle dependency, the contract evidence, and any visibility
constraints. It should not infer extra relationships from similar labels.

## Implementation Sequence

1. Add schema/documentation for the five artifacts.
2. Add read-only DTOs and validators.
3. Add dry-run validation endpoint.
4. Add query endpoint returning an LLM-safe federated context.
5. Add persistence tables only after validator semantics are stable.
6. Allow LLM proposal envelopes only after validation and review workflows exist.

## Non-Goals For v0.1

- No executable rule engine.
- No OPA/Rego or DMN integration.
- No automatic cross-service calls.
- No LLM-authored changes applied directly.
- No automatic canonicalization of business terms.
- No hidden inference from labels, aliases or embeddings without explicit evidence.
