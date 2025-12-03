# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
