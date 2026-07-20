package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringPreIntentToolPlan(
        String schemaVersion,
        String reason,
        List<AgenticAuthoringToolCall> toolCalls,
        String semanticIntentClass,
        String assistantMessage,
        boolean requiresFullIntentResolution,
        JsonNode queryConstraints,
        String artifactKind
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
        artifactKind = artifactKind == null || artifactKind.isBlank() ? "unknown" : artifactKind.trim();
    }

    public AgenticAuthoringPreIntentToolPlan(
            String schemaVersion,
            String reason,
            List<AgenticAuthoringToolCall> toolCalls,
            String semanticIntentClass,
            String assistantMessage) {
        this(schemaVersion, reason, toolCalls, semanticIntentClass, assistantMessage, false, null, "unknown");
    }

    public AgenticAuthoringPreIntentToolPlan(
            String schemaVersion,
            String reason,
            List<AgenticAuthoringToolCall> toolCalls) {
        this(schemaVersion, reason, toolCalls, "authoring_or_other", "", false, null, "unknown");
    }

    public boolean resolvesPlatformGuidance() {
        return "platform_guidance".equals(semanticIntentClass) && !assistantMessage.isBlank();
    }

    public boolean resolvesGovernedDomainDiscovery() {
        return "governed_domain_discovery".equals(semanticIntentClass);
    }
}
