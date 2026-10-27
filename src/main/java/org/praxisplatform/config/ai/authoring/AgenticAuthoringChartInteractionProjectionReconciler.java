package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Reconciles chart event mappings, pointClick/crossFilter bindings, and item template expressions
 * against governed {@link ChartInteractionProjection} targets during UI composition preview.
 */
final class AgenticAuthoringChartInteractionProjectionReconciler {

    enum ChartInteractionValueShape {
        SCALAR,
        SINGLETON_ARRAY,
        TEMPORAL_RANGE
    }

    record ChartInteractionProjection(
            String displayField,
            String sourceField,
            String targetField,
            ChartInteractionValueShape valueShape,
            String pointValuePath,
            Set<String> axisFields,
            Set<String> crossFilterTargetFields) {

        boolean targetMultiple() {
            return valueShape != ChartInteractionValueShape.SCALAR;
        }

        boolean temporalRange() {
            return valueShape == ChartInteractionValueShape.TEMPORAL_RANGE;
        }
    }

    private final ObjectMapper objectMapper;

    AgenticAuthoringChartInteractionProjectionReconciler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    void disableUngroundedTemporalFilterInteractions(
            ObjectNode plan,
            ObjectNode chart,
            String chartKey) {
        JsonNode interactions = chart.path("inputs").path("config").path("interactions");
        if (interactions instanceof ObjectNode interactionConfig && interactionConfig.has("crossFilter")) {
            interactionConfig.put("crossFilter", false);
        }
        JsonNode eventActions = interactions.path("eventActions");
        if (eventActions instanceof ObjectNode actions) {
            actions.remove("crossFilter");
        }
        removeUngroundedTemporalFilterLinks(plan.path("bindings"), chartKey);
        removeUngroundedTemporalFilterLinks(plan.path("composition").path("links"), chartKey);
    }

