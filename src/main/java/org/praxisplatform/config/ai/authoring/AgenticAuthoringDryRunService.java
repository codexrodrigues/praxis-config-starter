package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AgenticAuthoringDryRunService {

    private static final String PROFILE_CREATE_MINIMAL_FORM = "create-minimal-form";
    private static final String STATUS_PASSED = "passed";
    private static final String STATUS_FAILED = "failed";
    private static final String TARGET_APP_HELPDESK = "praxis-helpdesk-ui";
    private static final String TARGET_COMPONENT_PAGE_BUILDER = "praxis-dynamic-page-builder";
    private static final String WIDGET_DYNAMIC_FORM = "praxis-dynamic-form";
    private static final String REQUEST_SCHEMA_HASH = "ae6e69c0d65cc4d0fd92631a82e4c0a86924f7539ee3645decafb66ca72c99ce";

    private final ObjectMapper objectMapper;

    public AgenticAuthoringDryRunService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgenticAuthoringDryRunResult run(AgenticAuthoringDryRunRequest request) throws IOException {
        JsonNode examplesManifest = readJson(request.examplesGovernanceManifest());
        JsonNode pageCreateCatalog = readJson(request.pageCreateCatalog());
        JsonNode compiledFormPatch = readJson(request.compiledFormPatch());
        JsonNode authoringReplayBundle = readJson(request.authoringReplayBundle());

        List<AgenticAuthoringGateResult> gates = new ArrayList<>();
        gates.add(validateExamplesGovernance(examplesManifest, request.profileId()));
        gates.add(validatePageCreateCatalog(pageCreateCatalog, request.profileId()));
        gates.add(validateCompiledFormPatch(compiledFormPatch, pageCreateCatalog, request.profileId()));
        gates.add(validateAuthoringReplayBundle(authoringReplayBundle, compiledFormPatch, pageCreateCatalog, request.profileId()));

        List<String> failureCodes = gates.stream()
                .filter(gate -> STATUS_FAILED.equals(gate.status()))
                .map(gate -> "GATE_FAILED:" + gate.gateId())
                .toList();
        List<String> warnings = collectTextArray(authoringReplayBundle.path("warnings"));
        boolean valid = failureCodes.isEmpty();
        JsonNode patch = compiledFormPatch.path("patch");
        return new AgenticAuthoringDryRunResult(valid, List.copyOf(gates), failureCodes, warnings, patch);
    }

    public AgenticAuthoringDryRunResult run(AgenticAuthoringArtifactSource artifactSource) throws IOException {
        return run(artifactSource.dryRunRequest());
    }

    private JsonNode readJson(Path path) throws IOException {
        return objectMapper.readTree(path.toFile());
    }

    private AgenticAuthoringGateResult validateExamplesGovernance(JsonNode manifest, String profileId) {
        List<String> failures = new ArrayList<>();
        if (!"1.0.0".equals(text(manifest, "version"))) {
            failures.add("manifest.version must be 1.0.0");
        }
        if (!containsText(manifest.path("targetProfiles"), profileId)) {
            failures.add("targetProfiles must include " + profileId);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode entry : manifest.path("entries")) {
            String id = text(entry, "id");
            if (id.isBlank()) {
                failures.add("entry without id");
                continue;
            }
            if (!ids.add(id)) {
                failures.add("duplicate entry id " + id);
            }
            boolean eligible = entry.path("eligibleForPrompt").asBoolean(false)
                    || entry.path("eligibleForRag").asBoolean(false);
            String classification = text(entry, "classification");
            if (eligible && !("golden-fixture".equals(classification) || "prompt-example".equals(classification))) {
                failures.add("entry " + id + " is eligible with blocked classification " + classification);
            }
            if (eligible && containsText(entry.path("blockedProfiles"), profileId)) {
                failures.add("entry " + id + " is eligible but blocked for " + profileId);
            }
            if (eligible && !containsText(entry.path("allowedProfiles"), profileId)) {
                failures.add("entry " + id + " is eligible but does not allow " + profileId);
            }
            if (entry.path("eligibleForRag").asBoolean(false) && containsText(entry.path("risks"), "tenant-data-risk")) {
                failures.add("entry " + id + " is RAG eligible with tenant-data-risk");
            }
            if (entry.path("eligibleForRag").asBoolean(false) && containsText(entry.path("risks"), "free-json-risk")) {
                failures.add("entry " + id + " is RAG eligible with free-json-risk");
            }
        }
        return gate("examples-governance", failures);
    }

    private AgenticAuthoringGateResult validatePageCreateCatalog(JsonNode catalog, String profileId) {
        List<String> failures = new ArrayList<>();
        if (!"1.0.0".equals(text(catalog, "version"))) {
            failures.add("catalog.version must be 1.0.0");
        }
        if (!profileId.equals(text(catalog, "profileId"))) {
            failures.add("profileId must be " + profileId);
        }
        if (!TARGET_APP_HELPDESK.equals(text(catalog, "targetApp"))) {
            failures.add("targetApp must be " + TARGET_APP_HELPDESK);
        }
        if (!TARGET_COMPONENT_PAGE_BUILDER.equals(text(catalog, "targetComponent"))) {
            failures.add("targetComponent must be " + TARGET_COMPONENT_PAGE_BUILDER);
        }
        if (!"backend-metadata-driven".equals(text(catalog, "runtimeMode"))) {
            failures.add("runtimeMode must be backend-metadata-driven");
        }

        JsonNode widgets = catalog.path("allowedWidgets");
        if (!widgets.isArray() || widgets.size() != 1) {
            failures.add("catalog must expose exactly one widget for " + profileId);
        }
        JsonNode form = findByText(widgets, "id", WIDGET_DYNAMIC_FORM);
        if (form.isMissingNode()) {
            failures.add("catalog must include " + WIDGET_DYNAMIC_FORM);
        } else {
            if (!form.path("eligible").asBoolean(false)) {
                failures.add(WIDGET_DYNAMIC_FORM + " must be eligible");
            }
            requireAll(form.path("requiredInputs"), List.of("mode", "schemaUrl", "submitUrl", "submitMethod", "responseSchemaUrl", "formId", "componentInstanceId"), failures, "requiredInputs");
            requireAll(form.path("blockedInputs"), List.of("config", "resourcePath", "enableCustomization"), failures, "blockedInputs");
        }

        JsonNode constraints = catalog.path("patchConstraints");
        requireAll(constraints.path("blockedPagePaths"), List.of("page.options", "page.state.derived", "widgets[].layout", "connections"), failures, "blockedPagePaths");
        if (constraints.path("maxWidgets").asInt(-1) != 1) {
            failures.add("maxWidgets must be 1");
        }
        if (constraints.path("allowCompositionLinks").asBoolean(true)) {
            failures.add("allowCompositionLinks must be false");
        }
        if (!"post".equals(text(catalog.path("evidence").path("operationRef"), "method"))) {
            failures.add("operationRef.method must be post");
        }
        if (!"/api/helpdesk/chamados".equals(text(catalog.path("evidence").path("operationRef"), "path"))) {
            failures.add("operationRef.path must be /api/helpdesk/chamados");
        }
        return gate("page-create-catalog", failures);
    }

    private AgenticAuthoringGateResult validateCompiledFormPatch(JsonNode compiled, JsonNode catalog, String profileId) {
        List<String> failures = new ArrayList<>();
        if (!profileId.equals(text(compiled, "profileId"))) {
            failures.add("compiled profileId must be " + profileId);
        }
        if (!text(catalog, "catalogReleaseId").equals(text(compiled, "catalogReleaseId"))) {
            failures.add("compiled catalogReleaseId must match catalog");
        }
        if (!"v1.1".equals(text(compiled.path("compatibility"), "aiHttpContract"))) {
            failures.add("compiled patch must target ai contract v1.1");
        }
        if (compiled.path("compatibility").path("requiresV12").asBoolean(false)) {
            failures.add("compiled patch cannot require v1.2");
        }

        JsonNode patch = compiled.path("patch");
        if (!onlyProperty(patch, "page")) {
            failures.add("patch must contain only page");
        }
        JsonNode page = patch.path("page");
        if (page.path("options").isObject()) {
            failures.add("page.options is blocked");
        }
        if (!page.path("state").path("derived").isMissingNode()) {
            failures.add("page.state.derived is blocked");
        }
        JsonNode widgets = page.path("widgets");
        if (!widgets.isArray() || widgets.size() != 1) {
            failures.add("patch.page.widgets must contain exactly one widget");
        }
        JsonNode widget = widgets.isArray() && widgets.size() > 0 ? widgets.get(0) : MissingNode.getInstance();
        if (!widget.path("layout").isMissingNode()) {
            failures.add("widgets[].layout is blocked");
        }
        JsonNode definition = widget.path("definition");
        if (!WIDGET_DYNAMIC_FORM.equals(text(definition, "id"))) {
            failures.add("widget definition must be " + WIDGET_DYNAMIC_FORM);
        }
        JsonNode allowedWidget = findByText(catalog.path("allowedWidgets"), "id", text(definition, "id"));
        JsonNode inputs = definition.path("inputs");
        if (!allowedWidget.isMissingNode()) {
            for (JsonNode requiredInput : allowedWidget.path("requiredInputs")) {
                if (inputs.path(requiredInput.asText()).isMissingNode()) {
                    failures.add("missing required input " + requiredInput.asText());
                }
            }
            Iterator<String> inputNames = inputs.fieldNames();
            while (inputNames.hasNext()) {
                String inputName = inputNames.next();
                if (containsText(allowedWidget.path("blockedInputs"), inputName)) {
                    failures.add("blocked input " + inputName);
                }
                if (!containsText(allowedWidget.path("allowedInputs"), inputName)) {
                    failures.add("input not allowed " + inputName);
                }
            }
        }
        JsonNode evidence = catalog.path("evidence");
        if (!text(evidence.path("schemaRefs"), "request").equals(text(inputs, "schemaUrl"))) {
            failures.add("schemaUrl must match catalog request schema");
        }
        if (!text(evidence.path("schemaRefs"), "response").equals(text(inputs, "responseSchemaUrl"))) {
            failures.add("responseSchemaUrl must match catalog response schema");
        }
        if (!text(evidence.path("operationRef"), "path").equals(text(inputs, "submitUrl"))) {
            failures.add("submitUrl must match catalog operation path");
        }
        if (!"post".equals(text(inputs, "submitMethod"))) {
            failures.add("submitMethod must be post");
        }
        return gate("compiled-form-patch", failures);
    }

    private AgenticAuthoringGateResult validateAuthoringReplayBundle(JsonNode replay, JsonNode compiled, JsonNode catalog, String profileId) {
        List<String> failures = new ArrayList<>();
        if (!profileId.equals(text(replay, "profileId"))) {
            failures.add("replay profileId must be " + profileId);
        }
        if (!TARGET_APP_HELPDESK.equals(text(replay.path("sourceScope"), "targetApp"))) {
            failures.add("replay targetApp must be " + TARGET_APP_HELPDESK);
        }
        if (!text(catalog, "catalogReleaseId").equals(text(replay, "catalogReleaseId"))) {
            failures.add("replay catalogReleaseId must match catalog");
        }
        if (!text(compiled, "catalogReleaseId").equals(text(replay, "catalogReleaseId"))) {
            failures.add("replay catalogReleaseId must match compiled patch");
        }
        if (!REQUEST_SCHEMA_HASH.equals(text(replay, "schemaHash"))) {
            failures.add("replay schemaHash must match recorded proof");
        }
        requireAllFields(replay.path("artifactRefs"), List.of("intentResolution", "apiUseCaseResolution", "fieldSelectionPlan", "pageCreateCatalog", "examplesGovernanceManifest", "compiledFormPatch"), failures, "artifactRefs");
        requireGateStatus(replay, "candidate-eligibility", STATUS_PASSED, failures);
        requireGateStatus(replay, "examples-governance", STATUS_PASSED, failures);
        requireGateStatus(replay, "page-create-catalog", STATUS_PASSED, failures);
        requireGateStatus(replay, "compiled-form-patch", STATUS_PASSED, failures);
        JsonNode roundTrip = findByText(replay.path("gateResults"), "gateId", "round-trip");
        if (roundTrip.isMissingNode()) {
            failures.add("round-trip gate result is required");
        } else if (!STATUS_PASSED.equals(text(roundTrip, "status")) && !containsText(replay.path("warnings"), "round-trip-not-run")) {
            failures.add("round-trip-not-run warning is required until round-trip passes");
        }
        if (!"valid".equals(text(replay.path("finalValidationSummary"), "status"))) {
            failures.add("finalValidationSummary.status must be valid for this proof");
        }
        return gate("authoring-replay-bundle", failures);
    }

    private AgenticAuthoringGateResult gate(String gateId, List<String> failures) {
        return new AgenticAuthoringGateResult(gateId, failures.isEmpty() ? STATUS_PASSED : STATUS_FAILED, List.copyOf(failures));
    }

    private void requireGateStatus(JsonNode replay, String gateId, String status, List<String> failures) {
        JsonNode gate = findByText(replay.path("gateResults"), "gateId", gateId);
        if (gate.isMissingNode()) {
            failures.add("gate " + gateId + " is required");
            return;
        }
        if (!status.equals(text(gate, "status"))) {
            failures.add("gate " + gateId + " must be " + status);
        }
    }

    private void requireAll(JsonNode actual, List<String> expected, List<String> failures, String label) {
        for (String item : expected) {
            if (!containsText(actual, item)) {
                failures.add(label + " must include " + item);
            }
        }
    }

    private void requireAllFields(JsonNode object, List<String> fields, List<String> failures, String label) {
        for (String field : fields) {
            if (text(object, field).isBlank()) {
                failures.add(label + "." + field + " is required");
            }
        }
    }

    private JsonNode findByText(JsonNode array, String field, String value) {
        if (!array.isArray()) {
            return MissingNode.getInstance();
        }
        for (JsonNode item : array) {
            if (value.equals(text(item, field))) {
                return item;
            }
        }
        return MissingNode.getInstance();
    }

    private boolean containsText(JsonNode array, String value) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean onlyProperty(JsonNode object, String property) {
        if (!object.isObject()) {
            return false;
        }
        Iterator<String> fields = object.fieldNames();
        if (!fields.hasNext()) {
            return false;
        }
        String first = fields.next();
        return property.equals(first) && !fields.hasNext();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }

    private List<String> collectTextArray(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            values.add(item.asText());
        }
        return List.copyOf(values);
    }
}
