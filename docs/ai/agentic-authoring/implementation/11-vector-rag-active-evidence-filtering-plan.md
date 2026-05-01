# Vector/RAG Active Evidence Filtering Plan

Status: Slice 5 vector-disabled runtime proof complete
Date: 2026-05-01
Scope: next capability slice after Domain Knowledge supersession runtime proof

## Classification

- Change class: `arquitetural` when implemented, because vector/RAG may affect
  AI authoring influence, retrieval ranking and derived runtime context.
- Current document change: `docs-apenas`.
- Canonical owner: `praxis-config-starter`.
- Runtime proof owner: `praxis-api-quickstart`.
- UI proof owner: `praxis-ui-angular`, only after backend behavior is stable.
- Derived corpus owner: `praxisui-http-examples`, only if public HTTP examples
  or LLM surfaces change.

## Recommendation

Do not plug Project Knowledge directly into vector/RAG until the derived index
can prove canonical lifecycle filtering.

Project Knowledge influence is already safe in the current repository-backed
path: `AgenticAuthoringProjectKnowledgeService` only returns approved,
AI-visible concepts with at least one `active` evidence row. The next slice
must preserve that invariant if ranking or retrieval starts using vector/RAG.

The recommended direction is:

1. keep Domain Knowledge tables as the canonical source of truth;
2. treat vector/RAG as a disposable derived index;
3. require active-evidence filtering before any vector hit can influence
   `contextHints.projectKnowledge`;
4. include lifecycle metadata on derived documents only as routing/provenance,
   not as authority;
5. re-check canonical Domain Knowledge state after retrieval and before prompt
   injection;
6. define deletion or invalidation behavior for reverted/superseded evidence
   before enabling vector ranking in authoring.

## Current Inventory

The current codebase has three separate retrieval/indexing surfaces:

- `AgenticAuthoringProjectKnowledgeService` retrieves Project Knowledge from
  Domain Knowledge repositories and already requires active evidence.
- `DomainCatalogIngestionService` publishes domain catalog items to
  `RagVectorStoreService` as a derived RAG projection, skipping
  `aiUsage.visibility=deny` and sanitizing `mask`/`summarize_only` content.
- `ContextRetrievalService` searches vector store documents for API metadata
  and component definitions, scoped by tenant, environment and release.

The inventory did not find an implemented Project Knowledge vector index yet.
That is good: the platform can design lifecycle filtering before vector hits
become an AI influence channel.

## Impact Map

Canonical backend:

- `AgenticAuthoringProjectKnowledgeService`
- `DomainKnowledgeConceptRepository`
- `DomainKnowledgeEvidenceRepository`
- `RagVectorStoreService`
- `RagMetadataKeys`
- `RagResourceTypes`
- future Project Knowledge vector projection service, if introduced

Existing derived RAG surfaces:

- `DomainCatalogIngestionService`
- `ContextRetrievalService`
- `ApiMetadataIngestionService`
- `RegistryIngestionService`

Consumers:

- `praxis-api-quickstart`, as the Neon-backed host for runtime proof.
- `praxis-ui-angular`, only when Page Builder starts consuming ranked Project
  Knowledge or diagnostics need browser proof.
- `praxisui-http-examples`, only if examples or LLM corpus classify this as an
  operational public surface.

Docs and examples:

- this implementation guide;
- Project Knowledge release/readiness docs;
- quickstart LLM authoring guide if runtime smokes gain vector flags;
- HTTP corpus only after the behavior becomes public/operational.

Minimum validation while planning:

- document review;
- `git diff --check`.

Minimum validation when implementation starts:

- focused unit tests for vector document metadata and filters;
- focused retrieval tests proving reverted/superseded evidence cannot influence
  Project Knowledge through vector ranking;
- quickstart Neon-backed smoke with vector disabled as baseline;
- optional vector-enabled local smoke only after the repository can run pgvector
  safely against the configured operational datasource.

Breaking-change risk:

- low while this remains internal planning;
- medium if new internal RAG metadata keys are introduced;
- high if vector ranking changes authoring output before canonical re-checks
  are in place.

## Required Invariants

- Vector/RAG is never the source of truth for Project Knowledge status.
- A vector hit must not bypass tenant/environment/context/resource scope.
- A vector hit must not bypass `aiVisibility` constraints.
- A vector hit must not bypass `domain_knowledge_evidence.status='active'`.
- Reverted and superseded evidence must be excluded from AI influence.
- If vector metadata says an evidence row is active but the database says it is
  not active anymore, the database wins.
- Failed vector publication must not block canonical Domain Knowledge writes.
- Failed vector invalidation must fail closed for authoring influence by using
  canonical post-retrieval filtering.

