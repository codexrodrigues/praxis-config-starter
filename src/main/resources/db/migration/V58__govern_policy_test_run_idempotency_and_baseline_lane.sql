ALTER TABLE domain_rule_test_run
  ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(180),
  ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);

UPDATE domain_rule_test_run
SET idempotency_key = 'legacy-' || id::text
WHERE idempotency_key IS NULL;

UPDATE domain_rule_test_run
SET request_hash = UPPER(REPLACE(id::text, '-', '') || REPLACE(id::text, '-', ''))
WHERE request_hash IS NULL;

ALTER TABLE domain_rule_test_run
  ALTER COLUMN idempotency_key SET NOT NULL,
  ALTER COLUMN request_hash SET NOT NULL,
  ADD CONSTRAINT ck_domain_rule_test_run_request_hash
    CHECK (request_hash ~ '^[A-F0-9]{64}$');

CREATE UNIQUE INDEX IF NOT EXISTS uq_domain_rule_test_run_idempotency
  ON domain_rule_test_run (tenant_id, environment, workspace_id, idempotency_key);

ALTER TABLE domain_rule_change_workspace
  ADD COLUMN IF NOT EXISTS submitted_test_run_id UUID,
  ADD CONSTRAINT fk_domain_rule_change_workspace_submitted_test_run
    FOREIGN KEY (submitted_test_run_id) REFERENCES domain_rule_test_run(id) ON DELETE RESTRICT;

ALTER TABLE domain_rule_test_run_result
  ADD COLUMN IF NOT EXISTS baseline_result JSONB,
  ADD COLUMN IF NOT EXISTS candidate_baseline_comparison VARCHAR(32),
  ADD COLUMN IF NOT EXISTS baseline_matches_expected BOOLEAN,
  ADD COLUMN IF NOT EXISTS baseline_output_matches_expected BOOLEAN,
  ADD COLUMN IF NOT EXISTS baseline_reason_codes_match_expected BOOLEAN,
  ADD COLUMN IF NOT EXISTS baseline_effects_match_expected BOOLEAN,
  ADD CONSTRAINT ck_domain_rule_test_run_baseline_result_object
    CHECK (baseline_result IS NULL OR jsonb_typeof(baseline_result) = 'object'),
  ADD CONSTRAINT ck_domain_rule_test_run_candidate_baseline_comparison
    CHECK (candidate_baseline_comparison IS NULL OR candidate_baseline_comparison IN
      ('MATCH', 'MISMATCH', 'INCONCLUSIVE', 'TECHNICAL_ERROR'));
