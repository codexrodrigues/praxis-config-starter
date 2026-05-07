package org.praxisplatform.config.ai.authoring;

import org.praxisplatform.config.service.AiPrincipalContext;

interface AgenticAuthoringToolExecutor {

    AgenticAuthoringToolDefinition definition();

    AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call);

    default AgenticAuthoringToolResult execute(
            AgenticAuthoringToolCall call,
            AiPrincipalContext principalContext) {
        return execute(call);
    }
}
