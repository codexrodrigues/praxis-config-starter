-- Reactive and other runtime materializations expose one effective head per exact scoped target.
-- Existing inactive or duplicate heads are retained as governed history and marked superseded.
UPDATE domain_rule_materialization materialization
SET status = 'superseded',
    updated_at = now()
FROM domain_rule_definition definition
WHERE materialization.rule_definition_id = definition.id
  AND materialization.status = 'applied'
  AND definition.status <> 'active';

WITH ranked_heads AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY
                   COALESCE(tenant_id, ''),
                   COALESCE(environment, ''),
                   target_layer,
                   target_artifact_type,
                   target_artifact_key
               ORDER BY applied_at DESC NULLS LAST, updated_at DESC, id DESC
           ) AS head_rank
    FROM domain_rule_materialization
    WHERE status = 'applied'
)
UPDATE domain_rule_materialization materialization
SET status = 'superseded',
    updated_at = now()
FROM ranked_heads ranked
WHERE materialization.id = ranked.id
  AND ranked.head_rank > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_domain_rule_materialization_applied_target_head
    ON domain_rule_materialization (
        COALESCE(tenant_id, ''),
        COALESCE(environment, ''),
        target_layer,
        target_artifact_type,
        target_artifact_key
    )
    WHERE status = 'applied';

ALTER TABLE domain_rule_event
    DROP CONSTRAINT IF EXISTS ck_domain_rule_event_type;

ALTER TABLE domain_rule_event
    ADD CONSTRAINT ck_domain_rule_event_type
        CHECK (event_type IN (
            'definition.created',
            'definition.approved',
            'definition.activated',
            'materialization.created',
            'materialization.applied',
            'materialization.superseded',
            'intake.received',
            'simulation.requested',
            'simulation.completed',
            'publication.requested',
            'publication.completed',
            'approval.requested',
            'approval.completed'
        ));
