# Domain Knowledge Supersede Evidence Plan

Status: Slice 2 source-level hardening implemented
Date: 2026-05-01
Scope: next functional slice after governed Domain Knowledge revert readiness

## Classification

- Change class: `arquitetural` when implemented, because it may alter public AI
  contract semantics and Page Builder cockpit actions.
- Current document change: `docs-apenas`.
- Canonical owner: `praxis-config-starter`.
- Runtime proof owners: `praxis-api-quickstart` and `praxis-ui-angular`.
- Derived corpus owner: `praxisui-http-examples`.

## Recommendation

Do not start by adding a new public `supersede_evidence` operation.

Start with a narrow source-level slice that decides whether the existing
`revert_evidence + replacementEvidenceKey` semantics are sufficient for beta or
whether a first-class `supersede_evidence` operation materially improves AI
authoring, UX clarity and governance.

The recommended direction is:

1. keep destructive `delete_*` and broad `replace_*` operations blocked;
2. preserve `revert_evidence` as the canonical correction operation for
   removing influence from an evidence row;
3. introduce `supersede_evidence` only if the platform needs an explicit
   semantic distinction for "replace influence with a reviewed successor";
4. if introduced, make `supersede_evidence` a governed change-set operation
   that composes "add/resolve successor evidence" plus "mark prior evidence as
   superseded", never a raw payload overwrite.

## Slice 1 Decision - 2026-05-01

Decision: do not promote `supersede_evidence` to the public OpenAPI contract or
Page Builder cockpit yet.

The source-level inventory found that the platform already has enough
supersession primitives to avoid a new public operation in this beta cut:

- `domain_knowledge_evidence.status` already allows `active`, `superseded` and
  `reverted`;
- `DomainKnowledgeEvidence.supersededByEvidenceId` already records the
  replacement edge;
- `DomainKnowledgeChangeSetService.applyRevertEvidenceOperation(...)` already
  accepts `payload.replacementEvidenceKey`;
- `GET /domain-knowledge/change-sets/{id}/timeline` can already emit
  `evidence.superseded`;
- `AgenticAuthoringProjectKnowledgeService` already gates influence on active
  evidence only.

The inventory also found one semantic mismatch to harden before any public
operation expansion:

- when `revert_evidence` carries `replacementEvidenceKey`, the service records
  `supersededByEvidenceId`, but still sets the prior evidence status to
  `reverted`;
- the timeline then emits both `evidence.reverted` and `evidence.superseded`;
- this is safe, but semantically blurry: replacement intent should be modeled
  as supersession internally before the platform considers exposing a
  first-class `supersede_evidence` authoring operation.

Recommended next source-level slice:

1. keep public operation types unchanged: `add_evidence` and
   `revert_evidence`;
2. change only internal lifecycle semantics for `revert_evidence` with
   `replacementEvidenceKey`, so the prior evidence becomes `superseded`;
3. keep plain `revert_evidence` without replacement as `reverted`;
4. keep retrieval behavior unchanged because both `reverted` and `superseded`
   are already excluded from active Project Knowledge influence;
5. update focused service tests and safe timeline expectations;
6. do not touch OpenAPI, generated bindings, Angular cockpit or HTTP corpus
   until this source-level behavior is proven.

This preserves beta contract stability while making the canonical lifecycle
state match the semantic decision.

Slice 2 implementation result:

- completed on 2026-05-01;
- `revert_evidence` without `replacementEvidenceKey` still marks evidence as
  `reverted`;
- `revert_evidence` with `replacementEvidenceKey` now marks prior evidence as
  `superseded`;
- safe timeline emits `evidence.reverted` for plain revert and
  `evidence.superseded` for replacement-backed supersession;
- no OpenAPI, generated binding, Angular, quickstart or HTTP corpus changes
  were made in this source-level hardening patch.

## Why This Is The Next Slice

The previous checkpoints closed:

- active-evidence filtering for Project Knowledge retrieval;
- governed `revert_evidence` validation and apply;
- safe `evidence.reverted` and `evidence.superseded` timeline projection;
- browser proof that reverted evidence stops influencing later authoring;
- OpenAPI/binding/corpus synchronization without promoting mutating examples
  to `llmOperational`.

The remaining ambiguity is semantic, not infrastructural:

