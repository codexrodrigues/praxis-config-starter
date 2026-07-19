ALTER TABLE domain_knowledge_relationship
    DROP CONSTRAINT IF EXISTS ck_domain_knowledge_relationship_type;

ALTER TABLE domain_knowledge_relationship
    ADD CONSTRAINT ck_domain_knowledge_relationship_type
        CHECK (relationship_type IN (
            'contains',
            'part_of',
            'related_to',
            'has_field',
            'has_state',
            'has_action',
            'has_surface',
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
