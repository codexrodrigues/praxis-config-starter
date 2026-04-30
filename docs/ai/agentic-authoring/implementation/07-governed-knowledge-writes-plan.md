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

- done in the create/list/get HTTP slice: public `POST` and `GET` endpoints for
  creating, listing and reading proposed change sets with scope checks and safe
  responses;
- done in the validator foundation slice: deterministic patch operation schema
  represented by explicit DTOs and validated before any public endpoint exists;
- done in the validator foundation slice: validation service for proposed
  operations before persistence;
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

## Minimal Payload Examples

Create request:

```json
{
  "changeSetKey": "project-knowledge:human-resources.funcionarios:cpf-guidance:v1",
  "status": "proposed",
  "authorType": "llm",
  "authorId": "openai:gpt-5.4",
  "intent": "Improve CPF field guidance for employee registration",
  "reason": "The authoring turn detected that CPF field handling needs explicit LGPD guidance.",
  "patch": [
    {
      "operationId": "op-add-cpf-guidance-evidence",
      "operationType": "add_evidence",
      "target": {
        "subjectType": "concept",
        "conceptKey": "human-resources.funcionarios.field.cpf"
      },
      "reason": "Connect the guidance to reviewed Project Knowledge evidence.",
      "evidenceRefs": [
        "domain-catalog:human-resources:v2026-04-30"
      ],
      "confidence": 0.82,
      "payload": {
        "evidenceKey": "llm-proposal:funcionarios:cpf-guidance:v1",
        "evidenceType": "llm_proposal",
        "sourceUri": "praxis-agentic-authoring://turn/example",
        "sourcePointer": "/projectKnowledge/0",
        "summary": "CPF is personal data and form guidance should explain purpose and minimization."
      }
    }
  ]
}
```

Safe response:

```json
{
  "id": "3b0d6c7a-5cb3-4d25-8a3e-37b5f8ef61e9",
  "changeSetKey": "project-knowledge:human-resources.funcionarios:cpf-guidance:v1",
  "status": "proposed",
  "authorType": "llm",
  "intent": "Improve CPF field guidance for employee registration",
  "operationCount": 1,
  "validationStatus": "pending",
  "safeSummary": {
    "operationTypes": ["add_evidence"],
    "targetConceptKeys": ["human-resources.funcionarios.field.cpf"],
    "requiresReview": true
  }
}
```

The common response should expose safe summaries by default. Raw patch payloads
should be restricted to admin/debug surfaces or explicit detail endpoints.

## Validation Matrix

| Case | Expected result |
| --- | --- |
| Unknown `operationType` | Reject with `unsupported_operation_type`. |
| Missing `reason` | Reject with `missing_reason`. |
| Missing `evidenceRefs` for LLM-authored change | Reject with `missing_evidence`. |
| `authorType=llm` and `status=applied` on create | Reject with `invalid_initial_status`. |
| Target tenant/environment differs from request headers | Reject with `scope_mismatch`. |
| Operation attempts delete/replace in first cut | Reject with `destructive_operation_not_supported`. |
| Raw prompt/chat transcript stored as concept payload | Reject with `raw_prompt_memory_not_allowed`. |
| `ai_visibility=allow` proposed from unsafe/private evidence | Reject or downgrade to review-required visibility. |
| Duplicate alias/binding with compatible existing target | Reuse or report idempotent no-op. |
| Duplicate stable key with incompatible target | Reject with `stable_key_conflict`. |

## Proposed API Shape

The domain-catalog v1 document already recommends these endpoints. This cut
should implement the smallest safe subset:

- done in the create/list/get HTTP slice:
  `POST /api/praxis/config/domain-knowledge/change-sets`;
- deferred:
  `POST /api/praxis/config/domain-knowledge/change-sets/{id}/validate`;
- deferred:
  `PATCH /api/praxis/config/domain-knowledge/change-sets/{id}/status`;
- deferred:
  `POST /api/praxis/config/domain-knowledge/change-sets/{id}/apply`;
