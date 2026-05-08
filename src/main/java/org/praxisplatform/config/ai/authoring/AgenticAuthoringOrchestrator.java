package org.praxisplatform.config.ai.authoring;

import org.praxisplatform.config.service.AiPrincipalContext;

public class AgenticAuthoringOrchestrator {

    private final AgenticAuthoringToolLoopExecutor toolLoopExecutor;

    public AgenticAuthoringOrchestrator(AgenticAuthoringToolLoopExecutor toolLoopExecutor) {
        this.toolLoopExecutor = toolLoopExecutor;
    }

    public AgenticAuthoringToolLoopResult runToolLoop(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            String routeClass) {
        return toolLoopExecutor.run(request, principalContext, intentResolution, preview, routeClass);
    }
}
