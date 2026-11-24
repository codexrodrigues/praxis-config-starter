package org.praxisplatform.config.ai.agent.trajectory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.praxisplatform.config.ai.agent.provider.GoogleGenAiAgentToolGateway;
import org.praxisplatform.config.ai.agent.runtime.AgentSystemInstructionComposer;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.runtime.PraxisOperationalAgentLoop;
import org.praxisplatform.config.ai.agent.security.AgentSecurityMode;
import org.praxisplatform.config.ai.agent.security.DefaultDeterministicAgentSecurityGuard;
import org.praxisplatform.config.ai.agent.spi.AgentAnalyticalProvenance;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDossier;
import org.praxisplatform.config.ai.agent.spi.AgentEntityMatch;
import org.praxisplatform.config.ai.agent.spi.AgentEntitySearchRequest;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentQueryResult;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentStatsResult;
import org.praxisplatform.config.ai.agent.spi.AgentStatsResult.AgentStatsBucket;
import org.praxisplatform.config.ai.agent.spi.AgentTopicDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentVerifiedRecipeExecutionRequest;
import org.praxisplatform.config.ai.agent.spi.CompactAgentSchemaDigest;
import org.praxisplatform.config.ai.agent.telemetry.MicrometerGenAiAgentTelemetryRecorder;
import org.praxisplatform.config.ai.agent.tools.DiscoverTopicResourcesAndSchemasTool;
import org.praxisplatform.config.ai.agent.tools.ExecuteVerifiedAnalyticalRecipeTool;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiThinkingProfile;

/**
 * Opt-in live LLM trajectory smoke suite verifying end-to-end tool selection against a real
 * configured Gemini key. Excluded from default Maven Surefire builds ({@code ai-trajectory-live}).
 */
@Tag("external")
@Tag("ai-trajectory-live")
@EnabledIfEnvironmentVariable(named = "PRAXIS_AI_LIVE_TRAJECTORY", matches = "true")
class OperationalAgentLiveTrajectoryTest {

    @Test
    void liveModelRespectsVerifiedRecipePreference() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        assertThat(apiKey).isNotBlank();

        GoogleGenAiAgentToolGateway gateway = new GoogleGenAiAgentToolGateway(
                new ObjectMapper(), apiKey, "gemini-2.5-flash");

        AgentResourceExecutionBridge bridge = new AgentResourceExecutionBridge() {
            @Override
            public List<AgentTopicDescriptor> listTopics(AgentExecutionContext context) {
                return List.of(new AgentTopicDescriptor(
                        "workforce", "Workforce", "Workforce assignments",
                        "workforce.assignments", List.of("/api/workforce/assignments"),
                        List.of("workforce"), "", 1));
            }

            @Override
            public List<AgentEntityDescriptor> listEntities(AgentExecutionContext context, String topicOrQuery) {
                return List.of(new AgentEntityDescriptor(
                        "workforce.assignments", "workforce.assignments", "/api/workforce/assignments",
                        "Assignments", "Workforce assignments", "badge", "id", "title", "status",
                        List.of(), "taxId", "name", "id", "/assignments", "workforce", 1));
            }

            @Override
            public java.util.Optional<CompactAgentSchemaDigest> describeEntitySchema(
                    AgentExecutionContext context, String entityKeyOrResourcePath) {
                return java.util.Optional.empty();
            }

            @Override
            public List<AgentEntityMatch> searchEntities(
                    AgentExecutionContext context, AgentEntitySearchRequest request) {
                return List.of();
            }

            @Override
            public AgentQueryResult queryRecords(
                    AgentExecutionContext context, String entityKeyOrResourcePath,
                    Map<String, Object> filters, int page, int size, String sort) {
                return new AgentQueryResult(
                        entityKeyOrResourcePath, entityKeyOrResourcePath, entityKeyOrResourcePath,
                        "Assignments", 0L, 0, 0, List.of(), List.of(), List.of());
            }

            @Override
            public java.util.Optional<AgentEntityDossier> loadEntityDossier(
                    AgentExecutionContext context, String entityKeyOrResourcePath, String entityId, List<String> includeRelatedSurfaces) {
                return java.util.Optional.empty();
            }

            @Override
            public AgentStatsResult executeVerifiedAnalyticalRecipe(
                    AgentExecutionContext context, AgentVerifiedRecipeExecutionRequest request) {
                return new AgentStatsResult(
                        "workforce.assignments", "workforce.assignments", "/api/workforce/assignments",
                        "Active Headcount", "Certified", "GROUP_BY", "bar", "departmentCode", "Department",
                        "COUNT", null, null, 10L,
                        List.of(new AgentStatsBucket("ENG", "Engineering", java.math.BigDecimal.TEN, 10L, null, null)),
                        null,
                        AgentAnalyticalProvenance.verifiedRecipe(
                                request.recipeId(), "Active Headcount", "1.0.0", "CERTIFIED", "live-digest",
                                Map.of("status", "ACTIVE"), Map.of()));
            }
        };

        PraxisOperationalAgentLoop loop = new PraxisOperationalAgentLoop(
                gateway,
                List.of(new DiscoverTopicResourcesAndSchemasTool(bridge), new ExecuteVerifiedAnalyticalRecipeTool(bridge)),
                new OperationalAssistantBlockComposer(new ObjectMapper()),
                4,
                bridge,
                AgentSystemInstructionComposer.defaultComposer(),
                new DefaultDeterministicAgentSecurityGuard(),
                AgentSecurityMode.ENFORCE,
                new MicrometerGenAiAgentTelemetryRecorder(io.micrometer.observation.ObservationRegistry.NOOP));

        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "live-tenant", "live-user", "dev", "live", java.util.Set.of(),
                null, null, null, Map.of());

        PraxisOperationalAgentLoop.AgentTurnOutcome outcome = loop.execute(
                ctx,
                "Execute execute_verified_analytical_recipe para entityKey workforce.assignments com recipeId active-headcount.",
                null,
                List.of(),
                AiCallConfig.builder()
                        .provider("gemini")
                        .model("gemini-2.5-flash")
                        .thinkingProfile(AiThinkingProfile.FAST_LOOKUP)
                        .build(),
                PraxisOperationalAgentLoop.TurnProgressObserver.NOOP);

        assertThat(outcome.assistantMessage()).isNotBlank();
    }
}
