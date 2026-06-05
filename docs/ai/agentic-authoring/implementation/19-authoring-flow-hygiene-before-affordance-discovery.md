# Authoring Flow Hygiene Before Affordance Discovery

Status: implementation checkpoint
Date: 2026-06-03
Classification: `arquitetural` and `contrato-publico`.

## 2026-06-02 Implementation Checkpoint

The first hygiene cut introduced a shared Angular client in `@praxisui/ai`:

- `AgenticAuthoringTurnClientService` starts the canonical
  `/api/praxis/config/ai/authoring/turn/stream/start` flow through
  `AiBackendApiService`.
- It connects SSE, maps intermediate events to `PraxisAssistantTurnResult`
  processing states and maps terminal result/error/cancelled events to the
  shared assistant turn contract.
- It also exposes a raw `streamEvents()` lane for component flows with custom
  preview/apply semantics, so those flows can migrate transport without losing
  their own materialization policy.
- The shared client now carries initial lifecycle affordances that were needed
  before Page Builder could safely migrate: silent-stream status and terminal
  result timeout.
- `AiBackendApiService.connectAgenticAuthoringTurnStream` now falls back to
  `fetch` SSE parsing when `EventSource` is unavailable, reducing the transport
  gap with the Page Builder local stream implementation.
- `connectAgenticAuthoringTurnStream` also exposes optional lifecycle telemetry
  for probe readiness, transport opening and first-event receipt; the shared
  turn client maps these to `stream-lifecycle` events.
- The shared turn client and backend API client now accept per-call `baseUrl`
  and header overrides, preserving component/host endpoint configuration during
  migration.
- The service is exported from the `@praxisui/ai` public API so component
  flows can migrate away from `/patch` without each component reimplementing
  stream normalization.

This checkpoint does not migrate table, Page Builder or other component flows
yet. Page Builder was inspected as the first stream-first candidate, but it
The shared client now carries the Page Builder stream features that previously
blocked safe transport reuse: silent-stream status, result timeout, fetch
fallback, lifecycle telemetry and per-call endpoint/header configuration. The next cut can migrate
`PageBuilderAgenticAuthoringService.streamTurn` to `AgenticAuthoringTurnClientService.streamEvents()`
and then delete the duplicate local SSE parser/watchdogs if the focal Page
Builder specs and build remain green.

## 2026-06-03 Presentation Affordance Contract Checkpoint

The affordance discovery lane is now promoted beyond prompt guidance:

- `presentationAffordanceDiscovery` is registered as a read-only internal
  agentic tool and is advertised in the authoring context bundle as the
  canonical way to ask which presentation affordances fit a resolved target.
- `AgenticAuthoringConsultativeAnswerService` calls the tool while assembling
  evidence for consultative table answers, so the response synthesis can use
  the returned payload instead of only knowing that the tool exists.
- Built-in presentation affordance catalogs exist for table, form, chart and
  filter components, and registry-backed component definitions can override
  them through
  `componentDefinition.jsonSchema.authoringManifest.presentationAffordances`.
- The public manifest slice endpoint
  `GET /api/praxis/config/ai/authoring/manifests/{componentId}/presentation-affordances`
  exposes the governed catalog shape for host-neutral discovery.
- `getManifestSlice` also accepts `sliceKind=presentationAffordances`, allowing
  the agentic tool loop to fetch only this public slice when the LLM asks for
  available presentation capabilities.
- `AgenticAuthoringManifestContractValidator` validates the optional
  `presentationAffordances` block when present. Absence still allows the
  platform to fall back to built-in catalogs, but an invalid registry-provided
  catalog fails explicitly instead of being silently treated as missing.
- `praxis-ui-angular` now publishes the `praxis-table`
  `presentationAffordances` catalog in its authoring manifest, the AI registry
  generator projects it into the component-definition ingestion payload, and
  `praxis-api-quickstart` proves the public manifest slice through real HTTP.

Validation run:

