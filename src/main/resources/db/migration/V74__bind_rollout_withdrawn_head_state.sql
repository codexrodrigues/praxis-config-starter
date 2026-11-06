-- The retained snapshot of a withdrawn head is an audit selection, not an active policy.
-- Reentry may select identical immutable content, but must bind the exact tombstone CAS.
ALTER TABLE domain_rule_snapshot_rollout
    ADD COLUMN expected_head_withdrawn BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE domain_rule_snapshot_rollout
    DROP CONSTRAINT ck_domain_rule_rollout_distinct_snapshots;

ALTER TABLE domain_rule_snapshot_rollout
    ADD CONSTRAINT ck_domain_rule_rollout_distinct_snapshots CHECK (
        expected_active_snapshot_id IS NULL
        OR candidate_snapshot_id <> expected_active_snapshot_id
        OR expected_head_withdrawn
    ),
    ADD CONSTRAINT ck_domain_rule_rollout_withdrawn_head CHECK (
        NOT expected_head_withdrawn
        OR (expected_active_snapshot_id IS NOT NULL AND expected_head_etag IS NOT NULL)
    );
