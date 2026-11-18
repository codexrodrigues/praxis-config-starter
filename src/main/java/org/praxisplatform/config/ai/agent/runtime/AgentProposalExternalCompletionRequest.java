package org.praxisplatform.config.ai.agent.runtime;

/**
 * Payload opcional enviado pelo host apos efetivar a operacao diretamente via formulario governado
 * para conciliar o ciclo de vida e a trilha de auditoria da proposta persistida.
 */
public record AgentProposalExternalCompletionRequest(
        String updatedVersionTag,
        String updatedLifecycleState,
        String idempotencyKey
) {
}
