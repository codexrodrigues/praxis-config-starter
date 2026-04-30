# Next Cut Decision After Phase 7

Status: recommended direction
Date: 2026-04-30
Scope: post-Phase 7 agentic authoring planning

## Recommendation

The next cut should be `release-readiness` for the governed Project Knowledge
checkpoint, not vector/RAG ranking and not LLM-authored knowledge writes.

Phase 7 now has a complete beta proof for read-only governed Project Knowledge
influence:

- backend retrieval from governed Domain Knowledge concepts;
- planner consumption through an allowlisted safe projection;
- backend-derived audit counts and safe citation diagnostics;
- Page Builder UI explanation without raw payload, concept key or summary
  leakage;
- versioned local browser/LLM proof in `praxis-ui-angular`.

Before adding new capability, the platform should make this checkpoint easy to
consume, reproduce and release.

## Why Release-Readiness First

`release-readiness` is the smallest platform-correct next step because it
hardens the already-proven semantic decision path without expanding authority.
It should:

- consolidate the local validation commands into one phase checklist;
- document required environment/secrets without exposing secrets;
- verify that public docs, quickstart fixture and UI lane are consistent;
- decide whether a release tag/publication is needed or whether the checkpoint
  remains internal beta evidence;
- keep GitHub Actions as a final gate only.

## Deferred Capability Cuts

### Governed LLM-authored knowledge writes

Do not implement this until there is a separate governance plan covering:

- proposal boundaries through `domain_knowledge_change_set`;
- approval and rollback semantics;
- audit events for proposed/applied/rejected knowledge;
- prompt/source provenance;
- local E2E proving that the LLM cannot silently mutate canonical memory.

### Vector/RAG-derived ranking

Do not implement this until there is a separate governance plan covering:

- vector index as derived data only;
- mandatory canonical Domain Knowledge filtering before ranking;
- redaction and `ai_visibility` enforcement after ranking;
- deterministic fallback when vector search is unavailable;
- tests proving denied or out-of-scope hits cannot influence authoring.

## Minimum Release-Readiness Gates

Run local-first gates before any release/publication workflow:

- `mvn -Dtest=AgenticAuthoringPreviewServiceTest test` in
  `praxis-config-starter`;
- `AI_PROVIDER=openai AI_ENV_FILE=../praxis-config-starter/.env.openai.local.sh ./tools/local-e2e/run-project-knowledge-audit-cockpit-local.sh`
  in `praxis-ui-angular`;
- quickstart fixture verification when the release depends on seeded Project
  Knowledge evidence;
- one final GitHub Actions gate only when closing the release or publication
  phase.

## Stop Conditions

Pause and re-plan if the next change:

- creates a public write endpoint for project memory without change-set
  governance;
- treats vector/RAG data as canonical memory;
- makes Page Builder state the source of project knowledge;
- requires repeated GitHub Actions runs to discover local validation failures;
- cannot explain Project Knowledge influence without exposing sensitive
  payloads.
