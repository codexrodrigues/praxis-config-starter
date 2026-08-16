package org.praxisplatform.config.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class QuickstartGovernanceLabSmokeContractTest {

    private static final Path SMOKE_SCRIPT = Path.of(
            "tools", "Invoke-QuickstartAgenticAuthoringHttpSmokeSuite.ps1");

    @Test
    void shouldConfigureEveryGovernanceLabIdentityRequiredByTheQuickstart() throws IOException {
        String script = Files.readString(SMOKE_SCRIPT);
        List<String> requiredEnvironmentVariables = List.of(
                "APP_AUTH_GOVERNANCE_AUTHOR_USERNAME",
                "APP_AUTH_GOVERNANCE_AUTHOR_PASSWORD",
                "APP_AUTH_GOVERNANCE_APPROVER_A_USERNAME",
                "APP_AUTH_GOVERNANCE_APPROVER_A_PASSWORD",
                "APP_AUTH_GOVERNANCE_APPROVER_B_USERNAME",
                "APP_AUTH_GOVERNANCE_APPROVER_B_PASSWORD",
                "APP_AUTH_GOVERNANCE_PUBLISHER_USERNAME",
                "APP_AUTH_GOVERNANCE_PUBLISHER_PASSWORD",
                "APP_AUTH_GOVERNANCE_OPERATOR_USERNAME",
                "APP_AUTH_GOVERNANCE_OPERATOR_PASSWORD",
                "APP_AUTH_GOVERNANCE_AUDITOR_USERNAME",
                "APP_AUTH_GOVERNANCE_AUDITOR_PASSWORD");

        assertThat(requiredEnvironmentVariables)
                .allSatisfy(variable -> assertThat(script).contains("$env:" + variable + " ="));
    }

    @Test
    void shouldUseDistinctTechnicalActorsForTheGovernanceLifecycle() throws IOException {
        String script = Files.readString(SMOKE_SCRIPT);

        assertThat(script)
                .contains("$governanceAuthorUsername = \"$UserId-author\"")
                .contains("$governanceApproverAUsername = \"$UserId-approver-a\"")
                .contains("$governanceApproverBUsername = \"$UserId-approver-b\"")
                .contains("$governancePublisherUsername = \"$UserId-publisher\"")
                .contains("$governanceOperatorUsername = \"$UserId-operator\"")
                .contains("$governanceAuditorUsername = \"$UserId-auditor\"")
                .contains("$domainRuleArgs.AuthorUsername = $governanceAuthorUsername")
                .contains("$domainRuleArgs.ReviewerUsername = $governanceApproverAUsername")
                .contains("$expectAuthorApprovalIamRejection = $DomainRuleLifecycleOnly.IsPresent")
                .contains("$domainRuleArgs.ExpectAuthorApprovalIamRejection = $expectAuthorApprovalIamRejection")
                .doesNotContain("$domainRuleArgs.ExpectAuthorApprovalIamRejection = $true");
    }
}
