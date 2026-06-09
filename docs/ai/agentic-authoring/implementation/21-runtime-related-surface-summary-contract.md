# Runtime Related Surface Governed Summary Contract

Status: implemented locally
Date: 2026-06-05
Classification: `arquitetural` and `contrato-publico`.

## Context

`runtime-tool-policy:multi-tool-readonly-beta` is now implemented and validated
for the first executable multi-read case:

- semantic intent `runtime_related_surface_list`;
- up to two governed related-surface read-only steps;
- terminal evidence in `runtimeRelatedSurfaceReads[]`;
- fail-closed aggregation with no partial records when a step fails;
- no beta singular alias when more than one read is planned or executed.

`runtime_related_surface_summary` was intentionally blocked/read-free until
this contract existed. That was the right behavior: a summary is not just "run
the same reads and write prose". It needs a canonical aggregation contract so
the platform can prove which sanitized evidence was summarized, which fields
were omitted, which claims authorized the reads and which assertions are
allowed in the final answer.

This document defines the contract implemented locally for the first governed
summary cut.

## Decision

Enable governed summaries as an aggregation mode over already governed
related-surface reads, not as a new frontend hint, keyword path or arbitrary
LLM tool loop.

The backend may only produce a `runtime_related_surface_summary` answer when:

- the semantic intent decision is `runtime_related_surface_summary`;
- the backend-owned runtime tool policy authorizes read-only related-surface
  execution;
- every planned read passes the same claim acceptance, projection, redaction
  and freshness gates used by `runtime_related_surface_list`;
- the summary is derived only from sanitized read payloads in
  `runtimeRelatedSurfaceReads[]`;
- the terminal evidence bundle carries an explicit summary aggregate with
  source read references and redaction diagnostics.

The frontend observation remains evidence, never authority. It can describe
available surfaces, relations and component state, but it cannot request or
authorize summary execution.

## Execution Model

The first summary beta reuses the multi-read budget:

- `planner.backendPolicyRef=runtime-tool-policy:multi-tool-readonly-beta`;
- `planner.executionMode=read_only`;
- `planner.intentKind=runtime_related_surface_summary`;
- `aggregationPolicy.mode=governed_summary`;
- `aggregationPolicy.maxInputReads<=2`;
- `budget.runtimeRelatedSurfaceToolBudget.maxReads<=2`;
- `budget.usedToolCalls == runtimeRelatedSurfaceReads.length`;
- no additional tool call is spent for prose generation or aggregation.

When the semantic decision resolves a single summary target, the beta can narrow
the same summary contract to one related surface:

- the classifier must produce `SUMMARY_TARGET_SURFACE_REF`;
- the backend must reconcile that surfaceRef against accepted candidates from the
  current `runtimeRelatedSurfaceResolution`;
- terminal evidence may expose `runtimeRelatedSurfaceResolution.summaryTarget`
  with `source=semantic_decision` and `provenance=backend_reconciled`;
- `runtimeToolPlan.readMode=summary_targeted`;
- `aggregationPolicy.mode=governed_summary_targeted`;
- exactly one governed read is executed and `runtimeRelatedSurfaceSummary` is
  derived only from that sanitized read.

If the target is absent, summary preserves the existing multi-surface governed
summary. If the target is divergent, forged or not currently accepted, the turn
fails closed before HTTP and no terminal summary evidence is emitted.

Summary is an aggregate over read evidence. It must not add a third tool call,
open a broader search path, consult frontend rows, call an unrelated catalog or
promote observations into authoritative data.

`runtime_related_surface_compare` was blocked/read-free in this summary cut. Its
current terminal evidence contract is specified separately in
`22-runtime-related-surface-compare-contract.md`: compare may execute governed
reads when a backend-reconciled dimension is accepted and may emit minimal
terminal facts derived only from sanitized reads.

## Summary Evidence Contract

When a summary succeeds, the terminal evidence bundle should include:

