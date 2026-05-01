# Domain Knowledge Revert Phase

Status: local-first baseline implemented
Date: 2026-05-01
Scope: post Page Builder continuity hardening for governed Project Knowledge writes

## Recommendation

The next functional phase has implemented the first governed revert baseline
for Domain Knowledge evidence before adding broader write operation types.

Do not start by adding destructive operations such as `delete_evidence`,
`delete_concept`, `replace_payload` or bulk rollback. The platform-correct next
step is to model reversibility as a governed semantic decision with audit,
validation, approval and safe timeline proof.

## Why This Is The Next Phase

The current beta path proves a safe write lifecycle:

1. Page Builder proposes Project Knowledge as a Domain Knowledge change set.
2. Backend validation accepts only safe `add_evidence` operations.
3. A reviewer explicitly approves the proposal.
4. The change set applies evidence to an existing governed concept.
5. The cockpit reads back safe status and safe timeline body evidence.

That was enough for additive governed writes, but not enough for correction,
rollback or broader operation types. The current baseline now answers the first
required questions for beta rollback:

- how an applied evidence row is superseded without deleting audit history;
- how a revert is reviewed and applied;
- how timeline events prove reversal without exposing raw evidence payloads;
- how Project Knowledge influence stops using reverted evidence;
- how Page Builder presents revert opportunity without becoming the source of
  memory or business-rule truth.

## Current Canonical Baseline

The current implementation owns the lifecycle in `praxis-config-starter`:

- `DomainKnowledgeChangeSet` stores status, reviewer, patch, validation result
  and timestamps.
- `DomainKnowledgeChangeSetValidator` accepts `add_evidence` and
  `revert_evidence`, while destructive operation types remain blocked in this
  cut.
- `DomainKnowledgeChangeSetService.apply` applies additive evidence and
  governed evidence lifecycle transitions transactionally.
- `DomainKnowledgeEvidence` stores governed evidence rows with explicit
  lifecycle status, supersession pointer, revert change-set id, reverted
  timestamp and revert reason.
- `GET /api/praxis/config/domain-knowledge/change-sets/{id}/timeline` emits a
  safe derived timeline for creation, validation, review, application and
  evidence revert/supersede events.
- `AgenticAuthoringProjectKnowledgeService` only exposes governed Project
  Knowledge when the concept still has active evidence in the same
  tenant/environment.

Conclusion: the baseline now supports governed revert/supersede as the
platform-correct correction model. The next step is checkpoint/readiness and
only then a separately planned expansion for any broader write operation. Do
not add a UI-only delete button or raw patch mutation.

## Slice 1 Inventory Findings - 2026-05-01

The first inventory pass confirms that revert must start in the canonical
backend model, not in Page Builder state.

Code owners inspected:

- `DomainKnowledgeEvidence`
- `DomainKnowledgeEvidenceRepository`
- `DomainKnowledgeChangeSetService`
- `DomainKnowledgeProjectionService`
- `AgenticAuthoringProjectKnowledgeService`
- focused tests around change-set apply, projection and entity lifecycle

Evidence model at inventory time:

- `DomainKnowledgeEvidence` stores tenant, environment, subject scope,
  `evidenceKey`, `evidenceType`, `confidence`, payload, provenance and
  timestamps.
- It had no explicit lifecycle fields at that point: no status, supersession
  pointer, revert change-set id, reverted timestamp or revert reason. Slice 2
  closed this gap with canonical lifecycle columns.

Current repository retrieval:

- `findByTenantIdAndEnvironmentAndSubjectTypeAndSubjectId(...)`
- `findByTenantIdAndEnvironmentAndEvidenceKey(...)`
- `findByTenantIdAndEnvironmentAndEvidenceKeyIn(...)`

Current write paths:

- `DomainKnowledgeProjectionService.projectEvidence(...)` upserts evidence
  from catalog releases through `findByTenantIdAndEnvironmentAndEvidenceKeyIn`
  and `saveAll`.
- `DomainKnowledgeChangeSetService.apply(...)` currently applies
  `add_evidence` only.
- `DomainKnowledgeChangeSetService.applyEvidenceOperation(...)` creates or
  reuses an evidence row and persists it through `evidenceRepository.save`.
