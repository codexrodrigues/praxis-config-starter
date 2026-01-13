package org.praxisplatform.config.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.repository.AiRegistryRepository;

@RequiredArgsConstructor
public class AiRegistryStatusService {

    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";
    private static final String COMPONENT_DEF_COMPONENT_TYPE = "component-definition";
    private static final String REGISTRY_TYPE_TEMPLATE = "template";
    private static final String SYSTEM_SCOPE_KEY = "GLOBAL";

    private final AiRegistryRepository repository;
    private final AiRegistryBootstrapProperties bootstrapProperties;
    private final AiRegistryHealthProperties healthProperties;
    private final AiRegistryBootstrapState bootstrapState;

    public AiRegistryStatusReport getStatus() {
        long componentDefinitionCount = repository.countByRegistryType(REGISTRY_TYPE_COMPONENT_DEF);
        long templateCount = repository.countByRegistryType(REGISTRY_TYPE_TEMPLATE);

        long minComponentDefinitions = resolveMinComponentDefinitions();
        long minTemplates = resolveMinTemplates();
        List<String> requiredComponents = resolveRequiredComponents();
        List<String> missingComponents = findMissingComponents(requiredComponents);

        boolean ready = componentDefinitionCount >= minComponentDefinitions
                && templateCount >= minTemplates
                && missingComponents.isEmpty();

        String status = resolveStatus(ready, missingComponents, componentDefinitionCount, templateCount,
                minComponentDefinitions, minTemplates);

        return AiRegistryStatusReport.builder()
                .ready(ready)
                .status(status)
                .componentDefinitionCount(componentDefinitionCount)
                .templateCount(templateCount)
                .minComponentDefinitions(minComponentDefinitions)
                .minTemplates(minTemplates)
                .requiredComponents(requiredComponents)
                .missingComponents(missingComponents)
                .bootstrap(bootstrapState)
                .build();
    }

    private List<String> resolveRequiredComponents() {
        List<String> configured = healthProperties != null ? healthProperties.getRequiredComponents() : null;
        if (configured == null) {
            configured = bootstrapProperties != null ? bootstrapProperties.getRequiredComponents() : null;
        }
        if (configured == null) {
            return Collections.emptyList();
        }
        return configured;
    }

    private long resolveMinComponentDefinitions() {
        if (healthProperties != null && healthProperties.getMinComponentDefinitions() != null) {
            return healthProperties.getMinComponentDefinitions();
        }
        return bootstrapProperties != null ? bootstrapProperties.getMinComponentDefinitions() : 0L;
    }

    private long resolveMinTemplates() {
        if (healthProperties != null && healthProperties.getMinTemplates() != null) {
            return healthProperties.getMinTemplates();
        }
        return bootstrapProperties != null ? bootstrapProperties.getMinTemplates() : 0L;
    }

    private List<String> findMissingComponents(List<String> requiredComponents) {
        if (requiredComponents == null || requiredComponents.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> missing = new ArrayList<>();
        for (String componentId : requiredComponents) {
            if (componentId == null || componentId.isBlank()) {
                continue;
            }
            boolean exists = repository.existsByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                    REGISTRY_TYPE_COMPONENT_DEF,
                    componentId,
                    COMPONENT_DEF_COMPONENT_TYPE,
                    Scope.SYSTEM,
                    SYSTEM_SCOPE_KEY);
            if (!exists) {
                missing.add(componentId);
            }
        }
        return missing;
    }

    private String resolveStatus(
            boolean ready,
            List<String> missingComponents,
            long componentDefinitionCount,
            long templateCount,
            long minComponentDefinitions,
            long minTemplates) {
        if (ready) {
            return "ready";
        }
        if (missingComponents != null && !missingComponents.isEmpty()) {
            return "missing-components";
        }
        if (componentDefinitionCount < minComponentDefinitions || templateCount < minTemplates) {
            return "low-counts";
        }
        return "not-ready";
    }
}
