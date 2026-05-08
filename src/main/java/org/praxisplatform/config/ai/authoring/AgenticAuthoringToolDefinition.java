package org.praxisplatform.config.ai.authoring;

import java.util.Set;

record AgenticAuthoringToolDefinition(
        String name,
        Set<String> allowedRoutes,
        Set<String> allowedPhases,
        String ownerSurface,
        String sideEffectClass,
        String governanceLevel,
        String auditRedactionPolicy
) {
    AgenticAuthoringToolDefinition(
            String name,
            Set<String> allowedRoutes,
            String ownerSurface,
            String sideEffectClass,
            String governanceLevel,
            String auditRedactionPolicy) {
        this(
                name,
                allowedRoutes,
                Set.of("retrieveEvidence", "repairOrAsk"),
                ownerSurface,
                sideEffectClass,
                governanceLevel,
                auditRedactionPolicy);
    }
}
