-- AUTHORIZATION lifecycle. The Config role writes only the approved artifacts and deployment pointer.
ALTER TABLE domain_rule_definition_approval
    ADD CONSTRAINT uq_domain_rule_approval_release_scope
        UNIQUE (id, tenant_id, environment, definition_id, definition_hash);

CREATE TABLE authz_policy_release (
    id uuid PRIMARY KEY,
    instance_id varchar(255) NOT NULL,
    tenant_id varchar(128) NOT NULL,
    environment varchar(128) NOT NULL,
    service_key varchar(255) NOT NULL,
    definition_id uuid NOT NULL REFERENCES domain_rule_definition(id),
    approval_id uuid NOT NULL,
    definition_hash varchar(128) NOT NULL,
    published_by varchar(255) NOT NULL,
    published_at timestamptz NOT NULL DEFAULT current_timestamp,
    based_on jsonb NOT NULL,
    forward_head jsonb NOT NULL,
    forward_archive bytea NOT NULL,
    forward_policies jsonb NOT NULL,
    forward_pins jsonb NOT NULL,
    forward_verification jsonb NOT NULL,
    rollback_head jsonb NOT NULL,
    rollback_archive bytea NOT NULL,
    rollback_policies jsonb NOT NULL,
    rollback_pins jsonb NOT NULL,
    rollback_verification jsonb NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('published','activating','active','reverting','reverted')),
    activation_requested_by varchar(255),
    activation_observed_head jsonb,
    activation_observed_at timestamptz,
    rollback_requested_by varchar(255),
    rollback_observed_head jsonb,
    rollback_observed_at timestamptz,
    FOREIGN KEY (approval_id, tenant_id, environment, definition_id, definition_hash)
        REFERENCES domain_rule_definition_approval(id, tenant_id, environment, definition_id, definition_hash),
    CHECK (state <> 'active' OR activation_observed_head = forward_head
        AND activation_observed_head IS NOT NULL AND activation_observed_at IS NOT NULL),
    CHECK (state <> 'reverted' OR rollback_observed_head = rollback_head
        AND rollback_observed_head IS NOT NULL AND rollback_observed_at IS NOT NULL),
    UNIQUE (instance_id, tenant_id, environment, definition_id, definition_hash, based_on),
    UNIQUE (instance_id, tenant_id, environment, id)
);

CREATE TABLE authz_policy_deployment (
    instance_id varchar(255) PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    environment varchar(128) NOT NULL,
    release_id uuid NOT NULL,
    direction varchar(8) NOT NULL CHECK (direction IN ('forward','rollback')),
    FOREIGN KEY (instance_id, tenant_id, environment, release_id)
        REFERENCES authz_policy_release(instance_id, tenant_id, environment, id)
);
