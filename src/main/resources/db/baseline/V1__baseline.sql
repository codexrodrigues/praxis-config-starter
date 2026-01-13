-- Baseline schema for clean installs (ai_registry + runtime config + api metadata)
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS config_entries (
    id UUID PRIMARY KEY,
    config_key VARCHAR(255) NOT NULL UNIQUE,
    config_value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS api_metadata (
    id BIGSERIAL PRIMARY KEY,
    path VARCHAR(1024) NOT NULL,
    method VARCHAR(16) NOT NULL,
    tags TEXT,
    summary TEXT,
    description TEXT,
    operation_id TEXT,
    request_schema TEXT,
    response_schema TEXT,
    parameters TEXT,
    raw_json TEXT,
    embedding vector(768),
    CONSTRAINT uk_api_metadata_path_method UNIQUE (path, method)
);

CREATE INDEX IF NOT EXISTS idx_api_metadata_path ON api_metadata(path);

DO $$
DECLARE
    api_dim INTEGER := (
        SELECT atttypmod
        FROM pg_attribute
        WHERE attrelid = 'api_metadata'::regclass AND attname = 'embedding'
    );
BEGIN
    IF api_dim IS NOT NULL AND api_dim > 0 THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_api_metadata_embedding ON api_metadata USING ivfflat (embedding vector_l2_ops) WITH (lists = 100)';
    ELSE
        RAISE NOTICE 'Skipping ivfflat index on api_metadata.embedding because dimension is not set';
    END IF;
END$$;

CREATE TABLE IF NOT EXISTS ui_user_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255),
    component_type VARCHAR(64) NOT NULL,
    component_id VARCHAR(255) NOT NULL,
    environment VARCHAR(64),
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    etag UUID NOT NULL DEFAULT gen_random_uuid(),
    tags JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(255),
    CONSTRAINT uk_ui_user_config UNIQUE (tenant_id, user_id, component_type, component_id, environment)
);

CREATE INDEX IF NOT EXISTS idx_ui_user_config_lookup ON ui_user_config (tenant_id, component_type, component_id);
CREATE INDEX IF NOT EXISTS idx_ui_user_config_user ON ui_user_config (tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_ui_user_config_env ON ui_user_config (tenant_id, environment);

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
