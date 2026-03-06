CREATE DOMAIN IF NOT EXISTS jsonb AS VARCHAR(20000);

DROP TABLE IF EXISTS ai_turn_event;
DROP TABLE IF EXISTS ai_turn;
DROP TABLE IF EXISTS ai_thread;

CREATE TABLE IF NOT EXISTS ai_thread (
    thread_id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    environment VARCHAR(64),
    user_id VARCHAR(128),
    component_type VARCHAR(64) NOT NULL,
    component_id VARCHAR(255) NOT NULL,
    route_key VARCHAR(255),
    title VARCHAR(120),
    status VARCHAR(16) NOT NULL,
    summary TEXT,
    schema_hash VARCHAR(128),
    variant_id VARCHAR(128),
    last_config_etag VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_turn (
    thread_id UUID NOT NULL,
    turn_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    PRIMARY KEY (thread_id, turn_id),
    CONSTRAINT fk_ai_turn_thread FOREIGN KEY (thread_id) REFERENCES ai_thread(thread_id)
);

CREATE TABLE IF NOT EXISTS ai_turn_event (
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    environment VARCHAR(64),
    stream_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    turn_id UUID NOT NULL,
    seq BIGINT NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload jsonb NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (thread_id, turn_id, seq),
    CONSTRAINT uk_ai_turn_event_event_id UNIQUE (event_id),
    CONSTRAINT fk_ai_turn_event_turn FOREIGN KEY (thread_id, turn_id) REFERENCES ai_turn(thread_id, turn_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_turn_event_stream_seq
    ON ai_turn_event (stream_id, seq);
