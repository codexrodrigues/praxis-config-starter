# RFC: Federated Domain Catalog

Status: Draft  
Date: 2026-04-21  
Owner: Praxis Platform

## 1. Problem

Praxis already has strong metadata for API contracts, UI schemas, component capabilities, AI registry assets and agentic authoring manifests. This is enough for an LLM to understand many technical surfaces:

- which APIs exist;
- which DTO fields exist;
- which UI components can be configured;
- which component operations are allowed;
- which schemas feed forms, tables and actions.

It is not enough for an LLM, analyst or future AI developer to safely understand the business domain without reading source code.

Important business meaning often lives deeper than controllers and DTOs:

- service methods encode workflow transitions;
- entities encode domain vocabulary and relationships;
- repositories expose state snapshots and facts;
- option sources encode cross-resource references;
- actions and surfaces expose business operations;
- compliance and privacy constraints are not first-class domain metadata.

The future target for Praxis is agent-operable enterprise systems: systems written, interpreted, changed and governed by humans assisted by LLMs, and often by LLM agents directly. Therefore, domain knowledge must be explicit, versioned, queryable, auditable and patchable through governed contracts.

## 2. Goals

Build a foundation for a runtime, federated, AI-native domain map.

The first goal is not to execute rules. The first goal is to let Praxis answer:

- what business concepts exist;
- which bounded context owns each concept;
- which fields, states, actions, events and metrics belong to each concept;
- how concepts relate across microservices;
- which API/DTO/entity/service/UI artifacts implement each concept;
- which aliases, translations and business terms point to each concept;
- what evidence supports each statement;
- which source service published it;
- which release is active for a tenant/environment;
- what an LLM is allowed to change, and through which patch operations.

## 3. Non-Goals

This RFC does not define a rule engine.

Out of scope for the first implementation:

- OPA/Rego policy execution;
- DMN decision execution;
- workflow engine replacement;
- full backend validation generation;
- automatic rule materialization to Angular and Java;
- automatic code modifications;
- production-grade governance UI.

The catalog should be able to reference future rule/policy/workflow engines, but must not become a generic engine itself.

## 4. Existing Praxis Pieces

The design must preserve the current architecture:

- `api_metadata` remains the technical/API catalog.
- `ai_registry` remains the registry for component definitions, templates and AI artifacts.
- `/schemas/filtered` remains the structural schema contract.
- `/schemas/catalog` remains the documentary/API contract.
- `@ApiResource`, `@UISchema`, `@WorkflowAction` and `@UiSurface` remain valid and unchanged.
- `praxis-dynamic-form` continues to execute materialized `formRules`.

The new domain catalog is an additional layer that binds to these pieces.

## 5. Core Design Principles

1. Everything important becomes a node.
2. Every important relationship becomes an edge.
3. Every claim has evidence.
4. Every node can have aliases.
5. Every node can have technical bindings.
6. Source service releases are immutable.
7. Federated releases are immutable.
8. Cross-service meaning is explicit, not inferred silently.
9. LLMs never write tables directly; they propose structured patches.
10. The catalog must support human and AI authors.
11. Annotations are useful as code anchors, but declarative artifacts are better for rich AI editing.
12. Rules, policies and workflows reference the domain map; they are not the domain map.

## 6. Logical Model

The catalog has three layers:

1. Source layer: each service publishes its local domain view.
2. Federated layer: Praxis connects local contexts into an enterprise map.
3. Authoring layer: humans/LLMs propose governed changes.

### 6.1 Domain Sources

`domain_source` identifies a producer of domain metadata.

Examples:

- `hr-service`
- `operations-service`
- `assets-service`
- `procurement-service`
- `praxis-api-quickstart`

Important fields:

- `source_key`
- `source_type`: `microservice`, `monolith`, `external_system`, `manual_catalog`, `generated`
- `namespace`
- `service_name`
- `base_url`
- `environment`
- `owner_team`
- `lifecycle`
- `status`

### 6.2 Catalog Releases

`domain_catalog_release` version-controls snapshots.

There are two release types:

- source release: one service's published catalog;
- federated release: the enterprise-composed catalog.

Important fields:

- `release_key`
- `version`
- `release_type`
- `source_id`
- `tenant_id`
- `environment`
- `status`
- `checksum`
- `created_at`
- `activated_at`

Releases are immutable. Publishing creates a new release.

### 6.3 Contexts

`domain_context` represents a bounded context, subdomain, system or external context.

Examples:

- `human-resources.payroll`
- `operations.mission-control`
- `assets.fleet`
- `procurement.sourcing`

Important fields:

- `context_key`
- `context_type`: `bounded_context`, `subdomain`, `system`, `application`, `external_system`, `legacy_system`
- `label`
- `description`
- `owner_team`
- `language`
- `status`

### 6.4 Context Relationships

`domain_context_relationship` describes how bounded contexts relate.

Relationship types may follow DDD context mapping:

- `open_host_service`
- `published_language`
- `customer_supplier`
- `conformist`
- `anti_corruption_layer`
- `shared_kernel`
- `partnership`
- `separate_ways`

This is required for multi-microservice domains. Cross-context meaning must be explicit.

### 6.5 Contracts

`domain_contract` represents published contracts between contexts and consumers.

Contract types:

- `rest_api`
- `openapi_operation`
- `event`
- `asyncapi_message`
- `graphql`
- `schema`
- `lookup`
- `published_language`

Contracts allow Praxis to connect domain meaning to REST APIs, events, lookups and future message-based integrations.

### 6.6 Nodes

`domain_node` is the universal semantic element.

Node types:

- `domain`
- `bounded_context`
- `concept`
- `field`
- `relationship`
- `state`
- `action`
- `event`
- `process`
- `metric`
- `actor`
- `role`
- `document`
- `technical_asset`
- `policy_placeholder`
- `rule_placeholder`

Each node belongs to a context.

Important identity fields:

- `node_key`: local stable identity;
- `canonical_key`: optional federated identity;
- `local_name`: local context name;
- `label`: human label;
- `description`;
- `status`;
- `curation_status`;
- `payload`.

Do not use labels as identity.

### 6.7 Edges

`domain_edge` links nodes.

Edge types:

- `contains`
- `has_field`
- `has_state`
- `has_action`
- `has_event`
- `has_metric`
- `has_relationship`
- `references`
- `depends_on`
- `computed_from`
- `triggers`
- `allowed_in_state`
- `blocked_in_state`
- `maps_to`
- `same_as`
- `equivalent_to`
- `broader_than`
- `narrower_than`
- `impacts`
- `owned_by`
- `stewarded_by`
- `governed_by`

Cross-context edges must have evidence and may require a contract.

### 6.8 Bindings

`domain_binding` connects semantic nodes to technical artifacts.

Binding types:

- `api_resource`
- `api_operation`
- `dto_class`
- `dto_field`
- `entity_class`
- `entity_field`
- `service_method`
- `repository_projection`
- `workflow_action`
- `ui_surface`
- `ui_schema_field`
- `form_config`
- `table_config`
- `component_capability`
- `event_schema`

Bindings may reference:

- `api_metadata.id`;
- `ai_registry.id`;
- Java class/member;
- DTO/entity field;
- API path/method;
- schema pointer;
- component path.

### 6.9 Aliases

`domain_alias` maps natural language to nodes.

Alias examples:

- `folha` -> `human-resources.payroll.payment`
- `contracheque` -> `human-resources.payroll.payment`
- `holerite` -> `human-resources.payroll.payment`
- `data prevista` -> `human-resources.payroll.payment.paymentDate`

Alias fields:

- `alias`
- `locale`
- `region`
- `business_unit`
- `alias_type`: `synonym`, `abbreviation`, `legacy_name`, `business_slang`, `technical_name`, `misspelling`
- `weight`
- `source`

### 6.10 Resolutions

`domain_resolution` maps local nodes to canonical/federated nodes.

Resolution types:

- `same_as`
- `maps_to`
- `narrower_than`
- `broader_than`
- `reference_to`
- `local_projection_of`

Resolution statuses:

- `auto_resolved`
- `needs_review`
- `approved`
- `rejected`
- `conflict`

