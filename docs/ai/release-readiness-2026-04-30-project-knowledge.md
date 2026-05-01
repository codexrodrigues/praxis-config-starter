# Release Readiness - Governed Project Knowledge Checkpoint

Date: 2026-04-30

## Scope

This report records the local-first release-readiness checkpoint for governed
Project Knowledge in agentic authoring.

The operational checklist for closing this checkpoint is:

- [Project Knowledge Release Checklist](project-knowledge-release-checklist-2026-04-30.md)

The validated path covers:

- read-only retrieval of governed Project Knowledge from Domain Knowledge
  concepts;
- planner consumption through an allowlisted safe projection;
- backend-derived audit counts and safe citation diagnostics;
- Page Builder UI explanation without exposing raw payloads, concept keys,
  source summaries or knowledge summaries;
- a versioned local browser/LLM proof for the Project Knowledge audit cockpit.

This checkpoint does not authorize Maven Central publication, npm publication,
vector/RAG ranking or LLM-authored memory writes.

## Post-Checkpoint Update - 2026-05-01

The next beta slice after this read-only checkpoint is now closed in source:
governed Project Knowledge writes are represented as Domain Knowledge
change-sets, not as browser memory or direct frontend persistence.

Additional merged proof:

- `praxis-config-starter` PR #175 validates `add_evidence` payloads before
  persistence, including canonical `target.conceptKey`, `payload.evidenceKey`
  and supported `evidenceType` values.
- `praxis-ui-angular` PR #94 proves the Page Builder cockpit can create,
  validate, approve, apply and read back a governed
  `/api/praxis/config/domain-knowledge/change-sets` proposal through a real
  browser/local backend path.

The resulting beta contract is:

- Project Knowledge influence remains a safe, backend-derived projection.
- LLM-authored continuation must go through a governed change-set.
- `project_preference` and similar audit entry kinds are source semantics, not
  persisted `evidenceType` values.
- The canonical persisted evidence type for the Page Builder continuation path
  is `llm_proposal`, with source kind preserved separately in the payload.
- Applying a proposal is separate from approval and remains scoped to validated
  `add_evidence` operations in this cut.

This update still does not authorize Maven Central or npm publication by
itself. Treat it as source-level beta evidence until a named downstream
consumer requires a published artifact.

## Version Set

- `praxis-config-starter`: `main` commit
  `d2613e4` (`fix(agentic): harden project knowledge audit counts`).
- `praxis-config-starter`: docs checkpoint branch
  `docs/phase7-project-knowledge-checkpoint`.
- `praxis-ui-angular`: `main` commit
  `1ca97f54` (`test(page-builder): prove governed project knowledge audit status`).
- `praxis-api-quickstart`: `main` commit
  `03d4810a` (`Add Page Builder project knowledge fixture`).

## Merged Changes

- `praxis-api-quickstart` PR #45 seeds approved, active and AI-visible Project
  Knowledge into the configured config-store datasource for local proof.
- `praxis-ui-angular` PR #88 surfaces safe `projectKnowledge.retrieve`
  progress in the Page Builder assistant.
- `praxis-ui-angular` PR #89 proves the stream path with seeded governed
  Project Knowledge in a real browser E2E.
- `praxis-config-starter` PR #163 adds backend-owned
  `projectKnowledgeAudit` diagnostics.
- `praxis-ui-angular` PR #90 surfaces safe audit citation counts in the Page
  Builder cockpit.
- `praxis-config-starter` PR #164 synchronizes the implementation plan after
  the UI audit slice.
- `praxis-ui-angular` PR #91 adds the final browser proof assertion and
  versioned local E2E wrapper.
- `praxis-config-starter` PR #165 hardens audit count derivation and documents
  the versioned lane.
- `praxis-config-starter` PR #166 records the checkpoint and recommends
  `release-readiness` as the next cut.

## Contract State

The current beta contract is intentionally read-only:

- Project Knowledge is represented as a governed profile of the Domain
  Knowledge Layer, not as browser state or chat history.
- Normal authoring influence only uses active, approved and AI-visible safe
  projections.
- `AgenticAuthoringPreviewDiagnostics.projectKnowledgeAudit` is a safe
  observability projection.
- `influenceCount`, `citedCount` and `uncitedCount` are derived by the backend
  from safe audit entries and must not trust context/client-supplied counters.
- The UI renders citation-count explanation only; it must not render raw
  payloads, raw source data, concept keys, source summaries or knowledge
  summaries in the common cockpit status.
- Vector/RAG remains a derived future optimization, not canonical memory.
- LLM-authored memory writes remain out of scope until change-set governance,
  audit and rollback are explicitly planned.

## Validated Locally

Backend focal gate:

```bash
mvn -Dtest=AgenticAuthoringPreviewServiceTest test
```

Result:

- `11` tests;
- `0` failures;
- `BUILD SUCCESS`.

Browser/LLM cockpit gate:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-ui-angular

AI_PROVIDER=openai \
AI_ENV_FILE=../praxis-config-starter/.env.openai.local.sh \
PRAXIS_E2E_TIMEOUT_MS=600000 \
./tools/local-e2e/run-project-knowledge-audit-cockpit-local.sh
```

Result:

- local `praxis-config-starter` installed into the local Maven repository;
- managed quickstart and Angular services used isolated ports `8098` and
  `4083`;
- Playwright result: `1 passed`;
- run duration: about `33s`;
- temporary ports were clean after the run.

Docs gate:

```bash
git diff --check
```

Result:

- passed for the docs checkpoint diff.

## GitHub Actions Usage

GitHub Actions were used only as phase-closing gates after local-first proof:

- `praxis-ui-angular` PR #91 pull-request CI passed.
- `praxis-ui-angular/main` push CI passed after merge.
- `praxis-config-starter/main` push CI passed after PR #165 merge.

The docs checkpoint PR #166 is intentionally draft and has no checks. Do not
merge it only to create another `main` CI run unless the team explicitly accepts
that documentation closure as a phase gate.

## Release State

Ready as an internal beta checkpoint for read-only governed Project Knowledge
influence and safe audit/UI explanation.

The post-checkpoint governed write slice is also ready as source-level beta
evidence for the current `add_evidence` change-set path.

Not ready for external artifact publication by itself.

Do not create a new Maven Central version or npm package solely for this
checkpoint. Publication requires a named downstream need, a release decision and
one final gate.

## Residual Risks

- The proof depends on a real LLM provider and can vary in latency or provider
  availability.
- The Project Knowledge fixture is operational evidence, not a public endpoint.
- The current UI explanation is intentionally terse; richer audit UX should
  remain safe-by-default and governed.
- Vector/RAG ranking, rollback and broader write operation types remain
  unimplemented by design.

## Recommended Next Step

Treat this checkpoint as closed for beta read/audit/UI proof.

The next implementation phase should be one of:

1. Release-readiness closure only, if a downstream consumer needs a documented
   checkpoint without publication.
2. Hardening governed knowledge writes beyond the current `add_evidence`
   slice, only after rollback and broader operation semantics are planned.
3. Vector/RAG-derived ranking, only after a derived-index governance plan
   proves denied/out-of-scope hits cannot influence authoring.
