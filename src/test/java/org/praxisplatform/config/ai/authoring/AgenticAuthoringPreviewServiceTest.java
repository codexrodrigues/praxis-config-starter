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

    private AgenticAuthoringPreviewService service() {
        return new AgenticAuthoringPreviewService(planService, patchCompilerService);
    }
}
