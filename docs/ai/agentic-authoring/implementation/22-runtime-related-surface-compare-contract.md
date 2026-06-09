# Runtime Related Surface Governed Compare Contract

Status: terminal governed compare evidence implemented by backend tests;
real Page Builder + Quickstart + Neon smoke gate updated
Date: 2026-06-06
Classification: `arquitetural` and `contrato-publico`.

## Context

The runtime related-surface lane now has a stable beta ladder:

- `runtime_related_surface_availability` is read-free;
- `runtime_related_surface_list` can execute up to two governed read-only
  surface reads under `runtime-tool-policy:multi-tool-readonly-beta`;
- `runtime_related_surface_summary` can aggregate sanitized read evidence into
  `runtimeRelatedSurfaceSummary`;
- SSE technical duplicates are auditably replay-safe through
  `streamEventDiagnostics`.

`runtime_related_surface_compare` now has a minimal terminal evidence form.
Compare remains riskier than list or summary because it can easily imply
business conclusions, causality, missingness, ordering, severity or
recommendations that are not present in the sanitized evidence.

This document defines the planning-only baseline and the implemented terminal
evidence contract. The planning-only cut is implemented and validated by real
Page Builder + Quickstart smoke: the semantic intent reaches the runtime tool
planner, diagnostics expose accepted candidates and the blocked aggregate, and
the backend executes no related-surface reads. The governed compare cut is
implemented by backend tests and reflected in the real smoke gate: when a
canonical comparison dimension is accepted, the backend executes the already
governed related-surface reads and emits terminal `runtimeRelatedSurfaceCompare`
facts derived only from sanitized read evidence.

## Decision

Allow `runtime_related_surface_compare` terminal evidence only when the backend
can prove the comparison dimension, sides, fields and aggregation rules from
canonical claims. Under this contract,
`runtime-tool-policy:multi-tool-readonly-beta` may authorize governed reads and
a minimal compare aggregate when the comparison dimension is accepted. No
additional compare tool is called.

The first compare cut must not route by keywords, labels or frontend hints. It
must use the semantic intent decision only to enter the compare lane, then use
backend-owned contracts to decide whether a comparison is possible.

The compare aggregate may become executable only when:

- semantic intent is `runtime_related_surface_compare`;
- backend policy authorizes read-only related-surface execution;
- each compared side maps to an accepted related-surface read step;
- compared fields are declared in a backend-reconciled projection allowlist;
- a comparison dimension is canonical and non-sensitive;
- redaction applies before any comparison fact is exposed;
- the terminal evidence bundle can carry the read evidence without copying raw
  runtime values.

If those conditions are not met, the behavior remains planning-only:

- no executable `steps[]`;
- no backend related-surface HTTP reads;
- `runtimeRelatedSurfaceReads[]=[]`;
- `runtimeRelatedSurfaceCompare` absent;
- explicit `blockedSteps[]`/diagnostics explaining the missing comparison
  contract with `runtime-related-surface-compare-not-enabled`.

If those conditions are met, the implemented behavior is:

- up to two governed read-only `steps[]`;
- `runtimeRelatedSurfaceReads[]` populated only after all steps succeed;
- `aggregationPolicy.mode=governed_compare`;
- `aggregationPolicy.comparisonDimension` accepted with
  `fieldRef`, `source`, `provenance=backend_reconciled`,
  `allowedFactKinds`, `requiresBothSurfaces=true` and `redactionRequired=true`;
- `executionDiagnostics.compareEvidenceEmitted=true`;
- `executionDiagnostics.compareExecutionStage=terminal_governed_compare_evidence`;
- `runtimeRelatedSurfaceCompare` present with record-count,
  categorical-distribution, projection/redaction-coverage, record-count-delta
  category-overlap, record-presence-matrix and, for accepted temporal
  dimensions, temporal-coverage facts only;
- no singular `runtimeRelatedSurfaceRead` alias when multiple reads exist.

## Planning-Only Contract

For blocked/planning-only compare cases, `runtimeToolPlan` exposes:

- `planner.intentKind=runtime_related_surface_compare`;
- `planner.executionMode=read_only` only when backend policy is readonly-beta,
  but `executionDiagnostics.planningOnly=true`;