Canonical identity should be optional. Do not force enterprise-wide canonicalization before the business actually has consensus.

### 6.11 Evidence

`domain_evidence` records why Praxis believes a semantic statement is true.

Evidence types:

- `annotation`
- `declarative_artifact`
- `openapi`
- `dto_schema`
- `ui_schema`
- `workflow_action`
- `ui_surface`
- `service_annotation`
- `manual_curated`
- `llm_suggested`
- `human_approved`

Evidence should include confidence and source references.

### 6.12 Embeddings

`domain_embedding` stores searchable chunks for RAG.

Chunk kinds:

- `label`
- `description`
- `aliases`
- `business_summary`
- `technical_binding`
- `full_context`

Embeddings should be separate from the main node row so multiple search perspectives can coexist.

### 6.13 Governance Annotations

`domain_governance_annotation` annotates nodes, edges, bindings or contracts.

Annotation types:

- `data_classification`
- `privacy`
- `security`
- `retention`
- `ai_usage`
- `export_policy`
- `masking`
- `audit`
- `consent`
- `quality`
- `lineage`

Framework examples:

- `LGPD`
- `GDPR`
- `HIPAA`
- `SOX`
- `PCI`
- `internal`

AI usage must be first-class:

- `allowedInPrompt`
- `allowedForTraining`
- `allowedForRag`
- `redaction`
- `summarizationAllowed`

## 7. Authoring Layer

Praxis must support LLMs as future maintainers. Therefore, changes must be structured.

### 7.1 Domain Authoring Manifest

`domain_authoring_manifest` defines what an LLM may change.

Initial operation examples:

- `domain.alias.add`
- `domain.alias.remove`
- `domain.description.update`
- `domain.edge.propose`
- `domain.binding.propose`
- `domain.resolution.propose`
- `domain.governance.privacy.set`
- `domain.governance.aiUsage.set`

Each operation defines:

- input schema;
- preconditions;
- validators;
- affected paths;
- risk level;
- review requirements.

### 7.2 Domain Change Proposal

LLMs do not update the catalog directly. They create proposals.

Proposal statuses:

- `draft`
- `validated`
- `needs_review`
- `approved`
- `rejected`
- `published`
- `superseded`

### 7.3 Domain Change Patch

A patch is an ordered list of operations.

Example:

```json
{
  "schemaVersion": "1.0",
  "baseReleaseKey": "enterprise-domain",
  "baseVersion": 31,
  "intent": "Adicionar holerite como alias de folha de pagamento",
  "operations": [
    {
      "operationId": "domain.alias.add",
      "targetKey": "human-resources.payroll.payment",
      "value": {
        "alias": "holerite",
        "locale": "pt-BR",
        "aliasType": "synonym"
      }
    }
  ]
}
```

### 7.4 Validation Result

Validators should include:

- `schema-valid`
- `base-release-exists`
- `operation-allowed`
- `key-format-valid`
- `node-exists`
- `edge-type-valid`
- `binding-target-exists`
- `api-resource-exists`
- `api-field-exists`
- `alias-not-conflicting`
- `context-exists`
- `cross-context-edge-has-contract`
- `system-of-record-not-violated`
- `canonical-resolution-not-cyclic`
- `deprecated-node-not-used-as-primary`

### 7.5 Impact Snapshot

Impact snapshots explain what a patch may affect:

- concepts;
- fields;
- APIs;
- forms;
- tables;
- rules;
- policies;
- reports;
- downstream contexts;
- LLM prompt eligibility.

## 8. Declarative Artifacts and Annotations

Praxis should use both annotations and declarative files.

Annotations are good for code anchors:

```java
@DomainConceptRef("human-resources.payroll.payment")
@ApiResource(...)
public class FolhasPagamentoController {}
```

Declarative files are better for rich AI editing:

```yaml
schemaVersion: praxis.domain.concept/v1
nodeKey: human-resources.payroll.payment
label: Folha de pagamento
aliases:
  - folha
  - contracheque
  - holerite
description: Competência mensal de pagamento de um funcionário.
ownerTeam: people-platform
status: active
```

Recommended approach:

- annotation-first for bootstrap and binding;
- artifact-first for rich semantic authoring;
- database for active runtime releases and governance.