- Is supersession just a revert reason plus replacement evidence?
- Or should AI/human authoring see a first-class "supersede this evidence with
  that evidence" action?

This matters because Praxis is a platform of AI-authored semantic decisions,
not a patch editor. If the user intent is replacement of influence, the model
should express that intent canonically instead of smuggling it through a
generic revert payload forever.

## Current Baseline

Current backend behavior already has partial supersession support:

- `DomainKnowledgeEvidence` has `superseded_by_evidence_id`.
- `DomainKnowledgeChangeSetService.applyRevertEvidenceOperation(...)` accepts
  `payload.replacementEvidenceKey`.
- When replacement evidence is present and active for the same concept, apply
  records `superseded_by_evidence_id`.
- Safe timeline can emit `evidence.superseded`.
- Project Knowledge retrieval excludes non-`active` evidence.

Current public contract still says:

- allowed operation types are `add_evidence` and `revert_evidence`;
- operations `delete_*` and `replace_*` remain blocked;
- replacement evidence keys are not exposed by safe timeline/readback.

Conclusion: the platform can represent supersession internally, but the
authoring language should not expose `supersede_evidence` yet. The next
implementation should first make internal lifecycle status match the existing
replacement semantics.

## Impact Map

Canonical backend:

- `DomainKnowledgeChangeSetValidator`
- `DomainKnowledgeChangeSetService`
- `DomainKnowledgeEvidence`
- `DomainKnowledgeEvidenceRepository`
- `AgenticAuthoringProjectKnowledgeService`
- `docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml`, only if the
  operation becomes public

Consumers:

- `praxis-ui-angular`, especially Page Builder cockpit actions and
  `@praxisui/ai` generated binding if the public contract changes.
- `praxis-api-quickstart`, as the Neon-backed operational proof host.
- `praxisui-http-examples`, as the derived protected corpus and manifest owner.

Docs and examples:

- this implementation guide;
- AI contract README;
- release-readiness report for the slice;
- HTTP corpus only after the backend behavior and contract are stable.

Minimum validation if only planning changes:

- final document read;
- `git diff --check`.

Minimum validation if implementation starts:

- focused validator/service tests;
- generated contract consistency tests if OpenAPI changes;
- `@praxisui/ai` build if generated TS binding changes;
- quickstart Neon-backed smoke for add -> supersede -> retrieval;
- Page Builder browser proof only after backend/source-level proof is stable.

Breaking-change risk:

- medium if operation enums change in OpenAPI;
- low if this remains a source-level planning slice;
- high if UI exposes supersede actions before backend validation/apply semantics
  are stable.

## Semantic Decision To Make

Before coding, answer this explicitly:

Should `supersede_evidence` be a new operation type, or should the platform keep
`revert_evidence` with `replacementEvidenceKey` as the only public operation for
this beta cut?

Choose `revert_evidence + replacementEvidenceKey` if:

- the replacement is rare;
- UX can explain "revert with replacement" clearly;
- the platform wants fewer public operation types during beta;
- no downstream consumer needs a distinct supersession intent.

Choose first-class `supersede_evidence` if:

- the user-facing intent is "replace outdated guidance with reviewed guidance";
- the cockpit needs a clear "Supersede" action separate from "Revert";
- future LLM planning would benefit from a canonical operation name;
- audit/readiness needs to distinguish "withdraw influence" from "replace
  influence" without parsing payload fields.

Recommended beta decision:

- keep `revert_evidence + replacementEvidenceKey` as the canonical public beta
  path;
- harden internal lifecycle status so replacement marks prior evidence as
  `superseded`;
- defer first-class `supersede_evidence` until a named UI/LLM authoring
  consumer needs a distinct action or operation label.

## Candidate Operation Model

If promoted, `supersede_evidence` should mean:

```json
{
  "operationId": "op-supersede-identity-card-guidance",
  "operationType": "supersede_evidence",
  "target": {
    "tenantId": "desenv",
    "environment": "local",
    "subjectType": "concept",
    "conceptKey": "page-builder.e2e.project-knowledge.identity-card"
  },
  "reason": "Replace outdated identity-card guidance with reviewed guidance.",
  "evidenceRefs": [
    "domain-knowledge:reviewed-guidance"
  ],
  "confidence": 0.9,
  "payload": {
    "evidenceKey": "project-knowledge:identity-card:v1",
    "replacementEvidenceKey": "project-knowledge:identity-card:v2",
    "supersedeReason": "The replacement evidence was reviewed and is now preferred."
  }
}
```

