# Agentic Authoring Implementation Ready Plan

Date: 2026-04-29

Status: implementation-ready planning source. This document supersedes the
execution order in `02-implementation-backlog.md` when there is a conflict.
As of 2026-04-30, Phases 1-6 below are implemented in `main`; the next active
implementation phase is Phase 7.

## Premise

Praxis is a platform of semantic decisions authored by AI.

Agentic authoring in the Page Builder has two different responsibilities that
must not be collapsed:

- component/page authoring: create or modify a page artifact, widget, form,
  table, chart or layout projection;
- governed shared-decision authoring: capture business rules, policies,
  eligibility, validation, compliance or reusable operational decisions under
  `/api/praxis/config/domain-rules/**`.

The Page Builder can be a cockpit and runtime for governed decisions, but it is
not the source of truth for those decisions.

## Current Valid Problems

The following problems from the previous implementation docs are still valid:

- Persistent project knowledge is not yet a governed platform source. Client
  conversation history is not a canonical memory model.

The following problems were closed by the first implementation sequence:

- `AgenticAuthoringTurnEngine` now owns backend turn orchestration, while
  `AgenticAuthoringTurnStreamService` remains the lifecycle/SSE owner.
- `searchApiResources` is a registered executable internal tool, route-scoped
  and bounded by `MAX_TOOL_CALLS_PER_TURN`.
- `currentPage` structural inspection is available and resolver/planner paths
  prefer structured fields over summary text when present.
- Semantic retrieval, lexical fallback, broad artifact discovery, context-hint
  candidates and deterministic overrides have explicit provenance labels and
  safe stream/tool diagnostics.
- Backend-owned self-healing now classifies preview outcomes, emits safe repair
  progress and retries recoverable preview failures once.

The following problems are partially superseded:

- The Angular Page Builder already has a turn stream path through
  `streamTurn`, so the task is no longer "add stream support". The task is to
  make the stream the default path for the right Page Builder flow and keep
  fallback explicit.
- Shared-rule continuation is already implemented as a cockpit surface for
  `domain-rules`. The next implementation must integrate with that canonical
  path, not create a parallel Page Builder rule-authoring tool.

## Repository Investigation Learnings

The 2026-04-29 repository investigation confirmed that the next phase is an
extraction and hardening effort, not a rewrite.

Reusable backend assets:

- `AgenticAuthoringTurnStreamService` already implements start/connect, SSE
  replay, probe, cancel, timeout scheduling, ownership checks and event append.
  Keep it as the stream lifecycle owner and extract only decision orchestration.
- `AiTurnEventService`, `AiTurnEventEnvelope` and `AiSensitiveDataRedactor`
  already provide the canonical persisted event/replay/redaction path. New
  engine/tool events must pass through this path instead of creating a second
  audit stream.
- `AgenticAuthoringResourceDiscoveryService.search` is the correct first
  executable tool candidate. Promote it through an internal registry as
  `searchApiResources` rather than inventing a new discovery surface.
- `AgenticAuthoringCurrentPageAnalyzer` already knows how to extract form
  widgets, resource paths, schema URLs, submit URLs, field metadata and
  transient/server-backed bindings. Use it as the basis for structural
  inspection before adding any new summary heuristic.
- `AgenticAuthoringAutoConfiguration` already wires most authoring services.
  New engine, event sink and tool registry beans should be added there so the
  starter remains the canonical integration point.

Reusable governed-decision assets:

- `DomainRuleService` and `DomainRuleController` already own intake,
  definition creation/listing, status transitions, simulation, publication,
  materialization listing/creation/status transitions and safe timeline
  projection.
- The current domain-rules lifecycle is sufficient for the first governed
  handoff: the engine should initially return `route_required` and let the
  cockpit continue through canonical endpoints.
- Any future auto-intake or auto-simulation from the engine must be treated as
  a separate `contrato-publico` phase because it changes idempotency, actor,
  tenant/environment and write semantics.

Reusable Angular assets:

- The Page Builder authoring service already posts to
  `/api/praxis/config/ai/authoring/turn/stream/start`, connects EventSource,
  probes streams and keeps legacy fallback behavior.
