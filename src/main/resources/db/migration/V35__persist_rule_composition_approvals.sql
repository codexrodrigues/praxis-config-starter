CREATE TABLE domain_rule_composition_approval (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    composition_digest VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(255) NOT NULL,
    role VARCHAR(64) NOT NULL,
    manifest JSONB NOT NULL CHECK (jsonb_typeof(manifest) = 'object'),
    approved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_domain_rule_composition_approval_digest
        CHECK (composition_digest ~ '^[A-F0-9]{64}$'),
    CONSTRAINT ck_domain_rule_composition_approval_role
        CHECK (role = 'RULE_COMPOSITION_APPROVER'),
    CONSTRAINT uq_domain_rule_composition_approval_actor
        UNIQUE (tenant_id, environment, composition_digest, actor_ref)
);

CREATE INDEX idx_domain_rule_composition_approval_digest
    ON domain_rule_composition_approval (
        tenant_id,
        environment,
        composition_digest,
        approved_at
    );
