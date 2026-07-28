# Phase 5 — Domain to Component to Operation Continuity

Checkpoint: `CP-5`
Status: pending `CP-4` acceptance

## Objective

Connect the existing Machine-First Semantic IR and component-selection evidence to certified
component operations, so domain meaning determines what the interface must accomplish and the
platform proves that the selected component can accomplish it.

This phase closes continuity; it does not recreate Domain Catalog, Semantic IR,
`UiCompositionPlan`, resource discovery or component manifests.

## Decision chain

```text
user utterance and governed session context
  -> semantic intent and domain concept
  -> governed resource/surface/action/availability evidence
  -> UI purpose and interaction requirements
  -> compatible component candidates
  -> required certified component operations
  -> UiCompositionPlan and executable changes
  -> preview, apply and runtime observation
  -> explanation with end-to-end provenance
```

A component is eligible only if the current release supports the required behavior at the maturity
needed by the journey. Visual resemblance or lexical similarity is not compatibility evidence.

## Responsibility boundaries

| Concern | Canonical owner | Consumer/projection |
| --- | --- | --- |
| Business meaning, policy and governed decision | Config/domain-decision boundary | Semantic IR and UI materializations |
| Resource, field, surface, action and availability metadata | Metadata starter and governed API evidence | Domain/component selection tools |
| Component public behavior and configuration | Owning `@praxisui/*` library | Component capability certification view |
| Authoring operation execution | Source manifest plus config-starter executable registries | Planner/preview/apply |
| Page composition | Existing `UiCompositionPlan`/Page Builder contract | Runtime page materialization |
| Current UI state and outcome | Runtime observation grounded against canonical identities | Explanation and recovery |

Rules shared by multiple surfaces must be authored as governed domain decisions and materialized
into consumers. They must not be hidden in table/form/chart configuration.

## Reference journeys

### 1. Consult employees

The request to list employees with identity, photo and status must ground the employee resource,
allowed fields and consult surface, then select a table/list capability profile that can render the
required fields and status semantics. A request for row details additionally requires a certified
detail action or related-surface operation.

### 2. Create or edit an employee

The platform must select an applicable form journey, derive fields and option sources from current
metadata, apply authorization/availability, preserve identity/concurrency semantics and observe the
saved result. The LLM must not invent field keys from the user's wording.

### 3. Approve hours or vacations

Approval is a domain action/workflow, not a generic button. The platform must ground the canonical
action, target entity, inputs, current availability and authorization before choosing a form,
dialog, action bar or other presentation. If the action does not exist, the assistant explains the
gap rather than synthesizing a business endpoint.

### 4. Analyze indicators

The platform grounds metric definitions, dimensions, filters and provenance before choosing a
chart/dashboard composition. A chart cannot create a metric that the domain layer does not define.

### 5. Inspect related details

Dialog, drawer, expansion or navigation is selected from interaction requirements, certified
operations, viewport/context and available detail surfaces. The choice and fallback are explicit.

### 6. Attach documents

File upload is selected only when resource metadata, upload contract, limits and target association
are governed. UI success requires both upload/association evidence and visible journey state.

## Compound and conversational behavior

The continuity layer must handle a single spoken turn that contains several changes, references the
current page, corrects itself or conflicts with an earlier clause. The LLM resolves semantic intent
and uncertainty; deterministic tools validate each canonical target and operation.

For compound requests:

- build an explicit set/sequence of semantic decisions;
- expose dependencies and conflicts before apply;
- preserve atomicity or explain partial boundaries;
- request clarification only for decisions that materially change the outcome;
- never use keyword order as the primary conflict resolver;
- observe and explain each applied decision separately.

## Workstreams

1. Extend existing acceptance cases with the six reference journeys and spoken variants.
2. Join component-selection candidates with certification/maturity evidence.
3. Define rejection diagnostics for unavailable, incompatible or uncertified operations.
4. Preserve semantic provenance through `UiCompositionPlan`, preview, apply and observation.
5. Prove multi-component composition and ownership boundaries.
6. Route reusable business semantics to governed domain decisions.
7. Test refinement of an existing page without losing session/reference lineage.

## Required evidence per material selection

```text
semantic intent/concept identity
resource/surface/action identity and release
required UI purpose and interaction constraints
candidate components and rejection reasons
selected component/profile and certification level
required operations and dependency evidence
authorization/availability evidence
composition/materialization lineage
runtime outcome evidence
explanation claims
```

## Deliverables

- domain-to-component-to-operation selection conformance view;
- compatibility and maturity rejection tests;
- six reference journeys with positive, ambiguous, denied and unavailable cases;
- compound/spoken request corpus with deterministic decision lineage;
- multi-component `UiCompositionPlan` proof;
- table C8 end-to-end certification report;
- provenance and explanation report;
- classification of any remaining domain/component contract gaps;
- updated `CURRENT-STATE.md` and independent review handoff.

## Checkpoint criteria

- [ ] Every material selection has concept/resource/surface/action/capability provenance.
- [ ] Required component operations are certified at the journey's minimum level.
- [ ] Incompatible or unavailable components/actions are rejected with grounded diagnostics.
- [ ] No resource, field, action, input or technical binding is invented.
- [ ] No primary keyword/regex intent routing is introduced.
- [ ] Business rules shared across surfaces remain governed domain decisions.
- [ ] Multi-component composition preserves canonical ownership and apply lineage.
- [ ] Consult, preview, refine, apply, observe and explain are proven.
- [ ] The table pilot reaches C8 with reviewed domain, execution and outcome evidence.
- [ ] Spoken, referential, corrective, compound and contradictory cases are measured.
- [ ] Independent review accepts end-to-end provenance.

## Non-goals

- adding a second component-selection envelope;
- teaching domain rules through component examples;
- declaring all journeys transactional when their canonical actions are not;
- accepting an attractive UI as proof of correct domain semantics;
- using component maturity to hide missing domain metadata.
