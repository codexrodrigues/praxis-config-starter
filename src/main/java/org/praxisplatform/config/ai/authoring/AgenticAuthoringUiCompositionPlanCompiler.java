package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        putDefault(compiledFormPatch, "builderVersion", "config-ui-composition-plan-compiler@1.0.0");
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
        return failures;
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
        copyIfPresent(plan, page, "layoutPreset");
        copyIfPresent(plan, page, "layoutPresetOptions");
        if (plan.path("canvas").isObject()) {
            page.set("canvas", plan.path("canvas").deepCopy());
        } else {
            page.set("canvas", defaultCanvas(plan));
        }
        copyIfPresent(plan, page, "deviceLayouts");
        copyIfPresent(plan, page, "grouping");
        copyIfPresent(plan, page, "slotAssignments");
        copyIfPresent(plan, page, "state");
        copyIfPresent(plan, page, "themePreset");

        ArrayNode widgets = page.putArray("widgets");
        JsonNode bindings = plan.path("bindings");
        for (JsonNode plannedWidget : plan.path("widgets")) {
            ObjectNode widget = widgets.addObject();
            String widgetKey = plannedWidget.path("key").asText();
            widget.put("key", widgetKey);
            ObjectNode definition = widget.putObject("definition");
            definition.put("id", plannedWidget.path("componentId").asText());
            copyIfPresent(plannedWidget, definition, "bindingOrder");
            definition.set("inputs", plannedWidget.path("inputs").isObject()
                    ? plannedWidget.path("inputs").deepCopy()
                    : objectMapper.createObjectNode());
            definition.set("outputs", linkedOutputs(plannedWidget, widgetKey, bindings));
        }

        ObjectNode composition = page.putObject("composition");
        composition.put("version", "1.0.0");
        ArrayNode links = composition.putArray("links");
        if (bindings.isArray()) {
            for (JsonNode binding : bindings) {
                links.add(compileBinding(binding));
            }
        }
        return page;
    }

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
