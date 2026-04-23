# Release Readiness - AI Domain Federation v0.1

Date: 2026-04-23

## Scope

This report closes the local release-readiness pass for the AI/domain foundation delivered around:

- Domain Catalog v2 runtime context;
- Domain Federation v0.1 read-only contract;
- local live E2E wrappers that avoid GitHub Actions quota;
- quickstart runtime validation against a published starter dependency.

## Version Set

- `praxis-config-starter`: `0.1.0-rc.24` published to Maven Central.
- `praxis-api-quickstart`: commit `57bfc85`, consuming `praxis-config-starter:0.1.0-rc.24`.
- `praxis-ui-angular`: commit `b17b74a8`.
- Java: `21.0.10` for the local quickstart runtime.

## Release State

Ready for downstream local/runtime validation.

The starter artifact `io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.24` resolves from Maven and its jar contains:

- `DomainFederationController`;
- `DomainFederationContractValidator`;
- `DomainFederationQueryService`;
- `DomainFederationIngestDryRunService`;
- federation DTOs for source, context, relationship, contract and resolution payloads.

The quickstart was packaged without local starter overrides and contains the nested dependency:

- `BOOT-INF/lib/praxis-config-starter-0.1.0-rc.24.jar`.

## Validated Locally

Local validation used the remote PostgreSQL configuration already present in the quickstart project.

- Backend started from `praxis-api-quickstart-2.0.0-rc.9.jar`.
- Flyway validated `20` migrations.
- Current schema version was `20`.
- No migration was applied during the run.
- Domain Federation v0.1 live smoke passed against the published starter dependency.

Federation smoke result:

- `schemaVersion=praxis.domain-federation/v0.1`;
- `dryRunValid=true`;
- `dryRunIssueCount=0`;
- `ingestDryRunValid=true`;
- `previewCount=2`;
- `contextFederated=true`;
- `contextItemCount=2`;
- `relationshipCount=2`.

The latest local artifact directory for this validation was:

- `artifacts/local-e2e/domain-federation-v01-20260423-132515`.

## LLM And Browser Evidence

The broader local live E2E runbook records prior successful checks using real providers:

- OpenAI live HTTP/SSE smoke with `gpt-5.4-mini`;
- Gemini compliance-policy shadow with `gemini-2.5-flash`;
- Domain Catalog v2 ingest/context/relationships;
- Page Builder browser E2E `3/3` against Angular, backend, remote DB and real LLM.

See `docs/ai/local-live-e2e-without-actions.md`.

## GitHub Actions Usage

Tests were intentionally kept local to avoid GitHub Actions quota pressure.

The release workflow was adjusted so Maven Central publication can be triggered without executing tests in Actions. The publication run for `0.1.0-rc.24` reached Maven Central upload and the artifact became resolvable by Maven. The Action was later marked cancelled while waiting for Central Portal to return `PUBLISHED`; the artifact resolution confirms the effective publication.

## Residual Risks

- Maven Central publication status should still be treated as externally asynchronous; dependency resolution has passed, but Central Portal UI may lag.
- Domain Federation v0.1 now has a validation-first persistent read model for `dryRun=false`; `/context` still reads from the Domain Catalog projection until persisted retrieval is proven.
- The local smoke bootstraps Domain Catalog v2 for the test resource before federation. Production flows need explicit ingestion orchestration or scheduled federation ingest.
- Browser E2E with the published `0.1.0-rc.24` quickstart dependency has not been rerun after the final starter publication; the prior browser E2E passed before this final published-dependency confirmation.

## Recommended Next Step

Move from release readiness to the next architectural increment. The first item
has started with durable `domain_federation_release` and `domain_federation_item`
storage:

1. Add release list and validation report endpoints for persisted federation data.
2. Add persisted `/context` retrieval before falling back to Domain Catalog projection.
3. Add policy checks to prevent denied or low-confidence domain content from leaking across federated context retrieval.
4. Only after those controls are in place, allow LLM-assisted proposals for federation changes.

