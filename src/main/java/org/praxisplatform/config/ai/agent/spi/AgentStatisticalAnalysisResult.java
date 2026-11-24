package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * Neutral statistical inference or anomaly scan execution result separating UI payloads from
 * sanitized model-safe summaries and fail-closed epistemic metadata.
 */
public record AgentStatisticalAnalysisResult(
        String entityKey,
        String resourceKey,
        String resourcePath,
        String title,
        String analysisKind,
        AgentEpistemicEnvelope epistemicEnvelope,
        Map<String, Object> modelSafePayload,
        Map<String, Object> uiPayload
) {
    public AgentStatisticalAnalysisResult {
        modelSafePayload = AgentMapCopies.immutableOrderedMap(modelSafePayload);
        uiPayload = AgentMapCopies.immutableOrderedMap(uiPayload);
    }
}
