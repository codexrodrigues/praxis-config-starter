CREATE TABLE IF NOT EXISTS domain_rule_definition (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    rule_key VARCHAR(512) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    rule_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    context_key VARCHAR(255),
    resource_key VARCHAR(255),
    service_key VARCHAR(255),
    semantic_owner VARCHAR(255),
    steward VARCHAR(255),
    source_release_id UUID REFERENCES domain_catalog_release(id) ON DELETE SET NULL,
    source_change_set_id UUID REFERENCES domain_knowledge_change_set(id) ON DELETE SET NULL,
    definition JSONB NOT NULL DEFAULT '{}'::jsonb,
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    condition JSONB,
    governance JSONB NOT NULL DEFAULT '{}'::jsonb,
    validation_result JSONB,
    created_by_type VARCHAR(32) NOT NULL DEFAULT 'system',
    created_by VARCHAR(255),
    approved_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    CONSTRAINT ck_domain_rule_definition_version
        CHECK (version > 0),
    CONSTRAINT ck_domain_rule_definition_type
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
            'policy_reference'
        )),
    CONSTRAINT ck_domain_rule_definition_status
        CHECK (status IN ('draft', 'proposed', 'approved', 'active', 'deprecated', 'retired', 'rejected')),
    CONSTRAINT ck_domain_rule_definition_created_by_type
        CHECK (created_by_type IN ('human', 'llm', 'system', 'imported'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_rule_definition_scope_key_version
    ON domain_rule_definition (
        COALESCE(tenant_id, ''),
        COALESCE(environment, ''),
        rule_key,
        version
    );

CREATE INDEX IF NOT EXISTS idx_domain_rule_definition_scope_status
    ON domain_rule_definition (tenant_id, environment, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_domain_rule_definition_context
    ON domain_rule_definition (tenant_id, environment, context_key)
    WHERE context_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_domain_rule_definition_resource
    ON domain_rule_definition (tenant_id, environment, resource_key)
    WHERE resource_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_domain_rule_definition_type
    ON domain_rule_definition (tenant_id, environment, rule_type);

CREATE INDEX IF NOT EXISTS idx_domain_rule_definition_definition
    ON domain_rule_definition USING gin (definition);

CREATE TABLE IF NOT EXISTS domain_rule_materialization (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    rule_definition_id UUID NOT NULL REFERENCES domain_rule_definition(id) ON DELETE CASCADE,
    materialization_key VARCHAR(512) NOT NULL,
    target_layer VARCHAR(64) NOT NULL,
    target_artifact_type VARCHAR(64) NOT NULL,
    target_artifact_key VARCHAR(768) NOT NULL,
    target_pointer VARCHAR(768),
    target_release_key VARCHAR(255),
    materialized_rule_id VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    materialized_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_hash VARCHAR(128),
    validation_result JSONB,
    applied_by_type VARCHAR(32),
    applied_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at TIMESTAMPTZ,
    CONSTRAINT ck_domain_rule_materialization_target_layer
        CHECK (target_layer IN (
            'form_config',
            'frontend_adapter',
            'backend_validation',
            'workflow',
            'policy_engine',
            'notification',
            'reporting',
            'external_system'
        )),
    CONSTRAINT ck_domain_rule_materialization_status
        CHECK (status IN ('draft', 'pending_review', 'applied', 'failed', 'superseded', 'reverted')),
    CONSTRAINT ck_domain_rule_materialization_applied_by_type
        CHECK (applied_by_type IS NULL OR applied_by_type IN ('human', 'llm', 'system'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_rule_materialization_scope_key
    ON domain_rule_materialization (
        COALESCE(tenant_id, ''),
        COALESCE(environment, ''),
        materialization_key
    );

CREATE INDEX IF NOT EXISTS idx_domain_rule_materialization_definition
    ON domain_rule_materialization (tenant_id, environment, rule_definition_id);

CREATE INDEX IF NOT EXISTS idx_domain_rule_materialization_target
    ON domain_rule_materialization (
        tenant_id,
        environment,
        target_layer,
        target_artifact_type,
        target_artifact_key
    );

CREATE INDEX IF NOT EXISTS idx_domain_rule_materialization_status
    ON domain_rule_materialization (tenant_id, environment, status, updated_at DESC);

ALTER TABLE domain_knowledge_evidence
    DROP CONSTRAINT IF EXISTS ck_domain_knowledge_evidence_subject_type;

ALTER TABLE domain_knowledge_evidence
    ADD CONSTRAINT ck_domain_knowledge_evidence_subject_type
        CHECK (subject_type IN (
            'concept',
            'alias',
            'binding',
            'relationship',
            'rule_definition',
            'rule_materialization'
        ));