```bash
mvn -Dtest=AgenticAuthoringManifestContractValidatorTest,AgenticAuthoringPresentationAffordanceDiscoveryServiceTest,AgenticAuthoringManifestControllerTest,AgenticAuthoringToolRegistryTest,AiApiContractOpenApiTest test
```

Result: 45 tests passed locally.

Remaining maturity work:

- connect registry ingestion diagnostics to operational authoring manifest
  publication errors, so host-provided catalogs fail at intake with the same
  explicit contract messages;
- broaden target profile grounding so computed/derived columns carry output
  type, examples, dependencies and current renderer state before the LLM asks
  for affordances.

## Problem

The table assistant exposed a platform-level gap: after a calculated column was
created without a reliable output type, the next turn offered date formatting
options for a textual column. The local symptom is in table formatting, but the
root cause is broader: Praxis currently has more than one authoring path that
can answer the same user intent.

The platform must not solve this by adding a table-specific list of formats or
by stuffing every possible renderer, badge, icon, layout and alignment option
into the prompt. The correct maturity target is agentic discovery: the LLM
understands the semantic target, asks canonical tools for compatible
affordances, reasons over the returned options and either proposes a governed
decision or asks for clarification.

Before adding that missing affordance discovery capability, the authoring flow
needs hygiene. Adding one more canonical-looking service while `/patch`,
component-local flows and manifest tooling still compete would preserve the
same confusion under a new name.

## Current Inventory

Canonical backend candidate:

- `AgenticAuthoringTurnEngine`
- `AgenticAuthoringTurnStreamService`
- `AgenticAuthoringManifestService`
- `AgenticAuthoringTargetResolverRegistry`
- `AgenticAuthoringValidatorRegistry`
- `AgenticAuthoringToolRegistry`
- `AgenticAuthoringToolLoopExecutor`

These already form the right boundary for semantic authoring turns, target
resolution, manifest validation, tool execution and stream progress.

Legacy or transitional backend path:

- `AiOrchestratorController` exposes `POST /api/praxis/config/ai/patch`.
- `AiPatchStreamController` exposes `/patch/stream/**`.
- `AiOrchestratorService` still owns large deterministic/fallback logic,
  including prompt-shaped table formatting options and type filtering based on
  incomplete `dataProfile`.
- `AiStreamService` was found documented in code as canonical for
  `/patch/stream/**` during this investigation; that wording is misleading for
  the platform target and should be treated as a compatibility boundary.

Frontend split:

- `@praxisui/ai` exposes both legacy patch APIs and agentic turn stream APIs.
- Page Builder has a real stream-first authoring flow through `streamTurn`.
- Table, dynamic form, manual form, list, tabs, stepper, expansion and the
  dynamic-page shared flow are named `AgenticAuthoringTurnFlow` but still call
  `getPatch()` or `/patch/stream`.
- The assistant shell itself still has direct patch stream behavior, so a
  component can appear agentic while the transport and response contract are
  still legacy.

Table-specific evidence:

- `column.computed.add` can omit `outputType`.
- Current table state digest is too shallow for downstream turns: it carries
  visible identity/order information, but not enough semantic profile for
  computed columns, dependencies, renderer, value distribution or examples.
- Format quick replies are built from broad presets and are filtered only when
  `dataProfile.columns[field].inferredType` is available. A computed field not
  present in raw row data therefore receives generic options, including date
  presets.

## Decision

Do not create a standalone `presentationAffordanceDiscovery` service as the
next first step.

Instead, first make one canonical authoring lane explicit:

1. `/api/praxis/config/ai/authoring/turn/**` is the semantic authoring lane.
2. `/patch` and `/patch/stream` are compatibility adapters until migrated.
3. Component flows must use a shared `@praxisui/ai` agentic turn client instead
   of each component translating patch responses independently.
4. Target compatibility must be resolved by the existing manifest, target
   resolver, capabilities and validator boundary.
