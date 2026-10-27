package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringGenericUiCompositionPlanProvider.DashboardDimension;

/**
 * Package-private collaborator that materializes resource workspaces, master-detail pages,
 * action/approval workbenches, related-resource parent-child layouts, profile/list/tabs/expansion
 * pages, single-table/CRUD pages, and existing table/chart modifications for
 * {@link AgenticAuthoringGenericUiCompositionPlanProvider}.
 */
final class AgenticAuthoringWorkbenchAndRelatedResourcePlanSupport {

    private final AgenticAuthoringGenericUiCompositionPlanProvider provider;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringChartCapabilityCatalog chartCapabilityCatalog;

    AgenticAuthoringWorkbenchAndRelatedResourcePlanSupport(
            AgenticAuthoringGenericUiCompositionPlanProvider provider,
            ObjectMapper objectMapper,
            AgenticAuthoringChartCapabilityCatalog chartCapabilityCatalog) {
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.chartCapabilityCatalog = chartCapabilityCatalog;
    }

    ObjectNode tablePlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate) {
        ObjectNode plan = provider.basePlan("single-table-page");
        String tableKey = provider.widgetKey(candidate, "table");
        provider.addTable(plan.putArray("widgets"), candidate, tableKey, "main");
        applyGovernedQueryConstraints(plan, request);
        addSingleTableCanvas(plan, candidate, tableKey);
        addSingleTableDeviceLayouts(plan, tableKey);
        return plan;
    }

    ObjectNode crudPlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate) {
        ObjectNode plan = provider.basePlan("resource-crud");
        String crudKey = provider.widgetKey(candidate, "crud");
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
                String field = provider.valueOrDefault(
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

    ObjectNode pagePlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        if (shouldMaterializeProfilePage(visualizationDecision, candidate)) {
            return profilePagePlan(candidate);
        }
        if (provider.isPrimaryComponent(visualizationDecision, "praxis-list")) {
            return listPagePlan(candidate);
        }
        ObjectNode plan = provider.basePlan("master-detail-dashboard");
        ResourceWorkspaceGrounding grounding = resourceWorkspaceGrounding(request, candidate);
        boolean workbenchRequested = isActionWorkbenchRequested(visualizationDecision);
        JsonNode actionWorkbenchCommand = resolveActionWorkbenchCommand(request, visualizationDecision, grounding);
        JsonNode detailOperation = workspaceDetailOperation(grounding, candidate);
        ArrayNode widgets = plan.putArray("widgets");
        String filterKey = provider.widgetKey(candidate, "filter");
        String masterKey = provider.widgetKey(candidate, "master");
        String detailKey = provider.widgetKey(candidate, "detail");
        if (grounding.filterOperationCount() > 0) {
            addWorkspaceFilter(widgets, candidate, filterKey);
        }
        addWorkspaceTable(widgets, candidate, masterKey, grounding);
        if (actionWorkbenchCommand != null) {
            addActionWorkbenchDetail(widgets, candidate, detailKey, actionWorkbenchCommand);
        } else {
            addWorkspaceDetail(widgets, candidate, detailKey, detailOperation);
        }
        addMasterDetailStateAndBindings(
                plan,
                filterKey,
                masterKey,
                detailKey,
                grounding.filterOperationCount() > 0,
                true);
        addMasterDetailCanvas(plan, candidate, filterKey, masterKey, detailKey, grounding.filterOperationCount() > 0);
        addMasterDetailDeviceLayouts(plan, filterKey, masterKey, detailKey, grounding.filterOperationCount() > 0);
        addResourceWorkspaceGrounding(plan, grounding);
        if (actionWorkbenchCommand != null) {
            addActionWorkbenchGrounding(plan, actionWorkbenchCommand);
        } else if (workbenchRequested) {
            addDegradedActionWorkbenchGrounding(plan);
        }
        return plan;
    }

    Optional<AgenticAuthoringUiCompositionPlanResult> rejectUnverifiedMasterDetailGrounding(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate) {
        ResourceWorkspaceGrounding grounding = resourceWorkspaceGrounding(request, candidate);
        if (workspaceDetailOperation(grounding, candidate) != null) {
            return Optional.empty();
        }
        ObjectNode rejected = objectMapper.createObjectNode();
        addResourceWorkspaceGrounding(rejected, grounding);
        ObjectNode diagnostics = (ObjectNode) rejected.at("/diagnostics/resourceWorkspaceGrounding");
        diagnostics.put("status", "rejected");
        diagnostics.put(
                "failureCode",
                AgenticAuthoringSemanticMaterializationPolicy.RESOURCE_WORKSPACE_GROUNDING_REQUIRED_FAILURE);
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                false,
                List.of(AgenticAuthoringSemanticMaterializationPolicy.RESOURCE_WORKSPACE_GROUNDING_REQUIRED_FAILURE),
                List.of("ui-composition-plan-provider:resource-workspace-detail-fail-closed"),
                rejected,
                provider.emptyCompiledFormPatch()));
    }

    private JsonNode workspaceDetailOperation(
            ResourceWorkspaceGrounding grounding,
            AgenticAuthoringCandidate candidate) {
        if (grounding == null || !"verified".equals(grounding.status())) {
            return null;
        }
        String resourcePath = provider.businessResourcePath(candidate == null ? "" : candidate.resourcePath());
        List<JsonNode> details = grounding.operations().stream()
                .filter(operation -> "resource_operation".equals(provider.safe(operation.path("kind").asText())))
                .filter(operation -> "principal_capability".equals(provider.safe(operation.path("verificationMode").asText())))
                .filter(operation -> operation.path("availability").path("allowed").asBoolean(false)
                        && "resource_capabilities".equals(provider.safe(operation.path("availability").path("resolution").asText())))
                .filter(operation -> grounding.resourceKey().equals(provider.safe(operation.path("resourceKey").asText()))
                        && resourcePath.equals(provider.safe(operation.path("resourcePath").asText())))
                .filter(operation -> "get".equalsIgnoreCase(provider.safe(operation.path("apiMethod").asText()))
                        && "response".equals(provider.safe(operation.path("schemaType").asText())))
                .filter(operation -> Set.of("byid", "view", "read", "get")
                        .contains(provider.safe(operation.path("operationId").asText()).toLowerCase(Locale.ROOT)))
                .filter(operation -> provider.safe(operation.path("apiPath").asText())
                        .matches(java.util.regex.Pattern.quote(resourcePath) + "/\\{[^/{}]+\\}"))
                .toList();
        // Multiple verified item projections still need semantic disambiguation.
        return details.size() == 1 ? details.get(0) : null;
    }

    private boolean isActionWorkbenchRequested(AgenticAuthoringVisualizationDecision visualizationDecision) {
        if (visualizationDecision == null) {
            return false;
        }
        return provider.hasLayoutKind(
                        visualizationDecision,
                        "resource-action-workbench",
                        "approval-workbench",
                        "workflow-action-workbench")
                || provider.hasVisualIntent(
                        visualizationDecision,
                        "action-workbench",
                        "approval-workbench",
                        "workflow-workbench");
    }

    private JsonNode resolveActionWorkbenchCommand(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            ResourceWorkspaceGrounding grounding) {
        if (!isActionWorkbenchRequested(visualizationDecision)
                || grounding == null
                || grounding.commandOperations().isEmpty()) {
            return null;
        }
        String requestedAction = provider.safe(visualizationDecision.targetSurfaceId());
        if (!requestedAction.isBlank()) {
            for (JsonNode command : grounding.commandOperations()) {
                if (requestedAction.equalsIgnoreCase(provider.safe(command.path("actionId").asText()))
                        || requestedAction.equalsIgnoreCase(provider.safe(command.path("operationId").asText()))) {
                    return command;
                }
            }
        }
        String promptContext = (provider.safe(request == null ? "" : request.userPrompt()) + " "
                + provider.safe(visualizationDecision.intent())).toLowerCase(Locale.ROOT);
        JsonNode bestScored = null;
        int bestScore = 0;
        for (JsonNode command : grounding.commandOperations()) {
            int score = scoreActionWorkbenchCommand(command, promptContext);
            if (score > bestScore) {
                bestScore = score;
                bestScored = command;
            }
        }
        if (bestScored != null) {
            return bestScored;
        }
        for (JsonNode command : grounding.commandOperations()) {
            if ("ITEM".equalsIgnoreCase(provider.safe(command.path("scope").asText()))) {
                return command;
            }
        }
        return grounding.commandOperations().get(0);
    }

    private int scoreActionWorkbenchCommand(JsonNode command, String promptContext) {
        if (command == null || promptContext.isBlank()) {
            return 0;
        }
        int score = 0;
        String actionId = provider.safe(command.path("actionId").asText()).toLowerCase(Locale.ROOT);
        String operationId = provider.safe(command.path("operationId").asText()).toLowerCase(Locale.ROOT);
        if (!actionId.isBlank() && promptContext.contains(actionId)) {
            score += 4;
        }
        if (!operationId.isBlank() && promptContext.contains(operationId)) {
            score += 3;
        }
        if ("ITEM".equalsIgnoreCase(provider.safe(command.path("scope").asText()))) {
            score += 1;
        }
        return score > 1 ? score : 0;
    }

    private void addActionWorkbenchDetail(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            JsonNode command) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-dynamic-form");
        widget.put("role", "detail");
        ObjectNode inputs = widget.putObject("inputs");
        String resourcePath = provider.businessResourcePath(candidate.resourcePath());
        String actionId = provider.safe(command.path("actionId").asText());
        String operationId = provider.safe(command.path("operationId").asText());
        String apiPath = provider.safe(command.path("apiPath").asText());
        String apiMethod = provider.safe(command.path("apiMethod").asText()).toLowerCase(Locale.ROOT);
        String commandSchemaUrl = provider.safe(command.path("schemaUrl").asText());
        inputs.put("resourcePath", resourcePath);
        inputs.putNull("resourceId");
        inputs.put("schemaUrl", commandSchemaUrl);
        inputs.put("submitUrl", apiPath);
        inputs.put("submitMethod", apiMethod.isBlank() ? "post" : apiMethod);
        inputs.put("formId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("configPersistenceStrategy", "input-first");
        inputs.put("actionId", actionId);
        inputs.put("operationId", operationId);
        inputs.put("mode", "edit");
        inputs.put("schemaSource", "action");
        inputs.put("enableCustomization", true);
        ObjectNode emptyState = inputs.putObject("emptyState");
        emptyState.put("icon", "touch_app");
        emptyState.put("title", "Selecione um registro na tabela");
        emptyState.put("description", "Escolha um item na lista ao lado para preencher e executar esta ação.");
        ObjectNode config = inputs.putObject("config");
        config.put("title", "Executar ação (" + provider.valueOrDefault(actionId, operationId) + ") — " + provider.resourceTitle(candidate));
        ObjectNode actions = config.putObject("actions");
        actions.put("placement", "insideLastSection");
        actions.putObject("submit")
                .put("visible", true)
                .put("label", "Executar ação");
        actions.putObject("cancel")
                .put("visible", false);
        actions.putObject("reset")
                .put("visible", true)
                .put("label", "Limpar");
    }

    private void addActionWorkbenchGrounding(ObjectNode plan, JsonNode command) {
        ObjectNode diagnostics = plan.path("diagnostics") instanceof ObjectNode existing
                ? existing
                : plan.putObject("diagnostics");
        ObjectNode workbench = diagnostics.putObject("actionWorkbenchGrounding");
        workbench.put("schemaVersion", "praxis-action-workbench-grounding.v1");
        workbench.put("status", "verified");
        workbench.put("actionId", provider.safe(command.path("actionId").asText()));
        workbench.put("operationId", provider.safe(command.path("operationId").asText()));
        workbench.put("scope", provider.safe(command.path("scope").asText()));
        workbench.put("apiPath", provider.safe(command.path("apiPath").asText()));
        workbench.put("apiMethod", provider.safe(command.path("apiMethod").asText()));
        workbench.put("schemaUrl", provider.safe(command.path("schemaUrl").asText()));
        workbench.put("metadataUrl", provider.safe(command.path("metadataUrl").asText()));
    }

    private void addDegradedActionWorkbenchGrounding(ObjectNode plan) {
        ObjectNode diagnostics = plan.path("diagnostics") instanceof ObjectNode existing
                ? existing
                : plan.putObject("diagnostics");
        ObjectNode workbench = diagnostics.putObject("actionWorkbenchGrounding");
        workbench.put("schemaVersion", "praxis-action-workbench-grounding.v1");
        workbench.put("status", "degraded-to-master-detail");
        workbench.put("verificationGap", "missing-verified-workflow-action");
    }

    ObjectNode relatedResourcePagePlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        ObjectNode plan = provider.basePlan("master-detail-dashboard");
        ResourceWorkspaceGrounding grounding = resourceWorkspaceGrounding(request, candidate);
        String parentKey = provider.widgetKey(candidate, "parent");
        String relatedKey = provider.widgetKey(candidate, "related");
        ArrayNode widgets = plan.putArray("widgets");

        ObjectNode parent = widgets.addObject();
        parent.put("key", parentKey);
        parent.put("componentId", "praxis-table");
        parent.put("role", "master");
        parent.putObject("outputs").put("selectionChange", "emit");
        ObjectNode parentInputs = parent.putObject("inputs");
        parentInputs.put("resourcePath", provider.businessResourcePath(candidate.resourcePath()));
        parentInputs.put("tableId", parentKey);
        parentInputs.put("componentInstanceId", parentKey);
        parentInputs.put("configPersistenceStrategy", "input-first");
        parentInputs.put("enableCustomization", true);
        ObjectNode parentConfig = parentInputs.putObject("config");
        parentConfig.put("title", provider.resourceTitle(candidate));
        parentConfig.putArray("columns");
        parentConfig.putObject("behavior")
                .putObject("selection")
                .put("enabled", true)
                .put("type", "single")
                .put("mode", "row");
        ObjectNode parentActions = parentConfig.putObject("actions");
        parentActions.putObject("collection")
                .putObject("discovery")
                .put("enabled", grounding.hasCollectionCommands());
        ObjectNode parentRowActions = parentActions.putObject("row");
        parentRowActions.put("enabled", grounding.hasItemCommands());
        parentRowActions.putObject("discovery")
                .put("enabled", grounding.hasItemCommands());

        ObjectNode related = widgets.addObject();
        related.put("key", relatedKey);
        related.put("componentId", "praxis-related-resource-outlet");
        related.put("role", "detail");
        ObjectNode relatedInputs = related.putObject("inputs");
        relatedInputs.put("surfaceId", provider.safe(visualizationDecision.targetSurfaceId()));
        relatedInputs.put("parentResourcePath", provider.businessResourcePath(candidate.resourcePath()));
        relatedInputs.putNull("parentResourceId");
        relatedInputs.put("mode", "inline");
        relatedInputs.put("strictValidation", true);

        plan.putObject("state").putObject("values").putNull("selectedParentId");
        ArrayNode bindings = plan.putArray("bindings");
        ObjectNode selection = bindings.addObject();
        selection.put("id", parentKey + ".selectionChange->state.selectedParentId");
        selection.put("intent", "selection-sync");
        selection.putObject("from")
                .put("kind", "component-port")
                .put("widget", parentKey)
                .put("port", "selectionChange")
                .put("direction", "output");
        selection.putObject("to")
                .put("kind", "state")
                .put("path", "selectedParentId")
                .put("layer", "values");
        selection.putObject("transform")
                .put("kind", "pick-path")
                .put("id", "pick-related-parent-id")
                .put("path", "payload.row.id")
                .put("inputSource", "event");
        selection.putObject("policy")
                .put("distinct", true)
                .put("missingValuePolicy", "skip");

        ObjectNode context = bindings.addObject();
        context.put("id", "state.selectedParentId->" + relatedKey + ".parentResourceId");
        context.put("intent", "state-read");
        context.putObject("from")
                .put("kind", "state")
                .put("path", "selectedParentId")
                .put("layer", "values");
        context.putObject("to")
                .put("kind", "component-port")
                .put("widget", relatedKey)
                .put("port", "parentResourceId")
                .put("direction", "input");
        context.putObject("policy")
                .put("distinct", true)
                .put("missingValuePolicy", "skip");

        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "80px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        ObjectNode items = canvas.putObject("items");
        provider.putCanvasItem(items, parentKey, 1, 1, 7, 8);
        provider.putCanvasItem(items, relatedKey, 8, 1, 5, 8);
        addMasterDetailDeviceLayouts(plan, "", parentKey, relatedKey, false);

        addResourceWorkspaceGrounding(plan, grounding);
        ObjectNode planDiagnostics = plan.path("diagnostics") instanceof ObjectNode existingDiagnostics
                ? existingDiagnostics
                : plan.putObject("diagnostics");
        ObjectNode diagnostics = planDiagnostics.putObject("relatedResourceGrounding");
        diagnostics.put("schemaVersion", "praxis-related-resource-authoring-grounding.v1");
        diagnostics.put("status", "surface-id-selected-runtime-verification-required");
        diagnostics.put("parentResourcePath", provider.businessResourcePath(candidate.resourcePath()));
        diagnostics.put("surfaceId", provider.safe(visualizationDecision.targetSurfaceId()));
        diagnostics.put("relationshipAuthoredByComponent", false);
        return plan;
    }

    private void addMasterDetailStateAndBindings(
            ObjectNode plan,
            String filterKey,
            String masterKey,
            String detailKey,
            boolean includeFilter,
            boolean bindDetailResourceId) {
        plan.putObject("state")
                .putObject("values")
                .putNull("selectedItem")
                .putNull("selectedResourceId");
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
                .put("path", "payload.selectedRows.0")
                .put("inputSource", "event")
                .putNull("fallbackValue");
        selection.putObject("policy")
                .put("distinct", true)
                .put("missingValuePolicy", "propagate-undefined");
        selection.putObject("metadata")
                .put("source", "ui-composition-plan")
                .put("traceKey", "verified-component-port-composition")
                .putArray("tags")
                .add("master-detail")
                .add("selection-state");

        if (bindDetailResourceId) {
            ObjectNode identity = bindings.addObject();
            identity.put("id", masterKey + ".selectionChange->state.selectedResourceId");
            identity.put("intent", "state-write");
            identity.putObject("from")
                    .put("kind", "component-port")
                    .put("widget", masterKey)
                    .put("port", "selectionChange")
                    .put("direction", "output");
            identity.putObject("to")
                    .put("kind", "state")
                    .put("path", "selectedResourceId");
            identity.putObject("transform")
                    .put("kind", "pick-path")
                    .put("id", "pick-selected-resource-id")
                    .put("path", "payload.resourceIdentity.key.value")
                    .put("inputSource", "event")
                    .putNull("fallbackValue");
            identity.putObject("policy")
                    .put("distinct", true)
                    .put("missingValuePolicy", "propagate-undefined");
            identity.putObject("metadata")
                    .put("source", "ui-composition-plan")
                    .put("traceKey", "verified-component-port-composition")
                    .putArray("tags")
                    .add("master-detail")
                    .add("selection-resource-id");

            ObjectNode detail = bindings.addObject();
            detail.put("id", "state.selectedResourceId->" + detailKey + ".resourceId");
            detail.put("intent", "state-read");
            detail.putObject("from")
                    .put("kind", "state")
                    .put("path", "selectedResourceId");
            detail.putObject("to")
                    .put("kind", "component-port")
                    .put("widget", detailKey)
                    .put("port", "resourceId")
                    .put("direction", "input");
            detail.putObject("policy")
                    .put("distinct", true)
                    .put("missingValuePolicy", "propagate-undefined");
            detail.putObject("metadata")
                    .put("source", "ui-composition-plan")
                    .put("traceKey", "verified-component-port-composition")
                    .putArray("tags")
                    .add("master-detail")
                    .add("detail-resource-id");
        } else {
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
            provider.putCanvasItem(items, filterKey, 1, 1, 12, 1);
            provider.putCanvasItem(items, masterKey, 1, 2, 7, 8);
            provider.putCanvasItem(items, detailKey, 8, 2, 5, 8);
        } else {
            provider.putCanvasItem(items, masterKey, 1, 1, 7, 8);
            provider.putCanvasItem(items, detailKey, 8, 1, 5, 8);
        }

        ObjectNode options = plan.putObject("layoutPresetOptions");
        options.put("sourceResource", provider.businessResourcePath(candidate.resourcePath()));
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
            provider.putCanvasItem(items, filterKey, 1, 1, columns, 1);
            masterRow = 2;
        }
        provider.putCanvasItem(items, masterKey, 1, masterRow, columns, 7);
        provider.putCanvasItem(items, detailKey, 1, masterRow + 7, columns, 8);
    }

    private void addSingleTableCanvas(ObjectNode plan, AgenticAuthoringCandidate candidate, String tableKey) {
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        provider.putCanvasItem(canvas.putObject("items"), tableKey, 1, 1, 12, 7);

        ObjectNode options = plan.putObject("layoutPresetOptions");
        options.put("presetFamily", "single-table");
        options.put("sourceResource", provider.businessResourcePath(candidate.resourcePath()));
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
        provider.putCanvasItem(canvas.putObject("items"), tableKey, 1, 1, columns, 7);
    }

    private ObjectNode profilePagePlan(AgenticAuthoringCandidate candidate) {
        ObjectNode plan = provider.basePlan("resource-profile-page");
        ArrayNode widgets = plan.putArray("widgets");
        String summaryKey = provider.widgetKey(candidate, "profile-summary");
        String detailKey = provider.widgetKey(candidate, "profile-detail");
        addProfileSummary(widgets, candidate, summaryKey);
        addDetail(widgets, candidate, detailKey);
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        ObjectNode items = canvas.putObject("items");
        provider.putCanvasItem(items, summaryKey, 1, 1, 4, 8);
        provider.putCanvasItem(items, detailKey, 5, 1, 8, 8);
        ObjectNode options = plan.putObject("layoutPresetOptions");
        options.put("presetFamily", "profile-detail");
        options.put("sourceResource", provider.businessResourcePath(candidate.resourcePath()));
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
        provider.putCanvasItem(items, summaryKey, 1, 1, columns, 2);
        provider.putCanvasItem(items, detailKey, 1, 3, columns, 7);
    }

    private ObjectNode listPagePlan(AgenticAuthoringCandidate candidate) {
        ObjectNode plan = provider.basePlan("resource-list-page");
        ArrayNode widgets = plan.putArray("widgets");
        String listKey = provider.widgetKey(candidate, "list");
        provider.addList(widgets, candidate, listKey, "primary", List.of());
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        canvas.put("gap", "16px");
        provider.putCanvasItem(canvas.putObject("items"), listKey, 1, 1, 12, 8);
        return plan;
    }

    ObjectNode tabsPlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        ObjectNode plan = provider.basePlan("resource-tabs-page");
        ArrayNode widgets = plan.putArray("widgets");
        String tabsKey = provider.widgetKey(candidate, "tabs");
        addTabs(widgets, candidate, tabsKey, request, visualizationDecision);
        if (!tabsShouldIncludeChart(visualizationDecision)) {
            addTabsStateAndBindings(
                    plan,
                    tabsKey,
                    provider.widgetKey(candidate, "tabs-list"),
                    provider.widgetKey(candidate, "tabs-detail"));
        }
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        provider.putCanvasItem(canvas.putObject("items"), tabsKey, 1, 1, 12, 8);
        return plan;
    }

    private void addTabsStateAndBindings(
            ObjectNode plan,
            String tabsKey,
            String listKey,
            String detailKey) {
        plan.putObject("state").putObject("values").putNull("selectedItem");
        ArrayNode bindings = plan.putArray("bindings");

        ObjectNode selection = bindings.addObject();
        selection.put("id", tabsKey + "." + listKey + ".rowClick->state.selectedItem");
        selection.put("intent", "state-write");
        ObjectNode selectionFrom = selection.putObject("from");
        selectionFrom.put("kind", "component-port");
        selectionFrom.put("widget", tabsKey);
        selectionFrom.put("port", "rowClick");
        selectionFrom.put("direction", "output");
        addNestedWidgetPath(
                selectionFrom.putArray("nestedPath"),
                "list",
                0,
                listKey,
                "praxis-table");
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
                .put("traceKey", "verified-nested-component-port-composition")
                .putArray("tags")
                .add("tabs-nested")
                .add("selection-state");

        ObjectNode detail = bindings.addObject();
        detail.put("id", "state.selectedItem->" + tabsKey + "." + detailKey + ".resourceId");
        detail.put("intent", "state-read");
        detail.putObject("from")
                .put("kind", "state")
                .put("path", "selectedItem");
        ObjectNode detailTo = detail.putObject("to");
        detailTo.put("kind", "component-port");
        detailTo.put("widget", tabsKey);
        detailTo.put("port", "resourceId");
        detailTo.put("direction", "input");
        addNestedWidgetPath(
                detailTo.putArray("nestedPath"),
                "details",
                1,
                detailKey,
                "praxis-dynamic-form");
        detail.putObject("condition")
                .putArray("!!")
                .addObject()
                .put("var", "state.selectedItem");
        detail.putObject("transform")
                .put("kind", "pick-path")
                .put("id", "selected-item-resource-id")
                .put("inputSource", "state")
                .put("path", "selectedItem.id");
        detail.putObject("policy")
                .put("distinct", true)
                .put("missingValuePolicy", "skip");
        detail.putObject("metadata")
                .put("source", "ui-composition-plan")
                .put("traceKey", "verified-nested-component-port-composition")
                .putArray("tags")
                .add("tabs-nested")
                .add("detail-resource-id");
    }

    ObjectNode expansionPlan(AgenticAuthoringCandidate candidate) {
        ObjectNode plan = provider.basePlan("resource-expansion-page");
        ArrayNode widgets = plan.putArray("widgets");
        String expansionKey = provider.widgetKey(candidate, "expansion");
        addExpansion(widgets, candidate, expansionKey);
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "72px");
        provider.putCanvasItem(canvas.putObject("items"), expansionKey, 1, 1, 12, 8);
        return plan;
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
        String resourcePath = provider.businessResourcePath(candidate.resourcePath());
        inputs.put("resourcePath", resourcePath);
        inputs.put("tableId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("configPersistenceStrategy", "input-first");
        inputs.put("enableCustomization", true);
        ObjectNode config = inputs.putObject("config");
        config.put("title", provider.resourceTitle(candidate));
        config.putArray("columns");
        config.putObject("behavior")
                .putObject("selection")
                .put("enabled", true)
                .put("type", "single")
                .put("mode", "row");
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
        inputs.put("resourcePath", provider.businessResourcePath(candidate.resourcePath()));
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
        resource.put("path", provider.businessResourcePath(candidate.resourcePath()));
        resource.put("idField", "id");
        resource.put("title", provider.resourceTitle(candidate));
        ObjectNode table = metadata.putObject("table");
        table.put("title", provider.resourceTitle(candidate));
        table.putArray("columns");
        metadata.putObject("defaults").put("openMode", "drawer");
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
            List<DashboardDimension> dimensions = provider.dashboardDimensions(visualizationDecision, candidate, request);
            DashboardDimension dimension = dimensions.isEmpty() ? provider.unresolvedDashboardDimension() : dimensions.get(0);
            ObjectNode chartTab = tabs.addObject();
            chartTab.put("id", "chart");
            chartTab.put("textLabel", "Grafico");
            addNestedChart(chartTab.putArray("widgets"), candidate, provider.widgetKey(candidate, "tabs-chart-" + dimension.field()), dimension);
            provider.addSemanticAxisProvenance(widget, visualizationDecision, List.of(dimension));
        } else {
            ObjectNode listTab = tabs.addObject();
            listTab.put("id", "list");
            listTab.put("textLabel", "Lista");
            addNestedTable(listTab.putArray("widgets"), candidate, provider.widgetKey(candidate, "tabs-list"));
        }
        ObjectNode detailsTab = tabs.addObject();
        detailsTab.put("id", "details");
        detailsTab.put("textLabel", "Detalhes");
        if (tabsShouldIncludeChart(visualizationDecision)) {
            addNestedTable(detailsTab.putArray("widgets"), candidate, provider.widgetKey(candidate, "tabs-detail-table"));
        } else {
            addNestedDetail(detailsTab.putArray("widgets"), candidate, provider.widgetKey(candidate, "tabs-detail"));
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
        addNestedOverview(overview.putArray("widgets"), candidate, provider.widgetKey(candidate, "expansion-overview"));

        ObjectNode details = panels.addObject();
        details.put("id", "details");
        details.put("title", "Detalhes");
        details.put("description", "Registros conectados ao recurso governado.");
        details.put("icon", "table_view");
        addNestedTable(details.putArray("widgets"), candidate, provider.widgetKey(candidate, "expansion-details"));

        ObjectNode actions = panels.addObject();
        actions.put("id", "actions");
        actions.put("title", "Acoes");
        actions.put("description", "Formulário governado para revisar ou executar operações disponíveis.");
        actions.put("icon", "dynamic_form");
        addNestedDetail(actions.putArray("widgets"), candidate, provider.widgetKey(candidate, "expansion-actions"));
    }

    private boolean tabsShouldIncludeChart(AgenticAuthoringVisualizationDecision visualizationDecision) {
        if (provider.excludesComponent(visualizationDecision, "praxis-chart")) {
            return false;
        }
        return visualizationDecision != null
                && visualizationDecision.axes() != null
                && !visualizationDecision.axes().isEmpty()
                && (provider.hasLayoutKind(visualizationDecision, "tabs-with-chart", "chart-tabs", "analytical-tabs")
                || provider.hasVisualIntent(visualizationDecision, "chart", "charts", "grafico", "graficos", "analytical"));
    }

    private void addNestedTable(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-table");
        widget.put("childWidgetKey", key);
        ObjectNode outputs = widget.putObject("outputs");
        outputs.put("rowClick", "emit");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", provider.businessResourcePath(candidate.resourcePath()));
        inputs.put("tableId", key);
        inputs.put("title", provider.resourceTitle(candidate));
        inputs.putObject("config").putArray("columns");
    }

    private void addNestedOverview(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-rich-content");
        widget.put("childWidgetKey", key);
        ObjectNode document = provider.richContentDocument(widget.putObject("inputs"));
        ObjectNode card = document.putArray("nodes").addObject();
        card.put("type", "card");
        card.put("title", provider.resourceTitle(candidate));
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
        inputs.put("resourcePath", provider.businessResourcePath(candidate.resourcePath()));
        inputs.put("formId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("mode", "view");
        inputs.put("schemaSource", "resource");
        inputs.put("enableCustomization", true);
        inputs.putNull("resourceId");
        inputs.putObject("config")
                .put("title", "Detalhes de " + provider.resourceTitle(candidate));
    }

    private void addNestedWidgetPath(
            ArrayNode nestedPath,
            String tabId,
            int tabIndex,
            String widgetKey,
            String componentType) {
        nestedPath.addObject()
                .put("kind", "tab")
                .put("id", tabId)
                .put("index", tabIndex);
        nestedPath.addObject()
                .put("kind", "widget")
                .put("key", widgetKey)
                .put("componentType", componentType);
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
        provider.addChartOutputs(widget);
        ObjectNode inputs = widget.putObject("inputs");
        inputs.putObject("queryContext").putObject("filters");
        inputs.put("enableCustomization", true);
        provider.populateChartDocument(inputs.putObject("chartDocument"), candidate, key, dimension);
    }

    private void addProfileSummary(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-rich-content");
        widget.put("role", "summary");
        ObjectNode document = provider.richContentDocument(widget.putObject("inputs"));
        ObjectNode card = document.putArray("nodes").addObject();
        card.put("type", "card");
        card.put("title", provider.resourceTitle(candidate));
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

    private void addDetail(ArrayNode widgets, AgenticAuthoringCandidate candidate, String key) {
        addWorkspaceDetail(widgets, candidate, key, null);
    }

    private void addWorkspaceDetail(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String key,
            JsonNode detailOperation) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        widget.put("componentId", "praxis-dynamic-form");
        widget.put("role", "detail");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", provider.businessResourcePath(candidate.resourcePath()));
        inputs.putNull("resourceId");
        if (detailOperation != null) {
            inputs.put("schemaUrl", provider.safe(detailOperation.path("schemaUrl").asText()));
        } else {
            inputs.put("schemaUrl", provider.schemaUrl(candidate));
            inputs.put("submitUrl", provider.submitUrl(candidate));
            inputs.put("submitMethod", provider.submitMethod(candidate));
        }
        inputs.put("formId", key);
        inputs.put("componentInstanceId", key);
        inputs.put("configPersistenceStrategy", "input-first");
        inputs.put("mode", "view");
        inputs.put("schemaSource", "resource");
        inputs.put("enableCustomization", true);
        ObjectNode emptyState = inputs.putObject("emptyState");
        emptyState.put("icon", "touch_app");
        emptyState.put("title", "Selecione um registro na tabela");
        emptyState.put("description", "Escolha um item na lista ao lado para visualizar seus detalhes.");
        inputs.putObject("config")
                .put("title", "Detalhes de " + provider.resourceTitle(candidate));
    }

    Optional<AgenticAuthoringUiCompositionPlanResult> tableExportModification(
            AgenticAuthoringPlanRequest request,
            JsonNode persistedUiCompositionPlan) {
        if (!supportsTableExportModification(request, persistedUiCompositionPlan)) {
            return Optional.empty();
        }
        ObjectNode page = chartActionPage(request, persistedUiCompositionPlan);
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
                    provider.emptyCompiledFormPatch()));
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:generic-table-export-selected"),
                null,
                compiledPagePatch(page, "modify-existing-table-export-selected")));
    }

    private boolean supportsTableExportModification(
            AgenticAuthoringPlanRequest request,
            JsonNode persistedUiCompositionPlan) {
        if (request == null || request.intentResolution() == null) {
            return false;
        }
        if (!isUiCompositionPlan(persistedUiCompositionPlan)
                && !provider.isMaterializedPage(request.currentPage())
                && !provider.isMaterializedPage(provider.contextPreviewPage(request))
                && !isWidgetSnapshot(contextTargetWidgetSnapshot(request))) {
            return false;
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        String targetComponentId = intent.target() == null
                ? ""
                : provider.valueOrDefault(intent.target().componentId(), "");
        boolean structurallyTableTarget = targetComponentId.isBlank()
                || "praxis-table".equals(targetComponentId)
                || "praxis-dynamic-page-builder".equals(targetComponentId);
        return "modify".equals(intent.operationKind())
                && "table".equals(intent.artifactKind())
                && "configure_export".equals(provider.valueOrDefault(intent.changeKind(), ""))
                && structurallyTableTarget;
    }

    Optional<AgenticAuthoringUiCompositionPlanResult> tableColumnModification(
            AgenticAuthoringPlanRequest request,
            JsonNode persistedUiCompositionPlan) {
        if (!supportsTableColumnAddition(request, persistedUiCompositionPlan)) {
            return Optional.empty();
        }
        ObjectNode page = chartActionPage(request, persistedUiCompositionPlan);
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
                    provider.emptyCompiledFormPatch()));
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:generic-table-column-addition"),
                null,
                compiledPagePatch(page, "modify-existing-table-column-addition")));
    }

    private boolean supportsTableColumnAddition(
            AgenticAuthoringPlanRequest request,
            JsonNode persistedUiCompositionPlan) {
        if (request == null || request.intentResolution() == null) {
            return false;
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        if (!isUiCompositionPlan(persistedUiCompositionPlan)
                && !provider.isMaterializedPage(request.currentPage())
                && !provider.isMaterializedPage(provider.contextPreviewPage(request))
                && !isWidgetSnapshot(contextTargetWidgetSnapshot(request))) {
            return false;
        }
        String targetComponentId = intent.target() == null
                ? ""
                : provider.valueOrDefault(intent.target().componentId(), "");
        boolean structurallyTableTarget = targetComponentId.isBlank()
                || "praxis-table".equals(targetComponentId)
                || "praxis-dynamic-page-builder".equals(targetComponentId);
        return "modify".equals(intent.operationKind())
                && "table".equals(intent.artifactKind())
                && Set.of("column.add", "add_column").contains(provider.valueOrDefault(intent.changeKind(), ""))
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
                String field = provider.canonicalFieldName(column.path("field").asText(""));
                if (!field.isBlank()) {
                    existingFields.add(provider.normalize(field));
                }
            }
        }
        String prompt = normalizedPhrase(request.userPrompt());
        TableSchemaField selected = null;
        int selectedScore = 0;
        boolean ambiguous = false;
        for (JsonNode fieldNode : schemaFields) {
            String field = provider.canonicalFieldName(provider.firstNonBlank(
                    provider.jsonText(fieldNode, "fieldName"),
                    provider.jsonText(fieldNode, "field"),
                    provider.jsonText(fieldNode, "name")));
            if (field.isBlank() || existingFields.contains(provider.normalize(field))) {
                continue;
            }
            String label = provider.firstNonBlank(provider.jsonText(fieldNode, "label"), provider.titleFromResourcePath(field));
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
        String format = provider.normalize(provider.jsonText(fieldNode, "format"));
        if ("date".equals(format)) {
            return "date";
        }
        if ("date-time".equals(format) || "datetime".equals(format)) {
            return "datetime";
        }
        return switch (provider.normalize(provider.jsonText(fieldNode, "type"))) {
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
        return provider.normalize(value).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private boolean containsPhrase(String text, String phrase) {
        return !(text == null || text.isBlank() || phrase == null || phrase.isBlank())
                && (" " + text + " ").contains(" " + phrase + " ");
    }

    Optional<AgenticAuthoringUiCompositionPlanResult> chartModification(
            AgenticAuthoringPlanRequest request,
            JsonNode persistedUiCompositionPlan) {
        if (!supportsChartModification(request, persistedUiCompositionPlan)) {
            return Optional.empty();
        }
        ObjectNode page = chartActionPage(request, persistedUiCompositionPlan);
        ObjectNode chartWidget = findWidget(page, targetWidgetKey(request));
        if (chartWidget == null) {
            chartWidget = findSingleWidgetByComponent(page, "praxis-chart");
        }
        if (chartWidget == null || !"praxis-chart".equals(widgetComponentId(chartWidget))) {
            return Optional.empty();
        }
        ObjectNode chartDocument = widgetInputs(chartWidget).with("chartDocument");
        if (provider.isSurfaceOpenModalDrilldown(request)) {
            AgenticAuthoringCandidate candidate = candidateFromChartWidget(chartWidget);
            DashboardDimension dimension = dimensionFromChartWidget(chartWidget);
            String chartKey = widgetKeyFromWidget(chartWidget, candidate, dimension);
            enableChartDrilldownInteraction(chartWidget);
            if (isUiCompositionPlan(page)) {
                provider.addSurfaceOpenDrilldownBinding(page, chartKey, candidate, dimension);
                return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:generic-chart-surface-open-modification"),
                        page,
                        provider.emptyCompiledFormPatch()));
            }
            enableChartSurfaceOpenOutput(chartWidget, candidate, dimension);
            provider.addSurfaceOpenDrilldownComposition(page, chartKey, candidate, dimension);
            return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                    true,
                    List.of(),
                    List.of("ui-composition-plan-provider:generic-chart-surface-open-modification"),
                    null,
                    compiledPagePatch(page, "modify-existing-chart-surface-open")));
        }
        String prompt = provider.normalize(request.userPrompt());
        String changeKind = provider.valueOrDefault(request.intentResolution().changeKind(), "");
        boolean changed = "set_chart_type".equals(changeKind) || chartCapabilityCatalog.supports(changeKind, prompt)
                ? applyChartType(chartDocument, prompt, changeKind)
                : false;
        if (!changed) {
            return Optional.empty();
        }
        if (isUiCompositionPlan(page)) {
            return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                    true,
                    List.of(),
                    List.of("ui-composition-plan-provider:generic-chart-modification"),
                    page,
                    provider.emptyCompiledFormPatch()));
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
        pointClick.set("params", provider.surfaceOpenTablePayload(candidate, dimension));
        outputs.put("selectionChange", "emit");
    }

    private boolean supportsChartModification(
            AgenticAuthoringPlanRequest request,
            JsonNode persistedUiCompositionPlan) {
        if (request == null
                || request.intentResolution() == null) {
            return false;
        }
        if (!isUiCompositionPlan(persistedUiCompositionPlan)
                && !provider.isMaterializedPage(request.currentPage())
                && !provider.isMaterializedPage(provider.contextPreviewPage(request))
                && !isWidgetSnapshot(contextTargetWidgetSnapshot(request))) {
            return false;
        }
        String prompt = provider.normalize(request.userPrompt());
        String changeKind = provider.valueOrDefault(request.intentResolution().changeKind(), "");
        String artifactKind = provider.valueOrDefault(request.intentResolution().artifactKind(), "");
        String targetComponentId = request.intentResolution().target() == null
                ? ""
                : provider.valueOrDefault(request.intentResolution().target().componentId(), "");
        boolean structurallyChartTarget = targetComponentId.isBlank()
                || "praxis-chart".equals(targetComponentId)
                || "praxis-dynamic-page-builder".equals(targetComponentId);
        return "modify".equals(request.intentResolution().operationKind())
                && ("dashboard".equals(artifactKind) || "chart".equals(artifactKind))
                && structurallyChartTarget
                && ("set_chart_type".equals(changeKind)
                || chartCapabilityCatalog.supports(changeKind, prompt));
    }

    private ObjectNode chartActionPage(
            AgenticAuthoringPlanRequest request,
            JsonNode persistedUiCompositionPlan) {
        if (isUiCompositionPlan(persistedUiCompositionPlan) && persistedUiCompositionPlan.isObject()) {
            return persistedUiCompositionPlan.deepCopy();
        }
        JsonNode currentPage = request.currentPage();
        if (provider.isMaterializedPage(currentPage) && currentPage.isObject()) {
            return currentPage.deepCopy();
        }
        JsonNode previewPage = provider.contextPreviewPage(request);
        if (provider.isMaterializedPage(previewPage) && previewPage.isObject()) {
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

    private boolean isUiCompositionPlan(JsonNode candidate) {
        return candidate != null
                && candidate.isObject()
                && "praxis.ui-composition-plan".equals(candidate.path("kind").asText(""))
                && "1.0".equals(candidate.path("version").asText(""));
    }

    private JsonNode contextTargetWidgetSnapshot(AgenticAuthoringPlanRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        if (contextHints == null || contextHints.isMissingNode() || contextHints.isNull()) {
            return null;
        }
        return contextHints.path("targetWidgetSnapshot");
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
        String targetWidgetKey = target == null ? "" : provider.valueOrDefault(target.widgetKey(), "");
        if (!targetWidgetKey.isBlank()) {
            return targetWidgetKey;
        }
        JsonNode contextHints = request.contextHints();
        return provider.valueOrDefault(provider.jsonText(contextHints, "targetWidgetKey"),
                provider.jsonText(contextHints, "selectedWidgetKey"));
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

    String widgetComponentId(ObjectNode widget) {
        return provider.valueOrDefault(widget.path("componentId").asText(""),
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
        String resourcePath = provider.firstNonBlank(
                provider.jsonText(source, "resource"),
                "/api/unknown/resource");
        String operation = provider.valueOrDefault(provider.jsonText(source, "operation"), "group-by");
        String submitMethod = "post";
        String submitUrl = resourcePath + "/stats/" + operation;
        String schemaUrl = provider.defaultSchemaUrl(resourcePath, "post");
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

    DashboardDimension dimensionFromChartWidget(ObjectNode chartWidget) {
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
        String field = provider.firstNonBlank(
                provider.canonicalFieldName(provider.jsonText(dimension, "field")),
                "category");
        String label = provider.firstNonBlank(
                provider.jsonText(dimension, "label"),
                provider.titleFromResourcePath(field));
        String chartType = provider.firstNonBlank(
                provider.jsonText(chartDocument, "kind"),
                "bar");
        String orientation = provider.valueOrDefault(provider.jsonText(chartDocument, "orientation"), "vertical");
        String metricField = provider.firstNonBlank(
                provider.canonicalFieldName(provider.jsonText(metric, "field")),
                "");
        String metricAggregation = provider.firstNonBlank(
                provider.jsonText(metric, "aggregation"),
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
                provider.firstNonBlank(provider.jsonText(metric, "label"), metricField.isBlank() ? "Total" : provider.titleFromResourcePath(metricField)),
                "current-chart-widget-context");
    }

    private String widgetKeyFromWidget(
            ObjectNode chartWidget,
            AgenticAuthoringCandidate candidate,
            DashboardDimension dimension) {
        String key = chartWidget.path("key").asText("");
        return key.isBlank() ? provider.widgetKey(candidate, "chart-" + dimension.field()) : key;
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

    private boolean shouldMaterializeProfilePage(
            AgenticAuthoringVisualizationDecision visualizationDecision,
            AgenticAuthoringCandidate candidate) {
        boolean pageBuilderSurface = visualizationDecision == null
                || provider.isPrimaryComponent(visualizationDecision, "praxis-page-builder")
                || provider.safe(visualizationDecision.primaryComponent()).isBlank();
        boolean profileIntent = visualizationDecision != null
                && (provider.hasVisualIntent(visualizationDecision, "profile", "perfil", "ficha", "individual")
                || provider.hasLayoutKind(visualizationDecision, "single-column", "single_column", "profile", "profile-page"));
        boolean profileEvidence = hasProfileGovernedEvidence(candidate);
        boolean explicitlyCollectionFirst = visualizationDecision != null
                && (provider.isPrimaryComponent(visualizationDecision, "praxis-table")
                || provider.isPrimaryComponent(visualizationDecision, "praxis-list")
                || provider.isPrimaryComponent(visualizationDecision, "praxis-chart"));
        boolean explicitlyExcludedCollections = visualizationDecision != null
                && provider.excludesComponent(visualizationDecision, "praxis-table")
                && provider.excludesComponent(visualizationDecision, "praxis-list")
                && provider.excludesComponent(visualizationDecision, "praxis-chart");
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
        String evidenceText = provider.normalize(String.join(" ", fragments)).replaceAll("[^a-z0-9]+", " ").trim();
        return containsWholeTerm(evidenceText, "perfil")
                || containsWholeTerm(evidenceText, "profile")
                || containsWholeTerm(evidenceText, "ficha");
    }

    private boolean containsWholeTerm(String normalizedText, String term) {
        String normalizedTerm = provider.normalize(term).replaceAll("[^a-z0-9]+", " ").trim();
        if (normalizedText.isBlank() || normalizedTerm.isBlank()) {
            return false;
        }
        return (" " + normalizedText + " ").contains(" " + normalizedTerm + " ");
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
        String schemaVersion = provider.safe(envelope.path("schemaVersion").asText());
        String source = provider.safe(envelope.path("source").asText());
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

        String selectedResourcePath = provider.businessResourcePath(candidate == null ? "" : candidate.resourcePath());
        List<JsonNode> verifiedOperations = new ArrayList<>();
        List<JsonNode> filterOperations = new ArrayList<>();
        List<JsonNode> commandOperations = new ArrayList<>();
        String resourceKey = "";
        for (JsonNode entry : entries) {
            if (!isCompleteVerifiedOperation(entry)
                    || !selectedResourcePath.equals(provider.businessResourcePath(entry.path("resourcePath").asText()))) {
                continue;
            }
            verifiedOperations.add(entry.deepCopy());
            if (resourceKey.isBlank()) {
                resourceKey = provider.safe(entry.path("resourceKey").asText());
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
                || provider.safe(operation.path("resourceKey").asText()).isBlank()
                || provider.safe(operation.path("resourcePath").asText()).isBlank()
                || provider.safe(operation.path("apiPath").asText()).isBlank()
                || provider.safe(operation.path("apiMethod").asText()).isBlank()
                || provider.safe(operation.path("schemaUrl").asText()).isBlank()
                || provider.safe(operation.path("metadataUrl").asText()).isBlank()
                || provider.safe(operation.path("operationId").asText()).isBlank()
                || !operation.path("availability").path("allowed").isBoolean()) {
            return false;
        }
        String kind = provider.safe(operation.path("kind").asText());
        String verificationMode = provider.safe(operation.path("verificationMode").asText());
        String availabilityResolution = provider.safe(operation.path("availability").path("resolution").asText());
        if ("resource_operation".equals(kind)) {
            return "principal_capability".equals(verificationMode)
                    && operation.path("availability").path("allowed").asBoolean(false)
                    && "resource_capabilities".equals(availabilityResolution);
        }
        if (!"workflow_action".equals(kind)
                || !"runtime_action_discovery".equals(verificationMode)
                || provider.safe(operation.path("actionId").asText()).isBlank()) {
            return false;
        }
        String scope = provider.safe(operation.path("scope").asText()).toUpperCase(Locale.ROOT);
        if ("ITEM".equals(scope)) {
            return !operation.path("availability").path("allowed").asBoolean(true)
                    && "resource-context-required".equals(
                            provider.safe(operation.path("availability").path("reason").asText()))
                    && "item_capabilities_at_selection".equals(availabilityResolution);
        }
        return "COLLECTION".equals(scope)
                && operation.path("availability").path("allowed").asBoolean(false)
                && "catalog_principal".equals(availabilityResolution);
    }

    private boolean isCommandOperation(JsonNode operation, String resourcePath) {
        String method = provider.safe(operation.path("apiMethod").asText()).toLowerCase(Locale.ROOT);
        String apiPath = provider.safe(operation.path("apiPath").asText());
        return "workflow_action".equals(provider.safe(operation.path("kind").asText()))
                && !"get".equals(method)
                && apiPath.startsWith(resourcePath + "/")
                && apiPath.contains("/actions/");
    }

    private boolean isFilterOperation(JsonNode operation, String resourcePath) {
        String method = provider.safe(operation.path("apiMethod").asText()).toLowerCase(Locale.ROOT);
        String apiPath = provider.safe(operation.path("apiPath").asText());
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
            summary.put("command", isCommandOperation(operation, provider.businessResourcePath(operation.path("resourcePath").asText())));
            addSourceRef(sourceRefs, provider.safe(operation.path("schemaUrl").asText()));
            addSourceRef(sourceRefs, provider.safe(operation.path("metadataUrl").asText()));
            addSourceRef(sourceRefs, "metadata-operation:" + provider.safe(operation.path("operationId").asText()));
        }
    }

    private void copyText(JsonNode source, ObjectNode target, String field) {
        String value = provider.safe(source.path(field).asText());
        if (!value.isBlank()) {
            target.put(field, value);
        }
    }

    private void addSourceRef(ArrayNode sourceRefs, String value) {
        String normalized = provider.safe(value);
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

    private record TableSchemaField(String field, String label, String type) {
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
