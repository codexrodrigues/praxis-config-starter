package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiCapability;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.springframework.test.util.ReflectionTestUtils;

class AiOrchestratorServiceDeterministicFallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiOrchestratorService service;

    @BeforeEach
    void setUp() {
        service = new AiOrchestratorService(
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

    @Test
    void shouldApplyDeterministicDensityFallback() throws Exception {
        AiOrchestratorRequest request = baseRequest("Troque a densidade da tabela para compacta.");
        List<String> warnings = new ArrayList<>();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "tryResolveTableDeterministicDirectFallback",
                request,
                baseState(),
                warnings,
                tableCapabilities(),
                List.<AiCapability>of(),
                null);

        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo("patch");
        assertThat(response.getPatch().path("appearance").path("density").asText()).isEqualTo("compact");
        assertThat(warnings).anyMatch(w -> w.contains("densidade"));
    }

    @Test
    void shouldApplyDeterministicRowSelectionFallback() throws Exception {
        AiOrchestratorRequest request = baseRequest("Ative seleção de linhas com modo múltiplo.");
        List<String> warnings = new ArrayList<>();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "tryResolveTableDeterministicDirectFallback",
                request,
                baseState(),
                warnings,
                tableCapabilities(),
                List.<AiCapability>of(),
                null);

        assertThat(response).isNotNull();
        assertThat(response.getPatch().path("behavior").path("selection").path("enabled").asBoolean()).isTrue();
        assertThat(response.getPatch().path("behavior").path("selection").path("type").asText()).isEqualTo("multiple");
    }

    @Test
    void shouldApplyDeterministicAlignmentFallback() throws Exception {
        AiOrchestratorRequest request = baseRequest("Alinhe a coluna status à direita e a coluna createdAt ao centro.");
        List<String> warnings = new ArrayList<>();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "tryResolveTableDeterministicDirectFallback",
                request,
                baseState(),
                warnings,
                tableCapabilities(),
                List.<AiCapability>of(),
                null);

        assertThat(response).isNotNull();
        JsonNode columns = response.getPatch().path("columns");
        assertThat(columns.isArray()).isTrue();
        JsonNode statusColumn = findColumn(columns, "status");
        JsonNode createdAtColumn = findColumn(columns, "createdAt");
        assertThat(statusColumn).isNotNull();
        assertThat(createdAtColumn).isNotNull();
        assertThat(statusColumn.path("align").asText()).isEqualTo("right");
        assertThat(createdAtColumn.path("align").asText()).isEqualTo("center");
    }

    @Test
    void shouldApplyDeterministicStatusHighlightFallback() throws Exception {
        AiOrchestratorRequest request = baseRequest(
                "Com base no status ATIVO/INATIVO/PENDENTE, destaque visualmente PENDENTE e normalize ordenação por prioridade.");
        request.setDataProfile(baseDataProfile());
        List<String> warnings = new ArrayList<>();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "tryResolveTableDeterministicDirectFallback",
                request,
                baseState(),
                warnings,
                tableCapabilities(),
                List.<AiCapability>of(),
                null);

        assertThat(response).isNotNull();
        JsonNode statusColumn = findColumn(response.getPatch().path("columns"), "status");
        assertThat(statusColumn).isNotNull();
        assertThat(statusColumn.path("conditionalRenderers").isArray()).isTrue();
        assertThat(response.getPatch().toString()).contains("PENDENTE");
    }

    @Test
    void shouldApplyRelevanceFallbackWhenDensityPromptReturnsIrrelevantPatch() throws Exception {
        AiOrchestratorRequest request = baseRequest("Troque a densidade da tabela para compacta.");
        List<String> warnings = new ArrayList<>();
        JsonNode irrelevantPatch = objectMapper.readTree("""
                {
                  "columns": [
                    {
                      "field": "idade",
                      "computed": {
                        "expression": "floor(yearsSince(id))",
                        "outputType": "number",
                        "format": "1.0-0"
                      }
                    }
                  ]
                }
                """);

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "tryResolveDeterministicPatchRelevanceFallback",
                request,
                irrelevantPatch,
                baseState(),
                warnings,
                tableCapabilities(),
                List.<AiCapability>of(),
                null);

        assertThat(response).isNotNull();
        assertThat(response.getPatch().path("appearance").path("density").asText()).isEqualTo("compact");
        assertThat(warnings).anyMatch(w -> w.contains("nao aderiu ao pedido de densidade"));
    }

    private AiOrchestratorRequest baseRequest(String prompt) {
        return AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt(prompt)
                .schemaFields(baseSchemaFields())
                .build();
    }

    private JsonNode baseState() throws Exception {
        return objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status", "header": "Status" },
                    { "field": "createdAt", "header": "Data de criacao" },
                    { "field": "classe", "header": "Classe" }
                  ],
                  "behavior": {}
                }
                """);
    }

    private JsonNode baseDataProfile() throws Exception {
        return objectMapper.readTree("""
                {
                  "columns": {
                    "status": {
                      "inferredType": "string",
                      "topValues": ["ATIVO", "INATIVO", "PENDENTE"]
                    }
                  }
                }
                """);
    }

    private JsonNode baseSchemaFields() {
        return objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("name", "status")
                        .put("type", "string"))
                .add(objectMapper.createObjectNode()
                        .put("name", "createdAt")
                        .put("type", "datetime"))
                .add(objectMapper.createObjectNode()
                        .put("name", "classe")
                        .put("type", "string"));
    }

    private List<AiCapability> tableCapabilities() {
        return List.of(
                AiCapability.builder().path("appearance").valueKind("object").build(),
                AiCapability.builder().path("behavior").valueKind("object").build(),
                AiCapability.builder().path("columns[]").valueKind("object").build(),
                AiCapability.builder().path("columns[].conditionalRenderers[]").valueKind("object").build());
    }

    private JsonNode findColumn(JsonNode columns, String field) {
        if (columns == null || !columns.isArray()) {
            return null;
        }
        for (JsonNode column : columns) {
            if (column != null
                    && column.isObject()
                    && field.equalsIgnoreCase(column.path("field").asText())) {
                return column;
            }
        }
        return null;
    }
}