5. Presentation affordance discovery becomes a tool/capability inside that
   boundary after the duplicate paths are contained.

The future tool can still be named `presentationAffordanceDiscovery`, but it
should not be an isolated catalog. It should derive from the authoring manifest,
component capability catalogs, runtime metadata, target profile, validators and
host-published context.

## Hygiene Sequence

### 1. Name the legacy boundary

Mark `/patch` and `/patch/stream` as transitional compatibility paths in docs,
tests and code comments. Do not remove them immediately, but stop calling them
canonical in contexts where the agentic turn lane is the target.

Acceptance signal:

- A developer reading `@praxisui/ai` or `praxis-config-starter` can tell which
  endpoint is canonical for new semantic authoring work.

### 2. Introduce a shared frontend agentic turn adapter

Create a reusable `@praxisui/ai` adapter for:

- starting an agentic authoring turn stream;
- connecting SSE;
- normalizing terminal events into `PraxisAssistantTurnResult`;
- carrying current component/page state, selected target, runtime state,
  data profile, schema fields, context hints and attachments in one request
  shape;
- preserving host neutrality by avoiding component-specific endpoint forks.

Acceptance signal:

- Page Builder can use the adapter without losing its stream-first behavior.
- Table can migrate to the adapter without inventing a table-only backend
  endpoint.

### 3. Rebase `/patch` onto the canonical core or freeze it

Choose one beta-clean path:

- preferred: `/patch` delegates to the same semantic authoring core and only
  adapts the final response to the legacy `AiOrchestratorResponse`; or
- acceptable short-term: `/patch` is explicitly frozen as compatibility and is
  no longer expanded with new semantic behavior.

Acceptance signal:

- New capabilities are added to the agentic turn core, not to
  `AiOrchestratorService` deterministic branches.

### 4. Promote target profile as canonical grounding

Define the target profile needed before an LLM can ask for affordances:

- target kind and path;
- current config value and inherited/default values;
- component type and runtime surface;
- semantic type, declared type and inferred type;
- computed/derived expression, dependencies and output type;
- sample values and value distribution when safe;
- current renderer/format/alignment/layout;
- host/domain context that explains the field meaning;
- constraints and validators already declared by the manifest.

Acceptance signal:

- A computed textual column such as `Status Priority` is visible to the agent as
  a textual/derived target with examples like `PLANEJADA - ALTA`, even when the
  raw backend schema has no such field.

### 5. Implement compatibility validators before broad recommendations

Harden validators that are already named or implied by the manifests, including
format preset support, renderer type support and renderer config compatibility.
Recommendations should be gated by validators before they become quick replies.

Acceptance signal:

- Date formats cannot be offered for a target whose resolved profile is textual
  unless the user explicitly asks for a conversion decision and the preview
  validates that conversion.

### 6. Add affordance discovery as an agentic tool

Only after the above hygiene, add a canonical tool that answers:

> Given this resolved target profile and intent, which presentation
> affordances are compatible, available and explainable?

The tool should return ranked, structured candidates, not prompt prose:

- renderer affordances: text, badge, chip, icon, value mapping,
  conditional renderer, composed renderer;
- format affordances: numeric, currency, date/time, boolean, categorical,
  custom masks where supported;
- layout affordances: alignment, two-line composition, prefix/suffix,
  secondary line, emphasis and density;
- governance data: source, confidence, required clarification, validator
  evidence and preview constraints.

Acceptance signal:

- The LLM can ask "what can I do with this target?" and receive hundreds of
  possibilities when needed, while the user only sees the few options that fit
  the current semantic target and business intent.

## Impact Map

Canonical backend affected:

- `praxis-config-starter`
- `/api/praxis/config/ai/authoring/**`
- manifest, target resolver, validator, tool loop and turn stream services
- possibly AI contract docs and generated bindings if request/response shapes
  change

Runtime/frontend affected:

- `praxis-ui-angular`
- `@praxisui/ai`
- `@praxisui/table`
- other component flows currently named agentic but calling `/patch`
- assistant shell behavior around stream result normalization

