package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministically compiles the public {@code praxis.ui-composition-plan} contract into the
 * Page Builder runtime page persisted by agentic authoring.
 *
 * <p>This is the server-side implementation of the canonical {@code compileUiCompositionPlan}
 * contract owned by {@code @praxisui/page-builder}. Keeping the same validation and projection on
 * the server ensures the terminal result, lineage check and persisted patch all refer to the same
 * materialized page.</p>
 */
final class AgenticAuthoringUiCompositionPlanCompiler {

    private static final String PLAN_KIND = "praxis.ui-composition-plan";
    private static final String PLAN_VERSION = "1.0";
    private static final Set<String> LINK_INTENTS = Set.of(
            "event-propagation",
            "state-write",
            "state-read",
            "command-dispatch",
            "selection-sync",
            "data-projection",
            "status-propagation");
    private static final String COMPILED_WARNING = "ui-composition-plan-compiled-by-config";
    private static final String LEGACY_CLIENT_COMPILE_WARNING = "compiled-form-patch-materialized-by-page-builder";
    static final String BUILDER_VERSION = "config-ui-composition-plan-compiler@1.3.0";
    private static final String MASTER_DETAIL_PRESET_ID = "master-detail-dashboard";
    private static final Set<String> GLOBAL_ACTION_SOURCE_FIELDS = Set.of("kind", "actionId");
    private static final List<String> MASTER_DETAIL_DETAIL_SLOTS = List.of(
            "detail-table", "detail-chart-a", "detail-chart-b", "detail-kpis");

    private final ObjectMapper objectMapper;

    AgenticAuthoringUiCompositionPlanCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    CompileResult compile(JsonNode plan, JsonNode baseCompiledFormPatch) {
        MasterDetailAnalysis layoutAnalysis = analyzeMasterDetailPlan(plan);
        List<String> failures = validate(plan);
        layoutAnalysis.errors().stream().map(CompilerDiagnostic::code).forEach(failures::add);
        if (!failures.isEmpty()) {
            return new CompileResult(
                    false,
                    objectMapper.createObjectNode(),
                    List.copyOf(failures),
                    List.copyOf(layoutAnalysis.errors()));
        }

        ObjectNode compiledFormPatch = baseCompiledFormPatch instanceof ObjectNode existing
                ? existing.deepCopy()
                : objectMapper.createObjectNode();
        putDefault(compiledFormPatch, "version", "1.0.0");
        putDefault(compiledFormPatch, "profileId", "ui-composition-plan");
        putDefault(compiledFormPatch, "targetComponentId", "praxis-dynamic-page-builder");
        compiledFormPatch.put("builderVersion", BUILDER_VERSION);
        ObjectNode compatibility = compiledFormPatch.path("compatibility") instanceof ObjectNode existing
                ? existing
                : compiledFormPatch.putObject("compatibility");
        putDefault(compatibility, "aiHttpContract", "v1.1");
        putDefault(compatibility, "publicResponseKind", "ui-composition-plan");
        if (!compatibility.has("requiresV12")) {
            compatibility.put("requiresV12", false);
        }
        ObjectNode patch = compiledFormPatch.path("patch") instanceof ObjectNode existing
                ? existing
                : compiledFormPatch.putObject("patch");
        patch.set("page", compilePage(plan, materializeLayout(plan, layoutAnalysis)));
        ArrayNode warnings = compiledFormPatch.path("warnings") instanceof ArrayNode existing
                ? existing
                : compiledFormPatch.putArray("warnings");
        removeText(warnings, LEGACY_CLIENT_COMPILE_WARNING);
        addTextOnce(warnings, COMPILED_WARNING);
        layoutAnalysis.diagnostics().stream()
                .map(CompilerDiagnostic::code)
                .forEach(code -> addTextOnce(warnings, code));
        String compiledPageFailure = AgenticAuthoringCompiledPagePatchValidator
                .terminalApplyBlockReason(compiledFormPatch);
        if (!compiledPageFailure.isBlank()) {
            return new CompileResult(
                    false,
                    objectMapper.createObjectNode(),
                    List.of("ui-composition-plan-compiled-page-invalid:" + compiledPageFailure),
                    List.of());
        }
        return new CompileResult(
                true,
                compiledFormPatch,
                List.of(),
                List.copyOf(layoutAnalysis.diagnostics()));
    }

    private List<String> validate(JsonNode plan) {
        List<String> failures = new ArrayList<>();
        if (plan == null || !plan.isObject()) {
            failures.add("ui-composition-plan-object-required");
            return failures;
        }
        if (!plan.path("kind").isTextual() || !PLAN_KIND.equals(plan.path("kind").textValue())) {
            failures.add("ui-composition-plan-kind-invalid");
        }
        if (!plan.path("version").isTextual() || !PLAN_VERSION.equals(plan.path("version").textValue())) {
            failures.add("ui-composition-plan-version-invalid");
        }
        JsonNode widgets = plan.path("widgets");
        Set<String> widgetKeys = new HashSet<>();
        if (!widgets.isArray()) {
            failures.add("ui-composition-plan-widgets-array-required");
        } else {
            for (JsonNode widget : widgets) {
                if (!widget.isObject()) {
                    failures.add("ui-composition-plan-widget-object-required");
                    continue;
                }
                JsonNode keyNode = widget.get("key");
                if (keyNode == null || !keyNode.isTextual() || keyNode.textValue().trim().isBlank()) {
                    failures.add("ui-composition-plan-widget-key-required");
                    continue;
                }
                String key = keyNode.textValue().trim();
                if (!widgetKeys.add(key)) {
                    failures.add("ui-composition-plan-widget-key-duplicated");
                }
                JsonNode componentId = widget.get("componentId");
                if (componentId == null || !componentId.isTextual() || componentId.textValue().trim().isBlank()) {
                    failures.add("ui-composition-plan-component-id-required");
                }
                validateOptionalObject(widget, "inputs", "ui-composition-plan-widget-inputs-object-required", failures);
                validateOptionalObject(widget, "outputs", "ui-composition-plan-widget-outputs-object-required", failures);
                validateOptionalObject(widget, "shell", "ui-composition-plan-widget-shell-object-required", failures);
                if (widget.has("bindingOrder") && !isTextArray(widget.path("bindingOrder"))) {
                    failures.add("ui-composition-plan-widget-binding-order-invalid");
                }
                if (widget.has("role") && !widget.path("role").isTextual()) {
                    failures.add("ui-composition-plan-widget-role-invalid");
                }
            }
        }
        JsonNode bindings = plan.path("bindings");
        if (!bindings.isMissingNode() && !bindings.isArray()) {
            failures.add("ui-composition-plan-bindings-array-required");
        } else if (bindings.isArray()) {
            for (JsonNode binding : bindings) {
                if (!binding.isObject()) {
                    failures.add("ui-composition-plan-binding-object-required");
                    continue;
                }
                if (!isNonBlankText(binding.get("id"))) {
                    failures.add("ui-composition-plan-binding-id-required");
                }
                if (!binding.path("intent").isTextual()
                        || !LINK_INTENTS.contains(binding.path("intent").textValue())) {
                    failures.add("ui-composition-plan-binding-intent-invalid");
                }
                validateEndpoint(binding.path("from"), widgetKeys, true, failures);
                validateEndpoint(binding.path("to"), widgetKeys, false, failures);
                if (binding.has("transform")) {
                    validateTransform(binding.get("transform"), failures);
                }
            }
        }
        validateOptionalObject(plan, "canvas", "ui-composition-plan-canvas-object-required", failures);
        validateOptionalArray(plan, "grouping", "ui-composition-plan-grouping-array-required", failures);
        validateOptionalObject(plan, "deviceLayouts", "ui-composition-plan-device-layouts-object-required", failures);
        validateOptionalObject(plan, "slotAssignments", "ui-composition-plan-slot-assignments-object-required", failures);
        validateOptionalObject(plan, "state", "ui-composition-plan-state-object-required", failures);
        validateOptionalObject(plan, "i18n", "ui-composition-plan-i18n-object-required", failures);
        validateOptionalObject(plan, "context", "ui-composition-plan-context-object-required", failures);
        validateOptionalObject(plan, "layout", "ui-composition-plan-layout-object-required", failures);
        validateOptionalArray(
                plan,
                "selectionSyncs",
                "ui-composition-plan-selection-syncs-array-required",
                failures);
        validateOptionalArray(
                plan,
                "contextScopes",
                "ui-composition-plan-context-scopes-array-required",
                failures);
        validateOptionalObject(
                plan,
                "layoutPresetOptions",
                "ui-composition-plan-layout-preset-options-object-required",
                failures);
        validateOptionalText(plan, "layoutPreset", "ui-composition-plan-layout-preset-invalid", failures);
        validateOptionalText(plan, "themePreset", "ui-composition-plan-theme-preset-invalid", failures);
        validateCanvas(plan.path("canvas"), widgetKeys, failures);
        validateGrouping(plan.path("grouping"), widgetKeys, failures);
        validateDeviceLayouts(plan.path("deviceLayouts"), widgetKeys, failures);
        validateSlotAssignments(plan.path("slotAssignments"), widgetKeys, failures);
        validateSelectionSyncs(plan.path("selectionSyncs"), widgetKeys, failures);
        validateContextScopes(plan.path("contextScopes"), plan.path("widgets"), widgetKeys, failures);
        return failures;
    }

