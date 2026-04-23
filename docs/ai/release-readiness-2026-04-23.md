# Release Readiness - AI Domain Federation v0.1

Date: 2026-04-23

## Scope

This report closes the local release-readiness pass for the AI/domain foundation delivered around:

- Domain Catalog v2 runtime context;
- Domain Federation v0.1 read-only contract;
- Domain Federation v0.1 persisted candidate release read model;
- local live E2E wrappers that avoid GitHub Actions quota;
- quickstart runtime validation against a published starter dependency.

## Version Set

- `praxis-config-starter`: `0.1.0-rc.24` published to Maven Central.
- `praxis-config-starter`: local `main` after `test(domain): add persistent federation smoke (#20)` for persisted candidate validation.
- `praxis-api-quickstart`: commit `57bfc85`, consuming `praxis-config-starter:0.1.0-rc.24`.
- `praxis-api-quickstart`: locally rebuilt with `-Dpraxis.config.version=0.1.0-rc.5` for unpublished/current starter validation.
- `praxis-ui-angular`: commit `b17b74a8`.
- Java: `21.0.10` for the local quickstart runtime.

## Release State

Ready for the next downstream local/runtime validation cycle.

The starter artifact `io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.24` resolves from Maven and its jar contains:

- `DomainFederationController`;
- `DomainFederationContractValidator`;
- `DomainFederationQueryService`;
- `DomainFederationIngestDryRunService`;
- federation DTOs for source, context, relationship, contract and resolution payloads.

The quickstart was packaged without local starter overrides and contains the nested dependency:

- `BOOT-INF/lib/praxis-config-starter-0.1.0-rc.24.jar`.

For the persisted candidate validation, the quickstart was also packaged with a
local starter override and no `pom.xml` edit:

- `mvn.cmd clean package -DskipTests '-Dpraxis.config.version=0.1.0-rc.5'`.

## Validated Locally

Local validation used the remote PostgreSQL configuration already present in the quickstart project.

- Backend started from `praxis-api-quickstart-2.0.0-rc.9.jar`.
- Flyway validated `20` migrations.
- Current schema version was `20`.
- No migration was applied during the run.
- Domain Federation v0.1 live smoke passed against the published starter dependency.
- Domain Federation v0.1 persisted candidate smoke passed against the quickstart jar rebuilt with the local starter override and `PRAXIS_DOMAIN_FEDERATION_PERSISTENCE_ENABLED=true`.

For the persisted candidate smoke, Flyway validated `21` migrations, the current
schema version was `21`, and no migration was necessary during that run.

Federation smoke result:

- `schemaVersion=praxis.domain-federation/v0.1`;
- `dryRunValid=true`;
- `dryRunIssueCount=0`;
- `ingestDryRunValid=true`;
- `previewCount=2`;
- `contextFederated=true`;
- `contextItemCount=2`;
- `relationshipCount=2`.

Persisted federation smoke result:

- `federationDryRunValid=true`;
- `federationIngestValid=true`;
- `releaseStatus=candidate`;
- `releaseListed=true`;
- `validationReportValid=true`;
- `activatedStatus=active`;
- `contextSourceMode=persisted_federation`;
- `contextItemCount=1`;
- `contextRelationshipCount=1`;
- `deniedContextSourceMode=persisted_federation`;
- `deniedContextItemCount=1`;
- `deniedContextRelationshipCount=0`;
- `deniedItemCount=1`;
- `deniedGuidanceConfirmed=true`;
- `lowConfidenceContextSourceMode=persisted_federation`;
- `lowConfidenceContextItemCount=1`;
- `lowConfidenceContextRelationshipCount=0`;
- `lowConfidenceItemCount=1`;
- `lowConfidenceGuidanceConfirmed=true`;
- `persistedSources=1`;
- `persistedContexts=2`;
- `persistedContextRelationships=1`;
- `persistedContracts=1`;
- `persistedResolutions=1`.

The current persisted retrieval policy has also been validated locally to:

- exclude `contract.visibility=restricted` and `contract.visibility=deny_for_llm`
  from LLM-facing retrieval outside diagnostics;
- exclude low-confidence persisted items using `payload.confidence`,
  `payload.evidence.confidence` and `payload.resolution.confidence`;
- redact persisted relationships when the linked contract is not LLM-safe;
- prove the `deny_for_llm` redaction path over real HTTP against the packaged
  quickstart host, not only through focal tests;
- prove the `low-confidence` exclusion path over real HTTP for
  `policyProfile=authoring` against the packaged quickstart host.

The persistent smoke runner is:

- `tools/Invoke-QuickstartDomainFederationPersistentHttpSmoke.ps1`.

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
- The current Domain Federation v0.1 path persists governed candidate releases, supports explicit activation, and now prefers reviewed active releases during query-time federation retrieval.
- Production flows still need explicit ingestion orchestration or scheduled federation ingest across services.
- Browser E2E with the published `0.1.0-rc.24` quickstart dependency has not been rerun after the final starter publication; the prior browser E2E passed before this final published-dependency confirmation.

## Recommended Next Step

Move from release readiness to the next architectural increment:

1. Add policy checks to prevent denied or low-confidence domain content from leaking across persisted federated context retrieval.
2. Run the browser E2E again after packaging quickstart with the same starter version used by the next release candidate.
3. Feed persisted contracts and semantic resolutions into the UI explainability path and authoring prompts by default.
4. Only after those controls are in place, allow LLM-assisted proposals for federation changes.
