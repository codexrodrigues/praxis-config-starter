# Phase 7 — Generative UI Platform Readiness

Checkpoint: `CP-7`
Status: pending `CP-6` acceptance

## Objective

Convert component pilots and rollout evidence into a durable platform release gate for generative
Praxis interfaces.

Readiness means that domain-grounded intent can create, refine, apply, observe, explain and recover
interfaces within the governed capability graph. It does not mean that the LLM may generate
arbitrary Angular, HTML, JSON or business behavior.

## Readiness dimensions

### 1. Semantic correctness

- domain concepts, resources, fields, actions and inputs are governed and release-scoped;
- component choice follows interaction requirements and certified capabilities;
- ambiguity and contradiction are resolved or clarified before material changes;
- no keyword/regex mechanism makes the primary intent decision.

### 2. Deterministic execution safety

- every authorable operation has an executable validator/handler path;
- dependencies close or block explicitly;
- post-state and invariants hold;
- idempotency, conflict/concurrency and applicable inverse/recovery are tested;
- unauthorized or stale operations fail closed.

### 3. Runtime truth

- visible and interactive claims have applicable runtime/state evidence;
- partial materialization and observer uncertainty do not become success;
- runtime observations are grounded against canonical component/resource identities;
- explanation distinguishes planned, previewed, applied and observed facts.

### 4. Conversational quality

The representative human corpus includes:

- long spoken requests and filler words;
- hesitation, self-correction and abandoned clauses;
- pronouns and references to the current page/column/record;
- multiple compatible changes in one turn;
- internally contradictory changes;
- consult, edit, explain and undo/recover requests;
- domain terminology, colloquialisms and applicable locales;
- unavailable, ambiguous, denied and unsafe requests.

Semantic model metrics remain separate from deterministic execution gates.

### 5. Platform operations

- registry/corpus/provider projections are release-pinned and drift-checked;
- observability identifies model, effort, tools, Skill versions, evidence and failure layer;
- tenant/environment isolation and secret redaction are proven;
- cost/latency budgets and degradation behavior are explicit;
- local-first gates precede the minimum justified remote release/smoke gates.

### 6. Product truth and documentation

- public docs state which components, operations and journeys are certified;
- unsupported/consult-only/runtime-derived capabilities are not advertised as authorable;
- demonstrations use the same release and contracts as the product path;
- examples and playgrounds are derived consumers, not alternate sources of truth.

## Representative release corpus

The final corpus must cross at least:

- the component rollout waves from Phase 6;
- consult, create, refine, apply, observe, explain and recover intents;
- single and multi-component pages;
- list/detail/form/action/workflow/analytics/upload journeys;
- minimal, existing, conflicting, denied, stale and provider-failure states;
- direct and spoken-human language variants;
- Terra/Luna cohorts using identical deterministic infrastructure.

Passing a fixed demonstration script is necessary only when the script represents a declared
journey; it is never sufficient for platform readiness.

## Deterministic release blockers

The following values must be zero:

- invented resources, operations, fields, components, inputs or actions;
- unclassified public component paths;
- authorable operations below their required certification level;
- authorable operations without executable support;
- dependency edges without closure or blocking proof;
- material selections without provenance;
- false-success claims;
- cross-tenant/environment evidence leakage;
- undocumented certification drift.

Any nonzero value blocks promotion regardless of model fluency or average eval score.

## Measured service objectives

Thresholds are established from the Phase 4/5 baseline and recorded by model/effort, including:

- semantic operation/component precision and recall;
- target/argument grounding accuracy;
- clarification precision and unnecessary-clarification rate;
- useful-preview and successful-observed-outcome rates;
- recovery completion rate;
- retrieval recall@k/nDCG;
- latency, tokens, cost and repeated-run variance.

Threshold changes require versioned evidence and independent review; they cannot be lowered to make
a release pass silently.

## Failure and recovery requirements

For ambiguity, contradiction, authorization denial, stale release, invalid state, provider outage,
retrieval miss, compilation error and failed observation, the assistant must:

1. classify the failure layer;
2. avoid an unsupported success claim;
3. preserve or recover the last proven state;
4. explain what is known and unknown;
5. offer only grounded next actions;
6. maintain trace/provenance for review.

## Deliverables

- versioned readiness matrix by journey, component family and certification level;
- final deterministic and stochastic eval reports;
- spoken-human and compound-request corpus;
- security/isolation/provider-degradation evidence;
- release and rollback runbook;
- public support/certification matrix and presentation scenarios;
- drift/release gate integrated with canonical repositories;
- final architecture decision/acceptance record;
- ongoing ownership and new-capability contribution checklist.

## Checkpoint criteria

- [ ] Every deterministic release blocker is zero.
- [ ] Every material decision has current provenance.
- [ ] Create, refine, apply, observe, explain and recover are proven.
- [ ] Representative families and multi-component pages pass.
- [ ] Spoken, hesitant, corrective, referential, compound and contradictory requests pass defined
      thresholds.
- [ ] Failure modes preserve proven state and fail closed.
- [ ] Provider/model metrics identify effort, release, cost and variance.
- [ ] Tenant/environment isolation and secret redaction are proven.
- [ ] Public docs/examples match actual certified support.
- [ ] Local and minimal remote release gates pass.
- [ ] Independent architecture, security and product review accepts readiness.

## Post-readiness rule

After `CP-7`, every new or changed public component capability must enter the certification/drift
pipeline in the same change. A capability is not generative-UI-ready merely because it renders in
an editor or appears in a registry projection.
