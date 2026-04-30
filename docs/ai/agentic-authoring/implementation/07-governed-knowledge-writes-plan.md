# Governed Knowledge Writes Plan

Status: implementation planning
Date: 2026-04-30
Scope: post-Phase 7 agentic authoring capability cut

## Recommendation

The next capability cut should be governed LLM-authored Project Knowledge
writes, but only as proposed change sets. A normal authoring turn must still
remain unable to silently mutate canonical knowledge.

The canonical boundary is the existing `domain_knowledge_change_set` table and
`DomainKnowledgeChangeSet` entity. Do not create browser-local memory, Page
Builder state memory, chat-history memory or a parallel "project memory" store.

## Why This Comes Before Vector/RAG Ranking

Vector/RAG ranking can improve retrieval, but it is a derived optimization. The
platform first needs a safe way for AI to propose corrections or additions to
canonical knowledge without bypassing governance.

This cut improves the platform by allowing AI to author semantic knowledge
proposals while preserving:

- human or platform approval before application;
- deterministic validation before persistence changes;
- rollback/audit visibility;
- source/provenance requirements;
- explicit separation between proposal, approval and application.

## Existing Foundation To Reuse

Already present in `praxis-config-starter`:

- `domain_knowledge_change_set` in Flyway V18;
- `DomainKnowledgeChangeSet` JPA entity;
- `DomainKnowledgeChangeSetRepository`;
- domain knowledge concepts, aliases, bindings, relationships and evidence;
- `domain_knowledge_evidence.evidence_type = llm_proposal`;
- domain rules already referencing `source_change_set_id`;
- Phase 7 read-only Project Knowledge retrieval and safe audit diagnostics.

Missing before implementation:

- public or internal service for creating change sets;
- deterministic patch operation schema;
- validation service for proposed operations;
- approval/rejection transitions;
- application service that mutates curated knowledge rows transactionally;
- audit/timeline evidence for proposed, approved, rejected and applied changes;
- browser E2E proving authoring cannot silently apply memory writes.

## Canonical Lifecycle

Use the existing statuses:

1. `draft`: created but not submitted for review.
2. `proposed`: submitted by AI/human/system for validation and review.
3. `approved`: reviewer or policy approved the change set.
4. `rejected`: reviewer or policy rejected it.
5. `applied`: approved change set was transactionally applied to curated
   knowledge rows.
6. `superseded`: replaced by a newer change set before application.

The default AI path should create `proposed`, not `approved` or `applied`.

## Patch Operation Contract

Start with a small allowlisted operation set. Avoid arbitrary JSON Patch as the
public contract because it can encode structural mutations that are hard to
govern semantically.

Initial operations:

- `create_concept`
- `update_concept_summary`
- `set_concept_visibility`
- `add_alias`
- `add_binding`
- `add_relationship`
- `add_evidence`

Each operation must include:

- `operationId`
- `operationType`
- `target`
- `reason`
- `evidenceRefs`
- `confidence`
- `payload`

Validation must reject:

- unknown operation types;
- missing reason or evidence;
- payloads that attempt to write raw prompt/chat history as knowledge;
- writes to `ai_visibility=allow` when source evidence is unsafe;
- destructive operations in the first cut;
- cross-tenant or cross-environment targets;
- operations that would create duplicate stable keys without explicit reuse.

## Proposed API Shape

The domain-catalog v1 document already recommends these endpoints. This cut
should implement the smallest safe subset:

- `POST /api/praxis/config/domain-knowledge/change-sets`
- `POST /api/praxis/config/domain-knowledge/change-sets/{id}/validate`
- `PATCH /api/praxis/config/domain-knowledge/change-sets/{id}/status`
- `POST /api/praxis/config/domain-knowledge/change-sets/{id}/apply`
- `GET /api/praxis/config/domain-knowledge/change-sets`
- `GET /api/praxis/config/domain-knowledge/change-sets/{id}`

If we need to reduce scope further, start with create, validate, list and get.
Do not implement apply before validation and approval semantics are covered by
tests.

## Service Boundaries

Recommended classes:

