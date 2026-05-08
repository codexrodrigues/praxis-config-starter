package org.praxisplatform.config.ai.authoring;

import java.util.List;
import org.praxisplatform.config.service.AiPrincipalContext;

public record AgenticAuthoringToolLoopContext(
        String phase,
        int stepIndex,
        String routeClass,
        AgenticAuthoringTurnStreamRequest request,
        AiPrincipalContext principalContext,
        AgenticAuthoringIntentResolutionResult intentResolution,
        AgenticAuthoringPreviewResult preview,
        List<AgenticAuthoringToolLoopStep> trace
) {
}
