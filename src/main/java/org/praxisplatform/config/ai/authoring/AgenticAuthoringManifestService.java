package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.repository.AiRegistryRepository;

public class AgenticAuthoringManifestService {

    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";
    private static final String COMPONENT_DEF_COMPONENT_TYPE = "component-definition";
    private static final String SYSTEM_SCOPE_KEY = "GLOBAL";
    private static final String STATUS_NOT_FOUND = "not-found";

    private final AiRegistryRepository repository;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringTargetResolverRegistry targetResolverRegistry;
    private final AgenticAuthoringValidatorRegistry validatorRegistry;
    private final AgenticAuthoringEffectCompilerRegistry effectCompilerRegistry;
    private final AgenticAuthoringManifestContractValidator manifestContractValidator;
    private final AgenticAuthoringPresentationAffordanceCatalogService presentationAffordanceCatalogService;

    public AgenticAuthoringManifestService(
            AiRegistryRepository repository,
            ObjectMapper objectMapper,
            AgenticAuthoringTargetResolverRegistry targetResolverRegistry,
            AgenticAuthoringValidatorRegistry validatorRegistry,
            AgenticAuthoringEffectCompilerRegistry effectCompilerRegistry,
            AgenticAuthoringManifestContractValidator manifestContractValidator) {
        this(
                repository,
                objectMapper,
                targetResolverRegistry,
                validatorRegistry,
                effectCompilerRegistry,
                manifestContractValidator,
                AgenticAuthoringPresentationAffordanceCatalogService.defaultService(objectMapper));
    }

