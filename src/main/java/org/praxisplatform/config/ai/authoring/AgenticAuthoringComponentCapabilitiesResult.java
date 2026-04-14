package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringComponentCapabilitiesResult(
        String version,
        List<ComponentCapabilityCatalog> catalogs) {

    public record ComponentCapabilityCatalog(
            String componentId,
            String version,
            List<ComponentCapability> capabilities) {
    }

    public record ComponentCapability(
            String id,
            String changeKind,
            List<String> triggerTerms,
            List<ComponentFieldAlias> fieldAliases) {
    }

    public record ComponentFieldAlias(
            String field,
            List<String> aliases) {
    }
}
