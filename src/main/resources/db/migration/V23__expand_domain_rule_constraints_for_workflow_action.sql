ALTER TABLE domain_rule_definition
    DROP CONSTRAINT IF EXISTS ck_domain_rule_definition_type;

ALTER TABLE domain_rule_definition
    ADD CONSTRAINT ck_domain_rule_definition_type
        CHECK (rule_type IN (
            'visual_guidance',
            'form_rule',
            'validation',
            'visibility',
            'calculation',
            'workflow',
            'compliance',
            'privacy',
            'ai_usage',
            'policy_reference',
            'selection_eligibility',
            'workflow_action_policy'
        ));

ALTER TABLE domain_rule_materialization
    DROP CONSTRAINT IF EXISTS ck_domain_rule_materialization_target_layer;

ALTER TABLE domain_rule_materialization
    ADD CONSTRAINT ck_domain_rule_materialization_target_layer
        CHECK (target_layer IN (
            'form_config',
            'frontend_adapter',
            'backend_validation',
            'workflow',
            'policy_engine',
            'notification',
            'reporting',
            'external_system',
            'option_source',
            'workflow_action'
        ));
