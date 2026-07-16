ALTER TABLE domain_rule_snapshot
    ADD CONSTRAINT uq_domain_rule_snapshot_scope_id
    UNIQUE (id, tenant_id, environment, rule_set_key);

ALTER TABLE domain_rule_snapshot
    DROP CONSTRAINT domain_rule_snapshot_supersedes_snapshot_id_fkey;

ALTER TABLE domain_rule_snapshot
    ADD CONSTRAINT fk_domain_rule_snapshot_supersedes_scope
    FOREIGN KEY (supersedes_snapshot_id, tenant_id, environment, rule_set_key)
    REFERENCES domain_rule_snapshot (id, tenant_id, environment, rule_set_key)
    ON DELETE RESTRICT;

ALTER TABLE domain_rule_snapshot_head
    DROP CONSTRAINT domain_rule_snapshot_head_active_snapshot_id_fkey;

ALTER TABLE domain_rule_snapshot_head
    ADD CONSTRAINT fk_domain_rule_snapshot_head_active_scope
    FOREIGN KEY (active_snapshot_id, tenant_id, environment, rule_set_key)
    REFERENCES domain_rule_snapshot (id, tenant_id, environment, rule_set_key)
    ON DELETE RESTRICT;

ALTER TABLE domain_rule_snapshot_event
    DROP CONSTRAINT domain_rule_snapshot_event_from_snapshot_id_fkey;

ALTER TABLE domain_rule_snapshot_event
    DROP CONSTRAINT domain_rule_snapshot_event_to_snapshot_id_fkey;

ALTER TABLE domain_rule_snapshot_event
    ADD CONSTRAINT fk_domain_rule_snapshot_event_from_scope
    FOREIGN KEY (from_snapshot_id, tenant_id, environment, rule_set_key)
    REFERENCES domain_rule_snapshot (id, tenant_id, environment, rule_set_key)
    ON DELETE RESTRICT;

ALTER TABLE domain_rule_snapshot_event
    ADD CONSTRAINT fk_domain_rule_snapshot_event_to_scope
    FOREIGN KEY (to_snapshot_id, tenant_id, environment, rule_set_key)
    REFERENCES domain_rule_snapshot (id, tenant_id, environment, rule_set_key)
    ON DELETE RESTRICT;
