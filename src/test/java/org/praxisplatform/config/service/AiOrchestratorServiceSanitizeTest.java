package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiCapability;
import org.springframework.test.util.ReflectionTestUtils;

class AiOrchestratorServiceSanitizeTest {

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
    void shouldPreserveComputedDependenciesArray() throws Exception {
        JsonNode patch = objectMapper.readTree("""
                {
                  "columns": [
                    {
                      "field": "idade",
                      "computed": {
                        "dependencies": ["dataNascimento"]
                      }
                    }
                  ]
                }
                """);

        List<AiCapability> caps = List.of(
                AiCapability.builder().path("columns[].field").valueKind("string").build(),
                AiCapability.builder().path("columns[].computed").valueKind("object").build(),
                AiCapability.builder().path("columns[].computed.dependencies").valueKind("array").build()
        );

        Object sanitizeResult = ReflectionTestUtils.invokeMethod(service, "sanitizePatch", patch, caps);
        JsonNode sanitized = (JsonNode) ReflectionTestUtils.getField(sanitizeResult, "sanitized");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) ReflectionTestUtils.getField(sanitizeResult, "warnings");

        assertThat(sanitized.at("/columns/0/computed/dependencies/0").asText())
                .isEqualTo("dataNascimento");
        assertThat(warnings)
                .doesNotContain("Array ignorado: columns[].computed.dependencies");
    }
}
