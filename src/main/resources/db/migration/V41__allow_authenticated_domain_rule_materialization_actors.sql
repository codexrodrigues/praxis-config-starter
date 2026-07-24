ALTER TABLE domain_rule_materialization
    DROP CONSTRAINT ck_domain_rule_materialization_applied_by_type;

ALTER TABLE domain_rule_materialization
    ADD CONSTRAINT ck_domain_rule_materialization_applied_by_type
    CHECK (applied_by_type IS NULL OR applied_by_type IN (
        'human', 'llm', 'system', 'authenticated'));
