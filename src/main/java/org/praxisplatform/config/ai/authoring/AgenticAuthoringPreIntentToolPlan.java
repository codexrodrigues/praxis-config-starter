package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringPreIntentToolPlan(
        String schemaVersion,
        String reason,
        List<AgenticAuthoringToolCall> toolCalls
) {

    public AgenticAuthoringPreIntentToolPlan {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? "praxis-agentic-authoring-pre-intent-tool-plan.v1"
                : schemaVersion.trim();
        reason = reason == null ? "" : reason.trim();
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
