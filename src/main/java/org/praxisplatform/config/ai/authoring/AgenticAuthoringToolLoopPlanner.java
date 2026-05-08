package org.praxisplatform.config.ai.authoring;

import java.util.Optional;

public interface AgenticAuthoringToolLoopPlanner {

    Optional<AgenticAuthoringToolCall> nextTool(AgenticAuthoringToolLoopContext context);
}
