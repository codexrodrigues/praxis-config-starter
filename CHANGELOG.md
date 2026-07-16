# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Added
- Opt-in GitHub release gate for the versioned assistant consistency corpus,
  reusing the canonical local runner to execute repeated real-provider journeys
  with semantic accuracy, transaction, latency, token and cost evidence.
- Append-only `domain_rule_definition_approval` evidence bound to the canonical
  SHA-256 of an exact governed definition, including database-level mutation
  rejection and snapshot publication revalidation.
- Safe domain-rule timeline events accept the server-resolved `authenticated`
  actor class introduced by the maker-checker lifecycle.
- IAM roles `RULE_DEFINITION_AUTHOR` and `RULE_DEFINITION_APPROVER` for the
  definition maker-checker lifecycle.
- Release-hardening checkpoint for the governed runtime related surface preview,
  covering runtime observations, backend grounding, `runtimeToolPlan`,
  multi-read, summary, compare, detail, quick replies, governed multi-turn
  follow-up and the official short real smoke battery.
- Governed table runtime authoring now projects `dynamicPage.surface.open` from
  `runtimeOperations`, allowing selected-record related surfaces such as
  timelines or teams to be materialized as canonical runtime operations.
- Governed `praxis-filter` component authoring capability catalog so semantic
  page composition can materialize search/filter widgets from read/search
  intent without hard-coding UI-only aliases.
- Governed Domain Knowledge evidence lifecycle for active, reverted and
  superseded evidence states.
- Governed `revert_evidence` change-set validation, transactional apply and
  safe timeline events.
- Optional replacement evidence handling through
  `revert_evidence + replacementEvidenceKey`, preserving beta semantics without
  introducing a separate `supersede_evidence` operation type.
- Active-evidence filtering for Project Knowledge authoring retrieval so
  reverted or superseded original evidence no longer influences future AI turns.
- Opt-in Project Knowledge derived-index publication into the configured vector
  store, disabled by default.
- Opt-in Project Knowledge vector-ranked candidate retrieval for agentic
  authoring, disabled by default.
- Vector metadata for Project Knowledge derived documents, including tenant,
  environment, concept and evidence lifecycle fields used by runtime smokes.
- Release-readiness, release-decision and release-checklist documentation for
  the Project Knowledge Vector RAG checkpoint.

### Changed
- Domain-rule intake, creation and definition status transitions now derive
  tenant, environment and actor from server authentication. Definition approval
  requires authenticated author evidence, rejects self-approval and fails closed
  when the current source hash has no matching approval.
- The beta request contracts no longer accept `createdByType`, `createdBy`,
  `approvedBy`, `decidedByType` or `decidedBy` for governed definition lifecycle
  calls. Hosts must map the new IAM roles and recreate or version legacy rules
  whose author was not recorded as `authenticated` before snapshot publication.
- Runtime metadata for table AI turns now promotes `recordSurfaces` and
  `runtimeOperations` ahead of the full `contextHints`, reducing truncation risk
  when the LLM must materialize declared dynamic-page surface operations.
- Project Knowledge retrieval now treats Domain Knowledge as the canonical source
  of truth and vector search as candidate ranking only.
- Vector-ranked Project Knowledge candidates are reloaded from canonical Domain
  Knowledge with `sourceRelease` before safe projection building.
- Project Knowledge RAG publication and retrieval remain opt-in beta paths and
  must not be enabled implicitly by host applications.

### Fixed
- Stabilized repeated blank-page form and table creation by normalizing broad
  component-authoring capability aliases to canonical artifact creation and by
  recognizing the root POST create operation without mistaking the canonical
  `/schemas/filtered` endpoint for a business filter route. A single governed
  form-create candidate selected by the LLM-authored pre-intent focus now avoids
  redundant fast and full intent passes.
- Made manifest-backed action-plan parameters compatible with OpenAI strict
  Structured Outputs through a closed nullable JSON-string boundary that is
  decoded back to canonical `params` before manifest validation.
- Migrated the official page-apply HTTP proof from the obsolete synchronous
  preview path to the canonical persisted authoring-turn result, forwarding the
  required `streamId` and `resultEventId` lineage before materialization.
- Unified the full OpenAI HTTP smoke and deterministic domain-rule-only smoke
  on the same server-authenticated maker/checker identities, while keeping the
  corporate IAM rejection assertion specific to the corporate-only gate.
- Added a deterministic domain-rule-only mode to the official HTTP smoke so a
  rules release gate does not depend on an unrelated external LLM call.
- Made synchronous OpenAI Responses consumption forward-compatible with output
  union evolution by using the official SDK raw-response surface and projecting
  only the stable fields consumed by Praxis, without retries or raw-payload logs.
