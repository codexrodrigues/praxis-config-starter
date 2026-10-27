package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.praxisplatform.config.service.ResourceSurfaceCatalogFetchResult;

/**
 * Verifies {@code praxis-related-resource-outlet} widgets against governed {@code /schemas/surfaces}
 * contracts during UI composition preview.
 */
final class AgenticAuthoringRelatedResourceSurfaceVerifier {

    private static final Set<String> RELATED_RESOURCE_CHILD_OPERATIONS = Set.of(
            "FILTER",
            "LIST",
            "CREATE",
            "UPDATE",
            "DELETE");

    @FunctionalInterface
    interface SurfaceCatalogFetcher {
        ResourceSurfaceCatalogFetchResult fetch(
                String resourceKey,
                String requestBaseUrl,
                String tenantId,
                String userId,
                String environment);
    }

    record RelatedResourceSurfaceVerification(
            boolean valid,
            JsonNode uiCompositionPlan,
            List<String> failureCodes,
            List<String> warnings) {

        static RelatedResourceSurfaceVerification success(JsonNode uiCompositionPlan) {
            return new RelatedResourceSurfaceVerification(true, uiCompositionPlan, List.of(), List.of());
        }
    }

    RelatedResourceSurfaceVerification verifyRelatedResourceSurfaces(
            AgenticAuthoringPlanRequest request,
            JsonNode uiCompositionPlan,
            String schemaBaseUrl,
            String tenantId,
            String userId,
            String environment,
            SurfaceCatalogFetcher surfaceCatalogFetcher) {
        if (!(uiCompositionPlan instanceof ObjectNode plan) || !plan.path("widgets").isArray()) {
            return RelatedResourceSurfaceVerification.success(uiCompositionPlan);
        }
        List<String> failureCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean foundRelatedOutlet = false;
        for (JsonNode widget : plan.path("widgets")) {
            if (!"praxis-related-resource-outlet".equals(widget.path("componentId").asText(""))) {
                continue;
            }
            foundRelatedOutlet = true;
            String surfaceId = widget.path("inputs").path("surfaceId").asText("").trim();
            String parentResourcePath = businessResourcePath(
                    widget.path("inputs").path("parentResourcePath").asText(""));
            ObjectNode grounding = relatedResourceGrounding(plan);
            grounding.put("schemaVersion", "praxis-related-resource-authoring-grounding.v1");
            grounding.put("surfaceId", surfaceId);
            grounding.put("parentResourcePath", parentResourcePath);
            grounding.put("relationshipAuthoredByComponent", false);
            if (parentResourcePath.isBlank()) {
                grounding.put("status", "invalid-authoring-input");
                addOnce(failureCodes, "related-resource-authoring-input-incomplete");
                continue;
            }
            if (surfaceId.isBlank()) {
                grounding.put("status", "surface-selection-pending");
            }
            String parentResourceKey = relatedResourceParentResourceKey(request, parentResourcePath);
            if (parentResourceKey.isBlank()) {
                grounding.put("status", "parent-resource-key-unresolved");
                addOnce(failureCodes, "related-resource-parent-resource-key-unresolved");
                continue;
            }
            grounding.put("parentResourceKey", parentResourceKey);
            ResourceSurfaceCatalogFetchResult catalogResult = surfaceCatalogFetcher == null
                    ? null
                    : surfaceCatalogFetcher.fetch(
                            parentResourceKey,
                            schemaBaseUrl,
                            tenantId,
                            userId,
                            environment);
            if (catalogResult == null || !catalogResult.isSuccess()) {
                String status = surfaceFetchStatus(catalogResult);
                grounding.put("status", "surface-catalog-" + status);
                addOnce(failureCodes, "related-resource-surface-catalog-" + status);
                continue;
            }
            JsonNode catalog = catalogResult.getCatalog();
            if (!parentResourcePath.equals(businessResourcePath(catalog.path("resourcePath").asText("")))) {
                grounding.put("status", "parent-resource-path-mismatch");
                addOnce(failureCodes, "related-resource-parent-resource-path-mismatch");
                continue;
            }
            JsonNode surface = resolveRelatedResourceSurface(
                    catalog.path("surfaces"), surfaceId, parentResourceKey);
            if (!surface.isObject()
                    || !parentResourceKey.equals(surface.path("resourceKey").asText(""))) {
                if (surfaceId.isBlank()) {
                    grounding.put("status", "surface-required");
                    addOnce(failureCodes, "related-resource-target-surface-required");
                } else {
                    grounding.put("status", "surface-missing");
                    addOnce(failureCodes, "related-resource-target-surface-missing");
                }
                continue;
            }
            String resolvedSurfaceId = surface.path("id").asText("").trim();
            if (!surfaceId.equals(resolvedSurfaceId)
                    && widget.path("inputs") instanceof ObjectNode relatedInputs) {
                relatedInputs.put("surfaceId", resolvedSurfaceId);
                surfaceId = resolvedSurfaceId;
                grounding.put("surfaceId", resolvedSurfaceId);
            }
            if (!"ITEM".equals(surface.path("scope").asText(""))) {
                grounding.put("status", "surface-scope-incompatible");
                addOnce(failureCodes, "related-resource-target-surface-scope-incompatible");
                continue;
            }
            JsonNode availability = surface.path("availability");
            if (!availability.isObject()
                    || !availability.has("allowed")
                    || !availability.path("allowed").isBoolean()) {
                grounding.put("status", "surface-availability-unverified");
                addOnce(failureCodes, "related-resource-target-surface-availability-unverified");
                continue;
            }
            boolean allowed = availability.path("allowed").asBoolean(false);
            String availabilityReason = availability.path("reason").asText("").trim();
            if (!allowed && !"resource-context-required".equals(availabilityReason)) {
                grounding.put("status", "surface-unavailable");
                putText(grounding, "availabilityReason", availabilityReason);
                addOnce(failureCodes, "related-resource-target-surface-unavailable");
                continue;
            }
            JsonNode relation = surface.path("relatedResource");
            if (!completeRelatedResourceContract(surface, relation, parentResourceKey)) {
                grounding.put("status", "related-resource-contract-incomplete");
                addOnce(failureCodes, "related-resource-contract-incomplete");
                continue;
            }
            grounding.put("status", "verified");
            grounding.put("source", "schemas.surfaces");
            grounding.put("childResourceKey", relation.path("childResourceKey").asText(""));
            grounding.put("childResourcePath", relation.path("childResourcePath").asText(""));
            grounding.put("childParentField", relation.path("childParentField").asText(""));
            grounding.set("childOperations", relation.path("childOperations").deepCopy());
            addSourceRef(plan, "/schemas/surfaces?resource=" + parentResourceKey);
            addOnce(warnings, "related-resource-target-surface-verified");
        }
        return foundRelatedOutlet
                ? new RelatedResourceSurfaceVerification(
                        failureCodes.isEmpty(),
                        plan,
                        List.copyOf(failureCodes),
                        List.copyOf(warnings))
                : RelatedResourceSurfaceVerification.success(plan);
    }

