package org.praxisplatform.config.ai.agent.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload opcional enviado pelo host apos efetivar a operacao diretamente via formulario ou modal
 * de lote governado para conciliar o ciclo de vida e a trilha de auditoria da proposta persistida.
 */
public record AgentProposalExternalCompletionRequest(
        String updatedVersionTag,
        String updatedLifecycleState,
        String idempotencyKey,
        String domainProposalId,
        String bulkExecutionId,
        String bulkExecutionStatus,
        Long confirmedCount,
        Long unchangedCount,
        Long failedCount
) {
    @JsonCreator
    public AgentProposalExternalCompletionRequest(
            @JsonProperty("updatedVersionTag") String updatedVersionTag,
            @JsonProperty("updatedLifecycleState") String updatedLifecycleState,
            @JsonProperty("idempotencyKey") String idempotencyKey,
            @JsonProperty("domainProposalId") String domainProposalId,
            @JsonProperty("bulkExecutionId") String bulkExecutionId,
            @JsonProperty("bulkExecutionStatus") String bulkExecutionStatus,
            @JsonProperty("confirmedCount") Long confirmedCount,
            @JsonProperty("unchangedCount") Long unchangedCount,
            @JsonProperty("failedCount") Long failedCount) {
        this.updatedVersionTag = updatedVersionTag;
        this.updatedLifecycleState = updatedLifecycleState;
        this.idempotencyKey = idempotencyKey;
        this.domainProposalId = domainProposalId;
        this.bulkExecutionId = bulkExecutionId;
        this.bulkExecutionStatus = bulkExecutionStatus;
        this.confirmedCount = confirmedCount;
        this.unchangedCount = unchangedCount;
        this.failedCount = failedCount;
    }

    public AgentProposalExternalCompletionRequest(
            String updatedVersionTag,
            String updatedLifecycleState,
            String idempotencyKey) {
        this(
                updatedVersionTag,
                updatedLifecycleState,
                idempotencyKey,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
