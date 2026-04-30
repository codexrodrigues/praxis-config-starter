# Governed Project Knowledge Plan

Status: implementation-ready planning  
Date: 2026-04-30  
Scope: Phase 7 of agentic authoring in `praxis-config-starter`

## Purpose

Phase 7 introduces persistent project memory only as governed platform
knowledge. This is not chat history, browser storage or an opaque prompt blob.
It is a controlled read/write path that lets the authoring engine retrieve
stable project facts, preferences and constraints while preserving auditability,
scope, review and AI visibility.

The first implementation must reuse the existing Domain Knowledge Layer instead
of creating a second memory channel. `domain_knowledge_*` tables already provide
concepts, aliases, bindings, relationships, evidence and change sets. RAG/vector
infrastructure may index approved projections later, but it must remain derived
from canonical storage.

## Current Foundation

The repository already contains the pieces Phase 7 should build on:

- `domain_knowledge_concept` with tenant/environment scope, `context_key`,
  `resource_key`, lifecycle, curation status, `ai_visibility`, classification,
  compliance tags and payload.
- `domain_knowledge_alias`, `domain_knowledge_binding`,
  `domain_knowledge_relationship` and `domain_knowledge_evidence` for
  vocabulary, implementation mapping, semantic graph and auditable grounding.
- `domain_knowledge_change_set` as the governed proposal boundary for LLM,
  human or system authored knowledge changes.
- `vector_store` and `RagVectorStoreService` as shared retrieval infrastructure,
  useful only after canonical storage and visibility policy are explicit.
- `AiTurnEventService`, `AiTurnEventEnvelope` and redaction utilities as the
  safe audit/progress lane for authoring turns.

## Canonical Decision

Project knowledge is a profile of the Domain Knowledge Layer, not a new primary
store.

The canonical model required by Phase 7 maps to existing concepts:

| Phase 7 term | Canonical mapping |
| --- | --- |
| `scope` | `tenant_id`, `environment`, `context_key`, `resource_key` and optional payload qualifiers such as page/component/workspace keys |
| `kind` | `node_type` plus a governed `payload.kind` taxonomy for project-specific entries |
| `source` | `source_release_id`, evidence rows and change-set author metadata |
| `status` | `lifecycle`, `curation_status` and change-set status |
| `payload` | structured JSONB on the canonical knowledge row or change set |
| `visibility` | `ai_visibility`, classification, compliance tags and safe projection rules |

Do not add a separate `project_memory` table unless the implementation proves
that the Domain Knowledge Layer cannot express one required field without
semantic distortion. If a table extension is needed, prefer an explicit
`domain_knowledge_project_profile` or equivalent attached to knowledge concepts,
not a free-form memory blob.

## Project Knowledge Taxonomy

Initial `payload.kind` values should be intentionally narrow:

- `project_preference`: stable authoring preference for the current project,
  such as preferred page composition or naming style.
- `domain_decision_hint`: non-executable semantic hint that influences how a
  future decision should be interpreted.
- `component_authoring_pattern`: reusable UI/runtime pattern discovered from
  accepted authored pages.
- `resource_selection_rationale`: explanation of why a resource or operation is
  normally selected in this project context.
- `governance_constraint`: constraint that affects authoring eligibility,
  review requirements or safe output shape.
- `integration_note`: stable operational knowledge about configured services,
  origins or allowed platform surfaces.

These entries must not become executable rules. When knowledge starts requiring
simulation, approval, publication or materialization, it should be promoted to
`/api/praxis/config/domain-rules/**`.

## Retrieval Rules

The authoring engine may retrieve project knowledge only after all filters below
are applied:

- Tenant and environment must match the current turn.
- Context/resource/page/component scope must be relevant to the current
  authoring intent.
- `lifecycle` must be `active` or an explicitly allowed candidate state for a
  review-only flow.
- `curation_status` must be `approved` for normal authoring influence.
- `ai_visibility` must allow the selected projection: `allow`, `mask` or
  `summarize_only`; `deny` is never injected into LLM context.
- Classification and compliance tags must pass the same redaction discipline as
  existing authoring events.
