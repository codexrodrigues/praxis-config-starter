package org.praxisplatform.config.ai.authoring;

import java.util.Set;

record AgenticAuthoringToolDefinition(
        String name,
        Set<String> allowedRoutes,
        String ownerSurface,
        String sideEffectClass,
        String governanceLevel,
        String auditRedactionPolicy
) {
}
