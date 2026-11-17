package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * Projecao dupla de um registro retornado por ferramentas: uma visao governada para a UI do
 * usuario autenticado e uma visao estritamente higienizada segundo politicas de uso por IA.
 */
public record DualProjectedPayload(
        Map<String, Object> uiRecord,
        Map<String, Object> modelSafeRecord,
        List<String> hiddenFields,
        List<String> maskedForAiFields
) {
    public DualProjectedPayload {
        uiRecord = AgentMapCopies.immutableOrderedMap(uiRecord);
        modelSafeRecord = AgentMapCopies.immutableOrderedMap(modelSafeRecord);
        hiddenFields = AgentMapCopies.immutableList(hiddenFields);
        maskedForAiFields = AgentMapCopies.immutableList(maskedForAiFields);
    }
}
