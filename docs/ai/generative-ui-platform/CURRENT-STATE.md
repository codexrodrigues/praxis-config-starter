# Current State

Last updated: 2026-07-27
Program state: Phase 0 complete; Phase 1 ready to start
Git persistence: package is uncommitted/unpushed; no commit or publication was requested

## Completed in Phase 0

- [x] Preserved the investigation as stable evidence IDs.
- [x] Captured source repository revisions and generated-artifact hashes.
- [x] Distinguished structural registry coverage from executable/runtime coverage.
- [x] Identified `rowAction.add` as the vertical failure that exposes the cross-source gap.
- [x] Confirmed that domain-to-component Semantic IR and component-selection evidence already exist.
- [x] Confirmed that RAG and runtime-observation foundations must be reused, not recreated.
- [x] Defined the target continuity from domain meaning through evidence-based explanation.
- [x] Defined seven gated phases and a reusable component-certification direction.
- [x] Prepared independent execution and review prompts for Phase 1.
- [x] Validated the package locally inside the `praxis-config-starter` worktree.
- [ ] Commit/push the package only when explicitly authorized; this does not change the Phase 1
      technical scope.

## Active milestone

Phase 1 — Reproducible component inventory.

The next session must produce a generated or reproducibly computed matrix for `praxis-table` that
relates public configuration, editor support, capability, dependency, authoring operation,
resolver/handler, validator, tests, runtime observer and explanation evidence.

It must not implement the `rowAction.add` fix before the inventory classifies the existing support
and proves which missing relationship is local materialization versus a real contract gap.

## Current blocking checkpoint

`CP-1` remains open.

To close it, Phase 1 must prove:

- all 500 table capability paths classified;
- all 68 operations reconciled;
- all 340 declared dependency-bearing capabilities inspected;
- orphan capabilities, orphan operations and unproven success claims reported;
- a repeatable drift check defined;
- no new public contract introduced during inventory;
- a minimal Phase 2 proposal based on the adherence classification.

## Worktree warning

At baseline capture, `praxis-config-starter`, `praxis-ui-angular` and
`praxis-api-quickstart` already contained user changes. Those changes do not belong to this
documentation task. A new session must preserve them and use repository-specific `git status` and
focused diffs before editing.

The new `docs/ai/generative-ui-platform/` directory and the cross-link in the agentic-authoring
implementation README belong to Phase 0. They remain uncommitted at this handoff and must not be
mistaken for unrelated user work or silently included with the pre-existing code changes.

## Handoff

Start with [`prompts/phase-01-execute.prompt.md`](prompts/phase-01-execute.prompt.md). After the
executor reports completion, open a separate review session with
[`prompts/phase-01-review.prompt.md`](prompts/phase-01-review.prompt.md).

Do not advance this file to Phase 2 until the independent review closes `CP-1`.
