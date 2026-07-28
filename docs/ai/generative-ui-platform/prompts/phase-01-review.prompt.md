# Ready-to-Copy Prompt — Independently Review Phase 1

You are the independent reviewer for `CP-1` of the Praxis Generative UI Platform Program. Review
the Phase 1 executor's actual files, diffs, generated inventory and focused validation. Do not rely
on its summary and do not implement fixes unless the user separately authorizes a correction pass.

## Mandatory reading

Read completely:

1. workspace root and applicable local `AGENTS.md` files;
2. `praxis-config-starter/docs/ai/generative-ui-platform/README.md`;
3. `baseline.snapshot.json` and `BASELINE-EVIDENCE-2026-07-27.md`;
4. `ARCHITECTURE-CONTINUITY.md`, `CAPABILITY-COVERAGE-MODEL.md` and
   `DOMAIN-TO-COMPONENT-CONTINUITY.md`;
5. `PROGRAM-PLAN.md`, `CURRENT-STATE.md` and `ORCHESTRATOR.md`;
6. `phases/phase-01-component-inventory.md`;
7. `QUALITY-GATES.md` and `REVIEW-CHECKLIST.md`;
8. the executor's Phase 1 handoff, machine-readable inventory, human report, generator/checker,
   tests and validation output.

Treat `B-001...B-014` as accepted baseline except where the executor claims an evidenced source
delta. Use focused source checks to verify its joins and counts; do not repeat the original broad
study.

## Review posture

The executor may have used an economical model. Verify every checkpoint through reproducible
evidence. Good prose is not evidence of executable coverage.

Keep the review read-only unless explicitly asked to fix findings. Preserve and separate all
pre-existing user changes. Inspect repository status, relevant diffs and current HEADs before
judging scope hygiene.

## Required checks

### Scope and ownership

- Phase 1 stayed within inventory/docs/internal tooling.
- No public contract/runtime behavior was changed.
- Canonical source owners and generated projections are separated.
- Existing generators/reports were reused or the reason for a new internal artifact is proven.
- Unrelated dirty-worktree changes were not overwritten, staged or attributed to Phase 1.

### Reproducibility and counts

- Run the documented regeneration/check command from a clean-enough read context.
- Confirm deterministic output or explain environmental nondeterminism.
- Reconcile 500 table capability paths, 68 operations, 340 dependency-bearing paths and 79
  distinct dependency paths, unless source deltas prove replacement values.
- Confirm that projection/profile copies are deduplicated by stable source identity.
- Confirm that the existing 68/68 and 180 pairwise results are not presented as executable table
  completeness.

### Join integrity

Sample every operation family and inspect all reported orphans. Verify that:

- each public path has source/public API evidence and one authoring classification;
- each authorable relation points to real operation/target resolver/effect/handler/validator
  dispatch, not only an identifier in JSON;
- `dependsOn` edges are represented without assuming they close automatically;
- pre-state, post-state, invariant, observer and explanation evidence are not inferred from
  successful compilation;
- C0–C8 levels follow `CAPABILITY-COVERAGE-MODEL.md`;
- missing relations use one of the four adherence classifications with defensible evidence.

Explicitly inspect the `rowAction.add` row. The inventory must show the accepted baseline gap:
`actions.row.actions[]` is changed, `actions.row.enabled` is required by runtime, and current focused
fixture setup can mask the minimal-state failure. Phase 1 must not have silently fixed it.

### Architecture guardrails

- No parallel Semantic IR, RAG, registry, page model or runtime observation layer exists.
- No keyword/regex primary routing was introduced.
- Skills/embeddings/chunks are not treated as canonical truth.
- No table-specific convention is proposed as a universal contract without cross-family evidence.
- Every proposed public-contract need is classified `lacuna-real-de-contrato` and identifies datum,
  owner, consumers, derived artifacts and minimum proof.

### State and handoff truth

- `CURRENT-STATE.md` matches actual files and validation.
- Unvalidated areas and blockers are explicit.
- The Phase 2 proposal is minimal and based on Phase 1 evidence.
- Draft Phase 2 execution/review prompts cite the accepted inventory, preserve open findings and do
  not authorize work beyond `phase-02-executable-operation-contract.md`.
- No release, publication, deployment or premature Phase 2 implementation occurred.

## Review output

Return exactly one checkpoint outcome:

- `accepted`;
- `accepted-with-nonblocking-followups`;
- `changes-required`;
- `blocked`.

For every finding, provide:

```text
severity
checkpoint claim affected
file/artifact and precise location
observed evidence
why it matters
required correction
minimum revalidation
```

Conclude with:

- commands you ran;
- counts independently confirmed;
- files/repositories reviewed;
- what you did not validate;
- whether `CP-1` may close;
- whether `CURRENT-STATE.md` may advance to Phase 2.

Do not edit `CURRENT-STATE.md` merely to approve your own review. If the outcome is accepted, hand
the acceptance evidence back for a bounded checkpoint-recording step. If changes are required,
keep `CP-1` open and do not start Phase 2.
