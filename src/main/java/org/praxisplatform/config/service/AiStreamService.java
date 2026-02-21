package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.domain.AiTurnStatus;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.dto.AiPatchStreamStartResponse;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiStreamService {

    private static final int STREAM_WORKER_CORE_POOL_SIZE = 4;
    private static final int STREAM_WORKER_MAX_POOL_SIZE = 16;
    private static final int STREAM_WORKER_QUEUE_CAPACITY = 500;
    /**
     * Canonical idempotency hash fields: stable public contract only.
     * Internal/transient execution flags must never participate in this digest.
     */
    private static final List<String> IDEMPOTENCY_HASH_FIELDS = List.of(
            "componentId",
            "componentType",
            "userPrompt",
            "sessionId",
            "mode",
            "clientTurnId",
            "messages",
            "summary",
            "uiContextRef",
            "currentStateDigest",
            "currentState",
            "dataProfile",
            "schemaFields",
            "runtimeState",
            "suggestedPatch",
            "contextHints",
            "aiMode",
            "requireSchema",
            "resourcePath",
            "contractVersion",
            "schemaHash",
            "schemaContext",
            "variantId",
            "apiMethod",
            "apiTags",
            "apiSearchLimit");

    private final AiThreadService threadService;
    private final AiTurnService turnService;
    private final AiOrchestratorService orchestratorService;
    private final AiTurnEventService turnEventService;
    private final ObjectMapper objectMapper;
    private final AiSensitiveDataRedactor sensitiveDataRedactor;
    private final AiStreamAccessTokenService streamAccessTokenService;

    private final Map<UUID, Set<SseEmitter>> emittersByStream = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> replayTasks = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> cancelSignals = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> replayCursorByStream = new ConcurrentHashMap<>();
    private final Set<UUID> activeProcessingStreams = ConcurrentHashMap.newKeySet();
    private final Map<String, ReentrantLock> startLocks = new ConcurrentHashMap<>();
    private final Map<UUID, CapacityOwner> capacityOwnersByStream = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> tenantActiveCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> tenantUserActiveCounts = new ConcurrentHashMap<>();
    private final Object capacityLock = new Object();

    private final ExecutorService streamExecutor = createStreamExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @Value("${praxis.ai.stream.event-schema-version:v1}")
    private String eventSchemaVersion;

    @Value("${praxis.ai.stream.expires-seconds:900}")
    private long streamExpiresSeconds;

    @Value("${praxis.ai.stream.emitter-timeout-ms:300000}")
    private long emitterTimeoutMs;

    @Value("${praxis.ai.stream.heartbeat-seconds:15}")
    private long heartbeatSeconds;

    @Value("${praxis.ai.stream.processing-poll-seconds:1}")
    private long processingPollSeconds;

    @Value("${praxis.ai.stream.processing-max-polls:30}")
    private int processingMaxPolls;

    @Value("${praxis.ai.stream.max-active-global:200}")
    private int maxActiveGlobal;

    @Value("${praxis.ai.stream.max-active-per-tenant:50}")
    private int maxActivePerTenant;

    @Value("${praxis.ai.stream.max-active-per-user:10}")
    private int maxActivePerUser;

    public StreamStartResult startStream(
            AiOrchestratorRequest request,
            String baseUrl,
            AiPrincipalContext principalContext) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request is required.");
        }
        String prompt = resolveUserPrompt(request);
        if (prompt == null || prompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prompt vazio.");
        }
        request.setUserPrompt(prompt);
        request.setStreamTransport(Boolean.FALSE);
        request.setStreamTurnPreclaimed(Boolean.FALSE);

        AiThread thread = threadService.resolveThread(
                request,
                principalContext.tenantId(),
                principalContext.userId(),
                principalContext.environment(),
                prompt);
        UUID threadId = thread.getThreadId();
        if (threadId == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Thread id was not resolved.");
        }

        UUID turnId = request.getClientTurnId();
        if (turnId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientTurnId is required for stream start.");
        }
        request.setSessionId(threadId);
        String requestHash = requestHash(request);
        String startKey = threadId + ":" + turnId;
        ReentrantLock lock = startLocks.computeIfAbsent(startKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
                AiTurnEventService.StreamStartMetadata existingMetadata = turnEventService.findStartMetadata(threadId, turnId)
                        .orElse(null);
                if (existingMetadata != null) {
                    validateIdempotentRequest(existingMetadata.requestHash(), requestHash);
                    turnEventService.requireOwnership(existingMetadata.streamId(), principalContext);
                    AiTurnEventEnvelope tail = turnEventService.findLastEvent(existingMetadata.streamId()).orElse(null);
                    if (tail == null || !turnEventService.isTerminalType(tail.getType())) {
                        boolean isLocallyProcessing = activeProcessingStreams.contains(existingMetadata.streamId())
                                || hasReservedCapacity(existingMetadata.streamId());
                        if (!isLocallyProcessing) {
                            boolean capacityReservedForResume = false;
                            try {
                                reserveCapacityPermit(existingMetadata.streamId(), principalContext);
                                capacityReservedForResume = true;
                                AiTurnService.TurnDecision resumeDecision = turnService.beginTurn(
                                        existingMetadata.threadId(),
                                        existingMetadata.turnId());
                                if (resumeDecision == AiTurnService.TurnDecision.PROCESS) {
                                    AiOrchestratorRequest resumedRequest = copyRequest(request);
                                    resumedRequest.setSessionId(existingMetadata.threadId());
                                    resumedRequest.setClientTurnId(existingMetadata.turnId());
                                    resumedRequest.setStreamTransport(Boolean.TRUE);
                                    resumedRequest.setStreamTurnPreclaimed(Boolean.TRUE);
                                    startProcessing(
                                            existingMetadata.streamId(),
                                            resumedRequest,
                                            principalContext,
                                            baseUrl,
                                            AiTurnService.TurnDecision.PROCESS,
                                            true);
                                    capacityReservedForResume = false;
                                } else if (resumeDecision == AiTurnService.TurnDecision.DONE) {
                                    appendLegacyTerminalReconciliation(principalContext, existingMetadata, tail);
                                }
                            } finally {
                                if (capacityReservedForResume) {
                                    releaseCapacityPermit(existingMetadata.streamId());
                                }
                            }
                        }
                    }
                    AiPatchStreamStartResponse response = AiPatchStreamStartResponse.builder()
                            .streamId(existingMetadata.streamId())
                        .threadId(existingMetadata.threadId())
                        .turnId(existingMetadata.turnId())
                        .eventSchemaVersion(eventSchemaVersion)
                        .streamAuthMode(streamAccessTokenService.resolveAuthMode())
                        .streamAccessToken(streamAccessTokenService.issueToken(
                                existingMetadata.streamId(),
                                principalContext,
                                existingMetadata.expiresAt()))
                        .expiresAt(existingMetadata.expiresAt())
                        .fallbackPatchUrl(baseUrl + "/api/praxis/config/ai/patch")
                        .build();
                return new StreamStartResult(response, false);
            }

            UUID streamId = UUID.randomUUID();
            reserveCapacityPermit(streamId, principalContext);
            Instant expiresAt = Instant.now().plusSeconds(Math.max(streamExpiresSeconds, 60L));
            try {
                turnService.reserveTurnForStreaming(threadId, turnId);
                appendAndEmit(
                        principalContext,
                        streamId,
                        threadId,
                        turnId,
                        "status",
                        Map.of(
                                "state", "started",
                                "message", "Stream iniciado.",
                                "requestHash", requestHash,
                                "expiresAt", expiresAt.toString()));
                emitThoughtStep(
                        principalContext,
                        streamId,
                        threadId,
                        turnId,
                        1,
                        "request",
                        "Pedido recebido",
                        "done",
                        "Pedido validado e stream iniciado.");
            } catch (Exception ex) {
                releaseCapacityPermit(streamId);
                throw ex;
            }

            AiOrchestratorRequest streamRequest = copyRequest(request);
            streamRequest.setStreamTransport(Boolean.TRUE);
            streamRequest.setStreamTurnPreclaimed(Boolean.FALSE);
            startProcessing(
                    streamId,
                    streamRequest,
                    principalContext,
                    baseUrl,
                    AiTurnService.TurnDecision.PROCESS,
                    true);
            AiPatchStreamStartResponse response = AiPatchStreamStartResponse.builder()
                    .streamId(streamId)
                    .threadId(threadId)
                    .turnId(turnId)
                    .eventSchemaVersion(eventSchemaVersion)
                    .streamAuthMode(streamAccessTokenService.resolveAuthMode())
                    .streamAccessToken(streamAccessTokenService.issueToken(streamId, principalContext, expiresAt))
                    .expiresAt(expiresAt)
                    .fallbackPatchUrl(baseUrl + "/api/praxis/config/ai/patch")
                    .build();
            return new StreamStartResult(response, true);
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                startLocks.remove(startKey, lock);
            }
        }
    }

    public SseEmitter connect(
            UUID streamId,
            String lastEventId,
            String accessToken,
            AiPrincipalContext principalContext) {
        AiPrincipalContext effectivePrincipal = streamAccessTokenService.resolvePrincipalContext(
                streamId,
                accessToken,
                principalContext);
        AiTurnEventService.ReplayResult replayResult = turnEventService.replay(streamId, lastEventId, effectivePrincipal);
        SseEmitter emitter = new SseEmitter(Math.max(10_000L, emitterTimeoutMs));
        registerEmitter(streamId, emitter);
        ensureHeartbeat(streamId, replayResult.ownership());
        advanceReplayCursor(streamId, replayResult.afterSeq());

        for (AiTurnEventEnvelope replayEvent : replayResult.events()) {
            sendToEmitter(emitter, replayEvent);
            advanceReplayCursor(streamId, replayEvent.getSeq());
        }
        ensureIncrementalReplay(streamId, replayResult.ownership());
        AiTurnEventEnvelope tail = !replayResult.events().isEmpty()
                ? replayResult.events().get(replayResult.events().size() - 1)
                : turnEventService.findLastEvent(streamId).orElse(null);
        if (tail != null && turnEventService.isTerminalType(tail.getType())) {
            stopHeartbeat(streamId);
            stopIncrementalReplay(streamId);
            emitter.complete();
            unregisterEmitter(streamId, emitter);
        }
        return emitter;
    }

    public void probeStream(UUID streamId, String accessToken, AiPrincipalContext principalContext) {
        AiPrincipalContext effectivePrincipal = streamAccessTokenService.resolvePrincipalContext(
                streamId,
                accessToken,
                principalContext);
        turnEventService.requireOwnership(streamId, effectivePrincipal);
    }

    public AiPatchStreamCancelResponse cancelStream(
            UUID streamId,
            String accessToken,
            AiPrincipalContext principalContext) {
        AiPrincipalContext effectivePrincipal = streamAccessTokenService.resolvePrincipalContext(
                streamId,
                accessToken,
                principalContext);
        AiTurnEventService.StreamOwnership ownership = turnEventService.requireOwnership(streamId, effectivePrincipal);
        AiTurnEventEnvelope last = turnEventService.findLastEvent(streamId).orElse(null);
        if (last != null && turnEventService.isTerminalType(last.getType())) {
            reconcileTurnState(ownership.threadId(), ownership.turnId(), last.getType());
            return AiPatchStreamCancelResponse.builder()
                    .streamId(streamId)
                    .threadId(ownership.threadId())
                    .turnId(ownership.turnId())
                    .terminalState(resolveTerminalState(last))
                    .message("Turn already reached terminal state.")
                    .build();
        }

        cancelSignals.computeIfAbsent(streamId, ignored -> new AtomicBoolean(false)).set(true);
        AiPatchStreamCancelResponse response;
        try {
            appendAndEmit(
                    new AiPrincipalContext(
                            ownership.tenantId(),
                            ownership.userId(),
                            ownership.environment(),
                            true),
                    streamId,
                    ownership.threadId(),
                    ownership.turnId(),
                    "cancelled",
                    Map.of("state", "cancelled", "message", "Turno cancelado."));
            response = AiPatchStreamCancelResponse.builder()
                    .streamId(streamId)
                    .threadId(ownership.threadId())
                    .turnId(ownership.turnId())
                    .terminalState("cancelled")
                    .message("Turn cancelled.")
                    .build();
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == null || ex.getStatusCode().value() != HttpStatus.CONFLICT.value()) {
                throw ex;
            }
            AiTurnEventEnvelope terminal = turnEventService.findLastEvent(streamId)
                    .filter(evt -> turnEventService.isTerminalType(evt.getType()))
                    .orElse(null);
            if (terminal != null) {
                reconcileTurnState(ownership.threadId(), ownership.turnId(), terminal.getType());
                response = AiPatchStreamCancelResponse.builder()
                        .streamId(streamId)
                        .threadId(ownership.threadId())
                        .turnId(ownership.turnId())
                        .terminalState(resolveTerminalState(terminal))
                        .message("Turn already reached terminal state.")
                        .build();
            } else {
                throw ex;
            }
        }
        if (!activeProcessingStreams.contains(streamId)) {
            cancelSignals.remove(streamId);
        }
        return response;
    }

    private void startProcessing(
            UUID streamId,
            AiOrchestratorRequest request,
            AiPrincipalContext principalContext,
            String baseUrl,
            AiTurnService.TurnDecision decision,
            boolean capacityReserved) {
        if (!capacityReserved) {
            reserveCapacityPermit(streamId, principalContext);
            capacityReserved = true;
        }
        if (!activeProcessingStreams.add(streamId)) {
            if (capacityReserved) {
                releaseCapacityPermit(streamId);
            }
            return;
        }
        cancelSignals.computeIfAbsent(streamId, ignored -> new AtomicBoolean(false));
        try {
            streamExecutor.execute(() -> processTurn(streamId, request, principalContext, baseUrl, decision));
        } catch (RejectedExecutionException rejectedExecutionException) {
            activeProcessingStreams.remove(streamId);
            cancelSignals.remove(streamId);
            releaseCapacityPermit(streamId);
            log.warn("[AiStreamService] Stream executor saturated. streamId={}", streamId);
            safeAppendCapacityError(streamId, request, principalContext);
        }
    }

    private void processTurn(
            UUID streamId,
            AiOrchestratorRequest request,
            AiPrincipalContext principalContext,
            String baseUrl,
            AiTurnService.TurnDecision decision) {
        UUID threadId = request.getSessionId();
        UUID turnId = request.getClientTurnId();
        try {
            if (decision != AiTurnService.TurnDecision.IN_PROGRESS) {
                appendAndEmit(
                        principalContext,
                        streamId,
                        threadId,
                        turnId,
                        "status",
                        Map.of(
                                "state", "in_progress",
                                "phase", "analysis",
                                "message", "Turno em processamento."));
            }
            emitThoughtStep(
                    principalContext,
                    streamId,
                    threadId,
                    turnId,
                    2,
                    "proposal",
                    "Montando proposta",
                    "active",
                    "Gerando proposta com base no contexto.");

            AiOrchestratorResponse response = null;
            int polls = 0;
            do {
                if (isCancellationRequested(streamId)) {
                    return;
                }
                response = orchestratorService.generatePatch(
                        copyRequest(request),
                        baseUrl,
                        principalContext.tenantId(),
                        principalContext.userId(),
                        principalContext.environment());
                if (!isInProgressResponse(response)) {
                    break;
                }
                appendAndEmit(
                        principalContext,
                        streamId,
                        threadId,
                        turnId,
                        "status",
                        Map.of(
                                "state", "in_progress",
                                "phase", "analysis",
                                "message", response.getMessage() != null ? response.getMessage() : "Turno em processamento."));
                polls++;
                sleepBeforeNextPoll();
            } while (polls < Math.max(processingMaxPolls, 1));

            if (isCancellationRequested(streamId)) {
                return;
            }
            if (response == null) {
                appendAndEmit(
                        principalContext,
                        streamId,
                        threadId,
                        turnId,
                        "error",
                        Map.of("message", "Resposta vazia da IA."));
                return;
            }
            if ("error".equalsIgnoreCase(response.getType())) {
                appendAndEmit(
                        principalContext,
                        streamId,
                        threadId,
                        turnId,
                        "error",
                        Map.of("message", safeMessage(response.getMessage(), "Falha ao gerar configuracao.")));
                return;
            }
            if (isInProgressResponse(response)) {
                appendAndEmit(
                        principalContext,
                        streamId,
                        threadId,
                        turnId,
                        "error",
                    Map.of("message", "Tempo limite aguardando finalizacao do turno."));
                return;
            }
            emitThoughtStepForResponse(principalContext, streamId, threadId, turnId, response);
            appendAndEmit(
                    principalContext,
                    streamId,
                    threadId,
                    turnId,
                    "result",
                    Map.of("response", response));
        } catch (Exception ex) {
            if (isCancellationRequested(streamId)) {
                return;
            }
            log.warn("[AiStreamService] Stream processing failed: {}", safeMessage(ex.getMessage(), "processing failure"));
            appendAndEmit(
                    principalContext,
                    streamId,
                    threadId,
                    turnId,
                    "error",
                    Map.of("message", safeMessage(ex.getMessage(), "Falha no processamento do stream.")));
        } finally {
            activeProcessingStreams.remove(streamId);
            cancelSignals.remove(streamId);
            releaseCapacityPermit(streamId);
        }
    }

    private void ensureHeartbeat(UUID streamId, AiTurnEventService.StreamOwnership ownership) {
        if (heartbeatSeconds <= 0) {
            return;
        }
        heartbeatTasks.computeIfAbsent(streamId, key -> scheduler.scheduleAtFixedRate(
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
            AiTurnEventEnvelope tail = turnEventService.findLastEvent(streamId).orElse(null);
            if (tail != null && turnEventService.isTerminalType(tail.getType())) {
                stopHeartbeat(streamId);
                return;
            }
            emitHeartbeatKeepAlive(streamId, ownership);
        } catch (Exception ex) {
            log.debug("[AiStreamService] Heartbeat skipped for stream {}: {}", streamId, ex.getMessage());
        }
    }

    private void ensureIncrementalReplay(UUID streamId, AiTurnEventService.StreamOwnership ownership) {
        if (processingPollSeconds <= 0 || streamId == null || ownership == null) {
            return;
        }
        long pollSeconds = Math.max(processingPollSeconds, 1L);
        replayTasks.computeIfAbsent(streamId, key -> scheduler.scheduleAtFixedRate(
                () -> replayIncremental(streamId, ownership),
                pollSeconds,
                pollSeconds,
                TimeUnit.SECONDS));
    }

    private void replayIncremental(UUID streamId, AiTurnEventService.StreamOwnership ownership) {
        try {
            Set<SseEmitter> emitters = emittersByStream.get(streamId);
            if (emitters == null || emitters.isEmpty()) {
                stopIncrementalReplay(streamId);
                return;
            }
            if (activeProcessingStreams.contains(streamId)) {
                return;
            }
            long afterSeq = currentReplayCursor(streamId);
            AiTurnEventService.ReplayResult replay = turnEventService.replayFromSeq(
                    streamId,
                    afterSeq,
                    principalFromOwnership(ownership));
            if (!replay.events().isEmpty()) {
                for (AiTurnEventEnvelope envelope : replay.events()) {
                    emitToActiveEmitters(streamId, envelope);
                }
                AiTurnEventEnvelope tail = replay.events().get(replay.events().size() - 1);
                if (tail != null && turnEventService.isTerminalType(tail.getType())) {
                    stopHeartbeat(streamId);
                    stopIncrementalReplay(streamId);
                    completeStream(streamId);
                }
                return;
            }
            AiTurnEventEnvelope latest = turnEventService.findLastEvent(streamId).orElse(null);
            if (latest != null
                    && turnEventService.isTerminalType(latest.getType())
                    && latest.getSeq() <= currentReplayCursor(streamId)) {
                stopHeartbeat(streamId);
                stopIncrementalReplay(streamId);
                completeStream(streamId);
            }
        } catch (ResponseStatusException ex) {
            HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
            if (HttpStatus.GONE.equals(status)
                    || HttpStatus.NOT_FOUND.equals(status)
                    || HttpStatus.FORBIDDEN.equals(status)) {
                stopHeartbeat(streamId);
                stopIncrementalReplay(streamId);
                completeStream(streamId);
                return;
            }
            log.debug("[AiStreamService] Incremental replay skipped for stream {}: {}", streamId, ex.getMessage());
        } catch (Exception ex) {
            log.debug("[AiStreamService] Incremental replay failed for stream {}: {}", streamId, ex.getMessage());
        }
    }

    private AiPrincipalContext principalFromOwnership(AiTurnEventService.StreamOwnership ownership) {
        return new AiPrincipalContext(
                ownership.tenantId(),
                ownership.userId(),
                ownership.environment(),
                true);
    }

    private AiTurnEventEnvelope appendAndEmit(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String eventType,
            Object payload) {
        AiTurnEventEnvelope envelope = turnEventService.appendEvent(
                principalContext,
                streamId,
                threadId,
                turnId,
                eventType,
                payload);
        emitToActiveEmitters(streamId, envelope);
        if (turnEventService.isTerminalType(eventType)) {
            reconcileTurnState(threadId, turnId, eventType);
            stopHeartbeat(streamId);
            completeStream(streamId);
        }
        return envelope;
    }

    private void appendLegacyTerminalReconciliation(
            AiPrincipalContext principalContext,
            AiTurnEventService.StreamStartMetadata metadata,
            AiTurnEventEnvelope tail) {
        AiTurnStatus turnStatus = turnService.findTurnStatus(metadata.threadId(), metadata.turnId());
        String reconcileType = turnStatus == AiTurnStatus.CANCELLED ? "cancelled" : "error";
        String tailType = normalize(tail != null ? tail.getType() : null);
        Map<String, Object> payload = "cancelled".equalsIgnoreCase(reconcileType)
                ? Map.of(
                        "state", "cancelled",
                        "message", "Legacy cancelled turn reconciled without terminal stream event.",
                        "reason", "legacy_orphan_tail",
                        "tailType", tailType != null ? tailType : "none")
                : Map.of(
                        "message", "Turn finalized without terminal stream event. Reconciled automatically.",
                        "reason", "legacy_orphan_tail",
                        "tailType", tailType != null ? tailType : "none",
                        "turnStatus", turnStatus != null ? turnStatus.name() : "UNKNOWN");
        try {
            appendAndEmit(
                    principalContext,
                    metadata.streamId(),
                    metadata.threadId(),
                    metadata.turnId(),
                    reconcileType,
                    payload);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == null || ex.getStatusCode().value() != HttpStatus.CONFLICT.value()) {
                throw ex;
            }
        }
    }

    private void emitThoughtStepForResponse(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            AiOrchestratorResponse response) {
        if (response == null) {
            return;
        }
        String responseType = normalize(response.getType());
        if ("clarification".equalsIgnoreCase(responseType)) {
            emitThoughtStep(
                    principalContext,
                    streamId,
                    threadId,
                    turnId,
                    2,
                    "proposal",
                    "Aguardando complementos",
                    "active",
                    safeMessage(response.getMessage(), "Necessário complementar informações para seguir."));
            return;
        }
        String detail = normalize(response.getExplanation());
        if (detail == null) {
            detail = normalize(response.getMessage());
        }
        emitThoughtStep(
                principalContext,
                streamId,
                threadId,
                turnId,
                3,
                "impact",
                "Impacto consolidado",
                "done",
                detail != null ? detail : "Resumo e impacto preparados para revisão.");
    }

    private void emitThoughtStep(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            int step,
            String phase,
            String title,
            String state,
            String detail) {
        appendAndEmit(
                principalContext,
                streamId,
                threadId,
                turnId,
                "thought.step",
                Map.of(
                        "step", Math.max(step, 1),
                        "phase", phase != null ? phase : "",
                        "title", title != null ? title : "Etapa",
                        "state", state != null ? state : "active",
                        "detail", detail != null ? detail : ""));
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
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Global stream capacity exceeded.");
            }
            if (maxActivePerTenant > 0 && currentCount(tenantActiveCounts, tenantKey) >= maxActivePerTenant) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Tenant stream capacity exceeded.");
            }
            if (maxActivePerUser > 0 && currentCount(tenantUserActiveCounts, tenantUserKey) >= maxActivePerUser) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "User stream capacity exceeded.");
            }
            if (streamExecutor instanceof ThreadPoolExecutor executor) {
                if (executor.getQueue().remainingCapacity() <= 0
                        && executor.getActiveCount() >= executor.getMaximumPoolSize()) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stream executor saturated.");
                }
            }
            capacityOwnersByStream.put(streamId, new CapacityOwner(tenantKey, tenantUserKey));
            incrementCounter(tenantActiveCounts, tenantKey);
            incrementCounter(tenantUserActiveCounts, tenantUserKey);
        }
    }

    private boolean hasReservedCapacity(UUID streamId) {
        if (streamId == null) {
            return false;
        }
        synchronized (capacityLock) {
            return capacityOwnersByStream.containsKey(streamId);
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
        counters.computeIfAbsent(key, ignored -> new AtomicInteger(0)).incrementAndGet();
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

    private void safeAppendCapacityError(
            UUID streamId,
            AiOrchestratorRequest request,
            AiPrincipalContext principalContext) {
        if (streamId == null || request == null || principalContext == null) {
            return;
        }
        UUID threadId = request.getSessionId();
        UUID turnId = request.getClientTurnId();
        if (threadId == null || turnId == null) {
            return;
        }
        try {
            appendAndEmit(
                    principalContext,
                    streamId,
                    threadId,
                    turnId,
                    "error",
                    Map.of("message", "Capacidade do stream excedida. Tente novamente."));
        } catch (Exception ex) {
            log.debug("[AiStreamService] Failed to append capacity error event: {}", ex.getMessage());
        }
    }

    private void reconcileTurnState(UUID threadId, UUID turnId, String terminalType) {
        if (threadId == null || turnId == null) {
            return;
        }
        if ("cancelled".equalsIgnoreCase(terminalType)) {
            turnService.cancelTurn(threadId, turnId);
            return;
        }
        turnService.completeTurn(threadId, turnId);
    }

    private void emitHeartbeatKeepAlive(UUID streamId, AiTurnEventService.StreamOwnership ownership) {
        Set<SseEmitter> emitters = emittersByStream.get(streamId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        AiTurnEventEnvelope heartbeatEnvelope = AiTurnEventEnvelope.builder()
                .eventId(null)
                .streamId(streamId)
                .threadId(ownership != null ? ownership.threadId() : null)
                .turnId(ownership != null ? ownership.turnId() : null)
                .seq(-1L)
                .eventSchemaVersion(eventSchemaVersion)
                .timestamp(Instant.now())
                .type("heartbeat")
                .payload(objectMapper.valueToTree(Map.of("state", "alive")))
                .build();
        List<SseEmitter> failed = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                sendToEmitter(emitter, heartbeatEnvelope);
            } catch (Exception ex) {
                failed.add(emitter);
            }
        }
        for (SseEmitter emitter : failed) {
            unregisterEmitter(streamId, emitter);
        }
    }

    private void emitToActiveEmitters(UUID streamId, AiTurnEventEnvelope envelope) {
        if (envelope != null) {
            advanceReplayCursor(streamId, envelope.getSeq());
        }
        Set<SseEmitter> emitters = emittersByStream.get(streamId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        List<SseEmitter> failed = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                sendToEmitter(emitter, envelope);
            } catch (RuntimeException ex) {
                failed.add(emitter);
            }
        }
        for (SseEmitter emitter : failed) {
            unregisterEmitter(streamId, emitter);
        }
    }

    private void sendToEmitter(SseEmitter emitter, AiTurnEventEnvelope envelope) {
        try {
            SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event();
            if (envelope != null && envelope.getEventId() != null) {
                eventBuilder.id(envelope.getEventId().toString());
            }
            eventBuilder.data(envelope, MediaType.APPLICATION_JSON);
            emitter.send(eventBuilder);
        } catch (Exception ex) {
            throw new RuntimeException("Emitter send failed", ex);
        }
    }

    private void registerEmitter(UUID streamId, SseEmitter emitter) {
        emittersByStream.computeIfAbsent(streamId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> unregisterEmitter(streamId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            unregisterEmitter(streamId, emitter);
        });
        emitter.onError(ex -> unregisterEmitter(streamId, emitter));
    }

    private void unregisterEmitter(UUID streamId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByStream.get(streamId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByStream.remove(streamId);
            stopHeartbeat(streamId);
            stopIncrementalReplay(streamId);
            replayCursorByStream.remove(streamId);
        }
    }

    private void completeStream(UUID streamId) {
        stopHeartbeat(streamId);
        stopIncrementalReplay(streamId);
        Set<SseEmitter> emitters = emittersByStream.remove(streamId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // no-op
                }
            }
        }
        replayCursorByStream.remove(streamId);
    }

    private void stopHeartbeat(UUID streamId) {
        ScheduledFuture<?> heartbeat = heartbeatTasks.remove(streamId);
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }

    private void stopIncrementalReplay(UUID streamId) {
        ScheduledFuture<?> replayTask = replayTasks.remove(streamId);
        if (replayTask != null) {
            replayTask.cancel(false);
        }
    }

    private long currentReplayCursor(UUID streamId) {
        AtomicLong cursor = replayCursorByStream.computeIfAbsent(streamId, ignored -> new AtomicLong(0L));
        return Math.max(cursor.get(), 0L);
    }

    private void advanceReplayCursor(UUID streamId, long seq) {
        if (streamId == null || seq <= 0) {
            return;
        }
        replayCursorByStream
                .computeIfAbsent(streamId, ignored -> new AtomicLong(0L))
                .updateAndGet(current -> Math.max(current, seq));
    }

    private boolean isCancellationRequested(UUID streamId) {
        AtomicBoolean signal = cancelSignals.get(streamId);
        return signal != null && signal.get();
    }

    private boolean isInProgressResponse(AiOrchestratorResponse response) {
        if (response == null) {
            return false;
        }
        if (!"info".equalsIgnoreCase(response.getType())) {
            return false;
        }
        String message = response.getMessage() != null
                ? response.getMessage()
                : response.getExplanation();
        return message != null && message.toLowerCase().contains("processamento");
    }

    private void sleepBeforeNextPoll() {
        long delay = Math.max(processingPollSeconds, 1L);
        try {
            TimeUnit.SECONDS.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void validateIdempotentRequest(String storedHash, String incomingHash) {
        if (storedHash == null || incomingHash == null) {
            return;
        }
        if (!storedHash.equals(incomingHash)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "clientTurnId already exists with a different payload.");
        }
    }

    private String resolveTerminalState(AiTurnEventEnvelope terminalEvent) {
        if (terminalEvent == null) {
            return "completed";
        }
        return "cancelled".equalsIgnoreCase(terminalEvent.getType()) ? "cancelled" : "completed";
    }

    private String resolveUserPrompt(AiOrchestratorRequest request) {
        if (request == null) {
            return null;
        }
        String directPrompt = normalize(request.getUserPrompt());
        if (directPrompt != null) {
            return directPrompt;
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return null;
        }
        for (int idx = request.getMessages().size() - 1; idx >= 0; idx--) {
            var message = request.getMessages().get(idx);
            if (message == null || !"user".equalsIgnoreCase(message.getRole())) {
                continue;
            }
            String content = normalize(message.getContent());
            if (content != null) {
                return content;
            }
        }
        return null;
    }

    private String requestHash(AiOrchestratorRequest request) {
        try {
            JsonNode rawNode = objectMapper.valueToTree(request);
            ObjectNode canonicalNode = objectMapper.createObjectNode();
            if (rawNode instanceof ObjectNode rawObjectNode) {
                for (String field : IDEMPOTENCY_HASH_FIELDS) {
                    JsonNode value = rawObjectNode.get(field);
                    if (value != null) {
                        canonicalNode.set(field, value);
                    }
                }
            }
            byte[] raw = objectMapper.writeValueAsBytes(canonicalNode);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to hash request.", ex);
        }
    }

    private AiOrchestratorRequest copyRequest(AiOrchestratorRequest request) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(request);
            return objectMapper.readValue(payload, AiOrchestratorRequest.class);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to copy request.", ex);
        }
    }

    private String safeMessage(String message, String fallback) {
        String cleaned = normalize(message);
        if (cleaned == null) {
            return fallback;
        }
        String redacted = sensitiveDataRedactor.redactText(cleaned);
        return normalize(redacted) != null ? redacted : fallback;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ExecutorService createStreamExecutor() {
        AtomicInteger threadIndex = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("ai-stream-worker-" + threadIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                STREAM_WORKER_CORE_POOL_SIZE,
                STREAM_WORKER_MAX_POOL_SIZE,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(STREAM_WORKER_QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        for (ScheduledFuture<?> heartbeat : heartbeatTasks.values()) {
            if (heartbeat != null) {
                heartbeat.cancel(true);
            }
        }
        for (ScheduledFuture<?> replayTask : replayTasks.values()) {
            if (replayTask != null) {
                replayTask.cancel(true);
            }
        }
        heartbeatTasks.clear();
        replayTasks.clear();
        scheduler.shutdownNow();
        streamExecutor.shutdownNow();
        emittersByStream.clear();
        cancelSignals.clear();
        activeProcessingStreams.clear();
        replayCursorByStream.clear();
        capacityOwnersByStream.clear();
        tenantActiveCounts.clear();
        tenantUserActiveCounts.clear();
    }

    public record StreamStartResult(AiPatchStreamStartResponse response, boolean created) {
    }

    private record CapacityOwner(String tenantKey, String tenantUserKey) {
    }
}
