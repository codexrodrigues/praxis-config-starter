ALTER TABLE domain_rule_definition
    DROP CONSTRAINT ck_domain_rule_definition_type,
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
            'workflow_action_policy',
            'approval_policy',
            'design_token_palette',
            'authorization_policy'
        ));
