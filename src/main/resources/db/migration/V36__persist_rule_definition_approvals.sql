ALTER TABLE domain_rule_definition
    DROP CONSTRAINT ck_domain_rule_definition_created_by_type;

ALTER TABLE domain_rule_definition
    ADD CONSTRAINT ck_domain_rule_definition_created_by_type
    CHECK (created_by_type IN ('human', 'llm', 'system', 'imported', 'authenticated'));

CREATE TABLE domain_rule_definition_approval (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    definition_id UUID NOT NULL REFERENCES domain_rule_definition(id) ON DELETE RESTRICT,
    definition_hash VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(255) NOT NULL,
    role VARCHAR(64) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_domain_rule_definition_approval_hash
        CHECK (definition_hash ~ '^[A-F0-9]{64}$'),
    CONSTRAINT ck_domain_rule_definition_approval_role
        CHECK (role = 'RULE_DEFINITION_APPROVER'),
    CONSTRAINT uq_domain_rule_definition_approval_actor
        UNIQUE (tenant_id, environment, definition_id, definition_hash, actor_ref)
);

CREATE INDEX idx_domain_rule_definition_approval_lookup
    ON domain_rule_definition_approval (
        tenant_id,
        environment,
        definition_id,
        definition_hash,
        approved_at
    );

CREATE FUNCTION reject_domain_rule_definition_approval_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'domain_rule_definition_approval is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_domain_rule_definition_approval_append_only
BEFORE UPDATE OR DELETE ON domain_rule_definition_approval
FOR EACH ROW EXECUTE FUNCTION reject_domain_rule_definition_approval_mutation();