- The Page Builder component already renders the shared-rule handoff and can
  call canonical domain-rules actions through `DomainRuleService`.
- `@praxisui/core` already exposes `DomainRuleService` for intake,
  definition, simulation, publication, materialization and timeline calls.
  Do not duplicate this in Page Builder-specific services unless a narrower UI
  adapter is needed.

Reusable proof assets:

- `praxis-api-quickstart/scripts/verify-domain-rules-runtime.sh` is the
  operational HTTP proof for the domain-rules lifecycle and runtime
  enforcement.
- `praxisui-http-examples` already catalogs protected write examples and
  read-only LLM surface examples for materialization/enforcement evidence.
- `scripts/workspace/run-local-readiness-lane.sh` already defines local-first
  lanes for `domain-rules-runtime`, `domain-rules-timeline-runtime` and
  `shared-rule-timeline-cockpit`; use these before any GitHub Actions gate.

Do not reuse as canonical sources:

- `AiOrchestratorService` legacy patch flow for business rules.
- `currentPageSummary` as a source of truth when `currentPage` is available.
- Angular state, browser storage, quick replies, `WidgetPageDefinition` or
  safe timeline payloads as primary storage for reusable rules.
- Page Builder preview/apply as the materialization path for governed shared
  decisions.
- GitHub Actions as the normal development loop when local lanes can prove the
  same phase.

## Non-Negotiable Routing Rule

Before any preview, patch or page mutation, classify the user intent:

1. If the request is about page/component composition, continue in the Page
   Builder authoring path.
2. If the request is about business policy, validation, eligibility,
   compliance, approval, workflow governance, reusable rule or shared semantic
   decision, route to governed shared-decision authoring.
3. If both are present, split the turn:
   - return a `route_required` handoff to canonical `domain-rules`;
   - let the cockpit/user continue through existing canonical endpoints;
   - consume only canonical materialization descriptors/results emitted by the
     `domain-rules` owner.

This classification must happen before preview, patch or mutation. Safe
grounding tools may run after the route is classified, but only within the
allowed route scope. For example, a shared-decision route may use
`searchApiResources` to ground `/api/helpdesk/chamados`, but it must not call
Page Builder preview or apply page mutations.

Do not implement `createBusinessRule`, `publishRule`, `approveRule` or
`materializeRule` as Page Builder-local tools. Their canonical owner is
`/api/praxis/config/domain-rules/**`.

Do not persist reusable rule conditions, policies, approvals, eligibility,
validation, timeline, materialized payloads or hidden governance state in
`WidgetPageDefinition`, Page Builder metadata, quick replies, browser storage or
Angular state as the primary source. Those may only reference canonical
`domain-rules` artifacts or derived materialization descriptors.

## Target Architecture

### Backend

- `AgenticAuthoringTurnEngine` owns turn orchestration.
- `AgenticAuthoringTurnStreamService` owns stream lifecycle, replay, probe,
  cancel, timeout and event emission only.
- `AgenticAuthoringTurnEventSink` or equivalent internal interface lets the
  engine request safe event emission without depending on `AiTurnEventService`
  or `SseEmitter`.
- All engine/tool events must still flow through the stream service sink,
  persist via `AiTurnEventService`, apply `AiSensitiveDataRedactor`, and remain
  reproducible by replay/probe.
- `AgenticAuthoringToolRegistry` exposes executable backend tools.
- Tool execution is route-scoped, auditable and bounded by a max call count per
  turn.
- `currentPage` is analyzed structurally before summary is considered.
- Retrieval has explicit source/provenance:
  - semantic catalog retrieval;
  - lexical fallback;
  - deterministic override;
  - context-hint candidate.
- Shared-decision authoring exits the Page Builder preview path and returns a
  governed handoff to `domain-rules`.

### Frontend

- Page Builder presents stream progress and final backend result.
- Page Builder does not select resources, execute tools or invent business
  rules locally.
- Browser tests must prove that tool progress and diagnostics originate from
  backend SSE/events, not client-side quick reply or `contextHints` execution.
