-- Shared multi-instance AI provider rate-limit / capacity cooldown registry.
CREATE TABLE IF NOT EXISTS ai_provider_rate_limit_cooldown (
    cooldown_key VARCHAR(255) PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(160),
    retry_after TIMESTAMP WITH TIME ZONE NOT NULL,
    reason VARCHAR(512),
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_provider_cooldown_provider_retry
    ON ai_provider_rate_limit_cooldown (provider, retry_after);

-- Shared multi-instance SSE stream capacity lease table with automatic TTL expiration.
CREATE TABLE IF NOT EXISTS ai_authoring_stream_capacity_lease (
    stream_id VARCHAR(64) PRIMARY KEY,
    tenant_key VARCHAR(255) NOT NULL,
    user_key VARCHAR(255) NOT NULL,
    reserved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_authoring_stream_lease_expires
    ON ai_authoring_stream_capacity_lease (expires_at);

CREATE INDEX IF NOT EXISTS idx_ai_authoring_stream_lease_tenant_expires
    ON ai_authoring_stream_capacity_lease (tenant_key, expires_at);

CREATE INDEX IF NOT EXISTS idx_ai_authoring_stream_lease_user_expires
    ON ai_authoring_stream_capacity_lease (tenant_key, user_key, expires_at);
