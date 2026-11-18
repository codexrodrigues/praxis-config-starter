-- Link governed bulk operational assistant proposals to native praxis_bulk proposals and executions.
ALTER TABLE ai_agent_action_proposal
    ADD COLUMN IF NOT EXISTS bulk_proposal_id VARCHAR(120),
    ADD COLUMN IF NOT EXISTS bulk_operation_id VARCHAR(200),
    ADD COLUMN IF NOT EXISTS bulk_mode VARCHAR(40),
    ADD COLUMN IF NOT EXISTS bulk_atomicity VARCHAR(32),
    ADD COLUMN IF NOT EXISTS bulk_execution_id VARCHAR(120),
    ADD COLUMN IF NOT EXISTS bulk_totals_json TEXT;

CREATE INDEX IF NOT EXISTS idx_ai_agent_proposal_bulk
    ON ai_agent_action_proposal (bulk_proposal_id, bulk_execution_id);
