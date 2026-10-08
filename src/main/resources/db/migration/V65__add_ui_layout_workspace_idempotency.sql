ALTER TABLE ui_layout_draft
    ADD COLUMN creation_idempotency_key VARCHAR(180);

CREATE UNIQUE INDEX uq_ui_layout_draft_creation_idempotency
    ON ui_layout_draft (
        tenant_id,
        environment,
        root_component_type,
        root_component_id,
        created_by,
        creation_idempotency_key
    )
    WHERE creation_idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX uq_ui_layout_release_source_draft
    ON ui_layout_release (source_draft_id)
    WHERE source_draft_id IS NOT NULL;
