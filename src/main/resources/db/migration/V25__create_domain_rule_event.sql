CREATE TABLE IF NOT EXISTS domain_rule_event (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    rule_definition_id UUID NOT NULL REFERENCES domain_rule_definition(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_type VARCHAR(32),
    actor VARCHAR(255),
    summary VARCHAR(512) NOT NULL,
    status VARCHAR(32),
    target_layer VARCHAR(64),
    target_artifact_type VARCHAR(64),
    target_artifact_key VARCHAR(768),
    materialization_id UUID REFERENCES domain_rule_materialization(id) ON DELETE SET NULL,
    materialization_key VARCHAR(512),
    source_hash VARCHAR(128),
    visibility VARCHAR(32) NOT NULL DEFAULT 'safe',
    safe_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_rule_event_type
        CHECK (event_type IN (
            'definition.created',
            'definition.approved',
            'definition.activated',
            'materialization.created',
            'materialization.applied',
            'intake.received',
            'simulation.requested',
            'simulation.completed',
            'publication.requested',
            'publication.completed',
            'approval.requested',
            'approval.completed'
        )),
    CONSTRAINT ck_domain_rule_event_actor_type
        CHECK (actor_type IS NULL OR actor_type IN ('human', 'llm', 'system', 'imported')),
    CONSTRAINT ck_domain_rule_event_visibility
        CHECK (visibility IN ('safe')),
    CONSTRAINT ck_domain_rule_event_safe_metadata_object
        CHECK (jsonb_typeof(safe_metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_domain_rule_event_definition_time
    ON domain_rule_event (
        tenant_id,
        environment,
        rule_definition_id,
        occurred_at,
        event_type
    );

CREATE INDEX IF NOT EXISTS idx_domain_rule_event_type_time
    ON domain_rule_event (tenant_id, environment, event_type, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_domain_rule_event_materialization
    ON domain_rule_event (tenant_id, environment, materialization_id)
    WHERE materialization_id IS NOT NULL;
