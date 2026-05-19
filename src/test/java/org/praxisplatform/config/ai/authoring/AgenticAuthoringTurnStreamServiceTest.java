package org.praxisplatform.config.ai.authoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiThreadService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.AiTurnService;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringTurnStreamServiceTest {

    @Mock
    private AgenticAuthoringIntentResolverService intentResolverService;
    @Mock
    private AgenticAuthoringPreviewService previewService;
    @Mock
    private AiThreadService threadService;
    @Mock
    private AiTurnService turnService;
    @Mock
    private AiTurnEventService turnEventService;
    @Mock
    private AiStreamAccessTokenService streamAccessTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void startEmitsTerminalTimeoutWhenProcessingDoesNotFinish() throws Exception {
        UUID threadId = UUID.randomUUID();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest request = new AgenticAuthoringTurnStreamRequest(
                "Crie um painel",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                "gpt-test",
                null,
                "session-1",
                "turn-client-1",
                List.of(),
                null,
                List.of(),
                null,
                null);
        CountDownLatch intentStarted = new CountDownLatch(1);
        CountDownLatch releaseIntent = new CountDownLatch(1);

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Crie um painel")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class))).thenReturn(Optional.empty());
        when(turnEventService.isTerminalType(anyString()))
                .thenAnswer(invocation -> {
                    String type = invocation.getArgument(0, String.class);
                    return "result".equals(type) || "error".equals(type) || "cancelled".equals(type);
                });
        when(turnEventService.findLastEvent(any(UUID.class))).thenReturn(Optional.empty());
        when(turnEventService.appendEvent(any(), any(UUID.class), eq(threadId), any(UUID.class), anyString(), any()))
                .thenAnswer(invocation -> AiTurnEventEnvelope.builder()
                        .eventId(UUID.randomUUID())
                        .streamId(invocation.getArgument(1, UUID.class))
                        .threadId(invocation.getArgument(2, UUID.class))
                        .turnId(invocation.getArgument(3, UUID.class))
                        .type(invocation.getArgument(4, String.class))
                        .timestamp(Instant.now())
                        .payload(objectMapper.valueToTree(invocation.getArgument(5)))
                        .build());
        when(streamAccessTokenService.resolveAuthMode()).thenReturn("cookie");
        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenAnswer(invocation -> {
                    intentStarted.countDown();
                    releaseIntent.await(5, TimeUnit.SECONDS);
                    return null;
                });

        AgenticAuthoringTurnStreamService service = service();
        ReflectionTestUtils.setField(service, "processingTimeoutSeconds", 1L);
        service.start(request, "http://localhost", principalContext);

        intentStarted.await(2, TimeUnit.SECONDS);
        ArgumentCaptor<String> eventTypes = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(turnEventService, org.mockito.Mockito.timeout(4000))
                .appendEvent(any(), any(UUID.class), eq(threadId), any(UUID.class), eq("error"), any());
        org.mockito.Mockito.verify(turnEventService, atLeastOnce())
                .appendEvent(any(), any(UUID.class), eq(threadId), any(UUID.class), eventTypes.capture(), any());
        releaseIntent.countDown();
        service.shutdown();

        org.assertj.core.api.Assertions.assertThat(eventTypes.getAllValues()).contains("error");
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(turnEventService, atLeastOnce())
                .appendEvent(any(), any(UUID.class), eq(threadId), any(UUID.class), eq("error"), payloads.capture());
        org.assertj.core.api.Assertions.assertThat(payloads.getAllValues())
                .anySatisfy(payload -> {
                    JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("code").asText())
                            .isEqualTo("agentic-authoring-timeout");
                    org.assertj.core.api.Assertions.assertThat(node.path("assistantMessage").asText())
                            .contains("Demorei demais");
                    org.assertj.core.api.Assertions.assertThat(node.path("message").asText())
                            .contains("timed out");
                });
        verify(turnService, atLeastOnce()).expireTurn(eq(threadId), any(UUID.class));
        verify(previewService, never()).preview(any(), any(), any(), any());
    }

    @Test
    void timeoutTerminalPreventsLateCompletionAndPreview() throws Exception {
        UUID threadId = UUID.randomUUID();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest request = request();
        CountDownLatch intentStarted = new CountDownLatch(1);
        CountDownLatch releaseIntent = new CountDownLatch(1);
        AtomicLong seq = new AtomicLong();
        AtomicReference<AiTurnEventEnvelope> lastEvent = new AtomicReference<>();

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Crie um painel")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class))).thenReturn(Optional.empty());
        when(turnEventService.isTerminalType(anyString()))
                .thenAnswer(invocation -> isTerminal(invocation.getArgument(0, String.class)));
        when(turnEventService.findLastEvent(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(lastEvent.get()));
        when(turnEventService.appendEvent(any(), any(UUID.class), eq(threadId), any(UUID.class), anyString(), any()))
                .thenAnswer(invocation -> {
                    AiTurnEventEnvelope current = lastEvent.get();
                    if (current != null && isTerminal(current.getType())) {
                        return current;
                    }
                    AiTurnEventEnvelope event = AiTurnEventEnvelope.builder()
                            .eventId(UUID.randomUUID())
                            .streamId(invocation.getArgument(1, UUID.class))
                            .threadId(invocation.getArgument(2, UUID.class))
                            .turnId(invocation.getArgument(3, UUID.class))
                            .seq(seq.incrementAndGet())
                            .type(invocation.getArgument(4, String.class))
                            .timestamp(Instant.now())
                            .payload(objectMapper.valueToTree(invocation.getArgument(5)))
                            .build();
                    lastEvent.set(event);
                    return event;
                });
        when(streamAccessTokenService.resolveAuthMode()).thenReturn("cookie");
        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenAnswer(invocation -> {
                    intentStarted.countDown();
                    releaseIntent.await(5, TimeUnit.SECONDS);
                    return validIntent();
                });

        AgenticAuthoringTurnStreamService service = service();
        ReflectionTestUtils.setField(service, "processingTimeoutSeconds", 1L);
        service.start(request, "http://localhost", principalContext);

        intentStarted.await(2, TimeUnit.SECONDS);
        org.mockito.Mockito.verify(turnService, org.mockito.Mockito.timeout(4000))
                .expireTurn(eq(threadId), any(UUID.class));
        releaseIntent.countDown();
        org.mockito.Mockito.verify(turnService, org.mockito.Mockito.after(1000).never())
                .completeTurn(eq(threadId), any(UUID.class));
        service.shutdown();

        verify(previewService, never()).preview(any(), any(), any(), any());
    }

    @Test
    void processingFailureEmitsStableCodeAndSafeAssistantMessage() throws Exception {
        UUID threadId = UUID.randomUUID();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest request = request();

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Crie um painel")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class))).thenReturn(Optional.empty());
        when(turnEventService.isTerminalType(anyString()))
                .thenAnswer(invocation -> isTerminal(invocation.getArgument(0, String.class)));
        when(turnEventService.findLastEvent(any(UUID.class))).thenReturn(Optional.empty());
        List<Object> appendedPayloads = new CopyOnWriteArrayList<>();
        CountDownLatch processingFailureAppended = new CountDownLatch(1);
        when(turnEventService.appendEvent(any(), any(UUID.class), eq(threadId), any(UUID.class), anyString(), any()))
                .thenAnswer(invocation -> {
                    Object payload = invocation.getArgument(5);
                    appendedPayloads.add(payload);
                    JsonNode node = objectMapper.valueToTree(payload);
                    if ("agentic-authoring-processing-failed".equals(node.path("code").asText())) {
                        processingFailureAppended.countDown();
                    }
                    return AiTurnEventEnvelope.builder()
                            .eventId(UUID.randomUUID())
                            .streamId(invocation.getArgument(1, UUID.class))
                            .threadId(invocation.getArgument(2, UUID.class))
                            .turnId(invocation.getArgument(3, UUID.class))
                            .type(invocation.getArgument(4, String.class))
                            .timestamp(Instant.now())
                            .payload(node)
                            .build();
                });
        when(streamAccessTokenService.resolveAuthMode()).thenReturn("cookie");
        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenThrow(new IllegalStateException("provider quota exhausted"));

        AgenticAuthoringTurnStreamService service = service();
        service.start(request, "http://localhost", principalContext);

        org.assertj.core.api.Assertions.assertThat(processingFailureAppended.await(4, TimeUnit.SECONDS))
                .as("processing failure event should be appended before asserting captured payloads")
                .isTrue();
        service.shutdown();

        org.assertj.core.api.Assertions.assertThat(appendedPayloads)
                .anySatisfy(payload -> {
                    JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("code").asText())
                            .isEqualTo("agentic-authoring-processing-failed");
                    org.assertj.core.api.Assertions.assertThat(node.path("assistantMessage").asText())
                            .contains("Tive um problema");
                    org.assertj.core.api.Assertions.assertThat(node.path("message").asText())
                            .isEqualTo("provider quota exhausted");
                });
        verify(turnService).expireTurn(eq(threadId), any(UUID.class));
        verify(turnService, never()).completeTurn(eq(threadId), any(UUID.class));
    }

    @Test
    void streamExposesBackendRetrievalAndLlmSecondPassProgress() throws Exception {
        UUID threadId = UUID.randomUUID();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest request = request();

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Crie um painel")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class))).thenReturn(Optional.empty());
        when(turnEventService.isTerminalType(anyString()))
                .thenAnswer(invocation -> isTerminal(invocation.getArgument(0, String.class)));
        when(turnEventService.findLastEvent(any(UUID.class))).thenReturn(Optional.empty());
        when(turnEventService.appendEvent(any(), any(UUID.class), eq(threadId), any(UUID.class), anyString(), any()))
                .thenAnswer(invocation -> AiTurnEventEnvelope.builder()
                        .eventId(UUID.randomUUID())
                        .streamId(invocation.getArgument(1, UUID.class))
                        .threadId(invocation.getArgument(2, UUID.class))
                        .turnId(invocation.getArgument(3, UUID.class))
                        .type(invocation.getArgument(4, String.class))
                        .timestamp(Instant.now())
                        .payload(objectMapper.valueToTree(invocation.getArgument(5)))
                        .build());
        when(streamAccessTokenService.resolveAuthMode()).thenReturn("cookie");
        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(secondPassIntent());
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local"), eq("http://localhost")))
                .thenReturn(new AgenticAuthoringPreviewResult(
                        true,
                        List.of(),
                        List.of(),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        null,
                        null,
                        "Preview ready."));

        AgenticAuthoringTurnStreamService service = service();
        service.start(request, "http://localhost", principalContext);

        org.mockito.Mockito.verify(turnService, org.mockito.Mockito.timeout(4000))
                .completeTurn(eq(threadId), any(UUID.class));
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(turnEventService, org.mockito.Mockito.atLeast(7))
                .appendEvent(any(), any(UUID.class), eq(threadId), any(UUID.class), anyString(), payloads.capture());
        service.shutdown();

        org.assertj.core.api.Assertions.assertThat(payloads.getAllValues())
                .anySatisfy(payload -> {
                    JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText())
                            .isEqualTo("resource.discovery");
                    org.assertj.core.api.Assertions.assertThat(node.path("summary").asText())
                            .contains("backend catalog");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("source").asText())
                            .isEqualTo("backend-resource-catalog");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("candidateCount").asInt())
                            .isEqualTo(1);
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("selectedResourcePath").asText())
                            .isEqualTo("/api/human-resources/vw-resumo-missoes");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("retrievalSource").asText())
                            .isEqualTo("context_hint");
                })
                .anySatisfy(payload -> {
                    JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText())
                            .isEqualTo("intent.resolve.llm");
                    org.assertj.core.api.Assertions.assertThat(node.path("summary").asText())
                            .contains("reviewed refined backend resource candidates");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("secondPass").asBoolean())
                            .isTrue();
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("selectedResourcePath").asText())
                            .isEqualTo("/api/human-resources/vw-resumo-missoes");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("retrievalSource").asText())
                            .isEqualTo("context_hint");
                });
    }

    @Test
    void connectSchedulesTransientHeartbeatForActiveStream() {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AiTurnEventService.StreamOwnership ownership = new AiTurnEventService.StreamOwnership(
                streamId,
                threadId,
                turnId,
                "tenant",
                "user",
                "local",
                Instant.now().plusSeconds(60));
        AiTurnEventEnvelope tail = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .seq(2)
                .type("thought.step")
                .payload(objectMapper.createObjectNode()
                        .put("phase", "intent.resolve.llm")
                        .put("summary", "Asking the LLM to interpret the user request against governed context."))
                .build();

        when(turnEventService.replay(streamId, null, principalContext))
                .thenReturn(new AiTurnEventService.ReplayResult(ownership, List.of(), 0));
        when(turnEventService.findLastEvent(streamId)).thenReturn(Optional.of(tail));
        when(turnEventService.isTerminalType("thought.step")).thenReturn(false);

        AgenticAuthoringTurnStreamService service = service();
        ReflectionTestUtils.setField(service, "heartbeatSeconds", 1L);
        ReflectionTestUtils.setField(service, "processingPollSeconds", 60L);
        service.connect(streamId, null, principalContext);

        org.mockito.Mockito.verify(turnEventService, org.mockito.Mockito.timeout(2500).atLeast(2))
                .findLastEvent(streamId);
        service.shutdown();
    }

    @Test
    void heartbeatSummaryUsesUserFacingStatusMessageWhenAvailable() {
        AiTurnEventEnvelope tail = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(UUID.randomUUID())
                .type("status")
                .payload(objectMapper.createObjectNode()
                        .put("phase", "intent.resolve.llm")
                        .put("message", "Estou resolvendo sua intencao com o contexto governado antes de escolher recursos ou componentes."))
                .build();

        String summary = ReflectionTestUtils.invokeMethod(service(), "heartbeatSummary", tail);

        org.assertj.core.api.Assertions.assertThat(summary)
                .isEqualTo("Estou resolvendo sua intencao com o contexto governado antes de escolher recursos ou componentes.");
    }

    @Test
    void heartbeatSummaryUsesSpecificIntentResolutionFallbacks() {
        AiTurnEventEnvelope intentResolve = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(UUID.randomUUID())
                .type("thought.step")
                .payload(objectMapper.createObjectNode()
                        .put("phase", "intent.resolve")
                        .put("summary", "Preparing semantic intent resolution."))
                .build();
        AiTurnEventEnvelope llmResolve = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(UUID.randomUUID())
                .type("thought.step")
                .payload(objectMapper.createObjectNode()
                        .put("phase", "intent.resolve.llm")
                        .put("summary", "Resolving the user request against governed context."))
                .build();

        String intentSummary = ReflectionTestUtils.invokeMethod(service(), "heartbeatSummary", intentResolve);
        String llmSummary = ReflectionTestUtils.invokeMethod(service(), "heartbeatSummary", llmResolve);

        org.assertj.core.api.Assertions.assertThat(intentSummary)
                .isEqualTo("Estou organizando o pedido, a pagina atual e as restricoes governadas.");
        org.assertj.core.api.Assertions.assertThat(llmSummary)
                .isEqualTo("Estou resolvendo a intencao com o contexto governado antes de escolher recursos ou componentes.");
    }

    @Test
    void cancelDoesNotOverwriteTurnWhenTerminalEventWinsRace() {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AiTurnEventEnvelope resultEvent = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .seq(2)
                .type("result")
                .payload(objectMapper.createObjectNode())
                .build();
        when(turnEventService.requireOwnership(streamId, principalContext))
                .thenReturn(new AiTurnEventService.StreamOwnership(
                        streamId,
                        threadId,
                        turnId,
                        "tenant",
                        "user",
                        "local",
                        Instant.now().plusSeconds(60)));
        when(turnEventService.findLastEvent(streamId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(resultEvent))
                .thenReturn(Optional.of(resultEvent));
        when(turnEventService.isTerminalType("result")).thenReturn(true);

        AiPatchStreamCancelResponse response = service().cancel(streamId, principalContext);

        org.assertj.core.api.Assertions.assertThat(response.getTerminalState()).isEqualTo("completed");
        verify(turnEventService, never())
                .appendEvent(any(), eq(streamId), eq(threadId), eq(turnId), eq("cancelled"), any());
        verify(turnService, never()).cancelTurn(threadId, turnId);
    }

    private AgenticAuthoringTurnStreamRequest request() {
        return new AgenticAuthoringTurnStreamRequest(
                "Crie um painel",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                "gpt-test",
                null,
                "session-1",
                "turn-client-1",
                List.of(),
                null,
                List.of(),
                null,
                null);
    }

    private AgenticAuthoringIntentResolutionResult validIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_chart",
                "page-builder",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                null,
                List.of(),
                new AgenticAuthoringGateResult("eligible", "eligible", List.of()),
                null,
                "Preview ready.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult secondPassIntent() {
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-resumo-missoes",
                "read",
                "/api/human-resources/vw-resumo-missoes/schema",
                "/api/human-resources/vw-resumo-missoes/filter",
                "POST",
                0.91,
                "Matches the payroll dashboard request.",
                List.of("tool-search-api-resources"));
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_dashboard",
                "page-builder",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                candidate,
                List.of(candidate),
                new AgenticAuthoringGateResult("eligible", "eligible", List.of()),
                null,
                "Preview ready for Resumo missoes.",
                List.of(),
                List.of(),
                List.of("llm-intent-resolution-second-pass-used"),
                List.of(),
                objectMapper.createObjectNode());
    }

    private boolean isTerminal(String type) {
        return "result".equals(type) || "error".equals(type) || "cancelled".equals(type);
    }

    private AgenticAuthoringTurnStreamService service() {
        AgenticAuthoringTurnEngine turnEngine = new AgenticAuthoringTurnEngine(
                intentResolverService,
                previewService,
                objectMapper,
                new AgenticAuthoringCurrentPageAnalyzer(objectMapper),
                new AgenticAuthoringToolRegistry(new AgenticAuthoringResourceDiscoveryService(null, objectMapper)));
        return new AgenticAuthoringTurnStreamService(
                turnEngine,
                threadService,
                turnService,
                turnEventService,
                streamAccessTokenService);
    }
}
