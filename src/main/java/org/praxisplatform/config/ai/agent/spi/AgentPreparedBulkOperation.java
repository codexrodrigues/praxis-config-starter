package org.praxisplatform.config.ai.agent.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Resultado completo do preparo de uma operacao em lote governada em processo, vinculando a
 * proposta nativa ({@code domainProposalId}) e sua projecao dual (UI autorizada vs. LLM minimizada).
 */
public record AgentPreparedBulkOperation(
        AgentPreparedAction base,
        String domainProposalId,
        String bulkOperationId,
        String bulkMode,
        String atomicity,
        String selectionMode,
        String domainProposalStatus,
        long targetCount,
        long evaluatedCount,
        long executableCount,
        long blockedCount,
        String filterLabel,
        Map<String, Object> selectionPayload,
        List<Map<String, Object>> changes,
        List<Map<String, Object>> itemChanges,
        List<Map<String, Object>> uiPreviewItems,
        List<Map<String, Object>> modelSafePreviewItems,
        List<Map<String, Object>> blockedDiagnostics,
        Map<String, Object> bulkContract,
        Instant nativeExpiresAt
) {
    public AgentPreparedBulkOperation {
        selectionPayload = AgentMapCopies.immutableOrderedMap(selectionPayload);
        changes = AgentMapCopies.immutableList(changes);
        itemChanges = AgentMapCopies.immutableList(itemChanges);
        uiPreviewItems = AgentMapCopies.immutableList(uiPreviewItems);
        modelSafePreviewItems = AgentMapCopies.immutableList(modelSafePreviewItems);
        blockedDiagnostics = AgentMapCopies.immutableList(blockedDiagnostics);
        bulkContract = AgentMapCopies.immutableOrderedMap(bulkContract);
    }
}
