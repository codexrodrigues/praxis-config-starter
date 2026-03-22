package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class AiOrchestratorServiceContextHintsTest {

    private AiOrchestratorService service;
    private SchemaRetrievalService schemaRetrievalService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        schemaRetrievalService = mock(SchemaRetrievalService.class);
        service = new AiOrchestratorService(
                mock(AiContextService.class),
                mock(AiProvider.class),
                mock(AiInteractionLogger.class),
                mock(ContextRetrievalService.class),
                schemaRetrievalService,
                mock(AiRegistryTemplateService.class),
                mock(AiRagContextService.class),
                mock(UserConfigService.class),
                objectMapper,
                mock(AiApiKeyCryptoService.class),
                mock(AiThreadService.class),
                mock(AiMessageService.class));
    }

    @Test
    void buildContextHintsResolvesSchemaFieldsAndSampleViaTypedJitFetch() throws Exception {
        when(schemaRetrievalService.fetchSchemaResult(any(), nullable(String.class)))
                .thenReturn(SchemaFetchResult.success(objectMapper.readTree("""
                        {
                          "properties": {
                            "name": { "type": "string" },
                            "active": { "type": "boolean" }
                          }
                        }
                        """), "http://localhost/schemas/filtered?path=/api/users"));

        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .resourcePath("/api/users")
                .build();

        Object result = ReflectionTestUtils.invokeMethod(
                service,
                "buildContextHintsFromRequest",
                null,
                request,
                null,
                null,
                List.of(30, 40),
                null,
                null,
                null);
        JsonNode hints = (JsonNode) ReflectionTestUtils.getField(result, "hints");

        assertThat(hints).isNotNull();
        JsonNode contextPack = hints.path("contextPack");
        assertThat(contextPack.path("schemaFields").isArray()).isTrue();
        assertThat(contextPack.path("schemaFields").get(0).asText()).isEqualTo("name");
        assertThat(contextPack.path("schemaFields").get(1).asText()).isEqualTo("active");
        assertThat(contextPack.path("schemaSample").path("name").asText()).isEqualTo("<text>");
        assertThat(contextPack.path("schemaSample").path("active").asBoolean()).isFalse();
        verify(schemaRetrievalService, times(2)).fetchSchemaResult(any(), nullable(String.class));
        verify(schemaRetrievalService, never()).fetchSchema(any(), nullable(String.class));
    }

    @Test
    void buildContextHintsSkipsSchemaPackWhenTypedJitFetchFails() {
        when(schemaRetrievalService.fetchSchemaResult(any(), nullable(String.class)))
                .thenReturn(SchemaFetchResult.failure(
                        SchemaFetchResult.Status.UNAVAILABLE,
                        503,
                        "http://localhost/schemas/filtered?path=/api/users",
                        "SCHEMA_PLATFORM_UNAVAILABLE",
                        "upstream unavailable"));

        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .resourcePath("/api/users")
                .build();
        List<String> warnings = new java.util.ArrayList<>();

        Object result = ReflectionTestUtils.invokeMethod(
                service,
                "buildContextHintsFromRequest",
                null,
                request,
                null,
                null,
                List.of(30, 40),
                null,
                null,
                warnings);
        JsonNode hints = (JsonNode) ReflectionTestUtils.getField(result, "hints");

        assertThat(hints).isNull();
        assertThat(warnings).containsExactly("SCHEMA_CONTEXT_DEGRADED: SCHEMA_PLATFORM_UNAVAILABLE");
        verify(schemaRetrievalService, times(2)).fetchSchemaResult(any(), nullable(String.class));
        verify(schemaRetrievalService, never()).fetchSchema(any(), nullable(String.class));
    }
}
