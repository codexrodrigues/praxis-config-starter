package org.praxisplatform.config.ai.authoring;

import java.util.Map;

public record AgenticAuthoringToolLoopStep(
        int stepIndex,
        String phase,
        String tool,
        boolean valid,
        String errorCode,
        Map<String, Object> safeDiagnostics
) {
}
