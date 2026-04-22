# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

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
