package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
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
            "expansion-behavior-config",
            "pagination-config",
            "sorting-config",
            "meta-config",
            "behavior-config",
            "messages-config",
            "localization-config",
            "performance-config",
            "accessibility-config",
            "praxis-dialog-config-root",
            "praxis-dialog-size-fields",
            "praxis-dialog-position-fields",
            "praxis-dialog-backdrop-fields",
            "praxis-dialog-close-policy-fields",
            "praxis-dialog-global-preset-by-type-variant",
            "praxis-dialog-child-component-or-template-host",
            "settings-panel-config-root",
            "settings-panel-open-mode",
            "settings-panel-size-and-resize",
            "settings-value-provider-apply-contract",
            "settings-value-provider-save-contract",
            "settings-value-provider-reset-contract",
            "settings-panel-editor-host",
            "settings-panel-state-diagnostics",
            "stepper-navigation-config",
            "stepper-layout-config",
            "tabs-layout-config",
            "files-accept-list",
            "max-file-size-policy",
            "max-files-per-bulk-policy",
            "files-api-base-url-presign-contract",
            "files-api-base-url-upload-contract",
            "upload-security-policy",
            "upload-error-code-message",
            "files-upload-display-mode-and-ui",
            "schedule-expression",
            "schedule-kind-and-recurrence",
            "schedule-timezone",
            "schedule-validation-request",
            "schedule-preview-config",
            "crud-resource-by-path-or-key",
            "crud-list-surface",
            "crud-dialog-host-defaults",
            "crud-resource-capabilities",
            "child-authoring-manifest-operation",
            "x-ui-chart-document-root",
            "x-ui-chart-kind",
            "x-ui-chart-dimension-or-metric-axis",
            "x-ui-chart-source-and-field-catalog",
            "x-ui-chart-gauge-scale",
            "praxis-chart-query-context",
            "x-ui-chart-events-point-click",
            "x-ui-chart-events-cross-filter",
            "x-ui-chart-events-drill-down",
            "x-ui-chart-events-selection-change",
            "x-ui-chart-legend-feature",
            "x-ui-chart-tooltip-feature",
            "default-effect-preset-by-key",
            "praxis-table-authoring-operation",
            "conditional-renderer-in-row",
            "action-in-toolbar-config",
            "action-in-row-config",
            "action-in-bulk-config",
            "action-in-context-config",
            "table-meta-config",
            "schema-column-projection",
            "interaction-config",
            "loading-config",
            "empty-state-config",
            "resizing-config",
            "dragging-config",
            "editing-config",
            "table-runtime-operation-contract",
            "data-config",
            "rule-builder-json-logic-document",
            "metadata-editor-schema-normalizer",
            "manual-form-layout",
            "manual-form-customization-toolbar",
            "manual-form-autosave-policy",
            "manual-field-metadata-bridge",
            "manual-form-submit-behavior",
            "editorial-runtime-snapshot",
            "editorial-runtime-fallback-state",
            "editorial-solution-presentation",
            "widget-page-definition-root",
            "widget-page-canvas",
            "page-builder-persistence-identity",
            "agentic-preview-result",
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
        JsonNode resolutionSpec = targetSpec.path("resolutionSpec");
        String kind = text(targetSpec, "kind");
        String resolver = text(targetSpec, "resolver");
        boolean required = targetSpec.path("required").asBoolean(false);
        boolean rootPathGlobal = isRootPathGlobalResolution(resolver, resolutionSpec);
        if (!required && (isEmptyTarget(target) || rootPathGlobal)) {
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
        if (rootPathGlobal) {
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
        String targetValue = targetValue(target, resolutionSpec);
        if (targetValue.isBlank() && !objectTargetResolverAcceptsBlankValue(resolver, resolutionSpec)) {
            return unresolved(componentId, operationId, kind, resolver, "target value is required");
        }
        List<ResolvedCandidate> candidates = resolveCandidates(resolver, kind, config, target, targetValue, resolutionSpec);
        if (candidates.isEmpty() && !isSupportedResolver(resolver) && !hasResolutionSpec(resolutionSpec)) {
            return unresolved(componentId, operationId, kind, resolver, "unsupported target resolver: " + resolver);
        }
        return resolvedFromCandidates(componentId, operation, targetValue.isBlank() ? target.toString() : targetValue, candidates);
    }

    private boolean objectTargetResolverAcceptsBlankValue(String resolver, JsonNode resolutionSpec) {
        String strategy = text(resolutionSpec, "strategy");
        return "rich-timeline-item-by-block-id-and-item-id".equals(resolver)
                || "editorial-journey-step-block-by-id".equals(resolver)
                || "editorial-data-block-field-binding".equals(resolver)
                || "page-state-path".equals(resolver)
                || "widget-child-authoring-manifest-operation".equals(resolver)
                || (!isSupportedResolver(resolver)
                        && ("root-path".equals(strategy) || "parent-child-array-match".equals(strategy)));
    }

    private boolean isRootPathGlobalResolution(String resolver, JsonNode resolutionSpec) {
        if (GLOBAL_RESOLVERS.contains(resolver)) {
            return true;
        }
        if (isSupportedResolver(resolver) || !hasResolutionSpec(resolutionSpec)) {
            return false;
        }
        if (!"root-path".equals(text(resolutionSpec, "strategy").trim())) {
            return false;
        }
        JsonNode paths = resolutionSpec.path("paths");
        return paths.size() == 1 && "$".equals(paths.get(0).asText("").trim());
    }

    private boolean hasResolutionSpec(JsonNode resolutionSpec) {
        return resolutionSpec != null
                && resolutionSpec.isObject()
                && !text(resolutionSpec, "strategy").isBlank()
                && resolutionSpec.path("paths").isArray()
                && !resolutionSpec.path("paths").isEmpty();
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

    private List<ResolvedCandidate> resolveCandidates(
            String resolver,
            String kind,
            JsonNode config,
            JsonNode target,
            String targetValue,
            JsonNode resolutionSpec) {
        List<ResolvedCandidate> candidates = new ArrayList<>();
        if (!isSupportedResolver(resolver) && hasResolutionSpec(resolutionSpec)) {
            addResolutionSpecMatches(candidates, config, resolutionSpec, target, targetValue);
            return candidates;
        }
        switch (resolver) {
            case "column-by-field" -> addArrayMatches(
                    candidates,
                    config,
                    "columns[]",
                    List.of("field", "header", "label", "title"),
                    targetValue);
            case "x-ui-chart-metric-by-field" -> addArrayMatches(candidates, config, "chartDocument.metrics[]", List.of("field", "id", "name"), targetValue);
            case "field-by-name-or-label" -> addArrayMatches(candidates, config, "fieldMetadata[]", List.of("name", "label"), targetValue);
            case "field-by-name" -> addArrayMatches(candidates, config, "fieldMetadata[]", List.of("name"), targetValue);
            case "section-by-id-or-title" -> addArrayMatches(candidates, config, "sections[]", List.of("id", "title", "label"), targetValue);
            case "panel-by-id-or-title", "panel-content-by-id" -> addArrayMatches(candidates, config, "panels[]", List.of("id", "title", "label"), targetValue);
            case "step-by-id-or-label", "step-content-by-id", "step-validation-by-id" -> addArrayMatches(candidates, config, "steps[]", List.of("id", "label", "textLabel", "title"), targetValue);
            case "tab-by-id-or-label" -> addArrayMatches(candidates, config, "tabs[]", List.of("id", "label", "textLabel", "title"), targetValue);
            case "tab-or-link-by-id" -> {
                addArrayMatches(candidates, config, "tabs[]", List.of("id", "label", "textLabel", "title"), targetValue);
                addArrayMatches(candidates, config, "nav.links[]", List.of("id", "label", "textLabel", "title"), targetValue);
            }
            case "tab-index-or-id" -> addTabIndexOrIdMatches(candidates, config, targetValue, resolutionSpec);
            case "list-template-slot" -> addListTemplateSlotMatch(candidates, config, targetValue);
            case "cron-preset-by-label-or-expression" -> addCronPresetMatches(candidates, config, targetValue);
            case "rich-block-by-id-or-index", "rich-block-node-by-id-or-path", "rich-text-node-by-id-or-path", "rich-media-node-by-id-or-path", "rich-link-node-by-id-or-path", "rich-timeline-node-by-id-or-path" -> addRichContentNodeMatches(candidates, config, resolver, targetValue, resolutionSpec);
            case "rich-timeline-item-by-block-id-and-item-id" -> addRichTimelineItemMatches(candidates, config, target, targetValue, resolutionSpec);
            case "rule-by-id", "rule-by-id-or-name" -> addArrayMatches(candidates, config, "formRules[]", List.of("id", "name"), targetValue);
            case "action-by-id", "form-action-by-id", "actions-by-id" -> {
                addArrayMatches(candidates, config, "actions.custom[]", List.of("id", "name", "action"), targetValue);
                addArrayMatches(candidates, config, "actions[]", List.of("id", "name", "action"), targetValue);
                addArrayMatches(candidates, config, "toolbar.actions[]", List.of("id", "name", "action"), targetValue);
                addArrayMatches(candidates, config, "actions.row.actions[]", List.of("id", "name", "action"), targetValue);
            }
            case "crud-action-by-id:create" -> addArrayMatches(candidates, config, "actions[]", List.of("id", "name", "action"), "create");
            case "crud-action-by-id:edit" -> addArrayMatches(candidates, config, "actions[]", List.of("id", "name", "action"), "edit");
            case "crud-action-by-id:view" -> addArrayMatches(candidates, config, "actions[]", List.of("id", "name", "action"), "view");
            case "crud-action-by-id:delete" -> addArrayMatches(candidates, config, "actions[]", List.of("id", "name", "action"), "delete");
            case "renderer-in-column" -> addArrayMatches(
                    candidates,
                    config,
                    "columns[]",
                    List.of("field", "id", "key", "header", "label", "title"),
                    targetValue);
            case "conditional-renderer-in-column" -> addRecursiveArrayMatches(candidates, config, "conditionalRenderers", List.of("id", "key"), targetValue);
            case "style-rule-in-column-or-row" -> {
                addArrayMatches(candidates, config, "rowConditionalStyles[]", List.of("id", "key"), targetValue);
                addRecursiveArrayMatches(candidates, config, "conditionalStyles", List.of("id", "key"), targetValue);
            }
            case "field-control-type-token", "component-registry-coverage", "dynamic-fields-control-type-discovery" -> {
                addArrayMatches(candidates, config, "componentRegistry[]", List.of("controlType", "type", "id", "name"), targetValue);
                addRecursiveArrayMatches(candidates, config, "controlProfiles", List.of("controlType", "type", "id", "name"), targetValue);
                addArrayMatches(candidates, config, "editorCoverage[]", List.of("controlType", "type", "id", "name"), targetValue);
            }
            case "normalized-control-type-alias" -> addArrayMatches(candidates, config, "controlTypeAliases[]", List.of("alias", "normalizedAlias", "id"), normalizeToken(targetValue));
            case "field-selector-registry-entry" -> addArrayMatches(candidates, config, "selectorMappings[]", List.of("selector", "id"), targetValue);
            case "metadata-editor-tooling-coverage" -> addArrayMatches(candidates, config, "editorCoverage[]", List.of("controlType", "type", "id", "name"), targetValue);
            case "component-metadata-editorial-descriptor" -> addRecursiveArrayMatches(candidates, config, "controlProfiles", List.of("controlType", "type", "id", "name"), targetValue);
            case "field-metadata-json-path" -> addFieldMetadataPathMatches(candidates, config, targetValue);
            case "field-metadata-option-source" -> addMetadataRootMatch(candidates, config, "fieldMetadata.optionSource");
            case "metadata-editor-cascade-rules" -> addMetadataRootMatch(candidates, config, "fieldMetadata");
            case "metadata-editor-renderer-property" -> {
                int before = candidates.size();
                addArrayMatches(candidates, config, "properties[]", List.of("name", "propertyName", "id", "key"), targetValue);
                if (candidates.size() == before) {
                    addArrayMatches(candidates, config, "editorCoverage[]", List.of("propertyName", "name", "id", "key"), targetValue);
                }
            }
            case "field-metadata-validation-rules" -> addMetadataRootMatch(candidates, config, "fieldMetadata.validators");
            case "metadata-editor-context-hints" -> addMetadataRootMatch(candidates, config, "fieldMetadata");
            case "manual-form-field-by-name" -> addManualFieldMatches(candidates, config, targetValue);
            case "row-by-id-in-section" -> addRecursiveArrayMatches(candidates, config, "rows", List.of("id"), targetValue);
            case "column-by-id-in-row" -> addRecursiveArrayMatches(candidates, config, "columns", List.of("id"), targetValue);
            case "layout-item-by-id" -> addRecursiveArrayMatches(candidates, config, "items", List.of("id", "key"), targetValue);
            case "table-rule-by-stable-id", "table-rule-condition-by-rule-id" -> addArrayMatches(candidates, config, "ruleEffects.rules[]", List.of("ruleId", "id"), targetValue);
            case "rule-effect-by-rule-and-effect-id", "rule-animation-by-rule-and-effect-id" -> addTableRuleEffectMatches(candidates, config, target, targetValue);
            case "rule-node-by-id", "json-logic-condition-by-node", "property-effect-by-node" ->
                    addArrayMatches(candidates, config, "nodes[]", List.of("id", "nodeId"), targetValue);
            case "rule-node-edge-by-source-target" -> addVisualBuilderEdgeMatches(candidates, config, target);
            case "context-variable-by-name-scope" -> addVisualBuilderVariableMatches(candidates, config, target);
            case "editorial-data-block-adapter-registry" -> addEditorialAdapterMatches(candidates, config, targetValue);
            case "editorial-journey-step-block-by-id" -> addEditorialBlockMatches(candidates, config, target, targetValue);
            case "editorial-data-block-field-binding" -> addEditorialFieldBindingMatches(candidates, config, target);
            case "widget-by-stable-key", "widget-shell-by-widget-key" -> addArrayMatches(candidates, config, "widgets[]", List.of("widgetKey", "key", "id"), targetValue);
            case "composition-link-by-id" -> addArrayMatches(candidates, config, "composition.links[]", List.of("linkId", "id"), targetValue);
            case "page-state-path" -> addPageStatePathMatch(candidates, config, target);
            case "widget-child-authoring-manifest-operation" -> addPageBuilderChildOperationMatch(candidates, config, target);
            default -> {
                if (hasResolutionSpec(resolutionSpec)) {
                    addResolutionSpecMatches(candidates, config, resolutionSpec, target, targetValue);
                } else if (kind != null && !kind.isBlank()) {
                    addRecursiveObjectMatches(candidates, config, List.of("id", "field", "name", "key", "label", "title"), targetValue);
                }
            }
        }
        return candidates;
    }

    private void addResolutionSpecMatches(
            List<ResolvedCandidate> candidates,
            JsonNode config,
            JsonNode resolutionSpec,
            JsonNode target,
            String targetValue) {
        String strategy = text(resolutionSpec, "strategy").trim();
        List<String> paths = stringList(resolutionSpec.path("paths"), List.of());
        List<String> matchFields = stringList(
                resolutionSpec.path("matchFields"),
                List.of("id", "field", "name", "key", "label", "title"));
        List<String> childrenFields = stringList(
                resolutionSpec.path("childrenFields"),
                List.of("children", "nodes", "items", "tabs", "content", "header", "body", "footer", "aside", "trailing"));
        String typeField = firstNonBlank(text(resolutionSpec, "typeField").trim(), "type");
        List<String> allowedTypes = stringList(resolutionSpec.path("allowedTypes"), List.of());
        boolean allowIndex = resolutionSpec.path("allowIndex").asBoolean(true);

        switch (strategy) {
            case "root-path" -> {
                for (String rawPath : paths) {
                    String cleanPath = rawPath == null ? "" : rawPath.trim();
                    if (cleanPath.isBlank() || "$".equals(cleanPath)) {
                        candidates.add(new ResolvedCandidate("$", config));
                        return;
                    }
                    JsonNode resolved = resolvePath(config, cleanPath);
                    if (!resolved.isMissingNode() && !resolved.isNull()) {
                        candidates.add(new ResolvedCandidate(cleanPath, resolved));
                        return;
                    }
                }
            }
            case "array-match" -> {
                for (String rawPath : paths) {
                    String cleanPath = stripArraySuffix(rawPath);
                    JsonNode array = resolvePath(config, cleanPath);
                    if (!array.isArray()) {
                        continue;
                    }
                    for (int i = 0; i < array.size(); i++) {
                        JsonNode item = array.get(i);
                        if (matchesAllowedTypes(item, typeField, allowedTypes)
                                && matchesAnyKey(item, matchFields, targetValue)) {
                            candidates.add(new ResolvedCandidate(cleanPath + "[]/" + i, item));
                        }
                    }
                }
                if (candidates.isEmpty() && allowIndex) {
                    Integer index = parseNonNegativeInt(targetValue);
                    if (index != null) {
                        for (String rawPath : paths) {
                            String cleanPath = stripArraySuffix(rawPath);
                            JsonNode array = resolvePath(config, cleanPath);
                            if (!array.isArray() || index >= array.size()) {
                                continue;
                            }
                            JsonNode item = array.get(index);
                            if (matchesAllowedTypes(item, typeField, allowedTypes)) {
                                candidates.add(new ResolvedCandidate(cleanPath + "[]/" + index, item));
                            }
                        }
                    }
                }
            }
            case "recursive-array-match" -> {
                for (String rawPath : paths) {
                    String cleanPath = stripArraySuffix(rawPath);
                    JsonNode array = resolvePath(config, cleanPath);
                    if (!array.isArray()) {
                        continue;
                    }
                    addResolutionSpecRecursiveMatches(
                            candidates,
                            array,
                            cleanPath + "[]",
                            matchFields,
                            childrenFields,
                            typeField,
                            allowedTypes,
                            targetValue);
                    if (candidates.isEmpty() && allowIndex) {
                        Integer index = parseNonNegativeInt(targetValue);
                        if (index != null && index < array.size()) {
                            JsonNode item = array.get(index);
                            if (matchesAllowedTypes(item, typeField, allowedTypes)) {
                                candidates.add(new ResolvedCandidate(cleanPath + "[]/" + index, item));
                            }
                        }
                    }
                    if (!candidates.isEmpty()) {
                        return;
                    }
                }
            }
            case "parent-child-array-match" -> {
                String childArrayField = text(resolutionSpec, "childArrayField").trim();
                if (childArrayField.isBlank()) {
                    return;
                }
                List<String> parentInputFields = stringList(
                        resolutionSpec.path("parentInputMatchFields"),
                        List.of("parentId", "blockId", "timelineBlockId"));
                List<String> childInputFields = stringList(
                        resolutionSpec.path("inputMatchFields"),
                        List.of("itemId", "id"));
                String parentKey = extractFieldFromObject(target, parentInputFields);
                String childKey = extractFieldFromObject(target, childInputFields);
                if (parentKey.isBlank() && childKey.isBlank() && !targetValue.isBlank()) {
                    int sep = targetValue.indexOf('/');
                    if (sep < 0) {
                        sep = targetValue.indexOf(':');
                    }
                    if (sep > 0) {
                        parentKey = targetValue.substring(0, sep).trim();
                        childKey = targetValue.substring(sep + 1).trim();
                    } else {
                        childKey = targetValue;
                    }
                }
                for (String rawPath : paths) {
                    String cleanPath = stripArraySuffix(rawPath);
                    JsonNode array = resolvePath(config, cleanPath);
                    if (!array.isArray()) {
                        continue;
                    }
                    List<ResolvedCandidate> parentCandidates = new ArrayList<>();
                    if (!parentKey.isBlank()) {
                        addResolutionSpecRecursiveMatches(
                                parentCandidates,
                                array,
                                cleanPath + "[]",
                                List.of("id", "key", "field", "name"),
                                childrenFields,
                                typeField,
                                allowedTypes,
                                parentKey);
                    } else {
                        collectAllAllowedNodesRecursively(
                                parentCandidates,
                                array,
                                cleanPath + "[]",
                                childrenFields,
                                typeField,
                                allowedTypes);
                    }
                    for (ResolvedCandidate parent : parentCandidates) {
                        JsonNode children = parent.value().path(childArrayField);
                        if (!children.isArray()) {
                            continue;
                        }
                        int before = candidates.size();
                        for (int i = 0; i < children.size(); i++) {
                            JsonNode child = children.get(i);
                            if (!childKey.isBlank() && matchesAnyKey(child, matchFields, childKey)) {
                                candidates.add(new ResolvedCandidate(
                                        parent.path() + "." + childArrayField + "[]/" + i,
                                        child));
                            }
                        }
                        if (candidates.size() == before && allowIndex) {
                            Integer childIndex = parseNonNegativeInt(
                                    !childKey.isBlank() ? childKey : text(target, "itemIndex"));
                            if (childIndex != null && childIndex < children.size()) {
                                candidates.add(new ResolvedCandidate(
                                        parent.path() + "." + childArrayField + "[]/" + childIndex,
                                        children.get(childIndex)));
                            }
                        }
                    }
                    if (!candidates.isEmpty()) {
                        return;
                    }
                }
            }
            default -> {
            }
        }
    }

    private void addResolutionSpecRecursiveMatches(
            List<ResolvedCandidate> candidates,
            JsonNode array,
            String arrayPathPrefix,
            List<String> matchFields,
            List<String> childrenFields,
            String typeField,
            List<String> allowedTypes,
            String targetValue) {
        if (array == null || !array.isArray() || targetValue == null || targetValue.isBlank()) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonNode item = array.get(i);
            if (item == null || !item.isObject()) {
                continue;
            }
            String itemPath = arrayPathPrefix + "/" + i;
            if (matchesAllowedTypes(item, typeField, allowedTypes)
                    && matchesAnyKey(item, matchFields, targetValue)) {
                candidates.add(new ResolvedCandidate(itemPath, item));
            }
            for (String childField : childrenFields) {
                JsonNode childNode = item.path(childField);
                if (childNode.isArray()) {
                    addResolutionSpecRecursiveMatches(
                            candidates,
                            childNode,
                            itemPath + "." + childField + "[]",
                            matchFields,
                            childrenFields,
                            typeField,
                            allowedTypes,
                            targetValue);
                    for (int subIdx = 0; subIdx < childNode.size(); subIdx++) {
                        JsonNode subItem = childNode.get(subIdx);
                        if (subItem != null && subItem.isObject()) {
                            for (String nestedField : childrenFields) {
                                JsonNode nestedArray = subItem.path(nestedField);
                                if (nestedArray.isArray()) {
                                    addResolutionSpecRecursiveMatches(
                                            candidates,
                                            nestedArray,
                                            itemPath + "." + childField + "[]/" + subIdx + "." + nestedField + "[]",
                                            matchFields,
                                            childrenFields,
                                            typeField,
                                            allowedTypes,
                                            targetValue);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void collectAllAllowedNodesRecursively(
            List<ResolvedCandidate> candidates,
            JsonNode array,
            String arrayPathPrefix,
            List<String> childrenFields,
            String typeField,
            List<String> allowedTypes) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonNode item = array.get(i);
            if (item == null || !item.isObject()) {
                continue;
            }
            String itemPath = arrayPathPrefix + "/" + i;
            if (matchesAllowedTypes(item, typeField, allowedTypes)) {
                candidates.add(new ResolvedCandidate(itemPath, item));
            }
            for (String childField : childrenFields) {
                JsonNode childNode = item.path(childField);
                if (childNode.isArray()) {
                    collectAllAllowedNodesRecursively(
                            candidates,
                            childNode,
                            itemPath + "." + childField + "[]",
                            childrenFields,
                            typeField,
                            allowedTypes);
                }
            }
        }
    }

    private boolean matchesAllowedTypes(JsonNode item, String typeField, List<String> allowedTypes) {
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            return true;
        }
        String actualType = text(item, typeField).trim();
        return allowedTypes.contains(actualType);
    }

    private String stripArraySuffix(String rawPath) {
        if (rawPath == null) {
            return "";
        }
        String trimmed = rawPath.trim();
        return trimmed.endsWith("[]") ? trimmed.substring(0, trimmed.length() - 2) : trimmed;
    }

    private List<String> stringList(JsonNode arrayNode, List<String> fallback) {
        if (arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty()) {
            return fallback;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            if (item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values.isEmpty() ? fallback : List.copyOf(values);
    }

    private String extractFieldFromObject(JsonNode node, List<String> fields) {
        if (node == null || !node.isObject() || fields == null) {
            return "";
        }
        for (String field : fields) {
            String value = text(node, field).trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
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
                "step-validation-by-id",
                "tab-by-id-or-label",
                "tab-or-link-by-id",
                "tab-index-or-id",
                "list-template-slot",
                "cron-preset-by-label-or-expression",
                "rich-block-by-id-or-index", "rich-block-node-by-id-or-path",
                "rich-text-node-by-id-or-path",
                "rich-media-node-by-id-or-path",
                "rich-link-node-by-id-or-path",
                "rich-timeline-node-by-id-or-path",
                "rich-timeline-item-by-block-id-and-item-id",
                "rule-by-id",
                "rule-by-id-or-name",
                "action-by-id",
                "actions-by-id",
                "form-action-by-id",
                "crud-action-by-id:create",
                "crud-action-by-id:edit",
                "crud-action-by-id:view",
                "crud-action-by-id:delete",
                "renderer-in-column",
                "conditional-renderer-in-column",
                "style-rule-in-column-or-row",
                "field-control-type-token",
                "component-registry-coverage",
                "normalized-control-type-alias",
                "field-selector-registry-entry",
                "metadata-editor-tooling-coverage",
                "component-metadata-editorial-descriptor",
                "field-metadata-json-path",
                "dynamic-fields-control-type-discovery",
                "field-metadata-option-source",
                "metadata-editor-cascade-rules",
                "metadata-editor-renderer-property",
                "field-metadata-validation-rules",
                "metadata-editor-context-hints",
                "manual-form-field-by-name",
                "row-by-id-in-section",
                "column-by-id-in-row",
                "layout-item-by-id",
                "table-rule-by-stable-id",
                "table-rule-condition-by-rule-id",
                "rule-effect-by-rule-and-effect-id",
                "rule-animation-by-rule-and-effect-id",
                "rule-node-by-id",
                "rule-node-edge-by-source-target",
                "context-variable-by-name-scope",
                "editorial-data-block-adapter-registry",
                "editorial-journey-step-block-by-id",
                "editorial-data-block-field-binding",
                "widget-by-stable-key",
                "widget-shell-by-widget-key",
                "composition-link-by-id",
                "page-state-path",
                "widget-child-authoring-manifest-operation",
                "json-logic-condition-by-node",
                "property-effect-by-node").contains(resolver);
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

    private void addTabIndexOrIdMatches(
            List<ResolvedCandidate> candidates,
            JsonNode config,
            String targetValue,
            JsonNode resolutionSpec) {
        JsonNode tabs = resolvePath(config, "tabs");
        boolean allowIndex = isAllowIndexEnabled(resolutionSpec);
        if (allowIndex && tabs.isArray()) {
            Integer index = parseNonNegativeInt(targetValue);
            if (index != null) {
                if (index < tabs.size()) {
                    candidates.add(new ResolvedCandidate("tabs[]/" + index, tabs.get(index)));
                }
                return;
            }
        }
        addArrayMatches(candidates, config, "tabs[]", List.of("id", "label", "textLabel", "title"), targetValue);
        addArrayMatches(candidates, config, "nav.links[]", List.of("id", "label", "textLabel", "title"), targetValue);
    }

    private void addListTemplateSlotMatch(List<ResolvedCandidate> candidates, JsonNode config, String targetValue) {
        if (!listTemplateSlots().contains(targetValue)) {
            return;
        }
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("slot", targetValue);
        JsonNode existing = resolvePath(config, "templating." + targetValue);
        if (!existing.isMissingNode()) {
            value.set("template", existing);
        }
        candidates.add(new ResolvedCandidate("templating." + targetValue, value));
    }

    private void addCronPresetMatches(List<ResolvedCandidate> candidates, JsonNode config, String targetValue) {
        addArrayMatches(candidates, config, "metadata.presets[]", List.of("label", "cron", "expression", "value"), targetValue);
        addArrayMatches(candidates, config, "presets[]", List.of("label", "cron", "expression", "value"), targetValue);
    }

    private void addFieldMetadataPathMatches(List<ResolvedCandidate> candidates, JsonNode config, String targetValue) {
        JsonNode fieldMetadata = resolvePath(config, "fieldMetadata");
        if (fieldMetadata.isObject() && metadataFieldPaths().contains(targetValue)) {
            candidates.add(new ResolvedCandidate("fieldMetadata." + targetValue, fieldMetadata.path(targetValue)));
            return;
        }
        if (fieldMetadata.isArray()) {
            for (int i = 0; i < fieldMetadata.size(); i++) {
                JsonNode item = fieldMetadata.get(i);
                if (matchesAnyKey(item, List.of("name", "label", "id", "path", "jsonPath"), targetValue)) {
                    candidates.add(new ResolvedCandidate("fieldMetadata[]/" + i, item));
                }
            }
            return;
        }
        addRecursiveObjectMatches(candidates, config, List.of("path", "jsonPath", "name", "id"), targetValue);
    }

    private void addMetadataRootMatch(List<ResolvedCandidate> candidates, JsonNode config, String path) {
        JsonNode value = resolvePath(config, path);
        if (!value.isMissingNode()) {
            candidates.add(new ResolvedCandidate(path, value));
            return;
        }
        JsonNode fieldMetadata = resolvePath(config, "fieldMetadata");
        if (!fieldMetadata.isMissingNode()) {
            candidates.add(new ResolvedCandidate("fieldMetadata", fieldMetadata));
        }
    }

    private void addManualFieldMatches(List<ResolvedCandidate> candidates, JsonNode config, String targetValue) {
        addArrayMatches(candidates, config, "currentConfig.fieldMetadata[]", List.of("name", "field", "id", "label"), targetValue);
        if (!candidates.isEmpty()) {
            return;
        }
        addArrayMatches(candidates, config, "currentFieldMetadata[]", List.of("name", "field", "id", "label"), targetValue);
        addArrayMatches(candidates, config, "fieldMetadata[]", List.of("name", "field", "id", "label"), targetValue);
        addArrayMatches(candidates, config, "form.fieldMetadata[]", List.of("name", "field", "id", "label"), targetValue);
    }

    private Set<String> metadataFieldPaths() {
        return Set.of(
                "name",
                "label",
                "description",
                "controlType",
                "placeholder",
                "defaultValue",
                "group",
                "order",
                "required",
                "disabled",
                "readOnly",
                "hidden",
                "source",
                "transient",
                "submitPolicy",
                "hint",
                "helpText",
                "tooltip",
                "options",
                "optionSource",
                "validators",
                "conditionalRequired",
                "conditionalDisplay",
                "visibleIn",
                "ariaLabel",
                "ariaDescribedBy");
    }

    private Set<String> listTemplateSlots() {
        return Set.of(
                "leading",
                "primary",
                "secondary",
                "meta",
                "trailing",
                "identity",
                "balance",
                "limit",
                "risk",
                "alerts",
                "owner",
                "sectionHeader",
                "emptyState");
    }

    private void addRichContentNodeMatches(
            List<ResolvedCandidate> candidates,
            JsonNode config,
            String resolver,
            String targetValue,
            JsonNode resolutionSpec) {
        JsonNode nodes = resolvePath(config, "document.nodes");
        if (!nodes.isArray() || targetValue == null || targetValue.isBlank()) {
            return;
        }
        List<ResolvedCandidate> allNodes = new ArrayList<>();
        collectRichContentNodes(allNodes, nodes, "document.nodes");
        for (ResolvedCandidate candidate : allNodes) {
            if (matchesAnyKey(candidate.value(), List.of("id", "path", "key"), targetValue)
                    || candidate.path().equals(targetValue)) {
                candidates.add(candidate);
            }
        }
        if (candidates.isEmpty() && isAllowIndexEnabled(resolutionSpec) && "rich-block-by-id-or-index".equals(resolver)) {
            Integer index = parseNonNegativeInt(targetValue);
            if (index != null && index < nodes.size()) {
                candidates.add(new ResolvedCandidate("document.nodes[]/" + index, nodes.get(index)));
            }
        }
    }

    private void collectRichContentNodes(
            List<ResolvedCandidate> allNodes,
            JsonNode nodesArray,
            String arrayPath) {
        if (nodesArray == null || !nodesArray.isArray()) {
            return;
        }
        for (int index = 0; index < nodesArray.size(); index++) {
            JsonNode node = nodesArray.get(index);
            if (node == null || !node.isObject()) {
                continue;
            }
            String nodePath = arrayPath + "[]/" + index;
            allNodes.add(new ResolvedCandidate(nodePath, node));
            for (String slot : List.of("content", "header", "body", "footer", "aside", "trailing")) {
                JsonNode slotArray = node.path(slot);
                if (slotArray.isArray()) {
                    collectRichContentNodes(allNodes, slotArray, nodePath + "." + slot);
                }
            }
            JsonNode items = node.path("items");
            if (items.isArray()) {
                String nodeType = firstText(node, "type", "kind");
                if ("compose".equals(nodeType)) {
                    collectRichContentNodes(allNodes, items, nodePath + ".items");
                } else {
                    for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                        JsonNode subItem = items.get(itemIndex);
                        if (subItem != null && subItem.isObject() && subItem.path("content").isArray()) {
                            collectRichContentNodes(
                                    allNodes,
                                    subItem.path("content"),
                                    nodePath + ".items[]/" + itemIndex + ".content");
                        }
                    }
                }
            }
        }
    }

    private void addRichTimelineItemMatches(
            List<ResolvedCandidate> candidates,
            JsonNode config,
            JsonNode target,
            String targetValue,
            JsonNode resolutionSpec) {
        JsonNode nodes = resolvePath(config, "document.nodes");
        if (!nodes.isArray()) {
            return;
        }
        String timelineBlockId = target.isObject()
                ? firstText(target, "timelineBlockId", "blockId", "parentId")
                : "";
        String itemId = target.isObject()
                ? firstText(target, "itemId", "id", "key", "value")
                : targetValue;
        Integer itemIndex = null;
        if (isAllowIndexEnabled(resolutionSpec) && target.isObject()) {
            itemIndex = parseNonNegativeInt(firstText(target, "itemIndex", "index"));
        }
        if (timelineBlockId.isBlank() || (itemId.isBlank() && itemIndex == null)) {
            return;
        }
        List<ResolvedCandidate> allNodes = new ArrayList<>();
        collectRichContentNodes(allNodes, nodes, "document.nodes");
        for (ResolvedCandidate blockCandidate : allNodes) {
            JsonNode block = blockCandidate.value();
            if (!matchesAnyKey(block, List.of("id", "path", "key"), timelineBlockId)
                    && !blockCandidate.path().equals(timelineBlockId)) {
                continue;
            }
            JsonNode items = block.path("items");
            if (!items.isArray()) {
                continue;
            }
            if (itemIndex != null) {
                if (itemIndex < items.size()) {
                    candidates.add(new ResolvedCandidate(
                            blockCandidate.path() + ".items[]/" + itemIndex,
                            items.get(itemIndex)));
                }
                return;
            }
            for (int itemPosition = 0; itemPosition < items.size(); itemPosition++) {
                JsonNode item = items.get(itemPosition);
                if (matchesAnyKey(item, List.of("id", "key"), itemId)) {
                    candidates.add(new ResolvedCandidate(
                            blockCandidate.path() + ".items[]/" + itemPosition,
                            item));
                }
            }
            return;
        }
    }

    private void addTableRuleEffectMatches(
            List<ResolvedCandidate> candidates,
            JsonNode config,
            JsonNode target,
            String targetValue) {
        String ruleId = target.isObject() ? firstText(target, "ruleId", "id", "value") : "";
        String effectId = target.isObject() ? firstText(target, "effectId", "effect", "key") : targetValue;
        if (ruleId.isBlank() || effectId.isBlank()) {
            return;
        }
        JsonNode rules = resolvePath(config, "ruleEffects.rules");
        if (!rules.isArray()) {
            return;
        }
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            JsonNode rule = rules.get(ruleIndex);
            if (!matchesAnyKey(rule, List.of("ruleId", "id"), ruleId)) {
                continue;
            }
            JsonNode effects = rule.path("effects");
            if (!effects.isArray()) {
                return;
            }
            for (int effectIndex = 0; effectIndex < effects.size(); effectIndex++) {
                JsonNode effect = effects.get(effectIndex);
                if (matchesAnyKey(effect, List.of("effectId", "id"), effectId)) {
                    candidates.add(new ResolvedCandidate(
                            "ruleEffects.rules[]/" + ruleIndex + ".effects[]/" + effectIndex,
                            effect));
                }
            }
            return;
        }
    }

    private void addVisualBuilderEdgeMatches(List<ResolvedCandidate> candidates, JsonNode config, JsonNode target) {
        String sourceNodeId = target.isObject() ? firstText(target, "sourceNodeId", "sourceId", "parentId") : "";
        String targetNodeId = target.isObject() ? firstText(target, "targetNodeId", "targetId", "childId", "id") : "";
        if (sourceNodeId.isBlank() || targetNodeId.isBlank()) {
            return;
        }
        JsonNode nodes = resolvePath(config, "nodes");
        if (!nodes.isArray()) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode node = nodes.get(i);
            if (!matchesAnyKey(node, List.of("id", "nodeId"), sourceNodeId)) {
                continue;
            }
            JsonNode children = node.path("children");
            if (!children.isArray()) {
                return;
            }
            for (int childIndex = 0; childIndex < children.size(); childIndex++) {
                if (targetNodeId.equals(children.get(childIndex).asText(""))) {
                    ObjectNode value = JsonNodeFactory.instance.objectNode();
                    value.put("sourceNodeId", sourceNodeId);
                    value.put("targetNodeId", targetNodeId);
                    candidates.add(new ResolvedCandidate("nodes[]/" + i + ".children[]/" + childIndex, value));
                }
            }
            return;
        }
    }

    private void addVisualBuilderVariableMatches(List<ResolvedCandidate> candidates, JsonNode config, JsonNode target) {
        String name = target.isObject() ? firstText(target, "name", "id", "key", "value") : target.asText("");
        String scope = target.isObject() ? firstText(target, "scope") : "";
        if (name.isBlank() || scope.isBlank()) {
            return;
        }
        JsonNode variables = resolvePath(config, "contextVariables");
        if (!variables.isArray()) {
            return;
        }
        for (int i = 0; i < variables.size(); i++) {
            JsonNode variable = variables.get(i);
            if (name.equals(text(variable, "name")) && scope.equals(text(variable, "scope"))) {
                candidates.add(new ResolvedCandidate("contextVariables[]/" + i, variable));
            }
        }
    }

    private void addEditorialAdapterMatches(List<ResolvedCandidate> candidates, JsonNode config, String targetValue) {
        addArrayMatches(candidates, config, "adapterRegistry[]", List.of("adapterId", "id", "dataBlockType"), targetValue);
        addArrayMatches(candidates, config, "dataBlockAdapters[]", List.of("adapterId", "id", "dataBlockType"), targetValue);
        addArrayMatches(candidates, config, "hostConfig.dataBlockAdapters[]", List.of("adapterId", "id", "dataBlockType"), targetValue);
    }

    private void addEditorialBlockMatches(List<ResolvedCandidate> candidates, JsonNode config, JsonNode target, String targetValue) {
        String journeyId = text(target, "journeyId");
        String stepId = text(target, "stepId");
        String blockId = firstNonBlank(text(target, "blockId"), text(target, "id"), targetValue);
        JsonNode journeys = resolvePath(config, "solution.journeys");
        if (!journeys.isArray()) {
            return;
        }
        for (int journeyIndex = 0; journeyIndex < journeys.size(); journeyIndex++) {
            JsonNode journey = journeys.get(journeyIndex);
            if (!journeyId.isBlank() && !matchesAnyKey(journey, List.of("journeyId", "id"), journeyId)) {
                continue;
            }
            JsonNode steps = journey.path("steps");
            if (!steps.isArray()) {
                continue;
            }
            for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                JsonNode step = steps.get(stepIndex);
                if (!stepId.isBlank() && !matchesAnyKey(step, List.of("stepId", "id"), stepId)) {
                    continue;
                }
                JsonNode blocks = step.path("blocks");
                if (!blocks.isArray()) {
                    continue;
                }
                for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                    JsonNode block = blocks.get(blockIndex);
                    if (matchesAnyKey(block, List.of("blockId", "id"), blockId)) {
                        candidates.add(new ResolvedCandidate(
                                "solution.journeys[]/" + journeyIndex + ".steps[]/" + stepIndex + ".blocks[]/" + blockIndex,
                                block));
                    }
                }
            }
        }
    }

    private void addEditorialFieldBindingMatches(List<ResolvedCandidate> candidates, JsonNode config, JsonNode target) {
        String blockId = text(target, "blockId");
        String fieldName = text(target, "fieldName");
        if (blockId.isBlank() || fieldName.isBlank()) {
            return;
        }
        List<ResolvedCandidate> blocks = new ArrayList<>();
        ObjectNode blockTarget = JsonNodeFactory.instance.objectNode();
        blockTarget.put("blockId", blockId);
        addEditorialBlockMatches(blocks, config, blockTarget, blockId);
        for (ResolvedCandidate block : blocks) {
            JsonNode binding = block.value().path("fieldBindings").path(fieldName);
            if (!binding.isMissingNode()) {
                candidates.add(new ResolvedCandidate(block.path() + ".fieldBindings." + fieldName, binding));
            } else {
                candidates.add(new ResolvedCandidate(block.path(), block.value()));
            }
        }
    }

    private void addPageStatePathMatch(List<ResolvedCandidate> candidates, JsonNode config, JsonNode target) {
        String path = target.isObject() ? firstText(target, "path", "id", "value") : target.asText("");
        String layer = target.isObject() ? firstNonBlank(firstText(target, "layer"), "page") : "page";
        JsonNode value = resolvePath(config, "state." + layer + "." + path);
        if (!path.isBlank()) {
            candidates.add(new ResolvedCandidate(
                    "state." + layer + "." + path,
                    value.isMissingNode() ? target.deepCopy() : value));
        }
    }

    private void addPageBuilderChildOperationMatch(List<ResolvedCandidate> candidates, JsonNode config, JsonNode target) {
        String widgetKey = firstText(target, "widgetKey", "key", "id");
        String childComponentId = firstText(target, "childComponentId", "componentId");
        String childOperationId = firstText(target, "childOperationId", "operationId");
        if (widgetKey.isBlank() || childComponentId.isBlank() || childOperationId.isBlank()) {
            return;
        }
        List<ResolvedCandidate> widgets = new ArrayList<>();
        addArrayMatches(widgets, config, "widgets[]", List.of("widgetKey", "key", "id"), widgetKey);
        for (ResolvedCandidate widget : widgets) {
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            value.put("widgetKey", widgetKey);
            value.put("childComponentId", childComponentId);
            value.put("childOperationId", childOperationId);
            value.set("widget", widget.value());
            candidates.add(new ResolvedCandidate(widget.path() + ".definition.inputs", value));
        }
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
            String value = text(item, key);
            if (targetValue.equals(value) || targetValue.equals(normalizeToken(value))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return ascii.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
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
        return targetValue(target, MissingNode.getInstance());
    }

    private String targetValue(JsonNode target, JsonNode resolutionSpec) {
        if (target == null || target.isMissingNode() || target.isNull()) {
            return "";
        }
        if (target.isTextual() || target.isNumber() || target.isBoolean()) {
            return target.asText("");
        }
        if (resolutionSpec != null && resolutionSpec.isObject()) {
            for (String field : stringList(resolutionSpec.path("inputMatchFields"), List.of())) {
                if (target.has(field)) {
                    String candidate = target.path(field).asText("").trim();
                    if (!candidate.isBlank()) {
                        return candidate;
                    }
                }
            }
        }
        for (String field : List.of("id", "blockId", "linkId", "timelineBlockId", "itemId", "field", "name", "key", "value", "slot", "path", "jsonPath", "propertyName", "hintPath", "controlType", "ruleId", "effectId", "presetKey", "tableOperationId", "nodeId", "sourceNodeId", "targetNodeId")) {
            if (target.has(field)) {
                return target.path(field).asText("");
            }
        }
        return "";
    }

    private boolean isAllowIndexEnabled(JsonNode resolutionSpec) {
        return resolutionSpec == null
                || resolutionSpec.isMissingNode()
                || resolutionSpec.isNull()
                || resolutionSpec.path("allowIndex").asBoolean(true);
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

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
