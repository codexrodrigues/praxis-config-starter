# Page Builder Governed Continuity Phase

Status: local browser proof completed; docs handoff in progress
Date: 2026-05-01
Scope: post-rc.37 functional phase

## Recommendation

The next functional phase should make the Page Builder cockpit a coherent
continuation surface for governed semantic decisions and governed Project
Knowledge writes.

Do not start with another Maven/npm publication. The next cut should begin as a
local-first implementation batch that proves the browser and backend can continue
from safe handoff state through governed actions without making Page Builder the
source of business rules or memory.

## Why This Is The Next Phase

`praxis-config-starter:0.1.0-rc.37` closed the backend and published-runtime
proof for safe Domain Knowledge change-set timelines. The platform now has the
canonical pieces needed for continuity:

- Domain Knowledge change sets can be created, validated, approved, applied and
  inspected through safe timeline events.
- Project Knowledge can be represented as governed Domain Knowledge evidence
  instead of client-side memory.
- Domain Rules already expose governed intake, simulation, publication,
  materialization and timeline surfaces.
- The Page Builder already has stream turns, shared-rule handoff rendering and
  local browser proof lanes.

The remaining gap is product coherence: the cockpit must guide the user or AI
from a safe handoff into the right governed continuation action, then show proof
without leaking raw payloads.

## Canonical Direction

The Page Builder must remain a cockpit/runtime projection:

- It may present governed actions.
- It may call canonical backend endpoints.
- It may show safe status, validation and timeline summaries.
- It may link to readback or materialization evidence.

The Page Builder must not:

- store canonical Project Knowledge locally;
- invent domain-rule or domain-knowledge contracts;
- apply LLM-authored knowledge silently during normal authoring;
- use preview/apply page mutation as the materialization path for shared
  business decisions;
- render raw patch payloads, prompt history or unrestricted evidence text in the
  common cockpit.

## Phase Goal

Create a single governed continuation path in the Page Builder that can handle
both families of semantic decision work:

1. Shared-rule handoff continuation:
   - create or open definition;
   - simulate;
   - approve or publish;
   - materialize;
   - open runtime enforcement validation when available.
2. Project Knowledge change-set continuation:
   - create a governed proposal;
   - validate;
   - approve or reject;
   - apply explicitly;
   - read back safe audit/timeline and later authoring citation.

The user experience should feel like one governed cockpit, not two unrelated
debug panels.

## Implementation Slices

### Slice 1. Continuity Contract Inventory

Inventory the existing Angular services and backend endpoints used by the
cockpit.

Deliverables:

- list of canonical endpoints already available;
- list of missing typed client methods, if any;
- confirmation that no new backend endpoint is needed for the first UI slice;
- updated local test command map.

Definition of done:

- no implementation starts from guessed routes;
- the plan names exact Angular files and services to touch.

### Slice 2. Unified Governed Action Model

Introduce or consolidate a UI-local action model that represents continuation
actions without becoming a business-rule source.

Allowed action kinds:

- `open_definition`
- `simulate`
- `approve`
- `reject`
- `publish`
- `materialize`
- `validate_enforcement`
- `create_knowledge_change_set`
- `validate_knowledge_change_set`
- `apply_knowledge_change_set`
- `open_timeline`

Definition of done:

- action availability is derived from backend handoff/status;
- unavailable actions explain why they are blocked;
- no action encodes raw rule condition or raw patch payload.

### Slice 3. Cockpit UX Continuity

Make the Page Builder cockpit display a clear continuation rail:

- current governed artifact;
- next safe action;
- validation/review status;
- timeline/audit summary;
- materialization or readback proof when present.

Definition of done:

- shared-rule and Project Knowledge flows use the same visual language;
- safety states are explicit: pending validation, needs approval, applied,
  blocked, failed;
- raw sensitive payloads remain hidden from the common cockpit.

### Slice 4. Browser E2E Proof

Extend the existing local browser proof rather than creating a new ad hoc test.

Minimum proof:

- starts local quickstart and Angular through documented runners;
- uses configured Neon-backed persistence;
- runs with the real LLM provider only when required by the scenario;
- proves no silent mutation before approval/apply;
- proves timeline/audit status is visible;
- proves post-apply Project Knowledge influence can be cited safely.

Definition of done:

- browser test fails if Page Builder calls preview/apply as the shared decision
  materialization path;
- browser test fails if raw patch/evidence/prompt content appears in the common
  cockpit;
- services are cleaned up by the runner.

### Slice 5. Corpus And Docs Handoff

