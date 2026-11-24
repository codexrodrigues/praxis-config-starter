package org.praxisplatform.config.ai.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContextCustomizer;
import org.praxisplatform.config.ai.agent.spi.AgentThreadContextPropagator;
import org.praxisplatform.config.ai.agent.spi.CompositeAgentThreadContextPropagator;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiThinkingProfile;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiThreadService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.AiTurnService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Gerencia inicializacao idempotente, persistencia de eventos, propagacao segura de contexto,
 * heartbeat, replay SSE e cancelamento de turnos do assistente operacional.
 */
@Slf4j
public class OperationalAgentTurnStreamService {

    public record StartResult(AgenticAuthoringTurnStreamStartResponse response, boolean created) {
    }

    private final PraxisOperationalAgentLoop agentLoop;
    private final AiThreadService threadService;
    private final AiTurnService turnService;
    private final AiTurnEventService turnEventService;
    private final AiStreamAccessTokenService streamAccessTokenService;
    private final AiProviderManagementService providerManagementService;
    private final CompositeAgentThreadContextPropagator contextPropagator;
    private final List<AgentExecutionContextCustomizer> contextCustomizers;
    private final ObjectMapper objectMapper;

    private final Map<UUID, Set<SseEmitter>> emittersByStream = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> replayCursorByStream = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> replayTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Future<?>> processingTasks = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> terminalByStream = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> cancelledByStream = new ConcurrentHashMap<>();
    private final Map<UUID, Object> streamLocks = new ConcurrentHashMap<>();
    private final ExecutorService executor = createExecutor();
    private final ScheduledExecutorService scheduler = createScheduler();

    @Value("${praxis.ai.stream.event-schema-version:v1}")
    private String eventSchemaVersion = "v1";

    @Value("${praxis.ai.stream.expires-seconds:900}")
    private long streamExpiresSeconds = 900L;

    @Value("${praxis.ai.stream.emitter-timeout-ms:300000}")
    private long emitterTimeoutMs = 300_000L;

    @Value("${praxis.ai.agent.stream.heartbeat-seconds:5}")
    private long heartbeatSeconds = 5L;

    @Value("${praxis.ai.agent.stream.poll-seconds:1}")
    private long replayPollSeconds = 1L;

    @Value("${praxis.ai.agent.turn-timeout-seconds:120}")
    private long turnTimeoutSeconds = 120L;

    @Value("${praxis.ai.agent.stream.max-emitters-per-stream:4}")
    private int maxEmittersPerStream = 4;

    @Value("${praxis.ai.security.corporate-mode:false}")
    private boolean corporateMode = false;

    public void setCorporateMode(boolean corporateMode) {
        this.corporateMode = corporateMode;
    }