Rules:

- `payload.evidenceKey` is the active evidence losing influence.
- `payload.replacementEvidenceKey` must resolve to active evidence for the same
  concept, or be created earlier in the same change set by an `add_evidence`
  operation if same-change-set composition is explicitly implemented.
- `payload.supersedeReason` is required.
- evidence cannot be physically deleted.
- prior evidence becomes `superseded`, not `reverted`.
- replacement evidence remains `active`.
- retrieval returns replacement evidence only when it remains active.
- safe timeline may emit `evidence.superseded`, but must not expose either
  evidence key.

## Open Questions

- Should first-class `supersede_evidence` be allowed to reference replacement
  evidence created earlier in the same change set, or only pre-existing active
  evidence?
- Should `revert_evidence + replacementEvidenceKey` continue to be accepted if
  `supersede_evidence` is introduced?
- Should existing `revert_evidence` with replacement mark the old row as
  `reverted` with a supersession pointer, or should first-class supersede mark
  the old row as `superseded`?
- Does Page Builder need a separate "Supersede" action now, or can it keep the
  current revert proof until a named UX requirement appears?

## Implementation Slices

### Slice 1. Semantic Inventory

Deliverables:

- inspect current validator/service/timeline tests around replacement evidence;
- decide whether first-class operation is justified;
- document the decision in this file before code changes.

Definition of done:

- no OpenAPI enum changes yet;
- no UI action yet;
- the beta semantics are explicit.

Status:

- completed on 2026-05-01;
- decision recorded above;
- next slice should be source-level lifecycle hardening, not public contract
  promotion.

### Slice 2. Backend Source-Level Spike

Do this before any first-class operation is justified.

Deliverables:

- service applies replacement-backed `revert_evidence` as supersession
  transactionally;
- prior evidence status becomes `superseded`;
- replacement remains `active`;
- retrieval excludes prior evidence.

Definition of done:

- focused tests pass;
- no public contract generation;
- no `supersede_evidence` enum is added;
- plain `revert_evidence` without replacement still marks prior evidence as
  `reverted`.

Status:

- completed on 2026-05-01 with focused service/validator tests;
- no public contract promotion was performed.

### Slice 3. Contract Promotion

Only after source-level behavior is stable.

Deliverables:

- OpenAPI operation enum includes `supersede_evidence`;
- descriptions explain why it differs from `revert_evidence`;
- generated Java and Angular bindings are synchronized.

Definition of done:

- AI contract tests pass;
- `npx ng build praxis-ai` passes;
- derived docs identify the operation as protected, not safe-first
  `llmOperational`.

### Slice 4. Runtime Proof

Deliverables:

- quickstart smoke proves add -> supersede -> retrieval returns replacement and
  excludes prior evidence;
- Neon remains the configured persistence target;
- no local database shortcuts.

Definition of done:

- HTTP proof passes locally;
- generated/runtime artifacts do not expose raw evidence payloads or keys in
  safe readback/timeline.

### Slice 5. Cockpit UX

Only after backend and runtime proof are green.

Deliverables:

- Page Builder can offer a governed "Supersede evidence" action from a safe
  handoff;
- the action creates a governed change set and waits for validation/approval;
- UI never mutates memory or evidence locally.

Definition of done:

- browser E2E proves action, status, readback and retrieval behavior;
- services are cleaned up by local runner;
- no npm publication is required for exploratory proof.

## Stop Conditions

Pause and redesign if a proposed implementation:

- overwrites evidence payload in place;
- physically deletes prior evidence;
- exposes evidence keys or raw payloads in safe timeline/readback;
- makes Page Builder the source of truth for supersession;
- requires Maven/npm publication before local source-level proof;
- spends GitHub Actions while the behavior is still locally reproducible.

## Recommended Next Action

Slice 2 is complete. The next recommended action is a local quickstart runtime
proof for supersession status only if a downstream consumer needs evidence that
replacement-backed revert persists as `superseded` through the reference host.
Otherwise, move the next functional investment to vector/RAG active-evidence
filtering.
