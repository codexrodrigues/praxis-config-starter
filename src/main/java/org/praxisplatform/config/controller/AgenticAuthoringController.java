package org.praxisplatform.config.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactSource;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentCapabilitiesResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentCapabilitiesService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringConsultativeAnswer;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringConsultativeAnswerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunErrorResponse;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringCompileRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringCompileResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentResolutionRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentResolutionResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentResolverService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPatchCompilerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPersistedUiCompositionSourceResolver;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringResourceCandidatesRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringResourceCandidatesResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringResourceDiscoveryService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamService;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.UserConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/praxis/config/ai/authoring")
@ConditionalOnProperty(prefix = "praxis.ai.authoring", name = "http-enabled", havingValue = "true")
public class AgenticAuthoringController {

    private static final String RULE_DEFINITION_READER_ROLE = "RULE_DEFINITION_READER";

    private final AgenticAuthoringDryRunService dryRunService;
    private final AgenticAuthoringArtifactSource artifactSource;
    private final AgenticAuthoringIntentResolverService intentResolverService;
    private final AgenticAuthoringPlanService planService;
    private final AgenticAuthoringPatchCompilerService patchCompilerService;
    private final AgenticAuthoringPreviewService previewService;
    private final AgenticAuthoringApplyService applyService;
    private final AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService;
    private final AgenticAuthoringResourceDiscoveryService resourceDiscoveryService;
    private final AgenticAuthoringTurnStreamService turnStreamService;
    private final AiPrincipalContextResolver principalContextResolver;
    private final AiStreamAccessTokenService streamAccessTokenService;
    private final AgenticAuthoringConsultativeAnswerService consultativeAnswerService;
    private final AgenticAuthoringPersistedUiCompositionSourceResolver persistedUiCompositionSourceResolver;
    private final boolean corporateMode;

    @Autowired
    public AgenticAuthoringController(
            AgenticAuthoringDryRunService dryRunService,
            AgenticAuthoringArtifactSource artifactSource,
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            AgenticAuthoringPreviewService previewService,
            AgenticAuthoringApplyService applyService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService,
            AgenticAuthoringResourceDiscoveryService resourceDiscoveryService,
            AgenticAuthoringTurnStreamService turnStreamService,
            AiPrincipalContextResolver principalContextResolver,
            AiStreamAccessTokenService streamAccessTokenService,
            AgenticAuthoringConsultativeAnswerService consultativeAnswerService,
            @Nullable AgenticAuthoringPersistedUiCompositionSourceResolver persistedUiCompositionSourceResolver,
            @Value("${praxis.ai.security.corporate-mode:true}") boolean corporateMode) {
        this.dryRunService = dryRunService;
        this.artifactSource = artifactSource;
        this.intentResolverService = intentResolverService;
        this.planService = planService;
        this.patchCompilerService = patchCompilerService;
        this.previewService = previewService;
        this.applyService = applyService;
        this.componentCapabilitiesService = componentCapabilitiesService;
        this.resourceDiscoveryService = resourceDiscoveryService;
        this.turnStreamService = turnStreamService;
        this.principalContextResolver = principalContextResolver;
        this.streamAccessTokenService = streamAccessTokenService;
        this.consultativeAnswerService = consultativeAnswerService;
        this.persistedUiCompositionSourceResolver = persistedUiCompositionSourceResolver;
        this.corporateMode = corporateMode;
    }

    public AgenticAuthoringController(
            AgenticAuthoringDryRunService dryRunService,
            AgenticAuthoringArtifactSource artifactSource,
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            AgenticAuthoringPreviewService previewService,
            AgenticAuthoringApplyService applyService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService,
            AgenticAuthoringResourceDiscoveryService resourceDiscoveryService,
            AgenticAuthoringTurnStreamService turnStreamService,
            AiPrincipalContextResolver principalContextResolver,
            AiStreamAccessTokenService streamAccessTokenService,
            AgenticAuthoringConsultativeAnswerService consultativeAnswerService) {
        this(
                dryRunService,
                artifactSource,
                intentResolverService,
                planService,
                patchCompilerService,
                previewService,
                applyService,
                componentCapabilitiesService,
                resourceDiscoveryService,
                turnStreamService,
                principalContextResolver,
                streamAccessTokenService,
                consultativeAnswerService,
                null,
                true);
    }