- `planner.multiToolPlanningEnabled=true` when compare produces audited
  `candidateSteps[]`;
- `candidateSteps[]` for accepted/rejected surfaces;
- `blockedSteps[]` with `failureCode=runtime-related-surface-compare-not-enabled`;
- `budget.maxToolCalls=0`;
- `budget.usedToolCalls=0`;
- `runtimeRelatedSurfaceToolBudget.maxReads=0`;
- `aggregationPolicy.mode=compare_planning_only`;
- `executionDiagnostics.backendReadsPerformed=false`;
- `executionDiagnostics.aggregateStatus=blocked`.

The stream may emit `runtime.tool-plan.*` diagnostics, but no `runtime.tool-plan.step`
with executable status. Any duplicate SSE events must carry replay-safe
`streamEventDiagnostics` and must not be interpreted as execution.

## Executable Compare

The current executable compare remains deliberately small:

- `planner.backendPolicyRef=runtime-tool-policy:multi-tool-readonly-beta`;
- `planner.executionMode=read_only`;
- `planner.intentKind=runtime_related_surface_compare`;
- `readMode=compare`;
- `aggregationPolicy.mode=governed_compare`;
- `aggregationPolicy.maxInputReads<=2`;
- `budget.runtimeRelatedSurfaceToolBudget.maxReads<=2`;
- `budget.usedToolCalls == runtimeRelatedSurfaceReads.length`;
- no additional tool call is spent for comparison prose or aggregation;
- `runtimeRelatedSurfaceCompare` is derived exclusively from
  `runtimeRelatedSurfaceReads[]`.

Compare must not call a new broad search tool, consult frontend rows, read
unrelated catalogs, infer missing records from absence, or mutate workflow
state.

Compare executes no new tools beyond the already governed related-surface
read steps. It is allowed only when the semantic classifier proposes
`COMPARISON_DIMENSION_FIELD` and the backend reconciles that field against the
accepted related-surface schema refs, or when the backend can infer exactly one
non-sensitive common dimension from backend-reconciled surface contracts. Prompt
labels, frontend hints, `contextHints.runtimeRelatedSurfaceComparisonDimension`
and raw runtime observation values cannot provide or authorize the dimension.
Surface schema reconciliation can match the target by `targetWidget` or by the
canonical `targetResourcePath`; page composition refs and target resource refs
become equivalent only after backend grounding.

For real smoke stabilization, the starter may be booted with the backend-owned
policy
`praxis.ai.authoring.runtime-related-surface.intent-policy-ref=runtime-related-surface-intent-policy:temporal-compare-smoke`
and `praxis.ai.authoring.runtime-related-surface.temporal-comparison-field-ref`.
This policy replaces only the LLM classifier in the smoke environment. It emits
`runtime_related_surface_compare` only when the configured field is declared in
at least two grounded, non-redacted runtime surfaces, requires
backend-reconciled `fieldType=date|date-time` before any read, and never
downgrades a temporal decision to categorical compare. Unknown policy values
fall back to `runtime-related-surface-intent-policy:llm`, and frontend hints
cannot enable it.

The first accepted semantic-decision dimension projection is:

```json
{
  "comparisonDimension": {
    "fieldRef": "status",
    "source": "semantic_decision",
    "provenance": "backend_reconciled",
    "allowedFactKinds": [
      "surface_record_count",
      "categorical_distribution",
      "projection_redaction_coverage",
      "record_count_delta",
      "category_overlap",
      "record_presence_matrix"
    ],
    "requiresBothSurfaces": true,
    "redactionRequired": true
  }
}
```

A backend-owned unique-dimension inference uses the same accepted projection
shape with `source=backend_contract`:

```json
{
  "comparisonDimension": {
    "fieldRef": "ordem",
    "source": "backend_contract",
    "provenance": "backend_reconciled",
    "allowedFactKinds": [
      "surface_record_count",
      "categorical_distribution",
      "projection_redaction_coverage",
      "record_count_delta",
      "category_overlap",
      "record_presence_matrix"
    ],
    "requiresBothSurfaces": true,
    "redactionRequired": true
  }
}
```

