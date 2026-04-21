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
