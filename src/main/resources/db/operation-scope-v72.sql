-- Executed exclusively by V72 Java migration after canonical historical verification and table locks.
-- Preserve payloads, hashes, approvals, revisions, ETags and original opaque identities.
ALTER TABLE domain_rule_snapshot ADD COLUMN operation_key VARCHAR(512);
UPDATE domain_rule_snapshot SET operation_key=snapshot_payload #>> '{ruleSet,ref,operationKey}';
ALTER TABLE domain_rule_snapshot ALTER COLUMN operation_key SET NOT NULL;
ALTER TABLE domain_rule_snapshot ADD CONSTRAINT ck_domain_rule_snapshot_operation
    CHECK ((snapshot_payload #>> '{ruleSet,ref,operationKey}') IS NOT NULL
        AND jsonb_typeof(snapshot_payload #> '{ruleSet,ref,operationKey}')='string'
        AND length(operation_key)>0 AND operation_key=btrim(operation_key)
        AND operation_key=(snapshot_payload #>> '{ruleSet,ref,operationKey}'));

ALTER TABLE domain_rule_snapshot_head ADD COLUMN operation_key VARCHAR(512);
UPDATE domain_rule_snapshot_head h SET operation_key=s.operation_key FROM domain_rule_snapshot s WHERE s.id=h.active_snapshot_id;
ALTER TABLE domain_rule_snapshot_head ALTER COLUMN operation_key SET NOT NULL;
ALTER TABLE domain_rule_snapshot_event ADD COLUMN operation_key VARCHAR(512);
UPDATE domain_rule_snapshot_event e SET operation_key=s.operation_key FROM domain_rule_snapshot s WHERE s.id=e.to_snapshot_id;
ALTER TABLE domain_rule_snapshot_event ALTER COLUMN operation_key SET NOT NULL;
ALTER TABLE domain_rule_snapshot_rollout ADD COLUMN operation_key VARCHAR(512);
UPDATE domain_rule_snapshot_rollout r SET operation_key=s.operation_key FROM domain_rule_snapshot s WHERE s.id=r.candidate_snapshot_id;
ALTER TABLE domain_rule_snapshot_rollout ALTER COLUMN operation_key SET NOT NULL;
ALTER TABLE domain_rule_snapshot_rollout_event ADD COLUMN operation_key VARCHAR(512);
UPDATE domain_rule_snapshot_rollout_event e SET operation_key=r.operation_key FROM domain_rule_snapshot_rollout r WHERE r.id=e.rollout_id;
ALTER TABLE domain_rule_snapshot_rollout_event ALTER COLUMN operation_key SET NOT NULL;
ALTER TABLE domain_rule_candidate_probe ADD COLUMN operation_key VARCHAR(512);
UPDATE domain_rule_candidate_probe p SET operation_key=r.operation_key FROM domain_rule_snapshot_rollout r WHERE r.id=p.rollout_id;
ALTER TABLE domain_rule_candidate_probe ALTER COLUMN operation_key SET NOT NULL;
ALTER TABLE domain_rule_host_status ADD COLUMN operation_key VARCHAR(512);
UPDATE domain_rule_host_status h SET operation_key=s.operation_key FROM domain_rule_snapshot s
    WHERE s.tenant_id=h.tenant_id AND s.environment=h.environment AND s.rule_set_key=h.rule_set_key
      AND s.snapshot_key=h.loaded_snapshot_key;
-- A failure heartbeat without loaded content is attributable only when family history proves one operation.
UPDATE domain_rule_host_status h SET operation_key=(SELECT min(s.operation_key) FROM domain_rule_snapshot s
    WHERE s.tenant_id=h.tenant_id AND s.environment=h.environment AND s.rule_set_key=h.rule_set_key
    HAVING count(DISTINCT s.operation_key)=1) WHERE h.operation_key IS NULL AND h.loaded_snapshot_key IS NULL;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM domain_rule_host_status WHERE operation_key IS NULL) THEN
        RAISE EXCEPTION 'Host heartbeat operation is unverifiable; resolve history before migration';
    END IF;
END $$;
ALTER TABLE domain_rule_host_status ALTER COLUMN operation_key SET NOT NULL;

ALTER TABLE domain_rule_snapshot DROP CONSTRAINT uq_domain_rule_snapshot_revision,
    DROP CONSTRAINT uq_domain_rule_snapshot_version,
    ADD CONSTRAINT uq_domain_rule_snapshot_revision UNIQUE(tenant_id,environment,rule_set_key,operation_key,publication_revision),
    ADD CONSTRAINT uq_domain_rule_snapshot_version UNIQUE(tenant_id,environment,rule_set_key,operation_key,rule_set_version),
    ADD CONSTRAINT uq_domain_rule_snapshot_operation_id UNIQUE(id,tenant_id,environment,rule_set_key,operation_key);
ALTER TABLE domain_rule_snapshot DROP CONSTRAINT fk_domain_rule_snapshot_supersedes_scope,
    ADD CONSTRAINT fk_domain_rule_snapshot_supersedes_scope FOREIGN KEY(supersedes_snapshot_id,tenant_id,environment,rule_set_key,operation_key)
    REFERENCES domain_rule_snapshot(id,tenant_id,environment,rule_set_key,operation_key) ON DELETE RESTRICT;
ALTER TABLE domain_rule_snapshot_head DROP CONSTRAINT uq_domain_rule_snapshot_head,
    DROP CONSTRAINT fk_domain_rule_snapshot_head_active_scope,
    ADD CONSTRAINT uq_domain_rule_snapshot_head UNIQUE(tenant_id,environment,rule_set_key,operation_key),
    ADD CONSTRAINT fk_domain_rule_snapshot_head_active_scope FOREIGN KEY(active_snapshot_id,tenant_id,environment,rule_set_key,operation_key)
    REFERENCES domain_rule_snapshot(id,tenant_id,environment,rule_set_key,operation_key) ON DELETE RESTRICT;
ALTER TABLE domain_rule_snapshot_event DROP CONSTRAINT fk_domain_rule_snapshot_event_from_scope,
    DROP CONSTRAINT fk_domain_rule_snapshot_event_to_scope,
    ADD CONSTRAINT fk_domain_rule_snapshot_event_from_scope FOREIGN KEY(from_snapshot_id,tenant_id,environment,rule_set_key,operation_key)
    REFERENCES domain_rule_snapshot(id,tenant_id,environment,rule_set_key,operation_key) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_domain_rule_snapshot_event_to_scope FOREIGN KEY(to_snapshot_id,tenant_id,environment,rule_set_key,operation_key)
    REFERENCES domain_rule_snapshot(id,tenant_id,environment,rule_set_key,operation_key) ON DELETE RESTRICT;
ALTER TABLE domain_rule_snapshot_rollout DROP CONSTRAINT fk_domain_rule_rollout_candidate_scope,
    DROP CONSTRAINT fk_domain_rule_rollout_active_scope,
    ADD CONSTRAINT uq_domain_rule_rollout_operation_id UNIQUE(id,tenant_id,environment,rule_set_key,operation_key),
    ADD CONSTRAINT uq_domain_rule_rollout_operation_candidate UNIQUE(id,tenant_id,environment,rule_set_key,operation_key,candidate_snapshot_id),
    ADD CONSTRAINT fk_domain_rule_rollout_candidate_scope FOREIGN KEY(candidate_snapshot_id,tenant_id,environment,rule_set_key,operation_key)
    REFERENCES domain_rule_snapshot(id,tenant_id,environment,rule_set_key,operation_key) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_domain_rule_rollout_active_scope FOREIGN KEY(expected_active_snapshot_id,tenant_id,environment,rule_set_key,operation_key)
    REFERENCES domain_rule_snapshot(id,tenant_id,environment,rule_set_key,operation_key) ON DELETE RESTRICT;
ALTER TABLE domain_rule_candidate_probe DROP CONSTRAINT fk_domain_rule_candidate_probe_rollout_scope,
    DROP CONSTRAINT fk_domain_rule_candidate_probe_snapshot_scope,
    ADD CONSTRAINT fk_domain_rule_candidate_probe_rollout_scope FOREIGN KEY(rollout_id,tenant_id,environment,rule_set_key,operation_key,candidate_snapshot_id)
    REFERENCES domain_rule_snapshot_rollout(id,tenant_id,environment,rule_set_key,operation_key,candidate_snapshot_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_domain_rule_candidate_probe_snapshot_scope FOREIGN KEY(candidate_snapshot_id,tenant_id,environment,rule_set_key,operation_key)
    REFERENCES domain_rule_snapshot(id,tenant_id,environment,rule_set_key,operation_key) ON DELETE RESTRICT;
ALTER TABLE domain_rule_snapshot_rollout_event ADD CONSTRAINT fk_domain_rule_rollout_event_operation
    FOREIGN KEY(rollout_id,tenant_id,environment,rule_set_key,operation_key)
    REFERENCES domain_rule_snapshot_rollout(id,tenant_id,environment,rule_set_key,operation_key) ON DELETE RESTRICT;
ALTER TABLE domain_rule_host_status DROP CONSTRAINT uq_domain_rule_host_status_scope_actor,
    ADD CONSTRAINT uq_domain_rule_host_status_scope_actor UNIQUE(tenant_id,environment,rule_set_key,operation_key,host_actor_ref);
DROP INDEX uq_domain_rule_snapshot_rollout_open;
CREATE UNIQUE INDEX uq_domain_rule_snapshot_rollout_open ON domain_rule_snapshot_rollout(tenant_id,environment,rule_set_key,operation_key)
    WHERE status IN ('PREPARING','READY','BLOCKED');
CREATE INDEX idx_domain_rule_snapshot_operation_history ON domain_rule_snapshot(tenant_id,environment,rule_set_key,operation_key,publication_revision DESC);
CREATE INDEX idx_domain_rule_snapshot_event_operation ON domain_rule_snapshot_event(tenant_id,environment,rule_set_key,operation_key,created_at,id);
CREATE INDEX idx_domain_rule_rollout_operation ON domain_rule_snapshot_rollout(tenant_id,environment,rule_set_key,operation_key,created_at DESC);
CREATE INDEX idx_domain_rule_host_operation ON domain_rule_host_status(tenant_id,environment,rule_set_key,operation_key,observed_at DESC);