When the reconciled dimension is temporal, the backend may add
`fieldType=date|date-time` and authorize `temporal_coverage` explicitly.
The temporal type must come from backend-reconciled runtime/schema evidence,
currently `snapshot.schemaFieldDescriptors[]`, not from field-name inference:

```json
{
  "comparisonDimension": {
    "fieldRef": "data",
    "fieldType": "date-time",
    "source": "semantic_decision",
    "provenance": "backend_reconciled",
    "allowedFactKinds": [
      "surface_record_count",
      "categorical_distribution",
      "projection_redaction_coverage",
      "record_count_delta",
      "category_overlap",
      "record_presence_matrix",
      "temporal_coverage"
    ],
    "requiresBothSurfaces": true,
    "redactionRequired": true
  }
}
```

The backend sanitizes the accepted dimension into
`aggregationPolicy.comparisonDimension` only after setting
`provenance=backend_reconciled`. Missing, frontend-supplied or non-declared
fields, ambiguous common fields and fields omitted by redaction are recorded under
`runtimeRelatedSurfaceResolution.comparisonDimensionDiagnostics` and block before
any HTTP/read. If any accepted surface declares a temporal type for the requested
dimension, all compared surfaces must declare a reconciled temporal type for that
same field; otherwise the backend records
`runtime-related-surface-compare-dimension-temporal-type-not-reconciled` and
stays read-free.

## Terminal Evidence Model

Compare terminal facts must remain an aggregation mode over governed read
evidence:

- derive all compare facts exclusively from terminal
  `runtimeRelatedSurfaceReads[]` records that are already sanitized;
- require exactly two successful reads for the first two-sided compare mode;
- require an explicit comparable dimension from backend-owned contract or
  semantic decision, not from prompt labels alone;
- allow only the backend-owned fact kinds listed in
  `comparisonDimension.allowedFactKinds`: surface record counts,
  categorical distributions, projection/redaction coverage, count delta,
  category overlap, record presence matrix and, only for accepted temporal
  dimensions, temporal coverage;
- set `aggregationPolicy.mode=governed_compare` only after both reads and the
  comparison dimension are accepted;
- leave `runtimeRelatedSurfaceCompare` absent if any side, dimension,
  projection, redaction or replay/idempotency claim cannot be proven.

## Comparison Scope

The first executable beta should allow only narrow comparisons:

- two accepted related surfaces for the same selected source record; or
- one surface grouped by a declared non-sensitive dimension.

The comparison dimension must be canonical. Acceptable sources include:

- backend metadata/schema field declarations;
- backend-owned surface query context;
- accepted projection fields from `runtimeRelatedSurfaceReads[]`;
- an explicit semantic decision field in a governed compare contract;
- backend-owned unique-dimension inference when exactly one non-sensitive common
  field remains across accepted surfaces.

Unacceptable sources include:

- prompt keywords alone;
- visible labels alone;
- frontend table columns not reconciled by backend metadata;
- raw runtime observation values;
- hidden fields or fields omitted by redaction.

For the minimum executable cut, the comparison dimension should be modeled as an
explicit contract field, for example:

```json
{
  "comparisonDimension": {
    "fieldRef": "status",
    "source": "semantic_decision",
    "provenance": "backend_reconciled",
    "allowedFactKinds": [
      "surface_record_count",
      "categorical_distribution",
      "projection_redaction_coverage",
      "record_count_delta",
      "category_overlap",
      "record_presence_matrix"
    ],
    "requiresBothSurfaces": true,
    "redactionRequired": true
  }
}
```

If the semantic decision asks to compare but does not resolve a canonical
dimension, the backend may infer one only from backend-reconciled contracts and
only when exactly one non-sensitive common field exists. With no common field,
more than one common field, a redacted common field or non-singular source
selection, the answer must remain planning-only or return a blocked aggregate
diagnostic. The backend must not pick a dimension by keyword routing.

## Compare Evidence Contract

When compare succeeds, the terminal evidence bundle includes:

```json
{
  "runtimeRelatedSurfaceCompare": {
    "schemaVersion": "praxis-runtime-related-surface-compare.v1",
    "intentKind": "runtime_related_surface_compare",
    "aggregationMode": "governed_compare",
    "sourceReadRefs": [
      "runtime-tool-step:missionTeam",
      "runtime-tool-step:missionTimeline"
    ],
    "surfaceRefs": ["missionTeam", "missionTimeline"],
    "comparisonDimension": {
      "fieldRef": "ordem",
      "source": "semantic_decision",
      "provenance": "backend_reconciled",
      "allowedFactKinds": [
        "surface_record_count",
        "categorical_distribution",
        "projection_redaction_coverage",
        "record_count_delta",
        "category_overlap",
        "record_presence_matrix"
      ],
      "requiresBothSurfaces": true,
      "redactionRequired": true
    },
    "recordCountsBySurface": {
      "missionTeam": 8,
      "missionTimeline": 3
    },
    "categoricalDistributionBySurface": {
      "missionTeam": {"1": 8},
      "missionTimeline": {"1": 3}
    },
    "facts": [
      {
        "factRef": "runtime-compare-fact:missionTeam:1",
        "kind": "surface_record_count",
        "surfaceRef": "missionTeam",
        "sourceReadRef": "runtime-tool-step:missionTeam",
        "recordCount": 8,
        "redactionApplied": true
      },
      {
        "factRef": "runtime-compare-fact:missionTeam:2",
        "kind": "categorical_distribution",
        "surfaceRef": "missionTeam",
        "sourceReadRef": "runtime-tool-step:missionTeam",
        "fieldRef": "ordem",
        "distribution": {"1": 8},
        "redactionApplied": true
      },
      {
        "factRef": "runtime-compare-fact:missionTeam:3",
        "kind": "projection_redaction_coverage",
        "surfaceRef": "missionTeam",
        "sourceReadRef": "runtime-tool-step:missionTeam",
        "projectionFieldRefs": ["nome", "ordem"],
        "projectionFieldCount": 2,
        "omittedFieldRefs": ["cpf", "email"],
        "omittedFieldCount": 2,
        "redactionApplied": true,
        "truncated": false,
        "rawRuntimeValuesCopied": false
      },
      {
        "factRef": "runtime-compare-fact:record-count-delta:5",
        "kind": "record_count_delta",
        "leftSurfaceRef": "missionTeam",
        "rightSurfaceRef": "missionTimeline",
        "leftRecordCount": 8,
        "rightRecordCount": 3,
        "absoluteDelta": 5,
        "direction": "left_greater",
        "redactionApplied": true
      },
      {
        "factRef": "runtime-compare-fact:category-overlap:6",
        "kind": "category_overlap",
        "fieldRef": "ordem",
        "leftSurfaceRef": "missionTeam",
        "rightSurfaceRef": "missionTimeline",
        "sharedCategoryCount": 1,
        "leftOnlyCategoryCount": 0,
        "rightOnlyCategoryCount": 0,
        "sharedCategories": ["1"],
        "leftOnlyCategories": [],
        "rightOnlyCategories": [],
        "redactionApplied": true
      },
      {
        "factRef": "runtime-compare-fact:record-presence-matrix:7",
        "kind": "record_presence_matrix",
        "fieldRef": "ordem",
        "surfaceRefs": ["missionTeam", "missionTimeline"],
        "categories": ["1"],
        "presenceBySurface": {
          "missionTeam": {"1": true},
          "missionTimeline": {"1": true}
        },
        "absenceIsNotEvidence": true,
        "redactionApplied": true,
        "rawRuntimeValuesCopied": false
      }
    ],
    "omittedFields": ["cpf", "email"],
    "warnings": [],
    "rawRuntimeValuesCopied": false,
    "redactionApplied": true,
    "truncated": false
  }
}
```

The exact shape can evolve, but these semantics are mandatory:

- every fact has provenance to source read refs;
- every compared field is accepted by backend reconciliation;
- absence of a record is not treated as evidence unless the source explicitly
  declares a complete set;
- `record_presence_matrix` is a boolean presence summary over accepted
  categories and must always carry `absenceIsNotEvidence=true`;
- `temporal_coverage` is allowed only for an accepted temporal dimension
  (`fieldType=date|date-time`) and emits min/max plus with-value/missing-value
  counts by surface; it must not infer chronology for non-temporal dimensions;
- raw runtime values, `sampleRows`, `rawRows`, hidden values and source details
  are never copied;
