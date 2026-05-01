# Domain Knowledge Revert Phase

Status: implementation planning
Date: 2026-05-01
Scope: post Page Builder continuity hardening for governed Project Knowledge writes

## Recommendation

The next functional phase should plan and implement governed revert semantics
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

That is enough for additive governed writes, but not enough for correction,
rollback or broader operation types. Before the platform lets AI author
stronger mutations, it must answer:

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
- `DomainKnowledgeChangeSetValidator` accepts additive operation types and
  blocks destructive operation types in this cut.
- `DomainKnowledgeChangeSetService.apply` applies only `add_evidence`.
- `DomainKnowledgeEvidence` stores governed evidence rows but currently has no
  explicit lifecycle status, supersession pointer or reverted timestamp.
- `GET /api/praxis/config/domain-knowledge/change-sets/{id}/timeline` emits a
  safe derived timeline for creation, validation, review and application.

Conclusion: the next phase needs a canonical lifecycle extension, not a UI-only
delete button and not a raw patch mutation.

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
