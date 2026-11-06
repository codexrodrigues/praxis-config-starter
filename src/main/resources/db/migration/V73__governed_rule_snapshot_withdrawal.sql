-- A withdrawal keeps the last selected snapshot and the mutable head identity.
-- Re-activation must advance the existing revision; deleting/recreating a head would admit ABA.
ALTER TABLE domain_rule_snapshot_head ADD COLUMN withdrawn BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE domain_rule_snapshot_event ADD COLUMN reason VARCHAR(1000);
ALTER TABLE domain_rule_snapshot_event DROP CONSTRAINT domain_rule_snapshot_event_event_type_check;
ALTER TABLE domain_rule_snapshot_event ADD CONSTRAINT domain_rule_snapshot_event_event_type_check
 CHECK(event_type IN ('PUBLISHED','ACTIVATED','ROLLED_BACK','WITHDRAWN'));
ALTER TABLE domain_rule_snapshot_event ADD CONSTRAINT ck_rule_snapshot_withdrawal_reason
 CHECK(event_type <> 'WITHDRAWN' OR (reason IS NOT NULL AND length(btrim(reason)) BETWEEN 1 AND 1000));