    public OperationalAgentTurnStreamService(
            PraxisOperationalAgentLoop agentLoop,
            AiThreadService threadService,
            AiTurnService turnService,
            AiTurnEventService turnEventService,
            AiStreamAccessTokenService streamAccessTokenService,
            AiProviderManagementService providerManagementService,
            CompositeAgentThreadContextPropagator contextPropagator,
            List<AgentExecutionContextCustomizer> contextCustomizers,
            ObjectMapper objectMapper) {
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop is required");
        this.threadService = Objects.requireNonNull(threadService, "threadService is required");
        this.turnService = Objects.requireNonNull(turnService, "turnService is required");
        this.turnEventService = Objects.requireNonNull(turnEventService, "turnEventService is required");
        this.streamAccessTokenService = Objects.requireNonNull(streamAccessTokenService, "streamAccessTokenService is required");
        this.providerManagementService = providerManagementService;
        this.contextPropagator = contextPropagator != null
                ? contextPropagator
                : new CompositeAgentThreadContextPropagator(List.of());
        this.contextCustomizers = contextCustomizers == null ? List.of() : List.copyOf(contextCustomizers);
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    public StartResult start(
            OperationalAgentTurnStreamRequest request,
            HttpServletRequest servletRequest,
            String baseUrl,
            AiPrincipalContext principalContext) {
        validate(request);
        TurnScopedPiiVault piiVault = TurnScopedPiiVault.tokenize(request.userPrompt());
        String sanitizedPrompt = piiVault.sanitizedPrompt();
        String clientTurnId = StringUtils.hasText(request.clientTurnId())
                ? request.clientTurnId().trim()
                : UUID.randomUUID().toString();
        UUID turnId = stableUuid("operational-agent-turn", clientTurnId);
        UUID requestedThreadId = parseUuid(request.sessionId());

        AiOrchestratorRequest threadRequest = AiOrchestratorRequest.builder()
                .componentId("praxis-operational-agent")
                .componentType("operational-assistant")
                .userPrompt(sanitizedPrompt)
                .sessionId(requestedThreadId)
                .clientTurnId(turnId)
                .mode(requestedThreadId == null ? "new" : "continue")
                .build();
        AiThread thread = threadService.resolveThread(
                threadRequest,
                principalContext.tenantId(),
                principalContext.userId(),
                principalContext.environment(),
                sanitizedPrompt);
        UUID threadId = thread.getThreadId();

        AgentExecutionContext preContext = buildExecutionContext(
                threadId, turnId, null, request, principalContext, servletRequest);
        String requestHash = hashRequest(request, sanitizedPrompt, principalContext, preContext.propagatedClaims());
        AiTurnEventService.StreamStartMetadata existing = turnEventService.findStartMetadata(threadId, turnId)
                .orElse(null);
        if (existing != null) {
            validateIdempotency(existing.requestHash(), requestHash);
            return new StartResult(startResponse(
                    existing.streamId(),
                    threadId,
                    turnId,
                    existing.expiresAt(),
                    principalContext), false);
        }

        UUID streamId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(Math.max(streamExpiresSeconds, 60L));
        turnService.reserveTurnForStreaming(threadId, turnId);
        AiTurnEventService.StreamStartAppendResult startAppend = turnEventService.appendStartEventIfAbsent(
                principalContext,
                streamId,
                threadId,
                turnId,
                Map.of(
                        "state", "started",
                        "phase", "agent.init",
                        "statusText", "Recebi sua solicitação operacional e estou consultando os recursos do domínio.",
                        "requestHash", requestHash,
                        "expiresAt", expiresAt.toString()));
        if (!startAppend.appended()) {
            AiTurnEventEnvelope existingStart = startAppend.event();
            return new StartResult(startResponse(
                    existingStart.getStreamId(),
                    existingStart.getThreadId(),
                    existingStart.getTurnId(),
                    expiresAt,
                    principalContext), false);
        }

        AgentExecutionContext executionContext = new AgentExecutionContext(
                preContext.threadId(),
                preContext.turnId(),
                streamId,
                preContext.tenantId(),
                preContext.userId(),
                preContext.environment(),
                preContext.principalName(),
                preContext.authorities(),
                preContext.activeScreenResourcePath(),
                preContext.activeTopicKey(),
                preContext.activeSelectionHints(),
                preContext.propagatedClaims());
        AiCallConfig callConfig = resolveCallConfig(request, principalContext);
        AgentThreadContextPropagator.Snapshot propagatedSnapshot = contextPropagator.captureAll();

        try {
            Future<?> future = executor.submit(() -> runWorker(
                    principalContext,
                    streamId,
                    threadId,
                    turnId,
                    clientTurnId,
                    executionContext,
                    request,
                    piiVault,
                    callConfig,
                    propagatedSnapshot));
            processingTasks.put(streamId, future);
            if (future.isDone()) {
                processingTasks.remove(streamId, future);
            } else {
                scheduleTurnTimeout(principalContext, streamId, threadId, turnId, future);
            }
            return new StartResult(startResponse(streamId, threadId, turnId, expiresAt, principalContext), true);
        } catch (RejectedExecutionException ex) {
            appendAndEmit(principalContext, streamId, threadId, turnId, "error", Map.of(
                    "code", "operational-agent-executor-saturated",
                    "statusText", "Capacidade do assistente ocupada. Tente novamente em instantes.",
                    "retryable", true));
            completeStream(streamId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "operational-agent-executor-saturated", ex);
        }
    }

    public SseEmitter connect(
            UUID streamId,
            String lastEventId,
            AiPrincipalContext principalContext) {
        synchronized (streamLock(streamId)) {
            AiTurnEventService.ReplayResult replay = turnEventService.replay(streamId, lastEventId, principalContext);
            SseEmitter emitter = new SseEmitter(Math.max(10_000L, emitterTimeoutMs));
            Set<SseEmitter> emitters = emittersByStream.computeIfAbsent(streamId, ignored -> ConcurrentHashMap.newKeySet());
            if (maxEmittersPerStream > 0 && emitters.size() >= maxEmittersPerStream) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "operational-agent-emitter-limit-exceeded");
            }
            emitters.add(emitter);
            emitter.onCompletion(() -> unregister(streamId, emitter));
            emitter.onTimeout(() -> unregister(streamId, emitter));
            emitter.onError(err -> unregister(streamId, emitter));

            try {
                AtomicLong cursor = replayCursorByStream.computeIfAbsent(streamId, ignored -> new AtomicLong(replay.afterSeq()));
                AiTurnEventEnvelope tail = null;
                for (AiTurnEventEnvelope event : replay.events()) {
                    send(emitter, event);
                    cursor.set(Math.max(cursor.get(), event.getSeq()));
                    tail = event;
                }
                ensureHeartbeat(streamId);
                boolean hasTerminalTail = tail != null && turnEventService.isTerminalType(tail.getType());
                if (hasTerminalTail) {
                    terminalByStream.computeIfAbsent(streamId, ignored -> new AtomicBoolean()).set(true);
                    completeStream(streamId);
                } else if (!processingTasks.containsKey(streamId) && !terminalReached(streamId)) {
                    ensureReplayPoller(streamId, principalContext);
                }
                return emitter;
            } catch (RuntimeException ex) {
                unregister(streamId, emitter);
                emitter.completeWithError(ex);
                throw ex;
            }
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
        cancelledByStream.computeIfAbsent(streamId, ignored -> new AtomicBoolean()).set(true);
        appendAndEmit(principalContext, streamId, ownership.threadId(), ownership.turnId(), "cancelled", Map.of(
                "phase", "cancelled",
                "statusText", "Execução operacional cancelada pelo usuário."));
        Future<?> task = processingTasks.remove(streamId);
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
        turnService.cancelTurn(ownership.threadId(), ownership.turnId());
        completeStream(streamId);
        return AiPatchStreamCancelResponse.builder()
                .streamId(streamId)
                .threadId(ownership.threadId())
                .turnId(ownership.turnId())
                .terminalState("cancelled")
                .message("Stream cancelled.")
                .build();
    }

