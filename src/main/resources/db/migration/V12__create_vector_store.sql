CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS vector_store (
    id TEXT PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding vector(768)
);

CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON vector_store USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
