package org.praxisplatform.config.ai.agent.spi;

import java.util.List;

/**
 * SPI para contribuicao de topicos de negocio pelo host sem acoplamento hardcoded no starter.
 */
public interface AgentTopicContributor {

    List<AgentTopicDescriptor> contributeTopics(AgentExecutionContext context);
}
