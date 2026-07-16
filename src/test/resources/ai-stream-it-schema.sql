CREATE DOMAIN IF NOT EXISTS jsonb AS VARCHAR(20000);

DROP TABLE IF EXISTS ai_turn_event;
DROP TABLE IF EXISTS ai_turn;
DROP TABLE IF EXISTS ai_thread;
DROP TABLE IF EXISTS ai_assistant_observation_feedback;
DROP TABLE IF EXISTS ai_assistant_observation;

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
    safe_metadata jsonb NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
    ON ai_assistant_observation (thread_id, turn_id);

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_stream
    ON ai_assistant_observation (stream_id);

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_error
    ON ai_assistant_observation (tenant_id, environment, error_category, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_assistant_observation_feedback (
    feedback_id UUID PRIMARY KEY,
    observation_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128),
    user_id VARCHAR(128),
    rating VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    comment_preview TEXT,
    safe_metadata jsonb NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_assistant_observation_feedback_observation
        FOREIGN KEY (observation_id) REFERENCES ai_assistant_observation(observation_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_feedback_observation
    ON ai_assistant_observation_feedback (observation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_assistant_observation_feedback_recent
    ON ai_assistant_observation_feedback (tenant_id, environment, rating, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_thread (
    thread_id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    environment VARCHAR(64),
    user_id VARCHAR(128),
    component_type VARCHAR(64) NOT NULL,
    component_id VARCHAR(255) NOT NULL,
    route_key VARCHAR(255),
    title VARCHAR(120),
    status VARCHAR(16) NOT NULL,
    summary TEXT,
    schema_hash VARCHAR(128),
    variant_id VARCHAR(128),
    last_config_etag VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_turn (
    thread_id UUID NOT NULL,
    turn_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    next_event_seq BIGINT NOT NULL DEFAULT 1,
    terminal_event_type VARCHAR(64),
    PRIMARY KEY (thread_id, turn_id),
    CONSTRAINT fk_ai_turn_thread FOREIGN KEY (thread_id) REFERENCES ai_thread(thread_id)
);

CREATE TABLE IF NOT EXISTS ai_turn_event (
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    environment VARCHAR(64),
    stream_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    turn_id UUID NOT NULL,
    seq BIGINT NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload jsonb NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (thread_id, turn_id, seq),
    CONSTRAINT uk_ai_turn_event_event_id UNIQUE (event_id),
    CONSTRAINT fk_ai_turn_event_turn FOREIGN KEY (thread_id, turn_id) REFERENCES ai_turn(thread_id, turn_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_turn_event_stream_seq
    ON ai_turn_event (stream_id, seq);
