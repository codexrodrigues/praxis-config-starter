CREATE TABLE api_metadata_indexing_state (
    id BIGSERIAL PRIMARY KEY,
    lock_version BIGINT NOT NULL DEFAULT 0,
    tenant_id VARCHAR(255) NOT NULL,
    environment VARCHAR(255) NOT NULL,
    service_key VARCHAR(255) NOT NULL,
    release_id VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    expected_document_count BIGINT NOT NULL DEFAULT 0,
    legacy_indexed_document_count BIGINT NOT NULL DEFAULT 0,
    published_document_count BIGINT NOT NULL DEFAULT 0,
    failure_code VARCHAR(80),
    failure_message VARCHAR(320),
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_api_metadata_indexing_scope
        UNIQUE (tenant_id, environment, service_key, release_id),
    CONSTRAINT ck_api_metadata_indexing_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED'))
);

CREATE INDEX idx_api_metadata_indexing_recovery
    ON api_metadata_indexing_state (status, requested_at);
