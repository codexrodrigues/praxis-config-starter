ALTER TABLE domain_rule_rollout_policy
    ADD COLUMN IF NOT EXISTS status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS approved_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS activated_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS activated_at TIMESTAMPTZ;

UPDATE domain_rule_rollout_policy
SET status = CASE WHEN active THEN 'ACTIVE' ELSE 'SUPERSEDED' END,
    approved_by = COALESCE(approved_by, 'platform-migration'),
    approved_at = COALESCE(approved_at, created_at),
    activated_by = COALESCE(activated_by, 'platform-migration'),
    activated_at = COALESCE(activated_at, created_at)
WHERE status IS NULL;

ALTER TABLE domain_rule_rollout_policy
    ALTER COLUMN status SET NOT NULL,
    ADD CONSTRAINT ck_domain_rule_rollout_policy_status
        CHECK (status IN ('DRAFT', 'APPROVED', 'ACTIVE', 'SUPERSEDED')),
    ADD CONSTRAINT ck_domain_rule_rollout_policy_active_status
        CHECK (active = (status = 'ACTIVE')),
    ADD CONSTRAINT ck_domain_rule_rollout_policy_approval
        CHECK ((status = 'DRAFT' AND approved_by IS NULL AND approved_at IS NULL)
            OR (status <> 'DRAFT' AND approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    ADD CONSTRAINT ck_domain_rule_rollout_policy_activation
        CHECK ((status IN ('DRAFT', 'APPROVED') AND activated_by IS NULL AND activated_at IS NULL)
            OR (status IN ('ACTIVE', 'SUPERSEDED') AND activated_by IS NOT NULL AND activated_at IS NOT NULL));

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

INSERT INTO domain_rule_rollout_policy_head (
    id, tenant_id, environment, rule_set_key, active_policy_id,
    activation_revision, head_etag, updated_by, updated_at, row_version)
SELECT gen_random_uuid(), policy.tenant_id, policy.environment, policy.rule_set_key, policy.id,
       1, gen_random_uuid(), COALESCE(policy.activated_by, 'platform-migration'),
       COALESCE(policy.activated_at, policy.created_at), 0
FROM domain_rule_rollout_policy policy
WHERE policy.active = TRUE
ON CONFLICT (tenant_id, environment, rule_set_key) DO NOTHING;

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

INSERT INTO domain_rule_rollout_policy_event (
    id, policy_id, tenant_id, environment, rule_set_key,
    event_type, actor_ref, head_etag, created_at)
SELECT gen_random_uuid(), policy.id, policy.tenant_id, policy.environment, policy.rule_set_key,
       CASE WHEN policy.active THEN 'ACTIVATED' ELSE 'SUPERSEDED' END,
       COALESCE(policy.activated_by, 'platform-migration'), head.head_etag,
       COALESCE(policy.activated_at, policy.created_at)
FROM domain_rule_rollout_policy policy
LEFT JOIN domain_rule_rollout_policy_head head
  ON head.active_policy_id = policy.id
WHERE policy.status IN ('ACTIVE', 'SUPERSEDED');

CREATE INDEX IF NOT EXISTS idx_domain_rule_rollout_policy_event_timeline
    ON domain_rule_rollout_policy_event (
        tenant_id, environment, rule_set_key, created_at ASC);

CREATE OR REPLACE FUNCTION reject_domain_rule_rollout_policy_event_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'domain_rule_rollout_policy_event is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_domain_rule_rollout_policy_event_append_only
    ON domain_rule_rollout_policy_event;
CREATE TRIGGER trg_domain_rule_rollout_policy_event_append_only
BEFORE UPDATE OR DELETE ON domain_rule_rollout_policy_event
FOR EACH ROW EXECUTE FUNCTION reject_domain_rule_rollout_policy_event_mutation();