## 9. Runtime Contracts

Each service should publish:

```text
GET /schemas/domain
```

The config service should ingest:

```text
POST /api/praxis/config/domain-catalog/ingest
```

The config service should query:

```text
GET  /api/praxis/config/domain-catalog/search
GET  /api/praxis/config/domain-catalog/nodes/{nodeKey}
GET  /api/praxis/config/domain-catalog/resources/{resourceKey}
GET  /api/praxis/config/domain-catalog/contexts
POST /api/praxis/config/domain-catalog/resolve-intent
```

### 9.1 `/schemas/domain` Contract v0.1

The first contract must be intentionally small, deterministic and friendly to both machines and
LLMs. It should expose domain meaning already known by the runtime, without requiring an LLM to read
Java, TypeScript or SQL code.

Recommended endpoint:

```text
GET /schemas/domain
GET /schemas/domain?contextKey=human-resources
GET /schemas/domain?resourceKey=human-resources.folhas-pagamento
```

Response:

```json
{
  "schemaVersion": "praxis.domain-catalog/v0.1",
  "service": {
    "serviceKey": "praxis-api-quickstart",
    "name": "Praxis API Quickstart",
    "version": "0.1.0"
  },
  "release": {
    "releaseKey": "praxis-api-quickstart:2026-04-21T10:30:00Z",
    "generatedAt": "2026-04-21T10:30:00Z",
    "sourceHash": "sha256:..."
  },
  "contexts": [],
  "nodes": [],
  "edges": [],
  "bindings": [],
  "aliases": [],
  "evidence": [],
  "governance": []
}
```

Field semantics:

- `schemaVersion`: contract version. It must change only when the shape changes.
- `service`: source service that generated the catalog.
- `release`: immutable generation metadata for idempotent ingestion.
- `contexts`: bounded contexts or modules exposed by the service.
- `nodes`: semantic units such as concepts, fields, states, actions and surfaces.
- `edges`: relationships between semantic units.
- `bindings`: links from semantic units to APIs, DTOs, schemas, fields, actions and UI surfaces.
- `aliases`: business language variants used by analysts and users.
- `evidence`: proof for why the catalog believes a semantic item exists.
- `governance`: optional metadata for privacy, security, AI usage and compliance.

### 9.2 Context Shape

```json
{
  "contextKey": "human-resources",
  "label": "Recursos Humanos",
  "description": "Domínio responsável por folha, eventos e indicadores de RH.",
  "owner": "people-platform",
  "source": "openapi-tag",
  "status": "active"
}
```

Required fields:

- `contextKey`;
- `label`;
- `status`.

Optional fields:

- `description`;
- `owner`;
- `source`;
- `tags`;
- `confidence`.

### 9.3 Node Shape

```json
{
  "nodeKey": "human-resources.folhas-pagamento",
  "contextKey": "human-resources",
  "nodeType": "concept",
  "label": "Folha de pagamento",
  "description": "Registro de competência, valores e ciclo operacional de pagamento.",
  "status": "active",
  "source": "api-resource",
  "confidence": 0.92,
  "metadata": {
    "resourceKey": "human-resources.folhas-pagamento"
  }
}
```

Allowed `nodeType` values for v0.1:

- `concept`: business resource, aggregate, process or important object.
- `field`: business field or attribute.
- `state`: lifecycle state.
- `action`: operation that changes or evaluates business state.
- `surface`: UI/business interaction surface.
- `policy_hint`: non-executable hint about selection, visibility, compliance or eligibility.

Required fields:

- `nodeKey`;
- `contextKey`;
- `nodeType`;
- `label`;
- `status`.

Optional fields:

- `description`;
- `source`;
- `confidence`;
- `metadata`;
- `tags`;
- `owner`.

Key convention:

```text
<contextKey>.<resource-or-concept>[.<field-or-state-or-action>]
```

Examples:

```text
human-resources.folhas-pagamento
human-resources.folhas-pagamento.estado.programada
human-resources.folhas-pagamento.action.approve-events
human-resources.folhas-pagamento.surface.payment-schedule
procurement.supplier.policy.selectable-status
```

