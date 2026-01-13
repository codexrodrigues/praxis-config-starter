CREATE TABLE config_entries (
  id UUID PRIMARY KEY,
  config_key VARCHAR(255) NOT NULL UNIQUE,
  config_value VARCHAR(1024) NOT NULL
);

CREATE TABLE ai_registry (
  id UUID PRIMARY KEY,
  registry_type VARCHAR(64) NOT NULL,
  registry_key VARCHAR(255) NOT NULL,
  component_type VARCHAR(64),
  scope VARCHAR(32) NOT NULL,
  scope_key VARCHAR(255) NOT NULL,
  payload jsonb NOT NULL,
  version BIGINT NOT NULL,
  etag UUID NOT NULL,
  tags jsonb,
  source VARCHAR(64),
  source_ref VARCHAR(255),
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  created_by VARCHAR(255),
  updated_by VARCHAR(255),
  embedding vector
);