- Shared-rule handoff remains the UI continuation surface for governed
  decisions.
- Fallback to non-stream flow is visible, limited and not used for protected
  governed-decision flows.
- When a governed decision cannot be routed safely, the UI fails closed with a
  clear diagnostic instead of falling back to preview/page mutation.

Fallback matrix:

| Condition | Component/page authoring | Governed shared-decision authoring |
| --- | --- | --- |
| Stream start unavailable before classification | visible fallback to synchronous authoring may be allowed if the request is not governed | fail closed; do not call preview/apply |
| Probe/EventSource failure after stream start | show reconnect/retry state and preserve replay cursor | show reconnect/retry state; do not execute domain-rules writes locally |
| Backend returns `route_required` | render handoff; do not treat as error | render shared-rule cockpit handoff |
| Provider timeout/error before route is known | terminal error or retry according to backend classification | terminal safe diagnostic; no legacy preview fallback |
| 401/403/auth failure | auth diagnostic only | auth diagnostic only; no local rule execution |
| 404 for canonical backend endpoint | configuration/runtime diagnostic | stop the governed flow; do not invent alternate endpoint |
| Browser reload mid-flow | recover from persisted config/stream replay when possible | recover from canonical domain-rules ids/handoff only |

### Contract

- Keep `AiTurnEventEnvelope` as the canonical SSE envelope.
- Do not add a second stream format.
- Do not promote tool schemas to public contract until the runtime behavior and
  browser proof are stable.
- Tool events should be emitted as safe progress/diagnostic projections, not as
  raw prompts, conditions, materialized payloads or hidden chain-of-thought.
- Tool progress payloads must use a stable safe shape, such as `phase`,
  `label`, and allowlisted `diagnostics`. Do not rely on fields that the event
  redactor may replace, and test with the real `AiTurnEventService` pipeline.
- Safe diagnostics must use internal projection DTOs with a closed field
  allowlist. Negative tests must prove prompts, rule conditions, materialized
  payloads, sensitive headers and hidden reasoning cannot leak through tool or
  stream diagnostics.
- Keep tool definition/call/result types internal. If a field such as
  `provenance` must become visible in an HTTP/SSE DTO, classify that change as
  `contrato-publico` and update OpenAPI/docs/tests in the same cycle.

## Domain Rules Canonical Contract

Governed shared-decision handoff and cockpit flows must preserve the public
contract of `/api/praxis/config/domain-rules/**`.

Minimum canonical lifecycle:

- intake captures a natural-language decision request as a governed draft;
- definition persists the canonical decision and semantic target;
- simulation evaluates coverage, warnings, approvals and predicted
  materializations;
- approval/activation records governed status transitions;
- publication derives and applies eligible materializations;
- materialization references the canonical definition and target surface;
- timeline projects safe lifecycle observability;
- enforcement validation proves the derived runtime/backend behavior.

Target materialization reference for future contract-public descriptors, or for
fields already present in the current contract:

- `domainRuleId` or definition id;
- version/revision or source hash;
- governed status;
- owner surface;
- target surface/artifact type/artifact key;
- materialization id/key;
- projection type;
- provenance/ETag or equivalent concurrency reference;
- timeline/audit reference when available.

Minimum operational constraints:

- tenant, environment and user/actor headers remain explicit;
- idempotency/concurrency semantics are preserved for writes;
- prompt text, assistant message, rule condition, parameters and materialized
  payload do not leak into safe timeline or common cockpit views;
- Page Builder applies only canonical materialization descriptors/results from
  the `domain-rules` owner, never inferred local rule guidance.

Actor semantics must stay explicit. The current public HTTP boundary must not
silently invent new actor headers: use the headers already contracted by the
endpoint, and keep actor/steward/approver fields in request bodies where that is
the existing contract. Promoting a new header such as `X-User-ID` for
`domain-rules` is a separate `contrato-publico` change with controller, docs,
HTTP corpus and compatibility tests.

Required operation matrix before any auto-write from the engine:

