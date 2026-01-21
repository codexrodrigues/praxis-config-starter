package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiCapability;
import org.praxisplatform.config.dto.AiIntentClassification;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.springframework.test.util.ReflectionTestUtils;

class AiOrchestratorServiceComputedCreationIntentTest {

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
                null);
    }

    @Test
    void shouldBuildComputedColumnForAge() {
        AiIntentClassification intent = AiIntentClassification.builder()
                .intent("add_column_computed")
                .scope("config")
                .category("columns")
                .newField("idade")
                .baseFields(List.of("dataNascimento"))
                .computedFormat("years")
                .needsClarification(false)
                .build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("praxis-table")
                .userPrompt("Crie coluna calculada idade usando dataNascimento")
                .build();
        ObjectNode currentState = objectMapper.createObjectNode();
        ArrayNode columns = currentState.putArray("columns");
        columns.addObject().put("field", "dataNascimento");

        AiOrchestratorResponse response = invokeHandle(intent, request, currentState);

        assertThat(response.getType()).isEqualTo("patch");
        JsonNode patch = response.getPatch();
        JsonNode column = patch.path("columns").get(0);
        assertThat(column.path("field").asText()).isEqualTo("idade");
        assertThat(column.path("computed").path("expression").asText())
                .isEqualTo("floor(yearsSince(dataNascimento))");
        assertThat(column.path("computed").path("outputType").asText())
                .isEqualTo("number");
        assertThat(column.path("computed").path("format").asText())
                .isEqualTo("1.0-0");
        assertThat(column.path("computed").path("dependencies").get(0).asText())
                .isEqualTo("dataNascimento");
    }

    @Test
    void shouldBuildComputedColumnForTenureYearsMonths() {
        AiIntentClassification intent = AiIntentClassification.builder()
                .intent("add_column_computed")
                .scope("config")
                .category("columns")
                .newField("tempoEmpresa")
                .baseFields(List.of("dataAdmissao"))
                .computedFormat("years_months")
                .needsClarification(false)
                .build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("praxis-table")
                .userPrompt("Crie coluna calculada para mostrar o tempo de empresa usando dataAdmissao")
                .build();
        ObjectNode currentState = objectMapper.createObjectNode();
        ArrayNode columns = currentState.putArray("columns");
        columns.addObject().put("field", "dataAdmissao");

        AiOrchestratorResponse response = invokeHandle(intent, request, currentState);

        assertThat(response.getType()).isEqualTo("patch");
        JsonNode column = response.getPatch().path("columns").get(0);
        assertThat(column.path("field").asText()).isEqualTo("tempoEmpresa");
        assertThat(column.path("computed").path("expression").asText())
                .isEqualTo("toString(yearsSince(dataAdmissao)) + ' anos ' + "
                        + "toString(monthsSince(dataAdmissao) - yearsSince(dataAdmissao) * 12) + ' meses'");
        assertThat(column.path("computed").path("outputType").asText())
                .isEqualTo("string");
        assertThat(column.path("computed").path("dependencies").get(0).asText())
                .isEqualTo("dataAdmissao");
    }

    @Test
    void shouldRequestClarificationWhenBaseFieldMissing() {
        AiIntentClassification intent = AiIntentClassification.builder()
                .intent("add_column_computed")
                .scope("config")
                .category("columns")
                .newField("tempoEmpresa")
                .baseFields(List.of("dataX"))
                .computedFormat("years")
                .needsClarification(false)
                .build();
        ObjectNode dataProfile = objectMapper.createObjectNode();
        ObjectNode columnsProfile = dataProfile.putObject("columns");
        columnsProfile.putObject("dataAdmissao").put("inferredType", "date");
        columnsProfile.putObject("dataNascimento").put("inferredType", "date");
        columnsProfile.putObject("nome").put("inferredType", "string");

        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("praxis-table")
                .userPrompt("Crie coluna calculada tempoEmpresa usando dataX")
                .dataProfile(dataProfile)
                .build();
        ObjectNode currentState = objectMapper.createObjectNode();
        ArrayNode columns = currentState.putArray("columns");
        columns.addObject().put("field", "dataAdmissao");
        columns.addObject().put("field", "dataNascimento");
        columns.addObject().put("field", "nome");

        AiOrchestratorResponse response = invokeHandle(intent, request, currentState);

        assertThat(response.getType()).isEqualTo("clarification");
        assertThat(response.getOptions()).contains("dataAdmissao", "dataNascimento");
    }

    private AiOrchestratorResponse invokeHandle(
            AiIntentClassification intent,
            AiOrchestratorRequest request,
            JsonNode currentState) {
        List<AiCapability> caps = List.of(
                AiCapability.builder().path("columns").valueKind("array").build(),
                AiCapability.builder().path("columns[].field").valueKind("string").build(),
                AiCapability.builder().path("columns[].header").valueKind("string").build(),
                AiCapability.builder().path("columns[].visible").valueKind("boolean").build(),
                AiCapability.builder().path("columns[].type").valueKind("string").build(),
                AiCapability.builder().path("columns[].computed").valueKind("object").build(),
                AiCapability.builder().path("columns[].computed.expression").valueKind("string").build(),
                AiCapability.builder().path("columns[].computed.outputType").valueKind("string").build(),
                AiCapability.builder().path("columns[].computed.format").valueKind("string").build(),
                AiCapability.builder().path("columns[].computed.dependencies").valueKind("array").build()
        );
        Object response = ReflectionTestUtils.invokeMethod(
                service,
                "handleComputedCreationIntent",
                intent,
                request,
                currentState,
                new ArrayList<String>(),
                caps,
                List.<AiCapability>of(),
                null);
        return (AiOrchestratorResponse) response;
    }
}
