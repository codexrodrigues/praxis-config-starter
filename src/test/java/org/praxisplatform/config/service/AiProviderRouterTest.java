package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

        when(xai.generateText("ping")).thenReturn("pong");

        String result = router.generateText("ping");

        assertEquals("pong", result);
        verify(xai).generateText("ping");
        verifyNoInteractions(gemini, openai, mock);
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
