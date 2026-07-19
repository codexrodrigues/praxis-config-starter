# Machine-First Semantic Grounding: Human Resources Baseline Inventory

Status: reproducible static and local HTTP source baseline
Date: 2026-07-19
Related RFC: [`../2026-07-machine-first-semantic-ir-rfc.md`](../2026-07-machine-first-semantic-ir-rfc.md)

## Scope

This inventory answers the required platform question:

> What does Praxis already know about Human Resources that the LLM and generative UI flow do
> not yet retrieve or materialize well?

The baseline is intentionally split into:

- canonical capabilities already implemented;
- semantic information present in host code and metadata;
- current generated-catalog behavior;
- real contract gaps;
- measurements that still require a running local backend.

This document does not declare a new runtime contract.

## Reproduction Context

Repositories inspected:

- `praxis-metadata-starter`;
- `praxis-config-starter`;
- `praxis-api-quickstart`;
- `praxis-ui-angular` Page Builder AI contracts.

The local Angular server was active on `http://localhost:4003`. The canonical quickstart was
packaged and started on `http://localhost:8088` with the repository-owned local E2E wrapper.
The source baseline was captured read-only with:

```sh
tools/local-e2e/capture-machine-first-hr-baseline.sh
```

Captured runtime:

| Artifact | Version/evidence |
| --- | --- |
| quickstart commit | `1b102764484ebcde34553c605533c969d54c04b5` |
| quickstart | `2.0.0-rc.16` |
| `praxis-metadata-starter` | `8.0.0-rc.113` |
| `praxis-config-starter` consumed by the host | `0.1.0-rc.84` |
| Domain Catalog | `praxis.domain-catalog/v0.2` |
| release | `praxis-service:human-resources:025f0d304a66669b` |
| source hash | `025f0d304a66669b0fd3faa7ae69efcaaf41c3c598acc02f6fa61f585a006a86` |

The live payload passed the packaged `DomainCatalogSchemaValidationService` schema. The config
store was queried read-only: this exact aggregate release was not already persisted and the
capture deliberately did not ingest it. Retrieval, provider prompts, cost and latency therefore
remain separate downstream gates rather than claims of this source-baseline cut.

## Static Human Resources Inventory

Source scope:

```text
praxis-api-quickstart/src/main/java/com/example/praxis/apiquickstart/hr
```

| Signal | Count | What Praxis can learn |
| --- | ---: | --- |
| `@ApiResource` controller classes | 21 | Stable resource identity and operational path |
| `@ApiGroup` controller classes | 21 | Shared `human-resources` documentary grouping |
| `@UiSurface` declarations | 12 | Governed UI affordances over real operations |
| `@WorkflowAction` declarations | 7 | Explicit business commands and allowed states |
| `@DomainGovernance` declarations | 19 | Privacy/compliance/security and AI-use evidence |
| `@Schema(description=...)` occurrences | 143 | Field and payload descriptions available to schema extraction |
| Java files under HR DTO packages | 89 | Structural payloads, filters, lookups and analytic projections |

Reproduction commands:

```sh
rg -l '@ApiResource' praxis-api-quickstart/src/main/java/com/example/praxis/apiquickstart/hr --glob '*.java' | wc -l
rg -o '@UiSurface' praxis-api-quickstart/src/main/java/com/example/praxis/apiquickstart/hr --glob '*.java' | wc -l
rg -o '@WorkflowAction' praxis-api-quickstart/src/main/java/com/example/praxis/apiquickstart/hr --glob '*.java' | wc -l
rg -o '@DomainGovernance' praxis-api-quickstart/src/main/java/com/example/praxis/apiquickstart/hr --glob '*.java' | wc -l
rg -o '@Schema\([^\n]*description' praxis-api-quickstart/src/main/java/com/example/praxis/apiquickstart/hr --glob '*.java' | wc -l
```

## Published Resource Identities

The Human Resources group currently publishes these stable resource keys:

- `human-resources.funcionarios`;
- `human-resources.cargos`;
- `human-resources.departamentos`;
- `human-resources.dependentes`;
- `human-resources.enderecos`;
- `human-resources.eventos-folha`;
- `human-resources.ferias-afastamentos`;
- `human-resources.folhas-pagamento`;
- `human-resources.funcionario-habilidades`;
- `human-resources.habilidades`;
- `human-resources.historicos-cargos`;
- `human-resources.historicos-salariais`;
- `human-resources.identidades-secretas`;
- `human-resources.indenizacoes`;
- `human-resources.legacy-pay-codes`;
- `human-resources.mencoes-midia`;
- `human-resources.reputacoes`;
- `human-resources.vw-analytics-afastamentos`;
- `human-resources.vw-analytics-folha-pagamento`;
- `human-resources.vw-perfil-heroi`;
- `human-resources.vw-ranking-reputacao`.

