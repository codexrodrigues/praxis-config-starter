package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnEngine.AgenticAuthoringTurnOutcome;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnEngine.Completion;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiAssistantObservationService;
import org.praxisplatform.config.service.AiPrincipalContext;
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
    private static final long PERSISTED_TERMINAL_RECONCILIATION_SECONDS = 5L;
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
    private final AgenticAuthoringTurnStreamRequestNormalizer requestNormalizer =
            new AgenticAuthoringTurnStreamRequestNormalizer(objectMapper, runtimeComponentGroundingService);
    private final AgenticAuthoringStreamProgressMessageCatalog progressMessageCatalog =
            AgenticAuthoringStreamProgressMessageCatalog.INSTANCE;

    @Autowired(required = false)
    private AiAssistantObservationService assistantObservationService;

    private AgenticAuthoringStreamCapacityStore capacityStore =
            new InMemoryAgenticAuthoringStreamCapacityStore();

    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Autowired(required = false)
    public void setCapacityStore(AgenticAuthoringStreamCapacityStore capacityStore) {
        this.capacityStore = capacityStore != null
                ? capacityStore
                : new InMemoryAgenticAuthoringStreamCapacityStore();
    }

    @Autowired(required = false)
    public void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        if (this.meterRegistry == meterRegistry) {
            return;
        }
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            io.micrometer.core.instrument.Gauge.builder(
                            "praxis_ai_authoring_stream_capacity_active_global",
                            this,
                            service -> service.capacityStore.activeGlobalCount())
                    .description("Active Agentic Authoring SSE stream permits across the cluster or node")
                    .register(meterRegistry);
        }
    }

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
    private final Map<UUID, AtomicLong> persistedReconciliationNanosByStream = new ConcurrentHashMap<>();
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
        request = requestNormalizer.withoutClientVerifiedDomainOperations(request);
        validate(request);
        request = requestNormalizer.withGroundedRuntimeComponentContext(request);
        request = requestNormalizer.withRequestBaseUrl(request, baseUrl);
        UUID turnId = stableUuid("agentic-authoring-turn", request.clientTurnId());
        UUID requestedThreadId = requestedThreadId(request);
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
        request = requestNormalizer.withCanonicalSessionId(request, threadId);
        AiTurnEventService.StreamStartMetadata existing = turnEventService.findStartMetadata(threadId, turnId)
                .orElse(null);
        ResolvedActiveSemanticDecision resolvedActiveSemanticDecision = resolveActiveSemanticDecision(
                request,
                threadId,
                principalContext,
                existing);
        request = requestNormalizer.withPersistedResolvedCandidates(
                request,
                resolvedActiveSemanticDecision.issuedCandidateApis());
        AgenticAuthoringSemanticDecision activeSemanticDecision = resolvedActiveSemanticDecision.decision();
        AgenticAuthoringTurnStreamRequest effectiveRequest =
                requestNormalizer.withActiveSemanticDecision(request, activeSemanticDecision);
        String requestHash = requestNormalizer.requestHash(effectiveRequest, principalContext);

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

        // Freshness is checked by appendStartEventIfAbsent under the same database
        // thread lock as result publication. Historical lookup above permits exact replay.
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
                validateIdempotentRequest(requestNormalizer.requestHash(startEvent), requestHash);
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

    private UUID requestedThreadId(AgenticAuthoringTurnStreamRequest request) {
        if (request == null) {
            return null;
        }
        UUID explicitSessionId = parseUuid(request.sessionId());
        if (explicitSessionId != null) {
            return explicitSessionId;
        }
        AgenticAuthoringSemanticDecision activeDecision = request.activeSemanticDecision();
        return activeDecision == null ? null : parseUuid(activeDecision.conversationId());
    }

    private ResolvedActiveSemanticDecision resolveActiveSemanticDecision(
            AgenticAuthoringTurnStreamRequest request,
            UUID threadId,
            AiPrincipalContext principalContext,
            AiTurnEventService.StreamStartMetadata existing) {
        AgenticAuthoringSemanticDecision clientDecision = request == null
                ? null
                : request.activeSemanticDecision();
        if (clientDecision == null && existing == null) {
            return new ResolvedActiveSemanticDecision(
                    turnEventService.findLatestSemanticDecision(threadId, principalContext).orElse(null),
                    objectMapper.createArrayNode());
        }
        // Exact retries reuse the context admitted with this turn, not a newer
        // decision published since then. The initial free turn stores an empty id.
        String decisionId = clientDecision != null ? clientDecision.decisionId() : existing.activeSemanticDecisionId();
        if (clientDecision == null && (decisionId == null || decisionId.isBlank())) {
            return new ResolvedActiveSemanticDecision(null, objectMapper.createArrayNode());
        }
        return turnEventService.findPersistedSemanticDecisionContext(threadId, decisionId, principalContext)
                .map(context -> new ResolvedActiveSemanticDecision(
                        context.decision(),
                        context.issuedCandidateApis()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "active-semantic-decision-not-issued-in-thread"));
    }

    /**
     * Serializes replay registration with live appends. Without the shared monitor used by
     * {@link #appendAndEmit}, an event committed after the replay snapshot but before emitter
     * registration could be absent from both replay and the live stream.
     */
    public synchronized SseEmitter connect(
            UUID streamId,
            String lastEventId,
            AiPrincipalContext principalContext) {
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
            completeAfterProcessing(streamId);
        }
    }

    private synchronized StreamAppendResult appendAndEmit(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String type,
            Object payload) {
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
        long appendStartedAtNanos = System.nanoTime();
        AiTurnEventEnvelope event;
        try {
            event = turnEventService.appendEvent(
                    principalContext,
                    streamId,
                    threadId,
                    turnId,
                    type,
                    payload);
        } catch (RuntimeException failure) {
            if (turnEventService.isTerminalType(type)) terminal.set(false);
            throw failure;
        }
        long appendElapsedNanos = System.nanoTime() - appendStartedAtNanos;
        String appendPhase = objectMapper.valueToTree(payload).path("phase").asText("unspecified");
        Metrics.timer(
                        "praxis.ai.authoring.stream.event.append",
                        "type", safeMetricTag(type),
                        "phase", safeMetricTag(appendPhase))
                .record(appendElapsedNanos, TimeUnit.NANOSECONDS);
        if (appendElapsedNanos >= TimeUnit.MILLISECONDS.toNanos(250L)) {
            log.debug(
                    "Agentic authoring persisted event append was slow (type={}, phase={}, elapsedMs={}).",
                    safeMetricTag(type),
                    safeMetricTag(appendPhase),
                    TimeUnit.NANOSECONDS.toMillis(appendElapsedNanos));
        }
        rememberLatestEvent(streamId, event);
        replayCursorByStream
                .computeIfAbsent(streamId, ignored -> new AtomicLong(0))
                .updateAndGet(current -> Math.max(current, event.getSeq()));
        emittersByStream.getOrDefault(streamId, Set.of()).forEach(emitter -> send(emitter, event));
        if (turnEventService.isTerminalType(type)) {
            markObservationTerminal(streamId, threadId, turnId, type, payload);
            completeStreamResources(streamId);
        } else if (isLocalActiveStream(streamId)) {
            capacityStore.renewLease(streamId);
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
                AiTurnEventEnvelope tail = latestEvent(streamId, false);
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
                progressMessageCatalog.putTechnicalDuplicateDiagnostics(payload, "status", streamId, phase, tail);
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
            capacityStore.renewLease(streamId);
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
        progressMessageCatalog.putTechnicalDuplicateDiagnostics(payload, "heartbeat", streamId, phase, tail);
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
        return progressMessageCatalog.heartbeatPhase(tail);
    }

    private Map<String, Object> technicalDuplicateStreamEventDiagnostics(
            String eventType,
            UUID streamId,
            String phase,
            AiTurnEventEnvelope tail) {
        return progressMessageCatalog.technicalDuplicateStreamEventDiagnostics(eventType, streamId, phase, tail);
    }

    private String heartbeatSummary(AiTurnEventEnvelope tail) {
        return progressMessageCatalog.heartbeatSummary(tail);
    }

    private String processingProgressMessage(AiTurnEventEnvelope tail, long elapsedSeconds) {
        return progressMessageCatalog.processingProgressMessage(tail, elapsedSeconds);
    }

    private void send(SseEmitter emitter, AiTurnEventEnvelope event) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event()
                    .name(event.getType())
                    .data(event);
            if (event.getEventId() != null) {
                builder.id(event.getEventId().toString());
            }
            emitter.send(builder);
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

    private void completeAfterProcessing(UUID streamId) {
        processingTasks.remove(streamId);
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
        boolean wasLocalStream = streamStartedAtByStream.remove(streamId) != null;
        AtomicBoolean terminalState = terminalByStream.get(streamId);
        boolean isTerminal = terminalState != null && terminalState.get();
        if (wasLocalStream || isTerminal) {
            releaseCapacityPermit(streamId);
        }
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
        persistedReconciliationNanosByStream.remove(streamId);
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

    // Share the append monitor: reservation is not persisted and delivered terminal state.
    private synchronized boolean terminalReached(UUID streamId) {
        AtomicBoolean terminal = terminalByStream.computeIfAbsent(streamId, ignored -> new AtomicBoolean(false));
        if (terminal.get()) {
            return true;
        }
        AiTurnEventEnvelope latestEvent = latestEvent(streamId, false);
        if (latestEvent != null && turnEventService.isTerminalType(latestEvent.getType())) {
            terminal.set(true);
            return true;
        }
        latestEvent = latestEventWithPeriodicPersistenceReconciliation(streamId);
        boolean reached = latestEvent != null && turnEventService.isTerminalType(latestEvent.getType());
        if (reached) {
            terminal.set(true);
        }
        return reached;
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
        persistedReconciliationNanosByStream
                .computeIfAbsent(streamId, ignored -> new AtomicLong())
                .set(System.nanoTime());
        rememberLatestEvent(streamId, persisted);
        AiTurnEventEnvelope reconciled = latestEventByStream.get(streamId);
        return reconciled != null ? reconciled : persisted;
    }

    private boolean isStillLatestEvent(UUID streamId, AiTurnEventEnvelope observedEvent) {
        AiTurnEventEnvelope latestEvent = latestEvent(streamId, false);
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

    private AiTurnEventEnvelope latestEventWithPeriodicPersistenceReconciliation(UUID streamId) {
        if (streamId == null) {
            return null;
        }
        AtomicLong reconciledAt = persistedReconciliationNanosByStream
                .computeIfAbsent(streamId, ignored -> new AtomicLong());
        long now = System.nanoTime();
        long previous = reconciledAt.get();
        long interval = TimeUnit.SECONDS.toNanos(PERSISTED_TERMINAL_RECONCILIATION_SECONDS);
        if (previous > 0L && now - previous < interval) {
            return latestEvent(streamId, false);
        }
        if (!reconciledAt.compareAndSet(previous, now)) {
            return latestEvent(streamId, false);
        }
        try {
            AiTurnEventEnvelope persisted = turnEventService.findLastEvent(streamId).orElse(null);
            rememberLatestEvent(streamId, persisted);
            return latestEvent(streamId, false);
        } catch (RuntimeException ex) {
            reconciledAt.compareAndSet(now, previous);
            throw ex;
        }
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
        Set<UUID> localStreams = new java.util.LinkedHashSet<>(streamStartedAtByStream.keySet());
        localStreams.addAll(processingTasks.keySet());
        streamStartedAtByStream.clear();
        processingTasks.values().forEach(task -> task.cancel(true));
        processingTasks.clear();
        replayTasks.values().forEach(task -> task.cancel(true));
        replayTasks.clear();
        heartbeatTasks.values().forEach(task -> task.cancel(true));
        heartbeatTasks.clear();
        processingTimeoutTasks.values().forEach(task -> task.cancel(true));
        processingTimeoutTasks.clear();
        processingProgressTasks.values().forEach(task -> task.cancel(true));
        processingProgressTasks.clear();
        terminalCleanupTasks.values().forEach(task -> task.cancel(true));
        terminalCleanupTasks.clear();
        executor.shutdownNow();
        scheduler.shutdownNow();
        localStreams.forEach(this::releaseCapacityPermit);
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
        AgenticAuthoringStreamCapacityStore.ReservationStatus status = capacityStore.tryReserve(
                streamId,
                tenantKey,
                userKey,
                maxActiveGlobal,
                maxActivePerTenant,
                maxActivePerUser,
                this::isLocalExecutorAvailable);
        switch (status) {
            case RESERVED, ALREADY_RESERVED -> {
                counter(
                        "praxis_ai_authoring_stream_capacity_admitted_total",
                        "status",
                        safeMetricTag(status.name().toLowerCase())).increment();
            }
            case GLOBAL_LIMIT_EXCEEDED -> {
                recordCapacityRejection("global");
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, CAPACITY_REJECTED_CODE);
            }
            case TENANT_LIMIT_EXCEEDED -> {
                recordCapacityRejection("tenant");
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, CAPACITY_REJECTED_CODE);
            }
            case USER_LIMIT_EXCEEDED -> {
                recordCapacityRejection("user");
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, CAPACITY_REJECTED_CODE);
            }
            case EXECUTOR_SATURATED -> {
                recordCapacityRejection("executor");
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, EXECUTOR_REJECTED_CODE);
            }
        }
    }

    private boolean isLocalExecutorAvailable() {
        return !(executor instanceof ThreadPoolExecutor pool
                && pool.getQueue().remainingCapacity() <= 0
                && pool.getActiveCount() >= pool.getMaximumPoolSize());
    }

    private void releaseCapacityPermit(UUID streamId) {
        if (streamId != null) {
            capacityStore.release(streamId);
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void recordCapacityRejection(String reason) {
        counter(
                "praxis_ai_authoring_stream_capacity_rejected_total",
                "reason",
                safeMetricTag(reason)).increment();
    }

    private io.micrometer.core.instrument.Counter counter(String name, String... tags) {
        return meterRegistry != null
                ? meterRegistry.counter(name, tags)
                : Metrics.counter(name, tags);
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

    private record ResolvedActiveSemanticDecision(
            AgenticAuthoringSemanticDecision decision,
            JsonNode issuedCandidateApis) {
    }
}
