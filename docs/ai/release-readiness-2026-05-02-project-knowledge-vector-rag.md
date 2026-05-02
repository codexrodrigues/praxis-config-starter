# Release Readiness - Project Knowledge Vector RAG Checkpoint

Date: 2026-05-02

Status: source-level beta checkpoint, locally proven with Neon-backed quickstart.

## Scope

This report records the local-first readiness checkpoint for Project Knowledge
Vector RAG as a derived ranking path for AI-authored semantic decisions.

The validated path covers:

- optional publication of active Project Knowledge evidence into the configured
  vector store;
- optional vector-ranked candidate retrieval for agentic authoring;
- canonical reload of vector candidates from Domain Knowledge before they can
  influence authoring;
- lifecycle exclusion of reverted evidence from both authoring retrieval and
  vector documents;
- supersession behavior where the original evidence is removed and the
  replacement evidence remains active;
- quickstart HTTP proof with Neon-backed config storage.

This checkpoint does not authorize Maven Central publication, npm publication,
default runtime enablement, hosted smoke execution or broad RAG memory semantics.

## Version Set

- `praxis-config-starter`: `main` commit `abbe92b`
  (`Prove Project Knowledge vector retrieval locally [skip ci]`).
- `praxis-api-quickstart`: `main` commit `8dc827f`
  (`Add Project Knowledge vector runtime smoke [skip ci]`).

## Contract State

The current beta contract is intentionally narrow:

- Domain Knowledge remains the canonical source of Project Knowledge.
- Vector store rows are a derived index, not the source of truth.
- Vector retrieval is only a candidate ranking path.
- Candidate influence requires canonical reload and policy checks against active,
  approved and AI-visible Domain Knowledge concepts/evidence.
- The vector retriever must fetch `sourceRelease` with reloaded concepts so safe
  projection building does not depend on lazy session state.
- Reverted evidence must not influence authoring and must not remain as an
  active `project_knowledge` vector document.
- Superseded original evidence must be excluded, while active replacement
  evidence remains eligible.
- Both publication and retrieval are opt-in. Defaults stay disabled.

Required opt-in properties:

```properties
praxis.ai.rag.vector-store.enabled=true
praxis.project-knowledge.rag-publication.enabled=true
praxis.project-knowledge.rag-retrieval.enabled=true
```

Environment variable equivalents used by the quickstart proof:

```bash
PRAXIS_AI_RAG_VECTOR_STORE_ENABLED=true
PRAXIS_PROJECT_KNOWLEDGE_RAG_PUBLICATION_ENABLED=true
PRAXIS_PROJECT_KNOWLEDGE_RAG_RETRIEVAL_ENABLED=true
```

## Validated Locally

Config-starter focal gate:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter

mvn -q -Dtest=VectorRankedProjectKnowledgeCandidateRetrieverTest,AgenticAuthoringProjectKnowledgeServiceTest,RagProjectKnowledgeDerivedIndexServiceTest,RagProjectKnowledgeMetadataTest test
git diff --check
```

Result:

- all focal tests passed locally;
- docs/code whitespace gate passed.

Local install and quickstart package gate:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter
mvn -q -DskipTests install

cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart
mvn -q clean package -DskipTests -Dpraxis.config.version=0.1.0-rc.5
```

Result:

- starter installed into the local Maven repository;
- quickstart packaged against the local starter without publishing Maven.

Local quickstart startup used the configured Neon datasource and vector store:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter

PORT=8099 \
PROVIDER=openai \
STREAM_TIMEOUT_SECONDS=180 \
PRAXIS_AI_RAG_VECTOR_STORE_ENABLED=true \
PRAXIS_DOMAIN_CATALOG_RAG_PUBLICATION_ENABLED=false \
PRAXIS_PROJECT_KNOWLEDGE_RAG_PUBLICATION_ENABLED=true \
PRAXIS_PROJECT_KNOWLEDGE_RAG_RETRIEVAL_ENABLED=true \
tools/local-e2e/start-quickstart-local-e2e.sh
```

Observed:

- backend started on `http://localhost:8099`;
- `PgVectorStore` initialized on `vector_store`;
- no GitHub Actions were used.