- `DomainKnowledgeChangeSetValidator` blocks destructive operations such as
  `delete_concept`, `delete_alias`, `delete_binding`, `delete_relationship`,
  `delete_evidence`, `replace_concept` and `replace_payload`.

Retrieval and influence gap at inventory time:

- `AgenticAuthoringProjectKnowledgeService` retrieved governed project
  knowledge candidates from concepts, not from evidence lifecycle-aware
  repository queries.
- The Page Builder proof read back a safe concept/timeline projection, but did
  not yet prove that reverted evidence stopped influencing later AI authoring.
- Slices 7, 8 and 9 closed this gap with active-evidence retrieval filtering,
  HTTP runtime proof and browser proof.

Recommended first data-model decision:

- Add lifecycle columns directly to `domain_knowledge_evidence` for this beta
  phase: `status`, `superseded_by_evidence_id`,
  `reverted_by_change_set_id`, `reverted_at` and `revert_reason`.
- Default existing rows to `active`.
- Add repository methods that retrieve only active evidence by default where
  evidence participates in runtime or AI-authoring decisions.

Implementation boundary confirmed:

- Do not promote `delete_*` operations.
- Do not add Page Builder revert buttons before backend lifecycle semantics are
  stable.
- Do not add a special evidence `DELETE` endpoint.
- Do not rely on GitHub Actions for exploratory validation.
- Do not treat raw evidence payload, source pointer, source URI, prompt or chat
  history as safe timeline/readback content.

## Slice 2 Lifecycle Baseline - 2026-05-01

The first implementation slice establishes evidence lifecycle state without
making revert executable yet.

Implemented boundary:

- `domain_knowledge_evidence.status` defaults to `active`.
- `superseded_by_evidence_id`, `reverted_by_change_set_id`, `reverted_at` and
  `revert_reason` exist as canonical lifecycle fields.
- Repository methods can now query active evidence explicitly by subject,
  evidence key or evidence-key set.

Still intentionally pending:

- `revert_evidence` validation.
- transactional apply of revert/supersede.
- authoring retrieval changes that exclude reverted evidence by default.
- Page Builder cockpit actions.

## Slice 3 Validation Baseline - 2026-05-01

The validator now recognizes `revert_evidence` as a governed lifecycle
operation, not as a destructive delete.

Validation boundary:

- LLM-authored `revert_evidence` still requires `evidenceRefs`.
- `target.conceptKey` is required.
- `payload.evidenceKey` is required.
- `payload.revertReason` is required.
- raw prompt/chat/transcript payload fields remain blocked.
- `delete_*` and `replace_*` operations remain blocked.

Still intentionally pending:

- repository-backed checks that the evidence exists in the same
  tenant/environment.
- checks that the evidence belongs to the target concept.
- checks that the evidence is currently `active`.
- transactional apply that marks evidence as `reverted` or `superseded`.
- runtime/authoring retrieval filtering.

## Slice 4 Apply Baseline - 2026-05-01

`revert_evidence` now applies as a governed lifecycle transition.

Apply boundary:

- target concept must exist in the change-set tenant/environment.
- target evidence must exist in the same tenant/environment.
- target evidence must belong to the target concept.
- target evidence must be `active`.
- optional `replacementEvidenceKey`, when present, must resolve to active
  evidence for the same target concept.
- apply marks the target evidence as `reverted`, records
  `reverted_by_change_set_id`, `reverted_at`, `revert_reason` and optional
  `superseded_by_evidence_id`.
- no evidence row is physically deleted.

Still intentionally pending:

- safe timeline event types dedicated to `evidence.reverted` and
  `evidence.superseded`.
- runtime/authoring retrieval filtering that excludes reverted evidence by
  default.
- protected HTTP/browser proof of create -> validate -> approve -> apply ->
  timeline for revert.

## Slice 5 Timeline Baseline - 2026-05-01

Applied `revert_evidence` change sets now emit safe lifecycle timeline events.

Timeline boundary:

- `evidence.reverted` is emitted after `change_set.applied` for applied
  revert operations.
- `evidence.superseded` is emitted when the revert operation references a
  governed replacement evidence key.