    private void validateSelectionSyncs(
            JsonNode selectionSyncs,
            Set<String> widgetKeys,
            List<String> failures) {
        if (!selectionSyncs.isArray()) {
            return;
        }
        for (JsonNode selectionSync : selectionSyncs) {
            if (!selectionSync.isObject()) {
                failures.add("ui-composition-plan-selection-sync-object-required");
                continue;
            }
            if (!isNonBlankText(selectionSync.get("id"))) {
                failures.add("ui-composition-plan-selection-sync-id-required");
            }
            if (!"selection-sync".equals(selectionSync.path("intent").asText(""))) {
                failures.add("ui-composition-plan-selection-sync-intent-invalid");
            }
            JsonNode sources = selectionSync.path("sources");
            if (!sources.isArray() || sources.isEmpty()) {
                failures.add("ui-composition-plan-selection-sync-source-required");
            } else {
                for (JsonNode source : sources) {
                    validateEndpoint(source, widgetKeys, true, failures);
                    if (!"component-port".equals(source.path("kind").asText(""))) {
                        failures.add("ui-composition-plan-selection-sync-source-component-port-required");
                    } else if (!"output".equals(source.path("direction").asText(""))) {
                        failures.add("ui-composition-plan-selection-sync-source-direction-invalid");
                    }
                }
            }
            JsonNode target = selectionSync.path("target");
            if (!"state".equals(target.path("kind").asText(""))) {
                failures.add("ui-composition-plan-selection-sync-target-state-required");
            } else {
                validateEndpoint(target, widgetKeys, false, failures);
            }
            JsonNode mapping = selectionSync.path("mapping");
            if (!mapping.isObject() || mapping.isEmpty()) {
                failures.add("ui-composition-plan-selection-sync-mapping-required");
                continue;
            }
            mapping.fields().forEachRemaining(entry -> validateSelectionMapping(entry, failures));
        }
    }

    private void validateSelectionMapping(
            Map.Entry<String, JsonNode> entry,
            List<String> failures) {
        if (entry.getKey().trim().isBlank()) {
            failures.add("ui-composition-plan-selection-sync-target-key-required");
        }
        JsonNode mapping = entry.getValue();
        if (mapping.isTextual()) {
            if (mapping.textValue().trim().isBlank()) {
                failures.add("ui-composition-plan-selection-sync-source-path-required");
            }
            return;
        }
        if (!mapping.isObject() || !isNonBlankText(mapping.get("path"))) {
            failures.add("ui-composition-plan-selection-sync-source-path-required");
            return;
        }
        validateTransformInputSource(mapping, failures);
    }

    private void validateContextScopes(
            JsonNode contextScopes,
            JsonNode widgets,
            Set<String> widgetKeys,
            List<String> failures) {
        if (!contextScopes.isArray()) {
            return;
        }
        for (JsonNode contextScope : contextScopes) {
            if (!contextScope.isObject()) {
                failures.add("ui-composition-plan-context-scope-object-required");
                continue;
            }
            if (!isNonBlankText(contextScope.get("id"))) {
                failures.add("ui-composition-plan-context-scope-id-required");
            }
            JsonNode context = contextScope.path("context");
            if (!context.isObject() || context.isEmpty()) {
                failures.add("ui-composition-plan-context-scope-context-required");
            } else {
                context.fields().forEachRemaining(entry -> validateContextValue(entry, failures));
            }
            JsonNode targets = contextScope.path("targets");
            if (!targets.isArray() || targets.isEmpty()) {
                failures.add("ui-composition-plan-context-scope-target-required");
                continue;
            }
            for (JsonNode target : targets) {
                validateContextTarget(target, context, widgets, widgetKeys, failures);
            }
        }
    }

    private void validateContextValue(
            Map.Entry<String, JsonNode> entry,
            List<String> failures) {
        if (entry.getKey().trim().isBlank()) {
            failures.add("ui-composition-plan-context-scope-key-required");
        }
        JsonNode contextValue = entry.getValue();
        String kind = contextValue.path("kind").asText("");
        if ("state".equals(kind)) {
            if (!isNonBlankText(contextValue.get("path"))) {
                failures.add("ui-composition-plan-context-scope-state-path-required");
            }
            return;
        }
        if ("constant".equals(kind)) {
            if (!contextValue.has("value")) {
                failures.add("ui-composition-plan-context-scope-constant-value-required");
            }
            return;
        }
        failures.add("ui-composition-plan-context-scope-value-kind-invalid");
    }

    private void validateContextTarget(
            JsonNode target,
            JsonNode context,
            JsonNode widgets,
            Set<String> widgetKeys,
            List<String> failures) {
        if (!target.isObject()) {
            failures.add("ui-composition-plan-context-scope-target-object-required");
            return;
        }
        String widgetKey = target.path("widget").asText("").trim();
        if (widgetKey.isBlank() || !widgetKeys.contains(widgetKey)) {
            failures.add("ui-composition-plan-context-scope-target-widget-not-found");
            return;
        }
        JsonNode nestedPath = target.path("nestedPath");
        validateNestedPath(nestedPath, failures);
        if (nestedPath.isArray() && !nestedPath.isEmpty()) {
            ObjectNode ownerDefinition = plannedWidgetDefinition(findPlannedWidget(widgets, widgetKey));
            if (ownerDefinition == null || resolveNestedWidgetDefinition(ownerDefinition, nestedPath, 0) == null) {
                failures.add("ui-composition-plan-context-scope-nested-target-not-found");
            }
        }
        JsonNode inherit = target.path("inherit");
        if (!inherit.isMissingNode() && !isTextArray(inherit)) {
            failures.add("ui-composition-plan-context-scope-inherit-invalid");
            return;
        }
        for (JsonNode inheritedKey : inherit) {
            if (!context.has(inheritedKey.asText())) {
                failures.add("ui-composition-plan-context-scope-inherited-key-not-found");
            }
        }
    }

    private void validateOptionalObject(
            JsonNode plan,
            String field,
            String failureCode,
            List<String> failures) {
        if (plan.has(field) && !plan.path(field).isObject()) {
            failures.add(failureCode);
        }
    }

    private void validateOptionalArray(
            JsonNode plan,
            String field,
            String failureCode,
            List<String> failures) {
        if (plan.has(field) && !plan.path(field).isArray()) {
            failures.add(failureCode);
        }
    }

    private void validateOptionalText(
            JsonNode plan,
            String field,
            String failureCode,
            List<String> failures) {
        if (plan.has(field) && !plan.path(field).isTextual()) {
            failures.add(failureCode);
        }
    }

