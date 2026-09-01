# Domain Catalog Contract v0.2

Status: governed semantic contract  
Schema version: `praxis.domain-catalog/v0.2`  
Date: 2026-04-22

This contract extends `praxis.domain-catalog/v0.1` with first-class governed
semantic metadata for authoring, RAG and LLM context retrieval.

Federated multi-service semantics are intentionally defined in a separate
planning contract:

```text
docs/domain-catalog/domain-federation-v0.1.md
```

That federation contract introduces `domain_source`,
`domain_context_relationship`, `domain_contract` and `domain_resolution` as the
next read-only, validation-first layer above source catalog releases.

The top-level shape remains unchanged:

```json
{
  "schemaVersion": "praxis.domain-catalog/v0.2",
  "service": {},
  "release": {},
  "resourceKey": "human-resources.folhas-pagamento",
  "contexts": [],
  "nodes": [],
  "edges": [],
  "bindings": [],
  "aliases": [],
  "evidence": [],
  "governance": []
}
```

## Resource Scope And Release Identity

`resourceKey` is the structured scope for a catalog published for one resource.
The config store persists and queries it directly; consumers must not parse
`release.releaseKey` to recover the resource. Catalogs emitted for a group may
set `resourceKey` to `null`.

`release.sourceHash` is the canonical SHA-256 fingerprint of the semantic
catalog payload, excluding volatile publication metadata such as `generatedAt`.
Publishers should derive `release.releaseKey` from the service key, semantic
scope and source hash, so identical semantics keep the same release identity
across process restarts and repeated publication attempts.

The config store treats a release with the same `releaseKey`, `schemaVersion`,
tenant, environment and `sourceHash` as already ingested. In that case,
`/api/praxis/config/domain-catalog/ingest` returns the existing release and item
count without deleting or reinserting canonical items. Because RAG is a derived
materialization, the idempotent path checks its release status: a reconciled
corpus is left untouched, while a partial or unavailable status schedules
republication from the persisted canonical items. This makes a repeated ingest
the recovery operation after a transient embedding/vector-store failure without
changing release identity.

`releaseKey` is content identity inside one exact tenant/environment scope, not
a globally unique database key. Identical catalog content may therefore be
ingested independently by multiple tenants or environments. A repeated
`releaseKey` with different immutable content in the same scope is rejected as
a conflict instead of replacing the persisted release. Reads by `releaseKey`
must carry the same `X-Tenant-ID` and `X-Env` scope used for ingestion.

The `Domain Catalog PostgreSQL Migration` workflow applies the complete Flyway
chain to an ephemeral PostgreSQL/pgvector database. It proves that V31 accepts
the same release key in different scopes while rejecting a duplicate inside one
exact tenant/environment scope. The same gate verifies that the V16 canonical
vector identity index is installed and reproduces the legacy physical-id
collision before proving that reconciliation removes only the divergent row,
preserves other scopes and keeps the current physical id idempotent.

RAG publication is a derived materialization, not the source of truth for the
catalog. By default the starter schedules RAG publication after the catalog
transaction commits (`praxis.domain-catalog.rag-publication.async-enabled=true`)
and publishes documents in bounded batches
(`praxis.domain-catalog.rag-publication.batch-size=100`). A failed batch is
retried in place without reprocessing successful earlier batches only when the
canonical provider failure is recoverable (`rate_limit`, `capacity`,
`server_error`, `transport` or `timeout`). Exhausted quota, authentication,
client and unknown provider failures stop the current publication attempt
immediately; the structural release remains persisted and a later idempotent
ingest can resume from the missing documents. Logs expose only the normalized
failure kind, never the provider body or embedding input. The bounded retry
policy is configured by
`praxis.domain-catalog.rag-publication.max-attempts=3` and exponential backoff
starting at
`praxis.domain-catalog.rag-publication.retry-backoff-ms=1000` (capped at 60
seconds). Operators can still disable this materialization with
`praxis.domain-catalog.rag-publication.enabled=false`; `/items` and `/context`
continue to read the canonical transactional store.

Each pgvector batch is written through the canonical `ON CONFLICT (id) DO
UPDATE` operation. Existing documents are not deleted before embedding and
upsert, so a provider failure cannot turn a partial refresh into corpus data
loss; a later idempotent ingest can safely resume reconciliation.

