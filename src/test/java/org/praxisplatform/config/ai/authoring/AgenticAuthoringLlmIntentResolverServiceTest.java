package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderManagementService;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringLlmIntentResolverServiceTest {

    @Mock
    private AiProviderManagementService providerManagementService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolveSendsStructuredContextBundleAndToolCatalogToProvider() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "create",
                  "artifactKind": "dashboard",
                  "changeKind": "create_chart",
                  "selectedResourcePath": "/api/vendas/pedidos",
                  "followUpKind": "none",
                  "assistantMessage": "Encontrei pedidos como base para o grafico. Quer usar esse recurso?",
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "crie painel de visualizacao de graficos",
                        "page-builder",
                        "praxis-chart",
                        "/page-builder-ia",
                        objectMapper.createObjectNode().put("title", "Vendas"),
                        "chart-1",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        "session-1",
                        "turn-1",
                        List.of(new AgenticAuthoringConversationMessage("m1", "user", "quero um grafico", "2026-04-15T10:00:00Z")),
                        null,
                        List.of(),
                        objectMapper.createObjectNode().put("source", "page-builder")),
                "crie painel de visualizacao de graficos",
                objectMapper.createObjectNode().put("widgetCount", 1),
                null,
                List.of(new AgenticAuthoringCandidate(
                        "/api/vendas/pedidos",
                        "GET",
                        "/schemas/filtered/vendas.pedidos",
                        "/api/vendas/pedidos",
                        "POST",
                        0.93d,
                        "Pedidos parece relevante para graficos de vendas.",
                        List.of("resourceKey:vendas.pedidos"))),
                componentCapabilities(),
                "tenant",
                "user",
                "local");

        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("contextBundle:");
        assertThat(prompt).contains("\"schemaVersion\" : \"praxis-agentic-authoring-context-bundle.v1\"");
        assertThat(prompt).contains("\"runtimeContext\"");
        assertThat(prompt).contains("\"hostApplication\" : \"Angular Praxis Page Builder assistant\"");
        assertThat(prompt).contains("\"retrievalContext\"");
        assertThat(prompt).contains("\"candidateResources\"");
        assertThat(prompt).contains("/api/vendas/pedidos");
        assertThat(prompt).contains("\"componentContext\"");
        assertThat(prompt).contains("\"examples\"");
        assertThat(prompt).contains("Use categoryField para o eixo X");
        assertThat(prompt).contains("\"toolCatalog\"");
        assertThat(prompt).contains("\"searchApiResources\"");
        assertThat(prompt).contains("/api/praxis/config/ai/authoring/resource-candidates");
        assertThat(prompt).contains("Avoid terse labels such as \"alimentar tela\"");
    }

    @Test
    void diagnosticSnapshotExposesExactPromptAndContextBundle() {
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        JsonNode diagnostics = service.diagnosticSnapshot(
                new AgenticAuthoringIntentResolutionRequest(
                        "crie grafico",
                        "page-builder",
                        "praxis-chart",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        "chart-1",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key"),
                "crie grafico",
                objectMapper.createObjectNode().put("widgetCount", 1),
                null,
                List.of(),
                componentCapabilities());

        assertThat(diagnostics.path("schemaVersion").asText())
                .isEqualTo("praxis-agentic-authoring-llm-diagnostics.v1");
        assertThat(diagnostics.path("promptTemplateId").asText())
                .isEqualTo("ai-authoring/page-builder-system-prompt.v1.md");
        assertThat(diagnostics.path("prompt").asText()).contains("contextBundle:");
        assertThat(diagnostics.path("contextBundle").path("runtimeContext").path("hostApplication").asText())
                .isEqualTo("Angular Praxis Page Builder assistant");
        assertThat(diagnostics.path("contextBundle").path("toolCatalog").path("searchApiResources").path("endpoint").asText())
                .isEqualTo("/api/praxis/config/ai/authoring/resource-candidates");
    }

    private AgenticAuthoringComponentCapabilitiesResult componentCapabilities() {
        return new AgenticAuthoringComponentCapabilitiesResult(
                "0",
                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                        "praxis-chart",
                        "0",
                        List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                                "chart.create",
                                "create_chart",
                                List.of("grafico", "chart"),
                                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentFieldAlias(
                                        "categoryField",
                                        List.of("eixo x", "categoria"))),
                                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample(
                                        "crie um grafico por status",
                                        "Agrupar pedidos por status",
                                        List.of("Use categoryField para o eixo X", "Use valueField para a metrica"))))))));
    }
}
