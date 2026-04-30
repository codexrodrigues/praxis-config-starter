package org.praxisplatform.config.ai.authoring;

interface AgenticAuthoringToolExecutor {

    AgenticAuthoringToolDefinition definition();

    AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call);
}
