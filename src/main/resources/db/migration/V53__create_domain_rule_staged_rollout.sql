CREATE TABLE IF NOT EXISTS domain_rule_rollout_policy (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    policy_key VARCHAR(128) NOT NULL,
    policy_version INTEGER NOT NULL CHECK (policy_version > 0),
    enforcement_mode VARCHAR(32) NOT NULL CHECK (enforcement_mode IN ('OBSERVE_ONLY', 'REQUIRED')),
    minimum_fresh_probes INTEGER NOT NULL CHECK (minimum_fresh_probes >= 0),
    minimum_ready_ratio NUMERIC(5,4) NOT NULL CHECK (minimum_ready_ratio BETWEEN 0 AND 1),
    block_on_incompatible BOOLEAN NOT NULL,
    stale_after_seconds BIGINT NOT NULL CHECK (stale_after_seconds > 0),
    maximum_rollout_age_seconds BIGINT CHECK (maximum_rollout_age_seconds > 0),
    active BOOLEAN NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_domain_rule_rollout_policy_scope_id
        UNIQUE (id, tenant_id, environment, rule_set_key),
    CONSTRAINT uq_domain_rule_rollout_policy_version
        UNIQUE (tenant_id, environment, rule_set_key, policy_key, policy_version)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_domain_rule_rollout_policy_active
    ON domain_rule_rollout_policy (tenant_id, environment, rule_set_key)
    WHERE active = TRUE;

CREATE TABLE IF NOT EXISTS domain_rule_snapshot_rollout (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    candidate_snapshot_id UUID NOT NULL,
    expected_active_snapshot_id UUID NOT NULL,
    expected_head_etag UUID NOT NULL,
    policy_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('PREPARING', 'READY', 'BLOCKED', 'ACTIVATED', 'CANCELLED', 'EXPIRED')),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_domain_rule_snapshot_rollout_scope
        UNIQUE (id, tenant_id, environment, rule_set_key),
    CONSTRAINT uq_domain_rule_snapshot_rollout_scope_id
        UNIQUE (id, tenant_id, environment, rule_set_key, candidate_snapshot_id),
    CONSTRAINT fk_domain_rule_rollout_policy_scope
        FOREIGN KEY (policy_id, tenant_id, environment, rule_set_key)
        REFERENCES domain_rule_rollout_policy(id, tenant_id, environment, rule_set_key)
        ON DELETE RESTRICT,
    CONSTRAINT fk_domain_rule_rollout_candidate_scope
        FOREIGN KEY (candidate_snapshot_id, tenant_id, environment, rule_set_key)
        REFERENCES domain_rule_snapshot(id, tenant_id, environment, rule_set_key) ON DELETE RESTRICT,
    CONSTRAINT fk_domain_rule_rollout_active_scope
        FOREIGN KEY (expected_active_snapshot_id, tenant_id, environment, rule_set_key)
        REFERENCES domain_rule_snapshot(id, tenant_id, environment, rule_set_key) ON DELETE RESTRICT,
    CONSTRAINT ck_domain_rule_rollout_distinct_snapshots
        CHECK (candidate_snapshot_id <> expected_active_snapshot_id),
    CONSTRAINT ck_domain_rule_rollout_expiry
        CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_domain_rule_snapshot_rollout_open
    ON domain_rule_snapshot_rollout (tenant_id, environment, rule_set_key)
    WHERE status IN ('PREPARING', 'READY', 'BLOCKED');

CREATE INDEX IF NOT EXISTS idx_domain_rule_snapshot_rollout_scope_created
    ON domain_rule_snapshot_rollout (tenant_id, environment, rule_set_key, created_at DESC);

CREATE TABLE IF NOT EXISTS domain_rule_candidate_probe (
    id UUID PRIMARY KEY,
    rollout_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    host_actor_ref VARCHAR(255) NOT NULL,
    candidate_snapshot_id UUID NOT NULL,
    candidate_snapshot_key VARCHAR(128) NOT NULL,
    candidate_content_hash VARCHAR(64) NOT NULL
        CHECK (candidate_content_hash ~ '^[A-F0-9]{64}$'),
    preload_ready BOOLEAN NOT NULL,
    host_contract_version VARCHAR(64) NOT NULL,
    engine_contract_version VARCHAR(64),
    json_logic_dialect_version VARCHAR(64),
    json_logic_corpus_sha256 VARCHAR(64)
        CHECK (json_logic_corpus_sha256 IS NULL OR json_logic_corpus_sha256 ~ '^[A-F0-9]{64}$'),
    implementation_catalog_digest VARCHAR(64)
        CHECK (implementation_catalog_digest IS NULL OR implementation_catalog_digest ~ '^[A-F0-9]{64}$'),
    failure_code VARCHAR(64),
    observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_domain_rule_candidate_probe_actor UNIQUE (rollout_id, host_actor_ref),
    CONSTRAINT fk_domain_rule_candidate_probe_rollout_scope
        FOREIGN KEY (rollout_id, tenant_id, environment, rule_set_key, candidate_snapshot_id)
        REFERENCES domain_rule_snapshot_rollout(
            id, tenant_id, environment, rule_set_key, candidate_snapshot_id) ON DELETE RESTRICT,
    CONSTRAINT fk_domain_rule_candidate_probe_snapshot_scope
        FOREIGN KEY (candidate_snapshot_id, tenant_id, environment, rule_set_key)
        REFERENCES domain_rule_snapshot(id, tenant_id, environment, rule_set_key) ON DELETE RESTRICT,
    CONSTRAINT ck_domain_rule_candidate_probe_ready_coordinates
        CHECK (NOT preload_ready OR (
            engine_contract_version IS NOT NULL
            AND json_logic_dialect_version IS NOT NULL
            AND json_logic_corpus_sha256 IS NOT NULL
            AND implementation_catalog_digest IS NOT NULL
        ))
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_candidate_probe_rollout_observed
    ON domain_rule_candidate_probe (rollout_id, observed_at DESC);

CREATE TABLE IF NOT EXISTS domain_rule_snapshot_rollout_event (
    id UUID PRIMARY KEY,
    rollout_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    event_type VARCHAR(32) NOT NULL
        CHECK (event_type IN ('CREATED', 'READINESS_CHANGED', 'ACTIVATED', 'CANCELLED', 'EXPIRED')),
    actor_ref VARCHAR(255) NOT NULL,
    safe_metadata JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(safe_metadata) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_domain_rule_rollout_event_scope
        FOREIGN KEY (rollout_id, tenant_id, environment, rule_set_key)
        REFERENCES domain_rule_snapshot_rollout(id, tenant_id, environment, rule_set_key)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_snapshot_rollout_event_timeline
    ON domain_rule_snapshot_rollout_event (rollout_id, created_at ASC);
