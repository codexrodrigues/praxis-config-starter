package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * Desfecho estruturado da execucao de uma proposta confirmada, cobrindo sucesso {@code 200},
 * conflito otimista {@code 412 STALE} com snapshot atualizado, ou violacoes de regra {@code 422}.
 */
public record AgentActionExecutionOutcome(
        int httpStatus,
        boolean succeeded,
        String outcomeCode,
        String statusNote,
        String updatedVersionTag,
        String updatedLifecycleState,
        Map<String, Object> uiResult,
        Map<String, Object> modelSafeResult,
        List<Map<String, Object>> ruleViolations,
        Map<String, String> fieldErrors,
        Map<String, Object> refreshedSnapshot
) {
    public AgentActionExecutionOutcome {
        uiResult = AgentMapCopies.immutableOrderedMap(uiResult);
        modelSafeResult = AgentMapCopies.immutableOrderedMap(modelSafeResult);
        ruleViolations = AgentMapCopies.immutableList(ruleViolations);
        fieldErrors = AgentMapCopies.immutableOrderedMap(fieldErrors);
        refreshedSnapshot = AgentMapCopies.immutableOrderedMap(refreshedSnapshot);
    }
}
