package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringPreIntentToolPlan(
        String schemaVersion,
        String reason,
        List<AgenticAuthoringToolCall> toolCalls,
        String semanticIntentClass,
        String assistantMessage
) {

    public AgenticAuthoringPreIntentToolPlan {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? "praxis-agentic-authoring-pre-intent-tool-plan.v1"
                : schemaVersion.trim();
        reason = reason == null ? "" : reason.trim();
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        semanticIntentClass = semanticIntentClass == null || semanticIntentClass.isBlank()
                ? "authoring_or_other"
                : semanticIntentClass.trim();
        assistantMessage = assistantMessage == null ? "" : assistantMessage.trim();
    }

    public AgenticAuthoringPreIntentToolPlan(
            String schemaVersion,
            String reason,
            List<AgenticAuthoringToolCall> toolCalls) {
        this(schemaVersion, reason, toolCalls, "authoring_or_other", "");
    }

    public boolean resolvesPlatformGuidance() {
        return "platform_guidance".equals(semanticIntentClass) && !assistantMessage.isBlank();
    }

    public boolean resolvesGovernedDomainDiscovery() {
        return "governed_domain_discovery".equals(semanticIntentClass);
    }
}
