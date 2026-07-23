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
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;
import org.praxisplatform.config.service.ResourceCapabilitiesFetchResult;
import org.praxisplatform.config.service.ResourceCapabilitiesRetrievalService;
import org.praxisplatform.config.service.ResourceSurfaceCatalogFetchResult;
import org.praxisplatform.config.service.ResourceSurfaceCatalogRetrievalService;
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
    private final AgenticAuthoringUiCompositionPlanCompiler uiCompositionPlanCompiler;
    private final List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders;
    private final AgenticAuthoringPreviewMessageSynthesizerService messageSynthesizer;
    private final SchemaRetrievalService schemaRetrievalService;
    private final ResourceCapabilitiesRetrievalService resourceCapabilitiesRetrievalService;
    private final ResourceSurfaceCatalogRetrievalService resourceSurfaceCatalogRetrievalService;
    private final AgenticAuthoringComponentEditPlanService componentEditPlanService;

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
        this(
                planService,
                patchCompilerService,
                objectMapper,
                uiCompositionPlanProviders,
                messageSynthesizer,
                schemaRetrievalService,
                null);
    }

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper,
            List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders,
            AgenticAuthoringPreviewMessageSynthesizerService messageSynthesizer,
            SchemaRetrievalService schemaRetrievalService,
            ResourceCapabilitiesRetrievalService resourceCapabilitiesRetrievalService) {
        this(
                planService,
                patchCompilerService,
                objectMapper,
                uiCompositionPlanProviders,
                messageSynthesizer,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService,
                null,
                null);
    }

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper,
            List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders,
            AgenticAuthoringPreviewMessageSynthesizerService messageSynthesizer,
            SchemaRetrievalService schemaRetrievalService,
            ResourceCapabilitiesRetrievalService resourceCapabilitiesRetrievalService,
            ResourceSurfaceCatalogRetrievalService resourceSurfaceCatalogRetrievalService) {
        this(
                planService,
                patchCompilerService,
                objectMapper,
                uiCompositionPlanProviders,
                messageSynthesizer,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService,
                resourceSurfaceCatalogRetrievalService,
                null);
    }

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper,
            List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders,
            AgenticAuthoringPreviewMessageSynthesizerService messageSynthesizer,
            SchemaRetrievalService schemaRetrievalService,
            ResourceCapabilitiesRetrievalService resourceCapabilitiesRetrievalService,
            AgenticAuthoringComponentEditPlanService componentEditPlanService) {
        this(
                planService,
                patchCompilerService,
                objectMapper,
                uiCompositionPlanProviders,
                messageSynthesizer,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService,
                null,
                componentEditPlanService);
    }

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper,
            List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders,
            AgenticAuthoringPreviewMessageSynthesizerService messageSynthesizer,
            SchemaRetrievalService schemaRetrievalService,
            ResourceCapabilitiesRetrievalService resourceCapabilitiesRetrievalService,
            ResourceSurfaceCatalogRetrievalService resourceSurfaceCatalogRetrievalService,
            AgenticAuthoringComponentEditPlanService componentEditPlanService) {
        this.planService = Objects.requireNonNull(planService, "planService must not be null");
        this.patchCompilerService = Objects.requireNonNull(patchCompilerService, "patchCompilerService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.intentResolutionContext = new AgenticAuthoringIntentResolutionContext(this.objectMapper);
        this.conversationTurnOrchestrator = new AgenticAuthoringConversationTurnOrchestrator();
        this.uiCompositionPlanCompiler = new AgenticAuthoringUiCompositionPlanCompiler(this.objectMapper);
        this.uiCompositionPlanProviders = List.copyOf(
                uiCompositionPlanProviders == null ? List.of() : uiCompositionPlanProviders);
        this.messageSynthesizer = messageSynthesizer;
        this.schemaRetrievalService = schemaRetrievalService;
        this.resourceCapabilitiesRetrievalService = resourceCapabilitiesRetrievalService;
        this.resourceSurfaceCatalogRetrievalService = resourceSurfaceCatalogRetrievalService;
        this.componentEditPlanService = componentEditPlanService;
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
        Optional<AgenticAuthoringPreviewResult> componentEditPreview =
                previewComponentEditPlan(effectiveRequest, tenantId, userId, environment, schemaBaseUrl);
        if (componentEditPreview.isPresent()) {
            return componentEditPreview.get();
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
        AgenticAuthoringPlanResult planResult = resolveMinimalFormPlan(
                effectiveRequest,
                tenantId,
                userId,
                environment,
                schemaBaseUrl);
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
                            MissingNode.getInstance()),
                    null,
                    null,
                    planResult.providerInvocations()
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
        AgenticAuthoringPreviewMessageResult messageResult = previewAssistantMessage(
                effectiveRequest,
                intentResolution,
                null,
                valid,
                List.copyOf(failureCodes),
                List.copyOf(warnings),
                fallbackMessage,
                tenantId,
                userId,
                environment);
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
                messageResult.message(),
                mergeProviderInvocations(planResult.providerInvocations(), messageResult.providerInvocations())
        );
    }

    public AgenticAuthoringPlanResult generateMinimalFormPlan(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment,
            String schemaBaseUrl) throws IOException {
        return resolveMinimalFormPlan(
                enrichRequest(request),
                tenantId,
                userId,
                environment,
                schemaBaseUrl);
    }

    private AgenticAuthoringPlanResult resolveMinimalFormPlan(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment,
            String schemaBaseUrl) throws IOException {
        AgenticAuthoringPlanResult schemaGroundedPlan = schemaGroundedCreateFormPlan(
                request,
                tenantId,
                userId,
                environment,
                schemaBaseUrl);
        return schemaGroundedPlan != null
                ? schemaGroundedPlan
                : planService.generateMinimalFormPlan(request, tenantId, userId, environment);
    }

    private AgenticAuthoringPlanResult schemaGroundedCreateFormPlan(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment,
            String schemaBaseUrl) {
        AgenticAuthoringIntentResolutionResult intent = request == null ? null : request.intentResolution();
        if (schemaRetrievalService == null
                || intent == null
                || intent.selectedCandidate() == null
                || !"create".equals(intent.operationKind())
                || !"form".equals(intent.artifactKind())
                || !"create_artifact".equals(intent.changeKind())) {
            return null;
        }
        AiSchemaContext context = schemaContext(intent.selectedCandidate(), MissingNode.getInstance());
        SchemaFetchResult schemaResult = context == null
                ? null
                : schemaRetrievalService.fetchSchemaResult(
                        context,
                        schemaBaseUrl,
                        tenantId,
                        userId,
                        environment);
        if (schemaResult != null && schemaResult.isSuccess()) {
            return planService.materializeCreateFormPlanFromCanonicalSchema(
                    request,
                    schemaResult.getSchema());
        }
        List<String> warnings = new ArrayList<>(intent.warnings() == null ? List.of() : intent.warnings());
        warnings.add("minimal-form-plan-schema-grounding-required");
        if (schemaResult != null && schemaResult.getCode() != null && !schemaResult.getCode().isBlank()) {
            warnings.add("minimal-form-plan-schema-fetch:" + schemaResult.getCode());
        }
        return new AgenticAuthoringPlanResult(
                false,
                List.of("canonical-create-request-schema-unavailable"),
                List.copyOf(warnings),
                MissingNode.getInstance());
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

    private Optional<AgenticAuthoringPreviewResult> previewComponentEditPlan(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment,
            String schemaBaseUrl) {
        AgenticAuthoringIntentResolutionResult intent = request == null ? null : request.intentResolution();
        if (intent == null || !intent.valid()) {
            return Optional.empty();
        }
        AgenticAuthoringSemanticDecision semanticDecision = semanticDecision(intent);
        String operationKind = semanticDecision != null && !value(semanticDecision.operationKind()).isBlank()
                ? value(semanticDecision.operationKind())
                : value(intent.operationKind());
        if (!"modify".equals(operationKind)) {
            return Optional.empty();
        }
        boolean contextDerivedFromSemanticTarget = request.contextHints() == null
                || !request.contextHints().path("authoringManifestRef").isObject();
        if (contextDerivedFromSemanticTarget && componentEditPlanService == null) {
            return Optional.empty();
        }
        request = withSemanticTargetComponentAuthoringContext(request);
        JsonNode contextHints = request.contextHints();
        JsonNode manifestRef = contextHints == null
                ? MissingNode.getInstance()
                : contextHints.path("authoringManifestRef");
        if (!manifestRef.isObject()) {
            return Optional.empty();
        }
        boolean directComponentEdit = isDirectComponentEditRequest(request, manifestRef);
        request = withSchemaFieldContext(
                request,
                schemaBaseUrl,
                new PreviewSchemaFetchCache(schemaRetrievalService));
        contextHints = request.contextHints();
        manifestRef = contextHints.path("authoringManifestRef");

        List<String> contextFailures = validateComponentEditContext(request, manifestRef, directComponentEdit);
        if (!contextFailures.isEmpty()) {
            return Optional.of(componentEditFailure(
                    request,
                    contextFailures,
                    List.of("component-edit-plan-skipped-invalid-context"),
                    MissingNode.getInstance()));
        }
        if (componentEditPlanService == null) {
            return Optional.of(componentEditFailure(
                    request,
                    List.of("component-edit-plan-service-unavailable"),
                    List.of("component-edit-plan-failed-closed"),
                    MissingNode.getInstance()));
        }

        String selectedWidgetKey = contextHints.path("selectedWidgetKey").asText("").trim();
        String componentId = manifestRef.path("componentId").asText("").trim();
        JsonNode selectedWidget = selectedComponentWidget(request.currentPage(), selectedWidgetKey);
        JsonNode config = directComponentEdit
                ? request.currentPage()
                : selectedWidget.path("definition").path("inputs");
        ObjectNode validationContext = contextHints.path("validationContext").isObject()
                ? contextHints.path("validationContext").deepCopy()
                : objectMapper.createObjectNode();
        if (contextHints.path("schemaFields").isArray()) {
            validationContext.set("schemaFields", contextHints.path("schemaFields").deepCopy());
        }
        if (contextHints.path("schemaFieldContext").isObject()) {
            validationContext.set("schemaFieldContext", contextHints.path("schemaFieldContext").deepCopy());
        }
        JsonNode hostMaterialization = selectedHostMaterialization(contextHints, selectedWidgetKey, componentId);
        if (hostMaterialization.isObject()) {
            validationContext.set("hostMaterialization", hostMaterialization.deepCopy());
        }
        AgenticAuthoringComponentEditPlanResult result = componentEditPlanService.generateAndCompile(
                request,
                componentId,
                config,
                validationContext,
                tenantId,
                userId,
                environment);
        if (!result.valid()) {
            return Optional.of(componentEditFailure(
                    request,
                    result.failureCodes(),
                    result.warnings(),
                    result.plan(),
                    result.providerInvocations()));
        }

        JsonNode proposedConfig = result.compiledPatch().path("proposedConfig");
        if (!proposedConfig.isObject()) {
            return Optional.of(componentEditFailure(
                    request,
                    List.of("component-edit-plan-proposed-config-missing"),
                    result.warnings(),
                    result.plan(),
                    result.providerInvocations()));
        }
        ObjectNode compiledFormPatch = directComponentEdit
                ? materializeDirectComponentEditPatch(
                        componentId,
                        result.plan(),
                        result.compiledPatch(),
                        proposedConfig)
                : materializeComponentEditPagePatch(
                        request.currentPage(),
                        selectedWidgetKey,
                        componentId,
                        result.plan(),
                        result.compiledPatch(),
                        proposedConfig);
        List<String> warnings = new ArrayList<>(
                result.warnings() == null ? List.of() : result.warnings());
        warnings.add("compiled-from-component-authoring-manifest");
        if (contextDerivedFromSemanticTarget) {
            warnings.add("component-edit-context-derived-from-semantic-target");
        }
        if (directComponentEdit) {
            warnings.add("component-edit-target-is-local-component");
        }
        if (validationContext.path("schemaFields").isArray()
                && !validationContext.path("schemaFields").isEmpty()) {
            warnings.add("component-edit-plan-schema-fields-grounded");
        }
        boolean noOp = warnings.contains("component-edit-plan-no-op");
        return Optional.of(new AgenticAuthoringPreviewResult(
                true,
                List.of(),
                List.copyOf(warnings),
                result.plan(),
                compiledFormPatch,
                diagnostics(
                        request,
                        intent,
                        List.of(),
                        List.copyOf(warnings),
                        result.plan(),
                        compiledFormPatch),
                null,
                noOp
                        ? AgenticAuthoringPresentationText.assistantReply(
                                "A configuração solicitada já está aplicada. Não fiz nenhuma alteração.")
                        : componentEditAssistantMessage(result.plan()),
                result.providerInvocations()));
    }

    private JsonNode selectedHostMaterialization(
            JsonNode contextHints,
            String selectedWidgetKey,
            String componentId) {
        JsonNode components = contextHints == null
                ? MissingNode.getInstance()
                : contextHints.path("groundedRuntimeComponentContext").path("components");
        if (!components.isArray()) {
            return MissingNode.getInstance();
        }
        for (JsonNode component : components) {
            JsonNode identity = component.path("identity");
            boolean componentMatches = componentId.equals(identity.path("componentId").asText(""));
            boolean widgetMatches = selectedWidgetKey.isBlank()
                    || selectedWidgetKey.equals(identity.path("widgetKey").asText(""));
            JsonNode materialization = component.path("affordances").path("visualMaterialization");
            if (componentMatches && widgetMatches && materialization.isObject()) {
                return materialization;
            }
        }
        return MissingNode.getInstance();
    }

    /**
     * Restores the canonical component-edit context when the user continues from an already
     * materialized preview without keeping a widget explicitly selected in the client. The
     * semantic resolver has already selected the target widget and component; this method only
     * reconciles that decision with the current page before the server-owned manifest is loaded.
     */
    private AgenticAuthoringPlanRequest withSemanticTargetComponentAuthoringContext(
            AgenticAuthoringPlanRequest request) {
        if (request == null) {
            return null;
        }
        JsonNode existingHints = request.contextHints();
        if (existingHints != null && existingHints.path("authoringManifestRef").isObject()) {
            return request;
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        AgenticAuthoringTarget target = intent == null ? null : intent.target();
        String widgetKey = target == null ? "" : value(target.widgetKey()).trim();
        String componentId = target == null ? "" : value(target.componentId()).trim();
        if (widgetKey.isBlank() || componentId.isBlank()) {
            return request;
        }
        JsonNode widget = selectedComponentWidget(request.currentPage(), widgetKey);
        if (widget.isMissingNode()
                || !componentId.equals(widget.path("definition").path("id").asText("").trim())) {
            return request;
        }

        ObjectNode contextHints = existingHints != null && existingHints.isObject()
                ? existingHints.deepCopy()
                : objectMapper.createObjectNode();
        contextHints.put("selectedWidgetKey", widgetKey);
        contextHints.put("selectedComponentId", componentId);
        contextHints.putObject("authoringManifestRef")
                .put("componentId", componentId)
                .put("source", "server-resolved-semantic-target");
        if (!contextHints.path("validationContext").isObject()) {
            contextHints.putObject("validationContext");
        }
        if (!contextHints.path("contextDiagnostics").isArray()) {
            contextHints.putArray("contextDiagnostics");
        }
        return copyWithContextHints(request, contextHints);
    }

    /**
     * Preserves the natural-language understanding produced by semantic intent resolution for a
     * manifest-backed edit. The compiled plan remains the source of truth for materialization; this
     * projection only prevents the final UX from discarding the specific edit in favor of a generic
     * table message.
     */
    private String componentEditAssistantMessage(JsonNode compiledPlan) {
        List<String> operationIds = new ArrayList<>();
        if (compiledPlan != null && compiledPlan.path("operations").isArray()) {
            compiledPlan.path("operations").forEach(operation -> {
                String operationId = value(operation.path("operationId").asText());
                if (!operationId.isBlank() && !operationIds.contains(operationId)) {
                    operationIds.add(operationId);
                }
            });
        }
        String operations = operationIds.isEmpty()
                ? "operações validadas"
                : String.join(", ", operationIds);
        return AgenticAuthoringPresentationText.assistantReply(
                "Preparei uma prévia com " + operations
                        + ". As demais configurações atuais serão preservadas.");
    }

    private boolean isDirectComponentEditRequest(
            AgenticAuthoringPlanRequest request,
            JsonNode manifestRef) {
        if (request == null || request.intentResolution() == null || manifestRef == null) {
            return false;
        }
        String manifestComponentId = manifestRef.path("componentId").asText("").trim();
        String targetComponentId = value(request.intentResolution().targetComponentId()).trim();
        boolean pageDocument = request.currentPage() != null
                && request.currentPage().path("widgets").isArray();
        return !manifestComponentId.isBlank()
                && manifestComponentId.equals(targetComponentId)
                && !pageDocument;
    }

    private List<String> validateComponentEditContext(
            AgenticAuthoringPlanRequest request,
            JsonNode manifestRef,
            boolean directComponentEdit) {
        List<String> failures = new ArrayList<>();
        JsonNode contextHints = request.contextHints();
        String selectedWidgetKey = contextHints.path("selectedWidgetKey").asText("").trim();
        String selectedComponentId = contextHints.path("selectedComponentId").asText("").trim();
        String manifestComponentId = manifestRef.path("componentId").asText("").trim();
        if (!directComponentEdit && selectedWidgetKey.isBlank()) {
            failures.add("component-edit-plan-selected-widget-required");
        }
        if (!directComponentEdit && selectedComponentId.isBlank()) {
            failures.add("component-edit-plan-selected-component-required");
        }
        if (manifestComponentId.isBlank()) {
            failures.add("component-edit-plan-manifest-component-required");
        }
        if (!selectedComponentId.isBlank()
                && !manifestComponentId.isBlank()
                && !selectedComponentId.equals(manifestComponentId)) {
            failures.add("component-edit-plan-manifest-component-mismatch");
        }

        if (!directComponentEdit) {
            JsonNode selectedWidget = selectedComponentWidget(request.currentPage(), selectedWidgetKey);
            if (selectedWidget.isMissingNode()) {
                failures.add("component-edit-plan-selected-widget-not-found");
            } else if (!selectedComponentId.equals(
                    selectedWidget.path("definition").path("id").asText("").trim())) {
                failures.add("component-edit-plan-selected-widget-component-mismatch");
            }
        }

        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        if (intent.gate() == null || !"eligible".equals(intent.gate().status())) {
            failures.add("component-edit-plan-intent-not-eligible");
        }
        AgenticAuthoringTarget target = intent.target();
        boolean semanticTargetMismatch = directComponentEdit
                ? !manifestComponentId.equals(value(intent.targetComponentId()).trim())
                : target == null
                        || !selectedWidgetKey.equals(value(target.widgetKey()).trim())
                        || (!value(target.componentId()).trim().isBlank()
                        && !selectedComponentId.equals(value(target.componentId()).trim()));
        if (semanticTargetMismatch) {
            failures.add("component-edit-plan-semantic-target-mismatch");
        }

        JsonNode diagnostics = contextHints.path("contextDiagnostics");
        if (diagnostics.isArray()) {
            for (JsonNode diagnostic : diagnostics) {
                if (!"error".equals(diagnostic.path("severity").asText("").trim())) {
                    continue;
                }
                failures.add("component-authoring-context-unavailable");
                String code = diagnostic.path("code").asText("").trim();
                if (!code.isBlank()) {
                    failures.add("component-authoring-context-diagnostic:" + code);
                }
            }
        }
        return List.copyOf(new LinkedHashSet<>(failures));
    }

    private ObjectNode materializeDirectComponentEditPatch(
            String componentId,
            JsonNode plan,
            JsonNode componentPatch,
            JsonNode proposedConfig) {
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        compiledFormPatch.put("version", "1.0.0");
        compiledFormPatch.put("profileId", "component-manifest-edit");
        compiledFormPatch.put("targetComponentId", componentId);
        ObjectNode componentEdit = compiledFormPatch.putObject("componentEdit");
        componentEdit.put("componentId", componentId);
        componentEdit.put("manifestVersion", componentPatch.path("manifestVersion").asText(""));
        componentEdit.set("plan", plan == null ? MissingNode.getInstance() : plan.deepCopy());
        componentEdit.set("compiledPatch", componentPatch.deepCopy());
        compiledFormPatch.set("patch", proposedConfig.deepCopy());
        return compiledFormPatch;
    }

    private JsonNode selectedComponentWidget(JsonNode currentPage, String widgetKey) {
        if (currentPage == null || widgetKey == null || widgetKey.isBlank()) {
            return MissingNode.getInstance();
        }
        for (JsonNode widget : currentPage.path("widgets")) {
            if (widgetKey.equals(widget.path("key").asText(""))) {
                return widget;
            }
        }
        return MissingNode.getInstance();
    }

    private ObjectNode materializeComponentEditPagePatch(
            JsonNode currentPage,
            String widgetKey,
            String componentId,
            JsonNode plan,
            JsonNode componentPatch,
            JsonNode proposedConfig) {
        ObjectNode page = currentPage != null && currentPage.isObject()
                ? currentPage.deepCopy()
                : objectMapper.createObjectNode();
        JsonNode selectedWidget = selectedComponentWidget(page, widgetKey);
        if (selectedWidget instanceof ObjectNode widgetObject) {
            ObjectNode definition = widgetObject.path("definition") instanceof ObjectNode existingDefinition
                    ? existingDefinition
                    : widgetObject.putObject("definition");
            definition.set("inputs", proposedConfig.deepCopy());
        }

        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        compiledFormPatch.put("version", "1.0.0");
        compiledFormPatch.put("profileId", "component-manifest-edit");
        compiledFormPatch.put("targetComponentId", "praxis-dynamic-page-builder");
        ObjectNode componentEdit = compiledFormPatch.putObject("componentEdit");
        componentEdit.put("componentId", componentId);
        componentEdit.put("widgetKey", widgetKey);
        componentEdit.put("manifestVersion", componentPatch.path("manifestVersion").asText(""));
        componentEdit.set("plan", plan == null ? MissingNode.getInstance() : plan.deepCopy());
        componentEdit.set("compiledPatch", componentPatch.deepCopy());
        compiledFormPatch.putObject("patch").set("page", page);
        return compiledFormPatch;
    }

    private AgenticAuthoringPreviewResult componentEditFailure(
            AgenticAuthoringPlanRequest request,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode plan) {
        return componentEditFailure(request, failureCodes, warnings, plan, List.of());
    }

    private AgenticAuthoringPreviewResult componentEditFailure(
            AgenticAuthoringPlanRequest request,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode plan,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        List<String> failures = failureCodes == null ? List.of() : List.copyOf(failureCodes);
        List<String> safeWarnings = warnings == null ? List.of() : List.copyOf(warnings);
        JsonNode safePlan = plan == null ? MissingNode.getInstance() : plan;
        return new AgenticAuthoringPreviewResult(
                false,
                failures,
                safeWarnings,
                safePlan,
                MissingNode.getInstance(),
                diagnostics(
                        request,
                        request.intentResolution(),
                        failures,
                        safeWarnings,
                        safePlan,
                        MissingNode.getInstance()),
                null,
                null,
                providerInvocations);
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
        PreviewSchemaFetchCache schemaFetchCache = new PreviewSchemaFetchCache(schemaRetrievalService);
        PreviewResourceCapabilitiesFetchCache capabilitiesFetchCache =
                new PreviewResourceCapabilitiesFetchCache(resourceCapabilitiesRetrievalService);
        PreviewResourceSurfaceCatalogFetchCache surfaceCatalogFetchCache =
                new PreviewResourceSurfaceCatalogFetchCache(resourceSurfaceCatalogRetrievalService);
        request = withGovernedAnalyticsContext(
                request,
                schemaBaseUrl,
                tenantId,
                userId,
                environment,
                schemaFetchCache,
                capabilitiesFetchCache,
                surfaceCatalogFetchCache);
        if (!governedAnalyticsGroundingBlocksMaterialization(request)) {
            request = withSchemaFieldContext(request, schemaBaseUrl, schemaFetchCache);
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
            if (!planResult.valid()) {
                return Optional.of(invalidUiCompositionPlanPreview(
                        request,
                        planResult,
                        failureCodes,
                        warnings,
                        tenantId,
                        userId,
                        environment));
            }
            boolean technicallyValid = planResult.valid();
            boolean semanticallyValid = planResult.valid();
            JsonNode uiCompositionPlan = normalizeCountMetricBindings(
                    planResult.uiCompositionPlan(),
                    warnings);
            uiCompositionPlan = verifySemanticAxesWithSchema(
                    request,
                    uiCompositionPlan,
                    warnings,
                    schemaBaseUrl,
                    schemaFetchCache);
            uiCompositionPlan = groundTableQueryContextFilters(
                    request,
                    uiCompositionPlan,
                    warnings,
                    schemaBaseUrl,
                    schemaFetchCache);
            VisibleTableQueryFilterMaterialization visibleTableFilters =
                    materializeVisibleTableQueryFilters(
                            request,
                            uiCompositionPlan,
                            warnings,
                            schemaBaseUrl,
                            schemaFetchCache);
            uiCompositionPlan = visibleTableFilters.uiCompositionPlan();
            if (!visibleTableFilters.failureCodes().isEmpty()) {
                addAllOnce(failureCodes, visibleTableFilters.failureCodes());
                technicallyValid = false;
                semanticallyValid = false;
            }
            if (warnings.contains("table-query-filter-schema-grounding-incomplete")) {
                addAllOnce(failureCodes, List.of("table-query-filter-schema-grounding-incomplete"));
                technicallyValid = false;
                semanticallyValid = false;
            }
            if (provider instanceof AgenticAuthoringGenericUiCompositionPlanProvider genericProvider
                    && uiCompositionPlan instanceof ObjectNode uiCompositionPlanObject
                    && genericProvider.reflowPrunedDashboard(request, uiCompositionPlanObject)) {
                addWarningOnce(warnings, "ui-composition-plan-layout-reflowed-after-widget-prune");
            }
            uiCompositionPlan = verifyStatsCapabilities(
                    request,
                    uiCompositionPlan,
                    warnings,
                    schemaBaseUrl,
                    tenantId,
                    userId,
                    environment,
                    capabilitiesFetchCache);
            uiCompositionPlan = verifyResourceSchemaGrounding(
                    request,
                    uiCompositionPlan,
                    warnings,
                    schemaBaseUrl,
                    schemaFetchCache);
            uiCompositionPlan = reconcileChartInteractionsWithGrounding(
                    request,
                    uiCompositionPlan,
                    warnings,
                    schemaBaseUrl,
                    schemaFetchCache);
            if (uiCompositionPlan instanceof ObjectNode uiCompositionPlanObject) {
                normalizeFilterQueryContextBindings(uiCompositionPlanObject, warnings);
                if (!AgenticAuthoringSemanticMaterializationPolicy.requiresChartMaterialization(
                        semanticDecision(request.intentResolution()))) {
                    markOrphanUnverifiedSemanticAxesAsDropped(uiCompositionPlanObject, warnings);
                }
            }
            JsonNode semanticMaterialization = semanticMaterialization(planResult, uiCompositionPlan);
            if (containsUnverifiedSemanticAxes(semanticMaterialization)) {
                warnings.add("semantic-axis-schema-verification-pending");
            }
            if (containsUnverifiedStatsAxes(semanticMaterialization)) {
                warnings.add("semantic-axis-stats-capability-verification-pending");
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
            JsonNode compiledFormPatch = planResult.compiledFormPatch() == null
                    ? MissingNode.getInstance()
                    : planResult.compiledFormPatch();
            if (uiCompositionPlan != null && uiCompositionPlan.isObject()) {
                AgenticAuthoringUiCompositionPlanCompiler.CompileResult compilation =
                        uiCompositionPlanCompiler.compile(uiCompositionPlan, compiledFormPatch);
                addAllOnce(failureCodes, compilation.failureCodes());
                if (!compilation.valid()) {
                    technicallyValid = false;
                    semanticallyValid = false;
                } else {
                    compiledFormPatch = compilation.compiledFormPatch();
                    addWarningOnce(warnings, "ui-composition-plan-compiled-by-config");
                }
            } else {
                String compiledPatchFailure =
                        AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(compiledFormPatch);
                if (!compiledPatchFailure.isBlank()) {
                    addAllOnce(failureCodes, List.of(compiledPatchFailure));
                    technicallyValid = false;
                    semanticallyValid = false;
                }
            }
            String fallbackMessage = deterministicPreviewAssistantMessage(
                    request,
                    request.intentResolution(),
                    semanticMaterialization,
                    semanticallyValid,
                    List.copyOf(failureCodes));
            AgenticAuthoringPreviewMessageResult messageResult = previewAssistantMessage(
                    request,
                    request.intentResolution(),
                    semanticMaterialization,
                    semanticallyValid,
                    List.copyOf(failureCodes),
                    List.copyOf(warnings),
                    fallbackMessage,
                    tenantId,
                    userId,
                    environment);
            return Optional.of(new AgenticAuthoringPreviewResult(
                    technicallyValid && !containsUnverifiedStatsAxes(semanticMaterialization),
                    List.copyOf(failureCodes),
                    List.copyOf(warnings),
                    MissingNode.getInstance(),
                    compiledFormPatch,
                    diagnostics(
                            request,
                            request.intentResolution(),
                            failureCodes,
                            List.copyOf(warnings),
                            semanticMaterialization,
                            compiledFormPatch),
                    uiCompositionPlan,
                    messageResult.message(),
                    messageResult.providerInvocations()
            ));
        }
        return Optional.empty();
    }

    private boolean governedAnalyticsGroundingBlocksMaterialization(AgenticAuthoringPlanRequest request) {
        JsonNode grounding = request == null || request.contextHints() == null
                ? MissingNode.getInstance()
                : request.contextHints().path("governedAnalytics");
        return "comparison".equals(grounding.path("requestedOperation").asText(""))
                && !"verified".equals(grounding.path("status").asText(""));
    }

    private AgenticAuthoringPreviewResult invalidUiCompositionPlanPreview(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringUiCompositionPlanResult planResult,
            List<String> failureCodes,
            List<String> warnings,
            String tenantId,
            String userId,
            String environment) {
        addWarningOnce(warnings, "ui-composition-plan-post-processing-skipped-invalid-provider-result");
        JsonNode uiCompositionPlan = planResult.uiCompositionPlan() == null
                ? MissingNode.getInstance()
                : planResult.uiCompositionPlan();
        JsonNode compiledFormPatch = planResult.compiledFormPatch() == null
                ? MissingNode.getInstance()
                : planResult.compiledFormPatch();
        String fallbackMessage = deterministicPreviewAssistantMessage(
                request,
                request == null ? null : request.intentResolution(),
                uiCompositionPlan,
                false,
                List.copyOf(failureCodes));
        AgenticAuthoringPreviewMessageResult messageResult = previewAssistantMessage(
                request,
                request == null ? null : request.intentResolution(),
                uiCompositionPlan,
                false,
                List.copyOf(failureCodes),
                List.copyOf(warnings),
                fallbackMessage,
                tenantId,
                userId,
                environment);
        return new AgenticAuthoringPreviewResult(
                false,
                List.copyOf(failureCodes),
                List.copyOf(warnings),
                MissingNode.getInstance(),
                compiledFormPatch,
                diagnostics(
                        request,
                        request == null ? null : request.intentResolution(),
                        List.copyOf(failureCodes),
                        List.copyOf(warnings),
                        uiCompositionPlan,
                        compiledFormPatch),
                uiCompositionPlan,
                messageResult.message(),
                messageResult.providerInvocations());
    }

    private AgenticAuthoringPlanRequest withGovernedAnalyticsContext(
            AgenticAuthoringPlanRequest request,
            String schemaBaseUrl,
            String tenantId,
            String userId,
            String environment,
            PreviewSchemaFetchCache schemaFetchCache,
            PreviewResourceCapabilitiesFetchCache capabilitiesFetchCache,
            PreviewResourceSurfaceCatalogFetchCache surfaceCatalogFetchCache) {
        String requestedOperation = requestedGovernedAnalyticsOperation(request);
        if (request == null || requestedOperation.isBlank()) {
            return request;
        }
        AgenticAuthoringCandidate candidate = request.intentResolution() == null
                ? null
                : request.intentResolution().selectedCandidate();
        String resourcePath = businessResourcePath(firstNonBlank(
                candidate == null ? "" : candidate.resourcePath(),
                candidate == null ? "" : candidate.submitUrl()));
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode grounding = contextHints.putObject("governedAnalytics");
        grounding.put("schemaVersion", "praxis-agentic-authoring-governed-analytics.v1");
        grounding.put("requestedOperation", requestedOperation);
        grounding.put("resourcePath", resourcePath);
        ArrayNode sources = grounding.putArray("sources");
        sources.add("resource.capabilities");
        sources.add("schemas.filtered:x-ui.analytics");

        if (resourcePath.isBlank() || capabilitiesFetchCache == null) {
            grounding.put("status", "invalid-resource");
            return copyWithContextHints(request, contextHints);
        }
        ResourceCapabilitiesFetchResult capabilitiesResult = capabilitiesFetchCache.fetch(
                resourcePath,
                schemaBaseUrl,
                tenantId,
                userId,
                environment);
        if (capabilitiesResult == null || !capabilitiesResult.isSuccess()) {
            grounding.put("status", "capabilities-" + fetchStatus(capabilitiesResult));
            return copyWithContextHints(request, contextHints);
        }
        JsonNode operationCapability = canonicalStatsOperationCapability(
                capabilitiesResult.getCapabilities(),
                requestedOperation);
        if (!operationCapability.isObject() || !operationCapability.path("supported").asBoolean(false)) {
            grounding.put("status", "operation-unsupported");
            return copyWithContextHints(request, contextHints);
        }
        grounding.put("capabilityVerified", true);
        grounding.put("operationId", operationCapability.path("id").asText(
                canonicalStatsOperationId(requestedOperation)));
        JsonNode availability = operationCapability.path("availability");
        if (!availability.isObject() || !availability.has("allowed") || !availability.path("allowed").isBoolean()) {
            grounding.put("status", "operation-availability-unverified");
            return copyWithContextHints(request, contextHints);
        }
        ObjectNode operationAvailability = grounding.putObject("operationAvailability");
        operationAvailability.put("allowed", availability.path("allowed").asBoolean());
        putText(operationAvailability, "reason", availability.path("reason").asText(""));
        putText(operationAvailability, "accessClass", availability.path("metadata").path("accessClass").asText(""));
        if (!availability.path("allowed").asBoolean()) {
            grounding.put("status", "operation-unavailable");
            putText(grounding, "availabilityReason", availability.path("reason").asText(""));
            return copyWithContextHints(request, contextHints);
        }

        AiSchemaContext analyticsSchemaContext = AiSchemaContext.builder()
                .path(resourcePath + "/stats/" + requestedOperation)
                .operation("post")
                .schemaType("response")
                .build();
        SchemaFetchResult schemaResult = schemaFetchCache.fetchPrincipalAware(
                analyticsSchemaContext,
                schemaBaseUrl,
                tenantId,
                userId,
                environment);
        if (schemaResult == null || !schemaResult.isSuccess()) {
            grounding.put("status", "schema-" + schemaFetchStatus(schemaResult));
            return copyWithContextHints(request, contextHints);
        }
        grounding.put("schemaVerified", true);
        Optional<ObjectNode> projection = governedAnalyticsProjection(
                schemaResult.getSchema(),
                capabilitiesResult.getCapabilities(),
                resourcePath,
                requestedOperation);
        if (projection.isEmpty()) {
            grounding.put("status", missingKeyFilterBinding(
                    schemaResult.getSchema(),
                    resourcePath,
                    requestedOperation)
                            ? "key-filter-binding-required"
                            : "projection-ineligible");
            return copyWithContextHints(request, contextHints);
        }
        ObjectNode sanitizedProjection = projection.get();
        JsonNode projectionInteractions = sanitizedProjection.path("interactions");
        boolean crossFilterEnabled = projectionInteractions.path("crossFilter").asBoolean(false);
        JsonNode recordOpen = projectionInteractions.path("recordOpen");
        if (crossFilterEnabled || recordOpen.isObject()) {
            JsonNode nominalCapability = capabilityRoot(capabilitiesResult.getCapabilities())
                    .path("operations")
                    .path("filter");
            if (!nominalCapability.isObject() || !nominalCapability.path("supported").asBoolean(false)) {
                grounding.put("status", "nominal-operation-unsupported");
                return copyWithContextHints(request, contextHints);
            }
            JsonNode nominalAvailability = nominalCapability.path("availability");
            if (!nominalAvailability.isObject()
                    || !nominalAvailability.has("allowed")
                    || !nominalAvailability.path("allowed").isBoolean()) {
                grounding.put("status", "nominal-operation-availability-unverified");
                return copyWithContextHints(request, contextHints);
            }
            ObjectNode publishedNominalAvailability = grounding.putObject("nominalOperationAvailability");
            publishedNominalAvailability.put("operationId", nominalCapability.path("id").asText("filter"));
            publishedNominalAvailability.put("allowed", nominalAvailability.path("allowed").asBoolean());
            putText(publishedNominalAvailability, "reason", nominalAvailability.path("reason").asText(""));
            if (!nominalAvailability.path("allowed").asBoolean()) {
                removeNominalInteractions(sanitizedProjection);
                crossFilterEnabled = false;
                recordOpen = MissingNode.getInstance();
            }
        }
        if (crossFilterEnabled) {
            String keyFilterField = sanitizedProjection.path("bindings")
                    .path("primaryDimension")
                    .path("keyFilterField")
                    .asText("")
                    .trim();
            if (keyFilterField.isBlank()) {
                grounding.put("status", "key-filter-binding-required");
                return copyWithContextHints(request, contextHints);
            }
            AiSchemaContext filterSchemaContext = AiSchemaContext.builder()
                    .path(resourcePath + "/filter")
                    .operation("post")
                    .schemaType("request")
                    .build();
            SchemaFetchResult filterSchemaResult = schemaFetchCache.fetchPrincipalAware(
                    filterSchemaContext,
                    schemaBaseUrl,
                    tenantId,
                    userId,
                    environment);
            if (filterSchemaResult == null || !filterSchemaResult.isSuccess()) {
                grounding.put("status", "key-filter-schema-" + schemaFetchStatus(filterSchemaResult));
                return copyWithContextHints(request, contextHints);
            }
            Map<String, SchemaFieldDescriptor> filterFields = schemaFields(filterSchemaResult.getSchema());
            SchemaFieldDescriptor targetField = filterFields.get(normalize(keyFilterField));
            if (targetField == null || !keyFilterField.equals(targetField.name())) {
                grounding.put("status", "key-filter-field-missing");
                return copyWithContextHints(request, contextHints);
            }
            if (!supportsBucketKey(targetField)) {
                grounding.put("status", "key-filter-field-incompatible");
                return copyWithContextHints(request, contextHints);
            }
            sources.add("schemas.filtered:filter-request");
            ObjectNode keyFilterBinding = grounding.putObject("keyFilterBinding");
            keyFilterBinding.put("field", targetField.name());
            keyFilterBinding.put("type", targetField.type());
            keyFilterBinding.put("multiple", targetField.multiple()
                    || "array".equals(normalize(targetField.type())));
            putText(keyFilterBinding, "itemType", targetField.itemType());
            keyFilterBinding.put("schemaVerified", true);
        }
        if (recordOpen.isObject()) {
            String sourceIdentityField = recordOpen.path("sourceIdentityField").asText("").trim();
            String targetResourceKey = recordOpen.path("target").path("resourceKey").asText("").trim();
            String targetSurfaceId = recordOpen.path("target").path("surfaceId").asText("").trim();
            if (sourceIdentityField.isBlank() || targetResourceKey.isBlank() || targetSurfaceId.isBlank()) {
                grounding.put("status", "record-open-invalid");
                return copyWithContextHints(request, contextHints);
            }

            AiSchemaContext nominalResponseSchemaContext = AiSchemaContext.builder()
                    .path(resourcePath + "/filter")
                    .operation("post")
                    .schemaType("response")
                    .build();
            SchemaFetchResult nominalResponseSchema = schemaFetchCache.fetchPrincipalAware(
                    nominalResponseSchemaContext,
                    schemaBaseUrl,
                    tenantId,
                    userId,
                    environment);
            if (nominalResponseSchema == null || !nominalResponseSchema.isSuccess()) {
                grounding.put("status", "record-open-source-schema-" + schemaFetchStatus(nominalResponseSchema));
                return copyWithContextHints(request, contextHints);
            }
            Map<String, SchemaFieldDescriptor> nominalFields = schemaFields(nominalResponseSchema.getSchema());
            SchemaFieldDescriptor sourceField = nominalFields.get(normalize(sourceIdentityField));
            if (sourceField == null || !sourceIdentityField.equals(sourceField.name())) {
                grounding.put("status", "record-open-source-field-missing");
                return copyWithContextHints(request, contextHints);
            }
            if (!supportsRecordIdentity(sourceField)) {
                grounding.put("status", "record-open-source-field-incompatible");
                return copyWithContextHints(request, contextHints);
            }
            sources.add("schemas.filtered:filter-response");

            ResourceSurfaceCatalogFetchResult surfaceCatalogResult = surfaceCatalogFetchCache == null
                    ? null
                    : surfaceCatalogFetchCache.fetch(
                            targetResourceKey,
                            schemaBaseUrl,
                            tenantId,
                            userId,
                            environment);
            if (surfaceCatalogResult == null || !surfaceCatalogResult.isSuccess()) {
                grounding.put("status", "record-open-surface-catalog-" + surfaceFetchStatus(surfaceCatalogResult));
                return copyWithContextHints(request, contextHints);
            }
            JsonNode surfaceCatalog = surfaceCatalogResult.getCatalog();
            JsonNode targetSurface = findSurface(surfaceCatalog.path("surfaces"), targetSurfaceId);
            if (!targetSurface.isObject()
                    || !targetResourceKey.equals(targetSurface.path("resourceKey").asText(""))) {
                grounding.put("status", "record-open-surface-missing");
                return copyWithContextHints(request, contextHints);
            }
            if (!"ITEM".equals(targetSurface.path("scope").asText(""))) {
                grounding.put("status", "record-open-surface-scope-incompatible");
                return copyWithContextHints(request, contextHints);
            }
            JsonNode surfaceAvailability = targetSurface.path("availability");
            if (!surfaceAvailability.isObject()
                    || !surfaceAvailability.has("allowed")
                    || !surfaceAvailability.path("allowed").isBoolean()) {
                grounding.put("status", "record-open-surface-availability-unverified");
                return copyWithContextHints(request, contextHints);
            }
            boolean surfaceAllowed = surfaceAvailability.path("allowed").asBoolean(false);
            String surfaceAvailabilityReason = surfaceAvailability.path("reason").asText("").trim();
            if (!surfaceAllowed && !"resource-context-required".equals(surfaceAvailabilityReason)) {
                grounding.put("status", "record-open-surface-unavailable");
                putText(grounding, "availabilityReason", surfaceAvailabilityReason);
                return copyWithContextHints(request, contextHints);
            }

            sources.add("schemas.surfaces:target-resource");
            ObjectNode resolution = grounding.putObject("recordOpenResolution");
            resolution.put("sourceIdentityField", sourceIdentityField);
            resolution.put("sourceFieldType", sourceField.type());
            resolution.put("targetResourceKey", targetResourceKey);
            resolution.put("targetResourcePath", surfaceCatalog.path("resourcePath").asText(""));
            resolution.put("targetSurfaceId", targetSurfaceId);
            resolution.put("targetSurfaceKind", targetSurface.path("kind").asText(""));
            resolution.put("targetSurfaceScope", targetSurface.path("scope").asText(""));
            resolution.put("targetOperationId", targetSurface.path("operationId").asText(""));
            resolution.put("availability", surfaceAllowed ? "allowed" : surfaceAvailabilityReason);
            resolution.put("schemaVerified", true);
            resolution.put("catalogEndpointUrl", value(surfaceCatalogResult.getEndpointUrl()));
        }
        grounding.put("status", "verified");
        grounding.put("capabilityEndpointUrl", value(capabilitiesResult.getEndpointUrl()));
        grounding.put("schemaEndpointUrl", value(schemaResult.getEndpointUrl()));
        grounding.set("projection", sanitizedProjection);
        return copyWithContextHints(request, contextHints);
    }

    private void removeNominalInteractions(ObjectNode projection) {
        JsonNode interactions = projection.path("interactions");
        if (interactions instanceof ObjectNode interactionObject) {
            interactionObject.put("crossFilter", false);
            interactionObject.remove("recordOpen");
        }
        JsonNode primaryDimension = projection.path("bindings").path("primaryDimension");
        if (primaryDimension instanceof ObjectNode primaryDimensionObject) {
            primaryDimensionObject.remove("keyFilterField");
        }
    }

    private String requestedGovernedAnalyticsOperation(AgenticAuthoringPlanRequest request) {
        if (request == null || request.intentResolution() == null) {
            return "";
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        AgenticAuthoringCandidate candidate = intent.selectedCandidate();
        if (candidate != null && (endsWithStatsOperation(candidate.resourcePath(), "comparison")
                || endsWithStatsOperation(candidate.submitUrl(), "comparison"))) {
            return "comparison";
        }
        return "";
    }

    private boolean endsWithStatsOperation(String path, String operation) {
        String value = path == null ? "" : path.trim().replaceAll("/+$", "");
        return value.endsWith("/stats/" + operation);
    }

    private JsonNode canonicalStatsOperationCapability(JsonNode capabilities, String operation) {
        JsonNode root = capabilityRoot(capabilities);
        String operationId = canonicalStatsOperationId(operation);
        return operationId.isBlank() ? MissingNode.getInstance() : root.path("operations").path(operationId);
    }

    private String canonicalStatsOperationId(String operation) {
        return switch (operation) {
            case "comparison" -> "statsComparison";
            default -> "";
        };
    }

    private boolean isCanonicalStatsOperationAvailableForPrincipal(JsonNode capabilities, String operation) {
        JsonNode operationCapability = canonicalStatsOperationCapability(capabilities, operation);
        JsonNode availability = operationCapability.path("availability");
        return operationCapability.path("supported").asBoolean(false)
                && availability.isObject()
                && availability.path("allowed").isBoolean()
                && availability.path("allowed").asBoolean(false);
    }

    private JsonNode capabilityRoot(JsonNode capabilities) {
        JsonNode root = capabilities == null ? MissingNode.getInstance() : capabilities;
        return root.path("data").isObject() ? root.path("data") : root;
    }

    private boolean missingKeyFilterBinding(JsonNode schema, String resourcePath, String operation) {
        JsonNode projections = schema == null
                ? MissingNode.getInstance()
                : schema.path("x-ui").path("analytics").path("projections");
        if (!projections.isArray()) {
            return false;
        }
        for (JsonNode projection : projections) {
            JsonNode source = projection.path("source");
            if (operation.equals(source.path("operation").asText(""))
                    && sameResourcePath(resourcePath, source.path("resource").asText(""))
                    && projection.path("interactions").path("crossFilter").asBoolean(false)
                    && projection.path("bindings").path("primaryDimension")
                            .path("keyFilterField").asText("").isBlank()) {
                return true;
            }
        }
        return false;
    }

    private Optional<ObjectNode> governedAnalyticsProjection(
            JsonNode schema,
            JsonNode capabilities,
            String resourcePath,
            String operation) {
        JsonNode projections = schema == null
                ? MissingNode.getInstance()
                : schema.path("x-ui").path("analytics").path("projections");
        if (!projections.isArray()) {
            return Optional.empty();
        }
        List<StatsCapabilityFieldDescriptor> capabilityFields = statsCapabilityFields(capabilities);
        for (JsonNode projection : projections) {
            JsonNode source = projection.path("source");
            if (!"praxis.stats".equals(source.path("kind").asText(""))
                    || !operation.equals(source.path("operation").asText(""))
                    || !sameResourcePath(resourcePath, source.path("resource").asText(""))
                    || !eligibleComparisonProjection(projection, capabilityFields)) {
                continue;
            }
            return Optional.of(sanitizeAnalyticsProjection(projection));
        }
        return Optional.empty();
    }

    private boolean sameResourcePath(String left, String right) {
        return value(left).replaceAll("/+$", "").equals(value(right).replaceAll("/+$", ""));
    }

    private boolean eligibleComparisonProjection(
            JsonNode projection,
            List<StatsCapabilityFieldDescriptor> capabilityFields) {
        if (!"comparison".equals(projection.path("intent").asText(""))) {
            return false;
        }
        JsonNode bindings = projection.path("bindings");
        String dimensionField = bindings.path("primaryDimension").path("field").asText("");
        String periodField = bindings.path("comparisonPeriod").path("field").asText("");
        JsonNode period = bindings.path("comparisonPeriod");
        JsonNode metrics = bindings.path("primaryMetrics");
        if (dimensionField.isBlank()
                || periodField.isBlank()
                || period.path("timezone").asText("").isBlank()
                || period.path("preset").asText("").isBlank()
                || period.path("mode").asText("").isBlank()
                || !metrics.isArray()
                || metrics.isEmpty()) {
            return false;
        }
        boolean dimensionEligible = capabilityFields.stream()
                .anyMatch(field -> normalize(field.field()).equals(normalize(dimensionField))
                        && eligibleStatsDimension(field, "comparison"));
        boolean periodEligible = capabilityFields.stream()
                .anyMatch(field -> normalize(field.field()).equals(normalize(periodField))
                        && (field.timeSeriesEligible() || field.modes().contains("time-series")));
        if (!dimensionEligible || !periodEligible) {
            return false;
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (JsonNode metric : metrics) {
            String fieldName = metric.path("field").asText("");
            String aggregation = normalize(metric.path("aggregation").asText("count")).replace('_', '-');
            if (fieldName.isBlank()
                    || !Set.of("count", "distinct-count", "sum").contains(aggregation)
                    || !aliases.add(normalize(fieldName))) {
                return false;
            }
            boolean metricEligible = capabilityFields.stream()
                    .anyMatch(field -> normalize(field.field()).equals(normalize(fieldName))
                            && field.metricFieldEligible()
                            && field.metrics().contains(aggregation));
            if (!metricEligible) {
                return false;
            }
        }
        return true;
    }

    private ObjectNode sanitizeAnalyticsProjection(JsonNode projection) {
        ObjectNode sanitized = objectMapper.createObjectNode();
        putText(sanitized, "id", projection.path("id").asText(""));
        putText(sanitized, "intent", projection.path("intent").asText(""));
        ObjectNode source = sanitized.putObject("source");
        copyTextFields(projection.path("source"), source, "kind", "resource", "operation");
        ObjectNode bindings = sanitized.putObject("bindings");
        copyAnalyticsBinding(projection.path("bindings"), bindings, "primaryDimension");
        copyAnalyticsMetrics(projection.path("bindings"), bindings, "primaryMetrics");
        copyAnalyticsMetrics(projection.path("bindings"), bindings, "secondaryMetrics");
        copyAnalyticsBinding(projection.path("bindings"), bindings, "comparisonPeriod");
        copyAnalyticsGovernance(projection.path("governance"), sanitized);
        copyAnalyticsDefaults(projection.path("defaults"), sanitized);
        copyAnalyticsPresentationHints(projection.path("presentationHints"), sanitized);
        copyAnalyticsInteractions(projection.path("interactions"), sanitized);
        return sanitized;
    }

    private void copyAnalyticsBinding(JsonNode source, ObjectNode target, String fieldName) {
        JsonNode binding = source.path(fieldName);
        if (!binding.isObject()) {
            return;
        }
        ObjectNode copy = target.putObject(fieldName);
        if ("comparisonPeriod".equals(fieldName)) {
            copyTextFields(binding, copy, "field", "timezone", "preset", "mode");
        } else {
            copyTextFields(binding, copy, "field", "role", "label", "keyFilterField");
        }
    }

    private void copyAnalyticsMetrics(JsonNode source, ObjectNode target, String fieldName) {
        JsonNode metrics = source.path(fieldName);
        if (!metrics.isArray() || metrics.isEmpty()) {
            return;
        }
        ArrayNode copy = target.putArray(fieldName);
        for (JsonNode metric : metrics) {
            ObjectNode item = copy.addObject();
            copyTextFields(metric, item, "field", "aggregation", "label");
        }
    }

    private void copyAnalyticsGovernance(JsonNode governance, ObjectNode target) {
        JsonNode policyRefs = governance.path("policyRefs");
        if (!policyRefs.isArray() || policyRefs.isEmpty()) {
            return;
        }
        ObjectNode governanceCopy = target.putObject("governance");
        ArrayNode refsCopy = governanceCopy.putArray("policyRefs");
        for (JsonNode policyRef : policyRefs) {
            ObjectNode refCopy = refsCopy.addObject();
            copyTextFields(policyRef, refCopy, "policyId", "policyVersion", "role", "resultField");
            JsonNode attestation = policyRef.path("attestation");
            if (attestation.isObject()) {
                copyTextFields(
                        attestation,
                        refCopy.putObject("attestation"),
                        "policyIdField",
                        "policyVersionField");
            }
        }
    }

    private void copyAnalyticsDefaults(JsonNode defaults, ObjectNode target) {
        if (!defaults.isObject()) {
            return;
        }
        ObjectNode defaultsCopy = target.putObject("defaults");
        if (defaults.path("limit").canConvertToInt()) {
            defaultsCopy.put("limit", defaults.path("limit").asInt());
        }
        putText(defaultsCopy, "granularity", defaults.path("granularity").asText(""));
        JsonNode sort = defaults.path("sort");
        if (sort.isArray() && !sort.isEmpty()) {
            ArrayNode sortCopy = defaultsCopy.putArray("sort");
            for (JsonNode item : sort) {
                copyTextFields(item, sortCopy.addObject(), "field", "direction");
            }
        }
    }

    private void copyAnalyticsPresentationHints(JsonNode hints, ObjectNode target) {
        JsonNode families = hints.path("preferredFamilies");
        if (!families.isArray() || families.isEmpty()) {
            return;
        }
        ArrayNode familiesCopy = target.putObject("presentationHints").putArray("preferredFamilies");
        for (JsonNode family : families) {
            if (family.isTextual() && !family.asText("").isBlank()) {
                familiesCopy.add(family.asText());
            }
        }
    }

    private void copyAnalyticsInteractions(JsonNode interactions, ObjectNode target) {
        if (!interactions.isObject()) {
            return;
        }
        ObjectNode copy = target.putObject("interactions");
        for (String field : List.of("drillDown", "pointSelection", "crossFilter")) {
            if (interactions.has(field) && interactions.path(field).isBoolean()) {
                copy.put(field, interactions.path(field).asBoolean());
            }
        }
        JsonNode recordOpen = interactions.path("recordOpen");
        if (recordOpen.isObject()) {
            ObjectNode recordOpenCopy = copy.putObject("recordOpen");
            putText(recordOpenCopy, "sourceIdentityField", recordOpen.path("sourceIdentityField").asText(""));
            ObjectNode targetCopy = recordOpenCopy.putObject("target");
            putText(targetCopy, "resourceKey", recordOpen.path("target").path("resourceKey").asText(""));
            putText(targetCopy, "surfaceId", recordOpen.path("target").path("surfaceId").asText(""));
        }
    }

    private void copyTextFields(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            putText(target, field, source.path(field).asText(""));
        }
    }

    private void putText(ObjectNode target, String field, String text) {
        if (target != null && text != null && !text.isBlank()) {
            target.put(field, text.trim());
        }
    }

    private String fetchStatus(ResourceCapabilitiesFetchResult result) {
        return result == null || result.getStatus() == null
                ? "unavailable"
                : result.getStatus().name().toLowerCase(Locale.ROOT);
    }

    private String surfaceFetchStatus(ResourceSurfaceCatalogFetchResult result) {
        return result == null || result.getStatus() == null
                ? "unavailable"
                : result.getStatus().name().toLowerCase(Locale.ROOT);
    }

    private String schemaFetchStatus(SchemaFetchResult result) {
        return result == null || result.getStatus() == null
                ? "unavailable"
                : result.getStatus().name().toLowerCase(Locale.ROOT);
    }

    private AgenticAuthoringPlanRequest copyWithContextHints(
            AgenticAuthoringPlanRequest request,
            JsonNode contextHints) {
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

    private AgenticAuthoringPlanRequest withSchemaFieldContext(
            AgenticAuthoringPlanRequest request,
            String schemaBaseUrl,
            PreviewSchemaFetchCache schemaFetchCache) {
        if (request == null
                || schemaRetrievalService == null
                || !shouldEnrichSchemaFieldsForGenericMaterialization(request)
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
        SchemaFetchResult schemaResult = schemaFetchCache.fetch(schemaContext, schemaBaseUrl);
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

    private boolean shouldEnrichSchemaFieldsForGenericMaterialization(AgenticAuthoringPlanRequest request) {
        AgenticAuthoringIntentResolutionResult intent = request == null ? null : request.intentResolution();
        if (intent == null) {
            return false;
        }
        if ("modify".equals(value(intent.operationKind()))
                && "table".equals(value(intent.artifactKind()))
                && Set.of(
                        "column.add",
                        "add_column",
                        "column.filterable.set",
                        "filter.advanced.configure",
                        "filter.advanced.fields.add",
                        "filter.advanced.fields.remove")
                .contains(value(intent.changeKind()))) {
            return true;
        }
        if (intent.visualizationDecision() != null) {
            return false;
        }
        String artifactKind = value(intent.artifactKind());
        if (!List.of("dashboard", "page", "table").contains(artifactKind)) {
            return false;
        }
        if (isDashboardQualityRepairRequest(request.contextHints())) {
            return true;
        }
        String prompt = value(request.userPrompt());
        return containsAny(prompt,
                "dashboard", "painel", "visao geral", "visao 360", "overview", "360", "grafico", "graficos",
                "chart", "charts", "indicador", "indicadores", "kpi", "kpis");
    }

    private boolean isDashboardQualityRepairRequest(JsonNode contextHints) {
        if (contextHints == null || !contextHints.isObject()) {
            return false;
        }
        String source = value(contextHints.path("source").asText());
        String kind = value(contextHints.path("kind").asText());
        return "dashboard-quality-gate".equals(source)
                || "dashboard-repair-action".equals(kind)
                || contextHints.path("dashboardQuality").isObject();
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
            String schemaBaseUrl,
            PreviewSchemaFetchCache schemaFetchCache) {
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
        SchemaFetchResult schemaResult = schemaFetchCache.fetch(schemaContext, schemaBaseUrl);
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
            materializeTableColumnsFromSchema(objectNode, schemaFields, warnings);
            return objectNode;
        }
        return copy;
    }

    private void materializeTableColumnsFromSchema(
            ObjectNode uiCompositionPlan,
            Map<String, SchemaFieldDescriptor> schemaFields,
            List<String> warnings) {
        if (schemaFields == null || schemaFields.isEmpty()) {
            return;
        }
        JsonNode widgets = uiCompositionPlan.path("widgets");
        if (!(widgets instanceof ArrayNode widgetArray)) {
            return;
        }
        boolean materialized = false;
        for (JsonNode widget : widgetArray) {
            if (!"praxis-table".equals(widget.path("componentId").asText(""))) {
                continue;
            }
            if (!(widget.path("inputs") instanceof ObjectNode inputsObject)) {
                continue;
            }
            ObjectNode configObject = inputsObject.path("config") instanceof ObjectNode existingConfig
                    ? existingConfig
                    : inputsObject.putObject("config");
            JsonNode existingColumns = configObject.path("columns");
            if (existingColumns.isArray() && !existingColumns.isEmpty()) {
                continue;
            }
            ArrayNode columns = configObject.putArray("columns");
            schemaFields.values().stream()
                    .filter(this::isDefaultTableProjectionField)
                    .limit(16)
                    .map(this::tableColumnFromSchemaField)
                    .forEach(columns::add);
            if (!columns.isEmpty()) {
                materialized = true;
            }
        }
        if (materialized) {
            addWarningOnce(warnings, "table-columns-materialized-from-schema");
        }
    }

    private boolean isDefaultTableProjectionField(SchemaFieldDescriptor field) {
        return field != null && !field.hidden() && !field.tableHidden();
    }

    private ObjectNode tableColumnFromSchemaField(SchemaFieldDescriptor field) {
        ObjectNode column = objectMapper.createObjectNode();
        column.put("field", field.name());
        column.put("header", firstNonBlank(field.label(), field.name()));
        String type = tableColumnType(field);
        if (!type.isBlank()) {
            column.put("type", type);
        }
        return column;
    }

    private String tableColumnType(SchemaFieldDescriptor field) {
        String format = value(field.format());
        if ("date".equals(format)) {
            return "date";
        }
        if ("date-time".equals(format) || "datetime".equals(format)) {
            return "datetime";
        }
        String type = value(field.type());
        return switch (type) {
            case "integer", "number" -> "number";
            case "boolean" -> "boolean";
            case "string" -> "string";
            default -> "";
        };
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
            String schemaBaseUrl,
            PreviewSchemaFetchCache schemaFetchCache) {
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
        SchemaFetchResult schemaResult = schemaFetchCache.fetch(schemaContext, schemaBaseUrl);
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
                filterSchemaFields(request, schemaBaseUrl, schemaFetchCache).orElse(schemaFields);
        Map<String, SchemaFieldDescriptor> statsRequestFields =
                statsRequestSchemaFields(candidate, schemaBaseUrl, schemaFetchCache).orElse(Map.of());
        JsonNode copy = uiCompositionPlan == null ? MissingNode.getInstance() : uiCompositionPlan.deepCopy();
        if (copy instanceof ObjectNode objectNode) {
            reconcileSemanticAxesWithSchema(
                    request,
                    objectNode,
                    schemaFields,
                    filterSchemaFields,
                    statsRequestFields,
                    schemaResult,
                    warnings,
                    allowsSchemaSafeAxisRepair(request));
            return objectNode;
        }
        return copy;
    }

    private JsonNode normalizeCountMetricBindings(
            JsonNode uiCompositionPlan,
            List<String> warnings) {
        if (uiCompositionPlan == null
                || uiCompositionPlan.isMissingNode()
                || !containsComponent(uiCompositionPlan, "praxis-chart")) {
            return uiCompositionPlan;
        }
        JsonNode copy = uiCompositionPlan == null ? MissingNode.getInstance() : uiCompositionPlan.deepCopy();
        JsonNode widgets = copy.path("widgets");
        if (!(widgets instanceof ArrayNode widgetArray)) {
            return copy;
        }
        boolean normalized = false;
        for (JsonNode widget : widgetArray) {
            if (!"praxis-chart".equals(widget.path("componentId").asText(""))) {
                continue;
            }
            JsonNode config = widget.path("inputs").path("config");
            JsonNode series = config.path("series");
            if (series.isArray()) {
                for (JsonNode item : series) {
                    JsonNode metric = item.path("metric");
                    if (metric instanceof ObjectNode metricObject
                            && "count".equals(normalize(metricObject.path("aggregation").asText("")))) {
                        metricObject.put("field", "total");
                        metricObject.put("schemaVerified", true);
                        metricObject.put("schemaProbeStatus", "derived-record-count");
                        normalized = true;
                    }
                }
            }
            JsonNode queryMetrics = config.path("dataSource").path("query").path("metrics");
            if (queryMetrics.isArray()) {
                for (JsonNode metric : queryMetrics) {
                    if (metric instanceof ObjectNode metricObject
                            && "count".equals(normalize(metricObject.path("aggregation").asText("")))) {
                        metricObject.remove(List.of("field", "schemaVerified", "schemaProbeStatus"));
                        metricObject.put("alias", "total");
                        normalized = true;
                    }
                }
            }
            JsonNode statsMetric = config.path("dataSource").path("query").path("statsRequest").path("metric");
            if (statsMetric instanceof ObjectNode metricObject
                    && "count".equals(normalize(metricObject.path("operation").asText("")))) {
                metricObject.remove(List.of("field", "schemaVerified", "schemaProbeStatus"));
                metricObject.put("alias", "total");
                normalized = true;
            }
        }
        if (normalized) {
            addWarningOnce(warnings, "semantic-chart-count-metric-normalized-for-stats-contract");
            addWarningOnce(warnings, "semantic-chart-count-metric-preserved-for-record-count");
            warnings.remove("semantic-chart-metric-schema-verification-unsupported-field");
        }
        return copy;
    }

    private JsonNode groundTableQueryContextFilters(
            AgenticAuthoringPlanRequest request,
            JsonNode uiCompositionPlan,
            List<String> warnings,
            String schemaBaseUrl,
            PreviewSchemaFetchCache schemaFetchCache) {
        if (!containsComponent(uiCompositionPlan, "praxis-table")) {
            return uiCompositionPlan;
        }
        Optional<Map<String, SchemaFieldDescriptor>> filterFields =
                filterSchemaFields(request, schemaBaseUrl, schemaFetchCache);
        if (filterFields.isEmpty() || filterFields.get().isEmpty()) {
            return uiCompositionPlan;
        }
        JsonNode copy = uiCompositionPlan.deepCopy();
        if (copy.path("widgets") instanceof ArrayNode widgets) {
            for (JsonNode widget : widgets) {
                alignTableQueryContextFilters(widget, filterFields.get(), null, warnings);
            }
        }
        return copy;
    }

    private VisibleTableQueryFilterMaterialization materializeVisibleTableQueryFilters(
            AgenticAuthoringPlanRequest request,
            JsonNode uiCompositionPlan,
            List<String> warnings,
            String schemaBaseUrl,
            PreviewSchemaFetchCache schemaFetchCache) {
        if (componentEditPlanService == null
                || !(uiCompositionPlan instanceof ObjectNode plan)
                || !containsComponent(plan, "praxis-table")) {
            return VisibleTableQueryFilterMaterialization.success(uiCompositionPlan);
        }
        Map<String, SchemaFieldDescriptor> filterFields = filterSchemaFields(
                request,
                schemaBaseUrl,
                schemaFetchCache).orElse(Map.of());
        if (filterFields.isEmpty()) {
            return VisibleTableQueryFilterMaterialization.success(uiCompositionPlan);
        }

        ObjectNode materializedPlan = plan.deepCopy();
        List<String> failureCodes = new ArrayList<>();
        boolean materialized = false;
        for (JsonNode widget : materializedPlan.path("widgets")) {
            if (!(widget instanceof ObjectNode widgetObject)
                    || !"praxis-table".equals(widget.path("componentId").asText(""))) {
                continue;
            }
            ObjectNode inputs = widgetObject.path("inputs") instanceof ObjectNode existingInputs
                    ? existingInputs
                    : null;
            ObjectNode queryFilters = inputs != null
                    && inputs.path("queryContext").path("filters") instanceof ObjectNode existingFilters
                    ? existingFilters
                    : null;
            if (inputs == null || queryFilters == null || queryFilters.isEmpty()) {
                continue;
            }
            List<String> visibleFields = new ArrayList<>();
            queryFilters.fieldNames().forEachRemaining(field -> {
                if (filterFields.values().stream().anyMatch(candidate -> candidate.name().equals(field))) {
                    visibleFields.add(field);
                }
            });
            if (visibleFields.size() != queryFilters.size()) {
                failureCodes.add("table-query-filter-visible-field-grounding-incomplete");
                continue;
            }

            ObjectNode operationPlan = visibleAdvancedFilterOperationPlan(visibleFields);
            ObjectNode validationContext = visibleAdvancedFilterValidationContext(filterFields);
            AgenticAuthoringComponentEditPlanResult compilation =
                    componentEditPlanService.compileGovernedPlan(
                            "praxis-table",
                            inputs,
                            operationPlan,
                            validationContext);
            if (!compilation.valid()
                    || !compilation.compiledPatch().path("proposedConfig").isObject()) {
                failureCodes.add("table-query-filter-visible-manifest-compilation-failed");
                addAllOnce(warnings, compilation.warnings());
                continue;
            }
            widgetObject.set("inputs", compilation.compiledPatch().path("proposedConfig").deepCopy());
            materialized = true;
            appendVisibleTableFilterDiagnostics(
                    materializedPlan,
                    widgetObject.path("key").asText(""),
                    visibleFields,
                    compilation.compiledPatch().path("manifestVersion").asText(""));
        }
        if (materialized) {
            addWarningOnce(warnings, "table-query-filter-visible-through-authoring-manifest");
        }
        return new VisibleTableQueryFilterMaterialization(
                materializedPlan,
                List.copyOf(new LinkedHashSet<>(failureCodes)));
    }

    private ObjectNode visibleAdvancedFilterOperationPlan(List<String> visibleFields) {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("schemaVersion", AgenticAuthoringComponentEditPlanService.PLAN_SCHEMA_VERSION);
        plan.put("componentId", "praxis-table");
        ObjectNode operation = plan.putArray("operations").addObject();
        operation.put("operationId", "filter.advanced.configure");
        ObjectNode input = operation.putObject("input");
        input.put("enabled", true);
        input.put("queryBuilder", true);
        input.put("savePresets", false);
        ObjectNode settings = input.putObject("settings");
        settings.put("mode", "filter");
        settings.put("showAdvanced", true);
        ArrayNode alwaysVisibleFields = settings.putArray("alwaysVisibleFields");
        ArrayNode selectedFieldIds = settings.putArray("selectedFieldIds");
        visibleFields.forEach(field -> {
            alwaysVisibleFields.add(field);
            selectedFieldIds.add(field);
        });
        return plan;
    }

    private ObjectNode visibleAdvancedFilterValidationContext(
            Map<String, SchemaFieldDescriptor> filterFields) {
        ObjectNode context = objectMapper.createObjectNode();
        ArrayNode fields = context.putArray("filterSchemaFields");
        filterFields.values().forEach(field -> fields.addObject()
                .put("name", field.name())
                .put("label", firstNonBlank(field.label(), field.name()))
                .put("type", field.type())
                .put("format", field.format()));
        return context;
    }

    private void appendVisibleTableFilterDiagnostics(
            ObjectNode plan,
            String widgetKey,
            List<String> visibleFields,
            String manifestVersion) {
        ObjectNode diagnostics = plan.path("diagnostics") instanceof ObjectNode existing
                ? existing
                : plan.putObject("diagnostics");
        ArrayNode projections = diagnostics.path("visibleTableQueryFilters") instanceof ArrayNode existing
                ? existing
                : diagnostics.putArray("visibleTableQueryFilters");
        ObjectNode projection = projections.addObject();
        projection.put("source", "semantic-decision.constraints+praxis-table-authoring-manifest");
        projection.put("operationId", "filter.advanced.configure");
        projection.put("widgetKey", widgetKey);
        projection.put("manifestVersion", manifestVersion);
        ArrayNode fields = projection.putArray("fields");
        visibleFields.forEach(fields::add);
    }

    private JsonNode verifyStatsCapabilities(
            AgenticAuthoringPlanRequest request,
            JsonNode uiCompositionPlan,
            List<String> warnings,
            String requestBaseUrl,
            String tenantId,
            String userId,
            String environment,
            PreviewResourceCapabilitiesFetchCache capabilitiesFetchCache) {
        if (resourceCapabilitiesRetrievalService == null
                || uiCompositionPlan == null
                || uiCompositionPlan.isMissingNode()
                || !containsComponent(uiCompositionPlan, "praxis-chart")) {
            return uiCompositionPlan;
        }
        AgenticAuthoringCandidate candidate = request == null || request.intentResolution() == null
                ? null
                : request.intentResolution().selectedCandidate();
        String resourcePath = businessResourcePath(firstNonBlank(
                candidate == null ? "" : candidate.resourcePath(),
                candidate == null ? "" : candidate.submitUrl()));
        JsonNode copy = uiCompositionPlan.deepCopy();
        if (!(copy instanceof ObjectNode objectNode)) {
            return copy;
        }
        if (resourcePath.isBlank()) {
            markStatsAxesUnsupported(objectNode, "invalid-resource", warnings);
            return objectNode;
        }

        ResourceCapabilitiesFetchResult result = capabilitiesFetchCache.fetch(
                resourcePath,
                requestBaseUrl,
                tenantId,
                userId,
                environment);
        if (result == null || !result.isSuccess()) {
            String status = result == null || result.getStatus() == null
                    ? "unavailable"
                    : result.getStatus().name().toLowerCase(Locale.ROOT);
            markStatsAxesUnsupported(objectNode, status, warnings);
            return objectNode;
        }

        List<StatsCapabilityFieldDescriptor> capabilityFields = statsCapabilityFields(result.getCapabilities());
        if (capabilityFields.isEmpty()) {
            markStatsAxesUnsupported(objectNode, "no-fields", warnings);
            return objectNode;
        }
        int groundedCharts = 0;
        JsonNode widgets = objectNode.path("widgets");
        if (widgets.isArray()) {
            for (JsonNode widget : widgets) {
                if (!(widget instanceof ObjectNode widgetObject) || !isPraxisStatsChart(widgetObject)) {
                    continue;
                }
                ObjectNode config = widgetObject.path("inputs").path("config") instanceof ObjectNode value
                        ? value
                        : null;
                ObjectNode query = config != null && config.path("dataSource").path("query") instanceof ObjectNode value
                        ? value
                        : null;
                ObjectNode statsRequest = query != null && query.path("statsRequest") instanceof ObjectNode value
                        ? value
                        : null;
                ObjectNode semanticAxis = config != null && config.path("semanticAxis") instanceof ObjectNode value
                        ? value
                        : null;
                if (config == null || query == null || statsRequest == null || semanticAxis == null) {
                    markStatsAxisUnsupported(objectNode, semanticAxis, "invalid-chart-contract");
                    continue;
                }
                String operation = valueOrDefault(query.path("statsOperation").asText(""), "group-by");
                if ("comparison".equals(normalize(operation))
                        && !isCanonicalStatsOperationAvailableForPrincipal(
                                result.getCapabilities(),
                                "comparison")) {
                    markStatsAxisUnsupported(objectNode, semanticAxis, "operation-unavailable");
                    continue;
                }
                Optional<StatsCapabilityFieldDescriptor> dimension = resolveStatsDimensionCapability(
                        semanticAxis,
                        statsRequest.path("field").asText(""),
                        operation,
                        capabilityFields);
                if (dimension.isEmpty()) {
                    markStatsAxisUnsupported(objectNode, semanticAxis, "unsupported-dimension");
                    continue;
                }
                if ("comparison".equals(normalize(operation))
                        && !alignComparisonPeriodCapability(statsRequest, capabilityFields)) {
                    markStatsAxisUnsupported(objectNode, semanticAxis, "unsupported-comparison-period");
                    continue;
                }
                if (!alignStatsMetricCapability(config, capabilityFields)) {
                    markStatsAxisUnsupported(objectNode, semanticAxis, "unsupported-metric");
                    continue;
                }
                alignStatsExecutionField(query, statsRequest, dimension.get());
                markStatsAxisVerified(objectNode, semanticAxis, dimension.get(), result);
                groundedCharts++;
            }
        }
        int expectedCharts = countPraxisStatsCharts(objectNode);
        markResourceStatsGrounding(
                objectNode,
                resourcePath,
                result,
                capabilityFields.size(),
                groundedCharts,
                expectedCharts);
        if (groundedCharts < expectedCharts) {
            addWarningOnce(warnings, "semantic-axis-stats-capability-verification-unsupported");
        } else if (expectedCharts > 0) {
            addWarningOnce(warnings, "semantic-axis-stats-capability-verified");
        }
        return objectNode;
    }

    private JsonNode reconcileChartInteractionsWithGrounding(
            AgenticAuthoringPlanRequest request,
            JsonNode uiCompositionPlan,
            List<String> warnings,
            String schemaBaseUrl,
            PreviewSchemaFetchCache schemaFetchCache) {
        if (!(uiCompositionPlan instanceof ObjectNode plan)
                || schemaFetchCache == null
                || !containsComponent(plan, "praxis-chart")) {
            return uiCompositionPlan;
        }
        Map<String, SchemaFieldDescriptor> filterFields = filterSchemaFields(
                request,
                schemaBaseUrl,
                schemaFetchCache).orElse(Map.of());
        boolean reconciled = false;
        JsonNode widgets = plan.path("widgets");
        if (!widgets.isArray()) {
            return plan;
        }
        for (JsonNode widget : widgets) {
            if (!(widget instanceof ObjectNode chart)
                    || !"praxis-chart".equals(chart.path("componentId").asText(""))) {
                continue;
            }
            Optional<ChartInteractionProjection> projection = chartInteractionProjection(chart, filterFields);
            if (projection.isEmpty()) {
                JsonNode semanticAxis = chart.path("inputs").path("config").path("semanticAxis");
                if (isTimeseriesChart(chart)) {
                    String chartKey = firstNonBlank(chart.path("key").asText(""), chart.path("id").asText(""));
                    if (!chartKey.isBlank()) {
                        disableUngroundedTemporalFilterInteractions(plan, chart, chartKey);
                    }
                    addWarningOnce(warnings, "semantic-chart-temporal-range-filter-target-unresolved");
                }
                if (semanticAxis.path("statsEvidence").path("keyAndLabelDistinct").asBoolean(false)) {
                    addWarningOnce(warnings, "semantic-chart-interaction-key-filter-target-unresolved");
                }
                continue;
            }
            ChartInteractionProjection resolved = projection.get();
            reconciled = reconcileChartEventMappings(chart, resolved) || reconciled;
            String chartKey = firstNonBlank(chart.path("key").asText(""), chart.path("id").asText(""));
            if (!chartKey.isBlank()) {
                reconciled = reconcileChartInteractionBindings(plan, chartKey, resolved) || reconciled;
            }
            reconciled = reconcileItemTemplateFields(plan, resolved) || reconciled;
        }
        if (reconciled) {
            addWarningOnce(warnings, "semantic-chart-interactions-grounded");
        }
        return plan;
    }

    private boolean isTimeseriesChart(ObjectNode chart) {
        return chart != null && "timeseries".equalsIgnoreCase(chart.path("inputs").path("config")
                .path("dataSource").path("query").path("statsOperation").asText(""));
    }

    private void disableUngroundedTemporalFilterInteractions(
            ObjectNode plan,
            ObjectNode chart,
            String chartKey) {
        JsonNode interactions = chart.path("inputs").path("config").path("interactions");
        if (interactions instanceof ObjectNode interactionConfig && interactionConfig.has("crossFilter")) {
            interactionConfig.put("crossFilter", false);
        }
        JsonNode eventActions = interactions.path("eventActions");
        if (eventActions instanceof ObjectNode actions) {
            actions.remove("crossFilter");
        }
        removeUngroundedTemporalFilterLinks(plan.path("bindings"), chartKey);
        removeUngroundedTemporalFilterLinks(plan.path("composition").path("links"), chartKey);
    }

    private boolean removeUngroundedTemporalFilterLinks(JsonNode links, String chartKey) {
        if (!(links instanceof ArrayNode array)) {
            return false;
        }
        boolean changed = false;
        for (int index = array.size() - 1; index >= 0; index--) {
            JsonNode link = array.path(index);
            JsonNode from = link.path("from");
            if (!chartKey.equals(bindingWidgetKey(from))) {
                continue;
            }
            boolean queryContextTarget = "queryContext".equals(bindingPort(link.path("to")));
            boolean filteredSurfaceTarget = surfacePayloadContainsQueryContextFilter(link.path("to"));
            if (queryContextTarget || filteredSurfaceTarget) {
                array.remove(index);
                changed = true;
            }
        }
        return changed;
    }

    private boolean surfacePayloadContainsQueryContextFilter(JsonNode to) {
        ObjectNode payload = surfaceOpenPayload(to);
        if (payload == null || !payload.path("bindings").isArray()) {
            return false;
        }
        for (JsonNode binding : payload.path("bindings")) {
            if (binding.path("to").asText("").contains(".queryContext.filters.")) {
                return true;
            }
        }
        return false;
    }

    private Optional<ChartInteractionProjection> chartInteractionProjection(
            ObjectNode chart,
            Map<String, SchemaFieldDescriptor> filterFields) {
        ObjectNode config = chart.path("inputs").path("config") instanceof ObjectNode chartConfig
                ? chartConfig
                : null;
        ObjectNode semanticAxis = config != null && config.path("semanticAxis") instanceof ObjectNode axis
                ? axis
                : null;
        if (config == null || semanticAxis == null || !semanticAxis.path("schemaVerified").asBoolean(false)) {
            return Optional.empty();
        }
        String displayField = semanticAxis.path("field").asText("").trim();
        if (displayField.isBlank()) {
            return Optional.empty();
        }
        boolean keyAndLabelDistinct = semanticAxis.path("statsEvidence")
                .path("keyAndLabelDistinct")
                .asBoolean(false);
        boolean timeseries = "timeseries".equalsIgnoreCase(config
                .path("dataSource").path("query").path("statsOperation").asText(""));
        boolean governedKeyFilterRequired = config.path("analyticsProjection")
                .path("interactions").path("crossFilter").asBoolean(false);
        String governedKeyFilterField = config.path("analyticsProjection")
                .path("bindings").path("primaryDimension").path("keyFilterField")
                .asText("").trim();
        if (governedKeyFilterRequired && governedKeyFilterField.isBlank()) {
            return Optional.empty();
        }
        LinkedHashSet<String> axisFields = new LinkedHashSet<>();
        addNonBlank(axisFields, displayField);
        addNonBlank(axisFields, semanticAxis.path("requestedField").asText(""));
        addNonBlank(axisFields, semanticAxis.path("concept").asText(""));
        addNonBlank(axisFields, semanticAxis.path("statsExecutionField").asText(""));
        LinkedHashSet<String> explicitMappingSourceFields = new LinkedHashSet<>(axisFields);
        if (timeseries) {
            addNonBlank(explicitMappingSourceFields, "start");
            addNonBlank(explicitMappingSourceFields, "end");
        }
        Set<String> explicitTargets = explicitChartInteractionTargets(chart, explicitMappingSourceFields);
        if (explicitTargets.size() > 1) {
            return Optional.empty();
        }
        String explicitTarget = governedKeyFilterField.isBlank()
                ? explicitTargets.stream().findFirst().orElse("")
                : governedKeyFilterField;
        SchemaFieldDescriptor target = explicitTarget.isBlank()
                ? null
                : filterFields.get(normalize(explicitTarget));
        if (!explicitTarget.isBlank()
                && (target == null
                || !explicitTarget.equals(target.name())
                || (!governedKeyFilterField.isBlank() && !supportsBucketKey(target)))) {
            return Optional.empty();
        }
        SchemaFieldDescriptor probe = chartInteractionFilterProbe(semanticAxis);
        if (target == null && timeseries) {
            target = preferredTemporalRangeFilterField(probe, filterFields).orElse(null);
            if (target == null) {
                return Optional.empty();
            }
        }
        if (target == null && keyAndLabelDistinct) {
            target = preferredFilterInputField(probe, filterFields).orElse(null);
            if (target == null || normalize(target.name()).equals(normalize(displayField))) {
                return Optional.empty();
            }
        } else if (target == null) {
            target = filterFields.get(normalize(displayField));
            if (target == null) {
                String requestedField = semanticAxis.path("requestedField").asText("");
                SchemaFieldDescriptor requestedTarget = filterFields.get(normalize(requestedField));
                if (requestedTarget != null && !requestedTarget.multiple()
                        && !"array".equals(normalize(requestedTarget.type()))) {
                    target = requestedTarget;
                }
            }
            if (target == null && filterFields.isEmpty()) {
                target = chartInteractionFilterProbe(semanticAxis);
            }
            if (target == null || target.multiple() || "array".equals(normalize(target.type()))) {
                return Optional.empty();
            }
        }

        boolean temporalRangeTarget = isTemporalRangeFilterField(target);
        if (timeseries != temporalRangeTarget) {
            return Optional.empty();
        }
        ChartInteractionValueShape valueShape = temporalRangeTarget
                ? ChartInteractionValueShape.TEMPORAL_RANGE
                : target.multiple() || "array".equals(normalize(target.type()))
                        ? ChartInteractionValueShape.SINGLETON_ARRAY
                        : ChartInteractionValueShape.SCALAR;
        String sourceField = valueShape == ChartInteractionValueShape.TEMPORAL_RANGE
                ? "start"
                : !governedKeyFilterField.isBlank() || keyAndLabelDistinct ? "key" : displayField;
        String pointValuePath = valueShape == ChartInteractionValueShape.TEMPORAL_RANGE
                ? "payload.data.start"
                : !governedKeyFilterField.isBlank() || keyAndLabelDistinct
                ? "payload.data.key"
                : "payload.data." + displayField;
        return Optional.of(new ChartInteractionProjection(
                displayField,
                sourceField,
                target.name(),
                valueShape,
                pointValuePath,
                Set.copyOf(axisFields),
                governedCrossFilterTargetFields(chart, filterFields, axisFields)));
    }

    private boolean supportsBucketKey(SchemaFieldDescriptor field) {
        if (field == null) {
            return false;
        }
        boolean multiple = field.multiple() || "array".equals(normalize(field.type()));
        String valueType = normalize(multiple ? field.itemType() : field.type());
        return Set.of("string", "integer", "number", "boolean").contains(valueType);
    }

    private boolean supportsRecordIdentity(SchemaFieldDescriptor field) {
        if (field == null || field.multiple() || "array".equals(normalize(field.type()))) {
            return false;
        }
        return Set.of("string", "integer", "number").contains(normalize(field.type()));
    }

    private JsonNode findSurface(JsonNode surfaces, String surfaceId) {
        if (!surfaces.isArray() || surfaceId == null || surfaceId.isBlank()) {
            return MissingNode.getInstance();
        }
        for (JsonNode surface : surfaces) {
            if (surfaceId.equals(surface.path("id").asText(""))) {
                return surface;
            }
        }
        return MissingNode.getInstance();
    }

    private Set<String> governedCrossFilterTargetFields(
            ObjectNode chart,
            Map<String, SchemaFieldDescriptor> filterFields,
            Set<String> axisFields) {
        JsonNode mapping = chart.path("inputs").path("config")
                .path("interactions").path("eventActions").path("crossFilter").path("mapping");
        if (!mapping.isObject() || filterFields == null || filterFields.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        mapping.fields().forEachRemaining(entry -> {
            String target = entry.getValue().asText("");
            SchemaFieldDescriptor governed = filterFields.get(normalize(target));
            if (governed != null && !matchesAxisField(target, axisFields)) {
                targets.add(governed.name());
            }
        });
        return Set.copyOf(targets);
    }

    private Set<String> explicitChartInteractionTargets(
            ObjectNode chart,
            Set<String> axisFields) {
        JsonNode eventActions = chart.path("inputs").path("config")
                .path("interactions").path("eventActions");
        if (!eventActions.isObject()) {
            return Set.of();
        }
        LinkedHashSet<String> explicitTargets = new LinkedHashSet<>();
        for (JsonNode action : eventActions) {
            JsonNode mapping = action.path("mapping");
            if (!mapping.isObject()) {
                continue;
            }
            mapping.fields().forEachRemaining(entry -> {
                String source = entry.getKey();
                String target = entry.getValue().asText("");
                if (matchesAxisField(source, axisFields)
                        && !normalize(source).equals(normalize(target))
                        && !matchesAxisField(target, axisFields)) {
                    explicitTargets.add(target);
                }
            });
        }
        return Set.copyOf(explicitTargets);
    }

    private boolean matchesAxisField(String field, Set<String> axisFields) {
        if (field == null || field.isBlank() || axisFields == null) {
            return false;
        }
        String normalized = normalize(field);
        return axisFields.stream().map(this::normalize).anyMatch(normalized::equals);
    }

    private SchemaFieldDescriptor chartInteractionFilterProbe(ObjectNode semanticAxis) {
        String field = semanticAxis == null ? "" : semanticAxis.path("field").asText("");
        String label = semanticAxis == null ? "" : semanticAxis.path("label").asText("");
        return new SchemaFieldDescriptor(
                field,
                label,
                "",
                "",
                "",
                "",
                false,
                "",
                false,
                "",
                false,
                false,
                tokens(field),
                tokens(label),
                Set.of());
    }

    private void addNonBlank(Set<String> values, String candidate) {
        String resolved = value(candidate);
        if (!resolved.isBlank()) {
            values.add(resolved);
        }
    }

    private boolean reconcileChartEventMappings(
            ObjectNode chart,
            ChartInteractionProjection projection) {
        JsonNode eventActions = chart.path("inputs").path("config")
                .path("interactions").path("eventActions");
        if (!eventActions.isObject()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode action : eventActions) {
            if (!(action.path("mapping") instanceof ObjectNode mapping) || mapping.isEmpty()) {
                continue;
            }
            ObjectNode replacement = objectMapper.createObjectNode();
            var fields = mapping.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String source = entry.getKey();
                String target = entry.getValue().asText("");
                if (!isInteractionAxisField(source, projection)) {
                    replacement.set(source, entry.getValue().deepCopy());
                    continue;
                }
                String nextTarget = isInferredInteractionTarget(source, target, projection)
                        ? projection.targetField()
                        : target;
                replacement.put(projection.sourceField(), nextTarget);
            }
            if (!replacement.equals(mapping)) {
                mapping.removeAll();
                mapping.setAll(replacement);
                changed = true;
            }
        }
        return changed;
    }

    private boolean reconcileChartInteractionBindings(
            ObjectNode plan,
            String chartKey,
            ChartInteractionProjection projection) {
        JsonNode bindings = plan.path("bindings");
        if (!bindings.isArray()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode binding : bindings) {
            if (!(binding instanceof ObjectNode bindingObject)) {
                continue;
            }
            JsonNode from = bindingObject.path("from");
            if (!chartKey.equals(bindingWidgetKey(from))) {
                continue;
            }
            String fromPort = bindingPort(from);
            if ("pointClick".equals(fromPort)) {
                changed = alignInteractionPolicy(bindingObject, projection.pointValuePath()) || changed;
                if (projection.temporalRange()) {
                    changed = reconcileTemporalRangeCondition(bindingObject, "payload.data") || changed;
                }
                ObjectNode surfacePayload = surfaceOpenPayload(bindingObject.path("to"));
                if (surfacePayload != null) {
                    changed = reconcileSurfaceBindings(surfacePayload, projection) || changed;
                }
                if ("queryContext".equals(bindingPort(bindingObject.path("to")))) {
                    changed = reconcilePointQueryContextTransform(bindingObject, projection) || changed;
                }
            } else if ("crossFilter".equals(fromPort)
                    && "queryContext".equals(bindingPort(bindingObject.path("to")))) {
                String distinctBy = projection.temporalRange()
                        ? "payload.source.data.start"
                        : "payload.filters." + projection.targetField();
                changed = alignInteractionPolicy(
                        bindingObject,
                        distinctBy) || changed;
                if (projection.temporalRange()) {
                    changed = reconcileTemporalRangeCondition(bindingObject, "payload.source.data") || changed;
                }
                changed = reconcileCrossFilterQueryContextTransform(bindingObject, projection) || changed;
            }
        }
        return changed;
    }

    private ObjectNode surfaceOpenPayload(JsonNode to) {
        if (to == null || to.isMissingNode()) {
            return null;
        }
        String actionId = firstNonBlank(
                to.path("actionId").asText(""),
                to.path("ref").path("actionId").asText(""));
        if (!"surface.open".equals(actionId)) {
            return null;
        }
        JsonNode payload = to.path("payload");
        if (!(payload instanceof ObjectNode)) {
            payload = to.path("ref").path("payload");
        }
        return payload instanceof ObjectNode payloadObject ? payloadObject : null;
    }

    private boolean alignInteractionPolicy(ObjectNode binding, String distinctBy) {
        if (!(binding.path("policy") instanceof ObjectNode policy)
                || distinctBy == null
                || distinctBy.isBlank()
                || distinctBy.equals(policy.path("distinctBy").asText(""))) {
            return false;
        }
        policy.put("distinctBy", distinctBy);
        return true;
    }

    private boolean reconcileTemporalRangeCondition(ObjectNode binding, String dataPath) {
        String startPath = dataPath + ".start";
        String endPath = dataPath + ".end";
        JsonNode current = binding.path("condition");
        ArrayNode clauses = objectMapper.createArrayNode();
        if (current.path("and").isArray()) {
            current.path("and").forEach(clause -> clauses.add(clause.deepCopy()));
        } else if (!current.isMissingNode() && !current.isNull()) {
            clauses.add(current.deepCopy());
        }
        boolean hasStart = containsTemporalBoundaryClause(clauses, startPath);
        boolean hasEnd = containsTemporalBoundaryClause(clauses, endPath);
        if (hasStart && hasEnd && current.path("and").isArray()) {
            return false;
        }
        if (!hasStart) {
            clauses.add(temporalBoundaryCondition(startPath));
        }
        if (!hasEnd) {
            clauses.add(temporalBoundaryCondition(endPath));
        }
        binding.set("condition", objectMapper.createObjectNode().set("and", clauses));
        return true;
    }

    private ObjectNode temporalBoundaryCondition(String path) {
        ObjectNode truthy = objectMapper.createObjectNode();
        truthy.set("!!", objectMapper.createObjectNode().put("var", path));
        return truthy;
    }

    private boolean containsTemporalBoundaryClause(ArrayNode clauses, String path) {
        for (JsonNode clause : clauses) {
            if (path.equals(clause.path("!!").path("var").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private boolean reconcilePointQueryContextTransform(
            ObjectNode binding,
            ChartInteractionProjection projection) {
        ObjectNode template = bindingTransformTemplate(binding);
        if (template == null) {
            return false;
        }
        ObjectNode filters = template.path("filters") instanceof ObjectNode existing
                ? existing
                : template.putObject("filters");
        boolean changed = removeInferredInteractionFields(filters, projection);
        JsonNode nextValue = pointInteractionTemplateValue(projection);
        if (!nextValue.equals(filters.path(projection.targetField()))) {
            filters.set(projection.targetField(), nextValue);
            changed = true;
        }
        return changed;
    }

    private boolean reconcileCrossFilterQueryContextTransform(
            ObjectNode binding,
            ChartInteractionProjection projection) {
        ObjectNode template = bindingTransformTemplate(binding);
        if (template == null) {
            return false;
        }
        JsonNode existingFilters = template.path("filters");
        ObjectNode filters;
        boolean changed = false;
        if (existingFilters instanceof ObjectNode existing) {
            filters = existing;
        } else if ("${payload.filters}".equals(existingFilters.asText(""))) {
            filters = objectMapper.createObjectNode();
            for (String targetField : projection.crossFilterTargetFields()) {
                filters.put(targetField, "${payload.filters." + targetField + "}");
            }
            template.set("filters", filters);
            changed = true;
        } else if (existingFilters.isMissingNode() || existingFilters.isNull()) {
            filters = template.putObject("filters");
            changed = true;
        } else {
            return false;
        }
        changed = removeInferredInteractionFields(filters, projection) || changed;
        JsonNode nextValue = crossFilterInteractionTemplateValue(projection);
        if (!nextValue.equals(filters.path(projection.targetField()))) {
            filters.set(projection.targetField(), nextValue);
            changed = true;
        }
        return changed;
    }

    private ObjectNode bindingTransformTemplate(ObjectNode binding) {
        JsonNode transform = binding.path("transform");
        if (transform.path("template") instanceof ObjectNode directTemplate) {
            return directTemplate;
        }
        JsonNode steps = transform.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                JsonNode template = step.path("config").path("template");
                if (template instanceof ObjectNode objectNode) {
                    return objectNode;
                }
            }
        }
        return null;
    }

    private JsonNode pointInteractionTemplateValue(ChartInteractionProjection projection) {
        if (projection.temporalRange()) {
            return interactionTemplateArray("payload.data.start", "payload.data.end");
        }
        return interactionTemplateValue(projection.pointValuePath(), projection.targetMultiple());
    }

    private JsonNode crossFilterInteractionTemplateValue(ChartInteractionProjection projection) {
        if (projection.temporalRange()) {
            return interactionTemplateArray("payload.source.data.start", "payload.source.data.end");
        }
        return interactionTemplateValue(
                "payload.filters." + projection.targetField(),
                projection.targetMultiple());
    }

    private JsonNode interactionTemplateValue(String path, boolean multiple) {
        String expression = "${" + path + "}";
        if (!multiple) {
            return objectMapper.getNodeFactory().textNode(expression);
        }
        return interactionTemplateArray(path);
    }

    private ArrayNode interactionTemplateArray(String... paths) {
        ArrayNode values = objectMapper.createArrayNode();
        for (String path : paths) {
            values.add("${" + path + "}");
        }
        return values;
    }

    private boolean reconcileSurfaceBindings(
            ObjectNode surfacePayload,
            ChartInteractionProjection projection) {
        JsonNode bindings = surfacePayload.path("bindings");
        if (!bindings.isArray()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode binding : bindings) {
            if (!(binding instanceof ObjectNode bindingObject)) {
                continue;
            }
            String targetPath = bindingObject.path("to").asText("");
            int separator = targetPath.lastIndexOf('.');
            if (separator < 0) {
                continue;
            }
            String targetField = targetPath.substring(separator + 1);
            if (!isInteractionAxisField(targetField, projection)
                    && !normalize(targetField).equals(normalize(projection.targetField()))) {
                continue;
            }
            String nextTargetPath = targetPath.substring(0, separator + 1) + projection.targetField();
            if (!nextTargetPath.equals(targetPath)) {
                bindingObject.put("to", nextTargetPath);
                changed = true;
            }
            if (projection.targetMultiple()) {
                JsonNode nextValue = pointInteractionTemplateValue(projection);
                if (!"template".equals(bindingObject.path("mode").asText(""))
                        || !nextValue.equals(bindingObject.path("value"))
                        || bindingObject.has("from")) {
                    bindingObject.remove("from");
                    bindingObject.put("mode", "template");
                    bindingObject.set("value", nextValue);
                    changed = true;
                }
            } else if (!projection.pointValuePath().equals(bindingObject.path("from").asText(""))
                    || bindingObject.has("mode")
                    || bindingObject.has("value")) {
                bindingObject.put("from", projection.pointValuePath());
                bindingObject.remove(List.of("mode", "value"));
                changed = true;
            }
        }
        return changed;
    }

    private boolean removeInferredInteractionFields(
            ObjectNode filters,
            ChartInteractionProjection projection) {
        List<String> fieldsToRemove = new ArrayList<>();
        filters.fieldNames().forEachRemaining(field -> {
            if (isInteractionAxisField(field, projection)
                    && !normalize(field).equals(normalize(projection.targetField()))) {
                fieldsToRemove.add(field);
            }
        });
        if (fieldsToRemove.isEmpty()) {
            return false;
        }
        filters.remove(fieldsToRemove);
        return true;
    }

    private boolean reconcileItemTemplateFields(
            JsonNode node,
            ChartInteractionProjection projection) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        boolean changed = false;
        if (node instanceof ObjectNode objectNode) {
            JsonNode expression = objectNode.path("expr");
            if (expression.isTextual()) {
                String current = expression.asText("");
                for (String legacyField : projection.axisFields()) {
                    if (normalize(legacyField).equals(normalize(projection.displayField()))) {
                        continue;
                    }
                    String expected = "${item." + legacyField + "}";
                    if (expected.equals(current)) {
                        objectNode.put("expr", "${item." + projection.displayField() + "}");
                        changed = true;
                        break;
                    }
                }
            }
            var fields = objectNode.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                changed = reconcileItemTemplateFields(entry.getValue(), projection) || changed;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                changed = reconcileItemTemplateFields(item, projection) || changed;
            }
        }
        return changed;
    }

    private boolean isInferredInteractionTarget(
            String source,
            String target,
            ChartInteractionProjection projection) {
        return normalize(source).equals(normalize(target))
                || isInteractionAxisField(target, projection);
    }

    private boolean isInteractionAxisField(
            String field,
            ChartInteractionProjection projection) {
        if (field == null || field.isBlank() || projection == null) {
            return false;
        }
        String normalized = normalize(field);
        if (normalized.equals(normalize(projection.sourceField()))
                || normalized.equals(normalize(projection.displayField()))) {
            return true;
        }
        return projection.axisFields().stream()
                .map(this::normalize)
                .anyMatch(normalized::equals);
    }

    private boolean isPraxisStatsChart(ObjectNode widget) {
        return widget != null
                && "praxis-chart".equals(widget.path("componentId").asText(""))
                && "praxis.stats".equals(widget.path("inputs").path("config")
                        .path("dataSource").path("query").path("sourceKind").asText(""));
    }

    private int countPraxisStatsCharts(ObjectNode uiCompositionPlan) {
        int count = 0;
        JsonNode widgets = uiCompositionPlan.path("widgets");
        if (widgets.isArray()) {
            for (JsonNode widget : widgets) {
                if (widget instanceof ObjectNode widgetObject && isPraxisStatsChart(widgetObject)) {
                    count++;
                }
            }
        }
        return count;
    }

    private List<StatsCapabilityFieldDescriptor> statsCapabilityFields(JsonNode capabilities) {
        JsonNode root = capabilities == null ? MissingNode.getInstance() : capabilities;
        if (root.path("data").path("stats").isObject()) {
            root = root.path("data");
        }
        JsonNode fields = root.path("stats").path("fields");
        if (!fields.isArray()) {
            return List.of();
        }
        List<StatsCapabilityFieldDescriptor> result = new ArrayList<>();
        for (JsonNode field : fields) {
            String fieldName = field.path("field").asText("").trim();
            if (fieldName.isBlank()) {
                continue;
            }
            result.add(new StatsCapabilityFieldDescriptor(
                    fieldName,
                    field.path("label").asText("").trim(),
                    textValues(field.path("aliases")),
                    normalizedTextValues(field.path("metrics")),
                    normalizedTextValues(field.path("modes")),
                    field.path("groupByEligible").asBoolean(false),
                    field.path("timeSeriesEligible").asBoolean(false),
                    field.path("distributionTermsEligible").asBoolean(false),
                    field.path("distributionHistogramEligible").asBoolean(false),
                    field.path("metricFieldEligible").asBoolean(false),
                    field.path("keyAndLabelDistinct").asBoolean(false)));
        }
        return List.copyOf(result);
    }

    private Set<String> normalizedTextValues(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                String normalized = normalize(value.asText("")).replace('_', '-');
                if (!normalized.isBlank()) {
                    result.add(normalized);
                }
            }
        }
        return Set.copyOf(result);
    }

    private Set<String> textValues(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return Set.copyOf(result);
    }

    private Optional<StatsCapabilityFieldDescriptor> resolveStatsDimensionCapability(
            ObjectNode semanticAxis,
            String requestedExecutionField,
            String operation,
            List<StatsCapabilityFieldDescriptor> fields) {
        String requested = normalize(requestedExecutionField);
        Optional<StatsCapabilityFieldDescriptor> exactRequested = fields.stream()
                .filter(field -> eligibleStatsDimension(field, operation))
                .filter(field -> normalize(field.field()).equals(requested))
                .findFirst();
        if (exactRequested.isPresent()) {
            return exactRequested;
        }
        String semanticField = normalize(semanticAxis.path("field").asText(""));
        Optional<StatsCapabilityFieldDescriptor> exactSemantic = fields.stream()
                .filter(field -> eligibleStatsDimension(field, operation))
                .filter(field -> normalize(field.field()).equals(semanticField))
                .findFirst();
        if (exactSemantic.isPresent()) {
            return exactSemantic;
        }
        String originallyRequested = normalize(semanticAxis.path("requestedField").asText(""));
        Optional<StatsCapabilityFieldDescriptor> exactOriginallyRequested = fields.stream()
                .filter(field -> eligibleStatsDimension(field, operation))
                .filter(field -> normalize(field.field()).equals(originallyRequested))
                .findFirst();
        if (exactOriginallyRequested.isPresent()) {
            return exactOriginallyRequested;
        }
        List<StatsCapabilityFieldDescriptor> eligible = fields.stream()
                .filter(field -> eligibleStatsDimension(field, operation))
                .toList();
        int bestScore = 0;
        StatsCapabilityFieldDescriptor best = null;
        boolean ambiguous = false;
        for (StatsCapabilityFieldDescriptor field : eligible) {
            int score = statsCapabilityMatchScore(semanticAxis, field);
            if (score > bestScore) {
                bestScore = score;
                best = field;
                ambiguous = false;
            } else if (score > 0 && score == bestScore) {
                ambiguous = true;
            }
        }
        return !ambiguous && best != null && bestScore >= 4 ? Optional.of(best) : Optional.empty();
    }

    private int statsCapabilityMatchScore(
            ObjectNode semanticAxis,
            StatsCapabilityFieldDescriptor capability) {
        String axisLabel = normalize(semanticAxis.path("label").asText(""));
        String axisConcept = normalize(semanticAxis.path("concept").asText(""));
        String capabilityLabel = normalize(capability.label());
        // The semantic concept is the stable business meaning. Presentation labels may be
        // deliberately richer (for example "Funcionários por departamento") and DTO fields
        // may expose a display projection (departamentoNome), while the governed stats
        // capability executes through its canonical dimension (departamento).
        int score = (!axisLabel.isBlank() && axisLabel.equals(capabilityLabel))
                        || (!axisConcept.isBlank() && axisConcept.equals(capabilityLabel))
                ? 12
                : 0;
        Set<String> normalizedAliases = capability.aliases().stream()
                .map(this::normalize)
                .filter(alias -> !alias.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (normalizedAliases.contains(axisConcept)
                || normalizedAliases.contains(normalize(semanticAxis.path("field").asText("")))
                || normalizedAliases.contains(axisLabel)) {
            score = Math.max(score, 12);
        }
        Set<String> axisTokens = semanticAxisTokens(semanticAxis);
        Set<String> capabilityTokens = new LinkedHashSet<>(tokens(capability.field()));
        capabilityTokens.addAll(tokens(capability.label()));
        capability.aliases().forEach(alias -> capabilityTokens.addAll(tokens(alias)));
        for (String token : axisTokens) {
            if (capabilityTokens.contains(token)) {
                score += 2;
            }
        }
        return score;
    }

    private boolean eligibleStatsDimension(
            StatsCapabilityFieldDescriptor field,
            String operation) {
        String normalized = normalize(operation).replace('_', '-');
        return switch (normalized) {
            case "timeseries", "time-series" -> field.timeSeriesEligible()
                    || field.modes().contains("time-series");
            case "comparison" -> field.groupByEligible() || field.modes().contains("group-by");
            case "distribution" -> field.distributionTermsEligible()
                    || field.distributionHistogramEligible()
                    || field.modes().contains("distribution-terms")
                    || field.modes().contains("distribution-histogram");
            default -> field.groupByEligible() || field.modes().contains("group-by");
        };
    }

    private boolean alignStatsMetricCapability(
            ObjectNode config,
            List<StatsCapabilityFieldDescriptor> capabilityFields) {
        ObjectNode query = config.path("dataSource").path("query") instanceof ObjectNode value ? value : null;
        if (query != null && "comparison".equals(normalize(query.path("statsOperation").asText("")))) {
            return alignComparisonStatsMetricCapabilities(config, query, capabilityFields);
        }
        ObjectNode statsMetric = query != null && query.path("statsRequest").path("metric") instanceof ObjectNode value
                ? value
                : null;
        if (query == null || statsMetric == null) {
            return false;
        }
        String operation = normalize(statsMetric.path("operation").asText(""));
        if ("count".equals(operation)) {
            alignStatsCountMetric(query, statsMetric);
            return true;
        }
        String requestedField = statsMetric.path("field").asText("");
        Optional<StatsCapabilityFieldDescriptor> metricCapability = capabilityFields.stream()
                .filter(StatsCapabilityFieldDescriptor::metricFieldEligible)
                .filter(field -> normalize(field.field()).equals(normalize(requestedField)))
                .filter(field -> field.metrics().contains(operation))
                .findFirst();
        if (metricCapability.isEmpty()) {
            // A model-authored metric field that cannot be confirmed must not be replaced with a
            // different arbitrary schema field. COUNT is the canonical field-free aggregate and
            // preserves an executable chart without inventing financial or business semantics.
            alignStatsCountMetric(query, statsMetric);
            return true;
        }
        String canonicalField = metricCapability.get().field();
        statsMetric.put("field", canonicalField);
        JsonNode metrics = query.path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (metric instanceof ObjectNode metricObject
                        && normalize(metricObject.path("aggregation").asText("")).equals(operation)) {
                    metricObject.put("field", canonicalField);
                }
            }
        }
        return true;
    }

    private void alignStatsCountMetric(ObjectNode query, ObjectNode statsMetric) {
        statsMetric.put("operation", "COUNT");
        statsMetric.remove("field");
        statsMetric.put("alias", "total");
        JsonNode metrics = query.path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (metric instanceof ObjectNode metricObject) {
                    metricObject.put("aggregation", "count");
                    metricObject.remove("field");
                    metricObject.put("alias", "total");
                }
            }
        }
    }

    private boolean alignComparisonPeriodCapability(
            ObjectNode statsRequest,
            List<StatsCapabilityFieldDescriptor> capabilityFields) {
        if (statsRequest == null || statsRequest.path("metric").isObject()) {
            return false;
        }
        String requestedPeriodField = statsRequest.path("periodField").asText("");
        JsonNode period = statsRequest.path("period");
        if (requestedPeriodField.isBlank()
                || !period.isObject()
                || period.path("preset").asText("").isBlank()
                || period.path("timezone").asText("").isBlank()
                || period.path("mode").asText("").isBlank()) {
            return false;
        }
        Optional<StatsCapabilityFieldDescriptor> capability = capabilityFields.stream()
                .filter(field -> normalize(field.field()).equals(normalize(requestedPeriodField)))
                .filter(field -> field.timeSeriesEligible() || field.modes().contains("time-series"))
                .findFirst();
        if (capability.isEmpty()) {
            return false;
        }
        statsRequest.put("periodField", capability.get().field());
        return true;
    }

    private boolean alignComparisonStatsMetricCapabilities(
            ObjectNode config,
            ObjectNode query,
            List<StatsCapabilityFieldDescriptor> capabilityFields) {
        ObjectNode statsRequest = query.path("statsRequest") instanceof ObjectNode value ? value : null;
        JsonNode executionMetrics = statsRequest == null ? MissingNode.getInstance() : statsRequest.path("metrics");
        if (statsRequest == null
                || statsRequest.path("metric").isObject()
                || !executionMetrics.isArray()
                || executionMetrics.isEmpty()) {
            return false;
        }
        Set<String> aliases = new LinkedHashSet<>();
        Map<String, String> canonicalAliases = new LinkedHashMap<>();
        for (JsonNode metric : executionMetrics) {
            if (!(metric instanceof ObjectNode metricObject)) {
                return false;
            }
            String operation = normalize(metricObject.path("operation").asText("")).replace('_', '-');
            String alias = metricObject.path("alias").asText("").trim();
            String requestedField = "count".equals(operation)
                    ? alias
                    : metricObject.path("field").asText("").trim();
            if (!Set.of("count", "distinct-count", "sum").contains(operation)
                    || alias.isBlank()
                    || requestedField.isBlank()
                    || !aliases.add(normalize(alias))) {
                return false;
            }
            Optional<StatsCapabilityFieldDescriptor> capability = capabilityFields.stream()
                    .filter(StatsCapabilityFieldDescriptor::metricFieldEligible)
                    .filter(field -> normalize(field.field()).equals(normalize(requestedField)))
                    .filter(field -> field.metrics().contains(operation))
                    .findFirst();
            if (capability.isEmpty()) {
                return false;
            }
            String canonicalField = capability.get().field();
            canonicalAliases.put(alias, canonicalField);
            metricObject.put("operation", operation.toUpperCase(Locale.ROOT).replace('-', '_'));
            metricObject.put("alias", canonicalField);
            if ("count".equals(operation)) {
                metricObject.remove("field");
            } else {
                metricObject.put("field", canonicalField);
            }
        }
        alignComparisonDisplayBindings(config, query, canonicalAliases);
        return true;
    }

    private void alignComparisonDisplayBindings(
            ObjectNode config,
            ObjectNode query,
            Map<String, String> canonicalAliases) {
        JsonNode metrics = query.path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (!(metric instanceof ObjectNode metricObject)) {
                    continue;
                }
                String canonicalField = canonicalComparisonOutputField(
                        metricObject.path("field").asText(""),
                        canonicalAliases);
                if (!canonicalField.isBlank()) {
                    metricObject.put("field", canonicalField);
                    metricObject.put("alias", canonicalField);
                }
            }
        }
        JsonNode series = config.path("series");
        if (series.isArray()) {
            for (JsonNode item : series) {
                if (!(item instanceof ObjectNode seriesObject)
                        || !(seriesObject.path("metric") instanceof ObjectNode seriesMetric)) {
                    continue;
                }
                String canonicalField = canonicalComparisonOutputField(
                        seriesMetric.path("field").asText(""),
                        canonicalAliases);
                if (!canonicalField.isBlank()) {
                    seriesMetric.put("field", canonicalField);
                }
            }
        }
    }

    private String canonicalComparisonOutputField(
            String outputField,
            Map<String, String> canonicalAliases) {
        if (outputField == null || canonicalAliases == null || canonicalAliases.isEmpty()) {
            return "";
        }
        String prefix = "__praxisComparison_";
        if (!outputField.startsWith(prefix)) {
            return "";
        }
        for (String period : List.of("current", "previous")) {
            String suffix = "_" + period;
            if (!outputField.endsWith(suffix)) {
                continue;
            }
            String alias = outputField.substring(prefix.length(), outputField.length() - suffix.length());
            String canonicalAlias = canonicalAliases.entrySet().stream()
                    .filter(entry -> normalize(entry.getKey()).equals(normalize(alias)))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("");
            return canonicalAlias.isBlank() ? "" : prefix + canonicalAlias + suffix;
        }
        return "";
    }

    private void alignStatsExecutionField(
            ObjectNode query,
            ObjectNode statsRequest,
            StatsCapabilityFieldDescriptor capability) {
        statsRequest.put("field", capability.field());
        JsonNode dimensions = query.path("dimensions");
        if (dimensions instanceof ArrayNode dimensionArray) {
            dimensionArray.removeAll();
            dimensionArray.add(capability.field());
        }
    }

    private void markStatsAxesUnsupported(
            ObjectNode uiCompositionPlan,
            String status,
            List<String> warnings) {
        if (uiCompositionPlan == null) {
            return;
        }
        JsonNode widgets = uiCompositionPlan.path("widgets");
        if (widgets.isArray()) {
            for (JsonNode widget : widgets) {
                if (widget instanceof ObjectNode widgetObject && isPraxisStatsChart(widgetObject)) {
                    JsonNode axis = widgetObject.path("inputs").path("config").path("semanticAxis");
                    markStatsAxisUnsupported(
                            uiCompositionPlan,
                            axis instanceof ObjectNode axisObject ? axisObject : null,
                            status);
                }
            }
        }
        addWarningOnce(warnings, "semantic-axis-stats-capability-verification-" + status);
    }

    private void markStatsAxisUnsupported(
            ObjectNode uiCompositionPlan,
            ObjectNode semanticAxis,
            String status) {
        if (semanticAxis == null) {
            return;
        }
        semanticAxis.put("statsVerified", false);
        semanticAxis.put("statsProbeStatus", status);
        updateDiagnosticStatsAxis(uiCompositionPlan, semanticAxis, null, null, false, status);
    }

    private void markStatsAxisVerified(
            ObjectNode uiCompositionPlan,
            ObjectNode semanticAxis,
            StatsCapabilityFieldDescriptor capability,
            ResourceCapabilitiesFetchResult result) {
        semanticAxis.put("statsVerified", true);
        semanticAxis.put("statsProbeStatus", "verified");
        semanticAxis.put("statsExecutionField", capability.field());
        ObjectNode evidence = semanticAxis.putObject("statsEvidence");
        evidence.put("source", "resource.capabilities");
        evidence.put("endpointUrl", value(result == null ? null : result.getEndpointUrl()));
        evidence.put("keyAndLabelDistinct", capability.keyAndLabelDistinct());
        updateDiagnosticStatsAxis(uiCompositionPlan, semanticAxis, capability, result, true, "verified");
    }

    private void updateDiagnosticStatsAxis(
            ObjectNode uiCompositionPlan,
            ObjectNode semanticAxis,
            StatsCapabilityFieldDescriptor capability,
            ResourceCapabilitiesFetchResult result,
            boolean verified,
            String status) {
        JsonNode axes = uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return;
        }
        String field = normalize(semanticAxis.path("field").asText(""));
        for (JsonNode axis : axes) {
            if (!(axis instanceof ObjectNode axisObject)
                    || !normalize(axisObject.path("field").asText("")).equals(field)) {
                continue;
            }
            axisObject.put("statsVerified", verified);
            axisObject.put("statsProbeStatus", status);
            if (capability != null) {
                axisObject.put("statsExecutionField", capability.field());
                ObjectNode evidence = axisObject.putObject("statsEvidence");
                evidence.put("source", "resource.capabilities");
                evidence.put("endpointUrl", value(result == null ? null : result.getEndpointUrl()));
                evidence.put("keyAndLabelDistinct", capability.keyAndLabelDistinct());
            }
        }
    }

    private void markResourceStatsGrounding(
            ObjectNode uiCompositionPlan,
            String resourcePath,
            ResourceCapabilitiesFetchResult result,
            int fieldCount,
            int groundedChartCount,
            int chartCount) {
        ObjectNode diagnostics = uiCompositionPlan.path("diagnostics") instanceof ObjectNode existing
                ? existing
                : uiCompositionPlan.putObject("diagnostics");
        ObjectNode grounding = diagnostics.putObject("resourceStatsGrounding");
        grounding.put("verified", chartCount > 0 && groundedChartCount == chartCount);
        grounding.put("source", "resource.capabilities");
        grounding.put("resourcePath", resourcePath);
        grounding.put("endpointUrl", value(result == null ? null : result.getEndpointUrl()));
        grounding.put("fieldCount", Math.max(0, fieldCount));
        grounding.put("chartCount", Math.max(0, chartCount));
        grounding.put("groundedChartCount", Math.max(0, groundedChartCount));
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
            String schemaBaseUrl,
            PreviewSchemaFetchCache schemaFetchCache) {
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
        SchemaFetchResult filterSchemaResult = schemaFetchCache.fetch(filterContext, schemaBaseUrl);
        if (filterSchemaResult == null || !filterSchemaResult.isSuccess()) {
            return Optional.empty();
        }
        Map<String, SchemaFieldDescriptor> fields = schemaFields(filterSchemaResult.getSchema());
        return fields.isEmpty() ? Optional.empty() : Optional.of(fields);
    }

    private Optional<Map<String, SchemaFieldDescriptor>> statsRequestSchemaFields(
            AgenticAuthoringCandidate candidate,
            String schemaBaseUrl,
            PreviewSchemaFetchCache schemaFetchCache) {
        if (candidate == null || schemaFetchCache == null || !isStatsPath(candidate.submitUrl())) {
            return Optional.empty();
        }
        AiSchemaContext statsContext = AiSchemaContext.builder()
                .path(candidate.submitUrl())
                .operation(valueOrDefault(candidate.submitMethod(), "post"))
                .schemaType("request")
                .build();
        SchemaFetchResult statsSchemaResult = schemaFetchCache.fetch(statsContext, schemaBaseUrl);
        if (statsSchemaResult == null || !statsSchemaResult.isSuccess()) {
            return Optional.empty();
        }
        Map<String, SchemaFieldDescriptor> fields = schemaFields(statsSchemaResult.getSchema());
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
                "/stats/comparison",
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
                || normalized.contains("/stats/distribution")
                || normalized.contains("/stats/comparison");
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
            Map<String, SchemaFieldDescriptor> statsRequestFields,
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
                alignTableQueryContextFilters(widget, filterSchemaFields, schemaResult, warnings);
                JsonNode axis = widget.path("inputs").path("config").path("semanticAxis");
                if (!(axis instanceof ObjectNode axisObject)) {
                    continue;
                }
                String semanticallySelectedField = axisObject.path("field").asText("");
                boolean exactSemanticSchemaField = schemaFields.containsKey(normalize(semanticallySelectedField));
                Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisObject, schemaFields);
                schemaField = governedStatsAxisField(widget, schemaField, schemaFields, statsRequestFields, warnings);
                if (schemaField.isPresent() && isSafeGenericGroupByChartField(schemaField.get(), widget)) {
                    // An exact schema field selected by the semantic decision is already grounded.
                    // Prompt token scoring is only a repair mechanism for unresolved/approximate fields;
                    // allowing it here would replace a canonical LLM decision with lexical coincidence.
                    Optional<SchemaFieldDescriptor> promptAlignedSchemaField = exactSemanticSchemaField
                            ? Optional.empty()
                            : promptAlignedSafeGroupingField(
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
                    if (!normalize(requestedField).equals(normalize(selectedField.name()))) {
                        alignDiagnosticsAxis(uiCompositionPlan, requestedField, selectedField, schemaResult);
                    }
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
                        if (repairedSchemaField.isPresent()) {
                            repairedSchemaField = governedStatsAxisField(
                                    widget,
                                    repairedSchemaField,
                                    schemaFields,
                                    statsRequestFields,
                                    warnings);
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
                    } else {
                        markSemanticAxisDropped(axisObject, "unsupported-semantic-axis");
                        markDiagnosticsAxisDropped(uiCompositionPlan, axisObject.path("field").asText(""), "unsupported-semantic-axis");
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
            pruneOrphanGroupingItems(uiCompositionPlan, widgetKeys, warnings);
            pruneOrphanDeviceLayoutItems(uiCompositionPlan, widgetKeys, warnings);
            pruneOrphanSlotAssignments(uiCompositionPlan, widgetKeys, warnings);
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

    private void alignTableQueryContextFilters(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> filterSchemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        if (!"praxis-table".equals(widget.path("componentId").asText(""))
                || !(widget.path("inputs").path("queryContext").path("filters") instanceof ObjectNode filters)
                || filters.isEmpty()) {
            return;
        }
        ObjectNode aligned = objectMapper.createObjectNode();
        filters.fields().forEachRemaining(entry -> {
            Optional<SchemaFieldDescriptor> resolved = resolveSchemaField(
                    axisProbe(entry.getKey()),
                    filterSchemaFields);
            if (resolved.isPresent()) {
                aligned.set(resolved.get().name(), entry.getValue().deepCopy());
            }
        });
        if (aligned.size() != filters.size()) {
            addWarningOnce(warnings, "table-query-filter-schema-grounding-incomplete");
            return;
        }
        filters.removeAll();
        filters.setAll(aligned);
        addWarningOnce(warnings, "table-query-filter-schema-grounded");
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
            transform.put("id", bindingObject.path("id").asText() + "-query-context");
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

    private Optional<SchemaFieldDescriptor> governedStatsAxisField(
            JsonNode widget,
            Optional<SchemaFieldDescriptor> currentField,
            Map<String, SchemaFieldDescriptor> schemaFields,
            Map<String, SchemaFieldDescriptor> statsRequestFields,
            List<String> warnings) {
        if (!"timeseries".equalsIgnoreCase(statsOperation(widget))) {
            return currentField;
        }
        if (currentField.isPresent() && isStrictTemporalStatsField(currentField.get())) {
            return currentField;
        }
        Optional<SchemaFieldDescriptor> governedField = preferredStrictTemporalStatsField(
                schemaFields,
                statsRequestFields);
        if (governedField.isEmpty()) {
            return currentField;
        }
        if (currentField.isEmpty()
                || !normalize(currentField.get().name()).equals(normalize(governedField.get().name()))) {
            addWarningOnce(warnings, "semantic-chart-timeseries-axis-repaired-with-governed-temporal-field");
        }
        return governedField;
    }

    private Optional<SchemaFieldDescriptor> preferredStrictTemporalStatsField(
            Map<String, SchemaFieldDescriptor> schemaFields,
            Map<String, SchemaFieldDescriptor> statsRequestFields) {
        if (schemaFields == null || schemaFields.isEmpty()) {
            return Optional.empty();
        }
        Set<String> requestTokens = statsRequestFieldTokens(statsRequestFields);
        SchemaFieldDescriptor best = null;
        int bestScore = Integer.MIN_VALUE;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            if (!isStrictTemporalStatsField(field)) {
                continue;
            }
            int score = temporalFieldScore(field);
            if (!requestTokens.isEmpty()) {
                score += semanticMatchScore(requestTokens, field) * 40;
            }
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        return best == null || bestScore <= 0 ? Optional.empty() : Optional.of(best);
    }

    private Set<String> statsRequestFieldTokens(Map<String, SchemaFieldDescriptor> statsRequestFields) {
        if (statsRequestFields == null || statsRequestFields.isEmpty()) {
            return Set.of();
        }
        SchemaFieldDescriptor fieldRequest = statsRequestFields.get("field");
        if (fieldRequest == null) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        tokens.addAll(fieldRequest.labelTokens());
        tokens.addAll(fieldRequest.descriptionTokens());
        return tokens;
    }

    private boolean isStrictTemporalStatsField(SchemaFieldDescriptor field) {
        if (field == null) {
            return false;
        }
        String type = normalize(field.type());
        String format = normalize(field.format());
        return "date".equals(type)
                || "datetime".equals(type)
                || "date".equals(format)
                || "date-time".equals(format)
                || "datetime".equals(format);
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

    private void pruneOrphanGroupingItems(
            ObjectNode uiCompositionPlan,
            Set<String> widgetKeys,
            List<String> warnings) {
        JsonNode grouping = uiCompositionPlan.path("grouping");
        if (!(grouping instanceof ArrayNode groupingArray)) {
            return;
        }
        for (int i = groupingArray.size() - 1; i >= 0; i--) {
            JsonNode group = groupingArray.get(i);
            JsonNode widgetKeysNode = group.path("widgetKeys");
            if (!(widgetKeysNode instanceof ArrayNode groupWidgetKeys)) {
                continue;
            }
            for (int keyIndex = groupWidgetKeys.size() - 1; keyIndex >= 0; keyIndex--) {
                String key = groupWidgetKeys.path(keyIndex).asText("");
                if (!key.isBlank() && !widgetKeys.contains(key)) {
                    groupWidgetKeys.remove(keyIndex);
                    addWarningOnce(warnings, "ui-composition-plan-orphan-grouping-item-removed");
                }
            }
            if (groupWidgetKeys.isEmpty()) {
                groupingArray.remove(i);
                addWarningOnce(warnings, "ui-composition-plan-empty-grouping-removed");
            }
        }
        if (groupingArray.isEmpty()) {
            uiCompositionPlan.remove("grouping");
        }
    }

    private void pruneOrphanDeviceLayoutItems(
            ObjectNode uiCompositionPlan,
            Set<String> widgetKeys,
            List<String> warnings) {
        JsonNode deviceLayouts = uiCompositionPlan.path("deviceLayouts");
        if (!(deviceLayouts instanceof ObjectNode deviceLayoutsObject)) {
            return;
        }
        deviceLayoutsObject.fields().forEachRemaining(entry -> {
            JsonNode items = entry.getValue().path("canvas").path("items");
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
                addWarningOnce(warnings, "ui-composition-plan-orphan-device-layout-item-removed");
            }
        });
    }

    private void pruneOrphanSlotAssignments(
            ObjectNode uiCompositionPlan,
            Set<String> widgetKeys,
            List<String> warnings) {
        JsonNode slotAssignments = uiCompositionPlan.path("slotAssignments");
        if (!(slotAssignments instanceof ObjectNode slotAssignmentsObject)) {
            return;
        }
        List<String> orphanKeys = new ArrayList<>();
        slotAssignmentsObject.fieldNames().forEachRemaining(key -> {
            if (!widgetKeys.contains(key)) {
                orphanKeys.add(key);
            }
        });
        for (String orphanKey : orphanKeys) {
            slotAssignmentsObject.remove(orphanKey);
            addWarningOnce(warnings, "ui-composition-plan-orphan-slot-assignment-removed");
        }
        if (slotAssignmentsObject.isEmpty()) {
            uiCompositionPlan.remove("slotAssignments");
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
                "",
                false,
                "",
                false,
                "",
                false,
                false,
                tokens(field),
                tokens(field),
                Set.of());
    }

    private Optional<SchemaFieldDescriptor> preferredTemporalRangeFilterField(
            SchemaFieldDescriptor requestedField,
            Map<String, SchemaFieldDescriptor> schemaFields) {
        if (requestedField == null || schemaFields == null || schemaFields.isEmpty()) {
            return Optional.empty();
        }
        Set<String> requestedTokens = new LinkedHashSet<>();
        requestedTokens.addAll(requestedField.fieldTokens());
        requestedTokens.addAll(requestedField.labelTokens());
        requestedTokens.addAll(requestedField.descriptionTokens());
        SchemaFieldDescriptor best = null;
        int bestScore = 0;
        boolean ambiguous = false;
        for (SchemaFieldDescriptor candidate : schemaFields.values()) {
            if (!isTemporalRangeFilterField(candidate)) {
                continue;
            }
            int score = semanticMatchScore(requestedTokens, candidate);
            if (score < 6 && !temporalRangeStemMatches(requestedField, candidate)) {
                continue;
            }
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
                ambiguous = false;
            } else if (score > 0 && score == bestScore) {
                ambiguous = true;
            }
        }
        return best != null && bestScore >= 3 && !ambiguous
                ? Optional.of(best)
                : Optional.empty();
    }

    private boolean temporalRangeStemMatches(
            SchemaFieldDescriptor requestedField,
            SchemaFieldDescriptor candidate) {
        String requestedName = normalize(requestedField.name()).replaceAll("[^a-z0-9]", "");
        String candidateName = normalize(candidate.name()).replaceAll("[^a-z0-9]", "");
        return !requestedName.isBlank()
                && (candidateName.equals(requestedName)
                || candidateName.startsWith(requestedName + "between")
                || candidateName.startsWith(requestedName + "range"));
    }

    private boolean isTemporalRangeFilterField(SchemaFieldDescriptor field) {
        if (field == null || !"array".equals(normalize(field.type()))) {
            return false;
        }
        String controlType = normalize(field.controlType());
        return containsAny(controlType, "date-range", "date_range", "daterange", "date range");
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
        return isTemporalRangeFilterField(field)
                || field.multiple()
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
        if (isCanonicalFilterProjection(requestedField.name(), candidate.name())) {
            score += 100;
        }
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

    private boolean isCanonicalFilterProjection(String analyticalField, String filterField) {
        String analytical = normalize(analyticalField).replaceAll("[^a-z0-9]", "");
        String candidate = normalize(filterField).replaceAll("[^a-z0-9]", "");
        if (analytical.isBlank() || candidate.isBlank() || analytical.equals(candidate)) {
            return false;
        }
        for (String suffix : List.of("idsin", "idin", "ids", "id", "between", "range", "in")) {
            if (candidate.endsWith(suffix)
                    && candidate.substring(0, candidate.length() - suffix.length()).equals(analytical)) {
                return true;
            }
        }
        return false;
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
                    JsonNode metric = seriesObject.path("metric");
                    if (metric instanceof ObjectNode metricObject
                            && "count".equals(normalize(metricObject.path("aggregation").asText("")))) {
                        metricObject.put("dimensionField", field.name());
                    }
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
        alignQueryMetricsWithStatsMetric(config, warnings);
    }

    private void alignQueryMetricsWithStatsMetric(ObjectNode config, List<String> warnings) {
        JsonNode statsMetric = config.path("dataSource").path("query").path("statsRequest").path("metric");
        if (!(statsMetric instanceof ObjectNode statsMetricObject)) {
            return;
        }
        String operation = normalize(statsMetricObject.path("operation").asText(""));
        String field = statsMetricObject.path("field").asText("");
        if (!"sum".equals(operation) || field.isBlank()) {
            return;
        }
        JsonNode metrics = config.path("dataSource").path("query").path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (metric instanceof ObjectNode metricObject
                        && normalize(metricObject.path("field").asText("")).equals(normalize(field))
                        && "count".equals(normalize(metricObject.path("aggregation").asText("")))) {
                    metricObject.put("aggregation", "sum");
                    addWarningOnce(warnings, "semantic-chart-query-metric-aligned-with-stats-metric");
                }
            }
        }
        JsonNode series = config.path("series");
        if (series.isArray()) {
            for (JsonNode item : series) {
                JsonNode metric = item.path("metric");
                if (metric instanceof ObjectNode metricObject
                        && normalize(metricObject.path("field").asText("")).equals(normalize(field))
                        && "count".equals(normalize(metricObject.path("aggregation").asText("")))) {
                    metricObject.put("aggregation", "sum");
                    addWarningOnce(warnings, "semantic-chart-query-metric-aligned-with-stats-metric");
                }
            }
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
        Optional<ComparisonOutputMetric> comparisonOutput = comparisonOutputMetric(requestedField);
        if (comparisonOutput.isPresent()) {
            alignComparisonOutputMetric(
                    metric,
                    comparisonOutput.get(),
                    schemaFields,
                    schemaResult,
                    warnings);
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

    private void alignComparisonOutputMetric(
            ObjectNode metric,
            ComparisonOutputMetric output,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        Optional<SchemaFieldDescriptor> schemaField = schemaFields.values().stream()
                .filter(field -> normalize(field.name()).equals(normalize(output.sourceField())))
                .findFirst()
                .or(() -> resolveSchemaField(axisProbe(output.sourceField()), schemaFields));
        if (schemaField.isEmpty()) {
            metric.put("schemaVerified", false);
            metric.put("schemaProbeStatus", "unsupported-derived-comparison-output");
            addWarningOnce(warnings, "semantic-chart-metric-schema-verification-unsupported-field");
            return;
        }

        String requestedField = metric.path("field").asText("");
        String canonicalField = "__praxisComparison_"
                + schemaField.get().name()
                + "_"
                + output.period();
        if (!requestedField.equals(canonicalField)) {
            metric.put("requestedField", requestedField);
            metric.put("field", canonicalField);
            if (normalize(metric.path("alias").asText("")).equals(normalize(requestedField))) {
                metric.put("alias", canonicalField);
            }
        }
        metric.put("schemaVerified", true);
        metric.put("schemaProbeStatus", "verified-derived-comparison-output");
        metric.put("schemaEvidenceSource", "schemas.filtered");
        metric.put("schemaEvidenceUrl", schemaResult == null ? "" : value(schemaResult.getEndpointUrl()));
    }

    private Optional<ComparisonOutputMetric> comparisonOutputMetric(String field) {
        String prefix = "__praxisComparison_";
        if (field == null || !field.startsWith(prefix)) {
            return Optional.empty();
        }
        for (String period : List.of("current", "previous")) {
            String suffix = "_" + period;
            if (field.endsWith(suffix) && field.length() > prefix.length() + suffix.length()) {
                return Optional.of(new ComparisonOutputMetric(
                        field.substring(prefix.length(), field.length() - suffix.length()),
                        period));
            }
        }
        return Optional.empty();
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
        if ("count".equals(normalize(operation))) {
            String alias = metric.path("alias").asText("");
            if (!alias.isBlank() && normalize(alias).equals(normalize(field.name()))) {
                metric.put("alias", "total");
            }
            metric.remove("field");
            addWarningOnce(warnings, "semantic-chart-count-metric-field-removed-for-stats-contract");
        }
        if ("count".equals(normalize(aggregation))) {
            addWarningOnce(warnings, "semantic-chart-count-metric-preserved-for-record-count");
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
                property.path("items").path("type").asText(""),
                property.path("format").asText(""),
                property.path("enum").isArray() && !property.path("enum").isEmpty(),
                xUi.path("controlType").asText(""),
                xUi.path("multiple").asBoolean(false),
                xUi.path("endpoint").asText(""),
                xUi.path("hidden").asBoolean(false),
                xUi.path("tableHidden").asBoolean(false),
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
            if (axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            if (!axis.path("schemaVerified").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsUnverifiedStatsAxes(JsonNode uiCompositionPlan) {
        JsonNode axes = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return false;
        }
        for (JsonNode axis : axes) {
            if (axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            if (axis.path("statsVerified").isBoolean() && !axis.path("statsVerified").asBoolean()) {
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
            if (axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            if ("unsupported".equals(axis.path("schemaProbeStatus").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private void markOrphanUnverifiedSemanticAxesAsDropped(
            ObjectNode uiCompositionPlan,
            List<String> warnings) {
        JsonNode axes = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return;
        }
        Set<String> materializedChartFields = materializedChartSemanticAxisFields(uiCompositionPlan);
        for (JsonNode axis : axes) {
            if (!(axis instanceof ObjectNode axisObject)
                    || axisObject.path("schemaVerified").asBoolean(false)
                    || axisObject.path("materialized").isBoolean() && !axisObject.path("materialized").asBoolean()) {
                continue;
            }
            String field = normalize(axisObject.path("field").asText(""));
            if (field.isBlank() || !materializedChartFields.contains(field)) {
                markSemanticAxisDropped(axisObject, "chart-axis-not-materialized");
            }
        }
        if (!containsUnsupportedSemanticAxes(uiCompositionPlan)) {
            warnings.remove("semantic-axis-schema-verification-unsupported-axis");
        }
        if (!containsUnverifiedSemanticAxes(uiCompositionPlan)) {
            warnings.remove("semantic-axis-schema-verification-pending");
        }
    }

    private Set<String> materializedChartSemanticAxisFields(JsonNode uiCompositionPlan) {
        Set<String> fields = new LinkedHashSet<>();
        JsonNode widgets = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("widgets");
        if (!widgets.isArray()) {
            return fields;
        }
        for (JsonNode widget : widgets) {
            if (!"praxis-chart".equals(widget.path("componentId").asText(""))) {
                continue;
            }
            JsonNode axis = widget.path("inputs").path("config").path("semanticAxis");
            if (axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            String field = normalize(axis.path("field").asText(""));
            if (!field.isBlank()) {
                fields.add(field);
            }
        }
        return fields;
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

    private AgenticAuthoringPreviewMessageResult previewAssistantMessage(
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
            return AgenticAuthoringPreviewMessageResult.deterministic(fallbackMessage);
        }
        return messageSynthesizer.synthesizeWithTelemetry(
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

    private List<AiProviderInvocationTelemetry> mergeProviderInvocations(
            List<AiProviderInvocationTelemetry> first,
            List<AiProviderInvocationTelemetry> second) {
        List<AiProviderInvocationTelemetry> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return List.copyOf(merged);
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
        String resourceLabel = AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate);
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
                : AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate);
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
        return AgenticAuthoringResourcePresentationLabel.fromResourcePath(resourcePath);
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

    private final class PreviewSchemaFetchCache {

        private final SchemaRetrievalService schemaRetrievalService;
        private final Map<String, SchemaFetchResult> fetches = new LinkedHashMap<>();

        private PreviewSchemaFetchCache(SchemaRetrievalService schemaRetrievalService) {
            this.schemaRetrievalService = schemaRetrievalService;
        }

        private SchemaFetchResult fetch(AiSchemaContext context, String schemaBaseUrl) {
            if (schemaRetrievalService == null || context == null) {
                return null;
            }
            String key = cacheKey(context, schemaBaseUrl);
            if (fetches.containsKey(key)) {
                return fetches.get(key);
            }
            SchemaFetchResult result = schemaRetrievalService.fetchSchemaResult(context, schemaBaseUrl);
            fetches.put(key, result);
            return result;
        }

        private SchemaFetchResult fetchPrincipalAware(
                AiSchemaContext context,
                String schemaBaseUrl,
                String tenantId,
                String userId,
                String environment) {
            if (schemaRetrievalService == null || context == null) {
                return null;
            }
            String key = cacheKey(context, schemaBaseUrl);
            if (fetches.containsKey(key)) {
                return fetches.get(key);
            }
            SchemaFetchResult result = schemaRetrievalService.fetchSchemaResult(
                    context,
                    schemaBaseUrl,
                    tenantId,
                    userId,
                    environment);
            fetches.put(key, result);
            return result;
        }

        private String cacheKey(AiSchemaContext context, String schemaBaseUrl) {
            return value(schemaBaseUrl)
                    + "|"
                    + value(context.getPath())
                    + "|"
                    + value(context.getOperation())
                    + "|"
                    + value(context.getSchemaType());
        }
    }

    private final class PreviewResourceCapabilitiesFetchCache {

        private final ResourceCapabilitiesRetrievalService retrievalService;
        private final Map<String, ResourceCapabilitiesFetchResult> fetches = new LinkedHashMap<>();

        private PreviewResourceCapabilitiesFetchCache(ResourceCapabilitiesRetrievalService retrievalService) {
            this.retrievalService = retrievalService;
        }

        private ResourceCapabilitiesFetchResult fetch(
                String resourcePath,
                String requestBaseUrl,
                String tenantId,
                String userId,
                String environment) {
            if (retrievalService == null || resourcePath == null || resourcePath.isBlank()) {
                return null;
            }
            String key = String.join(
                    "|",
                    value(requestBaseUrl),
                    value(resourcePath),
                    value(tenantId),
                    value(userId),
                    value(environment));
            if (fetches.containsKey(key)) {
                return fetches.get(key);
            }
            ResourceCapabilitiesFetchResult result = retrievalService.fetchCapabilitiesResult(
                    resourcePath,
                    requestBaseUrl,
                    tenantId,
                    userId,
                    environment);
            fetches.put(key, result);
            return result;
        }
    }

    private final class PreviewResourceSurfaceCatalogFetchCache {

        private final ResourceSurfaceCatalogRetrievalService retrievalService;
        private final Map<String, ResourceSurfaceCatalogFetchResult> fetches = new LinkedHashMap<>();

        private PreviewResourceSurfaceCatalogFetchCache(ResourceSurfaceCatalogRetrievalService retrievalService) {
            this.retrievalService = retrievalService;
        }

        private ResourceSurfaceCatalogFetchResult fetch(
                String resourceKey,
                String requestBaseUrl,
                String tenantId,
                String userId,
                String environment) {
            if (retrievalService == null || resourceKey == null || resourceKey.isBlank()) {
                return null;
            }
            String key = String.join(
                    "|",
                    value(requestBaseUrl),
                    value(resourceKey),
                    value(tenantId),
                    value(userId),
                    value(environment));
            if (fetches.containsKey(key)) {
                return fetches.get(key);
            }
            ResourceSurfaceCatalogFetchResult result = retrievalService.fetchCatalogResult(
                    resourceKey,
                    requestBaseUrl,
                    tenantId,
                    userId,
                    environment);
            fetches.put(key, result);
            return result;
        }
    }

    private record VisibleTableQueryFilterMaterialization(
            JsonNode uiCompositionPlan,
            List<String> failureCodes) {

        private static VisibleTableQueryFilterMaterialization success(JsonNode uiCompositionPlan) {
            return new VisibleTableQueryFilterMaterialization(uiCompositionPlan, List.of());
        }
    }

    private record SchemaFieldDescriptor(
            String name,
            String label,
            String description,
            String type,
            String itemType,
            String format,
            boolean hasEnum,
            String controlType,
            boolean multiple,
            String endpoint,
            boolean hidden,
            boolean tableHidden,
            Set<String> fieldTokens,
            Set<String> labelTokens,
            Set<String> descriptionTokens) {
    }

    private record StatsCapabilityFieldDescriptor(
            String field,
            String label,
            Set<String> aliases,
            Set<String> metrics,
            Set<String> modes,
            boolean groupByEligible,
            boolean timeSeriesEligible,
            boolean distributionTermsEligible,
            boolean distributionHistogramEligible,
            boolean metricFieldEligible,
            boolean keyAndLabelDistinct) {
    }

    private record ComparisonOutputMetric(String sourceField, String period) {
    }

    private enum ChartInteractionValueShape {
        SCALAR,
        SINGLETON_ARRAY,
        TEMPORAL_RANGE
    }

    private record ChartInteractionProjection(
            String displayField,
            String sourceField,
            String targetField,
            ChartInteractionValueShape valueShape,
            String pointValuePath,
            Set<String> axisFields,
            Set<String> crossFilterTargetFields) {

        boolean targetMultiple() {
            return valueShape != ChartInteractionValueShape.SCALAR;
        }

        boolean temporalRange() {
            return valueShape == ChartInteractionValueShape.TEMPORAL_RANGE;
        }
    }

    private record SemanticAxisAssistantSummary(
            List<String> verifiedAxes,
            List<String> unsupportedAxes) {
    }
}
