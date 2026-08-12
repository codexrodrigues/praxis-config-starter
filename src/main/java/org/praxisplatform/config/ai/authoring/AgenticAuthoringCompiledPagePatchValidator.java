package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Central structural gate shared by terminal publication and page apply. */
final class AgenticAuthoringCompiledPagePatchValidator {

    private static final Set<String> LINK_INTENTS = Set.of(
            "event-propagation",
            "state-write",
            "state-read",
            "command-dispatch",
            "selection-sync",
            "data-projection",
            "status-propagation");
    private static final Set<String> NESTED_PATH_KINDS = Set.of(
            "widget", "tab", "nav", "link", "expansion", "panel", "stepper", "step", "slot", "group");
    private static final Set<String> STABLE_TRANSFORM_KINDS = Set.of(
            "identity",
            "constant",
            "pick-path",
            "template",
            "object-template",
            "array-template",
            "coalesce",
            "merge-objects",
            "select-case");
    private static final Set<String> SEMANTIC_KINDS = Set.of(
            "event",
            "value",
            "selection",
            "collection",
            "query-context",
            "view-context",
            "config-fragment",
            "status",
            "diagnostic");

    private AgenticAuthoringCompiledPagePatchValidator() {}

    static String terminalApplyBlockReason(JsonNode compiledFormPatch) {
        if (compiledFormPatch == null || !compiledFormPatch.isObject()) {
            return "compiled-form-patch-missing";
        }
        JsonNode page = compiledFormPatch.path("patch").path("page");
        if (!page.isObject()) {
            return "compiled-page-patch-missing";
        }
        JsonNode widgets = page.path("widgets");
        if (!widgets.isArray()) {
            return "compiled-page-widgets-missing";
        }
        if (widgets.isEmpty()) {
            return "compiled-page-widgets-empty";
        }
        Set<String> widgetKeys = new HashSet<>();
        for (JsonNode widget : widgets) {
            String widgetFailure = widgetFailure(widget, widgetKeys);
            if (!widgetFailure.isBlank()) {
                return widgetFailure;
            }
        }
        return pageStructureFailure(page, widgetKeys);
    }

    private static String widgetFailure(JsonNode widget, Set<String> widgetKeys) {
        if (!widget.isObject()) {
            return "compiled-page-widget-object-required";
        }
        String key = text(widget.get("key"));
        if (key.isBlank()) {
            return "compiled-page-widget-key-required";
        }
        if (!widgetKeys.add(key)) {
            return "compiled-page-widget-key-duplicated";
        }
        if (widget.has("className") && !widget.path("className").isTextual()) {
            return "compiled-page-widget-class-name-invalid";
        }
        if (widget.has("shell") && !widget.path("shell").isObject()) {
            return "compiled-page-widget-shell-object-required";
        }
        JsonNode definition = widget.path("definition");
        if (!definition.isObject()) {
            return "compiled-page-widget-definition-required";
        }
        if (text(definition.get("id")).isBlank()) {
            return "compiled-page-widget-component-id-required";
        }
        if (definition.has("childWidgetKey") && text(definition.get("childWidgetKey")).isBlank()) {
            return "compiled-page-widget-child-key-invalid";
        }
        if (definition.has("inputs") && !definition.path("inputs").isObject()) {
            return "compiled-page-widget-inputs-object-required";
        }
        if (definition.has("outputs")) {
            JsonNode outputs = definition.path("outputs");
            if (!outputs.isObject()) {
                return "compiled-page-widget-outputs-object-required";
            }
            for (JsonNode output : outputs) {
                if (output.isTextual()) {
                    if (!"emit".equals(output.textValue())) {
                        return "compiled-page-widget-output-action-invalid";
                    }
                    continue;
                }
                if (!output.isObject()
                        || (output.has("type") && !output.path("type").isTextual())
                        || (output.has("params") && !output.path("params").isObject())) {
                    return "compiled-page-widget-output-action-invalid";
                }
            }
        }
        if (definition.has("bindingOrder") && !isTextArray(definition.path("bindingOrder"))) {
            return "compiled-page-widget-binding-order-invalid";
        }
        return "";
    }

