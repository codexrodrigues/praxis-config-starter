package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class AgenticAuthoringIntentResolutionContext {

    private final AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer;

    AgenticAuthoringIntentResolutionContext(ObjectMapper objectMapper) {
        this.currentPageAnalyzer = new AgenticAuthoringCurrentPageAnalyzer(
                Objects.requireNonNull(objectMapper, "objectMapper must not be null"));
    }

    AgenticAuthoringIntentResolutionResult enrich(
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode currentPage) {
        if (intentResolution == null || currentPage == null || !currentPage.isObject()) {
            return intentResolution;
        }
        JsonNode currentSummary = intentResolution.currentPageSummary();
        if (hasFieldAwareFormSummary(currentSummary)) {
            return intentResolution;
        }
        JsonNode derivedSummary = currentPageAnalyzer.summarize(currentPage);
        if (!hasAnyFormSummary(derivedSummary)) {
            return intentResolution;
        }
        List<String> warnings = new ArrayList<>(intentResolution.warnings() == null
                ? List.of()
                : intentResolution.warnings());
        if (!warnings.contains("current-page-summary-derived")) {
            warnings.add("current-page-summary-derived");
        }
        return new AgenticAuthoringIntentResolutionResult(
                intentResolution.valid(),
                intentResolution.operationKind(),
                intentResolution.artifactKind(),
                intentResolution.changeKind(),
                intentResolution.authoringProfile(),
                intentResolution.targetApp(),
                intentResolution.targetComponentId(),
                intentResolution.target(),
                intentResolution.selectedCandidate(),
                intentResolution.candidates(),
                intentResolution.gate(),
                intentResolution.effectivePrompt(),
                intentResolution.assistantMessage(),
                intentResolution.apiCatalogAnswer(),
                intentResolution.quickReplies(),
                intentResolution.pendingClarification(),
                intentResolution.clarificationQuestions(),
                List.copyOf(warnings),
                intentResolution.failureCodes(),
                derivedSummary,
                intentResolution.llmDiagnostics(),
                intentResolution.visualizationDecision(),
                intentResolution.semanticDecision());
    }

    private boolean hasFieldAwareFormSummary(JsonNode summary) {
        if (hasStructuralFieldSummary(summary)) {
            return true;
        }
        if (!hasAnyFormSummary(summary)) {
            return false;
        }
        for (JsonNode formWidget : summary.path("formWidgets")) {
            if (formWidget.path("fieldNames").isArray()
                    && formWidget.path("localFieldNames").isArray()
                    && formWidget.path("serverBackedOverrideNames").isArray()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyFormSummary(JsonNode summary) {
        return hasAnyStructuralSummary(summary)
                || summary != null
                && summary.path("formWidgets").isArray()
                && !summary.path("formWidgets").isEmpty();
    }

    private boolean hasAnyStructuralSummary(JsonNode summary) {
        return summary != null
                && summary.path("structuralInspection").path("widgets").isArray()
                && !summary.path("structuralInspection").path("widgets").isEmpty();
    }

    private boolean hasStructuralFieldSummary(JsonNode summary) {
        return summary != null
                && summary.path("structuralInspection").path("fields").isArray()
                && !summary.path("structuralInspection").path("fields").isEmpty();
    }
}
