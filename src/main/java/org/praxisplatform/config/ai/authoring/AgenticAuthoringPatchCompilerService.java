package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AgenticAuthoringPatchCompilerService {

    private static final String BUILDER_VERSION = "compiled-form-patch-builder@0.1.0-draft";
    private static final String WIDGET_DYNAMIC_FORM = "praxis-dynamic-form";

    private final AgenticAuthoringArtifactProperties properties;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringMinimalFormPlanValidator planValidator;

    public AgenticAuthoringPatchCompilerService(
            AgenticAuthoringArtifactProperties properties,
            ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.planValidator = new AgenticAuthoringMinimalFormPlanValidator();
    }

    public AgenticAuthoringCompileResult compile(AgenticAuthoringCompileRequest request) throws IOException {
        if (request == null || request.minimalFormPlan() == null || request.minimalFormPlan().isMissingNode()) {
            throw new IllegalArgumentException("minimalFormPlan is required.");
        }
        JsonNode plan = request.minimalFormPlan();
        AgenticAuthoringIntentResolutionResult intentResolution = request.intentResolution();
        List<String> failures = new ArrayList<>(planValidator.validate(plan, intentResolution));
        JsonNode catalog = readPageCreateCatalog();
        failures.addAll(validateCatalog(catalog));
        JsonNode compiled = failures.isEmpty() ? buildCompiledFormPatch(plan, catalog, intentResolution) : objectMapper.createObjectNode();
        List<String> warnings = new ArrayList<>(List.of("round-trip-not-run", "compiled-from-minimal-form-plan"));
        if (intentResolution != null) {
            warnings.add("compiled-from-intent-resolution");
        }
        return new AgenticAuthoringCompileResult(
                failures.isEmpty(),
                List.copyOf(failures),
                List.copyOf(warnings),
                compiled
        );
    }

    private JsonNode readPageCreateCatalog() throws IOException {
        Path artifactsDir = properties.getArtifactsDir();
        if (artifactsDir == null) {
            throw new IllegalStateException("praxis.ai.authoring.artifacts-dir must be configured before compiling a form patch.");
        }
        Path root = artifactsDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("praxis.ai.authoring.artifacts-dir does not exist or is not a directory: " + root);
        }
        String fileName = properties.getPageCreateCatalog();
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalStateException("praxis.ai.authoring.pageCreateCatalog must not be blank.");
        }
        Path catalog = root.resolve(fileName).toAbsolutePath().normalize();
        if (!catalog.startsWith(root)) {
            throw new IllegalStateException("praxis.ai.authoring.pageCreateCatalog must resolve inside artifacts-dir.");
        }
        if (!Files.isRegularFile(catalog)) {
            throw new IllegalStateException("Page create catalog not found: " + catalog);
        }
        return objectMapper.readTree(catalog.toFile());
    }

    private List<String> validateCatalog(JsonNode catalog) {
        List<String> failures = new ArrayList<>();
        if (!"create-minimal-form".equals(text(catalog, "profileId"))) {
            failures.add("catalog profileId must be create-minimal-form");
        }
        if (!AgenticAuthoringMinimalFormPlanValidator.TARGET_COMPONENT_PAGE_BUILDER.equals(text(catalog, "targetComponent"))) {
            failures.add("catalog targetComponent must be praxis-dynamic-page-builder");
        }
        JsonNode form = findWidget(catalog.path("allowedWidgets"), WIDGET_DYNAMIC_FORM);
        if (form.isMissingNode() || !form.path("eligible").asBoolean(false)) {
            failures.add("catalog must expose eligible praxis-dynamic-form widget");
        }
        if (!"post".equals(text(catalog.path("evidence").path("operationRef"), "method"))) {
            failures.add("catalog operation method must be post");
        }
        if (text(catalog.path("evidence").path("schemaRefs"), "request").isBlank()) {
            failures.add("catalog request schema ref is required");
        }
        if (text(catalog.path("evidence").path("schemaRefs"), "response").isBlank()) {
            failures.add("catalog response schema ref is required");
        }
        return List.copyOf(failures);
    }

    private JsonNode buildCompiledFormPatch(
            JsonNode plan,
            JsonNode catalog,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        AgenticAuthoringCandidate candidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        String submitUrl = candidate == null ? text(catalog.path("evidence").path("operationRef"), "path") : candidate.submitUrl();
        String submitMethod = candidate == null ? text(catalog.path("evidence").path("operationRef"), "method") : candidate.submitMethod();
        if (submitMethod == null || submitMethod.isBlank()) {
            submitMethod = candidate == null ? "post" : candidate.operation();
        }
        String schemaUrl = candidate == null ? text(catalog.path("evidence").path("schemaRefs"), "request") : candidate.schemaUrl();
        String responseSchemaUrl = responseSchemaUrl(schemaUrl);
        String targetApp = text(plan, "targetApp");
        String targetComponentId = text(plan, "targetComponentId");
        String resourceSlug = slug(submitUrl);
        String widgetKey = resourceSlug + "-form";
        String formId = resourceSlug + "-minimal";
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", "1.0.0");
        root.put("profileId", "create-minimal-form");
        root.put("targetComponentId", targetComponentId.isBlank() ? text(catalog, "targetComponent") : targetComponentId);
        root.put("catalogReleaseId", catalogReleaseId(catalog, targetApp, intentResolution));
        ArrayNode sourceRefs = root.putArray("sourceRefs");
        for (JsonNode sourceRef : plan.path("sourceRefs")) {
            sourceRefs.add(sourceRef.asText());
        }
        ObjectNode patch = root.putObject("patch");
        ObjectNode page = patch.putObject("page");
        ObjectNode canvas = page.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "80px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "content");
        ObjectNode items = canvas.putObject("items");
        ObjectNode item = items.putObject(widgetKey);
        item.put("col", 1);
        item.put("row", 1);
        item.put("colSpan", 12);
        item.put("rowSpan", 4);

        ArrayNode widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", widgetKey);
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", WIDGET_DYNAMIC_FORM);
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("mode", "create");
        inputs.put("schemaUrl", schemaUrl);
        inputs.put("submitUrl", submitUrl);
        inputs.put("submitMethod", submitMethod.toLowerCase());
        inputs.put("responseSchemaUrl", responseSchemaUrl);
        inputs.put("formId", formId);
        inputs.put("componentInstanceId", formId);
        ObjectNode composition = page.putObject("composition");
        composition.putArray("links");

        ObjectNode compatibility = root.putObject("compatibility");
        compatibility.put("aiHttpContract", "v1.1");
        compatibility.put("publicResponseKind", "patch");
        compatibility.put("requiresV12", false);
        root.put("builderVersion", BUILDER_VERSION);
        ArrayNode warnings = root.putArray("warnings");
        warnings.add("round-trip-not-run");
        warnings.add("compiled-from-minimal-form-plan");
        if (intentResolution != null) {
            warnings.add("compiled-from-intent-resolution");
        }
        return root;
    }

    private String responseSchemaUrl(String requestSchemaUrl) {
        if (requestSchemaUrl == null || requestSchemaUrl.isBlank()) {
            return "";
        }
        if (requestSchemaUrl.contains("schemaType=request")) {
            return requestSchemaUrl.replace("schemaType=request", "schemaType=response");
        }
        return requestSchemaUrl + (requestSchemaUrl.contains("?") ? "&" : "?") + "schemaType=response";
    }

    private String catalogReleaseId(
            JsonNode catalog,
            String targetApp,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return text(catalog, "catalogReleaseId");
        }
        String app = targetApp == null || targetApp.isBlank() ? intentResolution.targetApp() : targetApp;
        return slug(app) + ".create-minimal-form.intent-resolution.v0.1.0";
    }

    private String slug(String value) {
        String source = value == null || value.isBlank() ? "praxis-generated" : value.toLowerCase();
        String slug = source.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "praxis-generated" : slug;
    }

    private JsonNode findWidget(JsonNode widgets, String id) {
        if (!widgets.isArray()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        for (JsonNode widget : widgets) {
            if (id.equals(text(widget, "id"))) {
                return widget;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }
}
