package org.praxisplatform.config.ai.authoring;

import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiThreadService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.AiTurnService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgenticAuthoringTurnStreamService {

    private final AgenticAuthoringIntentResolverService intentResolverService;
    private final AgenticAuthoringPreviewService previewService;
    private final AiThreadService threadService;
    private final AiTurnService turnService;
    private final AiTurnEventService turnEventService;
    private final AiStreamAccessTokenService streamAccessTokenService;
    private final ObjectMapper objectMapper;

    private final Map<UUID, Set<SseEmitter>> emittersByStream = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> replayCursorByStream = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> replayTasks = new ConcurrentHashMap<>();
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

    public StartResult start(
            AgenticAuthoringTurnStreamRequest request,
            String baseUrl,
            AiPrincipalContext principalContext) {
        validate(request);
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

        AiTurnEventService.StreamStartMetadata existing = turnEventService.findStartMetadata(threadId, turnId)
                .orElse(null);
        if (existing != null) {
            return new StartResult(startResponse(existing.streamId(), threadId, turnId, existing.expiresAt(), baseUrl, principalContext), false);
        }

        UUID streamId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(Math.max(streamExpiresSeconds, 60L));
        turnService.reserveTurnForStreaming(threadId, turnId);
        appendAndEmit(principalContext, streamId, threadId, turnId, "status", Map.of(
                "state", "started",
                "phase", "context.bundle",
                "message", "Agentic authoring stream started.",
                "requestHash", stableUuid("agentic-authoring-request", request.userPrompt() + "|" + request.clientTurnId()).toString(),
                "expiresAt", expiresAt.toString()));
        executor.submit(() -> process(principalContext, streamId, threadId, turnId, request));
        return new StartResult(startResponse(streamId, threadId, turnId, expiresAt, baseUrl, principalContext), true);
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
        }
        ensureReplay(streamId, principalContext);
        AiTurnEventEnvelope tail = turnEventService.findLastEvent(streamId).orElse(null);
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
        appendAndEmit(principalContext, streamId, ownership.threadId(), ownership.turnId(), "cancelled", Map.of(
                "message", "Agentic authoring stream cancelled.",
                "phase", "cancelled"));
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
            AgenticAuthoringTurnStreamRequest request) {
        try {
            appendAndEmit(principalContext, streamId, threadId, turnId, "thought.step", Map.of(
                    "phase", "context.bundle",
                    "summary", "Authoring context received.",
                    "diagnostics", safeDiagnostics(request)));
            appendAndEmit(principalContext, streamId, threadId, turnId, "thought.step", Map.of(
                    "phase", "intent.resolve",
                    "summary", "Resolving authoring intent."));
            AgenticAuthoringIntentResolutionResult intentResolution = intentResolverService.resolve(
                    toIntentRequest(request),
                    principalContext.tenantId(),
                    principalContext.userId(),
                    principalContext.environment());
            if (intentResolution.failureCodes() != null && intentResolution.failureCodes().contains("resource-candidate-ambiguous")) {
                appendAndEmit(principalContext, streamId, threadId, turnId, "thought.step", Map.of(
                        "phase", "resource.discovery",
                        "summary", "Resource candidates returned for user selection."));
            }
            AgenticAuthoringPreviewResult preview = null;
            if (intentResolution.valid()) {
                appendAndEmit(principalContext, streamId, threadId, turnId, "thought.step", Map.of(
                        "phase", "preview.plan",
                        "summary", "Generating page preview plan."));
                preview = previewService.preview(
                        toPlanRequest(request, intentResolution),
                        principalContext.tenantId(),
                        principalContext.userId(),
                        principalContext.environment());
                appendAndEmit(principalContext, streamId, threadId, turnId, "thought.step", Map.of(
                        "phase", "preview.compile",
                        "summary", "Compiled preview payload."));
            }
            appendAndEmit(principalContext, streamId, threadId, turnId, "result", Map.of(
                    "intentResolution", intentResolution,
                    "preview", preview != null ? preview : objectMapper.createObjectNode(),
                    "assistantMessage", safeText(intentResolution.assistantMessage()),
                    "quickReplies", intentResolution.quickReplies() != null ? intentResolution.quickReplies() : List.of(),
                    "canApply", preview != null && preview.valid()));
            turnService.completeTurn(threadId, turnId);
        } catch (Exception ex) {
            log.warn("[AgenticAuthoringTurnStreamService] Stream processing failed: {}", ex.getMessage());
            appendAndEmit(principalContext, streamId, threadId, turnId, "error", Map.of(
                    "message", ex.getMessage() != null ? ex.getMessage() : "Agentic authoring stream failed.",
                    "phase", "agentic-authoring"));
            turnService.expireTurn(threadId, turnId);
        } finally {
            complete(streamId);
        }
    }

    private AgenticAuthoringIntentResolutionRequest toIntentRequest(AgenticAuthoringTurnStreamRequest request) {
        return new AgenticAuthoringIntentResolutionRequest(
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
                request.contextHints());
    }

    private AgenticAuthoringPlanRequest toPlanRequest(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        return new AgenticAuthoringPlanRequest(
                request.userPrompt(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.currentPage(),
                intentResolution,
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                request.contextHints());
    }

    private AiTurnEventEnvelope appendAndEmit(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String type,
            Object payload) {
        AiTurnEventEnvelope event = turnEventService.appendEvent(principalContext, streamId, threadId, turnId, type, payload);
        emittersByStream.getOrDefault(streamId, Set.of()).forEach(emitter -> send(emitter, event));
        if (turnEventService.isTerminalType(type)) {
            complete(streamId);
        }
        return event;
    }

    private void ensureReplay(UUID streamId, AiPrincipalContext principalContext) {
        replayTasks.computeIfAbsent(streamId, ignored -> scheduler.scheduleAtFixedRate(() -> {
            try {
                AtomicLong cursor = replayCursorByStream.computeIfAbsent(streamId, key -> new AtomicLong(0));
                AiTurnEventService.ReplayResult replay = turnEventService.replayFromSeq(streamId, cursor.get(), principalContext);
                for (AiTurnEventEnvelope event : replay.events()) {
                    emittersByStream.getOrDefault(streamId, Set.of()).forEach(emitter -> send(emitter, event));
                    cursor.set(Math.max(cursor.get(), event.getSeq()));
                    if (turnEventService.isTerminalType(event.getType())) {
                        complete(streamId);
                    }
                }
            } catch (Exception ex) {
                complete(streamId);
            }
        }, Math.max(1, processingPollSeconds), Math.max(1, processingPollSeconds), TimeUnit.SECONDS));
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
        ScheduledFuture<?> replayTask = replayTasks.remove(streamId);
        if (replayTask != null) {
            replayTask.cancel(false);
        }
        Set<SseEmitter> emitters = emittersByStream.remove(streamId);
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
        replayCursorByStream.remove(streamId);
    }

    private AgenticAuthoringTurnStreamStartResponse startResponse(
            UUID streamId,
            UUID threadId,
            UUID turnId,
            Instant expiresAt,
            String baseUrl,
            AiPrincipalContext principalContext) {
        return AgenticAuthoringTurnStreamStartResponse.builder()
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .eventSchemaVersion(eventSchemaVersion)
                .streamAuthMode(streamAccessTokenService.resolveAuthMode())
                .streamAccessToken(streamAccessTokenService.issueToken(streamId, principalContext, expiresAt))
                .expiresAt(expiresAt)
                .fallbackAuthoringUrl(baseUrl + "/api/praxis/config/ai/authoring/turn")
                .build();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        scheduler.shutdownNow();
    }

    private Map<String, Object> safeDiagnostics(AgenticAuthoringTurnStreamRequest request) {
        return Map.of(
                "targetApp", nonBlank(request.targetApp(), ""),
                "targetComponentId", nonBlank(request.targetComponentId(), ""),
                "selectedWidgetKey", nonBlank(request.selectedWidgetKey(), ""),
                "hasContextHints", request.contextHints() != null && !request.contextHints().isNull(),
                "componentCapabilityCatalogs", request.componentCapabilities() != null
                        && request.componentCapabilities().catalogs() != null
                        ? request.componentCapabilities().catalogs().size()
                        : 0);
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

    private String safeText(String value) {
        return value != null ? value : "";
    }

    public record StartResult(AgenticAuthoringTurnStreamStartResponse response, boolean created) {
    }
}