    private void validateTransform(JsonNode transform, List<String> failures) {
        if (transform == null || !transform.isObject()) {
            failures.add("ui-composition-plan-transform-object-required");
            return;
        }
        if (!isNonBlankText(transform.get("id"))) {
            failures.add("ui-composition-plan-transform-id-required");
        }

        String kind = transform.path("kind").isTextual() ? transform.path("kind").textValue() : "";
        switch (kind) {
            case "pick-path" -> {
                if (!isNonBlankText(transform.get("path"))) {
                    failures.add("ui-composition-plan-transform-path-required");
                }
                validateTransformInputSource(transform, failures);
            }
            case "constant" -> {
                if (!transform.has("value")) {
                    failures.add("ui-composition-plan-transform-value-required");
                }
            }
            case "query-context" -> {
                if (!isNonBlankText(transform.get("field"))) {
                    failures.add("ui-composition-plan-transform-field-required");
                }
                validateTransformInputSource(transform, failures);
            }
            case "template" -> {
                if (!transform.has("template")) {
                    failures.add("ui-composition-plan-transform-template-required");
                }
                validateTransformInputSource(transform, failures);
            }
            default -> failures.add("ui-composition-plan-transform-kind-unsupported");
        }
    }

    private void validateTransformInputSource(JsonNode transform, List<String> failures) {
        if (!transform.has("inputSource")) {
            return;
        }
        String inputSource = transform.path("inputSource").isTextual()
                ? transform.path("inputSource").textValue()
                : "";
        if (!Set.of("event", "payload", "state", "context", "constant").contains(inputSource)) {
            failures.add("ui-composition-plan-transform-input-source-unsupported");
        }
    }

    private void validateCanvas(JsonNode canvas, Set<String> widgetKeys, List<String> failures) {
        JsonNode items = canvas.path("items");
        if (!canvas.isObject()) {
            return;
        }
        if (!items.isObject()) {
            failures.add("ui-composition-plan-canvas-items-object-required");
            return;
        }
        items.fieldNames().forEachRemaining(widgetKey -> {
            if (!widgetKeys.contains(widgetKey)) {
                failures.add("ui-composition-plan-canvas-widget-not-found");
            }
        });
    }

    private void validateGrouping(JsonNode grouping, Set<String> widgetKeys, List<String> failures) {
        if (!grouping.isArray()) {
            return;
        }
        Set<String> groupIds = new HashSet<>();
        for (JsonNode group : grouping) {
            if (!group.isObject()) {
                failures.add("ui-composition-plan-group-object-required");
                continue;
            }
            String groupId = isNonBlankText(group.get("id")) ? group.path("id").textValue().trim() : "";
            if (groupId.isBlank()) {
                failures.add("ui-composition-plan-group-id-required");
                continue;
            }
            if (!groupIds.add(groupId)) {
                failures.add("ui-composition-plan-group-id-duplicated");
            }
            if ("tabs".equals(group.path("kind").asText(""))) {
                for (JsonNode tab : group.path("tabs")) {
                    validateWidgetKeyReferences(
                            tab.path("widgetKeys"),
                            widgetKeys,
                            "ui-composition-plan-grouping-widget-not-found",
                            failures);
                }
            } else {
                validateWidgetKeyReferences(
                        group.path("widgetKeys"),
                        widgetKeys,
                        "ui-composition-plan-grouping-widget-not-found",
                        failures);
            }
        }
    }

    private void validateDeviceLayouts(
            JsonNode deviceLayouts,
            Set<String> widgetKeys,
            List<String> failures) {
        if (!deviceLayouts.isObject()) {
            return;
        }
        for (JsonNode variant : deviceLayouts) {
            if (!variant.isObject()) {
                failures.add("ui-composition-plan-device-layout-variant-object-required");
                continue;
            }
            validateObjectKeys(
                    variant.path("canvas").path("items"),
                    widgetKeys,
                    "ui-composition-plan-device-layout-widget-not-found",
                    failures);
            validateObjectKeys(
                    variant.path("widgetOverrides"),
                    widgetKeys,
                    "ui-composition-plan-device-layout-widget-override-not-found",
                    failures);
            for (JsonNode override : variant.path("groupingOverrides")) {
                validateWidgetKeyReferences(
                        override.path("widgetKeys"),
                        widgetKeys,
                        "ui-composition-plan-device-layout-grouping-widget-not-found",
                        failures);
                for (JsonNode tab : override.path("tabs")) {
                    validateWidgetKeyReferences(
                            tab.path("widgetKeys"),
                            widgetKeys,
                            "ui-composition-plan-device-layout-grouping-widget-not-found",
                            failures);
                }
            }
        }
    }

    private void validateSlotAssignments(
            JsonNode slotAssignments,
            Set<String> widgetKeys,
            List<String> failures) {
        if (!slotAssignments.isObject()) {
            return;
        }
        slotAssignments.fields().forEachRemaining(entry -> {
            if (!widgetKeys.contains(entry.getKey())) {
                failures.add("ui-composition-plan-slot-assignment-widget-not-found");
            }
            if (!isNonBlankText(entry.getValue())) {
                failures.add("ui-composition-plan-slot-assignment-slot-required");
            }
        });
    }

    private void validateObjectKeys(
            JsonNode object,
            Set<String> widgetKeys,
            String failureCode,
            List<String> failures) {
        if (!object.isObject()) {
            return;
        }
        object.fieldNames().forEachRemaining(widgetKey -> {
            if (!widgetKeys.contains(widgetKey)) {
                failures.add(failureCode);
            }
        });
    }

    private void validateWidgetKeyReferences(
            JsonNode references,
            Set<String> widgetKeys,
            String failureCode,
            List<String> failures) {
        if (!references.isArray()) {
            return;
        }
        for (JsonNode reference : references) {
            if (!reference.isTextual() || !widgetKeys.contains(reference.textValue())) {
                failures.add(failureCode);
            }
        }
    }

    private boolean isNonBlankText(JsonNode node) {
        return node != null && node.isTextual() && !node.textValue().trim().isBlank();
    }

    private boolean isTextArray(JsonNode node) {
        if (!node.isArray()) return false;
        for (JsonNode value : node) {
            if (!isNonBlankText(value)) return false;
        }
        return true;
    }

    private void validateEndpoint(
            JsonNode endpoint,
            Set<String> widgetKeys,
            boolean sourceEndpoint,
            List<String> failures) {
        if (!endpoint.isObject()) {
            failures.add("ui-composition-plan-endpoint-required");
            return;
        }
        String kind = endpoint.path("kind").asText("");
        if ("component-port".equals(kind)) {
            JsonNode widget = endpoint.get("widget");
            if (!isNonBlankText(widget) || !widgetKeys.contains(widget.textValue().trim())) {
                failures.add("ui-composition-plan-endpoint-widget-not-found");
            }
            if (!isNonBlankText(endpoint.get("port"))) {
                failures.add("ui-composition-plan-endpoint-port-required");
            }
            validateNestedPath(endpoint.path("nestedPath"), failures);
            return;
        }
        if ("state".equals(kind)) {
            if (!isNonBlankText(endpoint.get("path"))) {
                failures.add("ui-composition-plan-endpoint-state-path-required");
            }
            return;
        }
        if ("global-action".equals(kind)) {
            if (!isNonBlankText(endpoint.get("actionId"))) {
                failures.add("ui-composition-plan-endpoint-global-action-id-required");
            }
            if (sourceEndpoint) {
                endpoint.fieldNames().forEachRemaining(field -> {
                    if (!GLOBAL_ACTION_SOURCE_FIELDS.contains(field)) {
                        failures.add("ui-composition-plan-endpoint-global-action-source-field-unsupported");
                    }
                });
                return;
            }
            if (endpoint.has("payloadExpr") && !endpoint.path("payloadExpr").isTextual()) {
                failures.add("ui-composition-plan-endpoint-global-action-payload-expr-invalid");
            }
            return;
        }
        failures.add("ui-composition-plan-endpoint-kind-unsupported");
    }

    private void validateNestedPath(JsonNode nestedPath, List<String> failures) {
        if (!nestedPath.isArray() || nestedPath.isEmpty()) {
            return;
        }
        JsonNode terminal = nestedPath.path(nestedPath.size() - 1);
        if (!"widget".equals(terminal.path("kind").asText(""))
                || terminal.path("key").asText("").trim().isBlank()) {
            failures.add("ui-composition-plan-endpoint-nested-terminal-widget-key-required");
        }
    }

