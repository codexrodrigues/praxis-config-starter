-- Governed operational action proposals persisted prior to explicit human confirmation / form handoff.
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
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_ai_agent_proposal_principal_status
    ON ai_agent_action_proposal (tenant_id, user_id, environment, status, expires_at);

CREATE INDEX IF NOT EXISTS idx_ai_agent_proposal_turn
    ON ai_agent_action_proposal (thread_id, turn_id);
