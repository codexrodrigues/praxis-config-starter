package org.praxisplatform.config.service;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.dto.AiChatMessage;
import org.praxisplatform.config.dto.AiOrchestratorResponse;

/**
 * Snapshot da memoria util de um turno AI preparado antes da orquestracao.
 *
 * <p>O objeto reúne thread, turno, sumario, janela recente de mensagens e eventual resposta em
 * cache, permitindo que o orquestrador trate caminhos com ou sem reaproveitamento.
 */
public class AiMemoryContext {
    private final UUID threadId;
    private final UUID turnId;
    private final String summary;
    private final List<AiChatMessage> windowMessages;
    private final int windowSize;
    private final boolean cached;
    private final AiOrchestratorResponse cachedResponse;
    private final boolean deferTurnCompletion;

    public AiMemoryContext(
            UUID threadId,
            UUID turnId,
            String summary,
            List<AiChatMessage> windowMessages,
            int windowSize,
            boolean cached,
            AiOrchestratorResponse cachedResponse) {
        this(threadId, turnId, summary, windowMessages, windowSize, cached, cachedResponse, false);
    }

    public AiMemoryContext(
            UUID threadId,
            UUID turnId,
            String summary,
            List<AiChatMessage> windowMessages,
            int windowSize,
            boolean cached,
            AiOrchestratorResponse cachedResponse,
            boolean deferTurnCompletion) {
        this.threadId = threadId;
        this.turnId = turnId;
        this.summary = summary;
        this.windowMessages = windowMessages;
        this.windowSize = windowSize;
        this.cached = cached;
        this.cachedResponse = cachedResponse;
        this.deferTurnCompletion = deferTurnCompletion;
    }

    public UUID getThreadId() {
        return threadId;
    }

    public UUID getTurnId() {
        return turnId;
    }

    public String getSummary() {
        return summary;
    }

    public List<AiChatMessage> getWindowMessages() {
        return windowMessages;
    }

    public int getWindowSize() {
        return windowSize;
    }

    public boolean isCached() {
        return cached;
    }

    public AiOrchestratorResponse getCachedResponse() {
        return cachedResponse;
    }

    public boolean isDeferTurnCompletion() {
        return deferTurnCompletion;
    }
}
