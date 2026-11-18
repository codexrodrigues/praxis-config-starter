package org.praxisplatform.config.ai.agent.spi;

import java.util.List;

/**
 * Memoria de calculo deterministica avaliada sobre metadados {@code @CalculationMemory} com dupla
 * projecao ({@code uiTrails} para a interface autenticada e {@code modelSafeTrails} para o LLM).
 */
public record AgentEvaluatedCalculationMemory(
        String entityKey,
        String resourceKey,
        String resourcePath,
        String entityId,
        String recordTitle,
        String title,
        String subtitle,
        String icon,
        String legalBasis,
        String emptyText,
        String modelSafeTitle,
        String modelSafeSubtitle,
        String modelSafeLegalBasis,
        List<EvaluatedTrail> uiTrails,
        List<EvaluatedTrail> modelSafeTrails,
        List<String> maskedForAiFields
) {
    public AgentEvaluatedCalculationMemory {
        uiTrails = AgentMapCopies.immutableList(uiTrails);
        modelSafeTrails = AgentMapCopies.immutableList(modelSafeTrails);
        maskedForAiFields = AgentMapCopies.immutableList(maskedForAiFields);
    }

    public AgentEvaluatedCalculationMemory(
            String entityKey,
            String resourceKey,
            String resourcePath,
            String entityId,
            String recordTitle,
            String title,
            String subtitle,
            String icon,
            String legalBasis,
            String emptyText,
            List<EvaluatedTrail> uiTrails,
            List<EvaluatedTrail> modelSafeTrails,
            List<String> maskedForAiFields) {
        this(
                entityKey,
                resourceKey,
                resourcePath,
                entityId,
                recordTitle,
                title,
                subtitle,
                icon,
                legalBasis,
                emptyText,
                title,
                subtitle,
                legalBasis,
                uiTrails,
                modelSafeTrails,
                maskedForAiFields);
    }

    public record EvaluatedTrail(
            String id,
            String title,
            String ruleCode,
            String formula,
            String legalBasis,
            String legalPurpose,
            String resultLabel,
            String resultFormatted,
            String resultTone,
            String resultHint,
            List<EvaluatedStep> steps
    ) {
        public EvaluatedTrail {
            steps = AgentMapCopies.immutableList(steps);
        }
    }

    public record EvaluatedStep(
            String role,
            String label,
            String formattedValue,
            Object rawValue,
            String format,
            String operator,
            String hint,
            String legalBasis
    ) {
    }
}