- Made the agentic semantic-intent schema compatible with OpenAI strict
  Structured Outputs by closing nested objects, requiring every declared field
  and representing optional values as nullable types. The OpenAI adapter now
  rejects incompatible schemas locally before issuing a provider request.
- Updated the Quickstart domain-rule HTTP lifecycle proof to derive author and
  reviewer from distinct server identities, assert self-approval rejection and
  stop sending the removed caller-supplied definition actor fields.
- Fixed table runtime operation compilation so selected-record related-surface
  requests can produce `tableRuntimeOperations` with `surfaceId` instead of
  falling back to column clarification.
- Prevented inactive, reverted or superseded original evidence from remaining
  eligible for Project Knowledge authoring influence.
- Fixed vector-ranked Project Knowledge retrieval reloading concepts without
  `sourceRelease`, which could otherwise fail safe projection building outside
  an active persistence session.
- Ensured Project Knowledge vector lifecycle behavior removes reverted evidence
  documents and keeps replacement evidence documents only when the replacement
  remains active.
- Fixed agentic authoring test artifact path resolution so local release-like
  gates prefer this repository's `docs/ai/agentic-authoring/**` artifacts and
  only fall back to monorepo-level docs when expected files exist there.

### Validated
- The official OpenAI `gpt-5.4-mini` HTTP intent gate reached
  `route_required` with LLM-authored quick replies after the raw-response
  projection fix; the later lifecycle failure was isolated to a stale
  same-identity maker-checker fixture.
- OpenAI provider focal tests passed with `19/19`, including an incomplete,
  unconsumed output variant alongside a valid assistant message; the combined
  provider and semantic-intent resolver gate passed with `45/45` tests.
- OpenAI strict-schema/provider and semantic-intent gates passed with `45/45`
  and `323/323` tests; the `ci-smoke-unit` profile passed with `1,998/1,998`
  tests and the quickstart packaged against the locally installed starter.
- Official runtime tool plan readonly-beta smoke battery passed locally against
  real Angular, real Quickstart and Neon/Postgres with `15/15` scenarios,
  `0` retries, no raw leaks, no partial terminal reads on fail-closed paths,
  and no leftover `4003`/`8088` listeners or smoke/Chromium processes.
- Focal `AiOrchestratorServiceContextHintsTest` passed after adding
  `dynamicPage.surface.open` runtime-operation coverage.
- Focal agentic authoring tests passed for filter capability catalog loading,
  component capability exposure and search/master-detail page composition.
- Prepared local `0.1.0-rc.38` Maven alignment for downstream quickstart
  packaging without Maven Central publication.
- Focal starter tests passed for Domain Knowledge lifecycle validation, Project
  Knowledge active-evidence filtering, vector index publication, vector-ranked
  retrieval and RAG metadata.
- `praxis-api-quickstart` packaged against the locally installed starter without
  Maven Central publication and proved the vector path with Neon-backed
  persistence and `PgVectorStore`.
- Quickstart strict vector revert smoke passed with
  `REQUIRE_PROJECT_KNOWLEDGE_VECTOR_RETRIEVAL=true`, proving vector document
  count `1` after `add_evidence`, authoring retrieval present after add, vector
  document count `0` after revert and no authoring retrieval after revert.
- Quickstart strict vector supersession smoke passed with
  `REQUIRE_PROJECT_KNOWLEDGE_VECTOR_RETRIEVAL=true` and
  `REQUIRE_EVIDENCE_SUPERSESSION=true`, proving the original evidence vector
  document is removed while replacement evidence and authoring retrieval remain
  active.
- Local `ci-smoke-unit` release-like gate passed with `775` tests, `0`
  failures, `0` errors and `0` skipped after the test artifact path fix.
- No GitHub Actions, Maven Central publication, npm publication or hosted smoke
  was used for this Unreleased checkpoint.

## [0.1.0-rc.8] - 2026-04-22

### Added
- AI API contract schema now types `contextHints.domainCatalog` in more detail,
  including relationship query hints used by authoring prompt construction.

### Changed
- Generated AI contract bindings now preserve the richer domain catalog hint
  shape so Angular consumers can send typed relationship context requests
  instead of unstructured JSON blobs.

### Validated
- `praxis-api-quickstart` consumes `praxis-config-starter` `0.1.0-rc.8`
  from Maven Central and passed `mvn -B verify`.
- Remote `Agentic Authoring HTTP Smoke` passed with `run_page_builder_full_e2e=true`
  across config starter, metadata starter, quickstart and praxis-ui-angular
  `main` in run `24771109354`.

## [0.1.0-rc.7] - 2026-04-22

### Added
- Latest domain catalog lookups can now federate across the latest release of
  each service when `serviceKey` is omitted.
- New `GET /api/praxis/config/domain-catalog/relationships/latest` endpoint for
  deterministic lookup of explicit domain catalog `edge` relationships.