    private static String pageStructureFailure(JsonNode page, Set<String> widgetKeys) {
        if (page.has("layoutPreset") && !page.path("layoutPreset").isTextual()) {
            return "compiled-page-layout-preset-invalid";
        }
        if (page.has("layoutPresetOptions") && !page.path("layoutPresetOptions").isObject()) {
            return "compiled-page-layout-preset-options-object-required";
        }
        if (page.has("themePreset") && !page.path("themePreset").isTextual()) {
            return "compiled-page-theme-preset-invalid";
        }
        if (page.has("state") && !page.path("state").isObject()) {
            return "compiled-page-state-object-required";
        }
        if (page.has("context") && !page.path("context").isObject()) {
            return "compiled-page-context-object-required";
        }
        if (page.has("layout")) {
            String layoutFailure = layoutFailure(page.path("layout"), "compiled-page-layout");
            if (!layoutFailure.isBlank()) return layoutFailure;
        }
        if (page.has("canvas")) {
            String canvasFailure = canvasFailure(page.path("canvas"), false, widgetKeys);
            if (!canvasFailure.isBlank()) return canvasFailure;
        }
        if (page.has("slotAssignments")) {
            JsonNode assignments = page.path("slotAssignments");
            if (!assignments.isObject()) return "compiled-page-slot-assignments-object-required";
            Iterator<Map.Entry<String, JsonNode>> fields = assignments.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> assignment = fields.next();
                if (!widgetKeys.contains(assignment.getKey())) {
                    return "compiled-page-slot-assignment-widget-not-found";
                }
                if (text(assignment.getValue()).isBlank()) {
                    return "compiled-page-slot-assignment-invalid";
                }
            }
        }
        Set<String> groupIds = new HashSet<>();
        if (page.has("grouping")) {
            String groupingFailure = groupingFailure(page.path("grouping"), widgetKeys, groupIds);
            if (!groupingFailure.isBlank()) return groupingFailure;
        }
        if (page.has("deviceLayouts")) {
            String deviceFailure = deviceLayoutsFailure(page.path("deviceLayouts"), widgetKeys, groupIds);
            if (!deviceFailure.isBlank()) return deviceFailure;
        }
        if (page.has("composition")) {
            String compositionFailure = compositionFailure(page.path("composition"), widgetKeys);
            if (!compositionFailure.isBlank()) return compositionFailure;
        }
        return "";
    }

    private static String layoutFailure(JsonNode layout, String prefix) {
        if (!layout.isObject()) return prefix + "-object-required";
        if (layout.has("orientation")
                && !Set.of("vertical", "columns").contains(text(layout.get("orientation")))) {
            return prefix + "-orientation-invalid";
        }
        if (layout.has("columns") && !isPositiveInteger(layout.get("columns"))) {
            return prefix + "-columns-invalid";
        }
        if (layout.has("gap") && !layout.path("gap").isTextual()) {
            return prefix + "-gap-invalid";
        }
        if (layout.has("breakpoints")) {
            JsonNode breakpoints = layout.path("breakpoints");
            if (!breakpoints.isObject()) return prefix + "-breakpoints-object-required";
            Iterator<Map.Entry<String, JsonNode>> fields = breakpoints.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!Set.of("sm", "md", "lg", "xl").contains(entry.getKey())
                        || !isPositiveInteger(entry.getValue())) {
                    return prefix + "-breakpoint-invalid";
                }
            }
        }
        return "";
    }

    private static String canvasFailure(JsonNode canvas, boolean variant, Set<String> widgetKeys) {
        String prefix = variant ? "compiled-page-device-layout-canvas" : "compiled-page-canvas";
        if (!canvas.isObject()) return prefix + "-object-required";
        if (!variant) {
            if (!"grid".equals(text(canvas.get("mode")))) {
                return "compiled-page-canvas-mode-invalid";
            }
            if (!isPositiveInteger(canvas.get("columns"))) {
                return "compiled-page-canvas-columns-invalid";
            }
            if (!canvas.has("items") || !canvas.path("items").isObject()) {
                return "compiled-page-canvas-items-object-required";
            }
        } else if (canvas.has("columns") && !isPositiveInteger(canvas.get("columns"))) {
            return "compiled-page-device-layout-canvas-columns-invalid";
        }
        if (canvas.has("rowUnit") && !canvas.path("rowUnit").isTextual()) {
            return prefix + "-row-unit-invalid";
        }
        if (canvas.has("gap") && !canvas.path("gap").isTextual()) {
            return prefix + "-gap-invalid";
        }
        if (canvas.has("autoRows")
                && !Set.of("fixed", "content").contains(text(canvas.get("autoRows")))) {
            return prefix + "-auto-rows-invalid";
        }
        if (canvas.has("collisionPolicy")
                && !Set.of("block", "swap").contains(text(canvas.get("collisionPolicy")))) {
            return prefix + "-collision-policy-invalid";
        }
        if (!canvas.has("items")) return "";
        JsonNode items = canvas.path("items");
        if (!items.isObject()) return "compiled-page-canvas-items-object-required";
        Iterator<Map.Entry<String, JsonNode>> fields = items.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!widgetKeys.contains(entry.getKey())) {
                return prefix + "-widget-not-found";
            }
            String itemFailure = canvasItemFailure(entry.getValue(), variant, prefix);
            if (!itemFailure.isBlank()) return itemFailure;
        }
        return "";
    }

    private static String canvasItemFailure(JsonNode item, boolean variant, String prefix) {
        if (!item.isObject()) return "compiled-page-canvas-item-object-required";
        if (!variant && (!isPositiveInteger(item.get("col"))
                || !isPositiveInteger(item.get("row"))
                || !isPositiveInteger(item.get("colSpan"))
                || !isPositiveInteger(item.get("rowSpan")))) {
            return "compiled-page-canvas-item-position-invalid";
        }
        for (String field : Set.of("col", "row", "colSpan", "rowSpan")) {
            if (item.has(field) && !isPositiveInteger(item.get(field))) {
                return prefix + "-item-position-invalid";
            }
        }
        if (item.has("zIndex") && !item.path("zIndex").isIntegralNumber()) {
            return prefix + "-item-z-index-invalid";
        }
        if (item.has("hidden") && !item.path("hidden").isBoolean()) {
            return prefix + "-item-hidden-invalid";
        }
        if (item.has("constraints")) {
            String constraintsFailure = canvasConstraintsFailure(item.path("constraints"), prefix);
            if (!constraintsFailure.isBlank()) return constraintsFailure;
        }
        return "";
    }

    private static String canvasConstraintsFailure(JsonNode constraints, String prefix) {
        if (!constraints.isObject()) return prefix + "-item-constraints-object-required";
        for (String field : Set.of("minColSpan", "minRowSpan", "maxColSpan", "maxRowSpan")) {
            if (constraints.has(field) && !isPositiveInteger(constraints.get(field))) {
                return prefix + "-item-constraint-number-invalid";
            }
        }
        for (String field : Set.of("lockPosition", "lockSize")) {
            if (constraints.has(field) && !constraints.path(field).isBoolean()) {
                return prefix + "-item-constraint-boolean-invalid";
            }
        }
        return "";
    }

    private static String groupingFailure(
            JsonNode grouping,
            Set<String> widgetKeys,
            Set<String> groupIds) {
        if (!grouping.isArray()) return "compiled-page-grouping-array-required";
        for (JsonNode group : grouping) {
            if (!group.isObject()) return "compiled-page-group-object-required";
            String id = text(group.get("id"));
            if (id.isBlank()) return "compiled-page-group-id-required";
            if (!groupIds.add(id)) return "compiled-page-group-id-duplicated";
            String kind = text(group.get("kind"));
            if (!Set.of("section", "tabs", "hero", "rail").contains(kind)) {
                return "compiled-page-group-kind-invalid";
            }
            if (group.has("label") && !group.path("label").isTextual()) {
                return "compiled-page-group-label-invalid";
            }
            if ("tabs".equals(kind)) {
                String tabsFailure = groupingTabsFailure(group.path("tabs"), widgetKeys);
                if (!tabsFailure.isBlank()) return tabsFailure;
            } else {
                String refsFailure = widgetReferencesFailure(
                        group.path("widgetKeys"), widgetKeys, "compiled-page-group-widget-keys-invalid");
                if (!refsFailure.isBlank()) return refsFailure;
            }
            if ("section".equals(kind)
                    && group.has("layout")
                    && !Set.of("stack", "grid", "row").contains(text(group.get("layout")))) {
                return "compiled-page-group-section-layout-invalid";
            }
            if ("hero".equals(kind)
                    && group.has("emphasis")
                    && !Set.of("high", "medium").contains(text(group.get("emphasis")))) {
                return "compiled-page-group-hero-emphasis-invalid";
            }
            if ("rail".equals(kind) && !Set.of("left", "right").contains(text(group.get("side")))) {
                return "compiled-page-group-rail-side-invalid";
            }
        }
        return "";
    }

    private static String groupingTabsFailure(JsonNode tabs, Set<String> widgetKeys) {
        if (!tabs.isArray()) return "compiled-page-group-tabs-array-required";
        Set<String> tabIds = new HashSet<>();
        for (JsonNode tab : tabs) {
            String id = tab.isObject() ? text(tab.get("id")) : "";
            if (id.isBlank()
                    || !tabIds.add(id)
                    || text(tab.get("label")).isBlank()) {
                return "compiled-page-group-tab-invalid";
            }
            String refsFailure = widgetReferencesFailure(
                    tab.path("widgetKeys"), widgetKeys, "compiled-page-group-tab-widget-keys-invalid");
            if (!refsFailure.isBlank()) return refsFailure;
        }
        return "";
    }

    private static String deviceLayoutsFailure(
            JsonNode deviceLayouts,
            Set<String> widgetKeys,
            Set<String> groupIds) {
        if (!deviceLayouts.isObject()) return "compiled-page-device-layouts-object-required";
        Iterator<Map.Entry<String, JsonNode>> fields = deviceLayouts.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!Set.of("desktop", "tablet", "mobile").contains(entry.getKey())) {
                return "compiled-page-device-layout-key-invalid";
            }
            JsonNode variant = entry.getValue();
            if (!variant.isObject()) return "compiled-page-device-layout-variant-object-required";
            if (variant.has("layout")) {
                String layoutFailure = layoutFailure(
                        variant.path("layout"), "compiled-page-device-layout-layout");
                if (!layoutFailure.isBlank()) return layoutFailure;
            }
            if (variant.has("canvas")) {
                String canvasFailure = canvasFailure(variant.path("canvas"), true, widgetKeys);
                if (!canvasFailure.isBlank()) return canvasFailure;
            }
            if (variant.has("widgetOverrides")) {
                String overridesFailure = widgetOverridesFailure(variant.path("widgetOverrides"), widgetKeys);
                if (!overridesFailure.isBlank()) return overridesFailure;
            }
            if (variant.has("groupingOverrides")) {
                String overridesFailure = groupingOverridesFailure(
                        variant.path("groupingOverrides"), widgetKeys, groupIds);
                if (!overridesFailure.isBlank()) return overridesFailure;
            }
        }
        return "";
    }

    private static String widgetOverridesFailure(JsonNode overrides, Set<String> widgetKeys) {
        if (!overrides.isObject()) return "compiled-page-device-layout-widget-overrides-object-required";
        Iterator<Map.Entry<String, JsonNode>> fields = overrides.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!widgetKeys.contains(entry.getKey())) {
                return "compiled-page-device-layout-widget-override-not-found";
            }
            JsonNode override = entry.getValue();
            if (!override.isObject()) return "compiled-page-device-layout-widget-override-object-required";
            if (override.has("className") && !override.path("className").isTextual()) {
                return "compiled-page-device-layout-widget-class-name-invalid";
            }
            for (String field : Set.of("span", "order")) {
                if (override.has(field) && !override.path(field).isIntegralNumber()) {
                    return "compiled-page-device-layout-widget-number-invalid";
                }
            }
            if (override.has("hidden") && !override.path("hidden").isBoolean()) {
                return "compiled-page-device-layout-widget-hidden-invalid";
            }
        }
        return "";
    }

    private static String groupingOverridesFailure(
            JsonNode overrides,
            Set<String> widgetKeys,
            Set<String> groupIds) {
        if (!overrides.isArray()) return "compiled-page-device-layout-grouping-overrides-array-required";
        for (JsonNode override : overrides) {
            String id = override.isObject() ? text(override.get("id")) : "";
            if (id.isBlank()) return "compiled-page-device-layout-grouping-override-invalid";
            if (!groupIds.contains(id)) return "compiled-page-device-layout-grouping-override-not-found";
            if (override.has("hidden") && !override.path("hidden").isBoolean()) {
                return "compiled-page-device-layout-grouping-hidden-invalid";
            }
            if (override.has("widgetKeys")) {
                String refsFailure = widgetReferencesFailure(
                        override.path("widgetKeys"),
                        widgetKeys,
                        "compiled-page-device-layout-grouping-widget-keys-invalid");
                if (!refsFailure.isBlank()) return refsFailure;
            }
            if (override.has("tabs")) {
                String tabsFailure = groupingTabsFailure(override.path("tabs"), widgetKeys);
                if (!tabsFailure.isBlank()) return "compiled-page-device-layout-" + tabsFailure.substring(14);
            }
        }
        return "";
    }

    private static String compositionFailure(JsonNode composition, Set<String> widgetKeys) {
        if (!composition.isObject()) return "compiled-page-composition-object-required";
        if (composition.has("version") && !composition.path("version").isTextual()) {
            return "compiled-page-composition-version-invalid";
        }
        if (!composition.has("links")) return "";
        JsonNode links = composition.path("links");
        if (!links.isArray()) return "compiled-page-composition-links-array-required";
        Set<String> linkIds = new HashSet<>();
        for (JsonNode link : links) {
            if (!link.isObject()) return "compiled-page-composition-link-object-required";
            String id = text(link.get("id"));
            if (id.isBlank()) return "compiled-page-composition-link-id-required";
            if (!linkIds.add(id)) return "compiled-page-composition-link-id-duplicated";
            if (!LINK_INTENTS.contains(text(link.get("intent")))) {
                return "compiled-page-composition-link-intent-invalid";
            }
            String fromFailure = endpointFailure(link.path("from"), true, widgetKeys);
            if (!fromFailure.isBlank()) return fromFailure;
            String toFailure = endpointFailure(link.path("to"), false, widgetKeys);
            if (!toFailure.isBlank()) return toFailure;
            if (link.has("condition") && !link.path("condition").isObject() && !link.path("condition").isNull()) {
                return "compiled-page-composition-link-condition-invalid";
            }
            if (link.has("transform")) {
                String transformFailure = transformFailure(link.path("transform"));
                if (!transformFailure.isBlank()) return transformFailure;
            }
            if (link.has("policy")) {
                String policyFailure = linkPolicyFailure(link.path("policy"));
                if (!policyFailure.isBlank()) return policyFailure;
            }
            if (link.has("metadata")) {
                String metadataFailure = linkMetadataFailure(link.path("metadata"));
                if (!metadataFailure.isBlank()) return metadataFailure;
            }
        }
        return "";
    }

    private static String endpointFailure(JsonNode endpoint, boolean source, Set<String> widgetKeys) {
        String position = source ? "source" : "target";
        if (!endpoint.isObject()) return "compiled-page-composition-link-" + position + "-object-required";
        String kind = text(endpoint.get("kind"));
        JsonNode ref = endpoint.path("ref");
        if (!ref.isObject()) return "compiled-page-composition-link-" + position + "-ref-object-required";
        return switch (kind) {
            case "component-port" -> componentEndpointFailure(ref, source, widgetKeys);
            case "state" -> stateEndpointFailure(ref, source);
            case "global-action" -> globalActionEndpointFailure(ref);
            default -> "compiled-page-composition-link-" + position + "-kind-invalid";
        };
    }

    private static String componentEndpointFailure(JsonNode ref, boolean source, Set<String> widgetKeys) {
        String widget = text(ref.get("widget"));
        if (widget.isBlank()) return "compiled-page-composition-component-widget-required";
        if (!widgetKeys.contains(widget)) return "compiled-page-composition-component-widget-not-found";
        if (text(ref.get("port")).isBlank()) return "compiled-page-composition-component-port-required";
        String expectedDirection = source ? "output" : "input";
        if (!expectedDirection.equals(text(ref.get("direction")))) {
            return "compiled-page-composition-component-direction-invalid";
        }
        for (String field : Set.of("componentType", "bindingPath")) {
            if (ref.has(field) && text(ref.get(field)).isBlank()) {
                return "compiled-page-composition-component-" + field + "-invalid";
            }
        }
        if (ref.has("nestedPath")) {
            JsonNode nestedPath = ref.path("nestedPath");
            if (!nestedPath.isArray() || nestedPath.isEmpty()) {
                return "compiled-page-composition-component-nested-path-invalid";
            }
            for (JsonNode segment : nestedPath) {
                if (!segment.isObject() || !NESTED_PATH_KINDS.contains(text(segment.get("kind")))) {
                    return "compiled-page-composition-component-nested-segment-invalid";
                }
                for (String field : Set.of("id", "key", "componentType")) {
                    if (segment.has(field) && text(segment.get(field)).isBlank()) {
                        return "compiled-page-composition-component-nested-segment-invalid";
                    }
                }
                if (segment.has("index")
                        && (!segment.path("index").isIntegralNumber() || segment.path("index").intValue() < 0)) {
                    return "compiled-page-composition-component-nested-segment-invalid";
                }
            }
            JsonNode terminal = nestedPath.path(nestedPath.size() - 1);
            if (!"widget".equals(text(terminal.get("kind"))) || text(terminal.get("key")).isBlank()) {
                return "compiled-page-composition-component-nested-terminal-widget-required";
            }
            if (ref.has("bindingPath")) {
                return "compiled-page-composition-component-nested-binding-path-conflict";
            }
        }
        return "";
    }

    private static String stateEndpointFailure(JsonNode ref, boolean source) {
        if (text(ref.get("path")).isBlank()) return "compiled-page-composition-state-path-required";
        String layer = ref.has("layer") ? text(ref.get("layer")) : "values";
        if (!Set.of("values", "derived", "transient").contains(layer)) {
            return "compiled-page-composition-state-layer-invalid";
        }
        if (ref.has("writable") && !ref.path("writable").isBoolean()) {
            return "compiled-page-composition-state-writable-invalid";
        }
        if (!source && ("derived".equals(layer) || (ref.has("writable") && !ref.path("writable").booleanValue()))) {
            return "compiled-page-composition-state-target-read-only";
        }
        return "";
    }

    private static String globalActionEndpointFailure(JsonNode ref) {
        if (text(ref.get("actionId")).isBlank()) {
            return "compiled-page-composition-global-action-id-required";
        }
        if (ref.has("payloadExpr") && !ref.path("payloadExpr").isTextual()) {
            return "compiled-page-composition-global-action-payload-expr-invalid";
        }
        if (ref.has("meta") && !ref.path("meta").isObject()) {
            return "compiled-page-composition-global-action-meta-object-required";
        }
        return "";
    }

    private static String transformFailure(JsonNode transform) {
        if (!transform.isObject()) return "compiled-page-composition-transform-object-required";
        if (!"link-propagation".equals(text(transform.get("phase")))) {
            return "compiled-page-composition-transform-phase-invalid";
        }
        if (!Set.of("single-value", "object-fragment", "collection").contains(text(transform.get("mode")))) {
            return "compiled-page-composition-transform-mode-invalid";
        }
        if (transform.has("version") && !"2.0".equals(text(transform.get("version")))) {
            return "compiled-page-composition-transform-version-invalid";
        }
        if (transform.has("sourceBindings")) {
            JsonNode bindings = transform.path("sourceBindings");
            if (!bindings.isArray()) return "compiled-page-composition-transform-source-bindings-invalid";
            for (JsonNode binding : bindings) {
                if (!bindingFailure(binding).isBlank()) {
                    return "compiled-page-composition-transform-source-bindings-invalid";
                }
            }
        }
        JsonNode steps = transform.path("steps");
        if (!steps.isArray() || steps.isEmpty()) {
            return "compiled-page-composition-transform-steps-required";
        }
        for (JsonNode step : steps) {
            if (!step.isObject()
                    || !STABLE_TRANSFORM_KINDS.contains(text(step.get("kind")))
                    || !"link-propagation".equals(text(step.get("phase")))) {
                return "compiled-page-composition-transform-step-invalid";
            }
            if (step.has("id") && !step.path("id").isTextual()) {
                return "compiled-page-composition-transform-step-invalid";
            }
            if (step.has("when") && !step.path("when").isObject() && !step.path("when").isNull()) {
                return "compiled-page-composition-transform-step-when-invalid";
            }
            if (step.has("input") && !bindingFailure(step.path("input")).isBlank()) {
                return "compiled-page-composition-transform-step-input-invalid";
            }
            if (step.has("inputs")) {
                JsonNode inputs = step.path("inputs");
                if (!inputs.isArray()) return "compiled-page-composition-transform-step-inputs-invalid";
                for (JsonNode binding : inputs) {
                    if (!bindingFailure(binding).isBlank()) {
                        return "compiled-page-composition-transform-step-inputs-invalid";
                    }
                }
            }
            if (step.has("config") && !step.path("config").isObject()) {
                return "compiled-page-composition-transform-step-config-invalid";
            }
            if (step.has("output") && !outputHintFailure(step.path("output")).isBlank()) {
                return "compiled-page-composition-transform-step-output-invalid";
            }
        }
        if (transform.has("output") && !outputHintFailure(transform.path("output")).isBlank()) {
            return "compiled-page-composition-transform-output-invalid";
        }
        return "";
    }

    private static String bindingFailure(JsonNode binding) {
        if (!binding.isObject()) return "binding-object-required";
        if (binding.has("source")
                && !Set.of("event", "payload", "state", "context", "constant")
                        .contains(text(binding.get("source")))) {
            return "binding-source-invalid";
        }
        for (String field : Set.of("path", "alias")) {
            if (binding.has(field) && !binding.path(field).isTextual()) return "binding-text-invalid";
        }
        if (binding.has("optional") && !binding.path("optional").isBoolean()) {
            return "binding-optional-invalid";
        }
        return "";
    }

    private static String outputHintFailure(JsonNode output) {
        if (!output.isObject()) return "output-object-required";
        if (output.has("semanticKind") && !SEMANTIC_KINDS.contains(text(output.get("semanticKind")))) {
            return "output-semantic-kind-invalid";
        }
        if (output.has("cardinality")
                && !Set.of("one", "many", "stream").contains(text(output.get("cardinality")))) {
            return "output-cardinality-invalid";
        }
        if (output.has("materializationHint")
                && !Set.of("replace", "merge", "append").contains(text(output.get("materializationHint")))) {
            return "output-materialization-invalid";
        }
        for (String field : Set.of("schemaId", "schemaRef", "description")) {
            if (output.has(field) && !output.path(field).isTextual()) return "output-text-invalid";
        }
        if (output.has("stableShape") && !output.path("stableShape").isBoolean()) {
            return "output-stable-shape-invalid";
        }
        return "";
    }

    private static String linkPolicyFailure(JsonNode policy) {
        if (!policy.isObject()) return "compiled-page-composition-link-policy-object-required";
        if (policy.has("debounceMs")
                && (!policy.path("debounceMs").isIntegralNumber() || policy.path("debounceMs").longValue() < 0)) {
            return "compiled-page-composition-link-policy-debounce-invalid";
        }
        if (policy.has("distinct") && !policy.path("distinct").isBoolean()) {
            return "compiled-page-composition-link-policy-distinct-invalid";
        }
        if (policy.has("distinctBy") && !policy.path("distinctBy").isTextual()) {
            return "compiled-page-composition-link-policy-distinct-by-invalid";
        }
        if (policy.has("delivery")
                && !Set.of("sync", "microtask", "batched").contains(text(policy.get("delivery")))) {
            return "compiled-page-composition-link-policy-delivery-invalid";
        }
        if (policy.has("missingValuePolicy")
                && !Set.of("propagate-undefined", "skip", "use-default")
                        .contains(text(policy.get("missingValuePolicy")))) {
            return "compiled-page-composition-link-policy-missing-value-invalid";
        }
        if (policy.has("errorPolicy")
                && !Set.of("diagnostic", "drop", "halt-page").contains(text(policy.get("errorPolicy")))) {
            return "compiled-page-composition-link-policy-error-invalid";
        }
        return "";
    }

    private static String linkMetadataFailure(JsonNode metadata) {
        if (!metadata.isObject()) return "compiled-page-composition-link-metadata-object-required";
        for (String field : Set.of("label", "description", "traceKey")) {
            if (metadata.has(field) && !metadata.path(field).isTextual()) {
                return "compiled-page-composition-link-metadata-text-invalid";
            }
        }
        if (metadata.has("tags") && !isTextArray(metadata.path("tags"))) {
            return "compiled-page-composition-link-metadata-tags-invalid";
        }
        if (metadata.has("deprecated") && !metadata.path("deprecated").isBoolean()) {
            return "compiled-page-composition-link-metadata-deprecated-invalid";
        }
        if (metadata.has("source")
                && !Set.of(
                                "legacy-widget-connection",
                                "persisted-composition-link",
                                "native-composition-link",
                                "ui-composition-plan")
                        .contains(text(metadata.get("source")))) {
            return "compiled-page-composition-link-metadata-source-invalid";
        }
        return "";
    }

    private static String widgetReferencesFailure(
            JsonNode references,
            Set<String> widgetKeys,
            String invalidCode) {
        if (!isTextArray(references)) return invalidCode;
        for (JsonNode reference : references) {
            if (!widgetKeys.contains(reference.textValue().trim())) return invalidCode.replace("invalid", "not-found");
        }
        return "";
    }

    private static boolean isTextArray(JsonNode node) {
        if (!node.isArray()) return false;
        for (JsonNode value : node) {
            if (text(value).isBlank()) return false;
        }
        return true;
    }

    private static boolean isPositiveInteger(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.intValue() > 0;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue().trim() : "";
    }

    static JsonNode requireApplicablePage(JsonNode compiledFormPatch) {
        String reason = terminalApplyBlockReason(compiledFormPatch);
        return switch (reason) {
            case "" -> compiledFormPatch.path("patch").path("page");
            case "compiled-form-patch-missing" ->
                    throw new IllegalArgumentException("compiledFormPatch is required");
            case "compiled-page-patch-missing" ->
                    throw new IllegalArgumentException("compiledFormPatch.patch.page must be an object");
            case "compiled-page-widgets-missing", "compiled-page-widgets-empty" ->
                    throw new IllegalArgumentException("compiledFormPatch.patch.page.widgets must not be empty");
            case "compiled-page-widget-object-required",
                    "compiled-page-widget-key-required",
                    "compiled-page-widget-key-duplicated",
                    "compiled-page-widget-definition-required",
                    "compiled-page-widget-component-id-required",
                    "compiled-page-widget-inputs-object-required",
                    "compiled-page-widget-outputs-object-required",
                    "compiled-page-widget-binding-order-invalid" ->
                    throw new IllegalArgumentException(
                            "compiledFormPatch.patch.page.widgets contains an invalid widget: " + reason);
            default -> throw new IllegalArgumentException(
                    "compiledFormPatch.patch.page contains an invalid structure: " + reason);
        };
    }
}
