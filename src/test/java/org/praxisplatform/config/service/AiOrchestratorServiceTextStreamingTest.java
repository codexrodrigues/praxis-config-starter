package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AiOrchestratorServiceTextStreamingTest {

    @Mock
    private AiProvider aiProvider;

    @Mock
    private AiInteractionLogger interactionLogger;

    private AiOrchestratorService service;

    @BeforeEach
    void setUp() {
        service = new AiOrchestratorService(
                null,
                aiProvider,
                interactionLogger,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper(),
                null,
                null,
                null);
    }

    @Test
    void callAiTextUsesProviderStreamWhenEnabledAndStreamTransport() {
        ReflectionTestUtils.setField(service, "providerTextStreamEnabled", true);
        ReflectionTestUtils.setField(service, "providerTextStreamFallbackSyncOnError", true);
        AiCallConfig config = AiCallConfig.builder().provider("openai").build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .streamTransport(true)
                .build();

        when(aiProvider.supportsTextStreaming(config)).thenReturn(true);
        when(aiProvider.generateTextStream(eq("prompt"), eq(config), isNull(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<Boolean> cancellationRequested = invocation.getArgument(3, Supplier.class);
            return Boolean.TRUE.equals(cancellationRequested.get()) ? "streamed" : "sync";
        });

        AtomicBoolean cancelled = new AtomicBoolean(true);
        String text = AiStreamExecutionContextHolder.execute(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                cancelled::get,
                () -> ReflectionTestUtils.invokeMethod(
                        service,
                        "callAiText",
                        "qa_answer",
                        "prompt",
                        config,
                        request,
                        1));

        assertEquals("streamed", text);
        verify(aiProvider).generateTextStream(eq("prompt"), eq(config), isNull(), any());
        verify(aiProvider, never()).generateText("prompt", config);
    }

    @Test
    void callAiTextFallsBackToSyncWhenStreamingDisabled() {
        ReflectionTestUtils.setField(service, "providerTextStreamEnabled", false);
        ReflectionTestUtils.setField(service, "providerTextStreamFallbackSyncOnError", true);
        AiCallConfig config = AiCallConfig.builder().provider("openai").build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .streamTransport(true)
                .build();

        when(aiProvider.generateText("prompt", config)).thenReturn("sync");

        String text = ReflectionTestUtils.invokeMethod(
                service,
                "callAiText",
                "qa_answer",
                "prompt",
                config,
                request,
                1);

        assertEquals("sync", text);
        verify(aiProvider).generateText("prompt", config);
        verify(aiProvider, never()).generateTextStream(eq("prompt"), eq(config), isNull(), any());
    }

    @Test
    void callAiTextFallsBackToSyncWhenProviderStreamFailsWithTransportError() {
        ReflectionTestUtils.setField(service, "providerTextStreamEnabled", true);
        ReflectionTestUtils.setField(service, "providerTextStreamFallbackSyncOnError", true);
        AiCallConfig config = AiCallConfig.builder().provider("gemini").build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .streamTransport(true)
                .build();

        when(aiProvider.supportsTextStreaming(config)).thenReturn(true);
        when(aiProvider.generateTextStream(eq("prompt"), eq(config), isNull(), any()))
                .thenThrow(new IllegalStateException("Gemini API HTTP 503: capacity exhausted"));
        when(aiProvider.generateText("prompt", config)).thenReturn("sync-fallback");

        String text = ReflectionTestUtils.invokeMethod(
                service,
                "callAiText",
                "qa_answer",
                "prompt",
                config,
                request,
                1);

        assertEquals("sync-fallback", text);
        verify(aiProvider).generateTextStream(eq("prompt"), eq(config), isNull(), any());
        verify(aiProvider).generateText("prompt", config);
    }

    @Test
    void callAiTextFallsBackToSyncWhenProviderStreamFailsWithStructuredCapacityError() {
        ReflectionTestUtils.setField(service, "providerTextStreamEnabled", true);
        ReflectionTestUtils.setField(service, "providerTextStreamFallbackSyncOnError", true);
        AiCallConfig config = AiCallConfig.builder().provider("gemini").build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .streamTransport(true)
                .build();

        when(aiProvider.supportsTextStreaming(config)).thenReturn(true);
        when(aiProvider.generateTextStream(eq("prompt"), eq(config), isNull(), any()))
                .thenThrow(AiProviderStreamException.fromHttpStatus("gemini", 503, "capacity exhausted"));
        when(aiProvider.generateText("prompt", config)).thenReturn("sync-fallback");

        String text = ReflectionTestUtils.invokeMethod(
                service,
                "callAiText",
                "qa_answer",
                "prompt",
                config,
                request,
                1);

        assertEquals("sync-fallback", text);
        verify(aiProvider).generateTextStream(eq("prompt"), eq(config), isNull(), any());
        verify(aiProvider).generateText("prompt", config);
    }

    @Test
    void callAiTextDoesNotFallbackForStructuredAuthError() {
        ReflectionTestUtils.setField(service, "providerTextStreamEnabled", true);
        ReflectionTestUtils.setField(service, "providerTextStreamFallbackSyncOnError", true);
        AiCallConfig config = AiCallConfig.builder().provider("gemini").build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .streamTransport(true)
                .build();

        when(aiProvider.supportsTextStreaming(config)).thenReturn(true);
        when(aiProvider.generateTextStream(eq("prompt"), eq(config), isNull(), any()))
                .thenThrow(AiProviderStreamException.fromHttpStatus("gemini", 403, "forbidden"));

        AiProviderStreamException thrown = assertThrows(
                AiProviderStreamException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        service,
                        "callAiText",
                        "qa_answer",
                        "prompt",
                        config,
                        request,
                        1));

        assertEquals(AiProviderStreamException.Kind.AUTH, thrown.getKind());
        verify(aiProvider).generateTextStream(eq("prompt"), eq(config), isNull(), any());
        verify(aiProvider, never()).generateText("prompt", config);
    }

    @Test
    void callAiTextDoesNotFallbackForNonTransportProviderError() {
        ReflectionTestUtils.setField(service, "providerTextStreamEnabled", true);
        ReflectionTestUtils.setField(service, "providerTextStreamFallbackSyncOnError", true);
        AiCallConfig config = AiCallConfig.builder().provider("gemini").build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .streamTransport(true)
                .build();

        when(aiProvider.supportsTextStreaming(config)).thenReturn(true);
        when(aiProvider.generateTextStream(eq("prompt"), eq(config), isNull(), any()))
                .thenThrow(new IllegalArgumentException("validation failed"));

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        service,
                        "callAiText",
                        "qa_answer",
                        "prompt",
                        config,
                        request,
                        1));

        assertEquals("validation failed", thrown.getMessage());
        verify(aiProvider).generateTextStream(eq("prompt"), eq(config), isNull(), any());
        verify(aiProvider, never()).generateText("prompt", config);
    }
}