- terminal compare evidence is absent unless aggregate status is `success`.

## Allowed Facts

The first executable beta should allow only low-risk facts:

- counts by surface;
- counts by an accepted categorical field;
- projection/redaction coverage by surface, limited to sanitized field refs,
  counts, truncation and redaction flags;
- explicit overlap by stable sanitized identifier when both sides expose the
  same backend-approved identifier;
- temporal coverage by surface, limited to accepted temporal dimensions and
  normalized ISO date/date-time bounds plus with-value/missing-value counts;
- truncation, omissions and redaction limitations.

It must not infer:

- responsibility;
- causality;
- priority or severity;
- timeline correctness;
- missing required people or steps;
- recommended actions;
- compliance conclusions.

Those require separate semantic decision contracts.

## Failure Policy

Fail closed when:

- semantic intent is not `runtime_related_surface_compare`;
- backend policy does not authorize compare execution;
- fewer than two comparable accepted candidates exist when two-sided compare is
  required;
- the comparison dimension is missing, sensitive, hidden or frontend-only;
- one planned read fails;
- projection allowlist is absent;
- redaction cannot be proven;
- the compared sides use incompatible identifiers or units;
- the answer would require causality, ranking, recommendation or compliance
  semantics;
- any partial result would expose one side without the other.

On failure:

- terminal `runtimeRelatedSurfaceReads[]` must follow the existing fail-closed
  multi-read rule;
- `runtimeRelatedSurfaceCompare` must be absent or diagnostic-only with no
  facts;
- the answer must explain the block without inventing comparison facts;
- `budget.usedToolCalls` must reflect only reads actually attempted before
  failure stopped the plan.

## SSE Diagnostics

Planning-only compare should emit only sanitized diagnostics:

- `runtime.tool-plan.intent`: `runtime_related_surface_compare`;
- `runtime.tool-plan.candidates`: accepted/rejected candidates;
- `runtime.tool-plan.created`: budget, planning-only aggregation policy and
  blocked steps;
- `runtime.tool-plan.aggregate`: blocked aggregate status, failure code and
  zero reads.

Executable compare may emit read `runtime.tool-plan.step` events, but must keep
records and compare facts out of SSE diagnostics. Terminal compare facts appear
only in the sanitized result evidence bundle.

All audit-relevant events must include replay-safe `streamEventDiagnostics`.
Consumers should dedupe by `dedupeKey`/`eventUniquenessKey` and audit execution
through `stepRef`, `budget.usedToolCalls`, `runtimeRelatedSurfaceReads.length`
and `aggregateStatus`.

## Acceptance Gates

Planning-only gates:

- compare intent under default policy produces no reads and no executable
  steps;
- compare intent under readonly-beta remains blocked when no accepted
  `comparisonDimension` exists;
- candidate and blocked diagnostics explain missing compare contract;
- `planner.multiToolPlanningEnabled=true` whenever `candidateSteps[]` are
  emitted for compare planning;
- frontend hints cannot enable compare execution;
- duplicated SSE phases are replay-safe and do not affect budget or reads.

Executable compare gates:

- compare intent under readonly-beta with accepted `comparisonDimension`
  executes exactly the governed read steps and no additional compare tool;
- `aggregationPolicy.mode=governed_compare`;
- `aggregationPolicy.comparisonDimension.source` is either `semantic_decision`
  or `backend_contract`, and both require `provenance=backend_reconciled`;
- `executionDiagnostics.compareEvidenceEmitted=true`;
- `runtimeRelatedSurfaceReads[]` is present only on aggregate success;
- `runtimeRelatedSurfaceCompare` is present only on aggregate success;
- if reads succeed but any read omits the accepted comparison field from
  `projectionFields`, `runtimeRelatedSurfaceCompare` stays absent and the plan
  records `compareEvidenceEmitted=false`,
  `compareExecutionStage=terminal_governed_compare_blocked`,
  `compareEvidenceFailureCode=runtime-related-surface-compare-projection-field-missing`
  and `compareEvidenceMissingProjectionSurfaceRefs[]`;
- facts include only the accepted governed fact kinds:
  `surface_record_count`, `categorical_distribution`,
  `projection_redaction_coverage`, `record_count_delta` and
  `category_overlap`, `record_presence_matrix`, and `temporal_coverage` only
  when the accepted dimension is temporal;