    private void runWorker(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String clientTurnId,
            AgentExecutionContext executionContext,
            OperationalAgentTurnStreamRequest request,
            TurnScopedPiiVault piiVault,
            AiCallConfig callConfig,
            AgentThreadContextPropagator.Snapshot snapshot) {
        try (AutoCloseable ignored = snapshot.attach()) {
            PraxisOperationalAgentLoop.TurnProgressObserver observer = new PraxisOperationalAgentLoop.TurnProgressObserver() {
                @Override
                public void onStatus(String code, String text) {
                    if (!terminalReached(streamId)) {
                        appendAndEmit(principalContext, streamId, threadId, turnId, "progress", Map.of(
                                "phase", code,
                                "statusText", text));
                    }
                }

                @Override
                public void onToolStarted(String toolName, int round) {
                    if (!terminalReached(streamId)) {
                        appendAndEmit(principalContext, streamId, threadId, turnId, "progress", Map.of(
                                "phase", "tool.started",
                                "toolName", toolName,
                                "round", round,
                                "statusText", "Executando ferramenta: " + toolName));
                    }
                }

                @Override
                public void onToolCompleted(String toolName, int round, long durationMs) {
                    if (!terminalReached(streamId)) {
                        appendAndEmit(principalContext, streamId, threadId, turnId, "progress", Map.of(
                                "phase", "tool.completed",
                                "toolName", toolName,
                                "round", round,
                                "durationMs", durationMs));
                    }
                }

                @Override
                public boolean isCancelled() {
                    AtomicBoolean cancelled = cancelledByStream.get(streamId);
                    if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.get())) {
                        return true;
                    }
                    long nowMs = System.currentTimeMillis();
                    long prevMs = lastDbCancelCheckMs.get();
                    if (nowMs - prevMs >= 1_500L && lastDbCancelCheckMs.compareAndSet(prevMs, nowMs)) {
                        try {
                            AiTurnEventEnvelope tail = turnEventService.findLastEvent(streamId).orElse(null);
                            if (tail != null && "cancelled".equalsIgnoreCase(tail.getType())) {
                                cancelledByStream.computeIfAbsent(streamId, ignored -> new AtomicBoolean()).set(true);
                                return true;
                            }
                        } catch (RuntimeException ignored) {
                            // Cross-pod cancel probe failure should not abort an active turn prematurely.
                        }
                    }
                    return false;
                }
                private final AtomicLong lastDbCancelCheckMs = new AtomicLong(0L);
            };

