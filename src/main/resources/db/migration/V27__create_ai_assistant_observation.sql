CREATE TABLE IF NOT EXISTS ai_assistant_observation (
    observation_id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128),
    user_id VARCHAR(128),
    request_id VARCHAR(128),
    surface VARCHAR(64) NOT NULL,
    component_id VARCHAR(255),
    component_type VARCHAR(64),
    route_key VARCHAR(255),
    variant_id VARCHAR(128),
    schema_hash VARCHAR(128),
    contract_version VARCHAR(64),
    session_id UUID,
    client_turn_id UUID,
    thread_id UUID,
    turn_id UUID,
    stream_id UUID,
    prompt_hash VARCHAR(128) NOT NULL,
    prompt_preview TEXT,
    prompt_length INT,
    prompt_redacted BOOLEAN NOT NULL DEFAULT TRUE,
    admission_outcome VARCHAR(64) NOT NULL DEFAULT 'captured',
    terminal_outcome VARCHAR(64),
    quality_outcome VARCHAR(64) NOT NULL DEFAULT 'unresolved',
    error_category VARCHAR(64),
    error_code VARCHAR(128),
    error_message_preview TEXT,
    provider VARCHAR(64),
    model VARCHAR(128),
    llm_call_count INT NOT NULL DEFAULT 0,
    latency_ms BIGINT,
    token_estimate INT,
    cost_estimate_micros BIGINT,
    safe_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_assistant_observation_safe_metadata_object
        CHECK (jsonb_typeof(safe_metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_recent
    ON ai_assistant_observation (tenant_id, environment, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_quality
    ON ai_assistant_observation (tenant_id, environment, quality_outcome, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_component
    ON ai_assistant_observation (
        tenant_id,
        environment,
        component_id,
        admission_outcome,
        terminal_outcome,
        created_at DESC
    );

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_prompt_hash
    ON ai_assistant_observation (tenant_id, environment, prompt_hash, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_request
    ON ai_assistant_observation (request_id);

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_turn
    ON ai_assistant_observation (thread_id, turn_id)
    WHERE thread_id IS NOT NULL AND turn_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_stream
    ON ai_assistant_observation (stream_id)
    WHERE stream_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_error
    ON ai_assistant_observation (tenant_id, environment, error_category, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_assistant_observation_feedback (
    feedback_id UUID PRIMARY KEY,
    observation_id UUID NOT NULL REFERENCES ai_assistant_observation(observation_id) ON DELETE CASCADE,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128),
    user_id VARCHAR(128),
    rating VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    comment_preview TEXT,
    safe_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_assistant_observation_feedback_metadata_object
        CHECK (jsonb_typeof(safe_metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_feedback_observation
    ON ai_assistant_observation_feedback (observation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_feedback_recent
    ON ai_assistant_observation_feedback (tenant_id, environment, rating, created_at DESC);
