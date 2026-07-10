package org.praxisplatform.config.ai.authoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

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

    @BeforeEach
    void stubAtomicStartAppend() {
        org.mockito.Mockito.lenient()
                .when(turnEventService.appendStartEventIfAbsent(
                        any(),
                        any(UUID.class),
                        any(UUID.class),
                        any(UUID.class),
                        any()))
                .thenAnswer(invocation -> new AiTurnEventService.StreamStartAppendResult(
                        AiTurnEventEnvelope.builder()
                                .eventId(UUID.randomUUID())
                                .streamId(invocation.getArgument(1, UUID.class))
                                .threadId(invocation.getArgument(2, UUID.class))
                                .turnId(invocation.getArgument(3, UUID.class))
                                .seq(1L)
                                .type("status")
                                .timestamp(Instant.now())
                                .payload(objectMapper.valueToTree(invocation.getArgument(4)))
                                .build(),
                        true));
    }

    @Test
    void startEventUsesCuratedContextBundleMessage() throws Exception {
        UUID threadId = UUID.randomUUID();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest request = request();

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Crie um painel")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class))).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient()
                .when(turnEventService.isTerminalType(anyString()))
                .thenAnswer(invocation -> isTerminal(invocation.getArgument(0, String.class)));
        org.mockito.Mockito.lenient()
                .when(turnEventService.findLastEvent(any(UUID.class))).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient()
                .when(turnEventService.appendEvent(any(), any(UUID.class), eq(threadId), any(UUID.class), anyString(), any()))
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
        org.mockito.Mockito.lenient()
                .when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(validIntent());
        org.mockito.Mockito.lenient()
                .when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local"), eq("http://localhost")))
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

        ArgumentCaptor<Object> startPayload = ArgumentCaptor.forClass(Object.class);
        verify(turnEventService).appendStartEventIfAbsent(
                any(),
                any(UUID.class),
                eq(threadId),
                any(UUID.class),
                startPayload.capture());
        JsonNode node = objectMapper.valueToTree(startPayload.getValue());
        org.assertj.core.api.Assertions.assertThat(node.path("phase").asText()).isEqualTo("context.bundle");
        org.assertj.core.api.Assertions.assertThat(node.path("message").asText())
                .isEqualTo("Recebi seu pedido e estou preparando o contexto governado.")
                .doesNotContain("Agentic authoring stream started");
        org.assertj.core.api.Assertions.assertThat(node.path("requestHash").asText())
                .startsWith("sha256:")
                .doesNotContain("test-key")
                .doesNotContain("sampleRows");

        service.shutdown();
    }

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
        AgenticAuthoringTurnStreamService.StartResult startResult =
                service.start(request, "http://localhost", principalContext);
        org.assertj.core.api.Assertions.assertThat(startResult.response().getFallbackAuthoringUrl())
                .isEqualTo("http://localhost/api/praxis/config/ai/authoring/page-preview");

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
    void startEmitsPersistedProcessingProgressWhileBackendIsStillWorking() throws Exception {
        UUID threadId = UUID.randomUUID();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest request = request();
        CountDownLatch intentStarted = new CountDownLatch(1);
        CountDownLatch releaseIntent = new CountDownLatch(1);
        List<Object> statusPayloads = new CopyOnWriteArrayList<>();

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Crie um painel")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class))).thenReturn(Optional.empty());
        when(turnEventService.isTerminalType(anyString()))
                .thenAnswer(invocation -> isTerminal(invocation.getArgument(0, String.class)));
        when(turnEventService.findLastEvent(any(UUID.class))).thenReturn(Optional.empty());
        when(turnEventService.appendEvent(any(), any(UUID.class), eq(threadId), any(UUID.class), anyString(), any()))
                .thenAnswer(invocation -> {
                    String type = invocation.getArgument(4, String.class);
                    Object payload = invocation.getArgument(5);
                    if ("status".equals(type)) {
                        statusPayloads.add(payload);
                    }
                    return AiTurnEventEnvelope.builder()
                            .eventId(UUID.randomUUID())
                            .streamId(invocation.getArgument(1, UUID.class))
                            .threadId(invocation.getArgument(2, UUID.class))
                            .turnId(invocation.getArgument(3, UUID.class))
                            .type(type)
                            .timestamp(Instant.now())
                            .payload(objectMapper.valueToTree(payload))
                            .build();
                });
        when(streamAccessTokenService.resolveAuthMode()).thenReturn("cookie");
        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenAnswer(invocation -> {
                    intentStarted.countDown();
                    releaseIntent.await(5, TimeUnit.SECONDS);
                    return validIntent();
                });

        AgenticAuthoringTurnStreamService service = service();
        ReflectionTestUtils.setField(service, "processingTimeoutSeconds", 5L);
        ReflectionTestUtils.setField(service, "processingProgressSeconds", 1L);
        service.start(request, "http://localhost", principalContext);

        org.assertj.core.api.Assertions.assertThat(intentStarted.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(1300L);
        releaseIntent.countDown();
        service.shutdown();

        List<JsonNode> watchdogPayloads = statusPayloads.stream()
                .map(payload -> (JsonNode) objectMapper.valueToTree(payload))
                .filter(node -> "backend-processing-progress-watchdog"
                        .equals(node.path("diagnostics").path("source").asText()))
                .toList();
        org.assertj.core.api.Assertions.assertThat(watchdogPayloads)
                .isNotEmpty()
                .allSatisfy(node -> {
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("elapsedSeconds").asLong())
                            .isGreaterThanOrEqualTo(1L);
                    org.assertj.core.api.Assertions.assertThat(node.path("state").asText()).isEqualTo("in_progress");
                    org.assertj.core.api.Assertions.assertThat(node.path("message").asText())
                            .containsAnyOf(
                                    "Recebi seu pedido",
                                    "Estou organizando sua intencao",
                                    "A LLM esta revisando")
                            .doesNotContain("Preparing semantic intent resolution")
                            .doesNotContain("backend-processing-progress-watchdog");
                });
    }

    @Test
    void startPassesGroundedRuntimeComponentContextToAsyncTurnEngine() throws Exception {
        UUID threadId = UUID.randomUUID();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest request = new AgenticAuthoringTurnStreamRequest(
                "Quem participa da missão selecionada?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                "gpt-test",
                null,
                "session-runtime",
                "turn-runtime-grounding",
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                List.of(runtimeObservation()),
                AgenticAuthoringRuntimeComponentGroundingService.TRUST_BOUNDARY_UNTRUSTED_FRONTEND_OBSERVATION);
        AgenticAuthoringTurnEngine turnEngine = org.mockito.Mockito.mock(AgenticAuthoringTurnEngine.class);
        CountDownLatch processed = new CountDownLatch(1);

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Quem participa da missão selecionada?")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class))).thenReturn(Optional.empty());
        when(streamAccessTokenService.resolveAuthMode()).thenReturn("cookie");
        when(turnEngine.execute(any(), eq(principalContext), any(), eq("http://localhost")))
                .thenAnswer(invocation -> {
                    processed.countDown();
                    return AgenticAuthoringTurnEngine.AgenticAuthoringTurnOutcome.completed(
                            new AgenticAuthoringTurnEngine.AgenticAuthoringTurnState(
                                    "advisory_authoring",
                                    null,
                                    null));
                });

        AgenticAuthoringTurnStreamService service = new AgenticAuthoringTurnStreamService(
                turnEngine,
                threadService,
                turnService,
                turnEventService,
                streamAccessTokenService);
        service.start(request, "http://localhost", principalContext);

        org.assertj.core.api.Assertions.assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        service.shutdown();
        ArgumentCaptor<AgenticAuthoringTurnStreamRequest> requestCaptor =
                ArgumentCaptor.forClass(AgenticAuthoringTurnStreamRequest.class);
        verify(turnEngine).execute(requestCaptor.capture(), eq(principalContext), any(), eq("http://localhost"));
        JsonNode groundedContext = requestCaptor.getValue().contextHints().path("groundedRuntimeComponentContext");
        org.assertj.core.api.Assertions.assertThat(groundedContext.path("canonicalContext").asText())
                .isEqualTo("GroundedRuntimeComponentContext");
        org.assertj.core.api.Assertions.assertThat(groundedContext.path("availableSurfaces").toString())
                .contains("missionTeam");
        org.assertj.core.api.Assertions.assertThat(groundedContext.toString())
                .doesNotContain("Ana Torres")
                .doesNotContain("sampleRows");
    }

    @Test
    void startWithExistingClientTurnIdReplaysExistingStreamWithoutProcessingAgain() {
        UUID threadId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(5);
        Instant expiresAt = Instant.now().plusSeconds(300);
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest request = request();
        AgenticAuthoringTurnEngine turnEngine = org.mockito.Mockito.mock(AgenticAuthoringTurnEngine.class);

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Crie um painel")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class)))
                .thenReturn(Optional.of(new AiTurnEventService.StreamStartMetadata(
                        streamId,
                        threadId,
                        UUID.randomUUID(),
                        createdAt,
                        expiresAt,
                        requestHash(request),
                        "status")));
        when(streamAccessTokenService.resolveAuthMode()).thenReturn("cookie");

        AgenticAuthoringTurnStreamService service = new AgenticAuthoringTurnStreamService(
                turnEngine,
                threadService,
                turnService,
                turnEventService,
                streamAccessTokenService);

        AgenticAuthoringTurnStreamService.StartResult result =
                service.start(request, "http://localhost", principalContext);
        service.shutdown();

        org.assertj.core.api.Assertions.assertThat(result.created()).isFalse();
        org.assertj.core.api.Assertions.assertThat(result.response().getStreamId()).isEqualTo(streamId);
        verify(turnService, never()).reserveTurnForStreaming(any(), any());
        verify(turnEngine, never()).execute(any(), any(), any(), anyString());
        verify(turnEventService, never()).appendEvent(any(), any(UUID.class), eq(threadId), any(UUID.class), anyString(), any());
    }

    @Test
    void startWithExistingClientTurnIdConflictsWhenCanonicalRequestFingerprintDiffers() {
        UUID threadId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(5);
        Instant expiresAt = Instant.now().plusSeconds(300);
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest originalRequest = request();
        AgenticAuthoringTurnStreamRequest changedRequest = new AgenticAuthoringTurnStreamRequest(
                "Crie um painel executivo",
                originalRequest.targetApp(),
                originalRequest.targetComponentId(),
                originalRequest.currentRoute(),
                originalRequest.currentPage(),
                originalRequest.selectedWidgetKey(),
                originalRequest.provider(),
                originalRequest.model(),
                originalRequest.apiKey(),
                originalRequest.sessionId(),
                originalRequest.clientTurnId(),
                originalRequest.conversationMessages(),
                originalRequest.pendingClarification(),
                originalRequest.attachmentSummaries(),
                originalRequest.contextHints(),
                originalRequest.componentCapabilities(),
                originalRequest.activeSemanticDecision());
        AgenticAuthoringTurnEngine turnEngine = org.mockito.Mockito.mock(AgenticAuthoringTurnEngine.class);

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Crie um painel executivo")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class)))
                .thenReturn(Optional.of(new AiTurnEventService.StreamStartMetadata(
                        streamId,
                        threadId,
                        UUID.randomUUID(),
                        createdAt,
                        expiresAt,
                        requestHash(originalRequest),
                        "status")));

        AgenticAuthoringTurnStreamService service = new AgenticAuthoringTurnStreamService(
                turnEngine,
                threadService,
                turnService,
                turnEventService,
                streamAccessTokenService);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.start(changedRequest, "http://localhost", principalContext))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException response = (ResponseStatusException) ex;
                    org.assertj.core.api.Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    org.assertj.core.api.Assertions.assertThat(response.getReason())
                            .isEqualTo("agentic-authoring-idempotency-conflict");
                });
        service.shutdown();

        verify(turnService, never()).reserveTurnForStreaming(any(), any());
        verify(turnEngine, never()).execute(any(), any(), any(), anyString());
    }

    @Test
    void startWithExistingClientTurnIdConflictsWhenSemanticDecisionDiffers() {
        UUID threadId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(5);
        Instant expiresAt = Instant.now().plusSeconds(300);
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest originalRequest = request();
        AgenticAuthoringTurnStreamRequest changedRequest = withSemanticDecision(originalRequest, "decision-b");
        AgenticAuthoringTurnEngine turnEngine = org.mockito.Mockito.mock(AgenticAuthoringTurnEngine.class);

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Crie um painel")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class)))
                .thenReturn(Optional.of(new AiTurnEventService.StreamStartMetadata(
                        streamId,
                        threadId,
                        UUID.randomUUID(),
                        createdAt,
                        expiresAt,
                        requestHash(withSemanticDecision(originalRequest, "decision-a")),
                        "status")));

        AgenticAuthoringTurnStreamService service = new AgenticAuthoringTurnStreamService(
                turnEngine,
                threadService,
                turnService,
                turnEventService,
                streamAccessTokenService);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.start(changedRequest, "http://localhost", principalContext))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        service.shutdown();

        verify(turnService, never()).reserveTurnForStreaming(any(), any());
        verify(turnEngine, never()).execute(any(), any(), any(), anyString());
    }

    @Test
    void startWithConcurrentStartAppendReplaysExistingStreamWithoutProcessingAgain() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = stableUuid("agentic-authoring-turn", "turn-client-1");
        UUID existingStreamId = UUID.randomUUID();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest request = request();
        AgenticAuthoringTurnEngine turnEngine = org.mockito.Mockito.mock(AgenticAuthoringTurnEngine.class);
        AiTurnEventEnvelope existingStart = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(existingStreamId)
                .threadId(threadId)
                .turnId(turnId)
                .seq(1L)
                .type("status")
                .timestamp(Instant.now().minusSeconds(2))
                .payload(objectMapper.createObjectNode()
                        .put("state", "started")
                        .put("requestHash", requestHash(request)))
                .build();

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Crie um painel")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class))).thenReturn(Optional.empty());
        when(turnEventService.appendStartEventIfAbsent(
                eq(principalContext),
                any(UUID.class),
                eq(threadId),
                eq(turnId),
                any()))
                .thenReturn(new AiTurnEventService.StreamStartAppendResult(existingStart, false));
        when(streamAccessTokenService.resolveAuthMode()).thenReturn("cookie");

        AgenticAuthoringTurnStreamService service = new AgenticAuthoringTurnStreamService(
                turnEngine,
                threadService,
                turnService,
                turnEventService,
                streamAccessTokenService);

        AgenticAuthoringTurnStreamService.StartResult result =
                service.start(request, "http://localhost", principalContext);
        service.shutdown();

        org.assertj.core.api.Assertions.assertThat(result.created()).isFalse();
        org.assertj.core.api.Assertions.assertThat(result.response().getStreamId()).isEqualTo(existingStreamId);
        org.assertj.core.api.Assertions.assertThat(result.response().getTurnId()).isEqualTo(turnId);
        verify(turnService).reserveTurnForStreaming(threadId, turnId);
        verify(turnEngine, never()).execute(any(), any(), any(), anyString());
        verify(turnEventService, never()).appendEvent(any(), any(UUID.class), eq(threadId), eq(turnId), anyString(), any());
    }

    @Test
    void startWithConcurrentStartAppendConflictsWhenExistingStartFingerprintDiffers() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = stableUuid("agentic-authoring-turn", "turn-client-1");
        UUID existingStreamId = UUID.randomUUID();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest request = request();
        AgenticAuthoringTurnEngine turnEngine = org.mockito.Mockito.mock(AgenticAuthoringTurnEngine.class);
        AiTurnEventEnvelope existingStart = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(existingStreamId)
                .threadId(threadId)
                .turnId(turnId)
                .seq(1L)
                .type("status")
                .timestamp(Instant.now().minusSeconds(2))
                .payload(objectMapper.createObjectNode()
                        .put("state", "started")
                        .put("requestHash", "sha256:different"))
                .build();

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Crie um painel")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class))).thenReturn(Optional.empty());
        when(turnEventService.appendStartEventIfAbsent(
                eq(principalContext),
                any(UUID.class),
                eq(threadId),
                eq(turnId),
                any()))
                .thenReturn(new AiTurnEventService.StreamStartAppendResult(existingStart, false));

        AgenticAuthoringTurnStreamService service = new AgenticAuthoringTurnStreamService(
                turnEngine,
                threadService,
                turnService,
                turnEventService,
                streamAccessTokenService);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.start(request, "http://localhost", principalContext))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        service.shutdown();

        verify(turnService).reserveTurnForStreaming(threadId, turnId);
        verify(turnEngine, never()).execute(any(), any(), any(), anyString());
        verify(turnEventService, never()).appendEvent(any(), any(UUID.class), eq(threadId), eq(turnId), anyString(), any());
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
    void eventSinkTerminalReachedReconcilesPersistedTerminalEventFromAnotherInstance() throws Exception {
        UUID threadId = UUID.randomUUID();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest request = request();
        AgenticAuthoringTurnEngine turnEngine = org.mockito.Mockito.mock(AgenticAuthoringTurnEngine.class);
        AtomicReference<AiTurnEventEnvelope> persistedTail = new AtomicReference<>();
        AtomicReference<Boolean> terminalSeenByEngine = new AtomicReference<>(false);
        CountDownLatch processed = new CountDownLatch(1);

        when(threadService.resolveThread(any(), eq("tenant"), eq("user"), eq("local"), eq("Crie um painel")))
                .thenReturn(AiThread.builder().threadId(threadId).build());
        when(turnEventService.findStartMetadata(eq(threadId), any(UUID.class))).thenReturn(Optional.empty());
        when(turnEventService.isTerminalType(anyString()))
                .thenAnswer(invocation -> isTerminal(invocation.getArgument(0, String.class)));
        when(turnEventService.findLastEvent(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(persistedTail.get()));
        when(streamAccessTokenService.resolveAuthMode()).thenReturn("cookie");
        when(turnEngine.execute(any(), eq(principalContext), any(), eq("http://localhost")))
                .thenAnswer(invocation -> {
                    AgenticAuthoringTurnEventSink sink = invocation.getArgument(2, AgenticAuthoringTurnEventSink.class);
                    persistedTail.set(AiTurnEventEnvelope.builder()
                            .eventId(UUID.randomUUID())
                            .streamId(UUID.randomUUID())
                            .threadId(threadId)
                            .turnId(UUID.randomUUID())
                            .seq(99L)
                            .type("result")
                            .timestamp(Instant.now())
                            .payload(objectMapper.createObjectNode())
                            .build());
                    terminalSeenByEngine.set(sink.terminalReached());
                    processed.countDown();
                    return AgenticAuthoringTurnEngine.AgenticAuthoringTurnOutcome.completed(
                            new AgenticAuthoringTurnEngine.AgenticAuthoringTurnState(
                                    "terminal-reconciled",
                                    null,
                                    null));
                });

        AgenticAuthoringTurnStreamService service = new AgenticAuthoringTurnStreamService(
                turnEngine,
                threadService,
                turnService,
                turnEventService,
                streamAccessTokenService);
        service.start(request, "http://localhost", principalContext);

        org.assertj.core.api.Assertions.assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        service.shutdown();

        org.assertj.core.api.Assertions.assertThat(terminalSeenByEngine.get()).isTrue();
        verify(turnEventService, atLeastOnce()).findLastEvent(any(UUID.class));
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
        org.mockito.Mockito.verify(turnService, org.mockito.Mockito.timeout(4000))
                .expireTurn(eq(threadId), any(UUID.class));
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

        org.mockito.Mockito.verify(turnEventService, org.mockito.Mockito.timeout(2500).atLeastOnce())
                .findLastEvent(streamId);
        service.shutdown();
    }

    @Test
    void connectDoesNotSchedulePollingReplayForLocallyProducedStream() throws Exception {
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
        ReflectionTestUtils.setField(service, "processingPollSeconds", 1L);
        AgenticAuthoringTurnStreamService.StartResult startResult =
                service.start(request, "http://localhost", principalContext);
        org.assertj.core.api.Assertions.assertThat(intentStarted.await(2, TimeUnit.SECONDS)).isTrue();
        UUID streamId = startResult.response().getStreamId();
        when(turnEventService.replay(streamId, null, principalContext))
                .thenReturn(new AiTurnEventService.ReplayResult(
                        new AiTurnEventService.StreamOwnership(
                                streamId,
                                threadId,
                                startResult.response().getTurnId(),
                                "tenant",
                                "user",
                                "local",
                                Instant.now().plusSeconds(60)),
                        List.of(lastEvent.get()),
                        0));

        service.connect(streamId, null, principalContext);
        Thread.sleep(1300L);
        releaseIntent.countDown();
        service.shutdown();

        verify(turnEventService, never()).replayFromSeq(eq(streamId), anyLong(), eq(principalContext));
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
    void heartbeatSummaryUsesUserFacingToolLabelWhenAvailable() {
        AiTurnEventEnvelope tail = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(UUID.randomUUID())
                .type("thought.step")
                .payload(objectMapper.createObjectNode()
                        .put("phase", "tool.result")
                        .put("label", "Busca governada concluida; vou usar os candidatos como evidencia."))
                .build();

        String summary = ReflectionTestUtils.invokeMethod(service(), "heartbeatSummary", tail);

        org.assertj.core.api.Assertions.assertThat(summary)
                .isEqualTo("Busca governada concluida; vou usar os candidatos como evidencia.");
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
                .isEqualTo("A LLM esta revisando a intencao com a evidencia governada recuperada.");
    }

    @Test
    void intentResolvedWithoutPhaseUsesGroundingProgressFallback() {
        AiTurnEventEnvelope intentResolved = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(UUID.randomUUID())
                .type("intent.resolved")
                .payload(objectMapper.createObjectNode())
                .build();

        String phase = ReflectionTestUtils.invokeMethod(service(), "heartbeatPhase", intentResolved);
        String progress = ReflectionTestUtils.invokeMethod(service(), "processingProgressMessage", intentResolved, 24L);

        org.assertj.core.api.Assertions.assertThat(phase).isEqualTo("intent.resolve.grounding");
        org.assertj.core.api.Assertions.assertThat(progress)
                .contains("validando a decisao semantica")
                .contains("24 segundos")
                .doesNotContain("Ainda estou processando");
    }

    @Test
    void processingProgressMessageExplainsLongLlmWaitWithCuratedText() {
        AiTurnEventEnvelope tail = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(UUID.randomUUID())
                .type("thought.step")
                .payload(objectMapper.createObjectNode()
                        .put("phase", "intent.resolve.llm")
                        .put("summary", "Resolving the user request against governed context."))
                .build();

        String message = ReflectionTestUtils.invokeMethod(service(), "processingProgressMessage", tail, 32L);

        org.assertj.core.api.Assertions.assertThat(message)
                .contains("A LLM esta revisando a intencao com as evidencias governadas")
                .contains("32 segundos")
                .doesNotContain("Resolving the user request")
                .doesNotContain("backend-processing-progress-watchdog");
    }

    @Test
    void processingProgressMessageExplainsSkippedAuthoringEvidenceWithCuratedText() {
        AiTurnEventEnvelope tail = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(UUID.randomUUID())
                .type("thought.step")
                .payload(objectMapper.createObjectNode()
                        .put("phase", "authoringEvidence.skipped")
                        .put("summary", "No component selected."))
                .build();

        String message = ReflectionTestUtils.invokeMethod(service(), "processingProgressMessage", tail, 33L);

        org.assertj.core.api.Assertions.assertThat(message)
                .contains("Nao ha componente selecionado")
                .contains("recurso governado")
                .contains("33 segundos")
                .doesNotContain("Ainda estou processando")
                .doesNotContain("No component selected");
    }

    @Test
    void processingProgressMessageExplainsComponentCapabilityLoadingWithCuratedText() {
        AiTurnEventEnvelope tail = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(UUID.randomUUID())
                .type("thought.step")
                .payload(objectMapper.createObjectNode()
                        .put("phase", "component.capabilities")
                        .put("summary", "Loaded governed component capabilities."))
                .build();

        String heartbeat = ReflectionTestUtils.invokeMethod(service(), "heartbeatSummary", tail);
        String progress = ReflectionTestUtils.invokeMethod(service(), "processingProgressMessage", tail, 41L);

        org.assertj.core.api.Assertions.assertThat(heartbeat)
                .contains("capacidades governadas dos componentes")
                .doesNotContain("Loaded governed component capabilities");
        org.assertj.core.api.Assertions.assertThat(progress)
                .contains("capacidades governadas dos componentes")
                .contains("41 segundos")
                .doesNotContain("Loaded governed component capabilities");
    }

    @Test
    void processingProgressSkipsTailThatWasSupersededByIntentResolved() {
        UUID streamId = UUID.randomUUID();
        AiTurnEventEnvelope staleLlmTail = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(streamId)
                .seq(10L)
                .timestamp(Instant.now())
                .type("thought.step")
                .payload(objectMapper.createObjectNode()
                        .put("phase", "intent.resolve.llm")
                        .put("summary", "Resolving the user request against governed context."))
                .build();
        AiTurnEventEnvelope intentResolved = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(streamId)
                .seq(11L)
                .timestamp(Instant.now())
                .type("intent.resolved")
                .payload(objectMapper.createObjectNode())
                .build();
        when(turnEventService.findLastEvent(streamId)).thenReturn(Optional.of(intentResolved));

        Boolean stillLatest = ReflectionTestUtils.invokeMethod(
                service(),
                "isStillLatestEvent",
                streamId,
                staleLlmTail);

        org.assertj.core.api.Assertions.assertThat(stillLatest).isFalse();
    }

    @Test
    void processingProgressAppendRejectsObservedEventThatIsNoLongerLatest() {
        UUID observedEventId = UUID.randomUUID();
        UUID latestEventId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("phase", "preview.plan")
                .put("message", "Estou planejando como materializar a decisao em uma pre-visualizacao revisavel.");
        payload.putObject("diagnostics")
                .put("source", "backend-processing-progress-watchdog")
                .put("observedEventId", observedEventId.toString())
                .put("observedSeq", 23L);
        AiTurnEventEnvelope latestEvent = AiTurnEventEnvelope.builder()
                .eventId(latestEventId)
                .streamId(UUID.randomUUID())
                .seq(24L)
                .timestamp(Instant.now())
                .type("status")
                .payload(objectMapper.createObjectNode()
                        .put("phase", "preview.compile"))
                .build();

        Boolean stale = ReflectionTestUtils.invokeMethod(
                service(),
                "isStaleProcessingProgress",
                payload,
                latestEvent);

        org.assertj.core.api.Assertions.assertThat(stale).isTrue();
    }

    @Test
    void heartbeatDiagnosticsMarkRuntimeAuditPhaseAsTechnicalDuplicate() {
        UUID streamId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("phase", "runtime.tool-plan.step")
                .put("summary", "Runtime tool plan step status recorded.");
        payload.putObject("streamEventDiagnostics")
                .put("schemaVersion", "praxis-authoring-stream-event-diagnostics.v1")
                .put("dedupeKey", "runtime.tool-plan.step:consultativeIntent:runtime-tool-step:missionTeam")
                .put("eventUniquenessKey", "runtime.tool-plan.step:consultativeIntent:runtime-tool-step:missionTeam")
                .put("technicalDuplicate", false)
                .put("replaySafe", true)
                .put("duplicatesDoNotIndicateExecution", true);
        AiTurnEventEnvelope tail = AiTurnEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .streamId(streamId)
                .type("thought.step")
                .payload(payload)
                .build();

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> diagnostics = ReflectionTestUtils.invokeMethod(
                service(),
                "technicalDuplicateStreamEventDiagnostics",
                "heartbeat",
                streamId,
                "runtime.tool-plan.step",
                tail);

        org.assertj.core.api.Assertions.assertThat(diagnostics)
                .containsEntry("schemaVersion", "praxis-authoring-stream-event-diagnostics.v1")
                .containsEntry("dedupeKey", "runtime.tool-plan.step:consultativeIntent:runtime-tool-step:missionTeam")
                .containsEntry("technicalDuplicate", true)
                .containsEntry("technicalDuplicateOf", "runtime.tool-plan.step:consultativeIntent:runtime-tool-step:missionTeam")
                .containsEntry("replaySafe", true)
                .containsEntry("duplicatesDoNotIndicateExecution", true);
        org.assertj.core.api.Assertions.assertThat(String.valueOf(diagnostics.get("eventUniquenessKey")))
                .contains("heartbeat")
                .contains("runtime.tool-plan.step");
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

    private AgenticAuthoringTurnStreamRequest withSemanticDecision(
            AgenticAuthoringTurnStreamRequest request,
            String decisionId) {
        AgenticAuthoringSemanticDecision decision = new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                decisionId,
                "create",
                "dashboard",
                "create_dashboard",
                null,
                null,
                null,
                false,
                "",
                "",
                "");
        return new AgenticAuthoringTurnStreamRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                request.contextHints(),
                request.componentCapabilities(),
                decision);
    }

    private String requestHash(AgenticAuthoringTurnStreamRequest request) {
        try {
            ObjectNode fingerprint = objectMapper.createObjectNode();
            fingerprint.put("schemaVersion", "praxis-agentic-authoring-turn-request-fingerprint.v1");
            putIfPresent(fingerprint, "userPrompt", request.userPrompt());
            putIfPresent(fingerprint, "targetApp", request.targetApp());
            putIfPresent(fingerprint, "targetComponentId", request.targetComponentId());
            putIfPresent(fingerprint, "currentRoute", request.currentRoute());
            putIfPresent(fingerprint, "currentPage", request.currentPage());
            putIfPresent(fingerprint, "selectedWidgetKey", request.selectedWidgetKey());
            putIfPresent(fingerprint, "provider", request.provider());
            putIfPresent(fingerprint, "model", request.model());
            putIfPresent(fingerprint, "sessionId", request.sessionId());
            putIfPresent(fingerprint, "conversationMessages", request.conversationMessages());
            putIfPresent(fingerprint, "pendingClarification", request.pendingClarification());
            putIfPresent(fingerprint, "attachmentSummaries", request.attachmentSummaries());
            putIfPresent(fingerprint, "contextHints", request.contextHints());
            putIfPresent(fingerprint, "componentCapabilities", request.componentCapabilities());
            putIfPresent(fingerprint, "activeSemanticDecision", request.activeSemanticDecision());
            putIfPresent(fingerprint, "diagnostics", request.diagnostics());
            putIfPresent(
                    fingerprint,
                    "runtimeComponentObservationTrustBoundary",
                    request.runtimeComponentObservationTrustBoundary());
            byte[] raw = objectMapper.writeValueAsBytes(canonicalize(fingerprint));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(raw));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private void putIfPresent(ObjectNode target, String fieldName, Object value) {
        if (value == null) {
            return;
        }
        JsonNode node = value instanceof JsonNode jsonNode ? jsonNode : objectMapper.valueToTree(value);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        target.set(fieldName, node);
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            Iterator<String> iterator = node.fieldNames();
            while (iterator.hasNext()) {
                fieldNames.add(iterator.next());
            }
            Collections.sort(fieldNames);
            for (String fieldName : fieldNames) {
                canonical.set(fieldName, canonicalize(node.get(fieldName)));
            }
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                canonical.add(canonicalize(item));
            }
            return canonical;
        }
        return node;
    }

    private JsonNode runtimeObservation() throws Exception {
        return objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-runtime-component-observation.v1",
                  "identity": {
                    "instanceId": "mission-summary-table",
                    "componentId": "praxis-table",
                    "componentType": "table",
                    "widgetKey": "missionSummary",
                    "ownerPackage": "@praxisui/table"
                  },
                  "refs": {
                    "resourceKey": "missions",
                    "pageId": "mission-command-center"
                  },
                  "lifecycle": {
                    "active": true,
                    "visible": true
                  },
                  "snapshot": {
                    "selectionDigest": {
                      "selectedCount": 1,
                      "selectedIds": ["1"],
                      "idField": "missaoId",
                      "sampleRows": [{"titulo": "Operacao Aurora"}]
                    },
                    "schemaFieldRefs": ["titulo", "status", "prioridade", "ameaca"]
                  },
                  "affordances": {
                    "activeSurfaceRefs": ["missionTeam"],
                    "activeActionRefs": ["table.selection", "dynamicPage.surface.open"]
                  },
                  "claims": [
                    {"kind": "surface", "ref": "missionTeam", "observed": true}
                  ]
                }
                """);
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

    private UUID stableUuid(String namespace, String value) {
        return UUID.nameUUIDFromBytes(
                (namespace + ":" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
