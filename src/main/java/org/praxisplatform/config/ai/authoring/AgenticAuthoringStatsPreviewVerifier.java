package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.ResourceCapabilitiesFetchResult;
import org.praxisplatform.config.service.ResourceSurfaceCatalogFetchResult;
import org.praxisplatform.config.service.SchemaFetchResult;

/**
 * Verifies governed analytics projections ({@code x-ui.analytics.projections}) and
 * {@code praxis-chart} stats capabilities ({@code resource.capabilities.stats}) during
 * UI composition preview.
 */
final class AgenticAuthoringStatsPreviewVerifier {

    private static final Set<String> SEMANTIC_AXIS_STOP_WORDS = Set.of(
            "por",
            "para",
            "com",
            "dos",
            "das",
            "the",
            "and",
            "total",
            "registros",
            "registro");

    @FunctionalInterface
    interface CapabilitiesFetcher {
        ResourceCapabilitiesFetchResult fetch(
                String resourcePath,
                String requestBaseUrl,
                String tenantId,
                String userId,
                String environment);
    }

    @FunctionalInterface
    interface PrincipalAwareSchemaFetcher {
        SchemaFetchResult fetch(
                AiSchemaContext context,
                String schemaBaseUrl,
                String tenantId,
                String userId,
                String environment);
    }

    @FunctionalInterface
    interface SurfaceCatalogFetcher {
        ResourceSurfaceCatalogFetchResult fetch(
                String resourceKey,
                String requestBaseUrl,
                String tenantId,
                String userId,
                String environment);
    }

    private final ObjectMapper objectMapper;

