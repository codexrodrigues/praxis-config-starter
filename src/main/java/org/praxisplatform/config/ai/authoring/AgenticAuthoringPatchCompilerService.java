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
        if (isModifyAddField(intentResolution)) {
            failures.addAll(validateModifyAddFieldRequest(request));
        }
        JsonNode compiled = failures.isEmpty()
                ? buildCompiledFormPatch(plan, catalog, request.currentPage(), intentResolution)
                : objectMapper.createObjectNode();
        List<String> warnings = new ArrayList<>(List.of("round-trip-not-run", "compiled-from-minimal-form-plan"));
        if (intentResolution != null) {
            warnings.add("compiled-from-intent-resolution");
        }
        if (isModifyAddField(intentResolution)) {
            warnings.add("compiled-as-current-page-modification");
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
            JsonNode currentPage,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (isModifyAddField(intentResolution)) {
            return buildModifyAddFieldPatch(plan, catalog, currentPage, intentResolution);
        }
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

    private JsonNode buildModifyAddFieldPatch(
            JsonNode plan,
            JsonNode catalog,
            JsonNode currentPage,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        ObjectNode root = buildPatchEnvelope(plan, catalog, intentResolution, "modify-existing-form");
        ObjectNode patch = root.putObject("patch");
        ObjectNode page = currentPage.deepCopy();
        ObjectNode widget = resolveTargetWidget(page, intentResolution);
        ObjectNode inputs = object(widget.with("definition")).with("inputs");
        ObjectNode config = object(inputs.with("config"));
        ArrayNode fieldMetadata = array(config.withArray("fieldMetadata"));
        ArrayNode sections = array(config.withArray("sections"));
        ObjectNode section = ensureLocalSection(sections);
        ObjectNode row = ensureLocalRow(section);
        ArrayNode columns = array(row.withArray("columns"));
        ObjectNode column = ensureLocalColumn(columns);
        ArrayNode columnFields = array(column.withArray("fields"));

        for (JsonNode field : plan.path("fields")) {
            String name = text(field, "name");
            if (name.isBlank() || containsField(fieldMetadata, name)) {
                continue;
            }
            ObjectNode localField = fieldMetadata.addObject();
            localField.put("name", name);
            localField.put("label", text(field, "label"));
            localField.put("controlType", normalizeControlType(text(field, "controlType")));
            localField.put("required", field.path("required").asBoolean(false));
            localField.put("source", "local");
            localField.put("transient", true);
            localField.put("submitPolicy", "omit");
            if (field.has("defaultValue")) {
                localField.set("defaultValue", field.path("defaultValue"));
            }
            if (!containsText(columnFields, name)) {
                columnFields.add(name);
            }
        }

        patch.set("page", page);
        ArrayNode warnings = array(root.withArray("warnings"));
        warnings.add("compiled-as-current-page-modification");
        warnings.add("local-fields-omit-submit-by-default");
        return root;
    }

    private ObjectNode buildPatchEnvelope(
            JsonNode plan,
            JsonNode catalog,
            AgenticAuthoringIntentResolutionResult intentResolution,
            String profileId) {
        String targetApp = text(plan, "targetApp");
        String targetComponentId = text(plan, "targetComponentId");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", "1.0.0");
        root.put("profileId", profileId);
        root.put("targetComponentId", targetComponentId.isBlank() ? text(catalog, "targetComponent") : targetComponentId);
        root.put("catalogReleaseId", catalogReleaseId(catalog, targetApp, intentResolution));
        ArrayNode sourceRefs = root.putArray("sourceRefs");
        for (JsonNode sourceRef : plan.path("sourceRefs")) {
            sourceRefs.add(sourceRef.asText());
        }
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

    private List<String> validateModifyAddFieldRequest(AgenticAuthoringCompileRequest request) {
        List<String> failures = new ArrayList<>();
        JsonNode currentPage = request.currentPage();
        if (currentPage == null || !currentPage.isObject()) {
            failures.add("currentPage is required for modify add_field");
            return failures;
        }
        ObjectNode page = currentPage.deepCopy();
        ObjectNode widget = resolveTargetWidget(page, request.intentResolution());
        if (widget == null) {
            failures.add("target praxis-dynamic-form widget is required for modify add_field");
        }
        return failures;
    }

    private boolean isModifyAddField(AgenticAuthoringIntentResolutionResult intentResolution) {
        return intentResolution != null
                && "modify".equals(intentResolution.operationKind())
                && "form".equals(intentResolution.artifactKind())
                && "add_field".equals(intentResolution.changeKind());
    }

    private ObjectNode resolveTargetWidget(ObjectNode page, AgenticAuthoringIntentResolutionResult intentResolution) {
        String targetWidgetKey = intentResolution == null || intentResolution.target() == null
                ? ""
                : intentResolution.target().widgetKey();
        JsonNode widgets = page.path("widgets");
        if (!widgets.isArray()) {
            return null;
        }
        ObjectNode firstDynamicForm = null;
        for (JsonNode widget : widgets) {
            if (!widget.isObject()) {
                continue;
            }
            ObjectNode object = (ObjectNode) widget;
            if (targetWidgetKey != null && !targetWidgetKey.isBlank() && targetWidgetKey.equals(text(object, "key"))) {
                return object;
            }
            if (WIDGET_DYNAMIC_FORM.equals(text(object.path("definition"), "id")) && firstDynamicForm == null) {
                firstDynamicForm = object;
            }
        }
        return firstDynamicForm;
    }

    private ObjectNode ensureLocalSection(ArrayNode sections) {
        for (JsonNode section : sections) {
            if (section.isObject() && "agentic-local-fields".equals(text(section, "id"))) {
                return (ObjectNode) section;
            }
        }
        ObjectNode section = sections.addObject();
        section.put("id", "agentic-local-fields");
        section.put("title", "Campos adicionais");
        section.putArray("rows");
        return section;
    }

    private ObjectNode ensureLocalRow(ObjectNode section) {
        ArrayNode rows = array(section.withArray("rows"));
        if (!rows.isEmpty() && rows.get(0).isObject()) {
            return (ObjectNode) rows.get(0);
        }
        ObjectNode row = rows.addObject();
        row.put("id", "agentic-local-fields-row");
        row.putArray("columns");
        return row;
    }

    private ObjectNode ensureLocalColumn(ArrayNode columns) {
        if (!columns.isEmpty() && columns.get(0).isObject()) {
            return (ObjectNode) columns.get(0);
        }
        ObjectNode column = columns.addObject();
        column.put("id", "agentic-local-fields-column");
        column.put("span", 12);
        column.putArray("fields");
        return column;
    }

    private boolean containsField(ArrayNode fieldMetadata, String name) {
        for (JsonNode field : fieldMetadata) {
            if (name.equals(text(field, "name"))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsText(ArrayNode values, String value) {
        for (JsonNode item : values) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private String normalizeControlType(String controlType) {
        return controlType == null || controlType.isBlank() ? "text" : controlType;
    }

    private ObjectNode object(JsonNode node) {
        return (ObjectNode) node;
    }

    private ArrayNode array(JsonNode node) {
        return (ArrayNode) node;
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
