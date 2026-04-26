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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.DomainCatalogPromptContextService;

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
                  "resourceSearchQuery": "pedidos de vendas para graficos",
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
        assertThat(prompt).contains("resourceSearchQuery");
    }

    @Test
    void resolveAddsGovernedDomainContextToContextBundleBeforeCallingProvider() throws Exception {
        DomainCatalogPromptContextService domainCatalogPromptContextService =
                Mockito.mock(DomainCatalogPromptContextService.class);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<JsonNode> contextHintsCaptor = ArgumentCaptor.forClass(JsonNode.class);
        when(domainCatalogPromptContextService.buildPromptContext(
                eq("crie uma regra de aprovação para reembolso"),
                contextHintsCaptor.capture(),
                eq("tenant"),
                eq("local"))).thenReturn("""
                DOMAIN_CATALOG_CONTEXT
                schemaVersion: praxis.domain-catalog.context.v1
                serviceKey: praxis-service
                items:
                - [governance/policy] Reembolso exige aprovação do gestor (finance.reimbursement.approval) | visibility=mask | trainingUse=deny | ruleAuthoring=allow
                """);
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
                  "artifactKind": "unknown",
                  "changeKind": "create_artifact",
                  "selectedResourcePath": null,
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "assistantMessage": "Vou tratar isso como decisão governada.",
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(
                        providerManagementService,
                        objectMapper,
                        domainCatalogPromptContextService);
        JsonNode contextHints = objectMapper.readTree("""
                {
                  "domainCatalog": {
                    "schemaVersion": "praxis.ai.context-hints.domain-catalog/v0.2",
                    "serviceKey": "praxis-service",
                    "resourceKey": "finance.reimbursements",
                    "intent": "authoring",
                    "policyProfile": "authoring",
                    "query": "reembolso aprovação gestor"
                  }
                }
                """);

        service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "crie uma regra de aprovação para reembolso",
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        "session-1",
                        "turn-1",
                        List.of(),
                        null,
                        List.of(),
                        contextHints),
                "crie uma regra de aprovação para reembolso",
                objectMapper.createObjectNode(),
                null,
                List.of(),
                componentCapabilities(),
                "tenant",
                "user",
                "local");

        String prompt = promptCaptor.getValue();
        assertThat(contextHintsCaptor.getValue().path("domainCatalog").path("policyProfile").asText())
                .isEqualTo("authoring");
        assertThat(prompt).contains("\"governedDomainContext\"");
        assertThat(prompt).contains("\"schemaVersion\" : \"praxis-agentic-authoring-governed-domain-context.v1\"");
        assertThat(prompt).contains("\"available\" : true");
        assertThat(prompt).contains("DOMAIN_CATALOG_CONTEXT");
        assertThat(prompt).contains("visibility=mask");
        assertThat(prompt).contains("trainingUse=deny");
        assertThat(prompt).contains("Treat this block as governed semantic grounding");
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

    @Test
    void replacesRedactedQuickReplyPromptWithHumanLabel() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "create",
                  "artifactKind": "dashboard",
                  "changeKind": "create_dashboard",
                  "selectedResourcePath": "/api/human-resources/vw-analytics-folha-pagamento",
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "assistantMessage": "Escolha a fonte de dados.",
                  "quickReplies": [
                    {
                      "id": "use-analytics-view",
                      "kind": "resource",
                      "label": "Usar visão analítica de folha",
                      "prompt": "[REDACTED]",
                      "contextHints": {
                        "resourcePath": "/api/human-resources/vw-analytics-folha-pagamento"
                      }
                    }
                  ],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution resolution = service.resolve(
                        new AgenticAuthoringIntentResolutionRequest(
                                "quero um painel de pagamentos",
                                "page-builder",
                                "praxis-dynamic-page-builder",
                                "/page-builder-ia",
                                objectMapper.createObjectNode(),
                                null,
                                "openai",
                                "gpt-5.4-mini",
                                "test-key"),
                        "quero um painel de pagamentos",
                        objectMapper.createObjectNode(),
                        null,
                        List.of(),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(resolution.quickReplies()).hasSize(1);
        assertThat(resolution.quickReplies().get(0).prompt()).isEqualTo("Usar visão analítica de folha");
        assertThat(resolution.quickReplies().get(0).contextHints().path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
    }

    @Test
    void keepsUnresolvedLlmGuidanceWhenItContainsActionableContext() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": false,
                  "operationKind": "create",
                  "artifactKind": "form",
                  "changeKind": "create_minimal_form",
                  "selectedResourcePath": null,
                  "resourceSearchQuery": "api de cadastro de funcionario para formulario de RH",
                  "followUpKind": "none",
                  "assistantMessage": "Entendi que você quer uma ficha de cadastro. Vou buscar recursos de criação para funcionário.",
                  "quickReplies": [
                    {
                      "id": "search-form-resources",
                      "kind": "suggestion",
                      "label": "Buscar APIs de cadastro",
                      "prompt": "Buscar APIs de cadastro de funcionário",
                      "contextHints": {
                        "tool": "searchApiResources",
                        "artifactKind": "form"
                      }
                    }
                  ],
                  "clarificationQuestions": [],
                  "warnings": ["resource-selection-required"]
                }
                """));
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution resolution = service.resolve(
                        new AgenticAuthoringIntentResolutionRequest(
                                "preciso monta uma ficha pra cadastra funsionario",
                                "page-builder",
                                "praxis-dynamic-page-builder",
                                "/page-builder-ia",
                                objectMapper.createObjectNode(),
                                null,
                                "openai",
                                "gpt-5.4-mini",
                                "test-key"),
                        "preciso monta uma ficha pra cadastra funsionario",
                        objectMapper.createObjectNode(),
                        null,
                        List.of(),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.operationKind()).isEqualTo("create");
        assertThat(resolution.artifactKind()).isEqualTo("form");
        assertThat(resolution.resourceSearchQuery())
                .isEqualTo("api de cadastro de funcionario para formulario de RH");
        assertThat(resolution.quickReplies()).hasSize(1);
        assertThat(resolution.warnings()).contains("resource-selection-required");
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
