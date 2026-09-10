package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.Set;

/** Deterministic semantics after the LLM has selected a declared Table operation. */
final class TableDetailAuthoringSemantics {
    private TableDetailAuthoringSemantics() {}

    static ObjectNode apply(ObjectNode source, String operationId, JsonNode input) {
        if (!input.isObject() || input.isEmpty()) throw new IllegalArgumentException("Detail input must be a non-empty object");
        ObjectNode next = source.deepCopy();
        ObjectNode behavior = object(next, "behavior");
        ObjectNode detail = object(behavior, "detail");
        JsonNode presentation = MissingNode.getInstance();
        Boolean enabled = null;
        boolean selectRow = false;
        switch (operationId) {
            case "expansion.configure" -> {
                if (input.has("enabled") && !input.path("enabled").isBoolean()) throw new IllegalArgumentException("Invalid expansion enabled");
                merge(object(behavior, "expansion"), input);
                selectRow = input.path("enabled").asBoolean(false);
            }
            case "detail.configure" -> {
                validateStructure(input);
                merge(detail, input);
                presentation = input.path("presentation");
                if (input.has("enabled")) {
                    if (!input.path("enabled").isBoolean()) throw new IllegalArgumentException("Invalid detail enabled");
                    enabled = input.path("enabled").booleanValue();
                }
            }
            case "detail.presentation.configure" -> presentation = input;
            case "detail.source.configure" -> merge(object(detail, "source"), input);
            default -> throw new IllegalArgumentException("Unsupported Table detail operation");
        }
        if (selectRow) {
            presentation = next.objectNode().put("placement", "row");
            enabled = true;
        }
        if (!presentation.isMissingNode()) {
            if (!presentation.isObject()) throw new IllegalArgumentException("Invalid detail presentation");
            if (presentation.has("placement") && (!presentation.path("placement").isTextual()
                    || !Set.of("row", "bottom").contains(presentation.path("placement").asText()))) {
                throw new IllegalArgumentException("Invalid detail placement");
            }
            if (presentation.has("initiallyCollapsed") && !presentation.path("initiallyCollapsed").isBoolean()) {
                throw new IllegalArgumentException("Invalid initiallyCollapsed");
            }
            merge(object(detail, "presentation"), presentation);
        }
        if (enabled != null) detail.put("enabled", enabled);
        if (Boolean.FALSE.equals(enabled)) {
            object(behavior, "expansion").put("enabled", false);
        } else if (Boolean.TRUE.equals(enabled) || presentation.has("placement")) {
            boolean bottom = "bottom".equals(detail.path("presentation").path("placement").asText("row"));
            detail.put("enabled", true);
            object(behavior, "expansion").put("enabled", !bottom);
            if (bottom && !behavior.path("selection").path("enabled").asBoolean(false)) {
                ObjectNode selection = object(behavior, "selection");
                putDefault(selection, "mode", "both");
                putDefault(selection, "checkboxPosition", "start");
                for (String key : Set.of("allowSelectAll", "persistSelection", "persistOnDataUpdate")) {
                    if (!selection.has(key)) selection.put(key, false);
                }
                selection.put("type", "single");
                selection.put("enabled", true);
            }
        }
        validateStructure(detail);
        String placement = detail.path("presentation").path("placement").asText("row");
        if (!Set.of("row", "bottom").contains(placement)) throw new IllegalArgumentException("Invalid detail placement");
        boolean disabled = detail.has("enabled") && !detail.path("enabled").asBoolean(true);
        if ((disabled || "bottom".equals(placement)) && behavior.path("expansion").path("enabled").asBoolean(false)) {
            throw new IllegalArgumentException("Detail and expansion conflict");
        }
        if (!disabled && "bottom".equals(placement) && !behavior.path("selection").path("enabled").asBoolean(false)) {
            throw new IllegalArgumentException("Bottom detail requires selection");
        }
        JsonNode height = detail.path("height");
        if (("bottom".equals(placement) && "dynamic".equals(height.path("mode").asText()))
                || (height.has("px") && (!height.path("px").isNumber()
                || !Double.isFinite(height.path("px").asDouble()) || height.path("px").asDouble() <= 0))) {
            throw new IllegalArgumentException("Invalid detail height");
        }
        // An expansion-only edit must not manufacture an empty detail document.
        if (detail.isEmpty() && !source.path("behavior").has("detail")) behavior.remove("detail");
        return next;
    }

    private static void validateStructure(JsonNode detail) {
        for (String key : Set.of("presentation", "source", "schemaContract", "rendering", "height", "lazyLoad")) {
            if (detail.has(key) && !detail.path(key).isObject()) throw new IllegalArgumentException("Invalid detail " + key);
        }
        if (detail.has("enabled") && !detail.path("enabled").isBoolean()) throw new IllegalArgumentException("Invalid detail enabled");
        JsonNode presentation = detail.path("presentation");
        if (presentation.has("initiallyCollapsed") && !presentation.path("initiallyCollapsed").isBoolean()) throw new IllegalArgumentException("Invalid initiallyCollapsed");
        JsonNode source = detail.path("source");
        if (source.has("mode") && (!source.path("mode").isTextual() || !Set.of("inline", "resource", "resourcePath", "hypermedia").contains(source.path("mode").asText()))) throw new IllegalArgumentException("Invalid detail source mode");
        if (source.has("inlineSchema") && !source.path("inlineSchema").isObject()) throw new IllegalArgumentException("Invalid inline schema");
        if (source.path("inlineSchema").has("items") && !source.path("inlineSchema").path("items").isArray()) throw new IllegalArgumentException("Invalid inline schema items");
    }

    private static ObjectNode object(ObjectNode parent, String key) {
        return parent.path(key) instanceof ObjectNode value ? value : parent.putObject(key);
    }

    private static void putDefault(ObjectNode parent, String key, String value) {
        if (!parent.has(key)) parent.put(key, value);
    }

    private static void merge(ObjectNode target, JsonNode patch) {
        patch.fields().forEachRemaining(entry -> {
            if (entry.getValue().isObject()) merge(object(target, entry.getKey()), entry.getValue());
            else target.set(entry.getKey(), entry.getValue().deepCopy());
        });
    }
}
