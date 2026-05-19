package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.praxisplatform.config.dto.AiActionPlan;
import org.praxisplatform.config.dto.AiIntentClassification;
import org.praxisplatform.config.dto.AiOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class AiOrchestratorServiceTableKeywordRoutingHygieneTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void tableGeneratePatchFlowMustNotRouteThroughLegacyKeywordFallbacks() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java"));
        String generatePatchBody = source.substring(
                source.indexOf("public AiOrchestratorResponse generatePatch"),
                source.indexOf("private AiActionPlan extractTableActionPlan"));

        assertThat(generatePatchBody)
                .doesNotContain("tryResolveTableDeterministicDirectFallback(")
                .doesNotContain("deriveFallbackTableManifestActionPlan(")
                .doesNotContain("deriveFallbackTableActions(")
                .doesNotContain("tryResolveFilteringPrompt(")
                .doesNotContain("enforceFormatIntentWhenFieldExists(")
                .doesNotContain("handleComputedCreationIntent(")
                .doesNotContain("tryResolveComputedFastPath(")
                .doesNotContain("keyword-fallback-table-actions-used");
        assertThat(generatePatchBody).contains("extractTableActionPlan(");
    }

    @Test
    void tableActionPlanSchemaUsesAuthoringManifestOperationsAsToolEnum() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode manifest = objectMapper.readTree("""
                {
                  "operations": [
                    { "operationId": "column.format.set" },
                    { "operationId": "filter.advanced.configure" }
                  ]
                }
                """);

        AiJsonSchema schema = ReflectionTestUtils.invokeMethod(
                service,
                "buildTableActionPlanSchema",
                java.util.List.of(),
                manifest);

        JsonNode schemaJson = objectMapper.readTree(schema.jsonSchema());
        JsonNode operationEnum = schemaJson
                .path("properties")
                .path("actions")
                .path("items")
                .path("properties")
                .path("type")
                .path("enum");

        List<String> operationIds = new ArrayList<>();
        operationEnum.forEach(node -> operationIds.add(node.asText()));
        assertThat(operationIds).containsExactly("column.format.set", "filter.advanced.configure");
        assertThat(schemaJson
                .path("properties")
                .path("actions")
                .path("items")
                .path("properties")
                .path("params")
                .path("type")
                .asText()).isEqualTo("object");
    }

    @Test
    void tableOperationCatalogCarriesManifestExamplesForSemanticToolSelection() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode manifest = objectMapper.readTree("""
                {
                  "operations": [
                    {
                      "operationId": "column.format.set",
                      "title": "Definir formato",
                      "scope": "column",
                      "target": { "kind": "column", "resolver": "column-by-field", "required": true },
                      "inputSchema": { "type": "object", "properties": { "format": { "type": "string" } } },
                      "affectedPaths": ["columns[].format"],
                      "validators": ["format-preset-supported"]
                    }
                  ],
                  "examples": [
                    {
                      "id": "format-cpf",
                      "request": "Formate a coluna CPF",
                      "operationId": "column.format.set",
                      "target": "cpf",
                      "params": { "format": "000.000.000-00" },
                      "isPositive": true
                    }
                  ]
                }
                """);

        JsonNode catalog = ReflectionTestUtils.invokeMethod(
                service,
                "buildManifestOperationCatalogNode",
                manifest);

        JsonNode firstOperation = catalog.path(0);
        assertThat(firstOperation.path("operationId").asText()).isEqualTo("column.format.set");
        assertThat(firstOperation.path("examples").path(0).path("request").asText())
                .isEqualTo("Formate a coluna CPF");
        assertThat(firstOperation.path("examples").path(0).path("params").path("format").asText())
                .isEqualTo("000.000.000-00");
    }

    @Test
    void tableFormatOptionsFromLlmIntentBecomeGuidedActionPayloads() {
        AiOrchestratorService service = newService();
        AiIntentClassification intent = AiIntentClassification.builder()
                .category("format")
                .targetField("salario")
                .options(List.of("Moeda BRL", "Numero compacto"))
                .build();

        Boolean shouldOfferChoice = ReflectionTestUtils.invokeMethod(
                service,
                "shouldOfferFormatChoiceFromLlmIntent",
                true,
                intent,
                null);
        assertThat(shouldOfferChoice).isTrue();

        List<?> contextOptions = List.of(
                newContextOption("BRL|symbol|2", "Moeda BRL", "R$ 12.700,00"),
                newContextOption("compact", "Compacto", "12.7k"));
        @SuppressWarnings("unchecked")
        List<AiOption> payloads = (List<AiOption>) ReflectionTestUtils.invokeMethod(
                service,
                "buildFormatOptionPayloads",
                "salario",
                contextOptions);

        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0).getLabel()).isEqualTo("Moeda BRL");
        assertThat(payloads.get(0).getContextHints().path("optionSelected").path("targetField").asText())
                .isEqualTo("salario");
        assertThat(payloads.get(0).getContextHints().path("optionSelected").path("selection").path("value").asText())
                .isEqualTo("BRL|symbol|2");
        assertThat(payloads.get(0).getContextHints().path("presentation").path("ctaLabel").asText())
                .isEqualTo("Aplicar formato");
    }

    @Test
    void singleChosenFormatOptionFromLlmIntentIsTreatedAsSelectedAction() {
        AiOrchestratorService service = newService();
        AiIntentClassification intent = AiIntentClassification.builder()
                .category("format")
                .targetField("salario")
                .options(List.of("BRL|symbol|2"))
                .build();
        List<?> contextOptions = List.of(
                newContextOption("BRL|symbol|2", "Currency BRL symbol", "R$ 12.700,00"),
                newContextOption("USD|symbol|2", "Currency USD symbol", "US$ 12,700.00"));

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedFormatFromLlmIntentOptions",
                intent,
                contextOptions);

        assertThat(selection).isNotNull();
        assertThat(ReflectionTestUtils.getField(selection, "targetField")).isEqualTo("salario");
        assertThat(ReflectionTestUtils.getField(selection, "value")).isEqualTo("BRL|symbol|2");
        assertThat(ReflectionTestUtils.getField(selection, "mode")).isEqualTo("format");
    }

    @Test
    void tableRendererOptionsFromLlmIntentBecomeGuidedActionPayloads() {
        AiOrchestratorService service = newService();
        AiIntentClassification intent = AiIntentClassification.builder()
                .category("renderer")
                .targetField("ativo")
                .options(List.of(
                        "Mostrar badge colorido (verde = ativo, cinza/vermelho = inativo)",
                        "Mostrar ícone (check / cruz) com label acessível"))
                .build();

        Boolean shouldOfferChoice = ReflectionTestUtils.invokeMethod(
                service,
                "shouldOfferRendererChoiceFromLlmIntent",
                true,
                intent,
                null);
        assertThat(shouldOfferChoice).isTrue();

        @SuppressWarnings("unchecked")
        List<AiOption> payloads = (List<AiOption>) ReflectionTestUtils.invokeMethod(
                service,
                "buildRendererOptionPayloads",
                "ativo",
                intent.getOptions());

        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0).getLabel()).contains("badge colorido");
        assertThat(payloads.get(0).getContextHints().path("optionSelected").path("targetField").asText())
                .isEqualTo("ativo");
        assertThat(payloads.get(0).getContextHints().path("optionSelected").path("selection").path("mode").asText())
                .isEqualTo("renderer");
        assertThat(payloads.get(0).getContextHints().path("presentation").path("ctaLabel").asText())
                .isEqualTo("Aplicar opção");
    }

    @Test
    void selectedRendererGuidedActionBecomesManifestActionPlan() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode hints = objectMapper.readTree("""
                {
                  "optionSelected": {
                    "targetField": "ativo",
                    "selection": {
                      "value": "Badge colorido (verde para ativo, vermelho/cinza para inativo)",
                      "mode": "renderer"
                    }
                  }
                }
                """);

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromHints",
                hints);
        assertThat(selection).isNotNull();

        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                AiIntentClassification.builder().category("renderer").targetField("ativo").build(),
                objectMapper.readTree("{\"columns\":[{\"field\":\"ativo\",\"header\":\"Ativo\",\"type\":\"boolean\"}]}"),
                List.of(newColumnDescriptor("ativo", "Ativo")),
                List.of("field"));

        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(2);
        assertThat(plan.getActions().get(0).getType()).isEqualTo("column.conditionalRenderer.add");
        assertThat(plan.getActions().get(0).getTarget()).isEqualTo("ativo");
        assertThat(plan.getActions().get(0).getParams().path("renderer").path("type").asText())
                .isEqualTo("badge");
    }

    private AiOrchestratorService newService() {
        return new AiOrchestratorService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                objectMapper,
                null,
                mock(AiThreadService.class),
                mock(AiMessageService.class));
    }

    private Object newColumnDescriptor(String field, String header) throws Exception {
        Class<?> type = Class.forName("org.praxisplatform.config.service.AiOrchestratorService$ColumnDescriptor");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(field, header);
    }

    private Object newContextOption(String value, String label, String example) {
        try {
            Class<?> type = Class.forName("org.praxisplatform.config.service.AiOrchestratorService$ContextOption");
            var constructor = type.getDeclaredConstructor(String.class, String.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(value, label, example);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
