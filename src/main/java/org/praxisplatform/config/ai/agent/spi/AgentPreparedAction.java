package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * Diagnostico completo de preparacao de uma acao ou edicao governada em processo, incluindo estado
 * atual, ETag forte ({@code expectedVersionTag}), prefill e impedimentos explicitos.
 */
public record AgentPreparedAction(
        String entityKey,
        String resourceKey,
        String resourcePath,
        String entityId,
        String recordTitle,
        String recordSubtitle,
        String actionId,
        String actionTitle,
        String actionDescription,
        String operationKind,
        String formMode,
        String httpMethod,
        String submitUrl,
        String schemaUrl,
        String riskLevel,
        boolean confirmationRequired,
        boolean hasInputForm,
        String organizationId,
        String organizationLabel,
        String recordLifecycleState,
        String expectedVersionTag,
        boolean allowed,
        String blockedCode,
        String impedimentNote,
        Map<String, Object> prefill,
        List<AgentActionInputField> inputFields,
        List<String> missingRequiredFields
) {
    public AgentPreparedAction {
        prefill = AgentMapCopies.immutableOrderedMap(prefill);
        inputFields = AgentMapCopies.immutableList(inputFields);
        missingRequiredFields = AgentMapCopies.immutableList(missingRequiredFields);
    }
}
