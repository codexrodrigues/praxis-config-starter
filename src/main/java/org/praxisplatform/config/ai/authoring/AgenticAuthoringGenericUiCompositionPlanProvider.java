package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
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
    private final AgenticAuthoringChartCapabilityCatalog chartCapabilityCatalog =
            AgenticAuthoringChartCapabilityCatalog.INSTANCE;

    public AgenticAuthoringGenericUiCompositionPlanProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AgenticAuthoringUiCompositionPlanResult> plan(AgenticAuthoringPlanRequest request) {
        Optional<AgenticAuthoringUiCompositionPlanResult> chartModification = chartModification(request);
        if (chartModification.isPresent()) {
            return chartModification;
        }
        AgenticAuthoringIntentResolutionResult intent = request == null ? null : request.intentResolution();
        AgenticAuthoringCandidate candidate = selectedCandidate(intent);
        AgenticAuthoringVisualizationDecision visualizationDecision =
                intent == null ? null : intent.visualizationDecision();
        if (intent == null
                || candidate == null
                || !"create".equals(intent.operationKind())
                || !"eligible".equals(intent.gate() == null ? "" : intent.gate().status())) {
            return Optional.empty();
        }
        String artifactKind = safe(intent.artifactKind());
        boolean tabsDenied = excludesComponent(visualizationDecision, "praxis-tabs");
        boolean tabsRequested = !tabsDenied
                && (isPrimaryComponent(visualizationDecision, "praxis-tabs")
                || hasLayoutKind(visualizationDecision, "tabs", "tabbed", "tabbed-resource-workspace")
                || hasVisualIntent(visualizationDecision, "tab", "tabbed", "tabs"));
        boolean expansionDenied = excludesComponent(visualizationDecision, "praxis-expansion");
        boolean expansionRequested = !expansionDenied
                && (isPrimaryComponent(visualizationDecision, "praxis-expansion")
                || hasLayoutKind(visualizationDecision, "accordion", "expansion", "expansion-panels", "collapsible-panels")
                || hasVisualIntent(visualizationDecision, "accordion", "expansion", "expansivel", "paineis"));
        if (!List.of("table", "dashboard", "page", "chart").contains(artifactKind)
                && !(tabsRequested && "component".equals(artifactKind))
                && !(expansionRequested && "component".equals(artifactKind))) {
            return Optional.empty();
        }
        if (("dashboard".equals(artifactKind) || "chart".equals(artifactKind)) && visualizationDecision == null) {
            return Optional.empty();
        }
        boolean chartOnly = isChartOnlyRequest(request, visualizationDecision);
        ObjectNode plan = expansionRequested ? expansionPlan(candidate) : tabsRequested ? tabsPlan(request, candidate, visualizationDecision) : chartOnly ? singleChartPlan(request, candidate, visualizationDecision) : switch (artifactKind) {
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
        return false;
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
        boolean surfaceOpenModal = isSurfaceOpenModalDrilldown(request);
        ObjectNode plan = basePlan(surfaceOpenModal ? "chart-surface-drilldown" : "resource-dashboard");
        ArrayNode widgets = plan.putArray("widgets");
        boolean chartDashboard = visualizationDecision != null
                && "praxis-chart".equals(safe(visualizationDecision.primaryComponent()));
        if (!chartDashboard) {
            return plan;
        }
        List<DashboardDimension> dimensions = dashboardDimensions(visualizationDecision, candidate, request);
        if (includeSummary(visualizationDecision)) {
            addSummary(widgets, candidate, widgetKey(candidate, "summary"));
        }
        if (includeKpis(visualizationDecision)) {
            addKpis(widgets, candidate, widgetKey(candidate, "kpis"), dimensions);
        }
        if (includeFilters(visualizationDecision)) {
            addFilter(widgets, candidate, widgetKey(candidate, "filter"), dimensions);
        }
        for (DashboardDimension dimension : dimensions) {
            addChart(widgets, candidate, widgetKey(candidate, "chart-" + dimension.field()), dimension);
        }
        addSemanticAxisProvenance(plan, visualizationDecision, dimensions);
        if (surfaceOpenModal) {
            addSurfaceOpenDrilldownBinding(plan, candidate, dimensions);
        } else if (includeDetailTable(visualizationDecision)) {
            addTable(widgets, candidate, widgetKey(candidate, "table"), "detail");
        }
        if (!surfaceOpenModal) {
            addDashboardBindings(plan, candidate, dimensions, visualizationDecision);
        }
        addDashboardCanvas(plan, candidate, dimensions, visualizationDecision, surfaceOpenModal);
        return plan;
    }

    private boolean isSurfaceOpenModalDrilldown(AgenticAuthoringPlanRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        boolean contextualAction = contextHints != null
                && "contextual-preview-action".equals(safe(contextHints.path("kind").asText()))
                && "surface.open".equals(safe(contextHints.path("surfaceActionId").asText()))
                && "modal".equals(safe(contextHints.path("surfacePresentation").asText()))
                && "praxis-table".equals(safe(contextHints.path("surfaceWidgetId").asText()));
        if (contextualAction) {
            return true;
        }
        AgenticAuthoringIntentResolutionResult intent = request == null ? null : request.intentResolution();
        String changeKind = intent == null ? "" : safe(intent.changeKind());
        String artifactKind = intent == null ? "" : safe(intent.artifactKind());
        String targetComponentId = intent == null || intent.target() == null
                ? ""
                : safe(intent.target().componentId());
        return intent != null
                && "modify".equals(safe(intent.operationKind()))
                && "enable_chart_drilldown".equals(changeKind)
                && ("chart".equals(artifactKind) || "dashboard".equals(artifactKind))
                && (targetComponentId.isBlank()
                || "praxis-chart".equals(targetComponentId)
                || "praxis-dynamic-page-builder".equals(targetComponentId));
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

    private ObjectNode tabsPlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        ObjectNode plan = basePlan("resource-tabs-page");
        ArrayNode widgets = plan.putArray("widgets");
        String tabsKey = widgetKey(candidate, "tabs");
        addTabs(widgets, candidate, tabsKey, request, visualizationDecision);
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        putCanvasItem(canvas.putObject("items"), tabsKey, 1, 1, 12, 8);
        return plan;
    }

    private ObjectNode expansionPlan(AgenticAuthoringCandidate candidate) {
        ObjectNode plan = basePlan("resource-expansion-page");
        ArrayNode widgets = plan.putArray("widgets");
        String expansionKey = widgetKey(candidate, "expansion");
        addExpansion(widgets, candidate, expansionKey);
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        putCanvasItem(canvas.putObject("items"), expansionKey, 1, 1, 12, 8);
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

    private void addTabs(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-tabs");
        widget.put("role", "workspace");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("tabsId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("configPersistenceStrategy", "input-first");
        inputs.put("enableCustomization", true);
        ObjectNode config = inputs.putObject("config");
        config.putObject("group").put("dynamicHeight", true).put("preserveContent", true);
        ArrayNode tabs = config.putArray("tabs");
        if (tabsShouldIncludeChart(visualizationDecision)) {
            List<DashboardDimension> dimensions = dashboardDimensions(visualizationDecision, candidate, request);
            DashboardDimension dimension = dimensions.isEmpty() ? unresolvedDashboardDimension() : dimensions.get(0);
            ObjectNode chartTab = tabs.addObject();
            chartTab.put("id", "chart");
            chartTab.put("textLabel", "Grafico");
            addNestedChart(chartTab.putArray("widgets"), candidate, widgetKey(candidate, "tabs-chart-" + dimension.field()), dimension);
            addSemanticAxisProvenance(widget, visualizationDecision, List.of(dimension));
        } else {
            ObjectNode listTab = tabs.addObject();
            listTab.put("id", "list");
            listTab.put("textLabel", "Lista");
            addNestedTable(listTab.putArray("widgets"), candidate, widgetKey(candidate, "tabs-list"));
        }
        ObjectNode detailsTab = tabs.addObject();
        detailsTab.put("id", "details");
        detailsTab.put("textLabel", "Detalhes");
        if (tabsShouldIncludeChart(visualizationDecision)) {
            addNestedTable(detailsTab.putArray("widgets"), candidate, widgetKey(candidate, "tabs-detail-table"));
        } else {
            addNestedDetail(detailsTab.putArray("widgets"), candidate, widgetKey(candidate, "tabs-detail"));
        }
    }

    private void addExpansion(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-expansion");
        widget.put("role", "workspace");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("expansionId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("enableCustomization", true);
        inputs.put("strictValidation", true);
        ObjectNode config = inputs.putObject("config");
        ObjectNode accordion = config.putObject("accordion");
        accordion.put("multi", true);
        accordion.put("displayMode", "default");
        accordion.put("togglePosition", "after");
        ArrayNode panels = config.putArray("panels");

        ObjectNode overview = panels.addObject();
        overview.put("id", "overview");
        overview.put("title", "Dados gerais");
        overview.put("description", "Resumo da fonte governada selecionada.");
        overview.put("icon", "info");
        overview.put("expanded", true);
        addNestedOverview(overview.putArray("widgets"), candidate, widgetKey(candidate, "expansion-overview"));

        ObjectNode details = panels.addObject();
        details.put("id", "details");
        details.put("title", "Detalhes");
        details.put("description", "Registros conectados ao recurso governado.");
        details.put("icon", "table_view");
        addNestedTable(details.putArray("widgets"), candidate, widgetKey(candidate, "expansion-details"));

        ObjectNode actions = panels.addObject();
        actions.put("id", "actions");
        actions.put("title", "Acoes");
        actions.put("description", "Formulario governado para revisar ou executar operacoes disponiveis.");
        actions.put("icon", "dynamic_form");
        addNestedDetail(actions.putArray("widgets"), candidate, widgetKey(candidate, "expansion-actions"));
    }

    private boolean tabsShouldIncludeChart(AgenticAuthoringVisualizationDecision visualizationDecision) {
        if (excludesComponent(visualizationDecision, "praxis-chart")) {
            return false;
        }
        return visualizationDecision != null
                && visualizationDecision.axes() != null
                && !visualizationDecision.axes().isEmpty()
                && (hasLayoutKind(visualizationDecision, "tabs-with-chart", "chart-tabs", "analytical-tabs")
                || hasVisualIntent(visualizationDecision, "chart", "charts", "grafico", "graficos", "analytical"));
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

    private void addNestedOverview(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-rich-content");
        widget.put("childWidgetKey", key);
        ObjectNode document = richContentDocument(widget.putObject("inputs"));
        ObjectNode card = document.putArray("nodes").addObject();
        card.put("type", "card");
        card.put("title", titleFromResourcePath(businessResourcePath(candidate.resourcePath())));
        card.put("subtitle", "Fonte governada");
        card.put("variant", "filled");
        card.put("tone", "info");
        card.put("size", "sm");
        card.put("density", "compact");
        ArrayNode content = card.putArray("content");
        ObjectNode body = content.addObject();
        body.put("type", "text");
        body.put("text", "Use os paineis para consultar registros, revisar detalhes e acessar acoes confirmadas pelo catalogo do host.");
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

    private void addNestedChart(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            DashboardDimension dimension) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-chart");
        widget.put("childWidgetKey", key);
        ObjectNode inputs = widget.putObject("inputs");
        populateChartConfig(inputs.putObject("config"), candidate, key, dimension);
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
        populateChartConfig(config, candidate, key, dimension);
    }

    private void populateChartConfig(
            ObjectNode config,
            AgenticAuthoringCandidate candidate,
            String key,
            DashboardDimension dimension) {
        config.put("id", key);
        config.put("type", dimension.chartType());
        config.put("orientation", dimension.orientation());
        config.put("title", dimension.title() + " - " + titleFromResourcePath(businessResourcePath(candidate.resourcePath())));
        String statsOperation = statsOperation(candidate.resourcePath(), dimension);
        boolean timeseries = "timeseries".equals(statsOperation);
        config.put("subtitle", (timeseries ? "Evolucao por " : "Contagem por ") + dimension.label());
        config.putObject("semanticAxis")
                .put("concept", dimension.concept())
                .put("field", dimension.field())
                .put("label", dimension.label())
                .put("provenance", dimension.provenance())
                .put("schemaVerified", false)
                .put("schemaProbeStatus", "pending");
        config.putObject("sizing").put("mode", "fill-container").put("minHeight", 260);
        ObjectNode axes = config.putObject("axes");
        axes.putObject("x").put("field", dimension.field()).put("label", dimension.label()).put("type", timeseries ? "time" : "category");
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
        dataSource.put("statsEndpointInference", "canonical-resource-stats-" + statsOperation);
        ObjectNode query = dataSource.putObject("query");
        query.put("sourceKind", "praxis.stats");
        query.put("statsOperation", statsOperation);
        query.put("statsPath", statsPath(candidate.resourcePath(), statsOperation));
        if (timeseries) {
            query.put("granularity", "month");
        }
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
        if (timeseries) {
            statsRequest.put("granularity", "MONTH");
            statsRequest.put("fillGaps", false);
        }
        ObjectNode statsMetric = statsRequest.putObject("metric");
        statsMetric.put("operation", dimension.metricAggregation().toUpperCase(Locale.ROOT));
        if (!dimension.metricField().isBlank()) {
            statsMetric.put("field", dimension.metricField());
        }
        statsMetric.put("alias", metricOutputField(dimension));
        statsRequest.put("limit", 12);
        if (!timeseries) {
            statsRequest.put("orderBy", "VALUE_DESC");
        }
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
            List<DashboardDimension> dimensions,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        boolean includeFilters = includeFilters(visualizationDecision);
        boolean includeDetailTable = includeDetailTable(visualizationDecision);
        if (!includeFilters && !includeDetailTable) {
            return;
        }
        String filterKey = widgetKey(candidate, "filter");
        String tableKey = widgetKey(candidate, "table");
        ArrayNode bindings = plan.putArray("bindings");
        if (includeFilters && includeDetailTable) {
            ObjectNode tableBinding = bindings.addObject();
            tableBinding.put("id", filterKey + ".requestSearch->" + tableKey + ".queryContext");
            addComponentPortEndpoint(tableBinding.putObject("from"), filterKey, "requestSearch", "output");
            addComponentPortEndpoint(tableBinding.putObject("to"), tableKey, "queryContext", "input");
            tableBinding.putObject("transform").put("kind", "identity");
        }
        for (DashboardDimension dimension : dimensions) {
            String chartKey = widgetKey(candidate, "chart-" + dimension.field());
            if (includeFilters) {
                ObjectNode filterBinding = bindings.addObject();
                filterBinding.put("id", filterKey + ".requestSearch->" + chartKey + ".queryContext");
                addComponentPortEndpoint(filterBinding.putObject("from"), filterKey, "requestSearch", "output");
                addComponentPortEndpoint(filterBinding.putObject("to"), chartKey, "queryContext", "input");
                filterBinding.putObject("transform").put("kind", "identity");
            }

            if (includeDetailTable) {
                ObjectNode drilldownBinding = bindings.addObject();
                drilldownBinding.put("id", chartKey + ".selectionChange->" + tableKey + ".queryContext");
                addComponentPortEndpoint(drilldownBinding.putObject("from"), chartKey, "selectionChange", "output");
                addComponentPortEndpoint(drilldownBinding.putObject("to"), tableKey, "queryContext", "input");
                ObjectNode transform = drilldownBinding.putObject("transform");
                transform.put("kind", "pick-path");
                transform.put("path", "filters." + dimension.field());
            }
        }
    }

    private void addSurfaceOpenDrilldownComposition(
            ObjectNode plan,
            AgenticAuthoringCandidate candidate,
            List<DashboardDimension> dimensions) {
        DashboardDimension dimension = dimensions.isEmpty() ? unresolvedDashboardDimension() : dimensions.get(0);
        String chartKey = widgetKey(candidate, "chart-" + dimension.field());
        addSurfaceOpenDrilldownComposition(plan, chartKey, candidate, dimension);
    }

    private void addSurfaceOpenDrilldownBinding(
            ObjectNode plan,
            AgenticAuthoringCandidate candidate,
            List<DashboardDimension> dimensions) {
        DashboardDimension dimension = dimensions.isEmpty() ? unresolvedDashboardDimension() : dimensions.get(0);
        String chartKey = widgetKey(candidate, "chart-" + dimension.field());
        ArrayNode bindings = plan.withArray("bindings");
        ObjectNode binding = bindings.addObject();
        binding.put("id", chartKey + ".pointClick->surface.open");
        binding.put("intent", "command-dispatch");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "component-port");
        from.put("widget", chartKey);
        from.put("port", "pointClick");
        from.put("direction", "output");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "global-action");
        to.put("actionId", "surface.open");
        to.set("payload", surfaceOpenTablePayload(candidate, dimension));
        ObjectNode policy = binding.putObject("policy");
        policy.put("distinct", true);
        policy.put("distinctBy", "payload.category");
    }

    private void addSurfaceOpenDrilldownComposition(
            ObjectNode plan,
            String chartKey,
            AgenticAuthoringCandidate candidate,
            DashboardDimension dimension) {
        ObjectNode composition = plan.putObject("composition");
        ArrayNode links = composition.putArray("links");
        ObjectNode link = links.addObject();
        link.put("id", chartKey + ".pointClick->surface.open");
        link.put("intent", "command-dispatch");
        ObjectNode from = link.putObject("from");
        from.put("kind", "component-port");
        ObjectNode fromRef = from.putObject("ref");
        fromRef.put("widget", chartKey);
        fromRef.put("port", "pointClick");
        fromRef.put("direction", "output");
        ObjectNode to = link.putObject("to");
        to.put("kind", "global-action");
        ObjectNode toRef = to.putObject("ref");
        toRef.put("actionId", "surface.open");
        toRef.set("payload", surfaceOpenTablePayload(candidate, dimension));
        ObjectNode policy = link.putObject("policy");
        policy.put("distinct", true);
        policy.put("distinctBy", "payload.category");
    }

    private ObjectNode surfaceOpenTablePayload(
            AgenticAuthoringCandidate candidate,
            DashboardDimension dimension) {
        String resourcePath = businessResourcePath(candidate.resourcePath());
        String key = widgetKey(candidate, "modal-table-" + dimension.field());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("presentation", "modal");
        payload.put("title", "Detalhes por " + dimension.label());
        payload.put("icon", "table_view");
        payload.put("subtitle", "Registros filtrados pela categoria selecionada no grafico.");
        ObjectNode size = payload.putObject("size");
        size.put("width", "920px");
        size.put("maxWidth", "94vw");
        size.put("height", "660px");
        size.put("maxHeight", "86vh");
        ObjectNode widget = payload.putObject("widget");
        widget.put("id", "praxis-table");
        ArrayNode bindingOrder = widget.putArray("bindingOrder");
        bindingOrder.add("tableId");
        bindingOrder.add("componentInstanceId");
        bindingOrder.add("resourcePath");
        bindingOrder.add("config");
        bindingOrder.add("queryContext");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", resourcePath);
        inputs.put("tableId", key);
        inputs.put("componentInstanceId", key);
        inputs.putObject("queryContext").putObject("filters");
        ObjectNode config = inputs.putObject("config");
        config.put("title", titleFromResourcePath(resourcePath));
        addSurfaceOpenTableColumns(config.putArray("columns"), dimension);
        ArrayNode bindings = payload.putArray("bindings");
        ObjectNode binding = bindings.addObject();
        binding.put("from", "payload.category");
        binding.put("to", "widget.inputs.queryContext.filters." + canonicalFieldName(dimension.field()));
        return payload;
    }

    private void addSurfaceOpenTableColumns(ArrayNode columns, DashboardDimension dimension) {
        addTableColumn(columns, canonicalFieldName(dimension.field()), dimension.label(), "text");
        if (!dimension.metricField().isBlank()
                && !normalize(dimension.metricField()).equals(normalize(dimension.field()))) {
            addTableColumn(columns, canonicalFieldName(dimension.metricField()), dimension.metricLabel(), "number");
        }
    }

    private void addTableColumn(ArrayNode columns, String field, String label, String type) {
        String safeField = safe(field);
        if (safeField.isBlank()) {
            return;
        }
        ObjectNode column = columns.addObject();
        column.put("field", safeField);
        column.put("header", valueOrDefault(label, titleFromResourcePath(safeField)));
        column.put("type", valueOrDefault(type, "text"));
    }

    private void addDashboardCanvas(
            ObjectNode plan,
            AgenticAuthoringCandidate candidate,
            List<DashboardDimension> dimensions,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        addDashboardCanvas(plan, candidate, dimensions, visualizationDecision, false);
    }

    private void addDashboardCanvas(
            ObjectNode plan,
            AgenticAuthoringCandidate candidate,
            List<DashboardDimension> dimensions,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            boolean surfaceOpenModal) {
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        ObjectNode items = canvas.putObject("items");
        int nextRow = 1;
        if (includeSummary(visualizationDecision)) {
            putCanvasItem(items, widgetKey(candidate, "summary"), 1, nextRow, 12, 2);
            nextRow += 2;
        }
        if (includeKpis(visualizationDecision)) {
            putCanvasItem(items, widgetKey(candidate, "kpis"), 1, nextRow, 12, 2);
            nextRow += 2;
        }
        if (includeFilters(visualizationDecision)) {
            putCanvasItem(items, widgetKey(candidate, "filter"), 1, nextRow, 12, 1);
            nextRow += 1;
        }

        int chartCount = Math.max(1, dimensions.size());
        int chartColSpan = chartCount == 1 ? 12 : chartCount == 2 ? 6 : 4;
        int chartRow = nextRow;
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
        if (!surfaceOpenModal && includeDetailTable(visualizationDecision)) {
            putCanvasItem(items, widgetKey(candidate, "table"), 1, tableRow, 12, 5);
        }
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

    private Optional<AgenticAuthoringUiCompositionPlanResult> chartModification(AgenticAuthoringPlanRequest request) {
        if (!supportsChartModification(request)) {
            return Optional.empty();
        }
        ObjectNode page = chartActionPage(request);
        ObjectNode chartWidget = findWidget(page, chartTargetWidgetKey(request));
        if (chartWidget == null) {
            chartWidget = findSingleWidgetByComponent(page, "praxis-chart");
        }
        if (chartWidget == null || !"praxis-chart".equals(widgetComponentId(chartWidget))) {
            return Optional.empty();
        }
        ObjectNode config = widgetInputs(chartWidget).with("config");
        if (isSurfaceOpenModalDrilldown(request)) {
            AgenticAuthoringCandidate candidate = candidateFromChartWidget(chartWidget);
            DashboardDimension dimension = dimensionFromChartWidget(chartWidget);
            String chartKey = widgetKeyFromWidget(chartWidget, candidate, dimension);
            enableChartDrilldownInteraction(chartWidget);
            enableChartSurfaceOpenOutput(chartWidget, candidate, dimension);
            addSurfaceOpenDrilldownComposition(page, chartKey, candidate, dimension);
            return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                    true,
                    List.of(),
                    List.of("ui-composition-plan-provider:generic-chart-surface-open-modification"),
                    null,
                    compiledPagePatch(page, "modify-existing-chart-surface-open")));
        }
        String prompt = normalize(request.userPrompt());
        String changeKind = valueOrDefault(request.intentResolution().changeKind(), "");
        boolean changed = "set_chart_type".equals(changeKind) || chartCapabilityCatalog.supports(changeKind, prompt)
                ? applyChartType(config, prompt, changeKind)
                : false;
        if (!changed) {
            return Optional.empty();
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:generic-chart-modification"),
                null,
                compiledPagePatch(page, "modify-existing-chart")));
    }

    private void enableChartDrilldownInteraction(ObjectNode chartWidget) {
        ObjectNode interactions = widgetInputs(chartWidget)
                .with("config")
                .with("interactions");
        interactions.put("pointClick", true);
        interactions.put("selection", true);
    }

    private void enableChartSurfaceOpenOutput(
            ObjectNode chartWidget,
            AgenticAuthoringCandidate candidate,
            DashboardDimension dimension) {
        ObjectNode outputs = chartWidget
                .with("definition")
                .with("outputs");
        ObjectNode pointClick = outputs.putObject("pointClick");
        pointClick.put("type", "surface.open");
        pointClick.set("params", surfaceOpenTablePayload(candidate, dimension));
        outputs.put("selectionChange", "emit");
    }

    private boolean supportsChartModification(AgenticAuthoringPlanRequest request) {
        if (request == null
                || request.intentResolution() == null) {
            return false;
        }
        if (!isMaterializedPage(request.currentPage())
                && !isMaterializedPage(contextPreviewPage(request))
                && !isWidgetSnapshot(contextTargetWidgetSnapshot(request))) {
            return false;
        }
        String prompt = normalize(request.userPrompt());
        String changeKind = valueOrDefault(request.intentResolution().changeKind(), "");
        String artifactKind = valueOrDefault(request.intentResolution().artifactKind(), "");
        String targetComponentId = request.intentResolution().target() == null
                ? ""
                : valueOrDefault(request.intentResolution().target().componentId(), "");
        boolean structurallyChartTarget = targetComponentId.isBlank()
                || "praxis-chart".equals(targetComponentId)
                || "praxis-dynamic-page-builder".equals(targetComponentId);
        return "modify".equals(request.intentResolution().operationKind())
                && ("dashboard".equals(artifactKind) || "chart".equals(artifactKind))
                && structurallyChartTarget
                && ("set_chart_type".equals(changeKind)
                || chartCapabilityCatalog.supports(changeKind, prompt));
    }

    private ObjectNode chartActionPage(AgenticAuthoringPlanRequest request) {
        JsonNode currentPage = request.currentPage();
        if (isMaterializedPage(currentPage) && currentPage.isObject()) {
            return currentPage.deepCopy();
        }
        JsonNode previewPage = contextPreviewPage(request);
        if (isMaterializedPage(previewPage) && previewPage.isObject()) {
            return previewPage.deepCopy();
        }
        JsonNode targetWidgetSnapshot = contextTargetWidgetSnapshot(request);
        if (isWidgetSnapshot(targetWidgetSnapshot) && targetWidgetSnapshot.isObject()) {
            ObjectNode page = objectMapper.createObjectNode();
            page.putArray("widgets").add(targetWidgetSnapshot.deepCopy());
            return page;
        }
        return objectMapper.createObjectNode();
    }

    private JsonNode contextPreviewPage(AgenticAuthoringPlanRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        if (contextHints == null || contextHints.isMissingNode() || contextHints.isNull()) {
            return null;
        }
        JsonNode previewPage = contextHints.path("previewPage");
        if (!previewPage.isMissingNode() && !previewPage.isNull()) {
            return previewPage;
        }
        return contextHints.path("materializedPage");
    }

    private JsonNode contextTargetWidgetSnapshot(AgenticAuthoringPlanRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        if (contextHints == null || contextHints.isMissingNode() || contextHints.isNull()) {
            return null;
        }
        return contextHints.path("targetWidgetSnapshot");
    }

    private boolean isMaterializedPage(JsonNode page) {
        return page != null
                && page.isObject()
                && page.path("widgets").isArray()
                && !page.path("widgets").isEmpty();
    }

    private boolean isWidgetSnapshot(JsonNode widget) {
        return widget != null
                && widget.isObject()
                && !widgetComponentId((ObjectNode) widget).isBlank();
    }

    private String chartTargetWidgetKey(AgenticAuthoringPlanRequest request) {
        AgenticAuthoringTarget target = request.intentResolution() == null
                ? null
                : request.intentResolution().target();
        String targetWidgetKey = target == null ? "" : valueOrDefault(target.widgetKey(), "");
        if (!targetWidgetKey.isBlank()) {
            return targetWidgetKey;
        }
        JsonNode contextHints = request.contextHints();
        return valueOrDefault(jsonText(contextHints, "targetWidgetKey"),
                jsonText(contextHints, "selectedWidgetKey"));
    }

    private boolean applyChartType(ObjectNode config, String prompt, String changeKind) {
        String type = chartCapabilityCatalog.resolveField("set_chart_type", prompt).orElse("");
        if (type.isBlank()) {
            type = chartCapabilityCatalog.resolveField(changeKind, prompt).orElse("");
        }
        if (type.isBlank()) {
            return false;
        }
        config.put("type", type);
        ArrayNode series = config.withArray("series");
        if (!series.isEmpty() && series.get(0).isObject()) {
            ((ObjectNode) series.get(0)).put("type", type);
        }
        return true;
    }

    private ObjectNode findWidget(ObjectNode page, String widgetKey) {
        if (widgetKey == null || widgetKey.isBlank()) {
            return null;
        }
        JsonNode widgets = page.path("widgets");
        if (!widgets.isArray()) {
            return null;
        }
        for (JsonNode widget : widgets) {
            if (widget.isObject() && widgetKey.equals(widget.path("key").asText())) {
                return (ObjectNode) widget;
            }
        }
        return null;
    }

    private ObjectNode findSingleWidgetByComponent(ObjectNode page, String componentId) {
        JsonNode widgets = page.path("widgets");
        if (!widgets.isArray()) {
            return null;
        }
        ObjectNode match = null;
        for (JsonNode widget : widgets) {
            if (!widget.isObject() || !componentId.equals(widgetComponentId((ObjectNode) widget))) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = (ObjectNode) widget;
        }
        return match;
    }

    private String widgetComponentId(ObjectNode widget) {
        return valueOrDefault(widget.path("componentId").asText(""),
                widget.path("definition").path("id").asText(""));
    }

    private ObjectNode widgetInputs(ObjectNode widget) {
        JsonNode directInputs = widget.path("inputs");
        if (directInputs.isObject()) {
            return (ObjectNode) directInputs;
        }
        return widget.with("definition").with("inputs");
    }

    private AgenticAuthoringCandidate candidateFromChartWidget(ObjectNode chartWidget) {
        ObjectNode inputs = widgetInputs(chartWidget);
        JsonNode config = inputs.path("config");
        JsonNode dataSource = config.path("dataSource");
        String resourcePath = firstNonBlank(
                jsonText(inputs, "resourcePath"),
                jsonText(config, "resourcePath"),
                jsonText(dataSource, "resourcePath"),
                "/api/unknown/resource");
        String operation = valueOrDefault(jsonText(dataSource, "operation"), "post");
        String submitMethod = valueOrDefault(jsonText(dataSource, "submitMethod"), operation);
        String submitUrl = valueOrDefault(jsonText(dataSource, "submitUrl"), resourcePath);
        String schemaUrl = valueOrDefault(jsonText(dataSource, "schemaUrl"), defaultSchemaUrl(resourcePath, operation));
        return new AgenticAuthoringCandidate(
                resourcePath,
                operation,
                schemaUrl,
                submitUrl,
                submitMethod,
                1.0d,
                "current-chart-widget-context",
                List.of("current-chart-widget-context"));
    }

    private DashboardDimension dimensionFromChartWidget(ObjectNode chartWidget) {
        ObjectNode inputs = widgetInputs(chartWidget);
        JsonNode config = inputs.path("config");
        JsonNode semanticAxis = config.path("semanticAxis");
        JsonNode axesX = config.path("axes").path("x");
        JsonNode series = config.path("series").isArray() && !config.path("series").isEmpty()
                ? config.path("series").get(0)
                : null;
        String field = firstNonBlank(
                canonicalFieldName(jsonText(semanticAxis, "field")),
                canonicalFieldName(jsonText(axesX, "field")),
                canonicalFieldName(jsonText(series, "categoryField")),
                "category");
        String label = firstNonBlank(
                jsonText(semanticAxis, "label"),
                jsonText(axesX, "label"),
                titleFromResourcePath(field));
        String chartType = firstNonBlank(
                jsonText(config, "type"),
                jsonText(series, "type"),
                "bar");
        String orientation = valueOrDefault(jsonText(config, "orientation"), "vertical");
        String metricField = firstNonBlank(
                canonicalFieldName(jsonText(series == null ? null : series.path("value"), "field")),
                canonicalFieldName(jsonText(series, "valueField")),
                "");
        String metricAggregation = firstNonBlank(
                jsonText(series == null ? null : series.path("value"), "aggregation"),
                jsonText(series, "aggregation"),
                "count");
        return new DashboardDimension(
                valueOrDefault(jsonText(semanticAxis, "concept"), field),
                field,
                label,
                "Registros por " + label,
                chartType,
                orientation,
                metricAggregation,
                metricField,
                metricField.isBlank() ? "Total" : titleFromResourcePath(metricField),
                valueOrDefault(jsonText(semanticAxis, "provenance"), "current-chart-widget-context"));
    }

    private String widgetKeyFromWidget(
            ObjectNode chartWidget,
            AgenticAuthoringCandidate candidate,
            DashboardDimension dimension) {
        String key = chartWidget.path("key").asText("");
        return key.isBlank() ? widgetKey(candidate, "chart-" + dimension.field()) : key;
    }

    private ObjectNode compiledPagePatch(ObjectNode page, String profileId) {
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("version", "1.0.0");
        patch.put("profileId", profileId);
        patch.put("targetComponentId", "praxis-dynamic-page-builder");
        patch.putObject("patch").set("page", page);
        patch.putObject("compatibility")
                .put("aiHttpContract", "v1.1")
                .put("publicResponseKind", "patch")
                .put("requiresV12", false);
        patch.put("builderVersion", "generic-ui-composition-plan-provider@0.1.0-draft");
        patch.putArray("warnings").add("compiled-as-current-page-modification");
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
            AgenticAuthoringVisualizationAxisDecision sharedMetricAxis = sharedMetricAxis(visualizationDecision.axes());
            for (AgenticAuthoringVisualizationAxisDecision axis : visualizationDecision.axes()) {
                if (axis == null || safe(axis.field()).isBlank() || safe(axis.concept()).isBlank()) {
                    continue;
                }
                if (isMetricOnlyAxis(axis)) {
                    continue;
                }
                if (!isUsableDashboardAxis(axis)) {
                    continue;
                }
                String metricAggregation = valueOrDefault(axis.metricAggregation(), "count");
                String metricField = valueOrDefault(axis.metricField(), "");
                String metricLabel = valueOrDefault(axis.metricLabel(), "Total");
                if (metricField.isBlank() && sharedMetricAxis != null) {
                    metricAggregation = valueOrDefault(sharedMetricAxis.metricAggregation(), metricAggregation);
                    metricField = valueOrDefault(sharedMetricAxis.metricField(), sharedMetricAxis.field());
                    metricLabel = valueOrDefault(sharedMetricAxis.metricLabel(),
                            valueOrDefault(sharedMetricAxis.label(), metricLabel));
                }
                String field = canonicalFieldName(axis.field());
                metricField = canonicalFieldName(metricField);
                dimensions.add(new DashboardDimension(
                        safe(axis.concept()),
                        field,
                        valueOrDefault(axis.label(), titleFromResourcePath(field)),
                        "Registros por " + valueOrDefault(axis.label(), field),
                        valueOrDefault(axis.chartType(), "bar"),
                        valueOrDefault(axis.orientation(), "vertical"),
                        metricAggregation,
                        metricField,
                        metricLabel,
                        valueOrDefault(axis.provenance(), "llm-authored-semantic-axis")));
            }
        }
        if (dimensions.isEmpty()) {
            dimensions.add(unresolvedDashboardDimension());
        }
        return dimensions.stream().limit(3).toList();
    }

    private AgenticAuthoringVisualizationAxisDecision sharedMetricAxis(
            List<AgenticAuthoringVisualizationAxisDecision> axes) {
        if (axes == null) {
            return null;
        }
        return axes.stream()
                .filter(this::isMetricOnlyAxis)
                .findFirst()
                .orElse(null);
    }

    private boolean isMetricOnlyAxis(AgenticAuthoringVisualizationAxisDecision axis) {
        if (axis == null) {
            return false;
        }
        String metricField = safe(axis.metricField());
        String aggregation = normalize(axis.metricAggregation()).trim();
        String concept = normalize(axis.concept()).replaceAll("[^a-z0-9]+", " ").trim();
        boolean aggregateMetric = !metricField.isBlank()
                && !Set.of("", "count", "contagem").contains(aggregation);
        return aggregateMetric
                && (normalize(axis.field()).equals(normalize(metricField))
                || concept.equals("metric")
                || concept.equals("metrica")
                || concept.equals("measure")
                || concept.equals("medida"));
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

    private boolean includeSummary(AgenticAuthoringVisualizationDecision visualizationDecision) {
        return visualizationDecision != null && visualizationDecision.includeSummary();
    }

    private boolean includeDetailTable(AgenticAuthoringVisualizationDecision visualizationDecision) {
        return visualizationDecision != null
                && (visualizationDecision.includeDetailTable()
                && !excludesComponent(visualizationDecision, "praxis-table"));
    }

    private boolean includeFilters(AgenticAuthoringVisualizationDecision visualizationDecision) {
        return visualizationDecision != null
                && (visualizationDecision.includeFilters()
                && !excludesComponent(visualizationDecision, "praxis-filter"));
    }

    private boolean includeKpis(AgenticAuthoringVisualizationDecision visualizationDecision) {
        return visualizationDecision != null
                && (visualizationDecision.includeKpis()
                && !excludesComponent(visualizationDecision, "praxis-rich-content")
                && !excludesComponent(visualizationDecision, "praxis-kpi"));
    }

    private boolean excludesComponent(
            AgenticAuthoringVisualizationDecision visualizationDecision,
            String componentId) {
        if (visualizationDecision == null || visualizationDecision.excludedComponentIds() == null) {
            return false;
        }
        String expected = normalize(componentId);
        return visualizationDecision.excludedComponentIds().stream()
                .map(this::normalize)
                .anyMatch(expected::equals);
    }

    private boolean hasLayoutKind(
            AgenticAuthoringVisualizationDecision visualizationDecision,
            String... expectedLayoutKinds) {
        if (visualizationDecision == null) {
            return false;
        }
        String layoutKind = normalize(visualizationDecision.layoutKind()).replaceAll("[^a-z0-9]+", " ").trim();
        for (String expected : expectedLayoutKinds) {
            if (layoutKind.equals(normalize(expected).replaceAll("[^a-z0-9]+", " ").trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasVisualIntent(
            AgenticAuthoringVisualizationDecision visualizationDecision,
            String... expectedTokens) {
        if (visualizationDecision == null) {
            return false;
        }
        String intent = " " + normalize(visualizationDecision.intent()).replaceAll("[^a-z0-9]+", " ").trim() + " ";
        for (String expectedToken : expectedTokens) {
            String token = normalize(expectedToken).replaceAll("[^a-z0-9]+", " ").trim();
            if (!token.isBlank() && intent.contains(" " + token + " ")) {
                return true;
            }
        }
        return false;
    }

    private String valueOrDefault(String value, String fallback) {
        String safe = safe(value);
        return safe.isBlank() ? fallback : safe;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String safe = safe(value);
            if (!safe.isBlank()) {
                return safe;
            }
        }
        return "";
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
        return statsPath(resourcePath, "group-by");
    }

    private String statsPath(String resourcePath, String statsOperation) {
        String value = businessResourcePath(resourcePath);
        if (value.isBlank()) {
            return "";
        }
        String operation = valueOrDefault(statsOperation, "group-by");
        return value + "/stats/" + operation;
    }

    private String statsOperation(String resourcePath, DashboardDimension dimension) {
        String normalizedPath = safe(resourcePath).toLowerCase(Locale.ROOT);
        if (normalizedPath.endsWith("/stats/timeseries")
                || "temporal".equalsIgnoreCase(safe(dimension == null ? "" : dimension.orientation()))) {
            return "timeseries";
        }
        return "group-by";
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

    private String canonicalFieldName(String value) {
        String safe = safe(value).trim();
        if (safe.isBlank()) {
            return "";
        }
        String ascii = Normalizer.normalize(safe, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String[] parts = ascii.replaceAll("[^A-Za-z0-9]+", " ").trim().split("\\s+");
        if (parts.length > 1) {
            StringBuilder builder = new StringBuilder(parts[0].isBlank()
                    ? ""
                    : parts[0].substring(0, 1).toLowerCase(Locale.ROOT) + parts[0].substring(1));
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].isBlank()) {
                    continue;
                }
                builder.append(parts[i].substring(0, 1).toUpperCase(Locale.ROOT));
                if (parts[i].length() > 1) {
                    builder.append(parts[i].substring(1));
                }
            }
            return builder.toString();
        }
        if (safe.length() <= 1 || safe.equals(safe.toUpperCase(Locale.ROOT))) {
            return safe;
        }
        return safe.substring(0, 1).toLowerCase(Locale.ROOT) + safe.substring(1);
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

    private String jsonText(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || field == null || field.isBlank()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText("").trim() : "";
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
