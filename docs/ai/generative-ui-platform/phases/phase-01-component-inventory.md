# Phase 1 — Reproducible Component Inventory

Checkpoint: `CP-1`
Status: ready to execute
Change class: `arquitetural` analysis; implementation should remain internal/docs/tooling unless
review proves a public-contract need

## Objective

Produce a reproducible, source-linked inventory of `praxis-table` that can answer which capabilities
are authorable, how their operations execute, which dependencies exist, and what proof is missing.

This phase does not fix `rowAction.add` and does not add manifest fields.

## Required source audit

Read local AGENTS files first, then inspect:

- `praxis-ui-angular/projects/praxis-core/src/lib/ai/authoring-manifest.types.ts`;
- table public config/types and runtime;
- table editor/settings metadata;
- table capability/context-pack sources;
- table authoring manifest and focused specs;
- table AI adapter and runtime tests;
- `praxis-ui-angular/tools/ai-registry` generators/validators/reports;
- aggregate ingestion entry for `praxis-table`;
- config-starter target resolver, validator and effect compiler registries;
- manifest projection/retrieval and preview-message paths;
- existing table/browser authoring gates.

Do not read every unrelated component implementation.

## Tasks

1. Compare current source revisions with `baseline.snapshot.json`; inspect only relevant deltas.
2. Inventory existing generated coverage/report code before proposing another generator.
3. Extract every public table path and assign `authorable`, `consult-only`, `runtime-derived` or
   `unsupported` with source evidence.
4. Join capability `dependsOn` data with authoring operations, effects, handlers, validators and
   affected paths.
5. Prove backend support for each referenced resolver, validator, effect kind and handler; do not
   infer support from catalog membership alone.
6. Link editor/runtime/test/observer/explanation evidence when it exists.
7. Deduplicate stable source identities and distinguish generated copies/projections.
8. Compute certification level C0–C8 for every relevant row.
9. Report orphan paths, operations, dependencies, validators, handlers, observers and explanation
   claims.
10. Classify every gap under the four adherence categories.
11. Propose the smallest repeatable drift check and the smallest Phase 2 change.

## Minimum inventory dimensions

```text
component/profile identity
public config path
source and public API evidence
editor support
capability and dependsOn
authoring classification
operation and target resolver
effect/handler and affected paths
validator dispatch
pre-state/dependency/post-state evidence
runtime/state observer
explanation claim evidence
positive/boundary/negative tests
certification level
adherence classification
gap reason
```

## Deliverables

- machine-readable inventory generated or reproducibly computed from source owners;
- human report summarizing counts, gaps and classifications;
- documented command/script that produces the same result;
- focused tests for inventory/join logic if code is introduced;
- source/projection drift report;
- minimal Phase 2 impact proposal;
- draft Phase 2 execution and independent-review prompts grounded in the inventory results;
- updated `CURRENT-STATE.md` and review handoff.

The active session must first determine the correct owner/path for generated outputs. Do not make a
new generated artifact public merely because it is useful to this program.

## Checkpoint criteria

- [ ] 500 table capability paths classified or source delta documented.
- [ ] 68 operations reconciled or source delta documented.
- [ ] 340 dependency-bearing capabilities and 79 dependency paths reconciled.
- [ ] Existing static 68/68 and 180 pairwise coverage correctly scoped.
- [ ] Backend executable support distinguished from declarations.
- [ ] Every missing relation assigned an adherence class.
- [ ] Report generation is repeatable and source-linked.
- [ ] No public contract or runtime behavior changed.
- [ ] Draft Phase 2 execution/review prompts exist for checkpoint review.
- [ ] Independent review accepts counts and methodology.

## Stop conditions

Stop and report rather than guessing when:

- the correct canonical source of a path cannot be identified;
- generated artifacts conflict with source owners;
- an existing report already owns the intended output but has ambiguous semantics;
- pre-existing worktree changes overlap required edits;
- a contract extension appears necessary before the inventory can be completed.

The last condition becomes a Phase 2 proposal, not an unreviewed Phase 1 patch.
