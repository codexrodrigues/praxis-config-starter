# Phase 3 — Table Completeness Pilot

Checkpoint: `CP-3`
Status: pending `CP-2` acceptance

## Objective

Apply the approved Phase 2 method to the entire `praxis-table` surface through generation and
dependency-driven tests, not manual correction of hundreds of paths.

## Workstreams

### 1. Authoring classification closure

Close every Phase 1 unclassified/partial path. Preserve intentional consult-only, runtime-derived
and unsupported cases with reasons.

### 2. Operation conformance

For all table operations, prove target resolution, schema, validators, effects/handlers, affected
paths, dependency closure, post-state, round-trip, idempotency and applicable inverse/removal.

### 3. Dependency/state generation

Generate minimal, boundary and negative tests from actual dependency/conflict clusters. Retain
presentation pairwise coverage as a separate dimension.

### 4. Outcome observation

Group operations by observable outcome:

- column/layout/header visibility;
- renderers and composed items;
- filters/sort/pagination/selection;
- toolbar/row/bulk actions;
- expansion/details/related surfaces;
- export and interaction behavior;
- accessibility and conditional presentation;
- config-only or runtime-derived state.

Define the smallest authoritative observer for each family. Browser DOM is required only where
runtime materialization cannot be proved by canonical component state.

### 5. Explanation fidelity

Generate permitted explanation claims from verified state/outcome. Test that partial or failed
observation produces diagnostics rather than success.

### 6. Human-language corpus

Add natural, spoken, long, hesitant, referential, compound and contradictory variants across
operation families. Assign the expected semantic operation/target and ambiguity behavior. Phase 3
creates the labeled corpus and may record an initial baseline; Phase 4 owns the repeatable provider,
retrieval and Skill qualification required to close C7.

## Required reports

- table deterministic certification summary by C0–C6, with explicit C7/C8 pending state;
- dependency coverage and unresolved clusters;
- operation execution/observer/explanation matrix;
- deterministic failure taxonomy;
- natural-language metrics by model/effort;
- drift report against the prior table manifest/registry release.

## Checkpoint criteria

- [ ] 100% public paths explicitly classified.
- [ ] 100% authorable operations reach deterministic certification C0–C6.
- [ ] 100% dependency edges close or block under tested states.
- [ ] Every visual/interactive operation family has outcome proof.
- [ ] False-success claims are zero.
- [ ] All unrelated config survives round-trip.
- [ ] Labeled language variants cover all semantic operation families without claiming C7 pass.
- [ ] Generated certification is reproducible.
- [ ] Table-specific learnings are expressed as method, not hardcoded exceptions.
- [ ] Independent review certifies the pilot as reusable for Phase 6.

## Non-goal

This phase does not certify all Praxis components or provider-level language qualification. It
certifies the deterministic method against the most complex single-component pilot and records
where the method requires Phase 4 knowledge/eval evidence or cross-component evidence.
