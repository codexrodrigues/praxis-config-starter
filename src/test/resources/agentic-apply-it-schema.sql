CREATE DOMAIN IF NOT EXISTS jsonb AS VARCHAR(20000);

DROP TABLE IF EXISTS ai_turn_event;
DROP TABLE IF EXISTS ai_turn;
DROP TABLE IF EXISTS ai_thread;
DROP TABLE IF EXISTS ui_user_config;

CREATE TABLE ui_user_config (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    etag UUID NOT NULL,
    component_type VARCHAR(64) NOT NULL,
    environment VARCHAR(64),
    component_id VARCHAR(255) NOT NULL,
    payload jsonb NOT NULL,
    authoring_source jsonb,
    tags jsonb,
    tenant_id VARCHAR(255) NOT NULL,
    updated_by VARCHAR(255),
    user_id VARCHAR(255),
    UNIQUE (tenant_id, user_id, component_type, component_id, environment)
);

CREATE TABLE ai_thread (
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

CREATE TABLE ai_turn (
    thread_id UUID NOT NULL,
    turn_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    next_event_seq BIGINT NOT NULL DEFAULT 1,
    terminal_event_type VARCHAR(64),
    PRIMARY KEY (thread_id, turn_id),
    CONSTRAINT fk_agentic_apply_turn_thread FOREIGN KEY (thread_id) REFERENCES ai_thread(thread_id)
);

CREATE TABLE ai_turn_event (
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
    CONSTRAINT uk_agentic_apply_event_id UNIQUE (event_id),
    CONSTRAINT fk_agentic_apply_event_turn FOREIGN KEY (thread_id, turn_id) REFERENCES ai_turn(thread_id, turn_id)
);

CREATE INDEX idx_agentic_apply_event_stream_seq ON ai_turn_event (stream_id, seq);

CREATE TABLE IF NOT EXISTS ai_agent_action_proposal (
    proposal_id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    tenant_id VARCHAR(160) NOT NULL,
    user_id VARCHAR(160) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    thread_id UUID,
    turn_id UUID,
    entity_key VARCHAR(160) NOT NULL,
    resource_key VARCHAR(200),
    resource_path VARCHAR(255) NOT NULL,
    entity_id VARCHAR(160),
    action_id VARCHAR(160) NOT NULL,
    action_title VARCHAR(255) NOT NULL,
    action_description VARCHAR(1024),
    operation_kind VARCHAR(64) NOT NULL,
    form_mode VARCHAR(32) NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    submit_url VARCHAR(512) NOT NULL,
    schema_url VARCHAR(512),
    risk_level VARCHAR(32) NOT NULL,
    confirmation_required BOOLEAN NOT NULL DEFAULT TRUE,
    has_input_form BOOLEAN NOT NULL DEFAULT FALSE,
    organization_id VARCHAR(64),
    organization_label VARCHAR(160),
    record_title VARCHAR(255),
    record_subtitle VARCHAR(255),
    record_lifecycle_state VARCHAR(120),
    expected_version_tag VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    blocked_code VARCHAR(80),
    impediment_note VARCHAR(1024),
    prefill_json TEXT,
    input_fields_json TEXT,
    missing_fields_json TEXT,
    idempotency_key VARCHAR(160),
    execution_receipt_json TEXT,
    bulk_proposal_id VARCHAR(120),
    bulk_operation_id VARCHAR(200),
    bulk_mode VARCHAR(40),
    bulk_atomicity VARCHAR(32),
    bulk_execution_id VARCHAR(120),
    bulk_totals_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE
);

