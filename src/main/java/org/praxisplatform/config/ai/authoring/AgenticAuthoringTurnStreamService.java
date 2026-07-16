package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Metrics;
import jakarta.annotation.PreDestroy;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnEngine.AgenticAuthoringTurnOutcome;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnEngine.Completion;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiAssistantObservationService;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiThreadService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.AiTurnService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgenticAuthoringTurnStreamService {

    private static final int WORKER_CORE_POOL_SIZE = 4;
    private static final int WORKER_MAX_POOL_SIZE = 16;
    private static final int WORKER_QUEUE_CAPACITY = 500;
    private static final int SCHEDULER_POOL_SIZE = 2;
    private static final long TERMINAL_TOMBSTONE_SECONDS = 60L;
    private static final String REQUEST_FINGERPRINT_SCHEMA_VERSION =
            "praxis-agentic-authoring-turn-request-fingerprint.v1";
    private static final String IDEMPOTENCY_CONFLICT_REASON =
            "agentic-authoring-idempotency-conflict";
    private static final String CAPACITY_REJECTED_CODE =
            "agentic-authoring-stream-capacity-exceeded";
    private static final String EXECUTOR_REJECTED_CODE =
            "agentic-authoring-stream-executor-saturated";

    private final AgenticAuthoringTurnEngine turnEngine;
    private final AiThreadService threadService;
    private final AiTurnService turnService;
    private final AiTurnEventService turnEventService;
    private final AiStreamAccessTokenService streamAccessTokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringRuntimeComponentGroundingService runtimeComponentGroundingService =
            new AgenticAuthoringRuntimeComponentGroundingService(objectMapper);

    @Autowired(required = false)
    private AiAssistantObservationService assistantObservationService;

    private final Map<UUID, Set<SseEmitter>> emittersByStream = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> replayCursorByStream = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> replayTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> processingTimeoutTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> processingProgressTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> terminalCleanupTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Future<?>> processingTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> streamStartedAtByStream = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> terminalByStream = new ConcurrentHashMap<>();
    private final Map<UUID, AiTurnEventEnvelope> latestEventByStream = new ConcurrentHashMap<>();
    private final Map<UUID, CapacityOwner> capacityOwnersByStream = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> tenantActiveCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> tenantUserActiveCounts = new ConcurrentHashMap<>();
    private final Object capacityLock = new Object();
    private final ExecutorService executor = createExecutor();
    private final ScheduledExecutorService scheduler = createScheduler();

    @Value("${praxis.ai.stream.event-schema-version:v1}")
    private String eventSchemaVersion;

    @Value("${praxis.ai.stream.expires-seconds:900}")
    private long streamExpiresSeconds;

    @Value("${praxis.ai.stream.emitter-timeout-ms:300000}")
    private long emitterTimeoutMs;

    @Value("${praxis.ai.stream.processing-poll-seconds:1}")
    private long processingPollSeconds;

    @Value("${praxis.ai.authoring.stream.heartbeat-seconds:${praxis.ai.stream.heartbeat-seconds:5}}")
    private long heartbeatSeconds;

    @Value("${praxis.ai.stream.processing-timeout-seconds:180}")
    private long processingTimeoutSeconds;

    @Value("${praxis.ai.authoring.stream.processing-progress-seconds:8}")
    private long processingProgressSeconds;

    @Value("${praxis.ai.authoring.stream.max-active-global:${praxis.ai.stream.max-active-global:200}}")
    private int maxActiveGlobal;

    @Value("${praxis.ai.authoring.stream.max-active-per-tenant:${praxis.ai.stream.max-active-per-tenant:50}}")
    private int maxActivePerTenant;

    @Value("${praxis.ai.authoring.stream.max-active-per-user:${praxis.ai.stream.max-active-per-user:10}}")
    private int maxActivePerUser;

    @Value("${praxis.ai.authoring.stream.max-emitters-per-stream:4}")
    private int maxEmittersPerStream;

    @Value("${praxis.ai.authoring.stream.max-replay-pollers:${praxis.ai.authoring.stream.max-active-global:${praxis.ai.stream.max-active-global:200}}}")
    private int maxReplayPollers;

    public StartResult start(
            AgenticAuthoringTurnStreamRequest request,
            String baseUrl,
            AiPrincipalContext principalContext) {
        validate(request);
        request = withGroundedRuntimeComponentContext(request);
        request = withRequestBaseUrl(request, baseUrl);
        UUID turnId = stableUuid("agentic-authoring-turn", request.clientTurnId());
        UUID requestedThreadId = parseUuid(request.sessionId());
        AiOrchestratorRequest threadRequest = AiOrchestratorRequest.builder()
                .componentId(nonBlank(request.targetComponentId(), "praxis-dynamic-page-builder"))
                .componentType("page-builder")
                .userPrompt(request.userPrompt())
                .sessionId(requestedThreadId)
                .clientTurnId(turnId)
                .currentState(request.currentPage())
                .contextHints(request.contextHints())
                .mode(requestedThreadId == null ? "new" : "continue")
                .build();
        AiThread thread = threadService.resolveThread(
                threadRequest,
                principalContext.tenantId(),
                principalContext.userId(),
                principalContext.environment(),
                request.userPrompt());
        UUID threadId = thread.getThreadId();
        request = withCanonicalSessionId(request, threadId);
        AgenticAuthoringSemanticDecision activeSemanticDecision = request.activeSemanticDecision() != null
                ? request.activeSemanticDecision()
                : turnEventService.findLatestSemanticDecision(threadId, principalContext).orElse(null);
        AgenticAuthoringTurnStreamRequest effectiveRequest = withActiveSemanticDecision(request, activeSemanticDecision);
        String requestHash = requestHash(effectiveRequest);

        AiTurnEventService.StreamStartMetadata existing = turnEventService.findStartMetadata(threadId, turnId)
                .orElse(null);
        if (existing != null) {
            validateIdempotentRequest(existing.requestHash(), requestHash);
            UUID observationId = captureObservation(
                    threadRequest,
                    principalContext,
                    existing.streamId(),
                    threadId,
                    turnId,
                    request.userPrompt());
            return new StartResult(startResponse(
                    existing.streamId(),
                    observationId,
                    threadId,
                    turnId,
                    existing.expiresAt(),
                    baseUrl,
                    principalContext), false);
        }

        UUID streamId = UUID.randomUUID();
        UUID observationId = captureObservation(
                threadRequest,
                principalContext,
                streamId,
                threadId,
                turnId,
                request.userPrompt());
        Instant expiresAt = Instant.now().plusSeconds(Math.max(streamExpiresSeconds, 60L));
        boolean capacityReserved = false;
        try {
            reserveCapacityPermit(streamId, principalContext);
            capacityReserved = true;
            turnService.reserveTurnForStreaming(threadId, turnId);
            AiTurnEventService.StreamStartAppendResult startAppend =
                    turnEventService.appendStartEventIfAbsent(principalContext, streamId, threadId, turnId, Map.of(
                    "state", "started",
                    "phase", "context.bundle",
                    "message", "Recebi seu pedido e estou preparando o contexto governado.",
                    "requestHash", requestHash,
                    "activeSemanticDecisionId", activeSemanticDecision == null ? "" : activeSemanticDecision.decisionId(),
                    "expiresAt", expiresAt.toString()));
            AiTurnEventEnvelope startEvent = startAppend.event();
            rememberLatestEvent(startEvent.getStreamId(), startEvent);
            if (!startAppend.appended()) {
                releaseCapacityPermit(streamId);
                capacityReserved = false;
                validateIdempotentRequest(requestHash(startEvent), requestHash);
                UUID existingObservationId = captureObservation(
                        threadRequest,
                        principalContext,
                        startEvent.getStreamId(),
                        startEvent.getThreadId(),
                        startEvent.getTurnId(),
                        request.userPrompt());
                return new StartResult(startResponse(
                        startEvent.getStreamId(),
                        existingObservationId,
                        startEvent.getThreadId(),
                        startEvent.getTurnId(),
                        expiresAt(startEvent),
                        baseUrl,
                        principalContext), false);
            }
            streamStartedAtByStream.put(streamId, Instant.now());
            scheduleProcessingTimeout(principalContext, streamId, threadId, turnId);
            scheduleProcessingProgress(principalContext, streamId, threadId, turnId);
            Future<?> processingTask =
                    executor.submit(() -> process(principalContext, streamId, threadId, turnId, effectiveRequest, baseUrl));
            processingTasks.put(streamId, processingTask);
            if (processingTask.isDone()) {
                processingTasks.remove(streamId, processingTask);
            }
            return new StartResult(startResponse(streamId, observationId, threadId, turnId, expiresAt, baseUrl, principalContext), true);
        } catch (RejectedExecutionException ex) {
            recordCapacityRejection("executor");
            safeAppendCapacityError(
                    principalContext,
                    streamId,
                    threadId,
                    turnId,
                    EXECUTOR_REJECTED_CODE,
                    "Executor de authoring agentico saturado. Tente novamente em instantes.");
            complete(streamId);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    EXECUTOR_REJECTED_CODE,
                    ex);
        } catch (RuntimeException ex) {
            if (capacityReserved) {
                releaseCapacityPermit(streamId);
            }
            throw ex;
        }
    }

    private AgenticAuthoringTurnStreamRequest withActiveSemanticDecision(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision activeSemanticDecision) {
        if (request == null || activeSemanticDecision == null || request.activeSemanticDecision() != null) {
            return request;
        }
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
                activeSemanticDecision,
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    private AgenticAuthoringTurnStreamRequest withCanonicalSessionId(
            AgenticAuthoringTurnStreamRequest request,
            UUID threadId) {
        if (request == null || threadId == null || threadId.toString().equals(request.sessionId())) {
            return request;
        }
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
                threadId.toString(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                request.contextHints(),
                request.componentCapabilities(),
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    private AgenticAuthoringTurnStreamRequest withGroundedRuntimeComponentContext(
            AgenticAuthoringTurnStreamRequest request) {
        if (request == null) {
            return request;
        }
        ObjectNode groundedContext = runtimeComponentGroundingService.ground(
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
        ObjectNode contextHints = contextHintsWithoutClientGroundedRuntimeContext(request.contextHints());
        if (groundedContext != null && !groundedContext.isEmpty()) {
            contextHints.set("groundedRuntimeComponentContext", groundedContext);
        }
        if (contextHints.isEmpty() && request.contextHints() == null) {
            return request;
        }
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
                contextHints,
                request.componentCapabilities(),
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    private ObjectNode contextHintsWithoutClientGroundedRuntimeContext(JsonNode contextHints) {
        ObjectNode sanitized = contextHints != null && contextHints.isObject()
                ? contextHints.deepCopy()
                : objectMapper.createObjectNode();
        sanitized.remove("groundedRuntimeComponentContext");
        return sanitized;
    }

    private AgenticAuthoringTurnStreamRequest withRequestBaseUrl(
            AgenticAuthoringTurnStreamRequest request,
            String baseUrl) {
        if (request == null || baseUrl == null || baseUrl.isBlank()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        if (!contextHints.path("requestBaseUrl").asText("").isBlank()) {
            return request;
        }
        contextHints.put("requestBaseUrl", baseUrl.replaceAll("/+$", ""));
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
                contextHints,
                request.componentCapabilities(),
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    public SseEmitter connect(UUID streamId, String lastEventId, AiPrincipalContext principalContext) {
        AiTurnEventService.ReplayResult replay = turnEventService.replay(streamId, lastEventId, principalContext);
        SseEmitter emitter = new SseEmitter(Math.max(10_000L, emitterTimeoutMs));
        Set<SseEmitter> emitters = emittersByStream.computeIfAbsent(streamId, ignored -> ConcurrentHashMap.newKeySet());
        if (maxEmittersPerStream > 0 && emitters.size() >= maxEmittersPerStream) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "agentic-authoring-stream-emitter-limit-exceeded");
        }
        emitters.add(emitter);
        emitter.onCompletion(() -> unregister(streamId, emitter));
        emitter.onTimeout(() -> unregister(streamId, emitter));
        emitter.onError(error -> unregister(streamId, emitter));
        try {
            replayCursorByStream.computeIfAbsent(streamId, ignored -> new AtomicLong(replay.afterSeq()));
            for (AiTurnEventEnvelope event : replay.events()) {
                send(emitter, event);
                replayCursorByStream.get(streamId).set(Math.max(replayCursorByStream.get(streamId).get(), event.getSeq()));
                rememberLatestEvent(streamId, event);
            }
            if (!isLocalActiveStream(streamId)) {
                ensureReplay(streamId, principalContext);
            }
            ensureHeartbeat(streamId, replay.ownership());
            AiTurnEventEnvelope tail = latestEvent(streamId, true);
            if (tail != null && turnEventService.isTerminalType(tail.getType())) {
                complete(streamId);
            }
            return emitter;
        } catch (RuntimeException ex) {
            unregister(streamId, emitter);
            emitter.completeWithError(ex);
            throw ex;
        }
    }

    public void probe(UUID streamId, AiPrincipalContext principalContext) {
        turnEventService.requireOwnership(streamId, principalContext);
    }

    public AiPatchStreamCancelResponse cancel(UUID streamId, AiPrincipalContext principalContext) {
        AiTurnEventService.StreamOwnership ownership = turnEventService.requireOwnership(streamId, principalContext);
        AiTurnEventEnvelope tail = turnEventService.findLastEvent(streamId).orElse(null);
        if (tail != null && turnEventService.isTerminalType(tail.getType())) {
            return AiPatchStreamCancelResponse.builder()
                    .streamId(streamId)
                    .threadId(ownership.threadId())
                    .turnId(ownership.turnId())
                    .terminalState("completed")
                    .message("Stream already reached terminal state.")
                    .build();
        }
        StreamAppendResult cancelResult = appendAndEmit(principalContext, streamId, ownership.threadId(), ownership.turnId(), "cancelled", Map.of(
                "message", "Agentic authoring stream cancelled.",
                "phase", "cancelled"));
        if (!appendedType(cancelResult, "cancelled")) {
            return AiPatchStreamCancelResponse.builder()
                    .streamId(streamId)
                    .threadId(ownership.threadId())
                    .turnId(ownership.turnId())
                    .terminalState("completed")
                    .message("Stream already reached terminal state.")
                    .build();
        }
        cancelProcessing(streamId, true);
        turnService.cancelTurn(ownership.threadId(), ownership.turnId());
        return AiPatchStreamCancelResponse.builder()
                .streamId(streamId)
                .threadId(ownership.threadId())
                .turnId(ownership.turnId())
                .terminalState("cancelled")
                .message("Stream cancelled.")
                .build();
    }

    private void process(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            AgenticAuthoringTurnStreamRequest request,
            String schemaBaseUrl) {
        try {
            AgenticAuthoringTurnEventSink eventSink = new AgenticAuthoringTurnEventSink() {
                @Override
                public AgenticAuthoringTurnEventAppendResult append(String type, Object payload) {
                    StreamAppendResult result = appendAndEmit(principalContext, streamId, threadId, turnId, type, payload);
                    return new AgenticAuthoringTurnEventAppendResult(
                            result.event() != null ? result.event().getType() : null,
                            result.appended());
                }

                @Override
                public boolean terminalReached() {
                    return AgenticAuthoringTurnStreamService.this.terminalReached(streamId);
                }
            };
            AgenticAuthoringTurnOutcome outcome = turnEngine.execute(request, principalContext, eventSink, schemaBaseUrl);
            if (outcome.completion() == Completion.COMPLETE) {
                turnService.completeTurn(threadId, turnId);
            } else if (outcome.completion() == Completion.EXPIRE) {
                turnService.expireTurn(threadId, turnId);
            }
        } finally {
            complete(streamId);
        }
    }

    private synchronized StreamAppendResult appendAndEmit(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String type,
            Object payload) {
        // This service owns the active producer for a locally started stream and updates the
        // cache after every committed append. Re-reading the persisted tail before every
        // progress event adds one remote database round-trip per UX step. The event store remains
        // the cross-instance authority: appendEvent locks the turn and rejects any append after a
        // terminal marker, while explicit terminal/replay checks still reconcile persisted state.
        AiTurnEventEnvelope lastEvent = latestEvent(streamId, false);
        if (lastEvent == null && !isLocalActiveStream(streamId)) {
            lastEvent = latestEvent(streamId, true);
        }
        if (lastEvent != null && turnEventService.isTerminalType(lastEvent.getType())) {
            terminalByStream.computeIfAbsent(streamId, ignored -> new AtomicBoolean(false)).set(true);
            return new StreamAppendResult(lastEvent, false);
        }
        AtomicBoolean terminal = terminalByStream.computeIfAbsent(streamId, ignored -> new AtomicBoolean(false));
        if (terminal.get()) {
            return new StreamAppendResult(latestEvent(streamId, false), false);
        }
        if (isStaleProcessingProgress(payload, lastEvent)) {
            return new StreamAppendResult(lastEvent, false);
        }
        if (turnEventService.isTerminalType(type) && !terminal.compareAndSet(false, true)) {
            return new StreamAppendResult(latestEvent(streamId, false), false);
        }
        AiTurnEventEnvelope event = turnEventService.appendEvent(principalContext, streamId, threadId, turnId, type, payload);
        rememberLatestEvent(streamId, event);
        replayCursorByStream
                .computeIfAbsent(streamId, ignored -> new AtomicLong(0))
                .updateAndGet(current -> Math.max(current, event.getSeq()));
        emittersByStream.getOrDefault(streamId, Set.of()).forEach(emitter -> send(emitter, event));
        if (turnEventService.isTerminalType(type)) {
            markObservationTerminal(streamId, threadId, turnId, type, payload);
            completeStreamResources(streamId);
        }
        return new StreamAppendResult(event, true);
    }

    private boolean isStaleProcessingProgress(Object payload, AiTurnEventEnvelope latestEvent) {
        JsonNode node = objectMapper.valueToTree(payload);
        JsonNode diagnostics = node.path("diagnostics");
        if (!"backend-processing-progress-watchdog".equals(diagnostics.path("source").asText(""))) {
            return false;
        }
        long observedSeq = diagnostics.path("observedSeq").asLong(-1L);
        String observedEventId = diagnostics.path("observedEventId").asText("");
        if (latestEvent == null) {
            return observedSeq >= 0 || !observedEventId.isBlank();
        }
        if (!observedEventId.isBlank() && latestEvent.getEventId() != null) {
            return !observedEventId.equals(latestEvent.getEventId().toString());
        }
        return observedSeq != latestEvent.getSeq();
    }

    private void scheduleProcessingTimeout(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId) {
        long timeoutSeconds = Math.max(1, processingTimeoutSeconds);
        ScheduledFuture<?> task = scheduler.schedule(() -> {
            try {
                StreamAppendResult terminalResult = appendAndEmit(principalContext, streamId, threadId, turnId, "error", Map.of(
                        "message", "Agentic authoring stream timed out before producing a final response.",
                        "assistantMessage", "Demorei demais para concluir essa resposta. Tente de novo com um pedido um pouco mais direto ou confirme qual fonte de negocio devo usar.",
                        "code", "agentic-authoring-timeout",
                        "phase", "agentic-authoring",
                        "timeoutSeconds", timeoutSeconds));
                if (appendedType(terminalResult, "error")) {
                    cancelProcessing(streamId, true);
                    turnService.expireTurn(threadId, turnId);
                }
            } catch (Exception ex) {
                log.warn("[AgenticAuthoringTurnStreamService] Failed to append timeout event: {}", ex.getMessage());
            } finally {
                completeStreamResources(streamId);
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
        processingTimeoutTasks.put(streamId, task);
    }

    private void scheduleProcessingProgress(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId) {
        long progressSeconds = Math.max(0, processingProgressSeconds);
        if (progressSeconds <= 0) {
            return;
        }
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (terminalReached(streamId)) {
                    complete(streamId);
                    return;
                }
                AiTurnEventEnvelope tail = latestEvent(streamId, true);
                String phase = heartbeatPhase(tail);
                Map<String, Object> diagnostics = new java.util.LinkedHashMap<>();
                diagnostics.put("source", "backend-processing-progress-watchdog");
                diagnostics.put("intervalSeconds", progressSeconds);
                diagnostics.put("elapsedSeconds", streamElapsedSeconds(streamId));
                diagnostics.put("lastEventType", tail == null ? "" : nonBlank(tail.getType(), ""));
                diagnostics.put("lastPhase", phase);
                diagnostics.put("observedEventId", tail == null || tail.getEventId() == null ? "" : tail.getEventId().toString());
                diagnostics.put("observedSeq", tail == null ? -1L : tail.getSeq());
                String message = processingProgressMessage(tail, streamElapsedSeconds(streamId));
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("state", "in_progress");
                payload.put("phase", phase);
                payload.put("message", message);
                payload.put("summary", message);
                payload.put("diagnostics", diagnostics);
                putTechnicalDuplicateDiagnostics(payload, "status", streamId, phase, tail);
                if (!isStillLatestEvent(streamId, tail)) {
                    return;
                }
                appendAndEmit(principalContext, streamId, threadId, turnId, "status", payload);
            } catch (Exception ex) {
                log.debug("[AgenticAuthoringTurnStreamService] Processing progress skipped for stream {}: {}",
                        streamId,
                        ex.getMessage());
            }
        }, progressSeconds, progressSeconds, TimeUnit.SECONDS);
        processingProgressTasks.put(streamId, task);
    }

    private void ensureReplay(UUID streamId, AiPrincipalContext principalContext) {
        if (!replayTasks.containsKey(streamId)
                && maxReplayPollers > 0
                && replayTasks.size() >= maxReplayPollers) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "agentic-authoring-stream-replay-capacity-exceeded");
        }
        replayTasks.computeIfAbsent(streamId, ignored -> scheduler.scheduleAtFixedRate(() -> {
            try {
                AtomicLong cursor = replayCursorByStream.computeIfAbsent(streamId, key -> new AtomicLong(0));
                AiTurnEventService.ReplayResult replay = turnEventService.replayFromSeq(streamId, cursor.get(), principalContext);
                for (AiTurnEventEnvelope event : replay.events()) {
                    emittersByStream.getOrDefault(streamId, Set.of()).forEach(emitter -> send(emitter, event));
                    cursor.set(Math.max(cursor.get(), event.getSeq()));
                    rememberLatestEvent(streamId, event);
                    if (turnEventService.isTerminalType(event.getType())) {
                        complete(streamId);
                    }
                }
            } catch (Exception ex) {
                complete(streamId);
            }
        }, Math.max(1, processingPollSeconds), Math.max(1, processingPollSeconds), TimeUnit.SECONDS));
    }

    private void ensureHeartbeat(UUID streamId, AiTurnEventService.StreamOwnership ownership) {
        if (heartbeatSeconds <= 0 || streamId == null) {
            return;
        }
        heartbeatTasks.computeIfAbsent(streamId, ignored -> scheduler.scheduleAtFixedRate(
                () -> heartbeat(streamId, ownership),
                heartbeatSeconds,
                heartbeatSeconds,
                TimeUnit.SECONDS));
    }

    private void heartbeat(UUID streamId, AiTurnEventService.StreamOwnership ownership) {
        try {
            Set<SseEmitter> emitters = emittersByStream.get(streamId);
            if (emitters == null || emitters.isEmpty()) {
                stopHeartbeat(streamId);
                return;
            }
            AiTurnEventEnvelope tail = latestEvent(streamId, true);
            if (tail != null && turnEventService.isTerminalType(tail.getType())) {
                stopHeartbeat(streamId);
                return;
            }
            emitHeartbeatKeepAlive(streamId, ownership, tail);
        } catch (Exception ex) {
            log.debug("[AgenticAuthoringTurnStreamService] Heartbeat skipped for stream {}: {}",
                    streamId,
                    ex.getMessage());
        }
    }

    private void emitHeartbeatKeepAlive(
            UUID streamId,
            AiTurnEventService.StreamOwnership ownership,
            AiTurnEventEnvelope tail) {
        Set<SseEmitter> emitters = emittersByStream.get(streamId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        String phase = heartbeatPhase(tail);
        payload.put("state", "alive");
        payload.put("phase", phase);
        payload.put("message", heartbeatSummary(tail));
        payload.put("summary", heartbeatSummary(tail));
        payload.put("lastEventType", tail == null ? "" : nonBlank(tail.getType(), ""));
        putTechnicalDuplicateDiagnostics(payload, "heartbeat", streamId, phase, tail);
        AiTurnEventEnvelope heartbeatEnvelope = AiTurnEventEnvelope.builder()
                .eventId(null)
                .streamId(streamId)
                .threadId(ownership != null ? ownership.threadId() : null)
                .turnId(ownership != null ? ownership.turnId() : null)
                .seq(-1L)
                .eventSchemaVersion(eventSchemaVersion)
                .timestamp(Instant.now())
                .type("heartbeat")
                .payload(objectMapper.valueToTree(payload))
                .build();
        List<SseEmitter> failed = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                send(emitter, heartbeatEnvelope);
            } catch (Exception ex) {
                failed.add(emitter);
            }
        }
        for (SseEmitter emitter : failed) {
            unregister(streamId, emitter);
        }
    }

    private String heartbeatPhase(AiTurnEventEnvelope tail) {
        JsonNode payload = tail == null ? null : tail.getPayload();
        String phase = payload == null ? "" : payload.path("phase").asText("");
        if (phase != null && !phase.isBlank()) {
            return phase;
        }
        String type = tail == null ? "" : nonBlank(tail.getType(), "");
        return switch (type) {
            case "intent.resolved" -> "intent.resolve.grounding";
            default -> "agentic-authoring";
        };
    }

    private void putTechnicalDuplicateDiagnostics(
            Map<String, Object> payload,
            String eventType,
            UUID streamId,
            String phase,
            AiTurnEventEnvelope tail) {
        Map<String, Object> diagnostics = technicalDuplicateStreamEventDiagnostics(eventType, streamId, phase, tail);
        if (!diagnostics.isEmpty()) {
            payload.put("streamEventDiagnostics", diagnostics);
        }
    }

    private Map<String, Object> technicalDuplicateStreamEventDiagnostics(
            String eventType,
            UUID streamId,
            String phase,
            AiTurnEventEnvelope tail) {
        JsonNode tailDiagnostics = tail == null || tail.getPayload() == null
                ? null
                : tail.getPayload().path("streamEventDiagnostics");
        if (tailDiagnostics == null || !tailDiagnostics.isObject()) {
            return Map.of();
        }
        String dedupeKey = tailDiagnostics.path("dedupeKey").asText("");
        if (dedupeKey == null || dedupeKey.isBlank()) {
            return Map.of();
        }
        Map<String, Object> diagnostics = new java.util.LinkedHashMap<>();
        diagnostics.put("schemaVersion", "praxis-authoring-stream-event-diagnostics.v1");
        diagnostics.put("dedupeKey", dedupeKey);
        diagnostics.put("eventUniquenessKey", safeDiagnosticText(eventType) + ":"
                + (streamId == null ? "" : streamId) + ":"
                + safeDiagnosticText(phase) + ":"
                + Instant.now().toEpochMilli());
        diagnostics.put("technicalDuplicate", true);
        diagnostics.put("technicalDuplicateOf", dedupeKey);
        diagnostics.put("replaySafe", true);
        diagnostics.put("duplicatesDoNotIndicateExecution", true);
        return diagnostics;
    }

    private String safeDiagnosticText(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private String heartbeatSummary(AiTurnEventEnvelope tail) {
        JsonNode payload = tail == null ? null : tail.getPayload();
        String explicitMessage = payload == null ? "" : payload.path("message").asText("");
        if (explicitMessage != null && !explicitMessage.isBlank()) {
            return explicitMessage;
        }
        String label = payload == null ? "" : payload.path("label").asText("");
        if (label != null && !label.isBlank()) {
            return label;
        }
        String phase = heartbeatPhase(tail);
        return switch (phase) {
            case "consultative.intent" ->
                    "Estou separando pergunta consultiva de pedido de criacao para seguir pelo caminho certo.";
            case "consultative.answer" ->
                    "Encontrei evidencias governadas e estou transformando isso em uma resposta clara.";
            case "intent.resolve" ->
                    "Estou organizando o pedido, a pagina atual e as restricoes governadas.";
            case "intent.resolve.evidence" ->
                    "Estou avaliando os candidatos governados recuperados antes de materializar.";
            case "intent.resolve.llm" ->
                    "A LLM esta revisando a intencao com a evidencia governada recuperada.";
            case "intent.resolve.grounding" ->
                    "Estou validando a intencao com as evidencias disponiveis.";
            case "tool.plan" ->
                    "A LLM esta planejando quais ferramentas de leitura consultar.";
            case "tool.plan.skipped" ->
                    "O planejamento de ferramentas foi ignorado com diagnostico registrado.";
            case "resource.discovery", "tool.start", "tool.result", "tool.error" ->
                    "Estou consultando recursos, schemas e capacidades do backend.";
            case "authoringEvidence.retrieve" ->
                    "Estou buscando evidencias de componentes para planejar a pre-visualizacao.";
            case "authoringEvidence.result" ->
                    "As evidencias de componentes foram recuperadas para a pre-visualizacao.";
            case "authoringEvidence.skipped" ->
                    "Nao ha componente selecionado para buscar evidencia granular de autoria; vou seguir com as evidencias do recurso governado.";
            case "component.capabilities" ->
                    "Estou carregando capacidades governadas dos componentes para materializar a tela corretamente.";
            case "projectKnowledge.retrieve" ->
                    "Estou buscando conhecimento governado do projeto.";
            case "projectKnowledge.result" ->
                    "A busca de conhecimento governado do projeto foi concluida.";
            case "preview.plan" ->
                    "Estou planejando a materializacao governada da tela.";
            case "preview.compile" ->
                    "Estou preparando a pre-visualizacao governada.";
            case "tool.loop" ->
                    "Estou concluindo a validacao governada da pre-visualizacao.";
            default ->
                    "Ainda estou processando sua solicitacao.";
        };
    }

    private String processingProgressMessage(AiTurnEventEnvelope tail, long elapsedSeconds) {
        String phase = heartbeatPhase(tail);
        String elapsed = elapsedSeconds >= 20
                ? " Ja se passaram cerca de " + elapsedSeconds + " segundos; continuo trabalhando nisso."
                : "";
        return switch (phase) {
            case "context.bundle" ->
                    "Recebi seu pedido e estou montando o contexto governado para decidir o proximo passo." + elapsed;
            case "runtime.context.grounding" ->
                    "Estou conferindo o estado atual da pagina para evitar alterar o componente errado." + elapsed;
            case "tool.plan" ->
                    "A LLM esta planejando quais buscas governadas fazem sentido antes de escolher uma fonte." + elapsed;
            case "tool.plan.skipped" ->
                    "O planejamento de ferramentas foi concluido com diagnostico registrado; estou seguindo pelo caminho seguro." + elapsed;
            case "tool.start", "resource.discovery" ->
                    "Estou consultando o catalogo governado e procurando recursos compativeis com sua intencao." + elapsed;
            case "tool.result" ->
                    "Ja recuperei candidatos governados e estou usando essas evidencias para decidir a melhor fonte." + elapsed;
            case "intent.resolve" ->
                    "Estou organizando sua intencao em uma decisao canonica antes de montar a tela." + elapsed;
            case "intent.resolve.evidence" ->
                    "Estou avaliando os candidatos governados recuperados para decidir a fonte correta." + elapsed;
            case "intent.resolve.llm" ->
                    "A LLM esta revisando a intencao com as evidencias governadas; essa etapa pode levar alguns segundos." + elapsed;
            case "intent.resolve.grounding" ->
                    "Estou validando a decisao semantica contra os dados confirmados do backend." + elapsed;
            case "preview.plan" ->
                    "Estou planejando como materializar a decisao em uma pre-visualizacao revisavel." + elapsed;
            case "preview.compile" ->
                    "Estou compilando a pre-visualizacao e conferindo campos, componentes e fonte de dados." + elapsed;
            case "authoringEvidence.skipped" ->
                    "Nao ha componente selecionado para buscar evidencia granular de autoria; estou seguindo com as evidencias do recurso governado." + elapsed;
            case "component.capabilities" ->
                    "Estou carregando capacidades governadas dos componentes para decidir como materializar a tela." + elapsed;
            case "repair.attempt" ->
                    "Encontrei algo para ajustar e estou tentando reparar a pre-visualizacao com contexto governado." + elapsed;
            case "consultative.intent", "consultative.answer", "consultative.post-intent.probe" ->
                    "Estou separando orientacao de criacao de tela para responder sem perder o contexto governado." + elapsed;
            default ->
                    "Ainda estou processando sua solicitacao com as evidencias disponiveis." + elapsed;
        };
    }

    private void send(SseEmitter emitter, AiTurnEventEnvelope event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.getEventId() != null ? event.getEventId().toString() : null)
                    .name(event.getType())
                    .data(event));
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    private void unregister(UUID streamId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByStream.get(streamId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByStream.remove(streamId);
            }
        }
    }

    private void complete(UUID streamId) {
        cancelProcessing(streamId, false);
        completeStreamResources(streamId);
        clearTerminalState(streamId);
    }

    private void completeStreamResources(UUID streamId) {
        ScheduledFuture<?> processingTimeoutTask = processingTimeoutTasks.remove(streamId);
        if (processingTimeoutTask != null) {
            processingTimeoutTask.cancel(false);
        }
        ScheduledFuture<?> processingProgressTask = processingProgressTasks.remove(streamId);
        if (processingProgressTask != null) {
            processingProgressTask.cancel(false);
        }
        stopHeartbeat(streamId);
        ScheduledFuture<?> replayTask = replayTasks.remove(streamId);
        if (replayTask != null) {
            replayTask.cancel(false);
        }
        Set<SseEmitter> emitters = emittersByStream.remove(streamId);
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
        replayCursorByStream.remove(streamId);
        streamStartedAtByStream.remove(streamId);
        releaseCapacityPermit(streamId);
        scheduleTerminalStateCleanup(streamId);
    }

    private void scheduleTerminalStateCleanup(UUID streamId) {
        if (streamId == null || !terminalByStream.containsKey(streamId)) {
            return;
        }
        if (scheduler.isShutdown()) {
            clearTerminalState(streamId);
            return;
        }
        try {
            terminalCleanupTasks.computeIfAbsent(streamId, ignored -> scheduler.schedule(
                    () -> clearTerminalState(streamId),
                    TERMINAL_TOMBSTONE_SECONDS,
                    TimeUnit.SECONDS));
        } catch (RejectedExecutionException ex) {
            clearTerminalState(streamId);
        }
    }

    private void clearTerminalState(UUID streamId) {
        ScheduledFuture<?> cleanupTask = terminalCleanupTasks.remove(streamId);
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
        }
        terminalByStream.remove(streamId);
        latestEventByStream.remove(streamId);
    }

    private void cancelProcessing(UUID streamId, boolean mayInterruptIfRunning) {
        Future<?> processingTask = processingTasks.remove(streamId);
        if (processingTask != null && !processingTask.isDone()) {
            processingTask.cancel(mayInterruptIfRunning);
        }
    }

    private Instant expiresAt(AiTurnEventEnvelope startEvent) {
        Instant timestamp = startEvent != null && startEvent.getTimestamp() != null
                ? startEvent.getTimestamp()
                : Instant.now();
        return timestamp.plusSeconds(Math.max(streamExpiresSeconds, 60L));
    }

    private long streamElapsedSeconds(UUID streamId) {
        Instant startedAt = streamStartedAtByStream.get(streamId);
        if (startedAt == null) {
            return 0L;
        }
        return Math.max(0L, Instant.now().getEpochSecond() - startedAt.getEpochSecond());
    }

    private void stopHeartbeat(UUID streamId) {
        ScheduledFuture<?> heartbeatTask = heartbeatTasks.remove(streamId);
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
    }

    private boolean appendedType(StreamAppendResult result, String type) {
        return result != null
                && result.appended()
                && result.event() != null
                && type.equalsIgnoreCase(result.event().getType());
    }

    private boolean terminalReached(UUID streamId) {
        AiTurnEventEnvelope latestEvent = latestEvent(streamId, true);
        return latestEvent != null && turnEventService.isTerminalType(latestEvent.getType());
    }

    private AiTurnEventEnvelope latestEvent(UUID streamId) {
        return latestEvent(streamId, false);
    }

    private AiTurnEventEnvelope latestEvent(UUID streamId, boolean reconcilePersisted) {
        if (streamId == null) {
            return null;
        }
        AiTurnEventEnvelope cached = latestEventByStream.get(streamId);
        if (!reconcilePersisted) {
            return cached;
        }
        AiTurnEventEnvelope persisted = turnEventService.findLastEvent(streamId).orElse(null);
        rememberLatestEvent(streamId, persisted);
        AiTurnEventEnvelope reconciled = latestEventByStream.get(streamId);
        return reconciled != null ? reconciled : persisted;
    }

    private boolean isStillLatestEvent(UUID streamId, AiTurnEventEnvelope observedEvent) {
        AiTurnEventEnvelope latestEvent = latestEvent(streamId, true);
        if (observedEvent == null) {
            return latestEvent == null;
        }
        if (latestEvent == null) {
            return false;
        }
        if (observedEvent.getEventId() != null && latestEvent.getEventId() != null) {
            return observedEvent.getEventId().equals(latestEvent.getEventId());
        }
        return observedEvent.getSeq() == latestEvent.getSeq()
                && java.util.Objects.equals(observedEvent.getType(), latestEvent.getType())
                && java.util.Objects.equals(observedEvent.getTimestamp(), latestEvent.getTimestamp());
    }

    private void rememberLatestEvent(UUID streamId, AiTurnEventEnvelope event) {
        if (streamId == null || event == null) {
            return;
        }
        latestEventByStream.merge(streamId, event, (current, candidate) -> {
            long currentSeq = current.getSeq();
            long candidateSeq = candidate.getSeq();
            return candidateSeq >= currentSeq ? candidate : current;
        });
    }

    private boolean isLocalActiveStream(UUID streamId) {
        return streamId != null && streamStartedAtByStream.containsKey(streamId);
    }

    private UUID captureObservation(
            AiOrchestratorRequest threadRequest,
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String prompt) {
        if (assistantObservationService == null) {
            return null;
        }
        return assistantObservationService.captureStream(
                threadRequest,
                principalContext,
                AiAssistantObservationService.SURFACE_AGENTIC_AUTHORING_STREAM,
                streamId,
                threadId,
                turnId,
                prompt);
    }

    private void markObservationTerminal(
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String eventType,
            Object payload) {
        if (assistantObservationService == null) {
            return;
        }
        UUID observationId = assistantObservationService.findObservationId(threadId, turnId, streamId).orElse(null);
        if (observationId == null) {
            return;
        }
        JsonNode payloadNode = objectMapper.valueToTree(payload);
        String terminal = "cancelled".equalsIgnoreCase(eventType) ? "cancelled"
                : "error".equalsIgnoreCase(eventType) ? "error"
                : "result";
        assistantObservationService.markTerminal(
                observationId,
                terminal,
                payloadNode.path("code").isTextual() ? payloadNode.path("code").asText() : null,
                payloadNode.path("message").isTextual() ? payloadNode.path("message").asText() : null);
    }

    private record StreamAppendResult(AiTurnEventEnvelope event, boolean appended) {
    }

    private AgenticAuthoringTurnStreamStartResponse startResponse(
            UUID streamId,
            UUID observationId,
            UUID threadId,
            UUID turnId,
            Instant expiresAt,
            String baseUrl,
            AiPrincipalContext principalContext) {
        return AgenticAuthoringTurnStreamStartResponse.builder()
                .streamId(streamId)
                .observationId(observationId)
                .threadId(threadId)
                .turnId(turnId)
                .eventSchemaVersion(eventSchemaVersion)
                .streamAuthMode(streamAccessTokenService.resolveAuthMode())
                .streamAccessToken(streamAccessTokenService.issueToken(streamId, principalContext, expiresAt))
                .expiresAt(expiresAt)
                .fallbackAuthoringUrl(baseUrl + "/api/praxis/config/ai/authoring/page-preview")
                .build();
    }

    @PreDestroy
    void shutdown() {
        heartbeatTasks.values().forEach(task -> task.cancel(true));
        heartbeatTasks.clear();
        terminalCleanupTasks.values().forEach(task -> task.cancel(true));
        terminalCleanupTasks.clear();
        executor.shutdownNow();
        scheduler.shutdownNow();
    }

    private void validate(AgenticAuthoringTurnStreamRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request is required.");
        }
        if (request.userPrompt() == null || request.userPrompt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prompt vazio.");
        }
        if (request.clientTurnId() == null || request.clientTurnId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientTurnId is required for stream start.");
        }
    }

    private void validateIdempotentRequest(String storedHash, String incomingHash) {
        if (storedHash == null || storedHash.isBlank() || incomingHash == null || incomingHash.isBlank()) {
            return;
        }
        if (storedHash.equals(incomingHash)) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                IDEMPOTENCY_CONFLICT_REASON);
    }

    private void reserveCapacityPermit(UUID streamId, AiPrincipalContext principalContext) {
        if (streamId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "streamId is required.");
        }
        if (principalContext == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Identity context is required.");
        }
        String tenantKey = normalize(principalContext.tenantId());
        String userKey = normalize(principalContext.userId());
        if (tenantKey == null || userKey == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Identity context is required.");
        }
        String tenantUserKey = tenantUserKey(tenantKey, userKey);
        synchronized (capacityLock) {
            if (capacityOwnersByStream.containsKey(streamId)) {
                return;
            }
            if (maxActiveGlobal > 0 && capacityOwnersByStream.size() >= maxActiveGlobal) {
                recordCapacityRejection("global");
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, CAPACITY_REJECTED_CODE);
            }
            if (maxActivePerTenant > 0 && currentCount(tenantActiveCounts, tenantKey) >= maxActivePerTenant) {
                recordCapacityRejection("tenant");
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, CAPACITY_REJECTED_CODE);
            }
            if (maxActivePerUser > 0 && currentCount(tenantUserActiveCounts, tenantUserKey) >= maxActivePerUser) {
                recordCapacityRejection("user");
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, CAPACITY_REJECTED_CODE);
            }
            if (executor instanceof ThreadPoolExecutor pool
                    && pool.getQueue().remainingCapacity() <= 0
                    && pool.getActiveCount() >= pool.getMaximumPoolSize()) {
                recordCapacityRejection("executor");
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, EXECUTOR_REJECTED_CODE);
            }
            capacityOwnersByStream.put(streamId, new CapacityOwner(tenantKey, tenantUserKey));
            incrementCounter(tenantActiveCounts, tenantKey);
            incrementCounter(tenantUserActiveCounts, tenantUserKey);
        }
    }

    private void releaseCapacityPermit(UUID streamId) {
        if (streamId == null) {
            return;
        }
        synchronized (capacityLock) {
            CapacityOwner owner = capacityOwnersByStream.remove(streamId);
            if (owner == null) {
                return;
            }
            decrementCounter(tenantActiveCounts, owner.tenantKey());
            decrementCounter(tenantUserActiveCounts, owner.tenantUserKey());
        }
    }

    private int currentCount(Map<String, AtomicInteger> counters, String key) {
        if (counters == null || key == null) {
            return 0;
        }
        AtomicInteger value = counters.get(key);
        return value != null ? Math.max(0, value.get()) : 0;
    }

    private void incrementCounter(Map<String, AtomicInteger> counters, String key) {
        if (counters == null || key == null) {
            return;
        }
        counters.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private void decrementCounter(Map<String, AtomicInteger> counters, String key) {
        if (counters == null || key == null) {
            return;
        }
        counters.computeIfPresent(key, (ignored, current) -> current.decrementAndGet() <= 0 ? null : current);
    }

    private String tenantUserKey(String tenantKey, String userKey) {
        return tenantKey + ":" + userKey;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void recordCapacityRejection(String reason) {
        Metrics.counter(
                "praxis_ai_authoring_stream_capacity_rejected_total",
                "reason",
                safeMetricTag(reason)).increment();
    }

    private String safeMetricTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return sanitized.isBlank() ? "unknown" : sanitized;
    }

    private void safeAppendCapacityError(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String code,
            String message) {
        try {
            appendAndEmit(principalContext, streamId, threadId, turnId, "error", Map.of(
                    "code", code,
                    "message", message,
                    "assistantMessage", "A capacidade de processamento esta cheia agora. Tente novamente em instantes.",
                    "phase", "capacity.rejected",
                    "retryable", true));
        } catch (Exception ex) {
            log.debug("[AgenticAuthoringTurnStreamService] Failed to append capacity error event: {}", ex.getMessage());
        }
    }

    private String requestHash(AiTurnEventEnvelope startEvent) {
        if (startEvent == null || startEvent.getPayload() == null) {
            return null;
        }
        JsonNode requestHash = startEvent.getPayload().get("requestHash");
        if (requestHash == null || requestHash.isNull()) {
            return null;
        }
        String value = requestHash.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String requestHash(AgenticAuthoringTurnStreamRequest request) {
        try {
            ObjectNode fingerprint = objectMapper.createObjectNode();
            fingerprint.put("schemaVersion", REQUEST_FINGERPRINT_SCHEMA_VERSION);
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
            putIfPresent(fingerprint, "contextHints", contextHintsForFingerprint(request.contextHints()));
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
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to hash authoring turn request.", ex);
        }
    }

    private JsonNode contextHintsForFingerprint(JsonNode contextHints) {
        if (contextHints == null || !contextHints.isObject()) {
            return contextHints;
        }
        ObjectNode copy = contextHints.deepCopy();
        copy.remove("requestBaseUrl");
        if (copy.isEmpty()) {
            return null;
        }
        return copy;
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

    private ExecutorService createExecutor() {
        AtomicInteger threadIndex = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("agentic-authoring-stream-worker-" + threadIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                WORKER_CORE_POOL_SIZE,
                WORKER_MAX_POOL_SIZE,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(WORKER_QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private ScheduledExecutorService createScheduler() {
        AtomicInteger threadIndex = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("agentic-authoring-stream-scheduler-" + threadIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(SCHEDULER_POOL_SIZE, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private UUID stableUuid(String namespace, String value) {
        return UUID.nameUUIDFromBytes((namespace + ":" + nonBlank(value, "")).getBytes(StandardCharsets.UTF_8));
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    public record StartResult(AgenticAuthoringTurnStreamStartResponse response, boolean created) {
    }

    private record CapacityOwner(String tenantKey, String tenantUserKey) {
    }
}
