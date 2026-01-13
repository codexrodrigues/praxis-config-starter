-- Migrate legacy component_type values (table/form/tabs/page) to selector-based component types.
-- Uses NOT EXISTS to avoid unique constraint collisions when selector-based records already exist.

UPDATE ui_user_config u
SET component_type = 'praxis-table'
WHERE u.component_type = 'table'
  AND NOT EXISTS (
    SELECT 1
    FROM ui_user_config u2
    WHERE u2.tenant_id = u.tenant_id
      AND u2.user_id IS NOT DISTINCT FROM u.user_id
      AND u2.component_id = u.component_id
      AND u2.environment IS NOT DISTINCT FROM u.environment
      AND u2.component_type = 'praxis-table'
  );

UPDATE ui_user_config u
SET component_type = 'praxis-dynamic-form'
WHERE u.component_type = 'form'
  AND NOT EXISTS (
    SELECT 1
    FROM ui_user_config u2
    WHERE u2.tenant_id = u.tenant_id
      AND u2.user_id IS NOT DISTINCT FROM u.user_id
      AND u2.component_id = u.component_id
      AND u2.environment IS NOT DISTINCT FROM u.environment
      AND u2.component_type = 'praxis-dynamic-form'
  );

UPDATE ui_user_config u
SET component_type = 'praxis-tabs'
WHERE u.component_type = 'tabs'
  AND NOT EXISTS (
    SELECT 1
    FROM ui_user_config u2
    WHERE u2.tenant_id = u.tenant_id
      AND u2.user_id IS NOT DISTINCT FROM u.user_id
      AND u2.component_id = u.component_id
      AND u2.environment IS NOT DISTINCT FROM u.environment
      AND u2.component_type = 'praxis-tabs'
  );

UPDATE ui_user_config u
SET component_type = 'praxis-dynamic-gridster-page'
WHERE u.component_type = 'page'
  AND NOT EXISTS (
    SELECT 1
    FROM ui_user_config u2
    WHERE u2.tenant_id = u.tenant_id
      AND u2.user_id IS NOT DISTINCT FROM u.user_id
      AND u2.component_id = u.component_id
      AND u2.environment IS NOT DISTINCT FROM u.environment
      AND u2.component_type = 'praxis-dynamic-gridster-page'
  );
