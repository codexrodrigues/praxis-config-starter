ALTER TABLE domain_rule_snapshot
    ADD COLUMN composition_manifest JSONB,
    ADD COLUMN composition_digest VARCHAR(64);

ALTER TABLE domain_rule_snapshot
    ADD CONSTRAINT ck_domain_rule_snapshot_composition_digest
        CHECK (composition_digest IS NULL OR composition_digest ~ '^[A-F0-9]{64}$');

-- Existing beta snapshots remain preserved for audit but fail closed in the runtime reader.
-- Republish them through the manifest flow before they can become active again.
