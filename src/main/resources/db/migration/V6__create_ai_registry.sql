-- Unified AI registry for templates/capabilities/metadata (SYSTEM/GLOBAL)
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS ai_registry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    registry_type VARCHAR(64) NOT NULL,
    registry_key VARCHAR(255) NOT NULL,
    component_type VARCHAR(64),
    scope VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    scope_key VARCHAR(255) NOT NULL DEFAULT 'GLOBAL',
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    etag UUID NOT NULL DEFAULT gen_random_uuid(),
    tags JSONB,
    source VARCHAR(64),
    source_ref VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    embedding vector,
    CONSTRAINT uk_ai_registry UNIQUE (registry_type, registry_key, component_type, scope, scope_key),
    CONSTRAINT ck_ai_registry_scope_global CHECK (scope = 'SYSTEM' AND scope_key = 'GLOBAL')
);

CREATE INDEX IF NOT EXISTS idx_ai_registry_lookup ON ai_registry (registry_type, registry_key);
CREATE INDEX IF NOT EXISTS idx_ai_registry_component ON ai_registry (component_type);
CREATE INDEX IF NOT EXISTS idx_ai_registry_scope ON ai_registry (scope, scope_key);

-- Vector index only when dimension is set on the column
DO $$
DECLARE
    reg_dim INTEGER := (
        SELECT atttypmod
        FROM pg_attribute
        WHERE attrelid = 'ai_registry'::regclass AND attname = 'embedding'
    );
BEGIN
    IF reg_dim IS NOT NULL AND reg_dim > 0 THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_ai_registry_embedding ON ai_registry USING ivfflat (embedding vector_l2_ops) WITH (lists = 100)';
    ELSE
        RAISE NOTICE 'Skipping ivfflat index on ai_registry.embedding because dimension is not set';
    END IF;
END$$;
