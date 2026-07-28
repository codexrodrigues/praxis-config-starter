# Program Plan and Milestones

## Program classification

The documentation package is `docs-apenas`. Implementing the program will be `arquitetural` and
`transversal`, and individual slices may be `contrato-publico`.

Before any public contract edit, the active session must produce the root AGENTS impact map:

- canonical owner;
- affected consumers;
- public docs/examples/playgrounds;
- derived artifacts;
- minimum focused tests;
- breaking-change risk;
- adherence classification.

## Control model

Each phase ends at a blocking checkpoint. The executor updates `CURRENT-STATE.md`, records evidence
and stops. A separate review prompt validates the checkpoint. Only an accepted review opens the next
phase.

Opening the next phase is a bounded checkpoint-recording step: incorporate review findings, create
the next phase's execution and independent-review prompts from the accepted evidence, verify those
prompts against the next phase specification, and only then advance `CURRENT-STATE.md`. Future
prompt pairs are not pre-authored from assumptions that the preceding phase may invalidate.

| Phase | Milestone | Checkpoint |
| --- | --- | --- |
| 0 | Program baseline frozen in the local worktree | `CP-0` closed locally; Git persistence pending |
| 1 | Reproducible inventory | `CP-1` open |
| 2 | Executable operation vertical proof | `CP-2` pending |
| 3 | Complete table pilot | `CP-3` pending |
| 4 | Knowledge/tools/Skills/evals integrated | `CP-4` pending |
| 5 | Domain-to-component-to-operation continuity | `CP-5` pending |
| 6 | Component rollout factory | `CP-6` pending |
| 7 | Platform readiness | `CP-7` pending |

## Phase 0 — Baseline and continuity package

Detailed status: [CURRENT-STATE.md](CURRENT-STATE.md).

Deliverables:

- accepted evidence `B-001...B-014`;
- repository revisions and generated artifact hashes;
- architecture continuity and non-goals;
- coverage/certification model;
- phase plan, gates, orchestrator and handoff prompts.

Exit: complete when this package is internally linked, validated as docs-only and available in the
`praxis-config-starter` worktree. A commit/push is a separate repository operation and must remain
explicit in `CURRENT-STATE.md` until authorized and completed.

## Phase 1 — Reproducible component inventory

Specification: [phases/phase-01-component-inventory.md](phases/phase-01-component-inventory.md).

Goal: generate or reproducibly compute the conformance matrix for `praxis-table` before changing
contracts.

Required result:

```text
public path -> editor -> capability -> dependency -> operation -> resolver/handler
            -> validator -> state proof -> observer -> explanation -> tests
```

`CP-1` exit:

- all 500 paths classified;
- 68 operations and 340 dependency-bearing capabilities reconciled;
- existing reports and generators inventoried;
- gaps classified under the four adherence categories;
- proposed representation remains internal/derived unless a real contract gap is proven;
- repeatable drift command/check designed;
- independent review accepts counts, lineage and gap classification.

## Phase 2 — Executable operation contract

Specification: [phases/phase-02-executable-operation-contract.md](phases/phase-02-executable-operation-contract.md).

Goal: prove the smallest reusable contract/derivation using `rowAction.add`, starting from actions
disabled.

`CP-2` exit:

- the planner/compiler closes `actions.row.actions[] -> actions.row.enabled` or blocks explicitly;
- state-minimal, already-enabled, repeated, ambiguous, invalid and conflicting cases pass;
- unrelated table configuration is preserved;
- backend validation and Angular runtime agree;
- DOM/runtime evidence proves the action affordance;
- the assistant does not claim success before proof;
- no table-specific convention is promoted to a global contract without cross-component evidence;
- updated registry/skills/docs are synchronized only where their owner changed.

## Phase 3 — Complete table certification pilot

Specification: [phases/phase-03-table-completeness-pilot.md](phases/phase-03-table-completeness-pilot.md).

Goal: apply the Phase 2 method systematically to the full table surface.

`CP-3` exit:

- every table path retains an explicit authoring classification;
- every authorable operation reaches deterministic certification C0–C6;
- dependency closure tests cover all declared table dependencies;
- property/state-machine and dependency-cluster tests supplement presentation pairwise tests;
- every visual/interactive operation family has applicable outcome evidence;
- explanation fidelity compares claims with verified state, not message presence;
- a labeled human-language corpus covers every semantic operation family, while provider-level C7
  qualification remains explicitly pending Phase 4;
- table certification is generated and reproducible from source owners.

