-- Persist the server-attested semantic authoring source beside its executable UI materialization.
-- Runtime consumers continue to read payload; authoring consumers may reopen authoring_source.
ALTER TABLE ui_user_config
    ADD COLUMN IF NOT EXISTS authoring_source JSONB;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_ui_user_config_authoring_source_object'
          AND conrelid = 'ui_user_config'::regclass
    ) THEN
        ALTER TABLE ui_user_config
            ADD CONSTRAINT chk_ui_user_config_authoring_source_object
            CHECK (authoring_source IS NULL OR jsonb_typeof(authoring_source) = 'object');
    END IF;
END$$;

COMMENT ON COLUMN ui_user_config.authoring_source IS
    'Server-attested semantic source and provenance for the current executable payload; null when no aligned source exists.';
