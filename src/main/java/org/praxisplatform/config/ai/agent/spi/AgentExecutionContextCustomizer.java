package org.praxisplatform.config.ai.agent.spi;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Ponto de extensao para enriquecer o {@link AgentExecutionContext} na thread HTTP antes da
 * submissao assincrona do turno.
 */
@FunctionalInterface
public interface AgentExecutionContextCustomizer {

    AgentExecutionContext customize(AgentExecutionContext current, HttpServletRequest request);
}
