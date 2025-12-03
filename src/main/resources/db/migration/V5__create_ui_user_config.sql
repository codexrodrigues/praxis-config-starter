-- Storage transacional para customizações de UI por usuário/tenant
CREATE EXTENSION IF NOT EXISTS pgcrypto;

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
