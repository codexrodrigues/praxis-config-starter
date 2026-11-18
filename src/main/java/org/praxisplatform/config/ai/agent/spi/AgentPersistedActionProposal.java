package org.praxisplatform.config.ai.agent.spi;

import java.time.Instant;
import java.util.UUID;

/**
 * Proposta persistida de acao operacional pronta para revisao humana, abertura de formulario
 * pre-preenchido ou confirmacao com {@code If-Match} e {@code Idempotency-Key}.
 */
public record AgentPersistedActionProposal(
        UUID proposalId,
        String proposalStatus,
        Instant expiresAt,
        AgentPreparedAction prepared
) {
}
