CREATE TABLE ui_layout_definition (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    component_type VARCHAR(64) NOT NULL,
    component_id VARCHAR(255) NOT NULL,
    schema_version VARCHAR(128) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ui_layout_definition_target UNIQUE (tenant_id, environment, component_type, component_id)
);

CREATE TABLE ui_layout_revision (
    id UUID PRIMARY KEY,
    definition_id UUID NOT NULL REFERENCES ui_layout_definition(id),
    revision_number BIGINT NOT NULL,
    patch_document JSONB NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    schema_version VARCHAR(128) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_reason VARCHAR(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ui_layout_revision_number UNIQUE (definition_id, revision_number),
    CONSTRAINT chk_ui_layout_revision_patch_object CHECK (jsonb_typeof(patch_document) = 'object')
);

CREATE TABLE ui_layout_assignment_revision (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    revision_id UUID NOT NULL REFERENCES ui_layout_revision(id),
    layer_class VARCHAR(32) NOT NULL,
    selector_document JSONB NOT NULL,
    priority SMALLINT NOT NULL,
    contribution_key VARCHAR(255) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_ui_layout_assignment_selector_object CHECK (jsonb_typeof(selector_document) = 'object')
);

CREATE TABLE ui_layout_draft (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    root_component_type VARCHAR(64) NOT NULL,
    root_component_id VARCHAR(255) NOT NULL,
    draft_document JSONB NOT NULL,
    draft_etag UUID NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT chk_ui_layout_draft_document_object CHECK (jsonb_typeof(draft_document) = 'object')
);

CREATE TABLE ui_layout_release (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    root_component_type VARCHAR(64) NOT NULL,
    root_component_id VARCHAR(255) NOT NULL,
    source_draft_id UUID REFERENCES ui_layout_draft(id),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE ui_layout_release_member (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES ui_layout_release(id),
    member_order INTEGER NOT NULL,
    component_type VARCHAR(64) NOT NULL,
    component_id VARCHAR(255) NOT NULL,
    assignment_revision_id UUID NOT NULL REFERENCES ui_layout_assignment_revision(id),
    content_revision_id UUID NOT NULL REFERENCES ui_layout_revision(id),
    contribution_key VARCHAR(255) NOT NULL,
    CONSTRAINT uq_ui_layout_release_member_order UNIQUE (release_id, member_order),
    CONSTRAINT uq_ui_layout_release_member_contribution UNIQUE (release_id, component_type, component_id, contribution_key)
);

CREATE TABLE ui_layout_release_review (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL UNIQUE REFERENCES ui_layout_release(id),
    state VARCHAR(32) NOT NULL,
    review_etag UUID NOT NULL,
    submitted_by VARCHAR(255),
    submitted_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL
);

CREATE TABLE ui_layout_release_approval (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES ui_layout_release(id),
    actor VARCHAR(255) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE ui_layout_release_head (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    root_component_type VARCHAR(64) NOT NULL,
    root_component_id VARCHAR(255) NOT NULL,
    active_release_id UUID REFERENCES ui_layout_release(id),
    head_etag UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT uq_ui_layout_release_head_scope UNIQUE (tenant_id, environment, root_component_type, root_component_id)
);

CREATE TABLE ui_layout_release_event (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    root_component_type VARCHAR(64) NOT NULL,
    root_component_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_release_id UUID REFERENCES ui_layout_release(id),
    to_release_id UUID REFERENCES ui_layout_release(id),
    -- Null means the event did not move the aggregate head (creation/review evidence).
    -- Head-moving events always record the new strong head ETag.
    head_etag UUID,
    actor VARCHAR(255) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE OR REPLACE FUNCTION reject_ui_layout_immutable_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ui layout revisions and releases are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_ui_layout_revision_immutable
    BEFORE UPDATE OR DELETE ON ui_layout_revision
    FOR EACH ROW EXECUTE FUNCTION reject_ui_layout_immutable_mutation();
CREATE TRIGGER tr_ui_layout_assignment_revision_immutable
    BEFORE UPDATE OR DELETE ON ui_layout_assignment_revision
    FOR EACH ROW EXECUTE FUNCTION reject_ui_layout_immutable_mutation();
CREATE TRIGGER tr_ui_layout_release_immutable
    BEFORE UPDATE OR DELETE ON ui_layout_release
    FOR EACH ROW EXECUTE FUNCTION reject_ui_layout_immutable_mutation();
CREATE TRIGGER tr_ui_layout_release_member_immutable
    BEFORE UPDATE OR DELETE ON ui_layout_release_member
    FOR EACH ROW EXECUTE FUNCTION reject_ui_layout_immutable_mutation();
CREATE TRIGGER tr_ui_layout_release_approval_immutable
    BEFORE UPDATE OR DELETE ON ui_layout_release_approval
    FOR EACH ROW EXECUTE FUNCTION reject_ui_layout_immutable_mutation();
CREATE TRIGGER tr_ui_layout_release_event_immutable
    BEFORE UPDATE OR DELETE ON ui_layout_release_event
    FOR EACH ROW EXECUTE FUNCTION reject_ui_layout_immutable_mutation();
