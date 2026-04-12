package org.praxisplatform.config.ai.authoring;

public record AgenticAuthoringDryRunReport(
        String generatedAt,
        String profileId,
        String artifactsDir,
        AgenticAuthoringDryRunResult result
) {
}
