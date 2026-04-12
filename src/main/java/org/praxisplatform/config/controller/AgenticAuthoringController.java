package org.praxisplatform.config.controller;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactSource;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunErrorResponse;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringCompileRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringCompileResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPatchCompilerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.praxisplatform.config.service.UserConfigService;

@RestController
@RequestMapping("/api/praxis/config/ai/authoring")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "praxis.ai.authoring", name = "http-enabled", havingValue = "true")
public class AgenticAuthoringController {

    private final AgenticAuthoringDryRunService dryRunService;
    private final AgenticAuthoringArtifactSource artifactSource;
    private final AgenticAuthoringPlanService planService;
    private final AgenticAuthoringPatchCompilerService patchCompilerService;
    private final AgenticAuthoringPreviewService previewService;
    private final AgenticAuthoringApplyService applyService;

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
}
