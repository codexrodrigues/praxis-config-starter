# Enterprise Runtime Context and Navigation RFC

Status: accepted for first implementation slice

Date: 2026-07-01

Related issue: https://github.com/codexrodrigues/praxis-config-starter/issues/185

## Classification

Overall initiative: `arquitetural` and future `contrato-publico`.

Initial RFC step: `docs-apenas`.

First implementation slice: `contrato-publico`.

## Problem

Corporate Praxis hosts need a standard way to expose the active runtime context:
current user projection, active tenant or company, accessible tenants, active profile,
environment, navigation, resource links and safe security/runtime signals.

Today, hosts can solve this locally with `/api/me/**`, host-specific menus, company
switchers or static shell navigation. That approach does not scale to ErgonX,
multi-tenant SaaS, ERP, public-sector, banking, HR or insurance hosts because
Praxis AI, metadata and Angular runtimes cannot reliably ground requests in the
same enterprise context.

## Inventory Before New Contract

The first design question is what Praxis already knows but does not materialize
well enough as a runtime contract.

| Capability | Current source | Adherence |
| --- | --- | --- |
| Tenant/user/environment identity for config and AI calls | `AiPrincipalContext` and `AiPrincipalContextResolver` resolve tenant, user and environment from server-side principal, request attributes or local-development defaults. | `suportado-parcialmente` |
| Tenant/user/environment scoped UI config | `UserConfigController`, `UserConfigService` and `ui_user_config` support `X-Tenant-ID`, optional `X-User-ID`, optional `X-Env`, `USER` versus `TENANT` scope and ETag/conditional requests. | `suportado-parcialmente` |
| Semantic resource/domain grounding | `/api/praxis/config/domain-catalog/**`, domain federation, domain knowledge, domain rules and `api_metadata` publish recoverable context for AI grounding. | `suportado-parcialmente` |
| Resource/action/surface discovery | `praxis-metadata-starter` is the canonical source for resource metadata, filtered schemas, surfaces, actions and capabilities. | `ja-suportado-mal-nomeado-ou-mal-materializado` |
| Angular tenant/header bootstrap | `praxis-ui-angular` has global config bootstrap, tenant resolver, config storage and header factories for `X-Tenant-ID`, `X-User-ID` and `X-Env`. | `suportado-parcialmente` |
| Host proof for config and AI | `praxis-api-quickstart` hosts `/api/praxis/config/**`, config origin controls, security integration and smoke scripts against published starter contracts. | `suportado-parcialmente` |
| Public current-user/runtime context payload | No host-neutral payload separates safe public runtime context from private auth internals. | `lacuna-real-de-contrato` |
| Accessible tenant/company choices and switching | Headers and local resolvers exist, but there is no governed provider SPI or switch contract that aligns subsequent metadata, config and resource calls. | `lacuna-real-de-contrato` |
| Navigation tree linked to Praxis resources/actions/surfaces | Angular has global navigation actions and metadata has resource capabilities, but no host-neutral navigation node contract connects corporate menus to Praxis refs. | `lacuna-real-de-contrato` |
| Safe public security/runtime events | AI turn events and domain timelines exist, but there is no public enterprise runtime event projection for shell use. | `lacuna-real-de-contrato` |

## Boundary Decision

Do not make `praxis-api-quickstart`, ErgonX, HADES, Angular shell state or a static
frontend menu the canonical source of enterprise runtime context.

The preferred platform direction is:

1. Keep private authentication and authorization in the host.
2. Define a small host-neutral enterprise runtime contract in a platform starter.
3. Reuse existing `praxis-config-starter` identity, ETag and governance semantics
   where the contract crosses config or AI authoring.
4. Reuse `praxis-metadata-starter` resource, surface, action and capability refs
   for navigation targets instead of inventing a parallel resource vocabulary.

The open packaging decision remains whether the first implementation lives inside
`praxis-config-starter` or in a future `praxis-enterprise-runtime-starter`. The
contract must not depend on Ergon/HADES tables or any host-specific security model.

## Proposed Contract Shape

The names below are intentionally provisional until implementation starts.

```http
GET /api/praxis/runtime/context
GET /api/praxis/runtime/context/choices
GET /api/praxis/runtime/tenants
PUT /api/praxis/runtime/context
GET /api/praxis/runtime/navigation
GET /api/praxis/runtime/security-events
```

Minimum payload semantics:

