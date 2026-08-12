ALTER TABLE domain_rule_snapshot_event
    DROP CONSTRAINT IF EXISTS domain_rule_snapshot_event_event_type_check;

ALTER TABLE domain_rule_snapshot_event
    ADD CONSTRAINT domain_rule_snapshot_event_event_type_check
    CHECK (event_type IN ('PUBLISHED', 'ACTIVATED', 'ROLLED_BACK'));
