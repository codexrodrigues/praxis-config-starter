ALTER TABLE api_metadata
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(128) NOT NULL DEFAULT 'GLOBAL',
    ADD COLUMN IF NOT EXISTS environment VARCHAR(128) NOT NULL DEFAULT 'default',
    ADD COLUMN IF NOT EXISTS service_key VARCHAR(255) NOT NULL DEFAULT 'default',
    ADD COLUMN IF NOT EXISTS release_id VARCHAR(255) NOT NULL DEFAULT 'v1',
    ADD COLUMN IF NOT EXISTS release_version VARCHAR(255),
    ADD COLUMN IF NOT EXISTS generated_at VARCHAR(255);

ALTER TABLE api_metadata
    DROP CONSTRAINT IF EXISTS uk_api_metadata_path_method;

ALTER TABLE api_metadata
    DROP CONSTRAINT IF EXISTS uk_api_metadata_scope_path_method;

ALTER TABLE api_metadata
    ADD CONSTRAINT uk_api_metadata_scope_path_method
        UNIQUE (tenant_id, environment, service_key, release_id, path, method);

CREATE INDEX IF NOT EXISTS idx_api_metadata_scope
    ON api_metadata (tenant_id, environment, service_key, release_id);

CREATE INDEX IF NOT EXISTS idx_api_metadata_scope_operation
    ON api_metadata (tenant_id, environment, service_key, release_id, operation_id, method)
    WHERE operation_id IS NOT NULL;
