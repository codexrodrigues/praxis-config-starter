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
    supersedes_snapshot_id UUID REFERENCES domain_rule_snapshot(id) ON DELETE RESTRICT,
    published_by VARCHAR(255) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_domain_rule_snapshot_key UNIQUE (tenant_id, environment, snapshot_key),
    CONSTRAINT uq_domain_rule_snapshot_revision UNIQUE (tenant_id, environment, rule_set_key, publication_revision),
    CONSTRAINT uq_domain_rule_snapshot_version UNIQUE (tenant_id, environment, rule_set_key, rule_set_version)
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_snapshot_history
    ON domain_rule_snapshot (tenant_id, environment, rule_set_key, publication_revision DESC);

CREATE TABLE IF NOT EXISTS domain_rule_snapshot_head (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    active_snapshot_id UUID NOT NULL REFERENCES domain_rule_snapshot(id) ON DELETE RESTRICT,
    activation_revision BIGINT NOT NULL CHECK (activation_revision > 0),
    head_etag UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_domain_rule_snapshot_head UNIQUE (tenant_id, environment, rule_set_key)
);

CREATE TABLE IF NOT EXISTS domain_rule_snapshot_event (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    rule_set_key VARCHAR(512) NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('PUBLISHED', 'ROLLED_BACK')),
    from_snapshot_id UUID REFERENCES domain_rule_snapshot(id) ON DELETE RESTRICT,
    to_snapshot_id UUID NOT NULL REFERENCES domain_rule_snapshot(id) ON DELETE RESTRICT,
    activation_revision BIGINT NOT NULL CHECK (activation_revision > 0),
    head_etag UUID NOT NULL,
    actor VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_snapshot_event_timeline
    ON domain_rule_snapshot_event (tenant_id, environment, rule_set_key, activation_revision DESC);
