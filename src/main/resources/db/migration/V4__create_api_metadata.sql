CREATE TABLE IF NOT EXISTS api_metadata (
    id BIGSERIAL PRIMARY KEY,
    path VARCHAR(1024) NOT NULL,
    method VARCHAR(16) NOT NULL,
    tags TEXT,
    summary TEXT,
    description TEXT,
    operation_id TEXT,
    request_schema TEXT,
    response_schema TEXT,
    parameters TEXT,
    raw_json TEXT,
    embedding vector(768),
    CONSTRAINT uk_api_metadata_path_method UNIQUE (path, method)
);

CREATE INDEX IF NOT EXISTS idx_api_metadata_path ON api_metadata(path);