    public AgenticAuthoringController(
            AgenticAuthoringDryRunService dryRunService,
            AgenticAuthoringArtifactSource artifactSource,
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            AgenticAuthoringPreviewService previewService,
            AgenticAuthoringApplyService applyService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService,
            AgenticAuthoringResourceDiscoveryService resourceDiscoveryService,
            AgenticAuthoringTurnStreamService turnStreamService,
            AiPrincipalContextResolver principalContextResolver,
            AiStreamAccessTokenService streamAccessTokenService) {
        this(
                dryRunService,
                artifactSource,
                intentResolverService,
                planService,
                patchCompilerService,
                previewService,
                applyService,
                componentCapabilitiesService,
                resourceDiscoveryService,
                turnStreamService,
                principalContextResolver,
                streamAccessTokenService,
                null);
    }

    public AgenticAuthoringController(
            AgenticAuthoringDryRunService dryRunService,
            AgenticAuthoringArtifactSource artifactSource,
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            AgenticAuthoringPreviewService previewService,
            AgenticAuthoringApplyService applyService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService,
            AgenticAuthoringResourceDiscoveryService resourceDiscoveryService) {
        this(
                dryRunService,
                artifactSource,
                intentResolverService,
                planService,
                patchCompilerService,
                previewService,
                applyService,
                componentCapabilitiesService,
                resourceDiscoveryService,
                null,
                null,
                null,
                null);
    }

    @GetMapping("/component-capabilities")
    @Operation(
            summary = "Consultar capacidades governadas de authoring por componente",
            description = "Publica as operações declaradas pelos manifests de componentes e informa se a resposta veio da revisão corrente do AI Registry, do último catálogo governado válido ou de uma contingência embutida.")
    public ResponseEntity<AgenticAuthoringComponentCapabilitiesResult> listComponentCapabilities() {
        return ResponseEntity.ok(componentCapabilitiesService.listCapabilities());
    }

    @PostMapping("/resource-candidates")
    public ResponseEntity<AgenticAuthoringResourceCandidatesResult> searchResourceCandidates(
            @RequestBody AgenticAuthoringResourceCandidatesRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(resourceDiscoveryService.search(
                request,
                resolveAuthoringPrincipalContext(servletRequest, tenantId, userId, environment)));
    }

    @PostMapping("/turn/stream/start")
    public ResponseEntity<AgenticAuthoringTurnStreamStartResponse> startTurnStream(
            @RequestBody AgenticAuthoringTurnStreamRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        requireTurnStreamSupport();
        request = withoutClientVerifiedDomainOperations(request);
        requireSelectedDomainDecisionReadAccess(request, servletRequest);
        AiPrincipalContext principalContext = principalContextResolver.resolve(
                servletRequest,
                tenantId,
                userId,
                environment);
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        AgenticAuthoringTurnStreamService.StartResult result = turnStreamService.start(
                request,
                baseUrl,
                principalContext);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }

    @GetMapping(path = "/turn/stream/{streamId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connectTurnStream(
            @PathVariable UUID streamId,
            HttpServletRequest servletRequest,
            @RequestParam(value = "lastEventId", required = false) String lastEventIdParam,
            @RequestParam(value = "accessToken", required = false) String accessToken,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        requireTurnStreamSupport();
        AiPrincipalContext principalContext = resolveStreamPrincipalContext(
                streamId,
                servletRequest,
                tenantId,
                userId,
                environment,
                accessToken);
        String resolvedLastEventId = firstNonBlank(lastEventIdHeader, lastEventIdParam, null);
        return turnStreamService.connect(streamId, resolvedLastEventId, principalContext);
    }