- rejected or non-governed dimensions block before read execution.

Validated real-smoke gates:

- `compare-blocked` with Page Builder + Quickstart + Neon resolves
  `intentKind=runtime_related_surface_compare`;
- `candidateSteps.length=2`, `steps.length=0`, `runtimeRelatedSurfaceReads=[]`
  and `usedToolCalls=0`;
- `runtimeRelatedSurfaceCompare` is absent;
- `failureCode=runtime-related-surface-compare-not-enabled`;
- `planner.multiToolPlanningEnabled=true`;
- `summary-governed` still executes two governed reads with
  `aggregationPolicy.mode=governed_summary`, no singular alias and no raw-value
  leakage.
- `compare-governed` with Page Builder + Quickstart + Neon resolves
  `runtime_related_surface_compare`, reconciles the compare dimension against
  target surfaces by resource path when needed, executes exactly two governed
  reads under readonly-beta and emits `surface_record_count`,
  `categorical_distribution`, `projection_redaction_coverage`,
  `record_count_delta`, `category_overlap` and `record_presence_matrix`;
  it sets `compareEvidenceEmitted=true`, omits the singular alias and emits
  terminal `runtimeRelatedSurfaceCompare` with governed compare facts.
- `compare-redacted-dimension` with Page Builder + Quickstart + Neon keeps
  the compare read-free when the selected dimension is declared but redacted or
  omitted by a target surface. The gate requires
  `runtime-related-surface-compare-dimension-field-redacted`,
  `runtimeRelatedSurfaceReads=[]`, `runtimeRelatedSurfaceCompare` absent,
  `usedToolCalls=0` and `backendReadsPerformed=false`.
- `compare-governed` also verifies replay/idempotency for terminal compare
  evidence: submitting the same payload with the same `clientTurnId` must return
  the same `streamId` and `turnId`, preserve `runtimeRelatedSurfaceCompare`,
  keep `runtimeRelatedSurfaceReads.length=2`, keep `usedToolCalls=2`, omit the
  singular alias and avoid any raw-value leakage. Replay evidence describes the
  original governed execution; it must not grow the budget or append additional
  reads.

Official smoke gate:

- `cd praxis-ui-angular`;
- start Quickstart with
  `praxis.ai.authoring.runtime-tool.policy-ref=runtime-tool-policy:multi-tool-readonly-beta`;
- run `npm run smoke:runtime-tool-plan:readonly:short -- --timeout-ms 240000`;
- the short battery covers `multi-read`, `summary-governed`,
  `compare-blocked`, `compare-redacted-dimension`, `fail-second-surface` and
  `compare-governed`.

Terminal evidence gates:

- `runtimeRelatedSurfaceCompare` present only on aggregate success;
- no singular alias in multi-read;
- failure of any side leaves no terminal partial records and no compare facts;
- sensitive or non-declared comparison dimension blocks before exposing facts;
- smoke Page Builder + Quickstart + Neon proves no raw row, CPF, email, token,
  hidden value or mission title leakage.

## Non-Goals

This contract does not authorize:

- mutation or workflow actions;
- more than two reads;
- arbitrary analytical queries;
- open-ended LLM comparison over raw records;
- compliance decisions;
- recommendation generation;
- ranking/severity inference;
- partial comparison answers from failed aggregate plans.

## Implementation Notes

The planning-only cut does the following:

1. Ensures semantic intent `runtime_related_surface_compare` reaches the
   runtime tool planner before any fallback answer.
2. Emits `candidateSteps[]` and `blockedSteps[]` with
   `runtime-related-surface-compare-not-enabled`.
3. Preserves `steps[]=[]`, `runtimeRelatedSurfaceReads=[]`,
   `runtimeRelatedSurfaceCompare` absent and `usedToolCalls=0`.
4. Marks `executionDiagnostics.planningOnly=true`,
   `backendReadsPerformed=false` and `aggregateStatus=blocked`.
5. Marks `planner.multiToolPlanningEnabled=true` when candidate steps are
   present.
6. Defers executable compare until a comparison dimension is accepted.

The executable compare cut does the following:

