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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AiOrchestratorServiceTextStreamingTest {

    private static final String METRIC_STREAM_FALLBACK_TOTAL = "ai_stream_fallback_total";

    @Mock
    private AiProvider aiProvider;

    @Mock
    private AiInteractionLogger interactionLogger;

    private AiOrchestratorService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        Metrics.addRegistry(meterRegistry);
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

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(meterRegistry);
        meterRegistry.close();
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
        assertEquals(1.0d, fallbackCounterValue("gemini", "capacity"));
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
        assertEquals(1.0d, fallbackCounterValue("gemini", "capacity"));
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
        assertEquals(0.0d, fallbackCounterValue("gemini", "auth"));
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

    private double fallbackCounterValue(String provider, String reasonKind) {
        Counter counter = meterRegistry.find(METRIC_STREAM_FALLBACK_TOTAL)
                .tags("provider", provider, "reason_kind", reasonKind)
                .counter();
        return counter != null ? counter.count() : 0.0d;
    }
}
