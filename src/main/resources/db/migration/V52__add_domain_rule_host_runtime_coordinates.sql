ALTER TABLE domain_rule_host_status
    ADD COLUMN IF NOT EXISTS engine_contract_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS json_logic_dialect_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS json_logic_corpus_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS implementation_catalog_digest VARCHAR(64);

ALTER TABLE domain_rule_host_status
    DROP CONSTRAINT IF EXISTS ck_domain_rule_host_status_corpus_hash,
    DROP CONSTRAINT IF EXISTS ck_domain_rule_host_status_catalog_hash;

ALTER TABLE domain_rule_host_status
    ADD CONSTRAINT ck_domain_rule_host_status_corpus_hash
        CHECK (json_logic_corpus_sha256 IS NULL OR json_logic_corpus_sha256 ~ '^[A-F0-9]{64}$'),
    ADD CONSTRAINT ck_domain_rule_host_status_catalog_hash
        CHECK (implementation_catalog_digest IS NULL OR implementation_catalog_digest ~ '^[A-F0-9]{64}$');

ALTER TABLE domain_rule_host_status
    DROP CONSTRAINT IF EXISTS ck_domain_rule_host_status_ready_identity;

-- Beta clean migration: an old ready heartbeat did not prove runtime compatibility.
UPDATE domain_rule_host_status
SET ready = FALSE,
    failure_code = 'COMPATIBILITY_REPORT_REQUIRED'
WHERE ready = TRUE
  AND (engine_contract_version IS NULL
    OR json_logic_dialect_version IS NULL
    OR json_logic_corpus_sha256 IS NULL
    OR implementation_catalog_digest IS NULL);

ALTER TABLE domain_rule_host_status
    ADD CONSTRAINT ck_domain_rule_host_status_ready_identity
        CHECK (NOT ready OR (
            loaded_snapshot_key IS NOT NULL
            AND loaded_snapshot_content_hash IS NOT NULL
            AND activation_revision IS NOT NULL
            AND engine_contract_version IS NOT NULL
            AND json_logic_dialect_version IS NOT NULL
            AND json_logic_corpus_sha256 IS NOT NULL
            AND implementation_catalog_digest IS NOT NULL
        ));
