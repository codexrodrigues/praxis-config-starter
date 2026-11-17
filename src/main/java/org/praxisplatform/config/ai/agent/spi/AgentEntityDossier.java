package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * Dossie 360 consolidado de um registro, combinando dados principais com dupla projecao,
 * alertas proativos, superficies relacionadas e acoes governadas permitidas.
 */
public record AgentEntityDossier(
        AgentEntityDescriptor entity,
        String entityId,
        String displayTitle,
        String displaySubtitle,
        Map<String, Object> uiRecord,
        Map<String, Object> modelSafeRecord,
        List<String> hiddenFields,
        List<String> maskedForAiFields,
        List<AgentRelatedDataset> relatedDatasets,
        List<AgentProactiveAlert> alerts,
        List<AgentActionAffordance> actions,
        List<AgentSurfaceSummary> surfaces
) {
    public AgentEntityDossier {
        uiRecord = AgentMapCopies.immutableOrderedMap(uiRecord);
        modelSafeRecord = AgentMapCopies.immutableOrderedMap(modelSafeRecord);
        hiddenFields = AgentMapCopies.immutableList(hiddenFields);
        maskedForAiFields = AgentMapCopies.immutableList(maskedForAiFields);
        relatedDatasets = AgentMapCopies.immutableList(relatedDatasets);
        alerts = AgentMapCopies.immutableList(alerts);
        actions = AgentMapCopies.immutableList(actions);
        surfaces = AgentMapCopies.immutableList(surfaces);
    }
}
