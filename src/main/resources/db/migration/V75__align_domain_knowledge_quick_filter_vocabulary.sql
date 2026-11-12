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
            'resource_fact',
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
            'quick_filter',
            'form_config',
            'table_config',
            'rule_definition',
            'external_reference',
            'component_capability',
            'event_schema'
        ));

ALTER TABLE domain_knowledge_relationship
    DROP CONSTRAINT IF EXISTS ck_domain_knowledge_relationship_type;

ALTER TABLE domain_knowledge_relationship
    ADD CONSTRAINT ck_domain_knowledge_relationship_type
        CHECK (relationship_type IN (
            'contains',
            'part_of',
            'related_to',
            'has_field',
            'provides_fact',
            'has_state',
            'has_action',
            'has_surface',
            'has_stats',
            'has_quick_filter',
            'filters_field',
            'has_event',
            'has_metric',
            'has_relationship',
            'allowed_in_state',
            'selectable_when',
            'blocked_when',
            'blocked_in_state',
            'uses_concept',
            'references',
            'depends_on',
            'computed_from',
            'triggers',
            'produces',
            'consumes',
            'applies_to',
            'measured_by',
            'implemented_by',
            'maps_to',
            'same_as',
            'equivalent_to',
            'broader',
            'narrower',
            'broader_than',
            'narrower_than',
            'impacts',
            'owned_by',
            'stewarded_by',
            'governed_by',
            'materializes'
        ));
