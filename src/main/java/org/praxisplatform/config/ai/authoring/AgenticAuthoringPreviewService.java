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
import java.util.Locale;
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
        Optional<AgenticAuthoringPreviewResult> consultativeAnswer =
                previewConsultativeAnswer(effectiveRequest, intentResolution);
        if (consultativeAnswer.isPresent()) {
            return consultativeAnswer.get();
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
                effectiveRequest,
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

    private Optional<AgenticAuthoringPreviewResult> previewConsultativeAnswer(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (!isConsultativeAnswerIntent(intentResolution)) {
            return Optional.empty();
        }
        String assistantMessage = value(intentResolution.assistantMessage()).trim();
        if (assistantMessage.isBlank()) {
            return Optional.empty();
        }
        List<String> warnings = new ArrayList<>(
                intentResolution.warnings() == null ? List.of() : intentResolution.warnings());
        warnings.add("preview-materialization-skipped-consultative-answer");
        List<String> failureCodes = List.of();
        return Optional.of(new AgenticAuthoringPreviewResult(
                true,
                failureCodes,
                List.copyOf(warnings),
                MissingNode.getInstance(),
                MissingNode.getInstance(),
                diagnostics(
                        request,
                        intentResolution,
                        failureCodes,
                        List.copyOf(warnings),
                        MissingNode.getInstance(),
                        MissingNode.getInstance()),
                MissingNode.getInstance(),
                assistantMessage));
    }

    private boolean isConsultativeAnswerIntent(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null || !intentResolution.valid()) {
            return false;
        }
        String operationKind = value(intentResolution.operationKind());
        String changeKind = value(intentResolution.changeKind());
        String artifactKind = value(intentResolution.artifactKind());
        return ("explain".equals(operationKind) || ("explore".equals(operationKind) && "api_catalog".equals(artifactKind)))
                && (changeKind.startsWith("answer_")
                || "api_catalog".equals(artifactKind)
                || "component".equals(artifactKind));
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
        request = withSchemaFieldContext(request, schemaBaseUrl);
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
            if (uiCompositionPlan instanceof ObjectNode uiCompositionPlanObject) {
                normalizeFilterQueryContextBindings(uiCompositionPlanObject, warnings);
            }
            JsonNode semanticMaterialization = semanticMaterialization(planResult, uiCompositionPlan);
            if (containsUnverifiedSemanticAxes(semanticMaterialization)) {
                warnings.add("semantic-axis-schema-verification-pending");
            }
            AgenticAuthoringSemanticMaterializationPolicy.ValidationResult semanticValidation =
                    AgenticAuthoringSemanticMaterializationPolicy.validate(
                            semanticDecision(request.intentResolution()),
                            semanticMaterialization);
            addAllOnce(failureCodes, semanticValidation.failureCodes());
            addAllOnce(warnings, semanticValidation.warnings());
            if (!semanticValidation.valid()) {
                semanticallyValid = false;
            }
            warnings.add("compiled-form-patch-materialized-by-page-builder");
            String fallbackMessage = deterministicPreviewAssistantMessage(
                    request,
                    request.intentResolution(),
                    semanticMaterialization,
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
                            semanticMaterialization,
                            planResult.compiledFormPatch() == null
                                    ? MissingNode.getInstance()
                                    : planResult.compiledFormPatch()),
                    uiCompositionPlan,
                    previewAssistantMessage(
                            request,
                            request.intentResolution(),
                            semanticMaterialization,
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

    private AgenticAuthoringPlanRequest withSchemaFieldContext(
            AgenticAuthoringPlanRequest request,
            String schemaBaseUrl) {
        if (request == null
                || schemaRetrievalService == null
                || !shouldEnrichSchemaFieldsForGenericDashboard(request)
                || hasHostFieldCandidates(request.contextHints())) {
            return request;
        }
        AgenticAuthoringCandidate candidate = request.intentResolution() == null
                ? null
                : request.intentResolution().selectedCandidate();
        AiSchemaContext schemaContext = schemaContext(candidate, MissingNode.getInstance());
        if (schemaContext == null) {
            return request;
        }
        SchemaFetchResult schemaResult = schemaRetrievalService.fetchSchemaResult(schemaContext, schemaBaseUrl);
        if (schemaResult == null || !schemaResult.isSuccess()) {
            return request;
        }
        Map<String, SchemaFieldDescriptor> fields = schemaFields(schemaResult.getSchema());
        if (fields.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ArrayNode schemaFields = contextHints.putArray("schemaFields");
        for (SchemaFieldDescriptor field : fields.values()) {
            ObjectNode node = schemaFields.addObject();
            node.put("fieldName", field.name());
            node.put("label", firstNonBlank(field.label(), field.name()));
            node.put("type", field.type());
            node.put("format", field.format());
            node.put("source", "schemas.filtered");
            if (field.hasEnum()) {
                node.put("semanticKind", "categorical");
            }
        }
        ObjectNode provenance = contextHints.putObject("schemaFieldContext");
        provenance.put("source", "schemas.filtered");
        provenance.put("endpointUrl", value(schemaResult.getEndpointUrl()));
        provenance.put("fieldCount", fields.size());
        return new AgenticAuthoringPlanRequest(
                request.userPrompt(),
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
                contextHints);
    }

    private boolean shouldEnrichSchemaFieldsForGenericDashboard(AgenticAuthoringPlanRequest request) {
        AgenticAuthoringIntentResolutionResult intent = request == null ? null : request.intentResolution();
        if (intent == null || intent.visualizationDecision() != null) {
            return false;
        }
        String artifactKind = value(intent.artifactKind());
        if (!List.of("dashboard", "page", "table").contains(artifactKind)) {
            return false;
        }
        String prompt = value(request.userPrompt());
        return containsAny(prompt,
                "dashboard", "painel", "visao geral", "visao 360", "overview", "360", "grafico", "graficos",
                "chart", "charts", "indicador", "indicadores", "kpi", "kpis");
    }

    private boolean hasHostFieldCandidates(JsonNode contextHints) {
        if (contextHints == null || !contextHints.isObject()) {
            return false;
        }
        for (String field : List.of("schemaFields", "fieldCatalog", "fieldMetadata", "filterableFields", "columns", "properties")) {
            JsonNode value = contextHints.path(field);
            if ((value.isArray() || value.isObject()) && !value.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private JsonNode semanticMaterialization(
            AgenticAuthoringUiCompositionPlanResult planResult,
            JsonNode uiCompositionPlan) {
        if (uiCompositionPlan != null && !uiCompositionPlan.isMissingNode() && !uiCompositionPlan.isNull()) {
            return uiCompositionPlan;
        }
        JsonNode patchPage = planResult == null || planResult.compiledFormPatch() == null
                ? MissingNode.getInstance()
                : planResult.compiledFormPatch().path("patch").path("page");
        return patchPage.isMissingNode() || patchPage.isNull() ? MissingNode.getInstance() : patchPage;
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
        AiSchemaContext schemaContext = schemaContext(candidate, uiCompositionPlan);
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
        AiSchemaContext schemaContext = schemaContext(candidate, uiCompositionPlan);
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
        Map<String, SchemaFieldDescriptor> filterSchemaFields =
                filterSchemaFields(request, schemaBaseUrl).orElse(schemaFields);
        JsonNode copy = uiCompositionPlan == null ? MissingNode.getInstance() : uiCompositionPlan.deepCopy();
        if (copy instanceof ObjectNode objectNode) {
            reconcileSemanticAxesWithSchema(
                    request,
                    objectNode,
                    schemaFields,
                    filterSchemaFields,
                    schemaResult,
                    warnings,
                    allowsSchemaSafeAxisRepair(request));
            return objectNode;
        }
        return copy;
    }

    private AiSchemaContext schemaContext(AgenticAuthoringCandidate candidate, JsonNode uiCompositionPlan) {
        if (candidate == null) {
            return null;
        }
        String materializationReadPath = materializationReadSchemaPath(candidate, uiCompositionPlan);
        if (!materializationReadPath.isBlank()) {
            return AiSchemaContext.builder()
                    .path(materializationReadPath)
                    .operation("post")
                    .schemaType("response")
                    .build();
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

    private Optional<Map<String, SchemaFieldDescriptor>> filterSchemaFields(
            AgenticAuthoringPlanRequest request,
            String schemaBaseUrl) {
        if (schemaRetrievalService == null) {
            return Optional.empty();
        }
        AgenticAuthoringCandidate candidate = request == null || request.intentResolution() == null
                ? null
                : request.intentResolution().selectedCandidate();
        String businessPath = businessResourcePath(firstNonBlank(
                candidate == null ? "" : candidate.resourcePath(),
                candidate == null ? "" : candidate.submitUrl()));
        if (businessPath.isBlank()) {
            return Optional.empty();
        }
        AiSchemaContext filterContext = AiSchemaContext.builder()
                .path(businessPath + "/filter")
                .operation("post")
                .schemaType("request")
                .build();
        SchemaFetchResult filterSchemaResult = schemaRetrievalService.fetchSchemaResult(filterContext, schemaBaseUrl);
        if (filterSchemaResult == null || !filterSchemaResult.isSuccess()) {
            return Optional.empty();
        }
        Map<String, SchemaFieldDescriptor> fields = schemaFields(filterSchemaResult.getSchema());
        return fields.isEmpty() ? Optional.empty() : Optional.of(fields);
    }

    private String materializationReadSchemaPath(
            AgenticAuthoringCandidate candidate,
            JsonNode uiCompositionPlan) {
        if (!containsComponent(uiCompositionPlan, "praxis-chart")) {
            return "";
        }
        String businessPath = businessResourcePath(firstNonBlank(
                candidate == null ? "" : candidate.resourcePath(),
                candidate == null ? "" : candidate.submitUrl()));
        if (businessPath.isBlank()) {
            return "";
        }
        return businessPath + "/filter/cursor";
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
            AgenticAuthoringPlanRequest request,
            ObjectNode uiCompositionPlan,
            Map<String, SchemaFieldDescriptor> schemaFields,
            Map<String, SchemaFieldDescriptor> filterSchemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings,
            boolean allowSchemaSafeAxisRepair) {
        Set<String> contextTokens = authoringContextTokens(request, uiCompositionPlan);
        Set<String> promptTokens = authoringPromptTokens(request);
        Set<String> promptGroupingTokens = authoringPromptGroupingTokens(request);
        Set<String> promptAxisTokens = promptGroupingTokens.isEmpty() ? promptTokens : promptGroupingTokens;
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
            Set<String> assignedChartFields = exactSafeChartFields(widgetArray, schemaFields);
            for (int i = widgetArray.size() - 1; i >= 0; i--) {
                JsonNode widget = widgetArray.get(i);
                alignAuxiliaryWidgetBindings(widget, schemaFields, filterSchemaFields, schemaResult, warnings);
                JsonNode axis = widget.path("inputs").path("config").path("semanticAxis");
                if (!(axis instanceof ObjectNode axisObject)) {
                    continue;
                }
                Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisObject, schemaFields);
                if (schemaField.isPresent() && isSafeGenericGroupByChartField(schemaField.get(), widget)) {
                    Optional<SchemaFieldDescriptor> promptAlignedSchemaField = promptAlignedSafeGroupingField(
                            schemaFields,
                            widget,
                            assignedChartFields,
                            promptAxisTokens,
                            schemaField.get());
                    SchemaFieldDescriptor selectedField = promptAlignedSchemaField.orElse(schemaField.get());
                    String requestedField = axisObject.path("field").asText("");
                    String previousAxisLabel = axisObject.path("label").asText("");
                    alignSemanticAxis(axisObject, selectedField, schemaResult);
                    alignChartBinding(widget, selectedField);
                    alignTemporalChartOperation(widget, selectedField, warnings);
                    alignChartMetricBinding(widget, schemaFields, schemaResult, warnings, contextTokens);
                    if (promptAlignedSchemaField.isPresent()) {
                        alignChartDisplayText(widget, previousAxisLabel, selectedField);
                        alignDiagnosticsAxis(uiCompositionPlan, requestedField, selectedField, schemaResult);
                        addWarningOnce(warnings, "semantic-chart-axis-repaired-with-prompt-aligned-schema-field");
                    }
                    assignedChartFields.add(normalize(selectedField.name()));
                } else {
                    boolean allowGenericAxisRepair = allowsGenericInferredAxisRepair(axisObject);
                    boolean allowStatsAxisRepair = allowsStatsAxisRepair(axisObject, widget);
                    boolean allowContextualAxisRepair = allowsContextualAxisRepair(axisObject, contextTokens);
                    boolean allowStatusLikeAxisRepair = isStatusLikeConcept(semanticAxisTokens(axisObject));
                    Optional<SchemaFieldDescriptor> repairedSchemaField = Optional.empty();
                    boolean repairedFromPrompt = false;
                    if (allowSchemaSafeAxisRepair
                            || allowGenericAxisRepair
                            || allowStatsAxisRepair
                            || allowContextualAxisRepair
                            || allowStatusLikeAxisRepair) {
                        repairedSchemaField = promptAlignedSafeGroupingField(
                                schemaFields,
                                widget,
                                assignedChartFields,
                                promptAxisTokens,
                                schemaField.orElse(null));
                        repairedFromPrompt = repairedSchemaField.isPresent();
                        boolean allowsSchemaScoredFallback = allowSchemaSafeAxisRepair
                                || allowGenericAxisRepair
                                || allowStatsAxisRepair;
                        if (repairedSchemaField.isEmpty() && allowsSchemaScoredFallback) {
                            repairedSchemaField = preferredSafeGroupingField(schemaFields, widget, assignedChartFields, contextTokens);
                        }
                    }
                    if (repairedSchemaField.isPresent()) {
                        SchemaFieldDescriptor repairedField = repairedSchemaField.get();
                        String requestedField = axisObject.path("field").asText("");
                        String previousAxisLabel = axisObject.path("label").asText("");
                        alignSemanticAxis(axisObject, repairedField, schemaResult);
                        alignChartBinding(widget, repairedField);
                        alignTemporalChartOperation(widget, repairedField, warnings);
                        alignChartMetricBinding(widget, schemaFields, schemaResult, warnings, contextTokens);
                        if (repairedFromPrompt) {
                            alignChartDisplayText(widget, previousAxisLabel, repairedField);
                        }
                        alignDiagnosticsAxis(uiCompositionPlan, requestedField, repairedField, schemaResult);
                        assignedChartFields.add(normalize(repairedField.name()));
                        addWarningOnce(warnings, repairedFromPrompt
                                ? "semantic-chart-axis-repaired-with-prompt-aligned-schema-field"
                                : "semantic-chart-axis-repaired-with-schema-field");
                        continue;
                    }
                    if (schemaField.isPresent()) {
                        markSemanticAxisUnsupported(axisObject);
                        markDiagnosticsAxisUnsupported(uiCompositionPlan, schemaField.get().name());
                        addWarningOnce(warnings, "semantic-chart-group-by-unsupported-field-type");
                    } else if (allowSchemaSafeAxisRepair) {
                        markSemanticAxisDropped(axisObject, "schema-safe-axis-repair");
                        markDiagnosticsAxisDropped(uiCompositionPlan, axisObject.path("field").asText(""), "schema-safe-axis-repair");
                        addWarningOnce(warnings, "semantic-chart-axis-dropped-without-safe-schema-field");
                    }
                    widgetArray.remove(i);
                }
            }
            if (!containsUnsupportedSemanticAxes(uiCompositionPlan)) {
                warnings.remove("semantic-axis-schema-verification-unsupported-axis");
            }
            alignDashboardFilterFieldsWithResolvedChartAxes(widgetArray, filterSchemaFields, schemaResult, warnings);
            normalizeFilterQueryContextBindings(uiCompositionPlan, widgetArray, warnings);
            Set<String> widgetKeys = widgetKeys(widgetArray);
            pruneOrphanWidgetBindings(uiCompositionPlan, widgetKeys, warnings);
            pruneOrphanCanvasItems(uiCompositionPlan, widgetKeys, warnings);
        }
    }

    private void alignDashboardFilterFieldsWithResolvedChartAxes(
            ArrayNode widgetArray,
            Map<String, SchemaFieldDescriptor> filterSchemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        LinkedHashSet<String> resolvedAxisFields = new LinkedHashSet<>();
        for (JsonNode widget : widgetArray) {
            if (!"praxis-chart".equals(widget.path("componentId").asText(""))) {
                continue;
            }
            JsonNode axis = widget.path("inputs").path("config").path("semanticAxis");
            String field = axis.path("field").asText("");
            if (field.isBlank()
                    || "unsupported".equals(axis.path("schemaProbeStatus").asText(""))
                    || axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            resolvedAxisFields.add(field);
        }
        if (resolvedAxisFields.isEmpty()) {
            return;
        }
        for (JsonNode widget : widgetArray) {
            if (!"praxis-filter".equals(widget.path("componentId").asText(""))) {
                continue;
            }
            JsonNode inputs = widget.path("inputs");
            if (!(inputs instanceof ObjectNode inputsObject)) {
                continue;
            }
            JsonNode selectedFields = inputsObject.path("selectedFieldIds");
            ArrayNode fieldsArray = selectedFields instanceof ArrayNode existing
                    ? existing
                    : inputsObject.putArray("selectedFieldIds");
            if (!fieldsArray.isEmpty()) {
                continue;
            }
            resolvedAxisFields.forEach(fieldsArray::add);
            alignFilterFields(widget, filterSchemaFields, schemaResult, warnings);
        }
    }

    private void normalizeFilterQueryContextBindings(
            ObjectNode uiCompositionPlan,
            List<String> warnings) {
        JsonNode widgets = uiCompositionPlan.path("widgets");
        if (widgets instanceof ArrayNode widgetArray) {
            normalizeFilterQueryContextBindings(uiCompositionPlan, widgetArray, warnings);
        }
    }

    private void normalizeFilterQueryContextBindings(
            ObjectNode uiCompositionPlan,
            ArrayNode widgetArray,
            List<String> warnings) {
        Set<String> filterKeys = new LinkedHashSet<>();
        if (widgetArray != null) {
            for (JsonNode widget : widgetArray) {
                if (!"praxis-filter".equals(widget.path("componentId").asText(""))) {
                    continue;
                }
                String key = firstNonBlank(widget.path("key").asText(""), widget.path("id").asText(""));
                if (key.isBlank()) {
                    continue;
                }
                filterKeys.add(key);
                if (widget instanceof ObjectNode widgetObject) {
                    ObjectNode outputs = widgetObject.path("outputs") instanceof ObjectNode existing
                            ? existing
                            : widgetObject.putObject("outputs");
                    outputs.put("change", "emit");
                    outputs.put("requestSearch", "emit");
                    outputs.put("clear", "emit");
                }
            }
        }
        if (filterKeys.isEmpty()) {
            return;
        }
        JsonNode bindings = uiCompositionPlan.path("bindings");
        if (!(bindings instanceof ArrayNode bindingsArray)) {
            return;
        }
        for (JsonNode binding : bindingsArray) {
            if (!(binding instanceof ObjectNode bindingObject)) {
                continue;
            }
            JsonNode from = binding.path("from");
            JsonNode to = binding.path("to");
            String fromWidget = bindingWidgetKey(from);
            String fromPort = bindingPort(from);
            String toPort = bindingPort(to);
            if (!filterKeys.contains(fromWidget)
                    || !Set.of("change", "requestSearch", "submit").contains(fromPort)
                    || !"queryContext".equals(toPort)) {
                continue;
            }
            ObjectNode transform = bindingObject.putObject("transform");
            transform.put("kind", "template");
            ObjectNode template = transform.putObject("template");
            template.put("filters", "${payload}");
            addWarningOnce(warnings, "ui-composition-plan-filter-query-context-normalized");
        }
    }

    private Set<String> exactSafeChartFields(
            ArrayNode widgetArray,
            Map<String, SchemaFieldDescriptor> schemaFields) {
        Set<String> fields = new LinkedHashSet<>();
        if (widgetArray == null) {
            return fields;
        }
        for (JsonNode widget : widgetArray) {
            JsonNode axis = widget.path("inputs").path("config").path("semanticAxis");
            if (!(axis instanceof ObjectNode axisObject)) {
                continue;
            }
            Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisObject, schemaFields);
            if (schemaField.isPresent()
                    && isSafeGenericGroupByChartField(schemaField.get(), widget)) {
                fields.add(normalize(schemaField.get().name()));
            }
        }
        return fields;
    }

    private boolean allowsSchemaSafeAxisRepair(AgenticAuthoringPlanRequest request) {
        String prompt = normalize(String.join(" ",
                request == null ? "" : request.userPrompt(),
                request == null || request.intentResolution() == null ? "" : request.intentResolution().effectivePrompt()));
        if (prompt.isBlank()) {
            return false;
        }
        boolean asksForSafeAxis = containsAny(prompt,
                "eixo seguro",
                "eixos seguros",
                "safe axis",
                "safe axes",
                "campo seguro",
                "campos seguros",
                "campos suportados",
                "eixos suportados");
        boolean asksForSchemaGrounding = containsAny(prompt,
                "schema",
                "esquema",
                "confirmado",
                "confirmados",
                "verificado",
                "verificados",
                "suportado",
                "suportados");
        return asksForSafeAxis && asksForSchemaGrounding;
    }

    private boolean allowsGenericInferredAxisRepair(ObjectNode semanticAxis) {
        if (semanticAxis == null) {
            return false;
        }
        String provenance = semanticAxis.path("provenance").asText("");
        String probeStatus = semanticAxis.path("schemaProbeStatus").asText("");
        return "generic-dashboard-field-inference".equals(provenance)
                && (probeStatus.isBlank() || "pending".equals(probeStatus));
    }

    private boolean allowsStatsAxisRepair(ObjectNode semanticAxis, JsonNode widget) {
        if (semanticAxis == null || widget == null) {
            return false;
        }
        String statsOperation = statsOperation(widget);
        if (statsOperation.isBlank() || "group-by".equalsIgnoreCase(statsOperation)) {
            return false;
        }
        String field = normalize(semanticAxis.path("field").asText(""));
        String concept = normalize(semanticAxis.path("concept").asText(""));
        String provenance = normalize(semanticAxis.path("provenance").asText(""));
        String probeStatus = normalize(semanticAxis.path("schemaProbeStatus").asText(""));
        return (field.isBlank() || "unresolved".equals(field))
                && (concept.isBlank() || "unresolved".equals(concept))
                && (probeStatus.isBlank() || "pending".equals(probeStatus))
                && (provenance.isBlank()
                || "llm-authored-semantic-axis".equals(provenance)
                || "schema-grounding-required".equals(provenance));
    }

    private boolean allowsContextualAxisRepair(ObjectNode semanticAxis, Set<String> contextTokens) {
        if (semanticAxis == null || contextTokens == null || contextTokens.isEmpty()) {
            return false;
        }
        String field = normalize(semanticAxis.path("field").asText(""));
        String concept = normalize(semanticAxis.path("concept").asText(""));
        String provenance = normalize(semanticAxis.path("provenance").asText(""));
        String probeStatus = normalize(semanticAxis.path("schemaProbeStatus").asText(""));
        return (field.isBlank() || "unresolved".equals(field))
                && (concept.isBlank() || "unresolved".equals(concept))
                && (probeStatus.isBlank() || "pending".equals(probeStatus))
                && ("schema-grounding-required".equals(provenance)
                || "llm-authored-semantic-axis".equals(provenance)
                || provenance.isBlank());
    }

    private boolean isSafeGenericGroupByChartField(SchemaFieldDescriptor field, JsonNode widget) {
        if (field == null) {
            return false;
        }
        String statsOperation = statsOperation(widget);
        if (!statsOperation.isBlank() && !"group-by".equalsIgnoreCase(statsOperation)) {
            return true;
        }
        if (isTemporalChartRequest(widget) && isTemporalSchemaField(field)) {
            return true;
        }
        String type = normalize(field.type());
        String format = normalize(field.format());
        if (field.hasEnum()) {
            return true;
        }
        if (containsAnyToken(field.fieldTokens(), "data", "date")
                || containsAnyToken(field.labelTokens(), "data", "date")) {
            return false;
        }
        if (type.isBlank()) {
            return true;
        }
        if ("integer".equals(type) || "long".equals(type)) {
            return containsAnyToken(field.fieldTokens(), "mes", "ano", "competencia", "periodo")
                    || containsAnyToken(field.labelTokens(), "mes", "ano", "competencia", "periodo");
        }
        if ("string".equals(type)) {
            return format.isBlank() || "text".equals(format);
        }
        return "boolean".equals(type);
    }

    private boolean isTemporalChartRequest(JsonNode widget) {
        JsonNode config = widget.path("inputs").path("config");
        String chartType = normalize(config.path("type").asText(""));
        String xType = normalize(config.path("axes").path("x").path("type").asText(""));
        String semanticConcept = normalize(config.path("semanticAxis").path("concept").asText(""));
        return "line".equals(chartType)
                || "area".equals(chartType)
                || chartType.contains("line")
                || chartType.contains("area")
                || chartType.contains("time")
                || chartType.contains("serie")
                || chartType.contains("series")
                || "time".equals(semanticConcept)
                || "temporal".equals(semanticConcept)
                || semanticConcept.contains("time")
                || semanticConcept.contains("temporal")
                || semanticConcept.contains("tempo")
                || semanticConcept.contains("mensal")
                || semanticConcept.contains("mes")
                || semanticConcept.contains("data")
                || semanticConcept.contains("date")
                || "time".equals(xType);
    }

    private boolean isTemporalSchemaField(SchemaFieldDescriptor field) {
        if (field == null) {
            return false;
        }
        String type = normalize(field.type());
        String format = normalize(field.format());
        if ("date".equals(type) || "datetime".equals(type) || "date".equals(format)
                || "date-time".equals(format) || "datetime".equals(format)) {
            return true;
        }
        Set<String> tokens = new LinkedHashSet<>();
        tokens.addAll(field.fieldTokens());
        tokens.addAll(field.labelTokens());
        tokens.addAll(field.descriptionTokens());
        return containsAnyToken(tokens, "data", "date", "temporal", "tempo", "ocorrido", "competencia", "periodo");
    }

    private Optional<SchemaFieldDescriptor> preferredSafeGroupingField(
            Map<String, SchemaFieldDescriptor> schemaFields,
            JsonNode widget,
            Set<String> assignedChartFields,
            Set<String> contextTokens) {
        if ("timeseries".equalsIgnoreCase(statsOperation(widget))) {
            return preferredTemporalField(schemaFields, assignedChartFields);
        }
        SchemaFieldDescriptor best = null;
        int bestScore = Integer.MIN_VALUE;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            if (assignedChartFields != null && assignedChartFields.contains(normalize(field.name()))) {
                continue;
            }
            if (!isSafeGenericGroupByChartField(field, widget)) {
                continue;
            }
            int score = groupingFieldScore(field) + (semanticMatchScore(contextTokens, field) * 40);
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        return best == null || bestScore <= 0 ? Optional.empty() : Optional.of(best);
    }

    private Optional<SchemaFieldDescriptor> promptAlignedSafeGroupingField(
            Map<String, SchemaFieldDescriptor> schemaFields,
            JsonNode widget,
            Set<String> assignedChartFields,
            Set<String> promptTokens,
            SchemaFieldDescriptor currentField) {
        if (promptTokens == null || promptTokens.isEmpty()) {
            return Optional.empty();
        }
        int currentScore = currentField == null ? 0 : semanticMatchScore(promptTokens, currentField);
        SchemaFieldDescriptor best = null;
        int bestScore = currentScore;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            if (currentField != null && normalize(field.name()).equals(normalize(currentField.name()))) {
                continue;
            }
            if (assignedChartFields != null && assignedChartFields.contains(normalize(field.name()))) {
                continue;
            }
            if (!isSafeGenericGroupByChartField(field, widget)) {
                continue;
            }
            int score = semanticMatchScore(promptTokens, field);
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        if (best != null && bestScore >= 3 && (currentField == null || bestScore >= currentScore + 3)) {
            return Optional.of(best);
        }
        return statusLikeBooleanGroupingField(schemaFields, assignedChartFields, promptTokens, currentField);
    }

    private Optional<SchemaFieldDescriptor> statusLikeBooleanGroupingField(
            Map<String, SchemaFieldDescriptor> schemaFields,
            Set<String> assignedChartFields,
            Set<String> promptTokens,
            SchemaFieldDescriptor currentField) {
        if (!isStatusLikeConcept(promptTokens)) {
            return Optional.empty();
        }
        List<SchemaFieldDescriptor> candidates = schemaFields.values().stream()
                .filter(field -> currentField == null || !normalize(field.name()).equals(normalize(currentField.name())))
                .filter(field -> assignedChartFields == null || !assignedChartFields.contains(normalize(field.name())))
                .filter(field -> "boolean".equals(normalize(field.type())))
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    private Optional<SchemaFieldDescriptor> preferredTemporalField(
            Map<String, SchemaFieldDescriptor> schemaFields,
            Set<String> assignedChartFields) {
        SchemaFieldDescriptor best = null;
        int bestScore = Integer.MIN_VALUE;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            if (assignedChartFields != null && assignedChartFields.contains(normalize(field.name()))) {
                continue;
            }
            int score = temporalFieldScore(field);
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        return best == null || bestScore <= 0 ? Optional.empty() : Optional.of(best);
    }

    private int temporalFieldScore(SchemaFieldDescriptor field) {
        Set<String> tokens = new LinkedHashSet<>();
        tokens.addAll(field.fieldTokens());
        tokens.addAll(field.labelTokens());
        tokens.addAll(field.descriptionTokens());
        int score = 0;
        String type = normalize(field.type());
        String format = normalize(field.format());
        if ("date".equals(format) || "date-time".equals(format) || "datetime".equals(format)) {
            score += 200;
        }
        if ("date".equals(type) || "datetime".equals(type)) {
            score += 160;
        }
        if (containsAnyToken(tokens,
                "data",
                "date",
                "hora",
                "temporal",
                "tempo",
                "ocorrido",
                "ocorrencia",
                "competencia",
                "periodo",
                "mes",
                "ano")) {
            score += 50;
        }
        if (containsAnyToken(tokens, "id", "uuid", "codigo")) {
            score -= 120;
        }
        if (isNumericSchemaField(field)) {
            score -= 120;
        }
        return score;
    }

    private void alignDiagnosticsAxis(
            ObjectNode uiCompositionPlan,
            String requestedField,
            SchemaFieldDescriptor field,
            SchemaFetchResult schemaResult) {
        if (uiCompositionPlan == null || field == null) {
            return;
        }
        JsonNode axes = uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return;
        }
        for (JsonNode axis : axes) {
            if (axis instanceof ObjectNode axisObject
                    && normalize(requestedField).equals(normalize(axisObject.path("field").asText("")))) {
                alignSemanticAxis(axisObject, field, schemaResult);
                axisObject.put("materialized", true);
                return;
            }
        }
    }

    private void markDiagnosticsAxisUnsupported(ObjectNode uiCompositionPlan, String fieldName) {
        if (uiCompositionPlan == null || value(fieldName).isBlank()) {
            return;
        }
        JsonNode axes = uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return;
        }
        for (JsonNode axis : axes) {
            if (axis instanceof ObjectNode axisObject
                    && normalize(fieldName).equals(normalize(axisObject.path("field").asText("")))) {
                markSemanticAxisUnsupported(axisObject);
            }
        }
    }

    private void markDiagnosticsAxisDropped(ObjectNode uiCompositionPlan, String fieldName, String reason) {
        if (uiCompositionPlan == null || value(fieldName).isBlank()) {
            return;
        }
        JsonNode axes = uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return;
        }
        for (JsonNode axis : axes) {
            if (axis instanceof ObjectNode axisObject
                    && normalize(fieldName).equals(normalize(axisObject.path("field").asText("")))) {
                markSemanticAxisDropped(axisObject, reason);
            }
        }
    }

    private void pruneOrphanWidgetBindings(
            ObjectNode uiCompositionPlan,
            Set<String> widgetKeys,
            List<String> warnings) {
        JsonNode bindings = uiCompositionPlan.path("bindings");
        if (!(bindings instanceof ArrayNode bindingsArray)) {
            return;
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

    private Set<String> widgetKeys(ArrayNode widgets) {
        Set<String> widgetKeys = new LinkedHashSet<>();
        if (widgets == null) {
            return widgetKeys;
        }
        for (JsonNode widget : widgets) {
            String key = firstNonBlank(
                    widget.path("key").asText(""),
                    widget.path("id").asText(""));
            if (!key.isBlank()) {
                widgetKeys.add(key);
            }
        }
        return widgetKeys;
    }

    private void pruneOrphanCanvasItems(
            ObjectNode uiCompositionPlan,
            Set<String> widgetKeys,
            List<String> warnings) {
        JsonNode items = uiCompositionPlan.path("canvas").path("items");
        if (!(items instanceof ObjectNode itemsObject)) {
            return;
        }
        List<String> orphanKeys = new ArrayList<>();
        itemsObject.fieldNames().forEachRemaining(key -> {
            if (!widgetKeys.contains(key)) {
                orphanKeys.add(key);
            }
        });
        for (String orphanKey : orphanKeys) {
            itemsObject.remove(orphanKey);
            addWarningOnce(warnings, "ui-composition-plan-orphan-canvas-item-removed");
        }
    }

    private String bindingWidgetKey(JsonNode endpoint) {
        return firstNonBlank(
                endpoint.path("widgetKey").asText(""),
                firstNonBlank(
                        endpoint.path("widget").asText(""),
                        endpoint.path("ref").path("widget").asText("")));
    }

    private String bindingPort(JsonNode endpoint) {
        return firstNonBlank(
                endpoint.path("port").asText(""),
                endpoint.path("ref").path("port").asText(""));
    }

    private void alignAuxiliaryWidgetBindings(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> schemaFields,
            Map<String, SchemaFieldDescriptor> filterSchemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        if (widget == null || !widget.isObject()) {
            return;
        }
        String componentId = widget.path("componentId").asText("");
        if ("praxis-filter".equals(componentId)) {
            alignFilterFields(widget, filterSchemaFields, schemaResult, warnings);
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
            Optional<SchemaFieldDescriptor> schemaField = Optional.ofNullable(
                    schemaFields.get(normalize(requestedField)));
            if (schemaField.isPresent()) {
                SchemaFieldDescriptor selectedField = preferredFilterInputField(schemaField.get(), schemaFields)
                        .orElse(schemaField.get());
                fieldsArray.set(i, objectMapper.getNodeFactory().textNode(selectedField.name()));
                if (!normalize(selectedField.name()).equals(normalize(schemaField.get().name()))) {
                    addWarningOnce(warnings, "semantic-filter-schema-field-replaced-with-selectable-field");
                }
            } else {
                Optional<SchemaFieldDescriptor> selectedField = preferredFilterInputField(
                        syntheticRequestedFilterField(requestedField),
                        schemaFields);
                if (selectedField.isPresent()) {
                    fieldsArray.set(i, objectMapper.getNodeFactory().textNode(selectedField.get().name()));
                    addWarningOnce(warnings, "semantic-filter-schema-field-replaced-with-selectable-field");
                } else {
                    fieldsArray.remove(i);
                    addWarningOnce(warnings, "semantic-filter-schema-verification-unsupported-field");
                }
            }
        }
        LinkedHashSet<String> uniqueFields = new LinkedHashSet<>();
        boolean removedDuplicate = false;
        for (JsonNode field : fieldsArray) {
            String name = field.asText("");
            if (name.isBlank()) {
                continue;
            }
            removedDuplicate = !uniqueFields.add(name) || removedDuplicate;
        }
        if (removedDuplicate) {
            fieldsArray.removeAll();
            uniqueFields.forEach(fieldsArray::add);
            addWarningOnce(warnings, "semantic-filter-schema-field-deduplicated");
        }
    }

    private SchemaFieldDescriptor syntheticRequestedFilterField(String requestedField) {
        String field = value(requestedField);
        return new SchemaFieldDescriptor(
                field,
                field,
                "",
                "",
                "",
                false,
                "",
                false,
                "",
                tokens(field),
                tokens(field),
                Set.of());
    }

    private Optional<SchemaFieldDescriptor> preferredFilterInputField(
            SchemaFieldDescriptor requestedField,
            Map<String, SchemaFieldDescriptor> schemaFields) {
        if (requestedField == null || schemaFields == null || schemaFields.isEmpty()) {
            return Optional.empty();
        }
        if (isSelectableFilterInputField(requestedField)) {
            return Optional.of(requestedField);
        }
        SchemaFieldDescriptor best = null;
        int bestScore = 0;
        for (SchemaFieldDescriptor candidate : schemaFields.values()) {
            if (candidate == null || normalize(candidate.name()).equals(normalize(requestedField.name()))) {
                continue;
            }
            if (!isSelectableFilterInputField(candidate)) {
                continue;
            }
            int score = filterInputSemanticMatchScore(requestedField, candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best != null && bestScore >= 4 ? Optional.of(best) : Optional.empty();
    }

    private boolean isSelectableFilterInputField(SchemaFieldDescriptor field) {
        if (field == null) {
            return false;
        }
        String controlType = normalize(field.controlType());
        return field.multiple()
                || field.hasEnum()
                || !field.endpoint().isBlank()
                || containsAny(controlType, "select", "autocomplete", "radio");
    }

    private int filterInputSemanticMatchScore(
            SchemaFieldDescriptor requestedField,
            SchemaFieldDescriptor candidate) {
        Set<String> requestedTokens = new LinkedHashSet<>();
        requestedTokens.addAll(requestedField.fieldTokens());
        requestedTokens.addAll(requestedField.labelTokens());
        requestedTokens.addAll(requestedField.descriptionTokens());
        int score = semanticMatchScore(requestedTokens, candidate);
        if (score <= 0) {
            return 0;
        }
        if (candidate.multiple()) {
            score += 2;
        }
        if (!candidate.endpoint().isBlank()) {
            score += 2;
        }
        if (containsAny(normalize(candidate.controlType()), "select", "autocomplete")) {
            score += 1;
        }
        return score;
    }

    private void alignKpiFields(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        JsonNode kpis = widget.path("inputs").path("kpis");
        if (kpis.isMissingNode()) {
            kpis = widget.path("inputs").path("document").path("kpis");
        }
        if (kpis.isMissingNode()) {
            kpis = widget.path("inputs").path("document").path("nodes").path(0).path("items");
        }
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
                semanticAxis.put("label", field.label());
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

    private void markSemanticAxisDropped(ObjectNode semanticAxis, String reason) {
        markSemanticAxisUnsupported(semanticAxis);
        semanticAxis.put("materialized", false);
        semanticAxis.put("materializationReason", valueOrDefault(reason, "not-materialized"));
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

    private void alignChartDisplayText(JsonNode widget, String previousLabel, SchemaFieldDescriptor field) {
        ObjectNode config = widget.path("inputs").path("config") instanceof ObjectNode objectNode
                ? objectNode
                : null;
        if (config == null || field == null) {
            return;
        }
        String replacement = firstNonBlank(field.label(), field.name());
        replaceConfigText(config, "title", previousLabel, replacement);
        replaceConfigText(config, "subtitle", previousLabel, replacement);
    }

    private void replaceConfigText(ObjectNode config, String property, String previousLabel, String replacement) {
        String current = config.path(property).asText("");
        if (current.isBlank() || previousLabel == null || previousLabel.isBlank() || replacement == null || replacement.isBlank()) {
            return;
        }
        String updated = current.replace(previousLabel, replacement);
        if (!updated.equals(current)) {
            config.put(property, updated);
        }
    }

    private void alignTemporalChartOperation(
            JsonNode widget,
            SchemaFieldDescriptor field,
            List<String> warnings) {
        if (!isTemporalChartRequest(widget) || !isTemporalSchemaField(field)) {
            return;
        }
        ObjectNode config = widget.path("inputs").path("config") instanceof ObjectNode objectNode
                ? objectNode
                : null;
        if (config == null) {
            return;
        }
        JsonNode xAxis = config.path("axes").path("x");
        if (xAxis instanceof ObjectNode xAxisObject) {
            xAxisObject.put("type", "time");
        }
        JsonNode dataSource = config.path("dataSource");
        JsonNode query = dataSource.path("query");
        if (query instanceof ObjectNode queryObject) {
            queryObject.put("statsOperation", "timeseries");
            String resourcePath = businessResourcePath(dataSource.path("resourcePath").asText(""));
            if (!resourcePath.isBlank()) {
                queryObject.put("statsPath", resourcePath + "/stats/timeseries");
            }
            queryObject.put("granularity", "month");
            queryObject.remove("orderBy");
        }
        JsonNode statsRequest = query.path("statsRequest");
        if (statsRequest instanceof ObjectNode statsRequestObject) {
            statsRequestObject.put("granularity", "MONTH");
            statsRequestObject.put("fillGaps", false);
            statsRequestObject.remove("orderBy");
        }
        if (dataSource instanceof ObjectNode dataSourceObject) {
            dataSourceObject.put("statsEndpointInference", "canonical-resource-stats-timeseries");
        }
        addWarningOnce(warnings, "semantic-chart-temporal-operation-repaired-with-schema-field");
    }

    private void alignChartMetricBinding(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings,
            Set<String> contextTokens) {
        ObjectNode config = widget.path("inputs").path("config") instanceof ObjectNode objectNode
                ? objectNode
                : null;
        if (config == null) {
            return;
        }
        inferMetricFieldFromContext(config, schemaFields, contextTokens, warnings);
        JsonNode series = config.path("series");
        if (series.isArray()) {
            for (JsonNode item : series) {
                if (!(item instanceof ObjectNode seriesObject)) {
                    continue;
                }
                JsonNode metric = seriesObject.path("metric");
                if (metric instanceof ObjectNode metricObject) {
                    alignMetricField(metricObject, schemaFields, schemaResult, warnings);
                }
            }
        }
        JsonNode metrics = config.path("dataSource").path("query").path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (metric instanceof ObjectNode metricObject) {
                    alignMetricField(metricObject, schemaFields, schemaResult, warnings);
                }
            }
        }
        JsonNode statsMetric = config.path("dataSource").path("query").path("statsRequest").path("metric");
        if (statsMetric instanceof ObjectNode metricObject) {
            alignMetricField(metricObject, schemaFields, schemaResult, warnings);
        }
    }

    private void inferMetricFieldFromContext(
            ObjectNode config,
            Map<String, SchemaFieldDescriptor> schemaFields,
            Set<String> contextTokens,
            List<String> warnings) {
        if (config == null || schemaFields == null || schemaFields.isEmpty()
                || contextTokens == null || contextTokens.isEmpty()
                || !containsAnyToken(contextTokens, "soma", "somar", "somando", "sum", "totalizar")) {
            return;
        }
        Optional<SchemaFieldDescriptor> metricField = preferredNumericMetricField(schemaFields, contextTokens);
        if (metricField.isEmpty()) {
            return;
        }
        SchemaFieldDescriptor field = metricField.get();
        JsonNode series = config.path("series");
        if (series.isArray()) {
            for (JsonNode item : series) {
                JsonNode metric = item.path("metric");
                if (metric instanceof ObjectNode metricObject && metricNeedsInference(metricObject)) {
                    metricObject.put("field", field.name());
                    metricObject.put("aggregation", "sum");
                }
            }
        }
        JsonNode metrics = config.path("dataSource").path("query").path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (metric instanceof ObjectNode metricObject && (metricObject.path("field").asText("").isBlank()
                        || "total".equals(normalize(metricObject.path("field").asText(""))))) {
                    metricObject.put("field", field.name());
                    metricObject.put("aggregation", "sum");
                    metricObject.put("alias", field.name());
                }
            }
        }
        JsonNode statsMetric = config.path("dataSource").path("query").path("statsRequest").path("metric");
        if (statsMetric instanceof ObjectNode metricObject) {
            metricObject.put("field", field.name());
            metricObject.put("operation", "SUM");
            metricObject.put("alias", field.name());
        }
        addWarningOnce(warnings, "semantic-chart-metric-inferred-from-schema-context");
    }

    private Optional<SchemaFieldDescriptor> preferredNumericMetricField(
            Map<String, SchemaFieldDescriptor> schemaFields,
            Set<String> contextTokens) {
        SchemaFieldDescriptor best = null;
        int bestScore = 0;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            if (!isNumericSchemaField(field)) {
                continue;
            }
            int score = semanticMatchScore(contextTokens, field);
            Set<String> fieldTokens = new LinkedHashSet<>();
            fieldTokens.addAll(field.fieldTokens());
            fieldTokens.addAll(field.labelTokens());
            fieldTokens.addAll(field.descriptionTokens());
            if (containsAnyToken(fieldTokens, "id", "uuid", "codigo", "cod")) {
                score -= 12;
            }
            if (containsAnyToken(fieldTokens, "valor", "salario", "liquido", "bruto", "amount", "total")) {
                score += 4;
            }
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        return bestScore >= 2 ? Optional.of(best) : Optional.empty();
    }

    private boolean metricNeedsInference(ObjectNode metric) {
        if (metric == null) {
            return false;
        }
        String field = metric.path("field").asText("");
        return field.isBlank() || "total".equals(normalize(field));
    }

    private void alignMetricField(
            ObjectNode metric,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        String requestedField = metric.path("field").asText("");
        if (requestedField.isBlank() || "total".equals(normalize(requestedField))) {
            return;
        }
        Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisProbe(requestedField), schemaFields);
        if (schemaField.isEmpty()) {
            metric.put("schemaVerified", false);
            metric.put("schemaProbeStatus", "unsupported");
            addWarningOnce(warnings, "semantic-chart-metric-schema-verification-unsupported-field");
            return;
        }
        SchemaFieldDescriptor field = schemaField.get();
        if (!normalize(requestedField).equals(normalize(field.name()))) {
            metric.put("requestedField", requestedField);
            metric.put("field", field.name());
            String alias = metric.path("alias").asText("");
            if (!alias.isBlank() && normalize(alias).equals(normalize(requestedField))) {
                metric.put("alias", field.name());
            }
        }
        if (!field.label().isBlank() && metric.path("label").asText("").isBlank()) {
            metric.put("label", field.label());
        }
        repairCountAggregationForNumericMetric(metric, field, warnings);
        metric.put("schemaVerified", true);
        metric.put("schemaProbeStatus", "verified");
        metric.put("schemaEvidenceSource", "schemas.filtered");
        metric.put("schemaEvidenceUrl", schemaResult == null ? "" : value(schemaResult.getEndpointUrl()));
    }

    private void repairCountAggregationForNumericMetric(
            ObjectNode metric,
            SchemaFieldDescriptor field,
            List<String> warnings) {
        if (field == null || !isNumericSchemaField(field)) {
            return;
        }
        String aggregation = metric.path("aggregation").asText("");
        String operation = metric.path("operation").asText("");
        if ("count".equals(normalize(aggregation))) {
            metric.put("aggregation", "sum");
            addWarningOnce(warnings, "semantic-chart-metric-aggregation-repaired-with-schema-field");
        }
        if ("count".equals(normalize(operation))) {
            metric.put("operation", "SUM");
            addWarningOnce(warnings, "semantic-chart-metric-aggregation-repaired-with-schema-field");
        }
    }

    private boolean isNumericSchemaField(SchemaFieldDescriptor field) {
        return field != null && Set.of("number", "integer").contains(normalize(field.type()));
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
        if (containsAnyToken(tokens, "mes", "competencia", "periodo")) {
            score += 80;
        }
        if (containsAnyToken(tokens, "ano", "exercicio")) {
            score += 75;
        }
        if (containsAnyToken(tokens, "data", "created", "criacao")) {
            score += 60;
        }
        if (containsAnyToken(tokens, "id", "uuid", "codigo")) {
            score -= 80;
        }
        if (containsAnyToken(tokens,
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

    private String statsOperation(JsonNode widget) {
        return value(widget.path("inputs").path("config").path("dataSource").path("query").path("statsOperation").asText(""));
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
        if (score > 0 && containsAnyToken(field.fieldTokens(), "id", "uuid", "codigo")) {
            score -= 1;
        }
        if (score > 0 && containsAnyToken(field.fieldTokens(), "nome", "name", "label", "descricao", "description")) {
            score += 1;
        }
        return score;
    }

    private boolean isStatusLikeConcept(Set<String> tokens) {
        return containsAnyToken(tokens,
                "status",
                "situacao",
                "estado",
                "state",
                "active",
                "inactive",
                "ativo",
                "inativo",
                "andamento");
    }

    private Set<String> semanticAxisTokens(ObjectNode semanticAxis) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, semanticAxis.path("concept").asText(""));
        addTokens(tokens, semanticAxis.path("field").asText(""));
        addTokens(tokens, semanticAxis.path("label").asText(""));
        return tokens;
    }

    private Set<String> authoringContextTokens(
            AgenticAuthoringPlanRequest request,
            JsonNode uiCompositionPlan) {
        Set<String> tokens = new LinkedHashSet<>();
        if (request != null) {
            addPromptTokens(tokens, request);
            AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
            if (intent != null) {
                AgenticAuthoringVisualizationDecision visualization = intent.visualizationDecision();
                if (visualization != null) {
                    addTokens(tokens, visualization.intent());
                    addTokens(tokens, visualization.layoutKind());
                    addTokens(tokens, visualization.provenance());
                    if (visualization.axes() != null) {
                        for (AgenticAuthoringVisualizationAxisDecision axis : visualization.axes()) {
                            addTokens(tokens, axis.concept());
                            addTokens(tokens, axis.field());
                            addTokens(tokens, axis.label());
                            addTokens(tokens, axis.metricField());
                            addTokens(tokens, axis.metricLabel());
                        }
                    }
                }
            }
        }
        JsonNode diagnostics = uiCompositionPlan == null ? MissingNode.getInstance() : uiCompositionPlan.path("diagnostics");
        addTokens(tokens, diagnostics.path("visualizationDecisionIntent").asText(""));
        addTokens(tokens, diagnostics.path("visualizationDecisionProvenance").asText(""));
        return tokens;
    }

    private Set<String> authoringPromptTokens(AgenticAuthoringPlanRequest request) {
        Set<String> tokens = new LinkedHashSet<>();
        addPromptTokens(tokens, request);
        return tokens;
    }

    private Set<String> authoringPromptGroupingTokens(AgenticAuthoringPlanRequest request) {
        Set<String> tokens = new LinkedHashSet<>();
        String prompt = normalize(String.join(" ",
                request == null ? "" : request.userPrompt(),
                request == null || request.intentResolution() == null ? "" : request.intentResolution().effectivePrompt()))
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (prompt.isBlank()) {
            return tokens;
        }
        String candidate = textAfterLastPromptMarker(prompt, " por ");
        if (candidate.isBlank()) {
            candidate = textAfterLastPromptMarker(prompt, " by ");
        }
        if (candidate.isBlank()) {
            return tokens;
        }
        int stop = firstPromptStopIndex(candidate,
                " somando ",
                " soma ",
                " somar ",
                " agregando ",
                " usando ",
                " use ",
                " usar ",
                " com ",
                " nao ",
                " sem ",
                " without ");
        addTokens(tokens, stop >= 0 ? candidate.substring(0, stop) : candidate);
        return tokens;
    }

    private String textAfterLastPromptMarker(String prompt, String marker) {
        int index = prompt.lastIndexOf(marker);
        return index < 0 ? "" : prompt.substring(index + marker.length());
    }

    private int firstPromptStopIndex(String value, String... stops) {
        int best = -1;
        for (String stop : stops) {
            int index = value.indexOf(stop);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private void addPromptTokens(Set<String> tokens, AgenticAuthoringPlanRequest request) {
        if (request == null) {
            return;
        }
        addTokens(tokens, request.userPrompt());
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        if (intent != null) {
            addTokens(tokens, intent.effectivePrompt());
        }
    }

    private SchemaFieldDescriptor schemaFieldDescriptor(String field, JsonNode property) {
        JsonNode xUi = property.path("x-ui");
        if ((xUi.isMissingNode() || xUi.isNull()) && property.path("items").path("x-ui").isObject()) {
            xUi = property.path("items").path("x-ui");
        }
        String label = firstNonBlank(
                xUi.path("label").asText(""),
                property.path("title").asText(""));
        String description = firstNonBlank(
                property.path("description").asText(""),
                xUi.path("helpText").asText(""));
        return new SchemaFieldDescriptor(
                field,
                label,
                description,
                property.path("type").asText(""),
                property.path("format").asText(""),
                property.path("enum").isArray() && !property.path("enum").isEmpty(),
                xUi.path("controlType").asText(""),
                xUi.path("multiple").asBoolean(false),
                xUi.path("endpoint").asText(""),
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
        String tokenizable = value == null
                ? ""
                : value.replaceAll("([a-z])([A-Z])", "$1 $2");
        for (String token : normalize(tokenizable).replaceAll("[^a-z0-9]+", " ").split("\\s+")) {
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

    private boolean containsUnsupportedSemanticAxes(JsonNode uiCompositionPlan) {
        JsonNode axes = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return false;
        }
        for (JsonNode axis : axes) {
            if ("unsupported".equals(axis.path("schemaProbeStatus").asText(""))) {
                return true;
            }
        }
        return false;
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
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan,
            boolean valid,
            List<String> failureCodes) {
        if (!valid) {
            if (failureCodes != null && failureCodes.contains("semantic-preview-chart-required")) {
                return "Encontrei a fonte certa, mas ainda nao consegui montar o grafico pedido com seguranca. Vou deixar a proposta em revisao e ajustar a visualizacao antes de voce salvar.";
            }
            if (failureCodes != null && failureCodes.contains("semantic-preview-operational-dashboard-required")) {
                return "Encontrei a fonte certa, mas a tela ainda nao virou um dashboard operacional completo. Vou manter em revisao ate incluir indicadores, graficos e detalhe conectado de forma coerente.";
            }
            if (failureCodes != null && failureCodes.contains("semantic-preview-axis-schema-verification-required")) {
                return "Encontrei a base de dados, mas ainda nao consegui confirmar campos seguros para alguns graficos. Vou manter a proposta em revisao e sugerir eixos compativeis antes de aplicar.";
            }
            if (failureCodes != null
                    && failureCodes.contains(AgenticAuthoringSemanticMaterializationPolicy.PRIMARY_COMPONENT_REQUIRED_FAILURE)) {
                return "Entendi o componente que voce pediu, mas a pre-visualizacao ainda nao montou esse componente com seguranca. Vou manter em revisao e ajustar a tela antes de aplicar.";
            }
            if (failureCodes != null && failureCodes.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(code -> code.equals("semantic-decision-required")
                            || code.startsWith("semantic-decision-review-required"))) {
                return governedReviewAssistantMessage(intentResolution, uiCompositionPlan);
            }
            return "Encontrei a fonte de dados, mas o plano gerado usou propriedades incompativeis com o componente de tabela. Vou ajustar para usar apenas os campos suportados.";
        }
        AgenticAuthoringCandidate candidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        if (candidate == null || value(candidate.resourcePath()).isBlank()) {
            return "";
        }
        String resourceLabel = titleFromResourcePath(candidate.resourcePath());
        if ("modify".equals(value(intentResolution.operationKind()))
                && ("chart".equals(value(intentResolution.artifactKind()))
                || "set_chart_type".equals(value(intentResolution.changeKind())))
                && AgenticAuthoringSemanticMaterializationPolicy.containsComponent(uiCompositionPlan, "praxis-chart")) {
            String chartType = chartTypeLabel(chartType(uiCompositionPlan));
            return "Atualizei o grafico selecionado"
                    + (chartType.isBlank() ? "" : " para " + chartType)
                    + " mantendo a fonte \"" + resourceLabel + "\" e o recorte atual.";
        }
        if (AgenticAuthoringSemanticMaterializationPolicy.containsComponent(uiCompositionPlan, "praxis-chart")) {
            SemanticAxisAssistantSummary axisSummary = semanticAxisAssistantSummary(uiCompositionPlan);
            String visualization = axisSummary.verifiedAxes().isEmpty()
                    ? "inclui um grafico conectado ao recorte que voce pediu"
                    : "inclui grafico por " + joinHuman(axisSummary.verifiedAxes());
            String governance = axisSummary.unsupportedAxes().isEmpty()
                    ? "conferi os campos antes de montar a proposta"
                    : "deixei de fora " + joinHuman(axisSummary.unsupportedAxes())
                    + " porque ainda nao encontrei esses campos com seguranca";
            if (isSingleChartPlan(uiCompositionPlan)) {
                return "Montei um grafico usando \"" + resourceLabel + "\". "
                        + sentenceWithPeriod(visualization)
                        + " " + sentenceWithPeriod(governance)
                        + " Nao inclui tabela, filtros nem KPIs.";
            }
            return "Montei uma primeira versao de dashboard usando \"" + resourceLabel + "\". "
                    + sentenceWithPeriod(visualization)
                    + " " + sentenceWithPeriod(governance)
                    + " Tambem mantive " + detailSupportAssistantLine(uiCompositionPlan).toLowerCase(Locale.ROOT)
                    + " para apoiar a revisao.";
        }
        if (containsComponent(uiCompositionPlan, "praxis-table")) {
            if (containsRichTableRendering(uiCompositionPlan)) {
                return "Montei uma primeira versao usando \"" + resourceLabel + "\". "
                        + "A tabela ja vem com colunas organizadas, valores formatados e acoes por linha. "
                        + "Revise os detalhes ou me peca uma visualizacao analitica se quiser enxergar tendencias.";
            }
            boolean userRejectedAnalyticSupport = structuredAnalyticSupportRejected(request);
            String closing = userRejectedAnalyticSupport
                    ? "Mantive somente a tabela solicitada."
                    : "Se fizer sentido, posso transformar isso em um dashboard com graficos.";
            return "Montei uma primeira versao usando \"" + resourceLabel + "\" como base. "
                    + "Deixei a tabela conectada para voce revisar as informacoes e ajustar as colunas. "
                    + closing;
        }
        return "Montei uma primeira versao usando \"" + resourceLabel + "\". "
                + "Revise o resultado e salve quando estiver de acordo.";
    }

    private String chartType(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isObject()) {
            String type = value(node.path("type").asText(""));
            if (!type.isBlank()) {
                return type;
            }
            for (JsonNode child : node) {
                String nested = chartType(child);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = chartType(child);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }
        return "";
    }

    private String chartTypeLabel(String type) {
        return switch (value(type)) {
            case "line" -> "linhas";
            case "bar" -> "barras";
            case "pie" -> "pizza";
            case "donut" -> "donut";
            default -> value(type);
        };
    }

    private String sentenceWithPeriod(String value) {
        String normalized = value(value).trim();
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.endsWith(".") ? normalized : normalized + ".";
    }

    private boolean structuredAnalyticSupportRejected(AgenticAuthoringPlanRequest request) {
        AgenticAuthoringVisualizationDecision decision =
                request == null || request.intentResolution() == null
                        ? null
                        : request.intentResolution().visualizationDecision();
        if (decision == null) {
            return false;
        }
        return !decision.includeSummary()
                || !decision.includeFilters()
                || !decision.includeKpis()
                || excludesComponent(decision, "praxis-chart")
                || excludesComponent(decision, "praxis-tabs");
    }

    private boolean excludesComponent(
            AgenticAuthoringVisualizationDecision decision,
            String componentId) {
        if (decision == null || decision.excludedComponentIds() == null) {
            return false;
        }
        String expected = normalize(componentId);
        return decision.excludedComponentIds().stream()
                .map(this::normalize)
                .anyMatch(expected::equals);
    }

    private String governedReviewAssistantMessage(
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan) {
        AgenticAuthoringCandidate candidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        String resourceLabel = candidate == null || value(candidate.resourcePath()).isBlank()
                ? "a fonte encontrada"
                : titleFromResourcePath(candidate.resourcePath());
        if (containsComponent(uiCompositionPlan, "praxis-chart")) {
            if (isSingleChartPlan(uiCompositionPlan)) {
                return "Montei uma primeira pre-visualizacao de grafico para " + resourceLabel + ".\n\n"
                        + "- Fonte usada: \"" + resourceLabel + "\".\n"
                        + "- O que ja foi criado: um grafico conectado ao recorte pedido.\n"
                        + "- Por que ficou em revisao: a escolha da fonte ainda veio de evidencia semantica fraca, entao nao vou salvar automaticamente.\n"
                        + "- Como prosseguir: confirme a fonte, peca outro recorte ou diga \"salvar este grafico\".";
            }
            return "Montei uma primeira pre-visualizacao de dashboard para " + resourceLabel + ".\n\n"
                    + "- Fonte usada: \"" + resourceLabel + "\".\n"
                    + "- O que ja foi criado: componentes analiticos conectados ao schema da fonte.\n"
                    + "- Por que ficou em revisao: a escolha da fonte ainda veio de evidencia semantica fraca, entao nao vou salvar automaticamente.\n"
                    + "- Como prosseguir: confirme a fonte, peca outro recorte ou diga \"salvar este dashboard\".";
        }
        if (containsComponent(uiCompositionPlan, "praxis-table")) {
            return "Montei uma primeira pre-visualizacao de tabela para " + resourceLabel + ".\n\n"
                    + "- Fonte usada: \"" + resourceLabel + "\".\n"
                    + "- O que ja foi criado: uma tabela conectada, com colunas vindas do schema da fonte.\n"
                    + "- Por que ficou em revisao: a escolha da fonte ainda veio de evidencia semantica fraca, entao nao vou salvar automaticamente.\n"
                    + "- Como prosseguir: confirme que esta fonte esta correta, peca ajustes nas colunas ou diga \"salvar esta tabela\".";
        }
        return "Montei uma primeira pre-visualizacao usando \"" + resourceLabel + "\".\n\n"
                + "- Por que ficou em revisao: a decisao semantica ainda precisa de confirmacao antes de salvar.\n"
                + "- Como prosseguir: confirme a fonte, peca ajustes ou diga que posso salvar.";
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
        if (containsComponent(uiCompositionPlan, "praxis-list") && containsComponent(uiCompositionPlan, "praxis-table")) {
            return "lista de detalhe em cards ricos, tabela de detalhe e exploracao em modal pelos graficos para validar os dados antes de salvar";
        }
        if (containsComponent(uiCompositionPlan, "praxis-list")) {
            return "lista de detalhe em cards ricos para validar os dados antes de salvar";
        }
        if (containsComponent(uiCompositionPlan, "praxis-table")) {
            return "tabela de detalhe conectada ao recurso para validar os dados antes de salvar";
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

    private boolean isSingleChartPlan(JsonNode uiCompositionPlan) {
        return uiCompositionPlan != null
                && ("single-chart-page".equals(value(uiCompositionPlan.path("layoutPreset").asText()))
                || "single-chart".equals(value(uiCompositionPlan.path("compositionConstraints").path("mode").asText())));
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
        if (allowsSchemaSafeAxisRepair(request)) {
            return request;
        }
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
                && !"chart".equals(intentResolution.artifactKind())
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
            String type,
            String format,
            boolean hasEnum,
            String controlType,
            boolean multiple,
            String endpoint,
            Set<String> fieldTokens,
            Set<String> labelTokens,
            Set<String> descriptionTokens) {
    }

    private record SemanticAxisAssistantSummary(
            List<String> verifiedAxes,
            List<String> unsupportedAxes) {
    }
}