    static JsonNode findSurface(JsonNode surfaces, String surfaceId) {
        if (!surfaces.isArray() || surfaceId == null || surfaceId.isBlank()) {
            return MissingNode.getInstance();
        }
        for (JsonNode surface : surfaces) {
            if (surfaceId.equals(surface.path("id").asText(""))) {
                return surface;
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode resolveRelatedResourceSurface(
            JsonNode surfaces,
            String surfaceRef,
            String parentResourceKey) {
        JsonNode directSurface = findSurface(surfaces, surfaceRef);
        if (directSurface.isObject()
                || !surfaces.isArray()
                || parentResourceKey == null
                || parentResourceKey.isBlank()) {
            return directSurface;
        }
        if (surfaceRef == null || surfaceRef.isBlank()) {
            JsonNode uniqueRelatedSurface = MissingNode.getInstance();
            for (JsonNode surface : surfaces) {
                if (parentResourceKey.equals(surface.path("resourceKey").asText("").trim())
                        && surface.path("relatedResource").isObject()) {
                    if (uniqueRelatedSurface.isObject()) {
                        return MissingNode.getInstance();
                    }
                    uniqueRelatedSurface = surface;
                }
            }
            return uniqueRelatedSurface;
        }
        for (JsonNode surface : surfaces) {
            String surfaceId = surface.path("id").asText("").trim();
            String resourceKey = surface.path("resourceKey").asText("").trim();
            if (!surfaceId.isBlank()
                    && parentResourceKey.equals(resourceKey)
                    && surfaceRef.equals(resourceKey + ".surface." + surfaceId)) {
                return surface;
            }
        }
        return MissingNode.getInstance();
    }

    private ObjectNode relatedResourceGrounding(ObjectNode plan) {
        ObjectNode diagnostics = plan.path("diagnostics") instanceof ObjectNode currentDiagnostics
                ? currentDiagnostics
                : plan.putObject("diagnostics");
        return diagnostics.path("relatedResourceGrounding") instanceof ObjectNode currentGrounding
                ? currentGrounding
                : diagnostics.putObject("relatedResourceGrounding");
    }

    private String relatedResourceParentResourceKey(
            AgenticAuthoringPlanRequest request,
            String parentResourcePath) {
        JsonNode contextHints = request == null || request.contextHints() == null
                ? MissingNode.getInstance()
                : request.contextHints();
        JsonNode verifiedOperations = contextHints.path("verifiedDomainOperations").path("entries");
        if (verifiedOperations.isArray()) {
            for (JsonNode operation : verifiedOperations) {
                if (parentResourcePath.equals(businessResourcePath(operation.path("resourcePath").asText("")))) {
                    String resourceKey = operation.path("resourceKey").asText("").trim();
                    if (!resourceKey.isBlank()) {
                        return resourceKey;
                    }
                }
            }
        }
        String domainCatalogResourceKey = contextHints.path("domainCatalog").path("resourceKey").asText("").trim();
        if (!domainCatalogResourceKey.isBlank()) {
            return domainCatalogResourceKey;
        }
        String normalizedPath = value(parentResourcePath);
        if (normalizedPath.startsWith("/api/")) {
            normalizedPath = normalizedPath.substring(5);
        } else if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        return normalizedPath.replace('/', '.');
    }

    private boolean completeRelatedResourceContract(
            JsonNode surface,
            JsonNode relation,
            String expectedParentResourceKey) {
        String parentResourceKey = relation.path("parentResourceKey").asText("").trim();
        String parentIdPathVariable = relation.path("parentIdPathVariable").asText("").trim();
        String childResourceKey = relation.path("childResourceKey").asText("").trim();
        String childResourcePath = businessResourcePath(
                relation.path("childResourcePath").asText(""));
        String childParentField = relation.path("childParentField").asText("").trim();
        String selectionKeyField = relation.path("selectionKeyField").asText("").trim();
        String surfacePath = value(surface.path("path").asText(""));
        String parentPlaceholder = "{" + parentIdPathVariable + "}";
        String childPathWithoutParentPlaceholder = childResourcePath.replace(parentPlaceholder, "");
        JsonNode childOperations = relation.path("childOperations");
        return relation.isObject()
                && List.of("VIEW", "READ_PROJECTION").contains(surface.path("kind").asText(""))
                && "COLLECTION".equals(surface.path("responseCardinality").asText(""))
                && expectedParentResourceKey.equals(parentResourceKey)
                && parentIdPathVariable.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                && safeRelatedResourcePath(surfacePath)
                && surfacePath.contains(parentPlaceholder)
                && childResourceKey.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
                && safeRelatedResourcePath(childResourcePath)
                && !childPathWithoutParentPlaceholder.contains("{")
                && !childPathWithoutParentPlaceholder.contains("}")
                && childParentField.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                && relation.path("selectable").isBoolean()
                && selectionKeyField.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                && childOperations.isArray()
                && allRelatedResourceChildOperationsAreCanonical(childOperations);
    }

    private boolean safeRelatedResourcePath(String path) {
        String pathWithoutPlaceholders = value(path).replaceAll(
                "\\{[A-Za-z_][A-Za-z0-9_]*}",
                "");
        return path.startsWith("/api/")
                && !path.contains("..")
                && !path.contains("?")
                && !path.contains("#")
                && !path.contains("://")
                && !path.contains("\\")
                && path.chars().noneMatch(Character::isWhitespace)
                && !pathWithoutPlaceholders.contains("{")
                && !pathWithoutPlaceholders.contains("}");
    }

    private boolean allRelatedResourceChildOperationsAreCanonical(JsonNode operations) {
        for (JsonNode operation : operations) {
            if (!operation.isTextual()
                    || !RELATED_RESOURCE_CHILD_OPERATIONS.contains(operation.asText(""))) {
                return false;
            }
        }
        return true;
    }

    static void addSourceRef(ObjectNode plan, String sourceRef) {
        if (plan == null || sourceRef == null || sourceRef.isBlank()) {
            return;
        }
        ArrayNode sourceRefs = plan.path("sourceRefs") instanceof ArrayNode current
                ? current
                : plan.putArray("sourceRefs");
        for (JsonNode existing : sourceRefs) {
            if (sourceRef.equals(existing.asText(""))) {
                return;
            }
        }
        sourceRefs.add(sourceRef);
    }

    private String businessResourcePath(String path) {
        String resolved = value(path).trim();
        int queryIndex = resolved.indexOf('?');
        if (queryIndex >= 0) {
            resolved = resolved.substring(0, queryIndex);
        }
        resolved = resolved.replaceAll("/+$", "");
        if (resolved.endsWith("/filter/cursor")) {
            return resolved.substring(0, resolved.length() - "/filter/cursor".length());
        }
        if (resolved.endsWith("/filter")) {
            return resolved.substring(0, resolved.length() - "/filter".length());
        }
        if (resolved.endsWith("/all")) {
            return resolved.substring(0, resolved.length() - "/all".length());
        }
        if (resolved.endsWith("/by-ids")) {
            return resolved.substring(0, resolved.length() - "/by-ids".length());
        }
        if (resolved.endsWith("/options/filter")) {
            return resolved.substring(0, resolved.length() - "/options/filter".length());
        }
        if (resolved.endsWith("/options/by-ids")) {
            return resolved.substring(0, resolved.length() - "/options/by-ids".length());
        }
        if (resolved.endsWith("/stats/group-by")) {
            return resolved.substring(0, resolved.length() - "/stats/group-by".length());
        }
        if (resolved.endsWith("/stats/timeseries")) {
            return resolved.substring(0, resolved.length() - "/stats/timeseries".length());
        }
        if (resolved.endsWith("/stats/distribution")) {
            return resolved.substring(0, resolved.length() - "/stats/distribution".length());
        }
        if (resolved.endsWith("/stats")) {
            return resolved.substring(0, resolved.length() - "/stats".length());
        }
        return resolved;
    }

    private String surfaceFetchStatus(ResourceSurfaceCatalogFetchResult result) {
        return result == null || result.getStatus() == null
                ? "unavailable"
                : result.getStatus().name().toLowerCase(Locale.ROOT);
    }

    private void putText(ObjectNode target, String field, String text) {
        if (target != null && text != null && !text.isBlank()) {
            target.put(field, text.trim());
        }
    }

    private void addOnce(List<String> target, String value) {
        if (target != null && value != null && !value.isBlank() && !target.contains(value)) {
            target.add(value);
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
