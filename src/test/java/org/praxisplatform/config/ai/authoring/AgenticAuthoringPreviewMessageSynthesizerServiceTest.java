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
        assertThat(promptCaptor.getValue()).contains("\"resourcePath\" : \"/api/operations/vw-resumo-missoes\"");
        assertThat(promptCaptor.getValue()).contains("\"previewValid\" : true");
        assertThat(promptCaptor.getValue()).contains("\"componentId\" : \"praxis-table\"");
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
}
