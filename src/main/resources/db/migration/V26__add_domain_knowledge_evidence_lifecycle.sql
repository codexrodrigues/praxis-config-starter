ALTER TABLE domain_knowledge_evidence
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'active';

ALTER TABLE domain_knowledge_evidence
    ADD COLUMN IF NOT EXISTS superseded_by_evidence_id UUID;

ALTER TABLE domain_knowledge_evidence
    ADD COLUMN IF NOT EXISTS reverted_by_change_set_id UUID;

ALTER TABLE domain_knowledge_evidence
    ADD COLUMN IF NOT EXISTS reverted_at TIMESTAMPTZ;

ALTER TABLE domain_knowledge_evidence
    ADD COLUMN IF NOT EXISTS revert_reason TEXT;

UPDATE domain_knowledge_evidence
SET status = 'active'
WHERE status IS NULL OR btrim(status) = '';

ALTER TABLE domain_knowledge_evidence
    DROP CONSTRAINT IF EXISTS ck_domain_knowledge_evidence_status;

ALTER TABLE domain_knowledge_evidence
    ADD CONSTRAINT ck_domain_knowledge_evidence_status
        CHECK (status IN ('active', 'superseded', 'reverted'));

ALTER TABLE domain_knowledge_evidence
    DROP CONSTRAINT IF EXISTS fk_domain_knowledge_evidence_superseded_by;

ALTER TABLE domain_knowledge_evidence
    ADD CONSTRAINT fk_domain_knowledge_evidence_superseded_by
        FOREIGN KEY (superseded_by_evidence_id)
        REFERENCES domain_knowledge_evidence(id)
        ON DELETE SET NULL;

ALTER TABLE domain_knowledge_evidence
    DROP CONSTRAINT IF EXISTS fk_domain_knowledge_evidence_reverted_by_change_set;

ALTER TABLE domain_knowledge_evidence
    ADD CONSTRAINT fk_domain_knowledge_evidence_reverted_by_change_set
        FOREIGN KEY (reverted_by_change_set_id)
        REFERENCES domain_knowledge_change_set(id)
        ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_evidence_subject_status
    ON domain_knowledge_evidence (tenant_id, environment, subject_type, subject_id, status);

CREATE INDEX IF NOT EXISTS idx_domain_knowledge_evidence_key_status
    ON domain_knowledge_evidence (tenant_id, environment, evidence_key, status);
