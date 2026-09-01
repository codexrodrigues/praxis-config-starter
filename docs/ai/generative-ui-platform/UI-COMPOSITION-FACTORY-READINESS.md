# Governed UI composition factory readiness

Status: first operationally proved master-detail pilot; canonical single-table creation and semantic
refinement stable in five consecutive local production-like runs; canonical CRUD-simple certified
in five consecutive local production-like runs with zero retries, 2026-08-31.

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

- the first resource-backed master/detail/filter materializer resolves the official Core preset and enables Table action discovery from backend-owned verification;
- the Fluxo 3 focal proof closes semantic grounding, transaction lifecycle and UI composition against real HTTP/browser infrastructure for the missions pilot;
- the existing table materializer already emits one `praxis-table`, schema-derived columns and explicit responsive geometry, while issue #365 promotes that path to the canonical `single-table` semantic archetype and closes its no-second-pass and fail-closed grounding gates;
- Java and TypeScript share the #357 golden corpus and target attestation, while release-train adoption and coverage of other archetypes remain incomplete.

Classification: `suportado-parcialmente`.

### First-pass factory evidence

The official production-like gate now owns an additive, sanitized per-scenario receipt for the
`live-resource-workspace-command` pilot. Angular emits only governed interaction counts, canonical
blocking diagnostic codes, persistence/reload hashes and ETag equality, runtime outcomes and phase
milestones. The final v1 shape also proves terminal-result/apply correlation without publishing the
identifiers, compares apply/persisted/reload payload hashes and reports deterministic repair count.
Config Starter locates the receipt by the matrix-owned test title and attachment name,
rejects unknown properties, validates the persisted/runtime proof and combines authoring turns with
Playwright retries to classify the result as `first-pass` or `eventual-pass`.
Functional behavior is represented by strict canonical assertion ids whose required set belongs to
the gate matrix for each scenario. The common receipt therefore does not encode master-detail,
Table or command fields as if they were universal platform semantics.

This is `ja-suportado-mal-nomeado-ou-mal-materializado`: the browser journey already captured the
raw turns and all functional assertions, but the published evidence exposed only aggregate test
counts. The receipt is an internal release-evidence projection; it adds no endpoint, HTTP DTO,
Angular public API or parallel authoring contract. Raw prompts, URLs, domain fixture identifiers,
ETag values and chat transcripts are deliberately excluded.

