-- Internal evidence storage; no historical backfill or mutable api_metadata lookup.
ALTER TABLE ui_layout_draft ADD CONSTRAINT uq_ui_layout_draft_evidence_scope
    UNIQUE (id, tenant_id, environment, root_component_type, root_component_id);

CREATE TABLE ui_layout_baseline_metadata_evidence (
    capture_ref UUID PRIMARY KEY,
    source_draft_ref UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    root_component_type VARCHAR(64) NOT NULL,
    root_component_id VARCHAR(255) NOT NULL,
    component_type VARCHAR(64) NOT NULL,
    component_id VARCHAR(255) NOT NULL,
    baseline_source_ref VARCHAR(1024) NOT NULL,
    document_type VARCHAR(255) NOT NULL,
    authoring_schema_ref VARCHAR(1024) NOT NULL,
    authoring_schema_version VARCHAR(128) NOT NULL,
    baseline_content_hash VARCHAR(64) NOT NULL CHECK (baseline_content_hash ~ '^[0-9a-f]{64}$'),
    baseline_document_text TEXT NOT NULL CHECK (octet_length(baseline_document_text) <= 262144
        AND json_typeof(baseline_document_text::json) = 'object'),
    service_key VARCHAR(255) NOT NULL,
    resource_ref VARCHAR(1024) NOT NULL,
    operation_path VARCHAR(1024) NOT NULL CHECK (operation_path LIKE '/%'),
    operation_method VARCHAR(32) NOT NULL CHECK (operation_method ~ '^[A-Z][A-Z-]*$'),
    schema_type VARCHAR(32) NOT NULL CHECK (schema_type IN ('request', 'response')),
    source_schema_ref VARCHAR(1024) NOT NULL,
    producer_ref VARCHAR(1024) NOT NULL,
    publication_ref VARCHAR(1024) NOT NULL,
    normalizer_ref VARCHAR(1024) NOT NULL,
    projector_ref VARCHAR(1024) NOT NULL,
    actor_ref VARCHAR(255) NOT NULL,
    administrative_unit VARCHAR(255) NOT NULL,
    context_version VARCHAR(255) NOT NULL,
    policy_ref VARCHAR(1024) NOT NULL,
    policy_revision VARCHAR(255) NOT NULL,
    -- Preserve Java Instant nanoseconds; this is observation time, not a DB commit timestamp.
    captured_at_text VARCHAR(40) NOT NULL,
    raw_schema_text TEXT NOT NULL CHECK (octet_length(raw_schema_text) <= 262144
        AND json_typeof(raw_schema_text::json) = 'object'),
    raw_schema_hash VARCHAR(64) NOT NULL CHECK (raw_schema_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_ui_layout_metadata_draft_scope FOREIGN KEY
        (source_draft_ref, tenant_id, environment, root_component_type, root_component_id)
        REFERENCES ui_layout_draft (id, tenant_id, environment, root_component_type, root_component_id),
    CONSTRAINT uq_ui_layout_metadata_draft_target UNIQUE (source_draft_ref, component_type, component_id)
);

CREATE FUNCTION guard_ui_layout_metadata_evidence_insert()
RETURNS TRIGGER AS $$
DECLARE
    draft_state VARCHAR(32);
    accumulated_bytes BIGINT;
BEGIN
    IF current_setting('server_encoding') <> 'UTF8' THEN
        RAISE EXCEPTION 'metadata evidence requires UTF8 database encoding';
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'metadata evidence requires read committed isolation';
    END IF;
    -- Serialize competing target inserts and lifecycle transitions on the same draft.
    SELECT state INTO draft_state FROM ui_layout_draft
        WHERE id = NEW.source_draft_ref AND tenant_id = NEW.tenant_id
          AND environment = NEW.environment AND root_component_type = NEW.root_component_type
          AND root_component_id = NEW.root_component_id FOR UPDATE;
    IF draft_state IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'metadata evidence requires an editable scoped draft';
    END IF;
    SELECT COALESCE(SUM(octet_length(raw_schema_text)), 0) INTO accumulated_bytes
        FROM ui_layout_baseline_metadata_evidence WHERE source_draft_ref = NEW.source_draft_ref;
    IF accumulated_bytes + octet_length(NEW.raw_schema_text) > 1048576 THEN
        RAISE EXCEPTION 'metadata evidence exceeds composition quota';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_ui_layout_metadata_evidence_insert
    BEFORE INSERT ON ui_layout_baseline_metadata_evidence
    FOR EACH ROW EXECUTE FUNCTION guard_ui_layout_metadata_evidence_insert();
CREATE TRIGGER tr_ui_layout_metadata_evidence_immutable
    BEFORE UPDATE OR DELETE ON ui_layout_baseline_metadata_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_ui_layout_immutable_mutation();
CREATE TRIGGER tr_ui_layout_metadata_evidence_no_truncate
    BEFORE TRUNCATE ON ui_layout_baseline_metadata_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ui_layout_immutable_mutation();

COMMENT ON TABLE ui_layout_baseline_metadata_evidence IS
    'Immutable native baseline and raw metadata observations per draft/target. Integrity is not producer attestation or current permission.';
