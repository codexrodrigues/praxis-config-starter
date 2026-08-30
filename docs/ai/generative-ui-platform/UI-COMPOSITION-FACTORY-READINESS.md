# Governed UI composition factory readiness

Status: prioritized evolution baseline, 2026-08-29.

## Decision

`praxis-config-starter` should become the canonical control plane for AI-authored UI composition decisions. It should not generate Angular implementation code and it should not introduce a second page DSL.

The target result for a certified rich screen is a governed semantic decision and its deterministic materializations:

```text
metadata grounding
  -> semantic intent and archetype decision
  -> governed draft
  -> simulation and diagnostics
  -> approval/publication with ETag and lineage
  -> UiCompositionPlan + WidgetPageDefinition
  -> UI conformance report
  -> Dynamic Page runtime
```

The platform already contains most of the control-plane primitives. The priority is to connect and certify them, not to start another configuration model.

## Canonical ownership

- metadata starter owns `resource + surfaces + actions + capabilities` and operation/schema resolution;
- config starter owns the authored decision, versioning, target, simulation, approval, publication, headers/ETag and materialization history;
- UI Angular owns `UiCompositionPlan` compilation and `WidgetPageDefinition` execution;
- the API quickstart is the real HTTP proof host;
- consumer applications own only business-specific inputs that cannot yet be expressed canonically and must register those as residuals.

UI surfaces, forms, tables and manifests are projections or runtime evidence. They do not become the source of the business decision.

## Existing capability inventory

### Already supported

- governed config persistence under `/api/praxis/config/**`;
- AI registry bootstrap, snapshot and health/status evidence;
- domain decision/materialization lifecycle patterns with approvals and applied heads;
- semantic grounding corpus and schemas;
- a phased Generative UI program with capability coverage, operation contracts and quality gates;
- template hashes, target identities, ETag and other ingredients required for traceability.

Classification: `ja-suportado-so-ux` when the missing work is exposing existing state, diagnostics or evidence coherently to the authoring flow.

### Already supported but fragmented

The program documents component discovery, domain-to-component continuity and generative readiness, while runtime composition lives in the UI repository. The missing factory outcome is a single governed decision that connects those existing surfaces and carries their evidence through publication.

Classification: `ja-suportado-mal-nomeado-ou-mal-materializado` for state or evidence that already exists but is not bound to the UI target.

### Partially supported

- a partial resource-backed master/detail/filter materializer now exists and can enable the official Table action discovery path from backend-owned verification; cross-language certification and the real HTTP/browser command smoke remain incomplete;
- semantic grounding exists, but transaction lifecycle and UI composition are not closed in one real HTTP proof;
- validation exists on both sides, but releases do not share one golden cross-language gate.

Classification: `suportado-parcialmente`.

### Real contract gap

The missing interoperable attestation must bind:

- canonical decision id/version;
- materialization id/version;
- target application/surface;
- metadata and registry/catalog identities/hashes;
- conformance report and diagnostics;
- lineage and publication ETag.

Classification: `lacuna-real-de-contrato`. Before defining it, implementation must inventory and reuse the existing decision snapshots, registry snapshots, target identities, template hashes, approvals and materialization heads.

## Priority backlog

### P0 — Resource-backed rich workspace decision and materializer

