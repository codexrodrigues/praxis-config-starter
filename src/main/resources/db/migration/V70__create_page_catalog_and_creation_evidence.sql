-- Page identity is independent of authorial titles, reviews, assignments and the release head.
CREATE TABLE ui_page_catalog (
    page_id UUID PRIMARY KEY,
    application_id VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    route_namespace VARCHAR(512) NOT NULL,
    root_component_type VARCHAR(64) NOT NULL CHECK (root_component_type = 'praxis-dynamic-page'),
    root_component_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_ui_page_root_identity CHECK (root_component_id = page_id::text),
    CONSTRAINT ck_ui_page_namespace CHECK (route_namespace ~ '^/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*$'),
    CONSTRAINT uq_ui_page_scope UNIQUE (page_id, application_id, tenant_id, environment),
    CONSTRAINT uq_ui_page_root UNIQUE (tenant_id, environment, root_component_type, root_component_id)
);

-- Kept for the full lifetime of the catalog: a retained key can never create a second page.
-- evidence_text preserves exact numbers/presence/order; it is not current authored content.
CREATE TABLE ui_page_creation_evidence (
    page_id UUID PRIMARY KEY REFERENCES ui_page_catalog(page_id),
    application_id VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(255) NOT NULL,
    creation_key UUID NOT NULL,
    draft_id UUID NOT NULL UNIQUE REFERENCES ui_layout_draft(id) DEFERRABLE INITIALLY DEFERRED,
    command_hash VARCHAR(64) NOT NULL CHECK (command_hash ~ '^[0-9a-f]{64}$'),
    evidence_hash VARCHAR(64) NOT NULL CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    evidence_text TEXT NOT NULL CHECK (octet_length(evidence_text) <= 2097152 AND json_typeof(evidence_text::json) = 'object'),
    CONSTRAINT fk_ui_page_evidence_scope FOREIGN KEY (page_id, application_id, tenant_id, environment)
        REFERENCES ui_page_catalog(page_id, application_id, tenant_id, environment),
    CONSTRAINT uq_ui_page_creation_key UNIQUE (application_id, tenant_id, environment, actor_ref, creation_key)
);
ALTER TABLE ui_page_catalog ADD CONSTRAINT fk_ui_page_creation_evidence FOREIGN KEY (page_id)
    REFERENCES ui_page_creation_evidence(page_id) DEFERRABLE INITIALLY DEFERRED;

CREATE TRIGGER tr_ui_page_catalog_immutable BEFORE UPDATE OR DELETE ON ui_page_catalog
    FOR EACH ROW EXECUTE FUNCTION reject_ui_layout_immutable_mutation();
CREATE TRIGGER tr_ui_page_catalog_no_truncate BEFORE TRUNCATE ON ui_page_catalog
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ui_layout_immutable_mutation();
CREATE TRIGGER tr_ui_page_creation_evidence_immutable BEFORE UPDATE OR DELETE ON ui_page_creation_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_ui_layout_immutable_mutation();
CREATE TRIGGER tr_ui_page_creation_evidence_no_truncate BEFORE TRUNCATE ON ui_page_creation_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ui_layout_immutable_mutation();