### 9.4 Edge Shape

```json
{
  "edgeKey": "human-resources.folhas-pagamento.action.mark-paid.allowed-state.programada",
  "sourceNodeKey": "human-resources.folhas-pagamento.action.mark-paid",
  "targetNodeKey": "human-resources.folhas-pagamento.estado.programada",
  "edgeType": "allowed_in_state",
  "label": "Permitida quando a folha está programada",
  "confidence": 0.95,
  "evidenceKeys": [
    "evidence:folhas-pagamento-controller:workflow-action:mark-paid"
  ]
}
```

Allowed `edgeType` values for v0.1:

- `has_field`;
- `has_state`;
- `has_action`;
- `has_surface`;
- `allowed_in_state`;
- `blocked_in_state`;
- `uses_concept`;
- `selectable_when`;
- `blocked_when`;
- `derived_from`.

The edge list is the beginning of the semantic graph. It should not execute rules. It should make
the domain navigable and explainable.

### 9.5 Binding Shape

```json
{
  "bindingKey": "binding:human-resources.folhas-pagamento:api-resource",
  "nodeKey": "human-resources.folhas-pagamento",
  "bindingType": "api_resource",
  "target": {
    "resourceKey": "human-resources.folhas-pagamento",
    "path": "/api/human-resources/folhas-pagamento"
  },
  "schemaLinks": [
    {
      "rel": "create.request",
      "href": "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=post&schemaType=request"
    }
  ],
  "evidenceKeys": [
    "evidence:folhas-pagamento-controller:api-resource"
  ]
}
```

Allowed `bindingType` values for v0.1:

- `api_resource`;
- `api_operation`;
- `dto_schema`;
- `dto_field`;
- `workflow_action`;
- `approval_policy`;
- `ui_surface`;
- `option_source`;
- `state_snapshot`;
- `code_anchor`.

Bindings are the bridge between semantic vocabulary and existing Praxis metadata. They allow a LLM
to move from "folha de pagamento" to the exact API operation, DTO field or UI surface without
reading source code.

### 9.6 Alias Shape

```json
{
  "aliasKey": "alias:human-resources.folhas-pagamento:folha",
  "nodeKey": "human-resources.folhas-pagamento",
  "alias": "folha",
  "locale": "pt-BR",
  "source": "curated",
  "confidence": 0.9
}
```

Aliases are essential for analyst and LLM usage. Analysts rarely use exactly the same words as code,
DTOs or database columns.

### 9.7 Evidence Shape

```json
{
  "evidenceKey": "evidence:folhas-pagamento-controller:workflow-action:mark-paid",
  "evidenceType": "annotation",
  "sourceRef": {
    "kind": "java.annotation",
    "className": "com.example.praxis.apiquickstart.hr.controller.FolhasPagamentoController",
    "member": "markPaid",
    "annotation": "WorkflowAction"
  },
  "summary": "A ação mark-paid é declarada por @WorkflowAction e permitida no estado PROGRAMADA.",
  "confidence": 0.98
}
```

Allowed `evidenceType` values for v0.1:

- `annotation`;
- `openapi`;
- `dto_schema`;
- `ui_schema`;
- `option_source`;
- `state_snapshot`;
- `curated`;
- `inferred`.

Anything generated by inference should have lower confidence and be clearly reviewable.

### 9.8 Governance Shape

```json
{
  "governanceKey": "governance:human-resources.folhas-pagamento.field.valorLiquido:privacy",
  "nodeKey": "human-resources.folhas-pagamento.field.valorLiquido",
  "annotationType": "privacy",
  "classification": "personal_financial_data",
  "aiUsage": {
    "allowedInPrompt": false,
    "allowedForRag": true,
    "allowedForTraining": false,
    "redaction": "mask_value"
  },
  "source": "curated",
  "confidence": 0.8
}
```

Governance is optional in the first generator but should be part of the contract from v0.1. Adding
the shape now prevents a later breaking change when LGPD, GDPR, internal compliance and LLM access
control become mandatory.

### 9.9 Payroll Example

Condensed example for `human-resources.folhas-pagamento`:

