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
        AgenticAuthoringCandidate candidate = selectedCandidate(intent);
        if (intent == null
                || candidate == null
                || !"create".equals(intent.operationKind())
                || !"eligible".equals(intent.gate() == null ? "" : intent.gate().status())) {
            return Optional.empty();
        }
        String artifactKind = safe(intent.artifactKind());
        AgenticAuthoringVisualizationDecision visualizationDecision = intent.visualizationDecision();
        boolean tabsRequested = isPrimaryComponent(visualizationDecision, "praxis-tabs");
        if (!List.of("table", "dashboard", "page").contains(artifactKind)
                && !(tabsRequested && "component".equals(artifactKind))) {
            return Optional.empty();
        }
        boolean chartOnly = isChartOnlyRequest(request, visualizationDecision);
        ObjectNode plan = tabsRequested ? tabsPlan(candidate) : chartOnly ? singleChartPlan(request, candidate, visualizationDecision) : switch (artifactKind) {
            case "dashboard" -> dashboardPlan(request, candidate, visualizationDecision);
            case "page" -> pagePlan(candidate);
            default -> tablePlan(candidate);
        };
        String providerArtifactKind = chartOnly ? "chart" : artifactKind;
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:generic-resource-" + providerArtifactKind),
                plan,
                emptyCompiledFormPatch()));
    }

    private boolean isPrimaryComponent(
            AgenticAuthoringVisualizationDecision visualizationDecision,
            String componentId) {
        return visualizationDecision != null && componentId.equals(safe(visualizationDecision.primaryComponent()));
    }

    private boolean isChartOnlyRequest(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        if (!isPrimaryComponent(visualizationDecision, "praxis-chart")) {
            return false;
        }
        String layoutKind = normalize(visualizationDecision.layoutKind()).replaceAll("[^a-z0-9]+", " ").trim();
        String intent = normalize(visualizationDecision.intent()).replaceAll("[^a-z0-9]+", " ").trim();
        if (List.of("chart", "single chart", "single visualization").contains(layoutKind)
                || intent.contains("single chart")
                || intent.contains("chart only")) {
            return true;
        }
        if (!visualizationDecision.includeSummary() && !visualizationDecision.includeDetailTable()) {
            return true;
        }
        String prompt = normalize(request == null ? "" : request.userPrompt());
        boolean asksOnly = containsAny(prompt, "apenas", "somente", "so ", "only");
        boolean deniesDashboardSupport = containsAny(prompt,
                "nao crie tabela",
                "sem tabela",
                "nao crie filtros",
                "sem filtros",
                "nao crie filtro",
                "sem filtro",
                "nao crie kpi",
                "sem kpi",
                "nao crie kpis",
                "sem kpis");
        return asksOnly && deniesDashboardSupport;
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

    private ObjectNode singleChartPlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        ObjectNode plan = basePlan("single-chart-page");
        ArrayNode widgets = plan.putArray("widgets");
        List<DashboardDimension> dimensions = dashboardDimensions(visualizationDecision, candidate, request);
        DashboardDimension dimension = dimensions.isEmpty() ? unresolvedDashboardDimension() : dimensions.get(0);
        String chartKey = widgetKey(candidate, "chart-" + dimension.field());
        addChart(widgets, candidate, chartKey, dimension);
        addSemanticAxisProvenance(plan, visualizationDecision, List.of(dimension));
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        putCanvasItem(canvas.putObject("items"), chartKey, 1, 1, 12, 5);
        ObjectNode constraints = plan.putObject("compositionConstraints");
        constraints.put("mode", "single-chart");
        constraints.put("includeSummary", false);
        constraints.put("includeDetailTable", false);
        constraints.put("includeFilters", false);
        constraints.put("includeKpis", false);
        return plan;
    }

    private ObjectNode pagePlan(AgenticAuthoringCandidate candidate) {
        ObjectNode plan = basePlan("resource-master-detail");
        ArrayNode widgets = plan.putArray("widgets");
        addTable(widgets, candidate, widgetKey(candidate, "master"), "master");
        addDetail(widgets, candidate, widgetKey(candidate, "detail"));
        return plan;
    }

    private ObjectNode tabsPlan(AgenticAuthoringCandidate candidate) {
        ObjectNode plan = basePlan("resource-tabs-page");
        ArrayNode widgets = plan.putArray("widgets");
        String tabsKey = widgetKey(candidate, "tabs");
        addTabs(widgets, candidate, tabsKey);
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        putCanvasItem(canvas.putObject("items"), tabsKey, 1, 1, 12, 8);
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

    private void addTabs(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-tabs");
        widget.put("role", "workspace");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("tabsId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("enableCustomization", true);
        ObjectNode config = inputs.putObject("config");
        config.putObject("group").put("dynamicHeight", true).put("preserveContent", true);
        ArrayNode tabs = config.putArray("tabs");
        ObjectNode listTab = tabs.addObject();
        listTab.put("id", "list");
        listTab.put("textLabel", "Lista");
        addNestedTable(listTab.putArray("widgets"), candidate, widgetKey(candidate, "tabs-list"));
        ObjectNode detailsTab = tabs.addObject();
        detailsTab.put("id", "details");
        detailsTab.put("textLabel", "Detalhes");
        addNestedDetail(detailsTab.putArray("widgets"), candidate, widgetKey(candidate, "tabs-detail"));
    }

    private void addNestedTable(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-table");
        widget.put("childWidgetKey", key);
        ObjectNode outputs = widget.putObject("outputs");
        outputs.put("rowClick", "emit");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", businessResourcePath(candidate.resourcePath()));
        inputs.put("tableId", key);
        inputs.put("title", titleFromResourcePath(businessResourcePath(candidate.resourcePath())));
        inputs.putObject("config").putArray("columns");
    }

    private void addNestedDetail(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-dynamic-form");
        widget.put("childWidgetKey", key);
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", businessResourcePath(candidate.resourcePath()));
        inputs.put("formId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("mode", "view");
        inputs.put("schemaSource", "resource");
        inputs.put("enableCustomization", true);
        inputs.putNull("resourceId");
        inputs.putObject("config")
                .put("title", "Detalhes de " + titleFromResourcePath(businessResourcePath(candidate.resourcePath())));
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
        ObjectNode document = richContentDocument(widget.putObject("inputs"));
        ObjectNode statGroup = document.putArray("nodes").addObject();
        statGroup.put("type", "statGroup");
        statGroup.put("id", key + "-stats");
        statGroup.put("title", "Indicadores");
        statGroup.put("subtitle", titleFromResourcePath(businessResourcePath(candidate.resourcePath())));
        statGroup.put("layout", "grid");
        ArrayNode items = statGroup.putArray("items");
        ObjectNode total = items.addObject();
        total.put("id", key + "-total");
        total.put("label", "Total de registros");
        total.put("value", "-");
        total.put("caption", "Aguardando contagem");
        total.put("icon", "monitoring");
        total.put("tone", "info");
        for (DashboardDimension dimension : dimensions.stream().filter(this::isResolvedDimension).limit(2).toList()) {
            ObjectNode kpi = items.addObject();
            kpi.put("id", key + "-" + dimension.field());
            kpi.put("label", dimension.label());
            kpi.put("value", "-");
            kpi.put("caption", metricCaption(dimension));
            kpi.put("icon", "query_stats");
            kpi.put("tone", "neutral");
            kpi.put("dimensionField", dimension.field());
            kpi.put("schemaVerified", false);
        }
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
        for (DashboardDimension dimension : dimensions.stream().filter(this::isResolvedDimension).toList()) {
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
        putCanvasItem(items, widgetKey(candidate, "summary"), 1, 1, 12, 2);
        putCanvasItem(items, widgetKey(candidate, "kpis"), 1, 3, 12, 2);
        putCanvasItem(items, widgetKey(candidate, "filter"), 1, 5, 12, 1);

        int chartCount = Math.max(1, dimensions.size());
        int chartColSpan = chartCount == 1 ? 12 : chartCount == 2 ? 6 : 4;
        int chartRow = 6;
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
        ObjectNode document = richContentDocument(widget.putObject("inputs"));
        ObjectNode card = document.putArray("nodes").addObject();
        card.put("type", "card");
        card.put("title", titleFromResourcePath(businessResourcePath(candidate.resourcePath())));
        card.put("subtitle", "Pre-visualizacao analitica");
        card.put("variant", "filled");
        card.put("tone", "info");
        card.put("size", "sm");
        card.put("density", "compact");
        card.put("orientation", "horizontal");
        card.putObject("media")
                .put("kind", "icon")
                .put("icon", "dashboard_customize")
                .put("placement", "leading");
        ArrayNode content = card.putArray("content");
        ObjectNode body = content.addObject();
        body.put("type", "text");
        body.put("text", "Dashboard governado com indicadores, filtros, graficos e detalhe conectados pela fonte selecionada.");
    }

    private ObjectNode richContentDocument(ObjectNode inputs) {
        ObjectNode document = inputs.putObject("document");
        document.put("kind", "praxis.rich-content");
        document.put("version", "1.0.0");
        return document;
    }

    private String metricCaption(DashboardDimension dimension) {
        if (dimension == null) {
            return "Recorte pendente de verificacao";
        }
        String aggregation = dimension.metricAggregation().isBlank()
                ? "count"
                : dimension.metricAggregation().toLowerCase(Locale.ROOT);
        if (!dimension.metricField().isBlank()) {
            return aggregation.toUpperCase(Locale.ROOT) + " de " + dimension.metricLabel();
        }
        return "Agrupado por " + dimension.label();
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
                if (!isUsableDashboardAxis(axis)) {
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
            dimensions.add(unresolvedDashboardDimension());
        }
        return dimensions.stream().limit(3).toList();
    }

    private boolean isUsableDashboardAxis(AgenticAuthoringVisualizationAxisDecision axis) {
        String field = normalize(axis.field()).replaceAll("[^a-z0-9]+", " ").trim();
        String concept = normalize(axis.concept()).replaceAll("[^a-z0-9]+", " ").trim();
        String label = normalize(axis.label()).replaceAll("[^a-z0-9]+", " ").trim();
        String combined = String.join(" ", field, concept, label).trim();
        if (field.isBlank() || concept.isBlank()) {
            return false;
        }
        if (field.split("\\s+").length > 3 || concept.split("\\s+").length > 3) {
            return false;
        }
        for (String token : List.of(
                "crie",
                "criar",
                "quero",
                "preciso",
                "dashboard",
                "painel",
                "grafico",
                "graficos",
                "chart",
                "charts",
                "tabela",
                "table",
                "filtro",
                "filtros",
                "kpi",
                "kpis")) {
            if (combined.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private DashboardDimension unresolvedDashboardDimension() {
        return new DashboardDimension(
                "unresolved",
                "unresolved",
                "Unresolved",
                "Schema-grounded dimension required",
                "bar",
                "vertical",
                "count",
                "",
                "Total",
                "schema-grounding-required");
    }

    private boolean isResolvedDimension(DashboardDimension dimension) {
        return dimension != null
                && !"unresolved".equals(dimension.field())
                && !"schema-grounding-required".equals(dimension.provenance());
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

    private boolean containsAny(String value, String... terms) {
        String normalized = normalize(value);
        for (String term : terms) {
            if (!safe(term).isBlank() && normalized.contains(normalize(term))) {
                return true;
            }
        }
        return false;
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
