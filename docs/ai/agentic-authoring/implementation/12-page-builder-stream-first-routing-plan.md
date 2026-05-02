# Page Builder Stream-First Governed Routing Plan

Status: selected as the next source-level platform slice
Date: 2026-05-02
Scope: next capability slice after closing Project Knowledge Vector RAG locally

## Classification

- Change class when implemented: `arquitetural`.
- Current document change: `docs-apenas`.
- Canonical backend owner: `praxis-config-starter`.
- Primary UI consumer: `praxis-ui-angular`.
- Runtime proof host: `praxis-api-quickstart`.
- Derived corpus owner: `praxisui-http-examples`, only if public examples or
  published LLM surfaces change.

## Decision

Choose the next platform slice as:

> Make the Page Builder use the backend turn stream as the primary authoring
> path, while governed semantic decisions fail closed into canonical
> `domain-rules` / `domain-knowledge` handoffs instead of falling back to local
> preview or page mutation.

Do not publish the Project Knowledge Vector RAG checkpoint just because it is
locally proven.

Do not start by adding a new public Domain Knowledge operation or a new RAG
feature. The next bottleneck is that the official cockpit/runtime must make the
canonical backend turn and governed handoff behavior the default user path.

## Why This Slice

The completed local checkpoints prove that the backend can govern semantic
decisions:

- Domain Rules lifecycle, timeline, materialization and enforcement are already
  represented canonically.
- Domain Knowledge change sets support governed `add_evidence`, plain revert
  and replacement-backed supersession.
- Project Knowledge retrieval excludes inactive evidence.
- Project Knowledge Vector RAG is opt-in, derived and locally proven against
  Neon.

The remaining platform risk is not another backend primitive. It is UX/runtime
alignment:

- the Angular Page Builder already has stream support, but the stream is not yet
  clearly the default path for the right flow;
- fallback behavior must be explicit and safe;
- governed prompts must not silently fall back to local preview/apply;
- browser proof must show the backend authored or routed the decision, not the
  client.

This is the correct next platform slice because Praxis is a platform of
AI-authored semantic decisions. The official cockpit must make canonical
backend decisions visible, recoverable and hard to bypass.

## Explicitly Deferred

The following are intentionally not the next slice:

- Maven Central publication for Project Knowledge Vector RAG.
- npm publication for Angular packages.
- Hosted smoke against the published quickstart.
- First-class public `supersede_evidence`.
- Broader `delete_*`, `replace_*` or bulk rollback operation types.
- Auto-writing Domain Rules directly from the engine.
- Making vector store a canonical memory source.

Each of those requires a named downstream need or a separate public-contract
decision.

## Impact Map

Canonical backend:

- `AgenticAuthoringTurnEngine`
- `AgenticAuthoringTurnStreamService`
- route classification / route outcome services
- `AiTurnEventEnvelope` and safe stream event projections
- current shared-rule / Project Knowledge handoff DTOs

Primary UI consumer:

- `praxis-ui-angular`
- `@praxisui/page-builder`
- `@praxisui/ai`
- Page Builder assistant/cockpit components
- local browser E2E runners under `tools/local-e2e`

Runtime proof host:

- `praxis-api-quickstart`, only as the backend host for real HTTP/SSE proof.

Docs and derived artifacts:

- this implementation guide;
- browser E2E definition of done;
- quickstart LLM/domain authoring guide if commands or expectations change;
- `praxisui-http-examples` only if public corpus/examples are promoted.

Minimum validation for this planning commit:

- `git diff --check`.

Minimum validation when implementation starts:

- backend focal stream/route tests;
- Angular focal Page Builder tests;
- browser E2E proving stream-first behavior for component authoring;
- browser E2E proving governed prompts fail closed into canonical handoff;
- no GitHub Actions until phase-close gate.

Breaking-change risk:

- low if the first slice only changes default UI flow and preserves payloads;
- medium if SSE result/handoff payload shape changes;
- high if new public fields, endpoints, headers or generated bindings are
  required.

## Slice Boundaries

### Slice A - Inventory And Default-Path Selection

Goal:

- identify the exact Page Builder entry points where stream is available but
  not yet the default;
- decide which prompts are eligible for stream-first component/page authoring;
- decide which prompts must fail closed into governed handoff.

Definition of Done:

- files and services in `praxis-ui-angular` are mapped;
- no new endpoints or contracts are introduced;
- current fallback behavior is documented;
- protected governed routes are named before code changes.

Expected validation:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-ui-angular

git status --short
```

Plus targeted source inspection. No Action.

### Slice B - Stream-First Component Authoring

Goal:

- make backend turn stream the primary path for component/page authoring in the
  Page Builder path selected by Slice A.

Definition of Done:

- component/page prompts start through the backend stream by default;
- probe/replay/cancel remain visible and functional;
- legacy synchronous fallback is explicit, limited and not used for governed
  decisions;
- UI diagnostics show backend progress, not client-side invented execution.

Expected validation:

- Angular focal unit/spec tests for the selected service/component;
- local browser smoke for the selected Page Builder flow.

### Slice C - Governed Prompt Fail-Closed Behavior

Goal:

- ensure business-rule, compliance, validation, approval, eligibility and
  reusable semantic-decision prompts do not fall back to local page preview or
  local mutation.

Definition of Done:

- governed prompts route to canonical handoff/status;
- UI explains blocked fallback clearly;
- no Page Builder-local `createBusinessRule`, `publishRule`,
  `approveRule`, `materializeRule` or equivalent tool is introduced;
- no raw prompt, condition, materialized payload, evidence payload or chat
  history is rendered in the common cockpit.

Expected validation:

- Angular focal tests for the fail-closed branch;
- browser E2E showing a governed prompt returns handoff/cockpit state instead
  of preview/apply.

### Slice D - Local Readiness Lane

Goal:

- turn the proof into one repeatable local lane, reusing existing wrappers and
  ports documented by the repos.

Definition of Done:

- local runner starts/stops quickstart and Angular cleanly;
- browser proof covers stream-first component authoring and governed
  fail-closed routing;
- command and evidence are recorded in docs;
- GitHub Actions remain unused during implementation.

Expected validation:

```bash
# exact command to be filled after inventory confirms the existing runner
```

## Acceptance Criteria

The slice is complete when a real local browser proves all of these:

- component/page authoring uses the backend turn stream as the default path;
- governed semantic-decision prompts do not mutate Page Builder artifacts;
- governed prompts continue through canonical `domain-rules` or
  `domain-knowledge` handoffs;
- fallback is visible, bounded and disabled for protected governed routes;
- stream progress and final status originate from backend events;
- ports and local services are cleaned after the run;
- no GitHub Actions, Maven Central or npm publication were needed for local
  implementation.

## Recommended Next Action

Start with Slice A in `praxis-ui-angular`: inventory the Page Builder authoring
entry points, current stream usage, fallback behavior and existing browser E2E
wrappers.

Do not edit backend contracts or Angular behavior before that inventory is
complete.
