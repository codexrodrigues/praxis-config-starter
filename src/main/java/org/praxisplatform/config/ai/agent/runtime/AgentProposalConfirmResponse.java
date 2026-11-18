package org.praxisplatform.config.ai.agent.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.praxisplatform.config.ai.agent.spi.AgentMapCopies;

/**
 * Resposta HTTP canonica para confirmacao ou descarte de proposta governada em
 * {@code /api/praxis/config/ai/agent/proposals/{proposalId}/confirm} e {@code /reject}.
 */
public record AgentProposalConfirmResponse(
        UUID proposalId,
        String status,
        boolean succeeded,
        String outcomeCode,
        String statusNote,
        String updatedVersionTag,
        String updatedLifecycleState,
        Map<String, Object> uiResult,
        List<Map<String, Object>> ruleViolations,
        Map<String, String> fieldErrors,
        Map<String, Object> refreshedSnapshot
) {
    public AgentProposalConfirmResponse {
        uiResult = AgentMapCopies.immutableOrderedMap(uiResult);
        ruleViolations = AgentMapCopies.immutableList(ruleViolations);
        fieldErrors = AgentMapCopies.immutableOrderedMap(fieldErrors);
        refreshedSnapshot = AgentMapCopies.immutableOrderedMap(refreshedSnapshot);
    }
}