    @GetMapping("/turn/stream/{streamId}/probe")
    public ResponseEntity<Void> probeTurnStream(
            @PathVariable UUID streamId,
            HttpServletRequest servletRequest,
            @RequestParam(value = "accessToken", required = false) String accessToken,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        requireTurnStreamSupport();
        turnStreamService.probe(streamId, resolveStreamPrincipalContext(
                streamId,
                servletRequest,
                tenantId,
                userId,
                environment,
                accessToken));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/turn/stream/{streamId}/cancel")
    public ResponseEntity<AiPatchStreamCancelResponse> cancelTurnStream(
            @PathVariable UUID streamId,
            HttpServletRequest servletRequest,
            @RequestParam(value = "accessToken", required = false) String accessToken,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        requireTurnStreamSupport();
        return ResponseEntity.ok(turnStreamService.cancel(streamId, resolveStreamPrincipalContext(
                streamId,
                servletRequest,
                tenantId,
                userId,
                environment,
                accessToken)));
    }

    @PostMapping("/dry-run")
    public ResponseEntity<?> runDryRun() {
        try {
            AgenticAuthoringDryRunResult result = dryRunService.run(artifactSource);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException | IOException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }

    @PostMapping("/minimal-form-plan")
    public ResponseEntity<?> generateMinimalFormPlan(
            @RequestBody AgenticAuthoringPlanRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        try {
            request = withoutClientVerifiedDomainOperations(request);
            AgenticAuthoringPlanResult result = previewService.generateMinimalFormPlan(
                    request,
                    tenantId,
                    userId,
                    environment,
                    currentContextBaseUrl());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException | IOException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }

    @PostMapping("/intent-resolution")
    public ResponseEntity<?> resolveIntent(
            @RequestBody AgenticAuthoringIntentResolutionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        try {
            request = withoutClientIntentPersistenceAuthority(request);
            AgenticAuthoringIntentResolutionResult result = intentResolverService.resolve(
                    request,
                    tenantId,
                    userId,
                    environment);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }

    public ResponseEntity<?> resolveIntent(AgenticAuthoringIntentResolutionRequest request) {
        return resolveIntent(request, null, null, null);
    }

    private AgenticAuthoringIntentResolutionRequest withoutClientIntentPersistenceAuthority(
            AgenticAuthoringIntentResolutionRequest request) {
        if (request == null || request.contextHints() == null || !request.contextHints().isObject()) {
            return request;
        }
        ObjectNode sanitized = ((ObjectNode) request.contextHints()).deepCopy();
        sanitized.remove("verifiedDomainOperations");
        sanitized.remove("authoringEvidence");
        sanitized.remove("uiCompositionAuthoringSource");
        sanitized.remove("agenticApplyTarget");
        return new AgenticAuthoringIntentResolutionRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                sanitized.isEmpty() ? null : sanitized,
                request.activeSemanticDecision());
    }

    @PostMapping("/compiled-form-patch")
    public ResponseEntity<?> compileFormPatch(@RequestBody AgenticAuthoringCompileRequest request) {
        try {
            AgenticAuthoringCompileResult result = patchCompilerService.compile(request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException | IOException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }

    @PostMapping("/page-preview")
    public ResponseEntity<?> previewPage(
            @RequestBody AgenticAuthoringPlanRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        try {
            request = withoutClientPreviewAuthority(request);
            AiPrincipalContext principalContext = resolveAuthoringPrincipalContext(
                    servletRequest,
                    tenantId,
                    userId,
                    environment);
            if (principalContext == null) {
                principalContext = new AiPrincipalContext(tenantId, userId, environment, false);
            }
            JsonNode persistedUiCompositionPlan = persistedUiCompositionSourceResolver == null
                    ? null
                    : persistedUiCompositionSourceResolver.resolvePlanForPreview(request, principalContext);
            AgenticAuthoringPlanRequest previewRequest = withoutAgenticApplyTarget(request);
            AgenticAuthoringPlanRequest effectiveRequest = withResolvedIntent(
                    previewRequest,
                    principalContext.tenantId(),
                    principalContext.userId(),
                    principalContext.environment());
            String baseUrl = currentContextBaseUrl();
            Optional<AgenticAuthoringPreviewResult> consultativePreview =
                    previewConsultativeSemanticIntent(
                            effectiveRequest,
                            principalContext.tenantId(),
                            principalContext.userId(),
                            principalContext.environment());
            if (consultativePreview.isPresent()) {
                return ResponseEntity.ok(consultativePreview.get());
            }
            AgenticAuthoringPreviewResult result = persistedUiCompositionPlan == null
                    ? previewService.preview(
                            effectiveRequest,
                            principalContext.tenantId(),
                            principalContext.userId(),
                            principalContext.environment(),
                            baseUrl)
                    : previewService.previewWithPersistedUiCompositionPlan(
                            effectiveRequest,
                            principalContext.tenantId(),
                            principalContext.userId(),
                            principalContext.environment(),
                            baseUrl,
                            persistedUiCompositionPlan);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException | IOException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }

    public ResponseEntity<?> previewPage(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment) {
        return previewPage(request, null, tenantId, userId, environment);
    }

    private AgenticAuthoringPlanRequest withoutClientPreviewAuthority(
            AgenticAuthoringPlanRequest request) {
        if (request == null || request.contextHints() == null || !request.contextHints().isObject()) {
            return request;
        }
        ObjectNode sanitized = ((ObjectNode) request.contextHints()).deepCopy();
        sanitized.remove("verifiedDomainOperations");
        sanitized.remove("authoringEvidence");
        sanitized.remove("uiCompositionAuthoringSource");
        return copyWithContextHints(request, sanitized.isEmpty() ? null : sanitized);
    }

    private AgenticAuthoringPlanRequest withoutClientVerifiedDomainOperations(
            AgenticAuthoringPlanRequest request) {
        if (request == null || request.contextHints() == null || !request.contextHints().isObject()) {
            return request;
        }
        ObjectNode sanitized = ((ObjectNode) request.contextHints()).deepCopy();
        sanitized.remove("verifiedDomainOperations");
        return copyWithContextHints(request, sanitized.isEmpty() ? null : sanitized);
    }

    private AgenticAuthoringPlanRequest withoutAgenticApplyTarget(AgenticAuthoringPlanRequest request) {
        if (request == null || request.contextHints() == null || !request.contextHints().isObject()) {
            return request;
        }
        ObjectNode sanitized = ((ObjectNode) request.contextHints()).deepCopy();
        sanitized.remove("agenticApplyTarget");
        return copyWithContextHints(request, sanitized.isEmpty() ? null : sanitized);
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
                request.attachmentSummaries(),
                contextHints);
    }

    private AgenticAuthoringTurnStreamRequest withoutClientVerifiedDomainOperations(
            AgenticAuthoringTurnStreamRequest request) {
        if (request == null || request.contextHints() == null || !request.contextHints().isObject()) {
            return request;
        }
        ObjectNode sanitized = ((ObjectNode) request.contextHints()).deepCopy();
        sanitized.remove("verifiedDomainOperations");
        return new AgenticAuthoringTurnStreamRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                sanitized.isEmpty() ? null : sanitized,
                request.componentCapabilities(),
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    private Optional<AgenticAuthoringPreviewResult> previewConsultativeSemanticIntent(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment) {
        if (consultativeAnswerService == null
                || request == null
                || !isConsultativeSemanticIntent(request.intentResolution())
                || request.pendingClarification() != null) {
            return Optional.empty();
        }
        AgenticAuthoringComponentCapabilitiesResult componentCapabilities =
                componentCapabilitiesService == null ? null : componentCapabilitiesService.listCapabilities();
        AgenticAuthoringConsultativeAnswer answer = consultativeAnswerService.answer(
                        request,
                        componentCapabilities,
                        tenantId,
                        userId,
                        environment)
                .orElse(null);
        if (answer == null || answer.assistantMessage() == null || answer.assistantMessage().isBlank()) {
            return Optional.empty();
        }
        String artifactKind = "domain_api".equals(answer.category()) ? "api_catalog" : "component";
        String operationKind = "api_catalog".equals(artifactKind) ? "explore" : "explain";
        List<String> warnings = new java.util.ArrayList<>(answer.warnings() == null ? List.of() : answer.warnings());
        warnings.add("preview-consultative-semantic-intent-used");
        warnings.add("preview-materialization-skipped-consultative-answer");
        AgenticAuthoringIntentResolutionResult intentResolution = new AgenticAuthoringIntentResolutionResult(
                true,
                operationKind,
                artifactKind,
                answer.changeKind(),
                "consultative",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                null,
                List.of(),
                new org.praxisplatform.config.ai.authoring.AgenticAuthoringGateResult(
                        "consultative-post-intent",
                        "eligible",
                        List.of()),
                request.userPrompt(),
                answer.assistantMessage(),
                MissingNode.getInstance(),
                List.of(),
                null,
                List.of(),
                List.copyOf(warnings),
                List.of(),
                MissingNode.getInstance(),
                MissingNode.getInstance(),
                null)
                .withAssistantContent(org.praxisplatform.config.ai.authoring.AgenticAuthoringAssistantContentFactory
                        .fromConsultativeProjection(answer.apiCatalogProjection()));
        return Optional.of(new AgenticAuthoringPreviewResult(
                true,
                List.of(),
                List.copyOf(warnings),
                MissingNode.getInstance(),
                MissingNode.getInstance(),
                new org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewDiagnostics(
                        false,
                        "",
                        intentResolution.operationKind(),
                        intentResolution.changeKind(),
                        "consultative-semantic-intent"),
                MissingNode.getInstance(),
                answer.assistantMessage()));
    }

    private boolean isConsultativeSemanticIntent(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return false;
        }
        return equalsIgnoreCase(intentResolution.authoringProfile(), "consultative")
                || equalsIgnoreCase(intentResolution.operationKind(), "explain")
                || equalsIgnoreCase(intentResolution.operationKind(), "explore");
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private AgenticAuthoringPlanRequest withResolvedIntent(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment) {
        if (request == null
                || request.intentResolution() != null
                || intentResolverService == null
                || request.pendingClarification() != null
                || (request.conversationMessages() != null && !request.conversationMessages().isEmpty())) {
            return request;
        }
        AgenticAuthoringIntentResolutionResult intentResolution = intentResolverService.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        request.userPrompt(),
                        "praxis-ui-angular",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        request.currentPage(),
                        null,
                        request.provider(),
                        request.model(),
                        request.apiKey(),
                        request.sessionId(),
                        request.clientTurnId(),
                        request.conversationMessages(),
                        request.pendingClarification(),
                        request.attachmentSummaries(),
                        request.contextHints()),
                tenantId,
                userId,
                environment);
        if (intentResolution == null) {
            return request;
        }
        return new AgenticAuthoringPlanRequest(
                request.userPrompt(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.currentPage(),
                intentResolution,
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                request.contextHints());
    }

    private String currentContextBaseUrl() {
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    @PostMapping("/page-apply")
    public ResponseEntity<?> applyPage(
            @RequestBody AgenticAuthoringApplyRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestHeader(value = "X-Updated-By", required = false) String updatedBy,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        try {
            AiPrincipalContext principalContext = principalContextResolver.resolve(
                    servletRequest,
                    tenantId,
                    userId,
                    environment);
            AgenticAuthoringApplyResult result = applyService.apply(request, principalContext, updatedBy, ifMatch);
            String etag = result.etag() == null ? null : "\"" + result.etag() + "\"";
            return ResponseEntity.ok().eTag(etag).body(result);
        } catch (UserConfigService.PreconditionFailedException ex) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }

    private AiPrincipalContext resolveStreamPrincipalContext(
            UUID streamId,
            HttpServletRequest servletRequest,
            String tenantId,
            String userId,
            String environment,
            String accessToken) {
        AiPrincipalContext principalContext;
        try {
            principalContext = principalContextResolver.resolve(
                    servletRequest,
                    tenantId,
                    userId,
                    environment);
        } catch (ResponseStatusException ex) {
            boolean identityStatus = HttpStatus.FORBIDDEN.equals(ex.getStatusCode())
                    || HttpStatus.UNAUTHORIZED.equals(ex.getStatusCode());
            boolean signedMode = streamAccessTokenService.isSignedUrlTokenMode();
            boolean hasAccessToken = accessToken != null && !accessToken.isBlank();
            if (identityStatus && signedMode && hasAccessToken) {
                principalContext = null;
            } else {
                throw ex;
            }
        }
        if (streamAccessTokenService.isSignedUrlTokenMode()
                && accessToken != null
                && !accessToken.isBlank()
                && shouldUseSignedTokenPrincipalContext(tenantId, userId, environment, principalContext)) {
            principalContext = null;
        }
        return streamAccessTokenService.resolvePrincipalContext(streamId, accessToken, principalContext);
    }

    private AiPrincipalContext resolveAuthoringPrincipalContext(
            HttpServletRequest servletRequest,
            String tenantId,
            String userId,
            String environment) {
        if (principalContextResolver == null) {
            return new AiPrincipalContext(tenantId, userId, environment, false);
        }
        return principalContextResolver.resolve(servletRequest, tenantId, userId, environment);
    }

    private boolean shouldUseSignedTokenPrincipalContext(
            String tenantId,
            String userId,
            String environment,
            AiPrincipalContext principalContext) {
        if (principalContext == null) {
            return true;
        }
        if (!principalContext.resolvedFromServerPrincipal()) {
            return true;
        }
        return firstNonBlank(tenantId, userId, environment, null) == null;
    }

    private void requireTurnStreamSupport() {
        if (turnStreamService == null || principalContextResolver == null || streamAccessTokenService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agentic authoring stream is not configured.");
        }
    }

    private void requireSelectedDomainDecisionReadAccess(
            AgenticAuthoringTurnStreamRequest request,
            HttpServletRequest servletRequest) {
        if (!corporateMode || !hasSelectedDomainDecisionRef(request)) {
            return;
        }
        if (servletRequest == null || servletRequest.getUserPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        if (!servletRequest.isUserInRole(RULE_DEFINITION_READER_ROLE)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Authenticated principal requires IAM role " + RULE_DEFINITION_READER_ROLE + ".");
        }
    }

    private boolean hasSelectedDomainDecisionRef(AgenticAuthoringTurnStreamRequest request) {
        return request != null
                && request.contextHints() != null
                && request.contextHints().isObject()
                && request.contextHints().has("selectedDomainDecisionRef")
                && !request.contextHints().get("selectedDomainDecisionRef").isNull();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim();
            if (!normalized.isEmpty() && !"null".equalsIgnoreCase(normalized)) {
                return normalized;
            }
        }
        return null;
    }
}
