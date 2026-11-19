package org.praxisplatform.config.ai.agent.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDossier;
import org.praxisplatform.config.ai.agent.spi.AgentEntityMatch;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory;
import org.praxisplatform.config.ai.agent.spi.AgentKnowledgeDocument;
import org.praxisplatform.config.ai.agent.spi.AgentPersistedActionProposal;
import org.praxisplatform.config.ai.agent.spi.AgentQueryResult;
import org.praxisplatform.config.ai.agent.spi.AgentSimulationPreview;
import org.praxisplatform.config.ai.agent.spi.AgentStatsResult;

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
    private final List<AgentStatsResult> statsResults = new ArrayList<>();
    private final List<AgentEvaluatedCalculationMemory> calculationMemories = new ArrayList<>();
    private final List<AgentKnowledgeDocument> knowledgeCitations = new ArrayList<>();
    private final List<AgentSimulationPreview> simulationPreviews = new ArrayList<>();
    private final List<AgentPersistedActionProposal> actionProposals = new ArrayList<>();
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

    public synchronized void recordStatsResult(AgentStatsResult stats) {
        if (stats != null) {
            statsResults.add(stats);
        }
    }

    public synchronized void recordCalculationMemory(AgentEvaluatedCalculationMemory memory) {
        if (memory == null) {
            return;
        }
        String key = memoryKey(memory);
        for (int i = 0; i < calculationMemories.size(); i++) {
            if (key.equalsIgnoreCase(memoryKey(calculationMemories.get(i)))) {
                calculationMemories.set(i, memory);
                return;
            }
        }
        calculationMemories.add(memory);
    }

    public synchronized void recordKnowledgeCitations(List<AgentKnowledgeDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        for (AgentKnowledgeDocument doc : docs) {
            if (doc == null || doc.docId() == null) {
                continue;
            }
            boolean updated = false;
            for (int i = 0; i < knowledgeCitations.size(); i++) {
                if (doc.docId().equals(knowledgeCitations.get(i).docId())) {
                    if (doc.score() >= knowledgeCitations.get(i).score()) {
                        knowledgeCitations.set(i, doc);
                    }
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                knowledgeCitations.add(doc);
            }
        }
    }

    public synchronized void recordSimulationPreview(AgentSimulationPreview preview) {
        if (preview != null) {
            simulationPreviews.add(preview);
        }
    }

    public synchronized void recordActionProposal(AgentPersistedActionProposal proposal) {
        if (proposal == null || proposal.proposalId() == null) {
            return;
        }
        for (int i = 0; i < actionProposals.size(); i++) {
            if (proposal.proposalId().equals(actionProposals.get(i).proposalId())) {
                actionProposals.set(i, proposal);
                return;
            }
        }
        actionProposals.add(proposal);
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

    public synchronized List<AgentStatsResult> statsResults() {
        return Collections.unmodifiableList(new ArrayList<>(statsResults));
    }

    public synchronized List<AgentEvaluatedCalculationMemory> calculationMemories() {
        return Collections.unmodifiableList(new ArrayList<>(calculationMemories));
    }

    public synchronized List<AgentKnowledgeDocument> knowledgeCitations() {
        return Collections.unmodifiableList(new ArrayList<>(knowledgeCitations));
    }

    public synchronized List<AgentSimulationPreview> simulationPreviews() {
        return Collections.unmodifiableList(new ArrayList<>(simulationPreviews));
    }

    public synchronized List<AgentPersistedActionProposal> actionProposals() {
        return Collections.unmodifiableList(new ArrayList<>(actionProposals));
    }

    public synchronized List<ToolExecutionTrace> traces() {
        return Collections.unmodifiableList(new ArrayList<>(traces));
    }

    private static String memoryKey(AgentEvaluatedCalculationMemory memory) {
        String ek = memory.entityKey() != null ? memory.entityKey().trim() : "";
        String id = memory.entityId() != null ? memory.entityId().trim() : "";
        return ek + "::" + id;
    }
}
