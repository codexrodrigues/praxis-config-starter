# Domain Catalog Runtime Flow

Status: MVP runbook  
Date: 2026-04-21

This document describes the first runtime flow between:

- `praxis-api-quickstart`, publishing `/schemas/domain`;
- `praxis-config-starter`, ingesting `/api/praxis/config/domain-catalog/ingest`;
- LLM/runtime consumers, reading `/api/praxis/config/domain-catalog/items`.
- LLM/runtime consumers, reading `/api/praxis/config/domain-catalog/context` when they need
  a compact semantic context pack.
- LLM/runtime consumers, reading `/api/praxis/config/domain-catalog/relationships/latest`
  when they need explicit cross-service relationships.

The goal is to validate the semantic domain catalog without requiring an LLM to inspect source code.

## 1. Start The Services

Example local ports:

```bash
export QUICKSTART_BASE_URL="http://localhost:8088"
export CONFIG_BASE_URL="http://localhost:8089"
export TENANT_ID="demo"
export ENVIRONMENT="local"
```

`praxis-api-quickstart` publishes the source catalog.

`praxis-config-starter` persists and indexes the catalog. It must run with a database that has
migration `V17__create_domain_catalog.sql` applied.

## 2. Generate A Domain Catalog

Generate payroll semantics:

```bash
curl -sS \
  "$QUICKSTART_BASE_URL/schemas/domain?resourceKey=human-resources.folhas-pagamento" \
  -o /tmp/praxis-domain-human-resources-folhas-pagamento.json
```

Generate procurement supplier semantics:

```bash
curl -sS \
  "$QUICKSTART_BASE_URL/schemas/domain?resourceKey=procurement.suppliers" \
  -o /tmp/praxis-domain-procurement-suppliers.json
```

Expected top-level shape:

```json
{
  "schemaVersion": "praxis.domain-catalog/v0.2",
  "service": {},
  "release": {
    "releaseKey": "praxis-service:human-resources.folhas-pagamento:0123456789abcdef",
    "generatedAt": "2026-04-24T12:00:00Z",
    "sourceHash": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
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

The normative v0.2 vocabulary and governance baseline are documented in
`docs/domain-catalog/domain-catalog-contract-v0.2.md`. The matching JSON Schema
is available at
`docs/domain-catalog/contracts/praxis-domain-catalog-v0.2.schema.json`.

## 3. Ingest The Catalog

```bash
curl -sS -X POST \
  "$CONFIG_BASE_URL/api/praxis/config/domain-catalog/ingest" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: $TENANT_ID" \
  -H "X-Env: $ENVIRONMENT" \
  --data-binary @/tmp/praxis-domain-human-resources-folhas-pagamento.json
```

Expected response:

```json
{
  "releaseId": "00000000-0000-0000-0000-000000000000",
  "releaseKey": "praxis-service:human-resources.folhas-pagamento:0123456789abcdef",
  "itemCount": 42
}
```

Repeated ingestion of the same `releaseKey` and `sourceHash` is idempotent: the
config store returns the existing item count without deleting/reinserting the
catalog items or republishing RAG documents.

Save the returned `releaseKey`.

```bash
export DOMAIN_RELEASE_KEY="praxis-service:human-resources.folhas-pagamento:0123456789abcdef"
```

## 4. Query Persisted Items

List all items for a release:

```bash
curl -sS \
  "$CONFIG_BASE_URL/api/praxis/config/domain-catalog/items?releaseKey=$DOMAIN_RELEASE_KEY"
```

List only semantic nodes:

```bash
curl -sS \
  "$CONFIG_BASE_URL/api/praxis/config/domain-catalog/items?releaseKey=$DOMAIN_RELEASE_KEY&type=node"
```

Search for payroll value fields:

```bash
curl -sS \
  "$CONFIG_BASE_URL/api/praxis/config/domain-catalog/items?releaseKey=$DOMAIN_RELEASE_KEY&type=node&q=valor"
```

Search for selectable suppliers:

```bash
curl -sS \
  "$CONFIG_BASE_URL/api/praxis/config/domain-catalog/items?releaseKey=$DOMAIN_RELEASE_KEY&type=node&q=ACTIVE"
```

Filter by context:

```bash
curl -sS \
  "$CONFIG_BASE_URL/api/praxis/config/domain-catalog/items?releaseKey=$DOMAIN_RELEASE_KEY&contextKey=human-resources"
```

## 5. Query Latest Runtime Context

LLM/runtime clients should prefer `context` when they do not already know the active release key.

```bash
curl -sS \
  "$CONFIG_BASE_URL/api/praxis/config/domain-catalog/context?serviceKey=praxis-service&resourceKey=human-resources.folhas-pagamento&type=node&nodeType=field&q=salario&limit=10" \
  -H "X-Tenant-ID: $TENANT_ID" \
  -H "X-Env: $ENVIRONMENT"
