CREATE TABLE IF NOT EXISTS domain_federation_release (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    release_key VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'candidate',
    source_release_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    validation_report JSONB NOT NULL DEFAULT '{}'::jsonb,
    payload_hash VARCHAR(128),
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ,
    CONSTRAINT ck_domain_federation_release_status
        CHECK (status IN ('candidate', 'active', 'superseded', 'blocked', 'retired'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_federation_release_scope_key
    ON domain_federation_release (
        COALESCE(tenant_id, ''),
        COALESCE(environment, ''),
        release_key
    );

CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_federation_release_active_scope
    ON domain_federation_release (
        COALESCE(tenant_id, ''),
        COALESCE(environment, '')
    )
    WHERE status = 'active';

CREATE INDEX IF NOT EXISTS idx_domain_federation_release_scope_status
    ON domain_federation_release (tenant_id, environment, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_domain_federation_release_payload_hash
    ON domain_federation_release (payload_hash)
    WHERE payload_hash IS NOT NULL;

CREATE TABLE IF NOT EXISTS domain_source (
    id UUID PRIMARY KEY,
    federation_release_id UUID NOT NULL REFERENCES domain_federation_release(id) ON DELETE CASCADE,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    source_key VARCHAR(512) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    service_key VARCHAR(255),
    service_name VARCHAR(255),
    semantic_owner VARCHAR(255),
    technical_owner VARCHAR(255),
    trust_level VARCHAR(64) NOT NULL DEFAULT 'generated',
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    latest_release_id UUID REFERENCES domain_catalog_release(id) ON DELETE SET NULL,
    latest_release_key VARCHAR(512),
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_source_type
        CHECK (source_type IN (
            'microservice',
            'monolith',
            'external_system',
            'manual_catalog',
            'generated',
            'federated'
        )),
    CONSTRAINT ck_domain_source_trust_level
        CHECK (trust_level IN ('authoritative', 'curated', 'generated', 'experimental', 'untrusted')),
    CONSTRAINT ck_domain_source_status
        CHECK (status IN ('active', 'deprecated', 'retired', 'blocked'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_source_release_key
    ON domain_source (federation_release_id, source_key);

CREATE INDEX IF NOT EXISTS idx_domain_source_scope_status
    ON domain_source (tenant_id, environment, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_domain_source_service
    ON domain_source (tenant_id, environment, service_key)
    WHERE service_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_domain_source_trust_level
    ON domain_source (tenant_id, environment, trust_level);

CREATE TABLE IF NOT EXISTS domain_context (
    id UUID PRIMARY KEY,
    federation_release_id UUID NOT NULL REFERENCES domain_federation_release(id) ON DELETE CASCADE,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    context_key VARCHAR(512) NOT NULL,
    source_key VARCHAR(512) NOT NULL,
    context_type VARCHAR(64) NOT NULL DEFAULT 'bounded_context',
    label VARCHAR(512),
    description TEXT,
    semantic_owner VARCHAR(255),
    technical_owner VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'candidate',
    latest_release_key VARCHAR(512),
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_context_type
        CHECK (context_type IN (
            'bounded_context',
            'subdomain',
            'capability',
            'external_context',
            'federated_context'
        )),
    CONSTRAINT ck_domain_context_status
        CHECK (status IN ('candidate', 'active', 'deprecated', 'blocked', 'retired'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_context_release_key
    ON domain_context (federation_release_id, context_key);

CREATE INDEX IF NOT EXISTS idx_domain_context_scope_status
    ON domain_context (tenant_id, environment, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_domain_context_source
    ON domain_context (federation_release_id, source_key);

CREATE INDEX IF NOT EXISTS idx_domain_context_owner
    ON domain_context (tenant_id, environment, semantic_owner)
    WHERE semantic_owner IS NOT NULL;

CREATE TABLE IF NOT EXISTS domain_context_relationship (
    id UUID PRIMARY KEY,
    federation_release_id UUID NOT NULL REFERENCES domain_federation_release(id) ON DELETE CASCADE,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    relationship_key VARCHAR(768) NOT NULL,
    source_context_key VARCHAR(512) NOT NULL,
    target_context_key VARCHAR(512) NOT NULL,
    relationship_type VARCHAR(64) NOT NULL,
    contract_key VARCHAR(512),
    direction VARCHAR(32) NOT NULL DEFAULT 'source_to_target',
    ownership VARCHAR(32) NOT NULL DEFAULT 'unknown',
    confidence DOUBLE PRECISION,
    status VARCHAR(32) NOT NULL DEFAULT 'candidate',
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_context_relationship_type
        CHECK (relationship_type IN (
            'references',
            'depends_on',
            'uses',
            'publishes_to',
            'subscribes_to',
            'shared_kernel',
            'anti_corruption_layer',
            'customer_supplier',
            'conformist',
            'open_host_service',
            'separate_ways'
        )),
    CONSTRAINT ck_domain_context_relationship_direction
        CHECK (direction IN ('source_to_target', 'target_to_source', 'bidirectional')),
    CONSTRAINT ck_domain_context_relationship_ownership
        CHECK (ownership IN ('source_owned', 'target_owned', 'shared', 'external', 'unknown')),
    CONSTRAINT ck_domain_context_relationship_confidence
        CHECK (confidence IS NULL OR (confidence >= 0.0 AND confidence <= 1.0)),
    CONSTRAINT ck_domain_context_relationship_status
        CHECK (status IN ('candidate', 'active', 'deprecated', 'blocked', 'conflict', 'rejected'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_context_relationship_release_key
    ON domain_context_relationship (federation_release_id, relationship_key);

CREATE INDEX IF NOT EXISTS idx_domain_context_relationship_source
    ON domain_context_relationship (federation_release_id, source_context_key);

CREATE INDEX IF NOT EXISTS idx_domain_context_relationship_target
    ON domain_context_relationship (federation_release_id, target_context_key);

CREATE INDEX IF NOT EXISTS idx_domain_context_relationship_type
    ON domain_context_relationship (tenant_id, environment, relationship_type, status);

CREATE INDEX IF NOT EXISTS idx_domain_context_relationship_contract
    ON domain_context_relationship (federation_release_id, contract_key)
    WHERE contract_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS domain_contract (
    id UUID PRIMARY KEY,
    federation_release_id UUID NOT NULL REFERENCES domain_federation_release(id) ON DELETE CASCADE,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    contract_key VARCHAR(512) NOT NULL,
    contract_type VARCHAR(64) NOT NULL,
    provider_source_key VARCHAR(512) NOT NULL,
    provider_context_key VARCHAR(512) NOT NULL,
    consumer_context_key VARCHAR(512),
    resource_key VARCHAR(255),
    operation_key VARCHAR(512),
    schema_ref VARCHAR(1024),
    compatibility VARCHAR(64) NOT NULL DEFAULT 'experimental',
    visibility VARCHAR(64) NOT NULL DEFAULT 'internal',
    status VARCHAR(32) NOT NULL DEFAULT 'candidate',
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_contract_type
        CHECK (contract_type IN (
            'rest_endpoint',
            'openapi_operation',
            'event_schema',
            'asyncapi_message',
            'shared_identifier',
            'lookup_option_source',
            'workflow_action',
            'external_system',
            'policy_dependency'
        )),
    CONSTRAINT ck_domain_contract_compatibility
        CHECK (compatibility IN ('stable', 'backward_compatible', 'breaking', 'experimental')),
    CONSTRAINT ck_domain_contract_visibility
        CHECK (visibility IN ('public', 'internal', 'restricted', 'deny_for_llm')),
    CONSTRAINT ck_domain_contract_status
        CHECK (status IN ('candidate', 'active', 'deprecated', 'blocked', 'retired'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_contract_release_key
    ON domain_contract (federation_release_id, contract_key);

CREATE INDEX IF NOT EXISTS idx_domain_contract_provider
    ON domain_contract (federation_release_id, provider_source_key, provider_context_key);

CREATE INDEX IF NOT EXISTS idx_domain_contract_consumer
    ON domain_contract (federation_release_id, consumer_context_key)
    WHERE consumer_context_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_domain_contract_resource
    ON domain_contract (tenant_id, environment, resource_key)
    WHERE resource_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_domain_contract_visibility_status
    ON domain_contract (tenant_id, environment, visibility, status);

CREATE TABLE IF NOT EXISTS domain_resolution (
    id UUID PRIMARY KEY,
    federation_release_id UUID NOT NULL REFERENCES domain_federation_release(id) ON DELETE CASCADE,
    tenant_id VARCHAR(128),
    environment VARCHAR(128),
    resolution_key VARCHAR(768) NOT NULL,
    source_concept_key VARCHAR(512) NOT NULL,
    target_concept_key VARCHAR(512) NOT NULL,
    source_context_key VARCHAR(512) NOT NULL,
    target_context_key VARCHAR(512) NOT NULL,
    resolution_type VARCHAR(64) NOT NULL,
    confidence DOUBLE PRECISION,
    status VARCHAR(32) NOT NULL DEFAULT 'candidate',
    review_owner VARCHAR(255),
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_domain_resolution_type
        CHECK (resolution_type IN (
            'same_as',
            'equivalent_to',
            'broader_than',
            'narrower_than',
            'maps_to',
            'local_projection_of',
            'conflicts_with'
        )),
    CONSTRAINT ck_domain_resolution_confidence
        CHECK (confidence IS NULL OR (confidence >= 0.0 AND confidence <= 1.0)),
    CONSTRAINT ck_domain_resolution_status
        CHECK (status IN ('candidate', 'review_required', 'approved', 'rejected', 'conflict'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_resolution_release_key
    ON domain_resolution (federation_release_id, resolution_key);

CREATE INDEX IF NOT EXISTS idx_domain_resolution_source_context
    ON domain_resolution (federation_release_id, source_context_key);

CREATE INDEX IF NOT EXISTS idx_domain_resolution_target_context
    ON domain_resolution (federation_release_id, target_context_key);

CREATE INDEX IF NOT EXISTS idx_domain_resolution_concepts
    ON domain_resolution (federation_release_id, source_concept_key, target_concept_key);

CREATE INDEX IF NOT EXISTS idx_domain_resolution_type_status
    ON domain_resolution (tenant_id, environment, resolution_type, status);
