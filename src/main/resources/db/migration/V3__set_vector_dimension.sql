-- Set explicit vector dimension and recreate ivfflat indexes when dimension is known
ALTER TABLE component_definition
    ALTER COLUMN embedding TYPE vector(768) USING embedding;

ALTER TABLE ui_configuration
    ALTER COLUMN embedding TYPE vector(768) USING embedding;

-- Recreate ivfflat indexes now that dimensions are set
DROP INDEX IF EXISTS idx_component_definition_embedding;
DROP INDEX IF EXISTS idx_ui_configuration_embedding;

CREATE INDEX IF NOT EXISTS idx_component_definition_embedding
    ON component_definition USING ivfflat (embedding vector_l2_ops) WITH (lists = 100);

CREATE INDEX IF NOT EXISTS idx_ui_configuration_embedding
    ON ui_configuration USING ivfflat (embedding vector_l2_ops) WITH (lists = 100);
