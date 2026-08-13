package org.praxisplatform.config.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("smoke")
class DomainRuleMigrationConstraintTest {

    @Test
    void latestDomainRuleConstraintMigrationAllowsApprovalPolicyAndTargetLayer() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V24__expand_domain_rule_constraints_for_approval_policy.sql"));

        assertThat(migration).contains("approval_policy");
        assertThat(migration).contains("workflow_action_policy");
        assertThat(migration).contains("workflow_action");
        assertThat(migration).contains("selection_eligibility");
        assertThat(migration).contains("option_source");
        assertThat(migration).contains("ck_domain_rule_definition_type");
        assertThat(migration).contains("ck_domain_rule_materialization_target_layer");
    }

    @Test
    void domainRuleEventMigrationCreatesSafeAppendOnlyEventSource() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V25__create_domain_rule_event.sql"));

        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS domain_rule_event");
        assertThat(migration).contains("rule_definition_id UUID NOT NULL REFERENCES domain_rule_definition(id)");
        assertThat(migration).contains("materialization_id UUID REFERENCES domain_rule_materialization(id)");
        assertThat(migration).contains("safe_metadata JSONB NOT NULL DEFAULT '{}'::jsonb");
        assertThat(migration).contains("ck_domain_rule_event_type");
        assertThat(migration).contains("definition.created");
        assertThat(migration).contains("materialization.applied");
        assertThat(migration).contains("publication.completed");
        assertThat(migration).contains("approval.completed");
        assertThat(migration).contains("ck_domain_rule_event_visibility");
        assertThat(migration).contains("CHECK (visibility IN ('safe'))");
        assertThat(migration).contains("ck_domain_rule_event_safe_metadata_object");
        assertThat(migration).contains("idx_domain_rule_event_definition_time");
    }

    @Test
    void snapshotControlPlaneSeparatesImmutableContentFromMutableHead() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V30__create_domain_rule_snapshot_control_plane.sql"));

        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot");
        assertThat(migration).contains("snapshot_payload JSONB NOT NULL");
        assertThat(migration).contains("content_hash VARCHAR(64) NOT NULL");
        assertThat(migration).contains("uq_domain_rule_snapshot_version");
        assertThat(migration).contains("supersedes_snapshot_id UUID REFERENCES domain_rule_snapshot(id)");
        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot_head");
        assertThat(migration).contains("head_etag UUID NOT NULL");
        assertThat(migration).contains("activation_revision BIGINT NOT NULL");
        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot_event");
        assertThat(migration).contains("'PUBLISHED', 'ROLLED_BACK'");
        assertThat(migration).contains("uq_domain_rule_snapshot_head");
    }

    @Test
    void cleanInstallBaselineIncludesSnapshotControlPlaneSchema() throws IOException {
        String baseline = Files.readString(Path.of(
                "src/main/resources/db/baseline/V1__baseline.sql"));

        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot (");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot_head (");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot_event (");
        assertThat(baseline).contains("uq_domain_rule_snapshot_version");
        assertThat(baseline).contains("uq_domain_rule_snapshot_head");
        assertThat(baseline).contains("fk_domain_rule_snapshot_head_active_scope");
        assertThat(baseline).contains("fk_domain_rule_snapshot_event_to_scope");
        assertThat(baseline).contains("'PUBLISHED', 'ACTIVATED', 'ROLLED_BACK'");
    }

    @Test
    void explicitActivationMigrationExpandsTheAppendOnlyEventVocabulary() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V44__allow_explicit_rule_snapshot_activation.sql"));

        assertThat(migration).contains("DROP CONSTRAINT IF EXISTS domain_rule_snapshot_event_event_type_check");
        assertThat(migration).contains("'PUBLISHED', 'ACTIVATED', 'ROLLED_BACK'");
    }

    @Test
    void snapshotReferencesAreConstrainedToTheSameGovernedScope() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V32__enforce_domain_rule_snapshot_scope_references.sql"));

        assertThat(migration).contains("uq_domain_rule_snapshot_scope_id");
        assertThat(migration).contains("fk_domain_rule_snapshot_supersedes_scope");
        assertThat(migration).contains("fk_domain_rule_snapshot_head_active_scope");
        assertThat(migration).contains("fk_domain_rule_snapshot_event_from_scope");
        assertThat(migration).contains("fk_domain_rule_snapshot_event_to_scope");
        assertThat(migration).contains(
                "FOREIGN KEY (active_snapshot_id, tenant_id, environment, rule_set_key)");
        assertThat(migration).contains(
                "REFERENCES domain_rule_snapshot (id, tenant_id, environment, rule_set_key)");
    }

    @Test
    void compositionApprovalMigrationPreservesLegacyAuditDataAndAddsDigestEvidence() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V33__bind_snapshot_to_approved_composition.sql"));

        assertThat(migration).contains("composition_manifest JSONB");
        assertThat(migration).contains("composition_digest VARCHAR(64)");
        assertThat(migration).contains("ck_domain_rule_snapshot_composition_digest");
        assertThat(migration).doesNotContain("DELETE FROM domain_rule_snapshot");
    }

    @Test
    void makerCheckerMigrationPersistsIamBoundApprovalsAppendOnly() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V35__persist_rule_composition_approvals.sql"));
        String baseline = Files.readString(Path.of(
                "src/main/resources/db/baseline/V1__baseline.sql"));

        assertThat(migration).contains("CREATE TABLE domain_rule_composition_approval");
        assertThat(migration).contains("actor_ref VARCHAR(255) NOT NULL");
        assertThat(migration).contains("CHECK (role = 'RULE_COMPOSITION_APPROVER')");
        assertThat(migration).contains(
                "UNIQUE (tenant_id, environment, composition_digest, actor_ref)");
        assertThat(migration).doesNotContain("ON DELETE CASCADE");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_composition_approval");
        assertThat(baseline).contains("uq_domain_rule_composition_approval_actor");
    }

    @Test
    void definitionMakerCheckerBindsApprovalToExactContentAndRejectsMutation() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V36__persist_rule_definition_approvals.sql"));
        assertThat(migration).contains("CREATE TABLE domain_rule_definition_approval");
        assertThat(migration).contains("'authenticated'");
        assertThat(migration).contains(
                "definition_id UUID NOT NULL REFERENCES domain_rule_definition(id) ON DELETE RESTRICT");
        assertThat(migration).contains("definition_hash VARCHAR(64) NOT NULL");
        assertThat(migration).contains("CHECK (role = 'RULE_DEFINITION_APPROVER')");
        assertThat(migration).contains("BEFORE UPDATE OR DELETE");
    }

    @Test
    void authenticatedDefinitionActorsCanBePersistedInSafeTimelineEvents() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V37__allow_authenticated_domain_rule_event_actors.sql"));

        assertThat(migration).contains("DROP CONSTRAINT ck_domain_rule_event_actor_type");
        assertThat(migration).contains("'authenticated'");
        assertThat(migration).contains("actor_type IS NULL");
    }

    @Test
    void authenticatedReviewersCanBePersistedWhenApplyingMaterializations() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V41__allow_authenticated_domain_rule_materialization_actors.sql"));

        assertThat(migration).contains("DROP CONSTRAINT ck_domain_rule_materialization_applied_by_type");
        assertThat(migration).contains("'authenticated'");
        assertThat(migration).contains("applied_by_type IS NULL");
    }

    @Test
    void backendReactiveDeterminationMigrationUsesOneTypedCanonicalTarget() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V45__add_backend_reactive_determination_materialization.sql"));

        assertThat(migration).contains("target_layer = 'frontend_adapter'");
        assertThat(migration).contains("RAISE EXCEPTION");
        assertThat(migration).contains("'backend_determination'");
        assertThat(migration).contains("'resource-reactive-determination'");
        assertThat(migration).contains("ck_domain_rule_materialization_backend_determination_type");
        assertThat(migration).contains("ck_domain_rule_materialization_backend_determination_key");
        assertThat(migration).contains("ck_domain_rule_materialization_backend_determination_payload");
        assertThat(migration).contains("jsonb_array_length(materialized_payload -> 'inputs')");
        assertThat(migration).contains("<= 64");
        assertThat(migration).doesNotContain("'frontend_adapter',");
    }

    @Test
    void openApiKeepsExistingMaterializationTargetsExtensible() throws IOException {
        String contract = Files.readString(Path.of(
                "docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml"));
        String targetSchemas = contract.substring(
                contract.indexOf("    DomainRuleTargetLayer:"),
                contract.indexOf("    DomainRuleReactiveDeterminationSpec:"));

        assertThat(targetSchemas).contains("backend_determination");
        assertThat(targetSchemas).contains("resource-reactive-determination");
        assertThat(targetSchemas).contains("policy_engine");
        assertThat(targetSchemas).contains("spring-service");
        assertThat(targetSchemas).contains("opa-policy");
        assertThat(targetSchemas).doesNotContain("enum:");
    }

}