Proof hosts and derived artifacts:

- `praxis-api-quickstart` as operational proof host only
- `praxis-ui-landing-page` dynamic page examples if public examples change
- `praxisui-http-examples` and `LLM_SURFACE.md` if public HTTP/LLM surfaces are
  renamed, promoted or deprecated
- generated Angular AI contracts if backend contract changes

Minimum validation when implementation starts:

- backend focal tests for `AgenticAuthoringToolRegistry`,
  `AgenticAuthoringToolLoopExecutor`, `AgenticAuthoringTurnEngine`,
  `AgenticAuthoringManifestService`, `AgenticAuthoringTargetResolverRegistry`
  and `AgenticAuthoringValidatorRegistry`;
- Angular focal tests for `AiBackendApiService` and the new shared turn
  adapter;
- table turn-flow tests for computed textual columns, especially no date quick
  replies for textual derived targets;
- one local dynamic-page/table browser smoke only after the migrated flow can be
  exercised end to end.

Breaking-change risk:

- low for this document;
- medium for naming/deprecation of patch APIs;
- high if public agentic turn contracts gain required target-profile or
  affordance fields.

## Non-Goals

- Do not add a table-only format endpoint.
- Do not duplicate the component capability catalog in a new backend service.
- Do not make keyword routing decide the primary authoring intent.
- Do not keep growing `AiOrchestratorService` with new deterministic branches.
- Do not make Angular state or examples the source of business semantics.

## Implementation Checkpoint: Shared Angular Stream Client Adopted

The first hygiene cut is implemented on the Angular side:

- `@praxisui/ai` now owns the shared agentic turn stream client for the
  canonical `/authoring/turn/stream/**` flow.
- `AiBackendApiService` supports per-call authoring base URL and headers,
  lifecycle diagnostics, EventSource transport and fetch SSE fallback.
- `AgenticAuthoringTurnClientService` exposes both raw stream events and
  normalized assistant-turn results, including silence, result and stream
  timeout handling.
- `@praxisui/page-builder` delegates `streamTurn` to the shared client and no
  longer carries its own EventSource/fetch/probe/SSE parser implementation.
- Page Builder stream tests now assert the delegation contract and error
  compatibility; transport behavior is covered in `@praxisui/ai`.
- `@praxisui/table` now receives the shared client at runtime and prefers the
  canonical agentic turn stream when available, while preserving the legacy
  `/patch` path only as compatibility/fallback.
- Table turn-flow tests assert that the canonical stream receives full grounding
  context: current state, data profile, runtime state, schema fields and
  authoring context hints.

Validated locally:

- `ng build praxis-ai`
- `ng build praxis-page-builder`
- `ng test praxis-ai --watch=false --progress=false --include=projects/praxis-ai/src/lib/core/services/agentic-authoring-turn-client.service.spec.ts --include=projects/praxis-ai/src/lib/core/services/ai-backend-api.service.spec.ts`
- `ng test praxis-page-builder --watch=false --progress=false --include=projects/praxis-page-builder/src/lib/ai/page-builder-agentic-authoring.service.spec.ts`
- `ng build praxis-table`
- `ng test praxis-table --watch=false --progress=false --include=projects/praxis-table/src/lib/ai/table-agentic-authoring-turn-flow.spec.ts`

## Implementation Checkpoint: Presentation Affordance Discovery Tool Introduced

The first backend affordance cut is implemented as a governed authoring tool:

- `presentationAffordanceDiscovery` is registered in
  `AgenticAuthoringToolRegistry` as a read-only, route-scoped grounding tool.
- The tool returns target-aware presentation affordances for `praxis-table`
  column targets using the public `@praxisui/core:ColumnDefinition` contract as
  source reference.
- The first table projection includes alignment, badge, chip, icon, compose,
  conditional renderer and type-compatible value format affordances.
