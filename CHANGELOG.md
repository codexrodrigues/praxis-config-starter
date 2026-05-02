# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Added
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
- Project Knowledge retrieval now treats Domain Knowledge as the canonical source
  of truth and vector search as candidate ranking only.
- Vector-ranked Project Knowledge candidates are reloaded from canonical Domain
  Knowledge with `sourceRelease` before safe projection building.
- Project Knowledge RAG publication and retrieval remain opt-in beta paths and
  must not be enabled implicitly by host applications.

### Fixed
- Prevented inactive, reverted or superseded original evidence from remaining
  eligible for Project Knowledge authoring influence.
- Fixed vector-ranked Project Knowledge retrieval reloading concepts without
  `sourceRelease`, which could otherwise fail safe projection building outside
  an active persistence session.
- Ensured Project Knowledge vector lifecycle behavior removes reverted evidence
  documents and keeps replacement evidence documents only when the replacement
  remains active.

### Validated
- Focal starter tests passed for Domain Knowledge lifecycle validation, Project
  Knowledge active-evidence filtering, vector index publication, vector-ranked
  retrieval and RAG metadata.
- `praxis-api-quickstart` packaged against the locally installed starter without
  Maven Central publication and proved the vector path with Neon-backed
  persistence and `PgVectorStore`.
- Quickstart strict vector revert smoke passed with
  `REQUIRE_PROJECT_KNOWLEDGE_VECTOR_RETRIEVAL=true`, proving vector document
  count `1` after `add_evidence`, vector document count `0` after revert and no
  authoring retrieval after revert.
- Quickstart strict vector supersession smoke passed with
  `REQUIRE_PROJECT_KNOWLEDGE_VECTOR_RETRIEVAL=true` and
  `REQUIRE_EVIDENCE_SUPERSESSION=true`, proving the original evidence vector
  document is removed while replacement evidence remains active.
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
