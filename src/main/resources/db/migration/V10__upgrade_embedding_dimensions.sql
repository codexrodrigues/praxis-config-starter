-- Upgrade embeddings to 3072 dimensions (OpenAI text-embedding-3-large).
-- Existing vectors are cleared and must be re-ingested after migration.

DROP INDEX IF EXISTS idx_ai_registry_embedding;
DROP INDEX IF EXISTS idx_api_metadata_embedding;

UPDATE ai_registry SET embedding = NULL;
UPDATE api_metadata SET embedding = NULL;

ALTER TABLE ai_registry
    ALTER COLUMN embedding TYPE vector(3072);

ALTER TABLE api_metadata
    ALTER COLUMN embedding TYPE vector(3072);

-- ivfflat index only when dimension <= 2000 (pgvector limit)
DO $$
DECLARE
    reg_dim INTEGER := (
        SELECT atttypmod
        FROM pg_attribute
        WHERE attrelid = 'ai_registry'::regclass AND attname = 'embedding'
    );
    resolved_dim INTEGER := COALESCE(reg_dim - 4, 0);
BEGIN
    IF resolved_dim > 0 AND resolved_dim <= 2000 THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_ai_registry_embedding ON ai_registry USING ivfflat (embedding vector_l2_ops) WITH (lists = 100)';
    ELSE
        RAISE NOTICE 'Skipping ivfflat index on ai_registry.embedding; dimension % is not supported.', resolved_dim;
    END IF;
END$$;

DO $$
DECLARE
    reg_dim INTEGER := (
        SELECT atttypmod
        FROM pg_attribute
        WHERE attrelid = 'api_metadata'::regclass AND attname = 'embedding'
    );
    resolved_dim INTEGER := COALESCE(reg_dim - 4, 0);
BEGIN
    IF resolved_dim > 0 AND resolved_dim <= 2000 THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_api_metadata_embedding ON api_metadata USING ivfflat (embedding vector_l2_ops) WITH (lists = 100)';
    ELSE
        RAISE NOTICE 'Skipping ivfflat index on api_metadata.embedding; dimension % is not supported.', resolved_dim;
    END IF;
END$$;