    public AgenticAuthoringManifestService(
            AiRegistryRepository repository,
            ObjectMapper objectMapper,
            AgenticAuthoringTargetResolverRegistry targetResolverRegistry,
            AgenticAuthoringValidatorRegistry validatorRegistry,
            AgenticAuthoringEffectCompilerRegistry effectCompilerRegistry,
            AgenticAuthoringManifestContractValidator manifestContractValidator,
            AgenticAuthoringPresentationAffordanceCatalogService presentationAffordanceCatalogService) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.targetResolverRegistry = Objects.requireNonNull(targetResolverRegistry, "targetResolverRegistry must not be null");
        this.validatorRegistry = Objects.requireNonNull(validatorRegistry, "validatorRegistry must not be null");
        this.effectCompilerRegistry = Objects.requireNonNull(effectCompilerRegistry, "effectCompilerRegistry must not be null");
        this.manifestContractValidator = Objects.requireNonNull(manifestContractValidator, "manifestContractValidator must not be null");
        this.presentationAffordanceCatalogService = presentationAffordanceCatalogService != null
                ? presentationAffordanceCatalogService
                : AgenticAuthoringPresentationAffordanceCatalogService.defaultService(objectMapper);
    }

    public JsonNode getManifest(String componentId) {
        return findManifest(componentId)
                .orElseThrow(() -> new IllegalArgumentException("authoringManifest not found for component: " + componentId));
    }

    public JsonNode listEditableTargets(String componentId) {
        return getManifest(componentId).path("editableTargets");
    }

    public JsonNode listOperations(String componentId) {
        return getManifest(componentId).path("operations");
    }

    public JsonNode listPresentationAffordances(String componentId) {
        return presentationAffordanceCatalogService.getCatalogSlice(componentId);
    }

    public AgenticAuthoringResolvedTarget resolveTarget(
            String componentId,
            AgenticAuthoringResolveTargetRequest request) {
        JsonNode manifest = getManifest(componentId);
        JsonNode operation = findOperation(manifest, request == null ? null : request.operationId());
        if (operation.isMissingNode()) {
            return unresolved(componentId, request == null ? null : request.operationId(), "", "", "operation not found");
        }
        return targetResolverRegistry.resolve(
                componentId,
                operation,
                request == null ? null : request.target(),
                request == null ? null : request.config());
    }

    public AgenticAuthoringManifestValidationResult validateEditPlan(
            String componentId,
            AgenticAuthoringManifestEditPlanRequest request) {
        JsonNode manifest = getManifest(componentId);
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        failures.addAll(manifestContractValidator.validate(manifest));
        JsonNode baseValidationConfig = validationConfig(request);
        ObjectNode workingValidationConfig = baseValidationConfig != null && baseValidationConfig.isObject()
                ? (ObjectNode) baseValidationConfig.deepCopy()
                : objectMapper.createObjectNode();
        JsonNode requestValidationContext = request == null ? null : request.validationContext();
        ArrayNode normalizedOperations = objectMapper.createArrayNode();
        List<JsonNode> planOperations = operationsFromPlan(request == null ? null : request.plan());
        if (planOperations.isEmpty()) {
            failures.add("plan.operations is required");
        }
        for (JsonNode planOperation : planOperations) {
            String operationId = text(planOperation, "operationId");
            JsonNode operation = findOperation(manifest, operationId);
            if (operation.isMissingNode()) {
                failures.add("operation not found: " + operationId);
                continue;
            }
            int failureCountBefore = failures.size();
            validatePlanOperation(componentId, operation, planOperation, workingValidationConfig, failures, warnings);
            normalizedOperations.add(planOperation);
            if (failures.size() == failureCountBefore) {
                workingValidationConfig = evolveValidationConfigAfterOperation(
                        componentId,
                        operation,
                        planOperation,
                        workingValidationConfig,
                        requestValidationContext);
            }
        }
        ObjectNode normalizedPlan = objectMapper.createObjectNode();
        normalizedPlan.put("componentId", componentId);
        normalizedPlan.set("operations", normalizedOperations);
        return new AgenticAuthoringManifestValidationResult(
                failures.isEmpty(),
                List.copyOf(failures),
                List.copyOf(warnings),
                normalizedPlan);
    }

    private ObjectNode evolveValidationConfigAfterOperation(
            String componentId,
            JsonNode operation,
            JsonNode planOperation,
            ObjectNode currentValidationConfig,
            JsonNode requestValidationContext) {
        ObjectNode nextValidationConfig = currentValidationConfig.deepCopy();
        List<String> evolutionFailures = new ArrayList<>();
        List<String> evolutionWarnings = new ArrayList<>();
        effectCompilerRegistry.appendCompiledEffects(
                componentId,
                operation,
                planOperation,
                nextValidationConfig,
                objectMapper.createArrayNode(),
                evolutionFailures,
                evolutionWarnings);
        if (!evolutionFailures.isEmpty()) {
            return currentValidationConfig;
        }
        if (requestValidationContext != null
                && !requestValidationContext.isNull()
                && !requestValidationContext.isMissingNode()
                && !nextValidationConfig.has("validationContext")) {
            nextValidationConfig.set("validationContext", requestValidationContext);
        }
        return nextValidationConfig;
    }

    private JsonNode validationConfig(AgenticAuthoringManifestEditPlanRequest request) {
        JsonNode config = request == null ? null : request.config();
        JsonNode validationContext = request == null ? null : request.validationContext();
        if (validationContext == null || validationContext.isNull() || validationContext.isMissingNode()) {
            return config;
        }
        ObjectNode merged = validationContext.isObject()
                ? validationContext.deepCopy()
                : objectMapper.createObjectNode();
        mergeObject(merged, config);
        merged.set("validationContext", validationContext);
        return merged;
    }

    private void mergeObject(ObjectNode target, JsonNode source) {
        if (source == null || !source.isObject()) {
            return;
        }
        Iterator<java.util.Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            java.util.Map.Entry<String, JsonNode> field = fields.next();
            JsonNode current = target.path(field.getKey());
            JsonNode incoming = field.getValue();
            if (current instanceof ObjectNode currentObject && incoming != null && incoming.isObject()) {
                mergeObject(currentObject, incoming);
            } else {
                target.set(field.getKey(), incoming);
            }
        }
    }

    public AgenticAuthoringManifestCompileResult compilePatch(
            String componentId,
            AgenticAuthoringManifestEditPlanRequest request) {
        AgenticAuthoringManifestValidationResult validation = validateEditPlan(componentId, request);
        if (!validation.valid()) {
            return new AgenticAuthoringManifestCompileResult(
                    false,
                    validation.failures(),
                    validation.warnings(),
                    objectMapper.createObjectNode());
        }
        JsonNode manifest = getManifest(componentId);
        ArrayNode compiledEffects = objectMapper.createArrayNode();
        List<String> warnings = new ArrayList<>(validation.warnings());
        List<String> failures = new ArrayList<>();
        ObjectNode initialConfig = request != null && request.config() != null && request.config().isObject()
                ? (ObjectNode) request.config()
                : objectMapper.createObjectNode();
        ObjectNode proposedConfig = initialConfig.deepCopy();
        for (JsonNode planOperation : validation.normalizedPlan().path("operations")) {
            JsonNode operation = findOperation(manifest, text(planOperation, "operationId"));
            effectCompilerRegistry.appendCompiledEffects(
                    componentId,
                    operation,
                    planOperation,
                    proposedConfig,
                    compiledEffects,
                    failures,
                    warnings);
        }
        if (failures.isEmpty()) {
            validatorRegistry.validateProposedConfigAgainstClientSurfaceCapabilities(
                    componentId,
                    proposedConfig,
                    validationConfig(request),
                    failures);
        }
        if (!failures.isEmpty()) {
            return new AgenticAuthoringManifestCompileResult(
                    false,
                    List.copyOf(failures),
                    List.copyOf(warnings),
                    objectMapper.createObjectNode());
        }
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("componentId", componentId);
        patch.put("manifestVersion", text(manifest, "manifestVersion"));
        patch.put("patchKind", "component-config-patch");
        ArrayNode compiledOperations = toCompiledOperations(compiledEffects);
        ArrayNode applicablePatchOperations = toApplicablePatchOperations(compiledEffects, initialConfig);
        patch.set("compiledOperations", compiledOperations);
        patch.set("operations", compiledEffects.deepCopy());
        patch.set("patchOperations", applicablePatchOperations);
        patch.set("proposedConfig", proposedConfig);
        return new AgenticAuthoringManifestCompileResult(
                true,
                List.of(),
                List.copyOf(warnings),
                patch);
    }

    private ArrayNode toCompiledOperations(ArrayNode compiledEffects) {
        ArrayNode compiledOperations = objectMapper.createArrayNode();
        for (JsonNode compiledEffect : compiledEffects) {
            compiledOperations.add(toCompiledOperation(compiledEffect));
        }
        return compiledOperations;
    }

    private ArrayNode toApplicablePatchOperations(ArrayNode compiledEffects, ObjectNode initialConfig) {
        ObjectNode baseline = initialConfig != null ? initialConfig.deepCopy() : objectMapper.createObjectNode();
        ArrayNode patchOperations = objectMapper.createArrayNode();
        for (JsonNode compiledEffect : compiledEffects) {
            appendApplicablePatchOperations(compiledEffect, patchOperations, baseline);
        }
        return patchOperations;
    }

    private void appendApplicablePatchOperations(
            JsonNode compiledEffect,
            ArrayNode patchOperations,
            ObjectNode baseline) {
        String effectKind = text(compiledEffect, "effectKind");
        switch (effectKind) {
            case "merge-by-key" -> {
                if (compiledEffect.path("upsertInsert").asBoolean(false)) {
                    String appendPointer = pointerForAppend(compiledEffect);
                    ensureArrayContainerForElementPointer(appendPointer, patchOperations, baseline);
                    patchOperations.add(baseApplicableOperation("add", appendPointer)
                            .set("value", compiledEffect.path("value").deepCopy()));
                    setPointerValueOnBaseline(baseline, appendPointer, compiledEffect.path("value"));
                } else {
                    appendReplaceOperations(
                            pointerFromResolvedPath(text(compiledEffect, "resolvedPath")),
                            compiledEffect.path("value"),
                            patchOperations,
                            baseline);
                }
            }
            case "merge-object" -> appendReplaceOperations(
                    pointerFromResolvedPathOrConfigPath(
                            text(compiledEffect, "resolvedPath"),
                            text(compiledEffect, "path")),
                    compiledEffect.path("value"),
                    patchOperations,
                    baseline);
            case "set-value" -> {
                String targetPointer = pointerFromResolvedPathOrConfigPath(
                        text(compiledEffect, "resolvedPath"),
                        text(compiledEffect, "path"));
                JsonNode removedKeys = compiledEffect.path("removedKeys");
                if (removedKeys.isArray()) {
                    for (JsonNode removedKey : removedKeys) {
                        String key = removedKey.asText("");
                        if (!key.isBlank()) {
                            String removePointer = joinPointer(targetPointer, key);
                            patchOperations.add(baseApplicableOperation("remove", removePointer));
                            removePointerOnBaseline(baseline, removePointer);
                        }
                    }
                }
                ensureParentContainers(targetPointer, patchOperations, baseline);
                String op = (!targetPointer.isEmpty() && baseline.at(targetPointer).isMissingNode())
                        ? "add"
                        : "replace";
                JsonNode setValue = compiledEffect.path("value");
                patchOperations.add(baseApplicableOperation(op, targetPointer)
                        .set("value", setValue.deepCopy()));
                setPointerValueOnBaseline(baseline, targetPointer, setValue);
            }
            case "remove-by-key" -> {
                String removePointer = pointerFromResolvedPathOrRemovedIndex(
                        text(compiledEffect, "resolvedPath"),
                        text(compiledEffect, "path"),
                        compiledEffect.path("removedIndex").asInt(-1));
                patchOperations.add(baseApplicableOperation("remove", removePointer));
                removePointerOnBaseline(baseline, removePointer);
                appendSelectionUpdateOperations(compiledEffect, patchOperations, baseline);
            }
            case "append", "append-to-array", "append-unique" -> {
                String appendPointer = pointerForAppend(compiledEffect);
                ensureArrayContainerForElementPointer(appendPointer, patchOperations, baseline);
                patchOperations.add(baseApplicableOperation("add", appendPointer)
                        .set("value", compiledEffect.path("value").deepCopy()));
                setPointerValueOnBaseline(baseline, appendPointer, compiledEffect.path("value"));
            }
            case "insert-positioned" -> {
                int replacedIdx = compiledEffect.path("replacedIndex").asInt(-1);
                int insertedIdx = compiledEffect.path("insertedIndex").asInt(-1);
                if (replacedIdx >= 0) {
                    String replacePointer = pointerForArrayIndex(text(compiledEffect, "path"), replacedIdx);
                    patchOperations.add(baseApplicableOperation("replace", replacePointer)
                            .set("value", compiledEffect.path("value").deepCopy()));
                    setPointerValueOnBaseline(baseline, replacePointer, compiledEffect.path("value"));
                } else {
                    String insertPointer = insertedIdx >= 0
                            ? pointerForArrayIndex(text(compiledEffect, "path"), insertedIdx)
                            : pointerForAppend(compiledEffect);
                    ensureArrayContainerForElementPointer(insertPointer, patchOperations, baseline);
                    patchOperations.add(baseApplicableOperation("add", insertPointer)
                            .set("value", compiledEffect.path("value").deepCopy()));
                    insertPointerValueOnBaseline(baseline, insertPointer, compiledEffect.path("value"));
                }
            }
            case "reorder-by-key" -> {
                String path = text(compiledEffect, "path");
                String toPointer = pointerForArrayIndex(path, compiledEffect.path("toIndex").asInt(-1));
                String fromPointer = pointerForArrayIndex(path, compiledEffect.path("fromIndex").asInt(-1));
                ObjectNode move = baseApplicableOperation("move", toPointer);
                move.put("from", fromPointer);
                patchOperations.add(move);
                movePointerOnBaseline(baseline, fromPointer, toPointer);
                appendSelectionUpdateOperations(compiledEffect, patchOperations, baseline);
            }
            case "compile-domain-patch" -> patchOperations.add(compiledEffect.deepCopy());
            default -> patchOperations.add(compiledEffect.deepCopy());
        }
    }

    private void appendReplaceOperations(
            String basePointer,
            JsonNode value,
            ArrayNode patchOperations,
            ObjectNode baseline) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            ensureParentContainers(basePointer, patchOperations, baseline);
            String op = (!basePointer.isEmpty() && baseline.at(basePointer).isMissingNode()) ? "add" : "replace";
            patchOperations.add(baseApplicableOperation(op, basePointer).putNull("value"));
            setPointerValueOnBaseline(baseline, basePointer, NullNode.getInstance());
            return;
        }
        if (value.isObject()) {
            if (value.isEmpty()) {
                if (!basePointer.isEmpty()) {
                    ensureParentContainers(basePointer, patchOperations, baseline);
                    String op = baseline.at(basePointer).isMissingNode() ? "add" : "replace";
                    patchOperations.add(baseApplicableOperation(op, basePointer)
                            .set("value", objectMapper.createObjectNode()));
                    setPointerValueOnBaseline(baseline, basePointer, objectMapper.createObjectNode());
                }
                return;
            }
            Iterator<String> fieldNames = value.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                appendReplaceOperations(joinPointer(basePointer, field), value.path(field), patchOperations, baseline);
            }
            return;
        }
        ensureParentContainers(basePointer, patchOperations, baseline);
        String op = (!basePointer.isEmpty() && baseline.at(basePointer).isMissingNode()) ? "add" : "replace";
        patchOperations.add(baseApplicableOperation(op, basePointer).set("value", value.deepCopy()));
        setPointerValueOnBaseline(baseline, basePointer, value);
    }

    private void ensureParentContainers(String pointer, ArrayNode patchOperations, ObjectNode baseline) {
        if (pointer == null || pointer.isEmpty() || !pointer.startsWith("/")) {
            return;
        }
        String[] rawTokens = pointer.substring(1).split("/", -1);
        if (rawTokens.length <= 1) {
            return;
        }
        JsonNode current = baseline;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < rawTokens.length - 1; i++) {
            String segment = unescapePointerToken(rawTokens[i]);
            String nextSegment = unescapePointerToken(rawTokens[i + 1]);
            prefix.append('/').append(rawTokens[i]);
            if (current instanceof ObjectNode currentObject) {
                if (!currentObject.has(segment)) {
                    ObjectNode emptyContainer = objectMapper.createObjectNode();
                    patchOperations.add(baseApplicableOperation("add", prefix.toString())
                            .set("value", emptyContainer.deepCopy()));
                    current = currentObject.putObject(segment);
                } else {
                    JsonNode existing = currentObject.get(segment);
                    if (existing instanceof ObjectNode || (existing instanceof ArrayNode && isArrayIndexToken(nextSegment))) {
                        current = existing;
                    } else {
                        ObjectNode emptyContainer = objectMapper.createObjectNode();
                        patchOperations.add(baseApplicableOperation("replace", prefix.toString())
                                .set("value", emptyContainer.deepCopy()));
                        current = currentObject.putObject(segment);
                    }
                }
            } else if (current instanceof ArrayNode currentArray) {
                try {
                    int idx = Integer.parseInt(segment);
                    if (idx < 0 || idx >= currentArray.size()) {
                        return;
                    }
                    JsonNode existing = currentArray.get(idx);
                    if (existing instanceof ObjectNode || (existing instanceof ArrayNode && isArrayIndexToken(nextSegment))) {
                        current = existing;
                    } else {
                        ObjectNode emptyContainer = objectMapper.createObjectNode();
                        patchOperations.add(baseApplicableOperation("replace", prefix.toString())
                                .set("value", emptyContainer.deepCopy()));
                        ObjectNode created = objectMapper.createObjectNode();
                        currentArray.set(idx, created);
                        current = created;
                    }
                } catch (NumberFormatException ex) {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private void ensureArrayContainerForElementPointer(
            String elementPointer,
            ArrayNode patchOperations,
            ObjectNode baseline) {
        if (elementPointer == null || elementPointer.isEmpty() || !elementPointer.startsWith("/")) {
            return;
        }
        int lastSlash = elementPointer.lastIndexOf('/');
        if (lastSlash <= 0) {
            return;
        }
        String collectionPointer = elementPointer.substring(0, lastSlash);
        ensureParentContainers(collectionPointer, patchOperations, baseline);
        JsonNode existing = baseline.at(collectionPointer);
        if (!existing.isArray()) {
            String op = existing.isMissingNode() ? "add" : "replace";
            patchOperations.add(baseApplicableOperation(op, collectionPointer)
                    .set("value", objectMapper.createArrayNode()));
            setPointerValueOnBaseline(baseline, collectionPointer, objectMapper.createArrayNode());
        }
    }

    private void appendSelectionUpdateOperations(
            JsonNode compiledEffect,
            ArrayNode patchOperations,
            ObjectNode baseline) {
        JsonNode selectionUpdates = compiledEffect.path("selectionUpdates");
        if (!selectionUpdates.isArray()) {
            return;
        }
        for (JsonNode update : selectionUpdates) {
            String dottedPath = text(update, "path");
            if (dottedPath.isBlank()) {
                continue;
            }
            String selPointer = pointerFromConfigPath(dottedPath);
            if (selPointer.isEmpty()) {
                continue;
            }
            if ("remove".equals(text(update, "op"))) {
                if (!baseline.at(selPointer).isMissingNode()) {
                    patchOperations.add(baseApplicableOperation("remove", selPointer));
                    removePointerOnBaseline(baseline, selPointer);
                }
                continue;
            }
            JsonNode value = update.path("value");
            ensureParentContainers(selPointer, patchOperations, baseline);
            String op = baseline.at(selPointer).isMissingNode() ? "add" : "replace";
            patchOperations.add(baseApplicableOperation(op, selPointer)
                    .set("value", value.deepCopy()));
            setPointerValueOnBaseline(baseline, selPointer, value);
        }
    }

    private boolean isArrayIndexToken(String token) {
        if ("-".equals(token)) {
            return true;
        }
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void setPointerValueOnBaseline(ObjectNode baseline, String pointer, JsonNode value) {
        writePointerValueOnBaseline(baseline, pointer, value, false);
    }

    private void insertPointerValueOnBaseline(ObjectNode baseline, String pointer, JsonNode value) {
        writePointerValueOnBaseline(baseline, pointer, value, true);
    }

    private void writePointerValueOnBaseline(
            ObjectNode baseline,
            String pointer,
            JsonNode value,
            boolean insertIntoArray) {
        if (baseline == null || pointer == null) {
            return;
        }
        JsonNode copiedValue = (value == null || value.isMissingNode()) ? NullNode.getInstance() : value.deepCopy();
        if (pointer.isEmpty()) {
            if (copiedValue.isObject()) {
                baseline.removeAll();
                mergeObject(baseline, copiedValue);
            }
            return;
        }
        if (!pointer.startsWith("/")) {
            return;
        }
        String[] rawTokens = pointer.substring(1).split("/", -1);
        JsonNode current = baseline;
        for (int i = 0; i < rawTokens.length - 1; i++) {
            String segment = unescapePointerToken(rawTokens[i]);
            String nextSegment = unescapePointerToken(rawTokens[i + 1]);
            if (current instanceof ObjectNode currentObject) {
                JsonNode existing = currentObject.get(segment);
                if (existing instanceof ObjectNode || (existing instanceof ArrayNode && isArrayIndexToken(nextSegment))) {
                    current = existing;
                } else {
                    current = currentObject.putObject(segment);
                }
            } else if (current instanceof ArrayNode currentArray) {
                try {
                    int idx = Integer.parseInt(segment);
                    if (idx < 0 || idx >= currentArray.size()) {
                        return;
                    }
                    JsonNode existing = currentArray.get(idx);
                    if (existing instanceof ObjectNode || (existing instanceof ArrayNode && isArrayIndexToken(nextSegment))) {
                        current = existing;
                    } else {
                        ObjectNode created = objectMapper.createObjectNode();
                        currentArray.set(idx, created);
                        current = created;
                    }
                } catch (NumberFormatException ex) {
                    return;
                }
            } else {
                return;
            }
        }
        String leaf = unescapePointerToken(rawTokens[rawTokens.length - 1]);
        if (current instanceof ObjectNode parentObject) {
            parentObject.set(leaf, copiedValue);
        } else if (current instanceof ArrayNode parentArray) {
            if ("-".equals(leaf)) {
                parentArray.add(copiedValue);
            } else {
                try {
                    int idx = Integer.parseInt(leaf);
                    if (insertIntoArray) {
                        if (idx >= 0 && idx < parentArray.size()) {
                            parentArray.insert(idx, copiedValue);
                        } else if (idx == parentArray.size()) {
                            parentArray.add(copiedValue);
                        }
                    } else {
                        if (idx >= 0 && idx < parentArray.size()) {
                            parentArray.set(idx, copiedValue);
                        } else if (idx == parentArray.size()) {
                            parentArray.add(copiedValue);
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private void movePointerOnBaseline(ObjectNode baseline, String fromPointer, String toPointer) {
        if (baseline == null || fromPointer == null || toPointer == null
                || !fromPointer.startsWith("/") || !toPointer.startsWith("/")) {
            return;
        }
        JsonNode moved = removeAndReturnPointerOnBaseline(baseline, fromPointer);
        if (moved == null || moved.isMissingNode()) {
            return;
        }
        insertPointerValueOnBaseline(baseline, toPointer, moved);
    }

    private void removePointerOnBaseline(ObjectNode baseline, String pointer) {
        removeAndReturnPointerOnBaseline(baseline, pointer);
    }

    private JsonNode removeAndReturnPointerOnBaseline(ObjectNode baseline, String pointer) {
        if (baseline == null || pointer == null || !pointer.startsWith("/") || pointer.isEmpty()) {
            return MissingNode.getInstance();
        }
        String[] rawTokens = pointer.substring(1).split("/", -1);
        JsonNode current = baseline;
        for (int i = 0; i < rawTokens.length - 1; i++) {
            String segment = unescapePointerToken(rawTokens[i]);
            if (current instanceof ObjectNode currentObject) {
                current = currentObject.get(segment);
            } else if (current instanceof ArrayNode currentArray) {
                try {
                    int idx = Integer.parseInt(segment);
                    current = (idx >= 0 && idx < currentArray.size()) ? currentArray.get(idx) : null;
                } catch (NumberFormatException ex) {
                    return MissingNode.getInstance();
                }
            } else {
                return MissingNode.getInstance();
            }
        }
        String leaf = unescapePointerToken(rawTokens[rawTokens.length - 1]);
        if (current instanceof ObjectNode parentObject) {
            JsonNode removed = parentObject.remove(leaf);
            return removed != null ? removed : MissingNode.getInstance();
        } else if (current instanceof ArrayNode parentArray) {
            try {
                int idx = Integer.parseInt(leaf);
                if (idx >= 0 && idx < parentArray.size()) {
                    return parentArray.remove(idx);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return MissingNode.getInstance();
    }

    private String unescapePointerToken(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }

    private ObjectNode baseApplicableOperation(String op, String path) {
        ObjectNode operation = objectMapper.createObjectNode();
        operation.put("op", op);
        operation.put("path", path);
        return operation;
    }

    private String pointerFromResolvedPathOrConfigPath(String resolvedPath, String configPath) {
        return !resolvedPath.isBlank() ? pointerFromResolvedPath(resolvedPath) : pointerFromConfigPath(configPath);
    }

    private String pointerFromResolvedPathOrRemovedIndex(String resolvedPath, String configPath, int removedIndex) {
        if (!resolvedPath.isBlank()) {
            return pointerFromResolvedPath(resolvedPath);
        }
        if (removedIndex >= 0) {
            return pointerForArrayIndex(configPath, removedIndex);
        }
        return pointerFromConfigPath(configPath);
    }

    String pointerForAppend(JsonNode compiledEffect) {
        String path = text(compiledEffect, "path");
        String resolvedPath = text(compiledEffect, "resolvedPath");
        String collectionPointer;
        if (!resolvedPath.isBlank() && path.contains("[].")) {
            String tail = tailAfterLastArray(path);
            String tailCollection = collectionPath(tail);
            collectionPointer = tailCollection.isBlank()
                    ? pointerFromResolvedPath(resolvedPath)
                    : joinPointer(pointerFromResolvedPath(resolvedPath), pointerTokens(tailCollection));
        } else {
            collectionPointer = pointerFromConfigPath(collectionPath(path));
        }
        return collectionPointer.endsWith("/")
                ? collectionPointer + "-"
                : collectionPointer + "/-";
    }

    private String pointerForArrayIndex(String configPath, int index) {
        String collection = collectionPath(configPath);
        return joinPointer(pointerFromConfigPath(collection), String.valueOf(index));
    }

    private String pointerFromResolvedPath(String resolvedPath) {
        if (resolvedPath == null || resolvedPath.isBlank() || "$".equals(resolvedPath.trim())) {
            return "";
        }
        String clean = resolvedPath.trim();
        if (clean.startsWith("/")) {
            return joinPointer("", pointerTokens(clean));
        }
        if (clean.startsWith("$.")) {
            clean = clean.substring(2);
        }
        String normalized = clean.replace("[]/", "\u0000").replace("[]", "");
        return joinPointer("", pointerTokens(normalized));
    }

    String pointerFromConfigPath(String configPath) {
        if (configPath != null && configPath.trim().startsWith("/")) {
            return joinPointer("", pointerTokens(configPath.trim()));
        }
        String normalized = normalizeConfigPath(configPath);
        return joinPointer("", pointerTokens(normalized));
    }

    private String normalizeConfigPath(String configPath) {
        if (configPath == null || configPath.isBlank()) {
            return "";
        }
        String clean = configPath.trim().replace("[]/", "\u0000").replace("[]", "");
        if ("$".equals(clean)) {
            return "";
        }
        if (clean.startsWith("$.")) {
            clean = clean.substring(2);
        }
        return clean;
    }

    private String[] pointerTokens(String path) {
        if (path == null || path.isBlank()) {
            return new String[0];
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("/")) {
            // RFC 6901 defines "" as the root document pointer; accept "/" as a root alias
            // so pointer resolution yields zero tokens instead of a spurious empty segment.
            if ("/".equals(trimmed)) {
                return new String[0];
            }
            String[] rawTokens = trimmed.substring(1).split("/", -1);
            String[] decoded = new String[rawTokens.length];
            for (int i = 0; i < rawTokens.length; i++) {
                decoded[i] = unescapePointerToken(rawTokens[i]);
            }
            return decoded;
        }
        String normalized = normalizeConfigPath(trimmed);
        if (normalized.isBlank()) {
            return new String[0];
        }
        return normalized.split("[.\\u0000]");
    }

    private String joinPointer(String basePointer, String... tokens) {
        String pointer = basePointer == null ? "" : basePointer.trim();
        if ("/".equals(pointer)) {
            pointer = "";
        } else if (pointer.endsWith("/") && pointer.length() > 1) {
            pointer = pointer.substring(0, pointer.length() - 1);
        }
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            pointer = pointer + "/" + escapePointerToken(token);
        }
        return pointer;
    }

    private String escapePointerToken(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private String collectionPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int marker = path.lastIndexOf("[]");
        if (marker < 0) {
            return path;
        }
        return path.substring(0, marker);
    }

    private String tailAfterLastArray(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int marker = path.lastIndexOf("[].");
        if (marker < 0) {
            return "";
        }
        return path.substring(marker + 3);
    }

    private ObjectNode toCompiledOperation(JsonNode compiledEffect) {
        ObjectNode compiledOperation = objectMapper.createObjectNode();
        String effectKind = text(compiledEffect, "effectKind");
        compiledOperation.put("op", normalizeCompiledOperation(effectKind));
        copyIfPresent(compiledEffect, compiledOperation, "componentId");
        copyIfPresent(compiledEffect, compiledOperation, "operationId");
        copyIfPresent(compiledEffect, compiledOperation, "path");
        copyIfPresent(compiledEffect, compiledOperation, "resolvedPath");
        copyIfPresent(compiledEffect, compiledOperation, "key");
        copyIfPresent(compiledEffect, compiledOperation, "keyValue");
        copyIfPresent(compiledEffect, compiledOperation, "value");
        copyIfPresent(compiledEffect, compiledOperation, "removedValue");
        copyIfPresent(compiledEffect, compiledOperation, "removedIndex");
        copyIfPresent(compiledEffect, compiledOperation, "fromIndex");
        copyIfPresent(compiledEffect, compiledOperation, "toIndex");
        copyIfPresent(compiledEffect, compiledOperation, "appendedIndex");
        copyIfPresent(compiledEffect, compiledOperation, "insertedIndex");
        copyIfPresent(compiledEffect, compiledOperation, "replacedIndex");
        copyIfPresent(compiledEffect, compiledOperation, "handler");
        copyIfPresent(compiledEffect, compiledOperation, "submissionImpact");
        if ("compile-domain-patch".equals(effectKind)) {
            compiledOperation.put("compilerBoundary", true);
        }
        return compiledOperation;
    }

    private String normalizeCompiledOperation(String effectKind) {
        return switch (effectKind) {
            case "merge-by-key" -> "merge-object-by-key";
            case "merge-object" -> "merge-object";
            case "set-value" -> "set-value";
            case "remove-by-key" -> "remove-by-key";
            case "append", "append-to-array" -> "append";
            case "append-unique" -> "append-unique";
            case "insert-positioned" -> "insert-positioned";
            case "reorder-by-key" -> "reorder-by-key";
            case "compile-domain-patch" -> "domain-patch";
            default -> effectKind;
        };
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.path(field);
        if (!value.isMissingNode() && !value.isNull()) {
            target.set(field, value.deepCopy());
        }
    }

    private Optional<JsonNode> findManifest(String componentId) {
        if (componentId == null || componentId.isBlank()) {
            return Optional.empty();
        }
        String normalizedComponentId = componentId.trim();
        Optional<JsonNode> direct = findRepositoryManifest(normalizedComponentId);
        if (direct.isPresent()) {
            return direct;
        }
        if ("praxis-dynamic-page-builder".equals(normalizedComponentId)) {
            return findRepositoryManifest("praxis-page-builder");
        }
        return Optional.empty();
    }

    private Optional<JsonNode> findRepositoryManifest(String componentId) {
        return repository.findAuthoringManifestPayload(
                        REGISTRY_TYPE_COMPONENT_DEF,
                        componentId,
                        COMPONENT_DEF_COMPONENT_TYPE,
                        Scope.SYSTEM.name(),
                        SYSTEM_SCOPE_KEY)
                .map(this::readPayload)
                .filter(manifest -> manifest != null && manifest.isObject() && !manifest.isMissingNode());
    }

    private JsonNode readPayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("ai_registry payload is not valid JSON", ex);
        }
    }

    private void validatePlanOperation(
            String componentId,
            JsonNode operation,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures,
            List<String> warnings) {
        String operationId = text(operation, "operationId");
        if (operation.path("destructive").asBoolean(false)
                && !planOperation.path("confirmed").asBoolean(false)) {
            failures.add("operation requires explicit confirmation: " + operationId);
        }
        if (operation.path("target").path("required").asBoolean(false)) {
            AgenticAuthoringResolvedTarget resolved = targetResolverRegistry.resolve(
                    componentId,
                    operation,
                    planOperation.path("target"),
                    config);
            if (!AgenticAuthoringTargetResolverRegistry.STATUS_RESOLVED.equals(resolved.status())) {
                failures.add("target resolution failed for " + operationId + ": " + String.join(", ", resolved.failures()));
            }
        }
        validatorRegistry.validateInputSchema(operation, planOperation.path("input"), failures);
        validatorRegistry.executeOperationValidators(componentId, operation, planOperation, config, failures, warnings);
        for (JsonNode effect : operation.path("effects")) {
            if ("compile-domain-patch".equals(text(effect, "kind"))
                    && !effectCompilerRegistry.supportsDomainPatchHandler(text(effect, "handler"))) {
                warnings.add("operation requires domain compiler: " + operationId);
            }
        }
    }

    private List<JsonNode> operationsFromPlan(JsonNode plan) {
        if (plan == null || plan.isNull() || plan.isMissingNode()) {
            return List.of();
        }
        if (plan.path("operations").isArray()) {
            List<JsonNode> operations = new ArrayList<>();
            plan.path("operations").forEach(operations::add);
            return operations;
        }
        if (plan.has("operationId")) {
            return List.of(plan);
        }
        return List.of();
    }

    private JsonNode findOperation(JsonNode manifest, String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return MissingNode.getInstance();
        }
        for (JsonNode operation : manifest.path("operations")) {
            if (operationId.equals(text(operation, "operationId"))) {
                return operation;
            }
        }
        return MissingNode.getInstance();
    }

    private AgenticAuthoringResolvedTarget unresolved(
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

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }
}
