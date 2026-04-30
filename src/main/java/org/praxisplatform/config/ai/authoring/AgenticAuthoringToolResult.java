package org.praxisplatform.config.ai.authoring;

import java.util.Map;

record AgenticAuthoringToolResult(
        String tool,
        boolean valid,
        Object payload,
        String errorCode,
        String errorMessage,
        Map<String, Object> safeDiagnostics
) {

    static AgenticAuthoringToolResult success(
            String tool,
            Object payload,
            Map<String, Object> safeDiagnostics) {
        return new AgenticAuthoringToolResult(
                tool,
                true,
                payload,
                null,
                null,
                safeDiagnostics != null ? Map.copyOf(safeDiagnostics) : Map.of());
    }

    static AgenticAuthoringToolResult failure(
            String tool,
            String errorCode,
            String errorMessage) {
        return new AgenticAuthoringToolResult(
                tool,
                false,
                null,
                errorCode,
                errorMessage,
                Map.of());
    }
}
