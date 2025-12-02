CREATE TABLE IF NOT EXISTS config_entries (
    id UUID PRIMARY KEY,
    config_key VARCHAR(255) NOT NULL UNIQUE,
    config_value TEXT NOT NULL
);
