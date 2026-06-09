# Runtime Related Surface Controlled Multi-Tool Execution

Status: implemented and validated
Date: 2026-06-05
Classification: `arquitetural` and `contrato-publico`.

## Context

The runtime related-surface lane now has four proven layers:

- runtime components publish sanitized observations with
  `runtimeComponentObservationTrustBoundary=untrusted_frontend_observation`;
- the backend grounds those observations into a consultable runtime context and
  reconciles related surfaces through backend-owned claims;
- `runtime-tool-policy:multi-tool-dry-run-beta` can plan more than one related
  surface while remaining read-free;
- `runtime-tool-policy:multi-tool-readonly-beta` can execute up to two
  governed read-only related surface reads in a single turn.

The Page Builder smoke proved the dry-run contract:

- `candidateSteps.length=2` for `missionTeam` and `missionTimeline`;
- `steps=[]`, `runtimeRelatedSurfaceReads=[]`;
- `budget.maxToolCalls=0`, `budget.usedToolCalls=0`;
- `executionDiagnostics.dryRun=true`;
- no backend related-surface HTTP calls;
- no copied runtime raw values, rows, sample rows, CPF or mission title.

The latest Page Builder smoke proved the readonly execution contract:

- `runtime_related_surface_list` for `missionTeam + missionTimeline` executes
  exactly two governed read-only steps by `stepRef`;
- `runtimeRelatedSurfaceReads.length=2`;
- `budget.usedToolCalls=2`;
- `planner.executionMode=read_only`;
- `executionDiagnostics.aggregateStatus=success`;
- the beta singular alias `runtimeRelatedSurfaceRead` is absent in multi-read;
- no raw rows, sample rows, mission title, CPF, email or hidden values leak.

Additional runtime smokes proved the guardrails:

- `runtime_related_surface_summary` remained blocked/read-free in this cut,
  before the governed summary contract in
  `21-runtime-related-surface-summary-contract.md`;
- `runtime_related_surface_compare` has a planning-only/read-free baseline and
  a governed terminal evidence contract described by
  `22-runtime-related-surface-compare-contract.md`;
- a failing second surface leaves terminal `runtimeRelatedSurfaceReads[]` empty
  and does not expose partial records from the successful first read.

The follow-up negative gate cut strengthened the multi-read baseline before any
executable compare work:

- backend tests assert that a failed second read emits no terminal partial
  `records`, no singular `runtimeRelatedSurfaceRead`, no
  `runtimeRelatedSurfaceSummary` and no `runtimeRelatedSurfaceCompare`;
- backend tests assert that candidates with missing essential claims do not
  become executable steps and do not call HTTP;
- backend tests assert that frontend policy hints cannot activate dry-run or
  readonly multi-tool execution;
- the real smoke gate fails if planning-only compare leaks
  `runtimeRelatedSurfaceCompare`;
- the real smoke gate audits duplicate SSE phases by unique `stepRef`, budget
  and terminal read arrays, not by raw event counts.

## Decision

Use the backend-owned policy:

```text
runtime-tool-policy:multi-tool-readonly-beta
```

This policy is not enabled by frontend hints and must not be inferred from user
text. It may only be selected by the backend configuration surface that already
owns `praxis.ai.authoring.runtime-tool.policy-ref`.

The policy authorizes at most two runtime related-surface reads in one turn, and
only for semantically resolved consultative intents whose execution mode is
explicitly read-only. It does not authorize actions, mutation, arbitrary tool
loops, dynamic HTTP endpoints or reads from frontend-provided data.

## Policy Contract

When enabled, `runtimeToolPlan` must declare:

- `planner.backendPolicyRef=runtime-tool-policy:multi-tool-readonly-beta`;
- `planner.executionMode=read_only`;
- `planner.multiToolPlanningEnabled=true`;
- `planner.multiToolExecutionEnabled=true`;
- `planner.maxToolCallsMayExceedOne=true`;
- `multiToolAuthorization.source=backend_policy`;
- `multiToolAuthorization.allowed=true`;
- `budget.maxToolCalls<=2`;
- `budget.globalMaxToolCalls<=2`;
- `budget.runtimeRelatedSurfaceToolBudget.maxToolCalls<=2`;
- `budget.runtimeRelatedSurfaceToolBudget.maxReads<=2`;
- `budget.maxRelatedSurfaceReads<=2`;
- `budget.maxTotalRecordsReturned` bounded by policy, initially `16`;
- `aggregationPolicy.mode=bounded_multi_read`;
- `aggregationPolicy.maxInputReads<=2`;
- `aggregationPolicy.conflictResolution=fail_closed`.

Any plan with `maxToolCalls > 1` that lacks all of those claims must be clamped
or blocked with `runtime-multi-tool-policy-not-enabled`.

