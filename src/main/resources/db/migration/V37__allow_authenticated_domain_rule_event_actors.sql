ALTER TABLE domain_rule_event
    DROP CONSTRAINT ck_domain_rule_event_actor_type;

ALTER TABLE domain_rule_event
    ADD CONSTRAINT ck_domain_rule_event_actor_type
    CHECK (actor_type IS NULL OR actor_type IN (
        'human', 'llm', 'system', 'imported', 'authenticated'));