- done in the create/list/get HTTP slice:
  `GET /api/praxis/config/domain-knowledge/change-sets`;
- done in the create/list/get HTTP slice:
  `GET /api/praxis/config/domain-knowledge/change-sets/{id}`.

If we need to reduce scope further, start with create, validate, list and get.
Do not implement apply before validation and approval semantics are covered by
tests.

## DTO Shape

Suggested request DTOs:

- `DomainKnowledgeChangeSetCreateRequest`
- `DomainKnowledgeChangeSetStatusRequest`
- `DomainKnowledgeChangeSetValidationRequest`
- `DomainKnowledgeChangeSetApplyRequest`

Suggested response DTOs:

- `DomainKnowledgeChangeSetResponse`
- `DomainKnowledgeChangeSetOperationSummary`
- `DomainKnowledgeChangeSetValidationResponse`
- `DomainKnowledgeChangeSetApplyResponse`

Keep DTOs explicit. Do not expose the JPA entity directly through HTTP.

## Idempotency

Use `tenantId + environment + changeSetKey` as the stable create key. Repeated
create requests with the same key should be safe only when the normalized patch
hash, author metadata and target scope are compatible.

On retry:

- return the existing change set when the semantic patch is equivalent;
- reject when the same key points to a different operation set;
- never create a second row for the same stable key in the same scope.

The implementation can start with a deterministic normalized JSON hash stored
inside `validation_result` if adding a database column is not justified in the
first slice. If the hash becomes core lifecycle state, promote it to a real
column in a later migration.

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

## Definition Of Done By Slice

| Slice | Done when |
| --- | --- |
| DTO + validator | Allowed/rejected operations are covered by focal unit tests and no endpoint exists yet. |
| Create/list/get | Proposed change sets persist with tenant/environment scope and safe responses. |
| Validate | Validation results are deterministic, persisted and do not require an LLM call. |
| Status transitions | Invalid transitions are blocked and reviewer metadata is captured. |
| Apply first operation | `add_alias` plus `add_evidence` applies transactionally only after approval. |
| Quickstart corpus | Neon-backed HTTP flow proves create -> validate -> approve -> apply -> readback. |
| Page Builder cockpit | UI creates proposals but cannot apply them silently during normal authoring. |
| Browser E2E | Real browser proves no silent mutation before approval and citation after apply. |

## PR And Merge Strategy

Use small implementation PRs, but avoid opening remote gates for each one.

Recommended sequence:

1. local branch with DTO/validator/service tests;
2. local focal Maven validation;
3. PR without relying on Actions for exploratory feedback;
4. merge only after enough related slices are batched to justify one `main`
   gate, or after a slice changes public contract and needs a phase checkpoint.

Do not publish Maven/npm until a named downstream consumer needs the completed
lifecycle from public artifacts.

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

1. Done in the validator foundation slice: DTOs, deterministic validator and
   focal service tests for the patch operation contract, without controller or
   persistence orchestration.
2. Done in the service foundation slice: create/list/get service methods for
   proposed change sets, including idempotent retry, semantic conflict
   detection, tenant/environment scope checks and safe response projection.
3. Done in the create/list/get HTTP slice: public create/list/get endpoints
   delegate to the service and preserve safe responses. Explicit validation,
   status transitions and apply are still intentionally deferred.
4. Validation endpoint and validation result persistence.
5. Approval/rejection status transitions.
6. Apply endpoint for one narrow operation, preferably `add_alias` plus
   `add_evidence`.
7. Quickstart HTTP corpus proving the lifecycle with Neon.
8. Page Builder cockpit actions for proposal, review and explicit apply.
9. Browser E2E proving no silent mutation and post-apply influence citation.

## Publication Decision

No Maven Central or npm publication should be triggered for planning or early
implementation slices.

Use local tests and Neon-backed quickstart proof during development. Use
GitHub Actions only as a phase-closing gate when the lifecycle is complete and
a downstream consumer needs the public artifact.
