package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.SchemaFetchResult;
import org.praxisplatform.config.service.SchemaRetrievalService;

public class AgenticAuthoringPreviewService {

    private static final Set<String> SEMANTIC_AXIS_STOP_WORDS = Set.of(
            "por",
            "para",
            "com",
            "dos",
            "das",
            "the",
            "and",
            "total",
            "registros",
            "registro");

    private final AgenticAuthoringPlanService planService;
    private final AgenticAuthoringPatchCompilerService patchCompilerService;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringIntentResolutionContext intentResolutionContext;
    private final AgenticAuthoringConversationTurnOrchestrator conversationTurnOrchestrator;
    private final List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders;
    private final AgenticAuthoringPreviewMessageSynthesizerService messageSynthesizer;
    private final SchemaRetrievalService schemaRetrievalService;

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService) {
        this(planService, patchCompilerService, new ObjectMapper(), List.of());
    }

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper) {
        this(planService, patchCompilerService, objectMapper, List.of());
    }

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper,
            List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders) {
        this(planService, patchCompilerService, objectMapper, uiCompositionPlanProviders, null);
    }

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper,
            List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders,
            AgenticAuthoringPreviewMessageSynthesizerService messageSynthesizer) {
        this(planService, patchCompilerService, objectMapper, uiCompositionPlanProviders, messageSynthesizer, null);
    }

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper,
            List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders,
            AgenticAuthoringPreviewMessageSynthesizerService messageSynthesizer,
            SchemaRetrievalService schemaRetrievalService) {
        this.planService = Objects.requireNonNull(planService, "planService must not be null");
        this.patchCompilerService = Objects.requireNonNull(patchCompilerService, "patchCompilerService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.intentResolutionContext = new AgenticAuthoringIntentResolutionContext(this.objectMapper);
        this.conversationTurnOrchestrator = new AgenticAuthoringConversationTurnOrchestrator();
        this.uiCompositionPlanProviders = List.copyOf(
                uiCompositionPlanProviders == null ? List.of() : uiCompositionPlanProviders);
        this.messageSynthesizer = messageSynthesizer;
        this.schemaRetrievalService = schemaRetrievalService;
    }

    public AgenticAuthoringPreviewResult preview(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment) throws IOException {
        return preview(request, tenantId, userId, environment, null);
    }

    public AgenticAuthoringPreviewResult preview(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment,
            String schemaBaseUrl) throws IOException {
        AgenticAuthoringPlanRequest effectiveRequest = enrichRequest(request);
        AgenticAuthoringIntentResolutionResult intentResolution =
                effectiveRequest == null ? null : effectiveRequest.intentResolution();
        List<String> routeFailures = validateSharedRuleRoute(intentResolution);
        if (!routeFailures.isEmpty()) {
            List<String> warnings = new ArrayList<>();
            if (intentResolution != null) {
                warnings.addAll(intentResolution.warnings());
            }
            warnings.add("preview-skipped-invalid-intent-resolution");
            return new AgenticAuthoringPreviewResult(
                    false,
                    List.copyOf(routeFailures),
                    List.copyOf(warnings),
                    MissingNode.getInstance(),
                    MissingNode.getInstance(),
                    diagnostics(intentResolution, List.copyOf(routeFailures), List.copyOf(warnings))
            );
        }
        Optional<AgenticAuthoringPreviewResult> uiCompositionPreview =
                previewUiCompositionPlan(effectiveRequest, tenantId, userId, environment, schemaBaseUrl);
        if (uiCompositionPreview.isPresent()) {
            return uiCompositionPreview.get();
        }
        List<String> intentFailures = validateIntentResolution(intentResolution);
        if (!intentFailures.isEmpty()) {
            List<String> warnings = new ArrayList<>();
            if (intentResolution != null) {
                warnings.addAll(intentResolution.warnings());
            }
            if (requiresUiCompositionPlan(intentResolution)) {
                warnings.add(uiCompositionPlanProviderDiagnostic());
            }
            warnings.add("preview-skipped-invalid-intent-resolution");
            return new AgenticAuthoringPreviewResult(
                    false,
                    List.copyOf(intentFailures),
                    List.copyOf(warnings),
                    MissingNode.getInstance(),
                    MissingNode.getInstance(),
                    diagnostics(intentResolution, List.copyOf(intentFailures), List.copyOf(warnings))
            );
        }
        AgenticAuthoringPlanResult planResult =
                planService.generateMinimalFormPlan(effectiveRequest, tenantId, userId, environment);
        List<String> failureCodes = new ArrayList<>(planResult.failureCodes());
        List<String> warnings = new ArrayList<>(planResult.warnings());
        if (!planResult.valid()) {
            warnings.add("compile-skipped-invalid-minimal-form-plan");
            return new AgenticAuthoringPreviewResult(
                    false,
                    List.copyOf(failureCodes),
                    List.copyOf(warnings),
                    planResult.minimalFormPlan(),
                    MissingNode.getInstance(),
                    diagnostics(
                            effectiveRequest,
                            intentResolution,
                            List.copyOf(failureCodes),
                            List.copyOf(warnings),
                            planResult.minimalFormPlan(),
                            MissingNode.getInstance())
            );
        }

        AgenticAuthoringCompileResult compileResult =
                patchCompilerService.compile(new AgenticAuthoringCompileRequest(
                        planResult.minimalFormPlan(),
                        effectiveRequest == null ? null : effectiveRequest.currentPage(),
                        intentResolution));
        failureCodes.addAll(compileResult.failureCodes());
        warnings.addAll(compileResult.warnings());
        boolean valid = planResult.valid() && compileResult.valid();
        String fallbackMessage = deterministicPreviewAssistantMessage(
                intentResolution,
                null,
                valid,
                List.copyOf(failureCodes));
        return new AgenticAuthoringPreviewResult(
                valid,
                List.copyOf(failureCodes),
                List.copyOf(warnings),
                planResult.minimalFormPlan(),
                compileResult.compiledFormPatch(),
                diagnostics(
                        effectiveRequest,
                        intentResolution,
                        List.copyOf(failureCodes),
                        List.copyOf(warnings),
                        planResult.minimalFormPlan(),
                        compileResult.compiledFormPatch()),
                null,
                previewAssistantMessage(
                        effectiveRequest,
                        intentResolution,
                        null,
                        valid,
                        List.copyOf(failureCodes),
                        List.copyOf(warnings),
                        fallbackMessage,
                        tenantId,
                        userId,
                        environment)
        );
    }

    private Optional<AgenticAuthoringPreviewResult> previewUiCompositionPlan(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment,
            String schemaBaseUrl) {
        if (request == null) {
            return Optional.empty();
        }
        for (AgenticAuthoringUiCompositionPlanProvider provider : uiCompositionPlanProviders) {
            Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(request);
            if (result.isEmpty()) {
                continue;
            }
            AgenticAuthoringUiCompositionPlanResult planResult = result.get();
            List<String> failureCodes = new ArrayList<>(
                    planResult.failureCodes() == null ? List.of() : planResult.failureCodes());
            List<String> warnings = new ArrayList<>(
                    planResult.warnings() == null ? List.of() : planResult.warnings());
            boolean technicallyValid = planResult.valid();
            boolean semanticallyValid = planResult.valid();
            if (requiresOperationalMonitoringDashboard(request, request.intentResolution())
                    && !AgenticAuthoringSemanticMaterializationPolicy.containsComponent(
                    planResult.uiCompositionPlan(), "praxis-chart")) {
                addOnce(failureCodes, "semantic-preview-operational-dashboard-required");
                addOnce(warnings, AgenticAuthoringSemanticMaterializationPolicy.MATERIALIZATION_MISMATCH_WARNING);
                semanticallyValid = false;
            }
            JsonNode uiCompositionPlan = verifySemanticAxesWithSchema(
                    request,
                    planResult.uiCompositionPlan(),
                    warnings,
                    schemaBaseUrl);
            uiCompositionPlan = verifyResourceSchemaGrounding(
                    request,
                    uiCompositionPlan,
                    warnings,
                    schemaBaseUrl);
            if (containsUnverifiedSemanticAxes(uiCompositionPlan)) {
                warnings.add("semantic-axis-schema-verification-pending");
            }
            AgenticAuthoringSemanticMaterializationPolicy.ValidationResult semanticValidation =
                    AgenticAuthoringSemanticMaterializationPolicy.validate(
                    semanticDecision(request.intentResolution()),
                    uiCompositionPlan);
            addAllOnce(failureCodes, semanticValidation.failureCodes());
            addAllOnce(warnings, semanticValidation.warnings());
            if (!semanticValidation.valid()) {
                semanticallyValid = false;
            }
            warnings.add("compiled-form-patch-materialized-by-page-builder");
            String fallbackMessage = deterministicPreviewAssistantMessage(
                    request.intentResolution(),
                    uiCompositionPlan,
                    semanticallyValid,
                    List.copyOf(failureCodes));
            return Optional.of(new AgenticAuthoringPreviewResult(
                    technicallyValid,
                    List.copyOf(failureCodes),
                    List.copyOf(warnings),
                    MissingNode.getInstance(),
                    planResult.compiledFormPatch() == null ? MissingNode.getInstance() : planResult.compiledFormPatch(),
                    diagnostics(
                            request,
                            request.intentResolution(),
                            failureCodes,
                            List.copyOf(warnings),
                            uiCompositionPlan,
                            planResult.compiledFormPatch() == null
                                    ? MissingNode.getInstance()
                                    : planResult.compiledFormPatch()),
                    uiCompositionPlan,
                    previewAssistantMessage(
                            request,
                            request.intentResolution(),
                            uiCompositionPlan,
                            semanticallyValid,
                            List.copyOf(failureCodes),
                            List.copyOf(warnings),
                            fallbackMessage,
                            tenantId,
                            userId,
                            environment)
            ));
        }
        return Optional.empty();
    }

    private JsonNode verifyResourceSchemaGrounding(
            AgenticAuthoringPlanRequest request,
            JsonNode uiCompositionPlan,
            List<String> warnings,
            String schemaBaseUrl) {
        if (schemaRetrievalService == null || uiCompositionPlan == null || uiCompositionPlan.isMissingNode()) {
            return uiCompositionPlan;
        }
        AgenticAuthoringCandidate candidate = request == null || request.intentResolution() == null
                ? null
                : request.intentResolution().selectedCandidate();
        AiSchemaContext schemaContext = schemaContext(candidate);
        if (schemaContext == null) {
            return uiCompositionPlan;
        }
        SchemaFetchResult schemaResult = schemaRetrievalService.fetchSchemaResult(schemaContext, schemaBaseUrl);
        if (schemaResult == null || !schemaResult.isSuccess()) {
            return uiCompositionPlan;
        }
        Map<String, SchemaFieldDescriptor> schemaFields = schemaFields(schemaResult.getSchema());
        if (schemaFields.isEmpty()) {
            return uiCompositionPlan;
        }
        JsonNode copy = uiCompositionPlan.deepCopy();
        if (copy instanceof ObjectNode objectNode) {
            markResourceSchemaGrounded(objectNode, schemaResult, schemaFields.size());
            return objectNode;
        }
        return copy;
    }

    private void markResourceSchemaGrounded(
            ObjectNode uiCompositionPlan,
            SchemaFetchResult schemaResult,
            int fieldCount) {
        ObjectNode diagnostics = uiCompositionPlan.path("diagnostics") instanceof ObjectNode existing
                ? existing
                : uiCompositionPlan.putObject("diagnostics");
        ObjectNode grounding = diagnostics.putObject("resourceSchemaGrounding");
        grounding.put("verified", true);
        grounding.put("source", "schemas.filtered");
        grounding.put("endpointUrl", schemaResult == null ? "" : value(schemaResult.getEndpointUrl()));
        grounding.put("fieldCount", Math.max(0, fieldCount));
        JsonNode widgets = uiCompositionPlan.path("widgets");
        if (widgets.isArray()) {
            for (JsonNode widget : widgets) {
                if (widget.path("inputs") instanceof ObjectNode inputs) {
                    inputs.put("schemaVerification", "verified");
                    inputs.put("schemaEvidenceSource", "schemas.filtered");
                    inputs.put("schemaEvidenceUrl", schemaResult == null ? "" : value(schemaResult.getEndpointUrl()));
                }
                if (widget.path("definition").path("inputs") instanceof ObjectNode definitionInputs) {
                    definitionInputs.put("schemaVerification", "verified");
                    definitionInputs.put("schemaEvidenceSource", "schemas.filtered");
                    definitionInputs.put("schemaEvidenceUrl", schemaResult == null ? "" : value(schemaResult.getEndpointUrl()));
                }
            }
        }
    }

    private JsonNode verifySemanticAxesWithSchema(
            AgenticAuthoringPlanRequest request,
            JsonNode uiCompositionPlan,
            List<String> warnings,
            String schemaBaseUrl) {
        if (schemaRetrievalService == null || !containsUnverifiedSemanticAxes(uiCompositionPlan)) {
            return uiCompositionPlan;
        }
        AgenticAuthoringCandidate candidate = request == null || request.intentResolution() == null
                ? null
                : request.intentResolution().selectedCandidate();
        AiSchemaContext schemaContext = schemaContext(candidate);
        if (schemaContext == null) {
            addWarningOnce(warnings, "semantic-axis-schema-verification-invalid-context");
            return uiCompositionPlan;
        }
        SchemaFetchResult schemaResult = schemaRetrievalService.fetchSchemaResult(schemaContext, schemaBaseUrl);
        if (schemaResult == null || !schemaResult.isSuccess()) {
            String status = schemaResult == null || schemaResult.getStatus() == null
                    ? "unavailable"
                    : schemaResult.getStatus().name().toLowerCase(java.util.Locale.ROOT);
            addWarningOnce(warnings, "semantic-axis-schema-verification-" + status);
            return uiCompositionPlan;
        }
        Map<String, SchemaFieldDescriptor> schemaFields = schemaFields(schemaResult.getSchema());
        if (schemaFields.isEmpty()) {
            addWarningOnce(warnings, "semantic-axis-schema-verification-no-fields");
            return uiCompositionPlan;
        }
        JsonNode copy = uiCompositionPlan == null ? MissingNode.getInstance() : uiCompositionPlan.deepCopy();
        if (copy instanceof ObjectNode objectNode) {
            reconcileSemanticAxesWithSchema(objectNode, schemaFields, schemaResult, warnings);
            return objectNode;
        }
        return copy;
    }

    private AiSchemaContext schemaContext(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        java.util.Map<String, String> query = queryParameters(candidate.schemaUrl());
        String path = valueOrDefault(query.get("path"), candidate.submitUrl());
        String operation = valueOrDefault(query.get("operation"), candidate.submitMethod());
        String schemaType = valueOrDefault(query.get("schemaType"), "response");
        if (isStatsPath(path) || isStatsPath(candidate.submitUrl()) || isStatsPath(candidate.resourcePath())) {
            path = businessResourcePath(firstNonBlank(candidate.resourcePath(), candidate.submitUrl())) + "/filter/cursor";
            operation = "post";
            schemaType = "response";
        }
        String businessPath = businessResourcePath(firstNonBlank(
                firstNonBlank(path, candidate.submitUrl()),
                candidate.resourcePath()));
        if (!businessPath.isBlank()
                && normalize(path).equals(normalize(businessPath))
                && "get".equalsIgnoreCase(operation)) {
            path = businessPath + "/filter/cursor";
            operation = "post";
            schemaType = "response";
        }
        if (path.isBlank() || operation.isBlank() || schemaType.isBlank()) {
            return null;
        }
        return AiSchemaContext.builder()
                .path(path)
                .operation(operation)
                .schemaType(schemaType)
                .build();
    }

    private java.util.Map<String, String> queryParameters(String url) {
        java.util.Map<String, String> parameters = new java.util.LinkedHashMap<>();
        String value = value(url);
        int queryIndex = value.indexOf('?');
        if (queryIndex < 0 || queryIndex == value.length() - 1) {
            return parameters;
        }
        for (String pair : value.substring(queryIndex + 1).split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String key = separator >= 0 ? pair.substring(0, separator) : pair;
            String rawValue = separator >= 0 ? pair.substring(separator + 1) : "";
            parameters.put(
                    URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private String businessResourcePath(String resourcePath) {
        String value = value(resourcePath).replaceAll("/+$", "");
        for (String suffix : List.of(
                "/stats/group-by",
                "/stats/timeseries",
                "/stats/distribution",
                "/filter/cursor",
                "/filter",
                "/all")) {
            if (value.endsWith(suffix)) {
                value = value.substring(0, value.length() - suffix.length());
                break;
            }
        }
        return value;
    }

    private boolean isStatsPath(String value) {
        String normalized = value(value);
        return normalized.contains("/stats/group-by")
                || normalized.contains("/stats/timeseries")
                || normalized.contains("/stats/distribution");
    }

    private Map<String, SchemaFieldDescriptor> schemaFields(JsonNode schema) {
        Map<String, SchemaFieldDescriptor> fields = new LinkedHashMap<>();
        collectSchemaFields(schema, fields);
        return fields;
    }

    private void collectSchemaFields(JsonNode node, Map<String, SchemaFieldDescriptor> fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                String field = entry.getKey();
                if (!value(field).isBlank()) {
                    fields.putIfAbsent(normalize(field), schemaFieldDescriptor(field, entry.getValue()));
                }
            });
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectSchemaFields(entry.getValue(), fields));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectSchemaFields(item, fields);
            }
        }
    }

    private void reconcileSemanticAxesWithSchema(
            ObjectNode uiCompositionPlan,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        JsonNode axes = uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (axes.isArray()) {
            for (JsonNode axis : axes) {
                if (axis instanceof ObjectNode axisObject) {
                    Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisObject, schemaFields);
                    if (schemaField.isPresent()) {
                        alignSemanticAxis(axisObject, schemaField.get(), schemaResult);
                    } else {
                        markSemanticAxisUnsupported(axisObject);
                        addWarningOnce(warnings, "semantic-axis-schema-verification-unsupported-axis");
                    }
                }
            }
        }
        JsonNode widgets = uiCompositionPlan.path("widgets");
        if (widgets instanceof ArrayNode widgetArray) {
            for (int i = widgetArray.size() - 1; i >= 0; i--) {
                JsonNode widget = widgetArray.get(i);
                alignAuxiliaryWidgetBindings(widget, schemaFields, schemaResult, warnings);
                JsonNode axis = widget.path("inputs").path("config").path("semanticAxis");
                if (!(axis instanceof ObjectNode axisObject)) {
                    continue;
                }
                Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisObject, schemaFields);
                if (schemaField.isPresent()) {
                    alignSemanticAxis(axisObject, schemaField.get(), schemaResult);
                    alignChartBinding(widget, schemaField.get());
                } else {
                    widgetArray.remove(i);
                }
            }
            pruneOrphanWidgetBindings(uiCompositionPlan, widgetArray, warnings);
        }
    }

    private void pruneOrphanWidgetBindings(
            ObjectNode uiCompositionPlan,
            ArrayNode widgets,
            List<String> warnings) {
        JsonNode bindings = uiCompositionPlan.path("bindings");
        if (!(bindings instanceof ArrayNode bindingsArray)) {
            return;
        }
        Set<String> widgetKeys = new LinkedHashSet<>();
        for (JsonNode widget : widgets) {
            String key = firstNonBlank(
                    widget.path("key").asText(""),
                    widget.path("id").asText(""));
            if (!key.isBlank()) {
                widgetKeys.add(key);
            }
        }
        for (int i = bindingsArray.size() - 1; i >= 0; i--) {
            JsonNode binding = bindingsArray.get(i);
            String fromWidget = bindingWidgetKey(binding.path("from"));
            String toWidget = bindingWidgetKey(binding.path("to"));
            boolean fromMissing = !fromWidget.isBlank() && !widgetKeys.contains(fromWidget);
            boolean toMissing = !toWidget.isBlank() && !widgetKeys.contains(toWidget);
            if (fromMissing || toMissing) {
                bindingsArray.remove(i);
                addWarningOnce(warnings, "ui-composition-plan-orphan-binding-removed");
            }
        }
    }

    private String bindingWidgetKey(JsonNode endpoint) {
        return firstNonBlank(
                endpoint.path("widgetKey").asText(""),
                endpoint.path("widget").asText(""));
    }

    private void alignAuxiliaryWidgetBindings(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        if (widget == null || !widget.isObject()) {
            return;
        }
        String componentId = widget.path("componentId").asText("");
        if ("praxis-filter".equals(componentId)) {
            alignFilterFields(widget, schemaFields, schemaResult, warnings);
        }
        if ("praxis-rich-content".equals(componentId) && "kpi-band".equals(widget.path("role").asText(""))) {
            alignKpiFields(widget, schemaFields, schemaResult, warnings);
        }
    }

    private void alignFilterFields(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        JsonNode selectedFields = widget.path("inputs").path("selectedFieldIds");
        if (!(selectedFields instanceof ArrayNode fieldsArray)) {
            return;
        }
        for (int i = fieldsArray.size() - 1; i >= 0; i--) {
            String requestedField = fieldsArray.get(i).asText("");
            Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisProbe(requestedField), schemaFields);
            if (schemaField.isPresent()) {
                fieldsArray.set(i, objectMapper.getNodeFactory().textNode(schemaField.get().name()));
            } else {
                fieldsArray.remove(i);
                addWarningOnce(warnings, "semantic-filter-schema-verification-unsupported-field");
            }
        }
        if (widget.path("inputs") instanceof ObjectNode inputsObject) {
            inputsObject.put("schemaVerification", "verified");
            inputsObject.put("schemaEvidenceSource", "schemas.filtered");
            inputsObject.put("schemaEvidenceUrl", schemaResult == null ? "" : value(schemaResult.getEndpointUrl()));
        }
    }

    private void alignKpiFields(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        JsonNode kpis = widget.path("inputs").path("kpis");
        if (!(kpis instanceof ArrayNode kpiArray)) {
            return;
        }
        for (int i = kpiArray.size() - 1; i >= 0; i--) {
            JsonNode kpi = kpiArray.get(i);
            String dimensionField = kpi.path("dimensionField").asText("");
            if (dimensionField.isBlank()) {
                continue;
            }
            Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisProbe(dimensionField), schemaFields);
            if (schemaField.isPresent()) {
                if (kpi instanceof ObjectNode kpiObject) {
                    kpiObject.put("dimensionField", schemaField.get().name());
                    kpiObject.put("schemaVerified", true);
                    kpiObject.put("schemaProbeStatus", "verified");
                    kpiObject.put("schemaEvidenceSource", "schemas.filtered");
                    kpiObject.put("schemaEvidenceUrl", schemaResult == null ? "" : value(schemaResult.getEndpointUrl()));
                }
            } else {
                kpiArray.remove(i);
                addWarningOnce(warnings, "semantic-kpi-schema-verification-unsupported-field");
            }
        }
    }

    private ObjectNode axisProbe(String field) {
        ObjectNode probe = objectMapper.createObjectNode();
        probe.put("field", field == null ? "" : field);
        probe.put("label", field == null ? "" : field);
        probe.put("concept", field == null ? "" : field);
        return probe;
    }

    private void alignSemanticAxis(
            ObjectNode semanticAxis,
            SchemaFieldDescriptor field,
            SchemaFetchResult schemaResult) {
        String requestedField = semanticAxis.path("field").asText("");
        if (!normalize(requestedField).equals(normalize(field.name()))) {
            semanticAxis.put("requestedField", requestedField);
            semanticAxis.put("field", field.name());
            if (!field.label().isBlank()) {
                semanticAxis.put("schemaLabel", field.label());
            }
        }
        semanticAxis.put("schemaVerified", true);
        semanticAxis.put("schemaProbeStatus", "verified");
        ObjectNode evidence = semanticAxis.putObject("schemaEvidence");
        evidence.put("source", "schemas.filtered");
        evidence.put("endpointUrl", schemaResult == null ? "" : value(schemaResult.getEndpointUrl()));
    }

    private void markSemanticAxisUnsupported(ObjectNode semanticAxis) {
        semanticAxis.put("schemaVerified", false);
        semanticAxis.put("schemaProbeStatus", "unsupported");
    }

    private void alignChartBinding(JsonNode widget, SchemaFieldDescriptor field) {
        ObjectNode config = widget.path("inputs").path("config") instanceof ObjectNode objectNode
                ? objectNode
                : null;
        if (config == null) {
            return;
        }
        JsonNode xAxis = config.path("axes").path("x");
        if (xAxis instanceof ObjectNode xAxisObject) {
            xAxisObject.put("field", field.name());
            if (!field.label().isBlank()) {
                xAxisObject.put("label", field.label());
            }
        }
        JsonNode series = config.path("series");
        if (series.isArray()) {
            for (JsonNode item : series) {
                if (item instanceof ObjectNode seriesObject) {
                    seriesObject.put("categoryField", field.name());
                }
            }
        }
        JsonNode dimensions = config.path("dataSource").path("query").path("dimensions");
        if (dimensions instanceof ArrayNode dimensionsArray && !dimensionsArray.isEmpty()) {
            dimensionsArray.set(0, objectMapper.getNodeFactory().textNode(field.name()));
        }
        JsonNode statsRequest = config.path("dataSource").path("query").path("statsRequest");
        if (statsRequest instanceof ObjectNode statsRequestObject) {
            statsRequestObject.put("field", field.name());
        }
    }

    private Optional<SchemaFieldDescriptor> resolveSchemaField(
            ObjectNode semanticAxis,
            Map<String, SchemaFieldDescriptor> schemaFields) {
        String requestedField = semanticAxis.path("field").asText("");
        SchemaFieldDescriptor exact = schemaFields.get(normalize(requestedField));
        if (exact != null) {
            return Optional.of(exact);
        }
        if (isAggregateCountAxis(semanticAxis)) {
            return preferredGroupingField(schemaFields);
        }
        Set<String> requestedTokens = semanticAxisTokens(semanticAxis);
        SchemaFieldDescriptor best = null;
        int bestScore = 0;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            int score = semanticMatchScore(requestedTokens, field);
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        return bestScore >= 2 ? Optional.of(best) : Optional.empty();
    }

    private boolean isAggregateCountAxis(ObjectNode semanticAxis) {
        String raw = normalize(String.join(" ",
                semanticAxis.path("concept").asText(""),
                semanticAxis.path("field").asText(""),
                semanticAxis.path("label").asText("")));
        if (!containsAny(raw, "quantidade", "qtd", "count", "total", "registro", "registros")) {
            return false;
        }
        Set<String> tokens = new LinkedHashSet<>(semanticAxisTokens(semanticAxis));
        tokens.removeAll(Set.of(
                "recordcount",
                "record",
                "records",
                "quantidade",
                "qtd",
                "count",
                "contagem",
                "registro",
                "registros"));
        return tokens.isEmpty();
    }

    private Optional<SchemaFieldDescriptor> preferredGroupingField(Map<String, SchemaFieldDescriptor> schemaFields) {
        SchemaFieldDescriptor best = null;
        int bestScore = Integer.MIN_VALUE;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            int score = groupingFieldScore(field);
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        return best == null || bestScore <= 0 ? Optional.empty() : Optional.of(best);
    }

    private int groupingFieldScore(SchemaFieldDescriptor field) {
        Set<String> tokens = new LinkedHashSet<>();
        tokens.addAll(field.fieldTokens());
        tokens.addAll(field.labelTokens());
        tokens.addAll(field.descriptionTokens());
        if (tokens.isEmpty()) {
            return 0;
        }
        int score = 10;
        if (containsAnyToken(tokens, "status", "situacao", "andamento", "estado")) {
            score += 100;
        }
        if (containsAnyToken(tokens, "categoria", "classe", "tipo", "natureza")) {
            score += 90;
        }
        if (containsAnyToken(tokens, "departamento", "area", "equipe", "time", "setor")) {
            score += 85;
        }
        if (containsAnyToken(tokens, "mes", "competencia", "periodo")) {
            score += 80;
        }
        if (containsAnyToken(tokens, "ano", "exercicio")) {
            score += 75;
        }
        if (containsAnyToken(tokens, "data", "created", "criacao", "pagamento")) {
            score += 60;
        }
        if (containsAnyToken(tokens, "funcionario", "responsavel", "owner", "pessoa", "cliente")) {
            score += 45;
        }
        if (containsAnyToken(tokens, "id", "uuid", "codigo")) {
            score -= 80;
        }
        if (containsAnyToken(tokens,
                "salario",
                "valor",
                "total",
                "quantidade",
                "desconto",
                "bruto",
                "liquido",
                "preco",
                "amount")) {
            score -= 100;
        }
        return score;
    }

    private boolean containsAnyToken(Set<String> tokens, String... expected) {
        for (String token : expected) {
            if (tokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private int semanticMatchScore(Set<String> requestedTokens, SchemaFieldDescriptor field) {
        if (requestedTokens.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String token : requestedTokens) {
            if (field.fieldTokens().contains(token)) {
                score += 3;
            } else if (field.labelTokens().contains(token)) {
                score += 3;
            } else if (field.descriptionTokens().contains(token)) {
                score += 2;
            }
        }
        return score;
    }

    private Set<String> semanticAxisTokens(ObjectNode semanticAxis) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, semanticAxis.path("concept").asText(""));
        addTokens(tokens, semanticAxis.path("field").asText(""));
        addTokens(tokens, semanticAxis.path("label").asText(""));
        return tokens;
    }

    private SchemaFieldDescriptor schemaFieldDescriptor(String field, JsonNode property) {
        String label = firstNonBlank(
                property.path("x-ui").path("label").asText(""),
                property.path("title").asText(""));
        String description = firstNonBlank(
                property.path("description").asText(""),
                property.path("x-ui").path("helpText").asText(""));
        return new SchemaFieldDescriptor(
                field,
                label,
                description,
                tokens(field),
                tokens(label),
                tokens(description));
    }

    private Set<String> tokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, value);
        return tokens;
    }

    private void addTokens(Set<String> tokens, String value) {
        for (String token : normalize(value).replaceAll("[^a-z0-9]+", " ").split("\\s+")) {
            if (token.length() >= 3 && !SEMANTIC_AXIS_STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
    }

    private void addWarningOnce(List<String> warnings, String warning) {
        if (warnings != null && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private boolean containsUnverifiedSemanticAxes(JsonNode uiCompositionPlan) {
        JsonNode axes = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return false;
        }
        for (JsonNode axis : axes) {
            if (!axis.path("schemaVerified").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresOperationalMonitoringDashboard(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution != null && "dashboard".equals(intentResolution.artifactKind())) {
            String prompt = normalize(firstNonBlank(intentResolution.effectivePrompt(), request == null ? "" : request.userPrompt()));
            return isOperationalMonitoringPrompt(prompt);
        }
        String prompt = normalize(
                firstNonBlank(
                        intentResolution == null ? "" : intentResolution.effectivePrompt(),
                        request == null ? "" : request.userPrompt()));
        return isOperationalMonitoringPrompt(prompt);
    }

    private boolean isOperationalMonitoringPrompt(String prompt) {
        boolean monitoringIntent = containsAny(prompt,
                "monitorar", "acompanhar", "controlar", "painel de controle", "observabilidade");
        int operationalAxes = 0;
        if (containsAny(prompt, "gravidade", "severidade", "prioridade", "risco", "riscos")) {
            operationalAxes++;
        }
        if (containsAny(prompt, "andamento", "status", "situacao", "etapa", "fila", "atendimento")) {
            operationalAxes++;
        }
        if (containsAny(prompt, "responsavel", "dono", "owner", "equipe", "time")) {
            operationalAxes++;
        }
        if (containsAny(prompt, "chamado", "chamados", "ocorrencia", "ocorrencias", "incidente", "incidentes", "caso", "casos")) {
            operationalAxes++;
        }
        return monitoringIntent && operationalAxes >= 2;
    }

    private boolean requiresUiCompositionPlan(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return false;
        }
        return !"form".equals(intentResolution.artifactKind())
                && ("create".equals(intentResolution.operationKind())
                || "modify".equals(intentResolution.operationKind())
                || "remove".equals(intentResolution.operationKind()));
    }

    private String uiCompositionPlanProviderDiagnostic() {
        return uiCompositionPlanProviders.isEmpty()
                ? "ui-composition-plan-provider-unavailable"
                : "ui-composition-plan-provider-no-plan";
    }

    private String previewAssistantMessage(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan,
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            String fallbackMessage,
            String tenantId,
            String userId,
            String environment) {
        if (messageSynthesizer == null) {
            return fallbackMessage;
        }
        return messageSynthesizer.synthesize(
                request,
                intentResolution,
                uiCompositionPlan,
                valid,
                failureCodes,
                warnings,
                fallbackMessage,
                tenantId,
                userId,
                environment);
    }

    private String deterministicPreviewAssistantMessage(
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan,
            boolean valid,
            List<String> failureCodes) {
        if (!valid) {
            if (failureCodes != null && failureCodes.contains("semantic-preview-chart-required")) {
                return "Encontrei a fonte de dados, mas a pre-visualizacao nao materializou o grafico solicitado. Vou manter em revisao ate gerar um plano com componente analitico.";
            }
            if (failureCodes != null && failureCodes.contains("semantic-preview-operational-dashboard-required")) {
                return "Encontrei a fonte de dados, mas a pre-visualizacao nao materializou um dashboard de monitoramento. Vou manter em revisao ate gerar indicadores, grafico e detalhe operacional.";
            }
            return "Encontrei a fonte de dados, mas o plano gerado usou propriedades incompativeis com o componente de tabela. Vou ajustar para usar apenas os campos suportados.";
        }
        AgenticAuthoringCandidate candidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        if (candidate == null || value(candidate.resourcePath()).isBlank()) {
            return "";
        }
        String resourceLabel = titleFromResourcePath(candidate.resourcePath());
        if (AgenticAuthoringSemanticMaterializationPolicy.containsComponent(uiCompositionPlan, "praxis-chart")) {
            SemanticAxisAssistantSummary axisSummary = semanticAxisAssistantSummary(uiCompositionPlan);
            String visualization = axisSummary.verifiedAxes().isEmpty()
                    ? "grafico conectado ao recorte analitico solicitado"
                    : "grafico por " + joinHuman(axisSummary.verifiedAxes()) + ", validado em /schemas/filtered";
            String governance = axisSummary.unsupportedAxes().isEmpty()
                    ? "campos semanticos verificados antes da materializacao"
                    : "nao criei graficos para " + joinHuman(axisSummary.unsupportedAxes())
                    + " porque esses campos nao aparecem no schema da fonte";
            return "Criei uma pre-visualizacao de dashboard analitico.\n\n"
                    + "- Fonte governada: \"" + resourceLabel + "\".\n"
                    + "- Visualizacao: " + visualization + ".\n"
                    + "- Governanca: " + governance + ".\n"
                    + "- Apoio: " + detailSupportAssistantLine(uiCompositionPlan) + ".";
        }
        if (containsComponent(uiCompositionPlan, "praxis-table")) {
            if (containsRichTableRendering(uiCompositionPlan)) {
                return "Criei uma pre-visualizacao com tabela semantica.\n\n"
                        + "- Fonte governada: \"" + resourceLabel + "\".\n"
                        + "- Tabela: colunas semanticas, valores formatados, chips, icones e acoes por linha.\n"
                        + "- Proximo passo: revise os detalhes ou peca uma visualizacao analitica.";
            }
            return "Criei uma pre-visualizacao com tabela conectada.\n\n"
                    + "- Fonte de dados: \"" + resourceLabel + "\".\n"
                    + "- Tabela: conectada ao recurso para carregar schema e dados.\n"
                    + "- Proximo passo: revise as colunas, peca um grafico ou salve a pagina.";
        }
        return "Criei uma pre-visualizacao para revisao.\n\n"
                + "- Fonte de dados: \"" + resourceLabel + "\".\n"
                + "- Proximo passo: revise o resultado e salve a pagina quando estiver de acordo.";
    }

    private SemanticAxisAssistantSummary semanticAxisAssistantSummary(JsonNode uiCompositionPlan) {
        List<String> verifiedAxes = new ArrayList<>();
        List<String> unsupportedAxes = new ArrayList<>();
        JsonNode axes = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return new SemanticAxisAssistantSummary(List.of(), List.of());
        }
        for (JsonNode axis : axes) {
            String label = semanticAxisDisplayName(axis);
            if (label.isBlank()) {
                continue;
            }
            if ("unsupported".equals(axis.path("schemaProbeStatus").asText(""))) {
                addUnique(unsupportedAxes, label);
            } else if (axis.path("schemaVerified").asBoolean(false)) {
                addUnique(verifiedAxes, label);
            }
        }
        return new SemanticAxisAssistantSummary(List.copyOf(verifiedAxes), List.copyOf(unsupportedAxes));
    }

    private String semanticAxisDisplayName(JsonNode axis) {
        if (axis == null || axis.isMissingNode() || axis.isNull()) {
            return "";
        }
        String label = firstNonBlank(
                axis.path("schemaLabel").asText(""),
                firstNonBlank(axis.path("label").asText(""), axis.path("field").asText("")));
        String requestedField = axis.path("requestedField").asText("");
        if (axis.path("schemaVerified").asBoolean(false)
                && !requestedField.isBlank()
                && !normalize(requestedField).equals(normalize(axis.path("field").asText("")))) {
            return label + " (pedido como " + requestedField + ")";
        }
        return label;
    }

    private String detailSupportAssistantLine(JsonNode uiCompositionPlan) {
        if (containsComponent(uiCompositionPlan, "praxis-table")) {
            return "tabela de detalhe conectada ao recurso para validar os dados antes de salvar";
        }
        if (containsComponent(uiCompositionPlan, "praxis-list")) {
            return "lista de detalhe em cards ricos para validar os dados antes de salvar";
        }
        return "componentes de apoio para validar os dados antes de salvar";
    }

    private void addUnique(List<String> values, String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return;
        }
        for (String existing : values) {
            if (normalize(existing).equals(normalized)) {
                return;
            }
        }
        values.add(value);
    }

    private String joinHuman(List<String> values) {
        List<String> filtered = values == null
                ? List.of()
                : values.stream().filter(value -> !value(value).isBlank()).toList();
        if (filtered.isEmpty()) {
            return "";
        }
        if (filtered.size() == 1) {
            return filtered.get(0);
        }
        if (filtered.size() == 2) {
            return filtered.get(0) + " e " + filtered.get(1);
        }
        return String.join(", ", filtered.subList(0, filtered.size() - 1))
                + " e "
                + filtered.get(filtered.size() - 1);
    }

    private boolean containsComponent(JsonNode uiCompositionPlan, String componentId) {
        return AgenticAuthoringSemanticMaterializationPolicy.containsComponent(uiCompositionPlan, componentId);
    }

    private boolean containsRichTableRendering(JsonNode uiCompositionPlan) {
        JsonNode widgets = uiCompositionPlan == null ? MissingNode.getInstance() : uiCompositionPlan.path("widgets");
        if (!widgets.isArray()) {
            return false;
        }
        for (JsonNode widget : widgets) {
            if (!"praxis-table".equals(widget.path("componentId").asText())) {
                continue;
            }
            JsonNode columns = widget.path("inputs").path("config").path("columns");
            if (!columns.isArray()) {
                continue;
            }
            for (JsonNode column : columns) {
                if (column.has("renderer") || !value(column.path("format").asText()).isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsAny(String value, String... tokens) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String first, String second) {
        return value(first).isBlank() ? value(second) : value(first);
    }

    private String normalize(String value) {
        String normalized = java.text.Normalizer.normalize(value(value), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(java.util.Locale.ROOT).trim();
    }

    private String titleFromResourcePath(String resourcePath) {
        String value = value(resourcePath);
        if (value.isBlank()) {
            return "o recurso selecionado";
        }
        String lastSegment = value.substring(value.lastIndexOf('/') + 1)
                .replace("vw-", "")
                .replace('-', ' ')
                .trim();
        if (lastSegment.isBlank()) {
            return "o recurso selecionado";
        }
        return Character.toUpperCase(lastSegment.charAt(0)) + lastSegment.substring(1);
    }

    private AgenticAuthoringPlanRequest enrichRequest(AgenticAuthoringPlanRequest request) {
        if (request == null) {
            return null;
        }
        request = withEffectivePrompt(request);
        AgenticAuthoringIntentResolutionResult enrichedIntent =
                intentResolutionContext.enrich(request.intentResolution(), request.currentPage());
        if (enrichedIntent == request.intentResolution()) {
            return request;
        }
        return new AgenticAuthoringPlanRequest(
                request.userPrompt(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.currentPage(),
                enrichedIntent,
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                attachmentSummaries(request),
                request.contextHints());
    }

    private AgenticAuthoringPlanRequest withEffectivePrompt(AgenticAuthoringPlanRequest request) {
        String intentEffectivePrompt = request.intentResolution() == null
                ? ""
                : value(request.intentResolution().effectivePrompt()).trim();
        if (!intentEffectivePrompt.isBlank()) {
            String contextualEffectivePrompt = intentEffectivePrompt;
            if (isBareConfirmationPrompt(intentEffectivePrompt)) {
                AgenticAuthoringConversationTurn turn = conversationTurnOrchestrator.resolve(
                        intentEffectivePrompt,
                        request.conversationMessages(),
                        request.pendingClarification());
                contextualEffectivePrompt = turn.effectivePrompt();
                if (!Objects.equals(contextualEffectivePrompt, intentEffectivePrompt)) {
                    return withUserPrompt(request, contextualEffectivePrompt);
                }
            }
            if (Objects.equals(contextualEffectivePrompt, request.userPrompt())) {
                return request;
            }
            return withUserPrompt(request, contextualEffectivePrompt);
        }
        AgenticAuthoringConversationTurn turn = conversationTurnOrchestrator.resolve(
                request.userPrompt(),
                request.conversationMessages(),
                request.pendingClarification());
        String effectivePrompt = turn.effectivePrompt();
        if (Objects.equals(effectivePrompt, request.userPrompt())) {
            return request;
        }
        return withUserPrompt(request, effectivePrompt);
    }

    private AgenticAuthoringPlanRequest withUserPrompt(AgenticAuthoringPlanRequest request, String userPrompt) {
        return new AgenticAuthoringPlanRequest(
                userPrompt,
                request.provider(),
                request.model(),
                request.apiKey(),
                request.currentPage(),
                request.intentResolution(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                attachmentSummaries(request),
                request.contextHints());
    }

    private List<AgenticAuthoringAttachmentSummary> attachmentSummaries(AgenticAuthoringPlanRequest request) {
        if (request.attachmentSummaries() != null && !request.attachmentSummaries().isEmpty()) {
            return request.attachmentSummaries();
        }
        JsonNode diagnostics = request.pendingClarification() == null
                ? null
                : request.pendingClarification().diagnostics();
        JsonNode summaries = diagnostics == null ? null : diagnostics.path("attachmentSummaries");
        if (summaries == null || !summaries.isArray() || summaries.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(
                    summaries,
                    new TypeReference<List<AgenticAuthoringAttachmentSummary>>() {
                    });
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
    }

    private boolean isBareConfirmationPrompt(String prompt) {
        String normalized = value(prompt).toLowerCase(java.util.Locale.ROOT).trim();
        if (normalized.isBlank()) {
            return false;
        }
        boolean materializationRequest = normalized.matches(".*\\b(preview|previa|prévia|pre visualizacao|pré visualização|materialize|materializar)\\b.*");
        boolean generationVerb = normalized.matches(".*\\b(gere|gerar|generate)\\b.*");
        boolean newInstruction = normalized.matches(".*\\b(crie|criar|adicione|adicionar|altere|alterar|remova|remover|monte|montar|create|add|change|remove|build)\\b.*")
                || (generationVerb && !materializationRequest);
        if (newInstruction) {
            return false;
        }
        return normalized.matches(".*\\b(sim|confirmo|confirmado|confirmed|ok|siga|seguir|pode seguir|materialize|materializar|faça isso|faca isso)\\b.*");
    }

    private AgenticAuthoringPreviewDiagnostics diagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            List<String> failureCodes,
            List<String> warnings) {
        return diagnostics(null, intentResolution, failureCodes, warnings, MissingNode.getInstance(), MissingNode.getInstance());
    }

    private AgenticAuthoringPreviewDiagnostics diagnostics(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode minimalFormPlan,
            JsonNode compiledFormPatch) {
        if (intentResolution == null) {
            return new AgenticAuthoringPreviewDiagnostics(
                    false,
                    "",
                    "",
                    "",
                    "not-evaluated",
                    projectKnowledgeAudit(request, minimalFormPlan, compiledFormPatch));
        }
        String targetWidgetKey = intentResolution.target() == null ? "" : value(intentResolution.target().widgetKey());
        boolean derived = warnings.contains("current-page-summary-derived")
                || (intentResolution.warnings() != null && intentResolution.warnings().contains("current-page-summary-derived"));
        return new AgenticAuthoringPreviewDiagnostics(
                derived,
                targetWidgetKey,
                value(intentResolution.operationKind()),
                value(intentResolution.changeKind()),
                fieldScopeDecision(intentResolution, failureCodes),
                projectKnowledgeAudit(request, minimalFormPlan, compiledFormPatch));
    }

    private JsonNode projectKnowledgeAudit(
            AgenticAuthoringPlanRequest request,
            JsonNode minimalFormPlan,
            JsonNode compiledFormPatch) {
        JsonNode projectKnowledge = request == null || request.contextHints() == null
                ? MissingNode.getInstance()
                : request.contextHints().path("projectKnowledge");
        JsonNode entries = projectKnowledge.path("entries");
        if (!projectKnowledge.isObject() || !entries.isArray() || entries.isEmpty()) {
            return null;
        }
        Set<String> sourceRefs = sourceRefs(minimalFormPlan, compiledFormPatch);
        ObjectNode audit = objectMapper.createObjectNode();
        audit.put("schemaVersion", "praxis-agentic-authoring-project-knowledge-audit.v1");
        audit.put("source", safeText(projectKnowledge.path("source").asText("domain_knowledge_concept")));
        ArrayNode safeEntries = audit.putArray("entries");
        int citedCount = 0;
        for (JsonNode entry : entries) {
            if (!entry.isObject()) {
                continue;
            }
            String knowledgeId = safeText(entry.path("knowledgeId").asText(""));
            String conceptKey = safeText(entry.path("conceptKey").asText(""));
            List<String> matchedRefs = matchingProjectKnowledgeRefs(sourceRefs, knowledgeId, conceptKey);
            if (!matchedRefs.isEmpty()) {
                citedCount++;
            }
            ObjectNode safeEntry = safeEntries.addObject();
            safeEntry.put("knowledgeId", knowledgeId);
            safeEntry.put("conceptKey", conceptKey);
            safeEntry.put("kind", safeText(entry.path("kind").asText("")));
            safeEntry.put("visibility", safeText(entry.path("visibility").asText("")));
            safeEntry.put("influence", safeText(entry.path("influence").asText("")));
            safeEntry.put("sourceSummary", safeText(entry.path("sourceSummary").asText("")));
            safeEntry.put("cited", !matchedRefs.isEmpty());
            safeEntry.set("sourceRefs", objectMapper.valueToTree(matchedRefs));
        }
        audit.put("influenceCount", safeEntries.size());
        audit.put("citedCount", citedCount);
        audit.put("uncitedCount", Math.max(0, safeEntries.size() - citedCount));
        audit.put("citationPolicy", "sourceRefs must cite projectKnowledge entries when they materially influence the plan.");
        return audit;
    }

    private Set<String> sourceRefs(JsonNode minimalFormPlan, JsonNode compiledFormPatch) {
        Set<String> refs = new LinkedHashSet<>();
        collectSourceRefs(minimalFormPlan, refs);
        collectSourceRefs(compiledFormPatch, refs);
        return refs;
    }

    private void collectSourceRefs(JsonNode node, Set<String> refs) {
        JsonNode sourceRefs = node == null ? MissingNode.getInstance() : node.path("sourceRefs");
        if (!sourceRefs.isArray()) {
            return;
        }
        for (JsonNode sourceRef : sourceRefs) {
            if (sourceRef.isTextual() && !sourceRef.asText("").isBlank()) {
                refs.add(sourceRef.asText());
            }
        }
    }

    private List<String> matchingProjectKnowledgeRefs(
            Set<String> sourceRefs,
            String knowledgeId,
            String conceptKey) {
        if (sourceRefs == null || sourceRefs.isEmpty()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String sourceRef : sourceRefs) {
            if (!sourceRef.startsWith("projectKnowledge:")) {
                continue;
            }
            String ref = sourceRef.substring("projectKnowledge:".length());
            if ((!knowledgeId.isBlank() && knowledgeId.equals(ref))
                    || (!conceptKey.isBlank() && conceptKey.equals(ref))) {
                matches.add(sourceRef);
            }
        }
        return List.copyOf(matches);
    }

    private String fieldScopeDecision(AgenticAuthoringIntentResolutionResult intentResolution, List<String> failureCodes) {
        if (failureCodes.stream().anyMatch(code -> code.startsWith("add_field duplicates existing field: "))) {
            return "rejected-duplicate-field";
        }
        if (failureCodes.stream().anyMatch(code -> code.startsWith("remove_field requires current local/transient field: ")
                || code.startsWith("remove_field requires local/transient field: "))) {
            return "rejected-non-local-field-removal";
        }
        if ("modify".equals(intentResolution.operationKind()) && "add_field".equals(intentResolution.changeKind())) {
            return "accepted-add-local-field";
        }
        if ("remove".equals(intentResolution.operationKind()) && "remove_field".equals(intentResolution.changeKind())) {
            return "accepted-remove-local-field";
        }
        if ("modify".equals(intentResolution.operationKind()) && "rename_or_relabel".equals(intentResolution.changeKind())) {
            return "accepted-relabel-server-backed-field";
        }
        if ("create".equals(intentResolution.operationKind())) {
            return "accepted-create";
        }
        return "not-evaluated";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String valueOrDefault(String value, String fallback) {
        String sanitized = value(value).trim();
        return sanitized.isBlank() ? value(fallback).trim() : sanitized;
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private List<String> validateIntentResolution(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return List.of();
        }
        List<String> failures = new ArrayList<>();
        if (!intentResolution.valid()) {
            failures.add("intent-resolution-invalid");
        }
        if (intentResolution.gate() != null
                && "route_required".equals(intentResolution.gate().status())
                && intentResolution.gate().messages().contains("shared-rule-authoring-required")) {
            failures.add("intent-resolution-shared-rule-route-required");
        } else if (intentResolution.gate() == null || !"eligible".equals(intentResolution.gate().status())) {
            failures.add("intent-resolution-not-eligible");
        }
        if (intentResolution.selectedCandidate() == null) {
            failures.add("intent-resolution-selected-candidate-required");
        }
        if (!"create".equals(intentResolution.operationKind())
                && !"modify".equals(intentResolution.operationKind())
                && !"remove".equals(intentResolution.operationKind())) {
            failures.add("intent-resolution-operation-must-be-create-modify-or-remove");
        }
        if (!"form".equals(intentResolution.artifactKind())
                && !"dashboard".equals(intentResolution.artifactKind())
                && !"page".equals(intentResolution.artifactKind())
                && !"table".equals(intentResolution.artifactKind())) {
            failures.add("intent-resolution-artifact-must-be-form");
        }
        if (!"form".equals(intentResolution.artifactKind())
                && ("create".equals(intentResolution.operationKind())
                || "modify".equals(intentResolution.operationKind())
                || "remove".equals(intentResolution.operationKind()))) {
            failures.add("intent-resolution-artifact-requires-ui-composition-plan");
        }
        return List.copyOf(failures);
    }

    private AgenticAuthoringSemanticDecision semanticDecision(AgenticAuthoringIntentResolutionResult intentResolution) {
        return intentResolution == null ? null : intentResolution.semanticDecision();
    }

    private void addAllOnce(List<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addOnce(target, value);
        }
    }

    private void addOnce(List<String> target, String value) {
        if (target != null && value != null && !value.isBlank() && !target.contains(value)) {
            target.add(value);
        }
    }

    private List<String> validateSharedRuleRoute(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null || intentResolution.gate() == null) {
            return List.of();
        }
        if ("route_required".equals(intentResolution.gate().status())
                && intentResolution.gate().messages().contains("shared-rule-authoring-required")) {
            return List.of("intent-resolution-shared-rule-route-required");
        }
        return List.of();
    }

    private record SchemaFieldDescriptor(
            String name,
            String label,
            String description,
            Set<String> fieldTokens,
            Set<String> labelTokens,
            Set<String> descriptionTokens) {
    }

    private record SemanticAxisAssistantSummary(
            List<String> verifiedAxes,
            List<String> unsupportedAxes) {
    }
}
