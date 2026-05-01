# Project Knowledge Governed Writes Handoff - 2026-05-01

## Context

Praxis is being steered as a platform for AI-authored semantic decisions, with governed authoring, validation, approval, publication/materialization and runtime proof. This handoff captures the local-first batch prepared after the Project Knowledge cockpit became able to persist governed writes through Domain Knowledge change-sets.

The goal is to keep the batch ready for review/merge while avoiding unnecessary GitHub Actions runs during small local iterations.

## Current Batch

### praxis-config-starter

- Branch: `docs/project-knowledge-governed-writes-checkpoint`
- Commit: `2fe62d2 Document governed project knowledge write checkpoint`
- Scope: documentation checkpoint and release-readiness guidance.
- Canonical meaning: Domain Knowledge `add_evidence` is now the governed write path for Project Knowledge cockpit read/write proof.
- Local validation:
  - `git diff --check`
  - ignored-local-artifact check for `.env.openai.local.sh` and local E2E artifacts.

### praxis-api-quickstart

- Branch: `docs/page-builder-project-knowledge-cockpit-proof`
- Commit: `0281f3a Document Page Builder Project Knowledge cockpit proof`
- Scope: downstream host documentation.
- Canonical meaning: quickstart HTTP proof remains the host/runtime contract proof, while the Page Builder cockpit E2E proves browser UX against the same governed Domain Knowledge contract.
- Local validation:
  - `git diff --check`
  - ignored-local-artifact check for quickstart build output.

### praxis-ui-angular

- Branch: `test/page-builder-e2e-origin-env-runner`
- Commits:
  - `b808fe3d Clarify page builder E2E env file help`
  - `18598b70 Honor semantic environment in page builder E2E runner`
  - `5d07cf8c Align page builder local E2E environment`
- Scope: local E2E runner and documentation for the Project Knowledge cockpit proof.
- Canonical meaning: the official local browser proof now uses the same semantic environment/origin as the controlled UI/API flow and executes create, validate, approve, apply and readback for a governed change-set.
- Local validation:
  - `node --check scripts/run-page-builder-agentic-authoring-e2e.js`
  - `node scripts/run-page-builder-agentic-authoring-e2e.js --help`
  - `git diff --check`
  - `AI_PROVIDER=openai AI_ENV_FILE=../praxis-config-starter/.env.openai.local.sh PRAXIS_E2E_TIMEOUT_MS=900000 ./tools/local-e2e/run-project-knowledge-audit-cockpit-local.sh`
  - Result: Project Knowledge cockpit E2E passed locally, with services cleaned up by the runner.

## Recommended Merge Order

1. `praxis-config-starter`: merge the documentation checkpoint first, because it is the canonical operational record for the governed write milestone.
2. `praxis-api-quickstart`: merge downstream proof documentation next, because it references the canonical Domain Knowledge contract from the host perspective.
3. `praxis-ui-angular`: merge runner/documentation last, because it provides the executable browser proof used by the release checklist.

This order is semantic, not technically blocking. The branches are already independently pushed.

## GitHub Actions Budget

No GitHub Actions were intentionally triggered by branch pushes in this batch.

Opening PRs may trigger CI depending on repository workflow path filters:

- `praxis-config-starter`: expected low/no CI cost for docs-only branch unless repository settings or workflows change.
- `praxis-api-quickstart`: likely CI cost on PR because README/docs changes can be included by Java CI path filters.
- `praxis-ui-angular`: likely CI cost on PR because the branch touches `scripts/**` and E2E docs.

Recommended policy: do not open these as small incremental PRs unless we are ready to spend the phase-level CI budget. Keep local validation as the source of confidence until the batch is ready to close.

## Release Readiness Meaning

This batch does not publish a Maven/npm release. It prepares the local/browser proof needed before a later release gate.

The milestone proven locally is:

1. Page Builder cockpit creates a governed Project Knowledge write as a Domain Knowledge change-set.
2. The backend validates the `add_evidence` payload.
3. The cockpit approves/applies the change-set.
4. The cockpit reads back the materialized project knowledge state.

## Remaining Work Before RC Cut

- Decide when to spend the PR/CI budget for the three branches.
- If opening PRs, include the local validation evidence above in each PR body.
- Before publication/release, run one phase gate that covers the current canonical flow end-to-end instead of rerunning remote Actions for every small adjustment.
- Keep broader future work explicitly separate: rollback UX, vector/RAG evidence retrieval, richer materialization diagnostics and runtime enforcement validation beyond the current Project Knowledge proof.

