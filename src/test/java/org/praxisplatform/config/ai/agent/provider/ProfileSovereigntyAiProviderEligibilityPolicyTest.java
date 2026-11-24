package org.praxisplatform.config.ai.agent.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.agent.model.AgentConversationItem;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AgentTokenUsage;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.service.AiCallConfig;

@Tag("unit")
class ProfileSovereigntyAiProviderEligibilityPolicyTest {

    @Test
    void allowsByDefaultWhenNoRestrictionsAndNoSovereignOnlyClaimConfigured() {
        ProfileSovereigntyAiProviderEligibilityPolicy policy =
                new ProfileSovereigntyAiProviderEligibilityPolicy(List.of(), List.of(), List.of(), true);

        AgentExecutionContext ctx = contextWith("/api/workforce/employees", Map.of());
        assertThat(policy.evaluate("gemini", "gemini-2.5-flash", ctx).eligible()).isTrue();
        assertThat(policy.evaluate("openai", "gpt-4o-mini", ctx).eligible()).isTrue();
    }

    @Test
    void enforcesFailClosedSovereigntyForRestrictedProfilePrefixAndClaimWithRouterFailover() {
        ProfileSovereigntyAiProviderEligibilityPolicy policy =
                new ProfileSovereigntyAiProviderEligibilityPolicy(
                        List.of("CONFIDENTIAL_FINANCIAL", "PERSONAL"),
                        List.of("/api/workforce/restricted-payroll"),
                        List.of("openai"),
                        true);

        AgentExecutionContext restrictedByProfile = contextWith(
                "/api/workforce/dashboard",
                Map.of("dataClassification", "CONFIDENTIAL_FINANCIAL"));
        AgentExecutionContext restrictedByPath = contextWith(
                "/api/workforce/restricted-payroll/items",
                Map.of());
        AgentExecutionContext restrictedByClaim = contextWith(
                "/api/workforce/public-catalog",
                Map.of("sovereignOnly", true));

        assertThat(policy.evaluate("gemini", "gemini-2.5-flash", restrictedByProfile).eligible()).isFalse();
        assertThat(policy.evaluate("gemini", "gemini-2.5-flash", restrictedByPath).eligible()).isFalse();
        assertThat(policy.evaluate("gemini", "gemini-2.5-flash", restrictedByClaim).eligible()).isFalse();
        assertThat(policy.evaluate("openai", "sovereign-llm-v1", restrictedByProfile).eligible()).isTrue();

        AiToolCallingProvider gemini = stubProvider("gemini", req ->
                new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "external", List.of(), AgentTokenUsage.ZERO, "stop", "id-ext", Map.of()));
        AiToolCallingProvider sovereignOpenAi = stubProvider("openai", req ->
                new AgentModelTurnResponse("openai", "sovereign-llm-v1", "sovereign ok", List.of(), AgentTokenUsage.ZERO, "stop", "id-sov", Map.of()));

        RoutingAiToolCallingProvider router = new RoutingAiToolCallingProvider(
                null,
                List.of(gemini, sovereignOpenAi),
                null,
                List.of(policy));

        AgentModelTurnResponse response = router.generateTurn(
                new AgentModelTurnRequest(
                        "sys",
                        List.of(AgentConversationItem.userText("Analyze payroll")),
                        List.of(),
                        AiCallConfig.builder()
                                .provider("gemini")
                                .apiKey("k-primary")
                                .model("gemini-2.5-flash")
                                .build(),
                        false,
                        Map.of(),
                        restrictedByProfile),
                null);
        assertThat(response.provider()).isEqualTo("openai");

        // Fail-closed when zero sovereign providers are configured
        ProfileSovereigntyAiProviderEligibilityPolicy emptySovereign =
                new ProfileSovereigntyAiProviderEligibilityPolicy(
                        List.of("PERSONAL"), List.of(), List.of(), true);
        RoutingAiToolCallingProvider strictRouter = new RoutingAiToolCallingProvider(
                null, List.of(gemini), null, List.of(emptySovereign));

