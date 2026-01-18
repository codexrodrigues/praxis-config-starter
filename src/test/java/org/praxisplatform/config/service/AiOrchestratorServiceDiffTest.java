package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiPatchDiff;
import org.springframework.test.util.ReflectionTestUtils;

class AiOrchestratorServiceDiffTest {

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
    void shouldBuildDiffForScalarChange() {
        ObjectNode currentState = objectMapper.createObjectNode();
        currentState.put("title", "Old");

        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("title", "New");

        List<AiPatchDiff> diffs = ReflectionTestUtils.invokeMethod(
                service,
                "buildPatchDiff",
                currentState,
                patch);

        assertThat(diffs).hasSize(1);
        AiPatchDiff diff = diffs.get(0);
        assertThat(diff.getPath()).isEqualTo("title");
        assertThat(diff.getBefore().asText()).isEqualTo("Old");
        assertThat(diff.getAfter().asText()).isEqualTo("New");
    }

    @Test
    void shouldBuildDiffForNestedChange() {
        ObjectNode currentState = objectMapper.createObjectNode();
        ObjectNode config = currentState.putObject("config");
        config.put("a", 1);
        config.put("b", 2);

        ObjectNode patch = objectMapper.createObjectNode();
        patch.putObject("config").put("b", 3);

        List<AiPatchDiff> diffs = ReflectionTestUtils.invokeMethod(
                service,
                "buildPatchDiff",
                currentState,
                patch);

        assertThat(diffs).hasSize(1);
        AiPatchDiff diff = diffs.get(0);
        assertThat(diff.getPath()).isEqualTo("config.b");
        assertThat(diff.getBefore().asInt()).isEqualTo(2);
        assertThat(diff.getAfter().asInt()).isEqualTo(3);
    }

    @Test
    void shouldBuildDiffForArrayIdentityChange() {
        ObjectNode currentState = objectMapper.createObjectNode();
        ArrayNode columns = currentState.putArray("columns");
        columns.addObject().put("field", "status").put("width", 120);
        columns.addObject().put("field", "name").put("width", 200);

        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode patchColumns = patch.putArray("columns");
        patchColumns.addObject().put("field", "status").put("width", 180);

        List<AiPatchDiff> diffs = ReflectionTestUtils.invokeMethod(
                service,
                "buildPatchDiff",
                currentState,
                patch);

        assertThat(diffs).hasSize(1);
        AiPatchDiff diff = diffs.get(0);
        assertThat(diff.getPath()).isEqualTo("columns[field=status].width");
        assertThat(diff.getBefore().asInt()).isEqualTo(120);
        assertThat(diff.getAfter().asInt()).isEqualTo(180);
    }
}
