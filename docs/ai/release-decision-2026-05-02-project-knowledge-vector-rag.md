# Release Decision: Project Knowledge Vector RAG

Date: 2026-05-02

Status: not published; hold as local-first source-level beta evidence until the
owner authorizes a named release cut.

## Scope

This decision covers the opt-in Project Knowledge Vector RAG path:

- derived publication of active Project Knowledge evidence into the configured
  vector store;
- vector-ranked candidate retrieval for agentic authoring;
- canonical reload and policy filtering before any candidate can influence AI;
- lifecycle cleanup for revert and supersession.

This belongs to `praxis-config-starter` because `/api/praxis/config/**`,
Domain Knowledge, Project Knowledge retrieval, RAG configuration and agentic
authoring influence are canonical config-starter responsibilities. The
quickstart proves behavior over real HTTP and Neon-backed persistence, but it
must not redefine the contract locally.

Vector RAG is not canonical memory. It is a derived candidate-ranking index over
governed semantic decisions.

## Safety Contract

The vector path must preserve these invariants:

- Domain Knowledge remains the source of truth.
- Vector store rows are derived artifacts.
- Vector search may rank candidates, but it must not directly authorize
  influence.
- Candidates must be reloaded from canonical Domain Knowledge before projection.
- Reloaded concepts must include `sourceRelease` needed for safe projections.
- Influence requires active, approved and AI-visible evidence.
- Reverted or superseded original evidence must be excluded from retrieval and
  from active vector documents.
- Replacement evidence may remain eligible only when it is active and attached
  to the same governed concept.
- Raw payloads, source pointers, source URIs, patch hashes, prompts, transcripts
  and chat history must not become public explanation or timeline content.

This keeps Praxis aligned with the platform premise: AI authors semantic
decisions under governance, and RAG can accelerate discovery, but cannot become
the rule source.

## Current Evidence

- `praxis-config-starter/main` commit `1273880` added opt-in Project Knowledge
  RAG index publication with default disabled.
- `praxis-config-starter/main` commit `4864f7c` added opt-in vector-ranked
  candidate retrieval with default disabled.
- `praxis-config-starter/main` commit `abbe92b` fixed canonical reload to fetch
  `sourceRelease`, added/updated focal tests and documented the local runtime
  proof.
- `praxis-config-starter/main` commit `928964b` recorded the readiness
  checkpoint.
- `praxis-config-starter/main` commit `1bf718d` recorded the latest Neon-backed
  runtime gates in the operational release checklist.
- `praxis-api-quickstart/main` commit `8dc827f` added the strict vector runtime
  smoke gate.
- `praxis-api-quickstart/main` commit `5fa9b69` recorded the operational rollout
  guide.

Local starter focal tests passed:

```bash
mvn -q -Dtest=VectorRankedProjectKnowledgeCandidateRetrieverTest,AgenticAuthoringProjectKnowledgeServiceTest,RagProjectKnowledgeDerivedIndexServiceTest,RagProjectKnowledgeMetadataTest test
```

Local quickstart packaging against the unreleased starter passed:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter
mvn -q -DskipTests install

cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart
mvn -q clean package -DskipTests -Dpraxis.config.version=0.1.0-rc.5
```

Local Neon-backed vector runtime proof passed with:

```bash
BACKEND_URL=http://localhost:8099 \
TENANT_ID=desenv \
ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
REQUIRE_PROJECT_KNOWLEDGE_VECTOR_RETRIEVAL=true \
AUTHORING_STREAM_MAX_TIME=180 \
scripts/verify-domain-knowledge-change-set-runtime.sh
```

The revert proof observed:

- add change set `e8c156b2-82b0-4b44-88a6-5771cf597b95`;
- revert change set `086659a3-9c8d-4774-961d-71f42663f7f2`;
- vector document count `1` after `add_evidence`;
- authoring retrieval present after add;
- vector document count `0` after revert;
- authoring retrieval absent after revert;
- `projectKnowledgeVectorRetrievalChecked=true`.

Local supersession proof passed with:

```bash
BACKEND_URL=http://localhost:8099 \
TENANT_ID=desenv \
ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
REQUIRE_PROJECT_KNOWLEDGE_VECTOR_RETRIEVAL=true \
REQUIRE_EVIDENCE_SUPERSESSION=true \
AUTHORING_STREAM_MAX_TIME=180 \
scripts/verify-domain-knowledge-change-set-runtime.sh
```

The supersession proof observed:

- add change set `9b0eb8b4-7cb8-435b-a688-f311afb998a9`;
- supersession/revert change set `c73ea4dd-ed1a-41e5-afee-836ed6141536`;
- original evidence vector document count `1` after add;
- replacement evidence vector document count `1` after add;
- original evidence vector document count `0` after supersession;
- replacement evidence vector document count `1` after supersession;
- authoring retrieval still present because replacement evidence remained
  active;
- `supersessionChecked=true`;
- `projectKnowledgeVectorRetrievalChecked=true`.

The backend used for this local proof was stopped after the smokes, and
`http://localhost:8099/actuator/health` no longer responded.

