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
}
