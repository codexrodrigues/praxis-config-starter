package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AgenticAuthoringPreviewService {

    private final AgenticAuthoringPlanService planService;
    private final AgenticAuthoringPatchCompilerService patchCompilerService;

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService) {
        this.planService = Objects.requireNonNull(planService, "planService must not be null");
        this.patchCompilerService = Objects.requireNonNull(patchCompilerService, "patchCompilerService must not be null");
    }

    public AgenticAuthoringPreviewResult preview(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment) throws IOException {
        AgenticAuthoringPlanResult planResult =
                planService.generateMinimalFormPlan(request, tenantId, userId, environment);
        List<String> failureCodes = new ArrayList<>(planResult.failureCodes());
        List<String> warnings = new ArrayList<>(planResult.warnings());
        if (!planResult.valid()) {
            warnings.add("compile-skipped-invalid-minimal-form-plan");
            return new AgenticAuthoringPreviewResult(
                    false,
                    List.copyOf(failureCodes),
                    List.copyOf(warnings),
                    planResult.minimalFormPlan(),
                    MissingNode.getInstance()
            );
        }

        AgenticAuthoringCompileResult compileResult =
                patchCompilerService.compile(new AgenticAuthoringCompileRequest(planResult.minimalFormPlan()));
        failureCodes.addAll(compileResult.failureCodes());
        warnings.addAll(compileResult.warnings());
        return new AgenticAuthoringPreviewResult(
                planResult.valid() && compileResult.valid(),
                List.copyOf(failureCodes),
                List.copyOf(warnings),
                planResult.minimalFormPlan(),
                compileResult.compiledFormPatch()
        );
    }
}
