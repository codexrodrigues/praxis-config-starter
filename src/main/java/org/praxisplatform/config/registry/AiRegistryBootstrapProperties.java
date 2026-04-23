package org.praxisplatform.config.registry;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "praxis.ai.registry.bootstrap")
public class AiRegistryBootstrapProperties {

    private boolean enabled = true;

    /**
     * Reingest the configured snapshot even when minimum readiness checks already pass.
     */
    private boolean force = false;

    /**
     * Reingest the versioned snapshot when the persisted registry was previously
     * considered ready but was bootstrapped from a different snapshot.
     */
    private boolean refreshOnSnapshotDrift = true;

    /**
     * Optional external snapshot location (file:/... or classpath:/...).
     * When empty, falls back to classpath:ai-registry/registry-snapshot.json.
     */
    private String snapshotLocation;

    /**
     * Core component ids required to consider the registry ready.
     */
    private List<String> requiredComponents = List.of(
            "praxis-table",
            "praxis-dynamic-form"
    );

    /**
     * Minimum component_definition count required.
     */
    private long minComponentDefinitions = 1;

    /**
     * Minimum template count required.
     */
    private long minTemplates = 1;
}
