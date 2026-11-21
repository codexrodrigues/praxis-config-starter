ALTER TABLE domain_rule_change_workspace
    ADD COLUMN IF NOT EXISTS draft_documentation JSONB;