```json
{
  "runtimeRelatedSurfaceSummary": {
    "schemaVersion": "praxis-runtime-related-surface-summary.v1",
    "intentKind": "runtime_related_surface_summary",
    "aggregationMode": "governed_summary",
    "sourceReadRefs": ["runtime-tool-step:missionTeam"],
    "surfaceRefs": ["missionTeam"],
    "recordCountsBySurface": {
      "missionTeam": 8
    },
    "totalRecordCount": 8,
    "facts": [
      {
        "factRef": "runtime-summary-fact:missionTeam:1",
        "surfaceRef": "missionTeam",
        "sourceReadRef": "runtime-tool-step:missionTeam",
        "kind": "record_group_summary",
        "text": "8 participantes foram encontrados na superficie missionTeam.",
        "projectionFieldRefs": ["nome", "papel"],
        "redactionApplied": true
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

The exact field names can evolve during implementation, but the contract must
preserve these semantics:

- every fact has provenance back to a read step and surface;
- every fact is derived from sanitized projected records;
- omitted fields and redaction status remain visible;
- raw runtime observations, `sampleRows`, `rawRows`, hidden values and data
  source details are never copied into the summary evidence;
- terminal summary evidence is absent when the aggregate status is not
  `success`.

## Allowed Facts

The first beta should allow low-risk, evidence-local facts only:

- record counts by surface;
- grouped names or labels that are already present in sanitized projection;
- status/category counts when the projected field is accepted and non-sensitive;
- explicit omissions and truncation notices;
- surface-level availability context already accepted by grounding.

The summary must not infer business conclusions that are not present in the
sanitized evidence. It must not rank severity, assign responsibility, identify
missing people, compare timelines, infer causality or recommend actions unless
a later semantic decision contract authorizes that specific operation.

## Answer Rendering

The consultative answer may be generated from:

- `runtimeRelatedSurfaceSummary`;
- sanitized `runtimeRelatedSurfaceReads[]` referenced by the summary;
- accepted runtime grounding diagnostics.

It must not use:

- frontend `sampleRows`;
- raw runtime component values;
- target resource data outside the accepted read projections;
- domain catalog fallback prose as if it were evidence;
- old singular `runtimeRelatedSurfaceRead` when the plan is multi-read.

If summary evidence is present, the answer should name the surfaces summarized
and preserve uncertainty. If a surface was blocked, stale, truncated or redacted,
that limitation should appear as an operational caveat rather than being hidden.

## Failure Policy

Fail closed when:

- semantic intent is not `runtime_related_surface_summary`;
- backend policy does not authorize read-only related-surface execution;
- no accepted surface candidate exists;
- any required claim for a planned read is missing;
- any planned read fails;
- sanitized projection fields are absent;
- redaction cannot be proven;
- summary aggregation would require comparison, ranking, causality or action
  recommendation semantics;
- the aggregate would need partial records after a failed multi-read plan.

On failure:

- terminal `runtimeRelatedSurfaceReads[]` must follow the existing fail-closed
  multi-read rule;
- `runtimeRelatedSurfaceSummary` must be absent or carry only sanitized failure
  diagnostics with no facts;
- `budget.usedToolCalls` must reflect only reads actually attempted before the
  failure policy stopped execution;
- the answer must explain the block without inventing data or falling back to an
  unrelated catalog response.

## SSE Diagnostics

The stream should make the summary lifecycle visible without exposing records:

- `runtime.tool-plan.intent`: semantic intent
  `runtime_related_surface_summary`;
- `runtime.tool-plan.candidates`: accepted and rejected related-surface
  candidates;
- `runtime.tool-plan.created`: read plan, budget and aggregation policy;
- `runtime.tool-plan.step`: read-only steps, if authorized;
- `runtime.tool-plan.aggregate`: aggregate status, source read refs, redaction
  status, omitted fields, blocked reason and summary mode.

SSE diagnostics must not include summary facts if those facts would expose
record data. Facts belong in the terminal evidence bundle after redaction.

## Acceptance Gates

Unit gates:

- policy disabled keeps `runtime_related_surface_summary` blocked/read-free;
- readonly policy with accepted surfaces produces a governed summary aggregate
  without spending extra tool calls beyond reads;
- `budget.usedToolCalls == runtimeRelatedSurfaceReads.length`;
- summary over two surfaces omits the singular beta alias;
- summary with a backend-reconciled target executes exactly one read with
  `readMode=summary_targeted` and
  `aggregationPolicy.mode=governed_summary_targeted`;
- forged or divergent summary target blocks before HTTP and emits no terminal
  summary evidence;
- failure of any read leaves no terminal partial records and no summary facts;
- missing projection allowlist blocks summary before exposing records;
- redaction failure blocks summary;
- compare terminal evidence remains absent and is governed separately by
  `22-runtime-related-surface-compare-contract.md`;
- frontend policy hints do not enable summary execution.

Smoke gates:

- Page Builder real, Quickstart real and Neon real can summarize a selected
  mission over accepted related surfaces;
- the stream shows `runtime.context.grounding`, runtime tool planning, read
  steps and aggregate diagnostics in order;
- the terminal evidence contains `runtimeRelatedSurfaceSummary`;
- no `sampleRows`, `rawRows`, CPF, email, token, hidden values or mission title
  leak through request, SSE diagnostics or evidence bundle;
- a failing second surface produces no summary facts and no partial terminal
  records.

## Non-Goals

This contract does not authorize:

- mutation or workflow actions;
- more than two related-surface reads;
- compare semantics;
- cross-turn memory summaries;
- frontend-provided summaries;
- LLM-driven endpoint selection;
- partial successful summaries from failed aggregate plans;
- broad domain catalog fallback as substitute evidence.

## Implementation Notes

Implementation is intentionally small and layered:

1. Keep the existing semantic classifier precedence for `summary`.
2. Extend the planner so summary can produce the same governed read steps as
   list only when the backend policy allows it.
3. Add a backend-owned aggregation phase that consumes sanitized reads and emits
   `runtimeRelatedSurfaceSummary`.
4. Render the answer from the summary aggregate, not directly from raw reads.
5. Preserve blocked/read-free behavior when backend policy is disabled, a read
   fails, redaction/projection is not accepted or the intent is not summary.
