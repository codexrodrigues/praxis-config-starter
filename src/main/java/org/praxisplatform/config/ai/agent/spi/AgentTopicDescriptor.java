package org.praxisplatform.config.ai.agent.spi;

import java.util.List;

/**
 * Agrupamento semantico de recursos e notas de negocio contribuidos pelo host para roteamento por assunto.
 */
public record AgentTopicDescriptor(
        String topicKey,
        String title,
        String description,
        String primaryEntityKey,
        List<String> resourcePaths,
        List<String> keywords,
        String businessGuidance,
        int priority
) {
    public AgentTopicDescriptor {
        resourcePaths = resourcePaths == null ? List.of() : List.copyOf(resourcePaths);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }
}
