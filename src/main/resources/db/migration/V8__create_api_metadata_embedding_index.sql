DO $$
DECLARE
    dim INTEGER := (
        SELECT atttypmod
        FROM pg_attribute
        WHERE attrelid = 'api_metadata'::regclass AND attname = 'embedding'
    );
BEGIN
    IF dim IS NOT NULL AND dim > 0 THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_api_metadata_embedding ON api_metadata USING ivfflat (embedding vector_l2_ops) WITH (lists = 100)';
    ELSE
        RAISE NOTICE 'Skipping ivfflat index on api_metadata.embedding because dimension is not set';
    END IF;
END$$;
