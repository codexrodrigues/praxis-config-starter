package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiProviderManagementService;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringPreviewMessageSynthesizerServiceTest {

    @Mock
    private AiProviderManagementService providerManagementService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void synthesizeReturnsSafeLlmMessageWithPreviewContext() {
        when(providerManagementService.generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn("Criei uma pre-visualizacao usando Resumo missoes como fonte de dados. A tabela foi conectada ao recurso e ja pode carregar schema/dados. Voce pode revisar as colunas, pedir um grafico ou salvar a pagina.");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        String result = service().synthesize(
                request(),
                intent(),
                uiCompositionPlan(),
                true,
                List.of(),
                List.of("compiled-form-patch-materialized-by-page-builder"),
                "fallback seguro",
                "tenant",
                "user",
                "local");

        assertThat(result).contains("Resumo missoes");
        org.mockito.Mockito.verify(providerManagementService).generateText(
                promptCaptor.capture(),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
        assertThat(promptCaptor.getValue())
                .contains("\"label\" : \"Resumo missoes\"")
                .contains("\"sourceLabel\" : \"Resumo missoes\"")
                .doesNotContain("/api/operations/vw-resumo-missoes")
                .doesNotContain("/schemas/filtered")
                .doesNotContain("\"submitMethod\"")
                .doesNotContain("compiled-form-patch-materialized-by-page-builder")
                .doesNotContain("\"warnings\"")
                .doesNotContain("\"failureCodes\"");
        assertThat(promptCaptor.getValue()).contains("\"previewValid\" : true");
        assertThat(promptCaptor.getValue())
                .contains("\"componentKind\" : \"tabela\"")
                .doesNotContain("\"componentId\" : \"praxis-table\"");
    }

    @Test
    void synthesizeSanitizesTechnicalAddressesFromLlmMessage() {
        when(providerManagementService.generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn("Fonte governada: recurso \"/api/operations/vw-resumo-missoes\" (operação POST) foi usado. Schema em /schemas/filtered?path=/api/operations/vw-resumo-missoes/filter.");

        String result = service().synthesize(
                request(),
                intent(),
                uiCompositionPlan(),
                true,
                List.of(),
                List.of(),
                "fallback seguro",
                "tenant",
                "user",
                "local");

        assertThat(result)
                .contains("Resumo missoes")
                .doesNotContain("/api/")
                .doesNotContain("/schemas/")
                .doesNotContain("operação POST")
                .doesNotContain("operacao POST");
    }

    @Test
    void synthesizeRemovesTechnicalObservationLanguageFromLlmMessage() {
        when(providerManagementService.generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn("""
                        Pré-visualização criada com sucesso.
                        - Fonte governada: Indicadores incidentes.
                        - Visualização/componente: praxis-chart com Severidade.
                        - Observação técnica: avisos não impedem a visualização; nenhuma tabela foi incluída.
                        - Próximo passo: revise o gráfico ou salve.
                        """);

        String result = service().synthesize(
                request(),
                intent(),
                uiCompositionPlan(),
                true,
                List.of(),
                List.of("compiled-form-patch-materialized-by-page-builder"),
                "fallback seguro",
                "tenant",
                "user",
                "local");

        assertThat(result)
                .contains("Pré-visualização criada com sucesso")
                .contains("Próximo passo")
                .doesNotContain("Observação técnica")
                .doesNotContain("avisos")
                .doesNotContain("warnings");
    }

    @Test
    void synthesizeIncludesSemanticAxisVerificationInPreviewContext() {
        when(providerManagementService.generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn("Criei uma pre-visualizacao governada com grafico por Severidade. Andamento nao foi materializado porque nao aparece no schema da fonte.");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        String result = service().synthesize(
                request(),
                intent(),
                uiCompositionPlanWithSemanticAxes(),
                true,
                List.of(),
                List.of("semantic-axis-schema-verification-unsupported-axis"),
                "fallback seguro",
                "tenant",
                "user",
                "local");

        assertThat(result).contains("Severidade");
        org.mockito.Mockito.verify(providerManagementService).generateText(
                promptCaptor.capture(),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
        assertThat(promptCaptor.getValue())
                .contains("If uiCompositionSummary.semanticAxes has unsupported axes")
                .contains("\"semanticAxes\"")
                .contains("\"requestedField\" : \"gravidade\"")
                .contains("\"schemaLabel\" : \"Severidade\"")
                .contains("\"schemaProbeStatus\" : \"unsupported\"")
                .contains("\"field\" : \"andamento\"");
    }

    @Test
    void synthesizeUsesDeterministicMessageForSingleChartPreview() {
        String result = service().synthesize(
                request(),
                singleChartIntent(),
                singleChartPlan(),
                true,
                List.of(),
                List.of("ui-composition-plan-provider:generic-resource-chart"),
                "fallback seguro",
                "tenant",
                "user",
                "local");

        assertThat(result)
                .contains("grafico simples")
                .contains("Indicadores incidentes")
                .contains("Severidade")
                .contains("sem tabela, filtros ou KPIs")
                .doesNotContain("praxis-chart")
                .doesNotContain("/api/");
        org.mockito.Mockito.verify(providerManagementService, org.mockito.Mockito.never()).generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void synthesizeUsesDeterministicMessageForChartTypeModification() {
        String result = service().synthesize(
                request(),
                chartModificationIntent(),
                chartModificationPlan(),
                true,
                List.of(),
                List.of("ui-composition-plan-provider:generic-chart-modification"),
                "Entendi o que voce quer criar. Vou usar a fonte de negocio selecionada.",
                "tenant",
                "user",
                "local");

        assertThat(result)
                .contains("Atualizei o grafico selecionado para linhas")
                .contains("Indicadores incidentes")
                .contains("Preservei a dimensao")
                .doesNotContain("Entendi o que voce quer criar")
                .doesNotContain("praxis-chart")
                .doesNotContain("/api/");
        org.mockito.Mockito.verify(providerManagementService, org.mockito.Mockito.never()).generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void synthesizeUsesDeterministicMessageForGovernedChartModificationPlanSignal() {
        String result = service().synthesize(
                request(),
                intent(),
                chartModificationPlan(),
                true,
                List.of(),
                List.of("ui-composition-plan-provider:generic-chart-modification"),
                "Entendi o que voce quer criar. Vou usar a fonte de negocio selecionada.",
                "tenant",
                "user",
                "local");

        assertThat(result)
                .contains("Atualizei o grafico selecionado para linhas")
                .doesNotContain("Entendi o que voce quer criar")
                .doesNotContain("praxis-chart")
                .doesNotContain("/api/");
        org.mockito.Mockito.verify(providerManagementService, org.mockito.Mockito.never()).generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void synthesizeUsesDeterministicMessageForChartModificationArtifactKind() {
        String result = service().synthesize(
                request(),
                chartArtifactModificationIntent(),
                chartModificationPlan(),
                true,
                List.of(),
                List.of(),
                "Entendi: vou trocar o gráfico selecionado para o tipo linha.",
                "tenant",
                "user",
                "local");

        assertThat(result)
                .contains("Atualizei o grafico selecionado para linhas")
                .doesNotContain("vou trocar")
                .doesNotContain("praxis-chart")
                .doesNotContain("/api/");
        org.mockito.Mockito.verify(providerManagementService, org.mockito.Mockito.never()).generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void synthesizeUsesNaturalMessageForChartSurfaceOpenModalModification() {
        String result = service().synthesize(
                request(),
                chartSurfaceOpenModificationIntent(),
                chartSurfaceOpenModificationPlan(),
                true,
                List.of(),
                List.of("ui-composition-plan-provider:generic-chart-surface-open-modification"),
                "Atualizei o grafico selecionado para queryContext.",
                "tenant",
                "user",
                "local");

        assertThat(result)
                .contains("abrir os detalhes da categoria selecionada em um modal")
                .contains("tabela de detalhes conectada")
                .contains("Indicadores incidentes")
                .doesNotContain("queryContext")
                .doesNotContain("surface.open")
                .doesNotContain("praxis-chart")
                .doesNotContain("/api/");
        org.mockito.Mockito.verify(providerManagementService, org.mockito.Mockito.never()).generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void synthesizeIgnoresNonChartTypeFieldsWhenNamingChartModification() {
        ObjectNode plan = chartModificationPlan();
        plan.withArray("ports").addObject().put("type", "queryContext");

        String result = service().synthesize(
                request(),
                chartArtifactModificationIntent(),
                plan,
                true,
                List.of(),
                List.of(),
                "Entendi: vou trocar o gráfico selecionado.",
                "tenant",
                "user",
                "local");

        assertThat(result)
                .contains("Atualizei o grafico selecionado para linhas")
                .doesNotContain("queryContext");
        org.mockito.Mockito.verify(providerManagementService, org.mockito.Mockito.never()).generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void synthesizeFallsBackWhenProviderFails() {
        when(providerManagementService.generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenThrow(new IllegalStateException("provider offline"));

        String result = service().synthesize(
                request(),
                intent(),
                uiCompositionPlan(),
                true,
                List.of(),
                List.of(),
                "fallback seguro",
                "tenant",
                "user",
                "local");

        assertThat(result).isEqualTo("fallback seguro");
    }

    @Test
    void synthesizeFallsBackWhenProviderReturnsStructuredContent() {
        when(providerManagementService.generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn("{\"message\":\"nao deveria expor json\"}");

        String result = service().synthesize(
                request(),
                intent(),
                uiCompositionPlan(),
                true,
                List.of(),
                List.of(),
                "fallback seguro",
                "tenant",
                "user",
                "local");

        assertThat(result).isEqualTo("fallback seguro");
    }

    @Test
    void synthesizeDoesNotClaimSelectedResourceForLocalEditorialComposition() {
        when(providerManagementService.generateText(
                any(String.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn("Criei uma prévia a partir do recurso selecionado com duas abas para o workspace operacional.");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        String result = service().synthesize(
                localEditorialRequest(),
                localEditorialIntent(),
                uiCompositionPlan(),
                true,
                List.of(),
                List.of("explicit-local-ui-composition-resource-selection-bypassed"),
                "",
                "tenant",
                "user",
                "local");

        assertThat(result)
                .contains("conteudo local/editorial")
                .doesNotContain("recurso selecionado")
                .doesNotContain("fonte de dados");
        org.mockito.Mockito.verify(providerManagementService).generateText(
                promptCaptor.capture(),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
        assertThat(promptCaptor.getValue()).contains("\"localEditorialComposition\" : true");
        assertThat(promptCaptor.getValue()).contains("Do not mention selected resource");
    }

    private AgenticAuthoringPreviewMessageSynthesizerService service() {
        return new AgenticAuthoringPreviewMessageSynthesizerService(providerManagementService, objectMapper);
    }

    private AgenticAuthoringPlanRequest request() {
        return new AgenticAuthoringPlanRequest(
                "Crie um dashboard usando resumo missoes",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                intent());
    }

    private AgenticAuthoringPlanRequest localEditorialRequest() {
        return new AgenticAuthoringPlanRequest(
                "Crie uma página com tabs usando conteúdo local/editorial de demonstração.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                localEditorialIntent());
    }

    private AgenticAuthoringIntentResolutionResult intent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/operations/vw-resumo-missoes",
                        "post",
                        "/schemas/filtered?path=/api/operations/vw-resumo-missoes/filter&operation=post&schemaType=response",
                        "/api/operations/vw-resumo-missoes/filter",
                        "POST",
                        0.94d,
                        "user selected a dashboard resource candidate",
                        List.of("quick-reply")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult localEditorialIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "page",
                "create_tabbed_workspace",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                null,
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of("explicit-local-ui-composition-resource-selection-bypassed"),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult singleChartIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "chart",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "post",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter",
                        "POST",
                        0.94d,
                        "user selected chart resource candidate",
                        List.of("llm-visualization-decision")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult chartModificationIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "chart",
                "set_chart_type",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "indicadores-incidentes-chart",
                        "praxis-chart",
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter",
                        "POST"),
                new AgenticAuthoringCandidate(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "post",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter",
                        "POST",
                        0.94d,
                        "contextual chart action",
                        List.of("component-capability-catalog")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult chartArtifactModificationIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "chart",
                "generic-page-change",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "indicadores-incidentes-chart",
                        "praxis-chart",
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter",
                        "POST"),
                new AgenticAuthoringCandidate(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "post",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter",
                        "POST",
                        0.94d,
                        "contextual chart action",
                        List.of("component-capability-catalog")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult chartSurfaceOpenModificationIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "chart",
                "enable_chart_drilldown",
                "component-capability-catalog",
                "praxis-ui-angular",
                "praxis-chart",
                new AgenticAuthoringTarget(
                        "indicadores-incidentes-chart",
                        "praxis-chart",
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter",
                        "POST"),
                new AgenticAuthoringCandidate(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "post",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter",
                        "POST",
                        0.94d,
                        "contextual chart action",
                        List.of("component-capability-catalog")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private ObjectNode uiCompositionPlan() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("layoutPreset", "resource-dashboard");
        ObjectNode widget = plan.putArray("widgets").addObject();
        widget.put("key", "resumo-missoes-table");
        widget.put("componentId", "praxis-table");
        widget.put("title", "Resumo missoes");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", "/api/operations/vw-resumo-missoes");
        inputs.put("schemaPath", "/schemas/filtered?path=/api/operations/vw-resumo-missoes/all&operation=get&schemaType=response");
        return plan;
    }

    private ObjectNode uiCompositionPlanWithSemanticAxes() {
        ObjectNode plan = uiCompositionPlan();
        ObjectNode diagnostics = plan.putObject("diagnostics");
        ObjectNode severity = diagnostics.putArray("semanticAxes").addObject();
        severity.put("concept", "severity");
        severity.put("requestedField", "gravidade");
        severity.put("field", "severidade");
        severity.put("label", "Gravidade");
        severity.put("schemaLabel", "Severidade");
        severity.put("schemaVerified", true);
        severity.put("schemaProbeStatus", "verified");
        ObjectNode status = diagnostics.withArray("semanticAxes").addObject();
        status.put("concept", "status");
        status.put("field", "andamento");
        status.put("label", "Andamento");
        status.put("schemaVerified", false);
        status.put("schemaProbeStatus", "unsupported");
        return plan;
    }

    private ObjectNode singleChartPlan() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("layoutPreset", "single-chart-page");
        ObjectNode widget = plan.putArray("widgets").addObject();
        widget.put("key", "indicadores-incidentes-chart");
        widget.put("componentId", "praxis-chart");
        widget.put("title", "Indicadores incidentes por severidade");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", "/api/risk-intelligence/vw-indicadores-incidentes");
        ObjectNode config = inputs.putObject("config");
        config.put("type", "bar");
        config.put("field", "severidade");
        ObjectNode diagnostics = plan.putObject("diagnostics");
        ObjectNode severity = diagnostics.putArray("semanticAxes").addObject();
        severity.put("concept", "severity");
        severity.put("requestedField", "severidade");
        severity.put("field", "severidade");
        severity.put("label", "Severidade");
        severity.put("schemaLabel", "Severidade");
        severity.put("schemaVerified", true);
        severity.put("schemaProbeStatus", "verified");
        return plan;
    }

    private ObjectNode chartModificationPlan() {
        ObjectNode page = objectMapper.createObjectNode();
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("key", "indicadores-incidentes-chart");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("resourcePath", "/api/risk-intelligence/vw-indicadores-incidentes");
        ObjectNode config = inputs.putObject("config");
        config.put("type", "line");
        config.put("field", "severidade");
        config.putArray("series").addObject().put("type", "line");
        return page;
    }

    private ObjectNode chartSurfaceOpenModificationPlan() {
        ObjectNode page = chartModificationPlan();
        ObjectNode widget = (ObjectNode) page.path("widgets").path(0);
        ObjectNode interactions = widget.putObject("interactions");
        ObjectNode select = interactions.putObject("select");
        select.put("type", "queryContext");
        ObjectNode action = select.putObject("action");
        action.put("type", "surface.open");
        action.put("presentation", "modal");
        ObjectNode surface = page.putObject("surfaces").putObject("incident-details-modal");
        surface.put("presentation", "modal");
        ObjectNode detailWidget = surface.putArray("widgets").addObject();
        detailWidget.put("componentId", "praxis-table");
        detailWidget.put("title", "Detalhes");
        return page;
    }
}
