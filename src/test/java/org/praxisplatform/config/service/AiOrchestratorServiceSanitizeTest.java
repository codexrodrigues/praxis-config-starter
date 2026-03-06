package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiCapability;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
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
                null,
                mock(AiThreadService.class),
                mock(AiMessageService.class));
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

        Object sanitizeResult = ReflectionTestUtils.invokeMethod(
                service,
                "sanitizePatch",
                patch,
                caps,
                "praxis-table");
        JsonNode sanitized = (JsonNode) ReflectionTestUtils.getField(sanitizeResult, "sanitized");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) ReflectionTestUtils.getField(sanitizeResult, "warnings");

        assertThat(sanitized.at("/columns/0/computed/dependencies/0").asText())
                .isEqualTo("dataNascimento");
        assertThat(warnings)
                .doesNotContain("Array ignorado: columns[].computed.dependencies");
    }

    @Test
    void shouldApplyTableBaselineCapabilitiesWhenCatalogIsMissing() throws Exception {
        JsonNode patch = objectMapper.readTree("""
                {
                  "appearance": {
                    "density": "compact"
                  }
                }
                """);

        Object sanitizeResult = ReflectionTestUtils.invokeMethod(
                service,
                "sanitizePatch",
                patch,
                List.<AiCapability>of(),
                "praxis-table");
        JsonNode sanitized = (JsonNode) ReflectionTestUtils.getField(sanitizeResult, "sanitized");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) ReflectionTestUtils.getField(sanitizeResult, "warnings");

        assertThat(sanitized.path("appearance").path("density").asText()).isEqualTo("compact");
        assertThat(warnings).anyMatch(warning -> warning.contains("baseline segura"));
    }

    @Test
    void shouldNotDuplicatePrimitiveArraysWhenMergingPatches() throws Exception {
        JsonNode base = objectMapper.readTree("""
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
        JsonNode extra = objectMapper.readTree("""
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

        JsonNode merged = ReflectionTestUtils.invokeMethod(service, "mergePatchNodes", base, extra);

        List<String> deps = new java.util.ArrayList<>();
        for (JsonNode item : merged.at("/columns/0/computed/dependencies")) {
            deps.add(item.asText());
        }
        assertThat(deps).containsExactly("dataNascimento");
    }

    @Test
    void shouldDeduplicateMixedPrimitiveArraysWhenMergingPatches() throws Exception {
        JsonNode base = objectMapper.readTree("""
                {
                  "columns": [
                    {
                      "field": "idade",
                      "computed": {
                        "dependencies": ["dataNascimento", "dataAdmissao", "dataNascimento", null]
                      }
                    }
                  ]
                }
                """);
        JsonNode extra = objectMapper.readTree("""
                {
                  "columns": [
                    {
                      "field": "idade",
                      "computed": {
                        "dependencies": [null, "dataNascimento", "dataDemissao"]
                      }
                    }
                  ]
                }
                """);

        JsonNode merged = ReflectionTestUtils.invokeMethod(service, "mergePatchNodes", base, extra);

        List<String> deps = new java.util.ArrayList<>();
        for (JsonNode item : merged.at("/columns/0/computed/dependencies")) {
            if (item == null || item.isNull()) {
                deps.add("null");
            } else {
                deps.add(item.asText());
            }
        }
        assertThat(deps).containsExactly("dataNascimento", "dataAdmissao", "null", "dataDemissao");
    }
}
