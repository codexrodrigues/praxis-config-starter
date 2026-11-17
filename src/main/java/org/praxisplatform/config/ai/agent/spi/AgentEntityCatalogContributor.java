package org.praxisplatform.config.ai.agent.spi;

import java.util.List;

/**
 * Permite ao host contribuir ou enriquecer descritores de entidades alem da introspeccao
 * automatica de controllers {@code @ApiResource}.
 */
public interface AgentEntityCatalogContributor {

    List<AgentEntityDescriptor> contributeEntities(AgentExecutionContext context);
}
