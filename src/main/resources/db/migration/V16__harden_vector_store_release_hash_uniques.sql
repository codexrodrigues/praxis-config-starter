-- C-01/C-02: enforce deterministic release-scoped uniqueness for RAG documents.

WITH ranked_rows AS (
    SELECT
        ctid,
        row_number() OVER (
            PARTITION BY
                COALESCE(metadata ->> 'tenantId', 'global'),
                COALESCE(metadata ->> 'environment', 'global'),
                COALESCE(metadata ->> 'releaseId', metadata ->> 'version', 'v1'),
                COALESCE(metadata ->> 'componentId', metadata ->> 'resourceId', id),
                COALESCE(metadata ->> 'docType', metadata ->> 'resourceType', 'unknown-doc'),
                COALESCE(metadata ->> 'contentHash', md5(COALESCE(content, ''))),
                CASE
                    WHEN COALESCE(metadata ->> 'chunkIndex', '') ~ '^-?[0-9]+$'
                        THEN GREATEST((metadata ->> 'chunkIndex')::int, 0)
                    ELSE 0
                END
            ORDER BY id
        ) AS duplicate_rank
    FROM vector_store
)
DELETE FROM vector_store v
USING ranked_rows r
WHERE v.ctid = r.ctid
  AND r.duplicate_rank > 1;

CREATE UNIQUE INDEX IF NOT EXISTS idx_vector_store_scope_release_hash_chunk_unique
    ON vector_store (
        COALESCE(metadata ->> 'tenantId', 'global'),
        COALESCE(metadata ->> 'environment', 'global'),
        COALESCE(metadata ->> 'releaseId', metadata ->> 'version', 'v1'),
        COALESCE(metadata ->> 'componentId', metadata ->> 'resourceId', id),
        COALESCE(metadata ->> 'docType', metadata ->> 'resourceType', 'unknown-doc'),
        COALESCE(metadata ->> 'contentHash', md5(COALESCE(content, ''))),
        (
            CASE
            WHEN COALESCE(metadata ->> 'chunkIndex', '') ~ '^-?[0-9]+$'
                THEN GREATEST((metadata ->> 'chunkIndex')::int, 0)
            ELSE 0
            END
        )
    );

CREATE INDEX IF NOT EXISTS idx_vector_store_scope_lookup
    ON vector_store (
        COALESCE(metadata ->> 'tenantId', 'global'),
        COALESCE(metadata ->> 'environment', 'global'),
        COALESCE(metadata ->> 'releaseId', metadata ->> 'version', 'v1'),
        COALESCE(metadata ->> 'resourceType', 'unknown')
    );

CREATE INDEX IF NOT EXISTS idx_vector_store_component_lookup
    ON vector_store (
        COALESCE(metadata ->> 'componentId', metadata ->> 'resourceId', id),
        COALESCE(metadata ->> 'docType', metadata ->> 'resourceType', 'unknown-doc')
    );
