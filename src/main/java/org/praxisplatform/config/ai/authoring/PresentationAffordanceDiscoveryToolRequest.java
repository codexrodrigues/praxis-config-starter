package org.praxisplatform.config.ai.authoring;

public record PresentationAffordanceDiscoveryToolRequest(
        String componentId,
        String targetComponentId,
        String targetKind,
        String targetField,
        String columnField,
        String dataType,
        String outputType,
        String inferredType,
        String query,
        Integer limit) {
}
