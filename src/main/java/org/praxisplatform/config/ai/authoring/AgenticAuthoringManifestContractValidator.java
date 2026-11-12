package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AgenticAuthoringManifestContractValidator {

    private static final Set<String> SUPPORTED_RESOLUTION_STRATEGIES = Set.of(
            "root-path",
            "array-match",
            "recursive-array-match",
            "parent-child-array-match");

    private static final Set<String> SUPPORTED_ON_REMOVED_ACTIVE_POLICIES = Set.of(
            "clamp",
            "first",
            "clear");

    public AgenticAuthoringManifestContractValidator() {
    }

    public List<String> validate(JsonNode manifest) {
        List<String> failures = new ArrayList<>();
        if (manifest == null || manifest.isNull() || manifest.isMissingNode()) {
            return failures;
        }
        if (!manifest.isObject()) {
            failures.add("authoringManifest must be an object");
            return failures;
        }
        if (!manifest.path("editableTargets").isArray()) {
            failures.add("editableTargets must be an array");
        }
        if (!manifest.path("operations").isArray()) {
            failures.add("operations must be an array");
        }
        if (!manifest.path("validators").isArray()) {
            failures.add("validators must be an array");
        }
        Set<String> targetKinds = new HashSet<>();
        for (JsonNode target : manifest.path("editableTargets")) {
            String kind = text(target, "kind");
            if (!kind.isBlank()) {
                targetKinds.add(kind);
            }
            validateResolutionSpec(
                    target.path("resolutionSpec"),
                    "editableTargets[" + (kind.isBlank() ? "?" : kind) + "].resolutionSpec",
                    failures);
        }
        Set<String> validatorIds = new HashSet<>();
        for (JsonNode validator : manifest.path("validators")) {
            String validatorId = text(validator, "validatorId");
            if (!validatorId.isBlank()) {
                validatorIds.add(validatorId);
            }
        }
        for (JsonNode operation : manifest.path("operations")) {
            validateOperation(operation, targetKinds, validatorIds, failures);
        }
        validatePresentationAffordances(manifest.path("presentationAffordances"), failures);
        return List.copyOf(failures);
    }

    private void validatePresentationAffordances(JsonNode catalog, List<String> failures) {
        if (catalog == null || catalog.isMissingNode() || catalog.isNull()) {
            return;
        }
        if (!catalog.isObject()) {
            failures.add("presentationAffordances must be an object");
            return;
        }
        requireText(catalog, "presentationAffordances.version", failures);
        requireText(catalog, "presentationAffordances.defaultTargetKind", failures);
        requireText(catalog, "presentationAffordances.sourceRef", failures);
        JsonNode affordances = catalog.path("affordances");
        if (!affordances.isArray() || affordances.isEmpty()) {
            failures.add("presentationAffordances.affordances must be a non-empty array");
            return;
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode affordance : affordances) {
            String id = text(affordance, "id");
            if (id.isBlank()) {
                failures.add("presentationAffordances.affordances[].id is required");
            } else if (!ids.add(id)) {
                failures.add("presentationAffordances duplicate affordance id: " + id);
            }
            requireText(affordance, "presentationAffordances.affordances[" + id + "].targetKind", "targetKind", failures);
            requireText(affordance, "presentationAffordances.affordances[" + id + "].category", "category", failures);
            requireText(affordance, "presentationAffordances.affordances[" + id + "].description", "description", failures);
            validateStringArray(
                    affordance.path("options"),
                    "presentationAffordances.affordances[" + id + "].options",
                    true,
                    failures);
            validateStringArray(
                    affordance.path("appliesToTypes"),
                    "presentationAffordances.affordances[" + id + "].appliesToTypes",
                    false,
                    failures);
            if (!affordance.path("unknownCompatible").isBoolean()) {
                failures.add("presentationAffordances.affordances[" + id + "].unknownCompatible must be boolean");
            }
        }
    }

    private void validateOperation(
            JsonNode operation,
            Set<String> targetKinds,
            Set<String> validatorIds,
            List<String> failures) {
        String operationId = text(operation, "operationId");
        if (operationId.isBlank()) {
            failures.add("operationId is required");
        }
        JsonNode target = operation.path("target");
        String targetKind = text(target, "kind");
        if (targetKind.isBlank() || text(target, "resolver").isBlank()) {
            failures.add("operation target.kind and target.resolver are required: " + operationId);
        } else if (!targetKinds.contains(targetKind)) {
            failures.add("operation target.kind is not declared in editableTargets: " + operationId + " -> " + targetKind);
        }
        validateResolutionSpec(
                target.path("resolutionSpec"),
                "operations[" + (operationId.isBlank() ? "?" : operationId) + "].target.resolutionSpec",
                failures);
        if (!operation.path("preconditions").isArray() || operation.path("preconditions").isEmpty()) {
            failures.add("operation preconditions are required: " + operationId);
        }
        if (!operation.path("validators").isArray() || operation.path("validators").isEmpty()) {
            failures.add("operation validators are required: " + operationId);
        }
        if (!operation.path("effects").isArray() || operation.path("effects").isEmpty()) {
            failures.add("operation effects are required: " + operationId);
        } else {
            int effectIndex = 0;
            for (JsonNode effect : operation.path("effects")) {
                validateEffect(
                        effect,
                        "operations[" + (operationId.isBlank() ? "?" : operationId) + "].effects[" + effectIndex + "]",
                        failures);
                effectIndex++;
            }
        }
        if (!operation.path("affectedPaths").isArray() || operation.path("affectedPaths").isEmpty()) {
            failures.add("operation affectedPaths are required: " + operationId);
        }
        if (text(operation, "submissionImpact").isBlank()) {
            failures.add("operation submissionImpact is required: " + operationId);
        }
        if (operation.path("destructive").asBoolean(false)
                && !operation.path("requiresConfirmation").asBoolean(false)) {
            failures.add("destructive operation requires confirmation: " + operationId);
        }
        for (JsonNode validator : operation.path("validators")) {
            String validatorId = validator.asText("");
            if (!validatorId.isBlank() && !validatorIds.contains(validatorId)) {
                failures.add("operation references unknown validator: " + operationId + " -> " + validatorId);
            }
        }
    }

    private void validateResolutionSpec(JsonNode spec, String path, List<String> failures) {
        if (spec == null || spec.isMissingNode() || spec.isNull()) {
            return;
        }
        if (!spec.isObject()) {
            failures.add(path + " must be an object");
            return;
        }
        String strategy = text(spec, "strategy").trim();
        if (strategy.isBlank()) {
            failures.add(path + ".strategy is required");
        } else if (!SUPPORTED_RESOLUTION_STRATEGIES.contains(strategy)) {
            failures.add(path + ".strategy is unsupported: " + strategy);
        }
        JsonNode paths = spec.path("paths");
        if (!paths.isArray() || paths.isEmpty()) {
            failures.add(path + ".paths must be a non-empty array");
        } else {
            for (JsonNode p : paths) {
                if (!p.isTextual()) {
                    failures.add(path + ".paths must contain only strings");
                    break;
                }
            }
        }
        if ("parent-child-array-match".equals(strategy) && text(spec, "childArrayField").trim().isBlank()) {
            failures.add(path + ".childArrayField is required for parent-child-array-match");
        }
        JsonNode allowIndex = spec.path("allowIndex");
        if (!allowIndex.isMissingNode() && !allowIndex.isNull()) {
            if (!allowIndex.isBoolean() || allowIndex.asBoolean()) {
                failures.add(path + ".allowIndex must be false when specified");
            }
        }
    }

    private void validateEffect(JsonNode effect, String path, List<String> failures) {
        if (effect == null || !effect.isObject()) {
            failures.add(path + " must be an object");
            return;
        }
        String kind = text(effect, "kind").trim();
        if ("insert-positioned".equals(kind)) {
            if (text(effect, "path").trim().isBlank()) {
                failures.add(path + ".path is required for insert-positioned");
            }
            if (text(effect, "key").trim().isBlank()) {
                failures.add(path + ".key is required for insert-positioned");
            }
        }
        JsonNode reconciliation = effect.path("selectionReconciliation");
        if (reconciliation != null && !reconciliation.isMissingNode() && !reconciliation.isNull()) {
            if (!reconciliation.isObject()) {
                failures.add(path + ".selectionReconciliation must be an object");
                return;
            }
            String policy = text(reconciliation, "onRemovedActive").trim();
            if (!policy.isBlank() && !SUPPORTED_ON_REMOVED_ACTIVE_POLICIES.contains(policy)) {
                failures.add(path + ".selectionReconciliation.onRemovedActive is unsupported: " + policy);
            }
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }

    private void requireText(JsonNode node, String path, List<String> failures) {
        String field = path.substring(path.lastIndexOf('.') + 1);
        requireText(node, path, field, failures);
    }

    private void requireText(JsonNode node, String path, String field, List<String> failures) {
        if (text(node, field).isBlank()) {
            failures.add(path + " is required");
        }
    }

    private void validateStringArray(
            JsonNode node,
            String path,
            boolean allowEmpty,
            List<String> failures) {
        if (!node.isArray()) {
            failures.add(path + " must be an array");
            return;
        }
        if (!allowEmpty && node.isEmpty()) {
            failures.add(path + " must be a non-empty array");
            return;
        }
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                failures.add(path + " must contain only non-blank strings");
                return;
            }
        }
    }
}
