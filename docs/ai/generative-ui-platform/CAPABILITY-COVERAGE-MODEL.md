# Capability Coverage Model

## Purpose

Define what “covered” means for a component without requiring exhaustive enumeration of every
natural-language sentence or Cartesian product of configuration values.

Coverage is not the existence of a registry entry, manifest card, JSON schema, validator ID,
successful compilation or fluent assistant response. It is a chain of independently testable
claims.

## Classification of every public capability

Every public component path must receive one of these authoring classifications:

| Class | Meaning |
| --- | --- |
| `authorable` | User intent may change it through a governed semantic operation |
| `consult-only` | Assistant may read/explain it, but no authoring operation is exposed |
| `runtime-derived` | Runtime computes it; assistant may explain evidence but must not persist it as intent |
| `unsupported` | Publicly visible in source/config but intentionally unavailable to the assistant, with reason |

This classification is separate from the platform adherence classification required before new
contracts:

- `ja-suportado-so-ux`;
- `ja-suportado-mal-nomeado-ou-mal-materializado`;
- `suportado-parcialmente`;
- `lacuna-real-de-contrato`.

## Certification levels

| Level | Name | Evidence required |
| --- | --- | --- |
| C0 | discovered | Source owner and public path are known |
| C1 | declared | Capability, docs and stable identity are projected into the registry |
| C2 | selectable | Semantic intent can select the operation/target from governed evidence |
| C3 | executable | Resolver, schema, validator and effect/handler execute deterministically |
| C4 | state-safe | Preconditions, dependencies, post-state and invariants are proven |
| C5 | observable | Applicable runtime/state observer proves the outcome |
| C6 | explainable | Assistant claims are derived from state/outcome evidence |
| C7 | language-qualified | Human language corpus selects the correct operation and handles ambiguity |
| C8 | platform-certified | Drift, focused tests, integrated proof and review gate all pass |

A component is not “AI-ready” merely because all its operations are C1. A visible authoring
operation must normally reach C8 before becoming a platform readiness claim.

## Required inventory row

The Phase 1 inventory should be machine-readable and capable of representing at least:

```json
{
  "componentId": "praxis-table",
  "capabilityPath": "actions.row.actions[]",
  "authoringClass": "authorable",
  "sourceOwner": "@praxisui/table",
  "editorEvidence": [],
  "dependsOn": ["actions.row.enabled"],
  "operationIds": ["rowAction.add"],
  "targetResolvers": ["action-in-row-config"],
  "effectEvidence": [],
  "validatorIds": ["row-action-id-unique"],
  "handlerEvidence": [],
  "preStateEvidence": [],
  "postStateEvidence": [],
  "runtimeObserverEvidence": [],
  "explanationEvidence": [],
  "testEvidence": [],
  "certificationLevel": "C3",
  "adherence": "ja-suportado-mal-nomeado-ou-mal-materializado",
  "gaps": []
}
```

This is an illustrative report row, not an approved public schema or prescribed file path. Phase 1
must decide the smallest internal representation after auditing existing generated reports.

## Coverage dimensions

### Declaration coverage

- public surface classified;
- component/profile identity stable;
- docs/capability projection present;
- registry lineage and release recorded.

### Execution coverage

- operation schema satisfiable;
- target resolver implemented;
- validator actually dispatched;
- effect kind/handler implemented;
- affected paths coherent;
- compilation round-trip preserves unrelated configuration.

### State coverage

- minimal/default state;
- dependency disabled;
- dependency already enabled;
- target absent/present/ambiguous;
- operation repeated/idempotent;
- conflicting state;
- invalid input;
- remove/reset/undo where promised.

### Outcome coverage

- resulting canonical config/state satisfies the semantic request;
- applicable runtime DOM/state/event proves visibility or interaction;
- negative observer distinguishes hidden, disabled, suppressed and absent;
- explanation claims match the observed state;
- success is withheld when observation is inconclusive.

### Language coverage

- direct request;
- long spoken request;
- hesitation and self-correction;
- pronoun/reference to current artifact;
- multiple changes in one request;
- contradictory requests;
- consult versus edit distinction;
- ambiguous resource/field/component;
- locale and terminology variation.

Language qualification measures semantic selection. It must not compensate for deterministic
execution defects.

## Combinatorial strategy

The platform cannot exhaustively test every value combination. It can bound risk by generating
cases from the real dependency/compatibility graph:

1. one contract test per operation in its minimal valid state;
2. boundary-state tests for every dependency and target policy;
3. property/state-machine tests for global invariants and repeated operations;
4. pairwise or t-wise combinations over actual dependency/conflict clusters;
5. runtime observation cases for every visible/interactive operation family;
6. production/regression cases added to the graph after real failures;
7. separate LLM evals for semantic selection and argument grounding.

Current pairwise layout/renderer/size/shape/alignment cases remain useful presentation coverage,
but they are not a substitute for dependency-based generation.

## Component-level certification

A component family can be certified only when:

- 100% of public capability paths are classified;
- 100% of `authorable` paths map to one or more governed operations;
- every operation reaches the required certification level;
- every declared dependency edge is either closed by the planner/compiler or explicitly blocks;
- no declared validator/handler is assumed executable without backend proof;
- visual/interactive operations have outcome observers;
- zero assistant success message is unsupported by applicable evidence;
- drift generation fails when a new public path lacks classification;
- independent review accepts the evidence bundle.

Not every registry entry must have an authoring manifest. Every entry must have an explicit
classification and rationale.

## Platform-level metrics

Deterministic release blockers:

- unclassified public paths = 0;
- invented resources, operations, fields, component IDs or inputs = 0;
- authorable operations without implemented handler/validator evidence = 0;
- dependency edges without closure/blocking proof = 0;
- false-success outcome claims = 0;
- material selections without provenance = 0;
- cross-tenant/environment evidence leakage = 0.

Measured provider metrics, with thresholds established from a representative baseline:

- semantic operation recall/precision;
- target and argument grounding accuracy;
- clarification precision;
- end-to-end useful-preview rate;
- repair attempts and terminal failure classification;
- latency, tokens and provider cost;
- retrieval recall@k/nDCG for component/domain evidence;
- variance across repeated Terra/Luna runs.

Provider metrics do not relax deterministic zero-tolerance invariants.

## Drift policy

Any change to public component configuration, editor fields, capabilities, actions, surfaces,
authoring operations, handler registries, runtime affordances, docs/examples or observation
contracts must regenerate or re-evaluate the certification view.

The gate must distinguish:

- expected source change with updated certification;
- projection drift;
- executable backend drift;
- runtime/editor mismatch;
- documentation-only change;
- intentionally non-authorable addition.

Generated reports are evidence. Source owners remain authoritative.
