package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiContextDTO;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class AiOrchestratorServiceSchemaResolutionTest {

    private AiOrchestratorService service;
    private SchemaRetrievalService schemaRetrievalService;

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
                new ObjectMapper(),
                mock(AiApiKeyCryptoService.class),
                mock(AiThreadService.class),
                mock(AiMessageService.class));
    }

    @Test
    void resolveSchemaReturnsTypedNotFoundErrorWhenSchemaIsMissing() {
        when(schemaRetrievalService.fetchSchemaResult(any(), anyString()))
                .thenReturn(SchemaFetchResult.failure(
                        SchemaFetchResult.Status.NOT_FOUND,
                        404,
                        "http://localhost:8080/schemas/filtered?path=/api/users&operation=get&schemaType=response",
                        "SCHEMA_NOT_FOUND",
                        "missing schema"));

        AiOrchestratorResponse response = resolveSchemaResponse();

        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo("error");
        assertThat(response.getCode()).isEqualTo("SCHEMA_NOT_FOUND");
        assertThat(response.getMessage()).contains("Schema estrutural nao encontrado");
        assertThat(response.getExplanation()).contains("status=404");
    }

    @Test
    void resolveSchemaReturnsPlatformUnavailableErrorWhenMetadataPlatformFails() {
        when(schemaRetrievalService.fetchSchemaResult(any(), anyString()))
                .thenReturn(SchemaFetchResult.failure(
                        SchemaFetchResult.Status.UNAVAILABLE,
                        503,
                        "http://localhost:8080/schemas/filtered?path=/api/users&operation=get&schemaType=response",
                        "SCHEMA_PLATFORM_UNAVAILABLE",
                        "upstream unavailable"));

        AiOrchestratorResponse response = resolveSchemaResponse();

        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo("error");
        assertThat(response.getCode()).isEqualTo("SCHEMA_PLATFORM_UNAVAILABLE");
        assertThat(response.getMessage()).contains("plataforma de metadata");
        assertThat(response.getExplanation()).contains("retryable=true");
    }

    private AiOrchestratorResponse resolveSchemaResponse() {
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("praxis-table")
                .schemaContext(AiSchemaContext.builder()
                        .path("/api/users")
                        .operation("get")
                        .schemaType("response")
                        .build())
                .build();
        AiContextDTO context = AiContextDTO.builder().build();
        Object resolution = ReflectionTestUtils.invokeMethod(
                service,
                "resolveSchema",
                request,
                context,
                "http://localhost:8080",
                null,
                null,
                null);
        return (AiOrchestratorResponse) ReflectionTestUtils.getField(resolution, "response");
    }
}
