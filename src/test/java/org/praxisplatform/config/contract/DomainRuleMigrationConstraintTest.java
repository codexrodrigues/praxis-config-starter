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
    void materializationHeadMigrationSupersedesLegacyDuplicatesAndEnforcesOneAppliedTarget() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V55__enforce_single_applied_materialization_head.sql"));

        assertThat(migration).contains("definition.status <> 'active'");
        assertThat(migration).contains("row_number() OVER");
        assertThat(migration).contains("head_rank > 1");
        assertThat(migration).contains("uq_domain_rule_materialization_applied_target_head");
        assertThat(migration).contains("WHERE status = 'applied'");
        assertThat(migration).contains("'materialization.superseded'");
    }

    @Test
    void cleanInstallBaselineIncludesSnapshotControlPlaneSchema() throws IOException {
        String baseline = Files.readString(Path.of(
                "src/main/resources/db/baseline/V1__baseline.sql"));

        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot (");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot_head (");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot_event (");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_execution_observation (");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_host_status (");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_rollout_policy (");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot_rollout (");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_candidate_probe (");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot_rollout_event (");
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
    void executionObservationMigrationCreatesRedactedScopedAppendOnlyEvidence() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V50__create_domain_rule_execution_observation.sql"));

        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS domain_rule_execution_observation");
        assertThat(migration).contains("observation_id UUID PRIMARY KEY");
        assertThat(migration).contains("host_actor_ref VARCHAR(255) NOT NULL");
        assertThat(migration).contains("duration_micros BETWEEN 0 AND 300000000");
        assertThat(migration).contains("ALLOW", "DENY", "NOT_APPLICABLE", "INCONCLUSIVE", "TECHNICAL_ERROR");
        assertThat(migration).contains("fk_domain_rule_execution_observation_snapshot_scope");
        assertThat(migration).contains(
                "FOREIGN KEY (snapshot_id, tenant_id, environment, rule_set_key)");
        assertThat(migration).doesNotContain("facts", "reason_codes", "request_reference", "snapshot_payload");
    }

    @Test
    void hostStatusMigrationCreatesReplaceableRedactedScopedHeartbeat() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V51__create_domain_rule_host_status.sql"));

        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS domain_rule_host_status");
        assertThat(migration).contains("uq_domain_rule_host_status_scope_actor");
        assertThat(migration).contains("host_actor_ref VARCHAR(255) NOT NULL");
        assertThat(migration).contains("ck_domain_rule_host_status_ready_identity");
        assertThat(migration).doesNotContain("hostname", "ip_address", "facts", "snapshot_payload");
    }

    @Test
    void hostRuntimeCoordinateMigrationMakesReadyCompatibilityComplete() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V52__add_domain_rule_host_runtime_coordinates.sql"));

        assertThat(migration).contains("engine_contract_version VARCHAR(64)");
        assertThat(migration).contains("json_logic_dialect_version VARCHAR(64)");
        assertThat(migration).contains("json_logic_corpus_sha256 VARCHAR(64)");
        assertThat(migration).contains("implementation_catalog_digest VARCHAR(64)");
        assertThat(migration).contains("ck_domain_rule_host_status_ready_identity");
        assertThat(migration).contains("COMPATIBILITY_REPORT_REQUIRED");
        assertThat(migration).doesNotContain("hostname", "ip_address", "facts", "snapshot_payload");
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

    @Test
    void workspaceReviewIsAppendOnlyAndBoundToOneExactRevision() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V48__add_domain_rule_workspace_reviews.sql"));

        assertThat(migration).contains("CREATE TABLE domain_rule_workspace_review");
        assertThat(migration).contains("ON DELETE RESTRICT");
        assertThat(migration).contains("workspace_revision BIGINT NOT NULL");
        assertThat(migration).contains("base_definition_hash VARCHAR(64) NOT NULL");
        assertThat(migration).contains("decision IN ('APPROVE', 'REJECT')");
        assertThat(migration).contains("UNIQUE (workspace_id, workspace_revision)");
    }

    @Test
    void workspacePromotionReferencesExactlyOneCanonicalDefinition() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V49__add_domain_rule_workspace_promotion.sql"));

        assertThat(migration).contains("'PROMOTED'");
        assertThat(migration).contains("promoted_definition_id UUID");
        assertThat(migration).contains("REFERENCES domain_rule_definition(id) ON DELETE RESTRICT");
        assertThat(migration).contains("UNIQUE INDEX uq_domain_rule_change_workspace_promoted_definition");
        assertThat(migration).contains("(status = 'PROMOTED') = (promoted_definition_id IS NOT NULL)");
    }

    @Test
    void stagedRolloutMigrationSeparatesCandidatePreloadFromTheActiveHeartbeat() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V53__create_domain_rule_staged_rollout.sql"));

        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS domain_rule_rollout_policy");
        assertThat(migration).contains("'OBSERVE_ONLY', 'REQUIRED'");
        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot_rollout");
        assertThat(migration).contains("expected_head_etag UUID NOT NULL");
        assertThat(migration).contains("uq_domain_rule_snapshot_rollout_open");
        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS domain_rule_candidate_probe");
        assertThat(migration).contains("uq_domain_rule_candidate_probe_actor");
        assertThat(migration).contains("fk_domain_rule_candidate_probe_rollout_scope");
        assertThat(migration).contains("ck_domain_rule_candidate_probe_ready_coordinates");
        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS domain_rule_snapshot_rollout_event");
        assertThat(migration).contains("fk_domain_rule_rollout_event_scope");
        assertThat(migration).doesNotContain("hostname", "ip_address", "facts", "snapshot_payload");
    }

    @Test
    void rolloutPolicyGovernanceAddsMakerCheckerAntiAbaHeadAndAppendOnlyEvents() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V54__govern_domain_rule_rollout_policy.sql"));
        String baseline = Files.readString(Path.of(
                "src/main/resources/db/baseline/V1__baseline.sql"));

        assertThat(migration).contains("'DRAFT', 'APPROVED', 'ACTIVE', 'SUPERSEDED'");
        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS domain_rule_rollout_policy_head");
        assertThat(migration).contains("head_etag UUID NOT NULL");
        assertThat(migration).contains("activation_revision BIGINT NOT NULL");
        assertThat(migration).contains("fk_domain_rule_rollout_policy_head_active_scope");
        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS domain_rule_rollout_policy_event");
        assertThat(migration).contains("BEFORE UPDATE OR DELETE ON domain_rule_rollout_policy_event");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_rollout_policy_head");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS domain_rule_rollout_policy_event");
    }

    @Test
    void policyTestRunV58MakesRetriesIdempotentAndKeepsBaselineIndependent() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V58__govern_policy_test_run_idempotency_and_baseline_lane.sql"));

        assertThat(migration).contains("idempotency_key VARCHAR(180)");
        assertThat(migration).contains("request_hash VARCHAR(64)");
        assertThat(migration).contains("uq_domain_rule_test_run_idempotency");
        assertThat(migration).contains("tenant_id, environment, workspace_id, idempotency_key");
        assertThat(migration).contains("submitted_test_run_id UUID");
        assertThat(migration).contains("fk_domain_rule_change_workspace_submitted_test_run");
        assertThat(migration).contains("baseline_result JSONB");
        assertThat(migration).contains("candidate_baseline_comparison VARCHAR(32)");
        assertThat(migration).contains("jsonb_typeof(baseline_result) = 'object'");
        assertThat(migration).contains("'MATCH', 'MISMATCH', 'INCONCLUSIVE', 'TECHNICAL_ERROR'");
        assertThat(migration).doesNotContain("oracle_row", "raw_facts", "sql_text", "exception_message");
    }
}