The operational status surface for this derived materialization is:

```text
GET /api/praxis/config/domain-catalog/rag/status?serviceKey={serviceKey}&resourceKey={resourceKey}
```

Release discovery supports an exact optional resource scope:

```text
GET /api/praxis/config/domain-catalog/releases?serviceKey={serviceKey}&resourceKey={resourceKey}&limit={limit}
```

When `resourceKey` is present, filtering occurs in the canonical store before
ordering and pagination. Consumers that need the latest release of one resource
must use this parameter instead of scanning a globally limited service release
list. Tenant and environment headers remain part of the release scope.

It resolves the latest release for the requested tenant, environment, service
and optional resource, then reports `domain_catalog` vector-store document
counts, source breakdowns, visibility breakdowns, latest publication timestamp
and reconciliation warnings. The additive `publication` block exposes the
persisted materialization lifecycle for that immutable release:

- `PENDING`: a publication revision was requested but has not started;
- `PUBLISHING`: the current revision was claimed by the publisher;
- `PUBLISHED`: publication completed, with its published document count;
- `FAILED`: publication stopped, with a sanitized canonical `failureKind`,
  `retryable` decision and optional `retryAfter` timestamp.

The block also carries `revision`, `attempt`, expected/published counts and
lifecycle timestamps. `failureKind` is derived from the shared AI provider
taxonomy or from the sanitized RAG materialization taxonomy
(`vector_store_integrity`, `vector_store_transient`, `vector_store_failure`,
`rag_publication_contract` or `rag_publication_internal`); it never contains a
raw provider response, SQL detail or document content. Pending or publishing
work is recovered as pending after application restart. Consumers must treat
`FAILED` as terminal for that publication revision instead of polling for an
implicit transition or inferring provider/database state from text.
`retryable=true` means a later explicit publication request may be attempted;
it does not promise that a failed revision will schedule itself again.
`retryAfter`, when present, is the earliest provider-governed instant for that
later attempt. The publisher honors provider guidance during its bounded
internal retries and persists longer windows instead of sleeping for less than
the provider requested.

Domain Catalog document ids are derived from the same canonical chunk identity
stored in metadata. Before each bounded upsert batch, the publisher removes only
a legacy physical row that occupies the same canonical content identity under a
different id. This keeps reingestion order-independent while preserving an
existing same-id document until the vector-store upsert succeeds. Only
explicitly classified provider or vector-store transient failures are retried;
untyped, contract and integrity failures stop on the first attempt.

The expected count is computed from persisted
catalog items that are eligible for RAG publication: items must have searchable
content and must not declare `aiUsage.visibility=deny`. This endpoint is an
operational readiness check for the derived vector corpus; it does not replace
the canonical `/items`, `/context` or release identity contracts.

## Additions From v0.1

Context items may now include:

- `semanticOwner`: the owner of the bounded context semantics.
- `lifecycle`: `draft`, `candidate`, `active`, `deprecated` or `retired`.
- `businessGlossary`: curated preferred term, description and examples.

Node items may now include:

- `semanticOwner`: owner of the semantic node.
- `lifecycle`: lifecycle state independent from runtime availability.
- `businessGlossary`: business vocabulary for LLM/user-facing explanations.
- `resolution`: deterministic match metadata for authoring tools.
- `sourceEvidenceKeys`: evidence records that justify the node.

Aliases are no longer optional decoration. Generated labels and stable runtime
identifiers such as field names, workflow action IDs and UI surface IDs should
be materialized as `alias` items.

## Validation

`praxis-config-starter` validates v0.2 payloads before persistence using the
JSON Schema at:

```text
docs/domain-catalog/contracts/praxis-domain-catalog-v0.2.schema.json
```

The same schema is packaged in the starter runtime at:

```text
src/main/resources/domain-catalog/contracts/praxis-domain-catalog-v0.2.schema.json
```

Invalid fields, unsupported enum values and unsupported schema versions must
fail before any `domain_catalog_release` or `domain_catalog_item` write.

## Rule Boundary

v0.2 still does not define executable rules. `policy_hint` and `governance`
items remain semantic context. Component manifests and backend authoring tools
remain responsible for deterministic UI configuration changes.