| Operation | Endpoint | Idempotency/concurrency gate | Retry rule | Required proof |
| --- | --- | --- | --- | --- |
| Intake | `POST /domain-rules/intake` | deterministic request fingerprint or explicit idempotency key before engine auto-intake | retry only if replay returns same draft/handoff | HTTP test proving repeated request does not create unsafe duplicates |
| Definition | `POST /domain-rules/definitions` | rule key/version/source hash uniqueness | retry only when conflict resolves to same canonical definition | negative test for duplicate conflicting definition |
| Simulation | `POST /domain-rules/simulations` | read/evaluate operation; no persisted approval side effect | retry allowed when request is unchanged | HTTP test proving no status/materialization mutation |
| Approval/status | `PATCH /definitions/{id}/status` | current status plus actor/steward body semantics; ETag/source hash if promoted later | retry only when terminal state already matches requested transition | negative test for invalid transition |
| Publication | `POST /domain-rules/publications` | approved/active definition and eligible materialization source hash | retry only when publication result is the same canonical revision | negative test for publish before approval/activation |
| Materialization | `POST /domain-rules/materializations` | target artifact key plus definition/source hash uniqueness | retry only when existing materialization matches the same source | negative test proving materialization is derived, not source of truth |

Timeline and materialization projections must use explicit visibility tiers:

| Tier | Audience | Allowed content | Forbidden content |
| --- | --- | --- | --- |
| Safe timeline | common cockpit and LLM-safe inspection | event type, status, timestamps, safe actor/steward labels, definition/materialization ids when already safe | prompt, assistant text, rule condition, parameters, materialized payload, secrets |
| Authenticated detail | governed cockpit after authorization | canonical ids, target surface, status, projection type, safe diagnostics, audit references | raw prompt, hidden reasoning, secrets, unrestricted payload dumps |
| Privileged debug | operator-only troubleshooting | narrowly scoped redacted diagnostics with explicit audit | unredacted secrets, chain-of-thought, unrestricted customer data |

Publication and materialization invariants:

- Publication may derive/apply materializations only from a definition that is
  eligible by governed status, such as approved or active according to the
  current contract.
- Materialization remains a derived projection of a canonical definition and
  must never become the primary rule source.
- Timeline is observability, not authorization, not storage of rule payload, and
  not a replacement for canonical definition/materialization records.

## Implementation Phases

### Phase 0 - Documentation and Guardrails

Goal: make the implementation path unambiguous before code changes.

Tasks:

- Mark this document as the active implementation planning source.
- Keep older docs as historical context.
- Add a short route classifier table to streaming/contract docs before changing
  code.
- Confirm browser E2E commands distinguish the local-first cockpit/timeline
  lane from the full Page Builder prompt-to-stream gate.

Acceptance:

- No doc says shared business decisions should be authored as Page Builder-local
  patches or tools.
- Docs distinguish component/page authoring from governed shared-decision
  authoring.

Validation:

- `git diff --check`
- targeted text scan for obsolete guidance.

### Phase 1 - Extract Turn Engine And Routing Seam Without Behavior Change

Goal: separate orchestration from stream transport and introduce the seam where
route classification will run before preview or mutation.

Tasks:

- Add `AgenticAuthoringTurnEngine`.
- Add an internal `AgenticAuthoringTurnEventSink` or equivalent callback
  interface owned by `AgenticAuthoringTurnStreamService`.
- Add `AgenticAuthoringTurnState` and `AgenticAuthoringTurnOutcome` only if
  they carry real state needed by the engine.
- Wire the existing `AgenticAuthoringCurrentPageAnalyzer` into the engine seam
  early enough for route classification to prefer structural page facts over
  `currentPageSummary`, even if the complete `inspectCurrentPage` tool remains
  Phase 4.
- Move the current linear flow into the engine:
  `resolve intent -> emit progress -> preview -> result/error`.
- Add route fields to internal turn state, even if the first PR preserves the
  current behavior.
- Keep public endpoints, DTOs and SSE event envelope compatible.
- Keep `AgenticAuthoringTurnStreamService` responsible for:
  start, connect, replay, probe, cancel, timeout, ownership and event append.