- timeline events expose operation type, target concept keys, status,
  validation status and safe summary only.
- timeline events do not expose evidence keys, replacement evidence keys,
  source pointer, source URI, patch hash, prompt, transcript or raw payload.

Still intentionally pending:

- runtime/browser proof that a reverted evidence row no longer reaches
  authoring retrieval after apply.
- protected HTTP/browser proof of create -> validate -> approve -> apply ->
  timeline for revert.

## Slice 6 HTTP Smoke Preparation - 2026-05-01

The quickstart runtime smoke now has an explicit revert gate:

```bash
REQUIRE_CHANGE_SET_TIMELINE=true \
REQUIRE_EVIDENCE_REVERT=true \
tools/local-e2e/run-domain-knowledge-change-set-local.sh
```

Smoke boundary:

- The baseline `add_evidence` flow remains compatible by default.
- `REQUIRE_EVIDENCE_REVERT=true` creates a second governed change set with
  `revert_evidence` for the evidence created by the smoke itself.
- The revert flow revalidates, approves, applies, reads back and validates
  timeline event `evidence.reverted`.
- The smoke still delegates through `praxis-api-quickstart`, the operational
  reference host.

Neon-backed proof completed on 2026-05-01 against a quickstart packaged with
the local starter cut:

```bash
BASE_URL=http://localhost:8099 \
TENANT_ID=desenv \
ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
REQUIRE_EVIDENCE_REVERT=true \
tools/local-e2e/run-domain-knowledge-change-set-local.sh
```

Observed proof:

- baseline change set `622d5fb8-727a-49a5-b8e2-f684f790ace8` returned timeline
  `eventCount=4`.
- revert change set `e8861241-fbd8-48f9-9571-35dec841bca5` returned timeline
  `eventCount=5`.
- final status was `domain-knowledge-change-set-runtime-ready` with
  `revertChecked=true`.

Still intentionally pending:

- browser proof that Page Builder never offers reverted evidence as active
  Project Knowledge.

## Slice 7 Authoring Retrieval Baseline - 2026-05-01

Project Knowledge retrieval for agentic authoring now requires active evidence.

Retrieval boundary:

- `AgenticAuthoringProjectKnowledgeService` still starts from governed concept
  candidates: `lifecycle=active`, `curationStatus=approved` and LLM-safe
  `aiVisibility`.
- before projecting a concept into the authoring context, the service requires
  at least one `domain_knowledge_evidence` row for the same
  tenant/environment, `subjectType=concept`, matching concept id and
  `status=active`.
- concepts whose evidence has only been reverted or superseded are not exposed
  as Project Knowledge context for later AI authoring turns.
- safe projection evidence now includes
  `domain-knowledge:evidence-status:active` so downstream diagnostics can show
  that the context came from active governed evidence without exposing evidence
  keys or raw payload.

Still intentionally pending:

- Page Builder browser proof that reverted evidence is not offered as active
  continuity context.

## Slice 8 Runtime Retrieval Proof - 2026-05-01

The quickstart now proves active-evidence retrieval by HTTP real.

Command:

```bash
BACKEND_URL=http://localhost:8099 \
TENANT_ID=desenv \
ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
REQUIRE_EVIDENCE_REVERT=true \
REQUIRE_PROJECT_KNOWLEDGE_RETRIEVAL=true \
AUTHORING_STREAM_MAX_TIME=180 \
scripts/verify-domain-knowledge-change-set-runtime.sh
```

Observed proof:

- add change set `191e9f50-d840-4f44-ac9c-30e0a40014b4` applied active
  evidence to Project Knowledge concept
  `page-builder.e2e.project-knowledge.identity-card`.
- authoring retrieval after add returned `expected=present` and
  `retrievalCount=2`.
- revert change set `1db47667-ce1c-462a-8e42-ce1806782038` applied
  `revert_evidence` to the same smoke evidence.
- authoring retrieval after revert returned `expected=absent` and
  `retrievalCount=0`.
- final status was `domain-knowledge-change-set-runtime-ready` with
  `revertChecked=true` and `projectKnowledgeRetrievalChecked=true`.

Still intentionally pending:

- Page Builder browser proof that reverted evidence is not offered as active
  continuity context.

