ALTER TABLE domain_rule_change_workspace
    DROP CONSTRAINT domain_rule_change_workspace_status_check;

ALTER TABLE domain_rule_change_workspace
    ADD CONSTRAINT domain_rule_change_workspace_status_check
    CHECK (status IN ('OPEN', 'ABANDONED', 'SUBMITTED', 'APPROVED', 'REJECTED', 'PROMOTED'));

ALTER TABLE domain_rule_change_workspace
    ADD COLUMN promoted_definition_id UUID
        REFERENCES domain_rule_definition(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_domain_rule_change_workspace_promoted_definition
    ON domain_rule_change_workspace (promoted_definition_id)
    WHERE promoted_definition_id IS NOT NULL;

ALTER TABLE domain_rule_change_workspace
    ADD CONSTRAINT ck_domain_rule_change_workspace_promotion
    CHECK ((status = 'PROMOTED') = (promoted_definition_id IS NOT NULL));
