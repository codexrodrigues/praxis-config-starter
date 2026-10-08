# Internal metadata capture storage

Owner: Config. `JdbcUiLayoutMetadataCaptureStore` is package-private and is not a bean, public SPI, source provider or endpoint. The canonical command-service factory now composes it internally. V66 prepares `ui_layout_baseline_metadata_evidence`; no historical data is copied. No producer authority or compatibility claim follows from storing a declared ref.

## Mapping and integrity

Capture parsing and JDBC serialization use the same private strict mapper configuration, independently of host JSON defaults. All permissive JsonReadFeature options are disabled; duplicate keys, trailing tokens, depth and UTF-8 byte limits are checked. Tree reads and writes preserve explicit null properties and insertion order. NativeIdentity compares that ordered tree with B0, not a host-normalized or alphabetically sorted copy. The host mapper is copied and remains unchanged. This boundary is syntax/integrity enforcement, not schema validation or producer admission.

One immutable row per source draft/native target: capture UUID, exact tenant/environment/root/target, B0 source/hash/descriptor and document text, raw schema text and UTF-8 digest, structural operation/publication, reproduction refs and observation context. Raw schema is TEXT, never JSONB: formatting and property order remain input evidence. JSON casts in CHECK constraints validate object syntax without replacing the stored text. B0 text is also stored separately from the mutable draft. `captured_at_text` preserves the exact Java Instant, including nanoseconds; it is not a trusted DB commit clock.

A composite FK pins the draft scope. Unique draft/target and capture UUID prevent replacement; UPDATE, DELETE and TRUNCATE are rejected. Insert locks the scoped editable draft before checking the 1 MiB raw-body quota. Bodies/B0 each have 256 KiB limits; quota excludes B0 copies and overhead. UTF-8 database encoding and READ COMMITTED isolation are required. Other isolation modes are denied, because the quota proof depends on fresh snapshots after acquiring the draft lock. Tests against a real PostgreSQL server are required to prove the trigger and concurrency behavior.

The JDBC append boundary requires an actual Config datasource transaction, its synchronized connection holder and the writer TransactionStatus. It reads/locks the authoritative draft in exact scope and verifies complete registered target order, B0/source/descriptor, hashes, observation actor/unit/context and content access before inserting. A failed insert or final revocation marks both the connection holder and writer status rollback-only, even if the caller catches the exception. The caller must pass the status supplied by the Config TransactionOperations callback; an unrelated or fabricated status is not a valid integration. This internal boundary cannot resolve the authenticated session itself.

Reads query only exact draft/tenant/environment/root/target and verify the expected baseline binding plus current access. They never query latest api_metadata or recapture missing history. Historical observation is not current permission. Replay belongs in createDraft's existing idempotency branch: read and verify the already stored rows, never invoke append or producer capture again. A duplicate insert fails; no upsert/ON CONFLICT fallback exists.

## Remaining integration gates

The canonical writer now appends after draft flush in the same Config transaction, with explicit TransactionStatus, full context revalidation and current metadata admission; replay reads the existing captures without source.capture. See [writer composition](ui-layout-baseline-metadata-writer.md). An actual host producer and runtime delivery of the admitted snapshot to Table remain pending. Never backfill historical drafts or replace evidence with mutable current metadata.

Before publication or operational use, execute V64/V65/V66/V67/V68 on an approved existing PostgreSQL environment: composite FK mismatch, immutable mutation/TRUNCATE, duplicate identity, released draft insertion, multibyte bounds, invalid JSON, raw-text roundtrip, quota at/above limit, concurrent writers/lifecycle transitions, wrong isolation/encoding and real rollback after partial insert/final denial. No DB, Docker or paused monitor is created/reactivated by this package. Unit mocks prove the Java/JDBC boundary and explicit rollback markers only, not database behavior.

Design references: [PostgreSQL trigger data visibility](https://www.postgresql.org/docs/17/trigger-datachanges.html) and [SPI visibility](https://www.postgresql.org/docs/current/spi-visibility.html). The trigger uses the default VOLATILE function semantics; documentation review is not executable DB proof.

## Origin variants (V67)

V67 preserves V66 and adds origin_kind plus native_document_ref/revision. OPERATION_SCHEMA requires structural operation/schema and normalizer/projector; NATIVE_DOCUMENT requires native document identity/revision and forbids structural fields. SQL and row decoding reject unknown/mixed declarations. Existing rows receive the operation discriminator through ALTER ADD DEFAULT; the default is then removed, without rewriting payloads or consulting live sources. Immutability, FK, locking and quota triggers remain intact. Physical V66 raw_schema_text/hash columns retain their names and store either mandatory input kind; Java uses rawInputText/hash. NativeIdentity is verified by the codec using ordered parsed-tree traversal against B0, not JSONB/canonical hash equality. Native transformations are unsupported. PostgreSQL execution remains an explicit unexecuted gate.


## Full document assembly inputs (V68)

See the [focal PostgreSQL gate and coverage matrix](ui-layout-metadata-postgres-gate.md). The opt-in SQL gate requires an existing exclusively allocated schema migrated through V68; compilation is not database proof.

Operation captures require assembly_input_version=1, assembler_ref and exact assembly_input_text/hash, separately from raw_schema_text/hash. Java applies the same strict syntax/Unicode/depth/byte gates and verifies SHA-256 on replay. Native identity rows forbid all four fields. No raw input enters receipts or a new HTTP route.

V68 adds a NOT VALID completeness constraint: historical immutable operation rows remain stored but are denied by the reader if incomplete; new inserts require complete assembly inputs. No current-template backfill is permitted. The insert guard retains the scoped editable draft lock, UTF8/read-committed requirements and now sums raw schema plus assembly input bytes against 1 MiB. Each text remains bounded to 256 KiB. Existing FK, uniqueness and mutation/TRUNCATE protections are unchanged. PostgreSQL execution remains pending, including historical-row migration, null/UNKNOWN rejection, native/operation separation, exact quota/one-byte overflow, concurrent inserts and actual rollback.
