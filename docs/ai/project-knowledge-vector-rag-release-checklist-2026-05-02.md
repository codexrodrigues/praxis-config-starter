# Project Knowledge Vector RAG Release Checklist

Date: 2026-05-02

Scope: operational checklist for closing the opt-in Project Knowledge Vector RAG
phase without spending GitHub Actions during normal iteration.

## Decision

Use this checklist only when the owner explicitly authorizes a named release cut.

Do not use it to justify Maven Central publication by itself. The current state
is source-level beta evidence, not a publication request.

Canonical decision document:

- [Release Decision: Project Knowledge Vector RAG](release-decision-2026-05-02-project-knowledge-vector-rag.md)

Readiness evidence:

- [Release Readiness - Project Knowledge Vector RAG Checkpoint](release-readiness-2026-05-02-project-knowledge-vector-rag.md)

Quickstart rollout guide:

- [Project Knowledge Vector RAG Rollout](https://github.com/codexrodrigues/praxis-api-quickstart/blob/main/docs/PROJECT-KNOWLEDGE-VECTOR-RAG-ROLLOUT.md)

## Current Phase State

The phase is complete as local beta evidence:

- Project Knowledge RAG publication is opt-in and disabled by default.
- Project Knowledge vector retrieval is opt-in and disabled by default.
- Vector store rows are derived artifacts, not canonical memory.
- Vector-ranked candidates are reloaded from canonical Domain Knowledge before
  influence.
- Reverted evidence is removed from active vector influence.
- Supersession removes the original evidence vector document and keeps the
  replacement evidence eligible only when it remains active.
- Quickstart proves the behavior with Neon-backed persistence and
  `PgVectorStore`.

## Latest Local Preflight

Recorded on 2026-05-02, without GitHub Actions, Maven Central publication, npm
publication or hosted smoke.

Config-starter focal gate passed:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter

mvn -q -Dtest=VectorRankedProjectKnowledgeCandidateRetrieverTest,AgenticAuthoringProjectKnowledgeServiceTest,RagProjectKnowledgeDerivedIndexServiceTest,RagProjectKnowledgeMetadataTest test
```

Repository/tag preflight passed:

```bash
git fetch origin --tags --prune
git status --short
git tag --list 'v0.1.0-rc.*' --sort=-v:refname | head -10
```

Observed:

- working tree was clean before recording this evidence;
- latest local release tag remains `v0.1.0-rc.37`;
- no `v0.1.0-rc.38` tag existed locally after fetching tags.

Quickstart lightweight downstream gate passed:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart

bash -n scripts/verify-domain-knowledge-change-set-runtime.sh
mkdir -p target/scripts
mvn -q dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt -DincludeScope=runtime >/dev/null
javac -cp "$(cat target/runtime-classpath.txt)" -d target/scripts scripts/ProjectKnowledgeFixture.java
```

This lightweight gate only proves script syntax and fixture compilation. It does
not replace the required Neon-backed vector revert and supersession runtime
smokes before any future Maven publication.

## Stop Conditions

Do not publish if any of these are true:

- the owner has not explicitly authorized a named release cut;
- no downstream consumer needs a public Maven coordinate;
- the intended version is not named before the workflow starts;
- a matching tag already exists;
- defaults would enable publication or retrieval automatically;
- quickstart vector revert or supersession smoke fails;
- the only motivation is documentation drift, local proof bookkeeping or minor
  cleanup;
- closing the phase would require repeated Actions reruns.

## Local-First Gates

Run these locally before any remote gate.

### Config Starter Focal Gate

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter

mvn -q -Dtest=VectorRankedProjectKnowledgeCandidateRetrieverTest,AgenticAuthoringProjectKnowledgeServiceTest,RagProjectKnowledgeDerivedIndexServiceTest,RagProjectKnowledgeMetadataTest test
git diff --check
```

Expected:

- all focal tests pass;
- whitespace gate passes;
- Project Knowledge publication and retrieval remain opt-in by default.

### Local Starter Install Gate

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter

mvn -q -DskipTests install
```

Expected:

- local Maven repository receives the current starter artifact;
- no Maven Central publication is involved.

### Quickstart Package Gate

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart

mvn -q clean package -DskipTests -Dpraxis.config.version=0.1.0-rc.5
```

Expected:

- quickstart packages against the locally installed starter;
- the quickstart still acts as host proof and does not redefine starter
  semantics.

### Local Quickstart Startup Gate

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

Expected:

- quickstart starts on `http://localhost:8099`;
- Neon-backed config storage is used;
- `PgVectorStore` initializes;
- Domain Catalog RAG publication remains disabled for this isolated proof.

### Vector Revert Runtime Gate

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

Expected:

- vector document count is `1` after `add_evidence`;
- authoring retrieval is present after add;
- vector document count is `0` after revert;
- authoring retrieval is absent after revert;
- final output includes `projectKnowledgeVectorRetrievalChecked=true`.

### Vector Supersession Runtime Gate

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

Expected:

- original evidence vector document count is `1` after add;
- replacement evidence vector document count is `1` after add;
- original evidence vector document count is `0` after supersession;
- replacement evidence vector document count is `1` after supersession;
- authoring retrieval remains present because replacement evidence is active;
- final output includes `supersessionChecked=true` and
  `projectKnowledgeVectorRetrievalChecked=true`.

## Remote Gate Budget

Keep the default budget at zero Actions during implementation.

When closing a release cut, use at most one remote gate before publication and
one hosted smoke after deploy only if published-host evidence is required.

Do not run repeated Actions for diagnostics that can be reproduced locally.

## Maven Central Decision

Publish only if all are true:

- the owner authorizes the release in the current thread or release issue;
- a named downstream consumer needs the Maven coordinate;
- the intended version is explicit;
- the local-first gates above passed freshly;
- the release workflow is the single phase-closing remote gate;
- defaults remain disabled for both publication and retrieval.

After publication:

1. confirm Maven Central dependency resolution;
2. update `praxis-api-quickstart` to consume the published coordinate;
3. run `mvn -B verify` in quickstart;
4. rerun the vector revert and supersession smokes against the published
   dependency;
5. open or merge one quickstart update with the validation evidence.

## npm Decision

No npm publication is required for this checklist.

Only publish Angular packages if a named external UI consumer explicitly needs a
public registry version tied to this backend vector checkpoint.

## Docs And Derived Artifacts

Before closing the phase, review:

- this checklist;
- the release decision;
- the release readiness report;
- quickstart rollout documentation;
- quickstart README references;
- public docs or HTTP corpus only if hosted/published evidence exists.

Do not promote mutating, tenant-bound or protected examples into unauthenticated
`llmOperational` surfaces without a separate safety decision.

## Done Criteria

The phase can be considered release-ready only when:

- all local gates passed freshly;
- remote gate usage is intentional and bounded;
- Maven publication is explicitly authorized;
- quickstart consumes the published coordinate after Maven Central resolution;
- published-host proof is collected only if needed;
- residual risks are recorded instead of hidden behind reruns.
