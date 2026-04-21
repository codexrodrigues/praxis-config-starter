-- Manual application helper for V17__create_domain_catalog.sql.
--
-- Purpose:
--   Apply the domain catalog tables safely on the remote config database when the operator wants
--   to control the migration manually instead of letting application startup run Flyway.
--
-- Important:
--   This script is idempotent for the domain tables and indexes because it uses IF NOT EXISTS.
--   The final INSERT into flyway_schema_history is guarded and only runs when version 17 is absent.
--
-- Recommended psql usage:
--   \set ON_ERROR_STOP on
--   BEGIN;
--   -- paste/run this file
--   COMMIT;

-- 1. Pre-check current Flyway and table state.
SELECT
    version,
    description,
    script,
    success,
    installed_on
FROM flyway_schema_history
WHERE version IN ('16', '17')
ORDER BY installed_rank;

SELECT
    table_name
FROM information_schema.tables
WHERE table_schema = current_schema()
  AND table_name IN ('domain_catalog_release', 'domain_catalog_item')
ORDER BY table_name;

-- 2. Apply V17 objects.
CREATE TABLE IF NOT EXISTS domain_catalog_release (
    id UUID PRIMARY KEY,
    release_key VARCHAR(255) NOT NULL UNIQUE,
    schema_version VARCHAR(64) NOT NULL,
    service_key VARCHAR(255),
    service_name VARCHAR(255),
    service_version VARCHAR(64),
    generated_at TIMESTAMPTZ,
    source_hash VARCHAR(128),
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    raw_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS domain_catalog_item (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES domain_catalog_release(id) ON DELETE CASCADE,
    item_type VARCHAR(32) NOT NULL,
    item_key VARCHAR(512) NOT NULL,
    context_key VARCHAR(255),
    node_type VARCHAR(64),
    binding_type VARCHAR(64),
    edge_type VARCHAR(64),
    payload JSONB NOT NULL,
    searchable_text TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_domain_catalog_item UNIQUE (release_id, item_type, item_key)
);

CREATE INDEX IF NOT EXISTS idx_domain_catalog_release_scope
    ON domain_catalog_release (tenant_id, environment, service_key, generated_at DESC);

CREATE INDEX IF NOT EXISTS idx_domain_catalog_item_lookup
    ON domain_catalog_item (release_id, item_type, context_key);

CREATE INDEX IF NOT EXISTS idx_domain_catalog_item_node_type
    ON domain_catalog_item (release_id, node_type)
    WHERE node_type IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_domain_catalog_item_search
    ON domain_catalog_item USING gin (to_tsvector('simple', COALESCE(searchable_text, '')));

-- 3. Mark V17 as applied when this script is used instead of Flyway.
--
-- checksum is intentionally NULL because this is a manual registration. Flyway accepts NULL
-- checksums for SQL entries in schema history, but if your operational policy requires strict
-- checksum tracking, let the application run Flyway instead of inserting this row manually.
INSERT INTO flyway_schema_history (
    installed_rank,
    version,
    description,
    type,
    script,
    checksum,
    installed_by,
    execution_time,
    success
)
SELECT
    COALESCE((SELECT MAX(installed_rank) FROM flyway_schema_history), 0) + 1,
    '17',
    'create domain catalog',
    'SQL',
    'V17__create_domain_catalog.sql',
    NULL,
    current_user,
    0,
    TRUE
WHERE NOT EXISTS (
    SELECT 1
    FROM flyway_schema_history
    WHERE version = '17'
);

-- 4. Post-check.
SELECT
    version,
    description,
    script,
    success,
    installed_on
FROM flyway_schema_history
WHERE version IN ('16', '17')
ORDER BY installed_rank;

SELECT
    table_name
FROM information_schema.tables
WHERE table_schema = current_schema()
  AND table_name IN ('domain_catalog_release', 'domain_catalog_item')
ORDER BY table_name;

SELECT
    indexname
FROM pg_indexes
WHERE schemaname = current_schema()
  AND tablename IN ('domain_catalog_release', 'domain_catalog_item')
ORDER BY tablename, indexname;
