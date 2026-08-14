ALTER TABLE domain_rule_test_scenario
  ADD COLUMN IF NOT EXISTS expected_reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS expected_effect_intents JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE domain_rule_test_run_result
  ADD COLUMN IF NOT EXISTS expected_output JSONB,
  ADD COLUMN IF NOT EXISTS candidate_output JSONB,
  ADD COLUMN IF NOT EXISTS active_output JSONB,
  ADD COLUMN IF NOT EXISTS candidate_output_matches_expected BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS active_output_matches_expected BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS expected_reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS candidate_reason_codes_match_expected BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS active_reason_codes_match_expected BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS expected_effect_intents JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS candidate_effect_intents JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS active_effect_intents JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS candidate_effects_match_expected BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS active_effects_match_expected BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE domain_rule_test_scenario
  ADD CONSTRAINT ck_domain_rule_scenario_expected_reasons_array
  CHECK (jsonb_typeof(expected_reason_codes) = 'array'),
  ADD CONSTRAINT ck_domain_rule_scenario_expected_effects_array
  CHECK (jsonb_typeof(expected_effect_intents) = 'array');

ALTER TABLE domain_rule_test_run_result
  ADD CONSTRAINT ck_domain_rule_run_expected_reasons_array
  CHECK (jsonb_typeof(expected_reason_codes) = 'array'),
  ADD CONSTRAINT ck_domain_rule_run_expected_effects_array
  CHECK (jsonb_typeof(expected_effect_intents) = 'array'),
  ADD CONSTRAINT ck_domain_rule_run_candidate_effects_array
  CHECK (jsonb_typeof(candidate_effect_intents) = 'array'),
  ADD CONSTRAINT ck_domain_rule_run_active_effects_array
  CHECK (jsonb_typeof(active_effect_intents) = 'array');