            PraxisOperationalAgentLoop.AgentTurnOutcome outcome = agentLoop.execute(
                    executionContext,
                    request.userPrompt(),
                    piiVault,
                    request.conversationMessages(),
                    callConfig,
                    observer);

            if (observer.isCancelled() || terminalReached(streamId)) {
                return;
            }

            Map<String, Object> resultPayload = new LinkedHashMap<>();
            resultPayload.put("state", "completed");
            resultPayload.put("sessionId", threadId.toString());
            resultPayload.put("clientTurnId", clientTurnId);
            if (StringUtils.hasText(executionContext.activeScreenResourcePath())) {
                resultPayload.put("activeScreenResourcePath", executionContext.activeScreenResourcePath());
            }
            if (StringUtils.hasText(outcome.provider())) {
                resultPayload.put("provider", outcome.provider());
            }
            if (StringUtils.hasText(outcome.model())) {
                resultPayload.put("model", outcome.model());
            }
            resultPayload.put("roundsExecuted", outcome.roundsExecuted());
            resultPayload.put("assistantMessage", outcome.assistantMessage());
            resultPayload.put("assistantContent", outcome.assistantContent());
            resultPayload.put("quickReplies", outcome.quickReplies());
            resultPayload.put("traces", outcome.traces());
            resultPayload.put("inputTokens", outcome.usage().inputTokens());
            resultPayload.put("outputTokens", outcome.usage().outputTokens());
            resultPayload.put("cacheReadInputTokens", outcome.usage().cacheReadInputTokens());
            resultPayload.put("cacheWriteInputTokens", outcome.usage().cacheWriteInputTokens());
            resultPayload.put("thinkingTokens", outcome.usage().thinkingTokens());
            resultPayload.put("cacheHit", outcome.usage().cacheHit());
            resultPayload.put("totalTokens", outcome.usage().totalTokens());
            if (outcome.telemetrySummary() != null) {
                resultPayload.put("telemetryMeta", outcome.telemetrySummary().toSafeEventMeta());
            }

