CREATE TYPE IF NOT EXISTS jsonb AS TEXT;

CREATE TABLE domain_catalog_release (
  id UUID PRIMARY KEY,
  release_key VARCHAR(255) NOT NULL UNIQUE,
  schema_version VARCHAR(64) NOT NULL,
  service_key VARCHAR(255),
  service_name VARCHAR(255),
  service_version VARCHAR(64),
  generated_at TIMESTAMP WITH TIME ZONE,
  source_hash VARCHAR(128),
  tenant_id VARCHAR(128),
  environment VARCHAR(128),
  raw_payload jsonb NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE domain_knowledge_concept (
  id UUID PRIMARY KEY,
  tenant_id VARCHAR(128),
  environment VARCHAR(128),
  concept_key VARCHAR(512) NOT NULL,
  context_key VARCHAR(255),
  resource_key VARCHAR(255),
  node_type VARCHAR(64) NOT NULL,
  label VARCHAR(512),
  description TEXT,
  locale VARCHAR(32),
  semantic_owner VARCHAR(255),
  steward VARCHAR(255),
  lifecycle VARCHAR(32) NOT NULL DEFAULT 'candidate',
  curation_status VARCHAR(32) NOT NULL DEFAULT 'generated',
  ai_visibility VARCHAR(32) NOT NULL DEFAULT 'allow',
  data_category VARCHAR(64),
  classification VARCHAR(64),
  compliance_tags jsonb,
  source_release_id UUID REFERENCES domain_catalog_release(id) ON DELETE SET NULL,
  payload jsonb NOT NULL DEFAULT '{}',
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

