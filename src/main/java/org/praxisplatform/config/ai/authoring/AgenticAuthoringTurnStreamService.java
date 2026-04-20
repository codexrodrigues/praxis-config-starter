package org.praxisplatform.config.ai.authoring;

import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Map<UUID, ScheduledFuture<?>> processingTimeoutTasks = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> terminalByStream = new ConcurrentHashMap<>();
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

    @Value("${praxis.ai.stream.processing-timeout-seconds:45}")
    private long processingTimeoutSeconds;

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
        scheduleProcessingTimeout(principalContext, streamId, threadId, turnId);
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
            if (terminalReached(streamId)) {
                return;
            }
            emitIntentResolutionProgress(principalContext, streamId, threadId, turnId, intentResolution);
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
            StreamAppendResult terminalResult = appendAndEmit(principalContext, streamId, threadId, turnId, "result", Map.of(
                    "intentResolution", intentResolution,
                    "preview", preview != null ? preview : objectMapper.createObjectNode(),
                    "assistantMessage", safeText(previewAssistantMessage(preview, intentResolution)),
                    "quickReplies", intentResolution.quickReplies() != null ? intentResolution.quickReplies() : List.of(),
                    "canApply", preview != null && preview.valid()));
            if (appendedType(terminalResult, "result")) {
                turnService.completeTurn(threadId, turnId);
            }
        } catch (Exception ex) {
            log.warn("[AgenticAuthoringTurnStreamService] Stream processing failed: {}", ex.getMessage());
            StreamAppendResult terminalResult = appendAndEmit(principalContext, streamId, threadId, turnId, "error", Map.of(
                    "message", ex.getMessage() != null ? ex.getMessage() : "Agentic authoring stream failed.",
                    "assistantMessage", "The assistant could not finish this authoring request. Try again or ask support to review the diagnostics.",
                    "code", "agentic-authoring-processing-failed",
                    "phase", "agentic-authoring"));
            if (appendedType(terminalResult, "error")) {
                turnService.expireTurn(threadId, turnId);
            }
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

    private StreamAppendResult appendAndEmit(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String type,
            Object payload) {
        AiTurnEventEnvelope lastEvent = turnEventService.findLastEvent(streamId).orElse(null);
        if (lastEvent != null && turnEventService.isTerminalType(lastEvent.getType())) {
            return new StreamAppendResult(lastEvent, false);
        }
        AtomicBoolean terminal = terminalByStream.computeIfAbsent(streamId, ignored -> new AtomicBoolean(false));
        if (terminal.get()) {
            return new StreamAppendResult(turnEventService.findLastEvent(streamId).orElse(null), false);
        }
        if (turnEventService.isTerminalType(type) && !terminal.compareAndSet(false, true)) {
            return new StreamAppendResult(turnEventService.findLastEvent(streamId).orElse(null), false);
        }
        AiTurnEventEnvelope event = turnEventService.appendEvent(principalContext, streamId, threadId, turnId, type, payload);
        emittersByStream.getOrDefault(streamId, Set.of()).forEach(emitter -> send(emitter, event));
        if (turnEventService.isTerminalType(type)) {
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
                        "assistantMessage", "The assistant took too long to finish this request. Try again with a narrower request or review the active context.",
                        "code", "agentic-authoring-timeout",
                        "phase", "agentic-authoring",
                        "timeoutSeconds", timeoutSeconds));
                if (appendedType(terminalResult, "error")) {
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
        ScheduledFuture<?> processingTimeoutTask = processingTimeoutTasks.remove(streamId);
        if (processingTimeoutTask != null) {
            processingTimeoutTask.cancel(false);
        }
        ScheduledFuture<?> replayTask = replayTasks.remove(streamId);
        if (replayTask != null) {
            replayTask.cancel(false);
        }
        Set<SseEmitter> emitters = emittersByStream.remove(streamId);
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
        replayCursorByStream.remove(streamId);
        terminalByStream.remove(streamId);
    }

    private boolean appendedType(StreamAppendResult result, String type) {
        return result != null
                && result.appended()
                && result.event() != null
                && type.equalsIgnoreCase(result.event().getType());
    }

    private void emitIntentResolutionProgress(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return;
        }
        if (hasToolDiscoveredCandidates(intentResolution)) {
            appendAndEmit(principalContext, streamId, threadId, turnId, "thought.step", Map.of(
                    "phase", "resource.discovery",
                    "summary", "Resource candidates were retrieved from the backend catalog.",
                    "diagnostics", resourceDiscoveryDiagnostics(intentResolution)));
        } else if (contains(intentResolution.failureCodes(), "resource-candidate-ambiguous")) {
            appendAndEmit(principalContext, streamId, threadId, turnId, "thought.step", Map.of(
                    "phase", "resource.discovery",
                    "summary", "Resource candidates returned for user selection.",
                    "diagnostics", resourceDiscoveryDiagnostics(intentResolution)));
        }
        if (contains(intentResolution.warnings(), "llm-intent-resolution-second-pass-used")) {
            appendAndEmit(principalContext, streamId, threadId, turnId, "thought.step", Map.of(
                    "phase", "intent.resolve",
                    "summary", "Refined resource candidates were reviewed by the LLM.",
                    "diagnostics", secondPassDiagnostics(intentResolution)));
        }
    }

    private Map<String, Object> resourceDiscoveryDiagnostics(AgenticAuthoringIntentResolutionResult intentResolution) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("candidateCount", intentResolution.candidates() != null ? intentResolution.candidates().size() : 0);
        diagnostics.put("operationKind", safeText(intentResolution.operationKind()));
        diagnostics.put("artifactKind", safeText(intentResolution.artifactKind()));
        AgenticAuthoringCandidate selectedCandidate = intentResolution.selectedCandidate();
        if (selectedCandidate != null && selectedCandidate.resourcePath() != null && !selectedCandidate.resourcePath().isBlank()) {
            diagnostics.put("selectedResourcePath", selectedCandidate.resourcePath());
        }
        diagnostics.put("source", hasToolDiscoveredCandidates(intentResolution) ? "backend-resource-catalog" : "intent-resolution");
        return diagnostics;
    }

    private Map<String, Object> secondPassDiagnostics(AgenticAuthoringIntentResolutionResult intentResolution) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("secondPass", true);
        diagnostics.put("candidateCount", intentResolution.candidates() != null ? intentResolution.candidates().size() : 0);
        AgenticAuthoringCandidate selectedCandidate = intentResolution.selectedCandidate();
        if (selectedCandidate != null && selectedCandidate.resourcePath() != null && !selectedCandidate.resourcePath().isBlank()) {
            diagnostics.put("selectedResourcePath", selectedCandidate.resourcePath());
        }
        return diagnostics;
    }

    private boolean hasToolDiscoveredCandidates(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return false;
        }
        if (hasEvidence(intentResolution.selectedCandidate(), "tool-search-api-resources")) {
            return true;
        }
        return intentResolution.candidates() != null
                && intentResolution.candidates().stream()
                .anyMatch(candidate -> hasEvidence(candidate, "tool-search-api-resources"));
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null && contains(candidate.evidence(), evidence);
    }

    private boolean contains(List<String> values, String expected) {
        return values != null && values.stream().anyMatch(expected::equals);
    }

    private String previewAssistantMessage(
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        String message = preview == null ? "" : safeText(preview.assistantMessage());
        return !message.isBlank() ? message : safeText(intentResolution.assistantMessage());
    }

    private boolean terminalReached(UUID streamId) {
        return turnEventService.findLastEvent(streamId)
                .map(event -> turnEventService.isTerminalType(event.getType()))
                .orElse(false);
    }

    private record StreamAppendResult(AiTurnEventEnvelope event, boolean appended) {
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
