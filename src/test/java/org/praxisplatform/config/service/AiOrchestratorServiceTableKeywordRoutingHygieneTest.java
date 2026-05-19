package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
}
