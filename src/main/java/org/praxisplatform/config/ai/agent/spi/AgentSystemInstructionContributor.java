package org.praxisplatform.config.ai.agent.spi;

import org.springframework.core.Ordered;

import java.util.Optional;

/**
 * Extensible SPI allowing Praxis modules or host applications to contribute ordered sections
 * to the operational agent's system instruction without coupling the core loop to specific domains.
 */
public interface AgentSystemInstructionContributor extends Ordered {

    Optional<AgentSystemInstructionSection> contribute(
            AgentExecutionContext context,
            String resourceDiscoverySummary
    );

    @Override
    default int getOrder() {
        return 0;
    }
}
