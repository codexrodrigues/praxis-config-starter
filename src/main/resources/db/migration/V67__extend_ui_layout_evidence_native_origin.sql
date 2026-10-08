-- Preserve V66 checksum and existing immutable payloads. The added constant discriminator
-- classifies existing structural rows; it does not backfill observations from a live source.
ALTER TABLE ui_layout_baseline_metadata_evidence
    ADD COLUMN origin_kind VARCHAR(32) NOT NULL DEFAULT 'OPERATION_SCHEMA',
    ADD COLUMN native_document_ref VARCHAR(1024),
    ADD COLUMN native_document_revision VARCHAR(255);

ALTER TABLE ui_layout_baseline_metadata_evidence
    ALTER COLUMN origin_kind DROP DEFAULT,
    ALTER COLUMN resource_ref DROP NOT NULL,
    ALTER COLUMN operation_path DROP NOT NULL,
    ALTER COLUMN operation_method DROP NOT NULL,
    ALTER COLUMN schema_type DROP NOT NULL,
    ALTER COLUMN source_schema_ref DROP NOT NULL,
    ALTER COLUMN normalizer_ref DROP NOT NULL,
    ALTER COLUMN projector_ref DROP NOT NULL;

ALTER TABLE ui_layout_baseline_metadata_evidence ADD CONSTRAINT ck_ui_layout_evidence_origin CHECK (
    (origin_kind = 'OPERATION_SCHEMA'
        AND resource_ref IS NOT NULL AND operation_path IS NOT NULL AND operation_method IS NOT NULL
        AND schema_type IS NOT NULL AND source_schema_ref IS NOT NULL
        AND normalizer_ref IS NOT NULL AND projector_ref IS NOT NULL
        AND native_document_ref IS NULL AND native_document_revision IS NULL)
    OR
    (origin_kind = 'NATIVE_DOCUMENT'
        AND native_document_ref IS NOT NULL AND length(btrim(native_document_ref)) > 0
        AND native_document_revision IS NOT NULL AND length(btrim(native_document_revision)) > 0
        AND resource_ref IS NULL AND operation_path IS NULL AND operation_method IS NULL
        AND schema_type IS NULL AND source_schema_ref IS NULL AND normalizer_ref IS NULL AND projector_ref IS NULL)
);

-- Native identity reproduction must preserve input property/array order. JSONB equality
-- would discard property order. Whitespace normalization and exact validation are codec-owned;
-- raw_schema_text/hash retain their V66 physical names and apply to both admitted input kinds.
-- Existing FK, unique keys, quota/locking and immutability triggers remain unchanged.
