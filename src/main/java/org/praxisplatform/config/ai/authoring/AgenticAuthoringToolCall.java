package org.praxisplatform.config.ai.authoring;

public record AgenticAuthoringToolCall(
        String name,
        String routeClass,
        Object payload
) {
}
