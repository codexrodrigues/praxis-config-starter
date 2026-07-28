# Ready-to-Copy Prompt — Execute Phase 1

You are starting Phase 1 of the Praxis Generative UI Platform Program in the workspace
`praxis-plataform`.

## Program objective

Enable the Praxis LLM assistant to understand governed domain meaning, select compatible Praxis
components, author every supported capability through deterministic operations, observe the actual
runtime result and explain the decision with evidence. `praxis-table` is the first high-complexity
pilot; it is not a special architecture or the limit of the program. The validated method will be
applied to all Praxis component families and multi-component interfaces.

A fully generative interface does not mean arbitrary JSON, HTML or Angular generation. It means a
governed semantic decision materialized inside the declared and certified capability graph.

## Mandatory reading order

Read completely, in this order:

1. the workspace root `AGENTS.md` and every local `AGENTS.md` in repositories you may touch;
2. `praxis-config-starter/docs/ai/generative-ui-platform/README.md`;
3. `praxis-config-starter/docs/ai/generative-ui-platform/baseline.snapshot.json`;
4. `praxis-config-starter/docs/ai/generative-ui-platform/BASELINE-EVIDENCE-2026-07-27.md`;
5. `praxis-config-starter/docs/ai/generative-ui-platform/ARCHITECTURE-CONTINUITY.md`;
6. `praxis-config-starter/docs/ai/generative-ui-platform/CAPABILITY-COVERAGE-MODEL.md`;
7. `praxis-config-starter/docs/ai/generative-ui-platform/DOMAIN-TO-COMPONENT-CONTINUITY.md`;
8. `praxis-config-starter/docs/ai/generative-ui-platform/PROGRAM-PLAN.md`;
9. `praxis-config-starter/docs/ai/generative-ui-platform/CURRENT-STATE.md`;
10. `praxis-config-starter/docs/ai/generative-ui-platform/ORCHESTRATOR.md`;
11. `praxis-config-starter/docs/ai/generative-ui-platform/phases/phase-01-component-inventory.md`;
12. `praxis-config-starter/docs/ai/generative-ui-platform/QUALITY-GATES.md` and
    `REVIEW-CHECKLIST.md`.

Read the existing Semantic IR, acceptance, RAG and agentic-authoring documents only where Phase 1
links them directly. Do not perform another broad architecture survey.

Use these versioned Praxis Skills if available: `praxis-product-evolution`,
`praxis-generative-ui-authoring`, `praxis-ai-authoring-manifests` and
`praxis-ai-registry-ingestion`. Treat repository source and canonical contracts as truth if a local
Skill has drifted; update a Skill only when Phase 1 proves a small, objective recurring gap.

## Accepted baseline

Treat evidence IDs `B-001` through `B-014` as accepted findings. Do not repeat the investigation
that produced them. Revalidate an item only when:

- its source file changed after the recorded repository SHA;
- a focused test contradicts it; or
- the baseline explicitly calls it a hypothesis.

Begin with read-only repository status and delta checks against `baseline.snapshot.json`. Preserve
all pre-existing user changes. Never reset, discard, bulk-format or include unrelated edits.

The accepted baseline includes these critical facts:

- table has 500 capability paths;
- 340 paths declare `dependsOn`, using 79 distinct dependency paths;
- table projects 68 operations, 23 validators, 32 targets, 52 examples and 168 chunks;
- `rowAction.add` currently appends an action while the runtime separately requires
  `actions.row.enabled`; a focused fixture pre-enables that dependency and can mask the failure;
- existing static coverage proves structural projection, not executable/runtime completeness;
- human browser coverage is small and explanation success is not yet outcome proof;
- Semantic IR, progressive tools, component discovery, `UiCompositionPlan`, RAG industrialization
  and a first runtime-observation contract already exist and must be extended, not recreated.

## Classification and scope

The program is `arquitetural` and `transversal`; a future slice may be `contrato-publico`. This
session executes only Phase 1 inventory. It must remain analysis/docs/internal tooling unless the
inventory proves a real public-contract gap. Do not make that contract change in Phase 1.

Before editing, provide a short impact map covering:

- canonical source repositories/files;
- affected consumers and derived artifacts;
- public docs/examples/playgrounds potentially affected later;
- minimum focused validations;
- breaking-change risk;
- current dirty-worktree overlap.

