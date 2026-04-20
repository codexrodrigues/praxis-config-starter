package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AgenticAuthoringManifestContractValidator {

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
        return List.copyOf(failures);
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
        if (!operation.path("preconditions").isArray() || operation.path("preconditions").isEmpty()) {
            failures.add("operation preconditions are required: " + operationId);
        }
        if (!operation.path("validators").isArray() || operation.path("validators").isEmpty()) {
            failures.add("operation validators are required: " + operationId);
        }
        if (!operation.path("effects").isArray() || operation.path("effects").isEmpty()) {
            failures.add("operation effects are required: " + operationId);
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

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }
}