## Phase 4 — Knowledge, tools, Skills and evals

Specification: [phases/phase-04-knowledge-tools-skills-evals.md](phases/phase-04-knowledge-tools-skills-evals.md).

Goal: make the certified operation evidence progressively available to the model without loading
the entire registry or transferring authority to provider storage.

`CP-4` exit:

- operation retrieval returns coherent dependencies and validation evidence;
- current release/tenant/project tools can be discovered progressively;
- OpenAI Tool Search/MCP adapter is evaluated without replacing internal tool contracts;
- hosted Skills are attached, version-pinned and visible in trace when required;
- Skills describe procedure and use canonical tools rather than embedding mutable component truth;
- File Search/provider projections remain release-scoped derived read models;
- Praxis retrieval benchmark compares current pgvector/hybrid behavior and alternatives;
- evals separate semantic selection, arguments, compilation, state, runtime outcome and explanation;
- Terra/Luna cost, latency and variance are recorded on the same cases.
- the table corpus from Phase 3 closes C7 against explicit provider thresholds.

## Phase 5 — Domain to component to operation

Specification: [phases/phase-05-domain-component-operation-continuity.md](phases/phase-05-domain-component-operation-continuity.md).

Goal: connect the existing Machine-First component-selection envelope to certified executable
component operations.

Reference journeys:

- list and inspect employees;
- create/edit an employee;
- approve hours or vacations through a canonical action/workflow;
- analyze verified metrics;
- open related details;
- attach files in a governed form journey.

`CP-5` exit:

- every material selection has domain/resource/action/capability provenance;
- selection rejects components whose required operation is unavailable or uncertified;
- shared business rules hand off to governed domain authoring;
- multi-component composition preserves each owner's boundary;
- no primary keyword routing or invented technical binding;
- browser/HTTP proof covers consult, preview, refine, apply and explain.
- the table pilot reaches C8 through reviewed end-to-end domain, execution and outcome evidence.

## Phase 6 — Multi-component rollout factory

Specification: [phases/phase-06-multi-component-rollout.md](phases/phase-06-multi-component-rollout.md).

Goal: reuse the certification process across component families with no table-specific fork.

Initial waves, ordered by dependencies and subject to Phase 1 inventory refinement:

1. transactional/data entry: dynamic fields -> dynamic form -> CRUD -> manual/editorial forms ->
   file upload;
2. reading/analytics/presentation: list, chart and rich content;
3. composition/navigation: dialog, expansion, tabs, stepper, settings and Page Builder last;
4. authoring/specialized surfaces: metadata editor and visual/rule/cron builders;
5. explicit classification of runtime-only helpers, probes and non-authorable entries.

`CP-6` exit:

- all 105 current registry entries classified;
- all 20 current manifest families have a certification state;
- generated family/profile projections are not counted as independent canonical manifests;
- component additions/public changes create detectable drift;
- the factory is first proven on one representative family per wave, then every current
  `authorable` family/operation reaches its required certification level before `CP-6` closes;
- consult-only, runtime-derived and unsupported surfaces remain explicitly classified with reasons;
- cross-component composition cases pass without transitive public API shortcuts.

## Phase 7 — Generative UI readiness

Specification: [phases/phase-07-generative-ui-readiness.md](phases/phase-07-generative-ui-readiness.md).

Goal: promote the program from pilots to a platform release gate.

`CP-7` exit:

- zero invented resources, operations, fields, components, inputs or actions;
- 100% provenance for material decisions;
- zero false-success claims;
- ambiguity, contradiction, denial, stale release and provider outage fail closed;
- create, refine, explain, apply and recover are proven across representative families;
- spoken-human corpus covers hesitations, corrections, references and compound requests;
- deterministic local gates pass before a single justified remote release gate;
- public documentation states exactly which families/capabilities are certified.

## Rollout rule for new capabilities

After `CP-6`, a public component change is incomplete until it updates or explicitly preserves:

- source capability/config/editor contract;
- authoring classification;
- operation/dependency/state evidence;
- manifest and backend executor support when authorable;
- observer/explanation evidence when visible;
- generated registry/certification reports;
- focused tests and applicable public docs/examples.

## Publication policy

Phases 1–5 are local-first and do not authorize npm, Maven Central, tags or deployment. A phase may
prepare a release recommendation, but external publication requires explicit authorization and the
project's existing release gates.

GitHub Actions budget per phase: zero during normal development, one justified closing gate, and
one published-host smoke only when a release/deploy is actually in scope.
