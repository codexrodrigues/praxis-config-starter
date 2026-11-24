package org.praxisplatform.config.ai.agent.runtime;

import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentSystemInstructionContributor;
import org.praxisplatform.config.ai.agent.spi.AgentSystemInstructionSection;

import java.util.Optional;

/**
 * Contributes the tenant/catalog resource discovery summary in
 * {@link AgentSystemInstructionSection.SectionPlacement#TENANT_CATALOG_SECTION}.
 */
public final class ResourceDiscoverySummaryInstructionContributor implements AgentSystemInstructionContributor {

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE + 500;
    }

    @Override
    public Optional<AgentSystemInstructionSection> contribute(
            AgentExecutionContext context,
            String resourceDiscoverySummary
    ) {
        if (resourceDiscoverySummary == null || resourceDiscoverySummary.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AgentSystemInstructionSection(
                "praxis.core.resource-discovery-summary",
                AgentSystemInstructionSection.SectionPlacement.TENANT_CATALOG_SECTION,
                resourceDiscoverySummary.trim()));
    }
}
