CREATE TABLE IF NOT EXISTS domain_rule_change_workspace (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_key VARCHAR(512) NOT NULL,
    base_definition_id UUID NOT NULL REFERENCES domain_rule_definition(id) ON DELETE RESTRICT,
    base_definition_version INTEGER NOT NULL CHECK (base_definition_version > 0),
    base_definition_hash VARCHAR(64) NOT NULL CHECK (base_definition_hash ~ '^[A-F0-9]{64}$'),
    title VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('OPEN', 'ABANDONED', 'SUBMITTED')),
    draft_condition JSONB,
    draft_parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    rationale TEXT,
    etag UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_by VARCHAR(255) NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_change_workspace_scope
    ON domain_rule_change_workspace (tenant_id, environment, rule_key, updated_at DESC);

CREATE TABLE IF NOT EXISTS domain_rule_test_scenario (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES domain_rule_change_workspace(id) ON DELETE CASCADE,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    scenario_key VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    facts JSONB NOT NULL CHECK (jsonb_typeof(facts) = 'object'),
    expected_decision VARCHAR(32) NOT NULL CHECK (expected_decision IN ('ALLOW', 'DENY', 'NOT_APPLICABLE', 'INCONCLUSIVE', 'TECHNICAL_ERROR')),
    expected_output JSONB,
    status VARCHAR(32) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    etag UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_by VARCHAR(255) NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_domain_rule_test_scenario_key UNIQUE (workspace_id, scenario_key)
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_test_scenario_scope
    ON domain_rule_test_scenario (tenant_id, environment, workspace_id, scenario_key);
