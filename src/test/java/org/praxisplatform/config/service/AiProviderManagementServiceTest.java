package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.dto.AiAudioTranscriptionResponse;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        ReflectionTestUtils.setField(service, "transcriptionProvider", "openai");
        ReflectionTestUtils.setField(service, "transcriptionModel", "gpt-4o-mini-transcribe");
        service.initProviderRegistry();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(nullValues = "NULL", value = {
            "openai, gemini, gemini, openai, gpt-5.6-luna",
            "NULL, openai, gemini, openai, gpt-5.6-luna",
            "NULL, NULL, openai, openai, gpt-5.6-luna",
            "gemini, openai, openai, gemini, requested-model",
            "NULL, gemini, openai, gemini, requested-model",
            "open-ai, gemini, gemini, openai, gpt-5.6-luna"
    })
    void appliesPhaseModelOnlyAfterScopedProviderPrecedence(
            String requested, String stored, String host, String expectedProvider, String expectedModel) {
        ReflectionTestUtils.setField(service, "defaultProvider", host);
        var payload = objectMapper.createObjectNode();
        var ai = payload.putObject("ai").put("model", "stored-model");
        if (stored != null) ai.put("provider", stored);
        when(userConfigService.getResolved("tenant", "user", "praxis-global-config-editor",
                "praxis:global-config:tenant", "local"))
                .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(
                        UiUserConfig.builder().payload(payload.toString()).build(), UserConfigService.Scope.USER)));
        AiProvider adapter = expectedProvider.equals("openai") ? openai : gemini;
        when(adapter.generateJson(any(), any(), any())).thenReturn(objectMapper.createObjectNode());
        var trace = new AiProviderInvocationTrace("live_option_refinement", 1, requested, "requested-model");
        service.generateJson("synthetic", AiJsonSchema.ofSchema("{}"),
                AiCallConfig.agenticAuthoringBuilder().provider(requested).model("requested-model")
                        .providerModelOverrides(java.util.Map.of("openai", "gpt-5.6-luna"))
                        .invocationTrace(trace).build(), "tenant", "user", "local");
        var config = ArgumentCaptor.forClass(AiCallConfig.class);
        verify(adapter).generateJson(any(), any(), config.capture());
        assertEquals(expectedModel, config.getValue().getModel());
        assertEquals(expectedModel, trace.snapshot().model());
        verify(userConfigService).getResolved("tenant", "user", "praxis-global-config-editor",
                "praxis:global-config:tenant", "local");
        verifyNoMoreInteractions(userConfigService);
    }

    @Test
    void textGenerationUsesTheSameResolvedProviderPhasePolicy() {
        ReflectionTestUtils.setField(service, "defaultProvider", "openai");
        when(openai.generateText(any(), any())).thenReturn("synthetic");
        service.generateText("synthetic", AiCallConfig.agenticAuthoringBuilder().model("gpt-5-mini")
                .providerModelOverrides(java.util.Map.of("openai", "gpt-5.6-luna")).build(), null, null, null);
        var config = ArgumentCaptor.forClass(AiCallConfig.class);
        verify(openai).generateText(any(), config.capture());
        assertEquals("gpt-5.6-luna", config.getValue().getModel());
    }

    @Test
    void phaseModelPolicyStaysInternalAndSurvivesBuilderCopy() throws Exception {
        var config = AiCallConfig.agenticAuthoringBuilder().model("requested-model")
                .providerModelOverrides(java.util.Map.of("openai", "gpt-5.6-luna")).build();
        assertEquals(config.getProviderModelOverrides(), config.toBuilder().build().getProviderModelOverrides());
        assertFalse(objectMapper.valueToTree(config).has("providerModelOverrides"));
        var decoded = objectMapper.readValue(
                "{\"model\":\"requested-model\",\"providerModelOverrides\":{\"openai\":\"untrusted\"}}", AiCallConfig.class);
        assertTrue(decoded.getProviderModelOverrides() == null || decoded.getProviderModelOverrides().isEmpty());
    }

    @Test
    void transcribeAudioUsesConfiguredProviderModelAndScopedStoredCredentialExactlyOnce() {
        UiUserConfig storedConfig = UiUserConfig.builder()
                .payload("""
                        {"ai":{"provider":"openai","model":"gpt-5.4-mini","apiKeyEncrypted":"encrypted-test-key"}}
                        """)
                .build();
        when(userConfigService.getResolved(
                "tenant-a",
                "user-a",
                "praxis-global-config-editor",
                "praxis:global-config:tenant-a",
                "prod"))
                .thenReturn(Optional.of(new UserConfigService.ResolvedConfig(
                        storedConfig,
                        UserConfigService.Scope.USER)));
        when(apiKeyCryptoService.decrypt("encrypted-test-key")).thenReturn("resolved-test-key");
        when(openai.supportsAudioTranscription(any())).thenReturn(true);
        when(openai.transcribeAudio(any(), any())).thenReturn("  Criar uma página de missões  ");
        AiAudioTranscriptionRequest request = new AiAudioTranscriptionRequest(
                new byte[] {1, 2, 3},
                "voice.webm",
                "audio/webm",
                "pt-BR");
        ArgumentCaptor<AiAudioTranscriptionRequest> requestCaptor =
                ArgumentCaptor.forClass(AiAudioTranscriptionRequest.class);
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);

        AiAudioTranscriptionResponse response = service.transcribeAudio(
                request,
                "tenant-a",
                "user-a",
                "prod");

        assertEquals("praxis-ai-audio-transcription.v1", response.schemaVersion());
        assertEquals("  Criar uma página de missões  ", response.text());
        assertEquals("openai", response.provider());
        assertEquals("gpt-4o-mini-transcribe", response.model());
        assertEquals("pt-BR", response.language());
        assertFalse(response.toString().contains("resolved-test-key"));
        verify(openai).supportsAudioTranscription(any());
        verify(openai).transcribeAudio(requestCaptor.capture(), configCaptor.capture());
        assertArrayEquals(request.audio(), requestCaptor.getValue().audio());
        assertEquals("openai", configCaptor.getValue().getProvider());
        assertEquals("gpt-4o-mini-transcribe", configCaptor.getValue().getModel());
        assertEquals("resolved-test-key", configCaptor.getValue().getApiKey());
        assertEquals("tenant-a", configCaptor.getValue().getTenantId());
        assertEquals("prod", configCaptor.getValue().getEnvironment());
        verify(userConfigService).getResolved(
                "tenant-a",
                "user-a",
                "praxis-global-config-editor",
                "praxis:global-config:tenant-a",
                "prod");
        verifyNoMoreInteractions(userConfigService);
    }

    @Test
    void transcribeAudioRejectsAnUnavailableConfiguredProviderWithoutFallback() {
        ReflectionTestUtils.setField(service, "transcriptionProvider", "missing-provider");
        AiAudioTranscriptionRequest request = new AiAudioTranscriptionRequest(
                new byte[] {1}, "voice.webm", "audio/webm", null);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.transcribeAudio(request, null, null, null));

        assertEquals("Provider not available: missing-provider", error.getMessage());
        verify(openai, never()).supportsAudioTranscription(any());
        verify(openai, never()).transcribeAudio(any(), any());
    }

    @Test
    void transcribeAudioRejectsAProviderWithoutTheCapabilityBeforeInvokingItsAdapter() {
        when(openai.supportsAudioTranscription(any())).thenReturn(false);
        AiAudioTranscriptionRequest request = new AiAudioTranscriptionRequest(
                new byte[] {1}, "voice.webm", "audio/webm", null);

        UnsupportedOperationException error = assertThrows(
                UnsupportedOperationException.class,
                () -> service.transcribeAudio(request, null, null, null));

        assertEquals(
                "Configured provider does not support governed audio transcription: openai",
                error.getMessage());
        verify(openai, never()).transcribeAudio(any(), any());
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
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        doCallRealMethod().when(openai).testConnection(any());
        when(openai.listModels(any())).thenReturn(List.of());

        AiProviderTestResponse response = service.testConnection(
                AiProviderTestRequest.builder().provider("open-ai").model("gpt-4o-mini").build());

        assertTrue(response.isSuccess());
        assertEquals("open-ai", response.getProvider());
        assertEquals("gpt-4o-mini", response.getModel());
        verify(openai).testConnection(configCaptor.capture());
        assertEquals(32, configCaptor.getValue().getMaxTokens());
        verify(openai).listModels(any());
        verify(openai, never()).generateText(any(), any());
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
    void generateJsonPreservesInternalExecutionProfile() {
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        when(openai.generateJson(any(), any(), any()))
                .thenReturn(new ObjectMapper().createObjectNode());

        service.generateJson(
                "prompt",
                AiJsonSchema.ofSchema("{}"),
                AiCallConfig.agenticAuthoringBuilder()
                        .provider("openai")
                        .model("gpt-5.6-luna")
                        .build(),
                "tenant",
                "user",
                "local");

        verify(openai).generateJson(any(), any(), configCaptor.capture());
        assertEquals(AiExecutionProfile.AGENTIC_AUTHORING, configCaptor.getValue().getExecutionProfile());
    }

    @Test
    void generateJsonDoesNotBlockOnStoredConfigLookupBeyondCallBudget() {
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        when(openai.generateJson(any(), any(), any()))
                .thenReturn(new ObjectMapper().createObjectNode());
        org.mockito.Mockito.doAnswer(invocation -> {
                    Thread.sleep(5_000L);
                    return Optional.empty();
                })
                .when(userConfigService)
                .getResolved(any(), any(), any(), any(), any());

        long startedAt = System.nanoTime();
        service.generateJson(
                "prompt",
                AiJsonSchema.ofSchema("{}"),
                AiCallConfig.builder()
                        .provider("openai")
                        .model("gpt-4o-mini")
                        .timeoutSeconds(1)
                        .build(),
                "tenant",
                "user",
                "local");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertTrue(elapsedMs < 2_500L);
        verify(openai).generateJson(any(), any(), configCaptor.capture());
        assertEquals("openai", configCaptor.getValue().getProvider());
        assertEquals("gpt-4o-mini", configCaptor.getValue().getModel());
        assertEquals(1, configCaptor.getValue().getTimeoutSeconds());
    }

    @Test
    void interruptedAuthoringWorkerDoesNotReachProviderSelection() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> service.generateJson("cancelled turn", AiJsonSchema.ofSchema("{}"),
                            AiCallConfig.agenticAuthoringBuilder().provider("openai").build(),
                            "tenant", "user", "local"));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(openai, never()).generateJson(any(), any(), any());
            verify(userConfigService, never()).getResolved(any(), any(), any(), any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedConfigLookupDoesNotFallbackToPaidGeneration() throws Exception {
        var lookupStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseLookup = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var workerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        org.mockito.Mockito.doAnswer(invocation -> {
            lookupStarted.countDown();
            releaseLookup.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return Optional.empty();
        }).when(userConfigService).getResolved(any(), any(), any(), any(), any());
        try {
            var worker = executor.submit(() -> {
                workerThread.set(Thread.currentThread());
                assertThrows(java.util.concurrent.CancellationException.class,
                        () -> service.generateJson("cancelled lookup", AiJsonSchema.ofSchema("{}"),
                                AiCallConfig.agenticAuthoringBuilder().provider("openai").build(),
                                "tenant", "user", "local"));
                return Thread.currentThread().isInterrupted();
            });
            assertTrue(lookupStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            workerThread.get().interrupt();
            assertTrue(worker.get(1, java.util.concurrent.TimeUnit.SECONDS));
            verify(openai, never()).generateJson(any(), any(), any());
        } finally {
            releaseLookup.countDown();
            executor.shutdownNow();
        }
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
