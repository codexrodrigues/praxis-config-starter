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
import org.praxisplatform.config.ai.authoring.AgenticAuthoringChartInteractionProjectionReconciler.ChartInteractionProjection;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringChartInteractionProjectionReconciler.ChartInteractionValueShape;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringRelatedResourceSurfaceVerifier.RelatedResourceSurfaceVerification;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;
import org.praxisplatform.config.service.ResourceCapabilitiesFetchResult;
import org.praxisplatform.config.service.ResourceCapabilitiesRetrievalService;
import org.praxisplatform.config.service.ResourceSurfaceCatalogFetchResult;
import org.praxisplatform.config.service.ResourceSurfaceCatalogRetrievalService;
import org.praxisplatform.config.service.SchemaFetchResult;
import org.praxisplatform.config.service.SchemaRetrievalService;

public class AgenticAuthoringPreviewService {

    private final AgenticAuthoringPlanService planService;
    private final AgenticAuthoringPatchCompilerService patchCompilerService;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringIntentResolutionContext intentResolutionContext;
    private final AgenticAuthoringConversationTurnOrchestrator conversationTurnOrchestrator;
    private final AgenticAuthoringUiCompositionPlanCompiler uiCompositionPlanCompiler;
    private final AgenticAuthoringRelatedResourceSurfaceVerifier relatedResourceSurfaceVerifier;
    private final AgenticAuthoringStatsPreviewVerifier statsPreviewVerifier;
    private final AgenticAuthoringChartInteractionProjectionReconciler chartInteractionProjectionReconciler;
    private final AgenticAuthoringSemanticAxisSchemaVerifier semanticAxisSchemaVerifier;
    private final List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders;
    private final AgenticAuthoringPreviewMessageSynthesizerService messageSynthesizer;
    private final SchemaRetrievalService schemaRetrievalService;
    private final ResourceCapabilitiesRetrievalService resourceCapabilitiesRetrievalService;
    private final ResourceSurfaceCatalogRetrievalService resourceSurfaceCatalogRetrievalService;
    private final AgenticAuthoringComponentEditPlanService componentEditPlanService;
    private final AgenticAuthoringUiCompositionTemplateResolver uiCompositionTemplateResolver;
    private final AgenticAuthoringPreviewAssistantMessageSupport assistantMessageSupport =
            new AgenticAuthoringPreviewAssistantMessageSupport();

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
        this(
                planService,
                patchCompilerService,
                objectMapper,
                uiCompositionPlanProviders,
                messageSynthesizer,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService,
                resourceSurfaceCatalogRetrievalService,
                componentEditPlanService,
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
            ResourceSurfaceCatalogRetrievalService resourceSurfaceCatalogRetrievalService,
            AgenticAuthoringComponentEditPlanService componentEditPlanService,
            AgenticAuthoringUiCompositionTemplateResolver uiCompositionTemplateResolver) {
        this.planService = Objects.requireNonNull(planService, "planService must not be null");
        this.patchCompilerService = Objects.requireNonNull(patchCompilerService, "patchCompilerService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.intentResolutionContext = new AgenticAuthoringIntentResolutionContext(this.objectMapper);
        this.conversationTurnOrchestrator = new AgenticAuthoringConversationTurnOrchestrator();
        this.uiCompositionPlanCompiler = new AgenticAuthoringUiCompositionPlanCompiler(this.objectMapper);
        this.relatedResourceSurfaceVerifier = new AgenticAuthoringRelatedResourceSurfaceVerifier();
        this.statsPreviewVerifier = new AgenticAuthoringStatsPreviewVerifier(this.objectMapper);
        this.chartInteractionProjectionReconciler =
                new AgenticAuthoringChartInteractionProjectionReconciler(this.objectMapper);
        this.semanticAxisSchemaVerifier =
                new AgenticAuthoringSemanticAxisSchemaVerifier(this.objectMapper);
        this.uiCompositionPlanProviders = List.copyOf(
                uiCompositionPlanProviders == null ? List.of() : uiCompositionPlanProviders);
        this.messageSynthesizer = messageSynthesizer;
        this.schemaRetrievalService = schemaRetrievalService;
        this.resourceCapabilitiesRetrievalService = resourceCapabilitiesRetrievalService;
        this.resourceSurfaceCatalogRetrievalService = resourceSurfaceCatalogRetrievalService;
        this.componentEditPlanService = componentEditPlanService;
        this.uiCompositionTemplateResolver = uiCompositionTemplateResolver;
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
        return previewWithPersistedUiCompositionPlan(
                request,
                tenantId,
                userId,
                environment,
                schemaBaseUrl,
                null);
    }

    public AgenticAuthoringPreviewResult previewWithPersistedUiCompositionPlan(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment,
            String schemaBaseUrl,
            JsonNode persistedUiCompositionPlan) throws IOException {
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
                previewComponentEditPlan(
                        effectiveRequest,
                        tenantId,
                        userId,
                        environment,
                        schemaBaseUrl,
                        persistedUiCompositionPlan);
        if (componentEditPreview.isPresent()) {
            return componentEditPreview.get();
        }
        Optional<AgenticAuthoringPreviewResult> uiCompositionPreview =
                previewUiCompositionPlan(
                        effectiveRequest,
                        tenantId,
                        userId,
                        environment,
                        schemaBaseUrl,
                        persistedUiCompositionPlan);
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
                    planResult.minimalFormPlan() != null
                            && planResult.minimalFormPlan().path("clarificationNeed").path("needed").asBoolean(false)
                            ? planResult.minimalFormPlan().path("clarificationNeed").path("question").asText("")
                            : null,
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
        AgenticAuthoringPlanRequest effectiveRequest = enrichRequest(request);
        AgenticAuthoringIntentResolutionResult intentResolution =
                effectiveRequest == null ? null : effectiveRequest.intentResolution();
        List<String> intentFailures = validateIntentResolution(intentResolution);
        if (!intentFailures.isEmpty()) {
            List<String> warnings = new ArrayList<>();
            if (intentResolution != null && intentResolution.warnings() != null) {
                warnings.addAll(intentResolution.warnings());
            }
            warnings.add("minimal-form-plan-skipped-invalid-intent-resolution");
            return new AgenticAuthoringPlanResult(
                    false,
                    List.copyOf(intentFailures),
                    List.copyOf(warnings),
                    MissingNode.getInstance());
        }
        return resolveMinimalFormPlan(
                effectiveRequest,
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
            String schemaBaseUrl) throws IOException {
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
            return planService.generateCreateFormPlanFromCanonicalSchema(
                    request, schemaResult.getSchema(), tenantId, userId, environment);
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
            String schemaBaseUrl,
            JsonNode persistedUiCompositionPlan) {
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
        JsonNode refinedUiCompositionPlan = null;
        if (persistedUiCompositionPlan != null) {
            if (directComponentEdit) {
                return Optional.of(componentEditFailure(
                        request,
                        List.of("persisted-ui-composition-direct-component-edit-unsupported"),
                        List.of("persisted-ui-composition-refinement-failed-closed"),
                        result.plan(),
                        result.providerInvocations()));
            }
            refinedUiCompositionPlan = replacePersistedPlanWidgetInputs(
                    persistedUiCompositionPlan,
                    selectedWidgetKey,
                    componentId,
                    proposedConfig);
            if (refinedUiCompositionPlan == null) {
                return Optional.of(componentEditFailure(
                        request,
                        List.of("persisted-ui-composition-target-widget-missing"),
                        List.of("persisted-ui-composition-refinement-failed-closed"),
                        result.plan(),
                        result.providerInvocations()));
            }
            AgenticAuthoringUiCompositionPlanCompiler.CompileResult compilation =
                    uiCompositionPlanCompiler.compile(refinedUiCompositionPlan, compiledFormPatch);
            if (!compilation.valid()) {
                return Optional.of(componentEditFailure(
                        request,
                        compilation.failureCodes(),
                        List.of("persisted-ui-composition-refinement-failed-closed"),
                        result.plan(),
                        result.providerInvocations()));
            }
            compiledFormPatch = compilation.compiledFormPatch();
            addWarningOnce(warnings, "persisted-ui-composition-refined-by-component-manifest");
            addWarningOnce(warnings, "ui-composition-plan-compiled-by-config");
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
                refinedUiCompositionPlan,
                noOp
                        ? AgenticAuthoringPresentationText.assistantReply(
                                "A configuração solicitada já está aplicada. Não fiz nenhuma alteração.")
                        : componentEditAssistantMessage(result.plan()),
                result.providerInvocations()));
    }

    private JsonNode replacePersistedPlanWidgetInputs(
            JsonNode persistedUiCompositionPlan,
            String selectedWidgetKey,
            String componentId,
            JsonNode proposedInputs) {
        if (!(persistedUiCompositionPlan instanceof ObjectNode persistedPlan)
                || !"praxis.ui-composition-plan".equals(persistedPlan.path("kind").asText(""))
                || !"1.0".equals(persistedPlan.path("version").asText(""))
                || proposedInputs == null
                || !proposedInputs.isObject()) {
            return null;
        }
        ObjectNode refinedPlan = persistedPlan.deepCopy();
        JsonNode widgets = refinedPlan.path("widgets");
        if (!widgets.isArray()) {
            return null;
        }
        for (JsonNode widget : widgets) {
            if (widget instanceof ObjectNode object
                    && selectedWidgetKey.equals(object.path("key").asText(""))
                    && componentId.equals(object.path("componentId").asText(""))) {
                object.set("inputs", proposedInputs.deepCopy());
                return refinedPlan;
            }
        }
        return null;
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
        return statsPreviewVerifier.copyWithContextHints(request, contextHints);
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
            String schemaBaseUrl,
            JsonNode persistedUiCompositionPlan) {
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
            request = withAnalyticalAndFilterMetadataContext(
                    request,
                    schemaBaseUrl,
                    tenantId,
                    userId,
                    environment,
                    schemaFetchCache,
                    capabilitiesFetchCache);
        }
        for (AgenticAuthoringUiCompositionPlanProvider provider : uiCompositionPlanProviders) {
            Optional<AgenticAuthoringUiCompositionPlanResult> result =
                    persistedUiCompositionPlan != null
                            && provider instanceof AgenticAuthoringPersistedUiCompositionPlanProvider persistedProvider
                            ? persistedProvider.plan(request, persistedUiCompositionPlan)
                            : provider.plan(request);
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
            if (persistedUiCompositionPlan != null && planResult.uiCompositionPlan() == null) {
                addAllOnce(
                        failureCodes,
                        List.of("persisted-ui-composition-refinement-plan-required"));
                AgenticAuthoringUiCompositionPlanResult rejectedDegradation =
                        new AgenticAuthoringUiCompositionPlanResult(
                                false,
                                List.copyOf(failureCodes),
                                List.copyOf(warnings),
                                null,
                                planResult.compiledFormPatch());
                return Optional.of(invalidUiCompositionPlanPreview(
                        request,
                        rejectedDegradation,
                        failureCodes,
                        warnings,
                        tenantId,
                        userId,
                        environment));
            }
            AgenticAuthoringUiCompositionTemplateResolver.Resolution templateResolution =
                    resolveUiCompositionTemplateReference(planResult.uiCompositionPlan());
            addAllOnce(failureCodes, templateResolution.failureCodes());
            addAllOnce(warnings, templateResolution.warnings());
            if (!templateResolution.valid()) {
                AgenticAuthoringUiCompositionPlanResult invalidReference =
                        new AgenticAuthoringUiCompositionPlanResult(
                                false,
                                List.copyOf(failureCodes),
                                List.copyOf(warnings),
                                templateResolution.uiCompositionPlan(),
                                planResult.compiledFormPatch());
                return Optional.of(invalidUiCompositionPlanPreview(
                        request,
                        invalidReference,
                        failureCodes,
                        warnings,
                        tenantId,
                        userId,
                        environment));
            }
            boolean technicallyValid = planResult.valid();
            boolean semanticallyValid = planResult.valid();
            JsonNode uiCompositionPlan = prepareCanonicalChartsForPreview(templateResolution.uiCompositionPlan());
            RelatedResourceSurfaceVerification relatedResourceSurfaceVerification =
                    verifyRelatedResourceSurfaces(
                            request,
                            uiCompositionPlan,
                            schemaBaseUrl,
                            tenantId,
                            userId,
                            environment,
                            surfaceCatalogFetchCache);
            uiCompositionPlan = relatedResourceSurfaceVerification.uiCompositionPlan();
            addAllOnce(failureCodes, relatedResourceSurfaceVerification.failureCodes());
            addAllOnce(warnings, relatedResourceSurfaceVerification.warnings());
            if (!relatedResourceSurfaceVerification.valid()) {
                technicallyValid = false;
                semanticallyValid = false;
            }
            uiCompositionPlan = normalizeCountMetricBindings(uiCompositionPlan, warnings);
            uiCompositionPlan = verifySemanticAxesWithSchema(
                    request,
                    uiCompositionPlan,
                    warnings,
                    schemaBaseUrl,
                    schemaFetchCache);
            uiCompositionPlan = groundDataQueryContextFilters(
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
            if (warnings.contains("crud-query-filter-schema-grounding-incomplete")) {
                addAllOnce(failureCodes, List.of("crud-query-filter-schema-grounding-incomplete"));
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
                finalizeCanonicalChartsAfterPreview(uiCompositionPlanObject);
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

    private AgenticAuthoringUiCompositionTemplateResolver.Resolution resolveUiCompositionTemplateReference(
            JsonNode uiCompositionPlan) {
        if (uiCompositionPlan == null
                || !uiCompositionPlan.isObject()
                || !uiCompositionPlan.has("templateRef")) {
            return AgenticAuthoringUiCompositionTemplateResolver.Resolution.notReferenced(uiCompositionPlan);
        }
        if (uiCompositionTemplateResolver == null) {
            return AgenticAuthoringUiCompositionTemplateResolver.Resolution.invalid(
                    uiCompositionPlan,
                    List.of("ui-composition-template-resolver-unavailable"));
        }
        return uiCompositionTemplateResolver.resolve(uiCompositionPlan);
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
        return statsPreviewVerifier.withGovernedAnalyticsContext(
                request,
                schemaBaseUrl,
                tenantId,
                userId,
                environment,
                schemaFetchCache == null ? null : schemaFetchCache::fetchPrincipalAware,
                capabilitiesFetchCache == null ? null : capabilitiesFetchCache::fetch,
                surfaceCatalogFetchCache == null ? null : surfaceCatalogFetchCache::fetch);
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

    private AgenticAuthoringPlanRequest withAnalyticalAndFilterMetadataContext(
            AgenticAuthoringPlanRequest request,
            String schemaBaseUrl,
            String tenantId,
            String userId,
            String environment,
            PreviewSchemaFetchCache schemaFetchCache,
            PreviewResourceCapabilitiesFetchCache capabilitiesFetchCache) {
        AgenticAuthoringIntentResolutionResult intent = request == null ? null : request.intentResolution();
        if (intent == null || !List.of("dashboard", "chart").contains(value(intent.artifactKind()))) {
            return request;
        }
        AgenticAuthoringCandidate candidate = intent.selectedCandidate();
        String businessPath = businessResourcePath(firstNonBlank(
                candidate == null ? "" : candidate.resourcePath(),
                candidate == null ? "" : candidate.submitUrl()));
        if (businessPath.isBlank()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        boolean modified = false;
        boolean temporalMetricEvolutionStatsAvailable =
                hasTemporalMetricEvolutionStatsFields(contextHints.path("analyticsFields"));

        if (!contextHints.path("analyticsFields").isArray()
                && capabilitiesFetchCache != null
                && resourceCapabilitiesRetrievalService != null) {
            ResourceCapabilitiesFetchResult capResult = capabilitiesFetchCache.fetch(
                    businessPath,
                    schemaBaseUrl,
                    tenantId,
                    userId,
                    environment);
            if (capResult != null && capResult.isSuccess()) {
                JsonNode capRoot = capResult.getCapabilities();
                if (capRoot != null && capRoot.path("data").path("stats").isObject()) {
                    capRoot = capRoot.path("data");
                }
                JsonNode statsFields = capRoot == null
                        ? MissingNode.getInstance()
                        : capRoot.path("stats").path("fields");
                if (hasTemporalMetricEvolutionStatsFields(statsFields)) {
                    contextHints.set("analyticsFields", statsFields.deepCopy());
                    temporalMetricEvolutionStatsAvailable = true;
                    modified = true;
                }
            }
        }

        if (temporalMetricEvolutionStatsAvailable
                && !contextHints.path("filterSchemaFields").isArray()
                && schemaFetchCache != null
                && schemaRetrievalService != null) {
            AiSchemaContext filterContext = AiSchemaContext.builder()
                    .path(businessPath + "/filter")
                    .operation("post")
                    .schemaType("request")
                    .build();
            SchemaFetchResult filterSchemaResult = schemaFetchCache.fetchPrincipalAware(
                    filterContext,
                    schemaBaseUrl,
                    tenantId,
                    userId,
                    environment);
            if (filterSchemaResult != null && filterSchemaResult.isSuccess() && filterSchemaResult.getSchema() != null) {
                ArrayNode filterFields = objectMapper.createArrayNode();
                collectFilterSchemaFieldHints(filterSchemaResult.getSchema(), filterFields, new LinkedHashSet<>());
                if (!filterFields.isEmpty()) {
                    contextHints.set("filterSchemaFields", filterFields);
                    modified = true;
                }
            }
        }

        if (!modified) {
            return request;
        }
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

    private boolean hasTemporalMetricEvolutionStatsFields(JsonNode statsFields) {
        if (statsFields == null || !statsFields.isArray() || statsFields.isEmpty()) {
            return false;
        }
        boolean hasTimeSeries = false;
        boolean hasMetric = false;
        for (JsonNode field : statsFields) {
            if (field == null || !field.isObject()) {
                continue;
            }
            if (field.path("timeSeriesEligible").asBoolean(false)) {
                hasTimeSeries = true;
            }
            if (field.path("metricFieldEligible").asBoolean(false)) {
                hasMetric = true;
            }
            if (hasTimeSeries && hasMetric) {
                return true;
            }
        }
        return false;
    }

    private void collectFilterSchemaFieldHints(
            JsonNode node,
            ArrayNode target,
            Set<String> seenFields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                String fieldName = entry.getKey();
                JsonNode prop = entry.getValue();
                if (value(fieldName).isBlank() || !seenFields.add(normalize(fieldName))) {
                    return;
                }
                JsonNode xUi = prop.path("x-ui");
                if ((xUi.isMissingNode() || xUi.isNull()) && prop.path("items").path("x-ui").isObject()) {
                    xUi = prop.path("items").path("x-ui");
                }
                ObjectNode item = target.addObject();
                item.put("fieldName", fieldName);
                item.put("label", firstNonBlank(firstNonBlank(xUi.path("label").asText(""), prop.path("title").asText("")), fieldName));
                item.put("type", prop.path("type").asText(""));
                item.put("format", prop.path("format").asText(""));
                item.put("controlType", xUi.path("controlType").asText(""));
                String endpoint = xUi.path("endpoint").asText("");
                if (!endpoint.isBlank()) {
                    item.put("endpoint", endpoint);
                }
                JsonNode optionSource = xUi.path("optionSource");
                if (optionSource.isObject()) {
                    item.set("optionSource", optionSource.deepCopy());
                    String optionSourceType = optionSource.path("type").asText("");
                    if (!optionSourceType.isBlank()) {
                        item.put("optionSourceType", optionSourceType);
                    }
                }
                if (prop.path("enum").isArray() && !prop.path("enum").isEmpty()) {
                    item.put("semanticKind", "categorical");
                }
            });
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectFilterSchemaFieldHints(entry.getValue(), target, seenFields));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectFilterSchemaFieldHints(item, target, seenFields);
            }
        }
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
                    .sorted(java.util.Comparator.comparingInt(this::tableColumnPriority))
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

    private int tableColumnPriority(SchemaFieldDescriptor field) {
        String name = AgenticAuthoringTextNormalizationSupport.normalize(field == null ? "" : field.name());
        if (Set.of("funcionarionome", "nomecompleto", "fullname", "displayname", "nome", "name", "title", "titulo")
                .contains(name)) {
            return 10;
        }
        if (Set.of("salario", "valor", "valortotal", "total", "precoreferencia", "preco").contains(name)) {
            return 20;
        }
        if (Set.of("datafim", "datademissao", "dataencerramento", "datatermino", "createdat", "updatedat", "deletedat")
                .contains(name)) {
            return 80;
        }
        if (Set.of("id", "uuid", "codigo", "code").contains(name)) {
            return 90;
        }
        return 50;
    }

    private ObjectNode tableColumnFromSchemaField(SchemaFieldDescriptor field) {
        ObjectNode column = objectMapper.createObjectNode();
        column.put("field", field.name());
        column.put("header", firstNonBlank(field.label(), field.name()));
        String type = tableColumnType(field);
        if (!type.isBlank()) {
            column.put("type", type);
        }
        if ("number".equals(type) && isMonetarySchemaField(field)) {
            column.put("format", "BRL|symbol|2");
        }
        return column;
    }

    private boolean isMonetarySchemaField(SchemaFieldDescriptor field) {
        if (field == null) {
            return false;
        }
        String combined = AgenticAuthoringTextNormalizationSupport.normalize(field.name() + " " + field.label());
        for (String token : List.of(
                "salario", "remuneracao", "valor", "preco", "price", "custo", "cost",
                "receita", "revenue", "despesa", "faturamento", "orcamento", "budget",
                "liquido", "bruto", "folha", "bonus", "provento", "desconto", "montante")) {
            if (combined.contains(token)) {
                return true;
            }
        }
        return false;
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

    /**
     * The public Chart contract is {@code inputs.chartDocument}.  The preview pipeline predates that
     * contract and still contains a number of schema/interaction guards expressed against its former
     * internal projection. Keep that projection strictly ephemeral: it lets the existing governed
     * checks run without publishing or persisting a second Chart dialect.
     */
    private JsonNode prepareCanonicalChartsForPreview(JsonNode uiCompositionPlan) {
        if (!(uiCompositionPlan instanceof ObjectNode plan) || !plan.path("widgets").isArray()) {
            return uiCompositionPlan;
        }
        boolean hasCanonicalChart = false;
        for (JsonNode widget : plan.path("widgets")) {
            if ("praxis-chart".equals(widget.path("componentId").asText(""))
                    && widget.path("inputs").path("chartDocument").isObject()) {
                hasCanonicalChart = true;
                break;
            }
        }
        if (!hasCanonicalChart) {
            return uiCompositionPlan;
        }
        ObjectNode copy = plan.deepCopy();
        for (JsonNode widget : copy.path("widgets")) {
            if (!(widget instanceof ObjectNode chart)
                    || !"praxis-chart".equals(chart.path("componentId").asText(""))) {
                continue;
            }
            ObjectNode inputs = chart.path("inputs") instanceof ObjectNode value ? value : null;
            if (inputs == null || !inputs.path("chartDocument").isObject() || inputs.path("config").isObject()) {
                continue;
            }
            ObjectNode document = (ObjectNode) inputs.path("chartDocument");
            ObjectNode config = inputs.putObject("config");
            config.put("type", document.path("kind").asText("bar"));
            copyText(document, config, "title");
            copyText(document, config, "subtitle");

            JsonNode dimension = document.path("dimensions").isArray() && !document.path("dimensions").isEmpty()
                    ? document.path("dimensions").get(0)
                    : MissingNode.getInstance();
            ObjectNode axis = config.putObject("semanticAxis");
            axis.put("field", dimension.path("field").asText(""));
            axis.put("label", dimension.path("label").asText(dimension.path("field").asText("")));
            axis.put("concept", dimension.path("role").asText("category"));
            hydratePreviewSemanticAxis(copy, axis);
            ObjectNode x = config.putObject("axes").putObject("x");
            x.put("field", axis.path("field").asText(""));
            x.put("label", axis.path("label").asText(""));
            if ("time".equalsIgnoreCase(dimension.path("role").asText(""))) {
                x.put("type", "time");
            }
            ObjectNode dataSource = config.putObject("dataSource");
            dataSource.put("resourcePath", document.path("source").path("resource").asText(""));
            ObjectNode query = dataSource.putObject("query");
            String operation = document.path("source").path("operation").asText("group-by");
            query.put("statsOperation", operation);
            query.put("sourceKind", document.path("source").path("kind").asText(""));
            String resource = document.path("source").path("resource").asText("");
            if (!resource.isBlank()) {
                query.put("statsPath", resource + "/stats/" + operation);
            }
            query.set("dimensions", document.path("dimensions").deepCopy());
            query.set("metrics", document.path("metrics").deepCopy());
            JsonNode options = document.path("source").path("options");
            if (options.isObject()) {
                query.set("sourceOptions", options.deepCopy());
                if (options.has("granularity")) {
                    query.put("granularity", options.path("granularity").asText(""));
                }
            }
            ObjectNode statsRequest = query.putObject("statsRequest");
            statsRequest.put("field", axis.path("field").asText(""));
            statsRequest.putObject("filter");
            if (document.path("metrics").isArray() && !document.path("metrics").isEmpty()) {
                if ("comparison".equalsIgnoreCase(operation)) {
                    statsRequest.set("metrics", document.path("metrics").deepCopy());
                    String periodField = document.path("source").path("options")
                            .path("comparisonPeriod").path("field").asText("");
                    if (!periodField.isBlank()) {
                        statsRequest.put("periodField", periodField);
                    }
                } else if (document.path("metrics").get(0) instanceof ObjectNode firstMetric) {
                    ObjectNode statsMetric = firstMetric.deepCopy();
                    String aggregation = statsMetric.path("aggregation").asText("").trim();
                    if (statsMetric.path("operation").asText("").isBlank() && !aggregation.isBlank()) {
                        statsMetric.put("operation", aggregation.toUpperCase(Locale.ROOT).replace('-', '_'));
                    }
                    if (statsMetric.path("alias").asText("").isBlank()) {
                        String metricField = statsMetric.path("field").asText("").trim();
                        statsMetric.put(
                                "alias",
                                "count".equalsIgnoreCase(aggregation) || metricField.isBlank() ? "total" : metricField);
                    }
                    statsRequest.set("metric", statsMetric);
                } else {
                    statsRequest.set("metric", document.path("metrics").get(0).deepCopy());
                }
            }
            ArrayNode series = config.putArray("series");
            for (JsonNode metric : document.path("metrics")) {
                ObjectNode item = series.addObject();
                item.put("categoryField", axis.path("field").asText(""));
                item.set("metric", metric.deepCopy());
            }
            ObjectNode interactions = config.putObject("interactions");
            ObjectNode actions = interactions.putObject("eventActions");
            if (document.path("events").isObject()) {
                actions.setAll((ObjectNode) document.path("events").deepCopy());
                interactions.put("crossFilter", document.path("events").has("crossFilter"));
            }
        }
        return copy;
    }

    private void finalizeCanonicalChartsAfterPreview(ObjectNode plan) {
        if (plan == null || !plan.path("widgets").isArray()) {
            return;
        }
        for (JsonNode widget : plan.path("widgets")) {
            if (!(widget instanceof ObjectNode chart)
                    || !"praxis-chart".equals(chart.path("componentId").asText(""))) {
                continue;
            }
            ObjectNode inputs = chart.path("inputs") instanceof ObjectNode value ? value : null;
            ObjectNode config = inputs != null && inputs.path("config") instanceof ObjectNode value ? value : null;
            ObjectNode document = inputs != null && inputs.path("chartDocument") instanceof ObjectNode value ? value : null;
            if (inputs == null || config == null || document == null) {
                continue;
            }
            document.put("kind", config.path("type").asText(document.path("kind").asText("bar")));
            copyText(config, document, "title");
            copyText(config, document, "subtitle");
            ObjectNode axis = config.path("semanticAxis") instanceof ObjectNode value ? value : null;
            if (axis != null) {
                ArrayNode dimensions = document.path("dimensions") instanceof ArrayNode value
                        ? value : document.putArray("dimensions");
                ObjectNode dimension;
                if (dimensions.isEmpty()) {
                    dimension = dimensions.addObject();
                } else if (dimensions.get(0) instanceof ObjectNode existingDimension) {
                    dimension = existingDimension;
                } else {
                    dimension = objectMapper.createObjectNode();
                    dimensions.set(0, dimension);
                }
                String executionField = !axis.path("statsExecutionField").asText("").isBlank()
                        ? axis.path("statsExecutionField").asText("").trim()
                        : axis.path("field").asText("");
                String displayLabel = axis.path("label").asText(axis.path("field").asText(executionField));
                dimension.put("field", executionField);
                dimension.put("label", displayLabel);
                String statsOperation = config.path("dataSource").path("query").path("statsOperation").asText("");
                dimension.put("role", "timeseries".equalsIgnoreCase(statsOperation) ? "time" : "category");
                if (config.path("axes").path("x") instanceof ObjectNode xAxis && !executionField.isBlank()) {
                    xAxis.put("field", executionField);
                }
                if (config.path("series") instanceof ArrayNode seriesArray && !executionField.isBlank()) {
                    for (JsonNode item : seriesArray) {
                        if (item instanceof ObjectNode seriesItem) {
                            seriesItem.put("categoryField", executionField);
                        }
                    }
                }
                if (config.path("dataSource").path("query").path("statsRequest") instanceof ObjectNode statsRequest
                        && !executionField.isBlank()) {
                    statsRequest.put("field", executionField);
                    if (!statsRequest.path("filter").isObject()) {
                        statsRequest.putObject("filter");
                    }
                }
                synchronizeDiagnosticAxis(plan, axis);
            }
            ObjectNode source = document.path("source") instanceof ObjectNode value ? value : document.putObject("source");
            ObjectNode query = config.path("dataSource").path("query") instanceof ObjectNode value ? value : null;
            if (query != null) {
                String resource = config.path("dataSource").path("resourcePath").asText(source.path("resource").asText(""));
                if (!resource.isBlank()) source.put("resource", businessResourcePath(resource));
                String operation = query.path("statsOperation").asText(source.path("operation").asText("group-by"));
                source.put("operation", operation);
                ObjectNode options = source.path("options") instanceof ObjectNode value ? value : source.putObject("options");
                if (query.has("granularity")) options.put("granularity", query.path("granularity").asText(""));
                if ("timeseries".equalsIgnoreCase(operation)) {
                    options.put("fillGaps", query.path("statsRequest").path("fillGaps").asBoolean(false));
                }
                if (query.path("metrics").isArray()) {
                    ArrayNode documentMetrics = query.path("metrics").deepCopy();
                    for (JsonNode metric : documentMetrics) {
                        if (metric instanceof ObjectNode outputMetric
                                && "count".equals(normalize(outputMetric.path("aggregation").asText("")))) {
                            // COUNT is field-free in the HTTP request. The chart
                            // document still needs the returned measure identity.
                            outputMetric.put("field", outputMetric.path("alias").asText("total"));
                            outputMetric.remove("alias");
                        }
                    }
                    document.set("metrics", documentMetrics);
                }
            }
            JsonNode actions = config.path("interactions").path("eventActions");
            if (actions.isObject()) document.set("events", actions.deepCopy());
            // `config` remains a derived preview projection for existing Page Builder compositions.
            // The executable Chart state is synchronized into chartDocument above; no decision,
            // validator or compiler consumes this projection as an alternate source of truth.
        }
    }

    private void copyText(JsonNode source, ObjectNode target, String field) {
        if (source != null && source.has(field) && target != null) {
            target.put(field, source.path(field).asText(""));
        }
    }

    private void hydratePreviewSemanticAxis(ObjectNode plan, ObjectNode axis) {
        JsonNode axes = plan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) return;
        String field = normalize(axis.path("field").asText(""));
        for (JsonNode candidate : axes) {
            if (!(candidate instanceof ObjectNode diagnostic)) continue;
            String diagnosticField = normalize(diagnostic.path("field").asText(""));
            String requested = normalize(diagnostic.path("requestedField").asText(""));
            String executionField = normalize(diagnostic.path("statsExecutionField").asText(""));
            if (field.equals(diagnosticField)
                    || field.equals(requested)
                    || (!executionField.isBlank() && field.equals(executionField))) {
                axis.setAll(diagnostic.deepCopy());
                return;
            }
        }
    }

    private void synchronizeDiagnosticAxis(ObjectNode plan, ObjectNode axis) {
        JsonNode axes = plan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) return;
        String field = normalize(axis.path("field").asText(""));
        String requested = normalize(axis.path("requestedField").asText(""));
        String executionField = normalize(axis.path("statsExecutionField").asText(""));
        for (JsonNode candidate : axes) {
            if (!(candidate instanceof ObjectNode diagnostic)) continue;
            String diagnosticField = normalize(diagnostic.path("field").asText(""));
            String diagnosticRequested = normalize(diagnostic.path("requestedField").asText(""));
            String diagnosticExecution = normalize(diagnostic.path("statsExecutionField").asText(""));
            if (!field.equals(diagnosticField) && !field.equals(diagnosticRequested)
                    && !requested.equals(diagnosticField) && !requested.equals(diagnosticRequested)
                    && (executionField.isBlank() || (!executionField.equals(diagnosticField) && !executionField.equals(diagnosticExecution)))) continue;
            diagnostic.setAll(axis.deepCopy());
            return;
        }
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

    private JsonNode groundDataQueryContextFilters(
            AgenticAuthoringPlanRequest request,
            JsonNode uiCompositionPlan,
            List<String> warnings,
            String schemaBaseUrl,
            PreviewSchemaFetchCache schemaFetchCache) {
        if (!containsQueryContextFilters(uiCompositionPlan)) {
            return uiCompositionPlan;
        }
        JsonNode copy = uiCompositionPlan.deepCopy();
        Optional<Map<String, SchemaFieldDescriptor>> filterFields =
                filterSchemaFields(request, schemaBaseUrl, schemaFetchCache);
        if (filterFields.isEmpty() || filterFields.get().isEmpty()) {
            markQueryContextFilterGroundingIncomplete(copy, warnings);
            return copy;
        }
        if (copy.path("widgets") instanceof ArrayNode widgets) {
            for (JsonNode widget : widgets) {
                alignDataQueryContextFilters(widget, filterFields.get(), null, warnings);
            }
        }
        boolean incomplete = warnings.contains("table-query-filter-schema-grounding-incomplete")
                || warnings.contains("crud-query-filter-schema-grounding-incomplete");
        if (!incomplete && copy instanceof ObjectNode plan) {
            ObjectNode diagnostics = plan.path("diagnostics") instanceof ObjectNode existing
                    ? existing
                    : plan.putObject("diagnostics");
            diagnostics.put("queryConstraintsMaterialized", true);
            diagnostics.put("queryConstraintFieldsSchemaVerified", true);
        }
        return copy;
    }

    private boolean containsQueryContextFilters(JsonNode uiCompositionPlan) {
        JsonNode widgets = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("widgets");
        if (!widgets.isArray()) {
            return false;
        }
        for (JsonNode widget : widgets) {
            if (queryContextFilters(widget) instanceof ObjectNode filters && !filters.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void markQueryContextFilterGroundingIncomplete(JsonNode uiCompositionPlan, List<String> warnings) {
        JsonNode widgets = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("widgets");
        if (!widgets.isArray()) {
            return;
        }
        for (JsonNode widget : widgets) {
            if (!(queryContextFilters(widget) instanceof ObjectNode filters) || filters.isEmpty()) {
                continue;
            }
            addWarningOnce(warnings, queryFilterWarning(widget, "schema-grounding-incomplete"));
        }
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
        return statsPreviewVerifier.verifyStatsCapabilities(
                request,
                uiCompositionPlan,
                warnings,
                requestBaseUrl,
                tenantId,
                userId,
                environment,
                resourceCapabilitiesRetrievalService != null,
                capabilitiesFetchCache == null ? null : capabilitiesFetchCache::fetch);
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
                        chartInteractionProjectionReconciler.disableUngroundedTemporalFilterInteractions(
                                plan,
                                chart,
                                chartKey);
                    }
                    addWarningOnce(warnings, "semantic-chart-temporal-range-filter-target-unresolved");
                }
                if (semanticAxis.path("statsEvidence").path("keyAndLabelDistinct").asBoolean(false)) {
                    addWarningOnce(warnings, "semantic-chart-interaction-key-filter-target-unresolved");
                }
                continue;
            }
            ChartInteractionProjection resolved = projection.get();
            reconciled = chartInteractionProjectionReconciler.reconcileChartEventMappings(chart, resolved) || reconciled;
            String chartKey = firstNonBlank(chart.path("key").asText(""), chart.path("id").asText(""));
            if (!chartKey.isBlank()) {
                reconciled = chartInteractionProjectionReconciler.reconcileChartInteractionBindings(
                        plan,
                        chartKey,
                        resolved) || reconciled;
            }
            reconciled = chartInteractionProjectionReconciler.reconcileItemTemplateFields(plan, resolved) || reconciled;
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
        return AgenticAuthoringRelatedResourceSurfaceVerifier.findSurface(surfaces, surfaceId);
    }

    private RelatedResourceSurfaceVerification verifyRelatedResourceSurfaces(
            AgenticAuthoringPlanRequest request,
            JsonNode uiCompositionPlan,
            String schemaBaseUrl,
            String tenantId,
            String userId,
            String environment,
            PreviewResourceSurfaceCatalogFetchCache surfaceCatalogFetchCache) {
        return relatedResourceSurfaceVerifier.verifyRelatedResourceSurfaces(
                request,
                uiCompositionPlan,
                schemaBaseUrl,
                tenantId,
                userId,
                environment,
                surfaceCatalogFetchCache == null ? null : surfaceCatalogFetchCache::fetch);
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
        semanticAxisSchemaVerifier.reconcileSemanticAxesWithSchema(
                request,
                uiCompositionPlan,
                schemaFields,
                filterSchemaFields,
                statsRequestFields,
                schemaResult,
                warnings,
                allowSchemaSafeAxisRepair);
    }

    private void alignDataQueryContextFilters(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> filterSchemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        semanticAxisSchemaVerifier.alignDataQueryContextFilters(
                widget,
                filterSchemaFields,
                schemaResult,
                warnings);
    }

    private JsonNode queryContextFilters(JsonNode widget) {
        return semanticAxisSchemaVerifier.queryContextFilters(widget);
    }

    private String queryFilterWarning(JsonNode widget, String suffix) {
        return semanticAxisSchemaVerifier.queryFilterWarning(widget, suffix);
    }

    private void normalizeFilterQueryContextBindings(
            ObjectNode uiCompositionPlan,
            List<String> warnings) {
        semanticAxisSchemaVerifier.normalizeFilterQueryContextBindings(uiCompositionPlan, warnings);
    }

    private boolean allowsSchemaSafeAxisRepair(AgenticAuthoringPlanRequest request) {
        return semanticAxisSchemaVerifier.allowsSchemaSafeAxisRepair(request);
    }

    private Optional<SchemaFieldDescriptor> preferredTemporalRangeFilterField(
            SchemaFieldDescriptor requestedField,
            Map<String, SchemaFieldDescriptor> schemaFields) {
        return semanticAxisSchemaVerifier.preferredTemporalRangeFilterField(requestedField, schemaFields);
    }

    private boolean isTemporalRangeFilterField(SchemaFieldDescriptor field) {
        return semanticAxisSchemaVerifier.isTemporalRangeFilterField(field);
    }

    private Optional<SchemaFieldDescriptor> preferredFilterInputField(
            SchemaFieldDescriptor requestedField,
            Map<String, SchemaFieldDescriptor> schemaFields) {
        return semanticAxisSchemaVerifier.preferredFilterInputField(requestedField, schemaFields);
    }

    private SchemaFieldDescriptor schemaFieldDescriptor(String field, JsonNode property) {
        return semanticAxisSchemaVerifier.schemaFieldDescriptor(field, property);
    }

    private Set<String> tokens(String value) {
        return semanticAxisSchemaVerifier.tokens(value);
    }

    private void addWarningOnce(List<String> warnings, String warning) {
        if (warnings != null && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private boolean containsUnverifiedSemanticAxes(JsonNode uiCompositionPlan) {
        return semanticAxisSchemaVerifier.containsUnverifiedSemanticAxes(uiCompositionPlan);
    }

    private boolean containsUnverifiedStatsAxes(JsonNode uiCompositionPlan) {
        return statsPreviewVerifier.containsUnverifiedStatsAxes(uiCompositionPlan);
    }

    private void markOrphanUnverifiedSemanticAxesAsDropped(
            ObjectNode uiCompositionPlan,
            List<String> warnings) {
        semanticAxisSchemaVerifier.markOrphanUnverifiedSemanticAxesAsDropped(uiCompositionPlan, warnings);
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
        return assistantMessageSupport.deterministicPreviewAssistantMessage(
                request,
                intentResolution,
                uiCompositionPlan,
                valid,
                failureCodes);
    }

    private boolean containsComponent(JsonNode uiCompositionPlan, String componentId) {
        return AgenticAuthoringSemanticMaterializationPolicy.containsComponent(uiCompositionPlan, componentId);
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
        return AgenticAuthoringProjectKnowledgeAuditFactory.create(
                objectMapper,
                request == null ? null : request.contextHints(),
                minimalFormPlan,
                compiledFormPatch);
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
            if (result == null) {
                result = schemaRetrievalService.fetchSchemaResult(context, schemaBaseUrl);
            }
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

    record SchemaFieldDescriptor(
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
}
