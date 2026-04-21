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
import java.util.Set;

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
        return "stepper-step-reorder".equals(handler)
                || "tabs.reorder-tab-and-preserve-selection".equals(handler)
                || "tabs.remove-tab-and-reselect".equals(handler)
                || "tabs.set-active-item".equals(handler)
                || "rich-content-block-add".equals(handler)
                || "rich-content-media-block-update".equals(handler)
                || "rich-content-link-remove".equals(handler)
                || "rich-content-timeline-item-add".equals(handler)
                || "rich-content-timeline-item-update".equals(handler);
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
            case "tabs.reorder-tab-and-preserve-selection" -> compileTabsReorderAndPreserveSelection(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "tabs.remove-tab-and-reselect" -> compileTabsRemoveAndReselect(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "tabs.set-active-item" -> compileTabsSetActiveItem(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "rich-content-block-add" -> compileRichContentBlockAdd(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "rich-content-media-block-update" -> compileRichContentMediaBlockUpdate(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "rich-content-link-remove" -> compileRichContentLinkRemove(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "rich-content-timeline-item-add" -> compileRichContentTimelineItemAdd(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "rich-content-timeline-item-update" -> compileRichContentTimelineItemUpdate(
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

    private ObjectNode compileRichContentMediaBlockUpdate(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode resolvedNode = resolved == null ? MissingNode.getInstance() : nodeAtResolvedPath(proposedConfig, resolved.path());
        if (!(resolvedNode instanceof ObjectNode mediaBlock)) {
            failures.add("rich-content-media-block-update target not found: " + (resolved == null ? "" : resolved.path()));
            return null;
        }
        String nodeType = firstText(mediaBlock, "type", "kind");
        if (!"mediaBlock".equals(nodeType)) {
            failures.add("rich-content-media-block-update target is not mediaBlock: " + nodeType);
            return null;
        }

        JsonNode input = planOperation.path("input");
        ObjectNode value = objectMapper.createObjectNode();
        if (input.has("title")) {
            mediaBlock.set("title", input.path("title"));
            value.set("title", input.path("title"));
        }
        if (input.has("titleExpr")) {
            mediaBlock.set("titleExpr", input.path("titleExpr"));
            value.set("titleExpr", input.path("titleExpr"));
        }
        if (input.has("subtitle")) {
            mediaBlock.set("subtitle", input.path("subtitle"));
            value.set("subtitle", input.path("subtitle"));
        }
        if (input.has("subtitleExpr")) {
            mediaBlock.set("subtitleExpr", input.path("subtitleExpr"));
            value.set("subtitleExpr", input.path("subtitleExpr"));
        }
        ObjectNode avatarPatch = objectMapper.createObjectNode();
        if (input.has("avatarName")) {
            avatarPatch.set("name", input.path("avatarName"));
        }
        if (input.has("avatarImageSrc")) {
            avatarPatch.set("imageSrc", input.path("avatarImageSrc"));
        }
        if (!avatarPatch.isEmpty()) {
            ObjectNode avatar = mediaBlock.path("avatar") instanceof ObjectNode existingAvatar
                    ? existingAvatar
                    : mediaBlock.putObject("avatar");
            avatar.setAll(avatarPatch);
            value.set("avatar", avatarPatch);
        }
        if (value.isEmpty()) {
            failures.add("rich-content-media-block-update requires at least one supported field");
            return null;
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "merge-rich-media-block");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", resolved == null ? "" : resolved.path());
        compiled.put("resolvedPath", resolved == null ? "" : resolved.path());
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", text(mediaBlock, "id"));
        compiled.set("value", value);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileRichContentBlockAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode nodes = arrayAt(proposedConfig, "document.nodes", true);
        if (nodes == null) {
            failures.add("rich-content-block-add path is not an array: document.nodes[]");
            return null;
        }
        JsonNode input = planOperation.path("input");
        String type = text(input, "type");
        if (type.isBlank()) {
            failures.add("rich-content-block-add requires type");
            return null;
        }

        ObjectNode node = objectMapper.createObjectNode();
        if (input.path("node") instanceof ObjectNode inputNode) {
            node.setAll(inputNode);
        }
        if (!node.hasNonNull("type") && !node.hasNonNull("kind")) {
            node.put("type", type);
        }
        if (!node.hasNonNull("id")) {
            node.put("id", nextRichContentNodeId(nodes, type));
        }
        String nodeId = text(node, "id");
        if (nodeId.isBlank()) {
            failures.add("rich-content-block-add requires node.id or generated id");
            return null;
        }
        if (indexOfObjectByKey(nodes, "id", nodeId) >= 0) {
            failures.add("rich-content-block-add duplicate node id: document.nodes[] id=" + nodeId);
            return null;
        }

        int insertedIndex = nodes.size();
        String beforeBlockId = text(input, "beforeBlockId");
        String afterBlockId = text(input, "afterBlockId");
        if (!beforeBlockId.isBlank()) {
            insertedIndex = indexOfObjectByKey(nodes, "id", beforeBlockId);
            if (insertedIndex < 0) {
                failures.add("rich-content-block-add before block not found: document.nodes[] id=" + beforeBlockId);
                return null;
            }
        } else if (!afterBlockId.isBlank()) {
            int afterIndex = indexOfObjectByKey(nodes, "id", afterBlockId);
            if (afterIndex < 0) {
                failures.add("rich-content-block-add after block not found: document.nodes[] id=" + afterBlockId);
                return null;
            }
            insertedIndex = afterIndex + 1;
        }
        nodes.insert(insertedIndex, node);

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "insert-rich-block");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "document.nodes[]");
        compiled.put("keyValue", nodeId);
        compiled.put("insertedIndex", insertedIndex);
        if (!beforeBlockId.isBlank()) {
            compiled.put("beforeBlockId", beforeBlockId);
        }
        if (!afterBlockId.isBlank()) {
            compiled.put("afterBlockId", afterBlockId);
        }
        compiled.set("value", node);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileRichContentLinkRemove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode nodes = arrayAt(proposedConfig, "document.nodes", false);
        if (nodes == null) {
            failures.add("rich-content-link-remove path is not an array: document.nodes[]");
            return null;
        }
        int removedIndex = indexOfResolvedArrayTarget(resolved);
        if (removedIndex < 0 || removedIndex >= nodes.size()) {
            String linkId = resolved != null ? text(resolved.value(), "id") : "";
            removedIndex = indexOfObjectByKey(nodes, "id", linkId);
        }
        if (removedIndex < 0 || removedIndex >= nodes.size()) {
            failures.add("rich-content-link-remove target not found in document.nodes[]");
            return null;
        }
        JsonNode removedNode = nodes.get(removedIndex);
        String nodeType = firstText(removedNode, "type", "kind");
        if (!"link".equals(nodeType)) {
            failures.add("rich-content-link-remove target is not link: " + nodeType);
            return null;
        }
        String linkId = text(removedNode, "id");
        if (linkId.isBlank()) {
            failures.add("rich-content-link-remove requires link id");
            return null;
        }

        boolean preserveLabelAsText = planOperation.path("input").path("preserveLabelAsText").asBoolean(false);
        ObjectNode replacement = null;
        if (preserveLabelAsText) {
            String replacementId = linkId + "-text";
            if (indexOfObjectByKey(nodes, "id", replacementId) >= 0) {
                failures.add("rich-content-link-remove duplicate replacement node id: document.nodes[] id=" + replacementId);
                return null;
            }
            String label = firstText(removedNode, "label", "text", "title");
            if (label.isBlank()) {
                failures.add("rich-content-link-remove preserveLabelAsText requires link label");
                return null;
            }
            replacement = objectMapper.createObjectNode();
            replacement.put("id", replacementId);
            replacement.put("type", "text");
            replacement.put("text", label);
        }

        JsonNode removed = nodes.remove(removedIndex);
        if (replacement != null) {
            nodes.insert(removedIndex, replacement);
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", preserveLabelAsText ? "replace-rich-link-with-text" : "remove-rich-link");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "document.nodes[]");
        compiled.put("resolvedPath", resolved == null ? "" : resolved.path());
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", linkId);
        compiled.put("removedIndex", removedIndex);
        compiled.set("removedValue", removed);
        compiled.put("preserveLabelAsText", preserveLabelAsText);
        if (replacement != null) {
            compiled.set("replacementValue", replacement);
        }
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileRichContentTimelineItemAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode resolvedNode = resolved == null ? MissingNode.getInstance() : nodeAtResolvedPath(proposedConfig, resolved.path());
        if (!(resolvedNode instanceof ObjectNode timelineBlock)) {
            failures.add("rich-content-timeline-item-add target not found: " + (resolved == null ? "" : resolved.path()));
            return null;
        }
        String nodeType = firstText(timelineBlock, "type", "kind");
        if (!"timeline".equals(nodeType) && !"timelineBlock".equals(nodeType)) {
            failures.add("rich-content-timeline-item-add target is not timeline: " + nodeType);
            return null;
        }
        String timelineBlockId = text(timelineBlock, "id");
        String inputTimelineBlockId = text(planOperation.path("input"), "timelineBlockId");
        if (timelineBlockId.isBlank()) {
            failures.add("rich-content-timeline-item-add requires timeline block id");
            return null;
        }
        if (!inputTimelineBlockId.isBlank() && !timelineBlockId.equals(inputTimelineBlockId)) {
            failures.add("rich-content-timeline-item-add target does not match input timelineBlockId: " + inputTimelineBlockId);
            return null;
        }
        if (!(planOperation.path("input").path("item") instanceof ObjectNode inputItem)) {
            failures.add("rich-content-timeline-item-add requires item object");
            return null;
        }

        ArrayNode items;
        if (timelineBlock.path("items").isMissingNode() || timelineBlock.path("items").isNull()) {
            items = timelineBlock.putArray("items");
        } else if (timelineBlock.path("items") instanceof ArrayNode existingItems) {
            items = existingItems;
        } else {
            failures.add("rich-content-timeline-item-add path is not an array: document.nodes[].items[]");
            return null;
        }

        ObjectNode item = inputItem.deepCopy();
        if (!item.hasNonNull("id")) {
            item.put("id", nextRichTimelineItemId(items));
        }
        String itemId = text(item, "id");
        if (itemId.isBlank()) {
            failures.add("rich-content-timeline-item-add requires item.id or generated id");
            return null;
        }
        if (indexOfObjectByKey(items, "id", itemId) >= 0) {
            failures.add("rich-content-timeline-item-add duplicate item id: document.nodes[].items[] id=" + itemId);
            return null;
        }

        int insertedIndex = items.size();
        String beforeItemId = text(planOperation.path("input"), "beforeItemId");
        String afterItemId = text(planOperation.path("input"), "afterItemId");
        if (!beforeItemId.isBlank()) {
            insertedIndex = indexOfObjectByKey(items, "id", beforeItemId);
            if (insertedIndex < 0) {
                failures.add("rich-content-timeline-item-add before item not found: document.nodes[].items[] id=" + beforeItemId);
                return null;
            }
        } else if (!afterItemId.isBlank()) {
            int afterIndex = indexOfObjectByKey(items, "id", afterItemId);
            if (afterIndex < 0) {
                failures.add("rich-content-timeline-item-add after item not found: document.nodes[].items[] id=" + afterItemId);
                return null;
            }
            insertedIndex = afterIndex + 1;
        }
        items.insert(insertedIndex, item);

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "insert-rich-timeline-item");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "document.nodes[].items[]");
        compiled.put("resolvedPath", resolved == null ? "" : resolved.path());
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("timelineBlockId", timelineBlockId);
        compiled.put("keyValue", itemId);
        compiled.put("insertedIndex", insertedIndex);
        if (!beforeItemId.isBlank()) {
            compiled.put("beforeItemId", beforeItemId);
        }
        if (!afterItemId.isBlank()) {
            compiled.put("afterItemId", afterItemId);
        }
        compiled.set("value", item);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileRichContentTimelineItemUpdate(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode resolvedNode = resolved == null ? MissingNode.getInstance() : nodeAtResolvedPath(proposedConfig, resolved.path());
        if (!(resolvedNode instanceof ObjectNode item)) {
            failures.add("rich-content-timeline-item-update target not found: " + (resolved == null ? "" : resolved.path()));
            return null;
        }
        JsonNode input = planOperation.path("input");
        String timelineBlockId = text(input, "timelineBlockId");
        if (timelineBlockId.isBlank()) {
            failures.add("rich-content-timeline-item-update requires timelineBlockId");
            return null;
        }
        String itemIdBefore = text(item, "id");
        ObjectNode value = objectMapper.createObjectNode();
        if (input.path("patch") instanceof ObjectNode patch) {
            if (!applyTimelineItemPatch(item, patch, value, failures)) {
                return null;
            }
        }
        String field = text(input, "field");
        if (!field.isBlank()) {
            if (!timelineItemFields().contains(field)) {
                failures.add("rich-content-timeline-item-update unsupported field: " + field);
                return null;
            }
            if (!input.has("value")) {
                failures.add("rich-content-timeline-item-update field update requires value");
                return null;
            }
            item.set(field, input.path("value"));
            value.set(field, input.path("value"));
        }
        if (value.isEmpty()) {
            failures.add("rich-content-timeline-item-update requires patch or field");
            return null;
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "merge-rich-timeline-item");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "document.nodes[].items[]");
        compiled.put("resolvedPath", resolved == null ? "" : resolved.path());
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("timelineBlockId", timelineBlockId);
        compiled.put("keyValue", itemIdBefore);
        compiled.set("value", value);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private boolean applyTimelineItemPatch(
            ObjectNode item,
            ObjectNode patch,
            ObjectNode value,
            List<String> failures) {
        if (patch.has("id") && !text(item, "id").equals(text(patch, "id"))) {
            failures.add("rich-content-timeline-item-update cannot change item id");
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            if ("id".equals(fieldName)) {
                continue;
            }
            if (!timelineItemFields().contains(fieldName)) {
                failures.add("rich-content-timeline-item-update unsupported patch field: " + fieldName);
                return false;
            }
            item.set(fieldName, field.getValue());
            value.set(fieldName, field.getValue());
        }
        return true;
    }

    private ObjectNode compileTabsSetActiveItem(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode tabs = arrayAt(proposedConfig, "tabs", false);
        if (tabs == null) {
            failures.add("tabs.set-active-item path is not an array: tabs[]");
            return null;
        }
        int selectedIndex = indexOfResolvedArrayTarget(resolved);
        if (selectedIndex < 0 || selectedIndex >= tabs.size()) {
            String tabId = resolved != null ? text(resolved.value(), "id") : "";
            selectedIndex = indexOfObjectByKey(tabs, "id", tabId);
        }
        if (selectedIndex < 0 || selectedIndex >= tabs.size()) {
            failures.add("tabs.set-active-item target not found in tabs[]");
            return null;
        }
        JsonNode selectedTab = tabs.get(selectedIndex);
        String selectedTabId = text(selectedTab, "id");

        int groupSelectedIndexBefore = proposedConfig.path("group").path("selectedIndex").asInt(-1);
        int navSelectedIndexBefore = proposedConfig.path("nav").path("selectedIndex").asInt(-1);
        objectAt(proposedConfig, "group", true).put("selectedIndex", selectedIndex);
        if (proposedConfig.path("nav").isObject()) {
            objectAt(proposedConfig, "nav", true).put("selectedIndex", selectedIndex);
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "set-active-index");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "group.selectedIndex");
        compiled.put("resolvedPath", resolved != null ? resolved.path() : "");
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("selectedIndex", selectedIndex);
        compiled.put("selectedTabId", selectedTabId);
        compiled.put("groupSelectedIndexBefore", groupSelectedIndexBefore);
        compiled.put("groupSelectedIndexAfter", selectedIndex);
        compiled.put("navSelectedIndexBefore", navSelectedIndexBefore);
        if (proposedConfig.path("nav").isObject()) {
            compiled.put("navSelectedIndexAfter", selectedIndex);
        }
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileTabsRemoveAndReselect(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode tabs = arrayAt(proposedConfig, "tabs", false);
        if (tabs == null) {
            failures.add("tabs.remove-tab-and-reselect path is not an array: tabs[]");
            return null;
        }
        String tabId = resolved != null ? text(resolved.value(), "id") : "";
        int removedIndex = indexOfObjectByKey(tabs, "id", tabId);
        if (removedIndex < 0) {
            failures.add("tabs.remove-tab-and-reselect target not found: tabs[] id=" + tabId);
            return null;
        }

        JsonNode group = proposedConfig.path("group");
        int selectedIndexBefore = group.path("selectedIndex").asInt(-1);
        String selectedTabId = selectedIndexBefore >= 0 && selectedIndexBefore < tabs.size()
                ? text(tabs.get(selectedIndexBefore), "id")
                : "";
        String replacementActiveTabId = text(planOperation.path("input"), "replacementActiveTabId");

        tabs.remove(removedIndex);

        int selectedIndexAfter = selectedIndexBefore;
        if (tabs.isEmpty()) {
            selectedIndexAfter = -1;
            objectAt(proposedConfig, "group", true).put("selectedIndex", selectedIndexAfter);
        } else if (!replacementActiveTabId.isBlank()) {
            int replacementIndex = indexOfObjectByKey(tabs, "id", replacementActiveTabId);
            if (replacementIndex < 0) {
                failures.add("tabs.remove-tab-and-reselect replacement tab not found: tabs[] id=" + replacementActiveTabId);
                return null;
            }
            selectedIndexAfter = replacementIndex;
            objectAt(proposedConfig, "group", true).put("selectedIndex", selectedIndexAfter);
        } else if (tabId.equals(selectedTabId)) {
            selectedIndexAfter = Math.min(removedIndex, tabs.size() - 1);
            objectAt(proposedConfig, "group", true).put("selectedIndex", selectedIndexAfter);
        } else if (selectedIndexBefore > removedIndex) {
            selectedIndexAfter = selectedIndexBefore - 1;
            objectAt(proposedConfig, "group", true).put("selectedIndex", selectedIndexAfter);
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "remove-by-key");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "tabs[]");
        compiled.put("resolvedPath", resolved != null ? resolved.path() : "");
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", tabId);
        compiled.put("removedIndex", removedIndex);
        compiled.put("selectedIndexBefore", selectedIndexBefore);
        compiled.put("selectedIndexAfter", selectedIndexAfter);
        if (!replacementActiveTabId.isBlank()) {
            compiled.put("replacementActiveTabId", replacementActiveTabId);
        }
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileTabsReorderAndPreserveSelection(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode tabs = arrayAt(proposedConfig, "tabs", false);
        if (tabs == null) {
            failures.add("tabs.reorder-tab-and-preserve-selection path is not an array: tabs[]");
            return null;
        }
        String tabId = resolved != null ? text(resolved.value(), "id") : "";
        int fromIndex = indexOfObjectByKey(tabs, "id", tabId);
        if (fromIndex < 0) {
            failures.add("tabs.reorder-tab-and-preserve-selection target not found: tabs[] id=" + tabId);
            return null;
        }
        String beforeTabId = text(planOperation.path("input"), "beforeTabId");
        if (beforeTabId.isBlank()) {
            failures.add("tabs.reorder-tab-and-preserve-selection requires beforeTabId");
            return null;
        }
        int beforeIndex = indexOfObjectByKey(tabs, "id", beforeTabId);
        if (beforeIndex < 0) {
            failures.add("tabs.reorder-tab-and-preserve-selection before tab not found: tabs[] id=" + beforeTabId);
            return null;
        }

        JsonNode group = proposedConfig.path("group");
        int selectedIndexBefore = group.path("selectedIndex").asInt(-1);
        String selectedTabId = selectedIndexBefore >= 0 && selectedIndexBefore < tabs.size()
                ? text(tabs.get(selectedIndexBefore), "id")
                : "";

        JsonNode moved = tabs.remove(fromIndex);
        int targetIndex = beforeIndex;
        if (fromIndex < beforeIndex) {
            targetIndex = beforeIndex - 1;
        }
        tabs.insert(targetIndex, moved);

        int selectedIndexAfter = selectedIndexBefore;
        if (!selectedTabId.isBlank()) {
            selectedIndexAfter = indexOfObjectByKey(tabs, "id", selectedTabId);
            if (selectedIndexAfter >= 0) {
                objectAt(proposedConfig, "group", true).put("selectedIndex", selectedIndexAfter);
            }
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "reorder-by-key");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "tabs[]");
        compiled.put("resolvedPath", resolved != null ? resolved.path() : "");
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", tabId);
        compiled.put("fromIndex", fromIndex);
        compiled.put("toIndex", targetIndex);
        compiled.put("beforeTabId", beforeTabId);
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

    private int indexOfResolvedArrayTarget(AgenticAuthoringResolvedTarget resolved) {
        if (resolved == null || resolved.path() == null || resolved.path().isBlank()) {
            return -1;
        }
        int slash = resolved.path().lastIndexOf('/');
        if (slash < 0 || slash == resolved.path().length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(resolved.path().substring(slash + 1));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String nextRichContentNodeId(ArrayNode nodes, String type) {
        String normalizedType = type == null || type.isBlank()
                ? "block"
                : type.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase();
        for (int i = nodes.size() + 1; ; i++) {
            String candidate = normalizedType + "-" + i;
            if (indexOfObjectByKey(nodes, "id", candidate) < 0) {
                return candidate;
            }
        }
    }

    private String nextRichTimelineItemId(ArrayNode items) {
        for (int i = items.size() + 1; ; i++) {
            String candidate = "timeline-item-" + i;
            if (indexOfObjectByKey(items, "id", candidate) < 0) {
                return candidate;
            }
        }
    }

    private Set<String> timelineItemFields() {
        return Set.of(
                "title",
                "titleExpr",
                "subtitle",
                "subtitleExpr",
                "meta",
                "metaExpr",
                "icon",
                "iconExpr",
                "badge",
                "badgeExpr");
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
