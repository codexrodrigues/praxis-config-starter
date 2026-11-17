package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * Subcolecao relacionada materializada dentro de um dossie 360 (ex.: ferias, dependentes, lotacoes).
 */
public record AgentRelatedDataset(
        String surfaceId,
        String title,
        String childResourceKey,
        String childResourcePath,
        String childParentField,
        long totalCount,
        List<Map<String, Object>> uiRows,
        List<Map<String, Object>> modelSafeRows
) {
    public AgentRelatedDataset {
        uiRows = uiRows == null ? List.of() : List.copyOf(uiRows);
        modelSafeRows = modelSafeRows == null ? List.of() : List.copyOf(modelSafeRows);
    }
}