No GitHub Actions, Maven Central publication, npm publication or hosted smoke
was used for this checkpoint.

## Publication Decision

Do not publish this phase yet.

Operational release checklist:

- [Project Knowledge Vector RAG Release Checklist](project-knowledge-vector-rag-release-checklist-2026-05-02.md)

Publication becomes appropriate only after all of these are true:

- the owner explicitly authorizes closing Project Knowledge Vector RAG as a
  named release cut;
- a named downstream consumer needs the vector path from Maven Central;
- the intended version is explicit and no matching tag already exists;
- local starter focal tests pass freshly;
- quickstart strict vector revert and supersession proofs pass freshly against
  Neon;
- the defaults remain disabled for publication and retrieval;
- one remote gate is intentionally selected as a phase-closing gate.

Until then, keep this as source-level beta evidence.

## Gates Before Publishing Maven

Use local-first validation before any remote release workflow:

1. Confirm `praxis-config-starter/main` is clean and contains the intended
   vector commits.
2. Confirm no newer intended `v0.1.0-rc.*` tag already exists.
3. Run the focal starter tests:

```bash
mvn -q -Dtest=VectorRankedProjectKnowledgeCandidateRetrieverTest,AgenticAuthoringProjectKnowledgeServiceTest,RagProjectKnowledgeDerivedIndexServiceTest,RagProjectKnowledgeMetadataTest test
```

4. Install the starter locally:

```bash
mvn -q -DskipTests install
```

5. Package quickstart against the local starter:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart
mvn -q clean package -DskipTests -Dpraxis.config.version=0.1.0-rc.5
```

6. Start the quickstart with vector flags enabled:

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

7. Run both strict quickstart vector smokes:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart

BACKEND_URL=http://localhost:8099 \
TENANT_ID=desenv \
ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
REQUIRE_PROJECT_KNOWLEDGE_VECTOR_RETRIEVAL=true \
AUTHORING_STREAM_MAX_TIME=180 \
scripts/verify-domain-knowledge-change-set-runtime.sh

BACKEND_URL=http://localhost:8099 \
TENANT_ID=desenv \
ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
REQUIRE_PROJECT_KNOWLEDGE_VECTOR_RETRIEVAL=true \
REQUIRE_EVIDENCE_SUPERSESSION=true \
AUTHORING_STREAM_MAX_TIME=180 \
scripts/verify-domain-knowledge-change-set-runtime.sh
```

Use GitHub Actions only as the phase-closing release gate, not as an iteration
loop.

## Quickstart Rollout After Publication

Only after Maven Central resolves the approved coordinate:

1. Update `praxis-api-quickstart/pom.xml` to consume the published
   `praxis.config.version`.
2. Update quickstart README and rollout documentation with the published
   version.
3. Run `mvn -B verify` in quickstart.
4. Run the strict vector revert and supersession runtime smokes against the
   quickstart packaged with the published dependency.
5. Merge one quickstart update with validation evidence.
6. Redeploy the published quickstart runtime only when the phase intentionally
   needs hosted evidence.
7. Run at most one hosted smoke as the post-deploy phase gate.

## HTTP Corpus and Public Docs

Do not promote HTTP corpus examples solely for the source-level checkpoint.

If this phase becomes published and public docs need operational proof, update
derived corpus/docs only after hosted or published-runtime evidence exists.
Protected, mutating or tenant-bound examples must remain out of the
unauthenticated `llmOperational` surface unless a separate safety decision says
otherwise.

## npm Publication

No npm publication is required for this decision.

The current phase affects backend indexing, retrieval and quickstart runtime
smoke verification. Publish Angular packages only if a named external UI
consumer needs public registry support for a UI feature that specifically
depends on this vector checkpoint.

## Acceptance Criteria For Future Publication

This phase may be published only when all are true:

- Maven publication is explicitly authorized.
- The new coordinate is named before the release workflow starts.
- Starter focal tests pass locally.
- Quickstart local strict vector smokes pass for revert and supersession.
- Publication and retrieval remain opt-in.
- The release uses one remote gate rather than repeated Actions.
- Quickstart consumes the published coordinate without local overrides.
- Published or hosted proof is collected only if the phase requires public
  downstream evidence.

## Recommendation

Hold this phase as local beta evidence.

The next natural work should either be:

1. one authorized RC cut with the gates above, if a downstream consumer needs
   Maven Central; or
2. the next source-level capability slice, if no external consumer needs the
   vector path yet.

Do not create another Maven version for documentation drift, local proof
bookkeeping or minor cleanup.
