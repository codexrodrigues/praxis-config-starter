CREATE TABLE IF NOT EXISTS domain_rule_execution_observation (
    observation_id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    snapshot_id UUID NOT NULL,
    snapshot_key VARCHAR(128) NOT NULL,
    snapshot_content_hash VARCHAR(64) NOT NULL,
    rule_set_version INTEGER NOT NULL CHECK (rule_set_version > 0),
    activation_revision BIGINT NOT NULL CHECK (activation_revision > 0),
    outcome VARCHAR(32) NOT NULL,
    duration_micros BIGINT NOT NULL CHECK (duration_micros BETWEEN 0 AND 300000000),
    observed_at TIMESTAMPTZ NOT NULL,
    host_actor_ref VARCHAR(255) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_rule_execution_observation_hash
        CHECK (snapshot_content_hash ~ '^[A-F0-9]{64}$'),
    CONSTRAINT ck_domain_rule_execution_observation_outcome
        CHECK (outcome IN ('ALLOW', 'DENY', 'NOT_APPLICABLE', 'INCONCLUSIVE', 'TECHNICAL_ERROR')),
    CONSTRAINT fk_domain_rule_execution_observation_snapshot_scope
        FOREIGN KEY (snapshot_id, tenant_id, environment, rule_set_key)
        REFERENCES domain_rule_snapshot (id, tenant_id, environment, rule_set_key)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_execution_observation_snapshot_time
    ON domain_rule_execution_observation (
        tenant_id,
        environment,
        snapshot_key,
        observed_at DESC
    );

CREATE INDEX IF NOT EXISTS idx_domain_rule_execution_observation_ruleset_time
    ON domain_rule_execution_observation (
        tenant_id,
        environment,
        rule_set_key,
        observed_at DESC
    );
