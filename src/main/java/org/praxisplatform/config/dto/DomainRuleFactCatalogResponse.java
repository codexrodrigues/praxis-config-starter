package org.praxisplatform.config.dto;

import java.util.List;
import java.util.UUID;

/** Versioned fact vocabulary projected from a governed rule definition. */
public record DomainRuleFactCatalogResponse(
        UUID definitionId,
        String ruleKey,
        Integer definitionVersion,
        String schemaVersion,
        List<DomainRuleFactDescriptor> facts
) {
}
