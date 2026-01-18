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
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.springframework.test.util.ReflectionTestUtils;

class AiOrchestratorServiceSuggestedPatchTest {

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
    void shouldSanitizeSuggestedPatchFields() {
        ObjectNode currentState = buildCurrentState("status");
        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode columns = patch.putArray("columns");
        ObjectNode column = columns.addObject();
        column.put("field", "status");
        column.put("unknown", "x");
        column.putObject("renderer").putObject("badge").put("variant", "filled");

        List<AiCapability> caps = List.of(
                AiCapability.builder()
                        .path("columns[].renderer.badge.variant")
                        .build());

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "applySuggestedPatch",
                patch,
                currentState,
                "praxis-table",
                new ArrayList<>(),
                caps,
                List.of(),
                null);

        assertThat(response.getType()).isEqualTo("patch");
        JsonNode responsePatch = response.getPatch();
        JsonNode responseColumn = responsePatch.path("columns").get(0);
        assertThat(responseColumn.has("unknown")).isFalse();
        assertThat(responseColumn.path("renderer").path("badge").path("variant").asText())
                .isEqualTo("filled");
    }

    @Test
    void shouldRejectInvalidEnumValuesInSuggestedPatch() {
        ObjectNode currentState = buildCurrentState("status");
        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode columns = patch.putArray("columns");
        ObjectNode column = columns.addObject();
        column.put("field", "status");
        column.putObject("renderer").putObject("badge").put("variant", "invalid");

        List<AiCapability> caps = List.of(
                AiCapability.builder()
                        .path("columns[].renderer.badge.variant")
                        .allowedValues(List.of("filled", "outlined"))
                        .build());

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "applySuggestedPatch",
                patch,
                currentState,
                "praxis-table",
                new ArrayList<>(),
                caps,
                List.of(),
                null);

        assertThat(response.getType()).isEqualTo("error");
        assertThat(response.getCode()).isEqualTo("INVALID_ENUM_VALUE");
    }

    @Test
    void shouldRejectSuggestedPatchWhenCapabilitiesMissing() {
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("title", "Novo titulo");

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "applySuggestedPatch",
                patch,
                objectMapper.createObjectNode(),
                "praxis-card",
                new ArrayList<>(),
                List.of(),
                List.of(),
                null);

        assertThat(response.getType()).isEqualTo("error");
        assertThat(response.getWarnings())
                .isNotNull()
                .anySatisfy(w -> assertThat(w).contains("Capabilities ausentes"));
    }

    @Test
    void shouldDropArraysWhenPathDoesNotAllowArray() {
        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode filters = patch.putArray("filters");
        filters.addObject().put("field", "status").put("value", "active");

        List<AiCapability> caps = List.of(
                AiCapability.builder()
                        .path("filters")
                        .build());

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "applySuggestedPatch",
                patch,
                objectMapper.createObjectNode(),
                "praxis-card",
                new ArrayList<>(),
                caps,
                List.of(),
                null);

        assertThat(response.getType()).isEqualTo("error");
        assertThat(response.getMessage()).contains("patch sugerido");
    }

    private ObjectNode buildCurrentState(String field) {
        ObjectNode currentState = objectMapper.createObjectNode();
        ArrayNode columns = currentState.putArray("columns");
        columns.addObject().put("field", field);
        return currentState;
    }
}
