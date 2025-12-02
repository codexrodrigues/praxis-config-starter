-- Enable pgvector extension and create tables used for RAG
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS component_definition (
    id VARCHAR(255) PRIMARY KEY,
    description TEXT,
    json_schema TEXT,
    embedding vector
);

CREATE TABLE IF NOT EXISTS ui_configuration (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    app_id VARCHAR(255) NOT NULL,
    resource_path VARCHAR(1024) NOT NULL,
    component_id VARCHAR(255) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    scope_key VARCHAR(255),
    config_json TEXT NOT NULL,
    ai_description TEXT,
    embedding vector,
    CONSTRAINT uk_ui_configuration UNIQUE (tenant_id, app_id, component_id, scope, scope_key)
);

-- Vector indexes for similarity search (only if dimensions are set on the column)
DO $$
DECLARE
    comp_dim INTEGER := (SELECT atttypmod FROM pg_attribute WHERE attrelid = 'component_definition'::regclass AND attname = 'embedding');
BEGIN
    IF comp_dim IS NOT NULL AND comp_dim > 0 THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_component_definition_embedding ON component_definition USING ivfflat (embedding vector_l2_ops) WITH (lists = 100)';
    ELSE
        RAISE NOTICE 'Skipping ivfflat index on component_definition.embedding because dimension is not set';
    END IF;
END$$;

DO $$
DECLARE
    ui_dim INTEGER := (SELECT atttypmod FROM pg_attribute WHERE attrelid = 'ui_configuration'::regclass AND attname = 'embedding');
BEGIN
    IF ui_dim IS NOT NULL AND ui_dim > 0 THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_ui_configuration_embedding ON ui_configuration USING ivfflat (embedding vector_l2_ops) WITH (lists = 100)';
    ELSE
        RAISE NOTICE 'Skipping ivfflat index on ui_configuration.embedding because dimension is not set';
    END IF;
END$$;
