package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.AiProviderCatalogResponse;
import org.praxisplatform.config.dto.AiProviderModel;
import org.praxisplatform.config.dto.AiProviderModelsRequest;
import org.praxisplatform.config.dto.AiProviderModelsResponse;
import org.praxisplatform.config.dto.AiProviderTestRequest;
import org.praxisplatform.config.dto.AiProviderTestResponse;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AiProviderManagementServiceTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private UserConfigService userConfigService;

    @Mock
    private AiApiKeyCryptoService apiKeyCryptoService;

    @Mock
    private AiProvider gemini;

    @Mock
    private AiProvider openai;

    @Mock
    private AiProvider mockProvider;

    private AiProviderManagementService service;

    @BeforeEach
    void setUp() {
        when(gemini.getProviderName()).thenReturn("gemini");
        when(openai.getProviderName()).thenReturn("openai");
        service = new AiProviderManagementService(
                objectMapper,
                userConfigService,
                apiKeyCryptoService,
                List.of(gemini, openai));
        ReflectionTestUtils.setField(service, "defaultProvider", "gemini");
        ReflectionTestUtils.setField(service, "openaiModel", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "geminiModel", "gemini-2.0-flash");
        ReflectionTestUtils.setField(service, "xaiModel", "grok-2-latest");
        service.initProviderRegistry();
    }

    @Test
    void listModelsUsesRegistryAlias() {
        AiProviderModel model = AiProviderModel.builder().name("gpt-4o-mini").build();
        when(openai.listModels(any())).thenReturn(List.of(model));

        AiProviderModelsResponse response = service.listModels(
                AiProviderModelsRequest.builder().provider("open-ai").apiKey("key").build());

        assertTrue(response.isSuccess());
        assertEquals("open-ai", response.getProvider());
        assertEquals(1, response.getModels().size());
        assertEquals("gpt-4o-mini", response.getModels().get(0).getName());
        verify(openai).listModels(any());
    }

    @Test
    void testConnectionUsesSelectedProvider() {
        when(openai.generateText(any(), any())).thenReturn("ok");

        AiProviderTestResponse response = service.testConnection(
                AiProviderTestRequest.builder().provider("open-ai").model("gpt-4o-mini").build());

        assertTrue(response.isSuccess());
        assertEquals("open-ai", response.getProvider());
        assertEquals("gpt-4o-mini", response.getModel());
        verify(openai).generateText(any(), any());
    }

    @Test
    void generateJsonPreservesRequestTimeoutOverride() {
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        when(openai.generateJson(any(), any(), any()))
                .thenReturn(new ObjectMapper().createObjectNode());

        service.generateJson(
                "prompt",
                AiJsonSchema.ofSchema("{}"),
                AiCallConfig.builder()
                        .provider("openai")
                        .model("gpt-4o-mini")
                        .timeoutSeconds(15)
                        .build(),
                "tenant",
                "user",
                "local");

        verify(openai).generateJson(any(), any(), configCaptor.capture());
        assertEquals(15, configCaptor.getValue().getTimeoutSeconds());
    }

    @Test
    void listCatalogIncludesProviderStreamingCapabilities() {
        when(gemini.supportsTextStreaming(any())).thenReturn(true);
        when(gemini.supportsTurnCancellation(any())).thenReturn(true);
        when(openai.supportsTextStreaming(any())).thenReturn(true);
        when(openai.supportsTurnCancellation(any())).thenReturn(true);

        AiProviderCatalogResponse response = service.listCatalog();

        assertEquals(3, response.getProviders().size());
        assertTrue(response.getProviders().stream()
                .anyMatch(item -> "gemini".equals(item.getId())
                        && item.isSupportsTextStreaming()
                        && item.isSupportsTurnCancellation()));
        assertTrue(response.getProviders().stream()
                .anyMatch(item -> "openai".equals(item.getId())
                        && item.isSupportsTextStreaming()
                        && item.isSupportsTurnCancellation()));
        assertTrue(response.getProviders().stream()
                .anyMatch(item -> "xai".equals(item.getId())
                        && !item.isSupportsTextStreaming()));
        assertTrue(response.getProviders().stream()
                .noneMatch(item -> "mock".equals(item.getId())));
    }

    @Test
    void explicitUnavailableProviderDoesNotFallbackToMockForModels() {
        when(mockProvider.getProviderName()).thenReturn("mock");
        AiProviderManagementService mockOnlyService = new AiProviderManagementService(
                objectMapper,
                userConfigService,
                apiKeyCryptoService,
                List.of(mockProvider));
        ReflectionTestUtils.setField(mockOnlyService, "defaultProvider", "mock");
        ReflectionTestUtils.setField(mockOnlyService, "openaiModel", "gpt-4o-mini");
        ReflectionTestUtils.setField(mockOnlyService, "geminiModel", "gemini-2.0-flash");
        ReflectionTestUtils.setField(mockOnlyService, "xaiModel", "grok-2-latest");
        mockOnlyService.initProviderRegistry();

        AiProviderModelsResponse response = mockOnlyService.listModels(
                AiProviderModelsRequest.builder().provider("gemini").apiKey("key").build());

        assertFalse(response.isSuccess());
        assertEquals("gemini", response.getProvider());
        assertTrue(response.getModels().isEmpty());
    }

    @Test
    void explicitUnavailableProviderDoesNotFallbackToMockForTestConnection() {
        when(mockProvider.getProviderName()).thenReturn("mock");
        AiProviderManagementService mockOnlyService = new AiProviderManagementService(
                objectMapper,
                userConfigService,
                apiKeyCryptoService,
                List.of(mockProvider));
        ReflectionTestUtils.setField(mockOnlyService, "defaultProvider", "mock");
        ReflectionTestUtils.setField(mockOnlyService, "openaiModel", "gpt-4o-mini");
        ReflectionTestUtils.setField(mockOnlyService, "geminiModel", "gemini-2.0-flash");
        ReflectionTestUtils.setField(mockOnlyService, "xaiModel", "grok-2-latest");
        mockOnlyService.initProviderRegistry();

        AiProviderTestResponse response = mockOnlyService.testConnection(
                AiProviderTestRequest.builder().provider("gemini").model("gemini-2.5-flash").apiKey("key").build());

        assertFalse(response.isSuccess());
        assertEquals("gemini", response.getProvider());
        assertEquals("gemini-2.5-flash", response.getModel());
    }
}