- `DomainKnowledgeChangeSetController`: HTTP boundary under
  `/api/praxis/config/domain-knowledge/change-sets`.
- `DomainKnowledgeChangeSetService`: lifecycle orchestration, idempotency,
  scope checks and status transitions.
- `DomainKnowledgeChangeSetValidator`: deterministic patch operation
  validation.
- `DomainKnowledgePatchApplier`: transactional application to concept, alias,
  binding, relationship and evidence repositories.
- DTOs under `org.praxisplatform.config.dto` for request/response bodies.

Do not put these responsibilities into `AgenticAuthoringTurnEngine`. The engine
may propose a change set later, but governance belongs to the domain knowledge
boundary.

## Agentic Authoring Integration

First integration should be proposal-only:

1. Agentic authoring detects a knowledge gap or correction opportunity.
2. The engine emits a safe diagnostic that a governed correction can be
   proposed.
3. A user or cockpit action asks to create a change set.
4. The backend creates a `domain_knowledge_change_set` with
   `author_type=llm`, source provenance and `status=proposed`.
5. The UI shows the proposed change set and validation result.
6. Review/apply remains explicit.

Do not auto-apply during preview generation, repair loops or normal Page
Builder turns.

## Audit And Provenance

Every LLM-authored change set must carry:

- `author_type=llm`;
- `author_id` identifying model/provider or platform agent identity when
  available;
- `intent`;
- `reason`;
- operation-level evidence references;
- validation result;
- reviewer identity before approval;
- timestamps for review and application.

Application should also create `domain_knowledge_evidence` rows with
`evidence_type=llm_proposal` where appropriate.

## UI Expectations

Page Builder or future cockpit UI may:

- show a safe summary of proposed changes;
- show validation failures and missing evidence;
- request approval/rejection;
- request application after approval;
- link resulting knowledge back to the authoring turn.

UI must not:

- store canonical memory locally;
- bypass backend validation;
- hide rejected/failed proposal status;
- display raw payloads by default in the common cockpit.

## Local-First Test Plan

Backend focal tests:

- entity defaults already covered by `DomainKnowledgeEntityLifecycleTest`;
- service test for create/list/get with tenant/environment scoping;
- validator test for allowed operations and rejection cases;
- lifecycle test for proposed -> approved/rejected/applied transitions;
- apply test proving curated rows are changed transactionally;
- negative test proving apply before approval is blocked.

Integration/API tests:

- create a proposed LLM-authored change set;
- validate it;
- reject invalid operation types;
- approve then apply a safe alias/evidence proposal;
- verify denied or unsafe evidence cannot become AI-visible knowledge.

Browser E2E:

- Page Builder can surface a proposal opportunity;
- creating a proposal does not mutate runtime Project Knowledge immediately;
- cockpit shows validation status and requires explicit approval/apply;
- after apply, a subsequent authoring turn can cite the newly curated safe
  knowledge.

## Stop Conditions

Pause implementation if the design:

- applies LLM-authored changes during normal authoring without explicit review;
- introduces arbitrary JSON Patch as an unchecked public mutation format;
- stores prompt/chat history as canonical knowledge;
- treats vector/RAG as the write target;
- omits tenant/environment scoping;
- lacks deterministic validation before application;
- makes UI the source of approval or memory state.

## Suggested Slices

1. DTOs, validator and service tests for the patch operation contract.
2. Create/list/get endpoints for proposed change sets.
3. Validation endpoint and validation result persistence.
4. Approval/rejection status transitions.
5. Apply endpoint for one narrow operation, preferably `add_alias` plus
   `add_evidence`.
6. Quickstart HTTP corpus proving the lifecycle with Neon.
7. Page Builder cockpit actions for proposal, review and explicit apply.
8. Browser E2E proving no silent mutation and post-apply influence citation.

## Publication Decision

No Maven Central or npm publication should be triggered for planning or early
implementation slices.

Use local tests and Neon-backed quickstart proof during development. Use
GitHub Actions only as a phase-closing gate when the lifecycle is complete and
a downstream consumer needs the public artifact.
