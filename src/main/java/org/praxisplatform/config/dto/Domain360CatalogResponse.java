package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record Domain360CatalogResponse(
        String schemaVersion,
        String tenantId,
        String environment,
        String serviceKey,
        String resourceKey,
        String query,
        String sourceMode,
        DomainCatalogReleaseResponse release,
        List<String> retrievalGuidance,
        Domain360Coverage coverage,
        List<Domain360Entry> resources,
        List<Domain360Entry> fields,
        List<Domain360Entry> capabilities,
        List<Domain360Entry> surfaces,
        List<Domain360Entry> actions,
        List<Domain360Entry> workflows,
        List<Domain360Entry> stats,
        List<Domain360Entry> optionSources,
        List<Domain360Entry> relationships,
        List<Domain360Entry> contracts,
        List<Domain360Entry> resolutions,
        List<Domain360Route> recommendedRoutes,
        List<Domain360Diagnostic> diagnostics
) {

    public record Domain360Coverage(
            int resourceCount,
            int fieldCount,
            int capabilityCount,
            int surfaceCount,
            int actionCount,
            int workflowCount,
            int statsCount,
            int optionSourceCount,
            int relationshipCount,
            int contractCount,
            int resolutionCount
    ) {
    }

    public record Domain360Entry(
            String key,
            String label,
            String kind,
            String itemType,
            String contextKey,
            String source,
            String summary,
            JsonNode metadata
    ) {
    }

    public record Domain360Route(
            String routeKey,
            String label,
            String kind,
            String blueprint,
            List<String> recommendedComponents,
            List<String> requiredInteractions,
            List<String> requiredCapabilities,
            String promptSeed,
            String confidence
    ) {
    }

    public record Domain360Diagnostic(
            String severity,
            String code,
            String message,
            String target
    ) {
    }
}