## Proposed Implementation Slices

### Slice 1 - Guardrail Tests Before New Indexing

Objective:

- lock the current repository-backed Project Knowledge invariant before adding
  vector ranking.

Definition of Done:

- tests prove active evidence is required for Project Knowledge retrieval;
- tests prove `reverted` and `superseded` evidence do not influence retrieval;
- tests document that vector/RAG is not currently part of Project Knowledge
  influence.

Recommended validation:

```bash
mvn -q -Dtest=AgenticAuthoringProjectKnowledgeServiceTest test
```

### Slice 2 - Define Derived Document Metadata

Objective:

- introduce internal metadata keys needed for a future Project Knowledge vector
  projection without changing public HTTP contracts.

Candidate metadata:

- `domainKnowledgeConceptId`
- `domainKnowledgeConceptKey`
- `domainKnowledgeEvidenceId`
- `domainKnowledgeEvidenceKey`
- `domainKnowledgeEvidenceStatus`
- `aiVisibility`
- `contextKey`
- `resourceKey`

Definition of Done:

- metadata is documented as derived/provenance only;
- no authoring code trusts metadata status without canonical re-check;
- existing RAG document types remain compatible.

Implementation result:

- completed on 2026-05-01 as an internal metadata/provenance baseline;
- `RagMetadataKeys` now defines Project Knowledge provenance keys for concept,
  evidence, status, AI visibility and semantic scope;
- `RagResourceTypes.PROJECT_KNOWLEDGE` identifies future derived Project
  Knowledge documents without changing public HTTP contracts;
- `RagProjectKnowledgeMetadata` builds safe derived metadata from canonical
  `DomainKnowledgeConcept` and `DomainKnowledgeEvidence` rows;
- tests verify that raw payload, `sourceUri` and `sourcePointer` are not copied
  to metadata;
- no vector publication, vector retrieval or authoring prompt injection was
  introduced in this slice.

### Slice 3 - Add A Project Knowledge Candidate Retriever

Objective:

- create an internal retriever that can optionally rank Project Knowledge
  candidates with vector/RAG, then re-load and filter canonical rows before
  returning safe projections.

Definition of Done:

- vector candidates are treated as IDs/provenance, not payload authority;
- canonical DB state is re-read before prompt/context injection;
- fallback remains the current repository-backed path when vector store is
  disabled or unavailable;
- no public contract changes are required.

Implementation result:

- completed on 2026-05-01 as an internal baseline, not a vector-enabled
  retrieval path;
- `AgenticAuthoringProjectKnowledgeCandidateRetriever` separates candidate
  discovery from canonical influence validation;
- `RepositoryBackedProjectKnowledgeCandidateRetriever` preserves the existing
  repository-backed retrieval behavior;
- `AgenticAuthoringProjectKnowledgeService` still owns the final checks for
  lifecycle, curation status, AI visibility, scope, kind and active evidence;
- tests prove a candidate returned by a derived/external retriever is still
  re-checked against canonical active evidence before projection;
- no vector search, vector publication or prompt injection from vector hits was
  introduced.

### Slice 4 - Lifecycle Invalidation

Objective:

- ensure `add_evidence`, `revert_evidence` and replacement-backed supersession
  leave the derived vector index consistent enough for ranking and fail closed
  for influence.

Definition of Done:

- adding evidence can schedule/upsert a derived document only for active
  evidence;
- reverting evidence deletes or tombstones derived documents for the prior
  evidence;
- superseding evidence excludes the prior evidence and keeps the replacement
  eligible only if active;
- timeline remains observability, not the source of truth.

Implementation result:

- completed on 2026-05-01 as an internal hook baseline, not a vector publication
  path;
- `ProjectKnowledgeDerivedIndexService` defines lifecycle hooks for active
  evidence and deactivated evidence;
- `NoopProjectKnowledgeDerivedIndexService` keeps runtime behavior unchanged
  while no derived Project Knowledge index is published;
- `DomainKnowledgeChangeSetService` calls `evidenceActivated(...)` after
  governed `add_evidence` apply and `evidenceDeactivated(...)` after
  `revert_evidence` or replacement-backed supersession;
- focused tests verify the hook calls for add, plain revert and supersession;
- no pgvector setup, vector store writes, vector deletes or prompt injection
  were introduced.

### Slice 5 - Runtime Proof

Objective:

- prove the full path with real quickstart HTTP and configured persistence.

Definition of Done:

- baseline vector-disabled smoke still passes;
- vector-enabled local proof, if supported by the configured datasource, shows
  active evidence can rank Project Knowledge;