            appendAndEmit(principalContext, streamId, threadId, turnId, "result", resultPayload);
            turnService.completeTurn(threadId, turnId);
            completeStream(streamId);
        } catch (Exception ex) {
            if (!terminalReached(streamId)) {
                log.error("[OperationalAgentTurnStream] Failed executing stream {}: {}", streamId, ex.getMessage(), ex);
                appendAndEmit(principalContext, streamId, threadId, turnId, "error", Map.of(
                        "code", "operational-agent-turn-failed",
                        "statusText", "Não foi possível concluir a consulta operacional no momento. Tente novamente.",
                        "retryable", true));
                turnService.expireTurn(threadId, turnId);
                completeStream(streamId);
            }
        } finally {
            processingTasks.remove(streamId);
        }
    }

    private void appendAndEmit(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            String eventType,
            Object payload) {
        synchronized (streamLock(streamId)) {
            if (terminalReached(streamId)) {
                return;
            }
            AiTurnEventEnvelope envelope = turnEventService.appendEvent(
                    principalContext, streamId, threadId, turnId, eventType, payload);
            if (turnEventService.isTerminalType(eventType)) {
                terminalByStream.computeIfAbsent(streamId, ignored -> new AtomicBoolean()).set(true);
            }
            AtomicLong cursor = replayCursorByStream.computeIfAbsent(streamId, ignored -> new AtomicLong(0L));
            cursor.set(Math.max(cursor.get(), envelope.getSeq()));

            Set<SseEmitter> emitters = emittersByStream.get(streamId);
            if (emitters != null && !emitters.isEmpty()) {
                List<SseEmitter> failed = new ArrayList<>();
                for (SseEmitter emitter : emitters) {
                    try {
                        send(emitter, envelope);
                    } catch (Exception ex) {
                        failed.add(emitter);
                    }
                }
                for (SseEmitter fail : failed) {
                    unregister(streamId, fail);
                }
            }
        }
    }

    private void ensureHeartbeat(UUID streamId) {
        if (heartbeatSeconds <= 0 || scheduler.isShutdown()) {
            return;
        }
        heartbeatTasks.computeIfAbsent(streamId, id -> scheduler.scheduleAtFixedRate(
                () -> emitHeartbeat(id),
                heartbeatSeconds,
                heartbeatSeconds,
                TimeUnit.SECONDS));
    }

    private void ensureReplayPoller(UUID streamId, AiPrincipalContext principalContext) {
        if (scheduler.isShutdown()) {
            return;
        }
        long periodSeconds = Math.max(1L, replayPollSeconds);
        replayTasks.computeIfAbsent(streamId, id -> scheduler.scheduleAtFixedRate(() -> {
            try {
                if (terminalReached(id)) {
                    completeStream(id);
                    return;
                }
                AtomicLong cursor = replayCursorByStream.computeIfAbsent(id, ignored -> new AtomicLong(0L));
                AiTurnEventService.ReplayResult replay = turnEventService.replayFromSeq(
                        id, cursor.get(), principalContext);
                if (replay == null || replay.events() == null || replay.events().isEmpty()) {
                    return;
                }
                synchronized (streamLock(id)) {
                    Set<SseEmitter> emitters = emittersByStream.get(id);
                    for (AiTurnEventEnvelope event : replay.events()) {
                        if (event.getSeq() <= cursor.get()) {
                            continue;
                        }
                        if (emitters != null && !emitters.isEmpty()) {
                            List<SseEmitter> failed = new ArrayList<>();
                            for (SseEmitter emitter : emitters) {
                                try {
                                    send(emitter, event);
                                } catch (Exception ex) {
                                    failed.add(emitter);
                                }
                            }
                            for (SseEmitter fail : failed) {
                                unregister(id, fail);
                            }
                        }
                        cursor.set(Math.max(cursor.get(), event.getSeq()));
                        if (turnEventService.isTerminalType(event.getType())) {
                            terminalByStream.computeIfAbsent(id, ignored -> new AtomicBoolean()).set(true);
                            completeStream(id);
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                completeStream(id);
            }
        }, periodSeconds, periodSeconds, TimeUnit.SECONDS));
    }

    private void scheduleTurnTimeout(
            AiPrincipalContext principalContext,
            UUID streamId,
            UUID threadId,
            UUID turnId,
            Future<?> processingFuture) {
        if (turnTimeoutSeconds <= 0 || scheduler.isShutdown()) {
            return;
        }
        ScheduledFuture<?> task = scheduler.schedule(() -> {
            if (terminalReached(streamId)) {
                return;
            }
            cancelledByStream.computeIfAbsent(streamId, ignored -> new AtomicBoolean()).set(true);
            if (processingFuture != null && !processingFuture.isDone()) {
                processingFuture.cancel(true);
            }
            try {
                appendAndEmit(principalContext, streamId, threadId, turnId, "error", Map.of(
                        "code", "operational-agent-turn-timeout",
                        "statusText", "O tempo limite de processamento da consulta foi excedido.",
                        "retryable", true));
                turnService.expireTurn(threadId, turnId);
            } catch (RuntimeException ex) {
                log.debug("[OperationalAgentTurnStream] Timeout finalization warning for stream {}: {}", streamId, ex.getMessage());
            } finally {
                completeStream(streamId);
            }
        }, Math.max(5L, turnTimeoutSeconds), TimeUnit.SECONDS);
        timeoutTasks.put(streamId, task);
        if (terminalReached(streamId)) {
            ScheduledFuture<?> removed = timeoutTasks.remove(streamId);
            if (removed != null) {
                removed.cancel(false);
            }
        }
    }

    private void emitHeartbeat(UUID streamId) {
        if (terminalReached(streamId)) {
            return;
        }
        Set<SseEmitter> emitters = emittersByStream.get(streamId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        AiTurnEventEnvelope hb = AiTurnEventEnvelope.builder()
                .eventId(null)
                .streamId(streamId)
                .seq(-1L)
                .eventSchemaVersion(eventSchemaVersion)
                .timestamp(Instant.now())
                .type("heartbeat")
                .payload(objectMapper.valueToTree(Map.of("phase", "heartbeat")))
                .build();
        List<SseEmitter> failed = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                send(emitter, hb);
            } catch (Exception ex) {
                failed.add(emitter);
            }
        }
        for (SseEmitter fail : failed) {
            unregister(streamId, fail);
        }
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
            throw new IllegalStateException("SSE send failed", ex);
        }
    }

    private void completeStream(UUID streamId) {
        ScheduledFuture<?> hb = heartbeatTasks.remove(streamId);
        if (hb != null) {
            hb.cancel(false);
        }
        ScheduledFuture<?> replayTask = replayTasks.remove(streamId);
        if (replayTask != null) {
            replayTask.cancel(false);
        }
        ScheduledFuture<?> timeoutTask = timeoutTasks.remove(streamId);
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
        Set<SseEmitter> emitters = emittersByStream.remove(streamId);
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
        replayCursorByStream.remove(streamId);
        cancelledByStream.remove(streamId);
        if (!scheduler.isShutdown()) {
            scheduler.schedule(() -> {
                terminalByStream.remove(streamId);
                streamLocks.remove(streamId);
            }, 60, TimeUnit.SECONDS);
        } else {
            terminalByStream.remove(streamId);
            streamLocks.remove(streamId);
        }
    }

    private Object streamLock(UUID streamId) {
        return streamLocks.computeIfAbsent(streamId, ignored -> new Object());
    }

    private void unregister(UUID streamId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByStream.get(streamId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByStream.remove(streamId);
                ScheduledFuture<?> replayTask = replayTasks.remove(streamId);
                if (replayTask != null) {
                    replayTask.cancel(false);
                }
            }
        }
    }

    private boolean terminalReached(UUID streamId) {
        AtomicBoolean flag = terminalByStream.get(streamId);
        return flag != null && flag.get();
    }

    private AgentExecutionContext buildExecutionContext(
            UUID threadId,
            UUID turnId,
            UUID streamId,
            OperationalAgentTurnStreamRequest request,
            AiPrincipalContext principalContext,
            HttpServletRequest servletRequest) {
        Set<String> principalAuthorities = principalContext.authorities() != null
                ? principalContext.authorities()
                : Set.of();
        AgentExecutionContext ctx = new AgentExecutionContext(
                threadId,
                turnId,
                streamId,
                principalContext.tenantId(),
                principalContext.userId(),
                principalContext.environment(),
                principalContext.userId(),
                principalAuthorities,
                request.activeScreenResourcePath(),
                request.activeTopicKey(),
                request.activeSelectionHints(),
                Map.of());
        for (AgentExecutionContextCustomizer customizer : contextCustomizers) {
            AgentExecutionContext updated = customizer.customize(ctx, servletRequest);
            if (updated != null) {
                ctx = updated;
            }
        }
        return ctx;
    }

    private AiCallConfig resolveCallConfig(
            OperationalAgentTurnStreamRequest request,
            AiPrincipalContext principalContext) {
        String tenantKey = StringUtils.hasText(principalContext.tenantId())
                ? principalContext.tenantId().trim()
                : "default";
        String envKey = StringUtils.hasText(principalContext.environment())
                ? principalContext.environment().trim()
                : "default";
        AiCallConfig requested = AiCallConfig.builder()
                .provider(request.provider())
                .model(request.model())
                .apiKey(corporateMode ? null : request.apiKey())
                .tenantId(principalContext.tenantId())
                .environment(principalContext.environment())
                .thinkingProfile(AiThinkingProfile.OPERATIONAL_AUDIT)
                .promptCacheKey("praxis-op-agent:" + tenantKey + ":" + envKey)
                .build();
        if (providerManagementService == null) {
            return requested;
        }
        return providerManagementService.resolveEffectiveCallConfig(
                requested,
                principalContext.tenantId(),
                principalContext.userId(),
                principalContext.environment());
    }

    private AgenticAuthoringTurnStreamStartResponse startResponse(
            UUID streamId,
            UUID threadId,
            UUID turnId,
            Instant expiresAt,
            AiPrincipalContext principalContext) {
        return AgenticAuthoringTurnStreamStartResponse.builder()
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .eventSchemaVersion(eventSchemaVersion)
                .streamAuthMode(streamAccessTokenService.resolveAuthMode())
                .streamAccessToken(streamAccessTokenService.issueToken(streamId, principalContext, expiresAt))
                .expiresAt(expiresAt)
                .build();
    }

    private void validate(OperationalAgentTurnStreamRequest request) {
        if (request == null || !StringUtils.hasText(request.userPrompt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userPrompt is required.");
        }
    }

    private void validateIdempotency(String storedHash, String incomingHash) {
        if (StringUtils.hasText(storedHash) && StringUtils.hasText(incomingHash) && !storedHash.equals(incomingHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "operational-agent-idempotency-conflict");
        }
    }

    private String hashRequest(
            OperationalAgentTurnStreamRequest request,
            String sanitizedPrompt,
            AiPrincipalContext principalContext,
            Map<String, Object> propagatedClaims) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String historyJson = request.conversationMessages() != null && !request.conversationMessages().isEmpty()
                    ? objectMapper.writeValueAsString(request.conversationMessages())
                    : "";
            String hintsJson = request.activeSelectionHints() != null && !request.activeSelectionHints().isEmpty()
                    ? objectMapper.writeValueAsString(request.activeSelectionHints())
                    : "";
            String claimsScope = "";
            if (propagatedClaims != null && !propagatedClaims.isEmpty()) {
                java.util.TreeMap<String, Object> sortedClaims = new java.util.TreeMap<>();
                for (Map.Entry<String, Object> entry : propagatedClaims.entrySet()) {
                    if (StringUtils.hasText(entry.getKey()) && entry.getValue() != null) {
                        sortedClaims.put(entry.getKey(), entry.getValue());
                    }
                }
                if (!sortedClaims.isEmpty()) {
                    claimsScope = objectMapper.writeValueAsString(sortedClaims);
                }
            }
            String payload = String.join("|",
                    safe(principalContext.tenantId()),
                    safe(principalContext.userId()),
                    safe(principalContext.environment()),
                    safe(claimsScope),
                    safe(sanitizedPrompt),
                    safe(request.activeScreenResourcePath()),
                    safe(request.activeTopicKey()),
                    safe(request.provider()),
                    safe(request.model()),
                    historyJson,
                    hintsJson);
            return HexFormat.of().formatHex(md.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(Objects.hash(sanitizedPrompt, request.activeScreenResourcePath()));
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static UUID stableUuid(String namespace, String value) {
        return UUID.nameUUIDFromBytes((namespace + ":" + safe(value)).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID parseUuid(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private ExecutorService createExecutor() {
        AtomicInteger idx = new AtomicInteger(1);
        ThreadFactory tf = runnable -> {
            Thread t = new Thread(runnable);
            t.setName("operational-agent-worker-" + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(4, 16, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(200), tf, new ThreadPoolExecutor.AbortPolicy());
    }

    private ScheduledExecutorService createScheduler() {
        AtomicInteger idx = new AtomicInteger(1);
        ThreadFactory tf = runnable -> {
            Thread t = new Thread(runnable);
            t.setName("operational-agent-scheduler-" + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, tf);
        exec.setRemoveOnCancelPolicy(true);
        return exec;
    }

    @PreDestroy
    void shutdown() {
        processingTasks.values().forEach(f -> f.cancel(true));
        processingTasks.clear();
        heartbeatTasks.values().forEach(f -> f.cancel(false));
        heartbeatTasks.clear();
        replayTasks.values().forEach(f -> f.cancel(false));
        replayTasks.clear();
        timeoutTasks.values().forEach(f -> f.cancel(false));
        timeoutTasks.clear();
        emittersByStream.clear();
        replayCursorByStream.clear();
        cancelledByStream.clear();
        terminalByStream.clear();
        streamLocks.clear();
        executor.shutdownNow();
        scheduler.shutdownNow();
    }
}