        assertThatThrownBy(() -> strictRouter.generateTurn(
                new AgentModelTurnRequest(
                        "sys",
                        List.of(AgentConversationItem.userText("Analyze personal dossier")),
                        List.of(),
                        AiCallConfig.builder().provider("gemini").apiKey("k-ext").build(),
                        false,
                        Map.of(),
                        contextWith("/api/workforce/personal", Map.of("executionProfile", "PERSONAL"))),
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sovereignty policy");
    }

    @Test
    void enforcesRestrictedTopicsPathSegmentsAndOpenAiAliasNormalization() {
        ProfileSovereigntyAiProviderEligibilityPolicy policy =
                new ProfileSovereigntyAiProviderEligibilityPolicy(
                        List.of(),
                        List.of(),
                        List.of("payroll"),
                        List.of("confidential-payslip"),
                        List.of("open-ai"),
                        true);

        AgentExecutionContext restrictedByTopic = new AgentExecutionContext(
                null, null, null, "tenant-a", "user-1", "prod", "auditor",
                Set.of("ROLE_READ"), "/api/workforce/public-view", "payroll", Map.of(), Map.of());
        AgentExecutionContext restrictedBySegment = new AgentExecutionContext(
                null, null, null, "tenant-a", "user-1", "prod", "auditor",
                Set.of("ROLE_READ"), "/api/workforce/v2/confidential-payslip/items", "general", Map.of(), Map.of());
        AgentExecutionContext publicCtx = new AgentExecutionContext(
                null, null, null, "tenant-a", "user-1", "prod", "auditor",
                Set.of("ROLE_READ"), "/api/workforce/public-catalog", "catalog", Map.of(), Map.of());

        assertThat(policy.evaluate("gemini", "gemini-2.5-flash", restrictedByTopic).eligible()).isFalse();
        assertThat(policy.evaluate("gemini", "gemini-2.5-flash", restrictedBySegment).eligible()).isFalse();
        assertThat(policy.evaluate("open-ai", "self-hosted", restrictedByTopic).eligible()).isTrue();
        assertThat(policy.evaluate("openai", "self-hosted", restrictedBySegment).eligible()).isTrue();
        assertThat(policy.evaluate("gemini", "gemini-2.5-flash", publicCtx).eligible()).isTrue();

        AgentExecutionContext midTurnTopicEscalated = contextWith(
                "/api/workforce/public-catalog",
                Map.of("accessedTopicKeys", List.of("catalog", "payroll")));
        AgentExecutionContext midTurnPathEscalated = contextWith(
                "/api/workforce/public-catalog",
                Map.of("accessedResourcePaths", List.of("/api/workforce/v2/confidential-payslip/items")));
        assertThat(policy.evaluate("gemini", "gemini-2.5-flash", midTurnTopicEscalated).eligible()).isFalse();
        assertThat(policy.evaluate("gemini", "gemini-2.5-flash", midTurnPathEscalated).eligible()).isFalse();
        assertThat(policy.evaluate("openai", "self-hosted", midTurnTopicEscalated).eligible()).isTrue();
    }

    private static AgentExecutionContext contextWith(String screenPath, Map<String, Object> claims) {
        return new AgentExecutionContext(
                null, null, null, "tenant-a", "user-1", "prod", "auditor",
                Set.of("ROLE_READ"), screenPath, "workforce", Map.of(), claims);
    }

    private static AiToolCallingProvider stubProvider(
            String id,
            java.util.function.Function<AgentModelTurnRequest, AgentModelTurnResponse> fn) {
        return new AiToolCallingProvider() {
            @Override
            public String providerId() {
                return id;
            }

            @Override
            public boolean hasConfiguredCredentials(AiCallConfig callConfig) {
                return true;
            }

            @Override
            public AgentModelTurnResponse generateTurn(
                    AgentModelTurnRequest request,
                    org.praxisplatform.config.ai.agent.model.AgentModelStreamListener listener) {
                return fn.apply(request);
            }
        };
    }
}
