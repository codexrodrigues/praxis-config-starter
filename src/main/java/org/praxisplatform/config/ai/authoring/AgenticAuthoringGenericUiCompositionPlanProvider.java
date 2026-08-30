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
public class AgenticAuthoringGenericUiCompositionPlanProvider implements AgenticAuthoringUiCompositionPlanProvider {

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringChartCapabilityCatalog chartCapabilityCatalog =
            AgenticAuthoringChartCapabilityCatalog.INSTANCE;
    private static final Pattern FIELD_DECLARATION_PATTERN = Pattern.compile(
            "(?:field|fieldName|name|id|property|path|column|campo|coluna)\\s*[:=]\\s*['\\\"]?([A-Za-z_][A-Za-z0-9_.-]{1,80})",
            Pattern.CASE_INSENSITIVE);

    public AgenticAuthoringGenericUiCompositionPlanProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AgenticAuthoringUiCompositionPlanResult> plan(AgenticAuthoringPlanRequest request) {
        Optional<AgenticAuthoringUiCompositionPlanResult> tableExportModification =
                tableExportModification(request);
        if (tableExportModification.isPresent()) {
            return tableExportModification;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> tableColumnModification =
                tableColumnModification(request);
        if (tableColumnModification.isPresent()) {
            return tableColumnModification;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> chartModification = chartModification(request);
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
                && hasLayoutKind(visualizationDecision, "resource-master-detail");
        boolean crudRequested = !masterDetailRequested
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
        ObjectNode plan = masterDetailRequested ? pagePlan(request, candidate, visualizationDecision) : crudRequested ? crudPlan(request, candidate) : expansionRequested ? expansionPlan(candidate) : tabsRequested ? tabsPlan(request, candidate, visualizationDecision) : chartOnly ? singleChartPlan(request, candidate, visualizationDecision) : switch (artifactKind) {
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
        String providerArtifactKind = masterDetailRequested ? "page" : crudRequested ? "crud" : chartOnly ? "chart" : dashboardMaterialization ? "dashboard" : artifactKind;
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
        ObjectNode plan = basePlan("single-table-page");
        String tableKey = widgetKey(candidate, "table");
        addTable(plan.putArray("widgets"), candidate, tableKey, "main");
        applyGovernedQueryConstraints(plan, request);
        addSingleTableCanvas(plan, candidate, tableKey);
        addSingleTableDeviceLayouts(plan, tableKey);
        return plan;
    }

    private ObjectNode crudPlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate) {
        ObjectNode plan = basePlan("resource-crud");
        String crudKey = widgetKey(candidate, "crud");
        addCrud(plan.putArray("widgets"), candidate, crudKey);
        applyGovernedQueryConstraints(plan, request);
        addSingleTableCanvas(plan, candidate, crudKey);
        addSingleTableDeviceLayouts(plan, crudKey);
        return plan;
    }

    private void applyGovernedQueryConstraints(
            ObjectNode plan,
            AgenticAuthoringPlanRequest request) {
        AgenticAuthoringSemanticDecision decision = request == null || request.intentResolution() == null
                ? null
                : request.intentResolution().semanticDecision();
        JsonNode constraints = decision == null || decision.constraints() == null
                ? MissingNode.getInstance()
                : decision.constraints();
        if (!constraints.path("appliesToDataSelection").asBoolean(false)) {
            return;
        }
        JsonNode authoredFilters = constraints.path("filters");
        if (!authoredFilters.isArray() || authoredFilters.isEmpty()) {
            return;
        }
        for (JsonNode widget : plan.path("widgets")) {
            if (!(widget instanceof ObjectNode widgetObject)) {
                continue;
            }
            String componentId = widget.path("componentId").asText("");
            ObjectNode inputs = widgetObject.path("inputs") instanceof ObjectNode existingInputs
                    ? existingInputs
                    : widgetObject.putObject("inputs");
            ObjectNode queryContextHost;
            if ("praxis-table".equals(componentId)) {
                queryContextHost = inputs;
            } else if ("praxis-crud".equals(componentId)) {
                queryContextHost = inputs.path("metadata") instanceof ObjectNode existingMetadata
                        ? existingMetadata
                        : inputs.putObject("metadata");
            } else {
                continue;
            }
            ObjectNode queryContext = queryContextHost.path("queryContext") instanceof ObjectNode existingQueryContext
                    ? existingQueryContext
                    : queryContextHost.putObject("queryContext");
            ObjectNode filters = queryContext.path("filters") instanceof ObjectNode existingFilters
                    ? existingFilters
                    : queryContext.putObject("filters");
            for (JsonNode filter : authoredFilters) {
                String field = valueOrDefault(
                        filter.path("field").asText(""),
                        filter.path("concept").asText(""));
                JsonNode value = filter.path("value");
                if (!field.isBlank() && !value.isMissingNode() && !value.isNull()) {
                    filters.set(field, value.deepCopy());
                }
            }
            if (!filters.isEmpty()) {
                if ("praxis-table".equals(componentId)) {
                    ArrayNode bindingOrder = widgetObject.withArray("bindingOrder");
                    if (!containsText(bindingOrder, "queryContext")) {
                        bindingOrder.add("queryContext");
                    }
                }
                ObjectNode diagnostics = plan.path("diagnostics") instanceof ObjectNode existingDiagnostics
                        ? existingDiagnostics
                        : plan.putObject("diagnostics");
                // The provider only projects the AI-authored predicate. Canonical field
                // confirmation belongs to the preview boundary, which has access to the
                // governed /schemas/filtered contract. Do not claim materialization before
                // that evidence has been reconciled.
                diagnostics.put("queryConstraintsRequested", true);
            }
        }
    }

    private boolean containsText(ArrayNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText(""))) {
                return true;
            }
        }
        return false;
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
            addFilter(widgets, candidate, widgetKey(candidate, "filter"), renderableDimensions);
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
        if (shouldMaterializeProfilePage(visualizationDecision, candidate)) {
            return profilePagePlan(candidate);
        }
        if (isPrimaryComponent(visualizationDecision, "praxis-list")) {
            return listPagePlan(candidate);
        }
        ObjectNode plan = basePlan("master-detail-dashboard");
        ResourceWorkspaceGrounding grounding = resourceWorkspaceGrounding(request, candidate);
        ArrayNode widgets = plan.putArray("widgets");
        String filterKey = widgetKey(candidate, "filter");
        String masterKey = widgetKey(candidate, "master");
        String detailKey = widgetKey(candidate, "detail");
        if (grounding.filterOperationCount() > 0) {
            addWorkspaceFilter(widgets, candidate, filterKey);
        }
        addWorkspaceTable(widgets, candidate, masterKey, grounding);
        addDetail(widgets, candidate, detailKey);
        addMasterDetailStateAndBindings(plan, filterKey, masterKey, detailKey, grounding.filterOperationCount() > 0);
        addMasterDetailCanvas(plan, candidate, filterKey, masterKey, detailKey, grounding.filterOperationCount() > 0);
        addMasterDetailDeviceLayouts(plan, filterKey, masterKey, detailKey, grounding.filterOperationCount() > 0);
        addResourceWorkspaceGrounding(plan, grounding);
        return plan;
    }

    private void addMasterDetailStateAndBindings(
            ObjectNode plan,
            String filterKey,
            String masterKey,
            String detailKey,
            boolean includeFilter) {
        plan.putObject("state").putObject("values").putNull("selectedItem");
        ArrayNode bindings = plan.putArray("bindings");

        if (includeFilter) {
            ObjectNode filter = bindings.addObject();
            filter.put("id", filterKey + ".requestSearch->" + masterKey + ".queryContext");
            filter.put("intent", "data-projection");
            filter.putObject("from")
                    .put("kind", "component-port")
                    .put("widget", filterKey)
                    .put("port", "requestSearch")
                    .put("direction", "output");
            filter.putObject("to")
                    .put("kind", "component-port")
                    .put("widget", masterKey)
                    .put("port", "queryContext")
                    .put("direction", "input");
            filter.putObject("policy")
                    .put("distinct", true)
                    .put("missingValuePolicy", "skip");
            filter.putObject("metadata")
                    .put("source", "ui-composition-plan")
                    .put("traceKey", "verified-resource-filter-composition")
                    .putArray("tags")
                    .add("master-detail")
                    .add("filter");
        }

        ObjectNode selection = bindings.addObject();
        selection.put("id", masterKey + ".selectionChange->state.selectedItem");
        selection.put("intent", "state-write");
        selection.putObject("from")
                .put("kind", "component-port")
                .put("widget", masterKey)
                .put("port", "selectionChange")
                .put("direction", "output");
        selection.putObject("to")
                .put("kind", "state")
                .put("path", "selectedItem");
        selection.putObject("transform")
                .put("kind", "pick-path")
                .put("id", "pick-selected-row")
                .put("path", "payload.row");
        selection.putObject("policy")
                .put("distinct", true)
                .put("missingValuePolicy", "skip");
        selection.putObject("metadata")
                .put("source", "ui-composition-plan")
                .put("traceKey", "verified-component-port-composition")
                .putArray("tags")
                .add("master-detail")
                .add("selection-state");

        ObjectNode detail = bindings.addObject();
        detail.put("id", "state.selectedItem->" + detailKey + ".initialValue");
        detail.put("intent", "state-read");
        detail.putObject("from")
                .put("kind", "state")
                .put("path", "selectedItem");
        detail.putObject("to")
                .put("kind", "component-port")
                .put("widget", detailKey)
                .put("port", "initialValue")
                .put("direction", "input");
        detail.putObject("condition")
                .putArray("!!")
                .addObject()
                .put("var", "state.selectedItem");
        detail.putObject("policy")
                .put("distinct", true)
                .put("missingValuePolicy", "skip");
        detail.putObject("metadata")
                .put("source", "ui-composition-plan")
                .put("traceKey", "verified-component-port-composition")
                .putArray("tags")
                .add("master-detail")
                .add("detail-initial-value");
    }

    private void addMasterDetailCanvas(
            ObjectNode plan,
            AgenticAuthoringCandidate candidate,
            String filterKey,
            String masterKey,
            String detailKey,
            boolean includeFilter) {
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "80px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        ObjectNode items = canvas.putObject("items");
        if (includeFilter) {
            putCanvasItem(items, filterKey, 1, 1, 12, 2);
            putCanvasItem(items, masterKey, 1, 3, 7, 8);
            putCanvasItem(items, detailKey, 8, 3, 5, 8);
        } else {
            putCanvasItem(items, masterKey, 1, 1, 7, 8);
            putCanvasItem(items, detailKey, 8, 1, 5, 8);
        }

        ObjectNode options = plan.putObject("layoutPresetOptions");
        options.put("sourceResource", businessResourcePath(candidate.resourcePath()));
        options.put("density", "comfortable");
        options.put("responsiveStrategy", "canvas-device-layouts");
    }

    private void addMasterDetailDeviceLayouts(
            ObjectNode plan,
            String filterKey,
            String masterKey,
            String detailKey,
            boolean includeFilter) {
        ObjectNode deviceLayouts = plan.putObject("deviceLayouts");
        addStackedMasterDetailDeviceLayout(deviceLayouts.putObject("mobile"), 1, "88px", "12px", filterKey, masterKey, detailKey, includeFilter);
        addStackedMasterDetailDeviceLayout(deviceLayouts.putObject("tablet"), 6, "80px", "14px", filterKey, masterKey, detailKey, includeFilter);
    }

    private void addStackedMasterDetailDeviceLayout(
            ObjectNode variant,
            int columns,
            String rowUnit,
            String gap,
            String filterKey,
            String masterKey,
            String detailKey,
            boolean includeFilter) {
        ObjectNode canvas = variant.putObject("canvas");
        canvas.put("columns", columns);
        canvas.put("rowUnit", rowUnit);
        canvas.put("gap", gap);
        canvas.put("autoRows", "fixed");
        ObjectNode items = canvas.putObject("items");
        int masterRow = 1;
        if (includeFilter) {
            putCanvasItem(items, filterKey, 1, 1, columns, 2);
            masterRow = 3;
        }
        putCanvasItem(items, masterKey, 1, masterRow, columns, 7);
        putCanvasItem(items, detailKey, 1, masterRow + 7, columns, 8);
    }

    private void addSingleTableCanvas(ObjectNode plan, AgenticAuthoringCandidate candidate, String tableKey) {
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        putCanvasItem(canvas.putObject("items"), tableKey, 1, 1, 12, 7);

        ObjectNode options = plan.putObject("layoutPresetOptions");
        options.put("presetFamily", "single-table");
        options.put("sourceResource", businessResourcePath(candidate.resourcePath()));
        options.put("density", "comfortable");
        options.put("responsiveStrategy", "canvas-device-layouts");
    }

    private void addSingleTableDeviceLayouts(ObjectNode plan, String tableKey) {
        ObjectNode deviceLayouts = plan.putObject("deviceLayouts");
        addSingleTableDeviceLayout(deviceLayouts.putObject("mobile"), 1, "88px", "12px", tableKey);
        addSingleTableDeviceLayout(deviceLayouts.putObject("tablet"), 6, "80px", "14px", tableKey);
    }

    private void addSingleTableDeviceLayout(
            ObjectNode variant,
            int columns,
            String rowUnit,
            String gap,
            String tableKey) {
        ObjectNode canvas = variant.putObject("canvas");
        canvas.put("columns", columns);
        canvas.put("rowUnit", rowUnit);
        canvas.put("gap", gap);
        canvas.put("autoRows", "fixed");
        putCanvasItem(canvas.putObject("items"), tableKey, 1, 1, columns, 7);
    }

    private ObjectNode profilePagePlan(AgenticAuthoringCandidate candidate) {
        ObjectNode plan = basePlan("resource-profile-page");
        ArrayNode widgets = plan.putArray("widgets");
        String summaryKey = widgetKey(candidate, "profile-summary");
        String detailKey = widgetKey(candidate, "profile-detail");
        addProfileSummary(widgets, candidate, summaryKey);
        addDetail(widgets, candidate, detailKey);
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        ObjectNode items = canvas.putObject("items");
        putCanvasItem(items, summaryKey, 1, 1, 4, 8);
        putCanvasItem(items, detailKey, 5, 1, 8, 8);
        ObjectNode options = plan.putObject("layoutPresetOptions");
        options.put("presetFamily", "profile-detail");
        options.put("sourceResource", businessResourcePath(candidate.resourcePath()));
        options.put("density", "comfortable");
        options.put("responsiveStrategy", "canvas-device-layouts");
        ObjectNode slotAssignments = plan.putObject("slotAssignments");
        slotAssignments.put(summaryKey, "profile-summary");
        slotAssignments.put(detailKey, "profile-detail");
        ArrayNode grouping = plan.putArray("grouping");
        ObjectNode profileGroup = grouping.addObject();
        profileGroup.put("kind", "section");
        profileGroup.put("id", "profile-workspace");
        profileGroup.put("layout", "row");
        profileGroup.putArray("widgetKeys").add(summaryKey).add(detailKey);
        ObjectNode deviceLayouts = plan.putObject("deviceLayouts");
        addStackedProfileDeviceLayout(deviceLayouts.putObject("mobile"), 1, "88px", "12px", summaryKey, detailKey);
        addStackedProfileDeviceLayout(deviceLayouts.putObject("tablet"), 6, "80px", "14px", summaryKey, detailKey);
        return plan;
    }

    private void addStackedProfileDeviceLayout(
            ObjectNode variant,
            int columns,
            String rowUnit,
            String gap,
            String summaryKey,
            String detailKey) {
        ObjectNode canvas = variant.putObject("canvas");
        canvas.put("columns", columns);
        canvas.put("rowUnit", rowUnit);
        canvas.put("gap", gap);
        canvas.put("autoRows", "fixed");
        ObjectNode items = canvas.putObject("items");
        putCanvasItem(items, summaryKey, 1, 1, columns, 2);
        putCanvasItem(items, detailKey, 1, 3, columns, 7);
    }

    private ObjectNode listPagePlan(AgenticAuthoringCandidate candidate) {
        ObjectNode plan = basePlan("resource-list-page");
        ArrayNode widgets = plan.putArray("widgets");
        String listKey = widgetKey(candidate, "list");
        addList(widgets, candidate, listKey, "primary", List.of());
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        canvas.put("gap", "16px");
        putCanvasItem(canvas.putObject("items"), listKey, 1, 1, 12, 8);
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
        config.put("title", resourceTitle(candidate));
        config.putArray("columns");
    }

    private void addWorkspaceTable(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            ResourceWorkspaceGrounding grounding) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-table");
        widget.put("role", "master");
        widget.putArray("bindingOrder")
                .add("resourcePath")
                .add("tableId")
                .add("componentInstanceId")
                .add("config")
                .add("configPersistenceStrategy")
                .add("enableCustomization");
        widget.putObject("outputs").put("selectionChange", "emit");
        ObjectNode inputs = widget.putObject("inputs");
        String resourcePath = businessResourcePath(candidate.resourcePath());
        inputs.put("resourcePath", resourcePath);
        inputs.put("tableId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("configPersistenceStrategy", "input-first");
        inputs.put("enableCustomization", true);
        ObjectNode config = inputs.putObject("config");
        config.put("title", resourceTitle(candidate));
        config.putArray("columns");
        config.putObject("behavior")
                .putObject("selection")
                .put("enabled", true)
                .put("type", "single");
        ObjectNode actions = config.putObject("actions");
        actions.putObject("collection")
                .putObject("discovery")
                .put("enabled", grounding.hasCollectionCommands());
        ObjectNode row = actions.putObject("row");
        row.put("enabled", grounding.hasItemCommands());
        row.putObject("discovery")
                .put("enabled", grounding.hasItemCommands());
    }

    private void addWorkspaceFilter(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-filter");
        widget.put("role", "filter");
        widget.putObject("outputs").put("requestSearch", "emit");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", businessResourcePath(candidate.resourcePath()));
        inputs.put("filterId", key);
        inputs.put("formId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("enableCustomization", true);
        inputs.put("showFilterSettings", true);
        inputs.put("showSearchButton", true);
        inputs.put("persistenceKey", key);
        inputs.put("changeDebounceMs", 300);
    }

    private void addCrud(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-crud");
        widget.put("role", "main");
        widget.putArray("bindingOrder")
                .add("crudId")
                .add("componentInstanceId")
                .add("metadata")
                .add("enableCustomization");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("crudId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("enableCustomization", true);
        ObjectNode metadata = inputs.putObject("metadata");
        metadata.put("component", "praxis-crud");
        ObjectNode resource = metadata.putObject("resource");
        resource.put("path", businessResourcePath(candidate.resourcePath()));
        resource.put("idField", "id");
        resource.put("title", resourceTitle(candidate));
        ObjectNode table = metadata.putObject("table");
        table.put("title", resourceTitle(candidate));
        table.putArray("columns");
        metadata.putObject("defaults").put("openMode", "drawer");
    }

    private void addList(
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
        DashboardDimension primary = dimensions == null || dimensions.isEmpty() ? null : dimensions.get(0);
        String primaryField = primary == null ? "id" : canonicalFieldName(primary.field());
        templating.putObject("leading")
                .put("type", "icon")
                .put("expr", "subject");
        templating.putObject("primary")
                .put("type", "text")
                .put("expr", recordTitleExpression());
        putRecordSecondaryTemplate(templating, dimensions);
        templating.putObject("meta")
                .put("type", "text")
                .put("expr", recordMetaExpression());
        if (primary != null) {
            templating.putObject("trailing")
                    .put("type", "chip")
                    .put("expr", recordChipExpression(primaryField));
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
        actions.put("description", "Formulário governado para revisar ou executar operações disponíveis.");
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
        inputs.put("title", resourceTitle(candidate));
        inputs.putObject("config").putArray("columns");
    }

    private void addNestedOverview(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-rich-content");
        widget.put("childWidgetKey", key);
        ObjectNode document = richContentDocument(widget.putObject("inputs"));
        ObjectNode card = document.putArray("nodes").addObject();
        card.put("type", "card");
        card.put("title", resourceTitle(candidate));
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
                .put("title", "Detalhes de " + resourceTitle(candidate));
    }

    private void addNestedChart(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            DashboardDimension dimension) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-chart");
        widget.put("childWidgetKey", key);
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

    private void addChartOutputs(ObjectNode widget) {
        ObjectNode outputs = widget.putObject("outputs");
        outputs.put("pointClick", "emit");
        outputs.put("selectionChange", "emit");
        outputs.put("crossFilter", "emit");
    }

    private void populateChartDocument(
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
                + titleFromResourcePath(businessResourcePath(candidate.resourcePath())));
        document.put("subtitle", recordCount
                ? (timeseries ? "Evolucao de registros" : "Contagem de registros por " + dimension.label())
                : displayedMetric + " por " + dimension.label());
        document.putObject("sizing").put("mode", "fill-container").put("minHeight", 260);
        document.putArray("dimensions").addObject()
                .put("field", dimension.field())
                .put("label", dimension.label())
                .put("role", timeseries ? "time" : "category");
        document.putArray("metrics").addObject()
                .put("field", metricOutputField(dimension))
                .put("label", displayedMetric)
                .put("aggregation", canonicalAnalyticsAggregation(dimension.metricAggregation()));
        ObjectNode source = document.putObject("source");
        source.put("kind", "praxis.stats");
        source.put("resource", businessResourcePath(candidate.resourcePath()));
        source.put("operation", statsOperation);
        ObjectNode sourceOptions = source.putObject("options");
        if (timeseries) {
            sourceOptions.put("granularity", "month");
            sourceOptions.put("fillGaps", false);
        } else {
            sourceOptions.put("orderBy", "value-desc");
            sourceOptions.put("limit", 12);
        }
        document.put("legend", false);
        document.put("labels", true);
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
        statGroup.put("subtitle", titleFromResourcePath(businessResourcePath(candidate.resourcePath())));
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
            List<DashboardDimension> dimensions) {
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
        for (DashboardDimension dimension : dimensions.stream().filter(this::isResolvedDimension).toList()) {
            String filterField = valueOrDefault(dimension.filterField(), dimension.field());
            if (selectedFilterFields.add(filterField)) {
                selectedFields.add(filterField);
            }
        }
    }

    private void addSemanticAxisProvenance(
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
        policy.put("distinctBy", chartPointRawValuePath(dimension));
        policy.put("debounceMs", 250);
    }

    private void addSurfaceOpenDrilldownComposition(
            ObjectNode plan,
            String chartKey,
            AgenticAuthoringCandidate candidate,
            DashboardDimension dimension) {
        if (!allowsChartInteraction(dimension, "drillDown")) {
            return;
        }
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
        policy.put("distinctBy", chartPointRawValuePath(dimension));
        policy.put("debounceMs", 250);
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
        templating.putObject("leading")
                .put("type", "icon")
                .put("expr", "subject");
        templating.putObject("primary")
                .put("type", "text")
                .put("expr", recordTitleExpression());
        putRecordSecondaryTemplate(templating, dimensions);
        templating.putObject("meta")
                .put("type", "text")
                .put("expr", recordMetaExpression());
        templating.putObject("trailing")
                .put("type", "chip")
                .put("expr", recordChipExpression(canonicalFieldName(dimension.field())));
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
        return "${item.displayName ?? item.fullName ?? item.nomeCompleto ?? item.nome ?? item.name ?? item.title ?? item.label ?? item.descricao ?? item.description ?? item.id}";
    }

    private void putRecordSecondaryTemplate(ObjectNode templating, List<DashboardDimension> dimensions) {
        ObjectNode secondary = templating.putObject("secondary");
        secondary.put("type", "compose");
        ObjectNode compose = secondary.putObject("props").putObject("compose");
        compose.put("direction", "row");
        compose.put("wrap", true);
        compose.put("separator", " • ");
        ArrayNode items = compose.putArray("items");
        for (DashboardDimension dimension : dimensions == null ? List.<DashboardDimension>of() : dimensions) {
            if (!isResolvedDimension(dimension) || shouldRenderDimensionAsRecordChip(dimension.field())) {
                continue;
            }
            items.addObject()
                    .put("type", "text")
                    .put("expr", "${item." + canonicalFieldName(dimension.field()) + "}");
            if (items.size() >= 2) {
                break;
            }
        }
        items.addObject()
                .put("type", "text")
                .put("expr", "${item.description ?? item.descricao ?? item.category ?? item.categoria ?? item.type ?? item.tipo ?? item.group ?? item.grupo ?? item.segment ?? item.segmento}");
    }

    private String recordMetaExpression() {
        return "${item.createdAt ?? item.updatedAt ?? item.dataCriacao ?? item.dataAtualizacao ?? item.codigo ?? item.code ?? item.uuid}";
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

    private void addProfileSummary(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-rich-content");
        widget.put("role", "summary");
        ObjectNode document = richContentDocument(widget.putObject("inputs"));
        ObjectNode card = document.putArray("nodes").addObject();
        card.put("type", "card");
        card.put("title", resourceTitle(candidate));
        card.put("subtitle", "Ficha de perfil");
        card.put("variant", "filled");
        card.put("tone", "info");
        card.put("size", "sm");
        card.put("density", "comfortable");
        card.put("orientation", "horizontal");
        card.putObject("media")
                .put("kind", "icon")
                .put("icon", "badge")
                .put("placement", "leading");
        ArrayNode content = card.putArray("content");
        content.addObject()
                .put("type", "text")
                .put("text", "Resumo individual conectado a fonte governada selecionada. Use a ficha abaixo para consultar os campos confirmados do perfil.");
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
        inputs.putObject("config")
                .put("title", "Detalhes de " + resourceTitle(candidate));
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

    private Optional<AgenticAuthoringUiCompositionPlanResult> tableExportModification(
            AgenticAuthoringPlanRequest request) {
        if (!supportsTableExportModification(request)) {
            return Optional.empty();
        }
        ObjectNode page = chartActionPage(request);
        ObjectNode tableWidget = findWidget(page, targetWidgetKey(request));
        if (tableWidget == null) {
            tableWidget = findSingleWidgetByComponent(page, "praxis-table");
        }
        if (tableWidget == null || !"praxis-table".equals(widgetComponentId(tableWidget))) {
            return Optional.empty();
        }

        ObjectNode config = widgetInputs(tableWidget).with("config");
        ObjectNode selection = config.with("behavior").with("selection");
        selection.put("enabled", true);
        selection.put("type", "multiple");
        selection.put("mode", "checkbox");
        config.with("toolbar").put("visible", true);
        ObjectNode export = config.with("export");
        export.put("enabled", true);
        if (!export.path("formats").isArray() || export.path("formats").isEmpty()) {
            export.putArray("formats").add("csv");
        }
        export.with("general").put("scope", "selected");

        if (isUiCompositionPlan(page)) {
            return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                    true,
                    List.of(),
                    List.of("ui-composition-plan-provider:generic-table-export-selected"),
                    page,
                    emptyCompiledFormPatch()));
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:generic-table-export-selected"),
                null,
                compiledPagePatch(page, "modify-existing-table-export-selected")));
    }

    private boolean supportsTableExportModification(AgenticAuthoringPlanRequest request) {
        if (request == null || request.intentResolution() == null) {
            return false;
        }
        if (!isMaterializedPage(request.currentPage())
                && !isMaterializedPage(contextPreviewPage(request))
                && !isWidgetSnapshot(contextTargetWidgetSnapshot(request))) {
            return false;
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        String targetComponentId = intent.target() == null
                ? ""
                : valueOrDefault(intent.target().componentId(), "");
        boolean structurallyTableTarget = targetComponentId.isBlank()
                || "praxis-table".equals(targetComponentId)
                || "praxis-dynamic-page-builder".equals(targetComponentId);
        return "modify".equals(intent.operationKind())
                && "table".equals(intent.artifactKind())
                && "configure_export".equals(valueOrDefault(intent.changeKind(), ""))
                && structurallyTableTarget;
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> tableColumnModification(
            AgenticAuthoringPlanRequest request) {
        if (!supportsTableColumnAddition(request)) {
            return Optional.empty();
        }
        ObjectNode page = chartActionPage(request);
        ObjectNode tableWidget = findWidget(page, targetWidgetKey(request));
        if (tableWidget == null) {
            tableWidget = findSingleWidgetByComponent(page, "praxis-table");
        }
        if (tableWidget == null || !"praxis-table".equals(widgetComponentId(tableWidget))) {
            return Optional.empty();
        }
        TableSchemaField field = resolveTableColumnField(request, tableWidget);
        if (field == null) {
            return Optional.empty();
        }
        ArrayNode columns = widgetInputs(tableWidget).with("config").withArray("columns");
        ObjectNode column = columns.addObject();
        column.put("field", field.field());
        column.put("header", field.label());
        if (!field.type().isBlank()) {
            column.put("type", field.type());
        }
        if (isUiCompositionPlan(page)) {
            return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                    true,
                    List.of(),
                    List.of("ui-composition-plan-provider:generic-table-column-addition"),
                    page,
                    emptyCompiledFormPatch()));
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:generic-table-column-addition"),
                null,
                compiledPagePatch(page, "modify-existing-table-column-addition")));
    }

    private boolean supportsTableColumnAddition(AgenticAuthoringPlanRequest request) {
        if (request == null || request.intentResolution() == null) {
            return false;
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        if (!isMaterializedPage(request.currentPage())
                && !isMaterializedPage(contextPreviewPage(request))
                && !isWidgetSnapshot(contextTargetWidgetSnapshot(request))) {
            return false;
        }
        String targetComponentId = intent.target() == null
                ? ""
                : valueOrDefault(intent.target().componentId(), "");
        boolean structurallyTableTarget = targetComponentId.isBlank()
                || "praxis-table".equals(targetComponentId)
                || "praxis-dynamic-page-builder".equals(targetComponentId);
        return "modify".equals(intent.operationKind())
                && "table".equals(intent.artifactKind())
                && Set.of("column.add", "add_column").contains(valueOrDefault(intent.changeKind(), ""))
                && structurallyTableTarget;
    }

    private TableSchemaField resolveTableColumnField(
            AgenticAuthoringPlanRequest request,
            ObjectNode tableWidget) {
        JsonNode schemaFields = request.contextHints() == null
                ? MissingNode.getInstance()
                : request.contextHints().path("schemaFields");
        if (!schemaFields.isArray() || schemaFields.isEmpty()) {
            return null;
        }
        Set<String> existingFields = new LinkedHashSet<>();
        JsonNode columns = widgetInputs(tableWidget).path("config").path("columns");
        if (columns.isArray()) {
            for (JsonNode column : columns) {
                String field = canonicalFieldName(column.path("field").asText(""));
                if (!field.isBlank()) {
                    existingFields.add(normalize(field));
                }
            }
        }
        String prompt = normalizedPhrase(request.userPrompt());
        TableSchemaField selected = null;
        int selectedScore = 0;
        boolean ambiguous = false;
        for (JsonNode fieldNode : schemaFields) {
            String field = canonicalFieldName(firstNonBlank(
                    jsonText(fieldNode, "fieldName"),
                    jsonText(fieldNode, "field"),
                    jsonText(fieldNode, "name")));
            if (field.isBlank() || existingFields.contains(normalize(field))) {
                continue;
            }
            String label = firstNonBlank(jsonText(fieldNode, "label"), titleFromResourcePath(field));
            int score = tableColumnFieldMatchScore(prompt, field, label);
            if (score <= 0) {
                continue;
            }
            TableSchemaField candidate = new TableSchemaField(field, label, tableColumnType(fieldNode));
            if (score > selectedScore) {
                selected = candidate;
                selectedScore = score;
                ambiguous = false;
            } else if (score == selectedScore
                    && selected != null
                    && !selected.field().equals(candidate.field())) {
                ambiguous = true;
            }
        }
        return ambiguous ? null : selected;
    }

    private int tableColumnFieldMatchScore(String normalizedPrompt, String field, String label) {
        String normalizedLabel = normalizedPhrase(label);
        String normalizedField = normalizedPhrase(field);
        if (!normalizedLabel.isBlank() && containsPhrase(normalizedPrompt, normalizedLabel)) {
            return 4;
        }
        if (!normalizedField.isBlank() && containsPhrase(normalizedPrompt, normalizedField)) {
            return 3;
        }
        String compactPrompt = normalizedPrompt.replace(" ", "");
        String compactLabel = normalizedLabel.replace(" ", "");
        String compactField = normalizedField.replace(" ", "");
        if (compactLabel.length() >= 4 && compactPrompt.contains(compactLabel)) {
            return 2;
        }
        if (compactField.length() >= 4 && compactPrompt.contains(compactField)) {
            return 1;
        }
        return 0;
    }

    private String tableColumnType(JsonNode fieldNode) {
        String format = normalize(jsonText(fieldNode, "format"));
        if ("date".equals(format)) {
            return "date";
        }
        if ("date-time".equals(format) || "datetime".equals(format)) {
            return "datetime";
        }
        return switch (normalize(jsonText(fieldNode, "type"))) {
            case "integer", "number" -> "number";
            case "boolean" -> "boolean";
            case "string" -> "string";
            default -> "";
        };
    }

    private boolean isUiCompositionPlan(ObjectNode page) {
        return page != null && "praxis.ui-composition-plan".equals(page.path("kind").asText(""));
    }

    private String normalizedPhrase(String value) {
        return normalize(value).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private boolean containsPhrase(String text, String phrase) {
        return !(text == null || text.isBlank() || phrase == null || phrase.isBlank())
                && (" " + text + " ").contains(" " + phrase + " ");
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> chartModification(AgenticAuthoringPlanRequest request) {
        if (!supportsChartModification(request)) {
            return Optional.empty();
        }
        ObjectNode page = chartActionPage(request);
        ObjectNode chartWidget = findWidget(page, targetWidgetKey(request));
        if (chartWidget == null) {
            chartWidget = findSingleWidgetByComponent(page, "praxis-chart");
        }
        if (chartWidget == null || !"praxis-chart".equals(widgetComponentId(chartWidget))) {
            return Optional.empty();
        }
        ObjectNode chartDocument = widgetInputs(chartWidget).with("chartDocument");
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
                ? applyChartType(chartDocument, prompt, changeKind)
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
        ObjectNode events = widgetInputs(chartWidget)
                .with("chartDocument")
                .with("events");
        events.with("pointClick").put("action", "emit");
        events.with("selectionChange").put("action", "emit");
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

    private String targetWidgetKey(AgenticAuthoringPlanRequest request) {
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

    private boolean applyChartType(ObjectNode chartDocument, String prompt, String changeKind) {
        String type = chartCapabilityCatalog.resolveField("set_chart_type", prompt).orElse("");
        if (type.isBlank()) {
            type = chartCapabilityCatalog.resolveField(changeKind, prompt).orElse("");
        }
        if (type.isBlank()) {
            return false;
        }
        chartDocument.put("kind", type);
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
        JsonNode chartDocument = inputs.path("chartDocument");
        JsonNode source = chartDocument.path("source");
        String resourcePath = firstNonBlank(
                jsonText(source, "resource"),
                "/api/unknown/resource");
        String operation = valueOrDefault(jsonText(source, "operation"), "group-by");
        String submitMethod = "post";
        String submitUrl = resourcePath + "/stats/" + operation;
        String schemaUrl = defaultSchemaUrl(resourcePath, "post");
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
        JsonNode chartDocument = inputs.path("chartDocument");
        JsonNode dimension = chartDocument.path("dimensions").isArray()
                && !chartDocument.path("dimensions").isEmpty()
                ? chartDocument.path("dimensions").get(0)
                : null;
        JsonNode metric = chartDocument.path("metrics").isArray()
                && !chartDocument.path("metrics").isEmpty()
                ? chartDocument.path("metrics").get(0)
                : null;
        String field = firstNonBlank(
                canonicalFieldName(jsonText(dimension, "field")),
                "category");
        String label = firstNonBlank(
                jsonText(dimension, "label"),
                titleFromResourcePath(field));
        String chartType = firstNonBlank(
                jsonText(chartDocument, "kind"),
                "bar");
        String orientation = valueOrDefault(jsonText(chartDocument, "orientation"), "vertical");
        String metricField = firstNonBlank(
                canonicalFieldName(jsonText(metric, "field")),
                "");
        String metricAggregation = firstNonBlank(
                jsonText(metric, "aggregation"),
                "count");
        return new DashboardDimension(
                field,
                field,
                field,
                label,
                "Registros por " + label,
                chartType,
                orientation,
                metricAggregation,
                metricField,
                firstNonBlank(jsonText(metric, "label"), metricField.isBlank() ? "Total" : titleFromResourcePath(metricField)),
                "current-chart-widget-context");
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

    private String resourceTitle(AgenticAuthoringCandidate candidate) {
        return AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate);
    }

    private String titleFromResourcePath(String path) {
        String title = AgenticAuthoringResourcePresentationLabel.fromResourcePath(path);
        return "o recurso selecionado".equals(title) ? "Resource" : title;
    }

    private List<DashboardDimension> dashboardDimensions(
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
                String filterField = canonicalDashboardFilterField(field,
                        valueOrDefault(axis.label(), titleFromResourcePath(field)),
                        fieldCandidates);
                metricField = canonicalFieldName(metricField);
                if ("count".equals(normalize(metricAggregation))) {
                    metricField = "";
                }
                dimensions.add(new DashboardDimension(
                        safe(axis.concept()),
                        field,
                        filterField,
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
                        dashboardDimensionPriority(right, fieldCandidates),
                        dashboardDimensionPriority(left, fieldCandidates)))
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
                    || !"praxis-chart".equals(widgetComponentId(widgetObject))) {
                continue;
            }
            DashboardDimension dimension = dimensionFromChartWidget(widgetObject);
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

    private int dashboardDimensionPriority(DashboardDimension dimension, List<FieldCandidate> fieldCandidates) {
        if (dimension == null || dimension.field().isBlank()) {
            return -100;
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
        String normalizedField = searchableText(field.field());
        String normalizedLabel = searchableText(field.label());
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
        if (field.categoricalHint()) {
            score += 2;
        }
        if (isLikelyTechnicalOrMeasureField(field.field(), field.label())) {
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
                "salario", "salary", "count", "quantidade", "qtd")) {
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
        collectFieldCandidatesFromJson(contextHints, "context-hints", fields);
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
            Map<String, FieldCandidate> fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectFieldCandidatesFromJson(child, provenance, fields);
            }
            return;
        }
        if (!node.isObject()) {
            if (node.isTextual()) {
                collectFieldCandidatesFromText(node.asText(), provenance, fields);
            }
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
        boolean categoricalHint = containsAny(typeText,
                "enum", "select", "option", "categorical", "category", "dimension", "string", "text", "boolean");
        boolean optionSourceHint = containsAny(typeText, "select", "option")
                || node.has("optionSource")
                || node.has("options")
                || node.has("optionSourceUrl")
                || node.has("optionResourcePath");
        if (!safe(field).isBlank()) {
            addFieldCandidate(fields, field, label, categoricalHint, optionSourceHint, provenance);
        }
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (List.of("fields", "columns", "properties", "schemaFields", "filterableFields",
                    "resourceFields", "fieldCatalog", "fieldMetadata").contains(key)) {
                collectFieldCandidatesFromJson(value, provenance + ":" + key, fields);
            } else if (value.isObject() || value.isArray()) {
                collectFieldCandidatesFromJson(value, provenance, fields);
            }
        });
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
            addFieldCandidate(fields, matcher.group(1), "", containsAny(safeText,
                    "enum", "select", "option", "categorical", "category", "dimension", "filterable", "group-by"),
                    containsAny(safeText, "select", "option"), provenance);
        }
    }

    private void addFieldCandidate(
            Map<String, FieldCandidate> fields,
            String rawField,
            String rawLabel,
            boolean categoricalHint,
            boolean optionSourceHint,
            String provenance) {
        String field = canonicalFieldName(rawField);
        if (!isUsableFieldCandidate(field)) {
            return;
        }
        String key = normalize(field);
        FieldCandidate existing = fields.get(key);
        String label = valueOrDefault(rawLabel, titleFromResourcePath(field));
        FieldCandidate next = new FieldCandidate(
                field,
                label,
                normalize(label).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", ""),
                categoricalHint || existing != null && existing.categoricalHint(),
                optionSourceHint || existing != null && existing.optionSourceHint(),
                existing == null ? provenance : existing.provenance() + "," + provenance);
        fields.put(key, existing == null ? next : new FieldCandidate(
                existing.field(),
                valueOrDefault(existing.label(), next.label()),
                valueOrDefault(existing.concept(), next.concept()),
                existing.categoricalHint() || next.categoricalHint(),
                existing.optionSourceHint() || next.optionSourceHint(),
                next.provenance()));
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
        List<FieldCandidate> fields = List.of(new FieldCandidate(field, label, concept, true, false, provenance));
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

    private DashboardDimension unresolvedDashboardDimension() {
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

    private boolean shouldMaterializeProfilePage(
            AgenticAuthoringVisualizationDecision visualizationDecision,
            AgenticAuthoringCandidate candidate) {
        boolean pageBuilderSurface = visualizationDecision == null
                || isPrimaryComponent(visualizationDecision, "praxis-page-builder")
                || safe(visualizationDecision.primaryComponent()).isBlank();
        boolean profileIntent = visualizationDecision != null
                && (hasVisualIntent(visualizationDecision, "profile", "perfil", "ficha", "individual")
                || hasLayoutKind(visualizationDecision, "single-column", "single_column", "profile", "profile-page"));
        boolean profileEvidence = hasProfileGovernedEvidence(candidate);
        boolean explicitlyCollectionFirst = visualizationDecision != null
                && (isPrimaryComponent(visualizationDecision, "praxis-table")
                || isPrimaryComponent(visualizationDecision, "praxis-list")
                || isPrimaryComponent(visualizationDecision, "praxis-chart"));
        boolean explicitlyExcludedCollections = visualizationDecision != null
                && excludesComponent(visualizationDecision, "praxis-table")
                && excludesComponent(visualizationDecision, "praxis-list")
                && excludesComponent(visualizationDecision, "praxis-chart");
        return profileIntent
                && !explicitlyCollectionFirst
                && (pageBuilderSurface || explicitlyExcludedCollections)
                || profileEvidence
                && !explicitlyCollectionFirst
                && (pageBuilderSurface || explicitlyExcludedCollections);
    }

    private boolean hasProfileGovernedEvidence(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        List<String> fragments = new ArrayList<>();
        fragments.add(AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate));
        fragments.add(candidate.reason());
        fragments.addAll(candidate.evidence() == null ? List.of() : candidate.evidence());
        AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
        if (bundle != null && bundle.evidence() != null) {
            for (AgenticAuthoringEvidenceBundle.Evidence evidence : bundle.evidence()) {
                if (evidence == null) {
                    continue;
                }
                fragments.add(evidence.kind());
                fragments.add(evidence.ref());
                fragments.add(evidence.summary());
                fragments.addAll(evidence.matchedTerms());
            }
        }
        String evidenceText = normalize(String.join(" ", fragments)).replaceAll("[^a-z0-9]+", " ").trim();
        return containsWholeTerm(evidenceText, "perfil")
                || containsWholeTerm(evidenceText, "profile")
                || containsWholeTerm(evidenceText, "ficha");
    }

    private boolean containsWholeTerm(String normalizedText, String term) {
        String normalizedTerm = normalize(term).replaceAll("[^a-z0-9]+", " ").trim();
        if (normalizedText.isBlank() || normalizedTerm.isBlank()) {
            return false;
        }
        return (" " + normalizedText + " ").contains(" " + normalizedTerm + " ");
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

    private String businessResourcePath(String resourcePath) {
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

    private ResourceWorkspaceGrounding resourceWorkspaceGrounding(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate) {
        JsonNode envelope = request == null || request.contextHints() == null
                ? MissingNode.getInstance()
                : request.contextHints().path("verifiedDomainOperations");
        if (!envelope.isObject()) {
            return ResourceWorkspaceGrounding.unavailable("verified-domain-operations-missing");
        }
        String schemaVersion = safe(envelope.path("schemaVersion").asText());
        String source = safe(envelope.path("source").asText());
        if (!"praxis-agentic-authoring-verified-domain-operations.v2".equals(schemaVersion)
                || !"schemas.filtered+resource.capabilities+schemas.actions".equals(source)
                || !envelope.path("entries").isArray()) {
            return ResourceWorkspaceGrounding.rejected(
                    schemaVersion,
                    source,
                    "verified-domain-operations-envelope-untrusted");
        }
        JsonNode entries = envelope.path("entries");
        if (!envelope.path("operationCount").canConvertToInt()
                || envelope.path("operationCount").asInt() != entries.size()) {
            return ResourceWorkspaceGrounding.rejected(
                    schemaVersion,
                    source,
                    "verified-domain-operations-count-mismatch");
        }

        String selectedResourcePath = businessResourcePath(candidate == null ? "" : candidate.resourcePath());
        List<JsonNode> verifiedOperations = new ArrayList<>();
        List<JsonNode> filterOperations = new ArrayList<>();
        List<JsonNode> commandOperations = new ArrayList<>();
        String resourceKey = "";
        for (JsonNode entry : entries) {
            if (!isCompleteVerifiedOperation(entry)
                    || !selectedResourcePath.equals(businessResourcePath(entry.path("resourcePath").asText()))) {
                continue;
            }
            verifiedOperations.add(entry.deepCopy());
            if (resourceKey.isBlank()) {
                resourceKey = safe(entry.path("resourceKey").asText());
            }
            if (isCommandOperation(entry, selectedResourcePath)) {
                commandOperations.add(entry.deepCopy());
            }
            if (isFilterOperation(entry, selectedResourcePath)) {
                filterOperations.add(entry.deepCopy());
            }
        }
        if (verifiedOperations.isEmpty() || resourceKey.isBlank()) {
            return ResourceWorkspaceGrounding.rejected(
                    schemaVersion,
                    source,
                    "verified-domain-operations-resource-mismatch");
        }
        return new ResourceWorkspaceGrounding(
                "verified",
                schemaVersion,
                source,
                resourceKey,
                List.copyOf(verifiedOperations),
                List.copyOf(filterOperations),
                List.copyOf(commandOperations),
                commandOperations.isEmpty() ? "verified-command-operation-missing" : "");
    }

    private boolean isCompleteVerifiedOperation(JsonNode operation) {
        if (operation == null
                || !operation.isObject()
                || safe(operation.path("resourceKey").asText()).isBlank()
                || safe(operation.path("resourcePath").asText()).isBlank()
                || safe(operation.path("apiPath").asText()).isBlank()
                || safe(operation.path("apiMethod").asText()).isBlank()
                || safe(operation.path("schemaUrl").asText()).isBlank()
                || safe(operation.path("metadataUrl").asText()).isBlank()
                || safe(operation.path("operationId").asText()).isBlank()
                || !operation.path("availability").path("allowed").isBoolean()) {
            return false;
        }
        String kind = safe(operation.path("kind").asText());
        String verificationMode = safe(operation.path("verificationMode").asText());
        String availabilityResolution = safe(operation.path("availability").path("resolution").asText());
        if ("resource_operation".equals(kind)) {
            return "principal_capability".equals(verificationMode)
                    && operation.path("availability").path("allowed").asBoolean(false)
                    && "resource_capabilities".equals(availabilityResolution);
        }
        if (!"workflow_action".equals(kind)
                || !"runtime_action_discovery".equals(verificationMode)
                || safe(operation.path("actionId").asText()).isBlank()) {
            return false;
        }
        String scope = safe(operation.path("scope").asText()).toUpperCase(Locale.ROOT);
        if ("ITEM".equals(scope)) {
            return !operation.path("availability").path("allowed").asBoolean(true)
                    && "resource-context-required".equals(
                            safe(operation.path("availability").path("reason").asText()))
                    && "item_capabilities_at_selection".equals(availabilityResolution);
        }
        return "COLLECTION".equals(scope)
                && operation.path("availability").path("allowed").asBoolean(false)
                && "catalog_principal".equals(availabilityResolution);
    }

    private boolean isCommandOperation(JsonNode operation, String resourcePath) {
        String method = safe(operation.path("apiMethod").asText()).toLowerCase(Locale.ROOT);
        String apiPath = safe(operation.path("apiPath").asText());
        return "workflow_action".equals(safe(operation.path("kind").asText()))
                && !"get".equals(method)
                && apiPath.startsWith(resourcePath + "/")
                && apiPath.contains("/actions/");
    }

    private boolean isFilterOperation(JsonNode operation, String resourcePath) {
        String method = safe(operation.path("apiMethod").asText()).toLowerCase(Locale.ROOT);
        String apiPath = safe(operation.path("apiPath").asText());
        return "post".equals(method)
                && (apiPath.equals(resourcePath + "/filter")
                || apiPath.equals(resourcePath + "/filter/cursor"));
    }

    private void addResourceWorkspaceGrounding(
            ObjectNode plan,
            ResourceWorkspaceGrounding grounding) {
        ObjectNode diagnostics = plan.path("diagnostics") instanceof ObjectNode existing
                ? existing
                : plan.putObject("diagnostics");
        ObjectNode workspace = diagnostics.putObject("resourceWorkspaceGrounding");
        workspace.put("schemaVersion", "praxis-resource-workspace-grounding-diagnostics.v1");
        workspace.put("status", grounding.status());
        workspace.put("sourceSchemaVersion", grounding.sourceSchemaVersion());
        workspace.put("source", grounding.source());
        workspace.put("resourceKey", grounding.resourceKey());
        workspace.put("operationCount", grounding.operations().size());
        workspace.put("filterOperationCount", grounding.filterOperationCount());
        workspace.put("commandOperationCount", grounding.commandOperationCount());
        ObjectNode commandDiscovery = workspace.putObject("commandDiscovery");
        commandDiscovery.put("status", grounding.commandOperationCount() > 0 ? "enabled" : "blocked");
        commandDiscovery.put("source", "schemas-actions+runtime-hateoas-capabilities");
        commandDiscovery.put("scopeResolution", "schemas-actions-scope");
        commandDiscovery.put("availabilityResolution", "item-capabilities-at-selection");
        commandDiscovery.put("item", grounding.hasItemCommands());
        commandDiscovery.put("collection", grounding.hasCollectionCommands());
        commandDiscovery.put("endpointMaterializedByAuthoring", false);
        if (!grounding.failureCode().isBlank()) {
            workspace.put("failureCode", grounding.failureCode());
        }
        ArrayNode operations = workspace.putArray("operations");
        ArrayNode sourceRefs = plan.putArray("sourceRefs");
        addSourceRef(sourceRefs, "intent-resolution");
        for (JsonNode operation : grounding.operations()) {
            ObjectNode summary = operations.addObject();
            copyText(operation, summary, "conceptKey");
            copyText(operation, summary, "bindingKey");
            copyText(operation, summary, "kind");
            copyText(operation, summary, "apiPath");
            copyText(operation, summary, "apiMethod");
            copyText(operation, summary, "schemaUrl");
            copyText(operation, summary, "metadataUrl");
            copyText(operation, summary, "operationId");
            copyText(operation, summary, "actionId");
            copyText(operation, summary, "scope");
            copyText(operation, summary, "verificationMode");
            if (operation.path("availability").isObject()) {
                summary.set("availability", operation.path("availability").deepCopy());
            }
            copyText(operation, summary, "sourceRelease");
            summary.put("command", isCommandOperation(operation, businessResourcePath(operation.path("resourcePath").asText())));
            addSourceRef(sourceRefs, safe(operation.path("schemaUrl").asText()));
            addSourceRef(sourceRefs, safe(operation.path("metadataUrl").asText()));
            addSourceRef(sourceRefs, "metadata-operation:" + safe(operation.path("operationId").asText()));
        }
    }

    private void copyText(JsonNode source, ObjectNode target, String field) {
        String value = safe(source.path(field).asText());
        if (!value.isBlank()) {
            target.put(field, value);
        }
    }

    private void addSourceRef(ArrayNode sourceRefs, String value) {
        String normalized = safe(value);
        if (normalized.isBlank() || "capability-operation:".equals(normalized)) {
            return;
        }
        for (JsonNode existing : sourceRefs) {
            if (normalized.equals(existing.asText())) {
                return;
            }
        }
        sourceRefs.add(normalized);
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

        private DashboardDimension(
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

    private record FieldCandidate(
            String field,
            String label,
            String concept,
            boolean categoricalHint,
            boolean optionSourceHint,
            String provenance) {
    }

    private record TableSchemaField(String field, String label, String type) {
    }

    private record ScoredFieldCandidate(
            FieldCandidate field,
            int score,
            int index) {
    }

    private record ResourceWorkspaceGrounding(
            String status,
            String sourceSchemaVersion,
            String source,
            String resourceKey,
            List<JsonNode> operations,
            List<JsonNode> filterOperations,
            List<JsonNode> commandOperations,
            String failureCode) {

        private static ResourceWorkspaceGrounding unavailable(String failureCode) {
            return new ResourceWorkspaceGrounding(
                    "unavailable", "", "", "", List.of(), List.of(), List.of(), failureCode);
        }

        private static ResourceWorkspaceGrounding rejected(
                String sourceSchemaVersion,
                String source,
                String failureCode) {
            return new ResourceWorkspaceGrounding(
                    "rejected", sourceSchemaVersion, source, "", List.of(), List.of(), List.of(), failureCode);
        }

        private int filterOperationCount() {
            return filterOperations.size();
        }

        private int commandOperationCount() {
            return commandOperations.size();
        }

        private boolean hasItemCommands() {
            return commandOperations.stream()
                    .anyMatch(operation -> "ITEM".equals(commandScope(operation)));
        }

        private boolean hasCollectionCommands() {
            return commandOperations.stream()
                    .anyMatch(operation -> "COLLECTION".equals(commandScope(operation)));
        }

        private String commandScope(JsonNode operation) {
            String explicitScope = operation == null ? "" : operation.path("scope").asText("").trim();
            if ("ITEM".equalsIgnoreCase(explicitScope) || "COLLECTION".equalsIgnoreCase(explicitScope)) {
                return explicitScope.toUpperCase(Locale.ROOT);
            }
            String apiPath = operation == null ? "" : operation.path("apiPath").asText("");
            return apiPath.matches(".*/\\{[^/]+}/actions/.*") ? "ITEM" : "COLLECTION";
        }
    }
}
