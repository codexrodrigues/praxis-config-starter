package org.praxisplatform.config.ai.authoring;

import org.praxisplatform.config.service.AiPrincipalContext;

public interface AgenticAuthoringPreIntentToolPlanningService {

    AgenticAuthoringPreIntentToolPlanningResult plan(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext);
}
