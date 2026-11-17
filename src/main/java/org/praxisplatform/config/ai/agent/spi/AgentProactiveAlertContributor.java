package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * SPI de alertas proativos avaliados em tempo de consulta por entidade.
 */
public interface AgentProactiveAlertContributor {

    boolean supportsEntity(String entityKey);

    List<AgentProactiveAlert> evaluateAlerts(
            AgentExecutionContext context,
            String entityKey,
            String entityId,
            Map<String, Object> canonicalRecord);
}
