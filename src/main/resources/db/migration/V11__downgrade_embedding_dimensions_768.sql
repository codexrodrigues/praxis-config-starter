-- Revert embeddings to 768 dimensions for broader provider compatibility.
-- Existing vectors are cleared and must be re-ingested after migration.

DROP INDEX IF EXISTS idx_ai_registry_embedding;
DROP INDEX IF EXISTS idx_api_metadata_embedding;

UPDATE ai_registry SET embedding = NULL;
UPDATE api_metadata SET embedding = NULL;

ALTER TABLE ai_registry
    ALTER COLUMN embedding TYPE vector(768);

ALTER TABLE api_metadata
    ALTER COLUMN embedding TYPE vector(768);

CREATE INDEX IF NOT EXISTS idx_ai_registry_embedding
    ON ai_registry USING ivfflat (embedding vector_l2_ops)
    WITH (lists = 100);

CREATE INDEX IF NOT EXISTS idx_api_metadata_embedding
    ON api_metadata USING ivfflat (embedding vector_l2_ops)
    WITH (lists = 100);
