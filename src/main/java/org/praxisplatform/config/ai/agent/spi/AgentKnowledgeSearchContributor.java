package org.praxisplatform.config.ai.agent.spi;

import java.util.List;

/**
 * SPI extensivel para busca em normas, regras de dominio, catalogos de erro e configuracoes
 * declarativas sem acoplamento hardcoded no starter.
 */
public interface AgentKnowledgeSearchContributor {

    List<AgentKnowledgeDocument> searchKnowledge(
            AgentExecutionContext context,
            String query,
            String topicKey,
            String sourceFilter,
            int limit);
}