    private ObjectNode compilePage(
            JsonNode plan,
            UiCompositionLayoutMaterialization layoutMaterialization) {
        ObjectNode page = objectMapper.createObjectNode();
        copyIfPresent(plan, page, "i18n");
        copyIfPresent(plan, page, "context");
        copyIfPresent(plan, page, "layout");
        copyIfPresent(plan, page, "layoutPreset");
        setIfPresent(page, "layoutPresetOptions", layoutMaterialization.layoutPresetOptions());
        setIfPresent(page, "canvas", layoutMaterialization.canvas());
        setIfPresent(page, "deviceLayouts", layoutMaterialization.deviceLayouts());
        copyIfPresent(plan, page, "grouping");
        setIfPresent(page, "slotAssignments", layoutMaterialization.slotAssignments());
        copyIfPresent(plan, page, "state");
        copyIfPresent(plan, page, "themePreset");

        ArrayNode widgets = page.putArray("widgets");
        ArrayNode bindings = expandedBindings(plan);
        for (JsonNode plannedWidget : plan.path("widgets")) {
            ObjectNode widget = widgets.addObject();
            String widgetKey = plannedWidget.path("key").asText();
            widget.put("key", widgetKey);
            copyIfPresent(plannedWidget, widget, "shell");
            ObjectNode definition = widget.putObject("definition");
            definition.put("id", plannedWidget.path("componentId").asText());
            copyIfPresent(plannedWidget, definition, "bindingOrder");
            definition.set("inputs", plannedWidget.path("inputs").isObject()
                    ? plannedWidget.path("inputs").deepCopy()
                    : objectMapper.createObjectNode());
            definition.set("outputs", linkedOutputs(plannedWidget, widgetKey, bindings));
        }
        materializeContextScopeInputs(plan.path("contextScopes"), widgets);

        ObjectNode composition = page.putObject("composition");
        composition.put("version", "1.0.0");
        ArrayNode links = composition.putArray("links");
        for (JsonNode binding : bindings) {
            links.add(compileBinding(binding));
        }
        materializeLocalizedFallbacks(page);
        return page;
    }

    /**
     * Expands compact authoring descriptors such as {@code {"key":"copy.key"}}
     * with the page-owned fallback-locale dictionary. This keeps the plan terse
     * while the persisted runtime page remains portable and self-describing.
     */
    private void materializeLocalizedFallbacks(ObjectNode page) {
        JsonNode i18n = page.path("i18n");
        String fallbackLocale = i18n.path("fallbackLocale").asText("pt-BR").trim();
        if (fallbackLocale.isBlank()) {
            fallbackLocale = "pt-BR";
        }
        JsonNode dictionary = i18n.path("dictionaries").path(fallbackLocale);
        if (!dictionary.isObject()) {
            return;
        }
        materializeLocalizedFallbacks(page, dictionary);
    }

    private void materializeLocalizedFallbacks(JsonNode value, JsonNode dictionary) {
        if (value instanceof ArrayNode array) {
            array.forEach(item -> materializeLocalizedFallbacks(item, dictionary));
            return;
        }
        if (!(value instanceof ObjectNode object)) {
            return;
        }

        String key = object.path("key").asText("").trim();
        if (!key.isBlank()
                && !object.has("text")
                && hasOnlyLocalizedDescriptorFields(object)
                && dictionary.path(key).isTextual()) {
            object.put("text", dictionary.path(key).textValue());
        }
        object.elements().forEachRemaining(item -> materializeLocalizedFallbacks(item, dictionary));
    }