- For textual/computed string targets it does not expose date formats, avoiding
  the exact failure mode observed in the dynamic-page example.
- `AgenticAuthoringContextBundle` now advertises this tool to the LLM context so
  consultative flows can ask for/read presentation options instead of inventing
  them or relying on generic date formatting fallbacks.

Validated locally:

- `mvn -Dtest=AgenticAuthoringToolRegistryTest,AgenticAuthoringContextBundleTest test`

The remaining platform work is to wire this discovery result into the turn
planning/answer synthesis path so consultative answers can include the
affordance payload itself, then remove the table legacy `/patch` fallback after
the backend canonical turn covers every table response mode.

## Implementation Checkpoint: Consultative Answer Grounded By Affordances

The consultative answer path now consumes the discovery tool result when the
turn context already identifies a presentation target:

- `AgenticAuthoringConsultativeAnswerService` accepts an optional
  `AgenticAuthoringToolRegistry`.
- When `contextHints` identifies `praxis-table` plus a column target/type, the
  service invokes `presentationAffordanceDiscovery` during evidence assembly.
- The resulting payload is included in `Grounded evidence` under
  `presentationAffordanceDiscovery`, so the LLM can answer using concrete
  target-aware options instead of only the coarse component capability catalog.
- The Spring autoconfiguration wires the registry through `ObjectProvider`,
  preserving compatibility for hosts that override the consultative service.
- A regression test captures the prompt sent to the provider and verifies that a
  textual `statusPriority` column receives badge/chip/compose/alignment
  affordances and no date-format affordance.

Validated locally:

- `mvn -Dtest=AgenticAuthoringTurnEngineTest#consultativeAnswerPromptIncludesPresentationAffordanceDiscoveryEvidenceForTableColumn,AgenticAuthoringToolRegistryTest,AgenticAuthoringContextBundleTest test`

The remaining platform work is to let the governed tool loop request this
discovery when target/type context is incomplete, then remove the table legacy
`/patch` fallback after backend canonical turn coverage is complete.

## Implementation Checkpoint: Partial Affordance Discovery Without Type Guessing

The consultative discovery path now supports incomplete target/type context
without silently treating unknown values as text:

- `presentationAffordanceDiscovery` reports `dataType=unknown` when type,
  output type or inferred type are absent.
- Unknown type results include general table-column presentation affordances and
  `requiresTypeConfirmation=true`.
- Unknown type results do not include type-specific date or numeric format
  affordances.
- `AgenticAuthoringConsultativeAnswerService` now invokes discovery when the
  governed context identifies a `praxis-table` column target even if field/type
  details are incomplete.
- Regression tests cover both exact string targets and partial/unknown targets.

Validated locally:

- `mvn -Dtest=AgenticAuthoringToolRegistryTest,AgenticAuthoringTurnEngineTest#consultativeAnswerPromptIncludesPresentationAffordanceDiscoveryEvidenceForTableColumn+consultativeAnswerPromptIncludesPartialPresentationAffordanceDiscoveryWhenTypeIsMissing test`

The remaining platform work is to promote the affordance catalog from the
initial in-code table projection into a registry/manifest-backed provider model,
then use the same contract for other component targets before removing the table
legacy `/patch` fallback.

## Implementation Checkpoint: Affordance Discovery Provider Boundary

The presentation affordance projection no longer lives inside the tool executor:

- `PresentationAffordanceDiscoveryToolRequest` is now an explicit request
  contract for the discovery boundary.
- `AgenticAuthoringPresentationAffordanceDiscoveryService` delegates discovery
  to registered `AgenticAuthoringPresentationAffordanceProvider` instances.
- `AgenticAuthoringTablePresentationAffordanceProvider` owns the initial
  `praxis-table` column projection and keeps the `@praxisui/core:ColumnDefinition`
  source reference.
- `presentationAffordanceDiscovery` remains the governed read-only tool; it now
  validates route/phase/payload and delegates to the discovery service.
