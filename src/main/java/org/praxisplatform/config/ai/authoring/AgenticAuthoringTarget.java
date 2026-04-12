package org.praxisplatform.config.ai.authoring;

public record AgenticAuthoringTarget(
        String widgetKey,
        String componentId,
        String resourcePath,
        String schemaUrl,
        String submitUrl,
        String submitMethod
) {
}
