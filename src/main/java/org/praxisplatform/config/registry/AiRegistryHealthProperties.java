package org.praxisplatform.config.registry;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "praxis.ai.registry.health")
public class AiRegistryHealthProperties {

    private boolean enabled = true;

    /**
     * Optional override for core component ids required to consider the registry ready.
     */
    private List<String> requiredComponents;

    /**
     * Optional override for minimum component_definition count required.
     */
    private Long minComponentDefinitions;

    /**
     * Optional override for minimum template count required.
     */
    private Long minTemplates;
}
