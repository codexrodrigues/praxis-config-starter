CREATE TABLE IF NOT EXISTS domain_rule_test_run (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES domain_rule_change_workspace(id) ON DELETE RESTRICT,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    workspace_revision BIGINT NOT NULL CHECK (workspace_revision > 0),
    base_definition_hash VARCHAR(64) NOT NULL CHECK (base_definition_hash ~ '^[A-F0-9]{64}$'),
    evaluated_at TIMESTAMPTZ NOT NULL,
    user_time_zone VARCHAR(128) NOT NULL,
    active_snapshot_key VARCHAR(128),
    active_snapshot_content_hash VARCHAR(64),
    active_activation_revision BIGINT NOT NULL CHECK (active_activation_revision >= 0),
    result_summary JSONB NOT NULL CHECK (jsonb_typeof(result_summary) = 'object'),
    recorded_by VARCHAR(255) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_test_run_workspace
    ON domain_rule_test_run (tenant_id, environment, workspace_id, recorded_at DESC);

CREATE TABLE IF NOT EXISTS domain_rule_test_run_result (
    id UUID PRIMARY KEY,
    test_run_id UUID NOT NULL REFERENCES domain_rule_test_run(id) ON DELETE RESTRICT,
    scenario_id UUID NOT NULL REFERENCES domain_rule_test_scenario(id) ON DELETE RESTRICT,
    scenario_key VARCHAR(255) NOT NULL,
    expected_decision VARCHAR(32) NOT NULL,
    candidate_decision VARCHAR(32) NOT NULL,
    active_decision VARCHAR(32) NOT NULL,
    comparison VARCHAR(32) NOT NULL CHECK (comparison IN ('MATCH', 'MISMATCH', 'INCONCLUSIVE', 'TECHNICAL_ERROR')),
    candidate_matches_expected BOOLEAN NOT NULL,
    active_matches_expected BOOLEAN NOT NULL,
    candidate_reason_codes JSONB NOT NULL CHECK (jsonb_typeof(candidate_reason_codes) = 'array'),
    active_reason_codes JSONB NOT NULL CHECK (jsonb_typeof(active_reason_codes) = 'array'),
    candidate_plan_digest VARCHAR(64) NOT NULL,
    active_plan_digest VARCHAR(64) NOT NULL,
    facts_digest VARCHAR(64) NOT NULL,
    CONSTRAINT uq_domain_rule_test_run_scenario UNIQUE (test_run_id, scenario_id)
);
