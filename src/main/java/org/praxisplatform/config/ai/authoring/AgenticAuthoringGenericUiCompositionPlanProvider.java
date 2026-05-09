package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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
        AgenticAuthoringCandidate candidate = selectedCandidate(intent);
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
            case "dashboard" -> dashboardPlan(request, candidate, visualizationDecision);
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

    private AgenticAuthoringCandidate selectedCandidate(AgenticAuthoringIntentResolutionResult intent) {
        if (intent == null) {
            return null;
        }
        if (intent.selectedCandidate() != null) {
            return intent.selectedCandidate();
        }
        AgenticAuthoringSemanticDecision semanticDecision = intent.semanticDecision();
        AgenticAuthoringSemanticDecision.SelectedResource resource =
                semanticDecision == null ? null : semanticDecision.selectedResource();
        if (resource == null || safe(resource.resourcePath()).isBlank()) {
            return null;
        }
        return new AgenticAuthoringCandidate(
                safe(resource.resourcePath()),
                valueOrDefault(resource.operation(), "post"),
                valueOrDefault(resource.schemaUrl(), defaultSchemaUrl(resource.resourcePath(), resource.operation())),
                valueOrDefault(resource.submitUrl(), resource.resourcePath()),
                valueOrDefault(resource.submitMethod(), valueOrDefault(resource.operation(), "post")),
                semanticDecision.confidence() == null ? 0.70d : semanticDecision.confidence(),
                "semantic-decision-selected-resource",
                List.of("semantic-decision-selected-resource"),
                semanticDecision.retrievedEvidence());
    }

    private ObjectNode tablePlan(AgenticAuthoringCandidate candidate) {
        ObjectNode plan = basePlan("single-table-page");
        addTable(plan.putArray("widgets"), candidate, widgetKey(candidate, "table"), "main");
        return plan;
    }

    private ObjectNode dashboardPlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        ObjectNode plan = basePlan("resource-dashboard");
        ArrayNode widgets = plan.putArray("widgets");
        boolean chartDashboard = visualizationDecision != null
                && "praxis-chart".equals(safe(visualizationDecision.primaryComponent()));
        if (!chartDashboard && !looksLikeChartRequest(request)) {
            return plan;
        }
        List<DashboardDimension> dimensions = dashboardDimensions(visualizationDecision, candidate, request);
        addSummary(widgets, candidate, widgetKey(candidate, "summary"));
        addKpis(widgets, candidate, widgetKey(candidate, "kpis"), dimensions);
        addFilter(widgets, candidate, widgetKey(candidate, "filter"), dimensions);
        for (DashboardDimension dimension : dimensions) {
            addChart(widgets, candidate, widgetKey(candidate, "chart-" + dimension.field()), dimension);
        }
        addSemanticAxisProvenance(plan, visualizationDecision, dimensions);
        addTable(widgets, candidate, widgetKey(candidate, "table"), "detail");
        addDashboardBindings(plan, candidate, dimensions);
        addDashboardCanvas(plan, candidate, dimensions);
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
        series.putObject("metric")
                .put("field", metricOutputField(dimension))
                .put("aggregation", dimension.metricAggregation())
                .put("label", "Total");
        ObjectNode dataSource = config.putObject("dataSource");
        dataSource.put("kind", "remote");
        dataSource.put("resourcePath", businessResourcePath(candidate.resourcePath()));
        dataSource.put("schemaUrl", schemaUrl(candidate));
        dataSource.put("submitUrl", submitUrl(candidate));
        dataSource.put("submitMethod", submitMethod(candidate));
        dataSource.put("statsEndpointInference", "canonical-resource-stats-group-by");
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
        metric.put("alias", metricOutputField(dimension));
        ObjectNode statsRequest = query.putObject("statsRequest");
        statsRequest.putObject("filter");
        statsRequest.put("field", dimension.field());
        ObjectNode statsMetric = statsRequest.putObject("metric");
        statsMetric.put("operation", dimension.metricAggregation().toUpperCase(Locale.ROOT));
        if (!dimension.metricField().isBlank()) {
            statsMetric.put("field", dimension.metricField());
        }
        statsMetric.put("alias", metricOutputField(dimension));
        statsRequest.put("limit", 12);
        statsRequest.put("orderBy", "VALUE_DESC");
        config.putObject("interactions").put("selection", true);
    }

    private String metricOutputField(DashboardDimension dimension) {
        if (dimension != null && !dimension.metricField().isBlank()) {
            return dimension.metricField();
        }
        return "total";
    }

    private void addKpis(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            List<DashboardDimension> dimensions) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-rich-content");
        widget.put("role", "kpi-band");
        ObjectNode document = widget.putObject("inputs").putObject("document");
        document.put("kind", "praxis.rich-content.document");
        document.put("version", "1.0.0");
        ArrayNode kpis = document.putArray("kpis");
        ObjectNode total = kpis.addObject();
        total.put("id", key + "-total");
        total.put("label", "Total");
        total.put("aggregation", "count");
        total.put("schemaVerified", true);
        total.put("provenance", "generic-dashboard-count-kpi");
        for (DashboardDimension dimension : dimensions.stream().limit(2).toList()) {
            ObjectNode kpi = kpis.addObject();
            kpi.put("id", key + "-" + dimension.field());
            kpi.put("label", dimension.label());
            kpi.put("aggregation", dimension.metricAggregation());
            if (!dimension.metricField().isBlank()) {
                kpi.put("field", dimension.metricField());
            }
            kpi.put("dimensionField", dimension.field());
            kpi.put("schemaVerified", false);
            kpi.put("schemaProbeStatus", "pending");
            kpi.put("provenance", dimension.provenance());
        }
        ObjectNode text = document.putArray("nodes").addObject();
        text.put("type", "text");
        text.put("text", "Indicadores de " + titleFromResourcePath(businessResourcePath(candidate.resourcePath())) + ".");
    }

    private void addFilter(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            List<DashboardDimension> dimensions) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-filter");
        widget.put("role", "filter");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", businessResourcePath(candidate.resourcePath()));
        inputs.put("filterId", key);
        inputs.put("showFilterSettings", true);
        ArrayNode selectedFields = inputs.putArray("selectedFieldIds");
        for (DashboardDimension dimension : dimensions) {
            selectedFields.add(dimension.field());
        }
    }

    private void addSemanticAxisProvenance(
            ObjectNode plan,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            List<DashboardDimension> dimensions) {
        ObjectNode diagnostics = plan.putObject("diagnostics");
        diagnostics.put("schemaVersion", "praxis-ui-composition-plan-diagnostics.v1");
        diagnostics.put("visualizationDecisionSchemaVersion",
                visualizationDecision == null ? "" : safe(visualizationDecision.schemaVersion()));
        diagnostics.put("visualizationDecisionIntent",
                visualizationDecision == null ? "generic-dashboard" : safe(visualizationDecision.intent()));
        diagnostics.put("visualizationDecisionProvenance",
                visualizationDecision == null
                        ? "generic-dashboard-field-inference"
                        : safe(visualizationDecision.provenance()));
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

    private void addDashboardBindings(
            ObjectNode plan,
            AgenticAuthoringCandidate candidate,
            List<DashboardDimension> dimensions) {
        String filterKey = widgetKey(candidate, "filter");
        String tableKey = widgetKey(candidate, "table");
        ArrayNode bindings = plan.putArray("bindings");
        ObjectNode tableBinding = bindings.addObject();
        tableBinding.put("id", filterKey + ".requestSearch->" + tableKey + ".queryContext");
        addComponentPortEndpoint(tableBinding.putObject("from"), filterKey, "requestSearch", "output");
        addComponentPortEndpoint(tableBinding.putObject("to"), tableKey, "queryContext", "input");
        tableBinding.putObject("transform").put("kind", "identity");
        for (DashboardDimension dimension : dimensions) {
            String chartKey = widgetKey(candidate, "chart-" + dimension.field());
            ObjectNode filterBinding = bindings.addObject();
            filterBinding.put("id", filterKey + ".requestSearch->" + chartKey + ".queryContext");
            addComponentPortEndpoint(filterBinding.putObject("from"), filterKey, "requestSearch", "output");
            addComponentPortEndpoint(filterBinding.putObject("to"), chartKey, "queryContext", "input");
            filterBinding.putObject("transform").put("kind", "identity");

            ObjectNode drilldownBinding = bindings.addObject();
            drilldownBinding.put("id", chartKey + ".selectionChange->" + tableKey + ".queryContext");
            addComponentPortEndpoint(drilldownBinding.putObject("from"), chartKey, "selectionChange", "output");
            addComponentPortEndpoint(drilldownBinding.putObject("to"), tableKey, "queryContext", "input");
            ObjectNode transform = drilldownBinding.putObject("transform");
            transform.put("kind", "pick-path");
            transform.put("path", "filters." + dimension.field());
        }
    }

    private void addDashboardCanvas(
            ObjectNode plan,
            AgenticAuthoringCandidate candidate,
            List<DashboardDimension> dimensions) {
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        ObjectNode items = canvas.putObject("items");
        putCanvasItem(items, widgetKey(candidate, "summary"), 1, 1, 12, 1);
        putCanvasItem(items, widgetKey(candidate, "kpis"), 1, 2, 12, 1);
        putCanvasItem(items, widgetKey(candidate, "filter"), 1, 3, 12, 1);

        int chartCount = Math.max(1, dimensions.size());
        int chartColSpan = chartCount == 1 ? 12 : chartCount == 2 ? 6 : 4;
        int chartRow = 4;
        int chartRowSpan = 4;
        for (int i = 0; i < dimensions.size(); i++) {
            DashboardDimension dimension = dimensions.get(i);
            int rowOffset = i / 3;
            int columnOffset = i % 3;
            int col = 1 + columnOffset * chartColSpan;
            putCanvasItem(items, widgetKey(candidate, "chart-" + dimension.field()),
                    col, chartRow + rowOffset * chartRowSpan, chartColSpan, chartRowSpan);
        }

        int chartRows = (int) Math.ceil(dimensions.size() / 3.0d);
        int tableRow = chartRow + Math.max(1, chartRows) * chartRowSpan;
        putCanvasItem(items, widgetKey(candidate, "table"), 1, tableRow, 12, 5);
    }

    private void putCanvasItem(ObjectNode items, String key, int col, int row, int colSpan, int rowSpan) {
        ObjectNode item = items.putObject(key);
        item.put("col", col);
        item.put("row", row);
        item.put("colSpan", colSpan);
        item.put("rowSpan", rowSpan);
    }

    private void addComponentPortEndpoint(ObjectNode endpoint, String widgetKey, String port, String direction) {
        endpoint.put("kind", "component-port");
        endpoint.put("widget", widgetKey);
        endpoint.put("port", port);
        endpoint.put("direction", direction);
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

    private List<DashboardDimension> dashboardDimensions(
            AgenticAuthoringVisualizationDecision visualizationDecision,
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringPlanRequest request) {
        List<DashboardDimension> dimensions = new ArrayList<>();
        if (visualizationDecision != null && visualizationDecision.axes() != null) {
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
        }
        if (dimensions.isEmpty()) {
            dimensions.addAll(inferDashboardDimensions(candidate, request));
        }
        return dimensions.stream().limit(3).toList();
    }

    private List<DashboardDimension> inferDashboardDimensions(
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringPlanRequest request) {
        Set<String> terms = new LinkedHashSet<>();
        addTerms(terms, request == null ? "" : request.userPrompt());
        addTerms(terms, candidate == null ? "" : candidate.reason());
        addTerms(terms, businessResourcePath(candidate == null ? "" : candidate.resourcePath()));
        if (candidate != null && candidate.evidenceBundle() != null) {
            for (AgenticAuthoringEvidenceBundle.Evidence evidence : candidate.evidenceBundle().evidence()) {
                addTerms(terms, evidence.summary());
                addTerms(terms, evidence.ref());
                evidence.matchedTerms().forEach(term -> addTerms(terms, term));
            }
        }
        List<DashboardDimension> dimensions = new ArrayList<>();
        addInferredDimensionIfRelevant(dimensions, terms, "status", "status", "Status", "bar", "vertical");
        addInferredDimensionIfRelevant(dimensions, terms, "severity", "severity", "Severity", "bar", "vertical");
        addInferredDimensionIfRelevant(dimensions, terms, "category", "category", "Category", "bar", "vertical");
        addInferredDimensionIfRelevant(dimensions, terms, "type", "type", "Type", "bar", "vertical");
        addInferredDimensionIfRelevant(dimensions, terms, "period", "createdAt", "Period", "line", "horizontal");
        if (dimensions.isEmpty()) {
            dimensions.add(new DashboardDimension(
                    "category",
                    "category",
                    "Category",
                    "Registros por Category",
                    "bar",
                    "vertical",
                    "count",
                    "",
                    "Total",
                    "generic-dashboard-field-inference"));
        }
        return dimensions;
    }

    private void addInferredDimensionIfRelevant(
            List<DashboardDimension> dimensions,
            Set<String> terms,
            String concept,
            String field,
            String label,
            String chartType,
            String orientation) {
        if (terms.contains(concept)
                || terms.contains(field.toLowerCase(Locale.ROOT))
                || terms.contains(label.toLowerCase(Locale.ROOT))
                || ("severity".equals(concept) && (terms.contains("gravidade") || terms.contains("severidade")))
                || ("category".equals(concept) && (terms.contains("categoria") || terms.contains("classe")))
                || ("type".equals(concept) && (terms.contains("tipo") || terms.contains("natureza")))
                || ("period".equals(concept) && (terms.contains("data") || terms.contains("periodo") || terms.contains("tempo")))) {
            dimensions.add(new DashboardDimension(
                    concept,
                    field,
                    label,
                    "Registros por " + label,
                    chartType,
                    orientation,
                    "count",
                    "",
                    "Total",
                    "generic-dashboard-field-inference"));
        }
    }

    private void addTerms(Set<String> terms, String value) {
        for (String token : normalize(value).replaceAll("[^a-z0-9]+", " ").split("\\s+")) {
            if (token.length() >= 3) {
                terms.add(token);
            }
        }
    }

    private boolean looksLikeChartRequest(AgenticAuthoringPlanRequest request) {
        String prompt = normalize(request == null ? "" : request.userPrompt());
        return prompt.contains("grafico")
                || prompt.contains("graficos")
                || prompt.contains("chart")
                || prompt.contains("dashboard")
                || prompt.contains("painel")
                || prompt.contains("indicador")
                || prompt.contains("kpi");
    }

    private String valueOrDefault(String value, String fallback) {
        String safe = safe(value);
        return safe.isBlank() ? fallback : safe;
    }

    private String defaultSchemaUrl(String resourcePath, String operation) {
        String path = businessResourcePath(resourcePath);
        if (path.isBlank()) {
            return "";
        }
        return "/schemas/filtered?path=" + path
                + "&operation=" + valueOrDefault(operation, "post").toLowerCase(Locale.ROOT)
                + "&schemaType=response";
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