Acceptance:

- Stream service no longer directly calls `intentResolverService.resolve`.
- Stream service no longer directly calls `previewService.preview`.
- Engine does not depend directly on `AiTurnEventService` or `SseEmitter`.
- Engine progress/result/error emissions are persisted through the stream
  service sink, redacted through `AiSensitiveDataRedactor`, and replayable via
  the existing stream endpoints.
- Timeout/cancel/replay ownership remains in the stream service.
- Existing stream tests still pass.
- No new public contract is introduced.

Suggested focal backend tests:

- `AgenticAuthoringTurnStreamServiceTest`
- new `AgenticAuthoringTurnEngineTest`
- timeout/cancel race coverage after extraction.
- any existing intent/preview tests touched by the extraction.

### Phase 2 - Route Classifier And Governed Decision Boundary

Goal: make the Page Builder vs `domain-rules` boundary explicit before adding
general tool execution.

Tasks:

- Add an engine-owned route classifier with route classes:
  - `component_authoring`
  - `shared_rule_authoring`
  - `mixed`
  - `needs_clarification`
- For `shared_rule_authoring`, return handoff compatible with the current
  contract:
  - `gate.status=route_required`
  - `failureCodes` includes `shared-rule-authoring-required`
  - existing contracted hints/quick replies carry enough context for the
    cockpit to continue.
- Preserve explicit resource paths such as `/api/helpdesk/chamados`.
- Prohibit Page Builder preview/apply/page mutation for governed decision
  routes.
- Allow only safe grounding tools after route classification, not mutating
  Page Builder tools.

Acceptance:

- Business-rule prompts route to `shared_rule_authoring`/`domain-rules`.
- Component/layout prompts remain in Page Builder authoring.
- Mixed prompts produce a governed decision handoff and only reference
  canonical materialization descriptors/results for projections.
- No Page Builder-local business-rule tool or state is introduced.
- Fallback non-stream flow cannot silently bypass `domain-rules` for governed
  prompts.
- First cut does not auto-call `DomainRuleService` intake/simulation from the
  engine. Any auto-intake is a separate `contrato-publico` phase with
  idempotency, headers, ownership and HTTP contract tests.
- The first cut must not add new SSE/HTTP fields, link relations or endpoint
  descriptors just to expose the handoff. If new fields are needed, pause and
  reclassify the PR as `contrato-publico`.

Suggested focal tests:

- `AgenticAuthoringIntentResolverServiceTest`
- `AgenticAuthoringDomainCatalogHintsTest`
- quickstart `DomainAuthoringContextHintsContractTest`
- stream test proving `route_required` returns without preview.
- Angular shared-rule handoff specs if UI payload shape changes.

### Phase 3 - Executable Tool Model

Goal: create a real tool execution model without making tools public too early.

Tasks:

- Add internal types:
  - `AgenticAuthoringToolDefinition`
  - `AgenticAuthoringToolCall`
  - `AgenticAuthoringToolResult`
  - `AgenticAuthoringToolExecutor`
  - `AgenticAuthoringToolRegistry`
- Add bounded execution policy:
  - max calls per turn;
  - timeout/error classification;
  - safe event projection.
- Require every tool definition to declare:
  - `allowedRoutes`
  - `ownerSurface`
  - `sideEffectClass`
  - `governanceLevel`
  - audit/redaction policy.
- Promote the existing `AgenticAuthoringResourceDiscoveryService.search` into
  the first internal tool named `searchApiResources`.
- Keep `AgenticAuthoringTool*` types internal and out of OpenAPI until a
  separate contract-public promotion is explicitly approved.

Acceptance:

- `searchApiResources` is executable through the registry.
- The engine invokes the registry, not the controller.
- Result preserves candidates, score, reason and existing `evidence/source`
  without inventing public DTO fields.
- Failures have structured error metadata.
- Stream emits safe progress when resource discovery runs.
- Safe event projection is validated through the real event persistence/redactor
  path.
- Tool progress uses an internal safe projection DTO with an allowlist rather
  than arbitrary diagnostics maps.
- Tool execution cannot run outside its declared route scope.

