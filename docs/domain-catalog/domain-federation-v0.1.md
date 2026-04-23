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

Federation v0.1 does not replace these tables. It adds four explicit federation
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

### 2. `domain_context_relationship`

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

### 3. `domain_contract`

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

### 4. `domain_resolution`

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
- accepted context relationships;
- accepted contracts;
- accepted or reviewable resolutions;
- validation report;
- visibility/redaction decisions for LLM context retrieval.

The federated release must be immutable. Corrections create a new federated
release.

## Ingestion v0.1

Future endpoint shape:

```http
POST /api/praxis/config/domain-federation/ingest
```

Input should accept:

- source release keys;
- optional source filters by `serviceKey`, `contextKey`, tenant and environment;
- curated relationship/contract/resolution payloads;
- `dryRun=true` for validation without persistence.

Ingestion steps:

1. Load latest or specified `domain_catalog_release` records.
2. Validate `domain_source` records against releases.
3. Validate relationship endpoints against known contexts.
4. Validate contracts against source/context ownership.
5. Validate resolutions against known concepts/aliases.
6. Check governance and AI visibility constraints.
7. Produce a validation report.
8. Persist only if the report has no blocking errors.
9. Generate a federated release snapshot.

## Query v0.1

Future endpoint shape:

```http
GET /api/praxis/config/domain-federation/context
```

Recommended filters:

| Filter | Purpose |
| --- | --- |
| `serviceKey` | Restrict to one publishing service. |
| `contextKey` | Restrict to one source context. |
| `resourceKey` | Restrict to one resource or concept neighborhood. |
| `targetContextKey` | Include relationships toward a target context. |
| `relationshipType` | Restrict by context relationship type. |
| `contractKey` | Explain one contract and related contexts. |
| `intent` | `authoring`, `explanation`, `impact_analysis`, `compliance_review`, `troubleshooting`. |
| `q` | Semantic or lexical query. |

The response must be LLM-safe by default:

- exclude `ai_visibility=deny`;
- mask or summarize restricted concepts;
- include source/confidence/evidence;
- include conflicts instead of silently resolving them;
- include only contracts visible to the caller.

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
| `domain_context_relationship` | `domain_catalog_item.item_type=edge`, `domain_knowledge_relationship.cross_context=true` | `domain_context_relationship` |
| `domain_contract` | `domain_catalog_item.item_type=binding`, `domain_knowledge_binding` | `domain_contract` |
| `domain_resolution` | `domain_knowledge_relationship` with `same_as`, `maps_to`, `broader_than`, `narrower_than` | `domain_resolution` |

The first implementation may project these artifacts from existing
`domain_catalog_item` and `domain_knowledge_*` rows, then persist first-class
tables in a later migration once the contract stabilizes.

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

1. Add schema/documentation for the four artifacts.
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
