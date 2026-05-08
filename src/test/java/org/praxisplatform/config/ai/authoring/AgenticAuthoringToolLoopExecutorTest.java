package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.AiPrincipalContext;

@Tag("unit")
class AgenticAuthoringToolLoopExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesGovernedMultiToolLoopWithSafeTrace() {
        AgenticAuthoringToolLoopExecutor loop = new AgenticAuthoringToolLoopExecutor(
                registryWithOrdersResource(),
                searchPlanner(),
                3);

        AgenticAuthoringToolLoopResult result = loop.run(
                request(),
                new AiPrincipalContext("tenant", "user", "local", true),
                intent(),
                preview(List.of("semantic-preview-chart-required")),
                "component_authoring");

        assertThat(result.completed()).isTrue();
        assertThat(result.terminalReason()).isEqualTo("completed");
        assertThat(result.trace())
                .extracting(AgenticAuthoringToolLoopStep::phase)
                .contains(
                        "loadActiveDecision",
                        "retrieveEvidence",
                        "proposeDecision",
                        "materializePlan",
                        "validatePreview",
                        "repairOrAsk");
        assertThat(result.trace())
                .filteredOn(step -> !step.tool().isBlank())
                .hasSize(2)
                .allSatisfy(step -> {
                    assertThat(step.tool()).isEqualTo(AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES);
                    assertThat(step.valid()).isTrue();
                    assertThat(step.safeDiagnostics()).containsKey("candidateCount");
                    assertThat(step.safeDiagnostics()).doesNotContainKeys("prompt", "apiKey", "payload");
                });
    }

    @Test
    void stopsWhenToolExecutionLimitIsReached() {
        AgenticAuthoringToolLoopExecutor loop = new AgenticAuthoringToolLoopExecutor(
                registryWithOrdersResource(),
                searchPlanner(),
                1);

        AgenticAuthoringToolLoopResult result = loop.run(
                request(),
                new AiPrincipalContext("tenant", "user", "local", true),
                intent(),
                preview(List.of("semantic-preview-chart-required")),
                "component_authoring");

        assertThat(result.completed()).isFalse();
        assertThat(result.terminalReason()).isEqualTo("max-steps-exceeded");
        assertThat(result.trace())
                .filteredOn(step -> !step.tool().isBlank())
                .hasSize(1);
    }

    @Test
    void rejectsPlannerToolThatIsNotAllowedInTheCurrentPhase() {
        AgenticAuthoringToolLoopExecutor loop = new AgenticAuthoringToolLoopExecutor(
                registryWithOrdersResource(),
                context -> "proposeDecision".equals(context.phase())
                        ? Optional.of(searchCall(context.routeClass()))
                        : Optional.empty(),
                3);

        AgenticAuthoringToolLoopResult result = loop.run(
                request(),
                new AiPrincipalContext("tenant", "user", "local", true),
                intent(),
                preview(List.of()),
                "component_authoring");

        assertThat(result.completed()).isFalse();
        assertThat(result.terminalReason()).isEqualTo("tool-phase-not-allowed");
        assertThat(result.trace())
                .filteredOn(step -> "tool-phase-not-allowed".equals(step.errorCode()))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.phase()).isEqualTo("proposeDecision");
                    assertThat(step.tool()).isEqualTo(AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES);
                });
    }

    @Test
    void completesWhenNoRealToolIsRequired() {
        AgenticAuthoringToolLoopExecutor loop = new AgenticAuthoringToolLoopExecutor(
                registryWithOrdersResource(),
                context -> Optional.empty(),
                3);

        AgenticAuthoringToolLoopResult result = loop.run(
                request(),
                new AiPrincipalContext("tenant", "user", "local", true),
                intent(),
                preview(List.of()),
                "component_authoring");

        assertThat(result.completed()).isTrue();
        assertThat(result.terminalReason()).isEqualTo("completed");
        assertThat(result.trace())
                .filteredOn(step -> !step.tool().isBlank())
                .isEmpty();
    }

    private AgenticAuthoringToolLoopPlanner searchPlanner() {
        return context -> {
            if ("retrieveEvidence".equals(context.phase()) || "repairOrAsk".equals(context.phase())) {
                return Optional.of(searchCall(context.routeClass()));
            }
            return Optional.empty();
        };
    }

    private AgenticAuthoringToolCall searchCall(String routeClass) {
        return new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES,
                routeClass,
                new AgenticAuthoringResourceCandidatesRequest(
                        "orders status dashboard",
                        "orders status dashboard",
                        "dashboard",
                        5));
    }

    private AgenticAuthoringToolRegistry registryWithOrdersResource() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(new ApiMetadata(
                "/api/acme/orders",
                "POST",
                "orders,status,dashboard",
                "Orders",
                "Orders grouped by status",
                "filterOrders",
                null,
                "{\"type\":\"object\"}",
                "[]",
                "{}",
                null)));
        return new AgenticAuthoringToolRegistry(new AgenticAuthoringResourceDiscoveryService(
                new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                objectMapper));
    }

    private AgenticAuthoringTurnStreamRequest request() {
        return new AgenticAuthoringTurnStreamRequest(
                "orders status dashboard",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                null,
                null,
                "openai",
                "gpt-5.4-mini",
                "secret-key",
                "session",
                "turn",
                List.of(),
                null,
                List.of(),
                objectMapper.createObjectNode(),
                null,
                null);
    }

    private AgenticAuthoringIntentResolutionResult intent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_chart",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                null,
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                null,
                "Preview ready.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringPreviewResult preview(List<String> failureCodes) {
        return new AgenticAuthoringPreviewResult(
                true,
                failureCodes,
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null,
                objectMapper.createObjectNode(),
                "Preview ready.");
    }
}