```json
{
  "schemaVersion": "praxis.domain-catalog/v0.1",
  "service": {
    "serviceKey": "praxis-api-quickstart",
    "name": "Praxis API Quickstart"
  },
  "release": {
    "releaseKey": "praxis-api-quickstart:example",
    "generatedAt": "2026-04-21T10:30:00Z"
  },
  "contexts": [
    {
      "contextKey": "human-resources",
      "label": "Recursos Humanos",
      "status": "active"
    }
  ],
  "nodes": [
    {
      "nodeKey": "human-resources.folhas-pagamento",
      "contextKey": "human-resources",
      "nodeType": "concept",
      "label": "Folha de pagamento",
      "status": "active",
      "source": "api-resource"
    },
    {
      "nodeKey": "human-resources.folhas-pagamento.estado.programada",
      "contextKey": "human-resources",
      "nodeType": "state",
      "label": "Programada",
      "status": "active",
      "source": "workflow-action.allowedStates"
    },
    {
      "nodeKey": "human-resources.folhas-pagamento.action.mark-paid",
      "contextKey": "human-resources",
      "nodeType": "action",
      "label": "Marcar como paga",
      "status": "active",
      "source": "workflow-action"
    }
  ],
  "edges": [
    {
      "edgeKey": "human-resources.folhas-pagamento.has-action.mark-paid",
      "sourceNodeKey": "human-resources.folhas-pagamento",
      "targetNodeKey": "human-resources.folhas-pagamento.action.mark-paid",
      "edgeType": "has_action"
    },
    {
      "edgeKey": "human-resources.folhas-pagamento.action.mark-paid.allowed-state.programada",
      "sourceNodeKey": "human-resources.folhas-pagamento.action.mark-paid",
      "targetNodeKey": "human-resources.folhas-pagamento.estado.programada",
      "edgeType": "allowed_in_state"
    }
  ],
  "bindings": [
    {
      "bindingKey": "binding:human-resources.folhas-pagamento.action.mark-paid:workflow-action",
      "nodeKey": "human-resources.folhas-pagamento.action.mark-paid",
      "bindingType": "workflow_action",
      "target": {
        "path": "/api/human-resources/folhas-pagamento/{id}/actions/mark-paid",
        "method": "POST",
        "actionId": "mark-paid"
      }
    }
  ],
  "aliases": [
    {
      "aliasKey": "alias:human-resources.folhas-pagamento:folha",
      "nodeKey": "human-resources.folhas-pagamento",
      "alias": "folha",
      "locale": "pt-BR",
      "source": "curated"
    }
  ],
  "evidence": [],
  "governance": []
}
```

### 9.10 Extraction Rules For v0.1

The first generator should be deterministic:

- `@ApiResource.resourceKey` becomes a `concept` node.
- OpenAPI tags can become `context` candidates.
- DTO properties become `field` candidates when linked to a resource schema.
- `@WorkflowAction` becomes an `action` node.
- `@WorkflowAction.allowedStates` creates `state` nodes and `allowed_in_state` edges.
- `@UiSurface` becomes a `surface` node.
- `@UiSurface.allowedStates` creates `state` nodes and `allowed_in_state` edges.
- option source selection policies become `policy_hint` nodes plus `selectable_when` or
  `blocked_when` edges.
- every generated item should reference evidence.

The generator should avoid deep source-code analysis in v0.1. Service-level rules such as "paid
payroll cannot be rescheduled" can be represented later through annotations, curated artifacts or a
safe authoring proposal.

## 10. Integration With AI Context

`AiContextService` should eventually include `domainContext` alongside:

- component definition;
- template;
- schema context;
- runtime current state.

The domain context should be a focused context pack, not a full dump.

Example:

```json
{
  "concept": {
    "key": "human-resources.payroll.payment",
    "label": "Folha de pagamento",
    "aliases": ["folha", "contracheque", "holerite"]
  },
  "fields": [],
  "states": [],
  "actions": [],
  "bindings": [],
  "relatedContexts": []
}
```

## 11. MVP Proposal

### MVP 1: Read Path

Implement:

- `domain_source`
- `domain_catalog_release`
- `domain_context`
- `domain_node`
- `domain_edge`
- `domain_binding`
- `domain_alias`
- `domain_evidence`
- basic ingest endpoint;
- basic search endpoint;
- `/schemas/domain` generation from current metadata;
- simple RAG chunks.

Do not implement rule execution.

### MVP 2: Safe Write Path

Implement:

- domain authoring manifest;
- change proposals;
- change patches;
- validation results;
- `domain.alias.add`;
- `domain.description.update`;
- optional impact snapshot.

### MVP 3: Federation

Implement:

- context relationships;
- contracts;
- resolutions;
- cross-context edge validation;
- source and federated releases.

### MVP 4: Governance

Implement:

- governance annotations;
- AI usage controls;
- privacy/classification;
- masking/export annotations.

### MVP 5: Rule Drafts

Only after the vocabulary and context map are stable:

- rule draft creation against domain keys;
- materialization candidates for `formRules`;
- backend validation/policy binding proposals.

## 12. Risks

### Over-Modeling

The model can become too large for an MVP. Mitigation: implement the graph core first and postpone profiles/governance authoring.

### False Canonicalization

Forcing enterprise canonical keys too early can create wrong global concepts. Mitigation: local keys first, canonical keys optional, resolutions reviewed.

### LLM Hallucinated Semantics

LLM-suggested knowledge may be wrong. Mitigation: evidence, confidence, validation and review status.

### Runtime Drift From Source

Runtime changes may diverge from source artifacts. Mitigation: classify changes as runtime-safe, source-required or code-required.

### Privacy Leakage To LLMs

Domain metadata may expose sensitive field meanings. Mitigation: AI usage annotations and prompt redaction must be part of the catalog.

## 13. Open Questions

- Should `domain_context_relationship` and `domain_contract` be MVP 1 or MVP 3?
- Should rich declarative YAML files be introduced immediately or after annotation-based extraction?
- Should domain embeddings share vector infrastructure with `api_metadata` and `ai_registry`, or have separate tables only?
- How should tenant overrides be merged into active releases?
- Which authoring operations can be safe without human review?
- How should source-of-record conflicts be represented in the UI?

## 14. Decision Direction

Praxis should evolve from metadata-driven UI toward an AI-operable semantic substrate.

The first foundation is not a rule engine. The first foundation is a federated domain vocabulary and context map with:

- sources;
- contexts;
- contracts;
- semantic nodes;
- relationships;
- technical bindings;
- aliases;
- resolutions;
- evidence;
- governance annotations;
- immutable releases;
- LLM-safe change patches.

Rules, OPA policies, DMN decisions, workflow guards and UI rules should be future consumers of this substrate.

## 15. Review Findings Against Current Praxis Codebase

This section records the first implementation-oriented review after comparing this RFC with the
current `praxis-config-starter`, `praxis-metadata-starter` and `praxis-api-quickstart` codebases.

### 15.1 Config Starter Fit

The proposed catalog should be added as a new module in `praxis-config-starter`, without replacing
the existing persistence surfaces:

- `api_metadata` remains the technical/API catalog.
- `ai_registry` remains the component, template and authoring manifest registry.
- `ui_user_config` remains user/tenant/environment UI configuration storage.
- `vector_store` remains the shared RAG infrastructure.

The current migration chain already reaches `V16`, so domain catalog persistence should start in a
new migration after the existing sequence. A baseline update is also needed only when the project
policy requires clean-install baseline parity.

The current ingestion/RAG pattern is a good precedent for domain ingestion:

- ingest a canonical structured artifact;
- persist the source record;
- create deterministic RAG chunks;
- attach `resourceType`, release, source and hash metadata;
- query by lexical plus semantic retrieval.

Recommended first resource types:

- `domain_catalog_release`;
- `domain_node`;
- `domain_edge`;
- `domain_binding`;
- `domain_governance_annotation`.

The domain catalog should not duplicate all payload into `api_metadata` or `ai_registry`. It should
link to those tables through `domain_binding` and use RAG as a retrieval index, not as the canonical
source of truth.

### 15.2 Metadata Starter Fit

