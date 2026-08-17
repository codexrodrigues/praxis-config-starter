# Governed domain-rule catalog v1

`GET /api/praxis/config/domain-rules/definitions/catalog` is the canonical bounded discovery
surface for governed decision definitions. It is shared by human workstations and assistant tools;
neither consumer owns a parallel rule index.

The server resolves tenant, environment and `RULE_DEFINITION_READER` from the authenticated
principal. Caller-supplied scope headers never widen that principal. The response deliberately
contains identity and navigation fields only: definition id, exact rule key and version, type,
status, context, resource, service, semantic owner and update time. Conditions, parameters,
governance payloads and evidence are loaded only through the scoped exact-definition endpoint.

Supported filters are `query`, `ruleType`, `status`, `resourceKey`, `page` and `limit`. Text query
only ranks candidates inside the already resolved scope. It is not an intent resolver and must not
replace semantic LLM routing or governed Domain Catalog grounding.

The response schema is `praxis-domain-rule-catalog.v1`; pages expose `page`, `limit` and `hasMore`.
Consumers must preserve the exact `definitionId` when opening details so versions cannot be joined
by rule key alone.
