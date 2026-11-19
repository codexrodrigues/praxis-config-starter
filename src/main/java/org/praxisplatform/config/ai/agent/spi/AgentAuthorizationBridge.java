package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;
import java.util.Set;

/**
 * SPI de autorizacao e dupla projecao de dados para o agente operacional.
 *
 * <p>Permite que hosts com {@code praxis-authz-starter} apliquem decisoes OPA/PEPs enquanto hosts
 * sem OPA aplicam a governanca declarada via {@code @DomainGovernance}, {@code @AiUsagePolicy} e
 * {@code @UISchema}.
 */
public interface AgentAuthorizationBridge {

    boolean canReadResource(AgentExecutionContext context, String resourcePath);

    boolean canInvokeAction(
            AgentExecutionContext context,
            String resourcePath,
            String actionId,
            Object entityId);

    default boolean canModifyFields(
            AgentExecutionContext context,
            String resourcePath,
            Class<?> dtoType,
            Set<String> fieldNames) {
        return true;
    }

    DualProjectedPayload projectRecord(
            AgentExecutionContext context,
            String resourcePath,
            Class<?> dtoType,
            Map<String, Object> rawRecord);
}