    boolean reconcileChartEventMappings(
            ObjectNode chart,
            ChartInteractionProjection projection) {
        JsonNode eventActions = chart.path("inputs").path("config")
                .path("interactions").path("eventActions");
        if (!eventActions.isObject()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode action : eventActions) {
            if (!(action.path("mapping") instanceof ObjectNode mapping) || mapping.isEmpty()) {
                continue;
            }
            ObjectNode replacement = objectMapper.createObjectNode();
            var fields = mapping.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String source = entry.getKey();
                String target = entry.getValue().asText("");
                if (!isInteractionAxisField(source, projection)) {
                    replacement.set(source, entry.getValue().deepCopy());
                    continue;
                }
                String nextTarget = isInferredInteractionTarget(source, target, projection)
                        ? projection.targetField()
                        : target;
                replacement.put(projection.sourceField(), nextTarget);
            }
            if (!replacement.equals(mapping)) {
                mapping.removeAll();
                mapping.setAll(replacement);
                changed = true;
            }
        }
        return changed;
    }

    boolean reconcileChartInteractionBindings(
            ObjectNode plan,
            String chartKey,
            ChartInteractionProjection projection) {
        JsonNode bindings = plan.path("bindings");
        if (!bindings.isArray()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode binding : bindings) {
            if (!(binding instanceof ObjectNode bindingObject)) {
                continue;
            }
            JsonNode from = bindingObject.path("from");
            if (!chartKey.equals(bindingWidgetKey(from))) {
                continue;
            }
            String fromPort = bindingPort(from);
            if ("pointClick".equals(fromPort)) {
                changed = alignInteractionPolicy(bindingObject, projection.pointValuePath()) || changed;
                if (projection.temporalRange()) {
                    changed = reconcileTemporalRangeCondition(bindingObject, "payload.data") || changed;
                }
                ObjectNode surfacePayload = surfaceOpenPayload(bindingObject.path("to"));
                if (surfacePayload != null) {
                    changed = reconcileSurfaceBindings(surfacePayload, projection) || changed;
                }
                if ("queryContext".equals(bindingPort(bindingObject.path("to")))) {
                    changed = reconcilePointQueryContextTransform(bindingObject, projection) || changed;
                }
            } else if ("crossFilter".equals(fromPort)
                    && "queryContext".equals(bindingPort(bindingObject.path("to")))) {
                String distinctBy = projection.temporalRange()
                        ? "payload.source.data.start"
                        : "payload.filters." + projection.targetField();
                changed = alignInteractionPolicy(
                        bindingObject,
                        distinctBy) || changed;
                if (projection.temporalRange()) {
                    changed = reconcileTemporalRangeCondition(bindingObject, "payload.source.data") || changed;
                }
                changed = reconcileCrossFilterQueryContextTransform(bindingObject, projection) || changed;
            }
        }
        return changed;
    }

    boolean reconcileItemTemplateFields(
            JsonNode node,
            ChartInteractionProjection projection) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        boolean changed = false;
        if (node instanceof ObjectNode objectNode) {
            JsonNode expression = objectNode.path("expr");
            if (expression.isTextual()) {
                String current = expression.asText("");
                for (String legacyField : projection.axisFields()) {
                    if (normalize(legacyField).equals(normalize(projection.displayField()))) {
                        continue;
                    }
                    String expected = "${item." + legacyField + "}";
                    if (expected.equals(current)) {
                        objectNode.put("expr", "${item." + projection.displayField() + "}");
                        changed = true;
                        break;
                    }
                }
            }
            var fields = objectNode.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                changed = reconcileItemTemplateFields(entry.getValue(), projection) || changed;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                changed = reconcileItemTemplateFields(item, projection) || changed;
            }
        }
        return changed;
    }

    private boolean removeUngroundedTemporalFilterLinks(JsonNode links, String chartKey) {
        if (!(links instanceof ArrayNode array)) {
            return false;
        }
        boolean changed = false;
        for (int index = array.size() - 1; index >= 0; index--) {
            JsonNode link = array.path(index);
            JsonNode from = link.path("from");
            if (!chartKey.equals(bindingWidgetKey(from))) {
                continue;
            }
            boolean queryContextTarget = "queryContext".equals(bindingPort(link.path("to")));
            boolean filteredSurfaceTarget = surfacePayloadContainsQueryContextFilter(link.path("to"));
            if (queryContextTarget || filteredSurfaceTarget) {
                array.remove(index);
                changed = true;
            }
        }
        return changed;
    }

    private boolean surfacePayloadContainsQueryContextFilter(JsonNode to) {
        ObjectNode payload = surfaceOpenPayload(to);
        if (payload == null || !payload.path("bindings").isArray()) {
            return false;
        }
        for (JsonNode binding : payload.path("bindings")) {
            if (binding.path("to").asText("").contains(".queryContext.filters.")) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode surfaceOpenPayload(JsonNode to) {
        if (to == null || to.isMissingNode()) {
            return null;
        }
        String actionId = firstNonBlank(
                to.path("actionId").asText(""),
                to.path("ref").path("actionId").asText(""));
        if (!"surface.open".equals(actionId)) {
            return null;
        }
        JsonNode payload = to.path("payload");
        if (!(payload instanceof ObjectNode)) {
            payload = to.path("ref").path("payload");
        }
        return payload instanceof ObjectNode payloadObject ? payloadObject : null;
    }

    private boolean alignInteractionPolicy(ObjectNode binding, String distinctBy) {
        if (!(binding.path("policy") instanceof ObjectNode policy)
                || distinctBy == null
                || distinctBy.isBlank()
                || distinctBy.equals(policy.path("distinctBy").asText(""))) {
            return false;
        }
        policy.put("distinctBy", distinctBy);
        return true;
    }

    private boolean reconcileTemporalRangeCondition(ObjectNode binding, String dataPath) {
        String startPath = dataPath + ".start";
        String endPath = dataPath + ".end";
        JsonNode current = binding.path("condition");
        ArrayNode clauses = objectMapper.createArrayNode();
        if (current.path("and").isArray()) {
            current.path("and").forEach(clause -> clauses.add(clause.deepCopy()));
        } else if (!current.isMissingNode() && !current.isNull()) {
            clauses.add(current.deepCopy());
        }
        boolean hasStart = containsTemporalBoundaryClause(clauses, startPath);
        boolean hasEnd = containsTemporalBoundaryClause(clauses, endPath);
        if (hasStart && hasEnd && current.path("and").isArray()) {
            return false;
        }
        if (!hasStart) {
            clauses.add(temporalBoundaryCondition(startPath));
        }
        if (!hasEnd) {
            clauses.add(temporalBoundaryCondition(endPath));
        }
        binding.set("condition", objectMapper.createObjectNode().set("and", clauses));
        return true;
    }

    private ObjectNode temporalBoundaryCondition(String path) {
        ObjectNode truthy = objectMapper.createObjectNode();
        truthy.set("!!", objectMapper.createObjectNode().put("var", path));
        return truthy;
    }

    private boolean containsTemporalBoundaryClause(ArrayNode clauses, String path) {
        for (JsonNode clause : clauses) {
            if (path.equals(clause.path("!!").path("var").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private boolean reconcilePointQueryContextTransform(
            ObjectNode binding,
            ChartInteractionProjection projection) {
        ObjectNode template = bindingTransformTemplate(binding);
        if (template == null) {
            return false;
        }
        ObjectNode filters = template.path("filters") instanceof ObjectNode existing
                ? existing
                : template.putObject("filters");
        boolean changed = removeInferredInteractionFields(filters, projection);
        JsonNode nextValue = pointInteractionTemplateValue(projection);
        if (!nextValue.equals(filters.path(projection.targetField()))) {
            filters.set(projection.targetField(), nextValue);
            changed = true;
        }
        return changed;
    }

    private boolean reconcileCrossFilterQueryContextTransform(
            ObjectNode binding,
            ChartInteractionProjection projection) {
        ObjectNode template = bindingTransformTemplate(binding);
        if (template == null) {
            return false;
        }
        JsonNode existingFilters = template.path("filters");
        ObjectNode filters;
        boolean changed = false;
        if (existingFilters instanceof ObjectNode existing) {
            filters = existing;
        } else if ("${payload.filters}".equals(existingFilters.asText(""))) {
            filters = objectMapper.createObjectNode();
            for (String targetField : projection.crossFilterTargetFields()) {
                filters.put(targetField, "${payload.filters." + targetField + "}");
            }
            template.set("filters", filters);
            changed = true;
        } else if (existingFilters.isMissingNode() || existingFilters.isNull()) {
            filters = template.putObject("filters");
            changed = true;
        } else {
            return false;
        }
        changed = removeInferredInteractionFields(filters, projection) || changed;
        JsonNode nextValue = crossFilterInteractionTemplateValue(projection);
        if (!nextValue.equals(filters.path(projection.targetField()))) {
            filters.set(projection.targetField(), nextValue);
            changed = true;
        }
        return changed;
    }

    private ObjectNode bindingTransformTemplate(ObjectNode binding) {
        JsonNode transform = binding.path("transform");
        if (transform.path("template") instanceof ObjectNode directTemplate) {
            return directTemplate;
        }
        JsonNode steps = transform.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                JsonNode template = step.path("config").path("template");
                if (template instanceof ObjectNode objectNode) {
                    return objectNode;
                }
            }
        }
        return null;
    }

    private JsonNode pointInteractionTemplateValue(ChartInteractionProjection projection) {
        if (projection.temporalRange()) {
            return interactionTemplateArray("payload.data.start", "payload.data.end");
        }
        return interactionTemplateValue(projection.pointValuePath(), projection.targetMultiple());
    }

    private JsonNode crossFilterInteractionTemplateValue(ChartInteractionProjection projection) {
        if (projection.temporalRange()) {
            return interactionTemplateArray("payload.source.data.start", "payload.source.data.end");
        }
        return interactionTemplateValue(
                "payload.filters." + projection.targetField(),
                projection.targetMultiple());
    }

    private JsonNode interactionTemplateValue(String path, boolean multiple) {
        String expression = "${" + path + "}";
        if (!multiple) {
            return objectMapper.getNodeFactory().textNode(expression);
        }
        return interactionTemplateArray(path);
    }

    private ArrayNode interactionTemplateArray(String... paths) {
        ArrayNode values = objectMapper.createArrayNode();
        for (String path : paths) {
            values.add("${" + path + "}");
        }
        return values;
    }

    private boolean reconcileSurfaceBindings(
            ObjectNode surfacePayload,
            ChartInteractionProjection projection) {
        JsonNode bindings = surfacePayload.path("bindings");
        if (!bindings.isArray()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode binding : bindings) {
            if (!(binding instanceof ObjectNode bindingObject)) {
                continue;
            }
            String targetPath = bindingObject.path("to").asText("");
            int separator = targetPath.lastIndexOf('.');
            if (separator < 0) {
                continue;
            }
            String targetField = targetPath.substring(separator + 1);
            if (!isInteractionAxisField(targetField, projection)
                    && !normalize(targetField).equals(normalize(projection.targetField()))) {
                continue;
            }
            String nextTargetPath = targetPath.substring(0, separator + 1) + projection.targetField();
            if (!nextTargetPath.equals(targetPath)) {
                bindingObject.put("to", nextTargetPath);
                changed = true;
            }
            if (projection.targetMultiple()) {
                JsonNode nextValue = pointInteractionTemplateValue(projection);
                if (!"template".equals(bindingObject.path("mode").asText(""))
                        || !nextValue.equals(bindingObject.path("value"))
                        || bindingObject.has("from")) {
                    bindingObject.remove("from");
                    bindingObject.put("mode", "template");
                    bindingObject.set("value", nextValue);
                    changed = true;
                }
            } else if (!projection.pointValuePath().equals(bindingObject.path("from").asText(""))
                    || bindingObject.has("mode")
                    || bindingObject.has("value")) {
                bindingObject.put("from", projection.pointValuePath());
                bindingObject.remove(List.of("mode", "value"));
                changed = true;
            }
        }
        return changed;
    }

    private boolean removeInferredInteractionFields(
            ObjectNode filters,
            ChartInteractionProjection projection) {
        List<String> fieldsToRemove = new ArrayList<>();
        filters.fieldNames().forEachRemaining(field -> {
            if (isInteractionAxisField(field, projection)
                    && !normalize(field).equals(normalize(projection.targetField()))) {
                fieldsToRemove.add(field);
            }
        });
        if (fieldsToRemove.isEmpty()) {
            return false;
        }
        filters.remove(fieldsToRemove);
        return true;
    }

    private boolean isInferredInteractionTarget(
            String source,
            String target,
            ChartInteractionProjection projection) {
        return normalize(source).equals(normalize(target))
                || isInteractionAxisField(target, projection);
    }

    private boolean isInteractionAxisField(
            String field,
            ChartInteractionProjection projection) {
        if (field == null || field.isBlank() || projection == null) {
            return false;
        }
        String normalized = normalize(field);
        if (normalized.equals(normalize(projection.sourceField()))
                || normalized.equals(normalize(projection.displayField()))) {
            return true;
        }
        return projection.axisFields().stream()
                .map(this::normalize)
                .anyMatch(normalized::equals);
    }

    private String bindingWidgetKey(JsonNode endpoint) {
        return firstNonBlank(
                endpoint.path("widgetKey").asText(""),
                firstNonBlank(
                        endpoint.path("widget").asText(""),
                        endpoint.path("ref").path("widget").asText("")));
    }

    private String bindingPort(JsonNode endpoint) {
        return firstNonBlank(
                endpoint.path("port").asText(""),
                endpoint.path("ref").path("port").asText(""));
    }

    private String firstNonBlank(String first, String second) {
        return value(first).isBlank() ? value(second) : value(first);
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
