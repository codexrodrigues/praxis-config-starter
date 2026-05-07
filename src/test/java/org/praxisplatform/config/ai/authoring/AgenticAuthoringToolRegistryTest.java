package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;

@Tag("unit")
class AgenticAuthoringToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesSearchApiResourcesAsInternalRouteScopedTool() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        assertThat(registry.definitions())
                .singleElement()
                .satisfies(definition -> {
                    assertThat(definition.name()).isEqualTo("searchApiResources");
                    assertThat(definition.allowedRoutes())
                            .containsExactlyInAnyOrder(
                                    "component_authoring",
                                    "shared_rule_authoring",
                                    "mixed",
                                    "needs_clarification",
                                    "advisory_authoring");
                    assertThat(definition.ownerSurface())
                            .isEqualTo("praxis-config-starter:/api/praxis/config/ai/authoring/resource-candidates");
                    assertThat(definition.sideEffectClass()).isEqualTo("read_only");
                    assertThat(definition.governanceLevel()).isEqualTo("safe_grounding");
                    assertThat(definition.auditRedactionPolicy()).isEqualTo("safe_event_projection_only");
                });
    }

    @Test
    void executesSearchApiResourcesThroughRegistry() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "analytics,folha,pagamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica de folha de pagamento por departamento",
                        "listVwAnalyticsFolhaPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper));

        AgenticAuthoringToolResult result = registry.execute(new AgenticAuthoringToolCall(
                "searchApiResources",
                "component_authoring",
                new AgenticAuthoringResourceCandidatesRequest(
                        "graficos de folha de pagamento",
                        null,
                        "dashboard",
                        5)));

        assertThat(result.valid()).isTrue();
        assertThat(result.tool()).isEqualTo("searchApiResources");
        assertThat(result.payload()).isInstanceOf(AgenticAuthoringResourceCandidatesResult.class);
        AgenticAuthoringResourceCandidatesResult payload =
                (AgenticAuthoringResourceCandidatesResult) result.payload();
        assertThat(payload.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.safeDiagnostics())
                .containsEntry("candidateCount", 1)
                .containsEntry("artifactKind", "dashboard")
                .containsEntry("retrievalQuery", "graficos de folha de pagamento")
                .containsEntry("retrievalSource", "lexical_fallback");
    }

    @Test
    void rejectsToolExecutionOutsideDeclaredRouteScope() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(new AgenticAuthoringToolCall(
                "searchApiResources",
                "unsupported_route",
                new AgenticAuthoringResourceCandidatesRequest("funcionarios", null, "form", 5)));

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-route-not-allowed");
        assertThat(result.errorMessage()).contains("unsupported_route");
    }

    @Test
    void returnsStructuredFailureForInvalidPayload() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(new AgenticAuthoringToolCall(
                "searchApiResources",
                "component_authoring",
                "funcionarios"));

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-payload-invalid");
        assertThat(result.errorMessage()).contains("AgenticAuthoringResourceCandidatesRequest");
    }

    @Test
    void returnsStructuredFailureForUnknownTool() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(new AgenticAuthoringToolCall(
                "unknownTool",
                "component_authoring",
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-not-found");
    }
}