Suggested focal backend tests:

- `AgenticAuthoringResourceDiscoveryServiceTest`
- new `AgenticAuthoringToolRegistryTest`
- new engine tests proving tool call/result lifecycle.
- `AiApiContractOpenApiTest` or equivalent guard proving internal tool types do
  not leak into OpenAPI.
- stream/redaction test using `AiTurnEventService`.

### Phase 4 - Structural Current Page Inspection

Goal: make `currentPage` primary and `currentPageSummary` auxiliary.

Tasks:

- Add an internal `inspectCurrentPage` tool or engine service.
- Return at least:
  - `artifactKind`
  - `componentType`
  - `boundResource`
  - `editableRegions`
  - `fields`
  - `widgets`
  - `serverBindings`
  - `transientBindings`
- Move rules currently dependent on summary to structural inspection when
  possible.
- Keep `currentPageSummary` only as compatibility/compact text support.
- Treat `currentPage` as facts about a projection, not as policy source. If a
  page contains local validation/rule-like state and the user asks to reuse or
  govern it, propose canonicalization through `domain-rules`.

Acceptance:

- Resolver and planner can decide `modify` from structured `currentPage`.
- Field add/remove/relabel behavior does not regress.
- Summary ambiguity does not override structural page facts.
- Structural inspection does not infer canonical business policy from UI state.

Suggested focal backend tests:

- `AgenticAuthoringIntentResolverServiceTest`
- `AgenticAuthoringPlanServiceTest`
- `AgenticAuthoringMinimalFormPlanValidatorTest`
- new tests for `inspectCurrentPage`.

### Phase 5 - Retrieval Policy Separation

Goal: make candidate provenance clear and auditable.

Status: implemented in `main` by PRs #144-#148.

Tasks:

- Split retrieval into explicit components:
  - `SemanticCandidateRetriever`
  - `LexicalFallbackCandidateRetriever`
  - `CandidateRankingPolicy`
  - optional `DeterministicOverridePolicy`
- Preserve existing behavior while adding provenance labels.
- Ensure semantic retrieval is preferred when available.

Acceptance:

- Every selected candidate has explicit source/provenance.
- Lexical fallback is still available but cannot masquerade as semantic
  grounding.
- Stream diagnostics explain whether discovery came from semantic retrieval,
  lexical fallback, deterministic override or context hint.

Implemented evidence:

- Retrieval is split into `SemanticCandidateRetriever`,
  `LexicalFallbackCandidateRetriever`, `BroadArtifactCandidateRetriever` and
  `CandidateRankingPolicy`.
- `AgenticAuthoringCandidateProvenancePolicy` centralizes safe source
  classification.
- Lexical candidates carry explicit `lexical-fallback` evidence; legacy
  `api-metadata` remains mapped as lexical fallback for compatibility.
- Stream diagnostics use the same provenance policy and classify
  selected-candidate handoffs before falling back to candidate lists.
- `AgenticAuthoringResourceDiscoveryServiceTest` guards that semantic
  retrieval is preferred and lexical fallback is not consulted when semantic
  candidates exist.

### Phase 6 - Backend-Owned Repair Loop

Goal: retry only recoverable failures in a bounded and observable way.

Status: implemented in `main` by PRs #150-#153.

Tasks:

- Classify preview/compile/tool errors:
  - `retryable`
  - `non_retryable`
  - `route_required`
  - `user_clarification_required`
- Add at most one repair attempt per phase initially.
- Emit `repair.attempt` or equivalent safe progress event.
- Never repair by hiding a route-to-domain-rules requirement.

Acceptance:

- Recoverable preview failures get one backend-owned retry.
- Non-recoverable failures remain terminal and clear.
- User sees progress without raw internal prompt or payload leakage.

Implemented evidence:

- `AgenticAuthoringRepairClassificationPolicy` classifies preview outcomes as
  `retryable`, `non_retryable`, `route_required` or
  `user_clarification_required`.
- `AgenticAuthoringTurnEngine` emits safe `repair.attempt` progress and retries
  preview generation once when classification is `retryable`.
