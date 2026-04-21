package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgenticAuthoringEffectCompilerRegistry {

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringTargetResolverRegistry targetResolverRegistry;

    public AgenticAuthoringEffectCompilerRegistry(
            ObjectMapper objectMapper,
            AgenticAuthoringTargetResolverRegistry targetResolverRegistry) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.targetResolverRegistry = Objects.requireNonNull(targetResolverRegistry, "targetResolverRegistry must not be null");
    }

    void appendCompiledEffects(
            String componentId,
            JsonNode operation,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            ArrayNode patchOperations,
            List<String> failures,
            List<String> warnings) {
        for (JsonNode effect : operation.path("effects")) {
            String effectKind = text(effect, "kind");
            AgenticAuthoringResolvedTarget resolved = null;
            if (operation.path("target").path("required").asBoolean(false)) {
                resolved = targetResolverRegistry.resolve(componentId, operation, planOperation.path("target"), proposedConfig);
                if (!AgenticAuthoringTargetResolverRegistry.STATUS_RESOLVED.equals(resolved.status())) {
                    failures.add("target resolution failed during compile for "
                            + text(operation, "operationId")
                            + ": "
                            + String.join(", ", resolved.failures()));
                    continue;
                }
            }
            if ("compile-domain-patch".equals(effectKind)) {
                ObjectNode compiled = compileDomainPatch(
                        componentId,
                        operation,
                        effect,
                        planOperation,
                        resolved,
                        proposedConfig,
                        failures);
                if (compiled != null) {
                    patchOperations.add(compiled);
                }
                continue;
            }
            ObjectNode compiled = compileAndApplyEffect(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            if (compiled != null) {
                patchOperations.add(compiled);
            }
        }
    }

    boolean supportsDomainPatchHandler(String handler) {
        return "stepper-step-reorder".equals(handler);
    }

    private ObjectNode compileAndApplyEffect(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        String effectKind = text(effect, "kind");
        String path = text(effect, "path");
        JsonNode input = planOperation.path("input");
        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", effectKind);
        compiled.put("effectKind", effectKind);
        compiled.put("path", path);
        if (effect.has("key")) {
            compiled.set("key", effect.path("key"));
        }
        if (resolved != null) {
            compiled.put("resolvedPath", resolved.path());
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));

        switch (effectKind) {
            case "merge-by-key" -> applyMergeByKey(effect, input, resolved, proposedConfig, compiled, failures);
            case "remove-by-key" -> applyRemoveByKey(effect, resolved, proposedConfig, compiled, failures);
            case "set-value" -> applySetValue(effect, input, resolved, proposedConfig, compiled, failures);
            case "merge-object" -> applyMergeObject(effect, input, proposedConfig, compiled, failures);
            case "reorder-by-key" -> applyReorderByKey(effect, input, resolved, proposedConfig, compiled, failures);
            case "append", "append-to-array" -> applyAppend(effect, input, resolved, proposedConfig, compiled, failures);
            case "append-unique" -> applyAppendUnique(effect, input, resolved, proposedConfig, compiled, failures);
            default -> {
                failures.add("unsupported effect kind for compile: " + effectKind + " in " + text(operation, "operationId"));
                return null;
            }
        }
        return compiled;
    }

    private ObjectNode compileDomainPatch(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        String handler = text(effect, "handler");
        return switch (handler) {
            case "stepper-step-reorder" -> compileStepperStepReorder(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            default -> {
                failures.add("domain compiler is required for operation: " + text(operation, "operationId"));
                yield null;
            }
        };
    }

    private ObjectNode compileStepperStepReorder(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode steps = arrayAt(proposedConfig, "steps", false);
        if (steps == null) {
            failures.add("stepper-step-reorder path is not an array: steps[]");
            return null;
        }
        String stepId = resolved != null ? text(resolved.value(), "id") : "";
        int fromIndex = indexOfObjectByKey(steps, "id", stepId);
        if (fromIndex < 0) {
            failures.add("stepper-step-reorder target not found: steps[] id=" + stepId);
            return null;
        }
        String beforeStepId = text(planOperation.path("input"), "beforeStepId");
        if (beforeStepId.isBlank()) {
            failures.add("stepper-step-reorder requires beforeStepId");
            return null;
        }
        int beforeIndex = indexOfObjectByKey(steps, "id", beforeStepId);
        if (beforeIndex < 0) {
            failures.add("stepper-step-reorder before step not found: steps[] id=" + beforeStepId);
            return null;
        }

        int selectedIndexBefore = proposedConfig.path("selectedIndex").asInt(-1);
        String selectedStepId = selectedIndexBefore >= 0 && selectedIndexBefore < steps.size()
                ? text(steps.get(selectedIndexBefore), "id")
                : "";

        JsonNode moved = steps.remove(fromIndex);
        int targetIndex = beforeIndex;
        if (fromIndex < beforeIndex) {
            targetIndex = beforeIndex - 1;
        }
        steps.insert(targetIndex, moved);

        int selectedIndexAfter = selectedIndexBefore;
        if (!selectedStepId.isBlank()) {
            selectedIndexAfter = indexOfObjectByKey(steps, "id", selectedStepId);
            if (selectedIndexAfter >= 0) {
                proposedConfig.put("selectedIndex", selectedIndexAfter);
            }
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "reorder-by-key");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "steps[]");
        compiled.put("resolvedPath", resolved != null ? resolved.path() : "");
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", stepId);
        compiled.put("fromIndex", fromIndex);
        compiled.put("toIndex", targetIndex);
        compiled.put("beforeStepId", beforeStepId);
        compiled.put("selectedIndexBefore", selectedIndexBefore);
        compiled.put("selectedIndexAfter", selectedIndexAfter);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private void applyMergeByKey(
            JsonNode effect,
            JsonNode input,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        String path = text(effect, "path");
        String key = text(effect, "key");
        String keyValue = resolved != null ? text(resolved.value(), key) : "";
        ArrayNode array = arrayAt(proposedConfig, collectionPath(path), false);
        if (array == null) {
            failures.add("merge-by-key path is not an array: " + path);
            return;
        }
        ObjectNode target = findObjectByKey(array, key, keyValue);
        if (target == null) {
            failures.add("merge-by-key target not found: " + path + " " + key + "=" + keyValue);
            return;
        }
        mergeObject(target, input);
        compiled.put("keyValue", keyValue);
        compiled.set("value", input);
    }

    private void applyRemoveByKey(
            JsonNode effect,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        String path = text(effect, "path");
        String key = text(effect, "key");
        String keyValue = resolved != null ? text(resolved.value(), key) : "";
        ArrayNode array = arrayAt(proposedConfig, collectionPath(path), false);
        if (array == null) {
            failures.add("remove-by-key path is not an array: " + path);
            return;
        }
        int index = indexOfObjectByKey(array, key, keyValue);
        if (index < 0) {
            failures.add("remove-by-key target not found: " + path + " " + key + "=" + keyValue);
            return;
        }
        array.remove(index);
        compiled.put("keyValue", keyValue);
        compiled.put("removedIndex", index);
    }

    private void applySetValue(
            JsonNode effect,
            JsonNode input,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        String path = text(effect, "path");
        String tail = tailAfterArray(path);
        JsonNode value = valueForSetEffect(effect, input, tail);
        if (resolved != null && !tail.isBlank()) {
            JsonNode targetNode = nodeAtResolvedPath(proposedConfig, resolved.path());
            if (targetNode instanceof ObjectNode targetObject) {
                setDottedValue(targetObject, tail, value);
                compiled.put("resolvedPath", resolved.path() + "." + tail);
            } else {
                failures.add("set-value resolved target is not an object: " + resolved.path());
                return;
            }
        } else {
            setDottedValue(proposedConfig, normalizeDottedPath(path), value);
        }
        compiled.set("value", value);
    }

    private void applyMergeObject(
            JsonNode effect,
            JsonNode input,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        String path = normalizeDottedPath(text(effect, "path"));
        JsonNode target = objectAt(proposedConfig, path, true);
        if (target instanceof ObjectNode targetObject) {
            mergeObject(targetObject, input);
            compiled.set("value", input);
        } else {
            failures.add("merge-object path is not an object: " + path);
        }
    }

    private void applyReorderByKey(
            JsonNode effect,
            JsonNode input,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        String path = text(effect, "path");
        String key = text(effect, "key");
        String keyValue = resolved != null ? text(resolved.value(), key) : text(input, key);
        if (key.isBlank()) {
            failures.add("reorder-by-key requires key for path: " + path);
            return;
        }
        if (keyValue.isBlank()) {
            failures.add("reorder-by-key target key is missing for path: " + path);
            return;
        }
        ArrayNode array = arrayAt(proposedConfig, collectionPath(path), false);
        if (array == null) {
            failures.add("reorder-by-key path is not an array: " + path);
            return;
        }
        int fromIndex = indexOfObjectByKey(array, key, keyValue);
        if (fromIndex < 0) {
            failures.add("reorder-by-key target not found: " + path + " " + key + "=" + keyValue);
            return;
        }
        JsonNode item = array.remove(fromIndex);
        int toIndex = resolveReorderIndex(array, input, key, path, failures);
        if (toIndex < 0) {
            array.insert(fromIndex, item);
            return;
        }
        array.insert(toIndex, item);
        compiled.put("keyValue", keyValue);
        compiled.put("fromIndex", fromIndex);
        compiled.put("toIndex", toIndex);
    }

    private void applyAppend(
            JsonNode effect,
            JsonNode input,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        ArrayNode array = arrayForAppend(effect, resolved, proposedConfig, failures);
        if (array == null) {
            return;
        }
        array.add(input);
        compiled.set("value", input);
        compiled.put("appendedIndex", array.size() - 1);
    }

    private void applyAppendUnique(
            JsonNode effect,
            JsonNode input,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        String key = text(effect, "key");
        if (key.isBlank()) {
            failures.add("append-unique requires key for path: " + text(effect, "path"));
            return;
        }
        String keyValue = text(input, key);
        if (keyValue.isBlank()) {
            failures.add("append-unique input is missing key " + key + " for path: " + text(effect, "path"));
            return;
        }
        ArrayNode array = arrayForAppend(effect, resolved, proposedConfig, failures);
        if (array == null) {
            return;
        }
        int existingIndex = indexOfObjectByKey(array, key, keyValue);
        if (existingIndex >= 0) {
            failures.add("append-unique duplicate value for " + text(effect, "path") + " " + key + "=" + keyValue);
            return;
        }
        array.add(input);
        compiled.put("keyValue", keyValue);
        compiled.set("value", input);
        compiled.put("appendedIndex", array.size() - 1);
    }

    private ArrayNode arrayForAppend(
            JsonNode effect,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        String path = text(effect, "path");
        ObjectNode root = proposedConfig;
        String arrayPath = collectionPath(path);
        if (resolved != null && path.contains("[].")) {
            JsonNode targetNode = nodeAtResolvedPath(proposedConfig, resolved.path());
            if (!(targetNode instanceof ObjectNode targetObject)) {
                failures.add("append resolved target is not an object: " + resolved.path());
                return null;
            }
            root = targetObject;
            arrayPath = collectionPath(tailAfterLastArray(path));
        }
        ArrayNode array = arrayAt(root, arrayPath, true);
        if (array == null) {
            failures.add("append path is not an array: " + path);
        }
        return array;
    }

    private JsonNode valueForSetEffect(JsonNode effect, JsonNode input, String tail) {
        String inputPath = text(effect, "inputPath");
        if (!inputPath.isBlank()) {
            JsonNode value = resolvePath(input, inputPath);
            return value.isMissingNode() ? MissingNode.getInstance() : value;
        }
        if (!tail.isBlank() && input != null && input.has(tail)) {
            return input.path(tail);
        }
        return input == null ? MissingNode.getInstance() : input;
    }

    private void mergeObject(ObjectNode target, JsonNode input) {
        if (input == null || !input.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            target.set(field.getKey(), field.getValue());
        }
    }

    private ObjectNode findObjectByKey(ArrayNode array, String key, String keyValue) {
        int index = indexOfObjectByKey(array, key, keyValue);
        return index >= 0 && array.get(index) instanceof ObjectNode object ? object : null;
    }

    private int indexOfObjectByKey(ArrayNode array, String key, String keyValue) {
        if (array == null || key == null || key.isBlank()) {
            return -1;
        }
        for (int i = 0; i < array.size(); i++) {
            if (keyValue.equals(text(array.get(i), key))) {
                return i;
            }
        }
        return -1;
    }

    private int resolveReorderIndex(
            ArrayNode array,
            JsonNode input,
            String key,
            String path,
            List<String> failures) {
        String beforeKeyValue = firstText(input, "beforeId", "beforeKey", "beforePanelId", "beforeBlockId");
        if (!beforeKeyValue.isBlank()) {
            int beforeIndex = indexOfObjectByKey(array, key, beforeKeyValue);
            if (beforeIndex < 0) {
                failures.add("reorder-by-key before target not found: " + path + " " + key + "=" + beforeKeyValue);
                return -1;
            }
            return beforeIndex;
        }
        String afterKeyValue = firstText(input, "afterId", "afterKey", "afterPanelId", "afterBlockId");
        if (!afterKeyValue.isBlank()) {
            int afterIndex = indexOfObjectByKey(array, key, afterKeyValue);
            if (afterIndex < 0) {
                failures.add("reorder-by-key after target not found: " + path + " " + key + "=" + afterKeyValue);
                return -1;
            }
            return afterIndex + 1;
        }
        if (input != null && input.has("index") && input.path("index").canConvertToInt()) {
            int index = input.path("index").asInt();
            if (index < 0 || index > array.size()) {
                failures.add("reorder-by-key index is out of bounds: " + index + " for " + path);
                return -1;
            }
            return index;
        }
        failures.add("reorder-by-key requires beforeId, afterId or index for path: " + path);
        return -1;
    }

    private ObjectNode objectAt(ObjectNode root, String dottedPath, boolean create) {
        JsonNode node = nodeAt(root, dottedPath, create, true);
        return node instanceof ObjectNode object ? object : null;
    }

    private ArrayNode arrayAt(ObjectNode root, String dottedPath, boolean create) {
        JsonNode node = nodeAt(root, dottedPath, create, false);
        return node instanceof ArrayNode array ? array : null;
    }

    private JsonNode nodeAt(ObjectNode root, String dottedPath, boolean create, boolean objectLeaf) {
        if (dottedPath == null || dottedPath.isBlank()) {
            return root;
        }
        ObjectNode current = root;
        String[] segments = dottedPath.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            String segment = cleanPathSegment(segments[i]);
            boolean last = i == segments.length - 1;
            JsonNode child = current.path(segment);
            if (last) {
                if ((child == null || child.isMissingNode()) && create) {
                    child = objectLeaf ? current.putObject(segment) : current.putArray(segment);
                }
                return child;
            }
            ObjectNode childObject;
            if (child instanceof ObjectNode object) {
                childObject = object;
            } else {
                if (!create) {
                    return MissingNode.getInstance();
                }
                childObject = current.putObject(segment);
            }
            current = childObject;
        }
        return current;
    }

    private void setDottedValue(ObjectNode root, String dottedPath, JsonNode value) {
        String normalized = normalizeDottedPath(dottedPath);
        int lastDot = normalized.lastIndexOf('.');
        ObjectNode parent = lastDot < 0 ? root : objectAt(root, normalized.substring(0, lastDot), true);
        String field = lastDot < 0 ? normalized : normalized.substring(lastDot + 1);
        parent.set(cleanPathSegment(field), value);
    }

    private JsonNode nodeAtResolvedPath(ObjectNode root, String resolvedPath) {
        JsonNode current = root;
        if (resolvedPath == null || resolvedPath.isBlank()) {
            return current;
        }
        for (String segment : resolvedPath.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            int arrayMarker = segment.indexOf("[]/");
            if (arrayMarker >= 0) {
                String arrayName = segment.substring(0, arrayMarker);
                int index = Integer.parseInt(segment.substring(arrayMarker + 3));
                current = current.path(arrayName);
                if (!current.isArray() || index < 0 || index >= current.size()) {
                    return MissingNode.getInstance();
                }
                current = current.get(index);
            } else {
                current = current.path(segment);
            }
            if (current.isMissingNode()) {
                return current;
            }
        }
        return current;
    }

    private JsonNode resolvePath(JsonNode root, String dottedPath) {
        JsonNode current = root;
        if (dottedPath == null || dottedPath.isBlank()) {
            return current;
        }
        for (String segment : dottedPath.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            current = current.path(segment);
            if (current.isMissingNode()) {
                return current;
            }
        }
        return current;
    }

    private String collectionPath(String path) {
        return normalizeDottedPath(path).replace("[]", "");
    }

    private String normalizeDottedPath(String path) {
        return path == null ? "" : path.replace("[]", "");
    }

    private String cleanPathSegment(String segment) {
        return segment == null ? "" : segment.replace("[]", "");
    }

    private String tailAfterArray(String path) {
        int marker = path == null ? -1 : path.indexOf("[].");
        return marker < 0 ? "" : path.substring(marker + 3);
    }

    private String tailAfterLastArray(String path) {
        int marker = path == null ? -1 : path.lastIndexOf("[].");
        return marker < 0 ? path : path.substring(marker + 3);
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
