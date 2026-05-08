package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Host-neutral page composition planner for resource-backed authoring.
 *
 * <p>This provider materializes only generic component skeletons from the selected candidate and
 * resolved artifact kind. Business-specific layouts remain host-owned providers.</p>
 */
public class AgenticAuthoringGenericUiCompositionPlanProvider implements AgenticAuthoringUiCompositionPlanProvider {

    private final ObjectMapper objectMapper;

    public AgenticAuthoringGenericUiCompositionPlanProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AgenticAuthoringUiCompositionPlanResult> plan(AgenticAuthoringPlanRequest request) {
        AgenticAuthoringIntentResolutionResult intent = request == null ? null : request.intentResolution();
        AgenticAuthoringCandidate candidate = intent == null ? null : intent.selectedCandidate();
        if (intent == null
                || candidate == null
                || !"create".equals(intent.operationKind())
                || !"eligible".equals(intent.gate() == null ? "" : intent.gate().status())) {
            return Optional.empty();
        }
        String artifactKind = safe(intent.artifactKind());
        if (!List.of("table", "dashboard", "page").contains(artifactKind)) {
            return Optional.empty();
        }
        AgenticAuthoringVisualizationDecision visualizationDecision = intent.visualizationDecision();
        ObjectNode plan = switch (artifactKind) {
            case "dashboard" -> dashboardPlan(candidate, visualizationDecision);
            case "page" -> pagePlan(candidate);
            default -> tablePlan(candidate);
        };
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:generic-resource-" + artifactKind),
                plan,
                emptyCompiledFormPatch()));
    }

    private ObjectNode tablePlan(AgenticAuthoringCandidate candidate) {
        ObjectNode plan = basePlan("single-table-page");
        addTable(plan.putArray("widgets"), candidate, widgetKey(candidate, "table"), "main");
        return plan;
    }

    private ObjectNode dashboardPlan(
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        ObjectNode plan = basePlan("resource-dashboard");
        ArrayNode widgets = plan.putArray("widgets");
        if (visualizationDecision == null || !"praxis-chart".equals(safe(visualizationDecision.primaryComponent()))) {
            return plan;
        }
        if (visualizationDecision.includeSummary()) {
            addSummary(widgets, candidate, widgetKey(candidate, "summary"));
        }
        List<DashboardDimension> dimensions = dashboardDimensions(visualizationDecision);
        for (DashboardDimension dimension : dimensions) {
            addChart(widgets, candidate, widgetKey(candidate, "chart-" + dimension.field()), dimension);
        }
        addSemanticAxisProvenance(plan, visualizationDecision, dimensions);
        if (visualizationDecision.includeDetailTable()) {
            addTable(widgets, candidate, widgetKey(candidate, "table"), "detail");
        }
        return plan;
    }

    private ObjectNode pagePlan(AgenticAuthoringCandidate candidate) {
        ObjectNode plan = basePlan("resource-master-detail");
        ArrayNode widgets = plan.putArray("widgets");
        addTable(widgets, candidate, widgetKey(candidate, "master"), "master");
        addDetail(widgets, candidate, widgetKey(candidate, "detail"));
        return plan;
    }

    private ObjectNode basePlan(String layoutPreset) {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("version", "1.0");
        plan.put("layoutPreset", layoutPreset);
        plan.put("plannerId", "generic-ui-composition-plan-provider");
        return plan;
    }

    private void addTable(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key, String role) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-table");
        widget.put("role", role);
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", businessResourcePath(candidate.resourcePath()));
        inputs.put("tableId", key);
        inputs.put("title", titleFromResourcePath(businessResourcePath(candidate.resourcePath())));
        ObjectNode config = inputs.putObject("config");
        config.put("title", titleFromResourcePath(businessResourcePath(candidate.resourcePath())));
        config.putArray("columns");
    }

    private void addChart(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            DashboardDimension dimension) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-chart");
        widget.put("role", "main");
        ObjectNode config = widget.putObject("inputs").putObject("config");
        config.put("id", key);
        config.put("type", dimension.chartType());
        config.put("orientation", dimension.orientation());
        config.put("title", dimension.title() + " - " + titleFromResourcePath(businessResourcePath(candidate.resourcePath())));
        config.put("subtitle", "Contagem por " + dimension.label());
        config.putObject("semanticAxis")
                .put("concept", dimension.concept())
                .put("field", dimension.field())
                .put("label", dimension.label())
                .put("provenance", dimension.provenance())
                .put("schemaVerified", false)
                .put("schemaProbeStatus", "pending");
        config.putObject("sizing").put("mode", "fill-container").put("minHeight", 260);
        ObjectNode axes = config.putObject("axes");
        axes.putObject("x").put("field", dimension.field()).put("label", dimension.label()).put("type", "category");
        axes.putObject("y").put("label", "Total").put("type", "value");
        ObjectNode series = config.putArray("series").addObject();
        series.put("id", "total");
        series.put("name", "Total");
        series.put("type", dimension.chartType());
        series.put("categoryField", dimension.field());
        series.putObject("metric").put("aggregation", "count").put("label", "Total");
        ObjectNode dataSource = config.putObject("dataSource");
        dataSource.put("kind", "remote");
        dataSource.put("resourcePath", businessResourcePath(candidate.resourcePath()));
        dataSource.put("schemaUrl", schemaUrl(candidate));
        dataSource.put("submitUrl", submitUrl(candidate));
        dataSource.put("submitMethod", submitMethod(candidate));
        ObjectNode query = dataSource.putObject("query");
        query.put("sourceKind", "praxis.stats");
        query.put("statsOperation", "group-by");
        query.put("statsPath", statsPath(candidate.resourcePath()));
        query.putArray("dimensions").add(dimension.field());
        ObjectNode metric = query.putArray("metrics").addObject();
        metric.put("aggregation", dimension.metricAggregation());
        if (!dimension.metricField().isBlank()) {
            metric.put("field", dimension.metricField());
        }
        metric.put("alias", "total");
        ObjectNode statsRequest = query.putObject("statsRequest");
        statsRequest.putObject("filter");
        statsRequest.put("field", dimension.field());
        ObjectNode statsMetric = statsRequest.putObject("metric");
        statsMetric.put("operation", dimension.metricAggregation().toUpperCase(Locale.ROOT));
        if (!dimension.metricField().isBlank()) {
            statsMetric.put("field", dimension.metricField());
        }
        statsMetric.put("alias", "total");
        statsRequest.put("limit", 12);
        statsRequest.put("orderBy", "VALUE_DESC");
        config.putObject("interactions").put("selection", true);
    }

    private void addSemanticAxisProvenance(
            ObjectNode plan,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            List<DashboardDimension> dimensions) {
        ObjectNode diagnostics = plan.putObject("diagnostics");
        diagnostics.put("schemaVersion", "praxis-ui-composition-plan-diagnostics.v1");
        diagnostics.put("visualizationDecisionSchemaVersion", safe(visualizationDecision.schemaVersion()));
        diagnostics.put("visualizationDecisionIntent", safe(visualizationDecision.intent()));
        diagnostics.put("visualizationDecisionProvenance", safe(visualizationDecision.provenance()));
        ArrayNode axes = diagnostics.putArray("semanticAxes");
        for (DashboardDimension dimension : dimensions) {
            ObjectNode axis = axes.addObject();
            axis.put("concept", dimension.concept());
            axis.put("field", dimension.field());
            axis.put("label", dimension.label());
            axis.put("provenance", dimension.provenance());
            axis.put("schemaVerified", false);
            axis.put("schemaProbeStatus", "pending");
        }
    }

    private void addSummary(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-rich-content");
        widget.put("role", "supporting");
        ObjectNode document = widget.putObject("inputs").putObject("document");
        document.put("kind", "praxis.rich-content.document");
        document.put("version", "1.0.0");
        ObjectNode text = document.putArray("nodes").addObject();
        text.put("type", "text");
        text.put("text", "Preview for " + titleFromResourcePath(businessResourcePath(candidate.resourcePath())) + ".");
    }

    private void addDetail(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-dynamic-form");
        widget.put("role", "detail");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", businessResourcePath(candidate.resourcePath()));
        inputs.put("schemaUrl", schemaUrl(candidate));
        inputs.put("submitUrl", submitUrl(candidate));
        inputs.put("submitMethod", submitMethod(candidate));
        inputs.put("formId", key);
        inputs.put("mode", "view");
        inputs.put("schemaSource", "resource");
        inputs.put("enableCustomization", true);
    }

    private ObjectNode emptyCompiledFormPatch() {
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("version", "1.0.0");
        patch.put("profileId", "ui-composition-plan");
        patch.put("targetComponentId", "praxis-dynamic-page-builder");
        patch.putObject("patch");
        patch.putObject("compatibility")
                .put("aiHttpContract", "v1.1")
                .put("publicResponseKind", "ui-composition-plan")
                .put("requiresV12", false);
        patch.put("builderVersion", "generic-ui-composition-plan-provider@0.1.0-draft");
        patch.putArray("warnings").add("compiled-form-patch-materialized-by-page-builder");
        return patch;
    }

    private String widgetKey(AgenticAuthoringCandidate candidate, String suffix) {
        String slug = slug(baseResourceName(candidate == null ? "" : businessResourcePath(candidate.resourcePath())));
        return (slug.isBlank() ? "resource" : slug) + "-" + suffix;
    }

    private String titleFromResourcePath(String path) {
        String name = baseResourceName(path);
        if (name.isBlank()) {
            return "Resource";
        }
        String[] parts = name.replace('-', ' ').replace('_', ' ').split("\\s+");
        StringBuilder title = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!title.isEmpty()) {
                title.append(' ');
            }
            title.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                title.append(part.substring(1));
            }
        }
        return title.isEmpty() ? "Resource" : title.toString();
    }

    private List<DashboardDimension> dashboardDimensions(AgenticAuthoringVisualizationDecision visualizationDecision) {
        if (visualizationDecision == null || visualizationDecision.axes() == null) {
            return List.of();
        }
        List<DashboardDimension> dimensions = new ArrayList<>();
        for (AgenticAuthoringVisualizationAxisDecision axis : visualizationDecision.axes()) {
            if (axis == null || safe(axis.field()).isBlank() || safe(axis.concept()).isBlank()) {
                continue;
            }
            dimensions.add(new DashboardDimension(
                    safe(axis.concept()),
                    safe(axis.field()),
                    valueOrDefault(axis.label(), titleFromResourcePath(axis.field())),
                    "Registros por " + valueOrDefault(axis.label(), axis.field()),
                    valueOrDefault(axis.chartType(), "bar"),
                    valueOrDefault(axis.orientation(), "vertical"),
                    valueOrDefault(axis.metricAggregation(), "count"),
                    valueOrDefault(axis.metricField(), ""),
                    valueOrDefault(axis.metricLabel(), "Total"),
                    valueOrDefault(axis.provenance(), "llm-authored-semantic-axis")));
        }
        return dimensions.stream().limit(3).toList();
    }

    private String valueOrDefault(String value, String fallback) {
        String safe = safe(value);
        return safe.isBlank() ? fallback : safe;
    }

    private String baseResourceName(String path) {
        String value = safe(path);
        if (value.isBlank()) {
            return "";
        }
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        String[] parts = value.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];
            if (!part.isBlank() && !part.contains("{") && !List.of("filter", "cursor", "all").contains(part)) {
                return part;
            }
        }
        return "";
    }

    private String statsPath(String resourcePath) {
        String value = businessResourcePath(resourcePath);
        if (value.isBlank()) {
            return "";
        }
        return value + "/stats/group-by";
    }

    private String businessResourcePath(String resourcePath) {
        String value = safe(resourcePath).replaceAll("/+$", "");
        for (String suffix : List.of(
                "/stats/group-by",
                "/stats/timeseries",
                "/stats/distribution",
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

    private String submitUrl(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return "";
        }
        if (isStatsPath(candidate.submitUrl()) || isStatsPath(candidate.resourcePath())) {
            return businessResourcePath(candidate.resourcePath()) + "/filter/cursor";
        }
        return safe(candidate.submitUrl());
    }

    private String submitMethod(AgenticAuthoringCandidate candidate) {
        if (candidate != null && (isStatsPath(candidate.submitUrl()) || isStatsPath(candidate.resourcePath()))) {
            return "POST";
        }
        return candidate == null ? "" : safe(candidate.submitMethod());
    }

    private String schemaUrl(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return "";
        }
        if (isStatsPath(candidate.schemaUrl()) || isStatsPath(candidate.submitUrl()) || isStatsPath(candidate.resourcePath())) {
            return "/schemas/filtered?path=" + businessResourcePath(candidate.resourcePath())
                    + "/filter/cursor&operation=post&schemaType=response";
        }
        return safe(candidate.schemaUrl());
    }

    private boolean isStatsPath(String value) {
        String normalized = safe(value);
        return normalized.contains("/stats/group-by")
                || normalized.contains("/stats/timeseries")
                || normalized.contains("/stats/distribution");
    }

    private String slug(String value) {
        String normalized = Normalizer.normalize(safe(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return normalized.length() > 48 ? normalized.substring(0, 48).replaceAll("-$", "") : normalized;
    }

    private String normalize(String value) {
        return Normalizer.normalize(safe(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record DashboardDimension(
            String concept,
            String field,
            String label,
            String title,
            String chartType,
            String orientation,
            String metricAggregation,
            String metricField,
            String metricLabel,
            String provenance) {
    }
}