- Spring autoconfiguration registers the table provider and aggregates all
  available providers, so future component targets can extend the same contract
  without adding parallel tool paths.

Validated locally:

- `mvn -Dtest=AgenticAuthoringToolRegistryTest,AgenticAuthoringPresentationAffordanceDiscoveryServiceTest,AgenticAuthoringTurnEngineTest#consultativeAnswerPromptIncludesPresentationAffordanceDiscoveryEvidenceForTableColumn+consultativeAnswerPromptIncludesPartialPresentationAffordanceDiscoveryWhenTypeIsMissing test`

The remaining platform work is to promote provider payloads from Java-coded
projections into a registry/manifest-backed catalog and then add providers for
other component targets before removing the table legacy `/patch` fallback.

## Implementation Checkpoint: Table Affordances Moved To Versioned Catalog

The initial table affordance list now follows the existing component-capability
catalog pattern:

- `src/main/resources/ai-authoring/table-presentation-affordances.v0.json`
  stores the versioned `praxis-table` presentation affordance catalog.
- `AgenticAuthoringPresentationAffordanceCatalog` loads and validates the
  resource-backed catalog.
- `AgenticAuthoringTablePresentationAffordanceProvider` now projects compatible
  affordances from that catalog instead of hardcoding the affordance list in
  Java.
- Unknown type compatibility is explicit per affordance, so date and numeric
  value formats remain excluded until the target type is grounded.

Validated locally:

- `mvn -Dtest=AgenticAuthoringToolRegistryTest,AgenticAuthoringPresentationAffordanceDiscoveryServiceTest,AgenticAuthoringTurnEngineTest#consultativeAnswerPromptIncludesPresentationAffordanceDiscoveryEvidenceForTableColumn+consultativeAnswerPromptIncludesPartialPresentationAffordanceDiscoveryWhenTypeIsMissing test`

The remaining platform work is to register equivalent resource-backed catalogs
for other authorable components and decide whether these affordance catalogs
should also be surfaced through the public authoring manifest endpoints or stay
as internal grounding-only catalogs.

## Implementation Checkpoint: Multi-Component Resource-Backed Affordances

The affordance discovery provider is no longer table-specific:

- `AgenticAuthoringResourceBackedPresentationAffordanceProvider` loads multiple
  versioned presentation affordance catalogs and serves them through the same
  `presentationAffordanceDiscovery` tool.
- `AgenticAuthoringTablePresentationAffordanceProvider` was removed, avoiding a
  duplicate path between "table provider" and "catalog provider".
- `AgenticAuthoringPresentationAffordanceCatalog` now declares
  `defaultTargetKind`, so each component can define its natural target scope
  without table assumptions.
- New initial resource-backed catalogs were added for:
  - `praxis-dynamic-form`
  - `praxis-chart`
  - `praxis-filter`
- The Spring autoconfiguration now registers the resource-backed provider as the
  default affordance provider.

Validated locally:

- `mvn -Dtest=AgenticAuthoringToolRegistryTest,AgenticAuthoringPresentationAffordanceDiscoveryServiceTest,AgenticAuthoringTurnEngineTest#consultativeAnswerPromptIncludesPresentationAffordanceDiscoveryEvidenceForTableColumn+consultativeAnswerPromptIncludesPartialPresentationAffordanceDiscoveryWhenTypeIsMissing test`

The remaining platform decision is whether affordance catalogs remain
grounding-only internal tools or become a public slice of authoring manifests,
for example through `getManifestSlice` or a dedicated manifest section.

## Implementation Checkpoint: Affordances Exposed As Manifest Slice

Presentation affordances are now a public authoring-manifest slice, not only an
internal LLM grounding tool:

- `AgenticAuthoringPresentationAffordanceCatalogService` centralizes the
  resource-backed catalog source.
- `AgenticAuthoringResourceBackedPresentationAffordanceProvider` consumes that
  catalog service instead of owning a parallel catalog map.
