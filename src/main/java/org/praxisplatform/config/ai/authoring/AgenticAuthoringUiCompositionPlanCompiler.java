package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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

    private final ObjectMapper objectMapper;

    AgenticAuthoringUiCompositionPlanCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    CompileResult compile(JsonNode plan, JsonNode baseCompiledFormPatch) {
        List<String> failures = validate(plan);
        if (!failures.isEmpty()) {
            return new CompileResult(false, objectMapper.createObjectNode(), List.copyOf(failures));
        }

        ObjectNode compiledFormPatch = baseCompiledFormPatch instanceof ObjectNode existing
                ? existing.deepCopy()
                : objectMapper.createObjectNode();
        putDefault(compiledFormPatch, "version", "1.0.0");
        putDefault(compiledFormPatch, "profileId", "ui-composition-plan");
        putDefault(compiledFormPatch, "targetComponentId", "praxis-dynamic-page-builder");
        putDefault(compiledFormPatch, "builderVersion", "config-ui-composition-plan-compiler@1.1.0");
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
        patch.set("page", compilePage(plan));
        ArrayNode warnings = compiledFormPatch.path("warnings") instanceof ArrayNode existing
                ? existing
                : compiledFormPatch.putArray("warnings");
        removeText(warnings, LEGACY_CLIENT_COMPILE_WARNING);
        addTextOnce(warnings, COMPILED_WARNING);
        String compiledPageFailure = AgenticAuthoringCompiledPagePatchValidator
                .terminalApplyBlockReason(compiledFormPatch);
        if (!compiledPageFailure.isBlank()) {
            return new CompileResult(
                    false,
                    objectMapper.createObjectNode(),
                    List.of("ui-composition-plan-compiled-page-invalid:" + compiledPageFailure));
        }
        return new CompileResult(true, compiledFormPatch, List.of());
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
                validateEndpoint(binding.path("from"), widgetKeys, false, failures);
                validateEndpoint(binding.path("to"), widgetKeys, true, failures);
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
                    validateEndpoint(source, widgetKeys, false, failures);
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
            boolean allowGlobalAction,
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
        if (allowGlobalAction && "global-action".equals(kind)) {
            if (!isNonBlankText(endpoint.get("actionId"))) {
                failures.add("ui-composition-plan-endpoint-global-action-id-required");
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

    private ObjectNode compilePage(JsonNode plan) {
        ObjectNode page = objectMapper.createObjectNode();
        copyIfPresent(plan, page, "i18n");
        copyIfPresent(plan, page, "context");
        copyIfPresent(plan, page, "layout");
        copyIfPresent(plan, page, "layoutPreset");
        copyIfPresent(plan, page, "layoutPresetOptions");
        if (plan.path("canvas").isObject()) {
            page.set("canvas", plan.path("canvas").deepCopy());
        } else if (shouldMaterializeDefaultCanvas(plan)) {
            page.set("canvas", defaultCanvas(plan));
        }
        copyIfPresent(plan, page, "deviceLayouts");
        copyIfPresent(plan, page, "grouping");
        copyIfPresent(plan, page, "slotAssignments");
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

    private boolean shouldMaterializeDefaultCanvas(JsonNode plan) {
        boolean hasExplicitLayout = plan.path("layout").isObject();
        boolean hasLayoutPreset = !plan.path("layoutPreset").asText("").trim().isBlank();
        boolean hasMasterDetailRoles = hasRole(plan.path("widgets"), "master")
                && hasRole(plan.path("widgets"), "detail");
        return !hasExplicitLayout || hasLayoutPreset || hasMasterDetailRoles;
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

    private ObjectNode defaultCanvas(JsonNode plan) {
        ObjectNode canvas = objectMapper.createObjectNode();
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "80px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        ObjectNode items = canvas.putObject("items");
        boolean masterDetail = plan.path("layoutPreset").asText("").toLowerCase(Locale.ROOT).contains("master-detail")
                || hasRole(plan.path("widgets"), "master") && hasRole(plan.path("widgets"), "detail");
        List<JsonNode> orderedWidgets = new ArrayList<>();
        if (masterDetail) {
            JsonNode master = firstWidgetWithRole(plan.path("widgets"), "master");
            orderedWidgets.add(master == null ? plan.path("widgets").path(0) : master);
            for (JsonNode widget : plan.path("widgets")) {
                if (!widget.path("key").asText().equals(orderedWidgets.get(0).path("key").asText())) {
                    orderedWidgets.add(widget);
                }
            }
        } else {
            plan.path("widgets").forEach(orderedWidgets::add);
        }
        int row = 1;
        for (JsonNode widget : orderedWidgets) {
            int rowSpan = preferredRowSpan(widget);
            ObjectNode item = items.putObject(widget.path("key").asText());
            item.put("col", 1);
            item.put("row", row);
            item.put("colSpan", 12);
            item.put("rowSpan", rowSpan);
            row += rowSpan;
        }
        return canvas;
    }

    private boolean hasRole(JsonNode widgets, String role) {
        return firstWidgetWithRole(widgets, role) != null;
    }

    private JsonNode firstWidgetWithRole(JsonNode widgets, String role) {
        for (JsonNode widget : widgets) {
            if (role.equals(widget.path("role").asText(""))) {
                return widget;
            }
        }
        return null;
    }

    private int preferredRowSpan(JsonNode widget) {
        String componentId = widget.path("componentId").asText("").toLowerCase(Locale.ROOT);
        String role = widget.path("role").asText("");
        if (componentId.contains("dynamic-form") || "detail".equals(role)) {
            return 8;
        }
        if (componentId.contains("table") || componentId.contains("list")) {
            return 7;
        }
        if (componentId.contains("chart")) {
            return 5;
        }
        if (componentId.contains("kpi") || componentId.contains("filter")) {
            return 2;
        }
        if (componentId.contains("rich-content")) {
            return 3;
        }
        return 4;
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
        link.set("from", compileEndpoint(binding.path("from")));
        link.set("to", compileEndpoint(binding.path("to")));
        copyIfPresent(binding, link, "intent");
        copyIfPresent(binding, link, "condition");
        if (binding.path("transform").isObject()) {
            link.set("transform", compileTransform(binding.path("transform")));
        }
        copyIfPresent(binding, link, "policy");
        copyIfPresent(binding, link, "metadata");
        return link;
    }

    private ObjectNode compileEndpoint(JsonNode endpoint) {
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
            copyIfDefined(endpoint, ref, "payload");
            copyIfDefined(endpoint, ref, "payloadExpr");
            copyIfDefined(endpoint, ref, "meta");
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

    record CompileResult(boolean valid, ObjectNode compiledFormPatch, List<String> failureCodes) {}
}
