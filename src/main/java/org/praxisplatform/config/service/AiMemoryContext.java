package org.praxisplatform.config.service;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.dto.AiChatMessage;
import org.praxisplatform.config.dto.AiOrchestratorResponse;

public class AiMemoryContext {
    private final UUID threadId;
    private final UUID turnId;
    private final String summary;
    private final List<AiChatMessage> windowMessages;
    private final int windowSize;
    private final boolean cached;
    private final AiOrchestratorResponse cachedResponse;

    public AiMemoryContext(
            UUID threadId,
            UUID turnId,
            String summary,
            List<AiChatMessage> windowMessages,
            int windowSize,
            boolean cached,
            AiOrchestratorResponse cachedResponse) {
        this.threadId = threadId;
        this.turnId = turnId;
        this.summary = summary;
        this.windowMessages = windowMessages;
        this.windowSize = windowSize;
        this.cached = cached;
        this.cachedResponse = cachedResponse;
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
}