- Authoring prompt context can now include a dedicated
  `DOMAIN_CATALOG_RELATIONSHIPS` block from `contextHints.domainCatalog.relationships`.

### Changed
- Domain catalog relationship retrieval remains explicit: it does not synthesize
  relationships from labels, aliases or similarly named fields.
- Quickstart Domain Catalog v2 HTTP smoke now verifies projected explicit
  relationships through the config starter runtime endpoint.

### Validated
- Local quickstart Domain Catalog v2 HTTP smoke passed against the locally
  installed starter with `explicitRelationshipSeen=true`.
- Local quickstart Agentic Authoring HTTP/SSE smoke passed with OpenAI.
- Remote `Agentic Authoring HTTP Smoke` passed with `run_page_builder_full_e2e=true`
  across config starter, metadata starter, quickstart and praxis-ui-angular
  `main`.

## [0.1.0-rc.6] - 2026-04-22

### Added
- Domain catalog contract `praxis.domain-catalog/v0.2` with packaged runtime
  JSON Schema and matching documentation schema.
- Runtime JSON Schema validation before domain catalog persistence, covering
  published v0.1 payloads and governed v0.2 payloads.
- Quickstart Domain Catalog v0.2 HTTP smoke script covering runtime emission,
  ingestion and projected node/alias/governance retrieval.

### Changed
- The `Agentic Authoring HTTP Smoke` workflow now checks out and installs
  `praxis-metadata-starter` locally, packages the quickstart against both local
  starters and runs the Domain Catalog v0.2 HTTP smoke as part of the remote
  gate.
- Domain catalog ingestion now rejects unsupported schema versions and invalid
  payloads before writing `domain_catalog_release` or `domain_catalog_item`.
- Domain catalog prompt context now carries governed v0.2 semantics such as
  semantic owner, lifecycle, business glossary, resolution, source evidence and
  aliases into authoring/LLM prompt hints.

### Validated
- `praxis-api-quickstart` consumes `praxis-metadata-starter` `8.0.0-rc.13`
  and validates generated domain catalogs against this starter's schema
  contract.
- Runtime ingestion and read-only governance context verification passed for
  human resources, operations and procurement domain resources.

## [0.1.0-rc.5] - 2026-04-22

### Fixed
- Generated TypeScript AI contracts now keep `AiJsonObject` as strict JSON while modeling `AiContextHintsContract` as the extensible envelope for domain catalog hints.
- Generated context-hint types now stay compatible with `@praxisui/ai` and page-builder consumers without widening all JSON objects to `undefined`.

### Validated
- Post-merge local authoring gate passed with quickstart HTTP/SSE smoke and page-builder full E2E against the locally installed starter.

### Release Coordination
- Advanced directly to `0.1.0-rc.5` because remote release tags `v0.1.0-rc.3` and `v0.1.0-rc.4` already exist.

## [0.1.0-rc.2] - 2026-04-21

### Added
- Full page-builder agentic E2E gate option in the authoring smoke workflow.
- Release guidance for running the page-builder full gate before publishing authoring-sensitive releases.
- Domain catalog prompt context enrichment for governed surface/resource/action selection.

### Changed
- Authoring smoke workflow now uses a pinned quickstart ref by default to keep starter release validation independent from unpublished downstream dependencies.
- Payroll dashboard confirmation accepts the canonical payroll collection candidate as well as the analytics view candidate.

## [0.1.0-rc.1] - 2026-04-21

### Added
- Domain Catalog Foundation with Flyway V17 tables `domain_catalog_release` and `domain_catalog_item`.
- Runtime ingestion and retrieval endpoints under `/api/praxis/config/domain-catalog`.
- LLM-ready domain context response for semantic vocabulary retrieval.
- Prompt context bridge from `contextHints.domainCatalog` into AI orchestration.
- Agentic authoring quick replies enriched with `domainCatalog` hints.
- Configurable `praxis.domain-catalog.service-key` for host applications.

### Changed
- RAG resource typing now includes `domain_catalog`.
- Domain catalog persistence remains resilient when vector publication is unavailable.

## [0.0.1] - 2025-12-02

### Added
- **Core Domain:** Entities `ApiMetadata`, `ConfigEntry`, `UiConfiguration` mapped to PostgreSQL.
- **Vector Support:** JPA Converter `VectorConverter` handling `vector(768)` types for semantic search.
- **Ingestion:** `RegistryIngestionController` for loading API metadata into the system.
- **Flyway:** Initial migrations V1, V2 (vector enablement), and V4 (metadata schema).

### Pending
- Second-level cache for high-frequency read configurations.
- Visual administration interface for editing `UiConfiguration` directly.
- Strong typing for Ingestion Controller inputs.
