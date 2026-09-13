-- NULL is deliberate: absence of evidence in an old row is not evidence of no application.
ALTER TABLE domain_rule_materialization
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN ever_applied BOOLEAN;

UPDATE domain_rule_materialization m
SET ever_applied = TRUE
WHERE m.status = 'applied' OR m.applied_at IS NOT NULL OR EXISTS (
    SELECT 1 FROM domain_rule_event e
    WHERE e.event_type = 'materialization.applied'
      AND e.materialization_id = m.id
      AND e.rule_definition_id = m.rule_definition_id
      AND e.tenant_id IS NOT DISTINCT FROM m.tenant_id
      AND e.environment IS NOT DISTINCT FROM m.environment
      AND e.target_layer IS NOT DISTINCT FROM m.target_layer
      AND e.target_artifact_type IS NOT DISTINCT FROM m.target_artifact_type
      AND e.target_artifact_key IS NOT DISTINCT FROM m.target_artifact_key
      AND e.materialization_key IS NOT DISTINCT FROM m.materialization_key
);

-- The default applies only to future inserts, not to inconclusive legacy rows.
ALTER TABLE domain_rule_materialization
    ALTER COLUMN ever_applied SET DEFAULT FALSE,
    ADD CONSTRAINT ck_domain_rule_materialization_row_version CHECK (row_version >= 0),
    ADD CONSTRAINT ck_domain_rule_materialization_applied_history
        CHECK (status <> 'applied' OR ever_applied IS TRUE),
    ADD CONSTRAINT ck_domain_rule_materialization_superseded_history
        CHECK (status <> 'superseded' OR ever_applied IS DISTINCT FROM FALSE);

CREATE FUNCTION preserve_domain_rule_materialization_history() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Domain rule application history cannot be truncated' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.ever_applied IS DISTINCT FROM FALSE THEN
            RAISE EXCEPTION 'Domain rule application history cannot be deleted' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF (OLD.ever_applied IS TRUE AND NEW.ever_applied IS DISTINCT FROM TRUE)
            OR (OLD.ever_applied IS NULL AND NEW.ever_applied IS FALSE) THEN
            RAISE EXCEPTION 'Domain rule application history cannot be reset' USING ERRCODE = '23514';
        END IF;
        IF OLD.ever_applied IS DISTINCT FROM FALSE AND
            ROW(NEW.id, NEW.tenant_id, NEW.environment, NEW.rule_definition_id,
                NEW.materialization_key, NEW.target_layer, NEW.target_artifact_type, NEW.target_artifact_key)
            IS DISTINCT FROM
            ROW(OLD.id, OLD.tenant_id, OLD.environment, OLD.rule_definition_id,
                OLD.materialization_key, OLD.target_layer, OLD.target_artifact_type, OLD.target_artifact_key) THEN
            RAISE EXCEPTION 'Domain rule application history identity is immutable' USING ERRCODE = '23514';
        END IF;
        -- Hibernate already sends OLD+1. SQL writers omitting the version still invalidate stale entities.
        IF NEW.row_version = OLD.row_version THEN
            NEW.row_version := OLD.row_version + 1;
        ELSIF NEW.row_version IS DISTINCT FROM OLD.row_version + 1 THEN
            RAISE EXCEPTION 'Domain rule materialization version must advance by one' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.row_version IS DISTINCT FROM 0 THEN
            RAISE EXCEPTION 'New domain rule materialization version must be zero' USING ERRCODE = '23514';
        END IF;
        IF NEW.ever_applied IS NULL THEN
            NEW.ever_applied := FALSE;
        END IF;
    END IF;
    -- Superseded is reachable only from applied. Keep inconclusive legacy NULL,
    -- but do not allow new/imported superseded rows to claim never applied.
    IF NEW.status = 'applied' OR NEW.applied_at IS NOT NULL
        OR (NEW.status = 'superseded' AND NEW.ever_applied IS FALSE) THEN
        NEW.ever_applied := TRUE;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_domain_rule_materialization_history
BEFORE INSERT OR UPDATE OR DELETE ON domain_rule_materialization
FOR EACH ROW EXECUTE FUNCTION preserve_domain_rule_materialization_history();

CREATE TRIGGER trg_domain_rule_materialization_history_truncate
BEFORE TRUNCATE ON domain_rule_materialization
FOR EACH STATEMENT EXECUTE FUNCTION preserve_domain_rule_materialization_history();

-- An old application event can outlive its materialization. Keep that independent evidence,
-- including through the definition FK's existing ON DELETE CASCADE path.
CREATE FUNCTION preserve_domain_rule_application_event() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Domain rule application history cannot be truncated' USING ERRCODE = '23514';
    END IF;
    IF OLD.event_type = 'materialization.applied' THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'Domain rule application event cannot be deleted' USING ERRCODE = '23514';
        END IF;
        RAISE EXCEPTION 'Domain rule application event is immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_domain_rule_application_event_history
BEFORE UPDATE OR DELETE ON domain_rule_event
FOR EACH ROW EXECUTE FUNCTION preserve_domain_rule_application_event();

CREATE TRIGGER trg_domain_rule_application_event_history_truncate
BEFORE TRUNCATE ON domain_rule_event
FOR EACH STATEMENT EXECUTE FUNCTION preserve_domain_rule_application_event();
