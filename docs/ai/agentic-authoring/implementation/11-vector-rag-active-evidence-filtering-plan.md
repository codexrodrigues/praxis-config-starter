# Vector/RAG Active Evidence Filtering Plan

Status: Slice 1 guardrail started
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

Slice 1 started on 2026-05-01 by making the current active-evidence invariant
explicit in `AgenticAuthoringProjectKnowledgeServiceTest`: Project Knowledge
retrieval stays empty when candidates have no canonical `active` evidence,
including the explicit reverted/superseded guardrail.

Next implementation step: complete Slice 1 by confirming no current vector/RAG
surface is wired into Project Knowledge influence, then move to Slice 2 as an
internal metadata/provenance preparation. Do not inject vector hits directly
into prompts or `contextHints.projectKnowledge`.
