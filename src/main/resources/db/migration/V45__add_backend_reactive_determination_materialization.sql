DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM domain_rule_materialization
         WHERE target_layer = 'frontend_adapter'
    ) THEN
        RAISE EXCEPTION
            'Cannot remove ambiguous beta target_layer frontend_adapter while materializations still use it';
    END IF;
END $$;

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
            'approval_policy'
        ));

ALTER TABLE domain_rule_materialization
    ADD CONSTRAINT ck_domain_rule_materialization_backend_determination_type
        CHECK (
            (
                target_layer = 'backend_determination'
                AND target_artifact_type = 'resource-reactive-determination'
            )
            OR
            (
                target_layer <> 'backend_determination'
                AND target_artifact_type <> 'resource-reactive-determination'
            )
        );

ALTER TABLE domain_rule_materialization
    ADD CONSTRAINT ck_domain_rule_materialization_backend_determination_key
        CHECK (
            target_layer <> 'backend_determination'
            OR target_artifact_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$'
        );

ALTER TABLE domain_rule_materialization
    ADD CONSTRAINT ck_domain_rule_materialization_backend_determination_payload
        CHECK (
            target_layer <> 'backend_determination'
            OR COALESCE(
                CASE
                    WHEN jsonb_typeof(materialized_payload) = 'object'
                        AND jsonb_typeof(materialized_payload -> 'inputs') = 'array'
                        AND jsonb_typeof(materialized_payload -> 'outputs') = 'array'
                    THEN materialized_payload ->> 'schemaVersion' = 'praxis.backend-reactive-determination.v1'
                        AND materialized_payload ->> 'kind' = 'backend_reactive_determination'
                        AND materialized_payload #>> '{operationRef,operationId}'
                            ~ '^[A-Za-z][A-Za-z0-9._:-]{0,254}$'
                        AND materialized_payload #>> '{executionContract,idempotent}' = 'true'
                        AND materialized_payload #>> '{executionContract,persistence}' = 'none'
                        AND materialized_payload #>> '{executionContract,finalCommandRevalidation}' = 'true'
                        AND jsonb_array_length(materialized_payload -> 'inputs') > 0
                        AND jsonb_array_length(materialized_payload -> 'outputs') > 0
                        AND jsonb_array_length(materialized_payload -> 'inputs')
                            + jsonb_array_length(materialized_payload -> 'outputs') <= 64
                        AND NOT (materialized_payload ? 'tenantId')
                        AND NOT (materialized_payload ? 'environment')
                    ELSE FALSE
                END,
                FALSE
            )
        );