1. Parses `COMPARISON_DIMENSION_FIELD` from the runtime-related semantic intent
   decision and ignores frontend hints as authorization.
   In smoke-only runs, the backend-owned temporal compare intent policy may
   provide that field deterministically when the grounded surfaces declare it.
2. Accepts the dimension only when it is backend reconciled: safe `fieldRef`,
   source `semantic_decision` or `backend_contract`,
   `provenance=backend_reconciled`, declared in every accepted surface,
   allowed fact kinds, `requiresBothSurfaces=true` and `redactionRequired=true`.
   For `backend_contract`, the source must be backend-owned unique-dimension
   inference over accepted surface contracts, not a frontend hint.
3. Blocks before reads when no common dimension exists, more than one common
   dimension exists, the dimension is omitted/redacted/sensitive, or the source
   selection is not singular.
4. Executes the same governed related-surface read steps as list/summary under
   readonly-beta.
5. Emits `aggregationPolicy.mode=governed_compare` and the accepted comparison
   dimension.
6. Emits `runtimeRelatedSurfaceCompare` from sanitized
   `runtimeRelatedSurfaceReads[]` and marks `compareEvidenceEmitted=true` only
   when every successful read projects the accepted comparison field.
7. Blocks terminal compare evidence, without hiding the successful governed
   reads, when the accepted comparison field is missing from any read
   `projectionFields`.
8. Blocks before reads when the comparison dimension is missing or rejected.

## Negative Gate Baseline

The multi-read negative gate is now validated enough to start designing the
minimum executable compare cut:

1. partial failure keeps terminal `runtimeRelatedSurfaceReads[]=[]`;
2. replay/idempotency must not execute additional reads or emit new compare
   evidence;
3. duplicate SSE phases remain diagnostic-only and replay-safe;
4. any leaked `runtimeRelatedSurfaceCompare` under planning-only compare fails
   the smoke gate;
5. singular `runtimeRelatedSurfaceRead` remains absent for multi-read plans.

The first negative gate pass is now encoded in backend tests and in the real
readonly smoke script: failed multi-read aggregates keep terminal reads empty,
omit singular/summary/compare evidence, preserve bounded tool-call accounting,
and treat duplicate SSE phases as diagnostics rather than execution.

The real `fail-second-surface` smoke with Page Builder + Quickstart + Neon
confirmed `aggregateStatus=failed`, `runtimeRelatedSurfaceReads=[]`, no
`runtimeRelatedSurfaceSummary`, no `runtimeRelatedSurfaceCompare`, no singular
alias, no partial records in stream diagnostics and `usedToolCalls=2` reflecting
bounded attempts.

The backend projection negative now covers the later terminal-evidence boundary:
when both governed reads succeed but one read does not declare the accepted
comparison field in `projectionFields`, the terminal bundle keeps
`runtimeRelatedSurfaceReads[]`, omits `runtimeRelatedSurfaceCompare`, removes
the compare aggregate-used warning and records
`runtime-related-surface-compare-projection-field-missing` diagnostics on
`runtimeToolPlan`. This guard is intentionally backend-level because the real
Page Builder runtime derives accepted dimensions and read projections from the
same reconciled contract; forcing divergence in the smoke would require a
test-only backend hook or a frontend hint, both of which would weaken the
governance boundary being tested.

## Next Recommended Cut

The broadened fact kinds `projection_redaction_coverage`,
`record_presence_matrix` and `temporal_coverage` are implemented.
They are emitted only when present in `allowedFactKinds`.
`projection_redaction_coverage` carries field refs already present in sanitized
read metadata plus counts and redaction/truncation flags.
`record_presence_matrix` carries only booleans by accepted category and surface,
with `absenceIsNotEvidence=true`. `temporal_coverage` is additionally gated by
an accepted temporal dimension and carries only normalized ISO bounds plus
with-value/missing-value counts. These fact kinds must not copy record values or
infer data quality conclusions.

The temporal end-to-end cut is now the next maturity gate: keep the real smoke
coverage for a temporal backend-reconciled dimension (`fieldType=date|date-time`)
and for the fail-closed case where one surface does not declare the temporal
type. Do not add another fact family until this temporal evidence remains green
in the short real-smoke battery.
