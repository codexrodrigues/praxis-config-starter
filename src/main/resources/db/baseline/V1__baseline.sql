-- Baseline schema for clean installs (ai_registry + runtime config + api metadata)
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS config_entries (
    id UUID PRIMARY KEY,
    config_key VARCHAR(255) NOT NULL UNIQUE,
    config_value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS api_metadata (
    id BIGSERIAL PRIMARY KEY,
    path VARCHAR(1024) NOT NULL,
    method VARCHAR(16) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL DEFAULT 'GLOBAL',
    environment VARCHAR(128) NOT NULL DEFAULT 'default',
    service_key VARCHAR(255) NOT NULL DEFAULT 'default',
    release_id VARCHAR(255) NOT NULL DEFAULT 'v1',
    release_version VARCHAR(255),
    generated_at VARCHAR(255),
    tags TEXT,
    summary TEXT,
    description TEXT,
    operation_id TEXT,
    request_schema TEXT,
    response_schema TEXT,
    parameters TEXT,
    raw_json TEXT,
    embedding vector(768),
    CONSTRAINT uk_api_metadata_scope_path_method UNIQUE (tenant_id, environment, service_key, release_id, path, method)
);

CREATE INDEX IF NOT EXISTS idx_api_metadata_path ON api_metadata(path);
CREATE INDEX IF NOT EXISTS idx_api_metadata_scope
    ON api_metadata (tenant_id, environment, service_key, release_id);
CREATE INDEX IF NOT EXISTS idx_api_metadata_scope_operation
    ON api_metadata (tenant_id, environment, service_key, release_id, operation_id, method)
    WHERE operation_id IS NOT NULL;

DO $$
DECLARE
    api_dim INTEGER := (
        SELECT atttypmod
        FROM pg_attribute
        WHERE attrelid = 'api_metadata'::regclass AND attname = 'embedding'
    );
BEGIN
    IF api_dim IS NOT NULL AND api_dim > 0 THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_api_metadata_embedding ON api_metadata USING ivfflat (embedding vector_l2_ops) WITH (lists = 100)';
    ELSE
        RAISE NOTICE 'Skipping ivfflat index on api_metadata.embedding because dimension is not set';
    END IF;
END$$;

-- Immutable governed RuleSet snapshot control plane (squashed from V30).
CREATE TABLE IF NOT EXISTS domain_rule_snapshot (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    snapshot_key VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    rule_set_version INTEGER NOT NULL CHECK (rule_set_version > 0),
    publication_revision INTEGER NOT NULL CHECK (publication_revision > 0),
    snapshot_payload JSONB NOT NULL CHECK (jsonb_typeof(snapshot_payload) = 'object'),
    content_hash VARCHAR(64) NOT NULL CHECK (content_hash ~ '^[A-F0-9]{64}$'),
    composition_manifest JSONB NOT NULL,
    composition_digest VARCHAR(64) NOT NULL CHECK (composition_digest ~ '^[A-F0-9]{64}$'),
    supersedes_snapshot_id UUID,
    published_by VARCHAR(255) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_domain_rule_snapshot_key UNIQUE (tenant_id, environment, snapshot_key),
    CONSTRAINT uq_domain_rule_snapshot_revision UNIQUE (tenant_id, environment, rule_set_key, publication_revision),
    CONSTRAINT uq_domain_rule_snapshot_version UNIQUE (tenant_id, environment, rule_set_key, rule_set_version),
    CONSTRAINT uq_domain_rule_snapshot_scope_id UNIQUE (id, tenant_id, environment, rule_set_key),
    CONSTRAINT fk_domain_rule_snapshot_supersedes_scope
        FOREIGN KEY (supersedes_snapshot_id, tenant_id, environment, rule_set_key)
        REFERENCES domain_rule_snapshot (id, tenant_id, environment, rule_set_key)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_snapshot_history
    ON domain_rule_snapshot (tenant_id, environment, rule_set_key, publication_revision DESC);

CREATE TABLE IF NOT EXISTS domain_rule_composition_approval (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    composition_digest VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(255) NOT NULL,
    role VARCHAR(64) NOT NULL,
    manifest JSONB NOT NULL CHECK (jsonb_typeof(manifest) = 'object'),
    approved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_domain_rule_composition_approval_digest
        CHECK (composition_digest ~ '^[A-F0-9]{64}$'),
    CONSTRAINT ck_domain_rule_composition_approval_role
        CHECK (role = 'RULE_COMPOSITION_APPROVER'),
    CONSTRAINT uq_domain_rule_composition_approval_actor
        UNIQUE (tenant_id, environment, composition_digest, actor_ref)
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_composition_approval_digest
    ON domain_rule_composition_approval (
        tenant_id,
        environment,
        composition_digest,
        approved_at
    );

CREATE TABLE IF NOT EXISTS domain_rule_snapshot_head (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    active_snapshot_id UUID NOT NULL,
    activation_revision BIGINT NOT NULL CHECK (activation_revision > 0),
    head_etag UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_domain_rule_snapshot_head UNIQUE (tenant_id, environment, rule_set_key),
    CONSTRAINT fk_domain_rule_snapshot_head_active_scope
        FOREIGN KEY (active_snapshot_id, tenant_id, environment, rule_set_key)
        REFERENCES domain_rule_snapshot (id, tenant_id, environment, rule_set_key)
        ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS domain_rule_snapshot_event (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('PUBLISHED', 'ACTIVATED', 'ROLLED_BACK')),
    from_snapshot_id UUID,
    to_snapshot_id UUID NOT NULL,
    activation_revision BIGINT NOT NULL CHECK (activation_revision > 0),
    head_etag UUID NOT NULL,
    actor VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_domain_rule_snapshot_event_from_scope
        FOREIGN KEY (from_snapshot_id, tenant_id, environment, rule_set_key)
        REFERENCES domain_rule_snapshot (id, tenant_id, environment, rule_set_key)
        ON DELETE RESTRICT,
    CONSTRAINT fk_domain_rule_snapshot_event_to_scope
        FOREIGN KEY (to_snapshot_id, tenant_id, environment, rule_set_key)
        REFERENCES domain_rule_snapshot (id, tenant_id, environment, rule_set_key)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_snapshot_event_timeline
    ON domain_rule_snapshot_event (tenant_id, environment, rule_set_key, activation_revision DESC);

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
    outcome VARCHAR(32) NOT NULL CHECK (
        outcome IN ('ALLOW', 'DENY', 'NOT_APPLICABLE', 'INCONCLUSIVE', 'TECHNICAL_ERROR')
    ),
    duration_micros BIGINT NOT NULL CHECK (duration_micros BETWEEN 0 AND 300000000),
    observed_at TIMESTAMPTZ NOT NULL,
    host_actor_ref VARCHAR(255) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_rule_execution_observation_hash
        CHECK (snapshot_content_hash ~ '^[A-F0-9]{64}$'),
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
    engine_contract_version VARCHAR(64),
    json_logic_dialect_version VARCHAR(64),
    json_logic_corpus_sha256 VARCHAR(64),
    implementation_catalog_digest VARCHAR(64),
    failure_code VARCHAR(64),
    observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_domain_rule_host_status_scope_actor
        UNIQUE (tenant_id, environment, rule_set_key, host_actor_ref),
    CONSTRAINT ck_domain_rule_host_status_hash
        CHECK (loaded_snapshot_content_hash IS NULL OR loaded_snapshot_content_hash ~ '^[A-F0-9]{64}$'),
    CONSTRAINT ck_domain_rule_host_status_corpus_hash
        CHECK (json_logic_corpus_sha256 IS NULL OR json_logic_corpus_sha256 ~ '^[A-F0-9]{64}$'),
    CONSTRAINT ck_domain_rule_host_status_catalog_hash
        CHECK (implementation_catalog_digest IS NULL OR implementation_catalog_digest ~ '^[A-F0-9]{64}$'),
    CONSTRAINT ck_domain_rule_host_status_ready_identity
        CHECK (NOT ready OR (
            loaded_snapshot_key IS NOT NULL
            AND loaded_snapshot_content_hash IS NOT NULL
            AND activation_revision IS NOT NULL
            AND engine_contract_version IS NOT NULL
            AND json_logic_dialect_version IS NOT NULL
            AND json_logic_corpus_sha256 IS NOT NULL
            AND implementation_catalog_digest IS NOT NULL
        ))
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_host_status_scope_observed
    ON domain_rule_host_status (tenant_id, environment, rule_set_key, observed_at DESC);

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
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('DRAFT', 'APPROVED', 'ACTIVE', 'SUPERSEDED')),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    approved_by VARCHAR(255),
    approved_at TIMESTAMPTZ,
    activated_by VARCHAR(255),
    activated_at TIMESTAMPTZ,
    CONSTRAINT uq_domain_rule_rollout_policy_scope_id
        UNIQUE (id, tenant_id, environment, rule_set_key),
    CONSTRAINT uq_domain_rule_rollout_policy_version
        UNIQUE (tenant_id, environment, rule_set_key, policy_key, policy_version),
    CONSTRAINT ck_domain_rule_rollout_policy_active_status
        CHECK (active = (status = 'ACTIVE')),
    CONSTRAINT ck_domain_rule_rollout_policy_approval
        CHECK ((status = 'DRAFT' AND approved_by IS NULL AND approved_at IS NULL)
            OR (status <> 'DRAFT' AND approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT ck_domain_rule_rollout_policy_activation
        CHECK ((status IN ('DRAFT', 'APPROVED') AND activated_by IS NULL AND activated_at IS NULL)
            OR (status IN ('ACTIVE', 'SUPERSEDED') AND activated_by IS NOT NULL AND activated_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_domain_rule_rollout_policy_active
    ON domain_rule_rollout_policy (tenant_id, environment, rule_set_key)
    WHERE active = TRUE;

CREATE TABLE IF NOT EXISTS domain_rule_rollout_policy_head (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    active_policy_id UUID,
    activation_revision BIGINT NOT NULL CHECK (activation_revision >= 0),
    head_etag UUID NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_domain_rule_rollout_policy_head_scope
        UNIQUE (tenant_id, environment, rule_set_key),
    CONSTRAINT fk_domain_rule_rollout_policy_head_active_scope
        FOREIGN KEY (active_policy_id, tenant_id, environment, rule_set_key)
        REFERENCES domain_rule_rollout_policy(id, tenant_id, environment, rule_set_key)
        ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS domain_rule_rollout_policy_event (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    event_type VARCHAR(32) NOT NULL
        CHECK (event_type IN ('CREATED', 'APPROVED', 'ACTIVATED', 'SUPERSEDED')),
    actor_ref VARCHAR(255) NOT NULL,
    head_etag UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_domain_rule_rollout_policy_event_scope
        FOREIGN KEY (policy_id, tenant_id, environment, rule_set_key)
        REFERENCES domain_rule_rollout_policy(id, tenant_id, environment, rule_set_key)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_rollout_policy_event_timeline
    ON domain_rule_rollout_policy_event (
        tenant_id, environment, rule_set_key, created_at ASC);

CREATE OR REPLACE FUNCTION reject_domain_rule_rollout_policy_event_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'domain_rule_rollout_policy_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_domain_rule_rollout_policy_event_append_only
BEFORE UPDATE OR DELETE ON domain_rule_rollout_policy_event
FOR EACH ROW EXECUTE FUNCTION reject_domain_rule_rollout_policy_event_mutation();

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


CREATE TABLE IF NOT EXISTS ui_user_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255),
    component_type VARCHAR(64) NOT NULL,
    component_id VARCHAR(255) NOT NULL,
    environment VARCHAR(64),
    payload JSONB NOT NULL,
    authoring_source JSONB,
    version BIGINT NOT NULL DEFAULT 1,
    etag UUID NOT NULL DEFAULT gen_random_uuid(),
    tags JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(255),
    CONSTRAINT uk_ui_user_config UNIQUE (tenant_id, user_id, component_type, component_id, environment),
    CONSTRAINT chk_ui_user_config_authoring_source_object
        CHECK (authoring_source IS NULL OR jsonb_typeof(authoring_source) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_ui_user_config_lookup ON ui_user_config (tenant_id, component_type, component_id);
CREATE INDEX IF NOT EXISTS idx_ui_user_config_user ON ui_user_config (tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_ui_user_config_env ON ui_user_config (tenant_id, environment);

CREATE TABLE IF NOT EXISTS ai_registry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    registry_type VARCHAR(64) NOT NULL,
    registry_key VARCHAR(255) NOT NULL,
    component_type VARCHAR(64),
    scope VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    scope_key VARCHAR(255) NOT NULL DEFAULT 'GLOBAL',
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    etag UUID NOT NULL DEFAULT gen_random_uuid(),
    tags JSONB,
    source VARCHAR(64),
    source_ref VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    embedding vector,
    CONSTRAINT uk_ai_registry UNIQUE (registry_type, registry_key, component_type, scope, scope_key),
    CONSTRAINT ck_ai_registry_scope_global CHECK (scope = 'SYSTEM' AND scope_key = 'GLOBAL')
);

CREATE INDEX IF NOT EXISTS idx_ai_registry_lookup ON ai_registry (registry_type, registry_key);
CREATE INDEX IF NOT EXISTS idx_ai_registry_component ON ai_registry (component_type);
CREATE INDEX IF NOT EXISTS idx_ai_registry_scope ON ai_registry (scope, scope_key);

DO $$
DECLARE
    reg_dim INTEGER := (
        SELECT atttypmod
        FROM pg_attribute
        WHERE attrelid = 'ai_registry'::regclass AND attname = 'embedding'
    );
BEGIN
    IF reg_dim IS NOT NULL AND reg_dim > 0 THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_ai_registry_embedding ON ai_registry USING ivfflat (embedding vector_l2_ops) WITH (lists = 100)';
    ELSE
        RAISE NOTICE 'Skipping ivfflat index on ai_registry.embedding because dimension is not set';
    END IF;
END$$;
