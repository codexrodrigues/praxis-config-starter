package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactSource;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentCapabilitiesResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentCapabilitiesService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.praxisplatform.config.service.UserConfigService;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/praxis/config/ai/authoring")
@ConditionalOnProperty(prefix = "praxis.ai.authoring", name = "http-enabled", havingValue = "true")
public class AgenticAuthoringController {

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
            AiStreamAccessTokenService streamAccessTokenService) {
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
                null);
    }

    @GetMapping("/component-capabilities")
    public ResponseEntity<AgenticAuthoringComponentCapabilitiesResult> listComponentCapabilities() {
        return ResponseEntity.ok(componentCapabilitiesService.listCapabilities());
    }

    @PostMapping("/resource-candidates")
    public ResponseEntity<AgenticAuthoringResourceCandidatesResult> searchResourceCandidates(
            @RequestBody AgenticAuthoringResourceCandidatesRequest request) {
        return ResponseEntity.ok(resourceDiscoveryService.search(request));
    }

    @PostMapping("/turn/stream/start")
    public ResponseEntity<AgenticAuthoringTurnStreamStartResponse> startTurnStream(
            @RequestBody AgenticAuthoringTurnStreamRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        requireTurnStreamSupport();
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
            AgenticAuthoringPlanResult result = planService.generateMinimalFormPlan(request, tenantId, userId, environment);
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
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        try {
            AgenticAuthoringPreviewResult result = previewService.preview(request, tenantId, userId, environment);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException | IOException ex) {
            return ResponseEntity.badRequest().body(AgenticAuthoringDryRunErrorResponse.configurationInvalid(ex.getMessage()));
        }
    }

    @PostMapping("/page-apply")
    public ResponseEntity<?> applyPage(
            @RequestBody AgenticAuthoringApplyRequest request,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestHeader(value = "X-Updated-By", required = false) String updatedBy,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        try {
            AgenticAuthoringApplyResult result = applyService.apply(request, tenantId, userId, environment, updatedBy, ifMatch);
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
