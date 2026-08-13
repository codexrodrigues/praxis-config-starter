CREATE TABLE IF NOT EXISTS domain_rule_host_status (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    host_actor_ref VARCHAR(255) NOT NULL,
    loaded_snapshot_key VARCHAR(128),
    loaded_snapshot_content_hash VARCHAR(64),
    activation_revision BIGINT CHECK (activation_revision > 0),
    ready BOOLEAN NOT NULL,
    host_contract_version VARCHAR(64) NOT NULL,
    failure_code VARCHAR(64),
    observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_domain_rule_host_status_scope_actor
        UNIQUE (tenant_id, environment, rule_set_key, host_actor_ref),
    CONSTRAINT ck_domain_rule_host_status_hash
        CHECK (loaded_snapshot_content_hash IS NULL OR loaded_snapshot_content_hash ~ '^[A-F0-9]{64}$'),
    CONSTRAINT ck_domain_rule_host_status_ready_identity
        CHECK (NOT ready OR (
            loaded_snapshot_key IS NOT NULL
            AND loaded_snapshot_content_hash IS NOT NULL
            AND activation_revision IS NOT NULL
        ))
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_host_status_scope_observed
    ON domain_rule_host_status (tenant_id, environment, rule_set_key, observed_at DESC);
