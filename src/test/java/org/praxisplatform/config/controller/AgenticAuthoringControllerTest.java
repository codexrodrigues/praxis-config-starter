package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactSource;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringCompileRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringCompileResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunErrorResponse;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentResolutionRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentResolutionResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentResolverService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPatchCompilerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewService;
import org.praxisplatform.config.service.UserConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringControllerTest {

    @Mock
    private AgenticAuthoringDryRunService dryRunService;

    @Mock
    private AgenticAuthoringArtifactSource artifactSource;

    @Mock
    private AgenticAuthoringIntentResolverService intentResolverService;

    @Mock
    private AgenticAuthoringPlanService planService;

    @Mock
    private AgenticAuthoringPatchCompilerService patchCompilerService;

    @Mock
    private AgenticAuthoringPreviewService previewService;

    @Mock
    private AgenticAuthoringApplyService applyService;

    @Test
    void dryRunReturnsConfiguredArtifactResult() throws Exception {
        AgenticAuthoringDryRunResult expected = new AgenticAuthoringDryRunResult(
                true,
                List.of(),
                List.of(),
                List.of(),
                com.fasterxml.jackson.databind.node.MissingNode.getInstance()
        );
        when(dryRunService.run(artifactSource)).thenReturn(expected);

        ResponseEntity<?> response = controller().runDryRun();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void dryRunReturnsBadRequestWhenArtifactsAreNotConfigured() throws Exception {
        when(dryRunService.run(artifactSource)).thenThrow(new IOException("artifact not found"));

        ResponseEntity<?> response = controller().runDryRun();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(AgenticAuthoringDryRunErrorResponse.class);
        AgenticAuthoringDryRunErrorResponse body = (AgenticAuthoringDryRunErrorResponse) response.getBody();
        assertThat(body.valid()).isFalse();
        assertThat(body.failureCodes()).containsExactly("DRY_RUN_CONFIGURATION_INVALID");
        assertThat(body.message()).isEqualTo("artifact not found");
    }

    @Test
    void minimalFormPlanReturnsGeneratedPlan() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest("Crie um formulario", "openai", "gpt-5.4-mini", "test-key");
        AgenticAuthoringPlanResult expected = new AgenticAuthoringPlanResult(
                true,
                List.of(),
                List.of("minimal-form-plan-only"),
                com.fasterxml.jackson.databind.node.MissingNode.getInstance()
        );
        when(planService.generateMinimalFormPlan(request, "tenant", "user", "local")).thenReturn(expected);

        ResponseEntity<?> response = controller().generateMinimalFormPlan(request, "tenant", "user", "local");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void intentResolutionReturnsResolvedIntent() {
        AgenticAuthoringIntentResolutionRequest request = new AgenticAuthoringIntentResolutionRequest(
                "Crie um formulario para funcionarios",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                com.fasterxml.jackson.databind.node.MissingNode.getInstance(),
                null,
                null,
                null,
                null);
        AgenticAuthoringIntentResolutionResult expected = new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "form",
                "create_minimal_form",
                "create-minimal-form",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                null,
                List.of(),
                new org.praxisplatform.config.ai.authoring.AgenticAuthoringGateResult(
                        "candidate-eligibility@0.1.0",
                        "eligible",
                        List.of()),
                List.of(),
                List.of("metadata-probe-not-run"),
                List.of(),
                com.fasterxml.jackson.databind.node.MissingNode.getInstance());
        when(intentResolverService.resolve(request)).thenReturn(expected);

        ResponseEntity<?> response = controller().resolveIntent(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void compiledFormPatchReturnsCompiledPatch() throws Exception {
        AgenticAuthoringCompileRequest request = new AgenticAuthoringCompileRequest(
                com.fasterxml.jackson.databind.node.MissingNode.getInstance());
        AgenticAuthoringCompileResult expected = new AgenticAuthoringCompileResult(
                true,
                List.of(),
                List.of("compiled-from-minimal-form-plan"),
                com.fasterxml.jackson.databind.node.MissingNode.getInstance()
        );
        when(patchCompilerService.compile(request)).thenReturn(expected);

        ResponseEntity<?> response = controller().compileFormPatch(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void pagePreviewReturnsComposedPreview() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest("Crie um formulario", "openai", "gpt-5.4-mini", "test-key");
        AgenticAuthoringPreviewResult expected = new AgenticAuthoringPreviewResult(
                true,
                List.of(),
                List.of("compiled-from-minimal-form-plan"),
                com.fasterxml.jackson.databind.node.MissingNode.getInstance(),
                com.fasterxml.jackson.databind.node.MissingNode.getInstance()
        );
        when(previewService.preview(request, "tenant", "user", "local")).thenReturn(expected);

        ResponseEntity<?> response = controller().previewPage(request, "tenant", "user", "local");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void pageApplyReturnsPersistedResultWithEtag() {
        AgenticAuthoringApplyRequest request = new AgenticAuthoringApplyRequest(
                com.fasterxml.jackson.databind.node.MissingNode.getInstance(),
                "praxis-dynamic-page",
                "page",
                "user",
                null);
        AgenticAuthoringApplyResult expected = new AgenticAuthoringApplyResult(
                true,
                "praxis-dynamic-page",
                "page",
                "local",
                "user",
                1L,
                "00000000-0000-0000-0000-000000000123",
                com.fasterxml.jackson.databind.node.MissingNode.getInstance(),
                com.fasterxml.jackson.databind.node.MissingNode.getInstance(),
                List.of("persisted-page-payload-from-compiled-form-patch"));
        when(applyService.apply(request, "tenant", "user", "local", "author", "\"current\"")).thenReturn(expected);

        ResponseEntity<?> response = controller().applyPage(request, "tenant", "user", "local", "author", "\"current\"");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"00000000-0000-0000-0000-000000000123\"");
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void pageApplyReturnsPreconditionFailedForStaleEtag() {
        AgenticAuthoringApplyRequest request = new AgenticAuthoringApplyRequest(
                com.fasterxml.jackson.databind.node.MissingNode.getInstance(),
                "praxis-dynamic-page",
                "page",
                "user",
                null);
        when(applyService.apply(request, "tenant", "user", "local", "author", "\"stale\""))
                .thenThrow(new UserConfigService.PreconditionFailedException("stale configuration"));

        ResponseEntity<?> response = controller().applyPage(request, "tenant", "user", "local", "author", "\"stale\"");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(response.getBody()).isEqualTo("stale configuration");
    }

    private AgenticAuthoringController controller() {
        return new AgenticAuthoringController(dryRunService, artifactSource, intentResolverService, planService, patchCompilerService, previewService, applyService);
    }
}