Revert runtime proof:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart

BACKEND_URL=http://localhost:8099 \
TENANT_ID=desenv \
ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
REQUIRE_PROJECT_KNOWLEDGE_VECTOR_RETRIEVAL=true \
AUTHORING_STREAM_MAX_TIME=180 \
scripts/verify-domain-knowledge-change-set-runtime.sh
```

Observed result:

- vector document count after `add_evidence`: `1`;
- authoring retrieval after add returned `expected=present`;
- vector document count after revert: `0`;
- authoring retrieval after revert returned `expected=absent`;
- final marker included `projectKnowledgeVectorRetrievalChecked=true`.

Supersession runtime proof:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart

BACKEND_URL=http://localhost:8099 \
TENANT_ID=desenv \
ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
REQUIRE_PROJECT_KNOWLEDGE_VECTOR_RETRIEVAL=true \
REQUIRE_EVIDENCE_SUPERSESSION=true \
AUTHORING_STREAM_MAX_TIME=180 \
scripts/verify-domain-knowledge-change-set-runtime.sh
```

Observed result:

- original evidence vector document count after add: `1`;
- replacement evidence vector document count after add: `1`;
- after supersession, original evidence vector document count: `0`;
- after supersession, replacement evidence vector document count: `1`;
- authoring retrieval remained present because the replacement evidence stayed
  active;
- final marker included `supersessionChecked=true` and
  `projectKnowledgeVectorRetrievalChecked=true`.

## Bug Found and Fixed During Proof

The first vector-enabled runtime proof exposed a real canonical reload bug:

```text
could not initialize proxy [org.praxisplatform.config.domain.DomainCatalogRelease] - no Session
```

Root cause:

- vector-ranked retrieval reloaded concepts through a repository method that did
  not fetch `sourceRelease`;
- the safe projection builder needs release metadata after the repository call.

Fix:

- `DomainKnowledgeConceptRepository` now has
  `findWithSourceReleaseByTenantIdAndEnvironmentAndConceptKeyIn(...)`;
- `VectorRankedProjectKnowledgeCandidateRetriever` uses that method before
  building safe authoring projections;
- focal tests were updated and passed.

This is why the quickstart proof is important: it caught a runtime integration
fault that unit-only source checks would not have exposed.

## GitHub Actions Usage

No GitHub Actions were used for this checkpoint closure.

Keep the default remote budget at zero Actions during normal iteration. Use a
single remote gate only if the team intentionally promotes this checkpoint to a
phase-closing release or a hosted published smoke.

## Release State

Ready as an internal source-level beta checkpoint for opt-in Project Knowledge
Vector RAG indexing and candidate ranking.

Not ready for external artifact publication by itself.

Do not create a new Maven Central version solely for this checkpoint unless all
of these become true:

- a named downstream consumer needs the vector path from a public artifact;
- defaults remain safe and disabled unless explicitly enabled;
- focal starter tests pass freshly;
- quickstart vector runtime proof passes freshly against Neon;
- one intentionally selected remote release gate is accepted.

No npm publication is required. The current phase affects backend indexing,
retrieval and quickstart smoke verification, not public Angular packages.

## Residual Risks

- Vector ranking depends on embeddings and query shape, so exact ranking remains
  provider-sensitive.
- The vector table is a derived index and can drift if publication is enabled
  while lifecycle events fail midway; runtime influence is still protected by
  canonical reload and active-evidence filtering.
- This checkpoint proves `add_evidence`, revert and supersession semantics. It
  does not define broader memory writes, bulk rollback or destructive deletion.
- Hosted proof is intentionally deferred to avoid spending Actions and deploy
  cycles before a named release decision.

## Recommended Next Step

Treat this checkpoint as closed for local beta evidence.

The next recommended phase is release packaging only when the owner authorizes a
named cut. Until then, keep accumulating source-level evidence locally and avoid
publishing Maven/npm or triggering GitHub Actions for minor documentation-only
changes.
