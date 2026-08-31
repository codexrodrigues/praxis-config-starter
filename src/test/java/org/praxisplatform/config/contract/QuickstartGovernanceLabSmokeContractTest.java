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
    private static final Path LIFECYCLE_SCRIPT = Path.of(
            "tools", "Invoke-QuickstartDomainRuleLifecycleHttpE2E.ps1");

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
                .contains("$domainRuleArgs.PublisherUsername = $governancePublisherUsername")
                .contains("$domainRuleArgs.PublisherPassword = $governancePublisherPassword")
                .contains("$domainRuleArgs.OperatorUsername = $governanceOperatorUsername")
                .contains("$domainRuleArgs.OperatorPassword = $governanceOperatorPassword")
                .contains("$expectAuthorApprovalIamRejection = $DomainRuleLifecycleOnly.IsPresent")
                .contains("$domainRuleArgs.ExpectAuthorApprovalIamRejection = $expectAuthorApprovalIamRejection")
                .doesNotContain("$domainRuleArgs.ExpectAuthorApprovalIamRejection = $true");
    }

    @Test
    void shouldUseOneGovernedAuthoringApplyJourneyForThePaidProviderGate() throws IOException {
        String script = Files.readString(SMOKE_SCRIPT);

        assertThat(script)
                .contains("Invoke-QuickstartAgenticAuthoringApplyHttpE2E.ps1")
                .contains("liveGateJourney = \"governed-authoring-apply\"")
                .contains("isolatedLegacyProviderProbesRun = $false")
                .doesNotContain("Invoke-QuickstartAgenticAuthoringIntentResolutionHttpE2E.ps1")
                .doesNotContain("Invoke-QuickstartAgenticAuthoringPlanHttpE2E.ps1")
                .doesNotContain("Invoke-QuickstartAgenticAuthoringCompileHttpE2E.ps1")
                .doesNotContain("Invoke-QuickstartAgenticAuthoringPreviewHttpE2E.ps1")
                .doesNotContain("Invoke-QuickstartAiPatchStreamHttpE2E.ps1");
    }

    @Test
    void shouldPublishDomainRulesWithTheDedicatedPublisherIdentity() throws IOException {
        String script = Files.readString(LIFECYCLE_SCRIPT);

        assertThat(script)
                .contains("$publisherHeaders = Add-AuthenticatedCookie")
                .contains("-Headers $publisherHeaders")
                .contains("publishedBy = $publisherUserId");
        assertThat(script.split(java.util.regex.Pattern.quote(
                "-Uri \"$base/api/praxis/config/domain-rules/publications\""), -1))
                .hasSize(7);
        assertThat(java.util.regex.Pattern.compile(
                        "-Uri \\\"\\$base/api/praxis/config/domain-rules/publications\\\" `\\R\\s+-Headers \\$publisherHeaders")
                .matcher(script).results().count()).isEqualTo(6);
        assertThat(script).doesNotContain(
                "-Uri \"$base/api/praxis/config/domain-rules/publications\" `\n    -Headers $headers");
    }

    @Test
    void shouldUseTheAuthorizedActorForEachMaterializationTransition() throws IOException {
        String script = Files.readString(LIFECYCLE_SCRIPT);

        assertThat(script)
                .contains("$operatorHeaders = Add-AuthenticatedCookie")
                .containsSubsequence(
                        "$appliedCreationBlocked = Invoke-ExpectedFailure",
                        "-Headers $publisherHeaders",
                        "status = \"applied\"")
                .containsSubsequence(
                        "$appliedMaterialization = Invoke-JsonRequest",
                        "-Headers $publisherHeaders",
                        "status = \"applied\"")
                .containsSubsequence(
                        "$failedMaterialization = Invoke-JsonRequest",
                        "-Headers $operatorHeaders",
                        "status = \"failed\"")
                .containsSubsequence(
                        "$terminalMaterializationTransitionBlocked = Invoke-ExpectedFailure",
                        "-Headers $publisherHeaders",
                        "status = \"applied\"");
    }

    @Test
    void shouldProveReuseBeforeACompetingDecisionSupersedesTheTargetHead() throws IOException {
        String script = Files.readString(LIFECYCLE_SCRIPT);

        int initialPublication = script.indexOf("$inactivePublication = Invoke-JsonRequest");
        int reusedPublication = script.indexOf("$inactiveRepublish = Invoke-JsonRequest");
        int competingPublication = script.indexOf("$suspendedPublication = Invoke-JsonRequest");

        assertThat(initialPublication).isGreaterThanOrEqualTo(0);
        assertThat(reusedPublication).isGreaterThan(initialPublication);
        assertThat(competingPublication).isGreaterThan(reusedPublication);
        assertThat(script)
                .contains("Prove deterministic reuse while this definition still owns the applied target head")
                .contains("Publishing the competing suspended rule below correctly supersedes this materialization");
    }
}
