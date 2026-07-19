ALTER TABLE domain_knowledge_binding
    DROP CONSTRAINT IF EXISTS ck_domain_knowledge_binding_type;

ALTER TABLE domain_knowledge_binding
    ADD CONSTRAINT ck_domain_knowledge_binding_type
        CHECK (binding_type IN (
            'api_resource',
            'api_operation',
            'dto_class',
            'dto_schema',
            'dto_field',
            'entity_class',
            'entity_field',
            'service_method',
            'repository_projection',
            'workflow_action',
            'approval_policy',
            'ui_surface',
            'ui_schema_field',
            'option_source',
            'stats_endpoint',
            'form_config',
            'table_config',
            'rule_definition',
            'external_reference',
            'component_capability',
            'event_schema'
        ));
