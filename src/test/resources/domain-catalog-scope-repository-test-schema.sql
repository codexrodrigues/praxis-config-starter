CREATE TYPE IF NOT EXISTS jsonb AS TEXT;

CREATE TABLE domain_catalog_release (
  id UUID PRIMARY KEY,
  release_key VARCHAR(255) NOT NULL,
  schema_version VARCHAR(64) NOT NULL,
  service_key VARCHAR(255),
  service_name VARCHAR(255),
  service_version VARCHAR(64),
  resource_key VARCHAR(255),
  generated_at TIMESTAMP WITH TIME ZONE,
  source_hash VARCHAR(128),
  tenant_id VARCHAR(128),
  environment VARCHAR(128),
  raw_payload jsonb NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX uk_domain_catalog_release_scope_key
  ON domain_catalog_release (tenant_id, environment, release_key);

CREATE TABLE domain_catalog_item (
  id UUID PRIMARY KEY,
  release_id UUID NOT NULL REFERENCES domain_catalog_release(id) ON DELETE CASCADE,
  item_type VARCHAR(32) NOT NULL,
  item_key VARCHAR(512) NOT NULL,
  context_key VARCHAR(255),
  node_type VARCHAR(64),
  binding_type VARCHAR(64),
  edge_type VARCHAR(64),
  payload jsonb NOT NULL,
  searchable_text TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_domain_catalog_item UNIQUE (release_id, item_type, item_key)
);
