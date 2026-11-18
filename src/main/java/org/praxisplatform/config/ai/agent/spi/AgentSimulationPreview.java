package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * Resultado de simulacao nao persistente com dupla projecao ({@code uiPreview} para exibicao na UI
 * e {@code modelSafePreview} governado para o LLM) e eventuais inconsistencias de rascunho.
 */
public record AgentSimulationPreview(
        String entityKey,
        String resourceKey,
        String resourcePath,
        String determinationId,
        String title,
        String description,
        Map<String, Object> submittedDraft,
        Map<String, Object> uiPreview,
        Map<String, Object> modelSafePreview,
        List<AgentFieldDescriptor> previewFields,
        List<Map<String, String>> validationIssues
) {
    public AgentSimulationPreview {
        submittedDraft = AgentMapCopies.immutableOrderedMap(submittedDraft);
        uiPreview = AgentMapCopies.immutableOrderedMap(uiPreview);
        modelSafePreview = AgentMapCopies.immutableOrderedMap(modelSafePreview);
        previewFields = AgentMapCopies.immutableList(previewFields);
        validationIssues = AgentMapCopies.immutableList(validationIssues);
    }
}
