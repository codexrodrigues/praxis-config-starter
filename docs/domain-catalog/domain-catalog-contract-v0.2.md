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
  "contexts": [],
  "nodes": [],
  "edges": [],
  "bindings": [],
  "aliases": [],
  "evidence": [],
  "governance": []
}
```

## Release Identity

`release.sourceHash` is the canonical SHA-256 fingerprint of the semantic
catalog payload, excluding volatile publication metadata such as `generatedAt`.
Publishers should derive `release.releaseKey` from the service key, semantic
scope and source hash, so identical semantics keep the same release identity
across process restarts and repeated publication attempts.

The config store treats a release with the same `releaseKey`, `schemaVersion`,
tenant, environment and `sourceHash` as already ingested. In that case,
`/api/praxis/config/domain-catalog/ingest` returns the existing release and item
count without deleting/reinserting items or republishing RAG documents.

RAG publication is a derived materialization, not the source of truth for the
catalog. By default the starter schedules RAG publication after the catalog
transaction commits (`praxis.domain-catalog.rag-publication.async-enabled=true`)
and publishes documents in bounded batches
(`praxis.domain-catalog.rag-publication.batch-size=100`). Operators can still
disable this materialization with
`praxis.domain-catalog.rag-publication.enabled=false`; `/items` and `/context`
continue to read the canonical transactional store.

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