- Repair context passed to the planner is allowlisted metadata only:
  classification, attempt number and failure/warning counts.
- `route_required` and `user_clarification_required` remain excluded from the
  retry path.
- `AgenticAuthoringTurnEngineTest` guards successful repair, persistent repair
  failure after one attempt and user-clarification paths without raw payload
  leakage.

### Phase 7 - Governed Project Knowledge

Goal: introduce project memory only as governed platform knowledge.

Detailed plan:

- [05-governed-project-knowledge-plan.md](./05-governed-project-knowledge-plan.md)

Tasks:

- Define canonical model with at least:
  - `scope`
  - `kind`
  - `source`
  - `status`
  - `payload`
  - `visibility`
- Add retrieval only after storage/governance is explicit.
- Do not use browser localStorage/chat history as canonical memory.
- Reuse the Domain Knowledge Layer as the primary governed store unless an
  explicit implementation gap proves a small attached extension is required.
- Treat vector/RAG retrieval as a derived index, never as source of truth.

Acceptance:

- Project knowledge is scoped, governed and auditable.
- The engine retrieves only relevant knowledge.
- UI explains influence without exposing sensitive payloads by default.
- Normal authoring influence uses only approved, scoped and AI-visible safe
  projections.

## Browser E2E Gates

Use local-first validation before GitHub Actions.

Canonical local lane for governed shared-rule cockpit/timeline:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform

AI_PROVIDER=openai \
AI_ENV_FILE=praxis-config-starter/.env.openai.local.sh \
scripts/workspace/run-local-readiness-lane.sh shared-rule-timeline-cockpit
```

This lane proves the governed cockpit/timeline continuation surface. It does
not by itself prove the full prompt-to-stream route from Page Builder assistant
input.

Explicit macOS/Linux command for the same cockpit/timeline lane:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-ui-angular

node scripts/run-page-builder-agentic-authoring-e2e.js \
  --provider openai \
  --env-file ../praxis-config-starter/.env.openai.local.sh \
  --grep "Shared-rule handoff" \
  --spec projects/praxis-page-builder/test-dev/e2e/page-builder-agentic-validation.playwright.spec.ts \
  --ui-path /page-builder-ia \
  --ready-component-type praxis-dynamic-page \
  --ready-component-id page-builder-ia \
  --timeout-ms 900000
```

Full Page Builder stream gate:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter

powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File ./tools/Invoke-PbAgenticFullE2E.ps1 \
  -Provider openai \
  -QuickstartRoot ../praxis-api-quickstart \
  -UiRoot ../praxis-ui-angular \
  -StreamProcessingTimeoutSeconds 180
```

Explicit macOS/Linux command for the full Page Builder stream gate:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-ui-angular

node scripts/run-page-builder-agentic-authoring-e2e.js \
  --provider openai \
  --env-file ../praxis-config-starter/.env.openai.local.sh \
  --spec projects/praxis-page-builder/test-dev/e2e/page-builder-agentic-validation.playwright.spec.ts \
  --ui-path /page-builder-ia \
  --ready-component-type praxis-dynamic-page \
  --ready-component-id page-builder-ia \
  --timeout-ms 900000
```

Manual browser inspection target:

- backend: `http://localhost:8088`
- UI: `http://localhost:4003`
- route: `http://localhost:4003/page-builder-ia`

Use GitHub Actions only as a phase-closing gate or release/publication gate.

Before the full browser gate, prefer focal local checks for the changed layer:

- backend extraction/tool changes: focal Maven tests in
  `org.praxisplatform.config.ai.authoring`;
- contract changes: OpenAPI/contract guard tests;
- Angular stream/cockpit changes: focused Page Builder/`@praxisui/ai` specs;
- governed decision changes: quickstart HTTP smoke against
  `/api/praxis/config/domain-rules/**`.

## Required E2E Scenarios

### Component/Page Authoring

- Prompt submitted in the Page Builder assistant calls
  `/api/praxis/config/ai/authoring/turn/stream/start`.
- Stream opens and reaches terminal `result` or `error`.
- Existing page edit uses `currentPage` and does not create a new page by
  mistake.