## Slice 9 Browser Retrieval Proof - 2026-05-01

The Page Builder local browser lane now proves the active-evidence lifecycle in
the actual cockpit/authoring surface.

Command:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-ui-angular

AI_PROVIDER=openai \
AI_ENV_FILE=../praxis-config-starter/.env.openai.local.sh \
PRAXIS_E2E_TIMEOUT_MS=900000 \
./tools/local-e2e/run-project-knowledge-audit-cockpit-local.sh
```

Observed proof:

- managed runner installed the local `praxis-config-starter`, started isolated
  quickstart/UI services on `8098`/`4083`, ran the Project Knowledge browser
  lane and cleaned both ports.
- first browser turn confirmed the seeded Project Knowledge fixture was present
  in the Page Builder `projectKnowledgeAudit` while evidence was `active`.
- the test created, validated, approved and applied a governed
  `revert_evidence` change set through the canonical Domain Knowledge boundary.
- second browser turn confirmed the same concept key was absent from the
  authoring audit after revert.
- the cockpit proof still validates safe governed actions, readback and
  timeline without rendering raw concept keys, source summaries, source
  pointers, patch hashes, assistant messages or materialized payloads.
- result: `2 passed (1.3m)`.

Baseline status:

- runtime/HTTP proof and browser proof are complete for the current beta
  checkpoint.
- no npm/Maven publication and no GitHub Actions were required.
- destructive `delete_*` and broad `replace_*` operation types remain blocked.

## Canonical Direction

Model rollback as **revert/supersede**, not deletion.

Recommended semantics:

- Keep original evidence rows immutable for audit.
- Add lifecycle state to evidence or an equivalent canonical projection, such
  as `active`, `superseded`, `reverted`.
- Record the change set that superseded or reverted a row.
- Make revert itself a new governed change set operation.
- Require deterministic validation and explicit approval before apply.
- Keep safe timeline events derived from persisted lifecycle state.
- Make retrieval exclude reverted evidence by default.
- Allow audit views to include reverted evidence only as safe status/provenance,
  never raw payload.

## Proposed Operation Model

Start with one operation:

- `revert_evidence`

Minimum target:

```json
{
  "tenantId": "desenv",
  "environment": "local",
  "conceptKey": "human-resources.funcionarios.identity-card",
  "evidenceKey": "llm-proposal:funcionarios:identity-card:v1"
}
```

Minimum payload:

```json
{
  "revertReason": "The preference was superseded by a reviewed accessibility guideline.",
  "replacementEvidenceKey": "llm-proposal:funcionarios:identity-card:v2",
  "visibilityAfterRevert": "deny"
}
```

Rules:

- `revertReason` is required.
- target concept and evidence must exist in the same tenant/environment.
- evidence must belong to the target concept.
- evidence must be currently active.
- replacement evidence is optional, but if present it must exist or be created
  by another operation in the same governed change set.
- LLM-authored revert requires evidence references and confidence.
- revert cannot physically delete rows.

## Data Model Options

Preferred option for the first implementation:

- Add lifecycle fields to `domain_knowledge_evidence`:
  - `status`
  - `superseded_by_evidence_id`
  - `reverted_by_change_set_id`
  - `reverted_at`
  - `revert_reason`

Why:

- keeps retrieval filtering simple;
- preserves original evidence rows;
- lets timeline derive status without inspecting historical patch payloads;
- avoids inventing a second audit table before the lifecycle is proven.

Alternative option:

- Add an evidence lifecycle event table.

Use this only if multiple future transitions need append-only event sourcing
before retrieval can be filtered safely. It is more flexible, but heavier than
the first beta hardening step needs.

## API Boundary

Do not add a special `DELETE /evidence` endpoint for this phase.

The first implementation should continue through the existing change-set
boundary:

- `POST /api/praxis/config/domain-knowledge/change-sets`
- `POST /api/praxis/config/domain-knowledge/change-sets/{id}/validate`
- `PATCH /api/praxis/config/domain-knowledge/change-sets/{id}/status`
- `POST /api/praxis/config/domain-knowledge/change-sets/{id}/apply`
- `GET /api/praxis/config/domain-knowledge/change-sets/{id}/timeline`

OpenAPI and public contracts should be updated only after the runtime behavior
and tests are stable.

## Timeline Semantics

Safe timeline should add events such as:

- `evidence.revert_requested`
- `evidence.revert_validated`
- `evidence.reverted`
- `evidence.superseded`

Safe timeline may expose:

- event type;
- status;
- validation status;
- operation count;
- operation types;
- governed target count;
- actor type and safe actor id;
- timestamps.

Safe timeline must not expose:

- raw evidence payload;
- `sourcePointer`;
- `sourceUri`;
- `patchHash`;
- prompt;
- chat history;
- unrestricted evidence summary.

## Page Builder Role

Page Builder may:

- show that applied Project Knowledge evidence can be reverted through a
  governed change set;
- expose a safe `Request revert` or `Open revert proposal` action once the
  backend contract exists;
- show safe timeline/readback status.

Page Builder must not:

- delete evidence locally;
- mark memory as reverted in component state;
- decide retrieval eligibility;
- apply a revert without explicit backend approval/apply.

## Implementation Slices

### Slice 1. Contract Inventory

Deliverables:

- map current evidence repository queries and retrieval paths;
- identify all places that should exclude reverted evidence by default;
- confirm whether the first cut needs evidence lifecycle columns or a separate
  lifecycle event table.

Definition of done:

- no code change starts from guessed lifecycle fields;
- retrieval consumers are named before schema changes.

### Slice 2. Evidence Lifecycle Model

Deliverables:

- migration for lifecycle fields or lifecycle table;
- entity/repository updates;
- default status for existing evidence rows.

Definition of done:

- existing applied evidence remains active;
- no existing row is deleted or rewritten outside the explicit migration.

### Slice 3. Revert Validation

Deliverables:

- validator support for `revert_evidence`;
- deterministic errors for missing concept, missing evidence, wrong scope,
  already reverted evidence and missing reason/evidenceRefs.

Definition of done:

- destructive operations remain blocked;
- revert is validated as a governed lifecycle transition, not deletion.

### Slice 4. Apply Revert

Deliverables:

- `DomainKnowledgeChangeSetService.apply` handles `revert_evidence`;
- application marks evidence as reverted/superseded transactionally;
- applying the same change set is idempotent.

Definition of done:

- applied revert cannot be re-applied with conflicting state;
- retrieval excludes reverted evidence by default.

### Slice 5. Safe Timeline And Readback

Deliverables:

- timeline exposes safe revert/supersede events;
- readback summarizes lifecycle without raw payload;
- protected HTTP proof covers create -> validate -> approve -> apply ->
  timeline.

Definition of done:

- safe response contains no raw evidence text, source pointer, source URI,
  patch hash, prompt or chat history.

### Slice 6. Browser Cockpit Proof

Deliverables:

- Page Builder or a focused cockpit lane proves a governed revert action only
  after backend semantics are stable.

Definition of done:

- browser proof shows no local deletion;
- action is blocked until backend validation/approval conditions are met;
- services are cleaned up by local runner.

Status:

- complete for the current beta checkpoint via the local Project Knowledge
  cockpit lane on 2026-05-01.

## Local-First Validation

Expected backend focal tests:

```bash
mvn -q -Dtest=DomainKnowledgeChangeSetServiceTest,DomainKnowledgeChangeSetControllerTest,DomainKnowledgeChangeSetValidatorTest test
```

Expected protected HTTP proof after implementation:

```bash
REQUIRE_CHANGE_SET_TIMELINE=true \
./tools/local-e2e/run-domain-knowledge-change-set-local.sh
```

Add or adjust the exact runner only after inspecting current scripts. Do not
create ad hoc one-off scripts outside the existing local E2E structure.

Use GitHub Actions only as a release/phase gate or hosted smoke after local
proof is green.

## Stop Conditions

Pause and redesign if an implementation:

- physically deletes Domain Knowledge evidence;
- lets Page Builder mark evidence as reverted without backend lifecycle state;
- promotes `delete_*` operation types before revert semantics are proven;
- exposes raw evidence payload, source pointer, prompt or chat history in a
  common safe response;
- requires npm/Maven publication for exploratory feedback;
- depends on GitHub Actions for debugging behavior reproducible locally.
