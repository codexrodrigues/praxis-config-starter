package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgenticAuthoringResourceCandidatesResult(
        boolean valid,
        String tool,
        String retrievalQuery,
        String artifactKind,
        String assistantMessage,
        JsonNode assistantContent,
        List<AgenticAuthoringCandidate> candidates,
        List<AgenticAuthoringQuickReply> quickReplies,
        List<String> warnings,
        @JsonIgnore AgenticAuthoringResourceSearchFocus resourceSearchFocus,
        AgenticAuthoringConsultativeApiCatalogProjection consultativeProjection,
        @JsonIgnore Map<String, Object> diagnostics
) {
    static final String VERIFIED_OPERATIONS_CONTEXT_KEY = "verifiedOperations";
    static final String VERIFIED_RELATED_RESOURCE_SURFACES_CONTEXT_KEY = "verifiedRelatedResourceSurfaces";

    public AgenticAuthoringResourceCandidatesResult(
            boolean valid,
            String tool,
            String retrievalQuery,
            String artifactKind,
            String assistantMessage,
            List<AgenticAuthoringCandidate> candidates,
            List<AgenticAuthoringQuickReply> quickReplies,
            List<String> warnings) {
        this(
                valid,
                tool,
                retrievalQuery,
                artifactKind,
                assistantMessage,
                null,
                candidates,
                quickReplies,
                warnings,
                null,
                null,
                Map.of());
    }

    public AgenticAuthoringResourceCandidatesResult(
            boolean valid,
            String tool,
            String retrievalQuery,
            String artifactKind,
            String assistantMessage,
            JsonNode assistantContent,
            List<AgenticAuthoringCandidate> candidates,
            List<AgenticAuthoringQuickReply> quickReplies,
            List<String> warnings,
            AgenticAuthoringConsultativeApiCatalogProjection consultativeProjection) {
        this(
                valid,
                tool,
                retrievalQuery,
                artifactKind,
                assistantMessage,
                assistantContent,
                candidates,
                quickReplies,
                warnings,
                null,
                consultativeProjection,
                Map.of());
    }

    public AgenticAuthoringResourceCandidatesResult {
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    /**
     * Keeps exact operational verification available to the remainder of the
     * current authoring turn without publishing it as resource-search output.
     */
    @JsonIgnore
    List<AgenticAuthoringOperationalBindingVerificationService.OperationProjection> verifiedOperations() {
        Object value = diagnostics.get(VERIFIED_OPERATIONS_CONTEXT_KEY);
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(AgenticAuthoringOperationalBindingVerificationService.OperationProjection.class::isInstance)
                .map(AgenticAuthoringOperationalBindingVerificationService.OperationProjection.class::cast)
                .toList();
    }

    @JsonIgnore
    List<AgenticAuthoringOperationalBindingVerificationService.RelatedResourceSurfaceProjection>
            verifiedRelatedResourceSurfaces() {
        Object value = diagnostics.get(VERIFIED_RELATED_RESOURCE_SURFACES_CONTEXT_KEY);
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(AgenticAuthoringOperationalBindingVerificationService.RelatedResourceSurfaceProjection.class::isInstance)
                .map(AgenticAuthoringOperationalBindingVerificationService.RelatedResourceSurfaceProjection.class::cast)
                .toList();
    }

    @JsonIgnore
    Map<String, Object> safeDiagnostics() {
        if (!diagnostics.containsKey(VERIFIED_OPERATIONS_CONTEXT_KEY)
                && !diagnostics.containsKey(VERIFIED_RELATED_RESOURCE_SURFACES_CONTEXT_KEY)) {
            return diagnostics;
        }
        Map<String, Object> safe = new LinkedHashMap<>(diagnostics);
        safe.remove(VERIFIED_OPERATIONS_CONTEXT_KEY);
        safe.remove(VERIFIED_RELATED_RESOURCE_SURFACES_CONTEXT_KEY);
        return Map.copyOf(safe);
    }
}
