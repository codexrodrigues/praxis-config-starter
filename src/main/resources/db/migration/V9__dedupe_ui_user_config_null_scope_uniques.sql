-- Deduplicate ui_user_config rows (keeps most recently updated per scope).
WITH ranked AS (
  SELECT
    id,
    ROW_NUMBER() OVER (
      PARTITION BY tenant_id, component_type, component_id, environment, user_id
      ORDER BY updated_at DESC, created_at DESC, id DESC
    ) AS rn
  FROM ui_user_config
)
DELETE FROM ui_user_config
WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

-- Enforce uniqueness when environment/user_id are NULL (Postgres treats NULLs as distinct).
CREATE UNIQUE INDEX IF NOT EXISTS uk_ui_user_cfg_scope_null
  ON ui_user_config (tenant_id, component_type, component_id)
  WHERE environment IS NULL AND user_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ui_user_cfg_env_null
  ON ui_user_config (tenant_id, component_type, component_id, user_id)
  WHERE environment IS NULL AND user_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ui_user_cfg_user_null
  ON ui_user_config (tenant_id, component_type, component_id, environment)
  WHERE environment IS NOT NULL AND user_id IS NULL;
