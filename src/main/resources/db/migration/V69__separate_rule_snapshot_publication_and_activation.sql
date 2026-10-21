-- Preserve every old event and immutable snapshot. New PUBLISHED events carry no selection identity.
ALTER TABLE domain_rule_snapshot_event
    ALTER COLUMN activation_revision DROP NOT NULL,
    ALTER COLUMN head_etag DROP NOT NULL,
    ADD CONSTRAINT ck_domain_rule_snapshot_event_selection_identity
        CHECK ((activation_revision IS NOT NULL AND head_etag IS NOT NULL)
            OR (event_type = 'PUBLISHED' AND activation_revision IS NULL AND head_etag IS NULL));

-- Verified absence is a pair, never a fictitious head or an incomplete expected identity.
ALTER TABLE domain_rule_snapshot_rollout
    ALTER COLUMN expected_active_snapshot_id DROP NOT NULL,
    ALTER COLUMN expected_head_etag DROP NOT NULL,
    ADD CONSTRAINT ck_domain_rule_rollout_expected_identity_pair
        CHECK ((expected_active_snapshot_id IS NULL) = (expected_head_etag IS NULL));

CREATE INDEX IF NOT EXISTS idx_domain_rule_snapshot_event_chronology
    ON domain_rule_snapshot_event (tenant_id, environment, rule_set_key, created_at, id);
