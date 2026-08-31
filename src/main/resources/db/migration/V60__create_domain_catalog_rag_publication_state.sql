CREATE TABLE domain_catalog_rag_publication_state (
    release_id UUID PRIMARY KEY REFERENCES domain_catalog_release(id) ON DELETE CASCADE,
    lock_version BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    expected_document_count BIGINT NOT NULL DEFAULT 0,
    published_document_count BIGINT NOT NULL DEFAULT 0,
    failure_kind VARCHAR(80),
    retryable BOOLEAN,
    retry_after TIMESTAMP WITH TIME ZONE,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_domain_catalog_rag_publication_status
        CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_domain_catalog_rag_publication_recovery
    ON domain_catalog_rag_publication_state (status, requested_at);
