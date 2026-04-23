CREATE TABLE IF NOT EXISTS domain_knowledge_concept (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    concept_key VARCHAR(512) NOT NULL,
    context_key VARCHAR(255),
    resource_key VARCHAR(255),
    node_type VARCHAR(64) NOT NULL,
    label VARCHAR(512),
    description TEXT,
    locale VARCHAR(32),
    semantic_owner VARCHAR(255),
    steward VARCHAR(255),
    lifecycle VARCHAR(32) NOT NULL DEFAULT 'candidate',
    curation_status VARCHAR(32) NOT NULL DEFAULT 'generated',
    ai_visibility VARCHAR(32) NOT NULL DEFAULT 'allow',
    data_category VARCHAR(64),
    classification VARCHAR(64),
    compliance_tags JSONB,
    source_release_id UUID REFERENCES domain_catalog_release(id) ON DELETE SET NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_knowledge_concept_lifecycle
        CHECK (lifecycle IN ('draft', 'candidate', 'active', 'deprecated', 'retired')),
    CONSTRAINT ck_domain_knowledge_concept_curation_status
        CHECK (curation_status IN ('generated', 'review_required', 'approved', 'rejected')),
    CONSTRAINT ck_domain_knowledge_concept_ai_visibility
        CHECK (ai_visibility IN ('allow', 'mask', 'summarize_only', 'deny'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_knowledge_concept_scope_key
    ON domain_knowledge_concept (
        COALESCE(tenant_id, ''),
        COALESCE(environment, ''),
        concept_key
    );

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_concept_context
    ON domain_knowledge_concept (tenant_id, environment, context_key);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_concept_resource
    ON domain_knowledge_concept (tenant_id, environment, resource_key)
    WHERE resource_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_concept_type
    ON domain_knowledge_concept (tenant_id, environment, node_type);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_concept_status
    ON domain_knowledge_concept (tenant_id, environment, lifecycle, curation_status);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_concept_payload
    ON domain_knowledge_concept USING gin (payload);

CREATE TABLE IF NOT EXISTS domain_knowledge_alias (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    concept_id UUID NOT NULL REFERENCES domain_knowledge_concept(id) ON DELETE CASCADE,
    alias VARCHAR(512) NOT NULL,
    normalized_alias VARCHAR(512) NOT NULL,
    locale VARCHAR(32),
    region VARCHAR(64),
    business_unit VARCHAR(128),
    alias_type VARCHAR(64) NOT NULL DEFAULT 'synonym',
    weight DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    source VARCHAR(64) NOT NULL DEFAULT 'generated',
    curation_status VARCHAR(32) NOT NULL DEFAULT 'generated',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_knowledge_alias_type
        CHECK (alias_type IN (
            'preferred_term',
            'synonym',
            'abbreviation',
            'legacy_name',
            'business_slang',
            'technical_name',
            'misspelling'
        )),
    CONSTRAINT ck_domain_knowledge_alias_source
        CHECK (source IN ('generated', 'annotated', 'manual', 'imported', 'llm_proposed')),
    CONSTRAINT ck_domain_knowledge_alias_curation_status
        CHECK (curation_status IN ('generated', 'review_required', 'approved', 'rejected'))
);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_alias_lookup
    ON domain_knowledge_alias (tenant_id, environment, normalized_alias);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_alias_concept
    ON domain_knowledge_alias (tenant_id, environment, concept_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_knowledge_alias_preferred
    ON domain_knowledge_alias (
        COALESCE(tenant_id, ''),
        COALESCE(environment, ''),
        concept_id,
        COALESCE(locale, '')
    )
    WHERE alias_type = 'preferred_term'
      AND curation_status IN ('generated', 'review_required', 'approved');

CREATE TABLE IF NOT EXISTS domain_knowledge_binding (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    concept_id UUID NOT NULL REFERENCES domain_knowledge_concept(id) ON DELETE CASCADE,
    binding_type VARCHAR(64) NOT NULL,
    binding_key VARCHAR(768) NOT NULL,
    resource_key VARCHAR(255),
    api_path VARCHAR(768),
    api_method VARCHAR(16),
    schema_pointer VARCHAR(768),
    source_release_id UUID REFERENCES domain_catalog_release(id) ON DELETE SET NULL,
    confidence DOUBLE PRECISION,
    curation_status VARCHAR(32) NOT NULL DEFAULT 'generated',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_knowledge_binding_type
        CHECK (binding_type IN (
            'api_resource',
            'api_operation',
            'dto_class',
            'dto_field',
            'entity_class',
            'entity_field',
            'service_method',
            'repository_projection',
            'workflow_action',
            'ui_surface',
            'ui_schema_field',
            'form_config',
            'table_config',
            'component_capability',
            'event_schema'
        )),
    CONSTRAINT ck_domain_knowledge_binding_curation_status
        CHECK (curation_status IN ('generated', 'review_required', 'approved', 'rejected'))
);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_binding_concept
    ON domain_knowledge_binding (tenant_id, environment, concept_id);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_binding_key
    ON domain_knowledge_binding (tenant_id, environment, binding_type, binding_key);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_binding_resource
    ON domain_knowledge_binding (tenant_id, environment, resource_key)
    WHERE resource_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_binding_api
    ON domain_knowledge_binding (tenant_id, environment, api_path, api_method)
    WHERE api_path IS NOT NULL;

CREATE TABLE IF NOT EXISTS domain_knowledge_relationship (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    source_concept_id UUID NOT NULL REFERENCES domain_knowledge_concept(id) ON DELETE CASCADE,
    target_concept_id UUID NOT NULL REFERENCES domain_knowledge_concept(id) ON DELETE CASCADE,
    relationship_type VARCHAR(64) NOT NULL,
    cross_context BOOLEAN NOT NULL DEFAULT false,
    source_context_key VARCHAR(255),
    target_context_key VARCHAR(255),
    contract_key VARCHAR(512),
    confidence DOUBLE PRECISION,
    curation_status VARCHAR(32) NOT NULL DEFAULT 'generated',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_knowledge_relationship_type
        CHECK (relationship_type IN (
            'contains',
            'has_field',
            'has_state',
            'has_action',
            'references',
            'depends_on',
            'computed_from',
            'triggers',
            'maps_to',
            'same_as',
            'broader_than',
            'narrower_than',
            'governed_by',
            'owned_by',
            'stewarded_by'
        )),
    CONSTRAINT ck_domain_knowledge_relationship_curation_status
        CHECK (curation_status IN ('generated', 'review_required', 'approved', 'rejected'))
);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_relationship_source
    ON domain_knowledge_relationship (tenant_id, environment, source_concept_id);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_relationship_target
    ON domain_knowledge_relationship (tenant_id, environment, target_concept_id);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_relationship_type
    ON domain_knowledge_relationship (tenant_id, environment, relationship_type);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_relationship_cross_context
    ON domain_knowledge_relationship (tenant_id, environment, cross_context);

CREATE TABLE IF NOT EXISTS domain_knowledge_evidence (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    evidence_key VARCHAR(512) NOT NULL,
    subject_type VARCHAR(64) NOT NULL,
    subject_id UUID NOT NULL,
    evidence_type VARCHAR(64) NOT NULL,
    source_release_id UUID REFERENCES domain_catalog_release(id) ON DELETE SET NULL,
    source_uri VARCHAR(1024),
    source_pointer VARCHAR(1024),
    confidence DOUBLE PRECISION,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_knowledge_evidence_subject_type
        CHECK (subject_type IN ('concept', 'alias', 'binding', 'relationship')),
    CONSTRAINT ck_domain_knowledge_evidence_type
        CHECK (evidence_type IN (
            'annotation',
            'openapi',
            'json_schema',
            'java_symbol',
            'catalog_release',
            'manual_review',
            'llm_proposal',
            'import'
        ))
);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_evidence_subject
    ON domain_knowledge_evidence (tenant_id, environment, subject_type, subject_id);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_evidence_key
    ON domain_knowledge_evidence (tenant_id, environment, evidence_key);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_evidence_release
    ON domain_knowledge_evidence (tenant_id, environment, source_release_id)
    WHERE source_release_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS domain_knowledge_change_set (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    change_set_key VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    author_type VARCHAR(32) NOT NULL,
    author_id VARCHAR(255),
    reviewer_id VARCHAR(255),
    intent VARCHAR(255),
    reason TEXT,
    patch JSONB NOT NULL,
    validation_result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    applied_at TIMESTAMPTZ,
    CONSTRAINT ck_domain_knowledge_change_set_status
        CHECK (status IN ('draft', 'proposed', 'approved', 'rejected', 'applied', 'superseded')),
    CONSTRAINT ck_domain_knowledge_change_set_author_type
        CHECK (author_type IN ('human', 'llm', 'system'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_knowledge_change_set_scope_key
    ON domain_knowledge_change_set (
        COALESCE(tenant_id, ''),
        COALESCE(environment, ''),
        change_set_key
    );

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_change_set_status
    ON domain_knowledge_change_set (tenant_id, environment, status, created_at DESC);
