package org.praxisplatform.config.ai.authoring;

import java.util.Optional;

public class AgenticAuthoringDefaultToolLoopPlanner implements AgenticAuthoringToolLoopPlanner {

    @Override
    public Optional<AgenticAuthoringToolCall> nextTool(AgenticAuthoringToolLoopContext context) {
        if (!"repairOrAsk".equals(context.phase())) {
            return Optional.empty();
        }
        AgenticAuthoringPreviewResult preview = context.preview();
        if (preview == null
                || preview.failureCodes() == null
                || preview.failureCodes().stream().noneMatch(code -> code != null && code.startsWith("semantic-preview-"))) {
            return Optional.empty();
        }
        AgenticAuthoringIntentResolutionResult intent = context.intentResolution();
        String artifactKind = intent == null ? "dashboard" : safe(intent.artifactKind(), "dashboard");
        return Optional.of(new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES,
                context.routeClass(),
                new AgenticAuthoringResourceCandidatesRequest(
                        safe(context.request() == null ? "" : context.request().userPrompt(), "dashboard"),
                        safe(context.request() == null ? "" : context.request().userPrompt(), ""),
                        artifactKind,
                        5)));
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
