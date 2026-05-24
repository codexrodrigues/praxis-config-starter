package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.AiProviderModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AiProviderRouterTest {

    @Mock
    private SpringAiGeminiService gemini;

    @Mock
    private SpringAiOpenAiService openai;

    @Mock
    private SpringAiXaiService xai;

    @Mock
    private MockAiService mock;

    @Mock
    private ObjectProvider<SpringAiGeminiService> geminiProvider;

    @Mock
    private ObjectProvider<SpringAiOpenAiService> openaiProvider;

    @Mock
    private ObjectProvider<SpringAiXaiService> xaiProvider;

    @Mock
    private ObjectProvider<MockAiService> mockProvider;

    @Test
    void listModelsUsesConfigProviderAlias() {
        when(openaiProvider.getIfAvailable(any())).thenReturn(openai);
        AiProviderRouter router = new AiProviderRouter(geminiProvider, openaiProvider, xaiProvider, mockProvider);
        ReflectionTestUtils.setField(router, "provider", "gemini");

        AiProviderModel model = AiProviderModel.builder().name("gpt-4o-mini").build();
        when(openai.listModels(any())).thenReturn(List.of(model));

        AiCallConfig config = AiCallConfig.builder().provider("open-ai").build();
        List<AiProviderModel> models = router.listModels(config);

        assertEquals(1, models.size());
        assertEquals("gpt-4o-mini", models.get(0).getName());
        verify(openai).listModels(config);
        verifyNoInteractions(gemini, xai, mock);
    }

    @Test
    void generateTextUsesDefaultProviderWhenNoOverride() {
        when(xaiProvider.getIfAvailable(any())).thenReturn(xai);
        AiProviderRouter router = new AiProviderRouter(geminiProvider, openaiProvider, xaiProvider, mockProvider);
        ReflectionTestUtils.setField(router, "provider", "xai");

        when(xai.generateText(eq("ping"), any())).thenReturn("pong");

        String result = router.generateText("ping");

        assertEquals("pong", result);
        verify(xai).generateText(eq("ping"), any());
        verifyNoInteractions(gemini, openai, mock);
    }

    @Test
    void generateTextFallsBackToNextConfiguredProviderForRateLimit() {
        when(openaiProvider.getIfAvailable(any())).thenReturn(openai);
        when(geminiProvider.getIfAvailable(any())).thenReturn(gemini);
        AiProviderRouter router = new AiProviderRouter(geminiProvider, openaiProvider, xaiProvider, mockProvider);
        ReflectionTestUtils.setField(router, "provider", "openai");
        ReflectionTestUtils.setField(router, "providerFallbackEnabled", true);
        ReflectionTestUtils.setField(router, "providerFallbackCandidates", "gemini:gemini-2.0-flash");

        when(openai.generateText(eq("prompt"), any()))
                .thenThrow(AiProviderCallException.fromHttpStatus("openai", 429, "rate_limit_exceeded"));
        when(gemini.generateText(eq("prompt"), any())).thenReturn("answer");

        String result = router.generateText("prompt");

        assertEquals("answer", result);
        verify(openai).generateText(eq("prompt"), any());
        ArgumentCaptor<AiCallConfig> fallbackConfig = ArgumentCaptor.forClass(AiCallConfig.class);
        verify(gemini).generateText(eq("prompt"), fallbackConfig.capture());
        assertEquals("gemini", fallbackConfig.getValue().getProvider());
        assertEquals("gemini-2.0-flash", fallbackConfig.getValue().getModel());
        verifyNoInteractions(xai, mock);
    }

    @Test
    void generateTextFallsBackFromGeminiToOpenAiForRateLimit() {
        when(geminiProvider.getIfAvailable(any())).thenReturn(gemini);
        when(openaiProvider.getIfAvailable(any())).thenReturn(openai);
        AiProviderRouter router = new AiProviderRouter(geminiProvider, openaiProvider, xaiProvider, mockProvider);
        ReflectionTestUtils.setField(router, "provider", "gemini");
        ReflectionTestUtils.setField(router, "providerFallbackEnabled", true);
        ReflectionTestUtils.setField(router, "providerFallbackCandidates", "openai:gpt-5-mini");

        when(gemini.generateText(eq("prompt"), any()))
                .thenThrow(AiProviderCallException.fromHttpStatus("gemini", 429, "rateLimitExceeded"));
        when(openai.generateText(eq("prompt"), any())).thenReturn("answer");

        String result = router.generateText("prompt");

        assertEquals("answer", result);
        verify(gemini).generateText(eq("prompt"), any());
        ArgumentCaptor<AiCallConfig> fallbackConfig = ArgumentCaptor.forClass(AiCallConfig.class);
        verify(openai).generateText(eq("prompt"), fallbackConfig.capture());
        assertEquals("openai", fallbackConfig.getValue().getProvider());
        assertEquals("gpt-5-mini", fallbackConfig.getValue().getModel());
        verifyNoInteractions(xai, mock);
    }

    @Test
    void generateTextDoesNotFallbackForQuotaFailures() {
        when(openaiProvider.getIfAvailable(any())).thenReturn(openai);
        AiProviderRouter router = new AiProviderRouter(geminiProvider, openaiProvider, xaiProvider, mockProvider);
        ReflectionTestUtils.setField(router, "provider", "openai");
        ReflectionTestUtils.setField(router, "providerFallbackEnabled", true);
        ReflectionTestUtils.setField(router, "providerFallbackCandidates", "gemini");

        AiProviderCallException quotaFailure = AiProviderCallException.fromHttpStatus(
                "openai",
                429,
                "insufficient_quota");
        when(openai.generateText(eq("prompt"), any())).thenThrow(quotaFailure);

        AiProviderCallException result = assertThrows(
                AiProviderCallException.class,
                () -> router.generateText("prompt"));

        assertEquals(AiProviderCallException.Kind.QUOTA_EXHAUSTED, result.getKind());
        verify(openai).generateText(eq("prompt"), any());
        verifyNoInteractions(gemini, xai, mock);
    }

    @Test
    void supportsTextStreamingDelegatesToResolvedProvider() {
        when(openaiProvider.getIfAvailable(any())).thenReturn(openai);
        AiProviderRouter router = new AiProviderRouter(geminiProvider, openaiProvider, xaiProvider, mockProvider);
        ReflectionTestUtils.setField(router, "provider", "gemini");

        AiCallConfig config = AiCallConfig.builder().provider("openai").build();
        when(openai.supportsTextStreaming(config)).thenReturn(true);

        boolean result = router.supportsTextStreaming(config);

        assertTrue(result);
        verify(openai).supportsTextStreaming(config);
    }

    @Test
    void generateTextStreamDelegatesToResolvedProvider() {
        when(openaiProvider.getIfAvailable(any())).thenReturn(openai);
        AiProviderRouter router = new AiProviderRouter(geminiProvider, openaiProvider, xaiProvider, mockProvider);
        ReflectionTestUtils.setField(router, "provider", "gemini");

        AiCallConfig config = AiCallConfig.builder().provider("openai").build();
        AtomicInteger chunks = new AtomicInteger(0);
        when(openai.generateTextStream(any(), any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onChunk = invocation.getArgument(2, java.util.function.Consumer.class);
            onChunk.accept("chunk");
            return "chunk";
        });

        String result = router.generateTextStream("prompt", config, chunk -> chunks.incrementAndGet(), () -> false);

        assertEquals("chunk", result);
        assertEquals(1, chunks.get());
        verify(openai).generateTextStream(any(), any(), any(), any());
        verifyNoInteractions(gemini, xai, mock);
    }

    @Test
    void cancelTurnDelegatesToDefaultProvider() {
        when(geminiProvider.getIfAvailable(any())).thenReturn(gemini);
        AiProviderRouter router = new AiProviderRouter(geminiProvider, openaiProvider, xaiProvider, mockProvider);
        ReflectionTestUtils.setField(router, "provider", "gemini");

        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        router.cancelTurn(threadId, turnId);

        verify(gemini).cancelTurn(threadId, turnId);
    }
}
