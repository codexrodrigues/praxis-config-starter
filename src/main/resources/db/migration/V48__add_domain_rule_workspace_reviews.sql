ALTER TABLE domain_rule_change_workspace
    DROP CONSTRAINT domain_rule_change_workspace_status_check;

ALTER TABLE domain_rule_change_workspace
    ADD CONSTRAINT domain_rule_change_workspace_status_check
    CHECK (status IN ('OPEN', 'ABANDONED', 'SUBMITTED', 'APPROVED', 'REJECTED'));

CREATE TABLE domain_rule_workspace_review (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES domain_rule_change_workspace(id) ON DELETE RESTRICT,
    tenant_id VARCHAR(128) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    workspace_revision BIGINT NOT NULL CHECK (workspace_revision > 0),
    base_definition_hash VARCHAR(64) NOT NULL CHECK (base_definition_hash ~ '^[A-F0-9]{64}$'),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    rationale TEXT NOT NULL,
    reviewer_ref VARCHAR(255) NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_domain_rule_workspace_review_revision UNIQUE (workspace_id, workspace_revision)
);

CREATE INDEX idx_domain_rule_workspace_review_scope
    ON domain_rule_workspace_review (tenant_id, environment, workspace_id, reviewed_at DESC);
