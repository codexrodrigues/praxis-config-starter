ALTER TABLE ai_intelligence_release
    ADD COLUMN IF NOT EXISTS component_corpus_release_id VARCHAR(255);

COMMENT ON COLUMN ai_intelligence_release.component_corpus_release_id IS
    'Physical component corpus release id observed from successful backend ingestion; required before cleanup planning.';
