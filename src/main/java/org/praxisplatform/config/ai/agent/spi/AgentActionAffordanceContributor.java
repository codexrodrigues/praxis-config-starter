package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * SPI opcional do host para enriquecer, reordenar ou restringir affordances de acao
 * descobertas para registros individuais ou colecoes no assistente operacional.
 */
@FunctionalInterface
public interface AgentActionAffordanceContributor {

    List<AgentActionAffordance> contributeActions(
            AgentExecutionContext context,
            AgentEntityDescriptor entity,
            Object convertedId,
            String entityId,
            Map<String, Object> uiRecord,
            List<AgentActionAffordance> discoveredActions);
}
