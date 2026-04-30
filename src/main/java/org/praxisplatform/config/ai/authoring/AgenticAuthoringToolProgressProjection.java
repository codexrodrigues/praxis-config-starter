package org.praxisplatform.config.ai.authoring;

import java.util.Map;

record AgenticAuthoringToolProgressProjection(
        String phase,
        String label,
        Map<String, Object> diagnostics
) {

    AgenticAuthoringToolProgressProjection {
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
