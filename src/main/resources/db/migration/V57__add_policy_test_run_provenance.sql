ALTER TABLE domain_rule_test_run
  ADD COLUMN IF NOT EXISTS baseline_evidence JSONB;

ALTER TABLE domain_rule_test_run_result
  ADD COLUMN IF NOT EXISTS operational_evidence JSONB;

ALTER TABLE domain_rule_test_run
  ADD CONSTRAINT ck_domain_rule_test_run_baseline_evidence_object
  CHECK (baseline_evidence IS NULL OR jsonb_typeof(baseline_evidence) = 'object');

ALTER TABLE domain_rule_test_run_result
  ADD CONSTRAINT ck_domain_rule_test_run_operational_evidence_object
  CHECK (operational_evidence IS NULL OR jsonb_typeof(operational_evidence) = 'object');
