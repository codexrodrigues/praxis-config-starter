# Release Readiness - Domain Knowledge Revert Checkpoint

Date: 2026-05-01

## Scope

This report records the local-first release-readiness checkpoint for governed
Domain Knowledge evidence reversal.

The validated path covers:

- canonical evidence lifecycle fields on `domain_knowledge_evidence`;
- governed `revert_evidence` validation through Domain Knowledge change sets;
- transactional apply that marks evidence as `reverted` or `superseded`
  without physical deletion;
- safe timeline events for `evidence.reverted` and `evidence.superseded`;
- Project Knowledge retrieval filtering that requires active evidence;
- quickstart HTTP proof with Neon-backed config storage;
- Page Builder browser proof that reverted evidence no longer appears as active
  Project Knowledge authoring context.

This checkpoint does not authorize destructive `delete_*` operation types,
broader `replace_*` writes, vector/RAG ranking, Maven Central publication, npm
publication or repeated GitHub Actions runs.

## Version Set

- `praxis-config-starter`: `main` commit `f8d9411`
  (`Record Domain Knowledge revert browser checkpoint [skip ci]`).
- `praxis-api-quickstart`: `main` commit `60d8f12`
  (`Prove project knowledge retrieval after evidence revert [skip ci]`).
- `praxis-ui-angular`: `main` commit `574ee312`
  (`Prove Project Knowledge evidence revert in browser [skip ci]`).

## Contract State

The current beta contract is intentionally narrow:

- `add_evidence` remains the governed additive write path.
- `revert_evidence` is the governed correction path for evidence that should
  stop influencing future authoring.
- Revert is modeled as lifecycle transition, not deletion.
- Target evidence must exist in the same tenant/environment, belong to the
  target concept and be `active`.
- Optional replacement evidence must also be active and attached to the same
  target concept.
- Timeline/readback projections must remain safe and must not expose evidence
  keys, replacement keys, raw payloads, source pointers, source URIs, patch
  hashes, prompts, transcripts or chat history.
- Project Knowledge used by agentic authoring is derived from governed concepts
  only when active evidence exists.

## Validated Locally

Config-starter focal tests:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter

mvn -q -Dtest=AgenticAuthoringProjectKnowledgeServiceTest test
mvn -q -Dtest=AgenticAuthoringPlanServiceTest,AgenticAuthoringTurnEngineTest test
git diff --check
```

Result:

- all focal tests passed locally;
- docs/code whitespace gate passed.

Quickstart runtime proof with Neon:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart

BACKEND_URL=http://localhost:8099 \
TENANT_ID=desenv \
ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
REQUIRE_EVIDENCE_REVERT=true \
REQUIRE_PROJECT_KNOWLEDGE_RETRIEVAL=true \
AUTHORING_STREAM_MAX_TIME=180 \
scripts/verify-domain-knowledge-change-set-runtime.sh
```

Observed result:

- add change set `191e9f50-d840-4f44-ac9c-30e0a40014b4`;
- retrieval after add returned `expected=present` and `retrievalCount=2`;
- revert change set `1db47667-ce1c-462a-8e42-ce1806782038`;
- retrieval after revert returned `expected=absent` and `retrievalCount=0`;
- final marker `projectKnowledgeRetrievalChecked=true`.

Page Builder browser proof:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-ui-angular

AI_PROVIDER=openai \
AI_ENV_FILE=../praxis-config-starter/.env.openai.local.sh \
PRAXIS_E2E_TIMEOUT_MS=900000 \
./tools/local-e2e/run-project-knowledge-audit-cockpit-local.sh
```

Observed result:

- local `praxis-config-starter` installed into the local Maven repository;
- managed quickstart/UI services used isolated ports `8098` and `4083`;
- first browser turn included the Project Knowledge fixture in
  `projectKnowledgeAudit` while evidence was `active`;
- the test created, validated, approved and applied `revert_evidence` through
  the canonical Domain Knowledge change-set boundary;
- second browser turn no longer included the same concept in the audit;
- Playwright result: `2 passed (1.3m)`;
- temporary ports were clean after the run.

Docs gate:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter

git diff --check
```

Result:

- passed for this checkpoint documentation diff.

## GitHub Actions Usage

No GitHub Actions were used for this checkpoint closure.

Keep the default remote budget at zero Actions during normal iteration. Use a
single remote gate only if the team intentionally promotes this checkpoint to a
phase-closing release gate or a published-host smoke.

## Release State

Ready as an internal source-level beta checkpoint for governed evidence
reversal and active-evidence Project Knowledge retrieval.

Not ready for external artifact publication by itself.

Do not create a new Maven Central version or npm package solely for this
checkpoint. Publication requires:

- a named downstream consumer;
- an explicit release version decision;
- fresh local focal tests;
- fresh quickstart runtime proof;
- fresh Page Builder browser proof;
- one intentionally selected remote gate.

## Residual Risks

- The browser proof depends on a real LLM provider and can vary in latency or
  provider availability.
- The current revert surface is intentionally scoped to evidence lifecycle; it
  does not authorize concept deletion, payload replacement or bulk rollback.
- Timeline is safe observability, not the source of truth for lifecycle state.
- Vector/RAG indexing remains deferred and must treat reverted evidence as
  excluded from influence unless a future governance plan explicitly defines
  another safe behavior.

## Recommended Next Step

Treat this checkpoint as closed for beta evidence reversal.

The next capability cut should be planned separately and should start with one
of these, in order of preference:

1. Contract/readiness cleanup only, if a named consumer needs a documented
   checkpoint without publication.
2. A narrow governed `supersede_evidence` authoring UX, if the backend needs a
   first-class request distinct from `revert_evidence` with replacement.
3. Broader Domain Knowledge write types, only after an explicit architecture
   plan preserves audit, validation, rollback and safe timeline behavior.
4. Vector/RAG-derived ranking, only after canonical lifecycle filtering,
   denied-hit behavior and rollback semantics are planned.
