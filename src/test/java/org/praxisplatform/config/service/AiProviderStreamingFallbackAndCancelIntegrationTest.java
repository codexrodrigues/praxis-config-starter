package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiProviderModel;
import org.springframework.test.util.ReflectionTestUtils;

class AiProviderStreamingFallbackAndCancelIntegrationTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void shouldFallbackToSyncTextWhenProviderStreamFailsWithTransportError() {
        StubProvider provider = StubProvider.stream503ThenSync();
        AiOrchestratorService service = new AiOrchestratorService(
                null,
                provider,
                mock(AiInteractionLogger.class),
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper(),
                null,
                null,
                null);
        ReflectionTestUtils.setField(service, "providerTextStreamEnabled", true);
        ReflectionTestUtils.setField(service, "providerTextStreamFallbackSyncOnError", true);

        AiCallConfig config = AiCallConfig.builder().provider("stub").build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .streamTransport(true)
                .build();

        String result = ReflectionTestUtils.invokeMethod(
                service,
                "callAiText",
                "qa_answer",
                "ping",
                config,
                request,
                1);

        assertEquals("sync-fallback", result);
        assertEquals(1, provider.streamCalls.get());
        assertEquals(1, provider.syncCalls.get());
    }

    @Test
    void shouldCancelInFlightProviderStreamWithoutWaitingNetworkTimeout() throws Exception {
        StubProvider provider = StubProvider.longRunningStream();
        AiCallConfig config = AiCallConfig.builder().provider("stub").build();
        AtomicBoolean cancellationRequested = new AtomicBoolean(false);
        UUID streamId = UUID.randomUUID();

        Future<String> worker = executor.submit(() -> AiStreamExecutionContextHolder.execute(
                streamId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                cancellationRequested::get,
                () -> provider.generateTextStream("ping", config, null, cancellationRequested::get)));

        assertTrue(provider.awaitStreamStarted(1, TimeUnit.SECONDS));
        AiStreamExecutionContextHolder.abortStream(streamId);

        ExecutionException executionException = assertThrows(
                ExecutionException.class,
                () -> worker.get(2, TimeUnit.SECONDS));
        assertInstanceOf(CancellationException.class, executionException.getCause());
        assertTrue(provider.abortHookTriggered.get());
    }

    @Test
    void shouldFallbackForTransportCapacityTimeoutFailureMatrix() {
        Map<String, RuntimeException> matrix = Map.of(
                "capacity", AiProviderStreamException.fromHttpStatus("stub", 503, "capacity exhausted"),
                "rate-limit", AiProviderStreamException.fromHttpStatus("stub", 429, "rate limit"),
                "timeout", AiProviderStreamException.timeout("stub", new java.net.http.HttpTimeoutException("timed out")),
                "connect", new RuntimeException(new java.net.ConnectException("connection refused")),
                "reset", new RuntimeException(new java.net.SocketException("connection reset")));

        matrix.forEach((scenario, failure) -> {
            StubProvider provider = StubProvider.streamFailsWith(failure);
            AiOrchestratorService service = new AiOrchestratorService(
                    null,
                    provider,
                    mock(AiInteractionLogger.class),
                    null,
                    null,
                    null,
                    null,
                    null,
                    new ObjectMapper(),
                    null,
                    null,
                    null);
            ReflectionTestUtils.setField(service, "providerTextStreamEnabled", true);
            ReflectionTestUtils.setField(service, "providerTextStreamFallbackSyncOnError", true);

            AiCallConfig config = AiCallConfig.builder().provider("stub").build();
            AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                    .streamTransport(true)
                    .build();

            String result = ReflectionTestUtils.invokeMethod(
                    service,
                    "callAiText",
                    "qa_answer",
                    "ping-" + scenario,
                    config,
                    request,
                    1);

            assertEquals("sync-fallback", result, "Scenario should fallback: " + scenario);
            assertEquals(1, provider.streamCalls.get(), "Expected one stream attempt: " + scenario);
            assertEquals(1, provider.syncCalls.get(), "Expected one sync fallback call: " + scenario);
        });
    }

    private static final class StubProvider implements AiProvider {

        private final Mode mode;
        private final RuntimeException streamFailure;
        private final AtomicInteger streamCalls = new AtomicInteger(0);
        private final AtomicInteger syncCalls = new AtomicInteger(0);
        private final CountDownLatch streamStarted = new CountDownLatch(1);
        private final AtomicBoolean abortHookTriggered = new AtomicBoolean(false);

        private StubProvider(Mode mode) {
            this(mode, null);
        }

        private StubProvider(Mode mode, RuntimeException streamFailure) {
            this.mode = mode;
            this.streamFailure = streamFailure;
        }

        static StubProvider stream503ThenSync() {
            return new StubProvider(Mode.STREAM_503_THEN_SYNC);
        }

        static StubProvider longRunningStream() {
            return new StubProvider(Mode.LONG_RUNNING_STREAM);
        }

        static StubProvider streamFailsWith(RuntimeException failure) {
            return new StubProvider(Mode.STREAM_FAILS, failure);
        }

        boolean awaitStreamStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return streamStarted.await(timeout, unit);
        }

        @Override
        public JsonNode generateJson(String prompt) {
            return null;
        }

        @Override
        public String generateText(String prompt) {
            syncCalls.incrementAndGet();
            return "sync-fallback";
        }

        @Override
        public boolean supportsTextStreaming(AiCallConfig config) {
            return true;
        }

        @Override
        public boolean supportsTurnCancellation(AiCallConfig config) {
            return true;
        }

        @Override
        public String generateTextStream(
                String prompt,
                AiCallConfig config,
                Consumer<String> onChunk,
                Supplier<Boolean> cancellationRequested) {
            streamCalls.incrementAndGet();
            if (mode == Mode.STREAM_503_THEN_SYNC) {
                throw new IllegalStateException("provider stream http 503");
            }
            if (mode == Mode.STREAM_FAILS) {
                throw streamFailure != null ? streamFailure : new RuntimeException("stream failure");
            }

            AiStreamExecutionContextHolder.AbortRegistration registration =
                    AiStreamExecutionContextHolder.registerAbortAction(() -> abortHookTriggered.set(true));
            try {
                streamStarted.countDown();
                while (!Boolean.TRUE.equals(cancellationRequested.get()) && !abortHookTriggered.get()) {
                    Thread.sleep(10L);
                }
                throw new CancellationException("stream cancelled");
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new CancellationException("stream interrupted");
            } finally {
                registration.close();
            }
        }

        @Override
        public List<AiProviderModel> listModels(AiCallConfig config) {
            return List.of();
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

        private enum Mode {
            STREAM_503_THEN_SYNC,
            STREAM_FAILS,
            LONG_RUNNING_STREAM
        }
    }
}