Issue: [#356](https://github.com/codexrodrigues/praxis-config-starter/issues/356).

Implementation status for the first slice: the generic provider now consumes the
existing verified-operation envelope, emits a resource Filter, Table master and
Dynamic Form detail, binds `requestSearch -> queryContext` and
`selectionChange -> state -> initialValue`, publishes responsive device layouts,
and enables the official Table item or collection discovery policy only for the
scope proven by a backend-owned verified `/actions/` operation. An item-only
command does not enable collection discovery, and a collection-only command does
not create row actions. Client-provided operation
envelopes are removed at HTTP ingress. The server preview/compiler path and
transactional apply/ETag path have focused tests, but this is a partial slice:
the shared #357 corpus/attestation and a real HTTP/browser command smoke are not
integrated. See [the delta audit](RESOURCE-BACKED-WORKSPACE-DELTA-AUDIT.md) for
the reuse classification and remaining gaps.

The decision must describe semantic intent, archetype, resources, surfaces, actions, capabilities, components, slots, bindings and transactional lifecycle. It should materialize both compact authoring input and portable runtime output without leaking Angular implementation detail into Java.

Minimum lifecycle:

1. resolve initial intent semantically with the LLM and governed context;
2. ground resource and operation candidates from canonical metadata;
3. author a canonical draft decision;
4. materialize and simulate against the target registry;
5. attach diagnostics and conformance evidence;
6. approve and publish under ETag/concurrency controls;
7. let the runtime consume the published projection.

Keyword, regex, fuzzy matching or aliases may rank candidates only after semantic intent has selected the correct scope. If a required tool or component contract is absent, the result is an explicit platform gap, not a text heuristic or generated host code.

### P0 — Shared golden corpus and attestation

Issue: [#357](https://github.com/codexrodrigues/praxis-config-starter/issues/357).

Extend the existing machine-first corpus rather than inventing a second test universe. Add neutral JSON fixtures for valid/invalid plans, defaults, ports, policies, transactional actions and report codes. The same corpus must run in Java and TypeScript.

The attestation is the evidence that a published materialization is compatible with a concrete target. It is not a new source of UI semantics.

### P0 dependencies in the UI runtime

- conformance/report: [praxis-ui-angular#389](https://github.com/codexrodrigues/praxis-ui-angular/issues/389);
- LinkPolicy execution: [praxis-ui-angular#390](https://github.com/codexrodrigues/praxis-ui-angular/issues/390);
- responsive semantic layouts: [praxis-ui-angular#391](https://github.com/codexrodrigues/praxis-ui-angular/issues/391);
- widget manifests/ports/providers: [praxis-ui-angular#392](https://github.com/codexrodrigues/praxis-ui-angular/issues/392).

## Required decision shape at the semantic level

This is a responsibility inventory, not a proposed DTO:

| Concern | Canonical source | Materialized consumer |
| --- | --- | --- |
| business resource and fields | metadata starter | plan/widget inputs |
| surfaces and operations | metadata starter | widgets and actions |
| current authorization/capability | resource capability endpoints | availability and fail-closed behavior |
| screen archetype | governed AI-authored decision | layout blueprint |
| component selection | registry grounded decision | component ids/manifests |
| component interaction | semantic decision | `composition.links` |
| visual placement | archetype slots/hints | canvas/layout projection |
| confirmation/error/refresh | action/lifecycle decision | runtime action orchestration |
| persistence/publication | config starter | runtime load and provenance |
| execution diagnostics | UI conformance/runtime | report and authoring feedback |

No row authorizes the config starter to copy canonical resource, component or capability truth into a private catalog.

## Factory quality gates

### Decision quality

- every draft records intent, context, candidate evidence and selected canonical targets;
- ambiguous target selection requests semantic clarification;
- no primary keyword routing;
- unsupported capability produces a structured residual.

### Materialization quality

- deterministic output for the same decision and catalog;
- no unknown component/provider/input/output/port/policy;
- no loss of confirmation, authorization, validation, error, refresh or read-after-write semantics;
- UI validator accepts the artifact before publication.

### Governance quality

- simulation is immutable and traceable;
- approval binds the reviewed decision/materialization versions;
- publication honors required headers, origin and ETag;
- concurrent or stale publication fails closed;
- rollback selects a prior governed materialization, not an arbitrary JSON patch.

### Cross-language quality

- Java and TypeScript pass the same fixtures;
- diagnostics have stable, comparable codes and targets;
- registry/catalog mismatch fails before consumer deployment;
- the API quickstart proves the complete path through real HTTP.

## Five reference archetypes

The joint UI/config gate must certify:

1. simple CRUD;
2. master-detail;
3. parent-child/related resource;
4. business command with confirmation, error, refresh and read-after-write;
5. tabs or nested workspace.

The final proof uses real resource APIs and current capabilities. Mocks may support unit tests but cannot certify an archetype.

## Delivery sequence

1. inventory existing decision, materialization, registry and corpus artifacts;
2. define the smallest resource-backed workspace decision using existing semantics;
3. integrate target registry and UI conformance simulation;
4. implement golden cross-language fixtures and the minimum attestation;
5. prove one transactional master-detail pilot by HTTP;
6. expand to the five archetypes;
7. synchronize public docs, HTTP corpus, LLM surface and official examples before declaring readiness.

## Success measures

- 100% of published UI compositions have conformance evidence;
- zero runtime contract errors for certified archetypes;
- at least 90% first-pass functional rate;
- median zero screen-specific TypeScript/HTML for certified capabilities;
- deterministic Java/TypeScript parity;
- every residual is attributed to platform, metadata, domain decision or migration workflow ownership.

## Rejected shortcuts

- generate arbitrary JSON or Angular code directly from a prompt;
- create a UI-only decision model in the consumer;
- copy metadata truth into config starter;
- use textual heuristics as the primary intent router;
- publish despite target/catalog mismatch;
- maintain parallel v1/v2 contracts during beta without a concrete operational requirement.

## Documentation impact

Implementation of either issue changes public AI/config contracts and therefore requires synchronized review of:

- `docs/ai/*` and this program package;
- public HTTP examples and LLM surface;
- API quickstart proofs;
- UI examples/playgrounds and schemas when their public artifact changes.

The first #356 slice changes generated plan shape but does not add endpoints,
headers, public DTOs or schemas. Therefore the HTTP corpus and OpenAPI surface do
not require regeneration for this cut; the cross-language corpus and attestation
remain tracked by #357.
