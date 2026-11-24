package org.praxisplatform.config.ai.agent.model;

import org.praxisplatform.config.service.AiCallConfig;

/**
 * Porta neutra para provedores LLM com tool calling explicito desacoplado do executor local.
 */
public interface AiToolCallingProvider {

    String providerId();

    AgentModelTurnResponse generateTurn(
            AgentModelTurnRequest request,
            AgentModelStreamListener listener);

    /**
     * Indica se este provedor possui credenciais minimas disponiveis (por callConfig ou default do host)
     * para participar como candidato de roteamento ou failover.
     */
    default boolean hasConfiguredCredentials(AiCallConfig callConfig) {
        return true;
    }
}
