# Session Orchestrator

## Purpose

Allow many bounded Codex sessions, including economical models followed by independent review, to
advance the program without repeating the original investigation or losing governance.

## Mandatory opening sequence

1. Read workspace `AGENTS.md` and every local `AGENTS.md` in the intended edit scope.
2. Read this package's `README.md`, baseline, architecture, current state and active phase.
3. Read only existing RFCs directly referenced by the active phase.
4. Inspect repository status before any edit; preserve all pre-existing changes.
5. Compare current HEAD/source deltas with `baseline.snapshot.json`.
6. Revalidate only affected baseline items.
7. Produce the phase impact map and short plan before architectural/public-contract edits.

## Do not repeat the study

Treat `B-001...B-014` as accepted evidence. A broad re-audit is allowed only when multiple source
owners changed so substantially that incremental validation cannot establish lineage. Document that
reason before starting it.

Preferred incremental checks:

```sh
git status --short --branch
git diff --name-only <recorded-sha>..HEAD
git diff --name-only
```

Generated artifacts should be compared by their recorded source revision/hash. Do not infer drift
from a timestamp alone.

When a baseline source was already dirty, compare both repository `HEAD` and the per-file working
tree SHA-256 in `baseline.snapshot.json`. A matching commit with a different file hash is a source
delta and requires focused revalidation of the evidence IDs that cite that file.

## One phase per execution session

- Work only on the phase named in `CURRENT-STATE.md`.
- Do not start later-phase implementation “while here.”
- Stop at the phase checkpoint and prepare evidence for independent review.
- A review session does not implement unrelated improvements. It reports findings or fixes only
  when its prompt explicitly authorizes fixes.
- The next phase opens only after review acceptance is recorded.

## Prompt-pair lifecycle

Only the active/next authorized phase has a final ready-to-copy prompt pair. At every checkpoint:

1. the executor drafts the next `phase-NN-execute.prompt.md` and
   `phase-NN-review.prompt.md` from actual evidence;
2. the independent reviewer checks that the drafts do not assume unproven completion;
3. a bounded checkpoint-recording step incorporates review findings;
4. `CURRENT-STATE.md` advances only after both prompt files exist and link to the accepted evidence;
5. the prior prompt pair remains as execution history.

This prevents stale prompts for later architectural phases while preserving the requirement that
every economical execution session has a separate review prompt.

## Required adherence decision

Before proposing any new field, DTO, endpoint, manifest property, status, chunk or tool, answer:

> What does the platform already know that is not being joined, materialized, observed or explained
> correctly?

Classify the finding as:

- `ja-suportado-so-ux`;
- `ja-suportado-mal-nomeado-ou-mal-materializado`;
- `suportado-parcialmente`;
- `lacuna-real-de-contrato`.

Only the last class can justify a new canonical contract. The proposal must name the missing datum,
owner, consumers, derived artifacts and minimum proof.

## Source-of-truth rules

- Domain semantics: Config/Metadata owners, never a component or prompt.
- Component behavior: owning Angular source/runtime/editor.
- Authoring operations: source manifest plus backend executable registries.
- Corpus: generated projection, never hand-edited truth.
- Runtime observation: evidence requiring canonical grounding.
- Skills: procedure and references, not mutable component truth.
- Embeddings/vector stores: candidate retrieval only.
- Quickstart/playground: proof host, not contract owner.

## Worktree safety

The baseline captured dirty repositories. Before editing:

- list overlapping files;
- do not reset, checkout or rewrite user changes;
- use `apply_patch` for intentional text/code edits;
- avoid bulk formatting outside the phase scope;
- stage/commit only phase files if the user later authorizes a commit;
- never include unrelated changes in a release or PR.

## Validation policy

- Use the smallest reliable local gates for the changed owners.
- Inventory/docs phases use focused validators, generated-report checks, link checks and
  `git diff --check` rather than full builds.
- Public authoring-manifest changes require Angular source/registry proof and backend executable
  registry tests.
- Runtime UI claims require focal browser evidence.
- Domain/resource changes require metadata/config tests and quickstart HTTP proof as applicable.
- GitHub Actions is a phase/release gate, not iterative development tooling.
- Report exactly what was and was not validated.

## Mandatory session close

Update `CURRENT-STATE.md` with:

- phase/checkpoint state;
- completed deliverables;
- evidence paths and commands;
- files/repositories changed;
- unresolved findings;
- validation not run;
- next authorized phase;
- next execution and review prompt paths.

The phase cannot be recorded as opening its successor until that successor's prompt pair exists and
has been checked against the accepted review.

If baseline facts changed, update the baseline only with evidence and preserve the prior value in a
short change note. Do not silently rewrite history.

Do not publish packages, create tags, deploy or mutate external data unless the active phase and
user explicitly authorize that operation.
