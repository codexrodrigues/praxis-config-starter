package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
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

    public AgenticAuthoringManifestService(
            AiRegistryRepository repository,
            ObjectMapper objectMapper,
            AgenticAuthoringTargetResolverRegistry targetResolverRegistry,
            AgenticAuthoringValidatorRegistry validatorRegistry,
            AgenticAuthoringEffectCompilerRegistry effectCompilerRegistry,
            AgenticAuthoringManifestContractValidator manifestContractValidator) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.targetResolverRegistry = Objects.requireNonNull(targetResolverRegistry, "targetResolverRegistry must not be null");
        this.validatorRegistry = Objects.requireNonNull(validatorRegistry, "validatorRegistry must not be null");
        this.effectCompilerRegistry = Objects.requireNonNull(effectCompilerRegistry, "effectCompilerRegistry must not be null");
        this.manifestContractValidator = Objects.requireNonNull(manifestContractValidator, "manifestContractValidator must not be null");
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
            validatePlanOperation(componentId, operation, planOperation, request == null ? null : request.config(), failures, warnings);
            normalizedOperations.add(planOperation);
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
        ObjectNode proposedConfig = request != null && request.config() != null && request.config().isObject()
                ? request.config().deepCopy()
                : objectMapper.createObjectNode();
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
        patch.set("compiledOperations", compiledOperations);
        patch.set("operations", compiledEffects.deepCopy());
        patch.set("patchOperations", compiledEffects.deepCopy());
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
        return repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                        REGISTRY_TYPE_COMPONENT_DEF,
                        componentId,
                        COMPONENT_DEF_COMPONENT_TYPE,
                        Scope.SYSTEM,
                        SYSTEM_SCOPE_KEY)
                .map(registry -> readPayload(registry.getPayload()))
                .map(payload -> payload.path("componentDefinition").path("jsonSchema").path("authoringManifest"))
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