```

The response includes:

- `schemaVersion`: context contract version;
- `release`: the latest release selected for `serviceKey`, optional `resourceKey`, tenant and environment;
- `retrievalGuidance`: instructions for LLM/runtime interpretation;
- `items`: semantic catalog items relevant to the query.

Use `items/latest` when the client only needs raw items. Use `context` when the client is an LLM
or an orchestration service that needs an explicit semantic contract.

`context` applies direct `aiUsage.visibility` constraints before returning items to LLM/runtime
consumers:

- `deny`: the item is excluded from the context pack;
- `mask` and `summarize_only`: the item payload is reduced to governed summary fields such as
  `nodeKey`, `annotationType`, `classification`, `dataCategory`, `complianceTags` and `aiUsage`.

The raw `/items` endpoints remain deterministic persistence reads and may return the original
payload for authorized system clients.

## 6. Query Latest Explicit Relationships

Use `relationships/latest` when the client needs deterministic relationship lookup instead of
free-text inference.

```bash
curl -sS \
  "$CONFIG_BASE_URL/api/praxis/config/domain-catalog/relationships/latest?sourceNodeKey=human-resources.employee.field.costCenterId&targetNodeKey=finance.cost-center&edgeType=references&limit=10" \
  -H "X-Tenant-ID: $TENANT_ID" \
  -H "X-Env: $ENVIRONMENT"
```

Supported filters:

- `serviceKey`: optional. When omitted, the query federates across the latest release of each
  service in the requested tenant and environment.
- `resourceKey`: optional. Use it when one service publishes multiple resource catalogs.
- `sourceNodeKey`: optional exact canonical source node key.
- `targetNodeKey`: optional exact canonical target node key.
- `edgeType`: optional exact edge type such as `references`, `same_as`, `governed_by` or
  `materializes`.
- `q`: optional full-text search over edge materialization.
- `limit`: optional result limit, clamped by the service.

This endpoint only returns explicit `edge` items. It does not infer relationships from similar
names, labels or aliases.

## 7. Expected Payroll Semantics

For `human-resources.folhas-pagamento`, the catalog should include:

- a `concept` node for `human-resources.folhas-pagamento`;
- `action` nodes such as `approve-events` and `mark-paid`;
- `surface` nodes such as `payment-schedule`;
- `state` nodes such as `AGUARDANDO_EVENTOS`, `PROGRAMADA` and `PAGA` when exposed by actions/surfaces;
- `field` nodes from OpenAPI schemas, such as financial or workflow fields;
- `allowed_in_state` edges;
- `workflow_action`, `ui_surface` and `dto_field` bindings;
- `annotation` and `dto_schema` evidence.

## 8. Expected Procurement Semantics

For procurement option sources, the catalog should include `policy_hint` nodes.

Supplier example:

- allowed statuses: `ACTIVE`, `APPROVED`;
- blocked statuses: `INACTIVE`, `BLOCKED`;
- edge type: `selectable_when`;
- edge type: `blocked_when`;
- binding type: `option_source`.

These are not executable rules yet. They are semantic policy hints that make the business meaning
queryable and explainable.

## 9. How An LLM Should Use This

The LLM should first query the catalog instead of reading source code.

Example analyst request:

> Explique quando uma folha pode ser marcada como paga.

Runtime retrieval should search for:

```text
releaseKey=<active-release>
type=node
q=marcar paga PROGRAMADA
```

Then follow edges and bindings:

- action node: `human-resources.folhas-pagamento.action.mark-paid`;
- state node: `human-resources.folhas-pagamento.estado.programada`;
- edge: `allowed_in_state`;
- binding: actual API operation;
- evidence: `@WorkflowAction` or schema metadata.

The answer should cite catalog evidence, not code.

For a field classification request:

> Marque campos de salário como dados financeiros sensíveis.

Runtime retrieval should call:

```text
GET /api/praxis/config/domain-catalog/context?serviceKey=<service>&resourceKey=<resource>&type=node&nodeType=field&q=salario
```

The LLM should use returned field nodes, inspect `payload.metadata.fieldName`, and then propose a
governed patch or annotation in a future authoring flow.

For a cross-service relationship request:

> Este campo de centro de custo referencia qual conceito financeiro?

Runtime retrieval should call:

```text
GET /api/praxis/config/domain-catalog/relationships/latest?serviceKey=<service>&resourceKey=<resource>&sourceNodeKey=<field-node-key>&edgeType=references
```

The LLM should only cite returned relationship edges and their evidence. It should not invent
cross-service links from naming similarity.

## 10. Current MVP Limits

The MVP currently supports:

- generated catalog ingestion;
- release persistence;
- materialized items;
- basic item search;
- latest-release search for a specific service or, when `serviceKey` is
  omitted, federated search across the latest release of each service in the
  requested tenant/environment;
- explicit latest relationship lookup across one service or across the latest
  release of each service in the requested tenant/environment;
- direct AI visibility enforcement in LLM context packs for `deny`, `mask` and
  `summarize_only`;
- RAG publication skips `deny` items and indexes `mask`/`summarize_only` items
  only as governed summaries;
- optional RAG publication when `VectorStore` is available.

The MVP does not yet support:

- cross-service relationship inference beyond explicit edges and latest-release federation;
- LLM patch proposals;
- rule execution;
- OPA/DMN integration;
- governance authoring UI;
- automatic LGPD/GDPR classification.

## 11. Next Evolution

Recommended next steps:

1. Add governance annotations for privacy and AI usage.
2. Add indexed cross-service domain resolution for high-volume relationship queries.
3. Add governed authoring operations for aliases and descriptions.
4. Add domain context injection into the AI orchestrator prompt.
5. Add field-level LGPD/GDPR classification proposals.