`budget.maxToolCalls` remains the runtime related-surface budget for this
contract, not permission to spend arbitrary engine tools. It must be mirrored in
`runtimeRelatedSurfaceToolBudget` until the broader engine budget contract is
formalized. Invariants:

- `sum(stepBudget.maxToolCalls) <= budget.maxToolCalls`;
- `budget.usedToolCalls == executedStepCount`;
- failures before HTTP do not consume a tool call;
- related-surface reads must not borrow budget from unrelated authoring tools.

## Step Contract

Each executable step must include:

- stable `stepRef`, for example `runtime-tool-step:missionTeam`;
- `kind=runtime_related_surface_read`;
- `surfaceRef`;
- `candidateRef`;
- `toolName=resolveRuntimeRelatedSurface`;
- `toolPurpose=retrieveEvidence`;
- `dependsOn`, initially empty unless an aggregation step depends on prior
  reads;
- `stepBudget.maxToolCalls=1`;
- `stepBudget.maxRecordsReturned<=8`;
- `stepBudget.consumesGlobalToolBudget=true`;
- `projectionPolicyRef`;
- `redactionPolicyRef`;
- `acceptedClaimRefs`.

The engine must execute steps in ranked order. A failed step must stop the
multi-read plan unless the policy later defines an explicit partial-result mode.
The initial policy is fail-closed: no mixed success aggregation and no partial
records in the terminal evidence bundle.

## Claim Acceptance Gate

Before each step executes, the backend must own and accept these claims:

- relation exists in grounded runtime context;
- source widget and target widget are active when widget identity is required;
- surface/action is active;
- target resource path is backend-governed;
- operation is read-only and declared;
- `queryMapping.sourceField` matches the selected source id field;
- `queryMapping.targetFilterField` matches `queryMapping.targetPath=filters.<field>`;
- selected source record is fresh and singular;
- target projection fields are accepted by a backend-reconciled allowlist;
- redaction policy applies before evidence is exposed.

Presence in the frontend observation is evidence, not authority. Execution uses
only claims accepted by backend reconciliation.

The projection allowlist is backend-reconciled per read. The runtime observation
may contribute target schema refs, table configuration and surface identity as
evidence, but the final projection must be accepted against backend-governed
metadata/schema or a backend-owned surface contract. Without an accepted
allowlist, the step fails before records are exposed.

## Aggregation Contract

`runtimeRelatedSurfaceReads[]` becomes the canonical multi-read array. Under
`runtime-tool-policy:multi-tool-readonly-beta`, the beta alias
`runtimeRelatedSurfaceRead` must not be emitted when more than one read is
planned or executed. It may be emitted only when exactly one read succeeds and
the aggregate status is `success`.

Each read must include:

- `stepRef`;
- `surfaceRef`;
- `targetResourcePath`;
- `queryMapping`;
- sanitized `records`;
- `recordCount`;
- `recordLimit`;
- `truncated`;
- `projectionFields`;
- `omittedFields`;
- `redactionApplied=true`;
- `rawRuntimeValuesCopied=false`;
- `warnings`;
- `diagnostics`.

The aggregate answer may summarize across reads only from sanitized read
payloads. It must not copy raw runtime observations, frontend sample rows or
hidden values into the response or evidence bundle.

If any executable step fails, `runtimeRelatedSurfaceReads[]` in the terminal
evidence bundle must be empty. Successful intermediate reads from the same
failed plan may appear only as sanitized diagnostics without `records`, so
consumers cannot accidentally treat partial data as a successful answer.

## Intent Eligibility

Initial eligible intents:

- `runtime_related_surface_list` when the semantic decision explicitly asks for
  multiple related surfaces.

Initial non-eligible intents:

- `runtime_related_surface_availability`: always read-free;
- `runtime_related_surface_summary`: governed by
  `21-runtime-related-surface-summary-contract.md`;
- `runtime_related_surface_compare`: planning-only unless the governed
  comparison-dimension contract is satisfied under
  `22-runtime-related-surface-compare-contract.md`;
- `runtime_surface_disambiguation`: read-free until the user selects a target.
  When more than one candidate is accepted, the terminal evidence may include
  `runtimeRelatedSurfaceDisambiguation.options[]` with surface refs, candidate
  refs, accepted claim refs and projection/redaction policy refs, but no records
  and no backend reads. The terminal result may also project these options into
  `quickReplies[]`; each reply carries a backend-authored
  `semanticDecision.constraints.runtimeRelatedSurfaceDisambiguationSelection`
  plus a matching `value`, so the client can round-trip the chosen option as
  `activeSemanticDecision` without inventing refs from text.

Controlled single-read intent:

- `runtime_related_surface_detail`: can execute exactly one governed read under
  `runtime-tool-policy:multi-tool-readonly-beta` when the runtime resolution has
  exactly one accepted related-surface candidate, or when the semantic intent
  returns `DETAIL_TARGET_SURFACE_REF` and the backend reconciles that surfaceRef
  to an accepted candidate. Follow-up selection may also provide
  `activeSemanticDecision.constraints.runtimeRelatedSurfaceDisambiguationSelection`
  with the previously emitted `optionRef`, `candidateRef` and `surfaceRef`; the
  backend must reconcile those refs against an accepted candidate before planning
  the single detail read. If the target is absent, ambiguous, divergent or not
  reconciled, it stays read-free and emits a blocked diagnostic.

The semantic intent decision must precede ranking and execution. Keyword routing
or label matching may rank candidates only after the semantic intent has scoped
the operation.

## SSE Diagnostics

The stream must make the policy decision visible:

- `runtime.tool-plan.intent`: intent kind, semantic decision ref and read mode;
- `runtime.tool-plan.candidates`: accepted and rejected candidates;
- `runtime.tool-plan.created`: full sanitized plan, budget and steps;
- `runtime.tool-plan.step`: one event per executable step with status;
- `runtime.tool-plan.aggregate`: read count, used tool calls, blocked count,
  policy ref, aggregation mode, redaction status and failure code if blocked.

The events must not include records, raw rows, sample rows, data sources, CPF,
email, secrets or full runtime values. Sanitized records remain only in the
terminal evidence bundle.

## Failure Policy

Fail closed when:

- any required accepted claim is missing;
- a candidate relation is stale;
- selected source record is absent, stale or plural;
- `targetWidget` diverges when required for resource resolution;
- target resource path is absent or not backend governed;
- target filter field is not declared by `queryMapping.targetPath`;
- read-only operation cannot be proven;
- redaction cannot be applied;
- total planned calls exceed the backend policy budget;
- a step succeeds and another step fails in the same aggregate plan.

Failure must produce explicit diagnostics, no partial invented answer and no
fallback to domain catalog prose that implies unrelated data is authoritative.

## Implementation Status

Implemented:

1. Backend-owned policy allowlist, disabled by default.
2. Executable `steps[]` only under
   `runtime-tool-policy:multi-tool-readonly-beta`.
3. At most two read-only `runtime_related_surface_list` steps executed
   sequentially, sharing the related-surface budget.
4. Aggregate evidence as `runtimeRelatedSurfaceReads[]`.
5. Consultative answers rendered from sanitized aggregate evidence.
6. `availability` and `dry-run` remain read-free.
7. `summary` is governed by
   `21-runtime-related-surface-summary-contract.md`; `compare` is governed by
   `22-runtime-related-surface-compare-contract.md`.
8. Real Page Builder smoke with `missionTeam + missionTimeline`.

Remaining future work:

- broader budget integration with non-related-surface tools.

## Acceptance Gates

Unit gates:

- unknown policy falls back to `single-read-beta`;
- frontend hint cannot enable `multi-tool-readonly-beta`;
- dry-run still produces zero reads and zero tool calls;
- readonly policy executes at most two explicit multi-surface list reads;
- summary follows `21-runtime-related-surface-summary-contract.md`;
- compare follows `22-runtime-related-surface-compare-contract.md`;
- third candidate is blocked with budget diagnostics;
- one failed step blocks aggregation;
- one failed step removes partial records from terminal
  `runtimeRelatedSurfaceReads[]`;
- one failed step also leaves `runtimeRelatedSurfaceRead`,
  `runtimeRelatedSurfaceSummary` and `runtimeRelatedSurfaceCompare` absent;
- redaction is applied per read;
- stale or forged relations fail before HTTP.

Runtime smoke gate:

- backend starts with
  `praxis.ai.authoring.runtime-tool.policy-ref=runtime-tool-policy:multi-tool-readonly-beta`;
- Page Builder sends real runtime observations for `missionTeam` and
  `missionTimeline`;
- stream emits tool plan intent, candidates, created, one step per read and
  aggregate;
- `runtimeRelatedSurfaceReads.length<=2`;
- `budget.usedToolCalls<=2`;
- no singular `runtimeRelatedSurfaceRead` alias is emitted when two reads are
  present;
- responses are derived from backend-governed sanitized reads;
- no raw rows, sample rows, mission title, CPF, email, token or hidden values
  appear in request, stream diagnostics or evidence.

## Non-Goals

- No mutation.
- No action execution.
- No arbitrary tool loops.
- No frontend-selected policy.
- No keyword-routed intent.
- No multi-page or cross-session aggregation.
- No partial aggregation in the first readonly beta.
