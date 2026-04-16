package org.praxisplatform.config.ai.authoring;

import java.util.List;

public class AgenticAuthoringComponentCapabilitiesService {

    private static final String RESULT_VERSION = "0.1.0";

    private final AgenticAuthoringFormCapabilityCatalog formCatalog = AgenticAuthoringFormCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringTableCapabilityCatalog tableCatalog = AgenticAuthoringTableCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringChartCapabilityCatalog chartCatalog = AgenticAuthoringChartCapabilityCatalog.INSTANCE;

    public AgenticAuthoringComponentCapabilitiesResult listCapabilities() {
        return new AgenticAuthoringComponentCapabilitiesResult(
                RESULT_VERSION,
                List.of(
                        toCatalog(formCatalog.componentId(), formCatalog.version(), formCatalog.capabilities()),
                        toCatalog(tableCatalog.componentId(), tableCatalog.version(), tableCatalog.capabilities()),
                        toCatalog(chartCatalog.componentId(), chartCatalog.version(), chartCatalog.capabilities())));
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog toCatalog(
            String componentId,
            String version,
            List<AgenticAuthoringComponentCapabilityCatalog.ComponentCapability> capabilities) {
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                componentId,
                version,
                capabilities.stream().map(this::toCapability).toList());
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapability toCapability(
            AgenticAuthoringComponentCapabilityCatalog.ComponentCapability capability) {
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                capability.id(),
                capability.changeKind(),
                capability.triggerTerms(),
                capability.fieldAliases().stream().map(this::toFieldAlias).toList(),
                capability.examples().stream().map(this::toExample).toList());
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample toExample(
            AgenticAuthoringComponentCapabilityCatalog.ComponentCapabilityExample example) {
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample(
                example.prompt(),
                example.intent(),
                example.configHints());
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentFieldAlias toFieldAlias(
            AgenticAuthoringComponentCapabilityCatalog.ComponentFieldAlias alias) {
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentFieldAlias(
                alias.field(),
                alias.aliases());
    }
}
