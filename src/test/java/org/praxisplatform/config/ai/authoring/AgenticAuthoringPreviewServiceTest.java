package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringPreviewServiceTest {

    @Mock
    private AgenticAuthoringPlanService planService;

    @Mock
    private AgenticAuthoringPatchCompilerService patchCompilerService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void previewReturnsPlanAndCompiledPatchWhenBothStagesPass() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest("Crie um formulario", "openai", "gpt-5.4-mini", "test-key");
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("profileId", "create-minimal-form");
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("version", "1.0.0");
        when(planService.generateMinimalFormPlan(request, "tenant", "user", "local"))
                .thenReturn(new AgenticAuthoringPlanResult(true, List.of(), List.of("minimal-form-plan-only"), plan));
        when(patchCompilerService.compile(new AgenticAuthoringCompileRequest(plan)))
                .thenReturn(new AgenticAuthoringCompileResult(true, List.of(), List.of("compiled-from-minimal-form-plan"), patch));

        AgenticAuthoringPreviewResult result = service().preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.warnings()).contains("minimal-form-plan-only", "compiled-from-minimal-form-plan");
        assertThat(result.minimalFormPlan()).isSameAs(plan);
        assertThat(result.compiledFormPatch()).isSameAs(patch);
    }

    @Test
    void previewSkipsCompileWhenPlanIsInvalid() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest("Crie um formulario", null, null, null);
        ObjectNode plan = objectMapper.createObjectNode();
        when(planService.generateMinimalFormPlan(request, null, null, null))
                .thenReturn(new AgenticAuthoringPlanResult(false, List.of("titulo is required"), List.of(), plan));

        AgenticAuthoringPreviewResult result = service().preview(request, null, null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).containsExactly("titulo is required");
        assertThat(result.warnings()).contains("compile-skipped-invalid-minimal-form-plan");
        assertThat(result.compiledFormPatch().isMissingNode()).isTrue();
    }

    @Test
    void previewAllowsModifyIntentAndPassesCurrentPageToCompiler() throws Exception {
        ObjectNode currentPage = objectMapper.createObjectNode();
        currentPage.putArray("widgets");
        AgenticAuthoringIntentResolutionResult intent = modifyAddFieldIntent();
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Adicione observacao interna",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                currentPage,
                intent);
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("profileId", "create-minimal-form");
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("profileId", "modify-existing-form");
        when(planService.generateMinimalFormPlan(request, "tenant", "user", "local"))
                .thenReturn(new AgenticAuthoringPlanResult(true, List.of(), List.of(), plan));
        when(patchCompilerService.compile(new AgenticAuthoringCompileRequest(plan, currentPage, intent)))
                .thenReturn(new AgenticAuthoringCompileResult(true, List.of(), List.of("compiled-as-current-page-modification"), patch));

        AgenticAuthoringPreviewResult result = service().preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.compiledFormPatch()).isSameAs(patch);
        assertThat(result.warnings()).contains("compiled-as-current-page-modification");
    }

    @Test
    void previewAllowsRemoveFieldIntentAndPassesCurrentPageToCompiler() throws Exception {
        ObjectNode currentPage = objectMapper.createObjectNode();
        currentPage.putArray("widgets");
        AgenticAuthoringIntentResolutionResult intent = removeFieldIntent();
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Remova observacao interna",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                currentPage,
                intent);
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("profileId", "create-minimal-form");
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("profileId", "modify-existing-form");
        when(planService.generateMinimalFormPlan(request, "tenant", "user", "local"))
                .thenReturn(new AgenticAuthoringPlanResult(true, List.of(), List.of(), plan));
        when(patchCompilerService.compile(new AgenticAuthoringCompileRequest(plan, currentPage, intent)))
                .thenReturn(new AgenticAuthoringCompileResult(true, List.of(), List.of("local-transient-fields-removed-only"), patch));

        AgenticAuthoringPreviewResult result = service().preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.compiledFormPatch()).isSameAs(patch);
        assertThat(result.warnings()).contains("local-transient-fields-removed-only");
    }

    private AgenticAuthoringPreviewService service() {
        return new AgenticAuthoringPreviewService(planService, patchCompilerService);
    }

    private AgenticAuthoringIntentResolutionResult modifyAddFieldIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "form",
                "add_field",
                "create-minimal-form",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "funcionarios-form",
                        "praxis-dynamic-form",
                        "/api/human-resources/funcionarios",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request",
                        "/api/human-resources/funcionarios",
                        "post"),
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request",
                        "/api/human-resources/funcionarios",
                        "POST",
                        0.95,
                        "matched funcionarios",
                        List.of("funcionarios")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult removeFieldIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "remove",
                "form",
                "remove_field",
                "create-minimal-form",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "funcionarios-form",
                        "praxis-dynamic-form",
                        "/api/human-resources/funcionarios",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request",
                        "/api/human-resources/funcionarios",
                        "post"),
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request",
                        "/api/human-resources/funcionarios",
                        "POST",
                        0.95,
                        "matched funcionarios",
                        List.of("funcionarios")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }
}
