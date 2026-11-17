package org.praxisplatform.config.ai.agent.model;

/**
 * Porta neutra para provedores LLM com tool calling explicito desacoplado do executor local.
 */
public interface AiToolCallingProvider {

    String providerId();

    AgentModelTurnResponse generateTurn(
            AgentModelTurnRequest request,
            AgentModelStreamListener listener);
}
