package org.praxisplatform.config.ai.agent.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDossier;
import org.praxisplatform.config.ai.agent.spi.AgentEntityMatch;
import org.praxisplatform.config.ai.agent.spi.AgentQueryResult;

/**
 * Armazena os artefatos autoritativos produzidos pelas ferramentas em um turno para composicao
 * deterministica de blocos ricos da UI sem depender do LLM reproduzir JSON ou HTML.
 */
public final class AgentTurnArtifactStore {

    public record ToolExecutionTrace(
            String toolName,
            String description,
            long durationMs,
            int itemCount
    ) {
    }

    private final List<AgentEntityMatch> lastSearchMatches = new ArrayList<>();
    private final List<AgentQueryResult> queryResults = new ArrayList<>();
    private final List<AgentEntityDossier> dossiers = new ArrayList<>();
    private final List<ToolExecutionTrace> traces = new ArrayList<>();

    public synchronized void recordSearchMatches(List<AgentEntityMatch> matches) {
        lastSearchMatches.clear();
        if (matches != null) {
            lastSearchMatches.addAll(matches);
        }
    }

    public synchronized void recordQueryResult(AgentQueryResult result) {
        if (result != null) {
            queryResults.add(result);
        }
    }

    public synchronized void recordDossier(AgentEntityDossier dossier) {
        if (dossier == null) {
            return;
        }
        String entityKey = dossier.entity() != null && dossier.entity().entityKey() != null
                ? dossier.entity().entityKey().trim()
                : "";
        String entityId = dossier.entityId() != null ? dossier.entityId().trim() : "";
        for (int i = 0; i < dossiers.size(); i++) {
            AgentEntityDossier existing = dossiers.get(i);
            String existingKey = existing.entity() != null && existing.entity().entityKey() != null
                    ? existing.entity().entityKey().trim()
                    : "";
            String existingId = existing.entityId() != null ? existing.entityId().trim() : "";
            if (entityKey.equalsIgnoreCase(existingKey) && entityId.equals(existingId)) {
                dossiers.set(i, dossier);
                return;
            }
        }
        dossiers.add(dossier);
    }

    public synchronized void recordTrace(String toolName, String description, long durationMs, int itemCount) {
        traces.add(new ToolExecutionTrace(toolName, description, durationMs, itemCount));
    }

    public synchronized List<AgentEntityMatch> lastSearchMatches() {
        return Collections.unmodifiableList(new ArrayList<>(lastSearchMatches));
    }

    public synchronized List<AgentQueryResult> queryResults() {
        return Collections.unmodifiableList(new ArrayList<>(queryResults));
    }

    public synchronized List<AgentEntityDossier> dossiers() {
        return Collections.unmodifiableList(new ArrayList<>(dossiers));
    }

    public synchronized List<ToolExecutionTrace> traces() {
        return Collections.unmodifiableList(new ArrayList<>(traces));
    }
}
