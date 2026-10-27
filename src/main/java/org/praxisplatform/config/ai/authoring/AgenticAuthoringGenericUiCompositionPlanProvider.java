package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host-neutral page composition planner for resource-backed authoring.
 *
 * <p>This provider materializes only generic component skeletons from the selected candidate and
 * resolved artifact kind. Business-specific layouts remain host-owned providers.</p>
 */
public class AgenticAuthoringGenericUiCompositionPlanProvider implements
        AgenticAuthoringUiCompositionPlanProvider,
        AgenticAuthoringPersistedUiCompositionPlanProvider {

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringChartCapabilityCatalog chartCapabilityCatalog =
            AgenticAuthoringChartCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringWorkbenchAndRelatedResourcePlanSupport workbenchPlanSupport;
    private static final Pattern FIELD_DECLARATION_PATTERN = Pattern.compile(
            "(?:field|fieldName|name|id|property|path|column|campo|coluna)\\s*[:=]\\s*['\\\"]?([A-Za-z_][A-Za-z0-9_.-]{1,80})",
            Pattern.CASE_INSENSITIVE);

    public AgenticAuthoringGenericUiCompositionPlanProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.workbenchPlanSupport = new AgenticAuthoringWorkbenchAndRelatedResourcePlanSupport(
                this,
                objectMapper,
                chartCapabilityCatalog);
    }

    @Override
    public Optional<AgenticAuthoringUiCompositionPlanResult> plan(AgenticAuthoringPlanRequest request) {
        return plan(request, null);
    }

    @Override
    public Optional<AgenticAuthoringUiCompositionPlanResult> plan(
            AgenticAuthoringPlanRequest request,
            JsonNode persistedUiCompositionPlan) {
        Optional<AgenticAuthoringUiCompositionPlanResult> tableExportModification =
                tableExportModification(request, persistedUiCompositionPlan);
        if (tableExportModification.isPresent()) {
            return tableExportModification;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> tableColumnModification =
                tableColumnModification(request, persistedUiCompositionPlan);
        if (tableColumnModification.isPresent()) {
            return tableColumnModification;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> chartModification =
                chartModification(request, persistedUiCompositionPlan);
        if (chartModification.isPresent()) {
            return chartModification;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> dashboardQualityRepair = dashboardQualityRepair(request);
        if (dashboardQualityRepair.isPresent()) {
            return dashboardQualityRepair;
        }
        AgenticAuthoringIntentResolutionResult intent = request == null ? null : request.intentResolution();
        AgenticAuthoringCandidate candidate = selectedCandidate(intent);
        AgenticAuthoringSemanticDecision semanticDecision = intent == null ? null : intent.semanticDecision();
        AgenticAuthoringVisualizationDecision visualizationDecision =
                semanticDecision != null && semanticDecision.visualizationDecision() != null
                        ? semanticDecision.visualizationDecision()
                        : intent == null ? null : intent.visualizationDecision();
        String operationKind = semanticDecision != null && !safe(semanticDecision.operationKind()).isBlank()
                ? safe(semanticDecision.operationKind())
                : intent == null ? "" : safe(intent.operationKind());
        String artifactKind = semanticDecision != null && !safe(semanticDecision.artifactKind()).isBlank()
                ? safe(semanticDecision.artifactKind())
                : intent == null ? "" : safe(intent.artifactKind());
        if (intent == null
                || candidate == null
                || !"create".equals(operationKind)
                || !"eligible".equals(intent.gate() == null ? "" : intent.gate().status())) {
            return Optional.empty();
        }
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
        boolean masterDetailRequested = "page".equals(artifactKind)
                && hasLayoutKind(
                        visualizationDecision,
                        "resource-master-detail",
                        "resource-action-workbench",
                        "approval-workbench",
                        "workflow-action-workbench");
        if ("page".equals(artifactKind) && hasLayoutKind(visualizationDecision, "resource-master-detail")) {
            Optional<AgenticAuthoringUiCompositionPlanResult> rejected =
                    workbenchPlanSupport.rejectUnverifiedMasterDetailGrounding(request, candidate);
            if (rejected.isPresent()) {
                return rejected;
            }
        }
        boolean relatedResourceRequested = "page".equals(artifactKind)
                && hasLayoutKind(visualizationDecision, "parent-child-related-resource")
                && (isPrimaryComponent(visualizationDecision, "praxis-related-resource-outlet") || isPrimaryComponent(visualizationDecision, "praxis-table"));
        boolean crudRequested = !masterDetailRequested
                && !relatedResourceRequested
                && !excludesComponent(visualizationDecision, "praxis-crud")
                && isPrimaryComponent(visualizationDecision, "praxis-crud");
        if (!List.of("table", "dashboard", "page", "chart").contains(artifactKind)
                && !(tabsRequested && "component".equals(artifactKind))
                && !(expansionRequested && "component".equals(artifactKind))) {
            return Optional.empty();
        }
        if ("chart".equals(artifactKind) && visualizationDecision == null) {
            return Optional.empty();
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> governedAnalyticsFailure =
                governedAnalyticsFailure(request);
        if (governedAnalyticsFailure.isPresent()) {
            return governedAnalyticsFailure;
        }
        boolean chartOnly = isChartOnlyRequest(request, visualizationDecision);
        boolean dashboardMaterialization = shouldMaterializeDashboard(request, artifactKind, visualizationDecision);
        ObjectNode plan = relatedResourceRequested ? relatedResourcePagePlan(request, candidate, visualizationDecision)
                : masterDetailRequested ? pagePlan(request, candidate, visualizationDecision) : crudRequested ? crudPlan(request, candidate) : expansionRequested ? expansionPlan(candidate) : tabsRequested ? tabsPlan(request, candidate, visualizationDecision) : chartOnly ? singleChartPlan(request, candidate, visualizationDecision) : switch (artifactKind) {
            case "dashboard" -> dashboardPlan(request, candidate, visualizationDecision);
            case "table" -> dashboardMaterialization
                    ? dashboardPlan(request, candidate, visualizationDecision)
                    : tablePlan(request, candidate);
            case "page" -> pagePlan(request, candidate, visualizationDecision);
            default -> dashboardMaterialization
                    ? dashboardPlan(request, candidate, visualizationDecision)
                    : tablePlan(request, candidate);
        };
        preserveComponentSelectionAudit(request, plan);
        String providerArtifactKind = relatedResourceRequested ? "related-resource-page" : masterDetailRequested ? "page" : crudRequested ? "crud" : chartOnly ? "chart" : dashboardMaterialization ? "dashboard" : artifactKind;
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:generic-resource-" + providerArtifactKind),
                plan,
                emptyCompiledFormPatch()));
    }

    private void preserveComponentSelectionAudit(
            AgenticAuthoringPlanRequest request,
            ObjectNode plan) {
        JsonNode selection = request == null || request.contextHints() == null
                ? MissingNode.getInstance()
                : request.contextHints().path("componentSelection");
        if (plan == null || !selection.isObject()) {
            return;
        }
        ObjectNode diagnostics = plan.path("diagnostics") instanceof ObjectNode existing
                ? existing
                : plan.putObject("diagnostics");
        diagnostics.set("componentSelection", selection.deepCopy());
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> governedAnalyticsFailure(
            AgenticAuthoringPlanRequest request) {
        JsonNode grounding = governedAnalyticsContext(request);
        if (!"comparison".equals(grounding.path("requestedOperation").asText(""))) {
            return Optional.empty();
        }
        String status = grounding.path("status").asText("missing");
        if ("verified".equals(status) && grounding.path("projection").isObject()) {
            JsonNode projection = grounding.path("projection");
            boolean crossFilterEnabled = projection.path("interactions").path("crossFilter").asBoolean(false);
            String keyFilterField = projection.path("bindings")
                    .path("primaryDimension")
                    .path("keyFilterField")
                    .asText("")
                    .trim();
            if (!crossFilterEnabled || !keyFilterField.isBlank()) {
                JsonNode recordOpen = projection.path("interactions").path("recordOpen");
                if (!recordOpen.isObject()
                        || grounding.path("recordOpenResolution").path("schemaVerified").asBoolean(false)) {
                    return Optional.empty();
                }
                status = "record-open-resolution-required";
            } else {
                status = "key-filter-binding-required";
            }
        }
        String failure = "governed-analytics-comparison-" + slug(valueOrDefault(status, "unavailable"));
        if (Set.of(
                "operation-unavailable",
                "nominal-operation-unavailable",
                "record-open-surface-unavailable").contains(status)) {
            String availabilityReason = slug(grounding.path("availabilityReason").asText(""));
            if (!availabilityReason.isBlank()) {
                failure += "-" + availabilityReason;
            }
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                false,
                List.of(failure),
                List.of("ui-composition-plan-provider:governed-analytics-fail-closed"),
                objectMapper.createObjectNode(),
                emptyCompiledFormPatch()));
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> dashboardQualityRepair(
            AgenticAuthoringPlanRequest request) {
        if (!supportsDashboardQualityRepair(request)) {
            return Optional.empty();
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        AgenticAuthoringCandidate candidate = selectedCandidate(intent);
        if (candidate == null) {
            return Optional.empty();
        }
        ObjectNode plan = dashboardPlan(request, candidate, intent.visualizationDecision());
        ObjectNode diagnostics = plan.path("diagnostics") instanceof ObjectNode existingDiagnostics
                ? existingDiagnostics
                : plan.putObject("diagnostics");
        ObjectNode repair = diagnostics.putObject("dashboardQualityRepair");
        repair.put("schemaVersion", "praxis-dashboard-quality-repair.v1");
        repair.put("source", safe(request.contextHints().path("source").asText()));
        repair.put("kind", safe(request.contextHints().path("kind").asText()));
        repair.put("changeKind", safe(intent.changeKind()));
        repair.set("requestedWarnings", request.contextHints().path("warnings").isMissingNode()
                ? objectMapper.createArrayNode()
                : request.contextHints().path("warnings"));
        JsonNode dashboardQuality = request.contextHints().path("dashboardQuality");
        if (dashboardQuality.isObject()) {
            repair.set("qualityContext", dashboardQuality);
        }
        JsonNode repairSnapshot = dashboardRepairSnapshot(request);
        if (repairSnapshot != null && !repairSnapshot.isMissingNode() && !repairSnapshot.isNull()) {
            repair.set("inputSnapshot", dashboardRepairSnapshotSummary(repairSnapshot));
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of(
                        "ui-composition-plan-provider:generic-resource-dashboard",
                        "ui-composition-plan-provider:generic-dashboard-quality-repair"),
                plan,
                emptyCompiledFormPatch()));
    }

    private boolean supportsDashboardQualityRepair(AgenticAuthoringPlanRequest request) {
        if (request == null
                || request.intentResolution() == null
                || request.contextHints() == null
                || !request.contextHints().isObject()) {
            return false;
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        JsonNode contextHints = request.contextHints();
        String source = safe(contextHints.path("source").asText());
        String kind = safe(contextHints.path("kind").asText());
        return "modify".equals(safe(intent.operationKind()))
                && "dashboard".equals(safe(intent.artifactKind()))
                && ("dashboard-quality-gate".equals(source) || "dashboard-repair-action".equals(kind))
                && (contextHints.path("dashboardQuality").isObject()
                || contextHints.path("warnings").isArray());
    }

    private boolean shouldMaterializeDashboard(
            AgenticAuthoringPlanRequest request,
            String artifactKind,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        if ("dashboard".equals(artifactKind)) {
            return true;
        }
        if ("chart".equals(artifactKind) && !isChartOnlyRequest(request, visualizationDecision)) {
            return true;
        }
        if ("table".equals(artifactKind)
                && isPrimaryComponent(visualizationDecision, "praxis-table")
                && hasLayoutKind(visualizationDecision, "single-table")) {
            return false;
        }
        if (!"table".equals(artifactKind) && !"component".equals(artifactKind)) {
            return false;
        }
        if (visualizationDecision != null && excludesComponent(visualizationDecision, "praxis-chart")) {
            return false;
        }
        String prompt = request == null ? "" : request.userPrompt();
        return hasLayoutKind(visualizationDecision, "dashboard", "analytical-dashboard", "analytics-dashboard")
                || hasVisualIntent(visualizationDecision, "chart", "charts", "grafico", "graficos", "painel", "dashboard")
                || containsAny(prompt, "chart", "charts", "grafico", "graficos", "painel", "dashboard");
    }

    boolean isPrimaryComponent(
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
        String prompt = normalize(request == null ? "" : request.userPrompt());
        if (containsAny(prompt,
                "indicador", "indicadores", "kpi", "kpis",
                "historico detalhado", "detalhado", "detalhes",
                "painel", "dashboard")
                && !containsAny(prompt,
                        "sem tabela", "sem indicadores", "sem kpi", "sem detalhes",
                        "nao crie tabela", "nao inclua tabela", "nao mostrar tabela",
                        "apenas grafico", "apenas um grafico", "so grafico", "so um grafico",
                        "somente grafico", "somente um grafico", "unico grafico",
                        "chart only", "single chart", "only one chart", "just a chart")) {
            return false;
        }
        String layoutKind = normalize(visualizationDecision.layoutKind()).replaceAll("[^a-z0-9]+", " ").trim();
        String intent = normalize(visualizationDecision.intent()).replaceAll("[^a-z0-9]+", " ").trim();
        if (List.of("chart", "single chart", "single visualization").contains(layoutKind)
                || intent.contains("single chart")
                || intent.contains("chart only")) {
            return true;
        }
        if (request != null
                && request.intentResolution() != null
                && "dashboard".equals(safe(request.intentResolution().artifactKind()))
                && !excludesComponent(visualizationDecision, "praxis-table")) {
            return false;
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
        AgenticAuthoringSemanticDecision semanticDecision = intent.semanticDecision();
        AgenticAuthoringSemanticDecision.SelectedResource resource =
                semanticDecision == null ? null : semanticDecision.selectedResource();
        if (resource == null || safe(resource.resourcePath()).isBlank()) {
            if (intent.selectedCandidate() != null) {
                return intent.selectedCandidate();
            }
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

    private ObjectNode tablePlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate) {
        return workbenchPlanSupport.tablePlan(request, candidate);
    }

    private ObjectNode crudPlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate) {
        return workbenchPlanSupport.crudPlan(request, candidate);
    }

    private ObjectNode dashboardPlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        boolean surfaceOpenModal = isSurfaceOpenModalDrilldown(request);
        ObjectNode plan = basePlan(surfaceOpenModal ? "chart-surface-drilldown" : "resource-dashboard");
        ArrayNode widgets = plan.putArray("widgets");
        boolean chartDashboard = isChartDashboardRequest(request, visualizationDecision);
        List<DashboardDimension> dimensions = chartDashboard
                ? dashboardDimensions(visualizationDecision, candidate, request)
                : List.of();
        List<DashboardDimension> renderableDimensions = dimensions.stream()
                .filter(this::isResolvedDimension)
                .toList();
        boolean forceDashboardFilters = chartDashboard && !renderableDimensions.isEmpty();
        boolean includeNominalDetails = includeNominalDetails(request, visualizationDecision);
        boolean includeKpis = !surfaceOpenModal
                && includeNominalDetails
                && includeKpis(visualizationDecision);
        if (includeSummary(visualizationDecision)) {
            addSummary(widgets, candidate, widgetKey(candidate, "summary"), includeNominalDetails);
        }
        if (includeKpis) {
            addKpis(widgets, candidate, widgetKey(candidate, "kpis"));
        }
        if (includeFilters(visualizationDecision) || forceDashboardFilters) {
            addFilter(widgets, candidate, widgetKey(candidate, "filter"), renderableDimensions, request);
        }
        if (chartDashboard) {
            for (DashboardDimension dimension : renderableDimensions) {
                addChart(widgets, candidate, widgetKey(candidate, "chart-" + dimension.field()), dimension);
            }
        }
        addSemanticAxisProvenance(plan, visualizationDecision, dimensions, includeNominalDetails);
        addDashboardPresetMetadata(
                plan,
                candidate,
                renderableDimensions,
                visualizationDecision,
                surfaceOpenModal,
                forceDashboardFilters,
                includeKpis,
                includeNominalDetails);
        if (surfaceOpenModal && chartDashboard) {
            addSurfaceOpenDrilldownBinding(plan, candidate, renderableDimensions);
        } else if (includeNominalDetails) {
            addList(
                    widgets,
                    candidate,
                    widgetKey(candidate, "list"),
                    "insight-list",
                    renderableDimensions,
                    governedRecordOpen(request));
            addTable(widgets, candidate, widgetKey(candidate, "table"), "detail");
        }
        if (!surfaceOpenModal || !chartDashboard) {
            addDashboardBindings(
                    plan,
                    candidate,
                    renderableDimensions,
                    visualizationDecision,
                    forceDashboardFilters,
                    includeKpis,
                    includeNominalDetails);
        }
        addDashboardCanvas(
                plan,
                candidate,
                renderableDimensions,
                visualizationDecision,
                surfaceOpenModal,
                forceDashboardFilters,
                includeKpis,
                includeNominalDetails);
        addDashboardGrouping(
                plan,
                candidate,
                renderableDimensions,
                visualizationDecision,
                surfaceOpenModal,
                forceDashboardFilters,
                includeKpis,
                includeNominalDetails);
        addDashboardDeviceLayouts(
                plan,
                candidate,
                renderableDimensions,
                visualizationDecision,
                surfaceOpenModal,
                forceDashboardFilters,
                includeKpis,
                includeNominalDetails);
        return plan;
    }

    boolean reflowPrunedDashboard(
            AgenticAuthoringPlanRequest request,
            ObjectNode plan) {
        if (request == null
                || plan == null
                || !"generic-ui-composition-plan-provider".equals(plan.path("plannerId").asText(""))
                || !Set.of("resource-dashboard", "chart-surface-drilldown")
                        .contains(plan.path("layoutPreset").asText(""))) {
            return false;
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        AgenticAuthoringCandidate candidate = selectedCandidate(intent);
        AgenticAuthoringSemanticDecision semanticDecision = intent == null ? null : intent.semanticDecision();
        AgenticAuthoringVisualizationDecision visualizationDecision =
                semanticDecision != null && semanticDecision.visualizationDecision() != null
                        ? semanticDecision.visualizationDecision()
                        : intent == null ? null : intent.visualizationDecision();
        if (candidate == null) {
            return false;
        }

        Set<String> presentChartKeys = new LinkedHashSet<>();
        JsonNode widgets = plan.path("widgets");
        if (widgets.isArray()) {
            for (JsonNode widget : widgets) {
                if ("praxis-chart".equals(widget.path("componentId").asText(""))) {
                    String key = widget.path("key").asText("");
                    if (!key.isBlank()) {
                        presentChartKeys.add(key);
                    }
                }
            }
        }
        List<DashboardDimension> plannedDimensions = dashboardDimensions(
                visualizationDecision,
                candidate,
                request).stream()
                .filter(this::isResolvedDimension)
                .toList();
        List<DashboardDimension> survivingDimensions = plannedDimensions.stream()
                .filter(dimension -> presentChartKeys.contains(
                        widgetKey(candidate, "chart-" + dimension.field())))
                .toList();
        if (survivingDimensions.size() == plannedDimensions.size()
                || survivingDimensions.size() != presentChartKeys.size()) {
            return false;
        }

        boolean surfaceOpenModal = isSurfaceOpenModalDrilldown(request);
        boolean chartDashboard = isChartDashboardRequest(request, visualizationDecision);
        boolean forceDashboardFilters = chartDashboard && !survivingDimensions.isEmpty();
        boolean includeNominalDetails = includeNominalDetails(request, visualizationDecision);
        boolean includeKpis = !surfaceOpenModal
                && includeNominalDetails
                && includeKpis(visualizationDecision);
        addDashboardPresetMetadata(
                plan,
                candidate,
                survivingDimensions,
                visualizationDecision,
                surfaceOpenModal,
                forceDashboardFilters,
                includeKpis,
                includeNominalDetails);
        addDashboardCanvas(
                plan,
                candidate,
                survivingDimensions,
                visualizationDecision,
                surfaceOpenModal,
                forceDashboardFilters,
                includeKpis,
                includeNominalDetails);
        addDashboardGrouping(
                plan,
                candidate,
                survivingDimensions,
                visualizationDecision,
                surfaceOpenModal,
                forceDashboardFilters,
                includeKpis,
                includeNominalDetails);
        addDashboardDeviceLayouts(
                plan,
                candidate,
                survivingDimensions,
                visualizationDecision,
                surfaceOpenModal,
                forceDashboardFilters,
                includeKpis,
                includeNominalDetails);
        return true;
    }

    private void addDashboardPresetMetadata(
            ObjectNode plan,
            AgenticAuthoringCandidate candidate,
            List<DashboardDimension> dimensions,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            boolean surfaceOpenModal,
            boolean forceIncludeFilters,
            boolean includeKpis,
            boolean includeNominalDetails) {
        if (surfaceOpenModal) {
            plan.put("themePreset", "workspace-balanced");
            return;
        }
        plan.put("themePreset", "analytics-calm");
        ObjectNode options = plan.putObject("layoutPresetOptions");
        options.put("presetFamily", "analytics-overview");
        options.put("sourceResource", businessResourcePath(candidate.resourcePath()));
        options.put("density", "comfortable");
        options.put("responsiveStrategy", "canvas-device-layouts");
        options.put("detailStrategy", includeNominalDetails ? "rich-list-table-surface" : "aggregate-only");

        ObjectNode slotAssignments = plan.putObject("slotAssignments");
        if (includeSummary(visualizationDecision)) {
            slotAssignments.put(widgetKey(candidate, "summary"), "hero");
        }
        if (includeKpis) {
            slotAssignments.put(widgetKey(candidate, "kpis"), "kpis");
        }
        if (forceIncludeFilters || includeFilters(visualizationDecision)) {
            slotAssignments.put(widgetKey(candidate, "filter"), "filters");
        }
        for (int i = 0; i < dimensions.size(); i++) {
            DashboardDimension dimension = dimensions.get(i);
            slotAssignments.put(
                    widgetKey(candidate, "chart-" + dimension.field()),
                    i == 0 ? "primary-chart" : "secondary-chart-" + i);
        }
        if (includeNominalDetails) {
            slotAssignments.put(widgetKey(candidate, "list"), "insight-list");
            slotAssignments.put(widgetKey(candidate, "table"), "detail-table");
        }
    }

    boolean isSurfaceOpenModalDrilldown(AgenticAuthoringPlanRequest request) {
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

    private boolean isChartDashboardRequest(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        if (visualizationDecision == null) {
            return true;
        }
        if (excludesComponent(visualizationDecision, "praxis-chart")) {
            return false;
        }
        return "praxis-chart".equals(safe(visualizationDecision.primaryComponent()))
                || hasLayoutKind(visualizationDecision, "dashboard", "analytical-dashboard", "analytics-dashboard")
                || hasVisualIntent(visualizationDecision, "chart", "charts", "grafico", "graficos", "painel", "dashboard")
                || containsAny(request == null ? "" : request.userPrompt(),
                "chart", "charts", "grafico", "graficos", "painel", "dashboard");
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

    private ObjectNode pagePlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        return workbenchPlanSupport.pagePlan(request, candidate, visualizationDecision);
    }

    private ObjectNode relatedResourcePagePlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        return workbenchPlanSupport.relatedResourcePagePlan(request, candidate, visualizationDecision);
    }

    private ObjectNode tabsPlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        return workbenchPlanSupport.tabsPlan(request, candidate, visualizationDecision);
    }

    private ObjectNode expansionPlan(AgenticAuthoringCandidate candidate) {
        return workbenchPlanSupport.expansionPlan(candidate);
    }

    ObjectNode basePlan(String layoutPreset) {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("version", "1.0");
        plan.put("layoutPreset", layoutPreset);
        plan.put("plannerId", "generic-ui-composition-plan-provider");
        return plan;
    }

    void addTable(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key, String role) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-table");
        widget.put("role", role);
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", businessResourcePath(candidate.resourcePath()));
        inputs.put("tableId", key);
        ObjectNode config = inputs.putObject("config");
        config.put("title", resourceTitle(candidate));
        config.putArray("columns");
    }

    void addList(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            String role,
            List<DashboardDimension> dimensions) {
        addList(widgets, candidate, key, role, dimensions, MissingNode.getInstance());
    }

    private void addList(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            String role,
            List<DashboardDimension> dimensions,
            JsonNode recordOpen) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-list");
        widget.put("role", role);
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("listId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("configPersistenceStrategy", "input-first");
        inputs.put("enableCustomization", true);
        inputs.putObject("queryContext").putObject("filters");
        ObjectNode config = inputs.putObject("config");
        String resourcePath = businessResourcePath(candidate.resourcePath());
        config.put("title", "Destaques de " + resourceTitle(candidate));
        ObjectNode dataSource = config.putObject("dataSource");
        dataSource.put("resourcePath", resourcePath);
        dataSource.putObject("query");
        ObjectNode layout = config.putObject("layout");
        layout.put("variant", "cards");
        layout.put("density", "comfortable");
        layout.put("itemSpacing", "default");
        layout.put("lines", 3);
        layout.put("pageSize", 6);
        ObjectNode templating = config.putObject("templating");
        DashboardDimension chipDimension = preferredListChipDimension(dimensions);
        String chipField = chipDimension == null ? "" : canonicalFieldName(chipDimension.field());
        templating.putObject("leading")
                .put("type", "icon")
                .put("expr", "subject");
        templating.putObject("primary")
                .put("type", "text")
                .put("expr", recordTitleExpression());
        putRecordSecondaryTemplate(templating, dimensions, chipField);
        templating.putObject("meta")
                .put("type", "text")
                .put("expr", recordMetaExpression());
        if (chipDimension != null && !chipField.isBlank()) {
            templating.putObject("trailing")
                    .put("type", "chip")
                    .put("expr", recordChipExpression(chipField));
            templating.put("statusPosition", "top-right");
        }
        ObjectNode emptyState = templating.putObject("emptyState");
        emptyState.put("type", "text");
        emptyState.put("text", "Nenhum registro encontrado para os filtros atuais.");
        ObjectNode selection = config.putObject("selection");
        selection.put("mode", "single");
        ArrayNode itemActions = config.putArray("actions");
        ObjectNode detailsAction = itemActions.addObject();
        detailsAction.put("id", "open-details");
        detailsAction.put("label", "Ver detalhes");
        detailsAction.put("icon", "open_in_new");
        detailsAction.put("placement", "trailing");
        configureOpenDetailsAction(detailsAction, candidate, key, recordOpen);
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
        widget.putArray("bindingOrder")
                .add("chartDocument")
                .add("queryContext")
                .add("enableCustomization");
        addChartOutputs(widget);
        ObjectNode inputs = widget.putObject("inputs");
        inputs.putObject("queryContext").putObject("filters");
        inputs.put("enableCustomization", true);
        populateChartDocument(inputs.putObject("chartDocument"), candidate, key, dimension);
    }

    void addChartOutputs(ObjectNode widget) {
        ObjectNode outputs = widget.putObject("outputs");
        outputs.put("pointClick", "emit");
        outputs.put("selectionChange", "emit");
        outputs.put("crossFilter", "emit");
    }

    void populateChartDocument(
            ObjectNode document,
            AgenticAuthoringCandidate candidate,
            String key,
            DashboardDimension dimension) {
        if (isGovernedComparisonDimension(dimension)) {
            populateComparisonChartDocument(document, candidate, key, dimension);
            return;
        }
        document.put("version", "0.1.0");
        document.put("chartId", key);
        document.put("kind", dimension.chartType());
        putCanonicalChartOrientation(document, dimension.orientation());
        String statsOperation = statsOperation(candidate.resourcePath(), dimension);
        boolean timeseries = "timeseries".equals(statsOperation);
        boolean recordCount = dimension.metricField().isBlank()
                || "count".equals(normalize(dimension.metricAggregation()));
        String displayedMetric = recordCount
                ? "Registros"
                : valueOrDefault(dimension.metricLabel(), titleFromResourcePath(dimension.metricField()));
        document.put("title", displayedMetric
                + (timeseries ? " ao longo de " : " por ")
                + dimension.label()
                + " - "
                + resourceTitle(candidate));
        document.put("subtitle", recordCount
                ? (timeseries ? "Evolucao de registros" : "Contagem de registros por " + dimension.label())
                : displayedMetric + " por " + dimension.label());
        document.putObject("sizing").put("mode", "fill-container").put("minHeight", 260);
        document.putArray("dimensions").addObject()
                .put("field", dimension.field())
                .put("label", dimension.label())
                .put("role", timeseries ? "time" : "category");
        String metricField = metricOutputField(dimension);
        String metricAggregation = canonicalAnalyticsAggregation(dimension.metricAggregation());
        ObjectNode metricNode = document.putArray("metrics").addObject()
                .put("field", metricField)
                .put("label", displayedMetric)
                .put("aggregation", metricAggregation);
        String metricFormat = inferChartMetricFormat(metricField, displayedMetric, metricAggregation);
        if (!metricFormat.isBlank()) {
            metricNode.put("format", metricFormat);
        }
        ObjectNode source = document.putObject("source");
        source.put("kind", "praxis.stats");
        source.put("resource", businessResourcePath(candidate.resourcePath()));
        source.put("operation", statsOperation);
        ObjectNode sourceOptions = source.putObject("options");
        if (timeseries) {
            sourceOptions.put("granularity", "month");
            sourceOptions.put("fillGaps", false);
        } else {
            sourceOptions.put("orderBy", isTemporalDimension(dimension) ? "key-asc" : "value-desc");
            sourceOptions.put("limit", 12);
        }
        document.put("legend", false);
        document.put("labels", !timeseries);
        document.put("tooltip", true);
        ObjectNode events = document.putObject("events");
        events.putObject("pointClick").put("action", "emit");
        events.putObject("selectionChange").put("action", "emit");
        ObjectNode crossFilter = events.putObject("crossFilter");
        crossFilter.put("action", "emit");
        crossFilter.putObject("mapping").put(dimension.field(), canonicalFieldName(dimension.field()));
    }

    private void populateComparisonChartDocument(
            ObjectNode document,
            AgenticAuthoringCandidate candidate,
            String key,
            DashboardDimension dimension) {
        JsonNode projection = dimension.analyticsProjection();
        JsonNode source = projection.path("source");
        JsonNode bindings = projection.path("bindings");
        JsonNode period = bindings.path("comparisonPeriod");
        List<JsonNode> metrics = analyticsMetrics(projection);
        String resourcePath = source.path("resource").asText(businessResourcePath(candidate.resourcePath()));

        document.put("version", "0.1.0");
        document.put("chartId", key);
        document.put("kind", dimension.chartType());
        putCanonicalChartOrientation(document, dimension.orientation());
        document.put("title", dimension.title() + " - " + titleFromResourcePath(resourcePath));
        document.put("subtitle", "Periodo atual e anterior por " + dimension.label());
        document.putObject("sizing").put("mode", "fill-container").put("minHeight", 260);
        document.putArray("dimensions").addObject()
                .put("field", dimension.field())
                .put("label", dimension.label())
                .put("role", "category");
        ArrayNode documentMetrics = document.putArray("metrics");
        for (JsonNode metric : metrics) {
            String metricField = metric.path("field").asText("");
            String aggregation = canonicalAnalyticsAggregation(metric.path("aggregation").asText("count"));
            String metricLabel = valueOrDefault(metric.path("label").asText(""), titleFromResourcePath(metricField));
            documentMetrics.addObject()
                    .put("field", metricField)
                    .put("label", metricLabel)
                    .put("aggregation", aggregation);
        }
        ObjectNode sourceDocument = document.putObject("source");
        sourceDocument.put("kind", "praxis.stats");
        sourceDocument.put("resource", resourcePath);
        sourceDocument.put("operation", "comparison");
        ObjectNode sourceOptions = sourceDocument.putObject("options");
        sourceOptions.putObject("comparisonPeriod")
                .put("field", period.path("field").asText(""))
                .put("preset", period.path("preset").asText(""))
                .put("timezone", period.path("timezone").asText(""))
                .put("mode", period.path("mode").asText(""));
        copyAnalyticsSortAndLimitToChartDocument(projection, document, sourceOptions);
        document.put("legend", true);
        document.put("labels", true);
        document.put("tooltip", true);

        JsonNode projectionInteractions = projection.path("interactions");
        boolean hasRecordOpen = projectionInteractions.path("recordOpen").isObject();
        boolean drillDown = !hasRecordOpen && projectionInteractions.path("drillDown").asBoolean(false);
        boolean pointSelection = projectionInteractions.path("pointSelection").asBoolean(false);
        boolean crossFilterEnabled = projectionInteractions.path("crossFilter").asBoolean(false);
        ObjectNode events = document.putObject("events");
        if (drillDown) {
            events.putObject("drillDown").put("action", "emit");
        }
        if (drillDown || pointSelection) {
            events.putObject("pointClick").put("action", "emit");
        }
        if (pointSelection) {
            events.putObject("selectionChange").put("action", "emit");
        }
        if (crossFilterEnabled) {
            ObjectNode crossFilter = events.putObject("crossFilter");
            crossFilter.put("action", "emit");
            crossFilter.putObject("mapping").put("key", dimension.filterField());
        }
    }

    private void copyAnalyticsSortAndLimitToChartDocument(
            JsonNode projection,
            ObjectNode document,
            ObjectNode sourceOptions) {
        JsonNode defaults = projection.path("defaults");
        if (defaults.path("limit").canConvertToInt() && defaults.path("limit").asInt() > 0) {
            int limit = defaults.path("limit").asInt();
            document.put("limit", limit);
            sourceOptions.put("limit", limit);
        }
        JsonNode sort = defaults.path("sort");
        if (!sort.isArray() || sort.isEmpty()) {
            return;
        }
        ArrayNode documentSort = document.putArray("sort");
        JsonNode firstSort = sort.get(0);
        String field = firstSort.path("field").asText("");
        String direction = firstSort.path("direction").asText("");
        if (!field.isBlank() && Set.of("asc", "desc").contains(direction)) {
            documentSort.addObject().put("field", field).put("direction", direction);
            boolean dimensionSort = normalize(field).equals(normalize(
                    projection.path("bindings").path("primaryDimension").path("field").asText("")));
            sourceOptions.put("orderBy", dimensionSort
                    ? ("asc".equals(direction) ? "key-asc" : "key-desc")
                    : ("asc".equals(direction) ? "value-asc" : "value-desc"));
        } else {
            document.remove("sort");
        }
    }

    private List<JsonNode> analyticsMetrics(JsonNode projection) {
        List<JsonNode> metrics = new ArrayList<>();
        for (String binding : List.of("primaryMetrics", "secondaryMetrics")) {
            JsonNode candidates = projection.path("bindings").path(binding);
            if (candidates.isArray()) {
                candidates.forEach(metrics::add);
            }
        }
        return List.copyOf(metrics);
    }

    private String canonicalAnalyticsAggregation(String aggregation) {
        return valueOrDefault(aggregation, "count")
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    private void putCanonicalChartOrientation(ObjectNode document, String orientation) {
        String canonicalOrientation = normalize(orientation);
        if (Set.of("vertical", "horizontal").contains(canonicalOrientation)) {
            document.put("orientation", canonicalOrientation);
        }
    }

    private boolean isGovernedComparisonDimension(DashboardDimension dimension) {
        return dimension != null
                && dimension.analyticsProjection() != null
                && dimension.analyticsProjection().isObject()
                && "comparison".equals(dimension.analyticsProjection().path("source").path("operation").asText(""));
    }

    private boolean allowsChartInteraction(DashboardDimension dimension, String interaction) {
        if (!isGovernedComparisonDimension(dimension)) {
            return true;
        }
        return dimension.analyticsProjection()
                .path("interactions")
                .path(interaction)
                .asBoolean(false);
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
            String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-rich-content");
        widget.put("role", "kpi-band");
        widget.putArray("bindingOrder").add("document").add("context");
        ObjectNode inputs = widget.putObject("inputs");
        ObjectNode document = richContentDocument(inputs);
        ObjectNode statGroup = document.putArray("nodes").addObject();
        statGroup.put("type", "statGroup");
        statGroup.put("id", key + "-stats");
        statGroup.put("title", "Leitura executiva");
        statGroup.put("subtitle", resourceTitle(candidate));
        statGroup.put("layout", "grid");
        ArrayNode items = statGroup.putArray("items");
        ObjectNode total = items.addObject();
        total.put("id", key + "-total");
        total.put("label", "Total filtrado");
        total.put("valueExpr", "${table.totalItems}");
        total.put("format", "integer");
        total.put("captionExpr", "${table.totalCaption}");
        total.put("icon", "monitoring");
        total.put("tone", "info");
        ObjectNode loaded = items.addObject();
        loaded.put("id", key + "-loaded");
        loaded.put("label", "Itens na página");
        loaded.put("valueExpr", "${table.loadedItemsCount}");
        loaded.put("format", "integer");
        loaded.put("captionExpr", "${table.loadedItemsCaption}");
        loaded.put("icon", "view_list");
        loaded.put("tone", "neutral");
        ObjectNode status = items.addObject();
        status.put("id", key + "-status");
        status.put("label", "Status da consulta");
        status.put("valueExpr", "${table.status}");
        status.put("captionExpr", "${table.statusCaption}");
        status.put("icon", "sync");
        status.put("tone", "neutral");

        ObjectNode context = inputs.putObject("context");
        ObjectNode table = context.putObject("table");
        table.put("totalItems", 0);
        table.put("loadedItemsCount", 0);
        table.put("status", "Carregando");
        table.put("totalCaption", "Total retornado pela consulta filtrada");
        table.put("loadedItemsCaption", "Itens carregados nesta página");
        table.put("statusCaption", "Atualizado pelo runtime da tabela");
    }

    private void addFilter(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            List<DashboardDimension> dimensions,
            AgenticAuthoringPlanRequest request) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-filter");
        widget.put("role", "filter");
        ObjectNode outputs = widget.putObject("outputs");
        outputs.put("change", "emit");
        outputs.put("requestSearch", "emit");
        outputs.put("clear", "emit");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", businessResourcePath(candidate.resourcePath()));
        inputs.put("filterId", key);
        inputs.put("showFilterSettings", true);
        ArrayNode selectedFields = inputs.putArray("selectedFieldIds");
        Set<String> selectedFilterFields = new LinkedHashSet<>();
        List<FieldCandidate> fieldCandidates = dashboardFieldCandidates(
                candidate,
                request == null ? null : request.contextHints());
        String entityScopeFilterField = preferredEntityScopeFilterField(fieldCandidates, dimensions);
        if (!entityScopeFilterField.isBlank() && selectedFilterFields.add(entityScopeFilterField)) {
            selectedFields.add(entityScopeFilterField);
        }
        for (DashboardDimension dimension : dimensions.stream().filter(this::isResolvedDimension).toList()) {
            String filterField = valueOrDefault(dimension.filterField(), dimension.field());
            if (selectedFilterFields.add(filterField)) {
                selectedFields.add(filterField);
            }
        }
    }

    void addSemanticAxisProvenance(
            ObjectNode plan,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            List<DashboardDimension> dimensions) {
        addSemanticAxisProvenance(plan, visualizationDecision, dimensions, true);
    }

    private void addSemanticAxisProvenance(
            ObjectNode plan,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            List<DashboardDimension> dimensions,
            boolean includeNominalDetails) {
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
        ObjectNode blueprint = diagnostics.putObject("dashboardBlueprint");
        blueprint.put("schemaVersion", "praxis-dashboard-blueprint.v1");
        blueprint.put("planner", "generic-ui-composition-plan-provider");
        blueprint.put("domainSpecific", false);
        blueprint.put("fieldSelectionPolicy", "semantic-field-candidates-from-host-context");
        blueprint.put("requiresResolvedCategoricalAxes", true);
        blueprint.put("compositionStrategy", includeNominalDetails
                ? "executive-summary-kpis-filters-charts-rich-list-table-surface"
                : "executive-summary-kpis-filters-charts-aggregate-only");
        blueprint.put("detailSurface", includeNominalDetails
                ? "chart-point-opens-filtered-rich-list-modal"
                : "unavailable-for-current-principal");
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
            AgenticAuthoringVisualizationDecision visualizationDecision,
            boolean forceIncludeFilters,
            boolean includeKpis,
            boolean includeNominalDetails) {
        boolean includeFilters = forceIncludeFilters || includeFilters(visualizationDecision);
        if (!includeFilters && !includeNominalDetails) {
            return;
        }
        String filterKey = widgetKey(candidate, "filter");
        String tableKey = widgetKey(candidate, "table");
        String listKey = widgetKey(candidate, "list");
        ArrayNode bindings = plan.putArray("bindings");
        if (includeFilters && includeNominalDetails) {
            addFilterQueryContextBindings(bindings, filterKey, tableKey);
            addFilterQueryContextBindings(bindings, filterKey, listKey);
        }
        if (includeKpis && includeNominalDetails) {
            addTableKpiContextBindings(bindings, tableKey, widgetKey(candidate, "kpis"));
        }
        for (DashboardDimension dimension : dimensions) {
            String chartKey = widgetKey(candidate, "chart-" + dimension.field());
            if (includeFilters) {
                addFilterQueryContextBindings(bindings, filterKey, chartKey);
            }

            if (includeNominalDetails) {
                if (allowsChartInteraction(dimension, "drillDown")) {
                    ObjectNode modalBinding = bindings.addObject();
                    modalBinding.put("id", chartKey + ".pointClick->surface.open");
                    modalBinding.put("intent", "command-dispatch");
                    addComponentPortEndpoint(modalBinding.putObject("from"), chartKey, "pointClick", "output");
                    ObjectNode to = modalBinding.putObject("to");
                    to.put("kind", "global-action");
                    to.put("actionId", "surface.open");
                    to.set("payload", surfaceOpenListPayload(candidate, dimension, dimensions));
                    ObjectNode policy = modalBinding.putObject("policy");
                    policy.put("distinct", true);
                    policy.put("distinctBy", chartPointRawValuePath(dimension));
                    policy.put("debounceMs", 250);
                }

                if (allowsChartInteraction(dimension, "drillDown")
                        || allowsChartInteraction(dimension, "pointSelection")) {
                    addChartPointQueryContextBinding(bindings, chartKey, tableKey, dimension);
                    addChartPointQueryContextBinding(bindings, chartKey, listKey, dimension);
                }

                if (allowsChartInteraction(dimension, "crossFilter")) {
                    ObjectNode crossFilterBinding = bindings.addObject();
                    crossFilterBinding.put("id", chartKey + ".crossFilter->" + tableKey + ".queryContext");
                    crossFilterBinding.put("intent", "data-projection");
                    addComponentPortEndpoint(crossFilterBinding.putObject("from"), chartKey, "crossFilter", "output");
                    addComponentPortEndpoint(crossFilterBinding.putObject("to"), tableKey, "queryContext", "input");
                    addChartQueryContextPolicy(crossFilterBinding, dimension);
                    ObjectNode crossFilterTransform = crossFilterBinding.putObject("transform");
                    crossFilterTransform.put("kind", "template");
                    crossFilterTransform.put("id", chartKey + "-cross-filter-query-context");
                    crossFilterTransform.putObject("output").put("semanticKind", "query-context").put("stableShape", true);
                    ObjectNode template = crossFilterTransform.putObject("template");
                    putCrossFilterQueryContext(template, dimension);

                    ObjectNode listDrilldownBinding = bindings.addObject();
                    listDrilldownBinding.put("id", chartKey + ".crossFilter->" + listKey + ".queryContext");
                    listDrilldownBinding.put("intent", "data-projection");
                    addComponentPortEndpoint(listDrilldownBinding.putObject("from"), chartKey, "crossFilter", "output");
                    addComponentPortEndpoint(listDrilldownBinding.putObject("to"), listKey, "queryContext", "input");
                    addChartQueryContextPolicy(listDrilldownBinding, dimension);
                    ObjectNode listTransform = listDrilldownBinding.putObject("transform");
                    listTransform.put("kind", "template");
                    listTransform.put("id", chartKey + "-cross-filter-list-query-context");
                    listTransform.putObject("output").put("semanticKind", "query-context").put("stableShape", true);
                    ObjectNode listTemplate = listTransform.putObject("template");
                    putCrossFilterQueryContext(listTemplate, dimension);
                }
            }
        }
    }

    private void addChartPointQueryContextBinding(
            ArrayNode bindings,
            String chartKey,
            String targetKey,
            DashboardDimension dimension) {
        ObjectNode binding = bindings.addObject();
        binding.put("id", chartKey + ".pointClick->" + targetKey + ".queryContext");
        binding.put("intent", "data-projection");
        addComponentPortEndpoint(binding.putObject("from"), chartKey, "pointClick", "output");
        addComponentPortEndpoint(binding.putObject("to"), targetKey, "queryContext", "input");
        ObjectNode policy = binding.putObject("policy");
        policy.put("distinct", true);
        policy.put("distinctBy", chartPointRawValuePath(dimension));
        policy.put("debounceMs", 250);
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "template");
        transform.put("id", chartKey + "-point-query-context");
        transform.putObject("output").put("semanticKind", "query-context").put("stableShape", true);
        ObjectNode template = transform.putObject("template");
        ObjectNode filters = template.putObject("filters");
        filters.put(dimension.filterField(), "${" + chartPointRawValuePath(dimension) + "}");
    }

    private void addChartQueryContextPolicy(ObjectNode binding, DashboardDimension dimension) {
        ObjectNode policy = binding.putObject("policy");
        policy.put("distinct", true);
        policy.put("distinctBy", "payload.filters." + dimension.filterField());
        policy.put("debounceMs", 250);
    }

    private void putCrossFilterQueryContext(ObjectNode template, DashboardDimension dimension) {
        if (!isGovernedComparisonDimension(dimension)) {
            template.put("filters", "${payload.filters}");
            return;
        }
        ObjectNode filters = template.putObject("filters");
        filters.put(dimension.filterField(), "${payload.filters." + dimension.filterField() + "}");
    }

    private void addFilterQueryContextBindings(ArrayNode bindings, String filterKey, String targetKey) {
        for (String eventPort : List.of("change", "requestSearch")) {
            ObjectNode binding = bindings.addObject();
            binding.put("id", filterKey + "." + eventPort + "->" + targetKey + ".queryContext");
            binding.put("intent", "data-projection");
            addComponentPortEndpoint(binding.putObject("from"), filterKey, eventPort, "output");
            addComponentPortEndpoint(binding.putObject("to"), targetKey, "queryContext", "input");
            ObjectNode transform = binding.putObject("transform");
            transform.put("kind", "template");
            transform.put("id", filterKey + "-" + eventPort + "-" + targetKey + "-query-context");
            transform.putObject("output").put("semanticKind", "query-context").put("stableShape", true);
            ObjectNode template = transform.putObject("template");
            template.put("filters", "${payload}");
        }
    }

    private void addTableKpiContextBindings(ArrayNode bindings, String tableKey, String kpiKey) {
        String statePath = "dashboardKpis." + tableKey;
        String writeId = tableKey + ".loadingStateChange->" + statePath;
        ObjectNode writeBinding = bindings.addObject();
        writeBinding.put("id", writeId);
        writeBinding.put("intent", "state-write");
        addComponentPortEndpoint(writeBinding.putObject("from"), tableKey, "loadingStateChange", "output");
        ObjectNode stateTarget = writeBinding.putObject("to");
        stateTarget.put("kind", "state");
        stateTarget.put("path", statePath);
        stateTarget.put("layer", "transient");
        ObjectNode transform = writeBinding.putObject("transform");
        transform.put("kind", "template");
        transform.put("id", tableKey + "-loading-state-kpi-context");
        ObjectNode template = transform.putObject("template");
        ObjectNode table = template.putObject("table");
        table.put("totalItems", "${payload.context.totalItems}");
        table.put("loadedItemsCount", "${payload.context.loadedItemsCount}");
        table.put("status", "${payload.status}");
        table.put("totalCaption", "Total retornado pela consulta filtrada");
        table.put("loadedItemsCaption", "${payload.context.loadedItemsCount} itens carregados nesta página");
        table.put("statusCaption", "${payload.message}");
        table.put("resourcePath", "${payload.context.resourcePath}");
        writeBinding.putObject("policy").put("distinct", true);

        ObjectNode readBinding = bindings.addObject();
        readBinding.put("id", statePath + "->" + kpiKey + ".context");
        readBinding.put("intent", "state-read");
        ObjectNode stateSource = readBinding.putObject("from");
        stateSource.put("kind", "state");
        stateSource.put("path", statePath);
        stateSource.put("layer", "transient");
        addComponentPortEndpoint(readBinding.putObject("to"), kpiKey, "context", "input");
        readBinding.putObject("policy").put("missingValuePolicy", "skip");
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
        if (!allowsChartInteraction(dimension, "drillDown")) {
            return;
        }
        String chartKey = widgetKey(candidate, "chart-" + dimension.field());
        addSurfaceOpenDrilldownBinding(plan, chartKey, candidate, dimension);
    }

    void addSurfaceOpenDrilldownBinding(
            ObjectNode plan,
            String chartKey,
            AgenticAuthoringCandidate candidate,
            DashboardDimension dimension) {
        if (!allowsChartInteraction(dimension, "drillDown")) {
            return;
        }
        ArrayNode bindings = plan.withArray("bindings");
        String bindingId = chartKey + ".pointClick->surface.open";
        ObjectNode binding = null;
        for (JsonNode candidateBinding : bindings) {
            if (candidateBinding instanceof ObjectNode object
                    && bindingId.equals(object.path("id").asText(""))) {
                binding = object;
                binding.removeAll();
                break;
            }
        }
        if (binding == null) {
            binding = bindings.addObject();
        }
        binding.put("id", bindingId);
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
        policy.put("distinctBy", chartPointRawValuePath(dimension));
        policy.put("debounceMs", 250);
    }

    void addSurfaceOpenDrilldownComposition(
            ObjectNode plan,
            String chartKey,
            AgenticAuthoringCandidate candidate,
            DashboardDimension dimension) {
        if (!allowsChartInteraction(dimension, "drillDown")) {
            return;
        }
        JsonNode existingComposition = plan.get("composition");
        ObjectNode composition = existingComposition instanceof ObjectNode existing
                ? existing
                : plan.putObject("composition");
        JsonNode existingLinks = composition.get("links");
        ArrayNode links = existingLinks instanceof ArrayNode existing
                ? existing
                : composition.putArray("links");
        String linkId = chartKey + ".pointClick->surface.open";
        ObjectNode link = null;
        for (JsonNode candidateLink : links) {
            if (candidateLink instanceof ObjectNode object
                    && linkId.equals(object.path("id").asText(""))) {
                link = object;
                link.removeAll();
                break;
            }
        }
        if (link == null) {
            link = links.addObject();
        }
        link.put("id", linkId);
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
        policy.put("distinctBy", chartPointRawValuePath(dimension));
        policy.put("debounceMs", 250);
    }

    ObjectNode surfaceOpenTablePayload(
            AgenticAuthoringCandidate candidate,
            DashboardDimension dimension) {
        String resourcePath = businessResourcePath(candidate.resourcePath());
        String key = widgetKey(candidate, "modal-table-" + dimension.field());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("presentation", "modal");
        payload.put("title", "Detalhes por " + dimension.label());
        payload.put("icon", "table_view");
        payload.put("subtitle", "Registros filtrados pela categoria selecionada no gráfico.");
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
        config.put("title", "Registros de " + titleFromResourcePath(resourcePath));
        addSurfaceOpenTableColumns(config.putArray("columns"), dimension);
        ArrayNode bindings = payload.putArray("bindings");
        ObjectNode binding = bindings.addObject();
        binding.put("from", chartPointRawValuePath(dimension));
        binding.put("to", "widget.inputs.queryContext.filters." + dimension.filterField());
        return payload;
    }

    private ObjectNode surfaceOpenListPayload(
            AgenticAuthoringCandidate candidate,
            DashboardDimension dimension,
            List<DashboardDimension> dimensions) {
        String resourcePath = businessResourcePath(candidate.resourcePath());
        String key = widgetKey(candidate, "modal-list-" + dimension.field());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("presentation", "modal");
        payload.put("title", "Explorar " + dimension.label());
        payload.put("icon", "view_agenda");
        payload.put("subtitle", "Lista filtrada pela categoria selecionada no gráfico.");
        ObjectNode size = payload.putObject("size");
        size.put("width", "860px");
        size.put("maxWidth", "94vw");
        size.put("height", "680px");
        size.put("maxHeight", "86vh");
        ObjectNode widget = payload.putObject("widget");
        widget.put("id", "praxis-list");
        ArrayNode bindingOrder = widget.putArray("bindingOrder");
        bindingOrder.add("listId");
        bindingOrder.add("componentInstanceId");
        bindingOrder.add("config");
        bindingOrder.add("configPersistenceStrategy");
        bindingOrder.add("enableCustomization");
        bindingOrder.add("queryContext");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("listId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("configPersistenceStrategy", "input-first");
        inputs.put("enableCustomization", true);
        inputs.putObject("queryContext").putObject("filters");
        ObjectNode config = inputs.putObject("config");
        config.put("id", key);
        config.put("title", "Registros de " + titleFromResourcePath(resourcePath));
        ObjectNode dataSource = config.putObject("dataSource");
        dataSource.put("resourcePath", resourcePath);
        dataSource.putObject("query");
        ObjectNode layout = config.putObject("layout");
        layout.put("variant", "cards");
        layout.put("density", "comfortable");
        layout.put("lines", 3);
        layout.put("pageSize", 8);
        ObjectNode templating = config.putObject("templating");
        String surfaceChipField = canonicalFieldName(dimension.field());
        templating.putObject("leading")
                .put("type", "icon")
                .put("expr", "subject");
        templating.putObject("primary")
                .put("type", "text")
                .put("expr", recordTitleExpression());
        putRecordSecondaryTemplate(templating, dimensions, surfaceChipField);
        templating.putObject("meta")
                .put("type", "text")
                .put("expr", recordMetaExpression());
        templating.putObject("trailing")
                .put("type", "chip")
                .put("expr", recordChipExpression(surfaceChipField));
        templating.put("statusPosition", "top-right");
        ObjectNode emptyState = templating.putObject("emptyState");
        emptyState.put("type", "text");
        emptyState.put("text", "Nenhum registro encontrado para esta seleção.");
        ObjectNode selection = config.putObject("selection");
        selection.put("mode", "single");
        ArrayNode itemActions = config.putArray("actions");
        ObjectNode detailsAction = itemActions.addObject();
        detailsAction.put("id", "open-details");
        detailsAction.put("label", "Ver detalhes");
        detailsAction.put("icon", "open_in_new");
        detailsAction.put("placement", "trailing");
        configureOpenDetailsAction(detailsAction, candidate, key);
        ArrayNode bindings = payload.putArray("bindings");
        ObjectNode binding = bindings.addObject();
        binding.put("from", chartPointRawValuePath(dimension));
        binding.put("to", "widget.inputs.queryContext.filters." + dimension.filterField());
        ObjectNode queryBinding = bindings.addObject();
        queryBinding.put("from", chartPointRawValuePath(dimension));
        queryBinding.put("to", "widget.inputs.config.dataSource.query." + dimension.filterField());
        return payload;
    }

    private String chartPointRawValuePath(DashboardDimension dimension) {
        return isGovernedComparisonDimension(dimension) && !dimension.filterField().isBlank()
                ? "payload.data.key"
                : "payload.category";
    }

    private void configureOpenDetailsAction(
            ObjectNode action,
            AgenticAuthoringCandidate candidate,
            String sourceKey) {
        configureOpenDetailsAction(action, candidate, sourceKey, MissingNode.getInstance());
    }

    private void configureOpenDetailsAction(
            ObjectNode action,
            AgenticAuthoringCandidate candidate,
            String sourceKey,
            JsonNode recordOpen) {
        action.put("kind", "icon");
        action.put("showLoading", true);
        action.put("emitLocal", false);
        if (recordOpen != null && recordOpen.isObject()) {
            action.put("action", "surface.open");
            action.set("recordOpen", recordOpen.deepCopy());
            return;
        }
        ObjectNode globalAction = action.putObject("globalAction");
        globalAction.put("actionId", "surface.open");
        globalAction.set("payload", surfaceOpenFormPayload(candidate, sourceKey));
    }

    private ObjectNode surfaceOpenFormPayload(
            AgenticAuthoringCandidate candidate,
            String sourceKey) {
        String resourcePath = businessResourcePath(candidate.resourcePath());
        String key = valueOrDefault(sourceKey, widgetKey(candidate, "detail")) + "-detail-surface";
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("presentation", "modal");
        payload.put("title", "Detalhes do registro");
        payload.put("icon", "badge");
        payload.put("subtitle", "Formulário rico em modo leitura para o item selecionado.");
        ObjectNode size = payload.putObject("size");
        size.put("width", "760px");
        size.put("maxWidth", "94vw");
        size.put("height", "680px");
        size.put("maxHeight", "86vh");
        ObjectNode widget = payload.putObject("widget");
        widget.put("id", "praxis-dynamic-form");
        ArrayNode bindingOrder = widget.putArray("bindingOrder");
        bindingOrder.add("formId");
        bindingOrder.add("componentInstanceId");
        bindingOrder.add("resourcePath");
        bindingOrder.add("mode");
        bindingOrder.add("schemaSource");
        bindingOrder.add("resourceId");
        bindingOrder.add("config");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", resourcePath);
        inputs.put("formId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("mode", "view");
        inputs.put("schemaSource", "resource");
        inputs.put("resourceId", "${item.id}");
        ObjectNode config = inputs.putObject("config");
        config.put("title", "Detalhes de " + titleFromResourcePath(resourcePath));
        config.put("density", "comfortable");
        config.put("layout", "responsive");
        return payload;
    }

    private String recordTitleExpression() {
        return "${item.displayName ?? item.fullName ?? item.nomeCompleto ?? item.funcionarioNome ?? item.nome ?? item.name ?? item.title ?? item.label ?? item.motivo ?? item.descricao ?? item.description ?? item.id}";
    }

    private DashboardDimension preferredListChipDimension(List<DashboardDimension> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return null;
        }
        return dimensions.stream()
                .filter(dimension -> dimension != null
                        && !safe(dimension.field()).isBlank()
                        && !isTemporalDimension(dimension))
                .findFirst()
                .orElse(dimensions.get(0));
    }

    private String inferChartMetricFormat(String metricField, String metricLabel, String aggregation) {
        if ("count".equalsIgnoreCase(safe(aggregation))) {
            return "";
        }
        return isMonetaryFieldOrLabel(metricField, metricLabel) ? "BRL|symbol|2" : "";
    }

    private boolean isMonetaryFieldOrLabel(String field, String label) {
        String combined = searchableText(safe(field) + " " + safe(label));
        return containsAny(combined,
                "salario", "remuneracao", "valor", "preco", "price", "custo", "cost",
                "receita", "revenue", "despesa", "faturamento", "orcamento", "budget",
                "liquido", "bruto", "folha", "bonus", "provento", "desconto", "montante");
    }

    private void putRecordSecondaryTemplate(
            ObjectNode templating,
            List<DashboardDimension> dimensions,
            String chipField) {
        ObjectNode secondary = templating.putObject("secondary");
        secondary.put("type", "compose");
        ObjectNode compose = secondary.putObject("props").putObject("compose");
        compose.put("direction", "row");
        compose.put("wrap", true);
        compose.put("separator", " • ");
        ArrayNode items = compose.putArray("items");
        Set<String> excludedOrIncluded = new LinkedHashSet<>(List.of(
                "datainicio", "competencia", "datafim", "createdat", "updatedat", "datacriacao", "dataatualizacao"));
        if (!safe(chipField).isBlank()) {
            excludedOrIncluded.add(normalize(chipField));
        }
        List<DashboardDimension> safeDimensions = dimensions == null ? List.of() : dimensions;
        for (DashboardDimension dimension : safeDimensions) {
            if (dimension == null
                    || safe(dimension.metricField()).isBlank()
                    || "count".equals(normalize(dimension.metricAggregation()))) {
                continue;
            }
            String metricField = canonicalFieldName(dimension.metricField());
            if (!metricField.isBlank() && excludedOrIncluded.add(normalize(metricField))) {
                String expr = isMonetaryFieldOrLabel(metricField, dimension.metricLabel())
                        ? "${item." + metricField + "|currency:BRL:pt-BR}"
                        : "${item." + metricField + "}";
                items.addObject()
                        .put("type", "text")
                        .put("expr", expr);
                break;
            }
        }
        for (DashboardDimension dimension : safeDimensions) {
            if (!isResolvedDimension(dimension)
                    || isTemporalDimension(dimension)
                    || shouldRenderDimensionAsRecordChip(dimension.field())) {
                continue;
            }
            String dimField = canonicalFieldName(dimension.field());
            if (dimField.isBlank() || !excludedOrIncluded.add(normalize(dimField))) {
                continue;
            }
            items.addObject()
                    .put("type", "text")
                    .put("expr", "${item." + dimField + "}");
            if (items.size() >= 2) {
                break;
            }
        }
        List<String> fallbackCandidates = List.of(
                        "motivo", "description", "descricao", "category", "categoria",
                        "type", "tipo", "group", "grupo", "segment", "segmento")
                .stream()
                .filter(candidate -> !excludedOrIncluded.contains(normalize(candidate)))
                .map(candidate -> "item." + candidate)
                .toList();
        if (!fallbackCandidates.isEmpty()) {
            items.addObject()
                    .put("type", "text")
                    .put("expr", "${" + String.join(" ?? ", fallbackCandidates) + "}");
        }
    }

    private String recordMetaExpression() {
        return "${item.dataInicio ?? item.competencia ?? item.dataFim ?? item.createdAt ?? item.updatedAt ?? item.dataCriacao ?? item.dataAtualizacao ?? item.codigo ?? item.code ?? item.uuid}";
    }

    private String recordChipExpression(String field) {
        String canonical = canonicalFieldName(field);
        if (isBooleanLikeField(canonical)) {
            return "${item." + canonical + "}|bool:Ativo:Inativo";
        }
        if (isStatusLikeField(canonical)) {
            return "${item." + canonical + "}|map:true=Ativo,false=Inativo,ACTIVE=Ativo,INACTIVE=Inativo,active=Ativo,inactive=Inativo,enabled=Ativo,disabled=Inativo";
        }
        return "${item." + canonical + "}";
    }

    private boolean shouldRenderDimensionAsRecordChip(String field) {
        String canonical = canonicalFieldName(field);
        return isBooleanLikeField(canonical) || isStatusLikeField(canonical);
    }

    private boolean isBooleanLikeField(String field) {
        String normalized = normalizeForSearch(field);
        return normalized.equals("ativo")
                || normalized.equals("active")
                || normalized.equals("enabled")
                || normalized.equals("isactive")
                || normalized.equals("statusativo")
                || normalized.endsWith("ativo")
                || normalized.endsWith("active");
    }

    private boolean isStatusLikeField(String field) {
        String normalized = normalizeForSearch(field);
        return normalized.equals("status")
                || normalized.equals("state")
                || normalized.equals("situacao")
                || normalized.equals("enabled")
                || normalized.endsWith("status")
                || normalized.endsWith("state")
                || normalized.endsWith("situacao");
    }

    private String normalizeForSearch(String value) {
        return normalize(value).replaceAll("[^a-z0-9]+", "");
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
        addDashboardCanvas(
                plan,
                candidate,
                dimensions,
                visualizationDecision,
                false,
                false,
                includeKpis(visualizationDecision),
                includeDetailTable(visualizationDecision));
    }

    private void addDashboardCanvas(
            ObjectNode plan,
            AgenticAuthoringCandidate candidate,
            List<DashboardDimension> dimensions,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            boolean surfaceOpenModal,
            boolean forceIncludeFilters,
            boolean includeKpis,
            boolean includeNominalDetails) {
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
        if (includeKpis) {
            putCanvasItem(items, widgetKey(candidate, "kpis"), 1, nextRow, 12, 2);
            nextRow += 2;
        }
        if (forceIncludeFilters || includeFilters(visualizationDecision)) {
            putCanvasItem(items, widgetKey(candidate, "filter"), 1, nextRow, 12, 1);
            nextRow += 1;
        }

        int tableRow = nextRow;
        if (!dimensions.isEmpty()) {
            int chartCount = dimensions.size();
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
            tableRow = chartRow + Math.max(1, chartRows) * chartRowSpan;
        }
        if (!surfaceOpenModal && includeNominalDetails) {
            putCanvasItem(items, widgetKey(candidate, "list"), 1, tableRow, 5, 8);
            putCanvasItem(items, widgetKey(candidate, "table"), 6, tableRow, 7, 8);
        }
    }

    private void addDashboardGrouping(
            ObjectNode plan,
            AgenticAuthoringCandidate candidate,
            List<DashboardDimension> dimensions,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            boolean surfaceOpenModal,
            boolean forceIncludeFilters,
            boolean includeKpis,
            boolean includeNominalDetails) {
        ArrayNode grouping = plan.putArray("grouping");
        ArrayNode overviewKeys = objectMapper.createArrayNode();
        if (includeSummary(visualizationDecision)) {
            overviewKeys.add(widgetKey(candidate, "summary"));
        }
        if (includeKpis) {
            overviewKeys.add(widgetKey(candidate, "kpis"));
        }
        if (!overviewKeys.isEmpty()) {
            ObjectNode overview = grouping.addObject();
            overview.put("kind", "hero");
            overview.put("id", widgetKey(candidate, "overview-group"));
            overview.put("emphasis", "medium");
            overview.set("widgetKeys", overviewKeys);
        }

        if (forceIncludeFilters || includeFilters(visualizationDecision)) {
            ObjectNode filters = grouping.addObject();
            filters.put("kind", "section");
            filters.put("id", widgetKey(candidate, "filters-group"));
            filters.put("label", "Filtros");
            filters.put("layout", "stack");
            filters.putArray("widgetKeys").add(widgetKey(candidate, "filter"));
        }

        if (dimensions != null && !dimensions.isEmpty()) {
            ObjectNode analysis = grouping.addObject();
            analysis.put("kind", "section");
            analysis.put("id", widgetKey(candidate, "analysis-group"));
            analysis.put("label", "Análise");
            analysis.put("layout", dimensions.size() == 1 ? "stack" : "grid");
            ArrayNode chartKeys = analysis.putArray("widgetKeys");
            for (DashboardDimension dimension : dimensions) {
                chartKeys.add(widgetKey(candidate, "chart-" + dimension.field()));
            }
        }

        if (!surfaceOpenModal && includeNominalDetails) {
            ObjectNode details = grouping.addObject();
            details.put("kind", "section");
            details.put("id", widgetKey(candidate, "details-group"));
            details.put("label", "Detalhes");
            details.put("layout", "row");
            ArrayNode detailKeys = details.putArray("widgetKeys");
            detailKeys.add(widgetKey(candidate, "list"));
            detailKeys.add(widgetKey(candidate, "table"));
        }

        if (grouping.isEmpty()) {
            plan.remove("grouping");
        }
    }

    private void addDashboardDeviceLayouts(
            ObjectNode plan,
            AgenticAuthoringCandidate candidate,
            List<DashboardDimension> dimensions,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            boolean surfaceOpenModal,
            boolean forceIncludeFilters,
            boolean includeKpis,
            boolean includeNominalDetails) {
        ObjectNode deviceLayouts = plan.putObject("deviceLayouts");
        addDashboardMobileLayout(
                deviceLayouts.putObject("mobile"),
                candidate,
                dimensions,
                visualizationDecision,
                surfaceOpenModal,
                forceIncludeFilters,
                includeKpis,
                includeNominalDetails);
        addDashboardTabletLayout(
                deviceLayouts.putObject("tablet"),
                candidate,
                dimensions,
                visualizationDecision,
                surfaceOpenModal,
                forceIncludeFilters,
                includeKpis,
                includeNominalDetails);
    }

    private void addDashboardMobileLayout(
            ObjectNode variant,
            AgenticAuthoringCandidate candidate,
            List<DashboardDimension> dimensions,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            boolean surfaceOpenModal,
            boolean forceIncludeFilters,
            boolean includeKpis,
            boolean includeNominalDetails) {
        ObjectNode canvas = variant.putObject("canvas");
        canvas.put("columns", 1);
        canvas.put("rowUnit", "88px");
        canvas.put("gap", "12px");
        canvas.put("autoRows", "fixed");
        ObjectNode items = canvas.putObject("items");
        int nextRow = 1;
        if (includeSummary(visualizationDecision)) {
            putCanvasItem(items, widgetKey(candidate, "summary"), 1, nextRow, 1, 2);
            nextRow += 2;
        }
        if (includeKpis) {
            putCanvasItem(items, widgetKey(candidate, "kpis"), 1, nextRow, 1, 3);
            nextRow += 3;
        }
        if (forceIncludeFilters || includeFilters(visualizationDecision)) {
            putCanvasItem(items, widgetKey(candidate, "filter"), 1, nextRow, 1, 3);
            nextRow += 3;
        }
        for (DashboardDimension dimension : dimensions) {
            putCanvasItem(items, widgetKey(candidate, "chart-" + dimension.field()), 1, nextRow, 1, 4);
            nextRow += 4;
        }
        if (!surfaceOpenModal && includeNominalDetails) {
            putCanvasItem(items, widgetKey(candidate, "list"), 1, nextRow, 1, 6);
            nextRow += 6;
            putCanvasItem(items, widgetKey(candidate, "table"), 1, nextRow, 1, 8);
        }
    }

    private void addDashboardTabletLayout(
            ObjectNode variant,
            AgenticAuthoringCandidate candidate,
            List<DashboardDimension> dimensions,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            boolean surfaceOpenModal,
            boolean forceIncludeFilters,
            boolean includeKpis,
            boolean includeNominalDetails) {
        ObjectNode canvas = variant.putObject("canvas");
        canvas.put("columns", 6);
        canvas.put("rowUnit", "80px");
        canvas.put("gap", "14px");
        canvas.put("autoRows", "fixed");
        ObjectNode items = canvas.putObject("items");
        int nextRow = 1;
        if (includeSummary(visualizationDecision)) {
            putCanvasItem(items, widgetKey(candidate, "summary"), 1, nextRow, 6, 2);
            nextRow += 2;
        }
        if (includeKpis) {
            putCanvasItem(items, widgetKey(candidate, "kpis"), 1, nextRow, 6, 2);
            nextRow += 2;
        }
        if (forceIncludeFilters || includeFilters(visualizationDecision)) {
            putCanvasItem(items, widgetKey(candidate, "filter"), 1, nextRow, 6, 2);
            nextRow += 2;
        }
        int chartColSpan = dimensions.size() == 1 ? 6 : 3;
        int chartRowSpan = 4;
        for (int i = 0; i < dimensions.size(); i++) {
            DashboardDimension dimension = dimensions.get(i);
            int columnOffset = i % 2;
            int rowOffset = i / 2;
            putCanvasItem(
                    items,
                    widgetKey(candidate, "chart-" + dimension.field()),
                    1 + columnOffset * chartColSpan,
                    nextRow + rowOffset * chartRowSpan,
                    chartColSpan,
                    chartRowSpan);
        }
        if (!dimensions.isEmpty()) {
            nextRow += ((int) Math.ceil(dimensions.size() / 2.0d)) * chartRowSpan;
        }
        if (!surfaceOpenModal && includeNominalDetails) {
            putCanvasItem(items, widgetKey(candidate, "list"), 1, nextRow, 6, 7);
            nextRow += 7;
            putCanvasItem(items, widgetKey(candidate, "table"), 1, nextRow, 6, 7);
        }
    }

    void putCanvasItem(ObjectNode items, String key, int col, int row, int colSpan, int rowSpan) {
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

    private void addSummary(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            boolean includeNominalDetails) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-rich-content");
        widget.put("role", "supporting");
        ObjectNode document = richContentDocument(widget.putObject("inputs"));
        ObjectNode card = document.putArray("nodes").addObject();
        card.put("type", "card");
        card.put("title", resourceTitle(candidate));
        card.put("subtitle", "Visão executiva");
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
        body.put("text", includeNominalDetails
                ? "Visão inicial baseada em " + resourceTitle(candidate)
                        + ". Use os filtros para refinar indicadores, gráficos, lista e tabela. Selecione pontos do gráfico para abrir uma exploração contextual em modal e sincronizar os detalhes."
                : "Visão agregada baseada em " + resourceTitle(candidate)
                        + ". Use os filtros para refinar indicadores e gráficos dentro da superfície autorizada.");
    }

    ObjectNode richContentDocument(ObjectNode inputs) {
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

    ObjectNode emptyCompiledFormPatch() {
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

    private Optional<AgenticAuthoringUiCompositionPlanResult> tableExportModification(
            AgenticAuthoringPlanRequest request,
            JsonNode persistedUiCompositionPlan) {
        return workbenchPlanSupport.tableExportModification(request, persistedUiCompositionPlan);
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> tableColumnModification(
            AgenticAuthoringPlanRequest request,
            JsonNode persistedUiCompositionPlan) {
        return workbenchPlanSupport.tableColumnModification(request, persistedUiCompositionPlan);
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> chartModification(
            AgenticAuthoringPlanRequest request,
            JsonNode persistedUiCompositionPlan) {
        return workbenchPlanSupport.chartModification(request, persistedUiCompositionPlan);
    }

    JsonNode contextPreviewPage(AgenticAuthoringPlanRequest request) {
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

    private JsonNode dashboardRepairSnapshot(AgenticAuthoringPlanRequest request) {
        JsonNode previewPage = contextPreviewPage(request);
        if (isMaterializedPage(previewPage)) {
            return previewPage;
        }
        JsonNode contextHints = request == null ? null : request.contextHints();
        if (contextHints == null || !contextHints.isObject()) {
            return null;
        }
        JsonNode uiCompositionPlan = contextHints.path("uiCompositionPlan");
        if (uiCompositionPlan.isObject()
                && uiCompositionPlan.path("widgets").isArray()
                && !uiCompositionPlan.path("widgets").isEmpty()) {
            return uiCompositionPlan;
        }
        return null;
    }

    private ObjectNode dashboardRepairSnapshotSummary(JsonNode snapshot) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("schemaVersion", "praxis-dashboard-repair-input-snapshot.v1");
        summary.put("kind", safe(snapshot.path("kind").asText()));
        summary.put("layoutPreset", safe(snapshot.path("layoutPreset").asText()));
        JsonNode widgetsNode = snapshot.path("widgets");
        int widgetCount = widgetsNode.isArray() ? widgetsNode.size() : 0;
        summary.put("widgetCount", widgetCount);
        ArrayNode widgets = summary.putArray("widgets");
        if (widgetsNode.isArray()) {
            for (int index = 0; index < Math.min(widgetCount, 16); index++) {
                JsonNode widget = widgetsNode.path(index);
                if (!widget.isObject()) {
                    continue;
                }
                ObjectNode widgetSummary = widgets.addObject();
                widgetSummary.put("key", safe(widget.path("key").asText()));
                widgetSummary.put("componentId", firstNonBlank(
                        widget.path("componentId").asText(),
                        widget.path("definition").path("id").asText(),
                        widget.path("definition").path("componentId").asText()));
                widgetSummary.put("role", safe(widget.path("role").asText()));
            }
        }
        JsonNode bindings = snapshot.path("bindings").isArray()
                ? snapshot.path("bindings")
                : snapshot.path("composition").path("links");
        summary.put("bindingCount", bindings.isArray() ? bindings.size() : 0);
        return summary;
    }

    boolean isMaterializedPage(JsonNode page) {
        return page != null
                && page.isObject()
                && page.path("widgets").isArray()
                && !page.path("widgets").isEmpty();
    }

    String widgetKey(AgenticAuthoringCandidate candidate, String suffix) {
        String slug = slug(baseResourceName(candidate == null ? "" : businessResourcePath(candidate.resourcePath())));
        return (slug.isBlank() ? "resource" : slug) + "-" + suffix;
    }

    String resourceTitle(AgenticAuthoringCandidate candidate) {
        return AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate);
    }

    String titleFromResourcePath(String path) {
        String title = AgenticAuthoringResourcePresentationLabel.fromResourcePath(path);
        return "o recurso selecionado".equals(title) ? "Resource" : title;
    }

    List<DashboardDimension> dashboardDimensions(
            AgenticAuthoringVisualizationDecision visualizationDecision,
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringPlanRequest request) {
        Optional<DashboardDimension> governedComparison =
                governedComparisonDimension(visualizationDecision, request);
        if (governedComparison.isPresent()) {
            return List.of(governedComparison.get());
        }
        List<DashboardDimension> preservedDimensions = preserveCurrentDashboardDimensions(request)
                ? currentDashboardDimensions(request.currentPage())
                : List.of();
        List<DashboardDimension> dimensions = new ArrayList<>();
        List<FieldCandidate> fieldCandidates = dashboardFieldCandidates(candidate,
                request == null ? null : request.contextHints());
        boolean temporalEvolution = isTemporalEvolutionRequest(request, visualizationDecision);
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
                String label = valueOrDefault(axis.label(), titleFromResourcePath(field));
                String filterField = canonicalDashboardFilterField(field, label, fieldCandidates);
                metricField = canonicalFieldName(metricField);
                if ("count".equals(normalize(metricAggregation))) {
                    metricField = "";
                }
                boolean temporalMetricEvolutionContext =
                        temporalEvolution && hasGovernedTemporalMetricEvolutionContext(fieldCandidates);
                List<DashboardDimension> canonicalEvolutionDimensions = temporalMetricEvolutionContext
                        ? defaultTemporalEvolutionDimensions(fieldCandidates)
                        : List.of();
                FieldCandidate exactFieldCandidate = matchingDashboardFieldCandidate(field, fieldCandidates);
                boolean temporalAxis = (exactFieldCandidate != null && exactFieldCandidate.timeSeriesHint())
                        || isTemporalFieldNameOrLabel(field, label)
                        || "temporal".equalsIgnoreCase(safe(axis.orientation()))
                        || containsAny(searchableText(axis.concept()), "tempo", "data", "periodo", "evolucao", "competencia");
                final String normalizedAxisField = normalize(field);
                boolean matchedSecondaryEvolutionDimension = false;
                boolean entityScopeIdentityAxis = temporalMetricEvolutionContext
                        && isEntityScopeIdentityAxis(normalizedAxisField, fieldCandidates);
                if (entityScopeIdentityAxis && !canonicalEvolutionDimensions.isEmpty()) {
                    boolean alreadyHasTemporal = dimensions.stream().anyMatch(this::isTemporalDimension);
                    if (!alreadyHasTemporal) {
                        DashboardDimension primaryCanonical = canonicalEvolutionDimensions.get(0);
                        field = primaryCanonical.field();
                        filterField = primaryCanonical.filterField();
                        label = primaryCanonical.label();
                        temporalAxis = true;
                    } else if (canonicalEvolutionDimensions.size() > 1) {
                        DashboardDimension secondaryCanonical = canonicalEvolutionDimensions.get(1);
                        field = secondaryCanonical.field();
                        filterField = secondaryCanonical.filterField();
                        label = secondaryCanonical.label();
                        matchedSecondaryEvolutionDimension = true;
                    }
                } else if (temporalMetricEvolutionContext
                        && temporalAxis
                        && !canonicalEvolutionDimensions.isEmpty()
                        && dimensions.stream().anyMatch(this::isTemporalDimension)
                        && canonicalEvolutionDimensions.size() > 1) {
                    DashboardDimension secondaryCanonical = canonicalEvolutionDimensions.get(1);
                    field = secondaryCanonical.field();
                    filterField = secondaryCanonical.filterField();
                    label = secondaryCanonical.label();
                    temporalAxis = false;
                    matchedSecondaryEvolutionDimension = true;
                } else if (temporalMetricEvolutionContext
                        && temporalAxis
                        && !canonicalEvolutionDimensions.isEmpty()
                        && (normalize(canonicalEvolutionDimensions.get(0).field()).equals(normalizedAxisField)
                                || (exactFieldCandidate != null && exactFieldCandidate.metricHint() && !exactFieldCandidate.timeSeriesHint())
                                || (canonicalEvolutionDimensions.stream()
                                        .noneMatch(cd -> normalize(cd.field()).equals(normalizedAxisField))
                                        && (exactFieldCandidate == null || !exactFieldCandidate.timeSeriesHint())))) {
                    DashboardDimension primaryCanonical = canonicalEvolutionDimensions.get(0);
                    field = primaryCanonical.field();
                    filterField = primaryCanonical.filterField();
                    label = primaryCanonical.label();
                } else if (temporalMetricEvolutionContext
                        && !temporalAxis
                        && canonicalEvolutionDimensions.size() > 1
                        && (normalize(canonicalEvolutionDimensions.get(1).field()).equals(normalizedAxisField)
                                || (canonicalEvolutionDimensions.stream()
                                        .noneMatch(cd -> normalize(cd.field()).equals(normalizedAxisField))
                                        && (exactFieldCandidate == null || !exactFieldCandidate.groupByHint())))) {
                    DashboardDimension secondaryCanonical = canonicalEvolutionDimensions.get(1);
                    field = secondaryCanonical.field();
                    filterField = secondaryCanonical.filterField();
                    label = secondaryCanonical.label();
                    matchedSecondaryEvolutionDimension = true;
                }
                FieldCandidate metricCandidate = defaultTemporalEvolutionMetricCandidate(fieldCandidates);
                if (temporalMetricEvolutionContext
                        && metricCandidate != null
                        && (metricField.isBlank()
                                || matchingDashboardFieldCandidate(metricField, fieldCandidates) == null
                                || !matchingDashboardFieldCandidate(metricField, fieldCandidates).metricHint()
                                || normalize(metricCandidate.field()).equals(normalize(metricField))
                                || matchedSecondaryEvolutionDimension)) {
                    boolean promptExplicitlyAsksSum = request != null
                            && containsAny(searchableText(request.userPrompt()), "soma", "somatorio", "total ");
                    if (metricField.isBlank()
                            || "count".equals(normalize(metricAggregation))
                            || (metricCandidate.avgEligible()
                                    && "sum".equals(normalize(metricAggregation))
                                    && !promptExplicitlyAsksSum)) {
                        metricAggregation = metricCandidate.avgEligible() ? "avg" : "sum";
                    }
                    metricField = metricCandidate.field();
                    String defaultMetricLabel = defaultAggregatedMetricLabel(metricCandidate, metricAggregation);
                    if (metricLabel.isBlank()
                            || "Total".equalsIgnoreCase(metricLabel)
                            || containsAny(searchableText(metricLabel), "total")
                            || ("avg".equals(normalize(metricAggregation)) && !containsAny(searchableText(metricLabel), "media"))
                            || normalize(metricCandidate.label()).equals(normalize(metricLabel))
                            || matchedSecondaryEvolutionDimension) {
                        metricLabel = defaultMetricLabel;
                    }
                }
                String defaultChartType = temporalEvolution && temporalAxis
                        ? "area"
                        : matchedSecondaryEvolutionDimension && canonicalEvolutionDimensions.size() > 1
                        ? canonicalEvolutionDimensions.get(1).chartType()
                        : "bar";
                String chartType = valueOrDefault(axis.chartType(), defaultChartType);
                if (matchedSecondaryEvolutionDimension
                        && canonicalEvolutionDimensions.size() > 1
                        && "bar".equalsIgnoreCase(chartType)) {
                    chartType = canonicalEvolutionDimensions.get(1).chartType();
                }
                String orientation = valueOrDefault(
                        axis.orientation(),
                        temporalEvolution && temporalAxis && !isCategoricalPeriodField(field) ? "temporal" : "vertical");
                if (temporalEvolution && temporalAxis && "bar".equalsIgnoreCase(chartType)) {
                    chartType = "area";
                }
                if (temporalEvolution && temporalAxis && isCategoricalPeriodField(field)) {
                    orientation = "vertical";
                } else if (temporalEvolution && temporalAxis && "vertical".equalsIgnoreCase(orientation) && !isCategoricalPeriodField(field)) {
                    orientation = "temporal";
                }
                String title = temporalMetricEvolutionContext && temporalAxis && !canonicalEvolutionDimensions.isEmpty()
                        ? canonicalEvolutionDimensions.get(0).title()
                        : matchedSecondaryEvolutionDimension
                        ? canonicalEvolutionDimensions.get(1).title()
                        : "Registros por " + label;
                dimensions.add(new DashboardDimension(
                        safe(axis.concept()),
                        field,
                        filterField,
                        label,
                        title,
                        chartType,
                        orientation,
                        metricAggregation,
                        metricField,
                        metricLabel,
                        valueOrDefault(axis.provenance(), "llm-authored-semantic-axis")));
            }
        }
        if (temporalEvolution && hasGovernedTemporalMetricEvolutionContext(fieldCandidates)) {
            List<DashboardDimension> evolutionDefaults = defaultTemporalEvolutionDimensions(fieldCandidates);
            if (dimensions.isEmpty()) {
                dimensions.addAll(evolutionDefaults);
            } else {
                if (dimensions.stream().noneMatch(this::isTemporalDimension) && !evolutionDefaults.isEmpty()) {
                    dimensions.add(0, evolutionDefaults.get(0));
                }
                if (dimensions.size() == 1 && evolutionDefaults.size() > 1) {
                    String existingField = normalize(dimensions.get(0).field());
                    for (DashboardDimension fallbackDim : evolutionDefaults) {
                        if (!normalize(fallbackDim.field()).equals(existingField)) {
                            dimensions.add(fallbackDim);
                        }
                    }
                }
            }
        }
        List<DashboardDimension> inferredDimensions = inferredDashboardDimensions(candidate, request);
        if (dimensions.isEmpty()) {
            dimensions.addAll(inferredDimensions);
        }
        if (dimensions.isEmpty()) {
            dimensions.add(unresolvedDashboardDimension());
        }
        LinkedHashMap<String, DashboardDimension> uniqueDimensions = new LinkedHashMap<>();
        for (DashboardDimension preservedDimension : preservedDimensions) {
            uniqueDimensions.putIfAbsent(preservedDimension.field(), preservedDimension);
        }
        for (DashboardDimension dimension : dimensions) {
            // The newest semantic decision wins when it refines an already materialized axis.
            // LinkedHashMap keeps the original visual order while replacing the axis definition.
            uniqueDimensions.put(dimension.field(), dimension);
        }
        return uniqueDimensions
                .values()
                .stream()
                .sorted((left, right) -> Integer.compare(
                        dashboardDimensionPriority(right, fieldCandidates, temporalEvolution),
                        dashboardDimensionPriority(left, fieldCandidates, temporalEvolution)))
                .limit(3)
                .toList();
    }

    private boolean preserveCurrentDashboardDimensions(AgenticAuthoringPlanRequest request) {
        if (request == null
                || request.intentResolution() == null
                || !"dashboard".equals(safe(request.intentResolution().artifactKind()))
                || !isMaterializedPage(request.currentPage())) {
            return false;
        }
        if ("modify".equals(safe(request.intentResolution().operationKind()))
                && supportsDashboardQualityRepair(request)) {
            return true;
        }
        AgenticAuthoringSemanticDecision semanticDecision = request.intentResolution().semanticDecision();
        AgenticAuthoringSemanticRefinement refinement = semanticDecision == null
                ? null
                : semanticDecision.refinement();
        return refinement != null
                && refinement.preservesResource()
                && refinement.remove().stream().noneMatch(value -> "chart".equals(normalize(value)));
    }

    private List<DashboardDimension> currentDashboardDimensions(JsonNode currentPage) {
        if (!isMaterializedPage(currentPage)) {
            return List.of();
        }
        List<DashboardDimension> dimensions = new ArrayList<>();
        for (JsonNode widget : currentPage.path("widgets")) {
            if (!(widget instanceof ObjectNode widgetObject)
                    || !"praxis-chart".equals(workbenchPlanSupport.widgetComponentId(widgetObject))) {
                continue;
            }
            DashboardDimension dimension = workbenchPlanSupport.dimensionFromChartWidget(widgetObject);
            if (isResolvedDimension(dimension)) {
                dimensions.add(dimension);
            }
        }
        return List.copyOf(dimensions);
    }

    private Optional<DashboardDimension> governedComparisonDimension(
            AgenticAuthoringVisualizationDecision visualizationDecision,
            AgenticAuthoringPlanRequest request) {
        JsonNode grounding = governedAnalyticsContext(request);
        JsonNode projection = grounding.path("projection");
        if (!"verified".equals(grounding.path("status").asText(""))
                || !"comparison".equals(grounding.path("requestedOperation").asText(""))
                || !projection.isObject()) {
            return Optional.empty();
        }
        JsonNode dimension = projection.path("bindings").path("primaryDimension");
        JsonNode metrics = projection.path("bindings").path("primaryMetrics");
        String rawField = dimension.path("field").asText("");
        if (rawField.isBlank() || !metrics.isArray() || metrics.isEmpty()) {
            return Optional.empty();
        }
        String field = canonicalFieldName(rawField);
        boolean crossFilterEnabled = projection.path("interactions").path("crossFilter").asBoolean(false);
        String keyFilterField = dimension.path("keyFilterField").asText("").trim();
        if (crossFilterEnabled && keyFilterField.isBlank()) {
            return Optional.empty();
        }
        String filterField = crossFilterEnabled ? keyFilterField : field;
        String label = valueOrDefault(dimension.path("label").asText(""), titleFromResourcePath(field));
        JsonNode firstMetric = metrics.get(0);
        String rawMetricField = firstMetric.path("field").asText("");
        if (rawMetricField.isBlank()) {
            return Optional.empty();
        }
        String metricField = canonicalFieldName(rawMetricField);
        String metricAggregation = canonicalAnalyticsAggregation(firstMetric.path("aggregation").asText("count"));
        String metricLabel = valueOrDefault(firstMetric.path("label").asText(""), titleFromResourcePath(metricField));
        AgenticAuthoringVisualizationAxisDecision visualAxis = visualizationAxisForField(
                visualizationDecision,
                field);
        String projectionId = projection.path("id").asText("comparison");
        return Optional.of(new DashboardDimension(
                valueOrDefault(visualAxis == null ? "" : visualAxis.concept(), "comparison-" + field),
                field,
                filterField,
                label,
                "Comparativo por " + label,
                valueOrDefault(visualAxis == null ? "" : visualAxis.chartType(), "bar"),
                valueOrDefault(visualAxis == null ? "" : visualAxis.orientation(), "vertical"),
                metricAggregation,
                metricField,
                metricLabel,
                "x-ui.analytics.projection:" + projectionId,
                projection.deepCopy()));
    }

    private AgenticAuthoringVisualizationAxisDecision visualizationAxisForField(
            AgenticAuthoringVisualizationDecision visualizationDecision,
            String field) {
        if (visualizationDecision == null || visualizationDecision.axes() == null) {
            return null;
        }
        return visualizationDecision.axes().stream()
                .filter(axis -> axis != null && normalize(axis.field()).equals(normalize(field)))
                .findFirst()
                .orElse(null);
    }

    private int dashboardDimensionPriority(
            DashboardDimension dimension,
            List<FieldCandidate> fieldCandidates,
            boolean temporalEvolution) {
        if (dimension == null || dimension.field().isBlank()) {
            return -100;
        }
        if (temporalEvolution && isTemporalDimension(dimension)) {
            return 60;
        }
        FieldCandidate field = matchingDashboardFieldCandidate(dimension.field(), fieldCandidates);
        FieldCandidate filterField = matchingDashboardFieldCandidate(dimension.filterField(), fieldCandidates);
        int score = 0;
        if (field != null && field.optionSourceHint()) {
            score += 24;
        }
        if (filterField != null && filterField.optionSourceHint()) {
            score += 24;
        }
        if (field != null && field.categoricalHint()) {
            score += 8;
        }
        if (filterField != null && filterField.categoricalHint()) {
            score += 8;
        }
        if (!safe(dimension.filterField()).isBlank()
                && !normalize(dimension.filterField()).equals(normalize(dimension.field()))) {
            score += 12;
        }
        if (isLikelyTechnicalOrMeasureField(dimension.field(), dimension.label())) {
            score -= 24;
        }
        String searchable = searchableText(dimension.field() + " " + dimension.label());
        if (containsAny(searchable, "competencia", "periodo", "mes", "ano", "year", "month", "period")) {
            score -= 12;
        }
        return score;
    }

    private boolean isTemporalDimension(DashboardDimension dimension) {
        return temporalSupport(List.of()).isTemporalDimension(dimension);
    }

    private boolean isTemporalFieldNameOrLabel(String field, String label) {
        return temporalSupport(List.of()).isTemporalFieldNameOrLabel(field, label);
    }

    private boolean isCategoricalPeriodField(String field) {
        return temporalSupport(List.of()).isCategoricalPeriodField(field);
    }

    private boolean isTemporalEvolutionRequest(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        return temporalSupport(List.of()).isTemporalEvolutionRequest(request, visualizationDecision);
    }

    private boolean hasGovernedTemporalMetricEvolutionContext(List<FieldCandidate> fieldCandidates) {
        return temporalSupport(fieldCandidates).hasGovernedTemporalMetricEvolutionContext(fieldCandidates);
    }

    private boolean isEntityScopeIdentityAxis(String normalizedAxisField, List<FieldCandidate> fieldCandidates) {
        return temporalSupport(fieldCandidates).isEntityScopeIdentityAxis(normalizedAxisField, fieldCandidates);
    }

    private FieldCandidate defaultTemporalEvolutionMetricCandidate(List<FieldCandidate> fieldCandidates) {
        return temporalSupport(fieldCandidates).defaultTemporalEvolutionMetricCandidate(fieldCandidates);
    }

    private String defaultAggregatedMetricLabel(FieldCandidate metricCandidate, String aggregation) {
        return temporalSupport(List.of()).defaultAggregatedMetricLabel(metricCandidate, aggregation);
    }

    private List<DashboardDimension> defaultTemporalEvolutionDimensions(List<FieldCandidate> fieldCandidates) {
        return temporalSupport(fieldCandidates).defaultTemporalEvolutionDimensions(fieldCandidates);
    }

    private String preferredTemporalOrDashboardFilterField(
            String field,
            String label,
            List<FieldCandidate> fieldCandidates) {
        return temporalSupport(fieldCandidates).preferredTemporalOrDashboardFilterField(field, label, fieldCandidates);
    }

    private String preferredEntityScopeFilterField(
            List<FieldCandidate> fieldCandidates,
            List<DashboardDimension> dimensions) {
        return temporalSupport(fieldCandidates).preferredEntityScopeFilterField(fieldCandidates, dimensions);
    }

    private AgenticAuthoringTemporalAnalyticalPlanSupport temporalSupport(List<FieldCandidate> fieldCandidates) {
        return new AgenticAuthoringTemporalAnalyticalPlanSupport(
                this::dashboardFieldStem,
                this::titleFromResourcePath,
                (field, label) -> canonicalDashboardFilterField(field, label, fieldCandidates));
    }

    private FieldCandidate matchingDashboardFieldCandidate(String field, List<FieldCandidate> fieldCandidates) {
        String normalized = normalize(field);
        if (normalized.isBlank() || fieldCandidates == null || fieldCandidates.isEmpty()) {
            return null;
        }
        return fieldCandidates.stream()
                .filter(candidate -> normalize(candidate.field()).equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private List<DashboardDimension> inferredDashboardDimensions(
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringPlanRequest request) {
        String prompt = request == null ? "" : request.userPrompt();
        List<FieldCandidate> fields = dashboardFieldCandidates(candidate, request == null ? null : request.contextHints());
        if (fields.isEmpty()) {
            return List.of();
        }
        String normalizedPrompt = normalize(prompt).replaceAll("[^a-z0-9]+", " ").trim();
        List<ScoredFieldCandidate> scored = new ArrayList<>();
        for (int index = 0; index < fields.size(); index++) {
            FieldCandidate field = fields.get(index);
            int score = scoreFieldCandidateForDashboard(field, normalizedPrompt);
            if (score > 0) {
                scored.add(new ScoredFieldCandidate(field, score, index));
            }
        }
        scored.sort((left, right) -> {
            int score = Integer.compare(right.score(), left.score());
            return score != 0 ? score : Integer.compare(left.index(), right.index());
        });
        int strongestScore = scored.stream()
                .mapToInt(ScoredFieldCandidate::score)
                .max()
                .orElse(0);
        if (strongestScore >= 5) {
            scored = scored.stream()
                    .filter(field -> field.score() >= 5)
                    .toList();
        }
        return scored.stream()
                .map(ScoredFieldCandidate::field)
                .map(field -> inferredDashboardDimension(
                        field.concept(),
                        canonicalDashboardAnalyticalField(field, fields),
                        field.label(),
                        "Registros por " + field.label(),
                        field.provenance()))
                .toList();
    }

    private String canonicalDashboardAnalyticalField(FieldCandidate field, List<FieldCandidate> fields) {
        if (field == null) {
            return "";
        }
        String canonicalField = canonicalFieldName(field.field());
        if (!field.optionSourceHint() || fields == null || fields.isEmpty()) {
            return canonicalField;
        }
        String stem = dashboardFieldStem(canonicalField);
        String labelStem = dashboardFieldStem(field.label());
        return fields.stream()
                .filter(candidate -> !candidate.optionSourceHint())
                .filter(candidate -> !normalize(candidate.field()).equals(normalize(canonicalField)))
                .filter(candidate -> {
                    String candidateStem = dashboardFieldStem(candidate.field());
                    String candidateLabelStem = dashboardFieldStem(candidate.label());
                    return !stem.isBlank() && (candidateStem.equals(stem) || candidateLabelStem.equals(stem))
                            || !labelStem.isBlank()
                                    && (candidateStem.equals(labelStem) || candidateLabelStem.equals(labelStem));
                })
                .filter(candidate -> !isLikelyTechnicalOrMeasureField(candidate.field(), candidate.label()))
                .map(FieldCandidate::field)
                .findFirst()
                .orElse(canonicalField);
    }

    private int scoreFieldCandidateForDashboard(FieldCandidate field, String normalizedPrompt) {
        if (field == null || field.field().isBlank()) {
            return 0;
        }
        if (field.metricHint() && !field.groupByHint()) {
            return 0;
        }
        String normalizedField = searchableText(field.field());
        String normalizedLabel = searchableText(field.label());
        String combined = (normalizedField + " " + normalizedLabel).trim();
        for (String measureToken : List.of("valor", "value", "amount", "saldo", "preco", "price")) {
            if (phrasePresent(combined, measureToken) && !field.groupByHint()) {
                return 0;
            }
        }
        String compactPrompt = normalizedPrompt.replace(" ", "");
        String compactField = normalizedField.replace(" ", "");
        String compactLabel = normalizedLabel.replace(" ", "");
        int score = 0;
        if (!normalizedPrompt.isBlank()) {
            if (!normalizedLabel.isBlank() && phrasePresent(normalizedPrompt, normalizedLabel)) {
                score += 8;
            }
            if (!normalizedField.isBlank() && phrasePresent(normalizedPrompt, normalizedField)) {
                score += 7;
            }
            if (!compactLabel.isBlank() && compactPrompt.contains(compactLabel)) {
                score += 4;
            }
            if (!compactField.isBlank() && compactPrompt.contains(compactField)) {
                score += 3;
            }
            score += fieldTokenMatchScore(normalizedPrompt, normalizedLabel, 5);
            score += fieldTokenMatchScore(normalizedPrompt, normalizedField, 4);
        }
        if (field.groupByHint()) {
            score += 3;
        } else if (field.categoricalHint()) {
            score += 2;
        }
        if (!field.groupByHint() && isLikelyTechnicalOrMeasureField(field.field(), field.label())) {
            score -= 4;
        }
        return Math.max(score, 0);
    }

    private int fieldTokenMatchScore(String normalizedPrompt, String normalizedValue, int weight) {
        if (normalizedPrompt.isBlank() || normalizedValue.isBlank()) {
            return 0;
        }
        int score = 0;
        for (String token : normalizedValue.split("\\s+")) {
            if (isMeaningfulFieldToken(token) && phrasePresent(normalizedPrompt, token)) {
                score += weight;
            }
        }
        return Math.min(score, weight * 2);
    }

    private boolean isMeaningfulFieldToken(String token) {
        String normalized = safe(token);
        return normalized.length() > 2
                && !Set.of("nome", "name", "label", "text", "type", "field", "data").contains(normalized);
    }

    private boolean phrasePresent(String normalizedText, String normalizedPhrase) {
        if (normalizedText.isBlank() || normalizedPhrase.isBlank()) {
            return false;
        }
        String text = " " + normalizedText + " ";
        String phrase = " " + normalizedPhrase + " ";
        return text.contains(phrase);
    }

    private boolean isLikelyTechnicalOrMeasureField(String field, String label) {
        String normalizedField = searchableText(field);
        String normalizedLabel = searchableText(label);
        String combined = searchableText(field + " " + label);
        for (String token : List.of(
                "id", "uuid", "codigo", "code", "created", "updated", "deleted", "data", "date",
                "time", "timestamp", "valor", "value", "amount", "total", "saldo", "preco", "price",
                "count", "quantidade", "qtd")) {
            if (phrasePresent(combined, token)) {
                return true;
            }
        }
        for (String token : List.of("nome", "name", "email", "cpf", "telefone", "phone", "avatar", "foto")) {
            if (phrasePresent(normalizedLabel, token) || token.equals(normalizedField)) {
                return true;
            }
        }
        if (isLikelyRecordIdentityNameField(normalizedField, normalizedLabel, combined)) {
            return true;
        }
        return false;
    }

    private boolean isLikelyRecordIdentityNameField(
            String normalizedField,
            String normalizedLabel,
            String combined) {
        boolean nameLikeField = normalizedField.endsWith(" name")
                || normalizedField.endsWith(" nome")
                || normalizedField.endsWith(" title")
                || normalizedField.endsWith(" titulo");
        boolean nameLikeLabel = List.of("nome", "name", "titulo", "title", "descricao", "description")
                .contains(normalizedLabel);
        if (!nameLikeField && !nameLikeLabel) {
            return false;
        }
        return !containsAny(combined,
                "status", "situacao", "categoria", "category", "departamento", "department", "cargo", "role",
                "funcao", "area", "segmento", "segment", "tipo", "type", "canal", "channel", "regiao", "region",
                "grupo", "group", "classe", "class", "nivel", "level", "prioridade", "priority", "severidade",
                "severity", "responsavel", "owner");
    }

    private String searchableText(String value) {
        return normalize(safe(value).replaceAll("([a-z])([A-Z])", "$1 $2"))
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private List<FieldCandidate> dashboardFieldCandidates(
            AgenticAuthoringCandidate candidate,
            JsonNode contextHints) {
        Map<String, FieldCandidate> fields = new LinkedHashMap<>();
        String targetResourcePath = candidate == null ? "" : businessResourcePath(candidate.resourcePath());
        collectFieldCandidatesFromJson(contextHints, "context-hints", targetResourcePath, fields);
        if (candidate != null) {
            for (String evidence : candidate.evidence() == null ? List.<String>of() : candidate.evidence()) {
                collectFieldCandidatesFromText(evidence, "candidate-evidence", fields);
            }
            AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
            if (bundle != null) {
                for (AgenticAuthoringEvidenceBundle.Evidence evidence : bundle.evidence()) {
                    collectFieldCandidatesFromText(evidence.summary(), "evidence-summary:" + evidence.kind(), fields);
                    collectFieldCandidatesFromText(evidence.ref(), "evidence-ref:" + evidence.kind(), fields);
                    for (String term : evidence.matchedTerms()) {
                        collectFieldCandidatesFromText(term, "evidence-term:" + evidence.kind(), fields);
                    }
                }
            }
        }
        return fields.values().stream()
                .filter(field -> isUsableFieldCandidate(field.field()))
                .toList();
    }

    private void collectFieldCandidatesFromJson(
            JsonNode node,
            String provenance,
            String targetResourcePath,
            Map<String, FieldCandidate> fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectFieldCandidatesFromJson(child, provenance, targetResourcePath, fields);
            }
            return;
        }
        if (!node.isObject()) {
            if (node.isTextual()) {
                collectFieldCandidatesFromText(node.asText(), provenance, fields);
            }
            return;
        }
        String nodeResourcePath = businessResourcePath(jsonText(node, "resourcePath"));
        if (!safe(targetResourcePath).isBlank()
                && !safe(nodeResourcePath).isBlank()
                && !normalize(targetResourcePath).equals(normalize(nodeResourcePath))
                && (node.has("analyticsFields")
                        || node.has("submitUrl")
                        || node.has("schemaUrl")
                        || provenance.contains("candidates"))) {
            return;
        }
        String field = firstNonBlank(
                jsonText(node, "field"),
                jsonText(node, "fieldName"),
                jsonText(node, "name"),
                jsonText(node, "property"),
                jsonText(node, "path"),
                jsonText(node, "id"),
                jsonText(node, "key"));
        String label = firstNonBlank(
                jsonText(node, "label"),
                jsonText(node, "header"),
                jsonText(node, "title"),
                jsonText(node, "displayName"),
                jsonText(node, "description"));
        String typeText = String.join(" ",
                jsonText(node, "type"),
                jsonText(node, "dataType"),
                jsonText(node, "controlType"),
                jsonText(node, "optionSourceType"),
                jsonText(node, "sourceKind"),
                jsonText(node, "semanticKind"));
        String optionSourceType = firstNonBlank(
                jsonText(node, "optionSourceType"),
                jsonText(node.path("optionSource"), "type"),
                jsonText(node.path("x-ui").path("optionSource"), "type"));
        boolean categoricalHint = containsAny(typeText,
                "enum", "select", "option", "categorical", "category", "dimension", "string", "text", "boolean")
                || node.path("groupByEligible").asBoolean(false);
        boolean optionSourceHint = containsAny(typeText, "select", "option")
                || node.has("optionSource")
                || node.has("options")
                || node.has("optionSourceUrl")
                || node.has("optionResourcePath")
                || !safe(jsonText(node, "endpoint")).isBlank();
        boolean timeSeriesHint = node.path("timeSeriesEligible").asBoolean(false)
                || jsonArrayContains(node.path("modes"), "time-series", "timeseries")
                || containsAny(jsonText(node, "format"), "date", "date-time");
        boolean groupByHint = node.path("groupByEligible").asBoolean(false)
                || jsonArrayContains(node.path("modes"), "group-by", "groupby");
        boolean metricHint = node.path("metricFieldEligible").asBoolean(false);
        boolean avgEligible = jsonArrayContains(node.path("allowedAggregations"), "avg")
                || jsonArrayContains(node.path("metrics"), "avg");
        boolean entityLookupHint = "resource_entity".equalsIgnoreCase(safe(optionSourceType))
                || node.path("entityLookup").asBoolean(false)
                || (optionSourceHint
                        && (normalize(field).endsWith("id")
                                || normalize(field).endsWith("ids")
                                || normalize(field).endsWith("idsin")));
        if (!safe(field).isBlank()) {
            addFieldCandidate(
                    fields,
                    field,
                    label,
                    categoricalHint,
                    optionSourceHint,
                    timeSeriesHint,
                    groupByHint,
                    metricHint,
                    avgEligible,
                    entityLookupHint,
                    provenance);
        }
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (List.of("fields", "columns", "properties", "schemaFields", "filterableFields",
                    "filterSchemaFields", "analyticsFields", "resourceFields", "fieldCatalog", "fieldMetadata")
                    .contains(key)) {
                collectFieldCandidatesFromJson(value, provenance + ":" + key, targetResourcePath, fields);
            } else if (value.isObject() || value.isArray()) {
                collectFieldCandidatesFromJson(value, provenance + ":" + key, targetResourcePath, fields);
            }
        });
    }

    private boolean jsonArrayContains(JsonNode arrayNode, String... expectedValues) {
        if (arrayNode == null || !arrayNode.isArray() || expectedValues == null) {
            return false;
        }
        for (JsonNode item : arrayNode) {
            String normalizedItem = normalize(item.asText("")).replace('_', '-');
            for (String expected : expectedValues) {
                if (normalizedItem.equals(normalize(expected).replace('_', '-'))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void collectFieldCandidatesFromText(
            String text,
            String provenance,
            Map<String, FieldCandidate> fields) {
        String safeText = safe(text);
        if (safeText.isBlank()) {
            return;
        }
        Matcher matcher = FIELD_DECLARATION_PATTERN.matcher(safeText);
        while (matcher.find()) {
            addFieldCandidate(
                    fields,
                    matcher.group(1),
                    "",
                    containsAny(safeText,
                            "enum", "select", "option", "categorical", "category", "dimension", "filterable", "group-by"),
                    containsAny(safeText, "select", "option"),
                    false,
                    containsAny(safeText, "group-by"),
                    false,
                    false,
                    false,
                    provenance);
        }
    }

    private void addFieldCandidate(
            Map<String, FieldCandidate> fields,
            String rawField,
            String rawLabel,
            boolean categoricalHint,
            boolean optionSourceHint,
            boolean timeSeriesHint,
            boolean groupByHint,
            boolean metricHint,
            boolean avgEligible,
            boolean entityLookupHint,
            String provenance) {
        String field = canonicalFieldName(rawField);
        if (!isUsableFieldCandidate(field)) {
            return;
        }
        String key = normalize(field);
        FieldCandidate existing = fields.get(key);
        String defaultTitle = titleFromResourcePath(field);
        boolean shouldUpgradeExistingLabel = existing == null
                || safe(existing.label()).isBlank()
                || existing.label().equals(defaultTitle)
                || (normalize(existing.label()).equals(normalize(defaultTitle))
                        && (!normalize(rawLabel).equals(normalize(defaultTitle))
                                || hasNonAsciiChars(rawLabel) && !hasNonAsciiChars(existing.label())))
                || (normalize(existing.label()).equals(normalize(rawLabel))
                        && hasNonAsciiChars(rawLabel) && !hasNonAsciiChars(existing.label()));
        String resolvedLabel = !safe(rawLabel).isBlank() && shouldUpgradeExistingLabel
                ? rawLabel
                : existing != null && !safe(existing.label()).isBlank()
                        ? existing.label()
                        : valueOrDefault(rawLabel, defaultTitle);
        FieldCandidate next = new FieldCandidate(
                 existing == null ? field : existing.field(),
                resolvedLabel,
                normalize(resolvedLabel).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", ""),
                categoricalHint || (existing != null && existing.categoricalHint()),
                optionSourceHint || (existing != null && existing.optionSourceHint()),
                timeSeriesHint || (existing != null && existing.timeSeriesHint()),
                groupByHint || (existing != null && existing.groupByHint()),
                metricHint || (existing != null && existing.metricHint()),
                avgEligible || (existing != null && existing.avgEligible()),
                entityLookupHint || (existing != null && existing.entityLookupHint()),
                existing == null ? provenance : existing.provenance() + "," + provenance);
        fields.put(key, next);
    }

    private boolean isUsableFieldCandidate(String field) {
        String normalized = normalize(field).replaceAll("[^a-z0-9]+", " ").trim();
        if (normalized.isBlank() || normalized.length() < 2 || normalized.length() > 80) {
            return false;
        }
        if (normalized.split("\\s+").length > 3) {
            return false;
        }
        return !Set.of("resource", "schema", "fields", "columns", "properties", "filter", "filters").contains(normalized);
    }

    private boolean hasNonAsciiChars(String value) {
        String text = safe(value);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return true;
            }
        }
        return false;
    }

    private String canonicalDashboardFilterField(
            String analyticalField,
            String analyticalLabel,
            List<FieldCandidate> fields) {
        String field = canonicalFieldName(analyticalField);
        if (field.isBlank() || fields == null || fields.isEmpty()) {
            return field;
        }
        FieldCandidate exact = fields.stream()
                .filter(candidate -> normalize(candidate.field()).equals(normalize(field)))
                .findFirst()
                .orElse(null);
        if (exact != null && exact.optionSourceHint()) {
            return exact.field();
        }
        String stem = dashboardFieldStem(field);
        String labelStem = dashboardFieldStem(analyticalLabel);
        return fields.stream()
                .filter(FieldCandidate::optionSourceHint)
                .filter(candidate -> !normalize(candidate.field()).equals(normalize(field)))
                .filter(candidate -> {
                    String candidateStem = dashboardFieldStem(candidate.field());
                    String candidateLabelStem = dashboardFieldStem(candidate.label());
                    return !stem.isBlank() && (candidateStem.equals(stem) || candidateLabelStem.equals(stem))
                            || !labelStem.isBlank()
                                    && (candidateStem.equals(labelStem) || candidateLabelStem.equals(labelStem));
                })
                .map(FieldCandidate::field)
                .findFirst()
                .orElse(field);
    }

    private String dashboardFieldStem(String value) {
        String normalized = searchableText(value);
        if (normalized.isBlank()) {
            return "";
        }
        List<String> semanticTokens = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (!Set.of("id", "ids", "in", "codigo", "code", "nome", "name", "label", "descricao", "description",
                    "text", "titulo", "title", "value", "values", "selected", "selection", "list").contains(token)) {
                semanticTokens.add(token);
            }
        }
        return String.join(" ", semanticTokens);
    }

    private DashboardDimension inferredDashboardDimension(
            String concept,
            String field,
            String label,
            String title,
            String provenance) {
        List<FieldCandidate> fields = List.of(new FieldCandidate(
                field,
                label,
                concept,
                true,
                false,
                false,
                true,
                false,
                false,
                false,
                provenance));
        return new DashboardDimension(
                concept,
                field,
                valueOrDefault(canonicalDashboardFilterField(field, label, fields), field),
                label,
                title,
                "bar",
                "vertical",
                "count",
                "",
                "Total",
                valueOrDefault(provenance, "dashboard-host-field-inference"));
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
        if (field.isBlank() || concept.isBlank()) {
            return false;
        }
        // The semantic concept is descriptive LLM-authored evidence and may legitimately be a
        // sentence. Only the candidate field shape is screened here; schema grounding performed
        // by the preview service remains the canonical confirmation of the field.
        return field.split("\\s+").length <= 3;
    }

    DashboardDimension unresolvedDashboardDimension() {
        return new DashboardDimension(
                "unresolved",
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
        return visualizationDecision == null || visualizationDecision.includeSummary();
    }

    private boolean includeDetailTable(AgenticAuthoringVisualizationDecision visualizationDecision) {
        return visualizationDecision == null
                || (visualizationDecision.includeDetailTable()
                && !excludesComponent(visualizationDecision, "praxis-table"));
    }

    private boolean includeNominalDetails(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        if (!includeDetailTable(visualizationDecision)) {
            return false;
        }
        JsonNode grounding = governedAnalyticsContext(request);
        if (!"comparison".equals(grounding.path("requestedOperation").asText(""))) {
            return true;
        }
        JsonNode availability = grounding.path("nominalOperationAvailability");
        if (!availability.isObject()) {
            return true;
        }
        JsonNode allowed = availability.path("allowed");
        return allowed.isBoolean() && allowed.asBoolean();
    }

    private boolean includeFilters(AgenticAuthoringVisualizationDecision visualizationDecision) {
        return visualizationDecision != null
                && (visualizationDecision.includeFilters()
                && !excludesComponent(visualizationDecision, "praxis-filter"));
    }

    private boolean includeKpis(AgenticAuthoringVisualizationDecision visualizationDecision) {
        return visualizationDecision == null
                || (visualizationDecision.includeKpis()
                && !excludesComponent(visualizationDecision, "praxis-rich-content"));
    }

    boolean excludesComponent(
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

    boolean hasLayoutKind(
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

    boolean hasVisualIntent(
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

    String valueOrDefault(String value, String fallback) {
        String safe = safe(value);
        return safe.isBlank() ? fallback : safe;
    }

    String firstNonBlank(String... values) {
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

    String defaultSchemaUrl(String resourcePath, String operation) {
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
        if (dimension != null
                && dimension.analyticsProjection() != null
                && dimension.analyticsProjection().isObject()) {
            String governedOperation = dimension.analyticsProjection()
                    .path("source")
                    .path("operation")
                    .asText("");
            if (!governedOperation.isBlank()) {
                return governedOperation;
            }
        }
        String normalizedPath = safe(resourcePath).toLowerCase(Locale.ROOT);
        if (normalizedPath.endsWith("/stats/timeseries")
                || "temporal".equalsIgnoreCase(safe(dimension == null ? "" : dimension.orientation()))) {
            return "timeseries";
        }
        return "group-by";
    }

    String businessResourcePath(String resourcePath) {
        String value = safe(resourcePath).replaceAll("/+$", "");
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

    String submitUrl(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return "";
        }
        if (isStatsPath(candidate.submitUrl()) || isStatsPath(candidate.resourcePath())) {
            return businessResourcePath(candidate.resourcePath()) + "/filter/cursor";
        }
        return safe(candidate.submitUrl());
    }

    String submitMethod(AgenticAuthoringCandidate candidate) {
        if (candidate != null && (isStatsPath(candidate.submitUrl()) || isStatsPath(candidate.resourcePath()))) {
            return "POST";
        }
        return candidate == null ? "" : safe(candidate.submitMethod());
    }

    String schemaUrl(AgenticAuthoringCandidate candidate) {
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
                || normalized.contains("/stats/distribution")
                || normalized.contains("/stats/comparison");
    }

    private JsonNode governedAnalyticsContext(AgenticAuthoringPlanRequest request) {
        if (request == null || request.contextHints() == null || !request.contextHints().isObject()) {
            return objectMapper.createObjectNode();
        }
        return request.contextHints().path("governedAnalytics");
    }

    private JsonNode governedRecordOpen(AgenticAuthoringPlanRequest request) {
        JsonNode grounding = governedAnalyticsContext(request);
        if (!"verified".equals(grounding.path("status").asText(""))
                || !grounding.path("recordOpenResolution").path("schemaVerified").asBoolean(false)) {
            return MissingNode.getInstance();
        }
        JsonNode recordOpen = grounding.path("projection").path("interactions").path("recordOpen");
        return recordOpen.isObject() ? recordOpen : MissingNode.getInstance();
    }

    private String slug(String value) {
        String normalized = Normalizer.normalize(safe(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return normalized.length() > 48 ? normalized.substring(0, 48).replaceAll("-$", "") : normalized;
    }

    String normalize(String value) {
        return Normalizer.normalize(safe(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    String canonicalFieldName(String value) {
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

    String jsonText(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || field == null || field.isBlank()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText("").trim() : "";
    }

    String safe(String value) {
        return value == null ? "" : value.trim();
    }

    record DashboardDimension(
            String concept,
            String field,
            String filterField,
            String label,
            String title,
            String chartType,
            String orientation,
            String metricAggregation,
            String metricField,
            String metricLabel,
            String provenance,
            JsonNode analyticsProjection) {

        DashboardDimension(
                String concept,
                String field,
                String filterField,
                String label,
                String title,
                String chartType,
                String orientation,
                String metricAggregation,
                String metricField,
                String metricLabel,
                String provenance) {
            this(
                    concept,
                    field,
                    filterField,
                    label,
                    title,
                    chartType,
                    orientation,
                    metricAggregation,
                    metricField,
                    metricLabel,
                    provenance,
                    null);
        }
    }

    record FieldCandidate(
            String field,
            String label,
            String concept,
            boolean categoricalHint,
            boolean optionSourceHint,
            boolean timeSeriesHint,
            boolean groupByHint,
            boolean metricHint,
            boolean avgEligible,
            boolean entityLookupHint,
            String provenance) {
    }

    private record ScoredFieldCandidate(
            FieldCandidate field,
            int score,
            int index) {
    }

}
