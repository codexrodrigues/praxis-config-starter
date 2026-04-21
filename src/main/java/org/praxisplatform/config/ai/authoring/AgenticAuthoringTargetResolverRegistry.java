package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AgenticAuthoringTargetResolverRegistry {

    static final String STATUS_RESOLVED = "resolved";
    static final String STATUS_NOT_REQUIRED = "not-required";
    static final String STATUS_AMBIGUOUS = "ambiguous";
    static final String STATUS_NOT_FOUND = "not-found";

    private static final Set<String> GLOBAL_RESOLVERS = Set.of(
            "toolbar-config",
            "filter-config",
            "grouping-config",
            "selection-config",
            "export-config",
            "appearance-config",
            "expansion-config",
            "pagination-config",
            "sorting-config",
            "meta-config",
            "behavior-config",
            "messages-config",
            "localization-config",
            "performance-config",
            "accessibility-config",
            "settings-panel-config-root",
            "stepper-navigation-config",
            "stepper-layout-config",
            "tabs-layout-config",
            "component-config");

    public AgenticAuthoringTargetResolverRegistry() {
    }

    AgenticAuthoringResolvedTarget resolve(
            String componentId,
            JsonNode operation,
            JsonNode target,
            JsonNode config) {
        String operationId = text(operation, "operationId");
        JsonNode targetSpec = operation.path("target");
        String kind = text(targetSpec, "kind");
        String resolver = text(targetSpec, "resolver");
        boolean required = targetSpec.path("required").asBoolean(false);
        if (!required && (isEmptyTarget(target) || GLOBAL_RESOLVERS.contains(resolver))) {
            return new AgenticAuthoringResolvedTarget(
                    STATUS_NOT_REQUIRED,
                    componentId,
                    operationId,
                    kind,
                    resolver,
                    "",
                    MissingNode.getInstance(),
                    List.of(),
                    List.of());
        }
        if (config == null || config.isMissingNode() || config.isNull()) {
            return unresolved(componentId, operationId, kind, resolver, "config is required for target resolution");
        }
        if (GLOBAL_RESOLVERS.contains(resolver)) {
            return new AgenticAuthoringResolvedTarget(
                    STATUS_RESOLVED,
                    componentId,
                    operationId,
                    kind,
                    resolver,
                    "$",
                    config,
                    List.of("$"),
                    List.of());
        }
        String targetValue = targetValue(target);
        if (targetValue.isBlank()) {
            return unresolved(componentId, operationId, kind, resolver, "target value is required");
        }
        List<ResolvedCandidate> candidates = resolveCandidates(resolver, kind, config, targetValue);
        if (candidates.isEmpty() && !isSupportedResolver(resolver)) {
            return unresolved(componentId, operationId, kind, resolver, "unsupported target resolver: " + resolver);
        }
        return resolvedFromCandidates(componentId, operation, targetValue, candidates);
    }

    AgenticAuthoringResolvedTarget unresolved(
            String componentId,
            String operationId,
            String kind,
            String resolver,
            String failure) {
        return new AgenticAuthoringResolvedTarget(
                STATUS_NOT_FOUND,
                componentId,
                operationId == null ? "" : operationId,
                kind,
                resolver,
                "",
                MissingNode.getInstance(),
                List.of(),
                List.of(failure));
    }

    private List<ResolvedCandidate> resolveCandidates(String resolver, String kind, JsonNode config, String targetValue) {
        List<ResolvedCandidate> candidates = new ArrayList<>();
        switch (resolver) {
            case "column-by-field" -> addArrayMatches(candidates, config, "columns[]", List.of("field"), targetValue);
            case "x-ui-chart-metric-by-field" -> addArrayMatches(candidates, config, "chartDocument.metrics[]", List.of("field", "id", "name"), targetValue);
            case "field-by-name-or-label" -> addArrayMatches(candidates, config, "fieldMetadata[]", List.of("name", "label"), targetValue);
            case "field-by-name" -> addArrayMatches(candidates, config, "fieldMetadata[]", List.of("name"), targetValue);
            case "section-by-id-or-title" -> addArrayMatches(candidates, config, "sections[]", List.of("id", "title", "label"), targetValue);
            case "panel-by-id-or-title", "panel-content-by-id" -> addArrayMatches(candidates, config, "panels[]", List.of("id", "title", "label"), targetValue);
            case "step-by-id-or-label", "step-content-by-id" -> addArrayMatches(candidates, config, "steps[]", List.of("id", "label", "textLabel", "title"), targetValue);
            case "tab-by-id-or-label" -> addArrayMatches(candidates, config, "tabs[]", List.of("id", "label", "textLabel", "title"), targetValue);
            case "tab-index-or-id" -> addTabIndexOrIdMatches(candidates, config, targetValue);
            case "rich-block-by-id-or-index", "rich-text-node-by-id-or-path", "rich-media-node-by-id-or-path", "rich-link-node-by-id-or-path" -> addArrayMatches(candidates, config, "document.nodes[]", List.of("id", "path", "key"), targetValue);
            case "rule-by-id", "rule-by-id-or-name" -> addArrayMatches(candidates, config, "formRules[]", List.of("id", "name"), targetValue);
            case "action-by-id", "form-action-by-id", "actions-by-id" -> {
                addArrayMatches(candidates, config, "actions.custom[]", List.of("id", "name", "action"), targetValue);
                addArrayMatches(candidates, config, "actions[]", List.of("id", "name", "action"), targetValue);
                addArrayMatches(candidates, config, "toolbar.actions[]", List.of("id", "name", "action"), targetValue);
                addArrayMatches(candidates, config, "actions.row.actions[]", List.of("id", "name", "action"), targetValue);
            }
            case "renderer-in-column" -> addArrayMatches(candidates, config, "columns[]", List.of("field", "id", "key"), targetValue);
            case "conditional-renderer-in-column" -> addRecursiveArrayMatches(candidates, config, "conditionalRenderers", List.of("id", "key"), targetValue);
            case "style-rule-in-column-or-row" -> {
                addArrayMatches(candidates, config, "rowConditionalStyles[]", List.of("id", "key"), targetValue);
                addRecursiveArrayMatches(candidates, config, "conditionalStyles", List.of("id", "key"), targetValue);
            }
            case "component-metadata-editorial-descriptor" -> addRecursiveArrayMatches(candidates, config, "controlProfiles", List.of("controlType", "type", "id", "name"), targetValue);
            case "field-metadata-json-path" -> addRecursiveObjectMatches(candidates, config, List.of("path", "jsonPath", "name", "id"), targetValue);
            case "row-by-id-in-section" -> addRecursiveArrayMatches(candidates, config, "rows", List.of("id"), targetValue);
            case "column-by-id-in-row" -> addRecursiveArrayMatches(candidates, config, "columns", List.of("id"), targetValue);
            default -> {
                if (kind != null && !kind.isBlank()) {
                    addRecursiveObjectMatches(candidates, config, List.of("id", "field", "name", "key", "label", "title"), targetValue);
                }
            }
        }
        return candidates;
    }

    private AgenticAuthoringResolvedTarget resolvedFromCandidates(
            String componentId,
            JsonNode operation,
            String targetValue,
            List<ResolvedCandidate> candidates) {
        String operationId = text(operation, "operationId");
        String kind = text(operation.path("target"), "kind");
        String resolver = text(operation.path("target"), "resolver");
        String ambiguityPolicy = text(operation.path("target"), "ambiguityPolicy");
        if (candidates.size() == 1) {
            ResolvedCandidate candidate = candidates.get(0);
            return new AgenticAuthoringResolvedTarget(
                    STATUS_RESOLVED,
                    componentId,
                    operationId,
                    kind,
                    resolver,
                    candidate.path(),
                    candidate.value(),
                    List.of(candidate.path()),
                    List.of());
        }
        if (candidates.size() > 1) {
            if ("first".equals(ambiguityPolicy)) {
                ResolvedCandidate candidate = candidates.get(0);
                return new AgenticAuthoringResolvedTarget(
                        STATUS_RESOLVED,
                        componentId,
                        operationId,
                        kind,
                        resolver,
                        candidate.path(),
                        candidate.value(),
                        candidates.stream().map(ResolvedCandidate::path).toList(),
                        List.of("target matched multiple candidates; first candidate selected by ambiguityPolicy=first"));
            }
            return new AgenticAuthoringResolvedTarget(
                    STATUS_AMBIGUOUS,
                    componentId,
                    operationId,
                    kind,
                    resolver,
                    "",
                    MissingNode.getInstance(),
                    candidates.stream().map(ResolvedCandidate::path).toList(),
                    List.of("target is ambiguous: " + targetValue));
        }
        return unresolved(componentId, operationId, kind, resolver, "target not found: " + targetValue);
    }

    private boolean isSupportedResolver(String resolver) {
        return GLOBAL_RESOLVERS.contains(resolver)
                || Set.of(
                "column-by-field",
                "x-ui-chart-metric-by-field",
                "field-by-name",
                "field-by-name-or-label",
                "section-by-id-or-title",
                "panel-by-id-or-title",
                "panel-content-by-id",
                "step-by-id-or-label",
                "step-content-by-id",
                "tab-by-id-or-label",
                "tab-index-or-id",
                "rich-block-by-id-or-index",
                "rich-text-node-by-id-or-path",
                "rich-media-node-by-id-or-path",
                "rich-link-node-by-id-or-path",
                "rule-by-id",
                "rule-by-id-or-name",
                "action-by-id",
                "actions-by-id",
                "form-action-by-id",
                "renderer-in-column",
                "conditional-renderer-in-column",
                "style-rule-in-column-or-row",
                "component-metadata-editorial-descriptor",
                "field-metadata-json-path",
                "row-by-id-in-section",
                "column-by-id-in-row").contains(resolver);
    }

    private void addArrayMatches(
            List<ResolvedCandidate> candidates,
            JsonNode config,
            String arrayPath,
            List<String> keys,
            String targetValue) {
        JsonNode array = resolvePath(config, arrayPath.substring(0, arrayPath.length() - 2));
        if (!array.isArray()) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonNode item = array.get(i);
            if (matchesAnyKey(item, keys, targetValue)) {
                candidates.add(new ResolvedCandidate(arrayPath + "/" + i, item));
            }
        }
    }

    private void addTabIndexOrIdMatches(List<ResolvedCandidate> candidates, JsonNode config, String targetValue) {
        JsonNode tabs = resolvePath(config, "tabs");
        if (!tabs.isArray()) {
            return;
        }
        Integer index = parseNonNegativeInt(targetValue);
        if (index != null) {
            if (index < tabs.size()) {
                candidates.add(new ResolvedCandidate("tabs[]/" + index, tabs.get(index)));
            }
            return;
        }
        addArrayMatches(candidates, config, "tabs[]", List.of("id", "label", "textLabel", "title"), targetValue);
    }

    private void addRecursiveArrayMatches(
            List<ResolvedCandidate> candidates,
            JsonNode node,
            String arrayName,
            List<String> keys,
            String targetValue) {
        addRecursiveArrayMatches(candidates, node, arrayName, keys, targetValue, "");
    }

    private void addRecursiveArrayMatches(
            List<ResolvedCandidate> candidates,
            JsonNode node,
            String arrayName,
            List<String> keys,
            String targetValue,
            String path) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String nextPath = path.isBlank() ? field.getKey() : path + "." + field.getKey();
                JsonNode value = field.getValue();
                if (field.getKey().equals(arrayName) && value.isArray()) {
                    for (int i = 0; i < value.size(); i++) {
                        JsonNode item = value.get(i);
                        if (matchesAnyKey(item, keys, targetValue)) {
                            candidates.add(new ResolvedCandidate(nextPath + "[]/" + i, item));
                        }
                        addRecursiveArrayMatches(candidates, item, arrayName, keys, targetValue, nextPath + "[]/" + i);
                    }
                } else {
                    addRecursiveArrayMatches(candidates, value, arrayName, keys, targetValue, nextPath);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                addRecursiveArrayMatches(candidates, node.get(i), arrayName, keys, targetValue, path + "[]/" + i);
            }
        }
    }

    private void addRecursiveObjectMatches(
            List<ResolvedCandidate> candidates,
            JsonNode node,
            List<String> keys,
            String targetValue) {
        addRecursiveObjectMatches(candidates, node, keys, targetValue, "");
    }

    private void addRecursiveObjectMatches(
            List<ResolvedCandidate> candidates,
            JsonNode node,
            List<String> keys,
            String targetValue,
            String path) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            if (matchesAnyKey(node, keys, targetValue)) {
                candidates.add(new ResolvedCandidate(path, node));
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                addRecursiveObjectMatches(
                        candidates,
                        field.getValue(),
                        keys,
                        targetValue,
                        path.isBlank() ? field.getKey() : path + "." + field.getKey());
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                addRecursiveObjectMatches(candidates, node.get(i), keys, targetValue, path + "[]/" + i);
            }
        }
    }

    private boolean matchesAnyKey(JsonNode item, List<String> keys, String targetValue) {
        for (String key : keys) {
            if (targetValue.equals(text(item, key))) {
                return true;
            }
        }
        return false;
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

    private boolean isEmptyTarget(JsonNode target) {
        return target == null
                || target.isMissingNode()
                || target.isNull()
                || (target.isTextual() && target.asText().isBlank())
                || (target.isObject() && target.isEmpty());
    }

    private String targetValue(JsonNode target) {
        if (target == null || target.isMissingNode() || target.isNull()) {
            return "";
        }
        if (target.isTextual() || target.isNumber() || target.isBoolean()) {
            return target.asText("");
        }
        for (String field : List.of("id", "field", "name", "key", "value")) {
            if (target.has(field)) {
                return target.path(field).asText("");
            }
        }
        return "";
    }

    private Integer parseNonNegativeInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }

    private record ResolvedCandidate(String path, JsonNode value) {
    }
}