The successful Level 3 baseline from 2026-08-30 predates this receipt and therefore proves eventual
functionality, not first-pass functionality. The first post-instrumentation `full` run,
[Actions #33340504257](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/33340504257),
executed all 11 selected tests against real OpenAI, PostgreSQL/pgvector, Quickstart and Angular
sources. HTTP/SSE and Domain Catalog prerequisites passed, but the browser gate finished with seven
stable passes, one flaky pass and three failures after four retry attempts. The
`master-detail-command` scenario passed without a retry. The first `single-table-control` attempt
requested clarification with `intent-operation-unknown` and `intent-artifact-unknown`; its retry
completed the initial persisted/reloaded table lifecycle but failed later in the semantic refinement
battery because `column.header.set` was absent. It is therefore not a first-pass result and neither
archetype is certified by this failed run.

That run also exposed an evidence-retention defect: the collector threw on the aggregate Playwright
failure before extracting valid inline receipts, so `scenarioEvidence` was empty even for the passing
master-detail scenario. The collector now parses each selected attachment before enforcing aggregate
success and, only for a failed run, preserves every independently valid sanitized receipt, including a
receipt attached before a later assertion failed. The result remains `productionLike=false` and the
publication exporter remains strict; partial evidence is diagnostic and cannot certify a scenario.
This closes measurement loss without weakening the gate or creating another receipt contract.

After the resulting platform corrections, the canonical `single-table` profile passed five
independent production-like runs against the real OpenAI provider, PostgreSQL/pgvector, Quickstart,
SSE and Angular runtime. Each run executed the critical interception guard, the one-prompt creation
control and one canonical semantic refinement: `15/15` tests passed, zero were skipped, zero were
flaky and zero used a Playwright retry. All five control receipts prove one initial prompt, no
clarification/corrective prompt/deterministic repair, terminal/apply lineage, identical
apply/persisted/reload payload hashes, matching reload ETag and real resource rows. All five
refinement projections prove retained semantic-decision lineage, the governed
`column.header.set` operation, a backend-compiled patch and equivalent proposed/materialized
columns in the single matrix-authorized human turn.

The Config-owned portable evidence validator rejects missing scenarios, retries, flaky/skipped
tests, divergent receipt properties/assertions, broken persistence lineage, unattested focal limits
and incomplete semantic refinement. The five-run aggregate is
`praxis.page-builder-agentic-gate-evidence-summary/v1`; it records per-report SHA-256 and reported
1,045,517 ms across the five browser executions. This certifies the current single-table slice; it
does not imply that the broader six-archetype factory or the 90% portfolio target is complete.

The first immutable remote replay after that local series,
[Actions #33353177201](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/33353177201), exposed two
platform integration defects rather than an LLM retry: the Windows runner did not project the
matrix-owned `humanTurnLimit`, and exact operational verification discarded the already-governed
`schemaType=response` from the binding `schemaPointer`. With multiple operations for one resource,
that allowed a row table to inherit the request schema of `statsGroupBy`. Both defects are classified
as `ja-suportado-mal-nomeado-ou-mal-materializado`: `humanTurnLimit`, `schemaPointer`, `operationId`
and the semantic target surface already existed canonically. The runner now projects and cleans the
focal-turn environment, invokes the same portable evidence validator, and publishes the semantic
requirements in its result. Post-intent operation grounding now preserves the binding schema type
and ranks verified collection-read operations for row-oriented surfaces without textual intent
routing. A post-fix local production-like replay against the exact Config, Quickstart and Angular
worktrees passed `3/3`, zero retries, in 214,421.715 ms; its report SHA-256 is
`7b0e1702044efa11dfee198ce9ed5002c67d601df9922dd59305ac50c7bad64c`, with identical employee
field lineage across schema, plan, compiler and runtime and one canonical `column.header.set` turn.
This local replay proves the correction before a final remote closure gate; it does not rewrite the
failed remote run as a pass.

The corrected immutable remote replay,
[Actions #33355148829](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/33355148829),
then passed the HTTP/SSE smoke, Domain Catalog v2 smoke and the complete `single-table` Playwright
step: `3/3`, zero retries, 82,102 ms, `productionLike=true`, `evidenceValidation.passed=true` and a
first-pass receipt with equivalent apply/persisted/reload hashes. Its post-E2E publication step
still failed because an inline PowerShell conditional serialized the successful empty
`diagnosticEvidence` collection as JSON `null`; the strict exporter correctly rejected it. The
runner now materializes that property from a stable empty collection, and its Windows parser-only
gate includes an explicit JSON-array serialization fixture. This publication fix does not require
another LLM/browser execution; the remote functional evidence remains valid and the lightweight
Windows runner gate owns the remaining proof.

The 90% target and broader archetype certification remain open under
[praxis-config-starter#372](https://github.com/codexrodrigues/praxis-config-starter/issues/372).

The next incremental lane is `crud-simple`. It deliberately does not reuse the older `Fluxo 2` as
certification evidence: that journey accepts up to five typed prompts, persists a standalone
`praxis-dynamic-form` and does not execute a business write. The focal CRUD scenario requires one
semantic turn to materialize exactly one `praxis-crud`, then exercises discovery of the real
Quickstart capability snapshot (including canonical Create/Update/Delete operations) and the
request schema loaded on demand, plus Create, read-after-create, Update,
read-after-update, Delete and read-after-delete through the runtime UI. The common receipt remains
generic; the matrix owns the exact CRUD assertion ids. Fixture cleanup is fail-closed and a direct
DELETE is used only as recovery after the browser journey, never as functional proof. See
[praxis-ui-angular#453](https://github.com/codexrodrigues/praxis-ui-angular/issues/453).

### Implemented attestation contract; rollout still partial

The #357 interoperable attestation now binds the evidence it actually owns:

- corpus, schema and compiler contract hashes/receipts;
- canonical page projection hash and stable diagnostics;
- target profile id/fingerprint and derived component/port/capability/action requirements;
- registry-aware runtime receipt;
- template `registryKey`, version, ETag and `configSha256` revision evidence.

The focal Fluxo 3 proves terminal apply correlation (`streamId` + `resultEventId`), persisted page
version/ETag/hash and reload. Decision/materialization ids and approval/publication lineage remain
adjacent Config lifecycle evidence that still requires an explicit correlation proof; none of those
lifecycle fields belongs to the #357 cross-language report. Classification: `suportado-parcialmente`.
The compiler/target compatibility gap is closed; release-train adoption, explicit lifecycle-evidence
correlation and broader archetype/target coverage remain without introducing a parallel attestation
contract.

## Priority backlog

### P0 — Resource-backed rich workspace decision and materializer

Issue: [#356](https://github.com/codexrodrigues/praxis-config-starter/issues/356).

Implementation status for the first operationally proved pilot: the generic provider consumes the
backend-owned `verifiedDomainOperations` envelope with schema version
`praxis-agentic-authoring-verified-domain-operations.v2`, emits an optional resource Filter when a
verified filter operation exists, a resource Table master and Dynamic Form detail, binds
`requestSearch -> queryContext` when the Filter is present and
`selectionChange -> state -> initialValue`, publishes responsive device layouts,
and enables the official Table item or collection discovery policy only for the exact
scope reconciled with `/schemas/actions`, schemas and capabilities. An item-only
command does not enable collection discovery, and a collection-only command does
not create row actions. Client-provided operation
envelopes are removed at HTTP ingress. The server preview/compiler path and
transactional apply/ETag path have focused tests. The #357 shared corpus/attestation is integrated,
and the 2026-08-30 full-mode focal Fluxo 3 proved real LLM authoring, exact terminal-patch apply,
item action/capability discovery, governed Dynamic Form submit, `200` execution, repeated-transition
`409`, Table refresh and reload with identical payload SHA-256 and ETag. This proves the first
pilot operationally; the complete production-like matrix and additional archetypes remain release work. See
[the delta audit](RESOURCE-BACKED-WORKSPACE-DELTA-AUDIT.md) for the reuse classification and residuals.

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

### P1 — Canonical single-table decision and materializer

Issue: [#365](https://github.com/codexrodrigues/praxis-config-starter/issues/365).

The next narrow factory archetype is `layoutKind=single-table`: an AI-authored semantic decision for
exactly one governed collection resource, `artifactKind=table` and `primaryComponent=praxis-table`.
It projects one-way to `layoutPreset=single-table-page`, one Table widget and explicit canvas/device
layouts. The materialized id is not a second semantic alias and does not claim a Core catalog preset.
The compact path may skip the full intent pass only after one non-lexical operational candidate,
canonical collection binding, filtered schema and current resource capabilities are verified. Any
ambiguity or partial grounding remains fail-closed. Schema-visible fields are projected in canonical
order without the former silent 16-column cap; `hidden` and `tableHidden` remain authoritative.

Inventory update: the browser proof separates `single-table-control`, which persists the initial
one-prompt canonical creation, proves apply/readback/reload equality and real resource rows and emits
the common receipt, from `table-human-refinement`, which runs the deliberate multi-turn semantic-edit
battery without redefining that receipt. Both the focal single-table gate and the full gate require
the control and refinement scenarios; only the control owns the certification receipt. The capability
is now operationally certified for this narrow profile by five consecutive production-like runs.
It remains `suportado-parcialmente` at platform level because the broader archetype matrix,
publication artifact and consumer rollout are not yet complete.

### P1 — Canonical CRUD-simple certification

Issues: [#372](https://github.com/codexrodrigues/praxis-config-starter/issues/372) and
[praxis-ui-angular#453](https://github.com/codexrodrigues/praxis-ui-angular/issues/453).

The platform already materializes `layoutKind=resource-crud` as one `praxis-crud` whose metadata
contains the canonical resource identity and no copied action catalog. The runtime owns the
table/form/open-mode lifecycle and discovers schemas and capabilities from the backend. Explicit
workflow action/surface catalogs remain optional business contracts and are not fabricated for
ordinary CRUD. Classification: `suportado-parcialmente`; no new endpoint, DTO, public Angular contract,
component or page DSL is justified. Certification requires the focal zero-retry profile and its
consecutive production-like evidence series.

The 2026-08-31 local series passed five independent production-like executions: 10/10 required tests,
zero Playwright retries and five receipts with `firstPassFunctional=true`. Every run persisted and
reloaded the same semantic payload SHA-256, while using unique domain fixtures and cleaning them after
Create, read, Update and Delete through the runtime UI. The series also exposed and closed a systemic
Quickstart mapper defect that reassigned route-owned JPA identity during update; the host fix and its
generated-mapper regression gate are tracked by
[praxis-api-quickstart#241](https://github.com/codexrodrigues/praxis-api-quickstart/issues/241).

This certifies the narrow `crud-simple` profile for platform-internal use and removes it from the list
of unproved archetypes. Platform readiness remains `suportado-parcialmente` until parent-child/related
resource, richer business-command and tabs/nested-workspace profiles complete the matrix required by
#372 before broad Ergo promotion.

### P1 — Canonical parent-child/related-resource certification

Issues: [#382](https://github.com/codexrodrigues/praxis-config-starter/issues/382) and
[praxis-ui-angular#455](https://github.com/codexrodrigues/praxis-ui-angular/issues/455).

The inventory found no missing backend relation or Angular outlet contract. The metadata surface
already owns `childResourceKey`, `childResourcePath`, `childParentField`, selection identity,
operations and availability. The uncovered authoring contract gap is intentionally narrow:
`AgenticAuthoringVisualizationDecision.targetSurfaceId` records the exact canonical surface selected
by the LLM after governed grounding. It does not copy the relationship into Config.

The semantic path now recognizes `layoutKind=parent-child-related-resource` with
`primaryComponent=praxis-related-resource-outlet`, materializes a parent Table, selection state and
`parentResourceId` binding, and fails closed when the target surface is absent. Preview then fetches
`/schemas/surfaces?resource={parentResourceKey}` with the current principal and requires the exact
surface, `ITEM` scope, explicit availability and a complete `relatedResource` contract. A catalog
result of `resource-context-required` is accepted as the legitimate pre-selection state; invented,
unavailable or incomplete surfaces block before apply. The semantic policy independently requires
`relatedResourceGrounding.status=verified`.

The Config gate matrix now owns the focal `related-resource` profile, operations catalog scope,
sanitized receipt and `domainCatalogRagRequired=true`. Both managed runners consume that decision:
they enable Domain Catalog RAG and require the typed publication state to reach
`PUBLISHED + reconciled` before Angular or Playwright starts. The Angular scenario uses the
Quickstart mission/team pilot and the Core runtime fix that keeps contextual read URL separate from
the flat child command resource. Focused Java and Node gates pass, but the archetype is not
certified: the first integrated attempt failed to close vector publication and its no-refresh rerun
received provider `429 credit_balance_exhausted` before the browser tests. After provider recovery,
the required order is one successful focal run followed by five independent zero-retry,
zero-corrective-prompt runs accepted by the Config evidence validator. A run with
`--no-domain-catalog-rag` is diagnostic only and cannot certify this profile.

API Catalog readiness is scoped by the same canonical identity used by its persisted state:
`tenant + environment + serviceKey + releaseId`. Vector replacement carries `serviceKey` and is
serialized with the persisted revision lease, so concurrent hosts cannot make a superseded revision
mutate the corpus or combine searchable counts from different services. A gate must remain fail-closed
when `READY` is reported without reconciled counts; retrying or weakening the equality is not evidence.

Classification remains `suportado-parcialmente` until that production-like evidence closes. No Ergo
consumer migration should begin from this slice before certification.

### P1 — Canonical tabs/nested-workspace certification

Issues: [#414](https://github.com/codexrodrigues/praxis-config-starter/issues/414) and
[praxis-ui-angular#479](https://github.com/codexrodrigues/praxis-ui-angular/issues/479).

The runtime, authoring catalog, generic `UiCompositionPlan` provider and nested compiler already support
`praxis-tabs` with a collection widget and a synchronized detail/form widget. The first Config gap was in
the compact semantic orientation: `praxis-tabs` was an allowed primary component, while `tabs_layout` was
not an allowed complete composition archetype. The first production-like OpenAI proof then exposed a
second part of the same gap: approved `create`, `detail`, `edit` and `list` bindings for one canonical
resource were counted as competing resources. Both conditions forced a redundant full intent pass and
could turn an explicit, fully grounded creation request into clarification.

The canonical path treats `artifactKind=page + primaryComponent=praxis-tabs + layoutKind=tabs_layout`
as one complete AI-authored composition decision. The path is semantic, not lexical: it is accepted only
after the LLM authors the structured combination and the unique resource keeps its governed binding,
schema and capability evidence. Multiple approved surface bindings are collapsed only by canonical
`resourcePath`; bindings from different resources remain ambiguous and fail closed. The normal resource,
preview, compiler and apply gates remain mandatory.
Certification still requires the Angular production-like OpenAI scenario to prove first-pass
materialization, persisted nested paths, selection continuity and zero retry.

### P0 — Shared golden corpus and attestation

Issue: [#357](https://github.com/codexrodrigues/praxis-config-starter/issues/357).

Implemented as a neutral, versioned deterministic corpus consumed by Java and TypeScript. The audit
kept the existing semantic-grounding corpus in its retrieval role and introduced an adjacent
compiler corpus only for the real uncovered contract. It reuses template
`registryKey + version + ETag + configSha256`, fingerprints the frozen Corte A.5 target profile,
compares canonical page projection hashes and stable diagnostics, and runs registry-aware target
attestation as a separate Angular phase.

The gate includes deliberately divergent projection, target component/port, capability/action and
template-revision cases. The initial run found and corrected a real Java/TypeScript master-detail
layout drift rather than hiding it through normalization. Operational details and corpus governance
are documented in
[`ui-composition-compiler-parity-corpus-v1.md`](../agentic-authoring/ui-composition-compiler-parity-corpus-v1.md).

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

## Six reference archetypes

The joint UI/config gate must certify:

1. single-table;
2. simple CRUD;
3. master-detail;
4. parent-child/related resource;
5. business command with confirmation, error, refresh and read-after-write;
6. tabs or nested workspace.

The final proof uses real resource APIs and current capabilities. Mocks may support unit tests but cannot certify an archetype.

## Delivery sequence

1. inventory existing decision, materialization, registry and corpus artifacts;
2. define the smallest resource-backed workspace decision using existing semantics;
3. integrate target registry and UI conformance simulation;
4. implement golden cross-language fixtures and the minimum attestation;
5. prove one transactional master-detail pilot by HTTP;
6. publish a fail-closed, sanitized first-pass receipt for the operational pilot;
7. expand the same receipt to the remaining reference archetypes and measure the rate;
8. synchronize public docs, HTTP corpus, LLM surface and official examples before declaring readiness.

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
