# Domain Catalog Runtime Flow

Status: MVP runbook  
Date: 2026-04-21

This document describes the first runtime flow between:

- `praxis-api-quickstart`, publishing `/schemas/domain`;
- `praxis-config-starter`, ingesting `/api/praxis/config/domain-catalog/ingest`;
- LLM/runtime consumers, reading `/api/praxis/config/domain-catalog/items`.
- LLM/runtime consumers, reading `/api/praxis/config/domain-catalog/context` when they need
  a compact semantic context pack.

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
  "schemaVersion": "praxis.domain-catalog/v0.1",
  "service": {},
  "release": {},
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
  "releaseKey": "praxis-api-quickstart:example",
  "itemCount": 42
}
```

Save the returned `releaseKey`.

```bash
export DOMAIN_RELEASE_KEY="praxis-api-quickstart:example"
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
  "$CONFIG_BASE_URL/api/praxis/config/domain-catalog/context?serviceKey=praxis-service&type=node&nodeType=field&q=salario&limit=10" \
  -H "X-Tenant-ID: $TENANT_ID" \
  -H "X-Env: $ENVIRONMENT"
```

The response includes:

- `schemaVersion`: context contract version;
- `release`: the latest release selected for `serviceKey`, tenant and environment;
- `retrievalGuidance`: instructions for LLM/runtime interpretation;
- `items`: semantic catalog items relevant to the query.

Use `items/latest` when the client only needs raw items. Use `context` when the client is an LLM
or an orchestration service that needs an explicit semantic contract.

## 6. Expected Payroll Semantics

For `human-resources.folhas-pagamento`, the catalog should include:

- a `concept` node for `human-resources.folhas-pagamento`;
- `action` nodes such as `approve-events` and `mark-paid`;
- `surface` nodes such as `payment-schedule`;
- `state` nodes such as `AGUARDANDO_EVENTOS`, `PROGRAMADA` and `PAGA` when exposed by actions/surfaces;
- `field` nodes from OpenAPI schemas, such as financial or workflow fields;
- `allowed_in_state` edges;
- `workflow_action`, `ui_surface` and `dto_field` bindings;
- `annotation` and `dto_schema` evidence.

## 7. Expected Procurement Semantics

For procurement option sources, the catalog should include `policy_hint` nodes.

Supplier example:

- allowed statuses: `ACTIVE`, `APPROVED`;
- blocked statuses: `INACTIVE`, `BLOCKED`;
- edge type: `selectable_when`;
- edge type: `blocked_when`;
- binding type: `option_source`.

These are not executable rules yet. They are semantic policy hints that make the business meaning
queryable and explainable.

## 8. How An LLM Should Use This

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
GET /api/praxis/config/domain-catalog/context?serviceKey=<service>&type=node&nodeType=field&q=salario
```

The LLM should use returned field nodes, inspect `payload.metadata.fieldName`, and then propose a
governed patch or annotation in a future authoring flow.

## 9. Current MVP Limits

The MVP currently supports:

- generated catalog ingestion;
- release persistence;
- materialized items;
- basic item search;
- latest-release search for a specific service or, when `serviceKey` is
  omitted, federated search across the latest release of each service in the
  requested tenant/environment;
- optional RAG publication when `VectorStore` is available.

The MVP does not yet support:

- cross-service relationship inference beyond latest-release federation;
- LLM patch proposals;
- rule execution;
- OPA/DMN integration;
- governance authoring UI;
- automatic LGPD/GDPR classification.

## 10. Next Evolution

Recommended next steps:

1. Add governance annotations for privacy and AI usage.
2. Add cross-service domain resolution.
3. Add governed authoring operations for aliases and descriptions.
4. Add domain context injection into the AI orchestrator prompt.
5. Add field-level LGPD/GDPR classification proposals.