- Retrieval must return a safe projection with reason, relevance and influence,
  not raw canonical payload by default.

Vector search can rank candidates only after these governance filters define the
search space. A vector hit that fails scope, status or visibility checks must be
discarded.

## Authoring Flow

The first backend integration should remain read-only:

1. Resolve the authoring intent and inspect `currentPage`.
2. Build a governed project-knowledge query from tenant, environment, current
   page, selected resource candidates and intent classification.
3. Retrieve a small allowlisted set of safe knowledge projections.
4. Attach those projections to planner context with provenance and influence
   labels.
5. Emit safe progress and diagnostics through the existing turn event stream.
6. Generate preview or route to domain-rules as before.

The engine must not silently mutate knowledge during a normal authoring turn.
Writes should go through `domain_knowledge_change_set` or a future governed
endpoint that validates proposed operations before applying them.

## UI Contract

The UI should explain influence, not become the memory owner.

Minimum UI behavior:

- Show that project knowledge influenced the result when it did.
- Expose safe labels such as kind, scope, status and source summary.
- Hide raw payloads unless a diagnostic/admin surface explicitly allows them.
- Offer a path to request review or correction, backed by a governed change set.
- Never persist canonical memory in browser `localStorage`, chat transcript or
  Page Builder component state.

## Implementation Slices

1. Done in the Phase 7 read-model slice: add a backend read model/service that
   queries approved Domain Knowledge entries by tenant, environment,
   context/resource and `payload.kind`.
2. Done in the Phase 7 read-model slice: add unit tests for scope filtering,
   status filtering, `ai_visibility` behavior and safe projection redaction.
3. Done in the Phase 7 engine-wiring slice: add an internal authoring-engine
   retrieval step that consumes safe projections without changing public HTTP
   contracts.
4. Done in the Phase 7 engine-wiring slice: emit stream diagnostics that name
   retrieved knowledge by safe identifier, kind, source summary and influence
   classification.
5. Done in the Phase 7 planner-consumption slice: make the planner prompt
   consume `contextHints.projectKnowledge` through an allowlisted safe
   projection and require citation in `sourceRefs` when it materially
   influences a plan.
6. Add UI explanation only after backend diagnostics are stable.
7. Add optional vector ranking as a derived optimization, never as the canonical
   source of truth.

## Acceptance Criteria

- Project knowledge is scoped by tenant/environment and relevant authoring
  context.
- Only governed, approved and AI-visible knowledge can influence normal
  authoring.
- RAG/vector data is a derived retrieval index, not primary memory.
- The authoring stream can explain which safe knowledge influenced a turn.
- The UI can display influence without exposing sensitive payloads by default.
- There is no browser-local canonical memory and no hidden prompt-history
  dependency.

## Stop Conditions

Do not proceed with implementation if the proposed design:

- stores memory as a raw prompt/chat blob;
- uses browser storage or Angular state as canonical memory;
- bypasses `domain_knowledge_change_set` for writes proposed by an LLM;
- injects `ai_visibility = deny` payloads into LLM context;
- treats `vector_store` as the source of truth;
- introduces a public endpoint before the read model and governance tests are
  stable.

## Implemented Foundation

`AgenticAuthoringProjectKnowledgeService` is the first read-only backend
foundation for Phase 7. It returns safe projections from `domain_knowledge_concept`
only when tenant/environment scope, lifecycle, curation status and
`ai_visibility` allow normal authoring influence.

`AgenticAuthoringTurnEngine` now attaches those safe projections to
`contextHints.projectKnowledge` before preview planning and emits safe
`projectKnowledge.retrieve` diagnostics. It still does not mutate knowledge and
does not expose a public HTTP endpoint for project memory.

`AgenticAuthoringPlanService` now includes a dedicated governed project
knowledge block in the MinimalFormPlan prompt. The block is rebuilt from an
allowlist of safe fields and ignores non-canonical fields such as raw source
data. The prompt also instructs the model to cite project knowledge entries in
`sourceRefs` when they materially influence the generated plan.

The next implementation slice is to prove this full path through a local
agentic authoring turn with seeded governed knowledge, then expose the already
safe influence explanation in UI only after the backend behavior is stable.