    private boolean hasOnlyLocalizedDescriptorFields(ObjectNode object) {
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            if (!Set.of("key", "text", "params").contains(fields.next())) {
                return false;
            }
        }
        return true;
    }

    private void setIfPresent(ObjectNode target, String field, JsonNode value) {
        if (value != null && !value.isMissingNode() && !value.isNull()) {
            target.set(field, value.deepCopy());
        }
    }

    private ArrayNode expandedBindings(JsonNode plan) {
        ArrayNode bindings = objectMapper.createArrayNode();
        if (plan.path("bindings").isArray()) {
            plan.path("bindings").forEach(binding -> bindings.add(binding.deepCopy()));
        }
        expandSelectionSyncs(plan.path("selectionSyncs"), bindings);
        expandContextScopeBindings(plan.path("contextScopes"), bindings);
        return bindings;
    }

    private void expandSelectionSyncs(JsonNode selectionSyncs, ArrayNode bindings) {
        if (!selectionSyncs.isArray()) {
            return;
        }
        for (JsonNode selectionSync : selectionSyncs) {
            String syncId = selectionSync.path("id").asText();
            String basePath = stripTrailingDots(selectionSync.path("target").path("path").asText());
            for (JsonNode source : selectionSync.path("sources")) {
                Iterator<Map.Entry<String, JsonNode>> mappings = selectionSync.path("mapping").fields();
                while (mappings.hasNext()) {
                    Map.Entry<String, JsonNode> mappingEntry = mappings.next();
                    String targetKey = mappingEntry.getKey();
                    JsonNode mapping = mappingEntry.getValue();
                    String sourcePath = mapping.isTextual()
                            ? mapping.asText()
                            : mapping.path("path").asText();
                    String targetPath = basePath.isBlank() ? targetKey : basePath + "." + targetKey;
                    ObjectNode binding = bindings.addObject();
                    binding.put(
                            "id",
                            syncId + ":" + source.path("widget").asText() + "."
                                    + source.path("port").asText() + "->state." + targetPath);
                    binding.set("from", source.deepCopy());
                    ObjectNode target = binding.putObject("to");
                    target.put("kind", "state");
                    target.put("path", targetPath);
                    copyIfPresent(selectionSync.path("target"), target, "layer");
                    binding.put("intent", "selection-sync");
                    if (mapping.isObject()) {
                        copyIfPresent(mapping, binding, "condition");
                    }
                    ObjectNode transform = binding.putObject("transform");
                    transform.put("kind", "pick-path");
                    transform.put(
                            "id",
                            syncId + ":" + source.path("widget").asText() + "."
                                    + source.path("port").asText() + ":pick-" + targetKey);
                    transform.put("path", sourcePath);
                    if (mapping.isObject()) {
                        copyIfPresent(mapping, transform, "inputSource");
                    }
                    copyIfPresent(selectionSync, binding, "policy");
                    copyIfPresent(selectionSync, binding, "metadata");
                }
            }
        }
    }

    private void expandContextScopeBindings(JsonNode contextScopes, ArrayNode bindings) {
        if (!contextScopes.isArray()) {
            return;
        }
        for (JsonNode contextScope : contextScopes) {
            JsonNode context = contextScope.path("context");
            for (JsonNode target : contextScope.path("targets")) {
                for (String contextKey : inheritedContextKeys(target, context)) {
                    JsonNode contextValue = context.path(contextKey);
                    if (!"state".equals(contextValue.path("kind").asText(""))) {
                        continue;
                    }
                    ObjectNode binding = bindings.addObject();
                    binding.put(
                            "id",
                            contextScope.path("id").asText() + ":state."
                                    + contextValue.path("path").asText() + "->"
                                    + target.path("widget").asText() + "." + contextKey
                                    + nestedTargetIdentity(target.path("nestedPath")));
                    ObjectNode from = binding.putObject("from");
                    from.put("kind", "state");
                    from.put("path", contextValue.path("path").asText());
                    copyIfPresent(contextValue, from, "layer");
                    ObjectNode to = binding.putObject("to");
                    to.put("kind", "component-port");
                    to.put("widget", target.path("widget").asText());
                    to.put("port", contextKey);
                    to.put("direction", "input");
                    if (target.path("nestedPath").isArray() && !target.path("nestedPath").isEmpty()) {
                        to.set("nestedPath", target.path("nestedPath").deepCopy());
                    }
                    binding.put("intent", "state-read");
                    copyIfPresent(contextValue, binding, "condition");
                    copyIfPresent(contextScope, binding, "policy");
                    copyIfPresent(contextScope, binding, "metadata");
                }
            }
        }
    }

    private void materializeContextScopeInputs(JsonNode contextScopes, ArrayNode widgets) {
        if (!contextScopes.isArray()) {
            return;
        }
        for (JsonNode contextScope : contextScopes) {
            JsonNode context = contextScope.path("context");
            for (JsonNode target : contextScope.path("targets")) {
                ObjectNode widget = findCompiledWidget(widgets, target.path("widget").asText());
                if (widget == null || !(widget.path("definition") instanceof ObjectNode definition)) {
                    continue;
                }
                for (String contextKey : inheritedContextKeys(target, context)) {
                    JsonNode contextValue = context.path(contextKey);
                    boolean constant = "constant".equals(contextValue.path("kind").asText(""));
                    boolean stateInitial = "state".equals(contextValue.path("kind").asText(""))
                            && contextValue.has("initial");
                    if (!constant && !stateInitial) {
                        continue;
                    }
                    ObjectNode targetDefinition = definition;
                    if (target.path("nestedPath").isArray() && !target.path("nestedPath").isEmpty()) {
                        targetDefinition = resolveNestedWidgetDefinition(
                                definition,
                                target.path("nestedPath"),
                                0);
                    }
                    if (targetDefinition == null) {
                        continue;
                    }
                    ObjectNode inputs = targetDefinition.path("inputs") instanceof ObjectNode existingInputs
                            ? existingInputs
                            : targetDefinition.putObject("inputs");
                    inputs.set(
                            contextKey,
                            (constant ? contextValue.path("value") : contextValue.path("initial")).deepCopy());
                }
            }
        }
    }

    private List<String> inheritedContextKeys(JsonNode target, JsonNode context) {
        List<String> keys = new ArrayList<>();
        JsonNode inherit = target.path("inherit");
        if (inherit.isArray() && !inherit.isEmpty()) {
            inherit.forEach(value -> keys.add(value.asText()));
            return keys;
        }
        context.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private String nestedTargetIdentity(JsonNode nestedPath) {
        if (!nestedPath.isArray() || nestedPath.isEmpty()) {
            return "";
        }
        JsonNode terminal = nestedPath.path(nestedPath.size() - 1);
        String key = terminal.path("key").asText("").trim();
        return "widget".equals(terminal.path("kind").asText("")) && !key.isBlank()
                ? "#" + key
                : "";
    }

    private String stripTrailingDots(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '.') {
            end--;
        }
        return value.substring(0, end);
    }

    private JsonNode findPlannedWidget(JsonNode widgets, String widgetKey) {
        if (!widgets.isArray()) {
            return null;
        }
        for (JsonNode widget : widgets) {
            if (widgetKey.equals(widget.path("key").asText())) {
                return widget;
            }
        }
        return null;
    }

    private ObjectNode plannedWidgetDefinition(JsonNode plannedWidget) {
        if (plannedWidget == null || !plannedWidget.isObject()) {
            return null;
        }
        ObjectNode definition = objectMapper.createObjectNode();
        definition.put("id", plannedWidget.path("componentId").asText());
        definition.set(
                "inputs",
                plannedWidget.path("inputs").isObject()
                        ? plannedWidget.path("inputs").deepCopy()
                        : objectMapper.createObjectNode());
        return definition;
    }

    private ObjectNode findCompiledWidget(ArrayNode widgets, String widgetKey) {
        for (JsonNode widget : widgets) {
            if (widgetKey.equals(widget.path("key").asText()) && widget instanceof ObjectNode objectWidget) {
                return objectWidget;
            }
        }
        return null;
    }

    private ObjectNode resolveNestedWidgetDefinition(
            ObjectNode definition,
            JsonNode nestedPath,
            int offset) {
        NestedWidgetArrayLocation location = resolveNestedWidgetArrayLocation(definition, nestedPath, offset);
        if (location == null) {
            return null;
        }
        JsonNode widgetSegment = nestedPath.path(location.nextSegmentIndex());
        if (!"widget".equals(widgetSegment.path("kind").asText(""))
                || !isNonBlankText(widgetSegment.get("key"))) {
            return null;
        }
        ObjectNode child = findChildWidget(location.widgets(), widgetSegment.path("key").asText());
        if (child == null) {
            return null;
        }
        int remainingOffset = location.nextSegmentIndex() + 1;
        if (remainingOffset >= nestedPath.size()) {
            return child;
        }
        return resolveNestedWidgetDefinition(child, nestedPath, remainingOffset);
    }

    private NestedWidgetArrayLocation resolveNestedWidgetArrayLocation(
            ObjectNode definition,
            JsonNode nestedPath,
            int offset) {
        JsonNode config = definition.path("inputs").path("config");
        if (!config.isObject() || !nestedPath.isArray() || offset >= nestedPath.size()) {
            return null;
        }
        String componentId = definition.path("id").asText();
        if ("praxis-tabs".equals(componentId)) {
            return resolveTabsWidgetArray(config, nestedPath, offset);
        }
        if ("praxis-expansion".equals(componentId)) {
            return resolveExpansionWidgetArray(config, nestedPath, offset);
        }
        return null;
    }

    private NestedWidgetArrayLocation resolveTabsWidgetArray(
            JsonNode config,
            JsonNode nestedPath,
            int offset) {
        JsonNode first = nestedPath.path(offset);
        if ("tab".equals(first.path("kind").asText(""))) {
            JsonNode tab = findBySegment(config.path("tabs"), first);
            ArrayNode widgets = childWidgets(tab);
            return widgets == null ? null : new NestedWidgetArrayLocation(widgets, offset + 1);
        }
        JsonNode linkSegment = "nav".equals(first.path("kind").asText(""))
                ? nestedPath.path(offset + 1)
                : first;
        if (!"link".equals(linkSegment.path("kind").asText(""))) {
            return null;
        }
        JsonNode link = findBySegment(config.path("nav").path("links"), linkSegment);
        ArrayNode widgets = childWidgets(link);
        if (widgets == null) {
            return null;
        }
        return new NestedWidgetArrayLocation(
                widgets,
                "nav".equals(first.path("kind").asText("")) ? offset + 2 : offset + 1);
    }

    private NestedWidgetArrayLocation resolveExpansionWidgetArray(
            JsonNode config,
            JsonNode nestedPath,
            int offset) {
        JsonNode panelSegment = nestedPath.path(offset);
        if (!"panel".equals(panelSegment.path("kind").asText(""))) {
            return null;
        }
        JsonNode panel = findBySegment(config.path("panels"), panelSegment);
        ArrayNode widgets = childWidgets(panel);
        return widgets == null ? null : new NestedWidgetArrayLocation(widgets, offset + 1);
    }

    private JsonNode findBySegment(JsonNode items, JsonNode segment) {
        if (!items.isArray()) {
            return null;
        }
        if (isNonBlankText(segment.get("id"))) {
            String id = segment.path("id").asText();
            for (JsonNode item : items) {
                if (id.equals(item.path("id").asText())) {
                    return item;
                }
            }
        }
        if (segment.path("index").canConvertToInt()) {
            int index = segment.path("index").asInt();
            if (index >= 0 && index < items.size()) {
                return items.path(index);
            }
        }
        return null;
    }

    private ArrayNode childWidgets(JsonNode container) {
        return container != null && container.path("widgets") instanceof ArrayNode widgets
                ? widgets
                : null;
    }

    private ObjectNode findChildWidget(ArrayNode widgets, String childWidgetKey) {
        for (JsonNode widget : widgets) {
            if (childWidgetKey.equals(widget.path("childWidgetKey").asText())
                    && widget instanceof ObjectNode definition) {
                return definition;
            }
        }
        return null;
    }

    private record NestedWidgetArrayLocation(ArrayNode widgets, int nextSegmentIndex) {}

    private UiCompositionLayoutMaterialization materializeLayout(
            JsonNode plan,
            MasterDetailAnalysis analysis) {
        if (!analysis.masterDetail()) {
            JsonNode canvas = null;
            if (plan.path("canvas").isObject()) {
                canvas = plan.path("canvas");
            } else if (plan.path("widgets").isArray()
                    && !plan.path("widgets").isEmpty()
                    && (!plan.path("layout").isObject()
                            || !plan.path("layoutPreset").asText("").trim().isBlank())) {
                canvas = neutralCanvas(plan.path("widgets"));
            }
            return new UiCompositionLayoutMaterialization(
                    objectOrNull(plan.path("layoutPresetOptions")),
                    canvas,
                    objectOrNull(plan.path("deviceLayouts")),
                    objectOrNull(plan.path("slotAssignments")));
        }

        List<MasterDetailWidget> semantics = analysis.widgets();
        ObjectNode slotAssignments = analysis.slotAssignments();
        ObjectNode canvas = plan.path("canvas").isObject()
                ? (ObjectNode) plan.path("canvas").deepCopy()
                : masterDetailCanvas(semantics, 12, "desktop");
        ObjectNode deviceLayouts = plan.path("deviceLayouts").isObject()
                ? (ObjectNode) plan.path("deviceLayouts").deepCopy()
                : materializeMasterDetailDeviceLayouts(semantics, canvas);
        ObjectNode options = plan.path("layoutPresetOptions").isObject()
                ? (ObjectNode) plan.path("layoutPresetOptions").deepCopy()
                : objectMapper.createObjectNode();
        if (resolvePlanLayoutPresetId(plan) == null && !options.path("presetFamily").isTextual()) {
            options.put("presetFamily", MASTER_DETAIL_PRESET_ID);
        }
        return new UiCompositionLayoutMaterialization(
                options.isEmpty() ? null : options,
                canvas,
                deviceLayouts,
                slotAssignments.isEmpty() ? null : slotAssignments);
    }

    private JsonNode objectOrNull(JsonNode value) {
        return value.isObject() ? value.deepCopy() : null;
    }

    private boolean isMasterDetailPlan(JsonNode plan) {
        String presetId = resolvePlanLayoutPresetId(plan);
        if ("master-detail".equals(presetCategory(presetId))) {
            return true;
        }
        boolean master = false;
        boolean detail = false;
        JsonNode assignments = plan.path("slotAssignments");
        for (JsonNode widget : plan.path("widgets")) {
            String slot = assignments.path(widget.path("key").asText()).asText("").trim();
            String region = slotRegion(slot);
            if (region == null) {
                region = roleRegion(widget.path("role").asText(""));
            }
            master |= "master".equals(region);
            detail |= "detail".equals(region);
        }
        return master && detail;
    }

    private String resolvePlanLayoutPresetId(JsonNode plan) {
        String layoutPreset = plan.path("layoutPreset").asText("").trim();
        if (presetCategory(layoutPreset) != null) {
            return layoutPreset;
        }
        String presetFamily = plan.path("layoutPresetOptions").path("presetFamily").asText("").trim();
        return presetCategory(presetFamily) == null ? null : presetFamily;
    }

    private String presetCategory(String presetId) {
        return switch (presetId == null ? "" : presetId) {
            case "analytics-overview", "kpi-plus-table" -> "analytics";
            case MASTER_DETAIL_PRESET_ID -> "master-detail";
            case "ops-monitoring" -> "operations";
            default -> null;
        };
    }

    private List<SlotDefinition> presetSlots(String presetId) {
        return switch (presetId == null ? "" : presetId) {
            case "analytics-overview" -> List.of(
                    new SlotDefinition("hero", 1),
                    new SlotDefinition("kpis", null),
                    new SlotDefinition("primary-chart", 1),
                    new SlotDefinition("secondary-chart-a", 1),
                    new SlotDefinition("secondary-chart-b", 1),
                    new SlotDefinition("detail-table", 1));
            case "kpi-plus-table" -> List.of(
                    new SlotDefinition("filters", 1),
                    new SlotDefinition("kpis", null),
                    new SlotDefinition("detail-table", 1),
                    new SlotDefinition("aux-chart-a", 1),
                    new SlotDefinition("aux-chart-b", 1));
            case "ops-monitoring" -> List.of(
                    new SlotDefinition("status-cards", null),
                    new SlotDefinition("timeline-a", 1),
                    new SlotDefinition("timeline-b", 1),
                    new SlotDefinition("queue", 1),
                    new SlotDefinition("alerts", 1));
            default -> List.of(
                    new SlotDefinition("master", 1),
                    new SlotDefinition("detail-kpis", null),
                    new SlotDefinition("detail-chart-a", 1),
                    new SlotDefinition("detail-chart-b", 1),
                    new SlotDefinition("detail-table", 1));
        };
    }

    private MasterDetailAnalysis analyzeMasterDetailPlan(JsonNode plan) {
        if (plan == null || !plan.isObject() || !isMasterDetailPlan(plan)) {
            return MasterDetailAnalysis.notApplicable(objectMapper.createObjectNode());
        }

        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        List<CompilerDiagnostic> errors = new ArrayList<>();
        ObjectNode explicitAssignments = plan.path("slotAssignments").isObject()
                ? (ObjectNode) plan.path("slotAssignments").deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode slotAssignments = explicitAssignments.deepCopy();
        String resolvedPresetId = resolvePlanLayoutPresetId(plan);
        String layoutPreset = plan.path("layoutPreset").asText("").trim();
        String presetFamily = plan.path("layoutPresetOptions").path("presetFamily").asText("").trim();
        if (presetCategory(layoutPreset) == null
                && !presetFamily.isBlank()
                && presetCategory(presetFamily) == null) {
            errors.add(CompilerDiagnostic.error(
                    "master-detail-preset-family-unknown",
                    "layoutPresetOptions.presetFamily"));
        }

        String effectivePresetId = resolvedPresetId == null ? MASTER_DETAIL_PRESET_ID : resolvedPresetId;
        List<SlotDefinition> presetSlots = presetSlots(effectivePresetId);
        Set<String> knownSlots = new HashSet<>();
        presetSlots.stream().map(SlotDefinition::id).forEach(knownSlots::add);
        Map<String, List<String>> occupiedSlots = new HashMap<>();
        List<MasterDetailWidget> widgets = new ArrayList<>();

        int widgetIndex = 0;
        for (JsonNode widget : plan.path("widgets")) {
            String widgetKey = widget.path("key").asText();
            String explicitSlot = explicitAssignments.path(widgetKey).asText("").trim();
            String semanticRoleRegion = roleRegion(widget.path("role").asText(""));
            String explicitSlotRegion = slotRegion(explicitSlot);
            if (!explicitSlot.isBlank() && !knownSlots.contains(explicitSlot)) {
                errors.add(CompilerDiagnostic.error(
                        "master-detail-slot-unknown",
                        "slotAssignments." + widgetKey));
            }
            if (semanticRoleRegion != null
                    && explicitSlotRegion != null
                    && !semanticRoleRegion.equals(explicitSlotRegion)) {
                errors.add(CompilerDiagnostic.error(
                        "master-detail-role-slot-conflict",
                        "slotAssignments." + widgetKey));
            }
            String region = explicitSlotRegion != null
                    ? explicitSlotRegion
                    : semanticRoleRegion != null ? semanticRoleRegion : "supporting";
            if (explicitSlotRegion == null && semanticRoleRegion == null) {
                diagnostics.add(CompilerDiagnostic.warning(
                        "master-detail-widget-role-fallback",
                        "widgets." + widgetIndex + ".role"));
            }
            widgets.add(new MasterDetailWidget(
                    widget,
                    widgetIndex,
                    region,
                    explicitSlot.isBlank() ? null : explicitSlot));
            widgetIndex++;
        }

        if (resolvedPresetId != null && !"master-detail".equals(presetCategory(resolvedPresetId))) {
            errors.add(CompilerDiagnostic.error(
                    "master-detail-preset-conflict",
                    plan.path("layoutPresetOptions").path("presetFamily").isTextual()
                            ? "layoutPresetOptions.presetFamily"
                            : "layoutPreset"));
        }

        List<MasterDetailWidget> masters = region(widgets, "master");
        List<MasterDetailWidget> details = region(widgets, "detail");
        if (masters.isEmpty()) {
            errors.add(CompilerDiagnostic.error("master-detail-master-required", "widgets"));
        } else if (masters.size() > 1) {
            errors.add(CompilerDiagnostic.error("master-detail-master-ambiguous", "widgets"));
        }
        if (details.isEmpty()) {
            errors.add(CompilerDiagnostic.error("master-detail-detail-required", "widgets"));
        }

        for (MasterDetailWidget item : widgets) {
            if (item.slot() == null) {
                continue;
            }
            occupiedSlots.computeIfAbsent(item.slot(), ignored -> new ArrayList<>())
                    .add(item.widget().path("key").asText());
        }
        for (SlotDefinition slot : presetSlots) {
            int occupants = occupiedSlots.getOrDefault(slot.id(), List.of()).size();
            if (slot.maxItems() != null && occupants > slot.maxItems()) {
                errors.add(CompilerDiagnostic.error(
                        "master-detail-slot-cardinality-exceeded",
                        "slotAssignments"));
            }
        }

        for (MasterDetailWidget item : widgets) {
            if (item.slot() != null || !"detail".equals(item.region())) {
                continue;
            }
            String role = item.widget().path("role").asText("").trim();
            String selectedSlot = MASTER_DETAIL_DETAIL_SLOTS.contains(role)
                            && knownSlots.contains(role)
                            && !occupiedSlots.containsKey(role)
                    ? role
                    : MASTER_DETAIL_DETAIL_SLOTS.stream()
                            .filter(knownSlots::contains)
                            .filter(candidate -> !occupiedSlots.containsKey(candidate))
                            .findFirst()
                            .orElse(null);
            if (selectedSlot == null) {
                diagnostics.add(CompilerDiagnostic.warning(
                        "master-detail-detail-slot-fallback",
                        "widgets." + item.index() + ".role"));
                continue;
            }
            slotAssignments.put(item.widget().path("key").asText(), selectedSlot);
            item.slot(selectedSlot);
            occupiedSlots.put(selectedSlot, new ArrayList<>(List.of(item.widget().path("key").asText())));
        }
        MasterDetailWidget master = masters.isEmpty() ? null : masters.get(0);
        if (master != null && master.slot() == null && knownSlots.contains("master")) {
            slotAssignments.put(master.widget().path("key").asText(), "master");
            master.slot("master");
        }

        return new MasterDetailAnalysis(
                true,
                widgets,
                slotAssignments,
                List.copyOf(diagnostics),
                List.copyOf(errors));
    }

    private String slotRegion(String slot) {
        String resolved = slot == null ? "" : slot.trim();
        if ("master".equals(resolved)) {
            return "master";
        }
        if (Set.of("detail-table", "detail-chart-a", "detail-chart-b", "detail-kpis")
                .contains(resolved)) {
            return "detail";
        }
        return null;
    }

    private String roleRegion(String role) {
        String resolved = role == null ? "" : role.trim();
        if ("master".equals(resolved)) {
            return "master";
        }
        if (Set.of("detail", "detail-table", "detail-chart-a", "detail-chart-b", "detail-kpis")
                .contains(resolved)) {
            return "detail";
        }
        if ("filters".equals(resolved)) {
            return "filters";
        }
        if ("actions".equals(resolved)) {
            return "actions";
        }
        return "supporting".equals(resolved) ? "supporting" : null;
    }

    private ObjectNode neutralCanvas(JsonNode widgets) {
        ObjectNode canvas = objectMapper.createObjectNode();
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "80px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        ObjectNode items = canvas.putObject("items");
        int row = 1;
        for (JsonNode widget : widgets) {
            ObjectNode item = items.putObject(widget.path("key").asText());
            item.put("col", 1);
            item.put("row", row);
            item.put("colSpan", 12);
            item.put("rowSpan", 4);
            row += 4;
        }
        return canvas;
    }

    private ObjectNode materializeMasterDetailDeviceLayouts(
            List<MasterDetailWidget> semantics,
            ObjectNode desktopCanvas) {
        ObjectNode layouts = objectMapper.createObjectNode();
        layouts.putObject("desktop").set("canvas", canvasVariant(desktopCanvas));
        layouts.putObject("tablet").set("canvas", canvasVariant(masterDetailCanvas(semantics, 6, "tablet")));
        layouts.putObject("mobile").set("canvas", canvasVariant(masterDetailCanvas(semantics, 1, "mobile")));
        return layouts;
    }

    private ObjectNode canvasVariant(JsonNode canvas) {
        ObjectNode variant = objectMapper.createObjectNode();
        copyIfPresent(canvas, variant, "columns");
        copyIfPresent(canvas, variant, "rowUnit");
        copyIfPresent(canvas, variant, "gap");
        copyIfPresent(canvas, variant, "autoRows");
        copyIfPresent(canvas, variant, "collisionPolicy");
        copyIfPresent(canvas, variant, "items");
        return variant;
    }

    private ObjectNode masterDetailCanvas(
            List<MasterDetailWidget> semantics,
            int columns,
            String device) {
        ObjectNode items = objectMapper.createObjectNode();
        List<MasterDetailWidget> filters = region(semantics, "filters");
        List<MasterDetailWidget> actions = region(semantics, "actions");
        List<MasterDetailWidget> masters = region(semantics, "master");
        List<MasterDetailWidget> details = region(semantics, "detail");
        List<MasterDetailWidget> supporting = region(semantics, "supporting");
        int row = 1;
        if ("desktop".equals(device) && !filters.isEmpty() && !actions.isEmpty()) {
            int filterEnd = placeHorizontal(items, filters, row, 1, 8, device);
            int actionEnd = placeHorizontal(items, actions, row, 9, 4, device);
            row = Math.max(filterEnd, actionEnd);
        } else {
            row = placeStacked(items, filters, row, columns, device);
            row = placeStacked(items, actions, row, columns, device);
        }
        if ("desktop".equals(device)) {
            int contentRow = row;
            int detailRow = contentRow;
            for (MasterDetailWidget item : details) {
                int rowSpan = masterDetailRowSpan(item, device);
                putCanvasItem(items, item.widget(), 5, detailRow, 8, rowSpan);
                detailRow += rowSpan;
            }
            int contentHeight = Math.max(8, detailRow - contentRow);
            for (MasterDetailWidget item : masters) {
                putCanvasItem(items, item.widget(), 1, contentRow, 4, contentHeight);
            }
            row = contentRow + contentHeight;
        } else {
            row = placeStacked(items, masters, row, columns, device);
            row = placeStacked(items, details, row, columns, device);
        }
        placeStacked(items, supporting, row, columns, device);
        return defaultCanvas(items, columns, device);
    }

    private List<MasterDetailWidget> region(List<MasterDetailWidget> items, String region) {
        return items.stream().filter(item -> region.equals(item.region())).toList();
    }

    private int placeHorizontal(
            ObjectNode items,
            List<MasterDetailWidget> semantics,
            int row,
            int col,
            int colSpan,
            String device) {
        int nextRow = row;
        for (MasterDetailWidget item : semantics) {
            int rowSpan = masterDetailRowSpan(item, device);
            putCanvasItem(items, item.widget(), col, nextRow, colSpan, rowSpan);
            nextRow += rowSpan;
        }
        return nextRow;
    }

    private int placeStacked(
            ObjectNode items,
            List<MasterDetailWidget> semantics,
            int row,
            int columns,
            String device) {
        int nextRow = row;
        for (MasterDetailWidget item : semantics) {
            int rowSpan = masterDetailRowSpan(item, device);
            putCanvasItem(items, item.widget(), 1, nextRow, columns, rowSpan);
            nextRow += rowSpan;
        }
        return nextRow;
    }

    private int masterDetailRowSpan(MasterDetailWidget item, String device) {
        if (Set.of("filters", "actions").contains(item.region())) {
            return 2;
        }
        if ("master".equals(item.region())) {
            return "desktop".equals(device) ? 8 : 6;
        }
        if ("detail-kpis".equals(item.slot())) {
            return 2;
        }
        if ("detail-chart-a".equals(item.slot()) || "detail-chart-b".equals(item.slot())) {
            return 5;
        }
        if ("detail-table".equals(item.slot()) || "detail".equals(item.region())) {
            return "mobile".equals(device) ? 6 : 7;
        }
        return 4;
    }

    private void putCanvasItem(
            ObjectNode items,
            JsonNode widget,
            int col,
            int row,
            int colSpan,
            int rowSpan) {
        ObjectNode item = items.putObject(widget.path("key").asText());
        item.put("col", col);
        item.put("row", row);
        item.put("colSpan", colSpan);
        item.put("rowSpan", rowSpan);
    }

    private ObjectNode defaultCanvas(ObjectNode items, int columns, String device) {
        ObjectNode canvas = objectMapper.createObjectNode();
        canvas.put("mode", "grid");
        canvas.put("columns", columns);
        canvas.put("rowUnit", switch (device) {
            case "tablet" -> "72px";
            case "mobile" -> "64px";
            default -> "80px";
        });
        canvas.put("gap", "desktop".equals(device) ? "16px" : "12px");
        canvas.put("autoRows", "fixed");
        canvas.set("items", items);
        return canvas;
    }

    private record UiCompositionLayoutMaterialization(
            JsonNode layoutPresetOptions,
            JsonNode canvas,
            JsonNode deviceLayouts,
            JsonNode slotAssignments) {}

    private record SlotDefinition(String id, Integer maxItems) {}

    private record MasterDetailAnalysis(
            boolean masterDetail,
            List<MasterDetailWidget> widgets,
            ObjectNode slotAssignments,
            List<CompilerDiagnostic> diagnostics,
            List<CompilerDiagnostic> errors) {

        static MasterDetailAnalysis notApplicable(ObjectNode slotAssignments) {
            return new MasterDetailAnalysis(
                    false,
                    List.of(),
                    slotAssignments,
                    List.of(),
                    List.of());
        }
    }

    record CompilerDiagnostic(String code, String path, String severity) {
        static CompilerDiagnostic error(String code, String path) {
            return new CompilerDiagnostic(code, path, "error");
        }

        static CompilerDiagnostic warning(String code, String path) {
            return new CompilerDiagnostic(code, path, "warning");
        }
    }

    private static final class MasterDetailWidget {
        private final JsonNode widget;
        private final int index;
        private final String region;
        private String slot;

        MasterDetailWidget(JsonNode widget, int index, String region, String slot) {
            this.widget = widget;
            this.index = index;
            this.region = region;
            this.slot = slot;
        }

        JsonNode widget() {
            return widget;
        }

        int index() {
            return index;
        }

        String region() {
            return region;
        }

        String slot() {
            return slot;
        }

        void slot(String slot) {
            this.slot = slot;
        }
    }

    private ObjectNode linkedOutputs(JsonNode widget, String widgetKey, JsonNode bindings) {
        ObjectNode outputs = widget.path("outputs") instanceof ObjectNode declared
                ? declared.deepCopy()
                : objectMapper.createObjectNode();
        if (!bindings.isArray()) {
            return outputs;
        }
        for (JsonNode binding : bindings) {
            JsonNode endpoint = binding.path("from");
            if ("component-port".equals(endpoint.path("kind").asText())
                    && "output".equals(endpoint.path("direction").asText())
                    && widgetKey.equals(endpoint.path("widget").asText())
                    && (!endpoint.path("nestedPath").isArray() || endpoint.path("nestedPath").isEmpty())) {
                outputs.putIfAbsent(endpoint.path("port").asText(), objectMapper.getNodeFactory().textNode("emit"));
            }
        }
        return outputs;
    }

    private ObjectNode compileBinding(JsonNode binding) {
        ObjectNode link = objectMapper.createObjectNode();
        link.put("id", binding.path("id").asText());
        link.set("from", compileEndpoint(binding.path("from"), true));
        link.set("to", compileEndpoint(binding.path("to"), false));
        copyIfPresent(binding, link, "intent");
        copyIfPresent(binding, link, "condition");
        if (binding.path("transform").isObject()) {
            link.set("transform", compileTransform(binding.path("transform")));
        }
        copyIfPresent(binding, link, "policy");
        copyIfPresent(binding, link, "metadata");
        return link;
    }

    private ObjectNode compileEndpoint(JsonNode endpoint, boolean sourceEndpoint) {
        ObjectNode compiled = objectMapper.createObjectNode();
        String kind = endpoint.path("kind").asText();
        compiled.put("kind", kind);
        ObjectNode ref = compiled.putObject("ref");
        if ("component-port".equals(kind)) {
            ref.put("widget", endpoint.path("widget").asText());
            ref.put("port", endpoint.path("port").asText());
            ref.put("direction", endpoint.path("direction").asText());
            copyIfPresent(endpoint, ref, "nestedPath");
        } else if ("global-action".equals(kind)) {
            ref.put("actionId", endpoint.path("actionId").asText());
            if (!sourceEndpoint) {
                copyIfDefined(endpoint, ref, "payload");
                copyIfDefined(endpoint, ref, "payloadExpr");
                copyIfDefined(endpoint, ref, "meta");
            }
        } else {
            ref.put("path", endpoint.path("path").asText());
            copyIfPresent(endpoint, ref, "layer");
        }
        return compiled;
    }

    private ObjectNode compileTransform(JsonNode transform) {
        String kind = transform.path("kind").asText();
        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("version", "2.0");
        compiled.put("phase", "link-propagation");
        if ("query-context".equals(kind)) {
            compiled.put("mode", "object-fragment");
            ObjectNode step = baseTransformStep(compiled, transform, "object-template", true);
            String valuePath = transform.path("valueVar").asText("").trim();
            if (valuePath.isBlank()) {
                valuePath = "payload";
            }
            step.putObject("config")
                    .putObject("template")
                    .putObject("filters")
                    .put(transform.path("field").asText(), "${" + valuePath + "}");
            putQueryContextOutput(step.putObject("output"));
            putQueryContextOutput(compiled.putObject("output"));
            return compiled;
        }
        if ("template".equals(kind)) {
            JsonNode template = transform.path("template");
            String stepKind;
            String mode;
            if (template.isArray()) {
                stepKind = "array-template";
                mode = "collection";
            } else if (template.isObject()) {
                stepKind = "object-template";
                mode = "object-fragment";
            } else {
                stepKind = "template";
                mode = "single-value";
            }
            compiled.put("mode", mode);
            ObjectNode step = baseTransformStep(compiled, transform, stepKind, true);
            step.putObject("config").set("template", template.deepCopy());
            return compiled;
        }
        compiled.put("mode", "single-value");
        if ("constant".equals(kind)) {
            ObjectNode step = baseTransformStep(compiled, transform, "constant", false);
            step.putObject("config").set("value", transform.path("value").deepCopy());
            return compiled;
        }
        ObjectNode step = baseTransformStep(compiled, transform, "pick-path", true);
        step.putObject("config").put("path", transform.path("path").asText());
        return compiled;
    }

    private void putQueryContextOutput(ObjectNode output) {
        output.put("semanticKind", "query-context");
        output.put("stableShape", true);
    }

    private ObjectNode baseTransformStep(
            ObjectNode compiled,
            JsonNode transform,
            String kind,
            boolean includeInput) {
        ObjectNode step = compiled.putArray("steps").addObject();
        step.put("id", transform.path("id").asText());
        step.put("kind", kind);
        step.put("phase", "link-propagation");
        if (includeInput) {
            String inputSource = transform.path("inputSource").asText("").trim();
            step.putObject("input").put("source", inputSource.isBlank() ? "event" : inputSource);
        }
        return step;
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.path(field);
        if (!value.isMissingNode() && !value.isNull()) {
            target.set(field, value.deepCopy());
        }
    }

    private void copyIfDefined(JsonNode source, ObjectNode target, String field) {
        if (source.has(field)) {
            target.set(field, source.get(field).deepCopy());
        }
    }

    private void putDefault(ObjectNode node, String field, String value) {
        if (node.path(field).asText("").isBlank()) {
            node.put(field, value);
        }
    }

    private void removeText(ArrayNode values, String value) {
        for (int index = values.size() - 1; index >= 0; index--) {
            if (value.equals(values.path(index).asText())) {
                values.remove(index);
            }
        }
    }

    private void addTextOnce(ArrayNode values, String value) {
        for (JsonNode existing : values) {
            if (value.equals(existing.asText())) {
                return;
            }
        }
        values.add(value);
    }

    record CompileResult(
            boolean valid,
            ObjectNode compiledFormPatch,
            List<String> failureCodes,
            List<CompilerDiagnostic> diagnostics) {}
}