Only after the local browser lane is green:

- update quickstart docs with the exact local proof command and outcome;
- update HTTP examples only if a stable protected/read-only endpoint is added or
  newly confirmed;
- do not publish npm/Maven unless a named downstream consumer needs the cut.

Definition of done:

- docs explain the canonical owner of each action;
- `llmOperational` remains limited to safe operational reads;
- protected config actions stay out of unauthenticated LLM surfaces.

## Local-First Validation

Prefer the smallest reliable command for the touched slice.

Expected lanes:

```bash
# Shared-rule cockpit/timeline lane from the monorepo root.
scripts/workspace/run-local-readiness-lane.sh shared-rule-timeline-cockpit

# Project Knowledge cockpit lane from praxis-ui-angular.
AI_PROVIDER=openai \
AI_ENV_FILE=../praxis-config-starter/.env.openai.local.sh \
PRAXIS_E2E_TIMEOUT_MS=900000 \
./tools/local-e2e/run-project-knowledge-audit-cockpit-local.sh
```

Use GitHub Actions only when closing a phase or when a failure depends on runner
secrets/environment and cannot be reproduced locally.

## Stop Conditions

Pause and redesign if an implementation:

- creates Page Builder-local memory;
- persists chat history as knowledge;
- exposes raw patch/evidence payloads in common cockpit status;
- applies Project Knowledge without explicit approval/apply;
- creates duplicate backend contracts for existing Domain Knowledge or Domain
  Rules actions;
- requires repeated GitHub Actions reruns for basic development confidence.

## Recommended First PR

Start with Slice 1 plus the smallest Slice 2 foundation in `praxis-ui-angular`:

1. inventory the current Page Builder cockpit services/components/tests;
2. identify the existing action/status models;
3. add a typed continuation action model only if no canonical UI model already
   exists;
4. add focused unit tests for action derivation from safe backend handoff
   fixtures;
5. avoid starting dev servers or browser E2E until the typed model is stable.

This first PR should not publish packages and should not require GitHub Actions
for exploratory feedback.

## Local Completion Update - 2026-05-01

The local-first Page Builder continuity batch has now completed the core
implementation slices in `praxis-ui-angular/main`:

- Slice 1 inventoried the existing canonical Domain Rules and Domain Knowledge
  service boundaries.
- Slice 2 introduced the UI-local governed continuation action model without
  making Page Builder a business-rule source.
- Slice 3 connected the cockpit UX for shared-rule and Project Knowledge
  continuation actions.
- Slice 4 passed the managed local Project Knowledge browser lane:

```bash
cd praxis-ui-angular

AI_PROVIDER=openai \
AI_ENV_FILE=../praxis-config-starter/.env.openai.local.sh \
PRAXIS_E2E_TIMEOUT_MS=900000 \
./tools/local-e2e/run-project-knowledge-audit-cockpit-local.sh
```

Observed result after the safe timeline/body assertion update:

- `1 passed (10.2s)` for the focused Project Knowledge cockpit proof;
- the runner installed local `praxis-config-starter`, started quickstart and
  Angular on isolated ports, then cleaned up services;
- the cockpit created, validated, approved, applied and read back a real Domain
  Knowledge change-set;
- the rendered safe timeline body did not expose `conceptKey`,
  `sourceSummary`, `sourcePointer`, `patchHash`, `assistantMessage`,
  `materializedPayload` or raw knowledge summary text.

### Rich Content Renderer Decision

The cockpit now uses the canonical
`domainKnowledgeTimelineToRichContentDocument` projection from `@praxisui/core`,
but it intentionally does not import `<praxis-rich-content>` directly.

Reason: adding the full renderer inside `@praxisui/page-builder` would create a
new public package dependency on `@praxisui/rich-content` only to reuse markup in
an internal cockpit rail. That is not the correct platform tradeoff for this
slice. The canonical contract remains the `RichContentDocument` projection in
`@praxisui/core`; Page Builder renders a light cockpit view from that projection
without redefining timeline semantics.

If a future phase needs the full renderer in cockpit surfaces, first review the
public-lib dependency boundary and decide whether the renderer should be hosted
by a neutral shell or exposed through an existing runtime composition path.

### Remaining Work

Slice 5 is now limited to documentation handoff and release-gate decision:

- update local proof runbooks with the exact result above;
- keep protected config actions out of unauthenticated LLM surfaces;
- do not publish Maven/npm unless a named downstream consumer needs the cut;
- use GitHub Actions only as a phase/release gate, not for exploratory
  iterations already covered locally.