- the same proof shows reverted/superseded evidence cannot influence a later
  authoring turn;
- no GitHub Actions are used during normal iteration.

Runtime proof plan:

- Do not publish Project Knowledge vector documents yet. The current proof lane
  is vector-disabled and validates canonical lifecycle behavior.
- Package `praxis-api-quickstart` against the local `praxis-config-starter`
  artifact and run it with the configured Neon datasource, not a local database.
- Use the existing quickstart smoke to prove repository-backed canonical
  behavior:

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

- Use the supersession variant to prove replacement-backed lifecycle does not
  emit duplicate revert semantics and keeps only active replacement influence:

```bash
BACKEND_URL=http://localhost:8099 \
TENANT_ID=desenv \
ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
REQUIRE_EVIDENCE_SUPERSESSION=true \
AUTHORING_STREAM_MAX_TIME=180 \
scripts/verify-domain-knowledge-change-set-runtime.sh
```

Slice 5 is complete for the vector-disabled baseline when those smokes are run
against a quickstart packaged with the local starter changes and still show:

- active evidence can influence Project Knowledge retrieval;
- plain reverted evidence stops influencing later authoring;
- replacement-backed supersession emits `evidence.superseded`, not duplicate
  `evidence.reverted`;
- Project Knowledge influence remains repository-backed and canonically
  re-checked.

Observed result on 2026-05-01:

- installed local `praxis-config-starter` with `mvn -q -DskipTests install`;
- packaged `praxis-api-quickstart` against the local starter artifact with
  `mvn -q clean package -DskipTests -Dpraxis.config.version=0.1.0-rc.5`;
- started quickstart on `http://localhost:8099` with the configured Neon
  datasource, `PRAXIS_AI_RAG_VECTOR_STORE_ENABLED=false` and
  `PRAXIS_DOMAIN_CATALOG_RAG_PUBLICATION_ENABLED=false`;
- confirmed `/actuator/health` returned `UP`;
- ran the repository-backed Project Knowledge smoke with
  `REQUIRE_PROJECT_KNOWLEDGE_RETRIEVAL=true` and `REQUIRE_EVIDENCE_REVERT=true`;
- observed `project-knowledge-authoring-retrieval-ready` with `expected=present`
  after `add_evidence` and `retrievalCount=2`;
- observed `project-knowledge-authoring-retrieval-ready` with `expected=absent`
  after `revert_evidence` and `retrievalCount=0`;
- ran the supersession smoke with `REQUIRE_EVIDENCE_SUPERSESSION=true`;
- observed `replacement-evidence-created` and
  `supersession-timeline-confirmed`.

Vector-enabled proof remains blocked until a real Project Knowledge derived
index implementation exists and can prove active -> revert/supersede -> no
influence with canonical re-checks.

## Stop Conditions

Do not implement vector/RAG Project Knowledge ranking if any of these happen:

- the retriever cannot re-check canonical Domain Knowledge state after vector
  search;
- vector metadata would need to carry raw payload, prompt, source pointer,
  source URI or materialized payload;
- pgvector setup would require local database drift instead of the configured
  operational datasource;
- the change requires repeated GitHub Actions runs before local proof exists;
- the UI would need to compensate for missing backend lifecycle guarantees.

## Recommended Next Step

Slice 1 completed on 2026-05-01:

- `AgenticAuthoringProjectKnowledgeServiceTest` makes the active-evidence
  invariant explicit: Project Knowledge retrieval stays empty when candidates
  have no canonical `active` evidence, including reverted/superseded guardrails.
- `AgenticAuthoringTurnEngine` injects Project Knowledge through
  `AgenticAuthoringProjectKnowledgeService`, not vector/RAG.
- `AgenticAuthoringPlanService` only sanitizes already-provided
  `contextHints.projectKnowledge`.
- No current Project Knowledge influence path depends on `RagVectorStoreService`,
  `ContextRetrievalService`, `AiRagContextService` or `VectorStore`.

Slice 5 vector-disabled runtime proof completed on 2026-05-01:

- quickstart ran locally on `http://localhost:8099` against Neon with vector
  publication disabled;
- repository-backed Project Knowledge retrieval included active evidence and
  excluded plain reverted evidence;
- replacement-backed supersession completed with active replacement evidence
  and supersession timeline confirmation;
- no GitHub Actions were used.

Next implementation step: design the first real
`ProjectKnowledgeDerivedIndexService` implementation as a disposable derived
index. It must publish only sanitized/provenance metadata, delete or deactivate
derived entries on revert/supersession and keep the final canonical re-check in
`AgenticAuthoringProjectKnowledgeService` before any vector-ranked result can
influence `contextHints.projectKnowledge`.
