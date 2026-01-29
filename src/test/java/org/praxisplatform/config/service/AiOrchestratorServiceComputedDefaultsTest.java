package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiCapability;
import org.praxisplatform.config.dto.AiIntentClassification;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.springframework.test.util.ReflectionTestUtils;

class AiOrchestratorServiceComputedDefaultsTest {

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
    void shouldApplyDefaultsForComputedAgeColumn() {
        AiIntentClassification intent = AiIntentClassification.builder()
                .intent("update_column_rules")
                .category("columns")
                .needsClarification(true)
                .missingContext(List.of("dataType", "headerLabel", "renderer/format"))
                .build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("praxis-table")
                .userPrompt("Crie uma coluna calculada idade usando dataNascimento")
                .build();
        ObjectNode currentState = objectMapper.createObjectNode();
        ArrayNode columns = currentState.putArray("columns");
        columns.addObject().put("field", "dataNascimento");

        Object resolution = ReflectionTestUtils.invokeMethod(
                service,
                "resolveComputedDefaultsForClarification",
                intent,
                request,
                currentState,
                List.<AiCapability>of(),
                List.<AiCapability>of(),
                null);

        assertThat(resolution).isNotNull();
        assertThat(intent.getNeedsClarification()).isFalse();
        assertThat(intent.getMissingContext()).isNull();

        JsonNode patch = (JsonNode) ReflectionTestUtils.getField(resolution, "patch");
        JsonNode column = patch.path("columns").get(0);
        assertThat(column.path("field").asText()).isEqualTo("idade");
        assertThat(column.path("header").asText()).isEqualTo("Idade");
        assertThat(column.path("computed").path("expression").asText())
                .isEqualTo("floor(yearsSince(dataNascimento))");
        assertThat(column.path("computed").path("outputType").asText())
                .isEqualTo("number");
        assertThat(column.path("computed").path("format").asText())
                .isEqualTo("1.0-0");
    }

    @Test
    void shouldKeepClarificationWhenPresentationIsExplicit() {
        AiIntentClassification intent = AiIntentClassification.builder()
                .intent("update_column_rules")
                .category("columns")
                .needsClarification(true)
                .missingContext(List.of("renderer/format"))
                .build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("praxis-table")
                .userPrompt("Crie coluna idade como badge com sufixo ' anos'")
                .build();
        ObjectNode currentState = objectMapper.createObjectNode();
        ArrayNode columns = currentState.putArray("columns");
        columns.addObject().put("field", "dataNascimento");

        Object resolution = ReflectionTestUtils.invokeMethod(
                service,
                "resolveComputedDefaultsForClarification",
                intent,
                request,
                currentState,
                List.<AiCapability>of(),
                List.<AiCapability>of(),
                null);

        assertThat(resolution).isNull();
        assertThat(intent.getNeedsClarification()).isTrue();
    }

    @Test
    void shouldApplyDefaultsForTenureYears() {
        AiIntentClassification intent = AiIntentClassification.builder()
                .intent("update_column_rules")
                .category("columns")
                .needsClarification(true)
                .missingContext(List.of("dataType", "headerLabel", "renderer/format"))
                .build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("praxis-table")
                .userPrompt("Crie coluna calculada tempoEmpresa usando dataAdmissao")
                .build();
        ObjectNode currentState = objectMapper.createObjectNode();
        ArrayNode columns = currentState.putArray("columns");
        columns.addObject().put("field", "dataAdmissao");

        Object resolution = ReflectionTestUtils.invokeMethod(
                service,
                "resolveComputedDefaultsForClarification",
                intent,
                request,
                currentState,
                List.<AiCapability>of(),
                List.<AiCapability>of(),
                null);

        assertThat(resolution).isNotNull();
        JsonNode patch = (JsonNode) ReflectionTestUtils.getField(resolution, "patch");
        JsonNode column = patch.path("columns").get(0);
        assertThat(column.path("field").asText()).isEqualTo("tempoEmpresa");
        assertThat(column.path("header").asText()).isEqualTo("Tempo de Empresa");
        assertThat(column.path("computed").path("expression").asText())
                .isEqualTo("floor(yearsSince(dataAdmissao))");
        assertThat(column.path("computed").path("outputType").asText())
                .isEqualTo("number");
        assertThat(column.path("computed").path("format").asText())
                .isEqualTo("1.0-0");
    }

    @Test
    void shouldApplyDefaultsForTenureYearsMonths() {
        AiIntentClassification intent = AiIntentClassification.builder()
                .intent("update_column_rules")
                .category("columns")
                .needsClarification(true)
                .missingContext(List.of("dataType", "headerLabel", "renderer/format"))
                .build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("praxis-table")
                .userPrompt("Crie coluna calculada tempoEmpresa usando dataAdmissao (years_months)")
                .build();
        ObjectNode currentState = objectMapper.createObjectNode();
        ArrayNode columns = currentState.putArray("columns");
        columns.addObject().put("field", "dataAdmissao");

        Object resolution = ReflectionTestUtils.invokeMethod(
                service,
                "resolveComputedDefaultsForClarification",
                intent,
                request,
                currentState,
                List.<AiCapability>of(),
                List.<AiCapability>of(),
                null);

        assertThat(resolution).isNotNull();
        JsonNode patch = (JsonNode) ReflectionTestUtils.getField(resolution, "patch");
        JsonNode column = patch.path("columns").get(0);
        assertThat(column.path("field").asText()).isEqualTo("tempoEmpresa");
        assertThat(column.path("header").asText()).isEqualTo("Tempo de Empresa");
        assertThat(column.path("computed").path("expression").asText())
                .isEqualTo("toString(yearsSince(dataAdmissao)) + ' anos ' + "
                        + "toString(monthsSince(dataAdmissao) - yearsSince(dataAdmissao) * 12) + ' meses'");
        assertThat(column.path("computed").path("outputType").asText())
                .isEqualTo("string");
    }
}