`praxis-metadata-starter` already has a controller named `DomainCatalogController` at
`/schemas/catalog`. That endpoint is an OpenAPI-derived operational catalog, not the proposed
semantic domain vocabulary.

To avoid ambiguity, the new semantic surface should use a different name and path, for example:

- controller name: `SemanticDomainCatalogController` or `DomainVocabularyController`;
- endpoint path: `/schemas/domain` or `/schemas/domain-vocabulary`;
- DTO names prefixed with `SemanticDomain*` or `DomainVocabulary*`.

The new scanner should reuse existing registries where possible instead of reimplementing their
logic:

- `AnnotationDrivenActionDefinitionRegistry` already extracts `@WorkflowAction`.
- `AnnotationDrivenSurfaceDefinitionRegistry` already extracts `@UiSurface`.
- `ResourceStateSnapshotProvider` already exposes runtime state snapshots.
- `/schemas/filtered` remains the structural source for request/response schemas.
- `/schemas/actions` and `/schemas/surfaces` remain action and surface discovery surfaces.

The first semantic catalog generated by `metadata-starter` should therefore be derived from current
metadata, not from service code introspection:

- resources from `@ApiResource`;
- operations and schema links from OpenAPI;
- actions from `@WorkflowAction`;
- surfaces from `@UiSurface`;
- state availability from allowed-state metadata and `ResourceStateSnapshotProvider`;
- field candidates from DTO schemas and UI schema metadata;
- option source semantics from lookup descriptors and selection policies.

### 15.3 Quickstart Domain Examples

`praxis-api-quickstart` already contains enough business semantics to validate the MVP.

Payroll should be the first example because it has state, actions, partial forms and real service
rules:

- context: `human-resources`;
- concept: `folha_pagamento`;
- states: `AGUARDANDO_EVENTOS`, `PROGRAMADA`, `PAGA`;
- action: `approve-events`, allowed only in `AGUARDANDO_EVENTOS`;
- action: `mark-paid`, allowed only in `PROGRAMADA`;
- surface: `payment-schedule`, allowed in `AGUARDANDO_EVENTOS` and `PROGRAMADA`;
- service rule candidate: paid payroll cannot be rescheduled;
- service rule candidate: payment date must not be before the current date.

Procurement should be the second example because it validates reusable semantic policies without
starting a full rule engine:

- supplier selectable statuses: `ACTIVE`, `APPROVED`;
- supplier blocked statuses: `INACTIVE`, `BLOCKED`;
- product selectable statuses: `ACTIVE`, `AVAILABLE`;
- product blocked statuses: `INACTIVE`, `BLOCKED`;
- contract selectable statuses: `ACTIVE`, `SIGNED`;
- contract blocked statuses: `EXPIRED`, `CANCELLED`.

These examples should first become vocabulary, states, bindings, evidence and explanations. They
should not yet become executable shared rules. Executable rules should come after the vocabulary can
be searched, explained, versioned and validated.

### 15.4 Adjusted MVP Recommendation

The revised MVP should be smaller and more anchored in what Praxis already has:

1. Generate `/schemas/domain` from metadata already available in `praxis-metadata-starter`.
2. Ingest that artifact into new domain catalog tables in `praxis-config-starter`.
3. Create RAG chunks from the persisted domain catalog.
4. Add a query endpoint for LLM/runtime usage.
5. Validate with payroll and procurement examples from `praxis-api-quickstart`.

Do not implement authoring, rule materialization, OPA/DMN integration or cross-service federation in
the first implementation pass. Those layers depend on the vocabulary being stable.

### 15.5 First Implementation Slices

Recommended implementation order:

1. Define the `/schemas/domain` response contract in `praxis-metadata-starter`.
2. Implement a metadata-derived semantic catalog generator.
3. Add config-starter persistence tables for releases, nodes, edges, bindings, aliases and evidence.
4. Add an ingest endpoint for the generated domain artifact.
5. Add a search/read endpoint designed for LLM context assembly.
6. Add quickstart examples/tests that prove payroll and procurement semantics survive extraction,
   persistence and retrieval.

The key decision remains unchanged: Praxis should first create a domain vocabulary and semantic map.
Rules are consumers of this foundation, not the foundation itself.