## Phase 1 objective

Build a generated or reproducibly computed conformance inventory for `praxis-table` that joins:

```text
public configuration path
  -> public API/source owner
  -> editor support
  -> capability and dependencies
  -> authoring class
  -> operation and target resolver
  -> effect/handler and affected paths
  -> validator dispatch
  -> pre-state, dependency and post-state evidence
  -> runtime/state observer
  -> explanation evidence
  -> positive/boundary/negative tests
  -> C0-C8 certification level
  -> adherence classification and gaps
```

First inventory the existing generators and reports. Extend the correct existing owner when
possible; do not create a duplicate reporting pipeline merely because a new file is convenient.

Classify each public capability as one of:

- `authorable`;
- `consult-only`;
- `runtime-derived`;
- `unsupported`.

Classify every gap as one of:

- `ja-suportado-so-ux`;
- `ja-suportado-mal-nomeado-ou-mal-materializado`;
- `suportado-parcialmente`;
- `lacuna-real-de-contrato`.

Only the last class can become a future contract proposal, with missing datum, canonical owner,
consumers, derived artifacts and minimum proof explicitly identified.

## Required tasks

1. Reconcile current source deltas with `B-001...B-014`.
2. Identify the authoritative extraction/join point for the inventory.
3. Classify all table public capability paths.
4. Reconcile all operations and dependency edges with executable backend support.
5. Prove resolver, validator, effect and handler implementation; do not infer it from IDs/cards.
6. Link editor, runtime, observer, explanation and test evidence.
7. Distinguish source owners from generated/profile projections.
8. Compute C0–C8 without promoting structural presence to execution proof.
9. Report all orphan paths, operations, dependencies, validators, handlers, observers and claims.
10. Define a repeatable drift command/check.
11. Propose the smallest Phase 2 vertical change using `rowAction.add`, without implementing it.

## Prohibited actions

- Do not fix `rowAction.add` or another isolated scenario during inventory.
- Do not create parallel Semantic IR, RAG, registry, `UiCompositionPlan`, page model or runtime
  observation contracts.
- Do not use keyword/regex matching as primary intent routing.
- Do not treat Skills, embeddings, chunks, examples or frontend observations as canonical truth.
- Do not add a public manifest field, DTO, endpoint or exported type in Phase 1.
- Do not claim coverage from a schema/card, successful compilation or assistant message alone.
- Do not publish npm/Maven artifacts, create tags, deploy or trigger iterative GitHub Actions.
- Do not commit or push unrelated dirty-worktree changes.

## Deliverables

- a machine-readable inventory generated or reproducibly computed from source owners;
- a human report with counts, joins, gaps and adherence classifications;
- the exact command/script that regenerates the result;
- focused tests for any inventory/join code introduced;
- a source-versus-projection drift report;
- a minimal Phase 2 impact proposal, without premature public contract;
- draft `prompts/phase-02-execute.prompt.md` and `prompts/phase-02-review.prompt.md`, grounded in the
  actual inventory and still subject to the `CP-1` review;
- an updated `CURRENT-STATE.md` containing files, commands, validation, unvalidated items and next
  handoff;
- evidence for an independent reviewer to decide `CP-1`.

The session must determine the correct canonical location for generated output before creating it.
Do not make an internal certification report public accidentally.

## `CP-1` exit requirements

- 500 capability paths classified, or an evidenced source delta explains the new number;
- 68 operations reconciled, or an evidenced source delta explains the new number;
- 340 dependency-bearing paths and 79 distinct dependencies reconciled;
- current 68/68 and 180 pairwise reports correctly limited to structural/presentation claims;
- executable support distinguished from declaration;
- every missing relation assigned an adherence class;
- reproducible, source-linked generation and drift check;
- draft Phase 2 execution/review prompts available for the independent reviewer;
- no public contract or runtime behavior changed;
- all pre-existing changes preserved;
- exact validation and non-validation reported.

Stop at `CP-1`. Do not mark Phase 2 ready until a separate session runs
`prompts/phase-01-review.prompt.md` and accepts the checkpoint. If blocked by overlapping user work
or an unknowable source owner, record the evidence and stop instead of guessing.
