package org.praxisplatform.config.domain;

/**
 * Estados do ciclo de vida de uma proposta governada de acao operacional ({@link AiAgentActionProposal}).
 */
public enum AiAgentActionProposalStatus {
    PENDING,
    EXECUTING,
    BLOCKED,
    CONFIRMED,
    STALE,
    EXPIRED,
    REJECTED
}
