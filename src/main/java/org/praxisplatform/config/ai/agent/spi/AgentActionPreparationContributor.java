package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * SPI de guarda e enriquecimento contextual por organizacao, consolidacao somente leitura ou regras
 * de escopo do host durante a preparacao e confirmacao de propostas de escrita.
 */
public interface AgentActionPreparationContributor {

    ActionGuardAssessment assessAction(
            AgentExecutionContext context,
            AgentEntityDescriptor entity,
            String entityId,
            String actionId,
            Map<String, Object> authoritativeRecord,
            Map<String, Object> proposedInputs);

    record ActionGuardAssessment(
            boolean allowed,
            String blockedCode,
            String impedimentNote,
            String organizationId,
            String organizationLabel,
            Map<String, Object> enrichedPrefill
    ) {
        public ActionGuardAssessment {
            enrichedPrefill = AgentMapCopies.immutableOrderedMap(enrichedPrefill);
        }

        public static ActionGuardAssessment allow(
                String organizationId,
                String organizationLabel,
                Map<String, Object> enrichedPrefill) {
            return new ActionGuardAssessment(true, null, null, organizationId, organizationLabel, enrichedPrefill);
        }

        public static ActionGuardAssessment block(
                String blockedCode,
                String impedimentNote,
                String organizationId,
                String organizationLabel) {
            return new ActionGuardAssessment(
                    false, blockedCode, impedimentNote, organizationId, organizationLabel, Map.of());
        }
    }
}