    AgenticAuthoringStatsPreviewVerifier(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    AgenticAuthoringPlanRequest withGovernedAnalyticsContext(
            AgenticAuthoringPlanRequest request,
            String schemaBaseUrl,
            String tenantId,
            String userId,
            String environment,
            PrincipalAwareSchemaFetcher schemaFetcher,
            CapabilitiesFetcher capabilitiesFetcher,
            SurfaceCatalogFetcher surfaceCatalogFetcher) {
        String requestedOperation = requestedGovernedAnalyticsOperation(request);
        if (request == null || requestedOperation.isBlank()) {
            return request;
        }
        AgenticAuthoringCandidate candidate = request.intentResolution() == null
                ? null
                : request.intentResolution().selectedCandidate();
        String resourcePath = businessResourcePath(firstNonBlank(
                candidate == null ? "" : candidate.resourcePath(),
                candidate == null ? "" : candidate.submitUrl()));
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode grounding = contextHints.putObject("governedAnalytics");
        grounding.put("schemaVersion", "praxis-agentic-authoring-governed-analytics.v1");
        grounding.put("requestedOperation", requestedOperation);
        grounding.put("resourcePath", resourcePath);
        ArrayNode sources = grounding.putArray("sources");
        sources.add("resource.capabilities");
        sources.add("schemas.filtered:x-ui.analytics");

        if (resourcePath.isBlank() || capabilitiesFetcher == null) {
            grounding.put("status", "invalid-resource");
            return copyWithContextHints(request, contextHints);
        }
        ResourceCapabilitiesFetchResult capabilitiesResult = capabilitiesFetcher.fetch(
                resourcePath,
                schemaBaseUrl,
                tenantId,
                userId,
                environment);
        if (capabilitiesResult == null || !capabilitiesResult.isSuccess()) {
            grounding.put("status", "capabilities-" + fetchStatus(capabilitiesResult));
            return copyWithContextHints(request, contextHints);
        }
        JsonNode operationCapability = canonicalStatsOperationCapability(
                capabilitiesResult.getCapabilities(),
                requestedOperation);
        if (!operationCapability.isObject() || !operationCapability.path("supported").asBoolean(false)) {
            grounding.put("status", "operation-unsupported");
            return copyWithContextHints(request, contextHints);
        }
        grounding.put("capabilityVerified", true);
        grounding.put("operationId", operationCapability.path("id").asText(
                canonicalStatsOperationId(requestedOperation)));
        JsonNode availability = operationCapability.path("availability");
        if (!availability.isObject() || !availability.has("allowed") || !availability.path("allowed").isBoolean()) {
            grounding.put("status", "operation-availability-unverified");
            return copyWithContextHints(request, contextHints);
        }
        ObjectNode operationAvailability = grounding.putObject("operationAvailability");
        operationAvailability.put("allowed", availability.path("allowed").asBoolean());
        putText(operationAvailability, "reason", availability.path("reason").asText(""));
        putText(operationAvailability, "accessClass", availability.path("metadata").path("accessClass").asText(""));
        if (!availability.path("allowed").asBoolean()) {
            grounding.put("status", "operation-unavailable");
            putText(grounding, "availabilityReason", availability.path("reason").asText(""));
            return copyWithContextHints(request, contextHints);
        }

        AiSchemaContext analyticsSchemaContext = AiSchemaContext.builder()
                .path(resourcePath + "/stats/" + requestedOperation)
                .operation("post")
                .schemaType("response")
                .build();
        SchemaFetchResult schemaResult = schemaFetcher == null
                ? null
                : schemaFetcher.fetch(
                        analyticsSchemaContext,
                        schemaBaseUrl,
                        tenantId,
                        userId,
                        environment);
        if (schemaResult == null || !schemaResult.isSuccess()) {
            grounding.put("status", "schema-" + schemaFetchStatus(schemaResult));
            return copyWithContextHints(request, contextHints);
        }
        grounding.put("schemaVerified", true);
        Optional<ObjectNode> projection = governedAnalyticsProjection(
                schemaResult.getSchema(),
                capabilitiesResult.getCapabilities(),
                resourcePath,
                requestedOperation);
        if (projection.isEmpty()) {
            grounding.put("status", missingKeyFilterBinding(
                    schemaResult.getSchema(),
                    resourcePath,
                    requestedOperation)
                            ? "key-filter-binding-required"
                            : "projection-ineligible");
            return copyWithContextHints(request, contextHints);
        }
        ObjectNode sanitizedProjection = projection.get();
        JsonNode projectionInteractions = sanitizedProjection.path("interactions");
        boolean crossFilterEnabled = projectionInteractions.path("crossFilter").asBoolean(false);
        JsonNode recordOpen = projectionInteractions.path("recordOpen");
        if (crossFilterEnabled || recordOpen.isObject()) {
            JsonNode nominalCapability = capabilityRoot(capabilitiesResult.getCapabilities())
                    .path("operations")
                    .path("filter");
            if (!nominalCapability.isObject() || !nominalCapability.path("supported").asBoolean(false)) {
                grounding.put("status", "nominal-operation-unsupported");
                return copyWithContextHints(request, contextHints);
            }
            JsonNode nominalAvailability = nominalCapability.path("availability");
            if (!nominalAvailability.isObject()
                    || !nominalAvailability.has("allowed")
                    || !nominalAvailability.path("allowed").isBoolean()) {
                grounding.put("status", "nominal-operation-availability-unverified");
                return copyWithContextHints(request, contextHints);
            }
            ObjectNode publishedNominalAvailability = grounding.putObject("nominalOperationAvailability");
            publishedNominalAvailability.put("operationId", nominalCapability.path("id").asText("filter"));
            publishedNominalAvailability.put("allowed", nominalAvailability.path("allowed").asBoolean());
            putText(publishedNominalAvailability, "reason", nominalAvailability.path("reason").asText(""));
            if (!nominalAvailability.path("allowed").asBoolean()) {
                removeNominalInteractions(sanitizedProjection);
                crossFilterEnabled = false;
                recordOpen = MissingNode.getInstance();
            }
        }
        if (crossFilterEnabled) {
            String keyFilterField = sanitizedProjection.path("bindings")
                    .path("primaryDimension")
                    .path("keyFilterField")
                    .asText("")
                    .trim();
            if (keyFilterField.isBlank()) {
                grounding.put("status", "key-filter-binding-required");
                return copyWithContextHints(request, contextHints);
            }
            AiSchemaContext filterSchemaContext = AiSchemaContext.builder()
                    .path(resourcePath + "/filter")
                    .operation("post")
                    .schemaType("request")
                    .build();
            SchemaFetchResult filterSchemaResult = schemaFetcher.fetch(
                    filterSchemaContext,
                    schemaBaseUrl,
                    tenantId,
                    userId,
                    environment);
            if (filterSchemaResult == null || !filterSchemaResult.isSuccess()) {
                grounding.put("status", "key-filter-schema-" + schemaFetchStatus(filterSchemaResult));
                return copyWithContextHints(request, contextHints);
            }
            Map<String, SchemaFieldDescriptor> filterFields = schemaFields(filterSchemaResult.getSchema());
            SchemaFieldDescriptor targetField = filterFields.get(normalize(keyFilterField));
            if (targetField == null || !keyFilterField.equals(targetField.name())) {
                grounding.put("status", "key-filter-field-missing");
                return copyWithContextHints(request, contextHints);
            }
            if (!supportsBucketKey(targetField)) {
                grounding.put("status", "key-filter-field-incompatible");
                return copyWithContextHints(request, contextHints);
            }
            sources.add("schemas.filtered:filter-request");
            ObjectNode keyFilterBinding = grounding.putObject("keyFilterBinding");
            keyFilterBinding.put("field", targetField.name());
            keyFilterBinding.put("type", targetField.type());
            keyFilterBinding.put("multiple", targetField.multiple()
                    || "array".equals(normalize(targetField.type())));
            putText(keyFilterBinding, "itemType", targetField.itemType());
            keyFilterBinding.put("schemaVerified", true);
        }
        if (recordOpen.isObject()) {
            String sourceIdentityField = recordOpen.path("sourceIdentityField").asText("").trim();
            String targetResourceKey = recordOpen.path("target").path("resourceKey").asText("").trim();
            String targetSurfaceId = recordOpen.path("target").path("surfaceId").asText("").trim();
            if (sourceIdentityField.isBlank() || targetResourceKey.isBlank() || targetSurfaceId.isBlank()) {
                grounding.put("status", "record-open-invalid");
                return copyWithContextHints(request, contextHints);
            }

            AiSchemaContext nominalResponseSchemaContext = AiSchemaContext.builder()
                    .path(resourcePath + "/filter")
                    .operation("post")
                    .schemaType("response")
                    .build();
            SchemaFetchResult nominalResponseSchema = schemaFetcher.fetch(
                    nominalResponseSchemaContext,
                    schemaBaseUrl,
                    tenantId,
                    userId,
                    environment);
            if (nominalResponseSchema == null || !nominalResponseSchema.isSuccess()) {
                grounding.put("status", "record-open-source-schema-" + schemaFetchStatus(nominalResponseSchema));
                return copyWithContextHints(request, contextHints);
            }
            Map<String, SchemaFieldDescriptor> nominalFields = schemaFields(nominalResponseSchema.getSchema());
            SchemaFieldDescriptor sourceField = nominalFields.get(normalize(sourceIdentityField));
            if (sourceField == null || !sourceIdentityField.equals(sourceField.name())) {
                grounding.put("status", "record-open-source-field-missing");
                return copyWithContextHints(request, contextHints);
            }
            if (!supportsRecordIdentity(sourceField)) {
                grounding.put("status", "record-open-source-field-incompatible");
                return copyWithContextHints(request, contextHints);
            }
            sources.add("schemas.filtered:filter-response");

            ResourceSurfaceCatalogFetchResult surfaceCatalogResult = surfaceCatalogFetcher == null
                    ? null
                    : surfaceCatalogFetcher.fetch(
                            targetResourceKey,
                            schemaBaseUrl,
                            tenantId,
                            userId,
                            environment);
            if (surfaceCatalogResult == null || !surfaceCatalogResult.isSuccess()) {
                grounding.put("status", "record-open-surface-catalog-" + surfaceFetchStatus(surfaceCatalogResult));
                return copyWithContextHints(request, contextHints);
            }
            JsonNode surfaceCatalog = surfaceCatalogResult.getCatalog();
            JsonNode targetSurface = AgenticAuthoringRelatedResourceSurfaceVerifier.findSurface(
                    surfaceCatalog.path("surfaces"),
                    targetSurfaceId);
            if (!targetSurface.isObject()
                    || !targetResourceKey.equals(targetSurface.path("resourceKey").asText(""))) {
                grounding.put("status", "record-open-surface-missing");
                return copyWithContextHints(request, contextHints);
            }
            if (!"ITEM".equals(targetSurface.path("scope").asText(""))) {
                grounding.put("status", "record-open-surface-scope-incompatible");
                return copyWithContextHints(request, contextHints);
            }
            JsonNode surfaceAvailability = targetSurface.path("availability");
            if (!surfaceAvailability.isObject()
                    || !surfaceAvailability.has("allowed")
                    || !surfaceAvailability.path("allowed").isBoolean()) {
                grounding.put("status", "record-open-surface-availability-unverified");
                return copyWithContextHints(request, contextHints);
            }
            boolean surfaceAllowed = surfaceAvailability.path("allowed").asBoolean(false);
            String surfaceAvailabilityReason = surfaceAvailability.path("reason").asText("").trim();
            if (!surfaceAllowed && !"resource-context-required".equals(surfaceAvailabilityReason)) {
                grounding.put("status", "record-open-surface-unavailable");
                putText(grounding, "availabilityReason", surfaceAvailabilityReason);
                return copyWithContextHints(request, contextHints);
            }

            sources.add("schemas.surfaces:target-resource");
            ObjectNode resolution = grounding.putObject("recordOpenResolution");
            resolution.put("sourceIdentityField", sourceIdentityField);
            resolution.put("sourceFieldType", sourceField.type());
            resolution.put("targetResourceKey", targetResourceKey);
            resolution.put("targetResourcePath", surfaceCatalog.path("resourcePath").asText(""));
            resolution.put("targetSurfaceId", targetSurfaceId);
            resolution.put("targetSurfaceKind", targetSurface.path("kind").asText(""));
            resolution.put("targetSurfaceScope", targetSurface.path("scope").asText(""));
            resolution.put("targetOperationId", targetSurface.path("operationId").asText(""));
            resolution.put("availability", surfaceAllowed ? "allowed" : surfaceAvailabilityReason);
            resolution.put("schemaVerified", true);
            resolution.put("catalogEndpointUrl", value(surfaceCatalogResult.getEndpointUrl()));
        }
        grounding.put("status", "verified");
        grounding.put("capabilityEndpointUrl", value(capabilitiesResult.getEndpointUrl()));
        grounding.put("schemaEndpointUrl", value(schemaResult.getEndpointUrl()));
        grounding.set("projection", sanitizedProjection);
        return copyWithContextHints(request, contextHints);
    }

    JsonNode verifyStatsCapabilities(
            AgenticAuthoringPlanRequest request,
            JsonNode uiCompositionPlan,
            List<String> warnings,
            String requestBaseUrl,
            String tenantId,
            String userId,
            String environment,
            boolean capabilitiesRetrievalConfigured,
            CapabilitiesFetcher capabilitiesFetcher) {
        if (!capabilitiesRetrievalConfigured
                || uiCompositionPlan == null
                || uiCompositionPlan.isMissingNode()
                || !AgenticAuthoringSemanticMaterializationPolicy.containsComponent(uiCompositionPlan, "praxis-chart")) {
            return uiCompositionPlan;
        }
        AgenticAuthoringCandidate candidate = request == null || request.intentResolution() == null
                ? null
                : request.intentResolution().selectedCandidate();
        String resourcePath = businessResourcePath(firstNonBlank(
                candidate == null ? "" : candidate.resourcePath(),
                candidate == null ? "" : candidate.submitUrl()));
        JsonNode copy = uiCompositionPlan.deepCopy();
        if (!(copy instanceof ObjectNode objectNode)) {
            return copy;
        }
        if (resourcePath.isBlank()) {
            markStatsAxesUnsupported(objectNode, "invalid-resource", warnings);
            return objectNode;
        }

        ResourceCapabilitiesFetchResult result = capabilitiesFetcher == null
                ? null
                : capabilitiesFetcher.fetch(
                        resourcePath,
                        requestBaseUrl,
                        tenantId,
                        userId,
                        environment);
        if (result == null || !result.isSuccess()) {
            String status = result == null || result.getStatus() == null
                    ? "unavailable"
                    : result.getStatus().name().toLowerCase(Locale.ROOT);
            markStatsAxesUnsupported(objectNode, status, warnings);
            return objectNode;
        }

        List<StatsCapabilityFieldDescriptor> capabilityFields = statsCapabilityFields(result.getCapabilities());
        if (capabilityFields.isEmpty()) {
            markStatsAxesUnsupported(objectNode, "no-fields", warnings);
            return objectNode;
        }
        int groundedCharts = 0;
        JsonNode widgets = objectNode.path("widgets");
        if (widgets.isArray()) {
            for (JsonNode widget : widgets) {
                if (!(widget instanceof ObjectNode widgetObject) || !isPraxisStatsChart(widgetObject)) {
                    continue;
                }
                ObjectNode config = widgetObject.path("inputs").path("config") instanceof ObjectNode value
                        ? value
                        : null;
                ObjectNode query = config != null && config.path("dataSource").path("query") instanceof ObjectNode value
                        ? value
                        : null;
                ObjectNode statsRequest = query != null && query.path("statsRequest") instanceof ObjectNode value
                        ? value
                        : null;
                ObjectNode semanticAxis = config != null && config.path("semanticAxis") instanceof ObjectNode value
                        ? value
                        : null;
                if (config == null || query == null || statsRequest == null || semanticAxis == null) {
                    markStatsAxisUnsupported(objectNode, semanticAxis, "invalid-chart-contract");
                    continue;
                }
                String operation = valueOrDefault(query.path("statsOperation").asText(""), "group-by");
                if ("comparison".equals(normalize(operation))
                        && !isCanonicalStatsOperationAvailableForPrincipal(
                                result.getCapabilities(),
                                "comparison")) {
                    markStatsAxisUnsupported(objectNode, semanticAxis, "operation-unavailable");
                    continue;
                }
                Optional<StatsCapabilityFieldDescriptor> dimension = resolveStatsDimensionCapability(
                        semanticAxis,
                        statsRequest.path("field").asText(""),
                        operation,
                        capabilityFields);
                if (dimension.isEmpty() && ("timeseries".equalsIgnoreCase(operation) || "time-series".equalsIgnoreCase(operation))) {
                    Optional<StatsCapabilityFieldDescriptor> groupByDimension = resolveStatsDimensionCapability(
                            semanticAxis,
                            statsRequest.path("field").asText(""),
                            "group-by",
                            capabilityFields);
                    if (groupByDimension.isPresent()) {
                        dimension = groupByDimension;
                        operation = "group-by";
                        query.put("statsOperation", "group-by");
                        query.remove("granularity");
                        statsRequest.remove("granularity");
                        statsRequest.remove("fillGaps");
                        if (!resourcePath.isBlank()) {
                            query.put("statsPath", resourcePath + "/stats/group-by");
                        }
                    }
                }
                if (dimension.isEmpty()) {
                    markStatsAxisUnsupported(objectNode, semanticAxis, "unsupported-dimension");
                    continue;
                }
                if ("comparison".equals(normalize(operation))
                        && !alignComparisonPeriodCapability(statsRequest, capabilityFields)) {
                    markStatsAxisUnsupported(objectNode, semanticAxis, "unsupported-comparison-period");
                    continue;
                }
                if (!alignStatsMetricCapability(config, capabilityFields)) {
                    markStatsAxisUnsupported(objectNode, semanticAxis, "unsupported-metric");
                    continue;
                }
                alignStatsExecutionField(query, statsRequest, dimension.get());
                markStatsAxisVerified(objectNode, semanticAxis, dimension.get(), result);
                groundedCharts++;
            }
        }
        int expectedCharts = countPraxisStatsCharts(objectNode);
        markResourceStatsGrounding(
                objectNode,
                resourcePath,
                result,
                capabilityFields.size(),
                groundedCharts,
                expectedCharts);
        if (groundedCharts < expectedCharts) {
            addWarningOnce(warnings, "semantic-axis-stats-capability-verification-unsupported");
        } else if (expectedCharts > 0) {
            addWarningOnce(warnings, "semantic-axis-stats-capability-verified");
        }
        return objectNode;
    }

    boolean containsUnverifiedStatsAxes(JsonNode uiCompositionPlan) {
        JsonNode axes = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return false;
        }
        for (JsonNode axis : axes) {
            if (axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            if (axis.path("statsVerified").isBoolean() && !axis.path("statsVerified").asBoolean()) {
                return true;
            }
        }
        return false;
    }

    private void removeNominalInteractions(ObjectNode projection) {
        JsonNode interactions = projection.path("interactions");
        if (interactions instanceof ObjectNode interactionObject) {
            interactionObject.put("crossFilter", false);
            interactionObject.remove("recordOpen");
        }
        JsonNode primaryDimension = projection.path("bindings").path("primaryDimension");
        if (primaryDimension instanceof ObjectNode primaryDimensionObject) {
            primaryDimensionObject.remove("keyFilterField");
        }
    }

    private String requestedGovernedAnalyticsOperation(AgenticAuthoringPlanRequest request) {
        if (request == null || request.intentResolution() == null) {
            return "";
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        AgenticAuthoringCandidate candidate = intent.selectedCandidate();
        if (candidate != null && (endsWithStatsOperation(candidate.resourcePath(), "comparison")
                || endsWithStatsOperation(candidate.submitUrl(), "comparison"))) {
            return "comparison";
        }
        return "";
    }

    private boolean endsWithStatsOperation(String path, String operation) {
        String value = path == null ? "" : path.trim().replaceAll("/+$", "");
        return value.endsWith("/stats/" + operation);
    }

    private JsonNode canonicalStatsOperationCapability(JsonNode capabilities, String operation) {
        JsonNode root = capabilityRoot(capabilities);
        String operationId = canonicalStatsOperationId(operation);
        return operationId.isBlank() ? MissingNode.getInstance() : root.path("operations").path(operationId);
    }

    private String canonicalStatsOperationId(String operation) {
        return switch (operation) {
            case "comparison" -> "statsComparison";
            default -> "";
        };
    }

    private boolean isCanonicalStatsOperationAvailableForPrincipal(JsonNode capabilities, String operation) {
        JsonNode operationCapability = canonicalStatsOperationCapability(capabilities, operation);
        JsonNode availability = operationCapability.path("availability");
        return operationCapability.path("supported").asBoolean(false)
                && availability.isObject()
                && availability.path("allowed").isBoolean()
                && availability.path("allowed").asBoolean(false);
    }

    private JsonNode capabilityRoot(JsonNode capabilities) {
        JsonNode root = capabilities == null ? MissingNode.getInstance() : capabilities;
        return root.path("data").isObject() ? root.path("data") : root;
    }

    private boolean missingKeyFilterBinding(JsonNode schema, String resourcePath, String operation) {
        JsonNode projections = schema == null
                ? MissingNode.getInstance()
                : schema.path("x-ui").path("analytics").path("projections");
        if (!projections.isArray()) {
            return false;
        }
        for (JsonNode projection : projections) {
            JsonNode source = projection.path("source");
            if (operation.equals(source.path("operation").asText(""))
                    && sameResourcePath(resourcePath, source.path("resource").asText(""))
                    && projection.path("interactions").path("crossFilter").asBoolean(false)
                    && projection.path("bindings").path("primaryDimension")
                            .path("keyFilterField").asText("").isBlank()) {
                return true;
            }
        }
        return false;
    }

    private Optional<ObjectNode> governedAnalyticsProjection(
            JsonNode schema,
            JsonNode capabilities,
            String resourcePath,
            String operation) {
        JsonNode projections = schema == null
                ? MissingNode.getInstance()
                : schema.path("x-ui").path("analytics").path("projections");
        if (!projections.isArray()) {
            return Optional.empty();
        }
        List<StatsCapabilityFieldDescriptor> capabilityFields = statsCapabilityFields(capabilities);
        for (JsonNode projection : projections) {
            JsonNode source = projection.path("source");
            if (!"praxis.stats".equals(source.path("kind").asText(""))
                    || !operation.equals(source.path("operation").asText(""))
                    || !sameResourcePath(resourcePath, source.path("resource").asText(""))
                    || !eligibleComparisonProjection(projection, capabilityFields)) {
                continue;
            }
            return Optional.of(sanitizeAnalyticsProjection(projection));
        }
        return Optional.empty();
    }

    private boolean sameResourcePath(String left, String right) {
        return value(left).replaceAll("/+$", "").equals(value(right).replaceAll("/+$", ""));
    }

    private boolean eligibleComparisonProjection(
            JsonNode projection,
            List<StatsCapabilityFieldDescriptor> capabilityFields) {
        if (!"comparison".equals(projection.path("intent").asText(""))) {
            return false;
        }
        JsonNode bindings = projection.path("bindings");
        String dimensionField = bindings.path("primaryDimension").path("field").asText("");
        String periodField = bindings.path("comparisonPeriod").path("field").asText("");
        JsonNode period = bindings.path("comparisonPeriod");
        JsonNode metrics = bindings.path("primaryMetrics");
        if (dimensionField.isBlank()
                || periodField.isBlank()
                || period.path("timezone").asText("").isBlank()
                || period.path("preset").asText("").isBlank()
                || period.path("mode").asText("").isBlank()
                || !metrics.isArray()
                || metrics.isEmpty()) {
            return false;
        }
        boolean dimensionEligible = capabilityFields.stream()
                .anyMatch(field -> normalize(field.field()).equals(normalize(dimensionField))
                        && eligibleStatsDimension(field, "comparison"));
        boolean periodEligible = capabilityFields.stream()
                .anyMatch(field -> normalize(field.field()).equals(normalize(periodField))
                        && (field.timeSeriesEligible() || field.modes().contains("time-series")));
        if (!dimensionEligible || !periodEligible) {
            return false;
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (JsonNode metric : metrics) {
            String fieldName = metric.path("field").asText("");
            String aggregation = normalize(metric.path("aggregation").asText("count")).replace('_', '-');
            if (fieldName.isBlank()
                    || !Set.of("count", "distinct-count", "sum").contains(aggregation)
                    || !aliases.add(normalize(fieldName))) {
                return false;
            }
            boolean metricEligible = capabilityFields.stream()
                    .anyMatch(field -> normalize(field.field()).equals(normalize(fieldName))
                            && field.metricFieldEligible()
                            && field.metrics().contains(aggregation));
            if (!metricEligible) {
                return false;
            }
        }
        return true;
    }

    private ObjectNode sanitizeAnalyticsProjection(JsonNode projection) {
        ObjectNode sanitized = objectMapper.createObjectNode();
        putText(sanitized, "id", projection.path("id").asText(""));
        putText(sanitized, "intent", projection.path("intent").asText(""));
        ObjectNode source = sanitized.putObject("source");
        copyTextFields(projection.path("source"), source, "kind", "resource", "operation");
        ObjectNode bindings = sanitized.putObject("bindings");
        copyAnalyticsBinding(projection.path("bindings"), bindings, "primaryDimension");
        copyAnalyticsMetrics(projection.path("bindings"), bindings, "primaryMetrics");
        copyAnalyticsMetrics(projection.path("bindings"), bindings, "secondaryMetrics");
        copyAnalyticsBinding(projection.path("bindings"), bindings, "comparisonPeriod");
        copyAnalyticsGovernance(projection.path("governance"), sanitized);
        copyAnalyticsDefaults(projection.path("defaults"), sanitized);
        copyAnalyticsPresentationHints(projection.path("presentationHints"), sanitized);
        copyAnalyticsInteractions(projection.path("interactions"), sanitized);
        return sanitized;
    }

    private void copyAnalyticsBinding(JsonNode source, ObjectNode target, String fieldName) {
        JsonNode binding = source.path(fieldName);
        if (!binding.isObject()) {
            return;
        }
        ObjectNode copy = target.putObject(fieldName);
        if ("comparisonPeriod".equals(fieldName)) {
            copyTextFields(binding, copy, "field", "timezone", "preset", "mode");
        } else {
            copyTextFields(binding, copy, "field", "role", "label", "keyFilterField");
        }
    }

    private void copyAnalyticsMetrics(JsonNode source, ObjectNode target, String fieldName) {
        JsonNode metrics = source.path(fieldName);
        if (!metrics.isArray() || metrics.isEmpty()) {
            return;
        }
        ArrayNode copy = target.putArray(fieldName);
        for (JsonNode metric : metrics) {
            ObjectNode item = copy.addObject();
            copyTextFields(metric, item, "field", "aggregation", "label");
        }
    }

    private void copyAnalyticsGovernance(JsonNode governance, ObjectNode target) {
        JsonNode policyRefs = governance.path("policyRefs");
        if (!policyRefs.isArray() || policyRefs.isEmpty()) {
            return;
        }
        ObjectNode governanceCopy = target.putObject("governance");
        ArrayNode refsCopy = governanceCopy.putArray("policyRefs");
        for (JsonNode policyRef : policyRefs) {
            ObjectNode refCopy = refsCopy.addObject();
            copyTextFields(policyRef, refCopy, "policyId", "policyVersion", "role", "resultField");
            JsonNode attestation = policyRef.path("attestation");
            if (attestation.isObject()) {
                copyTextFields(
                        attestation,
                        refCopy.putObject("attestation"),
                        "policyIdField",
                        "policyVersionField");
            }
        }
    }

    private void copyAnalyticsDefaults(JsonNode defaults, ObjectNode target) {
        if (!defaults.isObject()) {
            return;
        }
        ObjectNode defaultsCopy = target.putObject("defaults");
        if (defaults.path("limit").canConvertToInt()) {
            defaultsCopy.put("limit", defaults.path("limit").asInt());
        }
        putText(defaultsCopy, "granularity", defaults.path("granularity").asText(""));
        JsonNode sort = defaults.path("sort");
        if (sort.isArray() && !sort.isEmpty()) {
            ArrayNode sortCopy = defaultsCopy.putArray("sort");
            for (JsonNode item : sort) {
                copyTextFields(item, sortCopy.addObject(), "field", "direction");
            }
        }
    }

    private void copyAnalyticsPresentationHints(JsonNode hints, ObjectNode target) {
        JsonNode families = hints.path("preferredFamilies");
        if (!families.isArray() || families.isEmpty()) {
            return;
        }
        ArrayNode familiesCopy = target.putObject("presentationHints").putArray("preferredFamilies");
        for (JsonNode family : families) {
            if (family.isTextual() && !family.asText("").isBlank()) {
                familiesCopy.add(family.asText());
            }
        }
    }

    private void copyAnalyticsInteractions(JsonNode interactions, ObjectNode target) {
        if (!interactions.isObject()) {
            return;
        }
        ObjectNode copy = target.putObject("interactions");
        for (String field : List.of("drillDown", "pointSelection", "crossFilter")) {
            if (interactions.has(field) && interactions.path(field).isBoolean()) {
                copy.put(field, interactions.path(field).asBoolean());
            }
        }
        JsonNode recordOpen = interactions.path("recordOpen");
        if (recordOpen.isObject()) {
            ObjectNode recordOpenCopy = copy.putObject("recordOpen");
            putText(recordOpenCopy, "sourceIdentityField", recordOpen.path("sourceIdentityField").asText(""));
            ObjectNode targetCopy = recordOpenCopy.putObject("target");
            putText(targetCopy, "resourceKey", recordOpen.path("target").path("resourceKey").asText(""));
            putText(targetCopy, "surfaceId", recordOpen.path("target").path("surfaceId").asText(""));
        }
    }

    private void copyTextFields(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            putText(target, field, source.path(field).asText(""));
        }
    }

    private void putText(ObjectNode target, String field, String text) {
        if (target != null && text != null && !text.isBlank()) {
            target.put(field, text.trim());
        }
    }

    private String fetchStatus(ResourceCapabilitiesFetchResult result) {
        return result == null || result.getStatus() == null
                ? "unavailable"
                : result.getStatus().name().toLowerCase(Locale.ROOT);
    }

    private String surfaceFetchStatus(ResourceSurfaceCatalogFetchResult result) {
        return result == null || result.getStatus() == null
                ? "unavailable"
                : result.getStatus().name().toLowerCase(Locale.ROOT);
    }

    private String schemaFetchStatus(SchemaFetchResult result) {
        return result == null || result.getStatus() == null
                ? "unavailable"
                : result.getStatus().name().toLowerCase(Locale.ROOT);
    }

    AgenticAuthoringPlanRequest copyWithContextHints(
            AgenticAuthoringPlanRequest request,
            JsonNode contextHints) {
        return new AgenticAuthoringPlanRequest(
                request.userPrompt(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.currentPage(),
                request.intentResolution(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                attachmentSummaries(request),
                contextHints);
    }

    private List<AgenticAuthoringAttachmentSummary> attachmentSummaries(AgenticAuthoringPlanRequest request) {
        if (request.attachmentSummaries() != null && !request.attachmentSummaries().isEmpty()) {
            return request.attachmentSummaries();
        }
        JsonNode diagnostics = request.pendingClarification() == null
                ? null
                : request.pendingClarification().diagnostics();
        JsonNode summaries = diagnostics == null ? null : diagnostics.path("attachmentSummaries");
        if (summaries == null || !summaries.isArray() || summaries.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(
                    summaries,
                    new TypeReference<List<AgenticAuthoringAttachmentSummary>>() {
                    });
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
    }

    private boolean isPraxisStatsChart(ObjectNode widget) {
        if (widget == null || !"praxis-chart".equals(widget.path("componentId").asText(""))) {
            return false;
        }
        JsonNode query = widget.path("inputs").path("config").path("dataSource").path("query");
        return "praxis.stats".equals(query.path("sourceKind").asText(""))
                && !"comparison".equalsIgnoreCase(query.path("statsOperation").asText(""));
    }

    private int countPraxisStatsCharts(ObjectNode uiCompositionPlan) {
        int count = 0;
        JsonNode widgets = uiCompositionPlan.path("widgets");
        if (widgets.isArray()) {
            for (JsonNode widget : widgets) {
                if (widget instanceof ObjectNode widgetObject && isPraxisStatsChart(widgetObject)) {
                    count++;
                }
            }
        }
        return count;
    }

    private List<StatsCapabilityFieldDescriptor> statsCapabilityFields(JsonNode capabilities) {
        JsonNode root = capabilities == null ? MissingNode.getInstance() : capabilities;
        if (root.path("data").path("stats").isObject()) {
            root = root.path("data");
        }
        JsonNode fields = root.path("stats").path("fields");
        if (!fields.isArray()) {
            return List.of();
        }
        List<StatsCapabilityFieldDescriptor> result = new ArrayList<>();
        for (JsonNode field : fields) {
            String fieldName = field.path("field").asText("").trim();
            if (fieldName.isBlank()) {
                continue;
            }
            result.add(new StatsCapabilityFieldDescriptor(
                    fieldName,
                    field.path("label").asText("").trim(),
                    textValues(field.path("aliases")),
                    normalizedTextValues(field.path("metrics")),
                    normalizedTextValues(field.path("modes")),
                    field.path("groupByEligible").asBoolean(false),
                    field.path("timeSeriesEligible").asBoolean(false),
                    field.path("distributionTermsEligible").asBoolean(false),
                    field.path("distributionHistogramEligible").asBoolean(false),
                    field.path("metricFieldEligible").asBoolean(false),
                    field.path("keyAndLabelDistinct").asBoolean(false)));
        }
        return List.copyOf(result);
    }

    private Set<String> normalizedTextValues(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                String normalized = normalize(value.asText("")).replace('_', '-');
                if (!normalized.isBlank()) {
                    result.add(normalized);
                }
            }
        }
        return Set.copyOf(result);
    }

    private Set<String> textValues(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return Set.copyOf(result);
    }

    private Optional<StatsCapabilityFieldDescriptor> resolveStatsDimensionCapability(
            ObjectNode semanticAxis,
            String requestedExecutionField,
            String operation,
            List<StatsCapabilityFieldDescriptor> fields) {
        String requested = normalize(requestedExecutionField);
        Optional<StatsCapabilityFieldDescriptor> exactRequested = fields.stream()
                .filter(field -> eligibleStatsDimension(field, operation))
                .filter(field -> normalize(field.field()).equals(requested))
                .findFirst();
        if (exactRequested.isPresent()) {
            return exactRequested;
        }
        String semanticField = normalize(semanticAxis.path("field").asText(""));
        Optional<StatsCapabilityFieldDescriptor> exactSemantic = fields.stream()
                .filter(field -> eligibleStatsDimension(field, operation))
                .filter(field -> normalize(field.field()).equals(semanticField))
                .findFirst();
        if (exactSemantic.isPresent()) {
            return exactSemantic;
        }
        String originallyRequested = normalize(semanticAxis.path("requestedField").asText(""));
        Optional<StatsCapabilityFieldDescriptor> exactOriginallyRequested = fields.stream()
                .filter(field -> eligibleStatsDimension(field, operation))
                .filter(field -> normalize(field.field()).equals(originallyRequested))
                .findFirst();
        if (exactOriginallyRequested.isPresent()) {
            return exactOriginallyRequested;
        }
        List<StatsCapabilityFieldDescriptor> eligible = fields.stream()
                .filter(field -> eligibleStatsDimension(field, operation))
                .toList();
        int bestScore = 0;
        StatsCapabilityFieldDescriptor best = null;
        boolean ambiguous = false;
        for (StatsCapabilityFieldDescriptor field : eligible) {
            int score = statsCapabilityMatchScore(semanticAxis, field);
            if (score > bestScore) {
                bestScore = score;
                best = field;
                ambiguous = false;
            } else if (score > 0 && score == bestScore) {
                ambiguous = true;
            }
        }
        return !ambiguous && best != null && bestScore >= 4 ? Optional.of(best) : Optional.empty();
    }

    private int statsCapabilityMatchScore(
            ObjectNode semanticAxis,
            StatsCapabilityFieldDescriptor capability) {
        String axisLabel = normalize(semanticAxis.path("label").asText(""));
        String axisConcept = normalize(semanticAxis.path("concept").asText(""));
        String capabilityLabel = normalize(capability.label());
        int score = (!axisLabel.isBlank() && axisLabel.equals(capabilityLabel))
                        || (!axisConcept.isBlank() && axisConcept.equals(capabilityLabel))
                ? 12
                : 0;
        Set<String> normalizedAliases = capability.aliases().stream()
                .map(this::normalize)
                .filter(alias -> !alias.isBlank())
                .collect(Collectors.toSet());
        if (normalizedAliases.contains(axisConcept)
                || normalizedAliases.contains(normalize(semanticAxis.path("field").asText("")))
                || normalizedAliases.contains(axisLabel)) {
            score = Math.max(score, 12);
        }
        Set<String> axisTokens = semanticAxisTokens(semanticAxis);
        Set<String> capabilityTokens = new LinkedHashSet<>(tokens(capability.field()));
        capabilityTokens.addAll(tokens(capability.label()));
        capability.aliases().forEach(alias -> capabilityTokens.addAll(tokens(alias)));
        for (String token : axisTokens) {
            if (capabilityTokens.contains(token)) {
                score += 2;
            }
        }
        return score;
    }

    private boolean eligibleStatsDimension(
            StatsCapabilityFieldDescriptor field,
            String operation) {
        String normalized = normalize(operation).replace('_', '-');
        return switch (normalized) {
            case "timeseries", "time-series" -> field.timeSeriesEligible()
                    || field.modes().contains("time-series");
            case "comparison" -> field.groupByEligible() || field.modes().contains("group-by");
            case "distribution" -> field.distributionTermsEligible()
                    || field.distributionHistogramEligible()
                    || field.modes().contains("distribution-terms")
                    || field.modes().contains("distribution-histogram");
            default -> field.groupByEligible() || field.modes().contains("group-by");
        };
    }

    private boolean alignStatsMetricCapability(
            ObjectNode config,
            List<StatsCapabilityFieldDescriptor> capabilityFields) {
        ObjectNode query = config.path("dataSource").path("query") instanceof ObjectNode value ? value : null;
        if (query != null && "comparison".equals(normalize(query.path("statsOperation").asText("")))) {
            return alignComparisonStatsMetricCapabilities(config, query, capabilityFields);
        }
        ObjectNode statsMetric = query != null && query.path("statsRequest").path("metric") instanceof ObjectNode value
                ? value
                : null;
        if (query == null || statsMetric == null) {
            return false;
        }
        String operation = normalize(firstNonBlank(
                statsMetric.path("operation").asText(""),
                statsMetric.path("aggregation").asText(""))).replace('_', '-');
        if ("count".equals(operation)) {
            alignStatsCountMetric(query, statsMetric);
            return true;
        }
        String requestedField = statsMetric.path("field").asText("");
        Optional<StatsCapabilityFieldDescriptor> metricCapability = capabilityFields.stream()
                .filter(StatsCapabilityFieldDescriptor::metricFieldEligible)
                .filter(field -> normalize(field.field()).equals(normalize(requestedField)))
                .filter(field -> field.metrics().contains(operation))
                .findFirst();
        if (metricCapability.isEmpty()) {
            alignStatsCountMetric(query, statsMetric);
            return true;
        }
        String canonicalField = metricCapability.get().field();
        statsMetric.put("field", canonicalField);
        statsMetric.put("operation", operation.toUpperCase(Locale.ROOT).replace('-', '_'));
        statsMetric.put("alias", canonicalField);
        JsonNode metrics = query.path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (metric instanceof ObjectNode metricObject
                        && normalize(metricObject.path("aggregation").asText("")).replace('_', '-').equals(operation)) {
                    metricObject.put("field", canonicalField);
                    metricObject.put("alias", canonicalField);
                }
            }
        }
        JsonNode series = config.path("series");
        if (series.isArray()) {
            for (JsonNode item : series) {
                if (item instanceof ObjectNode seriesItem
                        && seriesItem.path("metric") instanceof ObjectNode seriesMetric
                        && normalize(seriesMetric.path("aggregation").asText("")).replace('_', '-').equals(operation)) {
                    seriesMetric.put("field", canonicalField);
                }
            }
        }
        return true;
    }

    private void alignStatsCountMetric(ObjectNode query, ObjectNode statsMetric) {
        statsMetric.put("operation", "COUNT");
        statsMetric.remove("field");
        statsMetric.put("alias", "total");
        JsonNode metrics = query.path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (metric instanceof ObjectNode metricObject) {
                    metricObject.put("aggregation", "count");
                    metricObject.remove("field");
                    metricObject.put("alias", "total");
                }
            }
        }
    }

    private boolean alignComparisonPeriodCapability(
            ObjectNode statsRequest,
            List<StatsCapabilityFieldDescriptor> capabilityFields) {
        if (statsRequest == null || statsRequest.path("metric").isObject()) {
            return false;
        }
        String requestedPeriodField = statsRequest.path("periodField").asText("");
        JsonNode period = statsRequest.path("period");
        if (requestedPeriodField.isBlank()
                || !period.isObject()
                || period.path("preset").asText("").isBlank()
                || period.path("timezone").asText("").isBlank()
                || period.path("mode").asText("").isBlank()) {
            return false;
        }
        Optional<StatsCapabilityFieldDescriptor> capability = capabilityFields.stream()
                .filter(field -> normalize(field.field()).equals(normalize(requestedPeriodField)))
                .filter(field -> field.timeSeriesEligible() || field.modes().contains("time-series"))
                .findFirst();
        if (capability.isEmpty()) {
            return false;
        }
        statsRequest.put("periodField", capability.get().field());
        return true;
    }

    private boolean alignComparisonStatsMetricCapabilities(
            ObjectNode config,
            ObjectNode query,
            List<StatsCapabilityFieldDescriptor> capabilityFields) {
        ObjectNode statsRequest = query.path("statsRequest") instanceof ObjectNode value ? value : null;
        JsonNode executionMetrics = statsRequest == null ? MissingNode.getInstance() : statsRequest.path("metrics");
        if (statsRequest == null
                || statsRequest.path("metric").isObject()
                || !executionMetrics.isArray()
                || executionMetrics.isEmpty()) {
            return false;
        }
        Set<String> aliases = new LinkedHashSet<>();
        Map<String, String> canonicalAliases = new LinkedHashMap<>();
        for (JsonNode metric : executionMetrics) {
            if (!(metric instanceof ObjectNode metricObject)) {
                return false;
            }
            String operation = normalize(metricObject.path("operation").asText("")).replace('_', '-');
            String alias = metricObject.path("alias").asText("").trim();
            String requestedField = "count".equals(operation)
                    ? alias
                    : metricObject.path("field").asText("").trim();
            if (!Set.of("count", "distinct-count", "sum").contains(operation)
                    || alias.isBlank()
                    || requestedField.isBlank()
                    || !aliases.add(normalize(alias))) {
                return false;
            }
            Optional<StatsCapabilityFieldDescriptor> capability = capabilityFields.stream()
                .filter(StatsCapabilityFieldDescriptor::metricFieldEligible)
                .filter(field -> normalize(field.field()).equals(normalize(requestedField)))
                .filter(field -> field.metrics().contains(operation))
                .findFirst();
            if (capability.isEmpty()) {
                return false;
            }
            String canonicalField = capability.get().field();
            canonicalAliases.put(alias, canonicalField);
            metricObject.put("operation", operation.toUpperCase(Locale.ROOT).replace('-', '_'));
            metricObject.put("alias", canonicalField);
            if ("count".equals(operation)) {
                metricObject.remove("field");
            } else {
                metricObject.put("field", canonicalField);
            }
        }
        alignComparisonDisplayBindings(config, query, canonicalAliases);
        return true;
    }

    private void alignComparisonDisplayBindings(
            ObjectNode config,
            ObjectNode query,
            Map<String, String> canonicalAliases) {
        JsonNode metrics = query.path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (!(metric instanceof ObjectNode metricObject)) {
                    continue;
                }
                String canonicalField = canonicalComparisonOutputField(
                        metricObject.path("field").asText(""),
                        canonicalAliases);
                if (!canonicalField.isBlank()) {
                    metricObject.put("field", canonicalField);
                    metricObject.put("alias", canonicalField);
                }
            }
        }
        JsonNode series = config.path("series");
        if (series.isArray()) {
            for (JsonNode item : series) {
                if (!(item instanceof ObjectNode seriesObject)
                        || !(seriesObject.path("metric") instanceof ObjectNode seriesMetric)) {
                    continue;
                }
                String canonicalField = canonicalComparisonOutputField(
                        seriesMetric.path("field").asText(""),
                        canonicalAliases);
                if (!canonicalField.isBlank()) {
                    seriesMetric.put("field", canonicalField);
                }
            }
        }
    }

    private String canonicalComparisonOutputField(
            String outputField,
            Map<String, String> canonicalAliases) {
        if (outputField == null || canonicalAliases == null || canonicalAliases.isEmpty()) {
            return "";
        }
        String prefix = "__praxisComparison_";
        if (!outputField.startsWith(prefix)) {
            return "";
        }
        for (String period : List.of("current", "previous")) {
            String suffix = "_" + period;
            if (!outputField.endsWith(suffix)) {
                continue;
            }
            String alias = outputField.substring(prefix.length(), outputField.length() - suffix.length());
            String canonicalAlias = canonicalAliases.entrySet().stream()
                    .filter(entry -> normalize(entry.getKey()).equals(normalize(alias)))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("");
            return canonicalAlias.isBlank() ? "" : prefix + canonicalAlias + suffix;
        }
        return "";
    }

    private void alignStatsExecutionField(
            ObjectNode query,
            ObjectNode statsRequest,
            StatsCapabilityFieldDescriptor capability) {
        statsRequest.put("field", capability.field());
        JsonNode dimensions = query.path("dimensions");
        if (dimensions instanceof ArrayNode dimensionArray) {
            dimensionArray.removeAll();
            dimensionArray.add(capability.field());
        }
    }

    private void markStatsAxesUnsupported(
            ObjectNode uiCompositionPlan,
            String status,
            List<String> warnings) {
        if (uiCompositionPlan == null) {
            return;
        }
        JsonNode widgets = uiCompositionPlan.path("widgets");
        if (widgets.isArray()) {
            for (JsonNode widget : widgets) {
                if (widget instanceof ObjectNode widgetObject && isPraxisStatsChart(widgetObject)) {
                    JsonNode axis = widgetObject.path("inputs").path("config").path("semanticAxis");
                    markStatsAxisUnsupported(
                            uiCompositionPlan,
                            axis instanceof ObjectNode axisObject ? axisObject : null,
                            status);
                }
            }
        }
        addWarningOnce(warnings, "semantic-axis-stats-capability-verification-" + status);
    }

    private void markStatsAxisUnsupported(
            ObjectNode uiCompositionPlan,
            ObjectNode semanticAxis,
            String status) {
        if (semanticAxis == null) {
            return;
        }
        semanticAxis.put("statsVerified", false);
        semanticAxis.put("statsProbeStatus", status);
        updateDiagnosticStatsAxis(uiCompositionPlan, semanticAxis, null, null, false, status);
    }

    private void markStatsAxisVerified(
            ObjectNode uiCompositionPlan,
            ObjectNode semanticAxis,
            StatsCapabilityFieldDescriptor capability,
            ResourceCapabilitiesFetchResult result) {
        semanticAxis.put("statsVerified", true);
        semanticAxis.put("statsProbeStatus", "verified");
        semanticAxis.put("statsExecutionField", capability.field());
        ObjectNode evidence = semanticAxis.putObject("statsEvidence");
        evidence.put("source", "resource.capabilities");
        evidence.put("endpointUrl", value(result == null ? null : result.getEndpointUrl()));
        evidence.put("keyAndLabelDistinct", capability.keyAndLabelDistinct());
        updateDiagnosticStatsAxis(uiCompositionPlan, semanticAxis, capability, result, true, "verified");
    }

    private void updateDiagnosticStatsAxis(
            ObjectNode uiCompositionPlan,
            ObjectNode semanticAxis,
            StatsCapabilityFieldDescriptor capability,
            ResourceCapabilitiesFetchResult result,
            boolean verified,
            String status) {
        JsonNode axes = uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return;
        }
        String field = normalize(semanticAxis.path("field").asText(""));
        for (JsonNode axis : axes) {
            if (!(axis instanceof ObjectNode axisObject)
                    || !normalize(axisObject.path("field").asText("")).equals(field)) {
                continue;
            }
            axisObject.put("statsVerified", verified);
            axisObject.put("statsProbeStatus", status);
            if (capability != null) {
                axisObject.put("statsExecutionField", capability.field());
                ObjectNode evidence = axisObject.putObject("statsEvidence");
                evidence.put("source", "resource.capabilities");
                evidence.put("endpointUrl", value(result == null ? null : result.getEndpointUrl()));
                evidence.put("keyAndLabelDistinct", capability.keyAndLabelDistinct());
            }
        }
    }

    private void markResourceStatsGrounding(
            ObjectNode uiCompositionPlan,
            String resourcePath,
            ResourceCapabilitiesFetchResult result,
            int fieldCount,
            int groundedChartCount,
            int chartCount) {
        ObjectNode diagnostics = uiCompositionPlan.path("diagnostics") instanceof ObjectNode existing
                ? existing
                : uiCompositionPlan.putObject("diagnostics");
        ObjectNode grounding = diagnostics.putObject("resourceStatsGrounding");
        grounding.put("verified", chartCount > 0 && groundedChartCount == chartCount);
        grounding.put("source", "resource.capabilities");
        grounding.put("resourcePath", resourcePath);
        grounding.put("endpointUrl", value(result == null ? null : result.getEndpointUrl()));
        grounding.put("fieldCount", Math.max(0, fieldCount));
        grounding.put("chartCount", Math.max(0, chartCount));
        grounding.put("groundedChartCount", Math.max(0, groundedChartCount));
    }

    private Map<String, SchemaFieldDescriptor> schemaFields(JsonNode schema) {
        Map<String, SchemaFieldDescriptor> fields = new LinkedHashMap<>();
        collectSchemaFields(schema, fields);
        return fields;
    }

    private void collectSchemaFields(JsonNode node, Map<String, SchemaFieldDescriptor> fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                String field = entry.getKey();
                if (!value(field).isBlank()) {
                    fields.putIfAbsent(normalize(field), schemaFieldDescriptor(field, entry.getValue()));
                }
            });
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectSchemaFields(entry.getValue(), fields));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectSchemaFields(item, fields);
            }
        }
    }

    private SchemaFieldDescriptor schemaFieldDescriptor(String field, JsonNode property) {
        JsonNode xUi = property.path("x-ui");
        if ((xUi.isMissingNode() || xUi.isNull()) && property.path("items").path("x-ui").isObject()) {
            xUi = property.path("items").path("x-ui");
        }
        return new SchemaFieldDescriptor(
                field,
                property.path("type").asText(""),
                property.path("items").path("type").asText(""),
                xUi.path("multiple").asBoolean(false));
    }

    private boolean supportsBucketKey(SchemaFieldDescriptor field) {
        if (field == null) {
            return false;
        }
        boolean multiple = field.multiple() || "array".equals(normalize(field.type()));
        String valueType = normalize(multiple ? field.itemType() : field.type());
        return Set.of("string", "integer", "number", "boolean").contains(valueType);
    }

    private boolean supportsRecordIdentity(SchemaFieldDescriptor field) {
        if (field == null || field.multiple() || "array".equals(normalize(field.type()))) {
            return false;
        }
        return Set.of("string", "integer", "number").contains(normalize(field.type()));
    }

    private Set<String> semanticAxisTokens(ObjectNode semanticAxis) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, semanticAxis.path("concept").asText(""));
        addTokens(tokens, semanticAxis.path("field").asText(""));
        addTokens(tokens, semanticAxis.path("label").asText(""));
        return tokens;
    }

    private Set<String> tokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, value);
        return tokens;
    }

    private void addTokens(Set<String> tokens, String value) {
        String tokenizable = value == null
                ? ""
                : value.replaceAll("([a-z])([A-Z])", "$1 $2");
        for (String token : normalize(tokenizable).replaceAll("[^a-z0-9]+", " ").split("\\s+")) {
            if (token.length() >= 3 && !SEMANTIC_AXIS_STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
    }

    private void addWarningOnce(List<String> warnings, String warning) {
        if (warnings != null && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private String businessResourcePath(String resourcePath) {
        String value = value(resourcePath).replaceAll("/+$", "");
        for (String suffix : List.of(
                "/stats/group-by",
                "/stats/timeseries",
                "/stats/distribution",
                "/stats/comparison",
                "/filter/cursor",
                "/filter",
                "/all")) {
            if (value.endsWith(suffix)) {
                value = value.substring(0, value.length() - suffix.length());
                break;
            }
        }
        return value;
    }

    private String firstNonBlank(String first, String second) {
        return value(first).isBlank() ? value(second) : value(first);
    }

    private String valueOrDefault(String value, String fallback) {
        return value(value).isBlank() ? fallback : value.trim();
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    private record SchemaFieldDescriptor(
            String name,
            String type,
            String itemType,
            boolean multiple) {
    }

    private record StatsCapabilityFieldDescriptor(
            String field,
            String label,
            Set<String> aliases,
            Set<String> metrics,
            Set<String> modes,
            boolean groupByEligible,
            boolean timeSeriesEligible,
            boolean distributionTermsEligible,
            boolean distributionHistogramEligible,
            boolean metricFieldEligible,
            boolean keyAndLabelDistinct) {
    }
}
