package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Map<UUID, Future<?>> processingTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> streamStartedAtByStream = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> terminalByStream = new ConcurrentHashMap<>();
    private final Map<UUID, AiTurnEventEnvelope> latestEventByStream = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

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

    @Value("${praxis.ai.authoring.stream.processing-progress-seconds:20}")
    private long processingProgressSeconds;

    public StartResult start(
            AgenticAuthoringTurnStreamRequest request,
            String baseUrl,
            AiPrincipalContext principalContext) {
        validate(request);
        request = withGroundedRuntimeComponentContext(request);
        request = withRequestBaseUrl(request, baseUrl);
        UUID turnId = stableUuid("agentic-authoring-turn", request.clientTurnId());
        AiOrchestratorRequest threadRequest = AiOrchestratorRequest.builder()
                .componentId(nonBlank(request.targetComponentId(), "praxis-dynamic-page-builder"))
                .componentType("page-builder")
                .userPrompt(request.userPrompt())
                .clientTurnId(turnId)
                .currentState(request.currentPage())
                .contextHints(request.contextHints())
                .mode("new")
                .build();
        AiThread thread = threadService.resolveThread(
                threadRequest,
                principalContext.tenantId(),
                principalContext.userId(),
                principalContext.environment(),
                request.userPrompt());
        UUID threadId = thread.getThreadId();
        AgenticAuthoringSemanticDecision activeSemanticDecision = request.activeSemanticDecision() != null
                ? request.activeSemanticDecision()
                : turnEventService.findLatestSemanticDecision(threadId, principalContext).orElse(null);
        AgenticAuthoringTurnStreamRequest effectiveRequest = withActiveSemanticDecision(request, activeSemanticDecision);

        AiTurnEventService.StreamStartMetadata existing = turnEventService.findStartMetadata(threadId, turnId)
                .orElse(null);
        if (existing != null) {
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
        turnService.reserveTurnForStreaming(threadId, turnId);
        AiTurnEventService.StreamStartAppendResult startAppend =
                turnEventService.appendStartEventIfAbsent(principalContext, streamId, threadId, turnId, Map.of(
                "state", "started",
                "phase", "context.bundle",
                "message", "Agentic authoring stream started.",
                "requestHash", stableUuid("agentic-authoring-request", request.userPrompt() + "|" + request.clientTurnId()).toString(),
                "activeSemanticDecisionId", activeSemanticDecision == null ? "" : activeSemanticDecision.decisionId(),
                "expiresAt", expiresAt.toString()));
        AiTurnEventEnvelope startEvent = startAppend.event();
        rememberLatestEvent(startEvent.getStreamId(), startEvent);
        if (!startAppend.appended()) {
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

    private AgenticAuthoringTurnStreamRequest withGroundedRuntimeComponentContext(
            AgenticAuthoringTurnStreamRequest request) {
        if (request == null
                || request.contextHints() != null
                && request.contextHints().path("groundedRuntimeComponentContext").isObject()) {
            return request;
        }
        ObjectNode groundedContext = runtimeComponentGroundingService.ground(
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
        if (groundedContext == null || groundedContext.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("groundedRuntimeComponentContext", groundedContext);
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
        emittersByStream.computeIfAbsent(streamId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> unregister(streamId, emitter));
        emitter.onTimeout(() -> unregister(streamId, emitter));
        emitter.onError(error -> unregister(streamId, emitter));
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

    private StreamAppendResult appendAndEmit(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String type,
            Object payload) {
        AiTurnEventEnvelope lastEvent = latestEvent(streamId, true);
        if (lastEvent != null && turnEventService.isTerminalType(lastEvent.getType())) {
            terminalByStream.computeIfAbsent(streamId, ignored -> new AtomicBoolean(false)).set(true);
            return new StreamAppendResult(lastEvent, false);
        }
        AtomicBoolean terminal = terminalByStream.computeIfAbsent(streamId, ignored -> new AtomicBoolean(false));
        if (terminal.get()) {
            return new StreamAppendResult(latestEvent(streamId, true), false);
        }
        if (turnEventService.isTerminalType(type) && !terminal.compareAndSet(false, true)) {
            return new StreamAppendResult(latestEvent(streamId, true), false);
        }
        AiTurnEventEnvelope event = turnEventService.appendEvent(principalContext, streamId, threadId, turnId, type, payload);
        rememberLatestEvent(streamId, event);
        replayCursorByStream
                .computeIfAbsent(streamId, ignored -> new AtomicLong(0))
                .updateAndGet(current -> Math.max(current, event.getSeq()));
        emittersByStream.getOrDefault(streamId, Set.of()).forEach(emitter -> send(emitter, event));
        if (turnEventService.isTerminalType(type)) {
            markObservationTerminal(streamId, threadId, turnId, type, payload);
            complete(streamId);
        }
        return new StreamAppendResult(event, true);
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
                complete(streamId);
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
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("state", "in_progress");
                payload.put("phase", phase);
                payload.put("message", heartbeatSummary(tail));
                payload.put("summary", heartbeatSummary(tail));
                payload.put("diagnostics", diagnostics);
                putTechnicalDuplicateDiagnostics(payload, "status", streamId, phase, tail);
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
        return phase == null || phase.isBlank() ? "agentic-authoring" : phase;
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
        String phase = heartbeatPhase(tail);
        return switch (phase) {
            case "consultative.intent" ->
                    "Estou separando pergunta consultiva de pedido de criacao para seguir pelo caminho certo.";
            case "consultative.answer" ->
                    "Encontrei evidencias governadas e estou transformando isso em uma resposta clara.";
            case "intent.resolve" ->
                    "Estou organizando o pedido, a pagina atual e as restricoes governadas.";
            case "intent.resolve.llm" ->
                    "Estou resolvendo a intencao com o contexto governado antes de escolher recursos ou componentes.";
            case "intent.resolve.grounding" ->
                    "Estou validando a intencao com as evidencias disponiveis.";
            case "resource.discovery", "tool.start", "tool.result" ->
                    "Estou consultando recursos, schemas e capacidades do backend.";
            case "projectKnowledge.retrieve" ->
                    "Estou buscando conhecimento governado do projeto.";
            case "preview.plan" ->
                    "Estou planejando a materializacao governada da tela.";
            case "preview.compile" ->
                    "Estou preparando a pre-visualizacao governada.";
            default ->
                    "Ainda estou processando sua solicitacao.";
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
        ScheduledFuture<?> processingTimeoutTask = processingTimeoutTasks.remove(streamId);
        if (processingTimeoutTask != null) {
            processingTimeoutTask.cancel(false);
        }
        ScheduledFuture<?> processingProgressTask = processingProgressTasks.remove(streamId);
        if (processingProgressTask != null) {
            processingProgressTask.cancel(false);
        }
        Future<?> processingTask = processingTasks.remove(streamId);
        if (processingTask != null && !processingTask.isDone()) {
            processingTask.cancel(false);
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
        if (cached != null && !reconcilePersisted) {
            return cached;
        }
        AiTurnEventEnvelope persisted = turnEventService.findLastEvent(streamId).orElse(null);
        rememberLatestEvent(streamId, persisted);
        AiTurnEventEnvelope reconciled = latestEventByStream.get(streamId);
        return reconciled != null ? reconciled : persisted;
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

    private UUID stableUuid(String namespace, String value) {
        return UUID.nameUUIDFromBytes((namespace + ":" + nonBlank(value, "")).getBytes(StandardCharsets.UTF_8));
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    public record StartResult(AgenticAuthoringTurnStreamStartResponse response, boolean created) {
    }
}
