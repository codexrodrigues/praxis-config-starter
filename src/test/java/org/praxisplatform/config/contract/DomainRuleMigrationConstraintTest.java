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
}
