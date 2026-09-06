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
            'workflow_action_policy',
            'approval_policy',
            'design_token_palette'
        ));

ALTER TABLE domain_rule_materialization
    DROP CONSTRAINT IF EXISTS ck_domain_rule_materialization_target_layer;

ALTER TABLE domain_rule_materialization
    ADD CONSTRAINT ck_domain_rule_materialization_target_layer
        CHECK (target_layer IN (
            'form_config',
            'backend_validation',
            'backend_determination',
            'workflow',
            'policy_engine',
            'notification',
            'reporting',
            'external_system',
            'option_source',
            'workflow_action',
            'approval_policy',
            'design_token_catalog'
        ));

ALTER TABLE domain_rule_materialization
    ADD CONSTRAINT ck_domain_rule_materialization_color_palette_type
        CHECK (
            (
                target_layer = 'design_token_catalog'
                AND target_artifact_type = 'governed-color-palette'
            )
            OR
            (
                target_layer <> 'design_token_catalog'
                AND target_artifact_type <> 'governed-color-palette'
            )
        );

ALTER TABLE domain_rule_materialization
    ADD CONSTRAINT ck_domain_rule_materialization_color_palette_payload
        CHECK (
            target_layer <> 'design_token_catalog'
            OR COALESCE(
                jsonb_typeof(materialized_payload) = 'object'
                AND NULLIF(BTRIM(materialized_payload ->> 'paletteKey'), '') IS NOT NULL
                AND NULLIF(BTRIM(materialized_payload ->> 'displayName'), '') IS NOT NULL
                AND NULLIF(BTRIM(materialized_payload ->> 'familyKey'), '') IS NOT NULL
                AND jsonb_typeof(materialized_payload -> 'variant') = 'object'
                AND NULLIF(BTRIM(materialized_payload -> 'variant' ->> 'key'), '') IS NOT NULL
                AND NULLIF(BTRIM(materialized_payload -> 'variant' ->> 'displayName'), '') IS NOT NULL
                AND jsonb_typeof(materialized_payload -> 'entries') = 'array'
                AND jsonb_array_length(materialized_payload -> 'entries') > 0,
                FALSE
            )
        );

CREATE UNIQUE INDEX uq_domain_rule_materialization_applied_palette_variant
    ON domain_rule_materialization (
        COALESCE(tenant_id, ''),
        COALESCE(environment, ''),
        (materialized_payload ->> 'familyKey'),
        (materialized_payload -> 'variant' ->> 'key')
    )
    WHERE status = 'applied'
      AND target_layer = 'design_token_catalog'
      AND target_artifact_type = 'governed-color-palette';