These are excellent technical identities. They are not, by themselves, a curated business
model. In particular, analytic views, legacy codes, secret identities and operational entities
must not all be treated as equivalent kinds of business concept.

The aggregate HTTP catalog additionally includes
`human-resources.extraordinary-benefit-requests`, whose controller lives under the quickstart
rule-lab package rather than the HR source folder. This explains why the static package inventory
finds 21 HR controllers while the running `human-resources` group publishes 22 concept nodes.

## What The Current Publisher Does

`SemanticDomainCatalogService` currently:

1. loads actions and surfaces by resource or OpenAPI group;
2. derives the context key from the first resource-key segment or group;
3. creates a `concept` node for each discovered API resource;
4. adds action, surface, state, policy-hint and schema-field nodes;
5. creates technical bindings and source evidence;
6. projects field governance;
7. builds aliases from generated identities;
8. fingerprints and publishes an immutable v0.2 payload.

This is a valuable deterministic technical projection. Its documented class-level contract
also states that this first version derives domain vocabulary from annotated actions and
surfaces and does not interpret services or execute rules.

## Current Contract Vocabulary

The checked-in documentation v0.2 JSON Schema currently recognizes these node types:

- `concept`;
- `resource`;
- `entity`;
- `field`;
- `state`;
- `operation`;
- `action`;
- `surface`;
- `relationship`;
- `policy_hint`;
- `llm_visibility`.

Before this inventory cut, the packaged runtime schema additionally recognized `stats`, exposing
a pre-existing drift from the documentary schema. The publisher emits `concept`, `field`,
`state`, `action`, `surface`, `stats` and `policy_hint` nodes from technical metadata. Option
sources are correctly represented as `policy_hint` nodes with `option_source` bindings and
evidence, rather than as a separate node type. The documentary schema was synchronized with the
packaged schema in this cut and is now protected by an anti-drift test.

Business capabilities, processes, business events, metrics/KPIs and actors are not yet
first-class authored output of this pipeline.

## Adherence Classification

| Observation | Classification | Evidence and implication |
| --- | --- | --- |
| Stable HR resource ids exist | `already-supported` | Reuse as technical binding identities |
| Actions, surfaces, states and schemas are discoverable | `already-supported` | Inspect only after semantic scope is accepted |
| `@ApiGroup` has title/description but generated context description is currently null | `ja-suportado-mal-nomeado-ou-mal-materializado` | Materialize known group semantics before adding fields |
| Context is derived from `resourceKey` prefix or OpenAPI group | `suportado-parcialmente` | Add explicit authored context identity/binding |
| API resource is automatically promoted to `concept` | `suportado-parcialmente` | Preserve it as extracted candidate or technical binding, not approved business truth |
| Field descriptions and governance feed catalog nodes | `already-supported` | Retain as evidence and technical semantics |
| Documentation schema was missing runtime-supported `resourceKey`, `stats`, `has_stats`, `stats_endpoint` and `openapi_stats` | `ja-suportado-mal-nomeado-ou-mal-materializado` | Synchronized in this cut; retain the permanent anti-drift test |
| Domain Catalog release, ingestion, federation and canonical reload exist | `already-supported` | Do not create another catalog |
| Project Knowledge approval, visibility and evidence lifecycle exist | `already-supported` | Reuse for governed semantic claims |
| Global/domain summaries are absent | `lacuna-real-de-contrato` | Add compiled multi-resolution projections |
| Authored capability/process/event/metric concepts are absent | `lacuna-real-de-contrato` | Add the minimum Semantic IR profile |
| Authored/extracted/inferred provenance per claim is incomplete | `lacuna-real-de-contrato` | Add claim source and derivation semantics |
| Progressive LLM tools before API search are absent | `lacuna-real-de-contrato` | Add semantic retrieval tools and budgets |
| Page Builder plan lacks a complete semantic selection audit | `lacuna-real-de-contrato` | Add a turn/preview envelope, not fields in the persisted page |

## What Praxis Can Reliably Answer Today

With a healthy local runtime and ingested release, existing metadata can support grounded
answers to questions such as:

- Which fields are exposed for `human-resources.funcionarios`?
- Which UI surfaces exist for a specific accepted resource?
- Which workflow action deactivates an employee and in which state?
- Which schema describes a selected create or update operation?
- Which fields have explicit privacy or compliance governance?

Those are resource- or operation-level questions.

## What Is Not Yet Reliably Represented

The current catalog cannot, from an authored business model alone, reliably answer:

- What is the purpose and boundary of Human Resources in this host?
- Which business capabilities does HR provide?
- How do employee, employment relationship, onboarding and payroll differ?
- Which processes connect onboarding, leave, payroll and termination?
- Which KPIs measure each HR capability?
- Which concepts belong to HR but have no API endpoint?
- Which API resources are alternate projections of the same business concept?
- Which relationships were authored, extracted or inferred?

Answering these from all available endpoints would reconstruct business meaning from its final
technical projections and repeat the current failure mode.

## Candidate HR Pilot Map

The following is a hypothesis for evaluation, not approved canonical knowledge:

| Candidate type | Candidate ids | Potential existing bindings |
| --- | --- | --- |
| Domain/context | `human-resources`, `human-resources.workforce`, `human-resources.payroll` | `@ApiGroup("human-resources")` and resource prefixes |
| Concepts | employee, employment relationship, department, role, dependent, leave, payroll event, payroll run | Existing resource keys and DTOs |
| Capabilities | workforce management, employee onboarding, organization management, leave management, payroll processing | Surfaces, actions, services and API resources |
| Processes | onboarding, deactivation/reactivation, leave approval, payroll closing | Workflow actions and service/test evidence |
| Events | employee admitted, employee deactivated, leave started, payroll event approved/rejected | Action outcomes and future explicit event sources |
| Metrics | workforce count, payroll cost, leave rate, payroll status | Analytics resource projections |

Before publication, each candidate requires an authored definition or an inferred candidate
claim with evidence and governance.

## Local HTTP Source Baseline

`GET /schemas/domain?group=human-resources` returned `200` and the following deterministic
release projection:

| Published item | Count |
| --- | ---: |
| contexts | 1 |
| nodes | 545 |
| edges | 525 |
| bindings | 515 |
| aliases | 980 |
| evidence | 1,001 |
| governance entries | 163 |

Node vocabulary:

| Node type | Count |
| --- | ---: |
| `concept` | 22 |
| `field` | 331 |
| `action` | 14 |
| `surface` | 90 |
| `state` | 8 |
| `stats` | 66 |
| `policy_hint` | 14 |

The relationship projection contains 331 `has_field`, 90 `has_surface`, 66 `has_stats`, 14
`has_action`, 13 `uses_concept` and 11 `allowed_in_state` edges. Bindings include 331
`dto_field`, 90 `ui_surface`, 66 `stats_endpoint`, 14 `workflow_action` and 14 `option_source`
items. Evidence includes 817 `dto_schema`, 104 `annotation`, 66 `openapi_stats` and 14
`option_source` items.

### Measured semantic quality gaps

| Signal | Result | Interpretation |
| --- | ---: | --- |
| concept descriptions | 22/22 | Resource-level semantics are already much richer than endpoint names |
| bounded-context descriptions | 0/1 | Known group semantics are not materialized into the aggregate context |
| bounded-context owners | 0/1 | Ownership is absent from the machine-readable domain boundary |
| nodes with owner | 0/545 | Technical and business claims cannot yet be routed to an accountable owner |
| governance with owner/steward | 0/163 | Review routing cannot be grounded in catalog ownership |
| nodes without description | 26/545 | Eight fields, eight states and ten derived surfaces remain description-free |
| explicit governance | 20/163 | Explicit annotations are present but not dominant |
| heuristic governance | 143/163 | Most governance is inferred from DTO field-name heuristics |

The catalog is already a useful deterministic machine projection, but the numbers prove why it
cannot yet serve as approved business truth: technical materialization is broad, while context,
ownership and authored provenance are thin. The source service version is also `null` in the
live payload, so the release hash currently carries more provenance than the declared service
identity.

## Remaining Runtime Measurements

The next slices still need to capture:

1. governed ingestion and RAG publication status for an approved pilot release;
2. first-turn Domain Catalog or compiled-pack items sent to the provider;
3. API, schema and component-manifest reads per corpus case;
4. candidate funnel and fallback reasons;
5. tokens, provider cost and p50/p95 latency;
6. final selected resource/component and provenance coverage;
7. behavior with vector retrieval disabled.

The trace must use the local API directly. Browser SSE is a downstream UX proof, not the
primary diagnostic instrument for retrieval behavior.

## Baseline Acceptance Gate

The source/publisher part of this inventory is operationally reproducible through the versioned
capture script, which emits `praxis.machine-first-hr-baseline/v0.1` with:

- quickstart commit and starter versions;
- Domain Catalog release id and hash;
- static and HTTP item counts;
- zero secrets or raw sensitive payloads;
- coverage gaps and the read-only config-store observation.

The full acceptance gate remains open until retrieval traces exist for every must-pass corpus
case and are compared against the budgets in the machine-first RFC. The HTTP counts prove what
the publisher exposes; they do not prove that the running LLM flow retrieves or uses it well.