- `context`: one stateful response: `selection-required`, `ready` or
  `selection-invalid`; only `ready` contains the safe effective context.
- `context choices`: bounded, opaque complete choices and a session/query-bound
  cursor; a choice cannot be reconstructed from tenant/profile/module dimensions.
- `context switch`: only `choiceRef` plus `expectedSelectionVersion`; the host
  resolves the complete choice, reauthorizes it and commits compare-and-set before
  returning the same canonical context response.
- `tenants`: retained as a distinct host discovery projection. It is not a context
  selector and cannot replace opaque choices or selection CAS.
- `navigation`: tree or graph of host-visible destinations, with optional links to
  Praxis resource/action/surface refs from `praxis-metadata-starter`.
- `security-events`: optional safe projection of recent public events; no raw roles,
  tokens, policies, SQL, prompts, private audit internals or sensitive attributes.

## Integration Rules

- Runtime context resolution must be server-authoritative in corporate mode.
- Runtime identity and initial choice discovery require a server-authenticated
  session, not a tenant/profile/user header or a tenant already selected.
- `effectiveContext.activeOrganizationId`, when present, is the host-confirmed
  identifier of the current administrative organization for that contextVersion.
  It is a scope fact for presentation and contextual requests, never a role,
  capability, grant or catalog of permitted layout assignments.
- `effectiveContext.authorities` is the deliberate minimum public projection for
  field access; it is never inferred from the separate presentation capabilities.
- Tenant/company switching must define how subsequent metadata, config and resource
  calls receive the effective context.
- Navigation nodes should reference canonical Praxis concepts when available:
  `resourceKey`, `surfaceRef`, `actionRef`, `href`, `route`, `moduleKey` and
  `capabilityRef` are examples, not final field names.
- The contract must expose safe projections only. Private auth internals remain host-owned.

## Implementation Phases

1. Contract review
   - Confirm packaging: `praxis-config-starter` module versus new starter.
   - Finalize DTO names, endpoint base path and provider SPI.
   - Decide whether ETag applies to context/navigation read models.

2. Starter implementation
   - Add host-neutral provider interfaces.
   - Add safe default/no-op providers for non-corporate hosts.
   - Add controller tests for redaction, effective context and tenant switching.

3. Quickstart proof
   - Implement a fake non-Ergon provider.
   - Prove tenant switching updates downstream config/metadata/resource calls.
   - Add a focal smoke that does not require HADES or real corporate auth.

4. Angular consumption
   - Add a generic runtime context client in the canonical Angular owner.
   - Keep shell UI optional and host-specific.
   - Reuse existing header/bootstrap machinery rather than adding a parallel tenant store.

5. Public documentation and examples
   - Update public docs, examples and any HTTP corpus only after the backend contract is stable.

## Minimum Validation For Future Code

- Starter unit/controller tests for context payload, tenant choices, switching,
  redaction and cache/ETag behavior if adopted.
- Quickstart smoke proving a non-Ergon provider and downstream context propagation.
- Angular service tests if a public runtime context client is introduced.
- Documentation review for public contracts and examples.

## Non-Goals

- No dependency on HADES, Ergon tables or legacy transaction SQL.
- No replacement for host authentication or authorization.
- No exposure of secrets, raw roles, private policy internals or sensitive user attributes.
- No frontend-only static menu as the platform source of truth.
- No keyword or regex routing for user intent.

## Recommended Next Step

The packaging decision for the first slice is to implement the contract in
`praxis-config-starter`, while keeping the provider SPI host-neutral enough to move
or split into a future `praxis-enterprise-runtime-starter` if the boundary grows.

The current C0b code cut supersedes the earlier provisional switch envelope.
`EnterpriseRuntimeContextResponse` is now the sole response for GET and switch;
`EnterpriseRuntimeContextSwitchResponse` and dimensional command fields were
removed without aliases. Host-owned `EnterpriseRuntimeContextChoiceProvider`
serves `GET /api/praxis/runtime/context/choices`; defaults fail closed rather
than manufacture session state, choices, navigation, tenants or events. The host
alone owns selection versioning, context versioning, choice reauthorization,
revocation and operational CAS.

`GET /api/praxis/runtime/tenants` remains because it can represent a distinct
host discovery projection. It is explicitly not a selection mechanism, does not
authorize a context and must not be used by consumers as a substitute for
`/context/choices`.
