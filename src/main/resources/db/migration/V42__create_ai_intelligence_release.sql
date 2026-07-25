CREATE TABLE IF NOT EXISTS ai_intelligence_release (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expected_component_count INTEGER NOT NULL,
    expected_component_hash VARCHAR(64) NOT NULL,
    expected_template_count INTEGER NOT NULL,
    expected_template_hash VARCHAR(64) NOT NULL,
    expected_chunk_count BIGINT NOT NULL,
    embedding_profile VARCHAR(255) NOT NULL,
    observed_component_count INTEGER,
    observed_component_hash VARCHAR(64),
    observed_template_count INTEGER,
    observed_template_hash VARCHAR(64),
    observed_chunk_count BIGINT,
    producer_ref VARCHAR(255),
    failure_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ,
    CONSTRAINT uk_ai_intelligence_release UNIQUE (tenant_id, environment, release_id),
    CONSTRAINT ck_ai_intelligence_release_status CHECK (status IN ('STAGING','ACTIVE','FAILED','SUPERSEDED')),
    CONSTRAINT ck_ai_intelligence_release_expected_counts CHECK (
      expected_component_count >= 0 AND expected_template_count >= 0 AND expected_chunk_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_intelligence_release_active
    ON ai_intelligence_release (tenant_id, environment)
    WHERE status = 'ACTIVE';

