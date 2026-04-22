# Domain Catalog Contract v0.2

Status: governed semantic contract  
Schema version: `praxis.domain-catalog/v0.2`  
Date: 2026-04-22

This contract extends `praxis.domain-catalog/v0.1` with first-class governed
semantic metadata for authoring, RAG and LLM context retrieval.

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
