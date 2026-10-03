-- Preserve V66/V67 and historical payloads. Missing historical assembly inputs
-- cannot be reconstructed from current templates/preferences; the Java reader denies them.
ALTER TABLE ui_layout_baseline_metadata_evidence
    ADD COLUMN assembly_input_version INTEGER,
    ADD COLUMN assembler_ref VARCHAR(1024),
    ADD COLUMN assembly_input_text TEXT,
    ADD COLUMN assembly_input_hash VARCHAR(64);

-- NOT VALID preserves old incomplete rows; all new writes must satisfy this constraint.
-- IS NOT NULL is explicit: SQL CHECK otherwise accepts UNKNOWN for missing values.
ALTER TABLE ui_layout_baseline_metadata_evidence ADD CONSTRAINT ck_ui_layout_evidence_assembly CHECK (
    (origin_kind = 'OPERATION_SCHEMA'
        AND assembly_input_version IS NOT NULL AND assembly_input_version = 1
        AND assembler_ref IS NOT NULL AND length(btrim(assembler_ref)) > 0
        AND assembler_ref = btrim(assembler_ref)
        AND assembly_input_text IS NOT NULL AND octet_length(assembly_input_text) <= 262144
        AND json_typeof(assembly_input_text::json) = 'object'
        AND assembly_input_hash IS NOT NULL AND assembly_input_hash ~ '^[0-9a-f]{64}$')
    OR
    (origin_kind = 'NATIVE_DOCUMENT'
        AND assembly_input_version IS NULL AND assembler_ref IS NULL
        AND assembly_input_text IS NULL AND assembly_input_hash IS NULL)
) NOT VALID;

CREATE OR REPLACE FUNCTION guard_ui_layout_metadata_evidence_insert()
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
    -- Preserve the scoped draft lock used by competing inserts and lifecycle transitions.
    SELECT state INTO draft_state FROM ui_layout_draft
        WHERE id = NEW.source_draft_ref AND tenant_id = NEW.tenant_id
          AND environment = NEW.environment AND root_component_type = NEW.root_component_type
          AND root_component_id = NEW.root_component_id FOR UPDATE;
    IF draft_state IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'metadata evidence requires an editable scoped draft';
    END IF;
    SELECT COALESCE(SUM(octet_length(raw_schema_text) + COALESCE(octet_length(assembly_input_text), 0)), 0)
        INTO accumulated_bytes FROM ui_layout_baseline_metadata_evidence WHERE source_draft_ref = NEW.source_draft_ref;
    IF accumulated_bytes + octet_length(NEW.raw_schema_text)
        + COALESCE(octet_length(NEW.assembly_input_text), 0) > 1048576 THEN
        RAISE EXCEPTION 'metadata evidence exceeds composition quota';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Existing FK, target uniqueness, immutable UPDATE/DELETE/TRUNCATE triggers remain in force.
-- JSON syntax/digest checks do not establish source authority or execute the assembler.