- `AgenticAuthoringManifestService` exposes
  `listPresentationAffordances(componentId)`.
- `AgenticAuthoringManifestController` exposes
  `GET /api/praxis/config/ai/authoring/manifests/{componentId}/presentation-affordances`.
- `getManifestSlice` accepts `sliceKind=presentationAffordances`, so the LLM can
  retrieve the same public slice through the governed tool path.
- The OpenAPI contract now declares
  `AgenticAuthoringPresentationAffordanceCatalog` and
  `AgenticAuthoringPresentationAffordance`.

Validated locally:

- `mvn -Dtest=AgenticAuthoringToolRegistryTest,AgenticAuthoringPresentationAffordanceDiscoveryServiceTest,AgenticAuthoringTurnEngineTest#consultativeAnswerPromptIncludesPresentationAffordanceDiscoveryEvidenceForTableColumn+consultativeAnswerPromptIncludesPartialPresentationAffordanceDiscoveryWhenTypeIsMissing,AgenticAuthoringManifestControllerTest,AiApiContractOpenApiTest test`

The next platform work is to prove additional component families through the
same registry path, without introducing frontend-local metadata forks.

## Implementation Checkpoint: Registry-Backed Affordance Overrides

Hosts can now publish presentation affordance catalogs through the same governed
component-definition registry path used by authoring manifests:

- `AgenticAuthoringPresentationAffordanceCatalogService` accepts an optional
  `AiRegistryRepository`.
- For a component, the service first checks
  `componentDefinition.jsonSchema.authoringManifest.presentationAffordances` in
  `ai_registry`.
- If the registry catalog exists and is valid, it overrides the built-in starter
  catalog for that component.
- If no registry catalog exists, the built-in resource catalog remains the
  fallback.
- `AgenticAuthoringResourceBackedPresentationAffordanceProvider`,
  `presentationAffordanceDiscovery`, `getManifestSlice` and the public
  `/presentation-affordances` manifest endpoint all consume the same catalog
  service, so registry overrides do not create a second semantic path.
- Spring autoconfiguration wires `AiRegistryRepository` into the catalog service
  when available.

Validated locally:

- `mvn -Dtest=AgenticAuthoringPresentationAffordanceDiscoveryServiceTest,AgenticAuthoringToolRegistryTest,AgenticAuthoringManifestControllerTest,AiApiContractOpenApiTest test`

The registry path is guarded by
`AgenticAuthoringManifestContractValidator`, so invalid
`authoringManifest.presentationAffordances` payloads fail with explicit
governance diagnostics instead of silently falling back to the built-in catalog.

## Implementation Checkpoint: Real Host Published Affordance Proof

The registry-backed catalog path has now been proven against existing repository
hosts instead of a synthetic host fixture:

- `praxis-ui-angular` publishes `presentationAffordances` in the real
  `praxis-table` authoring manifest.
- `npm run generate:registry:ingestion` projects that catalog into
  `dist/praxis-component-registry-ingestion.json`.
- `praxis-api-quickstart` ingests the generated registry payload through
  `POST /api/praxis/config/ai-registry/component-definitions`.
- The Quickstart host then serves the Angular-published catalog through
  `GET /api/praxis/config/ai/authoring/manifests/praxis-table/presentation-affordances`.
- `AgenticAuthoringManifestController` is explicitly registered by
  `AgenticAuthoringAutoConfiguration` when
  `praxis.ai.authoring.http-enabled=true`, avoiding component-scan timing
  drift in host applications.

Validated locally:

- `npm run generate:registry:ingestion`
- `mvn -Dtest=AgenticAuthoringManifestControllerTest,AgenticAuthoringManifestContractValidatorTest,AgenticAuthoringPresentationAffordanceDiscoveryServiceTest,AgenticAuthoringManifestServiceTest test`
- `mvn -Dtest=AiRegistryPresentationAffordancesQuickstartIntegrationTest -Dpraxis.config.version=0.1.0-rc.49 test`