- Resource discovery happens through backend catalog when needed.
- Browser/network assertions prove Angular does not execute a local
  `searchApiResources` tool, does not semantically choose the resource on its
  own, and only renders candidates/progress returned by backend SSE/HTTP.
- Preview applies or enters review from backend result.
- Probe succeeds before EventSource connection, or the failure is visible and
  classified.
- Cancel reaches the canonical
  `/api/praxis/config/ai/authoring/turn/stream/{streamId}/cancel` endpoint when
  backend cancel is in scope. If cancel remains UI-only for a phase, record the
  debt and removal plan explicitly.

### Governed Shared Decisions

- Prompt about a reusable business rule routes from real assistant input to
  shared-rule handoff through the stream path.
- The stream result includes `gate.status=route_required` or equivalent safe
  route-required diagnostic and does not call Page Builder preview/apply for the
  business rule.
- Handoff points to the canonical `domain-rules` route using only existing
  contracted fields for the current phase.
- In the first routing PR, handoff must use existing contracted
  `gate.status`, `failureCodes`, hints and quick replies. New endpoint
  descriptors, link relations or SSE fields require a separate
  `contrato-publico` plan.
- Cockpit can continue through create definition, simulate, approve/activate,
  publish, materialize, timeline and enforcement validation when available.
- Cockpit actions have visible busy states, block double submit, show backend
  status/result, and never fabricate a rule or materialization after an error.
- `route_required` is rendered as a governed continuation, not as successful
  Page Builder preview and not as a generic failure.
- Timeline unavailable/error states remain diagnostics; they do not become a
  substitute source of truth.
- Materialization appears only with canonical definition/materialization
  references already available from `domain-rules`.
- Timeline is presented as safe observability projection, not source of truth.
- No prompt, assistant text, rule condition, parameters or materialized payload
  leak through common timeline/materialization views.
- HTTP contract smoke validates headers, tenant/environment, safe timeline
  redaction and canonical materialization references when the handoff or
  domain-rules contract is touched.

### Ambiguity And Safety

- Ambiguous resource prompt asks for clarification or presents candidates.
- Dangerous/out-of-scope prompt is blocked or routed to governance.
- Repeated clicks on critical actions do not duplicate unsafe operations.
- Reload during an in-progress flow is recoverable or clearly explained.
- Network assertions prove governed prompts do not call Page Builder
  `page-preview` or `page-apply` before the canonical domain-rules route is
  handled.

## Implementation Order

Completed first PR sequence:

1. Docs sync: mark this plan active and update old docs to point here.
2. Backend extraction: `AgenticAuthoringTurnEngine` plus event sink and routing
   seam, with no behavior change.
3. Route classifier: enforce Page Builder vs `domain-rules` boundary and
   fail-closed fallback for governed prompts.
4. Tool model: route-scoped internal registry plus `searchApiResources`
   adapter.
5. Structural page inspection: reduce summary dependency without inferring
   business policy from UI state.
6. Browser proof: local-first Page Builder stream E2E plus cockpit/timeline E2E.
7. Retrieval policy separation: make candidate provenance explicit and safe in
   diagnostics.
8. Backend-owned repair loop: classify repairability and retry recoverable
   preview failures once.

Recommended next PR sequence:

1. Implement the governed project knowledge read model/service described in
   [05-governed-project-knowledge-plan.md](./05-governed-project-knowledge-plan.md).
2. Add authoring-engine retrieval only after scope, status and visibility tests
   are explicit.

## Stop Conditions

Pause implementation and re-plan if any of these happen:

- a proposed change would make Page Builder the source of business-rule truth;
- a tool needs public contract promotion before its runtime behavior is stable;
- the frontend must choose semantic resources because backend lacks a canonical
  path;
- a fallback path silently bypasses `domain-rules`;
- browser E2E can pass only by mock or by disabling governance;
- the local full/cockpit E2E cannot run from documented commands because of
  missing port/origin/env/service setup, and the only way forward appears to be
  rerunning GitHub Actions. Fix the local lane first.
